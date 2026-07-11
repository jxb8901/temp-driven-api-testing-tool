/* Author: Jeffrey + ChatGPT */
package att.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionOptionsTest {
    @Test void parsesSinglePageAndSelectionOptions() {
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"docs", "--single-page"});
        assertEquals("docs", options.command());
        assertTrue(options.singlePage());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run"}));
    }
}
