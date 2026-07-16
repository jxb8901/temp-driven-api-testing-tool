/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.*;
import att.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolInvokerTest {
    @TempDir Path tempDir;
    @Test void safelyParsesYamlAndXmlOutputs() throws Exception {
        ToolInvoker invoker = new ToolInvoker(tempDir, new FrameworkConfig(null,null,null,"SIT",10000,null,null,null,null));
        assertEquals(Boolean.TRUE, ((Map<?,?>) invoker.parseOutput("enabled: true\ncount: 2", "yaml")).get("enabled"));
        Map<?,?> xml = (Map<?,?>) invoker.parseOutput("<Response id=\"1\"><Status>SUCCESS</Status><Status>POSTED</Status></Response>", "xml");
        assertEquals("Response", xml.get("name"));
        assertEquals("1", ((Map<?,?>) xml.get("attributes")).get("id"));
        List<?> statuses = (List<?>) xml.get("Status");
        assertEquals("SUCCESS", statuses.get(0));
        assertEquals("POSTED", statuses.get(1));
        Map<?,?> nested = (Map<?,?>) invoker.parseOutput("<Response><Messages><Message/><Message severity=\"WARN\">review</Message></Messages></Response>", "xml");
        List<?> messages = (List<?>) ((Map<?,?>) nested.get("Messages")).get("Message");
        assertEquals("WARN", ((Map<?,?>) ((Map<?,?>) messages.get(1)).get("attributes")).get("severity"));
        Map<?,?> attributeOnly = (Map<?,?>) invoker.parseOutput("<Response><Code value=\"00\"/></Response>", "xml");
        assertEquals("00", ((Map<?,?>) ((Map<?,?>) attributeOnly.get("Code")).get("attributes")).get("value"));
        assertThrows(Exception.class, () -> invoker.parseOutput("{\"a\":1,\"a\":2}", "json"));
        assertThrows(Exception.class, () -> invoker.parseOutput("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>", "xml"));
        Map<?,?> json = (Map<?,?>) invoker.parseOutput("{\"items\":[1,null,123456789012345678901234567890],\"decimal\":1.234567890123456789}", "json");
        assertTrue(((List<?>) json.get("items")).get(2) instanceof java.math.BigInteger);
        assertTrue(json.get("decimal") instanceof java.math.BigDecimal);
    }

    @Test void preservesXmlNamespacesWhenConfigured() throws Exception {
        FrameworkConfig preserve = new FrameworkConfig(null,null,null,"SIT",10000,null,null,null,null,null,"","",null,null,1,"preserve");
        Map<?,?> xml = (Map<?,?>) new ToolInvoker(tempDir,preserve).parseOutput("<r:root xmlns:r=\"urn:r\"><r:item r:id=\"1\">A<b>B</b>C</r:item></r:root>","xml");
        assertEquals("{urn:r}root", xml.get("name"));
        Map<?,?> item = (Map<?,?>) xml.get("{urn:r}item");
        assertEquals("1", ((Map<?,?>) item.get("attributes")).get("{urn:r}id"));
        assertEquals("AC", item.get("text"));
    }

    @Test void invokesConfiguredToolWithTypedContextInput() throws Exception {
        Map<String,ToolArgumentConfig> arguments=new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("account",new ToolArgumentConfig("account","Account","Identifier",true,""));
        arguments.put("count",new ToolArgumentConfig("count","Count","Numeric count",true,""));
        arguments.put("enabled",new ToolArgumentConfig("enabled","Enabled","Boolean flag",true,""));
        Map<String,ToolConfig> tools=new LinkedHashMap<String,ToolConfig>();
        tools.put("copyInput",new ToolConfig("copyInput","Copy Input","Echo typed JSON","/usr/bin/printf '{\"account\":\"%s\",\"count\":%s,\"enabled\":%s}' ${account} ${input.count} ${enabled}","json",arguments));
        FrameworkConfig config=new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        Map<String,Object> data=new LinkedHashMap<String,Object>(); data.put("account","00123");
        TestCase test=new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),data,Collections.emptyMap(),null);
        CaseRuntimeContext context=new CaseRuntimeContext(test,tempDir.resolve("case"),"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        Map<String,Object> input=new LinkedHashMap<String,Object>(); input.put("account","00123"); input.put("count",7); input.put("enabled",true);
        ToolInvocationResult result=new ToolInvoker(tempDir,config).invoke("call","copyInput",input,context,new CaseExecutionLog(tempDir.resolve("case.log")));
        Map<?,?> output=(Map<?,?>)result.output();
        assertEquals("00123",output.get("account")); assertEquals(7,((Number) output.get("count")).intValue()); assertEquals(Boolean.TRUE,output.get("enabled"));
        assertNotNull(context.resolve("CASE.STAGES.invoke.TEMPLATE.ACTIONS.call.TOOL.copyInput"));
        assertNull(context.resolve("TOOL.inputFile"));
        assertNull(context.resolve("TOOL.outputFile"));
    }

    @Test void saveAsIsTheOnlyToolFileOutput() throws Exception {
        Map<String,ToolConfig> tools=new LinkedHashMap<String,ToolConfig>();
        tools.put("echo",new ToolConfig("echo","Echo","Echo stdout","echo result","txt",Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config=new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        TestCase test=new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context=new CaseRuntimeContext(test,tempDir.resolve("case"),"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        ToolInvocationResult result=new ToolInvoker(tempDir,config).invokeAttempt("call","echo",Collections.<String,Object>emptyMap(),context,new CaseExecutionLog(tempDir.resolve("case.log")),10000L,"response.txt");
        Path saved=Paths.get(String.valueOf(result.invocation().get("outputFile")));
        assertEquals("result\n",new String(Files.readAllBytes(saved),"UTF-8"));
        assertFalse(Files.exists(saved.getParent().resolve("input.yaml")));
    }

    @Test void dropsBlankDelimitedValuesFromArgumentArray() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("keywords", new ToolArgumentConfig("keywords", "Keywords", "Search values", false, ","));
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("grep", new ToolConfig("grep", "Grep", "Capture command", "echo ${input.keywords}", "txt", arguments));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        TestCase test = new TestCase(2, "payment", "sheet", "TC1", Collections.<String>emptyList(), data, Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir.resolve("case"), "RUN-1", tempDir, tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
        CapturingRunner runner = new CapturingRunner();
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("keywords", "N/A, NULL");
        new ToolInvoker(tempDir, config, runner).invokeAttempt("call", "grep", input, context, new CaseExecutionLog(tempDir.resolve("case.log")), 1234L);
        assertEquals(Collections.singletonList("echo"), runner.argv);
        assertEquals(Duration.ofMillis(1234), runner.timeout);
        assertEquals(context.caseOutputDirectory(), runner.workingDirectory);
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), runner.environment.get("ATT_ROOT_DIR"));
        assertEquals(context.caseOutputDirectory().toString(), runner.environment.get("ATT_CASE_OUTPUT_DIR"));
        assertEquals(2, runner.environment.size());
    }

    @Test void emitsArgNameAndValueTogetherOnlyWhenOptionalArgumentIsProvided() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("reference", new ToolArgumentConfig("reference", "Reference", "Optional reference", false, "", "--reference"));
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("capture", new ToolConfig("capture", "capture", "", "Capture", "Capture optional argv", Arrays.asList("capture", "${reference}"), Collections.<String>emptyList(), "txt", arguments, null));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        CapturingRunner runner = new CapturingRunner();
        ToolInvoker invoker = new ToolInvoker(tempDir, config, runner);

        invoker.invokeAttempt("missing", "capture", Collections.<String,Object>emptyMap(), context(), new CaseExecutionLog(tempDir.resolve("missing.log")), 1000L);
        assertEquals(Collections.singletonList("capture"), runner.argv);

        invoker.invokeAttempt("blank", "capture", Collections.<String,Object>singletonMap("reference", "N/A"), context(), new CaseExecutionLog(tempDir.resolve("blank.log")), 1000L);
        assertEquals(Collections.singletonList("capture"), runner.argv);

        String value = "A B O'Reilly;$(ignored)";
        ToolInvocationResult result = invoker.invokeAttempt("present", "capture", Collections.<String,Object>singletonMap("reference", value), context(), new CaseExecutionLog(tempDir.resolve("present.log")), 1000L);
        assertEquals(Arrays.asList("capture", "--reference", value), runner.argv);
        assertEquals(runner.argv, result.invocation().get("logicalArgv"));
    }

    @Test void emitsArgNameOnceByDefaultBeforeDelimitedValuesAndOmitsAnEmptyList() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("tags", new ToolArgumentConfig("tags", "Tags", "Optional tags", false, ",", "--tags"));
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("capture", new ToolConfig("capture", "capture", "", "Capture", "Capture optional list", Arrays.asList("capture", "${tags}"), Collections.<String>emptyList(), "txt", arguments, null));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        CapturingRunner runner = new CapturingRunner();
        ToolInvoker invoker = new ToolInvoker(tempDir, config, runner);

        invoker.invokeAttempt("empty", "capture", Collections.<String,Object>singletonMap("tags", "N/A,NULL"), context(), new CaseExecutionLog(tempDir.resolve("empty-list.log")), 1000L);
        assertEquals(Collections.singletonList("capture"), runner.argv);

        invoker.invokeAttempt("values", "capture", Collections.<String,Object>singletonMap("tags", "PAYMENT, POSTED"), context(), new CaseExecutionLog(tempDir.resolve("values.log")), 1000L);
        assertEquals(Arrays.asList("capture", "--tags", "PAYMENT", "POSTED"), runner.argv);
    }

    @Test void expandsMultipleDelimitedArgumentsAndRepeatsArgNameByDefault() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("keywords", new ToolArgumentConfig("keywords", "Keywords", "Search words", true, ",", "--keyword", "repeat"));
        arguments.put("types", new ToolArgumentConfig("types", "Types", "Transaction types", true, "|", "--types", "once"));
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("capture", new ToolConfig("capture", "capture", "", "Capture", "Capture two lists", Arrays.asList("capture", "${keywords}", "${types}"), Collections.<String>emptyList(), "txt", arguments, null));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        CapturingRunner runner = new CapturingRunner();
        Map<String,Object> input = new LinkedHashMap<String,Object>();
        input.put("keywords", "PAYMENT, POSTED");
        input.put("types", "CARD|TRANSFER");

        new ToolInvoker(tempDir, config, runner).invokeAttempt("values", "capture", input, context(), new CaseExecutionLog(tempDir.resolve("multiple-lists.log")), 1000L);

        assertEquals(Arrays.asList("capture", "--keyword", "PAYMENT", "--keyword", "POSTED", "--types", "CARD", "TRANSFER"), runner.argv);
    }

    @Test void treatsMissingOrEmptyArgNameAsAnOptionalPositionalArgument() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("reference", new ToolArgumentConfig("reference", "Reference", "Optional positional reference", false, "", ""));
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("capture", new ToolConfig("capture", "capture", "", "Capture", "Capture optional positional argv", Arrays.asList("capture", "${reference}"), Collections.<String>emptyList(), "txt", arguments, null));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        CapturingRunner runner = new CapturingRunner();
        ToolInvoker invoker = new ToolInvoker(tempDir, config, runner);

        invoker.invokeAttempt("missing", "capture", Collections.<String,Object>emptyMap(), context(), new CaseExecutionLog(tempDir.resolve("positional-missing.log")), 1000L);
        assertEquals(Collections.singletonList("capture"), runner.argv);

        invoker.invokeAttempt("present", "capture", Collections.<String,Object>singletonMap("reference", "REF 123"), context(), new CaseExecutionLog(tempDir.resolve("positional-present.log")), 1000L);
        assertEquals(Arrays.asList("capture", "REF 123"), runner.argv);
    }

    @Test void resolvedArgumentsRemainAtomicArgvValues() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("value", new ToolArgumentConfig("value", "Value", "Free text", true, ""));
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("capture", new ToolConfig("capture", "Capture", "Capture argv", "echo --value=${value} ${input.value}", "txt", arguments));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
        TestCase test = new TestCase(2, "payment", "sheet", "TC1", Collections.<String>emptyList(), Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir.resolve("case"), "RUN-1", tempDir, tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        CapturingRunner runner = new CapturingRunner();
        Map<String,Object> input = new LinkedHashMap<String,Object>();
        input.put("value", "A B O'Reilly a\\b;$(ignored)");
        ToolInvocationResult result = new ToolInvoker(tempDir, config, runner).invokeAttempt("call", "capture", input, context, new CaseExecutionLog(tempDir.resolve("case.log")), 1234L);
        assertEquals(Arrays.asList("echo", "--value=A B O'Reilly a\\b;$(ignored)", "A B O'Reilly a\\b;$(ignored)"), runner.argv);
        assertEquals(runner.argv, result.invocation().get("argv"));
    }

    @Test void prependsGroupScriptAndWritesQualifiedEvidenceTree() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("id", new ToolArgumentConfig("id", "ID", "ID", true, "", "--id"));
        ToolConfig grouped = new ToolConfig("database.select", "select", "database", "Select", "Select row",
                Arrays.asList("query", "${id}"), Arrays.asList("./tools/dispatch", "--safe"), "txt", arguments, null);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>(); tools.put(grouped.key(), grouped);
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        CaseRuntimeContext context = context(); CapturingRunner runner = new CapturingRunner();
        ToolInvocationResult result = new ToolInvoker(tempDir,config,runner).invokeAttempt("call","database.select",Collections.<String,Object>singletonMap("id","A B"),context,new CaseExecutionLog(tempDir.resolve("case.log")),1000L);
        List<String> logical = Arrays.asList("./tools/dispatch", "--safe", "select", "query", "--id", "A B");
        assertEquals(Arrays.asList(tempDir.resolve("tools/dispatch").toString(), "--safe", "select", "query", "--id", "A B"), runner.argv);
        assertEquals(logical, result.invocation().get("logicalArgv"));
        assertEquals(runner.argv, result.invocation().get("argv"));
        assertEquals(context.caseOutputDirectory(), runner.workingDirectory);
        assertNotNull(((Map<?,?>)((Map<?,?>)result.invocation().get("TOOL")).get("database")).get("select"));
    }

    @Test void wrapsLogicalArgvForSshWithSafeRemoteQuoting() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("value", new ToolArgumentConfig("value", "Value", "Value", true, "", "--value"));
        SshConfig ssh = new SshConfig("tools.example", "att", 2222, "keys/id_ed25519");
        ToolConfig tool = new ToolConfig("remote.echo", "echo", "remote", "Echo", "Remote echo",
                Arrays.asList("printf", "%s", "${value}"), Collections.<String>emptyList(), "txt", arguments, ssh);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>(); tools.put(tool.key(), tool);
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        CaseRuntimeContext context = context(); CapturingRunner runner = new CapturingRunner();
        SshCommandRunner sshRunner = new SshCommandRunner(runner, () -> true,
                (target, command, timeout, root) -> { throw new AssertionError("Java fallback must not run"); }, System.err);
        String hostile = "A B O'Reilly;$(ignored)";
        ToolInvocationResult result = new ToolInvoker(tempDir,config,runner,sshRunner).invokeAttempt("call","remote.echo",Collections.<String,Object>singletonMap("value",hostile),context,new CaseExecutionLog(tempDir.resolve("case.log")),1000L);
        assertEquals(Arrays.asList("ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-p", "2222", "-i", tempDir.resolve("keys/id_ed25519").toString(), "--", "att@tools.example", "'printf' '%s' '--value' 'A B O'\"'\"'Reilly;$(ignored)'"), runner.argv);
        assertEquals(Arrays.asList("printf", "%s", "--value", hostile), result.invocation().get("logicalArgv"));
        assertTrue(runner.environment.isEmpty());
        Map<?,?> toolEvidence = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) result.invocation().get("TOOL")).get("remote")).get("echo");
        assertEquals("att@tools.example", ((Map<?,?>) toolEvidence.get("ssh")).get("destination"));
        assertEquals("openssh", ((Map<?,?>) toolEvidence.get("ssh")).get("transport"));
    }

    @Test void fallsBackToMwiedeJschWhenLocalSshIsUnavailable() throws Exception {
        SshConfig ssh = new SshConfig("tools.example", "att", 22, "keys/id_ed25519");
        ToolConfig tool = new ToolConfig("remote.echo", "echo", "remote", "Echo", "Remote echo",
                Arrays.asList("printf", "fallback"), Collections.<String>emptyList(), "txt", Collections.<String,ToolArgumentConfig>emptyMap(), ssh);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>(); tools.put(tool.key(), tool);
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        ByteArrayOutputStream warning = new ByteArrayOutputStream();
        final List<String> remoteCommands = new ArrayList<String>();
        SshCommandRunner sshRunner = new SshCommandRunner(new CapturingRunner(), () -> false,
                (target, command, timeout, root) -> { remoteCommands.add(command); return new CommandResult(0, "fallback", "", false); },
                new PrintStream(warning, true, "UTF-8"));
        ToolInvocationResult result = new ToolInvoker(tempDir,config,new CapturingRunner(),sshRunner).invokeAttempt("call","remote.echo",Collections.<String,Object>emptyMap(),context(),new CaseExecutionLog(tempDir.resolve("fallback.log")),1000L);
        assertEquals("fallback", result.output());
        assertEquals(Collections.singletonList("'printf' 'fallback'"), remoteCommands);
        assertEquals(Arrays.asList("printf", "fallback"), result.invocation().get("argv"));
        Map<?,?> toolEvidence = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) result.invocation().get("TOOL")).get("remote")).get("echo");
        assertEquals("mwiede/jsch", ((Map<?,?>) toolEvidence.get("ssh")).get("transport"));
        assertTrue(warning.toString("UTF-8").contains(SshCommandRunner.FALLBACK_WARNING));
    }

    @Test void javaSshFallbackRequiresStrictKnownHostsFileBeforeConnecting() {
        JschSshClient client = new JschSshClient(tempDir.resolve("missing-known-hosts"));
        IOException error = assertThrows(IOException.class, () -> client.run(new SshConfig("tools.example", "att", 22, ""), "'true'", Duration.ofSeconds(1), tempDir));
        assertTrue(error.getMessage().contains("known_hosts"));
    }

    private CaseRuntimeContext context() {
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,tempDir.resolve("case"),"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        return context;
    }

    private static final class CapturingRunner extends CommandRunner {
        private List<String> argv;
        private Duration timeout;
        private Path workingDirectory;
        private Map<String, String> environment = Collections.emptyMap();

        @Override
        public CommandResult run(List<String> argv, Duration timeout, java.nio.file.Path workingDirectory) {
            return capture(argv, timeout, workingDirectory, Collections.<String, String>emptyMap());
        }

        @Override
        public CommandResult run(List<String> argv, Duration timeout, Path workingDirectory, Map<String, String> environment) {
            return capture(argv, timeout, workingDirectory, environment);
        }

        private CommandResult capture(List<String> argv, Duration timeout, Path workingDirectory, Map<String, String> environment) {
            this.argv = new ArrayList<String>(argv);
            this.timeout = timeout;
            this.workingDirectory = workingDirectory;
            this.environment = new LinkedHashMap<String, String>(environment);
            return new CommandResult(0, "", "", false);
        }
    }
}
