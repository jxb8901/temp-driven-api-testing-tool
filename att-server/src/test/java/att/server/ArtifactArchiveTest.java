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
        assertTrue(new String(published,StandardCharsets.UTF_8).contains("password: [REDACTED_SECRET]"));
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
        for(String path:java.util.List.of("case\r\n.txt","a#fragment.log","a?query.txt","CON.txt","nested/trailing.","nested/space "))assertFalse(ArtifactArchive.safePath(path),path);
    }

    @Test void rejectsUnmanifestedEntriesAndTamperedDigestsFromIndependentZips() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("output"));Files.writeString(output.resolve("case.log"),"approved");
        Path good=temporary.resolve("good.zip");ArtifactArchive.build(output,good,Files::readAllBytes);
        Path extra=temporary.resolve("extra.zip");
        rewrite(good,extra,Map.of("private.txt","raw-secret".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->ArtifactArchive.verify(extra,true));
        assertThrows(IllegalArgumentException.class,()->ArtifactArchive.read(extra,"case.log"));
        Path changed=temporary.resolve("changed.zip");rewrite(good,changed,Map.of("case.log","tampered".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->ArtifactArchive.verify(changed,true));
        assertThrows(IllegalArgumentException.class,()->ArtifactArchive.read(changed,"case.log"));
    }

    @Test void rejectsHighCompressionRatiosAndCaseCollisions() throws Exception {
        Path bomb=temporary.resolve("bomb.zip");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(bomb))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("large.log"));zip.write(new byte[1024*1024]);zip.closeEntry();
        }
        assertThrows(IllegalArgumentException.class,()->ArtifactArchive.verify(bomb,false));
        Path collision=temporary.resolve("collision.zip");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(collision))) {
            for(String path:java.util.List.of("a.log","A.log")){zip.putNextEntry(new java.util.zip.ZipEntry(path));zip.write(1);zip.closeEntry();}
        }
        assertThrows(IllegalArgumentException.class,()->ArtifactArchive.verify(collision,false));
    }

    @Test void recordsOmissionsWithoutDisclosingRejectedNamesOrFollowingSymlinks() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("output"));
        Files.writeString(output.resolve("case.log"),"safe");Files.write(output.resolve("secret-binary.bin"),new byte[]{0,1});
        Path outside=Files.writeString(temporary.resolve("secret.txt"),"outside-secret");Files.createSymbolicLink(output.resolve("outside.log"),outside);
        Files.writeString(output.resolve("A.log"),"first");Files.writeString(output.resolve("a.log"),"second");
        Path archive=temporary.resolve("archive.zip");ArtifactArchive.build(output,archive,Files::readAllBytes);
        Map<String,Object> manifest=ArtifactArchive.verify(archive,true);
        assertEquals(false,manifest.get("complete"));
        assertFalse(manifest.toString().contains("secret-binary"));assertFalse(manifest.toString().contains("outside.log"));
        assertThrows(java.io.FileNotFoundException.class,()->ArtifactArchive.read(archive,"outside.log"));
    }

    @Test void reportDiscoveryInventoryDoesNotDependOnListingLimitsAndKeepsRelativeBytes() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("output"));
        for(String workload:java.util.List.of("one","two")) {
            Path samples=Files.createDirectories(output.resolve("load/run/samples/"+workload));
            for(int i=0;i<600;i++)Files.writeString(samples.resolve(i+".log"),"sample "+i);
        }
        Path report=output.resolve("load/run/report/index.html");Files.createDirectories(report.getParent());
        String html="<a href=\"../load-summary.json\">Summary</a>";Files.writeString(report,html);
        Files.writeString(output.resolve("load/run/load-summary.json"),"{\"businessValue\":\"keep-me\"}");
        Path archive=temporary.resolve("archive.zip");ArtifactArchive.build(output,archive,Files::readAllBytes);
        assertEquals(1202,((java.util.List<?>)ArtifactArchive.verify(archive,true).get("entries")).size());
        assertTrue(new String(ArtifactArchive.read(archive,"load/run/report/index.html"),StandardCharsets.UTF_8).contains("href=\"../load-summary.json\""));
    }

    @Test void publisherFailureLeavesRawOutputAndNoPublishedOrStagingArchive() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("output"));Files.writeString(output.resolve("case.log"),"raw");
        Path archive=temporary.resolve("job/artifacts.zip");
        assertThrows(java.io.IOException.class,()->ArtifactArchive.build(output,archive,file->{throw new java.io.IOException("disk full");}));
        assertEquals("raw",Files.readString(output.resolve("case.log")));assertFalse(Files.exists(archive));
        try(var paths=Files.list(archive.getParent())){assertEquals(0,paths.count());}
    }

    @Test void rejectsLinkAndSpecialFileAttributesEvenWithValidManifestAndDigests() throws Exception {
        Path good = simpleArchive();
        byte[] original = Files.readAllBytes(good);
        int central = signature(original, 0x02014b50);
        for (int mode : new int[] {0120777, 0010644, 0060644, 0040755}) {
            byte[] bytes = original.clone();
            bytes[central + 5] = 3; // Unix "made by" system.
            put32(bytes, central + 38, mode << 16);
            Path hostile = Files.write(temporary.resolve("mode-" + mode + ".zip"), bytes);
            assertRejectedArchive(hostile);
        }
        byte[] reparse = original.clone();
        put32(reparse, central + 38, 0x400);
        assertRejectedArchive(Files.write(temporary.resolve("reparse.zip"), reparse));
        byte[] regular = original.clone();
        regular[central + 5] = 3;
        put32(regular, central + 38, 0100644 << 16);
        ArtifactArchive.verify(Files.write(temporary.resolve("regular.zip"), regular), true);
    }

    @Test void rejectsLocalNameDisagreementHiddenPrefixAndUnapprovedComments() throws Exception {
        byte[] original = Files.readAllBytes(simpleArchive());
        byte[] mismatch = original.clone();
        System.arraycopy("../a.log".getBytes(StandardCharsets.UTF_8), 0, mismatch, 30, 8);
        assertRejectedArchive(Files.write(temporary.resolve("local-name.zip"), mismatch));

        byte[] prefixed = new byte[original.length + 4];
        System.arraycopy(original, 0, prefixed, 4, original.length);
        for (int cursor = 4; cursor < prefixed.length - 4; cursor++) {
            if (integer(prefixed, cursor) == 0x02014b50)
                put32(prefixed, cursor + 42, integer(prefixed, cursor + 42) + 4);
        }
        int end = prefixed.length - 22;
        put32(prefixed, end + 16, integer(prefixed, end + 16) + 4);
        assertRejectedArchive(Files.write(temporary.resolve("prefix.zip"), prefixed));

        byte[] comment = java.util.Arrays.copyOf(original, original.length + 6);
        comment[original.length - 2] = 6;
        System.arraycopy("SECRET".getBytes(StandardCharsets.UTF_8), 0, comment, original.length, 6);
        assertRejectedArchive(Files.write(temporary.resolve("comment.zip"), comment));
    }

    @Test void acceptsIndependentDeflateButRejectsFilenameOverrideExtraFields() throws Exception {
        Path good = simpleArchive(), deflated = temporary.resolve("deflated.zip");
        rewrite(good, deflated, Map.of());
        ArtifactArchive.verify(deflated, true);
        assertEquals("approved", new String(ArtifactArchive.read(deflated, "case.log"), StandardCharsets.UTF_8));
        Path hostile = temporary.resolve("unicode-name.zip");
        try (ZipFile source = new ZipFile(good.toFile());
             var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(hostile))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var copy = new java.util.zip.ZipEntry(entry.getName());
                if ("case.log".equals(entry.getName())) {
                    byte[] extra = new byte[4 + 5 + 8];
                    extra[0] = 0x75; extra[1] = 0x70; extra[2] = 13; extra[4] = 1;
                    var crc = new java.util.zip.CRC32(); crc.update("case.log".getBytes(StandardCharsets.UTF_8));
                    put32(extra, 5, (int) crc.getValue());
                    System.arraycopy("../a.log".getBytes(StandardCharsets.UTF_8), 0, extra, 9, 8);
                    copy.setExtra(extra);
                }
                zip.putNextEntry(copy);
                try (var input = source.getInputStream(entry)) { input.transferTo(zip); }
                zip.closeEntry();
            }
        }
        assertRejectedArchive(hostile);
    }

    @Test void rejectsFileDirectoryPrefixCollisionsInEitherOrder() throws Exception {
        for (boolean reverse : new boolean[] {false, true}) {
            Path archive = temporary.resolve("prefix-collision-" + reverse + ".zip");
            var names = reverse ? java.util.List.of("a.log/child.txt", "a.log-b.txt", "a.log") : java.util.List.of("a.log", "a.log-b.txt", "a.log/child.txt");
            var rows = new java.util.ArrayList<Map<String, Object>>();
            byte[] content = "approved".getBytes(StandardCharsets.UTF_8);
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
                for (String name : names) {
                    rows.add(Map.of("path", name, "mediaType", "text/plain", "size", content.length,
                            "sha256", ArtifactArchive.sha256(content), "classification", "textual-output",
                            "decision", "sanitized-once", "report", false, "preview", true));
                    zip.putNextEntry(new java.util.zip.ZipEntry(name)); zip.write(content); zip.closeEntry();
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(ArtifactArchive.MANIFEST));
                zip.write(ServerRuntime.JSON.writeValueAsBytes(Map.of("version", 1, "policy", "att-export-safe-text-v2", "entries", rows,
                        "complete", true, "omissions", java.util.List.of())));
                zip.closeEntry();
            }
            assertRejectedArchive(archive);
        }
    }

    @Test void rejectsAmbiguousOrUnapprovedManifestMetadata() throws Exception {
        Path good = simpleArchive();
        String original;
        try (ZipFile zip = new ZipFile(good.toFile()); var input = zip.getInputStream(zip.getEntry(ArtifactArchive.MANIFEST))) {
            original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var mutations = java.util.List.of(
                original.replace("\"version\":1", "\"version\":1.5"),
                original.replace("\"size\":8", "\"size\":8.5"),
                original.replace("\"version\":1", "\"version\":2,\"version\":1"),
                original + " {\"private\":\"SECRET\"}",
                original.replace("\"version\":1", "\"private\":\"SECRET\",\"version\":1"),
                original.replace("\"path\":", "\"private\":\"SECRET\",\"path\":"),
                original.replace("\"omissions\":[]", "\"omissions\":[{\"count\":1,\"reason\":\"SECRET\"}]"),
                original.replace("\"complete\":true", "\"complete\":false"));
        for (int i = 0; i < mutations.size(); i++) {
            assertNotEquals(original, mutations.get(i));
            Path archive = temporary.resolve("manifest-" + i + ".zip");
            rewrite(good, archive, Map.of(ArtifactArchive.MANIFEST, mutations.get(i).getBytes(StandardCharsets.UTF_8)));
            assertRejectedArchive(archive);
        }
    }

    @Test void rejectsConflictingHeaderSizesCrcMethodsFlagsAndOverlappingRecords() throws Exception {
        byte[] original = Files.readAllBytes(simpleArchive());
        int central = signature(original, 0x02014b50);
        int second = central + 46 + 8; // First entry has no extra fields or comment.
        java.util.List<java.util.function.Consumer<byte[]>> mutations = java.util.List.of(
                bytes -> put32(bytes, 14, integer(bytes, 14) ^ 1),
                bytes -> bytes[8] = 8,
                bytes -> bytes[6] = 1,
                bytes -> put32(bytes, central + 20, 9),
                bytes -> put32(bytes, second + 42, 0),
                bytes -> bytes[central + 8] |= 1,
                bytes -> bytes[central + 10] = 99,
                bytes -> bytes[bytes.length - 18] = 1);
        for (int i = 0; i < mutations.size(); i++) {
            byte[] bytes = original.clone(); mutations.get(i).accept(bytes);
            Path archive = Files.write(temporary.resolve("headers-" + i + ".zip"), bytes);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ArtifactArchive.verify(archive, true));
            assertEquals("Unsafe export ZIP structure", error.getMessage());
        }
    }

    @Test void preservesUtf8NamesAndNormalTimestampMetadata() throws Exception {
        Path output = Files.createDirectory(temporary.resolve("unicode-output"));
        String name = "caf\u00e9.log";
        Files.writeString(output.resolve(name), "approved");
        Path good = ArtifactArchive.build(output, temporary.resolve("unicode-source.zip"), Files::readAllBytes);
        Path archive = temporary.resolve("timestamps.zip");
        try (ZipFile source = new ZipFile(good.toFile());
             var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var copy = new java.util.zip.ZipEntry(entry.getName());
                copy.setExtra(new byte[] {0x55, 0x54, 5, 0, 1, 0, 0, 0, 0});
                zip.putNextEntry(copy);
                try (var input = source.getInputStream(entry)) { input.transferTo(zip); }
                zip.closeEntry();
            }
        }
        ArtifactArchive.verify(archive, true);
        assertEquals("approved", new String(ArtifactArchive.read(archive, name), StandardCharsets.UTF_8));
    }

    private Path simpleArchive() throws Exception {
        Path output = Files.createDirectory(temporary.resolve("fixture-output"));
        Files.writeString(output.resolve("case.log"), "approved");
        return ArtifactArchive.build(output, temporary.resolve("fixture.zip"), Files::readAllBytes);
    }
    private void assertRejectedArchive(Path archive) throws Exception {
        // These fixtures remain readable to the JDK and have intact approved payloads.
        try (ZipFile zip = new ZipFile(archive.toFile())) { assertNotNull(zip.getEntry(ArtifactArchive.MANIFEST)); }
        assertThrows(IllegalArgumentException.class, () -> ArtifactArchive.verify(archive, true));
        assertThrows(IllegalArgumentException.class, () -> ArtifactArchive.read(archive, "case.log"));
    }
    private static int signature(byte[] bytes, int signature) {
        for (int i = 0; i < bytes.length - 4; i++) if (integer(bytes, i) == signature) return i;
        throw new AssertionError("Missing ZIP signature");
    }
    private static int integer(byte[] bytes, int offset) {
        return java.nio.ByteBuffer.wrap(bytes, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
    }
    private static void put32(byte[] bytes, int offset, int value) {
        java.nio.ByteBuffer.wrap(bytes, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    private void rewrite(Path source,Path target,Map<String,byte[]> replacements) throws Exception {
        java.util.Set<String> copied=new java.util.HashSet<>();
        try(ZipFile original=new ZipFile(source.toFile());var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(target))) {
            var entries=original.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();copied.add(entry.getName());zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if(replacements.containsKey(entry.getName()))zip.write(replacements.get(entry.getName()));
                else try(var input=original.getInputStream(entry)){input.transferTo(zip);}zip.closeEntry();
            }
            for(var entry:replacements.entrySet())if(!copied.contains(entry.getKey())){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
    }

    private Map<?,?> manifestEntry(Path archive,String path) throws Exception {
        try(ZipFile zip=new ZipFile(archive.toFile());var input=zip.getInputStream(zip.getEntry(ArtifactArchive.MANIFEST))) {
            Map<?,?> manifest=ServerRuntime.JSON.readValue(input,Map.class);
            return ((java.util.List<Map<?,?>>)manifest.get("entries")).stream().filter(entry->path.equals(entry.get("path"))).findFirst().orElseThrow();
        }
    }
}
