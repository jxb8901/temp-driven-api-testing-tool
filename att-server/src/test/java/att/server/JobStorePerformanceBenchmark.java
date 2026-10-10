package att.server;

import att.worker.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in local benchmark for H2 connection open/close and concurrent status metadata operations. */
class JobStorePerformanceBenchmark {
    @TempDir Path temp;

    @Test void benchmarkHarnessWritesDeterministicSmokeReport() throws Exception {
        Path output = temp.resolve("job-store-smoke.json");
        Map<String, Object> report = benchmark(temp.resolve("smoke"), output, new int[]{1, 4}, 1, 0, 1, 1);
        assertTrue(Files.isRegularFile(output));
        assertTrue(String.valueOf(report.get("scenarios")).contains("statusGet"));
    }

    @Test void writesOptInJobStoreBenchmarkJson() throws Exception {
        assumeTrue(Boolean.getBoolean("att.server.benchmark.jobStore"), "opt-in local benchmark");
        int iterations = integerProperty("att.server.benchmark.jobStore.iterations", 2, 1);
        int warmups = integerProperty("att.server.benchmark.jobStore.warmups", 0, 0);
        int runs = integerProperty("att.server.benchmark.jobStore.runs", 2, 1);
        int connectionOps = integerProperty("att.server.benchmark.jobStore.connectionOps", 20, 1);
        Path output = Paths.get(System.getProperty("att.server.benchmark.jobStore.output",
                "target/job-store-benchmark.json")).toAbsolutePath().normalize();
        benchmark(temp.resolve("benchmark"), output, new int[]{1, 4, 8}, iterations, warmups, runs, connectionOps);
        System.out.println("JobStore benchmark written to " + output);
    }

    private Map<String, Object> benchmark(Path work, Path output, int[] threadCounts,
                                           int iterations, int warmups, int runs, int connectionOps) throws Exception {
        Files.createDirectories(work);
        List<Map<String, Object>> scenarios = new ArrayList<Map<String, Object>>();
        for (int threads : threadCounts) {
            List<Map<String, Object>> samples = new ArrayList<Map<String, Object>>();
            for (int sample = 0; sample < warmups + runs; sample++) {
                Map<String, Object> measurement = measure(work.resolve("threads-" + threads + "-sample-" + sample),
                        threads, iterations, connectionOps);
                if (sample >= warmups) samples.add(measurement);
            }
            scenarios.add(summarize(threads, iterations, samples, runs));
        }
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", "att-server-job-store-benchmark/v1");
        report.put("environment", environment(work));
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("threadCounts", Arrays.stream(threadCounts).boxed().toList());
        settings.put("iterationsPerThread", iterations); settings.put("warmups", warmups); settings.put("runs", runs);
        settings.put("connectionOpenCloseOperations", connectionOps);
        settings.put("operationMix", "per iteration: get one job, update that job, count RUNNING jobs");
        report.put("settings", settings);
        report.put("connectionOpenClose", measureConnectionOpenClose(work.resolve("connect-only"), connectionOps));
        report.put("scenarios", scenarios);
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.writeString(output, ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return report;
    }

    private Map<String, Object> measure(Path root, int threads, int iterations, int connectionOps) throws Exception {
        Files.createDirectories(root);
        Path dataDir = root.resolve("data");
        ServerConfig config = config(root, dataDir);
        JobStore store = new JobStore(config);
        Job[] jobs = new Job[threads];
        for (int index = 0; index < threads; index++) {
            String id = String.format(java.util.Locale.ROOT, "J%016X", index + 1);
            Path eventFile = root.resolve("events").resolve(id + ".jsonl");
            Job job = new Job(id, "run", "p", "benchmark", new WorkerRequest(), new JobEvents(eventFile, 100));
            job.status = "RUNNING";
            store.insert(job, "{\"command\":\"run\",\"packageId\":\"p\"}");
            jobs[index] = job;
        }
        long[][] gets = new long[threads][iterations];
        long[][] updates = new long[threads][iterations];
        long[][] counts = new long[threads][iterations];
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        long cpuBefore = processCpuTime();
        long heapBefore = usedHeap();
        long started = System.nanoTime();
        try {
            for (int index = 0; index < threads; index++) {
                final int worker = index;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    Job job = jobs[worker];
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        long before = System.nanoTime();
                        assertNotNull(store.get(job.id));
                        gets[worker][iteration] = System.nanoTime() - before;
                        before = System.nanoTime();
                        store.update(job);
                        updates[worker][iteration] = System.nanoTime() - before;
                        before = System.nanoTime();
                        if (store.count("RUNNING") < threads) throw new IllegalStateException("Running job count fell below the benchmark fixture size");
                        counts[worker][iteration] = System.nanoTime() - before;
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Benchmark workers should be ready");
            started = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) future.get(2, TimeUnit.MINUTES);
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            store.close();
        }
        long elapsed = System.nanoTime() - started;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("threads", threads); result.put("iterationsPerThread", iterations);
        result.put("totalOperations", (long) threads * iterations * 3L);
        result.put("totalWallMs", millis(elapsed));
        result.put("metadataOperationsPerSecond", (long) threads * iterations * 3L / (elapsed / 1_000_000_000.0));
        result.put("statusGetP50Ms", percentile(flatten(gets), 0.50)); result.put("statusGetP95Ms", percentile(flatten(gets), 0.95));
        result.put("updateP50Ms", percentile(flatten(updates), 0.50)); result.put("updateP95Ms", percentile(flatten(updates), 0.95));
        result.put("statusCountP50Ms", percentile(flatten(counts), 0.50)); result.put("statusCountP95Ms", percentile(flatten(counts), 0.95));
        result.put("processCpuMs", cpuDeltaMs(cpuBefore));
        result.put("heapUsedBeforeBytes", heapBefore); result.put("heapUsedAfterBytes", usedHeap());
        result.put("databaseBytes", fileBytes(dataDir.resolve("db")));
        return result;
    }

    private Map<String, Object> measureConnectionOpenClose(Path root, int operations) throws Exception {
        Files.createDirectories(root.resolve("db"));
        String url = "jdbc:h2:file:" + root.resolve("db/connection-only").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
        Class.forName("org.h2.Driver");
        long[] samples = new long[operations];
        long started = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            long before = System.nanoTime();
            try (java.sql.Connection ignored = java.sql.DriverManager.getConnection(url, "sa", "")) { }
            samples[i] = System.nanoTime() - before;
        }
        long elapsed = System.nanoTime() - started;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("operations", operations); result.put("totalWallMs", millis(elapsed));
        result.put("operationsPerSecond", operations / (elapsed / 1_000_000_000.0));
        result.put("openCloseP50Ms", percentile(samples, 0.50)); result.put("openCloseP95Ms", percentile(samples, 0.95));
        return result;
    }

