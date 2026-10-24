package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LoadThresholdWindowTest {
    @Test void steadyThresholdPassesWhenLegacyMeasuredThroughputIncludesRampDilution() {
        Map<String, Object> thresholds = new LinkedHashMap<String, Object>();
        thresholds.put("minThroughput", ">= 0.9/s"); // legacy measured-window denominator
        thresholds.put("steady", map("startedRate", ">= 0.9/s", "completedRate", ">= 0.9/s", "p95", "< 100ms", "errorRate", "<= 0%"));
        LoadScenario scenario = scenario(thresholds, 1_000L, 2_000L, 1_000L);
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, 10_000L, 4_000L);
        for (int i = 0; i < 2; i++) {
            long at = 11_100L + i * 500L;
            metrics.onEvent(LoadEvent.started("r", "arrivalRate", "STEADY", "i" + i, null, i, at, at));
            metrics.onEvent(LoadEvent.completion("r", "arrivalRate", "STEADY", "i" + i, null, i, at, at, at + 50L, ResultStatus.PASS));
        }
        metrics.finish(14_000L);
        LoadThresholdSummary verdict = new LoadThresholdEvaluator().evaluate(scenario, metrics.snapshot());
        assertEquals("NOT_MET", verdict.status());
        assertFalse(verdict.passed());
        assertTrue(verdict.results().stream().filter(r -> r.name().startsWith("steady.")).allMatch(ThresholdResult::passed),
                () -> att.validation.JsonSupport.write(verdict.toMap()));
        assertFalse(verdict.results().get(0).passed());
    }

    @Test void incompleteWindowProducesNotEvaluatedInsteadOfZeroOrPass() {
        Map<String, Object> thresholds = Collections.<String, Object>singletonMap("steady", map("completedRate", ">= 0/s"));
        LoadScenario scenario = scenario(thresholds, 0L, 2_000L, 0L);
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, 20_000L, 2_000L);
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "i", null, 1, 20_000L,
                20_000L, 20_100L, ResultStatus.PASS));
        metrics.finish(21_000L);
        LoadThresholdSummary verdict = new LoadThresholdEvaluator().evaluate(scenario, metrics.snapshot());
        assertEquals("NOT_EVALUATED", verdict.status());
        assertEquals("NOT_EVALUATED", verdict.results().get(0).status());
    }

    private static LoadScenario scenario(Map<String, Object> thresholds, long rampUp, long steady, long rampDown) {
        LoadWorkload workload = new LoadWorkload("work", "template", "PAYMENTS", Collections.emptyMap(), Collections.emptyMap(),
                LoadScenario.Model.ARRIVAL_RATE, 0, 10.0, "10/s", Duration.ZERO, Duration.ofMillis(rampUp),
                Duration.ofMillis(steady), Duration.ofMillis(rampDown), ThinkTimePolicy.fixed(Duration.ZERO), 10, "drop", thresholds);
        return new LoadScenario(Paths.get("load.yaml"), "att-load/v1.9", Collections.singletonList(workload),
                null, thresholds, Collections.emptyMap());
    }
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
