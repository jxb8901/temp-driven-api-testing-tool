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
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

    @Test void persistsOnlyAllowlistedMonotonicLiveLoadProgressAndBoundsHistory() throws Exception {
        try(ServerRuntime runtime=runtime("live-load-progress")) {
            Job job=createJob(runtime,"J0000000000000301");
            job.targetIdentity=fields("workloads",List.of(fields("name","steady","totalDurationMs",10000L)));
            Map<String,Object> snapshot=fields("schemaVersion","att-load-live-progress/v1","capturedAt","2026-10-27T00:00:05Z","elapsedMs",5000L,
                    "phase","STEADY","counts",fields("scheduled",10L,"started",9L,"completed",7L,"passed",6L,"sutFailures",1L,"runtimeErrors",0L,"dropped",0L,"active",2L,"queued",1L),
                    "measuredCounts",fields("completed",7L,"passed",6L),"rates",fields("completedTps",1.4,"successfulTps",1.2),
                    "latency",fields("sampleCount",7L,"meanMs",52.0,"p95Ms",63L,"p99Ms",63L),
                    "latestFailure",fields("status","ERROR","capturedAt","2026-10-27T00:00:04Z","code","TIMEOUT","message","secret-value"),
                    "phases",fields("STEADY",fields("completed",7L)),"workloads",fields("steady",fields("completed",7L),"/private/path",fields("completed",99L)),
                    "password","secret-value","filesystemPath","/private/secret/path");
            runtime.handleWorkerEvent(job,JSON.writeValueAsString(fields("type","PROGRESS","loadProgress",snapshot)));
            assertEquals(5000L,((Number)job.loadProgress.get("elapsedMs")).longValue());
            assertEquals(1,job.loadProgressHistory.size());assertEquals(1,job.loadProgressTrend.size());
            assertFalse(JSON.writeValueAsString(job.loadProgress).contains("secret-value"));
            assertEquals("TIMEOUT",((Map<?,?>)job.loadProgress.get("latestFailure")).get("code"));
            assertFalse(JSON.writeValueAsString(job.loadProgress).contains("/private"));
            assertEquals(Set.of("steady"),((Map<?,?>)job.loadProgress.get("workloads")).keySet());

            Map<String,Object> regressive=new LinkedHashMap<>(snapshot);regressive.put("elapsedMs",4000L);
            runtime.handleWorkerEvent(job,JSON.writeValueAsString(fields("type","PROGRESS","loadProgress",regressive)));
            assertEquals(5000L,((Number)job.loadProgress.get("elapsedMs")).longValue(),"A reconnect replay with an older sample cannot replace the latest snapshot");
            Map<String,Object> persisted=(Map<String,Object>)runtime.store.get(job.id).get("loadProgress");
            assertEquals(5000L,((Number)persisted.get("elapsedMs")).longValue());
            Map<String,Object> latestEvent=job.events.after(0).stream().filter(event->"progress".equals(event.get("event"))).reduce((left,right)->right).orElseThrow();
            assertFalse(JSON.writeValueAsString(latestEvent.get("data")).contains("loadProgress"),"The stale progress snapshot is omitted from the published event");

            Map<String,Object> next=new LinkedHashMap<>(snapshot);next.put("capturedAt","2026-10-27T00:00:10Z");next.put("elapsedMs",10000L);
            Map<String,Object> intervalCounts=new LinkedHashMap<>();
            for(String key:List.of("scheduled","started","completed","success","failure","runtimeError","otherOutcome","dropped","measuredScheduled","measuredStarted","measuredCompleted","measuredSuccess","measuredFailure","measuredRuntimeError","measuredOtherOutcome","measuredDropped"))intervalCounts.put(key,1L);
            Map<String,Object> interval=fields("schemaVersion","att-load-checkpoint-interval/v1","state","VERIFIED","startSequence",1L,"endSequence",2L,
                    "startAt","2026-10-27T00:00:00Z","endAt","2026-10-27T00:05:00Z","durationMs",300000L,"counts",intervalCounts,"latencySampleCount",10L,"latencyMeanMs",23.5,"path","/private/secret");
            next.put("checkpointInterval",interval);
            runtime.handleWorkerEvent(job,JSON.writeValueAsString(fields("type","PROGRESS","loadProgress",next)));
            runtime.handleWorkerEvent(job,JSON.writeValueAsString(fields("type","PROGRESS","loadProgress",next)));
            Map<String,Object> record=runtime.jobRecord(job.id);
            assertEquals(10000L,((Number)((Map<?,?>)record.get("loadProgress")).get("elapsedMs")).longValue());
            assertEquals(3,((List<?>)record.get("loadProgressHistory")).size());
            assertEquals(2,((List<?>)record.get("loadProgressTrend")).size());
            assertEquals(1,((List<?>)record.get("loadCheckpointHistory")).size(),"Repeated checkpoint intervals are deduplicated");
            assertEquals(23.5,((Number)((Map<?,?>)((List<?>)record.get("loadCheckpointHistory")).get(0)).get("latencyMeanMs")).doubleValue());
            assertFalse(JSON.writeValueAsString(record.get("loadCheckpointHistory")).contains("/private"));
            runtime.jobs.remove(job.id);
            assertEquals(1,((List<?>)runtime.jobRecord(job.id).get("loadCheckpointHistory")).size(),"Checkpoint history survives Job eviction");
        }
    }

    @Test void storesWorkerOversizeFallbackCauseWithoutPromotingTransportTruncation() throws Exception {
        try (ServerRuntime runtime = runtime("worker-fallback-cause")) {
            String id = "J0000000000000300";
            Job job = createJob(runtime, id);
            Map<String,Object> raw = legacyResult("ERROR", 3, "HTTP_RESPONSE_TOO_LARGE", true, 12);
            Map<String,Object> fallback = att.server.api.LoadTerminalResult.projectOperationResult(raw, "ERROR", 3);
            Map<String,Object> summary = new LinkedHashMap<>((Map<String,Object>) fallback.get("summary"));
            Map<String,Object> diagnostics = new LinkedHashMap<>((Map<String,Object>) summary.get("diagnostics"));
            diagnostics.put("transportWarnings", List.of(fields("code", "ATT-WORKER-EVENT-TRUNCATED", "summary", "Worker details truncated")));
            summary.put("diagnostics", diagnostics);
            fallback.put("summary", summary);
            fallback.put("diagnostics", List.of(fields("code", "HTTP_RESPONSE_TOO_LARGE", "summary", "Failed with HTTP_RESPONSE_TOO_LARGE"),
                    fields("code", "ATT-WORKER-EVENT-TRUNCATED", "summary", "Large Worker result details were omitted")));
            fallback.put("truncated", Boolean.TRUE);
            sendTerminal(runtime, job, fallback, "ERROR", 3);

            Map<?,?> stored = JSON.readValue((String) runtime.store.get(id).get("resultJson"), Map.class);
            Map<?,?> storedSummary = (Map<?,?>) stored.get("summary");
            Map<?,?> storedDiagnostics = (Map<?,?>) storedSummary.get("diagnostics");
            assertEquals("ERROR", stored.get("status"));
            assertEquals(3, ((Number) stored.get("exitCode")).intValue());
            assertEquals("HTTP_RESPONSE_TOO_LARGE", ((Map<?,?>) storedDiagnostics.get("primaryError")).get("code"));
            assertEquals("ATT-WORKER-EVENT-TRUNCATED", ((Map<?,?>) ((List<?>) storedDiagnostics.get("transportWarnings")).get(0)).get("code"));
            Map<?,?> terminal = (Map<?,?>) job.events.after(0).stream().filter(event -> "result".equals(event.get("event"))).findFirst().orElseThrow();
            JsonNode published = JSON.valueToTree(terminal.get("data"));
            assertEquals("HTTP_RESPONSE_TOO_LARGE", published.path("result").path("summary").path("diagnostics").path("primaryError").path("code").asText());
            assertEquals(3, published.path("result").path("exitCode").asInt());
        }
    }

    @Test void realLoadProcessKeepsRuntimeCausePrimaryWhenTerminalAndJournalEventsAreOversized() throws Exception {
        ServerRuntime.WorkerProcessLauncher launcher=builder->new ProcessBuilder(
                Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java").toString(),
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),OversizedLoadWorker.class.getName()).start();
        Path libs=workerLibraries("integrated-truncation-libs");
        try(ServerRuntime runtime=runtime("integrated-truncation",launcher,libs)) {
            Map<String,Object> submitted=runtime.submit("load",JSON.createObjectNode().put("packageId","p"),"integration");
            String id=String.valueOf(submitted.get("jobId"));
            Map<String,Object> stored=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(System.nanoTime()<deadline) {
                stored=runtime.store.get(id);
                if(stored!=null&&"ERROR".equals(stored.get("status"))&&!runtime.jobs.containsKey(id))break;
                Thread.sleep(20L);
            }
            assertNotNull(stored,"the submitted LOAD process did not finish");
            assertEquals("ERROR",stored.get("status"));
            assertEquals(3,((Number)stored.get("exitCode")).intValue());
            JsonNode primary=JSON.readTree(String.valueOf(stored.get("diagnosticJson")));
            assertEquals("HTTP_RESPONSE_TOO_LARGE",primary.path("code").asText());
            assertEquals("EXECUTION_ERROR",primary.path("outcome").asText());

            JsonNode result=JSON.readTree(String.valueOf(stored.get("resultJson")));
            assertEquals("ERROR",result.path("status").asText());
            assertEquals(3,result.path("exitCode").asInt());
            JsonNode summary=result.path("summary");
            assertEquals("HTTP_RESPONSE_TOO_LARGE",summary.path("diagnostics").path("primaryError").path("code").asText());
            List<String> secondary=new ArrayList<>();
            for(JsonNode warning:summary.path("diagnostics").path("transportWarnings"))secondary.add(warning.path("code").asText());
            assertTrue(secondary.contains("ATT-WORKER-EVENT-TRUNCATED"),secondary.toString());
            assertTrue(secondary.contains("ATT-JOURNAL-EVENT-TRUNCATED"),secondary.toString());
            assertFalse(result.toString().contains("private-canary"));
            assertFalse(result.toString().contains(runtime.config.dataDir.toString()));

            List<Map<String,Object>> events=new JobEvents(runtime.config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),100).after(0);
            Map<String,Object> largeLog=events.stream().filter(event->"log".equals(event.get("event"))).findFirst().orElseThrow();
            JsonNode journalNotice=JSON.valueToTree(largeLog.get("data")).path("journalLimit");
            assertEquals("ATT-JOURNAL-EVENT-TRUNCATED",journalNotice.path("code").asText());
            assertEquals("DATA_TRUNCATED",journalNotice.path("outcome").asText());
            stopInspectionProcesses(runtime);
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
            assertTrue(shortShape.containsKey("projectionHeapDeltaBytesBestEffort"));
            assertTrue(longShape.containsKey("projectionHeapUsedBeforeBytes"));
        }
    }

    @Test void realLongMultiWorkloadReportSplitsSeriesAndPublishesWithinArchiveLimits() throws Exception {
        Path output = Files.createDirectories(temp.resolve("long-report-output"));
        att.load.LoadRunResult result = syntheticLongReportResult();
        Path report = new att.load.LoadReportWriter().write(output, result);
        Path runDirectory = output.resolve("load/synthetic-long");
        for (String name : List.of("load-summary.json", "load-summary.yaml", "report/index.html"))
            assertTrue(Files.size(runDirectory.resolve(name)) <= ArtifactArchive.MAX_ENTRY_BYTES, name + " exceeded the archive file cap");
        Map<?,?> summary = JSON.readValue(runDirectory.resolve("load-summary.json").toFile(), Map.class);
        assertEquals("att-load-summary/v1.3", summary.get("schemaVersion"));
        assertEquals(3_600L, ((Number) ((Map<?,?>) summary.get("metrics")).get("scheduled")).longValue());
        assertEquals(3_600L, ((Number) ((Map<?,?>) summary.get("metrics")).get("completed")).longValue());
        assertEquals(12, ((Map<?,?>) summary.get("workloads")).size());
        assertEquals("details/v1/manifest.json", summary.get("detailManifestPath"));
        Map<?,?> aggregateMetrics = (Map<?,?>) summary.get("metrics");
        assertTrue(((Map<?,?>) aggregateMetrics.get("buckets")).size() <= 32, "aggregate series has a bounded inline preview");
        assertTrue(aggregateMetrics.containsKey("detailSeries"), "aggregate series has versioned sidecar references");
        att.validation.JsonSchemaVerifier.verifyJson(att.validation.SchemaFiles.resolve(temp,
                "att-load-detail-reference-v1.schema.json"), JSON.writeValueAsString(aggregateMetrics.get("detailSeries")));
        Map<?,?> summaryMetrics = (Map<?,?>) ((Map<?,?>) summary.get("workloads")).get("workload-00");
        assertTrue(((Map<?,?>) summaryMetrics.get("metrics")).get("buckets") instanceof Map);
        assertTrue(((Map<?,?>) summaryMetrics.get("metrics")).containsKey("detailSeries"));
        assertTrue(Files.size(report) > 0L);

        Path archive = ArtifactArchive.build(output, temp.resolve("synthetic-long-load.zip"), 0L);
        long includedBytes = 0L; int includedEntries = 0; long detailBytes = 0L; int detailFiles = 0; long maxEntryBytes = 0L;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (ArtifactArchive.MANIFEST.equals(entry.getName())) continue;
                includedEntries++; includedBytes += entry.getSize();
                maxEntryBytes = Math.max(maxEntryBytes, entry.getSize());
                if (entry.getName().startsWith("load/synthetic-long/details/v1/")) {
                    detailFiles++; detailBytes += entry.getSize();
                }
                assertTrue(entry.getSize() <= ArtifactArchive.MAX_ENTRY_BYTES, entry.getName() + " exceeded per-file limit");
            }
            assertTrue(includedEntries <= ArtifactArchive.MAX_ENTRIES);
            assertTrue(includedBytes <= ArtifactArchive.MAX_TOTAL_BYTES);
            long reportOutputBytes = detailManifestBytes(runDirectory.resolve("details/v1/manifest.json"))
                    + Files.size(runDirectory.resolve("load-summary.json")) + Files.size(runDirectory.resolve("load-summary.yaml")) + Files.size(report);
            assertTrue(reportOutputBytes <= 120L * 1024 * 1024);
            JsonNode archiveManifest = JSON.readTree(zip.getInputStream(zip.getEntry(ArtifactArchive.MANIFEST)));
            assertTrue(archiveManifest.path("complete").asBoolean(), archiveManifest.toString());
            String reportManifestPath = "load/synthetic-long/details/v1/manifest.json";
            JsonNode detailManifest = JSON.readTree(zip.getInputStream(zip.getEntry(reportManifestPath)));
            assertEquals("att-load-detail-manifest/v1", detailManifest.path("schemaVersion").asText());
            att.validation.JsonSchemaVerifier.verifyJson(att.validation.SchemaFiles.resolve(temp,
                    "att-load-detail-manifest-v1.schema.json"), detailManifest.toString());
            assertTrue(detailManifest.path("complete").asBoolean());
            assertEquals(12, detailManifest.path("workloads").size());
            assertTrue(detailManifest.path("detailFileCount").asInt() < 9_000);
            assertTrue(detailManifest.path("detailBytes").asLong() < 100L * 1024 * 1024);
            assertEquals(detailManifest.path("detailFileCount").asInt(), detailFiles,
                    "every file described by the detail manifest is present in the archive");
            assertEquals(detailManifest.path("detailBytes").asLong(), detailBytes,
                    "manifest byte accounting matches every archived detail file");
            for (JsonNode series : detailManifest.path("series")) {
                int itemCount = 0;
                int part = 0;
                JsonNode files = series.path("files");
                assertTrue(files.isArray() && files.size() > 0, series.toString());
                for (JsonNode chunkPath : files) {
                    JsonNode chunk = JSON.readTree(zip.getInputStream(zip.getEntry("load/synthetic-long/" + chunkPath.asText())));
                    assertEquals("att-load-detail-series/v1", chunk.path("schemaVersion").asText());
                    att.validation.JsonSchemaVerifier.verifyJson(att.validation.SchemaFiles.resolve(temp,
                            "att-load-detail-series-v1.schema.json"), chunk.toString());
                    assertEquals(++part, chunk.path("part").asInt());
                    assertEquals(files.size(), chunk.path("parts").asInt());
                    itemCount += chunk.path("items").size();
                }
                assertEquals(series.path("count").asInt(), itemCount, "no metric-series items were silently omitted: " + series.path("target"));
            }
            JsonNode aggregateBucketSeries = null;
            for (JsonNode series : detailManifest.path("aggregate").path("series"))
                if ("metrics/buckets".equals(series.path("target").asText())) aggregateBucketSeries = series;
            assertNotNull(aggregateBucketSeries, "aggregate rolling buckets must be fully represented in sidecars");
            int aggregateBucketCount = 0;
            for (JsonNode chunkPath : aggregateBucketSeries.path("files")) {
                JsonNode chunk = JSON.readTree(zip.getInputStream(zip.getEntry("load/synthetic-long/" + chunkPath.asText())));
                aggregateBucketCount += chunk.path("items").size();
            }
            assertEquals(3_600, aggregateBucketCount);
            for (JsonNode workload : detailManifest.path("workloads")) {
                String workloadPath = workload.path("summaryPath").asText();
                java.util.zip.ZipEntry workloadEntry = zip.getEntry("load/synthetic-long/" + workloadPath);
                assertNotNull(workloadEntry, workloadPath);
                JsonNode workloadDetail = JSON.readTree(zip.getInputStream(workloadEntry));
                assertEquals("att-load-workload-detail/v1", workloadDetail.path("schemaVersion").asText());
                att.validation.JsonSchemaVerifier.verifyJson(att.validation.SchemaFiles.resolve(temp,
                        "att-load-workload-detail-v1.schema.json"), workloadDetail.toString());
                JsonNode bucketSeries = null;
                for (JsonNode series : workload.path("series")) if ("metrics/buckets".equals(series.path("target").asText())) bucketSeries = series;
                assertNotNull(bucketSeries, workload.toString());
                int bucketCount = 0;
                for (JsonNode chunkPath : bucketSeries.path("files")) {
                    JsonNode chunk = JSON.readTree(zip.getInputStream(zip.getEntry("load/synthetic-long/" + chunkPath.asText())));
                    assertEquals("att-load-detail-series/v1", chunk.path("schemaVersion").asText());
                    bucketCount += chunk.path("items").size();
                }
                assertEquals(3_600, bucketCount, workload.toString());
            }
            System.out.println("LOAD_REPORT_ARCHIVE_BENCHMARK " + JSON.writeValueAsString(fields(
                    "scenario", "synthetic 60-minute, 12-workload", "summaryJsonBytes", Files.size(runDirectory.resolve("load-summary.json")),
                    "summaryYamlBytes", Files.size(runDirectory.resolve("load-summary.yaml")), "reportHtmlBytes", Files.size(report),
                    "detailBytes", detailManifest.path("detailBytes").asLong(), "detailFiles", detailManifest.path("detailFileCount").asInt(),
                    "archiveEntries", includedEntries, "archiveUncompressedBytes", includedBytes,
                    "archiveLargestEntryBytes", maxEntryBytes, "reportOutputBytes", reportOutputBytes,
                    "archiveEntryLimitBytes", ArtifactArchive.MAX_ENTRY_BYTES, "archiveTotalLimitBytes", ArtifactArchive.MAX_TOTAL_BYTES,
                    "archiveEntryLimit", ArtifactArchive.MAX_ENTRIES)));
        }
    }

    private long detailManifestBytes(Path manifestPath) throws Exception {
        JsonNode manifest = JSON.readTree(manifestPath.toFile());
        return manifest.path("detailBytes").asLong();
    }

    private Map<String, Object> measure(ServerRuntime runtime, String id, Map<String, Object> raw, long durationMs) throws Exception {
        Job job = createJob(runtime, id);
        Runtime jvm = Runtime.getRuntime();
        long heapUsedBefore = jvm.totalMemory() - jvm.freeMemory();
        Map<String, Object> compact = att.server.api.LoadTerminalResult.projectOperationResult(raw,
                String.valueOf(raw.get("status")), ((Number) raw.get("exitCode")).intValue());
        long heapUsedAfter = jvm.totalMemory() - jvm.freeMemory();
        long heapDelta = heapUsedAfter - heapUsedBefore;
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
                "projectionHeapUsedBeforeBytes", heapUsedBefore, "projectionHeapUsedAfterBytes", heapUsedAfter,
                "projectionHeapDeltaBytesBestEffort", heapDelta,
                "archiveState", ((Map<?, ?>) summary.get("archive")).get("status"),
                "omittedWorkloads", omitted.get("workloads"), "omittedBuckets", omitted.get("timeSeriesBuckets"));
        return result;
    }

    private att.load.LoadRunResult syntheticLongReportResult() throws Exception {
        StringBuilder descriptor = new StringBuilder("schemaVersion: att-load/v1.9\nworkloads:\n");
        for (int index = 0; index < 12; index++) descriptor.append("  - id: workload-")
                .append(String.format(java.util.Locale.ROOT, "%02d", index))
                .append("\n    target: {type: tool, id: synthetic}\n    load: {users: 1, duration: 1h}\n");
        Path scenarioFile = temp.resolve("synthetic-long.yaml");
        Files.writeString(scenarioFile, descriptor.toString(), StandardCharsets.UTF_8);
        att.load.LoadScenario scenario = new att.load.LoadScenarioLoader(temp).load(scenarioFile);
        java.time.Instant started = java.time.Instant.parse("2026-10-26T00:00:00Z");
        long startMs = started.toEpochMilli(); long endMs = startMs + 3_600_000L;
        att.load.LoadMetrics aggregate = att.load.LoadMetrics.forScenario(scenario, startMs, 3_600_000L);
        for (int index = 0; index < 3_600; index++) {
            att.load.LoadWorkload workload = scenario.workloads().get(index % scenario.workloads().size());
            aggregate.onEvent(syntheticEvent("synthetic-long", scenario.model().wireName(), index, startMs, workload));
        }
        aggregate.finish(endMs);

        Map<String, att.load.LoadRunResult> workloads = new LinkedHashMap<>();
        for (int workloadIndex = 0; workloadIndex < scenario.workloads().size(); workloadIndex++) {
            att.load.LoadWorkload workload = scenario.workloads().get(workloadIndex);
            att.load.LoadScenario childScenario = scenario.forWorkload(workload);
            att.load.LoadMetrics childMetrics = att.load.LoadMetrics.forScenario(childScenario, startMs, 3_600_000L);
            for (int index = 0; index < 3_600; index++)
                childMetrics.onEvent(syntheticEvent("synthetic-child-" + workload.id(), childScenario.model().wireName(), index, startMs, workload));
            childMetrics.finish(endMs);
            att.load.LoadRunResult child = new att.load.LoadRunResult("synthetic-child-" + workload.id(), childScenario,
                    started, java.time.Instant.ofEpochMilli(endMs), childMetrics.snapshot());
            workloads.put(workload.id(), child);
        }

        java.lang.reflect.Constructor<att.load.LoadRunResult> constructor = att.load.LoadRunResult.class.getDeclaredConstructor(
                String.class, att.load.LoadScenario.class, java.time.Instant.class, java.time.Instant.class,
                att.load.LoadMetricsSnapshot.class, att.load.LoadThresholdSummary.class, Map.class, Map.class, Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance("synthetic-long", scenario, started, java.time.Instant.ofEpochMilli(endMs),
                aggregate.snapshot(), att.load.LoadThresholdSummary.empty(), Map.of(), Map.of(), workloads);
    }

    private att.load.LoadEvent syntheticEvent(String runId, String model, int index, long startMs,
                                               att.load.LoadWorkload workload) {
        long scheduled = startMs + index * 1000L;
        return att.load.LoadEvent.completed(runId, model, "STEADY", "iteration-" + index, null, index + 1L,
                scheduled, scheduled + 5L, scheduled + 25L, att.core.ResultStatus.PASS)
                .withWorkloadIdentity(workload.id(), workload.targetType(), workload.targetId());
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
        return runtime(name,builder->{throw new UnsupportedOperationException("worker launch is not used");});
    }

    private ServerRuntime runtime(String name,ServerRuntime.WorkerProcessLauncher launcher) throws Exception {
        return runtime(name,launcher,null);
    }

    private ServerRuntime runtime(String name,ServerRuntime.WorkerProcessLauncher launcher,Path libs) throws Exception {
        Path allowed = Files.createDirectories(temp.resolve(name + "-packages"));
        Path pkg = Files.createDirectories(allowed.resolve("p"));
        Path data = temp.resolve(name + "-data");
        Path configFile = temp.resolve(name + "-server.yaml");
        Files.writeString(configFile, "server:\n  dataDir: " + ServerRuntimeTestSupport.yaml(data)
                + "\nworkers: {}\npackages:\n  allowedRoots:\n    - " + ServerRuntimeTestSupport.yaml(allowed)
                + "\n  entries:\n    p: " + ServerRuntimeTestSupport.yaml(pkg) + "\n");
        return new ServerRuntime(ServerConfig.load(configFile), libs==null?null:libs.toString(), ServerRuntimeTestSupport.resolvingConfiguration(launcher));
    }

    private Path workerLibraries(String name) throws Exception {
        Path libs=Files.createDirectory(temp.resolve(name));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-server-api",Path.of("att-server-api/target/classes"));
        ServerRuntimeTestSupport.addModuleJar(libs,"att-security",Path.of("att-security/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        return libs;
    }

    private static void stopInspectionProcesses(ServerRuntime runtime) throws Exception {
        java.lang.reflect.Field workersField=ServerRuntime.class.getDeclaredField("inspectionWorkerPool");
        workersField.setAccessible(true);
        for(Object worker:(List<?>)workersField.get(runtime)) {
            java.lang.reflect.Field processField=worker.getClass().getDeclaredField("process");
            processField.setAccessible(true);
            Process process=(Process)processField.get(worker);
            if(process!=null&&process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    public static class OversizedLoadWorker {
        public static void main(String[] args) throws Exception {
            new java.io.BufferedReader(new java.io.InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
            System.out.println("{\"type\":\"DIAGNOSTIC\",\"code\":\"HTTP_RESPONSE_TOO_LARGE\",\"summary\":\"HTTP response exceeded its configured byte limit\",\"limitName\":\"httphelper.defaults.maxResponseBytes\",\"configuredLimit\":10,\"observed\":11,\"outcome\":\"EXECUTION_ERROR\",\"stage\":\"execution\"}");
            System.out.print("{\"type\":\"LOG\",\"message\":\"");
            System.out.print("journal-private-canary"+"x".repeat(JobEvents.MAX_EVENT_DATA_BYTES));
            System.out.println("\"}");
            System.out.println("{\"type\":\"RESULT\",\"status\":\"ERROR\",\"exitCode\":3,\"padding\":\"private-canary"+"x".repeat(WorkerProtocol.MAX_FRAME_BYTES)+"\"}");
            System.out.println("{\"type\":\"RESULT\",\"status\":\"ERROR\",\"exitCode\":3,\"result\":{\"executionId\":\"load-e2e\",\"status\":\"ERROR\",\"exitCode\":3,\"summary\":{\"schemaVersion\":\"att-load-summary/v1.2\",\"runId\":\"load-e2e\",\"status\":\"ERROR\",\"executionStatus\":\"ERROR\",\"metrics\":{\"scheduled\":1,\"started\":1,\"completed\":0,\"runtimeError\":1}}}}");
            System.out.flush();
            System.exit(3);
        }
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
