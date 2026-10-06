package att.load;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.core.CaseRuntimeContext;
import att.core.TestCase;
import att.template.DefaultBuiltInProvider;
import att.template.UnifiedTemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LoadExecutionIdPatternTest {
    @TempDir Path root;

    @Test void validatesInitializationScopeAndRejectsStatefulExpressions() {
        LoadExecutionIdPattern.validate("${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}", LoadScenario.Model.CLOSED);
        LoadExecutionIdPattern.validate("${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.ITERATION}", LoadScenario.Model.ARRIVAL_RATE);
        for (String invalid : Arrays.asList(
                "${EXEC.LOAD.user_id}",
                "${EXEC.ID}",
                "${EXEC.OUTPUT_DIR}",
                "${EXEC.ACTIONS.call.output.result}",
                "${EXEC.LOAD.ITERATION}/child",
                "#{seq.next()}",
                "#{formatDate(value='2020-01-01T00:00:00Z', pattern='yyyy')}",
                "#{date.format(value='2020-01-01T00:00:00Z', pattern='yyyy')}",
                "#{file.delete('x')}",
                "#{deleteFile('x')}",
                "#{file.copy(source='a', target='b')}",
                "#{copyFile(source='a', target='b')}",
                "#{file.move(source='a', target='b')}",
                "#{moveFile(source='a', target='b')}",
                "#{file.mkdirs('x')}",
                "#{makeDirectories('x')}",
                "#{directoryExists('x')}")) {
            assertThrows(IllegalArgumentException.class, () -> LoadExecutionIdPattern.validate(invalid, LoadScenario.Model.CLOSED), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> LoadExecutionIdPattern.validate("${EXEC.LOAD.USER_ID}", LoadScenario.Model.ARRIVAL_RATE));
    }

    @Test void evaluatesAgainstPreExecutionRuntimeContextAndPublishesTheGeneratedIdentity() throws Exception {
        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("region", "hk");
        TestCase testCase = new TestCase(1, "load", "tool", "iteration-7", Collections.emptyList(),
                inputs, Collections.emptyMap(), "");
        Path output = root.resolve("output").resolve("pending");
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, output, "pending", "RUN-42", output,
                output.resolve("case.log"), "load", "2026-09-30T12:00:00Z", "2026-09-30T11:00:00Z");
        context.setProject(root);
        context.setLoadSourceMetadata(root.resolve("payment.yaml"), "payment");
        context.setTargetMetadata("tool", "sample.echo");
        context.setLoad("RUN-42", "closed", "iteration-7", 7L, "STEADY",
                "2026-09-30T12:00:00Z", "VU-3", "2026-09-30T11:00:00Z");
        context.setLoadWorkload("payments", "tool", "sample.echo");
        context.setTemplateMetadata("sample.echo", root.resolve("templates/sample.echo"));
        context.beginExecutionIdInitialization();

        String format = "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}-#{upper(${EXEC.INPUT.region})}";
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null, null, null, null, new DefaultBuiltInProvider());
        String id = LoadExecutionIdPattern.evaluate(format, LoadScenario.Model.CLOSED, context, engine, null);
        assertEquals("RUN-42-payments-VU-3-7-HK", id);

        Path workspace = root.resolve("output").resolve("load").resolve(id);
        context.finishExecutionIdInitialization(id, workspace, workspace.resolve("case.log"));
        assertEquals(id, context.resolve("EXEC.ID"));
        assertEquals("RUN-42", context.resolve("EXEC.RUN_ID"));
    }

    @Test void reservesUniqueDefaultIdsAcrossConcurrentWorkloadsAndPreservesRunId() throws Exception {
        installConfig();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config.yaml"), root);
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            Path runOutput = root.resolve("output/load/RUN");
            resources.initializeExecutionNamespace(runOutput);
            Set<String> ids = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> tasks = new ArrayList<Future<?>>();
                for (int w = 0; w < 4; w++) {
                    final String workload = "work" + w;
                    tasks.add(pool.submit(() -> {
                        for (int n = 1; n <= 50; n++) {
                            IterationRequest request = IterationRequest.closed("RUN", "iteration-" + n, n,
                                    "STEADY", Instant.now(), "VU-" + workload, Collections.emptyMap())
                                    .withWorkloadId(workload);
                            String id = resources.nextDefaultExecutionId(request.runId());
                            resources.reserveExecutionId(id, request, false, runOutput);
                            assertTrue(ids.add(id));
                            assertEquals("RUN", request.runId());
                        }
                    }));
                }
                for (Future<?> task : tasks) task.get();
            } finally {
                pool.shutdownNow();
            }
            assertEquals(200, ids.size());
            IterationRequest duplicate = IterationRequest.closed("RUN", "duplicate", 1, "STEADY",
                    Instant.now(), "VU-1", Collections.emptyMap()).withWorkloadId("work0");
            assertDoesNotThrow(() -> resources.reserveExecutionId(ids.iterator().next(), duplicate, false, runOutput));
            resources.reserveExecutionId("custom-id", duplicate, true, runOutput);
            assertThrows(IllegalArgumentException.class,
                    () -> resources.reserveExecutionId("CUSTOM-ID", duplicate, true, runOutput));
        }
        try (LoadRunResources fresh = new LoadRunResources(root, config)) {
            assertEquals("RUN-execution-1", fresh.nextDefaultExecutionId("RUN"));
        }
    }

    @Test void customIdReservationsAreAtomicAndExistingOutputIsRejectedAtStartup() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isSynchronized(LoadRunResources.class
                .getMethod("initializeExecutionNamespace", Path.class).getModifiers()),
                "scheduled iterations must use the lock-free initialized namespace fast path");
        installConfig();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config.yaml"), root);
        Path runOutput = root.resolve("output/load/custom-run");
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            resources.initializeExecutionNamespace(runOutput);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<Boolean>> attempts = new ArrayList<Future<Boolean>>();
                for (int index = 0; index < 32; index++) {
                    final int item = index;
                    attempts.add(pool.submit(() -> {
                        IterationRequest request = IterationRequest.closed("custom-run", "iteration-" + item, item + 1,
                                "STEADY", Instant.now(), "VU-1", Collections.emptyMap());
                        try {
                            resources.reserveExecutionId("shared-custom-id", request, true, runOutput);
                            return true;
                        } catch (IllegalArgumentException duplicate) { return false; }
                    }));
                }
                int winners = 0;
                for (Future<Boolean> attempt : attempts) if (attempt.get()) winners++;
                assertEquals(1, winners);
            } finally { pool.shutdownNow(); }
        }

        Path occupied = root.resolve("occupied/load/old-run/samples");
        Files.createDirectories(occupied);
        Files.write(occupied.resolve("retained"), new byte[] {1});
        try (LoadRunResources resources = new LoadRunResources(root, config)) {
            assertThrows(IllegalArgumentException.class,
                    () -> resources.initializeExecutionNamespace(root.resolve("occupied/load/old-run")));
        }
    }

    @Test void loadsCurrentScenarioIdFormatAndRejectsHistoricalSchema() throws Exception {
        installConfig();
        String yaml = "schemaVersion: att-load/v1.3\nexecution:\n  execIdFormat: '${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}'\n"
                + "workloads:\n  - id: one\n    target: {type: template, id: T}\n    load: {users: 1, duration: 1s}\n"
                + "evidence: {resources: {output: none}}\n";
        Path path = root.resolve("load.yaml");
        Files.write(path, yaml.getBytes("UTF-8"));
        LoadScenario scenario = new LoadScenarioLoader(root).load(path);
        assertEquals("${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}", scenario.execIdFormat());
        assertFalse(scenario.resourceOutputEnabled());
        assertEquals(scenario.execIdFormat(), scenario.forWorkload(scenario.workload()).execIdFormat());
        assertFalse(scenario.forWorkload(scenario.workload()).resourceOutputEnabled());
        Files.write(path, yaml.replace("v1.3", "v1.2").getBytes("UTF-8"));
        assertThrows(Exception.class, () -> new LoadScenarioLoader(root).load(path));
    }

    private void installConfig() throws Exception {
        att.TestSchemas.install(root);
        Files.write(root.resolve("config.yaml"), "schemaVersion: att-config/v2.10\n".getBytes("UTF-8"));
    }
}
