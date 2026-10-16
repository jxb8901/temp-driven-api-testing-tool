package att.metrics;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed-cardinality counters only: never accepts resource names, paths or configuration values. */
public final class InspectionMetrics {
    public static final InspectionMetrics JVM = new InspectionMetrics();
    public enum Stage {
        HTTP, QUEUE, STARTUP, DISPATCH, PROCESSING, CONFIG_PARSE, CONFIG_LOAD,
        SOURCE, WORKBOOK_PARSE, YAML_PARSE, INDEX_BUILD, INDEX_HIT, INDEX_MISS,
        INSPECTOR_HIT, INSPECTOR_MISS, INSPECTOR_EVICTION, CONFIG_HIT, CONFIG_MISS,
        CONFIG_EVICTION, INVALIDATION, WATCH_OVERFLOW, READ_BYTES, RESPONSE_BYTES
    }
    private final AtomicLongArray counts = new AtomicLongArray(Stage.values().length);
    private final AtomicLongArray nanos = new AtomicLongArray(Stage.values().length);
    public void record(Stage stage, long durationNanos) {
        counts.incrementAndGet(stage.ordinal());
        nanos.addAndGet(stage.ordinal(), Math.max(0, durationNanos));
    }
    public long count(Stage stage) { return counts.get(stage.ordinal()); }
    public void add(Stage stage, long count) { counts.addAndGet(stage.ordinal(), count); }
    public Map<String,Object> snapshot() {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (Stage stage : Stage.values()) {
            Map<String,Object> value = new LinkedHashMap<String,Object>();
            value.put("count", counts.get(stage.ordinal()));
            value.put("totalNanos", nanos.get(stage.ordinal()));
            result.put(stage.name(), value);
        }
        long peak = 0, used = 0;
        for (java.lang.management.MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
            if (pool.getPeakUsage() != null) peak += Math.max(0, pool.getPeakUsage().getUsed());
            if (pool.getUsage() != null) used += Math.max(0, pool.getUsage().getUsed());
        }
        long gcCount = 0, gcMs = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount()); gcMs += Math.max(0, gc.getCollectionTime());
        }
        result.put("heapUsedBytes", used); result.put("heapPoolPeakBytes", peak);
        result.put("gcCount", gcCount); result.put("gcTimeMs", gcMs);
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean)
            result.put("processCpuNanos", ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime());
        return result;
    }
}
