package att.load;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Closed workload scheduler: each VU waits for completion before its next iteration. */
public final class ClosedVuScheduler implements LoadScheduler {
    private final LoadScenario scenario;
    private final IterationExecutor executor;
    private final String runId;
    private final Consumer<LoadEvent> listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong();
    private volatile ExecutorService workers;

    public ClosedVuScheduler(LoadScenario scenario, IterationExecutor executor, String runId, Consumer<LoadEvent> listener) {
        if (scenario == null || scenario.model() != LoadScenario.Model.CLOSED) throw new IllegalArgumentException("ClosedVuScheduler requires a closed scenario");
        this.scenario = scenario; this.executor = executor; this.runId = LoadSchedulerSupport.runId(runId); this.listener = listener;
    }
    public ClosedVuScheduler(LoadScenario scenario, IterationExecutor executor, String runId, LoadEventListener listener) {
        this(scenario, executor, runId, adapt(listener));
    }
    private static Consumer<LoadEvent> adapt(final LoadEventListener listener) { return listener == null ? null : new Consumer<LoadEvent>() { @Override public void accept(LoadEvent event) { listener.onEvent(event); } }; }

    @Override public LoadRunResult run() throws Exception {
        final long startedAt = LoadSchedulerSupport.now(); final Instant start = LoadSchedulerSupport.instant(startedAt);
        final LoadMetrics metrics = new LoadMetrics(scenario.model().wireName(), startedAt);
        workers = Executors.newFixedThreadPool(scenario.users(), new NamedFactory("att-load-vu"));
        java.util.List<Future<?>> futures = new java.util.ArrayList<Future<?>>();
        for (int user = 0; user < scenario.users(); user++) {
            final int userNumber = user;
            futures.add(workers.submit(() -> runUser(userNumber, startedAt, metrics)));
        }
        try { for (Future<?> future : futures) future.get(); }
        finally { shutdown(); }
        long endedAt = LoadSchedulerSupport.now(); metrics.finish(endedAt);
        return new LoadRunResult(runId, scenario, start, LoadSchedulerSupport.instant(endedAt), metrics.snapshot());
    }

    private void runUser(int userNumber, long startedAt, LoadMetrics metrics) {
        long userIteration = 0L; String userId = "VU-" + (userNumber + 1);
        try {
            while (!cancelled.get()) {
                long elapsed = LoadSchedulerSupport.now() - startedAt;
                String phase = LoadPhase.at(scenario, elapsed).name(); if ("COMPLETE".equals(phase)) return;
                int active = activeUsers(elapsed);
                if (userNumber >= active) { LoadSchedulerSupport.sleep(1L); continue; }
                long sequenceValue = LoadSchedulerSupport.next(sequence);
                long scheduledAt = LoadSchedulerSupport.now();
                long iteration = ++userIteration;
                String iterationId = runId + "-" + userId + "-" + iteration;
                IterationRequest request = new IterationRequest(runId, LoadSchedulerSupport.instant(startedAt), "closed", iterationId,
                        sequenceValue, phase, LoadSchedulerSupport.instant(scheduledAt), userId, scenario.inputs(), null);
                long iterationStarted = LoadSchedulerSupport.now();
                att.core.ResultStatus status;
                try { status = executor.execute(request).status(); }
                catch (RuntimeException failure) { status = att.core.ResultStatus.ERROR; }
                long completedAt = LoadSchedulerSupport.now();
                LoadSchedulerSupport.emit(metrics, listener, LoadEvent.completed(runId, "closed", phase, iterationId, userId,
                        sequenceValue, scheduledAt, iterationStarted, completedAt, status));
                LoadSchedulerSupport.sleep(scenario.thinkTime().toMillis());
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private int activeUsers(long elapsedMs) {
        long warmup = scenario.warmup().toMillis(), rampUp = scenario.rampUp().toMillis();
        if (elapsedMs < warmup) return scenario.users();
        if (rampUp > 0L && elapsedMs < warmup + rampUp) return Math.max(1, (int) Math.ceil(scenario.users() * LoadPhase.fraction(scenario, elapsedMs)));
        if (LoadPhase.at(scenario, elapsedMs) == LoadPhase.RAMP_DOWN) return Math.max(1, (int) Math.ceil(scenario.users() * LoadPhase.fraction(scenario, elapsedMs)));
        return scenario.users();
    }
    @Override public void cancel() { cancelled.set(true); shutdown(); }
    @Override public void close() { cancel(); }
    private void shutdown() { ExecutorService value = workers; if (value != null) { value.shutdownNow(); workers = null; } }
    private static final class NamedFactory implements ThreadFactory { private final String prefix; private final AtomicLong index = new AtomicLong(); NamedFactory(String prefix) { this.prefix = prefix; } @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + "-" + index.incrementAndGet()); t.setDaemon(true); return t; } }
}
