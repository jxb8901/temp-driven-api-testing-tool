package att.template;

import att.exec.ToolExecutionException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public collector projection; exception evidence may contain private execution inputs. */
final class CollectorExceptionEvidence {
    static final int TEXT_LIMIT = 1024;
    static final int INSTANCE_LIMIT = 64;
    private static final String[] FIELDS = {
        "id", "type", "name", "implementation", "status", "category", "exitCode",
        "inputOmitted", "evidenceTruncated", "messageTruncated", "instancesTruncated", "instanceCount",
        "durationMs", "timeoutMs", "groupId", "toolKey", "sshHelper", "instance",
        "host", "sshPort", "sshTransport", "selectionStrategy", "selectionSource",
        "httpHelper", "mqHelper", "dbHelper", "reasonCode", "statusCode",
        "stdoutBytes", "stderrBytes", "stdoutTruncated", "stderrTruncated",
        "stdoutArtifactTruncated", "stderrArtifactTruncated", "stderr"
    };
    private static final String[] INSTANCE_FIELDS = {
        "instance", "host", "port", "transport", "status", "exitCode", "durationMs",
        "startedAt", "endedAt", "error", "stderr", "cleanupWarning",
        "stdoutBytes", "stderrBytes", "stdoutTruncated", "stderrTruncated",
        "stdoutArtifactTruncated", "stderrArtifactTruncated", "evidenceTruncated",
        "errorTruncated", "cleanupWarningTruncated"
    };

    private CollectorExceptionEvidence() {}

    static ToolExecutionException project(ToolExecutionException failure) {
        Map<String, Object> source = failure.evidence();
        if (source == null) source = Collections.emptyMap();
        List<String> privateValues = new ArrayList<String>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        collectInputValues(source.get("input"), privateValues, visited);
        privateValues.sort((left, right) -> Integer.compare(right.length(), left.length()));
        Map<String, Object> evidence = fields(source, FIELDS, privateValues);
        Object instances = source.get("instances");
        if (instances instanceof Map) {
            Map<?, ?> hosts = (Map<?, ?>) instances;
            Map<String, Object> projected = new LinkedHashMap<String, Object>();
            // Prefer failing hosts when the fan-out exceeds the public evidence budget.
            for (boolean failures : new boolean[] {true, false}) {
                for (Map.Entry<?, ?> entry : hosts.entrySet()) {
                    if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Map)) continue;
                    Map<?, ?> host = (Map<?, ?>) entry.getValue();
                    if (failures == "PASS".equals(host.get("status"))) continue;
                    if (projected.size() >= INSTANCE_LIMIT) break;
                    projected.put(bound((String) entry.getKey()), fields(host, INSTANCE_FIELDS, privateValues));
                }
            }
            evidence.put("instances", projected);
            if (!source.containsKey("instanceCount")) evidence.put("instanceCount", hosts.size());
            if (hosts.size() > projected.size()) {
                evidence.put("instancesTruncated", Boolean.TRUE);
                evidence.put("evidenceTruncated", Boolean.TRUE);
            }
        }
        if (source.containsKey("input")) evidence.put("inputOmitted", Boolean.TRUE);
        String message = text(failure.getMessage(), privateValues);
        String category = bound(failure.category());
        evidence.put("category", category);
        evidence.put("message", message);
        markTruncated(evidence, "message", failure.getMessage(), privateValues);
        // Do not let diagnostic traversal republish details from the private cause chain.
        return new ToolExecutionException(category, message, evidence, failure.exitCode(), null);
    }

    private static Map<String, Object> fields(Map<?, ?> source, String[] names, List<String> privateValues) {
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        for (String field : names) {
            Object value = source.get(field);
            if (value instanceof String) {
                boolean freeForm = "stderr".equals(field) || "error".equals(field) || "cleanupWarning".equals(field);
                List<String> tokens = freeForm ? privateValues : Collections.<String>emptyList();
                target.put(field, text((String) value, tokens));
                markTruncated(target, field, (String) value, tokens);
            } else if (value instanceof Number || value instanceof Boolean) {
                target.put(field, value);
            }
        }
        return target;
    }

    private static void markTruncated(Map<String, Object> target, String field, String value, List<String> tokens) {
        if (value != null && redact(value, tokens).length() > TEXT_LIMIT) {
            target.put(field + "Truncated", Boolean.TRUE);
            target.put("evidenceTruncated", Boolean.TRUE);
        }
    }

    private static void collectInputValues(Object value, List<String> values, Set<Object> visited) {
        if (value instanceof String) {
            if (!((String) value).isEmpty()) values.add((String) value);
        } else if (value instanceof DocumentValue) {
            collectInputValues(((DocumentValue) value).text(), values, visited);
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            // Binary inputs: cover decoded UTF-8, Base64, hex and Java's decimal array rendering.
            collectInputValues(new String(bytes, StandardCharsets.UTF_8), values, visited);
            collectInputValues(Base64.getEncoder().encodeToString(bytes), values, visited);
            StringBuilder hex = new StringBuilder();
            for (byte part : bytes) hex.append(String.format("%02x", part & 0xff));
            collectInputValues(hex.toString(), values, visited);
            collectInputValues(hex.toString().toUpperCase(java.util.Locale.ROOT), values, visited);
            collectInputValues(Arrays.toString(bytes), values, visited);
        } else if (value instanceof char[]) {
            collectInputValues(new String((char[]) value), values, visited);
        } else if (value instanceof Map && visited.add(value)) {
            for (Object nested : ((Map<?, ?>) value).values()) collectInputValues(nested, values, visited);
        } else if (value instanceof Iterable && visited.add(value)) {
            for (Object nested : (Iterable<?>) value) collectInputValues(nested, values, visited);
        } else if (value != null && value.getClass().isArray() && visited.add(value)) {
            for (int index = 0; index < Array.getLength(value); index++) {
                collectInputValues(Array.get(value, index), values, visited);
            }
        }
    }

    private static String redact(String value, List<String> privateValues) {
        if (value == null) return "";
        String safe = value;
        for (String privateValue : privateValues) safe = safe.replace(privateValue, "[REDACTED_SECRET]");
        return safe;
    }

    private static String bound(String value) {
        if (value == null) return "";
        return value.length() > TEXT_LIMIT ? value.substring(0, TEXT_LIMIT - 14) + "...[TRUNCATED]" : value;
    }

    private static String text(String value, List<String> privateValues) {
        return bound(redact(value, privateValues));
    }
}
