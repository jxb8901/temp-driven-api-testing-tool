/* Author: Jeffrey + ChatGPT */
package att;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkRunnerTest {
    @Test void helpDocumentsCleanAndAllSelection() throws Exception {
        java.lang.reflect.Method help=FrameworkRunner.class.getDeclaredMethod("help"); help.setAccessible(true);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); PrintStream previous=System.out;
        try { System.setOut(new PrintStream(bytes)); help.invoke(null); } finally { System.setOut(previous); }
        String text=bytes.toString("UTF-8"); assertTrue(text.contains("clean")); assertTrue(text.contains("--all")); assertTrue(text.contains("--update-snapshot")); assertTrue(text.contains("att.bat")); assertFalse(text.contains("--single-page"));
    }

    @Test void verboseIsAnExplicitOutputModeAndConflictsWithQuiet() {
        att.core.ExecutionOptions verbose = att.core.ExecutionOptions.parse(new String[]{"run", "--all", "--verbose"});
        assertTrue(verbose.verbose());
        assertFalse(verbose.quiet());
        assertThrows(IllegalArgumentException.class, () -> att.core.ExecutionOptions.parse(new String[]{"run", "--all", "--verbose", "--quiet"}));
    }

    @Test void snapshotCommandRequiresWorkbookSelectionAndRejectsRunOnlyOptions() {
        assertEquals("snapshot", att.core.ExecutionOptions.parse(new String[]{"snapshot", "--all"}).command());
        assertEquals(1, att.core.ExecutionOptions.parse(new String[]{"snapshot", "--suite", "testcase/payment.xlsx"}).suitePaths().size());
        assertThrows(IllegalArgumentException.class, () -> att.core.ExecutionOptions.parse(new String[]{"snapshot"}));
        assertThrows(IllegalArgumentException.class, () -> att.core.ExecutionOptions.parse(new String[]{"snapshot", "--all", "--tag", "smoke"}));
        att.core.ExecutionOptions update = att.core.ExecutionOptions.parse(new String[]{"run", "--all", "--update-snapshot"});
        assertTrue(update.updateSnapshot());
        assertThrows(IllegalArgumentException.class, () -> att.core.ExecutionOptions.parse(new String[]{"validate", "--package", "--update-snapshot"}));
        assertThrows(IllegalArgumentException.class, () -> att.core.ExecutionOptions.parse(new String[]{"snapshot", "--all", "--update-snapshot"}));
    }

    @Test void typedDiagnosticKeepsRunCodeIndependentFromMessageText() {
        att.validation.DiagnosticException error = new att.validation.DiagnosticException(
                att.validation.DiagnosticCodes.RUN_FAILED, "Run ID already exists",
                "path=/tmp/api-testing-tool/output/X", null, "runId", null, null, null, null, null,
                "Choose another --run-id.", null);
        assertEquals("ATT-RUN-001", error.code());
        assertTrue(error.format().contains("suggestion: Choose another --run-id."));
    }

    @Test void humanValidationDiagnosticsAreIndentedAndSeparated() throws Exception {
        java.util.List<att.validation.Diagnostic> diagnostics = java.util.Arrays.asList(
                new att.validation.Diagnostic("ATT-CTX-001", att.validation.Diagnostic.Severity.ERROR,
                        "Unknown Context\nrequestedPath: missing", "template.yaml", "actions.verify.actual", null, null, null, "VERIFY", "verify", "Use the Context tree."),
                new att.validation.Diagnostic("ATT-TPL-001", att.validation.Diagnostic.Severity.ERROR,
                        "Missing assertion", "template.yaml", "actions.verify.assert", null, null, null, "VERIFY", "verify", "Add an assertion."));
        att.validation.PackageValidator.ValidationSummary summary = new att.validation.PackageValidator.ValidationSummary("package", 1, 1, 1, 0, diagnostics);
        att.core.ExecutionOptions options = att.core.ExecutionOptions.parse(new String[]{"validate", "--package"});
        java.lang.reflect.Method method = FrameworkRunner.class.getDeclaredMethod("printDiagnostics",
                att.validation.PackageValidator.ValidationSummary.class, att.core.ExecutionOptions.class, PrintStream.class, boolean.class);
        method.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        method.invoke(null, summary, options, new PrintStream(bytes), true);
        assertEquals("\n  [ERROR] ATT-CTX-001: Unknown Context\n"
                        + "    requestedPath: missing\n"
                        + "    location: file=template.yaml, field=actions.verify.actual, template=VERIFY, action=verify\n"
                        + "    suggestion: Use the Context tree.\n\n"
                        + "  [ERROR] ATT-TPL-001: Missing assertion\n"
                        + "    location: file=template.yaml, field=actions.verify.assert, template=VERIFY, action=verify\n"
                        + "    suggestion: Add an assertion.\n",
                bytes.toString("UTF-8"));
    }
}
