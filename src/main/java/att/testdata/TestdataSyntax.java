package att.testdata;

import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Syntax guard for testdata-only markers in input mappings and executable definitions. */
public final class TestdataSyntax {
    private static final Pattern REFERENCE = Pattern.compile("@\\{[^{}]+}");
    private TestdataSyntax() { }

    public static void rejectDirectReferences(Object value, String owner) {
        if (value instanceof String) {
            String text = (String) value;
            if (text.contains("@{") || text.contains("%{"))
                throw new IllegalArgumentException(owner + " cannot directly reference testdata; resolve it in testcase/debug/load input mapping and use EXEC.INPUT");
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                rejectDirectReferences(entry.getKey(), owner);
                rejectDirectReferences(entry.getValue(), owner);
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) rejectDirectReferences(item, owner);
        }
    }

    public static boolean containsReference(Object value) {
        if (value instanceof String) return REFERENCE.matcher((String) value).find();
        if (value instanceof Map) for (Object item : ((Map<?, ?>) value).values()) if (containsReference(item)) return true;
        if (value instanceof Iterable) for (Object item : (Iterable<?>) value) if (containsReference(item)) return true;
        return false;
    }

    /** Extracts logical ids referenced by input mappings without selecting records. */
    public static Set<String> references(Object value) {
        if (containsGeneratorMarker(value))
            throw new IllegalArgumentException("%{...} is reserved for generated testdata record templates");
        if (!containsInputMarker(value)) return java.util.Collections.emptySet();
        Set<String> result = new LinkedHashSet<String>();
        collectReferences(value, result);
        return java.util.Collections.unmodifiableSet(result);
    }

    public static List<Reference> referenceExpressions(Object value) {
        Set<String> ids = references(value); // validates all marker syntax first
        if (ids.isEmpty()) return java.util.Collections.emptyList();
        List<Reference> result = new ArrayList<Reference>();
        collectReferenceExpressions(value, result);
        return java.util.Collections.unmodifiableList(result);
    }

    private static void collectReferenceExpressions(Object value, List<Reference> result) {
        if (value instanceof String) {
            String text = (String) value;
            Matcher matcher = REFERENCE.matcher(text);
            while (matcher.find()) {
                String reference = matcher.group().substring(2, matcher.group().length() - 1);
                int dot = reference.indexOf('.');
                int bracket = reference.indexOf('[');
                int end = dot < 0 ? bracket : bracket < 0 ? dot : Math.min(dot, bracket);
                String id = end < 0 ? reference : reference.substring(0, end);
                String path = end < 0 ? "" : reference.substring(end);
                if (path.startsWith(".")) path = path.substring(1);
                boolean embedded = matcher.start() != 0 || matcher.end() != text.length();
                result.add(new Reference(id, path, embedded));
            }
        } else if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) collectReferenceExpressions(item, result);
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) collectReferenceExpressions(item, result);
        }
    }

    public static final class Reference {
        private final String id;
        private final String path;
        private final boolean embedded;
        private Reference(String id, String path, boolean embedded) {
            this.id = id; this.path = path; this.embedded = embedded;
        }
        public String id() { return id; }
        public String path() { return path; }
        public boolean embedded() { return embedded; }
    }

    private static boolean containsGeneratorMarker(Object value) {
        if (value instanceof String) return ((String) value).contains("%{");
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) if (containsGeneratorMarker(item)) return true;
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) if (containsGeneratorMarker(item)) return true;
        }
        return false;
    }

    private static boolean containsInputMarker(Object value) {
        if (value instanceof String) {
            String text = (String) value;
            return text.contains("@{") || text.contains("${");
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) if (containsInputMarker(item)) return true;
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) if (containsInputMarker(item)) return true;
        }
        return false;
    }

    private static void collectReferences(Object value, Set<String> result) {
        if (value instanceof String) {
            String text = (String) value;
            rejectUnsupportedMappingMarkers(text);
            Matcher matcher = REFERENCE.matcher(text);
            int cursor = 0;
            while (matcher.find()) {
                int nextOpen = text.indexOf("@{", cursor);
                if (nextOpen >= 0 && nextOpen < matcher.start())
                    throw new IllegalArgumentException("Malformed @{testdata} reference in input mapping");
                String reference = matcher.group().substring(2, matcher.group().length() - 1);
                if (!reference.equals(reference.trim()))
                    throw new IllegalArgumentException("Testdata reference must not contain surrounding whitespace");
                int dot = reference.indexOf('.');
                int bracket = reference.indexOf('[');
                int end = dot < 0 ? bracket : bracket < 0 ? dot : Math.min(dot, bracket);
                String id = end < 0 ? reference : reference.substring(0, end);
                if (!id.matches("[A-Za-z][A-Za-z0-9_-]*"))
                    throw new IllegalArgumentException("Invalid logical testdata id in reference: @{" + reference + "}");
                result.add(id);
                cursor = matcher.end();
            }
            if (text.indexOf("@{", cursor) >= 0)
                throw new IllegalArgumentException("Malformed @{testdata} reference in input mapping");
        } else if (value instanceof Map) {
            // Map keys are literal input names; only authored values are mapped.
            for (Object item : ((Map<?, ?>) value).values()) collectReferences(item, result);
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) collectReferences(item, result);
        }
    }

    private static void rejectUnsupportedMappingMarkers(String text) {
        if (text.contains("%{") || text.contains("#{") || text.contains("&{"))
            throw new IllegalArgumentException("Input mapping supports only literals, @{...}, and ${Context} references");
        validateMarkerPairs(text, "@{", REFERENCE);
        validateMarkerPairs(text, "${", Pattern.compile("\\$\\{([^{}]+)}"));
        Matcher context = Pattern.compile("\\$\\{([^{}]+)}").matcher(text);
        while (context.find()) {
            String path = context.group(1);
            if (path.trim().isEmpty() || !path.equals(path.trim()))
                throw new IllegalArgumentException("Bootstrap Context reference must be a non-blank path");
            String normalized = path.toUpperCase(java.util.Locale.ROOT);
            if (normalized.equals("EXEC.INPUT") || normalized.startsWith("EXEC.INPUT.")
                    || normalized.startsWith("EXEC.INPUT["))
                throw new IllegalArgumentException("Input mapping cannot depend on EXEC.INPUT while it is being constructed");
        }
    }

    private static void validateMarkerPairs(String text, String opening, Pattern complete) {
        Matcher matcher = complete.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            int nextOpen = text.indexOf(opening, cursor);
            if (nextOpen >= 0 && nextOpen < matcher.start())
                throw new IllegalArgumentException("Malformed input mapping reference");
            cursor = matcher.end();
        }
        if (text.indexOf(opening, cursor) >= 0)
            throw new IllegalArgumentException("Malformed input mapping reference");
    }
}
