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
                .withFailureDiagnostic(fields("code", "MQ_POOL_TIMEOUT", "summary", "Timed out waiting for MQ connection"));
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
        assertEquals("att-load-summary/v1.2", cliSummary.get("schemaVersion"));
        assertTrue(cliMetrics.containsKey("buckets"), "direct Engine/CLI result keeps full summary");

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
        metrics.finish(10_801_000L);
        LoadRunResult detailed = new LoadRunResult("long-run", scenario, Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(10_801_000L), metrics.snapshot());
        Map<?,?> coverage = (Map<?,?>) detailed.toTerminalSummary(null).get("metricCoverage");
        assertEquals(Boolean.FALSE, coverage.get("fullDurationInRollingWindow"));
        assertEquals(4096, coverage.get("loadMetricsBucketCapacity"));
        assertEquals(2048, coverage.get("windowSeriesCapacity"));
    }

    private static Map<String,Object> fields(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
