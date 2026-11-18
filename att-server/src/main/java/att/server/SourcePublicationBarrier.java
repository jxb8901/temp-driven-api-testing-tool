package att.server;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Coordinates source readers with revision publication inside one Server runtime. */
final class SourcePublicationBarrier {
    // Non-fair mode lets an inspection Worker join the readers held by an executing Job
    // while a publisher waits for the full Job revision to be released.
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(false);

    Lock readLock() { return lock.readLock(); }
    Lock writeLock() { return lock.writeLock(); }
}
