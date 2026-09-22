package att.load;

import att.validation.Diagnostic;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight retained-iteration evidence reference; it never owns a Context. */
public final class EvidenceRef {
    private final Path workspace;
    private final Path caseLog;
    private final Diagnostic diagnostic;

    public EvidenceRef(Path workspace, Path caseLog, Diagnostic diagnostic) {
        this.workspace = workspace;
        this.caseLog = caseLog;
        this.diagnostic = diagnostic;
    }

    public Path workspace() { return workspace; }
    public Path caseLog() { return caseLog; }
    public Diagnostic diagnostic() { return diagnostic; }

    public Map<String, Object> toMap() { return toMap(null); }

    public Map<String, Object> toMap(Path base) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        putPath(result, "workspace", workspace, base);
        putPath(result, "caseLog", caseLog, base);
        if (diagnostic != null) result.put("diagnostic", diagnostic.toMap());
        return result;
    }

    private void putPath(Map<String, Object> target, String key, Path value, Path base) {
        if (value == null) return;
        Path normalized = value.toAbsolutePath().normalize();
        if (base != null) {
            Path normalizedBase = base.toAbsolutePath().normalize();
            if (normalized.startsWith(normalizedBase)) {
                target.put(key, normalizedBase.relativize(normalized).toString().replace('\\', '/'));
                return;
            }
        }
        target.put(key, normalized.toString());
    }
}
