package att.load;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MultiWorkloadRuntimeTest {
    @TempDir Path temp;

    @Test void coordinatesIndependentArrivalWorkloadsWithAggregateAndPerWorkloadMetrics() throws Exception {
        LoadScenario scenario = load("multi-arrival.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- id: fast\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {arrivalRate: 20/s, duration: 250ms, maxConcurrent: 4, overloadPolicy: drop}\n"
                + "- id: slow\n  target: {type: tool, id: sample.getSeq, arguments: {seqLen: 8}}\n"
                + "  load: {arrivalRate: 5/s, duration: 250ms, maxConcurrent: 2, overloadPolicy: drop}\n");
        LoadRunResult result = execute(scenario, "multi-arrival-test");
        assertEquals(ResultStatus.PASS, result.status());
        assertEquals(2, result.workloads().size());
        assertEquals(25.0, ((Number) result.metrics().value("configuredArrivalRatePerSecond")).doubleValue(), 0.0001);
        assertTrue(result.workloads().get("fast").metrics().longValue("started") > 0L);
        assertTrue(result.workloads().get("slow").metrics().longValue("started") > 0L);
        assertEquals(result.workloads().get("fast").metrics().longValue("started")
                        + result.workloads().get("slow").metrics().longValue("started"),
                result.metrics().longValue("started"));
    }

    @Test void coordinatesClosedVuPoolsAndKeepsDuplicateVuNamesDistinctInAggregateMetrics() throws Exception {
        LoadScenario scenario = load("multi-closed.yaml",
                "schemaVersion: att-load/v1.1\nseed: 7\nworkloads:\n"
                + "- id: one\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {users: 1, duration: 250ms}\n  execution: {thinkTime: 20ms}\n"
                + "- id: two\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {users: 1, duration: 250ms}\n  execution: {thinkTime: {min: 10ms, max: 30ms}}\n");
        LoadRunResult result = execute(scenario, "multi-closed-test");
        assertEquals(ResultStatus.PASS, result.status());
        assertEquals(2, ((Number) result.metrics().value("configuredUsers")).intValue());
        assertTrue(((Number) result.metrics().value("maxActiveVus")).intValue() >= 2,
                "VU-1 in two workload pools must count as two active virtual users");
        assertTrue(result.workloads().get("one").metrics().longValue("completed") > 0L);
        assertTrue(result.workloads().get("two").metrics().longValue("completed") > 0L);
    }

    @Test void publishesWorkloadAndTargetIdentityOnlyInFrameworkDiagnostics() throws Exception {
        Path root = projectRoot();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        LoadScenario scenario = load("context.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- id: payment\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {users: 1, duration: 100ms}\n");
        LoadScenario child = scenario.forWorkload(scenario.workloads().get(0));
        LoadTarget target = new LoadTargetResolver(root, config).resolve(child);
        IterationRequest request = IterationRequest.closed("run", "iter-1", 1L, "STEADY", Instant.now(), "VU-1", child.inputs())
                .withWorkloadId("payment");
        CaseRuntimeContext context = new LoadExecutionContextAdapter(root, config, target)
                .prepare(request, "run-execution-1", temp.resolve("iteration"), temp.resolve("iteration/case.log")).context();
        assertEquals("payment", CaseRuntimeContext.getPath(context.diagnosticsTree(), "load.workloadId"));
        assertEquals("tool", CaseRuntimeContext.getPath(context.diagnosticsTree(), "load.targetType"));
        assertEquals("sample.getAcDate", CaseRuntimeContext.getPath(context.diagnosticsTree(), "load.targetId"));
        assertEquals("VU-1", CaseRuntimeContext.getPath(context.diagnosticsTree(), "load.userId"));
        assertNull(context.resolve("EXEC.LOAD.WORKLOAD_ID"));
    }

    @Test void aggregatePercentileComesFromCombinedObservationsRatherThanAveragingWorkloadPercentiles() {
        long start = 1_000L;
        LoadMetrics aggregate = new LoadMetrics("arrivalRate", start, 1_000L, 0, 20.0, 20);
        for (int i = 0; i < 10; i++) {
            aggregate.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "a-" + i, null,
                    i + 1, start, start, start + 10, ResultStatus.PASS).withWorkloadId("a"));
        }
        for (int i = 0; i < 10; i++) {
            aggregate.onEvent(LoadEvent.completed("r", "arrivalRate", "STEADY", "b-" + i, null,
                    i + 11, start, start, start + 1000, ResultStatus.PASS).withWorkloadId("b"));
        }
        aggregate.finish(start + 1000L);
        assertEquals(1000.0, aggregate.snapshot().doubleValue("p95Ms"), 0.0001);
    }

    @Test void invalidLaterTargetFailsBeforeCoordinatorStartsAnyWorkload() throws Exception {
        Path root = projectRoot();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        LoadScenario scenario = load("invalid-target.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- id: valid\n  target: {type: tool, id: sample.getAcDate}\n"
                + "  load: {users: 1, duration: 100ms}\n"
                + "- id: invalid\n  target: {type: tool, id: missing.tool}\n"
                + "  load: {users: 1, duration: 100ms}\n");
        LoadScenario first = scenario.forWorkload(scenario.workloads().get(0));
        LoadTarget seedTarget = new LoadTargetResolver(root, config).resolve(first);
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            IterationExecutor seed = new IterationExecutor(root, config, seedTarget, resources, temp.resolve("output"));
            Exception error = assertThrows(Exception.class, () -> LoadRunCoordinator.runFrom(scenario, seed,
                    "invalid-target-run", new LoadEvidenceStore(LoadEvidencePolicy.from(scenario)), temp.resolve("output")));
            assertTrue(message(error).contains("missing.tool") || message(error).contains("Tool"), message(error));
        }
        assertFalse(Files.exists(temp.resolve("output/load/invalid-target-run/iterations")));
    }

    private LoadRunResult execute(LoadScenario scenario, String runId) throws Exception {
        Path root = projectRoot();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        LoadScenario first = scenario.forWorkload(scenario.workloads().get(0));
        LoadTarget seedTarget = new LoadTargetResolver(root, config).resolve(first);
        LoadEvidenceStore evidence = new LoadEvidenceStore(LoadEvidencePolicy.from(scenario));
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            IterationExecutor seed = new IterationExecutor(root, config, seedTarget, resources, temp.resolve("output"));
            return LoadRunCoordinator.runFrom(scenario, seed, runId, evidence, temp.resolve("output"));
        }
    }

    private LoadScenario load(String name, String content) throws Exception {
        Path file = temp.resolve(name); Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return new LoadScenarioLoader(projectRoot()).load(file);
    }
    private static Path projectRoot() { return Paths.get("").toAbsolutePath().normalize(); }
    private static String message(Throwable value) {
        StringBuilder result = new StringBuilder();
        while (value != null) { if (value.getMessage() != null) result.append(value.getMessage()).append('\n'); value = value.getCause(); }
        return result.toString();
    }
}
