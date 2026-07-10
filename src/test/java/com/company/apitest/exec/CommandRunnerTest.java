/* Author: Jeffrey + ChatGPT */
package com.company.apitest.exec;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CommandRunnerTest {
    @Test void runsWithoutShellAndPreservesQuotedArgument() throws Exception {
        CommandResult result = new CommandRunner().run("/bin/echo 'hello world'", Duration.ofSeconds(2));
        assertEquals(0, result.exitCode()); assertEquals("hello world", result.stdout().trim()); assertFalse(result.timedOut());
    }
}
