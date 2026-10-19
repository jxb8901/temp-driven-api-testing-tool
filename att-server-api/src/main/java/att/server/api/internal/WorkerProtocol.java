package att.server.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Byte framing shared by execution Workers and their Server reader (Java 8). */
public final class WorkerProtocol {
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final Pattern TYPE = Pattern.compile("^\\s*\\{\\s*\"type\"\\s*:\\s*\"(STATUS|LOG|PROGRESS|DIAGNOSTIC|RESULT)\"");
    private WorkerProtocol() { }

    public static Frame encode(ObjectMapper mapper, Object event, int limit) throws IOException {
        Capture capture = new Capture(limit);
        mapper.writeValue(capture, event);
        return capture.frame(false);
    }

    /** Drain the complete frame, retaining only a bounded prefix; LF/CRLF is not payload. */
    public static Frame read(InputStream input, int limit) throws IOException {
        Capture capture = new Capture(limit);
        int value, previous = -1;
        while ((value = input.read()) != -1 && value != '\n') {
            capture.write(value);
            previous = value;
        }
        if (value == -1 && capture.count == 0) return null;
        return capture.frame(value == '\n' && previous == '\r');
    }

    public static final class Frame {
        private final byte[] prefix;
        public final long sizeBytes;
        public final int limitBytes;
        private Frame(byte[] prefix, long sizeBytes, int limitBytes) {
            this.prefix = prefix; this.sizeBytes = sizeBytes; this.limitBytes = limitBytes;
        }
        public boolean oversized() { return sizeBytes > limitBytes; }
        public String text() {
            if (oversized()) throw new IllegalStateException("Worker frame exceeds its byte budget");
            return new String(prefix, 0, (int) sizeBytes, StandardCharsets.UTF_8);
        }
        public void writeTo(OutputStream output) throws IOException {
            if (oversized()) throw new IllegalStateException("Worker frame exceeds its byte budget");
            output.write(prefix, 0, (int) sizeBytes);
        }
        public String eventType() {
            Matcher match = TYPE.matcher(new String(prefix, 0, Math.min(prefix.length, 128), StandardCharsets.UTF_8));
            return match.find() ? match.group(1) : "UNKNOWN";
        }
    }

    private static final class Capture extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        private long count;
        Capture(int limit) {
            if (limit < 1 || limit == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid frame budget");
            this.limit = limit;
        }
        @Override public void write(int value) {
            if (count < limit + 1L) prefix.write(value);
            count++;
        }
        @Override public void write(byte[] value, int offset, int length) {
            int retained = (int) Math.min(length, Math.max(0L, limit + 1L - count));
            prefix.write(value, offset, retained);
            count += length;
        }
        Frame frame(boolean trailingCr) {
            return new Frame(prefix.toByteArray(), count - (trailingCr ? 1 : 0), limit);
        }
    }
}
