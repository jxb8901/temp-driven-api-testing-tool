package att.load;

import att.TestSchemas;
import att.core.ResultStatus;
import att.testdata.TestdataInputResolver;
import att.testdata.TestdataRegistry;
import att.testdata.TestdataStopException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedArrivalRateSchedulerTest {
    @TempDir Path testdataRoot;

    @Test
    void absoluteArrivalPlanUsesDeterministicPhaseAreas() {
        LoadScenario scenario = scenario(100.0, 0L, 1000L, 1000L, 1000L, 4);

        assertEquals(0.0, FixedArrivalRateScheduler.cumulativeArrivals(scenario, 0L), 0.000001);
        assertEquals(50.0, FixedArrivalRateScheduler.cumulativeArrivals(scenario, 1000L), 0.000001);
        assertEquals(150.0, FixedArrivalRateScheduler.cumulativeArrivals(scenario, 2000L), 0.000001);
        assertEquals(200.0, FixedArrivalRateScheduler.cumulativeArrivals(scenario, 3000L), 0.000001);
        assertEquals(1L, FixedArrivalRateScheduler.arrivalsDueAt(scenario, 0L));
        assertEquals(51L, FixedArrivalRateScheduler.arrivalsDueAt(scenario, 1000L));
        assertEquals(151L, FixedArrivalRateScheduler.arrivalsDueAt(scenario, 2000L));
        assertEquals(200L, FixedArrivalRateScheduler.arrivalsBeforeDeadline(scenario));
        assertEquals(3000L, FixedArrivalRateScheduler.plannedDue(scenario, 3000L, 1L));
        assertEquals(3000L, FixedArrivalRateScheduler.plannedDue(scenario, 0L, 201L));
        assertTrue(FixedArrivalRateScheduler.plannedDue(scenario, 0L, 1L)
                < FixedArrivalRateScheduler.plannedDue(scenario, 0L, 2L));
    }

    @Test
    void capDropsDueArrivalsWithoutFakeVirtualUsers() throws Exception {
        LoadScenario scenario = scenario(1000.0, 0L, 0L, 10L, 0L, 1);
        FakeTiming fake = new FakeTiming();
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        List<IterationRequest> requests = Collections.synchronizedList(new ArrayList<IterationRequest>());
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        LoadIterationRunner runner = request -> {
            requests.add(request);
            if (first.getAndSet(false)) {
                try { releaseFirst.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return result(request.iterationId());
        };
        FixedArrivalRateScheduler scheduler = new FixedArrivalRateScheduler(scenario, runner, "run-26",
                event -> { events.add(event); if (event.dropped()) releaseFirst.countDown(); }, fake.timing());

        LoadRunResult result = scheduler.run();

        assertTrue(result.metrics().longValue("dropped") > 0L);
        assertEquals(1L, result.metrics().longValue("maxInFlight"));
        assertEquals(0L, result.metrics().longValue("currentInFlight"));
        assertTrue(requests.size() > 0);
        Set<String> iterationIds = new HashSet<String>();
        for (IterationRequest request : requests) {
            assertTrue(request.userId() == null, "arrival-rate must not create a VU identity");
            assertTrue(iterationIds.add(request.iterationId()), "iteration IDs must be unique");
        }
        assertTrue(events.stream().anyMatch(LoadEvent::dropped));
        assertFalse(events.stream().anyMatch(event -> event.dropped() && event.started()));
    }

    @Test
    void droppedArrivalsDoNotConsumeIterationScopedTestdataOrdinals() throws Exception {
        TestSchemas.install(testdataRoot);
        Path descriptor = testdataRoot.resolve("data/arrival.yaml");
        Files.createDirectories(descriptor.getParent());
        Files.write(descriptor, ("schemaVersion: att-testdata/v1.0\n"
                + "id: arrival\n"
                + "records: [A, B]\n"
                + "selection: {strategy: sequential, exhaustion: stop}\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TestdataRegistry registry = new TestdataRegistry(testdataRoot, Collections.<Path>emptyList(),
                Collections.singletonList(descriptor));
        Map<String, Object> policy = Collections.<String, Object>singletonMap("arrival",
                Collections.<String, Object>singletonMap("scope", "iteration"));
        TestdataInputResolver resolver = new TestdataInputResolver(registry, policy, "work", "arrivalRate",
                null, 1, true);
        Map<String, Object> mapping = Collections.<String, Object>singletonMap("value", "@{arrival}");

        LoadScenario scenario = scenario(1000.0, 0L, 0L, 20L, 0L, 1);
        FakeTiming fake = new FakeTiming();
        List<IterationRequest> requests = Collections.synchronizedList(new ArrayList<IterationRequest>());
        List<String> selected = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstCompleted = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicInteger exhausted = new AtomicInteger();
        AtomicReference<FixedArrivalRateScheduler> schedulerRef = new AtomicReference<FixedArrivalRateScheduler>();
        LoadIterationRunner runner = request -> {
            requests.add(request);
            try {
                Object value = resolver.resolve(mapping, null, null, request.iterationId(),
                        request.testdataOrdinal(), () -> true).get("value");
                selected.add(String.valueOf(value));
                if (first.getAndSet(false)) {
                    try { releaseFirst.await(); }
                    finally { firstCompleted.countDown(); }
                }
                return result(request.iterationId());
            } catch (TestdataStopException stop) {
                exhausted.incrementAndGet();
                FixedArrivalRateScheduler scheduler = schedulerRef.get();
                if (scheduler != null) scheduler.cancel();
                return result(request.iterationId());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        };
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        FixedArrivalRateScheduler scheduler = new FixedArrivalRateScheduler(scenario, runner, "arrival-dense",
                event -> {
                    events.add(event);
                    if (event.dropped()) {
                        releaseFirst.countDown();
                        try { firstCompleted.await(2L, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                }, fake.cooperativeTiming());
        schedulerRef.set(scheduler);

        scheduler.run();

        assertTrue(events.stream().anyMatch(LoadEvent::dropped));
        assertEquals(Arrays.asList("A", "B"), selected);
        assertEquals(1, exhausted.get());
        assertTrue(requests.size() >= 3);
        assertEquals(Long.valueOf(0L), requests.get(0).testdataOrdinal());
        assertEquals(Long.valueOf(1L), requests.get(1).testdataOrdinal());
        assertTrue(requests.stream().anyMatch(request -> Long.valueOf(2L).equals(request.testdataOrdinal())));
        assertTrue(requests.get(1).iteration() > requests.get(0).iteration(),
                "admitted testdata ordinal must be independent of planned arrival sequence");
    }

    @Test
    void responseTimeDoesNotChangeAbsoluteDueTimesWhileCapacityIsAvailable() throws Exception {
        LoadScenario scenario = scenario(100.0, 0L, 0L, 50L, 0L, 5);
        FakeTiming fake = new FakeTiming();
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadIterationRunner runner = request -> {
            fake.advance(3L);
            return result(request.iterationId());
        };
        LoadRunResult result = new FixedArrivalRateScheduler(scenario, runner, "run-26-latency", events::add, fake.timing()).run();

        List<LoadEvent> completed = new ArrayList<LoadEvent>();
        for (LoadEvent event : events) if (event.completed()) completed.add(event);
        assertTrue(completed.size() >= 4);
        Collections.sort(completed, Comparator.comparingLong(LoadEvent::sequence));
        for (int index = 1; index < completed.size(); index++) {
            assertEquals(FixedArrivalRateScheduler.plannedDue(scenario, 0L, completed.get(index).sequence()),
                    completed.get(index).scheduledAtEpochMs());
        }
        assertEquals(100.0, result.metrics().doubleValue("achievedArrivalRate"), 0.000001);
        assertTrue(completed.get(completed.size() - 1).scheduledAtEpochMs() >= completed.get(0).scheduledAtEpochMs());
    }

    @Test
    void overlappingIterationsPublishSeparateStartAndCompletionEvents() throws Exception {
        LoadScenario scenario = scenario(1000.0, 0L, 0L, 10L, 0L, 2);
        FakeTiming fake = new FakeTiming();
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        LoadIterationRunner runner = request -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return result(request.iterationId());
        };
        FixedArrivalRateScheduler scheduler = new FixedArrivalRateScheduler(scenario, runner, "run-26-overlap",
                events::add, fake.timing());
        ExecutorService control = Executors.newSingleThreadExecutor();
        Future<LoadRunResult> future = control.submit(scheduler::run);
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS));
            List<LoadEvent> observed;
            synchronized (events) { observed = new ArrayList<LoadEvent>(events); }
            long startedEvents = observed.stream().filter(LoadEvent::started).count();
            assertTrue(startedEvents >= 2L, "both overlapping iterations must publish START events");
            assertTrue(observed.stream().noneMatch(event -> event.started() && event.completed()),
                    "START and completion must be separate events");
            release.countDown();
            LoadRunResult run = future.get(2L, TimeUnit.SECONDS);
            assertTrue(run.metrics().longValue("maxInFlight") > 1L);
            assertEquals(0L, run.metrics().longValue("currentInFlight"));
        } finally {
            release.countDown();
            scheduler.close();
            control.shutdownNow();
            control.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectedSubmissionDuringCancellationRollsBackAdmission() throws Exception {
        LoadScenario scenario = scenario(1000.0, 0L, 0L, 1000L, 0L, 1);
        FakeTiming fake = new FakeTiming();
        CountDownLatch beforeSubmit = new CountDownLatch(1);
        CountDownLatch allowSubmit = new CountDownLatch(1);
        Runnable hook = () -> {
            beforeSubmit.countDown();
            try { allowSubmit.await(2L, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        FixedArrivalRateScheduler scheduler = new FixedArrivalRateScheduler(scenario, request -> result(request.iterationId()),
                "run-26-submit-race", null, fake.timing(false), hook);
        ExecutorService control = Executors.newSingleThreadExecutor();
        Future<LoadRunResult> future = control.submit(scheduler::run);
        try {
            assertTrue(beforeSubmit.await(2L, TimeUnit.SECONDS));
            scheduler.cancel();
            allowSubmit.countDown();
            LoadRunResult run = future.get(2L, TimeUnit.SECONDS);
            assertEquals(0L, run.metrics().longValue("currentInFlight"));
        } finally {
            allowSubmit.countDown();
            scheduler.close();
            control.shutdownNow();
            control.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancellationInterruptsAnActiveIterationAndStopsSchedulerWorkers() throws Exception {
        LoadScenario scenario = scenario(1000.0, 0L, 0L, 60000L, 0L, 1);
        FakeTiming fake = new FakeTiming();
        CountDownLatch entered = new CountDownLatch(1);
        LoadIterationRunner runner = request -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return result(request.iterationId());
        };
        FixedArrivalRateScheduler scheduler = new FixedArrivalRateScheduler(scenario, runner, "run-26-cancel",
                null, fake.timing(false));
        ExecutorService control = Executors.newSingleThreadExecutor();
        Future<LoadRunResult> future = control.submit(scheduler::run);
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS));
            scheduler.cancel();
            future.get(2L, TimeUnit.SECONDS);
        } finally {
            scheduler.close();
            control.shutdownNow();
            control.awaitTermination(2L, TimeUnit.SECONDS);
        }
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse(thread.isAlive() && thread.getName().startsWith("att-load-arrival-"),
                    "scheduler worker leaked: " + thread.getName());
        }
    }

    private static LoadScenario scenario(double rate, long warmupMs, long rampUpMs, long durationMs,
                                         long rampDownMs, int maxConcurrent) {
        return new LoadScenario(Paths.get("issue-26.yaml"), "flow", "common.compose.v1",
                Collections.emptyMap(), Collections.<String, Object>singletonMap("source", "issue-26"),
                LoadScenario.Model.ARRIVAL_RATE, 0, rate, rate + "/s", Duration.ofMillis(warmupMs),
                Duration.ofMillis(rampUpMs), Duration.ofMillis(durationMs), Duration.ofMillis(rampDownMs),
                Duration.ZERO, maxConcurrent, "drop", Collections.emptyMap(), Collections.emptyMap());
    }

    private static IterationResult result(String iterationId) {
        return new IterationResult(iterationId, ResultStatus.PASS, Duration.ofMillis(1L), null,
                Collections.emptyList(), null, null);
    }

    private static final class FakeTiming {
        private final AtomicLong now = new AtomicLong();

        LoadSchedulerTiming timing() {
            return timing(true);
        }

        LoadSchedulerTiming timing(boolean advance) {
            return new LoadSchedulerTiming(now::get, millis -> {
                if (advance && millis > 0L) now.addAndGet(millis);
                Thread.yield();
            });
        }

        LoadSchedulerTiming cooperativeTiming() {
            return new LoadSchedulerTiming(now::get, millis -> {
                if (millis > 0L) now.addAndGet(millis);
                Thread.sleep(1L);
            });
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }
}
