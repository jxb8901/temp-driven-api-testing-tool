package att.server;

import att.api.DefaultAttService;
import att.api.SnapshotRequest;
import att.api.SnapshotResult;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tomcat.util.scan.StandardJarScanFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in end-to-end benchmark for actual Server Workers and HTTP/SSE delivery. */
class ServerWorkerSsePerformanceBenchmark {
    private static final String CONTEXT = "/tools/att";
    private static final String PACKAGE_ID = "benchmark";
    private static final String SSE_BODY = "x".repeat(192);

    @TempDir Path temp;

    @Test void writesWorkerAndSseBenchmarkJson() throws Exception {
        assumeTrue(Boolean.getBoolean("att.server.benchmark.workerSse"), "opt-in local benchmark");
        Path war = Path.of(System.getProperty("att.server.war", "att-server/target/att-server-4.0.1.war"))
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(war), "Packaged Server WAR must exist: " + war);

        int caseCount = integerProperty("att.server.benchmark.workerSse.cases", 300, 1);
        int replayEvents = integerProperty("att.server.benchmark.workerSse.events", 10000, 1000);
        int runs = integerProperty("att.server.benchmark.workerSse.runs", 2, 1);
        int warmups = integerProperty("att.server.benchmark.workerSse.warmups", 1, 0);
        int slowDelayMs = integerProperty("att.server.benchmark.workerSse.slowDelayMs", 1, 0);
        int soakSeconds = integerProperty("att.server.benchmark.workerSse.soakSeconds", 0, 0);
        int[] concurrency = integerListProperty("att.server.benchmark.workerSse.workers", new int[]{1, 4, 8});

        Path packageRoots = Files.createDirectories(temp.resolve("packages"));
        Path packageRoot = Files.createDirectories(packageRoots.resolve(PACKAGE_ID));
        installPackageFixture(packageRoot, caseCount);
        SnapshotResult snapshot = new DefaultAttService().snapshot(new SnapshotRequest(packageRoot, null, null,
                List.of(Path.of("testcase/event-run.xlsx")), null, Collections.emptySet(), false));
        assertEquals("PASS", snapshot.status(), "Benchmark suite snapshot must pass");

        Path config = temp.resolve("server.yaml");
        Path dataDir = temp.resolve("server-data");
        writeServerConfig(config, dataDir, packageRoots, packageRoot, Math.max(8, max(concurrency)));
        Path exploded = unpackWar(war, temp.resolve("exploded-war"));
        String oldConfig = System.getProperty("att.server.config");
        System.setProperty("att.server.config", config.toString());

