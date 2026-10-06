package att.load;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadSchedulerTimingTest {
    @Test void anchoredTimeAdvancesWithMonotonicElapsedClock() {
        AtomicLong monotonicMillis = new AtomicLong(400L);
        LoadSchedulerTiming timing = LoadSchedulerTiming.anchored(
                monotonicMillis::get, 1_700_000_000_000L, 400L, millis -> { });

        assertEquals(1_700_000_000_000L, timing.now());
        monotonicMillis.addAndGet(125L);
        assertEquals(1_700_000_000_125L, timing.now());
    }
}
