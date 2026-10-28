package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LoadWindowMetricsTest {
    @Test void rampUpAndWarmupArePreparationPhasesAndMeasuredWindowsStartAfterBoth() {
        long start = 10_000L;
        LoadScenario scenario = new LoadScenario(Paths.get("phase-order.yaml"), "template", "NOOP",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 1.0, "1/s",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ZERO,
                4, "drop", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, start, 5_000L);
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "RAMP_UP", "ramp", null, 1,
                start + 500L, start + 500L, start + 510L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "WARMUP", "warmup", null, 2,
                start + 1_500L, start + 1_500L, start + 1_510L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "steady", null, 3,
                start + 2_500L, start + 2_500L, start + 2_510L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "RAMP_DOWN", "down", null, 4,
                start + 5_500L, start + 5_500L, start + 5_510L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "DRAIN", "drain", null, 5,
                start + 6_000L, start + 6_000L, start + 6_010L, ResultStatus.PASS));
        metrics.finish(start + 6_010L);

        LoadMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(5L, snapshot.value("completed"));
        assertEquals(2L, snapshot.value("measuredScheduled"));
        assertEquals(2L, snapshot.value("measuredCompleted"));
        assertEquals(0.5, ((Number) snapshot.value("measuredAchievedArrivalRate")).doubleValue(), 0.000001,
                "the measured throughput denominator covers Steady + Ramp-down and excludes DRAIN");
        assertEquals(1L, snapshot.value("warmupCompleted"), "the legacy warmupCompleted counter remains Warm-up-only");
        assertEquals(Boolean.FALSE, snapshot.phases().get("RAMP_UP").get("measured"));
        assertEquals(Boolean.FALSE, snapshot.phases().get("WARMUP").get("measured"));
        assertEquals(Boolean.TRUE, snapshot.phases().get("STEADY").get("measured"));
        assertEquals(Boolean.TRUE, snapshot.phases().get("RAMP_DOWN").get("measured"));
        assertEquals(Boolean.FALSE, snapshot.phases().get("DRAIN").get("measured"));

        @SuppressWarnings("unchecked") Map<String,Object> windows = (Map<String,Object>) snapshot.value("windowMetrics");
        @SuppressWarnings("unchecked") List<Map<String,Object>> boundaries = (List<Map<String,Object>>) windows.get("phaseBoundaries");
        assertEquals(java.util.Arrays.asList("RAMP_UP", "WARMUP", "STEADY", "RAMP_DOWN", "DRAIN"),
                boundaries.stream().map(row -> row.get("phase")).collect(java.util.stream.Collectors.toList()));
        assertEquals(start + 2_000L, boundaries.get(2).get("windowStartEpochMs"));
    }

    @Test void windowSeriesKeepSubsecondSteadyTrafficAndExcludeWarmup() {
        long start = 10_000L;
        LoadWindowMetrics metrics = new LoadWindowMetrics(start, 250L, 0L, 250L, 0L);
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "WARMUP", "warmup", null, 1,
                start + 100L, start + 100L, start + 150L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "steady", null, 2,
                start + 350L, start + 350L, start + 375L, ResultStatus.PASS));
        metrics.finish(start + 500L);

        @SuppressWarnings("unchecked") Map<String, Object> root = metrics.snapshot(start + 500L);
        @SuppressWarnings("unchecked") Map<String, Object> timeSeries = (Map<String, Object>) root.get("timeSeries");
        @SuppressWarnings("unchecked") List<Map<String, Object>> allPoints =
                (List<Map<String, Object>>) timeSeries.get("points");
        assertEquals(2L, allPoints.get(0).get("completed"), "the all-execution series keeps both phase events");

        @SuppressWarnings("unchecked") Map<String, Object> windowSeries =
                (Map<String, Object>) timeSeries.get("windowSeries");
        @SuppressWarnings("unchecked") Map<String, Object> steadySeries =
                (Map<String, Object>) windowSeries.get("steady");
        assertEquals(start + 250L, steadySeries.get("windowStartEpochMs"));
        assertEquals(start + 500L, steadySeries.get("windowEndEpochMs"));
        assertEquals(250L, steadySeries.get("bucketDurationMs"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> steadyPoints =
                (List<Map<String, Object>>) steadySeries.get("points");
        assertEquals(1, steadyPoints.size());
        Map<String, Object> point = steadyPoints.get(0);
        assertEquals(start + 250L, point.get("windowStartEpochMs"));
        assertEquals(250L, point.get("durationMs"));
        assertEquals(1L, point.get("scheduled"));
        assertEquals(1L, point.get("started"));
        assertEquals(1L, point.get("completed"));
        assertEquals(1L, point.get("successful"));
        assertEquals(1L, point.get("latencyObservationCount"));
        assertEquals(25L, point.get("latencyP95Ms"));
        assertEquals(4.0, ((Number) point.get("completedRate")).doubleValue(), 0.000001);
        assertEquals(java.util.Collections.singletonList("STEADY"), point.get("phaseCoverage"));
    }

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
        assertEquals("event-time counts and rates; latency samples grouped by scheduled cohort; bins aligned to scheduler start; full-bin rate denominator",
                ((Map<?, ?>) root.get("timeSeries")).get("basis"));
    }

    @Test void trendLatencyStaysWithScheduledCohortWhenCompletionOccursDuringDrain() {
        LoadWindowMetrics metrics = new LoadWindowMetrics(10_000L, 1_000L, 1_000L, 3_000L, 1_000L);
        metrics.onEvent(LoadEvent.started("r", "arrivalRate", "STEADY", "late", null, 1, 12_000L, 12_000L));
        metrics.onEvent(LoadEvent.completion("r", "arrivalRate", "STEADY", "late", null, 1,
                12_000L, 12_000L, 17_000L, ResultStatus.PASS));
        metrics.finish(17_000L);

        Map<String, Object> root = metrics.snapshot(17_000L);
        @SuppressWarnings("unchecked") Map<String, Object> series = (Map<String, Object>) root.get("timeSeries");
        @SuppressWarnings("unchecked") java.util.List<Map<String, Object>> points =
                (java.util.List<Map<String, Object>>) series.get("points");
        Map<String, Object> steadyPoint = points.stream()
                .filter(point -> ((Number) point.get("windowStartEpochMs")).longValue() == 12_000L)
                .findFirst().orElseThrow(AssertionError::new);
        Map<String, Object> drainPoint = points.stream()
                .filter(point -> ((Number) point.get("windowStartEpochMs")).longValue() == 17_000L)
                .findFirst().orElseThrow(AssertionError::new);
        assertTrue(((java.util.List<?>) steadyPoint.get("phaseCoverage")).contains("STEADY"));
        assertEquals(1, steadyPoint.get("latencySampleCount"));
        assertEquals(1L, steadyPoint.get("latencyObservationCount"));
        assertEquals(5_000L, steadyPoint.get("latencyP50Ms"));
        assertEquals(0L, steadyPoint.get("completed"));
        assertTrue(((java.util.List<?>) drainPoint.get("phaseCoverage")).contains("DRAIN"));
        assertEquals(1L, drainPoint.get("completed"));
        assertEquals(0, drainPoint.get("latencySampleCount"));
    }

    @Test void rolledUpLatencyReservoirWeightsSamplesByTheirObservationCounts() {
        LoadWindowMetrics metrics = new LoadWindowMetrics(0L, 0L, 0L, 3_000L, 0L);
        for (int index = 0; index < 10_000; index++)
            metrics.onEvent(LoadEvent.completion("r", "arrivalRate", "STEADY", "common-" + index, null, index,
                    0L, 0L, 100L, ResultStatus.PASS));
        for (int index = 0; index < 10; index++)
            metrics.onEvent(LoadEvent.completion("r", "arrivalRate", "STEADY", "rare-" + index, null, index,
                    1_000L, 1_000L, 11_000L, ResultStatus.PASS));
        for (int second = 2; second <= 2_048; second++)
            metrics.onEvent(LoadEvent.dropped("r", "arrivalRate", "STEADY", "d" + second, second,
                    second * 1_000L, second * 1_000L));
        metrics.finish(2_049_000L);

        @SuppressWarnings("unchecked") Map<String, Object> series =
                (Map<String, Object>) metrics.snapshot(2_049_000L).get("timeSeries");
        @SuppressWarnings("unchecked") java.util.List<Map<String, Object>> points =
                (java.util.List<Map<String, Object>>) series.get("points");
        Map<String, Object> firstRolledPoint = points.stream()
                .filter(point -> ((Number) point.get("windowStartEpochMs")).longValue() == 0L)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(2_000L, series.get("bucketDurationMs"));
        assertEquals(10_010L, firstRolledPoint.get("latencyObservationCount"));
        assertEquals(128, firstRolledPoint.get("latencySampleCount"));
        assertEquals(100L, firstRolledPoint.get("latencyP95Ms"),
                "the ten rare slow observations must not outweigh ten thousand common observations");
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
