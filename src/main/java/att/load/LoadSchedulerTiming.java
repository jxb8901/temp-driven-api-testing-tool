package att.load;

import java.util.Objects;

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
        return new LoadSchedulerTiming(LoadSchedulerSupport::now, LoadSchedulerSupport::sleep);
    }

    long now() {
        return clock.now();
    }

    void sleep(long millis) throws InterruptedException {
        sleeper.sleep(millis);
    }
}
