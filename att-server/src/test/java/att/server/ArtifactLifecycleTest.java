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
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(Files.exists(runtime.outputDirectoryForJob(id))&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(Files.exists(runtime.outputDirectoryForJob(id)));
            assertFalse(Files.exists(runtime.artifactArchivePath(id).resolveSibling(".artifact-orphan.zip.tmp")));
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
