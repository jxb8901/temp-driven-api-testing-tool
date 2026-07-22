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
    @Test void toolActionCanInvokeBuiltInAndPersistItsResult() throws Exception {
        Path caseDir = tempDir.resolve("builtin-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("prepare","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        TemplateAction action = new TemplateAction("normalize", map("type","tool","call","#{upper('abc')}",
                "saveAs","normalized.txt","assert","${output.result} == 'ABC'"));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("prepare",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals("ABC", context.resolve("ACTIONS.normalize.output.result"));
        assertEquals(0, context.resolve("ACTIONS.normalize.output.exitCode"));
        assertEquals("builtin", context.resolve("ACTIONS.normalize.output.attempts[0].type"));
        assertEquals("upper", context.resolve("ACTIONS.normalize.output.attempts[0].name"));
        assertEquals("ABC", new String(Files.readAllBytes(caseDir.resolve("normalized.txt")), "UTF-8"));
        assertNull(context.resolve("ACTIONS.normalize.TOOL"));
    }

    @Test void everyActionTextSurfaceSupportsInlineBuiltIns() throws Exception {
        Map<String,Object> data = new LinkedHashMap<String,Object>(); data.put("SrcRefNo", "ABC123");
        TestCase test=new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),data,Collections.emptyMap(),null);
        CaseRuntimeContext context=new CaseRuntimeContext(test,tempDir,"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        List<TemplateAction> actions=Arrays.asList(
                new TemplateAction("note",map("type","log","message","#{lower(CASE.SrcRefNo)}","fields",map("size","#{length(CASE.SrcRefNo)}"),"description","Logged #{upper(output.result)}")),
                new TemplateAction("check",map("type","assert","assert","#{length(value=CASE.SrcRefNo)} <= 35","description","#{concat('Check ', CASE.caseId)}","expected","#{upper('ok')}","actual","#{lower('OK')}")));

        List<ValidationResult> results=new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute("verify",new StageTemplate("T",tempDir,actions),context,new CaseExecutionLog(tempDir.resolve("case.log")));

        assertEquals(Arrays.asList(ResultStatus.PASS, ResultStatus.PASS), Arrays.asList(results.get(0).status(), results.get(1).status()));
        assertEquals("abc123", context.resolve("ACTIONS.note.output.result"));
        assertEquals("6", context.resolve("ACTIONS.note.output.fields.size"));
        assertEquals("Logged ABC123", context.resolve("ACTIONS.note.description"));
        assertEquals("6 <= 35", ((Map<?,?>) context.resolve("ACTIONS.check.output.assertion")).get("rendered"));
        assertEquals("Check g.TC1\nOK", results.get(1).expected());
        assertEquals("ok", results.get(1).actual());
    }

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
        String timeoutLog = new String(Files.readAllBytes(tempDir.resolve("case2.log")),"UTF-8");
        assertTrue(timeoutLog.contains("Tool timed out: sample"));
        assertEquals(1, occurrences(timeoutLog, "Tool timed out: sample"));
        assertEquals(1, occurrences(timeoutLog, "[ACTION call ERROR]"));
        assertFalse(timeoutLog.contains("TOOL:"));
    }

    @Test void logActionCanEmitUtf8FileContentWithoutDuplicatingIt() throws Exception {
        Path caseDir = tempDir.resolve("log-file-case");
        Files.createDirectories(caseDir);
        Path source = caseDir.resolve("response.txt");
        Files.write(source, "第一行\r\n<Status>&OK</Status>\r\n".getBytes("UTF-8"));
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        TemplateAction action = new TemplateAction("response", map("type","log","file","#{concat(CASE.outputDirectory, '/response.txt')}","level","DEBUG"));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("verify",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals("第一行\n<Status>&OK</Status>\n", context.resolve("ACTIONS.response.output.result"));
        assertEquals(source.toRealPath().toString(), context.resolve("ACTIONS.response.output.sourceFile"));
        String text = new String(Files.readAllBytes(caseDir.resolve("case.log")),"UTF-8");
        assertEquals(1, occurrences(text, "<Status>&OK</Status>"));
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

    @Test void assignPublishesCaseVariableAcrossStagesAndAssertionDoesNotRollback() throws Exception {
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        Path caseDir = tempDir.resolve("assign-case");
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("prepare","PREPARE",Collections.<String,Object>emptyMap()),"PREPARE",tempDir);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("seq", new ToolConfig("seq","Sequence","test","seq","txt",Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        TemplateAction assign = new TemplateAction("build", map("type","assign","name","txnSeq",
                "expression","ATT#{upper('x')}#{seq()}","assert","${output.result} == 'wrong'","onFailure","continue"));
        List<ValidationResult> assigned = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,new FixedRunner(0,"0007"))))
                .execute("prepare",new StageTemplate("PREPARE",tempDir,Collections.singletonList(assign)),context,new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(ResultStatus.FAIL, assigned.get(0).status());
        assertEquals("ATTX0007", context.resolve("CASE.VARS.txnSeq"));
        assertEquals("ATTX0007", context.resolve("ACTIONS.build.output.result"));

        context.beginStage(new StageCaseData("invoke","INVOKE",Collections.<String,Object>emptyMap()),"INVOKE",tempDir);
        TemplateAction log = new TemplateAction("use", map("type","log","message","id=${CASE.VARS.txnSeq}"));
        List<ValidationResult> used = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("invoke",new StageTemplate("INVOKE",tempDir,Collections.singletonList(log)),context,new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(ResultStatus.PASS, used.get(0).status());
        assertEquals("id=ATTX0007", context.resolve("ACTIONS.use.output.result"));
    }

    @Test void failedOrDuplicateAssignDoesNotReplaceCaseVariable() throws Exception {
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        Path caseDir = tempDir.resolve("duplicate-assign");
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("prepare","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("first",map("type","assign","name","txnSeq","expression","FIRST")),
                new TemplateAction("duplicate",map("type","assign","name","txnSeq","expression","SECOND","onFailure","continue")),
                new TemplateAction("failed",map("type","assign","name","missingValue","expression","${CASE.missing}","onFailure","continue")));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("prepare",new StageTemplate("T",tempDir,actions),context,new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(ResultStatus.ERROR, results.get(1).status());
        assertEquals(ResultStatus.ERROR, results.get(2).status());
        assertEquals("FIRST", context.resolve("CASE.VARS.txnSeq"));
        assertNull(context.resolve("CASE.VARS.missingValue"));
    }
    private static final class SequencedRunner extends CommandRunner {
        int calls; final boolean timeout;
        final boolean alwaysSuccess;
        SequencedRunner(boolean timeout) { this(timeout, false); }
        SequencedRunner(boolean timeout, boolean alwaysSuccess) { this.timeout = timeout; this.alwaysSuccess = alwaysSuccess; }
        @Override public CommandResult run(List<String> argv, java.time.Duration duration, Path workingDirectory, Map<String,String> environment) { calls++; if (timeout) return new CommandResult(-1,"","",true); return alwaysSuccess || calls > 1 ? new CommandResult(0,"ok","",false) : new CommandResult(75,"first","retry",false); }
    }
    private static final class FixedRunner extends CommandRunner {
        private final int exitCode; private final String stdout;
        private FixedRunner(int exitCode, String stdout) { this.exitCode=exitCode; this.stdout=stdout; }
        @Override public CommandResult run(List<String> argv, java.time.Duration duration, Path workingDirectory, Map<String,String> environment) { return new CommandResult(exitCode,stdout,"",false); }
    }
    private int occurrences(String text,String value){int count=0,index=0;while((index=text.indexOf(value,index))>=0){count++;index+=value.length();}return count;}
    private Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
