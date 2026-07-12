package att.core;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

class IdentifierValidatorTest {
    @Test void preservesValidUnicodeIdentifiers() {
        assertEquals("SIT 回歸-01", IdentifierValidator.runId("SIT 回歸-01"));
        assertEquals("付款.TC001", IdentifierValidator.caseId("付款", "TC001"));
        assertEquals("支付.本地.TC001", IdentifierValidator.caseId("支付", "本地", "TC001"));
    }

    @Test void rejectsTraversalIllegalAndReservedNames() {
        for (String value : new String[]{"../x", "a/b", "a\\b", "CON", "name.", " x", "x\n"}) {
            assertThrows(IllegalArgumentException.class, () -> IdentifierValidator.runId(value), value);
        }
        assertThrows(IllegalArgumentException.class, () -> IdentifierValidator.workbookId("payment.regression"));
        assertThrows(IllegalArgumentException.class, () -> IdentifierValidator.caseId("payment", "local.sit", "TC001"));
    }

    @Test void resolvesValidatedNameAsStrictChild() {
        assertEquals(IdentifierValidator.canonicalPath(Paths.get("/tmp/out"), "test").resolve("run-1"), IdentifierValidator.strictChild(Paths.get("/tmp/out"), "run-1", "run"));
    }
    @Test void resolvesExistingParentSymlinkBeforeCreatingChild() throws Exception {
        java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("att-id");
        java.nio.file.Path real = java.nio.file.Files.createDirectories(temp.resolve("real"));
        java.nio.file.Path link = temp.resolve("link");
        try { java.nio.file.Files.createSymbolicLink(link, real); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException e) { return; }
        assertEquals(real.toRealPath().resolve("R1").normalize(), IdentifierValidator.strictChild(link,"R1","run"));
    }
}
