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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowRuntimeTest {
    @TempDir Path tempDir;

    @Test void executesNestedFlowsWithIsolatedScopeAndDeclaredOutputs() throws Exception {
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
        assertEquals("book.group.TC1-done", context.resolve("ACTIONS.compose.output.outputs.value"));
        assertNotNull(context.resolve("ACTIONS.compose.flow.actions.identity.flow.actions.copy"));
        assertNull(context.resolve("ACTIONS.compose.actions.identity"));
        assertNull(context.resolve("CASE.VARS.value"));
    }

    @Test void skippedInternalActionsStillProducePassAndFailedFlowExportsNothing() throws Exception {
        writeFlow("skip", "schemaVersion: att-flow/v3.0\nid: common.skip.v1\nname: Skip\ndescription: Skip\ninputs:\n  value: {type: string, required: true}\nactions:\n  optional: {type: log, message: never, runWhen: 'false'}\noutputs:\n  value: {type: string, from: '${input.value}'}\n");
        writeFlow("fail", "schemaVersion: att-flow/v3.0\nid: common.fail.v1\nname: Fail\ndescription: Fail\ninputs:\n  value: {type: string, required: true}\nactions:\n  reject: {type: assert, assert: 'false'}\noutputs:\n  value: {type: string, from: '${input.value}'}\n");
        FlowRegistry flows = new FlowRegistry(tempDir, tempDir.resolve("templates"));
        StageTemplate template = new StageTemplate("T", tempDir, Arrays.asList(
                action("skip", "common.skip.v1", "ok", "continue"),
                action("fail", "common.fail.v1", "hidden", "continue")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        List<ValidationResult> results;
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, context, log);
        }
        assertEquals(ResultStatus.PASS, results.get(0).status());
        assertEquals("ok", context.resolve("ACTIONS.skip.output.outputs.value"));
        assertEquals(ResultStatus.FAIL, results.get(1).status());
        assertTrue(((Map<?, ?>) context.resolve("ACTIONS.fail.output.outputs")).isEmpty());
    }

    @Test void repeatedFlowReuseQualifiesInternalArtifactsAndEvidence() throws Exception {
        writeFlow("save", "schemaVersion: att-flow/v3.0\nid: common.save.v1\nname: Save\ndescription: Save\ninputs:\n  value: {type: string, required: true}\nactions:\n  save:\n    type: tool\n    call: '#{upper(value=${input.value})}'\n    saveAs: {path: value.txt, format: text}\noutputs:\n  value: {type: string, from: '${actions.save.output.result}'}\n");
        FlowRegistry flows = new FlowRegistry(tempDir, tempDir.resolve("templates"));
        StageTemplate template = new StageTemplate("T", tempDir, Arrays.asList(
                action("first", "common.save.v1", "one", "stop"),
                action("second", "common.save.v1", "two", "stop")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, context, log);
            assertEquals(ResultStatus.PASS, results.get(0).status()); assertEquals(ResultStatus.PASS, results.get(1).status());
        }
        assertEquals("ONE", context.resolve("ACTIONS.first.output.outputs.value"));
        assertEquals("TWO", context.resolve("ACTIONS.second.output.outputs.value"));
        assertEquals("ONE", new String(Files.readAllBytes(tempDir.resolve("verify/flows/first/actions/save/value.txt")), StandardCharsets.UTF_8));
        assertEquals("TWO", new String(Files.readAllBytes(tempDir.resolve("verify/flows/second/actions/save/value.txt")), StandardCharsets.UTF_8));
    }

    @Test void appliesStopAndContinueWithinFlowWithoutExportingFailedOutputs() throws Exception {
        writeFlow("continue", "schemaVersion: att-flow/v3.0\nid: common.continue.v1\nname: Continue\ndescription: Continue\ninputs: {}\nactions:\n  reject: {type: assert, assert: 'false', onFailure: continue}\n  after: {type: log, message: continued}\noutputs: {}\n");
        writeFlow("stop", "schemaVersion: att-flow/v3.0\nid: common.stop.v1\nname: Stop\ndescription: Stop\ninputs: {}\nactions:\n  reject: {type: assert, assert: 'false'}\n  after: {type: log, message: unreachable}\noutputs: {}\n");
        FlowRegistry flows = new FlowRegistry(tempDir, tempDir.resolve("templates"));
        StageTemplate template = new StageTemplate("T", tempDir, Arrays.asList(
                noInputAction("continues", "common.continue.v1", "continue"),
                noInputAction("stops", "common.stop.v1", "continue")), "att-template/v3.0");
        CaseRuntimeContext context = context();
        List<ValidationResult> results;
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"))) {
            results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, context, log);
        }
        assertEquals(ResultStatus.FAIL, results.get(0).status());
        assertNotNull(context.resolve("ACTIONS.continues.flow.actions.after"));
        assertEquals(ResultStatus.FAIL, results.get(1).status());
        assertNull(context.resolve("ACTIONS.stops.flow.actions.after"));
    }

    @Test void resolvesAssignedCaseVariableThenStrictlyChecksFlowInputType() throws Exception {
        writeFlow("copy", "schemaVersion: att-flow/v3.0\nid: common.copy.v1\nname: Copy\ndescription: Copy\ninputs:\n  value: {type: string, required: true}\nactions:\n  copy: {type: assign, name: value, expression: '${input.value}'}\noutputs:\n  value: {type: string, from: '${runtime.value}'}\n");
        FlowRegistry flows = new FlowRegistry(tempDir, tempDir.resolve("templates"));
        StageTemplate template = new StageTemplate("T", tempDir,
                Collections.singletonList(action("copy", "common.copy.v1", "${CASE.VARS.SrcRefNo}", "stop")),
                "att-template/v3.0");

        CaseRuntimeContext valid = context();
        valid.put("CASE.VARS.SrcRefNo", "REF-001");
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("valid.log"))) {
            List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, valid, log);
            assertEquals(ResultStatus.PASS, results.get(0).status());
            assertEquals("REF-001", valid.resolve("ACTIONS.copy.output.outputs.value"));
        }

        CaseRuntimeContext invalid = context();
        invalid.put("CASE.VARS.SrcRefNo", Integer.valueOf(7));
        try (CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("invalid.log"))) {
            List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null), flows).execute("verify", template, invalid, log);
            assertEquals(ResultStatus.ERROR, results.get(0).status());
            assertTrue(results.get(0).message().contains("must be string"), results.get(0).message());
        }
    }

    private CaseRuntimeContext context() {
        TestCase test = new TestCase(2, "book", "group", "Cases", "TC1", Collections.<String>emptyList(), Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir, "R", tempDir, tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify", "T", Collections.<String,Object>emptyMap()), "T", tempDir);
        return context;
    }

    private TemplateAction action(String id, String use, String value, String onFailure) {
        Map<String,Object> values = new LinkedHashMap<String,Object>(); values.put("type", "flow"); values.put("use", use); values.put("onFailure", onFailure);
        Map<String,Object> with = new LinkedHashMap<String,Object>(); with.put("value", value); values.put("with", with);
        return new TemplateAction(id, values, "att-template/v3.0");
    }

    private TemplateAction noInputAction(String id, String use, String onFailure) {
        Map<String,Object> values = new LinkedHashMap<String,Object>(); values.put("type", "flow"); values.put("use", use); values.put("onFailure", onFailure);
        return new TemplateAction(id, values, "att-template/v3.0");
    }

    private void writeFlow(String name, String yaml) throws Exception {
        Path directory = tempDir.resolve("templates/flows").resolve(name); Files.createDirectories(directory);
        Files.write(directory.resolve("flow.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }
}
