package att.server.api;

import java.util.List;
import java.util.Map;

/** Typed response contracts for package resource discovery and safe source inspection. */
public final class ResourceInspection {
    private ResourceInspection() { }

    public static final class Page {
        public List<Resource> items;
        public int total;
        public String nextCursor;
        public List<Diagnostic> diagnostics;
        public String requestId;
    }

    public static final class Detail {
        public Resource resource;
        public Map<String, Object> definition;
        public List<Diagnostic> diagnostics;
        public String requestId;
    }

    public static final class Source {
        public Resource resource;
        public Boolean available;
        public String reason;
        public String format;
        public String logicalPath;
        public String text;
        public Boolean redacted;
        public String requestId;
    }

    public static final class Resource {
        public String resourceId;
        public String type;
        public String logicalId;
        public String name;
        public String description;
        public List<String> tags;
        public String state;
        public Boolean sourceAvailable;
        public Map<String, Object> provenance;
        public List<Reference> references;
        public List<Reference> referencedBy;
        public List<Diagnostic> diagnostics;
    }

    public static final class Reference {
        public String type;
        public String resourceId;
        public String logicalId;
        public String name;
        public String resolution;
    }

    public static final class Diagnostic {
        public String code;
        public String summary;
        public String field;
        public String resourceId;
    }
}
