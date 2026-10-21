package att.load;

import att.console.ExecutionConsoleFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight public diagnostic: no stack traces, raw context or filesystem locations. */
final class LoadFailureDiagnostic {
    private LoadFailureDiagnostic() { }
    static String safe(String value) {
        if(value==null)return "";
        String text=att.security.SecretProtection.text(value);
        // Public diagnostics deliberately omit host paths, including quoted paths with spaces.
        text=text.replaceAll("[\\\"'](?:[A-Za-z]:)?[/\\\\][^\\\"'\\r\\n]*[\\\"']", "[path]");
        text=text.replaceAll("(?:[A-Za-z]:)?[/\\\\][^\\s,;<>]*", "[path]");
        return ExecutionConsoleFormatter.present(text,ExecutionConsoleFormatter.Options.published(512,4));
    }
    static Map<String,Object> result(IterationResult result) {
        Map<String,Object> map=new LinkedHashMap<String,Object>();
        map.put("source","executor-result");
        map.put("code",safe(LoadSchedulerSupport.errorType(result)));
        map.put("summary",safe(result.diagnostic()==null?"Iteration returned "+result.status():result.diagnostic().summary()));
        map.put("evidenceState",result.evidenceFailureReason());
        return map;
    }
    static Map<String,Object> exception(RuntimeException failure) {
        Map<String,Object> map=new LinkedHashMap<String,Object>();
        map.put("source","scheduler-exception");map.put("code","LOAD_ITERATION_EXCEPTION");
        map.put("exceptionClass",safe(failure.getClass().getName()));
        map.put("summary",safe(failure.getMessage()));
        Throwable cause=failure.getCause();
        if(cause!=null && cause!=failure)map.put("cause",safe(cause.getClass().getName()+": "+cause.getMessage()));
        map.put("evidenceState","SCHEDULER_EXCEPTION_NO_CASE_LOG");return map;
    }
}
