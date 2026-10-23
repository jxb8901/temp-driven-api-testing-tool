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

    @Test void deadIdleIsNeverLeasedAndReplacementHasAnotherPid() throws Exception {
        ServerConfig config=config();List<Process> started=new CopyOnWriteArrayList<>();
        try(ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->builder(false),b->{Process p=b.start();started.add(p);return p;},()->"v1","a".repeat(64))) {
            await(()->((Number)pool.metrics().get("ready")).intValue()==1);
            Process killed=started.get(0);killed.destroyForcibly();assertTrue(killed.waitFor(5,TimeUnit.SECONDS));
            ExecutionWorkerPool.Lease raced=pool.lease("p");
            if(raced!=null) {assertNotEquals(killed.pid(),raced.process.pid());raced.process.destroyForcibly();raced.process.waitFor();raced.transferred();}
            await(()->((Number)pool.metrics().get("ready")).intValue()==1&&started.size()>=2);
            ExecutionWorkerPool.Lease replacement=pool.lease("p");assertNotNull(replacement);
            assertNotEquals(killed.pid(),replacement.process.pid());
            replacement.process.destroyForcibly();assertTrue(replacement.process.waitFor(5,TimeUnit.SECONDS));replacement.transferred();
        }
        for(Process process:started)assertFalse(process.isAlive());
    }

    @Test void closeDoesNotStealTransferredJobOwnership() throws Exception {
        ServerConfig config=config();ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->builder(false),ProcessBuilder::start,()->"v1","a".repeat(64));
        ExecutionWorkerPool.Lease lease=null;
        try {
            await(()->((Number)pool.metrics().get("ready")).intValue()==1);
            lease=pool.lease("p");assertNotNull(lease);lease.transferred();pool.close();
            assertTrue(lease.process.isAlive(),"A leased Worker belongs to ServerRuntime's Job fence");
            assertFalse(Files.exists(lease.marker));
        } finally {pool.close();if(lease!=null){lease.process.destroyForcibly();lease.process.waitFor(5,TimeUnit.SECONDS);}}
    }

    @Test void restartEvenWhenDisabledStopsRecordedIdleAndDescendantsButNeverRecycledPid() throws Exception {
        ServerConfig enabled=config();Path registry=Files.createDirectories(enabled.dataDir.resolve("prewarm"));
        Path childPid=temp.resolve("child.pid");ProcessBuilder builder=builder(false);
        builder.environment().remove("POOL_SECRET");builder.command().addAll(List.of("descendant",childPid.toString()));
        Process root=builder.start();ProcessHandle child=null;
        Process unrelated=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),ExecutionPoolProcessFixture.class.getName(),"child").start();
        try {
            await(()->Files.exists(childPid));child=ProcessHandle.of(Long.parseLong(Files.readString(childPid))).orElseThrow();
            Path marker=registry.resolve("abandoned.json");
            Files.write(marker,ServerRuntime.JSON.writeValueAsBytes(Map.of("version",1,"processes",List.of(
                    Map.of("pid",root.pid(),"start",root.info().startInstant().orElseThrow().toString()),
                    Map.of("pid",unrelated.pid(),"start",unrelated.info().startInstant().orElseThrow().minusSeconds(60).toString())))));
            Path yaml=temp.resolve("server.yaml");Files.writeString(yaml,Files.readString(yaml).replace("enabled: true","enabled: false"));
            try(ExecutionWorkerPool recovered=new ExecutionWorkerPool(ServerConfig.load(yaml),id->{throw new AssertionError("Disabled pool must not launch");},ProcessBuilder::start,()->"v1","a".repeat(64))) {
                assertFalse(root.isAlive());assertFalse(child.isAlive());assertTrue(unrelated.isAlive());
                assertFalse(Files.exists(marker));assertEquals(0L,recovered.metrics().get("starts"));
            }
        } finally {
            root.destroyForcibly();root.waitFor(5,TimeUnit.SECONDS);
            if(child!=null)child.destroyForcibly();unrelated.destroyForcibly();unrelated.waitFor(5,TimeUnit.SECONDS);
        }
    }

    @Test void incompleteIdleFenceCannotBeLeasedOrEraseRecoveryEvidence() throws Exception {
        ServerConfig config=config();ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->builder(false),ProcessBuilder::start,()->"v1","a".repeat(64));
        try {
            await(()->((Number)pool.metrics().get("ready")).intValue()==1);
            // Inject the existing fence's documented uncertain-enumeration state.
            var owned=ExecutionWorkerPool.class.getDeclaredField("owned");owned.setAccessible(true);
            synchronized(pool) {
                Object idle=((List<?>)owned.get(pool)).get(0);
                var fence=idle.getClass().getDeclaredField("fence");fence.setAccessible(true);
                ((WorkerProcessFence)fence.get(idle)).unverified();
                assertNull(pool.lease("p"));
            }
            pool.close();
            try(var markers=Files.list(config.dataDir.resolve("prewarm"))) {assertTrue(markers.findAny().isPresent());}
        } finally {pool.close();}
    }

    @Test void unexpectedIdleDescendantIsNeverLeasedAndIsStoppedBeforeReplacement() throws Exception {
        ServerConfig config=config();Path childPid=temp.resolve("idle-child.pid");List<Process> roots=new CopyOnWriteArrayList<>();
        try(ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->{
            ProcessBuilder b=builder(false);b.command().addAll(List.of("descendant",childPid.toString()));return b;
        },b->{Process p=b.start();roots.add(p);return p;},()->"v1","a".repeat(64))) {
            await(()->Files.exists(childPid));
            long pid=Long.parseLong(Files.readString(childPid));
            assertNull(pool.lease("p"));
            await(()->((Number)pool.metrics().get("startupFailures")).longValue()>=1);
            await(()->roots.stream().noneMatch(Process::isAlive)&&ProcessHandle.of(pid).map(h->!h.isAlive()).orElse(true));
            assertEquals(0L,pool.metrics().get("leases"));
            assertEquals(1L,pool.metrics().get("starts"),"Failed containment must use startup backoff");
        }
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
