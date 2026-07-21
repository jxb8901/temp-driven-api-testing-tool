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
        Map<String,Object> namespaced = new LinkedHashMap<String,Object>();
        namespaced.put("{urn:payment}Status", "SUCCESS");
        context.put("CASE.response", namespaced);
        assertEquals("SUCCESS", context.resolve("CASE.response['{urn:payment}Status']"));
        assertEquals("payments", context.resolve("CASE.workbookId"));
        assertEquals("100", context.resolve("CASE.amount"));
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), context.resolve("CASE.outputDirectory"));
        assertEquals(tempDir.toAbsolutePath().normalize(), context.caseOutputDirectory());
        assertEquals("MOBILE", context.resolve("CASE.STAGES.invoke.channel"));
    }

    @Test void resolvesNestedMapsAndListIndexes() {
        LinkedHashMap<String,Object> row = new LinkedHashMap<String,Object>();
        row.put("items", Arrays.asList(Collections.singletonMap("status", "FIRST"), Collections.singletonMap("status", "SECOND")));
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),row,Collections.emptyMap(),null), tempDir,"R",tempDir,tempDir.resolve("case.log"));
        assertEquals("FIRST", context.resolve("CASE.items[0].status"));
        assertEquals("SECOND", context.resolve("CASE.items.1.status"));
        assertNull(context.resolve("CASE.items[9].status"));
    }

    @Test void resolvesUppercaseToolScope() {
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "payment", "支付測試案例集", "TC001", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.<String, StageCaseData>emptyMap(), null),
                tempDir, "RUN", tempDir, tempDir.resolve("case.log"));
        context.put("TOOL.input.value", 7);
        assertEquals(7, context.resolve("TOOL.input.value"));
    }

    @Test void requiredContextReportsCaseSensitiveTypoAndNearestField() {
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(),
                        Collections.<String,Object>emptyMap(), Collections.<String,StageCaseData>emptyMap(), null),
                tempDir, "RUN", tempDir, tempDir.resolve("case.log"));
        att.validation.DiagnosticException error = assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("CASE.csaeId"));
        assertEquals(att.validation.DiagnosticCodes.CONTEXT_INVALID, error.code());
        assertTrue(error.format().contains("CASE.caseId"));
        assertTrue(error.format().contains("case-sensitive"));
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
        assertTrue(ambiguous.format().contains("CASE.payment.response.resultCode"));
        assertTrue(ambiguous.format().contains("CASE.refund.response.resultCode"));
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
}