        ServerHandle server = null;
        try {
            server = startServer(exploded, temp.resolve("tomcat-first"));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", "att-server-worker-sse-benchmark/v1");
            report.put("runtime", Map.of("version", att.Version.PRODUCT, "warSha256", sha256(war)));
            report.put("environment", environment());
            report.put("settings", Map.of("caseCount", caseCount, "warmups", warmups, "runs", runs,
                    "workerConcurrency", Arrays.stream(concurrency).boxed().toList(),
                    "observerModes", List.of("none", "fast", "slow"), "syntheticReplayEventsPerJob", replayEvents,
                    "slowReaderDelayMsPerEvent", slowDelayMs, "soakSeconds", soakSeconds, "maxEventsPerJob", 100000));

            List<Map<String, Object>> workerScenarios = new ArrayList<>();
            Map<Integer, List<String>> replayJobIds = new LinkedHashMap<>();
            for (int workerCount : concurrency) {
                for (String observerMode : List.of("none", "fast", "slow")) {
                    List<Map<String, Object>> samples = new ArrayList<>();
                    for (int sampleIndex = 0; sampleIndex < warmups + runs; sampleIndex++) {
                        Map<String, Object> sample = measureRunBatch(server.port, workerCount, observerMode,
                                caseCount, sampleIndex, slowDelayMs);
                        if (sampleIndex >= warmups) samples.add(sample);
                        if ("none".equals(observerMode) && sampleIndex == warmups + runs - 1)
                            replayJobIds.put(workerCount, stringList(sample.get("jobIds")));
                    }
                    Map<String, Object> scenario = new LinkedHashMap<>();
                    scenario.put("workerConcurrency", workerCount);
                    scenario.put("observerMode", observerMode);
                    scenario.put("measuredRuns", runs);
                    scenario.put("samples", samples);
                    workerScenarios.add(scenario);
                }
            }
            report.put("workerScenarios", workerScenarios);

            List<Map<String, Object>> serverCommands = new ArrayList<>();
            serverCommands.add(measureSingleCommand(server.port, "debug", Map.of("packageId", PACKAGE_ID,
                    "debugId", "server-bench-debug", "target", Map.of("type", "template", "id", "BENCH_EVENT"))));
            serverCommands.add(measureSingleCommand(server.port, "load", Map.of("packageId", PACKAGE_ID,
                    "runId", "server-bench-load", "scenario", "load/server-events.yaml")));
            report.put("eventHeavyCommands", serverCommands);

            List<Map<String, Object>> replayScenarios = new ArrayList<>();
            for (int workerCount : concurrency) {
                replayScenarios.add(measureSyntheticReplay(server.port, workerCount,
                        replayJobIds.get(workerCount), replayEvents, slowDelayMs));
            }
            report.put("sseReplay", replayScenarios);

            RestartCapture restart = restartAndReplay(server, exploded,
                    temp.resolve("tomcat-restarted"), concurrency, replayJobIds, replayEvents);
            server = restart.server;
            report.put("restartReplay", restart.report);
            if (soakSeconds > 0) report.put("optionalSoak", measureOptionalSoak(server.port,
                    max(concurrency), caseCount, soakSeconds));

            Path output = Path.of(System.getProperty("att.server.benchmark.workerSse.output",
                    "att-server/target/server-worker-sse-benchmark.json")).toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            Files.writeString(output, ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
            System.out.println("Server Worker/SSE benchmark written to " + output);
        } finally {
            if (server != null) stopServer(server);
            if (oldConfig == null) System.clearProperty("att.server.config");
            else System.setProperty("att.server.config", oldConfig);
        }
    }

    private Map<String, Object> measureRunBatch(int port, int workerCount, String observerMode,
                                                  int caseCount, int sampleIndex, int slowDelayMs) throws Exception {
        long started = System.nanoTime();
        List<String> jobIds = new ArrayList<>();
        for (int index = 0; index < workerCount; index++) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("packageId", PACKAGE_ID);
            request.put("runId", "server-bench-w" + workerCount + "-" + observerMode + "-" + sampleIndex + "-" + index);
            request.put("suites", List.of("testcase/event-run.xlsx"));
            jobIds.add(submit(port, "run", request));
        }

