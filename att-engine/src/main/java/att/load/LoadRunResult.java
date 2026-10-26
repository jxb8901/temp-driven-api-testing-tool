package att.load;

import att.Version;
import att.core.ResultAggregator;
import att.core.ResultStatus;
import att.server.api.LoadTerminalResult;

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
        if (preparationFailed()) return ResultStatus.ERROR;
        if (runtimeErrorCount() > 0L) return ResultStatus.ERROR;
        for (LoadRunResult workload : workloads.values()) if (workload.status() == ResultStatus.ERROR) return ResultStatus.ERROR;
        if ("NOT_MET".equals(thresholds.status())) return ResultStatus.FAIL;
        for (LoadRunResult workload : workloads.values()) if (workload.status() == ResultStatus.FAIL) return ResultStatus.FAIL;
        return ResultStatus.PASS;
    }
    /** Existing execution outcome, kept distinct from the configured performance verdict. */
    public String executionStatus() {
        if (preparationFailed()) return "ERROR";
        if (runtimeErrorCount() > 0L) return "ERROR";
        for (LoadRunResult workload : workloads.values()) if ("ERROR".equals(workload.executionStatus())) return "ERROR";
        return "PASS";
    }
    public String performanceVerdict() {
        boolean configured = thresholdsConfigured();
        boolean notMet = !thresholds.results().isEmpty() && "NOT_MET".equals(thresholds.status());
        boolean notEvaluated = !thresholds.results().isEmpty() && "NOT_EVALUATED".equals(thresholds.status());
        boolean evaluated = !thresholds.results().isEmpty() && "MET".equals(thresholds.status());
        for (LoadRunResult workload : workloads.values()) {
            if (!workload.thresholdsConfigured()) continue;
            String verdict = workload.performanceVerdict();
            configured = true;
            if ("NOT_MET".equals(verdict)) notMet = true;
            else if ("NOT_EVALUATED".equals(verdict)) notEvaluated = true;
            else if ("MET".equals(verdict)) evaluated = true;
        }
        if (!configured) return "NOT_EVALUATED";
        if (notMet) return "NOT_MET";
        if (notEvaluated) return "NOT_EVALUATED";
        return evaluated ? "MET" : "NOT_EVALUATED";
    }
    private boolean thresholdsConfigured() {
        if (!thresholds.results().isEmpty()) return true;
        for (LoadRunResult workload : workloads.values()) if (workload.thresholdsConfigured()) return true;
        return false;
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
        Map<String, Object> combined = new LinkedHashMap<String, Object>(resources);
        if (value != null) combined.putAll(value);
        return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence, combined, workloads);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", Version.LOAD_SUMMARY_SCHEMA);
        result.put("status", status().name()); result.put("executionStatus", executionStatus());
        result.put("performanceVerdict", performanceVerdict()); result.put("exitCode", exitCode());
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

    /** Builds the Worker/Server control-plane projection without copying full metrics or evidence maps. */
    public Map<String, Object> toTerminalSummary(Map<String, Object> configuration) {
        return toTerminalSummary(configuration, "PENDING");
    }

    /** Builds the compact projection with artifact publication kept independent from execution outcome. */
    public Map<String, Object> toTerminalSummary(Map<String, Object> configuration, String reportAvailability) {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        long durationMs = Math.max(0L, Duration.between(startedAt, endedAt).toMillis());
        source.put("executionId", runId); source.put("runId", runId); source.put("status", status().name());
        source.put("exitCode", exitCode()); source.put("durationMs", durationMs);
        source.put("startedAt", startedAt.toString()); source.put("endedAt", endedAt.toString());
        source.put("executionStatus", executionStatus()); source.put("performanceVerdict", performanceVerdict());
        source.put("metrics", metrics.values());
        source.put("thresholds", thresholds.toMap());
        Map<String, Object> workloadSummaries = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, LoadRunResult> entry : workloads.entrySet()) {
            LoadRunResult workload = entry.getValue();
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("status", workload.status().name()); summary.put("executionStatus", workload.executionStatus());
            summary.put("performanceVerdict", workload.performanceVerdict()); summary.put("metrics", workload.metrics().values());
            workloadSummaries.put(entry.getKey(), summary);
        }
        source.put("workloads", workloadSummaries);
        source.put("resources", resources);
        source.put("evidence", evidence);
        source.put("diagnostics", terminalDiagnostics());
        source.put("metricCoverage", terminalMetricCoverage(durationMs));
        Map<String, Object> completeness = new LinkedHashMap<String, Object>();
        completeness.put("state", "OMITTED"); completeness.put("truncated", Boolean.TRUE);
        Map<String, Object> omitted = new LinkedHashMap<String, Object>();
        omitted.put("timeSeriesBuckets", metrics.buckets().size());
        Object failureSamples = metrics.value("failureDiagnostics");
        omitted.put("failureSamples", failureSamples instanceof List ? ((List<?>) failureSamples).size() : 0);
        omitted.put("workloads", workloads.size()); omitted.put("thresholds", thresholds.results().size());
        Object evidenceCount = evidence.get("count");
        omitted.put("evidenceItems", evidenceCount instanceof Number ? ((Number) evidenceCount).intValue() : 0);
        completeness.put("omittedCounts", omitted); completeness.put("detailSource", "load-summary.json");
        source.put("detailCompleteness", completeness);
        Map<String, Object> archive = new LinkedHashMap<String, Object>();
        Map<String, Object> paths = new LinkedHashMap<String, Object>();
        paths.put("loadSummaryJson", "load/" + runId + "/load-summary.json");
        paths.put("loadSummaryYaml", "load/" + runId + "/load-summary.yaml");
        paths.put("report", "load/" + runId + "/report/index.html");
        String archiveState = "FAILED".equals(reportAvailability) ? "FAILED" : "PENDING";
        archive.put("paths", paths); archive.put("status", archiveState); archive.put("reportAvailability", archiveState);
        if ("FAILED".equals(archiveState)) archive.put("omissionReason", "LOAD_REPORT_WRITE_FAILED");
        source.put("archive", archive); source.put("reportProduced", Boolean.TRUE);
        if (configuration != null) source.put("configuration", configuration);
        return LoadTerminalResult.compact(source);
    }

    private Map<String, Object> terminalDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Object primary = metrics.value("primaryRuntimeError");
        if (primary instanceof Map) result.put("primaryError", primary);
        Object classifications = metrics.value("runtimeErrorClassifications");
        if (classifications instanceof Map) result.put("classifications", classifications);
        return result;
    }

    private Map<String, Object> terminalMetricCoverage(long durationMs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("loadMetricsBucketCapacity", metrics.value("timeSeriesBucketCapacity"));
        result.put("loadMetricsBucketCount", metrics.buckets().size());
        result.put("loadMetricsBucketWidthMs", 1000L);
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (String key : metrics.buckets().keySet()) {
            try { long at = Long.parseLong(key); first = Math.min(first, at); last = Math.max(last, at); }
            catch (NumberFormatException ignored) { }
        }
        if (first != Long.MAX_VALUE && last != Long.MIN_VALUE) {
            result.put("observedBucketStartEpochMs", first); result.put("observedBucketEndEpochMs", last + 1000L);
            result.put("observedBucketCoverageMs", Math.max(0L, last - first + 1000L));
        }
        int capacity = ((Number) metrics.value("timeSeriesBucketCapacity")).intValue();
        result.put("runDurationMs", durationMs); result.put("fullDurationInRollingWindow", durationMs <= capacity * 1000L);
        Object windowValue = metrics.value("windowMetrics");
        if (windowValue instanceof Map) {
            Object seriesValue = ((Map<?, ?>) windowValue).get("timeSeries");
            if (seriesValue instanceof Map) {
                Map<?, ?> series = (Map<?, ?>) seriesValue;
                result.put("windowSeriesCapacity", series.get("capacity"));
                result.put("windowSeriesBinWidthMs", series.get("bucketDurationMs"));
                result.put("windowSeriesCoverage", series.get("coverage"));
            }
            result.put("windowMetricsSchemaVersion", ((Map<?, ?>) windowValue).get("schemaVersion"));
        }
        return result;
    }

    private Map<String, Object> workloadMaps() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, LoadRunResult> entry : workloads.entrySet()) {
            LoadRunResult value = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("status", value.status().name());
            item.put("executionStatus", value.executionStatus());
            item.put("performanceVerdict", value.performanceVerdict());
            if (value.scenario().workload().mixed()) item.put("mix", value.resources().get("mix"));
            else {
                Map<String, Object> target = new LinkedHashMap<String, Object>();
                target.put("type", value.scenario().targetType()); target.put("id", value.scenario().targetId());
                item.put("target", target);
            }
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
    private boolean preparationFailed() {
        Object value = resources.get("preparation");
        return value instanceof Map && "PREPARATION_FAILED".equals(((Map<?, ?>) value).get("status"));
    }
}
