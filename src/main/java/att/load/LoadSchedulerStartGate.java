package att.load;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One-shot barrier that gives coordinated workload schedulers one committed T0. */
final class LoadSchedulerStartGate {
    private final CountDownLatch ready;
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile long startedAt;

    LoadSchedulerStartGate(int parties) {
        if (parties < 1) throw new IllegalArgumentException("Start gate requires at least one workload");
        ready = new CountDownLatch(parties);
    }

    long awaitStart() throws InterruptedException {
        ready.countDown();
        released.await();
        return startedAt;
    }

    void awaitReady() throws InterruptedException { ready.await(); }
    boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException { return ready.await(timeout, unit); }

    void release(long value) {
        if (value <= 0L) throw new IllegalArgumentException("Coordinated load start must be positive");
        startedAt = value;
        released.countDown();
    }
}
