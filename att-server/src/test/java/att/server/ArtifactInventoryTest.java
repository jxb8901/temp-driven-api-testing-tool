package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactInventoryTest {
    @TempDir Path temporary;

    @Test void historicalReservationsDoNotConsumeInventoryOrCauseOmissions() throws Exception {
        Path output = Files.createDirectory(temporary.resolve("output"));
        // More than 100k private files across sibling run namespaces, including
        // a report-shaped file which must never be handed to the publisher.
        for (String run : List.of("one", "two")) {
            Path reservations = Files.createDirectories(output.resolve("load/" + run + "/.exec-id-reservations"));
            for (int i = 0; i < 50_001; i++) Files.createFile(reservations.resolve(i + ".id"));
            Files.writeString(reservations.resolve("private.json"), "private");
        }
        Files.writeString(output.resolve("report.json"), "{}");
        long started = System.nanoTime();
        Path archive = temporary.resolve("artifacts.zip");
        if (Boolean.getBoolean("att.inventory.baseline")) {
            assertThrows(IllegalArgumentException.class, () -> ArtifactArchive.build(output, archive, Files::readAllBytes));
        } else {
            ArtifactArchive.build(output, archive, source -> {
                assertEquals(output.resolve("report.json").toRealPath(), source);
                return Files.readAllBytes(source);
            });
            var manifest = ArtifactArchive.verify(archive, true);
            assertEquals(true, manifest.get("complete"));
            assertEquals(List.of(), manifest.get("omissions"));
            assertEquals(1, ((List<?>) manifest.get("entries")).size());
        }
        System.out.println("INVENTORY_BENCHMARK privateFiles=100004 baseline=" + Boolean.getBoolean("att.inventory.baseline")
                + " publicationMs=" + (System.nanoTime() - started) / 1_000_000.0);
    }

    @Test void reservationFilesAtCandidateBoundaryArePrunedAcrossSiblingRunNamespaces() throws Exception {
        Path output = Files.createDirectory(temporary.resolve("boundary-output"));
        int filesPerRun = ArtifactArchive.MAX_CANDIDATES / 2;
        for (String run : List.of("one", "two")) {
            Path reservations = Files.createDirectories(output.resolve("load/" + run + "/.exec-id-reservations"));
            for (int i = 0; i < filesPerRun; i++) Files.createFile(reservations.resolve(i + ".id"));
        }
        Files.writeString(output.resolve("load/report.json"), "{\"status\":\"PASS\"}");

        long started = System.nanoTime();
        Path archive = temporary.resolve("boundary-artifacts.zip");
        ArtifactArchive.build(output, archive, source -> {
            assertEquals(output.resolve("load/report.json").toRealPath(), source);
            return Files.readAllBytes(source);
        });

        Map<?, ?> manifest = ArtifactArchive.verify(archive, true);
        assertEquals(true, manifest.get("complete"));
        assertEquals(List.of(), manifest.get("omissions"));
        assertEquals(1, ((List<?>) manifest.get("entries")).size());
        assertEquals("{\"status\":\"PASS\"}",
                new String(ArtifactArchive.read(archive, "load/report.json"), java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("INVENTORY_BENCHMARK privateFiles=" + ArtifactArchive.MAX_CANDIDATES
                + " siblingRunNamespaces=2 publicationMs=" + (System.nanoTime() - started) / 1_000_000.0);
    }

    @Test void similarlyNamedDirectoriesAndSymlinksAreNotTrustedPrivateSubtrees() throws Exception {
        Path output = Files.createDirectory(temporary.resolve("output"));
        Path alias = Files.createDirectories(output.resolve("load/run/.EXEC-ID-RESERVATIONS"));
        Files.writeString(alias.resolve("case.log"), "eligible");
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.json"), "private");
        Path link = output.resolve("load/other/.exec-id-reservations");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        Path archive = temporary.resolve("archive.zip");
        ArtifactArchive.build(output, archive, Files::readAllBytes);
        assertEquals("eligible", new String(ArtifactArchive.read(archive, "load/run/.EXEC-ID-RESERVATIONS/case.log"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(false, ArtifactArchive.verify(archive, true).get("complete"));
        assertThrows(java.io.FileNotFoundException.class, () -> ArtifactArchive.read(archive, "load/other/.exec-id-reservations/secret.json"));
    }

    @Test void untrustedCandidateAndDepthLimitsRemainEnforced() throws Exception {
        Path output = Files.createDirectory(temporary.resolve("output"));
        for (int i = 0; i < ArtifactArchive.MAX_CANDIDATES; i++) Files.createFile(output.resolve(i + ".bin"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArtifactArchive.build(output, temporary.resolve("limit.zip"), Files::readAllBytes))
                .getMessage().contains("inventory"));
        Files.delete(output.resolve("0.bin"));
        Path boundary = temporary.resolve("boundary.zip");
        ArtifactArchive.build(output, boundary, Files::readAllBytes);
        assertEquals(false, ArtifactArchive.verify(boundary, true).get("complete"));
        Path deep = Files.createDirectory(temporary.resolve("deep"));
        Path directory = deep;
        for (int i = 0; i < 64; i++) directory = Files.createDirectory(directory.resolve("a"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArtifactArchive.build(deep, temporary.resolve("deep.zip"), Files::readAllBytes))
                .getMessage().contains("depth"));
    }
}
