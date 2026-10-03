package att.load;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Scheduler-to-executor contract for one load iteration. */
public final class IterationRequest {
    private final String runId, model, iterationId, phase, userId, workloadId;
    private final String mixId, targetType, targetId;
    private final long iteration;
    private final Instant startedAt, runStartedAt;
    private final Map<String, Object> inputs;
    private final Path outputDirectory;
    private final boolean retainSuccessEvidence, retainFailureEvidence, captureFailureLog;
    private final BooleanSupplier testdataWaitAllowed;
    private final Long testdataOrdinal;

    public IterationRequest(String model, String iterationId, long iteration, String phase,
                            Instant startedAt, String userId, Map<String, Object> inputs, Path outputDirectory) {
        this("LOAD", null, model, iterationId, iteration, phase, startedAt, userId, inputs, outputDirectory,
                outputDirectory != null, true, null);
    }

    public IterationRequest(String runId, String model, String iterationId, long iteration, String phase,
                            Instant startedAt, String userId, Map<String, Object> inputs, Path outputDirectory) {
        this(runId, null, model, iterationId, iteration, phase, startedAt, userId, inputs, outputDirectory,
                outputDirectory != null, true, null);
    }

    public IterationRequest(String runId, Instant runStartedAt, String model, String iterationId, long iteration,
                            String phase, Instant startedAt, String userId, Map<String, Object> inputs,
                            Path outputDirectory) {
        this(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs, outputDirectory,
                outputDirectory != null, true, null);
    }

    private IterationRequest(String runId, Instant runStartedAt, String model, String iterationId, long iteration,
                             String phase, Instant startedAt, String userId, Map<String, Object> inputs,
                             Path outputDirectory, boolean retainSuccessEvidence, boolean retainFailureEvidence,
                             String workloadId) {
        this(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, retainSuccessEvidence, retainFailureEvidence, workloadId, () -> true, null);
    }

    private IterationRequest(String runId, Instant runStartedAt, String model, String iterationId, long iteration,
                             String phase, Instant startedAt, String userId, Map<String, Object> inputs,
                             Path outputDirectory, boolean retainSuccessEvidence, boolean retainFailureEvidence,
                             String workloadId, BooleanSupplier testdataWaitAllowed, Long testdataOrdinal) {
        this(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, retainSuccessEvidence, retainFailureEvidence, workloadId, retainFailureEvidence,
                testdataWaitAllowed, testdataOrdinal);
    }

    private IterationRequest(String runId, Instant runStartedAt, String model, String iterationId, long iteration,
                             String phase, Instant startedAt, String userId, Map<String, Object> inputs,
                             Path outputDirectory, boolean retainSuccessEvidence, boolean retainFailureEvidence,
                             String workloadId, boolean captureFailureLog,
                             BooleanSupplier testdataWaitAllowed, Long testdataOrdinal) {
        this(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, retainSuccessEvidence, retainFailureEvidence, workloadId, captureFailureLog,
                testdataWaitAllowed, testdataOrdinal, false);
    }

    private IterationRequest(String runId, Instant runStartedAt, String model, String iterationId, long iteration,
                             String phase, Instant startedAt, String userId, Map<String, Object> inputs,
                             Path outputDirectory, boolean retainSuccessEvidence, boolean retainFailureEvidence,
                             String workloadId, boolean captureFailureLog,
                             BooleanSupplier testdataWaitAllowed, Long testdataOrdinal, boolean inputsFrozen) {
        if (runId == null || runId.trim().isEmpty()) throw new IllegalArgumentException("Load runId must not be blank");
        if (!("closed".equals(model) || "arrivalRate".equals(model))) throw new IllegalArgumentException("LOAD.model must be closed or arrivalRate");
        if (iterationId == null || iterationId.trim().isEmpty()) throw new IllegalArgumentException("LOAD.iterationId must not be blank");
        if (iteration < 0) throw new IllegalArgumentException("LOAD.iteration must be >= 0");
        if (phase == null || phase.trim().isEmpty()) throw new IllegalArgumentException("EXEC.LOAD.PHASE must not be blank");
        if (!("WARMUP".equals(phase) || "RAMP_UP".equals(phase) || "STEADY".equals(phase) || "RAMP_DOWN".equals(phase)))
            throw new IllegalArgumentException("EXEC.LOAD.PHASE must be WARMUP, RAMP_UP, STEADY, or RAMP_DOWN");
        if (startedAt == null) throw new IllegalArgumentException("LOAD.startedAt is required");
        if ("closed".equals(model) && (userId == null || userId.trim().isEmpty())) throw new IllegalArgumentException("Closed iterations require a stable LOAD.userId");
        if ("arrivalRate".equals(model) && userId != null && !userId.trim().isEmpty()) throw new IllegalArgumentException("Arrival-rate iterations must not use a long-lived LOAD.userId");
        if (workloadId != null && workloadId.trim().isEmpty()) throw new IllegalArgumentException("LOAD.workloadId must not be blank");
        this.runId = runId; this.model = model; this.iterationId = iterationId; this.iteration = iteration; this.phase = phase;
        this.startedAt = startedAt; this.runStartedAt = runStartedAt == null ? startedAt : runStartedAt; this.userId = userId;
        this.inputs = inputsFrozen ? inputs : LoadIsolation.deepImmutableMap(inputs);
        this.outputDirectory = outputDirectory;
        this.retainSuccessEvidence = retainSuccessEvidence;
        this.retainFailureEvidence = retainFailureEvidence;
        this.captureFailureLog = captureFailureLog || retainFailureEvidence;
        this.workloadId = workloadId;
        this.testdataWaitAllowed = testdataWaitAllowed == null ? () -> true : testdataWaitAllowed;
        if (testdataOrdinal != null && testdataOrdinal.longValue() < 0L)
            throw new IllegalArgumentException("Testdata ordinal must be >= 0");
        this.testdataOrdinal = testdataOrdinal;
        this.mixId = null; this.targetType = null; this.targetId = null;
    }

