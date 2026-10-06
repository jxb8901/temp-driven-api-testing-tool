package att.load;

import att.core.ResultStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe, bounded-memory load metric aggregator. */
public final class LoadMetrics implements LoadEventListener {
    static final int MAX_LATENCIES = 4096;
    static final int MAX_BUCKETS = 4096;
    private static final int MAX_ERROR_CLASSIFICATIONS = 256;
    private static final int MAX_BUCKET_ERROR_CLASSIFICATIONS = 64;
    private static final int MAX_BUCKET_LATENCIES = 256;
    private static final long NO_TIMESTAMP = Long.MAX_VALUE;

    private final ConcurrentLatencyReservoir latencies = new ConcurrentLatencyReservoir(MAX_LATENCIES);
    private final BucketSlot[] buckets = bucketSlots();
    /* The phase set is fixed by the load contract, so this remains bounded independently of run duration. */
    private final ConcurrentHashMap<String, PhaseStats> phases = new ConcurrentHashMap<String, PhaseStats>();
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
    private final AtomicLong schedulerWakeups = new AtomicLong();
    private final AtomicLong submitLagCount = new AtomicLong(), submitLagSumMs = new AtomicLong(), submitLagMaxMs = new AtomicLong();
    private final AtomicInteger workerQueueDepth = new AtomicInteger(), workerQueueDepthPeak = new AtomicInteger();
    private final GeneratorTelemetry generatorTelemetry = new GeneratorTelemetry();
    private final AtomicLong measuredWindowStartMs = new AtomicLong(NO_TIMESTAMP);
    private final Object errorClassificationLock = new Object();
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
        generatorTelemetry.sample(isWarmup(event));
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
                activeUsers.add(activeUserKey(event));
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
                activeUsers.remove(activeUserKey(event));
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

        if (event.phase() != null) phase(event.phase()).accept(event, inFlight.get(), activeVus.get());
        long bucketStart = Math.floorDiv(event.scheduledAtEpochMs(), 1000L) * 1000L;
        Bucket bucket = bucket(bucketStart);
        if (bucket != null) bucket.accept(event, inFlight.get(), activeVus.get());
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

    public void recordSchedulerWakeup(int queueDepth) {
        schedulerWakeups.incrementAndGet();
        recordWorkerQueueDepth(queueDepth);
    }
    public void recordSubmitLag(long millis, int queueDepth) {
        long value = Math.max(0L, millis);
        submitLagCount.incrementAndGet(); submitLagSumMs.addAndGet(value); updateMax(submitLagMaxMs, value);
        recordWorkerQueueDepth(queueDepth);
    }
    public void recordWorkerQueueDepth(int depth) {
        int value = Math.max(0, depth); workerQueueDepth.set(value); updateMax(workerQueueDepthPeak, value);
    }
    public void mergeSchedulerTelemetry(Map<String, Object> values) {
        if (values == null) return;
        Object wakeups = values.get("schedulerWakeups");
        if (wakeups instanceof Number) schedulerWakeups.addAndGet(((Number) wakeups).longValue());
        Object count = values.get("submitLagCount"), mean = values.get("submitLagMeanMs"), maximum = values.get("submitLagMaxMs");
        if (count instanceof Number) {
            long n = ((Number) count).longValue(); submitLagCount.addAndGet(n);
            if (mean instanceof Number) submitLagSumMs.addAndGet(Math.round(((Number) mean).doubleValue() * n));
        }
        if (maximum instanceof Number) updateMax(submitLagMaxMs, ((Number) maximum).longValue());
        Object peak = values.get("workerQueueDepthPeak");
        if (peak instanceof Number) updateMax(workerQueueDepthPeak, ((Number) peak).intValue());
    }

