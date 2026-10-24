package att.load;

import att.core.ResultStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Explicit configured windows. Legacy LoadMetrics fields retain their original meaning.
 * Event rates use event timestamps; latency/outcome SLOs follow scheduled cohorts,
 * including their eventual outcomes in later phases or drain. */
final class LoadWindowMetrics {
    static final int SERIES_CAPACITY = 2048;
    static final int SAMPLE_CAPACITY = 4096;
    private final long start, generationEnd;
    private final List<Window> windows = new ArrayList<Window>();
    private final TreeMap<Long, Point> series = new TreeMap<Long, Point>();
    private long bucketWidthMs = 1000L;
    private volatile long ended;

    LoadWindowMetrics(long start, long warmup, long rampUp, long steady, long rampDown) {
        this.start = start;
        long measured = Math.addExact(start, warmup);
        long steadyStart = Math.addExact(measured, rampUp);
        long steadyEnd = Math.addExact(steadyStart, steady);
        generationEnd = Math.addExact(steadyEnd, rampDown);
        windows.add(new Window("all", start, Long.MAX_VALUE));
        windows.add(new Window("measured", measured, generationEnd));
        windows.add(new Window("steady", steadyStart, steadyEnd));
        windows.add(new Window("WARMUP", start, measured));
        windows.add(new Window("RAMP_UP", measured, steadyStart));
        windows.add(new Window("STEADY", steadyStart, steadyEnd));
        windows.add(new Window("RAMP_DOWN", steadyEnd, generationEnd));
        windows.add(new Window("DRAIN", generationEnd, Long.MAX_VALUE));
    }

    void onEvent(LoadEvent event) {
        for (Window window : windows) window.accept(event);
        if (event.scheduled()) record(event.scheduledAtEpochMs(), 0, event);
        if (event.started()) record(event.startedAtEpochMs(), 1, event);
        if (event.completed()) record(event.completedAtEpochMs(), 2, event);
        if (event.dropped()) record(event.scheduledAtEpochMs(), 3, event);
    }

    synchronized void finish(long end) {
        ended = end;
        for (Window window : windows) window.finish(end);
    }

