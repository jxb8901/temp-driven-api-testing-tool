package att.load;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.core.ExecutionOptions;
import att.core.ResultStatus;
import att.validation.DiagnosticException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(invalidThink));
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

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<IterationResult> first = pool.submit(() -> executor.execute(IterationRequest.closed("i-1", 1, "STEADY", Instant.now(), "VU-1", Collections.singletonMap("input", "one"))));
            Future<IterationResult> second = pool.submit(() -> executor.execute(IterationRequest.closed("i-2", 2, "STEADY", Instant.now(), "VU-2", Collections.singletonMap("input", "two"))));
            IterationResult a = first.get(); IterationResult b = second.get();
            assertEquals(ResultStatus.PASS, a.status()); assertEquals(ResultStatus.PASS, b.status());
            assertEquals("i-1", a.context().resolve("LOAD.iterationId"));
            assertEquals("i-2", b.context().resolve("LOAD.iterationId"));
            assertEquals("VU-1", a.context().resolve("LOAD.userId"));
            assertEquals("VU-2", b.context().resolve("LOAD.userId"));
            assertEquals("one", a.context().resolve("CASE.inputs.input"));
            assertEquals("i-1", a.context().resolve("CASE.VARS.iteration"));
            assertEquals("i-2", b.context().resolve("CASE.VARS.iteration"));

            IterationResult arrival = executor.execute(IterationRequest.arrivalRate("arrival-1", 3, "RAMP_UP", Instant.now(), Collections.singletonMap("input", "arrival")));
            assertEquals(ResultStatus.PASS, arrival.status());
            assertEquals("arrivalRate", arrival.context().resolve("LOAD.model"));
            assertNull(arrival.context().resolve("LOAD.userId"));
        } finally { pool.shutdownNow(); }
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
        assertEquals("closed", result.context().resolve("LOAD.model"));
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
                + "  iteration:\n    type: assign\n    name: iteration\n    expression: \"${LOAD.iterationId}\"\n"
                + "  phase:\n    type: log\n    message: \"${LOAD.model}/${LOAD.phase}/${CASE.input}\"\n").getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/load/echo/flow.yaml"), (
                "schemaVersion: att-flow/v3.0\nid: load.echo.v1\nname: Load Echo\ndescription: load flow\nactions:\n"
                + "  echo:\n    type: log\n    message: \"${LOAD.iterationId}\"\n").getBytes(StandardCharsets.UTF_8));
        return project;
    }

    private Path write(Path project, String name, String content) throws Exception {
        Path file = project.resolve(name); Files.write(file, content.getBytes(StandardCharsets.UTF_8)); return file;
    }
}
