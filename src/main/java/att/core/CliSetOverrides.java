package att.core;

import att.config.YamlSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Safe typed parser and deterministic nested-path writer for the unified --set CLI option. */
public final class CliSetOverrides {
    private CliSetOverrides() { }

    public static void validate(List<String> values) {
        if (values == null) return;
        for (String value : values) parse(value);
    }

    public static List<String> copyAssignments(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }

    public static boolean hasNamespace(List<String> values, String namespace) {
        if (values == null) return false;
        for (String value : values) if (parse(value).namespace.equals(namespace)) return true;
        return false;
    }

    public static Map<String, Object> apply(Map<String, Object> original, List<String> values, String namespace) {
        Map<String, Object> result = copyMap(original);
        if (values == null) return result;
        for (String raw : values) {
            Assignment assignment = parse(raw);
            if (namespace.equals(assignment.namespace)) set(result, assignment.path, assignment.value, raw);
        }
        return result;
    }

    public static String namespace(String assignment) { return parse(assignment).namespace; }

    private static Assignment parse(String raw) {
        int equals = raw == null ? -1 : raw.indexOf('=');
        if (equals <= 0) throw new IllegalArgumentException("--set requires <namespace.path>=<value>");
        String left = raw.substring(0, equals).trim();
        String right = raw.substring(equals + 1);
        int dot = left.indexOf('.');
        if (dot <= 0 || dot == left.length() - 1)
            throw new IllegalArgumentException("--set requires <namespace.path>=<value>; supported namespaces: input, arg, vars");
        String namespace = left.substring(0, dot);
        if (!"input".equals(namespace) && !"arg".equals(namespace) && !"vars".equals(namespace))
            throw new IllegalArgumentException("Unknown --set namespace '" + namespace + "'; supported namespaces: input, arg, vars");
        String path = left.substring(dot + 1);
        try { CaseRuntimeContext.validateReferencePath("EXEC." + ("input".equals(namespace) ? "INPUT" : "VARS") + "." + path); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid --set path '" + left + "': " + error.getMessage(), error);
        }
        if ("arg".equals(namespace) && !path.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("--set arg supports one Tool argument name, not a nested path: " + path);
        if (right.trim().isEmpty())
            throw new IllegalArgumentException("--set value must not be empty; use null or '' explicitly");
        Object parsed;
        String trimmedValue = right.trim();
        if (trimmedValue.startsWith("#{")) {
            // '#' starts a YAML comment in this position; ATT expressions are literals here.
            parsed = trimmedValue;
        } else try { parsed = YamlSupport.parser().load(right); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid YAML value for --set path '" + left + "': " + error.getMessage(), error); }
        return new Assignment(namespace, path, parsed, raw);
    }

    private static void set(Map<String, Object> root, String path, Object value, String raw) {
        List<PathPart> parts = parts(path, raw);
        Object current = root;
        for (int i = 0; i < parts.size(); i++) {
            PathPart part = parts.get(i);
            boolean last = i == parts.size() - 1;
            if (current instanceof Map && part.key != null) {
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) current;
                if (last) { map.put(part.key, copyValue(value)); return; }
                Object child = map.get(part.key);
                if (!map.containsKey(part.key)) {
                    child = parts.get(i + 1).index == null ? new LinkedHashMap<String, Object>() : new ArrayList<Object>();
                    map.put(part.key, child);
                }
                if (child == null || (!(child instanceof Map) && !(child instanceof List)))
                    throw traversal(raw, part.display);
                current = child;
            } else if (current instanceof List && part.index != null) {
                @SuppressWarnings("unchecked") List<Object> list = (List<Object>) current;
                int index = part.index.intValue();
                if (index < 0 || index > list.size())
                    throw new IllegalArgumentException("--set list index is out of range at '" + part.display + "' in " + raw);
                if (last) {
                    Object copied = copyValue(value);
                    if (index == list.size()) list.add(copied); else list.set(index, copied);
                    return;
                }
                if (index == list.size()) {
                    if (index != 0) throw new IllegalArgumentException("--set cannot skip list indexes at '" + part.display + "' in " + raw);
                    list.add(parts.get(i + 1).index == null ? new LinkedHashMap<String, Object>() : new ArrayList<Object>());
                }
                Object child = list.get(index);
                if (child == null || (!(child instanceof Map) && !(child instanceof List)))
                    throw traversal(raw, part.display);
                current = child;
            } else {
                throw traversal(raw, part.display);
            }
        }
    }

    private static IllegalArgumentException traversal(String raw, String segment) {
        return new IllegalArgumentException("--set path '" + raw + "' cannot traverse a scalar or incompatible container at '" + segment + "'");
    }

    private static List<PathPart> parts(String path, String raw) {
        List<PathPart> result = new ArrayList<PathPart>();
        int index = 0;
        boolean needKey = true;
        while (index < path.length()) {
            char ch = path.charAt(index);
            if (ch == '.') {
                if (needKey) throw invalidPath(raw);
                needKey = true; index++; continue;
            }
            if (ch == '[') {
                int end = bracketEnd(path, index);
                if (end < 0) throw invalidPath(raw);
                String selector = path.substring(index + 1, end).trim();
                if (selector.matches("[0-9]+")) {
                    try { result.add(PathPart.index(Integer.parseInt(selector), path.substring(index, end + 1))); }
                    catch (NumberFormatException error) { throw invalidPath(raw); }
                } else if (selector.length() >= 2 && ((selector.charAt(0) == '\'' && selector.charAt(selector.length() - 1) == '\'')
                        || (selector.charAt(0) == '"' && selector.charAt(selector.length() - 1) == '"'))) {
                    result.add(PathPart.key(selector.substring(1, selector.length() - 1), path.substring(index, end + 1)));
                } else throw invalidPath(raw);
                index = end + 1; needKey = false; continue;
            }
            if (!needKey) throw invalidPath(raw);
            int end = index;
            while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
            String key = path.substring(index, end);
            if (key.isEmpty()) throw invalidPath(raw);
            result.add(PathPart.key(key, key));
            index = end; needKey = false;
        }
        if (needKey || result.isEmpty() || result.get(0).key == null) throw invalidPath(raw);
        return result;
    }

    private static int bracketEnd(String path, int start) {
        char quote = 0;
        for (int i = start + 1; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quote != 0) {
                if (c == quote && (i == 0 || path.charAt(i - 1) != '\\')) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == ']') return i;
        }
        return -1;
    }

    private static IllegalArgumentException invalidPath(String raw) {
        return new IllegalArgumentException("Invalid --set path in '" + raw + "'; use dot keys and numeric list indexes such as input.customer.ids[0]");
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (source != null) for (Map.Entry<String, Object> entry : source.entrySet()) copy.put(entry.getKey(), copyValue(entry.getValue()));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) copy.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
            return copy;
        }
        if (value instanceof Iterable) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) copy.add(copyValue(item));
            return copy;
        }
        return value;
    }

    private static final class Assignment {
        private final String namespace, path, raw;
        private final Object value;
        private Assignment(String namespace, String path, Object value, String raw) {
            this.namespace = namespace; this.path = path; this.value = value; this.raw = raw;
        }
    }

    private static final class PathPart {
        private final String key, display;
        private final Integer index;
        private PathPart(String key, Integer index, String display) { this.key = key; this.index = index; this.display = display; }
        private static PathPart key(String key, String display) { return new PathPart(key, null, display); }
        private static PathPart index(int index, String display) { return new PathPart(null, Integer.valueOf(index), display); }
    }
}
