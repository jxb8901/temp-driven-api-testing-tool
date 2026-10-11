package att;

import att.api.ExecutionEvent;
import att.validation.Diagnostic;
import att.validation.DiagnosticCodes;
import att.validation.SourceLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkRunnerEventRenderingTest {
    @Test void humanWarningRendersCanonicalDiagnosticLayout() throws Exception {
        Path root = Paths.get("/tmp/att-package").toAbsolutePath().normalize();
        SourceLocation source = new SourceLocation(root.resolve("templates/Pay/template.yaml").toString(),
                8, 11, 8, 14, "      invoke: bad");
        Diagnostic diagnostic = new Diagnostic(DiagnosticCodes.TEMPLATE_INVALID, "Invalid template expression",
                "second line", root.resolve("templates/Pay/template.yaml").toString(), "actions.invoke",
                "Cases", 12, 4, "Pay", "invoke", null, source, null, 2, null);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));
        try {
            att.FrameworkRunner.class.getDeclaredMethod("printEvent", ExecutionEvent.class, CliOptions.class, Path.class)
                    .setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            // exercise through public event observer path below
        } finally {
            System.setErr(previous);
        }

        CliOptions options = CliOptions.parse(new String[]{"run", "--suite", "x.xlsx"});
        java.lang.reflect.Method observer = att.FrameworkRunner.class.getDeclaredMethod(
                "cliObserver", CliOptions.class, Path.class);
        observer.setAccessible(true);
        att.api.ExecutionEventListener listener = (att.api.ExecutionEventListener) observer.invoke(null, options, root);

        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));
        try {
            listener.onEvent(new ExecutionEvent(ExecutionEvent.Type.WARNING, null, null, null, diagnostic, null));
        } finally {
            System.setErr(previous);
        }

        String output = captured.toString(StandardCharsets.UTF_8.name());
        assertTrue(output.contains("[WARNING] ATT-TEMPLATE-001: Invalid template expression\n    second line"), output);
        assertTrue(output.contains("file=$ATT_PACKAGE_ROOT/templates/Pay/template.yaml"), output);
        assertTrue(output.contains("sheet=Cases, row=12, column=4, template=Pay, action=invoke"), output);
        assertTrue(output.contains("sourceFile=$ATT_PACKAGE_ROOT/templates/Pay/template.yaml, line=8, sourceColumn=11"), output);
        assertTrue(output.contains("caseId: Case-12"), output);
        assertTrue(output.contains("actions:\n      invoke: bad\n              ^"), output);
        assertTrue(output.contains("occurrences: 2"), output);
        assertFalse(output.contains(root.toString()), output);
    }
}
