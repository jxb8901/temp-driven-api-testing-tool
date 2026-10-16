package att.server;

import java.io.IOException;
import java.io.Reader;

/** Serial, bounded JSON-lines framing without a synchronized Reader call per character. */
final class InspectionProtocolReader implements AutoCloseable {
    private final Reader source;
    private final char[] buffer = new char[8192];
    private int offset, length;

    InspectionProtocolReader(Reader source) { this.source = source; }

    String readLine(int limit) throws IOException {
        if (length < 0) return null;
        StringBuilder line = new StringBuilder(Math.min(limit, buffer.length));
        boolean oversized = false;
        for (;;) {
            if (offset == length) {
                length = source.read(buffer); offset = 0;
                if (length < 0) return line.isEmpty() && !oversized ? null : oversized ? "\u0000OVERSIZED" : line.toString();
            }
            int start = offset;
            while (offset < length && buffer[offset] != '\n' && buffer[offset] != '\r') offset++;
            int count = offset - start;
            int accepted = Math.min(count, limit - line.length());
            line.append(buffer, start, accepted);
            if (accepted < count) oversized = true;
            if (offset < length && buffer[offset++] == '\n') return oversized ? "\u0000OVERSIZED" : line.toString();
        }
    }

    @Override public void close() throws IOException { source.close(); }
}
