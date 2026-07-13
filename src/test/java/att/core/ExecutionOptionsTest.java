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
    @Test void parsesRunConcurrencyPolicy() {
        assertEquals("reject", ExecutionOptions.parse(new String[]{"run","--all"}).concurrencyMode());
        assertEquals("queue", ExecutionOptions.parse(new String[]{"run","--all","--queue"}).concurrencyMode());
        assertEquals("parallel", ExecutionOptions.parse(new String[]{"run","--all","--parallel"}).concurrencyMode());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run","--all","--queue","--parallel"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"validate","--parallel"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run","--all","--concurrency","parallel"}));
    }
    @Test void rerunFailedIsACompleteSelectionAndAcceptsNarrowingFilters() {
        ExecutionOptions rerun = ExecutionOptions.parse(new String[]{"run","--rerun-failed"});
        assertTrue(rerun.rerunFailed());
        assertNull(rerun.suiteDirectory());
        ExecutionOptions narrowed = ExecutionOptions.parse(new String[]{"run","--rerun-failed","--tag","payment"});
        assertTrue(narrowed.tags().contains("payment"));
    }
}
