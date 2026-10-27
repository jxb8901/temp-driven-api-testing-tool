package att.server.api;

import att.server.api.internal.DiagnosticSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Versioned, bounded LOAD result carried by the Worker and persisted by the Server. */
public final class LoadTerminalResult {
    public static final String SCHEMA_VERSION = "att-load-terminal-result/v1";
    public static final int MAX_JOURNAL_EVENT_BYTES = 65536;
    /** The compact summary budget leaves room for OperationResult and terminal-event envelopes. */
    public static final int MAX_SUMMARY_BYTES = 32 * 1024;
    public static final int MAX_WORKLOADS = 8;
    public static final int MAX_THRESHOLDS = 8;
    public static final int MAX_CLASSIFICATIONS = 8;
    private static final int MAX_TEXT = 512;
    private static final ObjectMapper JSON = new ObjectMapper();

    private LoadTerminalResult() { }

    public static boolean isSummary(Object value) {
        return value instanceof Map && SCHEMA_VERSION.equals(((Map<?, ?>) value).get("schemaVersion"));
    }

    /** Projects a small source map. Only allowlisted scalar fields and bounded previews survive. */
    public static Map<String, Object> compact(Map<?, ?> source) {
        Map<?, ?> input = source == null ? Collections.emptyMap() : source;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", SCHEMA_VERSION);

        Map<String, Object> execution = new LinkedHashMap<String, Object>();
        putText(execution, "executionId", input.get("executionId"), 128);
        putText(execution, "runId", input.get("runId"), 128);
        putText(execution, "status", input.get("status"), 24);
        putNumber(execution, "exitCode", input.get("exitCode"));
        putNumber(execution, "durationMs", input.get("durationMs"));
        putText(execution, "startedAt", input.get("startedAt"), 40);
        putText(execution, "endedAt", input.get("endedAt"), 40);
        result.put("execution", execution);

        Map<String, Object> performance = new LinkedHashMap<String, Object>();
        String executionStatus = text(input.get("executionStatus"), 24);
        if (executionStatus.isEmpty()) executionStatus = text(execution.get("status"), 24);
        putText(performance, "executionStatus", executionStatus, 24);
        String performanceVerdict = text(input.get("performanceVerdict"), 24);
        putText(performance, "verdict", performanceVerdict.isEmpty() ? "NOT_EVALUATED" : performanceVerdict, 24);
        result.put("performance", performance);

        Map<?, ?> metricsSource = map(input.get("metrics"));
        Map<String, Object> metrics = metricSummary(metricsSource);
        result.put("metrics", metrics);

        Map<?, ?> thresholdsSource = map(input.get("thresholds"));
        result.put("thresholds", thresholdSummary(thresholdsSource));

        Map<?, ?> workloadsSource = map(input.get("workloads"));
        result.put("workloads", workloadSummary(workloadsSource));

        Map<?, ?> diagnosticsSource = map(input.get("diagnostics"));
        Map<String, Object> diagnostics = diagnostics(diagnosticsSource, metricsSource,
                metrics, String.valueOf(execution.getOrDefault("status", "UNKNOWN")),
                map(input.get("resources")));
        result.put("diagnostics", diagnostics);

        Map<?, ?> coverageSource = map(input.get("metricCoverage"));
        result.put("metricCoverage", coverageSource.isEmpty()
                ? coverage(metricsSource, input.get("durationMs")) : boundedCoverage(coverageSource));

        Map<?, ?> completenessSource = map(input.get("detailCompleteness"));
        result.put("detailCompleteness", completenessSource.isEmpty()
                ? completeness(metricsSource, workloadsSource, thresholdsSource, map(input.get("evidence")))
                : boundedCompleteness(completenessSource));

        result.put("archive", archive(map(input.get("archive")), input));
        Map<?, ?> configuration = map(input.get("configuration"));
        if (!configuration.isEmpty()) {
            try { result.put("configuration", ConfigurationSnapshot.copy(configuration)); }
            catch (IllegalArgumentException invalid) { result.put("configurationOmitted", Boolean.TRUE); }
        }

        enforceBudget(result);
        return result;
    }

    /**
     * Converts a Worker result to the v1 LOAD shape. This also protects the Server from
     * older Workers that still send the detailed att-load-summary/v1.2 map.
     */
    public static Map<String, Object> projectOperationResult(Map<?, ?> raw, String eventStatus, int eventExitCode) {
        Map<?, ?> original = raw == null ? Collections.emptyMap() : raw;
        Map<?, ?> summary = map(original.get("summary"));
        Map<String, Object> source = isSummary(summary) ? sourceFromCompact(summary) : sourceFromLegacy(original, summary);
        source.put("status", eventStatus == null ? original.get("status") : eventStatus);
        source.put("exitCode", eventExitCode);
        if (original.get("executionId") instanceof String && source.get("executionId") == null)
            source.put("executionId", original.get("executionId"));
        if (original.get("durationMs") instanceof Number && source.get("durationMs") == null)
            source.put("durationMs", original.get("durationMs"));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        putText(result, "executionId", source.get("executionId"), 128);
        putText(result, "status", source.get("status"), 24);
        putNumber(result, "exitCode", source.get("exitCode"));
        putNumber(result, "durationMs", source.get("durationMs"));
        result.put("diagnostics", outerDiagnostics(map(source.get("diagnostics")), String.valueOf(source.get("status"))));
        result.put("summary", compact(source));
        return result;
    }

