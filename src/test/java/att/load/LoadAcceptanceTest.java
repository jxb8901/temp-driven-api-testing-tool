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

    @Test void mixedWorkloadRunsThroughCliAndPresentsLoadReportAndDiagnostics() throws Exception {
        Path root = projectRoot();
        String suffix = Long.toString(System.nanoTime());
        Path scenario = writeScenario("mixed-cli-" + suffix + ".yaml",
                "schemaVersion: att-load/v1.6\nseed: 73\nworkloads:\n"
                        + "- id: checkout\n  mix:\n"
                        + "  - id: date\n    weight: 60\n    target: {type: tool, id: sample.getAcDate}\n"
                        + "  - id: sequence\n    weight: 40\n    target: {type: tool, id: sample.getSeq, arguments: {seqLen: 8}}\n"
                        + "  load: {users: 1, duration: 30ms}\n");

        String jsonRun = "review-mix-json-" + suffix;
        Path jsonOutput = root.resolve("target").resolve("review-mix-json-" + suffix);
        CliOutput jsonCli = executeCli(root, scenario, jsonOutput, jsonRun, "json");
        assertEquals(0, jsonCli.exitCode, jsonCli.stderr + "\n" + jsonCli.stdout);
        @SuppressWarnings("unchecked") Map<String, Object> json = JsonSupport.mapper().readValue(jsonCli.stdout, Map.class);
        assertEquals("PASS", json.get("status"));
        assertEquals("$ATT_HOME/target/review-mix-json-" + suffix + "/load/" + jsonRun + "/report/index.html", json.get("report"));
        assertFalse(jsonCli.stdout.contains(root.toString()));
        @SuppressWarnings("unchecked") Map<String, Object> workloads = (Map<String, Object>) json.get("workloads");
        assertTrue(workloads.containsKey("checkout"));

        String humanRun = "review-mix-human-" + suffix;
        Path humanOutput = root.resolve("target").resolve("review-mix-human-" + suffix);
        CliOutput humanCli = executeCli(root, scenario, humanOutput, humanRun, "human", "--quiet");
        assertEquals(0, humanCli.exitCode, humanCli.stderr + "\n" + humanCli.stdout);
        assertTrue(humanCli.stdout.contains("Report: $ATT_HOME/target/review-mix-human-" + suffix
                + "/load/" + humanRun + "/report/index.html"), humanCli.stdout);
        assertFalse(humanCli.stdout.contains(root.toString()));

        String duplicateRun = "review-mix-diagnostic-" + suffix;
        Path diagnosticOutput = root.resolve("target").resolve("review-mix-diagnostic-" + suffix);
        Files.createDirectories(diagnosticOutput.resolve("load").resolve(duplicateRun));
        CliOutput diagnosticCli = executeCli(root, scenario, diagnosticOutput, duplicateRun, "json");
        assertEquals(2, diagnosticCli.exitCode, diagnosticCli.stderr + "\n" + diagnosticCli.stdout);
        assertFalse(diagnosticCli.stderr.contains(root.toString()), diagnosticCli.stderr);
        assertTrue(diagnosticCli.stderr.contains("$ATT_HOME/target/review-mix-diagnostic-" + suffix
                + "/load/" + duplicateRun), diagnosticCli.stderr);
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
        assertEquals(att.Version.PRODUCT, profile.get("attVersion"));
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
        CliOutput cli = executeCli(root, scenario, output, runId, "json", extra);
        assertEquals(0, cli.exitCode, "load CLI failed: " + cli.stderr + "\n" + cli.stdout);
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

    private CliOutput executeCli(Path root, Path scenario, Path output, String runId,
                                 String format, String... extra) throws Exception {
        Path stdout = temp.resolve(runId + ".stdout");
        Path stderr = temp.resolve(runId + ".stderr");
        List<String> arguments = new java.util.ArrayList<String>(Arrays.asList(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                "att.FrameworkRunner", "load", scenario.toString(), "--output-dir", output.toString(),
                "--run-id", runId, "--format", format));
        arguments.addAll(Arrays.asList(extra));
        ProcessBuilder command = new ProcessBuilder(arguments);
        command.directory(root.toFile());
        command.redirectOutput(stdout.toFile());
        command.redirectError(stderr.toFile());
        Process process = command.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "load CLI did not finish: " + runId);
        return new CliOutput(process.exitValue(), read(stdout), read(stderr));
    }

    private Path writeScenario(String name, String content) throws Exception {
        Path file = temp.resolve(name);
        return LoadTestSupport.writeScenario(file, content);
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

    private static final class CliOutput {
        final int exitCode;
        final String stdout;
        final String stderr;
        CliOutput(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode; this.stdout = stdout; this.stderr = stderr;
        }
    }
}
