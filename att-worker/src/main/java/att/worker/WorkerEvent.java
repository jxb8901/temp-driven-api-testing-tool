package att.worker;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable JSON Lines event types emitted by att-worker/v1. */
public final class WorkerEvent {
    public enum Type { STATUS, LOG, PROGRESS, DIAGNOSTIC, RESULT }
    private final String type, jobId;
    private final Map<String,Object> data;
    public WorkerEvent(Type type, String jobId, Map<String,Object> data) { this.type=type.name(); this.jobId=jobId; this.data=data; }
    public Map<String,Object> toMap() { Map<String,Object> value=new LinkedHashMap<String,Object>(); value.put("type",type); if(jobId!=null)value.put("jobId",jobId); if(data!=null)value.putAll(data); return value; }
}
