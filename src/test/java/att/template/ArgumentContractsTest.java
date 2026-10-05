package att.template;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgumentContractsTest {
    @Test void appliesMqIntegerContractToTheFinalNestedFileExpressionResult() throws Exception {
        ExpressionBlockEvaluator evaluator = new ExpressionBlockEvaluator();
        Object finalValue = evaluator.evaluate("#{str.substr(&{params/wait.txt}, 0, 4)}",
                resolver("5000-ABC"));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("waitMs", finalValue);

        assertEquals(Integer.valueOf(5000), ArgumentContracts.coerce("mq.broker.request", arguments).get("waitMs"));
    }

    @Test void appliesTheSameIntegerContractToDirectFilesAndNestedContextResults() throws Exception {
        ExpressionBlockEvaluator evaluator = new ExpressionBlockEvaluator();
        Object directFile = evaluator.evaluate("&{params/wait.txt}", resolver(" 5000\n"));
        Map<String, Object> direct = new LinkedHashMap<String, Object>(); direct.put("waitMs", directFile);
        assertEquals(Integer.valueOf(5000), ArgumentContracts.coerce("mq.broker.receive", direct).get("waitMs"));

        Object contextResult = evaluator.evaluate("#{str.substr(${params/reference}, 0, 4)}", new ExpressionBlockEvaluator.Resolver() {
            @Override public Object context(String path) { assertEquals("params/reference", path); return "5000-ABC"; }
            @Override public Object call(String name, Map<String, Object> values) { return new DefaultBuiltInProvider().invoke(name, values); }
            @Override public String interpolate(String value) { return value; }
        });
        Map<String, Object> nested = new LinkedHashMap<String, Object>(); nested.put("waitMs", contextResult);
        assertEquals(Integer.valueOf(5000), ArgumentContracts.coerce("mq.broker.receive", nested).get("waitMs"));
    }

    @Test void trimsOnlyScalarIntegerAndBooleanCoercions() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("timeoutMs", " 1200\n");
        arguments.put("overwrite", " TrUe ");
        Map<String, Object> result = ArgumentContracts.coerce("ssh.application.upload", arguments);

        assertEquals(Integer.valueOf(1200), result.get("timeoutMs"));
        assertEquals(Boolean.TRUE, result.get("overwrite"));
        assertEquals(" 1200\n", arguments.get("timeoutMs"));
    }

    @Test void canonicalizesDeclaredNativeEnumsAndRejectsUnknownChoices() {
        Map<String, Object> http = new LinkedHashMap<String, Object>();
        http.put("method", "post"); http.put("requestFormat", "JSON");
        Map<String, Object> normalized = ArgumentContracts.coerce("http.api.request", http);
        assertEquals("POST", normalized.get("method"));
        assertEquals("json", normalized.get("requestFormat"));

        http.put("method", "TRACE");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ArgumentContracts.coerce("http.api.request", http));
        assertTrue(error.getMessage().contains("http.api.request.method"));
    }

    @Test void rejectsInvalidFinalValuesWithArgumentSpecificDiagnostics() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("timeoutMs", "abcd");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ArgumentContracts.coerce("ssh.application.execute", arguments));
        assertTrue(error.getMessage().contains("ssh.application.execute.timeoutMs"));
        assertTrue(error.getMessage().contains("String value 'abcd'"));
    }

    @Test void declaredToolContractsCoerceScalarsAndValidateEnumsWithoutChangingStrings() {
        Map<String, att.config.ToolArgumentConfig> contracts = new LinkedHashMap<String, att.config.ToolArgumentConfig>();
        contracts.put("count", new att.config.ToolArgumentConfig("count", "Count", "Count", true, "", "", "once", "integer", null));
        contracts.put("enabled", new att.config.ToolArgumentConfig("enabled", "Enabled", "Enabled", true, "", "", "once", "boolean", null));
        contracts.put("mode", new att.config.ToolArgumentConfig("mode", "Mode", "Mode", true, "", "", "once", "enum", java.util.Arrays.asList("FAST", "SAFE")));
        contracts.put("text", new att.config.ToolArgumentConfig("text", "Text", "Text", true, "", "", "once", "string", null));
        att.config.ToolConfig tool = new att.config.ToolConfig("typed", "typed", "", "Typed", "Typed",
                java.util.Collections.<String>emptyList(), java.util.Collections.<String>emptyList(), "text", contracts, null);
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("count", " 12\n"); input.put("enabled", " TrUe "); input.put("mode", "SAFE"); input.put("text", "  keep exactly\n");
        Map<String, Object> result = ArgumentContracts.coerce("typed", input, tool);
        assertEquals(Integer.valueOf(12), result.get("count"));
        assertEquals(Boolean.TRUE, result.get("enabled"));
        assertEquals("SAFE", result.get("mode"));
        assertEquals("  keep exactly\n", result.get("text"));

        input.put("mode", "UNKNOWN");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ArgumentContracts.coerce("typed", input, tool));
        assertTrue(error.getMessage().contains("typed.mode"));
    }

    private ExpressionBlockEvaluator.Resolver resolver(String fileContent) {
        return new ExpressionBlockEvaluator.Resolver() {
            @Override public Object context(String path) { throw new AssertionError(path); }
            @Override public Object call(String name, Map<String, Object> arguments) { return new DefaultBuiltInProvider().invoke(name, arguments); }
            @Override public String interpolate(String value) { return value; }
            @Override public String file(String path) { assertEquals("params/wait.txt", path); return fileContent; }
        };
    }
}
