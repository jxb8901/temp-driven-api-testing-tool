package att.server.api;

import java.util.Map;

/** Safe typed defaults for a selected Template, Flow, or Tool Debug form. */
public final class DebugForm {
    public ResourceInspection.Resource resource;
    public Map<String, Object> target;
    public Map<String, Object> input;
    public Boolean redacted;
    public String requestId;
}
