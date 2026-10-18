package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactArchiveTest {
    @TempDir Path temporary;

    @Test void writesManifestForThePublishedBytesAndReadsOnlyApprovedEntries() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("output"));
        Path report=output.resolve("report/index.html");Files.createDirectories(report.getParent());
        Files.writeString(report,"<html>password: secret-value</html>",StandardCharsets.UTF_8);
        Files.writeString(output.resolve("untrusted.bin"),"private binary bytes");
        Path archive=temporary.resolve("job/artifacts.zip");

        ArtifactArchive.build(output,archive,file->att.server.api.internal.DiagnosticSanitizer.redactText(Files.readString(file)).getBytes(StandardCharsets.UTF_8));

        byte[] published=ArtifactArchive.read(archive,"report/index.html");
        assertEquals("<html>password: [REDACTED_SECRET]</html>",new String(published,StandardCharsets.UTF_8));
        assertEquals(ArtifactArchive.sha256(published),manifestEntry(archive,"report/index.html").get("sha256"));
        assertTrue(manifestEntry(archive,"report/index.html").get("report").equals(Boolean.TRUE));
        assertThrows(java.io.FileNotFoundException.class,()->ArtifactArchive.read(archive,"untrusted.bin"));
        assertThrows(IllegalArgumentException.class,()->ArtifactArchive.read(archive,"../secret.txt"));
    }

    @Test void rejectsUnsafeAndEncodedArchivePaths() {
        assertFalse(ArtifactArchive.safePath("../secret.txt"));
        assertFalse(ArtifactArchive.safePath("a/%2e%2e/secret.txt"));
        assertFalse(ArtifactArchive.safePath("a\\secret.txt"));
        assertFalse(ArtifactArchive.safePath("/etc/passwd"));
        assertTrue(ArtifactArchive.safePath("report/index.html"));
    }

    private Map<?,?> manifestEntry(Path archive,String path) throws Exception {
        try(ZipFile zip=new ZipFile(archive.toFile());var input=zip.getInputStream(zip.getEntry(ArtifactArchive.MANIFEST))) {
            Map<?,?> manifest=ServerRuntime.JSON.readValue(input,Map.class);
            return ((java.util.List<Map<?,?>>)manifest.get("entries")).stream().filter(entry->path.equals(entry.get("path"))).findFirst().orElseThrow();
        }
    }
}
