package att.load;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Fixed-rate open workload scheduler using absolute planned arrival counts. */
public final class FixedArrivalRateScheduler implements LoadScheduler {
    private final LoadScenario scenario;
    private final LoadIterationRunner executor;
    private final String runId;
    private final Consumer<LoadEvent> listener;
    private final LoadSchedulerTiming timing;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile ExecutorService workers;
    private final AtomicInteger inFlight = new AtomicInteger();

    public FixedArrivalRateScheduler(LoadScenario scenario, IterationExecutor executor, String runId, Consumer<LoadEvent> listener) {
        this(scenario, executor, runId, listener, LoadSchedulerTiming.system());
    }
    public FixedArrivalRateScheduler(LoadScenario scenario, IterationExecutor executor, String runId, LoadEventListener listener) { this(scenario, executor, runId, adapt(listener)); }
    FixedArrivalRateScheduler(LoadScenario scenario, LoadIterationRunner executor, String runId,
                              Consumer<LoadEvent> listener, LoadSchedulerTiming timing) {
        if (scenario == null || scenario.model() != LoadScenario.Model.ARRIVAL_RATE) throw new IllegalArgumentException("FixedArrivalRateScheduler requires an arrivalRate scenario");
        if (executor == null) throw new IllegalArgumentException("FixedArrivalRateScheduler requires an iteration executor");
        this.scenario = scenario;
        this.executor = executor;
        this.runId = LoadSchedulerSupport.runId(runId);
        this.listener = listener;
        this.timing = timing == null ? LoadSchedulerTiming.system() : timing;
    }
    private static Consumer<LoadEvent> adapt(final LoadEventListener listener) { return listener == null ? null : new Consumer<LoadEvent>() { @Override public void accept(LoadEvent event) { listener.onEvent(event); } }; }

    @Override public LoadRunResult run() throws Exception {
        long startedAt = timing.now(); Instant start = LoadSchedulerSupport.instant(startedAt);
        final LoadMetrics metrics = new LoadMetrics(scenario.model().wireName(), startedAt, LoadPhase.totalMs(scenario));
        workers = Executors.newFixedThreadPool(scenario.maxConcurrent(), new NamedFactory("att-load-arrival"));
        long scheduledCount = 0L;
        try {
            while (!cancelled.get()) {
                long elapsed = timing.now() - startedAt;
                long total = LoadPhase.totalMs(scenario);
                long desired = elapsed >= total ? arrivalsBeforeDeadline(scenario) : arrivalsDueAt(scenario, elapsed);
                while (scheduledCount < desired) {
                    long plannedSequence = ++scheduledCount; long dueAt = plannedDue(scenario, startedAt, plannedSequence);
                    String phase = LoadPhase.at(scenario, Math.max(0L, dueAt - startedAt)).name();
                    String iterationId = runId + "-arrival-" + plannedSequence;
                    if (inFlight.get() >= scenario.maxConcurrent()) {
                        LoadSchedulerSupport.emit(metrics, listener, LoadEvent.dropped(runId, "arrivalRate", phase, iterationId,
                                plannedSequence, dueAt, timing.now()));
                    } else submit(metrics, phase, iterationId, plannedSequence, dueAt, startedAt);
                }
                if (elapsed >= total) break;
                timing.sleep(1L);
            }
        } finally { waitForWorkers(); shutdown(); }
        long endedAt = timing.now(); metrics.finish(endedAt);
        return new LoadRunResult(runId, scenario, start, LoadSchedulerSupport.instant(endedAt), metrics.snapshot());
    }

