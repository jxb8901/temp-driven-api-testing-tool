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
        String text=bytes.toString("UTF-8"); assertTrue(text.contains("clean")); assertTrue(text.contains("--all")); assertFalse(text.contains("--single-page"));
    }

    @Test void verboseIsAnExplicitOutputModeAndConflictsWithQuiet() {
        att.core.ExecutionOptions verbose = att.core.ExecutionOptions.parse(new String[]{"run", "--all", "--verbose"});
        assertTrue(verbose.verbose());
        assertFalse(verbose.quiet());
        assertThrows(IllegalArgumentException.class, () -> att.core.ExecutionOptions.parse(new String[]{"run", "--all", "--verbose", "--quiet"}));
    }
}
