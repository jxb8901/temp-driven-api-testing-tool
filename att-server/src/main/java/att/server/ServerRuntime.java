package att.server;

import att.metrics.InspectionMetrics;
import static att.metrics.InspectionMetrics.Stage.*;

import att.server.api.WorkerRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
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
    // Public inspection limits; keep aligned with PackageResourceInspector in the Local runtime.
    private static final int RESOURCE_DEFAULT_PAGE_SIZE=50, RESOURCE_MAX_PAGE_SIZE=100;
    private static final int TESTDATA_RECORD_MAX_PAGE_SIZE=100;
    static final long DRAFT_TTL_MILLIS=TimeUnit.MINUTES.toMillis(10);
    static final int MAX_STREAM_OBSERVERS=32;
    private static final class ValidatedScheduledScenario {
        final Map<String,Object> scenario;final String revisionDigest,model;final boolean containsSensitiveValues;
        ValidatedScheduledScenario(Map<String,Object> scenario,String revisionDigest,String model,boolean containsSensitiveValues){this.scenario=scenario;this.revisionDigest=revisionDigest;this.model=model;this.containsSensitiveValues=containsSensitiveValues;}
    }
    final ServerConfig config;
    final JobStore store;
    final AuthService auth;
    final ThreadPoolExecutor workers,streams,inspectors;
    final ConcurrentHashMap<String,Job> jobs=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,FutureTask<Void>> tasks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,AdmissionLease> leases=new ConcurrentHashMap<>();
    private final AtomicLong completed=new AtomicLong();
    private final Semaphore loadSlots,admissionSlots;
    final Semaphore streamSlots=new Semaphore(MAX_STREAM_OBSERVERS,true);
    private final ScheduledExecutorService retention;
    private final ScheduledExecutorService inspectionWatchdogs;
    private final LoadScheduleScheduler loadScheduleScheduler;
    private final WorkerProcessLauncher processLauncher;
    private final Clock clock;
    private final ConcurrentHashMap<Process,Boolean> inspectionProcesses=new ConcurrentHashMap<>();
    private final Object draftLock=new Object();
    private final ConcurrentHashMap<String,DebugDraft> debugDrafts=new ConcurrentHashMap<>();
    private record RunDraft(String id,String principal,String packageId,String revision,JsonNode workerInput,
                            Map<String,Object> preview,Instant expiresAt,DebugDraft resourceDebugDraft) { }
    private final ConcurrentHashMap<String,RunDraft> runDrafts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,QuickLoadDraft> quickLoadDrafts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,AdvancedLoadDraftState> advancedLoadDrafts=new ConcurrentHashMap<>();
    private final List<InspectionWorker> inspectionWorkerPool=new ArrayList<>();
    private final ScheduledExecutorService inspectionWarmup;
    private volatile boolean closing;
    final InspectionMetrics inspectionMetrics=new InspectionMetrics();
    private final AtomicLong inspectionWorkerSelection=new AtomicLong();
    private final byte[] cursorKey=new byte[32];
    private final SecureRandom cursorRandom=new SecureRandom();
    private final Path workerLibs;
    ServerRuntime(ServerConfig config,String workerLibs) throws Exception {
        this(config,workerLibs,ProcessBuilder::start);
    }
    ServerRuntime(ServerConfig config,String workerLibs,WorkerProcessLauncher processLauncher) throws Exception {
        this(config,workerLibs,processLauncher,Clock.systemUTC());
    }
    ServerRuntime(ServerConfig config,String workerLibs,WorkerProcessLauncher processLauncher,Clock clock) throws Exception {
        if(Runtime.version().feature()<17)throw new IllegalStateException("ATT Server requires Java 17 or later; detected Java "+Runtime.version().feature());
        this.config=config;this.workerLibs=workerLibs==null?null:Paths.get(workerLibs).toAbsolutePath().normalize();this.processLauncher=processLauncher;this.clock=java.util.Objects.requireNonNull(clock);this.store=new JobStore(config);this.auth=new AuthService(config,store);this.loadSlots=new Semaphore(config.maxConcurrentLoad,true);this.admissionSlots=new Semaphore(config.maxConcurrent+config.queuedLimit,true);
        cursorRandom.nextBytes(cursorKey);
        java.util.concurrent.BlockingQueue<Runnable> queue=config.queuedLimit==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(config.queuedLimit);
        workers=new ThreadPoolExecutor(config.maxConcurrent,config.maxConcurrent,0,TimeUnit.MILLISECONDS,queue,r->{Thread t=new Thread(r,"att-server-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        streams=new ThreadPoolExecutor(0,MAX_STREAM_OBSERVERS,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"att-server-sse");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        java.util.concurrent.BlockingQueue<Runnable> inspectionQueue=config.inspection.queuedLimit==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(config.inspection.queuedLimit);
        inspectors=new ThreadPoolExecutor(config.inspection.maxConcurrent,config.inspection.maxConcurrent,0,TimeUnit.MILLISECONDS,inspectionQueue,r->{Thread t=new Thread(r,"att-server-inspector");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        for(int i=0;i<config.inspection.maxConcurrent;i++)inspectionWorkerPool.add(new InspectionWorker());
        retention=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-retention");t.setDaemon(true);return t;});
        inspectionWatchdogs=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-inspector-watchdog");t.setDaemon(true);return t;});
        inspectionWarmup=config.inspection.enabled&&config.inspection.warmup.enabled()
                ?java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-inspector-warmup");t.setDaemon(true);return t;}):null;
        recover();cleanupExpiredJobsSafely();retention.scheduleWithFixedDelay(this::cleanupExpiredJobsSafely,1,1,TimeUnit.HOURS);
        retention.scheduleWithFixedDelay(this::expireDraftsSafely,1,1,TimeUnit.MINUTES);
        if(config.scheduling.enabled) {
            store.recoverScheduleExecutions();
            if(!config.authenticationRequired&&config.scheduling.allowAnonymousManagement)java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Scheduled Load management is enabled without authentication; anonymous clients share full schedule control");
            loadScheduleScheduler=new LoadScheduleScheduler(config.scheduling,store,this::submitScheduledJob,clock);
            loadScheduleScheduler.start();
        } else loadScheduleScheduler=null;
        if(inspectionWarmup!=null)inspectionWarmup.scheduleWithFixedDelay(this::warmInspectionSafely,250,250,TimeUnit.MILLISECONDS);
        if(config.inspection.enabled&&!config.inspection.redactionEnabled)java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Inspection masking is disabled; ordinary package values are visible to authorized callers, while classified secrets remain protected");
        if(!config.security.checkCrossOrigin)java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("server.security.checkCrossOrigin is disabled; configure trusted proxy origin handling and retain local-auth CSRF protection");
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
        return submit(command,input,principal,serverIssuedDraft,null,false);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft,String scheduleOccurrenceId) throws Exception {
        return submit(command,input,principal,serverIssuedDraft,scheduleOccurrenceId,false);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft,String scheduleOccurrenceId,boolean scheduleIssued) throws Exception {
        long requestReceivedNanos=System.nanoTime();
        if(!List.of("run","debug","load","validate").contains(command))throw new IllegalArgumentException("Unsupported job command");
        if(!serverIssuedDraft&&(input.has("draftId")||input.has("inlineDebugInput")||input.has("inlineLoadScenario")
                ||input.has("expectedRevisionDigest")||input.has("draftResourceId")))
            throw new IllegalArgumentException("Inline execution fields require a server-issued draft");
        if(serverIssuedDraft&&"load".equals(command)) {
            if(!scheduleIssued)requireInlineLoadEnabled();
            JsonNode scenarioNode=input.get("inlineLoadScenario");
            if(scenarioNode==null||!scenarioNode.isObject())throw new IllegalArgumentException("A validated inline Load scenario is required");
            @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(scenarioNode,Map.class);
            if(scheduleIssued)validateScheduledLoadLimits(config.inlineLoad,scenario);else enforceInlineLoadLimits(scenario);
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
        if("debug".equals(command)||("run".equals(command)&&request.target!=null))validateTarget(request.target);
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
            Job job=new Job(id,command,packageId,principal,request,events);job.scheduleOccurrenceId=scheduleOccurrenceId;job.requestReceivedNanos=requestReceivedNanos;String summary=JSON.writeValueAsString(Map.of("command",command,"packageId",packageId));
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
                job.request.outputDirectory=jobPath.resolve("output").toRealPath().toString();
                List<String> classpathEntries=workerClasspath();
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
    private List<String> workerClasspath() {
        return WorkerRuntimeClasspath.entries(workerLibs,config.workerLibraryDirs);
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
        String type=event.path("type").asText("").toUpperCase();Map<String,Object> data=JSON.convertValue(event,Map.class);data.remove("type");data.remove("jobId");Map<String,Object> safe=att.server.api.internal.DiagnosticSanitizer.sanitize(data);
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
        if("local".equals(config.authMode))requireJobOwner(id,principal);
        Job j=jobs.get(id);if(j==null){Map<String,Object> stored=store.get(id);if(stored==null||!JOB_ID.matcher(id).matches())throw new NotFoundException();return String.valueOf(stored.get("status"));}
        synchronized(j){if(j.terminal())return j.status;append(j,"status",Map.of("jobId",id,"status","CANCEL_REQUESTED"));
            Process p=j.process;if(p==null){FutureTask<Void> task=tasks.remove(id);if(task!=null){boolean cancelled=task.cancel(false);workers.remove(task);if(cancelled){AdmissionLease lease=leases.remove(id);if(lease!=null)lease.release();}}finish(j,"CANCELLED",143);jobs.remove(id,j);}
            else {terminateTree(p);try{if(!p.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS)){forceTree(p);p.waitFor();}}catch(InterruptedException e){Thread.currentThread().interrupt();forceTree(p);}finish(j,"CANCELLED",143);}}
        store.audit(principal,"CANCEL",id,j.packageId,"CANCELLED");return j.status;
    }
    void requireJobOwner(String id,String principal) throws Exception {
        if(!JOB_ID.matcher(id).matches())throw new NotFoundException();
        if("local".equals(config.authMode)&&!store.ownsJob(id,principal))throw new NotFoundException();
    }
    List<Map<String,Object>> jobsFor(String principal,String search) throws Exception {
        if("local".equals(config.authMode)){List<Map<String,Object>> owned=store.listOwned(100,principal);if(search==null||search.isBlank())return owned;String q=search.toLowerCase(java.util.Locale.ROOT);return owned.stream().filter(row->String.valueOf(row.get("principal")).toLowerCase(java.util.Locale.ROOT).contains(q)).toList();}
        return store.list(100,search);
    }
    Job job(String id){if(!JOB_ID.matcher(id).matches())throw new NotFoundException();Job j=jobs.get(id);if(j==null)throw new NotFoundException();return j;}
    Map<String,Object> jobRecord(String id) throws Exception {
        if(!JOB_ID.matcher(id).matches())throw new NotFoundException();
        Job active=jobs.get(id);
        if(active!=null) {
            // Terminal status must not become visible before finish() has persisted
            // the result, diagnostic, and final event. All of these share the Job monitor.
            synchronized(active) { return active.view(); }
        }
        Map<String,Object> record=store.get(id);if(record==null)throw new NotFoundException();
        record.put("executionType",Job.executionType(String.valueOf(record.get("command"))));
        record.remove("resultJson");record.remove("diagnosticJson");
        String performance=(String)record.remove("performanceJson");
        if(performance!=null&&!performance.isBlank())record.put("performance",JSON.readValue(performance,Map.class));
        return record;
    }
    Map<String,Object> resultRecord(String id) throws Exception {
        if(!JOB_ID.matcher(id).matches())throw new NotFoundException();
        Job active=jobs.get(id);
        if(active!=null) {
            synchronized(active) {
                // Avoid mixing an uncommitted terminal Job view with an older H2 snapshot.
                // Reading while holding the Job lock also waits for a concurrent finish().
                Map<String,Object> record=store.get(id);
                if(record==null)throw new NotFoundException();
                String resultJson=active.resultJson!=null?active.resultJson:(String)record.get("resultJson");
                String diagnosticJson=active.diagnosticJson!=null?active.diagnosticJson:(String)record.get("diagnosticJson");
                Map<String,Object> out=new LinkedHashMap<>();
                out.put("job",active.view());
                out.put("result",resultJson==null?null:publicJson(id,resultJson));
                out.put("diagnostic",diagnosticJson==null?null:publicJson(id,diagnosticJson));
                return out;
            }
        }
        Map<String,Object> record=store.get(id);if(record==null)throw new NotFoundException();
        Map<String,Object> out=new LinkedHashMap<>();out.put("job",jobRecord(id));
        String resultJson=(String)record.get("resultJson"),diagnosticJson=(String)record.get("diagnosticJson");
        out.put("result",resultJson==null?null:publicJson(id,resultJson));
        out.put("diagnostic",diagnosticJson==null?null:publicJson(id,diagnosticJson));
        return out;
    }
    List<Map<String,Object>> packages(){List<Map<String,Object>> out=new ArrayList<>();config.packages.forEach((id,path)->out.add(Map.of("packageId",id,"name",path.getFileName().toString())));return out;}
    Map<String,Object> packageView(String id){Path root=config.packages.get(id);if(root==null)throw new NotFoundException();return Map.of("packageId",id,"name",root.getFileName().toString());}
    Map<String,Object> inspectResource(String packageId,String action,String type,String resourceId,String query,int requestedLimit,String cursor,String principal) throws Exception {
        return inspectResource(packageId,action,type,resourceId,query,requestedLimit,cursor,"name","asc",null,principal);
    }
    Map<String,Object> inspectResource(String packageId,String action,String type,String resourceId,String query,int requestedLimit,String cursor,String sourceOriginPath,String principal) throws Exception {
        return inspectResource(packageId,action,type,resourceId,query,requestedLimit,cursor,"name","asc",sourceOriginPath,principal);
    }
    Map<String,Object> inspectResource(String packageId,String action,String type,String resourceId,String query,int requestedLimit,String cursor,
                                       String sort,String direction,String sourceOriginPath,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(!List.of("list","detail","source","source-file").contains(action))throw new IllegalArgumentException("Unsupported inspection action");
        if(type!=null&&!List.of("case","template","flow","tool").contains(type))throw new IllegalArgumentException("Unsupported resource type");
        String normalizedSort=sort==null||sort.isBlank()?"name":sort;
        String normalizedDirection=direction==null||direction.isBlank()?"asc":direction;
        if(!List.of("name","type","logicalId","state").contains(normalizedSort))throw new IllegalArgumentException("Unsupported resource sort field");
        if(!List.of("asc","desc").contains(normalizedDirection))throw new IllegalArgumentException("Resource sort direction must be asc or desc");
        if(!"list".equals(action)&&((sort!=null&& !"name".equals(sort))||(direction!=null&& !"asc".equals(direction))))
            throw new IllegalArgumentException("Resource sorting is only valid for list requests");
        if(sourceOriginPath!=null&&(sourceOriginPath.length()>1024||containsControl(sourceOriginPath)))throw new IllegalArgumentException("Source origin is invalid");
        if(sourceOriginPath!=null&& !"source-file".equals(action))throw new IllegalArgumentException("Source origin is only valid for a referenced source file");
        String normalizedQuery=query==null?"":query.trim();if(normalizedQuery.length()>200||containsControl(normalizedQuery))throw new IllegalArgumentException("query must contain at most 200 printable characters");
        int limit=requestedLimit==0?RESOURCE_DEFAULT_PAGE_SIZE:requestedLimit;
        if(limit<1||limit>RESOURCE_MAX_PAGE_SIZE)throw new IllegalArgumentException("limit must be between 1 and 100");
        Cursor prior=cursor==null||cursor.isEmpty()?null:decodeCursor(cursor,packageId,type,normalizedQuery,normalizedSort,normalizedDirection,principal);
        WorkerRequest request=inspectionRequest(packageId,root,null);
        request.inspectionAction=action;request.inspectionType=type;request.inspectionResourceId=resourceId;request.inspectionQuery=normalizedQuery;request.inspectionOffset=prior==null?0:prior.offset;request.inspectionLimit=limit;
        request.inspectionOrigin=sourceOriginPath;
        request.inspectionSort=normalizedSort;request.inspectionDirection=normalizedDirection;
        request.expectedRevisionDigest=prior==null?null:prior.revision;
        Map<String,Object> result=executeInspection(root,request);
        Object revision=result.remove("revisionDigest");
        if(revision instanceof String)result.put("_cacheRevision",inspectionCacheToken(List.of(packageId,principal,revision)));
        if("list".equals(action)) {
            Object next=result.remove("nextOffset");
            if(next instanceof Number&&revision instanceof String)result.put("nextCursor",encodeCursor(packageId,type,normalizedQuery,normalizedSort,normalizedDirection,((Number)next).intValue(),(String)revision,principal));
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
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!"virtualUsers".equals(model)&&!"arrivalRate".equals(model))throw new IllegalArgumentException("model is invalid");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>512)throw new IllegalArgumentException("resourceId is invalid");
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="quick-load-form";request.inspectionType=type;request.inspectionResourceId=resourceId;request.loadModel=model;
        Map<String,Object> result=executeInspection(root,request);result.remove("revisionDigest");result.remove("normalizedScenario");return result;
    }
    Map<String,Object> inspectQuickLoadPolicy(String packageId,String model,String environment,String templateId,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!"virtualUsers".equals(model)&&!"arrivalRate".equals(model))throw new IllegalArgumentException("model is invalid");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="quick-load-policy";request.loadModel=model;request.inspectionPath=templateId;
        Map<String,Object> result=executeInspection(root,request);result.remove("revisionDigest");return result;
    }
    private Map<String,Object> inspectDebugFormInternal(String packageId,String type,String resourceId,String environment,
                                                        String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>512)throw new IllegalArgumentException("resourceId is invalid");
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="debug-form";request.inspectionType=type;request.inspectionResourceId=resourceId;
        return executeInspection(root,request);
    }
    Map<String,Object> createDebugDraft(JsonNode input,String principal) throws Exception {
        DebugDraft draft=buildDebugDraft(input,principal);
        synchronized(draftLock) {
            expireDrafts();
            checkDraftCapacity(principal);
            debugDrafts.put(draft.id,draft);
        }
        return debugDraftView(draft);
    }
    private DebugDraft buildDebugDraft(JsonNode input,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
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
        if(!submitted.containsKey("schemaVersion"))submitted.put("schemaVersion","att-debug/v1.2");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="debug-input";request.inspectionType=type;request.inspectionTargetId=logicalId;
        request.inlineDebugInput=submitted;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawNormalized=inspected.remove("normalizedInput");
        Object rawDigest=inspected.remove("revisionDigest");
        Object rawSensitive=inspected.remove("containsSensitiveValues");
        if(!(rawNormalized instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")
                ||!(rawSensitive instanceof Boolean))
            throw new IllegalStateException("Debug validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> normalized=(Map<String,Object>)JSON.convertValue(rawNormalized,Map.class);
        Object rawResource=inspected.get("resource"),rawTarget=inspected.get("target");
        if(!(rawResource instanceof Map)||!(rawTarget instanceof Map))throw new IllegalStateException("Debug validation Worker returned an invalid target");
        @SuppressWarnings("unchecked") Map<String,Object> resource=(Map<String,Object>)rawResource;
        String resourceId=String.valueOf(resource.get("resourceId"));
        @SuppressWarnings("unchecked") Map<String,Object> resolvedTarget=(Map<String,Object>)rawTarget;
        String resolvedType=String.valueOf(resolvedTarget.get("type")),resolvedId=String.valueOf(resolvedTarget.get("id"));
        DebugDraft draft=new DebugDraft("D"+UUID.randomUUID().toString().replace("-","").toUpperCase(),principal,packageId,
                environment,resolvedType,resolvedId,resourceId,(String)rawDigest,normalized,submitted,((Boolean)rawSensitive).booleanValue(),inspected,
                clock.instant().plusMillis(DRAFT_TTL_MILLIS));
        return draft;
    }
    Map<String,Object> createQuickLoadDraft(JsonNode input,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
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
            checkDraftCapacity(principal);
            quickLoadDrafts.put(draft.id,draft);
        }
        return quickLoadDraftView(draft);
    }
    Map<String,Object> createAdvancedLoadDraft(JsonNode input,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
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
        inspected.remove("containsSensitiveValues");
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
            checkDraftCapacity(principal);
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
    Map<String,Object> createRunDraft(JsonNode body,String principal) throws Exception {
        RunDefinition run=RunDefinition.parse(body);
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!config.inspection.enabled)throw new NotFoundException();
        String packageId=run.workerInput.path("packageId").asText();
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        ObjectNode input=run.workerInput.deepCopy();
        if(input.path("rerunFailed").asBoolean(false))resolveFailedCases(input,principal,packageId);
        Map<String,Object> preview;DebugDraft resourceDebugDraft=null;String revision;
        if(run.resourceTarget) {
            ObjectNode debug=JSON.createObjectNode();debug.put("packageId",packageId);
            if(body.has("environment"))debug.set("environment",body.get("environment"));
            debug.set("target",body.get("target"));debug.set("input",body.get("input"));
            resourceDebugDraft=buildDebugDraft(debug,principal);
            preview=debugDraftView(resourceDebugDraft);preview.remove("draftId");
            input.set("inlineDebugInput",JSON.valueToTree(resourceDebugDraft.input));
            input.put("draftResourceId",resourceDebugDraft.resourceId);revision=resourceDebugDraft.revisionDigest;
        } else {
            WorkerRequest request=JSON.treeToValue(input,WorkerRequest.class);
            request.protocolVersion="att-worker/v1";request.command="inspect";request.packageRoot=root.toString();
            request.jobId="I"+UUID.randomUUID().toString().replace("-","");request.inspectionAction="run-input";
            request.config=safeRelative(request.config,"config");validatePackagePath(root,request.config,"config");
            request.suites=safePaths(request.suites,"suites");if(request.suites!=null)for(String suite:request.suites)validatePackagePath(root,suite,"suites");
            request.suiteDirectory=safeRelative(request.suiteDirectory,"suiteDirectory");validatePackagePath(root,request.suiteDirectory,"suiteDirectory");
            if(request.environment!=null)request.environment=inspectionEnvironment(request.environment,"environment");
            ObjectNode policy=JSON.createObjectNode();applyInspectionPolicy(policy,packageId);
            JSON.readerForUpdating(request).readValue(policy);
            preview=executeInspection(root,request);revision=(String)preview.remove("revisionDigest");
            Object ids=preview.get("selectedCaseIds");
            if(!(ids instanceof List<?> selected)||selected.isEmpty()||selected.size()>10000)throw new IllegalArgumentException("RUN must select between 1 and 10000 cases");
            // Freeze the resolved selection; never reevaluate tags or rerun history when a schedule fires.
            input.set("caseIds",JSON.valueToTree(selected));input.remove("tags");input.remove("excludeTags");input.remove("rerunFailed");
        }
        if(revision==null||!revision.matches("[a-f0-9]{64}"))throw new IllegalStateException("RUN validation did not return a package revision");
        input.put("expectedRevisionDigest",revision);applyInspectionPolicy(input,packageId);
        RunDraft draft=new RunDraft("R"+UUID.randomUUID().toString().replace("-","").toUpperCase(),principal,packageId,revision,input,
                new LinkedHashMap<>(preview),clock.instant().plusMillis(DRAFT_TTL_MILLIS),resourceDebugDraft);
        synchronized(draftLock) {
            expireDrafts();
            checkDraftCapacity(principal);
            runDrafts.put(draft.id,draft);
        }
        return runDraftView(draft);
    }
    private void resolveFailedCases(ObjectNode input,String principal,String packageId) throws Exception {
        Path selectedManifest=null;
        for(Map<String,Object> previous:store.listOwned(100,principal)) {
            if(!packageId.equals(previous.get("packageId"))||!"run".equals(previous.get("command"))||!List.of("PASS","FAIL","ERROR","INVALID").contains(previous.get("status")))continue;
            Path output=config.dataDir.resolve("jobs").resolve(String.valueOf(previous.get("jobId"))).resolve("output");
            if(!Files.isDirectory(output))continue;
            List<Path> manifests;
            try(var files=Files.walk(output,3)){manifests=files.filter(file->file.getFileName().toString().equals("run.yaml")&&Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList();}
            if(manifests.isEmpty())continue; // A Template/Flow/Tool RUN has a specialized report, not a Case manifest.
            if(manifests.size()!=1||Files.size(manifests.get(0))>8*1024*1024)throw new IllegalArgumentException("Previous Case RUN manifest is unavailable or exceeds the selection limit");
            selectedManifest=manifests.get(0);break;
        }
        if(selectedManifest==null)throw new IllegalArgumentException("No completed owned Case RUN is available for rerunFailed");
        Object document=new Yaml().load(Files.readString(selectedManifest));
        if(!(document instanceof Map<?,?> manifest)||!(manifest.get("cases") instanceof List<?> cases))throw new IllegalArgumentException("Previous Case RUN manifest is invalid");
        List<String> failed=new ArrayList<>();
        for(Object value:cases)if(value instanceof Map<?,?> row&&List.of("FAIL","ERROR","INVALID").contains(row.get("status"))&&row.get("caseId") instanceof String id) {
            if(!input.has("caseIds")||JSON.convertValue(input.get("caseIds"),List.class).contains(id))failed.add(id);
        }
        if(failed.isEmpty())throw new IllegalArgumentException("Previous Case RUN has no failed cases matching this selection");
        input.set("caseIds",JSON.valueToTree(failed));input.remove("rerunFailed");
    }
    private Map<String,Object> runDraftView(RunDraft draft) {
        Map<String,Object> view=new LinkedHashMap<>(draft.preview);
        view.put("draftId",draft.id);view.put("schemaVersion",RunDefinition.VERSION);view.put("executionType","run");
        view.put("packageId",draft.packageId);view.put("revisionDigest",draft.revision);view.put("expiresAt",draft.expiresAt.toString());
        view.put("options",Map.of("debug",draft.workerInput.path("diagnosticMode").asBoolean()));
        return view;
    }
    private RunDraft runDraft(String id,String principal,String packageId) {
        synchronized(draftLock) {
            expireDrafts();RunDraft draft=runDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(packageId))throw new NotFoundException();
            return draft;
        }
    }
    Map<String,Object> getRunDraft(String id,String principal) {
        RunDraft draft=runDrafts.get(id);if(draft==null)throw new NotFoundException();
        return runDraftView(runDraft(id,principal,draft.packageId));
    }
    Map<String,Object> submitRunDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        requireOnlyFields(body,"packageId","draftId","principal");
        RunDraft draft=runDraft(required(body,"draftId"),principal,required(body,"packageId"));
        synchronized(draft) {
            if(runDrafts.get(draft.id)!=draft)throw new NotFoundException();
            WorkerRequest revisionRequest=inspectionRequest(draft.packageId,config.packages.get(draft.packageId),draft.workerInput.path("environment").isTextual()?draft.workerInput.path("environment").asText():null);
            if(draft.workerInput.path("config").isTextual())revisionRequest.config=draft.workerInput.path("config").asText();
            revisionRequest.inspectionAction="revision";revisionRequest.expectedRevisionDigest=draft.revision;
            try { executeInspection(config.packages.get(draft.packageId),revisionRequest); }
            catch(StaleCursorException stale) {runDrafts.remove(draft.id,draft);throw new StaleDraftException();}
            Map<String,Object> accepted=submit("run",draft.workerInput,persistedPrincipal,true);
            runDrafts.remove(draft.id,draft);
            return accepted;
        }
    }
    Map<String,Object> getDraft(String id,String principal) {
        if(id!=null&&id.matches("R[0-9A-F]{32}"))return getRunDraft(id,principal);
        if(DRAFT_ID.matcher(id==null?"":id).matches())return getDebugDraft(id,principal);
        if(QUICK_LOAD_DRAFT_ID.matcher(id==null?"":id).matches())return getQuickLoadDraft(id,principal);
        if(ADVANCED_LOAD_DRAFT_ID.matcher(id==null?"":id).matches())return getAdvancedLoadDraft(id,principal);
        throw new NotFoundException();
    }
    Map<String,Object> submitDebugDraft(JsonNode body,String principal) throws Exception {
        return submitDebugDraft(body,principal,principal);
    }
    Map<String,Object> submitDebugDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId","principal");
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
            applyInspectionPolicy(internal,draft.packageId);
            requireLiveDraft(draft);
            Map<String,Object> accepted=submit("debug",internal,persistedPrincipal,true);
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
        return submitQuickLoadDraft(body,principal,principal);
    }
    Map<String,Object> submitQuickLoadDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        requireInlineLoadEnabled();
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId","principal");
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
            applyInspectionPolicy(internal,draft.packageId);
            requireLiveDraft(draft.expiresAt);
            Map<String,Object> accepted=submit("load",internal,persistedPrincipal,true);
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
        return submitAdvancedLoadDraft(body,principal,principal);
    }
    Map<String,Object> submitAdvancedLoadDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        requireInlineLoadEnabled();
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId","principal");
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
            applyInspectionPolicy(internal,draft.packageId);
            requireLiveDraft(draft.expiresAt);
            Map<String,Object> accepted=submit("load",internal,persistedPrincipal,true);
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
        return submitLoadDraft(body,principal,principal);
    }
    Map<String,Object> submitLoadDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        requireObject(body,"A JSON object is required");
        String draftId=required(body,"draftId");
        if(QUICK_LOAD_DRAFT_ID.matcher(draftId).matches())return submitQuickLoadDraft(body,principal,persistedPrincipal);
        if(ADVANCED_LOAD_DRAFT_ID.matcher(draftId).matches())return submitAdvancedLoadDraft(body,principal,persistedPrincipal);
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
        request.externalTextSources=config.inspection.externalTextSources(packageId);
        request.externalSourceRoots=config.inspection.externalSourceRoots;
        request.sourceBrowsingEnabled=config.inspection.sourceBrowsingEnabled;request.sourceBrowsingPolicy=config.inspection.sourcePolicy;
        request.sourceDenyGlobs=config.inspection.sourceDenyGlobs;request.redactionEnabled=config.inspection.redactionEnabled;
        request.sensitivePaths=config.inspection.sensitivePaths;request.sensitiveFieldPatterns=config.inspection.sensitiveFieldPatterns;
        return request;
    }
    private void applyInspectionPolicy(com.fasterxml.jackson.databind.node.ObjectNode request,String packageId) {
        request.set("safeTextSources",JSON.valueToTree(config.inspection.safeTextSources(packageId)));
        request.set("externalTextSources",JSON.valueToTree(config.inspection.externalTextSources(packageId)));
        request.set("externalSourceRoots",JSON.valueToTree(config.inspection.externalSourceRoots));
        request.put("maxSourceBytes",config.inspection.maxSourceBytes);request.put("maxResponseBytes",config.inspection.maxResponseBytes);
        request.put("sourceBrowsingEnabled",config.inspection.sourceBrowsingEnabled);request.put("sourceBrowsingPolicy",config.inspection.sourcePolicy);
        request.set("sourceDenyGlobs",JSON.valueToTree(config.inspection.sourceDenyGlobs));request.put("redactionEnabled",config.inspection.redactionEnabled);
        request.set("sensitivePaths",JSON.valueToTree(config.inspection.sensitivePaths));request.set("sensitiveFieldPatterns",JSON.valueToTree(config.inspection.sensitiveFieldPatterns));
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
    private void checkDraftCapacity(String principal) {
        long total=debugDrafts.size()+quickLoadDrafts.size()+advancedLoadDrafts.size()+runDrafts.size();
        long owned=debugDrafts.values().stream().filter(d->d.principal.equals(principal)).count()
                +quickLoadDrafts.values().stream().filter(d->d.principal.equals(principal)).count()
                +advancedLoadDrafts.values().stream().filter(d->d.principal.equals(principal)).count()
                +runDrafts.values().stream().filter(d->d.principal.equals(principal)).count();
        if(total>=MAX_ACTIVE_DRAFTS||owned>=MAX_DRAFTS_PER_PRINCIPAL)throw new DraftCapacityException();
    }
    private void expireDrafts() {
        Instant now=clock.instant();runDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
        debugDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
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
        validateScheduledLoadLimits(limits,scenario);
    }
    static void validateScheduledLoadLimits(ServerConfig.InlineLoad limits,Map<String,Object> scenario) {
        Object rawWorkloads=scenario.get("workloads");
        if(!(rawWorkloads instanceof List)||((List<?>)rawWorkloads).isEmpty())
            throw new IllegalArgumentException("Validated Load scenario must contain workloads");
        List<?> workloads=(List<?>)rawWorkloads;
        if(workloads.size()>limits.maxWorkloads)
            throw new InlineLoadLimitException("Scenario exceeds server.inlineLoad.maxWorkloads");
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
                    throw new InlineLoadLimitException("Scenario exceeds server.inlineLoad.maxAggregateArrivalRatePerSecond");
                totalArrivalRate+=arrivalRate;
                long concurrent=validatedCount(load.get("maxConcurrent"),"maxConcurrent");
                if(concurrent>limits.maxConcurrentPerWorkload)
                    throw new InlineLoadLimitException("Workload exceeds server.inlineLoad.maxConcurrentPerWorkload");
                totalArrivalConcurrency=addWithinLimit(totalArrivalConcurrency,concurrent,limits.maxTotalConcurrent,
                        "Scenario exceeds server.inlineLoad.maxTotalConcurrent");
            }
            long envelope=durationEnvelopeMillis(load);
            maximumEnvelopeMs=Math.max(maximumEnvelopeMs,envelope);
        }
        if(maximumEnvelopeMs>TimeUnit.SECONDS.toMillis(limits.maxDurationSeconds))
            throw new InlineLoadLimitException("Scenario exceeds server.inlineLoad.maxDurationSeconds");
    }
    private static long addWithinLimit(long total,long value,long maximum,String message) {
        if(value<0||total>maximum-value)throw new InlineLoadLimitException(message);
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
    Map<String,Object> inspectTestdataRecords(String packageId,String descriptorId,String environment,int offset,int limit,
                                              boolean previewGenerated,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(offset<0||limit<1||limit>TESTDATA_RECORD_MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Testdata pagination is invalid");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        String selected=environment==null||environment.isBlank()?null:inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,selected);request.inspectionType="configuration";
        request.inspectionAction="testdata-records";request.inspectionResourceId=descriptorId;request.inspectionSection=descriptorId;
        request.inspectionOffset=offset;request.inspectionLimit=limit;
        request.inspectionQuery=previewGenerated?"preview-generated":null;
        return executeInspection(root,request);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,section,offset,limit,null,null,null,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String inspectionPath,String inspectionOrigin,
                                            String inspectionQuery,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,section,offset,limit,
                inspectionPath,inspectionOrigin,inspectionQuery,false,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String inspectionPath,String inspectionOrigin,
                                            String inspectionQuery,boolean includeInherited,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,section,offset,limit,inspectionPath,
                inspectionOrigin,inspectionQuery,includeInherited,null,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String inspectionPath,String inspectionOrigin,
                                            String inspectionQuery,boolean includeInherited,String inspectionReferenceId,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,section,offset,limit,inspectionPath,
                inspectionOrigin,inspectionQuery,includeInherited,inspectionReferenceId,false,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String inspectionPath,String inspectionOrigin,
                                            String inspectionQuery,boolean includeInherited,String inspectionReferenceId,
                                            boolean includeAllFields,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        Path root=config.packages.get(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(!List.of("declared","effective","compare","testdata-records","files","source").contains(action))
            throw new IllegalArgumentException("Unsupported configuration inspection action");
        if(offset<0||limit<0||limit>100||"testdata-records".equals(action)&&limit<1)
            throw new IllegalArgumentException("Configuration pagination is invalid");
        if(inspectionPath!=null&&(inspectionPath.length()>1024||containsControl(inspectionPath)))
            throw new IllegalArgumentException("Configuration path is invalid");
        if(inspectionOrigin!=null&&(inspectionOrigin.length()>1024||containsControl(inspectionOrigin)))
            throw new IllegalArgumentException("Configuration source origin is invalid");
        if(inspectionQuery!=null&&(inspectionQuery.length()>200||containsControl(inspectionQuery)))
            throw new IllegalArgumentException("Configuration query must contain at most 200 printable characters");
        if(("source".equals(action)||"files".equals(action))&&(inspectionPath!=null&&inspectionPath.isBlank()))
            throw new IllegalArgumentException("Configuration path is invalid");
        if("source".equals(action)&&(inspectionPath==null||inspectionPath.isBlank()))
            throw new IllegalArgumentException("A package-relative source path is required");
        if("source".equals(action)) {
            if(inspectionReferenceId==null||inspectionReferenceId.isBlank()) {
                if((inspectionOrigin!=null&&!inspectionOrigin.isBlank())||!inspectionPath.startsWith("config/"))throw new NotFoundException();
            } else {
                JsonNode claim=decodeConfigurationSourceReference(inspectionReferenceId);
                if(!packageId.equals(claim.path("packageId").asText())
                        ||!java.util.Objects.equals(inspectionOrigin,claim.path("origin").asText())
                        ||!java.util.Objects.equals(inspectionPath,claim.path("target").asText()))throw new NotFoundException();
            }
        }
        String selected=null,other=null;
        if(("effective".equals(action)||"testdata-records".equals(action))&&environment!=null&&!environment.isBlank())selected=inspectionEnvironment(environment,"environment");
        if("compare".equals(action)){
            selected=inspectionEnvironment(environment,"left environment");
            other=inspectionEnvironment(otherEnvironment,"right environment");
            if(selected.equalsIgnoreCase(other))throw new IllegalArgumentException("left and right environments must differ");
        }
        WorkerRequest request=inspectionRequest(packageId,root,selected);
        request.inspectionAction=action;request.inspectionType="configuration";request.inspectionEnvironment=selected;request.inspectionOtherEnvironment=other;
        request.inspectionSection=section;request.inspectionIncludeInherited=Boolean.valueOf(includeInherited);
        request.inspectionIncludeAllFields=Boolean.valueOf(includeAllFields);
        request.inspectionOffset=offset;request.inspectionLimit=limit;
        request.redactionEnabled=config.inspection.redactionEnabled;request.sensitivePaths=config.inspection.sensitivePaths;request.sensitiveFieldPatterns=config.inspection.sensitiveFieldPatterns;
        request.inspectionPath=inspectionPath;request.inspectionOrigin=inspectionOrigin;request.inspectionQuery=inspectionQuery;
        request.inspectionReferenceId=inspectionReferenceId;
        Map<String,Object> inspected=executeInspection(root,request);
        if("source".equals(action))signConfigurationSourceReferences(packageId,inspected);
        return inspected;
    }
    private Map<String,Object> executeInspection(Path root,WorkerRequest request) throws Exception {
        Future<Map<String,Object>> future;
        long queuedAt=System.nanoTime();
        try { future=inspectors.submit(()->{inspectionMetrics.record(QUEUE,System.nanoTime()-queuedAt);return runInspectionWorker(root,request);}); }
        catch(RejectedExecutionException full){throw new InspectionCapacityException();}
        Map<String,Object> envelope;
        try { envelope=future.get(config.inspection.timeoutMs,TimeUnit.MILLISECONDS); }
        catch(java.util.concurrent.TimeoutException timeout){future.cancel(true);throw new InspectionTimeoutException();}
        catch(InterruptedException interrupted){future.cancel(true);Thread.currentThread().interrupt();throw interrupted;}
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("Package inspection failed",cause);}
        String workerError=(String)envelope.get("errorCode");
        if("ATT-RESOURCE-NOT-FOUND".equals(workerError))throw new NotFoundException();
        if("ATT-RESOURCE-CURSOR-STALE".equals(workerError))throw new StaleCursorException();
        boolean draftInspection="run-input".equals(request.inspectionAction)||"debug-input".equals(request.inspectionAction)||"debug-form".equals(request.inspectionAction)
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
                for(String key:List.of("code","summary","detail","field","file","suggestion","resourceId","sourceFile")) {
                    Object item=value.get(key);
                    if(item instanceof String) {
                        String text=att.server.api.internal.DiagnosticSanitizer.redactText((String)item);
                        text=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(text,root);
                        safe.put(key,text);
                    }
                }
                for(String key:List.of("line","column","sourceColumn"))if(value.get(key) instanceof Number)safe.put(key,value.get(key));
                if(!safe.isEmpty())diagnostics.add(safe);
            }
            if(diagnostics.isEmpty()) {
                Map<String,Object> fallback=new LinkedHashMap<>();
                fallback.put("code",workerError==null?"ATT-SERVER-DRAFT-INVALID":workerError);
                Object message=envelope.get("message");
                String summary=message instanceof String?String.valueOf(message):"Load or Debug input failed schema or target validation";
                fallback.put("summary",att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(att.server.api.internal.DiagnosticSanitizer.redactText(summary),root));
                String resourceId=request.inspectionTargetId==null?request.inspectionResourceId:request.inspectionTargetId;
                if(resourceId!=null)fallback.put("resourceId",resourceId);
                diagnostics.add(fallback);
            }
            String packageId=configuredPackageId(root);
            List<Map<String,String>> targets=diagnosticTargets(request);
            for(Map<String,Object> diagnostic:diagnostics) {
                if(packageId!=null)diagnostic.putIfAbsent("packageId",packageId);
                String environment=request.inspectionEnvironment==null?request.environment:request.inspectionEnvironment;
                if(environment!=null&&!environment.isBlank())diagnostic.putIfAbsent("environment",environment);
                if(!targets.isEmpty())diagnostic.putIfAbsent("targets",targets);
            }
            throw new DraftValidationException(diagnostics);
        }
        if("ATT-RESOURCE-RESPONSE-TOO-LARGE".equals(workerError))throw new InspectionResponseTooLargeException();
        if("ATT-RESOURCE-LIMIT".equals(workerError))throw new InspectionCapacityException();
        if(workerError!=null)throw new IllegalStateException("Package inspection Worker failed: "+workerError);
        Object raw=envelope.get("inspection");if(!(raw instanceof Map))throw new IllegalStateException("Package inspection Worker returned an invalid response");
        JsonNode safe=projectInspectionPaths(JSON.valueToTree(att.server.api.internal.DiagnosticSanitizer.sanitizeValue(raw)),root,"");
        @SuppressWarnings("unchecked") Map<String,Object> result=JSON.convertValue(safe,Map.class);
        return result;
    }
    private JsonNode projectInspectionPaths(JsonNode value,Path root,String field) {
        if(value.isTextual() && "$attDebugKeepDefault".equals(field))return value;
        if(value.isTextual())return JSON.getNodeFactory().textNode(semanticLocation(field)?value.asText():att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(value.asText(),root));
        if(value.isObject()){var copy=JSON.createObjectNode();value.fields().forEachRemaining(e->copy.set(e.getKey(),projectInspectionPaths(e.getValue(),root,semanticLocation(field)?field:e.getKey())));return copy;}
        if(value.isArray()){var copy=JSON.createArrayNode();for(JsonNode item:value)copy.add(projectInspectionPaths(item,root,field));return copy;}
        return value;
    }
    private String configuredPackageId(Path root) {
        if(root==null)return null;
        Path normalized=root.toAbsolutePath().normalize();
        for(Map.Entry<String,Path> entry:config.packages.entrySet())
            if(entry.getValue().toAbsolutePath().normalize().equals(normalized))return entry.getKey();
        return null;
    }
    private static List<Map<String,String>> diagnosticTargets(WorkerRequest request) {
        List<Map<String,String>> targets=new ArrayList<>();
        if(request.inspectionTargetId!=null) {
            Map<String,String> target=new LinkedHashMap<>();
            if(request.inspectionType!=null)target.put("type",request.inspectionType);
            target.put("id",request.inspectionTargetId);targets.add(target);return targets;
        }
        Object rawWorkloads=request.inlineLoadScenario==null?null:request.inlineLoadScenario.get("workloads");
        if(rawWorkloads instanceof List<?>)for(Object raw:(List<?>)rawWorkloads) {
            if(!(raw instanceof Map<?,?>))continue;
            Object rawTarget=((Map<?,?>)raw).get("target");if(!(rawTarget instanceof Map<?,?>))continue;
            Map<?,?> source=(Map<?,?>)rawTarget;Object type=source.get("type"),id=source.get("id");
            if(type instanceof String&&id instanceof String) {
                Map<String,String> target=new LinkedHashMap<>();target.put("type",(String)type);target.put("id",(String)id);
                if(!targets.contains(target))targets.add(target);
                if(targets.size()>=32)break;
            }
        }
        return targets;
    }
    private static String inspectionEnvironment(String value,String name) {
        if(value==null||!value.trim().matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))throw new IllegalArgumentException(name+" must be a valid profile name");
        return value.trim();
    }
    private Map<String,Object> runInspectionWorker(Path root,WorkerRequest request) throws Exception {
        String affinity=inspectionAffinity(root,request);
        InspectionWorker worker=acquireInspectionWorker(affinity);
        long started=System.nanoTime();
        try {
            Map<String,Object> response=worker.request(root,request);
            return response;
        }
        finally { inspectionMetrics.record(DISPATCH,System.nanoTime()-started);worker.lock.unlock(); }
    }
    String inspectionCacheToken(Object context) throws Exception {
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(cursorKey,"HmacSHA256"));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(JSON.writeValueAsBytes(context)));
    }
    private void warmInspectionSafely() {
        if(closing||inspectors.getActiveCount()>0||!inspectors.getQueue().isEmpty())return;
        List<ServerConfig.WarmupKey> keys=config.inspection.warmup.keys();
        for(int slot=0;slot<config.inspection.warmup.workers();slot++) {
            InspectionWorker worker=inspectionWorkerPool.get(slot);
            if(!worker.lock.tryLock())continue;
            try {
                if(closing||inspectors.getActiveCount()>0||!inspectors.getQueue().isEmpty())return;
                if(worker.process!=null&&!worker.process.isAlive())worker.discard();
                List<ServerConfig.WarmupKey> assigned=new ArrayList<>();
                for(int i=slot;i<keys.size();i+=config.inspection.warmup.workers())assigned.add(keys.get(i));
                if(assigned.isEmpty()&&!config.packages.isEmpty())assigned.add(new ServerConfig.WarmupKey(config.packages.keySet().iterator().next(),null));
                for(ServerConfig.WarmupKey key:assigned) {
                    int attempts=worker.warmAttempts.getOrDefault(key,0);
                    if(worker.warmed.contains(key)||attempts>=3||System.nanoTime()<worker.warmRetryAt)continue;
                    worker.warmAttempts.put(key,attempts+1);
                    worker.warming=true;
                    try {
                        Path root=config.packages.get(key.packageId());
                        if(root==null)continue; // Missing packages never affect Server startup.
                        WorkerRequest request=inspectionRequest(key.packageId(),root,key.environment());
                        request.inspectionAction="list";request.inspectionType="template";request.inspectionLimit=1;request.inspectionOffset=0;
                        Map<String,Object> response=worker.request(root,request);
                        if(!response.containsKey("errorCode"))worker.warmed.add(key);
                    } catch(Exception ignored) { /* No package contents or paths in background failure logs. */ }
                    finally {worker.warming=false;worker.warmRetryAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250L<<attempts);}
                    return;
                }
            } finally {worker.lock.unlock();}
        }
    }
    private String inspectionAffinity(Path root,WorkerRequest request) throws Exception {
        return att.server.api.internal.InspectionCacheIdentity.requested(root,request);
    }
    private InspectionWorker acquireInspectionWorker(String affinity) throws InterruptedException {
        int size=inspectionWorkerPool.size();
        int first=(int)Math.floorMod(inspectionWorkerSelection.getAndIncrement(),(long)size);
        for(int offset=0;offset<size;offset++) {
            InspectionWorker worker=inspectionWorkerPool.get((first+offset)%size);
            if(worker.lock.tryLock()) {
                if(worker.process!=null&&worker.process.isAlive()&&worker.holds(affinity))return worker;
                worker.lock.unlock();
            }
        }
        for(int offset=0;offset<size;offset++) {
            InspectionWorker worker=inspectionWorkerPool.get((first+offset)%size);
            if(worker.lock.tryLock())return worker;
        }
        // Interactive demand may cancel a background preload when every Worker is occupied.
        for(InspectionWorker candidate:inspectionWorkerPool)if(candidate.warming)candidate.kill();
        InspectionWorker worker=inspectionWorkerPool.get(first);
        worker.lock.lockInterruptibly();
        return worker;
    }
    private final class InspectionWorker implements AutoCloseable {
        final ReentrantLock lock=new ReentrantLock();
        private volatile boolean warming;
        private final Map<ServerConfig.WarmupKey,Integer> warmAttempts=new LinkedHashMap<>();
        private final java.util.Set<ServerConfig.WarmupKey> warmed=new java.util.HashSet<>();
        private long warmRetryAt;
        private final java.util.LinkedHashSet<String> retained=new java.util.LinkedHashSet<>();
        boolean holds(String key){synchronized(retained){return retained.contains(key);}}
        void retainActualKeys(Map<String,Object> metrics) {
            synchronized(retained) {
                retained.clear();
                Object keys=metrics.get("retainedAffinityKeys");
                if(keys instanceof List)for(Object key:(List<?>)keys)
                    if(key instanceof String&&((String)key).matches("[A-Za-z0-9_-]{43}")&&retained.size()<6)retained.add((String)key);
            }
        }
        private volatile Map<String,Object> metrics=Collections.emptyMap();
        private volatile Process process;
        private volatile InspectionProtocolReader reader;
        private volatile OutputStream writer;

        Map<String,Object> request(Path root,WorkerRequest request) throws Exception {
            ensureStarted(root);
            java.util.concurrent.atomic.AtomicBoolean timedOut=new java.util.concurrent.atomic.AtomicBoolean();
            ScheduledFuture<?> watchdog=inspectionWatchdogs.schedule(()->{
                timedOut.set(true);
                kill();
            },config.inspection.timeoutMs,TimeUnit.MILLISECONDS);
            try {
                writer.write(JSON.writeValueAsBytes(request));
                writer.write('\n');
                writer.flush();
                JsonNode result=null;
                String errorCode=null;
                Map<String,Object> workerDiagnostic=null;
                long total=0;
                while(result==null) {
                    String line=reader.readLine(config.inspection.maxResponseBytes);
                    if(line==null)throw new IllegalStateException("Package inspection Worker exited before completing the request");
                    if("\u0000OVERSIZED".equals(line))throw new InspectionResponseTooLargeException();
                    total+=line.getBytes(StandardCharsets.UTF_8).length+1L;
                    if(total>config.inspection.maxResponseBytes)throw new InspectionResponseTooLargeException();
                    JsonNode event=JSON.readTree(line);
                    if(event==null||!event.isObject())continue;
                    String eventJob=event.path("jobId").asText(null);
                    if(eventJob!=null&&!eventJob.equals(request.jobId))
                        throw new IllegalStateException("Package inspection Worker returned an event for another request");
                    String kind=event.path("type").asText("");
                    if("DIAGNOSTIC".equalsIgnoreCase(kind)) {
                        errorCode=event.path("code").asText(null);
                        workerDiagnostic=JSON.convertValue(event,Map.class);
                    }
                    if("RESULT".equalsIgnoreCase(kind))result=event;
                }
                if(timedOut.get())throw new InspectionTimeoutException();
                if(result.path("exitCode").asInt(3)!=0&&errorCode==null)
                    throw new IllegalStateException("Package inspection Worker did not complete successfully");
                Map<String,Object> response=new LinkedHashMap<>();
                if(result.path("inspectionMetrics").isObject()) {
                    Map<String,Object> reported=JSON.convertValue(result.path("inspectionMetrics"),Map.class);
                    if(result.path("inspectionMetricsDelta").asBoolean(false)) {
                        Map<String,Object> merged=new LinkedHashMap<>(metrics);merged.putAll(reported);metrics=merged;
                    } else metrics=reported;
                    retainActualKeys(metrics);
                }
                else {metrics=Collections.emptyMap();synchronized(retained){retained.clear();}}
                inspectionMetrics.add(RESPONSE_BYTES,total);
                if(errorCode!=null) {
                    response.put("errorCode",errorCode);
                    if(workerDiagnostic!=null) {
                        Map<String,Object> diagnostic=new LinkedHashMap<>();
                        for(String key:List.of("code","summary","detail","field","file","suggestion","resourceId","sourceFile")) {
                            Object value=workerDiagnostic.get(key);
                            if(value instanceof String)diagnostic.put(key,value);
                        }
                        for(String key:List.of("line","column","sourceColumn"))if(workerDiagnostic.get(key) instanceof Number)diagnostic.put(key,workerDiagnostic.get(key));
                        Object summary=diagnostic.get("summary");
                        if(!(summary instanceof String)||((String)summary).isBlank()) {
                            Object message=workerDiagnostic.get("message");
                            if(message instanceof String&&!((String)message).isBlank())diagnostic.put("summary",message);
                        }
                        if(!diagnostic.isEmpty())response.put("diagnostics",Collections.singletonList(diagnostic));
                    }
                    return response;
                }
                JsonNode inspection=result.path("result").path("summary").path("inspection");
                if(!inspection.isObject())throw new IllegalStateException("Package inspection Worker returned no inspection data");
                response.put("inspection",JSON.convertValue(inspection,Map.class));
                return response;
            } catch(Exception error) {
                discard();
                if(timedOut.get())throw new InspectionTimeoutException();
                throw error;
            } finally {
                watchdog.cancel(false);
            }
        }

        private void ensureStarted(Path root) throws Exception {
            if(closing)throw new InterruptedException("Server is shutting down");
            Process current=process;
            if(current!=null&&current.isAlive())return;
            discard();
            validatePackageRoot(root);
            List<String> classpathEntries=workerClasspath();
            String cp=String.join(java.io.File.pathSeparator,classpathEntries);
            ProcessBuilder builder=new ProcessBuilder(config.javaExecutable.toString(),"-Xmx"+config.inspection.heapMaxMb+"m",
                    "-cp",cp,"att.worker.WorkerMain","--inspection-daemon");
            builder.directory(root.toFile());
            long startupStarted=System.nanoTime();
            Process started=processLauncher.start(builder);
            inspectionMetrics.record(STARTUP,System.nanoTime()-startupStarted);
            process=started;
            inspectionProcesses.put(started,Boolean.TRUE);
            writer=started.getOutputStream();
            reader=new InspectionProtocolReader(new InputStreamReader(started.getInputStream(),StandardCharsets.UTF_8));
            if(closing){discard();throw new InterruptedException("Server is shutting down");}
            Thread stderr=new Thread(()->{
                try(java.io.InputStream in=started.getErrorStream()) {
                    byte[] buffer=new byte[8192];while(in.read(buffer)>=0) { }
                } catch(Exception ignored) { }
            },"att-server-inspector-stderr");
            stderr.setDaemon(true);
            stderr.start();
        }

        private void kill() {
            Process current=process;
            if(current!=null&&current.isAlive())forceTree(current);
        }

        private void discard() {
            Process current=process;
            process=null;
            metrics=Collections.emptyMap();
            synchronized(retained){retained.clear();}
            warmed.clear();
            if(!warming){warmAttempts.clear();warmRetryAt=0;}
            InspectionProtocolReader input=reader;reader=null;
            OutputStream output=writer;writer=null;
            if(current!=null) {
                if(current.isAlive())current.destroyForcibly();
                inspectionProcesses.remove(current);
            }
            try { if(input!=null)input.close(); } catch(Exception ignored) { }
            try { if(output!=null)output.close(); } catch(Exception ignored) { }
        }

        @Override public void close() {
            Process current=process;
            kill();lock.lock();
            try {
                discard();
                if(current!=null)try{current.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            } finally {lock.unlock();}
        }
    }
    private String encodeCursor(String packageId,String type,String query,String sort,String direction,int offset,String revision,String principal) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("packageId",packageId);payload.put("type",type);payload.put("query",query);payload.put("sort",sort);payload.put("direction",direction);payload.put("offset",offset);payload.put("revision",revision);payload.put("principal",principal);
        byte[] nonce=new byte[12];cursorRandom.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));
        byte[] ciphertext=cipher.doFinal(JSON.writeValueAsBytes(payload));byte[] token=new byte[nonce.length+ciphertext.length];
        System.arraycopy(nonce,0,token,0,nonce.length);System.arraycopy(ciphertext,0,token,nonce.length,ciphertext.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
    private void signConfigurationSourceReferences(String packageId,Map<String,Object> source) throws Exception {
        Object rawOrigin=source.get("logicalPath"),rawReferences=source.get("fileReferences");
        if(!(rawOrigin instanceof String)||!(rawReferences instanceof List))return;
        String origin=(String)rawOrigin;
        for(Object raw:(List<?>)rawReferences)if(raw instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> reference=(Map<String,Object>)raw;
            Object target=reference.get("logicalPath");
            if(target instanceof String)reference.put("referenceId",encodeConfigurationSourceReference(packageId,origin,(String)target));
        }
    }
    private String encodeConfigurationSourceReference(String packageId,String origin,String target) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("kind","configuration-source-reference");
        payload.put("packageId",packageId);payload.put("origin",origin);payload.put("target",target);
        byte[] nonce=new byte[12];cursorRandom.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal(JSON.writeValueAsBytes(payload));byte[] token=new byte[nonce.length+encrypted.length];
        System.arraycopy(nonce,0,token,0,nonce.length);System.arraycopy(encrypted,0,token,nonce.length,encrypted.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
    private JsonNode decodeConfigurationSourceReference(String token) {
        try {
            if(token==null||token.length()>4096)throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(token);if(bytes.length<28)throw new IllegalArgumentException();
            byte[] nonce=java.util.Arrays.copyOfRange(bytes,0,12),encrypted=java.util.Arrays.copyOfRange(bytes,12,bytes.length);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));
            JsonNode claim=JSON.readTree(cipher.doFinal(encrypted));
            if(!"configuration-source-reference".equals(claim.path("kind").asText())
                    ||claim.path("packageId").asText().isBlank()||claim.path("origin").asText().isBlank()
                    ||claim.path("target").asText().isBlank())throw new IllegalArgumentException();
            return claim;
        } catch(Exception invalid) { throw new NotFoundException(); }
    }
    private Cursor decodeCursor(String token,String packageId,String type,String query,String sort,String direction,String principal) throws Exception {
        if(token.length()>4096)throw new IllegalArgumentException("cursor is too long");byte[] bytes;
        try{bytes=Base64.getUrlDecoder().decode(token);}catch(IllegalArgumentException bad){throw new IllegalArgumentException("cursor is invalid");}
        if(bytes.length<28)throw new IllegalArgumentException("cursor is invalid");
        byte[] nonce=java.util.Arrays.copyOfRange(bytes,0,12),ciphertext=java.util.Arrays.copyOfRange(bytes,12,bytes.length),payload;
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));payload=cipher.doFinal(ciphertext);}
        catch(Exception bad){throw new IllegalArgumentException("cursor is invalid");}
        JsonNode value;try{value=JSON.readTree(payload);}catch(Exception bad){throw new IllegalArgumentException("cursor is invalid");}
        if(!packageId.equals(value.path("packageId").asText())||!java.util.Objects.equals(type,value.path("type").isNull()?null:value.path("type").asText())||!query.equals(value.path("query").asText())||!sort.equals(value.path("sort").asText())||!direction.equals(value.path("direction").asText())||!principal.equals(value.path("principal").asText()))throw new IllegalArgumentException("cursor does not match this resource query");
        int offset=value.path("offset").asInt(-1);String revision=value.path("revision").asText("");if(offset<1||offset>20000||!revision.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("cursor is invalid");return new Cursor(offset,revision);
    }
    private static boolean containsControl(String value){for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return true;return false;}
    List<LoadSchedule> loadSchedules() throws Exception {requireSchedulingEnabled();return store.schedules();}
    LoadSchedule loadSchedule(String id) throws Exception {requireSchedulingEnabled();LoadSchedule schedule=store.schedule(id);if(schedule==null)throw new NotFoundException();return schedule;}
    Map<String,Object> loadScheduleView(LoadSchedule schedule) {
        Map<String,Object> view=JSON.convertValue(schedule,Map.class);
        view.remove("runInput");view.put("executionType","load".equals(schedule.effectiveJobType())?"load":"run");
        view.put("revisionPolicy",schedule.runInput!=null||schedule.inlineDebugInput!=null?"reject-package-changes":"validate-current-package");
        view.remove("inlineScenario");view.remove("inlineDebugInput");view.remove("debugResourceId");view.remove("expectedRevisionDigest");
        view.put("jobType",schedule.effectiveJobType());
        view.put("inlineSnapshot",Boolean.valueOf(schedule.inlineScenario!=null||schedule.inlineDebugInput!=null||schedule.runInput!=null));
        if(schedule.runInput!=null) {
            Object target=schedule.runInput.get("target");
            if(target instanceof Map)view.put("debugTarget",target);
            else view.put("selectedCaseIds",schedule.runInput.get("caseIds"));
        }
        if("debug".equals(schedule.effectiveJobType()))view.put("debugTarget",Map.of("type",schedule.debugTargetType,"id",schedule.debugTargetId));
        return view;
    }
    List<Map<String,Object>> loadScheduleExecutions(String id,int limit) throws Exception {requireSchedulingEnabled();if(store.schedule(id)==null&&store.scheduleExecutions(id,1).isEmpty())throw new NotFoundException();return store.scheduleExecutions(id,limit);}
    LoadSchedule createLoadSchedule(JsonNode input,String principal) throws Exception {
        requireSchedulingEnabled();
        LoadSchedule schedule=buildLoadSchedule(input,null,principal);schedule.id="S"+UUID.randomUUID().toString().replace("-","").toUpperCase();schedule.createdBy=principal;schedule.createdAt=clock.instant().toString();
        store.createSchedule(schedule,config.scheduling.maxSchedules);store.audit(principal,"SCHEDULE_CREATE",null,schedule.packageId,"CREATED");return schedule;
    }
    Map<String,Object> previewLoadSchedule(JsonNode input) {
        if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
        input.fieldNames().forEachRemaining(key->{if(!Set.of("timezone","cron","runAt").contains(key))throw new IllegalArgumentException("Unknown preview field: "+key);});
        if(input.hasNonNull("runAt")) {
            if(input.hasNonNull("cron"))throw new IllegalArgumentException("Provide exactly one cron or runAt trigger");
            String fire=futureRunAt(fieldText(input,"runAt",null,true));
            String zone=fieldText(input,"timezone","UTC",false);scheduleZone(zone);
            return Map.of("timezone",zone,"runAt",fire,"nextRunAt",fire,"nextOccurrences",List.of(fire));
        }
        String timezone=fieldText(input,"timezone",null,true),cronText=fieldText(input,"cron",null,true);
        java.time.ZoneId zone=scheduleZone(timezone);CronExpression cron=CronExpression.parse(cronText,config.scheduling.minimumIntervalMinutes);
        cron.validateTimezoneMinimumInterval(zone,config.scheduling.minimumIntervalMinutes,clock.instant());
        List<String> nextOccurrences=new ArrayList<>();Instant cursor=clock.instant();
        for(int i=0;i<5;i++){cursor=cron.nextAfter(cursor,zone);nextOccurrences.add(cursor.toString());}
        return Map.of("timezone",timezone,"cron",cronText,"nextRunAt",nextOccurrences.get(0),"nextOccurrences",List.copyOf(nextOccurrences));
    }
    LoadSchedule updateLoadSchedule(String id,JsonNode input,String principal) throws Exception {
        requireSchedulingEnabled();LoadSchedule prior=store.schedule(id);if(prior==null)throw new NotFoundException();
        LoadSchedule schedule=buildLoadSchedule(input,prior,principal);schedule.id=id;schedule.createdBy=prior.createdBy;schedule.createdAt=prior.createdAt;
        schedule.updatedBy=principal;schedule.updatedAt=clock.instant().toString();
        if(!prior.enabled&&schedule.enabled||!java.util.Objects.equals(prior.cron,schedule.cron)||!java.util.Objects.equals(prior.runAt,schedule.runAt)||!prior.timezone.equals(schedule.timezone))
            schedule.nextRunAt=schedule.runAt==null?nextRun(schedule.cron,schedule.timezone,clock.instant()).toString():futureRunAt(schedule.runAt);
        else if(!schedule.enabled)schedule.nextRunAt=prior.nextRunAt;
        LoadSchedule updated=store.updateSchedule(schedule,prior.updatedAt);if(updated==null)throw new NotFoundException();
        store.audit(principal,"SCHEDULE_UPDATE",null,schedule.packageId,"UPDATED");return updated;
    }
    LoadSchedule setLoadScheduleEnabled(String id,boolean enabled,String principal) throws Exception {
        requireSchedulingEnabled();Instant now=clock.instant();LoadSchedule schedule;
        synchronized(store) {
            LoadSchedule current=store.schedule(id);if(current==null)throw new NotFoundException();
            String next=enabled?(current.runAt==null?nextRun(current.cron,current.timezone,now).toString():futureRunAt(current.runAt)):null;
            schedule=store.setScheduleEnabled(id,enabled,principal,now.toString(),next);
        }
        if(schedule==null)throw new NotFoundException();
        store.audit(principal,enabled?"SCHEDULE_RESUME":"SCHEDULE_PAUSE",null,schedule.packageId,enabled?"ENABLED":"PAUSED");
        return schedule;
    }
    void deleteLoadSchedule(String id,String principal) throws Exception {requireSchedulingEnabled();LoadSchedule schedule=store.schedule(id);if(schedule==null)throw new NotFoundException();store.deleteSchedule(id);store.audit(principal,"SCHEDULE_DELETE",null,schedule.packageId,"DELETED");}
    Map<String,Object> triggerLoadSchedule(String id,String principal) throws Exception {requireSchedulingEnabled();LoadSchedule schedule=store.schedule(id);if(schedule==null)throw new NotFoundException();Map<String,Object> occurrence=loadScheduleScheduler.trigger(schedule);store.audit(principal,"SCHEDULE_TRIGGER",String.valueOf(occurrence.get("jobId")),schedule.packageId,String.valueOf(occurrence.get("status")));return occurrence;}

    private Map<String,Object> submitScheduledJob(LoadSchedule schedule,String occurrenceId) throws Exception {
        if("local".equals(config.authMode)) {
            Map<String,Object> owner=store.authUser(schedule.createdBy);
            if(owner==null||!Boolean.TRUE.equals(owner.get("active"))||!"ADMIN".equals(owner.get("role")))throw new IllegalArgumentException("Schedule owner is no longer authorized");
        }
        if("run".equals(schedule.effectiveJobType()))return submitScheduledRun(schedule,occurrenceId);
        return "debug".equals(schedule.effectiveJobType())
                ? submitScheduledDebug(schedule,occurrenceId)
                : submitScheduledLoad(schedule,occurrenceId);
    }

    private Map<String,Object> submitScheduledRun(LoadSchedule schedule,String occurrenceId) throws Exception {
        validateScheduledRun(schedule);
        ObjectNode input=JSON.valueToTree(schedule.runInput);applyInspectionPolicy(input,schedule.packageId);
        Map<String,Object> accepted=submit("run",input,schedule.createdBy,true,occurrenceId,true);
        store.audit(schedule.createdBy,"SCHEDULE_EXECUTE",String.valueOf(accepted.get("jobId")),schedule.packageId,"ACCEPTED");
        return accepted;
    }
    private void validateScheduledRun(LoadSchedule schedule) throws Exception {
        Path root=config.packages.get(schedule.packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(schedule.runInput==null||schedule.expectedRevisionDigest==null)throw new IllegalArgumentException("Scheduled RUN snapshot is incomplete");
        if(schedule.inlineDebugInput!=null)validateScheduledDebugInput(root,schedule);
        else {
            WorkerRequest request=inspectionRequest(schedule.packageId,root,schedule.environment);
            if(schedule.config!=null){request.config=safeRelative(schedule.config,"config");validatePackagePath(root,request.config,"config");}
            request.inspectionAction="revision";request.expectedRevisionDigest=schedule.expectedRevisionDigest;
            try { executeInspection(root,request); }
            catch(StaleCursorException stale) {throw new StaleDraftException();}
        }
    }

    private Map<String,Object> submitScheduledLoad(LoadSchedule schedule,String occurrenceId) throws Exception {
        Path root=config.packages.get(schedule.packageId);
        if(root==null||!Files.isDirectory(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Configured schedule package is no longer available");
        validatePackageRoot(root);
        ValidatedScheduledScenario validated=validateScheduleScenario(root,schedule);Map<String,Object> scenario=validated.scenario;
        com.fasterxml.jackson.databind.node.ObjectNode input=JSON.createObjectNode();input.put("packageId",schedule.packageId);
        if(schedule.config!=null)input.put("config",schedule.config);if(schedule.environment!=null)input.put("environment",schedule.environment);
        input.set("inlineLoadScenario",JSON.valueToTree(scenario));input.put("expectedRevisionDigest",validated.revisionDigest);
        applyInspectionPolicy(input,schedule.packageId);
        return submit("load",input,"load-schedule:"+schedule.id,true,occurrenceId,true);
    }

    private Map<String,Object> submitScheduledDebug(LoadSchedule schedule,String occurrenceId) throws Exception {
        Path root=config.packages.get(schedule.packageId);
        if(root==null||!Files.isDirectory(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Configured schedule package is no longer available");
        validatePackageRoot(root);
        String revisionDigest=validateScheduledDebugInput(root,schedule);
        com.fasterxml.jackson.databind.node.ObjectNode input=JSON.createObjectNode();input.put("packageId",schedule.packageId);
        if(schedule.config!=null)input.put("config",schedule.config);if(schedule.environment!=null)input.put("environment",schedule.environment);
        input.set("target",JSON.valueToTree(Map.of("type",schedule.debugTargetType,"id",schedule.debugTargetId)));
        input.set("inlineDebugInput",JSON.valueToTree(schedule.inlineDebugInput));input.put("expectedRevisionDigest",revisionDigest);
        input.put("draftResourceId",schedule.debugResourceId);applyInspectionPolicy(input,schedule.packageId);
        return submit("debug",input,"debug-schedule:"+schedule.id,true,occurrenceId,true);
    }

    private String validateScheduledDebugInput(Path root,LoadSchedule schedule) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(schedule.inlineDebugInput==null||!List.of("template","flow","tool").contains(schedule.debugTargetType)
                ||schedule.debugTargetId==null||schedule.debugTargetId.isBlank()
                ||schedule.debugResourceId==null||schedule.expectedRevisionDigest==null
                ||!schedule.expectedRevisionDigest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Scheduled Debug snapshot is incomplete");
        WorkerRequest request=inspectionRequest(schedule.packageId,root,schedule.environment);
        if(schedule.config!=null){request.config=safeRelative(schedule.config,"config");validatePackagePath(root,request.config,"config");}
        request.inspectionAction="debug-input";request.inspectionType=schedule.debugTargetType;
        request.inspectionTargetId=schedule.debugTargetId;request.inlineDebugInput=deepMap(schedule.inlineDebugInput);
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawDigest=inspected.get("revisionDigest"),rawSensitive=inspected.get("containsSensitiveValues");
        Object rawResource=inspected.get("resource"),rawTarget=inspected.get("target");
        if(!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")
                ||!(rawSensitive instanceof Boolean)||!(rawResource instanceof Map)||!(rawTarget instanceof Map))
            throw new IllegalStateException("Debug validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> resource=(Map<String,Object>)rawResource;
        @SuppressWarnings("unchecked") Map<String,Object> target=(Map<String,Object>)rawTarget;
        if(!schedule.debugResourceId.equals(String.valueOf(resource.get("resourceId")))
                ||!schedule.debugTargetType.equals(String.valueOf(target.get("type")))
                ||!schedule.debugTargetId.equals(String.valueOf(target.get("id"))))
            throw new IllegalArgumentException("Scheduled Debug target changed; create a new schedule from a fresh preview");
        if(((Boolean)rawSensitive).booleanValue())
            throw new IllegalArgumentException("A Debug schedule cannot store submitted credentials, private values, or other classified sensitive fields. Keep classified package defaults protected or remove them from the override.");
        String digest=(String)rawDigest;
        if(!schedule.expectedRevisionDigest.equals(digest))
            throw new IllegalArgumentException("The package changed after this Debug schedule was created; create a new schedule from a fresh preview");
        return digest;
    }

    private ValidatedScheduledScenario validateScheduleScenario(Path root,LoadSchedule schedule) throws Exception {
        Map<String,Object> requested=schedule.inlineScenario==null?readScheduledScenario(root,schedule.scenario):deepMap(schedule.inlineScenario);
        ValidatedScheduledScenario validated=validateScheduledScenario(root,schedule,requested,schedule.inlineScenario==null);
        if(schedule.load!=null&&!schedule.load.isEmpty()) {
            Map<String,Object> overridden=withScheduledLoadOptions(validated.scenario,schedule.load);
            validated=validateScheduledScenario(root,schedule,overridden,false);
        }
        if(schedule.inlineScenario!=null&&validated.containsSensitiveValues)
            throw new IllegalArgumentException("An inline schedule cannot store credentials, private values, or other classified sensitive fields. Remove them from the Load draft or keep them in package-managed configuration.");
        validateScheduledLoadLimits(config.inlineLoad,validated.scenario);schedule.loadModel=validated.model;return validated;
    }

    private Map<String,Object> readScheduledScenario(Path root,String relative) throws Exception {
        Path scenario=root.resolve(relative).normalize();validatePackagePath(root,relative,"scenario");
        if(!Files.isRegularFile(scenario)||!scenario.toRealPath().startsWith(root.toRealPath()))throw new IllegalArgumentException("Scheduled Load scenario must be a regular package file");
        if(Files.size(scenario)>config.maxRequestBytes)throw new IllegalArgumentException("Scheduled Load scenario exceeds server.maxRequestBytes");
        org.yaml.snakeyaml.LoaderOptions options=new org.yaml.snakeyaml.LoaderOptions();options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);options.setCodePointLimit(config.maxRequestBytes);
        Object raw;try(var stream=Files.newInputStream(scenario)){raw=new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(stream);}
        if(!(raw instanceof Map<?,?>))throw new IllegalArgumentException("Scheduled Load scenario must be a YAML mapping");
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)JSON.convertValue(raw,Map.class);
        return result;
    }

    private ValidatedScheduledScenario validateScheduledScenario(Path root,LoadSchedule schedule,Map<String,Object> scenario,
                                                                  boolean packageFile) throws Exception {
        WorkerRequest request=inspectionRequest(schedule.packageId,root,schedule.environment);
        if(schedule.config!=null){request.config=safeRelative(schedule.config,"config");validatePackagePath(root,request.config,"config");}
        request.inspectionAction="load-scenario";request.inlineLoadScenario=scenario;
        if(packageFile)request.inspectionPath=schedule.scenario;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawScenario=inspected.get("normalizedScenario"),rawDigest=inspected.get("revisionDigest"),rawModel=inspected.get("model"),rawSensitive=inspected.get("containsSensitiveValues");
        if(!(rawScenario instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")
                ||!(rawModel instanceof String)||!List.of("virtualUsers","arrivalRate").contains(rawModel)||!(rawSensitive instanceof Boolean))
            throw new IllegalStateException("Load validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> normalized=(Map<String,Object>)JSON.convertValue(rawScenario,Map.class);
        return new ValidatedScheduledScenario(normalized,(String)rawDigest,(String)rawModel,((Boolean)rawSensitive).booleanValue());
    }

    private static Map<String,Object> withScheduledLoadOptions(Map<String,Object> source,Map<String,String> options) {
        @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(source,Map.class);
        Object raw=scenario.get("workloads");if(!(raw instanceof List<?> workloads)||workloads.isEmpty())throw new IllegalArgumentException("Scheduled Load scenario must contain workloads");
        List<Object> normalized=new ArrayList<>();
        for(Object value:workloads) {
            if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("Scheduled Load workload is invalid");
            @SuppressWarnings("unchecked") Map<String,Object> workload=(Map<String,Object>)JSON.convertValue(value,Map.class);
            Object prior=workload.get("load");if(!(prior instanceof Map<?,?>))throw new IllegalArgumentException("Scheduled Load pacing is invalid");
            @SuppressWarnings("unchecked") Map<String,Object> load=(Map<String,Object>)JSON.convertValue(prior,Map.class);load.putAll(options);workload.put("load",load);normalized.add(workload);
        }
        scenario.put("workloads",normalized);return scenario;
    }

    private LoadSchedule buildLoadSchedule(JsonNode input,LoadSchedule prior,String principal) throws Exception {
        if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
        Set<String> allowed=Set.of("name","description","tags","packageId","jobType","executionType","schemaVersion","scenario","draftId","config","environment","timezone","cron","runAt","load","maxConcurrentRuns","enabled");
        input.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalArgumentException("Unknown schedule field: "+key);});
        if(input.has("schemaVersion")&&!"att-schedule/v2".equals(fieldText(input,"schemaVersion",null,true)))throw new IllegalArgumentException("Unsupported schedule schemaVersion");
        LoadSchedule s=prior==null?new LoadSchedule():prior.copy();
        s.name=fieldText(input,"name",s.name,true);s.description=fieldText(input,"description",s.description,false);
        if(input.has("tags"))s.tags=scheduleTags(input.get("tags"));else if(s.tags==null)s.tags=List.of();
        s.packageId=fieldText(input,"packageId",s.packageId,true);
        String jobType=fieldText(input,"jobType",prior==null?"load":prior.effectiveJobType(),false);
        if(jobType==null||jobType.isBlank())jobType="load";
        if(input.has("executionType")) {
            String type=fieldText(input,"executionType",null,true);
            if(!List.of("run","load").contains(type))throw new IllegalArgumentException("executionType must be run or load");
            if(input.has("jobType")&&!type.equals(jobType))throw new IllegalArgumentException("executionType and jobType disagree");
            jobType=type;
        }
        if(!List.of("load","debug","run").contains(jobType))throw new IllegalArgumentException("jobType must be run, load or legacy debug");
        if(prior!=null&&!prior.effectiveJobType().equals(jobType))throw new IllegalArgumentException("A schedule job type is immutable; create a new schedule to change it");
        s.jobType=jobType;
        String draftId=fieldText(input,"draftId",null,false);
        if(draftId!=null) {
            if(prior!=null)throw new IllegalArgumentException("A validated draft can only create a new schedule");
            if(input.has("scenario")&&!input.get("scenario").isNull())throw new IllegalArgumentException("Provide either a validated draft or a package scenario path");
            String draftEnvironment=input.has("environment")?fieldText(input,"environment",null,false):null;
            draftEnvironment=draftEnvironment==null||draftEnvironment.isBlank()?null:inspectionEnvironment(draftEnvironment,"environment");
            if("run".equals(jobType)) {
                RunDraft draft=runDraft(draftId,principal,s.packageId);
                if(!java.util.Objects.equals(draftEnvironment,draft.workerInput.path("environment").isTextual()?draft.workerInput.path("environment").asText():null))throw new IllegalArgumentException("Schedule environment differs from the validated RUN draft");
                s.runInput=JSON.convertValue(draft.workerInput,Map.class);s.expectedRevisionDigest=draft.revision;
                s.config=draft.workerInput.path("config").isTextual()?draft.workerInput.path("config").asText():null;
                s.scenario=null;s.inlineScenario=null;s.load=new LinkedHashMap<>();
                if(draft.resourceDebugDraft!=null) {
                    DebugDraft debug=draft.resourceDebugDraft;
                    if(debug.containsSensitiveValues)throw new IllegalArgumentException("A RUN schedule cannot persist submitted sensitive values");
                    s.inlineDebugInput=deepMap(debug.scheduleInput);s.runInput.put("inlineDebugInput",s.inlineDebugInput);
                    s.debugTargetType=debug.targetType;s.debugTargetId=debug.targetId;s.debugResourceId=debug.resourceId;
                }
            } else if("debug".equals(jobType)) {
                DebugDraft debug=scheduleDraftDebug(draftId,principal,s.packageId,draftEnvironment);
                if(debug.containsSensitiveValues)throw new IllegalArgumentException("A Debug schedule cannot persist submitted credentials, private values, or other classified sensitive fields. Keep classified package defaults protected or remove them from the override.");
                s.scenario=null;s.inlineScenario=null;s.inlineDebugInput=deepMap(debug.scheduleInput);
                s.debugTargetType=debug.targetType;s.debugTargetId=debug.targetId;s.debugResourceId=debug.resourceId;
                s.expectedRevisionDigest=debug.revisionDigest;s.loadModel=null;s.load=new LinkedHashMap<>();
            } else {
                s.scenario=null;s.inlineDebugInput=null;s.debugTargetType=null;s.debugTargetId=null;s.debugResourceId=null;s.expectedRevisionDigest=null;
                s.inlineScenario=scheduleDraftScenario(draftId,principal,s.packageId,draftEnvironment);
            }
        } else if("run".equals(jobType)) {
            if(s.runInput==null)throw new IllegalArgumentException("A RUN schedule requires a validated RUN draft");
            if(input.has("scenario")||input.has("load"))throw new IllegalArgumentException("RUN schedules cannot use Load fields");
        } else if("debug".equals(jobType)) {
            if(s.inlineDebugInput==null||s.inlineScenario!=null)throw new IllegalArgumentException("A Debug schedule requires a validated Debug draft snapshot");
            if(input.has("scenario")&&!input.get("scenario").isNull()&&!input.get("scenario").asText().isBlank())
                throw new IllegalArgumentException("A Debug snapshot cannot be replaced by a Load scenario path");
            if(!s.load.isEmpty())throw new IllegalArgumentException("Debug schedules cannot use Load option overrides");
            s.scenario=null;
        } else if(s.inlineScenario==null) {
            if(s.inlineDebugInput!=null)throw new IllegalArgumentException("A Debug snapshot cannot be changed to a Load schedule");
            s.scenario=fieldText(input,"scenario",s.scenario,true);
        } else {
            if(input.has("scenario")&&!input.get("scenario").isNull()&&!input.get("scenario").asText().isBlank())
                throw new IllegalArgumentException("An inline scenario snapshot cannot be replaced by a package path");
            if(input.has("draftId"))throw new IllegalArgumentException("An inline scenario snapshot cannot be replaced");
        }
        s.config=fieldText(input,"config",s.config,false);s.environment=fieldText(input,"environment",s.environment,false);
        s.timezone=fieldText(input,"timezone",s.timezone, !input.hasNonNull("runAt")&&s.runAt==null);
        s.runAt=fieldText(input,"runAt",s.runAt,false);s.cron=fieldText(input,"cron",s.cron,s.runAt==null);
        if(s.runAt!=null&&s.cron!=null)throw new IllegalArgumentException("Provide exactly one cron or runAt trigger");
        if(s.timezone==null)s.timezone="UTC";
        if(input.has("enabled")){if(!input.get("enabled").isBoolean())throw new IllegalArgumentException("enabled must be true or false");s.enabled=input.get("enabled").booleanValue();}
        if(input.has("maxConcurrentRuns")){
            JsonNode limit=input.get("maxConcurrentRuns");if(!limit.isIntegralNumber()||!limit.canConvertToInt())throw new IllegalArgumentException("maxConcurrentRuns must be an integer");
            int maximum=limit.intValue();if(maximum<1||maximum>config.scheduling.maxConcurrentRuns)throw new IllegalArgumentException("maxConcurrentRuns must be between 1 and server.scheduling.maxConcurrentRuns");s.maxConcurrentRuns=maximum;
        }
        if(input.has("load"))s.load=loadOptions(input.get("load"));else if(s.load==null)s.load=new LinkedHashMap<>();
        if("debug".equals(s.effectiveJobType())&&s.load!=null&&!s.load.isEmpty())throw new IllegalArgumentException("Debug schedules cannot use Load option overrides");
        if(s.name.isBlank()||s.name.length()>120||containsControl(s.name))throw new IllegalArgumentException("name must be 1 to 120 characters without control characters");
        if(s.description!=null&&(s.description.length()>500||containsControl(s.description)))throw new IllegalArgumentException("description must be at most 500 characters without control characters");
        if(!config.packages.containsKey(s.packageId))throw new IllegalArgumentException("Unknown packageId");
        Path root=config.packages.get(s.packageId);validatePackageRoot(root);
        s.config=safeRelative(s.config,"config");validatePackagePath(root,s.config,"config");
        s.environment=s.environment==null||s.environment.isBlank()?null:safeText(s.environment,128,"environment");
        if("run".equals(s.effectiveJobType())) {
            JsonNode snapshot=JSON.valueToTree(s.runInput);
            String expectedEnvironment=snapshot.path("environment").isTextual()?snapshot.path("environment").asText():null;
            String expectedConfig=snapshot.path("config").isTextual()?snapshot.path("config").asText():null;
            if(!s.packageId.equals(snapshot.path("packageId").asText())||!java.util.Objects.equals(s.environment,expectedEnvironment)||!java.util.Objects.equals(s.config,expectedConfig))
                throw new IllegalArgumentException("A RUN snapshot is immutable; create a new schedule for changed values");
            if(input.has("scenario")||s.load!=null&&!s.load.isEmpty())throw new IllegalArgumentException("RUN schedules cannot use Load fields");
            validateScheduledRun(s);
        } else if("debug".equals(s.effectiveJobType())) {
            if(s.config!=null&&!"config/config.yaml".equals(s.config))throw new IllegalArgumentException("A Debug schedule must use the configuration validated by the Debug draft");
            if(prior!=null&&debugSnapshotInputChanged(prior,s,input))throw new IllegalArgumentException("A Debug snapshot is immutable; create a new schedule for changed values");
            validateScheduledDebugInput(root,s);
        } else if(s.inlineScenario==null) {
            s.scenario=safeRelative(s.scenario,"scenario");if(s.scenario==null)throw new IllegalArgumentException("scenario or draftId is required");
            validatePackagePath(root,s.scenario,"scenario");Path scenarioPath=root.resolve(s.scenario).normalize();
            if(!Files.isRegularFile(scenarioPath)||!scenarioPath.toRealPath().startsWith(root.toRealPath()))throw new IllegalArgumentException("scenario must be a regular file inside the configured package");
            if(s.inlineDebugInput!=null)throw new IllegalArgumentException("A Debug snapshot cannot be changed to a Load schedule");
        } else {
            if(!config.inlineLoad.enabled)throw new InlineLoadDisabledException();
            if(scenarioInputChanged(prior,s,input))throw new IllegalArgumentException("An inline scenario snapshot is immutable; create a new schedule for changed values");
            if(s.load!=null&&!s.load.isEmpty())throw new IllegalArgumentException("Inline scenario snapshots cannot use separate Load overrides");
        }
        java.time.ZoneId zone=scheduleZone(s.timezone);
        if(s.runAt==null) {
            CronExpression cron=CronExpression.parse(s.cron,config.scheduling.minimumIntervalMinutes);
            cron.validateTimezoneMinimumInterval(zone,config.scheduling.minimumIntervalMinutes,clock.instant());
        } else if(prior==null||!java.util.Objects.equals(s.runAt,prior.runAt)||!prior.enabled&&s.enabled)
            s.runAt=futureRunAt(s.runAt);
        if("load".equals(s.effectiveJobType())) {
            ValidatedScheduledScenario validated=validateScheduleScenario(root,s);
            if(s.inlineScenario!=null)s.inlineScenario=deepMap(validated.scenario);
        }
        s.nextRunAt=s.runAt==null?nextRun(s.cron,s.timezone,clock.instant()).toString():s.runAt;
        s.updatedBy=principal;s.updatedAt=clock.instant().toString();return s;
    }
    private String futureRunAt(String runAt) {
        Instant fire;
        try { fire=Instant.parse(runAt); } catch(java.time.format.DateTimeParseException invalid) {throw new IllegalArgumentException("runAt must be an ISO-8601 instant with UTC offset");}
        if(!fire.isAfter(clock.instant()))throw new IllegalArgumentException("runAt must be in the future");
        return fire.toString();
    }
    private Map<String,Object> scheduleDraftScenario(String draftId,String principal,String packageId,String environment) {
        synchronized(draftLock) {
            expireDrafts();
            QuickLoadDraft quick=quickLoadDrafts.get(draftId);
            if(quick!=null) {
                requireLiveDraft(quick.expiresAt);
                if(!quick.principal.equals(principal)||!quick.packageId.equals(packageId)||!java.util.Objects.equals(quick.environment,environment))throw new NotFoundException();
                return deepMap(quick.scenario);
            }
            AdvancedLoadDraftState advanced=advancedLoadDrafts.get(draftId);
            if(advanced!=null) {
                requireLiveDraft(advanced.expiresAt);
                if(!advanced.principal.equals(principal)||!advanced.packageId.equals(packageId)||!java.util.Objects.equals(advanced.environment,environment))throw new NotFoundException();
                return deepMap(advanced.scenario);
            }
            throw new NotFoundException();
        }
    }
    private DebugDraft scheduleDraftDebug(String draftId,String principal,String packageId,String environment) {
        synchronized(draftLock) {
            expireDrafts();DebugDraft draft=debugDrafts.get(draftId);
            if(draft==null)throw new NotFoundException();
            requireLiveDraft(draft.expiresAt);
            if(!draft.principal.equals(principal)||!draft.packageId.equals(packageId)||!java.util.Objects.equals(draft.environment,environment))throw new NotFoundException();
            return draft;
        }
    }
    private static boolean scenarioInputChanged(LoadSchedule prior,LoadSchedule current,JsonNode input) {
        if(prior==null||prior.inlineScenario==null)return false;
        if(input.has("draftId")||input.has("inlineScenario"))return true;
        return !java.util.Objects.equals(prior.packageId,current.packageId)
                ||!sameOptionalText(prior.environment,current.environment)
                ||!sameOptionalText(prior.config,current.config);
    }
    private static boolean debugSnapshotInputChanged(LoadSchedule prior,LoadSchedule current,JsonNode input) {
        if(prior==null||prior.inlineDebugInput==null)return false;
        if(input.has("draftId")||input.has("inlineDebugInput"))return true;
        return !java.util.Objects.equals(prior.packageId,current.packageId)
                ||!sameOptionalText(prior.environment,current.environment)
                ||!sameOptionalText(prior.config,current.config);
    }
    private static boolean sameOptionalText(String left,String right) {
        String normalizedLeft=left==null||left.isBlank()?null:left.trim();
        String normalizedRight=right==null||right.isBlank()?null:right.trim();
        return java.util.Objects.equals(normalizedLeft,normalizedRight);
    }
    private static java.time.ZoneId scheduleZone(String timezone) {
        try{java.time.ZoneId zone=java.time.ZoneId.of(timezone);if(!java.time.ZoneId.getAvailableZoneIds().contains(timezone))throw new IllegalArgumentException();return zone;}
        catch(Exception invalid){throw new IllegalArgumentException("timezone must be a valid IANA timezone");}
    }
    private static String fieldText(JsonNode input,String name,String prior,boolean required) {
        if(!input.has(name)) {if(required&&(prior==null||prior.isBlank()))throw new IllegalArgumentException(name+" is required");return prior;}
        JsonNode node=input.get(name);if(node==null||node.isNull()){if(required)throw new IllegalArgumentException(name+" is required");return null;}
        if(!node.isTextual())throw new IllegalArgumentException(name+" must be a string");String value=node.asText().trim();
        if(value.length()>512||containsControl(value))throw new IllegalArgumentException(name+" is invalid");return value;
    }
    private static Map<String,String> loadOptions(JsonNode value) {
        if(!value.isObject())throw new IllegalArgumentException("load must be an object of Load option strings");
        Set<String> allowed=Set.of("users","arrivalRate","warmup","rampUp","duration","rampDown","thinkTime","maxConcurrent","overloadPolicy");
        Map<String,String> result=new LinkedHashMap<>();value.fields().forEachRemaining(entry->{
            if(!allowed.contains(entry.getKey()))throw new IllegalArgumentException("Unsupported Load option: "+entry.getKey());
            if(!entry.getValue().isTextual())throw new IllegalArgumentException("Load option values must be strings");
            String option=entry.getValue().asText();if(option.length()>128||containsControl(option))throw new IllegalArgumentException("Load option is invalid");result.put(entry.getKey(),option);
        });return result;
    }
    private static List<String> scheduleTags(JsonNode value) {
        if(!value.isArray()||value.size()>20)throw new IllegalArgumentException("tags must be an array of at most 20 labels");
        java.util.LinkedHashSet<String> tags=new java.util.LinkedHashSet<>();
        for(JsonNode tag:value){if(!tag.isTextual())throw new IllegalArgumentException("schedule tags must be strings");String label=tag.asText().trim();
            if(!label.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,47}"))throw new IllegalArgumentException("each tag must be 1 to 48 letters, numbers, or . _ : - characters");tags.add(label);}
        return List.copyOf(tags);
    }
    private static Instant nextRun(String cron,String timezone,Instant after) {return CronExpression.parse(cron,1).nextAfter(after,java.time.ZoneId.of(timezone));}
    private void requireSchedulingEnabled(){if(!config.scheduling.enabled||loadScheduleScheduler==null)throw new NotFoundException();}
    Map<String,Object> inspectionStatistics() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("server",inspectionMetrics.snapshot());
        List<Map<String,Object>> daemons=new ArrayList<>();
        for(InspectionWorker worker:inspectionWorkerPool)daemons.add(worker.metrics);
        result.put("workers",daemons);
        return result;
    }
    Map<String,Object> counts() throws Exception {Map<String,Object> m=new LinkedHashMap<>();for(String s:List.of("QUEUED","PREPARING","RUNNING","PASS","FAIL","ERROR","INVALID","CANCELLED"))m.put(s.toLowerCase(),store.count(s));m.put("inspection",inspectionStatistics());m.put("queueDepth",workers.getQueue().size());m.put("activeWorkers",workers.getActiveCount());m.put("completedSinceStart",completed.get());m.put("loadSchedulingEnabled",config.scheduling.enabled);if(config.scheduling.enabled){m.put("loadSchedules",store.scheduleCount());m.put("activeScheduledLoads",store.activeScheduledCount(null));m.put("loadScheduleMetrics",store.scheduleMetrics());}return m;}
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
        JsonNode node=JSON.valueToTree(att.server.api.internal.DiagnosticSanitizer.sanitizeValue(data));
        return sanitize(node,output,packageRoot);
    }
    private JsonNode publicJson(String id,String json) throws Exception {
        JsonNode node=JSON.readTree(json);Object safe=att.server.api.internal.DiagnosticSanitizer.sanitizeValue(JSON.convertValue(node,Object.class));node=JSON.valueToTree(safe);Path output=config.dataDir.resolve("jobs").resolve(id).resolve("output").toAbsolutePath().normalize();
        return sanitize(node,output,packageRootForJob(id));
    }
    private static boolean semanticLocation(String field) {
        return Set.of("queue","requestQueue","requestQueues","requestQueueDefault","replyQueue","replyQueueDefault",
                "channel","queueManager","replyQueueManager","remotePath","remoteDirectory","remoteFile","host","hostname","url","baseUrl").contains(field);
    }
    private JsonNode sanitize(JsonNode node,Path output,Path packageRoot) {return sanitize(node,output,packageRoot,"");}
    private JsonNode sanitize(JsonNode node,Path output,Path packageRoot,String field) {
        if(node==null)return null;
        if(node.isTextual()) {if("$attDebugKeepDefault".equals(field) || semanticLocation(field))return node;String value=node.asText();String outputPrefix=output.toString();value=replacePathPrefix(value,outputPrefix,"artifact");if(packageRoot!=null)value=replacePathPrefix(value,packageRoot.toString(),"package");return JSON.getNodeFactory().textNode(att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(value,packageRoot));}
        if(node.isObject()){com.fasterxml.jackson.databind.node.ObjectNode copy=JSON.createObjectNode();node.fields().forEachRemaining(e->copy.set(e.getKey(),sanitize(e.getValue(),output,packageRoot,semanticLocation(field)?field:e.getKey())));return copy;}
        if(node.isArray()){com.fasterxml.jackson.databind.node.ArrayNode copy=JSON.createArrayNode();for(JsonNode item:node)copy.add(sanitize(item,output,packageRoot,field));return copy;}
        return node.deepCopy();
    }
    private static String replacePathPrefix(String value,String prefix,String logical){if(value.equals(prefix))return logical+":";String separator=java.io.File.separator;value=value.replace(prefix+separator,logical+":");if("\\".equals(separator))value=value.replace(prefix+"/",logical+":");else value=value.replace(prefix+"\\",logical+":");return value;}
    static String safeMessage(Exception e){String m=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(att.server.api.internal.DiagnosticSanitizer.redactText(e.getMessage()));return m.isEmpty()?"ATT Worker execution failed":m.length()>500?m.substring(0,500):m;}
    private static String readBoundedLine(BufferedReader reader,int limit) throws java.io.IOException {StringBuilder line=new StringBuilder();boolean oversized=false;int c;while((c=reader.read())!=-1){if(c=='\n')break;if(c=='\r')continue;if(line.length()<limit)line.append((char)c);else oversized=true;}if(c==-1&&line.isEmpty()&&!oversized)return null;return oversized?"\u0000OVERSIZED":line.toString();}
    JobEvents events(String id) throws Exception {if(!JOB_ID.matcher(id).matches()||store.get(id)==null)throw new NotFoundException();Job active=jobs.get(id);return active==null?new JobEvents(config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),config.maxEventsPerJob):active.events;}
    boolean terminal(String id) throws Exception {Job active=jobs.get(id);if(active!=null)return active.terminal();Map<String,Object> row=store.get(id);if(row==null)throw new NotFoundException();return List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(row.get("status"));}
    /** Downloads are projections; raw trusted evidence stays on disk. Binary disclosure has no public policy. */
    byte[] publicArtifact(String id, Path file) throws Exception {
        final int limit=16*1024*1024;
        byte[] bytes;
        try(var input=Files.newInputStream(file)){bytes=input.readNBytes(limit+1);}
        if(bytes.length>limit)throw new IllegalArgumentException("Artifact exceeds the safe text download limit (16 MiB)");
        String text;
        try { text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Binary artifacts require trusted filesystem access");}
        if(text.indexOf('\0')>=0)throw new IllegalArgumentException("Binary artifacts require trusted filesystem access");
        text=att.server.api.internal.DiagnosticSanitizer.redactText(text);
        text=replacePathPrefix(text,outputDirectoryForJob(id).toString(),"artifact");
        text=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(text,packageRootForJob(id));
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
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
    @Override public void close(){closing=true;if(inspectionWarmup!=null)inspectionWarmup.shutdownNow();if(loadScheduleScheduler!=null)loadScheduleScheduler.close();retention.shutdownNow();inspectionWatchdogs.shutdownNow();synchronized(draftLock){runDrafts.clear();debugDrafts.clear();quickLoadDrafts.clear();advancedLoadDrafts.clear();}for(InspectionWorker worker:inspectionWorkerPool)worker.close();for(Job j:jobs.values())if(j.process!=null&&j.process.isAlive()){try{terminateTree(j.process);if(!j.process.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS))forceTree(j.process);finishQuietly(j,"ERROR",143,"ATT-SERVER-INTERRUPTED","Server shutdown interrupted the Worker");}catch(Exception ignored){forceTree(j.process);}}for(Process process:inspectionProcesses.keySet())if(process.isAlive())forceTree(process);inspectors.shutdownNow();workers.shutdownNow();streams.shutdownNow();try{store.close();}catch(Exception ignored){}}
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
    static final class ScheduleConflictException extends RuntimeException {}
    static final class InspectionCapacityException extends RuntimeException {}
    static final class InspectionTimeoutException extends RuntimeException {}
    static final class InspectionResponseTooLargeException extends RuntimeException {}
    static final class StaleCursorException extends RuntimeException {}
    static final class StaleDraftException extends RuntimeException {}
    static final class DraftCapacityException extends RuntimeException {}
    static final class InlineLoadDisabledException extends RuntimeException {}
    static final class InlineLoadLimitException extends IllegalArgumentException {
        InlineLoadLimitException(String message){super(message);}
    }
    static final class DraftValidationException extends IllegalArgumentException {
        final List<Map<String,Object>> diagnostics;
        DraftValidationException(List<Map<String,Object>> diagnostics) {
            super(firstDiagnosticSummary(diagnostics));
            List<Map<String,Object>> copy=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> diagnostic:diagnostics)copy.add(Collections.unmodifiableMap(new LinkedHashMap<String,Object>(diagnostic)));
            this.diagnostics=Collections.unmodifiableList(copy);
        }
        private static String firstDiagnosticSummary(List<Map<String,Object>> diagnostics) {
            Object summary=diagnostics==null||diagnostics.isEmpty()?null:diagnostics.get(0).get("summary");
            return summary instanceof String&&!((String)summary).isBlank()?(String)summary:"Load or Debug input failed schema or target validation";
        }
    }
    private static final class DebugDraft {
        final String id,principal,packageId,environment,targetType,targetId,resourceId,revisionDigest;
        final Map<String,Object> input,scheduleInput,preview;
        final boolean containsSensitiveValues;
        final Instant expiresAt;
        boolean submitting;
        DebugDraft(String id,String principal,String packageId,String environment,String targetType,String targetId,
                   String resourceId,String revisionDigest,Map<String,Object> input,Map<String,Object> scheduleInput,
                   boolean containsSensitiveValues,Map<String,Object> preview,Instant expiresAt) {
            this.id=id;this.principal=principal;this.packageId=packageId;this.environment=environment;
            this.targetType=targetType;this.targetId=targetId;this.resourceId=resourceId;this.revisionDigest=revisionDigest;
            this.input=deepMap(input);this.scheduleInput=deepMap(scheduleInput);this.containsSensitiveValues=containsSensitiveValues;
            this.preview=deepMap(preview);this.expiresAt=expiresAt;
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
