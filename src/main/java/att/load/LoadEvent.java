package att.load;

import att.core.ResultStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A compact scheduler/iteration event; only a bounded reference to retained evidence is carried. */
public final class LoadEvent {
    private final String runId, workloadId, targetType, targetId, model, phase, iterationId, userId;
    private final long sequence, scheduledAtEpochMs, startedAtEpochMs, completedAtEpochMs, schedulerLagMs, latencyMs;
    private final boolean scheduled, started, completed, dropped;
    private final ResultStatus status;
    private final String errorType;
    private final EvidenceRef evidence;

    private LoadEvent(String runId, String workloadId, String targetType, String targetId,
                      String model, String phase, String iterationId, String userId, long sequence,
                      long scheduledAtEpochMs, long startedAtEpochMs, long completedAtEpochMs, long schedulerLagMs,
                      long latencyMs, boolean scheduled, boolean started, boolean completed, boolean dropped,
                      ResultStatus status, String errorType, EvidenceRef evidence) {
        this.runId = runId; this.workloadId = workloadId; this.targetType = targetType; this.targetId = targetId;
        this.model = model; this.phase = phase; this.iterationId = iterationId; this.userId = userId;
        this.sequence = sequence; this.scheduledAtEpochMs = scheduledAtEpochMs; this.startedAtEpochMs = startedAtEpochMs;
        this.completedAtEpochMs = completedAtEpochMs; this.schedulerLagMs = schedulerLagMs; this.latencyMs = latencyMs;
        this.scheduled = scheduled; this.started = started; this.completed = completed; this.dropped = dropped; this.status = status;
        this.errorType = errorType; this.evidence = evidence;
    }

    /** Returns the same event attributed to one configured workload. */
    public LoadEvent withWorkloadId(String value) {
        return withWorkloadIdentity(value, targetType, targetId);
    }

    /** Adds the configured workload and fixed-target identity to retained event data. */
    public LoadEvent withWorkloadIdentity(String workload, String type, String target) {
        return new LoadEvent(runId, workload, type, target, model, phase, iterationId, userId, sequence, scheduledAtEpochMs, startedAtEpochMs,
                completedAtEpochMs, schedulerLagMs, latencyMs, scheduled, started, completed, dropped, status, errorType, evidence);
    }

    public static LoadEvent completed(String runId, String model, String phase, String iterationId, String userId,
                                      long sequence, long scheduledAt, long startedAt, long completedAt, ResultStatus status) {
        return completed(runId, model, phase, iterationId, userId, sequence, scheduledAt, startedAt, completedAt, status, null);
    }
    public static LoadEvent completed(String runId, String model, String phase, String iterationId, String userId,
                                      long sequence, long scheduledAt, long startedAt, long completedAt,
                                      ResultStatus status, EvidenceRef evidence) {
        return completed(runId, model, phase, iterationId, userId, sequence, scheduledAt, startedAt, completedAt, status, null, evidence);
    }
    public static LoadEvent completed(String runId, String model, String phase, String iterationId, String userId,
                                      long sequence, long scheduledAt, long startedAt, long completedAt,
                                      ResultStatus status, String errorType, EvidenceRef evidence) {
        return new LoadEvent(runId, null, null, null, model, phase, iterationId, userId, sequence, scheduledAt, startedAt, completedAt,
                Math.max(0L, startedAt - scheduledAt), Math.max(0L, completedAt - startedAt), true, true, true, false, status, errorType, evidence);
    }
    public static LoadEvent started(String runId, String model, String phase, String iterationId, String userId,
                                    long sequence, long scheduledAt, long startedAt) {
        return new LoadEvent(runId, null, null, null, model, phase, iterationId, userId, sequence, scheduledAt, startedAt, 0L,
                Math.max(0L, startedAt - scheduledAt), 0L, true, true, false, false, null, null, null);
    }
    public static LoadEvent completion(String runId, String model, String phase, String iterationId, String userId,
                                       long sequence, long scheduledAt, long startedAt, long completedAt,
                                       ResultStatus status) {
        return completion(runId, model, phase, iterationId, userId, sequence, scheduledAt, startedAt, completedAt, status, null, null);
    }
    public static LoadEvent completion(String runId, String model, String phase, String iterationId, String userId,
                                       long sequence, long scheduledAt, long startedAt, long completedAt,
                                       ResultStatus status, EvidenceRef evidence) {
        return completion(runId, model, phase, iterationId, userId, sequence, scheduledAt, startedAt, completedAt, status, null, evidence);
    }
    public static LoadEvent completion(String runId, String model, String phase, String iterationId, String userId,
                                       long sequence, long scheduledAt, long startedAt, long completedAt,
                                       ResultStatus status, String errorType, EvidenceRef evidence) {
        return new LoadEvent(runId, null, null, null, model, phase, iterationId, userId, sequence, scheduledAt, startedAt, completedAt,
                Math.max(0L, startedAt - scheduledAt), Math.max(0L, completedAt - startedAt), false, false, true, false, status, errorType, evidence);
    }
    public static LoadEvent dropped(String runId, String model, String phase, String iterationId, long sequence,
                                    long scheduledAt, long observedAt) {
        return new LoadEvent(runId, null, null, null, model, phase, iterationId, null, sequence, scheduledAt, 0L, 0L,
                Math.max(0L, observedAt - scheduledAt), 0L, true, false, false, true, null, null, null);
    }

    public String runId() { return runId; }
    public String workloadId() { return workloadId; }
    public String targetType() { return targetType; }
    public String targetId() { return targetId; }
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
    public String errorType() { return errorType; }
    public EvidenceRef evidence() { return evidence; }
    public boolean success() { return status == ResultStatus.PASS; }

    public Map<String, Object> toMap() { return toMap(null); }
    public Map<String, Object> toMap(java.nio.file.Path base) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runId", runId); if (workloadId != null) result.put("workloadId", workloadId);
        if (targetType != null) result.put("targetType", targetType);
        if (targetId != null) result.put("targetId", targetId);
        result.put("model", model); result.put("phase", phase); result.put("iterationId", iterationId);
        if (userId != null) result.put("userId", userId);
        result.put("sequence", sequence); result.put("scheduledAt", Instant.ofEpochMilli(scheduledAtEpochMs).toString());
        if (started) result.put("startedAt", Instant.ofEpochMilli(startedAtEpochMs).toString());
        if (completed) result.put("completedAt", Instant.ofEpochMilli(completedAtEpochMs).toString());
        result.put("schedulerLagMs", schedulerLagMs); result.put("latencyMs", latencyMs);
        result.put("scheduled", scheduled); result.put("started", started); result.put("completed", completed);
        result.put("dropped", dropped); if (status != null) result.put("status", status.name());
        if (errorType != null) result.put("errorType", errorType);
        if (evidence != null) result.put("evidence", evidence.toMap(base));
        return result;
    }
}
