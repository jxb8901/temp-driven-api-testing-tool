package att.flow;

import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.StageCaseData;
import att.core.TestCase;
import att.core.ValidationResult;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.StageTemplateRunner;
import att.template.TemplateAction;
import att.template.UnifiedTemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlowRuntimeTest {
    @TempDir Path tempDir;

    @Test void nestedFailureRetainsInnerSourceAndCallChainAtTopLevel() throws Exception {
        writeFlow("inner-error", "schemaVersion: att-flow/v3.0\nid: common.inner-error.v1\nname: Inner\ndescription: Inner\nactions:\n  bad: {type: log, message: '${CASE.notPresent}'}\n");
        writeFlow("outer-error", "schemaVersion: att-flow/v3.0\nid: common.outer-error.v1\nname: Outer\ndescription: Outer\nactions:\n  nested: {type: flow, use: common.inner-error.v1}\n");
        StageTemplate template = new StageTemplate("T", tempDir, Collections.singletonList(
                flowAction("outer", "common.outer-error.v1", "stop")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            ValidationResult result = new StageTemplateRunner(new UnifiedTemplateEngine(null),
                    new FlowRegistry(tempDir, tempDir.resolve("templates"))).execute("verify", template, context, log).get(0);
            assertEquals(ResultStatus.ERROR, result.status());
            assertNotNull(result.diagnostic());
            assertTrue(result.diagnostic().file().endsWith("inner-error/flow.yaml"));
            assertEquals("bad", result.diagnostic().action());
            assertEquals(Arrays.asList("outer -> common.outer-error.v1", "nested -> common.inner-error.v1"),
                    result.diagnostic().context().toMap().get("callChain"));
            assertNotNull(context.resolve("ACTIONS.outer.output.exception"));
            assertNotNull(CaseRuntimeContext.getPath(context.caseTree(),
                    "STAGES.verify.TEMPLATE.ACTIONS.outer.flow.actions.nested.flow.actions.bad.diagnostic"));
            assertNull(context.resolve("ACTIONS.outer.output.outputs"));
        }
    }

    @Test void executesNestedFlowsInTheCallingTemplateContext() throws Exception {
        Path project = Paths.get("").toAbsolutePath();
        FlowRegistry flows = new FlowRegistry(project, project.resolve("templates"));
        StageTemplate template = new StageTemplateLoader(project, project.resolve("templates")).load("V3_FLOW_EXAMPLE");
        TestCase test = new TestCase(2, "book", "group", "Cases", "TC1", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir, "R", tempDir, tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "V3_FLOW_EXAMPLE", Collections.<String,Object>emptyMap()),
                template.name(), template.directory());

        List<ValidationResult> results;
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, context, log);
        }

        assertEquals(2, results.size());
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals(ResultStatus.PASS, results.get(1).status());
        assertEquals("book.group.TC1-done", context.resolve("EXEC.VARS.decoratedResult"));
        assertNull(context.resolve("ACTIONS.decoratedValue.output.result"));
        assertNotNull(CaseRuntimeContext.getPath(context.caseTree(),
                "STAGES.verify.TEMPLATE.ACTIONS.compose.flow.actions.identityFlow.flow.actions.identityValue"));
        assertNull(context.resolve("ACTIONS.compose.output.outputs"));
        assertEquals(new LinkedHashSet<String>(Arrays.asList("status", "success", "exception", "durationMs")),
                ((Map<?,?>) context.resolve("ACTIONS.compose.output")).keySet());
    }

    @Test void skippedInternalActionsProducePassAndFailedFlowHasOnlyStandardOutcome() throws Exception {
        writeFlow("skip", "schemaVersion: att-flow/v3.0\nid: common.skip.v1\nname: Skip\ndescription: Skip\nactions:\n  optional: {type: log, message: never, runWhen: 'false'}\n");
        writeFlow("fail", "schemaVersion: att-flow/v3.0\nid: common.fail.v1\nname: Fail\ndescription: Fail\nactions:\n  reject: {type: assert, assert: 'false'}\n");
        FlowRegistry flows = new FlowRegistry(tempDir, tempDir.resolve("templates"));
        StageTemplate template = new StageTemplate("T", tempDir, Arrays.asList(
                flowAction("skipFlow", "common.skip.v1", "continue"),
                flowAction("failFlow", "common.fail.v1", "continue")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        List<ValidationResult> results;
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, context, log);
        }
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertNull(context.resolve("ACTIONS.optional.output.status"));
        assertEquals("SKIPPED", CaseRuntimeContext.getPath(context.caseTree(),
                "STAGES.verify.TEMPLATE.ACTIONS.skipFlow.flow.actions.optional.output.status"));
        assertEquals(ResultStatus.FAIL, results.get(1).status());
        assertNull(context.resolve("ACTIONS.failFlow.output.outputs"));
    }

    @Test void skippedFlowInvocationDoesNotRunChildrenAndKeepsStandardOutcome() throws Exception {
        writeFlow("outer-skip", "schemaVersion: att-flow/v3.0\nid: common.outer-skip.v1\nname: Skip\ndescription: Skip\nactions:\n  unreachable: {type: log, message: never}\n");
        Map<String,Object> values = new LinkedHashMap<String,Object>();
        values.put("type", "flow"); values.put("use", "common.outer-skip.v1"); values.put("runWhen", "false");
        StageTemplate template = new StageTemplate("T", tempDir,
                Collections.singletonList(new TemplateAction("skippedFlow", values, "att-template/v3.0")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            assertEquals(ResultStatus.SKIPPED, new StageTemplateRunner(new UnifiedTemplateEngine(null),
                    new FlowRegistry(tempDir, tempDir.resolve("templates")))
                    .execute("verify", template, context, log).get(0).status());
        }
        assertNull(context.resolve("ACTIONS.unreachable"));
        assertEquals(new LinkedHashSet<String>(Arrays.asList("status", "success", "exception", "durationMs")),
                ((Map<?,?>) context.resolve("ACTIONS.skippedFlow.output")).keySet());
    }

    @Test void keepsQualifiedArtifactsWhilePublishingInternalActionDirectly() throws Exception {
        writeFlow("save", "schemaVersion: att-flow/v3.0\nid: common.save.v1\nname: Save\ndescription: Save\nactions:\n  saveValue:\n    type: tool\n    call: '#{upper(value=${CASE.caseId})}'\n    saveAs: {path: value.txt, format: text}\n");
        FlowRegistry flows = new FlowRegistry(tempDir, tempDir.resolve("templates"));
        StageTemplate template = new StageTemplate("T", tempDir,
                Collections.singletonList(flowAction("saveFlow", "common.save.v1", "stop")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            assertEquals(ResultStatus.PASS, new StageTemplateRunner(new UnifiedTemplateEngine(null), flows)
                    .execute("verify", template, context, log).get(0).status());
        }
        assertNull(context.resolve("ACTIONS.saveValue.output.result"));
        assertEquals("BOOK.GROUP.TC1", CaseRuntimeContext.getPath(context.caseTree(),
                "STAGES.verify.TEMPLATE.ACTIONS.saveFlow.flow.actions.saveValue.output.result"));
        assertEquals("BOOK.GROUP.TC1", new String(Files.readAllBytes(
                tempDir.resolve("verify/flows/saveFlow/actions/saveValue/value.txt")), StandardCharsets.UTF_8));
    }

    @Test void appliesStopAndContinueWithinFlow() throws Exception {
        writeFlow("continue", "schemaVersion: att-flow/v3.0\nid: common.continue.v1\nname: Continue\ndescription: Continue\nactions:\n  rejectContinue: {type: assert, assert: 'false', onFailure: continue}\n  afterContinue: {type: log, message: continued}\n");
        writeFlow("stop", "schemaVersion: att-flow/v3.0\nid: common.stop.v1\nname: Stop\ndescription: Stop\nactions:\n  rejectStop: {type: assert, assert: 'false'}\n  afterStop: {type: log, message: unreachable}\n");
        FlowRegistry flows = new FlowRegistry(tempDir, tempDir.resolve("templates"));
        StageTemplate template = new StageTemplate("T", tempDir, Arrays.asList(
                flowAction("continues", "common.continue.v1", "continue"),
                flowAction("stops", "common.stop.v1", "continue")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        List<ValidationResult> results;
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, context, log);
        }
        assertEquals(ResultStatus.FAIL, results.get(0).status());
        assertNull(context.resolve("ACTIONS.afterContinue"));
        assertNotNull(CaseRuntimeContext.getPath(context.caseTree(),
                "STAGES.verify.TEMPLATE.ACTIONS.continues.flow.actions.afterContinue"));
        assertEquals(ResultStatus.FAIL, results.get(1).status());
        assertNull(context.resolve("ACTIONS.afterStop"));
    }

    @Test void flowReadsPriorTemplateActionsAndAssignsCaseVariables() throws Exception {
        writeFlow("copy", "schemaVersion: att-flow/v3.0\nid: common.copy.v1\nname: Copy\ndescription: Copy\nactions:\n  copyReference: {type: assign, name: copiedRef, expression: '${EXEC.VARS.SrcRefNo}'}\n  auditReference: {type: log, message: '${EXEC.VARS.copiedRef}'}\n");
        FlowRegistry flows = new FlowRegistry(tempDir, tempDir.resolve("templates"));
        Map<String,Object> seed = new LinkedHashMap<String,Object>();
        seed.put("type", "assign"); seed.put("name", "SrcRefNo"); seed.put("expression", "REF-001");
        StageTemplate template = new StageTemplate("T", tempDir, Arrays.asList(
                new TemplateAction("seed", seed, "att-template/v3.0"),
                flowAction("copyFlow", "common.copy.v1", "stop")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, context, log);
            assertEquals(ResultStatus.PASS, results.get(0).status());
            assertEquals(ResultStatus.PASS, results.get(1).status());
        }
        assertNull(context.resolve("ACTIONS.copyReference.output.result"));
        assertEquals("REF-001", context.resolve("EXEC.VARS.copiedRef"));
    }

    @Test void movingRenderActionIntoFlowKeepsCaseExpressionsAndResultUnchanged() throws Exception {
        String payload = "case=${CASE.caseId}\nref=${CASE.SrcRefNo}\namount=${CASE.amount}\nchannel=${CASE.channel}\n";
        Files.write(tempDir.resolve("request.txt"), payload.getBytes(StandardCharsets.UTF_8));
        writeFlow("render", "schemaVersion: att-flow/v3.0\nid: common.render.v1\nname: Render\ndescription: Render\nactions:\n"
                + "  renderRequest: {type: render, payload: request.txt, renderAs: text}\n");
        Files.write(tempDir.resolve("templates/flows/render/request.txt"), payload.getBytes(StandardCharsets.UTF_8));

        Map<String,Object> render = new LinkedHashMap<String,Object>();
        render.put("type", "render"); render.put("payload", "request.txt"); render.put("renderAs", "text");
        StageTemplate inlineTemplate = new StageTemplate("INLINE", tempDir,
                Collections.singletonList(new TemplateAction("renderRequest", render, "att-template/v3.0")), "att-template/v3.0");
        StageTemplate flowTemplate = new StageTemplate("FLOW", tempDir,
                Collections.singletonList(flowAction("renderFlow", "common.render.v1", "stop")), "att-template/v3.0");

        Map<String,Object> data = new LinkedHashMap<String,Object>();
        data.put("SrcRefNo", "REF-001"); data.put("amount", "125.50"); data.put("channel", "FPS");
        String expected = "case=book.group.TC1\nref=REF-001\namount=125.50\nchannel=FPS\n";

        CaseRuntimeContext inlineContext = context(data, "inline");
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("inline/case.log"))) {
            assertEquals(ResultStatus.PASS, new StageTemplateRunner(new UnifiedTemplateEngine(null))
                    .execute("verify", inlineTemplate, inlineContext, log).get(0).status());
        }
        CaseRuntimeContext flowContext = context(data, "flow");
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("flow/case.log"))) {
            assertEquals(ResultStatus.PASS, new StageTemplateRunner(new UnifiedTemplateEngine(null),
                    new FlowRegistry(tempDir, tempDir.resolve("templates")))
                    .execute("verify", flowTemplate, flowContext, log).get(0).status());
        }

        assertEquals(expected, inlineContext.resolve("ACTIONS.renderRequest.output.result"));
        assertEquals(expected, CaseRuntimeContext.getPath(flowContext.caseTree(),
                "STAGES.verify.TEMPLATE.ACTIONS.renderFlow.flow.actions.renderRequest.output.result"));
    }

    private CaseRuntimeContext context() {
        return context(Collections.<String,Object>emptyMap(), "");
    }

    private CaseRuntimeContext context(Map<String,Object> data, String directory) {
        Path output = directory.isEmpty() ? tempDir : tempDir.resolve(directory);
        TestCase test = new TestCase(2, "book", "group", "Cases", "TC1", Collections.<String>emptyList(), data, Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, output, "R", tempDir, output.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        return context;
    }

    private TemplateAction flowAction(String id, String use, String onFailure) {
        Map<String,Object> values = new LinkedHashMap<String,Object>();
        values.put("type", "flow"); values.put("use", use); values.put("onFailure", onFailure);
        return new TemplateAction(id, values, "att-template/v3.0");
    }

    private void writeFlow(String name, String yaml) throws Exception {
        Path directory = tempDir.resolve("templates/flows").resolve(name); Files.createDirectories(directory);
        Files.write(directory.resolve("flow.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }
}
