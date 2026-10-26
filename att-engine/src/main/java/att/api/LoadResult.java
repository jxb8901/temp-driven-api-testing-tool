package att.api;

import att.validation.Diagnostic;
import att.load.LoadRunResult;
import att.server.api.LoadTerminalResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed canonical result for Load operations. */
public final class LoadResult extends OperationResult {
    private final LoadRunResult detailedResult;
    private final Map<String,Object> configuration;

    public LoadResult(String executionId, String status, int exitCode, long durationMs,
                         List<Diagnostic> diagnostics, Map<String,String> paths, Map<String,Object> summary) {
        super(executionId, status, exitCode, durationMs, diagnostics, paths, summary);
        this.detailedResult=null;
        this.configuration=null;
    }

    public LoadResult(String executionId, String status, int exitCode, long durationMs,
                      List<Diagnostic> diagnostics, Map<String,String> paths,
                      LoadRunResult detailedResult, Map<String,Object> configuration) {
        super(executionId, status, exitCode, durationMs, diagnostics, paths, null);
        this.detailedResult=detailedResult;
        this.configuration=configuration==null?null:new LinkedHashMap<String,Object>(configuration);
    }

    /** Full report shape used by direct Engine/CLI callers for compatibility. */
    @Override public Map<String,Object> summary() {
        if(detailedResult==null)return super.summary();
        Map<String,Object> value=new LinkedHashMap<String,Object>(detailedResult.toMap());
        if(configuration!=null)value.put("configuration",configuration);
        return value;
    }

    @Override public Map<String,Object> toMap() {
        Map<String,Object> value=super.toMap();
        if(detailedResult!=null)value.put("summary",summary());
        return value;
    }

    /** Worker-only projection; it never serializes the full detailed summary. */
    public Map<String,Object> toWorkerMap() {
        if(detailedResult==null)return toMap();
        Map<String,Object> value=new LinkedHashMap<String,Object>();
        value.put("executionId",executionId()); value.put("status",status()); value.put("exitCode",exitCode());
        value.put("durationMs",durationMs());
        List<Map<String,Object>> serialized=new java.util.ArrayList<Map<String,Object>>();
        for(Diagnostic diagnostic:diagnostics())serialized.add(diagnostic.toMap());
        value.put("diagnostics",serialized);
        value.put("summary",detailedResult.toTerminalSummary(configuration));
        return LoadTerminalResult.projectOperationResult(value,status(),exitCode());
    }
}
