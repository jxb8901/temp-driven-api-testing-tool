package att;

import att.api.ExecutionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameworkRunnerEventRenderingTest {
    @TempDir Path root;

    @Test void validationDiagnosticEventKeepsFullContextAndPortableSourceLocation() throws Exception {
        Map<String,Object> source = new LinkedHashMap<String,Object>();
        source.put("file", root.resolve("templates/Pay/template.yaml").toString());
        source.put("line", 8); source.put("column", 11); source.put("endLine", 8); source.put("endColumn", 12);
        source.put("excerpt", "actions:\n  invoke: bad"); source.put("excerptStartLine", 7);
        Map<String,Object> diagnostic = new LinkedHashMap<String,Object>();
        diagnostic.put("code", "ATT-TEMPLATE-001"); diagnostic.put("severity", "WARNING");
        diagnostic.put("message", "Invalid template expression\nsecond line");
        diagnostic.put("file", root.resolve("templates/Pay/template.yaml").toString());
        diagnostic.put("field", "actions.invoke"); diagnostic.put("sheet", "Cases"); diagnostic.put("row", 12);
        diagnostic.put("column", 4); diagnostic.put("template", "Pay"); diagnostic.put("action", "invoke");
        diagnostic.put("source", source); diagnostic.put("context", fields("caseId", "Case-12", "stage", "invoke"));
        diagnostic.put("schemaViolations", Arrays.asList(fields("path", "actions.invoke", "keyword", "type", "message", "must be a mapping")));
        diagnostic.put("occurrences", 2); diagnostic.put("affectedCases", Arrays.asList(fields("caseId", "Case-12")));
        diagnostic.put("suggestion", "Fix the expression.");
        Map<String,Object> data = fields("event", "VALIDATION_DIAGNOSTIC", "diagnostic", diagnostic);
        ExecutionEvent event = new ExecutionEvent(ExecutionEvent.Type.LOG, null, null, null, null,
                null, null, null, data);
        Method render = FrameworkRunner.class.getDeclaredMethod("renderExecutionEvent", ExecutionEvent.class, Path.class);
        render.setAccessible(true);
        String output = (String) render.invoke(null, event, root);
        assertTrue(output.contains("[WARNING] ATT-TEMPLATE-001: Invalid template expression\n    second line"), output);
        assertTrue(output.contains("file=$ATT_HOME/templates/Pay/template.yaml"), output);
        assertTrue(output.contains("sheet=Cases, row=12, column=4, template=Pay, action=invoke"), output);
        assertTrue(output.contains("sourceFile=$ATT_HOME/templates/Pay/template.yaml, line=8, sourceColumn=11"), output);
        assertTrue(output.contains("caseId: Case-12"), output);
        assertTrue(output.contains("actions:\n      invoke: bad\n              ^"), output);
        assertTrue(output.contains("occurrences: 2"), output);
        assertTrue(output.contains("affected cases:\n      - caseId=Case-12"), output);
        assertTrue(output.contains("[type] must be a mapping"), output);
        assertTrue(output.contains("suggestion: Fix the expression."), output);
    }

    @Test void validationPassProgressRendersTheCliSummaryOnceFromTheStructuredEvent() throws Exception {
        Map<String,Object> data = fields("event", "RUN_VALIDATION_SUMMARY", "productVersion", "4.0.1",
                "suites", 2, "cases", 5, "templates", 3, "tools", 4);
        ExecutionEvent event = new ExecutionEvent(ExecutionEvent.Type.PROGRESS, "run-1", null, null, null,
                "VALIDATION_PASS", null, "Validation passed", data);
        Method render = FrameworkRunner.class.getDeclaredMethod("renderExecutionEvent", ExecutionEvent.class, Path.class);
        render.setAccessible(true);
        assertEquals("[1/4] V4.0.1 validation PASS: 2 suites, 5 cases, 3 templates, 4 tools",
                render.invoke(null, event, root));
    }

    private static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (int i=0;i+1<pairs.length;i+=2) result.put(String.valueOf(pairs[i]), pairs[i+1]);
        return result;
    }
}
