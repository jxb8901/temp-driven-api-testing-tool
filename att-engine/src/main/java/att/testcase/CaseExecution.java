package att.testcase;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One execution attempt for a ResolvedTestCase; the resolver never creates executions. */
public final class CaseExecution {
    private final String executionId;
    private final String qualifiedCaseId;
    private final String status;

    public CaseExecution(String executionId, String qualifiedCaseId, String status) {
        this.executionId = required(executionId, "executionId");
        this.qualifiedCaseId = required(qualifiedCaseId, "qualifiedCaseId");
        this.status = required(status, "status");
    }

    public String executionId() { return executionId; }
    public String qualifiedCaseId() { return qualifiedCaseId; }
    public String status() { return status; }

    public Map<String,Object> toMap() {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("kind", "CaseExecution");
        result.put("executionId", executionId);
        result.put("qualifiedCaseId", qualifiedCaseId);
        result.put("status", status);
        return Collections.unmodifiableMap(result);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
