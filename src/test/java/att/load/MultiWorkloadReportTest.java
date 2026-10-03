package att.load;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultiWorkloadReportTest {
    @TempDir Path temp;

    @Test void writesSchemaValidAggregateAndPerWorkloadReport() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path scenarioFile = temp.resolve("report-v11.yaml");
        Files.write(scenarioFile, (
                "schemaVersion: att-load/v1.3\n"
                + "workloads:\n"
                + "- id: primary\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {arrivalRate: 10/s, duration: 250ms, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "- id: secondary\n  target: {type: tool, id: sample.getSeq, arguments: {seqLen: 8}}\n"
                + "  load: {arrivalRate: 5/s, duration: 250ms, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "thresholds: {errorRate: \"< 100%\"}\n").getBytes(StandardCharsets.UTF_8));
        LoadScenario scenario = new LoadScenarioLoader(root).load(scenarioFile);
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        LoadScenario first = scenario.forWorkload(scenario.workloads().get(0));
        LoadTarget seedTarget = new LoadTargetResolver(root, config).resolve(first);
        LoadRunResult result;
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            IterationExecutor seed = new IterationExecutor(root, config, seedTarget, resources, temp.resolve("out"));
            result = LoadRunCoordinator.runFrom(scenario, seed, "multi-report", new LoadEvidenceStore(LoadEvidencePolicy.from(scenario)), temp.resolve("out"));
        }
        result = result.withThresholds(new LoadThresholdEvaluator().evaluate(scenario, result.metrics()));
        Path report = new LoadReportWriter().write(temp.resolve("out"), result);
        Path summary = temp.resolve("out/load/multi-report/load-summary.json");
        String jsonText = new String(Files.readAllBytes(summary), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> att.validation.JsonSchemaVerifier.verifyJson(
                root.resolve("schemas/att-load-summary-v1.1.schema.json"), jsonText));
        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper().readValue(jsonText, Map.class);
        assertTrue(json.containsKey("workloads"));
        @SuppressWarnings("unchecked") Map<String, Object> workloads = (Map<String, Object>) json.get("workloads");
        assertTrue(workloads.containsKey("primary"));
        assertTrue(workloads.containsKey("secondary"));
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("<h2>Workloads</h2>"));
        assertTrue(html.contains("primary"));
        assertTrue(html.contains("secondary"));
        assertTrue(html.contains("aggregate raw-latency collector"));
    }

    @Test void executesClosedMixAndReportsPerEntryMetrics() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path scenarioFile = temp.resolve("mixed-report.yaml");
        Files.write(scenarioFile, ("schemaVersion: att-load/v1.6\nseed: 73\nworkloads:\n"
                + "- id: checkout\n  inputs: {region: HK}\n  mix:\n"
                + "  - id: date\n    weight: 60\n    target: {type: tool, id: sample.getAcDate}\n"
                + "  - id: sequence\n    weight: 40\n    target: {type: tool, id: sample.getSeq, arguments: {seqLen: 8}}\n"
                + "  load: {users: 8, duration: 1s}\n").getBytes(StandardCharsets.UTF_8));
        LoadScenario scenario = new LoadScenarioLoader(root).load(scenarioFile);
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        LoadScenario firstMix = scenario.forMixEntry(scenario.workload(), scenario.workload().mix().get(0));
        LoadTarget seedTarget = new LoadTargetResolver(root, config).resolve(firstMix);
        LoadRunResult result;
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            IterationExecutor seed = new IterationExecutor(root, config, seedTarget, resources, temp.resolve("mixed-out"));
            result = LoadRunCoordinator.runFrom(scenario, seed, "mixed-report", new LoadEvidenceStore(LoadEvidencePolicy.from(scenario)), temp.resolve("mixed-out"));
        }
        Path report = new LoadReportWriter().write(temp.resolve("mixed-out"), result);
        Path summary = temp.resolve("mixed-out/load/mixed-report/load-summary.json");
        String jsonText = new String(Files.readAllBytes(summary), StandardCharsets.UTF_8);
        att.validation.JsonSchemaVerifier.verifyJson(root.resolve("schemas/att-load-summary-v1.1.schema.json"), jsonText);
        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper().readValue(jsonText, Map.class);
        @SuppressWarnings("unchecked") Map<String, Object> workloads = (Map<String, Object>) json.get("workloads");
        @SuppressWarnings("unchecked") Map<String, Object> checkout = (Map<String, Object>) workloads.get("checkout");
        @SuppressWarnings("unchecked") Map<String, Object> mixes = (Map<String, Object>) checkout.get("mix");
        assertEquals(2, mixes.size());
        long selected = 0L;
        for (Object value : mixes.values()) {
            @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) value;
            selected += ((Number) entry.get("selectedCount")).longValue();
            assertTrue(entry.containsKey("metrics"));
        }
        assertTrue(selected > 0L);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("checkout mix"));
        assertTrue(html.contains("sequence"));
        assertTrue(html.contains("mixed targets"));
    }
}
