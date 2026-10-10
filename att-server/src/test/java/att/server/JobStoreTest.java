package att.server;

import att.worker.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JobStoreTest {
    @TempDir Path temp;
    @Test void persistsJobIdentityLifecycleAndDeterministicRestartRecovery() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages"));Path pkg=Files.createDirectory(allowed.resolve("payments"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("data")+"\n  javaExecutable: "+java+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    payments: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);JobStore store=new JobStore(config);
        Job job=new Job("J0123456789ABCDEF","run","payments","ci-build",new WorkerRequest(),new JobEvents(config.dataDir.resolve("jobs/J0123456789ABCDEF/events.jsonl"),100));
        store.insert(job,"{\"command\":\"run\",\"packageId\":\"payments\"}");store.audit("ci-build","SUBMIT",job.id,job.packageId,"ACCEPTED");
        assertEquals("QUEUED",store.get(job.id).get("status"));assertEquals(1,store.count("QUEUED"));
        job.status="RUNNING";job.workerPid=99999999L;job.workerStartTime=Instant.now();store.update(job);
        assertEquals(1,store.staleWorkers().size());store.interruptStale(job.id);
        Map<String,Object> restored=store.get(job.id);assertEquals("ERROR",restored.get("status"));assertTrue(((String)restored.get("diagnosticJson")).contains("ATT-SERVER-INTERRUPTED"));
        store.close();
    }

    @Test void persistsWorkerPerformanceMetadataAcrossStoreReads() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("performance-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("performance-server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("performance-data")+"\n  javaExecutable: "+java+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);JobStore store=new JobStore(config);
        Job job=new Job("J1111111111111111","run","p","test",new WorkerRequest(),new JobEvents(config.dataDir.resolve("jobs/J1111111111111111/events.jsonl"),100));
        job.requestReceivedNanos=1_000_000L;job.admittedNanos=2_500_000L;job.workerStartedNanos=4_000_000L;
        job.workerSpawnStartedNanos=4_500_000L;job.workerSpawnedNanos=6_000_000L;job.workerReadyNanos=8_000_000L;
        job.executionReadyNanos=11_000_000L;job.resultReceivedNanos=20_000_000L;job.workerTerminatedNanos=22_000_000L;
        job.workerMetrics=Map.of("heapPeakUsedBytes",4096L,"processCpuSupported",true);
        store.insert(job,"{}");store.update(job);

        Map<String,Object> restored=store.get(job.id);
        Map<String,Object> performance=ServerRuntime.JSON.readValue((String)restored.get("performanceJson"),Map.class);
        Map<?,?> timings=(Map<?,?>)performance.get("timings");
        assertEquals(1.5,((Number)timings.get("admissionMs")).doubleValue());
        assertEquals(1.5,((Number)timings.get("queueWaitMs")).doubleValue());
        assertEquals(2.0,((Number)timings.get("workerReadyMs")).doubleValue());
        assertEquals(2.0,((Number)timings.get("resultToTerminationMs")).doubleValue());
        assertEquals(4096L,((Number)((Map<?,?>)performance.get("worker")).get("heapPeakUsedBytes")).longValue());
        store.close();
    }
}
