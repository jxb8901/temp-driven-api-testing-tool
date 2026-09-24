package att.load;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Deterministic load-run randomization with isolated per-workload/VU streams. */
public final class LoadRandomization {
    private LoadRandomization() { }

    static long effectiveSeed(LoadScenario scenario, String runId) {
        if (scenario != null && scenario.seed() != null) return scenario.seed().longValue();
        return mix64(hash64("run:" + String.valueOf(runId)));
    }

    static String workloadKey(LoadScenario scenario) {
        if (scenario == null) return "default";
        return "default:" + scenario.targetType() + ":" + scenario.targetId();
    }

    static long streamSeed(long runSeed, String workloadId, String userId) {
        long workload = hash64(String.valueOf(workloadId));
        long user = hash64(String.valueOf(userId));
        return mix64(runSeed ^ workload ^ Long.rotateLeft(user, 23));
    }

    static Random randomForVu(long runSeed, String workloadId, String userId) {
        return new Random(streamSeed(runSeed, workloadId, userId));
    }

    private static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        long z = value + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
