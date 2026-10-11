package att.server.api;

import java.util.Map;

/** Safe model-specific policy defaults for the Advanced Load builder. */
public final class LoadPolicyForm {
    public String model;
    public Map<String, Object> policy;
    public String previewYaml;
    public Boolean redacted;
    public String requestId;
}
