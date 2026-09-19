/* Author: Jeffrey + ChatGPT */
package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextsTest {
    @TempDir Path tempDir;

    @Test
    void buildsUppercaseConceptTreeWithCamelCaseProperties() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("amount", "100");
        data.put("outputDirectory", "must-not-replace-framework-value");
        Map<String, Object> stageValues = new LinkedHashMap<String, Object>();
        stageValues.put("name", "PAYMENT_INVOKE");
        stageValues.put("channel", "MOBILE");
        StageCaseData stage = new StageCaseData("invoke", "PAYMENT_INVOKE", stageValues);
        TestCase testCase = new TestCase(2, "payments", "payment", "支付測試案例集", "TC001", Arrays.asList("smoke"), data,
                Collections.singletonMap("invoke", stage), null);
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, tempDir, "RUN", tempDir, tempDir.resolve("case.log"));
        context.beginStage(stage, "PAYMENT_INVOKE", tempDir.resolve("templates/PAYMENT_INVOKE"));

        assertEquals("payments.payment.TC001", context.resolve("CASE.caseId"));
        assertEquals("payments.payment.TC001", context.resolve("META.SOURCE.caseId"));
        Map<String,Object> namespaced = new LinkedHashMap<String,Object>();
        namespaced.put("{urn:payment}Status", "SUCCESS");
        context.put("CASE.response", namespaced);
        assertEquals("SUCCESS", context.resolve("CASE.response['{urn:payment}Status']"));
        assertEquals("SUCCESS", context.resolve("EXEC.INPUT.response['{urn:payment}Status']"));
        assertEquals("payments", context.resolve("CASE.workbookId"));
        assertEquals("100", context.resolve("CASE.amount"));
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), context.resolve("CASE.outputDirectory"));
        assertEquals(tempDir.toAbsolutePath().normalize(), context.caseOutputDirectory());
        assertNull(context.resolve("CASE.STAGES.invoke.channel"));
        assertNull(context.resolve("STAGES.invoke.channel"));
        assertEquals("MOBILE", CaseRuntimeContext.getPath(context.caseTree(), "STAGES.invoke.channel"));
        assertTrue(context.resolve("CASE.DB") instanceof Map);
        assertTrue(((Map<?, ?>) context.resolve("CASE.DB")).isEmpty());
        assertTrue(context.resolve("DB") instanceof Map);
        assertTrue(context.resolve("CASE.DB") != context.resolve("DB"));
    }

    @Test void resolvesNestedMapsAndListIndexes() {
        LinkedHashMap<String,Object> row = new LinkedHashMap<String,Object>();
        row.put("items", Arrays.asList(Collections.singletonMap("status", "FIRST"), Collections.singletonMap("status", "SECOND")));
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),row,Collections.emptyMap(),null), tempDir,"R",tempDir,tempDir.resolve("case.log"));
        assertEquals("FIRST", context.resolve("CASE.items[0].status"));
        assertEquals("SECOND", context.resolve("CASE.items.1.status"));
        assertNull(context.resolve("CASE.items[9].status"));
    }

    @Test void distinguishesDeferredValidationShapeFromMissingAndRealNull() {
        Map<String,Object> data = new LinkedHashMap<String,Object>();
        data.put("actualNull", null);
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),data,Collections.emptyMap(),null),
                tempDir,"R",tempDir,tempDir.resolve("case.log"));
        context.putValidationPlaceholder("CASE.VARS.response");

        assertTrue(context.contains("CASE.VARS.response.status.code"));
        assertTrue(context.isValidationDeferred("CASE.VARS.response.status.code"));
        assertTrue(context.isValidationDeferred("response.status.code"));
        assertNull(context.require("CASE.VARS.response.status.code"));
        assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("CASE.VARS.unknown.status"));
        assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("CASE.actualNull.status"));
    }

    @Test void resolvesUppercaseToolScope() {
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "payment", "支付測試案例集", "TC001", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.<String, StageCaseData>emptyMap(), null),
                tempDir, "RUN", tempDir, tempDir.resolve("case.log"));
        context.put("TOOL.input.value", 7);
        assertEquals(7, context.resolve("TOOL.input.value"));
    }

    @Test void helperViewsStayTransientAndSafeIdentityUsesMeta() {
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.<String, StageCaseData>emptyMap(), null),
                tempDir, "RUN", tempDir, tempDir.resolve("case.log"));

        context.setToolMetadata("orders.find");
        context.setDbHelperMetadata("orders");
        context.setMqHelperMetadata("orders");

        assertEquals("orders.find", context.resolve("META.TOOL.id"));
        assertEquals("orders", context.resolve("META.DBHELPER.id"));
        assertEquals("orders", context.resolve("META.MQHELPER.id"));
        assertTrue(!context.executionTree().containsKey("TOOL"));
        assertTrue(!context.executionTree().containsKey("DB"));
        assertTrue(!context.executionTree().containsKey("MQ"));
        assertThrows(IllegalArgumentException.class, () -> context.put("EXEC.TOOL.orders.find", "bad"));
        assertThrows(IllegalArgumentException.class, () -> context.put("EXEC.DB.orders.query", "bad"));
        assertThrows(IllegalArgumentException.class, () -> context.put("EXEC.MQ.orders.send", "bad"));
    }

    @Test void requiredContextReportsCaseSensitiveTypoAndNearestField() {
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(),
                        Collections.<String,Object>emptyMap(), Collections.<String,StageCaseData>emptyMap(), null),
                tempDir, "RUN", tempDir, tempDir.resolve("case.log"));
        att.validation.DiagnosticException error = assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("CASE.csaeId"));
        assertEquals(att.validation.DiagnosticCodes.CONTEXT_INVALID, error.code());
        assertTrue(error.format().contains("META.SOURCE.caseId"));
        assertTrue(error.format().contains("case-sensitive"));
    }

    @Test void stageOverlayIsRestoredAfterErrorAndActionsAreScopedToTheCurrentStage() {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("channel", "CASE");
        StageCaseData first = new StageCaseData("first", "T1",
                Collections.<String, Object>singletonMap("channel", "STAGE-1"));
        StageCaseData second = new StageCaseData("second", "T2",
                Collections.<String, Object>singletonMap("channel", "STAGE-2"));
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(), input,
                        Collections.<String, StageCaseData>emptyMap(), null),
                tempDir, "RUN", tempDir, tempDir.resolve("case.log"));

        context.beginStage(first, "T1", tempDir);
        context.addAction("firstAction", Collections.<String, Object>singletonMap("status", "PASS"));
        assertEquals("STAGE-1", context.resolve("EXEC.INPUT.channel"));
        assertEquals("PASS", context.resolve("EXEC.ACTIONS.firstAction.status"));
        context.finishStage("ERROR", 1L);
        assertFalse(context.hasActiveStage());
        assertEquals("CASE", context.resolve("EXEC.INPUT.channel"));

        context.beginStage(second, "T2", tempDir);
        assertEquals("STAGE-2", context.resolve("EXEC.INPUT.channel"));
        assertNull(context.resolve("EXEC.ACTIONS.firstAction.status"));
        context.finishStage("PASS", 1L);
        assertEquals("CASE", context.resolve("EXEC.INPUT.channel"));
    }

    @Test void resolvesOnlyUniqueCaseSensitivePathSuffixes() {
        Map<String,Object> response = new LinkedHashMap<String,Object>();
        response.put("resultCode", "SUCCESS"); response.put("status", "POSTED");
        Map<String,Object> payment = new LinkedHashMap<String,Object>(); payment.put("response", response);
        Map<String,Object> data = new LinkedHashMap<String,Object>(); data.put("payment", payment);
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2,"payment","sheet","TC001",Collections.<String>emptyList(),data,Collections.<String,StageCaseData>emptyMap(),null),
                tempDir,"R",tempDir,tempDir.resolve("case.log"));

        assertEquals("SUCCESS", context.resolve("payment.response.resultCode"));
        assertEquals("SUCCESS", context.resolve("response.resultCode"));
        assertEquals("SUCCESS", context.resolve("resultCode"));
        assertEquals("POSTED", context.resolve("response.status"));

        Map<String,Object> refundResponse = new LinkedHashMap<String,Object>(); refundResponse.put("resultCode", "REFUNDED");
        context.put("CASE.refund", Collections.singletonMap("response", refundResponse));
        att.validation.DiagnosticException ambiguous = assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("response.resultCode"));
        assertEquals(att.validation.DiagnosticCodes.CONTEXT_AMBIGUOUS, ambiguous.code());
        assertTrue(ambiguous.format().contains("EXEC.INPUT.payment.response.resultCode"));
        assertTrue(ambiguous.format().contains("EXEC.INPUT.refund.response.resultCode"));
    }

    @Test void missingContextReportsTraversalBoundaryWithoutDumpingTheContextTree() {
        Map<String,Object> status = new LinkedHashMap<String,Object>(); status.put("text", "SHOULD_NOT_APPEAR");
        Map<String,Object> response = new LinkedHashMap<String,Object>(); response.put("Status", status);
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2,"payment","sheet","TC001",Collections.<String>emptyList(),Collections.<String,Object>singletonMap("response", response),Collections.<String,StageCaseData>emptyMap(),null),
                tempDir,"R",tempDir,tempDir.resolve("case.log"));

        att.validation.DiagnosticException error = assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("CASE.response.Status.code"));
        assertEquals("ATT-CTX-001", error.code());
        assertTrue(error.format().contains("requestedPath: CASE.response.Status.code"));
        assertTrue(error.format().contains("currentNode: CASE.response.Status"));
        assertTrue(error.format().contains("missingSegment: code"));
        assertTrue(!error.format().contains("contextTree:"));
        assertTrue(!error.format().contains("text: string"));
        assertTrue(!error.format().contains("SHOULD_NOT_APPEAR"));
    }

    @Test void missingQuotedKeyAndListIndexReportExactTraversalBoundary() {
        Map<String,Object> row = new LinkedHashMap<String,Object>();
        row.put("response", Collections.singletonMap("Status Code", Arrays.asList("OK")));
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2,"payment","sheet","TC001",Collections.<String>emptyList(),row,Collections.<String,StageCaseData>emptyMap(),null),
                tempDir,"R",tempDir,tempDir.resolve("case.log"));

        att.validation.DiagnosticException missingIndex = assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("CASE.response['Status Code'][2]"));
        assertTrue(missingIndex.format().contains("currentNode: CASE.response['Status Code']"));
        assertTrue(missingIndex.format().contains("missingSegment: [2]"));

        att.validation.DiagnosticException missingQuotedKey = assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("CASE.response['Unknown Key']"));
        assertTrue(missingQuotedKey.format().contains("currentNode: CASE.response"));
        assertTrue(missingQuotedKey.format().contains("missingSegment: ['Unknown Key']"));
        assertTrue(!missingQuotedKey.format().contains("contextTree:"));
    }

    @Test void optionalReferencesReturnNullOnlyForMissingValues() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("text", "READY");
        nested.put("amount", 7);
        nested.put("enabled", Boolean.TRUE);
        nested.put("actualNull", null);
        nested.put("items", Arrays.asList(Collections.singletonMap("code", "A")));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("result", nested);
        data.put("scalar", "text");
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(), data,
                        Collections.<String, StageCaseData>emptyMap(), null),
                tempDir, "R", tempDir, tempDir.resolve("case.log"));

        assertNull(context.requireOptional("CASE.result.missing?"));
        assertNull(context.requireOptional("CASE.result.missing.child?"));
        assertNull(context.requireOptional("CASE.missing.child?"));
        assertEquals("READY", context.requireOptional("CASE.result.text?"));
        assertEquals(Integer.valueOf(7), context.requireOptional("CASE.result.amount?"));
        assertEquals(Boolean.TRUE, context.requireOptional("CASE.result.enabled?"));
        assertNull(context.requireOptional("CASE.result.actualNull?"));
        assertEquals("A", context.requireOptional("CASE.result.items[0].code?"));
        assertNull(context.requireOptional("CASE.result.items[9].code?"));

        assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("CASE.result.missing"));
        assertThrows(att.validation.DiagnosticException.class,
                () -> context.requireOptional("CASE.scalar.child?"));

        context.put("CASE.first", Collections.singletonMap("status", "A"));
        context.put("CASE.second", Collections.singletonMap("status", "B"));
        att.validation.DiagnosticException ambiguous = assertThrows(att.validation.DiagnosticException.class,
                () -> context.requireOptional("status?"));
        assertEquals(att.validation.DiagnosticCodes.CONTEXT_AMBIGUOUS, ambiguous.code());
    }

    @Test void optionalReferenceSyntaxRejectsMalformedPaths() {
        assertThrows(IllegalArgumentException.class, () -> CaseRuntimeContext.validateReferencePath("CASE..value?"));
        assertThrows(IllegalArgumentException.class, () -> CaseRuntimeContext.validateReferencePath("CASE.value??"));
        assertThrows(IllegalArgumentException.class, () -> CaseRuntimeContext.validateReferencePath("?"));
    }

    @Test void resolvesUniqueSuffixFromCompletedActionCanonicalTree() {
        StageCaseData stage = new StageCaseData("invoke", "PAYMENT", Collections.<String,Object>emptyMap());
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2,"payment","sheet","TC001",Collections.<String>emptyList(),Collections.<String,Object>emptyMap(),Collections.singletonMap("invoke", stage),null),
                tempDir,"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(stage, "PAYMENT", tempDir);
        Map<String,Object> output = new LinkedHashMap<String,Object>();
        output.put("result", Collections.singletonMap("resultCode", "SUCCESS"));
        Map<String,Object> action = new LinkedHashMap<String,Object>(); action.put("output", output);
        context.addAction("callApi", action);

        assertEquals("SUCCESS", context.resolve("callApi.output.result.resultCode"));
        assertEquals("SUCCESS", context.resolve("result.resultCode"));
        assertEquals("SUCCESS", context.resolve("resultCode"));
    }

    @Test void caseVariablesSurviveStagesAndRejectOverwrite() {
        Map<String,Object> input = new LinkedHashMap<String,Object>();
        input.put("VARS", Collections.singletonMap("fromExcel", "must-not-enter-runtime-vars"));
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2,"payment","sheet","TC001",Collections.<String>emptyList(),input,Collections.<String,StageCaseData>emptyMap(),null),
                tempDir,"R",tempDir,tempDir.resolve("case.log"));
        context.assignCaseVariable("txnSeq", "ATT001");
        context.beginStage(new StageCaseData("first","T1",Collections.<String,Object>emptyMap()),"T1",tempDir);
        assertEquals("ATT001", context.resolve("CASE.VARS.txnSeq"));
        context.beginStage(new StageCaseData("second","T2",Collections.<String,Object>emptyMap()),"T2",tempDir);
        assertEquals("ATT001", context.resolve("CASE.VARS.txnSeq"));
        assertNull(context.resolve("CASE.VARS.fromExcel"));
        att.validation.DiagnosticException duplicate = assertThrows(att.validation.DiagnosticException.class,
                () -> context.assignCaseVariable("txnSeq", "ATT002"));
        assertEquals(att.validation.DiagnosticCodes.CONTEXT_INVALID, duplicate.code());
        assertEquals("ATT001", context.resolve("CASE.VARS.txnSeq"));
    }

    @Test void canonicalExecMetaRootsShareStateAndKeepLocalOutputOutOfContext() {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("RefNo", "REF-001");
        input.put("channel", "CASE");
        input.put("ID", "EVIL");
        input.put("MODE", "EVIL");
        input.put("OUTPUT_DIR", "EVIL");
        input.put("VARS", "EVIL");
        input.put("secretToken", "must remain caller input, not metadata");
        Map<String, Object> stageValues = new LinkedHashMap<String, Object>();
        stageValues.put("channel", "STAGE");
        stageValues.put("stageOnly", "stage-value");
        StageCaseData stage = new StageCaseData("invoke", "PAYMENT", stageValues);
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(), input,
                        Collections.singletonMap("invoke", stage), null),
                tempDir, "RUN-1", tempDir, tempDir.resolve("case.log"));
        context.beginStage(stage, "PAYMENT", tempDir.resolve("templates/PAYMENT"));

        assertEquals("testcase", context.resolve("EXEC.MODE"));
        assertEquals("REF-001", context.resolve("EXEC.INPUT.RefNo"));
        assertEquals("STAGE", context.resolve("EXEC.INPUT.channel"));
        assertEquals("STAGE", context.resolve("CASE.channel"));
        assertEquals("stage-value", context.resolve("EXEC.INPUT.stageOnly"));
        assertEquals("REF-001", context.resolve("CASE.RefNo"));
        assertEquals(context.resolve("EXEC.ID"), context.resolve("RUN.id"));
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), context.resolve("EXEC.OUTPUT_DIR"));
        assertEquals(context.resolve("EXEC.OUTPUT_DIR"), context.resolve("CASE.outputDirectory"));
        assertEquals("PAYMENT", context.resolve("META.TEMPLATE.id"));
        assertNull(context.resolve("META.TEMPLATE.missing"));
        assertNull(context.resolve("EXEC.STAGES.invoke.channel"));
        assertFalse(context.executionTree().containsKey("STAGES"));
        assertFalse(String.valueOf(context.metadataTree()).contains("secretToken"));
        assertThrows(IllegalArgumentException.class, () -> context.put("EXEC.ID", "EVIL"));
        assertThrows(IllegalArgumentException.class, () -> context.put("EXEC.STATUS", "PASS"));
        assertThrows(IllegalArgumentException.class, () -> context.put("EXEC.STAGES.invoke", "EVIL"));
        assertThrows(IllegalArgumentException.class, () -> context.put("EXEC.STAGE.invoke", "EVIL"));

        context.finishStage("PASS", 1L);
        assertEquals("CASE", context.resolve("EXEC.INPUT.channel"));
        assertNull(context.resolve("EXEC.INPUT.stageOnly"));
        assertNull(context.resolve("CASE.STAGES.invoke.channel"));
        assertEquals("STAGE", CaseRuntimeContext.getPath(context.caseTree(), "STAGES.invoke.channel"));

        context.assignCaseVariable("txnId", "TX-1");
        assertSame(context.resolve("EXEC.VARS"), context.resolve("CASE.VARS"));
        assertEquals("TX-1", context.resolve("EXEC.VARS.txnId"));
        assertEquals("TX-1", context.resolve("CASE.VARS.txnId"));

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("result", Collections.singletonMap("status", "PASS"));
        context.setActionOutput(output);
        assertEquals("PASS", context.resolve("output.result.status"));
        assertNull(context.resolve("EXEC.OUTPUT"));
        context.clearActionOutput();
        assertNull(context.resolve("output.result.status"));
        assertThrows(att.validation.DiagnosticException.class, () -> context.require("output.result.status"));
        assertThrows(UnsupportedOperationException.class, () -> context.metadataTree().put("SECRET", "x"));

        CaseRuntimeContext debug = new CaseRuntimeContext(
                new TestCase(2, "payment", "sheet", "DEBUG.template.PAYMENT", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.singletonMap("invoke", stage), null),
                tempDir, "DEBUG-1", tempDir, tempDir.resolve("debug.log"), "debug");
        debug.setSourceMetadata("debug", tempDir.resolve("debug.yaml"), "DEBUG.template.PAYMENT");
        assertEquals("debug", debug.resolve("EXEC.MODE"));
        assertEquals("debug", debug.resolve("META.SOURCE.type"));
        assertEquals(tempDir.resolve("debug.yaml").toAbsolutePath().normalize().toString(),
                debug.resolve("META.SOURCE.path"));
    }
}
