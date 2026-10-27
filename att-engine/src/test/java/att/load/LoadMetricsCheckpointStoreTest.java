package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LoadMetricsCheckpointStoreTest {
    @TempDir Path temp;

    @Test void writesVerifiableFinalCheckpointWithExactMetricsAndIdentity() throws Exception {
        Instant started = Instant.parse("2026-10-26T00:00:00Z");
        LoadScenario scenario = new LoadScenario(Paths.get("checkpoint.yaml"), "template", "SIMPLE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 10.0, "10/s",
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                2, "drop", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, started.toEpochMilli(), 1_000L);
        metrics.enableCheckpoints(temp.resolve("load/run/checkpoints/workload-payments"), "run", "workload-payments");
        metrics.onEvent(LoadEvent.completed("run", "arrivalRate", "STEADY", "iteration-1", null, 1,
                started.toEpochMilli(), started.toEpochMilli(), started.toEpochMilli() + 10L, ResultStatus.PASS));
        metrics.finish(started.plusSeconds(1).toEpochMilli());
        metrics.finish(started.plusSeconds(2).toEpochMilli());

        Path directory = temp.resolve("load/run/checkpoints/workload-payments");
        Map<String, Object> checkpoint = LoadMetricsCheckpointStore.readLatest(directory, "run", "workload-payments");
        assertNotNull(checkpoint);
        assertEquals("FINAL_CHECKPOINT", checkpoint.get("state"));
        assertEquals(Boolean.TRUE, checkpoint.get("finalized"));
        assertEquals(1L, ((Number) checkpoint.get("sequence")).longValue());
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) checkpoint.get("payload");
        @SuppressWarnings("unchecked") Map<String, Object> snapshot = (Map<String, Object>) payload.get("metrics");
        assertEquals(1L, ((Number) snapshot.get("scheduled")).longValue());
        assertEquals(1L, ((Number) snapshot.get("started")).longValue());
        assertEquals(1L, ((Number) snapshot.get("completed")).longValue());
        assertEquals(0L, ((Number) snapshot.get("dropped")).longValue());
        assertEquals("VERIFIED", ((Map<?, ?>) snapshot.get("accounting")).get("state"));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(1L, files.filter(path -> path.getFileName().toString().endsWith(".json")).count());
        }
    }

    @Test void ignoresTamperedCheckpointAndRejectsWrongRunIdentity() throws Exception {
        Instant started = Instant.parse("2026-10-26T00:00:00Z");
        LoadMetrics metrics = new LoadMetrics("closed", started.toEpochMilli(), 1_000L);
        Path directory = temp.resolve("load/run/checkpoints/aggregate");
        metrics.enableCheckpoints(directory, "expected-run", "aggregate");
        metrics.finish(started.plusSeconds(1).toEpochMilli());

        assertNull(LoadMetricsCheckpointStore.readLatest(directory, "other-run", "aggregate"));
        Path checkpoint;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            checkpoint = files.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst()
                    .orElseThrow(IllegalStateException::new);
        }
        Files.write(checkpoint, "{\"schemaVersion\":\"att-load-metrics-checkpoint/v1\"}".getBytes(StandardCharsets.UTF_8));
        assertNull(LoadMetricsCheckpointStore.readLatest(directory, "expected-run", "aggregate"));
    }

    @Test void finalCheckpointWaitsForRunningPeriodicCaptureAndRemainsNewest() throws Exception {
        Instant started = Instant.parse("2026-10-26T00:00:00Z");
        LoadScenario scenario = new LoadScenario(Paths.get("checkpoint-race.yaml"), "template", "SIMPLE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 10.0, "10/s",
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                2, "drop", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, started.toEpochMilli(), 1_000L);
        Path directory = temp.resolve("load/run/checkpoints/aggregate");
        LoadMetricsCheckpointStore store = new LoadMetricsCheckpointStore(directory, "run", "aggregate", 1L);
        CountDownLatch periodicEntered = new CountDownLatch(1);
        CountDownLatch releasePeriodic = new CountDownLatch(1);
        CountDownLatch finishCompleted = new CountDownLatch(1);
        AtomicBoolean firstCapture = new AtomicBoolean(true);
        store.start(() -> {
            if (firstCapture.compareAndSet(true, false)) {
                periodicEntered.countDown();
                try {
                    if (!releasePeriodic.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("periodic checkpoint test timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return metrics.snapshot();
        });
        assertTrue(periodicEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        metrics.onEvent(LoadEvent.completed("run", "arrivalRate", "STEADY", "final-event", null, 1,
                started.toEpochMilli(), started.toEpochMilli(), started.toEpochMilli() + 1L, ResultStatus.PASS));
        Thread finisher = new Thread(() -> {
            store.finish(metrics::snapshot);
            finishCompleted.countDown();
        }, "checkpoint-race-finalizer");
        finisher.start();
        try {
            assertFalse(finishCompleted.await(50, java.util.concurrent.TimeUnit.MILLISECONDS),
                    "final capture must wait for the in-flight periodic capture and write");
        } finally {
            releasePeriodic.countDown();
        }
        assertTrue(finishCompleted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        finisher.join(5_000L);

        Map<String, Object> checkpoint = LoadMetricsCheckpointStore.readLatest(directory, "run", "aggregate");
        assertNotNull(checkpoint);
        assertEquals("FINAL_CHECKPOINT", checkpoint.get("state"));
        assertEquals(2L, ((Number) checkpoint.get("sequence")).longValue());
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) checkpoint.get("payload");
        @SuppressWarnings("unchecked") Map<String, Object> snapshot = (Map<String, Object>) payload.get("metrics");
        assertEquals(1L, ((Number) snapshot.get("scheduled")).longValue());
        assertEquals(1L, ((Number) snapshot.get("completed")).longValue());
    }

    @Test void checkpointWriteFailureIsRecordedWithoutPublishingAFile() throws Exception {
        Instant started = Instant.parse("2026-10-26T00:00:00Z");
        Path obstructedRoot = temp.resolve("load");
        Files.write(obstructedRoot, Collections.singletonList("not a directory"), StandardCharsets.UTF_8);
        LoadMetricsCheckpointStore store = new LoadMetricsCheckpointStore(
                obstructedRoot.resolve("run/checkpoints/aggregate"), "run", "aggregate");
        store.finish(() -> new LoadMetrics("run", started.toEpochMilli(), 1_000L).snapshot());

        assertEquals(1L, ((Number) store.status().get("writeFailures")).longValue());
        assertEquals("LOAD_CHECKPOINT_WRITE_FAILED", store.status().get("lastFailureCode"));
        assertNull(LoadMetricsCheckpointStore.readLatest(
                obstructedRoot.resolve("run/checkpoints/aggregate"), "run", "aggregate"));
    }

    @Test void snapshotsNeverObserveHalfAppliedIterationEvents() throws Exception {
        Instant started = Instant.parse("2026-10-26T00:00:00Z");
        LoadScenario scenario = new LoadScenario(Paths.get("snapshot.yaml"), "template", "SIMPLE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 10.0, "10/s",
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                4, "drop", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, started.toEpochMilli(), 1_000L);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int worker = 0; worker < 4; worker++) {
            final int workerId = worker;
            futures.add(workers.submit(() -> {
                release.await();
                for (int index = 0; index < 250; index++) {
                    long sequence = workerId * 250L + index + 1L;
                    metrics.onEvent(LoadEvent.completed("snapshot-run", "arrivalRate", "STEADY",
                            "iteration-" + sequence, null, sequence, started.toEpochMilli(),
                            started.toEpochMilli(), started.toEpochMilli() + 1L, ResultStatus.PASS));
                }
                return null;
            }));
        }
        release.countDown();
        try {
            while (futures.stream().anyMatch(future -> !future.isDone())) {
                LoadMetricsSnapshot snapshot = metrics.snapshot();
                assertEquals(snapshot.longValue("scheduled"), snapshot.longValue("started"));
                assertEquals(snapshot.longValue("started"), snapshot.longValue("completed"));
                assertEquals(snapshot.longValue("completed"), snapshot.longValue("success"));
            }
            for (Future<?> future : futures) future.get();
            LoadMetricsSnapshot snapshot = metrics.snapshot();
            assertEquals(1_000L, snapshot.longValue("completed"));
            assertEquals(1_000L, snapshot.longValue("success"));
        } finally {
            workers.shutdownNow();
        }
    }
}