    private IterationRequest(IterationRequest source, String mixId, String targetType, String targetId,
                             Map<String, Object> selectedInputs) {
        this.runId = source.runId; this.model = source.model; this.iterationId = source.iterationId;
        this.iteration = source.iteration; this.phase = source.phase; this.startedAt = source.startedAt;
        this.runStartedAt = source.runStartedAt; this.userId = source.userId;
        this.inputs = LoadIsolation.deepImmutableMap(selectedInputs); this.outputDirectory = source.outputDirectory;
        this.retainSuccessEvidence = source.retainSuccessEvidence; this.retainFailureEvidence = source.retainFailureEvidence;
        this.captureFailureLog = source.captureFailureLog; this.workloadId = source.workloadId;
        this.testdataWaitAllowed = source.testdataWaitAllowed; this.testdataOrdinal = source.testdataOrdinal;
        this.mixId = mixId; this.targetType = targetType; this.targetId = targetId;
    }

    public static IterationRequest closed(String iterationId, long iteration, String phase, Instant startedAt,
                                          String userId, Map<String, Object> inputs) {
        return closed("LOAD", iterationId, iteration, phase, startedAt, userId, inputs);
    }
    public static IterationRequest closed(String runId, String iterationId, long iteration, String phase,
                                          Instant startedAt, String userId, Map<String, Object> inputs) {
        return new IterationRequest(runId, "closed", iterationId, iteration, phase, startedAt, userId, inputs, null);
    }
    public static IterationRequest arrivalRate(String iterationId, long iteration, String phase, Instant startedAt,
                                               Map<String, Object> inputs) {
        return arrivalRate("LOAD", iterationId, iteration, phase, startedAt, inputs);
    }
    public static IterationRequest arrivalRate(String runId, String iterationId, long iteration, String phase,
                                               Instant startedAt, Map<String, Object> inputs) {
        return new IterationRequest(runId, "arrivalRate", iterationId, iteration, phase, startedAt, null, inputs, null);
    }
    public IterationRequest withOutputDirectory(Path directory) {
        return preserveMix(new IterationRequest(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs, directory,
                directory != null, retainFailureEvidence, workloadId, captureFailureLog, testdataWaitAllowed, testdataOrdinal, true));
    }
    public IterationRequest withFailureEvidence(boolean enabled) {
        return preserveMix(new IterationRequest(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, retainSuccessEvidence, enabled, workloadId, enabled, testdataWaitAllowed, testdataOrdinal, true));
    }
    public IterationRequest withEvidenceRetention(boolean success, boolean failure) {
        return preserveMix(new IterationRequest(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, success, failure, workloadId, failure, testdataWaitAllowed, testdataOrdinal, true));
    }
    /** Enables bounded failure-log capture while leaving the post-outcome retention claim to the scheduler. */
    public IterationRequest withFailureLogCapture(boolean enabled) {
        return preserveMix(new IterationRequest(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, retainSuccessEvidence, retainFailureEvidence, workloadId, enabled,
                testdataWaitAllowed, testdataOrdinal, true));
    }
    public IterationRequest withWorkloadId(String value) {
        return preserveMix(new IterationRequest(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, retainSuccessEvidence, retainFailureEvidence, value, captureFailureLog,
                testdataWaitAllowed, testdataOrdinal, true));
    }
    public IterationRequest withMixIdentity(String mix, String type, String target, Map<String, Object> selectedInputs) {
        return new IterationRequest(this, mix, type, target, selectedInputs);
    }
    private IterationRequest preserveMix(IterationRequest copy) {
        return mixId == null ? copy : new IterationRequest(copy, mixId, targetType, targetId, copy.inputs);
    }
    public IterationRequest withTestdataWaitAllowed(BooleanSupplier value) {
        return preserveMix(new IterationRequest(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, retainSuccessEvidence, retainFailureEvidence, workloadId, captureFailureLog,
                value, testdataOrdinal, true));
    }
    public IterationRequest withTestdataOrdinal(long value) {
        return preserveMix(new IterationRequest(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs,
                outputDirectory, retainSuccessEvidence, retainFailureEvidence, workloadId, captureFailureLog,
                testdataWaitAllowed, Long.valueOf(value), true));
    }
    public String runId() { return runId; }
    public String model() { return model; }
    public String iterationId() { return iterationId; }
    public long iteration() { return iteration; }
    public String phase() { return phase; }
    public Instant startedAt() { return startedAt; }
    public Instant runStartedAt() { return runStartedAt; }
    public String userId() { return userId; }
    public String workloadId() { return workloadId; }
    public String mixId() { return mixId; }
    public String targetType() { return targetType; }
    public String targetId() { return targetId; }
    public Map<String, Object> inputs() { return inputs; }
    public Path outputDirectory() { return outputDirectory; }
    public boolean retainSuccessEvidence() { return retainSuccessEvidence; }
    public boolean retainFailureEvidence() { return retainFailureEvidence; }
    public boolean captureFailureLog() { return captureFailureLog; }
    public BooleanSupplier testdataWaitAllowed() { return testdataWaitAllowed; }
    public Long testdataOrdinal() { return testdataOrdinal; }
}
