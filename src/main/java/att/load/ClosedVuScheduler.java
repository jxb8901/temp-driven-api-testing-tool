package att.load;

import java.time.Instant;
import java.util.Map;
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
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private volatile ExecutorService workers;

    public ClosedVuScheduler(LoadScenario scenario, IterationExecutor executor, String runId, Consumer<LoadEvent> listener) {
        this(scenario, executor, runId, listener, LoadSchedulerTiming.system());
    }
    public ClosedVuScheduler(LoadScenario scenario, IterationExecutor executor, String runId, LoadEventListener listener) {
        this(scenario, executor, runId, adapt(listener));
    }
    ClosedVuScheduler(LoadScenario scenario, LoadIterationRunner executor, String runId,
                      Consumer<LoadEvent> listener, LoadSchedulerTiming timing) {
        if (scenario == null || scenario.model() != LoadScenario.Model.CLOSED) throw new IllegalArgumentException("ClosedVuScheduler requires a closed scenario");
        if (executor == null) throw new IllegalArgumentException("ClosedVuScheduler requires an iteration executor");
        this.scenario = scenario;
        this.executor = executor;
        this.runId = LoadSchedulerSupport.runId(runId);
        this.listener = listener;
        this.timing = timing == null ? LoadSchedulerTiming.system() : timing;
    }
    private static Consumer<LoadEvent> adapt(final LoadEventListener listener) { return listener == null ? null : new Consumer<LoadEvent>() { @Override public void accept(LoadEvent event) { listener.onEvent(event); } }; }

    @Override public LoadRunResult run() throws Exception {
        final long startedAt = timing.now(); final Instant start = LoadSchedulerSupport.instant(startedAt);
        final LoadMetrics metrics = new LoadMetrics(scenario.model().wireName(), startedAt);
        workers = Executors.newFixedThreadPool(scenario.users(), new NamedFactory("att-load-vu"));
        java.util.List<Future<?>> futures = new java.util.ArrayList<Future<?>>();
        for (int user = 0; user < scenario.users(); user++) {
            final int userNumber = user;
            futures.add(workers.submit(() -> runUser(userNumber, startedAt, metrics)));
        }
        try { for (Future<?> future : futures) future.get(); }
        finally { shutdown(); }
        long endedAt = timing.now(); metrics.finish(endedAt);
        return new LoadRunResult(runId, scenario, start, LoadSchedulerSupport.instant(endedAt), metrics.snapshot());
    }

    private void runUser(int userNumber, long startedAt, LoadMetrics metrics) {
        long userIteration = 0L; String userId = "VU-" + (userNumber + 1);
        try {
            while (!cancelled.get()) {
                long elapsed = timing.now() - startedAt;
                String phase = LoadPhase.at(scenario, elapsed).name(); if ("COMPLETE".equals(phase)) return;
                int active = activeUsers(elapsed);
                if (userNumber >= active) { timing.sleep(1L); continue; }
                long sequenceValue = LoadSchedulerSupport.next(sequence);
                long scheduledAt = timing.now();
                long iteration = ++userIteration;
                String iterationId = runId + "-" + userId + "-" + iteration;
                IterationRequest request = new IterationRequest(runId, LoadSchedulerSupport.instant(startedAt), "closed", iterationId,
                        sequenceValue, phase, LoadSchedulerSupport.instant(scheduledAt), userId, scenario.inputs(), null);
                long iterationStarted = timing.now();
                att.core.ResultStatus status;
                EvidenceRef evidence = null;
                try {
                    IterationResult result = executor.execute(request);
                    status = result.status(); evidence = result.evidenceRef();
                }
                catch (RuntimeException failure) { status = att.core.ResultStatus.ERROR; }
                long completedAt = timing.now();
                LoadSchedulerSupport.emit(metrics, listener, LoadEvent.completed(runId, "closed", phase, iterationId, userId,
                        sequenceValue, scheduledAt, iterationStarted, completedAt, status, evidence));
                long remaining = LoadPhase.totalMs(scenario) - (timing.now() - startedAt);
                if (cancelled.get() || remaining <= 0L) return;
                timing.sleep(Math.min(scenario.thinkTime().toMillis(), remaining));
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    int activeUsers(long elapsedMs) {
        return activeUsers(scenario, elapsedMs);
    }

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
            try {
                if (!value.awaitTermination(1L, TimeUnit.SECONDS)) value.shutdownNow();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                workers = null;
            }
        }
    }
    private static final class NamedFactory implements ThreadFactory { private final String prefix; private final AtomicLong index = new AtomicLong(); NamedFactory(String prefix) { this.prefix = prefix; } @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + "-" + index.incrementAndGet()); t.setDaemon(true); return t; } }
}
