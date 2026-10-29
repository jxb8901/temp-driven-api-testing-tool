package att.server;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Fixed-cardinality API counters with a bounded recent-latency ring. */
final class MonitoringRequestMetrics {
    private static final int WINDOW = 256;
    private final LongAdder requests = new LongAdder();
    private final LongAdder successfulRequests = new LongAdder();
    private final LongAdder redirectRequests = new LongAdder();
    private final LongAdder clientErrors = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLongArray durations = new AtomicLongArray(WINDOW);

    synchronized void record(int status, long durationNanos) {
        requests.increment();
        if (status >= 200 && status < 300) successfulRequests.increment();
        else if (status >= 300 && status < 400) redirectRequests.increment();
        else if (status >= 400 && status < 500) clientErrors.increment();
        else if (status >= 500) serverErrors.increment();
        durations.set((int) Math.floorMod(sequence.getAndIncrement(), WINDOW), Math.max(0L, durationNanos / 1_000_000L));
    }

    long requests() { return requests.sum(); }
    long successfulRequests() { return successfulRequests.sum(); }
    long redirectRequests() { return redirectRequests.sum(); }
    long clientErrors() { return clientErrors.sum(); }
    long serverErrors() { return serverErrors.sum(); }
    synchronized Long percentile(double fraction) {
        long end = sequence.get();
        int count = (int) Math.min(end, WINDOW);
        if (count == 0) return null;
        long[] values = new long[count];
        long start = Math.max(0, end - count);
        for (int i = 0; i < count; i++) values[i] = durations.get((int) Math.floorMod(start + i, WINDOW));
        Arrays.sort(values);
        return values[Math.max(0, (int) Math.ceil(fraction * count) - 1)];
    }
}
