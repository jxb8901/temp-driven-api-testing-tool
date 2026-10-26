package att.worker;

import att.api.DebugResult;
import att.api.LoadResult;
import att.api.OperationResult;
import att.api.RunResult;
import att.api.ValidateResult;
import att.server.api.LoadTerminalResult;
import att.server.api.internal.WorkerProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkerProtocolEmissionTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void oversizedRunDebugAndLoadResultsRetainOutcomeMetricsAndArtifactDetails() throws Exception {
        StringBuilder value=new StringBuilder();
        for(int i=0;i<400000;i++)value.append('\u4e2d');
        Map<String,Object> details=Collections.<String,Object>singletonMap("large",value.toString());
        OperationResult[] outcomes={
            new RunResult("run-id","PASS",0,42,null,null,details),
            new DebugResult("debug-id","ERROR",3,42,null,null,details),
            new ValidateResult("validate-id","INVALID",2,42,null,null,details),
            new LoadResult("load-id","FAIL",1,42,null,null,details)
        };
        for(OperationResult outcome:outcomes) {
            Path output=Files.createDirectory(temp.resolve(outcome.executionId()));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();WorkerMain worker=worker(bytes,output);
            Map<String,Object> result=outcome.toMap();
            result.put("diagnostics",Collections.singletonList(fields("code","CONNECTION_ERROR","summary","JDBC driver unavailable")));
            invoke(worker,"emit",new Class<?>[]{WorkerEvent.Type.class,Map.class},WorkerEvent.Type.PROGRESS,fields("message",value.toString()));
            invoke(worker,"emitResult",new Class<?>[]{Map.class},fields("status",outcome.status(),"exitCode",outcome.exitCode(),"durationMs",42,"result",result));
            try(ByteArrayInputStream stream=new ByteArrayInputStream(bytes.toByteArray())) {
                WorkerProtocol.Frame progress=WorkerProtocol.read(stream,WorkerProtocol.MAX_FRAME_BYTES);
                assertFalse(progress.oversized());assertTrue(mapper.readTree(progress.text()).path("truncated").asBoolean());
                WorkerProtocol.Frame terminal=WorkerProtocol.read(stream,WorkerProtocol.MAX_FRAME_BYTES);
                assertFalse(terminal.oversized());JsonNode event=mapper.readTree(terminal.text());
                assertEquals("RESULT",event.path("type").asText());assertEquals(outcome.status(),event.path("status").asText());
                assertEquals(outcome.exitCode(),event.path("exitCode").asInt());
                assertEquals(outcome.executionId(),event.path("result").path("executionId").asText());
                assertEquals("CONNECTION_ERROR",event.path("result").path("diagnostics").get(0).path("code").asText());
                assertTrue(event.path("workerMetrics").path("sampleCount").asLong()>0);
                assertTrue(event.path("protocolLimit").path("sizeBytes").asLong()>WorkerProtocol.MAX_FRAME_BYTES);
                assertEquals("worker-protocol-result.json",event.path("result").path("detailsArtifact").asText());
                assertNull(WorkerProtocol.read(stream,WorkerProtocol.MAX_FRAME_BYTES));
            }
            JsonNode stored=mapper.readTree(output.resolve("worker-protocol-result.json").toFile());
            assertEquals(value.toString(),stored.path("result").path("summary").path("large").asText());
        }
    }

    @Test void compactLoadResultFitsWorkerFrameAndKeepsTheRuntimeCause() throws Exception {
        Map<String,Object> summary=LoadTerminalResult.compact(fields("executionId","load-id","runId","load-id",
                "status","ERROR","exitCode",3,"durationMs",3_600_000L,"executionStatus","ERROR",
                "performanceVerdict","NOT_MET","metrics",fields("scheduled",1L,"started",1L,"completed",1L,
                        "success",0L,"failure",0L,"runtimeError",1L),
                "thresholds",fields("status","NOT_EVALUATED"),"workloads",Collections.emptyMap(),
                "diagnostics",fields("primaryError",fields("code","HTTP_RESPONSE_TOO_LARGE",
                        "summary","HTTP response exceeded the configured response limit"),
                        "classifications",fields("HTTP_RESPONSE_TOO_LARGE",1L)),
                "metricCoverage",fields("loadMetricsBucketCapacity",4096,"loadMetricsBucketCount",2,
                        "loadMetricsBucketWidthMs",1000,"windowSeriesCapacity",2048,"windowSeriesBinWidthMs",1000),
                "detailCompleteness",fields("state","OMITTED","truncated",true,"omittedCounts",fields("timeSeriesBuckets",2)),
                "archive",fields("status","PENDING","reportAvailability","PENDING",
                        "paths",fields("loadSummaryJson","load/load-id/load-summary.json"))));
        LoadResult result=new LoadResult("load-id","ERROR",3,3_600_000L,Collections.emptyList(),Collections.emptyMap(),summary);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        WorkerMain worker=worker(bytes,Files.createDirectory(temp.resolve("compact-load")));
        invoke(worker,"emitResult",new Class<?>[]{Map.class},fields("status","ERROR","exitCode",3,"durationMs",3_600_000L,
                "result",result.toWorkerMap()));

        JsonNode event=mapper.readTree(WorkerProtocol.read(new ByteArrayInputStream(bytes.toByteArray()),WorkerProtocol.MAX_FRAME_BYTES).text());
        assertEquals("att-load-terminal-result/v1",event.path("result").path("summary").path("schemaVersion").asText());
        assertEquals("HTTP_RESPONSE_TOO_LARGE",event.path("result").path("summary").path("diagnostics").path("primaryError").path("code").asText());
        assertEquals(3,event.path("result").path("exitCode").asInt());
        assertFalse(event.path("result").path("summary").path("metrics").has("buckets"));
        assertTrue(event.path("loadResultTransport").path("workerFrameBytes").asLong()<WorkerProtocol.MAX_FRAME_BYTES);
        assertEquals(65_536,event.path("loadResultTransport").path("journalDataLimitBytes").asInt());
    }

    @Test void oversizedLegacyLoadFrameKeepsTheRealCauseBeforeTransportTruncation() throws Exception {
        StringBuilder large = new StringBuilder();
        for (int index = 0; index < 1_100_000; index++) large.append(index % 2 == 0 ? 'x' : '\u96ea');
        Map<String,Object> metrics = fields("scheduled", 12L, "started", 12L, "completed", 12L,
                "success", 10L, "failure", 1L, "runtimeError", 1L,
                "failureDiagnostics", Collections.singletonList(fields("status", "ERROR",
                        "code", "HTTP_RESPONSE_TOO_LARGE", "summary", "HTTP response exceeded its size limit")),
                "buckets", Collections.emptyMap(), "large", large.toString());
        Map<String,Object> legacy = fields("schemaVersion", "att-load-summary/v1.2", "runId", "load-legacy-large",
                "status", "ERROR", "executionStatus", "ERROR", "performanceVerdict", "NOT_MET",
                "durationMs", 3_600_000L, "metrics", metrics, "thresholds", fields("status", "NOT_EVALUATED"),
                "workloads", Collections.emptyMap(), "resources", Collections.emptyMap(), "report", "report/index.html");
        Map<String,Object> raw = fields("executionId", "load-legacy-large", "status", "ERROR", "exitCode", 3,
                "durationMs", 3_600_000L, "diagnostics", Collections.emptyList(), "summary", legacy);
        Path output = Files.createDirectory(temp.resolve("oversized-legacy-load"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); WorkerMain worker = worker(bytes, output);
        Field command = WorkerMain.class.getDeclaredField("currentCommand"); command.setAccessible(true); command.set(worker, "load");
        invoke(worker, "emitResult", new Class<?>[]{Map.class}, fields("status", "ERROR", "exitCode", 3,
                "durationMs", 3_600_000L, "result", raw));

        JsonNode event = mapper.readTree(WorkerProtocol.read(new ByteArrayInputStream(bytes.toByteArray()), WorkerProtocol.MAX_FRAME_BYTES).text());
        JsonNode result = event.path("result");
        JsonNode summary = result.path("summary");
        assertEquals("ERROR", result.path("status").asText());
        assertEquals(3, result.path("exitCode").asInt());
        assertEquals("att-load-terminal-result/v1", summary.path("schemaVersion").asText());
        assertEquals("HTTP_RESPONSE_TOO_LARGE", summary.path("diagnostics").path("primaryError").path("code").asText());
        assertEquals("ATT-WORKER-EVENT-TRUNCATED", summary.path("diagnostics").path("transportWarnings").get(0).path("code").asText());
        assertEquals("ATT-WORKER-EVENT-TRUNCATED", result.path("diagnostics").get(result.path("diagnostics").size() - 1).path("code").asText());
    }

    @Test void workerFailureResultKeepsLegacyShapeForNonLoadAndVersionedShapeForLoad() throws Exception {
        for (String commandName : new String[] {"run", "debug", "validate", "load"}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            WorkerMain worker = worker(bytes, Files.createDirectory(temp.resolve("failure-output-" + commandName)));
            JsonNode request = mapper.readTree(mapper.writeValueAsBytes(fields("protocolVersion", "att-worker/v1",
                    "jobId", "J-" + commandName, "command", commandName, "packageRoot", "",
                    "config", "missing.yaml", "outputDirectory", "output")));
            invoke(worker, "executeRequest", new Class<?>[]{JsonNode.class}, request);
            String[] lines = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim().split("\\R");
            JsonNode resultEvent = mapper.readTree(lines[lines.length - 1]);
            JsonNode result = resultEvent.path("result");
            assertEquals(resultEvent.path("status").asText(), result.path("status").asText());
            assertEquals(resultEvent.path("exitCode").asInt(), result.path("exitCode").asInt());
            if ("load".equals(commandName)) {
                assertEquals("att-load-terminal-result/v1", result.path("summary").path("schemaVersion").asText());
                assertTrue(result.path("diagnostics").isArray());
                assertEquals("INVALID", result.path("status").asText());
                assertEquals(2, result.path("exitCode").asInt());
                assertEquals("WORKER_REQUEST_INVALID", result.path("summary").path("diagnostics").path("primaryError").path("code").asText());
            } else {
                assertFalse(result.has("summary"));
                assertFalse(result.has("diagnostics"));
            }
        }
    }

    @Test void workerUsesTypedPreparationDiagnosticFromTheRealBudgetFailurePath() throws Exception {
        att.load.LoadResourcePreparationException failure = assertThrows(att.load.LoadResourcePreparationException.class,
                () -> att.load.LoadPreparationBudget.requireWithinPerRunLimit(att.load.LoadPreparationBudget.PER_RUN_CONNECTIONS + 1));
        assertEquals(att.load.LoadResourcePreparationException.RESOURCE_LIMIT, failure.diagnosticCode());
        Method classifier = WorkerMain.class.getDeclaredMethod("loadPreparationCode", Throwable.class);
        classifier.setAccessible(true);
        assertEquals("LOAD_RESOURCE_PREPARATION_LIMIT", classifier.invoke(null, failure));
    }

    @Test void artifactWriteFailureStillDeliversTheTerminalResult() throws Exception {
        Path output=Files.createDirectory(temp.resolve("occupied"));
        Files.write(output.resolve("worker-protocol-result.json"),new byte[]{1});
        StringBuilder large=new StringBuilder();for(int i=0;i<1100000;i++)large.append('x');
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();WorkerMain worker=worker(bytes,output);
        invoke(worker,"emitResult",new Class<?>[]{Map.class},fields("status","ERROR","exitCode",3,"result",fields("detail",large.toString())));
        JsonNode event=mapper.readTree(bytes.toByteArray());
        assertEquals("ERROR",event.path("status").asText());assertEquals(3,event.path("exitCode").asInt());
        assertTrue(event.path("result").path("detailsUnavailable").asBoolean());
        assertArrayEquals(new byte[]{1},Files.readAllBytes(output.resolve("worker-protocol-result.json")));
    }

    private WorkerMain worker(ByteArrayOutputStream out,Path output)throws Exception {
        Constructor<WorkerMain> constructor=WorkerMain.class.getDeclaredConstructor(PrintStream.class);constructor.setAccessible(true);
        WorkerMain worker=constructor.newInstance(new PrintStream(out,true,"UTF-8"));
        Field field=WorkerMain.class.getDeclaredField("executionOutput");field.setAccessible(true);field.set(worker,output);
        return worker;
    }
    private static void invoke(WorkerMain worker,String name,Class<?>[] types,Object... args)throws Exception {
        Method method=WorkerMain.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(worker,args);
    }
    private static Map<String,Object> fields(Object... fields) {
        Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<fields.length;i+=2)out.put((String)fields[i],fields[i+1]);return out;
    }
}
