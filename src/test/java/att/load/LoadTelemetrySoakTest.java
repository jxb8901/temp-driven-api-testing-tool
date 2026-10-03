package att.load;

import att.TestSchemas;
import att.config.FrameworkConfig;
import att.core.ResultStatus;
import att.testdata.TestdataInputResolver;
import att.testdata.TestdataRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional 30-60 minute soak through the production closed-VU Load runtime. */
class LoadTelemetrySoakTest {
    @TempDir Path root;

    @Test
    void realLoadRuntimeExercisesSyntheticTargetAndBoundedSelectionState() throws Exception {
        try (RuntimeFixture fixture = runtimeFixture(Duration.ofMillis(250L))) {
            LoadRunResult result = fixture.scheduler.run();
            assertTrue(result.metrics().longValue("completed") > 0L);
            assertEquals(ResultStatus.PASS, result.status());
            @SuppressWarnings("unchecked") Map<String, Object> sizes =
                    (Map<String, Object>) fixture.resolver.telemetry().get("selectionCacheSizesByScope");
            assertEquals(0L, sizes.get("iteration"));
            assertTrue(((Number) fixture.resources.metrics().get("resourceMetricSamples")).longValue() > 0L);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "att.load.soak", matches = "true")
    void retainedHeapAndRuntimeStateReachAPlateau() throws Exception {
        int minutes = Integer.parseInt(System.getProperty("att.load.soak.durationMinutes", "30"));
        assertTrue(minutes >= 30 && minutes <= 60, "soak duration must be 30-60 minutes");
        try (RuntimeFixture fixture = runtimeFixture(Duration.ofMinutes(minutes))) {
            ExecutorService runner = Executors.newSingleThreadExecutor();
            long warmupCheckpoint = System.nanoTime() + TimeUnit.MINUTES.toNanos(5L);
            Future<LoadRunResult> future = runner.submit(fixture.scheduler::run);
            try {
                while (!future.isDone() && System.nanoTime() < warmupCheckpoint) Thread.sleep(500L);
                assertFalse(future.isDone(), "the runtime must still be exercising Load at the warm-up checkpoint");
                System.gc();
                long warmupRetained = usedHeap();
                LoadRunResult result = future.get(minutes + 2L, TimeUnit.MINUTES);
                System.gc();
                long finalRetained = usedHeap();

                assertTrue(finalRetained <= warmupRetained + Math.max(16L * 1024L * 1024L, warmupRetained / 4L),
                        "retained heap should plateau within max(16 MiB, 25%) after warm-up; warmup="
                                + warmupRetained + ", final=" + finalRetained);
                assertTrue(result.metrics().longValue("completed") > 1000L,
                        "the scheduler and IterationExecutor must execute a substantial synthetic workload");

                @SuppressWarnings("unchecked") Map<String, Object> generator =
                        (Map<String, Object>) result.metrics().value("generator");
                long maxSamples = minutes * 60L * 10L + 20L;
                assertTrue(((Number) generator.get("sampleCount")).longValue() <= maxSamples,
                        "generator sampling must remain rate-limited");
                @SuppressWarnings("unchecked") Map<String, Object> resolverTelemetry = fixture.resolver.telemetry();
                @SuppressWarnings("unchecked") Map<String, Object> selectionSizes =
                        (Map<String, Object>) resolverTelemetry.get("selectionCacheSizesByScope");
                assertEquals(0L, selectionSizes.get("iteration"), "iteration selections must not survive an input mapping");
                assertTrue(((Number) resolverTelemetry.get("mappingEvaluations")).longValue() > 1000L);

                Map<String, Object> resourceMetrics = fixture.resources.metrics();
                long resourceSamples = ((Number) resourceMetrics.get("resourceMetricSamples")).longValue();
                assertTrue(resourceSamples <= maxSamples,
                        "resource gauge sampling must remain bounded independently of iteration count");
                @SuppressWarnings("unchecked") Map<String, Object> executionIds =
                        (Map<String, Object>) resourceMetrics.get("executionIds");
                assertEquals(0L, executionIds.get("customExecutionIdsTracked"));
                assertEquals(0L, executionIds.get("executionIdCollisionTrackingSize"));
                assertEquals(ResultStatus.PASS, result.status());
            } finally {
                fixture.scheduler.close();
                runner.shutdownNow();
                runner.awaitTermination(2L, TimeUnit.SECONDS);
            }
        }
    }

    private RuntimeFixture runtimeFixture(Duration duration) throws Exception {
        TestSchemas.install(root);
        Files.createDirectories(root.resolve("templates/SOAK"));
        Files.write(root.resolve("templates/SOAK/template.yaml"), (
                "schemaVersion: att-template/v3.6\nname: SOAK\ndescription: synthetic Load soak\nactions:\n"
                + "  noOp:\n    type: assign\n    name: soakIteration\n    expression: \"synthetic\"\n")
                .getBytes(StandardCharsets.UTF_8));

        Path descriptor = root.resolve("data/soak.yaml");
        Files.createDirectories(descriptor.getParent());
        Files.write(descriptor, ("schemaVersion: att-testdata/v1.0\n"
                + "id: soak\nrecords: [first, second]\n"
                + "selection: {strategy: sequential, exhaustion: recycle}\n").getBytes(StandardCharsets.UTF_8));
        TestdataInputResolver resolver = new TestdataInputResolver(new TestdataRegistry(root,
                Collections.<Path>emptyList(), Collections.singletonList(descriptor)),
                Collections.<String, Object>emptyMap(), "default", "closed", Long.valueOf(42L), 1);

        LoadWorkload workload = new LoadWorkload("default", "template", "SOAK",
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>singletonMap("record", "@{soak}"),
                Collections.<String, Object>emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, "",
                Duration.ZERO, Duration.ZERO, duration, Duration.ZERO, ThinkTimePolicy.fixed(Duration.ZERO),
                1, "drop", Collections.<String, Object>emptyMap());
        LoadScenario scenario = new LoadScenario(root.resolve("soak-load.yaml"), att.Version.LOAD_SCHEMA_V1_0,
                Collections.singletonList(workload), null, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(), false, Collections.singletonList(descriptor));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"),
                "SIT", 10000, Paths.get("templates"), Collections.emptyMap(), null, null);
        LoadTarget target = new LoadTargetResolver(root, config).resolve(scenario);
        new LoadTargetValidator(root, config).validate(scenario, target);
        LoadRunResources resources = new LoadRunResources(root, config);
        IterationExecutor iterations = new IterationExecutor(root, config, target, resources,
                root.resolve("output"), resolver);
        ClosedVuScheduler scheduler = new ClosedVuScheduler(scenario, iterations, "soak-run",
                (java.util.function.Consumer<LoadEvent>) null);
        return new RuntimeFixture(resources, resolver, scheduler);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static final class RuntimeFixture implements AutoCloseable {
        private final LoadRunResources resources;
        private final TestdataInputResolver resolver;
        private final ClosedVuScheduler scheduler;

        private RuntimeFixture(LoadRunResources resources, TestdataInputResolver resolver,
                               ClosedVuScheduler scheduler) {
            this.resources = resources;
            this.resolver = resolver;
            this.scheduler = scheduler;
        }

        @Override public void close() {
            scheduler.close();
            resources.close();
        }
    }
}
