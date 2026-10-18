package att.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Builds the sole public representation of a Job's output. Raw Worker output is never served. */
final class ArtifactArchive {
    static final String MANIFEST = "export-manifest-v1.json";
    static final int MAX_ENTRIES = 10_000;
    static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final Set<String> SAFE_EXTENSIONS = Set.of("txt", "log", "json", "yaml", "yml", "xml", "html", "htm", "css", "csv", "md");
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface interface Publisher { byte[] publish(Path source) throws Exception; }
    record Entry(String path, String mediaType, long size, String sha256, String classification, String decision, boolean report, boolean preview) {}

    private ArtifactArchive() {}

    static Path build(Path output, Path archive, Publisher publisher) throws Exception {
        if (Files.isSymbolicLink(output)) throw new IllegalArgumentException("Job output root must not be a symbolic link");
        Path requestedRoot = output.toAbsolutePath().normalize();
        Path root = output.toRealPath();
        if (!root.equals(requestedRoot)) throw new IllegalArgumentException("Job output root resolved outside its private directory");
        Path destination = archive.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempFile(destination.getParent(), ".artifact-", ".zip.tmp");
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long total = 0;
        int omitted = 0;
        boolean published = false;
        try {
            List<Path> candidates;
            try (var paths = Files.walk(root)) { candidates = paths.sorted(Comparator.comparing(p -> root.relativize(p).toString())).toList(); }
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(staging))) {
                for (Path candidate : candidates) {
                    if (candidate.equals(root) || Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                    String relative = root.relativize(candidate).toString().replace('\\', '/');
                    String lower = relative.toLowerCase(Locale.ROOT);
                    if (!safePath(relative) || lower.equals(MANIFEST) || !SAFE_EXTENSIONS.contains(extension(relative)) || names.contains(lower)) { omitted++; continue; }
                    if (entries.size() >= MAX_ENTRIES) { omitted++; continue; }
                    long sourceSize = Files.size(candidate);
                    if (sourceSize > MAX_ENTRY_BYTES || total + sourceSize > MAX_TOTAL_BYTES) { omitted++; continue; }
                    byte[] bytes;
                    try { bytes = publisher.publish(candidate); }
                    catch (IllegalArgumentException rejected) { omitted++; continue; }
                    if (bytes.length > MAX_ENTRY_BYTES || total + bytes.length > MAX_TOTAL_BYTES) { omitted++; continue; }
                    names.add(lower); total += bytes.length;
                    Entry info = new Entry(relative, mediaType(relative), bytes.length, sha256(bytes), "textual-output", "sanitized-once", relative.toLowerCase(Locale.ROOT).endsWith(".html") || relative.toLowerCase(Locale.ROOT).endsWith(".htm"), true);
                    entries.add(info);
                    zip.putNextEntry(new ZipEntry(relative)); zip.write(bytes); zip.closeEntry();
                }
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("version", 1); manifest.put("policy", "att-export-safe-text-v1"); manifest.put("entries", entries);
                manifest.put("omissions", omitted==0?List.of():List.of(Map.of("count", omitted, "reason", "unsupported, unsafe, duplicate, binary, oversized, or unapproved output")));
                zip.putNextEntry(new ZipEntry(MANIFEST)); zip.write(JSON.writeValueAsBytes(manifest)); zip.closeEntry();
            }
            try (ZipFile check = new ZipFile(staging.toFile())) {
                for (Entry item : entries) {
                    ZipEntry zipped = check.getEntry(item.path());
                    if (zipped == null || zipped.getSize() != item.size()) throw new IllegalStateException("Published archive verification failed");
                    try (InputStream input = check.getInputStream(zipped)) {
                        byte[] actual = input.readNBytes((int) MAX_ENTRY_BYTES + 1);
                        if (actual.length != item.size() || !sha256(actual).equals(item.sha256())) throw new IllegalStateException("Published archive integrity check failed");
                    }
                }
            }
            try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING); }
            published = true;
            return destination;
        } finally { if (!published) Files.deleteIfExists(staging); }
    }

    static byte[] read(Path archive, String requested) throws Exception {
        if (!safePath(requested) || MANIFEST.equals(requested)) throw new IllegalArgumentException("Invalid archive entry path");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(requested);
            if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES) throw new java.io.FileNotFoundException();
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes((int) MAX_ENTRY_BYTES + 1);
                if (bytes.length > MAX_ENTRY_BYTES || bytes.length != entry.getSize()) throw new IllegalArgumentException("Archive entry exceeds limits or is corrupt");
                return bytes;
            }
        }
    }

    static boolean safePath(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('%') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) return false;
        for (String part : name.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    private static String extension(String path) { int dot = path.lastIndexOf('.'); return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private static String mediaType(String path) { return switch (extension(path)) { case "html", "htm" -> "text/html"; case "json" -> "application/json"; case "yaml", "yml" -> "application/yaml"; case "xml" -> "application/xml"; case "css" -> "text/css"; case "csv" -> "text/csv"; case "md" -> "text/markdown"; default -> "text/plain"; }; }
    static String sha256(byte[] bytes) throws Exception { byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%02x", b)); return out.toString(); }
}
