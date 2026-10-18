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
