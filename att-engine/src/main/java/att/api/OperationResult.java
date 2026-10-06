package att.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Format-independent canonical outcome shared by engine operations. */
public class OperationResult {
    private final String jobId, status; private final int exitCode; private final long durationMs;
    private final List<Map<String,Object>> diagnostics; private final Map<String,String> paths; private final Map<String,Object> summary;
    public OperationResult(String jobId, String status, int exitCode, long durationMs,
                           List<Map<String,Object>> diagnostics, Map<String,String> paths, Map<String,Object> summary) {
        this.jobId=jobId; this.status=status; this.exitCode=exitCode; this.durationMs=durationMs;
        List<Map<String,Object>> copied = new ArrayList<Map<String,Object>>();
        if (diagnostics != null) for (Map<String,Object> diagnostic : diagnostics) copied.add(Collections.unmodifiableMap(new LinkedHashMap<String,Object>(diagnostic)));
        this.diagnostics=Collections.unmodifiableList(copied);
        this.paths=Collections.unmodifiableMap(new LinkedHashMap<String,String>(paths == null ? Collections.<String,String>emptyMap() : paths));
        this.summary=Collections.unmodifiableMap(new LinkedHashMap<String,Object>(summary == null ? Collections.<String,Object>emptyMap() : summary));
    }
    public String jobId() { return jobId; } public String status() { return status; } public int exitCode() { return exitCode; } public long durationMs() { return durationMs; }
    public List<Map<String,Object>> diagnostics() { return diagnostics; } public Map<String,String> paths() { return paths; } public Map<String,Object> summary() { return summary; }
    public Map<String,Object> toMap() {
        Map<String,Object> value=new LinkedHashMap<String,Object>(); value.put("jobId",jobId); value.put("status",status); value.put("exitCode",exitCode); value.put("durationMs",durationMs);
        value.put("diagnostics",diagnostics); value.put("paths",paths); value.put("summary",summary); return value;
    }
    static String display(Path value, Path root) { return value == null ? null : value.toAbsolutePath().normalize().toString(); }
}
