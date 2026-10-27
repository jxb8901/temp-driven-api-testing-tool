package att.server;

import att.server.api.ArtifactArchiveMetrics;
import java.lang.management.ManagementFactory;

/** One writer attempt, scoped to its thread; no paths, values or exception text are retained. */
final class ArtifactMetricsRecorder {
    static final ThreadLocal<ArtifactMetricsRecorder> CURRENT = new ThreadLocal<>();
    final ArtifactArchiveMetrics value = new ArtifactArchiveMetrics();
    private final long started = System.nanoTime();
    private final Long cpu;
    private final Long allocated;
    private final boolean measure;
    private long phaseStarted = started;
    String phase;

    ArtifactMetricsRecorder(long queued, long attempts) {
        this(queued, attempts, true);
    }
    ArtifactMetricsRecorder(long queued, long attempts, boolean measure) {
        this.measure=measure;
        cpu=measure?cpu():null;
        allocated=measure?allocated():null;
        value.attempts = attempts;
        value.retries = Math.max(0, attempts - 1);
        value.outcome = "RUNNING";
        value.queueWaitMs = millis(Math.max(0, started - queued));
    }

    void phase(String next) {
        long now = System.nanoTime();
        if (phase != null) {
            double elapsed = millis(now - phaseStarted);
            switch (phase) {
                case "scan_validate_preserve" -> value.scanValidationPreservationMs = add(value.scanValidationPreservationMs, elapsed);
                case "zip_write" -> value.zipWriteMs = add(value.zipWriteMs, elapsed);
                case "verification_fsync" -> value.verificationFsyncMs = add(value.verificationFsyncMs, elapsed);
                case "cleanup" -> value.cleanupMs = add(value.cleanupMs, elapsed);
                default -> { }
            }
        }
        phase = next;
        phaseStarted = now;
    }

    void fail(String reason) {
        value.failurePhase = phase == null ? "publication" : phase;
        value.failureReason = reason;
        value.outcome = "FAILED";
    }

    void finish() {
        phase(null);
        value.publicationWallMs = millis(System.nanoTime() - started);
        Long endCpu = measure?cpu():null, endAllocated = measure?allocated():null;
        if (cpu != null && endCpu != null && endCpu >= cpu) value.writerCpuMs = millis(endCpu - cpu);
        if (allocated != null && endAllocated != null && endAllocated >= allocated) value.writerAllocatedBytes = endAllocated - allocated;
        if (value.zipBytes != null && value.zipBytes > 0 && value.includedBytes != null)
            value.compressionRatio = (double)value.includedBytes / value.zipBytes;
    }

    void source(long bytes) {
        value.sourceFiles = (value.sourceFiles == null ? 0 : value.sourceFiles) + 1;
        long prior=value.sourceBytes==null?0:value.sourceBytes;
        value.sourceBytes=bytes>Long.MAX_VALUE-prior?Long.MAX_VALUE:prior+bytes;
    }

    private static double add(Double prior, double next) { return (prior == null ? 0 : prior) + next; }
    private static double millis(long nanos) { return nanos / 1_000_000.0; }
    private static Long cpu() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (!bean.isCurrentThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) return null;
            long result = bean.getCurrentThreadCpuTime();
            return result < 0 ? null : result;
        } catch (RuntimeException unavailable) { return null; }
    }
    private static Long allocated() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof com.sun.management.ThreadMXBean allocation)
                    || !allocation.isThreadAllocatedMemorySupported() || !allocation.isThreadAllocatedMemoryEnabled()) return null;
            long result = allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
            return result < 0 ? null : result;
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }
}
