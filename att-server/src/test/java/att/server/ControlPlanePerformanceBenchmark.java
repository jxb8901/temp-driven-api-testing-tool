package att.server;

import att.server.api.WorkerRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly selected benchmark, excluded from ordinary Surefire test discovery. */
class ControlPlanePerformanceBenchmark {
    @TempDir Path temp;
    private final List<Map<String,Object>> measurements = new ArrayList<>();

    @Test void writesReproducibleMetadataJournalAndClasspathMeasurements() throws Exception {
        ProcessTreeResourceSampler resources = Boolean.getBoolean("att.benchmark.processResources")
                ? ProcessTreeResourceSampler.start() : null;
        try {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean.isThreadContentionMonitoringSupported()) bean.setThreadContentionMonitoringEnabled(true);
        var allocationBean = allocationBean();
        Path pkg = Files.createDirectories(temp.resolve("packages/p"));
        Path runtime = Path.of("config/config.yaml").toAbsolutePath();
        int samples = Integer.getInteger("att.backend.samples", 64);
        assertTrue(samples >= 20 && samples <= 10000);
        int storeWarmups = Integer.getInteger("att.backend.storeWarmups", 10);
        assertTrue(storeWarmups >= 1 && storeWarmups <= 10);
        for (int clients : new int[]{1,4,8,16}) {
            Path dataDir = temp.resolve("data/clients-" + clients);
            Path file = temp.resolve("server-" + clients + ".yaml");
            Files.writeString(file, "server:\n  dataDir: " + dataDir + "\n  runtimeConfigPath: " + runtime
                    + "\nworkers: {}\npackages:\n  allowedRoots: [" + pkg.getParent() + "]\n  entries:\n    p: " + pkg + "\n");
            ServerConfig config = ServerConfig.load(file);
            try (JobStore store = new JobStore(config)) {
                ExecutorService threads = Executors.newFixedThreadPool(clients);
                try {
                    CountDownLatch start = new CountDownLatch(1), warmedUp = new CountDownLatch(clients), measureStart = new CountDownLatch(1);
                    List<Future<Map<String,Object>>> futures = new ArrayList<>();
                    String[] logins = new String[clients], sessionHashes = new String[clients];
                    LoadSchedule[] schedules = new LoadSchedule[clients];
                    for (int client = 0; client < clients; client++) {
                        final int index = client;
                        long baseId = clients * 1_000_000L + index * 20_000L;
                        String id = String.format(Locale.ROOT,"J%016x",baseId + 10_002L);
                        String login = "benchmark-" + clients + "-" + index;
                        Job job = new Job(id,"run","p",login,new WorkerRequest(),
                                new JobEvents(config.dataDir.resolve("jobs/" + id + "/events.jsonl"), 100));
                        job.status = "PASS"; job.finishedAt = Instant.now();
                        String loadId = String.format(Locale.ROOT,"J%016x",baseId + 10_001L);
                        Job loadJob = new Job(loadId,"load","p",login,new WorkerRequest(),
                                new JobEvents(config.dataDir.resolve("jobs/" + loadId + "/events.jsonl"), 100));
                        loadJob.status = "PASS"; loadJob.finishedAt = Instant.now();
                        String csrf = "csrf-" + clients + "-" + index;
                        String sessionHash = String.format(Locale.ROOT, "%064x", clients * 1000L + index + 1L);
                        long now = System.currentTimeMillis();
                        store.createAuthUser(login, "Benchmark " + index, "synthetic-benchmark-hash", "USER", csrf);
                        store.saveAuthSession(sessionHash, login, csrf, now, now + TimeUnit.DAYS.toMillis(1));
                        LoadSchedule schedule = new LoadSchedule();
                        schedule.id = "S" + clients + "-" + index; schedule.name = "benchmark";
                        schedule.packageId = "p"; schedule.jobType = "run"; schedule.enabled = false;
                        schedule.timezone = "UTC"; schedule.cron = "0 * * * * *";
                        schedule.updatedAt = Instant.now().toString();
                        store.saveSchedule(schedule);
                        store.insert(job,"{}"); store.update(job);
                        store.insert(loadJob,"{}"); store.update(loadJob);
                        logins[index] = login; sessionHashes[index] = sessionHash; schedules[index] = schedule;
                    }
                    for (int index = 0; index < 500; index++) {
                        String id = String.format(Locale.ROOT,"J%016x",100_000_000L + index);
                        Job expired = new Job(id,"run","p","retention-fixture",new WorkerRequest(),
                                new JobEvents(config.dataDir.resolve("jobs/" + id + "/events.jsonl"), 100));
                        expired.status = "PASS"; expired.finishedAt = Instant.now().minus(1,java.time.temporal.ChronoUnit.DAYS);
                        store.insert(expired,"{}"); store.update(expired);
                        store.artifactRecoveryNeeded(id,false);
                    }
                    long databaseBytesBefore = fileBytes(config.dataDir.resolve("db"));
                    for (int client = 0; client < clients; client++) {
                        final int index = client;
                        futures.add(threads.submit(() -> {
                            start.await();
                            long[] times = new long[samples];
                            Map<String,long[]> operationTimes = new LinkedHashMap<>();
                            for (String operation : List.of("jobInsert", "jobStatusUpdate", "jobGet", "jobList", "loadJobList",
                                    "authUserLookup", "authSessionVerifyTouch", "scheduleCount", "scheduleUpdate",
                                    "retentionScan", "pin", "unpin"))
                                operationTimes.put(operation, new long[samples]);
                            long blockedBefore = -1L, cpuBefore = -1L, allocatedBefore = -1L;
                            long heapBefore = 0L, sampledPeakHeap = 0L;
                            long threadId = Thread.currentThread().getId();
                            for (int i = -storeWarmups; i < samples; i++) {
                                if (i == 0) {
                                    warmedUp.countDown();
                                    if (!measureStart.await(120, TimeUnit.SECONDS)) throw new TimeoutException("Benchmark measurement start was not released");
                                    blockedBefore = bean.getThreadInfo(threadId).getBlockedTime();
                                    cpuBefore = bean.getCurrentThreadCpuTime();
                                    allocatedBefore = allocationBean == null ? -1L : allocationBean.getThreadAllocatedBytes(threadId);
                                    heapBefore = usedHeap(); sampledPeakHeap = heapBefore;
                                }
                                long before = System.nanoTime();
                                long baseId = clients * 1_000_000L + index * 20_000L;
                                String id = String.format(Locale.ROOT,"J%016x",baseId + i + 1L);
                                Job job = new Job(id,"run","p",logins[index],new WorkerRequest(),
                                        new JobEvents(config.dataDir.resolve("jobs/" + id + "/events.jsonl"), 100));
                                job.status = "QUEUED";
                                long operationBefore = System.nanoTime();
                                store.insert(job,"{}");
                                if (i >= 0) operationTimes.get("jobInsert")[i] = System.nanoTime() - operationBefore;
                                job.status = "RUNNING"; job.startedAt = Instant.now();
                                operationBefore = System.nanoTime();
                                store.update(job);
                                if (i >= 0) operationTimes.get("jobStatusUpdate")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                assertNotNull(store.get(id));
                                if (i >= 0) operationTimes.get("jobGet")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                store.list(20);
                                if (i >= 0) operationTimes.get("jobList")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                assertFalse(store.listLoad(20,0,null,false).isEmpty());
                                if (i >= 0) operationTimes.get("loadJobList")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                assertNotNull(store.authUser(logins[index]));
                                if (i >= 0) operationTimes.get("authUserLookup")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                Map<String,String> session = store.authSession(sessionHashes[index],
                                        System.currentTimeMillis(), TimeUnit.HOURS.toMillis(1));
                                assertEquals(logins[index], session.get("loginId"));
                                if (i >= 0) operationTimes.get("authSessionVerifyTouch")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                store.scheduleCount();
                                if (i >= 0) operationTimes.get("scheduleCount")[i] = System.nanoTime() - operationBefore;
                                LoadSchedule schedule = schedules[index];
                                String expectedUpdatedAt = schedule.updatedAt;
                                String updatedAt = Instant.now().toString();
                                if (updatedAt.equals(expectedUpdatedAt)) updatedAt = Instant.now().plusMillis(1).toString();
                                schedule.updatedAt = updatedAt;
                                operationBefore = System.nanoTime();
                                assertNotNull(store.updateSchedule(schedule, expectedUpdatedAt));
                                if (i >= 0) operationTimes.get("scheduleUpdate")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                assertFalse(store.expiredJobIds(Instant.now().plus(1, java.time.temporal.ChronoUnit.DAYS)).isEmpty());
                                if (i >= 0) operationTimes.get("retentionScan")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                String fixtureJobId = String.format(Locale.ROOT,"J%016x",baseId + 10_002L);
                                assertTrue(store.setPinned(fixtureJobId, true, "benchmark"));
                                if (i >= 0) operationTimes.get("pin")[i] = System.nanoTime() - operationBefore;
                                operationBefore = System.nanoTime();
                                assertTrue(store.setPinned(fixtureJobId, false, "benchmark"));
                                if (i >= 0) operationTimes.get("unpin")[i] = System.nanoTime() - operationBefore;
                                if (i >= 0) sampledPeakHeap = Math.max(sampledPeakHeap, usedHeap());
                                if (i >= 0) times[i] = System.nanoTime() - before;
                            }
                            Map<String,Object> row = summary("jobstore", times);
                            row.put("clients", clients);
                            row.put("operationMix", "job insert/status/get/list, LOAD list, auth user/session verify+touch, schedule count/update, retention scan, pin/unpin");
                            row.put("heapUsedBeforeBytes", heapBefore); row.put("sampledPeakHeapUsedBytes", sampledPeakHeap);
                            row.put("heapUsedAfterBytes", usedHeap());
                            row.put("threadCpuMs", (bean.getCurrentThreadCpuTime() - cpuBefore) / 1e6);
                            row.put("threadAllocatedBytes", allocationBean == null ? null : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore);
                            row.put("monitorBlockedMs", bean.getThreadInfo(threadId).getBlockedTime() - blockedBefore);
                            for (Map.Entry<String,long[]> operation : operationTimes.entrySet())
                                addDistribution(row, operation.getKey(), operation.getValue());
                            return row;
                        }));
                    }
                    start.countDown();
                    long warmupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                    while (warmedUp.getCount() > 0 && System.nanoTime() < warmupDeadline) {
                        for (Future<Map<String,Object>> future : futures) if (future.isDone()) future.get();
                        warmedUp.await(100, TimeUnit.MILLISECONDS);
                    }
                    assertEquals(0, warmedUp.getCount(), "Every client should finish its warmup before measurement");
                    long gcCollectionsBefore = gcCollections(), gcTimeBeforeMs = gcTimeMs();
                    long groupStarted = System.nanoTime();
                    measureStart.countDown();
                    List<Map<String,Object>> rows = new ArrayList<>();
                    for (var future : futures) rows.add(future.get(120, TimeUnit.SECONDS));
                    long groupElapsed = System.nanoTime() - groupStarted;
                    long databaseBytesAfter = fileBytes(config.dataDir.resolve("db"));
                    long gcCollections = Math.max(0L,gcCollections()-gcCollectionsBefore);
                    long gcTimeMs = Math.max(0L,gcTimeMs()-gcTimeBeforeMs);
                    long measuredCycles = (long) clients * samples;
                    for (Map<String,Object> row : rows) {
                        row.put("totalMeasuredCycles", measuredCycles);
                        row.put("groupWallMs", groupElapsed / 1e6);
                        row.put("cyclesPerSecond", measuredCycles / (groupElapsed / 1e9));
                        row.put("mixedOperationsPerSecond", measuredCycles * 12.0 / (groupElapsed / 1e9));
                        row.put("gcCollections",gcCollections); row.put("gcTimeMs",gcTimeMs);
                        row.put("h2FilesBytesBeforeGroup", databaseBytesBefore);
                        row.put("h2FilesBytesAfterGroup", databaseBytesAfter);
                        row.put("h2FilesBytesDelta", Math.max(0L,databaseBytesAfter-databaseBytesBefore));
                        measurements.add(row);
                    }
                } finally { threads.shutdownNow(); assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS)); }
            }
        }
        for (int caseCount : new int[]{20,200,1000}) {
            var request = ServerRuntime.JSON.createObjectNode();
            request.put("schemaVersion",RunDefinition.VERSION); request.put("executionType","run");
            request.put("packageId","benchmark"); request.put("environment","SIT");
            var selection = request.putObject("selection");
            var caseIds = selection.putArray("caseIds");
            for (int index = 0; index < caseCount; index++) caseIds.add("suite.CASE-" + index);
            String body = ServerRuntime.JSON.writeValueAsString(request);
            long[] parseTimes = new long[samples];
            for (int index = -10; index < samples; index++) {
                long before = System.nanoTime();
                RunDefinition parsed = RunDefinition.parseRetained(ServerRuntime.JSON.readTree(body));
                assertEquals(caseCount,parsed.workerInput.path("caseIds").size());
                if (index >= 0) parseTimes[index] = System.nanoTime() - before;
            }
            Map<String,Object> row = summary("run-definition-parse-only",parseTimes);
            row.put("caseCount",caseCount); row.put("environment","SIT");
            row.put("requestBytes",body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            row.put("includesJsonDecodeAndRetainedSchemaValidation",true);
            row.put("includesPackageRevisionOrResourceParsing",false);
            measurements.add(row);
        }
        for (int rate : new int[]{10,100,1000}) for (String observer : List.of("none","fast","slow","throwing")) {
            Path journal = temp.resolve("journal/" + rate + "-" + observer + ".jsonl");
            AtomicInteger compactionSnapshots = new AtomicInteger();
            AtomicInteger compactionQueuePeak = new AtomicInteger();
            int journalCapacity = Math.max(512, samples + 32);
            ThreadPoolExecutor compactor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32), runnable -> {
                        Thread thread = new Thread(runnable, "benchmark-event-compactor"); thread.setDaemon(true); return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            JobEvents events = new JobEvents(journal, journalCapacity, compactor, compactionSnapshots::incrementAndGet);
            List<Long> delivered = new ArrayList<>();
            try {
                long[] times = new long[samples];
                long started = System.nanoTime();
                try (AutoCloseable listener = events.listen(event -> {
                    delivered.add(((Number)event.get("id")).longValue());
                    if (observer.equals("slow")) LockSupport.parkNanos(2_000_000);
                    if (observer.equals("throwing")) throw new IllegalStateException("synthetic observer failure");
                })) {
                    // none uses an inert listener; fast also verifies delivery below.
                    if (observer.equals("none")) listener.close();
                    long appendStarted = System.nanoTime();
                    for (int i = 0; i < samples; i++) {
                        long target = started + (long)i * 1_000_000_000L / rate;
                        while (System.nanoTime() < target) LockSupport.parkNanos(target - System.nanoTime());
                        long before = System.nanoTime();
                        events.append("log", Map.of("message", "x".repeat(128)));
                        times[i] = System.nanoTime() - before;
                        compactionQueuePeak.accumulateAndGet(compactor.getQueue().size(), Math::max);
                    }
                    long appendElapsed = System.nanoTime() - appendStarted;
                    events.append("result", Map.of("status","PASS"));
                    int expectedCompactions = samples / 128;
                    compactor.shutdown();
                    assertTrue(compactor.awaitTermination(30, TimeUnit.SECONDS), "Journal compaction should drain before restart verification");
                    JobEvents recovered = new JobEvents(journal,32);
                    assertEquals(events.latest(),recovered.latest());
                    assertTrue(recovered.resultDeliveredThrough(recovered.latest()));
                    long cursor = 0;
                    for (var event : recovered.after(0)) {
                        long id = ((Number)event.get("id")).longValue();
                        assertTrue(id > cursor); cursor = id;
                    }
                    long replayCursor = Math.max(0, recovered.latest() - 10L);
                    List<Map<String,Object>> resumed = recovered.after(replayCursor);
                    assertEquals(10, resumed.size(), "Last-Event-ID replay should return the retained ordered suffix");
                    for (Map<String,Object> event : resumed) {
                        long id = ((Number)event.get("id")).longValue();
                        assertEquals(replayCursor + 1L, id); replayCursor = id;
                    }
                    assertEquals(recovered.latest(), replayCursor);
                    if (!observer.equals("none")) assertEquals(samples + 1, delivered.size());
                    Map<String,Object> row = summary("journal",times);
                    row.put("targetEventsPerSecond",rate); row.put("observer",observer);
                    row.put("retainedEventCapacity",journalCapacity);
                    row.put("expectedCompactionBatches",expectedCompactions);
                    row.put("compactionSnapshotsObserved",compactionSnapshots.get());
                    row.put("elapsedMs",(System.nanoTime()-started)/1e6);
                    row.put("appendWallMs",appendElapsed/1e6);
                    row.put("observedAppendEventsPerSecond", samples / (appendElapsed / 1e9));
                    row.put("compactionSnapshots",compactionSnapshots.get());
                    row.put("compactionQueuePeak",compactionQueuePeak.get());
                    row.put("retainedBytes",Files.size(journal));
                    measurements.add(row);
                }
            } finally {
                compactor.shutdownNow();
                assertTrue(compactor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        Path libs = Files.createDirectories(temp.resolve("lib"));
        for (int i=0;i<50;i++) Files.createFile(libs.resolve("fixture-"+i+".jar"));
        long[] times = new long[samples];
        for (int i=-10;i<samples;i++) {
            long before=System.nanoTime(); assertEquals(50,WorkerRuntimeClasspath.entries(libs,List.of()).size());
            if(i>=0) times[i]=System.nanoTime()-before;
        }
        measurements.add(summary("classpath-50-jars",times));
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schemaVersion","att-control-plane-benchmark/v1");
        report.put("java",System.getProperty("java.runtime.version"));
        report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        report.put("processors",Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("samples",samples); report.put("storeWarmups",storeWarmups);
        report.put("commit",System.getProperty("att.benchmark.commit","unspecified"));
        if (resources != null) report.put("processResources", resources.snapshot());
        report.put("measurements",measurements);
        Path output=Path.of(System.getProperty("att.backend.output","target/control-plane-benchmark.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        } finally {
            if (resources != null) resources.close();
        }
    }

    private static Map<String,Object> summary(String name,long[] times) {
        List<Double> raw=Arrays.stream(times).mapToDouble(value->value/1e6).boxed().toList();
        double[] sorted=raw.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        Map<String,Object> row=new LinkedHashMap<>(); row.put("operation",name); row.put("rawMs",raw);
        row.put("p50Ms",sorted[(int)Math.ceil(sorted.length*.50)-1]);
        row.put("p95Ms",sorted[(int)Math.ceil(sorted.length*.95)-1]); return row;
    }
    private static void addDistribution(Map<String,Object> row, String name, long[] times) {
        double[] sorted = Arrays.stream(times).mapToDouble(value -> value / 1e6).sorted().toArray();
        row.put(name + "P50Ms", sorted[(int)Math.ceil(sorted.length * .50) - 1]);
        row.put(name + "P95Ms", sorted[(int)Math.ceil(sorted.length * .95) - 1]);
        row.put(name + "RawMs", Arrays.stream(times).mapToDouble(value -> value / 1e6).boxed().toList());
    }
    private static com.sun.management.ThreadMXBean allocationBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) return null;
        try {
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            return bean;
        } catch (RuntimeException unavailable) { return null; }
    }
    private static long gcCollections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0L,bean.getCollectionCount())).sum();
    }
    private static long gcTimeMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0L,bean.getCollectionTime())).sum();
    }
    private static long usedHeap() { Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
    private static long fileBytes(Path root) throws Exception {
        if (!Files.exists(root)) return 0L;
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (Exception ignored) { return 0L; }
            }).sum();
        }
    }
}
