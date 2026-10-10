package att.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in local benchmark. Run explicitly with
 * {@code mvn -pl att-server -am -Dtest=JobEventsPerformanceBenchmark test}.
 */
class JobEventsPerformanceBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_CAPACITY = 2048;
    private static final String PAYLOAD = "x".repeat(128);

    @TempDir Path temp;

    @Test void writesRepeatableEventJournalBenchmarkJson() throws Exception {
        int[] eventsPerJournal = integerListProperty("att.server.benchmark.events", new int[]{1000, 10000});
        int[] concurrentJobs = integerListProperty("att.server.benchmark.jobs", new int[]{1, 4, 8});
        int warmups = integerProperty("att.server.benchmark.warmups", 1, 0);
        int runs = integerProperty("att.server.benchmark.runs", 3, 1);
        int capacity = integerProperty("att.server.benchmark.capacity", DEFAULT_CAPACITY, 1);

        Map<String, Object> results = new LinkedHashMap<>();
        int scenarioNumber = 0;
        for (int eventCount : eventsPerJournal) {
            for (int jobCount : concurrentJobs) {
                String scenario = "events-" + eventCount + "-jobs-" + jobCount;
                for (int sample = 0; sample < warmups + runs; sample++) {
                    Map<String, Object> measurement = measure(eventCount, jobCount, capacity,
                            temp.resolve("scenario-" + scenarioNumber++));
                    if (sample >= warmups) {
                        @SuppressWarnings("unchecked") List<Map<String, Object>> samples =
                                (List<Map<String, Object>>) results.computeIfAbsent(scenario, ignored -> new ArrayList<>());
                        samples.add(measurement);
                    }
                }
            }
        }

        List<Map<String, Object>> scenarios = summarize(results, runs);
        Path output = Path.of(System.getProperty("att.server.benchmark.output",
                "target/server-events-benchmark.json")).toAbsolutePath().normalize();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "att-server-events-benchmark/v1");
        report.put("environment", environment());
        report.put("settings", Map.of("warmups", warmups, "runs", runs, "capacity", capacity,
                "payloadBytes", PAYLOAD.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "eventsPerJournal", Arrays.stream(eventsPerJournal).boxed().toList(),
                "concurrentJournals", Arrays.stream(concurrentJobs).boxed().toList()));
        report.put("scenarios", scenarios);

        String baselineName = System.getProperty("att.server.benchmark.baseline");
        if (baselineName != null && !baselineName.isBlank()) {
            Path baseline = Path.of(baselineName).toAbsolutePath().normalize();
            report.put("baselineComparison", compareWithBaseline(scenarios, baseline));
        }
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Server event benchmark written to " + output);
    }

    private Map<String, Object> measure(int eventsPerJournal, int jobCount, int capacity, Path root) throws Exception {
        Files.createDirectories(root);
        CountingExecutor compactor = new CountingExecutor();
        List<JobEvents> journals = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (int job = 0; job < jobCount; job++) {
            Path file = root.resolve("job-" + job + ".jsonl");
            files.add(file);
            journals.add(new JobEvents(file, capacity, compactor));
        }

        long[] appendNanos = new long[eventsPerJournal * jobCount];
        AtomicInteger sampleIndex = new AtomicInteger();
        AtomicLong firstEventNanos = new AtomicLong(-1L);
        AtomicLong writerAllocatedBytes = new AtomicLong();
        com.sun.management.ThreadMXBean allocationBean = allocationBean();
        CountDownLatch ready = new CountDownLatch(jobCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        ThreadPoolExecutor writerPool = new ThreadPoolExecutor(jobCount, jobCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(jobCount));
        long startNanos = System.nanoTime();
        long cpuStart = processCpuTime();
        long heapBefore = usedHeap();
        try {
            for (int job = 0; job < jobCount; job++) {
                final JobEvents journal = journals.get(job);
                writers.add(writerPool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        long writerThreadId = Thread.currentThread().getId();
                        long allocatedBefore = allocatedBytes(allocationBean, writerThreadId);
                        for (int event = 1; event <= eventsPerJournal; event++) {
                            long before = System.nanoTime();
                            journal.append("progress", Map.of("sequence", event, "payload", PAYLOAD));
                            long elapsed = System.nanoTime() - before;
                            if (event == 1) firstEventNanos.compareAndSet(-1L, System.nanoTime() - startNanos);
                            appendNanos[sampleIndex.getAndIncrement()] = elapsed;
                        }
                        long allocatedAfter = allocatedBytes(allocationBean, writerThreadId);
                        if (allocatedBefore >= 0 && allocatedAfter >= allocatedBefore) {
                            writerAllocatedBytes.addAndGet(allocatedAfter - allocatedBefore);
                        }
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Benchmark writer threads should be ready");
            start.countDown();
            for (Future<?> writer : writers) writer.get(2, TimeUnit.MINUTES);
        } finally {
            start.countDown();
            writerPool.shutdownNow();
            writerPool.awaitTermination(5, TimeUnit.SECONDS);
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        compactor.awaitIdle(Duration.ofSeconds(30));
        compactor.close();
        double processCpuMs = cpuDeltaMs(cpuStart);
        long heapAfter = usedHeap();

        int retainedPerJournal = Math.min(eventsPerJournal, capacity);
        long diskBytes = 0;
        long replayStart = System.nanoTime();
        long replayCursor = eventsPerJournal - Math.min(100, retainedPerJournal);
        for (int job = 0; job < jobCount; job++) {
            assertEquals(eventsPerJournal, journals.get(job).latest());
            assertEquals(retainedPerJournal, Files.readAllLines(files.get(job)).size(),
                    "On-disk journal should retain exactly maxEventsPerJob events");
            JobEvents recovered = new JobEvents(files.get(job), capacity, Runnable::run);
            assertEquals(eventsPerJournal, recovered.latest());
            assertEquals(Math.min(100, retainedPerJournal), recovered.after(replayCursor, 100).size());
            diskBytes += Files.size(files.get(job));
        }
        for (int replay = 0; replay < 100; replay++) {
            for (JobEvents journal : journals) journal.after(replayCursor, 100);
        }
        long replayNanos = System.nanoTime() - replayStart;
        Arrays.sort(appendNanos);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventsPerJournal", eventsPerJournal);
        result.put("concurrentJobs", jobCount);
        result.put("totalEvents", appendNanos.length);
        result.put("totalWallMs", nanosToMs(elapsedNanos));
        result.put("eventsPerSecond", appendNanos.length / (elapsedNanos / 1_000_000_000.0));
        result.put("appendP50Ms", nanosToMs(percentile(appendNanos, 0.50)));
        result.put("appendP95Ms", nanosToMs(percentile(appendNanos, 0.95)));
        result.put("firstEventMs", nanosToMs(Math.max(0L, firstEventNanos.get())));
        result.put("replay100PagesMs", nanosToMs(replayNanos));
        result.put("diskBytes", diskBytes);
        result.put("processCpuMs", processCpuMs);
        result.put("writerAllocatedBytes", writerAllocatedBytes.get());
        result.put("writerAllocatedBytesPerEvent", appendNanos.length == 0 ? 0.0 : writerAllocatedBytes.get() / (double) appendNanos.length);
        result.put("writerAllocationMeasured", allocationBean != null);
        result.put("heapUsedBeforeBytes", heapBefore);
        result.put("heapUsedAfterBytes", heapAfter);
        return result;
    }

    private List<Map<String, Object>> summarize(Map<String, Object> raw, int runs) {
        List<Map<String, Object>> output = new ArrayList<>();
        raw.forEach((scenario, value) -> {
            @SuppressWarnings("unchecked") List<Map<String, Object>> samples = (List<Map<String, Object>>) value;
            double[] p50 = samples.stream().mapToDouble(sample -> ((Number) sample.get("appendP50Ms")).doubleValue()).toArray();
            double[] p95 = samples.stream().mapToDouble(sample -> ((Number) sample.get("appendP95Ms")).doubleValue()).toArray();
            double[] throughput = samples.stream().mapToDouble(sample -> ((Number) sample.get("eventsPerSecond")).doubleValue()).toArray();
            double[] firstEvent = samples.stream().mapToDouble(sample -> ((Number) sample.get("firstEventMs")).doubleValue()).toArray();
            double[] replay = samples.stream().mapToDouble(sample -> ((Number) sample.get("replay100PagesMs")).doubleValue()).toArray();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("scenario", scenario);
            summary.put("runs", runs);
            summary.put("appendP50Ms", percentile(p50, 0.50));
            summary.put("appendP95Ms", percentile(p95, 0.95));
            summary.put("eventsPerSecond", percentile(throughput, 0.50));
            summary.put("firstEventP50Ms", percentile(firstEvent, 0.50));
            summary.put("replay100PagesP50Ms", percentile(replay, 0.50));
            summary.put("appendP50CoefficientOfVariation", coefficientOfVariation(p50));
            summary.put("throughputCoefficientOfVariation", coefficientOfVariation(throughput));
            summary.put("samples", samples);
            output.add(summary);
        });
        return output;
    }

    private Map<String, Object> compareWithBaseline(List<Map<String, Object>> candidate, Path baseline) throws Exception {
        Map<String, Object> baselineReport = JSON.readValue(baseline.toFile(), new TypeReference<>() { });
        List<Map<String, Object>> baselineScenarios = JSON.convertValue(baselineReport.get("scenarios"), new TypeReference<>() { });
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> scenario : baselineScenarios) byName.put(String.valueOf(scenario.get("scenario")), scenario);
        Map<String, Object> comparisons = new LinkedHashMap<>();
        for (Map<String, Object> scenario : candidate) {
            Map<String, Object> previous = byName.get(String.valueOf(scenario.get("scenario")));
            if (previous == null) continue;
            Map<String, Object> ratio = new LinkedHashMap<>();
            ratio.put("appendP50Ratio", ratio(scenario, previous, "appendP50Ms"));
            ratio.put("appendP95Ratio", ratio(scenario, previous, "appendP95Ms"));
            ratio.put("throughputRatio", ratio(scenario, previous, "eventsPerSecond"));
            comparisons.put(String.valueOf(scenario.get("scenario")), ratio);
        }
        return Map.of("path", baseline.toString(), "ratios", comparisons,
                "note", "Ratios are descriptive; establish stable machine-specific baselines before setting regression gates.");
    }

    private Map<String, Object> environment() {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("javaVm", System.getProperty("java.vm.name"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        environment.put("liveThreads", ManagementFactory.getThreadMXBean().getThreadCount());
        return environment;
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean processBean ? processBean.getProcessCpuTime() : -1L;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) return null;
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) allocationBean.setThreadAllocatedMemoryEnabled(true);
        return allocationBean;
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean bean, long threadId) {
        return bean == null ? -1L : bean.getThreadAllocatedBytes(threadId);
    }

    private static double cpuDeltaMs(long before) {
        long after = processCpuTime();
        return before < 0 || after < 0 ? -1.0 : nanosToMs(after - before);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(fraction * sorted.length) - 1)];
    }

    private static double percentile(double[] values, double fraction) {
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        return sorted[Math.max(0, (int) Math.ceil(fraction * sorted.length) - 1)];
    }

    private static double coefficientOfVariation(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0.0);
        if (mean == 0.0 || values.length < 2) return 0.0;
        double variance = Arrays.stream(values).map(value -> Math.pow(value - mean, 2)).sum() / (values.length - 1);
        return Math.sqrt(variance) / mean;
    }

    private static double nanosToMs(long nanos) { return nanos / 1_000_000.0; }

    private static double ratio(Map<String, Object> candidate, Map<String, Object> baseline, String metric) {
        double before = ((Number) baseline.get(metric)).doubleValue();
        return before == 0.0 ? -1.0 : ((Number) candidate.get(metric)).doubleValue() / before;
    }

    private static int integerProperty(String key, int fallback, int minimum) {
        int value = Integer.parseInt(System.getProperty(key, String.valueOf(fallback)));
        if (value < minimum) throw new IllegalArgumentException(key + " must be >= " + minimum);
        return value;
    }

    private static int[] integerListProperty(String key, int[] fallback) {
        String configured = System.getProperty(key);
        int[] values = configured == null ? fallback : Arrays.stream(configured.split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        if (values.length == 0 || Arrays.stream(values).anyMatch(value -> value < 1)) {
            throw new IllegalArgumentException(key + " must contain positive comma-separated integers");
        }
        return values;
    }

    private static final class CountingExecutor implements Executor, AutoCloseable {
        private final AtomicInteger outstanding = new AtomicInteger();
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), runnable -> {
                    Thread thread = new Thread(runnable, "att-events-benchmark-compactor");
                    thread.setDaemon(true);
                    return thread;
                });

        @Override public void execute(Runnable command) {
            outstanding.incrementAndGet();
            try {
                executor.execute(() -> {
                    try { command.run(); }
                    finally { outstanding.decrementAndGet(); }
                });
            } catch (RuntimeException rejected) {
                outstanding.decrementAndGet();
                throw rejected;
            }
        }

        void awaitIdle(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (outstanding.get() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(0, outstanding.get(), "Compaction should finish before the benchmark sample is checked");
        }

        @Override public void close() throws InterruptedException {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Compactor executor should stop cleanly");
        }
    }
}
