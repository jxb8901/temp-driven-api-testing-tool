package att.load;

import att.config.MqPoolLifecycle;
import att.exec.MqTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        awaitCount(closes, 1);
        awaitClosing(pool);
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
        awaitCount(closes, 1);
        awaitClosing(pool);
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
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pool.total() != 0 && System.nanoTime() < until) Thread.yield();
        assertEquals(0, pool.total());
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

    @Test void invalidateWithHungDisconnectReturnsTheWorkerAndKeepsTheSlot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(2, 200L, () -> new Handle(ids.incrementAndGet()), value -> {
            closes.incrementAndGet();
            if (value.id != 1) return;
            entered.countDown();
            try { releaseClose.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            throw disconnectFailed();
        });
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            LoadResourcePool<Handle>.Lease first = pool.borrow();
            long began = System.nanoTime();
            Future<?> invalidated = workers.submit(new Runnable() {
                @Override public void run() { first.invalidate(broken()); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            invalidated.get(1, TimeUnit.SECONDS);
            long returnMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertTrue(returnMs < 1_000L, "workerReturnMs=" + returnMs);
            assertEquals(1, closes.get());
            assertEquals(0, pool.active());
            assertEquals(0, pool.idle());
            assertEquals(1, pool.total());
            assertEquals(1, number(pool.metrics().get("closing")));
            try (LoadResourcePool<Handle>.Lease second = pool.borrow()) {
                assertEquals(2, second.value().id);
                assertEquals(2, pool.total());
                assertEquals(2, pool.maxSize());
                assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
                assertEquals(2, ids.get());
                assertEquals(1, closes.get());
            }
            try (LoadResourcePool<Handle>.Lease again = pool.borrow()) {
                assertEquals(2, again.value().id);
            }
            assertEquals(2, ids.get());
            assertEquals(2, pool.total());
            assertTrue(pool.total() <= pool.maxSize());
            releaseClose.countDown();
            awaitClosing(pool);
            assertEquals(1, closes.get());
            assertEquals(2, pool.total());
            IllegalStateException teardown = assertThrows(IllegalStateException.class, pool::close);
            assertTrue(teardown.getMessage().contains("LOAD_RESOURCE_TEARDOWN_FAILED"), teardown.getMessage());
            assertEquals(1L, pool.failedCloseCount());
            assertEquals(1, pool.total());
            assertEquals(2, closes.get());
            assertEquals(2, ids.get());
        } finally {
            releaseClose.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            try { pool.close(); }
            catch (RuntimeException ignored) { }
        }
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

    @Test void lateConnectionBrokenAfterDeadlineCountsOnceAndKeepsTimeoutClass() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 40L, () -> new Handle(1), value -> {
            closes.incrementAndGet();
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw disconnectFailed();
        });
        pool.ensureMinIdle(1);
        IllegalStateException timeout = assertThrows(IllegalStateException.class, pool::close);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
        assertEquals(1, number(pool.metrics().get("residualClosing")));
        assertEquals(1L, pool.failedCloseCount());
        release.countDown();
        awaitClosing(pool);
        assertEquals(1, closes.get());
        assertEquals(1L, pool.failedCloseCount());
        assertEquals(1, pool.total());
        Map<String, Object> metrics = pool.metrics();
        assertEquals(1, number(metrics.get("disconnectAttemptCount")));
        assertEquals(1, number(metrics.get("disconnectFailureCount")));
        assertEquals("LOAD_RESOURCE_TEARDOWN_TIMEOUT", metrics.get("failureClass"));
        assertFalse(String.valueOf(metrics.get("failureClassCounts")).contains("PHYSICAL_DISCONNECT_FAILED"));
        Map<String, Object> session = onlySession(metrics);
        assertEquals("TIMED_OUT", session.get("confirmationState"));
        assertEquals("LOAD_RESOURCE_TEARDOWN_TIMEOUT", session.get("failureClass"));
        assertEquals(2, number(session.get("mqCompletionCode")));
        assertEquals(2009, number(session.get("mqReasonCode")));
        assertEquals(Boolean.FALSE, metrics.get("cleanupFailuresIncluded"));
    }

    @Test void lateSuccessfulDisconnectAfterDeadlineStillCountsOneFrozenTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 40L, () -> new Handle(1), value -> {
            closes.incrementAndGet();
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        pool.ensureMinIdle(1);
        IllegalStateException timeout = assertThrows(IllegalStateException.class, pool::close);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
        release.countDown();
        awaitClosing(pool);
        assertEquals(1, closes.get());
        assertEquals(0, pool.total());
        assertEquals(1L, pool.failedCloseCount());
        Map<String, Object> metrics = pool.metrics();
        assertEquals(1, number(metrics.get("disconnectAttemptCount")));
        assertEquals(0, number(metrics.get("disconnectFailureCount")));
        assertEquals("LOAD_RESOURCE_TEARDOWN_TIMEOUT", failureClass(metrics));
        Map<String, Object> session = onlySession(metrics);
        assertEquals("TIMED_OUT", session.get("confirmationState"));
        assertEquals("LOAD_RESOURCE_TEARDOWN_TIMEOUT", session.get("failureClass"));
        assertEquals("NOT_APPLIED", metrics.get("safeCloseClassification"));
    }

    @Test void lateConnectThenConnectionBrokenCountsOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 40L, () -> {
            entered.countDown();
            try { releaseConnect.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new Handle(1);
        }, value -> {
            closes.incrementAndGet();
            throw disconnectFailed();
        });
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> preparation = workers.submit(() -> pool.ensureMinIdle(1));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            IllegalStateException timeout = assertThrows(IllegalStateException.class, pool::close);
            assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
            Map<String, Object> frozen = pool.metrics();
            assertEquals(1, number(frozen.get("residualCreating")));
            assertEquals(0, number(frozen.get("residualClosing")));
            assertEquals(1, number(frozen.get("creating")));
            assertEquals(0, number(frozen.get("disconnectAttemptCount")));
            assertEquals(1L, pool.failedCloseCount());
            assertSingleClass(frozen, "LOAD_RESOURCE_TEARDOWN_TIMEOUT");
            assertEquals("TIMED_OUT", onlySession(frozen).get("confirmationState"));
            releaseConnect.countDown();
            ExecutionException finished = assertThrows(ExecutionException.class, () -> preparation.get(2, TimeUnit.SECONDS));
            assertTrue(finished.getCause() instanceof IllegalStateException, String.valueOf(finished.getCause()));
            awaitClosing(pool);
            assertEquals(1, closes.get());
            assertEquals(1, pool.total());
            assertEquals(1L, pool.failedCloseCount());
            assertEquals(0, pool.closeFailureCount());
            Map<String, Object> metrics = pool.metrics();
            assertEquals(0, number(metrics.get("creating")));
            assertEquals(1, number(metrics.get("disconnectAttemptCount")));
            assertEquals(1, number(metrics.get("disconnectFailureCount")));
            assertSingleClass(metrics, "LOAD_RESOURCE_TEARDOWN_TIMEOUT");
            assertFalse(String.valueOf(metrics.get("failureClassCounts")).contains("PHYSICAL_DISCONNECT_FAILED"));
            Map<String, Object> session = onlySession(metrics);
            assertEquals("TIMED_OUT", session.get("confirmationState"));
            assertEquals("LOAD_RESOURCE_TEARDOWN_TIMEOUT", session.get("failureClass"));
            assertEquals(2, number(session.get("mqCompletionCode")));
            assertEquals(2009, number(session.get("mqReasonCode")));
            assertEquals(1, number(session.get("disconnectFailureCount")));
            assertEquals("NOT_APPLIED", metrics.get("safeCloseClassification"));
        } finally {
            releaseConnect.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test void lateConnectThenSuccessfulDisconnectKeepsOneFrozenTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 40L, () -> {
            entered.countDown();
            try { releaseConnect.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new Handle(1);
        }, value -> {
            closes.incrementAndGet();
            disconnected.countDown();
        });
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> borrower = workers.submit(() -> {
                try { pool.borrow(); }
                catch (Exception ignored) { }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            IllegalStateException timeout = assertThrows(IllegalStateException.class, pool::close);
            assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
            borrower.get(2, TimeUnit.SECONDS);
            Map<String, Object> frozen = pool.metrics();
            assertEquals(1, number(frozen.get("residualCreating")));
            assertEquals(0, number(frozen.get("residualClosing")));
            assertEquals(1L, pool.failedCloseCount());
            assertEquals(0, number(frozen.get("disconnectAttemptCount")));
            assertSingleClass(frozen, "LOAD_RESOURCE_TEARDOWN_TIMEOUT");
            assertEquals("TIMED_OUT", onlySession(frozen).get("confirmationState"));
            releaseConnect.countDown();
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            awaitClosing(pool);
            assertEquals(1, closes.get());
            assertEquals(0, pool.total());
            assertEquals(1L, pool.failedCloseCount());
            assertEquals(0, pool.closeFailureCount());
            Map<String, Object> metrics = pool.metrics();
            assertEquals(1, number(metrics.get("disconnectAttemptCount")));
            assertEquals(0, number(metrics.get("disconnectFailureCount")));
            assertSingleClass(metrics, "LOAD_RESOURCE_TEARDOWN_TIMEOUT");
            Map<String, Object> session = onlySession(metrics);
            assertEquals("TIMED_OUT", session.get("confirmationState"));
            assertEquals("LOAD_RESOURCE_TEARDOWN_TIMEOUT", session.get("failureClass"));
            assertEquals("NOT_APPLIED", metrics.get("safeCloseClassification"));
        } finally {
            releaseConnect.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test void validationCloserDoesNotSeeValidationInterrupt() throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger validations = new AtomicInteger();
        AtomicBoolean closerSawInterrupt = new AtomicBoolean(true);
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) throws Exception {
                if (validations.incrementAndGet() > 1) return;
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        };
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(2, 2_000L, handles(), new Consumer<Handle>() {
            @Override public void accept(Handle value) {
                closerSawInterrupt.set(Thread.currentThread().isInterrupted());
                closes.incrementAndGet();
                disconnected.countDown();
            }
        }, LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 80L, 0L, 0L, 0)), validator);
        pool.ensureMinIdle(1);
        try {
            try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) {
                assertNotNull(lease.value());
            }
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
            assertFalse(closerSawInterrupt.get());
        } finally {
            pool.close();
        }
    }

    @Test void outageDuringValidationDoesNotLeaseKnownDeadSession() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) throws Exception {
                started.countDown();
                release.await();
            }
        };
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(2, 2_000L, handles(),
                value -> closes.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 2_000L, 0L, 0L, 0)), validator);
        pool.ensureMinIdle(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<Handle> borrowed = workers.submit(() -> {
                try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) { return lease.value(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            pool.noteIndependentOutage("MQ_QM_UNAVAILABLE");
            release.countDown();
            Handle handle = borrowed.get(2, TimeUnit.SECONDS);
            assertNotEquals(1, handle.id);
            awaitCount(closes, 1);
            assertEquals(0, pool.active());
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            pool.close();
        }
    }

    @Test void independentOutageReturnsWhileDisconnectIsHung() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 40L, () -> new Handle(1), value -> {
            closes.incrementAndGet();
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        pool.ensureMinIdle(1);
        long started = System.nanoTime();
        pool.noteIndependentOutage("MQ_QM_UNAVAILABLE");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMs < 1_000L, "noteIndependentOutage blocked, elapsedMs=" + elapsedMs);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(1, pool.total());
        assertEquals(1, number(pool.metrics().get("closing")));
        assertEquals(0, number(pool.metrics().get("disconnectFailureCount")));
        long began = System.nanoTime();
        IllegalStateException timeout = assertThrows(IllegalStateException.class, pool::close);
        long closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
        assertTrue(closeMs < 15_000L, "closeMs=" + closeMs);
        Map<String, Object> metrics = pool.metrics();
        assertEquals(1, number(metrics.get("residualClosing")));
        assertEquals(1, pool.total());
        assertEquals(1L, pool.failedCloseCount());
        assertEquals("NOT_APPLIED", metrics.get("safeCloseClassification"));
        Map<String, Object> session = onlySession(metrics);
        assertEquals(Boolean.TRUE, session.get("independentOutage"));
        assertEquals("TIMED_OUT", session.get("confirmationState"));
    }

    @Test void interruptBeforeLeaseReturnsValidatedSessionToIdle() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) { }
        };
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 2_000L, handles(),
                value -> closes.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 2_000L, 0L, 0L, 0)), validator);
        pool.ensureMinIdle(1);
        pool.beforeValidationCommit = new Runnable() {
            @Override public void run() { Thread.currentThread().interrupt(); }
        };
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> borrower = workers.submit(new Runnable() {
                @Override public void run() {
                    try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) {
                        throw new AssertionError("cancelled borrow returned " + lease.value().id);
                    } catch (InterruptedException expected) {
                        Thread.currentThread().interrupt();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            });
            borrower.get(2, TimeUnit.SECONDS);
            assertEquals(0, pool.active());
            assertEquals(1, pool.idle());
            assertEquals(1, pool.total());
            assertEquals(0, closes.get());
            pool.beforeValidationCommit = null;
            try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) { assertNotNull(lease.value()); }
            assertEquals(0, closes.get());
        } finally {
            pool.beforeValidationCommit = null;
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            pool.close();
        }
    }

    @Test void cancelledValidationWakesBorrowerWaitingBehindAHungClose() throws Exception {
        final long[] now = new long[] {1_000_000_000L};
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final CountDownLatch validationEntered = new CountDownLatch(1);
        final CountDownLatch releaseValidation = new CountDownLatch(1);
        final CountDownLatch gotLease = new CountDownLatch(1);
        final CountDownLatch releaseLease = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger validations = new AtomicInteger();
        final AtomicReference<Handle> validated = new AtomicReference<Handle>();
        final AtomicReference<Handle> borrowed = new AtomicReference<Handle>();
        final AtomicReference<Thread> waiterThread = new AtomicReference<Thread>();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) throws Exception {
                if (validations.incrementAndGet() == 1) {
                    validated.set(value);
                    validationEntered.countDown();
                    assertTrue(releaseValidation.await(3, TimeUnit.SECONDS));
                }
            }
        };
        final LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(2, 2_000L, handles(), new Consumer<Handle>() {
            @Override public void accept(Handle value) {
                closes.incrementAndGet();
                closeEntered.countDown();
                try { releaseClose.await(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }, LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 5_000L, 1_000L, 0L, 0)), validator);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            pool.ensureMinIdle(1);
            now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
            pool.idleEvictIntervalNanos = TimeUnit.MILLISECONDS.toNanos(15);
            long sweepUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (closeEntered.getCount() > 0 && System.nanoTime() < sweepUntil) {
                synchronized (pool) { pool.notifyAll(); }
                Thread.yield();
            }
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            pool.idleEvictIntervalNanos = TimeUnit.SECONDS.toNanos(30);
            pool.ensureMinIdle(1);
            Future<?> validating = workers.submit(new Runnable() {
                @Override public void run() {
                    try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) {
                        throw new AssertionError("cancelled borrow returned " + lease.value().id);
                    } catch (InterruptedException expected) {
                        Thread.currentThread().interrupt();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            });
            assertTrue(validationEntered.await(2, TimeUnit.SECONDS));
            final long waiterStarted = System.nanoTime();
            Future<?> waiter = workers.submit(new Runnable() {
                @Override public void run() {
                    waiterThread.set(Thread.currentThread());
                    try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) {
                        borrowed.set(lease.value());
                        gotLease.countDown();
                        assertTrue(releaseLease.await(3, TimeUnit.SECONDS));
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            });
            long parkUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            long stableSince = -1L;
            while (System.nanoTime() < parkUntil) {
                Thread parked = waiterThread.get();
                boolean waiting = parked != null && parked.getState() == Thread.State.TIMED_WAITING && pool.waiting() >= 2;
                long at = System.nanoTime();
                if (!waiting) stableSince = -1L;
                else if (stableSince < 0L) stableSince = at;
                else if (at - stableSince >= TimeUnit.MILLISECONDS.toNanos(40)) break;
                Thread.yield();
            }
            Thread parked = waiterThread.get();
            assertNotNull(parked);
            assertEquals(Thread.State.TIMED_WAITING, parked.getState());
            assertTrue(pool.waiting() >= 2);
            final AtomicBoolean interruptOnce = new AtomicBoolean(true);
            pool.beforeValidationCommit = new Runnable() {
                @Override public void run() {
                    if (!interruptOnce.compareAndSet(true, false)) return;
                    // Drop the pool lock so the waiter can consume the validation worker's notify,
                    // observe the still-empty queue, and wait again. The offer below has to notify it.
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                    try {
                        pool.wait(30L);
                        while (parked.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until) pool.wait(20L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    Thread.currentThread().interrupt();
                }
            };
            releaseValidation.countDown();
            validating.get(2, TimeUnit.SECONDS);
            assertTrue(gotLease.await(1, TimeUnit.SECONDS), "waiter did not receive the idle session inside the borrow budget");
            long handoffMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waiterStarted);
            assertTrue(handoffMs < 2_000L, "handoffMs=" + handoffMs);
            assertNotNull(validated.get());
            assertEquals(validated.get().id, borrowed.get().id);
            assertEquals(1, pool.active());
            assertEquals(0, pool.idle());
            assertEquals(1, closes.get());
            assertEquals(2, pool.total());
            releaseLease.countDown();
            waiter.get(2, TimeUnit.SECONDS);
            assertEquals(0, pool.active());
            assertEquals(1, pool.idle());
            assertEquals(1, closes.get());
            assertEquals(2, pool.total());
        } finally {
            releaseValidation.countDown();
            releaseLease.countDown();
            releaseClose.countDown();
            pool.beforeValidationCommit = null;
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            pool.close();
        }
    }

    @Test void backgroundMaxIdleEvictsAnIdleSessionNobodyBorrows() throws Exception {
        final long[] now = new long[] {1_000_000_000L};
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger validations = new AtomicInteger();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) { validations.incrementAndGet(); }
        };
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 500L, handles(),
                value -> closes.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 200L, 1_000L, 0L, 0)), validator);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        pool.idleEvictIntervalNanos = TimeUnit.MILLISECONDS.toNanos(15);
        assertTrue(pool.idleEvictorAlive());
        pool.ensureMinIdle(1);
        now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
        awaitEviction(pool, closes, 1);
        assertEquals(0, validations.get());
        assertEquals(0, number(pool.metrics().get("validationAttempts")));
        awaitClosing(pool);
        assertEquals(0, pool.idle());
        assertEquals(0, pool.total());
        pool.close();
        assertFalse(pool.idleEvictorAlive());
    }

    @Test void backgroundMaxLifetimeEvictsWithoutValidationOrABorrow() throws Exception {
        final long[] now = new long[] {1_000_000_000L};
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger validations = new AtomicInteger();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) { validations.incrementAndGet(); }
        };
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 500L, handles(),
                value -> closes.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(false, 60_000L, 200L, 0L, 1_000L, 0)), validator);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        pool.idleEvictIntervalNanos = TimeUnit.MILLISECONDS.toNanos(15);
        pool.ensureMinIdle(1);
        now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
        awaitEviction(pool, closes, 1);
        assertEquals(0, validations.get());
        assertEquals(0, number(pool.metrics().get("validationAttempts")));
        awaitClosing(pool);
        assertEquals(0, pool.total());
        pool.close();
        assertFalse(pool.idleEvictorAlive());
    }

    @Test void idleEvictorStaysOffUnlessMaxIdleOrMaxLifetimeIsSet() {
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> plain = pool(1, 200L, closes, false);
        assertFalse(plain.idleEvictorAlive());
        plain.ensureMinIdle(1);
        plain.close();
        assertEquals(1, closes.get());
        assertFalse(plain.idleEvictorAlive());
        LoadResourcePool<Handle> validating = new LoadResourcePool<Handle>(1, 200L, handles(), value -> { },
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 200L, 0L, 0L, 0)),
                new LoadResourcePool.IdleValidator<Handle>() {
                    @Override public void validate(Handle value, long timeoutMs) { }
                });
        assertFalse(validating.idleEvictorAlive());
        validating.close();
        assertFalse(validating.idleEvictorAlive());
    }

    @Test void closeStopsTheIdleEvictor() {
        final long[] now = new long[] {1_000_000_000L};
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 200L, handles(),
                value -> closes.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(false, 60_000L, 200L, 60_000L, 0L, 0)), null);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        pool.ensureMinIdle(1);
        assertTrue(pool.idleEvictorAlive());
        pool.close();
        assertFalse(pool.idleEvictorAlive());
        assertEquals(1, closes.get());
        assertEquals(0, pool.total());
    }

    @Test void closeDuringBackgroundEvictionDisconnectsOnce() throws Exception {
        final long[] now = new long[] {1_000_000_000L};
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 40L, handles(), value -> {
            closes.incrementAndGet();
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }, LoadResourcePool.Health.from(new MqPoolLifecycle(false, 60_000L, 200L, 1_000L, 0L, 0)), null);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        pool.idleEvictIntervalNanos = TimeUnit.MILLISECONDS.toNanos(15);
        pool.ensureMinIdle(1);
        now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (entered.getCount() > 0 && System.nanoTime() < until) {
                synchronized (pool) { pool.notifyAll(); }
                Thread.yield();
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Future<?> shutdown = workers.submit(new Runnable() {
                @Override public void run() { pool.close(); }
            });
            release.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
            assertEquals(1, closes.get());
            assertEquals(0, pool.total());
            assertFalse(pool.idleEvictorAlive());
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test void frequentNotificationsDoNotSweepBeforeTheInterval() throws Exception {
        final long[] now = new long[] {1_000_000_000L};
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 200L, handles(),
                value -> closes.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(false, 60_000L, 200L, 1_000L, 0L, 0)), null);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        assertTrue(pool.idleEvictorAlive());
        assertEquals(TimeUnit.SECONDS.toNanos(30), pool.idleEvictIntervalNanos);
        pool.ensureMinIdle(1);
        now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
        int before = pool.idleSweepCount();
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        while (System.nanoTime() < until) {
            synchronized (pool) { pool.notifyAll(); }
            Thread.yield();
        }
        assertEquals(before, pool.idleSweepCount());
        assertEquals(0, closes.get());
        assertEquals(1, pool.idle());
        assertEquals(1, pool.total());
        pool.close();
        assertFalse(pool.idleEvictorAlive());
    }

    @Test void backgroundEvictionBoundsConcurrentDisconnects() throws Exception {
        final int sessions = 12;
        final long[] now = new long[] {1_000_000_000L};
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<Integer> closedIds = Collections.synchronizedSet(new HashSet<Integer>());
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(sessions, 200L, handles(), value -> {
            int current = running.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            closes.incrementAndGet();
            closedIds.add(value.id);
            try { releaseClose.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                running.decrementAndGet();
            }
        }, LoadResourcePool.Health.from(new MqPoolLifecycle(false, 60_000L, 200L, 1_000L, 0L, 0)), null);
        pool.nanoClock = new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return now[0]; }
        };
        try {
            pool.ensureMinIdle(sessions);
            now[0] += TimeUnit.MILLISECONDS.toNanos(1_000L);
            pool.idleEvictIntervalNanos = TimeUnit.MILLISECONDS.toNanos(15);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((pool.idle() > 0 || closes.get() < LoadResourcePool.DISCONNECT_PARALLELISM) && System.nanoTime() < until) {
                synchronized (pool) { pool.notifyAll(); }
                Thread.yield();
            }
            assertEquals(0, pool.idle());
            assertEquals(LoadResourcePool.DISCONNECT_PARALLELISM, closes.get());
            Thread.sleep(150L);
            assertEquals(LoadResourcePool.DISCONNECT_PARALLELISM, closes.get());
            assertEquals(LoadResourcePool.DISCONNECT_PARALLELISM, closedIds.size());
            assertTrue(peak.get() <= LoadResourcePool.DISCONNECT_PARALLELISM);
            assertEquals(sessions, pool.total());
            assertEquals(sessions, pool.created());
            assertEquals(0, pool.active());
            assertTrue(pool.total() <= pool.maxSize());
            releaseClose.countDown();
            long done = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pool.total() > 0 && System.nanoTime() < done) Thread.yield();
            assertEquals(0, pool.total());
            assertEquals(sessions, closes.get());
            assertEquals(sessions, closedIds.size());
            assertTrue(peak.get() <= LoadResourcePool.DISCONNECT_PARALLELISM, "peak=" + peak.get());
            pool.close();
            assertEquals(sessions, closes.get());
        } finally {
            releaseClose.countDown();
            try { pool.close(); }
            catch (RuntimeException ignored) { }
        }
    }

    @Test void closeInterruptsIdleValidationBeforeDisconnect() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicBoolean sawInterrupt = new AtomicBoolean();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) throws Exception {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException interrupted) {
                    sawInterrupt.set(true);
                    Thread.currentThread().interrupt();
                }
            }
        };
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 2_000L, () -> new Handle(1),
                value -> closes.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 5_000L, 0L, 0L, 0)), validator);
        pool.ensureMinIdle(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> borrower = workers.submit(new Runnable() {
                @Override public void run() {
                    try { pool.borrow(); }
                    catch (Exception ignored) { }
                }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            long began = System.nanoTime();
            pool.close();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertTrue(sawInterrupt.get());
            assertEquals(1, closes.get());
            assertEquals(0L, pool.failedCloseCount());
            assertTrue(elapsedMs < 4_000L, "elapsedMs=" + elapsedMs);
            borrower.get(2, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void validationStillRunningAtShutdownIsNotADisconnectTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool.IdleValidator<Handle> validator = new LoadResourcePool.IdleValidator<Handle>() {
            @Override public void validate(Handle value, long timeoutMs) {
                started.countDown();
                while (!release.get()) {
                    try { Thread.sleep(20L); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }
        };
        LoadResourcePool<Handle> pool = new LoadResourcePool<Handle>(1, 500L, () -> new Handle(1),
                value -> closes.incrementAndGet(),
                LoadResourcePool.Health.from(new MqPoolLifecycle(true, 0L, 50L, 0L, 0L, 0)), validator);
        pool.ensureMinIdle(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            workers.submit(new Runnable() {
                @Override public void run() {
                    try { pool.borrow(); }
                    catch (Exception ignored) { }
                }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            long began = System.nanoTime();
            IllegalStateException incomplete = assertThrows(IllegalStateException.class, pool::close);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertTrue(incomplete.getMessage().startsWith("MQ_IDLE_VALIDATION_INCOMPLETE"), incomplete.getMessage());
            assertFalse(incomplete.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), incomplete.getMessage());
            assertTrue(elapsedMs < 15_000L, "elapsedMs=" + elapsedMs);
            assertEquals(0, closes.get());
            assertEquals(0L, pool.failedCloseCount());
            assertEquals(1, number(pool.metrics().get("residualValidating")));
            Map<String, Object> session = onlySession(pool.metrics());
            assertEquals(null, session.get("failureClass"));
            assertEquals("VALIDATING", session.get("confirmationState"));
        } finally {
            release.set(true);
            workers.shutdownNow();
        }
    }

    @Test void cancellationStaysOutOfIdleWithoutMarkingKnownDead() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Handle> pool = pool(1, 80L, closes, true);
        try (LoadResourcePool<Handle>.Lease lease = pool.borrow()) { lease.invalidate(); }
        awaitCount(closes, 1);
        awaitClosing(pool);
        assertEquals(1, closes.get());
        assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
        assertThrows(IllegalStateException.class, pool::close);
        assertEquals(1, closes.get());
        Map<String, Object> metrics = pool.metrics();
        assertEquals(Boolean.FALSE, metrics.get("knownDeadBeforeTeardown"));
        Map<String, Object> session = onlySession(metrics);
        assertEquals(Boolean.FALSE, session.get("knownDeadBeforeTeardown"));
        assertEquals("INVALIDATED", session.get("exclusionCode"));
        assertEquals("TEARDOWN", session.get("firstObserved"));
        assertEquals(2009, number(session.get("mqReasonCode")));
    }

    private static void awaitCount(AtomicInteger counter, int expected) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (counter.get() < expected && System.nanoTime() < until) Thread.yield();
        assertEquals(expected, counter.get());
    }

    private static void awaitEviction(LoadResourcePool<Handle> pool, AtomicInteger closes, int expected) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (closes.get() < expected && System.nanoTime() < until) {
            synchronized (pool) { pool.notifyAll(); }
            Thread.yield();
        }
        assertEquals(expected, closes.get());
    }

    private static void awaitClosing(LoadResourcePool<Handle> pool) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (number(pool.metrics().get("closing")) > 0 && System.nanoTime() < until) Thread.yield();
        assertEquals(0, number(pool.metrics().get("closing")));
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

    @SuppressWarnings("unchecked")
    private static void assertSingleClass(Map<String, Object> metrics, String expected) {
        assertEquals(expected, failureClass(metrics));
        assertEquals(Integer.valueOf(1), ((Map<String, Integer>) metrics.get("failureClassCounts")).get(expected));
    }

    private static int number(Object value) { return ((Number) value).intValue(); }

    private static final class Handle {
        final int id;
        Handle(int id) { this.id = id; }
    }
}
