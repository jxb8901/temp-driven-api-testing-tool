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
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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
        Files.writeString(configFile,"server:\n  dataDir: "+yaml(temp.resolve("inline-limits-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inlineLoad:\n    enabled: true\n    maxWorkloads: 10\n    maxTargets: 20\n    maxTotalUsers: 100\n    maxAggregateArrivalRatePerSecond: 100\n    maxConcurrentPerWorkload: 100\n    maxTotalConcurrent: 100\n    maxDurationSeconds: 60\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
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
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
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

    @Test void persistedScheduleSubmitsAStandardLoadJobAfterRestartAndRejectsInvalidOrUnavailablePackages() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("scheduled-load-packages"));Path packageRoot=Files.createDirectory(allowed.resolve("p"));
        Path templates=Files.createDirectories(packageRoot.resolve("templates/SCHEDULED"));Path loads=Files.createDirectories(packageRoot.resolve("load"));
        Files.createDirectories(packageRoot.resolve("testcase"));
        Files.createDirectories(packageRoot.resolve("testdata"));
        Path runtimeConfig=temp.resolve("scheduled-runtime/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT:\n    testdata: [testdata/edda.yaml]\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Files.writeString(packageRoot.resolve("testdata/edda.yaml"),"schemaVersion: att-testdata/v1.0\nid: edda\nrecords: [{currency: HKD}]\n");
        Files.writeString(templates.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: SCHEDULED\ndescription: scheduled Load smoke target\nactions:\n"
                +"  message:\n    type: log\n    message: scheduled Load passed\n");
        Files.writeString(templates.resolve("debug.yaml"),"schemaVersion: att-debug/v1.2\ntestdata: [testdata/edda.yaml]\ninputs:\n  currency: '@{edda.currency}'\n  password: package-sidecar-secret\n");
        Files.writeString(loads.resolve("scenario.yaml"),"schemaVersion: att-load/v1.6\nworkloads:\n  - id: scheduled-smoke\n    target:\n      type: template\n      id: SCHEDULED\n    load:\n      users: 1\n      duration: 1s\n");
        Files.writeString(loads.resolve("bad.yaml"),"schemaVersion: att-load/invalid\nworkloads: []\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("scheduled-load-data");Path configFile=temp.resolve("scheduled-load-server.yaml");
        Files.writeString(configFile,"server:\n  runtimeConfigPath: "+yaml(runtimeConfig)+"\n  dataDir: "+yaml(data)+"\n  javaExecutable: "+yaml(javaBin)
                +"\n  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\n    redaction:\n      enabled: true\n  scheduling:\n    enabled: true\n    minimumIntervalMinutes: 1\n    maxConcurrentRuns: 1\n  inlineLoad:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("scheduled-load-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        addWorkerClasspathJars(libs);
        MutableClock clock=new MutableClock(Instant.parse("2026-10-12T17:50:00Z"));
        JsonNode request=ServerRuntime.JSON.readTree("""
                {"name":"Nightly SIT","packageId":"p","scenario":"load/scenario.yaml","environment":"SIT",
                 "timezone":"Asia/Hong_Kong","cron":"0 2 * * *","enabled":false}
                """);
        ServerRuntime setupRuntime=new ServerRuntime(config,libs.toString(),builder->{builder.redirectError(ProcessBuilder.Redirect.INHERIT);return builder.start();},clock);
        String scheduleId,debugScheduleId;
        try {
            Map<String,Object> catalog=setupRuntime.inspectLoadCatalog("p",null,10,null,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> catalogItems=(List<Map<String,Object>>)catalog.get("items");
            Map<String,Object> discovered=catalogItems.stream().filter(item->"load/scenario.yaml".equals(item.get("path"))).findFirst().orElseThrow();
            assertEquals("unverified",discovered.get("validation"),"Catalog discovery should not resolve targets for every environment.");
            Map<String,Object> environmentValidation=setupRuntime.validateLoadCatalogScenario("p","load/scenario.yaml","SIT","alice");
            assertEquals("valid",environmentValidation.get("validation"));assertEquals("virtualUsers",environmentValidation.get("model"));
            assertThrows(Exception.class,()->setupRuntime.validateLoadCatalogScenario("p","load/bad.yaml","SIT","alice"));
            Map<String,Object> runNow=setupRuntime.submit("load",ServerRuntime.JSON.readTree(
                    "{\"packageId\":\"p\",\"scenario\":\"load/scenario.yaml\",\"environment\":\"SIT\"}"),"alice");
            String runNowId=String.valueOf(runNow.get("jobId"));
            long runNowDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Map<String,Object> runNowRecord=setupRuntime.jobRecord(runNowId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(runNowRecord.get("status"))&&System.nanoTime()<runNowDeadline) {
                Thread.sleep(20);runNowRecord=setupRuntime.jobRecord(runNowId);
            }
            assertEquals("PASS",runNowRecord.get("status"));
            Map<String,Object> runNowSummary=setupRuntime.loadJobsFor("alice",0,10).get(0);
            assertEquals("load/scenario.yaml",runNowSummary.get("scenario"));assertEquals("SIT",runNowSummary.get("environment"));

            Map<String,Object> sensitiveWorkload=new java.util.LinkedHashMap<>();
            sensitiveWorkload.put("id","sensitive-snapshot");
            sensitiveWorkload.put("target",Map.of("type","template","id","SCHEDULED"));
            sensitiveWorkload.put("load",Map.of("users",1,"duration","1s"));
            sensitiveWorkload.put("inputs",Map.of("password","inline-secret"));
            Map<String,Object> sensitiveScenario=Map.of("schemaVersion","att-load/v1.6","workloads",List.of(sensitiveWorkload));
            com.fasterxml.jackson.databind.node.ObjectNode sensitiveDraftRequest=ServerRuntime.JSON.createObjectNode();
            sensitiveDraftRequest.put("packageId","p");sensitiveDraftRequest.set("scenario",ServerRuntime.JSON.valueToTree(sensitiveScenario));
            Map<String,Object> sensitiveDraft=setupRuntime.createAdvancedLoadDraft(sensitiveDraftRequest,"alice");
            assertFalse(sensitiveDraft.toString().contains("inline-secret"),"The validated draft preview must not disclose the classified input.");
            assertFalse(sensitiveDraft.containsKey("containsSensitiveValues"),"Internal classification metadata must not become part of the public draft API.");
            JsonNode sensitiveScheduleRequest=ServerRuntime.JSON.readTree("{\"name\":\"Sensitive draft\",\"packageId\":\"p\",\"draftId\":\""
                    +sensitiveDraft.get("draftId")+"\",\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\"}");
            IllegalArgumentException sensitiveScheduleError=assertThrows(IllegalArgumentException.class,
                    ()->setupRuntime.createLoadSchedule(sensitiveScheduleRequest,"alice"));
            assertTrue(sensitiveScheduleError.getMessage().contains("cannot store credentials"));
            assertTrue(setupRuntime.loadSchedules().isEmpty(),"Sensitive inline values must be rejected before the schedule definition is persisted.");

            Map<String,Object> preview=setupRuntime.previewLoadSchedule(ServerRuntime.JSON.readTree("{\"timezone\":\"Asia/Hong_Kong\",\"cron\":\"0 2 * * *\"}"));
            assertEquals("Asia/Hong_Kong",preview.get("timezone"));assertEquals("2026-10-12T18:00:00Z",preview.get("nextRunAt"));
            assertEquals(5,((List<?>)preview.get("nextOccurrences")).size());
            assertEquals(preview.get("nextRunAt"),((List<?>)preview.get("nextOccurrences")).get(0));
            ServerRuntime.DraftValidationException invalid=assertThrows(ServerRuntime.DraftValidationException.class,()->setupRuntime.createLoadSchedule(
                    ServerRuntime.JSON.readTree("{\"name\":\"Bad scenario\",\"packageId\":\"p\",\"scenario\":\"load/bad.yaml\",\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\"}"),"operator"));
            assertFalse(invalid.diagnostics.isEmpty());
            assertThrows(IllegalArgumentException.class,()->setupRuntime.createLoadSchedule(ServerRuntime.JSON.readTree(
                    "{\"name\":\"Over cap\",\"packageId\":\"p\",\"scenario\":\"load/scenario.yaml\",\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\",\"maxConcurrentRuns\":2}"),"operator"));
            LoadSchedule created=setupRuntime.createLoadSchedule(request,"alice");scheduleId=created.id;

            Map<String,Object> resourcePage=setupRuntime.inspectResource("p","list","template",null,null,10,null,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> resources=(List<Map<String,Object>>)resourcePage.get("items");
            String resourceId=String.valueOf(resources.get(0).get("resourceId"));
            Map<String,Object> form=setupRuntime.inspectDebugForm("p","template",resourceId,"SIT","alice");
            assertTrue(form.toString().contains("@{edda.currency}"),"Ordinary sidecar expressions remain visible with masking enabled.");
            assertFalse(form.toString().contains("package-sidecar-secret"),"Password defaults remain protected in the safe form projection.");
            com.fasterxml.jackson.databind.node.ObjectNode debugDraftRequest=ServerRuntime.JSON.createObjectNode();debugDraftRequest.put("packageId","p");debugDraftRequest.put("environment","SIT");
            debugDraftRequest.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","SCHEDULED")));
            debugDraftRequest.set("input",ServerRuntime.JSON.valueToTree(form.get("input")));
            Map<String,Object> debugDraft;
            try { debugDraft=setupRuntime.createDebugDraft(debugDraftRequest,"alice"); }
            catch(ServerRuntime.DraftValidationException invalidDebugDraft) { throw new AssertionError(invalidDebugDraft.diagnostics.toString(),invalidDebugDraft); }
            assertTrue(debugDraft.toString().contains("@{edda.currency}"));
            assertFalse(debugDraft.toString().contains("package-sidecar-secret"));
            String debugDraftId=String.valueOf(debugDraft.get("draftId"));
            JsonNode debugScheduleRequest=ServerRuntime.JSON.readTree("{\"name\":\"Nightly Debug\",\"packageId\":\"p\",\"jobType\":\"debug\",\"draftId\":\""
                    +debugDraftId+"\",\"environment\":\"SIT\",\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\",\"enabled\":false}");
            LoadSchedule debugSchedule=setupRuntime.createLoadSchedule(debugScheduleRequest,"alice");debugScheduleId=debugSchedule.id;
            assertEquals("debug",debugSchedule.effectiveJobType());
            assertTrue(debugSchedule.inlineDebugInput.toString().contains("@{edda.currency}"));
            assertTrue(debugSchedule.inlineDebugInput.toString().contains("$attDebugKeepDefault"));
            assertFalse(debugSchedule.inlineDebugInput.toString().contains("package-sidecar-secret"));
            Map<String,Object> publicDebugSchedule=setupRuntime.loadScheduleView(debugSchedule);
            assertEquals(Map.of("type","template","id","SCHEDULED"),publicDebugSchedule.get("debugTarget"));
            assertFalse(publicDebugSchedule.containsKey("inlineDebugInput"));assertFalse(publicDebugSchedule.containsKey("expectedRevisionDigest"));
            assertFalse(publicDebugSchedule.containsKey("debugResourceId"));assertFalse(publicDebugSchedule.toString().contains("package-sidecar-secret"));

            // Unified RUN uses the same resource binding, protected defaults and Worker engine.
            com.fasterxml.jackson.databind.node.ObjectNode runRequest=debugDraftRequest.deepCopy();
            runRequest.put("schemaVersion",RunDefinition.VERSION);runRequest.put("executionType","run");
            runRequest.set("options",ServerRuntime.JSON.valueToTree(Map.of("debug",false)));
            Map<String,Object> runDraft=setupRuntime.createRunDraft(runRequest,"alice");
            String runDraftId=String.valueOf(runDraft.get("draftId"));
            assertEquals("run",runDraft.get("executionType"));
            assertFalse(runDraft.toString().contains("package-sidecar-secret"));
            assertThrows(ServerRuntime.NotFoundException.class,()->setupRuntime.getRunDraft(runDraftId,"bob"));
            com.fasterxml.jackson.databind.node.ObjectNode runScheduleRequest=ServerRuntime.JSON.createObjectNode();
            runScheduleRequest.put("name","Unified RUN");runScheduleRequest.put("packageId","p");runScheduleRequest.put("executionType","run");
            runScheduleRequest.put("draftId",runDraftId);runScheduleRequest.put("environment","SIT");
            runScheduleRequest.put("timezone","UTC");runScheduleRequest.put("cron","0 2 * * *");
            LoadSchedule runSchedule=setupRuntime.createLoadSchedule(runScheduleRequest,"alice");
            assertEquals("run",runSchedule.effectiveJobType());
            assertFalse(setupRuntime.loadScheduleView(runSchedule).containsKey("runInput"));
            assertFalse(runSchedule.runInput.toString().contains("package-sidecar-secret"));
            assertTrue(runSchedule.runInput.toString().contains("$attDebugKeepDefault"));
            assertEquals("run",setupRuntime.loadScheduleView(runSchedule).get("executionType"));
            assertEquals("RUN renamed",setupRuntime.updateLoadSchedule(runSchedule.id,ServerRuntime.JSON.readTree("{\"name\":\"RUN renamed\"}"),"alice").name);
            assertThrows(IllegalArgumentException.class,()->setupRuntime.updateLoadSchedule(runSchedule.id,ServerRuntime.JSON.readTree("{\"environment\":\"UAT\"}"),"alice"));
            Map<String,Object> runOccurrence=setupRuntime.triggerLoadSchedule(runSchedule.id,"alice");
            assertEquals("submitted",runOccurrence.get("status"),runOccurrence.toString());
            String runJobId=String.valueOf(runOccurrence.get("jobId"));
            long runDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            Map<String,Object> runRecord=setupRuntime.jobRecord(runJobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(runRecord.get("status"))&&System.nanoTime()<runDeadline) {
                Thread.sleep(20);runRecord=setupRuntime.jobRecord(runJobId);
            }
            assertEquals("PASS",runRecord.get("status"),setupRuntime.resultRecord(runJobId).toString());
            assertEquals("run",runRecord.get("command"));assertEquals("alice",runRecord.get("principal"));
            setupRuntime.deleteLoadSchedule(runSchedule.id,"alice");

            com.fasterxml.jackson.databind.node.ObjectNode overrideDraftRequest=ServerRuntime.JSON.createObjectNode();overrideDraftRequest.put("packageId","p");overrideDraftRequest.put("environment","SIT");
            overrideDraftRequest.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","SCHEDULED")));
            overrideDraftRequest.set("input",ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-debug/v1.2","inputs",Map.of("currency","@{edda.currency}","password","submitted-secret"))));
            var runOverride=overrideDraftRequest.deepCopy();runOverride.put("schemaVersion",RunDefinition.VERSION);runOverride.put("executionType","run");
            runOverride.set("options",ServerRuntime.JSON.valueToTree(Map.of("debug",true)));
            Map<String,Object> privateRunDraft=setupRuntime.createRunDraft(runOverride,"alice");
            assertFalse(privateRunDraft.toString().contains("submitted-secret"));
            IllegalArgumentException privateRunSchedule=assertThrows(IllegalArgumentException.class,()->setupRuntime.createLoadSchedule(
                    ServerRuntime.JSON.valueToTree(Map.of("name","Private RUN","packageId","p","executionType","run","draftId",privateRunDraft.get("draftId"),"environment","SIT","timezone","UTC","cron","0 2 * * *")),"alice"));
            assertTrue(privateRunSchedule.getMessage().contains("cannot persist submitted sensitive values"));
            Map<String,Object> secretOverrideDraft=setupRuntime.createDebugDraft(overrideDraftRequest,"alice");
            JsonNode secretOverrideSchedule=ServerRuntime.JSON.readTree("{\"name\":\"Secret Debug\",\"packageId\":\"p\",\"jobType\":\"debug\",\"draftId\":\""
                    +secretOverrideDraft.get("draftId")+"\",\"environment\":\"SIT\",\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\"}");
            IllegalArgumentException secretScheduleError=assertThrows(IllegalArgumentException.class,
                    ()->setupRuntime.createLoadSchedule(secretOverrideSchedule,"alice"));
            assertTrue(secretScheduleError.getMessage().contains("cannot persist submitted credentials"));
            assertFalse(setupRuntime.loadSchedules().stream().anyMatch(item->"Secret Debug".equals(item.name)));

            LoadSchedule edited=setupRuntime.updateLoadSchedule(scheduleId,ServerRuntime.JSON.readTree("{\"description\":\"edited description\"}"),"bob");
            assertEquals("edited description",edited.description);assertEquals("alice",edited.createdBy);assertEquals("bob",edited.updatedBy);
            assertFalse(setupRuntime.setLoadScheduleEnabled(scheduleId,false,"operator").enabled);
            assertTrue(setupRuntime.setLoadScheduleEnabled(scheduleId,true,"operator").enabled);

            // A schedule holds exactly one trigger, so an editor that switches recurrence replaces the stored trigger instead of retaining the omitted field.
            LoadSchedule triggerSwitch=setupRuntime.createLoadSchedule(ServerRuntime.JSON.readTree(
                    "{\"name\":\"Trigger switch\",\"packageId\":\"p\",\"scenario\":\"load/scenario.yaml\",\"environment\":\"SIT\",\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\",\"enabled\":false}"),"alice");
            assertEquals("2026-10-13T02:00:00Z",triggerSwitch.nextRunAt);
            LoadSchedule cronToOnce=setupRuntime.updateLoadSchedule(triggerSwitch.id,ServerRuntime.JSON.readTree("{\"runAt\":\"2026-10-13T09:30:00Z\"}"),"alice");
            assertEquals("2026-10-13T09:30:00Z",cronToOnce.runAt);assertNull(cronToOnce.cron);assertEquals("2026-10-13T09:30:00Z",cronToOnce.nextRunAt);
            LoadSchedule onceToCron=setupRuntime.updateLoadSchedule(triggerSwitch.id,ServerRuntime.JSON.readTree("{\"cron\":\"30 3 * * *\"}"),"alice");
            assertEquals("30 3 * * *",onceToCron.cron);assertNull(onceToCron.runAt);assertEquals("2026-10-13T03:30:00Z",onceToCron.nextRunAt);
            assertThrows(IllegalArgumentException.class,()->setupRuntime.updateLoadSchedule(triggerSwitch.id,
                    ServerRuntime.JSON.readTree("{\"cron\":\"30 3 * * *\",\"runAt\":\"2026-10-14T09:30:00Z\"}"),"alice"));
            assertEquals("Trigger switch renamed",setupRuntime.updateLoadSchedule(triggerSwitch.id,ServerRuntime.JSON.readTree("{\"name\":\"Trigger switch renamed\"}"),"alice").name);
            assertEquals("30 3 * * *",setupRuntime.loadSchedule(triggerSwitch.id).cron);
            setupRuntime.deleteLoadSchedule(triggerSwitch.id,"alice");

            LoadSchedule completedOnce=setupRuntime.createLoadSchedule(ServerRuntime.JSON.readTree(
                    "{\"name\":\"Completed once\",\"packageId\":\"p\",\"scenario\":\"load/scenario.yaml\",\"timezone\":\"UTC\",\"runAt\":\"2026-10-13T09:30:00Z\",\"enabled\":true}"),"alice");
            completedOnce.nextRunAt=null;setupRuntime.store.saveSchedule(completedOnce);
            setupRuntime.setLoadScheduleEnabled(scheduleId,false,"alice");
            Instant beforeCompletedEdit=clock.instant();clock.current=Instant.parse("2026-10-14T00:00:00Z");
            LoadSchedule completedEdited=setupRuntime.updateLoadSchedule(completedOnce.id,ServerRuntime.JSON.readTree("""
                    {"name":"Completed once renamed","description":"Post-run notes","tags":[],"packageId":"p","jobType":"load",
                     "scenario":"load/scenario.yaml","config":"","environment":"","timezone":"UTC",
                     "runAt":"2026-10-13T09:30:00.000Z","maxConcurrentRuns":1,"load":{},"enabled":true}
                    """),"alice");
            assertEquals("Completed once renamed",completedEdited.name);assertEquals("Post-run notes",completedEdited.description);
            assertEquals("2026-10-13T09:30:00Z",completedEdited.runAt);assertNull(completedEdited.nextRunAt,"A consumed one-time schedule must remain completed after a metadata-only update.");
            clock.current=beforeCompletedEdit;
            setupRuntime.setLoadScheduleEnabled(scheduleId,true,"alice");
            setupRuntime.deleteLoadSchedule(completedOnce.id,"alice");
        } finally {setupRuntime.close();}

        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),builder->{builder.redirectError(ProcessBuilder.Redirect.INHERIT);return builder.start();},clock);
        try {
            LoadSchedule restored=runtime.loadSchedule(scheduleId);
            assertEquals("alice",restored.createdBy);assertEquals("2026-10-12T18:00:00Z",restored.nextRunAt);
            Map<String,Object> occurrence=runtime.triggerLoadSchedule(scheduleId,"operator");
            assertEquals("submitted",occurrence.get("status"));String jobId=String.valueOf(occurrence.get("jobId"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Map<String,Object> record=runtime.jobRecord(jobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(record.get("status"))&&System.nanoTime()<deadline) {
                Thread.sleep(20);record=runtime.jobRecord(jobId);
            }
            assertEquals("PASS",record.get("status"),"Scheduled execution must use the standard Worker and Load path; result="+runtime.resultRecord(jobId));
            assertEquals("load",record.get("command"));assertEquals("load-schedule:"+scheduleId,record.get("principal"));
            Map<String,Object> report=runtime.resultRecord(jobId);
            assertEquals("PASS",((Map<?,?>)report.get("job")).get("status"));assertNotNull(report.get("result"));
            Path output=data.resolve("jobs").resolve(jobId).resolve("output");
            assertTrue(Files.isDirectory(output));
            try(var files=Files.walk(output)){assertTrue(files.anyMatch(Files::isRegularFile),"A standard Load run should produce artifacts");}

            LoadSchedule restoredDebug=runtime.loadSchedule(debugScheduleId);
            assertEquals("debug",restoredDebug.effectiveJobType());assertTrue(restoredDebug.inlineDebugInput.toString().contains("@{edda.currency}"));
            assertFalse(restoredDebug.inlineDebugInput.toString().contains("package-sidecar-secret"));
            Map<String,Object> debugOccurrence=runtime.triggerLoadSchedule(debugScheduleId,"operator");
            assertEquals("submitted",debugOccurrence.get("status"));String debugJobId=String.valueOf(debugOccurrence.get("jobId"));
            long debugDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Map<String,Object> debugRecord=runtime.jobRecord(debugJobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(debugRecord.get("status"))&&System.nanoTime()<debugDeadline) {
                Thread.sleep(20);debugRecord=runtime.jobRecord(debugJobId);
            }
            assertEquals("PASS",debugRecord.get("status"),"Scheduled Debug must submit a standard Debug Worker job; result="+runtime.resultRecord(debugJobId));
            assertEquals("debug",debugRecord.get("command"));assertEquals("debug-schedule:"+debugScheduleId,debugRecord.get("principal"));

            JsonNode unavailableRequest=ServerRuntime.JSON.readTree("{\"name\":\"Unavailable package\",\"packageId\":\"p\",\"scenario\":\"load/scenario.yaml\",\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\"}");
            String unavailableId=runtime.createLoadSchedule(unavailableRequest,"alice").id;
            try(var paths=Files.walk(packageRoot)) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);
            }
            Map<String,Object> rejected=runtime.triggerLoadSchedule(unavailableId,"operator");
            assertEquals("failed-to-submit",rejected.get("status"));
            assertTrue(String.valueOf(rejected.get("reason")).contains("no longer available"));
            runtime.deleteLoadSchedule(unavailableId,"operator");
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.loadSchedule(unavailableId));
            assertEquals("failed-to-submit",runtime.loadScheduleExecutions(unavailableId,10).get(0).get("status"),"delete preserves the occurrence history");
        } finally {runtime.close();}
    }

    @Test void listsPackageResourcesThroughTheBoundedInspectorWorkerAndSignsPaginationCursors() throws Exception {
        Path packageRoot=Path.of("").toRealPath().resolve("packages/default");Path allowed=packageRoot;
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("inspection-runtime-server.yaml");
        Files.writeString(configFile,"server:\n  dataDir: "+yaml(temp.resolve("inspection-runtime-data"))+"\n  javaExecutable: "+yaml(javaBin)+"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("inspection-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
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

    @Test void returnsDeclaredEffectiveAndComparisonConfigurationThroughTheBoundedInspectorWorker() throws Exception {
        Path packageRoot=Path.of("").toRealPath().resolve("packages/default");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name"," ").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("configuration-inspection-server.yaml");
        Path sharedRuntimeConfig=Path.of("config/config.yaml").toRealPath();
        Files.writeString(configFile,"server:\n  runtimeConfigPath: "+yaml(sharedRuntimeConfig)+"\n  dataDir: "+yaml(temp.resolve("configuration-inspection-data"))+"\n  javaExecutable: "+yaml(javaBin)+"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(packageRoot)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("configuration-inspection-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")&&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),builder->{
            builder.environment().put("ORDERS_DB_USERNAME","inspection-test-user");builder.environment().put("ORDERS_DB_PASSWORD","inspection-test-password");
            builder.environment().put("PAYMENT_MQ_USERNAME","inspection-test-user");builder.environment().put("PAYMENT_MQ_PASSWORD","inspection-test-password");
            builder.environment().put("JAVA_TOOL_OPTIONS","-Datt.inspection.debugFailures=true");
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            return builder.start();
        });
        try {
            Map<String,Object> declared=runtime.inspectConfiguration("p","declared",null,null,"alice");
            assertEquals("declared",declared.get("state"));
            assertTrue(declared.toString().contains("SIT"));assertTrue(declared.toString().contains("UAT"));
            Map<String,Object> effective=runtime.inspectConfiguration("p","effective","SIT",null,"alice");
            assertEquals("ready",effective.get("state"),effective.toString());assertEquals("SIT",effective.get("environment"));
            Map<String,Object> defaultEffective=runtime.inspectConfiguration("p","effective",null,null,"globals",0,50,"alice");
            assertEquals("ready",defaultEffective.get("state"),defaultEffective.toString());assertEquals("SIT",defaultEffective.get("environment"));
            Map<String,Object> comparison=runtime.inspectConfiguration("p","compare","SIT","UAT","dbhelpers",0,50,"alice");
            assertEquals("ready",comparison.get("state"));
            @SuppressWarnings("unchecked") List<Map<String,Object>> fields=(List<Map<String,Object>>)comparison.get("fields");
            Map<String,Object> visibleUrl=fields.stream().filter(item->"dbhelpers.orders.url".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
            assertEquals("changed",visibleUrl.get("change"));
            Map<String,Object> comparisonPage=runtime.inspectConfiguration("p","compare","SIT","UAT","dbhelpers",0,1,"alice");
            @SuppressWarnings("unchecked") List<Map<String,Object>> firstComparisonPage=(List<Map<String,Object>>)comparisonPage.get("fields");
            assertEquals(1,firstComparisonPage.size());assertTrue(((Number)comparisonPage.get("total")).intValue()>1);
            String publicData=declared+" "+effective+" "+comparison;
            assertTrue(publicData.contains("jdbc:postgresql://sit-db.example.internal:5432/orders"));
            assertTrue(publicData.contains("jdbc:postgresql://uat-db.example.internal:5432/orders"));
            assertFalse(publicData.contains("inspection-test-password"));assertFalse(publicData.contains(packageRoot.toString()));
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
        Path configFile=temp.resolve("debug-draft-server.yaml");Files.writeString(configFile,"server:\n  runtimeConfigPath: "+yaml(runtimeConfig)+"\n  dataDir: "+yaml(temp.resolve("debug-draft-data"))+"\n  javaExecutable: "+yaml(javaBin)
                +"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        Path libs=Files.createDirectory(temp.resolve("debug-draft-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
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
        Path runtimeConfig=temp.resolve("quick-load-runtime/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Path toolScript=Files.createDirectories(packageRoot.resolve("tools")).resolve("check-load-inputs.sh");
        Files.writeString(toolScript,"#!/bin/sh\n[ \"$1\" = CORR-176 ] && [ \"$2\" = ARG-176 ]\n");
        toolScript.toFile().setExecutable(true);
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
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
        Path toolSidecar=Files.createDirectories(runtimeConfig.getParent().resolve("tools")).resolve("echo.debug.yaml");
        Files.writeString(toolSidecar,"schemaVersion: att-debug/v1.2\ninputs: {correlationId: CORR-176}\narguments: {value: ARG-176}\n");
        byte[] originalToolSidecar=Files.readAllBytes(toolSidecar);
        Path loadPolicy=Files.createDirectories(packageRoot.resolve("load")).resolve("load.visualuser.yaml");
        Files.writeString(loadPolicy,"schemaVersion: att-load/v1.6\nload: {users: 1, duration: 1s}\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("quick-load-server.yaml");Files.writeString(configFile,"server:\n  runtimeConfigPath: "+yaml(runtimeConfig)+"\n  dataDir: "+yaml(temp.resolve("quick-load-data"))+"\n  javaExecutable: "+yaml(javaBin)
                +"\n  inlineLoad:\n    enabled: true\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(packageRoot)+"\n");
        Path libs=Files.createDirectory(temp.resolve("quick-load-WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
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
            Path arrivalPolicy=Files.createFile(packageRoot.resolve("load/load.arrivalrate.yaml"));
            Files.writeString(arrivalPolicy,"schemaVersion: att-load/v1.6\nload: {arrivalRate: '2/s', maxConcurrent: 3, overloadPolicy: drop, duration: 2m}\n");
            Map<String,Object> selectedPolicy=runtime.inspectQuickLoadPolicy("p","arrivalRate",null,"load/load.arrivalrate.yaml","alice");
            @SuppressWarnings("unchecked") Map<String,Object> selectedPolicyBody=(Map<String,Object>)selectedPolicy.get("policy");
            @SuppressWarnings("unchecked") Map<String,Object> selectedLoad=(Map<String,Object>)selectedPolicyBody.get("load");
            assertEquals("2/s",selectedLoad.get("arrivalRate"));
            @SuppressWarnings("unchecked") List<Map<String,Object>> policyTemplates=(List<Map<String,Object>>)selectedPolicy.get("templates");
            assertTrue(policyTemplates.stream().anyMatch(item->"load/load.arrivalrate.yaml".equals(item.get("id"))));
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
            long releaseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(((Number)runtime.counts().get("activeWorkers")).intValue()>0&&System.nanoTime()<releaseDeadline)Thread.sleep(10);
            assertEquals(0,((Number)runtime.counts().get("activeWorkers")).intValue(),"Completed Load jobs must release admission before the stale-draft check");
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
            assertEquals(Boolean.FALSE,advancedDraft.get("redacted"));assertTrue(advancedDraft.toString().contains("CORR-176"));assertTrue(advancedDraft.toString().contains("ARG-176"));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getAdvancedLoadDraft(advancedDraftId,"bob"));
            assertArrayEquals(originalToolSidecar,Files.readAllBytes(toolSidecar),"Advanced Load validation must leave Tool sidecars unchanged");
            Map<String,Object> advancedAccepted=runtime.submitAdvancedLoadDraft(ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"draftId\":\""+advancedDraftId+"\"}"),"alice");
            String advancedJobId=String.valueOf(advancedAccepted.get("jobId"));long advancedDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);Map<String,Object> advancedRecord=runtime.jobRecord(advancedJobId);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(advancedRecord.get("status"))&&System.nanoTime()<advancedDeadline){Thread.sleep(20);advancedRecord=runtime.jobRecord(advancedJobId);}
            assertEquals("PASS",advancedRecord.get("status"),"The executable Tool asserts it received both restored values: "+runtime.resultRecord(advancedJobId));
            long advancedReleaseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(((Number)runtime.counts().get("activeWorkers")).intValue()>0&&System.nanoTime()<advancedReleaseDeadline)Thread.sleep(10);
            assertEquals(0,((Number)runtime.counts().get("activeWorkers")).intValue(),"The Advanced Load Worker must exit before the test temp directory is removed");
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

    @Test void scheduledJobsRecheckActiveUserAuthorityBeforeAnyWorkerStarts() throws Exception {
        Path root=Files.createDirectories(temp.resolve("owner-package"));
        Path config=Files.writeString(temp.resolve("owner-server.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("owner-data"))+"\n  auth: {mode: local}\n  scheduling: {enabled: true}\nworkers: {}\npackages:\n  allowedRoots: ["+yaml(root)+"]\n  entries:\n    p: "+yaml(root)+"\n");
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(config),"")) {
            runtime.store.createBootstrapAdmin("keeper","Active administrator","unused","csrf");
            runtime.store.createAuthUser("disabled","Disabled owner","unused","ADMIN","csrf");
            runtime.store.setAuthActive("disabled",false);
            runtime.store.createAuthUser("reader","Reader owner","unused","USER","csrf");
            for(String type:List.of("run","load","debug"))for(String owner:List.of("missing","disabled")) {
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

    @Test void unifiedRunExecutesAllTargetsAndFreezesCaseSelectionsAcrossEnvironments() throws Exception {
        Path root=Files.createDirectories(temp.resolve("unified-package"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates/SIMPLE"));Files.createDirectories(root.resolve("templates/BAD"));Files.createDirectories(root.resolve("templates/flows/simple"));
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path runtimeConfig=temp.resolve("unified-runtime/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}, UAT: {}}\ntemplates: {root: templates}\ntestcase: {root: testcase}\ntools:\n  version:\n    name: Version\n    description: Local runtime version\n    command: ["+yaml(javaBin)+", '-version']\n    stdoutFormat: text\n    arguments: {}\n");
        Files.writeString(root.resolve("templates/SIMPLE/template.yaml"),"schemaVersion: att-template/v3.6\nname: SIMPLE\ndescription: Unified RUN fixture\nactions:\n  show: {type: log, message: once}\n");
        Files.writeString(root.resolve("templates/BAD/template.yaml"),"schemaVersion: att-template/v3.6\nname: BAD\ndescription: Failed Case fixture\nactions:\n  check: {type: assert, assert: '#{1 == 2}'}\n");
        Files.writeString(root.resolve("templates/flows/simple/flow.yaml"),"schemaVersion: att-flow/v3.6\nid: simple.flow.v1\nname: Simple flow\ndescription: Unified RUN flow\nactions:\n  show: {type: log, message: once}\n");
        Path workbook=root.resolve("testcase/cases.xlsx");
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=Files.newOutputStream(workbook)) {
            var sheet=book.createSheet("Cases");String[] columns={"ID","Tags","Template"};var header=sheet.createRow(0);
            for(int i=0;i<columns.length;i++)header.createCell(i).setCellValue(columns[i]);
            for(int i=1;i<=2;i++){var row=sheet.createRow(i);row.createCell(0).setCellValue("C"+i);row.createCell(1).setCellValue(i==1?"smoke":"slow");row.createCell(2).setCellValue("name: "+(i==1?"SIMPLE":"BAD"));}
            book.write(out);
        }
        Files.writeString(root.resolve("testcase/cases.yaml"),"schemaVersion: att-sidecar/v2.2\nid: cases\nexcel:\n  sheet: default=Cases\n  caseId: ID\n  tags: Tags\nstages:\n  - key: main\n    template: Template\n    required: true\n");
        new att.api.DefaultAttService().snapshot(new att.api.SnapshotRequest(root,runtimeConfig,"SIT",List.of(workbook),null,java.util.Set.of(),false));
        Path server=temp.resolve("unified-server.yaml");
        Files.writeString(server,"server:\n  runtimeConfigPath: "+yaml(runtimeConfig)+"\n  dataDir: "+yaml(temp.resolve("unified-data"))+"\n  javaExecutable: "+yaml(javaBin)+"\n  inspection:\n    enabled: true\n    timeoutMs: 30000\n  scheduling:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\npackages:\n  allowedRoots: ["+yaml(root)+"]\n  entries:\n    p: "+yaml(root)+"\n");
        Path libs=Files.createDirectory(temp.resolve("unified-libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))addModuleJar(libs,module,Path.of(module+"/target/classes"));
        addWorkerClasspathJars(libs);
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(server),libs.toString())) {
            for(String environment:List.of("SIT","UAT"))for(String type:List.of("case","template","flow","tool")) {
                var body=ServerRuntime.JSON.createObjectNode();body.put("schemaVersion",RunDefinition.VERSION);body.put("executionType","run");body.put("packageId","p");body.put("environment",environment);
                String id=switch(type){case "case"->"cases.default.C1";case "template"->"SIMPLE";case "flow"->"simple.flow.v1";default->"version";};
                body.set("target",ServerRuntime.JSON.valueToTree(Map.of("type",type,"id",id)));if(!"case".equals(type))body.set("input",ServerRuntime.JSON.createObjectNode());
                body.set("options",ServerRuntime.JSON.valueToTree(Map.of("debug","UAT".equals(environment))));
                Map<String,Object> draft;
                try {draft=runtime.createRunDraft(body,"alice");}catch(Exception failure){throw new AssertionError(environment+" "+type+" "+runtime.inspectResource("p","list",type,null,null,100,null,"alice"),failure);}
                String draftId=String.valueOf(draft.get("draftId"));
                assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getRunDraft(draftId,"bob"));
                LoadSchedule scheduled=runtime.createLoadSchedule(ServerRuntime.JSON.valueToTree(Map.of("name",environment+" "+type,
                        "packageId","p","environment",environment,"executionType","run","draftId",draftId,
                        "timezone","UTC","cron","0 2 * * *","enabled",false)),"alice");
                Map<String,Object> occurrence=runtime.triggerLoadSchedule(scheduled.id,"alice");
                Map<String,Object> scheduledResult=awaitUnifiedJob(runtime,String.valueOf(occurrence.get("jobId")));
                assertEquals("PASS",((Map<?,?>)scheduledResult.get("job")).get("status"),environment+" scheduled "+type+" "+scheduledResult);
                assertEquals("alice",((Map<?,?>)scheduledResult.get("job")).get("principal"));
                runtime.deleteLoadSchedule(scheduled.id,"alice");
                Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draftId)),"alice","alice");
                Map<String,Object> result=awaitUnifiedJob(runtime,String.valueOf(accepted.get("jobId")));
                assertEquals("PASS",((Map<?,?>)result.get("job")).get("status"),environment+" "+type+" "+result);
                assertEquals("run",((Map<?,?>)result.get("job")).get("executionType"));
                assertTrue(runtime.events(String.valueOf(accepted.get("jobId"))).after(0).stream().anyMatch(event->"result".equals(event.get("event"))));
                assertThrows(ServerRuntime.NotFoundException.class,()->runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draftId)),"alice","alice"));
            }
            var selection=ServerRuntime.JSON.readTree("{\"schemaVersion\":\"att-execution/v1\",\"executionType\":\"run\",\"packageId\":\"p\",\"selection\":{\"all\":true,\"tags\":[\"smoke\"]}}");
            Map<String,Object> draft=runtime.createRunDraft(selection,"alice");assertEquals(List.of("cases.default.C1"),draft.get("selectedCaseIds"));
            var all=ServerRuntime.JSON.readTree("{\"schemaVersion\":\"att-execution/v1\",\"executionType\":\"run\",\"packageId\":\"p\",\"selection\":{\"all\":true}}");
            for(Map<String,Object> filters:List.<Map<String,Object>>of(Map.of("suiteDirectory","testcase","tags",List.of("smoke")),Map.of("suites",List.of("testcase/cases.xlsx"),"caseIds",List.of("cases.default.C1","cases.default.C2"),"excludeTags",List.of("slow")))) {
                var filtered=all.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)filtered).set("selection",ServerRuntime.JSON.valueToTree(filters));
                assertEquals(List.of("cases.default.C1"),runtime.createRunDraft(filtered,"filters").get("selectedCaseIds"));
            }
            var unknown=all.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)unknown).set("selection",ServerRuntime.JSON.valueToTree(Map.of("caseIds",List.of("cases.default.C1","cases.default.MISSING"))));
            assertThrows(ServerRuntime.DraftValidationException.class,()->runtime.createRunDraft(unknown,"unknown"));
            Map<String,Object> allDraft=runtime.createRunDraft(all,"alice");
            Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",allDraft.get("draftId"))),"alice","alice");
            Map<String,Object> allResult=awaitUnifiedJob(runtime,String.valueOf(accepted.get("jobId")));
            assertEquals("FAIL",((Map<?,?>)allResult.get("job")).get("status"),allResult.toString());
            Path completedOutput=temp.resolve("unified-data").resolve("jobs").resolve(String.valueOf(accepted.get("jobId"))).resolve("output");
            assertTrue(Files.isRegularFile(completedOutput.resolve("latest-run.yaml")),allResult+"; output="+listTree(completedOutput));
            var rerun=(com.fasterxml.jackson.databind.node.ObjectNode)all.deepCopy();rerun.set("selection",ServerRuntime.JSON.valueToTree(Map.of("rerunFailed",true)));
            Map<String,Object> failed=runtime.createRunDraft(rerun,"alice");assertEquals(List.of("cases.default.C2"),failed.get("selectedCaseIds"));
            Map<String,Object> stale=runtime.createRunDraft(selection,"alice");
            Files.writeString(root.resolve("templates/SIMPLE/template.yaml"),"\n# package changed\n",java.nio.file.StandardOpenOption.APPEND);
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",stale.get("draftId"))),"alice","alice"));

            var debugInput=ServerRuntime.JSON.createObjectNode();debugInput.put("packageId","p");
            debugInput.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","SIMPLE")));
            debugInput.set("input",ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-debug/v1.2","inputs",Map.of())));
            for(int i=0;i<ServerRuntime.MAX_DRAFTS_PER_PRINCIPAL-1;i++)runtime.createDebugDraft(debugInput,"quota-boundary");
            var resourceRun=ServerRuntime.JSON.createObjectNode();resourceRun.put("schemaVersion",RunDefinition.VERSION);resourceRun.put("executionType","run");resourceRun.put("packageId","p");
            resourceRun.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","SIMPLE")));
            resourceRun.set("input",ServerRuntime.JSON.createObjectNode());resourceRun.set("options",ServerRuntime.JSON.valueToTree(Map.of("debug",false)));
            Map<String,Object> oneSlotRun=runtime.createRunDraft(resourceRun,"quota-boundary");
            assertThrows(ServerRuntime.DraftCapacityException.class,()->runtime.createDebugDraft(debugInput,"quota-boundary"));
            Map<String,Object> oneSlotAccepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",oneSlotRun.get("draftId"))),"quota-boundary","quota-boundary");
            assertEquals("PASS",((Map<?,?>)awaitUnifiedJob(runtime,String.valueOf(oneSlotAccepted.get("jobId"))).get("job")).get("status"));
            assertDoesNotThrow(()->runtime.createDebugDraft(debugInput,"quota-boundary"),"A resource RUN draft must occupy and release one quota slot");
        }
    }
    private static Map<String,Object> awaitUnifiedJob(ServerRuntime runtime,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline) {
            Map<String,Object> job=runtime.jobRecord(id);
            if(List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(job.get("status"))) {
                while(runtime.workers.getActiveCount()>0&&System.nanoTime()<deadline)Thread.sleep(10);
                return runtime.resultRecord(id);
            }
            Thread.sleep(20);
        }
        fail("RUN did not terminate: "+runtime.jobRecord(id));return Map.of();
    }

    private static void addModuleJar(Path lib,String name,Path classes)throws Exception {
        Path target=lib.resolve(name+".jar");try(OutputStream file=Files.newOutputStream(target);JarOutputStream jar=new JarOutputStream(file);var paths=Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path->{try{jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry();}catch(Exception e){throw new IllegalStateException(e);}});
        }
    }
    private static void addWorkerClasspathJars(Path libs)throws Exception {
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);
            if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")&&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());if(!Files.exists(target))try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
    }
    private static List<String> listTree(Path root)throws Exception {
        if(!Files.exists(root))return List.of("<missing>");
        try(var files=Files.walk(root,4)){return files.map(path->root.relativize(path).toString()).collect(java.util.stream.Collectors.toList());}
    }
    private static final class MutableClock extends Clock {
        private Instant current=Instant.now();
        MutableClock(){}
        MutableClock(Instant initial){current=initial;}
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
