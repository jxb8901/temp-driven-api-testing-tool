package att.load;

import att.config.SchemaSupport;
import att.config.YamlSupport;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import att.validation.JsonSchemaVerifier;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the historical att-load-profile/v1.0 descriptor for migration compatibility only. */
public final class LoadProfileLoader {
    public static final String SCHEMA_VERSION = "att-load-profile/v1.0";
    private final Path projectRoot;

    public LoadProfileLoader(Path projectRoot) { this.projectRoot = projectRoot.toAbsolutePath().normalize(); }

    /** Returns null when no default quick-load profile exists. */
    public Map<String, Object> loadDefault() throws Exception {
        Path path = projectRoot.resolve("load/load.yaml");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw invalid(path, "Quick-load profile must be a regular non-symlink file", null);
        try {
            Object loaded = YamlSupport.load(path);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Quick-load profile must be a YAML map");
            Map<String, Object> map = objectMap((Map<?, ?>) loaded);
            JsonSchemaVerifier.verify(att.validation.SchemaFiles.resolveVersion(projectRoot, SCHEMA_VERSION), map);
            SchemaSupport.requireVersion(map, SCHEMA_VERSION, "quick-load profile");
            return map;
        } catch (DiagnosticException error) {
            throw error;
        } catch (Exception error) {
            throw invalid(path, error.getMessage(), error);
        }
    }

    private DiagnosticException invalid(Path path, String detail, Throwable cause) {
        return new DiagnosticException(DiagnosticCodes.LOAD_INVALID, "Invalid quick-load profile", detail,
                path.toString(), "profile", null, null, null, null, null,
                "Migrate load/load.yaml to the current policy-only att-load/v1.5 descriptor with load intensity, evidence, thresholds, and optional testdata imports.", cause);
    }

    private static Map<String, Object> objectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
}
