package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Public Job semantics that must remain identical on cold and prewarmed one-shot dispatch. */
class ExecutionWorkerSemanticParityTest {
    @TempDir Path temp;

    @Test void warmAndColdSuccessfulRunsHaveEquivalentStateEventsAndReports() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("allowed"));
        Path pkg=Files.createDirectories(allowed.resolve("p"));
        Files.createDirectories(pkg.resolve("templates/SIMPLE"));
        Files.createDirectories(pkg.resolve("testcase"));
        Files.writeString(pkg.resolve("templates/SIMPLE/template.yaml"),"schemaVersion: att-template/v3.6\nname: SIMPLE\ndescription: Cold and warm parity fixture\nactions:\n  message: {type: log, message: parity-once}\n");
        Path runtimeConfig=temp.resolve("runtime-home/config/config.yaml");
        Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\ntestcase: {root: testcase}\ntemplates: {root: templates}\n");
        Path workbook=pkg.resolve("testcase/cases.xlsx");
        try(XSSFWorkbook book=new XSSFWorkbook();var out=Files.newOutputStream(workbook)) {
            Sheet sheet=book.createSheet("Cases");Row header=sheet.createRow(0);
            header.createCell(0).setCellValue("ID");header.createCell(1).setCellValue("Tags");header.createCell(2).setCellValue("Template");
            Row row=sheet.createRow(1);row.createCell(0).setCellValue("C1");row.createCell(1).setCellValue("smoke");row.createCell(2).setCellValue("name: SIMPLE");
            book.write(out);
        }
        Files.writeString(pkg.resolve("testcase/cases.yaml"),"schemaVersion: att-sidecar/v2.2\nid: cases\nexcel:\n  sheet: default=Cases\n  caseId: ID\n  tags: Tags\nstages:\n  - key: main\n    template: Template\n    required: true\n");
        new att.api.DefaultAttService().snapshot(new att.api.SnapshotRequest(pkg,runtimeConfig,"SIT",
                List.of(Path.of("testcase/cases.xlsx")),null,java.util.Set.of(),false));

        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        RunCapture cold=execute(false,allowed,pkg,runtimeConfig,libs,"cold");
        RunCapture warm=execute(true,allowed,pkg,runtimeConfig,libs,"warm");

        assertEquals("cold",cold.dispatch);
        assertEquals("warm",warm.dispatch);
        assertEquals(cold.status,warm.status);
        assertEquals("PASS",warm.status);
        assertEquals(cold.exitCode,warm.exitCode);
        assertEquals(cold.eventNames,warm.eventNames,"The event stream must keep the same order and terminal marker");
        assertTrue(warm.eventNames.contains("result"));
        assertEquals(cold.logMessages,warm.logMessages,"Executed work must emit the same log events");
        assertEquals(cold.resultPayload,warm.resultPayload,"The public terminal result must match, apart from measured execution duration");
        assertEquals(cold.diagnostic,warm.diagnostic,"The public diagnostic must match");
        assertEquals(cold.reportEntries,warm.reportEntries,"The published report inventory must match");
        assertEquals(cold.primaryReportPaths,warm.primaryReportPaths,"The primary Job Report set must match");
        assertTrue(cold.reportEntries.stream().anyMatch(path->path.endsWith("latest-run.yaml")||path.endsWith("run.yaml")),
                "The fixture should publish a report artifact");
        assertEquals(cold.historyStatus,warm.historyStatus,"Job history must retain the same terminal state");
    }

    private RunCapture execute(boolean enabled,Path allowed,Path pkg,Path runtimeConfig,Path libs,String name) throws Exception {
        Path data=temp.resolve(name+"-data");
        Path serverConfig=temp.resolve(name+"-server.yaml");
        String pool=enabled?"  executionPool: {enabled: true, minIdle: 1, maxIdle: 1, heapBudgetMb: 128, packageIds: [p]}\n":"";
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n"
                +"  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  auth: {mode: local}\n"
                +"  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  heapMaxMb: 128\n"+pool
                +"packages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(pkg)+"\n");
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            if(enabled)awaitReady(runtime,15);
            var definition=ServerRuntime.JSON.createObjectNode();
            definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");
            definition.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","case","id","cases.default.C1")));
            definition.set("options",ServerRuntime.JSON.valueToTree(Map.of("debug",false)));
            Map<String,Object> draft=runtime.createRunDraft(definition,"alice");
            Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draft.get("draftId"))),"alice","alice");
            String id=String.valueOf(accepted.get("jobId"));
            Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,id);
            awaitArchive(runtime,id,15);
            Map<?,?> job=(Map<?,?>)result.get("job");
            Map<?,?> performance=(Map<?,?>)job.get("performance");
            String dispatch=String.valueOf(performance.get("workerDispatch"));
            List<Map<String,Object>> events=runtime.events(id).after(0);
            List<String> eventNames=events.stream().map(event->String.valueOf(event.get("event"))).toList();
            List<String> logMessages=new ArrayList<>();
            for(Map<String,Object> event:events)if("log".equals(event.get("event"))) {
                Map<?,?> payload=(Map<?,?>)event.get("data");
                Object message=payload.get("console");if(message==null)message=payload.get("message");
                if(message!=null&&String.valueOf(message).contains("parity-once"))logMessages.add("parity-once");
            }
            List<String> reportEntries;
            try(ZipFile archive=new ZipFile(runtime.artifactArchivePath(id).toFile())) {
                reportEntries=archive.stream().filter(entry->!entry.isDirectory()).map(entry->stableRunPath(entry.getName())).sorted().toList();
            }
            Map<String,Object> index=runtime.artifactIndex(id);
            List<String> primaryReports=new ArrayList<>();
            for(Object raw:(List<?>)index.get("primaryReports")) {
                if(raw instanceof Map<?,?> entry)primaryReports.add(stableRunPath(String.valueOf(entry.get("path"))));
            }
            List<Map<String,Object>> history=runtime.store.listOwned(100,"alice");
            Map<?,?> historyRow=history.stream().filter(row->id.equals(row.get("jobId"))).findFirst().orElseThrow();
            Object resultPayload=stableResult(result.get("result"));
            Object diagnostic=result.get("diagnostic");
            return new RunCapture(dispatch,String.valueOf(job.get("status")),String.valueOf(job.get("exitCode")),eventNames,
                    logMessages,resultPayload,diagnostic,reportEntries,primaryReports,String.valueOf(historyRow.get("status")));
        }
    }

    private static void awaitReady(ServerRuntime runtime,int seconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline) {
            Object raw=runtime.executionPoolStatistics().get("ready");
            if(raw instanceof Number ready&&ready.intValue()==1)return;
            Thread.sleep(25);
        }
        fail("The prewarm Worker did not become READY");
    }

    private static void awaitArchive(ServerRuntime runtime,String id,int seconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline&& !"READY".equals(runtime.artifactIndex(id).get("archiveStatus")))Thread.sleep(20);
        assertEquals("READY",runtime.artifactIndex(id).get("archiveStatus"));
    }

    private static JsonNode stableResult(Object raw) {
        JsonNode result=ServerRuntime.JSON.valueToTree(raw);
        dropMeasuredDurations(result);
        return result;
    }

    private static String stableRunPath(String path) {
        return path.replaceAll("^\\d{8}-\\d{6}/","<run>/");
    }

    private static void dropMeasuredDurations(JsonNode node) {
        if(node instanceof ObjectNode object) {
            object.remove(List.of("durationMs","executionId","runId","startedAt","runStartedAt","finishedAt"));
            Iterator<Map.Entry<String,JsonNode>> fields=object.fields();
            while(fields.hasNext()) {
                Map.Entry<String,JsonNode> field=fields.next();
                if(field.getValue().isTextual())object.put(field.getKey(),field.getValue().asText().replaceAll("\\b[0-9]{8}-[0-9]{6}\\b","<run>"));
                else dropMeasuredDurations(field.getValue());
            }
        } else if(node.isArray())node.forEach(ExecutionWorkerSemanticParityTest::dropMeasuredDurations);
    }

    private record RunCapture(String dispatch,String status,String exitCode,List<String> eventNames,List<String> logMessages,
                              Object resultPayload,Object diagnostic,List<String> reportEntries,List<String> primaryReportPaths,
                              String historyStatus) { }
}
