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
                    closing++;
                }
                else {
                    created.incrementAndGet();
                    if (!idle.offer(value)) {
                        closing++;
                        closeValue = true;
                    } else value = null;
                }
                creating--;
                notifyAll();
            }
            if (closeValue) closeTracked(value);
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
        long budget = TimeUnit.MILLISECONDS.toNanos(borrowTimeoutMs);
        try {
            acquired = false;
            do {
                if (closed) throw new IllegalStateException("resource pool is closed");
                long remaining = Math.max(0L, budget - (System.nanoTime() - started));
                acquired = permits.tryAcquire(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (acquired || remaining == 0L) break;
            } while (System.nanoTime() - started < budget);
        } catch (InterruptedException | RuntimeException failure) {
            waiting.decrementAndGet();
            waitNanos.addAndGet(System.nanoTime() - started);
            throw failure;
        }
        if (!acquired) {
            waiting.decrementAndGet();
            waitNanos.addAndGet(System.nanoTime() - started);
            throw timeout(started);
        }
        T value = null;
        boolean reserved = false;
        boolean reservationReleased = false;
        boolean waitingRecorded = false;
        try {
            synchronized (this) {
                boolean waitedForCapacity = false;
                while (true) {
                    if (closed) throw new IllegalStateException("resource pool is closed");
                    if (waitedForCapacity && System.nanoTime() - started >= budget) throw timeout(started);
                    value = idle.poll();
                    if (value != null) {
                        leased.add(value);
                        break;
                    }
                    if (total.get() < maxSize) {
                        total.incrementAndGet();
                        creating++;
                        reserved = true;
                        break;
                    }
                    long remaining = budget - (System.nanoTime() - started);
                    if (remaining <= 0L) {
                        throw timeout(started);
                    }
                    waitedForCapacity = true;
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
            }
            waiting.decrementAndGet();
            waitNanos.addAndGet(System.nanoTime() - started);
            waitingRecorded = true;
            if (!reserved) return new Lease(value);
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
            if (!waitingRecorded) { waiting.decrementAndGet(); waitNanos.addAndGet(System.nanoTime() - started); }
            if (reserved && !reservationReleased) synchronized (this) {
                creating--; total.decrementAndGet(); notifyAll();
            }
            permits.release();
            throw error;
        } catch (Exception error) {
            if (!waitingRecorded) { waiting.decrementAndGet(); waitNanos.addAndGet(System.nanoTime() - started); }
            if (reserved && !reservationReleased) synchronized (this) {
                creating--; total.decrementAndGet(); notifyAll();
            }
            permits.release();
            throw error;
        } catch (Error error) {
            if (!waitingRecorded) { waiting.decrementAndGet(); waitNanos.addAndGet(System.nanoTime() - started); }
            if (reserved && !reservationReleased) synchronized (this) {
                creating--; total.decrementAndGet(); notifyAll();
            }
            permits.release();
            throw error;
        }
    }

    private synchronized PoolTimeoutException timeout(long started) {
        timeoutCount.incrementAndGet();
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("maxSize", maxSize); snapshot.put("total", total.get());
        snapshot.put("idle", idle.size()); snapshot.put("active", leased.size());
        snapshot.put("creating", creating); snapshot.put("closing", closing);
        snapshot.put("waiting", waiting.get()); snapshot.put("timeoutCount", timeoutCount.get());
        snapshot.put("closed", closed); snapshot.put("budgetMs", borrowTimeoutMs);
        snapshot.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return new PoolTimeoutException("POOL_TIMEOUT", "Timed out waiting for a load resource", snapshot);
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
    public int closeFailureCount() { synchronized (closeFailures) { return closeFailures.size(); } }
    public long waitDurationMs() { return TimeUnit.NANOSECONDS.toMillis(waitNanos.get()); }
    public synchronized Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("maxSize", maxSize); result.put("active", active()); result.put("idle", idle());
        result.put("total", total()); result.put("creating", creating); result.put("closing", closing);
        result.put("closed", closed); result.put("waiting", waiting()); result.put("waitDurationMs", waitDurationMs());
        result.put("timeoutCount", timeoutCount()); result.put("created", created()); result.put("discarded", discarded());
        result.put("replacementCount", discarded()); result.put("failures", failures());
        Map<String, Object> saturation = new LinkedHashMap<String, Object>();
        saturation.put("usage", active()); saturation.put("limit", maxSize); saturation.put("threshold", 0.8d);
        result.put("limitUsage", java.util.Collections.<String, Object>singletonMap("mq.pool.activeConnections", saturation));
        return result;
    }

    private void release(T value, boolean invalidate) {
        boolean closeValue = false;
        synchronized (this) {
            if (!leased.remove(value)) return;
            if (invalidate || closed || !idle.offer(value)) {
                if (invalidate || !closed) discarded.incrementAndGet();
                closing++;
                closeValue = true;
            }
            notifyAll();
        }
        if (closeValue) closeTracked(value);
        permits.release();
    }

    private void closeTracked(T value) {
        boolean confirmed = false;
        try { closer.accept(value); confirmed = true; }
        catch (RuntimeException | Error failure) { closeFailures.add(new IllegalStateException("LOAD_RESOURCE_TEARDOWN_FAILED", failure)); }
        finally {
            synchronized (this) {
                // A failed physical disconnect remains charged: opening a replacement
                // could otherwise exceed the configured physical-session limit.
                if (confirmed) total.decrementAndGet();
                closing--;
                notifyAll();
            }
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
                // Keep each physical session charged until its close succeeds.
                closing += values.size();
                notifyAll();
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
        private final Map<String, Object> snapshot;
        public PoolTimeoutException(String code, String message) { this(code, message, Collections.<String, Object>emptyMap()); }
        private PoolTimeoutException(String code, String message, Map<String, Object> snapshot) {
            super(message); this.code = code; this.snapshot = Collections.unmodifiableMap(snapshot);
        }
        public String code() { return code; }
        public Map<String, Object> snapshot() { return snapshot; }
    }
}
