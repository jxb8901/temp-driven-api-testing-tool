package att.load;

import att.core.ResultStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe, bounded-memory load metric aggregator. */
public final class LoadMetrics implements LoadEventListener {
    static final int MAX_LATENCIES = 4096;
    static final int MAX_BUCKETS = 4096;
    private static final int MAX_ERROR_CLASSIFICATIONS = 256;
    private static final int MAX_BUCKET_ERROR_CLASSIFICATIONS = 64;
    private static final int MAX_BUCKET_LATENCIES = 256;
    private static final long NO_TIMESTAMP = Long.MAX_VALUE;

    private final List<Long> latencies = new ArrayList<Long>();
    /* Bucket lookup is lock-free; creation/eviction is short-lived and individual updates are per bucket. */
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<Long, Bucket>();
    private final Object bucketLock = new Object();
    private final Map<String, AtomicLong> errorClassifications = new ConcurrentHashMap<String, AtomicLong>();
    private final Set<String> activeUsers = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicLong scheduled = new AtomicLong(), measuredScheduled = new AtomicLong(), started = new AtomicLong(), completed = new AtomicLong();
    private final AtomicLong success = new AtomicLong(), failure = new AtomicLong(), runtimeError = new AtomicLong(), dropped = new AtomicLong();
    private final AtomicLong measuredCompleted = new AtomicLong(), measuredFailure = new AtomicLong(), measuredSuccess = new AtomicLong(), measuredRuntimeError = new AtomicLong(), measuredDropped = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
    private final AtomicInteger activeVus = new AtomicInteger(), maxActiveVus = new AtomicInteger();
    private final AtomicLong measuredLatencyCount = new AtomicLong(), warmupCompleted = new AtomicLong(), measuredStarted = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyMinMs = new AtomicLong(Long.MAX_VALUE), latencyMaxMs = new AtomicLong();
    private final AtomicLong schedulerLagCount = new AtomicLong(), schedulerLagSumMs = new AtomicLong(), schedulerLagMaxMs = new AtomicLong();
    private final AtomicLong measuredWindowStartMs = new AtomicLong(NO_TIMESTAMP);
    private final String model;
    private final long startedAtEpochMs;
    private final long rateWindowMs;
    private final int configuredUsers;
    private final int configuredMaxConcurrent;
    private final double configuredArrivalRatePerSecond;
    private volatile long endedAtEpochMs;

    public LoadMetrics(String model, long startedAtEpochMs) { this(model, startedAtEpochMs, 0L, 0, 0.0, 0); }
    public LoadMetrics(String model, long startedAtEpochMs, long rateWindowMs) { this(model, startedAtEpochMs, rateWindowMs, 0, 0.0, 0); }

    public LoadMetrics(String model, long startedAtEpochMs, long rateWindowMs,
                       int configuredUsers, double configuredArrivalRatePerSecond, int configuredMaxConcurrent) {
        this.model = model;
        this.startedAtEpochMs = startedAtEpochMs;
        this.rateWindowMs = Math.max(0L, rateWindowMs);
        this.configuredUsers = Math.max(0, configuredUsers);
        this.configuredArrivalRatePerSecond = Math.max(0.0, configuredArrivalRatePerSecond);
        this.configuredMaxConcurrent = Math.max(0, configuredMaxConcurrent);
    }

    /** Creates a metrics view with scenario metadata required to compare configured and achieved load. */
    public static LoadMetrics forScenario(LoadScenario scenario, long startedAtEpochMs, long rateWindowMs) {
        if (scenario == null) throw new IllegalArgumentException("LoadMetrics requires a scenario");
        boolean closed = scenario.model() == LoadScenario.Model.CLOSED;
        boolean arrival = scenario.model() == LoadScenario.Model.ARRIVAL_RATE;
        return new LoadMetrics(scenario.model().wireName(), startedAtEpochMs, rateWindowMs,
                closed ? scenario.users() : 0,
                arrival ? scenario.arrivalRatePerSecond() : 0.0,
                arrival ? scenario.maxConcurrent() : 0);
    }

