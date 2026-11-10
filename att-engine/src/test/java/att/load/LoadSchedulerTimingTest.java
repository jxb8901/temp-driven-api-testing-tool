package att.load;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadSchedulerTimingTest {
    @Test void pendingVirtualIterationWaitsForCompletionWithoutAdvancingToHugeDeadline() throws Exception {
        AtomicLong now = new AtomicLong(45L);
        AtomicLong advancedBySleeper = new AtomicLong();
        LoadSchedulerTiming timing = new LoadSchedulerTiming(now::get, millis -> {
            advancedBySleeper.addAndGet(millis);
            now.addAndGet(millis);
        });
        LinkedBlockingQueue<String> completions = new LinkedBlockingQueue<String>();
        ExecutorService control = Executors.newSingleThreadExecutor();
        try {
            Future<String> awaited = control.submit(() -> timing.await(completions, Long.MAX_VALUE - 45L, true));
            completions.offer("worker-complete");
            assertEquals("worker-complete", awaited.get(2L, TimeUnit.SECONDS));
            assertEquals(45L, now.get(), "A pending worker must not let the virtual clock jump to Long.MAX_VALUE");
            assertEquals(0L, advancedBySleeper.get());
        } finally {
            control.shutdownNow();
            control.awaitTermination(2L, TimeUnit.SECONDS);
        }
    }

    @Test void anchoredTimeAdvancesWithMonotonicElapsedClock() {
        AtomicLong monotonicMillis = new AtomicLong(400L);
        LoadSchedulerTiming timing = LoadSchedulerTiming.anchored(
                monotonicMillis::get, 1_700_000_000_000L, 400L, millis -> { });

        assertEquals(1_700_000_000_000L, timing.now());
        monotonicMillis.addAndGet(125L);
        assertEquals(1_700_000_000_125L, timing.now());
    }
}
