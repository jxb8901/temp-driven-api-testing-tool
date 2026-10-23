package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded inspector-worker cases. A separate class so Surefire can run them on their own JVM. */
class ServerRuntimeInspectionTest {
    @TempDir Path temp;

    @Test void listsPackageResourcesThroughTheBoundedInspectorWorkerAndSignsPaginationCursors() throws Exception {
        Path packageRoot=Path.of("").toRealPath().resolve("packages/default");Path allowed=packageRoot;
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("inspection-runtime-server.yaml");
        Files.writeString(configFile,"server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("inspection-runtime-data"))+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)+"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers: {}\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(allowed)+"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("inspection-WEB-INF-lib"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")&&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        AtomicInteger inspectorStarts=new AtomicInteger();
        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),builder->{
            inspectorStarts.incrementAndGet();
            builder.environment().put("ORDERS_DB_USERNAME","inspection-test");builder.environment().put("ORDERS_DB_PASSWORD","inspection-test");
            builder.environment().put("PAYMENT_MQ_USERNAME","inspection-test");builder.environment().put("PAYMENT_MQ_PASSWORD","inspection-test");
            return builder.start();
        });
        try {
            Map<String,Object> first=runtime.inspectResource("p","list","template",null,null,1,null,"name","asc",null,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> firstItems=(List<Map<String,Object>>)first.get("items");
            assertEquals(1,firstItems.size());assertNotNull(first.get("nextCursor"));
            Map<String,Object> second=runtime.inspectResource("p","list","template",null,null,1,String.valueOf(first.get("nextCursor")),"name","asc",null,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> secondItems=(List<Map<String,Object>>)second.get("items");
            assertEquals(1,secondItems.size());assertNotEquals(firstItems.get(0).get("resourceId"),secondItems.get(0).get("resourceId"));
            String resourceId=String.valueOf(firstItems.get(0).get("resourceId"));
            runtime.inspectResource("p","detail","template",resourceId,null,1,null,"alice");
            runtime.inspectResource("p","source","template",resourceId,null,1,null,"alice");
            assertThrows(IllegalArgumentException.class,()->runtime.inspectResource("p","list","template",null,null,1,String.valueOf(first.get("nextCursor"))+"x","alice"));
            assertThrows(IllegalArgumentException.class,()->runtime.inspectResource("p","list","template",null,null,1,String.valueOf(first.get("nextCursor")),"bob"));
            assertThrows(IllegalArgumentException.class,()->runtime.inspectResource("p","list","template",null,null,1,String.valueOf(first.get("nextCursor")),"name","desc",null,"alice"));
            assertEquals(1,inspectorStarts.get(),"pagination, detail, and source requests should reuse the bounded inspection Worker process");
        } finally {runtime.close();}
    }

    @Test void pagesTestdataRecordsThroughTheBoundedInspectorWorker() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("testdata-inspection-packages"));
        Path packageRoot=Files.createDirectories(allowed.resolve("p"));
        Path descriptor=Files.createDirectories(packageRoot.resolve("testdata")).resolve("accounts.yaml");
        Files.writeString(descriptor,"schemaVersion: att-testdata/v1.0\nid: accounts\nselection: {strategy: sequential}\nrecords:\n"
                +"  - {accountId: A-1001, status: ACTIVE}\n  - {accountId: A-1002, status: CLOSED}\n");
        Path runtimeConfig=Files.createDirectories(temp.resolve("testdata-inspection-runtime/config")).resolve("config.yaml");
        Files.createDirectories(packageRoot.resolve("templates"));
        Files.createDirectories(packageRoot.resolve("testcase"));
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments:\n  SIT:\n    testdata: [testdata/accounts.yaml]\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("testdata-inspection-server.yaml");
        Files.writeString(configFile,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)
                +"\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("testdata-inspection-data"))
                +"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers: {}\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(allowed)
                +"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n");
        Path libs=Files.createDirectory(temp.resolve("testdata-inspection-WEB-INF-lib"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        AtomicInteger inspectorStarts=new AtomicInteger();
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(configFile),libs.toString(),builder->{
            inspectorStarts.incrementAndGet();return builder.start();
        });
        try {
            Map<String,Object> first=runtime.inspectTestdataRecords("p","accounts","SIT",0,1,false,"alice");
            assertEquals(2,((Number)first.get("total")).intValue());assertEquals(1,first.get("nextOffset"));
            @SuppressWarnings("unchecked") List<Map<String,Object>> firstRecords=(List<Map<String,Object>>)first.get("records");
            @SuppressWarnings("unchecked") Map<String,Object> firstValue=(Map<String,Object>)firstRecords.get(0).get("value");
            assertEquals("A-1001",firstValue.get("accountId"));
            Map<String,Object> second=runtime.inspectTestdataRecords("p","accounts","SIT",1,1,false,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> secondRecords=(List<Map<String,Object>>)second.get("records");
            @SuppressWarnings("unchecked") Map<String,Object> secondValue=(Map<String,Object>)secondRecords.get(0).get("value");
            assertEquals("A-1002",secondValue.get("accountId"));
            assertEquals(1,inspectorStarts.get(),"Testdata pages should reuse the bounded inspection Worker process");
            assertFalse(first.toString().contains(packageRoot.toString()));
        } finally {runtime.close();}
    }

    @Test void returnsDeclaredEffectiveAndComparisonConfigurationThroughTheBoundedInspectorWorker() throws Exception {
        Path packageRoot=Path.of("").toRealPath().resolve("packages/default");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name"," ").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("configuration-inspection-server.yaml");
        Path sharedRuntimeConfig=Path.of("config/config.yaml").toRealPath();
        Files.writeString(configFile,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(sharedRuntimeConfig)+"\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("configuration-inspection-data"))+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)+"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers: {}\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("configuration-inspection-WEB-INF-lib"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")&&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),builder->{
            builder.environment().put("ORDERS_DB_USERNAME","inspection-test-user");builder.environment().put("ORDERS_DB_PASSWORD","inspection-test-password");
            builder.environment().put("PAYMENT_MQ_USERNAME","inspection-test-user");builder.environment().put("PAYMENT_MQ_PASSWORD","inspection-test-password");
            return builder.start();
        });
        try {
            Map<String,Object> sharedDeclared=runtime.inspectConfiguration("","declared",null,null,"alice");
            assertEquals("declared",sharedDeclared.get("state"));
            assertEquals(runtime.inspectConfiguration("p","declared",null,null,"alice"),sharedDeclared);
            Map<String,Object> sharedSit=runtime.inspectConfiguration("","effective","SIT",null,"alice");
            assertNotEquals("invalid",sharedSit.get("state"));
            assertFalse(sharedSit.toString().contains("Testdata descriptors"));
            Map<String,Object> sharedCompare=runtime.inspectConfiguration("","compare","SIT","UAT","dbhelpers",0,50,"alice");
            assertEquals("degraded",sharedCompare.get("state"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.inspectConfiguration("","testdata-records","SIT",null,"accounts",0,50,"alice"));
            Map<String,Object> declared=runtime.inspectConfiguration("p","declared",null,null,"alice");
            assertEquals("declared",declared.get("state"));
            assertTrue(declared.toString().contains("SIT"));assertTrue(declared.toString().contains("UAT"));
            Map<String,Object> effective=runtime.inspectConfiguration("p","effective","SIT",null,"alice");
            assertEquals("degraded",effective.get("state"),effective.toString());assertEquals("SIT",effective.get("environment"));
            assertEquals("not-validated",effective.get("executionReadiness"));
            assertTrue(effective.toString().contains("ORDERS_DB_PASSWORD"));
            assertTrue(effective.toString().contains("config/dbhelpers/sit/orders.yaml"),effective.toString());
            Map<String,Object> defaultEffective=runtime.inspectConfiguration("p","effective",null,null,"globals",0,50,"alice");
            assertEquals("degraded",defaultEffective.get("state"),defaultEffective.toString());assertEquals("SIT",defaultEffective.get("environment"));
            Map<String,Object> comparison=runtime.inspectConfiguration("p","compare","SIT","UAT","dbhelpers",0,50,"alice");
            assertEquals("degraded",comparison.get("state"));
            assertEquals("not-validated",comparison.get("executionReadiness"));
            @SuppressWarnings("unchecked") List<Map<String,Object>> fields=(List<Map<String,Object>>)comparison.get("fields");
            Map<String,Object> visibleUrl=fields.stream().filter(item->"dbhelpers.orders.url".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
            assertEquals("changed",visibleUrl.get("change"));
            Map<String,Object> comparisonPage=runtime.inspectConfiguration("p","compare","SIT","UAT","dbhelpers",0,1,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> firstComparisonPage=(List<Map<String,Object>>)comparisonPage.get("fields");
            assertEquals(1,firstComparisonPage.size());assertTrue(((Number)comparisonPage.get("total")).intValue()>1);
            String publicData=declared+" "+effective+" "+comparison;
            assertTrue(publicData.contains("jdbc:postgresql://sit-db.example.internal:5432/orders"));
            assertTrue(publicData.contains("jdbc:postgresql://uat-db.example.internal:5432/orders"));
            assertFalse(publicData.contains("inspection-test-password"));assertFalse(publicData.contains("inspection-test-user"));
            assertFalse(publicData.contains(packageRoot.toString()));
            assertFalse(publicData.contains(sharedRuntimeConfig.toString()));
        } finally {runtime.close();}
    }

    @Test void validatesDebugDraftsWithoutDisclosingDefaultsAndRejectsStalePrincipalBoundSubmissions() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("debug-draft-packages"));Path packageRoot=Files.createDirectory(allowed.resolve("p"));
        Path templates=Files.createDirectories(packageRoot.resolve("templates/FORM"));Files.createDirectories(packageRoot.resolve("testcase"));
        Path runtimeConfig=temp.resolve("debug-draft-runtime/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Files.writeString(templates.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: debug form test\nactions:\n"
                +"  log:\n    type: log\n    message: '${EXEC.INPUT.value}'\n");
        Path debugSidecar=templates.resolve("debug.yaml");Files.writeString(debugSidecar,"schemaVersion: att-debug/v1.2\ninputs:\n"
                +"  value: safe\n  payload:\n    account: 'customer account 123456789'\n    message: 'temporary credential violet-123'\n");
        byte[] originalSidecar=Files.readAllBytes(debugSidecar);
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("debug-draft-server.yaml");Files.writeString(configFile,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("debug-draft-data"))+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(allowed)+"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n");
        Path libs=Files.createDirectory(temp.resolve("debug-draft-WEB-INF-lib"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")
                    &&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(configFile),libs.toString());
        try {
            Map<String,Object> page=runtime.inspectResource("p","list","template",null,null,10,null,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
            String resourceId=String.valueOf(items.get(0).get("resourceId"));
            Map<String,Object> form=runtime.inspectDebugForm("p","template",resourceId,null,"alice");
            assertFalse(form.toString().contains("123456789"));assertFalse(form.toString().contains("violet-123"));
            assertFalse(form.containsKey("normalizedInput"));
            com.fasterxml.jackson.databind.node.ObjectNode body=ServerRuntime.JSON.createObjectNode();body.put("packageId","p");
            body.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","FORM")));
            body.set("input",ServerRuntime.JSON.valueToTree(form.get("input")));
            Map<String,Object> draft=runtime.createDebugDraft(body,"alice");String draftId=String.valueOf(draft.get("draftId"));
            assertFalse(draft.toString().contains("123456789"));assertFalse(draft.toString().contains("violet-123"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getDebugDraft(draftId,"bob"));
            assertArrayEquals(originalSidecar,Files.readAllBytes(debugSidecar),"Draft creation must not modify the package sidecar");

            Files.writeString(debugSidecar,"schemaVersion: att-debug/v1.2\ninputs:\n  value: changed\n");
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.submitDebugDraft(
                    ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+draftId+"\"}"),"alice"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getDebugDraft(draftId,"alice"));
            assertTrue(Files.exists(debugSidecar));
        } finally {runtime.close();}
    }
}
