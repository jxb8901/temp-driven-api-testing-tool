package att.server.api;

import java.util.List;
import java.util.Map;

/** Safe response for a principal-bound, in-memory Quick or Advanced Load draft. */
public final class LoadDraft {
    public String draftId;
    public String packageId;
    public String environment;
    public Map<String, Object> target;
    public String model;
    public Map<String, Object> preview;
    public String previewYaml;
    public Boolean redacted;
    public List<ResourceInspection.Diagnostic> diagnostics;
    public String expiresAt;
    public String requestId;
}
