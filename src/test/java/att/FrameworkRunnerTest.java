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
        String text=bytes.toString("UTF-8"); assertTrue(text.contains("clean")); assertTrue(text.contains("--all")); assertTrue(text.contains("att.bat")); assertFalse(text.contains("--single-page"));
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
    }

    @Test void typedDiagnosticKeepsRunCodeIndependentFromMessageText() {
        att.validation.DiagnosticException error = new att.validation.DiagnosticException(
                att.validation.DiagnosticCodes.RUN_FAILED, "Run ID already exists",
                "path=/tmp/api-testing-tool/output/X", null, "runId", null, null, null, null, null,
                "Choose another --run-id.", null);
        assertEquals("ATT-RUN-001", error.code());
        assertTrue(error.format().contains("suggestion: Choose another --run-id."));
    }
}
