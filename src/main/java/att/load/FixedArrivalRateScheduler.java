package att.load;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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
    private final LoadEvidenceStore evidenceStore;
    private final Path evidenceOutputRoot;
    private final Runnable beforeSubmitHook;
    private final LoadSchedulerStartGate startGate;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Object admissionLock = new Object();
    private final ConcurrentHashMap<String, TaskHandle> admittedTasks = new ConcurrentHashMap<String, TaskHandle>();
    private volatile ThreadPoolExecutor workers;
    private volatile boolean ownsWorkers = true;
    private volatile Thread controlThread;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object completionMonitor = new Object();
    private volatile BlockingQueue<Boolean> schedulerWakeups;

    public FixedArrivalRateScheduler(LoadScenario scenario, IterationExecutor executor, String runId, Consumer<LoadEvent> listener) {
        this(scenario, executor, runId, listener, LoadSchedulerTiming.system());
    }
    public FixedArrivalRateScheduler(LoadScenario scenario, IterationExecutor executor, String runId, LoadEventListener listener) { this(scenario, executor, runId, adapt(listener)); }
    public FixedArrivalRateScheduler(LoadScenario scenario, IterationExecutor executor, String runId,
                                     LoadEvidenceStore evidenceStore, Path outputRoot) {
        this(scenario, executor, runId, adapt(evidenceStore), LoadSchedulerTiming.system(), null, evidenceStore, outputRoot, null);
    }
    public FixedArrivalRateScheduler(LoadScenario scenario, IterationExecutor executor, String runId,
                                     LoadEvidenceStore evidenceStore) {
        this(scenario, executor, runId, adapt(evidenceStore), LoadSchedulerTiming.system(), null, evidenceStore,
                executor == null ? null : executor.outputRoot(), null);
    }
    FixedArrivalRateScheduler(LoadScenario scenario, LoadIterationRunner executor, String runId,
                              Consumer<LoadEvent> listener, LoadSchedulerTiming timing) {
        this(scenario, executor, runId, listener, timing, null, null, null, null);
    }
    FixedArrivalRateScheduler(LoadScenario scenario, LoadIterationRunner executor, String runId,
                              Consumer<LoadEvent> listener, LoadSchedulerTiming timing, Runnable beforeSubmitHook) {
        this(scenario, executor, runId, listener, timing, beforeSubmitHook, null, null, null);
    }
    FixedArrivalRateScheduler(LoadScenario scenario, LoadIterationRunner executor, String runId,
                              Consumer<LoadEvent> listener, LoadSchedulerTiming timing, Runnable beforeSubmitHook,
                              LoadEvidenceStore evidenceStore, Path evidenceOutputRoot, LoadSchedulerStartGate startGate) {
        if (scenario == null || scenario.model() != LoadScenario.Model.ARRIVAL_RATE) throw new IllegalArgumentException("FixedArrivalRateScheduler requires an arrivalRate scenario");
        if (executor == null) throw new IllegalArgumentException("FixedArrivalRateScheduler requires an iteration executor");
        this.scenario = scenario;
        this.executor = executor;
        this.runId = LoadSchedulerSupport.runId(runId);
        this.listener = listener;
        this.timing = timing == null ? LoadSchedulerTiming.system() : timing;
        this.beforeSubmitHook = beforeSubmitHook;
        this.evidenceStore = evidenceStore;
        this.evidenceOutputRoot = evidenceOutputRoot == null ? null : evidenceOutputRoot.toAbsolutePath().normalize();
        this.startGate = startGate;
    }
    private static Consumer<LoadEvent> adapt(final LoadEventListener listener) { return listener == null ? null : new Consumer<LoadEvent>() { @Override public void accept(LoadEvent event) { listener.onEvent(event); } }; }

    void useSharedWorkers(ThreadPoolExecutor sharedWorkers) {
        if (sharedWorkers == null) throw new IllegalArgumentException("Shared Load worker executor is required");
        if (workers != null) throw new IllegalStateException("Load worker executor is already initialized");
        workers = sharedWorkers;
        ownsWorkers = false;
    }

    @Override public LoadRunResult run() throws Exception {
        if (scenario.coordinatorRequired()) {
            if (!(executor instanceof IterationExecutor)) throw new IllegalArgumentException("Multi-workload arrival scheduling requires IterationExecutor");
            return LoadRunCoordinator.runFrom(scenario, (IterationExecutor) executor, runId, evidenceStore, evidenceOutputRoot);
        }
        if (executor instanceof IterationExecutor) ((IterationExecutor) executor).initializeOutputNamespace(runId);
        long startedAt = startGate == null ? timing.now() : startGate.awaitStart();
        Instant start = LoadSchedulerSupport.instant(startedAt);
        final LoadMetrics metrics = LoadMetrics.forScenario(scenario, startedAt, LoadPhase.totalMs(scenario));
        if (workers == null) {
            workers = new ThreadPoolExecutor(scenario.maxConcurrent(), scenario.maxConcurrent(), 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(scenario.maxConcurrent()),
                    new NamedFactory("att-load-arrival-" + safe(scenario.workloadId())));
            ownsWorkers = true;
        }
        controlThread = Thread.currentThread();
        schedulerWakeups = new ArrayBlockingQueue<Boolean>(1);
        long scheduledCount = 0L;
        long admittedTestdataOrdinal = 0L;
        try {
            while (!cancelled.get()) {
                metrics.recordSchedulerWakeup(workers.getQueue().size());
                long elapsed = timing.now() - startedAt;
                long total = LoadPhase.totalMs(scenario);
                long desired = elapsed >= total ? arrivalsBeforeDeadline(scenario) : arrivalsDueAt(scenario, elapsed);
                while (scheduledCount < desired) {
                    long plannedSequence = ++scheduledCount; long dueAt = plannedDue(scenario, startedAt, plannedSequence);
                    String phase = LoadPhase.at(scenario, Math.max(0L, dueAt - startedAt)).name();
                    String prefix = scenario.legacyV10() ? runId : runId + "-" + safe(scenario.workloadId());
                    String iterationId = prefix + "-arrival-" + plannedSequence;
                    if (inFlight.get() >= scenario.maxConcurrent()) {
                        LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.dropped(runId, "arrivalRate", phase, iterationId,
                                plannedSequence, dueAt, timing.now())));
                    } else submit(metrics, phase, iterationId, plannedSequence, admittedTestdataOrdinal++, dueAt, startedAt);
                }
                if (elapsed >= total) break;
                long nextDue = plannedDue(scenario, startedAt, scheduledCount + 1L);
                long wait = Math.min(total - elapsed, nextDue - timing.now());
                // Floating-point phase boundaries can put the inverse one millisecond behind
                // the integer arrival count. Make progress to the next clock tick in that case.
                try { timing.await(schedulerWakeups, Math.max(1L, wait)); }
                catch (InterruptedException interrupted) {
                    if (cancelled.get()) break;
                    throw interrupted;
                }
            }
        } finally {
            try { waitForWorkers(); }
            finally { shutdown(); controlThread = null; }
        }
        long endedAt = timing.now(); metrics.finish(endedAt);
        return new LoadRunResult(runId, scenario, start, LoadSchedulerSupport.instant(endedAt), metrics.snapshot());
    }

    private void submit(LoadMetrics metrics, String phase, String id, long sequenceValue,
                        long testdataOrdinal, long dueAt, long runStartedAt) {
        inFlight.incrementAndGet();
        try {
            if (beforeSubmitHook != null) beforeSubmitHook.run();
            metrics.recordWorkerQueueDepth(workers.getQueue().size());
            final TaskHandle taskHandle = new TaskHandle();
            FutureTask<Void> task = new FutureTask<Void>(() -> {
                boolean cancelledBeforeStart = taskHandle.begin();
                long iterationStarted = timing.now();
                metrics.recordSubmitLag(iterationStarted - dueAt, workers.getQueue().size());
                att.core.ResultStatus status = att.core.ResultStatus.ERROR;
                String errorType = "RUNTIME_ERROR";
                EvidenceRef evidence = null;
                try {
                    LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.started(runId, "arrivalRate", phase, id, null,
                            sequenceValue, dueAt, iterationStarted)));
                    if (cancelledBeforeStart) {
                        errorType = "CANCELLED";
                    } else {
                        IterationRequest request = new IterationRequest(runId, LoadSchedulerSupport.instant(runStartedAt), "arrivalRate", id,
                                sequenceValue, phase, LoadSchedulerSupport.instant(iterationStarted), null, scenario.inputs(), null);
                        request = request.withTestdataOrdinal(testdataOrdinal)
                                .withTestdataWaitAllowed(() -> !cancelled.get() && remainingRunMillis(runStartedAt) > 0L);
                        if (!scenario.legacyV10()) request = request.withWorkloadId(scenario.workloadId());
                        Path evidenceRoot = evidenceOutputRoot(id);
                        if (evidenceRoot != null) {
                            request = request.withOutputDirectory(evidenceRoot)
                                    .withEvidenceRetention(true, false);
                        } else if (evidenceStore != null) {
                            request = request.withEvidenceRetention(false, false);
                        }
                        if (evidenceStore != null)
                            request = request.withFailureLogCapture(evidenceStore.retainsFailureEvidence());
                        IterationResult result = executor.execute(request);
                        if (result.status() != att.core.ResultStatus.PASS && evidenceStore != null && evidenceStore.claimFailureEvidence(id)) {
                            result = result.materializeEvidence();
                        } else if (result.status() != att.core.ResultStatus.PASS && evidenceStore != null) {
                            evidenceStore.releaseEvidence(id);
                        }
                        if (result.evidenceRef() == null) result.discardTransientWorkspace();
                        if (result.testdataStopRequested()) cancelled.set(true);
                        status = result.status(); errorType = LoadSchedulerSupport.errorType(result); evidence = result.evidenceRef();
                    }
                } catch (RuntimeException error) {
                    if (evidenceStore != null && !evidenceStore.claimFailureEvidence(id)) evidenceStore.releaseEvidence(id);
                    status = att.core.ResultStatus.ERROR; errorType = "RUNTIME_ERROR";
                }
                finally {
                    long completedAt = timing.now();
                    try {
                        LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.completion(runId, "arrivalRate", phase, id, null,
                                sequenceValue, dueAt, iterationStarted, completedAt, status, errorType, evidence)));
                    } finally {
                        inFlight.decrementAndGet();
                        admittedTasks.remove(id, taskHandle);
                        schedulerWakeups.offer(Boolean.TRUE);
                        synchronized (completionMonitor) { completionMonitor.notifyAll(); }
                    }
                }
                return null;
            });
            taskHandle.future = task;
            synchronized (admissionLock) {
                if (cancelled.get()) {
                    inFlight.decrementAndGet();
                    signalWorkerChange();
                    return;
                }
                admittedTasks.put(id, taskHandle);
                try { workers.execute(task); }
                catch (RejectedExecutionException rejected) {
                    admittedTasks.remove(id, taskHandle);
                    inFlight.decrementAndGet();
                    signalWorkerChange();
                }
            }
        } catch (RejectedExecutionException rejected) {
            inFlight.decrementAndGet();
            signalWorkerChange();
        }
    }

    private void signalWorkerChange() {
        BlockingQueue<Boolean> wakeups = schedulerWakeups;
        if (wakeups != null) wakeups.offer(Boolean.TRUE);
        synchronized (completionMonitor) { completionMonitor.notifyAll(); }
    }

    private LoadEvent tag(LoadEvent event) {
        return scenario.legacyV10() ? event : event.withWorkloadIdentity(
                scenario.workloadId(), scenario.targetType(), scenario.targetId());
    }
    private Path evidenceOutputRoot(String iterationId) {
        if (evidenceStore == null || evidenceOutputRoot == null || !evidenceStore.reserveSuccessEvidence(iterationId)) return null;
        Path root = evidenceOutputRoot.resolve("load").resolve(runId).resolve("iterations");
        return scenario.legacyV10() ? root : root.resolve(safe(scenario.workloadId()));
    }
    private long remainingRunMillis(long startedAt) {
        return LoadPhase.totalMs(scenario) - (timing.now() - startedAt);
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
        double target = sequenceValue - 1.0;
        double rate = scenario.arrivalRatePerSecond();
        long cursor = 0L;
        long warmup = scenario.warmup().toMillis();
        long rampUp = scenario.rampUp().toMillis();
        long steady = scenario.duration().toMillis();
        long rampDown = scenario.rampDown().toMillis();
        double area = rate * warmup / 1000.0;
        if (target <= area) return safeDue(start, inverseLinear(target, rate));
        target -= area; cursor += warmup;
        area = rate * rampUp / 2000.0;
        if (target <= area && rampUp > 0L)
            return safeDue(start, cursor + inverseRampUp(target, rate, rampUp));
        target -= area; cursor += rampUp;
        area = rate * steady / 1000.0;
        if (target <= area) return safeDue(start, cursor + inverseLinear(target, rate));
        target -= area; cursor += steady;
        return safeDue(start, cursor + inverseRampDown(target, rate, rampDown));
    }
    private static long inverseLinear(double arrivals, double rate) {
        if (arrivals <= 0.0 || rate <= 0.0) return 0L;
        return ceilMillis(arrivals * 1000.0 / rate);
    }
    private static long inverseRampUp(double arrivals, double rate, long duration) {
        if (arrivals <= 0.0 || rate <= 0.0 || duration <= 0L) return 0L;
        return ceilMillis(Math.sqrt(arrivals * 2000.0 * duration / rate));
    }
    private static long inverseRampDown(double arrivals, double rate, long duration) {
        if (arrivals <= 0.0 || rate <= 0.0 || duration <= 0L) return 0L;
        double discriminant = Math.max(0.0, (double) duration * duration - arrivals * 2000.0 * duration / rate);
        return ceilMillis(duration - Math.sqrt(discriminant));
    }
    private static long ceilMillis(double millis) {
        if (Double.isNaN(millis) || millis <= 0.0) return 0L;
        if (millis >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.ceil(millis - 1.0e-9);
    }
    private static long safeDue(long start, long offset) {
        try { return Math.addExact(start, offset); } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    private void waitForWorkers() throws InterruptedException {
        ExecutorService value = workers;
        if (value != null && ownsWorkers) {
            value.shutdown();
            try { if (!value.awaitTermination(30L, TimeUnit.SECONDS)) value.shutdownNow(); }
            catch (InterruptedException interrupted) {
                if (!cancelled.get()) throw interrupted;
                Thread.interrupted();
            }
        }
        synchronized (completionMonitor) {
            while (inFlight.get() > 0) {
                try { completionMonitor.wait(); }
                catch (InterruptedException interrupted) {
                    if (!cancelled.get()) throw interrupted;
                }
            }
        }
    }
    @Override public void cancel() {
        cancelled.set(true);
        Thread control = controlThread;
        if (control != null) control.interrupt();
        synchronized (admissionLock) {
            for (TaskHandle task : admittedTasks.values()) task.cancel();
        }
        if (ownsWorkers) shutdown();
        synchronized (completionMonitor) { completionMonitor.notifyAll(); }
    }
    @Override public void close() { cancel(); }
    private void shutdown() {
        ExecutorService value = workers;
        if (!ownsWorkers) return;
        if (value != null) {
            value.shutdownNow();
            try { value.awaitTermination(1L, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    private static String safe(String value) { return value == null ? "default" : value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private static final class TaskHandle {
        private FutureTask<Void> future;
        private boolean started;
        private boolean cancelledBeforeStart;
        synchronized boolean begin() { started = true; return cancelledBeforeStart; }
        synchronized void cancel() {
            if (started) future.cancel(true);
            else cancelledBeforeStart = true;
        }
    }
    private static final class NamedFactory implements ThreadFactory {
        private final String prefix; private final AtomicLong index = new AtomicLong();
        NamedFactory(String prefix) { this.prefix = prefix; }
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + "-" + index.incrementAndGet()); t.setDaemon(true); return t; }
    }
}
