package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosedVuSchedulerTest {
    @Test
    void phaseBoundariesAndLinearConcurrencyAreDeterministic() {
        LoadScenario scenario = scenario(4, 10L, 20L, 30L, 10L, 0L);

        assertEquals(LoadPhase.WARMUP, LoadPhase.at(scenario, 0L));
        assertEquals(LoadPhase.WARMUP, LoadPhase.at(scenario, 9L));
        assertEquals(LoadPhase.RAMP_UP, LoadPhase.at(scenario, 10L));
        assertEquals(LoadPhase.RAMP_UP, LoadPhase.at(scenario, 29L));
        assertEquals(LoadPhase.STEADY, LoadPhase.at(scenario, 30L));
        assertEquals(LoadPhase.STEADY, LoadPhase.at(scenario, 59L));
        assertEquals(LoadPhase.RAMP_DOWN, LoadPhase.at(scenario, 60L));
        assertEquals(LoadPhase.RAMP_DOWN, LoadPhase.at(scenario, 69L));
        assertEquals(LoadPhase.COMPLETE, LoadPhase.at(scenario, 70L));

        assertEquals(4, ClosedVuScheduler.activeUsers(scenario, 0L));
        assertEquals(1, ClosedVuScheduler.activeUsers(scenario, 10L));
        assertEquals(2, ClosedVuScheduler.activeUsers(scenario, 20L));
        assertEquals(4, ClosedVuScheduler.activeUsers(scenario, 29L));
        assertEquals(4, ClosedVuScheduler.activeUsers(scenario, 30L));
        assertEquals(4, ClosedVuScheduler.activeUsers(scenario, 60L));
        assertEquals(2, ClosedVuScheduler.activeUsers(scenario, 65L));
        assertEquals(1, ClosedVuScheduler.activeUsers(scenario, 69L));
        assertEquals(0, ClosedVuScheduler.activeUsers(scenario, 70L));
        assertTrue(ClosedVuScheduler.activeUsers(scenario, 69L) <= scenario.users());

        LoadScenario noRamps = scenario(3, 0L, 0L, 20L, 0L, 0L);
        assertEquals(LoadPhase.STEADY, LoadPhase.at(noRamps, 0L));
        assertEquals(3, ClosedVuScheduler.activeUsers(noRamps, 0L));
        assertEquals(LoadPhase.COMPLETE, LoadPhase.at(noRamps, 20L));
        assertEquals(0, ClosedVuScheduler.activeUsers(noRamps, 20L));
    }

    @Test
    void closedUsersWaitForCompletionAndThinkTimeBeforeTheirNextIteration() throws Exception {
        LoadScenario scenario = scenario(1, 0L, 0L, 50L, 0L, 10L);
        FakeTiming fake = new FakeTiming();
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        List<IterationRequest> requests = Collections.synchronizedList(new ArrayList<IterationRequest>());
        LoadIterationRunner runner = request -> {
            requests.add(request);
            fake.advance(5L);
            return result(request.iterationId(), ResultStatus.PASS);
        };

        ClosedVuScheduler scheduler = new ClosedVuScheduler(scenario, runner, "run-19", events::add, fake.timing());
        LoadRunResult result = scheduler.run();
        List<LoadEvent> completions = new ArrayList<LoadEvent>();
        for (LoadEvent event : events) if (event.completed()) completions.add(event);

        assertEquals(4, requests.size());
        assertEquals(requests.size(), completions.size());
        assertEquals("VU-1", requests.get(0).userId());
        assertEquals("VU-1", requests.get(3).userId());
        assertEquals("run-19-VU-1-1", requests.get(0).iterationId());
        assertEquals("run-19-VU-1-4", requests.get(3).iterationId());
        assertNotEquals(requests.get(0).iterationId(), requests.get(1).iterationId());
        assertEquals(10L, completions.get(1).scheduledAtEpochMs() - completions.get(0).completedAtEpochMs());
        assertEquals(10L, completions.get(2).scheduledAtEpochMs() - completions.get(1).completedAtEpochMs());
        assertEquals(50L, result.endedAt().toEpochMilli() - result.startedAt().toEpochMilli());
        assertEquals(4L, result.metrics().longValue("completed"));
        assertEquals(0L, result.metrics().longValue("runtimeError"));
    }

    @Test
    void anIterationFailureIsRecordedWithoutStoppingTheVirtualUser() throws Exception {
        LoadScenario scenario = scenario(1, 0L, 0L, 20L, 0L, 0L);
        FakeTiming fake = new FakeTiming();
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        AtomicLong calls = new AtomicLong();
        LoadIterationRunner runner = request -> {
            fake.advance(5L);
            if (calls.getAndIncrement() == 0L) throw new IllegalStateException("synthetic iteration failure");
            return result(request.iterationId(), ResultStatus.PASS);
        };

        new ClosedVuScheduler(scenario, runner, "run-19-failure", events::add, fake.timing()).run();
        List<LoadEvent> completions = new ArrayList<LoadEvent>();
        for (LoadEvent event : events) if (event.completed()) completions.add(event);

        assertTrue(completions.size() > 1);
        assertEquals(ResultStatus.ERROR, completions.get(0).status());
        assertEquals(ResultStatus.PASS, completions.get(1).status());
        assertFalse(completions.get(0).iterationId().equals(completions.get(1).iterationId()));
    }

    @Test
    void manyClosedVirtualUsersPreserveConfiguredConcurrencyWithBoundedWorkers() throws Exception {
        int users = 100;
        LoadScenario scenario = scenario(users, 0L, 0L, 10_000L, 0L, 10L);
        Set<String> virtualUsers = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        Set<String> workerThreads = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        CountDownLatch allEntered = new CountDownLatch(users);
        CountDownLatch interrupted = new CountDownLatch(users);
        CountDownLatch release = new CountDownLatch(1);
        LoadIterationRunner runner = request -> {
            virtualUsers.add(request.userId());
            workerThreads.add(Thread.currentThread().getName());
            int active = inFlight.incrementAndGet();
            peakInFlight.accumulateAndGet(active, Math::max);
            allEntered.countDown();
            try { release.await(); }
            catch (InterruptedException cancelled) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(cancelled);
            } finally { inFlight.decrementAndGet(); }
            return result(request.iterationId(), ResultStatus.PASS);
        };

        ClosedVuScheduler scheduler = new ClosedVuScheduler(scenario, runner, "run-19-bounded",
                (java.util.function.Consumer<LoadEvent>) null, LoadSchedulerTiming.system());
        ThreadPoolExecutor sharedWorkers = LoadWorkerPool.create(Math.min(4, users), users,
                runnable -> new Thread(runnable, "shared-load-test-worker"));
        assertEquals(0, sharedWorkers.getPoolSize(), "the bounded shared pool must start lazily");
        assertTrue(sharedWorkers.getCorePoolSize() < users, "the pool core must stay below configured VUs");
        scheduler.useSharedWorkers(sharedWorkers);
        ExecutorService control = Executors.newSingleThreadExecutor();
        Future<LoadRunResult> future = control.submit(scheduler::run);
        try {
            assertTrue(allEntered.await(10L, TimeUnit.SECONDS), "every configured VU should enter the target concurrently");
            scheduler.cancel();
            assertTrue(interrupted.await(10L, TimeUnit.SECONDS), "cancellation must interrupt admitted VU executions");
            LoadRunResult result = future.get(10L, TimeUnit.SECONDS);
            assertEquals(users, virtualUsers.size(), "every logical VU should start at least one iteration");
            assertEquals(users, peakInFlight.get(), "configured closed users must be concurrently active in the runner");
            assertTrue(workerThreads.size() <= users, "lazy worker growth must remain within configured VU capacity");
            assertEquals(users, result.metrics().longValue("completed"));
            assertEquals(0, inFlight.get());
        } finally {
            release.countDown();
            scheduler.close();
            control.shutdownNow();
            control.awaitTermination(10L, TimeUnit.SECONDS);
            sharedWorkers.shutdownNow();
            sharedWorkers.awaitTermination(10L, TimeUnit.SECONDS);
        }
    }


    private static LoadScenario scenario(int users, long warmupMs, long rampUpMs, long durationMs,
                                         long rampDownMs, long thinkTimeMs) {
        return new LoadScenario(Paths.get("issue-19.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.CLOSED, users, 0.0, null,
                Duration.ofMillis(warmupMs), Duration.ofMillis(rampUpMs), Duration.ofMillis(durationMs),
                Duration.ofMillis(rampDownMs), Duration.ofMillis(thinkTimeMs), 0, "fail", Collections.emptyMap(),
                Collections.emptyMap());
    }

    private static IterationResult result(String iterationId, ResultStatus status) {
        return new IterationResult(iterationId, status, Duration.ofMillis(5L), null,
                Collections.emptyList(), null, null);
    }

    private static final class FakeTiming {
        private final AtomicLong now = new AtomicLong();

        LoadSchedulerTiming timing() {
            return new LoadSchedulerTiming(now::get, millis -> {
                if (millis > 0L) now.addAndGet(millis);
                Thread.yield();
            });
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }
}