    @Override public void onEvent(LoadEvent event) {
        if (event == null) return;
        if (event.scheduled()) {
            scheduled.incrementAndGet();
            if (!isWarmup(event)) measuredScheduled.incrementAndGet();
        }
        if (event.dropped()) {
            dropped.incrementAndGet();
            if (!isWarmup(event)) measuredDropped.incrementAndGet();
        }
        if (event.started()) {
            started.incrementAndGet();
            if (!isWarmup(event)) measuredStarted.incrementAndGet();
            recordMeasuredTimestamp(event.scheduledAtEpochMs(), event.phase());
            recordSchedulerLag(event);
            int current = inFlight.incrementAndGet();
            updateMax(maxInFlight, current);
            if (event.userId() != null) {
                activeUsers.add(event.userId());
                int currentVus = activeUsers.size();
                activeVus.set(currentVus);
                updateMax(maxActiveVus, currentVus);
            }
        } else if (event.dropped()) {
            recordSchedulerLag(event);
        }
        if (event.completed()) {
            completed.incrementAndGet();
            inFlight.updateAndGet(value -> Math.max(0, value - 1));
            if (event.userId() != null) {
                activeUsers.remove(event.userId());
                activeVus.set(activeUsers.size());
            }
            if (event.status() == ResultStatus.PASS) {
                success.incrementAndGet();
                if (!isWarmup(event)) measuredSuccess.incrementAndGet();
            } else if (event.status() == ResultStatus.ERROR || event.status() == ResultStatus.INVALID) {
                runtimeError.incrementAndGet();
                if (!isWarmup(event)) measuredRuntimeError.incrementAndGet();
            } else {
                failure.incrementAndGet();
                if (!isWarmup(event)) measuredFailure.incrementAndGet();
            }
            if (isWarmup(event)) {
                warmupCompleted.incrementAndGet();
            } else {
                measuredCompleted.incrementAndGet();
                recordMeasuredTimestamp(event.completedAtEpochMs() > 0L ? event.completedAtEpochMs() : event.scheduledAtEpochMs(), event.phase());
                long latencyMs = Math.max(0L, event.latencyMs());
                long observation = measuredLatencyCount.incrementAndGet();
                latencySumMs.addAndGet(latencyMs);
                updateMin(latencyMinMs, latencyMs);
                updateMax(latencyMaxMs, latencyMs);
                recordLatency(latencyMs, observation);
            }
            if (event.status() != null && event.status() != ResultStatus.PASS) recordError(event);
        }

        long bucketStart = Math.floorDiv(event.scheduledAtEpochMs(), 1000L) * 1000L;
        Bucket bucket = bucket(bucketStart);
        bucket.accept(event, inFlight.get(), activeVus.get());
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
        long ended = endedAtEpochMs == 0L ? System.currentTimeMillis() : endedAtEpochMs;
        long elapsedMs = rateWindowMs > 0L ? rateWindowMs : Math.max(1L, ended - startedAtEpochMs);
        double elapsedSeconds = Math.max(0.001, elapsedMs / 1000.0);
        long measuredStart = measuredWindowStartMs.get();
        long measuredElapsedMs = measuredStart == NO_TIMESTAMP ? elapsedMs : Math.max(1L, ended - measuredStart);
        double measuredElapsedSeconds = Math.max(0.001, measuredElapsedMs / 1000.0);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("model", model);
        result.put("configuredUsers", configuredUsers);
        result.put("configuredArrivalRatePerSecond", configuredArrivalRatePerSecond);
        result.put("configuredMaxConcurrent", configuredMaxConcurrent);
        result.put("scheduled", scheduled.get());
        result.put("measuredScheduled", measuredScheduled.get());
        result.put("started", started.get());
        result.put("measuredStarted", measuredStarted.get());
        result.put("completed", completed.get());
        result.put("iterations", completed.get());
        result.put("success", success.get());
        result.put("failure", failure.get());
        result.put("runtimeError", runtimeError.get());
        result.put("dropped", dropped.get());
        result.put("measuredDropped", measuredDropped.get());
        result.put("currentInFlight", inFlight.get());
        result.put("maxInFlight", maxInFlight.get());
        result.put("activeVus", activeVus.get());
        result.put("maxActiveVus", maxActiveVus.get());
        result.put("warmupCompleted", warmupCompleted.get());
        result.put("measuredCompleted", measuredCompleted.get());
        result.put("measuredSuccess", measuredSuccess.get());
        result.put("measuredFailure", measuredFailure.get());
        result.put("measuredRuntimeError", measuredRuntimeError.get());
        result.put("sutErrorRate", measuredCompleted.get() == 0L ? 0.0 : ((double) measuredFailure.get()) / measuredCompleted.get());
        result.put("runtimeErrorRate", measuredCompleted.get() == 0L ? 0.0 : ((double) measuredRuntimeError.get()) / measuredCompleted.get());
        result.put("droppedRate", measuredScheduled.get() == 0L ? 0.0 : ((double) measuredDropped.get()) / measuredScheduled.get());
        result.put("allDroppedRate", scheduled.get() == 0L ? 0.0 : ((double) dropped.get()) / scheduled.get());
        result.put("completedThroughput", measuredCompleted.get() / measuredElapsedSeconds);
        result.put("measuredCompletedThroughput", measuredCompleted.get() / measuredElapsedSeconds);
        result.put("allCompletedThroughput", completed.get() / elapsedSeconds);
        result.put("achievedArrivalRate", started.get() / elapsedSeconds);
        result.put("measuredAchievedArrivalRate", measuredStarted.get() / measuredElapsedSeconds);
        result.put("achievedArrivalRatePercent", measuredScheduled.get() == 0L ? 0.0 : ((double) measuredStarted.get() * 100.0) / measuredScheduled.get());
        result.put("schedulerLagCount", schedulerLagCount.get());
        result.put("schedulerLagMeanMs", schedulerLagCount.get() == 0L ? 0.0 : ((double) schedulerLagSumMs.get()) / schedulerLagCount.get());
        result.put("schedulerLagMaxMs", schedulerLagMaxMs.get());
        result.put("errorClassifications", classificationSnapshot());
        result.put("latencySampleCount", sorted.size());
        result.put("latencySampleCapacity", MAX_LATENCIES);
        result.put("latencyObservationCount", measuredLatencyCount.get());
        result.put("latencyMinMs", measuredLatencyCount.get() == 0L ? 0L : latencyMinMs.get());
        result.put("latencyMeanMs", measuredLatencyCount.get() == 0L ? 0.0 : ((double) latencySumMs.get()) / measuredLatencyCount.get());
        result.put("p50Ms", percentile(sorted, 0.50));
        result.put("p90Ms", percentile(sorted, 0.90));
        result.put("p95Ms", percentile(sorted, 0.95));
        result.put("p99Ms", percentile(sorted, 0.99));
        result.put("latencyMaxMs", measuredLatencyCount.get() == 0L ? 0L : latencyMaxMs.get());
        result.put("timeSeriesBucketCapacity", MAX_BUCKETS);
        Map<String, Map<String, Object>> bucketMap = new TreeMap<String, Map<String, Object>>();
        synchronized (bucketLock) {
            result.put("timeSeriesBucketCount", buckets.size());
            for (Map.Entry<Long, Bucket> entry : buckets.entrySet()) bucketMap.put(String.valueOf(entry.getKey()), entry.getValue().toMap());
        }
        return new LoadMetricsSnapshot(result, bucketMap);
    }