    Map<String, Object> snapshot(long observedEnd) {
        long actualEnd = ended == 0L ? observedEnd : ended;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "att-load-window-metrics/v1");
        result.put("intervalConvention", "Configured windows are [start,end); all execution includes the terminal event timestamp.");
        result.put("generationEndEpochMs", generationEnd);
        result.put("finished", ended != 0L);
        Map<String, Object> summaries = new LinkedHashMap<String, Object>();
        for (Window window : windows) summaries.put(window.name, window.toMap(actualEnd, ended != 0L));
        result.put("windows", summaries);
        List<Map<String, Object>> phaseBoundaries = new ArrayList<Map<String, Object>>();
        for (Window window : windows) if (window.name.matches("WARMUP|RAMP_UP|STEADY|RAMP_DOWN|DRAIN")) {
            Map<String, Object> phase = new LinkedHashMap<String, Object>();
            phase.put("phase", window.name); phase.put("windowStartEpochMs", window.start);
            phase.put("windowEndEpochMs", window.end == Long.MAX_VALUE ? Math.max(window.start, actualEnd) : window.end);
            phase.put("measured", !"WARMUP".equals(window.name)); phaseBoundaries.add(phase);
        }
        result.put("phaseBoundaries", phaseBoundaries);
        result.put("timeSeries", seriesSnapshot());
        return result;
    }

    private synchronized void record(long timestamp, int kind, LoadEvent event) {
        Point point = pointAt(timestamp);
        if (point == null) return;
        point.counts[kind]++;
        if (kind == 1 || kind == 3) { point.schedulerLagTotalMs += event.schedulerLagMs(); point.schedulerLagCount++; }
        if (kind == 2) {
            if (event.status() == ResultStatus.PASS) point.counts[4]++;
            else if (event.status() == ResultStatus.ERROR || event.status() == ResultStatus.INVALID) point.counts[6]++;
            else point.counts[5]++;
            point.latencies.add(event.latencyMs());
        }
        if (kind == 1) point.concurrencyDelta++;
        if (kind == 2) point.concurrencyDelta--;
    }

    synchronized void recordSchedulerSample(long timestamp, int queueDepth) {
        Point point = pointAt(timestamp);
        if (point == null) return;
        point.queueDepthTotal += Math.max(0, queueDepth); point.queueSamples++;
        point.queueDepthPeak = Math.max(point.queueDepthPeak, Math.max(0, queueDepth));
        for (Window window : windows) window.recordQueueSample(timestamp, queueDepth);
    }

    synchronized void recordSubmitSample(long timestamp, long lagMs, int queueDepth) {
        Point point = pointAt(timestamp);
        if (point == null) return;
        point.submitLagTotalMs += Math.max(0L, lagMs); point.submitLagCount++;
        point.queueDepthTotal += Math.max(0, queueDepth); point.queueSamples++;
        point.queueDepthPeak = Math.max(point.queueDepthPeak, Math.max(0, queueDepth));
        for (Window window : windows) window.recordSubmitSample(timestamp, lagMs, queueDepth);
    }

    private Point pointAt(long timestamp) {
        if (timestamp < start) return null;
        long bin = Math.floorDiv(timestamp - start, bucketWidthMs);
        Point point = series.get(bin);
        if (point == null) { point = new Point(); series.put(bin, point); }
        while (series.size() > SERIES_CAPACITY) {
            TreeMap<Long, Point> rolled = new TreeMap<Long, Point>();
            for (Map.Entry<Long, Point> entry : series.entrySet()) {
                long key = Math.floorDiv(entry.getKey(), 2L);
                Point merged = rolled.get(key);
                if (merged == null) { merged = new Point(); rolled.put(key, merged); }
                merged.add(entry.getValue());
            }
            series.clear(); series.putAll(rolled);
            bucketWidthMs = Math.multiplyExact(bucketWidthMs, 2L);
        }
        long currentBin = Math.floorDiv(timestamp - start, bucketWidthMs);
        return series.get(currentBin);
    }

    private synchronized Map<String, Object> seriesSnapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("basis", "event-time; bins aligned to scheduler start; full-bin rate denominator");
        result.put("bucketDurationMs", bucketWidthMs);
        result.put("capacity", SERIES_CAPACITY);
        result.put("coverage", "Whole execution; uniform power-of-two roll-up, no historical eviction");
        List<Map<String, Object>> points = new ArrayList<Map<String, Object>>();
        String[] names = {"scheduled", "started", "completed", "dropped", "successful", "sutFailure", "runtimeError"};
        long inFlight = 0L;
        for (Map.Entry<Long, Point> entry : series.entrySet()) {
            Map<String, Object> point = new LinkedHashMap<String, Object>();
            point.put("windowStartEpochMs", start + entry.getKey() * bucketWidthMs);
            point.put("durationMs", bucketWidthMs);
            point.put("phaseCoverage", phaseCoverage(start + entry.getKey() * bucketWidthMs,
                    start + entry.getKey() * bucketWidthMs + bucketWidthMs));
            inFlight = Math.max(0L, inFlight + entry.getValue().concurrencyDelta);
            point.put("inFlightAtBinEnd", inFlight);
            point.put("observedInFlight", inFlight); // compatibility alias
            Map<String, Object> latency = entry.getValue().latencies.snapshot(true);
            point.put("latencySampleCount", latency.get("sampleCount"));
            point.put("latencyP50Ms", latency.get("p50Ms"));
            point.put("latencyP95Ms", latency.get("p95Ms"));
            point.put("latencyP99Ms", latency.get("p99Ms"));
            point.put("schedulerLagMeanMs", entry.getValue().schedulerLagCount == 0L ? null
                    : entry.getValue().schedulerLagTotalMs / (double) entry.getValue().schedulerLagCount);
            point.put("submitLagMeanMs", entry.getValue().submitLagCount == 0L ? null
                    : entry.getValue().submitLagTotalMs / (double) entry.getValue().submitLagCount);
            point.put("queueDepthMean", entry.getValue().queueSamples == 0L ? null
                    : entry.getValue().queueDepthTotal / (double) entry.getValue().queueSamples);
            point.put("queueDepthPeak", entry.getValue().queueSamples == 0L ? null : entry.getValue().queueDepthPeak);
            for (int i = 0; i < names.length; i++) {
                point.put(names[i], entry.getValue().counts[i]);
                point.put(names[i] + "Rate", entry.getValue().counts[i] * 1000.0 / bucketWidthMs);
            }
            point.put("sutFailureRate", entry.getValue().counts[2] == 0L ? 0.0 : entry.getValue().counts[5] / (double) entry.getValue().counts[2]);
            point.put("runtimeErrorRate", entry.getValue().counts[2] == 0L ? 0.0 : entry.getValue().counts[6] / (double) entry.getValue().counts[2]);
            point.put("droppedRate", entry.getValue().counts[0] == 0L ? 0.0 : entry.getValue().counts[3] / (double) entry.getValue().counts[0]);
            points.add(point);
        }
        result.put("points", points);
        return result;
    }

    private List<String> phaseCoverage(long from, long to) {
        List<String> result = new ArrayList<String>();
        for (Window window : windows) {
            if (!window.name.matches("WARMUP|RAMP_UP|STEADY|RAMP_DOWN|DRAIN")) continue;
            if (from < window.end && to > window.start) result.add(window.name);
        }
        return result;
    }

    private static final class Point {
        final long[] counts = new long[7];
        final Samples latencies = new Samples(128);
        long concurrencyDelta;
        long schedulerLagTotalMs, schedulerLagCount, submitLagTotalMs, submitLagCount;
        long queueDepthTotal, queueSamples, queueDepthPeak;
        void add(Point other) {
            for (int i = 0; i < counts.length; i++) counts[i] += other.counts[i];
            concurrencyDelta += other.concurrencyDelta;
            schedulerLagTotalMs += other.schedulerLagTotalMs; schedulerLagCount += other.schedulerLagCount;
            submitLagTotalMs += other.submitLagTotalMs; submitLagCount += other.submitLagCount;
            queueDepthTotal += other.queueDepthTotal; queueSamples += other.queueSamples;
            queueDepthPeak = Math.max(queueDepthPeak, other.queueDepthPeak);
            latencies.merge(other.latencies);
        }
    }

    private static final class Window {
        final String name;
        final long start, end;
        final LongAdder scheduled = new LongAdder(), started = new LongAdder(), completed = new LongAdder();
        final LongAdder successful = new LongAdder(), sutFailure = new LongAdder(), runtimeError = new LongAdder(), dropped = new LongAdder();
        final LongAdder cohortCompleted = new LongAdder(), cohortSuccess = new LongAdder();
        final LongAdder cohortSutFailure = new LongAdder(), cohortRuntimeError = new LongAdder();
        final LongAdder occupiedMs = new LongAdder(), lagMs = new LongAdder(), lagCount = new LongAdder();
        final LongAdder submitLagMs = new LongAdder(), submitLagCount = new LongAdder();
        final LongAdder queueDepthTotal = new LongAdder(), queueSamples = new LongAdder();
        long queueDepthPeak;
        final Map<String, Long> activeStarts = new java.util.HashMap<String, Long>();
        boolean intervalsSealed;
        final Samples latency = new Samples();
        Window(String name, long start, long end) { this.name = name; this.start = start; this.end = end; }
        boolean contains(long timestamp) { return timestamp >= start && timestamp < end; }
        synchronized void accept(LoadEvent event) {
            if (event.scheduled() && contains(event.scheduledAtEpochMs())) scheduled.increment();
            if (event.dropped() && contains(event.scheduledAtEpochMs())) dropped.increment();
            if (event.dropped() && contains(event.scheduledAtEpochMs())) { lagMs.add(event.schedulerLagMs()); lagCount.increment(); }
            String key = eventKey(event);
            if (event.started() && !intervalsSealed) activeStarts.put(key, event.startedAtEpochMs());
            if (event.started() && contains(event.startedAtEpochMs())) {
                started.increment(); lagMs.add(event.schedulerLagMs()); lagCount.increment();
            }
            if (!event.completed()) return;
            if (contains(event.completedAtEpochMs())) {
                completed.increment();
                if (event.success()) successful.increment();
                else if (runtime(event)) runtimeError.increment();
                else sutFailure.increment();
            }
            if (contains(event.scheduledAtEpochMs())) {
                cohortCompleted.increment(); latency.add(event.latencyMs());
                if (event.success()) cohortSuccess.increment();
                else if (runtime(event)) cohortRuntimeError.increment();
                else cohortSutFailure.increment();
            }
            Long observedStart = activeStarts.remove(key);
            long iterationStart = observedStart == null ? event.startedAtEpochMs() : observedStart;
            addOccupiedInterval(iterationStart, event.completedAtEpochMs());
        }
        synchronized void recordQueueSample(long timestamp, int depth) {
            if (!contains(timestamp)) return;
            queueDepthTotal.add(Math.max(0, depth)); queueSamples.increment();
            queueDepthPeak = Math.max(queueDepthPeak, Math.max(0, depth));
        }
        synchronized void recordSubmitSample(long timestamp, long lag, int depth) {
            if (!contains(timestamp)) return;
            submitLagMs.add(Math.max(0L, lag)); submitLagCount.increment();
            recordQueueSample(timestamp, depth);
        }
        synchronized void finish(long actualEnd) {
            if (intervalsSealed) return;
            for (long intervalStart : activeStarts.values()) addOccupiedInterval(intervalStart, actualEnd);
            activeStarts.clear(); intervalsSealed = true;
        }
        private void addOccupiedInterval(long intervalStart, long intervalEnd) {
            long overlap = Math.max(0L, Math.min(end, intervalEnd) - Math.max(start, intervalStart));
            if (overlap > 0L) occupiedMs.add(overlap);
        }
        private String eventKey(LoadEvent event) {
            return String.valueOf(event.runId()) + '\u0000' + String.valueOf(event.workloadId()) + '\u0000'
                    + String.valueOf(event.mixId()) + '\u0000' + String.valueOf(event.iterationId());
        }
        boolean runtime(LoadEvent event) { return event.status() == ResultStatus.ERROR || event.status() == ResultStatus.INVALID; }

        Map<String, Object> toMap(long actualEnd, boolean finished) {
            long effectiveEnd = end == Long.MAX_VALUE ? Math.max(start, actualEnd) : end;
            long duration = effectiveEnd - start;
            boolean valid = finished && duration > 0L && actualEnd >= effectiveEnd && scheduled.sum() + started.sum() + completed.sum() > 0L;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("windowStartEpochMs", start); result.put("windowEndEpochMs", effectiveEnd);
            result.put("durationMs", duration); result.put("phase", name);
            result.put("basis", "configured window; event-time rates; scheduled-cohort latency and outcomes");
            result.put("evaluated", valid);
            result.put("notEvaluatedReason", valid ? null : !finished ? "RUN_INCOMPLETE" : duration == 0L ? "ZERO_DURATION" : actualEnd < effectiveEnd ? "WINDOW_INCOMPLETE" : "NO_EVENTS");
            result.put("scheduled", scheduled.sum()); result.put("started", started.sum()); result.put("completed", completed.sum());
            result.put("successful", successful.sum()); result.put("sutFailure", sutFailure.sum());
            result.put("runtimeError", runtimeError.sum()); result.put("dropped", dropped.sum());
            result.put("scheduledRate", rate(scheduled.sum(), duration, valid));
            result.put("startedRate", rate(started.sum(), duration, valid));
            result.put("completedRate", rate(completed.sum(), duration, valid));
            result.put("successfulRate", rate(successful.sum(), duration, valid));
            result.put("admissionRate", ratio(started.sum(), scheduled.sum(), valid));
            result.put("droppedRate", ratio(dropped.sum(), scheduled.sum(), valid));
            result.put("schedulerLagMeanMs", ratio(lagMs.sum(), lagCount.sum(), valid));
            result.put("submitLagMeanMs", ratio(submitLagMs.sum(), submitLagCount.sum(), valid));
            result.put("workerQueueDepthMean", ratio(queueDepthTotal.sum(), queueSamples.sum(), valid));
            result.put("workerQueueDepthPeak", queueSamples.sum() == 0L ? null : queueDepthPeak);
            result.put("meanInFlight", valid ? occupiedMs.sum() / (double) duration : null);
            result.put("concurrencyBasis", "sum of interval overlap for completed and still-active iterations / configured window duration");
            Map<String, Object> cohort = new LinkedHashMap<String, Object>();
            cohort.put("basis", "scheduled timestamp; eventual outcomes include later phases and drain");
            cohort.put("completed", cohortCompleted.sum()); cohort.put("successful", cohortSuccess.sum());
            cohort.put("sutFailure", cohortSutFailure.sum()); cohort.put("runtimeError", cohortRuntimeError.sum());
            cohort.put("completedRate", rate(cohortCompleted.sum(), duration, valid));
            cohort.put("successfulRate", rate(cohortSuccess.sum(), duration, valid));
            cohort.put("sutErrorRate", ratio(cohortSutFailure.sum(), cohortCompleted.sum(), valid));
            cohort.put("runtimeErrorRate", ratio(cohortRuntimeError.sum(), cohortCompleted.sum(), valid));
            cohort.put("errorRate", ratio(cohortSutFailure.sum() + cohortRuntimeError.sum(), cohortCompleted.sum(), valid));
            cohort.put("latency", latency.snapshot(valid));
            result.put("cohort", cohort);
            return result;
        }
        private Object rate(long count, long duration, boolean valid) { return valid ? count * 1000.0 / duration : null; }
        private Object ratio(long count, long denominator, boolean valid) { return valid && denominator > 0L ? count / (double) denominator : null; }
    }

    /** Deterministic Algorithm R; fixed storage, distinct atomic observation IDs. */
    private static final class Samples {
        final AtomicLong count = new AtomicLong();
        final int capacity;
        final AtomicLongArray values;
        Samples() { this(SAMPLE_CAPACITY); }
        Samples(int capacity) { this.capacity = capacity; this.values = new AtomicLongArray(capacity); }
        void add(long value) {
            long seen = count.incrementAndGet();
            long slot = seen - 1;
            if (seen > capacity) {
                long mixed = seen + 0x9E3779B97F4A7C15L;
                mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
                mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
                slot = Math.floorMod(mixed ^ (mixed >>> 31), seen);
            }
            if (slot < capacity) values.set((int) slot, Math.max(0L, value));
        }
        Map<String, Object> snapshot(boolean valid) {
            long observations = count.get();
            int size = (int) Math.min(capacity, observations);
            long[] sorted = new long[size];
            for (int i = 0; i < size; i++) sorted[i] = values.get(i);
            Arrays.sort(sorted);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("basis", "iteration elapsed time, including framework/helper waiting; scheduled cohort");
            result.put("sampleCount", size); result.put("observationCount", observations);
            result.put("sampleRate", observations == 0L ? 0.0 : size / (double) observations);
            result.put("windowSize", capacity);
            result.put("approximation", "bounded uniform reservoir; nearest-rank percentiles");
            result.put("confidence", size < 100 ? "SPARSE" : "SAMPLED");
            int[] percentiles = {50, 95, 99};
            for (int p : percentiles) result.put("p" + p + "Ms", valid && size > 0 ? sorted[(int) Math.ceil(size * p / 100.0) - 1] : null);
            return result;
        }
        long[] snapshotValues() {
            int size = (int) Math.min(capacity, count.get());
            long[] result = new long[size]; for (int i = 0; i < size; i++) result[i] = values.get(i); return result;
        }
        void merge(Samples other) {
            long total = count.get() + other.count.get();
            long[] left = snapshotValues(), right = other.snapshotValues();
            long[] combined = new long[left.length + right.length];
            System.arraycopy(left, 0, combined, 0, left.length);
            System.arraycopy(right, 0, combined, left.length, right.length);
            int retained = Math.min(capacity, combined.length);
            for (int i = 0; i < retained; i++) values.set(i, combined[(int) (((long) i * combined.length) / retained)]);
            count.set(total);
        }
    }
}
