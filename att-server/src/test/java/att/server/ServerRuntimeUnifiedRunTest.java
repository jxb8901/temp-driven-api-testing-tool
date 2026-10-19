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

/** Unified RUN cases. A separate class so Surefire can run them on their own JVM. */
class ServerRuntimeUnifiedRunTest {
    @TempDir Path temp;

    @Test void unifiedRunExecutesAllTargetsAndFreezesCaseSelectionsAcrossEnvironments() throws Exception {
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
        Files.writeString(server,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("unified-data"))+"\n  javaExecutable: "+ServerRuntimeTestSupport.yaml(javaBin)+"\n  auth: {mode: local}\n  inspection:\n    enabled: true\n    timeoutMs: 30000\n  scheduling:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\npackages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(root)+"]\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(root)+"\n");
        Path libs=Files.createDirectory(temp.resolve("unified-libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(server),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            runtime.store.createAuthUser("legacy-admin","Legacy administrator","unused","ADMIN","csrf");
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
                boolean migratedLegacyAdmin="SIT".equals(environment)&&"case".equals(type);
                if(migratedLegacyAdmin) {
                    scheduled.createdBy="legacy-admin";scheduled.executionPrincipal=null;
                    scheduled.executionIdentityMigratedFrom=null;runtime.store.saveSchedule(scheduled);
                }
                Map<String,Object> occurrence=runtime.triggerLoadSchedule(scheduled.id,"alice");
                if(migratedLegacyAdmin) {
                    LoadSchedule migrated=runtime.loadSchedule(scheduled.id);
                    assertEquals("legacy-admin",migrated.createdBy,"Keep the original creator for audit");
                    assertEquals("alice",migrated.executionPrincipal);
                    assertEquals("legacy-admin",migrated.executionIdentityMigratedFrom);
                    assertEquals("alice",migrated.executionIdentityMigratedBy);
                    assertNotNull(migrated.executionIdentityMigratedAt);
                    String url="jdbc:h2:file:"+temp.resolve("unified-data/db/att-server").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
                    try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var audit=connection.prepareStatement(
                            "SELECT COUNT(*) FROM audit WHERE principal=? AND action=? AND outcome=?")) {
                        audit.setString(1,"alice");audit.setString(2,"SCHEDULE_IDENTITY_MIGRATE");audit.setString(3,"LEGACY_ADMIN_TO_USER");
                        try(var rows=audit.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                        try(var execution=connection.prepareStatement("SELECT COUNT(*) FROM audit WHERE principal=? AND action=? AND outcome=?")) {
                            execution.setString(1,"alice");execution.setString(2,"SCHEDULE_EXECUTE");execution.setString(3,"ACCEPTED");
                            try(var rows=execution.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                        }
                    }
                }
                Map<String,Object> scheduledResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,String.valueOf(occurrence.get("jobId")));
                assertEquals("PASS",((Map<?,?>)scheduledResult.get("job")).get("status"),environment+" scheduled "+type+" "+scheduledResult);
                assertEquals("alice",((Map<?,?>)scheduledResult.get("job")).get("principal"));
                runtime.deleteLoadSchedule(scheduled.id,"alice");
                Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draftId)),"alice","alice");
                Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,String.valueOf(accepted.get("jobId")));
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
            Map<String,Object> allResult=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,String.valueOf(accepted.get("jobId")));
            assertEquals("FAIL",((Map<?,?>)allResult.get("job")).get("status"),allResult.toString());
            Path completedOutput=temp.resolve("unified-data").resolve("jobs").resolve(String.valueOf(accepted.get("jobId"))).resolve("output");
            assertTrue(Files.isRegularFile(completedOutput.resolve("latest-run.yaml")),allResult.toString());
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
            assertEquals("PASS",((Map<?,?>)ServerRuntimeTestSupport.awaitUnifiedJob(runtime,String.valueOf(oneSlotAccepted.get("jobId"))).get("job")).get("status"));
            assertDoesNotThrow(()->runtime.createDebugDraft(debugInput,"quota-boundary"),"A resource RUN draft must occupy and release one quota slot");
        }
    }
}
