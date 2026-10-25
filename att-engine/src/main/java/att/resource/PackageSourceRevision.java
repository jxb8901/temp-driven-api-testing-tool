package att.resource;

import att.config.PackageConfig;
import att.config.RuntimeConfigPath;
import att.config.SchemaSupport;
import att.config.YamlSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Bounded fingerprint of Manifest validation inputs, independent of unrelated Package files. */
final class PackageSourceRevision {
    private static final int MAX_DEPENDENCIES = 10000;
    private static final long MAX_BYTES = 268435456L;
    private PackageSourceRevision() { }

    static String capture(Path packageRoot, Path config, PackageConfig candidate) throws Exception {
        Path authoredRoot = packageRoot.toAbsolutePath().normalize();
        Path authoredRuntime = RuntimeConfigPath.root(config);
        Path root = authoredRoot.toRealPath();
        Path runtime = authoredRuntime.toRealPath();
        Path central = runtime.resolve(authoredRuntime.relativize(config.toAbsolutePath().normalize()));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long[] bytes = {0};
        // Hash before parsing the import lists. A second capture after validation fences both
        // changes to those lists and changes to every descriptor they name.
        file(digest, runtime, central, bytes);
        Object loaded = YamlSupport.load(central);
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("Config must be a YAML map");
        Map<?, ?> global = (Map<?, ?>) loaded;
        Set<Path> shared = new TreeSet<Path>();
        imports(global, runtime, shared);
        if (global.get("environments") instanceof Map) {
            for (Map.Entry<?, ?> profile : ((Map<?, ?>) global.get("environments")).entrySet())
                if (!SchemaSupport.isDisabledKey(profile.getKey()) && profile.getValue() instanceof Map)
                    imports((Map<?, ?>) profile.getValue(), runtime, shared);
        }
        text(digest, "global");
        for (Path dependency : shared) file(digest, runtime, dependency, bytes);
        Set<Path> business = new TreeSet<Path>();
        for (List<Path> layer : candidate.descriptorLayers()) for (Path path : layer) {
            Path authored = path.toAbsolutePath().normalize();
            if (!authored.startsWith(authoredRoot)) throw new IOException("Manifest validation dependency is outside its source root");
            business.add(root.resolve(authoredRoot.relativize(authored)));
            if (business.size() + shared.size() > MAX_DEPENDENCIES) throw limit("dependency count");
        }
        text(digest, "package");
        // Root bindings affect validation, but their directory contents do not. Full execution
        // and Job revision checks retain their own business dependency validation.
        binding(digest, root, candidate.templatesRoot());
        binding(digest, root, candidate.testcaseRoot());
        for (Path dependency : business) file(digest, root, dependency, bytes);
        text(digest, att.validation.SchemaFiles.registryDigest());
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void imports(Map<?, ?> source, Path runtime, Set<Path> paths) throws IOException {
        for (String field : new String[]{"toolGroups", "dbhelpers", "mqhelpers", "httphelpers", "sshhelpers"}) {
            Object configured = source.get(field);
            if (!(configured instanceof Iterable)) continue; // The configuration loader diagnoses invalid contracts.
            for (Object item : (Iterable<?>) configured) {
                if (!(item instanceof String)) throw new IllegalArgumentException("Invalid shared descriptor path");
                Path relative = PackageConfig.safeRelative(runtime, ((String) item).trim());
                Path path = runtime.resolve(relative).normalize();
                if (!path.startsWith(runtime.resolve("config"))) throw new IllegalArgumentException("Shared descriptors must remain inside config/");
                paths.add(path);
                if (paths.size() > MAX_DEPENDENCIES) throw limit("dependency count");
            }
        }
    }

    private static void binding(MessageDigest digest, Path root, Path relative) throws IOException {
        PackageConfig.safeRelative(root, relative.toString().replace('\\', '/'));
        Path path = root.resolve(relative);
        text(digest, relative.toString().replace('\\', '/'));
        text(digest, Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                ? root.relativize(path.toRealPath()).toString().replace('\\', '/') : "missing");
    }

    private static void file(MessageDigest digest, Path root, Path file, long[] bytes) throws Exception {
        if (!file.startsWith(root)) throw new IOException("Manifest validation dependency is outside its source root");
        Path relative = root.relativize(file);
        PackageConfig.safeRelative(root, relative.toString().replace('\\', '/'));
        if (Files.isSymbolicLink(file)) throw new IOException("Manifest validation dependency contains an unsafe symlink");
        text(digest, relative.toString().replace('\\', '/'));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            text(digest, "unavailable");
            return; // Preserve source-aware descriptor diagnostics from candidate validation.
        }
        text(digest, "file");
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int size;
            while ((size = input.read(buffer)) != -1) {
                bytes[0] += size;
                if (bytes[0] > MAX_BYTES) throw limit("dependency bytes");
                digest.update(buffer, 0, size);
            }
        }
        digest.update((byte) 255);
    }

    private static IOException limit(String kind) {
        return new IOException("Manifest validation exceeds its " + kind + " limit");
    }
    private static void text(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
