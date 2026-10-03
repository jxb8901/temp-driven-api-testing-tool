package att.load;

import org.junit.jupiter.api.Test;

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

    private void borrowAndRelease(LoadResourcePool<Integer> pool) {
        try (LoadResourcePool<Integer>.Lease lease = pool.borrow()) {
            if (lease.value() == null) throw new AssertionError("missing resource");
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
