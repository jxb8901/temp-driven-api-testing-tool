package att.server;

import att.worker.WorkerRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ServerAdmissionTest {
    @TempDir Path temp;

    @Test void queueRejectionCreatesNoDurableJobRecordOrDirectory() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"Uses a POSIX test launcher");
        CountDownLatch launched=new CountDownLatch(1),allowLaunch=new CountDownLatch(1);
        ServerRuntime runtime=runtime("queue",1,0,1,30,builder->{launched.countDown();await(allowLaunch);return builder.start();});
        String id=null;
        try {
            id=(String)runtime.submit("validate",request(),"ci").get("jobId");assertTrue(launched.await(5,TimeUnit.SECONDS));
            assertThrows(ServerRuntime.QueueFullException.class,()->runtime.submit("validate",request(),"ci"));
            assertNotNull(runtime.store.get(id));
            try(var entries=Files.list(runtime.config.dataDir.resolve("jobs"))){assertEquals(1,entries.count(),"a rejected submission must not leave a job directory");}
            assertEquals(1,runtime.store.list(100).size(),"a rejected submission must not leave a job row");
        } finally {allowLaunch.countDown();awaitJobs(runtime);runtime.close();}
    }

    @Test void loadLimitIsAppliedBeforeGeneralWorkerThreadsAreOccupied() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows(),"Uses a POSIX test launcher");
        CountDownLatch firstLaunch=new CountDownLatch(1),secondLaunch=new CountDownLatch(1),allowLaunch=new CountDownLatch(1);java.util.concurrent.atomic.AtomicInteger launchCount=new java.util.concurrent.atomic.AtomicInteger();
        ServerRuntime runtime=runtime("load",2,1,1,30,builder->{if(launchCount.incrementAndGet()==1)firstLaunch.countDown();else secondLaunch.countDown();await(allowLaunch);return builder.start();});
        String loadId=null,runId=null;
        try {
            loadId=(String)runtime.submit("load",request(),"ci").get("jobId");assertTrue(firstLaunch.await(5,TimeUnit.SECONDS));
            assertThrows(ServerRuntime.QueueFullException.class,()->runtime.submit("load",request(),"ci"));
            runId=(String)runtime.submit("run",request(),"ci").get("jobId");
            assertTrue(secondLaunch.await(5,TimeUnit.SECONDS),"a Run must start while an admitted Load is waiting to launch");
        } finally {allowLaunch.countDown();awaitJobs(runtime);runtime.close();}
    }

    @Test void expiredTerminalJobsRemoveMetadataJournalAndArtifacts() throws Exception {
        ServerRuntime runtime=runtime("retention",1,1,1,1,ProcessBuilder::start);
        try {
            String id="J1234567890ABCDEF";Path directory=runtime.config.dataDir.resolve("jobs").resolve(id);Files.createDirectories(directory.resolve("output"));
            Files.writeString(directory.resolve("output/report.txt"),"expired");Job job=new Job(id,"run","p","ci",new WorkerRequest(),new JobEvents(directory.resolve("events.jsonl"),100));
            job.status="PASS";job.finishedAt=Instant.now().minusSeconds(TimeUnit.DAYS.toSeconds(2));runtime.store.insert(job,"{}");runtime.store.update(job);
            runtime.cleanupExpiredJobs();assertNull(runtime.store.get(id));assertFalse(Files.exists(directory));
        } finally {runtime.close();}
    }

    private ServerRuntime runtime(String name,int concurrent,int queue,int load,int retention,ServerRuntime.WorkerProcessLauncher launcher)throws Exception {
        Path allowed=Files.createDirectory(temp.resolve(name+"-packages"));Path pkg=Files.createDirectory(allowed.resolve("p"));
        Path script=temp.resolve(name+"-slow-java");Files.writeString(script,"#!/bin/sh\nsleep 1\n");script.toFile().setExecutable(true);
        Path yaml=temp.resolve(name+"-server.yaml");Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve(name+"-data")+"\n  javaExecutable: "+script+"\n  jobRetentionDays: "+retention+"\nworkers:\n  maxConcurrent: "+concurrent+"\n  queuedLimit: "+queue+"\n  maxConcurrentLoad: "+load+"\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        return new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve(name+"-libs")).toString(),launcher);
    }
    private static JsonNode request(){return ServerRuntime.JSON.createObjectNode().put("packageId","p");}
    private static void await(CountDownLatch gate)throws java.io.IOException {try{if(!gate.await(5,TimeUnit.SECONDS))throw new java.io.IOException("test launch gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("test launch gate interrupted",e);}}
    private static void awaitJobs(ServerRuntime runtime){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!runtime.jobs.isEmpty()&&System.nanoTime()<deadline)try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase().contains("win");}
}
