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

    @Test void incompletePendingMarkersKeepStartupAvailableAndPoolCold() throws Exception {
        int index=0;
        for(String content:List.of("","{\"version\":1"))for(boolean enabled:List.of(false,true)) {
            Path base=temp.resolve("incomplete-pending-"+(index++));ServerConfig config=config(base,enabled,List.of("p"),1,2);
            Path registry=Files.createDirectories(config.dataDir.resolve("prewarm"));
            Path pending=registry.resolve(".prewarm-interrupted.pending");Files.writeString(pending,content);
            List<Process> started=new CopyOnWriteArrayList<>();
            try(ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->builder(false),builder->{
                Process process=builder.start();started.add(process);return process;
            },()->"v1","a".repeat(64))) {
                assertTrue((Boolean)pool.metrics().get("recoveryUncertain"));
                assertEquals(0L,pool.metrics().get("starts"));
                assertTrue(Files.exists(pending),"Unparseable identity evidence must be retained");
                Thread.sleep(600);
                assertTrue(started.isEmpty(),"An uncertain recovery boot must not create idle Workers");
                assertEquals(0L,pool.metrics().get("starts"));
            }
            for(Process process:started)assertFalse(process.isAlive());
        }
    }

    @Test void validPendingAndCommittedMarkersRecoverWithPoolEnabledOrDisabled() throws Exception {
        int index=0;
        for(String suffix:List.of(".pending",".json"))for(boolean enabled:List.of(false,true)) {
            Path base=temp.resolve("valid-recovery-"+(index++));ServerConfig config=config(base,enabled,List.of("p"),0,1);
            Path registry=Files.createDirectories(config.dataDir.resolve("prewarm"));
            ProcessBuilder abandonedBuilder=builder(false);abandonedBuilder.environment().remove("POOL_SECRET");
            abandonedBuilder.command().add("child");Process abandoned=abandonedBuilder.start();
            Path marker=registry.resolve(suffix.equals(".pending")?".prewarm-crashed.pending":"crashed.json");
            try {
                Files.write(marker,ServerRuntime.JSON.writeValueAsBytes(Map.of("version",1,"processes",List.of(
                        Map.of("pid",abandoned.pid(),"start",abandoned.info().startInstant().orElseThrow().toString())))));
                try(ExecutionWorkerPool recovered=new ExecutionWorkerPool(config,id->{
                    throw new AssertionError("minIdle=0 must not start an idle process");
                },ProcessBuilder::start,()->"v1","a".repeat(64))) {
                    assertTrue(abandoned.waitFor(5,TimeUnit.SECONDS),"Recovery must terminate and reap the recorded process");
                    assertFalse(abandoned.isAlive(),"Recovery must stop the recorded process");
                    assertFalse(Files.exists(marker),"Recovered identity markers must be removed only after cleanup");
                    assertEquals(0L,recovered.metrics().get("starts"));
                    assertFalse((Boolean)recovered.metrics().get("recoveryUncertain"));
                }
            } finally {
                abandoned.destroyForcibly();abandoned.waitFor(5,TimeUnit.SECONDS);
            }
        }
    }

    @Test void warmAndColdWorkersStartWithTheSameExplicitTimezone() throws Exception {
        Path base=temp.resolve("timezone-pool");ServerConfig config=config(base,true,List.of("p"),1,1);
        Path packageRoot=config.packages.get("p");Path warmZone=temp.resolve("warm-zone.txt");Path coldZone=temp.resolve("cold-zone.txt");
        List<Process> started=new CopyOnWriteArrayList<>();Process cold=null;
        try(ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->{
            ProcessBuilder process=builder(false);process.environment().put("TZ","Asia/Hong_Kong");
            process.command().addAll(List.of("timezone",warmZone.toString()));return process;
        },builder->{Process process=builder.start();started.add(process);return process;},()->"v1","a".repeat(64))) {
            await(()->((Number)pool.metrics().get("ready")).intValue()==1&&Files.exists(warmZone));
            ProcessBuilder coldBuilder=builder(false);coldBuilder.environment().remove("POOL_SECRET");
            coldBuilder.environment().put("TZ","Asia/Hong_Kong");coldBuilder.directory(packageRoot.toFile());
            coldBuilder.command().addAll(List.of("timezone",coldZone.toString()));cold=coldBuilder.start();
            Process coldProcess=cold;await(()->nonEmpty(coldZone));
            assertEquals(Files.readString(coldZone),Files.readString(warmZone));
            assertEquals("Asia/Hong_Kong",Files.readString(warmZone));
            coldProcess.getOutputStream().close();assertTrue(coldProcess.waitFor(5,TimeUnit.SECONDS));
        } finally {
            if(cold!=null){cold.destroyForcibly();cold.waitFor(5,TimeUnit.SECONDS);}
        }
        for(Process process:started)assertFalse(process.isAlive());
    }

    @Test void demandedPackageGetsFairWarmCapacityBeyondGlobalMinIdle() throws Exception {
        Path base=temp.resolve("package-fairness");ServerConfig config=config(base,true,List.of("a","b"),1,2);
        Path aCwd=temp.resolve("a-cwd.txt"),bCwd=temp.resolve("b-cwd.txt");List<Process> started=new CopyOnWriteArrayList<>();
        try(ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->{
            ProcessBuilder process=builder(false);process.command().addAll(List.of("cwd",("a".equals(id)?aCwd:bCwd).toString()));return process;
        },builder->{Process process=builder.start();started.add(process);return process;},()->"v1","a".repeat(64))) {
            await(()->Files.exists(aCwd)||Files.exists(bCwd));
            String firstId=Files.exists(aCwd)?"a":"b",secondId="a".equals(firstId)?"b":"a";
            Path firstCwd="a".equals(firstId)?aCwd:bCwd,secondCwd="a".equals(secondId)?aCwd:bCwd;
            Path firstRoot=config.packages.get(firstId),secondRoot=config.packages.get(secondId);
            await(()->pool.hasReadyCapacity(firstId)&&Files.exists(firstCwd));
            assertEquals(firstRoot.toRealPath().toString(),Files.readString(firstCwd));
            assertFalse(pool.hasReadyCapacity(secondId),"The other package initially has no idle reservation");
            await(()->pool.hasReadyCapacity(secondId)&&Files.exists(secondCwd));
            assertEquals(secondRoot.toRealPath().toString(),Files.readString(secondCwd));
            assertTrue(pool.hasReadyCapacity(firstId),"Demand expansion must retain the first package's idle Worker");
            ExecutionWorkerPool.Lease lease=pool.lease(secondId);assertNotNull(lease);
            lease.process.destroyForcibly();assertTrue(lease.process.waitFor(5,TimeUnit.SECONDS));lease.transferred();
        }
        for(Process process:started)assertFalse(process.isAlive());
    }

    @Test void packageWithoutWarmSlotUsesColdPathAtFullIdleCap() throws Exception {
        Path base=temp.resolve("package-full-capacity");ServerConfig config=config(base,true,List.of("a","b"),1,1);
        Path aCwd=temp.resolve("full-cap-a-cwd.txt"),bCwd=temp.resolve("full-cap-b-cwd.txt");
        List<Process> started=new CopyOnWriteArrayList<>();
        try(ExecutionWorkerPool pool=new ExecutionWorkerPool(config,id->{
            ProcessBuilder process=builder(false);process.command().addAll(List.of("cwd",("a".equals(id)?aCwd:bCwd).toString()));return process;
        },builder->{Process process=builder.start();started.add(process);return process;},()->"v1","a".repeat(64))) {
            await(()->Files.exists(aCwd)||Files.exists(bCwd));
            String warmId=Files.exists(aCwd)?"a":"b",coldId="a".equals(warmId)?"b":"a";
            await(()->pool.hasReadyCapacity(warmId));
            assertEquals(1,started.size(),"The configured maximum is one idle Worker");
            Process warm=started.get(0);
            assertFalse(pool.hasReadyCapacity(coldId),"The second package has no warm reservation at the configured cap");
            assertNull(pool.lease(coldId),"The uncovered package remains on the existing cold dispatch path");
            for(int i=0;i<5;i++) {
                Thread.sleep(250);
                assertFalse(pool.hasReadyCapacity(coldId));
            }
            assertTrue(warm.isAlive(),"Demand for another package must not evict the existing idle Worker");
            assertTrue(pool.hasReadyCapacity(warmId));
            assertEquals(1,started.size(),"A hard maxIdle cap must not create or rotate extra Workers");
            assertEquals(1L,pool.metrics().get("starts"));
            assertEquals(1,pool.metrics().get("owned"));
        }
        for(Process process:started)assertFalse(process.isAlive());
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
        return config(temp,true,List.of("p"),1,2);
    }
    private ServerConfig config(Path base,boolean enabled,List<String> ids,int minIdle,int maxIdle) throws Exception {
        Files.createDirectories(base);Path allowed=Files.createDirectories(base.resolve("packages"));
        Map<String,Path> packages=new LinkedHashMap<>();
        for(String id:ids)packages.put(id,Files.createDirectories(allowed.resolve(id)));
        Path yaml=base.resolve("server.yaml");StringBuilder source=new StringBuilder("server:\n  dataDir: ").append(base.resolve("data"))
                .append("\nworkers:\n  heapMaxMb: 128\n  executionPool: {enabled: ").append(enabled)
                .append(", minIdle: ").append(minIdle).append(", maxIdle: ").append(maxIdle)
                .append(", heapBudgetMb: 256, packageIds: [").append(String.join(", ",ids)).append("]}\npackages:\n  allowedRoots: [")
                .append(allowed).append("]\n  entries:\n");
        for(Map.Entry<String,Path> entry:packages.entrySet())source.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        Files.writeString(yaml,source);
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
    private boolean nonEmpty(Path path) {
        try {return Files.size(path)>0;}catch(Exception unavailable){return false;}
    }
}
