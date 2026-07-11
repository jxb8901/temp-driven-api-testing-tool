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

class ContextsTest {
    @TempDir Path tempDir;

    @Test
    void buildsUppercaseConceptTreeWithCamelCaseProperties() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("amount", "100");
        Map<String, Object> stageValues = new LinkedHashMap<String, Object>();
        stageValues.put("name", "PAYMENT_INVOKE");
        stageValues.put("channel", "MOBILE");
        StageCaseData stage = new StageCaseData("invoke", "PAYMENT_INVOKE", stageValues);
        TestCase testCase = new TestCase(2, "payment", "支付測試案例集", "TC001", Arrays.asList("smoke"), data,
                Collections.singletonMap("invoke", stage), null);
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, tempDir, "RUN", tempDir, tempDir.resolve("case.log"));
        context.beginStage(stage, "PAYMENT_INVOKE", tempDir.resolve("templates/PAYMENT_INVOKE"));

        assertEquals("payment.TC001", context.resolve("CASE.caseId"));
        assertEquals("100", context.resolve("CASE.amount"));
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
}
