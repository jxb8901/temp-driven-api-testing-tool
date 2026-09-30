package att.debug;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.config.ToolArgumentConfig;
import att.core.ExecutionOptions;
import att.core.ResultStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test void streamsDebugActionStartBeforeSlowToolReturns() throws Exception {
        Path project = fixtureWithoutSidecars();
        Files.createDirectories(project.resolve("templates/SLOW"));
        Files.write(project.resolve("templates/SLOW/template.yaml"), (
                "schemaVersion: att-template/v3.3\nname: SLOW\ndescription: slow debug\nactions:\n"
                        + "  wait:\n    type: tool\n    call: '#{slow()}'\n").getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/SLOW/debug.yaml"),
                "schemaVersion: att-debug/v1.0\ncase: {caseName: slow}\n".getBytes(StandardCharsets.UTF_8));
        ToolConfig slow = new ToolConfig("slow", "Slow", "Slow test tool", "/bin/sleep 1", "txt",
                Collections.<String, ToolArgumentConfig>emptyMap());
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("slow", slow), null, null);
        DebugEngine engine = new DebugEngine(project, config);
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"debug", "template", "SLOW"});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        AtomicReference<DebugEngine.Result> result = new AtomicReference<DebugEngine.Result>();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread execution = new Thread(() -> {
            try { result.set(engine.run(options)); }
            catch (Throwable failure) { error.set(failure); }
        });
        boolean sawStart = false;
        boolean stillRunningAtStart = false;
        try {
            System.setOut(new PrintStream(bytes, true, "UTF-8"));
            execution.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4L);
            while (System.nanoTime() < deadline && execution.isAlive()) {
                if (bytes.toString("UTF-8").contains("type: tool, status: START")) {
                    sawStart = true;
                    stillRunningAtStart = execution.isAlive();
                    break;
                }
                Thread.sleep(10L);
            }
            execution.join(4000L);
        } finally {
            System.setOut(previous);
        }
        assertNull(error.get());
        assertTrue(sawStart, bytes.toString("UTF-8"));
        assertTrue(stillRunningAtStart, "the Action start must be visible while the Tool is still running");
        assertNotNull(result.get());
        assertEquals(ResultStatus.PASS, result.get().status());
        String live = bytes.toString("UTF-8");
        assertTrue(live.contains("[DEBUG] INPUT target=template:SLOW"));
        assertTrue(live.contains("resource: TOOL"));
        assertTrue(live.contains("status: PASS"));
    }

    @Test void mirrorsInternalStackDiagnosticsLiveAndKeepsThemInCaseLog() throws Exception {
        Path logPath = temp.resolve("internal-case.log");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, "UTF-8");
        att.core.CaseExecutionLog log = new att.core.CaseExecutionLog(logPath, false,
                new att.core.CaseLogConsoleMirror("DEBUG.template.INTERNAL", output));
        assertTrue(att.core.InternalExceptionLogger.logIfInternal(log, "template.assign",
                new NullPointerException("synthetic internal failure"), Collections.<String>emptyList()));
        log.close();
        String live = bytes.toString("UTF-8");
        String persisted = new String(Files.readAllBytes(logPath), StandardCharsets.UTF_8);
        assertTrue(live.contains("[ATT INTERNAL ERROR]"));
        assertTrue(persisted.contains("[ATT INTERNAL ERROR]"));
        assertTrue(live.contains("NullPointerException"));
        assertTrue(persisted.contains("NullPointerException"));
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
        att.TestSchemas.install(project);
        Files.createDirectories(project.resolve("templates/SIMPLE"));
        Files.createDirectories(project.resolve("templates/BROKEN"));
        Files.createDirectories(project.resolve("templates/flows/debug/echo"));
        Files.write(project.resolve("templates/SIMPLE/template.yaml"), (
                "schemaVersion: att-template/v3.3\nname: SIMPLE\ndescription: Simple debug template\nactions:\n  log:\n    type: log\n    message: 'value=${EXEC.INPUT.value}'\n" ).getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/BROKEN/template.yaml"), "not: [valid\n".getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/debug/echo/flow.yaml"), (
                "schemaVersion: att-flow/v3.3\nid: debug.echo.v1\nname: Debug Echo\ndescription: Debug Echo\nactions:\n  echo:\n    type: log\n    message: 'debug=${CASE.inputs.message}'\n" ).getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(project.resolve("output"));
        return project;
    }

}
