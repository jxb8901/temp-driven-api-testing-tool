package att.server;

import att.server.api.WorkerRequest;
import att.server.api.internal.WorkerProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeLoadTerminalResultTest {
    private static final ObjectMapper JSON = ServerRuntime.JSON;
    @TempDir Path temp;

    @Test void storesAndPublishesCompactLoadResultAndPreservesRuntimeCauseAcrossTheJournal() throws Exception {
        try (ServerRuntime runtime = runtime("runtime-cause")) {
            String id = "J0000000000000297";
            Job job = createJob(runtime, id);
            Map<String, Object> raw = legacyResult("ERROR", 3, "MQ_RESPONSE_TOO_LARGE", true, 4);
            Map<String, Object> compact = att.server.api.LoadTerminalResult.projectOperationResult(raw, "ERROR", 3);
            sendTerminal(runtime, job, compact, "ERROR", 3);

            Map<String, Object> stored = runtime.store.get(id);
            Map<?, ?> storedResult = JSON.readValue((String) stored.get("resultJson"), Map.class);
            Map<?, ?> summary = (Map<?, ?>) storedResult.get("summary");
            Map<?, ?> cause = (Map<?, ?>) ((Map<?, ?>) summary.get("diagnostics")).get("primaryError");
            assertEquals("att-load-terminal-result/v1", summary.get("schemaVersion"));
            assertEquals("MQ_RESPONSE_TOO_LARGE", cause.get("code"));
            assertEquals("ERROR", storedResult.get("status"));
            assertEquals(3, storedResult.get("exitCode"));
            assertFalse(JSON.writeValueAsString(storedResult).contains("/private/tmp"));
            assertFalse(JSON.writeValueAsString(storedResult).contains("buckets"));

            List<Map<String, Object>> events = job.events.after(0);
            Map<String, Object> terminal = events.stream().filter(event -> "result".equals(event.get("event"))).findFirst().orElseThrow();
            Map<?, ?> eventData = (Map<?, ?>) terminal.get("data");
            JsonNode published = JSON.valueToTree(runtime.resultRecord(id).get("result"));
            assertEquals("att-load-terminal-result/v1", published.path("summary").path("schemaVersion").asText());
            assertEquals("MQ_RESPONSE_TOO_LARGE", published.path("summary").path("diagnostics").path("primaryError").path("code").asText());
            assertEquals("ERROR", eventData.get("status"));

            int journalBytes = JSON.writeValueAsBytes(terminal).length;
            int sseBytes = sseEventBytes(runtime, terminal, id);
            assertTrue(journalBytes < 48 * 1024, "actual post-envelope journal event bytes=" + journalBytes);
            assertTrue(sseBytes < 48 * 1024, "actual post-envelope SSE bytes=" + sseBytes);
            assertTrue(((Map<?, ?>) storedResult).containsKey("summary"));
            Map<?, ?> performance = JSON.readValue((String) stored.get("performanceJson"), Map.class);
            Map<?, ?> sizes = (Map<?, ?>) ((Map<?, ?>) performance.get("worker")).get("loadTerminalResult");
            assertEquals(journalBytes, ((Number) sizes.get("journalEventBytes")).intValue());
            assertEquals(sseBytes, ((Number) sizes.get("sseEventBytes")).intValue());
            assertTrue(((Number) sizes.get("storedResultBytes")).intValue() < 48 * 1024);
        }
    }

    @Test void normalizesHistoricalDetailedLoadResultsOnReadWithoutExposingHostPaths() throws Exception {
        try (ServerRuntime runtime = runtime("historical-result")) {
            String id = "J0000000000000298";
            Job job = createJob(runtime, id);
            job.status = "ERROR"; job.exitCode = 3; job.finishedAt = java.time.Instant.now();
            Map<String, Object> raw = legacyResult("ERROR", 3, "HTTP_RESPONSE_TOO_LARGE", true, 12);
            raw.put("paths", fields("report", "/private/tmp/private/report.html"));
            job.resultJson = JSON.writeValueAsString(raw);
            runtime.store.update(job);

            JsonNode response = JSON.valueToTree(runtime.resultRecord(id).get("result"));
            assertEquals("att-load-terminal-result/v1", response.path("summary").path("schemaVersion").asText());
            assertEquals("HTTP_RESPONSE_TOO_LARGE", response.path("summary").path("diagnostics").path("primaryError").path("code").asText());
            assertFalse(response.toString().contains("/private/tmp"));
            assertFalse(response.toString().contains("buckets"));
            assertTrue(((String) runtime.store.get(id).get("resultJson")).contains("buckets"),
                    "historical storage is left intact; the public Job Results response is normalized");
        }
    }

    @Test void workerExitBeforeResultCreatesCompactErrorResultWithServerCause() throws Exception {
        try (ServerRuntime runtime = runtime("worker-exit")) {
            String id = "J0000000000000299";
            Job job = createJob(runtime, id);
            runtime.recordWorkerExit(job, 7);
            runtime.finish(job, "ERROR", 7);

            Map<String,Object> stored = runtime.store.get(id);
            Map<?,?> result = JSON.readValue((String) stored.get("resultJson"), Map.class);
            Map<?,?> summary = (Map<?,?>) result.get("summary");
            Map<?,?> cause = (Map<?,?>) ((Map<?,?>) summary.get("diagnostics")).get("primaryError");
            assertEquals("ERROR", result.get("status"));
            assertEquals(7, ((Number) result.get("exitCode")).intValue());
            assertEquals("att-load-terminal-result/v1", summary.get("schemaVersion"));
            assertEquals("ATT-SERVER-WORKER-EXITED", cause.get("code"));
            assertEquals("NOT_EVALUATED", ((Map<?,?>) summary.get("performance")).get("verdict"));
            assertEquals("OMITTED", ((Map<?,?>) summary.get("archive")).get("reportAvailability"));

            Map<?,?> terminal = job.events.after(0).stream()
                    .filter(event -> "result".equals(event.get("event"))).findFirst().orElseThrow();
            Map<?,?> eventData = (Map<?,?>) terminal.get("data");
            assertEquals("ATT-SERVER-WORKER-EXITED", JSON.valueToTree(eventData).path("result").path("summary")
                    .path("diagnostics").path("primaryError").path("code").asText());
        }
    }

    @Test void benchmarkShortAndSyntheticLongMultiWorkloadTransferSizes() throws Exception {
        try (ServerRuntime runtime = runtime("benchmark")) {
            Map<String, Object> shortShape = measure(runtime, "J0000000000000301", legacyResult("PASS", 0, null, false, 1), 1_000L);
            Map<String, Object> longShape = measure(runtime, "J0000000000000302", legacyResult("PASS", 0, null, true, 12), 3_600_000L);
            Map<String, Object> report = fields("schemaVersion", "att-load-terminal-result-benchmark/v1",
                    "synthetic", true, "scenarios", List.of(shortShape, longShape));
            System.out.println("LOAD_RESULT_BENCHMARK " + JSON.writeValueAsString(report));

            assertTrue(((Number) shortShape.get("workerFrameBytes")).longValue() < WorkerProtocol.MAX_FRAME_BYTES);
            assertTrue(((Number) longShape.get("workerFrameBytes")).longValue() < WorkerProtocol.MAX_FRAME_BYTES);
            assertTrue(((Number) shortShape.get("journalEventBytes")).longValue() < JobEvents.MAX_EVENT_DATA_BYTES);
            assertTrue(((Number) longShape.get("sseEventBytes")).longValue() < JobEvents.MAX_EVENT_DATA_BYTES);
            assertTrue(((Number) longShape.get("jobResultsTransferBytes")).longValue() > ((Number) longShape.get("storedResultBytes")).longValue());
            assertTrue(((Number) longShape.get("jobDetailsTransferBytes")).longValue() > ((Number) longShape.get("jobResultsTransferBytes")).longValue());
            assertTrue(((Number) longShape.get("workerFrameBytes")).longValue() > ((Number) shortShape.get("workerFrameBytes")).longValue());
            assertEquals("PENDING", longShape.get("archiveState"));
            assertTrue(((Number) longShape.get("omittedWorkloads")).intValue() > 0);
            assertEquals(4096, longShape.get("syntheticBucketCount"));
        }
    }

    private Map<String, Object> measure(ServerRuntime runtime, String id, Map<String, Object> raw, long durationMs) throws Exception {
        Job job = createJob(runtime, id);
        Map<String, Object> compact = att.server.api.LoadTerminalResult.projectOperationResult(raw,
                String.valueOf(raw.get("status")), ((Number) raw.get("exitCode")).intValue());
        long workerBytes = sendTerminal(runtime, job, compact, String.valueOf(raw.get("status")),
                ((Number) raw.get("exitCode")).intValue());
        Map<String, Object> stored = runtime.store.get(id);
        String storedJson = (String) stored.get("resultJson");
        Map<String, Object> jobResponse = runtime.jobRecord(id);
        jobResponse.put("requestId", "benchmark-job-request");
        int jobRecordBytes = JSON.writeValueAsBytes(jobResponse).length;
        Map<String, Object> response = runtime.resultRecord(id);
        response.put("requestId", "benchmark-request");
        int responseBytes = JSON.writeValueAsBytes(response).length;
        Map<?, ?> performance = JSON.readValue((String) stored.get("performanceJson"), Map.class);
        Map<?, ?> sizes = (Map<?, ?>) ((Map<?, ?>) performance.get("worker")).get("loadTerminalResult");
        List<Map<String, Object>> events = job.events.after(0);
        Map<String, Object> terminal = events.stream().filter(event -> "result".equals(event.get("event"))).findFirst().orElseThrow();
        int journalBytes = JSON.writeValueAsBytes(terminal).length;
        int sseBytes = sseEventBytes(runtime, terminal, id);
        Map<?, ?> responseResult = JSON.convertValue(response.get("result"), Map.class);
        Map<?, ?> summary = (Map<?, ?>) responseResult.get("summary");
        Map<?, ?> completeness = (Map<?, ?>) summary.get("detailCompleteness");
        Map<?, ?> omitted = (Map<?, ?>) completeness.get("omittedCounts");
        Map<String, Object> result = fields("name", durationMs <= 60_000L ? "short" : "synthetic 30-60 min multi-workload",
                "durationMs", durationMs, "syntheticBucketCount", durationMs <= 60_000L ? 2 : 4096,
                "workerFrameBytes", workerBytes, "storedResultBytes", storedJson.getBytes(StandardCharsets.UTF_8).length,
                "journalDataBytes", JSON.writeValueAsBytes(terminal.get("data")).length,
                "journalEventBytes", journalBytes, "sseEventBytes", sseBytes,
                "workerFrameLimitBytes", sizes.get("workerFrameLimitBytes"),
                "journalDataLimitBytes", sizes.get("journalDataLimitBytes"), "sseEventLimitBytes", sizes.get("sseEventLimitBytes"),
                "postEnvelopeTargetBytes", 48 * 1024, "jobRecordTransferBytes", jobRecordBytes,
                "jobResultsTransferBytes", responseBytes, "jobDetailsTransferBytes", jobRecordBytes + responseBytes,
                "archiveState", ((Map<?, ?>) summary.get("archive")).get("status"),
                "omittedWorkloads", omitted.get("workloads"), "omittedBuckets", omitted.get("timeSeriesBuckets"));
        return result;
    }

    private long sendTerminal(ServerRuntime runtime, Job job, Map<String, Object> result, String status, int exitCode) throws Exception {
        Map<String, Object> transport = fields("workerFrameBytes", 0L,
                "workerFrameLimitBytes", WorkerProtocol.MAX_FRAME_BYTES,
                "journalDataLimitBytes", JobEvents.MAX_EVENT_DATA_BYTES);
        Map<String, Object> event = fields("type", "RESULT", "jobId", job.id, "status", status, "exitCode", exitCode,
                "result", result, "loadResultTransport", transport, "workerMetrics", fields("sampleCount", 1));
        long last = -1L;
        WorkerProtocol.Frame frame = null;
        for (int i = 0; i < 6; i++) {
            frame = WorkerProtocol.encode(JSON, event, WorkerProtocol.MAX_FRAME_BYTES);
            if (frame.sizeBytes == last) break;
            last = frame.sizeBytes; transport.put("workerFrameBytes", last);
        }
        frame = WorkerProtocol.encode(JSON, event, WorkerProtocol.MAX_FRAME_BYTES);
        assertFalse(frame.oversized());
        runtime.handleWorkerEvent(job, frame.text());
        return frame.sizeBytes;
    }

    private int sseEventBytes(ServerRuntime runtime, Map<String, Object> terminal, String id) throws Exception {
        Object data = runtime.publicEventData(terminal.get("data"), runtime.config.dataDir.resolve("jobs").resolve(id).resolve("output"), packageRoot(runtime, id));
        String event = "id: " + terminal.get("id") + "\nevent: result\ndata: " + JSON.writeValueAsString(data) + "\n\n";
        return event.getBytes(StandardCharsets.UTF_8).length;
    }

    private Job createJob(ServerRuntime runtime, String id) throws Exception {
        Path events = runtime.config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl");
        Job job = new Job(id, "load", "p", "benchmark", new WorkerRequest(), new JobEvents(events, 100));
        runtime.store.insert(job, "{}");
        return job;
    }

    private ServerRuntime runtime(String name) throws Exception {
        Path allowed = Files.createDirectories(temp.resolve(name + "-packages"));
        Path pkg = Files.createDirectories(allowed.resolve("p"));
        Path data = temp.resolve(name + "-data");
        Path configFile = temp.resolve(name + "-server.yaml");
        Files.writeString(configFile, "server:\n  dataDir: " + ServerRuntimeTestSupport.yaml(data)
                + "\nworkers: {}\npackages:\n  allowedRoots:\n    - " + ServerRuntimeTestSupport.yaml(allowed)
                + "\n  entries:\n    p: " + ServerRuntimeTestSupport.yaml(pkg) + "\n");
        return new ServerRuntime(ServerConfig.load(configFile), null, builder -> { throw new UnsupportedOperationException("worker launch is not used"); });
    }

    private Path packageRoot(ServerRuntime runtime, String id) throws Exception { return runtime.packageRootForJob(id); }

    private static Map<String, Object> legacyResult(String status, int exitCode, String primaryCode, boolean longRun, int workloadCount) {
        Map<String, Object> buckets = new LinkedHashMap<>();
        int bucketCount = longRun ? 4096 : 2;
        for (int index = 0; index < bucketCount; index++) {
            long at = 1_800_000_000_000L + index * 1000L;
            buckets.put(String.valueOf(at), fields("bucketStartEpochMs", at, "completedTps", index % 100,
                    "p95Ms", 35 + index % 19, "p99Ms", 70 + index % 31));
        }
        Map<String, Object> metrics = fields("scheduled", longRun ? 900_000L : 10L, "started", longRun ? 899_900L : 10L,
                "completed", longRun ? 899_700L : 10L, "success", longRun ? 899_000L : ("ERROR".equals(status) ? 8L : 10L),
                "failure", "ERROR".equals(status) ? 1L : 0L, "runtimeError", primaryCode == null ? 0L : 1L,
                "completedThroughput", longRun ? 250.0 : 10.0, "successfulThroughput", longRun ? 249.8 : 10.0,
                "p95Ms", 94.0, "p99Ms", 160.0, "timeSeriesBucketCapacity", 4096,
                "timeSeriesBucketCount", bucketCount, "buckets", buckets,
                "windowMetrics", fields("schemaVersion", "att-load-window-metrics/v1",
                        "timeSeries", fields("capacity", 2048, "bucketDurationMs", longRun ? 60000L : 1000L,
                                "coverage", longRun ? "Whole execution; uniform power-of-two roll-up, no historical eviction" : "Whole execution")));
        if (primaryCode != null) {
            metrics.put("runtimeErrorClassifications", fields(primaryCode, 1));
            metrics.put("primaryRuntimeError", fields("code", primaryCode, "summary", "Failed with " + primaryCode + " — connection timeout \"界\"."));
            metrics.put("failureDiagnostics", List.of(fields("status", "ERROR", "code", primaryCode,
                    "summary", "Failed with " + primaryCode, "workloadId", "workload-界")));
        }
        Map<String, Object> workloads = new LinkedHashMap<>();
        for (int index = 0; index < workloadCount; index++) {
            String workloadId = "workload-" + String.format("%02d-界", index);
            workloads.put(workloadId, fields("status", status, "executionStatus", status,
                    "performanceVerdict", "NOT_EVALUATED", "metrics", fields("scheduled", longRun ? 75_000L : 10L,
                            "started", longRun ? 75_000L : 10L, "completed", longRun ? 74_900L : 10L,
                            "success", longRun ? 74_800L : 10L, "runtimeError", primaryCode == null ? 0L : 1L,
                            "buckets", buckets)));
        }
        List<Map<String, Object>> thresholds = new ArrayList<>();
        int thresholdCount = longRun ? 128 : 1;
        for (int index = 0; index < thresholdCount; index++) thresholds.add(fields("name", "latency-p95-workload-" + index,
                "expected", "<=100ms", "actual", "95ms", "status", "PASS"));
        Map<String, Object> summary = fields("schemaVersion", "att-load-summary/v1.2", "runId", "run-synthetic-界",
                "status", status, "executionStatus", status, "performanceVerdict", "MET",
                "durationMs", longRun ? 3_600_000L : 1000L, "startedAt", "2026-10-26T00:00:00Z",
                "endedAt", longRun ? "2026-10-26T01:00:00Z" : "2026-10-26T00:00:01Z",
                "metrics", metrics, "thresholds", fields("status", "MET", "results", thresholds),
                "workloads", workloads, "resources", fields("preparation", fields("status", "READY")),
                "report", "report/index.html");
        return fields("executionId", "run-synthetic-界", "status", status, "exitCode", exitCode,
                "durationMs", longRun ? 3_600_000L : 1000L, "paths", fields("report", "/private/tmp/report/index.html"),
                "summary", summary);
    }

    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }
}
