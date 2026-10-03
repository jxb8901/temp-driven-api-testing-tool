package att.testdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable syntax tree for a Load input mapping, shared by all iterations. */
public final class CompiledTestdataMapping {
    private static final Pattern REFERENCE = Pattern.compile("@\\{([^{}]+)}");
    private static final Pattern CONTEXT = Pattern.compile("\\$\\{([^{}]+)}");
    private final Node root;
    private final Set<String> references;

    private CompiledTestdataMapping(Node root, Set<String> references) {
        this.root = root;
        this.references = Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(references));
    }

    public static CompiledTestdataMapping compile(Map<String, Object> mapping) {
        Map<String, Object> safeMapping = mapping == null ? Collections.<String, Object>emptyMap() : mapping;
        Set<String> references = TestdataSyntax.references(safeMapping);
        return new CompiledTestdataMapping(compileNode(safeMapping), references);
    }

    public Set<String> references() { return references; }

    public Map<String, Object> evaluate(Resolver resolver) throws Exception {
        Object value = root.evaluate(resolver);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Input mapping must be an object");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    public interface Resolver {
        Object testdata(String id, String path) throws Exception;
        Object context(String path) throws Exception;
    }

    private interface Node { Object evaluate(Resolver resolver) throws Exception; }

    private static Node compileNode(Object value) {
        if (value instanceof String) return compileString((String) value);
        if (value instanceof Map) {
            final Map<Object, Node> children = new LinkedHashMap<Object, Node>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                children.put(entry.getKey(), compileNode(entry.getValue()));
            return resolver -> {
                Map<Object, Object> result = new LinkedHashMap<Object, Object>();
                for (Map.Entry<Object, Node> entry : children.entrySet()) result.put(entry.getKey(), entry.getValue().evaluate(resolver));
                return result;
            };
        }
        if (value instanceof Iterable) {
            final List<Node> children = new ArrayList<Node>();
            for (Object item : (Iterable<?>) value) children.add(compileNode(item));
            return resolver -> {
                List<Object> result = new ArrayList<Object>(children.size());
                for (Node child : children) result.add(child.evaluate(resolver));
                return result;
            };
        }
        return resolver -> value;
    }

    private static Node compileString(String value) {
        String text = value.trim();
        if (text.contains("%{")) throw new IllegalArgumentException("%{...} is reserved for generated testdata record templates");
        if (text.contains("#{") || text.contains("&{"))
            throw new IllegalArgumentException("Input mapping supports only literals, @{...}, and ${Context} references");
        Matcher exactData = REFERENCE.matcher(value);
        if (exactData.matches()) {
            DataReference reference = parseReference(exactData.group(1));
            return resolver -> resolver.testdata(reference.id, reference.path);
        }
        if (text.startsWith("@{") && text.endsWith("}") && !text.equals(value))
            throw new IllegalArgumentException("Exact testdata references must not have surrounding whitespace");
        Matcher exactContext = CONTEXT.matcher(text);
        if (exactContext.matches()) {
            String path = validateContext(exactContext.group(1));
            return resolver -> resolver.context(path);
        }
        List<Part> parts = new ArrayList<Part>();
        int cursor = 0;
        while (cursor < value.length()) {
            int dataStart = value.indexOf("@{", cursor), contextStart = value.indexOf("${", cursor);
            int start = dataStart < 0 ? contextStart : contextStart < 0 ? dataStart : Math.min(dataStart, contextStart);
            if (start < 0) { parts.add(Part.literal(value.substring(cursor))); break; }
            if (start > cursor) parts.add(Part.literal(value.substring(cursor, start)));
            boolean data = start == dataStart;
            Matcher matcher = (data ? REFERENCE : CONTEXT).matcher(value);
            matcher.region(start, value.length());
            if (!matcher.lookingAt()) throw new IllegalArgumentException("Malformed input mapping reference");
            String reference = matcher.group(1);
            if (data) parts.add(Part.data(parseReference(reference)));
            else parts.add(Part.context(validateContext(reference)));
            cursor = matcher.end();
        }
        if (value.contains("@{") && parts.stream().noneMatch(part -> part.kind == 1)
                || value.contains("${") && parts.stream().noneMatch(part -> part.kind == 2))
            throw new IllegalArgumentException("Malformed input mapping reference");
        final List<Part> immutable = Collections.unmodifiableList(parts);
        return resolver -> {
            StringBuilder result = new StringBuilder();
            for (Part part : immutable) {
                if (part.kind == 0) result.append(part.value);
                else {
                    Object resolved = part.kind == 1 ? resolver.testdata(part.id, part.path) : resolver.context(part.value);
                    if (resolved == null || resolved instanceof Map || resolved instanceof Iterable || resolved.getClass().isArray())
                        throw new IllegalArgumentException("Embedded input mapping references must resolve to a non-null scalar");
                    result.append(String.valueOf(resolved));
                }
            }
            return result.toString();
        };
    }

    private static DataReference parseReference(String reference) {
        if (!reference.equals(reference.trim())) throw new IllegalArgumentException("Testdata reference must not contain surrounding whitespace");
        int dot = reference.indexOf('.'), bracket = reference.indexOf('[');
        int separator = dot < 0 ? bracket : bracket < 0 ? dot : Math.min(dot, bracket);
        String id = separator < 0 ? reference : reference.substring(0, separator);
        if (!id.matches("[A-Za-z][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid logical testdata id in @{...}: " + id);
        return new DataReference(id, separator < 0 ? "" : reference.substring(separator));
    }

    private static String validateContext(String reference) {
        String path = reference.trim();
        if (path.isEmpty() || !path.equals(reference)) throw new IllegalArgumentException("Bootstrap Context reference must be a non-blank path");
        String normalized = path.toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("EXEC.INPUT") || normalized.startsWith("EXEC.INPUT.") || normalized.startsWith("EXEC.INPUT["))
            throw new IllegalArgumentException("Input mapping cannot depend on EXEC.INPUT while it is being constructed");
        return path;
    }

    private static final class Part {
        final int kind; final String value, id, path;
        private Part(int kind, String value, String id, String path) {
            this.kind = kind; this.value = value; this.id = id; this.path = path;
        }
        static Part literal(String value) { return new Part(0, value, null, null); }
        static Part data(DataReference reference) { return new Part(1, null, reference.id, reference.path); }
        static Part context(String value) { return new Part(2, value, null, null); }
    }

    private static final class DataReference {
        final String id, path;
        DataReference(String id, String path) { this.id = id; this.path = path; }
    }
}
