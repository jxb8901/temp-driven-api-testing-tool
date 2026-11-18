package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLifecycleTest {
    @TempDir Path temp;
    private ServerConfig config() throws Exception {
        Path root=Files.createDirectories(temp.resolve("packages/p"));
        Path file=temp.resolve("server.yaml");
        Files.writeString(file,"server:\n  dataDir: '"+temp.resolve("data")+"'\nworkers: {}\npackages:\n  allowedRoots: ['"+root+"']\n  entries: {p: '"+root+"'}\n");
        return ServerConfig.load(file);
    }
    private static ThreadPoolExecutor compactions(ServerRuntime runtime) throws Exception {
        var field=ServerRuntime.class.getDeclaredField("eventCompactions");field.setAccessible(true);return (ThreadPoolExecutor)field.get(runtime);
    }
    @Test void closeDrainsRunningAndQueuedCompactionsAndIsIdempotent() throws Exception {
        ServerConfig config=config();ServerRuntime runtime=new ServerRuntime(config,null);
        ThreadPoolExecutor executor=compactions(runtime);
        CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);
        try {
            for(int j=0;j<3;j++) {
                JobEvents events=new JobEvents(temp.resolve("journal-"+j+"/events.jsonl"),64,executor,()->{
                    started.countDown();try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                });
                for(int i=0;i<512;i++)events.append("log",Map.of("console","line "+i));
            }
            assertTrue(started.await(5,TimeUnit.SECONDS));assertFalse(executor.getQueue().isEmpty());
            ExecutorService closing=Executors.newSingleThreadExecutor();
            try {
                Future<?> closed=closing.submit(runtime::close);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!executor.isShutdown()&&System.nanoTime()<deadline)Thread.sleep(10);
                release.countDown();closed.get(10,TimeUnit.SECONDS);
            } finally {closing.shutdownNow();}
            assertTrue(executor.isTerminated());runtime.close();
            assertThrows(ServerRuntime.QueueFullException.class,()->runtime.submit("run",ServerRuntime.JSON.createObjectNode(),"alice"));
            assertThrows(RejectedExecutionException.class,()->executor.execute(()->{}));
            for(int j=0;j<3;j++) {
                JobEvents replay=new JobEvents(temp.resolve("journal-"+j+"/events.jsonl"),64);
                assertEquals(512,replay.latest());assertEquals(64,replay.after(0).size());
                assertFalse(Files.exists(temp.resolve("journal-"+j+"/events.jsonl.tmp")));
            }
            try(ServerRuntime reopened=new ServerRuntime(config,null)){assertFalse(compactions(reopened).isShutdown());}
        } finally {release.countDown();runtime.close();}
    }
    @Test void processLaunchUsesJvmLoaderAndRestoresCallerOnSuccessAndFailure() throws Exception {
        ClassLoader original=Thread.currentThread().getContextClassLoader();
        java.util.concurrent.atomic.AtomicBoolean fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(ServerRuntime runtime=new ServerRuntime(config(),null,builder->{
            assertSame(ClassLoader.getPlatformClassLoader(),Thread.currentThread().getContextClassLoader());
            if(fail.getAndSet(false))throw new java.io.IOException("launch failed");
            return builder.start();
        })) {
            ProcessBuilder command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            assertThrows(java.io.IOException.class,()->runtime.startWorkerProcess(command));
            assertSame(original,Thread.currentThread().getContextClassLoader());
            Process process=runtime.startWorkerProcess(command);
            try {assertTrue(process.waitFor(5,TimeUnit.SECONDS));}
            finally {process.destroyForcibly();}
            assertSame(original,Thread.currentThread().getContextClassLoader());
        }
    }
    @Test void sharedH2RegistrationSurvivesOneStoreClosingAndReloadsAfterLastClose() throws Exception {
        ServerConfig config=config();JobStore first=new JobStore(config),second=new JobStore(config);
        try {
            LoadSchedule schedule=new LoadSchedule();schedule.id="S1";schedule.name="Persisted";schedule.updatedAt="v1";
            first.saveSchedule(schedule);first.close();first.close();
            assertEquals("Persisted",second.schedule("S1").name);
            assertNotNull(DriverManager.getDriver("jdbc:h2:mem:test"));
        } finally {first.close();second.close();}
        try(JobStore reopened=new JobStore(config)){assertEquals("Persisted",reopened.schedule("S1").name);}
    }
    @Test void sourcePublicationAuditMigrationPersistsSnapshotAndValidationMetadata() throws Exception {
        ServerConfig config=config();
        Files.createDirectories(config.dataDir.resolve("db"));
        String url="jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')
                +";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;WRITE_DELAY=0";
        DriverManager.registerDriver(org.h2.Driver.load());
        try(java.sql.Connection connection=DriverManager.getConnection(url,"sa","");java.sql.Statement statement=connection.createStatement()) {
            statement.execute("CREATE TABLE source_publication_audit (transaction_id VARCHAR(40) PRIMARY KEY, principal VARCHAR(256) NOT NULL, action VARCHAR(32) NOT NULL, domain VARCHAR(64) NOT NULL, scope_id VARCHAR(128) NOT NULL, outcome VARCHAR(32) NOT NULL, before_revision VARCHAR(64) NOT NULL, after_revision VARCHAR(64) NOT NULL, request_id VARCHAR(128), resources_json CLOB NOT NULL, created_at VARCHAR(40) NOT NULL)");
            statement.execute("INSERT INTO source_publication_audit VALUES('T00000000000000000000000000000000','old-owner','OLD_PUBLISH','PACKAGE_SOURCE','p','COMMITTED','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',NULL,'[]','2026-01-01T00:00:00Z')");
        }

        SourceFileTransaction.AuditRecord record=new SourceFileTransaction.AuditRecord(
                "T11111111111111111111111111111111","alice",ChangeSetPublisher.PACKAGE_SOURCE,"p",
                "SOURCE_CHANGE_SET_PUBLISH","COMMITTED",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","request-256",
                List.of("load/sut-metrics/sources/runtime.yaml"),
                "R00000000000000000000000000000000","R11111111111111111111111111111111",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "NOT_APPLICABLE","2026-11-18T12:34:56Z");
        try(JobStore store=new JobStore(config)) {store.persistSourcePublicationAudit(record);}
        DriverManager.registerDriver(org.h2.Driver.load());

        try(java.sql.Connection connection=DriverManager.getConnection(url,"sa","");
            java.sql.PreparedStatement query=connection.prepareStatement("SELECT before_snapshot_id,after_snapshot_id,validation_revision,activation_status,created_at FROM source_publication_audit WHERE transaction_id=?")) {
            query.setString(1,record.transactionId);
            try(java.sql.ResultSet row=query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(record.beforeSnapshotId,row.getString(1));
                assertEquals(record.afterSnapshotId,row.getString(2));
                assertEquals(record.validationRevision,row.getString(3));
                assertEquals("NOT_APPLICABLE",row.getString(4));
                assertEquals(record.createdAt,row.getString(5));
            }
            try(java.sql.PreparedStatement old=connection.prepareStatement("SELECT before_snapshot_id,after_snapshot_id,validation_revision,activation_status FROM source_publication_audit WHERE transaction_id=?")) {
                old.setString(1,"T00000000000000000000000000000000");
                try(java.sql.ResultSet row=old.executeQuery()) {
                    assertTrue(row.next());
                    assertNull(row.getString(1));assertNull(row.getString(2));assertNull(row.getString(3));
                    assertEquals("UNKNOWN",row.getString(4));
                }
            }
        }
    }
    @Test void corruptRecoveredJournalDoesNotBlockStartupAndReleasesResources() throws Exception {
        ServerConfig config=config();
        Files.createDirectories(config.dataDir.resolve("jobs"));
        try(JobStore store=new JobStore(config)) {
            Job job=new Job("J0000000000000001","run","p","alice",null,null);
            store.insert(job,"{}");
        }
        // A path collision makes this recovered Job's event journal unreadable.
        Files.writeString(config.dataDir.resolve("jobs/J0000000000000001"),"blocked");

        ServerRuntime runtime=new ServerRuntime(config,null);
        try {
            assertEquals("ERROR",runtime.store.get("J0000000000000001").get("status"));
            assertEquals(1,runtime.store.list(100).size(),"The damaged Job metadata should remain available");
            JobEvents history=runtime.events("J0000000000000001");
            assertTrue(history.historyDegraded());
            assertEquals("ATT-JOURNAL-PERSISTENCE-DEGRADED",history.diagnostic().get("code"));
        } finally {runtime.close();}

        try(JobStore store=new JobStore(config)){assertNotNull(store.get("J0000000000000001"));}
        assertTimeoutPreemptively(Duration.ofSeconds(5),()->{
            while(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("att-server-")))Thread.sleep(10);
        });
    }
}
