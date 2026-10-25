package att.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable resolution identity shared by inspection, validation and execution. */
public final class EffectiveExecutionConfig {
    private final String identity, environmentOrigin, manifestDigest;
    private final Map<String, String> origins;
    private final java.util.List<String> diagnostics;
    EffectiveExecutionConfig(String identity, String environmentOrigin, String manifestDigest, Map<String, String> origins, java.util.List<String> diagnostics) {
        this.identity = identity; this.environmentOrigin = environmentOrigin; this.manifestDigest = manifestDigest;
        this.diagnostics = Collections.unmodifiableList(new java.util.ArrayList<String>(diagnostics));
        this.origins = Collections.unmodifiableMap(new LinkedHashMap<String, String>(origins));
    }
    public java.util.List<String> diagnostics() { return diagnostics; }
    public String identity() { return identity; }
    public String environmentOrigin() { return environmentOrigin; }
    public String manifestDigest() { return manifestDigest; }
    public Map<String, String> origins() { return origins; }
}
