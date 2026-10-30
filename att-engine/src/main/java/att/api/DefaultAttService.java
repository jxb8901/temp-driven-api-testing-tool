package att.api;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.RuntimeConfigPath;
import att.core.ExecutionOptions;
import att.core.FrameworkEngine;
import att.core.RunSummary;
import att.debug.DebugEngine;
import att.load.*;
import att.snapshot.SnapshotCommand;
import att.validation.Diagnostic;
import att.validation.DiagnosticException;
import att.validation.PackageValidator;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Default implementation backed by ATT's established execution/runtime components. */
public final class DefaultAttService implements AttService {
    private final LoadReportPublisher loadReportPublisher;
    private final LoadResourcesFactory loadResourcesFactory;
    private final LoadResourcesCloser loadResourcesCloser;

    public DefaultAttService() {
        this((outputRoot, result) -> new LoadReportWriter().write(outputRoot, result), LoadRunResources::new);
    }

    DefaultAttService(LoadReportPublisher loadReportPublisher) {
        this(loadReportPublisher, LoadRunResources::new);
    }

    DefaultAttService(LoadReportPublisher loadReportPublisher, LoadResourcesFactory loadResourcesFactory) {
        this(loadReportPublisher,loadResourcesFactory,LoadRunResources::close);
    }

    DefaultAttService(LoadReportPublisher loadReportPublisher, LoadResourcesFactory loadResourcesFactory,
                      LoadResourcesCloser loadResourcesCloser) {
        this.loadReportPublisher = loadReportPublisher == null
                ? (outputRoot, result) -> new LoadReportWriter().write(outputRoot, result)
                : loadReportPublisher;
        this.loadResourcesFactory = loadResourcesFactory == null ? LoadRunResources::new : loadResourcesFactory;
        this.loadResourcesCloser = loadResourcesCloser == null ? LoadRunResources::close : loadResourcesCloser;
    }

    interface LoadReportPublisher {
        Path write(Path outputRoot, LoadRunResult result) throws Exception;
    }

    interface LoadResourcesFactory {
        LoadRunResources create(Path packageRoot, FrameworkConfig config);
    }

    interface LoadResourcesCloser {
        void close(LoadRunResources resources);
    }

    private static ExecutionOptions options(AttRequest request, String command, List<Path> suites, Path suiteDirectory,
            java.util.Set<String> cases, java.util.Set<String> tags, java.util.Set<String> excluded, boolean all,
            boolean rerun, boolean dry, boolean failFast, String scope, String targetType, String targetId,
            Path input, boolean unsafe, Path scenario, String users, String rate, String warmup, String rampUp,
            String duration, String rampDown, String thinkTime, String maxConcurrent, String overload, List<String> overrides) {
        return ExecutionOptions.forApi(command, RuntimeConfigPath.resolve(request.configPath(), request.packageRoot()), request.environment(), suites, suiteDirectory,
                cases, tags, excluded, request.runId(), all, rerun, dry, failFast, request.outputDirectory(), scope,
                targetType, targetId, input, unsafe, scenario, users, rate, warmup, rampUp, duration, rampDown,
                thinkTime, maxConcurrent, overload, overrides);
    }
    private static FrameworkConfig config(AttRequest request) throws Exception {
        Path config = RuntimeConfigPath.resolve(request.configPath(), request.packageRoot());
        return new FrameworkConfigLoader().load(config, RuntimeConfigPath.root(config), request.packageRoot(), request.environment());
    }
    private static Map<String,String> paths(String... pairs) { Map<String,String> out=new LinkedHashMap<String,String>(); for(int i=0;i+1<pairs.length;i+=2) if(pairs[i+1]!=null) out.put(pairs[i],pairs[i+1]); return out; }

