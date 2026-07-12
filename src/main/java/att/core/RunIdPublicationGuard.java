package att.core;

import java.io.Closeable;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** Serializes final Run ID allocation/publication for one requested ID. */
final class RunIdPublicationGuard implements Closeable {
    private final FileChannel channel;
    private final FileLock lock;
    private RunIdPublicationGuard(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    static RunIdPublicationGuard acquire(Path outputRoot, String requestedRunId) throws Exception {
        Path lockRoot = outputRoot.resolve(".att-locks");
        Files.createDirectories(lockRoot);
        FileChannel channel = FileChannel.open(lockRoot.resolve("publish-" + requestedRunId + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return new RunIdPublicationGuard(channel, channel.lock());
    }
    @Override public void close() throws java.io.IOException { if (lock.isValid()) lock.release(); channel.close(); }
}
