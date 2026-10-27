package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in deterministic stress fixture; it does not replace the real-time Engine/Worker soak gate. */
class LoadMetricsHighIterationSoakTest {
    private static final long ITERATIONS = 9_000_000L;
    private static final long MEASUREMENT_MS = Duration.ofHours(25).toMillis();

    @Test
    @EnabledIfSystemProperty(named = "att.load.soak.accelerated", matches = "true")
    void retainsBoundedMetricsAcrossNineMillionCompletedSyntheticIterations() {
        Instant started = Instant.parse("2026-10-26T00:00:00Z");
        long startMs = started.toEpochMilli();
        LoadScenario scenario = new LoadScenario(Paths.get("accelerated-soak.yaml"), "template", "SOAK_NOOP",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 100.0, "100/s",
                Duration.ZERO, Duration.ZERO, Duration.ofHours(25), Duration.ZERO, Duration.ZERO,
                256, "drop", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, startMs, MEASUREMENT_MS);
        for (long sequence = 1L; sequence <= ITERATIONS; sequence++) {
            long scheduledAt = startMs + Math.multiplyExact(sequence - 1L, MEASUREMENT_MS) / ITERATIONS;
            metrics.onEvent(LoadEvent.completed("accelerated-soak", "arrivalRate", "STEADY", "synthetic",
                    null, sequence, scheduledAt, scheduledAt, scheduledAt + 1L, ResultStatus.PASS));
        }
        metrics.finish(startMs + MEASUREMENT_MS);
        LoadMetricsSnapshot snapshot = metrics.snapshot();

        assertEquals(ITERATIONS, snapshot.longValue("scheduled"));
        assertEquals(ITERATIONS, snapshot.longValue("started"));
        assertEquals(ITERATIONS, snapshot.longValue("completed"));
        assertEquals(ITERATIONS, snapshot.longValue("success"));
        assertEquals(0L, snapshot.longValue("dropped"));
        assertEquals(100.0, snapshot.doubleValue("completedThroughput"), 0.001);
        assertTrue(snapshot.buckets().size() <= LoadMetrics.MAX_BUCKETS);
        assertTrue(snapshot.longValue("timeSeriesBucketsEvicted") > 0L);
        @SuppressWarnings("unchecked") Map<String, Object> windowMetrics =
                (Map<String, Object>) snapshot.value("windowMetrics");
        @SuppressWarnings("unchecked") Map<String, Object> timeSeries =
                (Map<String, Object>) windowMetrics.get("timeSeries");
        @SuppressWarnings("unchecked") List<?> points = (List<?>) timeSeries.get("points");
        assertTrue(points.size() <= 2048);
        assertEquals("Whole execution; uniform power-of-two roll-up, no historical eviction", timeSeries.get("coverage"));
        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) points.get(0);
        @SuppressWarnings("unchecked") Map<String, Object> last = (Map<String, Object>) points.get(points.size() - 1);
        assertEquals(startMs, ((Number) first.get("windowStartEpochMs")).longValue());
        assertTrue(((Number) last.get("windowStartEpochMs")).longValue()
                + ((Number) last.get("durationMs")).longValue() >= startMs + MEASUREMENT_MS);
    }
}
