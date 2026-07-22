/* Author: Jeffrey + ChatGPT */
package att.exec;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CommandRunnerTest {
    @TempDir Path tempDir;
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

    @Test void boundsPreviewAndStreamsArtifactWithByteCounts() throws Exception {
        Path stdout = tempDir.resolve("stdout.txt"), stderr = tempDir.resolve("stderr.txt");
        CommandResult result = new CommandRunner().runWithCapture(
                java.util.Arrays.asList("/bin/sh", "-c", "i=0; while [ $i -lt 3000 ]; do printf x; i=$((i+1)); done"),
                Duration.ofSeconds(2), tempDir, java.util.Collections.<String,String>emptyMap(),
                new CommandRunner.CapturePolicy(1024, 2048, stdout, stderr));
        assertEquals(3000, result.stdoutBytes());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stdoutArtifactTruncated());
        assertTrue(result.stdout().contains("ATT output preview truncated"));
        assertEquals(2048, java.nio.file.Files.size(stdout));
        assertEquals(0, java.nio.file.Files.size(stderr));
    }
}
