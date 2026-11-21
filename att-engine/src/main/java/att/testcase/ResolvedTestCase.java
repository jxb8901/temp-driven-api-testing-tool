package att.testcase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable normalized plan derived from a Definition; constructing it never executes the Flow. */
public final class ResolvedTestCase {
    private final String packageId;
    private final String definitionId;
    private final String caseId;
    private final String qualifiedCaseId;
    private final String flowId;
    private final String name;
    private final String description;
    private final String sourceReference;
    private final String sourceRevision;
    private final String sourceRecordId;
    private final Integer recordIndex;
    private final Map<String,Object> data;
    private final Map<String,Object> inputs;
    private final List<String> tags;
    private final List<FlowDependency> dependencies;
    private final CaseSourceProvenance sourceProvenance;

    ResolvedTestCase(String packageId, String definitionId, String caseId, String flowId,
                     String name, String description, String sourceReference, String sourceRevision,
                     String sourceRecordId, Integer recordIndex, Map<String,Object> data,
                     Map<String,Object> inputs, List<String> tags, List<FlowDependency> dependencies,
                     CaseSourceProvenance sourceProvenance) {
        this.packageId = packageId;
        this.definitionId = definitionId;
        this.caseId = caseId;
        this.qualifiedCaseId = definitionId + "." + caseId;
        this.flowId = flowId;
        this.name = name;
        this.description = description;
        this.sourceReference = sourceReference;
        this.sourceRevision = sourceRevision;
        this.sourceRecordId = sourceRecordId;
        this.recordIndex = recordIndex;
        this.data = TestCaseValues.immutableObject(data, "data");
        this.inputs = TestCaseValues.immutableObject(inputs, "inputs");
        this.tags = Collections.unmodifiableList(new ArrayList<String>(tags));
        this.dependencies = Collections.unmodifiableList(new ArrayList<FlowDependency>(dependencies));
        if (sourceProvenance == null) throw new IllegalArgumentException("sourceProvenance is required");
        this.sourceProvenance = sourceProvenance;
    }

    public String packageId() { return packageId; }
    public String definitionId() { return definitionId; }
    public String caseId() { return caseId; }
    public String qualifiedCaseId() { return qualifiedCaseId; }
    public String flowId() { return flowId; }
    public String name() { return name; }
    public String description() { return description; }
    public String sourceReference() { return sourceReference; }
    public String sourceRevision() { return sourceRevision; }
    public String sourceRecordId() { return sourceRecordId; }
    public Integer recordIndex() { return recordIndex; }
    public Map<String,Object> data() { return data; }
    public Map<String,Object> inputs() { return inputs; }
    public List<String> tags() { return tags; }
    public List<FlowDependency> dependencies() { return dependencies; }
    public CaseSourceProvenance sourceProvenance() { return sourceProvenance; }
    public String flowRevision() { return dependencies.isEmpty() ? null : dependencies.get(0).revision(); }

    public Map<String,Object> toMap() {
        Map<String,Object> provenance = new LinkedHashMap<String,Object>();
        provenance.put("definition", sourceReference);
        provenance.put("definitionRevision", sourceRevision);
        provenance.put("dataSource", sourceProvenance.toMap());
        if (sourceRecordId != null) provenance.put("recordId", sourceRecordId);
        if (recordIndex != null) provenance.put("recordIndex", recordIndex);
        List<Map<String,Object>> dependencyMaps = new ArrayList<Map<String,Object>>();
        for (FlowDependency dependency : dependencies) dependencyMaps.add(dependency.toMap());

        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("kind", "ResolvedTestCase");
        result.put("packageId", packageId);
        result.put("definitionId", definitionId);
        result.put("caseId", caseId);
        result.put("qualifiedCaseId", qualifiedCaseId);
        result.put("flowId", flowId);
        result.put("flowRevision", flowRevision());
        if (name != null) result.put("name", name);
        if (description != null) result.put("description", description);
        result.put("data", data);
        result.put("inputs", inputs);
        result.put("tags", tags);
        result.put("provenance", Collections.unmodifiableMap(provenance));
        result.put("dependencies", Collections.unmodifiableList(dependencyMaps));
        return Collections.unmodifiableMap(result);
    }

    /** Static source dependency captured during resolution; it is not a runtime invocation. */
    public static final class FlowDependency {
        private final String id;
        private final String sourceReference;
        private final String revision;
        private final String schemaVersion;

        FlowDependency(String id, String sourceReference, String revision, String schemaVersion) {
            this.id = id;
            this.sourceReference = sourceReference;
            this.revision = revision;
            this.schemaVersion = schemaVersion;
        }

        public String id() { return id; }
        public String sourceReference() { return sourceReference; }
        public String revision() { return revision; }
        public String schemaVersion() { return schemaVersion; }

        public Map<String,Object> toMap() {
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            result.put("kind", "Flow");
            result.put("id", id);
            result.put("source", sourceReference);
            result.put("revision", revision);
            result.put("schemaVersion", schemaVersion);
            return Collections.unmodifiableMap(result);
        }
    }
}
