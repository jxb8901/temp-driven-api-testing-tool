package att.server;

import att.worker.WorkerRequest;
import att.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Owns the single-node job plane and its bounded Worker, inspector, and SSE executors. */
final class ServerRuntime implements AutoCloseable {
    static final ObjectMapper JSON=new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
    static final Pattern JOB_ID=Pattern.compile("J[0-9A-F]{16}");
    static final Pattern DRAFT_ID=Pattern.compile("D[0-9A-F]{32}");
    static final Pattern QUICK_LOAD_DRAFT_ID=Pattern.compile("L[0-9A-F]{32}");
    static final Pattern ADVANCED_LOAD_DRAFT_ID=Pattern.compile("A[0-9A-F]{32}");
    private static final Pattern INLINE_LOAD_DURATION=Pattern.compile("^([0-9]+)(ms|s|m|h)$");
    private static final Pattern INLINE_LOAD_RATE=Pattern.compile("^([1-9][0-9]*(?:\\.[0-9]+)?)/(s|m)$");
    static final int MAX_ACTIVE_DRAFTS=128, MAX_DRAFTS_PER_PRINCIPAL=16;
    static final long DRAFT_TTL_MILLIS=TimeUnit.MINUTES.toMillis(10);
    static final int MAX_STREAM_OBSERVERS=32;
    final ServerConfig config;
    final JobStore store;
    final ThreadPoolExecutor workers,streams,inspectors;
    final ConcurrentHashMap<String,Job> jobs=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,FutureTask<Void>> tasks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,AdmissionLease> leases=new ConcurrentHashMap<>();
    private final AtomicLong completed=new AtomicLong();
    private final Semaphore loadSlots,admissionSlots;
    final Semaphore streamSlots=new Semaphore(MAX_STREAM_OBSERVERS,true);
    private final ScheduledExecutorService retention;
    private final WorkerProcessLauncher processLauncher;
    private final Clock clock;
    private final ConcurrentHashMap<Process,Boolean> inspectionProcesses=new ConcurrentHashMap<>();
    private final Object draftLock=new Object();
    private final ConcurrentHashMap<String,DebugDraft> debugDrafts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,QuickLoadDraft> quickLoadDrafts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,AdvancedLoadDraftState> advancedLoadDrafts=new ConcurrentHashMap<>();
    private final byte[] cursorKey=new byte[32];
    private final SecureRandom cursorRandom=new SecureRandom();
    private volatile String webInfLibs;
    ServerRuntime(ServerConfig config,String webInfLibs) throws Exception {
        this(config,webInfLibs,ProcessBuilder::start);
    }
    ServerRuntime(ServerConfig config,String webInfLibs,WorkerProcessLauncher processLauncher) throws Exception {
        this(config,webInfLibs,processLauncher,Clock.systemUTC());
    }
    ServerRuntime(ServerConfig config,String webInfLibs,WorkerProcessLauncher processLauncher,Clock clock) throws Exception {
        if(Runtime.version().feature()<17)throw new IllegalStateException("ATT Server requires Java 17 or later; detected Java "+Runtime.version().feature());
        this.config=config;this.webInfLibs=webInfLibs;this.processLauncher=processLauncher;this.clock=java.util.Objects.requireNonNull(clock);this.store=new JobStore(config);this.loadSlots=new Semaphore(config.maxConcurrentLoad,true);this.admissionSlots=new Semaphore(config.maxConcurrent+config.queuedLimit,true);
        cursorRandom.nextBytes(cursorKey);
        java.util.concurrent.BlockingQueue<Runnable> queue=config.queuedLimit==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(config.queuedLimit);
        workers=new ThreadPoolExecutor(config.maxConcurrent,config.maxConcurrent,0,TimeUnit.MILLISECONDS,queue,r->{Thread t=new Thread(r,"att-server-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        streams=new ThreadPoolExecutor(0,MAX_STREAM_OBSERVERS,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"att-server-sse");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        java.util.concurrent.BlockingQueue<Runnable> inspectionQueue=config.inspection.queuedLimit==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(config.inspection.queuedLimit);
        inspectors=new ThreadPoolExecutor(config.inspection.maxConcurrent,config.inspection.maxConcurrent,0,TimeUnit.MILLISECONDS,inspectionQueue,r->{Thread t=new Thread(r,"att-server-inspector");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        retention=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-retention");t.setDaemon(true);return t;});
        recover();cleanupExpiredJobsSafely();
        retention.scheduleWithFixedDelay(this::cleanupExpiredJobsSafely,1,1,TimeUnit.HOURS);
        retention.scheduleWithFixedDelay(this::expireDraftsSafely,1,1,TimeUnit.MINUTES);
    }
    private void recover() throws Exception {
        for(Map<String,Object> stale:store.staleWorkers()) {
            try {
                long pid=((Number)stale.get("pid")).longValue();ProcessHandle.of(pid).ifPresent(handle->{
                    String expected=(String)stale.get("start");String actual=handle.info().startInstant().map(Instant::toString).orElse("");
                    String command=handle.info().commandLine().orElse("");
                    if(expected!=null&&expected.equals(actual)&&command.contains("att.worker.WorkerMain"))handle.destroyForcibly();
                });
            } catch(Exception ignored) { }
        }
        for(String id:store.staleJobIds()) {
            store.interruptStale(id);
            Path journal=config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl");
            JobEvents events=new JobEvents(journal,config.maxEventsPerJob);
            events.append("diagnostic",Map.of("code","ATT-SERVER-INTERRUPTED","summary","Job was interrupted by Server restart and was not rerun"));
            events.append("result",Map.of("jobId",id,"status","ERROR"));
        }
    }
    Map<String,Object> submit(String command,JsonNode input,String principal) throws Exception {
        return submit(command,input,principal,false);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft) throws Exception {
        long requestReceivedNanos=System.nanoTime();
        if(!List.of("run","debug","load","validate").contains(command))throw new IllegalArgumentException("Unsupported job command");
        if(!serverIssuedDraft&&(input.has("draftId")||input.has("inlineDebugInput")||input.has("inlineLoadScenario")
                ||input.has("expectedRevisionDigest")||input.has("draftResourceId")))
            throw new IllegalArgumentException("Inline execution fields require a server-issued draft");
        if(serverIssuedDraft&&"load".equals(command)) {
            requireInlineLoadEnabled();
            JsonNode scenarioNode=input.get("inlineLoadScenario");
            if(scenarioNode==null||!scenarioNode.isObject())throw new IllegalArgumentException("A validated inline Load scenario is required");
            @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(scenarioNode,Map.class);
            enforceInlineLoadLimits(scenario);
        }
        String packageId=required(input,"packageId");Path root=config.packages.get(packageId);
        if(root==null)throw new IllegalArgumentException("Unknown packageId");
        validatePackageRoot(root);
        WorkerRequest request=JSON.treeToValue(input,WorkerRequest.class);
        request.protocolVersion="att-worker/v1";request.command=command;request.packageRoot=root.toString();
        request.outputDirectory=null;request.config=safeRelative(request.config,"config");request.environment=safeText(request.environment,128,"environment");
        request.runId=safeText(request.runId,128,"runId");request.debugId=safeText(request.debugId,128,"debugId");request.suiteDirectory=safeRelative(request.suiteDirectory,"suiteDirectory");
        request.scenario=safeRelative(request.scenario,"scenario");request.debugInput=safeRelative(request.debugInput,"debugInput");
        request.unsafeFailureDetails=false;
        request.suites=safePaths(request.suites,"suites");
        validatePackagePath(root,request.config,"config");validatePackagePath(root,request.suiteDirectory,"suiteDirectory");
        validatePackagePath(root,request.scenario,"scenario");validatePackagePath(root,request.debugInput,"debugInput");
        if(request.suites!=null)for(String suite:request.suites)validatePackagePath(root,suite,"suites");
        if("debug".equals(command))validateTarget(request.target);
        if("load".equals(command)&&request.target!=null)validateTarget(request.target);
        boolean loadAdmission="load".equals(command);
        if(loadAdmission&&!loadSlots.tryAcquire()){store.audit(principal,"SUBMIT",null,packageId,"REJECTED_LOAD_CAPACITY");throw new QueueFullException("Load capacity is full; retry after a Load job completes");}
        if(!admissionSlots.tryAcquire()){if(loadAdmission)loadSlots.release();store.audit(principal,"SUBMIT",null,packageId,"REJECTED_QUEUE_FULL");throw new QueueFullException("Worker capacity is full; retry after a job completes");}
        AdmissionLease lease=new AdmissionLease(admissionSlots,loadAdmission?loadSlots:null);
        String id=null;Path jobPath=null;boolean persisted=false,handedOff=false;
        try {
            id="J"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();request.jobId=id;
            jobPath=config.dataDir.resolve("jobs").resolve(id);Files.createDirectories(jobPath.resolve("output"));final Path activeJobPath=jobPath;
            JobEvents events=new JobEvents(jobPath.resolve("events.jsonl"),config.maxEventsPerJob);
            Job job=new Job(id,command,packageId,principal,request,events);job.requestReceivedNanos=requestReceivedNanos;String summary=JSON.writeValueAsString(Map.of("command",command,"packageId",packageId));
            store.insert(job,summary);persisted=true;jobs.put(id,job);append(job,"status",Map.of("jobId",id,"status","QUEUED"));
            FutureTask<Void> task=new FutureTask<>(()->{execute(job,activeJobPath,lease);return null;});tasks.put(id,task);leases.put(id,lease);
            job.admittedNanos=System.nanoTime();
            try { workers.execute(task); }
            catch(java.util.concurrent.RejectedExecutionException full){throw new QueueFullException("Worker capacity is full; retry after a job completes");}
            handedOff=true;
            try{store.audit(principal,"SUBMIT",id,packageId,"ACCEPTED");}catch(Exception auditFailure){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to persist accepted job audit record");}
            return job.view();
        } catch(Exception failure) {
            if(!handedOff){if(id!=null){FutureTask<Void> task=tasks.remove(id);if(task!=null)task.cancel(false);jobs.remove(id);leases.remove(id);if(persisted)try{store.deleteJob(id);}catch(Exception ignored){}if(jobPath!=null)try{deleteTree(jobPath);}catch(Exception ignored){}}
                lease.release();if(failure instanceof QueueFullException)try{store.audit(principal,"SUBMIT",id,packageId,"REJECTED_QUEUE_FULL");}catch(Exception ignored){} }
            throw failure;
        }
    }
    private void execute(Job job,Path jobPath,AdmissionLease lease) {
        job.workerStartedNanos=System.nanoTime();
        try {
            Process process;
            synchronized(job) {
                // Publish the process while holding the same lock used by cancel(). A
                // cancellation that wins before launch prevents the Worker from starting;
                // one that follows launch always sees and terminates that exact process.
                if(job.terminal())return;
                transition(job,"PREPARING");
                validatePackageRoot(config.packages.get(job.packageId));
                Path libs=webInfLibs==null?null:Paths.get(webInfLibs);
                if(libs==null||!Files.isDirectory(libs))throw new IllegalStateException("Tomcat must deploy the WAR as an exploded application so WEB-INF/lib is available to the Worker launcher");
                job.request.outputDirectory=jobPath.resolve("output").toRealPath().toString();
                List<String> classpathEntries=new ArrayList<>();classpathEntries.add(libs.resolve("*").toString());
                for(Path libraryDir:config.workerLibraryDirs)classpathEntries.add(libraryDir.resolve("*").toString());
                String cp=String.join(java.io.File.pathSeparator,classpathEntries);
                List<String> command=new ArrayList<>();command.add(config.javaExecutable.toString());
                if(config.workerHeapInitialMb>0)command.add("-Xms"+config.workerHeapInitialMb+"m");
                if(config.workerHeapMaxMb>0)command.add("-Xmx"+config.workerHeapMaxMb+"m");
                command.add("-cp");command.add(cp);command.add("att.worker.WorkerMain");
                ProcessBuilder builder=new ProcessBuilder(command);
                builder.directory(config.packages.get(job.packageId).toFile());
                job.workerSpawnStartedNanos=System.nanoTime();process=processLauncher.start(builder);job.workerSpawnedNanos=System.nanoTime();job.process=process;job.workerPid=process.pid();job.workerStartTime=process.info().startInstant().orElse(Instant.now());job.startedAt=Instant.now();transition(job,"RUNNING");
            }
            Thread stderr=new Thread(()->{try(var in=process.getErrorStream()){byte[] buf=new byte[8192];while(in.read(buf)>=0){/* drain; protocol and safe diagnostics use stdout */}}catch(Exception ignored){}});stderr.setDaemon(true);stderr.start();
            try(var out=process.getOutputStream()){out.write(JSON.writeValueAsBytes(job.request));out.write('\n');out.flush();}
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=readBoundedLine(reader,1024*1024))!=null){if("\u0000OVERSIZED".equals(line)){append(job,"diagnostic",Map.of("code","ATT-SERVER-WORKER-EVENT-TOO-LARGE","summary","Worker event exceeded the protocol limit"));continue;}handleWorkerEvent(job,line);}}
            int exit=process.waitFor();job.workerTerminatedNanos=System.nanoTime();job.exitCode=exit;
            if(!job.resultReceived&&!job.terminal()){job.diagnosticJson=JSON.writeValueAsString(Map.of("code","ATT-SERVER-WORKER-EXITED","summary","Worker exited before delivering a terminal result","exitCode",exit));append(job,"diagnostic",JSON.readValue(job.diagnosticJson,Map.class));finish(job,"ERROR",exit==0?3:exit);}
            else if(!job.terminal()) {String state=exit==0?"PASS":"FAIL";finish(job,state,exit);}
        } catch(InterruptedException e){Thread.currentThread().interrupt();stopAfterFailure(job);if(!job.terminal())finishQuietly(job,"CANCELLED",143,"ATT-SERVER-CANCELLED","Worker was cancelled");}
          catch(Exception e){stopAfterFailure(job);if(!job.terminal())finishQuietly(job,"ERROR",3,"ATT-SERVER-WORKER-FAILED",safeMessage(e));}
        finally {if(job.process!=null&&!job.process.isAlive()){if(job.workerTerminatedNanos==0)job.workerTerminatedNanos=System.nanoTime();job.process=null;try{store.update(job);}catch(Exception ignored){}}tasks.remove(job.id);leases.remove(job.id);if(job.terminal())jobs.remove(job.id,job);lease.release();completed.incrementAndGet();}
    }
    private void stopAfterFailure(Job job) {
        Process process=job.process;
        if(process==null||!process.isAlive())return;
        terminateTree(process);
        try {if(!process.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS)){forceTree(process);process.waitFor();}}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();forceTree(process);}
    }
    void handleWorkerEvent(Job job,String line) throws Exception {
        JsonNode event=JSON.readTree(line);if(event==null||!event.isObject())return;
        String type=event.path("type").asText("").toUpperCase();Map<String,Object> data=JSON.convertValue(event,Map.class);data.remove("type");data.remove("jobId");Map<String,Object> safe=att.worker.internal.DiagnosticSanitizer.sanitize(data);
        long eventNanos=System.nanoTime();
        if("STATUS".equals(type)&&job.workerReadyNanos==0)job.workerReadyNanos=eventNanos;
        if(("PROGRESS".equals(type)||"LOG".equals(type))&&job.executionReadyNanos==0)job.executionReadyNanos=eventNanos;
        switch(type){case "STATUS"->{String status=event.path("status").asText("");if("RUNNING".equals(status)&&"PREPARING".equals(job.status))transition(job,"RUNNING");}
            case "PROGRESS"->append(job,"progress",safe);case "LOG"->append(job,"log",safe);case "DIAGNOSTIC"->{job.diagnosticJson=JSON.writeValueAsString(safe);store.update(job);append(job,"diagnostic",safe);}
            case "RESULT"->{job.resultReceivedNanos=eventNanos;Object workerMetrics=safe.get("workerMetrics");if(workerMetrics instanceof Map<?,?>)job.workerMetrics=(Map<String,Object>)workerMetrics;String status=event.path("status").asText("ERROR");int code=event.path("exitCode").asInt(3);Object result=safe.get("result");job.resultJson=result==null?"{}":JSON.writeValueAsString(result);job.resultReceived=true;finish(job,normalizeTerminal(status),code);}
            default->append(job,"log",Map.of("message","Worker emitted an unrecognized event"));}
    }
    private String normalizeTerminal(String s){return List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(s)?s:"ERROR";}
    String cancel(String id,String principal) throws Exception {
        Job j=jobs.get(id);if(j==null){Map<String,Object> stored=store.get(id);if(stored==null||!JOB_ID.matcher(id).matches())throw new NotFoundException();return String.valueOf(stored.get("status"));}
        synchronized(j){if(j.terminal())return j.status;append(j,"status",Map.of("jobId",id,"status","CANCEL_REQUESTED"));
            Process p=j.process;if(p==null){FutureTask<Void> task=tasks.remove(id);if(task!=null){boolean cancelled=task.cancel(false);workers.remove(task);if(cancelled){AdmissionLease lease=leases.remove(id);if(lease!=null)lease.release();}}finish(j,"CANCELLED",143);jobs.remove(id,j);}
            else {terminateTree(p);try{if(!p.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS)){forceTree(p);p.waitFor();}}catch(InterruptedException e){Thread.currentThread().interrupt();forceTree(p);}finish(j,"CANCELLED",143);}}
        store.audit(principal,"CANCEL",id,j.packageId,"CANCELLED");return j.status;
    }
    Job job(String id){if(!JOB_ID.matcher(id).matches())throw new NotFoundException();Job j=jobs.get(id);if(j==null)throw new NotFoundException();return j;}
    Map<String,Object> jobRecord(String id) throws Exception {Job j=jobs.get(id);if(j!=null)return j.view();if(!JOB_ID.matcher(id).matches())throw new NotFoundException();Map<String,Object> record=store.get(id);if(record==null)throw new NotFoundException();record.remove("resultJson");record.remove("diagnosticJson");String performance=(String)record.remove("performanceJson");if(performance!=null&&!performance.isBlank())record.put("performance",JSON.readValue(performance,Map.class));return record;}
    Map<String,Object> resultRecord(String id) throws Exception {Map<String,Object> record=store.get(id);if(record==null)throw new NotFoundException();Map<String,Object> out=new LinkedHashMap<>();out.put("job",jobRecord(id));out.put("result",record.get("resultJson")==null?null:publicJson(id,(String)record.get("resultJson")));out.put("diagnostic",record.get("diagnosticJson")==null?null:publicJson(id,(String)record.get("diagnosticJson")));return out;}
    List<Map<String,Object>> packages(){List<Map<String,Object>> out=new ArrayList<>();config.packages.forEach((id,path)->out.add(Map.of("packageId",id,"name",path.getFileName().toString())));return out;}
    Map<String,Object> packageView(String id){Path root=config.packages.get(id);if(root==null)throw new NotFoundException();return Map.of("packageId",id,"name",root.getFileName().toString());}
    Map<String,Object> inspectResource(String packageId,String action,String type,String resourceId,String query,int requestedLimit,String cursor,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(!List.of("list","detail","source").contains(action))throw new IllegalArgumentException("Unsupported inspection action");
        if(type!=null&&!List.of("case","template","flow","tool").contains(type))throw new IllegalArgumentException("Unsupported resource type");
        String normalizedQuery=query==null?"":query.trim();if(normalizedQuery.length()>200||containsControl(normalizedQuery))throw new IllegalArgumentException("query must contain at most 200 printable characters");
        int limit=requestedLimit==0?att.resource.PackageResourceInspector.DEFAULT_PAGE_SIZE:requestedLimit;
        if(limit<1||limit>att.resource.PackageResourceInspector.MAX_PAGE_SIZE)throw new IllegalArgumentException("limit must be between 1 and 100");
        Cursor prior=cursor==null||cursor.isEmpty()?null:decodeCursor(cursor,packageId,type,normalizedQuery,principal);
        WorkerRequest request=new WorkerRequest();request.protocolVersion="att-worker/v1";request.jobId="I"+UUID.randomUUID().toString().replace("-","");request.command="inspect";request.packageRoot=root.toString();request.config="config/config.yaml";
        request.inspectionAction=action;request.inspectionType=type;request.inspectionResourceId=resourceId;request.inspectionQuery=normalizedQuery;request.inspectionOffset=prior==null?0:prior.offset;request.inspectionLimit=limit;
        request.expectedRevisionDigest=prior==null?null:prior.revision;request.safeTextSources=config.inspection.safeTextSources(packageId);request.maxSourceBytes=config.inspection.maxSourceBytes;request.maxResponseBytes=config.inspection.maxResponseBytes;
        Map<String,Object> result=executeInspection(root,request);
        Object revision=result.remove("revisionDigest");
        if("list".equals(action)) {
            Object next=result.remove("nextOffset");
            if(next instanceof Number&&revision instanceof String)result.put("nextCursor",encodeCursor(packageId,type,normalizedQuery,((Number)next).intValue(),(String)revision,principal));
            else result.put("nextCursor",null);
        }
        return result;
    }
    Map<String,Object> inspectDebugForm(String packageId,String type,String resourceId,String environment,String principal) throws Exception {
        Map<String,Object> result=inspectDebugFormInternal(packageId,type,resourceId,environment,principal);
        result.remove("revisionDigest");
        return result;
    }
    Map<String,Object> inspectQuickLoadForm(String packageId,String type,String resourceId,String model,String environment,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        if(!"virtualUsers".equals(model)&&!"arrivalRate".equals(model))throw new IllegalArgumentException("model is invalid");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>512)throw new IllegalArgumentException("resourceId is invalid");
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="quick-load-form";request.inspectionType=type;request.inspectionResourceId=resourceId;request.loadModel=model;
        Map<String,Object> result=executeInspection(root,request);result.remove("revisionDigest");result.remove("normalizedScenario");return result;
    }
    Map<String,Object> inspectQuickLoadPolicy(String packageId,String model,String environment,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        if(!"virtualUsers".equals(model)&&!"arrivalRate".equals(model))throw new IllegalArgumentException("model is invalid");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="quick-load-policy";request.loadModel=model;
        Map<String,Object> result=executeInspection(root,request);result.remove("revisionDigest");return result;
    }
    private Map<String,Object> inspectDebugFormInternal(String packageId,String type,String resourceId,String environment,
                                                        String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>512)throw new IllegalArgumentException("resourceId is invalid");
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="debug-form";request.inspectionType=type;request.inspectionResourceId=resourceId;
        return executeInspection(root,request);
    }
    Map<String,Object> createDebugDraft(JsonNode input,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        if(!config.inspection.enabled)throw new NotFoundException();
        requireObject(input,"A JSON object is required");
        requireOnlyFields(input,"packageId","environment","target","input");
        String packageId=required(input,"packageId");Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        String environment=optionalText(input,"environment");if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        JsonNode targetNode=input.get("target");requireObject(targetNode,"target must be a JSON object");requireOnlyFields(targetNode,"type","id");
        String type=required(targetNode,"type"),logicalId=required(targetNode,"id");
        if(!List.of("template","flow","tool").contains(type)||logicalId.length()>512||containsControl(logicalId))
            throw new IllegalArgumentException("target is invalid");
        JsonNode inputNode=input.get("input");requireObject(inputNode,"input must be a JSON object");
        Map<String,Object> submitted=JSON.convertValue(inputNode,Map.class);
        if(!submitted.containsKey("schemaVersion"))submitted.put("schemaVersion",Version.DEBUG_SCHEMA);
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="debug-input";request.inspectionType=type;request.inspectionTargetId=logicalId;
        request.inlineDebugInput=submitted;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawNormalized=inspected.remove("normalizedInput");
        Object rawDigest=inspected.remove("revisionDigest");
        if(!(rawNormalized instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}"))
            throw new IllegalStateException("Debug validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> normalized=(Map<String,Object>)JSON.convertValue(rawNormalized,Map.class);
        Object rawResource=inspected.get("resource"),rawTarget=inspected.get("target");
        if(!(rawResource instanceof Map)||!(rawTarget instanceof Map))throw new IllegalStateException("Debug validation Worker returned an invalid target");
        @SuppressWarnings("unchecked") Map<String,Object> resource=(Map<String,Object>)rawResource;
        String resourceId=String.valueOf(resource.get("resourceId"));
        @SuppressWarnings("unchecked") Map<String,Object> resolvedTarget=(Map<String,Object>)rawTarget;
        String resolvedType=String.valueOf(resolvedTarget.get("type")),resolvedId=String.valueOf(resolvedTarget.get("id"));
        DebugDraft draft=new DebugDraft("D"+UUID.randomUUID().toString().replace("-","").toUpperCase(),principal,packageId,
                environment,resolvedType,resolvedId,resourceId,(String)rawDigest,normalized,inspected,
                clock.instant().plusMillis(DRAFT_TTL_MILLIS));
        synchronized(draftLock) {
            expireDrafts();
            if(debugDrafts.size()>=MAX_ACTIVE_DRAFTS)throw new DraftCapacityException();
            long owned=debugDrafts.values().stream().filter(value->value.principal.equals(principal)).count();
            if(owned>=MAX_DRAFTS_PER_PRINCIPAL)throw new DraftCapacityException();
            debugDrafts.put(draft.id,draft);
        }
        return debugDraftView(draft);
    }
    Map<String,Object> createQuickLoadDraft(JsonNode input,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        requireObject(input,"A JSON object is required");
        requireOnlyFields(input,"packageId","environment","target","model","input","load","execution","testdata");
        String packageId=required(input,"packageId");Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        String environment=optionalText(input,"environment");if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        JsonNode targetNode=input.get("target");requireObject(targetNode,"target must be a JSON object");requireOnlyFields(targetNode,"type","id");
        String type=required(targetNode,"type"),logicalId=required(targetNode,"id");
        if(!List.of("template","flow","tool").contains(type)||logicalId.length()>512||containsControl(logicalId))
            throw new IllegalArgumentException("target is invalid");
        String model=required(input,"model");
        if(!List.of("virtualUsers","arrivalRate").contains(model))throw new IllegalArgumentException("model is invalid");
        JsonNode inputNode=input.get("input");requireObject(inputNode,"input must be a JSON object");
        Map<String,Object> business=JSON.convertValue(inputNode,Map.class);
        Map<String,Object> load=objectMap(input.get("load"),"load must be a JSON object");
        Map<String,Object> execution=objectMap(input.get("execution"),"execution must be a JSON object");
        List<String> testdata=quickLoadTestdata(input.get("testdata"));
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="quick-load-input";request.inspectionType=type;request.inspectionTargetId=logicalId;
        request.loadModel=model;request.inlineLoadInput=business;request.loadOverrides=load;request.workloadExecution=execution;
        request.quickLoadTestdata=testdata;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawScenario=inspected.remove("normalizedScenario");
        Object rawDigest=inspected.remove("revisionDigest");
        if(!(rawScenario instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}"))
            throw new IllegalStateException("Quick Load validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(rawScenario,Map.class);
        enforceInlineLoadLimits(scenario);
        Object rawResource=inspected.get("resource"),rawTarget=inspected.get("target");
        if(!(rawResource instanceof Map)||!(rawTarget instanceof Map))throw new IllegalStateException("Quick Load validation Worker returned an invalid target");
        @SuppressWarnings("unchecked") Map<String,Object> resource=(Map<String,Object>)rawResource;
        String resourceId=String.valueOf(resource.get("resourceId"));
        @SuppressWarnings("unchecked") Map<String,Object> resolvedTarget=(Map<String,Object>)rawTarget;
        String resolvedType=String.valueOf(resolvedTarget.get("type")),resolvedId=String.valueOf(resolvedTarget.get("id"));
        QuickLoadDraft draft=new QuickLoadDraft("L"+UUID.randomUUID().toString().replace("-","").toUpperCase(),
                principal,packageId,environment,resolvedType,resolvedId,resourceId,model,(String)rawDigest,
                scenario,inspected,clock.instant().plusMillis(DRAFT_TTL_MILLIS));
        synchronized(draftLock) {
            expireDrafts();
            if(debugDrafts.size()+quickLoadDrafts.size()+advancedLoadDrafts.size()>=MAX_ACTIVE_DRAFTS)throw new DraftCapacityException();
            long owned=debugDrafts.values().stream().filter(value->value.principal.equals(principal)).count()
                    +quickLoadDrafts.values().stream().filter(value->value.principal.equals(principal)).count()
                    +advancedLoadDrafts.values().stream().filter(value->value.principal.equals(principal)).count();
            if(owned>=MAX_DRAFTS_PER_PRINCIPAL)throw new DraftCapacityException();
            quickLoadDrafts.put(draft.id,draft);
        }
        return quickLoadDraftView(draft);
    }
    Map<String,Object> createAdvancedLoadDraft(JsonNode input,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        requireObject(input,"A JSON object is required");requireOnlyFields(input,"packageId","environment","scenario");
        String packageId=required(input,"packageId");Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        String environment=optionalText(input,"environment");if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        JsonNode scenarioNode=input.get("scenario");requireObject(scenarioNode,"scenario must be a JSON object");
        @SuppressWarnings("unchecked") Map<String,Object> scenarioInput=(Map<String,Object>)JSON.convertValue(scenarioNode,Map.class);
        WorkerRequest request=inspectionRequest(packageId,root,environment);request.inspectionAction="load-scenario";request.inlineLoadScenario=scenarioInput;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawScenario=inspected.remove("normalizedScenario"),rawDigest=inspected.remove("revisionDigest");
        Object rawModel=inspected.get("model");
        if(!(rawScenario instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")
                ||!(rawModel instanceof String)||!List.of("virtualUsers","arrivalRate").contains(rawModel))
            throw new IllegalStateException("Load validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(rawScenario,Map.class);
        enforceInlineLoadLimits(scenario);
        AdvancedLoadDraftState draft=new AdvancedLoadDraftState("A"+UUID.randomUUID().toString().replace("-","").toUpperCase(),
                principal,packageId,environment,(String)rawModel,(String)rawDigest,scenario,inspected,
                clock.instant().plusMillis(DRAFT_TTL_MILLIS));
        synchronized(draftLock) {
            expireDrafts();
            if(debugDrafts.size()+quickLoadDrafts.size()+advancedLoadDrafts.size()>=MAX_ACTIVE_DRAFTS)throw new DraftCapacityException();
            long owned=debugDrafts.values().stream().filter(value->value.principal.equals(principal)).count()
                    +quickLoadDrafts.values().stream().filter(value->value.principal.equals(principal)).count()
                    +advancedLoadDrafts.values().stream().filter(value->value.principal.equals(principal)).count();
            if(owned>=MAX_DRAFTS_PER_PRINCIPAL)throw new DraftCapacityException();
            advancedLoadDrafts.put(draft.id,draft);
        }
        return advancedLoadDraftView(draft);
    }
    Map<String,Object> getDebugDraft(String id,String principal) {
        if(id==null||!DRAFT_ID.matcher(id).matches())throw new NotFoundException();
        synchronized(draftLock) {
            expireDrafts();DebugDraft draft=debugDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal))throw new NotFoundException();
            return debugDraftView(draft);
        }
    }
    Map<String,Object> getQuickLoadDraft(String id,String principal) {
        requireInlineLoadEnabled();
        if(id==null||!QUICK_LOAD_DRAFT_ID.matcher(id).matches())throw new NotFoundException();
        synchronized(draftLock) {
            expireDrafts();QuickLoadDraft draft=quickLoadDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal))throw new NotFoundException();
            return quickLoadDraftView(draft);
        }
    }
    Map<String,Object> getAdvancedLoadDraft(String id,String principal) {
        requireInlineLoadEnabled();
        if(id==null||!ADVANCED_LOAD_DRAFT_ID.matcher(id).matches())throw new NotFoundException();
        synchronized(draftLock) {
            expireDrafts();AdvancedLoadDraftState draft=advancedLoadDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal))throw new NotFoundException();
            return advancedLoadDraftView(draft);
        }
    }
    Map<String,Object> getDraft(String id,String principal) {
        if(DRAFT_ID.matcher(id==null?"":id).matches())return getDebugDraft(id,principal);
        if(QUICK_LOAD_DRAFT_ID.matcher(id==null?"":id).matches())return getQuickLoadDraft(id,principal);
        if(ADVANCED_LOAD_DRAFT_ID.matcher(id==null?"":id).matches())return getAdvancedLoadDraft(id,principal);
        throw new NotFoundException();
    }
    Map<String,Object> submitDebugDraft(JsonNode body,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId");
        String packageId=required(body,"packageId"),draftId=required(body,"draftId");
        DebugDraft draft;
        synchronized(draftLock) {
            expireDrafts();draft=debugDrafts.get(draftId);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(packageId))throw new NotFoundException();
            synchronized(draft){if(draft.submitting)throw new IllegalArgumentException("Draft submission is already in progress");draft.submitting=true;}
        }
        boolean submitted=false;
        try {
            requireLiveDraft(draft);
            com.fasterxml.jackson.databind.node.ObjectNode internal=JSON.createObjectNode();
            internal.put("packageId",draft.packageId);if(draft.environment!=null)internal.put("environment",draft.environment);
            internal.set("target",JSON.valueToTree(Map.of("type",draft.targetType,"id",draft.targetId)));
            internal.set("inlineDebugInput",JSON.valueToTree(draft.input));
            internal.put("expectedRevisionDigest",draft.revisionDigest);internal.put("draftResourceId",draft.resourceId);
            internal.set("safeTextSources",JSON.valueToTree(config.inspection.safeTextSources(draft.packageId)));
            internal.put("maxSourceBytes",config.inspection.maxSourceBytes);internal.put("maxResponseBytes",config.inspection.maxResponseBytes);
            requireLiveDraft(draft);
            Map<String,Object> accepted=submit("debug",internal,principal,true);
            debugDrafts.remove(draft.id,draft);submitted=true;return accepted;
        } catch(StaleDraftException stale) {
            debugDrafts.remove(draft.id,draft);throw stale;
        } catch(StaleCursorException|NotFoundException stale) {
            debugDrafts.remove(draft.id,draft);throw new StaleDraftException();
        } finally {
            if(!submitted)synchronized(draft){draft.submitting=false;}
        }
    }
    Map<String,Object> submitQuickLoadDraft(JsonNode body,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        requireInlineLoadEnabled();
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId");
        String packageId=required(body,"packageId"),draftId=required(body,"draftId");
        QuickLoadDraft draft;
        synchronized(draftLock) {
            expireDrafts();draft=quickLoadDrafts.get(draftId);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(packageId))throw new NotFoundException();
            synchronized(draft){if(draft.submitting)throw new IllegalArgumentException("Quick Load draft submission is already in progress");draft.submitting=true;}
        }
        boolean submitted=false;
        try {
            verifyQuickLoadDraftRevision(draft);
            requireLiveDraft(draft.expiresAt);
            com.fasterxml.jackson.databind.node.ObjectNode internal=JSON.createObjectNode();
            internal.put("packageId",draft.packageId);if(draft.environment!=null)internal.put("environment",draft.environment);
            internal.set("target",JSON.valueToTree(Map.of("type",draft.targetType,"id",draft.targetId)));
            internal.put("loadModel",draft.model);internal.set("inlineLoadScenario",JSON.valueToTree(draft.scenario));
            internal.put("expectedRevisionDigest",draft.revisionDigest);internal.put("draftResourceId",draft.resourceId);
            internal.set("safeTextSources",JSON.valueToTree(config.inspection.safeTextSources(draft.packageId)));
            internal.put("maxSourceBytes",config.inspection.maxSourceBytes);internal.put("maxResponseBytes",config.inspection.maxResponseBytes);
            requireLiveDraft(draft.expiresAt);
            Map<String,Object> accepted=submit("load",internal,principal,true);
            quickLoadDrafts.remove(draft.id,draft);submitted=true;return accepted;
        } catch(StaleDraftException stale) {
            quickLoadDrafts.remove(draft.id,draft);throw stale;
        } catch(StaleCursorException|NotFoundException stale) {
            quickLoadDrafts.remove(draft.id,draft);throw new StaleDraftException();
        } finally {
            if(!submitted)synchronized(draft){draft.submitting=false;}
        }
    }
    Map<String,Object> submitAdvancedLoadDraft(JsonNode body,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        requireInlineLoadEnabled();
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId");
        String packageId=required(body,"packageId"),draftId=required(body,"draftId");
        AdvancedLoadDraftState draft;
        synchronized(draftLock) {
            expireDrafts();draft=advancedLoadDrafts.get(draftId);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(packageId))throw new NotFoundException();
            synchronized(draft){if(draft.submitting)throw new IllegalArgumentException("Draft submission is already in progress");draft.submitting=true;}
        }
        boolean submitted=false;
        try {
            verifyPackageRevision(draft.packageId,draft.environment,draft.revisionDigest);
            requireLiveDraft(draft.expiresAt);
            com.fasterxml.jackson.databind.node.ObjectNode internal=JSON.createObjectNode();
            internal.put("packageId",draft.packageId);if(draft.environment!=null)internal.put("environment",draft.environment);
            internal.put("loadModel",draft.model);internal.set("inlineLoadScenario",JSON.valueToTree(draft.scenario));
            internal.put("expectedRevisionDigest",draft.revisionDigest);
            internal.set("safeTextSources",JSON.valueToTree(config.inspection.safeTextSources(draft.packageId)));
            internal.put("maxSourceBytes",config.inspection.maxSourceBytes);internal.put("maxResponseBytes",config.inspection.maxResponseBytes);
            requireLiveDraft(draft.expiresAt);
            Map<String,Object> accepted=submit("load",internal,principal,true);
            advancedLoadDrafts.remove(draft.id,draft);submitted=true;return accepted;
        } catch(StaleDraftException stale) {
            advancedLoadDrafts.remove(draft.id,draft);throw stale;
        } catch(StaleCursorException|NotFoundException stale) {
            advancedLoadDrafts.remove(draft.id,draft);throw new StaleDraftException();
        } finally {
            if(!submitted)synchronized(draft){draft.submitting=false;}
        }
    }
    Map<String,Object> submitLoadDraft(JsonNode body,String principal) throws Exception {
        requireObject(body,"A JSON object is required");
        String draftId=required(body,"draftId");
        if(QUICK_LOAD_DRAFT_ID.matcher(draftId).matches())return submitQuickLoadDraft(body,principal);
        if(ADVANCED_LOAD_DRAFT_ID.matcher(draftId).matches())return submitAdvancedLoadDraft(body,principal);
        throw new NotFoundException();
    }
    private void verifyQuickLoadDraftRevision(QuickLoadDraft draft) throws Exception {
        try { verifyPackageRevision(draft.packageId,draft.environment,draft.revisionDigest); }
        catch(StaleCursorException|NotFoundException changed){throw new StaleDraftException();}
    }
    private void verifyPackageRevision(String packageId,String environment,String revision) throws Exception {
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        WorkerRequest request=inspectionRequest(packageId,root,environment);request.inspectionAction="revision";request.expectedRevisionDigest=revision;
        executeInspection(root,request);
    }
    private void requireLiveDraft(DebugDraft draft) {
        if(!draft.expiresAt.isAfter(clock.instant()))throw new StaleDraftException();
    }
    private void requireLiveDraft(Instant expiresAt) {
        if(!expiresAt.isAfter(clock.instant()))throw new StaleDraftException();
    }
    private WorkerRequest inspectionRequest(String packageId,Path root,String environment) {
        WorkerRequest request=new WorkerRequest();request.protocolVersion="att-worker/v1";request.jobId="I"+UUID.randomUUID().toString().replace("-","");request.command="inspect";
        request.packageRoot=root.toString();request.config="config/config.yaml";request.environment=environment;
        request.maxResponseBytes=config.inspection.maxResponseBytes;request.maxSourceBytes=config.inspection.maxSourceBytes;
        request.safeTextSources=config.inspection.safeTextSources(packageId);
        return request;
    }
    private Map<String,Object> debugDraftView(DebugDraft draft) {
        Map<String,Object> view=new LinkedHashMap<String,Object>();view.put("draftId",draft.id);view.put("packageId",draft.packageId);
        view.put("environment",draft.environment);view.put("target",Map.of("type",draft.targetType,"id",draft.targetId));
        view.put("preview",draft.preview.get("input"));view.put("redacted",Boolean.TRUE.equals(draft.preview.get("redacted")));
        try {DumperOptions options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);options.setPrettyFlow(true);options.setWidth(120);view.put("previewYaml",new Yaml(options).dump(draft.preview.get("input")));}catch(Exception ignored){view.put("previewYaml","{}");}
        view.put("diagnostics",Collections.emptyList());view.put("expiresAt",draft.expiresAt.toString());return view;
    }
    private Map<String,Object> quickLoadDraftView(QuickLoadDraft draft) {
        Map<String,Object> view=new LinkedHashMap<String,Object>();view.put("draftId",draft.id);view.put("packageId",draft.packageId);
        view.put("environment",draft.environment);view.put("target",Map.of("type",draft.targetType,"id",draft.targetId));
        view.put("model",draft.model);view.put("preview",draft.preview.get("preview"));view.put("previewYaml",draft.preview.get("previewYaml"));
        view.put("redacted",Boolean.TRUE.equals(draft.preview.get("redacted")));view.put("diagnostics",Collections.emptyList());
        view.put("expiresAt",draft.expiresAt.toString());return view;
    }
    private Map<String,Object> advancedLoadDraftView(AdvancedLoadDraftState draft) {
        Map<String,Object> view=new LinkedHashMap<String,Object>();view.put("draftId",draft.id);view.put("packageId",draft.packageId);
        view.put("environment",draft.environment);view.put("model",draft.model);
        view.put("preview",draft.preview.get("preview"));view.put("previewYaml",draft.preview.get("previewYaml"));
        view.put("redacted",Boolean.TRUE.equals(draft.preview.get("redacted")));view.put("diagnostics",Collections.emptyList());
        view.put("expiresAt",draft.expiresAt.toString());return view;
    }
    private void expireDrafts() {
        Instant now=clock.instant();debugDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
        quickLoadDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
        advancedLoadDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
    }
    private void expireDraftsSafely() {synchronized(draftLock){expireDrafts();}}
    private static void requireObject(JsonNode value,String message) {if(value==null||!value.isObject())throw new IllegalArgumentException(message);}
    private static void requireOnlyFields(JsonNode object,String... allowed) {
        java.util.Set<String> names=new java.util.HashSet<>(java.util.Arrays.asList(allowed));
        java.util.Iterator<String> fields=object.fieldNames();while(fields.hasNext())if(!names.contains(fields.next()))throw new IllegalArgumentException("Unknown request field");
    }
    private void requireInlineLoadEnabled() {
        if(!config.inlineLoad.enabled)throw new InlineLoadDisabledException();
    }
    private void enforceInlineLoadLimits(Map<String,Object> scenario) {
        validateInlineLoadLimits(config.inlineLoad,scenario);
    }
    static void validateInlineLoadLimits(ServerConfig.InlineLoad limits,Map<String,Object> scenario) {
        if(!limits.enabled)throw new InlineLoadDisabledException();
        Object rawWorkloads=scenario.get("workloads");
        if(!(rawWorkloads instanceof List)||((List<?>)rawWorkloads).isEmpty())
            throw new IllegalArgumentException("Validated Load scenario must contain workloads");
        List<?> workloads=(List<?>)rawWorkloads;
        if(workloads.size()>limits.maxWorkloads)
            throw new IllegalArgumentException("Scenario exceeds server.inlineLoad.maxWorkloads");
        long totalUsers=0L,totalTargets=0L,totalArrivalConcurrency=0L;
        double totalArrivalRate=0.0;
        long maximumEnvelopeMs=0L;
        for(int i=0;i<workloads.size();i++) {
            Object raw=workloads.get(i);
            if(!(raw instanceof Map))throw new IllegalArgumentException("Validated Load workload is invalid");
            Map<?,?> workload=(Map<?,?>)raw;
            Object mix=workload.get("mix");
            if(mix instanceof List)totalTargets=addWithinLimit(totalTargets,((List<?>)mix).size(),limits.maxTargets,"Scenario exceeds server.inlineLoad.maxTargets");
            else if(workload.get("target") instanceof Map)totalTargets=addWithinLimit(totalTargets,1,limits.maxTargets,"Scenario exceeds server.inlineLoad.maxTargets");
            else throw new IllegalArgumentException("Validated Load target is invalid");

            Object rawLoad=workload.get("load");
            if(!(rawLoad instanceof Map))throw new IllegalArgumentException("Validated Load pacing is invalid");
            Map<?,?> load=(Map<?,?>)rawLoad;
            if(load.get("users") instanceof Number) {
                totalUsers=addWithinLimit(totalUsers,validatedCount(load.get("users"),"users"),limits.maxTotalUsers,
                        "Scenario exceeds server.inlineLoad.maxTotalUsers");
            } else {
                double arrivalRate=validatedRatePerSecond(load.get("arrivalRate"));
                if(arrivalRate>limits.maxAggregateArrivalRatePerSecond-totalArrivalRate)
                    throw new IllegalArgumentException("Scenario exceeds server.inlineLoad.maxAggregateArrivalRatePerSecond");
                totalArrivalRate+=arrivalRate;
                long concurrent=validatedCount(load.get("maxConcurrent"),"maxConcurrent");
                if(concurrent>limits.maxConcurrentPerWorkload)
                    throw new IllegalArgumentException("Workload exceeds server.inlineLoad.maxConcurrentPerWorkload");
                totalArrivalConcurrency=addWithinLimit(totalArrivalConcurrency,concurrent,limits.maxTotalConcurrent,
                        "Scenario exceeds server.inlineLoad.maxTotalConcurrent");
            }
            long envelope=durationEnvelopeMillis(load);
            maximumEnvelopeMs=Math.max(maximumEnvelopeMs,envelope);
        }
        if(maximumEnvelopeMs>TimeUnit.SECONDS.toMillis(limits.maxDurationSeconds))
            throw new IllegalArgumentException("Scenario exceeds server.inlineLoad.maxDurationSeconds");
    }
    private static long addWithinLimit(long total,long value,long maximum,String message) {
        if(value<0||total>maximum-value)throw new IllegalArgumentException(message);
        return total+value;
    }
    private static long validatedCount(Object value,String field) {
        if(!(value instanceof Number))throw new IllegalArgumentException("Validated Load "+field+" is invalid");
        try {
            long count=new java.math.BigDecimal(String.valueOf(value)).longValueExact();
            if(count<1)throw new IllegalArgumentException("Validated Load "+field+" is invalid");
            return count;
        } catch(NumberFormatException|ArithmeticException invalid) {
            throw new IllegalArgumentException("Validated Load "+field+" is invalid");
        }
    }
    private static double validatedRatePerSecond(Object value) {
        if(!(value instanceof String))throw new IllegalArgumentException("Validated Load arrivalRate is invalid");
        java.util.regex.Matcher matcher=INLINE_LOAD_RATE.matcher(((String)value).trim());
        if(!matcher.matches())throw new IllegalArgumentException("Validated Load arrivalRate is invalid");
        double rate=Double.parseDouble(matcher.group(1));
        if("m".equals(matcher.group(2)))rate/=60.0;
        if(!Double.isFinite(rate)||rate<=0.0)throw new IllegalArgumentException("Validated Load arrivalRate is invalid");
        return rate;
    }
    private static long durationMillis(Object value,String field) {
        if(!(value instanceof String))throw new IllegalArgumentException("Validated Load "+field+" is invalid");
        java.util.regex.Matcher matcher=INLINE_LOAD_DURATION.matcher(((String)value).trim());
        if(!matcher.matches())throw new IllegalArgumentException("Validated Load "+field+" is invalid");
        long amount=Long.parseLong(matcher.group(1));
        long multiplier="ms".equals(matcher.group(2))?1L:"s".equals(matcher.group(2))?1000L:"m".equals(matcher.group(2))?60000L:3600000L;
        try{return Math.multiplyExact(amount,multiplier);}
        catch(ArithmeticException overflow){throw new IllegalArgumentException("Validated Load "+field+" is too large");}
    }
    private static long durationEnvelopeMillis(Map<?,?> load) {
        try {
            return Math.addExact(Math.addExact(durationMillis(load.get("warmup"),"warmup"),durationMillis(load.get("rampUp"),"rampUp")),
                    Math.addExact(durationMillis(load.get("duration"),"duration"),durationMillis(load.get("rampDown"),"rampDown")));
        } catch(ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    private static String optionalText(JsonNode object,String name) {
        JsonNode value=object.get(name);if(value==null||value.isNull())return null;
        if(!value.isTextual()||value.asText().isBlank())throw new IllegalArgumentException(name+" must be a non-empty string");
        String text=value.asText();if(text.length()>128||containsControl(text))throw new IllegalArgumentException(name+" is invalid");return text.trim();
    }
    private static Map<String,Object> objectMap(JsonNode value,String message) {
        if(value==null||value.isNull())return Collections.emptyMap();
        requireObject(value,message);
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)JSON.convertValue(value,Map.class);
        return result;
    }
    private static List<String> quickLoadTestdata(JsonNode value) {
        if(value==null||value.isNull())return Collections.emptyList();
        if(!value.isArray()||value.size()>64)throw new IllegalArgumentException("testdata must be an array of at most 64 package-relative paths");
        List<String> paths=new ArrayList<>();
        for(JsonNode item:value) {
            if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>512||containsControl(item.asText()))
                throw new IllegalArgumentException("testdata entries must be non-empty package-relative paths");
            paths.add(item.asText().trim());
        }
        return paths;
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,null,0,0,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("An authenticated Servlet Principal is required");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(!List.of("declared","effective","compare").contains(action))throw new IllegalArgumentException("Unsupported configuration inspection action");
        String selected=null,other=null;
        if("effective".equals(action)&&environment!=null&&!environment.isBlank())selected=inspectionEnvironment(environment,"environment");
        if("compare".equals(action)){
            selected=inspectionEnvironment(environment,"left environment");
            other=inspectionEnvironment(otherEnvironment,"right environment");
            if(selected.equalsIgnoreCase(other))throw new IllegalArgumentException("left and right environments must differ");
        }
        WorkerRequest request=new WorkerRequest();request.protocolVersion="att-worker/v1";request.jobId="I"+UUID.randomUUID().toString().replace("-","");request.command="inspect";request.packageRoot=root.toString();request.config="config/config.yaml";
        request.inspectionAction=action;request.inspectionType="configuration";request.inspectionEnvironment=selected;request.inspectionOtherEnvironment=other;
        request.inspectionSection=section;request.inspectionOffset=offset;request.inspectionLimit=limit;request.maxResponseBytes=config.inspection.maxResponseBytes;
        return executeInspection(root,request);
    }
    private Map<String,Object> executeInspection(Path root,WorkerRequest request) throws Exception {
        Future<Map<String,Object>> future;
        try { future=inspectors.submit(()->runInspectionWorker(root,request)); }
        catch(RejectedExecutionException full){throw new InspectionCapacityException();}
        Map<String,Object> envelope;
        try { envelope=future.get(config.inspection.timeoutMs,TimeUnit.MILLISECONDS); }
        catch(java.util.concurrent.TimeoutException timeout){future.cancel(true);throw new InspectionTimeoutException();}
        catch(InterruptedException interrupted){future.cancel(true);Thread.currentThread().interrupt();throw interrupted;}
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("Package inspection failed",cause);}
        String workerError=(String)envelope.get("errorCode");
        if("ATT-RESOURCE-NOT-FOUND".equals(workerError))throw new NotFoundException();
        if("ATT-RESOURCE-CURSOR-STALE".equals(workerError))throw new StaleCursorException();
        boolean draftInspection="debug-input".equals(request.inspectionAction)||"debug-form".equals(request.inspectionAction)
                ||"quick-load-input".equals(request.inspectionAction)||"quick-load-form".equals(request.inspectionAction)
                ||"quick-load-policy".equals(request.inspectionAction)||"load-scenario".equals(request.inspectionAction);
        if(draftInspection&&("WORKER_REQUEST_INVALID".equals(workerError)
                ||workerError!=null&&(workerError.startsWith("ATT-DEBUG")||workerError.startsWith("ATT-LOAD"))
                ||envelope.get("diagnostics") instanceof List&&!((List<?>)envelope.get("diagnostics")).isEmpty())) {
            Object rawDiagnostics=envelope.get("diagnostics");
            List<Map<String,Object>> diagnostics=new ArrayList<Map<String,Object>>();
            if(rawDiagnostics instanceof List)for(Object raw:(List<?>)rawDiagnostics)if(raw instanceof Map) {
                @SuppressWarnings("unchecked") Map<String,Object> value=(Map<String,Object>)raw;
                Map<String,Object> safe=new LinkedHashMap<String,Object>();
                for(String key:List.of("code","summary","field","resourceId")) {
                    Object item=value.get(key);
                    if(item instanceof String)safe.put(key,att.worker.internal.DiagnosticSanitizer.redactText((String)item));
                }
                if(!safe.isEmpty())diagnostics.add(safe);
            }
            if(diagnostics.isEmpty())diagnostics.add(Map.of("code",workerError,"summary","Load or Debug input failed schema or target validation",
                    "resourceId",request.inspectionTargetId==null?request.inspectionResourceId:request.inspectionTargetId));
            throw new DraftValidationException(diagnostics);
        }
        if("ATT-RESOURCE-RESPONSE-TOO-LARGE".equals(workerError))throw new InspectionResponseTooLargeException();
        if("ATT-RESOURCE-LIMIT".equals(workerError))throw new InspectionCapacityException();
        if(workerError!=null)throw new IllegalStateException("Package inspection Worker failed: "+workerError);
        Object raw=envelope.get("inspection");if(!(raw instanceof Map))throw new IllegalStateException("Package inspection Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> result=new LinkedHashMap<>((Map<String,Object>)raw);
        return result;
    }
    private static String inspectionEnvironment(String value,String name) {
        if(value==null||!value.trim().matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))throw new IllegalArgumentException(name+" must be a valid profile name");
        return value.trim();
    }
    private Map<String,Object> runInspectionWorker(Path root,WorkerRequest request) throws Exception {
        Process process=null;
        try {
            validatePackageRoot(root);Path libs=webInfLibs==null?null:Paths.get(webInfLibs);
            if(libs==null||!Files.isDirectory(libs))throw new IllegalStateException("Tomcat must deploy the WAR as an exploded application so WEB-INF/lib is available to the Worker launcher");
            List<String> classpathEntries=new ArrayList<>();classpathEntries.add(libs.resolve("*").toString());for(Path libraryDir:config.workerLibraryDirs)classpathEntries.add(libraryDir.resolve("*").toString());
            String cp=String.join(java.io.File.pathSeparator,classpathEntries);
            ProcessBuilder builder=new ProcessBuilder(config.javaExecutable.toString(),"-Xmx"+config.inspection.heapMaxMb+"m","-cp",cp,"att.worker.WorkerMain");builder.directory(root.toFile());
            process=processLauncher.start(builder);inspectionProcesses.put(process,Boolean.TRUE);
            java.util.concurrent.atomic.AtomicBoolean timedOut=new java.util.concurrent.atomic.AtomicBoolean();
            final Process active=process;
            Thread watchdog=new Thread(()->{try{if(!active.waitFor(config.inspection.timeoutMs,TimeUnit.MILLISECONDS)){timedOut.set(true);forceTree(active);}}catch(InterruptedException ignored){Thread.currentThread().interrupt();}},"att-server-inspector-watchdog");watchdog.setDaemon(true);watchdog.start();
            Thread stderr=new Thread(()->{try(var in=active.getErrorStream()){byte[] buffer=new byte[8192];while(in.read(buffer)>=0){}}catch(Exception ignored){}});stderr.setDaemon(true);stderr.start();
            try(var out=process.getOutputStream()){out.write(JSON.writeValueAsBytes(request));out.write('\n');out.flush();}
            JsonNode result=null;String errorCode=null;Map<String,Object> workerDiagnostic=null;long total=0;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=readBoundedLine(reader,config.inspection.maxResponseBytes))!=null){
                    if("\u0000OVERSIZED".equals(line))throw new InspectionResponseTooLargeException();
                    total+=line.getBytes(StandardCharsets.UTF_8).length+1L;if(total>config.inspection.maxResponseBytes)throw new InspectionResponseTooLargeException();
                    JsonNode event=JSON.readTree(line);if(event==null||!event.isObject())continue;String kind=event.path("type").asText("");
                    if("DIAGNOSTIC".equalsIgnoreCase(kind)) {errorCode=event.path("code").asText(null);workerDiagnostic=JSON.convertValue(event,Map.class);}
                    if("RESULT".equalsIgnoreCase(kind))result=event;
                }
            }
            int exit=process.waitFor();
            if(timedOut.get())throw new InspectionTimeoutException();
            if(result==null||exit!=0&&errorCode==null)throw new IllegalStateException("Package inspection Worker did not complete successfully");
            Map<String,Object> response=new LinkedHashMap<>();
            if(errorCode!=null) {
                response.put("errorCode",errorCode);
                if(workerDiagnostic!=null&&workerDiagnostic.get("summary") instanceof String) {
                    Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
                    for(String key:List.of("code","summary","field","resourceId")) {
                        Object value=workerDiagnostic.get(key);if(value instanceof String)diagnostic.put(key,value);
                    }
                    response.put("diagnostics",Collections.singletonList(diagnostic));
                }
                return response;
            }
            JsonNode inspection=result.path("result").path("summary").path("inspection");if(!inspection.isObject())throw new IllegalStateException("Package inspection Worker returned no inspection data");
            response.put("inspection",JSON.convertValue(inspection,Map.class));return response;
        } catch(InterruptedException interrupted){if(process!=null&&process.isAlive())forceTree(process);Thread.currentThread().interrupt();throw interrupted;}
        catch(Exception error){if(process!=null&&process.isAlive())forceTree(process);throw error;}
        finally{if(process!=null){inspectionProcesses.remove(process);if(process.isAlive())forceTree(process);}}
    }
    private String encodeCursor(String packageId,String type,String query,int offset,String revision,String principal) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("packageId",packageId);payload.put("type",type);payload.put("query",query);payload.put("offset",offset);payload.put("revision",revision);payload.put("principal",principal);
        byte[] nonce=new byte[12];cursorRandom.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));
        byte[] ciphertext=cipher.doFinal(JSON.writeValueAsBytes(payload));byte[] token=new byte[nonce.length+ciphertext.length];
        System.arraycopy(nonce,0,token,0,nonce.length);System.arraycopy(ciphertext,0,token,nonce.length,ciphertext.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
    private Cursor decodeCursor(String token,String packageId,String type,String query,String principal) throws Exception {
        if(token.length()>4096)throw new IllegalArgumentException("cursor is too long");byte[] bytes;
        try{bytes=Base64.getUrlDecoder().decode(token);}catch(IllegalArgumentException bad){throw new IllegalArgumentException("cursor is invalid");}
        if(bytes.length<28)throw new IllegalArgumentException("cursor is invalid");
        byte[] nonce=java.util.Arrays.copyOfRange(bytes,0,12),ciphertext=java.util.Arrays.copyOfRange(bytes,12,bytes.length),payload;
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));payload=cipher.doFinal(ciphertext);}
        catch(Exception bad){throw new IllegalArgumentException("cursor is invalid");}
        JsonNode value;try{value=JSON.readTree(payload);}catch(Exception bad){throw new IllegalArgumentException("cursor is invalid");}
        if(!packageId.equals(value.path("packageId").asText())||!java.util.Objects.equals(type,value.path("type").isNull()?null:value.path("type").asText())||!query.equals(value.path("query").asText())||!principal.equals(value.path("principal").asText()))throw new IllegalArgumentException("cursor does not match this resource query");
        int offset=value.path("offset").asInt(-1);String revision=value.path("revision").asText("");if(offset<1||offset>20000||!revision.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("cursor is invalid");return new Cursor(offset,revision);
    }
    private static boolean containsControl(String value){for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return true;return false;}
    Map<String,Object> counts() throws Exception {Map<String,Object> m=new LinkedHashMap<>();for(String s:List.of("QUEUED","PREPARING","RUNNING","PASS","FAIL","ERROR","INVALID","CANCELLED"))m.put(s.toLowerCase(),store.count(s));m.put("queueDepth",workers.getQueue().size());m.put("activeWorkers",workers.getActiveCount());m.put("completedSinceStart",completed.get());return m;}
    Path artifact(Job job,String requested) throws Exception {
        if(requested==null||requested.isBlank())throw new IllegalArgumentException("artifact path is required");
        Path base=config.dataDir.resolve("jobs").resolve(job.id).resolve("output").toRealPath();Path relative=Paths.get(requested);
        if(relative.isAbsolute())throw new IllegalArgumentException("Artifact path must be relative to job output");
        Path candidate=base.resolve(relative).normalize();if(!candidate.startsWith(base)||!Files.exists(candidate,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new NotFoundException();
        Path walk=base;for(Path part:base.relativize(candidate)){walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new NotFoundException();}
        Path real=candidate.toRealPath();if(!real.startsWith(base)||!Files.isRegularFile(real))throw new NotFoundException();return real;
    }
    private void transition(Job j,String next) throws Exception {synchronized(j){if(j.terminal())return;j.status=next;if("RUNNING".equals(next))j.startedAt=Instant.now();store.update(j);append(j,"status",Map.of("jobId",j.id,"status",next));}}
    // terminal() shares this monitor; SSE also waits for JobEvents' result marker before closing.
    void finish(Job j,String status,int code) throws Exception {synchronized(j){if(j.terminal())return;j.status=status;j.exitCode=code;j.finishedAt=Instant.now();store.update(j);append(j,"status",Map.of("jobId",j.id,"status",status));Map<String,Object> result=new LinkedHashMap<>();result.put("jobId",j.id);result.put("status",status);result.put("exitCode",code);result.put("result",j.resultJson==null?null:publicJson(j.id,j.resultJson));append(j,"result",result);}}
    private void finishQuietly(Job j,String status,int code,String diagnostic,String message){try{j.diagnosticJson=JSON.writeValueAsString(Map.of("code",diagnostic,"summary",message));append(j,"diagnostic",JSON.readValue(j.diagnosticJson,Map.class));finish(j,status,code);}catch(Exception ignored){j.status=status;j.exitCode=code;j.finishedAt=Instant.now();}}
    private void append(Job j,String type,Map<String,?> data) throws Exception {j.events.append(type,data);}
    private static String required(JsonNode node,String name){JsonNode v=node.get(name);if(v==null||!v.isTextual()||v.asText().isBlank())throw new IllegalArgumentException(name+" is required");return v.asText();}
    private static String safeText(String s,int max,String name){if(s!=null&&(s.length()>max||s.contains("\n")||s.contains("\r")))throw new IllegalArgumentException("Invalid "+name);return s;}
    private static String safeRelative(String s,String name){if(s==null||s.isBlank())return null;Path p=Paths.get(s);if(p.isAbsolute()||p.normalize().startsWith("..")||s.indexOf('\0')>=0)throw new IllegalArgumentException(name+" must stay inside PACKAGE_ROOT");return p.normalize().toString();}
    private static List<String> safePaths(List<String> paths,String name){if(paths==null)return null;List<String> out=new ArrayList<>();for(String p:paths){if(p==null)throw new IllegalArgumentException(name+" entries must be non-empty paths");out.add(safeRelative(p,name));}return out;}
    private void validatePackageRoot(Path root) throws Exception {
        Path current=root.toRealPath();
        if(!current.equals(root)||!config.allowedRoots.stream().anyMatch(current::startsWith))
            throw new IllegalArgumentException("Configured package root no longer resolves within its original allowed root");
    }
    private static void validatePackagePath(Path root,String relative,String name) throws Exception {if(relative==null)return;Path base=root.toRealPath();Path candidate=base.resolve(relative).normalize();if(!candidate.startsWith(base))throw new IllegalArgumentException(name+" escapes PACKAGE_ROOT");Path existing=candidate;while(existing!=null&&!Files.exists(existing))existing=existing.getParent();if(existing!=null&&!existing.toRealPath().startsWith(base))throw new IllegalArgumentException(name+" resolves outside PACKAGE_ROOT");}
    private static void validateTarget(Map<String,Object> target){if(target==null)throw new IllegalArgumentException("target is required");Object type=target.get("type"),id=target.get("id");if(!(type instanceof String)||!(id instanceof String))throw new IllegalArgumentException("target.type and target.id are required");}
    Path packageRootForJob(String id) throws Exception {
        Job active=jobs.get(id);
        if(active!=null)return config.packages.get(active.packageId);
        Map<String,Object> row=store.get(id);
        if(row==null)throw new NotFoundException();
        return config.packages.get(String.valueOf(row.get("packageId")));
    }
    Path outputDirectoryForJob(String id) {
        return config.dataDir.resolve("jobs").resolve(id).resolve("output").toAbsolutePath().normalize();
    }
    Object publicEventData(Object data,Path output,Path packageRoot) throws Exception {
        JsonNode node=JSON.valueToTree(att.worker.internal.DiagnosticSanitizer.sanitizeValue(data));
        return sanitize(node,output,packageRoot);
    }
    private JsonNode publicJson(String id,String json) throws Exception {
        JsonNode node=JSON.readTree(json);Object safe=att.worker.internal.DiagnosticSanitizer.sanitizeValue(JSON.convertValue(node,Object.class));node=JSON.valueToTree(safe);Path output=config.dataDir.resolve("jobs").resolve(id).resolve("output").toAbsolutePath().normalize();
        return sanitize(node,output,packageRootForJob(id));
    }
    private JsonNode sanitize(JsonNode node,Path output,Path packageRoot) {
        if(node==null)return null;
        if(node.isTextual()) {String value=node.asText();String outputPrefix=output.toString();value=replacePathPrefix(value,outputPrefix,"artifact");if(packageRoot!=null)value=replacePathPrefix(value,packageRoot.toString(),"package");return JSON.getNodeFactory().textNode(value);}
        if(node.isObject()){com.fasterxml.jackson.databind.node.ObjectNode copy=JSON.createObjectNode();node.fields().forEachRemaining(e->copy.set(e.getKey(),sanitize(e.getValue(),output,packageRoot)));return copy;}
        if(node.isArray()){com.fasterxml.jackson.databind.node.ArrayNode copy=JSON.createArrayNode();for(JsonNode item:node)copy.add(sanitize(item,output,packageRoot));return copy;}
        return node.deepCopy();
    }
    private static String replacePathPrefix(String value,String prefix,String logical){if(value.equals(prefix))return logical+":";String separator=java.io.File.separator;value=value.replace(prefix+separator,logical+":");if("\\".equals(separator))value=value.replace(prefix+"/",logical+":");else value=value.replace(prefix+"\\",logical+":");return value;}
    private static String safeMessage(Exception e){String m=att.worker.internal.DiagnosticSanitizer.redactText(e.getMessage());return m.isEmpty()?"ATT Worker execution failed":m.length()>500?m.substring(0,500):m;}
    private static String readBoundedLine(BufferedReader reader,int limit) throws java.io.IOException {StringBuilder line=new StringBuilder();boolean oversized=false;int c;while((c=reader.read())!=-1){if(c=='\n')break;if(c=='\r')continue;if(line.length()<limit)line.append((char)c);else oversized=true;}if(c==-1&&line.isEmpty()&&!oversized)return null;return oversized?"\u0000OVERSIZED":line.toString();}
    JobEvents events(String id) throws Exception {if(!JOB_ID.matcher(id).matches()||store.get(id)==null)throw new NotFoundException();Job active=jobs.get(id);return active==null?new JobEvents(config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),config.maxEventsPerJob):active.events;}
    boolean terminal(String id) throws Exception {Job active=jobs.get(id);if(active!=null)return active.terminal();Map<String,Object> row=store.get(id);if(row==null)throw new NotFoundException();return List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(row.get("status"));}
    Path artifact(String id,String requested) throws Exception {Map<String,Object> row=store.get(id);if(row==null)throw new NotFoundException();Job j=jobs.get(id);if(j!=null)return artifact(j,requested);Path base=config.dataDir.resolve("jobs").resolve(id).resolve("output").toRealPath();Path relative=Paths.get(requested);if(relative.isAbsolute())throw new IllegalArgumentException("Artifact path must be relative to job output");Path candidate=base.resolve(relative).normalize();if(!candidate.startsWith(base)||!Files.exists(candidate,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new NotFoundException();Path walk=base;for(Path part:base.relativize(candidate)){walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new NotFoundException();}Path real=candidate.toRealPath();if(!real.startsWith(base)||!Files.isRegularFile(real))throw new NotFoundException();return real;}
    void cleanupExpiredJobs() throws Exception {
        Instant cutoff=Instant.now().minus(java.time.Duration.ofDays(config.jobRetentionDays));
        for(String id:store.expiredJobIds(cutoff))if(!jobs.containsKey(id))try{
            deleteTree(config.dataDir.resolve("jobs").resolve(id));store.deleteJob(id);
        }catch(Exception failure){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to remove expired ATT Server job "+id+": "+safeMessage(failure));}
    }
    private void cleanupExpiredJobsSafely(){try{cleanupExpiredJobs();}catch(Exception e){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to clean expired ATT Server jobs: "+safeMessage(e));}}
    private static void deleteTree(Path root)throws java.io.IOException {
        if(!Files.exists(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))return;
        try(java.util.stream.Stream<Path> paths=Files.walk(root)){
            for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);
        }
    }
    @Override public void close(){retention.shutdownNow();synchronized(draftLock){debugDrafts.clear();quickLoadDrafts.clear();advancedLoadDrafts.clear();}for(Job j:jobs.values())if(j.process!=null&&j.process.isAlive()){try{terminateTree(j.process);if(!j.process.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS))forceTree(j.process);finishQuietly(j,"ERROR",143,"ATT-SERVER-INTERRUPTED","Server shutdown interrupted the Worker");}catch(Exception ignored){forceTree(j.process);}}for(Process process:inspectionProcesses.keySet())if(process.isAlive())forceTree(process);inspectors.shutdownNow();workers.shutdownNow();streams.shutdownNow();try{store.close();}catch(Exception ignored){}}
    private static void terminateTree(Process process){process.toHandle().descendants().forEach(ProcessHandle::destroy);process.destroy();}
    private static void forceTree(Process process){process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
    @FunctionalInterface interface WorkerProcessLauncher { Process start(ProcessBuilder builder) throws java.io.IOException; }
    private static final class AdmissionLease {
        private final Semaphore admission,load;private final java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();
        AdmissionLease(Semaphore admission,Semaphore load){this.admission=admission;this.load=load;}
        void release(){if(released.compareAndSet(false,true)){admission.release();if(load!=null)load.release();}}
    }
    static final class QueueFullException extends RuntimeException {QueueFullException(String m){super(m);}}
    static final class NotFoundException extends RuntimeException {}
    static final class InspectionCapacityException extends RuntimeException {}
    static final class InspectionTimeoutException extends RuntimeException {}
    static final class InspectionResponseTooLargeException extends RuntimeException {}
    static final class StaleCursorException extends RuntimeException {}
    static final class StaleDraftException extends RuntimeException {}
    static final class DraftCapacityException extends RuntimeException {}
    static final class InlineLoadDisabledException extends RuntimeException {}
    static final class DraftValidationException extends IllegalArgumentException {
        final List<Map<String,Object>> diagnostics;
        DraftValidationException(List<Map<String,Object>> diagnostics) {
            super("Load or Debug draft failed schema or target validation");
            List<Map<String,Object>> copy=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> diagnostic:diagnostics)copy.add(Collections.unmodifiableMap(new LinkedHashMap<String,Object>(diagnostic)));
            this.diagnostics=Collections.unmodifiableList(copy);
        }
    }
    private static final class DebugDraft {
        final String id,principal,packageId,environment,targetType,targetId,resourceId,revisionDigest;
        final Map<String,Object> input,preview;
        final Instant expiresAt;
        boolean submitting;
        DebugDraft(String id,String principal,String packageId,String environment,String targetType,String targetId,
                   String resourceId,String revisionDigest,Map<String,Object> input,Map<String,Object> preview,Instant expiresAt) {
            this.id=id;this.principal=principal;this.packageId=packageId;this.environment=environment;
            this.targetType=targetType;this.targetId=targetId;this.resourceId=resourceId;this.revisionDigest=revisionDigest;
            this.input=deepMap(input);this.preview=deepMap(preview);this.expiresAt=expiresAt;
        }
    }
    private static final class QuickLoadDraft {
        final String id,principal,packageId,environment,targetType,targetId,resourceId,model,revisionDigest;
        final Map<String,Object> scenario,preview;
        final Instant expiresAt;
        boolean submitting;
        QuickLoadDraft(String id,String principal,String packageId,String environment,String targetType,String targetId,
                       String resourceId,String model,String revisionDigest,Map<String,Object> scenario,
                       Map<String,Object> preview,Instant expiresAt) {
            this.id=id;this.principal=principal;this.packageId=packageId;this.environment=environment;
            this.targetType=targetType;this.targetId=targetId;this.resourceId=resourceId;this.model=model;
            this.revisionDigest=revisionDigest;this.scenario=deepMap(scenario);this.preview=deepMap(preview);this.expiresAt=expiresAt;
        }
    }
    private static final class AdvancedLoadDraftState {
        final String id,principal,packageId,environment,model,revisionDigest;
        final Map<String,Object> scenario,preview;
        final Instant expiresAt;
        boolean submitting;
        AdvancedLoadDraftState(String id,String principal,String packageId,String environment,String model,
                               String revisionDigest,Map<String,Object> scenario,Map<String,Object> preview,
                               Instant expiresAt) {
            this.id=id;this.principal=principal;this.packageId=packageId;this.environment=environment;
            this.model=model;this.revisionDigest=revisionDigest;this.scenario=deepMap(scenario);
            this.preview=deepMap(preview);this.expiresAt=expiresAt;
        }
    }
    private static Map<String,Object> deepMap(Map<String,Object> source) {
        Map<String,Object> copy=new LinkedHashMap<>();
        if(source!=null)for(Map.Entry<String,Object> entry:source.entrySet())copy.put(entry.getKey(),deepValue(entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }
    private static Object deepValue(Object value) {
        if(value instanceof Map) {
            Map<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())copy.put(String.valueOf(entry.getKey()),deepValue(entry.getValue()));
            return Collections.unmodifiableMap(copy);
        }
        if(value instanceof List) {
            List<Object> copy=new ArrayList<>();for(Object item:(List<?>)value)copy.add(deepValue(item));return Collections.unmodifiableList(copy);
        }
        return value;
    }
    private static final class Cursor {final int offset;final String revision;Cursor(int offset,String revision){this.offset=offset;this.revision=revision;}}
}
