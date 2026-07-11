/* Author: Jeffrey + ChatGPT */
package att.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionOptionsTest {
    @Test void parsesDocsAndSelectionOptions() {
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"docs"});
        assertEquals("docs", options.command());
        assertEquals("clean", ExecutionOptions.parse(new String[]{"clean"}).command());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"docs", "--single-page"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run"}));
    }
    @Test void validateDefaultsToPackageAndRejectsCommandSpecificOptions() {
        assertEquals("package", ExecutionOptions.parse(new String[]{"validate"}).validationScope());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"version", "--all"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"validate", "--run-id", "x"}));
    }
    @Test void parsesCiOutputSelectionForRunOnly() {
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run","--all","--ci-output","json"});
        assertEquals(java.util.Collections.singleton("json"), options.ciOutputs());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"validate","--package","--ci-output","junit"}));
    }
}
