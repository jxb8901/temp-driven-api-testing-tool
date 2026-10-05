package att.template;

import att.exec.ToolExecutionException;
import att.exec.ToolInvocationResult;
import att.exec.ActionExecutionResult;
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
    static final int MIN_TOKEN_LENGTH = 4;
    static final int INSTANCE_LIMIT = 64;
    static final int INPUT_NODE_LIMIT = 256;
    static final int TOKEN_CHARACTER_LIMIT = 8192;
    static final int BINARY_LIMIT = 128;
    static final int ARRAY_LIMIT = 64;
    static final String OMITTED_TEXT = "[REDACTED_SECRET] [FAILURE_DETAILS_OMITTED]";
    private static final String[] FIELDS = {
        "id", "type", "name", "implementation", "status", "category", "exitCode",
        "inputOmitted", "inputRedactionLimited", "failureDetailsOmitted", "evidenceTruncated", "messageTruncated", "instancesTruncated", "instanceCount",
        "durationMs", "timeoutMs", "groupId", "toolKey", "sshHelper", "instance",
        "host", "sshPort", "sshTransport", "selectionStrategy", "selectionSource",
        "httpHelper", "mqHelper", "dbHelper", "db", "helper", "helperId", "operation", "completionCode", "reasonCode", "reason", "statusCode",
        "method", "url", "urlPathOmitted", "queueManager", "physicalInstance", "port", "channel", "transport",
        "message", "error", "cleanupWarning", "timeoutSeconds", "toolTimeoutMs", "effectiveTimeoutSeconds",
        "stdoutBytes", "stderrBytes", "stdoutTruncated", "stderrTruncated",
        "stdoutArtifactTruncated", "stderrArtifactTruncated", "stderr", "stdout"
    };
    private static final String[] INSTANCE_FIELDS = {
        "instance", "host", "port", "transport", "status", "exitCode", "durationMs",
        "startedAt", "endedAt", "error", "stderr", "cleanupWarning",
        "stdoutBytes", "stderrBytes", "stdoutTruncated", "stderrTruncated",
        "stdoutArtifactTruncated", "stderrArtifactTruncated", "evidenceTruncated",
        "errorTruncated", "cleanupWarningTruncated", "failureDetailsOmitted"
    };

    private static final String[] DIAGNOSTIC_FIELDS = {
        "code", "severity", "type", "category", "field", "message", "detail", "hint",
        "status", "exitCode", "completionCode", "reasonCode", "reason", "statusCode", "sqlState", "vendorCode", "timeoutMs", "inputRedactionLimited",
        "failureDetailsOmitted", "messageTruncated", "evidenceTruncated"
    };

    private CollectorExceptionEvidence() {}

    static ToolExecutionException project(ToolExecutionException failure) {
        return project(failure, false);
    }

    static ToolExecutionException project(ToolExecutionException failure, boolean unsafeLocal) {
        Map<String, Object> source = failure.evidence();
        if (source == null) source = Collections.emptyMap();
        Redaction redaction = new Redaction(unsafeLocal);
        redaction.limited = Boolean.TRUE.equals(source.get("inputRedactionLimited"));
        redaction.captureTruncated = truncatedDetails(source);
        redaction.collect(source.get("input"));
        redaction.tokens.sort((left, right) -> Integer.compare(right.length(), left.length()));
        Map<String, Object> evidence = projectNode(source, redaction);
        String message = freeText(failure.getMessage(), redaction, evidence, "message", truncatedDetails(source));
        String category = bound(failure.category());
        evidence.put("category", category);
        evidence.put("message", message);
        // Do not let diagnostic traversal republish details from the private cause chain.
        return new ToolExecutionException(category, message, evidence, failure.exitCode(), null);
    }

    /** Project a returned failure before any collector publication or logging. */
    static ToolInvocationResult project(ToolInvocationResult failure) {
        return project(failure, false);
    }

    static ToolInvocationResult project(ToolInvocationResult failure, boolean unsafeLocal) {
        Redaction redaction = new Redaction(unsafeLocal);
        Map<String, Object> invocation = failure.invocation();
        redaction.limited = Boolean.TRUE.equals(invocation.get("inputRedactionLimited"));
        redaction.captureTruncated = truncatedDetails(invocation);
        redaction.collect(invocation.get("input"));
        Map<String, Object> nativeEvidence = failure.operationResult().evidence();
        for (String kind : new String[] {"tool", "http", "db", "mq"}) {
            for (Map<?, ?> node : invocationNodes(nativeEvidence.get(kind), redaction)) {
                redaction.collect(node.get("input"));
                redaction.captureTruncated |= truncatedDetails(node);
            }
        }
        redaction.tokens.sort((left, right) -> Integer.compare(right.length(), left.length()));
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        Map<String, Object> resourceFailure = null;
        for (String kind : new String[] {"tool", "http", "db", "mq"}) {
            List<Map<?, ?>> nodes = invocationNodes(nativeEvidence.get(kind), redaction);
            if (nodes.isEmpty()) continue;
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            List<Object> projected = new ArrayList<Object>();
            for (Map<?, ?> node : nodes) {
                Map<String, Object> safeNode = projectNode(node, redaction);
                projected.add(safeNode);
                if (!"tool".equals(kind) && resourceFailure == null && safeNode.get("error") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> summary = (Map<String, Object>) safeNode.get("error");
                    if (!summary.isEmpty()) resourceFailure = summary;
                }
            }
            group.put("invocations", projected);
            evidence.put(kind, group);
        }
        Map<String, Object> safeInvocation = projectNode(invocation, redaction);
        if (resourceFailure != null && !safeInvocation.containsKey("error")) safeInvocation.put("error", resourceFailure);
        Map<String, Object> diagnostic = failure.operationResult().diagnostic();
        Map<String, Object> safeDiagnostic = diagnostic == null ? null : fields(diagnostic, DIAGNOSTIC_FIELDS, redaction);
        // Failed results can be raw stdout/payload echoes, so only publish failure metadata.
        return new ToolInvocationResult(failure.toolName(), failure.invocationId(), null, safeInvocation,
                failure.executionSuccess(), new ActionExecutionResult(null, evidence, failure.executionSuccess(),
                        safeDiagnostic, failure.operationResult().durationMs()));
    }

    private static List<Map<?, ?>> invocationNodes(Object group, Redaction redaction) {
        List<Map<?, ?>> result = new ArrayList<Map<?, ?>>();
        Object nodes = group instanceof Map ? ((Map<?, ?>) group).get("invocations") : null;
        if (!(nodes instanceof List)) return result;
        List<?> values = (List<?>) nodes;
        if (values.size() > INSTANCE_LIMIT) redaction.limited = true;
        for (int index = 0; index < Math.min(values.size(), INSTANCE_LIMIT); index++) {
            if (values.get(index) instanceof Map) result.add((Map<?, ?>) values.get(index));
        }
        return result;
    }

    private static Map<String, Object> projectNode(Map<?, ?> source, Redaction redaction) {
        Map<String, Object> evidence = fields(source, FIELDS, redaction);
        if (redaction.limited) evidence.put("inputRedactionLimited", Boolean.TRUE);
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
                    projected.put(bound((String) entry.getKey()), fields(host, INSTANCE_FIELDS, redaction));
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
        Object error = source.get("error");
        if (error == null && "db".equals(source.get("type")) && source.get("result") instanceof Map) {
            error = ((Map<?, ?>) source.get("result")).get("error");
        }
        if (error instanceof Map) evidence.put("error", errorSummary((Map<?, ?>) error, redaction));
        Object diagnostic = source.get("diagnostic");
        if (diagnostic instanceof Map) evidence.put("diagnostic", fields((Map<?, ?>) diagnostic, DIAGNOSTIC_FIELDS, redaction));
        return evidence;
    }

    private static Map<String, Object> errorSummary(Map<?, ?> source, Redaction redaction) {
        Map<String, Object> summary = fields(source, DIAGNOSTIC_FIELDS, redaction);
        if (source.get("cancellation") instanceof Map) {
            summary.put("cancellation", fields((Map<?, ?>) source.get("cancellation"),
                    new String[] {"requested", "mechanism", "confirmed"}, redaction));
        }
        return summary;
    }

    private static Map<String, Object> fields(Map<?, ?> source, String[] names, Redaction redaction) {
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        for (String field : names) {
            Object value = source.get(field);
            if (value instanceof String) {
                boolean freeForm = "url".equals(field) || "stdout".equals(field) || "stderr".equals(field) || "error".equals(field) || "cleanupWarning".equals(field)
                        || "message".equals(field) || "detail".equals(field) || "hint".equals(field);
                target.put(field, "url".equals(field) ? httpOrigin((String) value, target)
                        : freeForm ? freeText((String) value, redaction, target, field, truncatedDetails(source)) : bound((String) value));
                if (((String) value).length() > TEXT_LIMIT) {
                    target.put(field + "Truncated", Boolean.TRUE);
                    target.put("evidenceTruncated", Boolean.TRUE);
                }
            } else if (value instanceof Number || value instanceof Boolean) {
                target.put(field, value);
            }
        }
        return target;
    }

    /** HTTP evidence does not carry resolved request input; only the origin is provably public here. */
    private static String httpOrigin(String value, Map<String, Object> target) {
        try {
            if (value.length() > TEXT_LIMIT) throw new IllegalArgumentException("URL exceeds public budget");
            java.net.URI uri = new java.net.URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException("Invalid HTTP origin");
            if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                target.put("urlPathOmitted", Boolean.TRUE);
            }
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toASCIIString();
        } catch (java.net.URISyntaxException | IllegalArgumentException invalid) {
            target.put("urlPathOmitted", Boolean.TRUE);
            target.put("failureDetailsOmitted", Boolean.TRUE);
            return OMITTED_TEXT;
        }
    }

    private static String freeText(String value, Redaction redaction, Map<String, Object> target, String field, boolean truncated) {
        if (value == null || value.isEmpty()) return "";
        // Full-value substitution cannot prove that an upstream-truncated echo is safe.
        // Omit details when private inputs cannot be completely inspected within the budget.
        // Also avoid scanning/materializing oversized diagnostic strings.
        int textLimit = redaction.unsafeLocal ? 8192 : TEXT_LIMIT;
        if (redaction.limited || value.length() > textLimit || ((truncated || redaction.captureTruncated) && !redaction.tokens.isEmpty())) {
            target.put("failureDetailsOmitted", Boolean.TRUE);
            if (value.length() > TEXT_LIMIT) {
                target.put(field + "Truncated", Boolean.TRUE);
                target.put("evidenceTruncated", Boolean.TRUE);
            }
            return OMITTED_TEXT;
        }
        String safe = value;
        for (String token : redaction.tokens) {
            safe = safe.replace(token, "[REDACTED_SECRET]");
            if (safe.length() > textLimit) {
                target.put(field + "Truncated", Boolean.TRUE);
                target.put("evidenceTruncated", Boolean.TRUE);
                target.put("failureDetailsOmitted", Boolean.TRUE);
                return OMITTED_TEXT;
            }
        }
        return redaction.unsafeLocal ? safe : bound(safe);
    }

    private static boolean truncatedDetails(Map<?, ?> source) {
        for (String flag : new String[] {"messageTruncated", "errorTruncated", "cleanupWarningTruncated",
                "stderrTruncated", "stderrArtifactTruncated", "stdoutTruncated", "stdoutArtifactTruncated",
                "evidenceTruncated"}) {
            if (Boolean.TRUE.equals(source.get(flag))) return true;
        }
        return false;
    }

    /** A bounded inspection; exceeding any budget fails closed for free-form details. */
    private static final class Redaction {
        final List<String> tokens = new ArrayList<String>();
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        int nodes;
        int characters;
        boolean limited;
        boolean captureTruncated;
        final boolean unsafeLocal;

        Redaction(boolean unsafeLocal) { this.unsafeLocal = unsafeLocal; }

        void token(String value) {
            if (limited || value == null || value.isEmpty()) return;
            int textLimit = unsafeLocal ? 65536 : TEXT_LIMIT;
            int tokenLimit = unsafeLocal ? 1048576 : TOKEN_CHARACTER_LIMIT;
            if (value.length() < MIN_TOKEN_LENGTH || value.length() > textLimit || characters + value.length() > tokenLimit) {
                limited = true;
                return;
            }
            characters += value.length();
            tokens.add(value);
        }

        void collect(Object value) {
            if (limited || value == null) return;
            if (++nodes > (unsafeLocal ? 10000 : INPUT_NODE_LIMIT)) { limited = true; return; }
            if (value instanceof String) {
                token((String) value);
            } else if (value instanceof byte[]) {
                byte[] bytes = (byte[]) value;
                if (bytes.length > (unsafeLocal ? 65536 : BINARY_LIMIT)) { limited = true; return; }
                token(new String(bytes, StandardCharsets.UTF_8));
                token(Base64.getEncoder().encodeToString(bytes));
                StringBuilder hex = new StringBuilder(bytes.length * 2);
                for (byte part : bytes) hex.append(String.format("%02x", part & 0xff));
                token(hex.toString());
                token(hex.toString().toUpperCase(java.util.Locale.ROOT));
                token(Arrays.toString(bytes));
            } else if (value instanceof char[]) {
                char[] chars = (char[]) value;
                if (chars.length > (unsafeLocal ? 65536 : TEXT_LIMIT)) { limited = true; return; }
                token(new String(chars));
            } else if (value instanceof Map && visited.add(value)) {
                for (Object nested : ((Map<?, ?>) value).values()) {
                    collect(nested);
                    if (limited) break;
                }
            } else if (value instanceof Iterable && visited.add(value)) {
                for (Object nested : (Iterable<?>) value) {
                    collect(nested);
                    if (limited) break;
                }
            } else if (value.getClass().isArray() && visited.add(value)) {
                int length = Array.getLength(value);
                if (length > (unsafeLocal ? 4096 : ARRAY_LIMIT)) { limited = true; return; }
                if (value.getClass().getComponentType().isPrimitive()) {
                    List<Object> elements = new ArrayList<Object>(length);
                    for (int index = 0; index < length; index++) elements.add(Array.get(value, index));
                    token(elements.toString());
                    for (Object element : elements) {
                        collect(element);
                        if (limited) break;
                    }
                } else {
                    for (int index = 0; index < length; index++) {
                        collect(Array.get(value, index));
                        if (limited) break;
                    }
                }
            } else if (!(value instanceof Map) && !(value instanceof Iterable)) {
                // Unknown typed input may have an arbitrary or expensive representation.
                // Scalar values are bounded by their built-in representations.
                if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                        || value instanceof Float || value instanceof Double || value instanceof Boolean) token(String.valueOf(value));
                else limited = true;
            }
        }
    }

    private static String bound(String value) {
        if (value == null) return "";
        return value.length() > TEXT_LIMIT ? value.substring(0, TEXT_LIMIT - 14) + "...[TRUNCATED]" : value;
    }
}
