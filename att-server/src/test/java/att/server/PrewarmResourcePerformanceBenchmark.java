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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in OS-level memory and I/O capture for idle one-shot prewarm JVMs. */
class PrewarmResourcePerformanceBenchmark {
    @TempDir Path temporary;

    @Test void writesIdleWorkerResourceMatrixForOneTwoAndFourSlots() throws Exception {
        assumeTrue(Boolean.getBoolean("att.server.benchmark.prewarmResources"), "opt-in local resource benchmark");
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int slots : new int[]{1, 2, 4}) scenarios.add(measureIdlePool(slots));
        List<Map<String, Object>> activeRuns = List.of(measureActiveRun(false), measureActiveRun(true));

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
                "scope", "idle RSS samples include only execution Worker JVMs; active samples measure one RUN Worker during its Tool action"));
        report.put("scenarios", scenarios);
        report.put("activeRunScenarios", activeRuns);
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

    private Map<String, Object> measureActiveRun(boolean prewarm) throws Exception {
        Path scenario = Files.createDirectories(temporary.resolve(prewarm ? "active-prewarm" : "active-cold"));
        Path allowed = Files.createDirectories(scenario.resolve("allowed"));
        Path packageRoot = Files.createDirectories(allowed.resolve("p"));
        Path templates = Files.createDirectories(packageRoot.resolve("templates/HOLD"));
        Path tools = Files.createDirectories(packageRoot.resolve("tools"));
        Files.writeString(templates.resolve("template.yaml"), "schemaVersion: att-template/v3.6\nname: HOLD\n"
                + "description: Bounded active Worker RSS fixture\nactions:\n  hold:\n    type: tool\n    call: '#{hold()}'\n", StandardCharsets.UTF_8);
        Path script = tools.resolve("hold.sh");
        Files.writeString(script, "#!/bin/sh\ntouch \"$ATT_ROOT_DIR/tools/started\"\nexec sleep 3\n", StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        Path runtimeConfig = Files.createDirectories(scenario.resolve("runtime-home/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig, "schemaVersion: att-config/v2.12\nenvironment: BENCHMARK\nenvironments: {BENCHMARK: {}}\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\n"
                + "tools:\n  hold:\n    name: Hold\n    description: Bounded benchmark delay\n"
                + "    command: [./tools/hold.sh]\n    stdoutFormat: text\n    arguments: {}\n", StandardCharsets.UTF_8);
        Path data = scenario.resolve("server-data");
        Path configPath = scenario.resolve("server.yaml");
        String pool = prewarm
                ? "  executionPool: {enabled: true, minIdle: 1, maxIdle: 1, heapBudgetMb: 128, packageIds: [p]}\n"
                : "";
        Files.writeString(configPath, "server:\n  runtimeConfigPath: " + ServerRuntimeTestSupport.yaml(runtimeConfig)
                + "\n  dataDir: " + ServerRuntimeTestSupport.yaml(data)
                + "\n  auth: {mode: local}\n  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                + "workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  heapMaxMb: 128\n" + pool
                + "packages:\n  allowedRoots: [" + ServerRuntimeTestSupport.yaml(allowed) + "]\n  entries:\n    p: "
                + ServerRuntimeTestSupport.yaml(packageRoot) + "\n", StandardCharsets.UTF_8);

        Path libraries = Files.createDirectories(scenario.resolve("WEB-INF-lib"));
        for (String module : List.of("att-worker", "att-engine", "att-server-api", "att-security"))
            ServerRuntimeTestSupport.addModuleJar(libraries, module, Path.of(module + "/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libraries);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dispatch", prewarm ? "warm" : "cold");
        result.put("toolHoldMs", 3000);
        try (ServerRuntime runtime = new ServerRuntime(ServerConfig.load(configPath), libraries.toString())) {
            runtime.store.createAuthUser("benchmark", "Benchmark", "unused", "USER", "csrf");
            if (prewarm) awaitReady(runtime, 1, 30);
            var definition = ServerRuntime.JSON.createObjectNode();
            definition.put("schemaVersion", RunDefinition.VERSION);
            definition.put("executionType", "run");
            definition.put("packageId", "p");
            definition.put("environment", "BENCHMARK");
            definition.set("target", ServerRuntime.JSON.valueToTree(Map.of("type", "template", "id", "HOLD")));
            definition.set("input", ServerRuntime.JSON.createObjectNode());
            definition.set("options", ServerRuntime.JSON.valueToTree(Map.of("debug", false)));
            Map<String, Object> draft;
            try { draft = runtime.createRunDraft(definition, "benchmark"); }
            catch (ServerRuntime.DraftValidationException invalid) {
                throw new AssertionError("Active RSS RUN fixture must validate: " + invalid.diagnostics, invalid);
            }
            Map<String, Object> accepted = runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId", "p", "draftId", draft.get("draftId"))), "benchmark", "benchmark");
            String id = String.valueOf(accepted.get("jobId"));
            Job job = runtime.jobs.get(id);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((!Files.exists(tools.resolve("started")) || job == null || job.process == null)
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(tools.resolve("started")), "The bounded Tool action must start before RSS sampling");
            assertNotNull(job);
            assertNotNull(job.process);
            assertEquals(prewarm ? "warm" : "cold", job.workerDispatch);
            Map<String, Object> activeResources = ProcessTreeResourceSampler.sampleProcesses(List.of(job.process), 8, 100);
            assertEquals(1, activeResources.get("workerCount"));
            Map<String, Object> completion = ServerRuntimeTestSupport.awaitUnifiedJob(runtime, id);
            Map<?, ?> view = (Map<?, ?>) completion.get("job");
            assertEquals("PASS", view.get("status"), "Active RSS RUN must complete successfully");
            Map<?, ?> performance = (Map<?, ?>) view.get("performance");
            Map<?, ?> worker = (Map<?, ?>) performance.get("worker");
            result.put("status", view.get("status"));
            result.put("workerMetricsPeakRssBytes", worker.get("rssPeakBytes"));
            result.put("workerProcessActivePeakRssBytes", activeResources.get("peakTotalResidentBytes"));
            result.put("workerProcessActiveSamples", activeResources.get("sampleCount"));
            result.put("scope", "one execution Worker process sampled during a 3-second Tool action; excludes Server and Tool-child RSS");
            result.put("performance", performance);
        }
        return result;
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
