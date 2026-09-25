/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Executor-neutral result at the operation boundary.
 *
 * <p>Executors keep their native invocation contracts, but adapt the
 * observable part of that invocation to this result before the Action runner
 * publishes it.  Action status, retry policy, and attempt history remain
 * owned by the Action runner.  The maps intentionally retain their native
 * value types; the common boundary is not a process-like string conversion
 * layer.</p>
 */
public final class ActionExecutionResult {
    private final Object result;
    private final Map<String, Object> evidence;
    private final Map<String, Object> diagnostic;
    private final Map<String, Object> outputMetadata;
    private final boolean executionSuccess;
    private final long durationMs;

    public ActionExecutionResult(Object result, Map<String, Object> evidence, boolean success) {
        this(result, evidence, success, null, durationFromEvidence(evidence));
    }

    public ActionExecutionResult(Object result, Map<String, Object> evidence, boolean success,
                                 Map<String, Object> diagnostic,
                                 long durationMs) {
        this(result, evidence, success, diagnostic, durationMs, Collections.<String, Object>emptyMap());
    }

    public ActionExecutionResult(Object result, Map<String, Object> evidence, boolean success,
                                 Map<String, Object> diagnostic, long durationMs,
                                 Map<String, Object> outputMetadata) {
        this.result = result;
        this.evidence = snapshotMap(evidence);
        this.diagnostic = diagnostic;
        this.outputMetadata = snapshotMap(outputMetadata);
        this.executionSuccess = success;
        this.durationMs = durationMs;
    }

    public Object result() { return result; }
    public Map<String, Object> evidence() { return evidence; }
    public Map<String, Object> diagnostic() { return diagnostic; }
    /** Action-level metadata published beside, rather than inside, result. */
    public Map<String, Object> outputMetadata() { return outputMetadata; }
    public boolean executionSuccess() { return executionSuccess; }
    /** @deprecated Use {@link #executionSuccess()} for the operation boundary. */
    @Deprecated public boolean success() { return executionSuccess; }
    public long durationMs() { return durationMs; }

    /** Creates a stable common evidence map with one invocation entry. */
    public static Map<String, Object> evidence(String kind, Map<String, Object> helperEvidence) {
        if (kind == null || kind.trim().isEmpty()) throw new IllegalArgumentException("Action evidence kind must not be blank");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> kindEvidence = new LinkedHashMap<String, Object>();
        List<Object> invocations = new ArrayList<Object>();
        invocations.add(snapshot(helperEvidence == null
                ? new LinkedHashMap<String, Object>() : helperEvidence));
        kindEvidence.put("invocations", invocations);
        result.put(kind, kindEvidence);
        return result;
    }

    /** Merges common evidence without changing the stable kind cardinality. */
    @SuppressWarnings("unchecked")
    public static void mergeEvidence(Map<String, Object> target, Map<String, Object> additions) {
        if (target == null || additions == null || additions.isEmpty()) return;
        for (Map.Entry<String, Object> entry : additions.entrySet()) {
            String kind = entry.getKey();
            Object value = entry.getValue();
            if ("collectors".equals(kind)) {
                Map<String, Object> current = target.get(kind) instanceof Map
                        ? (Map<String, Object>) target.get(kind) : new LinkedHashMap<String, Object>();
                if (value instanceof Map) current.putAll((Map<String, Object>) value);
                target.put(kind, current);
                continue;
            }
            Map<String, Object> current = target.get(kind) instanceof Map
                    ? (Map<String, Object>) target.get(kind) : new LinkedHashMap<String, Object>();
            List<Object> invocations = current.get("invocations") instanceof List
                    ? (List<Object>) current.get("invocations") : new ArrayList<Object>();
            List<Object> incoming = value instanceof Map && ((Map<?, ?>) value).get("invocations") instanceof List
                    ? (List<Object>) ((Map<?, ?>) value).get("invocations") : Collections.singletonList(value);
            for (Object item : incoming) if (!containsEquivalent(invocations, item)) invocations.add(item);
            current.put("invocations", invocations);
            target.put(kind, current);
        }
    }

    private static long durationFromEvidence(Map<String, Object> evidence) {
        if (evidence == null) return -1L;
        return durationFromValue(evidence);
    }

    @SuppressWarnings("unchecked")
    private static long durationFromValue(Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Object duration = map.get("durationMs");
            if (duration instanceof Number) return ((Number) duration).longValue();
            if (duration != null) {
                try { return Long.parseLong(String.valueOf(duration)); }
                catch (NumberFormatException ignored) { /* preserve unknown timing */ }
            }
            Object invocations = map.get("invocations");
            if (invocations instanceof List) {
                for (Object item : (List<Object>) invocations) {
                    long nested = durationFromValue(item);
                    if (nested >= 0L) return nested;
                }
            }
            for (Object item : map.values()) {
                long nested = durationFromValue(item);
                if (nested >= 0L) return nested;
            }
        } else if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                long nested = durationFromValue(item);
                if (nested >= 0L) return nested;
            }
        }
        return -1L;
    }

    private static boolean containsEquivalent(List<Object> values, Object candidate) {
        for (Object value : values) {
            if (value == candidate) return true;
            if (value instanceof Map && candidate instanceof Map) {
                Object existingId = ((Map<?, ?>) value).get("id");
                Object candidateId = ((Map<?, ?>) candidate).get("id");
                if (existingId != null && existingId.equals(candidateId)) return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshotMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>() : (Map<String, Object>) snapshot(source);
    }

    @SuppressWarnings("unchecked")
    private static Object snapshot(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(String.valueOf(entry.getKey()), snapshot(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (List<Object>) value) copy.add(snapshot(item));
            return copy;
        }
        return value;
    }
}
