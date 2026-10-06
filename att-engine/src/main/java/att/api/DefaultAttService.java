package att.api;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
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
    private static PrintStream quietStream() { return new PrintStream(new OutputStream() { @Override public void write(int value) { } }); }
    private static ExecutionOptions options(AttRequest request, String command, List<Path> suites, Path suiteDirectory,
            java.util.Set<String> cases, java.util.Set<String> tags, java.util.Set<String> excluded, boolean all,
            boolean rerun, boolean dry, boolean failFast, String scope, String targetType, String targetId,
            Path input, boolean unsafe, Path scenario, String users, String rate, String warmup, String rampUp,
            String duration, String rampDown, String thinkTime, String maxConcurrent, String overload, List<String> overrides) {
        return ExecutionOptions.forApi(command, request.configPath(), request.environment(), suites, suiteDirectory,
                cases, tags, excluded, request.runId(), all, rerun, dry, failFast, request.outputDirectory(), scope,
                targetType, targetId, input, unsafe, scenario, users, rate, warmup, rampUp, duration, rampDown,
                thinkTime, maxConcurrent, overload, overrides);
    }
    private static FrameworkConfig config(AttRequest request) throws Exception {
        Path config = request.configPath().isAbsolute() ? request.configPath() : request.packageRoot().resolve(request.configPath());
        return new FrameworkConfigLoader().load(config, request.packageRoot(), request.environment());
    }
    private static Map<String,String> paths(String... pairs) { Map<String,String> out=new LinkedHashMap<String,String>(); for(int i=0;i+1<pairs.length;i+=2) if(pairs[i+1]!=null) out.put(pairs[i],pairs[i+1]); return out; }
    private static List<Map<String,Object>> diagnostics(List<Diagnostic> values) { List<Map<String,Object>> out=new ArrayList<Map<String,Object>>(); if(values!=null) for(Diagnostic d:values) out.add(d.toMap()); return out; }

    @Override public RunResult run(RunRequest request) throws Exception {
        long started=System.nanoTime(); FrameworkConfig cfg=config(request);
        ExecutionOptions opts=options(request,"run",request.suites(),request.suiteDirectory(),request.caseIds(),request.tags(),request.excludeTags(),request.all(),request.rerunFailed(),request.dryRun(),request.failFast(),"selected",null,null,null,false,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList());
        PackageValidator.ValidationSummary validation=new PackageValidator(request.packageRoot(),cfg).validate(opts);
        if(!validation.valid()) return new RunResult(request.runId(),"INVALID",2,elapsed(started),diagnostics(validation.diagnostics),Collections.<String,String>emptyMap(),validationMap(validation));
        FrameworkEngine engine=new FrameworkEngine(request.packageRoot(),cfg); engine.assertRunIdAvailable(opts);
        RunSummary result=engine.run(opts,validation.diagnostics);
        Map<String,Object> summary=new LinkedHashMap<String,Object>(); summary.put("total",result.total()); summary.put("passed",result.passed()); summary.put("failed",result.failed()); summary.put("error",result.error()); summary.put("skipped",result.skipped()); summary.put("invalid",result.invalid());
        return new RunResult(request.runId(),result.status().name(),result.exitCode(),elapsed(started),Collections.<Map<String,Object>>emptyList(),paths("report",OperationResult.display(result.reportPath(),request.packageRoot())),summary);
    }
    @Override public DebugResult debug(DebugRequest request) throws Exception {
        long started=System.nanoTime();
        ExecutionOptions opts=options(request,"debug",null,null,null,null,null,false,false,false,false,"selected",request.targetType(),request.targetId(),request.input(),request.unsafeFailureDetails(),null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList());
        DebugEngine.Result result=new DebugEngine(request.packageRoot(),config(request)).run(opts);
        List<Map<String,Object>> ds=result.diagnostic()==null?Collections.<Map<String,Object>>emptyList():Collections.singletonList(result.diagnostic().toDiagnostic().toMap());
        Map<String,String> paths=paths("outputDirectory",OperationResult.display(result.outputDirectory(),request.packageRoot()),"log",OperationResult.display(result.logPath(),request.packageRoot()),"result",OperationResult.display(result.resultPath(),request.packageRoot()));
        return new DebugResult(request.runId(),result.status().name(),result.exitCode(),result.durationMs(),ds,paths,Collections.<String,Object>emptyMap());
    }
    @Override public ValidateResult validate(ValidateRequest request) throws Exception {
        long started=System.nanoTime();
        ExecutionOptions opts=options(request,"validate",request.suites(),request.suiteDirectory(),request.caseIds(),request.tags(),request.excludeTags(),request.all(),false,true,false,request.scope(),null,null,null,false,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList());
        PackageValidator.ValidationSummary result=new PackageValidator(request.packageRoot(),config(request)).validate(opts);
        return new ValidateResult(null,result.valid()?"PASS":"INVALID",result.valid()?0:2,elapsed(started),diagnostics(result.diagnostics),Collections.<String,String>emptyMap(),validationMap(result));
    }
    @Override public SnapshotResult snapshot(SnapshotRequest request) throws Exception {
        long started=System.nanoTime();
        ExecutionOptions opts=options(request,"snapshot",request.suites(),request.suiteDirectory(),request.caseIds(),Collections.<String>emptySet(),Collections.<String>emptySet(),request.all(),false,false,false,"selected",null,null,null,false,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList());
        List<Path> generated=new SnapshotCommand().generate(request.packageRoot(),config(request),opts);
        List<String> items=new ArrayList<String>(); for(Path p:generated) items.add(OperationResult.display(p,request.packageRoot()));
        Map<String,Object> summary=new LinkedHashMap<String,Object>(); summary.put("snapshots",items.size()); summary.put("paths",items);
        return new SnapshotResult(null,"PASS",0,elapsed(started),Collections.<Map<String,Object>>emptyList(),Collections.<String,String>emptyMap(),summary);
    }
    @Override public LoadResult load(LoadRequest request) throws Exception {
        long started=System.nanoTime(); FrameworkConfig cfg=config(request);
        ExecutionOptions opts=options(request,"load",null,null,null,null,null,false,false,false,false,"selected",request.debugTargetType(),request.debugTargetId(),null,false,request.scenario(),request.users(),request.arrivalRate(),request.warmup(),request.rampUp(),request.duration(),request.rampDown(),request.thinkTime(),request.maxConcurrent(),request.overloadPolicy(),request.overrides());
        LoadScenarioLoader loader=new LoadScenarioLoader(request.packageRoot()); LoadScenario scenario;
        if(request.scenario()!=null) scenario=loader.load(request.scenario(),LoadOverrides.from(opts));
        else {
            Map<String,Object> promoted=new DebugEngine(request.packageRoot(),cfg).loadBootstrapInputForLoad(opts);
            scenario=loader.fromDebugInput((Path)promoted.get("source"),request.debugTargetType(),request.debugTargetId(),
                    map(promoted.get("inputs")),map(promoted.get("vars")),map(promoted.get("arguments")),loader.loadDefaultPolicy(),opts);
        }
        if(scenario.policyOnly()) throw new IllegalArgumentException("Load descriptor is policy-only and cannot be executed directly");
        LoadTarget target=null; if(!scenario.coordinatorRequired()){target=new LoadTargetResolver(request.packageRoot(),cfg).resolve(scenario);new LoadTargetValidator(request.packageRoot(),cfg).validate(scenario,target);}
        String id=att.core.IdentifierValidator.runId(request.runId()==null||request.runId().trim().isEmpty()?"load-"+System.currentTimeMillis():request.runId());
        Path outputRoot=(request.outputDirectory()==null?request.packageRoot().resolve(cfg.outputDirectory()):request.packageRoot().resolve(request.outputDirectory())).toAbsolutePath().normalize();
        Path loadRoot=outputRoot.resolve("load"); Files.createDirectories(loadRoot); Path runDir=att.core.IdentifierValidator.strictChild(loadRoot,id,"Load run directory");
        if(Files.exists(runDir)) throw new IllegalArgumentException("Load run ID already exists: "+id);
        LoadEvidenceStore evidence=new LoadEvidenceStore(LoadEvidencePolicy.from(scenario));
        LoadRunResult result; Map<String,Object> resourceMetrics;
        try(LoadRunResources resources=new LoadRunResources(request.packageRoot(),cfg)){
            if(scenario.coordinatorRequired()) result=LoadRunCoordinator.runFrom(request.packageRoot(),cfg,scenario,resources,id,evidence,outputRoot);
            else { IterationExecutor iterations=new IterationExecutor(request.packageRoot(),cfg,target,resources,outputRoot); LoadScheduler scheduler=scenario.model()==LoadScenario.Model.CLOSED?new ClosedVuScheduler(scenario,iterations,id,evidence,outputRoot):new FixedArrivalRateScheduler(scenario,iterations,id,evidence,outputRoot); try{result=scheduler.run();}finally{scheduler.close();} }
            resourceMetrics=resources.metrics();
        }
        Files.createDirectories(runDir); Map<String,Object> retained=evidence.write(runDir);
        result=result.withResources(resourceMetrics).withThresholds(new LoadThresholdEvaluator().evaluate(scenario,result.metrics())).withEvidence(retained);
        Path report=new LoadReportWriter().write(outputRoot,result);
        return new LoadResult(id,result.status().name(),result.exitCode(),elapsed(started),Collections.<Map<String,Object>>emptyList(),paths("report",OperationResult.display(report,request.packageRoot()),"outputDirectory",OperationResult.display(runDir,request.packageRoot())),result.toMap());
    }
    private static long elapsed(long started) { return Math.max(0L,(System.nanoTime()-started)/1000000L); }
    private static Map<String,Object> validationMap(PackageValidator.ValidationSummary value) { Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("mode",value.mode);m.put("suites",value.suites);m.put("cases",value.cases);m.put("templates",value.templates);m.put("tools",value.tools);m.put("errors",value.errors());m.put("warnings",value.warnings());return m; }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return value instanceof Map?(Map<String,Object>)value:Collections.<String,Object>emptyMap(); }
}
