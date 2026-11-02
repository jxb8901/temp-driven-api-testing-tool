package att.load;

import att.config.MqPoolLifecycle;
import att.exec.MqTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadResourcePoolTeardownTest {
    @Test void knownDeadConnectionFailsDisconnectBeforeTeardownAndStaysQuarantined() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = pool(1, 200L, closes, true);
        pool.ensureMinIdle(1);
        try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) {
            lease.invalidate(broken());
        }
        assertEquals(1, closes.get());
        assertEquals(1, pool.total());
        assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
        IllegalStateException teardown = assertThrows(IllegalStateException.class, pool::close);
        assertTrue(teardown.getMessage().contains("LOAD_RESOURCE_TEARDOWN_FAILED"), teardown.getMessage());
        assertFalse(teardown.getMessage().startsWith("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), teardown.getMessage());
        assertEquals(1, closes.get(), "a second close must not disconnect again");
        Map<String, Object> metrics = pool.metrics();
        assertEquals("POST_TEARDOWN", metrics.get("observation"));
        assertEquals(1, number(metrics.get("disconnectFailureCount")));
        assertEquals(Boolean.TRUE, metrics.get("knownDeadBeforeTeardown"));
        assertEquals("NOT_APPLIED", metrics.get("safeCloseClassification"));
        Map<String, Object> session = onlySession(metrics);
        assertEquals("QUARANTINED", session.get("confirmationState"));
        assertEquals("PHYSICAL_DISCONNECT_FAILED", session.get("failureClass"));
        assertEquals("BEFORE_TEARDOWN", session.get("firstObserved"));
        assertEquals(Boolean.TRUE, session.get("knownDeadBeforeTeardown"));
        assertEquals(2009, number(session.get("mqReasonCode")));
        assertEquals(1, pool.total());
    }

    @Test void firstConnectionBrokenIsObservedAtFinalDisconnect() {
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = pool(2, 200L, closes, true);
        pool.ensureMinIdle(2);
        IllegalStateException teardown = assertThrows(IllegalStateException.class, pool::close);
        assertTrue(teardown.getMessage().contains("LOAD_RESOURCE_TEARDOWN_FAILED"), teardown.getMessage());
        Map<String, Object> metrics = pool.metrics();
        assertEquals(2, number(metrics.get("disconnectAttemptCount")));
        assertEquals(2, number(metrics.get("disconnectFailureCount")));
        assertEquals(Boolean.FALSE, metrics.get("knownDeadBeforeTeardown"));
        assertEquals("PHYSICAL_DISCONNECT_FAILED", failureClass(metrics));
        List<Map<String, Object>> sessions = sessions(metrics);
        assertEquals(2, sessions.size());
        for (Map<String, Object> session : sessions) {
            assertEquals("TEARDOWN", session.get("firstObserved"));
            assertEquals(Boolean.FALSE, session.get("knownDeadBeforeTeardown"));
            assertEquals(2, number(session.get("mqCompletionCode")));
            assertEquals(2009, number(session.get("mqReasonCode")));
            assertEquals("QUARANTINED", session.get("confirmationState"));
        }
        assertEquals(2, pool.total());
        assertEquals(2, closes.get());
        assertThrows(IllegalStateException.class, pool::close);
        assertEquals(2, closes.get());
    }

    @Test void uncertainDisconnectStaysChargedAndIsNotReused() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        final long[] now = new long[] {1_000_000_000L};
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 80L,
                () -> new Handle(ids.incrementAndGet()), value -> {
                    closes.incrementAndGet();
                    throw new IllegalStateException("socket reset");
                }, LoadResourcePool.Health.from(new MqPoolLifecycle(false, 60_000L, 200L, 0L, 1_000L, 0)), null);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        pool.disconnectStarter = new Consumer<Runnable>() {
            @Override public void accept(Runnable task) { task.run(); }
        };
        pool.ensureMinIdle(1);
        now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
        assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
        assertEquals(1, closes.get());
        assertEquals(1, ids.get());
        assertEquals(1, pool.total());
        assertThrows(IllegalStateException.class, pool::close);
        assertEquals(1, closes.get());
        Map<String, Object> session = onlySession(pool.metrics());
        assertEquals("UNCERTAIN", session.get("confirmationState"));
        assertEquals(Boolean.TRUE, session.get("ownershipUncertain"));
        assertEquals("PHYSICAL_DISCONNECT_FAILED", session.get("failureClass"));
        assertEquals(1, number(pool.metrics().get("uncertainOwnershipCount")));
    }

    @Test void hungDisconnectIsTimeoutRatherThanPhysicalDisconnectFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 40L, () -> new Handle(1), value -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        pool.ensureMinIdle(1);
        long started = System.nanoTime();
        IllegalStateException timeout = assertThrows(IllegalStateException.class, pool::close);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
        assertTrue(elapsedMs < 15_000L, "elapsedMs=" + elapsedMs);
        Map<String, Object> metrics = pool.metrics();
        assertEquals(1, number(metrics.get("residualClosing")));
        assertEquals("LOAD_RESOURCE_TEARDOWN_TIMEOUT", failureClass(metrics));
        assertFalse(String.valueOf(metrics.get("failureClassCounts")).contains("PHYSICAL_DISCONNECT_FAILED"));
        Map<String, Object> session = onlySession(metrics);
        assertEquals("TIMED_OUT", session.get("confirmationState"));
        assertEquals("LOAD_RESOURCE_TEARDOWN_TIMEOUT", session.get("failureClass"));
        assertEquals(1, pool.total());
    }

    @Test void cleanDisconnectConfirmsReleaseAndFreesCapacity() {
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = pool(2, 200L, closes, false);
        pool.ensureMinIdle(2);
        pool.close();
        Map<String, Object> metrics = pool.metrics();
        assertEquals(0, pool.total());
        assertEquals(2, closes.get());
        assertEquals("CONFIRMED", metrics.get("safeCloseClassification"));
        assertEquals(0, number(metrics.get("disconnectFailureCount")));
        for (Map<String, Object> session : sessions(metrics)) {
            assertEquals("CONFIRMED_CLOSED", session.get("confirmationState"));
            assertEquals("NOT_FAILED", session.get("firstObserved"));
        }
        pool.close();
        assertEquals(2, closes.get());
    }

    @Test void independentOutageDoesNotReleaseCapacityWhenDisconnectFails() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 80L,
                () -> new Handle(ids.incrementAndGet()), value -> {
                    closes.incrementAndGet();
                    throw disconnectFailed();
                });
        pool.ensureMinIdle(1);
        pool.noteIndependentOutage("MQ_QM_UNAVAILABLE");
        assertEquals(1, closes.get());
        assertEquals(1, pool.total());
        assertEquals(1, ids.get());
        assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
        assertThrows(IllegalStateException.class, pool::close);
        Map<String, Object> session = onlySession(pool.metrics());
        assertEquals(Boolean.TRUE, session.get("independentOutage"));
        assertEquals("MQ_QM_UNAVAILABLE", session.get("independentOutageCode"));
        assertEquals("QUARANTINED", session.get("confirmationState"));
        assertEquals(1, pool.total());
    }

    @Test void independentOutageWithConfirmedCloseIsNotReoffered() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 200L,
                () -> new Handle(ids.incrementAndGet()), value -> { });
        pool.ensureMinIdle(1);
        pool.noteIndependentOutage("MQ_NETWORK_OUTAGE");
        try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) {
            assertEquals(2, lease.value().id);
        }
        pool.close();
        boolean sawOutage = false;
        for (Map<String, Object> session : sessions(pool.metrics())) {
            if (Boolean.TRUE.equals(session.get("independentOutage"))) sawOutage = true;
        }
        assertTrue(sawOutage);
        assertEquals(0, pool.total());
    }

    @Test void brokenConnectionIsNeverReofferedAndReplacementStaysInsideMaxSize() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(2, 80L, () -> {
            int current = live.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            return new Handle(ids.incrementAndGet());
        }, value -> {
            live.decrementAndGet();
            throw disconnectFailed();
        });
        pool.ensureMinIdle(1);
        try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) { lease.invalidate(broken()); }
        try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) {
            assertEquals(2, lease.value().id);
            assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
        }
        assertEquals(2, ids.get());
        assertTrue(pool.total() <= 2);
        assertTrue(peak.get() <= 2);
        assertThrows(IllegalStateException.class, pool::close);
    }

    @Test void permitsAreReusedAcrossWavesAndClosersRunOnce() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = pool(2, 500L, closes, false);
        for (int wave = 0; wave < 3; wave++) {
            LoadResourcePool<Handle>.Lease first = pool.borrow();
            LoadResourcePool<Handle>.Lease second = pool.borrow();
            assertNotEquals(first.value().id, second.value().id);
            first.close();
            second.close();
        }
        assertEquals(0, pool.active());
        assertEquals(2, pool.idle());
        assertEquals(2, pool.total());
        pool.close();
        assertEquals(2, closes.get());
        assertEquals(0, pool.total());
    }

    @Test void defaultBorrowPathDoesNotValidateAndStalePolicyValidatesOnlyAfterTheThreshold() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) { validations.incrementAndGet(); }
        };
        LoadResourcePool<Handle> fast = new LoadResourcePool<Handle>(2, 500L, handles(), value -> { },
                LoadResourcePool.Health.disabled(), validator);
        long started = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            try (LoadResourcePool<Handle>.Lease lease = fast.borrow()) { assertNotNull(lease.value()); }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(0, validations.get());
        assertEquals(0, number(fast.metrics().get("validationAttempts")));
        assertTrue(elapsedMs < 3_000L, "default borrow path elapsedMs=" + elapsedMs);
        fast.close();

        final long[] extra = new long[] {0L};
        LoadResourcePool<Handle> stale = new LoadResourcePool<Handle>(1, 500L, handles(), value -> { },
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 60_000L, 200L, 0L, 0L, 0)), validator);
        stale.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return System.nanoTime() + extra[0]; }
        };
        stale.ensureMinIdle(1);
        for (int i = 0; i < 100; i++) {
            try (LoadResourcePool<Handle>.Lease lease = stale.borrow()) { assertNotNull(lease.value()); }
        }
        assertEquals(0, validations.get());
        extra[0] = TimeUnit.MILLISECONDS.toNanos(60_000L);
        try (LoadResourcePool<Handle>.Lease lease = stale.borrow()) { assertNotNull(lease.value()); }
        assertEquals(1, validations.get());
        for (int i = 0; i < 100; i++) {
            try (LoadResourcePool<Handle>.Lease lease = stale.borrow()) { assertNotNull(lease.value()); }
        }
        assertEquals(1, validations.get());
        stale.close();
    }

    @Test void zeroThresholdValidatesEveryIdleBorrowAndMaxLifetimeRecyclesWithoutValidation() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) { validations.incrementAndGet(); }
        };
        LoadResourcePool<Handle> always = new LoadResourcePool<Handle>(1, 500L,
                () -> new Handle(created.incrementAndGet()), value -> closed.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 200L, 0L, 0L, 0)), validator);
        always.ensureMinIdle(1);
        long started = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            try (LoadResourcePool<Handle>.Lease lease = always.borrow()) { assertNotNull(lease.value()); }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(100, validations.get(), "threshold 0 checks every idle borrow");
        assertTrue(elapsedMs < 3_000L, "local validation elapsedMs=" + elapsedMs);
        assertEquals(0, closed.get());
        always.close();
        assertEquals(1, closed.get());

        final long[] now = new long[] {1_000_000_000L};
        AtomicInteger lifeCreated = new AtomicInteger();
        AtomicInteger lifeClosed = new AtomicInteger();
        LoadResourcePool<Handle> limited = new LoadResourcePool<Handle>(1, 500L,
                () -> new Handle(lifeCreated.incrementAndGet()), value -> lifeClosed.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(false, 60_000L, 200L, 0L, 1_000L, 0)), null);
        limited.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        limited.disconnectStarter = new Consumer<Runnable>() {
            @Override public void accept(Runnable task) { task.run(); }
        };
        limited.ensureMinIdle(1);
        now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
        for (int i = 0; i < 5; i++) {
            try (LoadResourcePool<Handle>.Lease lease = limited.borrow()) { assertNotNull(lease.value()); }
            now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
        }
        assertEquals(0, number(limited.metrics().get("validationAttempts")));
        assertTrue(lifeCreated.get() > 1, "maxLifetime must replace expired sessions");
        assertTrue(number(limited.metrics().get("discarded")) > 0);
        limited.close();
        assertEquals(lifeCreated.get(), lifeClosed.get());
        assertTrue(lifeCreated.get() <= 8);
    }

    @Test void hungValidationIsNotOfferedAndAnotherConnectionIsReturned() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        AtomicInteger ids = new AtomicInteger();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) throws Exception {
                if (value.id == 1) {
                    started.countDown();
                    new CountDownLatch(1).await();
                }
            }
        };
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(2, 1_000L,
                () -> new Handle(ids.incrementAndGet()), value -> { },
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 50L, 0L, 0L, 0)), validator);
        pool.ensureMinIdle(1);
        long began = System.nanoTime();
        LoadResourcePool<Handle>.Lease lease = pool.borrow();
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertNotEquals(1, lease.value().id);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 1_000L);
        } finally {
            lease.close();
        }
    }

    @Test void healthEventsAreRateLimited() throws Exception {
        final long[] now = new long[] {5_000_000_000L};
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(2, 500L, handles(), value -> { },
                LoadResourcePool.Health.from(new MqPoolLifecycle(false, 60_000L, 200L, 0L, 1_000L, 0)), null);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        pool.disconnectStarter = new Consumer<Runnable>() {
            @Override public void accept(Runnable task) { task.run(); }
        };
        pool.ensureMinIdle(2);
        now[0] += TimeUnit.MILLISECONDS.toNanos(5_000L);
        try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) { assertNotNull(lease.value()); }
        Map<String, Object> metrics = pool.metrics();
        assertTrue(number(metrics.get("suppressedHealthEvents")) >= 1, metrics.toString());
        assertTrue(((List<?>) metrics.get("healthEvents")).size() <= 8);
        pool.close();
    }

    @Test void concurrentBorrowersStayInsideCapacity() throws Exception {
        final Set<Handle> live = Collections.newSetFromMap(new IdentityHashMap<Handle, Boolean>());
        final AtomicInteger peak = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(4, 2_000L, new java.util.function.Supplier<Handle>() {
            private final AtomicInteger ids = new AtomicInteger();
            @Override public Handle get() {
                Handle handle = new Handle(ids.incrementAndGet());
                synchronized (live) {
                    live.add(handle);
                    peak.accumulateAndGet(live.size(), Math::max);
                }
                return handle;
            }
        }, new Consumer<Handle>() {
            @Override public void accept(Handle value) {
                synchronized (live) { live.remove(value); }
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) tasks.add(workers.submit(new Runnable() {
                @Override public void run() {
                    for (int i = 0; i < 40; i++) {
                        try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) { Thread.yield(); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    }
                }
            }));
            for (Future<?> task : tasks) task.get(10L, TimeUnit.SECONDS);
            assertTrue(peak.get() <= 4, "peak=" + peak.get());
            assertEquals(0, pool.active());
            assertTrue(pool.total() <= 4);
        } finally {
            workers.shutdownNow();
            pool.close();
        }
        assertEquals(0, pool.total());
    }

    private static LoadResourcePool<Handle> pool(int maxSize, long timeoutMs, final AtomicInteger closes, final boolean fail) {
        return new LoadResourcePool<Handle>(maxSize, timeoutMs, handles(), new Consumer<Handle>() {
            @Override public void accept(Handle value) {
                closes.incrementAndGet();
                if (fail) throw disconnectFailed();
            }
        });
    }

    private static java.util.function.Supplier<Handle> handles() {
        final AtomicInteger ids = new AtomicInteger();
        return new java.util.function.Supplier<Handle>() {
            @Override public Handle get() { return new Handle(ids.incrementAndGet()); }
        };
    }

    private static RuntimeException disconnectFailed() {
        return new IllegalStateException("MQ_CONNECTION_DISCONNECT_FAILED", broken());
    }

    private static MqTransport.Exception broken() {
        return new MqTransport.Exception("MQJE001: Completion Code '2', Reason '2009'", 2, 2009, "MQRC_CONNECTION_BROKEN", null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sessions(Map<String, Object> metrics) {
        Object value = metrics.get("physicalSessions");
        assertTrue(value instanceof List, String.valueOf(metrics));
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> onlySession(Map<String, Object> metrics) {
        List<Map<String, Object>> rows = sessions(metrics);
        assertEquals(1, rows.size(), metrics.toString());
        return rows.get(0);
    }

    private static String failureClass(Map<String, Object> metrics) {
        Object counts = metrics.get("failureClassCounts");
        assertTrue(counts instanceof Map, metrics.toString());
        assertEquals(1, ((Map<?, ?>) counts).size(), counts.toString());
        return String.valueOf(((Map<?, ?>) counts).keySet().iterator().next());
    }

    private static int number(Object value) { return ((Number) value).intValue(); }

    private static final class Handle {
        final int id;
        Handle(int id) { this.id = id; }
    }
}
