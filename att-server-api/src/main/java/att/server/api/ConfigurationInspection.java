package att.server.api;

import java.util.List;
import java.util.Map;

/** Typed outer contract for safe declared, effective, and compared package configuration views. */
public final class ConfigurationInspection {
    private ConfigurationInspection() { }

    public static final class Response {
        public String view;
        public String state;
        public String schemaVersion;
        public String environment;
        public String leftEnvironment;
        public String rightEnvironment;
        public Map<String, Object> globals;
        public List<Map<String, Object>> environments;
        public List<Map<String, Object>> sections;
        public List<Map<String, Object>> fields;
        public List<Diagnostic> diagnostics;
        public String requestId;
    }

    public static final class Diagnostic {
        public String code;
        public String summary;
    }
}
