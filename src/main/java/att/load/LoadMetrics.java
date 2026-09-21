package att.load;

import att.core.ResultStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe, bounded-memory load metric aggregator. */
public final class LoadMetrics implements LoadEventListener {
    private static final int MAX_LATENCIES = 4096;
    private final List<Long> latencies = new ArrayList<Long>();
    private final Map<Long, Bucket> buckets = new LinkedHashMap<Long, Bucket>();
    private final AtomicLong scheduled = new AtomicLong(), started = new AtomicLong(), completed = new AtomicLong();
    private final AtomicLong success = new AtomicLong(), failure = new AtomicLong(), runtimeError = new AtomicLong(), dropped = new AtomicLong();
    private final AtomicLong measuredFailure = new AtomicLong(), measuredSuccess = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
    private final AtomicLong measuredLatencyCount = new AtomicLong();
    private final String model;
    private final long startedAtEpochMs;
    private final long rateWindowMs;
    private volatile long endedAtEpochMs;

    public LoadMetrics(String model, long startedAtEpochMs) { this(model, startedAtEpochMs, 0L); }
    public LoadMetrics(String model, long startedAtEpochMs, long rateWindowMs) {
        this.model = model; this.startedAtEpochMs = startedAtEpochMs; this.rateWindowMs = Math.max(0L, rateWindowMs);
    }

    @Override public void onEvent(LoadEvent event) {
        if (event == null) return;
        if (event.scheduled()) scheduled.incrementAndGet();
        if (event.dropped()) dropped.incrementAndGet();
        if (event.started()) {
            started.incrementAndGet();
            int current = inFlight.incrementAndGet();
            for (;;) { int previous = maxInFlight.get(); if (current <= previous || maxInFlight.compareAndSet(previous, current)) break; }
        }
        if (event.completed()) {
            completed.incrementAndGet(); inFlight.updateAndGet(value -> Math.max(0, value - 1));
            if (event.status() == ResultStatus.PASS) { success.incrementAndGet(); if (!"WARMUP".equals(event.phase())) measuredSuccess.incrementAndGet(); }
            else if (event.status() == ResultStatus.ERROR) { runtimeError.incrementAndGet(); if (!"WARMUP".equals(event.phase())) measuredFailure.incrementAndGet(); }
            else { failure.incrementAndGet(); if (!"WARMUP".equals(event.phase())) measuredFailure.incrementAndGet(); }
            if (!"WARMUP".equals(event.phase())) {
                measuredLatencyCount.incrementAndGet();
                synchronized (latencies) {
                    long count = measuredLatencyCount.get();
                    if (latencies.size() < MAX_LATENCIES) latencies.add(event.latencyMs());
                    else {
                        long slot = Math.floorMod(count * 1103515245L + 12345L, count);
                        if (slot < MAX_LATENCIES) latencies.set((int) slot, event.latencyMs());
                    }
                }
            }
        }
        long bucketStart = (event.scheduledAtEpochMs() / 1000L) * 1000L;
        synchronized (buckets) { buckets.computeIfAbsent(bucketStart, key -> new Bucket()).accept(event); }
    }

    public void finish(long endedAtEpochMs) { this.endedAtEpochMs = endedAtEpochMs; }
    public long scheduled() { return scheduled.get(); }
    public long started() { return started.get(); }
    public long completed() { return completed.get(); }
    public long success() { return success.get(); }
    public long failure() { return failure.get(); }
    public long runtimeError() { return runtimeError.get(); }
    public long dropped() { return dropped.get(); }
    public int currentInFlight() { return inFlight.get(); }
    public int maxInFlight() { return maxInFlight.get(); }

    public LoadMetricsSnapshot snapshot() {
        List<Long> sorted;
        synchronized (latencies) { sorted = new ArrayList<Long>(latencies); }
        Collections.sort(sorted);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        long measured = Math.max(0L, completed.get() - warmupCompleted());
        long elapsedMs = rateWindowMs > 0L ? rateWindowMs : (endedAtEpochMs == 0L ? System.currentTimeMillis() : endedAtEpochMs) - startedAtEpochMs;
        double elapsedSeconds = Math.max(0.001, elapsedMs / 1000.0);
        long sum = 0L; for (Long value : sorted) sum += value.longValue();
        result.put("model", model); result.put("scheduled", scheduled.get()); result.put("started", started.get());
        result.put("completed", completed.get()); result.put("success", success.get()); result.put("failure", failure.get());
        result.put("runtimeError", runtimeError.get()); result.put("dropped", dropped.get()); result.put("currentInFlight", inFlight.get());
        result.put("maxInFlight", maxInFlight.get()); result.put("warmupCompleted", warmupCompleted()); result.put("measuredCompleted", measured);
        result.put("sutErrorRate", measured == 0L ? 0.0 : ((double) measuredFailure.get()) / measured);
        result.put("droppedRate", scheduled.get() == 0L ? 0.0 : ((double) dropped.get()) / scheduled.get());
        result.put("completedThroughput", completed.get() / elapsedSeconds);
        result.put("achievedArrivalRate", started.get() / elapsedSeconds);
        result.put("latencySampleCount", sorted.size()); result.put("latencySampleCapacity", MAX_LATENCIES);
        result.put("latencyMinMs", sorted.isEmpty() ? 0L : sorted.get(0)); result.put("latencyMeanMs", sorted.isEmpty() ? 0.0 : ((double) sum) / sorted.size());
        result.put("p50Ms", percentile(sorted, 0.50)); result.put("p90Ms", percentile(sorted, 0.90));
        result.put("p95Ms", percentile(sorted, 0.95)); result.put("p99Ms", percentile(sorted, 0.99));
        result.put("latencyMaxMs", sorted.isEmpty() ? 0L : sorted.get(sorted.size() - 1));
        Map<String, Map<String, Object>> bucketMap = new LinkedHashMap<String, Map<String, Object>>();
        synchronized (buckets) {
            for (Map.Entry<Long, Bucket> entry : buckets.entrySet()) bucketMap.put(String.valueOf(entry.getKey()), entry.getValue().toMap());
        }
        return new LoadMetricsSnapshot(result, bucketMap);
    }

    private long warmupCompleted() {
        long count = 0L; synchronized (buckets) { for (Bucket bucket : buckets.values()) count += bucket.warmupCompleted; }
        return count;
    }
    private long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0L;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static final class Bucket {
        long scheduled, started, completed, success, failure, dropped, p95;
        long warmupCompleted;
        void accept(LoadEvent event) {
            if (event.scheduled()) scheduled++; if (event.started()) started++; if (event.completed()) completed++; if (event.dropped()) dropped++;
            if (event.completed()) { if (event.success()) success++; else failure++; if ("WARMUP".equals(event.phase())) warmupCompleted++; p95 = Math.max(p95, event.latencyMs()); }
        }
        Map<String, Object> toMap() { Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("scheduled", scheduled); result.put("started", started); result.put("completed", completed); result.put("success", success); result.put("failure", failure); result.put("dropped", dropped); result.put("p95Ms", p95); result.put("warmupCompleted", warmupCompleted); return result; }
    }
}
