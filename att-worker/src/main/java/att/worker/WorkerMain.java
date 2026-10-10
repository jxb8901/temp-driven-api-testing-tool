package att.worker;

import att.api.*;
import att.resource.PackageResourceInspector;
import att.resource.PackageConfigurationInspector;
import att.worker.internal.DiagnosticSanitizer;
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

/** Typed Engine process adapter with a one-request job mode and a bounded inspection daemon mode. */
public final class WorkerMain {
    private static final String PROTOCOL="att-worker/v1";
    private final ObjectMapper mapper=new ObjectMapper();
    private final WorkerResourceTelemetry resourceTelemetry=new WorkerResourceTelemetry();
    private final PrintStream protocol;
    private final LinkedHashMap<InspectorKey,PackageResourceInspector> resourceInspectors =
            new LinkedHashMap<InspectorKey,PackageResourceInspector>(8,0.75f,true);
    private String jobId;
    private boolean inspectionDaemon;
    private WorkerMain(PrintStream protocol) { this.protocol=protocol; }
    public static void main(String[] args) throws Exception {
        PrintStream protocol=System.out;
        System.setOut(System.err);
        WorkerMain worker=new WorkerMain(protocol);
        if(args.length==1&&"--inspection-daemon".equals(args[0])) {
            worker.executeInspectionDaemon();
            return;
        }
        if(args.length!=0) {
            int invalid=worker.fail("WORKER_REQUEST_INVALID","Unsupported Worker mode");
            if(invalid!=0)System.exit(invalid);
            return;
        }
        int exit=worker.execute();
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
        return executeRequest(input);
    }

    private void executeInspectionDaemon() throws Exception {
        inspectionDaemon=true;
        try(BufferedReader requests=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            String line;
            while((line=requests.readLine())!=null) {
                if(line.trim().isEmpty())continue;
                JsonNode input;
                try {
                    JsonParser parser=mapper.getFactory().createParser(line);
                    input=mapper.readTree(parser);
                    if(parser.nextToken()!=null)throw new IllegalArgumentException("Only one JSON request is accepted per line");
                } catch(Exception error) {
                    jobId=null;
                    emit(WorkerEvent.Type.STATUS,fields("status","RUNNING"));
                    fail("WORKER_REQUEST_INVALID","Unable to parse Worker request JSON");
                    continue;
                }
                executeRequest(input);
            }
        } finally {
            closeResourceInspectors();
        }
    }

