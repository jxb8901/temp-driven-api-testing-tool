package att.load;

import att.api.LoadResult;
import att.core.ResultStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadTerminalResultTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void workerProjectionKeepsCauseAndCoverageWhileDirectLoadResultRemainsDetailed() throws Exception {
        LoadScenario scenario = new LoadScenario(Paths.get("load.yaml"), "template", "T", Collections.emptyMap(),
                Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 100.0, "100", Duration.ZERO,
                Duration.ZERO, Duration.ofHours(1), Duration.ZERO, Duration.ZERO, 0, "",
                Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, 1_000L, Duration.ofHours(1).toMillis());
        LoadEvent failure = LoadEvent.completed("run-雪", "arrivalRate", "STEADY", "iteration-1", null,
                1L, 1_000L, 1_001L, 1_010L, ResultStatus.ERROR, "MQ_POOL_TIMEOUT", null)
                .withFailureDiagnostic(fields("code", "MQ_POOL_TIMEOUT", "summary", "Timed out waiting for MQ connection",
                        "limitName", "mq.pool.connectionRequestTimeoutMs", "configuredLimit", 1000,
                        "observed", 1001, "scope", "engine.mqPool", "outcome", "EXECUTION_ERROR",
                        "stage", "execution", "remediationHint", "Reduce concurrent MQ requests."));
        metrics.onEvent(failure);
        metrics.finish(3_601_000L);
        LoadRunResult detailed = new LoadRunResult("run-雪", scenario, Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(3_601_000L), metrics.snapshot(), LoadThresholdSummary.empty(),
                Collections.emptyMap(), Collections.emptyMap());
        LoadResult result = new LoadResult("run-雪", "ERROR", 3, 3_600_000L, Collections.emptyList(),
                Collections.singletonMap("report", "/private/tmp/report.html"), detailed, Collections.emptyMap());

        Map<String,Object> cli = result.toMap();
        Map<String,Object> worker = result.toWorkerMap();
        Map<?,?> cliSummary = (Map<?,?>) cli.get("summary");
        Map<?,?> cliMetrics = (Map<?,?>) cliSummary.get("metrics");
        Map<?,?> cliDiagnostics = (Map<?,?>) cliSummary.get("diagnostics");
        Map<?,?> cliPrimary = (Map<?,?>) cliDiagnostics.get("primaryError");
        assertEquals("att-load-summary/v1.2", cliSummary.get("schemaVersion"));
        assertTrue(cliMetrics.containsKey("buckets"), "direct Engine/CLI result keeps full summary");
        assertEquals("MQ_POOL_TIMEOUT", cliPrimary.get("code"), "direct CLI results expose the primary cause");
        assertEquals("mq.pool.connectionRequestTimeoutMs", cliPrimary.get("limitName"));
        assertEquals("EXECUTION_ERROR", cliPrimary.get("outcome"));

        Map<?,?> terminal = (Map<?,?>) worker.get("summary");
        Map<?,?> root = (Map<?,?>) ((Map<?,?>) terminal.get("diagnostics")).get("primaryError");
        Map<?,?> coverage = (Map<?,?>) terminal.get("metricCoverage");
        assertEquals("att-load-terminal-result/v1", terminal.get("schemaVersion"));
        assertEquals("MQ_POOL_TIMEOUT", root.get("code"));
        assertEquals("ERROR", ((Map<?,?>) terminal.get("execution")).get("status"));
        assertEquals(3, worker.get("exitCode"));
        assertFalse(((Map<?,?>) terminal.get("metrics")).containsKey("buckets"));
        assertFalse(((Map<?,?>) terminal.get("metrics")).containsKey("failureDiagnostics"));
        assertEquals(4096, coverage.get("loadMetricsBucketCapacity"));
        assertEquals(Boolean.TRUE, coverage.get("fullDurationInRollingWindow"));
        assertEquals("Whole execution; uniform power-of-two roll-up, no historical eviction", coverage.get("windowSeriesCoverage"));
        assertFalse(json.writeValueAsString(worker).contains("/private/tmp"));
        assertTrue(json.writeValueAsBytes(worker).length < 48 * 1024);
    }

    @Test void rollingBucketCoverageDoesNotClaimAFullLongRunIsRetained() {
        LoadScenario scenario = new LoadScenario(Paths.get("load.yaml"), "template", "T", Collections.emptyMap(),
                Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null, Duration.ZERO,
                Duration.ZERO, Duration.ofHours(3), Duration.ZERO, Duration.ZERO, 0, "",
                Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, 1_000L, Duration.ofHours(3).toMillis());
        for (int i = 0; i < LoadMetrics.MAX_BUCKETS + 10; i++) {
            long at = 1_000L + i * 1_000L;
            metrics.onEvent(LoadEvent.completed("long-run", "closed", "STEADY", "iteration-" + i, "VU-1", i + 1,
                    at, at, at + 1L, ResultStatus.PASS));
        }
        metrics.finish(10_801_000L);
        LoadRunResult detailed = new LoadRunResult("long-run", scenario, Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(10_801_000L), metrics.snapshot());
        Map<?,?> coverage = (Map<?,?>) detailed.toTerminalSummary(null).get("metricCoverage");
        assertEquals(Boolean.FALSE, coverage.get("fullDurationInRollingWindow"));
        assertEquals(4096, coverage.get("loadMetricsBucketCapacity"));
        assertEquals(10L, coverage.get("evictedBucketCount"));
        assertEquals("METRICS_SAMPLED", coverage.get("outcome"));
        assertTrue(((Number) coverage.get("observedBucketCoverageMs")).longValue() < 3L * 60L * 60L * 1000L);
        assertEquals(2048, coverage.get("windowSeriesCapacity"));
    }

    @Test void evidenceAndReportLimitsStaySecondaryAndPreserveExecutionOutcome() throws Exception {
        LoadScenario scenario = new LoadScenario(Paths.get("load.yaml"), "template", "T", Collections.emptyMap(),
                Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null, Duration.ZERO,
                Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, 0, "",
                Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, 1_000L, 1_000L);
        metrics.finish(2_000L);
        Map<String,Object> evidence = fields("skippedSuccessQuotaCount", 0L, "skippedFailureQuotaCount", 4L,
                "failureQuotaExceeded", Boolean.TRUE, "policy", fields("maxFailureSamples", 2));
        LoadRunResult result = new LoadRunResult("quota-run", scenario, Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(2_000L), metrics.snapshot(), LoadThresholdSummary.empty(), evidence);

        Map<?,?> compact = result.toTerminalSummary(null, "FAILED");
        Map<?,?> diagnostics = (Map<?,?>) compact.get("diagnostics");
        java.util.List<?> notices = (java.util.List<?>) diagnostics.get("secondaryNotices");
        Map<?,?> evidenceNotice = (Map<?,?>) notices.get(0);
        Map<?,?> reportNotice = (Map<?,?>) notices.get(1);
        Map<?,?> execution = (Map<?,?>) compact.get("execution");
        Map<?,?> archive = (Map<?,?>) compact.get("archive");

        assertEquals("PASS", execution.get("status"));
        assertEquals(0, ((Number) execution.get("exitCode")).intValue());
        assertEquals("FAILED", archive.get("status"));
        assertEquals("ATT-LOAD-EVIDENCE-QUOTA", evidenceNotice.get("code"));
        assertEquals("DATA_TRUNCATED", evidenceNotice.get("outcome"));
        assertEquals("load.evidence.maxFailureSamples", evidenceNotice.get("limitName"));
        assertEquals(4L, ((Number) evidenceNotice.get("observed")).longValue());
        assertEquals("LOAD_REPORT_WRITE_FAILED", reportNotice.get("code"));
        assertEquals("ARCHIVE_FAILED", reportNotice.get("outcome"));
        assertFalse(diagnostics.containsKey("primaryError"));
        assertFalse(json.writeValueAsString(compact).contains("business-payload"));
        assertTrue(json.writeValueAsBytes(compact).length < 48 * 1024);
    }

    @Test void preparationDeadlineAndConnectionBudgetArePrimaryExecutionErrors() {
        LoadScenario scenario = new LoadScenario(Paths.get("load.yaml"), "template", "T", Collections.emptyMap(),
                Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null, Duration.ZERO,
                Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, 0, "",
                Collections.emptyMap(), Collections.emptyMap());
        Map<String,Object> deadline = fields("status", "PREPARATION_FAILED", "failureCode", "PREPARATION_TIMEOUT",
                "preparationDeadlineMs", 500L, "preparationMs", 501L, "initialConnections", 1,
                "perRunConnectionBudget", 64);
        Map<String,Object> connectionLimit = fields("status", "PREPARATION_FAILED", "failureCode", "PREPARATION_LIMIT_EXCEEDED",
                "preparationDeadlineMs", 500L, "preparationMs", 20L, "initialConnections", 65,
                "perRunConnectionBudget", 64);

        assertPreparationFailure(scenario, deadline, "LOAD_PREPARATION_TIMEOUT", "load.preparationDeadlineMs", 501L, 500L);
        assertPreparationFailure(scenario, connectionLimit, "LOAD_RESOURCE_PREPARATION_LIMIT",
                "load.initialConnectionsPerWorker", 65L, 64L);
    }

    @Test void helperResponseAndPoolLimitsPreserveLoadErrorExitAndPrimaryDiagnostic() {
        LoadScenario scenario = new LoadScenario(Paths.get("load.yaml"), "template", "T", Collections.emptyMap(),
                Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null, Duration.ZERO,
                Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, 0, "",
                Collections.emptyMap(), Collections.emptyMap());
        String[][] contracts = {
                {"LIMIT_EXCEEDED", "db.result.maxBytes"},
                {"MQ_RESPONSE_TOO_LARGE", "mq.response.maxBytes"},
                {"HTTP_RESPONSE_TOO_LARGE", "http.response.maxBytes"},
                {"DB_POOL_TIMEOUT", "db.poolConnectionTimeoutMs"},
                {"MQ_POOL_TIMEOUT", "mq.pool.borrowTimeoutMs"},
                {"HTTP_POOL_TIMEOUT", "http.pool.connectionRequestTimeoutMs"}
        };
        for (int i = 0; i < contracts.length; i++) {
            String code = contracts[i][0];
            String limitName = contracts[i][1];
            LoadMetrics metrics = LoadMetrics.forScenario(scenario, 1_000L, 1_000L);
            Map<String,Object> diagnostic = fields("code", code, "summary", "A configured helper limit was reached.",
                    "limitName", limitName, "configuredLimit", 1000, "observed", 1001,
                    "scope", "engine.helper", "outcome", "EXECUTION_ERROR", "stage", "execution",
                    "remediationHint", "Reduce request size or concurrency.");
            metrics.onEvent(LoadEvent.completed("helper-limit-run", "closed", "STEADY", "iteration-" + i,
                    "VU-1", i + 1L, 1_000L, 1_001L, 1_002L, ResultStatus.ERROR, code, null)
                    .withFailureDiagnostic(diagnostic));
            metrics.finish(2_000L);
            LoadRunResult result = new LoadRunResult("helper-limit-run", scenario, Instant.ofEpochMilli(1_000L),
                    Instant.ofEpochMilli(2_000L), metrics.snapshot());
            Map<?,?> terminal = result.toTerminalSummary(null);
            Map<?,?> execution = (Map<?,?>) terminal.get("execution");
            Map<?,?> primary = (Map<?,?>) ((Map<?,?>) terminal.get("diagnostics")).get("primaryError");
            assertEquals("ERROR", execution.get("status"), code);
            assertEquals(3, ((Number) execution.get("exitCode")).intValue(), code);
            assertEquals(code, primary.get("code"));
            assertEquals(limitName, primary.get("limitName"));
            assertEquals("EXECUTION_ERROR", primary.get("outcome"));
            assertEquals(1001L, ((Number) primary.get("observed")).longValue());
            assertEquals(1000L, ((Number) primary.get("configuredLimit")).longValue());
        }
    }

    private void assertPreparationFailure(LoadScenario scenario, Map<String,Object> preparation, String code,
                                         String limitName, long observed, long configured) {
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, 1_000L, 1_000L);
        metrics.finish(2_000L);
        LoadRunResult result = new LoadRunResult("preparation-failed", scenario, Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(2_000L), metrics.snapshot(), LoadThresholdSummary.empty(),
                Collections.emptyMap(), Collections.<String,Object>singletonMap("preparation", preparation));
        Map<?,?> terminal = result.toTerminalSummary(null);
        Map<?,?> execution = (Map<?,?>) terminal.get("execution");
        Map<?,?> primary = (Map<?,?>) ((Map<?,?>) terminal.get("diagnostics")).get("primaryError");
        assertEquals("ERROR", execution.get("status"));
        assertEquals(3, ((Number) execution.get("exitCode")).intValue());
        assertEquals(code, primary.get("code"));
        assertEquals(limitName, primary.get("limitName"));
        assertEquals("EXECUTION_ERROR", primary.get("outcome"));
        assertEquals(observed, ((Number) primary.get("observed")).longValue());
        assertEquals(configured, ((Number) primary.get("configuredLimit")).longValue());
    }

    private static Map<String,Object> fields(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
