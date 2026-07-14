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
        List<TemplateAction> actions=Arrays.asList(new TemplateAction("note",map("type","log","message","ok")),new TemplateAction("check",map("type","assert","assert","true","description","Case ${CASE.caseId}","expected","true","actual","${output.success}")));
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
        TemplateAction action = new TemplateAction("call",map("type","tool","call","#{sample()}","saveAs","${CASE.caseId}-response.txt","assert","${output.result} == 'ok'","retry",retry));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,runner))).execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(tempDir.resolve("case1.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status()); assertEquals(2, runner.calls);
        assertEquals(2, ((List<?>) context.resolve("ACTIONS.call.output.attempts")).size());
        assertEquals("ok", new String(Files.readAllBytes(Paths.get(String.valueOf(context.resolve("ACTIONS.call.output.targetFiles[0]")))),"UTF-8"));
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
            new TemplateAction("render", map("type","render","payload","payload.txt","renderAs","file","assert","${output.targetFiles[0]} != null")),
            new TemplateAction("call", map("type","tool","call","#{sample()}","assert","${output.result} == 'expected'","onFailure","continue"))
        );
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,new SequencedRunner(false, true)))).execute("invoke",new StageTemplate("T",tempDir,actions),context,new CaseExecutionLog(logDirectory.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(ResultStatus.FAIL, results.get(1).status());
        assertEquals("hello g.TC1", new String(Files.readAllBytes(tempDir.resolve("output/payload.txt")),"UTF-8"));
        assertFalse(Files.exists(logDirectory.resolve("payload.txt")));
    }

    @Test void saveAsCollisionFailsUnlessOverwriteIsTrue() throws Exception {
        Files.write(tempDir.resolve("payload.txt"), "new".getBytes("UTF-8"));
        Files.createDirectories(tempDir.resolve("output"));
        Files.write(tempDir.resolve("output/payload.txt"), "old".getBytes("UTF-8"));
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,tempDir.resolve("output"),"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        TemplateAction action = new TemplateAction("render", map("type","render","payload","payload.txt","renderAs","file"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(tempDir.resolve("case.log")));
        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertEquals("old", new String(Files.readAllBytes(tempDir.resolve("output/payload.txt")),"UTF-8"));
    }

    @Test void rendersGlobToDeterministicTypedResultsAndCompletesTwoPhaseText() throws Exception {
        Path template = tempDir.resolve("template");
        Files.createDirectories(template.resolve("data"));
        Files.write(template.resolve("data/b.json"), "{\"value\":2}".getBytes("UTF-8"));
        Files.write(template.resolve("data/a.json"), "{\"value\":1}".getBytes("UTF-8"));
        Files.write(template.resolve("value.yaml"), "name: ${CASE.caseId}\n".getBytes("UTF-8"));
        Files.write(template.resolve("value.xml"), "<Result><Status>OK</Status></Result>".getBytes("UTF-8"));
        Files.write(template.resolve("value.txt"), "hello ${CASE.caseId}".getBytes("UTF-8"));
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        Path caseDir = tempDir.resolve("case");
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("render","T",Collections.<String,Object>emptyMap()),"T",template);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("json", map("type","render","payload","data/*.json","renderAs","json","description","Render ${CASE.caseId}; status=${output.status}")),
                new TemplateAction("yaml", map("type","render","payload","value.yaml","renderAs","yaml")),
                new TemplateAction("xml", map("type","render","payload","value.xml","renderAs","xml")),
                new TemplateAction("text", map("type","render","payload","value.txt","renderAs","text")),
                new TemplateAction("check", map("type","assert","description","Check ${CASE.caseId}","assert","${ACTIONS.json.output.result['data/a.json'].value} == 1","expected","want ${CASE.caseId}\r\nline2","actual","${output.success}"))
        );
        FrameworkConfig parseConfig = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,template,Collections.<String,ToolConfig>emptyMap(),null,null);
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir,parseConfig))).execute("render",new StageTemplate("T",template,actions),context,new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(5, results.size());
        assertEquals(Arrays.asList("data/a.json","data/b.json"), new ArrayList<Object>(((Map<?,?>)context.resolve("ACTIONS.json.output.result")).keySet()));
        assertEquals("json", context.resolve("ACTIONS.json.output.renderAs"));
        assertEquals("Render g.TC1; status=PASS", context.resolve("ACTIONS.json.description"));
        assertEquals("g.TC1", context.resolve("ACTIONS.yaml.output.result.name"));
        assertEquals("OK", context.resolve("ACTIONS.xml.output.result.Status"));
        assertEquals("hello g.TC1", context.resolve("ACTIONS.text.output.result"));
        assertEquals("Check g.TC1\nwant g.TC1\nline2", results.get(4).expected());
        assertEquals("true", results.get(4).actual());
    }

    @Test void toolExitCodeIsEvidenceAndAssertionControlsResult() throws Exception {
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        Path caseDir = tempDir.resolve("exit-case");
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("sample", new ToolConfig("sample","Sample","test","sample","txt",Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("unasserted",map("type","tool","call","#{sample()}")),
                new TemplateAction("accepted",map("type","tool","call","#{sample()}","assert","${output.exitCode} == 9")),
                new TemplateAction("rejected",map("type","tool","call","#{sample()}","assert","${output.exitCode} == 0","onFailure","continue"))
        );
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,new FixedRunner(9,"ok")))).execute("invoke",new StageTemplate("T",tempDir,actions),context,new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(Arrays.asList(ResultStatus.PASS,ResultStatus.PASS,ResultStatus.FAIL), Arrays.asList(results.get(0).status(),results.get(1).status(),results.get(2).status()));
        assertEquals(9, context.resolve("ACTIONS.unasserted.output.exitCode"));
        assertEquals(false, context.resolve("ACTIONS.rejected.output.success"));
    }

    @Test void unsafeOrEmptyRenderGlobProducesNestedErrorOutcome() throws Exception {
        Files.write(tempDir.resolve("payload.txt"), "x".getBytes("UTF-8"));
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,tempDir.resolve("glob-case"),"R",tempDir,tempDir.resolve("glob.log"));
        context.beginStage(new StageCaseData("render","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        TemplateAction action = new TemplateAction("render",map("type","render","description","Render ${CASE.caseId}; status=${output.status}","payload","../*.txt","renderAs","text","onFailure","continue"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute("render",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(tempDir.resolve("glob.log")));
        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertEquals("ERROR", context.resolve("ACTIONS.render.output.status"));
        assertNotNull(context.resolve("ACTIONS.render.output.exception.message"));
        assertEquals("Render g.TC1; status=ERROR", context.resolve("ACTIONS.render.description"));
        assertTrue(((Number)context.resolve("ACTIONS.render.output.durationMs")).longValue() >= 0);
    }
    private static final class SequencedRunner extends CommandRunner {
        int calls; final boolean timeout;
        final boolean alwaysSuccess;
        SequencedRunner(boolean timeout) { this(timeout, false); }
        SequencedRunner(boolean timeout, boolean alwaysSuccess) { this.timeout = timeout; this.alwaysSuccess = alwaysSuccess; }
        @Override public CommandResult run(List<String> argv, java.time.Duration duration, Path workingDirectory) { calls++; if (timeout) return new CommandResult(-1,"","",true); return alwaysSuccess || calls > 1 ? new CommandResult(0,"ok","",false) : new CommandResult(75,"first","retry",false); }
    }
    private static final class FixedRunner extends CommandRunner {
        private final int exitCode; private final String stdout;
        private FixedRunner(int exitCode, String stdout) { this.exitCode=exitCode; this.stdout=stdout; }
        @Override public CommandResult run(List<String> argv, java.time.Duration duration, Path workingDirectory) { return new CommandResult(exitCode,stdout,"",false); }
    }
    private Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
