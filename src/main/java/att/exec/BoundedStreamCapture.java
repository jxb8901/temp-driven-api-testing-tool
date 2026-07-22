/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Retains a bounded head/tail preview while streaming a bounded artifact to disk. */
final class BoundedStreamCapture extends OutputStream {
    private final int headLimit;
    private final byte[] tail;
    private final long artifactLimit;
    private final Path artifact;
    private final ByteArrayOutputStream head;
    private final OutputStream artifactOutput;
    private long bytes;
    private long artifactBytes;
    private int tailCount;
    private int tailPosition;

    BoundedStreamCapture(int memoryLimit, long artifactLimit, Path artifact) throws IOException {
        this.headLimit = Math.max(1, memoryLimit / 2);
        this.tail = new byte[Math.max(0, memoryLimit - headLimit)];
        this.artifactLimit = artifactLimit;
        this.artifact = artifact;
        this.head = new ByteArrayOutputStream(headLimit);
        if (artifact == null) this.artifactOutput = null;
        else {
            Files.createDirectories(artifact.getParent());
            this.artifactOutput = Files.newOutputStream(artifact);
        }
    }

    @Override public synchronized void write(int value) throws IOException { byte[] one = new byte[]{(byte) value}; write(one, 0, 1); }

    @Override public synchronized void write(byte[] source, int offset, int length) throws IOException {
        if (length <= 0) return;
        int artifactWrite = (int) Math.min((long) length, Math.max(0L, artifactLimit - artifactBytes));
        if (artifactOutput != null && artifactWrite > 0) { artifactOutput.write(source, offset, artifactWrite); artifactBytes += artifactWrite; }
        for (int index = 0; index < length; index++) {
            byte value = source[offset + index];
            if (head.size() < headLimit) head.write(value);
            else if (tail.length > 0) {
                tail[tailPosition] = value;
                tailPosition = (tailPosition + 1) % tail.length;
                if (tailCount < tail.length) tailCount++;
            }
        }
        bytes += length;
    }

    @Override public synchronized void close() throws IOException { if (artifactOutput != null) artifactOutput.close(); }

    synchronized String preview() {
        ByteArrayOutputStream preview = new ByteArrayOutputStream(head.size() + tailCount + 96);
        byte[] prefix = head.toByteArray();
        preview.write(prefix, 0, prefix.length);
        if (memoryTruncated()) {
            byte[] marker = ("\n... ATT output preview truncated; totalBytes=" + bytes + " ...\n").getBytes(StandardCharsets.UTF_8);
            preview.write(marker, 0, marker.length);
        }
        int start = tailCount == tail.length ? tailPosition : 0;
        for (int index = 0; index < tailCount; index++) preview.write(tail[(start + index) % tail.length]);
        return new String(preview.toByteArray(), StandardCharsets.UTF_8);
    }

    synchronized long bytes() { return bytes; }
    synchronized boolean memoryTruncated() { return bytes > headLimit + tail.length; }
    synchronized boolean artifactTruncated() { return artifactOutput != null && bytes > artifactLimit; }
    Path artifact() { return artifact; }
}
