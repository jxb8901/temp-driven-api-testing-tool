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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Workload-agnostic execution layer. Schedulers provide timing and identity;
 * this class creates all mutable ATT runtime state for exactly one iteration.
 */
public final class IterationExecutor {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final LoadTarget target;
    private final Set<String> iterationIds = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public IterationExecutor(Path projectRoot, FrameworkConfig config, LoadTarget target) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize(); this.config = config; this.target = target;
    }

    public IterationResult execute(IterationRequest request) {
        if (!iterationIds.add(request.iterationId()))
            throw new IllegalArgumentException("Duplicate LOAD.iterationId within this load run: " + request.iterationId());
        Instant started = Instant.now();
        Path iterationDirectory = null;
        boolean temporary = request.outputDirectory() == null;
        CaseRuntimeContext context = null;
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        CaseExecutionLog log = null;
        DbHelperExecutor db = null;
        boolean finalized = false;
        ResultStatus status = ResultStatus.ERROR;
        att.validation.Diagnostic diagnostic = null;
        try {
            iterationDirectory = iterationDirectory(request, temporary);
            Path logPath = iterationDirectory.resolve("case.log");
            TestCase testCase = testCase(request);
            StageCaseData stage = new StageCaseData("LOAD", target.template().name(), Collections.<String, Object>emptyMap());
            context = new CaseRuntimeContext(testCase, iterationDirectory, "LOAD-" + request.iterationId(), iterationDirectory, logPath);
            context.put("CASE.environment", config.environment());
            context.setLoad(request.model(), request.iterationId(), request.iteration(), request.phase(), request.startedAt().toString(), request.userId());
            log = new CaseExecutionLog(logPath, config.caseLogYamlAnchors());
            context.beginStage(stage, target.template().name(), target.template().directory());
            db = new DbHelperExecutor(projectRoot, config);
            db.beginCase();
            ToolInvoker tools = new ToolInvoker(projectRoot, config);
            MqHelperExecutor mq = new MqHelperExecutor(projectRoot, config);
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
            if (db != null && !finalized) db.abortCase();
        } finally {
            if (context != null) context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            if (log != null) try { log.close(); } catch (Exception ignored) { }
            if (db != null) db.close();
        }
        Duration duration = Duration.between(started, Instant.now());
        if (temporary && status == ResultStatus.PASS && iterationDirectory != null) deleteTree(iterationDirectory);
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

    private Path iterationDirectory(IterationRequest request, boolean temporary) throws IOException {
        if (temporary) return Files.createTempDirectory("att-load-" + safe(request.iterationId()) + "-");
        Path root = request.outputDirectory().toAbsolutePath().normalize();
        Files.createDirectories(root);
        return IdentifierValidator.strictChild(root, safe(request.iterationId()), "Load iteration directory");
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
    private void deleteTree(Path root) {
        try {
            if (!Files.exists(root)) return;
            java.util.List<Path> paths = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.sorted(java.util.Comparator.reverseOrder()).forEach(paths::add); }
            for (Path path : paths) Files.deleteIfExists(path);
        } catch (Exception ignored) { }
    }
}
