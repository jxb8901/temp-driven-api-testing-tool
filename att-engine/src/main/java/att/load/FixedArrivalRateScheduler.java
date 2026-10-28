package att.load;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
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

/** Fixed-rate open workload scheduler; the generation window is half-open, [start, deadline). */
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
    private volatile LoadMetrics aggregateSchedulerMetrics;

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
        if (executor instanceof IterationExecutor) {
            IterationExecutor loadExecutor = (IterationExecutor) executor;
            loadExecutor.initializeOutputNamespace(runId);
            loadExecutor.prepareResources(runId);
            loadExecutor.markSchedulerStart(runId, LoadPhase.at(scenario, 0L).name(), scenario.model().wireName());
        }
        long startedAt = startGate == null ? timing.now() : startGate.awaitStart();
        Instant start = LoadSchedulerSupport.instant(startedAt);
        long total = LoadPhase.totalMs(scenario);
        long generationDeadline = safeDue(startedAt, total);
        final LoadMetrics metrics = LoadMetrics.forScenario(scenario, startedAt, total);
        enableCheckpoints(metrics);
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
                long nowAtWake = timing.now();
                int queueDepth = workers.getQueue().size();
                metrics.recordSchedulerWakeupAt(nowAtWake, queueDepth);
                LoadMetrics aggregate = aggregateSchedulerMetrics;
                if (aggregate != null) aggregate.recordSchedulerWakeupAt(nowAtWake, queueDepth);
                long elapsed = nowAtWake - startedAt;
                long desired = elapsed >= total ? arrivalsBeforeDeadline(scenario) : arrivalsDueAt(scenario, elapsed);
                while (scheduledCount < desired) {
                    long admissionNow = timing.now();
                    if (admissionNow >= generationDeadline) {
                        scheduledCount = rejectExpiredArrivals(metrics, scheduledCount,
                                arrivalsBeforeDeadline(scenario), startedAt, total, admissionNow);
                        break;
                    }
                    long plannedSequence = scheduledCount + 1L;
                    long dueOffset = plannedDueOffset(scenario, plannedSequence);
                    if (dueOffset >= total) break; // Generation is the half-open interval [start, end).
                    LoadPhase arrivalPhase = LoadPhase.at(scenario, dueOffset);
                    if (arrivalPhase == LoadPhase.COMPLETE) break; // COMPLETE is control state, never an iteration phase.
                    long dueAt = safeDue(startedAt, dueOffset);
                    String phase = arrivalPhase.name();
                    scheduledCount = plannedSequence;
                    String prefix = scenario.legacyV10() ? runId : runId + "-" + safe(scenario.workloadId());
                    String iterationId = prefix + "-arrival-" + plannedSequence;
                    if (inFlight.get() >= scenario.maxConcurrent()) {
                        LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.dropped(runId, "arrivalRate", phase, iterationId,
                                plannedSequence, dueAt, timing.now())));
                    } else if (submit(metrics, phase, iterationId, plannedSequence, admittedTestdataOrdinal, dueAt,
                            startedAt, generationDeadline)) admittedTestdataOrdinal++;
                }
                long nowAfterScheduling = timing.now();
                if (nowAfterScheduling >= generationDeadline) {
                    scheduledCount = rejectExpiredArrivals(metrics, scheduledCount,
                            arrivalsBeforeDeadline(scenario), startedAt, total, nowAfterScheduling);
                    break;
                }
                long elapsedAfterScheduling = nowAfterScheduling - startedAt;
                if (elapsedAfterScheduling >= total) break;
                long nextDue = plannedDue(scenario, startedAt, scheduledCount + 1L);
                long wait = Math.min(total - elapsedAfterScheduling, nextDue - nowAfterScheduling);
                // Floating-point phase boundaries can put the inverse one millisecond behind
                // the integer arrival count. Make progress to the next clock tick in that case.
                try { timing.await(schedulerWakeups, Math.max(1L, wait)); }
                catch (InterruptedException interrupted) {
                    if (!cancelled.get()) cancel();
                    Thread.interrupted();
                    break;
                }
            }
        } finally {
            try { waitForWorkers(); }
            finally { shutdown(); controlThread = null; }
        }
        long endedAt = timing.now(); metrics.finish(endedAt);
        LoadRunResult result = new LoadRunResult(runId, scenario, start, LoadSchedulerSupport.instant(endedAt), metrics.snapshot());
        return cancelled.get() ? result.withCancelled() : result;
    }

    private boolean submit(LoadMetrics metrics, String phase, String id, long sequenceValue,
                           long testdataOrdinal, long dueAt, long runStartedAt, long generationDeadline) {
        inFlight.incrementAndGet();
        try {
            if (beforeSubmitHook != null) beforeSubmitHook.run();
            metrics.recordWorkerQueueDepth(workers.getQueue().size());
            final TaskHandle taskHandle = new TaskHandle();
            FutureTask<Void> task = new FutureTask<Void>(() -> {
                boolean cancelledBeforeStart = taskHandle.begin();
                long iterationStarted = timing.now();
                int queueDepth = workers.getQueue().size();
                metrics.recordSubmitLagAt(iterationStarted, iterationStarted - dueAt, queueDepth);
                LoadMetrics aggregate = aggregateSchedulerMetrics;
                if (aggregate != null) aggregate.recordSubmitLagAt(iterationStarted, iterationStarted - dueAt, queueDepth);
                att.core.ResultStatus status = att.core.ResultStatus.ERROR;
                String errorType = "RUNTIME_ERROR";
                EvidenceRef evidence = null;
                java.util.Map<String,Object> failureDiagnostic=java.util.Collections.emptyMap();
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
                            request = request.withFailureLogCapture(
                                    evidenceStore.retainsFailureEvidence(scenario.workloadId()));
                        IterationResult result = executor.execute(request);
                        if (result.status() != att.core.ResultStatus.PASS && evidenceStore != null && evidenceStore.claimFailureEvidence(id, scenario.workloadId())) {
                            result = result.materializeEvidence();
                        } else if (result.status() != att.core.ResultStatus.PASS && evidenceStore != null) {
                            evidenceStore.releaseEvidence(id);
                        }
                        if (result.evidenceRef() == null) result.discardTransientWorkspace();
                        if (result.testdataStopRequested()) cancelled.set(true);
                        status = result.status(); errorType = LoadSchedulerSupport.errorType(result); evidence = result.evidenceRef();
                        if(status!=att.core.ResultStatus.PASS)failureDiagnostic=LoadFailureDiagnostic.result(result);
                    }
                } catch (RuntimeException error) {
                    if (evidenceStore != null && !evidenceStore.claimFailureEvidence(id, scenario.workloadId())) evidenceStore.releaseEvidence(id);
                    status = att.core.ResultStatus.ERROR; errorType = "LOAD_ITERATION_EXCEPTION";failureDiagnostic=LoadFailureDiagnostic.exception(error);
                }
                finally {
                    long completedAt = timing.now();
                    try {
                        LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.completion(runId, "arrivalRate", phase, id, null,
                                sequenceValue, dueAt, iterationStarted, completedAt, status, errorType, evidence).withFailureDiagnostic(failureDiagnostic)));
                    } finally {
                        if(evidenceStore!=null)evidenceStore.releaseEvidence(id);
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
                    LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.rejected(runId, "arrivalRate", phase, id,
                            sequenceValue, dueAt, timing.now(), "CANCELLED_BEFORE_ADMISSION")));
                    signalWorkerChange();
                    return false;
                }
                admittedTasks.put(id, taskHandle);
                long admissionNow = timing.now();
                if (admissionNow >= generationDeadline) {
                    admittedTasks.remove(id, taskHandle);
                    inFlight.decrementAndGet();
                    LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.rejected(runId, "arrivalRate", phase, id,
                            sequenceValue, dueAt, admissionNow, "GENERATION_DEADLINE_EXPIRED")));
                    signalWorkerChange();
                    return false;
                }
                try { workers.execute(task); }
                catch (RejectedExecutionException rejected) {
                    admittedTasks.remove(id, taskHandle);
                    inFlight.decrementAndGet();
                    LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.rejected(runId, "arrivalRate", phase, id,
                            sequenceValue, dueAt, timing.now(), "SUBMISSION_REJECTED")));
                    signalWorkerChange();
                    return false;
                }
                return true;
            }
        } catch (RejectedExecutionException rejected) {
            inFlight.decrementAndGet();
            LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.rejected(runId, "arrivalRate", phase, id,
                    sequenceValue, dueAt, timing.now(), "SUBMISSION_REJECTED")));
            signalWorkerChange();
            return false;
        }
    }

    private long rejectExpiredArrivals(LoadMetrics metrics, long scheduledCount, long plannedArrivals,
                                      long startedAt, long total, long observedAt) {
        while (scheduledCount < plannedArrivals) {
            long sequence = scheduledCount + 1L;
            long dueOffset = plannedDueOffset(scenario, sequence);
            if (dueOffset >= total) break;
            LoadPhase phase = LoadPhase.at(scenario, dueOffset);
            if (phase == LoadPhase.COMPLETE) break;
            String prefix = scenario.legacyV10() ? runId : runId + "-" + safe(scenario.workloadId());
            String iterationId = prefix + "-arrival-" + sequence;
            long dueAt = safeDue(startedAt, dueOffset);
            scheduledCount = sequence;
            LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.rejected(runId, "arrivalRate", phase.name(), iterationId,
                    sequence, dueAt, observedAt, "GENERATION_DEADLINE_EXPIRED")));
        }
        return scheduledCount;
    }

    private void signalWorkerChange() {
        BlockingQueue<Boolean> wakeups = schedulerWakeups;
        if (wakeups != null) wakeups.offer(Boolean.TRUE);
        synchronized (completionMonitor) { completionMonitor.notifyAll(); }
    }

    private void enableCheckpoints(LoadMetrics metrics) {
        if (evidenceOutputRoot == null) return;
        String workload = scenario.workloadId() == null ? "aggregate" : "workload-" + safe(scenario.workloadId());
        String directory = scenario.workloadId() == null ? "aggregate"
                : LoadMetricsCheckpointStore.workloadCheckpointDirectoryName(scenario.workloadId());
        metrics.enableCheckpoints(evidenceOutputRoot.resolve("load").resolve(runId).resolve("checkpoints").resolve(directory),
                runId, workload);
    }

    private LoadEvent tag(LoadEvent event) {
        return scenario.legacyV10() ? event : event.withWorkloadIdentity(
                scenario.workloadId(), scenario.targetType(), scenario.targetId());
    }
    private Path evidenceOutputRoot(String iterationId) {
        if (evidenceStore == null || evidenceOutputRoot == null || !evidenceStore.reserveSuccessEvidence(iterationId, scenario.workloadId())) return null;
        Path root = evidenceOutputRoot.resolve("load").resolve(runId).resolve("iterations");
        return scenario.legacyV10() ? root : root.resolve(
                LoadMetricsCheckpointStore.workloadCheckpointDirectoryName(scenario.workloadId()));
    }
    private long remainingRunMillis(long startedAt) {
        return LoadPhase.totalMs(scenario) - (timing.now() - startedAt);
    }
    /** Counts arrival thresholds reached by this elapsed millisecond, excluding the generation end. */
    static long arrivalsDueAt(LoadScenario scenario, long elapsedMs) {
        if (elapsedMs < 0L) return 0L;
        long total = LoadPhase.totalMs(scenario);
        if (total <= 0L) return 0L;
        if (elapsedMs >= total) return arrivalsBeforeDeadline(scenario);
        double cumulative = approximateCumulativeArrivals(scenario, elapsedMs);
        if (Double.isInfinite(cumulative) || cumulative >= Long.MAX_VALUE - 1.0) return Long.MAX_VALUE;
        double nearestInteger = Math.rint(cumulative);
        double tolerance = Math.max(1.0e-12, 64.0 * Math.ulp(Math.max(Math.abs(cumulative), Math.abs(nearestInteger))));
        if (Math.abs(cumulative - nearestInteger) <= tolerance) {
            Fraction exact = exactCumulativeArrivals(scenario, elapsedMs);
            if (exact != null) return exact.floorPlusOneSaturated();
        }
        return (long) Math.floor(Math.max(0.0, cumulative)) + 1L;
    }
    /** Counts thresholds reached strictly before the generation deadline; a threshold at the end is excluded. */
    static long arrivalsBeforeDeadline(LoadScenario scenario) {
        long total = LoadPhase.totalMs(scenario);
        if (total <= 0L) return 0L;
        Fraction exact = exactCumulativeArrivals(scenario, total);
        if (exact != null) return exact.ceilSaturated();
        double cumulative = approximateCumulativeArrivals(scenario, total);
        if (Double.isInfinite(cumulative) || cumulative >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.ceil(Math.max(0.0, cumulative));
    }
    static double cumulativeArrivals(LoadScenario scenario, long elapsedMs) {
        Fraction exact = exactCumulativeArrivals(scenario, elapsedMs);
        return exact == null ? approximateCumulativeArrivals(scenario, elapsedMs) : exact.doubleValue();
    }
    private static double approximateCumulativeArrivals(LoadScenario scenario, long elapsedMs) {
        long total = LoadPhase.totalMs(scenario); long step = Math.max(0L, Math.min(total, elapsedMs));
        double rate = scenario.arrivalRatePerSecond(); double result = 0.0; long cursor = 0L;
        long warmup = scenario.warmup().toMillis(); long rampUp = scenario.rampUp().toMillis(); long steady = scenario.duration().toMillis(); long rampDown = scenario.rampDown().toMillis();
        long warmupElapsed = Math.min(step, warmup); result += rate * warmupElapsed / 1000.0; cursor += warmup; if (step <= cursor) return result;
        long upElapsed = Math.min(step - cursor, rampUp); result += rampArea(rate, rampUp, upElapsed, true); cursor += rampUp; if (step <= cursor) return result;
        long steadyElapsed = Math.min(step - cursor, steady); result += rate * steadyElapsed / 1000.0; cursor += steady; if (step <= cursor) return result;
        long downElapsed = Math.min(step - cursor, rampDown); result += rampArea(rate, rampDown, downElapsed, false); return result;
    }
    private static double rampArea(double rate, long duration, long elapsed, boolean up) { if (duration <= 0 || elapsed <= 0) return 0.0; double fraction = Math.min(1.0, ((double) elapsed) / duration); return rate * elapsed / 1000.0 * (up ? fraction / 2.0 : 1.0 - fraction / 2.0); }
    /**
     * Returns the first integer-millisecond offset where cumulative area reaches sequence - 1.
     * The final threshold may map to the exclusive end; callers must not admit it.
     */
    static long plannedDue(LoadScenario scenario, long start, long sequenceValue) {
        return safeDue(start, plannedDueOffset(scenario, sequenceValue));
    }
    private static long plannedDueOffset(LoadScenario scenario, long sequenceValue) {
        if (sequenceValue < 1L) throw new IllegalArgumentException("Arrival sequence must be >= 1");
        if (sequenceValue == 1L) return 0L;
        long total = LoadPhase.totalMs(scenario);
        double target = sequenceValue - 1.0;
        double rate = scenario.arrivalRatePerSecond();
        long cursor = 0L;
        long warmup = scenario.warmup().toMillis();
        long rampUp = scenario.rampUp().toMillis();
        long steady = scenario.duration().toMillis();
        long rampDown = scenario.rampDown().toMillis();
        double area = rate * warmup / 1000.0;
        long approximate;
        if (target <= area) approximate = inverseLinear(target, rate);
        else {
            target -= area; cursor += warmup;
            area = rate * rampUp / 2000.0;
            if (target <= area && rampUp > 0L) approximate = saturatedOffsetAdd(cursor, inverseRampUp(target, rate, rampUp));
            else {
                target -= area; cursor += rampUp;
                area = rate * steady / 1000.0;
                if (target <= area) approximate = saturatedOffsetAdd(cursor, inverseLinear(target, rate));
                else {
                    target -= area; cursor += steady;
                    approximate = saturatedOffsetAdd(cursor, inverseRampDown(target, rate, rampDown));
                }
            }
        }
        return correctedDueOffset(scenario, sequenceValue - 1L, approximate, total);
    }
    private static long saturatedOffsetAdd(long left, long right) {
        try { return Math.addExact(left, right); } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    /**
     * Correct the analytic inverse against the exact configured-rate integral. The inverse is
     * normally within one millisecond; a bounded local search handles rounding, with a binary
     * search fallback so valid pre-deadline arrivals are never lost.
     */
    private static long correctedDueOffset(LoadScenario scenario, long target, long approximate, long total) {
        if (target <= 0L) return 0L;
        Fraction rate = exactArrivalRate(scenario);
        if (rate == null) return Math.min(Math.max(0L, approximate), total);
        ArrivalArea area = new ArrivalArea(scenario, rate, total);
        long candidate = Math.min(Math.max(0L, approximate), total);
        long low = Math.max(0L, candidate - 4L);
        long high = candidate > Long.MAX_VALUE - 4L ? total : Math.min(total, candidate + 4L);
        if (area.at(low).compareTo(target) >= 0)
            return firstOffsetAtOrAfter(area, target, 0L, low);
        if (area.at(high).compareTo(target) >= 0)
            return firstOffsetAtOrAfter(area, target, low + 1L, high);
        if (area.at(total).compareTo(target) < 0)
            return total == Long.MAX_VALUE ? Long.MAX_VALUE : total + 1L;
        return firstOffsetAtOrAfter(area, target, high + 1L, total);
    }
    private static long firstOffsetAtOrAfter(ArrivalArea area, long target, long low, long high) {
        while (low < high) {
            long middle = low + (high - low) / 2L;
            if (area.at(middle).compareTo(target) >= 0) high = middle;
            else low = middle + 1L;
        }
        return low;
    }
    private static Fraction exactCumulativeArrivals(LoadScenario scenario, long elapsedMs) {
        Fraction rate = exactArrivalRate(scenario);
        return rate == null ? null : new ArrivalArea(scenario, rate, LoadPhase.totalMs(scenario)).at(elapsedMs);
    }
    private static Fraction exactArrivalRate(LoadScenario scenario) {
        String configured = scenario.arrivalRate();
        if (configured != null) {
            int slash = configured.lastIndexOf('/');
            if (slash > 0 && slash < configured.length() - 1) {
                String unit = configured.substring(slash + 1).trim();
                if ("s".equals(unit) || "m".equals(unit)) {
                    try {
                        Fraction value = Fraction.fromDecimal(new BigDecimal(configured.substring(0, slash).trim()));
                        return "m".equals(unit) ? value.multiplyDivide(BigInteger.ONE, BigInteger.valueOf(60L)) : value;
                    } catch (NumberFormatException ignored) {
                        // Older in-memory scenarios may carry a display label instead of a rate literal.
                    }
                }
            }
        }
        double rate = scenario.arrivalRatePerSecond();
        return Double.isNaN(rate) || Double.isInfinite(rate) ? null : Fraction.fromDecimal(BigDecimal.valueOf(rate));
    }

    private static final class ArrivalArea {
        private final LoadScenario scenario;
        private final Fraction rate;
        private final long total;
        ArrivalArea(LoadScenario scenario, Fraction rate, long total) {
            this.scenario = scenario; this.rate = rate; this.total = total;
        }
        Fraction at(long elapsedMs) {
            long step = Math.max(0L, Math.min(total, elapsedMs));
            long warmup = scenario.warmup().toMillis();
            long rampUp = scenario.rampUp().toMillis();
            long steady = scenario.duration().toMillis();
            long rampDown = scenario.rampDown().toMillis();
            long cursor = 0L;
            Fraction result = Fraction.ZERO;
            long warmupElapsed = Math.min(step, warmup);
            result = result.add(rate.multiplyDivide(BigInteger.valueOf(warmupElapsed), BigInteger.valueOf(1000L)));
            cursor += warmup;
            if (step <= cursor) return result;
            long upElapsed = Math.min(step - cursor, rampUp);
            if (rampUp > 0L) {
                BigInteger elapsed = BigInteger.valueOf(upElapsed);
                BigInteger divisor = BigInteger.valueOf(rampUp).multiply(BigInteger.valueOf(2000L));
                result = result.add(rate.multiplyDivide(elapsed.multiply(elapsed), divisor));
            }
            cursor += rampUp;
            if (step <= cursor) return result;
            long steadyElapsed = Math.min(step - cursor, steady);
            result = result.add(rate.multiplyDivide(BigInteger.valueOf(steadyElapsed), BigInteger.valueOf(1000L)));
            cursor += steady;
            if (step <= cursor) return result;
            long downElapsed = Math.min(step - cursor, rampDown);
            if (rampDown > 0L) {
                BigInteger duration = BigInteger.valueOf(rampDown);
                BigInteger elapsed = BigInteger.valueOf(downElapsed);
                BigInteger numerator = duration.multiply(elapsed).shiftLeft(1).subtract(elapsed.multiply(elapsed));
                BigInteger divisor = duration.multiply(BigInteger.valueOf(2000L));
                result = result.add(rate.multiplyDivide(numerator, divisor));
            }
            return result;
        }
    }

    private static final class Fraction {
        static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
        private final BigInteger numerator;
        private final BigInteger denominator;
        private Fraction(BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() == 0) throw new IllegalArgumentException("Fraction denominator must not be zero");
            if (denominator.signum() < 0) { numerator = numerator.negate(); denominator = denominator.negate(); }
            BigInteger gcd = numerator.gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
        }
        static Fraction fromDecimal(BigDecimal value) {
            BigInteger numerator = value.unscaledValue();
            int scale = value.scale();
            return scale < 0
                    ? new Fraction(numerator.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE)
                    : new Fraction(numerator, BigInteger.TEN.pow(scale));
        }
        Fraction add(Fraction other) {
            BigInteger gcd = denominator.gcd(other.denominator);
            BigInteger leftScale = other.denominator.divide(gcd);
            BigInteger rightScale = denominator.divide(gcd);
            return new Fraction(numerator.multiply(leftScale).add(other.numerator.multiply(rightScale)),
                    denominator.multiply(leftScale));
        }
        Fraction multiplyDivide(BigInteger value, BigInteger divisor) {
            return new Fraction(numerator.multiply(value), denominator.multiply(divisor));
        }
        int compareTo(long integer) {
            return numerator.compareTo(denominator.multiply(BigInteger.valueOf(integer)));
        }
        long floorPlusOneSaturated() {
            BigInteger value = numerator.divide(denominator).add(BigInteger.ONE);
            return value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : value.longValue();
        }
        long ceilSaturated() {
            BigInteger[] division = numerator.divideAndRemainder(denominator);
            BigInteger value = division[1].signum() > 0 ? division[0].add(BigInteger.ONE) : division[0];
            return value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : value.longValue();
        }
        double doubleValue() {
            return new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContext.DECIMAL128).doubleValue();
        }
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
    void forwardSchedulerMetricsTo(LoadMetrics aggregate) {
        if (aggregate == null) throw new IllegalArgumentException("Aggregate LOAD metrics are required");
        aggregateSchedulerMetrics = aggregate;
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
