package att.load;

import att.core.ResultStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A compact scheduler/iteration event; no Context or raw evidence is retained. */
public final class LoadEvent {
    private final String runId, model, phase, iterationId, userId;
    private final long sequence, scheduledAtEpochMs, startedAtEpochMs, completedAtEpochMs, schedulerLagMs, latencyMs;
    private final boolean scheduled, started, completed, dropped;
    private final ResultStatus status;

    private LoadEvent(String runId, String model, String phase, String iterationId, String userId, long sequence,
                      long scheduledAtEpochMs, long startedAtEpochMs, long completedAtEpochMs, long schedulerLagMs,
                      long latencyMs, boolean scheduled, boolean started, boolean completed, boolean dropped,
                      ResultStatus status) {
        this.runId = runId; this.model = model; this.phase = phase; this.iterationId = iterationId; this.userId = userId;
        this.sequence = sequence; this.scheduledAtEpochMs = scheduledAtEpochMs; this.startedAtEpochMs = startedAtEpochMs;
        this.completedAtEpochMs = completedAtEpochMs; this.schedulerLagMs = schedulerLagMs; this.latencyMs = latencyMs;
        this.scheduled = scheduled; this.started = started; this.completed = completed; this.dropped = dropped; this.status = status;
    }

    public static LoadEvent completed(String runId, String model, String phase, String iterationId, String userId,
                                      long sequence, long scheduledAt, long startedAt, long completedAt, ResultStatus status) {
        return new LoadEvent(runId, model, phase, iterationId, userId, sequence, scheduledAt, startedAt, completedAt,
                Math.max(0L, startedAt - scheduledAt), Math.max(0L, completedAt - startedAt), true, true, true, false, status);
    }

    public static LoadEvent dropped(String runId, String model, String phase, String iterationId, long sequence,
                                    long scheduledAt, long observedAt) {
        return new LoadEvent(runId, model, phase, iterationId, null, sequence, scheduledAt, 0L, 0L,
                Math.max(0L, observedAt - scheduledAt), 0L, true, false, false, true, null);
    }

    public String runId() { return runId; }
    public String model() { return model; }
    public String phase() { return phase; }
    public String iterationId() { return iterationId; }
    public String userId() { return userId; }
    public long sequence() { return sequence; }
    public long scheduledAtEpochMs() { return scheduledAtEpochMs; }
    public long startedAtEpochMs() { return startedAtEpochMs; }
    public long completedAtEpochMs() { return completedAtEpochMs; }
    public long schedulerLagMs() { return schedulerLagMs; }
    public long latencyMs() { return latencyMs; }
    public boolean scheduled() { return scheduled; }
    public boolean started() { return started; }
    public boolean completed() { return completed; }
    public boolean dropped() { return dropped; }
    public ResultStatus status() { return status; }
    public boolean success() { return status == ResultStatus.PASS; }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runId", runId); result.put("model", model); result.put("phase", phase); result.put("iterationId", iterationId);
        if (userId != null) result.put("userId", userId);
        result.put("sequence", sequence); result.put("scheduledAt", Instant.ofEpochMilli(scheduledAtEpochMs).toString());
        if (started) result.put("startedAt", Instant.ofEpochMilli(startedAtEpochMs).toString());
        if (completed) result.put("completedAt", Instant.ofEpochMilli(completedAtEpochMs).toString());
        result.put("schedulerLagMs", schedulerLagMs); result.put("latencyMs", latencyMs);
        result.put("scheduled", scheduled); result.put("started", started); result.put("completed", completed);
        result.put("dropped", dropped); if (status != null) result.put("status", status.name());
        return result;
    }
}