    public LoadMetricsSnapshot snapshot() {
        long[] sorted = latencies.snapshot();
        Arrays.sort(sorted);
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
        result.put("schedulerWakeups", schedulerWakeups.get());
        result.put("submitLagCount", submitLagCount.get());
        result.put("submitLagMeanMs", submitLagCount.get() == 0L ? 0.0 : ((double) submitLagSumMs.get()) / submitLagCount.get());
        result.put("submitLagMaxMs", submitLagMaxMs.get());
        result.put("workerQueueDepth", workerQueueDepth.get());
        result.put("workerQueueDepthPeak", workerQueueDepthPeak.get());
        result.put("generator", generatorTelemetry.snapshot());
        result.put("errorClassifications", classificationSnapshot());
        result.put("latencySampleCount", sorted.length);
        result.put("latencySampleCapacity", MAX_LATENCIES);
        result.put("latencySampleRate", measuredLatencyCount.get() == 0L ? 0.0
                : Math.min(1.0, ((double) sorted.length) / measuredLatencyCount.get()));
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
        int bucketCount = 0;
        for (BucketSlot slot : buckets) {
            Bucket bucket = slot.value;
            if (bucket != null) { bucketMap.put(String.valueOf(bucket.bucketStart), bucket.toMap()); bucketCount++; }
        }
        result.put("timeSeriesBucketCount", bucketCount);
        Map<String, Map<String, Object>> phaseMap = new LinkedHashMap<String, Map<String, Object>>();
        String[] phaseOrder = {"WARMUP", "RAMP_UP", "STEADY", "RAMP_DOWN"};
        for (String phase : phaseOrder) {
            PhaseStats value = phases.get(phase);
            if (value != null) phaseMap.put(phase, value.toMap());
        }
        return new LoadMetricsSnapshot(result, bucketMap, phaseMap);
    }

    private PhaseStats phase(String name) {
        PhaseStats value = phases.get(name);
        if (value != null) return value;
        PhaseStats created = new PhaseStats(name);
        PhaseStats previous = phases.putIfAbsent(name, created);
        return previous == null ? created : previous;
    }

    private Bucket bucket(long bucketStart) {
        long bucketIndex = Math.floorDiv(bucketStart, 1000L);
        int index = (int) Math.floorMod(bucketIndex, (long) MAX_BUCKETS);
        return buckets[index].get(bucketStart, model, configuredUsers,
                configuredArrivalRatePerSecond, configuredMaxConcurrent);
    }

