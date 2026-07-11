/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.*;
import att.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
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
        List<?> statuses = (List<?>) ((Map<?,?>) xml.get("children")).get("Status");
        assertEquals("SUCCESS", ((Map<?,?>) statuses.get(0)).get("text"));
        assertEquals("POSTED", ((Map<?,?>) statuses.get(1)).get("text"));
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
        Map<?,?> item = (Map<?,?>) ((Map<?,?>) xml.get("children")).get("{urn:r}item");
        assertEquals("1", ((Map<?,?>) item.get("attributes")).get("{urn:r}id"));
        assertEquals("AC", item.get("text"));
    }

    @Test void invokesConfiguredToolWithTypedContextInput() throws Exception {
        Map<String,ToolArgumentConfig> arguments=new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("account",new ToolArgumentConfig("account","Account","Identifier",true,""));
        arguments.put("count",new ToolArgumentConfig("count","Count","Numeric count",true,""));
        arguments.put("enabled",new ToolArgumentConfig("enabled","Enabled","Boolean flag",true,""));
        Map<String,ToolConfig> tools=new LinkedHashMap<String,ToolConfig>();
        tools.put("copyInput",new ToolConfig("copyInput","Copy Input","Echo typed YAML","/bin/cp ${TOOL.inputFile} ${TOOL.outputFile}","yaml",arguments));
        FrameworkConfig config=new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        Map<String,Object> data=new LinkedHashMap<String,Object>(); data.put("account","00123");
        TestCase test=new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),data,Collections.emptyMap(),null);
        CaseRuntimeContext context=new CaseRuntimeContext(test,tempDir.resolve("case"),"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        Map<String,Object> input=new LinkedHashMap<String,Object>(); input.put("account","00123"); input.put("count",7); input.put("enabled",true);
        ToolInvocationResult result=new ToolInvoker(tempDir,config).invoke("call","copyInput",input,context,new CaseExecutionLog(tempDir.resolve("case.log")));
        Map<?,?> output=(Map<?,?>)result.output();
        assertEquals("00123",output.get("account")); assertEquals(Integer.valueOf(7),output.get("count")); assertEquals(Boolean.TRUE,output.get("enabled"));
        assertNotNull(context.resolve("CASE.STAGES.invoke.TEMPLATE.ACTIONS.call.TOOL.copyInput"));
        assertNotNull(context.resolve("TOOL.inputFile"));
        assertNotNull(context.resolve("TOOL.outputFile"));
    }

    @Test void dropsBlankDelimitedValuesFromFinalArray() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("keywords", new ToolArgumentConfig("keywords", "Keywords", "Search values", false, ","));
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("grep", new ToolConfig("grep", "Grep", "Capture command", "echo ${TOOL.input.keywords}", "txt", arguments));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        TestCase test = new TestCase(2, "payment", "sheet", "TC1", Collections.<String>emptyList(), data, Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir.resolve("case"), "RUN-1", tempDir, tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
        CapturingRunner runner = new CapturingRunner();
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("keywords", "N/A, NULL");
        new ToolInvoker(tempDir, config, runner).invokeAttempt("call", "grep", input, context, new CaseExecutionLog(tempDir.resolve("case.log")), 1234L);
        assertEquals("echo", runner.command.trim());
        assertEquals(Duration.ofMillis(1234), runner.timeout);
    }

    private static final class CapturingRunner extends CommandRunner {
        private String command;
        private Duration timeout;

        @Override
        public CommandResult run(String command, Duration timeout, java.nio.file.Path workingDirectory) {
            this.command = command;
            this.timeout = timeout;
            return new CommandResult(0, "", "", false);
        }
    }
}
