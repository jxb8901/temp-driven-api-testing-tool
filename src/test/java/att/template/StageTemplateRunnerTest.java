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

    @Test void runtimeContextFailureInsideFoldedCallPointsAtReference() throws Exception {
        StageTemplateLoader.clearForTests();
        att.TestSchemas.install(tempDir);
        Path templateDir = tempDir.resolve("templates/context-path");
        Files.createDirectories(templateDir);
        String reference = "${output.result.EaiRtn.EaiCode}";
        String templateText = "schemaVersion: att-template/v3.3\nname: Context path\ndescription: Runtime source mapping\n"
                + "actions:\n  invoke:\n    type: tool\n    call: >-\n"
                + "      #{sample(value=" + reference + ")}\n";
        Path descriptor = templateDir.resolve("template.yaml");
        Files.write(descriptor, templateText.getBytes("UTF-8"));
        StageTemplate template = new StageTemplateLoader(tempDir, Paths.get("templates")).load("context-path");

        Path caseDir = tempDir.resolve("context-path-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "Context path", Collections.<String, Object>emptyMap()),
                "Context path", templateDir);
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute(
                "invoke", template, context, new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertEquals("ATT-CTX-001", results.get(0).diagnostic().code());
        att.validation.SourceLocation source = results.get(0).diagnostic().source();
        assertNotNull(source);
        assertEquals(8, source.line());
        assertEquals(templateText.split("\\n")[7].indexOf(reference) + 1, source.column());
        assertTrue(source.excerpt().contains("call: >-"));
        assertTrue(source.excerpt().contains(reference));
    }

    @Test void toolOutputsStayNativeWithoutSharedResultPersistence() throws Exception {
        Path caseDir = tempDir.resolve("native-tool-output");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("prepare", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("json", map("type", "tool", "call", "#{upper('abc')}"), "att-template/v3.3"),
                new TemplateAction("text", map("type", "tool", "call", "#{upper('def')}"), "att-template/v3.3"));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("prepare", new StageTemplate("T", tempDir, actions, "att-template/v3.3"), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(Arrays.asList(ResultStatus.PASS, ResultStatus.PASS),
                Arrays.asList(results.get(0).status(), results.get(1).status()));
        assertEquals("ABC", context.resolve("ACTIONS.json.output.result"));
        assertEquals("DEF", context.resolve("ACTIONS.text.output.result"));
        assertFalse(Files.exists(caseDir.resolve("saved")));
    }

    @Test void toolDescriptorParsesNativeTypedResultWithoutCreatingArtifact() throws Exception {
        Path caseDir = tempDir.resolve("typed-tool-output");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("prepare", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("sample", new ToolConfig("sample", "Sample", "test", "fake", "json",
                Collections.<String, ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
        TemplateAction action = new TemplateAction("typed", map("type", "tool", "call", "#{sample()}"));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(
                new ToolInvoker(tempDir, config, new FixedRunner(0, "{\"ok\":true}))))
                .execute("prepare", new StageTemplate("T", tempDir, Collections.singletonList(action)), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        assertEquals(Boolean.TRUE, context.resolve("ACTIONS.typed.output.result.ok"));
        assertFalse(Files.exists(caseDir.resolve("selected.json")));
    }

    @Test void textToolResultsRemainInContextForWhitespaceAndStreamedCapture() throws Exception {
        Path caseDir = tempDir.resolve("raw-result-capture");
        Files.createDirectories(caseDir);
        Path whitespaceScript = tempDir.resolve("whitespace-output.sh");
        Files.write(whitespaceScript, "#!/bin/sh\nprintf '  abc \\n\\n'\n".getBytes("UTF-8"));
        whitespaceScript.toFile().setExecutable(true);
        Path largeScript = tempDir.resolve("large-output.sh");
        Files.write(largeScript, "#!/bin/sh\nhead -c 2048 /dev/zero | tr '\\000' x\nprintf '\\n'\n".getBytes("UTF-8"));
        largeScript.toFile().setExecutable(true);

        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("whitespace", new ToolConfig("whitespace", "Whitespace", "test", "./whitespace-output.sh", "text",
                Collections.<String, ToolArgumentConfig>emptyMap()));
        tools.put("large", new ToolConfig("large", "Large", "test", "./large-output.sh", "text",
                Collections.<String, ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tempDir,
                tools, null, null, null, "", "", null, null, 1, "ignore", "", false,
                new ProcessOutputConfig(1024, 4096));
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("whitespace", map("type", "tool", "call", "#{whitespace()}")),
                new TemplateAction("large", map("type", "tool", "call", "#{large()}")));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir, config)))
                .execute("invoke", new StageTemplate("T", tempDir, actions), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(Arrays.asList(ResultStatus.PASS, ResultStatus.PASS),
                Arrays.asList(results.get(0).status(), results.get(1).status()));
        assertEquals("abc", context.resolve("ACTIONS.whitespace.output.result"));
        assertEquals(2048, String.valueOf(context.resolve("ACTIONS.large.output.result")).length());
        assertEquals(Boolean.TRUE, context.resolve("ACTIONS.large.output.attempts[0].stdoutTruncated"));
        assertEquals(Boolean.FALSE, context.resolve("ACTIONS.large.output.attempts[0].stdoutArtifactTruncated"));
        assertFalse(Files.exists(caseDir.resolve("whitespace.txt")));
        assertFalse(Files.exists(caseDir.resolve("large.txt")));
    }

    @Test void toolActionPublishesBuiltInResultWithoutPersistingAFile() throws Exception {
        Path caseDir = tempDir.resolve("builtin-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("prepare", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        TemplateAction action = new TemplateAction("normalize", map("type", "tool", "call", "#{upper('abc')}",
                "assert", "${output.result} == 'ABC'"));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("prepare", new StageTemplate("T", tempDir, Collections.singletonList(action)), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        assertEquals("ABC", context.resolve("ACTIONS.normalize.output.result"));
        assertEquals("builtin", context.resolve("ACTIONS.normalize.output.attempts[0].type"));
        assertEquals("upper", context.resolve("ACTIONS.normalize.output.attempts[0].name"));
        assertEquals("upper", context.resolve("ACTIONS.normalize.output.evidence.tool.invocations[0].name"));
        assertFalse(Files.exists(caseDir.resolve("normalized.txt")));
        assertNull(context.resolve("ACTIONS.normalize.TOOL"));
    }

    @Test void templateAndToolArgvShareTheRunSequenceProvider() throws Exception {
        Path caseDir = tempDir.resolve("shared-sequence");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("prepare", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("sample", new ToolConfig("sample", "sample", "", "Sample", "Sample",
                Arrays.asList("fake", "#{seq.next()}"), Collections.<String>emptyList(), "text",
                Collections.<String, ToolArgumentConfig>emptyMap(), null));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir,
                tools, null, null);
        CapturingRunner command = new CapturingRunner();
        DefaultBuiltInProvider builtIns = new DefaultBuiltInProvider(new SequenceService());
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("templateSequence", map("type", "assign", "name", "firstSequence", "expression", "#{seq.next()}")),
                new TemplateAction("toolSequence", map("type", "tool", "call", "#{sample()}")));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(
                new ToolInvoker(tempDir, config, command), builtIns)).execute("prepare",
                new StageTemplate("T", tempDir, actions), context, new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(Arrays.asList(ResultStatus.PASS, ResultStatus.PASS),
                Arrays.asList(results.get(0).status(), results.get(1).status()));
        assertEquals(Long.valueOf(1), context.resolve("EXEC.VARS.firstSequence"));
        assertEquals("2", command.argv.get(1));
    }

    @Test void currentActionRootlessReferenceFailsBeforeActionPublication() throws Exception {
        Path caseDir = tempDir.resolve("self-reference-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        TemplateAction action = new TemplateAction("show", map("type","log",
                "message","${show.output.result}"));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertTrue(results.get(0).message().contains("Unknown Context variable"));
    }

    @Test void toolEvidenceRunsAfterPrimaryResultAndIsAvailableToAssertion() throws Exception {
        Path caseDir = tempDir.resolve("evidence-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("sample", new ToolConfig("sample","Sample","test","sample","text",Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        TemplateAction action = new TemplateAction("call", map("type","tool", "call","#{sample()}",
                "assert","${output.result} == 'ok'", "evidence", map("snapshot", map("call","#{capture(value=${output.result})}"))));
        assertFalse(action.evidence().isEmpty());
        CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"));
        CaptureBuiltIns builtIns = new CaptureBuiltIns();
        List<ValidationResult> results = new StageTemplateRunner(
                new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,new FixedRunner(0,"ok")), builtIns))
                .execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,log);

        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals("ok", builtIns.last.get("value"));
        assertEquals("ok", context.resolve("ACTIONS.call.output.result"));
        assertEquals("sample", context.resolve("ACTIONS.call.output.evidence.tool.invocations[0].name"));
        assertEquals("tool", context.resolve("ACTIONS.call.output.evidence.tool.invocations[0].type"));
        assertEquals("ok", context.resolve("ACTIONS.call.output.evidence.collectors.snapshot.result"));
        assertEquals("ok", context.resolve("ACTIONS.call.output.attempts[0].evidence.collectors.snapshot.result"));
        assertEquals("PASS", context.resolve("ACTIONS.call.output.attempts[0].evidence.collectors.snapshot.status"));
        String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
        assertTrue(caseLog.contains("EVIDENCE call attempt=1 collector=snapshot"));
        assertTrue(caseLog.contains("status: PASS"));
    }

    @Test void actionBoundaryDoesNotRelogInternalSshFailureOrExposeEnvironmentIdentityPath() throws Exception {
        Path caseDir = tempDir.resolve("internal-secret-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
        String privateKeyPath = tempDir.resolve("environment-private-key").toString();
        RuntimeException adapterFailure = new RuntimeException("SSH adapter could not read " + privateKeyPath);
        CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"));
        BuiltInProvider resourceBoundary = new BuiltInProvider() {
            @Override public Set<String> names() { return Collections.singleton("fail"); }
            @Override public Object invoke(String name, Map<String, Object> arguments) {
                InternalExceptionLogger.logIfInternal(log, "ssh.execute", adapterFailure,
                        Collections.singletonList(privateKeyPath));
                throw adapterFailure;
            }
        };
        TemplateAction action = new TemplateAction("invokeFail", map("type", "tool", "call", "#{fail()}"));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null, resourceBoundary))
                .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
        log.close();

        assertEquals(ResultStatus.ERROR, results.get(0).status());
        String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
        assertFalse(caseLog.contains(privateKeyPath));
        assertTrue(caseLog.contains("[REDACTED_SECRET]"));
        assertEquals(1, occurrences(caseLog, "[ATT INTERNAL ERROR]"));
        assertTrue(caseLog.contains("phase: ssh.execute"));
    }

    @Test void toolEvidenceRepeatsForEachPrimaryAssertionRetry() throws Exception {
        Path caseDir = tempDir.resolve("evidence-retry");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("sample", new ToolConfig("sample","Sample","test","sample","text",Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        Map<String,Object> retry = map("maxAttempts",3,"intervalMs",0,"retryOn",Arrays.asList("ASSERTION"));
        TemplateAction action = new TemplateAction("call", map("type","tool", "call","#{sample()}",
                "assert","${output.result} == 'ok'", "retry",retry,
                "evidence", map("snapshot", map("call","#{capture(value=${output.result})}"))));
        CaptureBuiltIns builtIns = new CaptureBuiltIns();
        SequencedRunner runner = new SequencedRunner(false);
        List<ValidationResult> results = new StageTemplateRunner(
                new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,runner), builtIns))
                .execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(2, runner.calls);
        assertEquals(2, builtIns.calls);
        assertEquals("first", context.resolve("ACTIONS.call.output.attempts[0].evidence.collectors.snapshot.result"));
        assertEquals("ok", context.resolve("ACTIONS.call.output.attempts[1].evidence.collectors.snapshot.result"));
        assertEquals("ok", context.resolve("ACTIONS.call.output.evidence.tool.invocations[0].output"));
    }

    @Test void evidenceFailureContinuePreservesPrimaryAssertionAndStopSkipsIt() throws Exception {
        Path caseDir = tempDir.resolve("evidence-failure");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("sample", new ToolConfig("sample","Sample","test","sample","text",Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10000,tempDir,tools,null,null);
        for (String mode : Arrays.asList("continue", "stop")) {
            Path directory = caseDir.resolve(mode); Files.createDirectories(directory);
            CaseRuntimeContext context = new CaseRuntimeContext(test,directory,"R-" + mode,tempDir,directory.resolve("case.log"));
            context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
            TemplateAction action = new TemplateAction("call", map("type","tool", "call","#{sample()}",
                    "assert","${output.result} == 'ok'", "evidence", map("broken", map("call","#{fail()}","onFailure",mode))));
            List<ValidationResult> results = new StageTemplateRunner(
                    new UnifiedTemplateEngine(new ToolInvoker(tempDir,config,new FixedRunner(0,"ok")), new FailingBuiltIns()))
                    .execute("invoke",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,new CaseExecutionLog(directory.resolve("case.log")));
            assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status());
            assertEquals("ok", context.resolve("ACTIONS.call.output.result"));
            assertEquals("ERROR", context.resolve("ACTIONS.call.output.attempts[0].evidence.collectors.broken.status"));
            if ("stop".equals(mode)) assertNull(context.resolve("ACTIONS.call.output.assertion"));
        }
    }

    @Test void actionTextAndTypedLogValuesSupportInlineBuiltIns() throws Exception {
        Map<String, Object> data = new LinkedHashMap<String, Object>(); data.put("SrcRefNo", "ABC123");
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(), data, Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir, "R", tempDir, tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("note", map("type", "log", "message", "#{lower(${CASE.SrcRefNo})}",
                        "value", map("size", "#{length(${CASE.SrcRefNo})}"), "format", "json",
                        "description", "Logged #{upper(${CASE.SrcRefNo})}")),
                new TemplateAction("check", map("type", "assert", "assert", "#{length(value=${CASE.SrcRefNo})} <= 35",
                        "description", "#{concat('Check ', ${CASE.caseId})}", "expected", "#{upper('ok')}",
                        "actual", "#{lower('OK')}")));

        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("verify", new StageTemplate("T", tempDir, actions), context,
                        new CaseExecutionLog(tempDir.resolve("case.log")));

        assertEquals(Arrays.asList(ResultStatus.PASS, ResultStatus.PASS),
                Arrays.asList(results.get(0).status(), results.get(1).status()));
        assertEquals("abc123", context.resolve("ACTIONS.note.message"));
        String logged = String.valueOf(context.resolve("ACTIONS.note.output.result"));
        assertTrue(logged.contains("abc123"));
        assertTrue(logged.contains("\"size\""));
        assertTrue(logged.contains("6"));
        assertEquals("Logged ABC123", context.resolve("ACTIONS.note.description"));
        assertEquals("6 <= 35", ((Map<?, ?>) context.resolve("ACTIONS.check.output.assertion")).get("rendered"));
        assertEquals("Check g.TC1", results.get(1).description());
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

    @Test void logActionPreservesMultilineContextAsRawCaseLogText() throws Exception {
        Path caseDir = tempDir.resolve("multiline-log"); Files.createDirectories(caseDir);
        Map<String,Object> data = new LinkedHashMap<String,Object>(); data.put("message", "first\nsecond\r\nthird");
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),data,Collections.emptyMap(),null);
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        TemplateAction action = new TemplateAction("note", map("type","log","message","${CASE.message}"));
        CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("verify",new StageTemplate("T",tempDir,Collections.singletonList(action)),context,log);

        assertEquals(ResultStatus.PASS, results.get(0).status());
        String text = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
        assertTrue(text.contains("[LOG note INFO]\nfirst\nsecond\nthird\n\n"));
        assertFalse(text.contains("first\\nsecond"));
    }

    @Test void toolOutputDoesNotCreateImplicitFiles() throws Exception {
        Path caseDir = tempDir.resolve("tool-output"); Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("sample", new ToolConfig("sample", "Sample", "test", "sample", "text",
                Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("builtin", map("type", "tool", "call", "#{upper('abc')}")),
                new TemplateAction("process", map("type", "tool", "call", "#{sample()}")));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(
                new ToolInvoker(tempDir, config, new FixedRunner(0, "line1\nline2\n"))))
                .execute("invoke", new StageTemplate("T", tempDir, actions), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(Arrays.asList(ResultStatus.PASS, ResultStatus.PASS),
                Arrays.asList(results.get(0).status(), results.get(1).status()));
        assertEquals("ABC", context.resolve("ACTIONS.builtin.output.result"));
        assertEquals("line1\\nline2", context.resolve("ACTIONS.process.output.result"));
        assertFalse(Files.exists(caseDir.resolve("console")));
        assertFalse(Files.exists(caseDir.resolve("process-output")));
    }

    @Test void retriesFailedAssertionAndRetriesTimeoutOnlyWhenConfigured() throws Exception {
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                new LinkedHashMap<String,Object>(), Collections.emptyMap(), null);
        Path caseOne = tempDir.resolve("case1");
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseOne, "R", tempDir, caseOne.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("sample", new ToolConfig("sample", "Sample", "test", "sample", "text",
                Collections.<String,ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
        SequencedRunner runner = new SequencedRunner(false);
        Map<String, Object> retry = map("maxAttempts", 3, "intervalMs", 0, "retryOn", Arrays.asList("ASSERTION"));
        TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{sample()}",
                "assert", "${output.result} == 'ok'", "retry", retry));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir, config, runner)))
                .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context,
                        new CaseExecutionLog(caseOne.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(2, runner.calls);
        assertEquals(2, ((List<?>) context.resolve("ACTIONS.call.output.attempts")).size());
        assertEquals("ok", context.resolve("ACTIONS.call.output.result"));
        try (java.util.stream.Stream<Path> paths = Files.walk(tempDir)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("attempt-")));
        }

        Path caseTwo = tempDir.resolve("case2");
        CaseRuntimeContext timeoutContext = new CaseRuntimeContext(test, caseTwo, "R2", tempDir, caseTwo.resolve("case.log"));
        timeoutContext.beginStage(new StageCaseData("invoke", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        SequencedRunner timeout = new SequencedRunner(true);
        TemplateAction timeoutAction = new TemplateAction("call", map("type", "tool", "call", "#{sample()}"));
        List<ValidationResult> timeoutResults = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir, config, timeout)))
                .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(timeoutAction)), timeoutContext,
                        new CaseExecutionLog(caseTwo.resolve("case.log")));
        assertEquals(ResultStatus.ERROR, timeoutResults.get(0).status());
        assertEquals(1, timeout.calls);
        String timeoutLog = new String(Files.readAllBytes(caseTwo.resolve("case.log")), "UTF-8");
        assertTrue(timeoutLog.contains("Tool timed out: sample"));
        assertEquals(1, occurrences(timeoutLog, "Tool timed out: sample"));
        assertEquals(1, occurrences(timeoutLog, "[ACTION call ERROR]"));
        assertFalse(timeoutLog.contains("TOOL:"));

        Path caseThree = tempDir.resolve("case3");
        CaseRuntimeContext retryTimeoutContext = new CaseRuntimeContext(test, caseThree, "R3", tempDir, caseThree.resolve("case.log"));
        retryTimeoutContext.beginStage(new StageCaseData("invoke", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        SequencedRunner retriedTimeout = new SequencedRunner(true);
        TemplateAction retryTimeoutAction = new TemplateAction("call", map("type", "tool", "call", "#{sample()}",
                "retry", map("maxAttempts", 3, "intervalMs", 0, "retryOn", Arrays.asList("TIMEOUT"))));
        List<ValidationResult> retryTimeoutResults = new StageTemplateRunner(new UnifiedTemplateEngine(
                new ToolInvoker(tempDir, config, retriedTimeout))).execute("invoke",
                new StageTemplate("T", tempDir, Collections.singletonList(retryTimeoutAction)), retryTimeoutContext,
                new CaseExecutionLog(caseThree.resolve("case.log")));
        assertEquals(ResultStatus.ERROR, retryTimeoutResults.get(0).status());
        assertEquals(3, retriedTimeout.calls);
    }

    @Test void logActionPreservesUtf8TypedValues() throws Exception {
        Path caseDir = tempDir.resolve("utf8-log"); Files.createDirectories(caseDir);
        String value = "第一行\n<Status>&OK</Status>\n";
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        TemplateAction action = new TemplateAction("note", map("type", "log", "value", value, "format", "text"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(value, context.resolve("ACTIONS.note.output.result"));
        assertTrue(new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8").contains(value));
    }

        @Test void rendersGlobToNativeTypedDocuments() throws Exception {
        Path template = tempDir.resolve("typed-documents");
        Files.createDirectories(template.resolve("data"));
        Files.write(template.resolve("data/a.json"), ("{\"value\":1}").getBytes("UTF-8"));
        Files.write(template.resolve("value.yaml"), ("name: ${CASE.caseId}\n").getBytes("UTF-8"));
        Files.write(template.resolve("value.xml"), "<Result>OK</Result>".getBytes("UTF-8"));
        Files.write(template.resolve("value.txt"), "hello ".getBytes("UTF-8"));
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        Path caseDir = tempDir.resolve("typed-document-case");
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("render", "T", Collections.<String,Object>emptyMap()), "T", template);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("json", map("type", "render", "payload", "data/*.json", "templateFormat", "json")),
                new TemplateAction("yaml", map("type", "render", "payload", "value.yaml", "templateFormat", "yaml")),
                new TemplateAction("xml", map("type", "render", "payload", "value.xml", "templateFormat", "xml")),
                new TemplateAction("text", map("type", "render", "payload", "value.txt", "templateFormat", "text")));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("render", new StageTemplate("T", template, actions), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(Arrays.asList(ResultStatus.PASS, ResultStatus.PASS, ResultStatus.PASS, ResultStatus.PASS),
                Arrays.asList(results.get(0).status(), results.get(1).status(), results.get(2).status(), results.get(3).status()));
        @SuppressWarnings("unchecked")
        Map<String, DocumentValue> json = (Map<String, DocumentValue>) context.resolve("ACTIONS.json.output.result");
        assertEquals("{\"value\":1}", json.get("data/a.json").text());
        assertEquals("json", json.get("data/a.json").format());
        assertEquals("name: g.TC1\n", ((DocumentValue) context.resolve("ACTIONS.yaml.output.result")).text());
        assertEquals("xml", ((DocumentValue) context.resolve("ACTIONS.xml.output.result")).format());
        assertEquals("hello ", ((DocumentValue) context.resolve("ACTIONS.text.output.result")).text());
        assertFalse(Files.exists(caseDir.resolve("value.txt")));
    }
    @Test void renderDoesNotOverwriteExistingOutputWhenNoPersistenceIsConfigured() throws Exception {
        Files.write(tempDir.resolve("payload.txt"), "new".getBytes("UTF-8"));
        Files.createDirectories(tempDir.resolve("output"));
        Files.write(tempDir.resolve("output/payload.txt"), "old".getBytes("UTF-8"));
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir.resolve("output"), "R", tempDir,
                tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        TemplateAction action = new TemplateAction("render", map("type", "render", "payload", "payload.txt",
                "templateFormat", "text"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context,
                        new CaseExecutionLog(tempDir.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals("new", ((DocumentValue) context.resolve("ACTIONS.render.output.result")).text());
        assertEquals("old", new String(Files.readAllBytes(tempDir.resolve("output/payload.txt")), "UTF-8"));
    }

    @Test void renderGlobPublishesDocumentsWithoutCreatingPathArtifacts() throws Exception {
        Path template = tempDir.resolve("pattern-template");
        Files.createDirectories(template.resolve("requests"));
        Files.write(template.resolve("requests/payment.xml"), "<payment/>".getBytes("UTF-8"));
        Files.write(template.resolve("requests/refund.xml"), "<refund/>".getBytes("UTF-8"));
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        Path output = tempDir.resolve("pattern-output");
        CaseRuntimeContext context = new CaseRuntimeContext(test, output, "R", tempDir, output.resolve("case.log"));
        context.beginStage(new StageCaseData("render", "T", Collections.<String,Object>emptyMap()), "T", template);
        TemplateAction action = new TemplateAction("render", map("type", "render", "payload", "requests/*.xml",
                "templateFormat", "text"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("render", new StageTemplate("T", template, Collections.singletonList(action)), context,
                        new CaseExecutionLog(output.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        @SuppressWarnings("unchecked")
        Map<String, DocumentValue> documents = (Map<String, DocumentValue>) context.resolve("ACTIONS.render.output.result");
        assertEquals("<payment/>", documents.get("requests/payment.xml").text());
        assertEquals("<refund/>", documents.get("requests/refund.xml").text());
        assertFalse(Files.exists(output.resolve("artifacts")));
        assertFalse(Files.exists(output.resolve("console")));
    }

    @Test void renderEvaluatesContextExpressionsAndKeepsTheDocumentInContext() throws Exception {
        Path template = tempDir.resolve("expression-path-template");
        Files.createDirectories(template.resolve("requests"));
        Files.write(template.resolve("requests/payment.xml"),
                "<payment for=\"${EXEC.INPUT.renderDirectory}\"/>".getBytes("UTF-8"));
        Path output = tempDir.resolve("expression-path-output");
        Map<String, Object> input = new LinkedHashMap<String, Object>(); input.put("renderDirectory", "chosen");
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(), input, Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, output, "R", tempDir, output.resolve("case.log"));
        context.beginStage(new StageCaseData("render", "T", Collections.<String,Object>emptyMap()), "T", template);
        TemplateAction action = new TemplateAction("render", map("type", "render", "payload", "requests/*.xml",
                "templateFormat", "text"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("render", new StageTemplate("T", template, Collections.singletonList(action)), context,
                        new CaseExecutionLog(output.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        assertEquals("<payment for=\"chosen\"/>",
                ((DocumentValue) context.resolve("ACTIONS.render.output.result")).text());
        assertFalse(Files.exists(output.resolve("chosen/payment.txt")));
    }

    @Test void toolExitCodeIsEvidenceAndAssertionControlsResult() throws Exception {
        TestCase test = new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
        Path caseDir = tempDir.resolve("exit-case");
        CaseRuntimeContext context = new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("sample", new ToolConfig("sample","Sample","test","sample","text",Collections.<String,ToolArgumentConfig>emptyMap()));
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
        TemplateAction action = new TemplateAction("render",map("type","render","description","Render ${CASE.caseId}; status=${output.status}","payload","../*.txt","templateFormat","text","onFailure","continue"));
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
        tools.put("seq", new ToolConfig("seq","Sequence","test","seq","text",Collections.<String,ToolArgumentConfig>emptyMap()));
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
                new TemplateAction("failed",map("type","assign","name","missingValue","expression","${CASE.missing}","onFailure","continue")),
                new TemplateAction("optional",map("type","assign","name","missingValueOptional","expression","${CASE.missing?}")));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("prepare",new StageTemplate("T",tempDir,actions),context,new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(ResultStatus.ERROR, results.get(1).status());
        assertEquals(ResultStatus.ERROR, results.get(2).status());
        assertEquals(ResultStatus.PASS, results.get(3).status());
        assertEquals("FIRST", context.resolve("CASE.VARS.txnSeq"));
        assertNull(context.resolve("CASE.VARS.missingValue"));
        assertNull(context.resolve("CASE.VARS.missingValueOptional"));
    }
    private static final class CaptureBuiltIns implements BuiltInProvider {
        int calls;
        Map<String,Object> last;
        @Override public Set<String> names() { return new LinkedHashSet<String>(Collections.singletonList("capture")); }
        @Override public Object invoke(String name, Map<String,Object> arguments) { calls++; last = new LinkedHashMap<String,Object>(arguments); return arguments.get("value"); }
    }
    private static final class FailingBuiltIns implements BuiltInProvider {
        @Override public Set<String> names() { return new LinkedHashSet<String>(Collections.singletonList("fail")); }
        @Override public Object invoke(String name, Map<String,Object> arguments) { throw new IllegalStateException("collector failed"); }
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
    private static final class CapturingRunner extends CommandRunner {
        List<String> argv;
        @Override public CommandResult run(List<String> argv, java.time.Duration duration, Path workingDirectory, Map<String,String> environment) {
            this.argv = new ArrayList<String>(argv);
            return new CommandResult(0, "ok", "", false);
        }
    }
    private int occurrences(String text,String value){int count=0,index=0;while((index=text.indexOf(value,index))>=0){count++;index+=value.length();}return count;}
    private Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
