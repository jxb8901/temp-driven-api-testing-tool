package att.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthoringSourceSupportTest {
    @TempDir Path temp;

    @Test void rejectsOversizedMalformedUtf8ProtectedAndSymlinkedSources() throws Exception {
        Path root=Files.createDirectories(temp.resolve("package"));
        Path source=root.resolve("source.yaml");

        Files.writeString(source,"12345");
        assertThrows(PackageResourceInspector.ResponseTooLargeException.class,
                ()->AuthoringSourceSupport.read(root,source,"source.yaml",4));

        Files.write(source,new byte[]{(byte)0xc3,(byte)0x28});
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,source,"source.yaml",16));

        Files.writeString(source,"apiKey: unique-token-123\n");
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,source,"source.yaml",128));

        Path protectedStore=Files.writeString(root.resolve("credentials.yaml"),"private: arbitrary opaque value\n");
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,protectedStore,"credentials.yaml",128));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.absent("credentials.yaml"),
                "An absent protected path must not disclose existence through a different response");

        Path target=Files.writeString(temp.resolve("outside.yaml"),"schemaVersion: att-template/v1\n");
        Path link=root.resolve("linked.yaml");
        try {
            Files.createSymbolicLink(link,target);
            assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                    ()->AuthoringSourceSupport.read(root,link,"linked.yaml",128));
        } catch(UnsupportedOperationException|java.nio.file.FileSystemException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symlink creation is unavailable on this filesystem");
        }
    }

    @Test void absentSourceHasStableEmptyByteEtag() throws Exception {
        java.util.Map<String,Object> result=AuthoringSourceSupport.absent("package.yaml");
        assertEquals(Boolean.FALSE,result.get("present"));assertEquals("",result.get("text"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                result.get("revision"));
        assertEquals("\"sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"",
                result.get("sourceEtag"));
    }
}
