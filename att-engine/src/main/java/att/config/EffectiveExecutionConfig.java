package att.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable resolution identity shared by inspection, validation and execution. */
public final class EffectiveExecutionConfig {
    private final String identity, environmentOrigin, manifestDigest, globalDigest;
    private final Map<String, String> origins;
    private final String registeredPackageId;
    private final java.util.List<String> diagnostics;
    EffectiveExecutionConfig(String identity, String environmentOrigin, String manifestDigest, String globalDigest, Map<String, String> origins, java.util.List<String> diagnostics) {
        this(identity, environmentOrigin, manifestDigest, globalDigest, origins, diagnostics, null);
    }
    private EffectiveExecutionConfig(String identity, String environmentOrigin, String manifestDigest, String globalDigest, Map<String,String> origins, java.util.List<String> diagnostics, String registeredPackageId) {
        this.registeredPackageId = registeredPackageId;
        this.identity = identity; this.environmentOrigin = environmentOrigin; this.manifestDigest = manifestDigest;
        this.globalDigest = globalDigest;
        this.diagnostics = Collections.unmodifiableList(new java.util.ArrayList<String>(diagnostics));
        this.origins = Collections.unmodifiableMap(new LinkedHashMap<String, String>(origins));
    }
    public java.util.List<String> diagnostics() { return diagnostics; }
    public String identity() { return identity; }
    public String environmentOrigin() { return environmentOrigin; }
    public String manifestDigest() { return manifestDigest; }
    public Map<String, String> origins() { return origins; }

    EffectiveExecutionConfig withContext(String origin, String packageId) {
        Map<String,String> fields = new LinkedHashMap<String,String>(origins);
        fields.put("environment", origin);
        return new EffectiveExecutionConfig(identity, origin, manifestDigest, globalDigest, fields, diagnostics, packageId);
    }

    /** Value-free evidence: no credentials, authored values or host filesystem coordinates. */
    public Map<String,Object> metadata(String environment) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        if (registeredPackageId != null) result.put("packageId", registeredPackageId);
        result.put("environment", environment); result.put("environmentOrigin", environmentOrigin);
        result.put("configurationIdentity", identity); result.put("manifestDigest", manifestDigest);
        result.put("globalDigest", globalDigest); result.put("origins", origins);
        result.put("engineVersion", att.Version.PRODUCT); result.put("engineRevision", att.Version.GIT_COMMIT);
        result.put("schemaRegistryDigest", att.validation.SchemaFiles.registryDigest());
        Map<String,String> paths = new LinkedHashMap<String,String>();
        for (Map.Entry<String,String> field : origins.entrySet()) paths.put(field.getKey(),
                "package".equals(field.getValue()) ? "package.yaml" : "request".equals(field.getValue()) ? "request.environment"
                        : "context".equals(field.getValue()) ? "Context.environment"
                        : "runtime-default".equals(field.getValue()) ? "" : "config/config.yaml");
        result.put("authoredPaths", Collections.unmodifiableMap(paths));
        return Collections.unmodifiableMap(result);
    }
}
