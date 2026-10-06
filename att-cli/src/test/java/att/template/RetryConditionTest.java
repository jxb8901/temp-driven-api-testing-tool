package att.template;

import att.core.CaseRuntimeContext;
import att.core.TestCase;
import att.validation.DiagnosticException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RetryConditionTest {
    @TempDir Path tempDir;

    @Test void deterministicBooleanExpressionsAndPureBuiltinsAreSupported() {
        for (String value : new String[]{"#{true}", "#{1 < 2}", "true", "#{length('abc') == 3}"}) {
            assertDoesNotThrow(() -> RetryCondition.validate(value));
            assertTrue(RetryCondition.evaluate(value, null), value);
        }
        assertFalse(RetryCondition.evaluate("#{false}", null));
    }

    @Test void deterministicSyntaxTypeAndEmptyValuesFailValidation() {
        for (Object value : new Object[]{"", " ", Boolean.FALSE, "#{1}", "#{'true'}", "#{true &&}", "#{upper('yes')}"})
            assertThrows(DiagnosticException.class, () -> RetryCondition.validate(value), String.valueOf(value));
    }

    @Test void externalStatefulAndFileCallsAreRejectedBeforeExecution() {
        for (String value : new String[]{"#{mq.payment.request(payload='x')}", "#{db.orders.query(sql='select 1')}",
                "#{http.api.get()}", "#{ssh.host.execute(command='x')}", "#{sample.tool()}", "#{seq('orders')}",
                "#{'#{seq(\'orders\')}'}", "seq('orders') == 1", "#{random()}", "#{sysdate()}", "#{file.exists('x')}", "&{payload.xml}"})
            assertThrows(DiagnosticException.class, () -> RetryCondition.validate(value), value);
    }

    @Test void currentOutputRemainsTypedAndStrictPathsFail() {
        TestCase test = new TestCase(1, "g", "s", "t", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, tempDir, "r", tempDir, tempDir.resolve("case.log"));
        Map<String,Object> output = new LinkedHashMap<String,Object>();
        output.put("status", "TIMEOUT"); output.put("attempt", 1);
        output.put("result", Collections.singletonMap("error", Collections.singletonMap("type", "TIMEOUT")));
        context.beginAction(output);
        context.setActionOutput(output);
        assertTrue(RetryCondition.evaluate("#{${output.status} == 'TIMEOUT' AND ${output.attempt} == 1}", context));
        assertTrue(RetryCondition.evaluate("#{${output.status} == 'TIMEOUT' AND ${output.result.error.type} == 'TIMEOUT'}", context));
        assertTrue(RetryCondition.evaluate("#{'TIMEOUT' == 'TIMEOUT' AND 'TIMEOUT' is not null}", context));
        output.put("attempt", 2);
        assertFalse(RetryCondition.evaluate("#{${output.attempt} == 1}", context));
        assertTrue(RetryCondition.evaluate("#{${output.missing?} != 2033}", context));
        DiagnosticException missing = assertThrows(DiagnosticException.class,
                () -> RetryCondition.evaluate("#{${output.missing} == 1}", context));
        assertEquals("retry.when", missing.field());
        assertTrue(missing.code().startsWith("ATT-CTX-"), missing.code());
        assertThrows(DiagnosticException.class, () -> RetryCondition.evaluate("${output.status}", context));
        context.endAction();
    }
}
