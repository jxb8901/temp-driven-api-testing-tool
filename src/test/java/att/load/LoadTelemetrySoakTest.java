package att.load;

import att.TestSchemas;
import att.testdata.TestdataInputResolver;
import att.testdata.TestdataRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional synthetic no-target soak; enable explicitly for 30-60 minute profile runs. */
class LoadTelemetrySoakTest {
    @TempDir Path root;

    @Test
    @EnabledIfSystemProperty(named = "att.load.soak", matches = "true")
    void retainedHeapAndIterationSelectionStateReachAPlateau() throws Exception {
        int minutes = Integer.parseInt(System.getProperty("att.load.soak.durationMinutes", "30"));
        assertTrue(minutes >= 30 && minutes <= 60, "soak duration must be 30-60 minutes");
        TestSchemas.install(root);
        Path descriptor = root.resolve("data/soak.yaml");
        Files.createDirectories(descriptor.getParent());
        Files.write(descriptor, ("schemaVersion: att-testdata/v1.0\n"
                + "id: soak\nrecords: [first, second]\n"
                + "selection: {strategy: sequential, exhaustion: recycle}\n").getBytes(StandardCharsets.UTF_8));
        TestdataInputResolver resolver = new TestdataInputResolver(new TestdataRegistry(root,
                Collections.<Path>emptyList(), Collections.singletonList(descriptor)),
                Collections.<String, Object>singletonMap("soak",
                        Collections.<String, Object>singletonMap("scope", "iteration")),
                "soak", "arrivalRate", Long.valueOf(42L));
        Map<String, Object> mapping = Collections.<String, Object>singletonMap("value", "@{soak}");
        long started = System.nanoTime();
        long warmupEnd = started + java.util.concurrent.TimeUnit.MINUTES.toNanos(5L);
        long deadline = started + java.util.concurrent.TimeUnit.MINUTES.toNanos(minutes);
        long ordinal = 0L;
        long warmupRetained = -1L;
        while (System.nanoTime() < deadline) {
            String id = "soak-arrival-" + (++ordinal);
            resolver.resolve(mapping, null, null, id, Long.valueOf(ordinal - 1L), () -> true);
            if (warmupRetained < 0L && System.nanoTime() >= warmupEnd) {
                System.gc();
                warmupRetained = usedHeap();
            }
        }
        System.gc();
        long finalRetained = usedHeap();
        @SuppressWarnings("unchecked") Map<String, Object> cacheSizes =
                (Map<String, Object>) resolver.telemetry().get("selectionCacheSizesByScope");
        assertEquals(0L, cacheSizes.get("iteration"), "iteration selections must not survive the mapping");
        assertTrue(finalRetained <= warmupRetained + Math.max(16L * 1024L * 1024L, warmupRetained / 4L),
                "retained heap should plateau within max(16 MiB, 25%) after warm-up; warmup="
                        + warmupRetained + ", final=" + finalRetained + ", iterations=" + ordinal);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
