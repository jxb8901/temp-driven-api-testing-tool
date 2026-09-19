package att.debug;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.config.ToolArgumentConfig;
import att.core.ExecutionOptions;
import att.core.ResultStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class DebugEngineTest {
    @TempDir Path temp;

    @Test void autoDiscoversTemplateFlowAndToolSidecarsAndPreservesCaseIdentity() throws Exception {
        Path project = fixture();
        java.util.Map<String, ToolArgumentConfig> echoArguments = Collections.singletonMap("value",
                new ToolArgumentConfig("value", "Value", "Value", true, ""));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("echo", new ToolConfig("echo", "Echo", "Echo", "/bin/echo ${value}", "txt", echoArguments)), null, null);

        DebugEngine.Result template = run(project, config, "template", "SIMPLE");
        assertEquals(ResultStatus.PASS, template.status());
        assertEquals(0, template.exitCode());
        String templateCase = new String(Files.readAllBytes(template.outputDirectory().resolve("artifacts/case.yaml")), StandardCharsets.UTF_8);
        assertTrue(templateCase.contains("caseId: DEBUG.template.SIMPLE"));
        assertTrue(templateCase.contains("outputDirectory: " + template.outputDirectory().resolve("artifacts")));
        assertFalse(templateCase.contains("caseId: EVIL"));
        assertFalse(templateCase.contains("outputDirectory: EVIL"));

        DebugEngine.Result flow = run(project, config, "flow", "debug.echo.v1");
        assertEquals(ResultStatus.PASS, flow.status());
        assertTrue(new String(Files.readAllBytes(flow.logPath()), StandardCharsets.UTF_8).contains("debug=hello"));

        DebugEngine.Result tool = run(project, config, "tool", "echo");
        assertEquals(ResultStatus.PASS, tool.status());
        assertTrue(new String(Files.readAllBytes(tool.logPath()), StandardCharsets.UTF_8).contains("hello-tool"));
    }

    @Test void explicitInputOverridesTemplateSidecar() throws Exception {
        Path project = fixture();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);
        Path explicit = temp.resolve("override.yaml");
        Files.write(explicit, ("schemaVersion: att-debug/v1.0\ncase:\n  value: explicit\n").getBytes(StandardCharsets.UTF_8));
        DebugEngine.Result result = run(project, config, "template", "SIMPLE", "--input", explicit.toString());
        assertEquals(ResultStatus.PASS, result.status());
        assertTrue(new String(Files.readAllBytes(result.logPath()), StandardCharsets.UTF_8).contains("explicit"));
        assertTrue(new String(Files.readAllBytes(result.resultPath()), StandardCharsets.UTF_8).contains("input: " + explicit));
    }

    @Test void missingSidecarIsDiagnosticAndDoesNotTouchNormalRunOutput() throws Exception {
        Path project = fixtureWithoutSidecars();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);
        DebugEngine.Result result = run(project, config, "template", "SIMPLE");
        assertEquals(ResultStatus.INVALID, result.status());
        assertEquals(2, result.exitCode());
        assertNotNull(result.diagnostic());
        assertEquals("ATT-DEBUG-001", result.diagnostic().code());
        assertTrue(Files.exists(result.logPath()));
        assertFalse(Files.exists(project.resolve("output/latest-run.yaml")));
    }

    private DebugEngine.Result run(Path project, FrameworkConfig config, String type, String id, String... extra) throws Exception {
        String[] args = new String[3 + extra.length];
        args[0] = "debug"; args[1] = type; args[2] = id;
        System.arraycopy(extra, 0, args, 3, extra.length);
        return new DebugEngine(project, config).run(ExecutionOptions.parse(args));
    }

    private Path fixture() throws Exception {
        Path project = fixtureWithoutSidecars();
        Files.write(project.resolve("templates/SIMPLE/debug.yaml"), (
                "schemaVersion: att-debug/v1.0\ncase:\n  value: sidecar\n  caseId: EVIL\n  outputDirectory: EVIL\n  VARS: EVIL\n  STAGES: EVIL\n" ).getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/debug/echo/debug.yaml"), (
                "schemaVersion: att-debug/v1.0\ninputs:\n  message: hello\n" ).getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(project.resolve("config/tools"));
        Files.write(project.resolve("config/tools/echo.debug.yaml"), (
                "schemaVersion: att-debug/v1.0\narguments:\n  value: hello-tool\n" ).getBytes(StandardCharsets.UTF_8));
        return project;
    }

    private Path fixtureWithoutSidecars() throws Exception {
        Path project = temp.resolve("project-" + System.nanoTime());
        Files.createDirectories(project.resolve("templates/SIMPLE"));
        Files.createDirectories(project.resolve("templates/BROKEN"));
        Files.createDirectories(project.resolve("templates/flows/debug/echo"));
        Files.createDirectories(project.resolve("schemas"));
        copySchema("att-template-v2.3.schema.json", project);
        copySchema("att-flow-v3.0.schema.json", project);
        copySchema("att-debug-v1.0.schema.json", project);
        Files.write(project.resolve("templates/SIMPLE/template.yaml"), (
                "schemaVersion: att-template/v2.3\nname: SIMPLE\ndescription: Simple debug template\nactions:\n  log:\n    type: log\n    message: 'value=${EXEC.INPUT.value}'\n" ).getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/BROKEN/template.yaml"), "not: [valid\n".getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/debug/echo/flow.yaml"), (
                "schemaVersion: att-flow/v3.0\nid: debug.echo.v1\nname: Debug Echo\ndescription: Debug Echo\nactions:\n  echo:\n    type: log\n    message: 'debug=${CASE.inputs.message}'\n" ).getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(project.resolve("output"));
        return project;
    }

    private void copySchema(String name, Path project) throws Exception {
        Files.copy(Paths.get("schemas").resolve(name), project.resolve("schemas").resolve(name));
    }
}
