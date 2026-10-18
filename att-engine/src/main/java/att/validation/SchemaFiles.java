package att.validation;

import att.config.YamlSupport;
import att.resource.PackageResourceResolver;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.TreeMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.stream.Stream;

/** Resolves schemas only through the trusted registry shipped with ATT. */
public final class SchemaFiles {
    private static final String RESOURCE_ROOT = "att-schemas/";
    private static volatile Path trustedRoot;
    private SchemaFiles() {}
    public static Path resolve(Path projectRoot, String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9._-]+\\.(?:json|xsd)"))
            throw new IllegalArgumentException("Invalid schema filename");
        Path root;
        try { root = trustedRoot(); }
        catch (Exception error) { throw unavailable("Trusted schema registry is unavailable", error.getMessage(), "Restore the ATT runtime schema resources."); }
        Path schemas = root.resolve("schemas");
        Path catalog = schemas.resolve("catalog.yaml");
        try {
            requireSchemaDirectory(schemas);
            requireRegular(catalog, "Schema catalog is unavailable", "Restore schemas/catalog.yaml before validating this ATT package.");
            Path canonicalCatalog = new PackageResourceResolver(root)
                    .fromInternalPath(catalog, PackageResourceResolver.Kind.FILE).canonicalPath();
            Object loaded = YamlSupport.load(canonicalCatalog);
            if (!(loaded instanceof Map) || !"att-schema-catalog/v3.0".equals(String.valueOf(((Map<?, ?>) loaded).get("schemaVersion"))))
                throw unavailable("Schema catalog is invalid", "catalog=schemas/catalog.yaml",
                        "Restore a valid att-schema-catalog/v3.0 catalog from the ATT package.");
            Object registry = ((Map<?, ?>) loaded).get("schemas");
            if (!(registry instanceof Map)) throw unavailable("Schema catalog is invalid", "catalog.schemas is missing",
                    "Restore a valid schema registry in schemas/catalog.yaml.");
            String registeredPath = null;
            for (Object value : ((Map<?, ?>) registry).values()) {
                if (!(value instanceof String)) continue;
                String path = ((String) value).replace('\\', '/');
                if (filename.equals(path) || ("history/" + filename).equals(path)) {
                    if (registeredPath != null && !registeredPath.equals(path))
                        throw unavailable("Schema catalog has ambiguous registrations", "schema=" + filename,
                                "Keep one authoritative current or historical catalog path for this schema.");
                    registeredPath = path;
                }
            }
            if (registeredPath == null) throw unavailable("Schema resource is not registered", "schema=" + filename,
                    "Register the required schema in schemas/catalog.yaml; ATT will not guess or fall back to another schema.");
            Path selected = schemas.resolve(registeredPath).normalize();
            if (!selected.startsWith(schemas) || !filename.equals(selected.getFileName().toString()))
                throw unavailable("Schema catalog contains an unsafe path", "schema=" + filename + ", registered=" + registeredPath,
                        "Use a package-contained schemas/ or schemas/history/ path in the catalog.");
            Path other = registeredPath.startsWith("history/")
                    ? schemas.resolve(filename) : schemas.resolve("history").resolve(filename);
            if (Files.exists(other, LinkOption.NOFOLLOW_LINKS))
                throw unavailable("Duplicate current/historical schema", "schema=" + filename + ", registered=" + registeredPath,
                        "Keep the schema only at its catalog-registered location.");
            requireRegular(selected, "Registered schema resource is unavailable",
                    "Restore the registered schema at " + registeredPath + "; ATT will not skip validation or fall back to another copy.");
            return new PackageResourceResolver(root).fromInternalPath(selected, PackageResourceResolver.Kind.FILE).canonicalPath();
        } catch (DiagnosticException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Schema catalog cannot be read", "catalog=schemas/catalog.yaml, reason=" + att.core.PathPresentation.displayDiagnosticText(error.getMessage(), root),
                    "Restore a readable schema catalog and the registered schema resources from the ATT package.");
        }
    }

    /**
     * Returns the registered historical schema versions and their current
     * counterpart. The catalog's current/history locations, not filename
     * ordering, define which version is current for each descriptor family.
     */
    public static Map<String, String> historicalSchemaVersions(Path projectRoot) {
        List<RegisteredSchema> registered = registeredJsonSchemas(projectRoot);
        Map<String, String> current = new LinkedHashMap<String, String>();
        for (RegisteredSchema schema : registered) {
            if (schema.historical) continue;
            String family = family(schema.version);
            String previous = current.put(family, schema.version);
            if (previous != null && !previous.equals(schema.version))
                throw unavailable("Schema catalog has multiple current versions", "family=" + family,
                        "Register exactly one current schema resource for each descriptor family.");
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (RegisteredSchema schema : registered) {
            if (!schema.historical) continue;
            String latest = current.get(family(schema.version));
            if (latest != null && !latest.equals(schema.version)) result.put(schema.version, latest);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Resolves a registered JSON Schema by its declared schemaVersion. */
    public static Path resolveVersion(Path projectRoot, String schemaVersion) {
        Path root;
        try { root = trustedRoot(); }
        catch (Exception error) { throw unavailable("Trusted schema registry is unavailable", error.getMessage(), "Restore the ATT runtime schema resources."); }
        for (RegisteredSchema schema : registeredJsonSchemas(projectRoot)) {
            if (schema.version.equals(schemaVersion)) {
                Path selected = root.resolve("schemas").resolve(schema.path).normalize();
                Path schemas = root.resolve("schemas");
                if (!selected.startsWith(schemas)) throw unavailable("Schema catalog contains an unsafe path", "schemaVersion=" + schemaVersion,
                        "Use a package-contained path in the schema catalog.");
                requireRegular(selected, "Registered schema resource is unavailable",
                        "Restore the registered schema at " + schema.path + ".");
                return new PackageResourceResolver(root)
                        .fromInternalPath(selected, PackageResourceResolver.Kind.FILE).canonicalPath();
            }
        }
        throw unavailable("Schema version is not registered", "schemaVersion=" + schemaVersion,
                "Use a schemaVersion registered by this ATT package.");
    }

    private static List<RegisteredSchema> registeredJsonSchemas(Path projectRoot) {
        Path root;
        try { root = trustedRoot(); }
        catch (IOException error) { throw unavailable("Trusted schema registry is unavailable", error.getMessage(), "Restore the ATT runtime schema resources."); }
        validateCatalog(root);
        Path schemas = root.resolve("schemas");
        Path catalog = schemas.resolve("catalog.yaml");
        try {
            Path canonicalCatalog = new PackageResourceResolver(root)
                    .fromInternalPath(catalog, PackageResourceResolver.Kind.FILE).canonicalPath();
            Object loaded = YamlSupport.load(canonicalCatalog);
            Object registry = ((Map<?, ?>) loaded).get("schemas");
            List<RegisteredSchema> result = new ArrayList<RegisteredSchema>();
            Set<String> versions = new HashSet<String>();
            for (Object rawPath : ((Map<?, ?>) registry).values()) {
                String path = String.valueOf(rawPath).replace('\\', '/');
                if (!path.endsWith(".json")) continue;
                Path resource = schemas.resolve(path).normalize();
                if (!resource.startsWith(schemas)) throw unavailable("Schema catalog contains an unsafe path", "path=" + path,
                        "Use a package-contained schemas/ or schemas/history/ path in the catalog.");
                Path canonicalResource = new PackageResourceResolver(root)
                        .fromInternalPath(resource, PackageResourceResolver.Kind.FILE).canonicalPath();
                com.fasterxml.jackson.databind.JsonNode document = JsonSupport.mapper().readTree(Files.readAllBytes(canonicalResource));
                com.fasterxml.jackson.databind.JsonNode versionNode = document.path("properties").path("schemaVersion").path("const");
                if (!versionNode.isTextual()) continue;
                String version = versionNode.asText();
                if (!versions.add(version)) throw unavailable("Schema catalog has duplicate schema versions", "schemaVersion=" + version,
                        "Register each schemaVersion at exactly one path.");
                result.add(new RegisteredSchema(version, path, path.startsWith("history/")));
            }
            return result;
        } catch (DiagnosticException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Schema catalog cannot be read", "catalog=schemas/catalog.yaml, reason=" + att.core.PathPresentation.displayDiagnosticText(error.getMessage(), root),
                    "Restore a readable schema catalog and its registered JSON Schema resources.");
        }
    }

    private static String family(String version) {
        int marker = version.lastIndexOf("/v");
        return marker < 0 ? version : version.substring(0, marker);
    }

    private static final class RegisteredSchema {
        private final String version, path;
        private final boolean historical;
        private RegisteredSchema(String version, String path, boolean historical) {
            this.version = version; this.path = path; this.historical = historical;
        }
    }

    /** Verifies every current and historical schema resource registered by this package. */
    public static void validateCatalog(Path projectRoot) {
        Path root;
        try { root = trustedRoot(); }
        catch (Exception error) { throw unavailable("Trusted schema registry is unavailable", error.getMessage(), "Restore the ATT runtime schema resources."); }
        Path schemas = root.resolve("schemas");
        Path catalog = schemas.resolve("catalog.yaml");
        try {
            requireSchemaDirectory(schemas);
            requireRegular(catalog, "Schema catalog is unavailable", "Restore schemas/catalog.yaml before validating this ATT package.");
            Object loaded = YamlSupport.load(catalog);
            if (!(loaded instanceof Map) || !"att-schema-catalog/v3.0".equals(String.valueOf(((Map<?, ?>) loaded).get("schemaVersion"))))
                throw unavailable("Schema catalog is invalid", "catalog=schemas/catalog.yaml",
                        "Restore a valid att-schema-catalog/v3.0 catalog from the ATT package.");
            Object registry = ((Map<?, ?>) loaded).get("schemas");
            if (!(registry instanceof Map) || ((Map<?, ?>) registry).isEmpty())
                throw unavailable("Schema catalog is invalid", "catalog.schemas is missing or empty",
                        "Restore a non-empty schema registry in schemas/catalog.yaml.");
            Set<String> filenames = new HashSet<String>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) registry).entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))
                    throw unavailable("Schema catalog is invalid", "every schema registration must have a string key and path",
                            "Restore a valid schema catalog from the ATT package.");
                String path = ((String) entry.getValue()).replace('\\', '/');
                if (!path.matches("(?:history/)?[A-Za-z0-9._-]+\\.(?:json|xsd)"))
                    throw unavailable("Schema catalog contains an unsafe path", "registration=" + entry.getKey() + ", path=" + path,
                            "Use a package-contained schemas/ or schemas/history/ path in the catalog.");
                String filename = path.substring(path.lastIndexOf('/') + 1);
                if (!filenames.add(filename))
                    throw unavailable("Schema catalog has duplicate schema registrations", "filename=" + filename,
                            "Register each schema filename at exactly one current or historical location.");
                Path resource = schemas.resolve(path).normalize();
                if (!resource.startsWith(schemas))
                    throw unavailable("Schema catalog contains an unsafe path", "registration=" + entry.getKey() + ", path=" + path,
                            "Use a package-contained schemas/ or schemas/history/ path in the catalog.");
                requireRegular(resource, "Registered schema resource is unavailable",
                        "Restore the registered schema at " + path + "; ATT will not skip validation or fall back to another copy.");
            }
        } catch (DiagnosticException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Schema catalog cannot be read", "catalog=schemas/catalog.yaml, reason=" + att.core.PathPresentation.displayDiagnosticText(error.getMessage(), root),
                    "Restore a readable schema catalog and every registered schema resource from the ATT package.");
        }
    }

    /** Shares one private materialization per packaged schema version across Worker JVMs. */
    private static Path trustedRoot() throws IOException {
        Path cached = trustedRoot;
        if (cached != null) return cached;
        synchronized (SchemaFiles.class) {
            if (trustedRoot != null) return trustedRoot;
            ClassLoader loader = SchemaFiles.class.getClassLoader();
            byte[] catalogBytes;
            try (InputStream stream = loader.getResourceAsStream(RESOURCE_ROOT + "catalog.yaml")) {
                if (stream == null) throw new IOException("Missing classpath resource " + RESOURCE_ROOT + "catalog.yaml");
                catalogBytes = readAll(stream);
            }
            Object loaded;
            try { loaded = YamlSupport.parser().load(new String(catalogBytes, java.nio.charset.StandardCharsets.UTF_8)); }
            catch (RuntimeException error) { throw new IOException("The packaged schema catalog is unreadable", error); }
            if (!(loaded instanceof Map) || !"att-schema-catalog/v3.0".equals(String.valueOf(((Map<?, ?>) loaded).get("schemaVersion"))))
                throw new IOException("The packaged schema catalog has an unsupported version");
            Object rawSchemas = ((Map<?, ?>) loaded).get("schemas");
            if (!(rawSchemas instanceof Map) || ((Map<?, ?>) rawSchemas).isEmpty()) throw new IOException("The packaged schema registry is empty");
            Map<String, byte[]> packagedFiles = new TreeMap<String, byte[]>();
            packagedFiles.put("catalog.yaml", catalogBytes);
            Set<String> filenames = new HashSet<String>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawSchemas).entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))
                    throw new IOException("The packaged schema registry contains a non-string registration");
                String relative = ((String) entry.getValue()).replace('\\', '/');
                if (!relative.matches("(?:history/)?[A-Za-z0-9._-]+\\.(?:json|xsd)")) throw new IOException("The packaged schema registry contains an unsafe path");
                String filename = relative.substring(relative.lastIndexOf('/') + 1);
                if (!filenames.add(filename)) throw new IOException("The packaged schema registry contains duplicate schema files");
                try (InputStream stream = loader.getResourceAsStream(RESOURCE_ROOT + relative)) {
                    if (stream == null) throw new IOException("Missing classpath resource " + RESOURCE_ROOT + relative);
                    packagedFiles.put(relative, readAll(stream));
                }
            }
            Path cacheBase = cacheBase();
            String fingerprint = fingerprint(packagedFiles);
            Path root = cacheBase.resolve(fingerprint);
            Path lockPath = cacheBase.resolve("att-trusted-schemas.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                restrictPermissions(lockPath, false);
                boolean complete;
                try { complete = cacheMatches(root, packagedFiles); }
                catch (IOException ignoredRead) { complete = false; }
                if (!complete) {
                    deleteTree(root);
                    try {
                        createPrivateDirectory(root);
                        Path schemas = root.resolve("schemas");
                        createPrivateDirectory(schemas);
                        for (Map.Entry<String, byte[]> file : packagedFiles.entrySet()) {
                            Path destination = schemas.resolve(file.getKey()).normalize();
                            if (!destination.startsWith(schemas)) throw new IOException("The packaged schema registry contains a path outside schemas/");
                            createPrivateDirectory(destination.getParent());
                            Files.write(destination, file.getValue());
                            restrictPermissions(destination, false);
                        }
                    } catch (IOException | RuntimeException failure) {
                        deleteTree(root);
                        throw failure;
                    }
                    if (!cacheMatches(root, packagedFiles)) {
                        deleteTree(root);
                        throw new IOException("The trusted schema cache could not be verified after extraction");
                    }
                }
            }
            trustedRoot = root;
            return root;
        }
    }

    private static Path cacheBase() throws IOException {
        String username = System.getProperty("user.name", "unknown");
        String userToken = fingerprint(Collections.singletonMap("user", username.getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        Path temporary = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(temporary) || !Files.isDirectory(temporary, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The temporary directory is unavailable");
        Path base = temporary.resolve("att-trusted-schema-cache-" + userToken);
        Path home = java.nio.file.Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        // Compare filesystem owners only where POSIX ownership semantics apply.
        // Windows temp/profile folders can report different ACL owners even for
        // directories created by the same logged-in user; inherited temp-folder
        // ACLs provide the private fallback there.
        boolean posixOwnerView = false;
        try {
            posixOwnerView = Files.getFileAttributeView(temporary, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
        } catch (UnsupportedOperationException ignored) {
            // Fall back to the temp directory's inherited ACL on non-POSIX providers.
        }
        UserPrincipal expectedOwner = posixOwnerView && Files.exists(home, LinkOption.NOFOLLOW_LINKS)
                ? Files.getOwner(home, LinkOption.NOFOLLOW_LINKS) : null;
        if (Files.exists(base, LinkOption.NOFOLLOW_LINKS) && expectedOwner != null
                && !expectedOwner.equals(Files.getOwner(base, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("The trusted schema cache has an unexpected owner");
        createPrivateDirectory(base);
        if (expectedOwner != null && !expectedOwner.equals(Files.getOwner(base, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("The trusted schema cache has an unexpected owner");
        return base;
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        if (directory == null) throw new IOException("The trusted schema cache path is invalid");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)));
            } catch (UnsupportedOperationException unsupported) {
                try { Files.createDirectory(directory); }
                catch (FileAlreadyExistsException concurrent) { /* Another Worker created the shared directory. */ }
            } catch (FileAlreadyExistsException concurrent) {
                // Another Worker created the shared directory concurrently.
            }
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The trusted schema cache contains an unsafe directory");
        restrictPermissions(directory, true);
    }

    private static boolean cacheMatches(Path root, Map<String, byte[]> packagedFiles) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
        Path schemas = root.resolve("schemas");
        if (Files.isSymbolicLink(schemas) || !Files.isDirectory(schemas, LinkOption.NOFOLLOW_LINKS)) return false;
        for (Map.Entry<String, byte[]> file : packagedFiles.entrySet()) {
            Path path = schemas.resolve(file.getKey()).normalize();
            if (!path.startsWith(schemas) || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(Files.readAllBytes(path), file.getValue())) return false;
        }
        return true;
    }

    private static String fingerprint(Map<String, byte[]> files) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> file : new TreeMap<String, byte[]>(files).entrySet()) {
                digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.getValue());
                digest.update((byte) 0);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable for the trusted schema cache", error);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
        }
    }

    private static void restrictPermissions(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // The JVM's private temporary-directory defaults remain the fallback on non-POSIX filesystems.
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) >= 0) result.write(buffer, 0, count);
        return result.toByteArray();
    }

    private static void requireSchemaDirectory(Path schemas) {
        if (Files.isSymbolicLink(schemas) || !Files.isDirectory(schemas, LinkOption.NOFOLLOW_LINKS))
            throw unavailable("Schema directory is unavailable", "expected=schemas/",
                    "Restore the package schemas/ directory without symlinks.");
        Path history = schemas.resolve("history");
        if (Files.exists(history, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(history) || !Files.isDirectory(history, LinkOption.NOFOLLOW_LINKS)))
            throw unavailable("Historical schema directory is unsafe", "expected=schemas/history/",
                    "Restore schemas/history/ as a regular package directory without symlinks.");
    }

    private static void requireRegular(Path path, String title, String suggestion) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || !Files.isReadable(path))
            throw unavailable(title, "expected=" + logicalSchemaPath(path), suggestion);
    }

    private static String logicalSchemaPath(Path path) {
        Path parent = path.getParent();
        if (parent != null && parent.getFileName() != null && "history".equals(parent.getFileName().toString()))
            return "schemas/history/" + path.getFileName();
        return "schemas/" + path.getFileName();
    }

    private static DiagnosticException unavailable(String title, String detail, String suggestion) {
        return DiagnosticException.of(DiagnosticCodes.PACKAGE_INVALID, title, detail, suggestion);
    }
}
