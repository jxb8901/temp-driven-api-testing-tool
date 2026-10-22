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
                for(int i=0;i<128;i++)events.append("log",Map.of("console","line "+i));
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
                assertEquals(128,replay.latest());assertEquals(64,replay.after(0).size());
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
    @Test void failedStartupReleasesStoreAndAllRuntimeThreads() throws Exception {
        ServerConfig config=config();
        Files.createDirectories(config.dataDir.resolve("jobs"));
        Files.writeString(config.dataDir.resolve("jobs/not-a-directory"),"blocked");
        // Corrupt a recovered job journal parent so recovery fails after executor allocation.
        try(JobStore store=new JobStore(config)) {
            Job job=new Job("J0000000000000001","run","p","alice",null,null);
            store.insert(job,"{}");
        }
        Files.writeString(config.dataDir.resolve("jobs/J0000000000000001"),"blocked");
        assertThrows(Exception.class,()->new ServerRuntime(config,null));
        try(JobStore store=new JobStore(config)){assertNotNull(store.get("J0000000000000001"));}
        assertTimeoutPreemptively(Duration.ofSeconds(5),()->{
            while(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("att-server-")))Thread.sleep(10);
        });
    }
}
