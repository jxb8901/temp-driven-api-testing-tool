package att.server;

import att.worker.WorkerRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class Job {
    final String id,command,packageId,principal;
    final Instant createdAt=Instant.now();
    volatile long requestReceivedNanos=System.nanoTime();
    volatile long admittedNanos,workerStartedNanos,workerSpawnStartedNanos,workerSpawnedNanos;
    volatile long workerReadyNanos,executionReadyNanos,resultReceivedNanos,workerTerminatedNanos;
    final WorkerRequest request;
    volatile String status="QUEUED";
    volatile Instant startedAt,finishedAt;
    volatile Long workerPid;
    volatile Instant workerStartTime;
    volatile Integer exitCode;
    volatile String resultJson,diagnosticJson;
    volatile Map<String,Object> workerMetrics;
    volatile boolean resultReceived;
    volatile Process process;
    final JobEvents events;
    Job(String id,String command,String packageId,String principal,WorkerRequest request,JobEvents events){this.id=id;this.command=command;this.packageId=packageId;this.principal=principal;this.request=request;this.events=events;}
    Map<String,Object> performance(){
        Map<String,Object> timings=new LinkedHashMap<>();
        addDuration(timings,"admissionMs",requestReceivedNanos,admittedNanos);
        addDuration(timings,"queueWaitMs",admittedNanos,workerStartedNanos);
        addDuration(timings,"workerPreparationMs",workerStartedNanos,workerSpawnStartedNanos);
        addDuration(timings,"workerSpawnMs",workerSpawnStartedNanos,workerSpawnedNanos);
        addDuration(timings,"workerReadyMs",workerSpawnedNanos,workerReadyNanos);
        addDuration(timings,"executionReadyMs",workerReadyNanos,executionReadyNanos);
        addDuration(timings,"workerLifetimeMs",workerSpawnedNanos,workerTerminatedNanos);
        addDuration(timings,"resultToTerminationMs",resultReceivedNanos,workerTerminatedNanos);
        Map<String,Object> performance=new LinkedHashMap<>();
        if(!timings.isEmpty())performance.put("timings",timings);
        if(workerMetrics!=null&&!workerMetrics.isEmpty())performance.put("worker",new LinkedHashMap<>(workerMetrics));
        return performance;
    }
    private static void addDuration(Map<String,Object> target,String name,long start,long end){
        if(start>0&&end>=start)target.put(name,(end-start)/1_000_000.0);
    }
    Map<String,Object> view(){Map<String,Object> m=new LinkedHashMap<>();m.put("jobId",id);m.put("command",command);m.put("packageId",packageId);m.put("principal",principal);m.put("status",status);m.put("createdAt",createdAt.toString());m.put("startedAt",startedAt==null?null:startedAt.toString());m.put("finishedAt",finishedAt==null?null:finishedAt.toString());m.put("exitCode",exitCode);Map<String,Object> performance=performance();if(!performance.isEmpty())m.put("performance",performance);return m;}
    synchronized boolean terminal(){return "PASS".equals(status)||"FAIL".equals(status)||"ERROR".equals(status)||"INVALID".equals(status)||"CANCELLED".equals(status);}
}
