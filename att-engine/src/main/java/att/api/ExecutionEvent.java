package att.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structured execution notification shared by CLI, Worker, and service callers. */
public final class ExecutionEvent {
    public enum Type { STATUS, PROGRESS, LOG, CASE_LOG, WARNING, DEBUG }
    private final Type type;
    private final String runId, caseId, stage, action, status, message;
    private final Long durationMs;
    private final Map<String,Object> data;

    public ExecutionEvent(Type type, String runId, String caseId, String stage, String action,
                         String status, Long durationMs, String message, Map<String,Object> data) {
        if (type == null) throw new IllegalArgumentException("event type is required");
        this.type=type; this.runId=runId; this.caseId=caseId; this.stage=stage; this.action=action;
        this.status=status; this.durationMs=durationMs; this.message=message;
        this.data=Collections.unmodifiableMap(new LinkedHashMap<String,Object>(data == null
                ? Collections.<String,Object>emptyMap() : data));
    }
    public Type type() { return type; }
    public String runId() { return runId; }
    public String caseId() { return caseId; }
    public String stage() { return stage; }
    public String action() { return action; }
    public String status() { return status; }
    public Long durationMs() { return durationMs; }
    public String message() { return message; }
    public Map<String,Object> data() { return data; }
    public static ExecutionEvent log(String message) {
        String value = message == null ? "" : message;
        Type kind = value.startsWith("[RUN]") || value.startsWith("[SUITE]") || value.startsWith("[CASE]")
                || value.startsWith("[STAGE]") || value.startsWith("[ACTION]") ? Type.PROGRESS
                : value.startsWith("[ATT WARNING]") ? Type.WARNING : value.startsWith("[DEBUG]") ? Type.DEBUG : Type.LOG;
        Map<String,Object> attributes = new LinkedHashMap<String,Object>();
        String run=null, testCase=null, stage=null, action=null, status=null; Long duration=null;
        Matcher matcher=Pattern.compile("([A-Za-z][A-Za-z0-9]*)=([^\\s]+)").matcher(value);
        while(matcher.find()) {
            String key=matcher.group(1), item=matcher.group(2);
            if("runId".equals(key)||"id".equals(key)&&value.startsWith("[RUN]")) run=item;
            else if("case".equals(key)||"id".equals(key)&&value.startsWith("[CASE]")) testCase=item;
            else if("stage".equals(key)) stage=item;
            else if("action".equals(key)) action=item;
            else if("status".equals(key)) status=item;
            else if("durationMs".equals(key)) try { duration=Long.valueOf(item); } catch(NumberFormatException ignored) { }
            else attributes.put(key,item);
        }
        return new ExecutionEvent(kind,run,testCase,stage,action,status,duration,value,attributes);
    }
}
