package att.load;

import att.console.ExecutionConsoleFormatter;
import att.core.ResultStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bounded LOAD console projection for the Worker. Successful iterations update counters only.
 * Progress is emitted at most once per interval, from iteration callbacks or from a timer while
 * the run is active and no callbacks arrive. Errors are rate-limited.
 * The line text comes from {@link ExecutionConsoleFormatter}.
 */
public final class LoadConsoleSampler {
    private volatile String runId;
    private final AtomicBoolean runIdAdopted = new AtomicBoolean();
    private final long intervalNanos;
    private final long errorIntervalNanos;
    private final AtomicLong startedNanos = new AtomicLong();
    private final AtomicLong schedulerStartedNanos = new AtomicLong();
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
    private final AtomicBoolean periodicArmed = new AtomicBoolean();
    private final Object errorLock = new Object();
    private final Consumer<String> periodicSink;
    private final BiConsumer<String,Map<String,Object>> structuredPeriodicSink;
    private final Counters liveCounters = new Counters();
    private final ConcurrentHashMap<String,Counters> liveWorkloads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Counters> livePhases = new ConcurrentHashMap<>();
    private final AtomicBoolean liveWorkloadsTruncated = new AtomicBoolean();
    private volatile String livePhase = "PREPARING";
    private volatile Map<String,Object> latestFailure;
    private volatile ScheduledExecutorService periodic;
    private volatile String model = "unknown";
    private final AtomicLong lastProgressNanos = new AtomicLong();
    private volatile long lastErrorNanos;

    public LoadConsoleSampler(String runId) {
        this(runId, 5000L, 1000L, null);
    }

    public LoadConsoleSampler(String runId, Consumer<String> periodicProgress) {
        this(runId, 5000L, 1000L, periodicProgress);
    }

    public LoadConsoleSampler(String runId, long intervalMs, long errorIntervalMs) {
        this(runId, intervalMs, errorIntervalMs, null);
    }

    public LoadConsoleSampler(String runId, long intervalMs, long errorIntervalMs, Consumer<String> periodicProgress) {
        this(runId,intervalMs,errorIntervalMs,periodicProgress,null);
    }

