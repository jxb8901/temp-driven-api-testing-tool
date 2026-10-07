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
}
