package att.api;

import att.validation.Diagnostic;
import java.util.List;
import java.util.Map;

/** Typed canonical result for Load operations. */
public final class LoadResult extends OperationResult {
    public LoadResult(String executionId, String status, int exitCode, long durationMs,
                         List<Diagnostic> diagnostics, Map<String,String> paths, Map<String,Object> summary) {
        super(executionId, status, exitCode, durationMs, diagnostics, paths, summary);
    }
}
