package att.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadTerminalResultTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void legacyDetailedSummaryProjectsToBoundedPostEnvelopeResultAndKeepsRealErrorFirst() throws Exception {
        Map<String,Object> summary = detailedSummary("HTTP_RESPONSE_TOO_LARGE", "root cause \"雪\"\\line\n" + "界".repeat(3000));
        List<Map<String,Object>> diagnostics = new ArrayList<>();
        diagnostics.add(fields("code", "ATT-WORKER-EVENT-TRUNCATED", "summary", "transport truncated"));
        diagnostics.add(fields("code", "ATT-JOURNAL-EVENT-TRUNCATED", "summary", "journal truncated"));
        Map<String,Object> original = fields("executionId", "run-雪", "status", "ERROR", "exitCode", 3,
                "durationMs", 3_600_000L, "paths", fields("report", "/private/tmp/report.html"),
                "diagnostics", diagnostics, "summary", summary);
        summary.put("configuration", maximumConfigurationEvidence());

        Map<String,Object> projected = LoadTerminalResult.projectOperationResult(original, "ERROR", 3);
        Map<?,?> compact = (Map<?,?>) projected.get("summary");
        Map<?,?> cause = (Map<?,?>) ((Map<?,?>) compact.get("diagnostics")).get("primaryError");
        assertEquals(LoadTerminalResult.SCHEMA_VERSION, compact.get("schemaVersion"));
        assertEquals("HTTP_RESPONSE_TOO_LARGE", cause.get("code"));
        assertTrue(String.valueOf(cause.get("summary")).contains("雪"));
        assertTrue(((List<?>) ((Map<?,?>) compact.get("diagnostics")).get("transportWarnings")).size() <= 2);
        assertEquals("ERROR", ((Map<?,?>) compact.get("execution")).get("status"));
        assertEquals("NOT_MET", ((Map<?,?>) compact.get("performance")).get("verdict"));
        assertEquals(1200L, ((Map<?,?>) compact.get("metrics")).get("runtimeError"));
        assertEquals(800L, ((Map<?,?>) compact.get("metrics")).get("failure"));
        Map<?,?> primaryClassification = (Map<?,?>) ((List<?>) ((Map<?,?>) compact.get("diagnostics")).get("classifications")).get(0);
        assertEquals("HTTP_RESPONSE_TOO_LARGE", primaryClassification.get("code"));
        assertEquals(1200L, primaryClassification.get("count"));
        assertEquals("OMITTED", ((Map<?,?>) compact.get("detailCompleteness")).get("state"));
        assertEquals("PENDING", ((Map<?,?>) compact.get("archive")).get("status"));
        assertTrue(JSON.writeValueAsBytes(compact).length <= LoadTerminalResult.MAX_SUMMARY_BYTES);
        assertEquals(32, ((Map<?,?>) ((Map<?,?>) compact.get("configuration")).get("authoredPaths")).size());
        assertFalse(JSON.writeValueAsString(projected).contains("/private/tmp"));
        assertFalse(JSON.writeValueAsString(projected).contains("failureDiagnostics"));
        assertFalse(JSON.writeValueAsString(projected).contains("buckets"));

        Map<String,Object> data = fields("jobId", "JOB-1", "status", "ERROR", "exitCode", 3,
                "result", projected, "console", "[LOAD] ERROR\n");
        Map<String,Object> journal = fields("id", 91, "event", "result", "data", data);
        int sseDataBytes = JSON.writeValueAsBytes(data).length;
        int journalEventBytes = JSON.writeValueAsBytes(journal).length;
        assertTrue(sseDataBytes < 48 * 1024, "post-envelope SSE data bytes=" + sseDataBytes);
        assertTrue(journalEventBytes < 48 * 1024, "post-envelope journal event bytes=" + journalEventBytes);
    }

    @Test void runtimeAndPreparationCausesTakePrecedenceOverTransportWarnings() {
        for (String code : new String[] {"HTTP_RESPONSE_TOO_LARGE", "MQ_RESPONSE_TOO_LARGE", "MQ_POOL_TIMEOUT"}) {
            Map<String,Object> summary = detailedSummary(code, "operation failed with " + code);
            Map<String,Object> result = LoadTerminalResult.projectOperationResult(
                    fields("status", "ERROR", "exitCode", 3, "diagnostics", List.of(fields("code", "ATT-WORKER-EVENT-TRUNCATED")), "summary", summary), "ERROR", 3);
            Map<?,?> cause = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) result.get("summary")).get("diagnostics")).get("primaryError");
            assertEquals(code, cause.get("code"));
            assertEquals(3, ((Number) result.get("exitCode")).intValue());
        }

        for (String[] pair : new String[][] {
                {"PREPARATION_LIMIT_EXCEEDED", "LOAD_RESOURCE_PREPARATION_LIMIT"},
                {"PREPARATION_TIMEOUT", "LOAD_PREPARATION_TIMEOUT"}
        }) {
            Map<String,Object> summary = detailedSummary(null, null);
            summary.put("resources", fields("preparation", fields("status", "PREPARATION_FAILED", "failed", 2, "failureCode", pair[0])));
            Map<String,Object> result = LoadTerminalResult.projectOperationResult(
                    fields("status", "ERROR", "exitCode", 3, "summary", summary), "ERROR", 3);
            Map<?,?> cause = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) result.get("summary")).get("diagnostics")).get("primaryError");
            assertEquals(pair[1], cause.get("code"));
            assertEquals(2, ((Number) cause.get("failedCount")).intValue());
        }
    }

    @Test void truncationAloneDoesNotChangeLoadOutcomeOrCreateARootCause() {
        Map<String,Object> result = LoadTerminalResult.projectOperationResult(
                fields("status", "PASS", "exitCode", 0,
                        "diagnostics", List.of(fields("code", "ATT-WORKER-EVENT-TRUNCATED", "summary", "transport symptom")),
                        "truncated", Boolean.TRUE, "summary", detailedSummary(null, null)), "PASS", 0);
        Map<String,Object> compactSummary = new LinkedHashMap<>((Map<String,Object>) result.get("summary"));
        compactSummary.put("archive", fields("status", "FAILED", "reportAvailability", "FAILED"));
        result = LoadTerminalResult.projectOperationResult(fields("status", "PASS", "exitCode", 0,
                "diagnostics", List.of(fields("code", "ATT-JOURNAL-EVENT-TRUNCATED", "summary", "secondary warning")),
                "summary", compactSummary), "PASS", 0);
        Map<?,?> compact = (Map<?,?>) result.get("summary");
        assertEquals("PASS", result.get("status")); assertEquals(0, ((Number) result.get("exitCode")).intValue());
        assertFalse(((Map<?,?>) compact.get("diagnostics")).containsKey("primaryError"));
        assertEquals("PASS", ((Map<?,?>) compact.get("execution")).get("status"));
        assertEquals("FAILED", ((Map<?,?>) compact.get("archive")).get("status"));
    }

    @Test void terminalExecutionOutcomesAndExitCodesRemainIndependentOfPerformanceVerdict() {
        for (Object[] outcome : new Object[][] { {"PASS", 0}, {"FAIL", 1}, {"ERROR", 3}, {"CANCELLED", 143} }) {
            String status = (String) outcome[0];
            int exitCode = (Integer) outcome[1];
            Map<String,Object> result = LoadTerminalResult.projectOperationResult(
                    fields("status", status, "exitCode", exitCode, "summary", detailedSummary(null, null)), status, exitCode);
            Map<?,?> summary = (Map<?,?>) result.get("summary");
            assertEquals(status, result.get("status"));
            assertEquals(exitCode, ((Number) result.get("exitCode")).intValue());
            assertEquals(status, ((Map<?,?>) summary.get("execution")).get("status"));
            assertEquals("NOT_MET", ((Map<?,?>) summary.get("performance")).get("verdict"));
        }
    }

    @Test void arbitraryNumericMetadataCannotBreakTheDeterministicByteBudget() throws Exception {
        Map<String,Object> summary = detailedSummary(null, null);
        Map<?,?> sourceMetrics = (Map<?,?>) summary.get("metrics");
        Map<String,Object> metrics = new LinkedHashMap<>((Map<String,Object>) sourceMetrics);
        metrics.put("completed", new BigInteger("9".repeat(100_000)));
        metrics.put("completedThroughput", Double.POSITIVE_INFINITY);
        summary.put("metrics", metrics);

        Map<String,Object> projected = LoadTerminalResult.projectOperationResult(
                fields("status", "PASS", "exitCode", 0, "summary", summary), "PASS", 0);
        Map<?,?> compact = (Map<?,?>) projected.get("summary");
        Map<?,?> compactMetrics = (Map<?,?>) compact.get("metrics");
        assertEquals(Long.MAX_VALUE, compactMetrics.get("completed"));
        assertTrue(JSON.writeValueAsBytes(compact).length <= LoadTerminalResult.MAX_SUMMARY_BYTES);
    }

    private Map<String,Object> detailedSummary(String primaryCode, String primarySummary) {
        Map<String,Object> classes = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) classes.put("CLASS_" + i, 10_000L + i);
        if (primaryCode != null) classes.put(primaryCode, 17L);
        List<Map<String,Object>> failures = new ArrayList<>();
        if (primaryCode != null) failures.add(fields("status", "ERROR", "code", primaryCode,
                "summary", primarySummary, "workloadId", "load-界"));
        Map<String,Object> buckets = new LinkedHashMap<>();
        for (int i = 0; i < 4096; i++) buckets.put(String.valueOf(1_700_000_000_000L + i * 1000L), fields("completed", i));
        Map<String,Object> workloads = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) workloads.put("workload-" + i, fields("status", "ERROR", "executionStatus", "ERROR",
                "performanceVerdict", "NOT_MET", "metrics", fields("completed", 500_000L, "buckets", buckets)));
        List<Map<String,Object>> thresholds = new ArrayList<>();
        for (int i = 0; i < 200; i++) thresholds.add(fields("name", "threshold-" + i, "expected", ">= 10/s", "actual", "9/s", "status", "FAIL"));
        Map<String,Object> metrics = fields("scheduled", 500_000L, "started", 499_000L, "completed", 498_000L,
                "success", 497_000L, "failure", 800L, "runtimeError", primaryCode == null ? 0L : 1200L,
                "dropped", 1000L, "timeSeriesBucketCapacity", 4096, "timeSeriesBucketCount", 4096,
                "errorClassifications", classes, "runtimeErrorClassifications", primaryCode == null ? Map.of() : Map.of(primaryCode, 1200L),
                "primaryRuntimeError", primaryCode == null ? null : fields("code", primaryCode, "summary", primarySummary),
                "failureDiagnostics", failures, "buckets", buckets,
                "windowMetrics", fields("schemaVersion", "att-load-window-metrics/v1", "timeSeries", fields("capacity", 2048, "bucketDurationMs", 60000L, "coverage", "Whole execution; roll-up")));
        return fields("runId", "run-雪", "status", primaryCode == null ? "PASS" : "ERROR", "exitCode", primaryCode == null ? 0 : 3,
                "startedAt", "2026-10-26T00:00:00Z", "endedAt", "2026-10-26T01:00:00Z", "durationMs", 3_600_000L,
                "executionStatus", primaryCode == null ? "PASS" : "ERROR", "performanceVerdict", "NOT_MET", "metrics", metrics,
                "thresholds", fields("status", "NOT_MET", "results", thresholds), "workloads", workloads,
                "resources", fields("preparation", fields("status", "READY")), "report", "report/index.html");
    }

    private Map<String,Object> maximumConfigurationEvidence() {
        Map<String,String> origins = new LinkedHashMap<>(), authored = new LinkedHashMap<>();
        for (int index = 0; index < 32; index++) {
            String key = "field" + String.format("%02d", index);
            origins.put(key, "package"); authored.put(key, "config/config.yaml");
        }
        return fields("configurationIdentity", "a".repeat(64), "manifestDigest", "b".repeat(64),
                "globalDigest", "c".repeat(64), "schemaRegistryDigest", "d".repeat(64),
                "packageRevision", "e".repeat(64), "approvedConfigurationIdentity", "f".repeat(64),
                "environment", "SIT", "engineVersion", "v".repeat(128), "engineRevision", "r".repeat(128),
                "packageId", "package-1", "environmentOrigin", "request", "origins", origins, "authoredPaths", authored);
    }

    private static Map<String,Object> fields(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
