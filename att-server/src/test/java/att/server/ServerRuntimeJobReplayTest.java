package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeJobReplayTest {
    @TempDir Path temp;
    private record Fixture(Path root,Path server,Path libs) {
        ServerRuntime open() throws Exception {return new ServerRuntime(ServerConfig.load(server),libs.toString());}
        ServerRuntime open(ServerRuntimeTestSupport.MutableClock clock) throws Exception {return new ServerRuntime(ServerConfig.load(server),libs.toString(),ProcessBuilder::start,clock);}
    }
    private Fixture fixture() throws Exception {
        Path root=Files.createDirectories(temp.resolve("unified-package"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates/SIMPLE"));Files.createDirectories(root.resolve("templates/BAD"));Files.createDirectories(root.resolve("templates/flows/simple"));
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path runtimeConfig=temp.resolve("unified-runtime/config/config.yaml");Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}, UAT: {}}\ntemplates: {root: templates}\ntestcase: {root: testcase}\ntools:\n  version:\n    name: Version\n    description: Local runtime version\n    command: ["+ServerRuntimeTestSupport.yaml(javaBin)+", '-version']\n    stdoutFormat: text\n    arguments: {}\n");
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
        Files.writeString(server,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("unified-data"))+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)+"\n  auth: {mode: local}\n  inspection:\n    enabled: true\n    timeoutMs: 30000\n  inlineLoad: {enabled: true}\n  scheduling:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\npackages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(root)+"]\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(root)+"\n");
        Path libs=Files.createDirectory(temp.resolve("unified-libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        Files.createDirectories(root.resolve("loads"));
        Files.writeString(root.resolve("loads/smoke.yaml"),"schemaVersion: att-load/v1.6\nworkloads:\n  - id: smoke\n    target: {type: template, id: SIMPLE}\n    load: {users: 1, duration: 1s}\n");
        return new Fixture(root,server,libs);
    }
    private static void users(ServerRuntime runtime) throws Exception {
        runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
        runtime.store.createAuthUser("bob","Bob","unused","USER","csrf");
    }
    private static JsonNode submit(String draftId) {return ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draftId));}
    private static void await(ServerRuntime runtime,String id) throws Exception {
        Instant deadline=Instant.now().plusSeconds(30);
        while(Instant.now().isBefore(deadline)) {
            String status=String.valueOf(runtime.jobRecord(id).get("status"));
            if(List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(status)){assertNotEquals("ERROR",status,runtime.resultRecord(id).toString());return;}
            Thread.sleep(25);
        }
        fail("Job did not finish: "+id);
    }
    @Test void schedulePoliciesPreserveSourceAndAllowStaleMetadataEditsAcrossRestart() throws Exception {
        Fixture fixture=fixture();String strictId,pauseId,ignoreId,stored;
        ServerRuntimeTestSupport.MutableClock clock=new ServerRuntimeTestSupport.MutableClock(Instant.parse("2026-10-22T00:00:00Z"));
        try(ServerRuntime runtime=fixture.open(clock)) {
            users(runtime);
            var definition=ServerRuntime.JSON.createObjectNode();definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");definition.put("environment","SIT");
            definition.putObject("target").put("type","template").put("id","SIMPLE");definition.putObject("input");
            Map<String,Object> draft=runtime.createRunDraft(definition,"alice");
            var request=ServerRuntime.JSON.valueToTree(Map.of("name","Policy test","executionType","run","packageId","p","environment","SIT","draftId",draft.get("draftId"),"timezone","UTC","cron","0 2 * * *","enabled",true));
            LoadSchedule strict=runtime.createLoadSchedule(request,"alice");strictId=strict.id;stored=strict.expectedRevisionDigest;
            var pauseRequest=((com.fasterxml.jackson.databind.node.ObjectNode)request).deepCopy().put("packageChangePolicy","pause");
            LoadSchedule pause=runtime.createLoadSchedule(pauseRequest,"alice");pauseId=pause.id;
            var ignoreRequest=((com.fasterxml.jackson.databind.node.ObjectNode)request).deepCopy().put("packageChangePolicy","ignore");
            LoadSchedule ignore=runtime.createLoadSchedule(ignoreRequest,"alice");ignoreId=ignore.id;
            Path unrelated=fixture.root.resolve("templates/BAD/template.yaml");String originalUnrelated=Files.readString(unrelated);
            Files.writeString(unrelated,originalUnrelated+"# unrelated change\n");
            Map<String,Object> blocked=runtime.triggerLoadSchedule(strictId,"alice");
            assertEquals("package-revision-changed",blocked.get("reason"));assertNull(blocked.get("jobId"));
            assertTrue(runtime.loadSchedule(strictId).enabled);
            clock.advance(Duration.ofSeconds(1));
            LoadSchedule renamed=runtime.updateLoadSchedule(strictId,ServerRuntime.JSON.valueToTree(Map.of("name","Renamed stale RUN","cron","0 3 * * *","description","metadata remains editable","tags",List.of("stale"))),"alice");
            assertEquals(stored,renamed.expectedRevisionDigest);assertEquals("package-revision-changed",renamed.sourceStatus);
            assertEquals(strict.runInput,renamed.runInput);
            assertEquals("package-revision-changed",runtime.triggerLoadSchedule(pauseId,"alice").get("reason"));
            assertFalse(runtime.loadSchedule(pauseId).enabled);assertEquals("package-revision-changed",runtime.loadSchedule(pauseId).autoPauseReason);
            assertThrows(ServerRuntime.ScheduleSourceChangedException.class,()->runtime.setLoadScheduleEnabled(pauseId,true,"alice"));
            Files.writeString(unrelated,originalUnrelated);
            Map<String,Object> restored=runtime.triggerLoadSchedule(strictId,"alice");assertEquals("submitted",restored.get("status"),restored.toString());
            await(runtime,String.valueOf(restored.get("jobId")));
            assertNull(runtime.loadSchedule(strictId).sourceStatus,"A successful execution after restoring the pinned package must clear current stale status");
            assertEquals(stored,runtime.loadSchedule(strictId).currentRevisionDigest);
            assertEquals(1,runtime.store.auditOutcomes("SCHEDULE_SOURCE_RESTORED").size());
            Map<String,Object> accepted=runtime.triggerLoadSchedule(ignoreId,"alice");assertEquals("submitted",accepted.get("status"),accepted.toString());
            await(runtime,String.valueOf(accepted.get("jobId")));
            assertEquals(stored,runtime.loadSchedule(ignoreId).expectedRevisionDigest);
            assertEquals(ignore.runInput,runtime.loadSchedule(ignoreId).runInput);
            assertEquals(1,runtime.store.auditOutcomes("SCHEDULE_SOURCE_REVALIDATED").size());
            assertTrue(runtime.store.auditOutcomes("SCHEDULE_SOURCE_REVALIDATED").get(0).contains("stored="+stored));
        }
        try(ServerRuntime runtime=fixture.open(clock)) {
            assertEquals("Renamed stale RUN",runtime.loadSchedule(strictId).name);
            assertFalse(runtime.loadSchedule(pauseId).enabled);assertEquals(stored,runtime.loadSchedule(ignoreId).expectedRevisionDigest);
            Path target=fixture.root.resolve("templates/SIMPLE/template.yaml");
            Files.writeString(target,Files.readString(target).replace("message: once","message: compatible latest target"));
            clock.advance(Duration.ofMinutes(16));
            Map<String,Object> compatible=runtime.triggerLoadSchedule(ignoreId,"alice");assertEquals("submitted",compatible.get("status"),compatible.toString());
            await(runtime,String.valueOf(compatible.get("jobId")));
            assertEquals(stored,runtime.loadSchedule(ignoreId).expectedRevisionDigest);
            clock.advance(Duration.ofMinutes(16));
            Files.delete(target);
            Map<String,Object> invalid=runtime.triggerLoadSchedule(ignoreId,"alice");
            assertEquals("failed-to-submit",invalid.get("status"));assertNull(invalid.get("jobId"));
        }
    }

    @Test void fileBackedLoadSchedulesPinAndEnforceTheirCreationRevision() throws Exception {
        Fixture fixture=fixture();String strictId,pauseId,strictRevision;
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);
            var strictRequest=ServerRuntime.JSON.valueToTree(Map.of("name","Strict file Load","executionType","load","packageId","p","environment","SIT",
                    "scenario","loads/smoke.yaml","timezone","UTC","cron","0 2 * * *","enabled",true));
            LoadSchedule strict=runtime.createLoadSchedule(strictRequest,"alice");strictId=strict.id;strictRevision=strict.expectedRevisionDigest;
            assertNotNull(strictRevision,"New file-backed schedules must pin their validated package revision");
            var pauseRequest=((com.fasterxml.jackson.databind.node.ObjectNode)strictRequest).deepCopy().put("name","Paused file Load").put("packageChangePolicy","pause");
            LoadSchedule pause=runtime.createLoadSchedule(pauseRequest,"alice");pauseId=pause.id;
            assertNotNull(pause.expectedRevisionDigest);
            Path scenario=fixture.root.resolve("loads/smoke.yaml");
            Files.writeString(scenario,Files.readString(scenario).replace("duration: 1s","duration: 2s"));
            Map<String,Object> strictResult=runtime.triggerLoadSchedule(strictId,"alice");
            assertEquals("package-revision-changed",strictResult.get("reason"));assertNull(strictResult.get("jobId"));
            assertTrue(runtime.loadSchedule(strictId).enabled);
            Map<String,Object> pauseResult=runtime.triggerLoadSchedule(pauseId,"alice");
            assertEquals("package-revision-changed",pauseResult.get("reason"));assertNull(pauseResult.get("jobId"));
            assertFalse(runtime.loadSchedule(pauseId).enabled);
            assertEquals(strictRevision,runtime.loadSchedule(strictId).expectedRevisionDigest);
        }
    }

    @Test void fullAdmissionRejectsLegacySubmissionBeforeInspectionCapacityIsConsumed() throws Exception {
        Fixture fixture=fixture();
        String contents=Files.readString(fixture.server);
        String constrained=contents.replace("  inspection:\n    enabled: true\n    timeoutMs: 30000",
                "  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 0\n    timeoutMs: 30000");
        assertNotEquals(contents,constrained,"Test fixture must configure a single unqueued inspector");
        Files.writeString(fixture.server,constrained);
        try(ServerRuntime runtime=fixture.open()) {
            var inspectorsField=ServerRuntime.class.getDeclaredField("inspectors");inspectorsField.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor inspectors=(java.util.concurrent.ThreadPoolExecutor)inspectorsField.get(runtime);
            java.util.concurrent.CountDownLatch inspectionStarted=new java.util.concurrent.CountDownLatch(1),releaseInspection=new java.util.concurrent.CountDownLatch(1);
            inspectors.execute(()->{inspectionStarted.countDown();try{releaseInspection.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
            assertTrue(inspectionStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var slotsField=ServerRuntime.class.getDeclaredField("admissionSlots");slotsField.setAccessible(true);
            java.util.concurrent.Semaphore slots=(java.util.concurrent.Semaphore)slotsField.get(runtime);int held=0;
            while(slots.tryAcquire())held++;
            try {
                assertTrue(held>0);
                long dispatches=runtime.inspectionMetrics.count(att.metrics.InspectionMetrics.Stage.DISPATCH);
                assertThrows(ServerRuntime.QueueFullException.class,()->runtime.submit("run",ServerRuntime.JSON.valueToTree(Map.of("packageId","p")),"alice"));
                assertEquals(dispatches,runtime.inspectionMetrics.count(att.metrics.InspectionMetrics.Stage.DISPATCH),
                        "A capacity-rejected legacy request must not dispatch an inspection Worker");
            } finally {releaseInspection.countDown();slots.release(held);}
        }
    }

    @Test void runTargetsAndExactBatchSelectionsAreRevalidatedAndIndependent() throws Exception {
        Fixture fixture=fixture();
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);
            for(String type:List.of("case","template","flow","tool","batch")) {
                var definition=ServerRuntime.JSON.createObjectNode();definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");definition.put("environment","SIT");
                definition.putObject("options").put("debug",!"case".equals(type));
                if("batch".equals(type))definition.putObject("selection").putArray("caseIds").add("cases.default.C1");
                else {
                    String target=switch(type){case "case"->"cases.default.C1";case "template"->"SIMPLE";case "flow"->"simple.flow.v1";default->"version";};
                    definition.set("target",ServerRuntime.JSON.valueToTree(Map.of("type",type,"id",target)));
                    if(!"case".equals(type))definition.putObject("input");
                }
                Map<String,Object> draft=runtime.createRunDraft(definition,"alice");
                long inspectionsBeforeSubmit=runtime.inspectionMetrics.count(att.metrics.InspectionMetrics.Stage.DISPATCH);
                String original=String.valueOf(runtime.submitRunDraft(submit(String.valueOf(draft.get("draftId"))),"alice","alice").get("jobId"));await(runtime,original);
                assertEquals(inspectionsBeforeSubmit+1,runtime.inspectionMetrics.count(att.metrics.InspectionMetrics.Stage.DISPATCH),
                        "Draft submission should perform only its freshness check, not re-inspect the validated RUN input");
                Map<String,Object> metadata=runtime.jobReplay(original,"alice");assertEquals(true,metadata.get("available"),type+": "+metadata);
                assertFalse(metadata.toString().contains(fixture.root.toString()),"No host roots in browser replay data");
                assertThrows(ServerRuntime.NotFoundException.class,()->runtime.jobReplay(original,"bob"));
                JsonNode retained=ServerRuntime.JSON.valueToTree(metadata.get("definition"));
                assertEquals(!"case".equals(type),retained.path("options").path("debug").asBoolean());
                if("batch".equals(type))assertEquals(List.of("cases.default.C1"),ServerRuntime.JSON.convertValue(retained.path("selection").path("caseIds"),List.class));
                Map<String,Object> before=runtime.jobRecord(original);String originalResult=(String)runtime.store.get(original).get("resultJson");
                Map<String,Object> review=runtime.createJobReplayDraft(original,"alice");
                var confirmation=ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId")));
                assertThrows(IllegalArgumentException.class,()->runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("confirm",false,"intentId",review.get("intentId"))),"alice"));
                assertThrows(ServerRuntime.NotFoundException.class,()->runtime.rerunJob(original,confirmation,"bob"));
                String rerun=String.valueOf(runtime.rerunJob(original,confirmation,"alice").get("jobId"));
                assertNotEquals(original,rerun);assertEquals(rerun,runtime.rerunJob(original,confirmation,"alice").get("jobId"));await(runtime,rerun);
                assertEquals(original,runtime.jobRecord(rerun).get("rerunOfJobId"));assertEquals(before.get("finishedAt"),runtime.jobRecord(original).get("finishedAt"));assertEquals(originalResult,runtime.store.get(original).get("resultJson"));
                Map<String,Object> scheduleReview=runtime.createJobReplayDraft(original,"alice");
                LoadSchedule schedule=runtime.createLoadSchedule(ServerRuntime.JSON.valueToTree(Map.of("name",type,"executionType","run","packageId","p","environment","SIT","draftId",scheduleReview.get("draftId"),"timezone","UTC","cron","0 2 * * *","enabled",false)),"alice");
                assertEquals(original,schedule.sourceJobId);
                String scheduled=String.valueOf(runtime.triggerLoadSchedule(schedule.id,"alice").get("jobId"));await(runtime,scheduled);
                assertEquals(schedule.id,runtime.jobReplay(scheduled,"alice").get("originatingScheduleId"));
            }
        }
    }
    @Test void loadSnapshotsSurviveDraftExpiryAndRestartAndRejectChanges() throws Exception {
        Fixture fixture=fixture();String original;String inlineJob;String quickJob;
        var clock=new ServerRuntimeTestSupport.MutableClock();
        try(ServerRuntime runtime=fixture.open(clock)) {
            users(runtime);
            original=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","loads/smoke.yaml","environment","SIT")),"alice").get("jobId"));await(runtime,original);
            JsonNode scenario=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-load/v1.6","workloads",List.of(Map.of("id","inline","target",Map.of("type","template","id","SIMPLE"),"load",Map.of("users",1,"duration","1s")))));
            var body=ServerRuntime.JSON.createObjectNode();body.put("packageId","p");body.put("environment","SIT");body.set("scenario",scenario);
            Map<String,Object> draft=runtime.createAdvancedLoadDraft(body,"alice");
            long inspectionsBeforeSubmit=runtime.inspectionMetrics.count(att.metrics.InspectionMetrics.Stage.DISPATCH);
            inlineJob=String.valueOf(runtime.submitAdvancedLoadDraft(submit(String.valueOf(draft.get("draftId"))),"alice").get("jobId"));await(runtime,inlineJob);
            assertEquals(inspectionsBeforeSubmit+1,runtime.inspectionMetrics.count(att.metrics.InspectionMetrics.Stage.DISPATCH),
                    "Advanced Load submission should perform only its freshness check, not validate the scenario twice");
            var quick=ServerRuntime.JSON.createObjectNode();quick.put("packageId","p");quick.put("environment","SIT");quick.put("model","virtualUsers");
            quick.putObject("target").put("type","template").put("id","SIMPLE");quick.putObject("input");quick.set("load",ServerRuntime.JSON.valueToTree(Map.of("users",1,"duration","1s")));
            Map<String,Object> quickDraft=runtime.createQuickLoadDraft(quick,"alice");
            inspectionsBeforeSubmit=runtime.inspectionMetrics.count(att.metrics.InspectionMetrics.Stage.DISPATCH);
            quickJob=String.valueOf(runtime.submitQuickLoadDraft(submit(String.valueOf(quickDraft.get("draftId"))),"alice").get("jobId"));await(runtime,quickJob);
            assertEquals(inspectionsBeforeSubmit+1,runtime.inspectionMetrics.count(att.metrics.InspectionMetrics.Stage.DISPATCH),
                    "Quick Load submission should perform only its freshness check, not validate the scenario twice");
            Map<String,Object> unused=runtime.createAdvancedLoadDraft(body,"alice");clock.advance(Duration.ofMinutes(11));
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getAdvancedLoadDraft(String.valueOf(unused.get("draftId")),"alice"));
        }
        try(ServerRuntime runtime=fixture.open()) {
            for(String source:List.of(original,inlineJob,quickJob)) {
                assertEquals(true,runtime.jobReplay(source,"alice").get("available"));
                Map<String,Object> review=runtime.createJobReplayDraft(source,"alice");
                String id=String.valueOf(runtime.rerunJob(source,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId"))),"alice").get("jobId"));await(runtime,id);
                assertEquals("load",runtime.jobRecord(id).get("executionType"));
                Map<String,Object> scheduleReview=runtime.createJobReplayDraft(source,"alice");
                LoadSchedule schedule=runtime.createLoadSchedule(ServerRuntime.JSON.valueToTree(Map.of("name","LOAD replay","executionType","load","packageId","p","environment","SIT","draftId",scheduleReview.get("draftId"),"timezone","UTC","cron","0 2 * * *","enabled",false)),"alice");
                assertEquals(source,schedule.sourceJobId);
                String scheduled=String.valueOf(runtime.triggerLoadSchedule(schedule.id,"alice").get("jobId"));await(runtime,scheduled);
                runtime.deleteLoadSchedule(schedule.id,"alice");
                assertFalse(runtime.jobReplay(scheduled,"alice").containsKey("originatingScheduleId"));
                assertEquals(true,runtime.jobReplay(scheduled,"alice").get("available"));
                assertNotNull(runtime.createJobReplayDraft(scheduled,"alice").get("draftId"));
            }
            Map<String,Object> review=runtime.createJobReplayDraft(original,"alice");
            runtime.store.setAuthActive("alice",false);
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId"))),"alice"));
            runtime.store.setAuthActive("alice",true);
            Files.writeString(fixture.root.resolve("templates/SIMPLE/template.yaml"),"# changed\n",java.nio.file.StandardOpenOption.APPEND);
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.createJobReplayDraft(original,"alice"));
            assertThrows(ServerRuntime.StaleDraftException.class,()->runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId"))),"alice"));
        }
    }
    @Test void classifiedInputsAndHistoricalJobsHaveNoPersistedReplayPayload() throws Exception {
        Fixture fixture=fixture();
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);
            var definition=ServerRuntime.JSON.createObjectNode();definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");
            definition.putObject("target").put("type","template").put("id","SIMPLE");
            definition.putObject("input").putObject("inputs").put("password","NEVER-RETAIN-THIS");
            Map<String,Object> draft=runtime.createRunDraft(definition,"alice");
            String id=String.valueOf(runtime.submitRunDraft(submit(String.valueOf(draft.get("draftId"))),"alice","alice").get("jobId"));await(runtime,id);
            Map<String,String> stored=runtime.store.executionSpec(id);assertNull(stored.get("spec"));assertFalse(stored.toString().contains("NEVER-RETAIN-THIS"));
            assertEquals(false,runtime.jobReplay(id,"alice").get("available"));
            assertThrows(IllegalArgumentException.class,()->runtime.createJobReplayDraft(id,"alice"));
            Job historical=new Job("J1234567890ABCDEF","load","p","alice",new att.server.api.WorkerRequest(),new JobEvents(temp.resolve("historical.jsonl"),100));
            runtime.store.insert(historical,"{\"scenario\":\"loads/smoke.yaml\"}");
            assertEquals(false,runtime.jobReplay(historical.id,"alice").get("available"));
            assertThrows(IllegalArgumentException.class,()->runtime.createJobReplayDraft(historical.id,"alice"));
            String raw=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","loads/smoke.yaml","overrides",List.of("inputs.password=NEVER-RETAIN-RAW-OVERRIDE"))),"alice").get("jobId"));
            assertNull(runtime.store.executionSpec(raw).get("spec"),"Raw overrides must never be silently omitted from a retained definition");
            assertFalse(runtime.store.executionSpec(raw).toString().contains("NEVER-RETAIN-RAW-OVERRIDE"));
        }
    }

    @Test void packageYamlReplayHonorsPolicyWithoutRequiringBrowserInlineLoadAndDeletedJobsFailClosed() throws Exception {
        Fixture fixture=fixture();String original;
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);original=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","loads/smoke.yaml")),"alice").get("jobId"));await(runtime,original);
        }
        Files.writeString(fixture.server,Files.readString(fixture.server).replace("inlineLoad: {enabled: true}","inlineLoad: {enabled: false}"));
        try(ServerRuntime runtime=fixture.open()) {
            Map<String,Object> review=runtime.createJobReplayDraft(original,"alice");
            JsonNode confirmation=ServerRuntime.JSON.valueToTree(Map.of("intentId",review.get("intentId"),"confirm",true));
            String accepted=String.valueOf(runtime.rerunJob(original,confirmation,"alice").get("jobId"));await(runtime,accepted);
            runtime.store.deleteJob(accepted);
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.rerunJob(original,confirmation,"alice"));
            runtime.store.deleteJob(original);
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.createJobReplayDraft(original,"alice"));
        }
    }
    @Test void legacyScheduledDebugReplaysAsRunWithDebugModeAfterScheduleRemoval() throws Exception {
        Fixture fixture=fixture();
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);
            var request=ServerRuntime.JSON.createObjectNode();request.put("packageId","p");request.putObject("target").put("type","template").put("id","SIMPLE");request.putObject("input");
            Map<String,Object> draft=runtime.createDebugDraft(request,"alice");
            LoadSchedule schedule=runtime.createLoadSchedule(ServerRuntime.JSON.valueToTree(Map.of("name","Legacy Debug","packageId","p","jobType","debug","draftId",draft.get("draftId"),"timezone","UTC","cron","0 2 * * *","enabled",false)),"alice");
            String original=String.valueOf(runtime.triggerLoadSchedule(schedule.id,"alice").get("jobId"));await(runtime,original);
            assertEquals(schedule.id,runtime.jobReplay(original,"alice").get("originatingScheduleId"));
            runtime.deleteLoadSchedule(schedule.id,"alice");
            Map<String,Object> review=runtime.createJobReplayDraft(original,"alice");
            assertEquals("run",ServerRuntime.JSON.valueToTree(review.get("definition")).path("executionType").asText());
            assertEquals(true,ServerRuntime.JSON.valueToTree(review.get("definition")).path("options").path("debug").asBoolean());
            String id=String.valueOf(runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("intentId",review.get("intentId"),"confirm",true)),"alice").get("jobId"));await(runtime,id);
            assertEquals("run",runtime.jobRecord(id).get("command"));assertFalse(runtime.jobReplay(id,"alice").containsKey("originatingScheduleId"));
        }
    }
}
