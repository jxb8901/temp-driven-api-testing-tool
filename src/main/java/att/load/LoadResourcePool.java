package att.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded run-scoped resource pool used by Load DB/MQ adapters. */
public final class LoadResourcePool<T> implements AutoCloseable {
    private final int maxSize; private final long borrowTimeoutMs; private final Supplier<T> factory; private final Consumer<T> closer;
    private final BlockingQueue<T> idle; private final AtomicInteger total = new AtomicInteger(); private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger timeoutCount = new AtomicInteger(), created = new AtomicInteger(), discarded = new AtomicInteger();
    private volatile boolean closed;
    public LoadResourcePool(int maxSize, long borrowTimeoutMs, Supplier<T> factory, Consumer<T> closer) {
        if (maxSize < 1) throw new IllegalArgumentException("pool maxSize must be positive");
        if (borrowTimeoutMs < 0L) throw new IllegalArgumentException("pool borrow timeout must not be negative");
        if (factory == null) throw new IllegalArgumentException("pool factory is required");
        this.maxSize = maxSize; this.borrowTimeoutMs = borrowTimeoutMs; this.factory = factory; this.closer = closer == null ? value -> {} : closer; this.idle = new ArrayBlockingQueue<T>(maxSize);
    }
    public Lease borrow() throws Exception {
        if (closed) throw new IllegalStateException("resource pool is closed");
        T value = idle.poll();
        if (value == null) synchronized (this) {
            value = idle.poll();
            if (value == null && total.get() < maxSize) { value = factory.get(); if (value != null) { total.incrementAndGet(); created.incrementAndGet(); } }
        }
        if (value == null) { waiting.incrementAndGet(); try { value = idle.poll(borrowTimeoutMs, TimeUnit.MILLISECONDS); } finally { waiting.decrementAndGet(); } }
        if (value == null) { timeoutCount.incrementAndGet(); throw new PoolTimeoutException("POOL_TIMEOUT", "Timed out waiting for a load resource"); }
        return new Lease(value);
    }
    public int maxSize() { return maxSize; } public int total() { return total.get(); } public int idle() { return idle.size(); } public int active() { return total.get() - idle.size(); } public int waiting() { return waiting.get(); } public int timeoutCount() { return timeoutCount.get(); } public int created() { return created.get(); } public int discarded() { return discarded.get(); }
    public synchronized void invalidate(T value) { if (value == null) return; total.decrementAndGet(); discarded.incrementAndGet(); try { closer.accept(value); } catch (RuntimeException ignored) { } }
    @Override public synchronized void close() { if (closed) return; closed = true; List<T> values = new ArrayList<T>(); idle.drainTo(values); for (T value : values) { total.decrementAndGet(); try { closer.accept(value); } catch (RuntimeException ignored) { } } }
    public final class Lease implements AutoCloseable {
        private T value; private boolean invalid;
        private Lease(T value) { this.value = value; }
        public T value() { if (value == null) throw new IllegalStateException("resource lease is closed"); return value; }
        public void invalidate() { if (value != null) { invalid = true; LoadResourcePool.this.invalidate(value); value = null; } }
        @Override public void close() { if (value == null) return; T returned = value; value = null; if (invalid || closed) { LoadResourcePool.this.invalidate(returned); return; } if (!idle.offer(returned)) LoadResourcePool.this.invalidate(returned); }
    }
    public static final class PoolTimeoutException extends Exception { private final String code; public PoolTimeoutException(String code, String message) { super(message); this.code = code; } public String code() { return code; } }
}
