package att.core;

import java.io.Closeable;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-process policy guard for commands sharing one ATT output root. */
final class RunConcurrencyGuard implements Closeable {
    private final FileChannel channel;
    private final FileLock lock;
    private RunConcurrencyGuard(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    static RunConcurrencyGuard acquire(Path outputRoot, String mode, Runnable queued) throws Exception {
        if ("parallel".equals(mode)) return new RunConcurrencyGuard(null, null);
        Files.createDirectories(outputRoot);
        FileChannel channel = FileChannel.open(outputRoot.resolve(".att-run.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { }
        if (lock == null && "reject".equals(mode)) {
            channel.close();
            throw new IllegalArgumentException("Another ATT run is active for output root " + outputRoot + "; use --queue to wait or --parallel to run concurrently");
        }
        if (lock == null) {
            queued.run();
            lock = channel.lock();
        }
        return new RunConcurrencyGuard(channel, lock);
    }

    @Override public void close() throws java.io.IOException {
        if (lock != null && lock.isValid()) lock.release();
        if (channel != null) channel.close();
    }
}
