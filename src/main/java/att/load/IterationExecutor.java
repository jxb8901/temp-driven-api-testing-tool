package att.load;

import att.Version;
import att.config.FrameworkConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.IdentifierValidator;
import att.core.ResultAggregator;
import att.core.ResultStatus;
import att.core.StageCaseData;
import att.core.TestCase;
import att.core.ValidationResult;
import att.exec.DbHelperExecutor;
import att.exec.MqHelperExecutor;
import att.exec.ToolInvoker;
import att.flow.FlowRegistry;
import att.template.StageTemplateRunner;
import att.template.UnifiedTemplateEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Workload-agnostic execution layer. Schedulers provide timing and identity;
 * this class creates all mutable ATT runtime state for exactly one iteration.
 */
public final class IterationExecutor {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final LoadTarget target;
    private final LoadRunResources resources;
    private final boolean ownsResources;

    public IterationExecutor(Path projectRoot, FrameworkConfig config, LoadTarget target) {
        this(projectRoot, config, target, new LoadRunResources(projectRoot, config), true);
    }

    public IterationExecutor(Path projectRoot, FrameworkConfig config, LoadTarget target, LoadRunResources resources) {
        this(projectRoot, config, target, resources, false);
    }

    private IterationExecutor(Path projectRoot, FrameworkConfig config, LoadTarget target,
                              LoadRunResources resources, boolean ownsResources) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize(); this.config = config; this.target = target;
        this.resources = resources == null ? new LoadRunResources(projectRoot, config) : resources;
        this.ownsResources = resources == null || ownsResources;
    }

    public IterationResult execute(IterationRequest request) {
        resources.ensureOpen();
        Instant started = Instant.now();
        Path iterationDirectory = null;
        boolean retainedWorkspace = request.outputDirectory() != null;
        CaseRuntimeContext context = null;
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        CaseExecutionLog log = null;
        boolean finalized = false;
        ResultStatus status = ResultStatus.ERROR;
        att.validation.Diagnostic diagnostic = null;
        try {
            iterationDirectory = iterationDirectory(request, retainedWorkspace);
            Path logPath = iterationDirectory.resolve("case.log");
            TestCase testCase = testCase(request);
            StageCaseData stage = new StageCaseData("LOAD", target.template().name(), Collections.<String, Object>emptyMap());
            context = new CaseRuntimeContext(testCase, iterationDirectory, request.iterationId(), iterationDirectory, logPath,
                    "load", request.startedAt().toString());
            context.setProject(projectRoot);
            context.setSourceMetadata("load", target.scenarioSource(), request.iterationId(), target.scenarioName());
            context.setTargetMetadata(target.type(), target.id());
            context.put("CASE.environment", config.environment());
            context.setLoad(request.runId(), request.model(), request.iterationId(), request.iteration(), request.phase(),
                    request.startedAt().toString(), request.userId(), request.runStartedAt().toString());
            log = retainedWorkspace ? new CaseExecutionLog(logPath, config.caseLogYamlAnchors())
                    : CaseExecutionLog.lightweight(logPath, config.caseLogYamlAnchors());
            context.beginStage(stage, target.template().name(), target.template().directory());
            DbHelperExecutor db = resources.db();
            db.beginCase();
            ToolInvoker tools = new ToolInvoker(projectRoot, config);
            MqHelperExecutor mq = resources.mq();
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(tools, db, mq);
            FlowRegistry flows = new FlowRegistry(projectRoot, target.templatesRoot(), false);
            results.addAll(new StageTemplateRunner(engine, flows).execute("LOAD", target.template(), context, log));
            results.addAll(db.finishCase(context, log));
            finalized = true;
            status = aggregate(results);
            context.put("CASE.status", status.name());
            context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            context.finishStage(status.name(), Duration.between(started, Instant.now()).toMillis());
            diagnostic = firstDiagnostic(results);
        } catch (Exception error) {
            att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(error);
            diagnostic = typed == null ? null : typed.toDiagnostic();
            status = ResultStatus.ERROR;
            if (context != null) {
                context.put("CASE.status", status.name());
                context.put("CASE.error", typed == null ? message(error) : typed.format());
                if (typed != null) context.put("CASE.errorDiagnostic", typed.toDiagnostic().toMap());
            }
            if (log != null) try { log.append("LOAD ERROR", typed == null ? message(error) : typed.toDiagnostic().toMap()); } catch (Exception ignored) { }
            if (!finalized) resources.db().abortCase();
        } finally {
            if (context != null) context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            if (log != null) try { log.close(); } catch (Exception ignored) { }
        }
        Duration duration = Duration.between(started, Instant.now());
        if (!retainedWorkspace && status != ResultStatus.PASS && log != null) {
            try { log.materialize(iterationDirectory.resolve("case.log")); iterationDirectory = iterationDirectory.resolve("case.log").getParent(); }
            catch (Exception ignored) { }
        }
        return new IterationResult(request.iterationId(), status, duration, context, results, iterationDirectory, diagnostic);
    }

    private TestCase testCase(IterationRequest request) {
        String rowId = request.iterationId().replaceAll("[^A-Za-z0-9_.-]", "_");
        Map<String, Object> data = new java.util.LinkedHashMap<String, Object>(request.inputs());
        if (!request.inputs().isEmpty()) {
            data.put("inputs", new java.util.LinkedHashMap<String, Object>(request.inputs()));
            for (Map.Entry<String, Object> entry : request.inputs().entrySet())
                if (!data.containsKey(entry.getKey())) data.put(entry.getKey(), entry.getValue());
        }
        if (!data.containsKey("caseName")) data.put("caseName", "LOAD " + target.type() + " " + target.id());
        return new TestCase(1, "LOAD", target.type(), rowId, Collections.<String>emptyList(), data,
                Collections.singletonMap("LOAD", new StageCaseData("LOAD", target.template().name(), Collections.<String, Object>emptyMap())), "");
    }

    private Path iterationDirectory(IterationRequest request, boolean retainedWorkspace) throws IOException {
        if (!retainedWorkspace) {
            return projectRoot.resolve(config.outputDirectory()).resolve("load").resolve(safe(request.runId()))
                    .resolve("iterations").resolve(LoadIsolation.workspaceName(request.runId(), request.iterationId(), request.iteration()));
        }
        Path root = request.outputDirectory().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path candidate = root.resolve(LoadIsolation.workspaceName(request.runId(), request.iterationId(), request.iteration())).normalize();
        if (!candidate.startsWith(root)) throw new IllegalArgumentException("Load iteration directory escapes output root");
        Files.createDirectories(candidate);
        return candidate;
    }

    private ResultStatus aggregate(List<ValidationResult> values) {
        if (values.isEmpty()) return ResultStatus.PASS;
        List<ResultStatus> statuses = new ArrayList<ResultStatus>();
        for (ValidationResult value : values) statuses.add(value.status());
        return ResultAggregator.aggregate(statuses);
    }
    private att.validation.Diagnostic firstDiagnostic(List<ValidationResult> values) {
        for (ValidationResult value : values) if (value.diagnostic() != null) return value.diagnostic();
        return null;
    }
    private String safe(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    public void close() { if (ownsResources) resources.close(); }
}
