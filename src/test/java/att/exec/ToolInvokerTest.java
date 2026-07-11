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
        ToolInvoker invoker = new ToolInvoker(tempDir, new FrameworkConfig(null,null,null,"SIT",10,null,null,null,null));
        assertEquals(Boolean.TRUE, ((Map<?,?>) invoker.parseOutput("enabled: true\ncount: 2", "yaml")).get("enabled"));
        assertEquals("SUCCESS", ((Map<?,?>)((Map<?,?>)invoker.parseOutput("<Response><Status>SUCCESS</Status></Response>", "xml")).get("Response")).get("Status"));
    }

    @Test void invokesConfiguredToolWithTypedContextInput() throws Exception {
        Map<String,ToolArgumentConfig> arguments=new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("account",new ToolArgumentConfig("account","Account","Identifier",true,""));
        arguments.put("count",new ToolArgumentConfig("count","Count","Numeric count",true,""));
        arguments.put("enabled",new ToolArgumentConfig("enabled","Enabled","Boolean flag",true,""));
        Map<String,ToolConfig> tools=new LinkedHashMap<String,ToolConfig>();
        tools.put("copyInput",new ToolConfig("copyInput","Copy Input","Echo typed YAML","/bin/cp ${TOOL.inputFile} ${TOOL.outputFile}","yaml",arguments));
        FrameworkConfig config=new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10,tempDir,tools,null,null);
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
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10, tempDir, tools, null, null);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        TestCase test = new TestCase(2, "payment", "sheet", "TC1", Collections.<String>emptyList(), data, Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir.resolve("case"), "RUN-1", tempDir, tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
        CapturingRunner runner = new CapturingRunner();
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("keywords", "N/A, NULL");
        new ToolInvoker(tempDir, config, runner).invoke("call", "grep", input, context, new CaseExecutionLog(tempDir.resolve("case.log")));
        assertEquals("echo", runner.command.trim());
    }

    private static final class CapturingRunner extends CommandRunner {
        private String command;

        @Override
        public CommandResult run(String command, Duration timeout, java.nio.file.Path workingDirectory) {
            this.command = command;
            return new CommandResult(0, "", "", false);
        }
    }
}
