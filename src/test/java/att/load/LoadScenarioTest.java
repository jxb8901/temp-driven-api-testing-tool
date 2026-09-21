package att.load;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.core.ExecutionOptions;
import att.core.ResultStatus;
import att.core.TestCase;
import att.validation.DiagnosticException;
import att.validation.JsonSupport;
import att.validation.PackageValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LoadScenarioTest {
    @TempDir Path temp;

    @Test void validatesBothWorkloadModelsAndExplicitOverridesWin() throws Exception {
        Path project = project();
        Path closed = write(project, "closed.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load:\n  users: 2\n  duration: 5s\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(closed);
        assertEquals(LoadScenario.Model.CLOSED, scenario.model());
        assertEquals(2, scenario.users());
        assertEquals(5000L, scenario.duration().toMillis());

        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", "closed.yaml", "--users", "7", "--duration", "2s"});
        LoadScenario overridden = new LoadScenarioLoader(project).load(closed, LoadOverrides.from(options));
        assertEquals(7, overridden.users());
        assertEquals(2000L, overridden.duration().toMillis());

        Path arrival = write(project, "arrival.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: flow, id: load.echo.v1}\n"
                + "load:\n  arrivalRate: 100/s\n  duration: 1m\n  maxConcurrent: 4\n  overloadPolicy: drop\n");
        LoadScenario open = new LoadScenarioLoader(project).load(arrival);
        assertEquals(LoadScenario.Model.ARRIVAL_RATE, open.model());
        assertEquals(100.0, open.arrivalRatePerSecond(), 0.0001);
        assertEquals(4, open.maxConcurrent());

        Path closedArrivalThreshold = write(project, "closed-arrival-threshold.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load: {users: 1, duration: 1s}\n"
                + "thresholds: {achievedArrivalRate: '>= 1%'}\n");
        DiagnosticException closedThresholdError = assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(closedArrivalThreshold));
        assertEquals("thresholds.achievedArrivalRate", closedThresholdError.field());

        Path arrivalThroughputThreshold = write(project, "arrival-throughput-threshold.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load: {arrivalRate: 1/s, duration: 1s, maxConcurrent: 1, overloadPolicy: drop}\n"
                + "thresholds: {minThroughput: '>= 1/s', achievedArrivalRate: '>= 99%'}\n");
        LoadScenario arrivalThresholds = new LoadScenarioLoader(project).load(arrivalThroughputThreshold);
        assertEquals(">= 1/s", arrivalThresholds.thresholds().get("minThroughput"));
        assertEquals(">= 99%", arrivalThresholds.thresholds().get("achievedArrivalRate"));
    }

    @Test void rejectsAmbiguousWorkloadAndClosedOnlyOptionsWithSourceDiagnostics() throws Exception {
        Path project = project();
        Path invalid = write(project, "invalid.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load:\n  users: 2\n  arrivalRate: 10/s\n  duration: 1s\n");
        DiagnosticException error = assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(invalid));
        assertEquals("ATT-LOAD-001", error.code());
        assertEquals(invalid.toString(), error.file());
        assertTrue(error.field() != null);

        Path invalidThink = write(project, "invalid-think.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load: {arrivalRate: 10/s, duration: 1s, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "execution: {thinkTime: 10ms}\n");
        DiagnosticException thinkError = assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(invalidThink));
        assertEquals("execution.thinkTime", thinkError.field());

        Path invalidArguments = write(project, "invalid-arguments.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE, arguments: {ignored: true}}\n"
                + "load: {users: 1, duration: 1s}\n");
        DiagnosticException argumentsError = assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(invalidArguments));
        assertEquals("target.arguments", argumentsError.field());

        Path invalidMaxConcurrent = write(project, "invalid-max-concurrent.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load: {arrivalRate: 10/s, duration: 1s, maxConcurrent: invalid, overloadPolicy: drop}\n");
        DiagnosticException maxConcurrentError = assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(invalidMaxConcurrent));
        assertEquals("load.maxConcurrent", maxConcurrentError.field());
    }

    @Test void iterationExecutorIsolatesLoadAndCaseStateForClosedAndArrivalIterations() throws Exception {
        Path project = project();
        Path scenarioFile = write(project, "closed.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "inputs: {input: validated}\n"
                + "load: {users: 2, duration: 1s}\n");
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), null, null);
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        final IterationExecutor executor = new IterationExecutor(project, config, target);
        final Instant iterationStarted = Instant.parse("2026-09-19T09:00:00Z");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<IterationResult> first = pool.submit(() -> executor.execute(IterationRequest.closed("run-29", "i-1", 1, "STEADY", iterationStarted, "VU-1", Collections.singletonMap("input", "one"))));
            Future<IterationResult> second = pool.submit(() -> executor.execute(IterationRequest.closed("run-29", "i-2", 2, "STEADY", iterationStarted, "VU-2", Collections.singletonMap("input", "two"))));
            IterationResult a = first.get(); IterationResult b = second.get();
            assertEquals(ResultStatus.PASS, a.status()); assertEquals(ResultStatus.PASS, b.status());
            assertEquals("i-1", a.context().resolve("EXEC.ID"));
            assertEquals(iterationStarted.toString(), a.context().resolve("EXEC.STARTED_AT"));
            assertEquals("i-1", a.context().resolve("EXEC.LOAD.ITERATION_ID"));
            assertEquals("i-2", b.context().resolve("EXEC.LOAD.ITERATION_ID"));
            assertEquals("run-29", a.context().resolve("EXEC.LOAD.RUN_ID"));
            assertEquals("run-29", b.context().resolve("EXEC.LOAD.RUN_ID"));
            assertEquals("load", a.context().resolve("EXEC.MODE"));
            assertEquals("VU-1", a.context().resolve("EXEC.LOAD.USER_ID"));
            assertEquals("VU-2", b.context().resolve("EXEC.LOAD.USER_ID"));
            assertEquals("one", a.context().resolve("EXEC.INPUT.input"));
            assertEquals("closed/STEADY/one", a.context().resolve("EXEC.ACTIONS.phase.output.result"));
            assertEquals("closed/STEADY/two", b.context().resolve("EXEC.ACTIONS.phase.output.result"));
            assertEquals("one", a.context().resolve("EXEC.VARS.flowInput"));
            assertEquals("two", b.context().resolve("EXEC.VARS.flowInput"));
            assertNull(a.context().resolve("output.result"));
            assertNull(a.context().resolve("EXEC.INPUT.inputs.input"));
            assertEquals("one", a.context().resolve("CASE.inputs.input"));
            assertEquals("LOAD_TEMPLATE", a.context().resolve("META.TARGET.id"));
            assertEquals("load", a.context().resolve("META.SOURCE.type"));
            assertEquals("closed", a.context().resolve("META.SOURCE.scenario"));
            assertNull(a.context().resolve("META.SOURCE.caseId"));
            assertFalse(a.context().metadataTree().toString().contains("i-1"));
            assertEquals("closed", a.context().resolve("EXEC.LOAD.MODEL"));
            assertNull(a.context().resolve("LOAD.model"));
            assertThrows(IllegalArgumentException.class, () -> a.context().put("EXEC.LOAD.MODEL", "arrivalRate"));
            assertEquals("i-1", a.context().resolve("CASE.VARS.iteration"));
            assertEquals("i-2", b.context().resolve("CASE.VARS.iteration"));
            assertFalse(Files.exists(a.outputDirectory()), "successful load iterations should not materialize case workspaces by default");
            assertFalse(Files.exists(b.outputDirectory()), "successful load iterations should not materialize case workspaces by default");

            IterationResult arrival = executor.execute(IterationRequest.arrivalRate("run-29", "arrival-1", 3, "RAMP_UP", Instant.now(), Collections.singletonMap("input", "arrival")));
            assertEquals(ResultStatus.PASS, arrival.status());
            assertEquals("arrivalRate", arrival.context().resolve("EXEC.LOAD.MODEL"));
            assertNull(arrival.context().resolve("EXEC.LOAD.USER_ID"));
        } finally { pool.shutdownNow(); }
    }

    @Test void iterationWorkspaceAndFailureEvidenceUseResolvedLoadOutputRoot() throws Exception {
        Path project = project();
        Files.createDirectories(project.resolve("templates/FAIL_TEMPLATE"));
        write(project, "templates/FAIL_TEMPLATE/template.yaml", "schemaVersion: att-template/v3.0\n"
                + "name: FAIL_TEMPLATE\ndescription: retained failure\nactions:\n"
                + "  verify: {type: assert, assert: \"${EXEC.INPUT.value} == 'expected'\", expected: expected, actual: \"${EXEC.INPUT.value}\"}\n");
        Path scenarioFile = write(project, "failure.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: FAIL_TEMPLATE}\ninputs: {value: actual}\n"
                + "load: {users: 1, duration: 1s}\n");
        FrameworkConfig config = new FrameworkConfig(Paths.get("configured-output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), null, null);
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        Path outputRoot = temp.resolve("cli-output");
        LoadRunResources resources = new LoadRunResources(project, config);
        try {
            IterationResult result = new IterationExecutor(project, config, target, resources, outputRoot).execute(
                    IterationRequest.closed("resolved-run", "resolved-iteration", 1, "STEADY", Instant.now(), "VU-1", scenario.inputs()));
            assertEquals(ResultStatus.FAIL, result.status());
            Path expectedWorkspace = outputRoot.resolve("load/resolved-run/iterations")
                    .resolve(LoadIsolation.workspaceName("resolved-run", "resolved-iteration", 1)).toAbsolutePath().normalize();
            assertEquals(expectedWorkspace, result.outputDirectory().toAbsolutePath().normalize());
            assertTrue(Files.isDirectory(expectedWorkspace));
            assertTrue(Files.isRegularFile(expectedWorkspace.resolve("case.log")));
            assertNotNull(result.evidenceRef());

            LoadEvidenceStore evidence = new LoadEvidenceStore(new LoadEvidencePolicy(
                    LoadEvidencePolicy.Success.NONE, LoadEvidencePolicy.Failure.FULL, 0.0, 10));
            long now = System.currentTimeMillis();
            evidence.onEvent(LoadEvent.completed("resolved-run", "closed", "STEADY", "resolved-iteration", "VU-1", 1,
                    now, now, now + 1, result.status(), result.evidenceRef()));
            Path runDirectory = outputRoot.resolve("load/resolved-run");
            Map<String, Object> written = evidence.write(runDirectory);
            assertEquals(1, written.get("count"));
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) written.get("items");
            Path eventFile = runDirectory.resolve(String.valueOf(items.get(0).get("path")));
            @SuppressWarnings("unchecked") Map<String, Object> event = JsonSupport.mapper().readValue(eventFile.toFile(), Map.class);
            @SuppressWarnings("unchecked") Map<String, Object> reference = (Map<String, Object>) event.get("evidence");
            assertEquals("iterations/" + expectedWorkspace.getFileName(), reference.get("workspace"));
            assertEquals("iterations/" + expectedWorkspace.getFileName() + "/case.log", reference.get("caseLog"));
        } finally { resources.close(); }
    }

    @SuppressWarnings("unchecked")
    @Test void concurrentIterationsIsolateNestedMapAndListInputMutation() throws Exception {
        Path project = project();
        Files.createDirectories(project.resolve("templates/NESTED_TEMPLATE"));
        write(project, "templates/NESTED_TEMPLATE/template.yaml", "schemaVersion: att-template/v3.0\n"
                + "name: NESTED_TEMPLATE\ndescription: nested input isolation\nactions:\n"
                + "  mapValue: {type: log, message: \"${EXEC.INPUT.payload.value}\"}\n"
                + "  listValue: {type: log, message: \"${EXEC.INPUT.payload.items[0].value}\"}\n");
        Path scenarioFile = write(project, "nested.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: NESTED_TEMPLATE}\nload: {users: 2, duration: 1s}\n");
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), null, null);
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);

        Map<String, Object> source = new LinkedHashMap<String, Object>();
        Map<String, Object> sourcePayload = new LinkedHashMap<String, Object>();
        sourcePayload.put("value", "original");
        List<Object> sourceItems = new ArrayList<Object>();
        Map<String, Object> sourceItem = new LinkedHashMap<String, Object>(); sourceItem.put("value", "original-list");
        sourceItems.add(sourceItem); sourcePayload.put("items", sourceItems); source.put("payload", sourcePayload);
        IterationRequest first = IterationRequest.closed("nested-run", "nested-a", 1, "STEADY", Instant.now(), "VU-1", source);
        IterationRequest second = IterationRequest.closed("nested-run", "nested-b", 2, "STEADY", Instant.now(), "VU-2", source);

        Map<String, Object> firstPayload = (Map<String, Object>) first.inputs().get("payload");
        ((Map<String, Object>) ((List<?>) firstPayload.get("items")).get(0)).put("value", "A-list");
        firstPayload.put("value", "A");
        Map<String, Object> secondPayload = (Map<String, Object>) second.inputs().get("payload");
        ((Map<String, Object>) ((List<?>) secondPayload.get("items")).get(0)).put("value", "B-list");
        secondPayload.put("value", "B");

        assertEquals("original", sourcePayload.get("value"));
        assertEquals("original-list", ((Map<?, ?>) sourceItems.get(0)).get("value"));
        assertEquals("A", firstPayload.get("value"));
        assertEquals("B", secondPayload.get("value"));

        LoadRunResources resources = new LoadRunResources(project, config);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            IterationExecutor executor = new IterationExecutor(project, config, target, resources);
            Future<IterationResult> firstResult = pool.submit(() -> executor.execute(first));
            Future<IterationResult> secondResult = pool.submit(() -> executor.execute(second));
            IterationResult a = firstResult.get(); IterationResult b = secondResult.get();
            assertEquals(ResultStatus.PASS, a.status()); assertEquals(ResultStatus.PASS, b.status());
            assertEquals("A", a.context().resolve("EXEC.INPUT.payload.value"));
            assertEquals("A-list", a.context().resolve("EXEC.INPUT.payload.items[0].value"));
            assertEquals("B", b.context().resolve("EXEC.INPUT.payload.value"));
            assertEquals("B-list", b.context().resolve("EXEC.INPUT.payload.items[0].value"));
            assertEquals("original", sourcePayload.get("value"));
            assertEquals("original-list", ((Map<?, ?>) sourceItems.get(0)).get("value"));
        } finally {
            pool.shutdownNow();
            resources.close();
        }
    }

    @Test void cancellationRetainsFailureEvidenceAndStopsActiveToolIteration() throws Exception {
        Path project = project();
        Files.createDirectories(project.resolve("templates/SLOW_TEMPLATE"));
        Path started = temp.resolve("slow-started");
        Path completed = temp.resolve("slow-completed");
        String command = "/bin/sh -c 'touch " + started + "; sleep 1; touch " + completed + "'";
        write(project, "templates/SLOW_TEMPLATE/template.yaml", "schemaVersion: att-template/v3.0\n"
                + "name: SLOW_TEMPLATE\ndescription: cancellable load action\nactions:\n"
                + "  run: {type: tool, call: \"#{slow()}\"}\n");
        Path scenarioFile = write(project, "slow.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: SLOW_TEMPLATE}\nload: {users: 1, duration: 10s}\n");
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("slow", new ToolConfig("slow", "Slow", "Slow", command, "txt", Collections.emptyMap())), null, null);
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        Path outputRoot = temp.resolve("cancel-output");
        LoadRunResources resources = new LoadRunResources(project, config);
        LoadEvidenceStore evidence = new LoadEvidenceStore(new LoadEvidencePolicy(
                LoadEvidencePolicy.Success.NONE, LoadEvidencePolicy.Failure.FULL, 0.0, 10));
        ClosedVuScheduler scheduler = new ClosedVuScheduler(scenario,
                new IterationExecutor(project, config, target, resources, outputRoot), "cancel-run", evidence);
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<LoadRunResult> future = runner.submit(scheduler::run);
            long deadline = System.currentTimeMillis() + 3000L;
            while (!Files.exists(started) && System.currentTimeMillis() < deadline) Thread.sleep(10L);
            assertTrue(Files.exists(started), "the active iteration did not start");
            scheduler.cancel();
            future.get(5, TimeUnit.SECONDS);
            Thread.sleep(1500L);
            assertFalse(Files.exists(completed), "the cancelled tool completed after scheduler shutdown");
            assertEquals(1, evidence.events().size());
            assertEquals(ResultStatus.ERROR, evidence.events().get(0).status());
            assertNotNull(evidence.events().get(0).evidence());
            assertFalse(resources.isClosed());
        } finally {
            scheduler.close();
            runner.shutdownNow();
            resources.close();
            assertTrue(resources.isClosed());
        }
    }

    @Test void loadValidationIsModeAwareAndOptionalLoadPathsRemainPortable() throws Exception {
        Path project = project();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), null, null);
        Files.createDirectories(project.resolve("templates/STRICT_TEMPLATE"));
        Files.createDirectories(project.resolve("templates/OPTIONAL_TEMPLATE"));
        write(project, "templates/STRICT_TEMPLATE/template.yaml", "schemaVersion: att-template/v3.0\n"
                + "name: STRICT_TEMPLATE\ndescription: strict load context\nactions:\n"
                + "  strict:\n    type: log\n    message: \"${EXEC.LOAD.MODEL}\"\n");
        write(project, "templates/OPTIONAL_TEMPLATE/template.yaml", "schemaVersion: att-template/v3.0\n"
                + "name: OPTIONAL_TEMPLATE\ndescription: optional load context\nactions:\n"
                + "  optional:\n    type: log\n    message: \"${EXEC.LOAD.USER_ID?}/${EXEC.INPUT.input}\"\n");

        Path strictFile = write(project, "strict.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: STRICT_TEMPLATE}\ninputs: {input: strict}\nload: {users: 1, duration: 1s}\n");
        LoadScenario strict = new LoadScenarioLoader(project).load(strictFile);
        LoadTarget strictTarget = new LoadTargetResolver(project, config).resolve(strict);
        LoadExecutionContextAdapter strictAdapter = new LoadExecutionContextAdapter(project, config, strictTarget);
        TestCase strictCase = strictAdapter.testCase("debug-strict", strict.inputs());
        DiagnosticException error = assertThrows(DiagnosticException.class, () -> new PackageValidator(project, config)
                .validateDebugTarget(strictTarget.template(), strictCase, strictAdapter.stage(), strictTarget.flows(),
                        strict.source(), "debug", strict.inputs()));
        assertTrue(error.format().contains("EXEC.LOAD.MODEL"), error.format());
        new LoadTargetValidator(project, config).validate(strict, strictTarget);

        Path optionalFile = write(project, "optional.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: OPTIONAL_TEMPLATE}\ninputs: {input: optional}\nload: {users: 1, duration: 1s}\n");
        LoadScenario optional = new LoadScenarioLoader(project).load(optionalFile);
        LoadTarget optionalTarget = new LoadTargetResolver(project, config).resolve(optional);
        LoadExecutionContextAdapter optionalAdapter = new LoadExecutionContextAdapter(project, config, optionalTarget);
        TestCase optionalCase = optionalAdapter.testCase("debug-optional", optional.inputs());
        new PackageValidator(project, config).validateDebugTarget(optionalTarget.template(), optionalCase, optionalAdapter.stage(),
                optionalTarget.flows(), optional.source(), "debug", optional.inputs());
        new LoadTargetValidator(project, config).validate(optional, optionalTarget);
    }

    @Test void loadInputNamedInputsRemainsAFlatBusinessField() throws Exception {
        Path project = project();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), null, null);
        Path scenarioFile = write(project, "collision.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\nload: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("inputs", "business-value");
        inputs.put("input", "ordinary-value");
        LoadExecutionContextAdapter.Prepared prepared = new LoadExecutionContextAdapter(project, config, target)
                .prepare(IterationRequest.closed("run-inputs", "iteration-inputs", 1, "STEADY", Instant.now(), "VU-1", inputs),
                        temp.resolve("iteration-inputs"), temp.resolve("iteration-inputs/case.log"));
        assertEquals("business-value", prepared.context().resolve("EXEC.INPUT.inputs"));
        assertEquals("business-value", prepared.context().resolve("CASE.inputs"));
        assertNull(prepared.context().resolve("EXEC.INPUT.inputs.value"));
    }

    @Test void resolvesAndExecutesAConfiguredToolTargetThroughTheSameExecutor() throws Exception {
        Path project = project();
        Path scenarioFile = write(project, "tool.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: tool, id: echo}\nload: {users: 1, duration: 1s}\n");
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("echo", new ToolConfig("echo", "Echo", "Echo", "/bin/echo load", "txt", Collections.emptyMap())), null, null);
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        IterationResult result = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("tool-1", 1, "STEADY", Instant.now(), "VU-1", Collections.emptyMap()));
        assertEquals(ResultStatus.PASS, result.status());
        assertEquals("closed", result.context().resolve("EXEC.LOAD.MODEL"));
    }

    @Test void cliRecognizesLoadAndRejectsMissingScenario() {
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", "scenario.yaml", "--arrival-rate", "100/s"});
        assertEquals("load", options.command());
        assertEquals(Paths.get("scenario.yaml"), options.loadScenario());
        assertEquals("100/s", options.loadArrivalRate());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"load"}));
    }

    private Path project() throws Exception {
        Path project = temp.resolve("project-" + System.nanoTime());
        Files.createDirectories(project.resolve("templates/LOAD_TEMPLATE"));
        Files.createDirectories(project.resolve("templates/flows/load/echo"));
        Files.createDirectories(project.resolve("schemas"));
        Files.createDirectories(project.resolve("output"));
        Files.copy(Paths.get("schemas/att-load-v1.0.schema.json"), project.resolve("schemas/att-load-v1.0.schema.json"));
        Files.copy(Paths.get("schemas/att-template-v3.0.schema.json"), project.resolve("schemas/att-template-v3.0.schema.json"));
        Files.copy(Paths.get("schemas/att-flow-v3.0.schema.json"), project.resolve("schemas/att-flow-v3.0.schema.json"));
        Files.write(project.resolve("templates/LOAD_TEMPLATE/template.yaml"), (
                "schemaVersion: att-template/v3.0\nname: LOAD_TEMPLATE\ndescription: load fixture\nactions:\n"
                + "  iteration:\n    type: assign\n    name: iteration\n    expression: \"${EXEC.LOAD.ITERATION_ID}\"\n"
                + "  phase:\n    type: log\n    message: \"${EXEC.LOAD.MODEL}/${EXEC.LOAD.PHASE}/${EXEC.INPUT.input}\"\n"
                + "  nested:\n    type: flow\n    use: load.echo.v1\n").getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/load/echo/flow.yaml"), (
                "schemaVersion: att-flow/v3.0\nid: load.echo.v1\nname: Load Echo\ndescription: load flow\nactions:\n"
                + "  echo:\n    type: assign\n    name: flowInput\n    expression: \"${EXEC.INPUT.input}\"\n").getBytes(StandardCharsets.UTF_8));
        return project;
    }

    private Path write(Path project, String name, String content) throws Exception {
        Path file = project.resolve(name); Files.write(file, content.getBytes(StandardCharsets.UTF_8)); return file;
    }
}