    private void submit(LoadMetrics metrics, String phase, String id, long sequenceValue, long dueAt, long runStartedAt) {
        inFlight.incrementAndGet();
        workers.submit(() -> {
            long iterationStarted = timing.now(); att.core.ResultStatus status; EvidenceRef evidence = null;
            try {
                IterationRequest request = new IterationRequest(runId, LoadSchedulerSupport.instant(runStartedAt), "arrivalRate", id,
                        sequenceValue, phase, LoadSchedulerSupport.instant(iterationStarted), null, scenario.inputs(), null);
                IterationResult result = executor.execute(request);
                status = result.status(); evidence = result.evidenceRef();
            } catch (RuntimeException error) { status = att.core.ResultStatus.ERROR; }
            long completedAt = timing.now(); inFlight.decrementAndGet();
            LoadSchedulerSupport.emit(metrics, listener, LoadEvent.completed(runId, "arrivalRate", phase, id, null,
                    sequenceValue, dueAt, iterationStarted, completedAt, status, evidence));
        });
    }
    static long arrivalsDueAt(LoadScenario scenario, long elapsedMs) {
        if (elapsedMs < 0L) return 0L;
        double cumulative = cumulativeArrivals(scenario, elapsedMs);
        if (Double.isInfinite(cumulative) || cumulative >= Long.MAX_VALUE - 1.0) return Long.MAX_VALUE;
        return (long) Math.floor(Math.max(0.0, cumulative)) + 1L;
    }

    static long arrivalsBeforeDeadline(LoadScenario scenario) {
        double cumulative = cumulativeArrivals(scenario, LoadPhase.totalMs(scenario));
        if (Double.isInfinite(cumulative) || cumulative >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.ceil(Math.max(0.0, cumulative));
    }

    static double cumulativeArrivals(LoadScenario scenario, long elapsedMs) {
        long total = LoadPhase.totalMs(scenario); long step = Math.max(0L, Math.min(total, elapsedMs));
        double rate = scenario.arrivalRatePerSecond(); double result = 0.0; long cursor = 0L;
        long warmup = scenario.warmup().toMillis(); long rampUp = scenario.rampUp().toMillis(); long steady = scenario.duration().toMillis(); long rampDown = scenario.rampDown().toMillis();
        long warmupElapsed = Math.min(step, warmup); result += rate * warmupElapsed / 1000.0; cursor += warmup; if (step <= cursor) return result;
        long upElapsed = Math.min(step - cursor, rampUp); result += rampArea(rate, rampUp, upElapsed, true); cursor += rampUp; if (step <= cursor) return result;
        long steadyElapsed = Math.min(step - cursor, steady); result += rate * steadyElapsed / 1000.0; cursor += steady; if (step <= cursor) return result;
        long downElapsed = Math.min(step - cursor, rampDown); result += rampArea(rate, rampDown, downElapsed, false); return result;
    }
    private static double rampArea(double rate, long duration, long elapsed, boolean up) { if (duration <= 0 || elapsed <= 0) return 0.0; double fraction = Math.min(1.0, ((double) elapsed) / duration); return rate * elapsed / 1000.0 * (up ? fraction / 2.0 : 1.0 - fraction / 2.0); }
    static long plannedDue(LoadScenario scenario, long start, long sequenceValue) {
        if (sequenceValue < 1L) throw new IllegalArgumentException("Arrival sequence must be >= 1");
        double target = sequenceValue - 1.0; long low = 0L, high = LoadPhase.totalMs(scenario);
        while (low < high) { long middle = low + (high - low) / 2L; if (cumulativeArrivals(scenario, middle) >= target) high = middle; else low = middle + 1L; }
        return start + low;
    }
    private void waitForWorkers() throws InterruptedException {
        ExecutorService value = workers;
        if (value != null) {
            value.shutdown();
            if (!value.awaitTermination(30L, TimeUnit.SECONDS)) value.shutdownNow();
        }
    }
    @Override public void cancel() { cancelled.set(true); shutdown(); }
    @Override public void close() { cancel(); }
    private void shutdown() {
        ExecutorService value = workers;
        if (value != null) {
            value.shutdownNow();
            try { value.awaitTermination(1L, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    private static final class NamedFactory implements ThreadFactory { private final String prefix; private final AtomicLong index = new AtomicLong(); NamedFactory(String prefix) { this.prefix = prefix; } @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + "-" + index.incrementAndGet()); t.setDaemon(true); return t; } }
}
