package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomThinkTimeSchedulerTest {
    @Test
    void schedulerSamplesAfterEachCompletionClipsToRunEndAndExcludesThinkTimeFromLatency() throws Exception {
        ThinkTimePolicy policy = ThinkTimePolicy.uniform(Duration.ofMillis(10L), Duration.ofMillis(12L));
        LoadScenario scenario = new LoadScenario(Paths.get("random-think.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null,
                Duration.ZERO, Duration.ZERO, Duration.ofMillis(15L), Duration.ZERO,
                policy, Long.valueOf(12345L), 0, "", Collections.emptyMap(), Collections.emptyMap());
        FakeTiming fake = new FakeTiming();
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadIterationRunner runner = request -> {
            fake.advance(1L);
            return new IterationResult(request.iterationId(), ResultStatus.PASS, Duration.ofMillis(1L), null,
                    Collections.emptyList(), null, null);
        };

        LoadRunResult result = new ClosedVuScheduler(scenario, runner, "random-run", events::add, fake.timing()).run();
        List<LoadEvent> completions = new ArrayList<LoadEvent>();
        for (LoadEvent event : events) if (event.completed()) completions.add(event);

        assertEquals(2, completions.size());
        assertEquals(2, fake.sleeps.size());
        long runSeed = LoadRandomization.effectiveSeed(scenario, "random-run");
        Random expectedRandom = LoadRandomization.randomForVu(runSeed, LoadRandomization.workloadKey(scenario), "VU-1");
        long firstSample = policy.sampleMillis(expectedRandom);
        long secondSample = policy.sampleMillis(expectedRandom);
        assertEquals(firstSample, fake.sleeps.get(0).longValue());
        long remainingAfterSecond = 15L - (1L + firstSample + 1L);
        assertEquals(Math.min(secondSample, remainingAfterSecond), fake.sleeps.get(1).longValue());
        assertTrue(fake.sleeps.get(1).longValue() < policy.minMillis(), "final think time should be clipped to remaining run time");
        assertEquals(15L, result.endedAt().toEpochMilli() - result.startedAt().toEpochMilli());
        for (LoadEvent completion : completions) assertEquals(1L, completion.latencyMs());
        assertEquals(1.0, result.metrics().doubleValue("p95Ms"), 0.0001);
    }

    @Test
    void productionTimingConsumesThinkTimeLongerThanSystemSleepQuantum() throws Exception {
        ThinkTimePolicy policy = ThinkTimePolicy.uniform(Duration.ofMillis(100L), Duration.ofMillis(120L));
        LoadScenario scenario = new LoadScenario(Paths.get("production-random-think.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null,
                Duration.ZERO, Duration.ZERO, Duration.ofMillis(220L), Duration.ZERO,
                policy, Long.valueOf(24680L), 0, "", Collections.emptyMap(), Collections.emptyMap());
        List<LoadEvent> events = Collections.synchronizedList(new ArrayList<LoadEvent>());
        LoadIterationRunner runner = request -> new IterationResult(request.iterationId(), ResultStatus.PASS, Duration.ZERO, null,
                Collections.emptyList(), null, null);

        new ClosedVuScheduler(scenario, runner, "production-random-run", events::add, LoadSchedulerTiming.system()).run();

        List<LoadEvent> starts = new ArrayList<LoadEvent>();
        List<LoadEvent> completions = new ArrayList<LoadEvent>();
        for (LoadEvent event : events) {
            if (event.started()) starts.add(event);
            if (event.completed()) completions.add(event);
        }
        assertTrue(completions.size() <= 3,
                "100ms minimum think time in a 220ms run should not permit more than three completed iterations; got " + completions.size());
        assertTrue(starts.size() >= 2, "production timing regression requires at least two iterations");
        long firstGap = starts.get(1).startedAtEpochMs() - completions.get(0).completedAtEpochMs();
        assertTrue(firstGap >= 90L,
                "production scheduler must consume the sampled >=100ms think time rather than the 50ms sleep quantum; gap=" + firstGap);
    }

    private static final class FakeTiming {
        private final AtomicLong now = new AtomicLong();
        private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<Long>());

        LoadSchedulerTiming timing() {
            return new LoadSchedulerTiming(now::get, millis -> {
                sleeps.add(Long.valueOf(millis));
                if (millis > 0L) now.addAndGet(millis);
                Thread.yield();
            });
        }

        void advance(long millis) { now.addAndGet(millis); }
    }
}