    private static Map<String, Object> sourceFromCompact(Map<?, ?> summary) {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        Map<?, ?> execution = map(summary.get("execution"));
        Map<?, ?> performance = map(summary.get("performance"));
        for (String key : new String[] {"executionId", "runId", "status", "exitCode", "durationMs", "startedAt", "endedAt"})
            if (execution.containsKey(key)) source.put(key, execution.get(key));
        if (performance.containsKey("executionStatus")) source.put("executionStatus", performance.get("executionStatus"));
        if (performance.containsKey("verdict")) source.put("performanceVerdict", performance.get("verdict"));
        source.put("metrics", summary.get("metrics"));
        source.put("thresholds", summary.get("thresholds"));
        source.put("workloads", summary.get("workloads"));
        source.put("diagnostics", summary.get("diagnostics"));
        source.put("metricCoverage", summary.get("metricCoverage"));
        source.put("detailCompleteness", summary.get("detailCompleteness"));
        source.put("archive", summary.get("archive"));
        source.put("configuration", summary.get("configuration"));
        return source;
    }

    private static Map<String, Object> sourceFromLegacy(Map<?, ?> original, Map<?, ?> summary) {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("executionId", original.get("executionId"));
        source.put("runId", summary.get("runId"));
        source.put("status", original.get("status"));
        source.put("exitCode", original.get("exitCode"));
        source.put("durationMs", original.get("durationMs"));
        source.put("startedAt", summary.get("startedAt"));
        source.put("endedAt", summary.get("endedAt"));
        source.put("executionStatus", summary.get("executionStatus"));
        source.put("performanceVerdict", summary.get("performanceVerdict"));
        source.put("metrics", summary.get("metrics"));
        source.put("thresholds", summary.get("thresholds"));
        source.put("workloads", summary.get("workloads"));
        source.put("resources", summary.get("resources"));
        source.put("evidence", summary.get("evidence"));
        source.put("configuration", summary.get("configuration"));

        Map<String, Object> diagnostic = new LinkedHashMap<String, Object>();
        Map<?, ?> metrics = map(summary.get("metrics"));
        Object primary = metrics.get("primaryRuntimeError");
        if (primary instanceof Map) diagnostic.put("primaryError", primary);
        Object runtimeClasses = metrics.get("runtimeErrorClassifications");
        if (runtimeClasses instanceof Map) diagnostic.put("classifications", runtimeClasses);
        else if (metrics.get("errorClassifications") instanceof Map) diagnostic.put("classifications", metrics.get("errorClassifications"));
        diagnostic.put("outer", original.get("diagnostics"));
        diagnostic.put("failureDiagnostics", metrics.get("failureDiagnostics"));
        source.put("diagnostics", diagnostic);

        Map<String, Object> archive = new LinkedHashMap<String, Object>();
        Object runId = summary.get("runId");
        String run = text(runId, 128);
        if (!run.isEmpty()) {
            Map<String, Object> paths = new LinkedHashMap<String, Object>();
            paths.put("loadSummaryJson", "load/" + run + "/load-summary.json");
            paths.put("loadSummaryYaml", "load/" + run + "/load-summary.yaml");
            Object report = summary.get("report");
            paths.put("report", "load/" + run + "/" + safeRelative(report, "report/index.html"));
            archive.put("paths", paths);
            archive.put("reportAvailability", "PENDING");
            archive.put("status", "PENDING");
        } else {
            archive.put("status", "OMITTED");
            archive.put("reportAvailability", "OMITTED");
        }
        source.put("archive", archive);
        return source;
    }

