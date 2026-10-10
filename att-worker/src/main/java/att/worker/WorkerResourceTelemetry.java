package att.worker;

import java.io.BufferedReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Event-triggered resource sampling for one isolated Worker process. */
final class WorkerResourceTelemetry {
    private static final long SAMPLE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final long maxHeapBytes = Runtime.getRuntime().maxMemory();
    private final long gcCountAtStart = gcCount();
    private final long gcTimeAtStart = gcTime();
    private final long cpuAtStart = processCpuNanos();
    private final long startedAtNanos = System.nanoTime();
    private long lastSampleNanos;
    private long peakHeapUsedBytes;
    private long peakHeapCommittedBytes;
    private long peakRssBytes = -1L;
    private long peakLiveThreads;
    private long samples;

    synchronized void sample() {
        long now = System.nanoTime();
        if (lastSampleNanos != 0L && now - lastSampleNanos < SAMPLE_INTERVAL_NANOS) return;
        lastSampleNanos = now;
        MemoryUsage heap = memory.getHeapMemoryUsage();
        peakHeapUsedBytes = Math.max(peakHeapUsedBytes, Math.max(0L, heap.getUsed()));
        peakHeapCommittedBytes = Math.max(peakHeapCommittedBytes, Math.max(0L, heap.getCommitted()));
        peakLiveThreads = Math.max(peakLiveThreads, threads.getThreadCount());
        long rss = processRssBytes();
        if (rss >= 0L) peakRssBytes = Math.max(peakRssBytes, rss);
        samples++;
    }

    synchronized Map<String, Object> snapshot() {
        sample();
        long cpuAtEnd = processCpuNanos();
        long elapsedNanos = Math.max(1L, System.nanoTime() - startedAtNanos);
        long cpuDelta = cpuAtStart < 0L || cpuAtEnd < cpuAtStart ? -1L : cpuAtEnd - cpuAtStart;
        long gcCount = delta(gcCountAtStart, gcCount());
        long gcTime = delta(gcTimeAtStart, gcTime());
        MemoryUsage heap = memory.getHeapMemoryUsage();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sampling", "event-triggered; at most one sample per 100 ms");
        result.put("sampleCount", samples);
        result.put("heapUsedBytes", Math.max(0L, heap.getUsed()));
        result.put("heapPeakUsedBytes", peakHeapUsedBytes);
        result.put("heapCommittedBytes", Math.max(0L, heap.getCommitted()));
        result.put("heapPeakCommittedBytes", peakHeapCommittedBytes);
        result.put("heapMaxBytes", maxHeapBytes);
        result.put("rssMetricsAvailable", peakRssBytes >= 0L);
        result.put("rssPeakBytes", peakRssBytes < 0L ? null : peakRssBytes);
        result.put("liveThreads", threads.getThreadCount());
        result.put("liveThreadsPeak", peakLiveThreads);
        result.put("gcMetricsAvailable", gcCount >= 0L && gcTime >= 0L);
        result.put("gcCollectionCount", gcCount);
        result.put("gcCollectionTimeMs", gcTime);
        result.put("processCpuSupported", cpuDelta >= 0L);
        result.put("processCpuTimeMs", cpuDelta < 0L ? null : TimeUnit.NANOSECONDS.toMillis(cpuDelta));
        result.put("processCpuPercent", cpuDelta < 0L ? null
                : Math.min(100.0 * Runtime.getRuntime().availableProcessors(), (double) cpuDelta * 100.0 / elapsedNanos));
        return result;
    }

    private long gcCount() {
        long total = 0L;
        int available = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            long count = collector.getCollectionCount();
            if (count >= 0L) { total += count; available++; }
        }
        return available == 0 ? -1L : total;
    }

    private long gcTime() {
        long total = 0L;
        int available = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            long time = collector.getCollectionTime();
            if (time >= 0L) { total += time; available++; }
        }
        return available == 0 ? -1L : total;
    }

    private static long delta(long before, long after) {
        return before < 0L || after < before ? -1L : after - before;
    }

    private static long processCpuNanos() {
        try {
            Class<?> type = Class.forName("com.sun.management.OperatingSystemMXBean");
            Method method = type.getMethod("getProcessCpuTime");
            Object value = method.invoke(ManagementFactory.getOperatingSystemMXBean());
            return value instanceof Number ? ((Number) value).longValue() : -1L;
        } catch (Exception unavailable) { return -1L; }
    }

    private static long processRssBytes() {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get("/proc/self/status"), StandardCharsets.US_ASCII)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String[] fields = line.trim().split("\\s+");
                    return Long.parseLong(fields[1]) * 1024L;
                }
            }
        } catch (Exception unavailable) { return -1L; }
        return -1L;
    }
}
