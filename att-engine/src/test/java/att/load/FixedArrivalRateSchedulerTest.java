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
import java.util.concurrent.ThreadPoolExecutor;
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
    void rejectedSubmissionIsRetainedAsAClassifiedDroppedArrival() throws Exception {
        LoadScenario scenario = scenario(10.0, 0L, 0L, 500L, 0L, 1);
        FakeTiming fake = new FakeTiming();
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadRunResult result = new FixedArrivalRateScheduler(scenario, request -> result(request.iterationId()),
                "forced-rejection", events::add, fake.timing(),
                () -> { throw new java.util.concurrent.RejectedExecutionException("forced for accounting test"); })
                .run();

        assertEquals(FixedArrivalRateScheduler.arrivalsBeforeDeadline(scenario), result.metrics().longValue("dropped"));
        assertEquals(result.metrics().longValue("dropped"), events.stream().filter(LoadEvent::dropped).count());
        assertTrue(events.stream().filter(LoadEvent::dropped)
                .allMatch(event -> "SUBMISSION_REJECTED".equals(event.errorType())));
        @SuppressWarnings("unchecked") Map<String, Object> dropClassifications =
                (Map<String, Object>) result.metrics().value("dropClassifications");
        assertEquals(result.metrics().longValue("dropped"),
                ((Number) dropClassifications.get("SUBMISSION_REJECTED")).longValue());
        assertEquals(0L, result.metrics().longValue("currentInFlight"));
    }

    @Test void oneSchedulerExceptionRetainsClassifiedDiagnosticWithoutCaseLog() throws Exception {
        LoadScenario scenario=scenario(10,0,0,500,0,1);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        LoadRunResult result=new FixedArrivalRateScheduler(scenario,request -> {
            if(calls.incrementAndGet()==2)throw new IllegalArgumentException("MQ put inhibited password=secret /private/host/path");
            return result(request.iterationId());
        },"one-error",null,LoadSchedulerTiming.system()).run();
        assertEquals(ResultStatus.ERROR,result.status());
        assertEquals(1,result.metrics().longValue("runtimeError"));assertEquals(1,result.metrics().longValue("measuredRuntimeError"));
        String samples=att.validation.JsonSupport.write(result.metrics().value("failureDiagnostics"));
        assertTrue(samples.contains("IllegalArgumentException"));assertTrue(samples.contains("MQ put inhibited"));
        assertFalse(samples.contains("password=secret"));assertFalse(samples.contains("/private/host"));
    }

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
    void fourHourMinuteRateReproducerExcludesTheExactGenerationDeadline() throws Exception {
        // The configured minute rate is exact, but converting it to a double per-second rate
        // produces 0.9600000000000001 and used to ceil the final area to 13,825 arrivals.
        LoadScenario scenario = scenario(57.6 / 60.0, "57.6/m", 0L, 0L, 14_400_000L, 0L, 16,
                Collections.<String, Object>singletonMap("achievedArrivalRate", ">= 100%"));
        long generationEnd = LoadPhase.totalMs(scenario);
        assertEquals(13_824.0, FixedArrivalRateScheduler.cumulativeArrivals(scenario, generationEnd), 0.0);
        assertEquals(13_824L, FixedArrivalRateScheduler.arrivalsBeforeDeadline(scenario));
        assertEquals(13_824L, FixedArrivalRateScheduler.arrivalsDueAt(scenario, generationEnd));
        assertEquals(generationEnd, FixedArrivalRateScheduler.plannedDue(scenario, 0L, 13_825L));
        for (long sequence = 1L; sequence <= 13_824L; sequence++) {
            assertTrue(FixedArrivalRateScheduler.plannedDue(scenario, 0L, sequence) < generationEnd,
                    "valid arrival " + sequence + " must be before the exclusive end");
        }

        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadRunResult result = new FixedArrivalRateScheduler(scenario, request -> {
            assertTrue("WARMUP".equals(request.phase()) || "RAMP_UP".equals(request.phase())
                    || "STEADY".equals(request.phase()) || "RAMP_DOWN".equals(request.phase()));
            return result(request.iterationId());
        }, "four-hour-boundary", events::add, new FakeTiming().timing()).run();

        assertEquals(ResultStatus.PASS, result.status());
        assertEquals("PASS", result.executionStatus());
        assertEquals(0L, result.metrics().longValue("failure"));
        assertEquals(0L, result.metrics().longValue("runtimeError"));
        assertEquals(13_824L, result.metrics().longValue("scheduled"));
        assertEquals(13_824L, result.metrics().longValue("started"));
        assertEquals(13_824L, result.metrics().longValue("completed"));
        assertEquals(0L, result.metrics().longValue("dropped"));
        assertEquals(0L, result.metrics().longValue("currentInFlight"));
        result = result.withThresholds(new LoadThresholdEvaluator().evaluate(scenario, result.metrics()));
        assertEquals(ResultStatus.PASS, result.status());
        assertEquals("PASS", result.executionStatus());
        assertEquals("MET", result.performanceVerdict());
        assertFalse(events.stream().anyMatch(event -> event.phase() == null || "COMPLETE".equals(event.phase())
                || event.scheduledAtEpochMs() >= generationEnd));
    }

    @Test
    void halfOpenArrivalCountsStayConsistentAcrossRatesPhasesAndWorkloadViews() {
        List<LoadScenario> scenarios = new ArrayList<LoadScenario>();
        scenarios.add(scenario(0.1, "0.1/s", 0L, 0L, 10_001L, 0L, 4));
        scenarios.add(scenario(0.96, "0.96/s", 0L, 0L, 14_400_000L, 0L, 4));
        scenarios.add(scenario(1.0, "1/s", 0L, 0L, 1_000L, 0L, 4));
        scenarios.add(scenario(1.0, "1/s", 0L, 0L, 1_001L, 0L, 4));
        scenarios.add(scenario(1.5, "1.5/s", 0L, 0L, 1_001L, 0L, 4));
        scenarios.add(scenario(10.1, "10.1/s", 3L, 101L, 997L, 1L, 4));
        scenarios.add(scenario(7.3, "7.3/s", 17L, 113L, 89L, 71L, 4));

        LoadWorkload first = arrivalWorkload("first", 57.6 / 60.0, "57.6/m", 14_400_000L);
        LoadWorkload second = arrivalWorkload("second", 10.1, "10.1/s", 10_001L);
        LoadScenario multi = new LoadScenario(Paths.get("multi-boundary.yaml"), "att-load/v1.9",
                Arrays.asList(first, second), null, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap());
        for (LoadWorkload workload : multi.workloads()) scenarios.add(multi.forWorkload(workload));

        for (LoadScenario scenario : scenarios) {
            long end = LoadPhase.totalMs(scenario);
            long count = FixedArrivalRateScheduler.arrivalsBeforeDeadline(scenario);
            assertEquals(count, FixedArrivalRateScheduler.arrivalsDueAt(scenario, end), scenario.arrivalRate());
            assertEquals(count, FixedArrivalRateScheduler.arrivalsDueAt(scenario, end + 1L), scenario.arrivalRate());
            for (long sequence = 1L; sequence <= count; sequence++) {
                long due = FixedArrivalRateScheduler.plannedDue(scenario, 500L, sequence) - 500L;
                assertTrue(due >= 0L && due < end, scenario.arrivalRate() + " sequence " + sequence);
                assertTrue(LoadPhase.at(scenario, due) != LoadPhase.COMPLETE,
                        scenario.arrivalRate() + " sequence " + sequence);
                if (due > 0L) assertTrue(FixedArrivalRateScheduler.arrivalsDueAt(scenario, due - 1L) < sequence,
                        "inverse must not move a valid arrival earlier than its cumulative threshold");
            }
            if (count < Long.MAX_VALUE)
                assertTrue(FixedArrivalRateScheduler.plannedDue(scenario, 0L, count + 1L) >= end,
                        "next arrival must be at or after the exclusive end for " + scenario.arrivalRate());
        }
    }

    @Test
    void admittedArrivalCanCompleteDuringDrainWithoutASecondAdmission() throws Exception {
        LoadScenario scenario = scenario(1.0, "1/s", 0L, 0L, 1_000L, 0L, 1);
        AtomicLong now = new AtomicLong(10_000L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch crossedDeadline = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LoadSchedulerTiming timing = new LoadSchedulerTiming(now::get, millis -> {
            now.addAndGet(millis + 5L);
            crossedDeadline.countDown();
        });
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        FixedArrivalRateScheduler scheduler = new FixedArrivalRateScheduler(scenario, request -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return result(request.iterationId());
        }, "drain-boundary", events::add, timing);
        ExecutorService control = Executors.newSingleThreadExecutor();
        Future<LoadRunResult> future = control.submit(scheduler::run);
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS));
            assertTrue(crossedDeadline.await(2L, TimeUnit.SECONDS));
            release.countDown();
            LoadRunResult result = future.get(5L, TimeUnit.SECONDS);
            assertEquals(1L, result.metrics().longValue("scheduled"));
            assertEquals(1L, result.metrics().longValue("started"));
            assertEquals(1L, result.metrics().longValue("completed"));
            assertEquals(0L, result.metrics().longValue("runtimeError"));
            assertEquals(0L, result.metrics().longValue("currentInFlight"));
            LoadEvent completion = events.stream().filter(LoadEvent::completed).findFirst().orElseThrow(AssertionError::new);
            assertEquals("STEADY", completion.phase());
            assertTrue(completion.scheduledAtEpochMs() < 11_000L);
            assertTrue(completion.completedAtEpochMs() > 11_000L);
            @SuppressWarnings("unchecked") Map<String, Object> windowMetrics =
                    (Map<String, Object>) result.metrics().value("windowMetrics");
            @SuppressWarnings("unchecked") Map<String, Object> windows = (Map<String, Object>) windowMetrics.get("windows");
            @SuppressWarnings("unchecked") Map<String, Object> drain = (Map<String, Object>) windows.get("DRAIN");
            assertEquals(0L, drain.get("scheduled"));
            assertEquals(1L, drain.get("completed"));
            assertEquals(1L, drain.get("successful"));
        } finally {
            release.countDown();
            scheduler.close();
            control.shutdownNow();
            control.awaitTermination(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void directArrivalDeadlinesMatchThePhaseIntegralAcrossEveryPhase() {
        LoadScenario scenario = scenario(7.3, 17L, 113L, 89L, 71L, 4);
        long start = 5000L;
        long expectedArrivals = FixedArrivalRateScheduler.arrivalsBeforeDeadline(scenario);
        for (long sequence = 1L; sequence <= expectedArrivals; sequence++) {
            long expectedOffset = referenceDueOffset(scenario, sequence);
            assertEquals(start + expectedOffset,
                    FixedArrivalRateScheduler.plannedDue(scenario, start, sequence),
                    "sequence " + sequence);
        }
    }

    private static long referenceDueOffset(LoadScenario scenario, long sequence) {
        double target = sequence - 1.0;
        long low = 0L;
        long high = LoadPhase.totalMs(scenario);
        while (low < high) {
            long middle = low + (high - low) / 2L;
            if (FixedArrivalRateScheduler.cumulativeArrivals(scenario, middle) >= target) high = middle;
            else low = middle + 1L;
        }
        return low;
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

    @Test
    void sharedWorkerCancellationDrainsAdmittedArrivalIterationsBeforeReturning() throws Exception {
        LoadScenario scenario = scenario(1000.0, 0L, 0L, 10_000L, 0L, 2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadIterationRunner runner = request -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException cancelled) { interrupted.countDown(); }
            return result(request.iterationId());
        };
        FixedArrivalRateScheduler scheduler = new FixedArrivalRateScheduler(scenario, runner, "run-26-shared-cancel",
                events::add, LoadSchedulerTiming.system());
        ThreadPoolExecutor sharedWorkers = LoadWorkerPool.create(1, 2,
                runnable -> new Thread(runnable, "shared-arrival-test-worker"));
        assertEquals(0, sharedWorkers.getPoolSize(), "the shared pool must start lazily");
        scheduler.useSharedWorkers(sharedWorkers);
        ExecutorService control = Executors.newSingleThreadExecutor();
        Future<LoadRunResult> future = control.submit(scheduler::run);
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS), "both admitted arrivals should enter the target");
            scheduler.cancel();
            assertTrue(interrupted.await(5L, TimeUnit.SECONDS), "cancellation must interrupt admitted arrival executions");
            LoadRunResult result = future.get(10L, TimeUnit.SECONDS);
            assertEquals(2L, result.metrics().longValue("completed"));
            assertEquals(2L, completionCount(events), "all admitted completions must be included before return");
            assertFalse(sharedWorkers.isShutdown(), "a child scheduler must not shut down the coordinator's pool");
        } finally {
            release.countDown();
            scheduler.close();
            control.shutdownNow();
            control.awaitTermination(10L, TimeUnit.SECONDS);
            sharedWorkers.shutdownNow();
            sharedWorkers.awaitTermination(10L, TimeUnit.SECONDS);
        }
    }

    private static long completionCount(List<LoadEvent> events) {
        synchronized (events) { return events.stream().filter(LoadEvent::completed).count(); }
    }

    private static LoadScenario scenario(double rate, long warmupMs, long rampUpMs, long durationMs,
                                         long rampDownMs, int maxConcurrent) {
        return scenario(rate, rate + "/s", warmupMs, rampUpMs, durationMs, rampDownMs, maxConcurrent);
    }

    private static LoadScenario scenario(double rate, String rateText, long warmupMs, long rampUpMs, long durationMs,
                                         long rampDownMs, int maxConcurrent) {
        return scenario(rate, rateText, warmupMs, rampUpMs, durationMs, rampDownMs, maxConcurrent,
                Collections.<String, Object>emptyMap());
    }

    private static LoadScenario scenario(double rate, String rateText, long warmupMs, long rampUpMs, long durationMs,
                                         long rampDownMs, int maxConcurrent, Map<String, Object> thresholds) {
        return new LoadScenario(Paths.get("issue-26.yaml"), "flow", "common.compose.v1",
                Collections.emptyMap(), Collections.<String, Object>singletonMap("source", "issue-26"),
                LoadScenario.Model.ARRIVAL_RATE, 0, rate, rateText, Duration.ofMillis(warmupMs),
                Duration.ofMillis(rampUpMs), Duration.ofMillis(durationMs), Duration.ofMillis(rampDownMs),
                Duration.ZERO, maxConcurrent, "drop", thresholds, Collections.emptyMap());
    }

    private static LoadWorkload arrivalWorkload(String id, double rate, String rateText, long durationMs) {
        return new LoadWorkload(id, "flow", "common.compose.v1", Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                LoadScenario.Model.ARRIVAL_RATE, 0, rate, rateText, Duration.ZERO, Duration.ZERO,
                Duration.ofMillis(durationMs), Duration.ZERO, ThinkTimePolicy.fixed(Duration.ZERO), 4, "drop",
                Collections.<String, Object>emptyMap());
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
