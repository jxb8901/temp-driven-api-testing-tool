package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionWorkerPoolTest {
    @TempDir Path temp;

    @Test void leasesReadyProcessesOnceReplenishesAndDiscardsChangedRuntime() throws Exception {
        ServerConfig config=config();AtomicReference<String> fingerprint=new AtomicReference<>("v1");
        List<Process> started=new CopyOnWriteArrayList<>();
        ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->builder(false),builder->{Process p=builder.start();started.add(p);return p;},fingerprint::get,"a".repeat(64));
        try {
            await(()->((Number)pool.metrics().get("ready")).intValue()==1);
            assertNull(pool.lease("other"));
            ExecutionWorkerPool.Lease first=pool.lease("p");assertNotNull(first);
            assertTrue(first.process.isAlive());assertTrue(Files.exists(first.marker));
            assertTrue(first.readyNanos>=first.spawnedNanos);
            first.transferred();assertFalse(Files.exists(first.marker));
            first.process.getOutputStream().close();assertTrue(first.process.waitFor(5,TimeUnit.SECONDS));
            await(()->((Number)pool.metrics().get("ready")).intValue()==1);
            ExecutionWorkerPool.Lease second=pool.lease("p");assertNotNull(second);
            assertNotEquals(first.process.pid(),second.process.pid());second.transferred();
            second.process.getOutputStream().close();assertTrue(second.process.waitFor(5,TimeUnit.SECONDS));
            await(()->((Number)pool.metrics().get("ready")).intValue()==1);
            fingerprint.set("v2");assertNull(pool.lease("p"));
            await(()->((Number)pool.metrics().get("ready")).intValue()==1);
            assertTrue(((Number)pool.metrics().get("owned")).intValue()<=config.executionPool.maxIdle);
            assertEquals(2L,pool.metrics().get("leases"));
        } finally {pool.close();}
        for(Process process:started)assertFalse(process.isAlive());
    }

    @Test void invalidHandshakeFallsBackAndRetriesAreBoundedByBackoff() throws Exception {
        ServerConfig config=config();List<Process> started=new CopyOnWriteArrayList<>();
        try(ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->builder(true),builder->{Process p=builder.start();started.add(p);return p;},()->"v1","a".repeat(64))) {
            await(()->((Number)pool.metrics().get("startupFailures")).intValue()>=1);
            assertEquals(1L,pool.metrics().get("starts"));assertNull(pool.lease("p"));
            assertEquals(0L,pool.metrics().get("leases"));
        }
        for(Process process:started)assertFalse(process.isAlive());
    }

    @Test void runtimeFingerprintDetectsJarChangesAndAdditionalLibraries() throws Exception {
        Path lib=Files.createDirectories(temp.resolve("lib"));Path addon=Files.createDirectories(temp.resolve("addon"));
        Files.writeString(lib.resolve("runtime.jar"),"v1");
        String before=ExecutionWorkerPool.fingerprint(lib,List.of(addon));
        Files.writeString(addon.resolve("extension.jar"),"new");
        assertNotEquals(before,ExecutionWorkerPool.fingerprint(lib,List.of(addon)));
    }

    private ServerConfig config() throws Exception {
        Path pkg=Files.createDirectories(temp.resolve("packages/p"));Path yaml=temp.resolve("server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("data")+"\nworkers:\n  heapMaxMb: 128\n"
                +"  executionPool: {enabled: true, minIdle: 1, maxIdle: 2, heapBudgetMb: 256, packageIds: [p]}\n"
                +"packages:\n  allowedRoots: ["+pkg.getParent()+"]\n  entries:\n    p: "+pkg+"\n");
        return ServerConfig.load(yaml);
    }
    private ProcessBuilder builder(boolean invalid) {
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),ExecutionPoolProcessFixture.class.getName()));
        if(invalid)command.add("invalid");
        ProcessBuilder builder=new ProcessBuilder(command);builder.environment().put("POOL_SECRET","must-be-removed");return builder;
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"Pool condition did not complete");
    }
}
