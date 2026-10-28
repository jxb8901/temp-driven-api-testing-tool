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
    private final boolean cancelled;

    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics) {
        this(runId, scenario, startedAt, endedAt, metrics, LoadThresholdSummary.empty(),
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                Collections.<String, LoadRunResult>emptyMap());
    }
    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence) {
        this(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence,
                Collections.<String, Object>emptyMap(), Collections.<String, LoadRunResult>emptyMap(), false);
    }
    public LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                         LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence,
                         Map<String, Object> resources) {
        this(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence, resources,
                Collections.<String, LoadRunResult>emptyMap(), false);
    }
    LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                  LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence,
                  Map<String, Object> resources, Map<String, LoadRunResult> workloads) {
        this(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence, resources, workloads, false);
    }
    private LoadRunResult(String runId, LoadScenario scenario, Instant startedAt, Instant endedAt,
                          LoadMetricsSnapshot metrics, LoadThresholdSummary thresholds, Map<String, Object> evidence,
                          Map<String, Object> resources, Map<String, LoadRunResult> workloads, boolean cancelled) {
        this.runId = runId; this.scenario = scenario; this.startedAt = startedAt; this.endedAt = endedAt;
        this.metrics = metrics; this.thresholds = thresholds == null ? LoadThresholdSummary.empty() : thresholds;
        this.evidence = immutable(evidence); this.resources = immutable(resources);
        this.workloads = workloads == null || workloads.isEmpty()
                ? Collections.<String, LoadRunResult>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, LoadRunResult>(workloads));
        this.cancelled = cancelled;
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
    public boolean cancelled() { return cancelled; }

    public ResultStatus status() {
        if (preparationFailed()) return ResultStatus.ERROR;
        if (accountingFailed()) return ResultStatus.ERROR;
        if (runtimeErrorCount() > 0L) return ResultStatus.ERROR;
        for (LoadRunResult workload : workloads.values()) if (workload.status() == ResultStatus.ERROR) return ResultStatus.ERROR;
        if ("NOT_MET".equals(thresholds.status())) return ResultStatus.FAIL;
        for (LoadRunResult workload : workloads.values()) if (workload.status() == ResultStatus.FAIL) return ResultStatus.FAIL;
        return ResultStatus.PASS;
    }
    /** Existing execution outcome, kept distinct from the configured performance verdict. */
    public String executionStatus() {
        if (cancelled) return "CANCELLED";
        if (preparationFailed()) return "ERROR";
        if (accountingFailed()) return "ERROR";
        if (runtimeErrorCount() > 0L) return "ERROR";
        for (LoadRunResult workload : workloads.values()) if ("ERROR".equals(workload.executionStatus())) return "ERROR";
        return "PASS";
    }
    public String performanceVerdict() {
        if (cancelled) return "NOT_EVALUATED";
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
    public int exitCode() { return cancelled ? 143 : ResultAggregator.exitCode(status()); }
    public boolean passed() { return !cancelled && status() == ResultStatus.PASS; }
    public LoadRunResult withThresholds(LoadThresholdSummary value) {
        return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, cancelled ? LoadThresholdSummary.empty() : value, evidence, resources, workloads, cancelled);
    }
    public LoadRunResult withEvidence(Map<String, Object> value) {
        return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, thresholds, value, resources, workloads, cancelled);
    }
    public LoadRunResult withResources(Map<String, Object> value) {
        Map<String, Object> combined = new LinkedHashMap<String, Object>(resources);
        if (value != null) combined.putAll(value);
        return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, thresholds, evidence, combined, workloads, cancelled);
    }
    public LoadRunResult withCancelled() {
        if (cancelled) return this;
        Map<String, LoadRunResult> cancelledWorkloads = new LinkedHashMap<String, LoadRunResult>();
        workloads.forEach((id, workload) -> cancelledWorkloads.put(id, workload.withCancelled()));
        return new LoadRunResult(runId, scenario, startedAt, endedAt, metrics, LoadThresholdSummary.empty(), evidence,
                resources, cancelledWorkloads, true);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", Version.LOAD_SUMMARY_SCHEMA);
        result.put("status", cancelled ? "CANCELLED" : status().name()); result.put("executionStatus", executionStatus());
        result.put("performanceVerdict", performanceVerdict()); result.put("exitCode", exitCode());
        result.put("runId", runId); result.put("startedAt", startedAt.toString()); result.put("endedAt", endedAt.toString());
        result.put("durationMs", Math.max(0L, Duration.between(startedAt, endedAt).toMillis()));
        Map<String, Object> scenarioSummary = scenario.toSummaryMap();
        if (scenario.randomizedThinkTime()) scenarioSummary.put("effectiveSeed", Long.valueOf(LoadRandomization.effectiveSeed(scenario, runId)));
        result.put("scenario", scenarioSummary); result.put("timing", timing());
        Map<String, Object> metricMap = metrics.toMap();
        metricMap.put("accounting", accountingSummary());
        result.put("metrics", metricMap); result.put("thresholds", thresholds.toMap());
        if (!workloads.isEmpty()) result.put("workloads", workloadMaps());
        result.put("resources", resources);
        if (!evidence.isEmpty()) result.put("evidence", evidence);
        result.put("report", "report/index.html");
        return result;
    }

    /** Full direct Engine/CLI result with bounded terminal diagnostics and coverage metadata. */
    public Map<String, Object> toDetailedMap(String reportAvailability) {
        Map<String, Object> result = toMap();
        long durationMs = Math.max(0L, Duration.between(startedAt, endedAt).toMillis());
        result.put("diagnostics", terminalDiagnostics(reportAvailability));
        result.put("metricCoverage", terminalMetricCoverage(durationMs));
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
        source.put("executionId", runId); source.put("runId", runId); source.put("status", cancelled ? "CANCELLED" : status().name());
        source.put("exitCode", exitCode()); source.put("durationMs", durationMs);
        source.put("startedAt", startedAt.toString()); source.put("endedAt", endedAt.toString());
        source.put("executionStatus", executionStatus()); source.put("performanceVerdict", performanceVerdict());
        Map<String, Object> terminalMetrics = new LinkedHashMap<String, Object>(metrics.values());
        terminalMetrics.put("accounting", accountingSummary());
        source.put("metrics", terminalMetrics);
        source.put("thresholds", thresholds.toMap());
        Map<String, Object> workloadSummaries = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, LoadRunResult> entry : workloads.entrySet()) {
            LoadRunResult workload = entry.getValue();
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("status", workload.cancelled() ? "CANCELLED" : workload.status().name()); summary.put("executionStatus", workload.executionStatus());
            summary.put("performanceVerdict", workload.performanceVerdict()); summary.put("metrics", workload.metrics().values());
            workloadSummaries.put(entry.getKey(), summary);
        }
        source.put("workloads", workloadSummaries);
        source.put("resources", resources);
        source.put("evidence", evidence);
        source.put("diagnostics", terminalDiagnostics(reportAvailability));
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
        boolean reportProduced = !"FAILED".equals(reportAvailability) && !"OMITTED".equals(reportAvailability);
        Map<String, Object> paths = new LinkedHashMap<String, Object>();
        if (reportProduced) {
            paths.put("loadSummaryJson", "load/" + runId + "/load-summary.json");
            paths.put("loadSummaryYaml", "load/" + runId + "/load-summary.yaml");
            paths.put("report", "load/" + runId + "/report/index.html");
            paths.put("reportManifest", "load/" + runId + "/load-report-manifest.json");
        }
        String archiveState = reportProduced ? "PENDING" : "FAILED";
        archive.put("paths", paths); archive.put("status", archiveState); archive.put("reportAvailability", archiveState);
        if (!reportProduced) archive.put("omissionReason", "LOAD_REPORT_WRITE_FAILED");
        source.put("archive", archive); source.put("reportProduced", reportProduced);
        if (configuration != null) source.put("configuration", configuration);
        return LoadTerminalResult.compact(source);
    }

    private Map<String, Object> terminalDiagnostics(String reportAvailability) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Object primary = metrics.value("primaryRuntimeError");
        if (primary instanceof Map) result.put("primaryError", primary);
        else {
            Map<String, Object> preparation = preparationFailureDiagnostic();
            if (preparation != null) result.put("primaryError", preparation);
        }
        Object classifications = metrics.value("runtimeErrorClassifications");
        if (classifications instanceof Map) result.put("classifications", classifications);
        List<Map<String, Object>> secondary = new ArrayList<Map<String, Object>>();
        String accountingState = accountingState();
        if ("FAILED".equals(accountingState) || "PARTIAL".equals(accountingState)) {
            String code = "FAILED".equals(accountingState)
                    ? "LOAD_ACCOUNTING_INVARIANT_FAILED" : "LOAD_ACCOUNTING_INCOMPLETE";
            secondary.add(att.server.api.LimitDiagnostic.create(code,
                    "LOAD metrics did not reach a fully reconciled clean drain (accounting state: " + accountingState + ").",
                    "load.metrics.accounting", null, null, "engine.loadMetrics",
                    att.server.api.LimitOutcome.EXECUTION_ERROR, "metrics",
                    "Inspect the bounded accounting violations and the latest verified checkpoint before interpreting throughput.", null));
        }
        Map<String, Object> evidenceNotice = evidenceQuotaNotice();
        if (evidenceNotice != null) secondary.add(evidenceNotice);
        for (Map<String, Object> notice : preflightNotices()) if (secondary.size() < 16) secondary.add(notice);
        if ("FAILED".equals(reportAvailability)) {
            secondary.add(att.server.api.LimitDiagnostic.create("LOAD_REPORT_WRITE_FAILED",
                    "LOAD execution completed, but report artifacts could not be published.", null, null, null,
                    "engine.loadReport", att.server.api.LimitOutcome.ARCHIVE_FAILED, "publication",
                    "Retry report publication when storage is available; reduce detailed outputs if a report budget was exceeded.", null));
        }
        if (!secondary.isEmpty()) result.put("secondaryNotices", secondary);
        return result;
    }

    private boolean accountingFailed() {
        String state = accountingState();
        return "FAILED".equals(state) || "PARTIAL".equals(state);
    }

    private String accountingState() {
        Object state = accountingSummary().get("state");
        return state instanceof String ? (String) state : "IN_PROGRESS";
    }
    public String accountingStatus() { return accountingState(); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> accountingSummary() {
        Object raw = metrics.value("accounting");
        Map<String, Object> result = raw instanceof Map
                ? new LinkedHashMap<String, Object>((Map<String, Object>) raw)
                : new LinkedHashMap<String, Object>();
        if (!workloads.isEmpty()) {
            Map<String, Map<String, Object>> childMetrics = new LinkedHashMap<String, Map<String, Object>>();
            boolean childInvalid = false;
            for (Map.Entry<String, LoadRunResult> entry : workloads.entrySet()) {
                childMetrics.put(entry.getKey(), entry.getValue().metrics().values());
                String childState = entry.getValue().accountingState();
                if ("FAILED".equals(childState) || "PARTIAL".equals(childState)) childInvalid = true;
            }
            Map<String, Object> aggregate = LoadMetricsAccounting.compareWorkloads(metrics.values(), childMetrics);
            result.put("workloadAggregate", aggregate);
            if (childInvalid || "FAILED".equals(aggregate.get("state"))) result.put("state", "FAILED");
        }
        if (!result.containsKey("state")) result.put("state", "IN_PROGRESS");
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> preflightNotices() {
        Object raw = resources.get("preflight");
        if (!(raw instanceof Map)) return Collections.emptyList();
        Object values = ((Map<String, Object>) raw).get("warnings");
        if (!(values instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object value : (List<?>) values) {
            if (!(value instanceof Map)) continue;
            Map<?, ?> warning = (Map<?, ?>) value;
            String code = warning.get("code") instanceof String ? (String) warning.get("code") : "LOAD_LONG_RUN_PREFLIGHT";
            String message = warning.get("message") instanceof String ? (String) warning.get("message") : "Long-duration LOAD preflight reported a storage or evidence risk.";
            result.add(att.server.api.LimitDiagnostic.create(code, message, null, null, null,
                    "engine.loadPreflight", null, "preflight",
                    "Review the preflight summary in load-summary.json before starting a long run.", null));
            if (result.size() >= 8) break;
        }
        return result;
    }

    private Map<String, Object> preparationFailureDiagnostic() {
        Object raw = resources.get("preparation");
        if (!(raw instanceof Map) || !"PREPARATION_FAILED".equals(((Map<?, ?>) raw).get("status"))) return null;
        Map<?, ?> preparation = (Map<?, ?>) raw;
        String failureCode = preparation.get("failureCode") instanceof String
                ? (String) preparation.get("failureCode") : "";
        if ("PREPARATION_LIMIT_EXCEEDED".equals(failureCode)) {
            Object configured = preparation.get("perRunConnectionBudget");
            Object observed = preparation.get("initialConnections");
            return att.server.api.LimitDiagnostic.create("LOAD_RESOURCE_PREPARATION_LIMIT",
                    "LOAD resource preparation exceeded its per-worker initial connection budget.",
                    "load.initialConnectionsPerWorker", configured instanceof Number ? (Number) configured : null,
                    observed instanceof Number ? (Number) observed : null, "engine.loadPreparation",
                    att.server.api.LimitOutcome.EXECUTION_ERROR, "preparation",
                    "Reduce helper minimum-ready connections or distribute work across additional Workers.", null);
        }
        if ("PREPARATION_TIMEOUT".equals(failureCode) || "PREPARATION_BUDGET_TIMEOUT".equals(failureCode)) {
            Object configured = preparation.get("preparationDeadlineMs");
            Object observed = preparation.get("preparationMs");
            return att.server.api.LimitDiagnostic.create("LOAD_PREPARATION_TIMEOUT",
                    "LOAD resource preparation did not complete before its deadline.",
                    "load.preparationDeadlineMs", configured instanceof Number ? (Number) configured : null,
                    observed instanceof Number ? (Number) observed : null, "engine.loadPreparation",
                    att.server.api.LimitOutcome.EXECUTION_ERROR, "preparation",
                    "Reduce helper readiness work or resolve slow connection setup before changing the deadline.", null);
        }
        return att.server.api.LimitDiagnostic.create("LOAD_RESOURCE_PREPARATION_FAILED",
                "LOAD resources could not reach readiness before execution started.", null, null, null,
                "engine.loadPreparation", att.server.api.LimitOutcome.EXECUTION_ERROR, "preparation",
                "Check the bounded helper readiness diagnostics and resolve connectivity or resource setup failures.", null);
    }

    private Map<String, Object> evidenceQuotaNotice() {
        long skippedSuccess = number(evidence.get("skippedSuccessQuotaCount"));
        long skippedFailure = number(evidence.get("skippedFailureQuotaCount"));
        long skippedUnclassified = number(evidence.get("skippedUnclassifiedQuotaCount"));
        long skipped = skippedSuccess > Long.MAX_VALUE - skippedFailure ? Long.MAX_VALUE : skippedSuccess + skippedFailure;
        skipped = skipped > Long.MAX_VALUE - skippedUnclassified ? Long.MAX_VALUE : skipped + skippedUnclassified;
        if (skipped <= 0L) return null;
        Object policyValue = evidence.get("policy");
        boolean legacyCombined = Boolean.TRUE.equals(evidence.get("combinedQuotaExceeded"))
                || policyValue instanceof Map && "legacyCombined".equals(((Map<?, ?>) policyValue).get("quotaMode"));
        String limitName = legacyCombined ? "load.evidence.maxSamples"
                : skippedSuccess > 0L && skippedFailure > 0L ? "load.evidence.sampleQuota"
                : skippedFailure > 0L ? "load.evidence.maxFailureSamples" : "load.evidence.maxSuccessSamples";
        return att.server.api.LimitDiagnostic.create("ATT-LOAD-EVIDENCE-QUOTA",
                "Some iteration evidence was not retained after its configured quota was reached; aggregate metrics remain complete.",
                limitName, null, skipped, "engine.loadEvidence", att.server.api.LimitOutcome.DATA_TRUNCATED,
                "evidence", "Reduce retained evidence or use bounded sampling; aggregate counters remain complete.", null);
    }

    private static long number(Object value) {
        return value instanceof Number ? Math.max(0L, ((Number) value).longValue()) : 0L;
    }

    private Map<String, Object> terminalMetricCoverage(long durationMs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("loadMetricsBucketCapacity", metrics.value("timeSeriesBucketCapacity"));
        result.put("loadMetricsBucketCount", metrics.buckets().size());
        result.put("loadMetricsBucketWidthMs", 1000L);
        Object evicted = metrics.value("timeSeriesBucketsEvicted");
        result.put("evictedBucketCount", evicted instanceof Number ? evicted : 0L);
        if (evicted instanceof Number && ((Number) evicted).longValue() > 0L) result.put("outcome", "METRICS_SAMPLED");
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
            item.put("status", value.cancelled() ? "CANCELLED" : value.status().name());
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
