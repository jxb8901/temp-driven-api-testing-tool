package att.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded, in-memory ZIP reader for staging Package files through the normal Change Set path. */
final class PackageSourceArchiveImport {
    static final int MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;
    static final int MAX_ENTRIES = ChangeSetPublisher.MAX_OPERATIONS;
    static final int MAX_EXPANDED_BYTES = ChangeSetPublisher.MAX_CHANGE_BYTES;
    static final int MAX_EXPANSION_RATIO = 200;

    static final class InvalidArchive extends IllegalArgumentException {
        InvalidArchive(String message) { super(message); }
        InvalidArchive(String message, Throwable cause) { super(message, cause); }
    }

    private static final class CentralEntry {
        final long compressedSize;
        final long uncompressedSize;
        final int method;
        CentralEntry(long compressedSize, long uncompressedSize, int method) {
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.method = method;
        }
    }

    private PackageSourceArchiveImport() { }

    static Map<String, byte[]> parse(byte[] archive) {
        if (archive == null || archive.length == 0) throw new InvalidArchive("A non-empty Package ZIP is required");
        if (archive.length > MAX_ARCHIVE_BYTES) throw new InvalidArchive("Package ZIP exceeds its compressed size limit");
        Map<String, CentralEntry> centralEntries = readCentralDirectory(archive);
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        Set<String> occupied = new HashSet<>();
        Set<String> directories = new HashSet<>();
        Map<String, String> pathCase = new HashMap<>();
        long expanded = 0L;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new InvalidArchive("Package ZIP contains too many entries");
                String rawPath = entry.getName();
                boolean directory = entry.isDirectory();
                String path = normalizePath(rawPath, directory);
                registerPathCase(path, pathCase);
                CentralEntry central = centralEntries.get(rawPath);
                if (central == null) throw new InvalidArchive("Package ZIP central directory is inconsistent");
                if (directory) {
                    if (central.uncompressedSize != 0L) throw new InvalidArchive("Package ZIP directory entries must be empty");
                    String key = path.toLowerCase(Locale.ROOT);
                    if (occupied.contains(key) || !directories.add(key))
                        throw new InvalidArchive("Package ZIP contains duplicate or conflicting paths");
                    drain(zip, 0L);
                    zip.closeEntry();
                    requireCentralMatch(entry, central);
                    continue;
                }
                if (!occupied.add(path.toLowerCase(Locale.ROOT)))
                    throw new InvalidArchive("Package ZIP contains duplicate or case-colliding paths");
                if (directories.contains(path.toLowerCase(Locale.ROOT)))
                    throw new InvalidArchive("Package ZIP contains conflicting file and directory paths");
                if (central.uncompressedSize < 0L || central.uncompressedSize > ChangeSetPublisher.MAX_FILE_BYTES)
                    throw new InvalidArchive("A Package ZIP file exceeds its size limit");
                byte[] contents = readEntry(zip, central.uncompressedSize, MAX_EXPANDED_BYTES - expanded);
                zip.closeEntry();
                requireCentralMatch(entry, central);
                expanded += contents.length;
                if (expanded > MAX_EXPANDED_BYTES)
                    throw new InvalidArchive("Package ZIP exceeds its expanded size limit");
                if ((long) contents.length > (long) archive.length * MAX_EXPANSION_RATIO)
                    throw new InvalidArchive("Package ZIP expansion ratio exceeds its limit");
                files.put(path, contents);
            }
        } catch (InvalidArchive invalid) {
            throw invalid;
        } catch (IOException | RuntimeException invalid) {
            throw new InvalidArchive("Package ZIP is malformed or unsupported", invalid);
        }
        if (files.isEmpty()) throw new InvalidArchive("Package ZIP contains no source files");
        long centralFiles = centralEntries.keySet().stream().filter(name -> !name.endsWith("/")).count();
        if (files.size() != centralFiles) throw new InvalidArchive("Package ZIP contains unsupported entries");
        List<String> paths = new ArrayList<>(files.keySet());
        for (int left = 0; left < paths.size(); left++) for (int right = left + 1; right < paths.size(); right++) {
            String a = paths.get(left).toLowerCase(Locale.ROOT), b = paths.get(right).toLowerCase(Locale.ROOT);
            if (a.startsWith(b + "/") || b.startsWith(a + "/"))
                throw new InvalidArchive("Package ZIP contains conflicting file paths");
        }
        for (String file : files.keySet()) {
            String fileKey = file.toLowerCase(Locale.ROOT);
            for (String directory : directories) {
                if (directory.startsWith(fileKey + "/"))
                    throw new InvalidArchive("Package ZIP contains conflicting file and directory paths");
            }
        }
        return files;
    }

    private static void requireCentralMatch(ZipEntry entry, CentralEntry central) {
        if (entry.getMethod() != central.method || entry.getSize() != central.uncompressedSize
                || entry.getCompressedSize() != central.compressedSize)
            throw new InvalidArchive("Package ZIP local entry does not match its central directory");
    }

    private static byte[] readEntry(ZipInputStream zip, long declaredSize, long remaining) throws IOException {
        if (declaredSize > remaining) throw new InvalidArchive("Package ZIP exceeds its expanded size limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(declaredSize, 8192L));
        byte[] buffer = new byte[8192];
        long count = 0L;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            count += read;
            if (count > declaredSize || count > remaining)
                throw new InvalidArchive("Package ZIP entry size does not match its central directory");
            output.write(buffer, 0, read);
        }
        if (count != declaredSize) throw new InvalidArchive("Package ZIP entry size does not match its central directory");
        return output.toByteArray();
    }

    private static void drain(ZipInputStream zip, long maximumBytes) throws IOException {
        byte[] buffer = new byte[256];
        long count = 0L;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            count += read;
            if (count > maximumBytes) throw new InvalidArchive("Package ZIP directory entry is not empty");
        }
    }

    private static String normalizePath(String rawPath, boolean directory) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.length() > 512 || rawPath.indexOf('\\') >= 0
                || rawPath.startsWith("/") || rawPath.indexOf('\0') >= 0 || rawPath.matches("^[A-Za-z]:.*"))
            throw new InvalidArchive("Package ZIP contains an unsafe path");
        String path = directory && rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
        if (path.isEmpty()) throw new InvalidArchive("Package ZIP contains an unsafe path");
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf(':') >= 0)
                throw new InvalidArchive("Package ZIP contains an unsafe path");
            for (int index = 0; index < part.length(); index++) {
                char value = part.charAt(index);
                if (Character.isISOControl(value) || value == 0x7f)
                    throw new InvalidArchive("Package ZIP contains an unsafe path");
            }
        }
        return path;
    }

    private static void registerPathCase(String path, Map<String, String> seen) {
        String[] parts = path.split("/");
        StringBuilder prefix = new StringBuilder();
        for (String part : parts) {
            if (prefix.length() > 0) prefix.append('/');
            prefix.append(part);
            String value = prefix.toString();
            String prior = seen.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
            if (prior != null && !prior.equals(value))
                throw new InvalidArchive("Package ZIP contains case-colliding paths");
        }
    }

    /** Parse the bounded central directory to reject symlink entries and unsupported ZIP features. */
    private static Map<String, CentralEntry> readCentralDirectory(byte[] bytes) {
        int eocd = findEndOfCentralDirectory(bytes);
        if (eocd < 0) throw new InvalidArchive("Package ZIP central directory is unavailable");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int disk = u16(input, eocd + 4), centralDisk = u16(input, eocd + 6);
        int diskEntries = u16(input, eocd + 8), totalEntries = u16(input, eocd + 10);
        long centralSize = u32(input, eocd + 12), centralOffset = u32(input, eocd + 16);
        int commentLength = u16(input, eocd + 20);
        if (disk != 0 || centralDisk != 0 || diskEntries != totalEntries || totalEntries < 1
                || totalEntries > MAX_ENTRIES || centralSize == 0xffffffffL || centralOffset == 0xffffffffL
                || eocd + 22L + commentLength != bytes.length || centralOffset + centralSize != eocd)
            throw new InvalidArchive("Package ZIP uses unsupported or inconsistent central directory metadata");
        Map<String, CentralEntry> entries = new HashMap<>();
        int cursor = (int) centralOffset;
        int centralEnd = (int) (centralOffset + centralSize);
        for (int index = 0; index < totalEntries; index++) {
            if (cursor + 46 > centralEnd || u32(input, cursor) != 0x02014b50L)
                throw new InvalidArchive("Package ZIP central directory is malformed");
            int host = (u16(input, cursor + 4) >>> 8) & 0xff;
            int flags = u16(input, cursor + 8);
            int method = u16(input, cursor + 10);
            long compressed = u32(input, cursor + 20), uncompressed = u32(input, cursor + 24);
            int nameLength = u16(input, cursor + 28), extraLength = u16(input, cursor + 30), comment = u16(input, cursor + 32);
            long externalAttributes = u32(input, cursor + 38);
            long localOffset = u32(input, cursor + 42);
            long end = cursor + 46L + nameLength + extraLength + comment;
            int allowedFlags = 0x0808 | (method == 8 ? 0x0006 : 0);
            if (end > centralEnd || (flags & ~allowedFlags) != 0 || (method != 0 && method != 8)
                    || compressed == 0xffffffffL || uncompressed == 0xffffffffL || localOffset == 0xffffffffL)
                throw new InvalidArchive("Package ZIP uses unsupported central directory features");
            if (uncompressed > 0L && (compressed == 0L
                    || uncompressed > compressed * MAX_EXPANSION_RATIO))
                throw new InvalidArchive("Package ZIP expansion ratio exceeds its limit");
            byte[] nameBytes = new byte[nameLength];
            ByteBuffer nameView = input.duplicate();
            nameView.position(cursor + 46); nameView.get(nameBytes);
            String name;
            try {
                name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString();
            } catch (CharacterCodingException invalid) {
                throw new InvalidArchive("Package ZIP paths must be valid UTF-8", invalid);
            }
            int unixMode = (int) ((externalAttributes >>> 16) & 0xffff);
            int unixType = unixMode & 0xf000;
            if (host == 3 && unixType != 0) {
                if (unixType != 0x8000 && unixType != 0x4000)
                    throw new InvalidArchive("Package ZIP special file entries are not allowed");
                if ((unixType == 0x4000) != name.endsWith("/"))
                    throw new InvalidArchive("Package ZIP entry type does not match its path");
            }
            if (entries.put(name, new CentralEntry(compressed, uncompressed, method)) != null)
                throw new InvalidArchive("Package ZIP contains duplicate entries");
            cursor = (int) end;
        }
        if (cursor != centralEnd) throw new InvalidArchive("Package ZIP central directory is malformed");
        return entries;
    }

    private static int findEndOfCentralDirectory(byte[] bytes) {
        int first = Math.max(0, bytes.length - 22 - 65535);
        for (int index = bytes.length - 22; index >= first; index--) {
            if ((bytes[index] & 0xff) == 0x50 && (bytes[index + 1] & 0xff) == 0x4b
                    && (bytes[index + 2] & 0xff) == 0x05 && (bytes[index + 3] & 0xff) == 0x06)
                return index;
        }
        return -1;
    }

    private static int u16(ByteBuffer input, int offset) {
        if (offset < 0 || offset + 2 > input.limit()) throw new InvalidArchive("Package ZIP central directory is truncated");
        return input.getShort(offset) & 0xffff;
    }

    private static long u32(ByteBuffer input, int offset) {
        if (offset < 0 || offset + 4 > input.limit()) throw new InvalidArchive("Package ZIP central directory is truncated");
        return input.getInt(offset) & 0xffffffffL;
    }
}
