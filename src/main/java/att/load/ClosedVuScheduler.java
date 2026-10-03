package att.load;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Closed workload scheduler: each VU waits for completion before its next iteration. */
public final class ClosedVuScheduler implements LoadScheduler {
    private final LoadScenario scenario;
    private final LoadIterationRunner executor;
    private final String runId;
    private final Consumer<LoadEvent> listener;
    private final LoadSchedulerTiming timing;
    private final LoadEvidenceStore evidenceStore;
    private final Path evidenceOutputRoot;
    private final LoadSchedulerStartGate startGate;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private final Object admissionLock = new Object();
    private final ConcurrentHashMap<String, TaskHandle> admittedTasks = new ConcurrentHashMap<String, TaskHandle>();
    private volatile ThreadPoolExecutor workers;
    private volatile Thread controlThread;
    private volatile boolean ownsWorkers = true;

    public ClosedVuScheduler(LoadScenario scenario, IterationExecutor executor, String runId, Consumer<LoadEvent> listener) {
        this(scenario, executor, runId, listener, LoadSchedulerTiming.system());
    }
    public ClosedVuScheduler(LoadScenario scenario, IterationExecutor executor, String runId, LoadEventListener listener) {
        this(scenario, executor, runId, adapt(listener));
    }
    public ClosedVuScheduler(LoadScenario scenario, IterationExecutor executor, String runId,
                             LoadEvidenceStore evidenceStore, Path outputRoot) {
        this(scenario, executor, runId, adapt(evidenceStore), LoadSchedulerTiming.system(), evidenceStore, outputRoot, null);
    }
    public ClosedVuScheduler(LoadScenario scenario, IterationExecutor executor, String runId,
                             LoadEvidenceStore evidenceStore) {
        this(scenario, executor, runId, adapt(evidenceStore), LoadSchedulerTiming.system(), evidenceStore,
                executor == null ? null : executor.outputRoot(), null);
    }
    ClosedVuScheduler(LoadScenario scenario, LoadIterationRunner executor, String runId,
                      Consumer<LoadEvent> listener, LoadSchedulerTiming timing) {
        this(scenario, executor, runId, listener, timing, null, null, null);
    }
    ClosedVuScheduler(LoadScenario scenario, LoadIterationRunner executor, String runId,
                      Consumer<LoadEvent> listener, LoadSchedulerTiming timing,
                      LoadEvidenceStore evidenceStore, Path evidenceOutputRoot, LoadSchedulerStartGate startGate) {
        if (scenario == null || scenario.model() != LoadScenario.Model.CLOSED) throw new IllegalArgumentException("ClosedVuScheduler requires a closed scenario");
        if (executor == null) throw new IllegalArgumentException("ClosedVuScheduler requires an iteration executor");
        this.scenario = scenario;
        this.executor = executor;
        this.runId = LoadSchedulerSupport.runId(runId);
        this.listener = listener;
        this.timing = timing == null ? LoadSchedulerTiming.system() : timing;
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
            if (!(executor instanceof IterationExecutor)) throw new IllegalArgumentException("Multi-workload closed scheduling requires IterationExecutor");
            return LoadRunCoordinator.runFrom(scenario, (IterationExecutor) executor, runId, evidenceStore, evidenceOutputRoot);
        }
        if (executor instanceof IterationExecutor) ((IterationExecutor) executor).initializeOutputNamespace(runId);
        final long startedAt = startGate == null ? timing.now() : startGate.awaitStart();
        final Instant start = LoadSchedulerSupport.instant(startedAt);
        final long runSeed = LoadRandomization.effectiveSeed(scenario, runId);
        final LoadMetrics metrics = LoadMetrics.forScenario(scenario, startedAt, 0L);
        // Iteration execution is synchronous and may block on SUT I/O, so worker
        // capacity can grow to preserve every configured closed VU's slot.
        int workerCount = scenario.users();
        if (workers == null) {
            workers = LoadWorkerPool.create(LoadWorkerPool.coreSize(workerCount), workerCount,
                    new NamedFactory("att-load-vu-" + safe(scenario.workloadId())));
            ownsWorkers = true;
        } else {
            workerCount = Math.min(workerCount, workers.getMaximumPoolSize());
        }
        java.util.List<VuState> users = new java.util.ArrayList<VuState>(scenario.users());
        for (int user = 0; user < scenario.users(); user++) {
            String userId = "VU-" + (user + 1);
            users.add(new VuState(user, LoadRandomization.randomForVu(runSeed,
                    LoadRandomization.workloadKey(scenario), userId)));
        }
        BlockingQueue<VuCompletion> completions = new LinkedBlockingQueue<VuCompletion>();
        int inFlight = 0;
        int dispatchCursor = 0;
        controlThread = Thread.currentThread();
        try {
            while (true) {
                VuCompletion completion;
                while ((completion = completions.poll()) != null) {
                    completion.user.running = false;
                    inFlight--;
                    if (!cancelled.get() && completion.completedAt < startedAt + LoadPhase.totalMs(scenario)) {
                        long think = scenario.thinkTimePolicy().sampleMillis(completion.user.random);
                        completion.user.readyAt = completion.completedAt + think;
                    }
                }
                long now = timing.now();
                long elapsed = now - startedAt;
                metrics.recordSchedulerWakeup(((ThreadPoolExecutor) workers).getQueue().size());
                int active = activeUsers(elapsed);
                for (int scanned = 0; scanned < users.size(); scanned++) {
                    if (cancelled.get() || elapsed >= LoadPhase.totalMs(scenario) || inFlight >= workerCount) break;
                    int userIndex = dispatchCursor;
                    dispatchCursor = (dispatchCursor + 1) % users.size();
                    VuState user = users.get(userIndex);
                    if (user.running || user.readyAt > now || user.userNumber >= active) continue;
                    if (!submitIteration(user, startedAt, metrics, completions)) break;
                    user.running = true;
                    inFlight++;
                }
                if (cancelled.get() && inFlight == 0) break;
                if (elapsed >= LoadPhase.totalMs(scenario) && inFlight == 0) break;
                long next = nextWakeAt(users, now, elapsed, active, startedAt, inFlight);
                long wait = cancelled.get() && inFlight > 0 ? Long.MAX_VALUE : Math.max(1L, next - now);
                VuCompletion arrived;
                try { arrived = timing.await(completions, wait); }
                catch (InterruptedException interrupted) {
                    if (cancelled.get()) continue;
                    throw interrupted;
                }
                if (arrived != null) {
                    arrived.user.running = false;
                    inFlight--;
                    if (!cancelled.get() && arrived.completedAt < startedAt + LoadPhase.totalMs(scenario)) {
                        long think = scenario.thinkTimePolicy().sampleMillis(arrived.user.random);
                        arrived.user.readyAt = arrived.completedAt + think;
                    }
                }
            }
        } finally { shutdown(); controlThread = null; }
        long endedAt = timing.now(); metrics.finish(endedAt);
        return new LoadRunResult(runId, scenario, start, LoadSchedulerSupport.instant(endedAt), metrics.snapshot());
    }

    private boolean submitIteration(final VuState user, final long startedAt, final LoadMetrics metrics,
                                    final BlockingQueue<VuCompletion> completions) {
        final String userId = "VU-" + (user.userNumber + 1);
        long phaseElapsed = Math.max(0L, timing.now() - startedAt);
        long runDuration = LoadPhase.totalMs(scenario);
        if (runDuration > 0L) phaseElapsed = Math.min(phaseElapsed, runDuration - 1L);
        final String phase = LoadPhase.at(scenario, phaseElapsed).name();
        final long sequenceValue = LoadSchedulerSupport.next(sequence);
        final long scheduledAt = timing.now();
        final long iteration = ++user.iteration;
        final LoadMixEntry selectedMix = LoadMixSelector.select(scenario.workload(),
                LoadRandomization.effectiveSeed(scenario, runId), iteration, userId);
        final String prefix = scenario.legacyV10() ? runId : runId + "-" + safe(scenario.workloadId());
        final String iterationId = prefix + "-" + userId + "-" + iteration;
        IterationRequest request = new IterationRequest(runId, LoadSchedulerSupport.instant(startedAt), "closed", iterationId,
                sequenceValue, phase, LoadSchedulerSupport.instant(scheduledAt), userId,
                LoadMixSelector.mergeInputs(scenario.inputs(), selectedMix), null);
        request = request.withTestdataWaitAllowed(() -> !cancelled.get() && remainingRunMillis(startedAt) > 0L);
        if (!scenario.legacyV10()) request = request.withWorkloadId(scenario.workloadId());
        Path evidenceRoot = evidenceOutputRoot(iterationId);
        if (evidenceRoot != null) request = request.withOutputDirectory(evidenceRoot).withEvidenceRetention(true, false);
        else if (evidenceStore != null) request = request.withEvidenceRetention(false, false);
        if (evidenceStore != null) request = request.withFailureLogCapture(evidenceStore.retainsFailureEvidence());
        if (selectedMix != null) request = request.withMixIdentity(selectedMix.id(), selectedMix.targetType(),
                selectedMix.targetId(), LoadMixSelector.mergeInputs(scenario.inputs(), selectedMix));
        final IterationRequest iterationRequest = request;
        final TaskHandle taskHandle = new TaskHandle();
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            boolean cancelledBeforeStart = taskHandle.begin();
            long iterationStarted = timing.now();
            att.core.ResultStatus status;
            String errorType = null;
            EvidenceRef evidence = null;
            LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.started(runId, "closed", phase, iterationId, userId,
                    sequenceValue, scheduledAt, iterationStarted), iterationRequest));
            if (cancelledBeforeStart) {
                status = att.core.ResultStatus.ERROR;
                errorType = "CANCELLED";
            } else {
                try {
                    IterationResult result = executor.execute(iterationRequest);
                    if (result.status() != att.core.ResultStatus.PASS && evidenceStore != null && evidenceStore.claimFailureEvidence(iterationId))
                        result = result.materializeEvidence();
                    else if (result.status() != att.core.ResultStatus.PASS && evidenceStore != null) evidenceStore.releaseEvidence(iterationId);
                    if (result.evidenceRef() == null) result.discardTransientWorkspace();
                    if (result.testdataStopRequested()) cancelled.set(true);
                    status = result.status(); errorType = LoadSchedulerSupport.errorType(result); evidence = result.evidenceRef();
                } catch (RuntimeException failure) {
                    if (evidenceStore != null && !evidenceStore.claimFailureEvidence(iterationId)) evidenceStore.releaseEvidence(iterationId);
                    status = att.core.ResultStatus.ERROR; errorType = "RUNTIME_ERROR";
                }
            }
            long completedAt = timing.now();
            try {
                LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.completion(runId, "closed", phase, iterationId, userId,
                        sequenceValue, scheduledAt, iterationStarted, completedAt, status, errorType, evidence), iterationRequest));
            } finally {
                completions.offer(new VuCompletion(user, completedAt));
                admittedTasks.remove(iterationId, taskHandle);
            }
            return null;
        });
        taskHandle.future = task;
        synchronized (admissionLock) {
            if (cancelled.get()) return false;
            admittedTasks.put(iterationId, taskHandle);
            try { workers.execute(task); }
            catch (RuntimeException failure) {
                admittedTasks.remove(iterationId);
                if (cancelled.get()) return false;
                throw failure;
            }
        }
        return true;
    }

    private long nextWakeAt(java.util.List<VuState> users, long now, long elapsed, int active,
                            long startedAt, int inFlight) {
        long end = safeAdd(startedAt, LoadPhase.totalMs(scenario));
        if (elapsed >= LoadPhase.totalMs(scenario) && inFlight > 0) return Long.MAX_VALUE;
        long next = end;
        for (VuState user : users) {
            if (user.running || user.userNumber >= scenario.users()) continue;
            if (user.userNumber < active && user.readyAt > now) next = Math.min(next, user.readyAt);
            else if (user.userNumber >= active && elapsed < scenario.warmup().toMillis() + scenario.rampUp().toMillis()) {
                long activation = scenario.warmup().toMillis()
                        + (long) Math.floor(((double) scenario.rampUp().toMillis() * user.userNumber) / scenario.users()) + 1L;
                next = Math.min(next, safeAdd(startedAt, activation));
            }
        }
        if (inFlight > 0 && next <= now) return safeAdd(now, 1L);
        return Math.max(now + 1L, next);
    }
    private static long safeAdd(long left, long right) {
        try { return Math.addExact(left, right); } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    private LoadEvent tag(LoadEvent event, IterationRequest request) {
        if (scenario.legacyV10()) return event;
        String type = request.targetType() == null ? scenario.targetType() : request.targetType();
        String target = request.targetId() == null ? scenario.targetId() : request.targetId();
        LoadEvent tagged = event.withWorkloadIdentity(scenario.workloadId(), type, target);
        return request.mixId() == null ? tagged : tagged.withMixIdentity(request.mixId(), type, target);
    }

    private void sleepThinkTime(long millis, long startedAt) throws InterruptedException {
        long remainingThinkTime = millis;
        while (remainingThinkTime > 0L && !cancelled.get()) {
            long remainingRun = remainingRunMillis(startedAt);
            if (remainingRun <= 0L) return;
            long slice = Math.min(LoadSchedulerSupport.MAX_SLEEP_SLICE_MS, Math.min(remainingThinkTime, remainingRun));
            if (slice <= 0L) return;
            timing.sleep(slice);
            remainingThinkTime -= slice;
        }
    }
    private long remainingRunMillis(long startedAt) { return LoadPhase.totalMs(scenario) - (timing.now() - startedAt); }
    private Path evidenceOutputRoot(String iterationId) {
        if (evidenceStore == null || evidenceOutputRoot == null || !evidenceStore.reserveSuccessEvidence(iterationId)) return null;
        Path root = evidenceOutputRoot.resolve("load").resolve(runId).resolve("iterations");
        return scenario.legacyV10() ? root : root.resolve(safe(scenario.workloadId()));
    }
    int activeUsers(long elapsedMs) { return activeUsers(scenario, elapsedMs); }
    static int activeUsers(LoadScenario scenario, long elapsedMs) {
        long warmup = scenario.warmup().toMillis(), rampUp = scenario.rampUp().toMillis();
        if (elapsedMs < warmup) return scenario.users();
        if (LoadPhase.at(scenario, elapsedMs) == LoadPhase.COMPLETE) return 0;
        if (rampUp > 0L && elapsedMs < warmup + rampUp) return Math.max(1, (int) Math.ceil(scenario.users() * LoadPhase.fraction(scenario, elapsedMs)));
        if (LoadPhase.at(scenario, elapsedMs) == LoadPhase.RAMP_DOWN) return Math.max(1, (int) Math.ceil(scenario.users() * LoadPhase.fraction(scenario, elapsedMs)));
        return scenario.users();
    }
    @Override public void cancel() {
        cancelled.set(true);
        Thread control = controlThread;
        if (control != null) control.interrupt();
        synchronized (admissionLock) {
            for (TaskHandle task : admittedTasks.values()) task.cancel();
        }
        if (ownsWorkers) shutdown();
    }
    @Override public void close() { cancel(); }
    private void shutdown() {
        ThreadPoolExecutor value = workers;
        if (!ownsWorkers) return;
        if (value != null) {
            value.shutdownNow();
            try { if (!value.awaitTermination(1L, TimeUnit.SECONDS)) value.shutdownNow(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { workers = null; }
        }
    }
    private static String safe(String value) { return value == null ? "default" : value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private static final class VuState {
        final int userNumber;
        final Random random;
        long iteration;
        long readyAt;
        boolean running;
        VuState(int userNumber, Random random) { this.userNumber = userNumber; this.random = random; }
    }
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
    private static final class VuCompletion {
        final VuState user;
        final long completedAt;
        VuCompletion(VuState user, long completedAt) { this.user = user; this.completedAt = completedAt; }
    }
    private static final class NamedFactory implements ThreadFactory {
        private final String prefix; private final AtomicLong index = new AtomicLong();
        NamedFactory(String prefix) { this.prefix = prefix; }
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + "-" + index.incrementAndGet()); t.setDaemon(true); return t; }
    }
}
