package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LoadWindowMetricsTest {
    @Test void admissionRateFollowsItsScheduledCohortAcrossPhaseBoundaries() {
        LoadWindowMetrics metrics = new LoadWindowMetrics(10_000L, 1_000L, 1_000L, 3_000L, 1_000L);
        // These ramp-up arrivals start during STEADY and must not be credited to
        // the steady cohort's admission percentage.
        for (int index = 0; index < 5; index++)
            metrics.onEvent(LoadEvent.started("r", "arrivalRate", "RAMP_UP", "ramp-" + index, null,
                    index, 11_000L + index * 100L, 12_100L + index * 100L));
        // Eight of ten STEADY arrivals start before finish; two remain unstarted.
        for (int index = 0; index < 8; index++)
            metrics.onEvent(LoadEvent.started("r", "arrivalRate", "STEADY", "steady-" + index, null,
                    index + 5, 12_000L + index * 100L, 12_200L + index * 100L));
        for (int index = 0; index < 2; index++)
            metrics.onEvent(LoadEvent.dropped("r", "arrivalRate", "STEADY", "dropped-" + index,
                    index + 20, 13_000L + index * 100L, 13_100L + index * 100L));
        metrics.finish(16_000L);

        @SuppressWarnings("unchecked") Map<String, Object> windows =
                (Map<String, Object>) metrics.snapshot(16_000L).get("windows");
        @SuppressWarnings("unchecked") Map<String, Object> steady = (Map<String, Object>) windows.get("steady");
        assertEquals(13L, steady.get("started"), "event-time starts still include prior-phase work");
        assertEquals(10L, steady.get("scheduled"));
        assertEquals(8.0 / 10.0, ((Number) steady.get("admissionRate")).doubleValue(), 0.000001);
        assertEquals(13.0 / 3.0, ((Number) steady.get("startedRate")).doubleValue(), 0.000001);
        assertTrue(((String) steady.get("basis")).contains("arrivals still unstarted at finish are not admitted"));
    }

    @Test void completionEventTimeAndScheduledCohortRemainIndependentAcrossDrain() {
        LoadWindowMetrics metrics = new LoadWindowMetrics(10_000L, 1_000L, 1_000L, 3_000L, 1_000L);
        // Scheduled during STEADY; its eventual completion is after generation ends.
        metrics.onEvent(LoadEvent.started("r", "arrivalRate", "STEADY", "late", null, 1, 12_000L, 12_000L));
        metrics.onEvent(LoadEvent.completion("r", "arrivalRate", "STEADY", "late", null, 1,
                12_000L, 12_000L, 17_000L, ResultStatus.PASS));
        // Arrival in RAMP_UP that completes in STEADY: wall-clock fail belongs to STEADY,
        // while the eventual failure belongs to the RAMP_UP scheduled cohort.
        metrics.onEvent(LoadEvent.completion("r", "arrivalRate", "RAMP_UP", "cross", null, 2,
                11_500L, 11_500L, 13_000L, ResultStatus.ERROR));
        metrics.finish(17_000L);
        Map<String, Object> root = metrics.snapshot(17_000L);
        @SuppressWarnings("unchecked") Map<String, Object> windows = (Map<String, Object>) root.get("windows");
        @SuppressWarnings("unchecked") Map<String, Object> steady = (Map<String, Object>) windows.get("steady");
        assertEquals(1L, steady.get("completed"));
        assertEquals(1L, steady.get("runtimeError"));
        assertEquals(0L, steady.get("successful"));
        assertEquals(1.0 / 3.0, (Double) steady.get("completedRate"), 0.000001);
        @SuppressWarnings("unchecked") Map<String, Object> steadyCohort = (Map<String, Object>) steady.get("cohort");
        assertEquals(1L, steadyCohort.get("completed"));
        assertEquals(1L, steadyCohort.get("successful"));
        @SuppressWarnings("unchecked") Map<String, Object> drain = (Map<String, Object>) windows.get("DRAIN");
        assertEquals(1L, drain.get("completed"));
        assertEquals("event-time; bins aligned to scheduler start; full-bin rate denominator",
                ((Map<?, ?>) root.get("timeSeries")).get("basis"));
    }

    @Test void incompleteSteadyWindowIsNotEvaluatedAndSeriesRetainsWholeRun() {
        LoadWindowMetrics metrics = new LoadWindowMetrics(0L, 0L, 0L, 3_000L, 0L);
        for (int second = 0; second < 2_050; second++)
            metrics.onEvent(LoadEvent.dropped("r", "arrivalRate", "STEADY", "d" + second, second,
                    second * 1_000L, second * 1_000L));
        metrics.finish(2_000L);
        Map<String, Object> root = metrics.snapshot(2_000L);
        @SuppressWarnings("unchecked") Map<String, Object> windows = (Map<String, Object>) root.get("windows");
        @SuppressWarnings("unchecked") Map<String, Object> steady = (Map<String, Object>) windows.get("steady");
        assertEquals(false, steady.get("evaluated"));
        assertEquals("WINDOW_INCOMPLETE", steady.get("notEvaluatedReason"));
        @SuppressWarnings("unchecked") Map<String, Object> series = (Map<String, Object>) root.get("timeSeries");
        assertTrue(((Number) series.get("bucketDurationMs")).longValue() >= 2_000L);
        assertTrue(((java.util.List<?>) series.get("points")).size() <= LoadWindowMetrics.SERIES_CAPACITY);
        assertEquals("Whole execution; uniform power-of-two roll-up, no historical eviction", series.get("coverage"));
    }
}
