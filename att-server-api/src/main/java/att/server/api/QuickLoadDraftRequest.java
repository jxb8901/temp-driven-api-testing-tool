package att.server.api;

import java.util.Map;

/** Public typed request for validating a fixed-target Quick Load draft. */
public final class QuickLoadDraftRequest {
    public String packageId;
    public String environment;
    public Map<String, Object> target;
    public String model;
    public Map<String, Object> input;
    public Map<String, Object> load;
    public Map<String, Object> execution;
}
