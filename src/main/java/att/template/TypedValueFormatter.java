/* Author: Jeffrey + ChatGPT */
package att.template;

/** One-way presentation of a typed value; never reparses or mutates the value. */
public final class TypedValueFormatter {
    public String format(Object value, String format) {
        String normalized = format == null || format.trim().isEmpty() ? "text" : format.trim().toLowerCase(java.util.Locale.ROOT);
        if (value instanceof DocumentValue) {
            DocumentValue document = (DocumentValue) value;
            if (format == null || format.trim().isEmpty() || document.format().equalsIgnoreCase(format.trim())) return document.text();
            throw new IllegalArgumentException("Cannot format DocumentValue " + document.format() + " as " + format + "; cross-format conversion is not implicit");
        }
        Object printable = representedChildren(value, normalized,
                new java.util.IdentityHashMap<Object, Boolean>());
        if ("sqlplus".equals(normalized)) return new DbTextResultFormatter().format(printable);
        if (!java.util.Arrays.asList("json", "yaml", "xml", "text").contains(normalized))
            throw new IllegalArgumentException("format must be json, yaml, xml, text, or sqlplus: " + format);
        return new att.exec.ObjectOutputCodec().encode(printable, normalized);
    }

    private Object representedChildren(Object value, String requestedFormat,
                                        java.util.IdentityHashMap<Object, Boolean> seen) {
        if (value instanceof DocumentValue) {
            DocumentValue document = (DocumentValue) value;
            if (!document.format().equals(requestedFormat))
                throw new IllegalArgumentException("Cannot format DocumentValue " + document.format() + " as " + requestedFormat + "; cross-format conversion is not implicit");
            return document.text();
        }
        if (value instanceof java.util.Map) {
            if (seen.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cannot format a cyclic typed value");
            java.util.Map<Object, Object> result = new java.util.LinkedHashMap<Object, Object>();
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) value).entrySet())
                result.put(entry.getKey(), representedChildren(entry.getValue(), requestedFormat, seen));
            seen.remove(value);
            return result;
        }
        if (value instanceof Iterable) {
            if (seen.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cannot format a cyclic typed value");
            java.util.List<Object> result = new java.util.ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(representedChildren(item, requestedFormat, seen));
            seen.remove(value);
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            java.util.List<Object> result = new java.util.ArrayList<Object>(length);
            for (int index = 0; index < length; index++)
                result.add(representedChildren(java.lang.reflect.Array.get(value, index), requestedFormat, seen));
            return result;
        }
        return value;
    }
}
