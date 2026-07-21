/*
 * Author: Jeffrey + ChatGPT
 */

package att.core;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends ordered V2 case, stage and action diagnostics into one UTF-8 case log.
 */
public class CaseExecutionLog {
    private final Path path;
    private final boolean yamlAnchors;
    private final java.util.function.Consumer<String> mirror;

    public CaseExecutionLog(Path path) throws IOException {
        this(path, false);
    }

    public CaseExecutionLog(Path path, boolean yamlAnchors) throws IOException {
        this(path, yamlAnchors, null);
    }

    public CaseExecutionLog(Path path, boolean yamlAnchors, java.util.function.Consumer<String> mirror) throws IOException {
        this.path = path;
        this.yamlAnchors = yamlAnchors;
        this.mirror = mirror;
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[0]);
    }

    public Path path() {
        return path;
    }

    public synchronized void append(String section, Object data) throws IOException {
        StringBuilder text = new StringBuilder();
        if (abnormal(section, data)) text.append("【!!!!!】");
        text.append("[").append(section).append("]\n");
        if (data == null) {
            text.append("\n");
        } else if (data instanceof String) {
            text.append(data).append("\n\n");
        } else {
            Object serializable = yamlAnchors ? data : detached(data, new IdentityHashMap<Object, Boolean>());
            text.append(new Yaml().dump(serializable)).append("\n");
        }
        Files.write(path, text.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        if (mirror != null) mirror.accept(text.toString());
    }

    /** Writes the human-readable action log without repeating the complete state retained in case.yaml. */
    public void appendAction(String section, Map<String, Object> action) throws IOException {
        append(section, compactAction(action));
    }

    /** Writes one standalone expression Tool invocation as a compact attempt record. */
    public void appendToolInvocation(String section, Map<String, Object> invocation) throws IOException {
        append(section, compactAttempt(invocation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compactAction(Map<String, Object> action) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        copyIfPresent(action, result, "id", "type");
        Object description = action.get("description");
        if (description != null && !String.valueOf(description).isEmpty()) result.put("description", description);
        Object rawOutput = action.get("output");
        if (!(rawOutput instanceof Map)) return result;
        Map<String, Object> output = (Map<String, Object>) rawOutput;
        Map<String, Object> compact = new LinkedHashMap<String, Object>();
        copyIfPresent(output, compact, "status", "durationMs");
        copyNonEmpty(output, compact, "targetFiles");
        if ("tool".equalsIgnoreCase(String.valueOf(action.get("type")))) {
            Object attempts = output.get("attempts");
            if (attempts instanceof Iterable) {
                java.util.List<Map<String, Object>> compactAttempts = new ArrayList<Map<String, Object>>();
                for (Object attempt : (Iterable<?>) attempts) if (attempt instanceof Map) compactAttempts.add(compactAttempt((Map<String, Object>) attempt));
                if (!compactAttempts.isEmpty()) compact.put("attempts", compactAttempts);
            }
            copyIfPresent(output, compact, "winningAttempt", "assertion");
        } else {
            copyResultUnlessTargetDuplicate(output, compact);
            copyIfPresent(output, compact, "renderAs", "sources", "level", "sourceFile", "fields", "name", "assertion", "expected", "actual");
        }
        Object exception = output.get("exception");
        if (exception instanceof Map) compact.put("exception", compactException((Map<String, Object>) exception));
        result.put("output", compact);
        return result;
    }

    private Map<String, Object> compactAttempt(Map<String, Object> attempt) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        copyIfPresent(attempt, result, "attempt", "id", "status", "durationMs", "input", "logicalArgv");
        Object logical = attempt.get("logicalArgv"), executed = attempt.get("argv");
        if (executed != null && !executed.equals(logical)) result.put("argv", executed);
        Object parsed = attempt.get("output"), stdout = attempt.get("stdout");
        if (!(parsed instanceof String) || stdout == null || !String.valueOf(parsed).equals(String.valueOf(stdout).trim())) {
            if (attempt.containsKey("output")) result.put("output", parsed);
        }
        if (stdout != null && !String.valueOf(stdout).isEmpty()) result.put("stdout", stdout);
        copyNonEmpty(attempt, result, "stderr");
        copyIfPresent(attempt, result, "exitCode", "timeoutMs", "outputFile", "category", "parserDiagnostic", "sshDestination", "sshPort", "sshTransport");
        return result;
    }

    private Map<String, Object> compactException(Map<String, Object> exception) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        copyIfPresent(exception, result, "type", "code", "summary", "detail", "location", "suggestion");
        return result;
    }

    private void copyResultUnlessTargetDuplicate(Map<String, Object> source, Map<String, Object> target) {
        if (!source.containsKey("result")) return;
        Object value = source.get("result");
        if (value != null && value.equals(source.get("targetFiles"))) return;
        target.put("result", value);
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private void copyNonEmpty(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value == null || (value instanceof String && ((String) value).isEmpty())
                || (value instanceof java.util.Collection && ((java.util.Collection<?>) value).isEmpty())
                || (value instanceof Map && ((Map<?, ?>) value).isEmpty())) return;
        target.put(key, value);
    }

    private boolean abnormal(String section, Object data) {
        if (section != null && section.matches("(?i).*(^|\\s)(ERROR|FAIL|INVALID)(\\s|$).*")) return true;
        return abnormalValue(data, new IdentityHashMap<Object, Boolean>());
    }

    private boolean abnormalValue(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null) return false;
        if (value instanceof ResultStatus) return abnormalStatus(String.valueOf(value));
        if (!(value instanceof Map) && !(value instanceof Iterable) && !value.getClass().isArray()) return false;
        if (visited.put(value, Boolean.TRUE) != null) return false;
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if ("status".equalsIgnoreCase(String.valueOf(entry.getKey())) && abnormalStatus(String.valueOf(entry.getValue()))) return true;
                if (abnormalValue(entry.getValue(), visited)) return true;
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) if (abnormalValue(item, visited)) return true;
        } else {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) if (abnormalValue(java.lang.reflect.Array.get(value, index), visited)) return true;
        }
        return false;
    }

    private boolean abnormalStatus(String value) {
        return "ERROR".equalsIgnoreCase(value) || "FAIL".equalsIgnoreCase(value) || "INVALID".equalsIgnoreCase(value);
    }

    /** Copies every occurrence so SnakeYAML never emits anchors for shared references. */
    private Object detached(Object value, IdentityHashMap<Object, Boolean> active) {
        if (value == null) return null;
        boolean container = value instanceof Map || value instanceof Iterable || value.getClass().isArray();
        if (!container) return value;
        if (active.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic data cannot be written to the case log");
        try {
            if (value instanceof Map) {
                Map<Object, Object> copy = new LinkedHashMap<Object, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    copy.put(detached(entry.getKey(), active), detached(entry.getValue(), active));
                }
                return copy;
            }
            ArrayList<Object> copy = new ArrayList<Object>();
            if (value instanceof Iterable) {
                for (Object item : (Iterable<?>) value) copy.add(detached(item, active));
            } else {
                int length = java.lang.reflect.Array.getLength(value);
                for (int index = 0; index < length; index++) copy.add(detached(java.lang.reflect.Array.get(value, index), active));
            }
            return copy;
        } finally {
            active.remove(value);
        }
    }
}
