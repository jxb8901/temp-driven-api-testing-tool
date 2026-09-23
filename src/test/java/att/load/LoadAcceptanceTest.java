package att.load;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.validation.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Release-gate checks for the documented Load V1 examples and CLI contract. */
class LoadAcceptanceTest {
    @TempDir Path temp;

    @Test void everyDocumentedExampleResolvesAndValidatesBeforeScheduling() throws Exception {
        Path root = projectRoot();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        List<String> examples = Arrays.asList("closed-minimal.yaml", "closed-smoke.yaml", "closed.yaml", "arrival-smoke.yaml", "arrival-rate.yaml", "tool.yaml");
        for (String name : examples) {
            Path scenarioFile = root.resolve("examples/load").resolve(name);
            LoadScenario scenario = new LoadScenarioLoader(root).load(scenarioFile);
            LoadTarget target = new LoadTargetResolver(root, config).resolve(scenario);
            new LoadTargetValidator(root, config).validate(scenario, target);
        }
    }

    @Test void cliRunsClosedAndArrivalRateModelsThroughPersistedReport() throws Exception {
        Path root = projectRoot();
        Path closedScenario = writeScenario("closed-cli.yaml",
                "schemaVersion: att-load/v1.0\n"
                + "target: {type: tool, id: sample.getAcDate}\n"
                        + "load: {users: 1, duration: 25ms}\n");
        Path arrivalScenario = writeScenario("arrival-cli.yaml",
                "schemaVersion: att-load/v1.0\n"
                        + "target: {type: tool, id: sample.getAcDate}\n"
                        + "load: {arrivalRate: 100/s, duration: 40ms, maxConcurrent: 2, overloadPolicy: drop}\n");

        assertCliLoad(root, closedScenario, temp.resolve("closed-output"), "issue25-closed", "closed");
        assertCliLoad(root, arrivalScenario, temp.resolve("arrival-output"), "issue25-arrival", "arrivalRate");
    }

    @Test void arrivalRateCliPersistsCapDropAndRateDimensionsThroughReport() throws Exception {
        Path root = projectRoot();
        Path scenario = writeScenario("arrival-saturation-cli.yaml",
                "schemaVersion: att-load/v1.0\n"
                        + "target:\n"
                        + "  type: tool\n"
                        + "  id: fpp.exehelper\n"
                        + "  arguments: {command: sleep, arguments: [0.25]}\n"
                        + "load: {arrivalRate: 100/s, duration: 150ms, maxConcurrent: 1, overloadPolicy: drop}\n");
        Path output = temp.resolve("arrival-saturation-output");
        Map<String, Object> summary = runCli(root, scenario, output, "issue25-arrival-saturation");
        @SuppressWarnings("unchecked") Map<String, Object> metrics = (Map<String, Object>) summary.get("metrics");
        assertEquals("PASS", summary.get("status"));
        assertEquals("arrivalRate", metrics.get("model"));
        assertEquals(100.0, ((Number) metrics.get("configuredArrivalRatePerSecond")).doubleValue(), 0.00001);
        assertEquals(1, ((Number) metrics.get("configuredMaxConcurrent")).intValue());
        assertTrue(((Number) metrics.get("scheduled")).longValue() > ((Number) metrics.get("started")).longValue());
        assertTrue(((Number) metrics.get("dropped")).longValue() > 0L);
        assertTrue(((Number) metrics.get("measuredAchievedArrivalRate")).doubleValue() > 0.0);
        assertTrue(((Number) metrics.get("completedThroughput")).doubleValue() > 0.0);
        String html = read(output.resolve("load/issue25-arrival-saturation/report/index.html"));
        assertTrue(html.contains("Configured arrival rate"));
        assertTrue(html.contains("Achieved scheduling rate"));
        assertTrue(html.contains("Completed TPS"));
        assertTrue(html.contains("Dropped arrivals"));
    }

    @Test void loadProfileWritesRepeatablePerformanceEvidence() throws Exception {
        Path root = projectRoot();
        Path scenario = writeScenario("profile-cli.yaml",
                "schemaVersion: att-load/v1.0\n"
                        + "target: {type: tool, id: sample.getAcDate}\n"
                        + "load: {users: 1, duration: 25ms}\n");
        Path output = temp.resolve("profile-output");
        runCli(root, scenario, output, "issue17-profile", "--profile", "--think-time", "1s");

        Path performance = output.resolve("load/issue17-profile/performance.json");
        assertTrue(Files.isRegularFile(performance), "load --profile must write performance.json");
        @SuppressWarnings("unchecked") Map<String, Object> profile = JsonSupport.mapper().readValue(performance.toFile(), Map.class);
        assertEquals("3.5.1", profile.get("attVersion"));
        assertTrue(((Map<?, ?>) profile.get("phases")).containsKey("loadExecutionMs"));
        assertTrue(((Map<?, ?>) profile.get("phases")).containsKey("loadReportMs"));
        assertEquals(1L, ((Number) ((Map<?, ?>) profile.get("counters")).get("loadCompleted")).longValue());
    }

    private void assertCliLoad(Path root, Path scenario, Path output, String runId, String model) throws Exception {
        Map<String, Object> summary = runCli(root, scenario, output, runId);
        assertEquals("PASS", summary.get("status"));
        assertEquals(model, ((Map<?, ?>) summary.get("metrics")).get("model"));
    }

    private Map<String, Object> runCli(Path root, Path scenario, Path output, String runId, String... extra) throws Exception {
        Path stdout = temp.resolve(runId + ".stdout");
        Path stderr = temp.resolve(runId + ".stderr");
        List<String> arguments = new java.util.ArrayList<String>(Arrays.asList(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                "att.FrameworkRunner", "load", scenario.toString(), "--output-dir", output.toString(),
                "--run-id", runId, "--format", "json"));
        arguments.addAll(Arrays.asList(extra));
        ProcessBuilder command = new ProcessBuilder(arguments);
        command.directory(root.toFile());
        command.redirectOutput(stdout.toFile());
        command.redirectError(stderr.toFile());
        Process process = command.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "load CLI did not finish: " + runId);
        assertEquals(0, process.exitValue(), "load CLI failed: " + read(stderr) + "\n" + read(stdout));

        Path runDirectory = output.resolve("load").resolve(runId);
        Path summaryFile = runDirectory.resolve("load-summary.json");
        assertTrue(Files.isRegularFile(summaryFile), "missing load summary for " + runId);
        assertTrue(Files.isRegularFile(runDirectory.resolve("load-summary.yaml")));
        assertTrue(Files.isRegularFile(runDirectory.resolve("report/index.html")));
        @SuppressWarnings("unchecked") Map<String, Object> summary = JsonSupport.mapper().readValue(summaryFile.toFile(), Map.class);
        assertEquals("report/index.html", summary.get("report"));
        assertTrue(read(runDirectory.resolve("report/index.html")).contains("ATT Load " + runId));
        return summary;
    }

    private Path writeScenario(String name, String content) throws IOException {
        Path file = temp.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Path projectRoot() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
