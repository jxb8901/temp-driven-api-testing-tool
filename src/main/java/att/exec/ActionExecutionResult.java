/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executor-neutral result at the Action boundary.
 *
 * <p>Executors keep their native invocation contracts, but adapt the
 * observable part of that invocation to this envelope before it is published
 * by the Action runner.  The maps intentionally retain their native value
 * types; the common boundary is not a process-like string conversion layer.</p>
 */
public final class ActionExecutionResult {
    private final Object result;
    private final Map<String, Object> evidence;
    private final Map<String, Object> diagnostic;
    private final List<Map<String, Object>> attempts;
    private final boolean success;
    private final long durationMs;

    public ActionExecutionResult(Object result, Map<String, Object> evidence, boolean success) {
        this(result, evidence, success, null, Collections.<Map<String, Object>>emptyList(), durationFromEvidence(evidence));
    }

    public ActionExecutionResult(Object result, Map<String, Object> evidence, boolean success,
                                 Map<String, Object> diagnostic,
                                 List<Map<String, Object>> attempts, long durationMs) {
        this.result = result;
        this.evidence = evidence == null
                ? new LinkedHashMap<String, Object>() : evidence;
        this.diagnostic = diagnostic;
        this.attempts = attempts == null
                ? Collections.<Map<String, Object>>emptyList()
                : new ArrayList<Map<String, Object>>(attempts);
        this.success = success;
        this.durationMs = durationMs;
    }

    public Object result() { return result; }
    public Map<String, Object> evidence() { return evidence; }
    public Map<String, Object> diagnostic() { return diagnostic; }
    public List<Map<String, Object>> attempts() { return Collections.unmodifiableList(attempts); }
    public boolean success() { return success; }
    public long durationMs() { return durationMs; }

    /** Creates the common evidence map without altering the helper payload. */
    public static Map<String, Object> evidence(String kind, Map<String, Object> helperEvidence) {
        if (kind == null || kind.trim().isEmpty()) throw new IllegalArgumentException("Action evidence kind must not be blank");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(kind, helperEvidence == null
                ? new LinkedHashMap<String, Object>() : helperEvidence);
        return result;
    }

    private static long durationFromEvidence(Map<String, Object> evidence) {
        if (evidence == null) return -1L;
        for (Object value : evidence.values()) {
            if (!(value instanceof Map)) continue;
            Object duration = ((Map<?, ?>) value).get("durationMs");
            if (duration instanceof Number) return ((Number) duration).longValue();
            if (duration != null) {
                try { return Long.parseLong(String.valueOf(duration)); }
                catch (NumberFormatException ignored) { /* preserve unknown timing */ }
            }
        }
        return -1L;
    }
}
