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
        public List<SourceReference> fileReferences;
        public Boolean fileReferencesTruncated;
        public String requestId;
    }

    public static final class SourceReference {
        public String referenceId;
        public String logicalPath;
        public Boolean available;
    }

    public static final class Resource {
        public String resourceId;
        public String type;
        public String logicalId;
        public String name;
        public String description;
        public List<String> tags;
        public String state;
        public String metadataState;
        public ExecutionReadiness executionReadiness;
        public Boolean sourceAvailable;
        public String sourceReason;
        public Boolean redactionEnabled;
        public Map<String, Object> provenance;
        public List<Reference> references;
        public List<Reference> referencedBy;
        public List<Diagnostic> diagnostics;
    }

    public static final class ExecutionReadiness {
        public String state;
        public String environment;
        public List<Diagnostic> diagnostics;
        public Boolean diagnosticsTruncated;
    }

    public static final class Reference {
        public String type;
        public String resourceId;
        public String logicalId;
        public String name;
        public String targetKind;
        public String resolution;
        public Map<String, Object> callSite;
    }

    public static final class Diagnostic {
        public String severity;
        public String state;
        public String environment;
        public String helperType;
        public String helperId;
        public String descriptor;
        public String environmentVariable;
        public String remediation;

        public String code;
        public String summary;
        public String field;
        public String resourceId;
        public String limitName;
        public Long configuredLimit;
        public Long limit;
        public Long observed;
        public Long actual;
        public Long usage;
        public String scope;
        public String outcome;
        public String stage;
        public String remediationHint;
        public String artifactRef;
    }
}
