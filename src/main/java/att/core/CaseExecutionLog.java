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

    public CaseExecutionLog(Path path) throws IOException {
        this(path, false);
    }

    public CaseExecutionLog(Path path, boolean yamlAnchors) throws IOException {
        this.path = path;
        this.yamlAnchors = yamlAnchors;
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
