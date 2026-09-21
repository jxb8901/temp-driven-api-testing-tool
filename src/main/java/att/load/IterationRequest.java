package att.load;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scheduler-to-executor contract for one load iteration. */
public final class IterationRequest {
    private final String runId, model, iterationId, phase, userId;
    private final long iteration;
    private final Instant startedAt, runStartedAt;
    private final Map<String, Object> inputs;
    private final Path outputDirectory;

    public IterationRequest(String model, String iterationId, long iteration, String phase,
                            Instant startedAt, String userId, Map<String, Object> inputs, Path outputDirectory) {
        this("LOAD", null, model, iterationId, iteration, phase, startedAt, userId, inputs, outputDirectory);
    }

    public IterationRequest(String runId, String model, String iterationId, long iteration, String phase,
                            Instant startedAt, String userId, Map<String, Object> inputs, Path outputDirectory) {
        this(runId, null, model, iterationId, iteration, phase, startedAt, userId, inputs, outputDirectory);
    }

    public IterationRequest(String runId, Instant runStartedAt, String model, String iterationId, long iteration,
                            String phase, Instant startedAt, String userId, Map<String, Object> inputs,
                            Path outputDirectory) {
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
        this.runId = runId; this.model = model; this.iterationId = iterationId; this.iteration = iteration; this.phase = phase;
        this.startedAt = startedAt; this.runStartedAt = runStartedAt == null ? startedAt : runStartedAt; this.userId = userId;
        this.inputs = inputs == null || inputs.isEmpty() ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(LoadIsolation.deepCopyMap(inputs));
        this.outputDirectory = outputDirectory;
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
        return new IterationRequest(runId, runStartedAt, model, iterationId, iteration, phase, startedAt, userId, inputs, directory);
    }
    public String runId() { return runId; }
    public String model() { return model; }
    public String iterationId() { return iterationId; }
    public long iteration() { return iteration; }
    public String phase() { return phase; }
    public Instant startedAt() { return startedAt; }
    public Instant runStartedAt() { return runStartedAt; }
    public String userId() { return userId; }
    public Map<String, Object> inputs() { return inputs; }
    public Path outputDirectory() { return outputDirectory; }
}
