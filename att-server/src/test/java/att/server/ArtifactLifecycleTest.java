package att.server;

import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactLifecycleTest {
    @TempDir Path temp;

    @Test void persistsArchiveMetricsAcrossFailureRetryAndRestartWithoutChangingExecution() throws Exception {
        ServerConfig config;
        String id="J0000000000000001";
        try(ServerRuntime runtime=runtime()) {
            config=runtime.config;
            job(runtime,id,"ERROR");
            Files.delete(runtime.outputDirectoryForJob(id));
            runtime.scheduleArtifactArchive(id);
            var failed=awaitMetrics(runtime,id,"FAILED");
            assertEquals(1,failed.attempts);assertEquals(0,failed.retries);
            assertEquals("publication_failed",failed.failureReason);
            assertEquals("publication",failed.failurePhase);
            assertNull(failed.sourceBytes);assertNull(failed.zipBytes);
            assertNull(runtime.artifactIndex(id).get("archiveSizeBytes"));
            Path output=Files.createDirectory(runtime.outputDirectoryForJob(id));
            Files.writeString(output.resolve("private-canary.log"),"password=private-canary-secret\nbusiness=retained\n");
            Files.write(output.resolve("binary.bin"),new byte[]{0,1,2});
            runtime.retryArtifactArchive(id);
            var ready=awaitMetrics(runtime,id,"READY");
            assertEquals(2,ready.attempts);assertEquals(1,ready.retries);
            assertNull(ready.failurePhase);assertNull(ready.failureReason);
            assertEquals(2,ready.sourceFiles);assertTrue(ready.sourceBytes>3);
            assertEquals(1,ready.includedEntries);assertEquals(1,ready.omittedEntries);
            assertEquals(Files.size(runtime.artifactArchivePath(id)),ready.zipBytes);
            assertEquals(ready.zipBytes,runtime.artifactIndex(id).get("archiveSizeBytes"));
            assertNotNull(ready.compressionRatio);
            for(Double elapsed:List.of(ready.queueWaitMs,ready.publicationWallMs,ready.scanClassificationRedactionMs,
                    ready.zipWriteMs,ready.verificationFsyncMs,ready.cleanupMs))assertTrue(elapsed>=0);
            assertTrue(ready.writerCpuMs==null||ready.writerCpuMs>=0);
            assertTrue(ready.writerAllocatedBytes==null||ready.writerAllocatedBytes>=0);
            String json=ServerRuntime.JSON.writeValueAsString(runtime.jobRecord(id).get("archiveMetrics"));
            assertFalse(json.contains("private-canary"));assertFalse(json.contains(output.toString()));
            assertEquals("ERROR",runtime.jobRecord(id).get("status"));
        }
        try(ServerRuntime restarted=new ServerRuntime(config,temp.resolve("libs").toString())) {
            var persisted=restarted.store.archiveMetrics(id);
            assertEquals(2,persisted.attempts);assertEquals("READY",persisted.outcome);
            assertEquals(persisted.zipBytes,restarted.artifactIndex(id).get("archiveSizeBytes"));
            restarted.store.quarantineWorker(id);
            assertEquals("FAILED",restarted.artifactIndex(id).get("archiveStatus"));
            assertNull(restarted.artifactIndex(id).get("archiveSizeBytes"));
        }
    }

    @Test void emptyArchiveHasRealZeroCountsButUnsupportedMeasurementsStayNull() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            String id="J0000000000000001";job(runtime,id,"CANCELLED");
            runtime.scheduleArtifactArchive(id);
            var metrics=awaitMetrics(runtime,id,"READY");
            assertEquals(0,metrics.sourceFiles);assertEquals(0,metrics.sourceBytes);
            assertEquals(0,metrics.includedEntries);assertEquals(0,metrics.omittedEntries);
            assertTrue(metrics.zipBytes>0);assertEquals(0.0,metrics.compressionRatio);
        }
        ArtifactMetricsRecorder unavailable=new ArtifactMetricsRecorder(System.nanoTime(),1,false);
        unavailable.finish();
        assertNull(unavailable.value.writerCpuMs);assertNull(unavailable.value.writerAllocatedBytes);
        assertNull(unavailable.value.zipWriteMs);
        assertNotNull(unavailable.value.publicationWallMs);
    }

    private att.server.api.ArtifactArchiveMetrics awaitMetrics(ServerRuntime runtime,String id,String outcome) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        do {
            var metrics=runtime.store.archiveMetrics(id);
            if(metrics!=null&&outcome.equals(metrics.outcome))return metrics;
            Thread.sleep(20);
        }while(System.nanoTime()<deadline);
        fail("Archive metrics did not reach "+outcome);return null;
    }

    @Test void archivesEveryTerminalOutcomeAndPreservesExactPreviewBytes() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            int counter=0;
            for(String state:List.of("PASS","FAIL","ERROR","INVALID","CANCELLED")) {
                String id=String.format("J%016X",++counter);Job job=job(runtime,id,state);
                Files.writeString(runtime.outputDirectoryForJob(id).resolve("case.log"),"password=archive-canary\nbusiness=unchanged\n");
                runtime.scheduleArtifactArchive(id);runtime.scheduleArtifactArchive(id);
                awaitState(runtime,id,"READY");
                assertEquals(state,runtime.jobRecord(id).get("status"));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(Files.exists(runtime.outputDirectoryForJob(id))&&System.nanoTime()<deadline)Thread.sleep(10);
                assertFalse(Files.exists(runtime.outputDirectoryForJob(id)));
                byte[] preview=runtime.archiveEntry(id,"case.log");
                assertArrayEquals(ArtifactArchive.read(runtime.artifactArchivePath(id),"case.log"),preview);
                assertFalse(new String(preview,java.nio.charset.StandardCharsets.UTF_8).contains("archive-canary"));
                assertTrue(new String(preview,java.nio.charset.StandardCharsets.UTF_8).contains("business=unchanged"));
                assertEquals(job.principal,runtime.jobRecord(id).get("principal"));
            }
        }
    }

    @Test void failuresKeepPrivateSourceAndRetryWhileLiveJobsStayUnarchived() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            String id="J0000000000000001";job(runtime,id,"RUNNING");
            runtime.scheduleArtifactArchive(id);Thread.sleep(100);
            assertFalse(Files.exists(runtime.artifactArchivePath(id)));
            assertThrows(IllegalArgumentException.class,()->runtime.retryArtifactArchive(id));
            Job failed=job(runtime,"J0000000000000002","ERROR");
            Files.delete(runtime.outputDirectoryForJob(failed.id));runtime.scheduleArtifactArchive(failed.id);
            awaitState(runtime,failed.id,"FAILED");
            assertThrows(ServerRuntime.ArchiveUnavailableException.class,()->runtime.openArtifactArchive(failed.id,true));
            Path output=Files.createDirectory(runtime.outputDirectoryForJob(failed.id));Files.writeString(output.resolve("case.log"),"recovered");
            runtime.retryArtifactArchive(failed.id);awaitState(runtime,failed.id,"READY");
            assertEquals("recovered",new String(runtime.archiveEntry(failed.id,"case.log"),java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void restartReconcilesCommittedZipBeforeStatusUpdateAndCleansStaging() throws Exception {
        ServerConfig config;
        String id="J0000000000000001";
        try(ServerRuntime runtime=runtime()) {
            config=runtime.config;job(runtime,id,"PASS");
            Path output=runtime.outputDirectoryForJob(id);Files.writeString(output.resolve("case.log"),"safe");
            ArtifactArchive.build(output,runtime.artifactArchivePath(id),file->runtime.publicArtifact(id,file));
            Files.writeString(output.getParent().resolve("archive-status.json"),"{\"state\":\"ARCHIVING\"}");
            Files.writeString(output.getParent().resolve(".artifact-orphan.zip.tmp"),"incomplete");
        }
        try(ServerRuntime runtime=new ServerRuntime(config,temp.resolve("libs").toString())) {
            awaitState(runtime,id,"READY");
            Path staging = runtime.artifactArchivePath(id).resolveSibling(".artifact-orphan.zip.tmp");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while((Files.exists(runtime.outputDirectoryForJob(id))||Files.exists(staging))&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(Files.exists(runtime.outputDirectoryForJob(id)));
            assertFalse(Files.exists(staging));
            assertEquals("safe",new String(runtime.archiveEntry(id,"case.log"),java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void structuredPublicationRedactsSensitiveFieldsWithoutBreakingSyntaxOrBusinessValues() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            String id="J0000000000000001";job(runtime,id,"PASS");Path output=runtime.outputDirectoryForJob(id);
            Files.writeString(output.resolve("result.json"),"{\"clientToken\":{\"arbitraryField\":\"nested-canary\"},\"customerName\":\"Business Name\",\"amount\":12.5}");
            Files.writeString(output.resolve("result.yaml"),"clientSecret:\n  arbitraryField: nested-canary\ncustomerName: Business Name\namount: 12.5\n");
            Files.writeString(output.resolve("invalid.json"),"{invalid: secret}");
            runtime.scheduleArtifactArchive(id);awaitState(runtime,id,"READY");
            byte[] json=runtime.archiveEntry(id,"result.json"),yaml=runtime.archiveEntry(id,"result.yaml");
            assertEquals("[REDACTED_SECRET]",ServerRuntime.JSON.readTree(json).path("clientToken").asText());
            assertEquals("Business Name",ServerRuntime.JSON.readTree(json).path("customerName").asText());
            Map<?,?> yamlValue=new org.yaml.snakeyaml.Yaml().load(new String(yaml,java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("[REDACTED_SECRET]",yamlValue.get("clientSecret"));assertEquals("Business Name",yamlValue.get("customerName"));
            assertFalse(new String(json,java.nio.charset.StandardCharsets.UTF_8).contains("nested-canary"));
            assertFalse(new String(yaml,java.nio.charset.StandardCharsets.UTF_8).contains("nested-canary"));
            assertEquals(false,runtime.artifactIndex(id).get("complete"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.archiveEntry(id,"invalid.json"));
        }
    }

    @Test void retentionWaitsForOpenDownloadsAndReaderCapacityIsReleased() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            String id="J0000000000000001";Job expired=job(runtime,id,"PASS");
            expired.finishedAt=Instant.now().minus(java.time.Duration.ofDays(runtime.config.jobRetentionDays+1L));runtime.store.update(expired);
            Files.writeString(runtime.outputDirectoryForJob(id).resolve("case.log"),"retained until reader closes");
            runtime.scheduleArtifactArchive(id);awaitState(runtime,id,"READY");
            var readers=new java.util.ArrayList<ServerRuntime.ArtifactRead>();
            try {
                for(int i=0;i<8;i++)readers.add(runtime.openArtifactArchive(id,true));
                assertThrows(ServerRuntime.ArtifactCapacityException.class,()->runtime.openArtifactArchive(id,true));
                java.util.concurrent.CompletableFuture.runAsync(()->{
                    try{runtime.cleanupExpiredJobs();}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
                }).get(5,TimeUnit.SECONDS);
                assertNotNull(runtime.jobRecord(id));assertTrue(Files.isRegularFile(readers.get(0).path));
                assertEquals("retained until reader closes",new String(ArtifactArchive.read(readers.get(0).path,"case.log"),java.nio.charset.StandardCharsets.UTF_8));
            } finally {for(var reader:readers)reader.close();}
            try(var reopened=runtime.openArtifactArchive(id,true)){assertTrue(Files.isRegularFile(reopened.path));}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            do{runtime.cleanupExpiredJobs();if(runtime.store.get(id)==null)break;Thread.sleep(10);}while(System.nanoTime()<deadline);
            assertNull(runtime.store.get(id));assertFalse(Files.exists(runtime.artifactArchivePath(id).getParent()));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.openArtifactArchive(id,true));
        }
    }

    @Test void corruptionNeverFallsBackToRawAndRecoveryOnlyRebuildsWhenSourceStillExists() throws Exception {
        try(ServerRuntime runtime=runtime()) {
            String id="J0000000000000001";job(runtime,id,"PASS");
            Path output=runtime.outputDirectoryForJob(id);Files.writeString(output.resolve("case.log"),"password=private-canary\nbusiness=retained");
            Files.writeString(runtime.artifactArchivePath(id),"partial central directory");
            Files.writeString(output.getParent().resolve("archive-status.json"),"{\"state\":\"READY\"}");
            assertEquals("FAILED",runtime.artifactIndex(id).get("archiveStatus"));
            assertThrows(ServerRuntime.ArchiveUnavailableException.class,()->runtime.archiveEntry(id,"case.log"));
            assertTrue(Files.readString(output.resolve("case.log")).contains("private-canary"),"Failure must retain private source, not publish it");
            runtime.retryArtifactArchive(id);awaitState(runtime,id,"READY");
            awaitMetrics(runtime,id,"READY");
            String published=new String(runtime.archiveEntry(id,"case.log"),java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(published.contains("private-canary"));assertTrue(published.contains("business=retained"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(Files.exists(output)&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(Files.exists(output));
            Files.writeString(runtime.artifactArchivePath(id),"corrupt after source cleanup");
            runtime.scheduleArtifactArchive(id);awaitState(runtime,id,"FAILED");
            assertEquals(false,runtime.artifactIndex(id).get("retryable"));
            assertThrows(ServerRuntime.ArchiveUnavailableException.class,()->runtime.openArtifactArchive(id,true));
            assertThrows(IllegalArgumentException.class,()->runtime.retryArtifactArchive(id));
            Files.writeString(output.getParent().resolve("archive-status.json"),"not valid JSON");
            assertEquals("invalid_metadata",runtime.artifactIndex(id).get("reason"));
        }
    }

    @Test void artifactPublicationCannotUseProtocolPointersOrContainersToBypassProtection() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            String id = "J0000000000000001"; job(runtime, id, "PASS");
            Path output = runtime.outputDirectoryForJob(id);
            Map<String, Object> authored = Map.of(
                    "password", Map.of("$attDebugKeepDefault", "/pointer-secret-canary"),
                    "$attDebugKeepDefault", "/private/marker-host-canary/report.txt",
                    "url", Map.of("arbitraryField", "/private/container-host-canary/report.txt"),
                    "remotePath", output.resolve("case.log").toString(),
                    "customerName", "Business Customer");
            String json = ServerRuntime.JSON.writeValueAsString(authored);
            Files.writeString(output.resolve("result.json"), json);
            Files.writeString(output.resolve("result.yaml"), new org.yaml.snakeyaml.Yaml().dump(authored));
            for (String name : List.of("result.json", "result.yaml")) {
                byte[] projected = runtime.publicArtifact(id, output.resolve(name));
                String text = new String(projected, java.nio.charset.StandardCharsets.UTF_8);
                for (String forbidden : List.of("pointer-secret-canary", "marker-host-canary", "container-host-canary", output.toString()))
                    assertFalse(text.contains(forbidden), name + " retained a protocol-only escape");
                assertTrue(text.contains("Business Customer"));
            }
            assertEquals(json, Files.readString(output.resolve("result.json")), "Projection must not rewrite private source");
            runtime.scheduleArtifactArchive(id); awaitState(runtime, id, "READY");
            for (String name : List.of("result.json", "result.yaml")) {
                byte[] preview = runtime.archiveEntry(id, name);
                assertArrayEquals(ArtifactArchive.read(runtime.artifactArchivePath(id), name), preview);
                assertFalse(new String(preview, java.nio.charset.StandardCharsets.UTF_8).contains("-canary"));
            }
        }
    }

    @Test void structuredJsonPublicationRejectsAmbiguityAndPreservesBusinessNumberPrecision() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            String id = "J0000000000000001"; job(runtime, id, "PASS");
            Path output = runtime.outputDirectoryForJob(id), invalid = output.resolve("invalid.json");
            for (String text : List.of("{\"amount\":1,\"amount\":2}", "{\"amount\":1} {\"other\":2}",
                    "[] trailing", "[".repeat(65) + "0" + "]".repeat(65))) {
                Files.writeString(invalid, text);
                assertThrows(IllegalArgumentException.class, () -> runtime.publicArtifact(id, invalid));
            }
            Files.writeString(output.resolve("result.json"),
                    "{\"amount\":12345678901234567890.1234567890123456789,\"customerName\":\"Business Customer\"}");
            byte[] published = runtime.publicArtifact(id, output.resolve("result.json"));
            var precise = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            assertEquals(new java.math.BigDecimal("12345678901234567890.1234567890123456789"),
                    precise.readTree(published).path("amount").decimalValue());
            Path collision = output.resolve("collision.json");
            Files.writeString(collision, ServerRuntime.JSON.writeValueAsString(Map.of("/host/one/name.txt", "a", "/host/two/name.txt", "b")));
            assertThrows(IllegalArgumentException.class, () -> runtime.publicArtifact(id, collision));
            runtime.scheduleArtifactArchive(id); awaitState(runtime, id, "READY");
            assertArrayEquals(published, runtime.archiveEntry(id, "result.json"));
            assertEquals(false, runtime.artifactIndex(id).get("complete"));
            assertThrows(ServerRuntime.NotFoundException.class, () -> runtime.archiveEntry(id, "collision.json"));
        }
    }

    @Test void yamlPublicationPreservesExactNumbersAndRejectsUnsafeOrOversizedValues() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            String id = "J0000000000000001"; job(runtime, id, "PASS");
            Path output = runtime.outputDirectoryForJob(id), file = output.resolve("result.yaml");
            String decimal = "12345678901234567890.1234567890123456789";
            Files.writeString(file, "amount: " + decimal + "\nunits: 123456789012345678901234567890\n"
                    + "duration: 1:01.25\ncustomerName: Business Customer\n");
            byte[] projected = runtime.publicArtifact(id, file);
            String text = new String(projected, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains(decimal), text);
            assertTrue(text.contains("123456789012345678901234567890"), text);
            assertTrue(text.contains("61.25"), text);
            for (String invalid : List.of("value: .inf\n", "value: .NaN\n", "value: " + "1".repeat(1001),
                    "value: 1\nvalue: 2\n", "value: !!java.lang.String unsafe\n", "first: &a [1]\nsecond: *a\n")) {
                Files.writeString(output.resolve("invalid.yaml"), invalid);
                assertThrows(IllegalArgumentException.class, () -> runtime.publicArtifact(id, output.resolve("invalid.yaml")));
            }
            runtime.scheduleArtifactArchive(id); awaitState(runtime, id, "READY");
            assertArrayEquals(projected, runtime.archiveEntry(id, "result.yaml"));
            assertEquals(false, runtime.artifactIndex(id).get("complete"));
        }
    }

    @Test void realisticSecretCorpusHasOneApprovedRepresentationAcrossFormats() throws Exception {
        try (ServerRuntime runtime = runtime()) {
            String id = "J0000000000000001"; job(runtime, id, "FAIL");
            Path output = runtime.outputDirectoryForJob(id);
            Map<String,Object> response = Map.of(
                    "customerName", "Business Customer", "amount", 12.5,
                    "arbitraryReturnField", "Bearer export-canary-bearer",
                    "dsn", "jdbc:postgresql://operator:export-canary-dsn@db.example/orders",
                    "headers", Map.of("Cookie", "sid=export-canary-cookie", "Authorization", "Basic export-canary-basic"),
                    "regulated", Map.of("classification", "secret", "arbitraryRows", List.of("export-canary-regulated")),
                    "password=export-canary-key", "opaque",
                    "/private/export-canary-host/key.txt", "ordinary value",
                    "queue", "ORDERS.REQUEST", "remotePath", "/remote/inbox/ordinary.txt");
            Files.writeString(output.resolve("evidence.json"), ServerRuntime.JSON.writeValueAsString(response));
            Files.writeString(output.resolve("evidence.yaml"), new org.yaml.snakeyaml.Yaml().dump(response));
            Files.writeString(output.resolve("case.log"), "Authorization: Bearer export-canary-log\nCookie: sid=export-canary-logcookie\n"
                    + "url=postgresql://operator:export-canary-logdsn@db.example/orders\n"
                    + "password: |\n  export-canary-multiline\n"
                    + "-----BEGIN PRIVATE KEY-----\nexport-canary-pem\n-----END PRIVATE KEY-----\nbusiness=unchanged\n");
            Files.writeString(output.resolve("result.xml"), "<result><credentials>export-canary-xml</credentials><business>unchanged</business></result>");
            Files.writeString(output.resolve("report.html"), "<html><body><pre>password=export-canary-html</pre><p>business=unchanged</p>"
                    + "<a href='evidence.json'>Evidence</a></body></html>");
            Files.writeString(output.resolve("result.csv"), "name,password\r\nBusiness Customer,export-canary-csv\r\n");
            Files.write(output.resolve("invalid-utf8.log"), new byte[] {(byte)0xc3, (byte)0x28});
            Files.write(output.resolve("binary.log"), new byte[] {0, 1});
            Map<String,byte[]> projected = new java.util.LinkedHashMap<>();
            for (String name : List.of("evidence.json", "evidence.yaml", "case.log", "result.xml", "result.csv")) {
                byte[] bytes = runtime.publicArtifact(id, output.resolve(name));
                assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("export-canary-"), name);
                projected.put(name, bytes);
            }
            runtime.scheduleArtifactArchive(id); awaitState(runtime, id, "READY");
            Map<String,Object> manifest = ArtifactArchive.verify(runtime.artifactArchivePath(id), true);
            assertEquals(false, manifest.get("complete"));
            try (var zip = new java.util.zip.ZipFile(runtime.artifactArchivePath(id).toFile())) {
                for (Object entry : (List<?>) manifest.get("entries")) {
                    Map<?,?> row = (Map<?,?>) entry; String name = String.valueOf(row.get("path"));
                    byte[] preview = runtime.archiveEntry(id, name);
                    try (var input = zip.getInputStream(zip.getEntry(name))) { assertArrayEquals(preview, input.readAllBytes()); }
                    assertEquals(row.get("sha256"), ArtifactArchive.sha256(preview));
                    assertFalse(new String(preview, java.nio.charset.StandardCharsets.UTF_8).contains("export-canary-"), name);
                    if (projected.containsKey(name)) assertArrayEquals(projected.get(name), preview);
                }
            }
            var business = ServerRuntime.JSON.readTree(runtime.archiveEntry(id, "evidence.json"));
            assertEquals("Business Customer", business.path("customerName").asText());
            assertEquals(12.5, business.path("amount").asDouble());
            assertEquals("ORDERS.REQUEST", business.path("queue").asText());
            assertEquals("/remote/inbox/ordinary.txt", business.path("remotePath").asText());
            assertThrows(ServerRuntime.NotFoundException.class, () -> runtime.archiveEntry(id, "invalid-utf8.log"));
            assertThrows(ServerRuntime.NotFoundException.class, () -> runtime.archiveEntry(id, "binary.log"));
        }
    }

    private ServerRuntime runtime() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("packages"));Path pkg=Files.createDirectories(packages.resolve("p"));
        Path config=Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: '"+temp.resolve("data")+"'\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 4\npackages:\n  allowedRoots:\n    - '"+packages+"'\n  entries:\n    p: '"+pkg+"'\n");
        return new ServerRuntime(ServerConfig.load(config),Files.createDirectories(temp.resolve("libs")).toString());
    }

    private Job job(ServerRuntime runtime,String id,String state) throws Exception {
        Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
        Job job=new Job(id,"run","p","user-a",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
        runtime.store.insert(job,"{}");job.status=state;if(!"RUNNING".equals(state))job.finishedAt=Instant.now();runtime.store.update(job);
        return job;
    }

    private void awaitState(ServerRuntime runtime,String id,String expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);Map<String,Object> index;
        do {index=runtime.artifactIndex(id);if(expected.equals(index.get("archiveStatus")))return;Thread.sleep(10);}while(System.nanoTime()<deadline);
        fail("Expected "+expected+" but got "+index);
    }
}
