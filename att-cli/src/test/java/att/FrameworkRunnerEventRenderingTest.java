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
        source.put("excerpt", "actions:\n      invoke: bad"); source.put("excerptStartLine", 7);
        Map<String,Object> diagnostic = new LinkedHashMap<String,Object>();
        diagnostic.put("code", "ATT-TEMPLATE-001"); diagnostic.put("severity", "WARNING");
        diagnostic.put("message", "Invalid template expression\n    second line");
        diagnostic.put("file", root.resolve("templates/Pay/template.yaml").toString());
        diagnostic.put("field", "actions.invoke"); diagnostic.put("sheet", "Cases"); diagnostic.put("row", 12);
        diagnostic.put("column", 4); diagnostic.put("template", "Pay"); diagnostic.put("action", "invoke");
        diagnostic.put("source", source); diagnostic.put("context", fields("caseId", "Case-12", "stage", "invoke"));
        diagnostic.put("occurrences", 2);

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        PrintCapture capture = new PrintCapture(buffer);
        Method method = FrameworkRunner.class.getDeclaredMethod("printEvent", ExecutionEvent.class, CliOptions.class, Path.class);
        method.setAccessible(true);
        CliOptions options = CliOptions.parse(new String[]{"run", "--suite", "suite.xlsx"});
        method.invoke(null, new ExecutionEvent(ExecutionEvent.Type.WARNING, null, null, null,
                att.validation.Diagnostic.fromMap(diagnostic), null), options, root);

        String output = buffer.toString("UTF-8");
        assertTrue(output.contains("[WARNING] ATT-TEMPLATE-001: Invalid template expression\n    second line"), output);
        assertTrue(output.contains("file=$ATT_PACKAGE_ROOT/templates/Pay/template.yaml"), output);
        assertTrue(output.contains("sheet=Cases, row=12, column=4, template=Pay, action=invoke"), output);
        assertTrue(output.contains("sourceFile=$ATT_PACKAGE_ROOT/templates/Pay/template.yaml, line=8, sourceColumn=11"), output);
        assertTrue(output.contains("caseId: Case-12"), output);
        assertTrue(output.contains("actions:\n      invoke: bad\n              ^"), output);
        assertTrue(output.contains("occurrences: 2"), output);
    }

    private static Map<String,Object> fields(Object... values) {
        Map<String,Object> map = new LinkedHashMap<String,Object>();
        for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private static final class PrintCapture extends java.io.PrintStream {
        private final java.io.ByteArrayOutputStream buffer;
        PrintCapture(java.io.ByteArrayOutputStream buffer) {
            super(buffer, true);
            this.buffer = buffer;
            System.setErr(this);
        }
        @Override public void close() {
            System.setErr(System.out);
            super.close();
        }
    }
}
