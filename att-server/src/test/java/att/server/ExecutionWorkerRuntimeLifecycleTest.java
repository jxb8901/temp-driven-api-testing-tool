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

/** End-to-end lifecycle checks for source-pinned cold Workers with the optional execution pool enabled. */
class ExecutionWorkerRuntimeLifecycleTest {
    @TempDir Path temp;

    @Test void sourcePinnedColdWorkerLifecycleStopsTreesAndPersistsUnverifiedQuarantine() throws Exception {
        assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),"Uses a POSIX child Tool fixture");
        Path allowed=Files.createDirectories(temp.resolve("allowed"));
        Path pkg=Files.createDirectories(allowed.resolve("p"));
        Path templates=Files.createDirectories(pkg.resolve("templates"));
        Files.createDirectories(pkg.resolve("testcase"));
        Path tools=Files.createDirectories(pkg.resolve("tools"));
        Path holdScript=tools.resolve("hold.sh");
        Files.writeString(holdScript,"#!/bin/sh\n"
                +"printf '0\\n' > \"$ATT_CASE_OUTPUT_DIR/heartbeat\"\n"
                +"touch \"$ATT_CASE_OUTPUT_DIR/started\"\n"
                +"count=0\nwhile :; do count=$((count+1)); printf '%s\\n' \"$count\" > \"$ATT_CASE_OUTPUT_DIR/heartbeat\"; sleep 0.02; done\n");
        holdScript.toFile().setExecutable(true);
        Files.writeString(Files.createDirectories(templates.resolve("HOLD")).resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: HOLD\ndescription: Long child Tool for Worker lifecycle tests\n"
                +"actions:\n  hold:\n    type: tool\n    call: '#{hold()}'\n");
        Files.writeString(Files.createDirectories(templates.resolve("TIMEOUT")).resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: TIMEOUT\ndescription: Timed child Tool for Worker lifecycle tests\n"
                +"actions:\n  hold:\n    type: tool\n    call: '#{hold()}'\n    timeoutMs: 300\n");

        Path runtimeConfig=temp.resolve("local/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\ntemplates: {root: templates}\ntestcase: {root: testcase}\n");
        Files.writeString(tools.resolve("hold.yaml"),"schemaVersion: att-tool/v1\nid: hold\nname: Hold\n"
                +"description: Hold the Worker for lifecycle checks\ncommand: [./tools/hold.sh]\nstdoutFormat: text\narguments: {}\n");
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
            Path cancelledOutput=runtime.outputDirectoryForJob(cancelled.id);
            await(()->cancelled.terminal()||outputHas(cancelledOutput,"started"),20);
            assertTrue(outputHas(cancelledOutput,"started"),"HOLD Tool did not start; Job="+runtime.jobRecord(cancelled.id)
                    +", events="+runtime.events(cancelled.id).after(0));
            assertEquals("cold",cancelled.workerDispatch,"A source-pinned cancellation must use the isolated cold Worker");
            long cancelledWorker=cancelled.workerPid.longValue();
            assertEquals("CANCELLED",runtime.cancel(cancelled.id,"alice"));
            await(()->cancelled.terminal()&&!ProcessHandle.of(cancelledWorker).map(ProcessHandle::isAlive).orElse(false),15);
            assertHeartbeatStopped(cancelledOutput,"Cancelled child Tool must stop writing into its Job output");
            List<String> cancelledEvents=runtime.events(cancelled.id).after(0).stream().map(event->String.valueOf(event.get("event"))).toList();
            assertTrue(cancelledEvents.contains("result"),cancelledEvents.toString());
            awaitArchive(runtime,cancelled.id);

            awaitReadyWorkers(runtime,1,15);
            Job timedOut=submit(runtime,"TIMEOUT");
            Path timeoutOutput=runtime.outputDirectoryForJob(timedOut.id);
            await(()->outputHas(timeoutOutput,"started")&&timedOut.workerPid!=null,20);
            assertEquals("cold",timedOut.workerDispatch,"A source-pinned timeout must use the isolated cold Worker");
            long timeoutWorker=timedOut.workerPid.longValue();
            await(()->timedOut.terminal(),20);
            assertNotEquals("PASS",timedOut.status,"The timed Tool must not be reported as a successful Job");
            await(()->!ProcessHandle.of(timeoutWorker).map(ProcessHandle::isAlive).orElse(false),15);
            assertHeartbeatStopped(timeoutOutput,"Timed-out child Tool must stop writing into its Job output");
            awaitArchive(runtime,timedOut.id);
            assertNotEquals(cancelledWorker,timeoutWorker,"A one-shot Worker PID cannot serve two Jobs");

            awaitReadyWorkers(runtime,1,15);
            Job uncertain=submit(runtime,"HOLD");
            Path uncertainOutput=runtime.outputDirectoryForJob(uncertain.id);
            await(()->outputHas(uncertainOutput,"started")&&uncertain.workerPid!=null,20);
            assertEquals("cold",uncertain.workerDispatch,"A source-pinned uncertain cleanup must use the isolated cold Worker");
            long uncertainWorker=uncertain.workerPid.longValue();
            assertNotNull(uncertain.processFence);
            uncertain.processFence.unverified();
            assertEquals("CANCELLED",runtime.cancel(uncertain.id,"alice"));
            await(()->!ProcessHandle.of(uncertainWorker).map(ProcessHandle::isAlive).orElse(false),15);
            assertHeartbeatStopped(uncertainOutput,"Cancelled uncertain child Tool must stop writing into its Job output");
            long quarantineDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!runtime.store.workerQuarantined(uncertain.id)&&System.nanoTime()<quarantineDeadline) {
                runtime.reconcileWorkerReleases();Thread.sleep(20);
            }
            assertTrue(runtime.store.workerQuarantined(uncertain.id),"Unverified cold Worker cleanup must be durably quarantined");
            while(!"worker_unverified".equals(runtime.artifactIndex(uncertain.id).get("reason"))
                    &&System.nanoTime()<quarantineDeadline)Thread.sleep(20);
            assertEquals("worker_unverified",runtime.artifactIndex(uncertain.id).get("reason"));
            assertFalse(Files.exists(runtime.artifactArchivePath(uncertain.id)),"An unverified Worker cannot publish artifacts");
            assertTrue(Files.isDirectory(runtime.outputDirectoryForJob(uncertain.id)),"Quarantine retains private evidence");
            assertThrows(IllegalArgumentException.class,()->runtime.retryArtifactArchive(uncertain.id));
            quarantinedJobId=uncertain.id;
        }
        try(ServerRuntime restarted=new ServerRuntime(poolConfig,libs.toString())) {
            assertTrue(restarted.store.workerQuarantined(quarantinedJobId),"Source-pinned Worker quarantine must survive Server restart");
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
        Map<String,Object> draft;
        try { draft=runtime.createRunDraft(definition,"alice"); }
        catch (ServerRuntime.InspectionConfigurationException invalid) { throw new AssertionError(invalid.diagnostics.toString(), invalid); }
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

    private static void assertHeartbeatStopped(Path root,String message) throws Exception {
        Path heartbeat=outputFile(root,"heartbeat");
        assertNotNull(heartbeat,"The child Tool must write a heartbeat before cleanup");
        String stoppedValue=Files.readString(heartbeat);
        Thread.sleep(250);
        assertEquals(stoppedValue,Files.readString(heartbeat),message);
    }

    private static boolean outputHas(Path root,String name) {
        return outputFile(root,name)!=null;
    }

    private static Path outputFile(Path root,String name) {
        if(root==null||!Files.isDirectory(root))return null;
        try(var files=Files.walk(root)) {
            return files.filter(path->path.getFileName()!=null&&name.equals(path.getFileName().toString())).findFirst().orElse(null);
        } catch(Exception unavailable) { return null; }
    }
}
