package att.load;

import att.config.MqPoolLifecycle;
import att.exec.MqTransport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded run-scoped resource pool used by load DB/MQ adapters. */
public final class LoadResourcePool<T> implements AutoCloseable {
    private final int maxSize;
    private final long borrowTimeoutMs;
    private final Supplier<T> factory;
    private final Consumer<T> closer;
    private final Health health;
    private final IdleValidator<T> idleValidator;
    private final BlockingQueue<T> idle;
    private final Semaphore permits;
    private final Set<T> leased = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
    private final Map<T, Session> sessions = new IdentityHashMap<T, Session>();
    /** Sessions reserved before {@code factory.get()} returns a connection object. */
    private final ArrayDeque<Session> creatingSessions = new ArrayDeque<Session>();
    private final ArrayDeque<Session> confirmedHistory = new ArrayDeque<Session>();
    private final ArrayDeque<Session> failedHistory = new ArrayDeque<Session>();
    private final AtomicInteger total = new AtomicInteger();
    /** Slots reserved by borrowers whose factory calls are currently outside the pool monitor. */
    private int creating;
    /** Physical closers running outside the pool monitor. */
    private int closing;
    /** Idle checks running outside the pool monitor. */
    private int validating;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger timeoutCount = new AtomicInteger();
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    /** Disconnect workers that never started. Each remains in {@code closing} and in {@code closeFailures}. */
    private final AtomicInteger disconnectStartFailures = new AtomicInteger();
    private final AtomicInteger validationAttempts = new AtomicInteger();
    private final AtomicInteger validationFailures = new AtomicInteger();
    private final AtomicInteger validationUnsupported = new AtomicInteger();
    private final AtomicInteger recycled = new AtomicInteger();
    private final AtomicLong waitNanos = new AtomicLong();
    private final List<RuntimeException> closeFailures = Collections.synchronizedList(new ArrayList<RuntimeException>());
    /** Validation workers close() can interrupt without interrupting a later disconnect. */
    private final Set<Thread> validationWorkers = Collections.newSetFromMap(new IdentityHashMap<Thread, Boolean>());
    private final List<Map<String, Object>> healthEvents = new ArrayList<Map<String, Object>>();
    private int suppressedHealthEvents;
    private long lastHealthEventNanos;
    private int nextSessionId;
    private int observedDisconnectAttempts;
    private int observedDisconnectFailures;
    private int knownDeadBeforeCount;
    private int uncertainCloseCount;
    private volatile boolean closed;
    /** Residual physical reservations after a bounded shutdown wait. */
    private int residualCreating;
    private int residualClosing;
    private int residualValidating;
    private static final long SHUTDOWN_WAIT_FLOOR_MS = 5_000L;
    /** Background idle sweep. Borrow still recycles immediately; this only covers sessions nobody borrows again. */
    private static final long IDLE_EVICT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_RETAINED_CONFIRMED = 32;
    private static final int MAX_EXPORTED_SESSIONS = 16;
    private static final long HEALTH_EVENT_INTERVAL_NANOS = 10_000_000_000L;
    private static final Pattern LEADING_CODE = Pattern.compile("^([A-Z][A-Z0-9_]{1,94})(?:\\s|:|$)");
    /** Package-visible so tests can age idle sessions without sleeping or calling MQ. */
    volatile LongSupplier nanoClock = System::nanoTime;
    /**
     * At most this many physical disconnects run at once in one pool. Further closes stay queued and keep their slots.
     * Shutdown counts queued and running closes together.
     */
    static final int DISCONNECT_PARALLELISM = 4;
    private final ExecutorService disconnects = newDisconnectExecutor();
    /** Package-visible so tests can inject a failing disconnect-worker starter. */
    volatile java.util.function.Consumer<Runnable> disconnectStarter = this::enqueueDisconnect;
    /**
     * Package-visible sweep period. The default is about 30s and the task exists only when maxIdle or maxLifetime is set.
     * Borrow and return notifications do not run a sweep before this interval elapses. {@code close()} still wakes the
     * task immediately because the pool is closed.
     */
    volatile long idleEvictIntervalNanos = IDLE_EVICT_INTERVAL_NANOS;
    /**
     * Package-visible hook on the borrower, inside the pool lock, after validation finishes and before a lease is taken.
     * Tests use it to interrupt at that commit point.
     */
    volatile Runnable beforeValidationCommit;
    private volatile Thread evictor;
    /** Sweeps that actually scanned the idle queue. A notification before the deadline does not increment this. */
    private int idleSweepCount;

    public LoadResourcePool(int maxSize, long borrowTimeoutMs, Supplier<T> factory, Consumer<T> closer) {
        this(maxSize, 0, borrowTimeoutMs, factory, closer);
    }

    public LoadResourcePool(int maxSize, int minIdle, long borrowTimeoutMs,
                            Supplier<T> factory, Consumer<T> closer) {
        this(maxSize, minIdle, borrowTimeoutMs, factory, closer, Health.disabled(), null);
    }

    public LoadResourcePool(int maxSize, long borrowTimeoutMs, Supplier<T> factory, Consumer<T> closer,
                            Health health, IdleValidator<T> idleValidator) {
        this(maxSize, 0, borrowTimeoutMs, factory, closer, health, idleValidator);
    }

