package att.template;

import att.exec.ToolExecutionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public collector projection; exception evidence may contain private execution inputs. */
final class CollectorExceptionEvidence {
    static final int TEXT_LIMIT = 1024;
    private static final String[] FIELDS = {
        "id", "type", "name", "implementation", "status", "category", "exitCode",
        "inputOmitted", "evidenceTruncated", "messageTruncated",
        "durationMs", "timeoutMs", "groupId", "toolKey", "sshHelper", "instance",
        "host", "sshPort", "sshTransport", "selectionStrategy", "selectionSource",
        "httpHelper", "mqHelper", "dbHelper", "reasonCode", "statusCode",
        "stdoutBytes", "stderrBytes", "stdoutTruncated", "stderrTruncated",
        "stdoutArtifactTruncated", "stderrArtifactTruncated", "stderr"
    };

    private CollectorExceptionEvidence() {}

    static ToolExecutionException project(ToolExecutionException failure) {
        Map<String, Object> source = failure.evidence();
        if (source == null) source = Collections.emptyMap();
        List<String> privateValues = new ArrayList<String>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        collectInputValues(source.get("input"), privateValues, visited);
        privateValues.sort((left, right) -> Integer.compare(right.length(), left.length()));
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        for (String field : FIELDS) {
            Object value = source.get(field);
            if (value instanceof String) {
                evidence.put(field, text((String) value, privateValues));
                if (((String) value).length() > TEXT_LIMIT) {
                    evidence.put(field + "Truncated", Boolean.TRUE);
                    evidence.put("evidenceTruncated", Boolean.TRUE);
                }
            } else if (value instanceof Number || value instanceof Boolean) {
                evidence.put(field, value);
            }
        }
        if (source.containsKey("input")) evidence.put("inputOmitted", Boolean.TRUE);
        String message = text(failure.getMessage(), privateValues);
        String category = text(failure.category(), privateValues);
        evidence.put("category", category);
        evidence.put("message", message);
        if (failure.getMessage() != null && failure.getMessage().length() > TEXT_LIMIT) {
            evidence.put("messageTruncated", Boolean.TRUE);
            evidence.put("evidenceTruncated", Boolean.TRUE);
        }
        // Do not let diagnostic traversal republish details from the private cause chain.
        return new ToolExecutionException(category, message, evidence, failure.exitCode(), null);
    }

    private static void collectInputValues(Object value, List<String> values, Set<Object> visited) {
        if (value instanceof String) {
            if (!((String) value).isEmpty()) values.add((String) value);
        } else if (value instanceof Map && visited.add(value)) {
            for (Object nested : ((Map<?, ?>) value).values()) collectInputValues(nested, values, visited);
        } else if (value instanceof Iterable && visited.add(value)) {
            for (Object nested : (Iterable<?>) value) collectInputValues(nested, values, visited);
        }
    }

    private static String text(String value, List<String> privateValues) {
        if (value == null) return "";
        String safe = value;
        for (String privateValue : privateValues) safe = safe.replace(privateValue, "[REDACTED_SECRET]");
        if (safe.length() > TEXT_LIMIT) safe = safe.substring(0, TEXT_LIMIT - 14) + "...[TRUNCATED]";
        return safe;
    }
}
