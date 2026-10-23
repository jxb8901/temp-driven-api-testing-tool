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
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Public Job semantics that must remain identical on cold and prewarmed one-shot dispatch. */
class ExecutionWorkerSemanticParityTest {
    @TempDir Path temp;

    @Test void warmAndColdRunAndDebugPassAndFailHaveEquivalentStateEventsAndReports() throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("allowed"));
        Path pkg=Files.createDirectories(allowed.resolve("p"));
        Files.createDirectories(pkg.resolve("templates/SIMPLE"));
        Files.createDirectories(pkg.resolve("templates/BAD"));
        Files.createDirectories(pkg.resolve("testcase"));
        Files.writeString(pkg.resolve("templates/SIMPLE/template.yaml"),"schemaVersion: att-template/v3.6\nname: SIMPLE\ndescription: Cold and warm parity fixture\nactions:\n  message: {type: log, message: parity-once}\n");
        Files.writeString(pkg.resolve("templates/BAD/template.yaml"),"schemaVersion: att-template/v3.6\nname: BAD\ndescription: Failed cold and warm parity fixture\nactions:\n  check: {type: assert, assert: '#{1 == 2}'}\n");
        for(String template:List.of("SIMPLE","BAD"))
            Files.writeString(pkg.resolve("templates").resolve(template).resolve("debug.yaml"),
                    "schemaVersion: att-debug/v1.2\ninputs: {value: parity-once}\n");
        Path runtimeConfig=temp.resolve("runtime-home/config/config.yaml");
        Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\ntestcase: {root: testcase}\ntemplates: {root: templates}\n");
        Path workbook=pkg.resolve("testcase/cases.xlsx");
        try(XSSFWorkbook book=new XSSFWorkbook();var out=Files.newOutputStream(workbook)) {
            Sheet sheet=book.createSheet("Cases");Row header=sheet.createRow(0);
            header.createCell(0).setCellValue("ID");header.createCell(1).setCellValue("Tags");header.createCell(2).setCellValue("Template");
            Row row=sheet.createRow(1);row.createCell(0).setCellValue("C1");row.createCell(1).setCellValue("smoke");row.createCell(2).setCellValue("name: SIMPLE");
            Row failing=sheet.createRow(2);failing.createCell(0).setCellValue("C2");failing.createCell(1).setCellValue("failure");failing.createCell(2).setCellValue("name: BAD");
            book.write(out);
        }
        Files.writeString(pkg.resolve("testcase/cases.yaml"),"schemaVersion: att-sidecar/v2.2\nid: cases\nexcel:\n  sheet: default=Cases\n  caseId: ID\n  tags: Tags\nstages:\n  - key: main\n    template: Template\n    required: true\n");
        new att.api.DefaultAttService().snapshot(new att.api.SnapshotRequest(pkg,runtimeConfig,"SIT",
                List.of(Path.of("testcase/cases.xlsx")),null,java.util.Set.of(),false));

        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        RunCapture cold=execute(false,allowed,pkg,runtimeConfig,libs,"cases.default.C1","cold");
        RunCapture warm=execute(true,allowed,pkg,runtimeConfig,libs,"cases.default.C1","warm");

        assertEquivalent("PASS",cold,warm);
        assertRunReport(cold);assertRunReport(warm);

        RunCapture coldFailure=execute(false,allowed,pkg,runtimeConfig,libs,"cases.default.C2","cold-failure");
        RunCapture warmFailure=execute(true,allowed,pkg,runtimeConfig,libs,"cases.default.C2","warm-failure");
        assertEquivalent("FAIL",coldFailure,warmFailure);
        assertRunReport(coldFailure);assertRunReport(warmFailure);

        RunCapture coldDebug=executeDebug(false,allowed,pkg,runtimeConfig,libs,"SIMPLE","cold-debug");
        RunCapture warmDebug=executeDebug(true,allowed,pkg,runtimeConfig,libs,"SIMPLE","warm-debug");
        assertEquivalent("PASS",coldDebug,warmDebug);

        RunCapture coldDebugFailure=executeDebug(false,allowed,pkg,runtimeConfig,libs,"BAD","cold-debug-failure");
        RunCapture warmDebugFailure=executeDebug(true,allowed,pkg,runtimeConfig,libs,"BAD","warm-debug-failure");
        assertEquivalent("FAIL",coldDebugFailure,warmDebugFailure);
    }

    @Test void warmAndColdRunAndDebugCancellationAndToolTimeoutHaveEquivalentTerminalEvidence() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),
                "Uses a POSIX child Tool fixture");
        Path allowed=Files.createDirectories(temp.resolve("lifecycle-allowed"));
        Path pkg=Files.createDirectories(allowed.resolve("p"));
        Path templates=Files.createDirectories(pkg.resolve("templates"));
        Files.createDirectories(pkg.resolve("testcase"));
        Path tools=Files.createDirectories(pkg.resolve("tools"));
        Path holdScript=tools.resolve("hold.sh");
        Files.writeString(holdScript,"#!/bin/sh\n"
                +"printf '%s' \"$$\" > \"$ATT_ROOT_DIR/tools/child.pid\"\n"
                +"touch \"$ATT_ROOT_DIR/tools/started\"\n"
                +"exec sleep 60\n");
        holdScript.toFile().setExecutable(true);
        Files.writeString(Files.createDirectories(templates.resolve("HOLD")).resolve("template.yaml"),
                "schemaVersion: att-template/v3.6\nname: HOLD\ndescription: Cancellation parity fixture\n"
                        +"actions:\n  hold: {type: tool, call: '#{hold()}'}\n");
        Files.writeString(Files.createDirectories(templates.resolve("TIMEOUT")).resolve("template.yaml"),
                "schemaVersion: att-template/v3.6\nname: TIMEOUT\ndescription: Timeout parity fixture\n"
                        +"actions:\n  hold: {type: tool, call: '#{hold()}', timeoutMs: 300}\n");
        for(String template:List.of("HOLD","TIMEOUT"))
            Files.writeString(templates.resolve(template).resolve("debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n");
        Path runtimeConfig=temp.resolve("lifecycle-runtime/config/config.yaml");
        Files.createDirectories(runtimeConfig.getParent());
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\n"
                +"tools:\n  hold:\n    name: Hold\n    description: Hold the Worker for lifecycle parity checks\n"
                +"    command: [./tools/hold.sh]\n    stdoutFormat: text\n    arguments: {}\n");
        Path libs=Files.createDirectories(temp.resolve("lifecycle-libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))
            ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);

        TerminalCapture coldCancel=executeInterrupted(false,allowed,pkg,runtimeConfig,libs,"HOLD",true,false,"cold-cancel");
        TerminalCapture warmCancel=executeInterrupted(true,allowed,pkg,runtimeConfig,libs,"HOLD",true,false,"warm-cancel");
        assertEquals("CANCELLED",coldCancel.status);
        assertEquals("cold",coldCancel.dispatch);assertEquals("warm",warmCancel.dispatch);
        assertTerminalEquivalent(coldCancel,warmCancel,"Cancellation state, event, diagnostic, archive and history must match");

        TerminalCapture coldTimeout=executeInterrupted(false,allowed,pkg,runtimeConfig,libs,"TIMEOUT",false,false,"cold-timeout");
        TerminalCapture warmTimeout=executeInterrupted(true,allowed,pkg,runtimeConfig,libs,"TIMEOUT",false,false,"warm-timeout");
        assertNotEquals("PASS",coldTimeout.status);
        assertEquals("cold",coldTimeout.dispatch);assertEquals("warm",warmTimeout.dispatch);
        assertTerminalEquivalent(coldTimeout,warmTimeout,"Timeout state, event, diagnostic, archive and history must match");

        TerminalCapture coldDebugCancel=executeInterrupted(false,allowed,pkg,runtimeConfig,libs,"HOLD",true,true,"cold-debug-cancel");
        TerminalCapture warmDebugCancel=executeInterrupted(true,allowed,pkg,runtimeConfig,libs,"HOLD",true,true,"warm-debug-cancel");
        assertEquals("CANCELLED",coldDebugCancel.status);
        assertEquals("cold",coldDebugCancel.dispatch);assertEquals("warm",warmDebugCancel.dispatch);
        assertTerminalEquivalent(coldDebugCancel,warmDebugCancel,"DEBUG cancellation evidence must match across dispatch paths");

        TerminalCapture coldDebugTimeout=executeInterrupted(false,allowed,pkg,runtimeConfig,libs,"TIMEOUT",false,true,"cold-debug-timeout");
        TerminalCapture warmDebugTimeout=executeInterrupted(true,allowed,pkg,runtimeConfig,libs,"TIMEOUT",false,true,"warm-debug-timeout");
        assertNotEquals("PASS",coldDebugTimeout.status);
        assertEquals("cold",coldDebugTimeout.dispatch);assertEquals("warm",warmDebugTimeout.dispatch);
        assertTerminalEquivalent(coldDebugTimeout,warmDebugTimeout,"DEBUG Tool-timeout evidence must match across dispatch paths");
    }

    private void assertEquivalent(String expectedStatus,RunCapture cold,RunCapture warm) {
        assertEquals("cold",cold.dispatch);
        assertEquals("warm",warm.dispatch);
        assertEquals(cold.status,warm.status);
        assertEquals(expectedStatus,warm.status);
        assertEquals(cold.exitCode,warm.exitCode);
        assertEquals(cold.eventNames,warm.eventNames,"The event stream must keep the same order and terminal marker");
        assertTrue(warm.eventNames.contains("result"));
        assertEquals(cold.logMessages,warm.logMessages,"Executed work must emit the same log events");
        assertEquals(cold.resultPayload,warm.resultPayload,"The public terminal result must match, apart from measured execution duration");
        assertEquals(cold.diagnostic,warm.diagnostic,"The public diagnostic must match");
        assertEquals(cold.reportEntries,warm.reportEntries,"The published report inventory must match");
        assertEquals(cold.primaryReportPaths,warm.primaryReportPaths,"The primary Job Report set must match");
        assertFalse(cold.reportEntries.isEmpty(),"The fixture should publish report artifacts for both RUN and DEBUG");
        assertEquals(cold.historyStatus,warm.historyStatus,"Job history must retain the same terminal state");
    }

    private void assertRunReport(RunCapture capture) {
        assertTrue(capture.reportEntries.stream().anyMatch(path->path.endsWith("latest-run.yaml")||path.endsWith("run.yaml")),
                "The RUN fixture should publish its run report");
        assertFalse(capture.primaryReportPaths.isEmpty(),"The RUN fixture should identify primary Job Reports");
    }

    private void assertTerminalEquivalent(TerminalCapture cold,TerminalCapture warm,String message) {
        assertEquals(cold.status,warm.status,message+": status");
        assertEquals(cold.exitCode,warm.exitCode,message+": exit code");
        assertEquals(cold.eventNames,warm.eventNames,message+": event order");
        assertEquals(cold.logMessages,warm.logMessages,message+": logs");
        assertEquals(cold.resultPayload,warm.resultPayload,message+": result");
        assertEquals(cold.diagnostic,warm.diagnostic,message+": diagnostic");
        assertEquals(cold.reportEntries,warm.reportEntries,message+": report inventory");
        assertEquals(cold.primaryReportPaths,warm.primaryReportPaths,message+": primary reports");
        assertEquals(cold.historyStatus,warm.historyStatus,message+": Job history");
    }

    private RunCapture execute(boolean enabled,Path allowed,Path pkg,Path runtimeConfig,Path libs,String targetCase,String name) throws Exception {
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
            definition.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","case","id",targetCase)));
            definition.set("options",ServerRuntime.JSON.valueToTree(Map.of("debug",false)));
            Map<String,Object> draft=runtime.createRunDraft(definition,"alice");
            Map<String,Object> accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draft.get("draftId"))),"alice","alice");
            String id=String.valueOf(accepted.get("jobId"));
            Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,id);
            awaitArchive(runtime,id,15);
            return captureRun(runtime,id,result);
        }
    }

    private RunCapture executeDebug(boolean enabled,Path allowed,Path pkg,Path runtimeConfig,Path libs,String template,String name) throws Exception {
        Path data=temp.resolve(name+"-data"),serverConfig=temp.resolve(name+"-server.yaml");
        String pool=enabled?"  executionPool: {enabled: true, minIdle: 1, maxIdle: 1, heapBudgetMb: 128, packageIds: [p]}\n":"";
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n"
                +"  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  auth: {mode: local}\n"
                +"  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  heapMaxMb: 128\n"+pool
                +"packages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(pkg)+"\n");
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            if(enabled)awaitReady(runtime,15);
            var definition=ServerRuntime.JSON.createObjectNode();definition.put("packageId","p");
            definition.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id",template)));
            definition.set("input",ServerRuntime.JSON.createObjectNode());
            Map<String,Object> draft=runtime.createDebugDraft(definition,"alice");
            Map<String,Object> accepted=runtime.submitDebugDraft(ServerRuntime.JSON.valueToTree(
                    Map.of("packageId","p","draftId",draft.get("draftId"))),"alice");
            String id=String.valueOf(accepted.get("jobId"));
            Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,id);
            awaitArchive(runtime,id,15);
            return captureRun(runtime,id,result);
        }
    }

    private RunCapture captureRun(ServerRuntime runtime,String id,Map<String,Object> result) throws Exception {
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
        for(Object raw:(List<?>)index.get("primaryReports"))if(raw instanceof Map<?,?> entry)
            primaryReports.add(stableRunPath(String.valueOf(entry.get("path"))));
        List<Map<String,Object>> history=runtime.store.listOwned(100,"alice");
        Map<?,?> historyRow=history.stream().filter(row->id.equals(row.get("jobId"))).findFirst().orElseThrow();
        return new RunCapture(dispatch,String.valueOf(job.get("status")),String.valueOf(job.get("exitCode")),eventNames,
                logMessages,stableResult(result.get("result")),result.get("diagnostic"),reportEntries,primaryReports,
                String.valueOf(historyRow.get("status")));
    }

    private TerminalCapture executeInterrupted(boolean enabled,Path allowed,Path pkg,Path runtimeConfig,Path libs,
                                               String template,boolean cancel,boolean debug,String name) throws Exception {
        Path started=pkg.resolve("tools/started"),childPid=pkg.resolve("tools/child.pid");
        Files.deleteIfExists(started);Files.deleteIfExists(childPid);
        Path data=temp.resolve(name+"-data"),serverConfig=temp.resolve(name+"-server.yaml");
        String pool=enabled?"  executionPool: {enabled: true, minIdle: 1, maxIdle: 1, heapBudgetMb: 128, packageIds: [p]}\n":"";
        Files.writeString(serverConfig,"server:\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(runtimeConfig)+"\n"
                +"  dataDir: "+ServerRuntimeTestSupport.yaml(data)+"\n  auth: {mode: local}\n"
                +"  inspection: {enabled: true, maxConcurrent: 1, timeoutMs: 30000}\n"
                +"workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  heapMaxMb: 128\n"+pool
                +"packages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries:\n    p: "+ServerRuntimeTestSupport.yaml(pkg)+"\n");
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(serverConfig),libs.toString())) {
            runtime.store.createAuthUser("alice","Alice","unused","USER","csrf");
            if(enabled)awaitReady(runtime,15);
            Map<String,Object> accepted;
            if(debug) {
                var definition=ServerRuntime.JSON.createObjectNode();definition.put("packageId","p");
                definition.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id",template)));
                definition.set("input",ServerRuntime.JSON.createObjectNode());
                Map<String,Object> draft=runtime.createDebugDraft(definition,"alice");
                accepted=runtime.submitDebugDraft(ServerRuntime.JSON.valueToTree(Map.of("packageId","p","draftId",draft.get("draftId"))),"alice");
            } else {
                var definition=ServerRuntime.JSON.createObjectNode();
                definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");
                definition.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id",template)));
                definition.set("input",ServerRuntime.JSON.createObjectNode());definition.set("options",ServerRuntime.JSON.valueToTree(Map.of("debug",false)));
                Map<String,Object> draft=runtime.createRunDraft(definition,"alice");
                accepted=runtime.submitRunDraft(ServerRuntime.JSON.valueToTree(
                        Map.of("packageId","p","draftId",draft.get("draftId"))),"alice","alice");
            }
            String id=String.valueOf(accepted.get("jobId"));
            Job job=runtime.jobs.get(id);
            await(()->Files.exists(started)&&Files.exists(childPid)&&job.workerPid!=null,20);
            long workerPid=job.workerPid;
            long toolPid=Long.parseLong(Files.readString(childPid));
            if(cancel)assertEquals("CANCELLED",runtime.cancel(id,"alice"));
            Map<String,Object> result=ServerRuntimeTestSupport.awaitUnifiedJob(runtime,id);
            await(()->!ProcessHandle.of(workerPid).map(ProcessHandle::isAlive).orElse(false)
                    &&!ProcessHandle.of(toolPid).map(ProcessHandle::isAlive).orElse(false),15);
            awaitArchive(runtime,id,20);
            Map<?,?> row=(Map<?,?>)result.get("job");
            Map<?,?> performance=(Map<?,?>)row.get("performance");
            String dispatch=String.valueOf(performance.get("workerDispatch"));
            List<Map<String,Object>> events=runtime.events(id).after(0);
            List<String> eventNames=events.stream().map(event->String.valueOf(event.get("event"))).toList();
            List<String> logs=new ArrayList<>();
            for(Map<String,Object> event:events)if("log".equals(event.get("event"))) {
                Map<?,?> payload=(Map<?,?>)event.get("data");
                Object message=payload.get("console");if(message==null)message=payload.get("message");
                if(message!=null)logs.add(normalizeLog(String.valueOf(message)));
            }
            List<String> reportEntries;
            try(ZipFile archive=new ZipFile(runtime.artifactArchivePath(id).toFile())) {
                reportEntries=archive.stream().filter(entry->!entry.isDirectory())
                        .map(entry->stableRunPath(entry.getName())).sorted().toList();
            }
            Map<String,Object> index=runtime.artifactIndex(id);
            List<String> primaryReports=new ArrayList<>();
            for(Object raw:(List<?>)index.get("primaryReports"))if(raw instanceof Map<?,?> entry)
                primaryReports.add(stableRunPath(String.valueOf(entry.get("path"))));
            Map<String,Object> history=runtime.store.listOwned(100,"alice").stream()
                    .filter(item->id.equals(item.get("jobId"))).findFirst().orElseThrow();
            return new TerminalCapture(dispatch,String.valueOf(row.get("status")),String.valueOf(row.get("exitCode")),eventNames,logs,
                    stableResult(result.get("result")),stableResult(result.get("diagnostic")),reportEntries,primaryReports,
                    String.valueOf(history.get("status")));
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

    private static void await(BooleanSupplier condition,int seconds) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"Lifecycle condition did not complete within "+seconds+" seconds");
    }

    private static JsonNode stableResult(Object raw) {
        JsonNode result=ServerRuntime.JSON.valueToTree(raw);
        dropMeasuredDurations(result);
        return result;
    }

    private static String stableRunPath(String path) {
        return path.replaceAll("^\\d{8}-\\d{6}/","<run>/");
    }

    private static String normalizeLog(String message) {
        return message.replaceAll("\\b[0-9]{8}-[0-9]{6}\\b","<run>")
                .replaceAll("durationMs: ?[0-9]+","durationMs: <measured>");
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
    private record TerminalCapture(String dispatch,String status,String exitCode,List<String> eventNames,List<String> logMessages,
                                   Object resultPayload,Object diagnostic,List<String> reportEntries,List<String> primaryReportPaths,
                                   String historyStatus) { }
}
