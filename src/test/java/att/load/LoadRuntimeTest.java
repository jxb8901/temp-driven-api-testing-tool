package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadRuntimeTest {
    @Test void metricsExcludeWarmupFromSlaAndKeepDropsSeparate() {
        long now = System.currentTimeMillis();
        LoadMetrics metrics = new LoadMetrics("arrivalRate", now);
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "WARMUP", "w-1", null, 1, now, now, now + 100, ResultStatus.ERROR));
        metrics.onEvent(LoadEvent.dropped("r", "arrivalRate", "STEADY", "d-1", 2, now + 1000, now + 1001));
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "s-1", null, 3, now + 1000, now + 1001, now + 1101, ResultStatus.PASS));
        metrics.finish(now + 2000);
        LoadMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(3L, snapshot.longValue("scheduled"));
        assertEquals(1L, snapshot.longValue("dropped"));
        assertEquals(1L, snapshot.longValue("measuredCompleted"));
        assertEquals(0.0, snapshot.doubleValue("sutErrorRate"), 0.00001);
        assertEquals(100L, snapshot.longValue("p95Ms"));
    }

    @Test void resourcePoolIsBoundedAndClassifiesBorrowTimeout() throws Exception {
        AtomicInteger created = new AtomicInteger(); AtomicInteger closed = new AtomicInteger();
        LoadResourcePool<Object> pool = new LoadResourcePool<Object>(1, 5L, () -> { created.incrementAndGet(); return new Object(); }, value -> closed.incrementAndGet());
        LoadResourcePool<Object>.Lease first = pool.borrow();
        assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
        assertEquals(1, pool.total()); assertEquals(1, pool.timeoutCount());
        first.close();
        LoadResourcePool<Object>.Lease second = pool.borrow(); assertNotNull(second.value()); second.invalidate();
        pool.close();
        assertEquals(1, created.get()); assertEquals(1, closed.get());
    }

    @Test void evidenceDoesNotMisclassifyDroppedArrivalsAsSutFailures() {
        long now = System.currentTimeMillis();
        LoadEvidencePolicy policy = new LoadEvidencePolicy(LoadEvidencePolicy.Success.NONE, LoadEvidencePolicy.Failure.FULL, 0.0, 10);
        LoadEvidenceStore store = new LoadEvidenceStore(policy);
        store.onEvent(LoadEvent.dropped("r", "arrivalRate", "STEADY", "dropped-1", 1, now, now + 1));
        store.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "failed-1", null, 2, now, now, now + 1, ResultStatus.FAIL));
        assertEquals(1, store.events().size());
        assertEquals("failed-1", store.events().get(0).iterationId());
    }

    @Test void achievedArrivalRatePercentageIsComparedWithConfiguredRate() {
        Map<String, Object> thresholds = new LinkedHashMap<String, Object>();
        thresholds.put("achievedArrivalRate", ">= 99%");
        LoadScenario scenario = new LoadScenario(Paths.get("arrival.yaml"), "template", "LOAD_TEMPLATE", Collections.emptyMap(),
                Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 100.0, "100/s", Duration.ZERO,
                Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, 1, "drop", thresholds, Collections.emptyMap());
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("achievedArrivalRate", 99.0);
        values.put("runtimeError", 0L);
        LoadThresholdSummary summary = new LoadThresholdEvaluator().evaluate(scenario,
                new LoadMetricsSnapshot(values, Collections.emptyMap()));
        assertTrue(summary.passed());
        assertEquals("99.0000%", summary.results().get(0).actual());
    }

    @Test void iterationWorkspaceNamesRemainDistinctAfterPathSanitization() {
        assertNotEquals(LoadIsolation.workspaceName("run", "vu/a", 1),
                LoadIsolation.workspaceName("run", "vu_a", 1));
        assertNotEquals(LoadIsolation.workspaceName("run", "same", 1),
                LoadIsolation.workspaceName("run", "same", 2));
    }
}
