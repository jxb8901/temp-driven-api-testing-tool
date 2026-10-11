package att.server;

import att.worker.WorkerRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final ScheduledExecutorService inspectionWatchdogs;
    private final WorkerProcessLauncher processLauncher;
    private final ConcurrentHashMap<Process,Boolean> inspectionProcesses=new ConcurrentHashMap<>();
    private final List<InspectionWorker> inspectionWorkerPool=new ArrayList<>();
    private final AtomicLong inspectionWorkerSelection=new AtomicLong();
    private final byte[] cursorKey=new byte[32];
    private final SecureRandom cursorRandom=new SecureRandom();
    private volatile String webInfLibs;
    ServerRuntime(ServerConfig config,String webInfLibs) throws Exception {
        this(config,webInfLibs,ProcessBuilder::start);
    }
    ServerRuntime(ServerConfig config,String webInfLibs,WorkerProcessLauncher processLauncher) throws Exception {
        if(Runtime.version().feature()<17)throw new IllegalStateException("ATT Server requires Java 17 or later; detected Java "+Runtime.version().feature());
        this.config=config;this.webInfLibs=webInfLibs;this.processLauncher=processLauncher;this.store=new JobStore(config);this.loadSlots=new Semaphore(config.maxConcurrentLoad,true);this.admissionSlots=new Semaphore(config.maxConcurrent+config.queuedLimit,true);
        cursorRandom.nextBytes(cursorKey);
        java.util.concurrent.BlockingQueue<Runnable> queue=config.queuedLimit==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(config.queuedLimit);
        workers=new ThreadPoolExecutor(config.maxConcurrent,config.maxConcurrent,0,TimeUnit.MILLISECONDS,queue,r->{Thread t=new Thread(r,"att-server-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        streams=new ThreadPoolExecutor(0,MAX_STREAM_OBSERVERS,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"att-server-sse");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        java.util.concurrent.BlockingQueue<Runnable> inspectionQueue=config.inspection.queuedLimit==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(config.inspection.queuedLimit);
        inspectors=new ThreadPoolExecutor(config.inspection.maxConcurrent,config.inspection.maxConcurrent,0,TimeUnit.MILLISECONDS,inspectionQueue,r->{Thread t=new Thread(r,"att-server-inspector");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        for(int i=0;i<config.inspection.maxConcurrent;i++)inspectionWorkerPool.add(new InspectionWorker());
        retention=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-retention");t.setDaemon(true);return t;});
        inspectionWatchdogs=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-inspector-watchdog");t.setDaemon(true);return t;});
        recover();cleanupExpiredJobsSafely();retention.scheduleWithFixedDelay(this::cleanupExpiredJobsSafely,1,1,TimeUnit.HOURS);
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
        long requestReceivedNanos=System.nanoTime();
        if(!List.of("run","debug","load","validate").contains(command))throw new IllegalArgumentException("Unsupported job command");
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
        InspectionWorker worker=acquireInspectionWorker();
        try { return worker.request(root,request); }
        finally { worker.lock.unlock(); }
    }
    private InspectionWorker acquireInspectionWorker() throws InterruptedException {
        int size=inspectionWorkerPool.size();
        int first=(int)Math.floorMod(inspectionWorkerSelection.getAndIncrement(),(long)size);
        for(int offset=0;offset<size;offset++) {
            InspectionWorker worker=inspectionWorkerPool.get((first+offset)%size);
            if(worker.lock.tryLock())return worker;
        }
        InspectionWorker worker=inspectionWorkerPool.get(first);
        worker.lock.lockInterruptibly();
        return worker;
    }
    private final class InspectionWorker implements AutoCloseable {
        final ReentrantLock lock=new ReentrantLock();
        private volatile Process process;
        private volatile BufferedReader reader;
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
                long total=0;
                while(result==null) {
                    String line=readBoundedLine(reader,config.inspection.maxResponseBytes);
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
                    if("DIAGNOSTIC".equalsIgnoreCase(kind))errorCode=event.path("code").asText(null);
                    if("RESULT".equalsIgnoreCase(kind))result=event;
                }
                if(timedOut.get())throw new InspectionTimeoutException();
                if(result.path("exitCode").asInt(3)!=0&&errorCode==null)
                    throw new IllegalStateException("Package inspection Worker did not complete successfully");
                Map<String,Object> response=new LinkedHashMap<>();
                if(errorCode!=null) {response.put("errorCode",errorCode);return response;}
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
            Process current=process;
            if(current!=null&&current.isAlive())return;
            discard();
            validatePackageRoot(root);
            Path libs=webInfLibs==null?null:Paths.get(webInfLibs);
            if(libs==null||!Files.isDirectory(libs))
                throw new IllegalStateException("Tomcat must deploy the WAR as an exploded application so WEB-INF/lib is available to the Worker launcher");
            List<String> classpathEntries=new ArrayList<>();
            classpathEntries.add(libs.resolve("*").toString());
            for(Path libraryDir:config.workerLibraryDirs)classpathEntries.add(libraryDir.resolve("*").toString());
            String cp=String.join(java.io.File.pathSeparator,classpathEntries);
            ProcessBuilder builder=new ProcessBuilder(config.javaExecutable.toString(),"-Xmx"+config.inspection.heapMaxMb+"m",
                    "-cp",cp,"att.worker.WorkerMain","--inspection-daemon");
            builder.directory(root.toFile());
            Process started=processLauncher.start(builder);
            process=started;
            inspectionProcesses.put(started,Boolean.TRUE);
            writer=started.getOutputStream();
            reader=new BufferedReader(new InputStreamReader(started.getInputStream(),StandardCharsets.UTF_8));
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
            BufferedReader input=reader;reader=null;
            OutputStream output=writer;writer=null;
            if(current!=null) {
                if(current.isAlive())current.destroyForcibly();
                inspectionProcesses.remove(current);
            }
            try { if(input!=null)input.close(); } catch(Exception ignored) { }
            try { if(output!=null)output.close(); } catch(Exception ignored) { }
        }

        @Override public void close() { discard(); }
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
    @Override public void close(){retention.shutdownNow();inspectionWatchdogs.shutdownNow();for(InspectionWorker worker:inspectionWorkerPool)worker.close();for(Job j:jobs.values())if(j.process!=null&&j.process.isAlive()){try{terminateTree(j.process);if(!j.process.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS))forceTree(j.process);finishQuietly(j,"ERROR",143,"ATT-SERVER-INTERRUPTED","Server shutdown interrupted the Worker");}catch(Exception ignored){forceTree(j.process);}}for(Process process:inspectionProcesses.keySet())if(process.isAlive())forceTree(process);inspectors.shutdownNow();workers.shutdownNow();streams.shutdownNow();try{store.close();}catch(Exception ignored){}}
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
    private static final class Cursor {final int offset;final String revision;Cursor(int offset,String revision){this.offset=offset;this.revision=revision;}}
}
