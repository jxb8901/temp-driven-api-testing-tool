package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunConcurrencyGuardTest {
    @TempDir Path tempDir;

    @Test void rejectIsDefaultExclusivePolicyAndParallelDoesNotAcquireTheLock() throws Exception {
        try (RunConcurrencyGuard first = RunConcurrencyGuard.acquire(tempDir, "reject", () -> { })) {
            assertThrows(IllegalArgumentException.class, () -> RunConcurrencyGuard.acquire(tempDir, "reject", () -> { }));
            assertDoesNotThrow(() -> {
                try (RunConcurrencyGuard ignored = RunConcurrencyGuard.acquire(tempDir, "parallel", () -> { })) { }
            });
        }
    }
}
