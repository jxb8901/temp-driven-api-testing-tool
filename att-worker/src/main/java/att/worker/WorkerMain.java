package att.worker;

import att.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-request, one-result process adapter for the typed engine API. */
public final class WorkerMain {
    private static final String PROTOCOL="att-worker/v1";
    private final ObjectMapper mapper=new ObjectMapper();
    private final PrintStream protocol;
    private String jobId;
    private WorkerMain(PrintStream protocol) { this.protocol=protocol; }
    public static void main(String[] args) throws Exception {
        PrintStream protocol=System.out;
        System.setOut(System.err);
        int exit=new WorkerMain(protocol).execute();
        if(exit!=0) System.exit(exit);
    }
    private int execute() throws Exception {
        JsonParser parser=mapper.getFactory().createParser(new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)));
        JsonNode input;
        try {
            input=mapper.readTree(parser);
        } catch(Exception error) {
            emit(WorkerEvent.Type.STATUS,fields("status","RUNNING"));
            return fail("WORKER_REQUEST_INVALID","Unable to parse Worker request JSON");
        }
        if(input==null) { emit(WorkerEvent.Type.STATUS,fields("status","RUNNING")); return fail("WORKER_REQUEST_INVALID","A JSON request is required"); }
        if(input!=null&&input.hasNonNull("jobId")) jobId=input.get("jobId").asText();
        try {
            if(parser.nextToken()!=null) { emit(WorkerEvent.Type.STATUS,fields("status","RUNNING")); return fail("WORKER_REQUEST_INVALID","Only one JSON request is accepted per Worker process"); }
        } catch(Exception error) {
            emit(WorkerEvent.Type.STATUS,fields("status","RUNNING"));
            return fail("WORKER_REQUEST_INVALID","Unable to parse Worker request JSON");
        }
        WorkerRequest request;
        emit(WorkerEvent.Type.STATUS,fields("status","RUNNING"));
        try { request=mapper.treeToValue(input,WorkerRequest.class); }
        catch(Exception error) { return fail("WORKER_REQUEST_INVALID","Unable to decode Worker request"); }
        if(!PROTOCOL.equals(request.protocolVersion)) return fail("WORKER_PROTOCOL_UNSUPPORTED","Unsupported protocolVersion; expected "+PROTOCOL);
        jobId=request.jobId;
        if(jobId==null||jobId.trim().isEmpty()) return fail("WORKER_REQUEST_INVALID","jobId is required");
        try {
            OperationResult result=dispatch(request);
            Map<String,Object> payload=new LinkedHashMap<String,Object>(); payload.put("status",result.status()); payload.put("exitCode",result.exitCode()); payload.put("durationMs",result.durationMs()); payload.put("result",result.toMap());
            emit(WorkerEvent.Type.RESULT,payload);
            return result.exitCode();
        } catch(Exception error) {
            String code="WORKER_EXECUTION_FAILED", message=error.getMessage();
            int exit=3; String status="ERROR";
            if(error instanceof att.validation.DiagnosticException) { code=((att.validation.DiagnosticException)error).code(); message=((att.validation.DiagnosticException)error).getMessage(); }
            else if(error instanceof IllegalArgumentException) { code="WORKER_REQUEST_INVALID"; exit=2; status="INVALID"; }
            emit(WorkerEvent.Type.DIAGNOSTIC,fields("code",code,"message",message==null?"ATT operation failed":message));
            emit(WorkerEvent.Type.RESULT,fields("status",status,"exitCode",exit,"result",fields("executionId",null,"status",status,"exitCode",exit)));
            return exit;
        }
    }
    private OperationResult dispatch(WorkerRequest r) throws Exception {
        if(r.packageRoot==null||r.packageRoot.trim().isEmpty()) throw new IllegalArgumentException("packageRoot is required");
        Path root=Paths.get(r.packageRoot).toAbsolutePath().normalize();
        Path config=path(r.config); Path output=path(r.outputDirectory);
        AttService service=new DefaultAttService();
        if("run".equals(r.command)) return service.run(new RunRequest(root,config,r.environment,output,r.runId,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),set(r.tags),set(r.excludeTags),bool(r.all),bool(r.rerunFailed),bool(r.dryRun),bool(r.failFast),null,"reject",false,event -> emitExecution(event)));
        if("debug".equals(r.command)) { Map<String,Object> t=requiredTarget(r); return service.debug(new DebugRequest(root,config,r.environment,output,r.runId,text(t,"type"),text(t,"id"),path(r.debugInput),bool(r.unsafeFailureDetails),event -> emitExecution(event),r.debugId,r.overrides)); }
        if("load".equals(r.command)) { Map<String,Object> t=r.target==null?Collections.<String,Object>emptyMap():r.target; Map<String,String> l=r.load==null?Collections.<String,String>emptyMap():r.load;
            att.load.LoadEventListener observer = event -> { try { emit(WorkerEvent.Type.PROGRESS, event.toMap(root)); } catch(Exception error) { throw new IllegalStateException(error); } };
            return service.load(new LoadRequest(root,config,r.environment,output,r.runId,path(r.scenario),optionalText(t,"type"),optionalText(t,"id"),l.get("users"),l.get("arrivalRate"),l.get("warmup"),l.get("rampUp"),l.get("duration"),l.get("rampDown"),l.get("thinkTime"),l.get("maxConcurrent"),l.get("overloadPolicy"),r.overrides,observer)); }
        if("validate".equals(r.command)) return service.validate(new ValidateRequest(root,config,r.environment,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),set(r.tags),set(r.excludeTags),bool(r.all),r.validationScope));
        if("snapshot".equals(r.command)) return service.snapshot(new SnapshotRequest(root,config,r.environment,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),bool(r.all)));
        throw new IllegalArgumentException("Unsupported Worker command: "+r.command);
    }
    private Map<String,Object> requiredTarget(WorkerRequest r) { if(r.target==null)throw new IllegalArgumentException("target is required"); return r.target; }
    private void emitExecution(att.api.ExecutionEvent event) {
        Map<String,Object> data = new LinkedHashMap<String,Object>();
        if(event.runId()!=null)data.put("executionId",event.runId()); if(event.caseId()!=null)data.put("caseId",event.caseId());
        if(event.stage()!=null)data.put("stage",event.stage()); if(event.action()!=null)data.put("action",event.action());
        if(event.status()!=null)data.put("status",event.status()); if(event.durationMs()!=null)data.put("durationMs",event.durationMs());
        if(event.message()!=null)data.put("message",event.message()); data.putAll(event.data());
        WorkerEvent.Type type=event.type()==att.api.ExecutionEvent.Type.PROGRESS||event.type()==att.api.ExecutionEvent.Type.STATUS
                ||event.type()==att.api.ExecutionEvent.Type.DEBUG
                ? WorkerEvent.Type.PROGRESS : WorkerEvent.Type.LOG;
        try { emit(type,data); } catch(Exception error) { throw new IllegalStateException(error); }
    }
    private void emit(WorkerEvent.Type type,Map<String,Object> data) throws Exception { protocol.println(mapper.writeValueAsString(new WorkerEvent(type,jobId,data).toMap())); protocol.flush(); }
    private int fail(String code,String message) throws Exception { emit(WorkerEvent.Type.DIAGNOSTIC,fields("code",code,"message",message)); emit(WorkerEvent.Type.RESULT,fields("status","INVALID","exitCode",2,"result",fields("executionId",null,"status","INVALID","exitCode",2))); return 2; }
    private static Map<String,Object> fields(Object... values) { Map<String,Object> map=new LinkedHashMap<String,Object>(); for(int i=0;i+1<values.length;i+=2)map.put(String.valueOf(values[i]),values[i+1]); return map; }
    private static boolean bool(Boolean value) { return Boolean.TRUE.equals(value); }
    private static String text(Map<String,Object> value,String key) { String found=optionalText(value,key); if(found==null||found.trim().isEmpty())throw new IllegalArgumentException("target."+key+" is required"); return found; }
    private static String optionalText(Map<String,Object> value,String key) { Object found=value.get(key); return found==null?null:String.valueOf(found); }
    private static Path path(String value) { return value==null?null:Paths.get(value); }
    private static List<Path> paths(List<String> values) { List<Path> out=new ArrayList<Path>(); if(values!=null)for(String value:values)out.add(Paths.get(value)); return out; }
    private static java.util.Set<String> set(List<String> values) { return new java.util.LinkedHashSet<String>(values==null?Collections.<String>emptyList():values); }
}
