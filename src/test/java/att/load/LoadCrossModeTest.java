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
                new ToolConfig("echo", "Echo", "Echo", "/bin/echo ${value}", "txt", arguments));
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), tools, null, null);
    }

    private Path fixture() throws Exception {
        Path project = temp.resolve("project-" + System.nanoTime());
        Files.createDirectories(project.resolve("templates/SHARED"));
        Files.createDirectories(project.resolve("templates/flows/shared/echo"));
        Files.createDirectories(project.resolve("schemas"));
        copySchema(project, "att-template-v3.0.schema.json");
        copySchema(project, "att-flow-v3.0.schema.json");
        copySchema(project, "att-debug-v1.0.schema.json");
        copySchema(project, "att-load-v1.0.schema.json");
        write(project, "templates/SHARED/template.yaml", "schemaVersion: att-template/v3.0\n"
                + "name: SHARED\ndescription: cross-mode fixture\nactions:\n"
                + "  direct:\n    type: log\n    message: \"value=${EXEC.INPUT.value}\"\n"
                + "  invoke:\n    type: tool\n    call: \"#{echo(value=${EXEC.INPUT.value})}\"\n"
                + "  nested:\n    type: flow\n    use: shared.echo.v1\n");
        write(project, "templates/flows/shared/echo/flow.yaml", "schemaVersion: att-flow/v3.0\n"
                + "id: shared.echo.v1\nname: Shared Echo\ndescription: cross-mode flow\nactions:\n"
                + "  flowLog:\n    type: log\n    message: \"flow=${EXEC.INPUT.value}\"\n");
        write(project, "templates/SHARED/debug.yaml", "schemaVersion: att-debug/v1.0\ninputs:\n  value: shared-value\n");
        return project;
    }

    private void copySchema(Path project, String name) throws Exception {
        Files.copy(Paths.get("schemas").resolve(name), project.resolve("schemas").resolve(name));
    }

    private Path write(Path project, String relative, String content) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
