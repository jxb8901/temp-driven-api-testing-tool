package att.server.api;

import java.util.List;
import java.util.Map;

/** Safe response for an in-memory, principal-bound Debug preview draft. */
public final class DebugDraft {
    public String draftId;
    public String packageId;
    public String environment;
    public Map<String, Object> target;
    public Map<String, Object> preview;
    public String previewYaml;
    public Boolean redacted;
    public List<ResourceInspection.Diagnostic> diagnostics;
    public String expiresAt;
    public String requestId;
}
