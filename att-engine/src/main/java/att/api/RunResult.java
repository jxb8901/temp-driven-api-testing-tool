package att.api;

import att.validation.Diagnostic;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

/** Typed canonical result for Run operations. */
public final class RunResult extends OperationResult {
    private final java.util.List<java.util.Map<String, Object>> caseFailures;
    public RunResult(String executionId, String status, int exitCode, long durationMs,
                         List<Diagnostic> diagnostics, Map<String,String> paths, Map<String,Object> summary) {
        this(executionId, status, exitCode, durationMs, diagnostics, paths, summary,
                Collections.<Map<String, Object>>emptyList());
    }
    public RunResult(String executionId, String status, int exitCode, long durationMs,
                         List<Diagnostic> diagnostics, Map<String,String> paths, Map<String,Object> summary,
                         List<Map<String, Object>> caseFailures) {
        super(executionId, status, exitCode, durationMs, diagnostics, paths, summary);
        List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>();
        if (caseFailures != null) for (Map<String, Object> failure : caseFailures)
            copy.add(Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>(failure)));
        this.caseFailures = Collections.unmodifiableList(copy);
    }
    public List<Map<String, Object>> caseFailures() { return caseFailures; }
}
