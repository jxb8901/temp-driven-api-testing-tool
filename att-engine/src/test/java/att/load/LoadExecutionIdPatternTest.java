package att.load;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LoadExecutionIdPatternTest {
    @TempDir Path root;

    @Test void generatesOver100kUniqueIdentitiesWithConstantStorageAcrossConcurrentWorkloads() throws Exception {
        installConfig();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config.yaml"), root);
        Path run = root.resolve("output/load/RUN");
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            resources.initializeExecutionNamespace(run);
            Set<String> ids = ConcurrentHashMap.newKeySet();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int w = 0; w < 8; w++) tasks.add(pool.submit(() -> {
                    for (int i = 0; i < 15000; i++) assertTrue(ids.add(resources.nextExecutionId("RUN")));
                }));
                for (Future<?> task : tasks) task.get();
            } finally { pool.shutdownNow(); }
            assertEquals(120000, ids.size());
            assertTrue(ids.contains("RUN-execution-1"));
            assertTrue(ids.contains("RUN-execution-120000"));
            assertFalse(Files.exists(run.resolve(".exec-id-reservations")));
            try (java.util.stream.Stream<Path> files = Files.list(run)) { assertEquals(1L, files.count()); }
            assertThrows(IllegalArgumentException.class, () -> resources.nextExecutionId("OTHER"));
            try (LoadRunResources second = new LoadRunResources(root, config)) {
                assertThrows(java.io.IOException.class, () -> second.initializeExecutionNamespace(run));
            }
        }
        try (LoadRunResources restarted = new LoadRunResources(root, config)) {
            assertThrows(java.io.IOException.class, () -> restarted.initializeExecutionNamespace(run));
        }
    }

    @Test void acceptsExistingRunIdContractWithDeterministicBoundedExecutionSegments() throws Exception {
        installConfig();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config.yaml"), root);
        assertEquals(68, LoadExecutionIdPattern.executionPrefix(String.join("", Collections.nCopies(128, "測"))).length());
        Set<String> prefixes = new HashSet<>();
        for (String id : Arrays.asList("測試2026", "my run", "a..b", "_run", "run!", String.join("", Collections.nCopies(128, "x")))) {
            assertEquals(id, att.core.IdentifierValidator.runId(id));
            String prefix = LoadExecutionIdPattern.executionPrefix(id);
            assertTrue(prefixes.add(prefix));
            assertEquals(prefix, LoadExecutionIdPattern.executionPrefix(id));
            try (LoadRunResources resources = new LoadRunResources(root, config)) {
                resources.initializeExecutionNamespace(root.resolve("output/load").resolve(id));
                assertEquals(prefix + "-execution-1", resources.nextExecutionId(id));
                LoadExecutionIdPattern.requireSafe(resources.nextExecutionId(id));
                assertThrows(IllegalArgumentException.class, () -> resources.nextExecutionId("OTHER"));
            }
        }
    }

    @Test void rejectsUnsafeOrOverlongRunIdsAndNeverWrapsSequence() throws Exception {
        installConfig();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config.yaml"), root);
        for (String id : Arrays.asList("", "../run", "a/b", "a\\b", "run.", String.join("", Collections.nCopies(211, "x")))) {
            try (LoadRunResources resources = new LoadRunResources(root, config)) {
                assertThrows(IllegalArgumentException.class, () -> resources.nextExecutionId(id));
            }
        }
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            java.lang.reflect.Field field = LoadRunResources.class.getDeclaredField("executionSequence");
            field.setAccessible(true);
            java.util.concurrent.atomic.AtomicLong sequence = (java.util.concurrent.atomic.AtomicLong) field.get(resources);
            sequence.set(Long.MAX_VALUE - 1);
            assertEquals("RUN-execution-" + Long.MAX_VALUE, resources.nextExecutionId("RUN"));
            assertThrows(IllegalStateException.class, () -> resources.nextExecutionId("RUN"));
            assertThrows(IllegalStateException.class, () -> resources.nextExecutionId("RUN"));
            assertEquals(Long.MAX_VALUE, sequence.get());
        }
    }

    @Test void rejectsCustomFormatsInHistoricalCurrentPolicyAndInlineSnapshots() throws Exception {
        installConfig();
        for (String version : Arrays.asList("att-load/v1.3", "att-load/v1.7", "att-load/v1.8")) {
            String yaml = "schemaVersion: " + version + "\nexecution: {execIdFormat: ''}\n"
                    + "workloads: [{id: one, target: {type: tool, id: sample.echo}, load: {users: 1, duration: 1s}}]\n";
            Path source = root.resolve("scenario.yaml");
            Files.write(source, yaml.getBytes("UTF-8"));
            LoadScenarioLoader loader = new LoadScenarioLoader(root);
            att.validation.DiagnosticException error = assertThrows(att.validation.DiagnosticException.class, () -> loader.load(source));
            assertEquals("execution.execIdFormat", error.field());
            assertTrue(error.detail().contains("Remove execution.execIdFormat"));
            @SuppressWarnings("unchecked") Map<String,Object> snapshot = (Map<String,Object>) att.config.YamlSupport.load(source);
            error = assertThrows(att.validation.DiagnosticException.class, () -> loader.loadInline(snapshot));
            assertEquals("execution.execIdFormat", error.field());
        }
        Path policy = root.resolve("load/load.yaml");
        Files.createDirectories(policy.getParent());
        Files.write(policy, "schemaVersion: att-load/v1.7\nload: {users: 1, duration: 1s}\nexecution: {execIdFormat: custom}\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new LoadScenarioLoader(root).loadDefaultPolicy());
    }

    @Test void migratesHistoricalSnapshotsWithoutCustomizationAndRejectsRetainedOutput() throws Exception {
        installConfig();
        Path source = root.resolve("scenario.yaml");
        Files.write(source, ("schemaVersion: att-load/v1.7\nworkloads: [{id: one, target: {type: tool, id: sample.echo}, load: {users: 1, duration: 1s}}]\n").getBytes("UTF-8"));
        assertEquals("att-load/v1.8", new LoadScenarioLoader(root).load(source).schemaVersion());
        Path run = root.resolve("occupied/load/RUN");
        Files.createDirectories(run.resolve("failures"));
        Files.write(run.resolve("failures/retained"), new byte[]{1});
        try (LoadRunResources resources = new LoadRunResources(root, new FrameworkConfigLoader().load(root.resolve("config.yaml"), root))) {
            assertThrows(IllegalArgumentException.class, () -> resources.initializeExecutionNamespace(run));
        }
    }
    @Test void rejectsLegacyMetricsOnlySummaryWithoutOverwritingIt() throws Exception {
        installConfig();
        Path run = root.resolve("legacy/load/RUN");
        Files.createDirectories(run);
        Path summary = run.resolve("load-summary.json");
        Files.write(summary, "retained".getBytes("UTF-8"));
        try (LoadRunResources resources = new LoadRunResources(root, new FrameworkConfigLoader().load(root.resolve("config.yaml"), root))) {
            assertThrows(IllegalArgumentException.class, () -> resources.initializeExecutionNamespace(run));
            assertEquals("retained", new String(Files.readAllBytes(summary), "UTF-8"));
            assertFalse(Files.exists(run.resolve(".execution-owner")));
        }
    }

    private void installConfig() throws Exception {
        att.TestSchemas.install(root);
        Files.write(root.resolve("config.yaml"), "schemaVersion: att-config/v2.11\n".getBytes("UTF-8"));
    }
}
