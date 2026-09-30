package att.load;

import att.config.FrameworkConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultAggregator;
import att.core.ResultStatus;
import att.core.ValidationResult;
import att.exec.DbHelperExecutor;
import att.exec.MqHelperExecutor;
import att.exec.ToolInvoker;
import att.flow.FlowRegistry;
import att.template.StageTemplateRunner;
import att.template.UnifiedTemplateEngine;
import att.template.DefaultBuiltInProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Workload-agnostic execution layer; one instance binds one fixed target and shared run resources. */
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

    @Override public IterationResult execute(IterationRequest request) {
        resources.ensureOpen();
        Instant started = Instant.now();
        Path executionWorkspace = null;
        Path transientWorkspace = null;
        Path evidenceDirectory = null;
        String executionId = request.iterationId();
        CaseRuntimeContext context = null;
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        CaseExecutionLog log = null;
        boolean finalized = false;
        ResultStatus status = ResultStatus.ERROR;
        att.validation.Diagnostic diagnostic = null;
        try {
            Path pending = outputRoot.resolve("load").resolve(safe(request.runId())).resolve("executions")
                    .resolve(".pending-" + LoadIsolation.shortHash(request.iterationId()));
            LoadExecutionContextAdapter.Prepared prepared = new LoadExecutionContextAdapter(projectRoot, config, target)
                    .prepare(request, request.iterationId(), pending, pending.resolve("case.log"));
            context = prepared.context();
            context.setResourceOutputEnabled(target.resourceOutputEnabled());
            context.setTemplateMetadata(target.template().name(), target.template().directory());
            context.beginExecutionIdInitialization();
            UnifiedTemplateEngine identityEngine = new UnifiedTemplateEngine(null, null, null, null,
                    new DefaultBuiltInProvider(resources.sequences()));
            try {
                executionId = target.execIdFormat().isEmpty()
                        ? resources.nextDefaultExecutionId(request.runId())
                        : LoadExecutionIdPattern.evaluate(target.execIdFormat(),
                                "closed".equals(request.model()) ? LoadScenario.Model.CLOSED : LoadScenario.Model.ARRIVAL_RATE,
                                context, identityEngine, null);
                resources.reserveExecutionId(executionId, request);
                ensureUnusedExecutionId(request, executionId);
            } catch (Exception invalidId) {
                executionId = resources.nextDefaultExecutionId(request.runId());
                resources.reserveExecutionId(executionId, request);
                executionWorkspace = executionWorkspace(request, executionId);
                context.finishExecutionIdInitialization(executionId, executionWorkspace, executionWorkspace.resolve("case.log"));
                log = CaseExecutionLog.lightweight(executionWorkspace.resolve("case.log"), config.caseLogYamlAnchors());
                throw invalidId;
            }
            executionWorkspace = executionWorkspace(request, executionId);
            context.finishExecutionIdInitialization(executionId, executionWorkspace, executionWorkspace.resolve("case.log"));
            // EXEC.OUTPUT_DIR is a logical/planned path in Load. A local
            // command-backed Tool creates it on demand when it needs a cwd;
            // metrics-only/helper iterations therefore do not pay per-iteration
            // mkdir and temporary-workspace cleanup I/O.
            context.setCommandWorkingDirectory(executionWorkspace);
            log = CaseExecutionLog.lightweight(executionWorkspace.resolve("case.log"), config.caseLogYamlAnchors());
            att.core.ExecutionBootstrapVariables.evaluate(context.bootstrapVariables(), context, identityEngine);
            context.beginStage(prepared.stage(), target.template().name(), target.template().directory());
            DbHelperExecutor db = resources.db();
            db.beginCase();
            ToolInvoker tools = new ToolInvoker(projectRoot, config);
            MqHelperExecutor mq = resources.mq();
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(tools, db, mq, resources.http(),
                    new att.template.DefaultBuiltInProvider(resources.sequences()));
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
        boolean retainEvidence = status == ResultStatus.PASS
                ? request.retainSuccessEvidence() : request.retainFailureEvidence();
        evidenceDirectory = evidenceDirectory(request, executionId, status == ResultStatus.PASS);
        if (retainEvidence && log != null) {
            try {
                Files.createDirectories(evidenceDirectory);
                mergeWorkspace(transientWorkspace, evidenceDirectory);
                // Files explicitly written through EXEC.OUTPUT_DIR remain at
                // their public executions/<EXEC.ID> location and are also
                // included in retained sample/failure evidence.
                mergeWorkspace(executionWorkspace, evidenceDirectory);
                if (executionWorkspace != null) Files.createDirectories(executionWorkspace);
                log.materialize(evidenceDirectory.resolve("case.log"));
                log.materialize(executionWorkspace.resolve("case.log"));
            }
            catch (Exception ignored) { }
        }
        if (context != null && evidenceDirectory != null && retainEvidence) {
            try {
                Files.createDirectories(evidenceDirectory);
                if (executionWorkspace != null) Files.createDirectories(executionWorkspace);
                context.materializeResourceOutputs(executionWorkspace);
                Path resourceOutput = executionWorkspace.resolve("resource-output.yaml");
                if (Files.isRegularFile(resourceOutput)) Files.copy(resourceOutput, evidenceDirectory.resolve("resource-output.yaml"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                byte[] caseYaml = new org.yaml.snakeyaml.Yaml().dump(context.caseTree()).getBytes(StandardCharsets.UTF_8);
                Files.write(evidenceDirectory.resolve("case.yaml"), caseYaml);
                Files.write(executionWorkspace.resolve("case.yaml"), caseYaml);
            } catch (Exception ignored) { }
        }
        boolean evidenceAvailable = retainEvidence && evidenceDirectory != null
                && Files.isDirectory(evidenceDirectory)
                && Files.isRegularFile(evidenceDirectory.resolve("case.log"));
        if (evidenceAvailable) {
            deleteWorkspace(transientWorkspace);
            transientWorkspace = null;
        } else {
            deleteEmptyWorkspace(executionWorkspace);
        }
        return new IterationResult(request.iterationId(), executionId, status, duration, context, results,
                executionWorkspace, evidenceDirectory, diagnostic, evidenceAvailable,
                evidenceAvailable ? null : log, transientWorkspace);
    }

    private void deleteEmptyWorkspace(Path directory) {
        Path boundary = outputRoot.resolve("load").toAbsolutePath().normalize();
        Path current = directory == null ? null : directory.toAbsolutePath().normalize();
        while (current != null && current.startsWith(boundary) && !current.equals(boundary)) {
            if (!Files.isDirectory(current)) return;
            try (java.nio.file.DirectoryStream<Path> contents = Files.newDirectoryStream(current)) {
                if (contents.iterator().hasNext()) return;
                Files.deleteIfExists(current);
            } catch (IOException ignored) { return; }
            current = current.getParent();
        }
    }

    private void deleteWorkspace(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private Path executionWorkspace(IterationRequest request, String executionId) {
        return outputRoot.resolve("load").resolve(safe(request.runId())).resolve("executions").resolve(executionId).normalize();
    }

    private Path evidenceDirectory(IterationRequest request, String executionId, boolean success) {
        return outputRoot.resolve("load").resolve(safe(request.runId()))
                .resolve(success ? "samples" : "failures").resolve(executionId).normalize();
    }

    private void ensureUnusedExecutionId(IterationRequest request, String executionId) {
        Path run = outputRoot.resolve("load").resolve(safe(request.runId()));
        if (Files.exists(run.resolve("samples").resolve(executionId))
                || Files.exists(run.resolve("failures").resolve(executionId))
                || Files.exists(run.resolve("executions").resolve(executionId)))
            throw new IllegalArgumentException("EXEC.ID already has a Load workspace: " + executionId);
    }

    private void mergeWorkspace(Path source, Path destination) throws IOException {
        if (source == null || !Files.isDirectory(source)) return;
        Files.createDirectories(destination);
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path current = iterator.next();
                if (current.equals(source) || Files.isSymbolicLink(current)) continue;
                Path targetPath = destination.resolve(source.relativize(current)).normalize();
                if (!targetPath.startsWith(destination.toAbsolutePath().normalize()))
                    throw new IOException("Load evidence path escapes its workspace");
                if (Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(targetPath);
                else if (Files.isRegularFile(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(current, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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

    Path projectRoot() { return projectRoot; }
    FrameworkConfig config() { return config; }
    LoadRunResources resources() { return resources; }
    Path outputRoot() { return outputRoot; }

    public void close() { if (ownsResources) resources.close(); }
}
