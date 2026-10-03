package att.load;

import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.ValidationResult;
import att.core.CaseExecutionLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lightweight result returned to a load scheduler for one iteration. */
public final class IterationResult {
    private final String iterationId, executionId;
    private final ResultStatus status;
    private final Duration duration;
    private final CaseRuntimeContext context;
    private final List<ValidationResult> validations;
    private final Path outputDirectory;
    private final Path evidenceDirectory;
    private final att.validation.Diagnostic diagnostic;
    private final boolean evidenceRetained;
    private final CaseExecutionLog evidenceLog;
    private final Path transientWorkspace;

    IterationResult(String iterationId, ResultStatus status, Duration duration, CaseRuntimeContext context,
                    List<ValidationResult> validations, Path outputDirectory, att.validation.Diagnostic diagnostic) {
        this(iterationId, status, duration, context, validations, outputDirectory, diagnostic,
                status != ResultStatus.PASS);
    }

    IterationResult(String iterationId, ResultStatus status, Duration duration, CaseRuntimeContext context,
                    List<ValidationResult> validations, Path outputDirectory, att.validation.Diagnostic diagnostic,
                    boolean evidenceRetained) {
        this(iterationId, status, duration, context, validations, outputDirectory, diagnostic, evidenceRetained, null);
    }

    IterationResult(String iterationId, ResultStatus status, Duration duration, CaseRuntimeContext context,
                    List<ValidationResult> validations, Path outputDirectory, att.validation.Diagnostic diagnostic,
                    boolean evidenceRetained, CaseExecutionLog evidenceLog) {
        this(iterationId, iterationId, status, duration, context, validations, outputDirectory, diagnostic, evidenceRetained, evidenceLog);
    }

    IterationResult(String iterationId, String executionId, ResultStatus status, Duration duration, CaseRuntimeContext context,
                    List<ValidationResult> validations, Path outputDirectory, att.validation.Diagnostic diagnostic,
                    boolean evidenceRetained, CaseExecutionLog evidenceLog) {
        this(iterationId, executionId, status, duration, context, validations, outputDirectory, diagnostic,
                evidenceRetained, evidenceLog, null);
    }

    IterationResult(String iterationId, String executionId, ResultStatus status, Duration duration, CaseRuntimeContext context,
                    List<ValidationResult> validations, Path outputDirectory, att.validation.Diagnostic diagnostic,
                    boolean evidenceRetained, CaseExecutionLog evidenceLog, Path transientWorkspace) {
        this(iterationId, executionId, status, duration, context, validations, outputDirectory, outputDirectory,
                diagnostic, evidenceRetained, evidenceLog, transientWorkspace);
    }

    IterationResult(String iterationId, String executionId, ResultStatus status, Duration duration, CaseRuntimeContext context,
                    List<ValidationResult> validations, Path outputDirectory, Path evidenceDirectory,
                    att.validation.Diagnostic diagnostic, boolean evidenceRetained, CaseExecutionLog evidenceLog,
                    Path transientWorkspace) {
        this.iterationId = iterationId; this.executionId = executionId; this.status = status; this.duration = duration; this.context = context;
        this.validations = Collections.unmodifiableList(new ArrayList<ValidationResult>(validations));
        this.outputDirectory = outputDirectory; this.evidenceDirectory = evidenceDirectory;
        this.diagnostic = diagnostic; this.evidenceRetained = evidenceRetained;
        this.evidenceLog = evidenceLog; this.transientWorkspace = transientWorkspace;
    }
    public String iterationId() { return iterationId; }
    public String executionId() { return executionId; }
    public ResultStatus status() { return status; }
    public Duration duration() { return duration; }
    public CaseRuntimeContext context() { return context; }
    public boolean testdataStopRequested() {
        return context != null && Boolean.TRUE.equals(context.resolve("CASE.testdataStop"));
    }
    public List<ValidationResult> validations() { return validations; }
    public Path outputDirectory() { return outputDirectory; }
    public att.validation.Diagnostic diagnostic() { return diagnostic; }

    /** Materializes deferred failure evidence after a post-completion retention claim. */
    IterationResult materializeEvidence() {
        if (evidenceRetained || evidenceLog == null || evidenceDirectory == null) return this;
        boolean interrupted = Thread.interrupted();
        try {
            Files.createDirectories(outputDirectory);
            Files.createDirectories(evidenceDirectory);
            copyWorkspace(transientWorkspace, evidenceDirectory);
            copyWorkspace(outputDirectory, evidenceDirectory);
            if (context != null) context.materializeResourceOutputs(outputDirectory);
            Path outputResource = outputDirectory.resolve("resource-output.yaml");
            if (Files.isRegularFile(outputResource)) linkOrCopy(outputResource, evidenceDirectory.resolve("resource-output.yaml"));
            Path retainedLog = evidenceDirectory.resolve("case.log");
            evidenceLog.materialize(retainedLog);
            linkOrCopy(retainedLog, outputDirectory.resolve("case.log"));
            if (context != null) {
                byte[] caseYaml = new org.yaml.snakeyaml.Yaml().dump(context.caseTree()).getBytes(StandardCharsets.UTF_8);
                Path retainedCase = evidenceDirectory.resolve("case.yaml");
                Files.write(retainedCase, caseYaml);
                linkOrCopy(retainedCase, outputDirectory.resolve("case.yaml"));
            }
            if (!Files.isRegularFile(evidenceDirectory.resolve("case.log"))) return this;
            deleteWorkspace(transientWorkspace);
            return new IterationResult(iterationId, executionId, status, duration, context, validations,
                    outputDirectory, evidenceDirectory, diagnostic, true, null, null);
        } catch (IOException | RuntimeException ignored) {
            deletePartialWorkspace();
            return this;
        } finally {
            if (interrupted || Thread.interrupted()) Thread.currentThread().interrupt();
        }
    }

    private void deletePartialWorkspace() {
        if (evidenceDirectory == null || !Files.exists(evidenceDirectory)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(evidenceDirectory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /** Drops command side effects when the bounded retention policy declines this iteration. */
    void discardTransientWorkspace() {
        deleteWorkspace(transientWorkspace);
        deleteWorkspace(outputDirectory);
    }

    private void linkOrCopy(Path source, Path destination) throws IOException {
        if (source.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) return;
        Files.createDirectories(destination.getParent());
        Files.deleteIfExists(destination);
        try { Files.createLink(destination, source); }
        catch (IOException | UnsupportedOperationException unavailable) {
            Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyWorkspace(Path source, Path destination) throws IOException {
        if (source == null || !Files.isDirectory(source)) return;
        if (source.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) return;
        Path root = destination.toAbsolutePath().normalize();
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path current = iterator.next();
                if (current.equals(source) || Files.isSymbolicLink(current)) continue;
                Path target = root.resolve(source.relativize(current)).normalize();
                if (!target.startsWith(root)) throw new IOException("Load evidence path escapes its workspace");
                if (Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else if (Files.isRegularFile(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    linkOrCopy(current, target);
                }
            }
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

    public EvidenceRef evidenceRef() {
        if (!evidenceRetained) return null;
        Path caseLog = evidenceDirectory == null ? null : evidenceDirectory.resolve("case.log");
        return new EvidenceRef(evidenceDirectory, caseLog, diagnostic, executionId);
    }
}
