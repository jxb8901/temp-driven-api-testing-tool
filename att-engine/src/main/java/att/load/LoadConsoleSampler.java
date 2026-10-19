package att.load;

import att.console.ExecutionConsoleFormatter;
import att.core.ResultStatus;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * Bounded LOAD console projection for the Worker. Successful iterations update counters only;
 * a progress line is produced on the configured interval, and errors are rate-limited.
 * The line text comes from {@link ExecutionConsoleFormatter}.
 */
public final class LoadConsoleSampler {
    private volatile String runId;
    private final AtomicBoolean runIdAdopted = new AtomicBoolean();
    private final long intervalNanos;
    private final long errorIntervalNanos;
    private final long startedNanos = System.nanoTime();
    private final AtomicLong scheduled = new AtomicLong();
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong passed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong latencyTotalMs = new AtomicLong();
    private final AtomicLong latencySamples = new AtomicLong();
    private final AtomicLong suppressedErrors = new AtomicLong();
    private final AtomicBoolean startEmitted = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Object errorLock = new Object();
    private volatile String model = "unknown";
    private volatile long lastProgressNanos;
    private volatile long lastErrorNanos;

    public LoadConsoleSampler(String runId) {
        this(runId, 5000L, 1000L);
    }

    public LoadConsoleSampler(String runId, long intervalMs, long errorIntervalMs) {
        String supplied = runId == null ? "" : runId.trim();
        if (supplied.isEmpty()) this.runId = "unknown";
        else {
            this.runId = ExecutionConsoleFormatter.token(supplied, "unknown");
            this.runIdAdopted.set(true);
        }
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, intervalMs));
        this.errorIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, errorIntervalMs));
    }

    /** One start line for the run. Later calls return null. */
    public String startLine(String loadModel) {
        return startLine(loadModel, null);
    }

    /**
     * One start line for the run. When the request omitted {@code runId}, {@code observedRunId}
     * is the id the load engine assigned and is used for this line and every later line.
     */
    public String startLine(String loadModel, String observedRunId) {
        adoptRunId(observedRunId);
        if (!startEmitted.compareAndSet(false, true)) return null;
        model = ExecutionConsoleFormatter.token(loadModel, "unknown");
        lastProgressNanos = System.nanoTime();
        return ExecutionConsoleFormatter.loadStart(runId, model, null);
    }

    /** Returns a console line, or null when this iteration should stay off the console. */
    public String onEvent(LoadEvent event) {
        if (event == null || finished.get()) return null;
        adoptRunId(event.runId());
        if (event.model() != null) model = ExecutionConsoleFormatter.token(event.model(), model);
        if (event.scheduled()) scheduled.incrementAndGet();
        if (event.started()) started.incrementAndGet();
        if (event.dropped()) dropped.incrementAndGet();
        if (event.completed()) {
            completed.incrementAndGet();
            if (event.latencyMs() >= 0L) {
                latencyTotalMs.addAndGet(event.latencyMs());
                latencySamples.incrementAndGet();
            }
            ResultStatus status = event.status();
            if (status == ResultStatus.PASS) passed.incrementAndGet();
            else if (status == ResultStatus.FAIL) failed.incrementAndGet();
            else errors.incrementAndGet();
        }
        if (event.dropped() || (event.completed() && !event.success())) {
            String error = errorLine(event);
            if (error != null) return error;
        }
        long now = System.nanoTime();
        if (lastProgressNanos != 0L && now - lastProgressNanos >= intervalNanos) {
            lastProgressNanos = now;
            return progress("PROGRESS", null);
        }
        return null;
    }

    public String finish(String status) {
        if (!finished.compareAndSet(false, true)) return null;
        return progress("COMPLETE", ExecutionConsoleFormatter.token(status, "UNKNOWN"));
    }

    private void adoptRunId(String observed) {
        if (observed == null || observed.trim().isEmpty()) return;
        if (runIdAdopted.compareAndSet(false, true)) runId = ExecutionConsoleFormatter.token(observed, "unknown");
    }

    private String errorLine(LoadEvent event) {
        String status = event.dropped() ? "DROPPED" : event.status() == null ? "ERROR" : event.status().name();
        String line = ExecutionConsoleFormatter.loadError(runId,
                ExecutionConsoleFormatter.token(event.workloadId(), "default"), event.sequence(), status,
                ExecutionConsoleFormatter.token(event.errorType(), ""));
        long now = System.nanoTime();
        synchronized (errorLock) {
            if (errorIntervalNanos == 0L || lastErrorNanos == 0L || now - lastErrorNanos >= errorIntervalNanos) {
                lastErrorNanos = now;
                return line;
            }
            suppressedErrors.incrementAndGet();
            return null;
        }
    }

    private String progress(String label, String terminalStatus) {
        long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        long completeCount = completed.get();
        long samples = latencySamples.get();
        double throughput = elapsedMs == 0L ? 0.0 : completeCount * 1000.0 / elapsedMs;
        double meanLatency = samples == 0L ? 0.0 : (double) latencyTotalMs.get() / samples;
        long active = Math.max(0L, started.get() - completeCount);
        long suppressed = suppressedErrors.getAndSet(0L);
        return ExecutionConsoleFormatter.loadProgress(label, runId, terminalStatus, elapsedMs, scheduled.get(),
                started.get(), completeCount, active, passed.get(), failed.get(), errors.get(), dropped.get(),
                throughput, meanLatency, suppressed);
    }
}
