package att.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded run-scoped resource pool used by load DB/MQ adapters. */
public final class LoadResourcePool<T> implements AutoCloseable {
    private final int maxSize;
    private final long borrowTimeoutMs;
    private final Supplier<T> factory;
    private final Consumer<T> closer;
    private final BlockingQueue<T> idle;
    private final Semaphore permits;
    private final Set<T> leased = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger timeoutCount = new AtomicInteger();
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong waitNanos = new AtomicLong();
    private volatile boolean closed;

    public LoadResourcePool(int maxSize, long borrowTimeoutMs, Supplier<T> factory, Consumer<T> closer) {
        this(maxSize, 0, borrowTimeoutMs, factory, closer);
    }

    public LoadResourcePool(int maxSize, int minIdle, long borrowTimeoutMs,
                            Supplier<T> factory, Consumer<T> closer) {
        if (maxSize < 1) throw new IllegalArgumentException("pool maxSize must be positive");
        if (minIdle < 0 || minIdle > maxSize) throw new IllegalArgumentException("pool minIdle must be from 0 to maxSize");
        if (borrowTimeoutMs < 0L) throw new IllegalArgumentException("pool borrow timeout must not be negative");
        if (factory == null) throw new IllegalArgumentException("pool factory is required");
        this.maxSize = maxSize;
        this.borrowTimeoutMs = borrowTimeoutMs;
        this.factory = factory;
        this.closer = closer == null ? value -> {} : closer;
        this.idle = new ArrayBlockingQueue<T>(maxSize);
        this.permits = new Semaphore(maxSize, true);
        if (minIdle > 0) {
            try { ensureMinIdle(minIdle); }
            catch (RuntimeException error) { close(); throw error; }
        }
    }

    /** Creates the configured idle floor without borrowing or sharing a resource. */
    public synchronized void ensureMinIdle(int minIdle) {
        if (minIdle < 0 || minIdle > maxSize) throw new IllegalArgumentException("pool minIdle must be from 0 to maxSize");
        if (closed) throw new IllegalStateException("resource pool is closed");
        while (!closed && idle.size() < minIdle && total.get() < maxSize) {
            T value;
            try { value = factory.get(); }
            catch (RuntimeException error) { failures.incrementAndGet(); throw error; }
            if (value == null) {
                failures.incrementAndGet();
                throw new IllegalStateException("resource factory returned null");
            }
            total.incrementAndGet();
            created.incrementAndGet();
            idle.offer(value);
        }
    }

    public Lease borrow() throws Exception {
        if (closed) throw new IllegalStateException("resource pool is closed");
        waiting.incrementAndGet();
        boolean acquired;
        long started = System.nanoTime();
        try { acquired = permits.tryAcquire(borrowTimeoutMs, TimeUnit.MILLISECONDS); }
        finally {
            waiting.decrementAndGet();
            waitNanos.addAndGet(System.nanoTime() - started);
        }
        if (!acquired) {
            timeoutCount.incrementAndGet();
            throw new PoolTimeoutException("POOL_TIMEOUT", "Timed out waiting for a load resource");
        }
        try {
            synchronized (this) {
                if (closed) throw new IllegalStateException("resource pool is closed");
                T value = idle.poll();
                if (value == null) {
                    try { value = factory.get(); }
                    catch (RuntimeException error) { failures.incrementAndGet(); throw error; }
                    if (value == null) {
                        failures.incrementAndGet();
                        throw new IllegalStateException("resource factory returned null");
                    }
                    total.incrementAndGet();
                    created.incrementAndGet();
                }
                leased.add(value);
                return new Lease(value);
            }
        } catch (RuntimeException error) {
            permits.release();
            throw error;
        } catch (Exception error) {
            permits.release();
            throw error;
        }
    }

    public int maxSize() { return maxSize; }
    public int total() { return total.get(); }
    public int idle() { return idle.size(); }
    public synchronized int active() { return leased.size(); }
    public int waiting() { return waiting.get(); }
    public int timeoutCount() { return timeoutCount.get(); }
    public int created() { return created.get(); }
    public int discarded() { return discarded.get(); }
    public int failures() { return failures.get(); }
    public long waitDurationMs() { return TimeUnit.NANOSECONDS.toMillis(waitNanos.get()); }
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("maxSize", maxSize); result.put("active", active()); result.put("idle", idle());
        result.put("total", total()); result.put("waiting", waiting()); result.put("waitDurationMs", waitDurationMs());
        result.put("timeoutCount", timeoutCount()); result.put("created", created()); result.put("discarded", discarded());
        result.put("replacementCount", discarded()); result.put("failures", failures());
        return result;
    }

    private void release(T value, boolean invalidate) {
        boolean closeValue = false;
        synchronized (this) {
            if (!leased.remove(value)) return;
            if (invalidate || closed || !idle.offer(value)) {
                total.decrementAndGet();
                if (invalidate || !closed) discarded.incrementAndGet();
                closeValue = true;
            }
        }
        if (closeValue) closeQuietly(value);
        permits.release();
    }

    private void closeQuietly(T value) {
        try { closer.accept(value); } catch (RuntimeException ignored) { }
    }

    @Override public void close() {
        List<T> values = new ArrayList<T>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            idle.drainTo(values);
            values.addAll(leased);
            leased.clear();
            total.set(0);
            permits.release(maxSize);
        }
        for (T value : values) closeQuietly(value);
    }

    public final class Lease implements AutoCloseable {
        private T value;
        private Lease(T value) { this.value = value; }
        public T value() {
            T current = value;
            if (current == null) throw new IllegalStateException("resource lease is closed");
            return current;
        }
        public void invalidate() {
            T current = value;
            if (current == null) return;
            value = null;
            LoadResourcePool.this.release(current, true);
        }
        @Override public void close() {
            T current = value;
            if (current == null) return;
            value = null;
            LoadResourcePool.this.release(current, false);
        }
    }

    public static final class PoolTimeoutException extends Exception {
        private final String code;
        public PoolTimeoutException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
