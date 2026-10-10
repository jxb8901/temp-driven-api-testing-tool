package att.server.api;

import java.util.Map;

/** Safe business defaults and a one-workload preview for a selected Load model. */
public final class QuickLoadForm {
    public ResourceInspection.Resource resource;
    public Map<String, Object> target;
    public String model;
    public Map<String, Object> input;
    public Map<String, Object> preview;
    public String previewYaml;
    public Boolean redacted;
    public String requestId;
}
