package att.console;

import att.security.SecretProtection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Authoritative plain-text console renderer for RUN, DEBUG and LOAD execution events.
 *
 * <p>The implementation lives in the shared Java 8 library so CLI, Worker and Server call one
 * formatter without placing the execution engine on the Server classpath. It never writes to a
 * stream. Callers choose a presentation policy: local CLI display, or the published projection
 * that is bounded, secret-redacted and free of ANSI control sequences.
 */
public final class ExecutionConsoleFormatter {
    public static final int PUBLISHED_MAX_CHARS = 4000;
    public static final int PUBLISHED_MAX_LINES = 40;
    private static final int CLI_MAX_CHARS = 16000;
    private static final int CLI_MAX_LINES = 80;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)");

    /** Projects nested structures before they are rendered. CLI path display uses this hook. */
    public interface StructureProjector {
        Object project(Object value);
    }

    /** Explicit presentation policy. The formatter does not read global CLI flags or locales. */
    public static final class Options {
        final boolean redact;
        final StructureProjector projector;
        final int maxChars;
        final int maxLines;

        private Options(boolean redact, StructureProjector projector, int maxChars, int maxLines) {
            this.redact = redact;
            this.projector = projector;
            this.maxChars = maxChars;
            this.maxLines = maxLines;
        }

        public static Options cli(StructureProjector projector) {
            return new Options(false, projector, CLI_MAX_CHARS, CLI_MAX_LINES);
        }

        /** Safe projection stored in the Job journal and shown as Live Output. */
        public static Options published() {
            return published(PUBLISHED_MAX_CHARS, PUBLISHED_MAX_LINES);
        }

        public static Options published(int maxChars, int maxLines) {
            if (maxChars < 1 || maxChars > 32000 || maxLines < 1 || maxLines > 200)
                throw new IllegalArgumentException("Published console limits must be 1..32000 chars and 1..200 lines");
            return new Options(true, null, maxChars, maxLines);
        }
    }

    private ExecutionConsoleFormatter() { }

    /**
     * Renders one execution event. {@code executionType} is the original {@code ExecutionEvent.Type}
     * name. Returns {@code null} when the event has no console line.
     */
    public static String format(String executionType, String runId, String caseId, String stage, String action,
                                String status, Long durationMs, String message, Map<String, Object> data, Options options) {
        if (options == null) throw new IllegalArgumentException("console options are required");
        Map<String, Object> values = data == null ? Collections.<String, Object>emptyMap() : data;
        Object kind = values.get("event");
        if ("RUN_VALIDATION_SUMMARY".equals(kind)
                || ("PROGRESS".equals(executionType) && "VALIDATION_PASS".equals(status)))
            return present("[1/4] V" + values.get("productVersion") + " validation PASS: "
                    + values.get("suites") + " suites, " + values.get("cases") + " cases, "
                    + values.get("templates") + " templates, " + values.get("tools") + " tools", options);
        if ("RUN_SELECTION_SUMMARY".equals(kind))
            return present("[2/4] Selected: " + values.get("cases") + " cases from " + values.get("suites") + " suites", options);
        if ("RUN_EXECUTION_START".equals(kind)) return present("[3/4] " + message, options);
        if ("VALIDATION_DIAGNOSTIC".equals(kind)) {
            Object value = values.get("diagnostic");
            if (options.projector != null) value = options.projector.project(value);
            if (value instanceof Map) return present(renderValidationDiagnostic((Map<?, ?>) value), options);
        }
        if ("DEBUG_STARTED".equals(kind))
            return present("[DEBUG] START target=" + scalar(values.get("targetType"), options) + ":" + scalar(values.get("targetId"), options)
                    + " input=" + scalar(values.get("input"), options) + " output=" + scalar(values.get("output"), options), options);
        if ("DEBUG_INPUT_RESOLVED".equals(kind))
            return present("[DEBUG] INPUT target=" + scalar(values.get("targetType"), options) + ":" + scalar(values.get("targetId"), options)
                    + " case=" + scalar(caseId, options) + " resolved=" + scalar(values.get("resolved"), options), options);
        if ("RUN_QUEUED".equals(kind)) return present("[RUN] queued: " + message, options);
        if ("RUN_STARTED".equals(kind))
            return present("[RUN] id=" + scalar(runId, options) + " suites=" + scalar(values.get("suites"), options)
                    + " output=" + scalar(values.get("output"), options), options);
        if ("RUN_CANCELLED".equals(kind)) return present("[RUN] CANCELLED runId=" + scalar(runId, options), options);
        if ("SUITE_STARTED".equals(kind))
            return present("[SUITE] file=" + scalar(values.get("file"), options) + " cases=" + scalar(values.get("cases"), options), options);
        if ("CASE_STARTED".equals(kind)) return present("[CASE] id=" + scalar(caseId, options) + " status=START", options);
        if ("CASE_FINISHED".equals(kind))
            return present("[CASE] id=" + scalar(caseId, options) + " status=" + scalar(status, options) + " durationMs=" + durationMs, options);
        if ("CASE_LOG_PATH".equals(kind))
            return present("[CASE-LOG] case=" + scalar(caseId, options) + " file=" + scalar(values.get("file"), options), options);
        if ("STAGE_STARTED".equals(kind))
            return present("[STAGE] case=" + scalar(caseId, options) + " stage=" + scalar(stage, options)
                    + " template=" + scalar(values.get("template"), options) + " status=START", options);
        if ("STAGE_FINISHED".equals(kind))
            return present("[STAGE] case=" + scalar(caseId, options) + " stage=" + scalar(stage, options)
                    + " template=" + scalar(values.get("template"), options) + " status=" + scalar(status, options)
                    + " durationMs=" + durationMs, options);
        if ("ACTION_FINISHED".equals(kind))
            return present("[ACTION] case=" + scalar(caseId, options) + " stage=" + scalar(stage, options)
                    + " action=" + scalar(action, options) + " status=" + scalar(status, options)
                    + (values.get("detail") == null ? "" : " message=" + scalar(values.get("detail"), options)), options);
        if ("CASE_LOG".equals(executionType)) return present("[CASE-LOG case=" + scalar(caseId, options) + "] " + message, options);
        if ("STATUS".equals(executionType) && "SNAPSHOT_UPDATED".equals(status)) return present(message, options);
        if ("STATUS".equals(executionType) && "CANCELLED".equals(status) && values.get("target") != null)
            return present("[DEBUG] CANCELLED target=" + scalar(values.get("target"), options), options);
        if (!knownType(executionType) && kind == null) return null;
        return present(message, options);
    }

    /** Renders the flattened, already sanitized Worker/Server event payload. */
    public static String formatPayload(Map<String, Object> data, Options options) {
        if (data == null || data.isEmpty()) return null;
        Object existing = data.get("console");
        if (existing instanceof String) return present((String) existing, options);
        String executionType = text(data.get("executionEventType"));
        if (!knownType(executionType) && data.get("event") == null) return null;
        return format(executionType, firstText(data, "runId", "executionId"), text(data.get("caseId")),
                text(data.get("stage")), text(data.get("action")), text(data.get("status")),
                longValue(data.get("durationMs")), text(data.get("message")), data, options);
    }

    public static String lifecycle(String mode, String jobId, String status, Integer exitCode) {
        StringBuilder line = new StringBuilder("[").append(mode == null ? "RUN" : mode).append("] status=")
                .append(status).append(" jobId=").append(token(jobId, "unknown"));
        if (exitCode != null) line.append(" exitCode=").append(exitCode.intValue());
        return line.toString();
    }

    public static String diagnostic(String mode, String code, String summary) {
        StringBuilder line = new StringBuilder("[").append(mode == null ? "RUN" : mode).append("] DIAGNOSTIC");
        if (code != null && !code.isEmpty()) line.append(" code=").append(token(code, "unknown"));
        if (summary != null && !summary.isEmpty()) line.append(" summary=").append(token(summary, ""));
        return line.toString();
    }

    public static String runComplete(boolean verbose, Object total, Object passed, Object failed,
                                     Object error, Object skipped, Object invalid) {
        return (verbose ? "[4/4] Complete: " : "Complete: ")
                + "total=" + total + ", passed=" + passed + ", failed=" + failed
                + ", error=" + error + ", skipped=" + skipped + ", invalid=" + invalid;
    }

    public static String reportLine(String path) { return "Report: " + path; }

    public static String metricsLine(Object metrics) { return "Metrics: " + metrics; }

    public static String debugComplete(String status, String targetType, String targetId, Object durationMs) {
        return "DEBUG " + status + " | Target: " + targetType + " " + targetId + " | Duration: " + durationMs + "ms";
    }

    public static String labeledPath(String label, String path) { return label + ": " + path; }

    public static String quietFailure(Object status, Object caseId, String caseLog) {
        return "[RUN] " + status + " case=" + caseId + " caseLog=" + caseLog;
    }

    public static String loadStart(String runId, String model, String configuration) {
        StringBuilder line = new StringBuilder("[LOAD] START runId=").append(runId).append(" model=").append(model);
        if (configuration != null && !configuration.isEmpty()) line.append(' ').append(configuration);
        return line.toString();
    }

    public static String loadError(String runId, String workload, long sequence, String status, String errorType) {
        StringBuilder line = new StringBuilder("[LOAD] ERROR runId=").append(runId)
                .append(" workload=").append(workload)
                .append(" sequence=").append(sequence)
                .append(" status=").append(status);
        if (errorType != null && !errorType.isEmpty()) line.append(" errorType=").append(errorType);
        return line.toString();
    }

    public static String loadSummary(String runId, String model, String status, long completed, long passed,
                                     long failed, long errors, long dropped, long suppressed) {
        StringBuilder summary = new StringBuilder("[LOAD] SUMMARY runId=").append(runId)
                .append(" model=").append(model).append(" status=").append(status)
                .append(" completed=").append(completed).append(" pass=").append(passed)
                .append(" fail=").append(failed).append(" error=").append(errors)
                .append(" dropped=").append(dropped);
        if (suppressed > 0L) summary.append(" suppressedErrors=").append(suppressed);
        return summary.toString();
    }

    public static String loadProgress(String label, String runId, String terminalStatus, long elapsedMs,
                                      long scheduled, long started, long completed, long active, long passed,
                                      long failed, long errors, long dropped, double throughput, double meanLatency,
                                      long suppressed) {
        StringBuilder line = new StringBuilder("[LOAD] ").append(label).append(" runId=").append(runId);
        if (terminalStatus != null) line.append(" status=").append(terminalStatus);
        line.append(" elapsedMs=").append(elapsedMs)
                .append(" scheduled=").append(scheduled)
                .append(" started=").append(started)
                .append(" completed=").append(completed)
                .append(" active=").append(active)
                .append(" pass=").append(passed)
                .append(" fail=").append(failed)
                .append(" error=").append(errors)
                .append(" dropped=").append(dropped)
                .append(" throughputPerSec=").append(String.format(Locale.ROOT, "%.2f", throughput))
                .append(" meanLatencyMs=").append(String.format(Locale.ROOT, "%.2f", meanLatency));
        if (suppressed > 0L) line.append(" suppressedErrors=").append(suppressed);
        return line.toString();
    }

    /** Bounded identifier used by LOAD lines. Locale-independent and free of control characters. */
    public static String token(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    private static boolean knownType(String executionType) {
        return "STATUS".equals(executionType) || "PROGRESS".equals(executionType) || "LOG".equals(executionType)
                || "CASE_LOG".equals(executionType) || "WARNING".equals(executionType) || "DEBUG".equals(executionType);
    }

    public static String present(String value, Options options) {
        if (value == null) return null;
        String text = ANSI.matcher(value).replaceAll("");
        if (options.redact) { text = SecretProtection.text(text).replace("\r\n","\n").replace('\r','\n');text=text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", ""); }
        text = limitLines(text, options.maxLines);
        if (text.length() > options.maxChars) {
            int end = options.maxChars;
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            text = text.substring(0, Math.max(0, end)) + " [truncated]";
        }
        return text;
    }

    private static String limitLines(String text, int maxLines) {
        if (maxLines <= 0) return text;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n' && ++lines > maxLines) {
            return text.substring(0, i) + "\n[truncated]";
        }
        return text;
    }

    private static String scalar(Object value, Options options) {
        if (value == null) return "null";
        if (options.redact && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean))
            return "[omitted]";
        String text = String.valueOf(value);
        if (!options.redact) return text;
        text = text.replace('\r', ' ').replace('\n', ' ');
        if (text.length() > 512) {
            int end = 512;
            if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            text = text.substring(0, end);
        }
        return text;
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    private static String firstText(Map<String, Object> data, String primary, String fallback) {
        Object value = data.get(primary);
        if (value == null) value = data.get(fallback);
        return text(value);
    }

    private static Long longValue(Object value) {
        return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
    }

    private static String renderValidationDiagnostic(Map<?, ?> diagnostic) {
        StringBuilder out = new StringBuilder();
        appendDiagnosticLines(out, "  [" + diagnostic.get("severity") + "] " + diagnostic.get("code") + ": ",
                "\n    ", diagnostic.get("message"));
        Map<?, ?> source = diagnostic.get("source") instanceof Map
                ? (Map<?, ?>) diagnostic.get("source") : Collections.emptyMap();
        String[] locationKeys = {"file", "field", "sheet", "row", "column", "template", "action"};
        List<String> location = new ArrayList<String>();
        for (String key : locationKeys) addLocation(location, key, diagnostic.get(key));
        addLocation(location, "sourceFile", source.get("file"));
        addLocation(location, "line", source.get("line"));
        addLocation(location, "sourceColumn", source.get("column"));
        addLocation(location, "endLine", source.get("endLine"));
        addLocation(location, "endColumn", source.get("endColumn"));
        if (!location.isEmpty()) out.append("\n    location: ").append(join(location));
        appendDiagnosticContext(out, diagnostic.get("context"));
        appendDiagnosticSource(out, source);
        appendDiagnosticSchemaViolations(out, diagnostic.get("schemaViolations"));
        Object occurrences = diagnostic.get("occurrences");
        if (occurrences instanceof Number && ((Number) occurrences).intValue() > 1) {
            out.append("\n    occurrences: ").append(occurrences);
            Object affected = diagnostic.get("affectedCases");
            if (affected instanceof Iterable) {
                out.append("\n    affected cases:");
                for (Object item : (Iterable<?>) affected) out.append("\n      - ").append(flatMap(item));
            }
        }
        appendDiagnosticLines(out, "\n    suggestion: ", "\n      ", diagnostic.get("suggestion"));
        return out.toString();
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(values.get(i));
        }
        return out.toString();
    }

    private static void appendDiagnosticContext(StringBuilder out, Object value) {
        if (!(value instanceof Map)) return;
        for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet())
            out.append('\n').append("    ").append(item.getKey()).append(": ").append(item.getValue());
    }

    private static void appendDiagnosticSource(StringBuilder out, Map<?, ?> source) {
        Object excerptValue = source.get("excerpt");
        if (excerptValue == null) return;
        String[] lines = String.valueOf(excerptValue).split("\\r?\\n", -1);
        int line = number(source.get("line"), 1);
        int column = number(source.get("column"), 1);
        int start = number(source.get("excerptStartLine"), line);
        int caretLine = Math.max(0, Math.min(lines.length - 1, line - start));
        String markedLine = lines[caretLine];
        int spaces = Math.min(Math.max(0, column - 1), markedLine.length());
        for (int index = 0; index < lines.length; index++) {
            out.append('\n').append("    ").append(lines[index]);
            if (index == caretLine) {
                out.append('\n').append("    ");
                for (int col = 0; col < spaces; col++) out.append(markedLine.charAt(col) == '\t' ? '\t' : ' ');
                out.append('^');
            }
        }
    }

    private static void appendDiagnosticSchemaViolations(StringBuilder out, Object value) {
        if (!(value instanceof Iterable)) return;
        java.util.Iterator<?> iterator = ((Iterable<?>) value).iterator();
        if (!iterator.hasNext()) return;
        out.append("\n    schemaViolations:");
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof Map)) { out.append("\n      ").append(item); continue; }
            Map<?, ?> violation = (Map<?, ?>) item;
            out.append("\n      ");
            Object path = violation.get("path"), keyword = violation.get("keyword"), message = violation.get("message");
            if (path != null && !String.valueOf(path).isEmpty()) out.append(path).append(' ');
            if (keyword != null && !String.valueOf(keyword).isEmpty()) out.append('[').append(keyword).append("] ");
            out.append(message == null ? violation : message);
        }
    }

    private static void addLocation(List<String> location, String key, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) location.add(key + "=" + value);
    }

    private static void appendDiagnosticLines(StringBuilder out, String first, String rest, Object value) {
        if (value == null) return;
        String[] lines = String.valueOf(value).split("\\r?\\n", -1);
        out.append(first).append(lines[0]);
        for (int i = 1; i < lines.length; i++) out.append(rest).append(lines[i]);
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String flatMap(Object value) {
        if (!(value instanceof Map)) return String.valueOf(value);
        List<String> items = new ArrayList<String>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            items.add(entry.getKey() + "=" + entry.getValue());
        return join(items);
    }

    /** Worker-shaped payload used by parity tests and the Server backfill. */
    public static Map<String, Object> payload(String executionType, String runId, String caseId, String stage,
                                              String action, String status, Long durationMs, String message,
                                              Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (runId != null) copy.put("executionId", runId);
        if (caseId != null) copy.put("caseId", caseId);
        if (stage != null) copy.put("stage", stage);
        if (action != null) copy.put("action", action);
        if (status != null) copy.put("status", status);
        if (durationMs != null) copy.put("durationMs", durationMs);
        if (message != null) copy.put("message", message);
        if (data != null) copy.putAll(data);
        if (executionType != null) copy.put("executionEventType", executionType);
        return copy;
    }
}
