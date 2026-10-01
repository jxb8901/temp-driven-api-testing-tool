package att.load;

import att.config.FrameworkConfig;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ExecutionOptions;
import att.core.ResultStatus;
import att.core.StageCaseData;
import att.core.TestCase;
import att.debug.DebugEngine;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.StageTemplateRunner;
import att.template.UnifiedTemplateEngine;
import att.exec.ToolInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression proof that one canonical component is portable across execution modes. */
class LoadCrossModeTest {
    @TempDir Path temp;

    @Test void sameTemplateFlowAndToolRunWithCanonicalInputInTestcaseDebugAndLoad() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();

        StageTemplate template = new StageTemplateLoader(project, config.templatesRoot(), false).loadSelected("SHARED");
        FlowRegistry flows = new FlowRegistry(project, config.templatesRoot(), false);
        Map<String, Object> testcaseInput = Collections.<String, Object>singletonMap("value", "shared-value");
        CaseRuntimeContext testcase = testcaseContext(project, template, testcaseInput);
        List<att.core.ValidationResult> testcaseResults = execute(project, config, template, flows, testcase, "testcase");
        assertPortableResult(testcase, testcaseResults);

        DebugEngine.Result debug = new DebugEngine(project, config).run(ExecutionOptions.parse(new String[]{
                "debug", "template", "SHARED", "--output-dir", temp.resolve("debug-output").toString(), "--format", "json"}));
        assertEquals(ResultStatus.PASS, debug.status());
        String debugLog = new String(Files.readAllBytes(debug.logPath()), StandardCharsets.UTF_8);
        assertTrue(debugLog.contains("value=shared-value"), debugLog);
        assertTrue(debugLog.contains("flow=shared-value"), debugLog);
        assertTrue(debugLog.contains("shared-value"), debugLog);

        Path scenarioFile = write(project, "shared-load.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: SHARED}\n"
                + "inputs: {value: shared-value}\n"
                + "load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(scenarioFile);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        IterationResult load = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("cross-mode", "cross-mode-1", 1, "STEADY", Instant.now(), "VU-1", scenario.inputs()));
        assertPortableResult(load.context(), load.validations());
    }

