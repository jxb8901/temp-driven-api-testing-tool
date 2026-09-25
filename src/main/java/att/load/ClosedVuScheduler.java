package att.load;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private volatile ExecutorService workers;

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

    @Override public LoadRunResult run() throws Exception {
        if (scenario.coordinatorRequired()) {
            if (!(executor instanceof IterationExecutor)) throw new IllegalArgumentException("Multi-workload closed scheduling requires IterationExecutor");
            return LoadRunCoordinator.runFrom(scenario, (IterationExecutor) executor, runId, evidenceStore, evidenceOutputRoot);
        }
        final long startedAt = startGate == null ? timing.now() : startGate.awaitStart();
        final Instant start = LoadSchedulerSupport.instant(startedAt);
        final long runSeed = LoadRandomization.effectiveSeed(scenario, runId);
        final LoadMetrics metrics = LoadMetrics.forScenario(scenario, startedAt, 0L);
        workers = Executors.newFixedThreadPool(scenario.users(), new NamedFactory("att-load-vu-" + safe(scenario.workloadId())));
        java.util.List<Future<?>> futures = new java.util.ArrayList<Future<?>>();
        for (int user = 0; user < scenario.users(); user++) {
            final int userNumber = user;
            futures.add(workers.submit(() -> runUser(userNumber, startedAt, metrics, runSeed)));
        }
        try { for (Future<?> future : futures) future.get(); }
        finally { shutdown(); }
        long endedAt = timing.now(); metrics.finish(endedAt);
        return new LoadRunResult(runId, scenario, start, LoadSchedulerSupport.instant(endedAt), metrics.snapshot());
    }

    private void runUser(int userNumber, long startedAt, LoadMetrics metrics, long runSeed) {
        long userIteration = 0L; String userId = "VU-" + (userNumber + 1);
        Random random = LoadRandomization.randomForVu(runSeed, LoadRandomization.workloadKey(scenario), userId);
        try {
            while (!cancelled.get()) {
                long elapsed = timing.now() - startedAt;
                String phase = LoadPhase.at(scenario, elapsed).name(); if ("COMPLETE".equals(phase)) return;
                int active = activeUsers(elapsed);
                if (userNumber >= active) { timing.sleep(1L); continue; }
                long sequenceValue = LoadSchedulerSupport.next(sequence);
                long scheduledAt = timing.now();
                long iteration = ++userIteration;
                String prefix = scenario.legacyV1() ? runId : runId + "-" + safe(scenario.workloadId());
                String iterationId = prefix + "-" + userId + "-" + iteration;
                IterationRequest request = new IterationRequest(runId, LoadSchedulerSupport.instant(startedAt), "closed", iterationId,
                        sequenceValue, phase, LoadSchedulerSupport.instant(scheduledAt), userId, scenario.inputs(), null);
                if (!scenario.legacyV1()) request = request.withWorkloadId(scenario.workloadId());
                Path sampleRoot = sampleOutputRoot(iterationId);
                if (sampleRoot != null) request = request.withOutputDirectory(sampleRoot);
                request = request.withFailureEvidence(evidenceStore == null || evidenceStore.retainsFailureEvidence());
                long iterationStarted = timing.now();
                att.core.ResultStatus status;
                String errorType = null;
                EvidenceRef evidence = null;
                LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.started(runId, "closed", phase, iterationId, userId,
                        sequenceValue, scheduledAt, iterationStarted)));
                try {
                    IterationResult result = executor.execute(request);
                    status = result.status(); errorType = LoadSchedulerSupport.errorType(result); evidence = result.evidenceRef();
                }
                catch (RuntimeException failure) { status = att.core.ResultStatus.ERROR; errorType = "RUNTIME_ERROR"; }
                long completedAt = timing.now();
                LoadSchedulerSupport.emit(metrics, listener, tag(LoadEvent.completion(runId, "closed", phase, iterationId, userId,
                        sequenceValue, scheduledAt, iterationStarted, completedAt, status, errorType, evidence)));
                long remaining = remainingRunMillis(startedAt);
                if (cancelled.get() || remaining <= 0L) return;
                long sampledThinkTime = scenario.thinkTimePolicy().sampleMillis(random);
                sleepThinkTime(Math.min(sampledThinkTime, remaining), startedAt);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private LoadEvent tag(LoadEvent event) { return scenario.legacyV1() ? event : event.withWorkloadId(scenario.workloadId()); }

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
    private Path sampleOutputRoot(String iterationId) {
        if (evidenceStore == null || evidenceOutputRoot == null || !evidenceStore.reserveSuccess(iterationId)) return null;
        Path root = evidenceOutputRoot.resolve("load").resolve(runId).resolve("iterations");
        return scenario.legacyV1() ? root : root.resolve(safe(scenario.workloadId()));
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
    @Override public void cancel() { cancelled.set(true); shutdown(); }
    @Override public void close() { cancel(); }
    private void shutdown() {
        ExecutorService value = workers;
        if (value != null) {
            value.shutdownNow();
            try { if (!value.awaitTermination(1L, TimeUnit.SECONDS)) value.shutdownNow(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { workers = null; }
        }
    }
    private static String safe(String value) { return value == null ? "default" : value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private static final class NamedFactory implements ThreadFactory {
        private final String prefix; private final AtomicLong index = new AtomicLong();
        NamedFactory(String prefix) { this.prefix = prefix; }
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + "-" + index.incrementAndGet()); t.setDaemon(true); return t; }
    }
}
