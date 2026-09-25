package att.load;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class MultiWorkloadThresholdTest {
    @TempDir Path temp;

    @Test void anyWorkloadOrGlobalThresholdFailureMakesRunFailWithExitOne() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path file = temp.resolve("thresholds.yaml");
        Files.write(file, (
                "schemaVersion: att-load/v1.1\n"
                + "workloads:\n"
                + "- id: constrained\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {arrivalRate: 10/s, duration: 250ms, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "  thresholds: {minThroughput: \">= 999999/s\"}\n"
                + "- id: normal\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {arrivalRate: 5/s, duration: 250ms, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "thresholds: {minThroughput: \">= 999999/s\"}\n").getBytes(StandardCharsets.UTF_8));
        LoadScenario scenario = new LoadScenarioLoader(root).load(file);
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        LoadScenario first = scenario.forWorkload(scenario.workloads().get(0));
        LoadTarget seedTarget = new LoadTargetResolver(root, config).resolve(first);
        LoadRunResult result;
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            IterationExecutor seed = new IterationExecutor(root, config, seedTarget, resources, temp.resolve("out"));
            result = LoadRunCoordinator.runFrom(scenario, seed, "threshold-run",
                    new LoadEvidenceStore(LoadEvidencePolicy.from(scenario)), temp.resolve("out"));
        }
        assertEquals(ResultStatus.FAIL, result.workloads().get("constrained").status());
        result = result.withThresholds(new LoadThresholdEvaluator().evaluate(scenario, result.metrics()));
        assertEquals(ResultStatus.FAIL, result.status());
        assertEquals(1, result.exitCode());
        assertFalse(result.thresholds().passed());
    }
}
