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
    static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    static final int MAX_CANDIDATES = 50_000;
    static final long MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES + 4L * 1024 * 1024;
    private static final Set<String> SAFE_EXTENSIONS = Set.of("txt", "log", "json", "yaml", "yml", "xml", "html", "htm", "css", "csv", "md");
    private static final String OMISSION_REASON = "unsupported, unsafe, duplicate, binary, oversized, or unapproved output";
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                    .maxNestingDepth(16).maxStringLength(8192).maxNumberLength(20).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @FunctionalInterface interface Publisher { byte[] publish(Path source) throws Exception; }
    record Entry(String path, String mediaType, long size, String sha256, String classification, String decision, boolean report, boolean preview) {}

    private ArtifactArchive() {}

    static Path build(Path output, Path archive, Publisher publisher) throws Exception {
        ArtifactMetricsRecorder metrics = ArtifactMetricsRecorder.CURRENT.get();
        if (metrics != null) {
            metrics.phase("scan_classification_redaction");
            metrics.value.sourceFiles=0L;metrics.value.sourceBytes=0L;
            metrics.value.includedEntries=0L;metrics.value.omittedEntries=0L;metrics.value.includedBytes=0L;
        }
        if (Files.isSymbolicLink(output)) throw new IllegalArgumentException("Job output root must not be a symbolic link");
        Path requestedRoot=output.toAbsolutePath().normalize(),root=output.toRealPath();
        if (!root.equals(requestedRoot.getParent().toRealPath().resolve(requestedRoot.getFileName()))) throw new IllegalArgumentException("Job output escaped its private directory");
        Path destination=archive.toAbsolutePath().normalize();Files.createDirectories(destination.getParent());
        Path staging=Files.createTempFile(destination.getParent(),".artifact-",".zip.tmp");
        Path exports=Files.createTempDirectory(destination.getParent(),".artifact-export-");
        boolean published=false;
        try {
            List<Path> candidates=inventory(root);
            Set<String> names=new HashSet<>();List<String> approved=new ArrayList<>();long total=0;int omitted=0;
            for(Path candidate:candidates) {
                if(root.relativize(candidate).getNameCount()>=64&&Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Artifact directory depth exceeds limits");
                if(candidate.equals(root)||Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.isSymbolicLink(candidate)||!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)){omitted++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                if(!candidate.toRealPath().startsWith(root))throw new IllegalArgumentException("Artifact escaped its private directory");
                if(metrics!=null)metrics.source(Files.size(candidate));
                String relative=root.relativize(candidate).toString().replace('\\','/'),lower=relative.toLowerCase(Locale.ROOT);
                if(!safePath(relative)||lower.equals(MANIFEST)||!SAFE_EXTENSIONS.contains(extension(relative))||names.contains(lower)){omitted++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                if(approved.size()>=MAX_ENTRIES)throw new IllegalArgumentException("Artifact entry count exceeds limits");
                long sourceSize=Files.size(candidate);
                if(sourceSize>MAX_ENTRY_BYTES){omitted++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                byte[] bytes;
                try{bytes=publisher.publish(candidate);}catch(IllegalArgumentException rejected){omitted++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                if(bytes.length>MAX_ENTRY_BYTES){omitted++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                if(total+bytes.length>MAX_TOTAL_BYTES)throw new IllegalArgumentException("Artifact total size exceeds limits");
                names.add(lower);approved.add(relative);total+=bytes.length;
                Path target=exports.resolve(relative);Files.createDirectories(target.getParent());Files.write(target,bytes);
            }
            // Resolve report links only after classification, so omitted evidence never leaves a dangling raw link.
            Set<String> paths=Set.copyOf(approved);List<Entry> entries=new ArrayList<>();total=0;
            if(metrics!=null)metrics.phase("zip_write");
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(staging))) {
                for(String relative:approved) {
                    byte[] bytes=Files.readAllBytes(exports.resolve(relative));
                    boolean report=Set.of("html","htm").contains(extension(relative));
                    if(report) {
                        if(metrics!=null)metrics.phase("scan_classification_redaction");
                        bytes=ArtifactHtmlPolicy.publish(bytes,relative,paths);
                        if(metrics!=null)metrics.phase("zip_write");
                    }
                    if(bytes.length>MAX_ENTRY_BYTES||total+bytes.length>MAX_TOTAL_BYTES)throw new IllegalArgumentException("Final artifact size exceeds limits");
                    total+=bytes.length;
                    entries.add(new Entry(relative,mediaType(relative),bytes.length,sha256(bytes),"textual-output","sanitized-once",report,true));
                    writeEntry(zip,relative,bytes);
                    if(metrics!=null){metrics.value.includedEntries++;metrics.value.includedBytes+=bytes.length;}
                }
                Map<String,Object> manifest=new LinkedHashMap<>();
                manifest.put("version",1);manifest.put("policy","att-export-safe-text-v2");manifest.put("entries",entries);manifest.put("complete",omitted==0);
                manifest.put("omissions",omitted==0?List.of():List.of(Map.of("count",omitted,"reason",OMISSION_REASON)));
                byte[] bytes=JSON.writeValueAsBytes(manifest);
                if(bytes.length>MAX_MANIFEST_BYTES)throw new IllegalArgumentException("Artifact manifest exceeds limits");
                writeEntry(zip,MANIFEST,bytes);
            }
            if(metrics!=null)metrics.phase("verification_fsync");
            verify(staging,true);
            try(var channel=java.nio.channels.FileChannel.open(staging,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(staging,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            published=true;
            if(metrics!=null)metrics.value.zipBytes=Files.size(destination);
            return destination;
        } catch(Exception failure) {
            if(metrics!=null)metrics.fail(failure instanceof IllegalArgumentException?"policy_limit":"publication_failed");
            throw failure;
        } finally {
            if(metrics!=null)metrics.phase("cleanup");
            if(!published)Files.deleteIfExists(staging);
            try(var paths=Files.walk(exports)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }

    /** Prune only the established private LOAD bookkeeping namespace. Never follow links. */
    private static List<Path> inventory(Path root) throws java.io.IOException {
        List<Path> candidates = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), 64, new java.nio.file.SimpleFileVisitor<Path>() {
            private void count(Path path) {
                if (candidates.size() >= MAX_CANDIDATES)
                    throw new IllegalArgumentException("Artifact inventory exceeds limits");
                candidates.add(path);
            }
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path directory,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                Path relative = root.relativize(directory);
                // Exact spelling and position: aliases, arbitrary hidden folders
                // and symlinks remain untrusted candidates with the usual bounds.
                if (relative.getNameCount() == 3 && "load".equals(relative.getName(0).toString())
                        && ".exec-id-reservations".equals(relative.getName(2).toString()))
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                count(directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isDirectory()) throw new IllegalArgumentException("Artifact directory depth exceeds limits");
                count(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        candidates.sort(Comparator.comparing(path -> root.relativize(path).toString()));
        return candidates;
    }

    static byte[] read(Path archive, String requested) throws Exception {
        try (VerifiedRead read = openVerified(archive, false)) { return read.read(requested); }
    }

    /** Request-scoped handle: verify structure once, pin that ZIP, check every requested payload. */
    static final class VerifiedRead implements AutoCloseable {
        private final ZipFile zip;
        private final Map<String, Map<?,?>> entries;
        final Map<String,Object> manifest;
        private VerifiedRead(ZipFile zip, Map<String,Object> manifest) {
            this.zip = zip;
            Map<String,Map<?,?>> indexed = new java.util.HashMap<>();
            List<Map<?,?>> rows = new ArrayList<>();
            for (Object value : (List<?>) manifest.get("entries")) {
                Map<?,?> row = Map.copyOf((Map<?,?>) value);
                rows.add(row);
                indexed.put((String) row.get("path"), row);
            }
            entries = Map.copyOf(indexed);
            Map<String,Object> snapshot = new LinkedHashMap<>(manifest);
            snapshot.put("entries", List.copyOf(rows));
            snapshot.put("omissions", ((List<?>) manifest.get("omissions")).stream().map(value -> Map.copyOf((Map<?,?>) value)).toList());
            this.manifest = Map.copyOf(snapshot);
        }
        byte[] read(String requested) throws Exception {
            if (!safePath(requested) || MANIFEST.equals(requested)) throw new IllegalArgumentException("Invalid archive entry path");
            Map<?,?> approved = entries.get(requested);
            if (approved == null) throw new java.io.FileNotFoundException();
            ZipEntry entry = zip.getEntry(requested);
            if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES) throw new java.io.FileNotFoundException();
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes((int) MAX_ENTRY_BYTES + 1);
                if (bytes.length > MAX_ENTRY_BYTES || bytes.length != entry.getSize() || !sha256(bytes).equals(approved.get("sha256")) || !validCrc(entry,bytes)) throw new IllegalArgumentException("Archive entry exceeds limits or is corrupt");
                return bytes;
            }
        }
        @Override public void close() throws java.io.IOException { zip.close(); }
    }

    static VerifiedRead openVerified(Path archive, boolean digests) throws Exception {
        var before = Files.readAttributes(archive, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.isSymbolicLink() || !before.isRegularFile() || before.size() > MAX_ARCHIVE_BYTES)
            throw new IllegalArgumentException("Invalid artifact archive");
        ArtifactZipStructure.verify(archive);
        ZipFile zip = new ZipFile(archive.toFile());
        try {
            Map<String,Object> manifest = verifyManifest(zip, digests);
            var after = Files.readAttributes(archive, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
                throw new IllegalArgumentException("Artifact archive changed during verification");
            return new VerifiedRead(zip, manifest);
        } catch (Exception failure) { zip.close(); throw failure; }
    }

    /** Validate the entire directory against the bounded manifest before trusting any entry. */
    static Map<String,Object> verify(Path archive, boolean digests) throws Exception {
        try (VerifiedRead read = openVerified(archive, digests)) { return read.manifest; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> verifyManifest(ZipFile zip, boolean digests) throws Exception {
        Set<String> names = new HashSet<>();
        long total = 0;
        int count = 0;
        var directory = zip.entries();
        while (directory.hasMoreElements()) {
            ZipEntry entry = directory.nextElement();
            long cap = MANIFEST.equals(entry.getName()) ? MAX_MANIFEST_BYTES : MAX_ENTRY_BYTES;
            if (++count > MAX_ENTRIES + 1 || !safePath(entry.getName()) || entry.isDirectory()
                    || !names.add(entry.getName().toLowerCase(Locale.ROOT)) || entry.getSize() < 0 || entry.getSize() > cap
                    || entry.getCompressedSize() < 0 || entry.getSize() > Math.max(1, entry.getCompressedSize()) * 200L)
                throw new IllegalArgumentException("Unsafe archive directory");
            if (!MANIFEST.equals(entry.getName())) total += entry.getSize();
            if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Archive total exceeds limits");
        }
        ZipEntry manifestEntry = zip.getEntry(MANIFEST);
        if (manifestEntry == null) throw new IllegalArgumentException("Archive manifest is missing");
        Map<String,Object> manifest;
        try (InputStream input = zip.getInputStream(manifestEntry)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length != manifestEntry.getSize() || !validCrc(manifestEntry,bytes)) throw new IllegalArgumentException("Invalid manifest size");
            try { manifest = JSON.readValue(bytes, Map.class); }
            catch (com.fasterxml.jackson.core.JacksonException invalid) { throw new IllegalArgumentException("Invalid archive manifest"); }
        }
        if (manifest == null || !manifest.keySet().equals(Set.of("version", "policy", "entries", "complete", "omissions"))
                || !Integer.valueOf(1).equals(manifest.get("version")) || !validOmissions(manifest)
                || !"att-export-safe-text-v2".equals(manifest.get("policy")) || !(manifest.get("entries") instanceof List<?> entries)
                || entries.size() != count - 1) throw new IllegalArgumentException("Invalid archive manifest");
        Set<String> approved = new HashSet<>();
        for (Object value : entries) {
            if (!(value instanceof Map<?,?> row) || !row.keySet().equals(Set.of("path", "mediaType", "size", "sha256", "classification", "decision", "report", "preview"))
                    || !(row.get("path") instanceof String path) || !safePath(path)
                    || MANIFEST.equalsIgnoreCase(path) || !approved.add(path.toLowerCase(Locale.ROOT)) || !SAFE_EXTENSIONS.contains(extension(path)))
                throw new IllegalArgumentException("Invalid manifest entry");
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || !integral(row.get("size")) || ((Number) row.get("size")).longValue() != entry.getSize()
                    || !(row.get("sha256") instanceof String digest) || !digest.matches("[a-f0-9]{64}")
                    || !mediaType(path).equals(row.get("mediaType")) || !"textual-output".equals(row.get("classification"))
                    || !"sanitized-once".equals(row.get("decision")) || !Boolean.TRUE.equals(row.get("preview"))
                    || !Boolean.valueOf(Set.of("html","htm").contains(extension(path))).equals(row.get("report")))
                throw new IllegalArgumentException("Archive does not match manifest");
            if (digests) try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes((int)MAX_ENTRY_BYTES + 1);
                if (bytes.length != entry.getSize() || !sha256(bytes).equals(digest) || !validCrc(entry,bytes)) throw new IllegalArgumentException("Archive integrity check failed");
            }
        }
        return manifest;
    }

    private static boolean integral(Object value) { return value instanceof Integer || value instanceof Long; }

    private static boolean validOmissions(Map<String, Object> manifest) {
        if (!(manifest.get("complete") instanceof Boolean complete) || !(manifest.get("omissions") instanceof List<?> omissions)) return false;
        if (complete) return omissions.isEmpty();
        if (omissions.size() != 1 || !(omissions.get(0) instanceof Map<?, ?> omission)
                || !omission.keySet().equals(Set.of("count", "reason")) || !OMISSION_REASON.equals(omission.get("reason"))
                || !integral(omission.get("count"))) return false;
        long count = ((Number) omission.get("count")).longValue();
        return count > 0 && count <= MAX_CANDIDATES;
    }

    private static void writeEntry(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        // STORED makes size/ratio limits deterministic even for highly repetitive legitimate logs.
        var crc = new java.util.zip.CRC32(); crc.update(bytes);
        ZipEntry entry = new ZipEntry(path); entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }

    private static boolean validCrc(ZipEntry entry,byte[] bytes) {
        var crc=new java.util.zip.CRC32();crc.update(bytes);return crc.getValue()==entry.getCrc();
    }

    static boolean safePath(String name) {
        if (name == null || name.isBlank() || name.length() > 2048 || name.startsWith("/") || !java.text.Normalizer.isNormalized(name,java.text.Normalizer.Form.NFC) || name.indexOf('\\') >= 0 || name.indexOf('%') >= 0 || name.indexOf(':') >= 0 || name.indexOf('?') >= 0 || name.indexOf('#') >= 0 || name.chars().anyMatch(c -> c < 32 || c == 127 || "<>\"|*".indexOf(c)>=0)) return false;
        for (String part : name.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) return false;
        return true;
    }
    private static String extension(String path) { int dot = path.lastIndexOf('.'); return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private static String mediaType(String path) { return switch (extension(path)) { case "html", "htm" -> "text/html"; case "json" -> "application/json"; case "yaml", "yml" -> "application/yaml"; case "xml" -> "application/xml"; case "css" -> "text/css"; case "csv" -> "text/csv"; case "md" -> "text/markdown"; default -> "text/plain"; }; }
    static String sha256(byte[] bytes) throws Exception { byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%02x", b)); return out.toString(); }
}