    private void recordLatency(long latencyMs, long observation) {
        latencies.add(Math.max(0L, latencyMs), observation);
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
        if (count == null) {
            synchronized (errorClassificationLock) {
                count = errorClassifications.get(type);
                if (count == null) {
                    if (errorClassifications.size() >= MAX_ERROR_CLASSIFICATIONS) type = "OTHER";
                    count = errorClassifications.get(type);
                    if (count == null) {
                        AtomicLong candidate = new AtomicLong();
                        AtomicLong existing = errorClassifications.putIfAbsent(type, candidate);
                        count = existing == null ? candidate : existing;
                    }
                }
            }
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

    private static String activeUserKey(LoadEvent event) {
        return event.workloadId() == null ? event.userId() : event.workloadId() + "/" + event.userId();
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
    private static long percentile(long[] sorted, double percentile) {
        if (sorted.length == 0) return 0L;
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static BucketSlot[] bucketSlots() {
        BucketSlot[] slots = new BucketSlot[MAX_BUCKETS];
        for (int index = 0; index < slots.length; index++) slots[index] = new BucketSlot();
        return slots;
    }

    private static final class PhaseStats {
        private final String name;
        private final ConcurrentLatencyReservoir latencies = new ConcurrentLatencyReservoir(MAX_BUCKET_LATENCIES);
        private final AtomicLong startAt = new AtomicLong(Long.MAX_VALUE), endAt = new AtomicLong();
        private final LongAdder scheduled = new LongAdder(), started = new LongAdder(), completed = new LongAdder();
        private final LongAdder success = new LongAdder(), failure = new LongAdder(), runtimeError = new LongAdder(), dropped = new LongAdder();
        private final LongAdder measuredScheduled = new LongAdder(), measuredStarted = new LongAdder(), measuredCompleted = new LongAdder();
        private final LongAdder measuredSuccess = new LongAdder(), measuredFailure = new LongAdder(), measuredRuntimeError = new LongAdder(), measuredDropped = new LongAdder();
        private final LongAdder schedulerLagCount = new LongAdder(), schedulerLagSumMs = new LongAdder(), latencyCount = new LongAdder(), latencySumMs = new LongAdder();
        private final AtomicLong schedulerLagMaxMs = new AtomicLong(), latencyMinMs = new AtomicLong(Long.MAX_VALUE), latencyMaxMs = new AtomicLong();
        private final AtomicInteger currentInFlight = new AtomicInteger(), maxInFlight = new AtomicInteger(), activeVus = new AtomicInteger(), maxActiveVus = new AtomicInteger();

        private PhaseStats(String name) { this.name = name; }

        private void accept(LoadEvent event, int currentInFlight, int activeVus) {
            long eventStart = event.scheduledAtEpochMs() > 0L ? event.scheduledAtEpochMs() : event.startedAtEpochMs();
            if (eventStart > 0L) updateMin(startAt, eventStart);
            long eventEnd = event.completedAtEpochMs() > 0L ? event.completedAtEpochMs()
                    : event.startedAtEpochMs() > 0L ? event.startedAtEpochMs() : event.scheduledAtEpochMs();
            if (eventEnd > 0L) updateMax(endAt, eventEnd);
            boolean measured = !"WARMUP".equals(name);
            if (event.scheduled()) { scheduled.increment(); if (measured) measuredScheduled.increment(); }
            if (event.started()) {
                started.increment(); if (measured) measuredStarted.increment();
                recordLag(event);
            } else if (event.dropped()) {
                recordLag(event);
            }
            if (event.dropped()) { dropped.increment(); if (measured) measuredDropped.increment(); }
            if (event.completed()) {
                completed.increment();
                if (event.status() == ResultStatus.PASS) { success.increment(); if (measured) measuredSuccess.increment(); }
                else if (event.status() == ResultStatus.ERROR || event.status() == ResultStatus.INVALID) {
                    runtimeError.increment(); if (measured) measuredRuntimeError.increment();
                } else { failure.increment(); if (measured) measuredFailure.increment(); }
                if (measured) {
                    measuredCompleted.increment();
                    long latency = Math.max(0L, event.latencyMs());
                    long observation = latencyCount.sum() + 1L;
                    latencyCount.increment(); latencySumMs.add(latency);
                    updateMin(latencyMinMs, latency); updateMax(latencyMaxMs, latency);
                    latencies.add(latency, observation);
                }
            }
            this.currentInFlight.set(Math.max(0, currentInFlight));
            this.activeVus.set(Math.max(0, activeVus));
            updateMax(maxInFlight, this.currentInFlight.get());
            updateMax(maxActiveVus, this.activeVus.get());
        }

        private void recordLag(LoadEvent event) {
            long lag = Math.max(0L, event.schedulerLagMs());
            schedulerLagCount.increment(); schedulerLagSumMs.add(lag); updateMax(schedulerLagMaxMs, lag);
        }

        private Map<String, Object> toMap() {
            long[] sorted = latencies.snapshot(); Arrays.sort(sorted);
            long start = startAt.get(), end = endAt.get();
            long scheduledCount = scheduled.sum(), measuredScheduledCount = measuredScheduled.sum();
            long startedCount = started.sum(), measuredStartedCount = measuredStarted.sum();
            long completedCount = completed.sum(), measuredCompletedCount = measuredCompleted.sum();
            long successCount = success.sum(), measuredSuccessCount = measuredSuccess.sum();
            long failureCount = failure.sum(), measuredFailureCount = measuredFailure.sum();
            long runtimeErrorCount = runtimeError.sum(), measuredRuntimeErrorCount = measuredRuntimeError.sum();
            long droppedCount = dropped.sum(), measuredDroppedCount = measuredDropped.sum();
            long lagCount = schedulerLagCount.sum(), lagSum = schedulerLagSumMs.sum();
            long observedLatencyCount = latencyCount.sum(), observedLatencySum = latencySumMs.sum();
            long durationMs = start == Long.MAX_VALUE ? 0L : Math.max(0L, end - start);
            double seconds = Math.max(0.001, durationMs / 1000.0);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("phase", name); result.put("startAtEpochMs", start == Long.MAX_VALUE ? 0L : start);
            result.put("endAtEpochMs", end); result.put("durationMs", durationMs);
            result.put("measured", !"WARMUP".equals(name));
            result.put("scheduled", scheduledCount); result.put("measuredScheduled", measuredScheduledCount);
            result.put("started", startedCount); result.put("measuredStarted", measuredStartedCount);
            result.put("completed", completedCount); result.put("measuredCompleted", measuredCompletedCount);
            result.put("success", successCount); result.put("measuredSuccess", measuredSuccessCount);
            result.put("failure", failureCount); result.put("measuredFailure", measuredFailureCount);
            result.put("runtimeError", runtimeErrorCount); result.put("measuredRuntimeError", measuredRuntimeErrorCount);
            result.put("dropped", droppedCount); result.put("measuredDropped", measuredDroppedCount);
            result.put("completedThroughput", measuredCompletedCount / seconds);
            result.put("sutErrorRate", measuredCompletedCount == 0L ? 0.0 : ((double) measuredFailureCount) / measuredCompletedCount);
            result.put("runtimeErrorRate", measuredCompletedCount == 0L ? 0.0 : ((double) measuredRuntimeErrorCount) / measuredCompletedCount);
            result.put("droppedRate", measuredScheduledCount == 0L ? 0.0 : ((double) measuredDroppedCount) / measuredScheduledCount);
            result.put("schedulerLagCount", lagCount);
            result.put("schedulerLagMeanMs", lagCount == 0L ? 0.0 : ((double) lagSum) / lagCount);
            result.put("schedulerLagMaxMs", schedulerLagMaxMs.get());
            result.put("currentInFlight", currentInFlight.get()); result.put("maxInFlight", maxInFlight.get());
            result.put("activeVus", activeVus.get()); result.put("maxActiveVus", maxActiveVus.get());
            result.put("latencyObservationCount", observedLatencyCount); result.put("latencySampleCount", sorted.length);
            result.put("latencySampleCapacity", MAX_BUCKET_LATENCIES);
            result.put("latencySampleRate", observedLatencyCount == 0L ? 0.0 : Math.min(1.0, ((double) sorted.length) / observedLatencyCount));
            result.put("latencyMinMs", observedLatencyCount == 0L ? 0L : latencyMinMs.get());
            result.put("latencyMeanMs", observedLatencyCount == 0L ? 0.0 : ((double) observedLatencySum) / observedLatencyCount);
            result.put("p50Ms", percentile(sorted, 0.50)); result.put("p90Ms", percentile(sorted, 0.90));
            result.put("p95Ms", percentile(sorted, 0.95)); result.put("p99Ms", percentile(sorted, 0.99));
            result.put("latencyMaxMs", observedLatencyCount == 0L ? 0L : latencyMaxMs.get());
            return result;
        }
    }

    private static final class Bucket {
        private final long bucketStart;
        private final String model;
        private final int configuredUsers, configuredMaxConcurrent;
        private final double configuredArrivalRatePerSecond;
        private final ConcurrentLatencyReservoir latencies = new ConcurrentLatencyReservoir(MAX_BUCKET_LATENCIES);
        private final Map<String, AtomicLong> errors = new ConcurrentHashMap<String, AtomicLong>();
        private final Object errorLock = new Object();
        private final java.util.concurrent.atomic.AtomicReference<String> phase = new java.util.concurrent.atomic.AtomicReference<String>();
        private final LongAdder scheduled = new LongAdder(), measuredScheduled = new LongAdder(), started = new LongAdder();
        private final LongAdder completed = new LongAdder(), success = new LongAdder(), failure = new LongAdder(), dropped = new LongAdder();
        private final LongAdder measuredDropped = new LongAdder(), warmupCompleted = new LongAdder(), sutFailure = new LongAdder(), runtimeError = new LongAdder();
        private final LongAdder schedulerLagCount = new LongAdder(), schedulerLagSumMs = new LongAdder(), latencyCount = new LongAdder(), latencySumMs = new LongAdder();
        private final AtomicLong schedulerLagMaxMs = new AtomicLong(), latencyMinMs = new AtomicLong(Long.MAX_VALUE), latencyMaxMs = new AtomicLong();
        private final AtomicInteger currentInFlight = new AtomicInteger(), maxInFlight = new AtomicInteger(), activeVus = new AtomicInteger(), maxActiveVus = new AtomicInteger();

        private Bucket(long bucketStart, String model, int configuredUsers, double configuredArrivalRatePerSecond, int configuredMaxConcurrent) {
            this.bucketStart = bucketStart;
            this.model = model;
            this.configuredUsers = configuredUsers;
            this.configuredArrivalRatePerSecond = configuredArrivalRatePerSecond;
            this.configuredMaxConcurrent = configuredMaxConcurrent;
        }

        private void accept(LoadEvent event, int currentInFlight, int activeVus) {
            if (event.phase() != null) {
                phase.updateAndGet(previous -> previous == null ? event.phase()
                        : previous.equals(event.phase()) ? previous : "MIXED");
            }
            if (event.scheduled()) {
                scheduled.increment();
                if (!isWarmup(event)) measuredScheduled.increment();
            }
            if (event.started()) {
                started.increment();
                recordLag(event);
            }
            if (event.dropped()) {
                dropped.increment();
                if (!isWarmup(event)) measuredDropped.increment();
                if (!event.started()) recordLag(event);
            }
            this.currentInFlight.set(Math.max(0, currentInFlight));
            updateMax(maxInFlight, this.currentInFlight.get());
            this.activeVus.set(Math.max(0, activeVus));
            updateMax(maxActiveVus, this.activeVus.get());
            if (event.completed()) {
                completed.increment();
                if (event.success()) success.increment(); else failure.increment();
                if (isWarmup(event)) warmupCompleted.increment();
                else {
                    if (event.status() == ResultStatus.FAIL) sutFailure.increment();
                    addLatency(event.latencyMs());
                }
                if (event.status() == ResultStatus.ERROR) runtimeError.increment();
                if (event.status() != null && event.status() != ResultStatus.PASS) {
                    String type = event.errorType();
                    if (type == null || type.trim().isEmpty()) type = event.status().name();
                    AtomicLong count = errors.get(type);
                    if (count == null) {
                        synchronized (errorLock) {
                            count = errors.get(type);
                            if (count == null) {
                                if (errors.size() >= MAX_BUCKET_ERROR_CLASSIFICATIONS) type = "OTHER";
                                count = errors.get(type);
                                if (count == null) {
                                    AtomicLong candidate = new AtomicLong();
                                    AtomicLong previous = errors.putIfAbsent(type, candidate);
                                    count = previous == null ? candidate : previous;
                                }
                            }
                        }
                    }
                    count.incrementAndGet();
                }
            }
        }

        private void recordLag(LoadEvent event) {
            long lag = Math.max(0L, event.schedulerLagMs());
            schedulerLagCount.increment(); schedulerLagSumMs.add(lag); updateMax(schedulerLagMaxMs, lag);
        }

        private void addLatency(long latencyMs) {
            long observation = latencyCount.sum() + 1L;
            latencyCount.increment();
            long value = Math.max(0L, latencyMs);
            latencySumMs.add(value);
            updateMin(latencyMinMs, value);
            updateMax(latencyMaxMs, value);
            latencies.add(value, observation);
        }

        private Map<String, Object> toMap() {
            long[] sorted = latencies.snapshot();
            Arrays.sort(sorted);
            long scheduledCount = scheduled.sum(), measuredScheduledCount = measuredScheduled.sum();
            long completedCount = completed.sum(), warmupCount = warmupCompleted.sum();
            long measuredCount = completedCount - warmupCount, lagCount = schedulerLagCount.sum();
            long observedLatencyCount = latencyCount.sum(), observedLatencySum = latencySumMs.sum();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("bucketStartEpochMs", bucketStart);
            result.put("bucketStart", Instant.ofEpochMilli(bucketStart).toString());
            result.put("model", model);
            result.put("phase", phase.get() == null ? "UNKNOWN" : phase.get());
            result.put("configuredUsers", configuredUsers);
            result.put("configuredArrivalRatePerSecond", configuredArrivalRatePerSecond);
            result.put("configuredMaxConcurrent", configuredMaxConcurrent);
            result.put("scheduled", scheduledCount);
            result.put("measuredScheduled", measuredScheduledCount);
            result.put("started", started.sum());
            result.put("completed", completedCount);
            result.put("success", success.sum());
            result.put("failure", failure.sum());
            result.put("runtimeError", runtimeError.sum());
            result.put("dropped", dropped.sum());
            result.put("measuredDropped", measuredDropped.sum());
            result.put("warmupCompleted", warmupCount);
            result.put("measuredCompleted", measuredCount);
            result.put("sutErrorRate", measuredCount == 0L ? 0.0 : ((double) sutFailure.sum()) / measuredCount);
            result.put("droppedRate", measuredScheduledCount == 0L ? 0.0 : ((double) measuredDropped.sum()) / measuredScheduledCount);
            result.put("allDroppedRate", scheduledCount == 0L ? 0.0 : ((double) dropped.sum()) / scheduledCount);
            result.put("completedThroughput", measuredCount);
            result.put("completedTps", measuredCount);
            result.put("currentInFlight", currentInFlight.get());
            result.put("maxInFlight", maxInFlight.get());
            result.put("activeVus", activeVus.get());
            result.put("maxActiveVus", maxActiveVus.get());
            result.put("schedulerLagCount", lagCount);
            result.put("schedulerLagMeanMs", lagCount == 0L ? 0.0 : ((double) schedulerLagSumMs.sum()) / lagCount);
            result.put("schedulerLagMaxMs", schedulerLagMaxMs.get());
            result.put("latencySampleCount", sorted.length);
            result.put("latencySampleCapacity", MAX_BUCKET_LATENCIES);
            result.put("latencySampleRate", observedLatencyCount == 0L ? 0.0 : Math.min(1.0, ((double) sorted.length) / observedLatencyCount));
            result.put("latencyObservationCount", observedLatencyCount);
            result.put("latencyMinMs", observedLatencyCount == 0L ? 0L : latencyMinMs.get());
            result.put("latencyMeanMs", observedLatencyCount == 0L ? 0.0 : ((double) observedLatencySum) / observedLatencyCount);
            result.put("p95Ms", percentile(sorted, 0.95));
            result.put("p99Ms", percentile(sorted, 0.99));
            result.put("latencyMaxMs", observedLatencyCount == 0L ? 0L : latencyMaxMs.get());
            Map<String, Long> errorSnapshot = new TreeMap<String, Long>();
            for (Map.Entry<String, AtomicLong> error : errors.entrySet()) errorSnapshot.put(error.getKey(), error.getValue().get());
            result.put("errorClassifications", new LinkedHashMap<String, Long>(errorSnapshot));
            return result;
        }
    }

    private static final class BucketSlot {
        private volatile Bucket value;
        Bucket get(long start, String model, int users, double rate, int maxConcurrent) {
            Bucket current = value;
            if (current != null && current.bucketStart == start) return current;
            synchronized (this) {
                current = value;
                if (current != null && current.bucketStart == start) return current;
                if (current != null && current.bucketStart > start) return null;
                Bucket replacement = new Bucket(start, model, users, rate, maxConcurrent);
                value = replacement;
                return replacement;
            }
        }
    }

    private static final class ConcurrentLatencyReservoir {
        private final AtomicLongArray samples;
        private final AtomicLong observations = new AtomicLong();
        ConcurrentLatencyReservoir(int capacity) { samples = new AtomicLongArray(Math.max(1, capacity)); }
        void add(long value, long ignoredObservation) {
            long seen = observations.incrementAndGet();
            if (seen <= samples.length()) samples.set((int) seen - 1, value);
            else {
                long slot = reservoirSlot(seen, seen);
                if (slot < samples.length()) samples.set((int) slot, value);
            }
        }
        long[] snapshot() {
            int size = (int) Math.min(samples.length(), observations.get());
            long[] result = new long[size];
            for (int index = 0; index < size; index++) result[index] = samples.get(index);
            return result;
        }
    }

}