    private static Map<String, Object> metricSummary(Map<?, ?> source) {
        String[] counters = {"scheduled", "started", "completed", "success", "failure", "runtimeError", "dropped",
                "measuredScheduled", "measuredStarted", "measuredCompleted", "measuredSuccess", "measuredFailure",
                "measuredRuntimeError", "measuredDropped", "latencyObservationCount", "latencySampleCount",
                "maxInFlight", "maxActiveVus", "configuredUsers", "configuredMaxConcurrent"};
        String[] rates = {"achievedArrivalRate", "measuredAchievedArrivalRate", "completedThroughput",
                "measuredCompletedThroughput", "successfulThroughput", "p50Ms", "p90Ms", "p95Ms", "p99Ms",
                "latencyMeanMs", "latencyMaxMs", "runtimeErrorRate", "sutErrorRate", "totalErrorRate"};
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String key : counters) putNumber(out, key, source.get(key));
        for (String key : rates) putNumber(out, key, source.get(key));
        return out;
    }

    private static Map<String, Object> thresholdSummary(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String verdict = text(source.get("performanceVerdict"), 24);
        if (verdict.isEmpty()) verdict = text(source.get("status"), 24);
        if (verdict.isEmpty()) verdict = text(source.get("verdict"), 24);
        out.put("verdict", verdict.isEmpty() ? "NOT_EVALUATED" : verdict);
        Object raw = source.get("results") instanceof Iterable ? source.get("results") : source.get("items");
        int total = raw instanceof List ? ((List<?>) raw).size() : number(source.get("total"), 0).intValue();
        total = Math.max(total, number(source.get("total"), total).intValue());
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (raw instanceof Iterable) for (Object item : (Iterable<?>) raw) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) item;
            if (items.size() >= MAX_THRESHOLDS) continue;
            Map<String, Object> bounded = new LinkedHashMap<String, Object>();
            putText(bounded, "name", row.get("name"), 64);
            putText(bounded, "status", row.get("status"), 24);
            putText(bounded, "actual", row.get("actual"), 48);
            if (!bounded.isEmpty()) items.add(bounded);
        }
        int omitted = Math.max(number(source.get("omittedCount"), 0).intValue(), total - items.size());
        out.put("total", total); out.put("items", items); out.put("omittedCount", Math.max(0, omitted));
        out.put("passed", statusCount(raw, "PASS", number(source.get("passed"), 0).intValue()));
        out.put("failed", statusCount(raw, "FAIL", number(source.get("failed"), 0).intValue()));
        out.put("notEvaluated", statusCount(raw, "NOT_EVALUATED", number(source.get("notEvaluated"), 0).intValue()));
        return out;
    }

    private static Map<String, Object> workloadSummary(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        Object preview = source.get("items");
        if (preview instanceof Iterable) {
            int total = number(source.get("total"), iterableSize(preview)).intValue();
            for (Object value : (Iterable<?>) preview) {
                if (items.size() >= MAX_WORKLOADS || !(value instanceof Map)) continue;
                Map<?, ?> workload = (Map<?, ?>) value;
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                putText(item, "id", workload.get("id"), 96);
                putText(item, "status", workload.get("status"), 24);
                putText(item, "executionStatus", workload.get("executionStatus"), 24);
                putText(item, "performanceVerdict", workload.get("performanceVerdict"), 24);
                Map<?, ?> metrics = map(workload.get("metrics"));
                Map<String, Object> counters = new LinkedHashMap<String, Object>();
                for (String key : new String[] {"scheduled", "started", "completed", "success", "failure", "runtimeError", "dropped"})
                    putNumber(counters, key, metrics.get(key));
                if (!counters.isEmpty()) item.put("metrics", counters);
                items.add(item);
            }
            out.put("total", total); out.put("items", items);
            out.put("omittedCount", Math.max(number(source.get("omittedCount"), 0).intValue(), total - items.size()));
            return out;
        }
        int total = source.size();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Map)) continue;
            Map<?, ?> workload = (Map<?, ?>) entry.getValue();
            if (items.size() >= MAX_WORKLOADS) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            putText(item, "id", entry.getKey(), 96);
            putText(item, "status", workload.get("status"), 24);
            putText(item, "executionStatus", workload.get("executionStatus"), 24);
            putText(item, "performanceVerdict", workload.get("performanceVerdict"), 24);
            Map<?, ?> metrics = map(workload.get("metrics"));
            Map<String, Object> counters = new LinkedHashMap<String, Object>();
            for (String key : new String[] {"scheduled", "started", "completed", "success", "failure", "runtimeError", "dropped"})
                putNumber(counters, key, metrics.get(key));
            if (!counters.isEmpty()) item.put("metrics", counters);
            if (!item.isEmpty()) items.add(item);
        }
        out.put("total", total); out.put("items", items);
        out.put("omittedCount", Math.max(0, total - items.size()));
        return out;
    }

    private static Map<String, Object> diagnostics(Map<?, ?> source, Map<?, ?> metrics,
                                                    Map<String, Object> metricSummary, String status,
                                                    Map<?, ?> resources) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<?, ?> prep = map(map(resources.get("preparation")));
        Map<?, ?> primary = map(source.get("primaryError"));
        if (primary.isEmpty()) primary = map(metrics.get("primaryRuntimeError"));
        if (primary.isEmpty() && isFailureOutcome(status)) primary = firstRuntimeFailure(source.get("failureDiagnostics"), metrics.get("failureDiagnostics"));
        if (primary.isEmpty() && isFailureOutcome(status) && !prep.isEmpty()
                && "PREPARATION_FAILED".equals(prep.get("status"))) primary = preparationError(prep);
        if (primary.isEmpty() && isFailureOutcome(status)) primary = firstRealDiagnostic(source.get("outer"));
        if (primary.isEmpty() && "ERROR".equals(status)) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("code", "LOAD_ERROR_DIAGNOSTIC_UNAVAILABLE");
            fallback.put("summary", "LOAD ended with ERROR; the Worker did not provide a safe root-cause diagnostic.");
            primary = fallback;
        }
        Map<String, Object> boundedPrimary = diagnostic(primary);
        if (!boundedPrimary.isEmpty()) out.put("primaryError", boundedPrimary);

        Map<?, ?> classes = map(source.get("classifications"));
        String primaryCode = text(boundedPrimary.get("code"), 128);
        List<Map<String, Object>> classifications = null;
        if (classes.isEmpty() && source.get("classifications") instanceof Iterable)
            classifications = boundedClassifications((Iterable<?>) source.get("classifications"), primaryCode);
        if (classifications == null) {
            if (classes.isEmpty()) classes = map(metrics.get("runtimeErrorClassifications"));
            if (classes.isEmpty()) classes = map(metrics.get("errorClassifications"));
            classifications = classifications(classes, primaryCode);
        }
        out.put("classifications", classifications);
        int total = Math.max(classes.size(), number(source.get("classificationCount"), classes.size()).intValue());
        out.put("classificationCount", total);
        out.put("omittedClassificationCount", Math.max(0, total - classifications.size()));

        List<Map<String, Object>> warnings = new ArrayList<Map<String, Object>>();
        addTransportWarning(warnings, source.get("outer"));
        addTransportWarning(warnings, source.get("transportWarnings"));
        if (!warnings.isEmpty()) out.put("transportWarnings", warnings);
        List<Map<String, Object>> secondary = safeSecondaryNotices(source.get("secondaryNotices"));
        if (!secondary.isEmpty()) out.put("secondaryNotices", secondary);
        return out;
    }

    private static List<Map<String, Object>> safeSecondaryNotices(Object value) {
        List<Map<String, Object>> notices = new ArrayList<Map<String, Object>>();
        if (!(value instanceof Iterable)) return notices;
        for (Object item : (Iterable<?>) value) if (item instanceof Map && notices.size() < 2) {
            Map<String, Object> notice = diagnostic((Map<?, ?>) item);
            if (!notice.isEmpty()) notices.add(notice);
        }
        return notices;
    }

    private static boolean isFailureOutcome(String status) {
        return "ERROR".equals(status) || "INVALID".equals(status);
    }

    private static Map<String, Object> firstRuntimeFailure(Object first, Object second) {
        for (Object value : new Object[] {first, second}) if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) if (item instanceof Map) {
                Map<?, ?> row = (Map<?, ?>) item;
                if (!"ERROR".equals(row.get("status")) && !"INVALID".equals(row.get("status"))) continue;
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("code", row.get("code") == null ? row.get("type") : row.get("code"));
                result.put("summary", row.get("summary"));
                result.put("workloadId", row.get("workloadId"));
                for (String key : new String[] {"limitName", "configuredLimit", "actual", "observed", "usage", "limit",
                        "threshold", "scope", "outcome", "stage", "remediationHint", "artifactRef"})
                    if (row.containsKey(key)) result.put(key, row.get(key));
                return result;
            }
        }
        return Collections.emptyMap();
    }

    private static Map<String, Object> preparationError(Map<?, ?> preparation) {
        String code = text(preparation.get("failureCode"), 128);
        if (code.isEmpty()) code = "LOAD_RESOURCE_PREPARATION_FAILED";
        if ("PREPARATION_LIMIT_EXCEEDED".equals(code) || "LOAD_RESOURCE_PREPARATION_LIMIT".equals(code)) code = "LOAD_RESOURCE_PREPARATION_LIMIT";
        else if ("PREPARATION_TIMEOUT".equals(code) || "LOAD_PREPARATION_TIMEOUT".equals(code)) code = "LOAD_PREPARATION_TIMEOUT";
        else if (!code.startsWith("LOAD_") && !code.endsWith("_ERROR")) code = "LOAD_RESOURCE_PREPARATION_FAILED";
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", code);
        result.put("summary", "LOAD resource preparation failed (" + code + ").");
        if (preparation.get("failed") instanceof Number && number(preparation.get("failed"), 0).longValue() > 0L)
            result.put("failedCount", number(preparation.get("failed"), 0));
        boolean resourceLimit = "LOAD_RESOURCE_PREPARATION_LIMIT".equals(code);
        boolean deadline = "LOAD_PREPARATION_TIMEOUT".equals(code);
        if (resourceLimit || deadline) {
            String limitName = resourceLimit ? "load.initialConnectionsPerWorker" : "load.preparationDeadlineMs";
            Number configured = number(preparation.get(resourceLimit ? "perRunConnectionBudget" : "preparationDeadlineMs"),
                    resourceLimit ? 64 : 30000);
            Number observed = number(preparation.get(resourceLimit ? "initialConnections" : "preparationMs"), 0);
            result.putAll(LimitDiagnostic.create(code, String.valueOf(result.get("summary")), limitName, configured,
                    observed, "load.preparation", LimitOutcome.EXECUTION_ERROR, "preparation",
                    resourceLimit ? "Reduce the number of distinct initial DB and MQ connections per LOAD."
                            : "Check dependency readiness and reduce preparation work before extending the deadline.", null));
        }
        return result;
    }

    private static Map<String, Object> firstRealDiagnostic(Object value) {
        if (!(value instanceof Iterable)) return Collections.emptyMap();
        for (Object item : (Iterable<?>) value) if (item instanceof Map) {
            Map<String, Object> diagnostic = diagnostic((Map<?, ?>) item);
            if (!diagnostic.isEmpty() && !isTransportCode(text(diagnostic.get("code"), 128))) return diagnostic;
        }
        return Collections.emptyMap();
    }

    private static Map<String, Object> diagnostic(Map<?, ?> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Object code = source.get("code"); if (code == null) code = source.get("type");
        putText(result, "code", code, 128);
        Object summary = source.get("summary"); if (summary == null) summary = source.get("message");
        putText(result, "summary", summary, MAX_TEXT);
        putText(result, "workloadId", source.get("workloadId"), 96);
        putText(result, "phase", source.get("phase"), 24);
        putNumber(result, "failedCount", source.get("failedCount"));
        putText(result, "limitName", source.get("limitName"), 96);
        putNumber(result, "configuredLimit", source.get("configuredLimit"));
        putNumber(result, "actual", source.get("actual"));
        putNumber(result, "observed", source.get("observed"));
        putNumber(result, "usage", source.get("usage"));
        putNumber(result, "limit", source.get("limit"));
        putNumber(result, "threshold", source.get("threshold"));
        putText(result, "scope", source.get("scope"), 96);
        String outcome = text(source.get("outcome"), 32);
        if (isLimitOutcome(outcome)) result.put("outcome", outcome);
        putText(result, "stage", source.get("stage"), 96);
        putText(result, "remediationHint", DiagnosticSanitizer.redactAbsolutePaths(
                DiagnosticSanitizer.redactText(text(source.get("remediationHint"), 256))), 256);
        String artifactRef = safeRelative(source.get("artifactRef"), null);
        if (artifactRef != null) result.put("artifactRef", artifactRef);
        return result;
    }

    private static boolean isLimitOutcome(String value) {
        for (LimitOutcome outcome : LimitOutcome.values()) if (outcome.name().equals(value)) return true;
        return false;
    }

    private static List<Map<String, Object>> classifications(Map<?, ?> source, String primaryCode) {
        TreeMap<String, Long> sorted = new TreeMap<String, Long>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Number)) continue;
            sorted.put(text(entry.getKey(), 128), ((Number) entry.getValue()).longValue());
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (!primaryCode.isEmpty() && sorted.containsKey(primaryCode)) result.add(classification(primaryCode, sorted.remove(primaryCode)));
        for (Map.Entry<String, Long> entry : sorted.entrySet()) {
            if (result.size() >= MAX_CLASSIFICATIONS) break;
            result.add(classification(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static List<Map<String, Object>> boundedClassifications(Iterable<?> source, String primaryCode) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<String, Object> primary = null;
        for (Object value : source) if (value instanceof Map) {
            Map<?, ?> row = (Map<?, ?>) value;
            String code = text(row.get("code"), 128);
            Map<String, Object> item = classification(code, number(row.get("count"), 0).longValue());
            if (code.equals(primaryCode)) primary = item;
            else if (result.size() < MAX_CLASSIFICATIONS) result.add(item);
        }
        if (primary != null) result.add(0, primary);
        if (result.size() > MAX_CLASSIFICATIONS) result.remove(result.size() - 1);
        return result;
    }

    private static Map<String, Object> classification(String code, Long count) {
        Map<String, Object> row = new LinkedHashMap<String, Object>(); row.put("code", code); row.put("count", count); return row;
    }

    private static List<Map<String, Object>> outerDiagnostics(Map<?, ?> source, String status) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Map<?, ?> primary = map(source.get("primaryError"));
        if (primary.isEmpty() && "ERROR".equals(status)) primary = map(source.get("primaryRuntimeError"));
        Map<String, Object> safePrimary = diagnostic(primary);
        if (!safePrimary.isEmpty()) result.add(safePrimary);
        else if (isFailureOutcome(status)) {
            Map<String, Object> safe = firstRealDiagnostic(source.get("outer"));
            if (!safe.isEmpty()) result.add(safe);
        }
        if (result.isEmpty()) {
            Map<String, Object> safe = firstRealDiagnostic(source.get("outer"));
            if (!safe.isEmpty()) result.add(safe);
        }
        return result;
    }

    private static Map<String, Object> coverage(Map<?, ?> metrics, Object durationValue) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int capacity = number(metrics.get("timeSeriesBucketCapacity"), 4096).intValue();
        int count = number(metrics.get("timeSeriesBucketCount"), map(metrics.get("buckets")).size()).intValue();
        out.put("loadMetricsBucketCapacity", capacity); out.put("loadMetricsBucketCount", Math.max(0, count));
        out.put("loadMetricsBucketWidthMs", 1000);
        long evicted = number(metrics.get("timeSeriesBucketsEvicted"), 0).longValue();
        out.put("evictedBucketCount", Math.max(0L, evicted));
        if (evicted > 0L) out.put("outcome", LimitOutcome.METRICS_SAMPLED.name());
        List<Long> bucketTimes = new ArrayList<Long>();
        for (Object key : map(metrics.get("buckets")).keySet()) {
            try { bucketTimes.add(Long.parseLong(String.valueOf(key))); } catch (NumberFormatException ignored) { }
        }
        if (!bucketTimes.isEmpty()) {
            Collections.sort(bucketTimes);
            long start = bucketTimes.get(0), end = bucketTimes.get(bucketTimes.size() - 1) + 1000L;
            out.put("observedBucketStartEpochMs", start); out.put("observedBucketEndEpochMs", end);
            out.put("observedBucketCoverageMs", Math.max(0L, end - start));
        }
        long duration = number(durationValue, 0).longValue();
        out.put("runDurationMs", Math.max(0L, duration));
        out.put("fullDurationInRollingWindow", duration <= ((long) capacity * 1000L));
        Map<?, ?> window = map(metrics.get("windowMetrics"));
        Map<?, ?> series = map(window.get("timeSeries"));
        out.put("windowSeriesCapacity", number(series.get("capacity"), 2048).intValue());
        out.put("windowSeriesBinWidthMs", number(series.get("bucketDurationMs"), 1000).longValue());
        putText(out, "windowSeriesCoverage", series.get("coverage"), 96);
        putText(out, "windowMetricsSchemaVersion", window.get("schemaVersion"), 64);
        return out;
    }

    private static Map<String, Object> boundedCoverage(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String[] numbers = {"loadMetricsBucketCapacity", "loadMetricsBucketCount", "loadMetricsBucketWidthMs",
                "observedBucketStartEpochMs", "observedBucketEndEpochMs", "observedBucketCoverageMs", "runDurationMs",
                "windowSeriesCapacity", "windowSeriesBinWidthMs", "evictedBucketCount"};
        for (String key : numbers) putNumber(result, key, source.get(key));
        if (source.get("fullDurationInRollingWindow") instanceof Boolean) result.put("fullDurationInRollingWindow", source.get("fullDurationInRollingWindow"));
        String outcome = text(source.get("outcome"), 32);
        if (isLimitOutcome(outcome)) result.put("outcome", outcome);
        putText(result, "windowSeriesCoverage", source.get("windowSeriesCoverage"), 96);
        putText(result, "windowMetricsSchemaVersion", source.get("windowMetricsSchemaVersion"), 64);
        return result;
    }

    private static Map<String, Object> completeness(Map<?, ?> metrics, Map<?, ?> workloads,
                                                    Map<?, ?> thresholds, Map<?, ?> evidence) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("state", "OMITTED"); out.put("truncated", Boolean.TRUE);
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("timeSeriesBuckets", number(metrics.get("timeSeriesBucketCount"), map(metrics.get("buckets")).size()).intValue());
        counts.put("failureSamples", iterableSize(metrics.get("failureDiagnostics")));
        counts.put("workloads", Math.max(0, workloads.size() - MAX_WORKLOADS));
        counts.put("thresholds", Math.max(0, iterableSize(thresholds.get("results")) - MAX_THRESHOLDS));
        counts.put("evidenceItems", number(evidence.get("retainedSuccessCount"), 0).intValue()
                + number(evidence.get("retainedFailureCount"), 0).intValue());
        out.put("omittedCounts", counts);
        out.put("detailSource", "load-summary.json");
        return out;
    }

    private static Map<String, Object> boundedCompleteness(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        putText(out, "state", source.get("state"), 24);
        if (source.get("truncated") instanceof Boolean) out.put("truncated", source.get("truncated"));
        Map<?, ?> counts = map(source.get("omittedCounts"));
        Map<String, Object> bounded = new LinkedHashMap<String, Object>();
        for (String key : new String[] {"timeSeriesBuckets", "failureSamples", "workloads", "thresholds", "evidenceItems"})
            putNumber(bounded, key, counts.get(key));
        out.put("omittedCounts", bounded);
        putText(out, "detailSource", source.get("detailSource"), 64);
        return out;
    }

    private static Map<String, Object> archive(Map<?, ?> source, Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String state = text(source.get("status"), 24);
        if (!isArchiveState(state)) state = "PENDING";
        out.put("status", state);
        Map<String, Object> paths = new LinkedHashMap<String, Object>();
        Map<?, ?> supplied = map(source.get("paths"));
        for (String key : new String[] {"loadSummaryJson", "loadSummaryYaml", "report"}) {
            Object path = supplied.get(key);
            if (path != null) {
                String safe = safeRelative(path, "");
                if (!safe.isEmpty()) paths.put(key, safe);
            }
        }
        if (paths.isEmpty()) {
            Object runValue = input.get("runId");
            String run = safeSegment(runValue);
            if (!run.isEmpty() && Boolean.TRUE.equals(source.get("reportProduced"))) {
                paths.put("loadSummaryJson", "load/" + run + "/load-summary.json");
                paths.put("loadSummaryYaml", "load/" + run + "/load-summary.yaml");
                paths.put("report", "load/" + run + "/report/index.html");
            }
        }
        out.put("paths", paths);
        String report = text(source.get("reportAvailability"), 24);
        if (!isArchiveState(report)) report = paths.isEmpty() ? "OMITTED" : "PENDING";
        out.put("reportAvailability", report);
        putNumber(out, "omittedArtifactCount", source.get("omittedArtifactCount"));
        putText(out, "omissionReason", source.get("omissionReason"), 96);
        return out;
    }

    private static void enforceBudget(Map<String, Object> summary) {
        if (jsonBytes(summary) <= MAX_SUMMARY_BYTES) return;
        Map<?, ?> workloadData = map(summary.get("workloads"));
        summary.put("workloads", fields("total", workloadData.get("total"), "items", Collections.emptyList(),
                "omittedCount", workloadData.get("total")));
        if (jsonBytes(summary) <= MAX_SUMMARY_BYTES) return;
        Map<?, ?> thresholdData = map(summary.get("thresholds"));
        summary.put("thresholds", fields("verdict", thresholdData.get("verdict"), "total", thresholdData.get("total"),
                "passed", thresholdData.get("passed"), "failed", thresholdData.get("failed"),
                "notEvaluated", thresholdData.get("notEvaluated"), "items", Collections.emptyList(),
                "omittedCount", thresholdData.get("total")));
        if (jsonBytes(summary) <= MAX_SUMMARY_BYTES) return;
        Map<?, ?> diagnosticData = map(summary.get("diagnostics"));
        List<Map<String, Object>> classes = new ArrayList<Map<String, Object>>();
        Object primary = diagnosticData.get("primaryError");
        String primaryCode = text(map(primary).get("code"), 128);
        Object rawClasses = diagnosticData.get("classifications");
        if (rawClasses instanceof Iterable) for (Object item : (Iterable<?>) rawClasses) {
            if (item instanceof Map && primaryCode.equals(((Map<?, ?>) item).get("code"))) classes.add(castStringMap((Map<?, ?>) item));
        }
        Map<String, Object> shrunkDiagnostics = castStringMap(diagnosticData);
        shrunkDiagnostics.put("classifications", classes);
        int originalCount = number(diagnosticData.get("classificationCount"), 0).intValue();
        shrunkDiagnostics.put("omittedClassificationCount", Math.max(0, originalCount - classes.size()));
        shrunkDiagnostics.remove("transportWarnings");
        Object notices = shrunkDiagnostics.remove("secondaryNotices");
        int omittedNotices = iterableSize(notices);
        if (omittedNotices > 0) shrunkDiagnostics.put("omittedSecondaryNoticeCount", omittedNotices);
        summary.put("diagnostics", shrunkDiagnostics);
        if (jsonBytes(summary) <= MAX_SUMMARY_BYTES) return;
        summary.remove("configuration");
        summary.put("configurationOmitted", Boolean.TRUE);
        if (jsonBytes(summary) > MAX_SUMMARY_BYTES)
            throw new IllegalStateException("Bounded LOAD terminal summary exceeds its deterministic byte budget");
    }

    private static int statusCount(Object raw, String status, int fallback) {
        if (!(raw instanceof Iterable)) return fallback;
        int count = 0;
        for (Object item : (Iterable<?>) raw) if (item instanceof Map && status.equals(((Map<?, ?>) item).get("status"))) count++;
        return count;
    }

    private static int iterableSize(Object value) {
        if (!(value instanceof Iterable)) return 0;
        int count = 0; for (Object ignored : (Iterable<?>) value) count++; return count;
    }

    private static void addTransportWarning(List<Map<String, Object>> warnings, Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (String key : new String[] {"protocolLimit", "journalLimit"}) if (map.get(key) instanceof Map) {
                Map<String, Object> warning = diagnostic((Map<?, ?>) map.get(key));
                warning.remove("workloadId"); warning.remove("phase"); warning.remove("failedCount");
                if (!warning.isEmpty() && warnings.size() < 2) warnings.add(warning);
            }
            Object diagnostics = map.get("diagnostics");
            if (diagnostics instanceof Iterable) for (Object item : (Iterable<?>) diagnostics) if (item instanceof Map) {
                Map<String, Object> warning = diagnostic((Map<?, ?>) item);
                if (isTransportCode(text(warning.get("code"), 128)) && warnings.size() < 2) warnings.add(warning);
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) if (item instanceof Map) {
                Map<String, Object> warning = diagnostic((Map<?, ?>) item);
                if (isTransportCode(text(warning.get("code"), 128)) && warnings.size() < 2) warnings.add(warning);
            }
        }
    }

    private static boolean isTransportCode(String code) {
        return "ATT-WORKER-EVENT-TRUNCATED".equals(code) || "ATT-JOURNAL-EVENT-TRUNCATED".equals(code)
                || "ATT-SERVER-WORKER-EVENT-TOO-LARGE".equals(code);
    }

    private static String safeRelative(Object value, String fallback) {
        if (!(value instanceof String)) return fallback;
        String path = ((String) value).replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.indexOf('\0') >= 0) return fallback;
        String[] parts = path.split("/");
        for (String part : parts) if ("..".equals(part) || part.isEmpty()) return fallback;
        return truncate(path, 256);
    }

    private static String safeSegment(Object value) {
        String segment = text(value, 128);
        if (segment.isEmpty() || segment.contains("/") || segment.contains("\\") || "..".equals(segment)) return "";
        return segment;
    }

    private static boolean isArchiveState(String value) {
        return "READY".equals(value) || "PENDING".equals(value) || "ARCHIVING".equals(value)
                || "FAILED".equals(value) || "OMITTED".equals(value) || "EXPIRED".equals(value);
    }

    private static Map<?, ?> map(Object value) { return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap(); }
    private static Number number(Object value, Number fallback) { return value instanceof Number ? (Number) value : fallback; }
    private static void putNumber(Map<String, Object> target, String key, Object value) {
        if (!(value instanceof Number)) return;
        Number number = (Number) value;
        if (number instanceof java.math.BigInteger) {
            java.math.BigInteger integer = (java.math.BigInteger) number;
            if (integer.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) target.put(key, Long.MAX_VALUE);
            else if (integer.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0) target.put(key, Long.MIN_VALUE);
            else target.put(key, integer.longValue());
        } else if (number instanceof java.math.BigDecimal) {
            double valueAsDouble = number.doubleValue();
            target.put(key, Double.isFinite(valueAsDouble) ? valueAsDouble
                    : valueAsDouble < 0 ? -Double.MAX_VALUE : Double.MAX_VALUE);
        } else if (number instanceof Float || number instanceof Double) {
            double valueAsDouble = number.doubleValue();
            if (Double.isFinite(valueAsDouble)) target.put(key, valueAsDouble);
            else if (Double.isNaN(valueAsDouble)) target.put(key, 0d);
            else target.put(key, valueAsDouble < 0 ? -Double.MAX_VALUE : Double.MAX_VALUE);
        } else if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long) {
            target.put(key, number);
        } else target.put(key, number.longValue());
    }
    private static void putText(Map<String, Object> target, String key, Object value, int max) {
        if (value instanceof String) {
            String safe = DiagnosticSanitizer.redactAbsolutePaths(DiagnosticSanitizer.redactText((String) value));
            if (safe.length() > max) safe = truncate(safe, max);
            target.put(key, safe);
        }
    }
    private static String text(Object value, int max) {
        if (!(value instanceof String)) return "";
        String safe = DiagnosticSanitizer.redactAbsolutePaths(DiagnosticSanitizer.redactText((String) value));
        return safe.length() > max ? truncate(safe, max) : safe;
    }
    private static String truncate(String value, int max) {
        if (value.length() <= max) return value;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
    private static int jsonBytes(Object value) {
        try { return JSON.writeValueAsBytes(value).length; }
        catch (Exception failure) { throw new IllegalStateException("Unable to serialize compact LOAD terminal result", failure); }
    }
    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
    private static Map<String, Object> castStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) if (entry.getKey() instanceof String) result.put((String) entry.getKey(), entry.getValue());
        return result;
    }
}
