package att.debug;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.config.ToolArgumentConfig;
import att.config.RunConfig;
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
        assertTrue(templateCase.contains("outputDirectory: " + att.core.PathPresentation.displayPath(
                template.outputDirectory().resolve("artifacts"), project)));
        assertFalse(templateCase.contains("caseId: EVIL"));
        assertFalse(templateCase.contains("outputDirectory: EVIL"));

        DebugEngine.Result flow = run(project, config, "flow", "debug.echo.v1");
        assertEquals(ResultStatus.PASS, flow.status());
        assertTrue(new String(Files.readAllBytes(flow.logPath()), StandardCharsets.UTF_8).contains("debug=hello"));

        DebugEngine.Result tool = run(project, config, "tool", "echo");
        assertEquals(ResultStatus.PASS, tool.status());
        assertTrue(new String(Files.readAllBytes(tool.logPath()), StandardCharsets.UTF_8).contains("hello-tool"));
    }

    @Test void configuredDebugIdentityIsUsedAndCollisionsFail() throws Exception {
        Path project = fixture();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null,
                new RunConfig("timestamp", "yyyyMMdd-HHmmss", "", "debug-${META.TARGET.type}-${META.TARGET.id}"));
        DebugEngine.Result result = run(project, config, "template", "SIMPLE");
        assertEquals("debug-template-SIMPLE", result.outputDirectory().getFileName().toString());
        IllegalArgumentException collision = assertThrows(IllegalArgumentException.class,
                () -> run(project, config, "template", "SIMPLE"));
        assertTrue(collision.getMessage().contains("Debug ID already exists"), collision.getMessage());
    }

    @Test void explicitInputOverridesTemplateSidecar() throws Exception {
        Path project = fixture();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);
        Path explicit = temp.resolve("override.yaml");
        Files.write(explicit, ("schemaVersion: att-debug/v1.2\ncase:\n  value: explicit\n").getBytes(StandardCharsets.UTF_8));
        DebugEngine.Result result = run(project, config, "template", "SIMPLE", "--input", explicit.toString());
        assertEquals(ResultStatus.PASS, result.status());
        assertTrue(new String(Files.readAllBytes(result.logPath()), StandardCharsets.UTF_8).contains("explicit"));
        assertTrue(new String(Files.readAllBytes(result.resultPath()), StandardCharsets.UTF_8)
                .contains("input: $EXTERNAL/override.yaml"));
    }

    @Test void namespacedSetOverridesTypedDebugInputAndToolArguments() throws Exception {
        Path project = fixture();
        java.util.Map<String, ToolArgumentConfig> echoArguments = Collections.singletonMap("value",
                new ToolArgumentConfig("value", "Value", "Value", true, ""));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("echo", new ToolConfig("echo", "Echo", "Echo", "/bin/echo ${value}", "txt", echoArguments)), null, null);

        Files.createDirectories(project.resolve("templates/INPUT"));
        Files.write(project.resolve("templates/INPUT/template.yaml"), (
                "schemaVersion: att-template/v3.4\nname: INPUT\ndescription: input override\nactions:\n"
                        + "  show: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/INPUT/debug.yaml"),
                "schemaVersion: att-debug/v1.2\ninputs: {amount: 7}\n".getBytes(StandardCharsets.UTF_8));
        DebugEngine.Result template = run(project, config, "template", "INPUT", "--set", "input.amount=42");
        assertEquals(ResultStatus.PASS, template.status(), template.diagnostic() == null ? "" : template.diagnostic().format());
        assertTrue(new String(Files.readAllBytes(template.logPath()), StandardCharsets.UTF_8).contains("amount=42"));

        DebugEngine.Result tool = run(project, config, "tool", "echo", "--set", "arg.value=typed");
        assertEquals(ResultStatus.PASS, tool.status(), tool.diagnostic() == null ? "" : tool.diagnostic().format());
        assertTrue(new String(Files.readAllBytes(tool.logPath()), StandardCharsets.UTF_8).contains("typed"));
    }

    @Test void groupedToolSidecarKeepsExistingPrecedenceAndCliArgOverridesWinLast() throws Exception {
        Path project = fixtureWithoutSidecars();
        java.util.Map<String, ToolArgumentConfig> arguments = Collections.singletonMap("value",
                new ToolArgumentConfig("value", "Value", "Value", true, ""));
        ToolConfig grouped = new ToolConfig("group.echo", "echo", "group", "Echo", "Grouped Echo",
                java.util.Arrays.asList("/bin/echo", "${value}"), Collections.<String>emptyList(), "txt", arguments, null);
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("group.echo", grouped), null, null);
        Files.createDirectories(project.resolve("config/tools"));
        Files.write(project.resolve("config/tools/group.debug.yaml"), (
                "schemaVersion: att-debug/v1.2\narguments: {value: from-root}\n"
                        + "tools:\n  echo:\n    arguments: {value: from-group}\n")
                .getBytes(StandardCharsets.UTF_8));

        DebugEngine.Result withoutOverride = run(project, config, "tool", "group.echo");
        DebugEngine.Result withOverride = run(project, config, "tool", "group.echo",
                "--output-dir", temp.resolve("group-tool-overridden").toString(), "--set", "arg.value=debug-overridden");

        assertEquals(ResultStatus.PASS, withoutOverride.status(), withoutOverride.diagnostic() == null ? "" : withoutOverride.diagnostic().format());
        String originalLog = new String(Files.readAllBytes(withoutOverride.logPath()), StandardCharsets.UTF_8);
        assertTrue(originalLog.contains("from-group"), originalLog);
        assertFalse(originalLog.contains("from-root"), originalLog);
        assertEquals(ResultStatus.PASS, withOverride.status(), withOverride.diagnostic() == null ? "" : withOverride.diagnostic().format());
        String overrideLog = new String(Files.readAllBytes(withOverride.logPath()), StandardCharsets.UTF_8);
        assertTrue(overrideLog.contains("debug-overridden"), overrideLog);
        assertFalse(overrideLog.contains("from-group"), overrideLog);
    }

    @Test void disabledToolOverrideDoesNotReplaceDebugArguments() throws Exception {
        Path project = fixtureWithoutSidecars();
        java.util.Map<String, ToolArgumentConfig> arguments = Collections.singletonMap("value",
                new ToolArgumentConfig("value", "Value", "Value", true, ""));
        ToolConfig tool = new ToolConfig("group.x-disabled", "x-disabled", "group", "Disabled", "Disabled tool fixture",
                java.util.Arrays.asList("/bin/echo", "${value}"), Collections.<String>emptyList(), "txt", arguments, null);
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap(tool.key(), tool), null, null);
        Files.createDirectories(project.resolve("config/tools"));
        Files.write(project.resolve("config/tools/group.debug.yaml"), ("schemaVersion: att-debug/v1.2\n"
                + "arguments: {value: from-root}\ntools:\n  x-disabled:\n    arguments: {value: from-disabled-override}\n"
                + "  x-malformed: [not, a, tool override]\n").getBytes(StandardCharsets.UTF_8));

        DebugEngine.Result result = run(project, config, "tool", "group.x-disabled");
        assertEquals(ResultStatus.PASS, result.status(), result.diagnostic() == null ? "" : result.diagnostic().format());
        String log = new String(Files.readAllBytes(result.logPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("from-root"), log);
        assertFalse(log.contains("from-disabled-override"), log);
    }

    @Test void standaloneAndNestedFlowBootstrapPreservesExplicitNull() throws Exception {
        Path project = fixtureWithoutSidecars();
        Files.createDirectories(project.resolve("templates/WRAPPER"));
        Files.write(project.resolve("templates/WRAPPER/template.yaml"), (
                "schemaVersion: att-template/v3.4\nname: WRAPPER\ndescription: nested flow bootstrap\nactions:\n"
                        + "  nested: {type: flow, use: debug.echo.v1}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/debug/echo/flow.yaml"), (
                "schemaVersion: att-flow/v3.4\nid: debug.echo.v1\nname: Debug Echo\ndescription: vars flow\nactions:\n"
                        + "  echo: {type: log, message: 'seed=${EXEC.VARS.seed}|amount=${EXEC.INPUT.amount}'}\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/WRAPPER/debug.yaml"),
                "schemaVersion: att-debug/v1.2\ninputs: {amount: 11}\nvars: {seed: nested}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/debug/echo/debug.yaml"),
                "schemaVersion: att-debug/v1.2\ninputs: {amount: 12}\nvars: {seed: null}\n".getBytes(StandardCharsets.UTF_8));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);

        DebugEngine.Result nested = run(project, config, "template", "WRAPPER");
        assertEquals(ResultStatus.PASS, nested.status(), nested.diagnostic() == null ? "" : nested.diagnostic().format());
        assertTrue(new String(Files.readAllBytes(nested.logPath()), StandardCharsets.UTF_8).contains("seed=nested|amount=11"));
        DebugEngine.Result standalone = run(project, config, "flow", "debug.echo.v1");
        assertEquals(ResultStatus.PASS, standalone.status(), standalone.diagnostic() == null ? "" : standalone.diagnostic().format());
        String log = new String(Files.readAllBytes(standalone.logPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("seed=|amount=12"), log);
        assertTrue(new String(Files.readAllBytes(standalone.resultPath()), StandardCharsets.UTF_8).contains("seed: null"));
    }

    @Test void rejectsInvalidVarsShapeAndToolBootstrapVarsBeforeInvocation() throws Exception {
        Path project = fixture();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>singletonMap("echo",
                new ToolConfig("echo", "Echo", "Echo", "/bin/echo ${value}", "txt", Collections.<String, ToolArgumentConfig>emptyMap())), null, null);
        Path invalidShape = temp.resolve("invalid-vars-shape.yaml");
        Files.write(invalidShape, "schemaVersion: att-debug/v1.2\nvars: [not, a, map]\n".getBytes(StandardCharsets.UTF_8));
        DebugEngine.Result invalid = run(project, config, "template", "SIMPLE", "--input", invalidShape.toString());
        assertEquals(ResultStatus.INVALID, invalid.status());

        Path toolVars = temp.resolve("tool-vars.yaml");
        Files.write(toolVars, "schemaVersion: att-debug/v1.2\nvars: {notArguments: value}\n".getBytes(StandardCharsets.UTF_8));
        DebugEngine.Result rejected = run(project, config, "tool", "echo", "--input", toolVars.toString());
        assertEquals(ResultStatus.INVALID, rejected.status());
        assertNotNull(rejected.diagnostic());
        assertTrue(rejected.diagnostic().detail().contains("supported only for standalone Template and Flow targets"), rejected.diagnostic().format());
    }

    @Test void debugVarsSeedTypedCanonicalContextAndCanBeReplacedByAssign() throws Exception {
        Path project = fixtureWithoutSidecars();
        Files.createDirectories(project.resolve("templates/VARS"));
        Files.write(project.resolve("templates/VARS/template.yaml"), (
                "schemaVersion: att-template/v3.4\nname: VARS\ndescription: Debug vars\nactions:\n"
                        + "  before:\n    type: log\n    message: 'before=${EXEC.VARS.refNo}|${EXEC.VARS.retryCount}|${EXEC.VARS.order.id}|${EXEC.INPUT.amount}'\n"
                        + "  replace:\n    type: assign\n    name: refNo\n    expression: REF002\n"
                        + "  after:\n    type: log\n    message: 'after=${EXEC.VARS.refNo}'\n").getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/VARS/debug.yaml"), (
                "schemaVersion: att-debug/v1.2\ninputs:\n  amount: 100\nvars:\n  refNo: REF001\n  enabled: true\n  retryCount: 3\n  tags: [SIT, PAYMENT]\n  order:\n    id: ORD001\n    amount: 100\n").getBytes(StandardCharsets.UTF_8));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);

        DebugEngine.Result result = run(project, config, "template", "VARS");

        assertEquals(ResultStatus.PASS, result.status());
        String log = new String(Files.readAllBytes(result.logPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("before=REF001|3|ORD001|100"), log);
        assertTrue(log.contains("after=REF002"), log);
        String caseYaml = new String(Files.readAllBytes(result.outputDirectory().resolve("artifacts/case.yaml")), StandardCharsets.UTF_8);
        assertTrue(caseYaml.contains("refNo: REF002"), caseYaml);
        assertTrue(caseYaml.contains("enabled: true"), caseYaml);
        assertTrue(caseYaml.contains("tags: [SIT, PAYMENT]"), caseYaml);
    }

    @Test void debugBootstrapUsesTypedExpressionsAndCliOverridesBeforeEvaluation() throws Exception {
        Path project = fixtureWithoutSidecars();
        Files.createDirectories(project.resolve("templates/BOOTSTRAP"));
        Files.write(project.resolve("templates/BOOTSTRAP/template.yaml"), (
                "schemaVersion: att-template/v3.4\nname: BOOTSTRAP\ndescription: typed bootstrap\nactions:\n"
                        + "  before:\n    type: log\n    message: 'before=${EXEC.VARS.amount}|${EXEC.VARS.twice}|${EXEC.VARS.label}|${EXEC.VARS.cli}|${EXEC.VARS.templateName}'\n"
                        + "  nested:\n    type: flow\n    use: debug.echo.v1\n"
                        + "  replace:\n    type: assign\n    name: amount\n    expression: ${EXEC.INPUT.amount}\n"
                        + "  after:\n    type: log\n    message: 'after=${EXEC.VARS.amount}'\n").getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/debug/echo/flow.yaml"), (
                "schemaVersion: att-flow/v3.4\nid: debug.echo.v1\nname: Debug Echo\ndescription: bootstrap flow\nactions:\n"
                        + "  echo:\n    type: log\n    message: 'nested=${EXEC.VARS.cli}'\n").getBytes(StandardCharsets.UTF_8));
        Path input = project.resolve("templates/BOOTSTRAP/debug.yaml");
        Files.write(input, ("schemaVersion: att-debug/v1.2\ninputs: {amount: 21}\nvars:\n"
                + "  amount: 5\n  twice: '#{${EXEC.INPUT.amount} * 2}'\n"
                + "  label: 'REQ-${EXEC.ID}'\n  cli: from-sidecar\n  templateName: '${META.TEMPLATE.id}'\n").getBytes(StandardCharsets.UTF_8));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);

        DebugEngine.Result result = run(project, config, "template", "BOOTSTRAP", "--set", "vars.cli=${EXEC.INPUT.amount}");

        assertEquals(ResultStatus.PASS, result.status(), result.diagnostic() == null ? "" : result.diagnostic().format());
        String log = new String(Files.readAllBytes(result.logPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("before=5|42|REQ-"), log);
        assertTrue(log.contains("|21|BOOTSTRAP"), log);
        assertTrue(log.contains("nested=21"), log);
        assertTrue(log.contains("after=21"), log);
    }

    @Test void testdataInputsAreResolvedBeforeDebugBootstrapVariables() throws Exception {
        Path project = fixtureWithoutSidecars();
        Files.createDirectories(project.resolve("templates/TESTDATA"));
        Files.write(project.resolve("templates/TESTDATA/template.yaml"), (
                "schemaVersion: att-template/v3.6\nname: TESTDATA\ndescription: mapped bootstrap input\nactions:\n"
                        + "  show: {type: log, message: 'copied=${EXEC.VARS.copied}|input=${EXEC.INPUT.accountId}'}\n")
                .getBytes(StandardCharsets.UTF_8));
        Path descriptor = project.resolve("config/testdata/accounts.yaml");
        Files.createDirectories(descriptor.getParent());
        Files.write(descriptor, ("schemaVersion: att-testdata/v1.0\nid: accounts\n"
                + "records: [{id: 42}]\n").getBytes(StandardCharsets.UTF_8));
        Path input = project.resolve("templates/TESTDATA/debug.yaml");
        Files.write(input, ("schemaVersion: att-debug/v1.2\ninputs: {accountId: '@{accounts.id}'}\n"
                + "vars: {copied: '${EXEC.INPUT.accountId}'}\n").getBytes(StandardCharsets.UTF_8));
        FrameworkConfig base = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"),
                "SIT", 10000, Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);
        FrameworkConfig config = withTestdata(base, descriptor);

        DebugEngine.Result result = run(project, config, "template", "TESTDATA", "--input", input.toString());

        assertEquals(ResultStatus.PASS, result.status(), result.diagnostic() == null ? "" : result.diagnostic().format());
        String log = new String(Files.readAllBytes(result.logPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("copied=42|input=42"), log);
        String caseYaml = new String(Files.readAllBytes(result.outputDirectory().resolve("artifacts/case.yaml")),
                StandardCharsets.UTF_8);
        assertTrue(caseYaml.contains("accountId: 42"), caseYaml);
        assertTrue(caseYaml.contains("copied: 42"), caseYaml);
    }

    @Test void debugSidecarTestdataIsAValidatedLocalOverlay() throws Exception {
        Path project = fixtureWithoutSidecars();
        Files.createDirectories(project.resolve("templates/TESTDATA"));
        Files.write(project.resolve("templates/TESTDATA/template.yaml"), (
                "schemaVersion: att-template/v3.6\nname: TESTDATA\ndescription: local descriptor import\nactions:\n"
                        + "  show: {type: log, message: 'account=${EXEC.INPUT.accountId}'}\n")
                .getBytes(StandardCharsets.UTF_8));
        Path descriptor = project.resolve("debug-data/accounts.yaml");
        Files.createDirectories(descriptor.getParent());
        Files.write(descriptor, "schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: 42}]\n"
                .getBytes(StandardCharsets.UTF_8));
        Path environmentDescriptor = project.resolve("config/testdata/environment-accounts.yaml");
        Files.createDirectories(environmentDescriptor.getParent());
        Files.write(environmentDescriptor, "schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: 17}]\n"
                .getBytes(StandardCharsets.UTF_8));
        Path input = project.resolve("templates/TESTDATA/debug.yaml");
        Files.write(input, ("schemaVersion: att-debug/v1.2\ntestdata: [debug-data/accounts.yaml]\n"
                + "inputs: {accountId: '@{accounts.id}'}\n").getBytes(StandardCharsets.UTF_8));
        FrameworkConfig base = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"),
                "SIT", 10000, Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);
        FrameworkConfig config = withTestdata(base, environmentDescriptor);

        DebugEngine.Result result = run(project, config, "template", "TESTDATA");

        assertEquals(ResultStatus.PASS, result.status(), result.diagnostic() == null ? "" : result.diagnostic().format());
        assertTrue(new String(Files.readAllBytes(result.logPath()), StandardCharsets.UTF_8).contains("account=42"));
        String caseYaml = new String(Files.readAllBytes(result.outputDirectory().resolve("artifacts/case.yaml")), StandardCharsets.UTF_8);
        assertTrue(caseYaml.contains("debug-local"), caseYaml);
        assertEquals(1, config.testdataDescriptors().size(), "Debug-local imports must not mutate shared configuration");
        Object sharedValue = new att.testdata.TestdataInputResolver(
                new att.testdata.TestdataRegistry(project, config.testdataDescriptors(), Collections.<Path>emptyList()))
                .resolve(Collections.singletonMap("accountId", "@{accounts.id}"), null, null, null).get("accountId");
        assertEquals("17", String.valueOf(sharedValue), "Run's environment layer remains the shared default");
        ExecutionOptions loadOptions = att.core.ExecutionOptionsTestSupport.parse(new String[]{"load", "--debug", "template", "TESTDATA",
                "--input", input.toString()});
        assertThrows(Exception.class, () -> new DebugEngine(project, config).loadBootstrapInputForLoad(loadOptions));

        Path duplicate = project.resolve("debug-data/accounts-copy.yaml");
        Files.write(duplicate, Files.readAllBytes(descriptor));
        Files.write(input, ("schemaVersion: att-debug/v1.2\ntestdata: [debug-data/accounts.yaml, debug-data/accounts-copy.yaml]\n"
                + "inputs: {accountId: '@{accounts.id}'}\n").getBytes(StandardCharsets.UTF_8));
        DebugEngine.Result duplicateResult = run(project, config, "template", "TESTDATA");
        assertEquals(ResultStatus.INVALID, duplicateResult.status());
    }

    @Test void historicalDebugSchemaReportsMigrationToV11() throws Exception {
        Path project = fixtureWithoutSidecars();
        Path input = temp.resolve("historical-debug.yaml");
        Files.write(input, "schemaVersion: att-debug/v1.0\ninputs: {value: old}\n".getBytes(StandardCharsets.UTF_8));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);

        DebugEngine.Result result = run(project, config, "template", "SIMPLE", "--input", input.toString());

        assertEquals(ResultStatus.INVALID, result.status());
        assertNotNull(result.diagnostic());
        assertEquals("ATT-SCHEMA-001", result.diagnostic().code());
        assertTrue(result.diagnostic().suggestion().contains("att-debug/v1.2"), result.diagnostic().format());
    }

    @Test void previousDebugSchemaReportsMigrationBeforeFurtherValidation() throws Exception {
        Path project = fixtureWithoutSidecars();
        Path input = temp.resolve("previous-debug.yaml");
        Files.write(input, "schemaVersion: att-debug/v1.1\ninputs: {value: old}\n".getBytes(StandardCharsets.UTF_8));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);

        DebugEngine.Result result = run(project, config, "template", "SIMPLE", "--input", input.toString());

        assertEquals(ResultStatus.INVALID, result.status());
        assertNotNull(result.diagnostic());
        assertEquals("ATT-SCHEMA-001", result.diagnostic().code());
        assertTrue(result.diagnostic().detail().contains("att-debug/v1.1"), result.diagnostic().format());
        assertTrue(result.diagnostic().suggestion().contains("att-debug/v1.2"), result.diagnostic().format());
    }

    @Test void invalidDebugVarNameFailsBeforeTargetExecution() throws Exception {
        Path project = fixtureWithoutSidecars();
        Path input = temp.resolve("invalid-debug-vars.yaml");
        Files.write(input, "schemaVersion: att-debug/v1.2\nvars:\n  bad-name: value\n".getBytes(StandardCharsets.UTF_8));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);

        DebugEngine.Result result = run(project, config, "template", "SIMPLE", "--input", input.toString());

        assertEquals(ResultStatus.INVALID, result.status());
        assertNotNull(result.diagnostic());
        assertEquals("ATT-DEBUG-001", result.diagnostic().code());
        assertTrue(result.diagnostic().detail().contains("bad-name"), result.diagnostic().format());
    }

    @Test void bootstrapDiagnosticsLocateCycleAndUnavailableRootAtVarsSource() throws Exception {
        Path project = fixtureWithoutSidecars();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);
        Path cycle = project.resolve("templates/SIMPLE/debug.yaml");
        Files.write(cycle, ("schemaVersion: att-debug/v1.2\nvars:\n  first: '${EXEC.VARS.second}'\n"
                + "  second: '${EXEC.VARS.first}'\n").getBytes(StandardCharsets.UTF_8));

        DebugEngine.Result cycleResult = run(project, config, "template", "SIMPLE");

        assertEquals(ResultStatus.INVALID, cycleResult.status());
        assertNotNull(cycleResult.diagnostic());
        assertEquals("vars.first", cycleResult.diagnostic().field());
        assertNotNull(cycleResult.diagnostic().source());
        assertTrue(cycleResult.diagnostic().source().line() > 0);
        assertTrue(cycleResult.diagnostic().format().contains("^"), cycleResult.diagnostic().format());

        Path unavailable = project.resolve("templates/SIMPLE/debug.yaml");
        Files.write(unavailable, ("schemaVersion: att-debug/v1.2\nvars:\n  result: '${EXEC.ACTIONS.prior.output}'\n")
                .getBytes(StandardCharsets.UTF_8));
        DebugEngine.Result unavailableResult = run(project, config, "template", "SIMPLE");
        assertEquals(ResultStatus.INVALID, unavailableResult.status());
        assertNotNull(unavailableResult.diagnostic());
        assertEquals("vars.result", unavailableResult.diagnostic().field());
        assertNotNull(unavailableResult.diagnostic().source());
        assertTrue(unavailableResult.diagnostic().format().contains("^"), unavailableResult.diagnostic().format());
    }

    @Test void optionalMissingBootstrapInputsRemainValidWhileStrictReferencesFailEarly() throws Exception {
        Path project = fixtureWithoutSidecars();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);
        Path sidecar = project.resolve("templates/SIMPLE/debug.yaml");
        Files.write(sidecar, ("schemaVersion: att-debug/v1.2\ninputs: {value: present}\nvars:\n"
                + "  optional: '${EXEC.INPUT.customerId?}'\n"
                + "  optionalBlock: '#{${EXEC.INPUT.region?}}'\n").getBytes(StandardCharsets.UTF_8));

        DebugEngine.Result optional = run(project, config, "template", "SIMPLE");

        assertEquals(ResultStatus.PASS, optional.status(), optional.diagnostic() == null ? "" : optional.diagnostic().format());
        String caseYaml = new String(Files.readAllBytes(optional.outputDirectory().resolve("artifacts/case.yaml")), StandardCharsets.UTF_8);
        assertTrue(caseYaml.contains("optional: null"), caseYaml);
        assertTrue(caseYaml.contains("optionalBlock: null"), caseYaml);

        Files.write(sidecar, ("schemaVersion: att-debug/v1.2\ninputs: {value: present}\nvars:\n"
                + "  strict: '${EXEC.INPUT.customerId}'\n").getBytes(StandardCharsets.UTF_8));
        DebugEngine.Result strict = run(project, config, "template", "SIMPLE",
                "--output-dir", temp.resolve("strict-bootstrap-input").toString());
        assertEquals(ResultStatus.INVALID, strict.status());
        assertNotNull(strict.diagnostic());
        assertEquals("vars.strict", strict.diagnostic().field());
        assertTrue(strict.diagnostic().detail().contains("missing input"), strict.diagnostic().format());
    }

    @Test void debugBootstrapRejectsLoadOnlyRootsDuringPreflight() throws Exception {
        Path project = fixtureWithoutSidecars();
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.<String, ToolConfig>emptyMap(), null, null);
        Path sidecar = project.resolve("templates/SIMPLE/debug.yaml");
        for (String reference : new String[]{"${EXEC.LOAD.USER_ID}", "${EXEC.LOAD}"}) {
            Files.write(sidecar, ("schemaVersion: att-debug/v1.2\nvars:\n  loadValue: '" + reference + "'\n")
                    .getBytes(StandardCharsets.UTF_8));
            DebugEngine.Result result = run(project, config, "template", "SIMPLE", "--output-dir",
                    temp.resolve("debug-load-root-" + Math.abs(reference.hashCode())).toString());

            assertEquals(ResultStatus.INVALID, result.status(), reference);
            assertNotNull(result.diagnostic());
            assertEquals("vars.loadValue", result.diagnostic().field());
            assertTrue(result.diagnostic().detail().contains("not an initialized bootstrap root"),
                    result.diagnostic().format());
        }
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
                "schemaVersion: att-template/v3.4\nname: SLOW\ndescription: slow debug\nactions:\n"
                        + "  wait:\n    type: tool\n    call: '#{slow()}'\n").getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/SLOW/debug.yaml"),
                "schemaVersion: att-debug/v1.2\ncase: {caseName: slow}\n".getBytes(StandardCharsets.UTF_8));
        ToolConfig slow = new ToolConfig("slow", "Slow", "Slow test tool", "/bin/sleep 1", "txt",
                Collections.<String, ToolArgumentConfig>emptyMap());
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("slow", slow), null, null);
        DebugEngine engine = new DebugEngine(project, config);
        ExecutionOptions parsedOptions = att.core.ExecutionOptionsTestSupport.parse(new String[]{"debug", "template", "SLOW"});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream eventOutput = new PrintStream(bytes, true, "UTF-8");
        java.util.List<att.api.ExecutionEvent> events = new java.util.concurrent.CopyOnWriteArrayList<att.api.ExecutionEvent>();
        final ExecutionOptions options = parsedOptions.withObserver(event -> {
            events.add(event);
            String message = event.message();
            if (event.type() == att.api.ExecutionEvent.Type.CASE_LOG)
                message = "[CASE-LOG case=" + event.caseId() + "] " + message;
            if (message != null) synchronized (eventOutput) { eventOutput.println(message); }
        });
        AtomicReference<DebugEngine.Result> result = new AtomicReference<DebugEngine.Result>();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread execution = new Thread(() -> {
            try { result.set(engine.run(options)); }
            catch (Throwable failure) { error.set(failure); }
        });
        boolean sawStart = false;
        boolean stillRunningAtStart = false;
        try {
            execution.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4L);
            while (System.nanoTime() < deadline && execution.isAlive()) {
                String console = bytes.toString("UTF-8");
                if (console.contains("type: tool, status: START")) {
                    sawStart = true;
                    stillRunningAtStart = execution.isAlive();
                    break;
                }
                Thread.sleep(10L);
            }
            execution.join(4000L);
        } finally { eventOutput.close(); }
        assertNull(error.get());
        assertTrue(sawStart, bytes.toString("UTF-8"));
        assertTrue(stillRunningAtStart, "the Action start must be visible while the Tool is still running");
        assertNotNull(result.get());
        assertEquals(ResultStatus.PASS, result.get().status());
        String live = bytes.toString("UTF-8");
        assertTrue(events.stream().anyMatch(event -> "DEBUG_INPUT_RESOLVED".equals(event.data().get("event"))
                && "INPUT_RESOLVED".equals(event.status()) && "template".equals(event.data().get("targetType"))));
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
        return new DebugEngine(project, config).run(att.core.ExecutionOptionsTestSupport.parse(args));
    }

    private FrameworkConfig withTestdata(FrameworkConfig base, Path descriptor) {
        return new FrameworkConfig(base.outputDirectory(), base.reportDirectory(), base.logDirectory(), base.environment(),
                base.timeoutMs(), base.templatesRoot(), base.testcasesRoot(), base.tools(), base.dbHelpers(), base.mqHelpers(),
                base.sshHelpers(), base.httpHelpers(), base.report(), base.run(), base.sheetGroups(), base.caseIdColumn(),
                base.tagsColumn(), base.dataColumns(), base.stages(), base.headerRows(), base.xmlNamespaceMode(),
                base.workbookId(), base.caseLogYamlAnchors(), base.processOutput(), Collections.singletonList(descriptor));
    }

    private Path fixture() throws Exception {
        Path project = fixtureWithoutSidecars();
        Files.write(project.resolve("templates/SIMPLE/debug.yaml"), (
                "schemaVersion: att-debug/v1.2\ncase:\n  value: sidecar\n  caseId: EVIL\n  outputDirectory: EVIL\n  VARS: EVIL\n  STAGES: EVIL\n" ).getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/debug/echo/debug.yaml"), (
                "schemaVersion: att-debug/v1.2\ninputs:\n  message: hello\n" ).getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(project.resolve("config/tools"));
        Files.write(project.resolve("config/tools/echo.debug.yaml"), (
                "schemaVersion: att-debug/v1.2\narguments:\n  value: hello-tool\n" ).getBytes(StandardCharsets.UTF_8));
        return project;
    }

    private Path fixtureWithoutSidecars() throws Exception {
        Path project = temp.resolve("project-" + System.nanoTime());
        att.TestSchemas.install(project);
        Files.createDirectories(project.resolve("templates/SIMPLE"));
        Files.createDirectories(project.resolve("templates/BROKEN"));
        Files.createDirectories(project.resolve("templates/flows/debug/echo"));
        Files.write(project.resolve("templates/SIMPLE/template.yaml"), (
                "schemaVersion: att-template/v3.4\nname: SIMPLE\ndescription: Simple debug template\nactions:\n  log:\n    type: log\n    message: 'value=${EXEC.INPUT.value}'\n" ).getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/BROKEN/template.yaml"), "not: [valid\n".getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("templates/flows/debug/echo/flow.yaml"), (
                "schemaVersion: att-flow/v3.4\nid: debug.echo.v1\nname: Debug Echo\ndescription: Debug Echo\nactions:\n  echo:\n    type: log\n    message: 'debug=${CASE.inputs.message}'\n" ).getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(project.resolve("output"));
        return project;
    }

}