    private int executeRequest(JsonNode input) throws Exception {
        if(input==null) { emit(WorkerEvent.Type.STATUS,fields("status","RUNNING")); return fail("WORKER_REQUEST_INVALID","A JSON request is required"); }
        if(input.hasNonNull("jobId")) jobId=input.get("jobId").asText();
        WorkerRequest request;
        emit(WorkerEvent.Type.STATUS,fields("status","RUNNING"));
        try { request=mapper.treeToValue(input,WorkerRequest.class); }
        catch(Exception error) { return fail("WORKER_REQUEST_INVALID","Unable to decode Worker request"); }
        if(!PROTOCOL.equals(request.protocolVersion)) return fail("WORKER_PROTOCOL_UNSUPPORTED","Unsupported protocolVersion; expected "+PROTOCOL);
        if(inspectionDaemon&&!("inspect".equals(request.command))) return fail("WORKER_REQUEST_INVALID","Inspection Workers accept only inspect requests");
        jobId=request.jobId;
        if(jobId==null||jobId.trim().isEmpty()) return fail("WORKER_REQUEST_INVALID","jobId is required");
        try {
            OperationResult result=dispatch(request);
            Map<String,Object> payload=new LinkedHashMap<String,Object>(); payload.put("status",result.status()); payload.put("exitCode",result.exitCode()); payload.put("durationMs",result.durationMs()); payload.put("result",result.toMap());
            emitResult(payload);
            return result.exitCode();
        } catch(Exception error) {
            String code="WORKER_EXECUTION_FAILED", message=error.getMessage();
            int exit=3; String status="ERROR";
            if(error instanceof att.validation.DiagnosticException) { code=((att.validation.DiagnosticException)error).code(); message=((att.validation.DiagnosticException)error).getMessage(); }
            else if(error instanceof PackageResourceInspector.ResourceNotFoundException) { code="ATT-RESOURCE-NOT-FOUND"; message="Package resource was not found"; }
            else if(error instanceof PackageResourceInspector.ResponseTooLargeException) { code="ATT-RESOURCE-RESPONSE-TOO-LARGE"; message="Package resource response exceeded the configured limit"; }
            else if(error instanceof PackageResourceInspector.ResourceLimitException) { code="ATT-RESOURCE-LIMIT"; message="Package resource inspection exceeded a configured limit"; }
            else if(error instanceof PackageResourceInspector.StaleResourceCursorException&&"debug".equals(request.command)&&request.expectedRevisionDigest!=null) { code="ATT-SERVER-DRAFT-STALE"; message="Package content changed after preview; rebuild the Debug draft"; exit=2; status="INVALID"; }
            else if(error instanceof PackageResourceInspector.StaleResourceCursorException) { code="ATT-RESOURCE-CURSOR-STALE"; message="The package resources changed; refresh the Explorer"; }
            else if(error instanceof PackageConfigurationInspector.ResponseTooLargeException) { code="ATT-RESOURCE-RESPONSE-TOO-LARGE"; message="Package configuration response exceeded the configured limit"; }
            else if(error instanceof PackageConfigurationInspector.ConfigurationLimitException) { code="ATT-RESOURCE-LIMIT"; message="Package configuration inspection exceeded a configured limit"; }
            else if(error instanceof IllegalArgumentException) { code="WORKER_REQUEST_INVALID"; exit=2; status="INVALID"; }
            Map<String,Object> diagnostic=fields("code",code,"message",DiagnosticSanitizer.redactText(message==null?"ATT operation failed":message));
            if("inspect".equals(request.command)&&("debug-input".equals(request.inspectionAction)||"debug-form".equals(request.inspectionAction))
                    &&error instanceof att.validation.DiagnosticException) {
                att.validation.DiagnosticException typed=(att.validation.DiagnosticException)error;
                diagnostic.put("summary",DiagnosticSanitizer.redactText(typed.summary()));
                if(typed.field()!=null)diagnostic.put("field",DiagnosticSanitizer.redactText(typed.field()));
                String resourceId=request.inspectionTargetId==null?request.inspectionResourceId:request.inspectionTargetId;
                if(resourceId!=null)diagnostic.put("resourceId",DiagnosticSanitizer.redactText(resourceId));
            }
            emit(WorkerEvent.Type.DIAGNOSTIC,diagnostic);
            emitResult(fields("status",status,"exitCode",exit,"result",fields("executionId",null,"status",status,"exitCode",exit)));
            return exit;
        }
    }
    private OperationResult dispatch(WorkerRequest r) throws Exception {
        if(r.packageRoot==null||r.packageRoot.trim().isEmpty()) throw new IllegalArgumentException("packageRoot is required");
        Path root=Paths.get(r.packageRoot).toAbsolutePath().normalize();
        Path config=path(r.config); Path output=path(r.outputDirectory);
        AttService service=new DefaultAttService();
        if("run".equals(r.command)) return service.run(new RunRequest(root,config,r.environment,output,r.runId,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),set(r.tags),set(r.excludeTags),bool(r.all),bool(r.rerunFailed),bool(r.dryRun),bool(r.failFast),null,"reject",false,event -> emitExecution(event)));
        if("debug".equals(r.command)) { Map<String,Object> t=requiredTarget(r);
            if(r.expectedRevisionDigest!=null) new PackageResourceInspector(root,config,r.environment,
                    r.safeTextSources,r.maxSourceBytes==null?65536:r.maxSourceBytes,
                    r.maxResponseBytes==null?262144:r.maxResponseBytes).verifyRevision(r.expectedRevisionDigest);
            return service.debug(new DebugRequest(root,config,r.environment,output,r.runId,text(t,"type"),text(t,"id"),path(r.debugInput),bool(r.unsafeFailureDetails),event -> emitExecution(event),r.debugId,r.overrides,false,null,r.inlineDebugInput)); }
        if("load".equals(r.command)) { Map<String,Object> t=r.target==null?Collections.<String,Object>emptyMap():r.target; Map<String,String> l=r.load==null?Collections.<String,String>emptyMap():r.load;
            att.load.LoadEventListener observer = event -> { try { emit(WorkerEvent.Type.PROGRESS, event.toMap(root)); } catch(Exception error) { throw new IllegalStateException(error); } };
            return service.load(new LoadRequest(root,config,r.environment,output,r.runId,path(r.scenario),optionalText(t,"type"),optionalText(t,"id"),l.get("users"),l.get("arrivalRate"),l.get("warmup"),l.get("rampUp"),l.get("duration"),l.get("rampDown"),l.get("thinkTime"),l.get("maxConcurrent"),l.get("overloadPolicy"),r.overrides,observer)); }
        if("validate".equals(r.command)) return service.validate(new ValidateRequest(root,config,r.environment,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),set(r.tags),set(r.excludeTags),bool(r.all),r.validationScope));
        if("snapshot".equals(r.command)) return service.snapshot(new SnapshotRequest(root,config,r.environment,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),bool(r.all)));
        if("inspect".equals(r.command)) {
            if("configuration".equals(r.inspectionType)) {
                PackageConfigurationInspector inspector = new PackageConfigurationInspector(root, config,
                        r.maxResponseBytes == null ? 262144 : r.maxResponseBytes);
                Map<String,Object> inspected = inspector.inspect(r.inspectionAction,
                        r.inspectionEnvironment, r.inspectionOtherEnvironment, r.inspectionSection,
                        r.inspectionOffset == null ? 0 : r.inspectionOffset,
                        r.inspectionLimit == null ? 0 : r.inspectionLimit);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            PackageResourceInspector inspector = resourceInspector(root, config, r);
            if("debug-form".equals(r.inspectionAction)) {
                Map<String,Object> inspected=inspector.inspectDebugForm(r.inspectionType,r.inspectionResourceId,r.expectedRevisionDigest);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("debug-input".equals(r.inspectionAction)) {
                Map<String,Object> inspected=inspector.validateDebugInput(r.inspectionType,r.inspectionTargetId,r.inlineDebugInput);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            Map<String,Object> inspected = inspector.inspect(r.inspectionAction, r.inspectionType,
                    r.inspectionResourceId, r.inspectionQuery,
                    r.inspectionOffset == null ? 0 : r.inspectionOffset,
                    r.inspectionLimit == null ? 50 : r.inspectionLimit,
                    r.expectedRevisionDigest);
            return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                    Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
        }
        throw new IllegalArgumentException("Unsupported Worker command: "+r.command);
    }

    private synchronized PackageResourceInspector resourceInspector(Path root,Path config,WorkerRequest request) {
        List<String> safeSources=new ArrayList<String>();
        if(request.safeTextSources!=null)safeSources.addAll(request.safeTextSources);
        Collections.sort(safeSources);
        InspectorKey key=new InspectorKey(root.toAbsolutePath().normalize().toString(),
                config==null?"":config.toString(),request.environment,safeSources,
                request.maxSourceBytes==null?65536:request.maxSourceBytes,
                request.maxResponseBytes==null?262144:request.maxResponseBytes);
        PackageResourceInspector inspector=resourceInspectors.get(key);
        if(inspector!=null)return inspector;
        inspector=new PackageResourceInspector(root,config,request.environment,safeSources,
                key.maxSourceBytes,key.maxResponseBytes,inspectionDaemon);
        resourceInspectors.put(key,inspector);
        while(resourceInspectors.size()>2) {
            Map.Entry<InspectorKey,PackageResourceInspector> eldest=resourceInspectors.entrySet().iterator().next();
            resourceInspectors.remove(eldest.getKey());
            eldest.getValue().close();
        }
        return inspector;
    }

    private synchronized void closeResourceInspectors() {
        for(PackageResourceInspector inspector:resourceInspectors.values())inspector.close();
        resourceInspectors.clear();
    }

    private static final class InspectorKey {
        final String root,config,environment;
        final List<String> safeTextSources;
        final int maxSourceBytes,maxResponseBytes;
        InspectorKey(String root,String config,String environment,List<String> safeTextSources,
                     int maxSourceBytes,int maxResponseBytes) {
            this.root=root;this.config=config;this.environment=environment;
            this.safeTextSources=Collections.unmodifiableList(new ArrayList<String>(safeTextSources));
            this.maxSourceBytes=maxSourceBytes;this.maxResponseBytes=maxResponseBytes;
        }
        @Override public boolean equals(Object other) {
            if(this==other)return true;
            if(!(other instanceof InspectorKey))return false;
            InspectorKey value=(InspectorKey)other;
            return maxSourceBytes==value.maxSourceBytes&&maxResponseBytes==value.maxResponseBytes
                    &&java.util.Objects.equals(root,value.root)&&java.util.Objects.equals(config,value.config)
                    &&java.util.Objects.equals(environment,value.environment)&&safeTextSources.equals(value.safeTextSources);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(root,config,environment,safeTextSources,maxSourceBytes,maxResponseBytes);
        }
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
    private void emit(WorkerEvent.Type type,Map<String,Object> data) throws Exception { resourceTelemetry.sample();protocol.println(mapper.writeValueAsString(new WorkerEvent(type,jobId,data).toMap())); protocol.flush(); }
    private void emitResult(Map<String,Object> payload) throws Exception {payload.put("workerMetrics",resourceTelemetry.snapshot());emit(WorkerEvent.Type.RESULT,payload);}
    private int fail(String code,String message) throws Exception { emit(WorkerEvent.Type.DIAGNOSTIC,fields("code",code,"message",message)); emitResult(fields("status","INVALID","exitCode",2,"result",fields("executionId",null,"status","INVALID","exitCode",2))); return 2; }
    private static Map<String,Object> fields(Object... values) { Map<String,Object> map=new LinkedHashMap<String,Object>(); for(int i=0;i+1<values.length;i+=2)map.put(String.valueOf(values[i]),values[i+1]); return map; }
    private static boolean bool(Boolean value) { return Boolean.TRUE.equals(value); }
    private static String text(Map<String,Object> value,String key) { String found=optionalText(value,key); if(found==null||found.trim().isEmpty())throw new IllegalArgumentException("target."+key+" is required"); return found; }
    private static String optionalText(Map<String,Object> value,String key) { Object found=value.get(key); return found==null?null:String.valueOf(found); }
    private static Path path(String value) { return value==null?null:Paths.get(value); }
    private static List<Path> paths(List<String> values) { List<Path> out=new ArrayList<Path>(); if(values!=null)for(String value:values)out.add(Paths.get(value)); return out; }
    private static java.util.Set<String> set(List<String> values) { return new java.util.LinkedHashSet<String>(values==null?Collections.<String>emptyList():values); }
}
