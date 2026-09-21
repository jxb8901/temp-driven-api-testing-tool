package att.load;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Completed scheduler result shared by threshold evaluation and reporting. */
public final class LoadRunResult {
    private final String runId;
    private final LoadScenario scenario;
    private final Instant startedAt, endedAt;
    private final LoadMetricsSnapshot metrics;
    private final LoadThresholdSummary thresholds;
    private final Map<String, Object> evidence;

    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics) {
        this(runId, scenario, startedAt, endedAt, metrics, LoadThresholdSummary.empty(), Collections.<String, Object>emptyMap());
    }
    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence) {
        this.runId = runId; this.scenario = scenario; this.startedAt = startedAt; this.endedAt = endedAt;
        this.metrics = metrics; this.thresholds = thresholds == null ? LoadThresholdSummary.empty() : thresholds;
        this.evidence = evidence == null ? Collections.<String, Object>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(evidence));
    }
    public String runId() { return runId; }
    public LoadScenario scenario() { return scenario; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public LoadMetricsSnapshot metrics() { return metrics; }
    public LoadThresholdSummary thresholds() { return thresholds; }
    public Map<String, Object> evidence() { return evidence; }
    public boolean passed() { return thresholds.passed() && metrics.longValue("runtimeError") == 0L; }
    public LoadRunResult withThresholds(LoadThresholdSummary value) { return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, value, evidence); }
    public LoadRunResult withEvidence(Map<String, Object> value) { return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, thresholds, value); }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("status", passed() ? "PASS" : "FAIL");
        result.put("runId", runId); result.put("startedAt", startedAt.toString()); result.put("endedAt", endedAt.toString());
        result.put("scenario", scenario.toMap()); result.put("metrics", metrics.toMap()); result.put("thresholds", thresholds.toMap());
        if (!evidence.isEmpty()) result.put("evidence", evidence); return result;
    }
}
