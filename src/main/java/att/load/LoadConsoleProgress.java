package att.load;

import att.core.ResultStatus;

import java.io.PrintStream;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Low-overhead, bounded live progress view for load events. */
public final class LoadConsoleProgress implements LoadEventListener, AutoCloseable {
    private static final long DEFAULT_INTERVAL_MS = 5000L;
    private static final long DEFAULT_ERROR_INTERVAL_MS = 1000L;

    private final String runId;
    private final String model;
    private final PrintStream output;
    private final boolean verbose;
    private final long errorIntervalNanos;
    private final long startedNanos = System.nanoTime();
    private final ScheduledExecutorService reporter;
    private final AtomicBoolean finished = new AtomicBoolean();
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
    private final Object errorLock = new Object();
    private volatile long lastErrorNanos;
    private final Thread shutdownHook;

    public LoadConsoleProgress(String runId, LoadScenario scenario, PrintStream output) {
        this(runId, scenario, output, DEFAULT_INTERVAL_MS, DEFAULT_ERROR_INTERVAL_MS, true, true);
    }

    public LoadConsoleProgress(String runId, LoadScenario scenario, PrintStream output, boolean verbose) {
        this(runId, scenario, output, DEFAULT_INTERVAL_MS, DEFAULT_ERROR_INTERVAL_MS, true, verbose);
    }

    LoadConsoleProgress(String runId, LoadScenario scenario, PrintStream output,
                        long intervalMs, long errorIntervalMs, boolean installShutdownHook) {
        this(runId, scenario, output, intervalMs, errorIntervalMs, installShutdownHook, true);
    }

    LoadConsoleProgress(String runId, LoadScenario scenario, PrintStream output,
                        long intervalMs, long errorIntervalMs, boolean installShutdownHook, boolean verbose) {
        if (scenario == null) throw new IllegalArgumentException("Load progress requires a scenario");
        if (output == null) throw new IllegalArgumentException("Load progress requires an output stream");
        this.runId = safe(runId, "unknown");
        this.model = scenario.model().wireName();
        this.output = output;
        this.verbose = verbose;
        this.errorIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, errorIntervalMs));
        reporter = verbose ? Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "att-load-console-progress");
                thread.setDaemon(true);
                return thread;
            }
        }) : null;
        if (verbose) {
            emit("[LOAD] START runId=" + this.runId + " model=" + model + " " + configuration(scenario));
            reporter.scheduleAtFixedRate(new Runnable() {
                @Override public void run() { printProgress("PROGRESS", null); }
            }, Math.max(1L, intervalMs), Math.max(1L, intervalMs), TimeUnit.MILLISECONDS);
        }
        if (installShutdownHook) {
            Thread hook = new Thread(new Runnable() {
                @Override public void run() { finish("CANCELLED"); }
            }, "att-load-console-shutdown");
            Thread registered = null;
            try {
                Runtime.getRuntime().addShutdownHook(hook);
                registered = hook;
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down; the active hook is unnecessary.
            } catch (SecurityException ignored) {
                // Console progress remains useful even when hook registration is denied.
            }
            shutdownHook = registered;
        } else shutdownHook = null;
    }

    @Override public void onEvent(LoadEvent event) {
        if (event == null || finished.get()) return;
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
        if (event.dropped() || (event.completed() && !event.success())) reportError(event);
    }

    /** Emits exactly one final summary, including when invoked from the JVM shutdown hook. */
    public void finish(String status) {
        if (!finished.compareAndSet(false, true)) return;
        if (reporter != null) reporter.shutdownNow();
        printProgress(verbose ? "COMPLETE" : "SUMMARY", safe(status, "UNKNOWN"));
        removeShutdownHook();
    }

    @Override public void close() {
        finish("CANCELLED");
    }

    private void reportError(LoadEvent event) {
        if (!verbose) return;
        String status = event.dropped() ? "DROPPED"
                : event.status() == null ? "ERROR" : event.status().name();
        StringBuilder line = new StringBuilder("[LOAD] ERROR runId=").append(runId)
                .append(" workload=").append(safe(event.workloadId(), "default"))
                .append(" sequence=").append(event.sequence())
                .append(" status=").append(status);
        String errorType = safe(event.errorType(), "");
        if (!errorType.isEmpty()) line.append(" errorType=").append(errorType);
        long now = System.nanoTime();
        synchronized (errorLock) {
            if (errorIntervalNanos == 0L || lastErrorNanos == 0L || now - lastErrorNanos >= errorIntervalNanos) {
                lastErrorNanos = now;
                emit(line.toString());
            } else suppressedErrors.incrementAndGet();
        }
    }

    private void printProgress(String label, String terminalStatus) {
        if ("PROGRESS".equals(label) && finished.get()) return;
        if ("SUMMARY".equals(label)) {
            emit("[LOAD] SUMMARY runId=" + runId + " model=" + model + " status=" + terminalStatus
                    + " completed=" + completed.get() + " pass=" + passed.get() + " fail=" + failed.get()
                    + " error=" + errors.get() + " dropped=" + dropped.get());
            return;
        }
        long elapsedMs = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        long completeCount = completed.get();
        long samples = latencySamples.get();
        double throughput = elapsedMs == 0L ? 0.0 : completeCount * 1000.0 / elapsedMs;
        double meanLatency = samples == 0L ? 0.0 : (double) latencyTotalMs.get() / samples;
        long active = Math.max(0L, started.get() - completeCount);
        long suppressed = suppressedErrors.getAndSet(0L);
        StringBuilder line = new StringBuilder("[LOAD] ").append(label)
                .append(" runId=").append(runId);
        if (terminalStatus != null) line.append(" status=").append(terminalStatus);
        line.append(" elapsedMs=").append(elapsedMs)
                .append(" scheduled=").append(scheduled.get())
                .append(" started=").append(started.get())
                .append(" completed=").append(completeCount)
                .append(" active=").append(active)
                .append(" pass=").append(passed.get())
                .append(" fail=").append(failed.get())
                .append(" error=").append(errors.get())
                .append(" dropped=").append(dropped.get())
                .append(" throughputPerSec=").append(String.format(Locale.ROOT, "%.2f", throughput))
                .append(" meanLatencyMs=").append(String.format(Locale.ROOT, "%.2f", meanLatency));
        if (suppressed > 0L) line.append(" suppressedErrors=").append(suppressed);
        emit(line.toString());
    }

    private void emit(String line) {
        synchronized (output) {
            output.println(line);
            output.flush();
        }
    }

    private void removeShutdownHook() {
        if (shutdownHook == null) return;
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
        catch (IllegalStateException ignored) { /* JVM shutdown is already in progress. */ }
        catch (SecurityException ignored) { /* The final line has already been emitted. */ }
    }

    private static String configuration(LoadScenario scenario) {
        StringBuilder result = new StringBuilder("workloads=").append(scenario.workloads().size());
        if (scenario.model() == LoadScenario.Model.CLOSED) result.append(" users=").append(scenario.configuredUsers());
        else result.append(" arrivalRatePerSec=").append(String.format(Locale.ROOT, "%.2f", scenario.configuredArrivalRatePerSecond()))
                .append(" maxConcurrent=").append(scenario.configuredMaxConcurrent());
        result.append(" durationMs=").append(scenario.duration().toMillis());
        return result.toString();
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }
}
