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
import java.util.concurrent.Semaphore;
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
    private static final int MAX_RETAINED_CONFIRMED = 32;
    private static final int MAX_EXPORTED_SESSIONS = 16;
    private static final long HEALTH_EVENT_INTERVAL_NANOS = 10_000_000_000L;
    private static final Pattern LEADING_CODE = Pattern.compile("^([A-Z][A-Z0-9_]{1,94})(?:\\s|:|$)");
    /** Package-visible so tests can age idle sessions without sleeping or calling MQ. */
    volatile LongSupplier nanoClock = System::nanoTime;
    /** Package-visible so tests can inject a failing disconnect-worker starter. */
    volatile java.util.function.Consumer<Runnable> disconnectStarter = LoadResourcePool::startDisconnectWorker;

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
                Session session = registerLocked(value);
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
        total.incrementAndGet();
        creating++;
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
                    creating--;
                    if (failure != null) {
                        failures.incrementAndGet();
                        total.decrementAndGet();
                        pending.failure = failure;
                    } else if (closed || !idle.offer(value)) {
                        Session session = registerLocked(value);
                        closeValue = queueClose(session);
                    } else {
                        created.incrementAndGet();
                        markIdle(registerLocked(value));
                    }
                    pending.done = true;
                    LoadResourcePool.this.notifyAll();
                }
                if (closeValue) closeTracked(value);
            }, "att-load-resource-connect");
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException | Error failure) {
            creating--;
            total.decrementAndGet();
            notifyAll();
            throw failure;
        }
        return pending;
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
            if (closeAfter[0]) closeTracked(value);
        }, "att-load-resource-validate");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException | Error failure) {
            boolean closeValue;
            synchronized (this) {
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
                if (closed || (error != null && !unsupported)) {
                    if (!session.discardCounted && !closed) {
                        discarded.incrementAndGet();
                        session.discardCounted = true;
                        recycled.incrementAndGet();
                    }
                    closeNow = queueClose(session);
                    if (closed) closedNow = new IllegalStateException("resource pool is closed");
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
        for (T value : drop) closeTracked(value);
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
                ? "POST_TEARDOWN sample taken after disconnect attempts. It is separate from the pre-teardown resources.mq counters. Unconfirmed sessions remain quarantined."
                : "PRE_TEARDOWN sample taken before disconnect. failures counts creation and validation problems only and does not include cleanup disconnect failures.");
        result.put("cleanupFailuresIncluded", Boolean.valueOf(closed));
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
        if (closeValue) closeTracked(value);
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
        catch (RuntimeException | Error error) {
            failure = error;
            closeFailures.add(new IllegalStateException("LOAD_RESOURCE_TEARDOWN_FAILED", error));
        }
        synchronized (this) {
            if (confirmed) {
                session.confirmation = "CONFIRMED_CLOSED";
                session.state = "CONFIRMED_CLOSED";
                session.failureClass = null;
                remember(session);
                total.decrementAndGet();
            } else {
                classifyFailure(session, failure);
                remember(session);
            }
            closing = Math.max(0, closing - 1);
            notifyAll();
        }
    }

    private static void startDisconnectWorker(Runnable task) {
        Thread thread = new Thread(task, "att-load-resource-disconnect");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs a physical disconnect outside the shutdown waiter so one hung closer cannot block the deadline. */
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
        List<T> values = new ArrayList<T>();
        long budgetMs = Math.max(borrowTimeoutMs, SHUTDOWN_WAIT_FLOOR_MS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        synchronized (this) {
            if (!closed) {
                closed = true;
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
                notifyAll();
            } else values = Collections.emptyList();
        }
        for (T value : values) closeAsync(value);
        boolean interrupted = false;
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
        if (residualCreating > 0 || residualClosing > 0 || residualValidating > 0) {
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
    }

    private void stampResidualsLocked() {
        residualCreating = creating;
        residualClosing = closing;
        residualValidating = validating;
        for (Session session : sessions.values()) {
            if (!"CLOSING".equals(session.state) && !"VALIDATING".equals(session.state) && !"CREATED".equals(session.state)) continue;
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
        long life = ageMs(session.createdNanos);
        long idleAge = ageMs(session.idleSinceNanos);
        if (health.maxLifetimeMs > 0L && life >= health.maxLifetimeMs) return IdleAction.RECYCLE;
        if (health.maxIdleMs > 0L && idleAge >= health.maxIdleMs) return IdleAction.RECYCLE;
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
        if (invalidate) {
            session.knownDead = true;
            if (session.knownDeadCode == null) session.knownDeadCode = "INVALIDATED";
        } else if (cause != null) session.ownershipUncertain = true;
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
        final T value;
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