        ExecutorService readers = Executors.newFixedThreadPool(Math.max(1, workerCount));
        List<Future<Map<String, Object>>> streams = new ArrayList<>();
        CountDownLatch streamStart = new CountDownLatch(1);
        try {
            if (!"none".equals(observerMode)) {
                for (String jobId : jobIds) {
                    streams.add(readSseAsync(readers, port, jobId, null,
                            "slow".equals(observerMode) ? slowDelayMs : 0, -1, streamStart));
                }
                streamStart.countDown();
            }
            Map<String, Object> serverPeaks = waitForJobs(port, jobIds, DurationLimit.WORKER_JOB_SECONDS);
            List<Map<String, Object>> jobs = new ArrayList<>();
            for (String jobId : jobIds) {
                Map<String, Object> view = getJson(port, "/api/v1/jobs/" + jobId);
                assertEquals("PASS", view.get("status"), "Server Worker job must pass: " + jobId + " " + view);
                Map<String, Object> job = new LinkedHashMap<>();
                job.put("jobId", jobId);
                job.put("status", view.get("status"));
                job.put("performance", view.getOrDefault("performance", Map.of()));
                job.put("journalEventCount", journalEventCount(jobId));
                job.put("journalBytesOnDisk", journalBytes(jobId));
                jobs.add(job);
            }
            List<Map<String, Object>> sse = new ArrayList<>();
            for (Future<Map<String, Object>> future : streams) sse.add(future.get(DurationLimit.WORKER_JOB_SECONDS, TimeUnit.SECONDS));
            long elapsed = System.nanoTime() - started;
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("workerConcurrency", workerCount);
            sample.put("observerMode", observerMode);
            sample.put("caseCountPerJob", caseCount);
            sample.put("jobCount", jobIds.size());
            sample.put("status", "PASS");
            sample.put("totalWallMs", nanosToMs(elapsed));
            sample.put("jobsPerSecond", jobIds.size() / (elapsed / 1_000_000_000.0));
            sample.put("serverMetricsPeak", serverPeaks);
            sample.put("jobs", jobs);
            sample.put("sseReaders", sse);
            sample.put("jobIds", jobIds);
            return sample;
        } finally {
            streamStart.countDown();
            readers.shutdownNow();
            readers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Map<String, Object> measureSingleCommand(int port, String command, Map<String, Object> request) throws Exception {
        long started = System.nanoTime();
        String id = submit(port, command, request);
        Map<String, Object> peaks = waitForJobs(port, List.of(id), DurationLimit.WORKER_JOB_SECONDS);
        Map<String, Object> view = getJson(port, "/api/v1/jobs/" + id);
        Map<String, Object> jobResult = getJson(port, "/api/v1/jobs/" + id + "/result");
        assertEquals("PASS", view.get("status"), command + " Worker job must pass: " + view + " result=" + jobResult);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", command);
        result.put("jobId", id);
        result.put("status", view.get("status"));
        result.put("wallMs", nanosToMs(System.nanoTime() - started));
        result.put("journalEventCount", journalEventCount(id));
        result.put("performance", view.getOrDefault("performance", Map.of()));
        result.put("serverMetricsPeak", peaks);
        return result;
    }

    private Map<String, Object> measureOptionalSoak(int port, int workerCount, int caseCount, int durationSeconds)
            throws Exception {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(durationSeconds);
        int batchCount = 0, totalJobs = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            Map<String, Object> sample = measureRunBatch(port, workerCount, "none", caseCount,
                    1_000_000 + batchCount, 0);
            totalJobs += number(sample.get("jobCount"));
            if (samples.size() < 100) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("batch", batchCount);
                summary.put("wallMs", sample.get("totalWallMs"));
                summary.put("jobsPerSecond", sample.get("jobsPerSecond"));
                summary.put("serverMetricsPeak", sample.get("serverMetricsPeak"));
                samples.add(summary);
            }
            batchCount++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumDurationSeconds", durationSeconds);
        result.put("observedWallMs", nanosToMs(System.nanoTime() - started));
        result.put("workerConcurrency", workerCount);
        result.put("caseCountPerJob", caseCount);
        result.put("observerMode", "none");
        result.put("batches", batchCount);
        result.put("jobs", totalJobs);
        result.put("sampleLimit", 100);
        result.put("samples", samples);
        return result;
    }

