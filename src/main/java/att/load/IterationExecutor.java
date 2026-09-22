package att.load;

import att.Version;
import att.config.FrameworkConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.IdentifierValidator;
import att.core.ResultAggregator;
import att.core.ResultStatus;
import att.core.ValidationResult;
import att.exec.DbHelperExecutor;
import att.exec.MqHelperExecutor;
import att.exec.ToolInvoker;
import att.flow.FlowRegistry;
import att.template.StageTemplateRunner;
import att.template.UnifiedTemplateEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Workload-agnostic execution layer. Schedulers provide timing and identity;
 * this class creates all mutable ATT runtime state for exactly one iteration.
 */
public final class IterationExecutor implements LoadIterationRunner {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final LoadTarget target;
    private final LoadRunResources resources;
    private final Path outputRoot;
    private final FlowRegistry flows;
    private final boolean ownsResources;

    public IterationExecutor(Path projectRoot, FrameworkConfig config, LoadTarget target) {
        this(projectRoot, config, target, new LoadRunResources(projectRoot, config), true,
                projectRoot.resolve(config.outputDirectory()));
    }

    public IterationExecutor(Path projectRoot, FrameworkConfig config, LoadTarget target, LoadRunResources resources) {
        this(projectRoot, config, target, resources, false, projectRoot.resolve(config.outputDirectory()));
    }

    public IterationExecutor(Path projectRoot, FrameworkConfig config, LoadTarget target,
                             LoadRunResources resources, Path outputRoot) {
        this(projectRoot, config, target, resources, false, outputRoot);
    }

    private IterationExecutor(Path projectRoot, FrameworkConfig config, LoadTarget target,
                              LoadRunResources resources, boolean ownsResources, Path outputRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize(); this.config = config; this.target = target;
        this.resources = resources == null ? new LoadRunResources(projectRoot, config) : resources;
        this.outputRoot = (outputRoot == null ? this.projectRoot.resolve(config.outputDirectory()) : outputRoot)
                .toAbsolutePath().normalize();
        this.flows = target.flows().freezeFor(target.template());
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
            LoadExecutionContextAdapter.Prepared prepared = new LoadExecutionContextAdapter(projectRoot, config, target)
                    .prepare(request, iterationDirectory, logPath);
            context = prepared.context();
            log = retainedWorkspace ? new CaseExecutionLog(logPath, config.caseLogYamlAnchors())
                    : CaseExecutionLog.lightweight(logPath, config.caseLogYamlAnchors());
            context.beginStage(prepared.stage(), target.template().name(), target.template().directory());
            DbHelperExecutor db = resources.db();
            db.beginCase();
            ToolInvoker tools = new ToolInvoker(projectRoot, config);
            MqHelperExecutor mq = resources.mq();
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(tools, db, mq);
            results.addAll(new StageTemplateRunner(engine, flows).execute("LOAD", target.template(), context, log));
            if (Thread.currentThread().isInterrupted()) resources.db().abortCase();
            else results.addAll(db.finishCase(context, log));
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
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (context != null) context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            if (log != null) try { log.close(); } catch (Exception ignored) { }
        }
        Duration duration = Duration.between(started, Instant.now());
        if (!retainedWorkspace && status != ResultStatus.PASS && log != null) {
            try { log.materialize(iterationDirectory.resolve("case.log")); iterationDirectory = iterationDirectory.resolve("case.log").getParent(); }
            catch (Exception ignored) { }
        }
        if (context != null && iterationDirectory != null && (status != ResultStatus.PASS || retainedWorkspace)) {
            try {
                Files.createDirectories(iterationDirectory);
                Files.write(iterationDirectory.resolve("case.yaml"),
                        new org.yaml.snakeyaml.Yaml().dump(context.caseTree()).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
        return new IterationResult(request.iterationId(), status, duration, context, results, iterationDirectory, diagnostic);
    }

    private Path iterationDirectory(IterationRequest request, boolean retainedWorkspace) throws IOException {
        if (!retainedWorkspace) {
            return outputRoot.resolve("load").resolve(safe(request.runId()))
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
