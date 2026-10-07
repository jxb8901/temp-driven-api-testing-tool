package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class PathSafetyTest {
    @TempDir Path directory;

    @Test void rejectsParentAndTargetSymlinksBeforeWriting() throws Exception {
        Path root = Files.createDirectory(directory.resolve("case"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.createSymbolicLink(root.resolve("linked"), outside);
        assertThrows(IOException.class, () -> PathSafety.ensureContained(root, root.resolve("linked/secret.txt"), "saveAs"));
        Path file = Files.write(outside.resolve("value.txt"), new byte[]{1});
        Files.createSymbolicLink(root.resolve("value.txt"), file);
        assertThrows(IOException.class, () -> PathSafety.ensureContained(root, root.resolve("value.txt"), "saveAs"));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(file));
        assertDoesNotThrow(() -> PathSafety.ensureContained(root, root.resolve("new/path.txt"), "saveAs"));
        Path linkedRoot = directory.resolve("linked-case");
        Files.createSymbolicLink(linkedRoot, root);
        assertThrows(IOException.class, () -> PathSafety.ensureContained(linkedRoot, linkedRoot.resolve("new/path.txt"), "saveAs"));
    }

    @Test void missingSqlFileIsAPathDiagnosticWithTheConfiguredValue() {
        att.validation.DiagnosticException error = assertThrows(att.validation.DiagnosticException.class,
                () -> new att.exec.DbHelperExecutor(directory, null).resolveSqlFile("sql/missing.sql"));
        assertEquals(att.validation.DiagnosticCodes.PATH_INVALID, error.code());
        assertTrue(error.detail().contains("sql/missing.sql"));
        assertNotNull(error.getCause());
    }
}
