package att.server.api.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageSourceRevisionTest {
    @TempDir Path root;

    @Test void candidateOverlayAndPublishedBytesHaveOneIdentityWithoutManifestAndGeneratedArtifacts() throws Exception {
        Path templates = Files.createDirectories(root.resolve("templates/payments"));
        Path scripts = templates.resolve("request.sh");
        Files.write(scripts, bytes("echo before\n"));
        Path files = Files.createDirectories(root.resolve("files"));
        Path binary = files.resolve("payload.bin");
        Files.write(binary, new byte[]{0, 1, 2, 3});
        Path opaque = files.resolve("payload.unknown");
        Files.write(opaque, new byte[]{5, 6, 7});
        Path custom = Files.createDirectories(root.resolve("custom"));
        Path customScript = custom.resolve("runner.sh");
        Files.write(customScript, bytes("echo original\n"));
        Path output = Files.createDirectories(root.resolve("artifacts")).resolve("report.txt");
        Files.write(output, bytes("generated report\n"));
        String initial = PackageSourceRevision.digest(root);
        assertTrue(PackageSourceRevision.includes("templates/payments/request.sh"));
        assertTrue(PackageSourceRevision.includes("files/payload.bin"));
        assertTrue(PackageSourceRevision.includes("files/payload.unknown"));
        assertTrue(PackageSourceRevision.includes("custom/runner.sh"));
        assertFalse(PackageSourceRevision.includes("package.yaml"));
        assertFalse(PackageSourceRevision.includes("artifacts/report.txt"));

        Files.write(root.resolve("package.yaml"), bytes("schemaVersion: att-package/v1\n"));
        Files.write(output, bytes("changed generated report\n"));
        assertEquals(initial, PackageSourceRevision.digest(root));

        byte[] changed = bytes("echo after\n");
        String candidate = PackageSourceRevision.digest(root,
                Collections.singletonMap("templates/payments/request.sh", changed));
        assertNotEquals(initial, candidate);
        Files.write(scripts, changed);
        assertEquals(candidate, PackageSourceRevision.digest(root));

        Files.write(binary, new byte[]{0, 1, 2, 4});
        String changedBinary = PackageSourceRevision.digest(root);
        assertNotEquals(candidate, changedBinary);
        Files.write(opaque, new byte[]{5, 6, 8});
        String changedOpaque = PackageSourceRevision.digest(root);
        assertNotEquals(changedBinary, changedOpaque);
        Files.write(customScript, bytes("echo updated\n"));
        assertNotEquals(changedOpaque, PackageSourceRevision.digest(root));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
