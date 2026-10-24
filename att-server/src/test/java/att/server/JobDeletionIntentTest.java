package att.server;

import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

class JobDeletionIntentTest {
    @TempDir Path temp;
    private ServerConfig config() throws Exception {
        Path pkg=Files.createDirectories(temp.resolve("package"));
        Path file=temp.resolve("server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+temp.resolve("data")+"\nworkers: {}\npackages:\n  allowedRoots: ["+pkg+"]\n  entries:\n    p: "+pkg+"\n");
        return ServerConfig.load(file);
    }
    private Job terminal(JobStore store,ServerConfig config,String id) throws Exception {
        Job job=new Job(id,"run","p","alice",new WorkerRequest(),new JobEvents(config.dataDir.resolve("jobs/"+id+"/events.jsonl"),100));
        store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();store.update(job);store.artifactRecoveryNeeded(id,false);return job;
    }
    @Test void markSurvivesRestartAndUnmarkCannotRestoreClaimedDeletion() throws Exception {
        ServerConfig config=config();String id="J1111111111111111";
        try(JobStore store=new JobStore(config)) {
            terminal(store,config,id);
            assertEquals("MARKED",store.setDeletionRequested(id,true,"alice",100));
            assertEquals("ALREADY_IN_STATE",store.setDeletionRequested(id,true,"alice",200));
            assertEquals("DELETE_REQUESTED",store.get(id).get("deletionState"));
            assertFalse(store.setPinned(id,true,"bob"));
            assertFalse(store.claimRequestedDeletion(id,99));
        }
        try(JobStore store=new JobStore(config)) {
            assertEquals(java.util.List.of(id),store.requestedDeletionIds(100));
            assertEquals("UNMARKED",store.setDeletionRequested(id,false,"alice",300));
            assertFalse(store.claimRequestedDeletion(id,1000));
            assertTrue(store.setPinned(id,true,"bob"));
            assertEquals("PINNED_UNPIN_FIRST",store.setDeletionRequested(id,true,"alice",400));
            assertTrue(store.setPinned(id,false,"bob"));
            assertEquals("MARKED",store.setDeletionRequested(id,true,"alice",500));
            assertTrue(store.claimRequestedDeletion(id,500));
            assertEquals("ALREADY_CLAIMED",store.setDeletionRequested(id,false,"alice",600));
            assertNull(store.get(id));
            assertTrue(store.jobRowExists(id));
            assertEquals("alice",store.deletionActor(id));
            assertTrue(store.auditOutcomes("JOB_DELETE_UNMARK").contains("ALREADY_CLAIMED"));
        }
    }
    @Test void unmarkAndClaimRaceHasOnlyOneWinner() throws Exception {
        ServerConfig config=config();
        try(JobStore store=new JobStore(config);JobStore other=new JobStore(config)) {
            for(int i=0;i<20;i++) {
                String id="J"+String.format("%016X",i);terminal(store,config,id);
                store.setDeletionRequested(id,true,"alice",100);
                var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
                try {
                    var claim=executor.submit(()->{start.await();return store.claimRequestedDeletion(id,100);});
                    var unmark=executor.submit(()->{start.await();return other.setDeletionRequested(id,false,"alice",200);});
                    start.countDown();boolean claimed=claim.get();String outcome=unmark.get();
                    assertEquals(claimed?"ALREADY_CLAIMED":"UNMARKED",outcome);
                    assertEquals(claimed,store.get(id)==null);
                } finally {executor.shutdownNow();}
            }
        }
    }
    @Test void pinAndMarkRaceCannotLeavePinnedPendingJob() throws Exception {
        ServerConfig config=config();
        try(JobStore store=new JobStore(config);JobStore other=new JobStore(config)) {
            for(int i=100;i<120;i++) {
                String id="J"+String.format("%016X",i);terminal(store,config,id);
                var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
                try {
                    var pin=executor.submit(()->{start.await();return other.setPinned(id,true,"bob");});
                    var mark=executor.submit(()->{start.await();return store.setDeletionRequested(id,true,"alice",100);});
                    start.countDown();boolean pinned=pin.get();String outcome=mark.get();
                    assertEquals(pinned?"PINNED_UNPIN_FIRST":"MARKED",outcome);
                    var row=store.get(id);assertEquals(pinned,row.get("pinned"));assertEquals(pinned?null:"DELETE_REQUESTED",row.get("deletionState"));
                } finally {executor.shutdownNow();}
            }
        }
    }
    @Test void recoveryAndQuarantineFenceIntentAndClaim() throws Exception {
        ServerConfig config=config();
        try(JobStore store=new JobStore(config)) {
            Job job=terminal(store,config,"J2222222222222222");
            store.artifactRecoveryNeeded(job.id,true);
            assertEquals("NOT_ELIGIBLE",store.setDeletionRequested(job.id,true,"alice",100));
            store.artifactRecoveryNeeded(job.id,false);assertEquals("MARKED",store.setDeletionRequested(job.id,true,"alice",100));
            store.quarantineWorker(job.id);assertFalse(store.claimRequestedDeletion(job.id,100));
            assertEquals("UNMARKED",store.setDeletionRequested(job.id,false,"alice",200));
        }
    }
}