    @Test void loadDebugPromotionEvaluatesOverridesPerExecutionWithoutSharingValues() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        write(project, "templates/SHARED/template.yaml", "schemaVersion: att-template/v3.3\n"
                + "name: SHARED\ndescription: bootstrap fixture\nactions:\n"
                + "  check:\n    type: log\n    message: 'value=${EXEC.VARS.refNo}|id=${EXEC.VARS.executionId}'\n");
        write(project, "templates/SHARED/debug.yaml", "schemaVersion: att-debug/v1.1\ninputs: {amount: 17}\n"
                + "vars: {refNo: sidecar, executionId: '${EXEC.ID}'}\n");
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", "--debug", "template", "SHARED",
                "--input", "templates/SHARED/debug.yaml", "--users", "1", "--duration", "1s",
                "--run-id", "bootstrap-load", "--output-dir", temp.resolve("load-output").toString(),
                "--set", "vars.refNo=${EXEC.INPUT.amount}"});
        Map<String, Object> promoted = new DebugEngine(project, config).loadBootstrapInputForLoad(options);
        LoadScenario scenario = new LoadScenarioLoader(project).fromDebugInput((Path) promoted.get("source"),
                options.debugTargetType(), options.debugTargetId(), map(promoted.get("inputs")), map(promoted.get("vars")), options);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);

        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationExecutor executor = new IterationExecutor(project, config, target, resources, temp.resolve("load-output"));
            IterationResult first = executor.execute(IterationRequest.closed("bootstrap-load", "i-1", 1,
                    "STEADY", Instant.now(), "VU-1", scenario.inputs()).withWorkloadId(scenario.workloadId()));
            IterationResult second = executor.execute(IterationRequest.closed("bootstrap-load", "i-2", 2,
                    "STEADY", Instant.now(), "VU-1", scenario.inputs()).withWorkloadId(scenario.workloadId()));
            assertEquals(ResultStatus.PASS, first.status());
            assertEquals(ResultStatus.PASS, second.status());
            assertEquals(17L, ((Number) first.context().require("EXEC.VARS.refNo")).longValue());
            assertEquals(first.context().resolve("EXEC.ID"), first.context().resolve("EXEC.VARS.executionId"));
            assertEquals(second.context().resolve("EXEC.ID"), second.context().resolve("EXEC.VARS.executionId"));
            assertNotEquals(first.context().resolve("EXEC.VARS.executionId"), second.context().resolve("EXEC.VARS.executionId"));
            assertTrue(String.valueOf(first.context().resolve("EXEC.ACTIONS.check.output.result")).contains("value=17|id="));
        }
    }

    @Test void loadDebugPromotesTypedToolArgumentsIntoTheNormalLoadRuntime() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        write(project, "config/tools/echo.debug.yaml", "schemaVersion: att-debug/v1.1\narguments: {value: sidecar}\n");
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", "--debug", "tool", "echo",
                "--users", "1", "--duration", "1s", "--set", "arg.value=typed-load"});
        Map<String, Object> promoted = new DebugEngine(project, config).loadBootstrapInputForLoad(options);
        LoadScenario scenario = new LoadScenarioLoader(project).fromDebugInput((Path) promoted.get("source"),
                options.debugTargetType(), options.debugTargetId(), map(promoted.get("inputs")), map(promoted.get("vars")),
                map(promoted.get("arguments")), null, options);
        assertEquals("typed-load", scenario.targetArguments().get("value"));
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        IterationResult result = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("tool-debug-load", "tool-debug-load-1", 1,
                        "STEADY", Instant.now(), "VU-1", scenario.inputs()));
        assertEquals(ResultStatus.PASS, result.status());
        assertTrue(String.valueOf(result.context().resolve("EXEC.ACTIONS.loadTool.output.result")).contains("typed-load"));
    }

    @Test void groupedToolSidecarOverridesSurviveQuickLoadPromotion() throws Exception {
        Path project = fixture();
        write(project, "config/tools/group.debug.yaml", "schemaVersion: att-debug/v1.1\narguments: {value: from-root}\n"
                + "tools:\n  echo:\n    arguments: {value: from-sidecar}\n");
        Map<String, att.config.ToolArgumentConfig> arguments = Collections.singletonMap("value",
                new att.config.ToolArgumentConfig("value", "Value", "Value", true, ""));
        ToolConfig grouped = new ToolConfig("group.echo", "echo", "group", "Echo", "Grouped Echo",
                java.util.Arrays.asList("/bin/echo", "${value}"), Collections.<String>emptyList(), "txt", arguments, null);
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("group.echo", grouped), null, null);
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", "--debug", "tool", "group.echo",
                "--users", "1", "--duration", "1s", "--set", "arg.value=typed-load"});

        Map<String, Object> promoted = new DebugEngine(project, config).loadBootstrapInputForLoad(options);

        assertEquals("typed-load", map(promoted.get("arguments")).get("value"));
        LoadScenario scenario = new LoadScenarioLoader(project).fromDebugInput((Path) promoted.get("source"),
                options.debugTargetType(), options.debugTargetId(), map(promoted.get("inputs")), map(promoted.get("vars")),
                map(promoted.get("arguments")), null, options);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);
        IterationResult result = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("group-tool-debug-load", "group-tool-debug-load-1", 1,
                        "STEADY", Instant.now(), "VU-1", scenario.inputs()));

        assertEquals(ResultStatus.PASS, result.status());
        String output = String.valueOf(result.context().resolve("EXEC.ACTIONS.loadTool.output.result"));
        assertTrue(output.contains("typed-load"), output);
        assertFalse(output.contains("from-sidecar"), output);
    }

    @Test void missingStaticBootstrapInputFailsBeforeLoadSchedulingWithWorkloadSourceLocation() throws Exception {
        Path project = fixture();
        Path source = write(project, "load/missing-bootstrap-input.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: first\n    target: {type: template, id: SHARED}\n    load: {users: 1, duration: 1s}\n"
                + "  - id: second\n    target: {type: template, id: SHARED}\n"
                + "    inputs: {customer: {id: C001}}\n"
                + "    vars:\n      account: '${EXEC.INPUT.customer.account}'\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario parsed = new LoadScenarioLoader(project).load(source);
        LoadScenario selected = parsed.forWorkload(parsed.workload("second"));
        FrameworkConfig config = config();
        LoadTarget target = new LoadTargetResolver(project, config).resolve(selected);

        att.validation.DiagnosticException diagnostic = assertThrows(att.validation.DiagnosticException.class,
                () -> new LoadTargetValidator(project, config).validate(selected, target));

        assertEquals("workloads[1].vars.account", diagnostic.field());
        assertEquals(source.toString(), diagnostic.file());
        assertNotNull(diagnostic.source());
        assertEquals(10, diagnostic.source().line());
        assertTrue(diagnostic.format().contains("^"), diagnostic.format());
        assertTrue(diagnostic.detail().contains("workloadId: second"), diagnostic.detail());
    }

    @Test void optionalMissingInputsSurviveLoadBootstrapIncludingExpressionBlocks() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        write(project, "templates/OPTIONAL/template.yaml", "schemaVersion: att-template/v3.3\nname: OPTIONAL\n"
                + "description: optional bootstrap references\nactions:\n"
                + "  check: {type: log, message: 'optional=${EXEC.VARS.customerId}|block=${EXEC.VARS.region}'}\n");
        Path source = write(project, "load/optional-bootstrap-input.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: optional\n    target: {type: template, id: OPTIONAL}\n    inputs: {}\n"
                + "    vars:\n      customerId: '${EXEC.INPUT.customerId?}'\n"
                + "      region: '#{${EXEC.INPUT.region?}}'\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(source);
        LoadTarget target = new LoadTargetResolver(project, config).resolve(scenario);
        new LoadTargetValidator(project, config).validate(scenario, target);

        IterationResult result = new IterationExecutor(project, config, target).execute(
                IterationRequest.closed("optional-bootstrap", "optional-bootstrap-1", 1,
                        "STEADY", Instant.now(), "VU-1", scenario.inputs()));

        assertEquals(ResultStatus.PASS, result.status());
        assertNull(result.context().require("EXEC.VARS.customerId"));
        assertNull(result.context().require("EXEC.VARS.region"));
    }

    @Test void optionalStructurallyInvalidInputPathFailsBeforeLoadSchedulerStarts() throws Exception {
        Path project = fixture();
        FrameworkConfig config = config();
        Path source = write(project, "load/optional-invalid-input-path.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: invalidPath\n    target: {type: template, id: SHARED}\n"
                + "    inputs: {value: ready, customer: C001}\n"
                + "    vars:\n      customerId: '${EXEC.INPUT.customer.id?}'\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario scenario = new LoadScenarioLoader(project).load(source);
        LoadTarget seedTarget = new LoadTargetResolver(project, config).resolve(scenario);
        Path output = temp.resolve("invalid-input-path-output");

        try (LoadRunResources resources = new LoadRunResources(project, config)) {
            IterationExecutor seed = new IterationExecutor(project, config, seedTarget, resources, output);
            att.validation.DiagnosticException diagnostic = assertThrows(att.validation.DiagnosticException.class,
                    () -> LoadRunCoordinator.runFrom(scenario, seed, "invalid-input-path-run", null, output));
            assertEquals("workloads[0].vars.customerId", diagnostic.field());
            assertTrue(diagnostic.detail().contains("structurally invalid input path"), diagnostic.format());
        }
        assertFalse(Files.exists(output.resolve("load/invalid-input-path-run")),
                "preflight failure must happen before a workload scheduler creates run output");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private List<att.core.ValidationResult> execute(Path project, FrameworkConfig config, StageTemplate template,
                                                      FlowRegistry flows, CaseRuntimeContext context, String stageName) throws Exception {
        Files.createDirectories(context.caseOutputDirectory());
        try (CaseExecutionLog log = new CaseExecutionLog(context.caseOutputDirectory().resolve("case.log"))) {
            return new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(project, config)), flows)
                    .execute(stageName, template, context, log);
        }
    }

    private void assertPortableResult(CaseRuntimeContext context, List<att.core.ValidationResult> results) {
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(ResultStatus.PASS, results.get(1).status());
        assertEquals(ResultStatus.PASS, results.get(2).status());
        assertEquals("shared-value", context.resolve("EXEC.INPUT.value"));
        assertEquals("value=shared-value", context.resolve("EXEC.ACTIONS.direct.output.result"));
        assertEquals("shared-value", String.valueOf(context.resolve("EXEC.ACTIONS.invoke.output.result")).trim());
        Map<?, ?> caseTree = context.caseTree();
        Map<?, ?> stages = (Map<?, ?>) caseTree.get("STAGES");
        String stageKey = String.valueOf(stages.keySet().iterator().next());
        assertEquals("flow=shared-value", CaseRuntimeContext.getPath(caseTree,
                "STAGES." + stageKey + ".TEMPLATE.ACTIONS.nested.flow.actions.flowLog.output.result"));
    }

    private CaseRuntimeContext testcaseContext(Path project, StageTemplate template, Map<String, Object> input) {
        StageCaseData stage = new StageCaseData("testcase", template.name(), Collections.<String, Object>emptyMap());
        TestCase testCase = new TestCase(1, "shared", "group", "TC1", Collections.<String>emptyList(), input,
                Collections.singletonMap("testcase", stage), "");
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, temp.resolve("testcase-output"), "testcase-run",
                temp.resolve("testcase-run"), temp.resolve("testcase-output/case.log"));
        context.setProject(project);
        context.beginStage(stage, template.name(), template.directory());
        return context;
    }

    private FrameworkConfig config() {
        Map<String, ToolArgumentConfig> arguments = Collections.singletonMap("value",
                new ToolArgumentConfig("value", "Value", "Value", true, ""));
        Map<String, ToolConfig> tools = Collections.singletonMap("echo",
                new ToolConfig("echo", "Echo", "Echo", "/bin/echo ${value}", "text", arguments));
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), tools, null, null);
    }

    private Path fixture() throws Exception {
        Path project = temp.resolve("project-" + System.nanoTime());
        att.TestSchemas.install(project);
        Files.createDirectories(project.resolve("templates/SHARED"));
        Files.createDirectories(project.resolve("templates/flows/shared/echo"));
        write(project, "templates/SHARED/template.yaml", "schemaVersion: att-template/v3.3\n"
                + "name: SHARED\ndescription: cross-mode fixture\nactions:\n"
                + "  direct:\n    type: log\n    message: \"value=${EXEC.INPUT.value}\"\n"
                + "  invoke:\n    type: tool\n    call: \"#{echo(value=${EXEC.INPUT.value})}\"\n"
                + "  nested:\n    type: flow\n    use: shared.echo.v1\n");
        write(project, "templates/flows/shared/echo/flow.yaml", "schemaVersion: att-flow/v3.3\n"
                + "id: shared.echo.v1\nname: Shared Echo\ndescription: cross-mode flow\nactions:\n"
                + "  flowLog:\n    type: log\n    message: \"flow=${EXEC.INPUT.value}\"\n");
        write(project, "templates/SHARED/debug.yaml", "schemaVersion: att-debug/v1.1\ninputs:\n  value: shared-value\n");
        return project;
    }

    private Path write(Path project, String relative, String content) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        return LoadTestSupport.writeScenario(file, content);
    }
}
