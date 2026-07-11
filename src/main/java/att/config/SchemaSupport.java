package att.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Small strict-schema primitives shared by YAML document loaders. */
public final class SchemaSupport {
    private SchemaSupport() {}

    public static void requireVersion(Map<?, ?> map, String expected, String owner) {
        Object value = map.get("schemaVersion");
        if (!(value instanceof String) || !expected.equals(value)) {
            throw new IllegalArgumentException(owner + ".schemaVersion must be exactly '" + expected + "'");
        }
    }

    public static void rejectUnknown(Map<?, ?> map, String owner, String... allowed) {
        Set<String> names = new LinkedHashSet<String>(Arrays.asList(allowed));
        for (Object keyValue : map.keySet()) {
            String key = String.valueOf(keyValue);
            if (!names.contains(key) && !key.startsWith("x-")) throw new IllegalArgumentException("Unknown field " + owner + "." + key);
        }
    }

    public static Map<?, ?> map(Object value, String owner) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(owner + " must be a map");
        return (Map<?, ?>) value;
    }

    public static String string(Object value, String owner, boolean required) {
        if (value == null && !required) return "";
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw new IllegalArgumentException(owner + " must be a non-blank string");
        return (String) value;
    }
}
