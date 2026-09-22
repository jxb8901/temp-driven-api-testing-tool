package att.load;

import att.core.ResultAggregator;
import att.core.ResultStatus;
import att.Version;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Completed scheduler result shared by threshold evaluation and reporting. */
public final class LoadRunResult {
    private final String runId;
    private final LoadScenario scenario;
    private final Instant startedAt, endedAt;
    private final LoadMetricsSnapshot metrics;
    private final LoadThresholdSummary thresholds;
    private final Map<String, Object> evidence;
    private final Map<String, Object> resources;

    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics) {
        this(runId, scenario, startedAt, endedAt, metrics, LoadThresholdSummary.empty(),
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());
    }
    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence) {
        this(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence, Collections.<String, Object>emptyMap());
    }
    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence,
                         Map<String, Object> resources) {
        this.runId = runId; this.scenario = scenario; this.startedAt = startedAt; this.endedAt = endedAt;
        this.metrics = metrics; this.thresholds = thresholds == null ? LoadThresholdSummary.empty() : thresholds;
        this.evidence = immutable(evidence); this.resources = immutable(resources);
    }
    public String runId() { return runId; }
    public LoadScenario scenario() { return scenario; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public LoadMetricsSnapshot metrics() { return metrics; }
    public LoadThresholdSummary thresholds() { return thresholds; }
    public Map<String, Object> evidence() { return evidence; }
    public Map<String, Object> resources() { return resources; }
    public ResultStatus status() { return runtimeErrorCount() > 0L ? ResultStatus.ERROR : (thresholds.passed() ? ResultStatus.PASS : ResultStatus.FAIL); }
    public int exitCode() { return ResultAggregator.exitCode(status()); }
    public boolean passed() { return status() == ResultStatus.PASS; }
    public LoadRunResult withThresholds(LoadThresholdSummary value) { return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, value, evidence, resources); }
    public LoadRunResult withEvidence(Map<String, Object> value) { return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, thresholds, value, resources); }
    public LoadRunResult withResources(Map<String, Object> value) { return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence, value); }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("schemaVersion", Version.LOAD_SUMMARY_SCHEMA);
        result.put("status", status().name()); result.put("exitCode", exitCode());
        result.put("runId", runId); result.put("startedAt", startedAt.toString()); result.put("endedAt", endedAt.toString());
        result.put("durationMs", Math.max(0L, Duration.between(startedAt, endedAt).toMillis()));
        result.put("scenario", scenario.toSummaryMap()); result.put("timing", timing());
        result.put("metrics", metrics.toMap()); result.put("thresholds", thresholds.toMap());
        result.put("resources", resources);
        if (!evidence.isEmpty()) result.put("evidence", evidence);
        result.put("report", "report/index.html");
        return result;
    }

    private Map<String, Object> timing() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> phases = new ArrayList<Map<String, Object>>();
        String[] names = {"WARMUP", "RAMP_UP", "STEADY", "RAMP_DOWN"};
        Duration[] durations = {scenario.warmup(), scenario.rampUp(), scenario.duration(), scenario.rampDown()};
        Instant cursor = startedAt;
        for (int index = 0; index < names.length; index++) {
            Instant end = cursor.plus(durations[index]);
            Map<String, Object> phase = new LinkedHashMap<String, Object>();
            phase.put("phase", names[index]); phase.put("startAt", cursor.toString()); phase.put("endAt", end.toString());
            phase.put("durationMs", durations[index].toMillis()); phase.put("measured", index != 0);
            phases.add(phase); cursor = end;
        }
        result.put("startedAt", startedAt.toString()); result.put("endedAt", endedAt.toString());
        result.put("durationMs", Math.max(0L, Duration.between(startedAt, endedAt).toMillis()));
        result.put("phases", phases);
        return result;
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(LoadIsolation.deepCopyMap(value));
    }
    private long runtimeErrorCount() {
        Object value = metrics.value("runtimeError");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}
