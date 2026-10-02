/* Author: Jeffrey + ChatGPT */
package att.template;

/** One-way presentation of a typed value; never reparses or mutates the value. */
public final class TypedValueFormatter {
    public String format(Object value, String format) {
        return format(value, format, java.util.Collections.<String>emptyList());
    }
    public String format(Object value, String format, java.util.List<String> secrets) {
        String normalized = format == null || format.trim().isEmpty() ? "text" : format.trim().toLowerCase(java.util.Locale.ROOT);
        Object printable = presentationCopy(value, secrets);
        if ("sqlplus".equals(normalized)) return new DbTextResultFormatter().format(printable);
        if (!java.util.Arrays.asList("json", "yaml", "xml", "text").contains(normalized))
            throw new IllegalArgumentException("format must be text, json, yaml, xml, or sqlplus: " + format);
        return new att.exec.ObjectOutputCodec().encode(printable, normalized);
    }

    /** Safe presentation tree for retained metadata; the canonical value is never changed. */
    public Object presentationCopy(Object value, java.util.List<String> secrets) {
        java.util.List<String> redactions = new java.util.ArrayList<String>(secrets);
        redactions.removeIf(item -> item == null || item.isEmpty());
        redactions.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return representedChildren(value, "", new java.util.IdentityHashMap<Object, Boolean>(), redactions);
    }

    private Object representedChildren(Object value, String requestedFormat,
                                        java.util.IdentityHashMap<Object, Boolean> seen, java.util.List<String> redactions) {
        if (!redactions.isEmpty() && (value instanceof CharSequence || value instanceof Character)) {
            String safe = String.valueOf(value);
            for (String secret : redactions) safe = safe.replace(secret, "[REDACTED_SECRET]");
            return safe;
        }
        if (value instanceof java.util.Map) {
            if (seen.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cannot format a cyclic typed value");
            java.util.Map<Object, Object> result = new java.util.LinkedHashMap<Object, Object>();
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) value).entrySet())
                result.put(representedChildren(entry.getKey(), requestedFormat, seen, redactions), representedChildren(entry.getValue(), requestedFormat, seen, redactions));
            seen.remove(value);
            return result;
        }
        if (value instanceof Iterable) {
            if (seen.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cannot format a cyclic typed value");
            java.util.List<Object> result = new java.util.ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(representedChildren(item, requestedFormat, seen, redactions));
            seen.remove(value);
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            if (seen.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cannot format a cyclic typed value");
            int length = java.lang.reflect.Array.getLength(value);
            java.util.List<Object> result = new java.util.ArrayList<Object>(length);
            for (int index = 0; index < length; index++)
                result.add(representedChildren(java.lang.reflect.Array.get(value, index), requestedFormat, seen, redactions));
            seen.remove(value);
            return result;
        }
        if (!redactions.isEmpty() && value != null) {
            String text = String.valueOf(value), safe = text;
            for (String secret : redactions) safe = safe.replace(secret, "[REDACTED_SECRET]");
            if (!safe.equals(text)) return safe;
        }
        return value;
    }
}
