package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LoadRuntimeTest {
    @Test void metricsExcludeWarmupFromSlaAndKeepDropsSeparate() {
        long now = System.currentTimeMillis();
        LoadMetrics metrics = new LoadMetrics("arrivalRate", now);
        metrics.onEvent(LoadEvent.started("r", "arrivalRate", "WARMUP", "w-1", null, 1, now, now));
        metrics.onEvent(LoadEvent.completion("r", "arrivalRate", "WARMUP", "w-1", null, 1, now, now, now + 100, ResultStatus.ERROR, null));
        metrics.onEvent(LoadEvent.dropped("r", "arrivalRate", "STEADY", "d-1", 2, now + 1000, now + 1001));
        metrics.onEvent(LoadEvent.started("r", "arrivalRate", "STEADY", "s-1", null, 3, now + 1000, now + 1001));
        metrics.onEvent(LoadEvent.completion("r", "arrivalRate", "STEADY", "s-1", null, 3, now + 1000, now + 1001, now + 1101, ResultStatus.PASS, null));
        metrics.finish(now + 2000);
        LoadMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(3L, snapshot.longValue("scheduled"));
        assertEquals(1L, snapshot.longValue("dropped"));
        assertEquals(1L, snapshot.longValue("measuredCompleted"));
        assertEquals(0.0, snapshot.doubleValue("sutErrorRate"), 0.00001);
        assertEquals(1L, snapshot.longValue("runtimeError"));
        assertEquals(0L, snapshot.longValue("measuredRuntimeError"));
        assertEquals(100L, snapshot.longValue("p95Ms"));
    }

    @Test void metricsExposeStablePercentilesClassificationsAndArrivalDimensions() {
        long now = 1_700_000_000_000L;
        LoadMetrics metrics = new LoadMetrics("arrivalRate", now, 2_000L, 0, 100.0, 4);
        for (int latency = 1; latency <= 100; latency++) {
            metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "i-" + latency, null, latency,
                    now + 1_000L, now + 1_000L, now + 1_000L + latency, ResultStatus.PASS));
        }
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "sut-failure", null, 101,
                now + 1_000L, now + 1_000L, now + 1_120L, ResultStatus.FAIL, "ASSERTION", null));
        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "runtime-failure", null, 102,
                now + 1_000L, now + 1_000L, now + 1_130L, ResultStatus.ERROR, "DB_TIMEOUT", null));
        metrics.onEvent(LoadEvent.dropped("r", "arrivalRate", "STEADY", "dropped", 103, now + 1_000L, now + 1_015L));
        metrics.finish(now + 2_000L);

        LoadMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(100.0, snapshot.doubleValue("configuredArrivalRatePerSecond"), 0.00001);
        assertEquals(4L, snapshot.longValue("configuredMaxConcurrent"));
        assertEquals(103L, snapshot.longValue("scheduled"));
        assertEquals(102L, snapshot.longValue("started"));
        assertEquals(102L, snapshot.longValue("completed"));
        assertEquals(1L, snapshot.longValue("dropped"));
        assertEquals(102L, snapshot.longValue("measuredCompleted"));
        assertEquals(1L, snapshot.longValue("measuredFailure"));
        assertEquals(1L, snapshot.longValue("measuredRuntimeError"));
        assertEquals(1.0 / 102.0, snapshot.doubleValue("sutErrorRate"), 0.00001);
        assertEquals(1.0 / 102.0, snapshot.doubleValue("runtimeErrorRate"), 0.00001);
        assertEquals(51L, snapshot.longValue("p50Ms"));
        assertEquals(97L, snapshot.longValue("p95Ms"));
        assertEquals(120L, snapshot.longValue("p99Ms"));
        assertEquals(130L, snapshot.longValue("latencyMaxMs"));
        assertEquals(1L, ((Number) ((Map<?, ?>) snapshot.value("errorClassifications")).get("ASSERTION")).longValue());
        assertEquals(1L, ((Number) ((Map<?, ?>) snapshot.value("errorClassifications")).get("DB_TIMEOUT")).longValue());
        assertEquals(1, snapshot.buckets().size());
        Map<String, Object> bucket = snapshot.buckets().get(String.valueOf(now + 1_000L));
        assertNotNull(bucket);
        assertEquals("STEADY", bucket.get("phase"));
        assertEquals(97L, ((Number) bucket.get("p95Ms")).longValue());
        assertEquals(120L, ((Number) bucket.get("p99Ms")).longValue());
        assertEquals(1L, ((Number) bucket.get("dropped")).longValue());
    }

    @Test void metricsTrackClosedVusAndBoundBothLatencyAndTimeSeriesMemory() {
        long now = 1_700_100_000_000L;
        LoadMetrics metrics = new LoadMetrics("closed", now, 0L, 2, 0.0, 0);
        metrics.onEvent(LoadEvent.started("r", "closed", "STEADY", "one", "VU-1", 1, now, now));
        metrics.onEvent(LoadEvent.started("r", "closed", "STEADY", "two", "VU-2", 2, now, now + 1));
        metrics.onEvent(LoadEvent.completion("r", "closed", "STEADY", "one", "VU-1", 1, now, now, now + 10, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completion("r", "closed", "STEADY", "two", "VU-2", 2, now, now + 1, now + 20, ResultStatus.PASS));
        for (int i = 0; i < LoadMetrics.MAX_LATENCIES + 100; i++) {
            long timestamp = now + 10_000L + i * 1_000L;
            metrics.onEvent(LoadEvent.completed("r", "closed", "STEADY", "many-" + i, "VU-1", i + 3,
                    timestamp, timestamp, timestamp + 1, ResultStatus.PASS));
        }
        metrics.finish(now + (LoadMetrics.MAX_BUCKETS + 100L) * 1_000L);
        LoadMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(2L, snapshot.longValue("configuredUsers"));
        assertEquals(2L, snapshot.longValue("maxActiveVus"));
        assertEquals(0L, snapshot.longValue("activeVus"));
        assertEquals(LoadMetrics.MAX_LATENCIES, snapshot.longValue("latencySampleCapacity"));
        assertTrue(snapshot.longValue("latencySampleCount") <= LoadMetrics.MAX_LATENCIES);
        assertEquals(LoadMetrics.MAX_BUCKETS, snapshot.longValue("timeSeriesBucketCapacity"));
        assertTrue(snapshot.buckets().size() <= LoadMetrics.MAX_BUCKETS);
    }

    @Test void metricsRemainAccurateWhenEventsArePublishedConcurrently() throws Exception {
        long now = 1_700_200_000_000L;
        LoadMetrics metrics = new LoadMetrics("arrivalRate", now, 10_000L, 0, 80.0, 8);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        int perThread = 100;
        try {
            for (int thread = 0; thread < 8; thread++) {
                final int threadNumber = thread;
                executor.submit(() -> {
                    for (int index = 0; index < perThread; index++) {
                        int sequence = threadNumber * perThread + index;
                        metrics.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "parallel-" + sequence, null,
                                sequence, now, now, now + 5L, ResultStatus.PASS));
                    }
                });
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        }
        metrics.finish(now + 10_000L);
        LoadMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(800L, snapshot.longValue("scheduled"));
        assertEquals(800L, snapshot.longValue("started"));
        assertEquals(800L, snapshot.longValue("completed"));
        assertEquals(0L, snapshot.longValue("currentInFlight"));
        assertEquals(0.0, snapshot.doubleValue("sutErrorRate"), 0.00001);
        assertEquals(800L, snapshot.longValue("schedulerLagCount"));
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

    @Test void resourcePoolHonorsMinIdleAndClosesBorrowedResourcesOnShutdown() throws Exception {
        AtomicInteger created = new AtomicInteger(); AtomicInteger closed = new AtomicInteger();
        LoadResourcePool<Object> pool = new LoadResourcePool<Object>(2, 1, 50L,
                () -> { created.incrementAndGet(); return new Object(); }, value -> closed.incrementAndGet());
        assertEquals(1, pool.total());
        assertEquals(1, pool.idle());
        LoadResourcePool<Object>.Lease first = pool.borrow();
        LoadResourcePool<Object>.Lease second = pool.borrow();
        assertEquals(2, pool.active());
        pool.close();
        assertEquals(2, created.get());
        assertEquals(2, closed.get());
        assertEquals(0, pool.total());
        first.close(); second.close();
        assertEquals(2, closed.get(), "leases must not close resources twice after pool shutdown");
    }

    @Test void resourcePoolInvalidationWakesCapacityAndTracksDiscard() throws Exception {
        AtomicInteger created = new AtomicInteger(); AtomicInteger closed = new AtomicInteger();
        LoadResourcePool<Object> pool = new LoadResourcePool<Object>(1, 500L,
                () -> { created.incrementAndGet(); return new Object(); }, value -> closed.incrementAndGet());
        LoadResourcePool<Object>.Lease first = pool.borrow();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<LoadResourcePool<Object>.Lease> waiting = executor.submit(pool::borrow);
            Thread.sleep(20L);
            first.invalidate();
            LoadResourcePool<Object>.Lease replacement = waiting.get(1L, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, created.get());
            assertEquals(1, pool.discarded());
            replacement.close();
            pool.close();
            assertEquals(2, closed.get());
        } finally { executor.shutdownNow(); pool.close(); }
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

    @Test void achievedArrivalRatePercentageUsesIntegratedRampedSchedule() {
        Map<String, Object> thresholds = new LinkedHashMap<String, Object>();
        thresholds.put("achievedArrivalRate", ">= 99%");
        LoadScenario scenario = new LoadScenario(Paths.get("ramped-arrival.yaml"), "template", "LOAD_TEMPLATE", Collections.emptyMap(),
                Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 100.0, "100/s", Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO, 2, "drop", thresholds, Collections.emptyMap());
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("scheduled", 200L);
        values.put("started", 200L);
        values.put("achievedArrivalRate", 200.0 / 3.0);
        LoadThresholdSummary summary = new LoadThresholdEvaluator().evaluate(scenario,
                new LoadMetricsSnapshot(values, Collections.emptyMap()));
        assertTrue(summary.passed());
        assertEquals("100.0000%", summary.results().get(0).actual());
    }

    @Test void iterationWorkspaceNamesRemainDistinctAfterPathSanitization() {
        assertNotEquals(LoadIsolation.workspaceName("run", "vu/a", 1),
                LoadIsolation.workspaceName("run", "vu_a", 1));
        assertNotEquals(LoadIsolation.workspaceName("run", "same", 1),
                LoadIsolation.workspaceName("run", "same", 2));
    }
}
