package att.load;

import att.console.ExecutionConsoleFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight public diagnostic: no stack traces, raw context or filesystem locations. */
final class LoadFailureDiagnostic {
    private static final Pattern PROTECTED = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\\\"]+"
                    + "|\\b(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT)[ \\t]+/[^\\s,;<>]*");
    private static final Pattern QUOTED_ABSOLUTE = Pattern.compile(
            "[\\\"'](?:(?:[A-Za-z]:[\\\\/]|\\\\\\\\|/)[^\\\"'\\r\\n]*)[\\\"']");
    private static final Pattern ABSOLUTE_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_/:.$<\\\\])(?:[A-Za-z]:[\\\\/]|\\\\\\\\|/)[^\\s,;<>\\\"']+");
    private LoadFailureDiagnostic() { }
    static String safeCode(String value) {
        // Known-safe codes avoid SecretProtection and diagnostic masking on scheduler hot paths.
        if (value == null) return "";
        if (value.length() > 128) return safe(value);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == ':' || c == '-')) return safe(value);
        }
        return value;
    }
    static String safe(String value) {
        if (value == null) return "";
        String text = att.security.SecretProtection.text(value);
        // HTTP request targets and URLs are diagnostic content, not host filesystem paths.
        List<String> protectedText = new ArrayList<String>();
        Matcher matcher = PROTECTED.matcher(text);
        StringBuffer masked = new StringBuffer();
        while (matcher.find()) {
            String token = "\u0001ATT_DIAGNOSTIC_" + protectedText.size() + "\u0002";
            protectedText.add(matcher.group());
            matcher.appendReplacement(masked, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(masked);
        text = QUOTED_ABSOLUTE.matcher(masked.toString()).replaceAll("[path]");
        text = ABSOLUTE_TOKEN.matcher(text).replaceAll("[path]");
        for (int i = 0; i < protectedText.size(); i++)
            text = text.replace("\u0001ATT_DIAGNOSTIC_" + i + "\u0002", protectedText.get(i));
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
