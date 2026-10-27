package att.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadTerminalResultTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void legacyDetailedSummaryProjectsToBoundedPostEnvelopeResultAndKeepsRealErrorFirst() throws Exception {
        Map<String,Object> summary = detailedSummary("HTTP_RESPONSE_TOO_LARGE", "root cause \"雪\"\\line\n" + repeat("界",3000));
        List<Map<String,Object>> diagnostics = new ArrayList<>();
        diagnostics.add(fields("code", "ATT-WORKER-EVENT-TRUNCATED", "summary", "transport truncated",
                "limitName", "worker.protocol.maxFrameBytes", "configuredLimit", 2_097_152L,
                "observed", 2_200_000L, "outcome", "DATA_TRUNCATED"));
        diagnostics.add(fields("code", "ATT-JOURNAL-EVENT-TRUNCATED", "summary", "journal truncated",
                "limitName", "server.journal.maxEventBytes", "configuredLimit", 65_536L,
                "observed", 70_000L, "outcome", "DATA_TRUNCATED"));
        Map<String,Object> original = fields("executionId", "run-雪", "status", "ERROR", "exitCode", 3,
                "durationMs", 3_600_000L, "paths", fields("report", "/private/tmp/report.html"),
                "diagnostics", diagnostics, "summary", summary);
        summary.put("configuration", maximumConfigurationEvidence());

        Map<String,Object> projected = LoadTerminalResult.projectOperationResult(original, "ERROR", 3);
        Map<?,?> compact = (Map<?,?>) projected.get("summary");
        Map<?,?> cause = (Map<?,?>) ((Map<?,?>) compact.get("diagnostics")).get("primaryError");
        assertEquals(LoadTerminalResult.SCHEMA_VERSION, compact.get("schemaVersion"));
        assertEquals("HTTP_RESPONSE_TOO_LARGE", cause.get("code"));
        assertEquals("EXECUTION_ERROR", cause.get("outcome"));
        assertEquals("http.response.maxBytes", cause.get("limitName"));
        assertEquals(10_485_760L, ((Number) cause.get("configuredLimit")).longValue());
        assertTrue(String.valueOf(cause.get("summary")).contains("雪"));
        assertTrue(((List<?>) ((Map<?,?>) compact.get("diagnostics")).get("transportWarnings")).size() <= 2);
        Map<?,?> workerWarning=(Map<?,?>)((List<?>)((Map<?,?>)compact.get("diagnostics")).get("transportWarnings")).get(0);
        assertEquals("ATT-WORKER-EVENT-TRUNCATED",workerWarning.get("code"));
        assertEquals("DATA_TRUNCATED",workerWarning.get("outcome"));
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
        for (String code : new String[] {"HTTP_RESPONSE_TOO_LARGE", "MQ_RESPONSE_TOO_LARGE", "MQ_POOL_TIMEOUT",
                "DB_POOL_TIMEOUT", "HTTP_POOL_TIMEOUT", "LIMIT_EXCEEDED"}) {
            Map<String,Object> summary = detailedSummary(code, "operation failed with " + code);
            Map<String,Object> result = LoadTerminalResult.projectOperationResult(
                    fields("status", "ERROR", "exitCode", 3, "diagnostics", Collections.singletonList(fields("code", "ATT-WORKER-EVENT-TRUNCATED")), "summary", summary), "ERROR", 3);
            Map<?,?> cause = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) result.get("summary")).get("diagnostics")).get("primaryError");
            assertEquals(code, cause.get("code"));
            assertEquals("ERROR", result.get("status"));
            assertEquals(3, ((Number) result.get("exitCode")).intValue());
            assertEquals("EXECUTION_ERROR", cause.get("outcome"));
            assertTrue(cause.get("limitName") instanceof String);
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
            assertEquals("EXECUTION_ERROR", cause.get("outcome"));
            assertEquals("load.preparation", cause.get("scope"));
            assertTrue(cause.containsKey("limitName"));
            assertTrue(cause.get("configuredLimit") instanceof Number);
            assertTrue(cause.get("observed") instanceof Number);
        }
    }

    @Test void preparationRootCauseDoesNotInventFailedCountWhenNoHelperFailed() {
        Map<String,Object> summary = detailedSummary(null, null);
        summary.put("resources", fields("preparation", fields("status", "PREPARATION_FAILED", "required", 65,
                "prepared", 0, "failed", 0, "failureCode", "LOAD_RESOURCE_PREPARATION_LIMIT")));
        Map<String,Object> result = LoadTerminalResult.projectOperationResult(
                fields("status", "ERROR", "exitCode", 3, "summary", summary), "ERROR", 3);
        Map<?,?> cause = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) result.get("summary")).get("diagnostics")).get("primaryError");
        assertEquals("LOAD_RESOURCE_PREPARATION_LIMIT", cause.get("code"));
        assertFalse(cause.containsKey("failedCount"));
    }

    @Test void truncationAloneDoesNotChangeLoadOutcomeOrCreateARootCause() {
        Map<String,Object> result = LoadTerminalResult.projectOperationResult(
                fields("status", "PASS", "exitCode", 0,
                        "diagnostics", Collections.singletonList(fields("code", "ATT-WORKER-EVENT-TRUNCATED", "summary", "transport symptom")),
                        "truncated", Boolean.TRUE, "summary", detailedSummary(null, null)), "PASS", 0);
        Map<String,Object> compactSummary = new LinkedHashMap<>((Map<String,Object>) result.get("summary"));
        compactSummary.put("archive", fields("status", "FAILED", "reportAvailability", "FAILED"));
        result = LoadTerminalResult.projectOperationResult(fields("status", "PASS", "exitCode", 0,
                "diagnostics", Collections.singletonList(fields("code", "ATT-JOURNAL-EVENT-TRUNCATED", "summary", "secondary warning")),
                "summary", compactSummary), "PASS", 0);
        Map<?,?> compact = (Map<?,?>) result.get("summary");
        assertEquals("PASS", result.get("status")); assertEquals(0, ((Number) result.get("exitCode")).intValue());
        assertFalse(((Map<?,?>) compact.get("diagnostics")).containsKey("primaryError"));
        assertEquals("PASS", ((Map<?,?>) compact.get("execution")).get("status"));
        assertEquals("FAILED", ((Map<?,?>) compact.get("archive")).get("status"));
    }

    @Test void secondaryNoticesAreSafeBoundedAndRemainSeparateFromThePrimaryCause() throws Exception {
        Map<String,Object> diagnostics = fields("primaryError", fields("code", "HTTP_RESPONSE_TOO_LARGE",
                "summary", "SUT response exceeded its configured limit", "outcome", "EXECUTION_ERROR"),
                "secondaryNotices", Arrays.asList(
                fields("code", "ATT-LOAD-EVIDENCE-QUOTA", "summary", "Evidence was sampled",
                        "limitName", "load.evidence.maxFailureSamples", "observed", 12L,
                        "outcome", "DATA_TRUNCATED", "scope", "engine.loadEvidence"),
                fields("code", "LOAD_REPORT_WRITE_FAILED", "summary", "Report artifacts could not be published",
                        "outcome", "ARCHIVE_FAILED", "scope", "engine.loadReport",
                        "remediationHint", "Retry report publication after storage is available."),
                fields("code", "ATT-UNBOUNDED", "summary", "This third notice must be omitted")));
        Map<String,Object> compact = LoadTerminalResult.compact(fields("runId", "safe-run", "status", "ERROR",
                "exitCode", 3, "durationMs", 1000L, "executionStatus", "ERROR", "performanceVerdict", "NOT_EVALUATED",
                "metrics", fields("runtimeError", 1L), "thresholds", Collections.emptyMap(),
                "workloads", Collections.emptyMap(), "diagnostics", diagnostics,
                "metricCoverage", fields("loadMetricsBucketCapacity", 4096),
                "detailCompleteness", fields("state", "OMITTED", "truncated", Boolean.TRUE),
                "archive", fields("status", "PENDING", "reportAvailability", "PENDING")));
        Map<String,Object> projected = LoadTerminalResult.projectOperationResult(
                fields("status", "ERROR", "exitCode", 3, "summary", compact), "ERROR", 3);
        Map<?,?> compactDiagnostics = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) projected.get("summary")).get("diagnostics"));
        Map<?,?> primary = (Map<?,?>) compactDiagnostics.get("primaryError");
        List<?> notices = (List<?>) compactDiagnostics.get("secondaryNotices");
        assertEquals("HTTP_RESPONSE_TOO_LARGE", primary.get("code"));
        assertEquals(2, notices.size());
        assertEquals("ATT-LOAD-EVIDENCE-QUOTA", ((Map<?,?>) notices.get(0)).get("code"));
        assertEquals("ARCHIVE_FAILED", ((Map<?,?>) notices.get(1)).get("outcome"));
        assertFalse(JSON.writeValueAsString(projected).contains("business-payload"));
        assertTrue(JSON.writeValueAsBytes(projected).length <= LoadTerminalResult.MAX_SUMMARY_BYTES + 1024);
    }

    @Test void earlyInvalidLoadKeepsItsDiagnosticAndRemainsInvalidWithoutArchive() {
        Map<String,Object> result = LoadTerminalResult.projectOperationResult(fields(
                "status", "INVALID", "exitCode", 2,
                "diagnostics", Collections.singletonList(fields("code", "LOAD_INVALID", "message", "Scenario is missing workloads")),
                "summary", Collections.emptyMap()), "INVALID", 2);
        Map<?,?> summary = (Map<?,?>) result.get("summary");
        Map<?,?> primary = (Map<?,?>) ((Map<?,?>) summary.get("diagnostics")).get("primaryError");
        Map<?,?> archive = (Map<?,?>) summary.get("archive");
        assertEquals("INVALID", result.get("status"));
        assertEquals(2, ((Number) result.get("exitCode")).intValue());
        assertEquals("LOAD_INVALID", primary.get("code"));
        assertEquals("Scenario is missing workloads", primary.get("summary"));
        assertEquals("OMITTED", archive.get("status"));
        assertEquals("OMITTED", archive.get("reportAvailability"));
        assertTrue(((Map<?,?>) archive.get("paths")).isEmpty());
    }

    @Test void serverReprojectionKeepsWorkerFallbackCauseAndTransportWarningSeparate() {
        Map<String,Object> workerFallback = LoadTerminalResult.projectOperationResult(fields(
                "status", "ERROR", "exitCode", 3,
                "summary", detailedSummary("MQ_RESPONSE_TOO_LARGE", "MQ response exceeded the configured size")), "ERROR", 3);
        Map<String,Object> summary = new LinkedHashMap<>((Map<String,Object>) workerFallback.get("summary"));
        Map<String,Object> diagnostics = new LinkedHashMap<>((Map<String,Object>) summary.get("diagnostics"));
        diagnostics.put("transportWarnings", Collections.singletonList(fields("code", "ATT-WORKER-EVENT-TRUNCATED", "summary", "Worker details truncated")));
        diagnostics.put("secondaryNotices", Collections.singletonList(fields("code", "ATT-LOAD-EVIDENCE-QUOTA",
                "summary", "Some iteration evidence was not retained", "outcome", "DATA_TRUNCATED")));
        summary.put("diagnostics", diagnostics);
        workerFallback.put("summary", summary);

        Map<String,Object> stored = LoadTerminalResult.projectOperationResult(workerFallback, "ERROR", 3);
        Map<?,?> storedSummary = (Map<?,?>) stored.get("summary");
        Map<?,?> storedDiagnostics = (Map<?,?>) storedSummary.get("diagnostics");
        Map<?,?> cause = (Map<?,?>) storedDiagnostics.get("primaryError");
        assertEquals("ERROR", stored.get("status"));
        assertEquals(3, ((Number) stored.get("exitCode")).intValue());
        assertEquals("MQ_RESPONSE_TOO_LARGE", cause.get("code"));
        assertEquals("ATT-WORKER-EVENT-TRUNCATED", ((Map<?,?>) ((List<?>) storedDiagnostics.get("transportWarnings")).get(0)).get("code"));
        assertEquals("ATT-LOAD-EVIDENCE-QUOTA", ((Map<?,?>) ((List<?>) storedDiagnostics.get("secondaryNotices")).get(0)).get("code"));
    }

    @Test void reportPublicationFailureDoesNotReplaceTheLoadRootCauseOrOutcome() {
        Map<String,Object> summary = LoadTerminalResult.compact(fields("runId", "load-run", "status", "ERROR",
                "exitCode", 3, "durationMs", 1000L, "metrics", fields("runtimeError", 1L, "failure", 1L),
                "diagnostics", fields("primaryError", fields("code", "HTTP_RESPONSE_TOO_LARGE",
                        "summary", "SUT response exceeded its configured limit")),
                "archive", fields("status", "FAILED", "reportAvailability", "FAILED",
                        "omissionReason", "LOAD_REPORT_WRITE_FAILED", "paths", fields("report", "load/run/report/index.html"))));
        Map<String,Object> result = LoadTerminalResult.projectOperationResult(
                fields("status", "ERROR", "exitCode", 3, "summary", summary), "ERROR", 3);
        Map<?,?> compact = (Map<?,?>) result.get("summary");
        Map<?,?> archive = (Map<?,?>) compact.get("archive");
        Map<?,?> diagnostics = (Map<?,?>) compact.get("diagnostics");
        assertEquals("ERROR", result.get("status"));
        assertEquals(3, ((Number) result.get("exitCode")).intValue());
        assertEquals("FAILED", archive.get("status"));
        assertEquals("FAILED", archive.get("reportAvailability"));
        assertEquals("HTTP_RESPONSE_TOO_LARGE", ((Map<?,?>) diagnostics.get("primaryError")).get("code"));
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
        metrics.put("completed", new BigInteger(repeat("9",100_000)));
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
        String limitName = "MQ_POOL_TIMEOUT".equals(primaryCode) ? "mq.pool.borrowTimeoutMs"
                : "MQ_RESPONSE_TOO_LARGE".equals(primaryCode) ? "mq.response.maxBytes"
                : "DB_POOL_TIMEOUT".equals(primaryCode) ? "db.poolConnectionTimeoutMs"
                : "HTTP_POOL_TIMEOUT".equals(primaryCode) ? "http.pool.connectionRequestTimeoutMs"
                : "LIMIT_EXCEEDED".equals(primaryCode) ? "db.result.maxRows" : "http.response.maxBytes";
        Number configuredLimit = "MQ_POOL_TIMEOUT".equals(primaryCode) ? 30_000L
                : "MQ_RESPONSE_TOO_LARGE".equals(primaryCode) ? 4_194_304L
                : "DB_POOL_TIMEOUT".equals(primaryCode) ? 250L
                : "HTTP_POOL_TIMEOUT".equals(primaryCode) ? 40L
                : "LIMIT_EXCEEDED".equals(primaryCode) ? 1L : 10_485_760L;
        Number observed = "MQ_POOL_TIMEOUT".equals(primaryCode) || "DB_POOL_TIMEOUT".equals(primaryCode)
                || "HTTP_POOL_TIMEOUT".equals(primaryCode) ? null : configuredLimit.longValue() + 1L;
        String scope = "MQ_POOL_TIMEOUT".equals(primaryCode) || "MQ_RESPONSE_TOO_LARGE".equals(primaryCode) ? "engine.mq"
                : "DB_POOL_TIMEOUT".equals(primaryCode) || "LIMIT_EXCEEDED".equals(primaryCode) ? "engine.db"
                : "HTTP_POOL_TIMEOUT".equals(primaryCode) ? "engine.http.pool" : "engine.http";
        Map<String,Object> classes = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) classes.put("CLASS_" + i, 10_000L + i);
        if (primaryCode != null) classes.put(primaryCode, 17L);
        List<Map<String,Object>> failures = new ArrayList<>();
        if (primaryCode != null) failures.add(fields("status", "ERROR", "code", primaryCode,
                "summary", primarySummary, "workloadId", "load-界", "limitName", limitName,
                "configuredLimit", configuredLimit, "observed", observed,
                "scope", scope, "outcome", "EXECUTION_ERROR", "stage", "execution"));
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
                "errorClassifications", classes, "runtimeErrorClassifications", primaryCode == null ? Collections.<String,Object>emptyMap() : fields(primaryCode, 1200L),
                "primaryRuntimeError", primaryCode == null ? null : fields("code", primaryCode, "summary", primarySummary,
                        "limitName", limitName, "configuredLimit", configuredLimit,
                        "observed", observed, "scope", scope,
                        "outcome", "EXECUTION_ERROR", "stage", "execution"),
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
        return fields("configurationIdentity", repeat("a",64), "manifestDigest", repeat("b",64),
                "globalDigest", repeat("c",64), "schemaRegistryDigest", repeat("d",64),
                "packageRevision", repeat("e",64), "approvedConfigurationIdentity", repeat("f",64),
                "environment", "SIT", "engineVersion", repeat("v",128), "engineRevision", repeat("r",128),
                "packageId", "package-1", "environmentOrigin", "request", "origins", origins, "authoredPaths", authored);
    }

    private static Map<String,Object> fields(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
