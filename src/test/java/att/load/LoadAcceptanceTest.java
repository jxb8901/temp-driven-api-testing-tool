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
        List<String> examples = Arrays.asList("closed-minimal.yaml", "closed.yaml", "arrival-rate.yaml", "tool.yaml");
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

    private void assertCliLoad(Path root, Path scenario, Path output, String runId, String model) throws Exception {
        Path stdout = temp.resolve(runId + ".stdout");
        Path stderr = temp.resolve(runId + ".stderr");
        ProcessBuilder command = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                "att.FrameworkRunner", "load", scenario.toString(), "--output-dir", output.toString(),
                "--run-id", runId, "--format", "json");
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
        assertEquals("PASS", summary.get("status"));
        assertEquals(model, ((Map<?, ?>) summary.get("metrics")).get("model"));
        assertEquals("report/index.html", summary.get("report"));
        assertTrue(read(runDirectory.resolve("report/index.html")).contains("ATT Load " + runId));
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