    private Map<String, Object> measureSyntheticReplay(int port, int workerCount, List<String> jobIds,
                                                         int eventCount, int slowDelayMs) throws Exception {
        assertTrue(jobIds != null && jobIds.size() == workerCount, "Retained Worker job IDs are required for replay");
        List<Map<String, Object>> cursors = new ArrayList<>();
        for (int index = 0; index < jobIds.size(); index++) {
            String jobId = jobIds.get(index);
            Object journal = events(port, jobId);
            long cursor = latestEvent(journal);
            int count = index == 0 ? eventCount : Math.min(1000, eventCount);
            long appendStarted = System.nanoTime();
            for (int event = 1; event <= count; event++)
                appendEvent(journal, "progress", Map.of("benchmarkSequence", event, "payload", SSE_BODY));
            long appendElapsed = System.nanoTime() - appendStarted;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jobId", jobId);
            item.put("lastEventId", cursor);
            item.put("appendedEvents", count);
            item.put("appendMs", nanosToMs(appendElapsed));
            item.put("appendEventsPerSecond", count / Math.max(0.000001, appendElapsed / 1_000_000_000.0));
            item.put("journalBytesOnDisk", journalBytes(jobId));
            cursors.add(item);
        }

        ExecutorService readers = Executors.newFixedThreadPool(jobIds.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String, Object>>> replayFutures = new ArrayList<>();
        try {
            long replayStarted = System.nanoTime();
            for (Map<String, Object> item : cursors) {
                replayFutures.add(readSseAsync(readers, port, (String) item.get("jobId"),
                        ((Number) item.get("lastEventId")).longValue(), 0,
                        ((Number) item.get("appendedEvents")).intValue(), start));
            }
            start.countDown();
            List<Map<String, Object>> parallelReplay = new ArrayList<>();
            for (int index = 0; index < replayFutures.size(); index++) {
                Map<String, Object> replay = replayFutures.get(index).get(DurationLimit.SSE_READ_SECONDS, TimeUnit.SECONDS);
                assertEquals(((Number) cursors.get(index).get("appendedEvents")).longValue(),
                        ((Number) replay.get("eventCount")).longValue(), "SSE replay should deliver every appended event");
                parallelReplay.add(replay);
            }
            long replayElapsed = System.nanoTime() - replayStarted;

            Map<String, Object> leader = cursors.get(0);
            long leaderCursor = ((Number) leader.get("lastEventId")).longValue();
            String leaderId = (String) leader.get("jobId");
            Map<String, Object> resume = readSse(port, leaderId, leaderCursor + eventCount - 1000, 0, 1000);
            assertEquals(1000L, ((Number) resume.get("eventCount")).longValue(), "Last-Event-ID resume should replay the final 1,000 events");

            Map<String, Object> slow = readSse(port, leaderId, leaderCursor, slowDelayMs, eventCount);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workerConcurrency", workerCount);
            result.put("syntheticEventsAndPayload", "progress events with 192-byte fixed payload; appended after completed real Worker jobs");
            result.put("jobs", cursors);
            result.put("parallelReplay", parallelReplay);
            result.put("parallelReplayWallMs", nanosToMs(replayElapsed));
            result.put("lastEventIdResume", resume);
            result.put("slowConsumerReplay", slow);
            return result;
        } finally {
            start.countDown();
            readers.shutdownNow();
            readers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private RestartCapture restartAndReplay(ServerHandle server, Path exploded, Path baseDir,
                                             int[] concurrency, Map<Integer, List<String>> jobIds,
                                             int replayEvents) throws Exception {
        int workerCount = concurrency[concurrency.length - 1];
        String jobId = jobIds.get(workerCount).get(0);
        long latest = latestEvent(events(server.port, jobId));
        long cursor = latest - Math.min(1000, replayEvents);
        stopServer(server);
        ServerHandle restarted = startServer(exploded, baseDir);
        try {
            Map<String, Object> replay = readSse(restarted.port, jobId, cursor, 0, Math.min(1000, replayEvents));
            assertEquals(Math.min(1000, replayEvents), ((Number) replay.get("eventCount")).intValue(),
                    "Restarted Server should replay retained events after Last-Event-ID");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobId", jobId);
            result.put("lastEventId", cursor);
            result.put("replay", replay);
            result.put("serverRestarted", true);
            return new RestartCapture(restarted, result);
        } catch (Exception failure) {
            stopServer(restarted);
            throw failure;
        }
    }

    private Map<String, Object> waitForJobs(int port, List<String> jobIds, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        int activePeak = 0;
        int queuePeak = 0;
        boolean complete = false;
        while (System.nanoTime() < deadline) {
            Map<String, Object> metrics = getJson(port, "/api/v1/metrics");
            activePeak = Math.max(activePeak, number(metrics.get("activeWorkers")));
            queuePeak = Math.max(queuePeak, number(metrics.get("queueDepth")));
            complete = true;
            for (String jobId : jobIds) {
                String status = String.valueOf(getJson(port, "/api/v1/jobs/" + jobId).get("status"));
                if (!List.of("PASS", "FAIL", "ERROR", "INVALID", "CANCELLED").contains(status)) {
                    complete = false;
                    break;
                }
            }
            if (complete) break;
            Thread.sleep(50L);
        }
        assertTrue(complete, "Server Worker jobs should reach a terminal state within " + timeoutSeconds + " seconds");
        return Map.of("activeWorkersPeak", activePeak, "queueDepthPeak", queuePeak);
    }

    private Future<Map<String, Object>> readSseAsync(ExecutorService pool, int port, String jobId,
                                                       Long lastEventId, int delayMs, int expectedEvents,
                                                       CountDownLatch start) {
        return pool.submit(() -> {
            if (!start.await(DurationLimit.SSE_READ_SECONDS, TimeUnit.SECONDS))
                throw new IllegalStateException("SSE reader start barrier timed out");
            return expectedEvents < 0 ? readSse(port, jobId, lastEventId, delayMs)
                    : readSse(port, jobId, lastEventId, delayMs, expectedEvents);
        });
    }

    private Map<String, Object> readSse(int port, String jobId, Long lastEventId, int delayMs) throws Exception {
        return readSse(port, jobId, lastEventId, delayMs, -1);
    }

    private Map<String, Object> readSse(int port, String jobId, Long lastEventId, int delayMs,
                                         int expectedEvents) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + CONTEXT + "/api/v1/jobs/" + jobId + "/events");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(DurationLimit.SSE_READ_SECONDS * 1000);
        if (lastEventId != null) connection.setRequestProperty("Last-Event-ID", String.valueOf(lastEventId));
        long started = System.nanoTime();
        long bytes = 0L, events = 0L, firstId = -1L, lastId = -1L, firstEventNanos = 0L;
        try {
            int status = connection.getResponseCode();
            assertEquals(200, status, "SSE response must be HTTP 200");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((expectedEvents < 0 || events < expectedEvents) && (line = reader.readLine()) != null) {
                    bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    if (line.startsWith("id: ")) {
                        long id = Long.parseLong(line.substring(4).trim());
                        if (firstId < 0L) { firstId = id; firstEventNanos = System.nanoTime(); }
                        lastId = id;
                        events++;
                        if (delayMs > 0) Thread.sleep(delayMs);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
        long elapsed = System.nanoTime() - started;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("lastEventId", lastEventId);
        result.put("eventCount", events);
        result.put("firstEventId", firstId < 0L ? null : firstId);
        result.put("timeToFirstEventMs", firstEventNanos == 0L ? null : nanosToMs(firstEventNanos - started));
        result.put("lastEventIdReceived", lastId < 0L ? null : lastId);
        result.put("bytes", bytes);
        result.put("wallMs", nanosToMs(elapsed));
        result.put("eventsPerSecond", events / Math.max(0.000001, elapsed / 1_000_000_000.0));
        result.put("consumerDelayMsPerEvent", delayMs);
        return result;
    }

    private String submit(int port, String command, Map<String, Object> request) throws Exception {
        Map<String, Object> response = postJson(port, "/api/v1/jobs/" + command, request);
        Object jobId = response.get("jobId");
        assertTrue(jobId instanceof String && ((String) jobId).matches("J[0-9A-F]{16}"),
                "Server should return a valid Job ID: " + response);
        return (String) jobId;
    }

    private Map<String, Object> postJson(int port, String path, Map<String, Object> body) throws Exception {
        HttpURLConnection connection = open(port, path);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try {
            connection.getOutputStream().write(ServerRuntime.JSON.writeValueAsBytes(body));
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(202, status, "Server should accept " + path + ": " + text);
            return ServerRuntime.JSON.readValue(text, Map.class);
        } finally {
            connection.disconnect();
        }
    }

    private Map<String, Object> getJson(int port, String path) throws Exception {
        HttpURLConnection connection = open(port, path);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(200, status, "GET " + path + " should succeed: " + text);
            return ServerRuntime.JSON.readValue(text, Map.class);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + CONTEXT + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        return connection;
    }

    private Object runtime(int port) throws Exception {
        ServerHandle handle = ServerHandle.ACTIVE.get(port);
        if (handle == null) throw new IllegalStateException("No embedded Tomcat registered for port " + port);
        Object value = handle.context.getServletContext().getAttribute(ServerBootstrap.RUNTIME);
        if (value != null && ServerRuntime.class.getName().equals(value.getClass().getName())) return value;
        throw new IllegalStateException("Unable to find the embedded ATT Server runtime");
    }

    private Object events(int port, String jobId) throws Exception {
        var method = runtime(port).getClass().getDeclaredMethod("events", String.class);
        method.setAccessible(true);
        return method.invoke(runtime(port), jobId);
    }

    private long latestEvent(Object journal) throws Exception {
        var method = journal.getClass().getDeclaredMethod("latest");
        method.setAccessible(true);
        return ((Number) method.invoke(journal)).longValue();
    }

    private void appendEvent(Object journal, String type, Map<String, Object> data) throws Exception {
        var method = journal.getClass().getDeclaredMethod("append", String.class, Map.class);
        method.setAccessible(true);
        method.invoke(journal, type, data);
    }

    private int journalEventCount(String jobId) throws Exception {
        Path file = temp.resolve("server-data/jobs").resolve(jobId).resolve("events.jsonl");
        if (!Files.isRegularFile(file)) return 0;
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) { return (int) lines.count(); }
    }

    private long journalBytes(String jobId) throws Exception {
        Path file = temp.resolve("server-data/jobs").resolve(jobId).resolve("events.jsonl");
        return Files.isRegularFile(file) ? Files.size(file) : 0L;
    }

    private void installPackageFixture(Path root, int caseCount) throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates/BENCH_EVENT"));
        Files.createDirectories(root.resolve("load"));
        copyTree(Path.of("schemas"), root.resolve("schemas"));
        Files.writeString(root.resolve("config/config.yaml"),
                "schemaVersion: att-config/v2.12\noutputDirectory: output\nenvironment: BENCHMARK\n"
                        + "testcase: {root: testcase}\ntemplates: {root: templates}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("templates/BENCH_EVENT/template.yaml"),
                "schemaVersion: att-template/v3.6\nname: BENCH_EVENT\ndescription: Event-heavy Server benchmark target\n"
                        + "actions:\n  writeEvent:\n    type: log\n    message: \"Server benchmark case ${META.SOURCE.caseId?}\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("templates/BENCH_EVENT/debug.yaml"),
                "schemaVersion: att-debug/v1.2\ninputs: {}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("testcase/event-run.yaml"),
                "schemaVersion: att-sidecar/v2.2\nid: serverEventBenchmark\nexcel:\n  sheet: Benchmark\n"
                        + "  headerRows: 2\n  caseId: Case ID\n  tags: Tags\nstages:\n  - key: main\n"
                        + "    template: Template\n    required: true\n    onFailure: stop\n", StandardCharsets.UTF_8);
        createWorkbook(root.resolve("testcase/event-run.xlsx"), caseCount);
        Files.writeString(root.resolve("load/server-events.yaml"),
                "schemaVersion: att-load/v1.6\nseed: 177\nworkloads:\n  - id: server-events\n"
                        + "    target: {type: template, id: BENCH_EVENT}\n    load:\n      arrivalRate: 100/s\n"
                        + "      warmup: 1s\n      duration: 3s\n      maxConcurrent: 128\n"
                        + "      overloadPolicy: drop\nevidence:\n  mode: metrics\n  maxSamples: 10\n",
                StandardCharsets.UTF_8);
    }

    private void createWorkbook(Path path, int caseCount) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Benchmark");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("ATT Server Worker and SSE benchmark");
            Row header = sheet.createRow(1);
            String[] columns = {"Case ID", "Tags", "Name", "Template"};
            for (int column = 0; column < columns.length; column++) header.createCell(column).setCellValue(columns[column]);
            for (int index = 1; index <= caseCount; index++) {
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(String.format(java.util.Locale.ROOT, "BENCH%05d", index));
                row.createCell(1).setCellValue("benchmark");
                row.createCell(2).setCellValue("Synthetic event-heavy case");
                row.createCell(3).setCellValue("BENCH_EVENT");
            }
            try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        }
    }

