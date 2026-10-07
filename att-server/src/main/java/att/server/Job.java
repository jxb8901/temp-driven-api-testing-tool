package att.server;

import att.worker.WorkerRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class Job {
    final String id,command,packageId,principal;
    final Instant createdAt=Instant.now();
    final WorkerRequest request;
    volatile String status="QUEUED";
    volatile Instant startedAt,finishedAt;
    volatile Long workerPid;
    volatile Instant workerStartTime;
    volatile Integer exitCode;
    volatile String resultJson,diagnosticJson;
    volatile boolean resultReceived;
    volatile Process process;
    final JobEvents events;
    Job(String id,String command,String packageId,String principal,WorkerRequest request,JobEvents events){this.id=id;this.command=command;this.packageId=packageId;this.principal=principal;this.request=request;this.events=events;}
    Map<String,Object> view(){Map<String,Object> m=new LinkedHashMap<>();m.put("jobId",id);m.put("command",command);m.put("packageId",packageId);m.put("principal",principal);m.put("status",status);m.put("createdAt",createdAt.toString());m.put("startedAt",startedAt==null?null:startedAt.toString());m.put("finishedAt",finishedAt==null?null:finishedAt.toString());m.put("exitCode",exitCode);return m;}
    synchronized boolean terminal(){return "PASS".equals(status)||"FAIL".equals(status)||"ERROR".equals(status)||"INVALID".equals(status)||"CANCELLED".equals(status);}
}