    private Map<String, Object> summarize(int threads, int iterations, List<Map<String, Object>> samples, int runs) {
        double[] totals = values(samples, "totalWallMs");
        double[] throughput = values(samples, "metadataOperationsPerSecond");
        double[] getP95 = values(samples, "statusGetP95Ms");
        double[] updateP95 = values(samples, "updateP95Ms");
        double[] countP95 = values(samples, "statusCountP95Ms");
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("threads", threads); summary.put("iterationsPerThread", iterations); summary.put("runs", runs);
        summary.put("totalWallP50Ms", percentile(totals, 0.50));
        summary.put("metadataOperationsPerSecondP50", percentile(throughput, 0.50));
        summary.put("statusGetP95MsP50", percentile(getP95, 0.50));
        summary.put("updateP95MsP50", percentile(updateP95, 0.50));
        summary.put("statusCountP95MsP50", percentile(countP95, 0.50));
        summary.put("samples", samples);
        return summary;
    }

    private ServerConfig config(Path root, Path dataDir) throws Exception {
        Path allowed = Files.createDirectories(root.resolve("packages"));
        Path pkg = Files.createDirectories(allowed.resolve("p"));
        Path java = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        Path yaml = root.resolve("server.yaml");
        Files.writeString(yaml, "server:\n  dataDir: " + quote(dataDir) + "\n  javaExecutable: " + quote(java)
                + "\nworkers: {}\npackages:\n  allowedRoots:\n    - " + quote(allowed)
                + "\n  entries:\n    p: " + quote(pkg) + "\n");
        return ServerConfig.load(yaml);
    }

    private Map<String, Object> environment(Path work) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("javaVersion", System.getProperty("java.version")); result.put("javaVm", System.getProperty("java.vm.name"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("architecture", System.getProperty("os.arch")); result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("fileStore", Files.getFileStore(work).name() + " (" + Files.getFileStore(work).type() + ")");
        return result;
    }

    private static String quote(Path path) { return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'"; }
    private static long fileBytes(Path root) throws Exception {
        if (!Files.exists(root)) return 0L;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (Exception e) { return 0L; }
            }).sum();
        }
    }
    private static long[] flatten(long[][] values) {
        long[] flattened = new long[values.length * (values.length == 0 ? 0 : values[0].length)];
        int index = 0;
        for (long[] row : values) for (long value : row) flattened[index++] = value;
        Arrays.sort(flattened);
        return flattened;
    }
    private static double[] values(List<Map<String, Object>> samples, String key) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) values[i] = ((Number) samples.get(i).get(key)).doubleValue();
        return values;
    }
    private static double percentile(long[] sorted, double fraction) { return millis(sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)]); }
    private static double percentile(double[] values, double fraction) {
        Arrays.sort(values);
        return values[Math.max(0, (int) Math.ceil(values.length * fraction) - 1)];
    }
    private static double millis(long nanos) { return nanos / 1_000_000.0; }
    private static int integerProperty(String name, int fallback, int minimum) {
        String value = System.getProperty(name);
        if (value == null) return fallback;
        int parsed = Integer.parseInt(value);
        if (parsed < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum);
        return parsed;
    }
    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean
                ? ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime() : -1L;
    }
    private static double cpuDeltaMs(long before) {
        long after = processCpuTime();
        return before < 0L || after < before ? -1.0 : millis(after - before);
    }
    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
