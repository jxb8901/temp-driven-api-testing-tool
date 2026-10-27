package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadMetricsAccountingTest {
    @Test
    void reconcilesRunAndPhaseArrivalsOutcomesAndDrops() {
        long start = 1_000L;
        LoadMetrics metrics = LoadMetrics.forScenario(scenario(), start, 10_000L);
        metrics.onEvent(LoadEvent.completed("run", "arrivalRate", "STEADY", "pass", null, 1,
                start, start, start + 1, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("run", "arrivalRate", "STEADY", "sut-fail", null, 2,
                start + 1, start + 1, start + 2, ResultStatus.FAIL));
        metrics.onEvent(LoadEvent.rejected("run", "arrivalRate", "STEADY", "rejected", 3,
                start + 2, start + 3, "SUBMISSION_REJECTED"));
        metrics.finish(start + 10_000L);

        @SuppressWarnings("unchecked") Map<String, Object> accounting = (Map<String, Object>) metrics.snapshot().value("accounting");
        assertEquals("VERIFIED", accounting.get("state"));
        assertEquals(2, accounting.get("scopesChecked"));
        @SuppressWarnings("unchecked") Map<String, Object> phase = (Map<String, Object>) metrics.snapshot().phases().get("STEADY");
        assertEquals(3L, phase.get("scheduled"));
        assertEquals(2L, phase.get("started"));
        assertEquals(2L, phase.get("completed"));
        assertEquals(1L, phase.get("dropped"));
        assertEquals(0, phase.get("currentInFlight"));
    }

    @Test
    void unresolvedFinalDrainProducesPartialAccountingAndAnExecutionError() {
        long start = 1_000L;
        LoadScenario scenario = scenario();
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, start, 10_000L);
        metrics.onEvent(LoadEvent.started("run", "arrivalRate", "STEADY", "pending", null, 1,
                start, start + 1));
        metrics.finish(start + 10_000L);
        LoadRunResult result = new LoadRunResult("run", scenario, Instant.ofEpochMilli(start),
                Instant.ofEpochMilli(start + 10_000L), metrics.snapshot());

        assertEquals("PARTIAL", result.accountingStatus());
        assertEquals("ERROR", result.executionStatus());
        assertEquals("ERROR", result.status().name());
        String report = att.validation.JsonSupport.write(result.toDetailedMap("PENDING"));
        assertTrue(report.contains("LOAD_ACCOUNTING_INCOMPLETE"));
    }

    @Test
    void skippedCompletionsAreExplicitOtherOutcomesRatherThanSutFailures() {
        long start = 1_000L;
        LoadMetrics metrics = LoadMetrics.forScenario(scenario(), start, 10_000L);
        metrics.onEvent(LoadEvent.completed("run", "arrivalRate", "STEADY", "skipped", null, 1,
                start, start, start + 1, ResultStatus.SKIPPED));
        metrics.finish(start + 10_000L);

        assertEquals(0L, metrics.failure());
        assertEquals(1L, metrics.otherOutcome());
        @SuppressWarnings("unchecked") Map<String, Object> accounting =
                (Map<String, Object>) metrics.snapshot().value("accounting");
        assertEquals("VERIFIED", accounting.get("state"));
        @SuppressWarnings("unchecked") Map<String, Object> phase =
                (Map<String, Object>) metrics.snapshot().phases().get("STEADY");
        assertEquals(0L, phase.get("failure"));
        assertEquals(1L, phase.get("otherOutcome"));
    }

    @Test
    void impossibleCountersAndWorkloadAggregateMismatchAreRejected() {
        Map<String, Object> impossible = new LinkedHashMap<String, Object>();
        impossible.put("scheduled", 2L); impossible.put("started", 1L); impossible.put("dropped", 0L);
        impossible.put("completed", 1L); impossible.put("success", 2L); impossible.put("failure", 0L);
        impossible.put("runtimeError", 0L); impossible.put("currentInFlight", 0L);
        Map<String, Object> accounting = LoadMetricsAccounting.evaluate(impossible,
                Collections.<String, Map<String, Object>>emptyMap(), true);
        assertEquals("FAILED", accounting.get("state"));

        Map<String, Object> aggregate = counters(2L, 2L, 2L, 1L, 1L, 0L, 0L, 0L);
        Map<String, Map<String, Object>> children = new LinkedHashMap<String, Map<String, Object>>();
        children.put("one", counters(1L, 1L, 1L, 1L, 0L, 0L, 0L, 0L));
        children.put("two", counters(1L, 1L, 1L, 0L, 1L, 0L, 0L, 0L));
        assertEquals("VERIFIED", LoadMetricsAccounting.compareWorkloads(aggregate, children).get("state"));
        children.put("two", counters(1L, 1L, 1L, 0L, 0L, 0L, 0L, 0L));
        assertEquals("FAILED", LoadMetricsAccounting.compareWorkloads(aggregate, children).get("state"));
    }

    private static Map<String, Object> counters(long scheduled, long started, long completed,
                                                long success, long failure, long runtimeError,
                                                long dropped, long inFlight) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("scheduled", scheduled); result.put("started", started); result.put("completed", completed);
        result.put("success", success); result.put("failure", failure); result.put("runtimeError", runtimeError);
        result.put("otherOutcome", 0L);
        result.put("dropped", dropped); result.put("currentInFlight", inFlight);
        return result;
    }

    private static LoadScenario scenario() {
        return new LoadScenario(Paths.get("accounting.yaml"), "template", "NOOP",
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                LoadScenario.Model.ARRIVAL_RATE, 0, 10.0, "10/s", Duration.ZERO, Duration.ZERO,
                Duration.ofSeconds(10), Duration.ZERO, Duration.ZERO, 4, "drop",
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());
    }
}
