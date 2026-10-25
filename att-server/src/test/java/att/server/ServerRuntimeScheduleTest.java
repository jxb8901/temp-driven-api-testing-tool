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

/** Persisted Load-schedule cases. A separate class so Surefire can run them on their own JVM. */
class ServerRuntimeScheduleTest {
    @TempDir Path temp;

    @Test void persistedScheduleSubmitsAStandardLoadJobAfterRestartAndRejectsInvalidOrUnavailablePackages() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("scheduled-load-packages"));Path packageRoot=Files.createDirectory(allowed.resolve("p"));
        Path templates=Files.createDirectories(packageRoot.resolve("templates/SCHEDULED"));Path loads=Files.createDirectories(packageRoot.resolve("load"));
        Files.createDirectories(packageRoot.resolve("testcase"));
        Files.createDirectories(packageRoot.resolve("testdata"));
        Path runtimeConfig=temp.resolve("scheduled-runtime/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Files.writeString(packageRoot.resolve("package.yaml"),"schemaVersion: att-package/v1\ntestdata: {environments: {SIT: [testdata/edda.yaml]}}\n");
        Files.writeString(packageRoot.resolve("testdata/edda.yaml"),"schemaVersion: att-testdata/v1.0\nid: edda\nrecords: [{currency: HKD}]\n");
        Files.writeString(templates.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: SCHEDULED\ndescription: scheduled Load smoke target\nactions:\n"
                +"  message:\n    type: log\n    message: scheduled Load passed\n");
        Files.writeString(templates.resolve("debug.yaml"),"schemaVersion: att-debug/v1.2\ntestdata: [testdata/edda.yaml]\ninputs:\n  currency: '@{edda.currency}'\n  password: package-sidecar-secret\n");
        Files.writeString(loads.resolve("scenario.yaml"),"schemaVersion: att-load/v1.6\nworkloads:\n  - id: scheduled-smoke\n    target:\n      type: template\n      id: SCHEDULED\n    load:\n      users: 1\n      duration: 1s\n");
        Files.writeString(loads.resolve("bad.yaml"),"schemaVersion: att-load/invalid\nworkloads: []\n");
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("scheduled-load-data");Path configFile=temp.resolve("scheduled-load-server.yaml");
        Files.writeString(configFile,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)
                +"\n  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\n    redaction:\n      enabled: true\n  scheduling:\n    enabled: true\n    minimumIntervalMinutes: 1\n    maxConcurrentRuns: 1\n  inlineLoad:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(allowed)+"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("scheduled-load-WEB-INF-lib"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        ServerRuntimeTestSupport.MutableClock clock=new ServerRuntimeTestSupport.MutableClock(Instant.parse("2026-10-12T17:50:00Z"));
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
            assertEquals(Boolean.TRUE,environmentValidation.get("scheduleAvailable"));
            assertTrue(String.valueOf(environmentValidation.get("draftId")).matches("C[0-9A-F]{32}"));
            assertFalse(environmentValidation.containsKey("normalizedScenario"));
            assertThrows(ServerRuntime.NotFoundException.class,()->setupRuntime.getCatalogLoadDraft(String.valueOf(environmentValidation.get("draftId")),"bob"));
            com.fasterxml.jackson.databind.node.ObjectNode catalogScheduleRequest=ServerRuntime.JSON.createObjectNode();
            catalogScheduleRequest.put("name","Validated Catalog scenario");catalogScheduleRequest.put("packageId","p");
            catalogScheduleRequest.put("draftId",String.valueOf(environmentValidation.get("draftId")));
            catalogScheduleRequest.put("environment","SIT");catalogScheduleRequest.put("timezone","UTC");catalogScheduleRequest.put("cron","0 2 * * *");
            LoadSchedule catalogSnapshotSchedule=setupRuntime.createLoadSchedule(catalogScheduleRequest,"alice");
            assertNotNull(catalogSnapshotSchedule.listSummary);
            assertFalse(((List<?>)catalogSnapshotSchedule.listSummary.get("workloads")).isEmpty());
            assertEquals(ServerRuntime.JSON.writeValueAsString(catalogSnapshotSchedule.listSummary),ServerRuntime.JSON.writeValueAsString(setupRuntime.store.schedule(catalogSnapshotSchedule.id).listSummary));
            assertTrue(catalogSnapshotSchedule.catalogSnapshot);assertNull(catalogSnapshotSchedule.scenario);
            assertNotNull(catalogSnapshotSchedule.inlineScenario);assertNotNull(catalogSnapshotSchedule.expectedRevisionDigest);
            assertTrue(Boolean.TRUE.equals(setupRuntime.loadScheduleView(catalogSnapshotSchedule).get("inlineSnapshot")));
            assertFalse(setupRuntime.loadScheduleView(catalogSnapshotSchedule).containsKey("inlineScenario"));
            setupRuntime.deleteLoadSchedule(catalogSnapshotSchedule.id,"alice");
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
            setupRuntime.deleteLoadSchedule(completedOnce.id,"alice");
        } finally {setupRuntime.close();}

        Path noInlineConfigFile=temp.resolve("scheduled-catalog-no-inline-server.yaml");
        Files.writeString(noInlineConfigFile,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("scheduled-catalog-no-inline-data"))
                +"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)+"\n  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256\n    redaction:\n      enabled: true\n  scheduling:\n    enabled: true\n    minimumIntervalMinutes: 1\n    maxConcurrentRuns: 1\n  inlineLoad:\n    enabled: false\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+ServerRuntimeTestSupport.yaml(allowed)+"\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(packageRoot)+"\n");
        ServerRuntime noInlineRuntime=new ServerRuntime(ServerConfig.load(noInlineConfigFile),libs.toString(),
                builder->{builder.redirectError(ProcessBuilder.Redirect.INHERIT);return builder.start();},clock);
        try {
            Map<String,Object> validated=noInlineRuntime.validateLoadCatalogScenario("p","load/scenario.yaml","SIT","alice");
            assertEquals(Boolean.TRUE,validated.get("scheduleAvailable"),"File-backed Schedule drafts must not depend on browser Inline Load policy.");
            com.fasterxml.jackson.databind.node.ObjectNode snapshotRequest=ServerRuntime.JSON.createObjectNode();
            snapshotRequest.put("name","Catalog file snapshot");snapshotRequest.put("packageId","p");snapshotRequest.put("draftId",String.valueOf(validated.get("draftId")));
            snapshotRequest.put("environment","SIT");snapshotRequest.put("timezone","UTC");snapshotRequest.put("cron","0 2 * * *");
            LoadSchedule fileSnapshot=noInlineRuntime.createLoadSchedule(snapshotRequest,"alice");
            assertTrue(fileSnapshot.catalogSnapshot);assertNotNull(fileSnapshot.inlineScenario);
            assertThrows(ServerRuntime.InlineLoadDisabledException.class,()->noInlineRuntime.createAdvancedLoadDraft(
                    ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"scenario\":{\"schemaVersion\":\"att-load/v1.7\",\"workloads\":[]}}"),"alice"));
        } finally {noInlineRuntime.close();}

        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),builder->{builder.redirectError(ProcessBuilder.Redirect.INHERIT);return builder.start();},clock);
        try {
            // Resume only after the runtime that observed the clock jump has stopped.
            // Its scheduler can still see that jump and would misfire this cron forward.
            runtime.setLoadScheduleEnabled(scheduleId,true,"alice");
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
            long archiveDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while((!"READY".equals(runtime.artifactIndex(jobId).get("archiveStatus"))||Files.exists(output))&&System.nanoTime()<archiveDeadline)Thread.sleep(20);
            assertEquals("READY",runtime.artifactIndex(jobId).get("archiveStatus"));
            assertFalse(Files.exists(output),"Verified scheduled Load artifacts live only in the ZIP");
            assertFalse(((List<?>)runtime.artifactIndex(jobId).get("primaryReports")).isEmpty(),"The archived Load report is discovered independently of pagination");

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
}