    public LoadResourcePool(int maxSize, int minIdle, long borrowTimeoutMs,
                            Supplier<T> factory, Consumer<T> closer,
                            Health health, IdleValidator<T> idleValidator) {
        if (maxSize < 1) throw new IllegalArgumentException("pool maxSize must be positive");
        if (minIdle < 0 || minIdle > maxSize) throw new IllegalArgumentException("pool minIdle must be from 0 to maxSize");
        if (borrowTimeoutMs < 0L) throw new IllegalArgumentException("pool borrow timeout must not be negative");
        if (factory == null) throw new IllegalArgumentException("pool factory is required");
        this.maxSize = maxSize;
        this.borrowTimeoutMs = borrowTimeoutMs;
        this.factory = factory;
        this.closer = closer == null ? value -> {} : closer;
        this.health = health == null ? Health.disabled() : health;
        this.idleValidator = idleValidator;
        this.idle = new ArrayBlockingQueue<T>(maxSize);
        this.permits = new Semaphore(maxSize, true);
        if (minIdle > 0) {
            try { ensureMinIdle(minIdle); }
            catch (RuntimeException error) { close(); throw error; }
        }
        startIdleEvictor();
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
            final Session reserved;
            synchronized (this) {
                if (closed) throw new IllegalStateException("resource pool is closed");
                if (idle.size() + creating >= minIdle || total.get() >= maxSize) return;
                reserved = reserveCreationLocked();
            }
            T value;
            try { value = factory.get(); }
            catch (RuntimeException error) {
                synchronized (this) { failCreationLocked(reserved); }
                throw error;
            } catch (Error error) {
                synchronized (this) { failCreationLocked(reserved); }
                throw error;
            }
            if (value == null) {
                synchronized (this) { failCreationLocked(reserved); }
                throw new IllegalStateException("resource factory returned null");
            }
            boolean interrupted = Thread.currentThread().isInterrupted();
            boolean timedOut = System.nanoTime() >= deadlineNanos;
            boolean closedDuringConnect;
            boolean closeValue;
            synchronized (this) {
                closedDuringConnect = closed;
                Session session = materializeLocked(reserved, value);
                closeValue = session.deadlineFrozen || closedDuringConnect || timedOut || interrupted;
                if (closeValue) {
                    closeValue = queueClose(session);
                }
                else {
                    created.incrementAndGet();
                    if (!idle.offer(value)) {
                        closeValue = queueClose(session);
                    } else {
                        markIdle(session);
                        value = null;
                    }
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

    /** A creation remains charged to total until it succeeds or fails, even if its borrower leaves. */
    private final class PendingCreation {
        boolean done;
        Throwable failure;
    }

    /** Called with the pool monitor held. At most maxSize such daemon threads can be in flight. */
    private PendingCreation startCreation() {
        final PendingCreation pending = new PendingCreation();
        final Session reserved = reserveCreationLocked();
        try {
            Thread thread = new Thread(() -> {
                T value = null;
                Throwable failure = null;
                try {
                    value = factory.get();
                    if (value == null) failure = new IllegalStateException("resource factory returned null");
                } catch (Throwable error) {
                    failure = error;
                }
                boolean closeValue = false;
                synchronized (LoadResourcePool.this) {
                    if (failure != null) {
                        failCreationLocked(reserved);
                        pending.failure = failure;
                    } else {
                        Session session = materializeLocked(reserved, value);
                        creating--;
                        if (session.deadlineFrozen || closed || !idle.offer(value)) closeValue = queueClose(session);
                        else {
                            created.incrementAndGet();
                            markIdle(session);
                        }
                    }
                    pending.done = true;
                    LoadResourcePool.this.notifyAll();
                }
                if (closeValue) closeTracked(value);
            }, "att-load-resource-connect");
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException | Error failure) {
            abandonCreationLocked(reserved);
            throw failure;
        }
        return pending;
    }

    /** Called with the pool monitor held. The record exists before {@code factory.get()} returns. */
    private Session reserveCreationLocked() {
        total.incrementAndGet();
        creating++;
        Session session = new Session(++nextSessionId, null, nanoClock.getAsLong());
        creatingSessions.addLast(session);
        return session;
    }

    /** Called with the pool monitor held. The factory never ran, so the reservation leaves no record. */
    private void abandonCreationLocked(Session session) {
        creating--;
        total.decrementAndGet();
        creatingSessions.remove(session);
        notifyAll();
    }

    /**
     * Called with the pool monitor held. A connect already frozen at the shutdown deadline keeps that
     * one timeout record and does not add a close failure. No connection object was returned, so the slot is released.
     */
    private void failCreationLocked(Session session) {
        creating--;
        creatingSessions.remove(session);
        total.decrementAndGet();
        failures.incrementAndGet();
        if (session.deadlineFrozen) remember(session);
        notifyAll();
    }

    /** Called with the pool monitor held. Binds the connection object to the session reserved for this create. */
    private Session materializeLocked(Session reserved, T value) {
        creatingSessions.remove(reserved);
        Session existing = sessions.get(value);
        if (existing != null) {
            if (reserved != existing && reserved.deadlineFrozen) remember(reserved);
            return existing;
        }
        reserved.value = value;
        sessions.put(value, reserved);
        return reserved;
    }

    public Lease borrow() throws Exception {
        if (closed) throw new IllegalStateException("resource pool is closed");
        waiting.incrementAndGet();
        long started = System.nanoTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(borrowTimeoutMs);
        boolean acquired = false;
        try {
            acquired = acquirePermit(started, budget);
            while (true) {
                T pendingValidation = null;
                Lease offered = null;
                List<T> recycleNow = new ArrayList<T>();
                try {
                    synchronized (this) {
                        PendingCreation pending = null;
                        boolean waited = false;
                        while (true) {
                            if (closed) throw new IllegalStateException("resource pool is closed");
                            if (waited && System.nanoTime() - started >= budget) throw timeout(started);
                            T value = idle.poll();
                            if (value != null) {
                                Session session = registerLocked(value);
                                IdleAction action = classify(session);
                                if (action == IdleAction.OFFER) {
                                    session.state = "LEASED";
                                    leased.add(value);
                                    offered = new Lease(value);
                                    break;
                                }
                                if (action == IdleAction.RECYCLE) {
                                    recycleLocked(session);
                                    recycleNow.add(value);
                                    continue;
                                }
                                session.state = "VALIDATING";
                                session.validationFinished = false;
                                session.validationHandled = false;
                                session.validationResult = null;
                                validating++;
                                pendingValidation = value;
                                break;
                            }
                            if (pending != null && pending.done) {
                                Throwable failure = pending.failure;
                                pending = null;
                                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                                if (failure instanceof Error) throw (Error) failure;
                            }
                            long remaining = budget - (System.nanoTime() - started);
                            if (remaining <= 0L) throw timeout(started);
                            if (!recycleNow.isEmpty() && idle.isEmpty() && total.get() >= maxSize) break;
                            if (pending == null && total.get() < maxSize) pending = startCreation();
                            waited = true;
                            TimeUnit.NANOSECONDS.timedWait(this, remaining);
                        }
                    }
                } finally {
                    for (T value : recycleNow) closeAsync(value);
                }
                if (offered != null) {
                    acquired = false;
                    return offered;
                }
                if (pendingValidation != null) {
                    Lease validated = finishValidation(pendingValidation, started, budget);
                    if (validated != null) {
                        acquired = false;
                        return validated;
                    }
                    if (System.nanoTime() - started >= budget) {
                        synchronized (this) { throw timeout(started); }
                    }
                }
            }
        } finally {
            if (acquired) permits.release();
            waiting.decrementAndGet();
            waitNanos.addAndGet(Math.max(0L, System.nanoTime() - started));
        }
    }

    private boolean acquirePermit(long started, long budget) throws Exception {
        boolean acquired = false;
        do {
            if (closed) throw new IllegalStateException("resource pool is closed");
            long remaining = Math.max(0L, budget - (System.nanoTime() - started));
            acquired = permits.tryAcquire(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            if (acquired || remaining == 0L) break;
        } while (System.nanoTime() - started < budget);
        if (!acquired) throw timeout(started);
        return true;
    }

    private Lease finishValidation(T value, long started, long budget) throws Exception {
        final Session session;
        synchronized (this) { session = sessions.get(value); }
        if (session == null || idleValidator == null) {
            boolean closeValue = false;
            synchronized (this) {
                validating = Math.max(0, validating - 1);
                Session current = session != null ? session : sessions.get(value);
                if (current == null) current = registerLocked(value);
                if (idleValidator == null && !closed && !current.closeQueued && idle.offer(value)) markIdle(current);
                else closeValue = queueClose(current);
            }
            if (closeValue) closeAsync(value);
            return null;
        }
        long timeoutMs = Math.max(1L, Math.min(health.validationTimeoutMs, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(Math.max(1L, budget - (System.nanoTime() - started))))));
        validationAttempts.incrementAndGet();
        final boolean[] closeAfter = new boolean[] {false};
        final Thread worker = new Thread(() -> {
            Throwable error = null;
            try { idleValidator.validate(value, timeoutMs); }
            catch (Throwable failure) { error = failure; }
            synchronized (LoadResourcePool.this) {
                validationWorkers.remove(Thread.currentThread());
                if (session.validationHandled) {
                    validating = Math.max(0, validating - 1);
                    session.lastValidationOutcome = "TIMED_OUT";
                    session.lastValidationNanos = nanoClock.getAsLong();
                    if (!session.discardCounted && !closed) {
                        discarded.incrementAndGet();
                        session.discardCounted = true;
                        recycled.incrementAndGet();
                    }
                    closeAfter[0] = queueClose(session);
                    recordHealthLocked("MQ_IDLE_VALIDATION_TIMEOUT");
                } else {
                    session.validationResult = error;
                    session.validationFinished = true;
                    LoadResourcePool.this.notifyAll();
                }
            }
            if (closeAfter[0]) {
                // close() and the borrow timeout interrupt this same worker. Drop that flag before disconnect.
                Thread.interrupted();
                closeTracked(value);
            }
        }, "att-load-resource-validate");
        worker.setDaemon(true);
        synchronized (this) { validationWorkers.add(worker); }
        try { worker.start(); }
        catch (RuntimeException | Error failure) {
            boolean closeValue;
            synchronized (this) {
                validationWorkers.remove(worker);
                validating = Math.max(0, validating - 1);
                session.validationHandled = true;
                closeValue = queueClose(session);
            }
            if (closeValue) closeAsync(value);
            throw failure;
        }
        boolean timedOut = false;
        boolean closeNow = false;
        boolean borrowerInterrupted = false;
        Lease lease = null;
        IllegalStateException closedNow = null;
        synchronized (this) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (!session.validationFinished && !closed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    borrowerInterrupted = true;
                    break;
                }
            }
            if (session.validationFinished) {
                session.validationHandled = true;
                validating = Math.max(0, validating - 1);
                Throwable error = session.validationResult;
                boolean unsupported = unsupported(error);
                session.lastValidationNanos = nanoClock.getAsLong();
                if (error == null) session.lastValidationOutcome = "PASSED";
                else if (unsupported) {
                    session.lastValidationOutcome = "UNSUPPORTED";
                    validationUnsupported.incrementAndGet();
                    recordHealthLocked("MQ_IDLE_VALIDATION_UNSUPPORTED");
                } else {
                    session.lastValidationOutcome = "FAILED";
                    validationFailures.incrementAndGet();
                    applyCause(session, error, false);
                    recordHealthLocked(session.knownDead ? "MQ_CONNECTION_NOT_REUSED" : "MQ_IDLE_VALIDATION_FAILED");
                }
                Runnable commit = beforeValidationCommit;
                if (commit != null) commit.run();
                boolean cancelled = borrowerInterrupted || Thread.currentThread().isInterrupted();
                boolean reject = closed || session.knownDead || session.independentOutage || session.closeQueued
                        || (error != null && !unsupported);
                if (reject) {
                    if (!session.discardCounted && !closed) {
                        discarded.incrementAndGet();
                        session.discardCounted = true;
                        recycled.incrementAndGet();
                    }
                    if (error == null && (session.knownDead || session.independentOutage || session.closeQueued))
                        recordHealthLocked("MQ_CONNECTION_NOT_REUSED");
                    closeNow = queueClose(session);
                    if (closed) closedNow = new IllegalStateException("resource pool is closed");
                } else if (cancelled) {
                    borrowerInterrupted = true;
                    if (!idle.offer(value)) closeNow = queueClose(session);
                    else session.state = "IDLE";
                    // The validation worker already notified when it stored the result, before this offer.
                    // A borrower parked in borrow() has since gone back to sleep on an empty queue.
                    // Do not markIdle: that would reset idleSince and hide a session that crossed maxIdle during validation.
                    notifyAll();
                } else {
                    session.state = "LEASED";
                    leased.add(value);
                    lease = new Lease(value);
                }
            } else {
                session.validationHandled = true;
                session.lastValidationOutcome = "TIMED_OUT";
                timedOut = true;
                if (closed) closedNow = new IllegalStateException("resource pool is closed");
            }
        }
        if (timedOut) worker.interrupt();
        if (closeNow) closeAsync(value);
        if (closedNow != null) throw closedNow;
        if (lease == null && borrowerInterrupted) throw new InterruptedException("resource borrow cancelled");
        return lease;
    }

    private synchronized PoolTimeoutException timeout(long started) {
        timeoutCount.incrementAndGet();
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("maxSize", maxSize); snapshot.put("total", total.get());
        snapshot.put("idle", idle.size()); snapshot.put("active", leased.size());
        snapshot.put("creating", creating); snapshot.put("closing", closing); snapshot.put("validating", validating);
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
    /** Start failures are also counted by {@link #closeFailureCount()} and stay in {@code closing}. */
    int disconnectStartFailureCount() { return disconnectStartFailures.get(); }

    /**
     * One unconfirmed connect or disconnect per physical session.
     * A connect still inside the factory, or a disconnect still running, is one frozen record at the deadline.
     * A later completion attaches to that record and does not add another close failure.
     * Idle validation that has not reached {@code disconnect()} is not included.
     */
    long failedCloseCount() {
        long residuals;
        synchronized (this) { residuals = (long) residualCreating + residualClosing; }
        return Math.max(0L, closeFailureCount() - disconnectStartFailureCount() + residuals);
    }
    public long waitDurationMs() { return TimeUnit.NANOSECONDS.toMillis(waitNanos.get()); }

    /** Records an externally observed queue-manager or network outage. This does not prove sessions were released. */
    public void noteIndependentOutage(String code) {
        String safe = safeCode(code);
        if (safe == null) safe = "MQ_INFRASTRUCTURE_OUTAGE";
        List<T> drop = new ArrayList<T>();
        synchronized (this) {
            for (Map.Entry<T, Session> entry : new ArrayList<Map.Entry<T, Session>>(sessions.entrySet())) {
                Session session = entry.getValue();
                session.independentOutage = true;
                session.independentOutageCode = safe;
                session.knownDead = true;
                session.knownDeadCode = safe;
                if ("IDLE".equals(session.state) && idle.remove(entry.getKey())) {
                    if (!session.discardCounted) {
                        discarded.incrementAndGet();
                        session.discardCounted = true;
                        recycled.incrementAndGet();
                    }
                    if (queueClose(session)) drop.add(entry.getKey());
                }
            }
            recordHealthLocked(safe);
            notifyAll();
        }
        for (T value : drop) closeAsync(value);
    }

    public synchronized Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("maxSize", maxSize); result.put("active", active()); result.put("idle", idle());
        result.put("total", total()); result.put("creating", creating); result.put("closing", closing);
        result.put("validating", validating);
        result.put("closed", closed); result.put("waiting", waiting()); result.put("waitDurationMs", waitDurationMs());
        result.put("timeoutCount", timeoutCount()); result.put("created", created()); result.put("discarded", discarded());
        result.put("replacementCount", discarded()); result.put("failures", failures());
        result.put("residualCreating", residualCreating); result.put("residualClosing", residualClosing);
        result.put("residualValidating", residualValidating);
        result.put("observation", closed ? "POST_TEARDOWN" : "PRE_TEARDOWN");
        result.put("observationNote", closed
                ? "POST_TEARDOWN sample taken after disconnect attempts. failures counts creation failures only. Idle-validation problems are validationFailures. Disconnect results are disconnectFailureCount and failureClass. cleanupFailuresIncluded is false because those results are not folded into failures."
                : "PRE_TEARDOWN sample taken before disconnect. failures counts creation failures only. Idle-validation problems are validationFailures. Neither includes cleanup disconnect failures. cleanupFailuresIncluded is false.");
        result.put("cleanupFailuresIncluded", Boolean.FALSE);
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("staleIdleValidation", health.staleIdleValidation);
        policy.put("staleIdleThresholdMs", health.staleIdleThresholdMs);
        policy.put("validationTimeoutMs", health.validationTimeoutMs);
        policy.put("maxIdleMs", health.maxIdleMs);
        policy.put("maxLifetimeMs", health.maxLifetimeMs);
        result.put("healthPolicy", policy);
        result.put("validationAttempts", validationAttempts.get());
        result.put("validationFailures", validationFailures.get());
        result.put("validationUnsupported", validationUnsupported.get());
        result.put("recycled", recycled.get());
        result.put("suppressedHealthEvents", suppressedHealthEvents);
        if (!healthEvents.isEmpty()) result.put("healthEvents", new ArrayList<Map<String, Object>>(healthEvents));
        Map<String, Integer> classes = new LinkedHashMap<String, Integer>();
        List<Session> exported = exportSessions();
        countFailureClasses(classes, creatingSessions);
        countFailureClasses(classes, sessions.values());
        countFailureClasses(classes, failedHistory);
        countFailureClasses(classes, confirmedHistory);
        result.put("disconnectAttemptCount", observedDisconnectAttempts);
        result.put("disconnectFailureCount", observedDisconnectFailures);
        result.put("uncertainOwnershipCount", uncertainCloseCount);
        result.put("knownDeadBeforeTeardownCount", knownDeadBeforeCount);
        if (observedDisconnectFailures > 0)
            result.put("knownDeadBeforeTeardown", Boolean.valueOf(knownDeadBeforeCount == observedDisconnectFailures));
        if (!classes.isEmpty()) result.put("failureClassCounts", classes);
        if (classes.size() == 1) result.put("failureClass", classes.keySet().iterator().next());
        if (closed) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Session session : exported) rows.add(sessionRow(session));
            result.put("physicalSessions", rows);
            boolean unconfirmed = observedDisconnectFailures > 0 || residualCreating > 0 || residualClosing > 0 || residualValidating > 0;
            result.put("safeCloseClassification", unconfirmed ? "NOT_APPLIED" : "CONFIRMED");
        }
        Map<String, Object> saturation = new LinkedHashMap<String, Object>();
        saturation.put("usage", active()); saturation.put("limit", maxSize); saturation.put("threshold", 0.8d);
        result.put("limitUsage", java.util.Collections.<String, Object>singletonMap("mq.pool.activeConnections", saturation));
        return result;
    }

    /** Safe post-close diagnostic. Reason 2009 alone never becomes a confirmed release. */
    public synchronized Map<String, Object> teardownDetails() {
        Map<String, Object> details = new LinkedHashMap<String, Object>(metrics());
        details.put("safeCloseClassification", "NOT_APPLIED");
        details.put("safeCloseReason", "DISCONNECT_NOT_CONFIRMED");
        return details;
    }

    private void release(T value, boolean invalidate, Throwable cause) {
        boolean closeValue = false;
        synchronized (this) {
            if (!leased.remove(value)) return;
            Session session = sessions.get(value);
            if (session == null) session = registerLocked(value);
            if (invalidate || cause != null) applyCause(session, cause, invalidate);
            boolean drop = invalidate || session.knownDead || closed || !idle.offer(value);
            if (!drop) markIdle(session);
            else {
                if ((invalidate || !closed) && !session.discardCounted) {
                    discarded.incrementAndGet();
                    session.discardCounted = true;
                }
                closeValue = queueClose(session);
            }
            notifyAll();
        }
        // A hung IBM disconnect must not pin the LOAD worker or its borrow permit.
        // The physical slot stays charged until closeTracked confirms the close.
        if (closeValue) closeAsync(value);
        permits.release();
    }

    private void closeTracked(T value) {
        Session session;
        synchronized (this) {
            session = sessions.get(value);
            if (session == null || session.closeInvoked) return;
            session.closeInvoked = true;
            session.disconnectAttempts++;
            observedDisconnectAttempts++;
            session.knownDeadBeforeDisconnect = session.knownDead;
            session.idleAgeAtCloseMs = ageMs(session.idleSinceNanos);
            session.lifetimeAtCloseMs = ageMs(session.createdNanos);
            session.lastUseAgeAtCloseMs = ageMs(session.lastSuccessfulUseNanos);
            if (!session.closeQueued) queueClose(session);
        }
        boolean confirmed = false;
        Throwable failure = null;
        try { closer.accept(value); confirmed = true; }
        catch (RuntimeException | Error error) { failure = error; }
        synchronized (this) {
            if (session.deadlineFrozen) {
                if (confirmed) total.decrementAndGet();
                else attachLateFailure(session, failure);
                remember(session);
            } else if (confirmed) {
                session.confirmation = "CONFIRMED_CLOSED";
                session.state = "CONFIRMED_CLOSED";
                session.failureClass = null;
                remember(session);
                total.decrementAndGet();
            } else {
                closeFailures.add(new IllegalStateException("LOAD_RESOURCE_TEARDOWN_FAILED", failure));
                classifyFailure(session, failure);
                remember(session);
            }
            closing = Math.max(0, closing - 1);
            notifyAll();
        }
    }

    private ExecutorService newDisconnectExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "att-load-resource-disconnect");
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(DISCONNECT_PARALLELISM, DISCONNECT_PARALLELISM,
                30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), factory);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Queues one physical disconnect. At most {@link #DISCONNECT_PARALLELISM} run; the rest wait in this pool's queue. */
    private void enqueueDisconnect(Runnable task) {
        disconnects.execute(task);
    }

    /** Runs a physical disconnect outside the caller so one hung closer cannot block a worker or the shutdown deadline. */
    private void closeAsync(T value) {
        try {
            disconnectStarter.accept(() -> closeTracked(value));
        } catch (RuntimeException | Error failure) {
            disconnectStartFailures.incrementAndGet();
            closeFailures.add(new IllegalStateException("LOAD_RESOURCE_TEARDOWN_FAILED: disconnect worker could not start", failure));
            synchronized (this) { notifyAll(); }
        }
    }

    @Override public void close() {
        try {
            closeWithinBudget();
        } finally {
            disconnects.shutdown();
        }
    }

    private void closeWithinBudget() {
        List<T> values = new ArrayList<T>();
        List<Thread> validators = new ArrayList<Thread>();
        Thread evictorThread = null;
        boolean interrupted = false;
        long budgetMs = Math.max(borrowTimeoutMs, SHUTDOWN_WAIT_FLOOR_MS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        synchronized (this) {
            if (!closed) {
                closed = true;
                evictorThread = evictor;
                Set<T> unique = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
                idle.drainTo(values);
                unique.addAll(values);
                for (T leasedValue : new ArrayList<T>(leased)) if (unique.add(leasedValue)) values.add(leasedValue);
                leased.clear();
                List<T> closable = new ArrayList<T>();
                for (T value : values) {
                    Session session = sessions.get(value);
                    if (session == null) session = registerLocked(value);
                    if (queueClose(session)) closable.add(value);
                }
                values = closable;
                validators.addAll(validationWorkers);
                notifyAll();
            } else values = Collections.emptyList();
        }
        if (evictorThread != null) {
            evictorThread.interrupt();
            try { evictorThread.join(1_000L); }
            catch (InterruptedException cancelled) { interrupted = true; }
        }
        for (Thread validator : validators) validator.interrupt();
        for (T value : values) closeAsync(value);
        synchronized (this) {
            while (creating > 0 || closing > 0 || validating > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) break;
                try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                catch (InterruptedException cancelled) {
                    interrupted = true;
                }
            }
            if (creating > 0 || closing > 0 || validating > 0) stampResidualsLocked();
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (residualCreating > 0 || residualClosing > 0) {
            IllegalStateException timeout = new IllegalStateException("LOAD_RESOURCE_TEARDOWN_TIMEOUT: outstanding physical reservations remain "
                    + "creating=" + residualCreating + " closing=" + residualClosing + " validating=" + residualValidating
                    + " total=" + total.get() + " failureClass=LOAD_RESOURCE_TEARDOWN_TIMEOUT snapshot=" + metrics());
            synchronized (closeFailures) {
                for (RuntimeException closeFailure : closeFailures) timeout.addSuppressed(closeFailure);
            }
            throw timeout;
        }
        if (!closeFailures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "LOAD_RESOURCE_TEARDOWN_FAILED: one or more pooled resources did not confirm close");
            synchronized (closeFailures) {
                for (RuntimeException closeFailure : closeFailures) failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        if (residualValidating > 0) {
            throw new IllegalStateException("MQ_IDLE_VALIDATION_INCOMPLETE: idle validation still running and disconnect was not attempted "
                    + "validating=" + residualValidating + " total=" + total.get()
                    + " failureClass=MQ_IDLE_VALIDATION_INCOMPLETE snapshot=" + metrics());
        }
    }

    private void stampResidualsLocked() {
        residualCreating = creating;
        residualClosing = closing;
        residualValidating = validating;
        freezeInFlightLocked(creatingSessions);
        freezeInFlightLocked(sessions.values());
    }

    /** One frozen timeout record for a connect or disconnect still running at the shutdown deadline. */
    private void freezeInFlightLocked(Iterable<Session> rows) {
        for (Session session : rows) {
            if (session.deadlineFrozen) continue;
            if ("VALIDATING".equals(session.state) && !session.closeQueued) {
                if ("NOT_RUN".equals(session.lastValidationOutcome)) session.lastValidationOutcome = "TIMED_OUT";
                continue;
            }
            if (!"CLOSING".equals(session.state) && !"CREATED".equals(session.state)) continue;
            session.deadlineFrozen = true;
            if (session.failureClass == null) session.failureClass = "LOAD_RESOURCE_TEARDOWN_TIMEOUT";
            session.confirmation = "TIMED_OUT";
            session.state = "TIMED_OUT";
            addCode(session, "LOAD_RESOURCE_TEARDOWN_TIMEOUT");
        }
    }

    private Session registerLocked(T value) {
        Session existing = sessions.get(value);
        if (existing != null) return existing;
        Session session = new Session(++nextSessionId, value, nanoClock.getAsLong());
        sessions.put(value, session);
        return session;
    }

    private void markIdle(Session session) {
        long now = nanoClock.getAsLong();
        session.state = "IDLE";
        session.idleSinceNanos = now;
        session.lastSuccessfulUseNanos = now;
    }

    private boolean queueClose(Session session) {
        if (session == null || session.closeQueued) return false;
        session.closeQueued = true;
        session.state = "CLOSING";
        closing++;
        return true;
    }

    private void startIdleEvictor() {
        if (health.maxIdleMs <= 0L && health.maxLifetimeMs <= 0L) return;
        Thread thread = new Thread(this::evictLoop, "att-load-resource-evict");
        thread.setDaemon(true);
        evictor = thread;
        thread.start();
    }

    /** True while the optional max-idle / max-lifetime sweep is alive. */
    boolean idleEvictorAlive() {
        Thread thread = evictor;
        return thread != null && thread.isAlive();
    }

    /** How many times the background sweep has scanned idle sessions. */
    synchronized int idleSweepCount() { return idleSweepCount; }

    private void evictLoop() {
        long nextSweep = System.nanoTime() + sweepInterval();
        while (true) {
            List<T> drop;
            synchronized (this) {
                while (!closed) {
                    long interval = sweepInterval();
                    long remaining = nextSweep - System.nanoTime();
                    if (remaining > interval) {
                        nextSweep = System.nanoTime() + interval;
                        remaining = interval;
                    }
                    if (remaining <= 0L) break;
                    try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                    catch (InterruptedException ignored) { }
                }
                if (closed) return;
                drop = evictExpiredLocked();
                idleSweepCount++;
                nextSweep = System.nanoTime() + sweepInterval();
            }
            for (T value : drop) closeAsync(value);
        }
    }

    private long sweepInterval() {
        long pause = idleEvictIntervalNanos;
        if (pause < 1_000_000L) pause = 1_000_000L;
        return pause;
    }

    /** Called with the pool monitor held. Closes idle sessions past maxIdle or maxLifetime. No validation and no MQ call here. */
    private List<T> evictExpiredLocked() {
        List<T> drop = new ArrayList<T>();
        if (closed || (health.maxIdleMs <= 0L && health.maxLifetimeMs <= 0L)) return drop;
        for (T value : new ArrayList<T>(idle)) {
            Session session = sessions.get(value);
            if (session == null || !"IDLE".equals(session.state) || session.closeQueued || !expired(session)) continue;
            if (!idle.remove(value)) continue;
            recycleLocked(session);
            drop.add(value);
        }
        return drop;
    }

    private boolean expired(Session session) {
        long life = ageMs(session.createdNanos);
        long idleAge = ageMs(session.idleSinceNanos);
        return (health.maxLifetimeMs > 0L && life >= health.maxLifetimeMs)
                || (health.maxIdleMs > 0L && idleAge >= health.maxIdleMs);
    }

    private void recycleLocked(Session session) {
        if (!session.discardCounted && !closed) {
            discarded.incrementAndGet();
            session.discardCounted = true;
            recycled.incrementAndGet();
        }
        queueClose(session);
        recordHealthLocked(session.knownDead ? "MQ_CONNECTION_NOT_REUSED" : "MQ_IDLE_CONNECTION_RECYCLED");
    }

    private IdleAction classify(Session session) {
        if (session.knownDead) return IdleAction.RECYCLE;
        if (!health.active()) return IdleAction.OFFER;
        long idleAge = ageMs(session.idleSinceNanos);
        if (expired(session)) return IdleAction.RECYCLE;
        if (health.staleIdleValidation && idleAge >= health.staleIdleThresholdMs) {
            if (idleValidator == null) {
                session.lastValidationOutcome = "UNSUPPORTED";
                validationUnsupported.incrementAndGet();
                recordHealthLocked("MQ_IDLE_VALIDATION_UNSUPPORTED");
                return IdleAction.OFFER;
            }
            return IdleAction.VALIDATE;
        }
        return IdleAction.OFFER;
    }

    private void applyCause(Session session, Throwable cause, boolean invalidate) {
        MqTransport.Exception mq = findMq(cause);
        if (mq != null) {
            if (mq.completionCode() != null) session.mqCompletion = mq.completionCode();
            if (mq.reasonCode() != null) session.mqReason = mq.reasonCode();
            String reason = safeCode(mq.reason());
            if (mq.connectionFailure()) {
                session.knownDead = true;
                if (reason != null) session.knownDeadCode = reason;
                return;
            }
            session.ownershipUncertain = true;
            return;
        }
        if (!invalidate && cause == null) return;
        if (session.exclusionCode == null) session.exclusionCode = "INVALIDATED";
    }

    /** Records IBM completion and reason on the timeout record without counting the session again. */
    private void attachLateFailure(Session session, Throwable failure) {
        session.disconnectFailures++;
        observedDisconnectFailures++;
        if (session.knownDeadBeforeDisconnect) knownDeadBeforeCount++;
        MqTransport.Exception mq = findMq(failure);
        if (mq != null) {
            if (mq.completionCode() != null) session.mqCompletion = mq.completionCode();
            if (mq.reasonCode() != null) session.mqReason = mq.reasonCode();
            String reason = safeCode(mq.reason());
            if (reason != null) session.failureCode = reason;
            if (mq.connectionFailure()) session.knownDead = true;
            else {
                session.ownershipUncertain = true;
                uncertainCloseCount++;
            }
        } else if (failure != null) {
            session.ownershipUncertain = true;
            uncertainCloseCount++;
        }
        String messageCode = leadingCode(failure);
        if (session.failureCode == null) session.failureCode = messageCode;
        if (messageCode != null) addCode(session, messageCode);
        if (session.failureCode != null && !session.failureCode.equals(messageCode)) addCode(session, session.failureCode);
    }

    private void classifyFailure(Session session, Throwable failure) {
        session.disconnectFailures++;
        observedDisconnectFailures++;
        if (session.knownDeadBeforeDisconnect) knownDeadBeforeCount++;
        MqTransport.Exception mq = findMq(failure);
        boolean definitive = mq != null && mq.connectionFailure();
        if (mq != null) {
            if (mq.completionCode() != null) session.mqCompletion = mq.completionCode();
            if (mq.reasonCode() != null) session.mqReason = mq.reasonCode();
            String reason = safeCode(mq.reason());
            if (reason != null) session.failureCode = reason;
        }
        String messageCode = leadingCode(failure);
        if (session.failureCode == null) session.failureCode = messageCode;
        if (messageCode != null) addCode(session, messageCode);
        boolean timedOut = "TIMED_OUT".equals(session.confirmation)
                || "LOAD_RESOURCE_TEARDOWN_TIMEOUT".equals(session.failureClass);
        if (!timedOut) {
            session.failureClass = "PHYSICAL_DISCONNECT_FAILED";
            session.confirmation = definitive ? "QUARANTINED" : "UNCERTAIN";
            session.state = session.confirmation;
        }
        if (!definitive) {
            session.ownershipUncertain = true;
            uncertainCloseCount++;
        }
        if (definitive) session.knownDead = true;
        addCode(session, "PHYSICAL_DISCONNECT_FAILED");
        if (session.failureCode != null) addCode(session, session.failureCode);
    }

    private void addCode(Session session, String code) {
        if (code == null) return;
        if (session.codeCounts.size() >= 8 && !session.codeCounts.containsKey(code)) return;
        Integer current = session.codeCounts.get(code);
        session.codeCounts.put(code, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
    }

    private void remember(Session session) {
        if (session.remembered) return;
        session.remembered = true;
        creatingSessions.remove(session);
        if (session.value != null && sessions.get(session.value) == session) sessions.remove(session.value);
        ArrayDeque<Session> bucket = interesting(session) ? failedHistory : confirmedHistory;
        bucket.addLast(session);
        while (bucket.size() > MAX_RETAINED_CONFIRMED) bucket.removeFirst();
    }

    private void countFailureClasses(Map<String, Integer> classes, Iterable<Session> rows) {
        for (Session session : rows) {
            if (session.failureClass == null) continue;
            Integer count = classes.get(session.failureClass);
            classes.put(session.failureClass, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }
    }

    private List<Session> exportSessions() {
        List<Session> failed = new ArrayList<Session>();
        List<Session> other = new ArrayList<Session>();
        Set<Session> seen = Collections.newSetFromMap(new IdentityHashMap<Session, Boolean>());
        for (Session session : failedHistory) if (seen.add(session)) failed.add(session);
        for (Session session : creatingSessions) {
            if (!seen.add(session)) continue;
            if (interesting(session)) failed.add(session);
            else other.add(session);
        }
        for (Session session : sessions.values()) {
            seen.add(session);
            if (interesting(session)) failed.add(session);
            else other.add(session);
        }
        for (Session session : confirmedHistory) {
            if (seen.add(session)) other.add(session);
        }
        List<Session> rows = new ArrayList<Session>();
        for (Session session : failed) if (rows.size() < MAX_EXPORTED_SESSIONS) rows.add(session);
        for (Session session : other) if (rows.size() < MAX_EXPORTED_SESSIONS) rows.add(session);
        return rows;
    }

    private boolean interesting(Session session) {
        return session.failureClass != null || session.disconnectFailures > 0 || session.knownDead
                || "QUARANTINED".equals(session.confirmation) || "UNCERTAIN".equals(session.confirmation)
                || "TIMED_OUT".equals(session.confirmation);
    }

    private Map<String, Object> sessionRow(Session session) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("physicalId", "p" + session.id);
        row.put("confirmationState", session.confirmation == null ? session.state : session.confirmation);
        if (session.failureClass != null) row.put("failureClass", session.failureClass);
        if (session.failureCode != null) row.put("failureCode", session.failureCode);
        row.put("disconnectAttemptCount", session.disconnectAttempts);
        row.put("disconnectFailureCount", session.disconnectFailures);
        row.put("idleAgeMs", session.closeInvoked ? session.idleAgeAtCloseMs : ageMs(session.idleSinceNanos));
        row.put("lifetimeMs", session.closeInvoked ? session.lifetimeAtCloseMs : ageMs(session.createdNanos));
        row.put("lastSuccessfulUseAgeMs", session.closeInvoked ? session.lastUseAgeAtCloseMs : ageMs(session.lastSuccessfulUseNanos));
        row.put("lastValidationOutcome", session.lastValidationOutcome);
        if (session.lastValidationNanos > 0L) row.put("lastValidationAgeMs", ageMs(session.lastValidationNanos));
        row.put("knownDeadBeforeTeardown", Boolean.valueOf(session.knownDeadBeforeDisconnect));
        row.put("independentOutage", Boolean.valueOf(session.independentOutage));
        if (session.independentOutageCode != null) row.put("independentOutageCode", session.independentOutageCode);
        if (session.mqCompletion != null) row.put("mqCompletionCode", session.mqCompletion);
        if (session.mqReason != null) row.put("mqReasonCode", session.mqReason);
        if (session.ownershipUncertain) row.put("ownershipUncertain", Boolean.TRUE);
        if (session.knownDeadCode != null) row.put("knownDeadCode", session.knownDeadCode);
        if (session.exclusionCode != null) row.put("exclusionCode", session.exclusionCode);
        if (!session.codeCounts.isEmpty()) row.put("codeCounts", new LinkedHashMap<String, Integer>(session.codeCounts));
        if (session.disconnectFailures > 0) row.put("firstObserved", session.knownDeadBeforeDisconnect ? "BEFORE_TEARDOWN" : "TEARDOWN");
        else if (session.knownDead) row.put("firstObserved", "BEFORE_TEARDOWN");
        else row.put("firstObserved", "NOT_FAILED");
        return row;
    }

    private void recordHealthLocked(String code) {
        String safe = safeCode(code);
        if (safe == null) return;
        long now = nanoClock.getAsLong();
        if (lastHealthEventNanos != 0L && now - lastHealthEventNanos < HEALTH_EVENT_INTERVAL_NANOS) {
            suppressedHealthEvents++;
            return;
        }
        lastHealthEventNanos = now;
        if (healthEvents.size() >= 8) {
            suppressedHealthEvents++;
            return;
        }
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("code", safe);
        healthEvents.add(event);
    }

    private long ageMs(long since) {
        if (since <= 0L) return 0L;
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanoClock.getAsLong() - since));
    }

    private static boolean unsupported(Throwable failure) {
        MqTransport.Exception mq = findMq(failure);
        if (mq != null && "MQ_IDLE_VALIDATION_UNSUPPORTED".equals(mq.reason())) return true;
        String code = leadingCode(failure);
        return "MQ_IDLE_VALIDATION_UNSUPPORTED".equals(code);
    }

    private static MqTransport.Exception findMq(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current instanceof MqTransport.Exception) return (MqTransport.Exception) current;
            current = current.getCause();
        }
        return null;
    }

    private static String leadingCode(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            String code = leadingCode(current.getMessage());
            if (code != null) return code;
            current = current.getCause();
        }
        return null;
    }

    private static String leadingCode(String message) {
        if (message == null || message.length() > 4096) return null;
        Matcher matcher = LEADING_CODE.matcher(message.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String safeCode(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.matches("[A-Z][A-Z0-9_]{1,94}") ? trimmed : null;
    }

    private enum IdleAction { OFFER, RECYCLE, VALIDATE }

    private final class Session {
        final int id;
        T value;
        final long createdNanos;
        long idleSinceNanos;
        long lastSuccessfulUseNanos;
        long lastValidationNanos;
        String lastValidationOutcome = "NOT_RUN";
        String state = "CREATED";
        String confirmation;
        String failureClass;
        String failureCode;
        String knownDeadCode;
        String independentOutageCode;
        Integer mqCompletion;
        Integer mqReason;
        boolean knownDead;
        boolean knownDeadBeforeDisconnect;
        boolean ownershipUncertain;
        boolean independentOutage;
        boolean discardCounted;
        boolean closeQueued;
        boolean closeInvoked;
        boolean deadlineFrozen;
        String exclusionCode;
        boolean validationFinished;
        boolean validationHandled;
        boolean remembered;
        Throwable validationResult;
        int disconnectAttempts;
        int disconnectFailures;
        long idleAgeAtCloseMs;
        long lifetimeAtCloseMs;
        long lastUseAgeAtCloseMs;
        final Map<String, Integer> codeCounts = new LinkedHashMap<String, Integer>();

        Session(int id, T value, long now) {
            this.id = id;
            this.value = value;
            this.createdNanos = now;
            this.idleSinceNanos = now;
            this.lastSuccessfulUseNanos = now;
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
        public void invalidate() { invalidate(null); }
        public void invalidate(Throwable cause) {
            T current = value;
            if (current == null) return;
            value = null;
            LoadResourcePool.this.release(current, true, cause);
        }
        @Override public void close() {
            T current = value;
            if (current == null) return;
            value = null;
            LoadResourcePool.this.release(current, false, null);
        }
    }

    /** Optional check used only when stale-idle validation is enabled. */
    public interface IdleValidator<T> {
        void validate(T value, long timeoutMs) throws Exception;
    }

    /** Borrow-path policy. Disabled performs no validation and no idle recycling. */
    public static final class Health {
        final boolean staleIdleValidation;
        final long staleIdleThresholdMs;
        final long validationTimeoutMs;
        final long maxIdleMs;
        final long maxLifetimeMs;

        private Health(boolean staleIdleValidation, long staleIdleThresholdMs, long validationTimeoutMs,
                       long maxIdleMs, long maxLifetimeMs) {
            this.staleIdleValidation = staleIdleValidation;
            this.staleIdleThresholdMs = staleIdleThresholdMs;
            this.validationTimeoutMs = validationTimeoutMs;
            this.maxIdleMs = maxIdleMs;
            this.maxLifetimeMs = maxLifetimeMs;
        }

        public static Health disabled() { return new Health(false, 60_000L, 200L, 0L, 0L); }

        public static Health from(MqPoolLifecycle lifecycle) {
            if (lifecycle == null || !lifecycle.active()) return disabled();
            return new Health(lifecycle.staleIdleValidation(), lifecycle.staleIdleThresholdMs(),
                    lifecycle.validationTimeoutMs(), lifecycle.maxIdleMs(), lifecycle.maxLifetimeMs());
        }

        public boolean active() { return staleIdleValidation || maxIdleMs > 0L || maxLifetimeMs > 0L; }
        public boolean validates() { return staleIdleValidation; }
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
