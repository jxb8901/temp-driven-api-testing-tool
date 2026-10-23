package att.worker;

import att.api.*;
import att.metrics.InspectionMetrics;
import static att.metrics.InspectionMetrics.Stage.*;
import att.resource.PackageResourceInspector;
import att.resource.PackageConfigurationInspector;
import att.resource.PackageConfigurationFileInspector;
import att.config.RuntimeConfigPath;
import att.server.api.internal.DiagnosticSanitizer;
import att.server.api.internal.WorkerProtocol;
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
    private final att.resource.PackageConfigurationSnapshots configurationSnapshots=new att.resource.PackageConfigurationSnapshots();
    private final LinkedHashMap<String,Map<String,Object>> inspectionAggregates=new LinkedHashMap<String,Map<String,Object>>(16,.75f,true);
    private String jobId;
    private boolean inspectionDaemon;
    private boolean prewarmed;
    private boolean inspectionRequest;
    private int inspectionResponseLimit=262144;
    private long inspectionResponseBytes;
    private Map<String,Object> lastInspectionMetrics;
    private Path executionOutput;
    private att.load.LoadConsoleSampler loadSampler;
    private String loadSummaryLine;
    private WorkerMain(PrintStream protocol) { this.protocol=protocol; }
    public static void main(String[] args) throws Exception {
        // Trusted evidence retains original diagnostics. Protocol secrets are protected
        // in emit(); Server projects host paths with the package context.
        att.core.PathPresentation.configure(false, false);
        PrintStream protocol=System.out;
        System.setOut(System.err);
        WorkerMain worker=new WorkerMain(protocol);
        if(args.length==1&&"--inspection-daemon".equals(args[0])) {
            worker.executeInspectionDaemon();
            return;
        }
        if(args.length==1&&"--prewarmed-one-shot".equals(args[0])) {
            worker.prewarmed=true;
            // Initialize shared machinery without loading any Package/configuration
            // or environment secrets. stdin remains open until exactly one lease.
            Class.forName("att.api.DefaultAttService");
            Map<String,Object> ready=fields("type","READY","protocolVersion","att-prewarm/v1",
                    "productVersion",att.Version.PRODUCT,"gitCommit",att.Version.GIT_COMMIT,
                    "schemaRegistryDigest",att.validation.SchemaFiles.registryDigest(),
                    "cwd",Paths.get("").toRealPath().toString());
            WorkerProtocol.Frame frame=WorkerProtocol.encode(worker.mapper,ready,8192);
            frame.writeTo(protocol);protocol.println();protocol.flush();
            int exit=worker.execute();
            if(exit!=0)System.exit(exit);
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
        consoleOptions=att.console.ExecutionConsoleFormatter.Options.published();
        inspectionRequest=false;
        inspectionResponseBytes=0;inspectionResponseLimit=262144;
        if(input==null) { emit(WorkerEvent.Type.STATUS,fields("status","RUNNING")); return fail("WORKER_REQUEST_INVALID","A JSON request is required"); }
        if(input.hasNonNull("jobId")) jobId=input.get("jobId").asText();
        WorkerRequest request;
        emit(WorkerEvent.Type.STATUS,fields("status","RUNNING"));
        try { request=mapper.treeToValue(input,WorkerRequest.class); if(request.maxResponseBytes!=null)inspectionResponseLimit=request.maxResponseBytes; }
        catch(Exception error) { return fail("WORKER_REQUEST_INVALID","Unable to decode Worker request"); }
        try { consoleOptions=att.console.ExecutionConsoleFormatter.Options.published(
                request.liveOutputMaxEntryChars==null?4000:request.liveOutputMaxEntryChars,
                request.liveOutputMaxEntryLines==null?40:request.liveOutputMaxEntryLines); }
        catch(IllegalArgumentException error) { return fail("WORKER_REQUEST_INVALID",error.getMessage()); }
        inspectionRequest="inspect".equals(request.command);
        if(!PROTOCOL.equals(request.protocolVersion)) return fail("WORKER_PROTOCOL_UNSUPPORTED","Unsupported protocolVersion; expected "+PROTOCOL);
        if(inspectionDaemon&&!("inspect".equals(request.command))) return fail("WORKER_REQUEST_INVALID","Inspection Workers accept only inspect requests");
        if(prewarmed) {
            if(!("run".equals(request.command)||"debug".equals(request.command)))
                return fail("WORKER_REQUEST_INVALID","Prewarmed Workers accept only one RUN/DEBUG Job");
            if(request.packageRoot==null||!Paths.get(request.packageRoot).toRealPath().equals(Paths.get("").toRealPath()))
                return fail("WORKER_REQUEST_INVALID","Prewarmed Worker package affinity mismatch");
            if(request.expectedRevisionDigest==null)
                return fail("WORKER_REQUEST_INVALID","Prewarmed execution requires an immutable package revision");
            try { att.security.ExecutionEnvironment.assign(request.executionEnvironment); }
            catch(IllegalArgumentException|IllegalStateException invalid) {
                return fail("WORKER_REQUEST_INVALID","Invalid one-shot execution environment");
            }
        } else if(request.executionEnvironment!=null) {
            return fail("WORKER_REQUEST_INVALID","Execution environment requires the prewarm protocol");
        }
        jobId=request.jobId;
        if(jobId==null||jobId.trim().isEmpty()) return fail("WORKER_REQUEST_INVALID","jobId is required");
        if(request.expectedSchemaRegistryDigest!=null) {
            if(!request.expectedSchemaRegistryDigest.matches("[a-f0-9]{64}"))
                return fail("WORKER_SCHEMA_REGISTRY_EXPECTATION_INVALID","The Server supplied an invalid Schema Registry identity");
            String actual;
            try { actual=att.validation.SchemaFiles.registryDigest(); }
            catch(Exception unavailable) {
                return fail("WORKER_SCHEMA_REGISTRY_UNAVAILABLE","The embedded ATT Schema Registry is unavailable; reinstall the matching Local Runtime");
            }
            if(!java.security.MessageDigest.isEqual(request.expectedSchemaRegistryDigest.getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII)))
                return fail("WORKER_SCHEMA_REGISTRY_MISMATCH","The Worker Schema Registry does not match the Server's validated Local Runtime; install the matching Local archive");
        }
        long processingStarted=System.nanoTime();
        long buildsBefore=InspectionMetrics.JVM.count(INDEX_BUILD),loadsBefore=InspectionMetrics.JVM.count(CONFIG_LOAD),bytesBefore=InspectionMetrics.JVM.count(READ_BYTES);
        try {
            OperationResult result=dispatch(request);
            if(inspectionDaemon) {
                InspectionMetrics.JVM.record(PROCESSING,System.nanoTime()-processingStarted);
                if("source".equals(request.inspectionAction)||"source-file".equals(request.inspectionAction))
                    InspectionMetrics.JVM.record(SOURCE,System.nanoTime()-processingStarted);
            }
            recordInspectionAggregate(request,processingStarted,buildsBefore,loadsBefore,bytesBefore);
            Map<String,Object> payload=new LinkedHashMap<String,Object>(); payload.put("status",result.status()); payload.put("exitCode",result.exitCode()); payload.put("durationMs",result.durationMs()); payload.put("result",result.toMap());
            emitResult(payload);
            return result.exitCode();
        } catch(Exception error) {
            if(inspectionDaemon)InspectionMetrics.JVM.record(PROCESSING,System.nanoTime()-processingStarted);
            recordInspectionAggregate(request,processingStarted,buildsBefore,loadsBefore,bytesBefore);
            String code="WORKER_EXECUTION_FAILED", message=error.getMessage();
            int exit=3; String status="ERROR";
            if(error instanceof att.validation.DiagnosticException) { code=((att.validation.DiagnosticException)error).code(); message=((att.validation.DiagnosticException)error).getMessage(); }
            else if(error instanceof PackageResourceInspector.ResourceNotFoundException) { code="ATT-RESOURCE-NOT-FOUND"; message="Package resource was not found"; }
            else if(error instanceof PackageResourceInspector.ResponseTooLargeException) { code="ATT-RESOURCE-RESPONSE-TOO-LARGE"; message="Package resource response exceeded the configured limit"; }
            else if(error instanceof PackageResourceInspector.ResourceLimitException) { code="ATT-RESOURCE-LIMIT"; message="Package resource inspection exceeded a configured limit"; }
            else if(error instanceof PackageResourceInspector.StaleResourceCursorException
                    &&("run".equals(request.command)||"debug".equals(request.command)||"load".equals(request.command))
                    &&request.expectedRevisionDigest!=null) { code="ATT-SERVER-DRAFT-STALE"; message="Package content changed after preview; rebuild the draft"; exit=2; status="INVALID"; }
            else if(error instanceof PackageResourceInspector.StaleResourceCursorException) { code="ATT-RESOURCE-CURSOR-STALE"; message="The package resources changed; refresh the Explorer"; }
            else if(error instanceof PackageConfigurationInspector.ResponseTooLargeException) { code="ATT-RESOURCE-RESPONSE-TOO-LARGE"; message="Package configuration response exceeded the configured limit"; }
            else if(error instanceof PackageConfigurationInspector.ConfigurationLimitException) { code="ATT-RESOURCE-LIMIT"; message="Package configuration inspection exceeded a configured limit"; }
            else if(error instanceof IllegalArgumentException) { code="WORKER_REQUEST_INVALID"; exit=2; status="INVALID"; }
            Map<String,Object> diagnostic=fields("code",code,"message",DiagnosticSanitizer.redactText(message==null?"ATT operation failed":message));
            boolean draftInspection="inspect".equals(request.command)&&("debug-input".equals(request.inspectionAction)||"debug-form".equals(request.inspectionAction)
                    ||"quick-load-input".equals(request.inspectionAction)||"quick-load-form".equals(request.inspectionAction)
                    ||"load-templates".equals(request.inspectionAction)||"load-template-compose".equals(request.inspectionAction)
                    ||"quick-load-policy".equals(request.inspectionAction)
                    ||"load-scenario".equals(request.inspectionAction)
                    ||"load-catalog".equals(request.inspectionAction)
                    ||"load-detail".equals(request.inspectionAction)
                    ||"load-source".equals(request.inspectionAction)
                    ||"run-input".equals(request.inspectionAction));
            if(draftInspection) {
                String safeMessage=DiagnosticSanitizer.redactText(message==null?"ATT operation failed":message);
                diagnostic.put("summary",safeMessage);
            }
            if(draftInspection&&error instanceof att.validation.DiagnosticException) {
                att.validation.DiagnosticException typed=(att.validation.DiagnosticException)error;
                diagnostic.put("summary",DiagnosticSanitizer.redactText(typed.summary()));
                if(typed.detail()!=null)diagnostic.put("detail",DiagnosticSanitizer.redactText(typed.detail()));
                if(typed.file()!=null)diagnostic.put("file",DiagnosticSanitizer.redactText(typed.file()));
                if(typed.field()!=null)diagnostic.put("field",DiagnosticSanitizer.redactText(typed.field()));
                if(typed.suggestion()!=null)diagnostic.put("suggestion",DiagnosticSanitizer.redactText(typed.suggestion()));
                if(typed.source()!=null) {
                    diagnostic.put("sourceFile",DiagnosticSanitizer.redactText(typed.source().file()));
                    diagnostic.put("line",typed.source().line());
                    diagnostic.put("sourceColumn",typed.source().column());
                }
                String resourceId=request.inspectionTargetId==null?request.inspectionResourceId:request.inspectionTargetId;
                if(resourceId!=null)diagnostic.put("resourceId",DiagnosticSanitizer.redactText(resourceId));
            }
            if(loadSampler!=null&&loadSummaryLine==null) loadSummaryLine=loadSampler.finish(status);
            emit(WorkerEvent.Type.DIAGNOSTIC,diagnostic);
            emitResult(fields("status",status,"exitCode",exit,"result",fields("executionId",null,"status",status,"exitCode",exit)));
            return exit;
        }
    }
    private OperationResult dispatch(WorkerRequest r) throws Exception {
        if(r.packageRoot==null||r.packageRoot.trim().isEmpty()) throw new IllegalArgumentException("packageRoot is required");
        Path root=Paths.get(r.packageRoot).toAbsolutePath().normalize();
        Path config=path(r.config); Path output=path(r.outputDirectory);
        executionOutput=output==null?null:(output.isAbsolute()?output:root.resolve(output)).normalize();
        AttService service=new DefaultAttService();
        if("run".equals(r.command)&&r.expectedRevisionDigest!=null) verifyRevision(root,config,r);
        if("run".equals(r.command)&&r.target!=null) {
            Map<String,Object> t=requiredTarget(r);
            return service.debug(new DebugRequest(root,config,r.environment,output,r.runId,text(t,"type"),text(t,"id"),path(r.debugInput),false,event -> emitRunExecution(event,r.diagnosticMode),r.debugId,r.overrides,false,null,r.inlineDebugInput));
        }
        if("run".equals(r.command)) return service.run(new RunRequest(root,config,r.environment,output,r.runId,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),set(r.tags),set(r.excludeTags),bool(r.all),bool(r.rerunFailed),bool(r.dryRun),bool(r.failFast),null,"reject",false,event -> emitRunExecution(event,r.diagnosticMode)));
        if("debug".equals(r.command)) { Map<String,Object> t=requiredTarget(r);
            if(r.expectedRevisionDigest!=null) verifyRevision(root,config,r);
            return service.debug(new DebugRequest(root,config,r.environment,output,r.runId,text(t,"type"),text(t,"id"),path(r.debugInput),bool(r.unsafeFailureDetails),event -> emitExecution(event),r.debugId,r.overrides,false,null,r.inlineDebugInput)); }
        if("load".equals(r.command)) { Map<String,Object> t=r.target==null?Collections.<String,Object>emptyMap():r.target; Map<String,String> l=r.load==null?Collections.<String,String>emptyMap():r.load;
            loadSampler=new att.load.LoadConsoleSampler(r.runId, line -> { try { emit(WorkerEvent.Type.PROGRESS, fields("console", line, "phase", "PROGRESS")); } catch(Exception error) { throw new IllegalStateException(error); } });
            att.load.LoadEventListener observer = event -> { try {
                String start=loadSampler.startLine(event.model(), event.runId());
                if(start!=null) emit(WorkerEvent.Type.PROGRESS, fields("console", start, "runId", event.runId(), "phase", "START"));
                Map<String,Object> payload=event.toMap(root);
                String line=loadSampler.onEvent(event);
                if(line!=null) payload.put("console", line);
                emit(WorkerEvent.Type.PROGRESS, payload);
            } catch(Exception error) { throw new IllegalStateException(error); } };
            att.api.OperationResult loadResult;
            if(r.inlineLoadScenario!=null) {
                if(r.expectedRevisionDigest!=null) verifyRevision(root,config,r);
                att.load.LoadScenario parsed=new att.load.LoadScenarioLoader(root).loadInline(r.inlineLoadScenario);
                loadResult=service.load(new LoadRequest(root,config,r.environment,output,r.runId,null,null,null,
                        null,null,null,null,null,null,null,null,null,r.overrides,observer,parsed,false));
            } else loadResult=service.load(new LoadRequest(root,config,r.environment,output,r.runId,path(r.scenario),optionalText(t,"type"),optionalText(t,"id"),l.get("users"),l.get("arrivalRate"),l.get("warmup"),l.get("rampUp"),l.get("duration"),l.get("rampDown"),l.get("thinkTime"),l.get("maxConcurrent"),l.get("overloadPolicy"),r.overrides,observer));
            loadSummaryLine=loadSampler.finish(loadResult.status());
            return loadResult; }
        if("validate".equals(r.command)) return service.validate(new ValidateRequest(root,config,r.environment,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),set(r.tags),set(r.excludeTags),bool(r.all),r.validationScope));
        if("snapshot".equals(r.command)) return service.snapshot(new SnapshotRequest(root,config,r.environment,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),bool(r.all)));
        if("inspect".equals(r.command)) {
            if("configuration".equals(r.inspectionType)) {
                int maxResponseBytes = r.maxResponseBytes == null ? 262144 : r.maxResponseBytes;
                Map<String,Object> inspected;
                if ("files".equals(r.inspectionAction)) {
                    PackageConfigurationFileInspector files = new PackageConfigurationFileInspector(RuntimeConfigPath.root(config),
                            new java.util.HashSet<>(r.safeTextSources == null ? Collections.emptyList() : r.safeTextSources),
                            r.maxSourceBytes == null ? 65536 : r.maxSourceBytes, maxResponseBytes,
                            !Boolean.FALSE.equals(r.sourceBrowsingEnabled),r.sourceBrowsingPolicy,r.sourceDenyGlobs);
                    inspected = files.list(r.inspectionPath, r.inspectionQuery,
                            r.inspectionOffset == null ? 0 : r.inspectionOffset,
                            r.inspectionLimit == null ? 50 : r.inspectionLimit);
                } else if ("source".equals(r.inspectionAction)) {
                    inspected = resourceInspector(RuntimeConfigPath.root(config), config, r).inspectPackageFileSource(r.inspectionPath, r.inspectionOrigin,r.inspectionReferenceId);
                } else {
                    PackageConfigurationInspector inspector = new PackageConfigurationInspector(root, config,
                            maxResponseBytes, Boolean.TRUE.equals(r.redactionEnabled), r.sensitivePaths, r.sensitiveFieldPatterns,
                            Boolean.TRUE.equals(r.inspectionIncludeAllFields));
                    if(Boolean.TRUE.equals(r.inspectionSharedConfiguration))inspector.forSharedRuntime();
                    if(inspectionDaemon)inspector.withSnapshots(configurationSnapshots,att.server.api.internal.InspectionCacheIdentity.context(root,r));
                    inspected = inspector.inspect(r.inspectionAction,
                            r.inspectionEnvironment, r.inspectionOtherEnvironment, r.inspectionSection,
                            r.inspectionOffset == null ? 0 : r.inspectionOffset,
                            r.inspectionLimit == null ? 0 : r.inspectionLimit,
                            Boolean.TRUE.equals(r.inspectionIncludeInherited),
                            "preview-generated".equals(r.inspectionQuery),
                            "effective".equals(r.inspectionAction) ? r.inspectionQuery : null);
                }
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            PackageResourceInspector inspector = resourceInspector(root, config, r);
            if("run-input".equals(r.inspectionAction)) {
                String revision=inspector.executionRevisionDigest();
                OperationResult validated=service.validate(new ValidateRequest(root,config,r.environment,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),set(r.tags),set(r.excludeTags),bool(r.all),"selected"));
                if(validated.exitCode()!=0) {
                    for(att.validation.Diagnostic diagnostic:validated.diagnostics())emit(WorkerEvent.Type.DIAGNOSTIC,diagnostic.toMap());
                    return validated;
                }
                if(r.caseIds!=null&&!r.caseIds.isEmpty()) {
                    OperationResult identities=validated;
                    if(r.tags!=null&&!r.tags.isEmpty()||r.excludeTags!=null&&!r.excludeTags.isEmpty())
                        identities=service.validate(new ValidateRequest(root,config,r.environment,paths(r.suites),path(r.suiteDirectory),set(r.caseIds),Collections.<String>emptySet(),Collections.<String>emptySet(),bool(r.all),"selected"));
                    Object resolved=identities.summary().get("selectedCaseIds");
                    if(!(resolved instanceof List)||!new java.util.HashSet<Object>((List<?>)resolved).containsAll(r.caseIds))
                        throw new IllegalArgumentException("RUN includes Case IDs that do not exist in the selected suites");
                }
                inspector.verifyRevision(revision);
                Map<String,Object> inspected=new LinkedHashMap<String,Object>(validated.summary());
                inspected.put("revisionDigest",revision);
                return new OperationResult(null,"PASS",0,0,validated.diagnostics(),Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("revision".equals(r.inspectionAction)) {
                inspector.verifyRevision(r.expectedRevisionDigest);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",Collections.<String,Object>singletonMap("revisionDigest",inspector.executionRevisionDigest())));
            }
            if("prewarm-eligibility".equals(r.inspectionAction)) {
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspector.prewarmEligibility()));
            }
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
            if("quick-load-form".equals(r.inspectionAction)) {
                Map<String,Object> inspected=inspector.inspectQuickLoadForm(r.inspectionType,r.inspectionResourceId,
                        r.loadModel,r.expectedRevisionDigest);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("quick-load-input".equals(r.inspectionAction)) {
                Map<String,Object> inspected=inspector.validateQuickLoadInput(r.inspectionType,r.inspectionTargetId,
                        r.loadModel,r.inlineLoadInput,r.loadOverrides,r.workloadExecution,r.quickLoadTestdata);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("quick-load-policy".equals(r.inspectionAction)) {
                Map<String,Object> inspected=inspector.inspectQuickLoadPolicy(r.loadModel,r.inspectionPath);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("load-templates".equals(r.inspectionAction) || "load-template-compose".equals(r.inspectionAction)) {
                Map<String,Object> inspected="load-templates".equals(r.inspectionAction)
                        ? inspector.inspectLoadTemplates(r.inspectionQuery, r.inspectionOffset==null?0:r.inspectionOffset,
                                r.inspectionLimit==null?50:r.inspectionLimit)
                        : inspector.composeLoadTemplate(r.inlineLoadScenario);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("load-scenario".equals(r.inspectionAction)) {
                Map<String,Object> inspected=r.inspectionPath==null
                        ?inspector.validateLoadScenario(r.inlineLoadScenario)
                        :inspector.validateLoadScenarioFile(r.inspectionPath);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("load-catalog".equals(r.inspectionAction)) {
                Map<String,Object> inspected=inspector.inspectLoadCatalog(r.inspectionQuery,
                        r.inspectionOffset==null?0:r.inspectionOffset,r.inspectionLimit==null?50:r.inspectionLimit,r.expectedRevisionDigest);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("load-detail".equals(r.inspectionAction)) {
                Map<String,Object> inspected=inspector.inspectLoadCatalogDetail(r.inspectionPath,r.expectedRevisionDigest);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            if("load-source".equals(r.inspectionAction)) {
                Map<String,Object> inspected=inspector.inspectLoadSource(r.inspectionPath);
                return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                        Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
            }
            Map<String,Object> inspected = inspector.inspect(r.inspectionAction, r.inspectionType,
                    r.inspectionResourceId, r.inspectionQuery,
                    r.inspectionOffset == null ? 0 : r.inspectionOffset,
                    r.inspectionLimit == null ? 50 : r.inspectionLimit,
                    r.expectedRevisionDigest,r.inspectionOrigin,
                    r.inspectionSort == null ? "name" : r.inspectionSort,
                    r.inspectionDirection == null ? "asc" : r.inspectionDirection);
            return new OperationResult(null,"PASS",0,0,Collections.<att.validation.Diagnostic>emptyList(),
                    Collections.<String,String>emptyMap(),Collections.<String,Object>singletonMap("inspection",inspected));
        }
        throw new IllegalArgumentException("Unsupported Worker command: "+r.command);
    }

    private void verifyRevision(Path root,Path config,WorkerRequest request) throws Exception {
        String policy=request.sourceBrowsingPolicy==null?"allowlist-only":request.sourceBrowsingPolicy;
        boolean sourceBrowsingEnabled=!Boolean.FALSE.equals(request.sourceBrowsingEnabled);
        boolean redactionEnabled=!Boolean.FALSE.equals(request.redactionEnabled);
        try(PackageResourceInspector inspector=new PackageResourceInspector(root,config,request.environment,
                request.safeTextSources,request.externalTextSources,request.externalSourceRoots,
                request.maxSourceBytes==null?65536:request.maxSourceBytes,
                request.maxResponseBytes==null?262144:request.maxResponseBytes,true,
                sourceBrowsingEnabled,policy,request.sourceDenyGlobs,redactionEnabled,
                request.sensitivePaths,request.sensitiveFieldPatterns)) {
            if(prewarmed)inspector.verifyPrewarmRevision(request.expectedRevisionDigest);
            else inspector.verifyRevision(request.expectedRevisionDigest);
        }
    }

    private synchronized PackageResourceInspector resourceInspector(Path root,Path config,WorkerRequest request) throws Exception {
        List<String> safeSources=new ArrayList<String>();
        if(request.safeTextSources!=null)safeSources.addAll(request.safeTextSources);
        Collections.sort(safeSources);
        List<String> externalSources=new ArrayList<String>();
        if(request.externalTextSources!=null)externalSources.addAll(request.externalTextSources);
        Collections.sort(externalSources);
        List<String> externalRoots=new ArrayList<String>();
        if(request.externalSourceRoots!=null)externalRoots.addAll(request.externalSourceRoots);
        Collections.sort(externalRoots);
        InspectorKey key=new InspectorKey(root.toAbsolutePath().normalize().toString(),
                config==null?"":config.toString(),request.environment,safeSources,externalSources,externalRoots,
                request.maxSourceBytes==null?65536:request.maxSourceBytes,
                request.maxResponseBytes==null?262144:request.maxResponseBytes,
                Boolean.TRUE.equals(request.sourceBrowsingEnabled),request.sourceBrowsingPolicy,
                request.sourceDenyGlobs,Boolean.TRUE.equals(request.redactionEnabled),request.sensitivePaths,request.sensitiveFieldPatterns);
        key.affinityKey=att.server.api.internal.InspectionCacheIdentity.resource(root,request);
        PackageResourceInspector inspector=resourceInspectors.get(key);
        if(inspector!=null){InspectionMetrics.JVM.add(INSPECTOR_HIT,1);return inspector;}
        InspectionMetrics.JVM.add(INSPECTOR_MISS,1);
        inspector=new PackageResourceInspector(root,config,request.environment,safeSources,externalSources,externalRoots,
                key.maxSourceBytes,key.maxResponseBytes,inspectionDaemon,key.sourceBrowsingEnabled,key.sourceBrowsingPolicy,
                key.sourceDenyGlobs,key.redactionEnabled,key.sensitivePaths,key.sensitiveFieldPatterns);
        resourceInspectors.put(key,inspector);
        while(resourceInspectors.size()>2) {
            Map.Entry<InspectorKey,PackageResourceInspector> eldest=resourceInspectors.entrySet().iterator().next();
            resourceInspectors.remove(eldest.getKey());
            InspectionMetrics.JVM.add(INSPECTOR_EVICTION,1);
            eldest.getValue().close();
        }
        return inspector;
    }

    private synchronized void closeResourceInspectors() {
        for(PackageResourceInspector inspector:resourceInspectors.values())inspector.close();
        resourceInspectors.clear();
        configurationSnapshots.clear();
    }

    private static final class InspectorKey {
        String affinityKey;
        final String root,config,environment;
        final List<String> safeTextSources,externalTextSources,externalSourceRoots;
        final int maxSourceBytes,maxResponseBytes;
        final boolean sourceBrowsingEnabled,redactionEnabled;
        final String sourceBrowsingPolicy;
        final List<String> sourceDenyGlobs,sensitivePaths,sensitiveFieldPatterns;
        InspectorKey(String root,String config,String environment,List<String> safeTextSources,List<String> externalTextSources,List<String> externalSourceRoots,
                     int maxSourceBytes,int maxResponseBytes,boolean sourceBrowsingEnabled,String sourceBrowsingPolicy,
                     List<String> sourceDenyGlobs,boolean redactionEnabled,List<String> sensitivePaths,List<String> sensitiveFieldPatterns) {
            this.root=root;this.config=config;this.environment=environment;
            this.safeTextSources=Collections.unmodifiableList(new ArrayList<String>(safeTextSources));
            this.externalTextSources=Collections.unmodifiableList(new ArrayList<String>(externalTextSources));
            this.externalSourceRoots=Collections.unmodifiableList(new ArrayList<String>(externalSourceRoots));
            this.maxSourceBytes=maxSourceBytes;this.maxResponseBytes=maxResponseBytes;
            this.sourceBrowsingEnabled=sourceBrowsingEnabled;this.sourceBrowsingPolicy=sourceBrowsingPolicy;this.sourceDenyGlobs=copy(sourceDenyGlobs);
            this.redactionEnabled=redactionEnabled;this.sensitivePaths=copy(sensitivePaths);this.sensitiveFieldPatterns=copy(sensitiveFieldPatterns);
        }
        private static List<String> copy(List<String> values){return Collections.unmodifiableList(new ArrayList<String>(values==null?Collections.<String>emptyList():values));}
        @Override public boolean equals(Object other) {
            if(this==other)return true;
            if(!(other instanceof InspectorKey))return false;
            InspectorKey value=(InspectorKey)other;
            return maxSourceBytes==value.maxSourceBytes&&maxResponseBytes==value.maxResponseBytes&&sourceBrowsingEnabled==value.sourceBrowsingEnabled&&redactionEnabled==value.redactionEnabled
                    &&java.util.Objects.equals(root,value.root)&&java.util.Objects.equals(config,value.config)
                    &&java.util.Objects.equals(environment,value.environment)&&java.util.Objects.equals(sourceBrowsingPolicy,value.sourceBrowsingPolicy)
                    &&safeTextSources.equals(value.safeTextSources)&&externalTextSources.equals(value.externalTextSources)&&externalSourceRoots.equals(value.externalSourceRoots)
                    &&sourceDenyGlobs.equals(value.sourceDenyGlobs)&&sensitivePaths.equals(value.sensitivePaths)&&sensitiveFieldPatterns.equals(value.sensitiveFieldPatterns);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(root,config,environment,safeTextSources,externalTextSources,externalSourceRoots,maxSourceBytes,maxResponseBytes,sourceBrowsingEnabled,sourceBrowsingPolicy,sourceDenyGlobs,redactionEnabled,sensitivePaths,sensitiveFieldPatterns);
        }
    }
    private Map<String,Object> requiredTarget(WorkerRequest r) { if(r.target==null)throw new IllegalArgumentException("target is required"); return r.target; }
    private void emitRunExecution(att.api.ExecutionEvent event,Boolean diagnosticMode) {
        if(Boolean.FALSE.equals(diagnosticMode)&&event.type()==att.api.ExecutionEvent.Type.DEBUG)return;
        emitExecution(event);
    }
    private void emitExecution(att.api.ExecutionEvent event) {
        Map<String,Object> data = new LinkedHashMap<String,Object>();
        if(event.runId()!=null)data.put("executionId",event.runId()); if(event.caseId()!=null)data.put("caseId",event.caseId());
        if(event.stage()!=null)data.put("stage",event.stage()); if(event.action()!=null)data.put("action",event.action());
        if(event.status()!=null)data.put("status",event.status()); if(event.durationMs()!=null)data.put("durationMs",event.durationMs());
        if(event.message()!=null)data.put("message",event.message()); data.putAll(event.data());
        data.put("executionEventType",event.type().name());
        WorkerEvent.Type type=event.type()==att.api.ExecutionEvent.Type.PROGRESS||event.type()==att.api.ExecutionEvent.Type.STATUS
                ||event.type()==att.api.ExecutionEvent.Type.DEBUG
                ? WorkerEvent.Type.PROGRESS : WorkerEvent.Type.LOG;
        try { emit(type,data); } catch(Exception error) { throw new IllegalStateException(error); }
    }
    private synchronized void emit(WorkerEvent.Type type,Map<String,Object> data) throws Exception {
        resourceTelemetry.sample();
        Map<String,Object> safe;
        if(inspectionDaemon&&type==WorkerEvent.Type.RESULT&&data.containsKey("inspectionMetrics")) {
            Map<String,Object> content=new LinkedHashMap<String,Object>(data);
            Object metrics=content.remove("inspectionMetrics");
            safe=DiagnosticSanitizer.sanitize(content);
            // These locally constructed metrics contain only fixed counters, numeric JMX
            // samples, validated environment labels and opaque hashes. Repeated secret
            // classification of every counter name adds work to every inspection read.
            // Authored results, diagnostics and Worker telemetry still pass the sanitizer.
            safe.put("inspectionMetrics",metrics);
        } else safe=DiagnosticSanitizer.sanitize(data);
        if(!inspectionDaemon&&!inspectionRequest) {
            attachConsole(type,safe);
            WorkerProtocol.Frame frame=WorkerProtocol.encode(mapper,new WorkerEvent(type,jobId,safe).toMap(),WorkerProtocol.MAX_FRAME_BYTES);
            if(frame.oversized()) {
                Map<String,Object> bounded=boundedEvent(type,safe,frame.sizeBytes);
                frame=WorkerProtocol.encode(mapper,new WorkerEvent(type,boundedText(jobId,128),bounded).toMap(),WorkerProtocol.MAX_FRAME_BYTES);
            }
            frame.writeTo(protocol);protocol.write('\n');protocol.flush();
            return;
        }
        String line=mapper.writeValueAsString(new WorkerEvent(type,jobId,safe).toMap());
        long bytes=line.getBytes(StandardCharsets.UTF_8).length+1L;
        if(inspectionDaemon&&type==WorkerEvent.Type.RESULT&&safe.containsKey("inspectionMetrics")
                &&inspectionResponseBytes+bytes>inspectionResponseLimit) {
            // Optional telemetry must never make an otherwise valid bounded response fail.
            safe.remove("inspectionMetrics");safe.remove("inspectionMetricsDelta");
            lastInspectionMetrics=null;
            line=mapper.writeValueAsString(new WorkerEvent(type,jobId,safe).toMap());
            bytes=line.getBytes(StandardCharsets.UTF_8).length+1L;
        }
        protocol.println(line);protocol.flush();
        if(inspectionDaemon)inspectionResponseBytes+=bytes;
    }
    private att.console.ExecutionConsoleFormatter.Options consoleOptions=att.console.ExecutionConsoleFormatter.Options.published();
    private void attachConsole(WorkerEvent.Type type,Map<String,Object> safe) {
        Object existing=safe.get("console");
        if(existing instanceof String) {
            safe.put("console",publishConsole((String)existing));
            return;
        }
        if(safe.get("executionEventType") instanceof String) {
            String line=att.console.ExecutionConsoleFormatter.formatPayload(safe,consoleOptions);
            if(line!=null&&!line.isEmpty()) safe.put("console",publishConsole(line));
            return;
        }
        if(type==WorkerEvent.Type.RESULT&&loadSummaryLine!=null) {
            safe.put("console",publishConsole(loadSummaryLine));
            loadSummaryLine=null;
        }
    }
    private String publishConsole(String line) {
        return att.console.ExecutionConsoleFormatter.present(DiagnosticSanitizer.redactAbsolutePaths(DiagnosticSanitizer.redactText(line)),consoleOptions);
    }
    private Map<String,Object> boundedEvent(WorkerEvent.Type type,Map<String,Object> safe,long size) {
        Map<String,Object> bounded=fields("truncated",true,"protocolLimit",fields("code","ATT-WORKER-EVENT-TRUNCATED",
                "eventType",type.name(),"sizeBytes",size,"limitBytes",WorkerProtocol.MAX_FRAME_BYTES));
        for(String key:new String[]{"status","code","message","executionId","caseId","stage","action","executionEventType"})
            if(safe.get(key) instanceof String)bounded.put(key,boundedText(safe.get(key),512));
        if(safe.get("console") instanceof String)bounded.put("console",boundedText(safe.get("console"),512));
        for(String key:new String[]{"exitCode","durationMs"})if(safe.get(key) instanceof Number)bounded.put(key,safe.get(key));
        if(type==WorkerEvent.Type.RESULT) {
            Object value=safe.get("result");
            Map<?,?> original=value instanceof Map?(Map<?,?>)value:Collections.emptyMap();
            Map<String,Object> result=fields("status",bounded.get("status"),"exitCode",bounded.get("exitCode"),"truncated",true);
            if(original.get("executionId") instanceof String)result.put("executionId",boundedText(original.get("executionId"),256));
            if(original.get("durationMs") instanceof Number)result.put("durationMs",original.get("durationMs"));
            List<Map<String,Object>> diagnostics=new ArrayList<Map<String,Object>>();
            Object source=original.get("diagnostics");
            if(source instanceof Iterable)for(Object item:(Iterable<?>)source) {
                if(diagnostics.size()==4)break;
                if(!(item instanceof Map))continue;
                Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
                for(String key:new String[]{"code","summary","message","detail","suggestion"})
                    if(((Map<?,?>)item).get(key) instanceof String)diagnostic.put(key,boundedText(((Map<?,?>)item).get(key),512));
                if(!diagnostic.isEmpty())diagnostics.add(diagnostic);
            }
            diagnostics.add(fields("code","ATT-WORKER-EVENT-TRUNCATED","summary","Large result details were omitted from the Worker protocol; inspect the published artifacts"));
            result.put("diagnostics",diagnostics);
            if(executionOutput!=null)try {
                java.nio.file.Files.createDirectories(executionOutput);
                try(java.io.OutputStream out=java.nio.file.Files.newOutputStream(executionOutput.resolve("worker-protocol-result.json"),
                        java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)) {
                    mapper.writeValue(out,safe);
                }
                result.put("detailsArtifact","worker-protocol-result.json");
            } catch(Exception unavailable) { result.put("detailsUnavailable",true); }
            bounded.put("result",result);
            bounded.put("workerMetrics",safe.get("workerMetrics"));
        }
        return bounded;
    }
    private static String boundedText(Object value,int limit) {
        if(value==null)return null;
        String text=String.valueOf(value);
        if(text.length()<=limit)return text;
        int end=Character.isHighSurrogate(text.charAt(limit-1))?limit-1:limit;
        return text.substring(0,end);
    }
    private void recordInspectionAggregate(WorkerRequest request,long started,long buildsBefore,long loadsBefore,long bytesBefore) {
        if(!inspectionDaemon)return;
        try {
            String context=att.server.api.internal.InspectionCacheIdentity.context(Paths.get(request.packageRoot),request);
            String environment=request.inspectionEnvironment==null?request.environment:request.inspectionEnvironment;
            String key=att.metrics.InspectionCacheKey.environment(context,environment,"aggregate");
            Map<String,Object> values=inspectionAggregates.get(key);
            if(values==null){values=new LinkedHashMap<String,Object>();inspectionAggregates.put(key,values);}
            increment(values,"requests",1);increment(values,"totalNanos",System.nanoTime()-started);
            increment(values,"indexBuilds",InspectionMetrics.JVM.count(INDEX_BUILD)-buildsBefore);
            increment(values,"configLoads",InspectionMetrics.JVM.count(CONFIG_LOAD)-loadsBefore);
            increment(values,"readBytes",InspectionMetrics.JVM.count(READ_BYTES)-bytesBefore);
            while(inspectionAggregates.size()>16)inspectionAggregates.remove(inspectionAggregates.keySet().iterator().next());
        } catch(Exception unavailable) { /* Instrumentation must not change request success or expose error details. */ }
    }
    private static void increment(Map<String,Object> values,String key,long delta) {
        Object old=values.get(key);values.put(key,(old instanceof Number?((Number)old).longValue():0L)+Math.max(0L,delta));
    }
    private void emitResult(Map<String,Object> payload) throws Exception {
            if(inspectionDaemon) {
                Map<String,Object> metrics=InspectionMetrics.JVM.snapshot();
                int directories=0;
                for(PackageResourceInspector inspector:resourceInspectors.values())directories+=inspector.watchedDirectoryCount();
                metrics.put("watchedDirectories",directories);
                metrics.put("retainedInspectors",resourceInspectors.size());
                List<Map<String,Object>> keys=new ArrayList<Map<String,Object>>();
                for(InspectorKey key:resourceInspectors.keySet()) {
                    Map<String,Object> safeKey=new LinkedHashMap<String,Object>();
                    byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(key.root.getBytes(StandardCharsets.UTF_8));
                    safeKey.put("packageKey",java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
                    safeKey.put("environment",key.environment==null?"default":key.environment.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")?key.environment:"other");
                    keys.add(safeKey);
                }
                metrics.put("retainedInspectorKeys",keys);
                List<String> affinity=new ArrayList<String>(configurationSnapshots.affinityKeys());
                for(InspectorKey key:resourceInspectors.keySet())affinity.add(key.affinityKey);
                metrics.put("retainedAffinityKeys",affinity);
                Map<String,Map<String,Object>> aggregates=new LinkedHashMap<String,Map<String,Object>>();
                for(Map.Entry<String,Map<String,Object>> entry:inspectionAggregates.entrySet())
                    aggregates.put(entry.getKey(),new LinkedHashMap<String,Object>(entry.getValue()));
                metrics.put("packageEnvironmentAggregates",aggregates);
                metrics.put("retainedConfigSnapshots",configurationSnapshots.size());
                metrics.put("retainedConfigWeightBytes",configurationSnapshots.retainedWeight());
                if(lastInspectionMetrics==null)payload.put("inspectionMetrics",metrics);
                else {
                    Map<String,Object> changed=new LinkedHashMap<String,Object>();
                    for(Map.Entry<String,Object> entry:metrics.entrySet())
                        if(!java.util.Objects.equals(entry.getValue(),lastInspectionMetrics.get(entry.getKey())))changed.put(entry.getKey(),entry.getValue());
                    payload.put("inspectionMetrics",changed);payload.put("inspectionMetricsDelta",Boolean.TRUE);
                }
                lastInspectionMetrics=metrics;
            }
        payload.put("workerMetrics",resourceTelemetry.snapshot());emit(WorkerEvent.Type.RESULT,payload);
    }
    private int fail(String code,String message) throws Exception { emit(WorkerEvent.Type.DIAGNOSTIC,fields("code",code,"message",message)); emitResult(fields("status","INVALID","exitCode",2,"result",fields("executionId",null,"status","INVALID","exitCode",2))); return 2; }
    private static Map<String,Object> fields(Object... values) { Map<String,Object> map=new LinkedHashMap<String,Object>(); for(int i=0;i+1<values.length;i+=2)map.put(String.valueOf(values[i]),values[i+1]); return map; }
    private static boolean bool(Boolean value) { return Boolean.TRUE.equals(value); }
    private static String text(Map<String,Object> value,String key) { String found=optionalText(value,key); if(found==null||found.trim().isEmpty())throw new IllegalArgumentException("target."+key+" is required"); return found; }
    private static String optionalText(Map<String,Object> value,String key) { Object found=value.get(key); return found==null?null:String.valueOf(found); }
    private static Path path(String value) { return value==null?null:Paths.get(value); }
    private static List<Path> paths(List<String> values) { List<Path> out=new ArrayList<Path>(); if(values!=null)for(String value:values)out.add(Paths.get(value)); return out; }
    private static java.util.Set<String> set(List<String> values) { return new java.util.LinkedHashSet<String>(values==null?Collections.<String>emptyList():values); }
}
