package att.load;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Event-triggered, rate-limited JVM telemetry. It retains counters and peaks only. */
final class GeneratorTelemetry {
    static final long SAMPLE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final java.util.List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final long maxHeapBytes = Runtime.getRuntime().maxMemory();
    private final long gcCountAtStart = gcCount();
    private final long gcTimeAtStart = gcTime();
    private final long cpuAtStart = processCpuNanos();
    private final long startedAtNanos = System.nanoTime();
    private long lastSampleNanos;
    private long peakHeapUsedBytes;
    private long peakHeapCommittedBytes;
    private long peakLiveThreads;
    private long warmupEndHeapUsedBytes = -1L;
    private long endHeapUsedBytes;
    private long samples;

    synchronized void sample(boolean warmup) {
        long now = System.nanoTime();
        if (lastSampleNanos != 0L && now - lastSampleNanos < SAMPLE_INTERVAL_NANOS) return;
        lastSampleNanos = now;
        MemoryUsage heap = memory.getHeapMemoryUsage();
        long used = Math.max(0L, heap.getUsed());
        long committed = Math.max(0L, heap.getCommitted());
        peakHeapUsedBytes = Math.max(peakHeapUsedBytes, used);
        peakHeapCommittedBytes = Math.max(peakHeapCommittedBytes, committed);
        endHeapUsedBytes = used;
        peakLiveThreads = Math.max(peakLiveThreads, threads.getThreadCount());
        samples++;
        if (!warmup && warmupEndHeapUsedBytes < 0L) warmupEndHeapUsedBytes = used;
    }

    synchronized Map<String, Object> snapshot() {
        sample(false);
        long cpuAtEnd = processCpuNanos();
        long elapsed = Math.max(1L, System.nanoTime() - startedAtNanos);
        boolean cpuAvailable = cpuAtStart >= 0L && cpuAtEnd >= cpuAtStart;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sampling", "event-triggered; at most one sample per 100 ms");
        result.put("sampleCount", samples);
        result.put("heapUsedBytes", endHeapUsedBytes);
        result.put("heapPeakUsedBytes", peakHeapUsedBytes);
        result.put("heapCommittedBytes", memory.getHeapMemoryUsage().getCommitted());
        result.put("heapPeakCommittedBytes", peakHeapCommittedBytes);
        result.put("heapMaxBytes", maxHeapBytes);
        result.put("liveThreads", threads.getThreadCount());
        result.put("liveThreadsPeak", peakLiveThreads);
        long gcCount = delta(gcCountAtStart, gcCount()), gcTime = delta(gcTimeAtStart, gcTime());
        result.put("gcMetricsAvailable", gcCount >= 0L && gcTime >= 0L);
        result.put("gcCollectionCount", gcCount);
        result.put("gcCollectionTimeMs", gcTime);
        result.put("gcMetricsUnavailableReason", gcCount >= 0L && gcTime >= 0L ? null : "JVM garbage-collector counters are unavailable");
        result.put("processCpuSupported", cpuAvailable);
        result.put("processCpuTimeMs", cpuAvailable ? TimeUnit.NANOSECONDS.toMillis(cpuAtEnd - cpuAtStart) : null);
        result.put("processCpuPercent", cpuAvailable
                ? Math.min(100.0 * Runtime.getRuntime().availableProcessors(),
                    (double) (cpuAtEnd - cpuAtStart) * 100.0 / elapsed) : null);
        result.put("processCpuUnavailableReason", cpuAvailable ? null : "JVM process CPU time is unavailable on this runtime");
        result.put("heapWarmupEndBytes", warmupEndHeapUsedBytes < 0L ? null : warmupEndHeapUsedBytes);
        return result;
    }

    private long gcCount() {
        long total = 0L;
        int available = 0;
        for (GarbageCollectorMXBean collector : collectors) if (collector.getCollectionCount() >= 0L) { total += collector.getCollectionCount(); available++; }
        return available == 0 ? -1L : total;
    }
    private long gcTime() {
        long total = 0L;
        int available = 0;
        for (GarbageCollectorMXBean collector : collectors) if (collector.getCollectionTime() >= 0L) { total += collector.getCollectionTime(); available++; }
        return available == 0 ? -1L : total;
    }
    private static long delta(long before, long after) { return before < 0L || after < before ? -1L : after - before; }
    private static long processCpuNanos() {
        try {
            Class<?> type = Class.forName("com.sun.management.OperatingSystemMXBean");
            Method method = type.getMethod("getProcessCpuTime");
            Object value = method.invoke(ManagementFactory.getOperatingSystemMXBean());
            return value instanceof Number ? ((Number) value).longValue() : -1L;
        } catch (Exception unavailable) { return -1L; }
    }
}
