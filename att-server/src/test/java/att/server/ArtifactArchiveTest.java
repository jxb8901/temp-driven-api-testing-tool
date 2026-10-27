package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactArchiveTest {
    @TempDir Path temporary;

    @Test void unsafeProducerOutputIsOmittedWithoutAnArchiveRedactionPass() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("mixed-approval-output"));
        Files.writeString(output.resolve("safe.log"),"safe business payload\n");
        Files.writeString(output.resolve("raw-secret.log"),"password=producer-secret\n");
        assertThrows(IllegalArgumentException.class,
                () -> ServerRuntime.approvedArtifactBytes(output.resolve("raw-secret.log")));
        Path archive=temporary.resolve("mixed-approval.zip");
        ArtifactArchive.build(output,archive,ServerRuntime::validateApprovedArtifactBytes);
        Map<String,Object> manifest=ArtifactArchive.verify(archive,true);
        assertEquals(false,manifest.get("complete"));
        assertEquals("safe business payload\n",new String(ArtifactArchive.read(archive,"safe.log"),StandardCharsets.UTF_8));
        assertThrows(java.io.FileNotFoundException.class,()->ArtifactArchive.read(archive,"raw-secret.log"));
    }

    @Test void preservesProducerBytesAndDigestsForReportsAndCommitManifest() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("byte-preserving-output"));
        Map<String,String> sources=Map.of(
                "examples/load-summary.json", "{\"payload\":\"keep exact bytes\\n\"}",
                "examples/load-summary.yaml", "payload: '  keep  spaces  '\n",
                "examples/report/index.html", "<!doctype html><html><script>window.T=1</script><a href=\"../missing.log\">link</a></html>",
                "examples/report-commit-manifest.json", "{\"sha256\":\"source-defined\"}");
        for(Map.Entry<String,String> entry:sources.entrySet()) {
            Path file=output.resolve(entry.getKey());Files.createDirectories(file.getParent());
            Files.writeString(file,entry.getValue(),StandardCharsets.UTF_8);
        }
        Path zip=temporary.resolve("byte-preserved.zip");
        ArtifactArchive.build(output,zip,ServerRuntime::validateApprovedArtifactBytes);
        Map<String,Object> manifest=ArtifactArchive.verify(zip,true);
        assertEquals("att-export-approved-bytes-v1",manifest.get("policy"));
        for(String path:sources.keySet()) {
            byte[] original=Files.readAllBytes(output.resolve(path));
            byte[] extracted=ArtifactArchive.read(zip,path);
            assertArrayEquals(original,extracted,path);
            assertEquals(ArtifactArchive.sha256(original),manifestEntry(zip,path).get("sha256"),path);
        }
    }

    @Test void legacySanitizedManifestVerificationReturnsStoredZipBytesWithoutRewriting() throws Exception {
        Path archive=temporary.resolve("legacy-sanitized.zip");
        byte[] original="previously approved producer bytes".getBytes(StandardCharsets.UTF_8);
        Map<String,Object> row=Map.of("path","case.log","mediaType","text/plain","size",original.length,
                "sha256",ArtifactArchive.sha256(original),"classification","textual-output",
                "decision","sanitized-once","report",false,"preview",true);
        Map<String,Object> manifest=Map.of("version",1,"policy","att-export-safe-text-v2",
                "entries",List.of(row),"complete",true,"omissions",List.of());
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("case.log"));zip.write(original);zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry(ArtifactArchive.MANIFEST));
            zip.write(ServerRuntime.JSON.writeValueAsBytes(manifest));zip.closeEntry();
        }

        assertEquals("att-export-safe-text-v2",ArtifactArchive.verify(archive,true).get("policy"));
        assertArrayEquals(original,ArtifactArchive.read(archive,"case.log"));
    }

    @Test void detailSidecarsTakePriorityOverSurplusEvidenceAtEntryCap() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("evidence-heavy-output"));
        Path report=output.resolve("reports/load-summary.json");Files.createDirectories(report.getParent());
        Files.writeString(report,"{\"schemaVersion\":\"att-load-summary/v1.3\"}");
        Path detail=output.resolve("reports/details/v1/manifest.json");Files.createDirectories(detail.getParent());
        Files.writeString(detail,"{\"schemaVersion\":\"att-load-detail-manifest/v1\"}");
        Path evidence=Files.createDirectories(output.resolve("reports/samples"));
        for(int i=0;i<ArtifactArchive.MAX_ENTRIES+100;i++)Files.writeString(evidence.resolve(String.format("%05d.log",i)),"evidence");
        Path zip=temporary.resolve("evidence-heavy.zip");
        ArtifactArchive.build(output,zip);
        Map<String,Object> manifest=ArtifactArchive.verify(zip,true);
        assertEquals(false,manifest.get("complete"));
        assertEquals(ArtifactArchive.MAX_ENTRIES,((List<?>)manifest.get("entries")).size());
        assertArrayEquals(Files.readAllBytes(report),ArtifactArchive.read(zip,"reports/load-summary.json"));
        assertArrayEquals(Files.readAllBytes(detail),ArtifactArchive.read(zip,"reports/details/v1/manifest.json"));
    }

    @Test void preservesProducerHtmlBytesAndReadsOnlyApprovedEntries() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("output"));
        Path report=output.resolve("report/index.html");Files.createDirectories(report.getParent());
        byte[] original="<html>password: secret-value<script>window.payload = '<raw>'</script></html>".getBytes(StandardCharsets.UTF_8);
        Files.write(report,original);
        Files.writeString(output.resolve("untrusted.bin"),"private binary bytes");
        Path archive=temporary.resolve("job/artifacts.zip");

        ArtifactArchive.build(output,archive);

        byte[] published=ArtifactArchive.read(archive,"report/index.html");
        assertArrayEquals(original,published,"Archive must retain Engine/Worker HTML bytes without rewriting");
        assertEquals(ArtifactArchive.sha256(original),manifestEntry(archive,"report/index.html").get("sha256"));
        assertTrue(manifestEntry(archive,"report/index.html").get("report").equals(Boolean.TRUE));
        assertThrows(java.io.FileNotFoundException.class,()->ArtifactArchive.read(archive,"untrusted.bin"));
        assertThrows(IllegalArgumentException.class,()->ArtifactArchive.read(archive,"../secret.txt"));
    }

    @Test void cappedMemoryAndSpillPublishExactlyTheSameProducerSnapshot() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("staging-output"));
        Map<String,String> sources=Map.of("a.log","producer password: [REDACTED_SECRET]\n"+"x".repeat(300),
                "b.log","producer password: [REDACTED_SECRET]\n"+"y".repeat(2048),
                "index.html","<a href=\"a.log\">A</a><a href=\"b.log\">B</a><a href=\"private.bin\">Omitted</a>");
        Files.writeString(output.resolve("private.bin"),"not publishable");
        Path disk=temporary.resolve("disk/artifacts.zip"),memory=temporary.resolve("memory/artifacts.zip");
        for(long budget:new long[]{0,1024}) {
            for(var source:sources.entrySet())Files.writeString(output.resolve(source.getKey()),source.getValue());
            var mutated=new java.util.concurrent.atomic.AtomicBoolean();
            ArtifactMetricsRecorder recorder=new ArtifactMetricsRecorder(System.nanoTime(),1);
            ArtifactMetricsRecorder.CURRENT.set(recorder);
            try {
                ArtifactArchive.build(output,budget==0?disk:memory,budget,path->{
                    if(mutated.compareAndSet(false,true))for(String name:sources.keySet())
                        Files.writeString(output.resolve(name),"password: changed-after-snapshot");
                });
            } finally {recorder.finish();ArtifactMetricsRecorder.CURRENT.remove();}
            assertTrue(mutated.get());
            assertTrue(recorder.value.memoryExportPeakBytes<=budget);
            assertEquals(budget==0?3:1,recorder.value.temporaryExportFiles.longValue());
            assertTrue(recorder.value.temporaryExportBytes>2048);
            assertEquals(recorder.value.temporaryExportBytes,recorder.value.temporaryExportReadBytes,
                    "Spilled producer bytes are read once for ZIP publication");
            assertEquals(recorder.value.zipWriteInputBytes,recorder.value.verificationBytesRead,
                    "Publication digest verification rereads exactly the bytes written to ZIP entries");
        }
        assertEquals(ArtifactArchive.verify(disk,true),ArtifactArchive.verify(memory,true));
        for(String path:sources.keySet()) {
            byte[] expected=ArtifactArchive.read(disk,path),actual=ArtifactArchive.read(memory,path);
            assertArrayEquals(expected,actual);
            assertArrayEquals(sources.get(path).getBytes(StandardCharsets.UTF_8),actual,"ZIP entries must equal the pre-write producer snapshot");
        }
        String html=new String(ArtifactArchive.read(memory,"index.html"),StandardCharsets.UTF_8);
        assertTrue(html.contains("href=\"a.log\""));assertTrue(html.contains("href=\"b.log\""));
        assertTrue(html.contains("href=\"private.bin\""), "approved report HTML is byte-preserved; links must not be rewritten");
        for(Path archive:java.util.List.of(disk,memory))try(var paths=Files.list(archive.getParent())) {assertEquals(java.util.List.of(archive),paths.toList());}
    }

    @Test void repeatedLargeLoadPreviewsUseOnePinnedVerifiedDirectoryAndRejectNewCorruption() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("large-load"));
        for(int i=0;i<2048;i++) {
            Path file=output.resolve("workload-"+(i%4)+"/case-"+i+".log");
            Files.createDirectories(file.getParent());Files.writeString(file,"evidence "+i);
        }
        Path archive=temporary.resolve("large.zip");ArtifactArchive.build(output,archive);
        long started=System.nanoTime();
        for(int i=0;i<8;i++)assertEquals("evidence "+i,new String(ArtifactArchive.read(archive,"workload-"+(i%4)+"/case-"+i+".log"),StandardCharsets.UTF_8));
        System.out.println("2048-entry archive, 8 independent previews: "+((System.nanoTime()-started)/1_000_000)+" ms");
        started=System.nanoTime();
        try(var read=ArtifactArchive.openVerified(archive,false)) {
            assertEquals(2048,((java.util.List<?>)read.manifest.get("entries")).size());
            assertThrows(UnsupportedOperationException.class,()->((java.util.List<?>)read.manifest.get("entries")).clear());
            // Replace the pathname: the verified handle must keep using its original inode,
            // never reopen or rewalk the replacement directory for each preview.
            Path replacement=temporary.resolve("replacement.zip");Files.writeString(replacement,"corrupt replacement");
            Files.move(replacement,archive,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            for(int i=0;i<128;i++)assertEquals("evidence "+i,new String(read.read("workload-"+(i%4)+"/case-"+i+".log"),StandardCharsets.UTF_8));
            assertThrows(java.io.FileNotFoundException.class,()->read.read("unapproved.log"));
            assertThrows(Exception.class,()->ArtifactArchive.openVerified(archive,false));
        }
        System.out.println("2048-entry verified context + 128 digest/CRC previews: "+((System.nanoTime()-started)/1_000_000)+" ms");
    }

    @Test void pinnedPreviewStillRejectsInPlacePayloadTampering() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("mutable-output"));
        String payload="unique-approved-value";
        Files.writeString(output.resolve("case.log"),payload);
        Path archive=temporary.resolve("mutable.zip");ArtifactArchive.build(output,archive);
        try(var read=ArtifactArchive.openVerified(archive,false)) {
            byte[] bytes=Files.readAllBytes(archive);
            int offset=new String(bytes,StandardCharsets.ISO_8859_1).indexOf(payload);
            assertTrue(offset>=0);
            try(var file=new java.io.RandomAccessFile(archive.toFile(),"rw")) {file.seek(offset);file.writeByte('X');}
            assertThrows(IllegalArgumentException.class,()->read.read("case.log"));
        }
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
        Path good=temporary.resolve("good.zip");ArtifactArchive.build(output,good);
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
        Path archive=temporary.resolve("archive.zip");ArtifactArchive.build(output,archive);
        Map<String,Object> manifest=ArtifactArchive.verify(archive,true);
        assertEquals(false,manifest.get("complete"));
        assertFalse(manifest.toString().contains("secret-binary"));assertFalse(manifest.toString().contains("outside.log"));
        assertThrows(java.io.FileNotFoundException.class,()->ArtifactArchive.read(archive,"outside.log"));
    }

    @Test void oversizedReportEntryIsOmittedWithSafeDiagnosticAndItsLinkIsUnavailable() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("oversized-report-output"));
        Path report=Files.createDirectories(output.resolve("load/run/report"));
        Files.writeString(report.resolve("index.html"),"<a href=\"oversized.html\">Open details</a>",StandardCharsets.UTF_8);
        Files.write(report.resolve("oversized.html"),new byte[(int)ArtifactArchive.MAX_ENTRY_BYTES+1]);
        Path archive=temporary.resolve("oversized-report.zip");
        ArtifactArchive.build(output,archive);

        Map<String,Object> manifest=ArtifactArchive.verify(archive,true);
        assertEquals(false,manifest.get("complete"));
        List<?> notices=(List<?>)manifest.get("diagnostics");
        Map<?,?> notice=(Map<?,?>)notices.get(0);
        assertEquals("ATT-ARTIFACT-OMITTED",notice.get("code"));
        assertEquals("artifact.archive.entryBytes",notice.get("limitName"));
        assertEquals("ARTIFACT_OMITTED",notice.get("outcome"));
        assertEquals(ArtifactArchive.MAX_ENTRY_BYTES+1,((Number)notice.get("observed")).longValue());
        assertFalse(manifest.toString().contains("oversized.html"),"The safe diagnostic must not reveal a filesystem or logical rejected name");
        assertThrows(java.io.FileNotFoundException.class,()->ArtifactArchive.read(archive,"load/run/report/oversized.html"));
        String published=new String(ArtifactArchive.read(archive,"load/run/report/index.html"),StandardCharsets.UTF_8);
        assertEquals("<a href=\"oversized.html\">Open details</a>", published,
                "Archive publication preserves producer bytes even when a linked artifact is omitted");
    }

    @Test void totalAndEntryCountCapsFailPublicationWithTypedLimitEvidence() throws Exception {
        Path totalOutput=Files.createDirectory(temporary.resolve("total-cap-output"));
        byte[] part=new byte[15*1024*1024];
        java.util.Arrays.fill(part,(byte)'x');
        for(int i=0;i<9;i++)Files.write(totalOutput.resolve("part-"+i+".log"),part);
        ArtifactArchive.LimitFailure total=assertThrows(ArtifactArchive.LimitFailure.class,
                ()->ArtifactArchive.build(totalOutput,temporary.resolve("total-cap.zip")));
        assertEquals("artifact.archive.totalBytes",total.limitName);
        assertEquals(ArtifactArchive.MAX_TOTAL_BYTES,total.limit);
        assertTrue(total.observed>ArtifactArchive.MAX_TOTAL_BYTES);
        assertFalse(Files.exists(temporary.resolve("total-cap.zip")));

        Path entriesOutput=Files.createDirectory(temporary.resolve("entry-cap-output"));
        for(int i=0;i<ArtifactArchive.MAX_ENTRIES+1;i++)Files.createFile(entriesOutput.resolve(i+".log"));
        Path cappedZip=temporary.resolve("entry-cap.zip");
        ArtifactArchive.build(entriesOutput,cappedZip);
        Map<String,Object> cappedManifest=ArtifactArchive.verify(cappedZip,true);
        assertEquals(false,cappedManifest.get("complete"));
        assertEquals(ArtifactArchive.MAX_ENTRIES,((List<?>)cappedManifest.get("entries")).size());
        assertEquals("artifact.archive.entries",((Map<?,?>)((List<?>)cappedManifest.get("diagnostics")).get(0)).get("limitName"));
    }

    @Test void reportDiscoveryInventoryDoesNotDependOnListingLimitsAndKeepsRelativeBytes() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("output"));
        for(String workload:java.util.List.of("one","two")) {
            Path samples=Files.createDirectories(output.resolve("load/run/samples/"+workload));
            for(int i=0;i<600;i++)Files.writeString(samples.resolve(i+".log"),"sample "+i);
        }
        writeCanonicalLoadReport(output.resolve("load/run"), "run");
        Path archive=temporary.resolve("archive.zip");ArtifactArchive.build(output,archive);
        Map<String,Object> archiveManifest=ArtifactArchive.verify(archive,true);
        assertEquals(1205,((java.util.List<?>)archiveManifest.get("entries")).size());
        String report=new String(ArtifactArchive.read(archive,"load/run/report/index.html"),StandardCharsets.UTF_8);
        assertTrue(report.contains("href=\"../load-summary.json\""));
        assertTrue(new String(ArtifactArchive.read(archive,"load/run/load-summary.json"),StandardCharsets.UTF_8)
                .contains("\"report\":\"report/index.html\""));
        assertTrue(new String(ArtifactArchive.read(archive,"load/run/load-summary.yaml"),StandardCharsets.UTF_8)
                .contains("report: report/index.html"));
        byte[] loadManifest=Files.readAllBytes(output.resolve("load/run/"+ArtifactArchive.LOAD_REPORT_MANIFEST));
        assertArrayEquals(loadManifest,ArtifactArchive.read(archive,"load/run/"+ArtifactArchive.LOAD_REPORT_MANIFEST));
        assertEquals(ArtifactArchive.sha256(loadManifest),manifestEntry(archive,"load/run/"+ArtifactArchive.LOAD_REPORT_MANIFEST).get("sha256"));
        Path run=output.resolve("load/run");
        @SuppressWarnings("unchecked") Map<String,Object> committed=new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(Files.readAllBytes(run.resolve(ArtifactArchive.LOAD_REPORT_MANIFEST)),Map.class);
        for(Object value:(List<?>)committed.get("files")) {
            Map<?,?> row=(Map<?,?>)value;String path="load/run/"+row.get("path");
            byte[] source=Files.readAllBytes(output.resolve(path));
            byte[] zipped=ArtifactArchive.read(archive,path);
            assertArrayEquals(source,zipped,path);
            assertEquals(row.get("lengthBytes"),source.length,path);
            assertEquals(row.get("sha256"),ArtifactArchive.sha256(zipped),path);
            assertEquals(row.get("sha256"),manifestEntry(archive,path).get("sha256"),path);
        }
    }

    @Test void loadArchiveRejectsMissingOrCorruptReportCommit() throws Exception {
        Path missing=Files.createDirectory(temporary.resolve("missing-report-commit"));
        Files.createDirectories(missing.resolve("load/run/report"));
        Files.writeString(missing.resolve("load/run/report/index.html"),"<html>report</html>");
        Files.writeString(missing.resolve("load/run/load-summary.json"),"{\"report\":\"report/index.html\"}");
        assertThrows(java.io.IOException.class,()->ArtifactArchive.build(missing,temporary.resolve("missing.zip")));

        Path corrupt=Files.createDirectory(temporary.resolve("corrupt-report-commit"));
        writeCanonicalLoadReport(corrupt.resolve("load/run"),"run");
        Files.writeString(corrupt.resolve("load/run/report/index.html"),"<html>tampered</html>");
        assertThrows(java.io.IOException.class,()->ArtifactArchive.build(corrupt,temporary.resolve("corrupt.zip")));
        assertFalse(Files.exists(temporary.resolve("missing.zip")));
        assertFalse(Files.exists(temporary.resolve("corrupt.zip")));
    }

    private static void writeCanonicalLoadReport(Path run,String runId) throws Exception {
        Files.createDirectories(run.resolve("report"));
        Files.createDirectories(run.resolve("details/v1"));
        Files.writeString(run.resolve("report/index.html"),"<a href=\"../load-summary.json\">Summary</a>");
        Files.writeString(run.resolve("load-summary.json"),"{\"runId\":\"run\",\"durationMs\":1000,\"executionStatus\":\"PASS\",\"performanceVerdict\":\"NOT_EVALUATED\",\"report\":\"report/index.html\",\"reportManifest\":\"load-report-manifest.json\",\"businessValue\":\"keep-me\"}");
        Files.writeString(run.resolve("load-summary.yaml"),"report: report/index.html\n");
        Files.writeString(run.resolve("details/v1/manifest.json"),"{\"complete\":true}");
        List<Map<String,Object>> files=new ArrayList<>();
        for(String relative:List.of("load-summary.json","load-summary.yaml","report/index.html","details/v1/manifest.json")) {
            byte[] bytes=Files.readAllBytes(run.resolve(relative));
            files.add(Map.of("path",relative,"lengthBytes",bytes.length,"sha256",ArtifactArchive.sha256(bytes)));
        }
        Map<String,Object> manifest=new LinkedHashMap<>();
        manifest.put("schemaVersion",ArtifactArchive.LOAD_REPORT_MANIFEST_SCHEMA);
        manifest.put("state","COMPLETE");manifest.put("complete",true);manifest.put("runId",runId);
        manifest.put("executionStatus","PASS");manifest.put("performanceVerdict","NOT_EVALUATED");
        manifest.put("startedAt","2026-10-26T00:00:00Z");manifest.put("endedAt","2026-10-26T00:00:01Z");
        manifest.put("durationMs",1000L);manifest.put("files",files);
        Files.write(run.resolve(ArtifactArchive.LOAD_REPORT_MANIFEST),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(manifest));
    }

    @Test void archiveWriteFailureLeavesRawOutputAndNoPublishedOrStagingArchive() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("output"));Files.writeString(output.resolve("case.log"),"raw");
        Path archive=temporary.resolve("job/artifacts.zip");
        assertThrows(java.io.IOException.class,()->ArtifactArchive.build(output,archive,1024,path->{throw new java.io.IOException("disk full");}));
        assertEquals("raw",Files.readString(output.resolve("case.log")));assertFalse(Files.exists(archive));
        try(var paths=Files.list(archive.getParent())){assertEquals(0,paths.count());}
    }

    @Test void interruptedSpillPublicationCleansStagingAndPreservesPriorArchive() throws Exception {
        Path output=Files.createDirectory(temporary.resolve("interrupted-output"));
        Files.writeString(output.resolve("a.log"),"original-a");Files.writeString(output.resolve("b.log"),"original-b");
        Path archive=temporary.resolve("interrupted-job/artifacts.zip");
        ArtifactArchive.build(output,archive);
        byte[] priorArchive=Files.readAllBytes(archive);
        Files.writeString(output.resolve("a.log"),"replacement-a");Files.writeString(output.resolve("b.log"),"replacement-b");

        var writerPaused=new java.util.concurrent.CountDownLatch(1);
        var releaseWriter=new java.util.concurrent.CountDownLatch(1);
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread writer=new Thread(()->{
            try {
                ArtifactArchive.build(output,archive,0,path->{
                    if("a.log".equals(path)) {
                        writerPaused.countDown();releaseWriter.await();
                    }
                });
            } catch(Throwable error) { failure.set(error); }
        },"artifact-interruption-fixture");
        writer.start();
        try {
            assertTrue(writerPaused.await(5,java.util.concurrent.TimeUnit.SECONDS),"Writer should pause after writing the first spilled entry");
            writer.interrupt();writer.join(5000);
            assertFalse(writer.isAlive(),"Interrupted archive writer should exit");
        } finally {
            releaseWriter.countDown();
            if(writer.isAlive()) {writer.interrupt();writer.join(5000);}
        }

        assertTrue(failure.get() instanceof InterruptedException,"Interrupted publication should preserve its failure cause");
        assertArrayEquals(priorArchive,Files.readAllBytes(archive),"Uncommitted ZIP must not replace the prior archive");
        assertEquals("replacement-a",Files.readString(output.resolve("a.log")));
        assertEquals("replacement-b",Files.readString(output.resolve("b.log")));
        try(var paths=Files.list(archive.getParent())) { assertEquals(java.util.List.of(archive),paths.toList(),"ZIP staging must be removed"); }
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
                            "decision", "preserved", "report", false, "preview", true));
                    zip.putNextEntry(new java.util.zip.ZipEntry(name)); zip.write(content); zip.closeEntry();
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(ArtifactArchive.MANIFEST));
                zip.write(ServerRuntime.JSON.writeValueAsBytes(Map.of("version", 1, "policy", "att-export-approved-bytes-v1", "entries", rows,
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
        Path good = ArtifactArchive.build(output, temporary.resolve("unicode-source.zip"));
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
        return ArtifactArchive.build(output, temporary.resolve("fixture.zip"));
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
