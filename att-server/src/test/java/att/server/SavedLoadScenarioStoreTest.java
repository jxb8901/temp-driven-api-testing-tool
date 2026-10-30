package att.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SavedLoadScenarioStoreTest {
    @TempDir Path temp;

    private ServerConfig config() throws Exception {
        Path pkg=Files.createDirectories(temp.resolve("pkg"));
        Path file=Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: "+temp.resolve("data")+"\nworkers: {}\npackages:\n  allowedRoots: ["+pkg+"]\n  entries:\n    p: "+pkg+"\n");
        return ServerConfig.load(file);
    }
    private SavedLoadScenario record(String id) {
        SavedLoadScenario v=new SavedLoadScenario();v.id=id;v.ownerSubject="local:alice";v.packageId="p";
        v.name="Payments";v.description="";v.tagsJson="[\"nightly\"]";v.authoredJson="{\"load\":{\"arrivalRate\":\"100/s\"}}";
        v.model="arrivalRate";v.sourceRevision="a".repeat(64);v.revision=1;
        v.createdAt="2026-01-01T00:00:00Z";v.updatedAt=v.createdAt;return v;
    }
    @Test void persistsPrivatelyWithOptimisticRevisionsAndNoDefinitionInList() throws Exception {
        ServerConfig config=config();String id="LS"+"A".repeat(32);
        try(JobStore store=new JobStore(config)) {
            store.createSavedLoadScenario(record(id),1);
            assertThrows(IllegalArgumentException.class,()->store.createSavedLoadScenario(record("LS"+"B".repeat(32)),1));
            assertNull(store.savedLoadScenario(id,"local:bob"));
            assertEquals(List.of(),store.savedLoadScenarioSummaries("local:bob",10,0));
            assertFalse(store.savedLoadScenarioSummaries("local:alice",10,0).get(0).containsKey("authoredJson"));
        }
        try(JobStore store=new JobStore(config)) {
            SavedLoadScenario update=store.savedLoadScenario(id,"local:alice");assertEquals(1,update.revision);
            update.authoredJson="{\"load\":{\"arrivalRate\":\"150/s\"}}";update.revision=2;
            assertTrue(store.updateSavedLoadScenario(update,1));
            assertFalse(store.updateSavedLoadScenario(update,1));
            assertFalse(store.deleteSavedLoadScenario(id,"local:bob",2));
            assertFalse(store.deleteSavedLoadScenario(id,"local:alice",1));
            assertEquals(update.authoredJson,store.savedLoadScenario(id,"local:alice").authoredJson);
            assertTrue(store.deleteSavedLoadScenario(id,"local:alice",2));
            assertNull(store.savedLoadScenario(id,"local:alice"));
        }
    }
    @Test void concurrentStoresEnforceOwnerQuotaAndRevision() throws Exception {
        ServerConfig config=config();
        try(JobStore first=new JobStore(config);JobStore second=new JobStore(config)) {
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            var start=new java.util.concurrent.CountDownLatch(1);
            try {
                var a=pool.submit(() -> { start.await(); try { first.createSavedLoadScenario(record("LS"+"A".repeat(32)),1); return true; } catch(IllegalArgumentException quota) { return false; } });
                var b=pool.submit(() -> { start.await(); try { second.createSavedLoadScenario(record("LS"+"B".repeat(32)),1); return true; } catch(IllegalArgumentException quota) { return false; } });
                start.countDown();assertNotEquals(a.get(),b.get());
                var items=first.savedLoadScenarioSummaries("local:alice",10,0);assertEquals(1,items.size());
                String id=(String)items.get(0).get("id");
                SavedLoadScenario v1=first.savedLoadScenario(id,"local:alice"),v2=second.savedLoadScenario(id,"local:alice");
                v1.revision=2;v2.revision=2;v1.name="one";v2.name="two";
                var gate=new java.util.concurrent.CountDownLatch(1);
                var c=pool.submit(() -> {gate.await();return first.updateSavedLoadScenario(v1,1);});
                var d=pool.submit(() -> {gate.await();return second.updateSavedLoadScenario(v2,1);});
                gate.countDown();assertNotEquals(c.get(),d.get());
                assertEquals(2,first.savedLoadScenario(id,"local:alice").revision);
            } finally {pool.shutdownNow();}
        }
    }

    @Test void scheduleCreationLocksSourceRevisionAndKeepsIndependentSnapshot() throws Exception {
        ServerConfig config=config();String id="LS"+"A".repeat(32);
        try(JobStore store=new JobStore(config)) {
            SavedLoadScenario source=record(id);store.createSavedLoadScenario(source,10);
            LoadSchedule schedule=new LoadSchedule();schedule.id="S1";schedule.packageId="p";schedule.savedScenarioId=id;
            schedule.savedScenarioRevision=1L;schedule.expectedRevisionDigest="a".repeat(64);schedule.updatedAt="2026-01-01T00:00:00Z";
            schedule.inlineScenario=java.util.Map.of("load",java.util.Map.of("arrivalRate","100/s"));
            SavedLoadScenario changed=store.savedLoadScenario(id,"local:alice");changed.revision=2;
            changed.authoredJson="{\"load\":{\"arrivalRate\":\"150/s\"}}";
            assertTrue(store.updateSavedLoadScenario(changed,1));
            assertThrows(ServerRuntime.SavedScenarioConflictException.class,()->store.createScheduleFromSaved(schedule,"local:alice",1,10));
            assertNull(store.schedule("S1"));
            schedule.savedScenarioRevision=2L;store.createScheduleFromSaved(schedule,"local:alice",2,10);
            assertTrue(store.deleteSavedLoadScenario(id,"local:alice",2));
            LoadSchedule retained=store.schedule("S1");
            assertEquals(2L,retained.savedScenarioRevision);assertEquals(id,retained.savedScenarioId);
            assertEquals("a".repeat(64),retained.expectedRevisionDigest);
            assertEquals("100/s",((java.util.Map<?,?>)retained.inlineScenario.get("load")).get("arrivalRate"));
        }
    }

    @Test void validatedSubmissionLocksRevisionUntilAcceptance() throws Exception {
        ServerConfig config=config();String id="LS"+"A".repeat(32);
        try(JobStore first=new JobStore(config);JobStore second=new JobStore(config)) {
            first.createSavedLoadScenario(record(id),10);
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            try {
                var submission=pool.submit(() -> first.withSavedRevision(id,"local:alice",1,"p",() -> {
                    entered.countDown();assertTrue(release.await(2,java.util.concurrent.TimeUnit.SECONDS));return "accepted";
                }));
                assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
                SavedLoadScenario changed=second.savedLoadScenario(id,"local:alice");changed.revision=2;
                var update=pool.submit(() -> second.updateSavedLoadScenario(changed,1));
                assertThrows(java.util.concurrent.TimeoutException.class,()->update.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));
                release.countDown();assertEquals("accepted",submission.get(1,java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(update.get(1,java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(ServerRuntime.SavedScenarioConflictException.class,
                        ()->first.withSavedRevision(id,"local:alice",1,"p",()->"wrong"));
            } finally {release.countDown();pool.shutdownNow();}
        }
    }

}
