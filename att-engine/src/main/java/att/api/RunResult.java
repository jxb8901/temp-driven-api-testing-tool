package att.api;

import java.util.List;
import java.util.Map;

/** Typed canonical result for Run operations. */
public final class RunResult extends OperationResult {
    public RunResult(String jobId, String status, int exitCode, long durationMs,
                         List<Map<String,Object>> diagnostics, Map<String,String> paths, Map<String,Object> summary) {
        super(jobId, status, exitCode, durationMs, diagnostics, paths, summary);
    }
}
