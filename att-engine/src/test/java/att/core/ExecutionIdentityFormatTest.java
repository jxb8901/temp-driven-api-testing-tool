package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionIdentityFormatTest {
    @TempDir Path packageRoot;

    @Test void evaluatesAllowedRunAndDebugMetadataAndClockFormats() {
        assertEquals("run-testcase", ExecutionIdentityFormat.runId("run-${META.SOURCE.type}",
                "fallback", packageRoot));
        assertEquals("debug-tool-helper", ExecutionIdentityFormat.debugId("debug-${META.TARGET.type}-${META.TARGET.id}",
                "fallback", packageRoot, "tool", "helper"));
        assertTrue(ExecutionIdentityFormat.runId("run-#{date.systimestamp(format='yyyyMMdd')}",
                "fallback", packageRoot).matches("run-[0-9]{8}"));
    }

    @Test void rejectsUnavailableContextExternalCallsAndUnsafeSegments() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "${EXEC.RUN_ID}", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "${EXEC.INPUT.account}", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "${META.TARGET.id}", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.debugId(
                "#{env.get(name='HOME')}", "fallback", packageRoot, "tool", "helper"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "../escape", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "   ", "fallback", packageRoot));
    }

    @Test void keepsExistingDefaultsWhenFormatIsNotConfigured() {
        assertEquals("fallback", ExecutionIdentityFormat.runId("", "fallback", packageRoot));
        assertEquals("fallback", ExecutionIdentityFormat.debugId("", "fallback", packageRoot, "tool", "helper"));
    }
}
