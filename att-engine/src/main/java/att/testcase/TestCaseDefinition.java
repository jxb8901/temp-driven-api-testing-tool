package att.testcase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable authored Test Case Definition; it does not represent a selected or executed Case. */
public final class TestCaseDefinition {
    private final String id;
    private final String name;
    private final String description;
    private final String flowId;
    private final String caseIdExpression;
    private final String dataSourceType;
    private final List<String> tags;
    private final Map<String,Object> directInputs;
    private final Map<String,Object> inputProjection;
    private final List<SourceRecord> records;
    private final Map<String,Object> report;
    private final Map<String,Object> extensions;
    private final Map<String,Object> dataSourceFields;

    TestCaseDefinition(String id, String name, String description, String flowId, String caseIdExpression,
                       String dataSourceType, List<String> tags, Map<String,Object> directInputs,
                       Map<String,Object> inputProjection, List<SourceRecord> records,
                       Map<String,Object> report, Map<String,Object> extensions,
                       Map<String,Object> dataSourceFields) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.flowId = flowId;
        this.caseIdExpression = caseIdExpression;
        this.dataSourceType = dataSourceType;
        this.tags = Collections.unmodifiableList(new ArrayList<String>(tags));
        this.directInputs = directInputs == null ? null : TestCaseValues.immutableObject(directInputs, "inputs");
        this.inputProjection = inputProjection == null ? null : TestCaseValues.immutableObject(inputProjection, "inputs");
        this.records = Collections.unmodifiableList(new ArrayList<SourceRecord>(records));
        this.report = report == null ? null : TestCaseValues.immutableObject(report, "report");
        this.extensions = extensions == null ? Collections.<String,Object>emptyMap()
                : TestCaseValues.immutableObject(extensions, "extensions");
        this.dataSourceFields = dataSourceFields == null ? Collections.<String,Object>emptyMap()
                : TestCaseValues.immutableObject(dataSourceFields, "dataSource");
    }

    public String schemaVersion() { return "att-testcase/v1"; }
    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String flowId() { return flowId; }
    public String caseIdExpression() { return caseIdExpression; }
    public String dataSourceType() { return dataSourceType; }
    public List<String> tags() { return tags; }
    public Map<String,Object> directInputs() { return directInputs; }
    public Map<String,Object> inputProjection() { return inputProjection; }
    public List<SourceRecord> records() { return records; }
    public Map<String,Object> report() { return report; }
    public Map<String,Object> extensions() { return extensions; }
    public Map<String,Object> dataSourceFields() { return dataSourceFields; }

    public Map<String,Object> toMap() {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("kind", "TestCaseDefinition");
        result.put("schemaVersion", schemaVersion());
        result.put("id", id);
        if (name != null) result.put("name", name);
        if (description != null) result.put("description", description);
        result.put("flow", flowId);
        if (!tags.isEmpty()) result.put("tags", tags);
        if (caseIdExpression != null) result.put("caseIdExpression", caseIdExpression);
        if (report != null) result.put("report", report);
        if (directInputs != null) result.put("inputs", directInputs);
        if (dataSourceType != null) {
            Map<String,Object> source = new LinkedHashMap<String,Object>();
            source.putAll(dataSourceFields);
            source.put("type", dataSourceType);
            List<Map<String,Object>> recordValues = new ArrayList<Map<String,Object>>();
            for (SourceRecord record : records) recordValues.add(record.toMap());
            source.put("records", Collections.unmodifiableList(recordValues));
            result.put("dataSource", source);
            if (inputProjection != null) result.put("inputs", inputProjection);
        }
        result.putAll(extensions);
        return Collections.unmodifiableMap(result);
    }

    /** One authored input row. Index is zero-based and remains stable while source order is unchanged. */
    public static final class SourceRecord {
        private final String recordId;
        private final List<String> tags;
        private final Map<String,Object> data;
        private final int index;
        private final Map<String,Object> extensions;
        private final CaseSourceProvenance sourceProvenance;

        SourceRecord(String recordId, List<String> tags, Map<String,Object> data, int index,
                     Map<String,Object> extensions, CaseSourceProvenance sourceProvenance) {
            this.recordId = recordId;
            this.tags = Collections.unmodifiableList(new ArrayList<String>(tags));
            this.data = TestCaseValues.immutableObject(data, "dataSource.records[" + index + "].data");
            this.index = index;
            this.extensions = extensions == null ? Collections.<String,Object>emptyMap()
                    : TestCaseValues.immutableObject(extensions, "dataSource.records[" + index + "].extensions");
            this.sourceProvenance = sourceProvenance;
        }

        public String recordId() { return recordId; }
        public List<String> tags() { return tags; }
        public Map<String,Object> data() { return data; }
        public int index() { return index; }
        public Map<String,Object> extensions() { return extensions; }
        public CaseSourceProvenance sourceProvenance() { return sourceProvenance; }

        public Map<String,Object> toMap() {
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            result.put("recordId", recordId);
            if (!tags.isEmpty()) result.put("tags", tags);
            result.put("data", data);
            result.putAll(extensions);
            return Collections.unmodifiableMap(result);
        }
    }
}
