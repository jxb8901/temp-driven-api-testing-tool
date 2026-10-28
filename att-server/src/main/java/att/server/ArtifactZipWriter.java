package att.server;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** Bounded ZIP writer for already-approved bytes and, when selected, one-pass raw DEFLATE streams. */
final class ArtifactZipWriter implements AutoCloseable {
    static final int STORED = 0;
    static final int DEFLATED = 8;
    private static final int UTF8_FLAG = 0x0800;
    private static final int DOS_DATE_1980_01_01 = 0x0021;

    private final CountingOutputStream output;
    private final List<CentralEntry> entries = new ArrayList<>();
    private final long maxArchiveBytes;
    private boolean finished;

    ArtifactZipWriter(OutputStream output, long maxArchiveBytes) {
        this.output = new CountingOutputStream(output);
        this.maxArchiveBytes = maxArchiveBytes;
    }

    long bytesWritten() { return output.count; }

    void write(String name, byte[] source, int method, Path deflatedFile, long compressedSize) throws IOException {
        if (finished) throw new IllegalStateException("ZIP is already finished.");
        if (method != STORED && method != DEFLATED) throw new IllegalArgumentException("Unsupported ZIP method.");
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length < 1 || nameBytes.length > 8192) throw new IOException("ZIP entry name exceeds its bound.");
        long expandedSize = source.length;
        long actualCompressedSize = method == STORED ? expandedSize : compressedSize;
        if (actualCompressedSize < 0 || actualCompressedSize > 0xffffffffL || expandedSize > 0xffffffffL)
            throw new IOException("ZIP entry size exceeds the non-ZIP64 archive limit.");
        if (method == STORED && (deflatedFile != null || compressedSize != expandedSize))
            throw new IllegalArgumentException("Stored ZIP entry metadata is inconsistent.");
        if (method == DEFLATED && (deflatedFile == null || !Files.isRegularFile(deflatedFile)
                || Files.size(deflatedFile) != actualCompressedSize))
            throw new IOException("Deflated ZIP staging entry is unavailable.");

        CRC32 crc = new CRC32();
        crc.update(source);
        long localOffset = output.count;
        long localLength = 30L + nameBytes.length + actualCompressedSize;
        ensureRoom(localLength);
        writeInt(0x04034b50L); writeShort(20); writeShort(UTF8_FLAG); writeShort(method);
        writeShort(0); writeShort(DOS_DATE_1980_01_01); writeInt(crc.getValue());
        writeInt(actualCompressedSize); writeInt(expandedSize); writeShort(nameBytes.length); writeShort(0);
        output.write(nameBytes);
        if (method == STORED) output.write(source);
        else try (InputStream input = Files.newInputStream(deflatedFile)) { input.transferTo(output); }
        if (output.count != localOffset + localLength) throw new IOException("ZIP entry length changed while writing.");
        entries.add(new CentralEntry(nameBytes, method, crc.getValue(), actualCompressedSize, expandedSize, localOffset));
    }

    void finish() throws IOException {
        if (finished) return;
        if (entries.isEmpty() || entries.size() > ArtifactArchive.MAX_ENTRIES + 1)
            throw new IOException("ZIP entry count exceeds its bound.");
        long centralOffset = output.count;
        for (CentralEntry entry : entries) {
            long centralLength = 46L + entry.name.length;
            ensureRoom(centralLength + 22L);
            writeInt(0x02014b50L); writeShort(20); writeShort(20); writeShort(UTF8_FLAG); writeShort(entry.method);
            writeShort(0); writeShort(DOS_DATE_1980_01_01); writeInt(entry.crc);
            writeInt(entry.compressedSize); writeInt(entry.expandedSize); writeShort(entry.name.length);
            writeShort(0); writeShort(0); writeShort(0); writeShort(0); writeInt(0); writeInt(entry.localOffset);
            output.write(entry.name);
        }
        long centralSize = output.count - centralOffset;
        if (entries.size() > 0xffff || centralOffset > 0xffffffffL || centralSize > 0xffffffffL)
            throw new IOException("ZIP central directory exceeds the non-ZIP64 archive limit.");
        ensureRoom(22L);
        writeInt(0x06054b50L); writeShort(0); writeShort(0); writeShort(entries.size()); writeShort(entries.size());
        writeInt(centralSize); writeInt(centralOffset); writeShort(0);
        output.flush();
        finished = true;
    }

    @Override public void close() throws IOException { output.close(); }

    private void ensureRoom(long additional) {
        if (additional < 0 || output.count > maxArchiveBytes - additional)
            throw new ArtifactArchive.LimitFailure("Artifact archive exceeds limits", "artifact.archive.zipBytes",
                    maxArchiveBytes, output.count > Long.MAX_VALUE - additional ? Long.MAX_VALUE : output.count + additional);
    }

    private void writeShort(long value) throws IOException {
        output.write((int) value & 255); output.write((int) (value >>> 8) & 255);
    }

    private void writeInt(long value) throws IOException {
        writeShort(value); writeShort(value >>> 16);
    }

    private record CentralEntry(byte[] name, int method, long crc, long compressedSize,
                                long expandedSize, long localOffset) { }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length); count += length;
        }
    }
}
