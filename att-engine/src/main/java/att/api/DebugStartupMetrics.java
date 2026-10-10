package att.api;

import java.util.concurrent.atomic.AtomicReference;

/** CLI-side monotonic timestamps that the Engine cannot observe on its own. */
public final class DebugStartupMetrics {
    private final long javaMainEntryNanos;
    private final long argumentParseNanos;
    private final AtomicReference<Long> firstConsoleEventNanos = new AtomicReference<Long>();

    public DebugStartupMetrics(long javaMainEntryNanos, long argumentParseNanos) {
        this.javaMainEntryNanos = javaMainEntryNanos;
        this.argumentParseNanos = Math.max(0L, argumentParseNanos);
    }

    public long javaMainEntryNanos() { return javaMainEntryNanos; }
    public long argumentParseNanos() { return argumentParseNanos; }

    /** Records the completion of the first progress event actually written to a console. */
    public void markFirstConsoleEvent() { firstConsoleEventNanos.compareAndSet(null, Long.valueOf(System.nanoTime())); }
    public boolean hasFirstConsoleEvent() { return firstConsoleEventNanos.get() != null; }
    public long firstConsoleEventNanos() {
        Long recorded = firstConsoleEventNanos.get();
        return recorded == null ? 0L : recorded.longValue();
    }
}
