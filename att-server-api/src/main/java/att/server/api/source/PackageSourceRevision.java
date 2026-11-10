package att.server.api.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One canonical Package-owned source closure and digest for authoring, validation,
 * publication and runtime inspection. Manifest, central Config, registry and
 * execution revisions remain separate. No untrusted authored bytes are returned.
 *
 * Deliberately independent of the selected Environment and the resource UI index.
 * Source changes to direct runtime File Expressions, scripts and binary payloads
 * must invalidate a draft even when those files cannot be edited through Studio.
 */
public final class PackageSourceRevision {
    private static final int MAX_FILES = 20000;
    private static final int MAX_ENTRIES = MAX_FILES * 3;
    private static final long MAX_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final Set<String> TEXT = set("yaml", "yml", "xml", "json", "csv", "tsv",
            "txt", "sql", "properties", "md", "xlsx", "xls");
    private static final Set<String> RUNTIME = set("sh", "bash", "py", "js", "ts", "groovy",
            "rb", "pl", "ps1", "bat", "cmd", "xsd", "wsdl", "html", "graphql", "gql",
            "bin", "dat", "png", "jpg", "jpeg", "pdf");
    private static final Set<String> SCRIPT_EXTENSIONS = set("sh", "bash", "py", "js", "ts",
            "groovy", "rb", "pl", "ps1", "bat", "cmd");
    private static final Set<String> RAW_ASSET_ROOTS = set("files", "resources", "scripts", "tools");
    private static final Set<String> SOURCE_ROOTS = set("templates", "flows", "testcase",
            "testdata", "load", "files", "resources", "scripts", "tools", "actions", "stages");
    private static final Set<String> EXCLUDED = set("secrets", "credentials", "private",
            "auth", "roles", "security", "bootstrap", "schemas", "config",
            "output", "jobs", "cache", "node_modules", ".npm", ".yarn", ".git",
            ".att", "target", "logs", "reports", "results", "artifacts", "work", "server");

    private PackageSourceRevision() { }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    /** Package manifest and runtime deployment files are deliberately not PACKAGE_SOURCE. */
    public static boolean includes(String logicalPath) {
        if (logicalPath == null || logicalPath.isEmpty() || logicalPath.startsWith("/")
                || logicalPath.indexOf('\\') >= 0 || logicalPath.contains("//")) return false;
        String lower = logicalPath.toLowerCase(Locale.ROOT);
        if ("package.yaml".equals(lower) || "att-server.yaml".equals(lower)) return false;
        String[] segments = lower.split("/");
        for (String segment : segments) if (segment.isEmpty() || ".".equals(segment)
                || "..".equals(segment) || EXCLUDED.contains(segment)) return false;
        // Raw runtime asset roots may contain extensionless/binary File Expressions.
        // A script referenced by a Template can also live in a custom Package path.
        if (RAW_ASSET_ROOTS.contains(segments[0])) return true;
        String name = segments[segments.length - 1];
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String extension = name.substring(dot + 1);
        if (TEXT.contains(extension) || SCRIPT_EXTENSIONS.contains(extension)) return true;
        return SOURCE_ROOTS.contains(segments[0]) && RUNTIME.contains(extension);
    }

    public static String digest(Path root) throws IOException {
        return digest(root, Collections.<String, byte[]>emptyMap());
    }

    /** Applies a validated authoring overlay without mutating the published source. */
    public static String digest(Path root, Map<String, byte[]> overrides) throws IOException {
        if (root == null || overrides == null) throw new IOException("Package source root is unavailable");
        final Path canonical = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(canonical) || !canonical.equals(canonical.toRealPath()))
            throw new IOException("Package source root is unsafe");
        final TreeMap<String, Path> sources = new TreeMap<String, Path>();
        final int[] visited = new int[] { 0 };
        Files.walkFileTree(canonical, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (++visited[0] > MAX_ENTRIES) throw new IOException("Package source entry limit exceeded");
                if (dir.equals(canonical)) return FileVisitResult.CONTINUE;
                String logical = canonical.relativize(dir).toString().replace('\\', '/');
                String[] segments = logical.toLowerCase(Locale.ROOT).split("/");
                for (String part : segments) if (EXCLUDED.contains(part)) return FileVisitResult.SKIP_SUBTREE;
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir))
                    throw new IOException("Package source contains an unsafe directory");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (++visited[0] > MAX_ENTRIES) throw new IOException("Package source entry limit exceeded");
                String logical = canonical.relativize(file).toString().replace('\\', '/');
                if (!includes(logical)) return FileVisitResult.CONTINUE;
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()
                        || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Package source contains an unsafe file");
                sources.put(logical, file);
                if (sources.size() > MAX_FILES) throw new IOException("Package source file limit exceeded");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                throw failure;
            }
        });
        for (Map.Entry<String, byte[]> change : overrides.entrySet()) {
            String logical = change.getKey();
            if (!includes(logical)) continue;
            if (change.getValue() == null) sources.remove(logical);
            else sources.put(logical, canonical.resolve(logical));
        }
        if (sources.size() > MAX_FILES) throw new IOException("Package source file limit exceeded");
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException invalid) { throw new IOException("Source digest is unavailable", invalid); }
        digest.update("ATT_PACKAGE_SOURCE_V2".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        long total = 0;
        byte[] buffer = new byte[8192];
        for (Map.Entry<String, Path> source : sources.entrySet()) {
            String path = source.getKey();
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (overrides.containsKey(path)) {
                byte[] bytes = overrides.get(path);
                if (bytes == null) throw new IOException("Deleted Package source remains in closure");
                if (bytes.length > MAX_FILE_BYTES) throw new IOException("Package source file exceeds limit");
                total += bytes.length;
                if (total > MAX_BYTES) throw new IOException("Package source exceeds byte limit");
                digest.update(bytes);
            } else {
                Path file = source.getValue();
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) > MAX_FILE_BYTES)
                    throw new IOException("Package source file is unavailable or oversized");
                try (InputStream input = Files.newInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_BYTES) throw new IOException("Package source exceeds byte limit");
                        digest.update(buffer, 0, read);
                    }
                }
            }
            digest.update((byte) 0xff);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) {
            result.append(Character.forDigit((b >>> 4) & 15, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }
}
