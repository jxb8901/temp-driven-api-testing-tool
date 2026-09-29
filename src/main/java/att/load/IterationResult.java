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
    private final String iterationId;
    private final ResultStatus status;
    private final Duration duration;
    private final CaseRuntimeContext context;
    private final List<ValidationResult> validations;
    private final Path outputDirectory;
    private final att.validation.Diagnostic diagnostic;
    private final boolean evidenceRetained;
    private final CaseExecutionLog evidenceLog;

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
        this.iterationId = iterationId; this.status = status; this.duration = duration; this.context = context;
        this.validations = Collections.unmodifiableList(new ArrayList<ValidationResult>(validations));
        this.outputDirectory = outputDirectory; this.diagnostic = diagnostic; this.evidenceRetained = evidenceRetained;
        this.evidenceLog = evidenceLog;
    }
    public String iterationId() { return iterationId; }
    public ResultStatus status() { return status; }
    public Duration duration() { return duration; }
    public CaseRuntimeContext context() { return context; }
    public List<ValidationResult> validations() { return validations; }
    public Path outputDirectory() { return outputDirectory; }
    public att.validation.Diagnostic diagnostic() { return diagnostic; }

    /** Materializes deferred failure evidence after a post-completion retention claim. */
    IterationResult materializeEvidence() {
        if (evidenceRetained || evidenceLog == null || outputDirectory == null) return this;
        try {
            Files.createDirectories(outputDirectory);
            evidenceLog.materialize(outputDirectory.resolve("case.log"));
            if (context != null) Files.write(outputDirectory.resolve("case.yaml"),
                    new org.yaml.snakeyaml.Yaml().dump(context.caseTree()).getBytes(StandardCharsets.UTF_8));
            if (!Files.isRegularFile(outputDirectory.resolve("case.log"))) return this;
            return new IterationResult(iterationId, status, duration, context, validations, outputDirectory,
                    diagnostic, true, null);
        } catch (IOException | RuntimeException ignored) {
            return this;
        }
    }

    public EvidenceRef evidenceRef() {
        if (!evidenceRetained) return null;
        Path caseLog = outputDirectory == null ? null : outputDirectory.resolve("case.log");
        return new EvidenceRef(outputDirectory, caseLog, diagnostic);
    }
}
