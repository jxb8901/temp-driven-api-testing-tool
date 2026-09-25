package att.load;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, dependency-free helpers for isolating mutable load iteration state. */
public final class LoadIsolation {
    private LoadIsolation() {}

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) result.add(deepCopy(item));
            return result;
        }
        if (value instanceof Collection) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Collection<?>) value) result.add(deepCopy(item));
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<Object>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) result.add(deepCopy(java.lang.reflect.Array.get(value, index)));
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>()
                : (Map<String, Object>) deepCopy(source);
    }

    @SuppressWarnings("unchecked")
    public static Object deepImmutable(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                result.put(String.valueOf(entry.getKey()), deepImmutable(entry.getValue()));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Collection) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Collection<?>) value) result.add(deepImmutable(item));
            return Collections.unmodifiableList(result);
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<Object>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++)
                result.add(deepImmutable(java.lang.reflect.Array.get(value, index)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepImmutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        return (Map<String, Object>) deepImmutable(source);
    }

    /** Safe, deterministic and collision-resistant physical directory name. */
    public static String workspaceName(String runId, String iterationId, long sequence) {
        String raw = (iterationId == null ? "iteration" : iterationId).replaceAll("[^A-Za-z0-9_.-]", "_");
        if (raw.length() > 80) raw = raw.substring(0, 80);
        return raw + "-" + shortHash(runId + "\u0000" + iterationId + "\u0000" + sequence);
    }

    public static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 8; index++) result.append(String.format("%02x", digest[index]));
            return result.toString();
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