    public LoadConsoleSampler(String runId,long intervalMs,long errorIntervalMs,Consumer<String> periodicProgress,
                              BiConsumer<String,Map<String,Object>> structuredPeriodicProgress) {
        String supplied = runId == null ? "" : runId.trim();
        if (supplied.isEmpty()) this.runId = "unknown";
        else {
            this.runId = ExecutionConsoleFormatter.token(supplied, "unknown");
            this.runIdAdopted.set(true);
        }
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, intervalMs));
        this.errorIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, errorIntervalMs));
        this.periodicSink = periodicProgress;
        this.structuredPeriodicSink = structuredPeriodicProgress;
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
        startedNanos.compareAndSet(0L,System.nanoTime());
        lastProgressNanos.set(System.nanoTime());
        armPeriodic();
        return ExecutionConsoleFormatter.loadStart(runId, model, null);
    }

    /** Adopts the Engine's scheduler START event without emitting a second START line. */
    public void observePreparedStart(String loadModel, String observedRunId) {
        observePreparedStart(loadModel, observedRunId, null);
    }

    public void observePreparedStart(String loadModel, String observedRunId, String phase) {
        adoptRunId(observedRunId);
        schedulerStartedNanos.compareAndSet(0L,System.nanoTime());
        if(phase!=null)livePhase=ExecutionConsoleFormatter.token(phase,"UNKNOWN");
        if (loadModel != null) model = ExecutionConsoleFormatter.token(loadModel, model);
        if (startEmitted.compareAndSet(false, true)) {
            startedNanos.compareAndSet(0L,System.nanoTime());
            lastProgressNanos.set(System.nanoTime());
            armPeriodic();
        }
    }

    /** Returns a console line, or null when this iteration should stay off the console. */
    public String onEvent(LoadEvent event) {
        if (event == null || finished.get()) return null;
        schedulerStartedNanos.compareAndSet(0L,System.nanoTime());
        liveCounters.observe(event);
        String phaseKey=ExecutionConsoleFormatter.token(event.phase(),"UNKNOWN");
        livePhase=phaseKey;
        if(livePhases.size()<8||livePhases.containsKey(phaseKey))livePhases.computeIfAbsent(phaseKey,ignored->new Counters()).observe(event);
        if(event.workloadId()!=null&&!event.workloadId().trim().isEmpty()) {
            String workloadKey=ExecutionConsoleFormatter.token(event.workloadId(),"unknown");
            if(liveWorkloads.size()<32||liveWorkloads.containsKey(workloadKey))liveWorkloads.computeIfAbsent(workloadKey,ignored->new Counters()).observe(event);
            else liveWorkloadsTruncated.set(true);
        }
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
            Map<String,Object> failure=new LinkedHashMap<>();
            failure.put("status",event.dropped()?"DROPPED":event.status()==null?"ERROR":event.status().name());
            failure.put("capturedAt",Instant.now().toString());
            String type=event.errorType();if(type!=null && type.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))failure.put("code",type);
            latestFailure=failure;
            String error = errorLine(event);
            if (error != null) return error;
        }
        if (claimProgress(System.nanoTime())) return progress("PROGRESS", null);
        return null;
    }

    public String finish(String status) {
        if (!finished.compareAndSet(false, true)) return null;
        stopPeriodic();
        return progress("COMPLETE", ExecutionConsoleFormatter.token(status, "UNKNOWN"));
    }

    /** Bounded, sanitized live counters for the Server's coalesced Job progress snapshot. */
    public Map<String,Object> snapshot() {
        long began=schedulerStartedNanos.get();long elapsedMs=began==0L?0L:Math.max(0L,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("schemaVersion","att-load-live-progress/v1");
        snapshot.put("capturedAt",Instant.now().toString());
        snapshot.put("elapsedMs",elapsedMs);
        snapshot.put("phase",livePhase);
        snapshot.put("counts",liveCounters.toMap());
        Map<String,Object> rates=new LinkedHashMap<>();
        rates.put("completedTps",rate(liveCounters.completed.get(),elapsedMs));
        rates.put("successfulTps",rate(liveCounters.passed.get(),elapsedMs));
        snapshot.put("rates",rates);
        snapshot.put("latency",liveCounters.latencyMap());
        if(latestFailure!=null)snapshot.put("latestFailure",latestFailure);
        Map<String,Object> measured=new LinkedHashMap<>();
        for(String phase:new String[]{"RAMP_UP","STEADY","RAMP_DOWN"}) {
            Counters counters=livePhases.get(phase);if(counters!=null)mergeCounters(measured,counters);
        }
        snapshot.put("measuredCounts",measured);
        Map<String,Object> phases=new TreeMap<>();livePhases.forEach((key,value)->phases.put(key,value.toMap()));snapshot.put("phases",phases);
        Map<String,Object> workloads=new TreeMap<>();liveWorkloads.forEach((key,value)->{
            Map<String,Object> workload=value.toMap();workload.put("latency",value.latencyMap());
            workloads.put(key,workload);
        });
        snapshot.put("workloads",workloads);snapshot.put("workloadsTruncated",liveWorkloadsTruncated.get());
        return snapshot;
    }

    private static void mergeCounters(Map<String,Object> target,Counters counters) {
        for(Map.Entry<String,Object> entry:counters.toMap().entrySet()) {
            if("schedulerLagMeanMs".equals(entry.getKey()))continue;
            target.merge(entry.getKey(),entry.getValue(),(left,right)->left instanceof Number&&right instanceof Number
                    ?((Number)left).longValue()+((Number)right).longValue():right);
        }
    }
    private static double rate(long count,long elapsedMs) {return elapsedMs<=0L?0.0:count*1000.0/elapsedMs;}

    private static final class Counters {
        final AtomicLong scheduled=new AtomicLong(),started=new AtomicLong(),completed=new AtomicLong(),passed=new AtomicLong();
        final AtomicLong sutFailures=new AtomicLong(),runtimeErrors=new AtomicLong(),dropped=new AtomicLong(),other=new AtomicLong();
        final AtomicLong latencyTotalMs=new AtomicLong(),latencySamples=new AtomicLong(),schedulerLagTotalMs=new AtomicLong(),schedulerLagSamples=new AtomicLong();
        final AtomicLongArray latencyHistogram=new AtomicLongArray(64);
        void observe(LoadEvent event) {
            if(event.scheduled())scheduled.incrementAndGet();
            if(event.started())started.incrementAndGet();
            if(event.dropped())dropped.incrementAndGet();
            if(event.started()||event.dropped()) {schedulerLagTotalMs.addAndGet(Math.max(0L,event.schedulerLagMs()));schedulerLagSamples.incrementAndGet();}
            if(event.completed()) {
                completed.incrementAndGet();
                if(event.status()==ResultStatus.PASS)passed.incrementAndGet();
                else if(event.status()==ResultStatus.FAIL)sutFailures.incrementAndGet();
                else if(event.status()==ResultStatus.ERROR||event.status()==null)runtimeErrors.incrementAndGet();
                else other.incrementAndGet();
                if(event.latencyMs()>=0L) {
                    latencyTotalMs.addAndGet(event.latencyMs());latencySamples.incrementAndGet();
                    int bucket=event.latencyMs()==0L?0:64-Long.numberOfLeadingZeros(event.latencyMs());
                    latencyHistogram.incrementAndGet(Math.min(63,bucket));
                }
            }
        }
        Map<String,Object> toMap() {
            long scheduledCount=scheduled.get(),startedCount=started.get(),completedCount=completed.get(),droppedCount=dropped.get();
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("scheduled",scheduledCount);value.put("started",startedCount);value.put("completed",completedCount);value.put("passed",passed.get());
            value.put("sutFailures",sutFailures.get());value.put("runtimeErrors",runtimeErrors.get());value.put("dropped",droppedCount);value.put("other",other.get());
            value.put("active",Math.max(0L,startedCount-completedCount));value.put("queued",Math.max(0L,scheduledCount-startedCount-droppedCount));
            value.put("schedulerLagMeanMs",schedulerLagSamples.get()==0?null:(double)schedulerLagTotalMs.get()/schedulerLagSamples.get());
            return value;
        }
        Map<String,Object> latencyMap() {
            long samples=latencySamples.get();Map<String,Object> value=new LinkedHashMap<>();
            value.put("sampleCount",samples);value.put("meanMs",samples==0?null:(double)latencyTotalMs.get()/samples);
            value.put("p95Ms",percentile(.95,samples));value.put("p99Ms",percentile(.99,samples));
            value.put("percentileMethod","bounded log histogram estimate");return value;
        }
        Long percentile(double quantile,long samples) {
            if(samples==0)return null;long threshold=(long)Math.ceil(samples*quantile),seen=0;
            for(int bucket=0;bucket<latencyHistogram.length();bucket++) {
                seen+=latencyHistogram.get(bucket);if(seen>=threshold)return bucket==0?0L:bucket>=63?Long.MAX_VALUE:(1L<<bucket)-1L;
            }
            return null;
        }
    }

    private void armPeriodic() {
        if ((periodicSink == null && structuredPeriodicSink == null) || !periodicArmed.compareAndSet(false, true)) return;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "att-load-console-sampler");
                thread.setDaemon(true);
                return thread;
            }
        });
        periodic = scheduler;
        long intervalMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(intervalNanos));
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                try { publishPeriodic(); }
                catch (RuntimeException ignored) { /* One failed snapshot must not cancel later intervals. */ }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void publishPeriodic() {
        if (finished.get() || !claimProgress(System.nanoTime()) || finished.get()) return;
        String line = progress("PROGRESS", null);
        if (line != null) {
            if(periodicSink!=null)periodicSink.accept(line);
            if(structuredPeriodicSink!=null)structuredPeriodicSink.accept(line,snapshot());
        }
    }

    private void stopPeriodic() {
        ScheduledExecutorService scheduler = periodic;
        if (scheduler == null) return;
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    /** One progress line per interval across iteration threads and the periodic timer. */
    private boolean claimProgress(long now) {
        while (true) {
            long previous = lastProgressNanos.get();
            if (previous == 0L || now - previous < intervalNanos) return false;
            if (lastProgressNanos.compareAndSet(previous, now)) return true;
        }
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
        long began = startedNanos.get();
        long elapsedMs = began == 0L ? 0L : Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
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
