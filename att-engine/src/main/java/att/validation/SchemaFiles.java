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
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

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

    /** Materializes the packaged schemas so existing validators can consume them as Paths. */
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

            Path root = Files.createTempDirectory("att-trusted-schemas-");
            restrictPermissions(root, true);
            Path schemas = Files.createDirectory(root.resolve("schemas"));
            restrictPermissions(schemas, true);
            Path catalog = schemas.resolve("catalog.yaml");
            Files.write(catalog, catalogBytes);
            restrictPermissions(catalog, false);
            for (Object value : ((Map<?, ?>) rawSchemas).values()) {
                if (!(value instanceof String)) throw new IOException("The packaged schema registry contains a non-string path");
                String relative = ((String) value).replace('\\', '/');
                if (!relative.matches("(?:history/)?[A-Za-z0-9._-]+\\.(?:json|xsd)")) throw new IOException("The packaged schema registry contains an unsafe path");
                Path destination = schemas.resolve(relative).normalize();
                if (!destination.startsWith(schemas)) throw new IOException("The packaged schema registry contains a path outside schemas/");
                Files.createDirectories(destination.getParent());
                restrictPermissions(destination.getParent(), true);
                try (InputStream stream = loader.getResourceAsStream(RESOURCE_ROOT + relative)) {
                    if (stream == null) throw new IOException("Missing classpath resource " + RESOURCE_ROOT + relative);
                    Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictPermissions(destination, false);
            }
            trustedRoot = root;
            return root;
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
