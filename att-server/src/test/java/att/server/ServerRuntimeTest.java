package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeTest {
    @TempDir Path temp;

    @Test void loadInspectionConfigurationDiagnosticsKeepSafeActionableFields() throws Exception {
        Path packageRoot=Files.createDirectories(temp.resolve("package/config"));
        Path absoluteConfig=packageRoot.resolve("config.yaml");
        Map<String,Object> raw=Map.of("code","ATT-CFG-001","summary","Required environment value is missing",
                "detail","Supply the variable to the Tomcat process","field","dbhelpers.orders.url",
                "file",absoluteConfig.toString(),"suggestion","Configure ORDERS_DB_URL in Tomcat.","environmentValue","do-not-return");

        List<Map<String,Object>> diagnostics=ServerRuntime.safeInspectionDiagnostics(List.of(raw),packageRoot);
        assertEquals(1,diagnostics.size());
        assertEquals("ATT-CFG-001",diagnostics.get(0).get("code"));
        assertEquals("Supply the variable to the Tomcat process",diagnostics.get(0).get("detail"));
        assertEquals("dbhelpers.orders.url",diagnostics.get(0).get("field"));
        assertEquals("Configure ORDERS_DB_URL in Tomcat.",diagnostics.get(0).get("suggestion"));
        assertEquals("config.yaml",diagnostics.get(0).get("file"));
        assertFalse(diagnostics.toString().contains("do-not-return"));
        ServerRuntime.InspectionConfigurationException failure=new ServerRuntime.InspectionConfigurationException(diagnostics);
        assertEquals("Required environment value is missing",failure.summary());
    }

    @Test void enforcesAggregateInlineLoadCapsWithoutOverflow() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("inline-limits-packages"));Path pkg=Files.createDirectory(allowed.resolve("p"));
        Path configFile=temp.resolve("inline-limits-server.yaml");Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Files.writeString(configFile,"server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("inline-limits-data"))+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(java)
                +"\n  inlineLoad:\n    enabled: true\n    maxWorkloads: 10\n    maxTargets: 20\n    maxTotalUsers: 100\n    maxAggregateArrivalRatePerSecond: 100\n    maxConcurrentPerWorkload: 100\n    maxTotalConcurrent: 100\n    maxDurationSeconds: 60\nworkers: {}\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(allowed)+"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(pkg)+"\n");
        ServerConfig.InlineLoad limits=ServerConfig.load(configFile).inlineLoad;
        assertDoesNotThrow(()->ServerRuntime.validateInlineLoadLimits(limits,inlineScenario(List.of(arrivalWorkload("one",2,"1/s"),arrivalWorkload("two",3,"1/s")))));
        List<Map<String,Object>> maxWorkloads=new java.util.ArrayList<>();for(int i=0;i<10;i++)maxWorkloads.add(virtualUsersWorkload("at-workload-limit-"+i,1));
        assertDoesNotThrow(()->ServerRuntime.validateInlineLoadLimits(limits,inlineScenario(maxWorkloads)));
        assertDoesNotThrow(()->ServerRuntime.validateInlineLoadLimits(limits,inlineScenario(List.of(virtualUsersWorkload("at-user-limit",100)))));
        assertDoesNotThrow(()->ServerRuntime.validateInlineLoadLimits(limits,inlineScenario(List.of(arrivalWorkload("at-arrival-limit",100,"100/s")))));
        assertDoesNotThrow(()->ServerRuntime.validateInlineLoadLimits(limits,inlineScenario(List.of(virtualUsersWorkload("at-duration-limit",1,"60s")))));
        Map<String,Object> twentyTargets=virtualUsersWorkload("at-target-limit",1);
        twentyTargets.put("mix",Collections.nCopies(20,Map.of("target",Map.of("type","template","id","T"),"weight",1)));
        twentyTargets.remove("target");assertDoesNotThrow(()->ServerRuntime.validateInlineLoadLimits(limits,inlineScenario(List.of(twentyTargets))));
        assertDoesNotThrow(()->ServerRuntime.validateInlineLoadLimits(limits,inlineScenario(List.of(arrivalWorkload("at-workload-concurrency-limit",100,"1/s")))));
        assertLimit(limits,List.of(arrivalWorkload("per-workload",101,"1/s")),"maxConcurrentPerWorkload");
        assertLimit(limits,List.of(arrivalWorkload("one",51,"1/s"),arrivalWorkload("two",50,"1/s")),"maxTotalConcurrent");
        List<Map<String,Object>> manySmall=new java.util.ArrayList<>();for(int i=0;i<10;i++)manySmall.add(arrivalWorkload("small-"+i,11,"1/s"));
        assertLimit(limits,manySmall,"maxTotalConcurrent");
        assertLimit(limits,List.of(virtualUsersWorkload("large",Long.MAX_VALUE),virtualUsersWorkload("small",1)),"maxTotalUsers");
        assertLimit(limits,List.of(virtualUsersWorkload("over-user-limit",101)),"maxTotalUsers");
        assertLimit(limits,List.of(arrivalWorkload("rate",1,"101/s")),"maxAggregateArrivalRatePerSecond");
        assertLimit(limits,List.of(virtualUsersWorkload("duration",1,"61s")),"maxDurationSeconds");
        List<Map<String,Object>> tooMany=new java.util.ArrayList<>();for(int i=0;i<11;i++)tooMany.add(virtualUsersWorkload("workload-"+i,1));
        assertLimit(limits,tooMany,"maxWorkloads");
        Map<String,Object> tooManyTargets=virtualUsersWorkload("targets",1);
        tooManyTargets.put("mix",Collections.nCopies(21,Map.of("target",Map.of("type","template","id","T"),"weight",1)));
        tooManyTargets.remove("target");assertLimit(limits,List.of(tooManyTargets),"maxTargets");
    }

    private static void assertLimit(ServerConfig.InlineLoad limits,List<Map<String,Object>> workloads,String message) {
        ServerRuntime.InlineLoadLimitException error=assertThrows(ServerRuntime.InlineLoadLimitException.class,
                ()->ServerRuntime.validateInlineLoadLimits(limits,inlineScenario(workloads)));
        assertTrue(error.getMessage().contains(message),error.getMessage());
    }
    private static Map<String,Object> inlineScenario(List<Map<String,Object>> workloads) {return Map.of("schemaVersion","att-load/v1.6","workloads",workloads);}
    private static Map<String,Object> virtualUsersWorkload(String id,long users) {return virtualUsersWorkload(id,users,"1s");}
    private static Map<String,Object> virtualUsersWorkload(String id,long users,String duration) {
        return inlineWorkload(id,Map.of("users",users,"duration",duration,"warmup","0s","rampUp","0s","rampDown","0s"));
    }
    private static Map<String,Object> arrivalWorkload(String id,long concurrent,String rate) {
        return inlineWorkload(id,Map.of("arrivalRate",rate,"maxConcurrent",concurrent,"duration","1s","warmup","0s","rampUp","0s","rampDown","0s"));
    }
    private static Map<String,Object> inlineWorkload(String id,Map<String,Object> load) {
        return new java.util.LinkedHashMap<>(Map.of("id",id,"target",Map.of("type","template","id","T"),"load",load));
    }

    @Test void launchesOneStructuredWorkerProcessForAnAcceptedJob() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages"));Path pkg=Files.createDirectory(allowed.resolve("broken-package"));
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("data")+"\n  javaExecutable: "+javaBin+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("WEB-INF-lib"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")&&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        ServerRuntime runtime=new ServerRuntime(config,libs.toString());
        try {
            JsonNode request=ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"target\":{\"type\":\"template\",\"id\":\"missing\"}}");
            Map<String,Object> submitted=runtime.submit("debug",request,"ci-test");String id=(String)submitted.get("jobId");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);Map<String,Object> record=runtime.jobRecord(id);while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(record.get("status"))&&System.nanoTime()<deadline){Thread.sleep(20);record=runtime.jobRecord(id);}
            assertTrue(List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(record.get("status")),"Worker must produce a terminal result");while(!runtime.jobs.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(20);assertTrue(runtime.jobs.isEmpty(),"Completed Job objects should be evicted from memory");assertTrue(runtime.events(id).after(0).stream().anyMatch(e->"result".equals(e.get("event"))));
            assertEquals("debug",record.get("command"));
        } finally {runtime.close();}
    }

    @Test void skipsEligibilityInspectionWhenWarmPoolHasNoReadyCapacity() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("prewarm-saturated-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path libs=Files.createDirectory(temp.resolve("prewarm-saturated-libs"));Path yaml=temp.resolve("prewarm-saturated-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("prewarm-saturated-data")
                +"\n  inspection: {enabled: true, warmup: {enabled: false}}\nworkers:\n  heapMaxMb: 128\n"
                +"  executionPool: {enabled: true, minIdle: 1, maxIdle: 1, heapBudgetMb: 128, packageIds: [p]}\n"
                +"packages:\n  allowedRoots: ["+allowed+"]\n  entries:\n    p: "+pkg+"\n");
        java.util.concurrent.atomic.AtomicInteger launches=new java.util.concurrent.atomic.AtomicInteger();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),libs.toString(),builder->{
            launches.incrementAndGet();
            if(!builder.command().contains("--prewarmed-one-shot"))throw new java.io.IOException("Unexpected Inspector launch in saturated-pool test");
            ProcessBuilder fixture=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-cp",classpath,ExecutionPoolProcessFixture.class.getName());
            fixture.directory(builder.directory());fixture.environment().clear();fixture.environment().putAll(builder.environment());
            return fixture.start();
        });
        ExecutionWorkerPool.Lease leased=null;
        try {
            long readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(((Number)runtime.executionWorkers.metrics().get("ready")).intValue()!=1&&System.nanoTime()<readyDeadline)Thread.sleep(20);
            assertEquals(1L,runtime.executionWorkers.metrics().get("ready"));assertEquals(1,launches.get());
            EnumMap<att.metrics.InspectionMetrics.Stage,Long> before=new EnumMap<>(att.metrics.InspectionMetrics.Stage.class);
            for(att.metrics.InspectionMetrics.Stage stage:att.metrics.InspectionMetrics.Stage.values())before.put(stage,runtime.inspectionMetrics.count(stage));
            synchronized(runtime.executionWorkers) {
                leased=runtime.executionWorkers.lease("p");assertNotNull(leased);
                Job job=new Job("J1122334455667788","run","p","test",new att.server.api.WorkerRequest(),
                        new JobEvents(temp.resolve("saturated-events.jsonl"),100));
                assertNull(runtime.prewarmRevision(job));
                assertEquals("no_ready_worker",job.prewarmFallbackReason);
                assertNull(job.prewarmEligibilityMs,"A cold fallback must not pay for package inspection");
                assertEquals(1,launches.get(),"No Inspector or replacement Worker should launch during eligibility probing");
                for(att.metrics.InspectionMetrics.Stage stage:att.metrics.InspectionMetrics.Stage.values())
                    assertEquals(before.get(stage),runtime.inspectionMetrics.count(stage),"Inspection metric changed: "+stage);
            }
        } finally {
            if(leased!=null) {
                leased.process.destroyForcibly();leased.process.waitFor(5,TimeUnit.SECONDS);leased.transferred();
            }
            runtime.close();
        }
    }

    @Test void cancellationCannotSlipBetweenWorkerLaunchAndProcessPublication() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),"Uses a POSIX test launcher");
        Path allowed=Files.createDirectory(temp.resolve("cancel-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path launcher=temp.resolve("slow-java");Files.writeString(launcher,"#!/bin/sh\nexec sleep 60\n");launcher.toFile().setExecutable(true);
        Path configFile=temp.resolve("cancel-server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("cancel-data")+"\n  javaExecutable: "+launcher+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("cancel-WEB-INF-lib"));
        CountDownLatch launchEntered=new CountDownLatch(1),allowLaunch=new CountDownLatch(1);
        AtomicReference<Process> launched=new AtomicReference<>();
        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),ServerRuntimeTestSupport.resolvingConfiguration(builder->{
            launchEntered.countDown();
            try {if(!allowLaunch.await(90,TimeUnit.SECONDS))throw new java.io.IOException("test launch gate timed out");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("test launch gate interrupted",e);}
            Process process=builder.start();launched.set(process);return process;
        }));
        FutureTask<Map<String,Object>> submission=new FutureTask<>(()->runtime.submit("validate",ServerRuntime.JSON.readTree("{\"packageId\":\"p\"}"),"ci-test"));
        try {
            new Thread(submission,"test-job-submit").start();
            assertTrue(launchEntered.await(75,TimeUnit.SECONDS),"Worker launcher should reach the process-publication boundary");
            long jobDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(runtime.jobs.isEmpty()&&System.nanoTime()<jobDeadline)Thread.sleep(10);
            assertEquals(1,runtime.jobs.size(),"The submitted Job should be visible before its process is published");
            String id=runtime.jobs.keySet().iterator().next();
            FutureTask<Void> cancel=new FutureTask<>(()->{runtime.cancel(id,"ci-test");return null;});new Thread(cancel,"test-job-cancel").start();
            Thread.sleep(100);assertFalse(cancel.isDone(),"Cancellation must wait while launch and process publication are atomic");
            allowLaunch.countDown();assertEquals(id,submission.get(10,TimeUnit.SECONDS).get("jobId"));cancel.get(5,TimeUnit.SECONDS);
            assertEquals("CANCELLED",runtime.jobRecord(id).get("status"));
            assertNotNull(launched.get());assertFalse(launched.get().isAlive(),"Cancellation must terminate the published Worker process");
            long cleanupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(runtime.jobs.containsKey(id)&&System.nanoTime()<cleanupDeadline)Thread.sleep(10);
            assertFalse(runtime.jobs.containsKey(id),"Worker cleanup must finish before the temporary fixture is removed");
        } finally {allowLaunch.countDown();if(!submission.isDone())submission.cancel(true);runtime.close();}
    }

    @Test void cancellationAndWorkerCompletionSerializeWithoutMonitorInversion() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("race-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));Path yaml=temp.resolve("race-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("race-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("race-libs")).toString());
        try {
            for(int i=0;i<20;i++) {
                String id=String.format("J%016X",i+1);Job job=new Job(id,"run","p","test",new att.server.api.WorkerRequest(),new JobEvents(runtime.config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),100));
                runtime.store.insert(job,"{}");runtime.jobs.put(id,job);CountDownLatch start=new CountDownLatch(1);
                FutureTask<Void> cancel=new FutureTask<>(()->{start.await();runtime.cancel(id,"test");return null;});FutureTask<Void> finish=new FutureTask<>(()->{start.await();runtime.finish(job,"PASS",0);return null;});
                Thread a=new Thread(cancel),b=new Thread(finish);a.start();b.start();start.countDown();cancel.get(3,TimeUnit.SECONDS);finish.get(3,TimeUnit.SECONDS);
                assertTrue(job.terminal());
            }
        } finally {runtime.close();}
    }

    @Test void sseCompletionWaitsForResultMarkerAndClientCursor() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("sse-completion-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));Path yaml=temp.resolve("sse-completion-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("sse-completion-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("sse-completion-libs")).toString());
        try {
            String id="J1122334455667788";Job job=new Job(id,"run","p","test",new att.server.api.WorkerRequest(),new JobEvents(runtime.config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),100));
            runtime.store.insert(job,"{}");runtime.jobs.put(id,job);long clientCursor=job.events.latest();
            AtomicReference<Boolean> terminalValue=new AtomicReference<>();CountDownLatch terminalCheckStarted=new CountDownLatch(1);
            Thread terminalCheck=new Thread(()->{terminalCheckStarted.countDown();try{terminalValue.set(runtime.terminal(id));}catch(Exception failure){throw new RuntimeException(failure);}},"test-sse-terminal-check");
            synchronized(job) {
                job.status="PASS";job.exitCode=0;job.finishedAt=java.time.Instant.now();runtime.store.update(job);
                assertFalse(job.events.resultDeliveredThrough(clientCursor),"An old Last-Event-ID cursor must not pass an unpublished result marker");
                terminalCheck.start();assertTrue(terminalCheckStarted.await(5,TimeUnit.SECONDS));long blockedDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(terminalCheck.getState()!=Thread.State.BLOCKED&&System.nanoTime()<blockedDeadline)Thread.yield();
                assertEquals(Thread.State.BLOCKED,terminalCheck.getState(),"Terminal observation must wait while final journal events are unpublished");
                job.events.append("status",Map.of("jobId",id,"status","PASS"));
                job.events.append("result",Map.of("jobId",id,"status","PASS","exitCode",0));
            }
            terminalCheck.join(5000);
            assertFalse(terminalCheck.isAlive());assertEquals(Boolean.TRUE,terminalValue.get());
            List<Map<String,Object>> replay=job.events.after(clientCursor);assertEquals(List.of("status","result"),replay.stream().map(event->event.get("event")).toList());
            long resumedCursor=((Number)replay.get(0).get("id")).longValue();assertFalse(job.events.resultDeliveredThrough(resumedCursor),"Receiving only the status event must not close SSE");
            resumedCursor=((Number)replay.get(1).get("id")).longValue();assertTrue(job.events.resultDeliveredThrough(resumedCursor),"SSE can close once Last-Event-ID has passed the result event");assertFalse(job.events.hasMore(resumedCursor));
        } finally {runtime.close();}
    }

    @Test void publicResultsReplacePackageAndOutputRootsWithLogicalReferences() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("redact-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));Path yaml=temp.resolve("redact-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("redact-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("redact-libs")).toString());
        try {
            String id="JABCDEF0123456789";Path output=runtime.config.dataDir.resolve("jobs").resolve(id).resolve("output");Files.createDirectories(output);
            Job job=new Job(id,"run","p","test",new att.server.api.WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
            runtime.store.insert(job,"{}");runtime.jobs.put(id,job);
            job.resultJson=ServerRuntime.JSON.writeValueAsString(Map.of("summary",pkg.toRealPath().resolve("case.json").toString(),"artifact",output.resolve("report.xlsx").toString()));
            job.diagnosticJson=ServerRuntime.JSON.writeValueAsString(Map.of("code","ATT-TEST","message","active worker diagnostic"));job.status="INVALID";
            var activeResponse=ServerRuntime.JSON.valueToTree(runtime.resultRecord(id));
            assertTrue(activeResponse.path("result").path("summary").asText().endsWith("case.json"),activeResponse.toString());
            assertEquals("active worker diagnostic",activeResponse.path("diagnostic").path("message").asText());
            runtime.finish(job,"PASS",0);var response=ServerRuntime.JSON.valueToTree(runtime.resultRecord(id));String encoded=response.toString();
            assertFalse(encoded.contains(pkg.toString()));assertFalse(encoded.contains(output.toString()));assertTrue(encoded.contains("package:case.json"));assertTrue(encoded.contains("artifact:report.xlsx"));
            String original="payload='&{./Pacs008.xml}' <a></a>\nfile="+pkg.resolve("flows/bad.yaml")+" line=7 column=3\nexternal=/private/host/hidden.txt\npassword=artifact-canary\n";
            Path artifact=Files.writeString(output.resolve("case.log"),original);
            String projected=new String(runtime.publicArtifact(id,artifact),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(projected.contains("payload='&{./Pacs008.xml}' <a></a>"),projected);
            assertTrue(projected.contains("flows/bad.yaml line=7 column=3"),projected);
            assertFalse(projected.contains(pkg.toString()));assertFalse(projected.contains("/private/host"));
            assertFalse(projected.contains("artifact-canary"));assertEquals(original,Files.readString(artifact));
            Object event=runtime.publicEventData(Map.of("message",original,"headers",Map.of("Authorization","Bearer event-canary","Content-Type","application/xml")),output,pkg);
            String publicEvent=ServerRuntime.JSON.writeValueAsString(event);
            assertFalse(publicEvent.contains("event-canary"));assertFalse(publicEvent.contains("artifact-canary"));
            assertFalse(publicEvent.contains("/private/host"));assertTrue(publicEvent.contains("application/xml"));
            Path binary=Files.write(output.resolve("binary.bin"),new byte[]{0,1,2});
            assertThrows(IllegalArgumentException.class,()->runtime.publicArtifact(id,binary));
            Path oversized=output.resolve("large.log");try(var stream=Files.newOutputStream(oversized)){stream.write(new byte[16*1024*1024+1]);}
            assertThrows(IllegalArgumentException.class,()->runtime.publicArtifact(id,oversized));

        } finally {runtime.close();}
    }

    @Test void recordsAndPersistsWorkerLifecycleTimingsAndResourceMetrics() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("metrics-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path yaml=temp.resolve("metrics-server.yaml");Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("metrics-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("metrics-libs")).toString());
        try {
            String id="J0123456789ABCDEF";Job job=new Job(id,"run","p","test",new att.server.api.WorkerRequest(),
                    new JobEvents(runtime.config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),100));
            long now=System.nanoTime();job.requestReceivedNanos=now-100_000_000L;job.admittedNanos=now-80_000_000L;
            job.workerStartedNanos=now-70_000_000L;job.workerSpawnStartedNanos=now-60_000_000L;job.workerSpawnedNanos=now-50_000_000L;
            job.status="PREPARING";runtime.store.insert(job,"{}");runtime.jobs.put(id,job);

            runtime.handleWorkerEvent(job,"{\"type\":\"STATUS\",\"jobId\":\""+id+"\",\"status\":\"RUNNING\"}");
            runtime.handleWorkerEvent(job,"{\"type\":\"PROGRESS\",\"jobId\":\""+id+"\",\"message\":\"ready\"}");
            runtime.handleWorkerEvent(job,"{\"type\":\"RESULT\",\"jobId\":\""+id+"\",\"status\":\"PASS\",\"exitCode\":0,\"result\":{\"status\":\"PASS\",\"exitCode\":0},\"workerMetrics\":{\"heapPeakUsedBytes\":4096,\"processCpuSupported\":true}}");
            job.workerTerminatedNanos=System.nanoTime();runtime.store.update(job);runtime.jobs.remove(id);

            Map<String,Object> record=runtime.jobRecord(id);
            Map<?,?> performance=(Map<?,?>)record.get("performance");Map<?,?> timings=(Map<?,?>)performance.get("timings");
            assertTrue(((Number)timings.get("admissionMs")).doubleValue()>=0.0);
            assertTrue(((Number)timings.get("queueWaitMs")).doubleValue()>=0.0);
            assertTrue(((Number)timings.get("workerSpawnMs")).doubleValue()>=0.0);
            assertTrue(((Number)timings.get("workerReadyMs")).doubleValue()>=0.0);
            assertTrue(((Number)timings.get("executionReadyMs")).doubleValue()>=0.0);
            assertTrue(((Number)timings.get("workerLifetimeMs")).doubleValue()>=0.0);
            assertEquals(4096L,((Number)((Map<?,?>)performance.get("worker")).get("heapPeakUsedBytes")).longValue());
        } finally {runtime.close();}
    }

    @Test void appliesValidatedHeapBoundsToWorkerCommandWithoutAcceptingArbitraryJvmFlags() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("heap-command-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path yaml=temp.resolve("heap-command-server.yaml");Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("heap-command-data")+"\n  javaExecutable: "+java+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\n  heapInitialMb: 128\n  heapMaxMb: 1024\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        AtomicReference<List<String>> command=new AtomicReference<>();
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("heap-command-libs")).toString(),ServerRuntimeTestSupport.resolvingConfiguration(builder->{
            command.set(new java.util.ArrayList<>(builder.command()));
            throw new java.io.IOException("captured Worker launch command");
        }));
        try {
            Map<String,Object> submitted=runtime.submit("validate",ServerRuntime.JSON.readTree("{\"packageId\":\"p\"}"),"test");
            String id=(String)submitted.get("jobId");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!runtime.jobs.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(runtime.jobs.isEmpty());
            assertNotNull(command.get());
            assertTrue(command.get().contains("-Xms128m"));
            assertTrue(command.get().contains("-Xmx1024m"));
            assertTrue(command.get().stream().noneMatch(argument->argument.startsWith("-javaagent")||argument.startsWith("-D")));
            assertEquals("ERROR",runtime.jobRecord(id).get("status"));
        } finally {runtime.close();}
    }

    @Test void rejectsPackageRootReplacedBySymlinkAfterServerInitialization() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),"Uses POSIX symlink behavior");
        Path allowed=Files.createDirectory(temp.resolve("symlink-allowed"));Path outside=Files.createDirectory(temp.resolve("symlink-outside"));
        Path pkg=Files.createDirectory(allowed.resolve("package"));Path yaml=temp.resolve("symlink-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("symlink-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("symlink-libs")).toString());
        try {
            Files.move(pkg,allowed.resolve("package-original"));Files.createSymbolicLink(pkg,outside);
            JsonNode request=ServerRuntime.JSON.readTree("{\"packageId\":\"p\"}");
            IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->runtime.submit("validate",request,"test"));
            assertTrue(rejected.getMessage().contains("original allowed root"));assertTrue(runtime.store.list(100).isEmpty());
        } finally {runtime.close();}
    }

    @Test void workerCanLoadDriverFromSeparatelyConfiguredLibraryJar() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("external-driver-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path externalLibraries=Files.createDirectory(temp.resolve("external-worker-libraries"));Path webLibs=Files.createDirectory(temp.resolve("external-worker-web-libs"));
        Path driverSource=Files.createDirectories(temp.resolve("driver-src/oracle/jdbc")).resolve("OracleDriver.java");
        Files.writeString(driverSource,"package oracle.jdbc; public final class OracleDriver { }");Path driverClasses=Files.createDirectory(temp.resolve("driver-classes"));
        Path probeSource=Files.createDirectories(temp.resolve("probe-src/probe")).resolve("WorkerProbe.java");
        List<Map<String,Object>> workerEvents=List.of(
                Map.of("type","DIAGNOSTIC","code","TEST","message","password=synthetic-diagnostic-secret; Cookie: synthetic-cookie-text-marker"),
                Map.of("type","PROGRESS","message","token=synthetic-progress-secret; Authorization: Bearer synthetic-jwt-marker"),
                Map.of("type","LOG","message","Authorization: Basic synthetic-basic-text-marker","headers",Map.of("Authorization","Basic synthetic-basic-marker","Proxy-Authorization","Bearer synthetic-proxy-marker","Cookie","synthetic-cookie-marker","Set-Cookie","synthetic-set-cookie-marker")),
                Map.of("type","RESULT","status","PASS","exitCode",0,"result",Map.of("driver","__DRIVER__","message","password=synthetic-result-secret","headers",Map.of("Authorization","Bearer synthetic-result-marker")))
        );
        List<String> encodedEventList=new java.util.ArrayList<>();for(Map<String,Object> event:workerEvents)encodedEventList.add("\""+java.util.Base64.getEncoder().encodeToString(ServerRuntime.JSON.writeValueAsBytes(event))+"\"");String encodedEvents=String.join(",",encodedEventList);
        Files.writeString(probeSource,"""
                package probe;
                public final class WorkerProbe {
                    public static void main(String[] args) {
                        try {
                            new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
                            Class<?> d=Class.forName("oracle.jdbc.OracleDriver");
                            String[] events={%s};
                            for(String event:events) {
                                String json=new String(java.util.Base64.getDecoder().decode(event),java.nio.charset.StandardCharsets.UTF_8);
                                System.out.println(json.replace("__DRIVER__",d.getName()));
                            }
                        } catch(Throwable t) { System.out.println("{\\\"type\\\":\\\"RESULT\\\",\\\"status\\\":\\\"ERROR\\\",\\\"exitCode\\\":3}"); }
                    }
                }
                """.formatted(encodedEvents));
        Path probeClasses=Files.createDirectory(temp.resolve("probe-classes"));javax.tools.JavaCompiler compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assertNotNull(compiler,"Tests require a JDK compiler");
        assertEquals(0,compiler.run(null,null,null,"-d",driverClasses.toString(),driverSource.toString()));assertEquals(0,compiler.run(null,null,null,"-d",probeClasses.toString(),probeSource.toString()));
        ServerRuntimeTestSupport.addModuleJar(externalLibraries,"oracle-driver",driverClasses);ServerRuntimeTestSupport.addModuleJar(webLibs,"worker-probe",probeClasses);
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");Path yaml=temp.resolve("external-driver-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("external-driver-data"))+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(java)+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\n  libraryDirs:\n    - "+ServerRuntimeTestSupport.yaml(externalLibraries)+"\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(allowed)+"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(pkg)+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),webLibs.toString(),ServerRuntimeTestSupport.resolvingConfiguration(builder->{
            assertTrue(builder.command().get(2).contains(externalLibraries.resolve("*").toString()),"Configured external library wildcard must be on the Worker classpath");
            builder.command().set(3,"probe.WorkerProbe");return builder.start();
        }));
        try {
            Map<String,Object> submitted=runtime.submit("validate",ServerRuntime.JSON.readTree("{\"packageId\":\"p\"}"),"test");String id=(String)submitted.get("jobId");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);Map<String,Object> result=runtime.jobRecord(id);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(result.get("status"))&&System.nanoTime()<deadline){Thread.sleep(10);result=runtime.jobRecord(id);}
            assertEquals("PASS",result.get("status"),"Worker subprocess must load the class contained only in the separately supplied driver JAR: job="+result+" result="+runtime.resultRecord(id));
            String publicResult=ServerRuntime.JSON.valueToTree(runtime.resultRecord(id)).toString();
            String publicEvents=runtime.events(id).after(0).toString();String published=publicResult+publicEvents;
            assertTrue(publicResult.contains("oracle.jdbc.OracleDriver"));assertFalse(published.contains("synthetic-"));assertTrue(published.contains("[REDACTED_SECRET]"));
        } finally {runtime.close();}
    }

    @Test void scheduledJobsRecheckActiveUserAuthorityBeforeAnyWorkerStarts() throws Exception {
        Path root=Files.createDirectories(temp.resolve("owner-package"));
        Path config=Files.writeString(temp.resolve("owner-server.yaml"),"server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("owner-data"))+"\n  auth: {mode: local}\n  scheduling: {enabled: true}\nworkers: {}\npackages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(root)+"]\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(root)+"\n");
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(config),"")) {
            runtime.store.createBootstrapAdmin("keeper","Active administrator","unused","csrf");
            runtime.store.createAuthUser("disabled-admin","Disabled administrator","unused","ADMIN","csrf");
            runtime.store.setAuthActive("disabled-admin",false);
            runtime.store.createAuthUser("disabled-user","Disabled user","unused","USER","csrf");
            runtime.store.setAuthActive("disabled-user",false);
            runtime.store.createAuthUser("reader","Reader owner","unused","USER","csrf");
            for(String type:List.of("run","load","debug"))for(String owner:List.of("missing","disabled-admin","disabled-user")) {
                LoadSchedule schedule=new LoadSchedule();schedule.id="S"+type+owner;schedule.name="Owner authority";schedule.jobType=type;
                schedule.packageId="p";schedule.createdBy=owner;schedule.timezone="UTC";schedule.cron="0 2 * * *";
                schedule.createdAt=Instant.now().toString();schedule.updatedAt=schedule.createdAt;
                runtime.store.createSchedule(schedule,100);
                Map<String,Object> occurrence=runtime.triggerLoadSchedule(schedule.id,"reader");
                assertEquals("failed-to-submit",occurrence.get("status"));
                assertTrue(String.valueOf(occurrence.get("reason")).contains("no longer authorized"),occurrence.toString());
            }
            assertEquals(0,runtime.workers.getActiveCount());assertTrue(runtime.store.list(100).isEmpty());
        }
    }
}
