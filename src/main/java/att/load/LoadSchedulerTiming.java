package att.load;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Injectable timing boundary for deterministic scheduler tests. */
final class LoadSchedulerTiming {
    interface Clock {
        long now();
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Clock clock;
    private final Sleeper sleeper;

    LoadSchedulerTiming(Clock clock, Sleeper sleeper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    static LoadSchedulerTiming system() {
        final long monotonicOriginNanos = System.nanoTime();
        final long epochOriginMillis = System.currentTimeMillis();
        Clock elapsedMillis = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - monotonicOriginNanos);
        return anchored(elapsedMillis, epochOriginMillis, 0L, LoadSchedulerSupport::sleep);
    }

    static LoadSchedulerTiming anchored(Clock monotonicMillis, long epochOriginMillis,
                                       long monotonicOriginMillis, Sleeper sleeper) {
        Objects.requireNonNull(monotonicMillis, "monotonicMillis");
        Clock epochMillis = () -> epochOriginMillis + (monotonicMillis.now() - monotonicOriginMillis);
        return new LoadSchedulerTiming(epochMillis, sleeper);
    }

    long now() {
        return clock.now();
    }

    void sleep(long millis) throws InterruptedException {
        sleeper.sleep(millis);
    }
}
