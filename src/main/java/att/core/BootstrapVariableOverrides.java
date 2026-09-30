package att.core;

import att.config.YamlSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies repeatable --set vars.path=value overrides before bootstrap expression evaluation. */
public final class BootstrapVariableOverrides {
    private BootstrapVariableOverrides() { }

    public static Map<String, Object> apply(Map<String, Object> original, List<String> assignments) {
        Map<String, Object> result = copy(original);
        if (assignments == null) return result;
        for (String assignment : assignments) {
            int equals = assignment == null ? -1 : assignment.indexOf('=');
            if (equals < 0) throw new IllegalArgumentException("--set requires vars.path=value");
            String path = assignment.substring(0, equals).trim();
            if (!path.startsWith("vars.") || path.length() <= 5)
                throw new IllegalArgumentException("Issue #91 supports --set vars.path=value only");
            String[] segments = path.substring(5).split("\\.", -1);
            for (String segment : segments) if (!segment.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("Invalid --set variable path '" + path + "'");
            Object parsed = YamlSupport.parser().load(assignment.substring(equals + 1));
            Map<String, Object> cursor = result;
            for (int i = 0; i < segments.length - 1; i++) {
                String segment = segments[i];
                Object child = cursor.get(segment);
                if (child == null && !cursor.containsKey(segment)) {
                    Map<String, Object> created = new LinkedHashMap<String, Object>();
                    cursor.put(segment, created);
                    cursor = created;
                } else if (child instanceof Map) {
                    cursor = cast(child);
                } else {
                    throw new IllegalArgumentException("--set path '" + path + "' crosses a non-map value at '" + segment + "'");
                }
            }
            cursor.put(segments[segments.length - 1], parsed);
        }
        return result;
    }

    public static List<String> copyAssignments(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source != null) for (Map.Entry<String, Object> entry : source.entrySet()) result.put(entry.getKey(), copyValue(entry.getValue()));
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) result.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(copyValue(item));
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
}