    private Bucket bucket(long bucketStart) {
        Bucket value = buckets.get(bucketStart);
        if (value != null) return value;
        synchronized (bucketLock) {
            value = buckets.get(bucketStart);
            if (value != null) return value;
            if (buckets.size() >= MAX_BUCKETS) {
                Long oldest = null;
                for (Long key : buckets.keySet()) if (oldest == null || key.longValue() < oldest.longValue()) oldest = key;
                if (oldest != null) buckets.remove(oldest);
            }
            value = new Bucket(bucketStart, model, configuredUsers, configuredArrivalRatePerSecond, configuredMaxConcurrent);
            buckets.put(bucketStart, value);
            return value;
        }
    }

    private void recordLatency(long latencyMs, long observation) {
        synchronized (latencies) {
            if (latencies.size() < MAX_LATENCIES) latencies.add(latencyMs);
            else {
                long slot = reservoirSlot(observation, observation);
                if (slot < MAX_LATENCIES) latencies.set((int) slot, Math.max(0L, latencyMs));
            }
        }
    }

    private void recordMeasuredTimestamp(long timestamp, String phase) {
        if (timestamp <= 0L || "WARMUP".equals(phase)) return;
        for (;;) {
            long previous = measuredWindowStartMs.get();
            if (timestamp >= previous || measuredWindowStartMs.compareAndSet(previous, timestamp)) break;
        }
    }