    @Override public RunResult run(RunRequest request) throws Exception {
        long started=System.nanoTime();
        att.core.PerformanceProfile profile=new att.core.PerformanceProfile(request.profile());
        long phase=profile.begin(); FrameworkConfig cfg=config(request); profile.end("configLoadMs",phase);
        ExecutionOptions opts=options(request,"run",request.suites(),request.suiteDirectory(),request.caseIds(),request.tags(),request.excludeTags(),request.all(),request.rerunFailed(),request.dryRun(),request.failFast(),"selected",null,null,null,false,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList())
                .withRunPolicies(request.ciOutputs(),request.concurrencyMode(),request.profile())
                .withObserver(request.observer())
                .withIdentitySeed(att.core.ExecutionIdentitySeed.now());
        FrameworkEngine engine=new FrameworkEngine(request.packageRoot(),cfg);
        opts=opts.withRunId(engine.effectiveRunId(opts));
        if(request.updateSnapshot()) {
            List<Path> updated=new SnapshotCommand().updateForRun(request.packageRoot(),cfg,opts);
            for(Path snapshot:updated) if(request.observer()!=null) request.observer().onEvent(new ExecutionEvent(
                    ExecutionEvent.Type.STATUS,opts.runId(),null,null,null,"SNAPSHOT_UPDATED",null,
                    "Snapshot updated: "+request.packageRoot().relativize(snapshot.toAbsolutePath().normalize()).toString().replace('\\','/'),
                    Collections.<String,Object>emptyMap()));
        }
        engine.assertRunIdAvailable(opts);
        phase=profile.begin(); PackageValidator.ValidationSummary validation=new PackageValidator(request.packageRoot(),cfg,
                RuntimeConfigPath.resolve(request.configPath(), request.packageRoot())).validate(opts);
        profile.end("validationMs",phase);
        if(!validation.valid()) return new RunResult(opts.runId(),"INVALID",2,elapsed(started),validation.diagnostics,Collections.<String,String>emptyMap(),withConfiguration(validationMap(validation), cfg));
        if(request.observer()!=null) {
            Map<String,Object> progress = new LinkedHashMap<String,Object>();
            progress.put("productVersion", att.Version.PRODUCT);
            progress.put("cases", validation.cases);
            progress.put("suites", validation.suites);
            progress.put("templates", validation.templates);
            progress.put("tools", validation.tools);
            progress.put("event", "RUN_VALIDATION_SUMMARY");
            request.observer().onEvent(new ExecutionEvent(ExecutionEvent.Type.PROGRESS, opts.runId(), null, null, null,
                    "VALIDATION_PASS", null, "Validation passed", progress));
            List<Diagnostic> visible=new ArrayList<Diagnostic>(validation.diagnostics);
            if(!visible.isEmpty()) {
                for(int i=0;i<visible.size();i++) {
                    Map<String,Object> diagnostic = new LinkedHashMap<String,Object>();
                    diagnostic.put("diagnostic", visible.get(i).toMap());
                    emitExecutionEvent(request.observer(), opts.runId(), "VALIDATION_DIAGNOSTIC", null, diagnostic);
                }
            }
            emitExecutionEvent(request.observer(), opts.runId(), "RUN_SELECTION_SUMMARY", "Cases selected", progress);
            emitExecutionEvent(request.observer(), opts.runId(), "RUN_EXECUTION_START", "Executing cases", Collections.<String,Object>emptyMap());
        }
        RunSummary result=engine.run(opts,validation.diagnostics,profile);
        Map<String,Object> summary=new LinkedHashMap<String,Object>(); summary.put("total",result.total()); summary.put("passed",result.passed()); summary.put("failed",result.failed()); summary.put("error",result.error()); summary.put("skipped",result.skipped()); summary.put("invalid",result.invalid());
        List<Map<String,Object>> failures=new ArrayList<Map<String,Object>>();
        for(att.core.TestResult item:result.results()) if(item.status()!=att.core.ResultStatus.PASS&&item.status()!=att.core.ResultStatus.SKIPPED) {
            Map<String,Object> failure=new LinkedHashMap<String,Object>(); failure.put("status",item.status().name()); failure.put("caseId",item.caseId());
            failure.put("caseLog",item.caseLogPath()==null?null:OperationResult.display(item.caseLogPath(),request.packageRoot())); failures.add(failure);
        }
        summary = withConfiguration(summary, cfg);
        return new RunResult(result.runId(),result.status().name(),result.exitCode(),elapsed(started),validation.diagnostics,paths("report",OperationResult.display(result.reportPath(),request.packageRoot()),"configuration",OperationResult.display(result.reportPath().getParent().getParent().resolve("configuration.json"),request.packageRoot())),summary,failures);
    }
    @Override public DebugResult debug(DebugRequest request) throws Exception {
        long serviceStarted=System.nanoTime();
        DebugStartupMetrics startupMetrics=request.startupMetrics();
        long profileStarted=startupMetrics==null?serviceStarted:startupMetrics.javaMainEntryNanos();
        att.core.PerformanceProfile profile=request.profileEnabled()
                ?new att.core.PerformanceProfile(true,profileStarted,startupMetrics==null?"debug-service-entry":"java-main-entry")
                :null;
        if(profile!=null&&startupMetrics!=null)
            profile.durationNanos("cliArgumentParseMs",startupMetrics.argumentParseNanos());
        String requestedDebugId = request.debugId() == null || request.debugId().trim().isEmpty()
                ? request.runId() : request.debugId();
        ExecutionOptions opts=options(request,"debug",null,null,null,null,null,false,false,false,false,"selected",request.targetType(),request.targetId(),request.input(),request.unsafeFailureDetails(),null,null,null,null,null,null,null,null,null,null,request.overrides())
                .withRunId(requestedDebugId).withObserver(request.observer())
                .withInlineDebugInput(request.inlineInput());
        long configPhase=profile==null?0L:profile.begin();
        FrameworkConfig cfg=config(request);
        if(profile!=null)profile.end("configLoadMs",configPhase);
        DebugEngine.Result result=new DebugEngine(request.packageRoot(), RuntimeConfigPath.resolve(request.configPath(), request.packageRoot()), cfg,
                new att.exec.IbmMqClientFactory(), true, Collections.<String>emptyList(), Collections.<String>emptyList()).run(opts,profile);
        if(profile!=null) {
            if(startupMetrics!=null&&startupMetrics.hasFirstConsoleEvent())
                profile.durationNanos("processToFirstConsoleEventMs",
                        startupMetrics.firstConsoleEventNanos()-startupMetrics.javaMainEntryNanos());
            profile.write(result.outputDirectory());
        }
        List<Diagnostic> ds=result.diagnostic()==null?Collections.<Diagnostic>emptyList():Collections.singletonList(result.diagnostic().toDiagnostic());
        Map<String,String> paths=paths("outputDirectory",OperationResult.display(result.outputDirectory(),request.packageRoot()),"log",OperationResult.display(result.logPath(),request.packageRoot()),"result",OperationResult.display(result.resultPath(),request.packageRoot()));
        if(profile!=null)paths.put("performance",OperationResult.display(result.outputDirectory().resolve("performance.json"),request.packageRoot()));
        paths.put("configuration",OperationResult.display(att.config.ConfigurationEvidence.write(result.outputDirectory(),cfg),request.packageRoot()));
        return new DebugResult(result.executionId(),result.status().name(),result.exitCode(),result.durationMs(),ds,paths,withConfiguration(Collections.<String,Object>emptyMap(),cfg));
    }
    @Override public ValidateResult validate(ValidateRequest request) throws Exception {
        long started=System.nanoTime();
        ExecutionOptions opts=options(request,"validate",request.suites(),request.suiteDirectory(),request.caseIds(),request.tags(),request.excludeTags(),request.all(),false,true,false,request.scope(),null,null,null,false,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList());
        FrameworkConfig cfg=config(request);
        PackageValidator.ValidationSummary result=new PackageValidator(request.packageRoot(),cfg,
                RuntimeConfigPath.resolve(request.configPath(), request.packageRoot())).validate(opts);
        return new ValidateResult(null,result.valid()?"PASS":"INVALID",result.valid()?0:2,elapsed(started),result.diagnostics,Collections.<String,String>emptyMap(),withConfiguration(validationMap(result),cfg));
    }
    @Override public SnapshotResult snapshot(SnapshotRequest request) throws Exception {
        long started=System.nanoTime();
        ExecutionOptions opts=options(request,"snapshot",request.suites(),request.suiteDirectory(),request.caseIds(),Collections.<String>emptySet(),Collections.<String>emptySet(),request.all(),false,false,false,"selected",null,null,null,false,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList());
        FrameworkConfig cfg=config(request);
        List<Path> generated=new SnapshotCommand().generate(request.packageRoot(),cfg,opts);
        List<String> items=new ArrayList<String>(); for(Path p:generated) items.add(OperationResult.display(p,request.packageRoot()));
        Map<String,Object> summary=new LinkedHashMap<String,Object>(); summary.put("snapshots",items.size()); summary.put("paths",items);
        return new SnapshotResult(null,"PASS",0,elapsed(started),Collections.<Diagnostic>emptyList(),Collections.<String,String>emptyMap(),withConfiguration(summary,cfg));
    }
    @Override public LoadResult load(LoadRequest request) throws Exception {
        long started=System.nanoTime();
        att.core.PerformanceProfile profile=new att.core.PerformanceProfile(request.profileEnabled());
        long configPhase=profile.begin(); FrameworkConfig cfg=config(request); profile.end("configLoadMs",configPhase);
        ExecutionOptions opts=options(request,"load",null,null,null,null,null,false,false,false,false,"selected",request.debugTargetType(),request.debugTargetId(),null,false,request.scenario(),request.users(),request.arrivalRate(),request.warmup(),request.rampUp(),request.duration(),request.rampDown(),request.thinkTime(),request.maxConcurrent(),request.overloadPolicy(),request.overrides());
        long validationPhase=profile.begin();
        LoadScenarioLoader loader=new LoadScenarioLoader(request.packageRoot()); LoadScenario scenario=request.parsedScenario();
        if(scenario!=null) { /* CLI supplied the already parsed typed scenario. */ }
        else if(request.scenario()!=null) scenario=loader.load(request.scenario(),LoadOverrides.from(opts));
        else {
            Map<String,Object> promoted=new DebugEngine(request.packageRoot(), RuntimeConfigPath.resolve(request.configPath(), request.packageRoot()), cfg,
                    new att.exec.IbmMqClientFactory(), true, Collections.<String>emptyList(), Collections.<String>emptyList()).loadBootstrapInputForLoad(opts);
            scenario=loader.fromDebugInput((Path)promoted.get("source"),request.debugTargetType(),request.debugTargetId(),
                    map(promoted.get("inputs")),map(promoted.get("vars")),map(promoted.get("arguments")),loader.loadDefaultPolicy(),opts);
        }
        if(scenario.policyOnly()) throw new IllegalArgumentException("Load descriptor is policy-only and cannot be executed directly");
        LoadTarget target=null; if(!scenario.coordinatorRequired()){target=new LoadTargetResolver(request.packageRoot(),cfg).resolve(scenario);new LoadTargetValidator(request.packageRoot(),cfg).validate(scenario,target);}
        profile.endAccumulated("validationMs",validationPhase);
        att.core.ExecutionIdentitySeed identitySeed=att.core.ExecutionIdentitySeed.now();
        String fallback="load-"+System.currentTimeMillis();
        String id=request.runId()==null||request.runId().trim().isEmpty()
                ? att.core.ExecutionIdentityFormat.runId(cfg.run().runIdFormat(), fallback, request.packageRoot(),
                        "load", scenario.source(), identitySeed.startedAt())
                : att.core.IdentifierValidator.runId(request.runId());
        Path outputRoot=(request.outputDirectory()==null?request.packageRoot().resolve(cfg.outputDirectory()):request.packageRoot().resolve(request.outputDirectory())).toAbsolutePath().normalize();
        Map<String,Object> longRunPreflight=att.load.LoadLongRunPreflight.inspect(scenario,outputRoot);
        if(request.listener()!=null && longRunPreflight.get("warnings") instanceof List) {
            for(Object value:(List<?>)longRunPreflight.get("warnings")) if(value instanceof Map) {
                Map<String,Object> warning=new LinkedHashMap<String,Object>();
                for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())
                    if(entry.getKey() instanceof String) warning.put((String)entry.getKey(),entry.getValue());
                warning.put("event","LOAD_LONG_RUN_PREFLIGHT");
                warning.put("console","[LOAD] Preflight warning: "+warning.get("message"));
                request.listener().onPreparationEvent(warning);
            }
        }
        Path loadRoot=outputRoot.resolve("load"); Path runDir=att.core.IdentifierValidator.strictChild(loadRoot,id,"Load run directory");
        Files.createDirectories(loadRoot);
        try {
            Files.createDirectory(runDir);
        } catch (java.nio.file.FileAlreadyExistsException collision) {
            throw new IllegalArgumentException("Load run ID already exists: "+id+" ("+runDir+"). Choose a different --run-id.", collision);
        }
        att.config.ConfigurationEvidence.write(runDir,cfg);
        LoadEvidenceStore evidence=new LoadEvidenceStore(LoadEvidencePolicy.from(scenario),request.listener());
        LoadRunResult result=null;
        Map<String,Object> resourceMetrics=Collections.emptyMap();
        boolean reportEligible=false;
        Throwable executionFailure=null;
        Throwable cleanupFailure=null;
        long executionPhase=profile.begin();
        LoadRunResources resources=loadResourcesFactory.create(request.packageRoot(),cfg);
        try {
            if (request.listener() != null) resources.setPreparationObserver(request.listener()::onPreparationEvent);
            if(scenario.coordinatorRequired()) {
                result=LoadRunCoordinator.runFrom(request.packageRoot(),cfg,scenario,resources,id,evidence,outputRoot,identitySeed.startedAt());
                reportEligible=true;
            } else {
                IterationExecutor iterations=new IterationExecutor(request.packageRoot(),cfg,target,resources,outputRoot,null,identitySeed.startedAt());
                LoadScheduler scheduler=scenario.model()==LoadScenario.Model.CLOSED?new ClosedVuScheduler(scenario,iterations,id,evidence,outputRoot):new FixedArrivalRateScheduler(scenario,iterations,id,evidence,outputRoot);
                try {
                    try { result=scheduler.run(); reportEligible=true; }
                    catch (att.load.LoadResourcePreparationException failure) {
                        long failedAt=System.currentTimeMillis();
                        LoadMetrics failedMetrics=LoadMetrics.forScenario(scenario,failedAt,LoadPhase.totalMs(scenario));
                        failedMetrics.finish(failedAt);
                        result=new LoadRunResult(id,scenario,Instant.ofEpochMilli(failedAt),Instant.ofEpochMilli(failedAt),
                                failedMetrics.snapshot(),LoadThresholdSummary.empty(),Collections.<String,Object>emptyMap(),resources.metrics());
                    }
                } catch (Throwable failure) {
                    executionFailure=failure;
                } finally {
                    try { scheduler.close(); }
                    catch (Throwable closeFailure) {
                        LoadResourceTeardownException typed=new LoadResourceTeardownException("SCHEDULER",null,1L,closeFailure);
                        cleanupFailure=merge(cleanupFailure,typed);
                    }
                }
            }
        } catch (Throwable failure) {
            executionFailure=merge(executionFailure,failure);
        } finally {
            try { resourceMetrics=resources.metrics(); }
            catch (Throwable metricsFailure) { cleanupFailure=merge(cleanupFailure,metricsFailure); }
            try { loadResourcesCloser.close(resources); }
            catch (Throwable closeFailure) { cleanupFailure=merge(cleanupFailure,closeFailure); }
        }
        if (executionFailure!=null) {
            if(cleanupFailure!=null&&cleanupFailure!=executionFailure) executionFailure.addSuppressed(cleanupFailure);
            rethrow(executionFailure);
        }
        if(result==null) throw new IllegalStateException("LOAD completed without a scheduler result.");
        if(cleanupFailure!=null) result=result.withTeardownFailure(cleanupFailure);
        profile.end("loadExecutionMs",executionPhase);
        Map<String,Object> retained=evidence.write(runDir);
        Map<String,Object> preflightResources=new LinkedHashMap<String,Object>(resourceMetrics);
        preflightResources.put("preflight",longRunPreflight);
        result=result.withResources(preflightResources).withThresholds(new LoadThresholdEvaluator().evaluate(scenario,result.metrics())).withEvidence(retained);
        long reportPhase=profile.begin();
        Path report=null;
        String reportAvailability=reportEligible?"PENDING":"OMITTED";
        if(reportEligible) {
            try {
                report=loadReportPublisher.write(outputRoot,result);
                if(report==null) reportAvailability="FAILED";
            } catch(Exception reportFailure) {
                reportAvailability="FAILED";
                profile.counter("loadReportWriteFailures",1L);
            }
        }
        profile.end("loadReportMs",reportPhase);
        profile.counter("loadScheduled",result.metrics().longValue("scheduled"));
        profile.counter("loadStarted",result.metrics().longValue("started"));
        profile.counter("loadCompleted",result.metrics().longValue("completed"));
        profile.counter("loadDropped",result.metrics().longValue("dropped"));
        @SuppressWarnings("unchecked") Map<String,Object> renderStats=(Map<String,Object>)resourceMetrics.get("render");
        if(renderStats!=null) for(Map.Entry<String,Object> entry:renderStats.entrySet())
            if(entry.getValue() instanceof Number) profile.counter(entry.getKey(),((Number)entry.getValue()).longValue());
        profile.write(runDir);
        Map<String,String> resultPaths=paths("outputDirectory",OperationResult.display(runDir,request.packageRoot()),
                "configuration",OperationResult.display(runDir.resolve("configuration.json"),request.packageRoot()));
        if(report!=null) {
            resultPaths.put("report",OperationResult.display(report,request.packageRoot()));
            resultPaths.put("reportManifest",OperationResult.display(runDir.resolve("load-report-manifest.json"),request.packageRoot()));
        }
        return new LoadResult(id,result.cancelled()&&!result.teardownFailed()?"CANCELLED":result.status().name(),result.exitCode(),elapsed(started),Collections.<Diagnostic>emptyList(),
                resultPaths,result,att.config.ConfigurationEvidence.metadata(cfg),reportAvailability);
    }
    private static Throwable merge(Throwable primary,Throwable secondary) {
        if(primary==null)return secondary;
        if(secondary!=null&&secondary!=primary)primary.addSuppressed(secondary);
        return primary;
    }
    private static void rethrow(Throwable failure) throws Exception {
        if(failure instanceof Exception)throw (Exception)failure;
        if(failure instanceof Error)throw (Error)failure;
        throw new IllegalStateException("LOAD execution failed",failure);
    }
    private static Map<String,Object> withConfiguration(Map<String,Object> summary, FrameworkConfig config) {
        Map<String,Object> value = new LinkedHashMap<String,Object>(summary);
        value.put("configuration",att.config.ConfigurationEvidence.metadata(config));
        return value;
    }
    private static long elapsed(long started) { return Math.max(0L,(System.nanoTime()-started)/1000000L); }
    private static void emitExecutionEvent(ExecutionEventListener observer, String runId, String event, String message, Map<String,Object> data) {
        if (observer == null) return;
        Map<String,Object> attributes = new LinkedHashMap<String,Object>(data);
        attributes.put("event", event);
        observer.onEvent(new ExecutionEvent(ExecutionEvent.Type.LOG, runId, null, null, null,
                null, null, message, attributes));
    }
    private static Map<String,Object> validationMap(PackageValidator.ValidationSummary value) { Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("mode",value.mode);m.put("suites",value.suites);m.put("cases",value.cases);m.put("templates",value.templates);m.put("tools",value.tools);m.put("errors",value.errors());m.put("warnings",value.warnings());m.put("selectedCaseIds",value.selectedCaseIds);return m; }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return value instanceof Map?(Map<String,Object>)value:Collections.<String,Object>emptyMap(); }
}
