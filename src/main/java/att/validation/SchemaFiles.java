package att.validation;

import att.config.YamlSupport;
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

/** Resolves a schema only through the package's authoritative schema catalog. */
public final class SchemaFiles {
    private SchemaFiles() {}
    public static Path resolve(Path projectRoot, String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9._-]+\\.(?:json|xsd)"))
            throw new IllegalArgumentException("Invalid schema filename");
        Path root = projectRoot.toAbsolutePath().normalize();
        Path schemas = root.resolve("schemas");
        Path catalog = schemas.resolve("catalog.yaml");
        try {
            requireSchemaDirectory(schemas);
            requireRegular(catalog, "Schema catalog is unavailable", "Restore schemas/catalog.yaml before validating this ATT package.");
            Object loaded = YamlSupport.load(catalog);
            if (!(loaded instanceof Map) || !"att-schema-catalog/v3.0".equals(String.valueOf(((Map<?, ?>) loaded).get("schemaVersion"))))
                throw unavailable("Schema catalog is invalid", "catalog=" + catalog,
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
            return selected;
        } catch (DiagnosticException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Schema catalog cannot be read", "catalog=" + catalog + ", reason=" + error.getMessage(),
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
        for (RegisteredSchema schema : registeredJsonSchemas(projectRoot)) {
            if (schema.version.equals(schemaVersion)) {
                Path selected = projectRoot.toAbsolutePath().normalize().resolve("schemas").resolve(schema.path).normalize();
                Path schemas = projectRoot.toAbsolutePath().normalize().resolve("schemas");
                if (!selected.startsWith(schemas)) throw unavailable("Schema catalog contains an unsafe path", "schemaVersion=" + schemaVersion,
                        "Use a package-contained path in the schema catalog.");
                requireRegular(selected, "Registered schema resource is unavailable",
                        "Restore the registered schema at " + schema.path + ".");
                return selected;
            }
        }
        throw unavailable("Schema version is not registered", "schemaVersion=" + schemaVersion,
                "Use a schemaVersion registered by this ATT package.");
    }

    private static List<RegisteredSchema> registeredJsonSchemas(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        validateCatalog(root);
        Path schemas = root.resolve("schemas");
        Path catalog = schemas.resolve("catalog.yaml");
        try {
            Object loaded = YamlSupport.load(catalog);
            Object registry = ((Map<?, ?>) loaded).get("schemas");
            List<RegisteredSchema> result = new ArrayList<RegisteredSchema>();
            Set<String> versions = new HashSet<String>();
            for (Object rawPath : ((Map<?, ?>) registry).values()) {
                String path = String.valueOf(rawPath).replace('\\', '/');
                if (!path.endsWith(".json")) continue;
                Path resource = schemas.resolve(path).normalize();
                if (!resource.startsWith(schemas)) throw unavailable("Schema catalog contains an unsafe path", "path=" + path,
                        "Use a package-contained schemas/ or schemas/history/ path in the catalog.");
                com.fasterxml.jackson.databind.JsonNode document = JsonSupport.mapper().readTree(Files.readAllBytes(resource));
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
            throw unavailable("Schema catalog cannot be read", "catalog=" + catalog + ", reason=" + error.getMessage(),
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
        Path schemas = projectRoot.toAbsolutePath().normalize().resolve("schemas");
        Path catalog = schemas.resolve("catalog.yaml");
        try {
            requireSchemaDirectory(schemas);
            requireRegular(catalog, "Schema catalog is unavailable", "Restore schemas/catalog.yaml before validating this ATT package.");
            Object loaded = YamlSupport.load(catalog);
            if (!(loaded instanceof Map) || !"att-schema-catalog/v3.0".equals(String.valueOf(((Map<?, ?>) loaded).get("schemaVersion"))))
                throw unavailable("Schema catalog is invalid", "catalog=" + catalog,
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
            throw unavailable("Schema catalog cannot be read", "catalog=" + catalog + ", reason=" + error.getMessage(),
                    "Restore a readable schema catalog and every registered schema resource from the ATT package.");
        }
    }

    private static void requireSchemaDirectory(Path schemas) {
        if (Files.isSymbolicLink(schemas) || !Files.isDirectory(schemas, LinkOption.NOFOLLOW_LINKS))
            throw unavailable("Schema directory is unavailable", "expected=" + schemas,
                    "Restore the package schemas/ directory without symlinks.");
        Path history = schemas.resolve("history");
        if (Files.exists(history, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(history) || !Files.isDirectory(history, LinkOption.NOFOLLOW_LINKS)))
            throw unavailable("Historical schema directory is unsafe", "expected=" + history,
                    "Restore schemas/history/ as a regular package directory without symlinks.");
    }

    private static void requireRegular(Path path, String title, String suggestion) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || !Files.isReadable(path))
            throw unavailable(title, "expected=" + path, suggestion);
    }

    private static DiagnosticException unavailable(String title, String detail, String suggestion) {
        return DiagnosticException.of(DiagnosticCodes.PACKAGE_INVALID, title, detail, suggestion);
    }
}
