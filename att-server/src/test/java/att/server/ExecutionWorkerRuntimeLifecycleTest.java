package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** End-to-end Job lifecycle checks for the optional one-shot execution pool. */
class ExecutionWorkerRuntimeLifecycleTest {
    @TempDir Path temp;

    @Test void warmWorkerLifecycleStopsTreesAndPersistsUnverifiedQuarantine() throws Exception {
        assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),"Uses a POSIX child Tool fixture");
        Path allowed=Files.createDirectories(temp.resolve("allowed"));
        Path pkg=Files.createDirectories(allowed.resolve("p"));
        Path templates=Files.createDirectories(pkg.resolve("templates"));
        Files.createDirectories(pkg.resolve("testcase"));
        Path tools=Files.createDirectories(pkg.resolve("tools"));
        Path holdScript=tools.resolve("hold.sh");
        Files.writeString(holdScript,"#!/bin/sh\n"
                +"printf '%s' \"$$\" > \"$ATT_ROOT_DIR/tools/child.pid\"\n"
                +"touch \"$ATT_ROOT_DIR/tools/started\"\n"
                +"exec sleep 60\n");
        holdScript.toFile().setExecutable(true);
        Files.writeString(Files.createDirectories(templates.resolve("HOLD")).resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: HOLD\ndescription: Long child Tool for Worker lifecycle tests\n"
                +"actions:\n  hold:\n    type: tool\n    call: '#{hold()}'\n");
        Files.writeString(Files.createDirectories(templates.resolve("TIMEOUT")).resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: TIMEOUT\ndescription: Timed child Tool for Worker lifecycle tests\n"
                +"actions:\n  hold:\n    type: tool\n    call: '#{hold()}'\n    timeoutMs: 300\n");

        Path runtimeConfig=temp.resolve("local/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\ntemplates: {root: templates}\ntestcase: {root: testcase}\n"
                +"tools:\n  hold:\n    name: Hold\n    description: Hold the Worker for lifecycle checks\n    command: [./tools/hold.sh]\n    stdoutFormat: text\n    arguments: {}\n");
        Path serverConfig=temp.resolve("server.yaml");
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n"
                +"  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("data"))+"\n  auth: {mode: local}\n"
                +"  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"workers:\n  maxConcurrent: 2\n  queuedLimit: 1\n  heapMaxMb: 128\n"
                +"  executionPool: {enabled: true, minIdle: 1, maxIdle: 1, heapBudgetMb: 128, packageIds: [p]}\n"
                +"packages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(pkg)+"\n");
        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        ServerConfig poolConfig=ServerConfig.load(serverConfig);
        String quarantinedJobId;
        try(ServerRuntime runtime=new ServerRuntime(poolConfig,libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            awaitReadyWorkers(runtime,1,15);
            Job cancelled=submit(runtime,"HOLD");
            await(()->cancelled.terminal()||Files.exists(tools.resolve("started")),20);
            assertTrue(Files.exists(tools.resolve("started")),"HOLD Tool did not start; Job="+runtime.jobRecord(cancelled.id)
                    +", events="+runtime.events(cancelled.id).after(0));
            assertEquals("warm",cancelled.workerDispatch,"The cancellation path must use a leased prewarm Worker");
            long cancelledWorker=cancelled.workerPid.longValue();
            long cancelledChild=Long.parseLong(Files.readString(tools.resolve("child.pid")));
            assertEquals("CANCELLED",runtime.cancel(cancelled.id,"alice"));
            await(()->cancelled.terminal()&&!ProcessHandle.of(cancelledWorker).map(ProcessHandle::isAlive).orElse(false)
                    &&!ProcessHandle.of(cancelledChild).map(ProcessHandle::isAlive).orElse(false),15);
            List<String> cancelledEvents=runtime.events(cancelled.id).after(0).stream().map(event->String.valueOf(event.get("event"))).toList();
            assertTrue(cancelledEvents.contains("result"),cancelledEvents.toString());
            awaitArchive(runtime,cancelled.id);

            awaitReadyWorkers(runtime,1,15);
            Files.deleteIfExists(tools.resolve("started"));
            Job timedOut=submit(runtime,"TIMEOUT");
            await(()->Files.exists(tools.resolve("started"))&&timedOut.workerPid!=null,20);
            assertEquals("warm",timedOut.workerDispatch,"The action-timeout path must also use a one-shot prewarm Worker");
            long timeoutWorker=timedOut.workerPid.longValue();
            long timeoutChild=Long.parseLong(Files.readString(tools.resolve("child.pid")));
            await(()->timedOut.terminal(),20);
            assertNotEquals("PASS",timedOut.status,"The timed Tool must not be reported as a successful Job");
            await(()->!ProcessHandle.of(timeoutWorker).map(ProcessHandle::isAlive).orElse(false)
                    &&!ProcessHandle.of(timeoutChild).map(ProcessHandle::isAlive).orElse(false),15);
            awaitArchive(runtime,timedOut.id);
            assertNotEquals(cancelledWorker,timeoutWorker,"A one-shot Worker PID cannot serve two Jobs");

            awaitReadyWorkers(runtime,1,15);
            Files.deleteIfExists(tools.resolve("started"));
            Job uncertain=submit(runtime,"HOLD");
            await(()->Files.exists(tools.resolve("started"))&&uncertain.workerPid!=null,20);
            assertEquals("warm",uncertain.workerDispatch);
            long uncertainWorker=uncertain.workerPid.longValue();
            long uncertainChild=Long.parseLong(Files.readString(tools.resolve("child.pid")));
            assertNotNull(uncertain.processFence);
            uncertain.processFence.unverified();
            assertEquals("CANCELLED",runtime.cancel(uncertain.id,"alice"));
            await(()->!ProcessHandle.of(uncertainWorker).map(ProcessHandle::isAlive).orElse(false)
                    &&!ProcessHandle.of(uncertainChild).map(ProcessHandle::isAlive).orElse(false),15);
            long quarantineDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!runtime.store.workerQuarantined(uncertain.id)&&System.nanoTime()<quarantineDeadline) {
                runtime.reconcileWorkerReleases();Thread.sleep(20);
            }
            assertTrue(runtime.store.workerQuarantined(uncertain.id),"Unverified warm cleanup must be durably quarantined");
            while(!"worker_unverified".equals(runtime.artifactIndex(uncertain.id).get("reason"))
                    &&System.nanoTime()<quarantineDeadline)Thread.sleep(20);
            assertEquals("worker_unverified",runtime.artifactIndex(uncertain.id).get("reason"));
            assertFalse(Files.exists(runtime.artifactArchivePath(uncertain.id)),"An unverified Worker cannot publish artifacts");
            assertTrue(Files.isDirectory(runtime.outputDirectoryForJob(uncertain.id)),"Quarantine retains private evidence");
            assertThrows(IllegalArgumentException.class,()->runtime.retryArtifactArchive(uncertain.id));
            quarantinedJobId=uncertain.id;
        }
        try(ServerRuntime restarted=new ServerRuntime(poolConfig,libs.toString())) {
            assertTrue(restarted.store.workerQuarantined(quarantinedJobId),"Warm-worker quarantine must survive Server restart");
            assertEquals("worker_unverified",restarted.artifactIndex(quarantinedJobId).get("reason"));
            assertFalse(Files.exists(restarted.artifactArchivePath(quarantinedJobId)));
            assertTrue(Files.isDirectory(restarted.outputDirectoryForJob(quarantinedJobId)));
        }
    }

    private Job submit(ServerRuntime runtime,String template) throws Exception {
        var definition=ServerRuntime.JSON.createObjectNode();
        definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");
        definition.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id",template)));
        definition.set("input",ServerRuntime.JSON.createObjectNode());definition.set("options",ServerRuntime.JSON.valueToTree(Map.of("debug",false)));
        Map<String,Object> draft=runtime.createRunDraft(definition,"alice");
        Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draft.get("draftId"))),"alice","alice");
        return runtime.jobs.get(String.valueOf(accepted.get("jobId")));
    }

    private static void awaitArchive(ServerRuntime runtime,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!"READY".equals(runtime.artifactIndex(id).get("archiveStatus"))&&System.nanoTime()<deadline)Thread.sleep(20);
        assertEquals("READY",runtime.artifactIndex(id).get("archiveStatus"));
        assertTrue(Files.isRegularFile(runtime.artifactArchivePath(id)));
    }

    private static void awaitReadyWorkers(ServerRuntime runtime,int expected,int seconds) throws Exception {
        var poolField=ServerRuntime.class.getDeclaredField("executionWorkers");poolField.setAccessible(true);
        Object pool=poolField.get(runtime);var ownedField=pool.getClass().getDeclaredField("owned");ownedField.setAccessible(true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline) {
            int ready=0;
            synchronized(pool) {
                for(Object idle:(List<?>)ownedField.get(pool)) {
                    var readyField=idle.getClass().getDeclaredField("readyNanos");readyField.setAccessible(true);
                    var retiredField=idle.getClass().getDeclaredField("retired");retiredField.setAccessible(true);
                    var processField=idle.getClass().getDeclaredField("process");processField.setAccessible(true);
                    Process process=(Process)processField.get(idle);
                    if(!retiredField.getBoolean(idle)&&readyField.getLong(idle)!=0&&process!=null&&process.isAlive())ready++;
                }
            }
            if(ready==expected)return;
            Thread.sleep(25);
        }
        fail("Expected "+expected+" ready prewarmed Workers within "+seconds+" seconds");
    }

    private static void await(BooleanSupplier condition,int seconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"Lifecycle condition did not complete within "+seconds+" seconds");
    }
}
