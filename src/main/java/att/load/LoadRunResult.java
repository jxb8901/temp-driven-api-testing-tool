package att.load;

import att.Version;
import att.core.ResultAggregator;
import att.core.ResultStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Completed load-run result shared by threshold evaluation and reporting. */
public final class LoadRunResult {
    private final String runId;
    private final LoadScenario scenario;
    private final Instant startedAt, endedAt;
    private final LoadMetricsSnapshot metrics;
    private final LoadThresholdSummary thresholds;
    private final Map<String, Object> evidence;
    private final Map<String, Object> resources;
    private final Map<String, LoadRunResult> workloads;

    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics) {
        this(runId, scenario, startedAt, endedAt, metrics, LoadThresholdSummary.empty(),
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                Collections.<String, LoadRunResult>emptyMap());
    }
    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence) {
        this(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence,
                Collections.<String, Object>emptyMap(), Collections.<String, LoadRunResult>emptyMap());
    }
    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence,
                         Map<String, Object> resources) {
        this(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence, resources,
                Collections.<String, LoadRunResult>emptyMap());
    }
    LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                  LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence,
                  Map<String, Object> resources, Map<String, LoadRunResult> workloads) {
        this.runId = runId; this.scenario = scenario; this.startedAt = startedAt; this.endedAt = endedAt;
        this.metrics = metrics; this.thresholds = thresholds == null ? LoadThresholdSummary.empty() : thresholds;
        this.evidence = immutable(evidence); this.resources = immutable(resources);
        this.workloads = workloads == null || workloads.isEmpty()
                ? Collections.<String, LoadRunResult>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, LoadRunResult>(workloads));
    }

    public String runId() { return runId; }
    public LoadScenario scenario() { return scenario; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public LoadMetricsSnapshot metrics() { return metrics; }
    public LoadThresholdSummary thresholds() { return thresholds; }
    public Map<String, Object> evidence() { return evidence; }
    public Map<String, Object> resources() { return resources; }
    public Map<String, LoadRunResult> workloads() { return workloads; }
    public boolean multiWorkload() { return !workloads.isEmpty(); }

    public ResultStatus status() {
        if (runtimeErrorCount() > 0L) return ResultStatus.ERROR;
        for (LoadRunResult workload : workloads.values()) if (workload.status() == ResultStatus.ERROR) return ResultStatus.ERROR;
        if (!thresholds.passed()) return ResultStatus.FAIL;
        for (LoadRunResult workload : workloads.values()) if (workload.status() == ResultStatus.FAIL) return ResultStatus.FAIL;
        return ResultStatus.PASS;
    }
    public int exitCode() { return ResultAggregator.exitCode(status()); }
    public boolean passed() { return status() == ResultStatus.PASS; }
    public LoadRunResult withThresholds(LoadThresholdSummary value) {
        return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, value, evidence, resources, workloads);
    }
    public LoadRunResult withEvidence(Map<String, Object> value) {
        return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, thresholds, value, resources, workloads);
    }
    public LoadRunResult withResources(Map<String, Object> value) {
        return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence, value, workloads);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", Version.LOAD_SUMMARY_SCHEMA);
        result.put("status", status().name()); result.put("exitCode", exitCode());
        result.put("runId", runId); result.put("startedAt", startedAt.toString()); result.put("endedAt", endedAt.toString());
        result.put("durationMs", Math.max(0L, Duration.between(startedAt, endedAt).toMillis()));
        Map<String, Object> scenarioSummary = scenario.toSummaryMap();
        if (scenario.randomizedThinkTime()) scenarioSummary.put("effectiveSeed", Long.valueOf(LoadRandomization.effectiveSeed(scenario, runId)));
        result.put("scenario", scenarioSummary); result.put("timing", timing());
        result.put("metrics", metrics.toMap()); result.put("thresholds", thresholds.toMap());
        if (!workloads.isEmpty()) result.put("workloads", workloadMaps());
        result.put("resources", resources);
        if (!evidence.isEmpty()) result.put("evidence", evidence);
        result.put("report", "report/index.html");
        return result;
    }

    private Map<String, Object> workloadMaps() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, LoadRunResult> entry : workloads.entrySet()) {
            LoadRunResult value = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("status", value.status().name());
            Map<String, Object> target = new LinkedHashMap<String, Object>();
            target.put("type", value.scenario().targetType()); target.put("id", value.scenario().targetId());
            item.put("target", target);
            item.put("model", value.scenario().model().wireName());
            item.put("metrics", value.metrics().toMap());
            item.put("thresholds", value.thresholds().toMap());
            result.put(entry.getKey(), item);
        }
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
