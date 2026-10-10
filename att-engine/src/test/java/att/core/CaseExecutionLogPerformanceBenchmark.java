package att.core;

import att.validation.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in local benchmark for CaseExecutionLog physical and deferred evidence modes. */
class CaseExecutionLogPerformanceBenchmark {
    private static final int PAYLOAD_CHARS = 128;
    @TempDir Path temp;

    @Test void benchmarkHarnessWritesDeterministicSmokeReport() throws Exception {
        Path output = temp.resolve("case-log-smoke.json");
        Map<String, Object> report = benchmark(50, 0, 1, temp.resolve("smoke"), output);
        assertTrue(Files.isRegularFile(output));
        assertTrue(String.valueOf(report.get("scenarios")).contains("physical_full_evidence"));
    }

    @Test void writesOptInCaseLogBenchmarkJson() throws Exception {
        assumeTrue(Boolean.getBoolean("att.engine.benchmark.caseLog"), "opt-in local benchmark");
        int iterations = integerProperty("att.engine.benchmark.caseLog.iterations", 5000, 1);
        int warmups = integerProperty("att.engine.benchmark.caseLog.warmups", 1, 0);
        int runs = integerProperty("att.engine.benchmark.caseLog.runs", 3, 1);
        Path output = Paths.get(System.getProperty("att.engine.benchmark.caseLog.output",
                "target/case-log-benchmark.json")).toAbsolutePath().normalize();
        benchmark(iterations, warmups, runs, temp.resolve("benchmark"), output);
        System.out.println("CaseExecutionLog benchmark written to " + output);
    }

    private Map<String, Object> benchmark(int iterations, int warmups, int runs, Path work, Path output) throws Exception {
        String payload = payload(PAYLOAD_CHARS);
        String[] scenarios = {"physical_full_evidence", "physical_batched_full_evidence", "physical_with_mirror",
                "deferred_full_evidence", "bounded_failure_evidence"};
        Map<String, List<Map<String, Object>>> samples = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String scenario : scenarios) samples.put(scenario, new ArrayList<Map<String, Object>>());

        int sampleNumber = 0;
        for (int sample = 0; sample < warmups + runs; sample++) {
            for (String scenario : scenarios) {
                Map<String, Object> measurement = measure(scenario, iterations, payload,
                        work.resolve("sample-" + sampleNumber++));
                if (sample >= warmups) samples.get(scenario).add(measurement);
            }
        }

        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", "att-case-log-benchmark/v1");
        report.put("environment", environment(work));
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("iterations", iterations); settings.put("warmups", warmups); settings.put("runs", runs);
        settings.put("payloadChars", payload.length());
        settings.put("note", "Synthetic appendRaw records; interactive mirror is a counting callback, not terminal I/O.");
        report.put("settings", settings);
        List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
        for (String scenario : scenarios) summaries.add(summarize(scenario, samples.get(scenario), runs));
        report.put("scenarios", summaries);
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.write(output, JsonSupport.write(report).getBytes(StandardCharsets.UTF_8));
        return report;
    }

    private Map<String, Object> measure(String scenario, int iterations, String payload, Path root) throws Exception {
        Files.createDirectories(root);
        Path file = root.resolve("case.log");
        AtomicLong mirrorChars = new AtomicLong();
        CaseExecutionLog log;
        if ("physical_full_evidence".equals(scenario)) log = new CaseExecutionLog(file);
        else if ("physical_batched_full_evidence".equals(scenario)) log = CaseExecutionLog.buffered(file, false);
        else if ("physical_with_mirror".equals(scenario))
            log = new CaseExecutionLog(file, false, value -> mirrorChars.addAndGet(value.length()));
        else if ("deferred_full_evidence".equals(scenario)) log = CaseExecutionLog.lightweight(file);
        else log = CaseExecutionLog.bounded(file, false, 1024 * 1024);

        long[] appendNanos = new long[iterations];
        long cpuBefore = processCpuTime();
        long heapBefore = usedHeap();
        long appendStarted = System.nanoTime();
        long closeNanos;
        long materializeNanos = 0L;
        try {
            for (int index = 0; index < iterations; index++) {
                long started = System.nanoTime();
                log.appendRaw("ACTION", payload);
                appendNanos[index] = System.nanoTime() - started;
            }
            long closeStarted = System.nanoTime();
            if ("deferred_full_evidence".equals(scenario) || "bounded_failure_evidence".equals(scenario)) {
                long materializeStarted = System.nanoTime();
                log.materialize(file);
                materializeNanos = System.nanoTime() - materializeStarted;
            }
            log.close();
            closeNanos = System.nanoTime() - closeStarted;
        } catch (Exception failure) {
            try { log.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        long appendWallNanos = System.nanoTime() - appendStarted - materializeNanos - closeNanos;
        Arrays.sort(appendNanos);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("scenario", scenario);
        result.put("iterations", iterations);
        result.put("appendWallMs", millis(appendWallNanos));
        result.put("recordsPerSecond", appendWallNanos <= 0L ? 0.0 : iterations / (appendWallNanos / 1_000_000_000.0));
        result.put("appendP50Ms", millis(percentile(appendNanos, 0.50)));
        result.put("appendP95Ms", millis(percentile(appendNanos, 0.95)));
        result.put("materializeMs", millis(materializeNanos));
        result.put("closeMs", millis(closeNanos));
        result.put("fileBytes", Files.exists(file) ? Files.size(file) : 0L);
        result.put("mirrorChars", mirrorChars.get());
        result.put("processCpuMs", cpuDeltaMs(cpuBefore));
        result.put("heapUsedBeforeBytes", heapBefore);
        result.put("heapUsedAfterBytes", usedHeap());
        return result;
    }

    private Map<String, Object> summarize(String scenario, List<Map<String, Object>> samples, int runs) {
        double[] p50 = values(samples, "appendP50Ms");
        double[] p95 = values(samples, "appendP95Ms");
        double[] wall = values(samples, "appendWallMs");
        double[] throughput = values(samples, "recordsPerSecond");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("scenario", scenario); result.put("runs", runs);
        result.put("appendP50Ms", percentile(p50, 0.50)); result.put("appendP95Ms", percentile(p95, 0.95));
        result.put("appendWallP50Ms", percentile(wall, 0.50));
        result.put("recordsPerSecondP50", percentile(throughput, 0.50));
        result.put("samples", samples);
        return result;
    }

    private double[] values(List<Map<String, Object>> samples, String name) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) values[i] = ((Number) samples.get(i).get(name)).doubleValue();
        return values;
    }

    private Map<String, Object> environment(Path work) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVm", System.getProperty("java.vm.name"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("architecture", System.getProperty("os.arch"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("fileStore", Files.getFileStore(work).name() + " (" + Files.getFileStore(work).type() + ")");
        return result;
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
    private static long percentile(long[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }
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
    private static String payload(int length) {
        char[] value = new char[length];
        Arrays.fill(value, 'x');
        return new String(value);
    }
}