    private void recordSchedulerLag(LoadEvent event) {
        long lag = Math.max(0L, event.schedulerLagMs());
        schedulerLagCount.incrementAndGet();
        schedulerLagSumMs.addAndGet(lag);
        updateMax(schedulerLagMaxMs, lag);
    }

    private void recordError(LoadEvent event) {
        String type = event.errorType();
        if (type == null || type.trim().isEmpty()) type = event.status().name();
        AtomicLong count = errorClassifications.get(type);
        if (count == null && errorClassifications.size() >= MAX_ERROR_CLASSIFICATIONS) type = "OTHER";
        count = errorClassifications.get(type);
        if (count == null) {
            AtomicLong candidate = new AtomicLong();
            AtomicLong existing = errorClassifications.putIfAbsent(type, candidate);
            count = existing == null ? candidate : existing;
        }
        count.incrementAndGet();
    }

    private Map<String, Long> classificationSnapshot() {
        List<String> keys = new ArrayList<String>(errorClassifications.keySet());
        Collections.sort(keys);
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        for (String key : keys) result.put(key, errorClassifications.get(key).get());
        return result;
    }

    private static boolean isWarmup(LoadEvent event) { return "WARMUP".equals(event.phase()); }

    private static void updateMax(AtomicInteger target, int candidate) {
        for (;;) { int previous = target.get(); if (candidate <= previous || target.compareAndSet(previous, candidate)) return; }
    }
    private static void updateMax(AtomicLong target, long candidate) {
        for (;;) { long previous = target.get(); if (candidate <= previous || target.compareAndSet(previous, candidate)) return; }
    }
    private static void updateMin(AtomicLong target, long candidate) {
        for (;;) { long previous = target.get(); if (candidate >= previous || target.compareAndSet(previous, candidate)) return; }
    }
    /** SplitMix64-based deterministic draw for Algorithm R; unlike a linear expression it mixes the observation index. */
    private static long reservoirSlot(long observation, long bound) {
        long value = observation + 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return Math.floorMod(value, bound);
    }
    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0L;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static final class Bucket {
        private final long bucketStart;
        private final String model;
        private final int configuredUsers, configuredMaxConcurrent;
        private final double configuredArrivalRatePerSecond;
        private final List<Long> latencies = new ArrayList<Long>();
        private final Map<String, Long> errors = new LinkedHashMap<String, Long>();
        private String phase;
        private long scheduled, measuredScheduled, started, completed, success, failure, dropped, measuredDropped, warmupCompleted, sutFailure, runtimeError;
        private long schedulerLagCount, schedulerLagSumMs, schedulerLagMaxMs, latencyCount, latencySumMs;
        private long latencyMinMs = Long.MAX_VALUE, latencyMaxMs;
        private int currentInFlight, maxInFlight, activeVus, maxActiveVus;

        private Bucket(long bucketStart, String model, int configuredUsers, double configuredArrivalRatePerSecond, int configuredMaxConcurrent) {
            this.bucketStart = bucketStart;
            this.model = model;
            this.configuredUsers = configuredUsers;
            this.configuredArrivalRatePerSecond = configuredArrivalRatePerSecond;
            this.configuredMaxConcurrent = configuredMaxConcurrent;
        }

        private synchronized void accept(LoadEvent event, int currentInFlight, int activeVus) {
            if (event.phase() != null) {
                if (phase == null) phase = event.phase();
                else if (!phase.equals(event.phase())) phase = "MIXED";
            }
            if (event.scheduled()) {
                scheduled++;
                if (!isWarmup(event)) measuredScheduled++;
            }
            if (event.started()) {
                started++;
                schedulerLagCount++;
                schedulerLagSumMs += Math.max(0L, event.schedulerLagMs());
                schedulerLagMaxMs = Math.max(schedulerLagMaxMs, Math.max(0L, event.schedulerLagMs()));
            }
            if (event.dropped()) {
                dropped++;
                if (!isWarmup(event)) measuredDropped++;
                if (!event.started()) {
                    schedulerLagCount++;
                    schedulerLagSumMs += Math.max(0L, event.schedulerLagMs());
                    schedulerLagMaxMs = Math.max(schedulerLagMaxMs, Math.max(0L, event.schedulerLagMs()));
                }
            }
            this.currentInFlight = Math.max(0, currentInFlight);
            this.maxInFlight = Math.max(this.maxInFlight, this.currentInFlight);
            this.activeVus = Math.max(0, activeVus);
            this.maxActiveVus = Math.max(this.maxActiveVus, this.activeVus);
            if (event.completed()) {
                completed++;
                if (event.success()) success++; else failure++;
                if (isWarmup(event)) warmupCompleted++;
                else {
                    if (event.status() == ResultStatus.FAIL) sutFailure++;
                    addLatency(event.latencyMs());
                }
                if (event.status() == ResultStatus.ERROR) runtimeError++;
                if (event.status() != null && event.status() != ResultStatus.PASS) {
                    String type = event.errorType();
                    if (type == null || type.trim().isEmpty()) type = event.status().name();
                    if (!errors.containsKey(type) && errors.size() >= MAX_BUCKET_ERROR_CLASSIFICATIONS) type = "OTHER";
                    Long count = errors.get(type);
                    errors.put(type, count == null ? 1L : count + 1L);
                }
            }
        }

        private void addLatency(long latencyMs) {
            latencyCount++;
            long value = Math.max(0L, latencyMs);
            latencySumMs += value;
            latencyMinMs = Math.min(latencyMinMs, value);
            latencyMaxMs = Math.max(latencyMaxMs, value);
            if (latencies.size() < MAX_BUCKET_LATENCIES) latencies.add(value);
            else {
                long slot = reservoirSlot(latencyCount, latencyCount);
                if (slot < MAX_BUCKET_LATENCIES) latencies.set((int) slot, value);
            }
        }

        private synchronized Map<String, Object> toMap() {
            List<Long> sorted = new ArrayList<Long>(latencies);
            Collections.sort(sorted);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("bucketStartEpochMs", bucketStart);
            result.put("bucketStart", Instant.ofEpochMilli(bucketStart).toString());
            result.put("model", model);
            result.put("phase", phase == null ? "UNKNOWN" : phase);
            result.put("configuredUsers", configuredUsers);
            result.put("configuredArrivalRatePerSecond", configuredArrivalRatePerSecond);
            result.put("configuredMaxConcurrent", configuredMaxConcurrent);
            result.put("scheduled", scheduled);
            result.put("measuredScheduled", measuredScheduled);
            result.put("started", started);
            result.put("completed", completed);
            result.put("success", success);
            result.put("failure", failure);
            result.put("runtimeError", runtimeError);
            result.put("dropped", dropped);
            result.put("measuredDropped", measuredDropped);
            result.put("warmupCompleted", warmupCompleted);
            result.put("measuredCompleted", completed - warmupCompleted);
            result.put("sutErrorRate", completed - warmupCompleted == 0L ? 0.0 : ((double) sutFailure) / (completed - warmupCompleted));
            result.put("droppedRate", measuredScheduled == 0L ? 0.0 : ((double) measuredDropped) / measuredScheduled);
            result.put("allDroppedRate", scheduled == 0L ? 0.0 : ((double) dropped) / scheduled);
            result.put("completedThroughput", completed - warmupCompleted);
            result.put("completedTps", completed - warmupCompleted);
            result.put("currentInFlight", currentInFlight);
            result.put("maxInFlight", maxInFlight);
            result.put("activeVus", activeVus);
            result.put("maxActiveVus", maxActiveVus);
            result.put("schedulerLagCount", schedulerLagCount);
            result.put("schedulerLagMeanMs", schedulerLagCount == 0L ? 0.0 : ((double) schedulerLagSumMs) / schedulerLagCount);
            result.put("schedulerLagMaxMs", schedulerLagMaxMs);
            result.put("latencySampleCount", sorted.size());
            result.put("latencySampleCapacity", MAX_BUCKET_LATENCIES);
            result.put("latencyObservationCount", latencyCount);
            result.put("latencyMinMs", latencyCount == 0L ? 0L : latencyMinMs);
            result.put("latencyMeanMs", latencyCount == 0L ? 0.0 : ((double) latencySumMs) / latencyCount);
            result.put("p95Ms", percentile(sorted, 0.95));
            result.put("p99Ms", percentile(sorted, 0.99));
            result.put("latencyMaxMs", latencyCount == 0L ? 0L : latencyMaxMs);
            result.put("errorClassifications", new LinkedHashMap<String, Long>(errors));
            return result;
        }
    }
}
