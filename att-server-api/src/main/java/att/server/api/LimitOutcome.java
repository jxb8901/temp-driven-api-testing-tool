package att.server.api;

/** Stable classification for a configured or internal capacity limit outcome. */
public enum LimitOutcome {
    EXECUTION_ERROR,
    SUBMISSION_REJECTED,
    ARCHIVE_FAILED,
    ARTIFACT_OMITTED,
    DATA_TRUNCATED,
    HISTORY_EVICTED,
    METRICS_SAMPLED
}
