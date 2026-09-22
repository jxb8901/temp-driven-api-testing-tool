package att.load;

import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.ValidationResult;

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

    IterationResult(String iterationId, ResultStatus status, Duration duration, CaseRuntimeContext context,
                    List<ValidationResult> validations, Path outputDirectory, att.validation.Diagnostic diagnostic) {
        this.iterationId = iterationId; this.status = status; this.duration = duration; this.context = context;
        this.validations = Collections.unmodifiableList(new ArrayList<ValidationResult>(validations));
        this.outputDirectory = outputDirectory; this.diagnostic = diagnostic;
    }
    public String iterationId() { return iterationId; }
    public ResultStatus status() { return status; }
    public Duration duration() { return duration; }
    public CaseRuntimeContext context() { return context; }
    public List<ValidationResult> validations() { return validations; }
    public Path outputDirectory() { return outputDirectory; }
    public att.validation.Diagnostic diagnostic() { return diagnostic; }
    public EvidenceRef evidenceRef() {
        if (outputDirectory == null && status == ResultStatus.PASS && diagnostic == null) return null;
        Path caseLog = outputDirectory == null ? null : outputDirectory.resolve("case.log");
        return new EvidenceRef(outputDirectory, caseLog, diagnostic);
    }
}
