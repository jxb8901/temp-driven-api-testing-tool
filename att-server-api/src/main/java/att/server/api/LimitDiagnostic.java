package att.server.api;

import att.server.api.internal.DiagnosticSanitizer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Safe, bounded wire projection for limit diagnostics shared by Engine, Worker, and Server. */
public final class LimitDiagnostic {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}");
    private static final Pattern RELATIVE = Pattern.compile("[A-Za-z0-9._/-]{1,256}");

    private LimitDiagnostic() { }

    public static Map<String, Object> create(String code, String summary, String limitName,
                                             Number configuredLimit, Number observed, String scope,
                                             LimitOutcome outcome, String stage, String remediationHint,
                                             String artifactRef) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        put(value, "code", code, 128);
        put(value, "summary", summary, 512);
        put(value, "limitName", limitName, 96);
        if (configuredLimit != null) {
            value.put("configuredLimit", configuredLimit);
            value.put("limit", configuredLimit);
        }
        if (observed != null) {
            value.put("observed", observed);
            value.put("actual", observed);
            value.put("usage", observed);
        }
        put(value, "scope", scope, 96);
        if (outcome != null) value.put("outcome", outcome.name());
        put(value, "stage", stage, 96);
        put(value, "remediationHint", remediationHint, 256);
        if (safeArtifactRef(artifactRef)) value.put("artifactRef", artifactRef);
        return value;
    }

    private static void put(Map<String, Object> target, String key, String raw, int maximum) {
        if (raw == null) return;
        String value = DiagnosticSanitizer.redactAbsolutePaths(DiagnosticSanitizer.redactText(raw));
        if ("summary".equals(key) || "remediationHint".equals(key)) {
            if (value.length() > maximum) value = value.substring(0, maximum);
        } else if (!TOKEN.matcher(value).matches()) return;
        if (!value.isEmpty()) target.put(key, value);
    }

    private static boolean safeArtifactRef(String value) {
        if (value == null || !RELATIVE.matcher(value).matches() || value.startsWith("/")
                || value.contains("..") || value.indexOf('\\') >= 0) return false;
        return true;
    }
}
