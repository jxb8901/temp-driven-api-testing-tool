package att.server;

import att.server.api.WorkerRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

class ApiServletIntegratedLimitsTest {
    private static final int DEFAULT_INSPECTION_TIMEOUT_MS=30_000;
    @TempDir Path temp;

    @Test void nativeArtifactStatusSurvivesNonLoadJobsAndCorruptOptionalLoadMetadata() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("artifact-status-packages"));
        createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"artifact-status-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("artifact-status-libs"))) {
            ApiServlet servlet=servlet(runtime);
            for(String command:List.of("run","debug")) {
                String id="debug".equals(command)?"J0000000000000731":"J0000000000000730";
                Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
                Files.writeString(output.resolve("native.txt"),"native report");
                Job job=new Job(id,command,"p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
                runtime.store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();runtime.store.update(job);
                ArtifactArchive.build(output,runtime.artifactArchivePath(id));
                Files.writeString(runtime.artifactArchivePath(id).resolveSibling("archive-status.json"),"{\"state\":\"READY\"}");
                Captured response=invoke(servlet,"GET","/jobs/"+id+"/artifacts/status",Collections.emptyMap(),new byte[0]);
                JsonNode overview=ServerRuntime.JSON.readTree(response.body());
                assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
                assertEquals("READY",overview.path("archiveStatus").asText());
                assertFalse(overview.has("loadReportV2"),command+" jobs must not expose a LOAD report extension.");
            }

            String id="J0000000000000732";
            Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
            Files.writeString(output.resolve("native.txt"),"native report");
            Job job=new Job(id,"load","p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
            runtime.store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();runtime.store.update(job);
            ArtifactArchive.build(output,runtime.artifactArchivePath(id));
            Files.writeString(runtime.artifactArchivePath(id).resolveSibling("archive-status.json"),"{\"state\":\"READY\"}");
            Path malformedSnapshot=output.getParent().resolve("sut-metrics/snapshot.json");
            Files.createDirectories(malformedSnapshot.getParent());
            Files.writeString(malformedSnapshot,"{not-json");
            Captured response=invoke(servlet,"GET","/jobs/"+id+"/artifacts/status",Collections.emptyMap(),new byte[0]);
            JsonNode overview=ServerRuntime.JSON.readTree(response.body());
            assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
            assertEquals("READY",overview.path("archiveStatus").asText());
            assertEquals("FAILED",overview.path("loadReportV2").path("state").asText());
        }
    }

    @Test void staleCentralImpactReturnsConflictThroughPublishRoute() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("impact-api-packages"));
        Path packageRoot=createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("impact-api-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"impact-api-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,libs,true,true)) {
            Map<String,String> revisions=runtime.changeSetPublisher.currentRevisions(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true);
            String candidate=Files.readString(runtimeConfig)+"# impact API candidate\n";
            Map<String,Object> draft=runtime.changeSetPublisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,revisions.get("baseSourceRevision"),null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",candidate.getBytes(StandardCharsets.UTF_8))));
            String id=String.valueOf(draft.get("changeSetId"));
            runtime.changeSetPublisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id);
            runtime.changeSetPublisher.review(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id);
            var impact=runtime.changeSetPublisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,id,null);
            assertNotNull(impact.freshness);
            String freshness=ConfigImpactAnalyzer.freshnessFingerprint(impact.freshness);
            att.server.api.source.SourceManagementContracts.ImpactAcknowledgement acknowledgement=
                    new att.server.api.source.SourceManagementContracts.ImpactAcknowledgement(freshness);

            Path manifest=packageRoot.resolve("package.yaml");
            Files.writeString(manifest,Files.readString(manifest)+"# concurrent manifest change\n");
            Captured response=invokeAsAdmin(servlet(runtime),"alice","POST",
                    "/configuration/change-sets/"+id+"/publish",Map.of(),ServerRuntime.JSON.writeValueAsBytes(Map.of(
                            "impactAcknowledgement",Map.of("freshnessFingerprint",freshness))));
            JsonNode error=ServerRuntime.JSON.readTree(response.body()).path("error");
            assertEquals(409,response.status,new String(response.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-CONFIG-IMPACT-STALE",error.path("code").asText());
            assertFalse(Files.readString(runtimeConfig).contains("# impact API candidate"),
                    "a stale impact must leave the central source unchanged");
        }
    }

    @Test void emptyRegisteredPackageReturnsEmptyInspectorInventoryAndDoesNotAffectAnotherPackage() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("empty-package-packages"));
        Files.createDirectory(packages.resolve("lilyzhou"));
        createPackage(packages.resolve("healthy"));
        Path broken=createPackage(packages.resolve("broken"));
        Files.delete(broken.resolve("templates"));
        Files.writeString(broken.resolve("templates"),"not a directory");
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"empty-package-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("empty-package-libs"))) {
            ApiServlet servlet=servlet(runtime);
            Captured catalogResponse=invoke(servlet,"GET","/packages/lilyzhou/load-catalog",Map.of("limit","100"),new byte[0]);
            JsonNode catalog=ServerRuntime.JSON.readTree(catalogResponse.body());
            assertEquals(200,catalogResponse.status,new String(catalogResponse.body(),StandardCharsets.UTF_8));
            assertTrue(catalog.path("items").isArray());assertEquals(0,catalog.path("items").size());
            assertEquals(0,catalog.path("total").asInt());assertTrue(catalog.path("nextCursor").isMissingNode()||catalog.path("nextCursor").isNull(),catalog.toString());
            assertTrue(catalogResponse.headers.get("X-ATT-Inspection-Revision").matches("[A-Za-z0-9_-]{43}"),catalogResponse.headers.toString());

            Captured resourcesResponse=invoke(servlet,"GET","/packages/lilyzhou/resources",Map.of("type","template"),new byte[0]);
            JsonNode resources=ServerRuntime.JSON.readTree(resourcesResponse.body());
            assertEquals(200,resourcesResponse.status,new String(resourcesResponse.body(),StandardCharsets.UTF_8));
            assertTrue(resources.path("items").isArray());assertEquals(0,resources.path("items").size());
            assertFalse(Files.exists(packages.resolve("lilyzhou/templates")));
            assertFalse(Files.exists(packages.resolve("lilyzhou/testcase")));
            assertFalse(Files.exists(packages.resolve("lilyzhou/load")));

            Captured invalidRoot=invoke(servlet,"GET","/packages/broken/resources",Map.of("type","template"),new byte[0]);
            JsonNode invalidBody=ServerRuntime.JSON.readTree(invalidRoot.body());
            assertEquals(422,invalidRoot.status,new String(invalidRoot.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-RESOURCE-ROOT-INVALID",invalidBody.path("diagnostics").path(0).path("code").asText());
            assertEquals("resources.templatesRoot",invalidBody.path("diagnostics").path(0).path("field").asText());
            assertFalse(new String(invalidRoot.body(),StandardCharsets.UTF_8).contains(packages.toString()));

            Captured nextPackage=invoke(servlet,"GET","/packages/healthy/resources",Map.of("type","template"),new byte[0]);
            assertEquals(200,nextPackage.status,new String(nextPackage.body(),StandardCharsets.UTF_8));
            Captured retried=invoke(servlet,"GET","/packages/lilyzhou/load-catalog",Map.of("limit","100"),new byte[0]);
            assertEquals(200,retried.status,new String(retried.body(),StandardCharsets.UTF_8));
            assertEquals(0,ServerRuntime.JSON.readTree(retried.body()).path("items").size());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void packageFileIndexPagesCanonicalReferencesWithCallSitesAndRejectsStaleCursors() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("package-file-index-packages"));
        Path packageRoot=createPackage(packages.resolve("alice"));
        Path template=Files.createDirectories(packageRoot.resolve("templates/SAMPLE")).resolve("template.yaml");
        Files.writeString(template,"schemaVersion: att-template/v3.6\nname: SAMPLE\ndescription: File index fixture\nactions:\n"
                +"  sendRequest:\n    type: log\n    message: '&{./input.json}'\n"
                +"  retryRequest:\n    type: log\n    message: '&{./input.json}'\n"
                +"  sendArchive:\n    type: log\n    message: '&{./archive.xml}'\n");
        Files.writeString(packageRoot.resolve("templates/SAMPLE/input.json"),"{\"ok\":true}\n");
        Files.writeString(packageRoot.resolve("templates/SAMPLE/archive.xml"),"<archive/>\n");
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"package-file-index-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,
                workerLibraries("package-file-index-libs"))) {
            ApiServlet servlet=servlet(runtime);
            Captured resources=invokeAs(servlet,"alice","GET","/packages/alice/resources",
                    Map.of("type","template"),new byte[0]);
            JsonNode resourcePage=ServerRuntime.JSON.readTree(resources.body());
            assertEquals(200,resources.status,new String(resources.body(),StandardCharsets.UTF_8));
            assertEquals(1,resourcePage.path("total").asInt(),resourcePage.toString());
            assertEquals("ready",resourcePage.path("items").get(0).path("state").asText(),resourcePage.toString());
            assertEquals(true,resourcePage.path("items").get(0).path("sourceAvailable").asBoolean(),resourcePage.toString());
            Captured first=invokeAs(servlet,"alice","GET","/packages/alice/resources/files",
                    Map.of("limit","1"),new byte[0]);
            JsonNode firstPage=ServerRuntime.JSON.readTree(first.body());
            assertEquals(200,first.status,new String(first.body(),StandardCharsets.UTF_8));
            assertEquals(2,firstPage.path("total").asInt(),firstPage.toString());
            assertEquals("COMPLETE",firstPage.path("coverage").path("state").asText());
            JsonNode input=firstPage.path("items").get(0);
            assertEquals("templates/SAMPLE/archive.xml",input.path("path").asText());
            assertFalse(firstPage.path("nextCursor").asText().isBlank());

            String cursor=firstPage.path("nextCursor").asText();
            Captured second=invokeAs(servlet,"alice","GET","/packages/alice/resources/files",
                    Map.of("limit","1","cursor",cursor),new byte[0]);
            JsonNode secondPage=ServerRuntime.JSON.readTree(second.body());
            assertEquals(200,second.status,new String(second.body(),StandardCharsets.UTF_8));
            assertEquals("templates/SAMPLE/input.json",secondPage.path("items").get(0).path("path").asText());
            JsonNode use=secondPage.path("items").get(0).path("uses").get(0);
            assertEquals("SAMPLE",use.path("label").asText());
            assertEquals(2,use.path("callSites").size());
            assertEquals(Set.of("sendRequest","retryRequest"),java.util.stream.StreamSupport.stream(
                    secondPage.path("items").get(0).path("uses").get(0).path("callSites").spliterator(),false)
                    .map(callSite->callSite.path("actionId").asText()).collect(java.util.stream.Collectors.toSet()));
            Files.writeString(packageRoot.resolve("templates/SAMPLE/input.json"),"{\"ok\":false}\n");
            Captured stale=invokeAs(servlet,"alice","GET","/packages/alice/resources/files",
                    Map.of("limit","1","cursor",cursor),new byte[0]);
            JsonNode staleBody=ServerRuntime.JSON.readTree(stale.body());
            assertEquals(409,stale.status,new String(stale.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-RESOURCE-CURSOR-STALE",staleBody.path("error").path("code").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void asynchronousValidationRecordIsDurableOwnerScopedAndCreatesNoJob() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("validation-record-packages"));
        createPackage(packages.resolve("payments"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path sharedHelper=Files.createDirectories(runtimeConfig.getParent().resolve("dbhelpers/sit")).resolve("orders.yaml");
        Files.writeString(sharedHelper,sharedDbHelper("initial helper"));
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.15\nenvironment: SIT\n"
                +"environments:\n  SIT:\n    dbhelpers: [config/dbhelpers/sit/orders.yaml]\ntimeoutMs: 10000\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\n");
        Path libs=workerLibraries("validation-record-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"validation-record-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs,
                true,1,ref->System.getenv("ATT_SECRET_"+ref),true)) {
            ApiServlet servlet=servlet(runtime);
            byte[] body="{\"command\":\"validate\",\"targetKind\":\"config\"}".getBytes(StandardCharsets.UTF_8);
            Captured accepted=invokeAs(servlet,"POST","/validations",Map.of(),body,"alice");
            JsonNode queued=ServerRuntime.JSON.readTree(accepted.body());
            assertEquals(202,accepted.status,new String(accepted.body(),StandardCharsets.UTF_8));
            assertTrue(queued.path("validationId").asText().matches("V[0-9A-F]{32}"));
            String id=queued.path("validationId").asText();
            assertEquals("QUEUED",queued.path("status").asText());
            assertFalse(queued.has("environment"),"Shared Config context is canonical with a null Environment");
            assertTrue(accepted.headers.get("Location").endsWith("/api/v1/validations/"+id));

            Captured otherOwner=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"bob");
            assertEquals(404,otherOwner.status,"Record identifiers must not disclose another owner's validation");
            Captured list=invokeAs(servlet,"GET","/validations",Map.of("limit","10"),new byte[0],"alice");
            JsonNode listed=ServerRuntime.JSON.readTree(list.body());
            assertEquals(200,list.status,new String(list.body(),StandardCharsets.UTF_8));
            assertEquals(id,listed.path("items").path(0).path("validationId").asText());
            assertFalse(listed.path("items").path(0).has("report"),"List rows must not duplicate durable reports");

            Captured result=null;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline) {
                result=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"alice");
                JsonNode record=ServerRuntime.JSON.readTree(result.body());
                if(!List.of("QUEUED","RUNNING").contains(record.path("status").asText()))break;
                Thread.sleep(25);
            }
            assertNotNull(result);
            JsonNode completed=ServerRuntime.JSON.readTree(result.body());
            assertEquals(200,result.status,new String(result.body(),StandardCharsets.UTF_8));
            assertEquals("COMPLETED",completed.path("status").asText());
            assertEquals("att-validation/v1",completed.path("report").path("schemaVersion").asText());
            assertFalse(completed.has("environment"),"The report may describe Runtime default SIT without changing the Shared Config context key");
            assertEquals("SIT",completed.path("report").path("context").path("environment").asText());
            assertTrue(completed.path("sourceRevision").asText().matches("[a-f0-9]{64}"));
            assertEquals("CURRENT",completed.path("applicability").asText());
            assertEquals(0,runtime.jobsFor("alice",null).size(),"Read-only validation must not create a Job");

            Captured matching=invokeAs(servlet,"GET","/validations",Map.of("scope","config","matchContext","true","limit","1"),new byte[0],"alice");
            assertEquals(id,ServerRuntime.JSON.readTree(matching.body()).path("items").path(0).path("validationId").asText());
            Captured summary=invokeAs(servlet,"GET","/validations/summary",Map.of(),new byte[0],"alice");
            JsonNode summaryRecord=ServerRuntime.JSON.readTree(summary.body()).path("record");
            assertEquals(id,summaryRecord.path("validationId").asText());
            assertFalse(summaryRecord.has("report"));

            Semaphore validationAdmission=semaphore(runtime,"validationAdmissionSlots");
            long admissionDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(validationAdmission.availablePermits()==0&&System.nanoTime()<admissionDeadline)Thread.sleep(5);
            assertTrue(validationAdmission.availablePermits()>0,"The next submission must have a free validation admission slot");

            Files.writeString(runtimeConfig,Files.readString(runtimeConfig)+"\n# changed after validation\n");
            Captured mirrorEdit=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"alice");
            assertEquals("CURRENT",ServerRuntime.JSON.readTree(mirrorEdit.body()).path("applicability").asText(),
                    "Unpublished edits to the deployment mirror do not change the active Config used by validation");

            Files.writeString(sharedHelper,sharedDbHelper("edited external child helper"));
            Captured sharedHelperStale=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"alice");
            assertEquals("CURRENT",ServerRuntime.JSON.readTree(sharedHelperStale.body()).path("applicability").asText(),
                    "Unpublished helper edits do not change the active Config used by validation");

            long packageAdmissionDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(validationAdmission.availablePermits()==0&&System.nanoTime()<packageAdmissionDeadline)Thread.sleep(5);
            byte[] packageBody="{\"command\":\"validate\",\"targetKind\":\"config\",\"packageId\":\"payments\"}"
                    .getBytes(StandardCharsets.UTF_8);
            Captured packageAccepted=invokeAs(servlet,"POST","/validations",Map.of(),packageBody,"alice");
            assertEquals(202,packageAccepted.status,new String(packageAccepted.body(),StandardCharsets.UTF_8));
            String packageValidationId=ServerRuntime.JSON.readTree(packageAccepted.body()).path("validationId").asText();
            Captured packageResult=null;long packageDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<packageDeadline) {
                packageResult=invokeAs(servlet,"GET","/validations/"+packageValidationId,Map.of(),new byte[0],"alice");
                JsonNode record=ServerRuntime.JSON.readTree(packageResult.body());
                if(!List.of("QUEUED","RUNNING").contains(record.path("status").asText()))break;
                Thread.sleep(25);
            }
            assertNotNull(packageResult);
            JsonNode packageCompleted=ServerRuntime.JSON.readTree(packageResult.body());
            assertEquals("COMPLETED",packageCompleted.path("status").asText());
            assertEquals("CURRENT",packageCompleted.path("applicability").asText());
            Files.writeString(sharedHelper,sharedDbHelper("edited again for Package validation"));
            Captured packageHelperStale=invokeAs(servlet,"GET","/validations/"+packageValidationId,Map.of(),new byte[0],"alice");
            assertEquals("CURRENT",ServerRuntime.JSON.readTree(packageHelperStale.body()).path("applicability").asText(),
                    "Package validation also remains bound to the active central Config revision");

            // The publication transaction imports the deployment mirror only after a caller has
            // reviewed it. Restore the accepted mirror bytes so this test can publish an explicit,
            // independently staged Config revision after proving the unreviewed edits stayed inert.
            Path activeRoot=runtime.executionCentralSourceRoot();
            Path activeConfig=activeRoot.resolve("config/config.yaml");
            Files.writeString(runtimeConfig,Files.readString(activeConfig));
            Files.writeString(sharedHelper,Files.readString(activeRoot.resolve("config/dbhelpers/sit/orders.yaml")));
            Map<String,String> revisions=runtime.changeSetPublisher.currentRevisions(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true);
            String candidate=Files.readString(activeConfig)+"\n# published validation source revision\n";
            Map<String,Object> draft=runtime.changeSetPublisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,revisions.get("baseSourceRevision"),null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml",candidate.getBytes(StandardCharsets.UTF_8))));
            String changeSetId=String.valueOf(draft.get("changeSetId"));
            try {
                runtime.changeSetPublisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                        ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,changeSetId);
            } catch(ChangeSetPublisher.ValidationRejected rejected) {
                throw new AssertionError("validation-source revision candidate rejected with "+rejected.code,rejected);
            }
            runtime.changeSetPublisher.review(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,changeSetId);
            var impact=runtime.changeSetPublisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID,"alice",true,changeSetId,null);
            assertNotEquals(att.server.api.source.SourceManagementContracts.ImpactDecision.BLOCKED,impact.decision,
                    "Impact provider="+impact.providerState+" code="+impact.unavailableCode+" coverage="+impact.coverage);
            String freshness=ConfigImpactAnalyzer.freshnessFingerprint(impact.freshness);
            att.server.api.source.SourceManagementContracts.ImpactAcknowledgement acknowledgement=
                    new att.server.api.source.SourceManagementContracts.ImpactAcknowledgement(freshness,"alice",
                            ConfigImpactAnalyzer.acknowledgementDigest(freshness,"alice"));
            Map<String,Object> publication=runtime.publishConfigurationChangeSet("alice",changeSetId,
                    "validation-source-revision-test",acknowledgement);
            assertEquals(Boolean.TRUE,publication.get("published"));

            Captured sharedConfigStale=invokeAs(servlet,"GET","/validations/"+id,Map.of(),new byte[0],"alice");
            assertEquals("STALE",ServerRuntime.JSON.readTree(sharedConfigStale.body()).path("applicability").asText(),
                    "Publishing a new active central Config revision stales the prior Shared Config validation");
            Captured packageConfigStale=invokeAs(servlet,"GET","/validations/"+packageValidationId,Map.of(),new byte[0],"alice");
            assertEquals("STALE",ServerRuntime.JSON.readTree(packageConfigStale.body()).path("applicability").asText(),
                    "Publishing a new active central Config revision stales Package validation too");

            ReentrantLock workerLock=inspectionWorkerLock(runtime);workerLock.lock();
            java.util.concurrent.CountDownLatch blockerStarted=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseBlocker=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ThreadPoolExecutor executor=validationExecutor(runtime);
            executor.execute(()->{blockerStarted.countDown();try{releaseBlocker.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            assertTrue(blockerStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            String secondId;
            try {
                Captured secondAccepted=invokeAs(servlet,"POST","/validations",Map.of(),body,"alice");
                secondId=ServerRuntime.JSON.readTree(secondAccepted.body()).path("validationId").asText();
                assertEquals(202,secondAccepted.status);
                JsonNode queuedAgain=ServerRuntime.JSON.readTree(secondAccepted.body());
                assertEquals("QUEUED",queuedAgain.path("status").asText());
                assertFalse(queuedAgain.has("environment"));
                Captured queuedLookup=invokeAs(servlet,"GET","/validations",Map.of("scope","config","matchContext","true","limit","1"),new byte[0],"alice");
                assertEquals(secondId,ServerRuntime.JSON.readTree(queuedLookup.body()).path("items").path(0).path("validationId").asText(),
                        "A Shared Config lookup recovers the queued record without another submission");
                releaseBlocker.countDown();
                boolean sawRunning=false;long runningDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<runningDeadline) {
                    JsonNode current=ServerRuntime.JSON.readTree(invokeAs(servlet,"GET","/validations/"+secondId,Map.of(),new byte[0],"alice").body());
                    if("RUNNING".equals(current.path("status").asText())){sawRunning=true;break;}
                    Thread.sleep(10);
                }
                assertTrue(sawRunning,"Validation should enter RUNNING while waiting for the held inspection Worker");
                Captured runningLookup=invokeAs(servlet,"GET","/validations",Map.of("scope","config","matchContext","true","limit","1"),new byte[0],"alice");
                assertEquals(secondId,ServerRuntime.JSON.readTree(runningLookup.body()).path("items").path(0).path("validationId").asText(),
                        "A Shared Config lookup recovers the running record without another submission");
            } finally {releaseBlocker.countDown();workerLock.unlock();}
            Captured secondResult=null;long secondDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<secondDeadline) {
                secondResult=invokeAs(servlet,"GET","/validations/"+secondId,Map.of(),new byte[0],"alice");
                if(!List.of("QUEUED","RUNNING").contains(ServerRuntime.JSON.readTree(secondResult.body()).path("status").asText()))break;
                Thread.sleep(25);
            }
            assertNotNull(secondResult);
            assertEquals("COMPLETED",ServerRuntime.JSON.readTree(secondResult.body()).path("status").asText());
            Captured completedLookup=invokeAs(servlet,"GET","/validations",Map.of("scope","config","matchContext","true","limit","1"),new byte[0],"alice");
            assertEquals(secondId,ServerRuntime.JSON.readTree(completedLookup.body()).path("items").path(0).path("validationId").asText(),
                    "The Shared Config lookup also recovers the completed record");
            stopInspectionProcesses(runtime);
        }
    }

    @Test void queuedPlanFreezesTheResolvedEnvironmentAcrossConfigurationChanges() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("validation-environment-packages"));
        createPackage(packages.resolve("payments"));
        Path runtimeConfig=sharedRuntimeConfig();
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\n  UAT: {}\ntimeoutMs: 10000\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\n");
        Path libs=workerLibraries("validation-environment-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"validation-environment-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs,true,1)) {
            ApiServlet servlet=servlet(runtime);
            java.util.concurrent.CountDownLatch blockerStarted=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseBlocker=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ThreadPoolExecutor executor=validationExecutor(runtime);
            String validationId;
            executor.execute(()->{blockerStarted.countDown();try{releaseBlocker.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
            assertTrue(blockerStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            try {
                byte[] body="{\"command\":\"validate\",\"targetKind\":\"config\",\"packageId\":\"payments\"}"
                        .getBytes(StandardCharsets.UTF_8);
                Captured accepted=invokeAs(servlet,"POST","/validations",Map.of(),body,"alice");
                JsonNode queued=ServerRuntime.JSON.readTree(accepted.body());validationId=queued.path("validationId").asText();
                assertEquals(202,accepted.status,new String(accepted.body(),StandardCharsets.UTF_8));
                assertEquals("QUEUED",queued.path("status").asText());
                assertEquals("SIT",queued.path("environment").asText(),"The durable record must capture the submitted effective Environment");

                Files.writeString(runtimeConfig,Files.readString(runtimeConfig).replace("environment: SIT","environment: UAT"));
                JsonNode stillQueued=ServerRuntime.JSON.readTree(invokeAs(servlet,"GET","/validations/"+validationId,Map.of(),new byte[0],"alice").body());
                assertEquals("QUEUED",stillQueued.path("status").asText());
                assertEquals("SIT",stillQueued.path("environment").asText());
            } finally {releaseBlocker.countDown();}

            JsonNode completed=null;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline) {
                Captured result=invokeAs(servlet,"GET","/validations/"+validationId,Map.of(),new byte[0],"alice");
                completed=ServerRuntime.JSON.readTree(result.body());
                if(!List.of("QUEUED","RUNNING").contains(completed.path("status").asText()))break;
                Thread.sleep(25);
            }
            assertNotNull(completed);
            assertEquals("COMPLETED",completed.path("status").asText());
            assertEquals("SIT",completed.path("environment").asText());
            assertEquals("SIT",completed.path("report").path("context").path("environment").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void inspectionEndpointRejectsDeterministicWorkbookCountLimit() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("packages"));
        Path counted=createPackage(packages.resolve("counted"));
        Path cases=Files.createDirectories(counted.resolve("testcase"));
        for(int index=0;index<=500;index++)Files.createFile(cases.resolve(String.format("case-%03d.xlsx",index)));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("inspection-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"inspection-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            ApiServlet servlet=servlet(runtime);
            Captured scan=invoke(servlet,"GET","/packages/counted/resources",Collections.singletonMap("type","case"),new byte[0]);
            JsonNode rejected=ServerRuntime.JSON.readTree(scan.body());
            JsonNode diagnostic=rejected.path("diagnostics").path(0);
            assertEquals(413,scan.status);
            assertEquals("ATT-RESOURCE-LIMIT",diagnostic.path("code").asText());
            assertEquals("resourceInspector.maxWorkbooks",diagnostic.path("limitName").asText(),new String(scan.body(),StandardCharsets.UTF_8));
            assertEquals(500,diagnostic.path("configuredLimit").asInt());
            assertEquals(501,diagnostic.path("observed").asInt());
            assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
            assertFalse(new String(scan.body(),StandardCharsets.UTF_8).contains(packages.toString()));
            stopInspectionProcesses(runtime);
        }
    }

    @Test void inspectionEndpointReturnsPartialSuiteForOversizedWorkbook() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("partial-packages"));
        Path oversized=createPackage(packages.resolve("oversized"));
        Path oversizedCases=Files.createDirectories(oversized.resolve("testcase"));
        try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(oversizedCases.resolve("large.xlsx").toFile(),"rw")) {
            file.setLength(32L*1024L*1024L+1L);
        }
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("partial-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"partial-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            ApiServlet servlet=servlet(runtime);
            Captured partial=invoke(servlet,"GET","/packages/oversized/resources",Collections.singletonMap("type","case"),new byte[0]);
            JsonNode response=ServerRuntime.JSON.readTree(partial.body());
            assertEquals(200,partial.status);
            assertEquals("invalid",response.path("items").path(0).path("state").asText());
            JsonNode suiteDiagnostic=response.path("items").path(0).path("diagnostics").path(0);
            assertEquals("ATT-RESOURCE-CASE-INVALID",suiteDiagnostic.path("code").asText());
            assertEquals("resourceInspector.maxWorkbookBytes",suiteDiagnostic.path("limitName").asText(),new String(partial.body(),StandardCharsets.UTF_8));
            assertEquals(32L*1024L*1024L,suiteDiagnostic.path("configuredLimit").asLong());
            assertEquals("DATA_TRUNCATED",suiteDiagnostic.path("outcome").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void inspectionEndpointRejectsWhenQueueIsSaturated() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("saturated-packages"));
        createPackage(packages.resolve("oversized"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("saturated-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"saturated-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            ApiServlet servlet=servlet(runtime);
            Semaphore inspectionSlots=semaphore(runtime,"inspectionTaskSlots");
            inspectionSlots.acquire();
            try {
                Captured saturated=invoke(servlet,"GET","/packages/oversized/resources",Collections.singletonMap("type","case"),new byte[0]);
                JsonNode capacity=ServerRuntime.JSON.readTree(saturated.body()).path("diagnostics").path(0);
                assertEquals(503,saturated.status);
                assertEquals("ATT-SERVER-INSPECTION-CAPACITY",capacity.path("code").asText());
                assertEquals("SUBMISSION_REJECTED",capacity.path("outcome").asText());
                assertEquals("server.inspection.capacity",capacity.path("limitName").asText());
            } finally {inspectionSlots.release();}
            stopInspectionProcesses(runtime);
        }
    }

    @Test void requestAndWorkerAdmissionFailuresUseTheActualServletRoutes() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("admission-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("admission-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"admission-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            ApiServlet servlet=servlet(runtime);
            byte[] oversizedBody=new byte[1025];
            Captured tooLarge=invoke(servlet,"POST","/jobs/run",Collections.emptyMap(),oversizedBody);
            JsonNode requestLimit=ServerRuntime.JSON.readTree(tooLarge.body()).path("diagnostics").path(0);
            assertEquals(413,tooLarge.status);
            assertEquals("ATT-SERVER-REQUEST-TOO-LARGE",requestLimit.path("code").asText());
            assertEquals("SUBMISSION_REJECTED",requestLimit.path("outcome").asText());
            assertEquals("server.maxRequestBytes",requestLimit.path("limitName").asText());

            Semaphore admission=semaphore(runtime,"admissionSlots");
            admission.acquire();
            try {
                Captured full=invoke(servlet,"POST","/jobs/run",Collections.emptyMap(),"{\"packageId\":\"p\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                JsonNode queue=ServerRuntime.JSON.readTree(full.body()).path("diagnostics").path(0);
                assertEquals(429,full.status);
                assertEquals("ATT-SERVER-CAPACITY-EXCEEDED",queue.path("code").asText());
                assertEquals("SUBMISSION_REJECTED",queue.path("outcome").asText());
                assertEquals("server.admission.queue",queue.path("limitName").asText());
            } finally {admission.release();}

            Semaphore inspections=semaphore(runtime,"inspectionTaskSlots");
            inspections.acquire();
            try {
                Captured full=invoke(servlet,"GET","/packages/p/resources",Collections.singletonMap("type","template"),new byte[0]);
                JsonNode queue=ServerRuntime.JSON.readTree(full.body()).path("diagnostics").path(0);
                assertEquals(503,full.status);
                assertEquals("ATT-SERVER-INSPECTION-CAPACITY",queue.path("code").asText());
                assertEquals("SUBMISSION_REJECTED",queue.path("outcome").asText());
            } finally {inspections.release();}
        }
    }

    @Test void sutMetricsRoutesEnforceAdminWritesBoundBodiesAndFilterSourcesForReaders() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-route-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        List<Map<String,Object>> dataSources=List.of(
                Map.of("schemaVersion","att-sut-metrics-source/v1","id","prom","name","Local Prometheus",
                        "url","http://127.0.0.1:9090","auth",Map.of("mode","BEARER","secretRef","PROM_TOKEN"),
                        "readers",List.of("alice"),"readRoles",List.of(),"allowedTenants",List.of("tenant-a"),"defaultTenant","tenant-a"),
                Map.of("schemaVersion","att-sut-metrics-source/v1","id","role-prom","name","Role-readable Prometheus",
                        "url","http://127.0.0.1:9091","auth",Map.of("mode","NONE"),"readers",List.of(),
                        "readRoles",List.of("USER"),"allowedTenants",List.of()));
        List<Map<String,Object>> metricSources=List.of(
                Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","private-prom-metrics","name","Private Prometheus metrics",
                        "connection",Map.of("kind","DATA_SOURCE","id","prom"),
                        "fetch",Map.of("id","prom-fetch","mode","prometheus-range","query","up{instance=\"sentinel_prometheus_query\"}",
                                "parameters",Map.of("region","sentinel_prometheus_parameter"),"sampling","range","step","15s"),
                        "mapping",Map.of("labels",Map.of("retain",List.of("instance")),
                                "outputs",List.of(Map.of("id","prom-up","title","Prometheus up","unit","count","kind","gauge","metricName","up"))),
                        "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576)),
                Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","private-db-metrics","name","Private DB metrics",
                        "connection",Map.of("kind","DBHELPER","id","orders"),
                        "fetch",Map.of("id","db-fetch","mode","dbhelper-query",
                                "call","#{db.orders.query(sql='select sentinel_dbhelper_sql from private_table')}",
                                "parameters",Map.of("tenant","sentinel_dbhelper_parameter"),"sampling","afterDrain"),
                        "mapping",Map.of("labels",Map.of("instance","sentinel_dbhelper_column"),
                                "outputs",List.of(Map.of("id","db-count","title","DB count","unit","count","kind","gauge","column","COUNT_VALUE"))),
                        "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576)),
                Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","private-tool-metrics","name","Private tool metrics",
                        "connection",Map.of("kind","PACKAGE_TOOL","id","private-tool"),
                        "fetch",Map.of("id","tool-fetch","mode","package-tool",
                                "call","#{tools.private.read(path='sentinel_package_tool_call')}",
                                "parameters",Map.of("scope","sentinel_package_tool_parameter"),"sampling","afterDrain"),
                        "mapping",Map.of("labels",Map.of("instance","sentinel_package_tool_column"),
                                "outputs",List.of(Map.of("id","tool-count","title","Tool count","unit","count","kind","gauge","column","COUNT_VALUE"))),
                        "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576)));
        Map<String,Object> groupedProfile=Map.of("schemaVersion","att-sut-metrics-profile/v2","id","grouped",
                "name","Grouped profile","metricSourceIds",List.of("private-prom-metrics"),
                "groupIds",List.of("private-prom-group"),
                "allowedBindings",Map.of("environments",List.of(),"targets",List.of(Map.of("type","target","id","sut-1")),
                        "tenants",List.of(),"parameters",Map.of()),
                "budgets",Map.of("maxFetches",4,"maxObservations",512,"maxArtifactBytes",1048576));
        List<Map<String,Object>> profiles=List.of(Map.of("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile",
                "kind","base","category","app","metrics",List.of(Map.of("id","requests","title","Requests","unit","count",
                        "kind","gauge","query","up"))),groupedProfile);
        List<Map<String,Object>> groups=List.of(Map.of("schemaVersion","att-sut-metrics-group/v1","id","private-prom-group",
                "title","Private Prometheus group","order",1,"match",Map.of("metricIds",List.of("prom-up"),
                        "sourceIds",List.of("prom"),"labels",Map.of("tenant","sentinel_private_tenant")),
                "groupBy",List.of("instance"),"display","table"));
        writeSutMetricsConfig(runtimeConfig,dataSources,metricSources,groups,profiles,true);
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"sut-metrics-route-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,workerLibraries("sut-metrics-route-libs"),true,ref->"PROM_TOKEN".equals(ref)?"api-test-token":null)) {
            ApiServlet servlet=servlet(runtime);
            String userRole=runtime.config.authorization.userRole;
            String adminRole=runtime.config.authorization.adminRole;
            byte[] source=ServerRuntime.JSON.writeValueAsBytes(Map.of("schemaVersion","att-sut-metrics-source/v1","id","prom",
                    "name","Local Prometheus","url","http://127.0.0.1:9090","auth",Map.of("mode","BEARER","secretRef","PROM_TOKEN"),
                    "readers",List.of("alice"),"readRoles",List.of(),"allowedTenants",List.of("tenant-a"),"defaultTenant","tenant-a"));

            Captured anonymousRead=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],null,Set.of());
            assertEquals(401,anonymousRead.status);
            assertEquals("private, no-store",anonymousRead.headers.get("Cache-Control"));
            Captured readerWrite=invokeAs(servlet,"POST","/admin/sut-metrics/sources",Collections.emptyMap(),source,"alice",Set.of(userRole));
            assertEquals(403,readerWrite.status);
            assertEquals("private, no-store",readerWrite.headers.get("Cache-Control"));

            byte[] oversized=new byte[1025];
            Captured oversizedWrite=invokeAs(servlet,"POST","/admin/sut-metrics/sources",Collections.emptyMap(),oversized,"admin",Set.of(adminRole));
            JsonNode diagnostic=ServerRuntime.JSON.readTree(oversizedWrite.body()).path("diagnostics").path(0);
            assertEquals(413,oversizedWrite.status);
            assertEquals("ATT-SERVER-REQUEST-TOO-LARGE",diagnostic.path("code").asText());
            assertEquals("server.maxRequestBytes",diagnostic.path("limitName").asText());
            assertEquals("private, no-store",oversizedWrite.headers.get("Cache-Control"));

            Captured adminWrite=invokeAs(servlet,"POST","/admin/sut-metrics/sources",Collections.emptyMap(),source,"admin",Set.of(adminRole));
            assertEquals(409,adminWrite.status,new String(adminWrite.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-SUT-METRICS-CONFIG-READ-ONLY",ServerRuntime.JSON.readTree(adminWrite.body()).path("error").path("code").asText());
            byte[] invalidGroup=ServerRuntime.JSON.writeValueAsBytes(Map.of("schemaVersion","att-sut-metrics-group/v1",
                    "id","invalid-group","title","Invalid group","order",10,
                    "match",Map.of("metricIds",List.of("cpu")),"groupBy",List.of(17),"display","table"));
            Captured invalidGroupWrite=invokeAs(servlet,"POST","/admin/sut-metrics/groups",Collections.emptyMap(),
                    invalidGroup,"admin",Set.of(adminRole));
            assertEquals(409,invalidGroupWrite.status,new String(invalidGroupWrite.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-SUT-METRICS-CONFIG-READ-ONLY",ServerRuntime.JSON.readTree(invalidGroupWrite.body())
                    .path("error").path("code").asText());
            Captured readerList=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],"alice",Set.of(userRole));
            JsonNode readerItem=ServerRuntime.JSON.readTree(readerList.body()).path("items").path(0);
            assertEquals(200,readerList.status);
            assertEquals("private, no-store",readerList.headers.get("Cache-Control"));
            assertEquals("Local Prometheus",readerItem.path("name").asText());
            assertTrue(readerItem.path("authConfigured").asBoolean());
            assertFalse(readerItem.has("url"),readerItem.toString());
            assertFalse(readerList.bodyText().contains("PROM_TOKEN"),readerList.bodyText());

            byte[] roleSource=ServerRuntime.JSON.writeValueAsBytes(Map.of("schemaVersion","att-sut-metrics-source/v1","id","role-prom",
                    "name","Role-readable Prometheus","url","http://127.0.0.1:9091","auth",Map.of("mode","NONE"),
                    "readers",List.of(),"readRoles",List.of("USER"),"allowedTenants",List.of()));
            Captured roleWrite=invokeAs(servlet,"POST","/admin/sut-metrics/sources",Collections.emptyMap(),roleSource,"admin",Set.of(adminRole));
            assertEquals(409,roleWrite.status,new String(roleWrite.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-SUT-METRICS-CONFIG-READ-ONLY",ServerRuntime.JSON.readTree(roleWrite.body()).path("error").path("code").asText());

            Captured otherReader=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],"bob",Set.of(userRole));
            JsonNode roleItem=ServerRuntime.JSON.readTree(otherReader.body()).path("items").path(0);
            assertEquals(200,otherReader.status);
            assertEquals(1,ServerRuntime.JSON.readTree(otherReader.body()).path("items").size());
            assertEquals("role-prom",roleItem.path("id").asText());
            Captured adminList=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],"admin",Set.of(adminRole));
            JsonNode adminItem=ServerRuntime.JSON.readTree(adminList.body()).path("items").path(0);
            assertEquals("http://127.0.0.1:9090",adminItem.path("url").asText());
            assertEquals("PROM_TOKEN",adminItem.path("auth").path("secretRef").asText());

            Captured readerMetricSources=invokeAs(servlet,"GET","/sut-metrics/metric-sources",Collections.emptyMap(),
                    new byte[0],"alice",Set.of(userRole));
            assertEquals(200,readerMetricSources.status,readerMetricSources.bodyText());
            assertEquals("private, no-store",readerMetricSources.headers.get("Cache-Control"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_prometheus_query"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_prometheus_parameter"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_dbhelper_sql"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_dbhelper_parameter"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_dbhelper_column"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_package_tool_call"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_package_tool_parameter"));
            assertFalse(readerMetricSources.bodyText().contains("sentinel_package_tool_column"));
            assertFalse(readerMetricSources.bodyText().contains("a".repeat(64)));
            JsonNode safeMetricSource=ServerRuntime.JSON.readTree(readerMetricSources.body()).path("items").path(0);
            assertFalse(safeMetricSource.has("fetch"),safeMetricSource.toString());
            assertEquals("DBHELPER",safeMetricSource.path("connection").path("kind").asText());
            assertEquals("db-count",safeMetricSource.path("mapping").path("outputs").path(0).path("id").asText());

            Captured readerGroups=invokeAs(servlet,"GET","/sut-metrics/groups",Collections.emptyMap(),
                    new byte[0],"bob",Set.of(userRole));
            assertEquals(200,readerGroups.status,readerGroups.bodyText());
            JsonNode safeGroup=ServerRuntime.JSON.readTree(readerGroups.body()).path("items").path(0);
            assertEquals("private-prom-group",safeGroup.path("id").asText());
            assertFalse(safeGroup.has("match"),safeGroup.toString());
            assertFalse(readerGroups.bodyText().contains("sentinel_private_tenant"),readerGroups.bodyText());
            assertFalse(readerGroups.bodyText().contains("prom-up"),readerGroups.bodyText());
            assertFalse(readerGroups.bodyText().contains("sourceIds"),readerGroups.bodyText());

            Captured adminGroups=invokeAs(servlet,"GET","/sut-metrics/groups",Collections.emptyMap(),
                    new byte[0],"admin",Set.of(adminRole));
            assertEquals(200,adminGroups.status,adminGroups.bodyText());
            assertTrue(adminGroups.bodyText().contains("sentinel_private_tenant"),adminGroups.bodyText());
            assertTrue(adminGroups.bodyText().contains("prom-up"),adminGroups.bodyText());

            Captured adminMetricSources=invokeAs(servlet,"GET","/sut-metrics/metric-sources",Collections.emptyMap(),
                    new byte[0],"admin",Set.of(adminRole));
            assertEquals(200,adminMetricSources.status,adminMetricSources.bodyText());
            assertTrue(adminMetricSources.bodyText().contains("sentinel_prometheus_query"));
            assertTrue(adminMetricSources.bodyText().contains("sentinel_dbhelper_sql"));
            assertTrue(adminMetricSources.bodyText().contains("sentinel_package_tool_call"));

            String groupedJobId="J0000000000000812";
            Map<String,Object> groupedScenario=Map.of("schemaVersion","att-load-observability/v1",
                    "collections",List.of(Map.of("id","grouped-binding","profile","grouped","targetIds",List.of("sut-1"))),
                    "collection",Map.of("mode","post-run","before","1m","after","1m","step","15s"),
                    "livePreview",Map.of("enabled",false));
            Map<String,Object> groupedSnapshot=runtime.sutMetrics.resolveScenario(groupedScenario,"alice",false);
            seedLoadJob(runtime,groupedJobId,"alice",groupedSnapshot);
            Captured groupedJobResponse=invokeAs(servlet,"GET","/jobs/"+groupedJobId+"/sut-metrics",Collections.emptyMap(),
                    new byte[0],"alice",Set.of(userRole));
            assertEquals(200,groupedJobResponse.status,groupedJobResponse.bodyText());
            JsonNode apiGroup=ServerRuntime.JSON.readTree(groupedJobResponse.body()).path("groups").path(0);
            assertEquals("private-prom-group",apiGroup.path("id").asText(),groupedJobResponse.bodyText());
            assertEquals("Private Prometheus group",apiGroup.path("title").asText());
            assertFalse(apiGroup.has("match"),apiGroup.toString());
            assertFalse(groupedJobResponse.bodyText().contains("sentinel_private_tenant"),groupedJobResponse.bodyText());

            Map<String,Object> wrongTenant=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                    "source","prom","tenant","tenant-b","targets",List.of(Map.of("id","sut-1","type","app")))),
                    "collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"));
            byte[] previewBody=ServerRuntime.JSON.writeValueAsBytes(Map.of("observability",wrongTenant));
            Captured tenantDenied=invokeAs(servlet,"POST","/admin/sut-metrics/profiles/preview",Collections.emptyMap(),previewBody,"admin",Set.of(adminRole));
            assertEquals(400,tenantDenied.status,new String(tenantDenied.body(),StandardCharsets.UTF_8));
            assertEquals("private, no-store",tenantDenied.headers.get("Cache-Control"));
            assertTrue(tenantDenied.bodyText().contains("tenant"),tenantDenied.bodyText());
        }
    }

    @Test void sutMetricsJobRouteRequiresJobOwnershipAndAuthorizedSourceReaders() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-job-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=writeSutMetricsConfig(sharedRuntimeConfig(),
                List.of(prometheusSource("prom","Prometheus","http://127.0.0.1:9090",List.of("alice"),List.of(),
                        List.of("tenant-a"),"tenant-a",Map.of(),Map.of())),List.of(),List.of(),
                List.of(baseProfile("base","Base profile",List.of(Map.of("id","requests","title","Requests","unit","count",
                        "kind","gauge","query","up")))),false);
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"sut-metrics-job-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,workerLibraries("sut-metrics-job-libs"),true)) {
            Map<String,Object> scenario=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                    "source","prom","tenant","tenant-a","targets",List.of(Map.of("id","sut-1","type","app")))),
                    "collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"),"livePreview",Map.of("enabled",true));
            Map<String,Object> snapshot=runtime.sutMetrics.resolveScenario(scenario,"alice",false);
            seedLoadJob(runtime,"J0000000000000711","alice",snapshot);
            seedLoadJob(runtime,"J0000000000000712","bob",snapshot);

            ApiServlet servlet=servlet(runtime);
            Captured otherOwner=invokeAs(servlet,"GET","/jobs/J0000000000000711/sut-metrics",Collections.emptyMap(),new byte[0],"bob",Set.of(runtime.config.authorization.userRole));
            Captured unauthorizedSource=invokeAs(servlet,"GET","/jobs/J0000000000000712/sut-metrics",Collections.emptyMap(),new byte[0],"bob",Set.of(runtime.config.authorization.userRole));
            Captured owner=invokeAs(servlet,"GET","/jobs/J0000000000000711/sut-metrics",Collections.emptyMap(),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));

            assertEquals(404,otherOwner.status,"Job ownership is checked before reading its SUT Metrics snapshot.");
            assertEquals("private, no-store",otherOwner.headers.get("Cache-Control"));
            assertEquals(404,unauthorizedSource.status,"Owning a Job does not grant access to a Data Source the caller cannot read.");
            assertEquals("private, no-store",unauthorizedSource.headers.get("Cache-Control"));
            assertEquals(200,owner.status,new String(owner.body(),StandardCharsets.UTF_8));
            assertEquals("PENDING",ServerRuntime.JSON.readTree(owner.body()).path("state").asText());
            assertEquals("private, no-store",owner.headers.get("Cache-Control"));
        }
    }

    @Test void sutMetricsRouteExposesCompletedBusinessPhaseAsProvisionalDuringPrometheusBuffer() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-metrics-provisional-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000811";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0);
             ServerRuntime runtime = runtime(packages, writeSutMetricsConfig(sharedRuntimeConfig(),
                     List.of(prometheusSource("prom","Prometheus",fixture.url(),List.of("alice"),List.of(),List.of(),null,Map.of(),Map.of())),
                     List.of(
                             Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","prom-metrics","name","Prometheus metrics",
                                     "connection",Map.of("kind","DATA_SOURCE","id","prom"),
                                     "fetch",Map.of("id","prom-fetch","mode","prometheus-range","query","up","parameters",Map.of(),"sampling","range","step","5m"),
                                     "mapping",Map.of("labels",Map.of("retain",List.of("instance")),"outputs",List.of(Map.of(
                                             "id","requests","title","Requests","unit","count","kind","gauge","metricName","up"))),
                                     "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576)),
                             Map.of("schemaVersion","att-sut-metrics-metric-source/v1","id","business-metrics","name","Business metrics",
                                     "connection",Map.of("kind","DBHELPER","id","orders"),
                                     "fetch",Map.of("id","business-fetch","mode","dbhelper-query",
                                             "call","#{db.orders.query(sql='select count(*) as COUNT_VALUE from orders')}",
                                             "parameters",Map.of(),"sampling","afterDrain"),
                                     "mapping",Map.of("labels",Map.of(),"outputs",List.of(Map.of(
                                             "id","count","title","Count","unit","count","kind","gauge","column","COUNT_VALUE"))),
                                     "limits",Map.of("maxRows",64,"maxSeries",16,"maxSamples",1000,"maxArtifactBytes",1048576))),
                     List.of(),List.of(
                             flatProfile("base","Base",List.of("prom-metrics"),List.of(Map.of("type","target","id","sut-1"))),
                             flatProfile("business","Business",List.of("business-metrics"),List.of())),true), "sut-metrics-provisional-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-metrics-provisional-libs"), true)) {
            Map<String, Object> scenario = new java.util.LinkedHashMap<>();
            scenario.put("schemaVersion", "att-load-observability/v1");
            scenario.put("collections", List.of(
                    Map.of("id", "prom-binding", "profile", "base", "targetIds", List.of("sut-1")),
                    Map.of("id", "business-binding", "profile", "business")));
            scenario.put("collection", Map.of("mode", "post-run", "before", "1m", "after", "1h", "step", "5m"));
            scenario.put("livePreview", Map.of("enabled", false));
            scenario.put("thresholds", List.of(Map.of("id", "count-max", "profile", "business", "metric", "count", "max", 10.0)));
            Map<String, Object> snapshot = runtime.sutMetrics.resolveScenario(scenario, "alice", false,
                    businessCallContext(runtime,packages.resolve("p")));
            runtime.sutMetrics.setBusinessCallExecutor((ignoredJob, ignoredSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) { return 12; }
                @Override public void close() { }
            });
            seedLoadJob(runtime, jobId, "alice", snapshot, "{}");
            Instant finishedAt = Instant.now().minusSeconds(2), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Map<String, Object> manifest;
            do {
                manifest = runtime.sutMetrics.collectionStatus(jobId);
                if (manifest.get("collectionProgress") instanceof Map<?, ?> progress
                        && "COMPLETE".equals(progress.get("business"))) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertEquals("PENDING", manifest.get("state"));
            assertEquals(0, fixture.requests.get(), "Prometheus must remain in its configured after-buffer.");

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/sut-metrics",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode body = ServerRuntime.JSON.readTree(response.body());
            assertEquals(200, response.status, response.bodyText());
            assertTrue(body.path("provisional").asBoolean(), body.toString());
            assertEquals("PENDING", body.path("state").asText());
            assertEquals("COMPLETE", body.path("collection").path("collectionProgress").path("business").asText());
            assertEquals("PENDING", body.path("collection").path("collectionProgress").path("prometheus").asText());
            assertTrue(body.path("collection").path("eligibleAt").isTextual(), body.toString());
            assertEquals(1, body.path("items").size(), "The API exposes the completed business value before Prometheus is eligible.");
            assertEquals("count", body.path("items").get(0).path("metricId").asText());
            assertEquals("PENDING", body.path("thresholdEvaluation").path("state").asText());
        }
    }

    @Test void loadResultRouteReturnsNativeAndSutCombinedPerformanceVerdict() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-result-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000731";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.1);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-result-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-result-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));

            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode acceptance = ServerRuntime.JSON.readTree(response.body()).path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", acceptance.path("nativePerformanceVerdict").asText());
            assertEquals("NOT_MET", acceptance.path("sutThresholds").path("status").asText());
            assertEquals("NOT_MET", acceptance.path("performanceVerdict").asText());
            JsonNode sutTargets = acceptance.path("sutThresholds").path("items").path(0).path("targets");
            assertEquals("NOT_MET", sutTargets.get(0).path("status").asText());
            assertEquals("sut-a", sutTargets.get(0).path("target").path("id").asText());
            assertEquals("NOT_EVALUATED", sutTargets.get(1).path("status").asText());
            assertEquals("sut-b", sutTargets.get(1).path("target").path("id").asText());
        }
    }

    @Test void corruptExternalEvaluationDoesNotBreakNativeLoadResultRoute() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-corrupt-result-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000735";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.0);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-corrupt-result-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-corrupt-result-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);
            Path evaluation = runtime.config.dataDir.resolve("jobs").resolve(jobId)
                    .resolve("sut-metrics/threshold-evaluation.json");
            Files.writeString(evaluation, "{truncated");

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode body = ServerRuntime.JSON.readTree(response.body());
            JsonNode acceptance = body.path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", body.path("result").path("summary").path("performance").path("verdict").asText());
            assertEquals("ERROR", acceptance.path("externalEvaluationStatus").asText());
            assertEquals("NOT_EVALUATED", acceptance.path("performanceVerdict").asText());
            assertEquals("THRESHOLD_EVALUATION_UNAVAILABLE",
                    acceptance.path("externalEvaluation").path("diagnostics").path(0).path("code").asText());
        }
    }

    @Test void corruptSutSnapshotDoesNotBreakNativeLoadResultRoute() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-corrupt-snapshot-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000736";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.0);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-corrupt-snapshot-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-corrupt-snapshot-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);
            Path savedSnapshot = runtime.config.dataDir.resolve("jobs").resolve(jobId)
                    .resolve("sut-metrics/snapshot.json");
            Files.writeString(savedSnapshot, "{truncated");

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode body = ServerRuntime.JSON.readTree(response.body());
            JsonNode acceptance = body.path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", body.path("result").path("summary").path("performance").path("verdict").asText());
            assertEquals("ERROR", acceptance.path("externalEvaluationStatus").asText());
            assertEquals("NOT_EVALUATED", acceptance.path("performanceVerdict").asText());
            assertEquals("SUT_SNAPSHOT_UNAVAILABLE",
                    acceptance.path("externalEvaluation").path("diagnostics").path(0).path("code").asText());
        }
    }

    @Test void corruptExternalEvaluationDoesNotBreakSutMetricsRoute() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-corrupt-sut-metrics-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000737";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.0);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-corrupt-sut-metrics-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-corrupt-sut-metrics-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);
            Path evaluation = runtime.config.dataDir.resolve("jobs").resolve(jobId)
                    .resolve("sut-metrics/threshold-evaluation.json");
            Files.writeString(evaluation, "{truncated");

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/sut-metrics",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode body = ServerRuntime.JSON.readTree(response.body());
            assertEquals(200, response.status, response.bodyText());
            assertEquals("READY", body.path("collection").path("state").asText());
            assertTrue(body.path("items").isArray() && body.path("items").size() > 0,
                    "Valid SUT collection evidence should remain available.");
            assertEquals("ERROR", body.path("thresholdEvaluation").path("state").asText());
            assertEquals("THRESHOLD_EVALUATION_UNAVAILABLE",
                    body.path("thresholdEvaluation").path("diagnostics").path(0).path("code").asText());
            assertEquals("NOT_EVALUATED", body.path("performanceAcceptance").path("performanceVerdict").asText());
        }
    }

    @Test void loadResultRouteKeepsAProvenViolationWhenAnotherTargetHasNoEvidence() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-missing-result-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000732";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.1, "sut-b");
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-missing-result-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-missing-result-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, true);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode acceptance = ServerRuntime.JSON.readTree(response.body()).path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", acceptance.path("nativePerformanceVerdict").asText());
            assertEquals("NOT_MET", acceptance.path("sutThresholds").path("status").asText());
            assertEquals("NOT_MET", acceptance.path("performanceVerdict").asText());
            JsonNode targets = acceptance.path("sutThresholds").path("items").path(0).path("targets");
            assertTargetStatus(targets, "sut-a", "NOT_MET");
            assertTargetStatus(targets, "sut-b", "NOT_EVALUATED");
        }
    }

    @Test void loadResultRouteDoesNotLetAnOptionalSutFailureOverrideNativeMet() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-optional-result-packages"));
        createPackage(packages.resolve("p"));
        String jobId = "J0000000000000733";
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.1);
             ServerRuntime runtime = runtime(packages, writeTwoTargetRuntimeConfig(sharedRuntimeConfig(), fixture), "sut-threshold-optional-result-data",
                     DEFAULT_INSPECTION_TIMEOUT_MS, null, workerLibraries("sut-threshold-optional-result-libs"), true)) {
            Map<String, Object> snapshot = twoTargetThresholdSnapshot(runtime, fixture, false);
            seedLoadJob(runtime, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
            Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
            runtime.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                    List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                            "endAt", finishedAt.toString(), "measured", true)));
            awaitThresholdEvaluation(runtime, jobId);

            Captured response = invokeAs(servlet(runtime), "GET", "/jobs/" + jobId + "/result",
                    Collections.emptyMap(), new byte[0], "alice", Set.of(runtime.config.authorization.userRole));
            JsonNode acceptance = ServerRuntime.JSON.readTree(response.body()).path("performanceAcceptance");
            assertEquals(200, response.status, response.bodyText());
            assertEquals("MET", acceptance.path("nativePerformanceVerdict").asText());
            assertEquals("MET", acceptance.path("sutThresholds").path("status").asText());
            assertEquals("MET", acceptance.path("performanceVerdict").asText());
            JsonNode rule = acceptance.path("sutThresholds").path("items").path(0);
            assertFalse(rule.path("required").asBoolean());
            assertEquals("NOT_MET", rule.path("status").asText());
        }
    }

    @Test void loadResultRouteResumesPendingEvaluationAfterRestart() throws Exception {
        Path packages = Files.createDirectories(temp.resolve("sut-threshold-restart-result-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig = sharedRuntimeConfig();
        Path libs = workerLibraries("sut-threshold-restart-result-libs");
        String dataName = "sut-threshold-restart-result-data";
        String jobId = "J0000000000000734";
        Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(30);
        try (ApiPrometheusFixture fixture = new ApiPrometheusFixture(0, 0, 90.0)) {
            writeTwoTargetRuntimeConfig(runtimeConfig, fixture);
            ServerRuntime first = runtime(packages, runtimeConfig, dataName, DEFAULT_INSPECTION_TIMEOUT_MS, null, libs, true);
            CountDownLatch releaseEvaluators = null;
            try {
                Map<String, Object> snapshot = twoTargetThresholdSnapshot(first, fixture, true);
                seedLoadJob(first, jobId, "alice", snapshot, nativeLoadResult(jobId, "MET"));
                releaseEvaluators = blockThresholdEvaluators(first);
                first.sutMetrics.jobFinished(jobId, startedAt, finishedAt,
                        List.of(Map.of("phase", "STEADY", "startAt", startedAt.plusSeconds(10).toString(),
                                "endAt", finishedAt.toString(), "measured", true)));
                awaitCollectionState(first, jobId, "READY");
                awaitEvaluationState(first, jobId, "EVALUATING");

                Captured pendingResponse = invokeAs(servlet(first), "GET", "/jobs/" + jobId + "/result",
                        Collections.emptyMap(), new byte[0], "alice", Set.of(first.config.authorization.userRole));
                JsonNode pendingAcceptance = ServerRuntime.JSON.readTree(pendingResponse.body()).path("performanceAcceptance");
                assertEquals(200, pendingResponse.status, pendingResponse.bodyText());
                assertEquals("EVALUATING", pendingAcceptance.path("externalEvaluationStatus").asText());
                assertEquals("PENDING", pendingAcceptance.path("performanceVerdict").asText());
            } finally {
                first.close();
                if (releaseEvaluators != null) releaseEvaluators.countDown();
            }

            try (ServerRuntime recovered = runtime(packages, runtimeConfig, dataName, DEFAULT_INSPECTION_TIMEOUT_MS, null, libs, true)) {
                assertEquals("MET", awaitThresholdEvaluation(recovered, jobId).get("state"));
                Captured finalResponse = invokeAs(servlet(recovered), "GET", "/jobs/" + jobId + "/result",
                        Collections.emptyMap(), new byte[0], "alice", Set.of(recovered.config.authorization.userRole));
                JsonNode finalAcceptance = ServerRuntime.JSON.readTree(finalResponse.body()).path("performanceAcceptance");
                assertEquals(200, finalResponse.status, finalResponse.bodyText());
                assertEquals("MET", finalAcceptance.path("externalEvaluationStatus").asText());
                assertEquals("MET", finalAcceptance.path("performanceVerdict").asText());
            }
        }
    }

    @Test void sutMetricsLiveRouteUsesPinnedTenantAndReturnsBoundedTimeoutAndResponseFailures() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-live-api-packages"));
        createPackage(packages.resolve("p"));
        Map<String,Map<String,Object>> cases=Map.of(
                "ready",Map.of(),
                "timeout",Map.of("requestTimeoutMs",250),
                "response-limit",Map.of("maxResponseBytes",1024));
        for(Map.Entry<String,Map<String,Object>> entry:cases.entrySet()) {
            String name=entry.getKey();boolean delay="timeout".equals(name);int responseBytes="response-limit".equals(name)?2048:0;
            try(ApiPrometheusFixture fixture=new ApiPrometheusFixture(delay?700:0,responseBytes);
                ServerRuntime runtime=runtime(packages,writeSutMetricsConfig(sharedRuntimeConfig(),
                        List.of(prometheusSource("prom","Prometheus",fixture.url(),List.of(),List.of("USER"),List.of("tenant-a"),"tenant-a",
                                entry.getValue(),Map.of())),List.of(),List.of(),List.of(baseProfile("base","Base profile",
                                List.of(Map.of("id","requests","title","Requests","unit","count","kind","gauge","query","up")))),false),
                        "sut-metrics-live-api-"+name,DEFAULT_INSPECTION_TIMEOUT_MS,
                        null,workerLibraries("sut-metrics-live-api-"+name+"-libs"),true)) {
                Map<String,Object> scenario=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                        "source","prom","tenant","tenant-a","targets",List.of(Map.of("id","sut-1","type","app")))),
                        "collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"),"livePreview",Map.of("enabled",true));
                Map<String,Object> snapshot=runtime.sutMetrics.resolveScenario(scenario,"alice",false);
                seedRunningLoadJob(runtime,"J0000000000000721","alice",snapshot);

                Captured result=invokeAs(servlet(runtime),"GET","/jobs/J0000000000000721/sut-metrics/live",
                        Map.of("range","15m","step","30s"),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));
                JsonNode response=ServerRuntime.JSON.readTree(result.body());
                assertEquals(200,result.status,new String(result.body(),StandardCharsets.UTF_8));
                assertEquals("ready".equals(name)?"READY":"UNAVAILABLE",response.path("state").asText(),response.toString());
                assertEquals(1,fixture.requests.get());
                assertEquals("tenant-a",fixture.tenant.get());
                assertEquals("up",fixture.query.get());
                assertEquals("private, no-store",result.headers.get("Cache-Control"));
                assertFalse(result.bodyText().contains(fixture.url()),result.bodyText());
                if(!"ready".equals(name)) {
                    JsonNode item=response.path("items").path(0);
                    assertEquals("UNAVAILABLE",item.path("queryStatus").asText(),item.toString());
                    assertTrue(item.path("error").asText().toLowerCase().contains("timeout")
                            || item.path("error").asText().toLowerCase().contains("timed out")
                            || item.path("error").asText().toLowerCase().contains("limit"),item.toString());
                }
            }
        }
    }

    @Test void sutMetricsLiveRouteCoalescesConcurrentViewersAndForwardsBearerSecretWithoutLeakingIt() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-live-auth-packages"));
        createPackage(packages.resolve("p"));
        try(ApiPrometheusFixture fixture=new ApiPrometheusFixture(800,0);
            ServerRuntime runtime=runtime(packages,writeSutMetricsConfig(sharedRuntimeConfig(),
                    List.of(prometheusSource("prom","Authenticated Prometheus",fixture.url(),List.of("alice"),List.of("USER"),
                            List.of("tenant-a"),"tenant-a",Map.of(),Map.of("mode","BEARER","secretRef","PROM_TOKEN"))),
                    List.of(),List.of(),List.of(baseProfile("base","Base profile",
                            List.of(Map.of("id","requests","title","Requests","unit","count","kind","gauge","query","up")))),false),
                    "sut-metrics-live-auth-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                    null,workerLibraries("sut-metrics-live-auth-libs"),true,ref->"PROM_TOKEN".equals(ref)?"api-test-token":"wrong-ref")) {
            Map<String,Object> scenario=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                    "source","prom","tenant","tenant-a","targets",List.of(Map.of("id","sut-1","type","app")))),
                    "collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"),"livePreview",Map.of("enabled",true));
            Map<String,Object> snapshot=runtime.sutMetrics.resolveScenario(scenario,"alice",false);
            seedRunningLoadJob(runtime,"J0000000000000722","alice",snapshot);
            ApiServlet api=servlet(runtime);

            while(Instant.now().getEpochSecond()%30<8||Instant.now().getEpochSecond()%30>21)Thread.sleep(20);
            ExecutorService viewers=Executors.newFixedThreadPool(2);
            CountDownLatch start=new CountDownLatch(1);
            try {
                Future<Captured> first=viewers.submit(()->{start.await();return invokeAs(api,"GET","/jobs/J0000000000000722/sut-metrics/live",
                        Map.of("range","15m","step","30s"),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));});
                Future<Captured> second=viewers.submit(()->{start.await();return invokeAs(api,"GET","/jobs/J0000000000000722/sut-metrics/live",
                        Map.of("range","15m","step","30s"),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));});
                start.countDown();
                Captured firstResponse=first.get(5,TimeUnit.SECONDS),secondResponse=second.get(5,TimeUnit.SECONDS);
                assertEquals(200,firstResponse.status,firstResponse.bodyText());assertEquals(200,secondResponse.status,secondResponse.bodyText());
                assertEquals("READY",ServerRuntime.JSON.readTree(firstResponse.body()).path("state").asText());
                assertEquals("READY",ServerRuntime.JSON.readTree(secondResponse.body()).path("state").asText());
                assertEquals(1,fixture.requests.get(),"Concurrent viewers through the Server route share one Prometheus request.");
                assertEquals("Bearer api-test-token",fixture.authorization.get());
                assertEquals("tenant-a",fixture.tenant.get());
                assertFalse(firstResponse.bodyText().contains("api-test-token"));
                assertFalse(secondResponse.bodyText().contains("api-test-token"));
                assertFalse(runtime.sutMetrics.listSources("alice",false).toString().contains("api-test-token"));
            } finally {viewers.shutdownNow();viewers.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }

    @Test void sutMetricsLiveRouteReturns503WhenBoundedPreviewCapacityIsSaturated() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-live-capacity-packages"));
        createPackage(packages.resolve("p"));
        try(ApiPrometheusFixture fixture=new ApiPrometheusFixture(10000,0);
            ServerRuntime runtime=runtime(packages,writeSutMetricsConfig(sharedRuntimeConfig(),
                    List.of(prometheusSource("prom","Prometheus",fixture.url(),List.of("alice"),List.of("USER"),List.of(),null,
                            Map.of("requestTimeoutMs",60000),Map.of())),List.of(),List.of(),
                    List.of(baseProfile("base","Base profile",List.of(Map.of("id","requests","title","Requests","unit","count",
                            "kind","gauge","query","up")))),false),
                    "sut-metrics-live-capacity-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                    null,workerLibraries("sut-metrics-live-capacity-libs"),true,ref->null)) {
            List<Map<String,Object>> targets=new java.util.ArrayList<>();
            for(int index=0;index<21;index++)targets.add(Map.of("id","sut-"+index,"type","app"));
            Map<String,Object> scenario=Map.of("schemaVersion","att-load-observability/v1","collections",List.of(Map.of("profile","base",
                    "source","prom","targets",targets)),"collection",Map.of("mode","post-run","step","30s","before","1m","after","1m"),
                    "livePreview",Map.of("enabled",true));
            Map<String,Object> snapshot=runtime.sutMetrics.resolveScenario(scenario,"alice",false);
            seedRunningLoadJob(runtime,"J0000000000000723","alice",snapshot);
            ApiServlet api=servlet(runtime);
            ExecutorService viewers=Executors.newFixedThreadPool(21);
            ExecutorCompletionService<Captured> completed=new ExecutorCompletionService<>(viewers);
            CountDownLatch start=new CountDownLatch(1);
            try {
                for(int index=0;index<4;index++) {
                    String target="sut-"+index;
                    completed.submit(()->{start.await();return invokeAs(api,"GET","/jobs/J0000000000000723/sut-metrics/live",
                            Map.of("range","15m","step","30s","targetId",target),new byte[0],"alice",Set.of(runtime.config.authorization.userRole));});
                }
                start.countDown();
                long sourceDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(fixture.requests.get()<4&&System.nanoTime()<sourceDeadline)Thread.sleep(10);
                assertEquals(4,fixture.requests.get(),"The four active preview workers must be held at the source before filling the queue.");
                for(int index=4;index<21;index++) {
                    String target="sut-"+index;
                    completed.submit(()->invokeAs(api,"GET","/jobs/J0000000000000723/sut-metrics/live",
                            Map.of("range","15m","step","30s","targetId",target),new byte[0],"alice",Set.of(runtime.config.authorization.userRole)));
                }
                Future<Captured> rejected=completed.poll(5,TimeUnit.SECONDS);
                Captured saturated=rejected==null?null:rejected.get(5,TimeUnit.SECONDS);
                assertNotNull(saturated,"A request should be rejected as soon as all bounded preview slots are occupied.");
                JsonNode diagnostic=ServerRuntime.JSON.readTree(saturated.body()).path("diagnostics").path(0);
                assertEquals(503,saturated.status,saturated.bodyText());
                assertEquals("ATT-SUT-METRICS-CAPACITY",diagnostic.path("code").asText());
                assertEquals("private, no-store",saturated.headers.get("Cache-Control"));
                assertEquals(4,fixture.requests.get(),"Only the fixed-size live-preview worker pool may reach Prometheus while saturated.");
            } finally {viewers.shutdownNow();viewers.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }

    private void seedLoadJob(ServerRuntime runtime,String id,String owner,Map<String,Object> snapshot) throws Exception {
        seedLoadJob(runtime, id, owner, snapshot, "{}");
    }

    private void seedLoadJob(ServerRuntime runtime,String id,String owner,Map<String,Object> snapshot,String resultJson) throws Exception {
        Path jobDir=runtime.config.dataDir.resolve("jobs").resolve(id);
        Files.createDirectories(jobDir.resolve("output"));
        Job job=new Job(id,"load","p",owner,new WorkerRequest(),new JobEvents(jobDir.resolve("events.jsonl"),100));
        job.status="PASS";job.exitCode=0;job.finishedAt=Instant.now();
        runtime.store.insert(job,resultJson);
        job.resultJson=resultJson;
        runtime.store.update(job);
        runtime.sutMetrics.writeSnapshot(id,snapshot);
    }

    private Map<String, Object> twoTargetThresholdSnapshot(ServerRuntime runtime, ApiPrometheusFixture fixture, boolean required) throws Exception {
        Map<String, Object> scenario = Map.of("schemaVersion", "att-load-observability/v1",
                "collections", List.of(Map.of("id", "primary", "profile", "base", "source", "prom",
                        "targets", List.of(
                                Map.of("id", "sut-a", "type", "app", "parameters", Map.of("instance", "sut-a")),
                                Map.of("id", "sut-b", "type", "app", "parameters", Map.of("instance", "sut-b"))))),
                "collection", Map.of("mode", "post-run", "step", "15s", "before", "1m", "after", "1m"),
                "thresholds", List.of(Map.of("id", "cpu-max-90", "profile", "primary", "metric", "cpu", "max", 90.0,
                        "unit", "percent", "minimumCoverage", 0.95, "required", required)),
                "livePreview", Map.of("enabled", true));
        return runtime.sutMetrics.resolveScenario(scenario, "alice", false);
    }

    private String nativeLoadResult(String jobId, String verdict) throws Exception {
        return ServerRuntime.JSON.writeValueAsString(Map.of("executionId", jobId, "status", "PASS", "exitCode", 0,
                "summary", Map.of("executionStatus", "PASS", "performanceVerdict", verdict,
                        "thresholds", Map.of("total", 1, "performanceVerdict", verdict, "results", List.of()))));
    }

    private void assertTargetStatus(JsonNode targets, String targetId, String expectedStatus) {
        for (JsonNode target : targets) {
            if (targetId.equals(target.path("target").path("id").asText())) {
                assertEquals(expectedStatus, target.path("status").asText(), target.toString());
                return;
            }
        }
        fail("Missing SUT threshold result for target " + targetId + ": " + targets);
    }

    private CountDownLatch blockThresholdEvaluators(ServerRuntime runtime) throws Exception {
        Field field = SutMetricsService.class.getDeclaredField("evaluators");
        field.setAccessible(true);
        ExecutorService evaluators = (ExecutorService) field.get(runtime.sutMetrics);
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        for (int index = 0; index < 2; index++) evaluators.execute(() -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        if (!started.await(5, TimeUnit.SECONDS)) {
            release.countDown();
            fail("Threshold evaluation workers did not start in time.");
        }
        return release;
    }

    private Map<String, Object> awaitCollectionState(ServerRuntime runtime, String jobId, String expectedState) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            Map<String, Object> manifest = runtime.sutMetrics.collectionStatus(jobId);
            if (expectedState.equals(manifest.get("state"))) return manifest;
            if (Set.of("FAILED", "UNAVAILABLE").contains(manifest.get("state")))
                fail("Collection ended before the expected state: " + manifest);
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("SUT Metrics collection did not reach " + expectedState + " before the test deadline.");
        return Map.of();
    }

    private Map<String, Object> awaitEvaluationState(ServerRuntime runtime, String jobId, String expectedState) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            Map<String, Object> evaluation = runtime.sutMetrics.thresholdEvaluationStatus(jobId);
            if (expectedState.equals(evaluation.get("state"))) return evaluation;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("SUT threshold evaluation did not reach " + expectedState + " before the test deadline.");
        return Map.of();
    }

    private Map<String,Object> awaitThresholdEvaluation(ServerRuntime runtime,String jobId) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        do {
            Map<String,Object> evaluation=runtime.sutMetrics.thresholdEvaluationStatus(jobId);
            if(Set.of("MET","NOT_MET","NOT_EVALUATED","ERROR").contains(evaluation.get("state")))return evaluation;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        fail("SUT threshold evaluation did not finish before the test deadline.");
        return Map.of();
    }

    private void seedRunningLoadJob(ServerRuntime runtime,String id,String owner,Map<String,Object> snapshot) throws Exception {
        Path jobDir=runtime.config.dataDir.resolve("jobs").resolve(id);
        Files.createDirectories(jobDir.resolve("output"));
        Job job=new Job(id,"load","p",owner,new WorkerRequest(),new JobEvents(jobDir.resolve("events.jsonl"),100));
        job.status="RUNNING";job.startedAt=Instant.now();job.loadProgress=Map.of("capturedAt",Instant.now().toString(),"elapsedMs",300_000L);
        runtime.store.insert(job,"{}");runtime.jobs.put(id,job);runtime.sutMetrics.writeSnapshot(id,snapshot);
    }

    @Test void inspectionDeadlineFlowsThroughTheServletAs504WithAContractDiagnostic() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("timeout-packages"));
        createPackage(packages.resolve("p"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("timeout-libs");
        try(ServerRuntime runtime=runtime(packages,runtimeConfig,"timeout-data",100,null,libs)) {
            Object worker=inspectionWorkers(runtime).get(0);
            Field lockField=worker.getClass().getDeclaredField("lock");lockField.setAccessible(true);
            ReentrantLock lock=(ReentrantLock)lockField.get(worker);
            lock.lock();
            Captured timeout;
            try {timeout=invoke(servlet(runtime),"GET","/packages/p/resources",Collections.singletonMap("type","template"),new byte[0]);}
            finally {lock.unlock();}
            JsonNode diagnostic=ServerRuntime.JSON.readTree(timeout.body()).path("diagnostics").path(0);
            assertEquals(504,timeout.status);
            assertEquals("ATT-SERVER-INSPECTION-TIMEOUT",diagnostic.path("code").asText());
            assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
            assertEquals("server.inspection.timeoutMs",diagnostic.path("limitName").asText());
        }
    }

    @Test void privilegedAuthoringSourceCapacityIs503ForPackageAndCentralRoutes() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("authoring-capacity-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"authoring-capacity-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("authoring-capacity-libs"))) {
            addAuthoringUsers(runtime);
            ApiServlet servlet=servlet(runtime);Semaphore inspections=semaphore(runtime,"inspectionTaskSlots");inspections.acquire();
            try {
                Captured packageSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                        Collections.singletonMap("type","manifest"),new byte[0]);
                Captured centralSource=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                        Collections.singletonMap("path","config/config.yaml"),new byte[0]);
                assertSafeSourceFailure(packageSource,503,"ATT-SERVER-INSPECTION-CAPACITY");
                assertSafeSourceFailure(centralSource,503,"ATT-SERVER-INSPECTION-CAPACITY");
            } finally {inspections.release();}
            Captured protectedPath=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/att-server.yaml"),new byte[0]);
            assertSafeSourceFailure(protectedPath,404,"ATT-SERVER-NOT-FOUND");
        }
    }

    @Test void authoringSourceReadsReturnRequiredChangeSetRevisionPreconditions() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("authoring-revisions-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"authoring-revisions-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("authoring-revisions-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            Captured packageSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Collections.singletonMap("type","manifest"),new byte[0]);
            JsonNode packageBody=ServerRuntime.JSON.readTree(packageSource.body());
            assertEquals(200,packageSource.status,new String(packageSource.body(),StandardCharsets.UTF_8));
            assertTrue(packageBody.path("baseSourceRevision").asText().matches("[a-f0-9]{64}"),packageBody.toString());
            assertTrue(packageBody.path("registryRevision").asText().matches("[a-f0-9]{64}"),packageBody.toString());

            Captured centralSource=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/config.yaml"),new byte[0]);
            JsonNode centralBody=ServerRuntime.JSON.readTree(centralSource.body());
            assertEquals(200,centralSource.status,new String(centralSource.body(),StandardCharsets.UTF_8));
            assertTrue(centralBody.path("baseSourceRevision").asText().matches("[a-f0-9]{64}"),centralBody.toString());
            assertTrue(centralBody.path("registryRevision").isMissingNode(),centralBody.toString());

            String baseRevision=packageBody.path("baseSourceRevision").asText();
            String registryRevision=packageBody.path("registryRevision").asText();
            Map<String,Object> operation=Map.of("operation","create","path","templates/stale-editor.yaml","content","id: stale\n");
            Captured missingBase=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("registryRevision",registryRevision,"operations",List.of(operation))));
            assertEquals(400,missingBase.status,new String(missingBase.body(),StandardCharsets.UTF_8));
            Captured missingRegistry=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("baseSourceRevision",baseRevision,"operations",List.of(operation))));
            assertEquals(400,missingRegistry.status,new String(missingRegistry.body(),StandardCharsets.UTF_8));

            Field transactionField=ServerRuntime.class.getDeclaredField("sourceFileTransaction");transactionField.setAccessible(true);
            SourceFileTransaction transaction=(SourceFileTransaction)transactionField.get(runtime);
            Path configuredPackage=runtime.packageRegistry.snapshot().packages.get("alice").toRealPath();
            Path active=transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,"alice",configuredPackage);
            byte[] external= "id: external\n".getBytes(StandardCharsets.UTF_8);
            transaction.commit(ChangeSetPublisher.PACKAGE_SOURCE,"alice",active,"alice",baseRevision,"c".repeat(64),
                    Map.of("templates/external.yaml",new SourceFileTransaction.Write("templates/external.yaml",external,null)),()->{});
            Captured stale=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("baseSourceRevision",baseRevision,
                            "registryRevision",registryRevision,"operations",List.of(operation))));
            assertEquals(409,stale.status,new String(stale.body(),StandardCharsets.UTF_8));
            stopInspectionProcesses(runtime);
        }
    }

    @Test void realChangeSetServletResponsesMatchVersionedSchemaAndNoStoreContract() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("change-set-schema-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        Path libs=workerLibraries("change-set-schema-libs");
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"change-set-schema-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,libs)) {
            addAuthoringUsers(runtime);
            ApiServlet servlet=servlet(runtime);
            com.fasterxml.jackson.databind.JsonNode schemaNode;
            try(java.io.InputStream stream=getClass().getResourceAsStream("/schemas/att-source-management-v1.schema.json")) {
                assertNotNull(stream);
                schemaNode=ServerRuntime.JSON.readTree(stream);
            }
            com.networknt.schema.JsonSchema schema=com.networknt.schema.JsonSchemaFactory
                    .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schemaNode);

            Captured packageSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Collections.singletonMap("type","manifest"),new byte[0]);
            JsonNode packageRevisions=ServerRuntime.JSON.readTree(packageSource.body());
            assertEquals(200,packageSource.status);
            Map<String,Object> packageOperation=Map.of("operation","create","path","files/wire.txt","content","safe static file\n");
            Map<String,Object> packageStage=Map.of("baseSourceRevision",packageRevisions.path("baseSourceRevision").asText(),
                    "registryRevision",packageRevisions.path("registryRevision").asText(),"operations",List.of(packageOperation));
            Captured staged=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(packageStage));
            JsonNode stageBody=assertChangeSetSchema(schema,staged,201);
            assertEquals("draft",stageBody.path("state").asText());
            assertTrue(stageBody.path("revisions").path("packageRegistration").isObject());
            String changeId=stageBody.path("changeSetId").asText();

            for(String action:List.of("validate","review")) {
                Captured response=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+changeId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
                assertChangeSetSchema(schema,response,200);
            }
            Captured published=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+changeId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertChangeSetSchema(schema,published,200);

            Captured currentPackage=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Collections.singletonMap("type","manifest"),new byte[0]);
            JsonNode currentPackageRevisions=ServerRuntime.JSON.readTree(currentPackage.body());
            Captured discardStage=invokeAs(servlet,"alice","POST","/packages/alice/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("baseSourceRevision",currentPackageRevisions.path("baseSourceRevision").asText(),
                            "registryRevision",currentPackageRevisions.path("registryRevision").asText(),"operations",
                            List.of(Map.of("operation","create","path","files/discard.txt","content","discard me\n")))));
            JsonNode discardDraft=assertChangeSetSchema(schema,discardStage,201);
            Captured discarded=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"
                            +discardDraft.path("changeSetId").asText()+"/discard",Collections.emptyMap(),
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertChangeSetSchema(schema,discarded,200);

            Captured centralSource=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/config.yaml"),new byte[0]);
            JsonNode centralRevisions=ServerRuntime.JSON.readTree(centralSource.body());
            Captured centralStage=invokeAs(servlet,"admin","POST","/configuration/change-sets",Collections.emptyMap(),
                    ServerRuntime.JSON.writeValueAsBytes(Map.of("baseSourceRevision",centralRevisions.path("baseSourceRevision").asText(),
                            "operations",List.of(Map.of("operation","replace","path","config/config.yaml",
                                    "content",Files.readString(runtimeConfig))))));
            JsonNode centralDraft=assertChangeSetSchema(schema,centralStage,201);
            assertTrue(centralDraft.path("revisions").path("packageRegistration").isNull(),
                    "central Config responses preserve absent Package registration identities as null");
            Captured centralDiscard=invokeAs(servlet,"admin","POST","/configuration/change-sets/"
                            +centralDraft.path("changeSetId").asText()+"/discard",Collections.emptyMap(),
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertChangeSetSchema(schema,centralDiscard,200);
        }
    }

    @Test void packageZipImportStagesReviewsAndPublishesPackageLocalChanges() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("package-zip-import-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"package-zip-import-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("package-zip-import-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            byte[] archive=packageZip("files/z-imported.txt","last\n","files/a-imported.txt","first\n");
            byte[] runtimeConfigBefore=Files.readAllBytes(runtimeConfig);

            Captured wrongType=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),archive);
            assertEquals(415,wrongType.status,new String(wrongType.body(),StandardCharsets.UTF_8));

            Captured malformed=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),new byte[]{1,2,3},"application/zip");
            assertEquals(400,malformed.status,new String(malformed.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-PACKAGE-ZIP-INVALID",ServerRuntime.JSON.readTree(malformed.body())
                    .path("error").path("code").asText());

            Captured unauthorized=invokeAsContentType(servlet,"alice","POST","/packages/bob/change-sets/import",
                    Collections.emptyMap(),new byte[]{1,2,3},"application/zip");
            assertEquals(403,unauthorized.status,new String(unauthorized.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-PACKAGE-SOURCE-DENIED",ServerRuntime.JSON.readTree(unauthorized.body())
                    .path("error").path("code").asText(),"Package ownership is checked before parsing an archive");

            byte[] traversal=packageZip("files/partial-import.txt","must-not-publish\n","../outside.yaml","escaped\n");
            Captured rejectedTraversal=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),traversal,"application/zip");
            assertEquals(400,rejectedTraversal.status,new String(rejectedTraversal.body(),StandardCharsets.UTF_8));
            assertFalse(Files.exists(ownerPackage.getParent().resolve("outside.yaml")),"A rejected traversal ZIP writes no outside file");
            assertFalse(Files.exists(ownerPackage.resolve("files/partial-import.txt")),"A rejected ZIP stages no valid prefix entry");

            Captured imported=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),archive,"application/zip");
            assertEquals(201,imported.status,new String(imported.body(),StandardCharsets.UTF_8));
            assertEquals("private, no-store",imported.headers.get("Cache-Control"));
            JsonNode draft=ServerRuntime.JSON.readTree(imported.body());
            assertEquals("draft",draft.path("state").asText());
            assertEquals(2,draft.path("changedFiles").size());
            assertEquals("create",draft.path("changedFiles").path(0).path("operation").asText());
            assertEquals("files/a-imported.txt",draft.path("changedFiles").path(0).path("path").asText(),
                    "ZIP entries should produce a deterministic path-sorted Change Set diff");
            assertEquals("files/z-imported.txt",draft.path("changedFiles").path(1).path("path").asText());
            String id=draft.path("changeSetId").asText();
            assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig),
                    "Package ZIP import must not modify the globally owned Runtime Config");
            assertFalse(Files.exists(ownerPackage.resolve("files/a-imported.txt"))
                            ||Files.exists(ownerPackage.resolve("files/z-imported.txt")),
                    "Import creates an ordinary Change Set and must leave published Package files untouched");

            for(String action:List.of("validate","review")) {
                Captured response=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+id+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
                assertFalse(Files.exists(ownerPackage.resolve("files/a-imported.txt"))
                                ||Files.exists(ownerPackage.resolve("files/z-imported.txt")),
                        "Validation and review must not publish imported files");
                assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig),
                        "Package Change Set gates must not modify the global Runtime Config");
            }
            Captured published=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+id+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200,published.status,new String(published.body(),StandardCharsets.UTF_8));
            assertEquals("first\n",Files.readString(ownerPackage.resolve("files/a-imported.txt")));
            assertEquals("last\n",Files.readString(ownerPackage.resolve("files/z-imported.txt")));
            assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig));

            byte[] replacementArchive=packageZip("files/a-imported.txt","updated\n","files/new-imported.txt","new\n");
            Captured replacement=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),replacementArchive,"application/zip");
            assertEquals(201,replacement.status,new String(replacement.body(),StandardCharsets.UTF_8));
            JsonNode replacementDraft=ServerRuntime.JSON.readTree(replacement.body());
            assertEquals("replace",replacementDraft.path("changedFiles").path(0).path("operation").asText());
            assertEquals("files/a-imported.txt",replacementDraft.path("changedFiles").path(0).path("path").asText());
            assertEquals("create",replacementDraft.path("changedFiles").path(1).path("operation").asText());
            String replacementId=replacementDraft.path("changeSetId").asText();
            for(String action:List.of("validate","review")) {
                Captured response=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+replacementId+"/"+action,
                        Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
                assertEquals(200,response.status,new String(response.body(),StandardCharsets.UTF_8));
            }
            Captured replacementPublished=invokeAs(servlet,"alice","POST","/packages/alice/change-sets/"+replacementId+"/publish",
                    Collections.emptyMap(),"{}".getBytes(StandardCharsets.UTF_8));
            assertEquals(200,replacementPublished.status,new String(replacementPublished.body(),StandardCharsets.UTF_8));
            assertEquals("updated\n",Files.readString(ownerPackage.resolve("files/a-imported.txt")));
            assertEquals("new\n",Files.readString(ownerPackage.resolve("files/new-imported.txt")));
            assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig));

            byte[] malformedWorkbookArchive=packageZip("files/valid-prefix.txt","must-stay-private\n",
                    "testcase/malformed.xlsx","not an XLSX workbook");
            Captured malformedWorkbookImport=invokeAsContentType(servlet,"alice","POST","/packages/alice/change-sets/import",
                    Collections.emptyMap(),malformedWorkbookArchive,"application/zip");
            assertEquals(400,malformedWorkbookImport.status,new String(malformedWorkbookImport.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-PACKAGE-ZIP-INVALID",ServerRuntime.JSON.readTree(malformedWorkbookImport.body())
                    .path("error").path("code").asText());
            assertFalse(Files.exists(ownerPackage.resolve("files/valid-prefix.txt")),
                    "A valid ZIP prefix must not be staged when another entry contains a malformed workbook");
            assertFalse(Files.exists(ownerPackage.resolve("testcase/malformed.xlsx")),
                    "A malformed workbook must never become a published Package source");
            assertArrayEquals(runtimeConfigBefore,Files.readAllBytes(runtimeConfig));
        }
    }

    private static JsonNode assertChangeSetSchema(com.networknt.schema.JsonSchema schema,Captured response,int status) throws Exception {
        assertEquals(status,response.status,new String(response.body(),StandardCharsets.UTF_8));
        assertEquals("private, no-store",response.headers.get("Cache-Control"));
        JsonNode body=ServerRuntime.JSON.readTree(response.body());
        assertEquals("limits-test",body.path("requestId").asText());
        java.util.Set<com.networknt.schema.ValidationMessage> errors=schema.validate(body);
        assertTrue(errors.isEmpty(),errors.toString()+" response="+body);
        return body;
    }

    @Test void packageAuthoringSourceTimeoutIs504() throws Exception {
        assertAuthoringSourceTimeout(false,"package-authoring-timeout");
    }

    @Test void centralAuthoringSourceTimeoutIs504() throws Exception {
        assertAuthoringSourceTimeout(true,"central-authoring-timeout");
    }

    @Test void privilegedAuthoringSourceUnexpectedWorkerFailuresAreSafe500ForBothRoutes() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("authoring-failure-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        ServerRuntime.WorkerProcessLauncher brokenLauncher=builder->{throw new java.io.IOException("source-error-excerpt-canary");};
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"authoring-failure-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,brokenLauncher,workerLibraries("authoring-failure-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            Captured packageSource=invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                    Collections.singletonMap("type","manifest"),new byte[0]);
            Captured centralSource=invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/config.yaml"),new byte[0]);
            assertSafeSourceFailure(packageSource,500,"ATT-SERVER-REQUEST-FAILED");
            assertSafeSourceFailure(centralSource,500,"ATT-SERVER-REQUEST-FAILED");
            assertFalse(new String(packageSource.body(),StandardCharsets.UTF_8).contains("source-error-excerpt-canary"));
            assertFalse(new String(centralSource.body(),StandardCharsets.UTF_8).contains("source-error-excerpt-canary"));
        }
    }

    private void assertAuthoringSourceTimeout(boolean central,String dataName) throws Exception {
        Path packages=Files.createDirectories(temp.resolve(dataName+"-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,dataName+"-data",100,null,
                workerLibraries(dataName+"-libs"))) {
            addAuthoringUsers(runtime);ApiServlet servlet=servlet(runtime);
            java.util.List<ReentrantLock> held=new java.util.ArrayList<ReentrantLock>();
            for(Object worker:inspectionWorkers(runtime)) {
                Field lockField=worker.getClass().getDeclaredField("lock");lockField.setAccessible(true);
                ReentrantLock lock=(ReentrantLock)lockField.get(worker);lock.lock();held.add(lock);
            }
            Captured response;
            try {
                response=central
                        ?invokeAs(servlet,"admin","GET","/configuration/authoring-source",
                                Collections.singletonMap("path","config/config.yaml"),new byte[0])
                        :invokeAs(servlet,"alice","GET","/packages/alice/authoring-source",
                                Collections.singletonMap("type","manifest"),new byte[0]);
            } finally {for(int index=held.size()-1;index>=0;index--)held.get(index).unlock();}
            assertSafeSourceFailure(response,504,"ATT-SERVER-INSPECTION-TIMEOUT");
            stopInspectionProcesses(runtime);
        }
    }

    private ServerRuntime authoringRuntime(Path packages,Path ownerPackage,Path runtimeConfig,String dataName,int timeout,
                                            ServerRuntime.WorkerProcessLauncher launcher,Path libs) throws Exception {
        Path yaml=temp.resolve(dataName+".yaml");
        String config="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve(dataName))
                +"\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  authenticationRequired: true\n  auth: {mode: local}\n  centralConfigurationEditingEnabled: true\n"
                +"  packageConfiguration: {sourceEditingEnabled: true}\n  inspection:\n    enabled: true\n    maxConcurrent: 1\n"
                +"    queuedLimit: 0\n    timeoutMs: "+timeout+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 0\n"
                +"packages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(packages)+"]\n  entries:\n    alice: "
                +ServerRuntimeTestSupport.yaml(ownerPackage)+"\n";
        ServerConfig serverConfig=ServerConfig.load(Files.writeString(yaml,config));
        return new ServerRuntime(serverConfig,libs.toString(),launcher==null?ProcessBuilder::start:launcher);
    }

    @Test void centralAuthoringSourceWithholdsProtectedEmbeddedScalarsFromActiveRevision() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("central-active-secret-packages"));
        Path ownerPackage=createPackage(packages.resolve("alice"));
        Path runtimeConfig=sharedRuntimeConfig();
        String protectedSource=Files.readString(runtimeConfig)
                +"x-authoring-secret-fixture: |\n  classification: secret\n  value: central-active-route-canary\n";
        Files.writeString(runtimeConfig,protectedSource);
        try(ServerRuntime runtime=authoringRuntime(packages,ownerPackage,runtimeConfig,"central-active-secret-data",
                DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("central-active-secret-libs"))) {
            addAuthoringUsers(runtime);
            Captured central=invokeAs(servlet(runtime),"admin","GET","/configuration/authoring-source",
                    Collections.singletonMap("path","config/config.yaml"),new byte[0]);
            assertSafeSourceFailure(central,404,"ATT-SERVER-NOT-FOUND");
            assertFalse(new String(central.body(),StandardCharsets.UTF_8).contains("central-active-route-canary"));
        }
    }

    private static void addAuthoringUsers(ServerRuntime runtime) throws Exception {
        runtime.auth.createUser("alice","Alice","alice source access password","USER");
        runtime.auth.createUser("admin","Admin","admin source access password","ADMIN");
    }

    private static void assertSafeSourceFailure(Captured response,int status,String code) throws Exception {
        JsonNode body=ServerRuntime.JSON.readTree(response.body());
        assertEquals(status,response.status,new String(response.body(),StandardCharsets.UTF_8));
        assertEquals(code,body.path("error").path("code").asText(),body.toString());
        assertFalse(new String(response.body(),StandardCharsets.UTF_8).contains("schemaVersion"),
                "Source failures must not include source excerpts");
    }

    @Test void packageConfigurationByteLimitHasStructuredFieldsOnTheRealInspectionRoute() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("configuration-limit-packages"));createPackage(packages.resolve("p"));
        Path config=sharedRuntimeConfig();
        try(java.io.OutputStream out=Files.newOutputStream(config,java.nio.file.StandardOpenOption.APPEND)) {
            out.write(("#"+"x".repeat(1024*1024)+"\n").getBytes(StandardCharsets.UTF_8));
        }
        try(ServerRuntime runtime=runtime(packages,config,"configuration-limit-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("configuration-limit-libs"))) {
            Captured response=invoke(servlet(runtime),"GET","/packages/p/configuration/effective",Collections.singletonMap("environment","SIT"),new byte[0]);
            JsonNode diagnostic=ServerRuntime.JSON.readTree(response.body()).path("diagnostics").path(0);
            assertEquals(413,response.status,new String(response.body(),StandardCharsets.UTF_8));
            assertEquals("ATT-RESOURCE-LIMIT",diagnostic.path("code").asText());
            assertEquals("packageConfiguration.maxConfigBytes",diagnostic.path("limitName").asText(),diagnostic.toString());
            assertEquals(1024*1024,diagnostic.path("configuredLimit").asInt());
            assertEquals(1024*1024,diagnostic.path("limit").asInt());
            assertTrue(diagnostic.path("observed").asLong()>diagnostic.path("configuredLimit").asLong());
            assertEquals(diagnostic.path("observed").asLong(),diagnostic.path("actual").asLong());
            assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
            stopInspectionProcesses(runtime);
        }
    }

    @Test void resourcePageSizeOverOneHundredHasStructured400DiagnosticOnRealRoute() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("page-size-packages"));createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"page-size-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("page-size-libs"))) {
            Map<String,String> parameters=new HashMap<>();parameters.put("type","template");parameters.put("limit","101");
            Captured response=invoke(servlet(runtime),"GET","/packages/p/resources",parameters,new byte[0]);
            JsonNode diagnostic=ServerRuntime.JSON.readTree(response.body()).path("diagnostics").path(0);
            assertEquals(400,response.status);
            assertEquals("ATT-RESOURCE-LIMIT",diagnostic.path("code").asText());
            assertEquals("resourceInspector.maxPageSize",diagnostic.path("limitName").asText());
            assertEquals(100,diagnostic.path("configuredLimit").asInt());
            assertEquals(101,diagnostic.path("observed").asInt());
            assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
        }
    }

    @Test void artifactReadSlotExhaustionNamesItsOwnCapacityOnTheRealArtifactRoute() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("artifact-capacity-packages"));createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"artifact-capacity-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("artifact-capacity-libs"))) {
            String id="J0000000000000295";Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
            Files.writeString(output.resolve("case.log"),"approved");
            Job job=new Job(id,"run","p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
            job.status="PASS";job.exitCode=0;job.finishedAt=Instant.now();runtime.store.insert(job,"{}");
            ArtifactArchive.build(output,runtime.artifactArchivePath(id));
            Files.writeString(runtime.artifactArchivePath(id).resolveSibling("archive-status.json"),"{\"state\":\"READY\"}");
            int acquired=0;while(runtime.acquireArtifactReadSlot())acquired++;
            assertEquals(8,acquired);
            try {
                Captured response=invoke(servlet(runtime),"GET","/jobs/"+id+"/artifacts/view/case.log",Collections.emptyMap(),new byte[0]);
                JsonNode diagnostic=ServerRuntime.JSON.readTree(response.body()).path("diagnostics").path(0);
                assertEquals(503,response.status);
                assertEquals("ATT-SERVER-ARTIFACT-CAPACITY",diagnostic.path("code").asText());
                assertEquals("server.artifacts.readSlots",diagnostic.path("limitName").asText());
                assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
            } finally {for(int index=0;index<acquired;index++)runtime.releaseArtifactReadSlot();}
        }
    }

    @Test void artifactRouteReportsPendingArchivingFailedAndReadyStatesSeparately() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("archive-packages"));
        createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"archive-data",DEFAULT_INSPECTION_TIMEOUT_MS,null,workerLibraries("archive-libs"))) {
            ApiServlet servlet=servlet(runtime);
            assertArchiveState(servlet,runtime,"J0000000000000291","PENDING",false,"ATT-ARCHIVE-NOT-READY",null);
            assertArchiveState(servlet,runtime,"J0000000000000292","ARCHIVING",false,"ATT-ARCHIVE-NOT-READY",null);
            assertArchiveState(servlet,runtime,"J0000000000000293","FAILED",false,"ATT-SERVER-ARCHIVE-UNAVAILABLE","ARCHIVE_FAILED");
            Captured ready=assertArchiveState(servlet,runtime,"J0000000000000294","READY",true,null,null);
            assertEquals(200,ready.status);
            assertEquals("approved",new String(ready.body(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private Captured assertArchiveState(ApiServlet servlet,ServerRuntime runtime,String id,String state,boolean ready,
                                        String expectedCode,String expectedOutcome) throws Exception {
        Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
        Files.writeString(output.resolve("case.log"),"approved");
        Job job=new Job(id,"run","p","alice",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
        job.status="PASS";job.exitCode=0;job.finishedAt=Instant.now();runtime.store.insert(job,"{}");
        Path status=runtime.artifactArchivePath(id).resolveSibling("archive-status.json");
        if(ready) {
            ArtifactArchive.build(output,runtime.artifactArchivePath(id));
            Files.writeString(status,"{\"state\":\"READY\"}");
        } else if(!"PENDING".equals(state))Files.writeString(status,"{\"state\":\""+state+"\"}");
        Captured result=invoke(servlet,"GET","/jobs/"+id+"/artifacts/view/case.log",Collections.emptyMap(),new byte[0]);
        if(ready)return result;
        JsonNode error=ServerRuntime.JSON.readTree(result.body());
        JsonNode diagnostic=error.path("diagnostics").path(0);
        assertEquals(409,result.status);
        assertEquals(expectedCode,diagnostic.path("code").asText());
        assertEquals(state,diagnostic.path("archiveState").asText());
        if(expectedOutcome==null)assertFalse(diagnostic.has("outcome"));
        else assertEquals(expectedOutcome,diagnostic.path("outcome").asText());
        return result;
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,false,false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,0,
                ref->System.getenv("ATT_SECRET_"+ref),false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  int inspectionQueuedLimit) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,
                inspectionQueuedLimit,ref->System.getenv("ATT_SECRET_"+ref),false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  java.util.function.Function<String,String> secretResolver) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,0,secretResolver,false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,
                                  boolean authenticationRequired,boolean centralConfigurationEditingEnabled) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,0,
                ref->System.getenv("ATT_SECRET_"+ref),centralConfigurationEditingEnabled);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  int inspectionQueuedLimit,java.util.function.Function<String,String> secretResolver) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,
                inspectionQueuedLimit,secretResolver,false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  int inspectionQueuedLimit,java.util.function.Function<String,String> secretResolver,
                                  boolean centralConfigurationEditingEnabled) throws Exception {
        Path yaml=temp.resolve(dataName+".yaml");
        StringBuilder entries=new StringBuilder();
        try(java.util.stream.Stream<Path> children=Files.list(packages)) {
            children.filter(Files::isDirectory)
                    .filter(path->path.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                    .sorted().forEach(path->entries.append("    ").append(path.getFileName())
                    .append(": ").append(ServerRuntimeTestSupport.yaml(path)).append("\n"));
        }
        String config="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve(dataName))
                +"\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  maxRequestBytes: 1024\n  authenticationRequired: "+authenticationRequired
                +"\n  centralConfigurationEditingEnabled: "+centralConfigurationEditingEnabled
                +"\n  authorization:\n    allowAnonymousOperations: "+(!authenticationRequired)+"\n"
                +"  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: "+inspectionQueuedLimit+"\n    timeoutMs: "+timeout
                +"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 0\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(packages)
                +"\n  entries:\n"+entries;
        ServerConfig serverConfig=ServerConfig.load(Files.writeString(yaml,config));
        return new ServerRuntime(serverConfig,libs.toString(),launcher==null?ProcessBuilder::start:launcher,
                java.time.Clock.systemUTC(),(output,archive,validator)->ArtifactArchive.build(output,archive,validator,serverConfig.archiveCompression),
                null,secretResolver);
    }

    private Path createPackage(Path root) throws Exception {
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates"));
        Files.writeString(root.resolve("package.yaml"),"schemaVersion: att-package/v1\n");
        return root;
    }

    private Path sharedRuntimeConfig() throws Exception {
        Path root=Files.createDirectories(temp.resolve("shared-runtime-config/config"));
        return Files.writeString(root.resolve("config.yaml"),"schemaVersion: att-config/v2.15\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntimeoutMs: 10000\ntestcase: {root: testcase}\ntemplates: {root: templates}\n");
    }

    private Path writeTwoTargetRuntimeConfig(Path config, ApiPrometheusFixture fixture) throws Exception {
        Map<String,Object> profile=baseProfile("base","Base profile",List.of(Map.of("id","cpu","title","CPU","unit","percent",
                "kind","gauge","query","up{instance=\"{{instance}}\"}")));
        profile.put("params",Map.of("instance",Map.of("type","promLabelValue","default","sut-a")));
        profile.put("parameters",Map.of("instance","sut-a"));
        profile.put("overridableParams",List.of("instance"));
        return writeSutMetricsConfig(config,
                List.of(prometheusSource("prom","Prometheus",fixture.url(),List.of("alice"),List.of(),List.of(),null,Map.of(),Map.of())),
                List.of(),List.of(),List.of(profile),false);
    }

    private Map<String,Object> prometheusSource(String id,String name,String url,List<String> readers,List<String> roles,
                                                List<String> tenants,String defaultTenant,Map<String,Object> limits,
                                                Map<String,Object> auth) {
        Map<String,Object> source=new LinkedHashMap<>();
        source.put("schemaVersion","att-sut-metrics-source/v1");source.put("id",id);source.put("name",name);source.put("url",url);
        if(!auth.isEmpty())source.put("auth",auth);
        source.put("readers",readers);source.put("readRoles",roles);source.put("allowedTenants",tenants);
        if(defaultTenant!=null)source.put("defaultTenant",defaultTenant);
        if(!limits.isEmpty())source.put("limits",limits);
        return source;
    }

    private Map<String,Object> baseProfile(String id,String name,List<Map<String,Object>> metrics) {
        return new LinkedHashMap<>(Map.of("schemaVersion","att-sut-metrics-profile/v1","id",id,"name",name,
                "kind","base","category","app","metrics",metrics));
    }

    private Map<String,Object> flatProfile(String id,String name,List<String> metricSourceIds,List<Map<String,Object>> targets) {
        return Map.of("schemaVersion","att-sut-metrics-profile/v2","id",id,"name",name,
                "metricSourceIds",metricSourceIds,"groupIds",List.of(),
                "allowedBindings",Map.of("environments",List.of("SIT"),"targets",targets,"tenants",List.of(),"parameters",Map.of()),
                "budgets",Map.of("maxFetches",4,"maxObservations",512,"maxArtifactBytes",1048576));
    }

    private SutMetricsService.BusinessCallContext businessCallContext(ServerRuntime runtime,Path packageRoot) throws Exception {
        byte[] configBytes=Files.readAllBytes(runtime.config.runtimeConfigPath);
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(configBytes);
        StringBuilder runtimeRevision=new StringBuilder(64);
        for(byte value:digest)runtimeRevision.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        Path configRoot=ServerConfig.runtimeConfigRoot(runtime.config.runtimeConfigPath);
        att.config.FrameworkConfig framework=new att.config.FrameworkConfigLoader().load(
                runtime.config.runtimeConfigPath,configRoot,packageRoot,"SIT");
        return new SutMetricsService.BusinessCallContext("p",packageRoot,runtime.config.runtimeConfigPath,configRoot,"SIT",
                "a".repeat(64),runtimeRevision.toString(),Set.of(),List.of(),framework);
    }

    private Path writeSutMetricsConfig(Path config,List<Map<String,Object>> sources,List<Map<String,Object>> metricSources,
                                       List<Map<String,Object>> groups,List<Map<String,Object>> profiles,
                                       boolean includeOrdersDbHelper) throws Exception {
        Path configDirectory=config.getParent();
        String environment=includeOrdersDbHelper?"    dbhelpers: [config/dbhelpers/sit/orders.yaml]\n":"    {}\n";
        StringBuilder text=new StringBuilder("schemaVersion: att-config/v2.14\nenvironment: SIT\nenvironments:\n  SIT:\n")
                .append(environment).append("  UAT: {}\ntimeoutMs: 10000\ntestcase: {root: testcase}\ntemplates: {root: templates}\nsutMetrics:\n");
        appendYamlItems(text,"dataSources",sources);
        appendYamlItems(text,"metricSources",metricSources);
        appendYamlItems(text,"groups",groups);
        appendYamlItems(text,"profiles",profiles);
        Files.createDirectories(configDirectory);
        if(includeOrdersDbHelper) {
            Path helper=Files.createDirectories(configDirectory.resolve("dbhelpers/sit")).resolve("orders.yaml");
            Files.writeString(helper,sharedDbHelper("SUT Metrics API test helper"));
        }
        return Files.writeString(config,text.toString());
    }

    private void appendYamlItems(StringBuilder text,String name,List<Map<String,Object>> items) throws Exception {
        text.append("  ").append(name).append(":");
        if(items.isEmpty()) {text.append(" []\n");return;}
        text.append('\n');
        for(Map<String,Object> item:items)
            text.append("    - ").append(ServerRuntime.JSON.writeValueAsString(item)).append('\n');
    }

    private static String sharedDbHelper(String description) {
        return "schemaVersion: att-dbhelper/v2.6\nid: orders\nname: Orders\ndescription: " + description + "\n"
                + "connection:\n  url: 'jdbc:postgresql://localhost/orders'\n  username: test\n  password: test\n"
                + "  driverClass: org.postgresql.Driver\n  readOnly: false\n  isolation: readCommitted\n"
                + "  properties: {sslMode: disable}\nstatement: {timeoutSeconds: 30}\n"
                + "transaction: {scope: case, onEnd: rollback}\nevidence: {sql: full, parameters: masked}\n";
    }

    private Path workerLibraries(String name) throws Exception {
        Path libs=Files.createDirectory(temp.resolve(name));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        return libs;
    }

    private ApiServlet servlet(ServerRuntime runtime) throws Exception {
        ServletContext context=proxy(ServletContext.class,(self,method,args)->{
            if("getAttribute".equals(method.getName()))return runtime;
            return defaultValue(method.getReturnType());
        });
        ServletConfig config=proxy(ServletConfig.class,(self,method,args)->"getServletContext".equals(method.getName())?context:defaultValue(method.getReturnType()));
        ApiServlet servlet=new ApiServlet();servlet.init(config);return servlet;
    }

    private Captured invoke(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,"alice",null);
    }

    private Captured invokeAs(ApiServlet servlet,String login,String method,String path,
                              Map<String,String> parameters,byte[] body) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,login,null,false);
    }

    private Captured invokeAs(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body,String actor) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,actor,null,false);
    }

    private Captured invokeAsAdmin(ApiServlet servlet,String login,String method,String path,
                                   Map<String,String> parameters,byte[] body) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,login,null,true);
    }

    private Captured invokeAs(ApiServlet servlet,String login,String method,String path,Map<String,String> parameters,
                              byte[] body,boolean admin) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,login,null,admin);
    }

    private Captured invokeAs(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body,
                              String login,Set<String> roles) throws Exception {
        return invokeAs(servlet,method,path,parameters,body,login,roles,false);
    }

    private Captured invokeAs(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body,
                              String login,Set<String> roles,boolean admin) throws Exception {
        return invokeAsContentType(servlet,method,path,parameters,body,login,roles,admin,"application/json");
    }

    private Captured invokeAsContentType(ApiServlet servlet,String login,String method,String path,
                                         Map<String,String> parameters,byte[] body,String contentType) throws Exception {
        return invokeAsContentType(servlet,method,path,parameters,body,login,null,false,contentType);
    }

    private Captured invokeAsContentType(ApiServlet servlet,String method,String path,Map<String,String> parameters,
                                         byte[] body,String login,Set<String> roles,boolean admin,String contentType) throws Exception {
        ServletContext context=servlet.getServletContext();
        HttpServletRequest request=proxy(HttpServletRequest.class,(self,called,args)->{
            switch(called.getName()) {
                case "getServletContext": return context;
                case "getAttribute": return AuthService.IDENTITY.equals(args[0])?login:null;
                case "getMethod": return method;
                case "getPathInfo": return path;
                case "getContextPath": return "";
                case "getParameter": return parameters.get(args[0]);
                case "getHeader": return "X-Request-ID".equals(args[0])?"limits-test":null;
                case "getContentType": return contentType;
                case "getUserPrincipal": return login==null?null:(Principal)()->login;
                case "isUserInRole": return roles!=null?roles.contains(args[0])
                        :admin?runtimeAdminRole(servlet,args[0]):runtimeRole(servlet,args[0]);
                case "getInputStream": return input(body);
                case "getRemoteAddr": return "127.0.0.1";
                default: return defaultValue(called.getReturnType());
            }
        });
        Captured response=new Captured();
        HttpServletResponse httpResponse=proxy(HttpServletResponse.class,(self,called,args)->{
            if("setStatus".equals(called.getName()))response.status=(Integer)args[0];
            if("getOutputStream".equals(called.getName()))return response.stream;
            if("setHeader".equals(called.getName()))response.headers.put(String.valueOf(args[0]),String.valueOf(args[1]));
            return defaultValue(called.getReturnType());
        });
        servlet.service(request,httpResponse);
        return response;
    }

    private static byte[] packageZip(String... pathsAndContents) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            for(int index=0;index<pathsAndContents.length;index+=2) {
                zip.putNextEntry(new java.util.zip.ZipEntry(pathsAndContents[index]));
                zip.write(pathsAndContents[index+1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private boolean runtimeRole(ApiServlet servlet,Object role) {
        try {
            Field field=ApiServlet.class.getDeclaredField("runtime");field.setAccessible(true);
            ServerRuntime runtime=(ServerRuntime)field.get(servlet);
            return runtime.config.authorization.userRole.equals(role);
        } catch(Exception failure){throw new IllegalStateException(failure);}
    }

    private boolean runtimeAdminRole(ApiServlet servlet,Object role) {
        try {
            Field field=ApiServlet.class.getDeclaredField("runtime");field.setAccessible(true);
            ServerRuntime runtime=(ServerRuntime)field.get(servlet);
            return runtime.config.authorization.adminRole.equals(role);
        } catch(Exception failure){throw new IllegalStateException(failure);}
    }

    private static ServletInputStream input(byte[] body) {
        InputStream input=new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() throws java.io.IOException {return input.read();}
            @Override public boolean isFinished() {try{return input.available()==0;}catch(Exception unavailable){return true;}}
            @Override public boolean isReady(){return true;}
            @Override public void setReadListener(ReadListener listener) { }
        };
    }

    private static Semaphore semaphore(ServerRuntime runtime,String fieldName) throws Exception {
        Field field=ServerRuntime.class.getDeclaredField(fieldName);field.setAccessible(true);return (Semaphore)field.get(runtime);
    }

    private static java.util.List<?> inspectionWorkers(ServerRuntime runtime) throws Exception {
        Field field=ServerRuntime.class.getDeclaredField("inspectionWorkerPool");field.setAccessible(true);
        return (java.util.List<?>)field.get(runtime);
    }

    private static ReentrantLock inspectionWorkerLock(ServerRuntime runtime) throws Exception {
        Object worker=inspectionWorkers(runtime).get(0);Field field=worker.getClass().getDeclaredField("lock");field.setAccessible(true);
        return (ReentrantLock)field.get(worker);
    }

    private static java.util.concurrent.ThreadPoolExecutor validationExecutor(ServerRuntime runtime) throws Exception {
        Field field=ServerRuntime.class.getDeclaredField("validationExecutor");field.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor)field.get(runtime);
    }

    private static void stopInspectionProcesses(ServerRuntime runtime) throws Exception {
        for(Object worker:inspectionWorkers(runtime)) {
            Field processField=worker.getClass().getDeclaredField("process");processField.setAccessible(true);
            Process process=(Process)processField.get(worker);
            if(process!=null&&process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase().contains("win");}

    private static Object defaultValue(Class<?> type) {
        if(type==boolean.class)return Boolean.FALSE;
        if(type==int.class)return Integer.valueOf(0);
        if(type==long.class)return Long.valueOf(0L);
        if(type==double.class)return Double.valueOf(0.0d);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }

    private static final class Captured {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Map<String,String> headers=new HashMap<String,String>();
        int status=200;
        final ServletOutputStream stream=new ServletOutputStream() {
            @Override public boolean isReady(){return true;}
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int value){output.write(value);}
            @Override public void write(byte[] bytes,int offset,int length){output.write(bytes,offset,length);}
        };
        byte[] body(){return output.toByteArray();}
        String bodyText(){return new String(body(),StandardCharsets.UTF_8);}
    }

    private static final class ApiPrometheusFixture implements AutoCloseable {
        final AtomicInteger requests=new AtomicInteger();
        final AtomicReference<String> tenant=new AtomicReference<>(),query=new AtomicReference<>(),authorization=new AtomicReference<>();
        private final HttpServer server;
        private final ExecutorService executor=Executors.newCachedThreadPool();
        private final long delayMs;
        private final int responseBytes;
        private final Double rangeValue;
        private final String unavailableInstance;
        ApiPrometheusFixture(long delayMs,int responseBytes) throws Exception {
            this(delayMs,responseBytes,null,null);
        }
        ApiPrometheusFixture(long delayMs,int responseBytes,double rangeValue) throws Exception {
            this(delayMs,responseBytes,Double.valueOf(rangeValue),null);
        }
        ApiPrometheusFixture(long delayMs,int responseBytes,double rangeValue,String unavailableInstance) throws Exception {
            this(delayMs,responseBytes,Double.valueOf(rangeValue),unavailableInstance);
        }
        private ApiPrometheusFixture(long delayMs,int responseBytes,Double rangeValue,String unavailableInstance) throws Exception {
            this.delayMs=delayMs;this.responseBytes=responseBytes;this.rangeValue=rangeValue;this.unavailableInstance=unavailableInstance;
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/api/v1/query_range",this::respond);server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        private void respond(HttpExchange exchange) {
            requests.incrementAndGet();tenant.set(exchange.getRequestHeaders().getFirst("X-Scope-OrgID"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String raw=exchange.getRequestURI().getRawQuery();
            query.set(java.util.Arrays.stream(raw.split("&")).filter(part->part.startsWith("query="))
                    .map(part->java.net.URLDecoder.decode(part.substring("query=".length()),StandardCharsets.UTF_8)).findFirst().orElse(""));
            try {
                if(delayMs>0)Thread.sleep(delayMs);
                long timestamp=Instant.now().getEpochSecond()-1;
                String queryText=queryParameter(raw,"query");
                if(unavailableInstance!=null&&queryText.contains(unavailableInstance)) {
                    exchange.sendResponseHeaders(503,-1);
                    return;
                }
                byte[] body;
                if(responseBytes>0)body=new byte[responseBytes];
                else if(rangeValue!=null) {
                    String startText=queryParameter(raw,"start"),endText=queryParameter(raw,"end"),stepText=queryParameter(raw,"step");
                    long start=Instant.parse(startText).getEpochSecond(),end=Instant.parse(endText).getEpochSecond();
                    long step=Long.parseLong(stepText.substring(0,stepText.length()-1));
                    String instance=queryText.contains("sut-a")?"sut-a":"sut-b";
                    double value="sut-a".equals(instance)?rangeValue:90.0;
                    StringBuilder values=new StringBuilder();
                    for(long at=start;at<=end;at+=step) {
                        if(values.length()>0)values.append(',');
                        values.append('[').append(at).append(",\"").append(value).append("\"]");
                    }
                    body=("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"instance\":\""+instance+"\"},\"values\":["
                            +values+"]}]}}").getBytes(StandardCharsets.UTF_8);
                } else body=("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"instance\":\"sut-1\"},\"values\":[["
                        +timestamp+",\"1.0\"]]}]}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            } catch(Exception ignored) { }
            finally {exchange.close();}
        }
        private static String queryParameter(String raw,String name) {
            return java.util.Arrays.stream(raw.split("&")).filter(part->part.startsWith(name+"="))
                    .map(part->java.net.URLDecoder.decode(part.substring(name.length()+1),StandardCharsets.UTF_8))
                    .findFirst().orElseThrow();
        }
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
}
