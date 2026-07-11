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
}
