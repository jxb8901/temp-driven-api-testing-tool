package att.testcase;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Location and source metadata for a resolved input record. */
public final class CaseSourceProvenance {
    private final String sourceType;
    private final String sourceReference;
    private final String sheet;
    private final Integer row;
    private final Integer recordIndex;
    private final String recordId;
    private final Map<String,Object> metadata;

    public CaseSourceProvenance(String sourceType, String sourceReference, String sheet, Integer row,
                                Integer recordIndex, String recordId, Map<String,Object> metadata) {
        this.sourceType = required(sourceType, "sourceType");
        this.sourceReference = required(sourceReference, "sourceReference");
        this.sheet = optional(sheet, "sheet");
        this.row = row;
        this.recordIndex = recordIndex;
        this.recordId = optional(recordId, "recordId");
        if (row != null && row.intValue() < 1) throw new IllegalArgumentException("row must be one-based and positive");
        if (recordIndex != null && recordIndex.intValue() < 0) throw new IllegalArgumentException("recordIndex must not be negative");
        this.metadata = metadata == null ? Collections.<String,Object>emptyMap()
                : TestCaseValues.immutableObject(metadata, "sourceProvenance");
    }

    public static CaseSourceProvenance inline(String definitionReference, Integer recordIndex, String recordId) {
        return new CaseSourceProvenance("inline", definitionReference, null, null, recordIndex, recordId,
                Collections.<String,Object>emptyMap());
    }

    public String sourceType() { return sourceType; }
    public String sourceReference() { return sourceReference; }
    public String sheet() { return sheet; }
    public Integer row() { return row; }
    public Integer recordIndex() { return recordIndex; }
    public String recordId() { return recordId; }
    public Map<String,Object> metadata() { return metadata; }

    public Map<String,Object> toMap() {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("sourceType", sourceType);
        result.put("sourceReference", sourceReference);
        if (sheet != null) result.put("sheet", sheet);
        if (row != null) result.put("row", row);
        if (recordIndex != null) result.put("recordIndex", recordIndex);
        if (recordId != null) result.put("recordId", recordId);
        if (!metadata.isEmpty()) result.put("metadata", metadata);
        return Collections.unmodifiableMap(result);
    }

    private static String required(String value, String field) {
        String checked = optional(value, field);
        if (checked == null) throw new IllegalArgumentException(field + " is required");
        return checked;
    }

    private static String optional(String value, String field) {
        if (value == null) return null;
        if (value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
