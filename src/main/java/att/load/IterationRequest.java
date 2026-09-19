package att.load;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scheduler-to-executor contract for one load iteration. */
public final class IterationRequest {
    private final String model, iterationId, phase, userId;
    private final long iteration;
    private final Instant startedAt;
    private final Map<String, Object> inputs;
    private final Path outputDirectory;

    public IterationRequest(String model, String iterationId, long iteration, String phase,
                            Instant startedAt, String userId, Map<String, Object> inputs, Path outputDirectory) {
        if (!("closed".equals(model) || "arrivalRate".equals(model))) throw new IllegalArgumentException("LOAD.model must be closed or arrivalRate");
        if (iterationId == null || iterationId.trim().isEmpty()) throw new IllegalArgumentException("LOAD.iterationId must not be blank");
        if (iteration < 0) throw new IllegalArgumentException("LOAD.iteration must be >= 0");
        if (phase == null || phase.trim().isEmpty()) throw new IllegalArgumentException("LOAD.phase must not be blank");
        if (startedAt == null) throw new IllegalArgumentException("LOAD.startedAt is required");
        if ("closed".equals(model) && (userId == null || userId.trim().isEmpty())) throw new IllegalArgumentException("Closed iterations require a stable LOAD.userId");
        if ("arrivalRate".equals(model) && userId != null && !userId.trim().isEmpty()) throw new IllegalArgumentException("Arrival-rate iterations must not use a long-lived LOAD.userId");
        this.model = model; this.iterationId = iterationId; this.iteration = iteration; this.phase = phase;
        this.startedAt = startedAt; this.userId = userId;
        this.inputs = inputs == null || inputs.isEmpty() ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(inputs));
        this.outputDirectory = outputDirectory;
    }

    public static IterationRequest closed(String iterationId, long iteration, String phase, Instant startedAt,
                                          String userId, Map<String, Object> inputs) {
        return new IterationRequest("closed", iterationId, iteration, phase, startedAt, userId, inputs, null);
    }
    public static IterationRequest arrivalRate(String iterationId, long iteration, String phase, Instant startedAt,
                                               Map<String, Object> inputs) {
        return new IterationRequest("arrivalRate", iterationId, iteration, phase, startedAt, null, inputs, null);
    }
    public IterationRequest withOutputDirectory(Path directory) {
        return new IterationRequest(model, iterationId, iteration, phase, startedAt, userId, inputs, directory);
    }
    public String model() { return model; }
    public String iterationId() { return iterationId; }
    public long iteration() { return iteration; }
    public String phase() { return phase; }
    public Instant startedAt() { return startedAt; }
    public String userId() { return userId; }
    public Map<String, Object> inputs() { return inputs; }
    public Path outputDirectory() { return outputDirectory; }
}
