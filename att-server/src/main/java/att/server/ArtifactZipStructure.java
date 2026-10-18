package att.server;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.TreeSet;

/** Bounded export profile for PKWARE APPNOTE records, including metadata ignored by ZipFile. */
final class ArtifactZipStructure {
    private record Span(long start, long end) {}
    private ArtifactZipStructure() {}

    static void verify(Path archive) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
            long length = file.length();
            if (length < 22 || length > ArtifactArchive.MAX_ARCHIVE_BYTES) throw unsafe();
            // Export archives have no comments, multipart records, ZIP64, or executable prefix.
            byte[] end = read(file, length - 22, 22);
            long count = u16(end, 10), offset = u32(end, 16), size = u32(end, 12);
            if (u32(end, 0) != 0x06054b50L || u16(end, 4) != 0 || u16(end, 6) != 0
                    || u16(end, 8) != count || count < 1 || count > ArtifactArchive.MAX_ENTRIES + 1
                    || u16(end, 20) != 0 || offset + size != length - 22) throw unsafe();
            long cursor = offset;
            var names = new TreeSet<String>();
            var spans = new ArrayList<Span>();
            for (int i = 0; i < count; i++) {
                if (cursor + 46 > length - 22) throw unsafe();
                byte[] central = read(file, cursor, 46);
                int flags = u16(central, 8), method = u16(central, 10), nameLength = u16(central, 28);
                int extraLength = u16(central, 30), commentLength = u16(central, 32);
                long compressed = u32(central, 20), expanded = u32(central, 24), crc = u32(central, 16);
                long localOffset = u32(central, 42), attributes = u32(central, 38);
                int kind = (int) (attributes >>> 16) & 0170000;
                if (u32(central, 0) != 0x02014b50L || nameLength < 1 || nameLength > 8192
                        || commentLength != 0 || extraLength > 64 || u16(central, 34) != 0
                        || (flags & ~0x080e) != 0 || (method != 0 && method != 8)
                        || (method == 0 && ((flags & 6) != 0 || compressed != expanded))
                        || (attributes & 0xff58) != 0 || (kind != 0 && kind != 0100000)
                        || compressed == 0xffffffffL || expanded == 0xffffffffL || localOffset >= offset
                        || cursor + 46L + nameLength + extraLength > length - 22) throw unsafe();
                byte[] nameBytes = read(file, cursor + 46, nameLength);
                if ((flags & 0x800) == 0) for (byte value : nameBytes) if (value < 0) throw unsafe();
                String name;
                try {
                    name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString();
                } catch (java.nio.charset.CharacterCodingException invalid) { throw unsafe(); }
                if (!ArtifactArchive.safePath(name)) throw unsafe();
                String key = name.toLowerCase(Locale.ROOT);
                if (!names.add(key)) throw unsafe();
                extra(file, cursor + 46 + nameLength, extraLength);
                byte[] local = read(file, localOffset, 30);
                int localExtra = u16(local, 28);
                long dataEnd = localOffset + 30L + nameLength + localExtra + compressed;
                if (u32(local, 0) != 0x04034b50L || u16(local, 6) != flags || u16(local, 8) != method
                        || u16(local, 26) != nameLength || localExtra > 64 || dataEnd > offset
                        || !Arrays.equals(nameBytes, read(file, localOffset + 30, nameLength))) throw unsafe();
                extra(file, localOffset + 30 + nameLength, localExtra);
                if ((flags & 8) == 0) {
                    if (u32(local, 14) != crc || u32(local, 18) != compressed || u32(local, 22) != expanded) throw unsafe();
                } else {
                    if ((u32(local, 14) != 0 && u32(local, 14) != crc)
                            || (u32(local, 18) != 0 && u32(local, 18) != compressed)
                            || (u32(local, 22) != 0 && u32(local, 22) != expanded)) throw unsafe();
                    byte[] descriptor = read(file, dataEnd, 12);
                    if (u32(descriptor, 0) == 0x08074b50L) {
                        dataEnd += 4;
                        descriptor = read(file, dataEnd, 12);
                    }
                    if (u32(descriptor, 0) != crc || u32(descriptor, 4) != compressed || u32(descriptor, 8) != expanded) throw unsafe();
                    dataEnd += 12;
                    if (dataEnd > offset) throw unsafe();
                }
                spans.add(new Span(localOffset, dataEnd));
                cursor += 46L + nameLength + extraLength;
            }
            if (cursor != length - 22) throw unsafe();
            for (String name : names) {
                String descendant = names.ceiling(name + "/");
                if (descendant != null && descendant.startsWith(name + "/")) throw unsafe();
            }
            spans.sort(Comparator.comparingLong(Span::start));
            long next = 0;
            for (Span span : spans) {
                if (span.start != next) throw unsafe();
                next = span.end;
            }
            if (next != offset) throw unsafe();
        }
    }

    private static void extra(RandomAccessFile file, long offset, int length) throws IOException {
        byte[] bytes = read(file, offset, length);
        for (int cursor = 0; cursor < length;) {
            if (cursor + 4 > length) throw unsafe();
            int tag = u16(bytes, cursor), size = u16(bytes, cursor + 2);
            // Timestamp metadata cannot rename entries or introduce links during extraction.
            if (tag != 0x5455 || size < 1 || cursor + 4 + size > length) throw unsafe();
            int flags = bytes[cursor + 4] & 255;
            int timestamps = Integer.bitCount(flags);
            if ((flags & ~7) != 0 || (size != 1 + 4 * timestamps && size != 1 + 4 * (flags & 1))) throw unsafe();
            cursor += 4 + size;
        }
    }

    private static byte[] read(RandomAccessFile file, long offset, int length) throws IOException {
        if (offset < 0 || offset + length > file.length()) throw unsafe();
        byte[] bytes = new byte[length];
        file.seek(offset);
        file.readFully(bytes);
        return bytes;
    }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8; }
    private static long u32(byte[] bytes, int offset) { return Integer.toUnsignedLong(u16(bytes, offset) | u16(bytes, offset + 2) << 16); }
    private static IllegalArgumentException unsafe() { return new IllegalArgumentException("Unsafe export ZIP structure"); }
}
