package att.load;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkTimePolicyTest {
    @Test
    void fixedAndDegenerateUniformRangeNormalizeToTheSamePolicy() {
        ThinkTimePolicy fixed = ThinkTimePolicy.fixed(Duration.ofMillis(500L));
        ThinkTimePolicy degenerate = ThinkTimePolicy.uniform(Duration.ofMillis(500L), Duration.ofMillis(500L));

        assertEquals(ThinkTimePolicy.Type.FIXED, fixed.type());
        assertEquals(ThinkTimePolicy.Type.FIXED, degenerate.type());
        assertFalse(fixed.randomized());
        assertEquals(500L, fixed.sampleMillis(null));
        assertEquals(500L, degenerate.sampleMillis(new Random(1L)));
        assertEquals("fixed: 500ms", fixed.summary());
        assertEquals("500ms", fixed.toConfigValue());
    }

    @Test
    void uniformRangeUsesInclusiveIntegerMillisecondBounds() {
        ThinkTimePolicy policy = ThinkTimePolicy.uniform(Duration.ofMillis(500L), Duration.ofSeconds(2L));
        assertEquals(ThinkTimePolicy.Type.UNIFORM_RANGE, policy.type());
        assertTrue(policy.randomized());
        assertEquals("uniform: 500ms..2s", policy.summary());

        Random minimum = new Random() { @Override public long nextLong() { return 0L; } };
        Random maximum = new Random() { @Override public long nextLong() { return 3000L; } };
        assertEquals(500L, policy.sampleMillis(minimum));
        assertEquals(2000L, policy.sampleMillis(maximum));

        Random random = new Random(12345L);
        boolean sawDifferent = false;
        long first = policy.sampleMillis(random);
        for (int i = 0; i < 10000; i++) {
            long sampled = policy.sampleMillis(random);
            assertTrue(sampled >= 500L && sampled <= 2000L);
            if (sampled != first) sawDifferent = true;
        }
        assertTrue(sawDifferent);
    }

    @Test
    void sameVuSeedReplaysAndDifferentVusUseIndependentStreams() {
        long runSeed = 424242L;
        String workload = "payment";
        Random first = LoadRandomization.randomForVu(runSeed, workload, "VU-1");
        Random replay = LoadRandomization.randomForVu(runSeed, workload, "VU-1");
        Random second = LoadRandomization.randomForVu(runSeed, workload, "VU-2");
        ThinkTimePolicy policy = ThinkTimePolicy.uniform(Duration.ofMillis(10L), Duration.ofMillis(100L));

        List<Long> a = sequence(policy, first, 50);
        List<Long> b = sequence(policy, replay, 50);
        List<Long> c = sequence(policy, second, 50);
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(LoadRandomization.streamSeed(runSeed, workload, "VU-1"),
                LoadRandomization.streamSeed(runSeed, workload, "VU-2"));
    }

    @Test
    void perVuRandomStreamsRemainDeterministicUnderConcurrency() throws Exception {
        final ThinkTimePolicy policy = ThinkTimePolicy.uniform(Duration.ZERO, Duration.ofMillis(999L));
        final long seed = 987654321L;
        final String workload = "enquiry";
        List<List<Long>> expected = new ArrayList<List<Long>>();
        for (int i = 0; i < 12; i++)
            expected.add(sequence(policy, LoadRandomization.randomForVu(seed, workload, "VU-" + (i + 1)), 100));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<List<Long>>> futures = new ArrayList<Future<List<Long>>>();
            for (int i = 0; i < 12; i++) {
                final String user = "VU-" + (i + 1);
                futures.add(pool.submit(() -> sequence(policy, LoadRandomization.randomForVu(seed, workload, user), 100)));
            }
            for (int i = 0; i < futures.size(); i++) assertEquals(expected.get(i), futures.get(i).get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Long> sequence(ThinkTimePolicy policy, Random random, int count) {
        List<Long> values = new ArrayList<Long>();
        for (int i = 0; i < count; i++) values.add(Long.valueOf(policy.sampleMillis(random)));
        return values;
    }
}
