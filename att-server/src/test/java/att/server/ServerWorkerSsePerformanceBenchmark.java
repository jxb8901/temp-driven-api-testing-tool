package att.server;

import att.api.DefaultAttService;
import att.api.SnapshotRequest;
import att.api.SnapshotResult;
import org.apache.catalina.Context;
import org.apache.catalina.realm.MemoryRealm;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        List<String> observerModes = stringListProperty("att.server.benchmark.workerSse.observers",
                List.of("none", "fast", "slow"));

        Path packageRoots = Files.createDirectories(temp.resolve("packages"));
        Path packageRoot = Files.createDirectories(packageRoots.resolve(PACKAGE_ID));
        installPackageFixture(packageRoot, caseCount);
        Path runtimeConfig = Files.createDirectories(temp.resolve("runtime-home/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\noutputDirectory: output\nenvironment: BENCHMARK\nenvironments: {BENCHMARK: {}}\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\n",StandardCharsets.UTF_8);
        Path runtimeHome=runtimeConfig.getParent().getParent();
        Path localArchive=Path.of(System.getProperty("att.server.benchmark.localArchive",
                "att-dist/target/att-"+ServerBuildInfo.PRODUCT+"-local.tar.gz")).toAbsolutePath().normalize();
        Path runtimeBundle=installWorkerRuntime(runtimeHome,localArchive);
        SnapshotResult snapshot = new DefaultAttService().snapshot(new SnapshotRequest(packageRoot, runtimeConfig, null,
                List.of(Path.of("testcase/event-run.xlsx")), null, Collections.emptySet(), false));
        assertEquals("PASS", snapshot.status(), "Benchmark suite snapshot must pass");

        Path config = temp.resolve("server.yaml");
        Path dataDir = temp.resolve("server-data");
        writeServerConfig(config, runtimeConfig, dataDir, packageRoots, packageRoot, Math.max(8, max(concurrency)));
        Path exploded = unpackWar(war, temp.resolve("exploded-war"));
        String oldConfig = System.getProperty("att.server.config");
        String oldHome = System.getProperty("att.home");
        System.setProperty("att.server.config", config.toString());
        System.setProperty("att.home", runtimeConfig.getParent().getParent().toString());

        ServerHandle server = null;
        try {
            server = startServer(exploded, temp.resolve("tomcat-first"));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", "att-server-worker-sse-benchmark/v1");
            report.put("sourceRevision", System.getProperty("att.benchmark.commit", "unspecified"));
            report.put("runtime", Map.of("version", ServerBuildInfo.PRODUCT, "warSha256", sha256(war),
                    "workerRuntimeSha256", sha256(runtimeBundle)));
            report.put("environment", environment());
            report.put("settings", Map.of("caseCount", caseCount, "warmups", warmups, "runs", runs,
                    "workerConcurrency", Arrays.stream(concurrency).boxed().toList(),
                    "observerModes", observerModes, "syntheticReplayEventsPerJob", replayEvents,
                    "slowReaderDelayMsPerEvent", slowDelayMs, "soakSeconds", soakSeconds, "maxEventsPerJob", 100000,
                    "processResourceSampling", Boolean.getBoolean("att.benchmark.processResources")));

            List<Map<String, Object>> workerScenarios = new ArrayList<>();
            Map<Integer, List<String>> replayJobIds = new LinkedHashMap<>();
            for (int workerCount : concurrency) {
                for (String observerMode : observerModes) {
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
            report.put("packageValidation", measureSingleCommand(server.port, "validate",
                    Map.of("packageId", PACKAGE_ID, "validationScope", "package")));

            if (Boolean.getBoolean("att.server.benchmark.mixedInspection"))
                report.put("mixedExplorerValidation", measureMixedExplorerValidation(exploded, runtimeConfig,
                        packageRoots, packageRoot, caseCount));

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
            if (oldHome == null) System.clearProperty("att.home");
            else System.setProperty("att.home", oldHome);
        }
    }

    private Map<String, Object> measureRunBatch(int port, int workerCount, String observerMode,
                                                  int caseCount, int sampleIndex, int slowDelayMs) throws Exception {
        ProcessTreeResourceSampler resources=Boolean.getBoolean("att.benchmark.processResources")
                ?ProcessTreeResourceSampler.start():null;
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
                Map<String,Object> result=getJson(port,"/api/v1/jobs/"+jobId+"/result");
                assertEquals("PASS", view.get("status"), "Server Worker job must pass: " + jobId + " " + view + " result=" + result);
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
            if(resources!=null) {
                resources.close();
                sample.put("processResources",resources.snapshot());
            }
            return sample;
        } finally {
            streamStart.countDown();
            readers.shutdownNow();
            readers.awaitTermination(5, TimeUnit.SECONDS);
            if(resources!=null)resources.close();
        }
    }

    private Map<String, Object> measureSingleCommand(int port, String command, Map<String, Object> request) throws Exception {
        ProcessTreeResourceSampler resources=Boolean.getBoolean("att.benchmark.processResources")
                ?ProcessTreeResourceSampler.start():null;
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
        if(resources!=null) {
            resources.close();
            result.put("processResources",resources.snapshot());
        }
        if(resources!=null)resources.close();
        return result;
    }

    /**
     * Measures the public Explorer resource-list and RUN-draft routes while the
     * bounded Inspector queue is occupied by repeated validation requests.
     */
    private Map<String, Object> measureMixedExplorerValidation(Path explodedWar, Path runtimeConfig,
                                                                 Path packageRoots, Path packageRoot,
                                                                 int caseCount) throws Exception {
        int warmups = integerProperty("att.server.benchmark.mixedInspection.warmups", 2, 0);
        int runs = integerProperty("att.server.benchmark.mixedInspection.runs", 12, 4);
        int validationBurst = integerProperty("att.server.benchmark.mixedInspection.validationBurst", 4, 2);
        int validationUsers = integerProperty("att.server.benchmark.mixedInspection.validationUsers", 128, 64);
        Path mixedConfig = temp.resolve("mixed-inspection-server.yaml");
        Path mixedData = temp.resolve("mixed-inspection-data");
        Files.writeString(mixedConfig, "server:\n  runtimeConfigPath: " + ServerRuntimeTestSupport.yaml(runtimeConfig)
                + "\n  dataDir: " + ServerRuntimeTestSupport.yaml(mixedData)
                + "\n  authenticationRequired: true\n  maxEventsPerJob: 100000\n"
                + "  inlineLoad: {enabled: true}\n"
                + "  inspection: {enabled: true, maxConcurrent: 1, queuedLimit: 64, timeoutMs: 120000}\n"
                + "workers:\n  maxConcurrent: 1\n  queuedLimit: 8\n  maxConcurrentLoad: 1\n"
                + "packages:\n  allowedRoots: [" + ServerRuntimeTestSupport.yaml(packageRoots) + "]\n  entries:\n    " + PACKAGE_ID
                + ": " + ServerRuntimeTestSupport.yaml(packageRoot) + "\n", StandardCharsets.UTF_8);

        String oldConfig = System.getProperty("att.server.config");
        String oldHome = System.getProperty("att.home");
        System.setProperty("att.server.config", mixedConfig.toString());
        System.setProperty("att.home", runtimeConfig.getParent().getParent().toString());
        ServerHandle mixed = null;
        ExecutorService validators = Executors.newFixedThreadPool(validationBurst);
        try {
            mixed = startServer(explodedWar, temp.resolve("tomcat-mixed-inspection"), validationUsers);
            final int mixedPort = mixed.port;
            Object runtime = runtime(mixedPort);
            java.lang.reflect.Field inspectorField = runtime.getClass().getDeclaredField("inspectors");
            inspectorField.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor inspectors =
                    (java.util.concurrent.ThreadPoolExecutor) inspectorField.get(runtime);
            Map<String, Object> definition = Map.of("schemaVersion", "att-execution/v1",
                    "executionType", "run", "packageId", PACKAGE_ID, "environment", "BENCHMARK",
                    "selection", Map.of("all", true), "options", Map.of("debug", false));
            String explorerPath = "/api/v1/packages/" + PACKAGE_ID + "/resources?type=case&limit=50";
            List<Double> baselineExplorer = new ArrayList<>();
            List<Double> baselineValidation = new ArrayList<>();
            List<Double> mixedExplorer = new ArrayList<>();
            List<Double> mixedValidation = new ArrayList<>();
            List<Integer> queueDepthAtExplorer = new ArrayList<>();
            int userIndex = 0;

            // Warm the same package and Inspector, then take serial request baselines.
            for (int index = 0; index < warmups; index++) {
                assertTrue(itemCount(getJson(mixedPort, explorerPath, "benchmark-user")) > 0,
                        "Explorer must return the synthetic Case inventory");
                Map<String, Object> draft = postJsonExpected(mixedPort, "/api/v1/drafts/run", definition, 201,
                        validationUser(userIndex++, validationUsers));
                assertTrue(String.valueOf(draft.get("draftId")).startsWith("R"), "RUN validation must return a draft");
            }
            for (int index = 0; index < runs; index++) {
                long requestStarted = System.nanoTime();
                assertTrue(itemCount(getJson(mixedPort, explorerPath, "benchmark-user")) > 0);
                baselineExplorer.add(nanosToMs(System.nanoTime() - requestStarted));
                requestStarted = System.nanoTime();
                Map<String, Object> draft = postJsonExpected(mixedPort, "/api/v1/drafts/run", definition, 201,
                        validationUser(userIndex++, validationUsers));
                assertTrue(String.valueOf(draft.get("draftId")).startsWith("R"));
                baselineValidation.add(nanosToMs(System.nanoTime() - requestStarted));
            }
            long startsBeforeMixed = inspectionCounter(runtime, "STARTUP");
            long dispatchesBeforeMixed = inspectionCounter(runtime, "DISPATCH");

            for (int sample = 0; sample < warmups + runs; sample++) {
                CountDownLatch ready = new CountDownLatch(validationBurst);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Double>> requests = new ArrayList<>();
                for (int index = 0; index < validationBurst; index++) {
                    final String user = validationUser(userIndex++, validationUsers);
                    requests.add(validators.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Validation start gate timed out");
                        long requestStarted = System.nanoTime();
                        Map<String, Object> draft = postJsonExpected(mixedPort, "/api/v1/drafts/run", definition, 201, user);
                        assertTrue(String.valueOf(draft.get("draftId")).startsWith("R"), "RUN validation must return a draft");
                        return nanosToMs(System.nanoTime() - requestStarted);
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS), "Validation clients must reach the start gate");
                start.countDown();
                long backlogDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (inspectors.getActiveCount() == 0 && System.nanoTime() < backlogDeadline) Thread.sleep(2);
                while (inspectors.getQueue().isEmpty() && System.nanoTime() < backlogDeadline
                        && requests.stream().anyMatch(request -> !request.isDone())) Thread.sleep(2);
                int queuedAtExplorer = inspectors.getQueue().size();
                long explorerStarted = System.nanoTime();
                Map<String, Object> page = getJson(mixedPort, explorerPath, "benchmark-user");
                double explorerLatency = nanosToMs(System.nanoTime() - explorerStarted);
                assertTrue(itemCount(page) > 0, "Explorer must return the synthetic Case inventory under validation load");
                List<Double> validationLatency = new ArrayList<>();
                for (Future<Double> request : requests) validationLatency.add(request.get(120, TimeUnit.SECONDS));
                if (sample >= warmups) {
                    mixedExplorer.add(explorerLatency);
                    mixedValidation.addAll(validationLatency);
                    queueDepthAtExplorer.add(queuedAtExplorer);
                }
            }
            long startupsAfter = inspectionCounter(runtime, "STARTUP");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("caseCount", caseCount);
            report.put("inspectorConcurrency", 1);
            report.put("validationBurstSize", validationBurst);
            report.put("warmups", warmups);
            report.put("measuredMixedSamples", runs);
            report.put("measurementScope", "public Explorer resource-list and RUN-draft HTTP routes on one bounded Inspector Worker");
            report.put("baselineExplorer", latencySummary(baselineExplorer));
            report.put("baselineRunDraftValidation", latencySummary(baselineValidation));
            report.put("mixedExplorer", latencySummary(mixedExplorer));
            report.put("mixedRunDraftValidation", latencySummary(mixedValidation));
            report.put("queueDepthAtExplorerArrival", queueDepthAtExplorer);
            report.put("validationSamplesWithQueuedPredecessors", queueDepthAtExplorer.stream().filter(depth -> depth > 0).count());
            report.put("inspectorWorkerStartsDuringMixedSamples", startupsAfter - startsBeforeMixed);
            report.put("inspectorWorkerStartsTotal", startupsAfter);
            report.put("inspectorDispatchesDuringMixedSamples", inspectionCounter(runtime, "DISPATCH") - dispatchesBeforeMixed);
            report.put("httpRequestsAreReadOnlyOrDraftValidation", true);
            report.put("additionalValidationRoutes", measureAdditionalValidationRoutes(mixedPort, runtime,
                    warmups, runs, validationUsers));
            return report;
        } finally {
            validators.shutdownNow();
            validators.awaitTermination(5, TimeUnit.SECONDS);
            if (mixed != null) stopServer(mixed);
            if (oldConfig == null) System.clearProperty("att.server.config");
            else System.setProperty("att.server.config", oldConfig);
            if (oldHome == null) System.clearProperty("att.home");
            else System.setProperty("att.home", oldHome);
        }
    }

    /** Measures the Quick Load and Advanced Load form/catalog/draft paths through the public WAR routes. */
    private Map<String, Object> measureAdditionalValidationRoutes(int port, Object runtime,
                                                                   int warmups, int runs, int validationUsers)
            throws Exception {
        List<String> routes = List.of("quickLoadForm", "quickLoadPolicy", "quickLoadDraft",
                "advancedLoadTemplates", "advancedLoadDraft", "loadCatalog", "loadCatalogValidate");
        Map<String, List<Double>> samples = new LinkedHashMap<>();
        for (String route : routes) samples.put(route, new ArrayList<>());
        Map<String, Object> targetPage = getJson(port,
                "/api/v1/packages/" + PACKAGE_ID + "/resources?type=template&limit=50", "benchmark-user");
        List<?> targetItems = targetPage.get("items") instanceof List<?> items ? items : List.of();
        assertFalse(targetItems.isEmpty(), "The benchmark package must expose its template to Quick Load");
        Object targetResourceId = ((Map<?, ?>) targetItems.get(0)).get("resourceId");
        assertTrue(targetResourceId instanceof String && !String.valueOf(targetResourceId).isBlank(), targetItems.toString());
        String templateResourceId = String.valueOf(targetResourceId);
        int requestIndex = 0;
        for (int index = 0; index < warmups; index++) {
            for (String route : routes) invokeValidationRoute(port, route, templateResourceId,
                    validationUser(requestIndex++, validationUsers));
        }
        long startsBefore = inspectionCounter(runtime, "STARTUP");
        long dispatchesBefore = inspectionCounter(runtime, "DISPATCH");
        for (int index = 0; index < runs; index++) {
            for (String route : routes) {
                long started = System.nanoTime();
                invokeValidationRoute(port, route, templateResourceId, validationUser(requestIndex++, validationUsers));
                samples.get(route).add(nanosToMs(System.nanoTime() - started));
            }
        }
        Map<String, Object> summaries = new LinkedHashMap<>();
        for (String route : routes) summaries.put(route, latencySummary(samples.get(route)));
        long workerStarts = inspectionCounter(runtime, "STARTUP") - startsBefore;
        long dispatches = inspectionCounter(runtime, "DISPATCH") - dispatchesBefore;
        assertEquals(0L, workerStarts, "Validation and draft routes must not start Inspector workers");
        assertEquals((long) runs * routes.size(), dispatches,
                "Each measured validation route must be accounted for by one Inspector dispatch");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warmupsPerRoute", warmups);
        result.put("measuredSamplesPerRoute", runs);
        result.put("measurementScope", "public Quick Load and Advanced Load form, policy, template, catalog and draft routes");
        result.put("latency", summaries);
        result.put("inspectorWorkerStartsDuringMeasurements", workerStarts);
        result.put("inspectorDispatchesDuringMeasurements", dispatches);
        result.put("businessJobsSubmitted", false);
        return result;
    }

    private void invokeValidationRoute(int port, String route, String templateResourceId, String user) throws Exception {
        String root = "/api/v1/packages/" + PACKAGE_ID;
        String encodedScenario = java.net.URLEncoder.encode("load/server-events.yaml", StandardCharsets.UTF_8);
        switch (route) {
            case "quickLoadForm" -> getJson(port, root + "/resources/template/BENCH_EVENT/quick-load-form"
                    .replace("BENCH_EVENT", java.net.URLEncoder.encode(templateResourceId, StandardCharsets.UTF_8))
                    + "?model=virtualUsers&environment=BENCHMARK", user);
            case "quickLoadPolicy" -> getJson(port, root + "/load-policy?model=virtualUsers&environment=BENCHMARK", user);
            case "quickLoadDraft" -> {
                Map<String, Object> body = Map.of("packageId", PACKAGE_ID, "environment", "BENCHMARK",
                        "target", Map.of("type", "template", "id", "BENCH_EVENT"), "model", "virtualUsers",
                        "input", Map.of("inputs", Map.of(), "vars", Map.of()),
                        "load", Map.of("users", 1, "duration", "1s"), "testdata", List.of());
                Map<String, Object> draft = postJsonExpected(port, "/api/v1/drafts/quick-load", body, 201, user);
                assertTrue(draft.get("draftId") instanceof String && !String.valueOf(draft.get("draftId")).isBlank(), draft.toString());
            }
            case "advancedLoadTemplates" -> getJson(port, root + "/load-templates?offset=0&limit=100", user);
            case "advancedLoadDraft" -> {
                Map<String, Object> scenario = Map.of("schemaVersion", "att-load/v1.6", "workloads",
                        List.of(Map.of("id", "benchmark-inline", "target", Map.of("type", "template", "id", "BENCH_EVENT"),
                                "load", Map.of("users", 1, "duration", "1s"))));
                Map<String, Object> draft = postJsonExpected(port, "/api/v1/drafts/load",
                        Map.of("packageId", PACKAGE_ID, "environment", "BENCHMARK", "scenario", scenario), 201, user);
                assertTrue(draft.get("draftId") instanceof String && !String.valueOf(draft.get("draftId")).isBlank(), draft.toString());
            }
            case "loadCatalog" -> getJson(port, root + "/load-catalog?limit=100", user);
            case "loadCatalogValidate" -> {
                Map<String, Object> response = getJson(port, root + "/load-catalog/validate?resourceId="
                        + encodedScenario + "&environment=BENCHMARK", user);
                assertEquals("valid", response.get("validation"), response.toString());
            }
            default -> throw new IllegalArgumentException("Unknown validation route: " + route);
        }
    }

    private static long inspectionCounter(Object runtime, String counter) throws Exception {
        var method = runtime.getClass().getDeclaredMethod("inspectionStatistics");
        method.setAccessible(true);
        Map<?, ?> statistics = (Map<?, ?>) method.invoke(runtime);
        Map<?, ?> server = (Map<?, ?>) statistics.get("server");
        Map<?, ?> metric = (Map<?, ?>) server.get(counter);
        return ((Number) metric.get("count")).longValue();
    }

    private static int itemCount(Map<?, ?> page) {
        Object items = page.get("items");
        return items instanceof List<?> ? ((List<?>) items).size() : 0;
    }

    private static String validationUser(int index, int users) {
        return "validation-" + String.format(java.util.Locale.ROOT, "%03d", index % users);
    }

    private static Map<String, Object> latencySummary(List<Double> samples) {
        List<Double> sorted = samples.stream().sorted().toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", sorted.size());
        summary.put("p50Ms", sorted.isEmpty() ? null : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.50) - 1)));
        summary.put("p95Ms", sorted.isEmpty() ? null : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1)));
        summary.put("samplesMs", sorted);
        return summary;
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
        connection.setRequestProperty("Authorization", basicAuth("benchmark-user"));
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
        return postJsonExpected(port, path, body, 202, null);
    }

    private Map<String, Object> postJsonExpected(int port, String path, Map<String, Object> body,
                                                int expectedStatus, String username) throws Exception {
        HttpURLConnection connection = open(port, path, username);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try {
            connection.getOutputStream().write(ServerRuntime.JSON.writeValueAsBytes(body));
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expectedStatus, status, "Server should accept " + path + ": " + text);
            return ServerRuntime.JSON.readValue(text, Map.class);
        } finally {
            connection.disconnect();
        }
    }

    private Map<String, Object> getJson(int port, String path) throws Exception {
        return getJson(port, path, null);
    }

    private Map<String, Object> getJson(int port, String path, String username) throws Exception {
        HttpURLConnection connection = open(port, path, username);
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
        return open(port, path, null);
    }

    private HttpURLConnection open(int port, String path, String username) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + CONTEXT + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization", basicAuth(username == null
                ? path.endsWith("/metrics") ? "benchmark-admin" : "benchmark-user" : username));
        return connection;
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":benchmark-password")
                .getBytes(StandardCharsets.UTF_8));
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
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates/BENCH_EVENT"));
        Files.createDirectories(root.resolve("load"));
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

    private void writeServerConfig(Path path, Path runtimeConfig, Path dataDir, Path packages, Path packageRoot, int maxConcurrent) throws Exception {
        Files.writeString(path, "server:\n  runtimeConfigPath: " + runtimeConfig.toAbsolutePath() + "\n  dataDir: " + dataDir.toAbsolutePath() + "\n"
                + "  authenticationRequired: true\n  maxEventsPerJob: 100000\n"
                + "workers:\n  maxConcurrent: " + maxConcurrent + "\n  queuedLimit: 32\n  maxConcurrentLoad: 1\n"
                + "packages:\n  allowedRoots:\n    - " + packages.toAbsolutePath() + "\n  entries:\n    benchmark: "
                + packageRoot.toAbsolutePath() + "\n", StandardCharsets.UTF_8);
    }

    private ServerHandle startServer(Path explodedWar, Path baseDir) throws Exception {
        return startServer(explodedWar, baseDir, 0);
    }

    private ServerHandle startServer(Path explodedWar, Path baseDir, int validationUsers) throws Exception {
        Files.createDirectories(baseDir);
        StringBuilder userXml = new StringBuilder("<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"ATT_ADMIN\"/>"
                + "<user username=\"benchmark-user\" password=\"benchmark-password\" roles=\"ATT_USER\"/>"
                + "<user username=\"benchmark-admin\" password=\"benchmark-password\" roles=\"ATT_ADMIN\"/>");
        for (int index = 0; index < validationUsers; index++)
            userXml.append("<user username=\"").append(validationUser(index, validationUsers))
                    .append("\" password=\"benchmark-password\" roles=\"ATT_USER\"/>");
        Path users = Files.writeString(baseDir.resolve("tomcat-users.xml"), userXml.append("</tomcat-users>").toString(),
                StandardCharsets.UTF_8);
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toAbsolutePath().toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        MemoryRealm realm = new MemoryRealm();
        realm.setPathname(users.toString());
        tomcat.getEngine().setRealm(realm);
        Context context = tomcat.addWebapp(CONTEXT, explodedWar.toString());
        StandardJarScanFilter filter = new StandardJarScanFilter();
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

    private Path installWorkerRuntime(Path home,Path localArchive)throws Exception {
        assertTrue(Files.isRegularFile(localArchive),"Matching Local runtime archive must exist: "+localArchive);
        Path root=home.toAbsolutePath().normalize();Files.createDirectories(root.resolve("lib"));
        try(TarArchiveInputStream tar=new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(localArchive)))) {
            TarArchiveEntry entry;
            while((entry=tar.getNextTarEntry())!=null) {
                String name=entry.getName().replace('\\','/');int slash=name.indexOf('/');
                if(slash<0)continue;
                String relative=name.substring(slash+1);
                if(!relative.startsWith("lib/"))continue;
                Path destination=root.resolve(relative).normalize();
                assertTrue(destination.startsWith(root),"Local archive entry must stay inside ATT_HOME");
                if(entry.isDirectory())Files.createDirectories(destination);
                else {
                    assertFalse(entry.isSymbolicLink()||entry.isLink(),"Worker runtime libraries must not use archive links");
                    Files.createDirectories(destination.getParent());
                    Files.copy(tar,destination,StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path runtime=root.resolve("lib/att-local-"+ServerBuildInfo.PRODUCT+".jar");
        assertTrue(Files.isRegularFile(runtime),"Local archive must contain its combined Worker runtime: "+runtime);
        return runtime;
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

    private static List<String> stringListProperty(String name, List<String> fallback) {
        String configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) return fallback;
        List<String> values = Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (values.isEmpty() || values.stream().anyMatch(value -> !List.of("none", "fast", "slow").contains(value))
                || values.stream().distinct().count() != values.size())
            throw new IllegalArgumentException(name + " must be a unique comma-separated list of none, fast, or slow");
        return values;
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
