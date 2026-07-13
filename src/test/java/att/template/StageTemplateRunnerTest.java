/* Author: Jeffrey + ChatGPT */
package att.template;

import att.core.*;
import att.config.*;
import att.exec.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StageTemplateRunnerTest {
    @TempDir Path tempDir;
    @Test void recordsLogAndAssertionActions() throws Exception {
        TestCase test=new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),new LinkedHashMap<String,Object>(),Collections.emptyMap(),null);
        CaseRuntimeContext context=new CaseRuntimeContext(test,tempDir,"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        List<TemplateAction> actions=Arrays.asList(new TemplateAction("note",map("type","log","message","ok")),new TemplateAction("check",map("type","assert","expression","true")));
        List<ValidationResult> results=new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute("verify",new StageTemplate("T",tempDir,actions),context,new CaseExecutionLog(tempDir.resolve("case.log")));
        assertEquals(2,results.size()); assertEquals(ResultStatus.PASS,results.get(1).status());
    }
    @Test void retriesEligibleExitCodeAndNeverRetriesTimeout() throws Exception {
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),new LinkedHashMap<String,Object>(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,tempDir.resolve("case1"),"R",tempDir,tempDir.resolve("case1.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("sample", new ToolConfig("sample","Sample","test","sample","txt",Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        SequencedRunner runner = new SequencedRunner(false);
        Map<String,Object> retry = map("maxAttempts",3,"retryOn",Arrays.asList("EXIT_CODE"),"exitCodes",Arrays.asList(75));
        TemplateAction action = new TemplateAction("call",map("type","tool","call","#{sample()}","saveAs","${CASE.caseId}-response.txt","assert","${ACTIONS.call.output} == 'ok'","retry",retry));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,runner))).execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(tempDir.resolve("case1.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status()); assertEquals(2, runner.calls);
        assertEquals(2, ((List<?>) context.resolve("ACTIONS.call.attempts")).size());
        assertEquals("ok", new String(Files.readAllBytes(Paths.get(String.valueOf(context.resolve("ACTIONS.call.outputFile")))),"UTF-8"));
        try (java.util.stream.Stream<Path> paths = Files.walk(tempDir)) { assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("attempt-"))); }

        CaseRuntimeContext timeoutContext = new CaseRuntimeContext(test,tempDir.resolve("case2"),"R2",tempDir,tempDir.resolve("case2.log"));
        timeoutContext.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        SequencedRunner timeout = new SequencedRunner(true);
        TemplateAction timeoutAction = new TemplateAction("call",map("type","tool","call","#{sample()}","retry",map("maxAttempts",3,"retryOn",Arrays.asList("EXIT_CODE"))));
        List<ValidationResult> timeoutResults = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,timeout))).execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(timeoutAction)),timeoutContext,new CaseExecutionLog(tempDir.resolve("case2.log")));
        assertEquals(ResultStatus.ERROR, timeoutResults.get(0).status()); assertEquals(1, timeout.calls);
        assertTrue(new String(Files.readAllBytes(tempDir.resolve("case2.log")),"UTF-8").contains("Tool timed out: sample"));
    }

    @Test void renderAndToolSupportInlineAssertionsAndCaseLogSaveAsRules() throws Exception {
        Path logDirectory = tempDir.resolve("logs");
        Files.createDirectories(logDirectory);
        Files.write(tempDir.resolve("payload.txt"), "hello ${CASE.caseId}".getBytes("UTF-8"));
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,tempDir.resolve("output"),"R",tempDir,logDirectory.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("sample", new ToolConfig("sample","Sample","test","sample","txt",Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        List<TemplateAction> actions = Arrays.asList(
            new TemplateAction("render", map("type","render","payload","payload.txt","saveAs","${CASE.caseId}-request.txt","assert","${ACTIONS.render.output} == 'hello g.TC1'")),
            new TemplateAction("call", map("type","tool","call","#{sample()}","assert","${ACTIONS.call.output} == 'expected'","onFailure","continue"))
        );
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,new SequencedRunner(false, true)))).execute("invoke",new StageTemplate("T",tempDir,actions),context,new CaseExecutionLog(logDirectory.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(ResultStatus.FAIL, results.get(1).status());
        assertEquals("hello g.TC1", new String(Files.readAllBytes(logDirectory.resolve("g.TC1-request.txt")),"UTF-8"));
        assertFalse(Files.exists(tempDir.resolve("output/invoke/render/g.TC1-request.txt")));
    }

    @Test void saveAsCollisionFailsUnlessOverwriteIsTrue() throws Exception {
        Files.write(tempDir.resolve("payload.txt"), "new".getBytes("UTF-8"));
        Files.write(tempDir.resolve("same.txt"), "old".getBytes("UTF-8"));
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,tempDir.resolve("output"),"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        TemplateAction action = new TemplateAction("render", map("type","render","payload","payload.txt","saveAs","same.txt"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(tempDir.resolve("case.log")));
        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertEquals("old", new String(Files.readAllBytes(tempDir.resolve("same.txt")),"UTF-8"));
    }
    private static final class SequencedRunner extends CommandRunner {
        int calls; final boolean timeout;
        final boolean alwaysSuccess;
        SequencedRunner(boolean timeout) { this(timeout, false); }
        SequencedRunner(boolean timeout, boolean alwaysSuccess) { this.timeout = timeout; this.alwaysSuccess = alwaysSuccess; }
        @Override public CommandResult run(List<String> argv, java.time.Duration duration, Path workingDirectory) { calls++; if (timeout) return new CommandResult(-1,"","",true); return alwaysSuccess || calls > 1 ? new CommandResult(0,"ok","",false) : new CommandResult(75,"first","retry",false); }
    }
    private Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
