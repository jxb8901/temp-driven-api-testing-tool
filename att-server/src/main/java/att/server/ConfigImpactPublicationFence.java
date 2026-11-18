package att.server;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import java.util.Map;
import java.nio.file.Path;

/** Holds the Package Registry and persisted Schedule inventory stable through a Config pointer switch. */
final class ConfigImpactPublicationFence {
    private ConfigImpactPublicationFence() { }

    static <T> T runInventory(Lock registryReadLock, Object scheduleStoreMonitor, Callable<T> operation) throws Exception {
        registryReadLock.lock();
        try {
            synchronized (scheduleStoreMonitor) {
                return operation.call();
            }
        } finally {
            registryReadLock.unlock();
        }
    }

    static <T> T runPublication(Lock registryReadLock, Supplier<Map<String, Path>> packageRoots,
                                SourceFileTransaction transaction, Object scheduleStoreMonitor,
                                Callable<T> operation) throws Exception {
        registryReadLock.lock();
        try {
            Map<String, Path> roots = packageRoots.get();
            // Package writers acquire their source locks before recording their audit in JobStore.
            // Match that order before taking the Schedule store monitor to avoid a lock cycle.
            return transaction.withPackageWriterLocks(roots, () -> {
                synchronized (scheduleStoreMonitor) {
                    return operation.call();
                }
            });
        } finally {
            registryReadLock.unlock();
        }
    }
}
