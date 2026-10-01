/* Author: Jeffrey + ChatGPT */
package att.template;

import att.core.*;
import att.config.*;
import att.exec.*;
import att.flow.FlowRegistry;
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
        assertEquals("actions.invoke.call", results.get(0).diagnostic().field());
        att.validation.SourceLocation source = results.get(0).diagnostic().source();
        assertNotNull(source);
        assertEquals(8, source.line());
        assertEquals(templateText.split("\\n")[7].indexOf(reference) + 1, source.column());
        assertTrue(source.excerpt().contains("call: >-"));
        assertTrue(source.excerpt().contains(reference));
    }

    @Test void toolAssertionContextFailurePointsToAssertFieldAndExpression() throws Exception {
        StageTemplateLoader.clearForTests();
        att.TestSchemas.install(tempDir);
        Path templateDir = tempDir.resolve("templates/tool-assert-source");
        Files.createDirectories(templateDir);
        String templateText = "schemaVersion: att-template/v3.3\nname: Tool assert source\ndescription: Assertion source\n"
                + "actions:\n  call:\n    type: tool\n    call: \"#{upper('ok')}\"\n    assert: >-\n"
                + "      ${output.result.missing} == 'OK'\n";
        Path descriptor = templateDir.resolve("template.yaml");
        Files.write(descriptor, templateText.getBytes("UTF-8"));
        StageTemplate template = new StageTemplateLoader(tempDir, Paths.get("templates")).load("tool-assert-source");
        Path caseDir = tempDir.resolve("tool-assert-source-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "Tool assert source", Collections.<String, Object>emptyMap()),
                "Tool assert source", templateDir);
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute(
                "invoke", template, context, new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertEquals("ATT-CTX-001", results.get(0).diagnostic().code());
        assertEquals("actions.call.assert", results.get(0).diagnostic().field());
        att.validation.SourceLocation source = results.get(0).diagnostic().source();
        assertNotNull(source);
        assertEquals(9, source.line());
        assertEquals(templateText.split("\\n")[8].indexOf("${output.result.missing}") + 1, source.column());
        assertTrue(source.excerpt().contains("assert: >-"));
        assertTrue(source.excerpt().contains("output.result.missing"));
        assertTrue(results.get(0).diagnostic().detail().contains("requestedPath: output.result.missing"));
    }

    @Test void toolExpectedContextFailurePointsToExpectedFieldAndExpression() throws Exception {
        assertToolReportFieldFailure("expected");
    }

    @Test void toolActualContextFailurePointsToActualFieldAndExpression() throws Exception {
        assertToolReportFieldFailure("actual");
    }

    private void assertToolReportFieldFailure(String field) throws Exception {
        StageTemplateLoader.clearForTests();
        att.TestSchemas.install(tempDir);
        String templateId = "tool-" + field + "-source";
        Path templateDir = tempDir.resolve("templates/" + templateId);
        Files.createDirectories(templateDir);
        String reference = "${output.result.missing}";
        String templateText = "schemaVersion: att-template/v3.3\nname: Tool report source\ndescription: Report source\n"
                + "actions:\n  call:\n    type: tool\n    call: \"#{upper('ok')}\"\n    assert: \"true\"\n"
                + "    " + field + ": >-\n      " + reference + "\n";
        Path descriptor = templateDir.resolve("template.yaml");
        Files.write(descriptor, templateText.getBytes("UTF-8"));
        StageTemplate template = new StageTemplateLoader(tempDir, Paths.get("templates")).load(templateId);
        Path caseDir = tempDir.resolve(templateId + "-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "Tool report source", Collections.<String, Object>emptyMap()),
                "Tool report source", templateDir);
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute(
                "invoke", template, context, new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(1, results.size());
        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertEquals("ATT-CTX-001", results.get(0).diagnostic().code());
        assertEquals("actions.call." + field, results.get(0).diagnostic().field());
        assertEquals(descriptor.toRealPath().toString(), results.get(0).diagnostic().file());
        att.validation.SourceLocation source = results.get(0).diagnostic().source();
        assertNotNull(source);
        assertEquals(10, source.line());
        assertEquals(templateText.split("\\n")[9].indexOf(reference) + 1, source.column());
        assertTrue(source.excerpt().contains(field + ": >-"));
        assertTrue(source.excerpt().contains(reference));
        assertTrue(results.get(0).diagnostic().detail().contains("requestedPath: output.result.missing"));
        assertEquals(Boolean.TRUE, context.resolve("ACTIONS.call.output.assertion.passed"));
    }

    @Test void evidenceCollectorContextFailurePointsToCollectorCallField() throws Exception {
        StageTemplateLoader.clearForTests();
        att.TestSchemas.install(tempDir);
        Path templateDir = tempDir.resolve("templates/collector-source");
        Files.createDirectories(templateDir);
        String templateText = "schemaVersion: att-template/v3.3\nname: Collector source\ndescription: Collector source\n"
                + "actions:\n  call:\n    type: tool\n    call: \"#{upper('ok')}\"\n    evidence:\n"
                + "      broken:\n        call: >-\n          #{capture(value=${output.result.missing})}\n        onFailure: stop\n";
        Path descriptor = templateDir.resolve("template.yaml");
        Files.write(descriptor, templateText.getBytes("UTF-8"));
        StageTemplate template = new StageTemplateLoader(tempDir, Paths.get("templates")).load("collector-source");
        Path caseDir = tempDir.resolve("collector-source-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "Collector source", Collections.<String, Object>emptyMap()),
                "Collector source", templateDir);
        List<ValidationResult> results = new StageTemplateRunner(
                new UnifiedTemplateEngine(null, new CaptureBuiltIns())).execute(
                        "invoke", template, context, new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertEquals("actions.call.evidence.broken.call", results.get(0).diagnostic().field());
        att.validation.SourceLocation source = results.get(0).diagnostic().source();
        assertNotNull(source);
        assertEquals(11, source.line());
        assertEquals(templateText.split("\\n")[10].indexOf("${output.result.missing}") + 1, source.column());
        assertTrue(source.excerpt().contains("call: >-"));
        assertTrue(source.excerpt().contains("output.result.missing"));
        assertTrue(results.get(0).diagnostic().detail().contains("requestedPath: output.result.missing"));
    }

    @Test void flowNestedToolDiagnosticKeepsFlowSourceAndNestedActionField() throws Exception {
        StageTemplateLoader.clearForTests();
        att.TestSchemas.install(tempDir);
        Path flowDir = tempDir.resolve("templates/flows/diagnostic/source");
        Files.createDirectories(flowDir);
        String flowText = "schemaVersion: att-flow/v3.3\nid: diagnostic.source.v1\nname: Diagnostic source\n"
                + "description: Flow diagnostic source\nactions:\n  call:\n    type: tool\n    call: \"#{upper('ok')}\"\n    assert: >-\n"
                + "      ${output.result.missing} == 'OK'\n";
        Files.write(flowDir.resolve("flow.yaml"), flowText.getBytes("UTF-8"));
        FlowRegistry registry = new FlowRegistry(tempDir, Paths.get("templates"), false);
        TemplateAction invoke = new TemplateAction("nested", map("type", "flow", "use", "diagnostic.source.v1"));
        Path caseDir = tempDir.resolve("flow-source-case");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "Wrapper", Collections.<String, Object>emptyMap()), "Wrapper", tempDir);
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null), registry).execute(
                "invoke", new StageTemplate("Wrapper", tempDir, Collections.singletonList(invoke)), context,
                new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.ERROR, results.get(0).status());
        assertNotNull(results.get(0).diagnostic());
        assertEquals("actions.call.assert", results.get(0).diagnostic().field());
        assertEquals(flowDir.resolve("flow.yaml").toRealPath().toString(), results.get(0).diagnostic().file());
        assertEquals(10, results.get(0).diagnostic().source().line());
        assertTrue(results.get(0).diagnostic().source().excerpt().contains("output.result.missing"));
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
                new ToolInvoker(tempDir, config, new FixedRunner(0, "{\"ok\":true}"))))
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
        String largeResult = String.valueOf(context.resolve("ACTIONS.large.output.result"));
        assertTrue(largeResult.length() > 1024 && largeResult.length() < 2048);
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
                "assert","${output.evidence.collectors.snapshot.result} == 'ok' and ${output.evidence.collectors.snapshot.status} == 'PASS'",
                "evidence", map("snapshot", map("call","#{capture(value=${output.result})}"))));
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
        assertEquals("ok", context.resolve("EXEC.ACTIONS.call.output.evidence.collectors.snapshot.result"));
        assertEquals("PASS", context.resolve("EXEC.ACTIONS.call.output.evidence.collectors.snapshot.status"));
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
        assertEquals("first", context.resolve("EXEC.ACTIONS.call.output.attempts[0].evidence.collectors.snapshot.result"));
        assertEquals("PASS", context.resolve("EXEC.ACTIONS.call.output.attempts[1].evidence.collectors.snapshot.status"));
        assertEquals("ok", context.resolve("EXEC.ACTIONS.call.output.evidence.collectors.snapshot.result"));
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
            assertFalse(String.valueOf(context.resolve("ACTIONS.call.output.evidence.collectors.broken.error.message")).trim().isEmpty());
            if ("stop".equals(mode)) {
                assertNull(context.resolve("ACTIONS.call.output.assertion"));
                assertTrue(results.get(0).message().contains("collector failed"));
            }
        }
    }

    @Test void callBackedHttpCollectorPreservesNativeFailureMessageForContinueAndStop() throws Exception {
        StageTemplateLoader.clearForTests();
        att.TestSchemas.install(tempDir);
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("snapshot", new ToolConfig("snapshot", "snapshot", "", "Snapshot", "HTTP collector",
                Collections.<String>emptyList(), "#{http.missing.get()}", Collections.<String>emptyList(),
                "", Collections.<String, ToolArgumentConfig>emptyMap(), null, null));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir,
                tools, null, null);
        String message = "Unknown HTTP helper: missing";
        try (HttpHelperExecutor http = new HttpHelperExecutor(tempDir, config)) {
            for (String mode : Arrays.asList("continue", "stop")) {
                Path caseDir = tempDir.resolve("http-collector-failure-" + mode);
                Files.createDirectories(caseDir);
                Path descriptor = caseDir.resolve("template.yaml");
                String templateText = "schemaVersion: att-template/v3.3\nname: T\ndescription: HTTP collector source\n"
                        + "actions:\n  call:\n    type: tool\n    call: \"#{upper('ok')}\"\n    assert: \"${output.result} == 'OK'\"\n"
                        + "    evidence:\n      snapshot:\n        call: \"#{snapshot()}\"\n        onFailure: " + mode + "\n";
                Files.write(descriptor, templateText.getBytes("UTF-8"));
                StageTemplate template = new StageTemplateLoader(tempDir, caseDir).load("T");
                TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
                CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R-" + mode,
                        tempDir, caseDir.resolve("case.log"));
                context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()),
                        "T", tempDir);
                UnifiedTemplateEngine engine = new UnifiedTemplateEngine(new ToolInvoker(tempDir, config),
                        null, null, http, new DefaultBuiltInProvider());
                List<ValidationResult> results;
                try (CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                    results = new StageTemplateRunner(engine).execute("invoke",
                            template, context, log);
                }

                assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR,
                        results.get(0).status(), results.get(0).message());
                assertEquals("OK", context.resolve("ACTIONS.call.output.result"));
                String collector = "ACTIONS.call.output.evidence.collectors.snapshot";
                assertEquals("ERROR", context.resolve(collector + ".status"));
                assertEquals(message, context.resolve(collector + ".error.message"));
                assertEquals(message, context.resolve(collector + ".operationDiagnostic.message"));
                assertEquals("actions.call.evidence.snapshot.call", context.resolve(collector + ".diagnostic.field"));
                assertEquals(message, context.resolve(collector + ".diagnostic.detail"));
                assertEquals(message, context.resolve(collector + ".evidence.http.invocations[0].error.message"));
                assertEquals("HTTP_CONFIG", context.resolve(collector + ".evidence.http.invocations[0].error.type"));
                assertEquals(message, context.resolve(
                        "ACTIONS.call.output.attempts[0].evidence.collectors.snapshot.error.message"));
                if ("stop".equals(mode)) {
                    assertNull(context.resolve("ACTIONS.call.output.assertion"));
                    assertTrue(results.get(0).message().contains(message), results.get(0).message());
                    assertEquals("actions.call.evidence.snapshot.call", results.get(0).diagnostic().field());
                    assertTrue(results.get(0).diagnostic().detail().contains(message));
                    assertEquals(descriptor.toRealPath().toString(), results.get(0).diagnostic().file());
                    assertEquals(11, results.get(0).diagnostic().source().line());
                } else {
                    assertNotNull(context.resolve("ACTIONS.call.output.assertion"));
                }
                String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
                assertTrue(caseLog.contains("EVIDENCE call attempt=1 collector=snapshot"));
                assertTrue(caseLog.contains(message));
                assertFalse(caseLog.contains("Evidence collector operation failed with status ERROR"));
            }
        }
    }

    @Test void thrownCollectorEvidenceOmitsPrivateInputsAndBoundsPublicFailureDetails() throws Exception {
        String secret = "collector-secret-literal-791";
        String large = String.join("", Collections.nCopies(20000, "x"));
        for (String failure : Arrays.asList("block", "fail")) {
            for (String mode : Arrays.asList("continue", "stop")) {
                Path caseDir = tempDir.resolve("private-collector-" + failure + "-" + mode);
                Files.createDirectories(caseDir);
                TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
                CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
                context.beginStage(new StageCaseData("invoke", "T", map("password", secret, "payload", large)), "T", tempDir);
                TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{upper('ok')}",
                        "assert", "${output.result} == 'OK'",
                        "evidence", map("private", map("call", "#{" + failure
                                + "(password=${EXEC.INPUT.password}, payload=${EXEC.INPUT.payload})}",
                                "timeoutMs", 50, "onFailure", mode))));
                List<ValidationResult> results;
                try (CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                    results = new StageTemplateRunner(new PrivateCollectorEngine())
                            .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
                }
                assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status());
                String collector = "ACTIONS.call.output.evidence.collectors.private";
                String evidence = collector + ".evidence.tool.invocations[0]";
                assertEquals("TIMEOUT", context.resolve(collector + ".status"));
                assertEquals("TIMEOUT", context.resolve(collector + ".error.category"));
                assertEquals(failure, context.resolve(evidence + ".name"));
                assertEquals(50L, ((Number) context.resolve(evidence + ".timeoutMs")).longValue());
                assertEquals(Boolean.TRUE, context.resolve(evidence + ".inputOmitted"));
                assertNull(context.resolve(evidence + ".input"));
                assertNull(context.resolve(evidence + ".payload"));
                assertNull(context.resolve(evidence + ".argv"));
                if ("fail".equals(failure)) {
                    assertEquals("app", context.resolve(evidence + ".sshHelper"));
                    assertEquals(Boolean.TRUE, context.resolve(evidence + ".stderrTruncated"));
                    assertEquals(Boolean.TRUE, context.resolve(evidence + ".evidenceTruncated"));
                    assertTrue(String.valueOf(context.resolve(evidence + ".stderr")).length() <= CollectorExceptionEvidence.TEXT_LIMIT);
                    assertTrue(String.valueOf(context.resolve(evidence + ".stderr")).contains("[REDACTED_SECRET]"));
                }
                String published = att.validation.JsonSupport.write(context.resolve(collector));
                assertFalse(published.contains(secret));
                assertFalse(published.contains(large));
                assertTrue(published.length() < 12000, "collector evidence must stay bounded");
                assertTrue(String.valueOf(context.resolve(collector + ".error.message")).length() <= CollectorExceptionEvidence.TEXT_LIMIT);
                assertFalse(results.get(0).message().contains(secret));
                String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
                assertFalse(caseLog.contains(secret));
                assertFalse(caseLog.contains(large));
                assertTrue(caseLog.contains("EVIDENCE call attempt=1 collector=private"));
            }
        }
    }

    @Test void nonzeroCollectorExitUsesStatusAndExitCodeInsteadOfStdout() throws Exception {
        for (String mode : Arrays.asList("continue", "stop")) {
            Path caseDir = tempDir.resolve("collector-stdout-" + mode);
            Files.createDirectories(caseDir);
            TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                    Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
            CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
            context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
            Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
            tools.put("sample", new ToolConfig("sample", "Sample", "test", "fake", "text",
                    Collections.<String, ToolArgumentConfig>emptyMap()));
            FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
            TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{upper('ok')}",
                    "evidence", map("snapshot", map("call", "#{sample()}", "onFailure", mode))));
            List<ValidationResult> results;
            try (CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir, config,
                        new FixedRunner(2, "partial-data", "stderr detail"))))
                        .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
            }
            assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status());
            String collector = "ACTIONS.call.output.evidence.collectors.snapshot";
            assertNull(context.resolve(collector + ".result"));
            String message = String.valueOf(context.resolve(collector + ".error.message"));
            assertTrue(message.contains("status ERROR"), message);
            assertTrue(message.contains("exitCode=2"), message);
            assertFalse(message.contains("partial-data"));
            if ("stop".equals(mode)) {
                assertTrue(results.get(0).message().contains("exitCode=2"));
                assertFalse(results.get(0).message().contains("partial-data"));
            }
        }
    }

    @Test void failedCommandCollectorPreservesOperationEvidenceAndActionableLogMessage() throws Exception {
        Path caseDir = tempDir.resolve("evidence-command-failure");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("sample", new ToolConfig("sample", "Sample", "test", "fake", "text",
                Collections.<String, ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
        TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{upper('ok')}",
                "assert", "${output.result} == 'OK'",
                "evidence", map("appLog", map("call", "#{sample()}", "onFailure", "continue"))));
        CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"));
        List<ValidationResult> results = new StageTemplateRunner(
                new UnifiedTemplateEngine(new ToolInvoker(tempDir, config, new FixedRunner(2, "", "missing.log: No such file"))))
                .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
        log.close();

        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals("PASS", context.resolve("ACTIONS.call.output.status"));
        assertEquals("ERROR", context.resolve("ACTIONS.call.output.evidence.collectors.appLog.status"));
        assertEquals("OPERATION_FAILED", context.resolve("ACTIONS.call.output.evidence.collectors.appLog.error.category"));
        assertTrue(String.valueOf(context.resolve("ACTIONS.call.output.evidence.collectors.appLog.error.message")).contains("exitCode=2"));
        assertEquals(Integer.valueOf(2), context.resolve("ACTIONS.call.output.evidence.collectors.appLog.error.exitCode"));
        assertEquals("missing.log: No such file",
                context.resolve("ACTIONS.call.output.evidence.collectors.appLog.evidence.tool.invocations[0].stderr"));
        String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
        assertTrue(caseLog.contains("EVIDENCE call attempt=1 collector=appLog"));
        assertTrue(caseLog.contains("missing.log: No such file"));
    }

    @Test void failedCollectorEvidenceSurvivesEarlierRetryAttempt() throws Exception {
        Path caseDir = tempDir.resolve("evidence-collector-retry-failure");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put("sample", new ToolConfig("sample", "Sample", "test", "fake", "text",
                Collections.<String, ToolArgumentConfig>emptyMap()));
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
        Map<String, Object> retry = map("maxAttempts", 2, "intervalMs", 0, "retryOn", Arrays.asList("ASSERTION"));
        TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{sample()}",
                "assert", "${output.result} == 'ok'", "retry", retry,
                "evidence", map("snapshot", map("call", "#{capture(value=${output.result})}", "onFailure", "continue"))));
        SequencedCollectorBuiltIns builtIns = new SequencedCollectorBuiltIns();
        SequencedRunner runner = new SequencedRunner(false);
        List<ValidationResult> results = new StageTemplateRunner(
                new UnifiedTemplateEngine(new ToolInvoker(tempDir, config, runner), builtIns))
                .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals("ERROR", context.resolve("ACTIONS.call.output.attempts[0].evidence.collectors.snapshot.status"));
        assertTrue(String.valueOf(context.resolve("ACTIONS.call.output.attempts[0].evidence.collectors.snapshot.error.message"))
                .contains("collector first failed"));
        assertEquals("PASS", context.resolve("ACTIONS.call.output.attempts[1].evidence.collectors.snapshot.status"));
        assertEquals("PASS", context.resolve("ACTIONS.call.output.evidence.collectors.snapshot.status"));
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

    @Test void explicitNullLogValueIsAcceptedAndRenderedAsTypedValue() throws Exception {
        Path caseDir = tempDir.resolve("null-log-value");
        Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
        TemplateAction action = new TemplateAction("note", map("type", "log", "value", null, "format", "json"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("verify", new StageTemplate("T", tempDir, Collections.singletonList(action)), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));

        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        assertEquals("null\n", context.resolve("EXEC.ACTIONS.note.output.result"));
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
        assertEquals("line1\nline2", context.resolve("ACTIONS.process.output.result"));
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

    @Test void rendersGlobToExactStringsWithoutFormatInference() throws Exception {
        Path template = tempDir.resolve("typed-documents");
        Files.createDirectories(template.resolve("data"));
        Files.write(template.resolve("data/a.json"), ("{\"value\":1}").getBytes("UTF-8"));
        Files.write(template.resolve("data/b.json"), ("{\"value\":2}").getBytes("UTF-8"));
        Files.write(template.resolve("value.yaml"), ("name: ${CASE.caseId}\n").getBytes("UTF-8"));
        Files.write(template.resolve("value.xml"), "<Result>OK</Result>".getBytes("UTF-8"));
        Files.write(template.resolve("value.txt"), "hello ".getBytes("UTF-8"));
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        Path caseDir = tempDir.resolve("typed-document-case");
        CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
        context.beginStage(new StageCaseData("render", "T", Collections.<String,Object>emptyMap()), "T", template);
        List<TemplateAction> actions = Arrays.asList(
                new TemplateAction("json", map("type", "render", "payload", "data/*.json")),
                new TemplateAction("yaml", map("type", "render", "payload", "value.yaml")),
                new TemplateAction("xml", map("type", "render", "payload", "value.xml")),
                new TemplateAction("text", map("type", "render", "payload", "value.txt")));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("render", new StageTemplate("T", template, actions), context,
                        new CaseExecutionLog(caseDir.resolve("case.log")));
        assertEquals(Arrays.asList(ResultStatus.PASS, ResultStatus.PASS, ResultStatus.PASS, ResultStatus.PASS),
                Arrays.asList(results.get(0).status(), results.get(1).status(), results.get(2).status(), results.get(3).status()));
        @SuppressWarnings("unchecked")
        Map<String, String> json = (Map<String, String>) context.resolve("ACTIONS.json.output.result");
        assertEquals("{\"value\":1}", json.get("data/a.json"));
        assertEquals("{\"value\":2}", json.get("data/b.json"));
        assertEquals("name: g.TC1\n", context.resolve("ACTIONS.yaml.output.result"));
        assertEquals("<Result>OK</Result>", context.resolve("ACTIONS.xml.output.result"));
        assertEquals("hello ", context.resolve("ACTIONS.text.output.result"));
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
        TemplateAction action = new TemplateAction("render", map("type", "render", "payload", "payload.txt"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context,
                        new CaseExecutionLog(tempDir.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals("new", context.resolve("ACTIONS.render.output.result"));
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
        TemplateAction action = new TemplateAction("render", map("type", "render", "payload", "requests/*.xml"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("render", new StageTemplate("T", template, Collections.singletonList(action)), context,
                        new CaseExecutionLog(output.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        @SuppressWarnings("unchecked")
        Map<String, String> documents = (Map<String, String>) context.resolve("ACTIONS.render.output.result");
        assertEquals("<payment/>", documents.get("requests/payment.xml"));
        assertEquals("<refund/>", documents.get("requests/refund.xml"));
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
        TemplateAction action = new TemplateAction("render", map("type", "render", "payload", "requests/*.xml"));
        List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                .execute("render", new StageTemplate("T", template, Collections.singletonList(action)), context,
                        new CaseExecutionLog(output.resolve("case.log")));
        assertEquals(ResultStatus.PASS, results.get(0).status(), results.get(0).message());
        assertEquals("<payment for=\"chosen\"/>", context.resolve("ACTIONS.render.output.result"));
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
        TemplateAction action = new TemplateAction("render",map("type","render","description","Render ${CASE.caseId}; status=${output.status}","payload","../*.txt","onFailure","continue"));
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
        @Override public Set<String> names() { return new LinkedHashSet<String>(Arrays.asList("capture", "upper")); }
        @Override public Object invoke(String name, Map<String,Object> arguments) {
            calls++; last = new LinkedHashMap<String,Object>(arguments);
            if ("upper".equals(name)) return String.valueOf(arguments.get("value")).toUpperCase(Locale.ROOT);
            return arguments.get("value");
        }
    }
    @Test void typedCollectorSecretsAreRedactedWithoutChangingTimeoutMetadata() throws Exception {
        String document = "<password>document-secret-931</password>";
        String arraySecret = "array-secret-742";
        byte[] binary = "binary-secret-628".getBytes("UTF-8");
        String binary64 = java.util.Base64.getEncoder().encodeToString(binary);
        String binaryHex = "62696e6172792d7365637265742d363238";
        String binaryDecimal = Arrays.toString(binary);
        String primitiveArray = Arrays.toString(new int[] {918, 627});
        String details = document + " " + arraySecret + " " + new String(binary, "UTF-8") + " " + binary64
                + " " + binaryHex + " " + binaryHex.toUpperCase(Locale.ROOT) + " " + binaryDecimal
                + " " + primitiveArray + " char-array-secret";
        final Map<String, Object> privateInput = map("document", document,
                "array", new Object[] {new String[] {arraySecret}, binary, "char-array-secret".toCharArray(), new int[] {918, 627}},
                "collision", Arrays.asList("TIMEOUT", "app", "one"));
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null) {
            @Override public ToolInvocationResult executeToolAttempt(String call, CaseRuntimeContext context,
                    CaseExecutionLog log, String invocationId, Long timeoutMs, String saveAs,
                    boolean overwrite, boolean bypassCache) throws Exception {
                if (!call.startsWith("#{fail(")) return super.executeToolAttempt(call, context, log,
                        invocationId, timeoutMs, saveAs, overwrite, bypassCache);
                throw new ToolExecutionException("TIMEOUT", details,
                        map("input", privateInput, "status", "TIMEOUT", "sshHelper", "app",
                                "instance", "one", "stderr", details), null, null);
            }
        };
        for (String mode : Arrays.asList("continue", "stop")) {
            Path caseDir = tempDir.resolve("typed-collector-" + mode);
            Files.createDirectories(caseDir);
            TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                    Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
            CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
            context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
            TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{upper('ok')}",
                    "evidence", map("typed", map("call", "#{fail()}", "onFailure", mode))));
            try (CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                List<ValidationResult> results = new StageTemplateRunner(engine).execute("invoke",
                        new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
                assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status());
            }
            String path = "ACTIONS.call.output.evidence.collectors.typed";
            assertEquals("TIMEOUT", context.resolve(path + ".status"));
            assertEquals("TIMEOUT", context.resolve(path + ".error.category"));
            String invocation = path + ".evidence.tool.invocations[0]";
            assertEquals("TIMEOUT", context.resolve(invocation + ".status"));
            assertEquals("app", context.resolve(invocation + ".sshHelper"));
            assertEquals("one", context.resolve(invocation + ".instance"));
            String published = att.validation.JsonSupport.write(context.resolve(path));
            String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
            for (String secret : Arrays.asList(document, arraySecret, new String(binary, "UTF-8"), binary64,
                    binaryHex, binaryHex.toUpperCase(Locale.ROOT), binaryDecimal, primitiveArray, "char-array-secret")) {
                assertFalse(published.contains(secret), secret);
                assertFalse(caseLog.contains(secret), secret);
            }
            assertTrue(published.contains("[REDACTED_SECRET]"));
        }
    }

    @Test void collectorInstanceProjectionIsBoundedAndPrioritizesFailures() {
        Map<String, Object> instances = new LinkedHashMap<String, Object>();
        for (int index = 0; index < 80; index++) instances.put("peer" + index,
                map("instance", "peer" + index, "status", "PASS", "output", "private payload"));
        instances.put("failed", map("status", "TIMEOUT", "error", "secret" + String.join("", Collections.nCopies(5000, "x")),
                "stderr", "secret", "rawOutput", "private payload"));
        ToolExecutionException projected = CollectorExceptionEvidence.project(new ToolExecutionException("TIMEOUT",
                "fanout failed", map("input", map("token", "secret"), "instances", instances), null, null));
        Map<?, ?> peers = (Map<?, ?>) projected.evidence().get("instances");
        assertEquals(CollectorExceptionEvidence.INSTANCE_LIMIT, peers.size());
        assertTrue(peers.containsKey("failed"));
        assertEquals(81, projected.evidence().get("instanceCount"));
        assertEquals(Boolean.TRUE, projected.evidence().get("instancesTruncated"));
        Map<?, ?> failed = (Map<?, ?>) peers.get("failed");
        assertEquals("TIMEOUT", failed.get("status"));
        assertEquals(Boolean.TRUE, failed.get("errorTruncated"));
        assertTrue(String.valueOf(failed.get("error")).length() <= CollectorExceptionEvidence.TEXT_LIMIT);
        assertFalse(att.validation.JsonSupport.write(peers).contains("secret"));
        assertFalse(att.validation.JsonSupport.write(peers).contains("private payload"));
        assertEquals(projected.evidence(), CollectorExceptionEvidence.project(projected).evidence());
    }

    @Test void longPrivateInputsNeverPublishUpstreamTruncatedEchoes() throws Exception {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < 2000; index++) value.append("secret-").append(index).append('-');
        final String secret = value.toString();
        assertTrue(secret.length() > 20000);
        for (final String echo : Arrays.asList(secret.substring(0, 2000),
                secret.substring(0, 128) + "...[CAPTURE_TRUNCATED]..." + secret.substring(secret.length() - 128))) {
            for (String mode : Arrays.asList("continue", "stop")) {
                Path caseDir = tempDir.resolve("long-secret-" + echo.length() + "-" + mode);
                Files.createDirectories(caseDir);
                TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
                CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
                context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
                UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null) {
                    @Override public ToolInvocationResult executeToolAttempt(String call, CaseRuntimeContext runtime,
                            CaseExecutionLog log, String invocationId, Long timeoutMs, String saveAs,
                            boolean overwrite, boolean bypassCache) throws Exception {
                        if (!call.startsWith("#{fail(")) return super.executeToolAttempt(call, runtime, log,
                                invocationId, timeoutMs, saveAs, overwrite, bypassCache);
                        throw new ToolExecutionException("TIMEOUT", "Timed out: " + echo,
                                map("input", map("token", secret), "status", "TIMEOUT", "sshHelper", "app",
                                        "instance", "one", "timeoutMs", 50L, "stderr", echo,
                                        "instances", map("one", map("status", "TIMEOUT", "error", echo,
                                                "stderr", echo, "cleanupWarning", echo))),
                                null, new IllegalStateException(echo));
                    }
                };
                TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{upper('ok')}",
                        "evidence", map("private", map("call", "#{fail()}", "onFailure", mode))));
                List<ValidationResult> results;
                try (CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                    results = new StageTemplateRunner(engine).execute("invoke",
                            new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
                }
                assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status());
                String path = "ACTIONS.call.output.evidence.collectors.private";
                assertEquals("TIMEOUT", context.resolve(path + ".status"));
                assertEquals("TIMEOUT", context.resolve(path + ".error.category"));
                assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, context.resolve(path + ".error.message"));
                String invocation = path + ".evidence.tool.invocations[0]";
                assertEquals(Boolean.TRUE, context.resolve(invocation + ".inputRedactionLimited"));
                assertEquals(Boolean.TRUE, context.resolve(invocation + ".failureDetailsOmitted"));
                assertEquals("app", context.resolve(invocation + ".sshHelper"));
                assertEquals(50L, context.resolve(invocation + ".timeoutMs"));
                assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, context.resolve(invocation + ".instances.one.error"));
                String published = att.validation.JsonSupport.write(context.resolve(path));
                String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
                for (String fragment : Arrays.asList(secret.substring(0, 64), secret.substring(secret.length() - 64))) {
                    assertFalse(published.contains(fragment));
                    assertFalse(caseLog.contains(fragment));
                    assertFalse(results.get(0).message().contains(fragment));
                }
            }
        }
    }

    @Test void oversizedBinaryAndArrayInputsUseBoundedFailClosedProjection() {
        byte[] bytes = new byte[8 * 1024 * 1024];
        Arrays.fill(bytes, (byte) 's');
        int[] numbers = new int[1024 * 1024];
        Arrays.fill(numbers, 917);
        Iterable<Object> endless = () -> new Iterator<Object>() {
            @Override public boolean hasNext() { return true; }
            @Override public Object next() { return "private"; }
        };
        for (Object input : Arrays.asList(bytes, numbers, endless,
                Collections.nCopies(20, String.join("", Collections.nCopies(512, "x"))))) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                ToolExecutionException projected = CollectorExceptionEvidence.project(new ToolExecutionException(
                        "TIMEOUT", "ssssssssssssssss",
                        map("input", input, "status", "TIMEOUT", "stderr", "ssssssssssssssss",
                                "instances", map("one", map("status", "TIMEOUT", "error", "private"))),
                        null, null));
                assertEquals(Boolean.TRUE, projected.evidence().get("inputRedactionLimited"));
                assertEquals(Boolean.TRUE, projected.evidence().get("failureDetailsOmitted"));
                assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, projected.getMessage());
                assertEquals("TIMEOUT", projected.category());
                assertEquals("TIMEOUT", projected.evidence().get("status"));
                assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, projected.evidence().get("stderr"));
                assertTrue(att.validation.JsonSupport.write(projected.evidence()).length() < 2000);
                assertEquals(projected.evidence(), CollectorExceptionEvidence.project(projected).evidence());
            });
        }
    }

    @Test void flaggedTruncatedCapturesOmitShortSecretFragments() throws Exception {
        String secret = "short-private-token-246813579";
        String preview = secret.substring(0, 12) + "...[CAPTURE_TRUNCATED]..."
                + secret.substring(secret.length() - 8);
        for (String flag : Arrays.asList("stderrTruncated", "stderrArtifactTruncated",
                "messageTruncated", "evidenceTruncated")) {
            Map<String, Object> source = map("input", map("token", secret), "status", "TIMEOUT",
                    "sshHelper", "app", "instance", "one", "stderr", preview, flag, Boolean.TRUE,
                    "instances", map("one", map("status", "TIMEOUT", "error", preview,
                            "stderr", preview, "cleanupWarning", preview, flag, Boolean.TRUE)));
            verifyPrivateCollectorFailure("truncated-" + flag, new ToolExecutionException("TIMEOUT",
                    "Failure: " + preview, source, null, null),
                    Arrays.asList(secret.substring(0, 12), secret.substring(secret.length() - 8)), true);
            ToolExecutionException projected = CollectorExceptionEvidence.project(new ToolExecutionException("TIMEOUT",
                    "Failure: " + preview, source, null, null));
            assertEquals(Boolean.TRUE, projected.evidence().get(flag));
            assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, projected.evidence().get("stderr"));
            Map<?, ?> host = (Map<?, ?>) ((Map<?, ?>) projected.evidence().get("instances")).get("one");
            assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, host.get("error"));
            assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, host.get("stderr"));
            assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, host.get("cleanupWarning"));
            assertEquals(projected.evidence(), CollectorExceptionEvidence.project(projected).evidence());
        }
        // An executor's truncated diagnostic remains useful when there are no private tokens.
        ToolExecutionException publicFailure = CollectorExceptionEvidence.project(new ToolExecutionException("TIMEOUT",
                "public failure", map("stderr", "public preview", "stderrTruncated", Boolean.TRUE), null, null));
        assertEquals("public preview", publicFailure.evidence().get("stderr"));
    }

    @Test void primitiveArrayElementsAreRedactedWhenEchoedAlone() throws Exception {
        Map<String, Object> input = map("pins", new int[] {1234, 5678});
        ToolExecutionException failure = new ToolExecutionException("TIMEOUT", "Invalid PIN 1234",
                map("input", input, "status", "TIMEOUT", "sshHelper", "app", "instance", "one",
                        "stderr", "PIN 5678 rejected; list=[1234, 5678]",
                        "instances", map("one", map("status", "TIMEOUT", "error", "Invalid PIN 1234"))),
                null, null);
        verifyPrivateCollectorFailure("primitive-element", failure, Arrays.asList("1234", "5678"), false);
        ToolExecutionException projected = CollectorExceptionEvidence.project(failure);
        assertEquals("Invalid PIN [REDACTED_SECRET]", projected.getMessage());
        assertFalse(Boolean.TRUE.equals(projected.evidence().get("inputRedactionLimited")));
        assertEquals(projected.evidence(), CollectorExceptionEvidence.project(projected).evidence());
    }

    private void verifyPrivateCollectorFailure(String scenario, final ToolExecutionException failure,
            List<String> fragments, boolean omitted) throws Exception {
        for (String mode : Arrays.asList("continue", "stop")) {
            Path caseDir = tempDir.resolve(scenario + "-" + mode);
            Files.createDirectories(caseDir);
            TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                    Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
            CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
            context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", tempDir);
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null) {
                @Override public ToolInvocationResult executeToolAttempt(String call, CaseRuntimeContext runtime,
                        CaseExecutionLog log, String invocationId, Long timeoutMs, String saveAs,
                        boolean overwrite, boolean bypassCache) throws Exception {
                    if (!call.startsWith("#{fail(")) return super.executeToolAttempt(call, runtime, log,
                            invocationId, timeoutMs, saveAs, overwrite, bypassCache);
                    throw failure;
                }
            };
            TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{upper('ok')}",
                    "evidence", map("private", map("call", "#{fail()}", "onFailure", mode))));
            List<ValidationResult> results;
            try (CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                results = new StageTemplateRunner(engine).execute("invoke",
                        new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
            }
            assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status());
            String path = "ACTIONS.call.output.evidence.collectors.private";
            assertEquals("TIMEOUT", context.resolve(path + ".status"));
            assertEquals("TIMEOUT", context.resolve(path + ".error.category"));
            String invocation = path + ".evidence.tool.invocations[0]";
            assertEquals("app", context.resolve(invocation + ".sshHelper"));
            assertEquals("one", context.resolve(invocation + ".instance"));
            if (omitted) {
                assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, context.resolve(path + ".error.message"));
                assertEquals(Boolean.TRUE, context.resolve(invocation + ".failureDetailsOmitted"));
            }
            String published = att.validation.JsonSupport.write(context.resolve(path));
            String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
            for (String fragment : fragments) {
                assertFalse(published.contains(fragment), fragment);
                assertFalse(caseLog.contains(fragment), fragment);
                assertFalse(results.get(0).message().contains(fragment), fragment);
            }
            assertTrue(published.contains("[REDACTED_SECRET]"));
        }
    }

    @Test void returnedCommandCollectorFailuresNeverPublishPrivateExecutionData() throws Exception {
        String secret = "returned-command-secret-739";
        for (boolean oversized : new boolean[] {false, true}) {
            String payload = oversized ? String.join("", Collections.nCopies(20000, "x")) : "ordinary-payload";
            for (String mode : Arrays.asList("continue", "stop")) {
                Path caseDir = tempDir.resolve("returned-private-" + oversized + "-" + mode);
                Files.createDirectories(caseDir);
                TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
                CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
                context.beginStage(new StageCaseData("invoke", "T", map("secret", secret, "payload", payload)), "T", tempDir);
                Map<String, ToolArgumentConfig> arguments = new LinkedHashMap<String, ToolArgumentConfig>();
                arguments.put("secret", new ToolArgumentConfig("secret", "Secret", "", true, ""));
                arguments.put("payload", new ToolArgumentConfig("payload", "Payload", "", true, ""));
                Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
                tools.put("sample", new ToolConfig("sample", "Sample", "test", "fake ${secret} ${payload}", "text", arguments));
                FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
                CommandRunner process = new CommandRunner() {
                    @Override public CommandResult run(List<String> argv, java.time.Duration timeout, Path directory,
                            Map<String, String> environment) {
                        assertTrue(argv.contains(secret));
                        assertTrue(argv.contains(payload));
                        return new CommandResult(2, secret + " " + payload, "Denied token " + secret, false);
                    }
                };
                TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{upper('ok')}",
                        "evidence", map("private", map("call",
                                "#{sample(secret=${EXEC.INPUT.secret}, payload=${EXEC.INPUT.payload})}", "onFailure", mode))));
                List<ValidationResult> results;
                try (CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                    results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir, config, process)))
                            .execute("invoke", new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
                }
                assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status());
                String path = "ACTIONS.call.output.evidence.collectors.private";
                String invocation = path + ".evidence.tool.invocations[0]";
                assertEquals("ERROR", context.resolve(path + ".status"));
                assertNull(context.resolve(path + ".result"));
                assertEquals("sample", context.resolve(invocation + ".name"));
                assertEquals("ERROR", context.resolve(invocation + ".status"));
                assertEquals(2, context.resolve(invocation + ".exitCode"));
                assertEquals(Boolean.TRUE, context.resolve(invocation + ".inputOmitted"));
                for (String field : Arrays.asList("input", "argv", "logicalArgv", "command", "rawOutput", "output")) {
                    assertNull(context.resolve(invocation + "." + field), field);
                }
                assertEquals(oversized ? CollectorExceptionEvidence.OMITTED_TEXT : "Denied token [REDACTED_SECRET]",
                        context.resolve(invocation + ".stderr"));
                assertEquals(oversized ? CollectorExceptionEvidence.OMITTED_TEXT : "[REDACTED_SECRET] [REDACTED_SECRET]",
                        context.resolve(invocation + ".stdout"));
                if (oversized) assertEquals(Boolean.TRUE, context.resolve(invocation + ".inputRedactionLimited"));
                String message = String.valueOf(context.resolve(path + ".error.message"));
                assertTrue(message.contains("exitCode=2"), message);
                String published = att.validation.JsonSupport.write(context.resolve(path));
                String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
                assertFalse(published.contains(secret));
                assertFalse(caseLog.contains(secret));
                assertFalse(published.contains(payload));
                assertFalse(caseLog.contains(payload));
                assertFalse(results.get(0).message().contains(secret));
            }
        }
    }

    @Test void returnedNativeDiagnosticsUseTheSharedPrivateInputProjection() {
        String secret = "native-private-token-729";
        Map<String, Object> invocation = map("status", "ERROR", "input", map("token", secret));
        Map<String, Object> nativeNode = map("id", "native", "input", map("token", secret),
                "error", map("type", "HTTP_TIMEOUT", "message", "Denied " + secret), "stdout", secret);
        ActionExecutionResult operation = new ActionExecutionResult(secret,
                ActionExecutionResult.evidence("http", nativeNode), false,
                map("code", "HTTP_TIMEOUT", "message", "Denied " + secret, "detail", secret,
                        "privatePayload", secret), 10L);
        ToolInvocationResult projected = CollectorExceptionEvidence.project(
                new ToolInvocationResult("native", "native", secret, invocation, false, operation));
        assertNull(projected.output());
        assertEquals("Denied [REDACTED_SECRET]", projected.operationResult().diagnostic().get("message"));
        assertEquals("HTTP_TIMEOUT", projected.operationResult().diagnostic().get("code"));
        assertFalse(att.validation.JsonSupport.write(projected.evidence()).contains(secret));
        assertFalse(att.validation.JsonSupport.write(projected.operationResult().diagnostic()).contains(secret));
        assertEquals(projected.evidence(), CollectorExceptionEvidence.project(projected).evidence());
    }

    @Test void shortScalarAndPrimitiveTokensOmitDetailsInsteadOfCorruptingNumbers() {
        for (Object input : Arrays.asList(new int[] {1, 2}, Integer.valueOf(1), Byte.valueOf((byte) 2), "ab")) {
            ToolExecutionException projected = CollectorExceptionEvidence.project(new ToolExecutionException("TIMEOUT",
                    "HTTP 502 from app01 at 2026-10-01",
                    map("input", input, "status", "TIMEOUT", "sshHelper", "app01", "exitCode", 2,
                            "stderr", "HTTP 502 from app01 at 2026-10-01"), 2, null));
            assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, projected.getMessage());
            assertEquals(CollectorExceptionEvidence.OMITTED_TEXT, projected.evidence().get("stderr"));
            assertEquals(Boolean.TRUE, projected.evidence().get("inputRedactionLimited"));
            assertEquals("TIMEOUT", projected.category());
            assertEquals("app01", projected.evidence().get("sshHelper"));
            assertEquals(2, projected.exitCode());
            assertEquals(2, projected.evidence().get("exitCode"));
        }
    }

    @Test void failedCommandCollectorKeepsSafeStdoutDiagnosticWithoutPromotingItToMessage() throws Exception {
        String secret = "stdout-private-token-729";
        String stdout = "Access denied for " + secret;
        for (String mode : Arrays.asList("continue", "stop")) {
            Path caseDir = tempDir.resolve("stdout-diagnostic-" + mode);
            Files.createDirectories(caseDir);
            TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                    Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
            CaseRuntimeContext context = new CaseRuntimeContext(test, caseDir, "R", tempDir, caseDir.resolve("case.log"));
            context.beginStage(new StageCaseData("invoke", "T", map("secret", secret)), "T", tempDir);
            Map<String, ToolArgumentConfig> arguments = Collections.singletonMap("secret",
                    new ToolArgumentConfig("secret", "Secret", "", true, ""));
            Map<String, ToolConfig> tools = Collections.singletonMap("sample",
                    new ToolConfig("sample", "Sample", "test", "fake ${secret}", "text", arguments));
            FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10000, tempDir, tools, null, null);
            TemplateAction action = new TemplateAction("call", map("type", "tool", "call", "#{upper('ok')}",
                    "evidence", map("diagnostic", map("call", "#{sample(secret=${EXEC.INPUT.secret})}", "onFailure", mode))));
            List<ValidationResult> results;
            try (CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                results = new StageTemplateRunner(new UnifiedTemplateEngine(new ToolInvoker(tempDir, config,
                        new FixedRunner(2, stdout, "")))).execute("invoke",
                        new StageTemplate("T", tempDir, Collections.singletonList(action)), context, log);
            }
            assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status());
            String path = "ACTIONS.call.output.evidence.collectors.diagnostic";
            assertNull(context.resolve(path + ".result"));
            assertEquals("Access denied for [REDACTED_SECRET]",
                    context.resolve(path + ".evidence.tool.invocations[0].stdout"));
            assertEquals("", context.resolve(path + ".evidence.tool.invocations[0].stderr"));
            String message = String.valueOf(context.resolve(path + ".error.message"));
            assertTrue(message.contains("exitCode=2"), message);
            assertFalse(message.contains("Access denied"));
            assertFalse(att.validation.JsonSupport.write(context.resolve(path)).contains(secret));
            String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
            assertFalse(caseLog.contains(secret));
            assertTrue(caseLog.contains("Access denied for [REDACTED_SECRET]"));
        }
    }

    @Test void mqRootCompletionReasonAndSafeHttpLocationSurviveProjection() {
        Map<String, Object> reason = map("type", "MQ_ERROR", "completionCode", 2,
                "reasonCode", 2059, "reason", "MQRC_Q_MGR_NOT_AVAILABLE", "message", "Unavailable");
        Map<String, Object> mq = map("type", "mq", "status", "ERROR", "completionCode", 2,
                "reasonCode", 2059, "reason", "MQRC_Q_MGR_NOT_AVAILABLE", "error", reason,
                "queueManager", "QM1", "physicalInstance", "one", "host", "localhost",
                "port", 1414, "channel", "APP.CHANNEL", "transport", "client", "payload", "private payload");
        ToolInvocationResult projectedMq = CollectorExceptionEvidence.project(new ToolInvocationResult("mq", "mq", null,
                map("status", "ERROR"), false, ActionExecutionResult.evidence("mq", mq)));
        Map<?, ?> mqNode = (Map<?, ?>) ((List<?>) ((Map<?, ?>) projectedMq.evidence().get("mq")).get("invocations")).get(0);
        assertEquals(2, mqNode.get("completionCode"));
        assertEquals(2059, mqNode.get("reasonCode"));
        assertEquals("MQRC_Q_MGR_NOT_AVAILABLE", mqNode.get("reason"));
        assertEquals(2, ((Map<?, ?>) mqNode.get("error")).get("completionCode"));
        assertEquals("MQRC_Q_MGR_NOT_AVAILABLE", ((Map<?, ?>) mqNode.get("error")).get("reason"));
        assertFalse(mqNode.containsKey("payload"));
        assertEquals(projectedMq.evidence(), CollectorExceptionEvidence.project(projectedMq).evidence());

        String secret = "http-private-token-739";
        String safeUrl = "https://api.example/orders/731";
        Map<String, Object> http = map("status", "ERROR", "helperId", "orders", "method", "POST",
                "url", safeUrl, "statusCode", 503, "input", map("token", secret),
                "error", map("type", "HTTP_ERROR", "message", "Denied " + secret),
                "body", secret, "query", map("token", secret));
        ToolInvocationResult projectedHttp = CollectorExceptionEvidence.project(new ToolInvocationResult("http", "http", secret,
                map("status", "ERROR"), false, ActionExecutionResult.evidence("http", http)));
        Map<?, ?> httpNode = (Map<?, ?>) ((List<?>) ((Map<?, ?>) projectedHttp.evidence().get("http")).get("invocations")).get(0);
        assertEquals("POST", httpNode.get("method"));
        assertEquals("https://api.example", httpNode.get("url"));
        assertEquals(Boolean.TRUE, httpNode.get("urlPathOmitted"));
        assertEquals(503, httpNode.get("statusCode"));
        assertFalse(httpNode.containsKey("body"));
        assertFalse(httpNode.containsKey("query"));
        assertFalse(att.validation.JsonSupport.write(projectedHttp.evidence()).contains(secret));
        assertEquals(projectedHttp.evidence(), CollectorExceptionEvidence.project(projectedHttp).evidence());
    }

    private final class PrivateCollectorEngine extends UnifiedTemplateEngine {
        private PrivateCollectorEngine() { super(null, new PrivateCollectorBuiltIns()); }
        @Override public ToolInvocationResult executeToolAttempt(String call, CaseRuntimeContext context,
                CaseExecutionLog log, String invocationId, Long timeoutMs, String saveAs,
                boolean overwrite, boolean bypassCache) throws Exception {
            if (!call.startsWith("#{fail(")) {
                return super.executeToolAttempt(call, context, log, invocationId, timeoutMs, saveAs, overwrite, bypassCache);
            }
            String secret = String.valueOf(context.require("EXEC.INPUT.password"));
            String detail = secret + String.join("", Collections.nCopies(10000, "z"));
            Map<String, Object> input = map("password", secret, "payload", context.require("EXEC.INPUT.payload"));
            throw new ToolExecutionException("TIMEOUT", "Collector timed out: " + detail,
                    map("name", "fail", "status", "TIMEOUT", "timeoutMs", 50L,
                            "input", input, "payload", input.get("payload"),
                            "argv", Arrays.asList(secret), "stderr", detail, "sshHelper", "app", "instance", "one"),
                    null, new IllegalStateException(secret));
        }
    }

    private static final class PrivateCollectorBuiltIns implements BuiltInProvider {
        @Override public Set<String> names() { return new LinkedHashSet<String>(Arrays.asList("upper", "block")); }
        @Override public Object invoke(String name, Map<String, Object> arguments) {
            if ("upper".equals(name)) return new DefaultBuiltInProvider().invoke(name, arguments);
            try {
                Thread.sleep(10000);
                return "unexpected completion";
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static final class FailingBuiltIns implements BuiltInProvider {
        @Override public Set<String> names() { return new LinkedHashSet<String>(Collections.singletonList("fail")); }
        @Override public Object invoke(String name, Map<String,Object> arguments) { throw new IllegalStateException("collector failed"); }
    }
    private static final class SequencedCollectorBuiltIns implements BuiltInProvider {
        int calls;
        @Override public Set<String> names() { return new LinkedHashSet<String>(Collections.singletonList("capture")); }
        @Override public Object invoke(String name, Map<String, Object> arguments) {
            calls++;
            if (calls == 1) throw new IllegalStateException("collector first failed");
            return arguments.get("value");
        }
    }
    private static final class SequencedRunner extends CommandRunner {
        int calls; final boolean timeout;
        final boolean alwaysSuccess;
        SequencedRunner(boolean timeout) { this(timeout, false); }
        SequencedRunner(boolean timeout, boolean alwaysSuccess) { this.timeout = timeout; this.alwaysSuccess = alwaysSuccess; }
        @Override public CommandResult run(List<String> argv, java.time.Duration duration, Path workingDirectory, Map<String,String> environment) { calls++; if (timeout) return new CommandResult(-1,"","",true); return alwaysSuccess || calls > 1 ? new CommandResult(0,"ok","",false) : new CommandResult(75,"first","retry",false); }
    }
    private static final class FixedRunner extends CommandRunner {
        private final int exitCode; private final String stdout; private final String stderr;
        private FixedRunner(int exitCode, String stdout) { this(exitCode, stdout, ""); }
        private FixedRunner(int exitCode, String stdout, String stderr) { this.exitCode=exitCode; this.stdout=stdout; this.stderr=stderr; }
        @Override public CommandResult run(List<String> argv, java.time.Duration duration, Path workingDirectory, Map<String,String> environment) { return new CommandResult(exitCode,stdout,stderr,false); }
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
