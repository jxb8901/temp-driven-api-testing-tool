package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

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
                "${META.PACKAGE_ROOT}", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "${EXEC.RUN_ID}", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "${EXEC.INPUT.account}", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "${META.TARGET.id}", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "${EXEC.STARTED_AT}", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.debugId(
                "#{env.get(name='HOME')}", "fallback", packageRoot, "tool", "helper"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "../escape", "fallback", packageRoot));
        assertThrows(IllegalArgumentException.class, () -> ExecutionIdentityFormat.runId(
                "   ", "fallback", packageRoot));
    }

    @Test void sourcePathInIdentityFormatUsesLogicalPackageName() throws Exception {
        Path source = packageRoot.resolve("testcase/payment.xlsx");
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.write(source, new byte[0]);
        assertEquals("testcase-payment.xlsx", ExecutionIdentityFormat.runId(
                "#{str.replace(${META.SOURCE.path}, '/', '-')}", "fallback", packageRoot, "testcase", source));
    }

    @Test void keepsExistingDefaultsWhenFormatIsNotConfigured() {
        assertEquals("fallback", ExecutionIdentityFormat.runId("", "fallback", packageRoot));
        assertEquals("fallback", ExecutionIdentityFormat.debugId("", "fallback", packageRoot, "tool", "helper"));
    }

    @Test void validatesFormatsWithoutEvaluatingThemAndUsesTheSuppliedStartTime() {
        assertDoesNotThrow(() -> ExecutionIdentityFormat.validateRunIdFormat(
                "#{str.replace(str.substr(${EXEC.RUN_STARTED_AT}, 0, 10), '-', '')}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionIdentityFormat.validateRunIdFormat("#{seq.next()}"));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionIdentityFormat.validateDebugIdFormat("${EXEC.INPUT.secret}"));

        Instant startedAt=Instant.parse("2026-10-07T01:02:03Z");
        String id=ExecutionIdentityFormat.runId(
                "#{str.replace(str.substr(${EXEC.RUN_STARTED_AT}, 0, 10), '-', '')}",
                "fallback", packageRoot, "testcase", packageRoot, startedAt);
        assertEquals("20261007", id);
        assertEquals("debug-2026-10-07T01-02-03Z", ExecutionIdentityFormat.debugId(
                "debug-#{str.replace(value=${EXEC.STARTED_AT}, target=':', replacement='-')}", "fallback",
                packageRoot, "template", "SIMPLE", packageRoot, startedAt));
    }
}
