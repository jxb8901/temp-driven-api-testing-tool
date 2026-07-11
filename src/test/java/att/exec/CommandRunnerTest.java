/* Author: Jeffrey + ChatGPT */
package att.exec;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CommandRunnerTest {
    @Test void runsWithoutShellAndPreservesQuotedArgument() throws Exception {
        CommandResult result = new CommandRunner().run("/bin/echo 'hello world'", Duration.ofSeconds(2));
        assertEquals(0, result.exitCode()); assertEquals("hello world", result.stdout().trim()); assertFalse(result.timedOut());
    }

    @Test void preservesEmptyQuotedArguments() throws Exception {
        CommandResult result = new CommandRunner().run("/bin/sh -c 'printf \"%s|%s|%s\" \"$1\" \"$2\" \"$3\"' _ a '' b", Duration.ofSeconds(2));
        assertEquals(0, result.exitCode());
        assertEquals("a||b", result.stdout().trim());
        assertFalse(result.timedOut());
    }
}
