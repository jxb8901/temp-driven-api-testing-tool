package att.template;

import att.core.CaseRuntimeContext;
import att.core.TestCase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Author: Jeffrey + ChatGPT. */
class ExpressionEvaluatorTest {
    @TempDir Path tempDir;
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    @Test void evaluatesStringNumberAndBooleanLiterals() {
        assertTrue(evaluator.evaluate("'payment posted' == \"payment posted\""));
        assertTrue(evaluator.evaluate("12.50 >= 12 and true == true"));
        assertTrue(evaluator.evaluate("false or 7 < 9"));
        assertFalse(evaluator.evaluate("true != true"));
        assertTrue(evaluator.evaluate("1.0 == 1.00"));
        assertTrue(evaluator.evaluate("12345678901234567890.0001 > 12345678901234567890.0000"));
    }

    @Test void consumesBothSidesOfLogicalOperators() {
        assertTrue(evaluator.evaluate("true or false and false"));
        assertFalse(evaluator.evaluate("false and true or false"));
        assertTrue(evaluator.evaluate("not (false or 'ABC' like 'Z%')"));
    }

    @Test void resolvesTypedContextWithoutExpressionInjection() {
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("phrase","rock and roll"); data.put("quoted","Jeffrey's payment"); data.put("amount",12.5); data.put("enabled",true);
        CaseRuntimeContext context=new CaseRuntimeContext(new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),data,Collections.emptyMap(),null),tempDir,"R",tempDir,tempDir.resolve("case.log"));
        assertTrue(evaluator.evaluate("${CASE.phrase} == 'rock and roll'",context));
        assertTrue(evaluator.evaluate("${CASE.quoted} == \"Jeffrey's payment\"",context));
        assertTrue(evaluator.evaluate("${CASE.amount} > 10 and ${CASE.enabled} == true",context));
        assertTrue(evaluator.evaluate("'${CASE.phrase}' == 'rock and roll'",context));
        assertTrue(evaluator.evaluate("${CASE.phrase} like '%${CASE.phrase}%'",context));
        assertTrue(evaluator.evaluate("${CASE.missing} is null",context));
    }

    @Test void resolvesQuotedMapKeysContainingNamespaceBraces() {
        Map<String,Object> response = new LinkedHashMap<String,Object>();
        response.put("{urn:payment}Status", "SUCCESS");
        Map<String,Object> data = new LinkedHashMap<String,Object>();
        data.put("response", response);
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),data,Collections.emptyMap(),null),tempDir,"R",tempDir,tempDir.resolve("case.log"));
        assertTrue(evaluator.evaluate("${CASE.response['{urn:payment}Status']} == 'SUCCESS'", context));
        evaluator.validateSyntax("${CASE.response['{urn:payment}Status']} == 'SUCCESS'");
    }

    @Test void rejectsUnclosedStringLiteral() {
        assertThrows(IllegalArgumentException.class,()->evaluator.evaluate("'unfinished == 'x'"));
    }
}