    private void writeServerConfig(Path path, Path dataDir, Path packages, Path packageRoot, int maxConcurrent) throws Exception {
        Files.writeString(path, "server:\n  dataDir: " + dataDir.toAbsolutePath() + "\n"
                + "  authenticationRequired: false\n  maxEventsPerJob: 100000\n"
                + "workers:\n  maxConcurrent: " + maxConcurrent + "\n  queuedLimit: 32\n  maxConcurrentLoad: 1\n"
                + "packages:\n  allowedRoots:\n    - " + packages.toAbsolutePath() + "\n  entries:\n    benchmark: "
                + packageRoot.toAbsolutePath() + "\n", StandardCharsets.UTF_8);
    }

    private ServerHandle startServer(Path explodedWar, Path baseDir) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toAbsolutePath().toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addWebapp(CONTEXT, explodedWar.toString());
        StandardJarScanFilter filter = new StandardJarScanFilter();
        filter.setDefaultPluggabilityScan(false);
        filter.setDefaultTldScan(false);
        context.getJarScanner().setJarScanFilter(filter);
        tomcat.start();
        int port = tomcat.getConnector().getLocalPort();
        ServerHandle handle = new ServerHandle(tomcat, context, port);
        ServerHandle.ACTIVE.put(port, handle);
        Object runtime = context.getServletContext().getAttribute(ServerBootstrap.RUNTIME);
        assertTrue(runtime != null && ServerRuntime.class.getName().equals(runtime.getClass().getName()),
                "ATT Server bootstrap listener should initialize in the unpacked WAR");
        return handle;
    }

    private void stopServer(ServerHandle handle) throws Exception {
        ServerHandle.ACTIVE.remove(handle.port);
        try { handle.tomcat.stop(); } finally { handle.tomcat.destroy(); }
    }

    private Path unpackWar(Path war, Path destination) throws Exception {
        Path root = Files.createDirectories(destination).toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(war))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                assertTrue(target.startsWith(root), "WAR entry must stay inside the exploded directory");
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
        return root;
    }

    private void copyTree(Path source, Path target) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Map<String, Object> environment() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVm", System.getProperty("java.vm.name"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("architecture", System.getProperty("os.arch"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("liveThreads", Thread.activeCount());
        result.put("capturedAt", Instant.now().toString());
        return result;
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return hex.toString();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static int integerProperty(String name, int fallback, int minimum) {
        int value = Integer.getInteger(name, fallback);
        if (value < minimum) throw new IllegalArgumentException(name + " must be >= " + minimum);
        return value;
    }

    private static int[] integerListProperty(String name, int[] fallback) {
        String configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) return fallback;
        return Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .mapToInt(Integer::parseInt).toArray();
    }

    private static int max(int[] values) { return Arrays.stream(values).max().orElse(1); }
    private static int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static double nanosToMs(long nanos) { return nanos / 1_000_000.0; }

    private static final class DurationLimit {
        static final int WORKER_JOB_SECONDS = 300;
        static final int SSE_READ_SECONDS = 180;
    }

    private static final class ServerHandle {
        static final Map<Integer, ServerHandle> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
        final Tomcat tomcat;
        final Context context;
        final int port;
        ServerHandle(Tomcat tomcat, Context context, int port) { this.tomcat = tomcat; this.context = context; this.port = port; }
    }

    private static final class RestartCapture {
        final ServerHandle server;
        final Map<String, Object> report;
        RestartCapture(ServerHandle server, Map<String, Object> report) { this.server = server; this.report = report; }
    }
}
