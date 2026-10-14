package att.server.api;

import java.util.Map;
import java.util.List;

/** Safe model-specific policy defaults for the Advanced Load builder. */
public final class LoadPolicyForm {
    public String model;
    public Map<String, Object> policy;
    public String previewYaml;
    public Boolean redacted;
    public List<Template> templates;
    public Boolean templateCatalogTruncated;
    public String requestId;

    public static final class Template {
        public String id;
        public String name;
        public String model;
        public Boolean bundled;
    }
}
