package att.api;

import att.core.IdentifierValidator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable source identity for a retry of failed Cases from a Native Suite RUN. */
public final class NativeSuiteRetryLineage {
    public static final int MAX_RETRY_ATTEMPT_NUMBER = 1000;
    private static final Set<String> FIELDS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "sourceJobId", "sourceRunId", "sourceManifestSha256", "sourceRevisionDigest",
            "sourceSelectedCaseCount", "retryAttemptNumber", "retryCaseCount",
            "failedQualifiedCaseIds", "retryExecutionCount", "retrySkippedCaseCount")));
    private final String sourceJobId;
    private final String sourceRunId;
    private final String sourceManifestSha256;
    private final String sourceRevisionDigest;
    private final int sourceSelectedCaseCount;
    private final int retryAttemptNumber;
    private final List<String> failedQualifiedCaseIds;

    public NativeSuiteRetryLineage(String sourceJobId, String sourceRunId, String sourceManifestSha256,
            String sourceRevisionDigest, int sourceSelectedCaseCount, int retryAttemptNumber,
            List<String> failedQualifiedCaseIds) {
        if (sourceJobId != null && !sourceJobId.matches("J[A-F0-9]{16}"))
            throw new IllegalArgumentException("Invalid Native Suite retry source Job ID");
        this.sourceRunId = IdentifierValidator.runId(sourceRunId);
        if (sourceManifestSha256 == null || !sourceManifestSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid Native Suite retry source manifest digest");
        if (sourceRevisionDigest == null || !sourceRevisionDigest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid Native Suite retry source revision digest");
        if (sourceSelectedCaseCount < 1 || retryAttemptNumber < 1 || retryAttemptNumber > MAX_RETRY_ATTEMPT_NUMBER)
            throw new IllegalArgumentException("Invalid Native Suite retry counts");
        this.failedQualifiedCaseIds = copyIds(failedQualifiedCaseIds);
        if (this.failedQualifiedCaseIds.isEmpty() || this.failedQualifiedCaseIds.size() > sourceSelectedCaseCount)
            throw new IllegalArgumentException("Native Suite retry IDs must be a nonempty subset of the source selection");
        this.sourceJobId = sourceJobId;
        this.sourceManifestSha256 = sourceManifestSha256;
        this.sourceRevisionDigest = sourceRevisionDigest;
        this.sourceSelectedCaseCount = sourceSelectedCaseCount;
        this.retryAttemptNumber = retryAttemptNumber;
    }

    private static List<String> copyIds(List<String> values) {
        if (values == null) throw new IllegalArgumentException("Native Suite retry IDs are required");
        Set<String> seen = new LinkedHashSet<String>();
        List<String> copy = new ArrayList<String>(values.size());
        for (String value : values) {
            if (value == null || value.length() > 255
                    || !value.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.[A-Za-z0-9_-]{1,128}"))
                throw new IllegalArgumentException("Invalid Native Suite retry Qualified Case ID");
            if (!seen.add(value)) throw new IllegalArgumentException("Duplicate Native Suite retry Qualified Case ID: " + value);
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    public String sourceJobId() { return sourceJobId; }
    public String sourceRunId() { return sourceRunId; }
    public String sourceManifestSha256() { return sourceManifestSha256; }
    public String sourceRevisionDigest() { return sourceRevisionDigest; }
    public int sourceSelectedCaseCount() { return sourceSelectedCaseCount; }
    public int retryAttemptNumber() { return retryAttemptNumber; }
    public List<String> failedQualifiedCaseIds() { return failedQualifiedCaseIds; }

    /** Stable request/spec representation. Runtime attempt counts are added by the Engine manifest. */
    public Map<String,Object> toMap() {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("sourceJobId", sourceJobId);
        result.put("sourceRunId", sourceRunId);
        result.put("sourceManifestSha256", sourceManifestSha256);
        result.put("sourceRevisionDigest", sourceRevisionDigest);
        result.put("sourceSelectedCaseCount", Integer.valueOf(sourceSelectedCaseCount));
        result.put("retryAttemptNumber", Integer.valueOf(retryAttemptNumber));
        result.put("retryCaseCount", Integer.valueOf(failedQualifiedCaseIds.size()));
        result.put("failedQualifiedCaseIds", failedQualifiedCaseIds);
        return Collections.unmodifiableMap(result);
    }

    public Map<String,Object> toManifestMap(long retryExecutionCount, long retrySkippedCaseCount) {
        if (retryExecutionCount < 0 || retryExecutionCount > failedQualifiedCaseIds.size()
                || retrySkippedCaseCount < 0 || retrySkippedCaseCount > failedQualifiedCaseIds.size())
            throw new IllegalArgumentException("Invalid Native Suite retry execution counts");
        if (retryExecutionCount + retrySkippedCaseCount > failedQualifiedCaseIds.size())
            throw new IllegalArgumentException("Native Suite retry execution counts exceed the frozen retry selection");
        Map<String,Object> result = new LinkedHashMap<String,Object>(toMap());
        result.put("retryExecutionCount", Long.valueOf(retryExecutionCount));
        result.put("retrySkippedCaseCount", Long.valueOf(retrySkippedCaseCount));
        return Collections.unmodifiableMap(result);
    }

    public static NativeSuiteRetryLineage fromMap(Map<?,?> value) {
        if (value == null) throw new IllegalArgumentException("Native Suite retry lineage is required");
        for (Object key : value.keySet()) if (!(key instanceof String) || !FIELDS.contains(key))
            throw new IllegalArgumentException("Unknown Native Suite retry lineage field");
        Object rawIds = value.get("failedQualifiedCaseIds");
        if (!(rawIds instanceof List<?>)) throw new IllegalArgumentException("Native Suite retry IDs are required");
        List<String> ids = new ArrayList<String>();
        for (Object id : (List<?>) rawIds) {
            if (!(id instanceof String)) throw new IllegalArgumentException("Invalid Native Suite retry Qualified Case ID");
            ids.add((String) id);
        }
        String sourceJobId = value.get("sourceJobId") == null ? null : String.valueOf(value.get("sourceJobId"));
        String revision = value.get("sourceRevisionDigest") == null ? null : String.valueOf(value.get("sourceRevisionDigest"));
        NativeSuiteRetryLineage result = new NativeSuiteRetryLineage(sourceJobId,
                string(value, "sourceRunId"), string(value, "sourceManifestSha256"), revision,
                integer(value, "sourceSelectedCaseCount"), integer(value, "retryAttemptNumber"), ids);
        Object retryCaseCount = value.get("retryCaseCount");
        if (retryCaseCount != null && (!(retryCaseCount instanceof Number)
                || ((Number) retryCaseCount).intValue() != result.failedQualifiedCaseIds.size()))
            throw new IllegalArgumentException("Native Suite retry count does not match its Qualified Case IDs");
        for (String name : new String[] {"retryExecutionCount", "retrySkippedCaseCount"}) {
            Object count = value.get(name);
            if (count != null && (!(count instanceof Number) || ((Number) count).longValue() < 0
                    || ((Number) count).longValue() > result.failedQualifiedCaseIds.size()))
                throw new IllegalArgumentException("Invalid Native Suite retry execution counts");
        }
        return result;
    }

    private static String string(Map<?,?> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String)) throw new IllegalArgumentException("Invalid Native Suite retry " + key);
        return (String) item;
    }
    private static int integer(Map<?,?> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof Number) || ((Number) item).doubleValue() != ((Number) item).intValue())
            throw new IllegalArgumentException("Invalid Native Suite retry " + key);
        return ((Number) item).intValue();
    }
}
