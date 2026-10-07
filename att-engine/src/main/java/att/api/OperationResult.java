package att.api;

import att.validation.Diagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Format-independent canonical outcome shared by engine operations. */
public class OperationResult {
    private final String executionId, status;
    private final int exitCode;
    private final long durationMs;
    private final List<Diagnostic> diagnostics;
    private final Map<String,String> paths;
    private final Map<String,Object> summary;

    public OperationResult(String executionId, String status, int exitCode, long durationMs,
                           List<Diagnostic> diagnostics, Map<String,String> paths, Map<String,Object> summary) {
        this.executionId=executionId; this.status=status; this.exitCode=exitCode; this.durationMs=durationMs;
        this.diagnostics=Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics == null ? Collections.<Diagnostic>emptyList() : diagnostics));
        this.paths=Collections.unmodifiableMap(new LinkedHashMap<String,String>(paths == null ? Collections.<String,String>emptyMap() : paths));
        this.summary=Collections.unmodifiableMap(new LinkedHashMap<String,Object>(summary == null ? Collections.<String,Object>emptyMap() : summary));
    }
    /** ATT's run or operation identity. Worker orchestration job IDs belong to the Worker protocol. */
    public String executionId() { return executionId; }
    public String status() { return status; }
    public int exitCode() { return exitCode; }
    public long durationMs() { return durationMs; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public Map<String,String> paths() { return paths; }
    public Map<String,Object> summary() { return summary; }

    public Map<String,Object> toMap() {
        Map<String,Object> value=new LinkedHashMap<String,Object>();
        value.put("executionId",executionId); value.put("status",status); value.put("exitCode",exitCode); value.put("durationMs",durationMs);
        List<Map<String,Object>> serialized=new ArrayList<Map<String,Object>>();
        for (Diagnostic diagnostic : diagnostics) serialized.add(diagnostic.toMap());
        value.put("diagnostics",serialized); value.put("paths",paths); value.put("summary",summary); return value;
    }
    static String display(Path value, Path root) { return value == null ? null : value.toAbsolutePath().normalize().toString(); }
}
