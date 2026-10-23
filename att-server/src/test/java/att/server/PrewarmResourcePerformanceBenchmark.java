package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in OS-level memory and I/O capture for idle one-shot prewarm JVMs. */
class PrewarmResourcePerformanceBenchmark {
    @TempDir Path temporary;

    @Test void writesIdleWorkerResourceMatrixForOneTwoAndFourSlots() throws Exception {
        assumeTrue(Boolean.getBoolean("att.server.benchmark.prewarmResources"), "opt-in local resource benchmark");
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int slots : new int[]{1, 2, 4}) scenarios.add(measureIdlePool(slots));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "att-execution-prewarm-resource-benchmark/v1");
        report.put("sourceRevision", System.getProperty("att.benchmark.commit", "unspecified"));
        report.put("environment", Map.of("javaVersion", System.getProperty("java.version"),
                "javaVm", System.getProperty("java.vm.name"),
                "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
                "architecture", System.getProperty("os.arch"),
                "processors", Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory()));
        report.put("settings", Map.of("poolSizes", List.of(1, 2, 4), "idleSampleCount", 9,
                "idleSamplingIntervalMs", 250, "workerHeapMaxMb", 128,
                "scope", "execution Worker JVMs only; no Jobs submitted during sampling"));
        report.put("scenarios", scenarios);
        Path destination = Path.of(System.getProperty("att.server.benchmark.prewarmResources.output",
                "target/prewarm-worker-resource-matrix.json")).toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
        System.out.println("Prewarm Worker resource benchmark written to " + destination);
    }

    private Map<String, Object> measureIdlePool(int slots) throws Exception {
        Path scenario = Files.createDirectories(temporary.resolve("pool-" + slots));
        Path packageRoots = Files.createDirectories(scenario.resolve("packages"));
        Path runtimeConfig = Files.createDirectories(scenario.resolve("runtime-home/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig, "schemaVersion: att-config/v2.12\nenvironment: BENCHMARK\n"
                + "testcase: {root: testcase}\ntemplates: {root: templates}\n", StandardCharsets.UTF_8);
        List<String> packageIds = new ArrayList<>();
        Map<String, Path> packages = new LinkedHashMap<>();
        for (int index = 1; index <= slots; index++) {
            String id = "p" + index;
            packageIds.add(id);
            Path root = Files.createDirectories(packageRoots.resolve(id));
            Files.createDirectories(root.resolve("testcase"));
            Files.createDirectories(root.resolve("templates"));
            packages.put(id, root);
        }
        Path data = scenario.resolve("server-data");
        Path configPath = scenario.resolve("server.yaml");
        StringBuilder config = new StringBuilder("server:\n  runtimeConfigPath: ")
                .append(ServerRuntimeTestSupport.yaml(runtimeConfig)).append("\n  dataDir: ")
                .append(ServerRuntimeTestSupport.yaml(data)).append("\nworkers:\n  maxConcurrent: 4\n")
                .append("  queuedLimit: 2\n  maxConcurrentLoad: 1\n  heapMaxMb: 128\n")
                .append("  executionPool: {enabled: true, minIdle: ").append(slots)
                .append(", maxIdle: ").append(slots).append(", heapBudgetMb: 512, packageIds: [")
                .append(String.join(", ", packageIds)).append("]}\npackages:\n  allowedRoots: [")
                .append(ServerRuntimeTestSupport.yaml(packageRoots)).append("]\n  entries:\n");
        packages.forEach((id, root) -> config.append("    ").append(id).append(": ")
                .append(ServerRuntimeTestSupport.yaml(root)).append('\n'));
        Files.writeString(configPath, config, StandardCharsets.UTF_8);

        Path libraries = Files.createDirectories(scenario.resolve("WEB-INF-lib"));
        for (String module : List.of("att-worker", "att-engine", "att-server-api", "att-security"))
            ServerRuntimeTestSupport.addModuleJar(libraries, module, Path.of(module + "/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libraries);

        ServerRuntime runtime = new ServerRuntime(ServerConfig.load(configPath), libraries.toString());
        List<Process> workers = List.of();
        long readyStarted = System.nanoTime();
        try {
            awaitReady(runtime, slots, 45);
            double readyMs = (System.nanoTime() - readyStarted) / 1_000_000.0;
            workers = readyProcesses(runtime, slots);
            Map<String, Object> resources = ProcessTreeResourceSampler.sampleProcesses(workers, 9, 250);
            assertEquals(slots, resources.get("workerCount"));
            Map<String, Object> scenarioResult = new LinkedHashMap<>();
            scenarioResult.put("idlePoolSlots", slots);
            scenarioResult.put("readyWorkers", slots);
            scenarioResult.put("poolReadyMs", readyMs);
            scenarioResult.put("workerHeapMaxMb", 128);
            scenarioResult.put("resources", resources);
            return scenarioResult;
        } finally {
            runtime.close();
            for (Process process : workers) {
                if (process.isAlive()) process.waitFor(10, TimeUnit.SECONDS);
                assertFalse(process.isAlive(), "Closing the runtime must terminate each measured idle Worker");
            }
        }
    }

    private static void awaitReady(ServerRuntime runtime, int expected, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Map<String, Object> stats = runtime.executionPoolStatistics();
            if (((Number) stats.get("ready")).intValue() == expected) return;
            Thread.sleep(25);
        }
        assertEquals(expected, ((Number) runtime.executionPoolStatistics().get("ready")).intValue(),
                "All configured idle Workers must prove READY before measurement");
    }

    private static List<Process> readyProcesses(ServerRuntime runtime, int expected) throws Exception {
        var poolField = ServerRuntime.class.getDeclaredField("executionWorkers");
        poolField.setAccessible(true);
        Object pool = poolField.get(runtime);
        var ownedField = pool.getClass().getDeclaredField("owned");
        ownedField.setAccessible(true);
        List<Process> processes = new ArrayList<>();
        synchronized (pool) {
            for (Object idle : (List<?>) ownedField.get(pool)) {
                var readyField = idle.getClass().getDeclaredField("readyNanos");
                readyField.setAccessible(true);
                var retiredField = idle.getClass().getDeclaredField("retired");
                retiredField.setAccessible(true);
                var processField = idle.getClass().getDeclaredField("process");
                processField.setAccessible(true);
                Process process = (Process) processField.get(idle);
                if (!retiredField.getBoolean(idle) && readyField.getLong(idle) != 0L
                        && process != null && process.isAlive()) processes.add(process);
            }
        }
        assertEquals(expected, processes.size());
        return List.copyOf(processes);
    }
}
