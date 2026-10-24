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
    /** Slots reserved by borrowers whose factory calls are currently outside the pool monitor. */
    private int creating;
    /** Physical closers running outside the pool monitor. */
    private int closing;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger timeoutCount = new AtomicInteger();
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong waitNanos = new AtomicLong();
    private final List<RuntimeException> closeFailures = Collections.synchronizedList(new ArrayList<RuntimeException>());
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
    public void ensureMinIdle(int minIdle) {
        try { ensureMinIdleBounded(minIdle, Long.MAX_VALUE); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("resource preparation interrupted", interrupted);
        } catch (java.util.concurrent.TimeoutException impossible) {
            throw new IllegalStateException("resource preparation timed out", impossible);
        }
    }

    /** Creates an idle floor while honoring the run-wide preparation deadline. */
    public void ensureMinIdle(int minIdle, long deadlineNanos)
            throws InterruptedException, java.util.concurrent.TimeoutException {
        ensureMinIdleBounded(minIdle, deadlineNanos);
    }

    private void ensureMinIdleBounded(int minIdle, long deadlineNanos)
            throws InterruptedException, java.util.concurrent.TimeoutException {
        if (minIdle < 0 || minIdle > maxSize) throw new IllegalArgumentException("pool minIdle must be from 0 to maxSize");
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("resource preparation cancelled");
            if (System.nanoTime() >= deadlineNanos) throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT");
            synchronized (this) {
                if (closed) throw new IllegalStateException("resource pool is closed");
                if (idle.size() + creating >= minIdle || total.get() >= maxSize) return;
                total.incrementAndGet();
                creating++;
            }
            T value;
            try { value = factory.get(); }
            catch (RuntimeException error) {
                synchronized (this) { creating--; total.decrementAndGet(); notifyAll(); }
                failures.incrementAndGet(); throw error;
            } catch (Error error) {
                synchronized (this) { creating--; total.decrementAndGet(); notifyAll(); }
                failures.incrementAndGet(); throw error;
            }
            if (value == null) {
                synchronized (this) { creating--; total.decrementAndGet(); notifyAll(); }
                failures.incrementAndGet();
                throw new IllegalStateException("resource factory returned null");
            }
            boolean interrupted = Thread.currentThread().isInterrupted();
            boolean timedOut = System.nanoTime() >= deadlineNanos;
            boolean closedDuringConnect;
            boolean closeValue;
            synchronized (this) {
                closedDuringConnect = closed;
                closeValue = closedDuringConnect || timedOut || interrupted;
                if (closeValue) {
                    total.decrementAndGet();
                    closing++;
                }
                else {
                    created.incrementAndGet();
                    if (!idle.offer(value)) {
                        total.decrementAndGet();
                        closing++;
                        closeValue = true;
                    } else value = null;
                }
                if (!closeValue) { creating--; notifyAll(); }
            }
            if (closeValue) {
                try { closeTracked(value); }
                finally { synchronized (this) { creating--; notifyAll(); } }
            }
            if (interrupted) throw new InterruptedException("resource preparation cancelled");
            if (timedOut) throw new java.util.concurrent.TimeoutException("LOAD_PREPARATION_TIMEOUT");
            if (closedDuringConnect) throw new IllegalStateException("resource pool is closed");
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
        T value = null;
        boolean reserved = false;
        boolean reservationReleased = false;
        try {
            synchronized (this) {
                if (closed) throw new IllegalStateException("resource pool is closed");
                value = idle.poll();
                if (value == null) {
                    // The permit bounds active borrowers; reserve the pool slot
                    // before releasing the monitor so concurrent cold growth
                    // cannot exceed maxSize.
                    if (total.get() >= maxSize) throw new IllegalStateException("resource pool capacity invariant violated");
                    total.incrementAndGet();
                    creating++;
                    reserved = true;
                }
            }
            if (reserved) {
                try { value = factory.get(); }
                catch (RuntimeException error) {
                    failures.incrementAndGet();
                    synchronized (this) { creating--; total.decrementAndGet(); reservationReleased = true; notifyAll(); }
                    throw error;
                } catch (Error error) {
                    failures.incrementAndGet();
                    synchronized (this) { creating--; total.decrementAndGet(); reservationReleased = true; notifyAll(); }
                    throw error;
                }
                if (value == null) {
                    failures.incrementAndGet();
                    synchronized (this) { creating--; total.decrementAndGet(); reservationReleased = true; notifyAll(); }
                    throw new IllegalStateException("resource factory returned null");
                }
            }
            boolean closeCreated = false;
            synchronized (this) {
                if (reserved) { creating--; reservationReleased = true; }
                if (closed) {
                    if (reserved) total.decrementAndGet();
                    if (value != null) { closing++; closeCreated = true; }
                    notifyAll();
                } else {
                    if (reserved) created.incrementAndGet();
                    leased.add(value);
                    notifyAll();
                    return new Lease(value);
                }
            }
            if (closeCreated) closeTracked(value);
            throw new IllegalStateException("resource pool is closed");
        } catch (RuntimeException error) {
            if (reserved && !reservationReleased) synchronized (this) {
                creating--; total.decrementAndGet(); notifyAll();
            }
            permits.release();
            throw error;
        } catch (Exception error) {
            if (reserved && !reservationReleased) synchronized (this) {
                creating--; total.decrementAndGet(); notifyAll();
            }
            permits.release();
            throw error;
        } catch (Error error) {
            if (reserved && !reservationReleased) synchronized (this) {
                creating--; total.decrementAndGet(); notifyAll();
            }
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
                closing++;
                closeValue = true;
            }
        }
        if (closeValue) closeTracked(value);
        permits.release();
    }

    private void closeTracked(T value) {
        try { closer.accept(value); }
        catch (RuntimeException | Error failure) { closeFailures.add(new IllegalStateException("LOAD_RESOURCE_TEARDOWN_FAILED", failure)); }
        finally {
            synchronized (this) { closing--; notifyAll(); }
        }
    }

    @Override public void close() {
        List<T> values = new ArrayList<T>();
        synchronized (this) {
            if (!closed) {
                closed = true;
                idle.drainTo(values);
                values.addAll(leased);
                leased.clear();
                // Keep capacity charged for factories and physical closers that
                // have not settled; their resulting sessions are part of teardown.
                total.set(creating);
                closing += values.size();
                permits.release(maxSize);
            }
        }
        for (T value : values) closeTracked(value);
        boolean interrupted = false;
        synchronized (this) {
            while (creating > 0 || closing > 0) {
                try { wait(); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (!closeFailures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "LOAD_RESOURCE_TEARDOWN_FAILED: one or more pooled resources did not confirm close");
            synchronized (closeFailures) {
                for (RuntimeException closeFailure : closeFailures) failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
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
