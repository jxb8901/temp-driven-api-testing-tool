package att.server;

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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
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

        Files.createDirectories(root.resolve("load"));
        Files.writeString(root.resolve("load/smoke.yaml"),"schemaVersion: att-load/v1.6\nworkloads:\n  - id: smoke\n    target: {type: template, id: SIMPLE}\n    load: {users: 1, duration: 1s}\n");
        return new Fixture(root,server,libs);
    }
    private static void users(ServerRuntime runtime) throws Exception {
        runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
        runtime.store.createAuthUser("bob","Bob","unused","USER","csrf");
    }
    private static JsonNode submit(String draftId) {return ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draftId));}
    private static long jobCount(ServerRuntime runtime,String principal) throws Exception {
        return ((Number)runtime.store.queryJobs(JobQuery.parse(Map.of(),principal)).get("total")).longValue();
    }

    @Test void legacyRetainedExecutionSpecV1StillParses() throws Exception {
        var legacy=ServerRuntime.JSON.createObjectNode();legacy.put("schemaVersion","att-job-execution/v1");
        legacy.put("revisionPolicy","reject-package-changes");legacy.put("revision","a".repeat(64));
        var definition=legacy.putObject("definition");definition.put("executionType","load");definition.put("packageId","p");definition.putObject("scenario").put("schemaVersion","att-load/v1.9");
        JobExecutionSpec parsed=JobExecutionSpec.parse(ServerRuntime.JSON.writeValueAsString(legacy));
        assertEquals("att-job-execution/v1",parsed.value.path("schemaVersion").asText());assertEquals("p",parsed.definition().path("packageId").asText());
    }
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
            Map<String,Object> accepted=runtime.triggerLoadSchedule(ignoreId,"alice");assertEquals("submitted",accepted.get("status"),accepted.toString());
            await(runtime,String.valueOf(accepted.get("jobId")));
            assertEquals(stored,runtime.loadSchedule(ignoreId).expectedRevisionDigest);
            assertEquals(ignore.runInput,runtime.loadSchedule(ignoreId).runInput);
            assertEquals(1,runtime.store.auditOutcomes("SCHEDULE_SOURCE_REVALIDATED").size());
            assertTrue(runtime.store.auditOutcomes("SCHEDULE_SOURCE_REVALIDATED").get(0).contains("stored="+stored));
            Files.writeString(unrelated,originalUnrelated);
            clock.advance(Duration.ofMinutes(16));
            Map<String,Object> restored=runtime.triggerLoadSchedule(strictId,"alice");assertEquals("submitted",restored.get("status"),restored.toString());
            await(runtime,String.valueOf(restored.get("jobId")));
            assertNull(runtime.loadSchedule(strictId).sourceStatus,"A successful execution after restoring the pinned package must clear current stale status");
            assertEquals(stored,runtime.loadSchedule(strictId).currentRevisionDigest);
            assertEquals(1,runtime.store.auditOutcomes("SCHEDULE_SOURCE_RESTORED").size());
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
                    "scenario","load/smoke.yaml","timezone","UTC","cron","0 2 * * *","enabled",true));
            LoadSchedule strict=runtime.createLoadSchedule(strictRequest,"alice");strictId=strict.id;strictRevision=strict.expectedRevisionDigest;
            assertNotNull(strictRevision,"New file-backed schedules must pin their validated package revision");
            var pauseRequest=((com.fasterxml.jackson.databind.node.ObjectNode)strictRequest).deepCopy().put("name","Paused file Load").put("packageChangePolicy","pause");
            LoadSchedule pause=runtime.createLoadSchedule(pauseRequest,"alice");pauseId=pause.id;
            assertNotNull(pause.expectedRevisionDigest);
            Path scenario=fixture.root.resolve("load/smoke.yaml");
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
                assertThrows(ServerRuntime.JobConflictException.class,()->runtime.submitRunDraft(submit(String.valueOf(review.get("draftId"))),"alice","alice"),
                        "Replay editor drafts must use the source Job confirmation intent to preserve lineage and idempotency");
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
            original=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","load/smoke.yaml","environment","SIT")),"alice").get("jobId"));await(runtime,original);
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
            ServerRuntime.JobConflictException mismatch=assertThrows(ServerRuntime.JobConflictException.class,()->runtime.createJobReplayDraft(original,"alice"));
            assertEquals("ATT-SERVER-REPLAY-REVISION-CONFLICT",mismatch.code);
            ServerRuntime.JobConflictException stale=assertThrows(ServerRuntime.JobConflictException.class,()->runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId"))),"alice"));
            assertEquals("ATT-SERVER-REPLAY-REVISION-CONFLICT",stale.code);
        }
    }

    @Test void currentPackageReplayCreatesEditableBoundDraftAndKeepsPathBackedLoadIndependent() throws Exception {
        Fixture fixture=fixture();String original;String originalResult;String originalRevision;
        Files.writeString(fixture.root.resolve("load/smoke.yaml"),"schemaVersion: att-load/v1.9\n"
                +"load: {warmup: 1s, rampUp: 2s, duration: 1s, rampDown: 1s}\n"
                +"workloads:\n"
                +"  - id: smoke-a\n    target: {type: template, id: SIMPLE}\n    load: {users: 1}\n"
                +"  - id: smoke-b\n    mix: [{id: simple, weight: 100, target: {type: template, id: SIMPLE}}]\n    load: {users: 1}\n"
                +"thresholds: {minThroughput: '>= 0/s'}\nevidence: {mode: metrics, maxSamples: 10}\n");
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);
            original=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","load/smoke.yaml","environment","SIT")),"alice").get("jobId"));await(runtime,original);
            originalResult=(String)runtime.store.get(original).get("resultJson");originalRevision=JobExecutionSpec.parse(runtime.store.executionSpec(original).get("spec")).revision();
            Files.writeString(fixture.root.resolve("templates/SIMPLE/template.yaml"),"# compatible current package change\n",java.nio.file.StandardOpenOption.APPEND);
            ServerRuntime.JobConflictException strict=assertThrows(ServerRuntime.JobConflictException.class,()->runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","strict"))));
            assertEquals("ATT-SERVER-REPLAY-REVISION-CONFLICT",strict.code);
            Map<String,Object> edits=Map.of("load",Map.of("warmup","3s","rampUp","5s","duration","24h","rampDown","2s"),
                    "workloads",List.of(Map.of("id","smoke-a","load",Map.of("users",2)),Map.of("id","smoke-b","load",Map.of("users",3))));
            Map<String,Object> review=runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current","overrides",edits)));
            assertEquals("att-job-replay-draft/v1",review.get("schemaVersion"));assertNotEquals(originalRevision,review.get("currentRevision"));
            assertEquals(originalRevision,review.get("originalRevision"));
            assertEquals("1s",ServerRuntime.JSON.valueToTree(review.get("originalDefinition")).path("scenario").path("load").path("duration").asText());
            assertEquals("24h",ServerRuntime.JSON.valueToTree(review.get("definition")).path("scenario").path("load").path("duration").asText());
            String draftRef=String.valueOf(review.get("draftRef"));assertTrue(draftRef.matches("[RAC][0-9A-F]{32}"));
            Map<String,Object> editor=runtime.getJobReplayEditorDraft(original,draftRef,"alice");
            assertEquals("load/smoke.yaml",editor.get("sourcePath"));
            assertEquals("1s",ServerRuntime.JSON.valueToTree(editor.get("originalDefinition")).path("scenario").path("load").path("duration").asText());
            JsonNode scenario=ServerRuntime.JSON.valueToTree(editor.get("definition")).path("scenario");
            assertEquals("24h",scenario.path("load").path("duration").asText());
            assertEquals(2,scenario.path("workloads").get(0).path("load").path("users").asInt());
            assertEquals(3,scenario.path("workloads").get(1).path("load").path("users").asInt());
            assertEquals("simple",scenario.path("workloads").get(1).path("mix").get(0).path("id").asText());
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.getJobReplayEditorDraft(original,draftRef,"bob"));
            assertApiError(apiRequest(runtime,"GET","/jobs/"+original+"/replay-draft/"+draftRef,"bob",Map.of()),404,"ATT-SERVER-NOT-FOUND");
            long jobsBeforeCrossOwnerSubmit=jobCount(runtime,"alice");
            assertThrows(ServerRuntime.NotFoundException.class,()->runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId"),"sourcePolicy","current")),"bob"));
            assertApiError(apiRequest(runtime,"POST","/jobs/"+original+"/rerun","bob",Map.of("confirm",true,"intentId",review.get("intentId"),"sourcePolicy","current")),404,"ATT-SERVER-NOT-FOUND");
            assertEquals(jobsBeforeCrossOwnerSubmit,jobCount(runtime,"alice"));
            assertThrows(IllegalArgumentException.class,()->runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current","overrides",Map.of("workloads",List.of(Map.of("id","smoke-a","load",Map.of("arrivalRate","2/s"))))))));
            Map<String,Object> rerun=runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId"),"sourcePolicy","current")),"alice");
            String rerunId=String.valueOf(rerun.get("jobId"));assertEquals(original,runtime.jobRecord(rerunId).get("rerunOfJobId"));
            assertEquals(rerunId,runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",review.get("intentId"),"sourcePolicy","current")),"alice").get("jobId"));
            JobExecutionSpec rerunSpec=JobExecutionSpec.parse(runtime.store.executionSpec(rerunId).get("spec"));
            assertEquals("current",rerunSpec.value.path("replay").path("sourcePolicy").asText());
            assertEquals(originalRevision,rerunSpec.value.path("replay").path("originalRevision").asText());
            assertEquals(review.get("currentRevision"),rerunSpec.value.path("replay").path("effectiveRevision").asText());
            assertEquals("24h",rerunSpec.definition().path("scenario").path("load").path("duration").asText());
            assertEquals(originalResult,runtime.store.get(original).get("resultJson"));
            assertEquals(originalRevision,JobExecutionSpec.parse(runtime.store.executionSpec(original).get("spec")).revision());

            Map<String,Object> staleReview=runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current")));
            long jobsBeforeStaleSubmit=jobCount(runtime,"alice");
            Files.writeString(fixture.root.resolve("templates/SIMPLE/template.yaml"),"# changed after validation\n",java.nio.file.StandardOpenOption.APPEND);
            ServerRuntime.JobConflictException stale=assertThrows(ServerRuntime.JobConflictException.class,()->runtime.rerunJob(original,ServerRuntime.JSON.valueToTree(Map.of("confirm",true,"intentId",staleReview.get("intentId"),"sourcePolicy","current")),"alice"));
            assertEquals("ATT-SERVER-REPLAY-DRAFT-STALE",stale.code);
            assertEquals(jobsBeforeStaleSubmit,jobCount(runtime,"alice"),"A stale validated replay must not create a Job");
            assertEquals(originalResult,runtime.store.get(original).get("resultJson"));
        }
    }

    @Test void removedRunTargetIssuesAuthorizedEditableDraftWithResourceDiagnosticAndNoGuessedTarget() throws Exception {
        Fixture fixture=fixture();
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);
            var definition=ServerRuntime.JSON.createObjectNode();definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");definition.put("environment","SIT");
            definition.putObject("target").put("type","template").put("id","SIMPLE");definition.putObject("input");definition.putObject("options").put("debug",false);
            Map<String,Object> validated=runtime.createRunDraft(definition,"alice");
            String original=String.valueOf(runtime.submitRunDraft(submit(String.valueOf(validated.get("draftId"))),"alice","alice").get("jobId"));await(runtime,original);
            Files.delete(fixture.root.resolve("templates/SIMPLE/template.yaml"));long count=jobCount(runtime,"alice");
            Map<String,Object> invalid=runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current")));
            assertEquals("invalid",invalid.get("validationState"));assertEquals("ATT-SERVER-REPLAY-RESOURCE-MISSING",invalid.get("errorCode"));
            assertNull(invalid.get("intentId"),"An unresolved target cannot receive a runnable confirmation intent");
            assertEquals("SIMPLE",ServerRuntime.JSON.valueToTree(invalid.get("definition")).path("target").path("id").asText());
            assertFalse(((List<?>)invalid.get("diagnostics")).isEmpty());
            Map<String,Object> editor=runtime.getJobReplayEditorDraft(original,String.valueOf(invalid.get("draftRef")),"alice");
            assertEquals("ATT-SERVER-REPLAY-RESOURCE-MISSING",editor.get("errorCode"));
            assertEquals("SIMPLE",ServerRuntime.JSON.valueToTree(editor.get("definition")).path("target").path("id").asText());
            assertEquals(count,jobCount(runtime,"alice"));
        }
    }

    @Test void invalidLoadSchemaReturnsFieldDiagnosticsAndNoConfirmationIntent() throws Exception {
        Fixture fixture=fixture();
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);String original=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","load/smoke.yaml","environment","SIT")),"alice").get("jobId"));await(runtime,original);
            Map<String,Object> invalid=runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current","overrides",Map.of("load",Map.of("duration","not-a-duration")))));
            assertEquals("invalid",invalid.get("validationState"));assertEquals("ATT-SERVER-REPLAY-VALIDATION-FAILED",invalid.get("errorCode"));
            assertNull(invalid.get("intentId"));assertFalse(((List<?>)invalid.get("diagnostics")).isEmpty());
            assertTrue(invalid.get("diagnostics").toString().contains("duration"),invalid.get("diagnostics").toString());
        }
    }

    @Test void replayDraftCapacityIsA429BoundaryBeforePackageValidation() throws Exception {
        Fixture fixture=fixture();
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);String original=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","load/smoke.yaml")),"alice").get("jobId"));await(runtime,original);
            var field=ServerRuntime.class.getDeclaredField("replayReviews");field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,Object> reviews=(Map<String,Object>)field.get(runtime);
            Class<?> reviewType=java.util.Arrays.stream(ServerRuntime.class.getDeclaredClasses()).filter(type->type.getSimpleName().equals("ReplayReview")).findFirst().orElseThrow();
            var constructor=reviewType.getDeclaredConstructors()[0];constructor.setAccessible(true);
            var definition=ServerRuntime.JSON.createObjectNode();definition.put("executionType","load");definition.put("packageId","p");definition.putObject("scenario").put("schemaVersion","att-load/v1.9");
            for(int i=0;i<ServerRuntime.MAX_DRAFTS_PER_PRINCIPAL;i++) {
                String ref="C"+String.format(java.util.Locale.ROOT,"%032X",i+1);
                Object review=constructor.newInstance(original,"alice",ref,"p","load","current","a".repeat(64),"a".repeat(64),"digest",null,
                        definition.deepCopy(),definition.deepCopy(),null,Map.of(),null,List.of(),Instant.MAX);
                reviews.put("test-"+i,review);
            }
            assertThrows(ServerRuntime.DraftCapacityException.class,()->runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current"))));
            assertApiError(apiRequest(runtime,"POST","/jobs/"+original+"/replay-draft","alice",Map.of("sourcePolicy","current")),429,"ATT-SERVER-DRAFT-CAPACITY");
        }
    }

    @Test void packageDefaultEnvironmentIsResolvedAgainAndMissingProfileBlocksReplay() throws Exception {
        Fixture fixture=fixture();String original;
        Path packageYaml=fixture.root.resolve("package.yaml");Files.writeString(packageYaml,"schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n");
        Path runtimeConfig=fixture.server.getParent().resolve("unified-runtime/config/config.yaml");
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);var definition=ServerRuntime.JSON.createObjectNode();definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");
            definition.putObject("target").put("type","template").put("id","SIMPLE");definition.putObject("input");definition.putObject("options").put("debug",false);
            Map<String,Object> validated=runtime.createRunDraft(definition,"alice");
            original=String.valueOf(runtime.submitRunDraft(submit(String.valueOf(validated.get("draftId"))),"alice","alice").get("jobId"));await(runtime,original);
            Map<String,String> stored=runtime.store.executionSpec(original);JobExecutionSpec spec=JobExecutionSpec.parse(stored.get("spec"));
            assertEquals("package",spec.configuration().get("environmentOrigin"));assertEquals("UAT",spec.configuration().get("environment"));
            String config=Files.readString(runtimeConfig);Files.writeString(runtimeConfig,config.replace("environments: {SIT: {}, UAT: {}}","environments: {SIT: {}}"));
            Map<String,Object> invalid=runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current")));
            assertEquals("invalid",invalid.get("validationState"));assertEquals("ATT-SERVER-REPLAY-VALIDATION-FAILED",invalid.get("errorCode"));
            assertNull(invalid.get("intentId"));assertFalse(((List<?>)invalid.get("diagnostics")).isEmpty());
            assertTrue(invalid.get("diagnostics").toString().toLowerCase(java.util.Locale.ROOT).contains("environment"),invalid.get("diagnostics").toString());
        }
    }

    @Test void replayOverridesPreserveArrivalRateWorkloadIdentityAndCommonPhases() {
        var source=ServerRuntime.JSON.createObjectNode();source.put("executionType","load");source.put("packageId","p");
        var scenario=source.putObject("scenario");scenario.put("schemaVersion","att-load/v1.9");
        var common=scenario.putObject("load");common.put("warmup","2s");common.put("rampUp","3s");common.put("duration","30m");common.put("rampDown","4s");
        var workloads=scenario.putArray("workloads");
        for(String id:List.of("primary","mixed")) {
            var workload=workloads.addObject();workload.put("id",id);workload.putObject("load").put("arrivalRate","2/s").put("maxConcurrent",4).put("overloadPolicy","drop");
            if("mixed".equals(id))workload.putArray("mix").addObject().put("id","checkout-slot").put("weight",100).putObject("target").put("type","template").put("id","SIMPLE");
        }
        scenario.putObject("thresholds").put("p95Ms",800);scenario.putObject("evidence").put("mode","failures");
        Map<String,Object> edits=Map.of("load",Map.of("duration","24h"),"workloads",List.of(
                Map.of("id","primary","load",Map.of("arrivalRate","5/s","maxConcurrent",8,"overloadPolicy","wait")),
                Map.of("id","mixed","load",Map.of("arrivalRate","3/s","maxConcurrent",6))),
                "thresholds",Map.of("p95Ms",500),"evidence",Map.of("mode","all","maxSamples",20));
        var updated=JobReplayOverrides.apply(source,ServerRuntime.JSON.valueToTree(edits));
        var result=updated.path("scenario");assertEquals("24h",result.path("load").path("duration").asText());
        assertEquals("2s",result.path("load").path("warmup").asText());assertEquals("3s",result.path("load").path("rampUp").asText());assertEquals("4s",result.path("load").path("rampDown").asText());
        assertEquals(2,result.path("workloads").size());assertEquals("mixed",result.path("workloads").get(1).path("id").asText());
        assertEquals("checkout-slot",result.path("workloads").get(1).path("mix").get(0).path("id").asText());
        assertEquals("5/s",result.path("workloads").get(0).path("load").path("arrivalRate").asText());
        assertEquals(8,result.path("workloads").get(0).path("load").path("maxConcurrent").asInt());
        assertFalse(result.path("workloads").get(0).path("load").has("users"));
        assertEquals(500,result.path("thresholds").path("p95Ms").asInt());assertEquals("all",result.path("evidence").path("mode").asText());
        assertThrows(IllegalArgumentException.class,()->JobReplayOverrides.apply(source,ServerRuntime.JSON.valueToTree(Map.of(
                "workloads",List.of(Map.of("id","primary","load",Map.of("users",10)))))));
    }

    @Test void replayDraftReferenceExpiresAndIsLostOnServerRestart() throws Exception {
        Fixture fixture=fixture();String original;String draftRef;String originalResult;String originalSpec;var clock=new ServerRuntimeTestSupport.MutableClock();
        try(ServerRuntime runtime=fixture.open(clock)) {
            users(runtime);original=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","load/smoke.yaml")),"alice").get("jobId"));await(runtime,original);
            Map<String,Object> draft=runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current")));draftRef=String.valueOf(draft.get("draftRef"));
            originalResult=(String)runtime.store.get(original).get("resultJson");originalSpec=runtime.store.executionSpec(original).get("spec");
            assertThrows(ServerRuntime.ReplayDraftExpiredException.class,()->runtime.getJobReplayEditorDraft(original,"CFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF","alice"));
            assertApiError(apiRequest(runtime,"GET","/jobs/"+original+"/replay-draft/CFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF","alice",Map.of()),410,"ATT-SERVER-REPLAY-DRAFT-EXPIRED");
        }
        try(ServerRuntime runtime=fixture.open(clock)) {
            assertThrows(ServerRuntime.ReplayDraftExpiredException.class,()->runtime.getJobReplayEditorDraft(original,draftRef,"alice"));
            assertApiError(apiRequest(runtime,"GET","/jobs/"+original+"/replay-draft/"+draftRef,"alice",Map.of()),410,"ATT-SERVER-REPLAY-DRAFT-EXPIRED");
            assertEquals(originalResult,runtime.store.get(original).get("resultJson"));
            assertEquals(originalSpec,runtime.store.executionSpec(original).get("spec"));
            assertEquals(true,runtime.jobReplay(original,"alice").get("available"));
            Map<String,Object> replacement=runtime.createJobReplayDraft(original,"alice",ServerRuntime.JSON.valueToTree(Map.of("sourcePolicy","current")));
            String replacementRef=String.valueOf(replacement.get("draftRef"));clock.advance(Duration.ofMinutes(11));
            assertThrows(ServerRuntime.ReplayDraftExpiredException.class,()->runtime.getJobReplayEditorDraft(original,replacementRef,"alice"));
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
            ServerRuntime.ReplayInputMissingException sensitive=assertThrows(ServerRuntime.ReplayInputMissingException.class,()->runtime.createJobReplayDraft(id,"alice"));
            assertTrue(sensitive.getMessage().toLowerCase(java.util.Locale.ROOT).contains("safely retained"));
            assertFalse(sensitive.getMessage().contains("NEVER-RETAIN-THIS"));
            assertApiError(apiRequest(runtime,"POST","/jobs/"+id+"/replay-draft","alice",Map.of()),422,"ATT-SERVER-REPLAY-INPUT-MISSING");
            Job historical=new Job("J1234567890ABCDEF","load","p","alice",new att.server.api.WorkerRequest(),new JobEvents(temp.resolve("historical.jsonl"),100));
            runtime.store.insert(historical,"{\"scenario\":\"load/smoke.yaml\"}");
            assertEquals(false,runtime.jobReplay(historical.id,"alice").get("available"));
            assertThrows(ServerRuntime.ReplayInputMissingException.class,()->runtime.createJobReplayDraft(historical.id,"alice"));
            String raw=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","load/smoke.yaml","overrides",List.of("inputs.password=NEVER-RETAIN-RAW-OVERRIDE"))),"alice").get("jobId"));
            assertNull(runtime.store.executionSpec(raw).get("spec"),"Raw overrides must never be silently omitted from a retained definition");
            assertFalse(runtime.store.executionSpec(raw).toString().contains("NEVER-RETAIN-RAW-OVERRIDE"));
        }
    }

    @Test void packageYamlReplayHonorsPolicyWithoutRequiringBrowserInlineLoadAndDeletedJobsFailClosed() throws Exception {
        Fixture fixture=fixture();String original;
        try(ServerRuntime runtime=fixture.open()) {
            users(runtime);original=String.valueOf(runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","scenario","load/smoke.yaml")),"alice").get("jobId"));await(runtime,original);
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

    private static void assertApiError(Map<String,Object> response,int status,String code) {
        assertEquals(status,response.get("status"));
        assertEquals(code,ServerRuntime.JSON.valueToTree(response.get("body")).path("error").path("code").asText());
    }

    private static Map<String,Object> apiRequest(ServerRuntime runtime,String method,String path,String principal,Map<String,Object> body) throws Exception {
        ServletContext context=servletProxy(ServletContext.class,(self,called,args)->"getAttribute".equals(called.getName())&&ServerBootstrap.RUNTIME.equals(args[0])?runtime:null);
        ApiServlet servlet=new ApiServlet();
        servlet.init(servletProxy(ServletConfig.class,(self,called,args)->"getServletContext".equals(called.getName())?context:null));
        byte[] requestBytes=ServerRuntime.JSON.writeValueAsBytes(body);ByteArrayInputStream requestBytesIn=new ByteArrayInputStream(requestBytes);
        ServletInputStream requestStream=new ServletInputStream() {
            @Override public boolean isFinished(){return requestBytesIn.available()==0;}
            @Override public boolean isReady(){return true;}
            @Override public void setReadListener(ReadListener listener){}
            @Override public int read(){return requestBytesIn.read();}
        };
        HttpServletRequest request=servletProxy(HttpServletRequest.class,(self,called,args)->{
            switch(called.getName()) {
                case "getPathInfo":return path;
                case "getServletContext":return context;
                case "getAttribute":return AuthService.IDENTITY.equals(args[0])?principal:null;
                case "getContentType":return "application/json";
                case "getInputStream":return requestStream;
                case "getHeader":return null;
                case "getContextPath":return "";
                case "getRemoteAddr":return "127.0.0.1";
                case "getMethod":return method;
                default:return null;
            }
        });
        ByteArrayOutputStream responseBytes=new ByteArrayOutputStream();
        ServletOutputStream responseStream=new ServletOutputStream() {
            @Override public boolean isReady(){return true;}
            @Override public void setWriteListener(WriteListener listener){}
            @Override public void write(int value){responseBytes.write(value);}
        };
        Map<String,Object> response=new java.util.LinkedHashMap<>();response.put("status",200);
        HttpServletResponse responseProxy=servletProxy(HttpServletResponse.class,(self,called,args)->{
            if("setStatus".equals(called.getName()))response.put("status",args[0]);
            if("getOutputStream".equals(called.getName()))return responseStream;
            return null;
        });
        if("POST".equals(method))servlet.doPost(request,responseProxy);else servlet.doGet(request,responseProxy);
        response.put("body",ServerRuntime.JSON.readTree(responseBytes.toByteArray()));return response;
    }

    private static <T>T servletProxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
}
