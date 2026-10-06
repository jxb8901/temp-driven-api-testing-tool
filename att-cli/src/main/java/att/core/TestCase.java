/* Author: Jeffrey + ChatGPT */
package att.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One V2 testcase row with a workbook-and-sheet-qualified Case ID. */
public final class TestCase {
    private final int rowNumber;
    private final String groupId;
    private final String workbookId;
    private final String sheetName;
    private final String rowCaseId;
    private final String caseId;
    private final List<String> tags;
    private final Map<String, Object> caseData;
    private final Map<String, StageCaseData> stages;
    private final String invalidReason;

    public TestCase(int rowNumber, String groupId, String sheetName, String rowCaseId, List<String> tags,
                    Map<String, Object> caseData, Map<String, StageCaseData> stages, String invalidReason) {
        this(rowNumber, "", groupId, sheetName, rowCaseId, tags, caseData, stages, invalidReason);
    }

    public TestCase(int rowNumber, String workbookId, String groupId, String sheetName, String rowCaseId, List<String> tags,
                    Map<String, Object> caseData, Map<String, StageCaseData> stages, String invalidReason) {
        this.rowNumber = rowNumber;
        this.workbookId = workbookId;
        this.groupId = groupId;
        this.sheetName = sheetName;
        this.rowCaseId = rowCaseId;
        this.caseId = workbookId == null || workbookId.isEmpty() ? groupId + "." + rowCaseId : workbookId + "." + groupId + "." + rowCaseId;
        this.tags = tags == null ? Collections.<String>emptyList() : new ArrayList<String>(tags);
        this.caseData = caseData == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(caseData);
        this.stages = stages == null ? Collections.<String, StageCaseData>emptyMap() : new LinkedHashMap<String, StageCaseData>(stages);
        this.invalidReason = invalidReason;
    }

    public boolean valid() { return invalidReason == null || invalidReason.trim().isEmpty(); }
    public boolean enabled() { return true; }
    public int rowNumber() { return rowNumber; }
    public String groupId() { return groupId; }
    public String workbookId() { return workbookId; }
    public String sheetName() { return sheetName; }
    public String rowCaseId() { return rowCaseId; }
    public String caseId() { return caseId; }
    public String caseName() { Object value = caseData.get("caseName"); return value == null ? caseId : String.valueOf(value); }
    public List<String> tags() { return Collections.unmodifiableList(tags); }
    public Map<String, Object> caseData() { return Collections.unmodifiableMap(caseData); }
    public Map<String, StageCaseData> stages() { return Collections.unmodifiableMap(stages); }
    public StageCaseData stage(String key) { return stages.get(key); }
    public String invalidReason() { return invalidReason; }
}
