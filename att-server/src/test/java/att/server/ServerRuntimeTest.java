package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeTest {
    @TempDir Path temp;
    @Test void launchesOneStructuredWorkerProcessForAnAcceptedJob() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages"));Path pkg=Files.createDirectory(allowed.resolve("broken-package"));
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("data")+"\n  javaExecutable: "+javaBin+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
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

    @Test void listsPackageResourcesThroughTheBoundedInspectorWorkerAndSignsPaginationCursors() throws Exception {
        Path packageRoot=Path.of("").toRealPath();Path allowed=packageRoot;
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("inspection-runtime-server.yaml");
        Files.writeString(configFile,"server:\n  dataDir: "+yaml(temp.resolve("inspection-runtime-data"))+"\n  javaExecutable: "+yaml(javaBin)+"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("inspection-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")&&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),builder->{
            builder.environment().put("ORDERS_DB_USERNAME","inspection-test");builder.environment().put("ORDERS_DB_PASSWORD","inspection-test");
            builder.environment().put("PAYMENT_MQ_USERNAME","inspection-test");builder.environment().put("PAYMENT_MQ_PASSWORD","inspection-test");
            return builder.start();
        });
        try {
            Map<String,Object> first=runtime.inspectResource("p","list","template",null,null,1,null,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> firstItems=(List<Map<String,Object>>)first.get("items");
            assertEquals(1,firstItems.size());assertNotNull(first.get("nextCursor"));
            Map<String,Object> second=runtime.inspectResource("p","list","template",null,null,1,String.valueOf(first.get("nextCursor")),"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> secondItems=(List<Map<String,Object>>)second.get("items");
            assertEquals(1,secondItems.size());assertNotEquals(firstItems.get(0).get("resourceId"),secondItems.get(0).get("resourceId"));
            assertThrows(IllegalArgumentException.class,()->runtime.inspectResource("p","list","template",null,null,1,String.valueOf(first.get("nextCursor"))+"x","alice"));
            assertThrows(IllegalArgumentException.class,()->runtime.inspectResource("p","list","template",null,null,1,String.valueOf(first.get("nextCursor")),"bob"));
        } finally {runtime.close();}
    }

    @Test void returnsDeclaredEffectiveAndComparisonConfigurationThroughTheBoundedInspectorWorker() throws Exception {
        Path packageRoot=Path.of("").toRealPath();
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name"," ").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("configuration-inspection-server.yaml");
        Files.writeString(configFile,"server:\n  dataDir: "+yaml(temp.resolve("configuration-inspection-data"))+"\n  javaExecutable: "+yaml(javaBin)+"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(packageRoot)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("configuration-inspection-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
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
            Map<String,Object> declared=runtime.inspectConfiguration("p","declared",null,null,"alice");
            assertEquals("declared",declared.get("state"));
            assertTrue(declared.toString().contains("SIT"));assertTrue(declared.toString().contains("UAT"));
            Map<String,Object> effective=runtime.inspectConfiguration("p","effective","SIT",null,"alice");
            assertEquals("ready",effective.get("state"));assertEquals("SIT",effective.get("environment"));
            Map<String,Object> defaultEffective=runtime.inspectConfiguration("p","effective",null,null,"globals",0,50,"alice");
            assertEquals("ready",defaultEffective.get("state"));assertEquals("SIT",defaultEffective.get("environment"));
            Map<String,Object> comparison=runtime.inspectConfiguration("p","compare","SIT","UAT","alice");
            assertEquals("ready",comparison.get("state"));
            @SuppressWarnings("unchecked") List<Map<String,Object>> fields=(List<Map<String,Object>>)comparison.get("fields");
            Map<String,Object> hidden=fields.stream().filter(item->"dbhelpers.orders.url".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
            assertEquals("hidden",hidden.get("change"));
            Map<String,Object> comparisonPage=runtime.inspectConfiguration("p","compare","SIT","UAT",null,0,1,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> firstComparisonPage=(List<Map<String,Object>>)comparisonPage.get("fields");
            assertEquals(1,firstComparisonPage.size());assertTrue(((Number)comparisonPage.get("total")).intValue()>1);
            String publicData=declared+" "+effective+" "+comparison;
            assertFalse(publicData.contains("sit-db.example.internal"));assertFalse(publicData.contains("uat-db.example.internal"));
            assertFalse(publicData.contains("inspection-test-password"));assertFalse(publicData.contains(packageRoot.toString()));
        } finally {runtime.close();}
    }

    @Test void validatesDebugDraftsWithoutDisclosingDefaultsAndRejectsStalePrincipalBoundSubmissions() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("debug-draft-packages"));Path packageRoot=Files.createDirectory(allowed.resolve("p"));
        Path templates=Files.createDirectories(packageRoot.resolve("templates/FORM"));Files.createDirectories(packageRoot.resolve("testcase"));
        Files.createDirectories(packageRoot.resolve("config"));copySchemas(packageRoot);
        Files.writeString(packageRoot.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Files.writeString(templates.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: debug form test\nactions:\n"
                +"  log:\n    type: log\n    message: '${EXEC.INPUT.value}'\n");
        Path debugSidecar=templates.resolve("debug.yaml");Files.writeString(debugSidecar,"schemaVersion: att-debug/v1.2\ninputs:\n"
                +"  value: safe\n  payload:\n    account: 'customer account 123456789'\n    message: 'temporary credential violet-123'\n");
        byte[] originalSidecar=Files.readAllBytes(debugSidecar);
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("debug-draft-server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+yaml(temp.resolve("debug-draft-data"))+"\n  javaExecutable: "+yaml(javaBin)
                +"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        Path libs=Files.createDirectory(temp.resolve("debug-draft-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
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
            Map<String,Object> accepted=runtime.submitDebugDraft(ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+draftId+"\"}"),"alice");
            String jobId=String.valueOf(accepted.get("jobId"));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            Map<String,Object> record=runtime.jobRecord(jobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(record.get("status"))&&System.nanoTime()<deadline){Thread.sleep(20);record=runtime.jobRecord(jobId);}
            assertEquals("INVALID",record.get("status"),String.valueOf(runtime.resultRecord(jobId)));
            String events=runtime.events(jobId).after(0).toString();assertTrue(events.contains("ATT-SERVER-DRAFT-STALE"),events);
            assertTrue(Files.exists(debugSidecar));
        } finally {runtime.close();}
    }

    @Test void quickLoadDraftOmitsDebugImportsAndRunsOnlyExplicitLoadTestdata() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("quick-load-packages"));Path packageRoot=Files.createDirectory(allowed.resolve("p"));
        Path templates=Files.createDirectories(packageRoot.resolve("templates/FORM"));Files.createDirectories(packageRoot.resolve("testcase"));
        Files.createDirectories(packageRoot.resolve("config"));copySchemas(packageRoot);
        Path toolScript=Files.createDirectories(packageRoot.resolve("tools")).resolve("check-load-inputs.sh");
        Files.writeString(toolScript,"#!/bin/sh\n[ \"$1\" = CORR-176 ] && [ \"$2\" = ARG-176 ]\n");
        toolScript.toFile().setExecutable(true);
        Files.writeString(packageRoot.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n"
                +"tools:\n  echo:\n    name: Echo\n    description: Echo typed Load values\n"
                +"    command: [./tools/check-load-inputs.sh, \"${input.correlationId}\", \"${value}\"]\n    stdoutFormat: text\n"
                +"    arguments:\n      correlationId: {name: Correlation ID, description: Correlation ID, required: false}\n"
                +"      value: {name: Value, description: Value, required: true}\n");
        Files.writeString(templates.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: Quick Load test\nactions:\n"
                +"  log:\n    type: log\n    message: '${EXEC.INPUT.value}'\n");
        Path debugData=Files.createDirectories(packageRoot.resolve("debug-data")).resolve("debug.yaml");
        Files.writeString(debugData,"schemaVersion: att-testdata/v1.0\nid: debugAccounts\nrecords: [{id: 17}]\n");
        Path loadData=Files.createDirectories(packageRoot.resolve("testdata")).resolve("load.yaml");
        Files.writeString(loadData,"schemaVersion: att-testdata/v1.0\nid: loadAccounts\nrecords: [{id: 42}]\n");
        Path debugSidecar=templates.resolve("debug.yaml");
        Files.writeString(debugSidecar,"schemaVersion: att-debug/v1.2\ntestdata: [debug-data/debug.yaml]\ninputs: {value: default}\nvars: {reference: REF001}\n");
        byte[] originalSidecar=Files.readAllBytes(debugSidecar);
        Path toolSidecar=Files.createDirectories(packageRoot.resolve("config/tools")).resolve("echo.debug.yaml");
        Files.writeString(toolSidecar,"schemaVersion: att-debug/v1.2\ninputs: {correlationId: CORR-176}\narguments: {value: ARG-176}\n");
        byte[] originalToolSidecar=Files.readAllBytes(toolSidecar);
        Path loadPolicy=Files.createDirectories(packageRoot.resolve("load")).resolve("load.visualuser.yaml");
        Files.writeString(loadPolicy,"schemaVersion: att-load/v1.6\nload: {users: 1, duration: 1s}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("quick-load-server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+yaml(temp.resolve("quick-load-data"))+"\n  javaExecutable: "+yaml(javaBin)
                +"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        Path libs=Files.createDirectory(temp.resolve("quick-load-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")
                    &&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        MutableClock clock=new MutableClock();
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(configFile),libs.toString(),ProcessBuilder::start,clock);
        try {
            Map<String,Object> page=runtime.inspectResource("p","list","template",null,null,10,null,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
            String resourceId=String.valueOf(items.get(0).get("resourceId"));
            Map<String,Object> form=runtime.inspectQuickLoadForm("p","template",resourceId,"virtualUsers",null,"alice");
            assertEquals(Boolean.TRUE,form.get("debugLocalTestdataOmitted"));
            assertFalse(form.toString().contains("debug-data/debug.yaml"));assertFalse(form.toString().contains("debugAccounts"));
            com.fasterxml.jackson.databind.node.ObjectNode body=ServerRuntime.JSON.createObjectNode();body.put("packageId","p");
            body.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","FORM")));
            body.put("model","virtualUsers");body.set("input",ServerRuntime.JSON.valueToTree(Map.of("inputs",Map.of("value","draft"),"vars",Map.of("reference","REF002"))));
            body.set("load",ServerRuntime.JSON.valueToTree(Map.of("users",1,"duration","1s")));
            body.set("testdata",ServerRuntime.JSON.valueToTree(List.of("testdata/load.yaml")));
            Map<String,Object> draft=runtime.createQuickLoadDraft(body,"alice");String draftId=String.valueOf(draft.get("draftId"));
            assertFalse(draft.toString().contains("debugAccounts"));assertFalse(draft.toString().contains("testdata/load.yaml"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getQuickLoadDraft(draftId,"bob"));
            assertArrayEquals(originalSidecar,Files.readAllBytes(debugSidecar),"Draft validation must leave Debug sidecars unchanged");
            Map<String,Object> accepted=runtime.submitQuickLoadDraft(ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+draftId+"\"}"),"alice");
            String jobId=String.valueOf(accepted.get("jobId"));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Map<String,Object> record=runtime.jobRecord(jobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(record.get("status"))&&System.nanoTime()<deadline){Thread.sleep(20);record=runtime.jobRecord(jobId);}
            assertEquals("PASS",record.get("status"),String.valueOf(runtime.resultRecord(jobId)));
            assertEquals("load",record.get("command"));assertArrayEquals(originalSidecar,Files.readAllBytes(debugSidecar));
            assertTrue(Files.exists(loadPolicy));

            Map<String,Object> toolScenario=Map.of("schemaVersion","att-load/v1.6","load",Map.of("users",1,"duration","1s"),
                    "workloads",List.of(Map.of("id","echo-tool","load",Map.of("users",1),
                            "target",Map.of("type","tool","id","echo","arguments",Map.of("value",Map.of("$attDebugKeepDefault","/arguments/value"))),
                            "inputs",Map.of("correlationId",Map.of("$attDebugKeepDefault","/inputs/correlationId")))));
            com.fasterxml.jackson.databind.node.ObjectNode advancedBody=ServerRuntime.JSON.createObjectNode();advancedBody.put("packageId","p");advancedBody.set("scenario",ServerRuntime.JSON.valueToTree(toolScenario));
            Map<String,Object> advancedDraft;
            try { advancedDraft=runtime.createAdvancedLoadDraft(advancedBody,"alice"); }
            catch(ServerRuntime.DraftValidationException invalid) { throw new AssertionError(invalid.diagnostics.toString(),invalid); }
            String advancedDraftId=String.valueOf(advancedDraft.get("draftId"));
            assertEquals(Boolean.TRUE,advancedDraft.get("redacted"));assertFalse(advancedDraft.toString().contains("CORR-176"));assertFalse(advancedDraft.toString().contains("ARG-176"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getAdvancedLoadDraft(advancedDraftId,"bob"));
            assertArrayEquals(originalToolSidecar,Files.readAllBytes(toolSidecar),"Advanced Load validation must leave Tool sidecars unchanged");
            Map<String,Object> advancedAccepted=runtime.submitAdvancedLoadDraft(ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+advancedDraftId+"\"}"),"alice");
            String advancedJobId=String.valueOf(advancedAccepted.get("jobId"));long advancedDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Map<String,Object> advancedRecord=runtime.jobRecord(advancedJobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(advancedRecord.get("status"))&&System.nanoTime()<advancedDeadline){Thread.sleep(20);advancedRecord=runtime.jobRecord(advancedJobId);}
            assertEquals("PASS",advancedRecord.get("status"),"The executable Tool asserts it received both restored values: "+runtime.resultRecord(advancedJobId));
            assertArrayEquals(originalToolSidecar,Files.readAllBytes(toolSidecar));
            Map<String,Object> stale=runtime.createQuickLoadDraft(body,"alice");
            Files.writeString(templates.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: changed\nactions:\n"
                    +"  log:\n    type: log\n    message: '${EXEC.INPUT.value}'\n");
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.submitQuickLoadDraft(
                    ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+stale.get("draftId")+"\"}"),"alice"));

            Map<String,Object> expired=runtime.createQuickLoadDraft(body,"alice");
            clock.advance(Duration.ofMinutes(11));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getQuickLoadDraft(String.valueOf(expired.get("draftId")),"alice"));
        } finally {runtime.close();}
    }
    @Test void cancellationCannotSlipBetweenWorkerLaunchAndProcessPublication() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),"Uses a POSIX test launcher");
        Path allowed=Files.createDirectory(temp.resolve("cancel-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path launcher=temp.resolve("slow-java");Files.writeString(launcher,"#!/bin/sh\nexec sleep 60\n");launcher.toFile().setExecutable(true);
        Path configFile=temp.resolve("cancel-server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("cancel-data")+"\n  javaExecutable: "+launcher+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("cancel-WEB-INF-lib"));
        CountDownLatch launchEntered=new CountDownLatch(1),allowLaunch=new CountDownLatch(1);
        AtomicReference<Process> launched=new AtomicReference<>();
        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),builder->{
            launchEntered.countDown();
            try {if(!allowLaunch.await(5,TimeUnit.SECONDS))throw new java.io.IOException("test launch gate timed out");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("test launch gate interrupted",e);}
            Process process=builder.start();launched.set(process);return process;
        });
        try {
            Map<String,Object> submitted=runtime.submit("validate",ServerRuntime.JSON.readTree("{\"packageId\":\"p\"}"),"ci-test");
            String id=(String)submitted.get("jobId");assertTrue(launchEntered.await(5,TimeUnit.SECONDS),"Worker launcher should reach the process-publication boundary");
            FutureTask<Void> cancel=new FutureTask<>(()->{runtime.cancel(id,"ci-test");return null;});new Thread(cancel,"test-job-cancel").start();
            Thread.sleep(100);assertFalse(cancel.isDone(),"Cancellation must wait while launch and process publication are atomic");
            allowLaunch.countDown();cancel.get(5,TimeUnit.SECONDS);
            assertEquals("CANCELLED",runtime.jobRecord(id).get("status"));
            assertNotNull(launched.get());assertFalse(launched.get().isAlive(),"Cancellation must terminate the published Worker process");
            long cleanupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(runtime.jobs.containsKey(id)&&System.nanoTime()<cleanupDeadline)Thread.sleep(10);
            assertFalse(runtime.jobs.containsKey(id),"Worker cleanup must finish before the temporary fixture is removed");
        } finally {allowLaunch.countDown();runtime.close();}
    }

    @Test void cancellationAndWorkerCompletionSerializeWithoutMonitorInversion() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("race-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));Path yaml=temp.resolve("race-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("race-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("race-libs")).toString());
        try {
            for(int i=0;i<20;i++) {
                String id=String.format("J%016X",i+1);Job job=new Job(id,"run","p","test",new att.worker.WorkerRequest(),new JobEvents(runtime.config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),100));
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
            String id="J1122334455667788";Job job=new Job(id,"run","p","test",new att.worker.WorkerRequest(),new JobEvents(runtime.config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),100));
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
            Job job=new Job(id,"run","p","test",new att.worker.WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));job.resultJson=ServerRuntime.JSON.writeValueAsString(Map.of("summary",pkg.toRealPath().resolve("case.json").toString(),"artifact",output.resolve("report.xlsx").toString()));
            runtime.store.insert(job,"{}");runtime.jobs.put(id,job);runtime.finish(job,"PASS",0);var response=ServerRuntime.JSON.valueToTree(runtime.resultRecord(id));String encoded=response.toString();
            assertFalse(encoded.contains(pkg.toString()));assertFalse(encoded.contains(output.toString()));assertTrue(encoded.contains("package:case.json"));assertTrue(encoded.contains("artifact:report.xlsx"));
        } finally {runtime.close();}
    }

    @Test void recordsAndPersistsWorkerLifecycleTimingsAndResourceMetrics() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("metrics-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path yaml=temp.resolve("metrics-server.yaml");Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("metrics-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("metrics-libs")).toString());
        try {
            String id="J0123456789ABCDEF";Job job=new Job(id,"run","p","test",new att.worker.WorkerRequest(),
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
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("heap-command-libs")).toString(),builder->{
            command.set(new java.util.ArrayList<>(builder.command()));
            throw new java.io.IOException("captured Worker launch command");
        });
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
        addModuleJar(externalLibraries,"oracle-driver",driverClasses);addModuleJar(webLibs,"worker-probe",probeClasses);
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");Path yaml=temp.resolve("external-driver-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+yaml(temp.resolve("external-driver-data"))+"\n  javaExecutable: "+yaml(java)+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\n  libraryDirs:\n    - "+yaml(externalLibraries)+"\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),webLibs.toString(),builder->{
            assertTrue(builder.command().get(2).contains(externalLibraries.resolve("*").toString()),"Configured external library wildcard must be on the Worker classpath");
            builder.command().set(3,"probe.WorkerProbe");return builder.start();
        });
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

    private static void addModuleJar(Path lib,String name,Path classes)throws Exception {
        Path target=lib.resolve(name+".jar");try(OutputStream file=Files.newOutputStream(target);JarOutputStream jar=new JarOutputStream(file);var paths=Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path->{try{jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry();}catch(Exception e){throw new IllegalStateException(e);}});
        }
    }
    private static final class MutableClock extends Clock {
        private Instant current=Instant.now();
        @Override public ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return current;}
        void advance(Duration duration){current=current.plus(duration);}
    }
    private static void copySchemas(Path packageRoot)throws Exception {
        Path source=Path.of("schemas").toRealPath(),destination=packageRoot.resolve("schemas");
        try(var paths=Files.walk(source)) {
            for(Path path:(Iterable<Path>)paths::iterator) {
                Path target=destination.resolve(source.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(target);
                else {Files.createDirectories(target.getParent());Files.copy(path,target);}
            }
        }
    }
    private static String yaml(Path path){return "'"+path.toAbsolutePath().toString().replace("'","''")+"'";}
}
