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

    @Test void sutMetricsRoutesEnforceAdminWritesBoundBodiesAndFilterSourcesForReaders() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("sut-metrics-route-packages"));
        createPackage(packages.resolve("p"));
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"sut-metrics-route-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,workerLibraries("sut-metrics-route-libs"),true)) {
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
            assertEquals(201,adminWrite.status,new String(adminWrite.body(),StandardCharsets.UTF_8));
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
            assertEquals(201,roleWrite.status,new String(roleWrite.body(),StandardCharsets.UTF_8));

            Captured otherReader=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],"bob",Set.of(userRole));
            JsonNode roleItem=ServerRuntime.JSON.readTree(otherReader.body()).path("items").path(0);
            assertEquals(200,otherReader.status);
            assertEquals(1,ServerRuntime.JSON.readTree(otherReader.body()).path("items").size());
            assertEquals("role-prom",roleItem.path("id").asText());
            Captured adminList=invokeAs(servlet,"GET","/sut-metrics/sources",Collections.emptyMap(),new byte[0],"admin",Set.of(adminRole));
            JsonNode adminItem=ServerRuntime.JSON.readTree(adminList.body()).path("items").path(0);
            assertEquals("http://127.0.0.1:9090",adminItem.path("url").asText());
            assertEquals("PROM_TOKEN",adminItem.path("auth").path("secretRef").asText());

            runtime.sutMetrics.upsertProfile(Map.of("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile",
                    "kind","base","category","app","metrics",List.of(Map.of("id","requests","title","Requests","unit","count",
                            "kind","gauge","query","up"))));
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
        try(ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"sut-metrics-job-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                null,workerLibraries("sut-metrics-job-libs"),true)) {
            runtime.sutMetrics.upsertSource(Map.of("schemaVersion","att-sut-metrics-source/v1","id","prom","name","Prometheus",
                    "url","http://127.0.0.1:9090","readers",List.of("alice"),"readRoles",List.of(),"allowedTenants",List.of("tenant-a"),
                    "defaultTenant","tenant-a"));
            runtime.sutMetrics.upsertProfile(Map.of("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile",
                    "kind","base","category","app","metrics",List.of(Map.of("id","requests","title","Requests","unit","count",
                            "kind","gauge","query","up"))));
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
                ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"sut-metrics-live-api-"+name,DEFAULT_INSPECTION_TIMEOUT_MS,
                        null,workerLibraries("sut-metrics-live-api-"+name+"-libs"),true)) {
                runtime.sutMetrics.upsertSource(Map.of("schemaVersion","att-sut-metrics-source/v1","id","prom","name","Prometheus",
                        "url",fixture.url(),"readers",List.of(),"readRoles",List.of("USER"),"allowedTenants",List.of("tenant-a"),
                        "defaultTenant","tenant-a","limits",entry.getValue()));
                runtime.sutMetrics.upsertProfile(Map.of("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile",
                        "kind","base","category","app","metrics",List.of(Map.of("id","requests","title","Requests","unit","count",
                                "kind","gauge","query","up"))));
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
            ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"sut-metrics-live-auth-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                    null,workerLibraries("sut-metrics-live-auth-libs"),true,ref->"PROM_TOKEN".equals(ref)?"api-test-token":"wrong-ref")) {
            runtime.sutMetrics.upsertSource(Map.of("schemaVersion","att-sut-metrics-source/v1","id","prom","name","Authenticated Prometheus",
                    "url",fixture.url(),"auth",Map.of("mode","BEARER","secretRef","PROM_TOKEN"),"readers",List.of("alice"),
                    "readRoles",List.of("USER"),"allowedTenants",List.of("tenant-a"),"defaultTenant","tenant-a"));
            runtime.sutMetrics.upsertProfile(Map.of("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile",
                    "kind","base","category","app","metrics",List.of(Map.of("id","requests","title","Requests","unit","count",
                            "kind","gauge","query","up"))));
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
            ServerRuntime runtime=runtime(packages,sharedRuntimeConfig(),"sut-metrics-live-capacity-data",DEFAULT_INSPECTION_TIMEOUT_MS,
                    null,workerLibraries("sut-metrics-live-capacity-libs"),true,ref->null)) {
            runtime.sutMetrics.upsertSource(Map.of("schemaVersion","att-sut-metrics-source/v1","id","prom","name","Prometheus",
                    "url",fixture.url(),"readers",List.of("alice"),"readRoles",List.of("USER"),"limits",Map.of("requestTimeoutMs",60000)));
            runtime.sutMetrics.upsertProfile(Map.of("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile",
                    "kind","base","category","app","metrics",List.of(Map.of("id","requests","title","Requests","unit","count",
                            "kind","gauge","query","up"))));
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
        Path jobDir=runtime.config.dataDir.resolve("jobs").resolve(id);
        Files.createDirectories(jobDir.resolve("output"));
        Job job=new Job(id,"load","p",owner,new WorkerRequest(),new JobEvents(jobDir.resolve("events.jsonl"),100));
        job.status="PASS";job.exitCode=0;job.finishedAt=Instant.now();
        runtime.store.insert(job,"{}");
        runtime.sutMetrics.writeSnapshot(id,snapshot);
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
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,false);
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired) throws Exception {
        return runtime(packages,runtimeConfig,dataName,timeout,launcher,libs,authenticationRequired,
                ref->System.getenv("ATT_SECRET_"+ref));
    }

    private ServerRuntime runtime(Path packages,Path runtimeConfig,String dataName,int timeout,
                                  ServerRuntime.WorkerProcessLauncher launcher,Path libs,boolean authenticationRequired,
                                  java.util.function.Function<String,String> secretResolver) throws Exception {
        Path yaml=temp.resolve(dataName+".yaml");
        StringBuilder entries=new StringBuilder();
        try(java.util.stream.Stream<Path> children=Files.list(packages)) {
            children.filter(Files::isDirectory).sorted().forEach(path->entries.append("    ").append(path.getFileName())
                    .append(": ").append(ServerRuntimeTestSupport.yaml(path)).append("\n"));
        }
        String config="server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve(dataName))
                +"\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  maxRequestBytes: 1024\n  authenticationRequired: "+authenticationRequired+"\n  authorization:\n    allowAnonymousOperations: "+(!authenticationRequired)+"\n"
                +"  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 0\n    timeoutMs: "+timeout
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
        return invokeAs(servlet,method,path,parameters,body,"alice",null);
    }

    private Captured invokeAs(ApiServlet servlet,String method,String path,Map<String,String> parameters,byte[] body,
                              String login,Set<String> roles) throws Exception {
        ServletContext context=servlet.getServletContext();
        HttpServletRequest request=proxy(HttpServletRequest.class,(self,called,args)->{
            switch(called.getName()) {
                case "getServletContext": return context;
                case "getMethod": return method;
                case "getPathInfo": return path;
                case "getContextPath": return "";
                case "getParameter": return parameters.get(args[0]);
                case "getHeader": return "X-Request-ID".equals(args[0])?"limits-test":null;
                case "getContentType": return "application/json";
                case "getUserPrincipal": return login==null?null:(Principal)()->login;
                case "isUserInRole": return roles==null?runtimeRole(servlet,args[0]):roles.contains(args[0]);
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
        String bodyText(){return new String(body(),StandardCharsets.UTF_8);}
    }

    private static final class ApiPrometheusFixture implements AutoCloseable {
        final AtomicInteger requests=new AtomicInteger();
        final AtomicReference<String> tenant=new AtomicReference<>(),query=new AtomicReference<>(),authorization=new AtomicReference<>();
        private final HttpServer server;
        private final ExecutorService executor=Executors.newCachedThreadPool();
        private final long delayMs;
        private final int responseBytes;
        ApiPrometheusFixture(long delayMs,int responseBytes) throws Exception {
            this.delayMs=delayMs;this.responseBytes=responseBytes;
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
                byte[] body=responseBytes>0?new byte[responseBytes]:
                        ("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"instance\":\"sut-1\"},\"values\":[["
                                +timestamp+",\"1.0\"]]}]}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            } catch(Exception ignored) { }
            finally {exchange.close();}
        }
        @Override public void close(){server.stop(0);executor.shutdownNow();}
    }
}
