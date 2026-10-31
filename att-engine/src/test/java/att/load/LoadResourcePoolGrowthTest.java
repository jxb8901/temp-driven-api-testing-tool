package att.load;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadResourcePoolGrowthTest {
    @Test
    void coldGrowthCreatesResourcesOutsideMonitorAndNeverExceedsPoolLimit() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger activeFactories = new AtomicInteger();
        AtomicInteger peakFactories = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(2, 1000L, () -> {
            int active = activeFactories.incrementAndGet();
            peakFactories.accumulateAndGet(active, Math::max);
            entered.countDown();
            try {
                if (!release.await(2L, TimeUnit.SECONDS)) throw new IllegalStateException("factory barrier timed out");
                return ids.incrementAndGet();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            } finally { activeFactories.decrementAndGet(); }
        }, ignored -> {});
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> borrowAndRelease(pool));
            Future<?> second = workers.submit(() -> borrowAndRelease(pool));
            assertTrue(entered.await(1L, TimeUnit.SECONDS), "both cold creations should enter the factory concurrently");
            release.countDown();
            first.get(2L, TimeUnit.SECONDS);
            second.get(2L, TimeUnit.SECONDS);
            assertEquals(2, peakFactories.get());
            assertEquals(2, pool.total());
            assertEquals(2, pool.idle());
            assertEquals(0, pool.active());
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2L, TimeUnit.SECONDS);
            pool.close();
        }
    }

    @Test
    void borrowerWaitsForReservedIdleCreationAndTimesOutOnce() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger ids = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(2, 150L, () -> {
            int id = ids.incrementAndGet();
            if (id == 2) {
                creating.countDown();
                try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return id;
        }, ignored -> {});
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (LoadResourcePool<Integer>.Lease first = pool.borrow()) {
            Future<?> refill = workers.submit(() -> pool.ensureMinIdle(1));
            assertTrue(creating.await(1, TimeUnit.SECONDS));
            Future<Boolean> timed = workers.submit(() -> {
                try { pool.borrow(); return false; }
                catch (LoadResourcePool.PoolTimeoutException expected) { return true; }
            });
            assertTrue(timed.get(1, TimeUnit.SECONDS));
            assertEquals(1, pool.timeoutCount());
            assertEquals(2, ids.get());
            Future<?> waitingBorrow = workers.submit(() -> borrowAndRelease(pool));
            finish.countDown();
            refill.get(1, TimeUnit.SECONDS);
            waitingBorrow.get(1, TimeUnit.SECONDS);
            assertEquals(0, pool.waiting());
        } finally {
            finish.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            pool.close();
        }
    }

    @Test
    void blockedFactoryTimesOutWithoutReleasingItsPhysicalReservation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger creates = new AtomicInteger(), closes = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 120L, () -> {
            creates.incrementAndGet(); entered.countDown();
            boolean interrupted = false;
            while (true) {
                try { finish.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return 1;
        }, value -> closes.incrementAndGet());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            Future<?> first = workers.submit(() -> org.junit.jupiter.api.Assertions.assertThrows(
                    LoadResourcePool.PoolTimeoutException.class, pool::borrow));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            first.get(1, TimeUnit.SECONDS);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 900);
            assertEquals(1, pool.total());
            assertEquals(1, pool.metrics().get("creating"));
            assertEquals(1, pool.timeoutCount());
            org.junit.jupiter.api.Assertions.assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
            assertEquals(1, creates.get(), "a timed-out factory must keep its physical slot");
            assertEquals(2, pool.timeoutCount());
            assertEquals(0, pool.waiting());
            finish.countDown();
            try (LoadResourcePool<Integer>.Lease lease = pool.borrow()) {
                assertEquals(1, lease.value());
            }
            assertEquals(1, creates.get());
        } finally {
            finish.countDown(); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS); pool.close();
        }
        assertEquals(1, closes.get());
        assertEquals(0, pool.total());
    }

    @Test
    void failedFactoryReleasesReservationAndPermitForRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 1000L, () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("connect failed");
            return 7;
        }, ignored -> {});
        try {
            IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, pool::borrow);
            assertEquals("connect failed", error.getMessage());
            assertEquals(0, pool.total()); assertEquals(1, pool.failures());
            try (LoadResourcePool<Integer>.Lease lease = pool.borrow()) { assertEquals(7, lease.value()); }
            assertEquals(2, calls.get()); assertEquals(0, pool.waiting());
        } finally { pool.close(); }
    }

    @Test
    void interruptedBorrowDoesNotReleaseAnInFlightFactorySlot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger creates = new AtomicInteger(), closes = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 5000L, () -> {
            creates.incrementAndGet(); entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return 1;
        }, value -> closes.incrementAndGet());
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> borrower = workers.submit(() -> borrowAndRelease(pool));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(borrower.cancel(true));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (pool.waiting() != 0 && System.nanoTime() < end) Thread.yield();
            assertEquals(0, pool.waiting()); assertEquals(1, pool.total());
            assertEquals(1, pool.metrics().get("creating")); assertEquals(1, creates.get());
            finish.countDown();
            try (LoadResourcePool<Integer>.Lease lease = pool.borrow()) { assertEquals(1, lease.value()); }
            assertEquals(1, creates.get());
        } finally {
            finish.countDown(); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS); pool.close();
        }
        assertEquals(1, closes.get());
    }

    @Test
    void zeroBorrowTimeoutOnlyUsesImmediatelyIdleResource() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 0L, creates::incrementAndGet, ignored -> {});
        try {
            org.junit.jupiter.api.Assertions.assertThrows(LoadResourcePool.PoolTimeoutException.class, pool::borrow);
            assertEquals(0, creates.get());
            pool.ensureMinIdle(1);
            try (LoadResourcePool<Integer>.Lease lease = pool.borrow()) { assertEquals(1, lease.value()); }
        } finally { pool.close(); }
    }

    @Test
    void shutdownTerminatesWhenPhysicalConnectNeverReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 40L, () -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return 1;
        }, value -> closes.incrementAndGet());
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> preparation = workers.submit(() -> pool.ensureMinIdle(1));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            IllegalStateException timeout = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, pool::close);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
            assertTrue(elapsedMs < 15_000L, "shutdown must honor its deadline, elapsedMs=" + elapsedMs);
            Map<String, Object> metrics = pool.metrics();
            assertEquals(1, metrics.get("residualCreating"));
            assertEquals(0, metrics.get("residualClosing"));
            assertEquals(1, metrics.get("creating"));
            assertEquals(1, metrics.get("total"));
            assertEquals(0, closes.get());
            preparation.cancel(true);
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownTerminatesWhenPhysicalDisconnectNeverReturns() throws Exception {
        CountDownLatch enteredClose = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 40L, () -> 1, value -> {
            enteredClose.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            closes.incrementAndGet();
        });
        pool.ensureMinIdle(1);
        long started = System.nanoTime();
        IllegalStateException timeout = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, pool::close);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(enteredClose.await(1, TimeUnit.SECONDS));
        assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
        assertTrue(elapsedMs < 15_000L, "shutdown must honor its deadline while a closer is stuck, elapsedMs=" + elapsedMs);
        Map<String, Object> metrics = pool.metrics();
        assertEquals(0, metrics.get("residualCreating"));
        assertEquals(1, metrics.get("residualClosing"));
        assertEquals(1, metrics.get("closing"));
        assertEquals(1, metrics.get("total"));
        assertEquals(0, closes.get());
    }

    @Test
    void shutdownRetainsReservationWhenDisconnectWorkerCannotStart() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 40L, () -> 1, value -> closes.incrementAndGet());
        pool.ensureMinIdle(1);
        pool.disconnectStarter = task -> { throw new IllegalStateException("no disconnect workers"); };
        long started = System.nanoTime();
        IllegalStateException timeout = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, pool::close);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
        assertTrue(elapsedMs < 15_000L, "shutdown must not block on a failed worker start, elapsedMs=" + elapsedMs);
        assertEquals(1, pool.metrics().get("residualClosing"));
        assertEquals(1, pool.total());
        assertEquals(0, closes.get());
        assertEquals(1, pool.closeFailureCount());
    }

    @Test
    void lateDisconnectAfterShutdownDeadlineRetainsThenReleasesReservation() throws Exception {
        CountDownLatch enteredClose = new CountDownLatch(1);
        CountDownLatch finishClose = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 40L, () -> 1, value -> {
            enteredClose.countDown();
            try { finishClose.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            closes.incrementAndGet();
        });
        pool.ensureMinIdle(1);
        IllegalStateException timeout = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, pool::close);
        assertTrue(timeout.getMessage().contains("LOAD_RESOURCE_TEARDOWN_TIMEOUT"), timeout.getMessage());
        assertTrue(enteredClose.await(1, TimeUnit.SECONDS));
        assertEquals(1, pool.metrics().get("residualClosing"));
        assertEquals(1, pool.total());
        assertEquals(0, closes.get());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, pool::borrow);
        finishClose.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((Integer) pool.metrics().get("closing") > 0 && System.nanoTime() < until) Thread.yield();
        assertEquals(0, pool.metrics().get("closing"));
        assertEquals(0, pool.total());
        assertEquals(1, closes.get());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, pool::borrow);
    }

    @Test
    void shutdownClosesConnectionThatArrivesAfterBorrowTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 50L, () -> {
            entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return 1;
        }, value -> closes.incrementAndGet());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> borrower = workers.submit(() -> org.junit.jupiter.api.Assertions.assertThrows(
                    LoadResourcePool.PoolTimeoutException.class, pool::borrow));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            borrower.get(1, TimeUnit.SECONDS);
            Future<?> shutdown = workers.submit(pool::close);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!Boolean.TRUE.equals(pool.metrics().get("closed")) && System.nanoTime() < end) Thread.yield();
            assertEquals(true, pool.metrics().get("closed"));
            finish.countDown(); shutdown.get(1, TimeUnit.SECONDS);
            assertEquals(1, closes.get()); assertEquals(0, pool.total());
        } finally {
            finish.countDown(); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedCloseQuarantinesCapacityAndDoesNotCreateReplacement() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 40L,
                created::incrementAndGet, value -> { closes.incrementAndGet(); throw new IllegalStateException("disconnect failed"); });
        LoadResourcePool<Integer>.Lease lease = pool.borrow();
        lease.invalidate();
        lease.close();
        LoadResourcePool.PoolTimeoutException timeout = org.junit.jupiter.api.Assertions.assertThrows(
                LoadResourcePool.PoolTimeoutException.class, pool::borrow);
        assertEquals(1, timeout.snapshot().get("total"));
        assertEquals(1, timeout.snapshot().get("timeoutCount"));
        assertEquals(1, created.get());
        assertEquals(1, closes.get());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, pool::close);
    }

    @Test
    void shutdownWakesBorrowerWaitingOnCreation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(1, 5000L, () -> {
            entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return 1;
        }, ignored -> {});
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> refill = workers.submit(() -> pool.ensureMinIdle(1));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Future<?> borrower = workers.submit(() -> borrowAndRelease(pool));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (pool.waiting() == 0 && System.nanoTime() < until) Thread.yield();
            assertEquals(1, pool.waiting());
            Future<?> closing = workers.submit(pool::close);
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> borrower.get(1, TimeUnit.SECONDS));
            finish.countDown();
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> refill.get(1, TimeUnit.SECONDS));
            closing.get(1, TimeUnit.SECONDS);
            assertEquals(0, pool.total());
            assertEquals(0, pool.waiting());
        } finally {
            finish.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidationRetainsSlotUntilPhysicalCloseCompletes() throws Exception {
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch finishClose = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(2, 1000L, created::incrementAndGet, value -> {
            if (value == 1) {
                closing.countDown();
                try { finishClose.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (LoadResourcePool<Integer>.Lease first = pool.borrow();
             LoadResourcePool<Integer>.Lease second = pool.borrow()) {
            Future<?> invalidation = workers.submit(first::invalidate);
            assertTrue(closing.await(1, TimeUnit.SECONDS));
            Future<?> borrower = workers.submit(() -> borrowAndRelease(pool));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (pool.waiting() == 0 && System.nanoTime() < until) Thread.yield();
            assertEquals(1, pool.waiting());
            assertEquals(2, pool.total());
            assertEquals(2, created.get());
            finishClose.countDown();
            invalidation.get(1, TimeUnit.SECONDS);
            borrower.get(1, TimeUnit.SECONDS);
            assertEquals(3, created.get());
        } finally {
            finishClose.countDown(); workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS); pool.close();
        }
    }

    @Test
    void concurrentBurstsDrainWithoutExceedingPhysicalCapacity() throws Exception {
        AtomicInteger live = new AtomicInteger(), peak = new AtomicInteger(), ids = new AtomicInteger();
        LoadResourcePool<Integer> pool = new LoadResourcePool<Integer>(8, 5000L, () -> {
            int count = live.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
            return ids.incrementAndGet();
        }, ignored -> live.decrementAndGet());
        ExecutorService workers = Executors.newFixedThreadPool(32);
        try {
            for (int burst = 0; burst < 4; burst++) {
                CountDownLatch start = new CountDownLatch(1);
                java.util.List<Future<?>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < 128; i++) futures.add(workers.submit(() -> {
                    try {
                        start.await();
                        try (LoadResourcePool<Integer>.Lease lease = pool.borrow()) {
                            if (lease.value() == null) throw new AssertionError("missing resource");
                            Thread.sleep(2L);
                        }
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                }));
                start.countDown();
                for (Future<?> future : futures) future.get(10L, TimeUnit.SECONDS);
                assertEquals(0, pool.active()); assertEquals(0, pool.waiting());
                assertEquals(pool.total(), pool.idle());
            }
            assertTrue(peak.get() <= 8); assertEquals(0, pool.timeoutCount());
        } finally {
            workers.shutdownNow(); workers.awaitTermination(2L, TimeUnit.SECONDS); pool.close();
        }
        assertEquals(0, live.get());
    }

    private void borrowAndRelease(LoadResourcePool<Integer> pool) {
        try (LoadResourcePool<Integer>.Lease lease = pool.borrow()) {
            if (lease.value() == null) throw new AssertionError("missing resource");
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
