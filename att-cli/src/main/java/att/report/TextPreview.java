/* Author: Jeffrey + ChatGPT */
package att.report;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a bounded UTF-8 head/tail preview without loading an entire evidence file. */
final class TextPreview {
    private TextPreview() {}
    static Preview read(Path file, int limit) throws Exception {
        long size = Files.size(file);
        if (limit <= 0) return new Preview("", size > 0);
        if (size <= limit) return new Preview(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), false);
        int headSize = Math.max(1, limit / 2), tailSize = Math.max(0, limit - headSize);
        byte[] head = new byte[headSize], tail = new byte[tailSize];
        int headRead = 0;
        try (InputStream input = Files.newInputStream(file)) {
            while (headRead < head.length) { int count = input.read(head, headRead, head.length - headRead); if (count < 0) break; headRead += count; }
        }
        int tailRead = 0;
        if (tail.length > 0) try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(Math.max(0L, size - tail.length));
            ByteBuffer buffer = ByteBuffer.wrap(tail);
            while (buffer.hasRemaining()) { int count = channel.read(buffer); if (count < 0) break; tailRead += count; }
        }
        String marker = "\n... ATT report preview truncated; totalBytes=" + size + " ...\n";
        return new Preview(new String(head, 0, headRead, StandardCharsets.UTF_8) + marker + new String(tail, 0, tailRead, StandardCharsets.UTF_8), true);
    }
    static final class Preview { final String text; final boolean truncated; Preview(String text, boolean truncated) { this.text = text; this.truncated = truncated; } }
}
