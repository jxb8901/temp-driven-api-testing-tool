package att.server;

import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactRecoveryTest {
    @TempDir Path temp;

    @Test void recoverySkipsReadyHistoryPagesUnfinishedJobsAndClearsAttemptedFailures() throws Exception {
        ServerConfig config=config();
        try(ServerRuntime runtime=new ServerRuntime(config,temp.resolve("libs").toString())) {
            String url="jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')
                    +";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
            try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.prepareStatement(
                    "INSERT INTO jobs(job_id,command,package_id,principal,status,created_at,finished_at,att_version,request_summary,artifact_recovery) VALUES(?,'run','p','test',?,?,?,'test','{}',?)")) {
                for(int i=1;i<=2188;i++) {
                    boolean running=i>2178;
                    statement.setString(1,id(i));statement.setString(2,running?"RUNNING":"PASS");
                    statement.setString(3,Instant.now().toString());statement.setString(4,running?null:Instant.now().toString());
                    statement.setBoolean(5,i>2048);statement.addBatch();
                }
                statement.executeBatch();
            }
            assertEquals(id(2049),runtime.store.artifactRecoveryJobIds("",64).get(0));
            assertThrows(IllegalArgumentException.class,()->runtime.store.artifactRecoveryJobIds("",65));
            assertEquals(64,runtime.recoverArtifactArchives());
            assertEquals(64,runtime.recoverArtifactArchives());
            assertEquals(2,runtime.recoverArtifactArchives());
            assertEquals(0,runtime.recoverArtifactArchives(),"Each pass has a fixed budget and advances its cursor");
            assertEquals(64,runtime.recoverArtifactArchives(),"Capacity-rejected, unattempted publications remain eligible on the next cycle");
            assertFalse(Files.exists(config.dataDir.resolve("jobs").resolve(id(1))),"READY history is never passed to the filesystem writer");
        }
        try(JobStore reopened=new JobStore(config)) {
            var remaining=reopened.artifactRecoveryJobIds("",64);
            assertEquals(64,remaining.size(),"Unattempted recovery eligibility survives restart");
            assertFalse(remaining.contains(id(2049)),"A terminal failed publication must leave the recovery queue");
        }
    }

    @Test void verifiedPublicationAndCleanupDurablyLeaveTheRecoveryQueue() throws Exception {
        ServerConfig config=config();
        String id=id(1);
        try(ServerRuntime runtime=new ServerRuntime(config,temp.resolve("libs").toString())) {
            Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
            Files.writeString(output.resolve("case.log"),"business=unchanged");
            Job job=new Job(id,"run","p","test",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
            runtime.store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();runtime.store.update(job);
            assertEquals(1,runtime.store.artifactRecoveryJobIds("",64).size());
            runtime.scheduleArtifactArchive(id);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!runtime.store.artifactRecoveryJobIds("",64).isEmpty()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(runtime.store.artifactRecoveryJobIds("",64).isEmpty());
            assertEquals("READY",runtime.artifactIndex(id).get("archiveStatus"));
            assertFalse(Files.exists(output));
        }
        try(ServerRuntime restarted=new ServerRuntime(config,temp.resolve("libs").toString())) {
            assertEquals(0,restarted.recoverArtifactArchives(),"Complete READY archives are not rescanned on startup or maintenance");
            assertEquals("business=unchanged",new String(restarted.archiveEntry(id,"case.log"),java.nio.charset.StandardCharsets.UTF_8));
            restarted.store.quarantineWorker(id);
            assertEquals("FAILED",restarted.artifactIndex(id).get("archiveStatus"),"Durable quarantine overrides a READY filesystem status");
            assertEquals("worker_unverified",restarted.artifactIndex(id).get("reason"));
            assertEquals(false,restarted.artifactIndex(id).get("retryable"));
            assertThrows(ServerRuntime.ArchiveUnavailableException.class,()->restarted.openArtifactArchive(id,true));
            assertThrows(ServerRuntime.ArchiveUnavailableException.class,()->restarted.archiveEntry(id,"case.log"));
        }
    }

    @Test void quarantinedHistoryCannotStarveTheBoundedRetentionPage() throws Exception {
        ServerConfig config=config();
        try(JobStore store=new JobStore(config)) {
            String url="jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')
                    +";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
            try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.prepareStatement(
                    "INSERT INTO jobs(job_id,command,package_id,principal,status,created_at,finished_at,att_version,request_summary,worker_quarantined,artifact_recovery) VALUES(?,'run','p','test','PASS',?,?,'test','{}',?,FALSE)")) {
                for(int i=1;i<=502;i++) {
                    statement.setString(1,id(i));statement.setString(2,Instant.EPOCH.toString());
                    statement.setString(3,Instant.EPOCH.plusSeconds(i).toString());statement.setBoolean(4,i<=501);statement.addBatch();
                }
                statement.executeBatch();
            }
            assertEquals(java.util.List.of(id(502)),store.expiredJobIds(Instant.now()));
            assertTrue(store.workerMayBeAlive(id(1)),"Quarantine remains a durable deletion/publication fence");
        }
    }

    private ServerConfig config() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("packages"));
        Path pkg=Files.createDirectories(allowed.resolve("p"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").startsWith("Windows")?"java.exe":"java");
        Path yaml=Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: '"+temp.resolve("data")
                +"'\n  javaExecutable: '"+java+"'\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 0\npackages:\n  allowedRoots:\n    - '"+allowed+"'\n  entries:\n    p: '"+pkg+"'\n");
        return ServerConfig.load(yaml);
    }

    private static String id(int number){return String.format("J%016X",number);}
}
