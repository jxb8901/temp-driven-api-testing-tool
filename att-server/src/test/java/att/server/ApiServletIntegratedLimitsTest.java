package att.server;

import att.server.api.WorkerRequest;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

class ApiServletIntegratedLimitsTest {
    private static final int DEFAULT_INSPECTION_TIMEOUT_MS=30_000;
    @TempDir Path temp;

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
                    new att.server.api.source.SourceManagementContracts.ImpactAcknowledgement(freshness,"alice",
                            ConfigImpactAnalyzer.acknowledgementDigest(freshness,"alice"));

            Path manifest=packageRoot.resolve("package.yaml");
            Files.writeString(manifest,Files.readString(manifest)+"# concurrent manifest change\n");
            Captured response=invokeAsAdmin(servlet(runtime),"alice","POST",
                    "/configuration/change-sets/"+id+"/publish",Map.of(),ServerRuntime.JSON.writeValueAsBytes(Map.of(
                            "impactAcknowledgement",Map.of("freshnessFingerprint",freshness,"actor","alice",
                                    "acknowledgementDigest",acknowledgement.acknowledgementDigest))));
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
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,
                                  boolean authenticationRequired,boolean centralConfigurationEditingEnabled) throws Exception {
        Path yaml=temp.resolve(dataName+".yaml");
        StringBuilder entries=new StringBuilder();
        try(java.util.stream.Stream<Path> children=Files.list(packages)) {
            children.filter(Files::isDirectory).sorted().forEach(path->entries.append("    ").append(path.getFileName())
                    .append(": ").append(ServerRuntimeTestSupport.yaml(path)).append("\n"));
        }
        String config="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve(dataName))
                +"\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  maxRequestBytes: 1024\n  authenticationRequired: "+authenticationRequired
                +"\n  centralConfigurationEditingEnabled: "+centralConfigurationEditingEnabled
                +"\n  authorization:\n    allowAnonymousOperations: "+(!authenticationRequired)+"\n"
                +"  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 0\n    timeoutMs: "+timeout
                +"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 0\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(packages)
                +"\n  entries:\n"+entries;
        ServerConfig serverConfig=ServerConfig.load(Files.writeString(yaml,config));
        return new ServerRuntime(serverConfig,libs.toString(),launcher==null?ProcessBuilder::start:launcher);
    }

    private Path createPackage(Path root) throws Exception {
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates"));
        Files.writeString(root.resolve("package.yaml"),"schemaVersion: att-package/v1\n");
        return root;
    }

    private Path sharedRuntimeConfig() throws Exception {
        Path root=Files.createDirectories(temp.resolve("shared-runtime-config/config"));
        return Files.writeString(root.resolve("config.yaml"),"schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntimeoutMs: 10000\ntestcase: {root: testcase}\ntemplates: {root: templates}\n");
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
        return invokeAs(servlet,"alice",method,path,parameters,body);
    }

    private Captured invokeAsAdmin(ApiServlet servlet,String login,String method,String path,
                                   Map<String,String> parameters,byte[] body) throws Exception {
        return invokeAs(servlet,login,method,path,parameters,body,true);
    }

    private Captured invokeAs(ApiServlet servlet,String login,String method,String path,Map<String,String> parameters,byte[] body) throws Exception {
        return invokeAs(servlet,login,method,path,parameters,body,false);
    }

    private Captured invokeAs(ApiServlet servlet,String login,String method,String path,Map<String,String> parameters,
                              byte[] body,boolean admin) throws Exception {
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
                case "getContentType": return "application/json";
                case "getUserPrincipal": return (Principal)()->login;
                case "isUserInRole": return admin?runtimeAdminRole(servlet,args[0]):runtimeRole(servlet,args[0]);
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
    }
}
