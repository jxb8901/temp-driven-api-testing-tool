package att.load;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
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
    private final boolean realtime;

    LoadSchedulerTiming(Clock clock, Sleeper sleeper) {
        this(clock, sleeper, false);
    }

    private LoadSchedulerTiming(Clock clock, Sleeper sleeper, boolean realtime) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.realtime = realtime;
    }

    static LoadSchedulerTiming system() {
        final long monotonicOriginNanos = System.nanoTime();
        final long epochOriginMillis = System.currentTimeMillis();
        Clock elapsedMillis = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - monotonicOriginNanos);
        return new LoadSchedulerTiming(() -> epochOriginMillis + elapsedMillis.now(),
                Thread::sleep, true);
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

    <T> T await(BlockingQueue<T> completed, long millis) throws InterruptedException {
        if (completed == null) throw new IllegalArgumentException("Completion queue is required");
        if (realtime) return completed.poll(Math.max(0L, millis), TimeUnit.MILLISECONDS);
        T ready = completed.poll(Math.max(0L, millis), TimeUnit.MILLISECONDS);
        if (ready != null) return ready;
        sleep(Math.max(0L, millis));
        return completed.poll();
    }
}
