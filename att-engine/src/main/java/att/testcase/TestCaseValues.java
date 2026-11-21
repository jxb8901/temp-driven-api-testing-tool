package att.testcase;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded immutable snapshots of the JSON-compatible values used by native Test Cases. */
final class TestCaseValues {
    static final int MAX_VALUE_DEPTH = 64;
    static final int MAX_VALUE_NODES = 100000;

    private TestCaseValues() { }

    static Map<String,Object> immutableObject(Object value, String field) {
        if (!(value instanceof Map<?,?>)) throw new IllegalArgumentException(field + " must be an object");
        Counter counter = new Counter(MAX_VALUE_NODES);
        Object copied = copy(value, field, 0, counter, new IdentityHashMap<Object,Boolean>());
        @SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>) copied;
        return result;
    }

    static Object immutableValue(Object value, String field) {
        return copy(value, field, 0, new Counter(MAX_VALUE_NODES), new IdentityHashMap<Object,Boolean>());
    }

    static Map<String,Object> object(Object value, String field) {
        if (!(value instanceof Map<?,?>)) throw new IllegalArgumentException(field + " must be an object");
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (Map.Entry<?,?> entry : ((Map<?,?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException(field + " keys must be strings");
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    static Map<String,Object> extensionFields(Map<String,Object> values) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (Map.Entry<String,Object> entry : values.entrySet())
            if (entry.getKey().startsWith("x-")) result.put(entry.getKey(), entry.getValue());
        return result.isEmpty() ? Collections.<String,Object>emptyMap()
                : immutableObject(result, "extensions");
    }

    private static Object copy(Object value, String field, int depth, Counter counter,
                               IdentityHashMap<Object,Boolean> active) {
        counter.consume(field);
        if (depth > MAX_VALUE_DEPTH) throw new IllegalArgumentException(field + " exceeds the maximum nesting depth");
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) return value;
        if (value instanceof Float) {
            float number = ((Float) value).floatValue();
            if (Float.isInfinite(number) || Float.isNaN(number)) throw new IllegalArgumentException(field + " contains a non-finite number");
            return value;
        }
        if (value instanceof Double) {
            double number = ((Double) value).doubleValue();
            if (Double.isInfinite(number) || Double.isNaN(number)) throw new IllegalArgumentException(field + " contains a non-finite number");
            return value;
        }
        if (active.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException(field + " contains a recursive YAML alias");
        try {
            if (value instanceof Map<?,?>) {
                Map<String,Object> result = new LinkedHashMap<String,Object>();
                for (Map.Entry<?,?> entry : ((Map<?,?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException(field + " keys must be strings");
                    String key = (String) entry.getKey();
                    result.put(key, copy(entry.getValue(), field + "." + key, depth + 1, counter, active));
                }
                return Collections.unmodifiableMap(result);
            }
            if (value instanceof List<?>) {
                List<Object> result = new ArrayList<Object>();
                int index = 0;
                for (Object item : (List<?>) value) result.add(copy(item, field + "[" + index++ + "]", depth + 1, counter, active));
                return Collections.unmodifiableList(result);
            }
        } finally {
            active.remove(value);
        }
        throw new IllegalArgumentException(field + " contains an unsupported value type");
    }

    private static final class Counter {
        private int remaining;
        Counter(int remaining) { this.remaining = remaining; }
        void consume(String field) {
            if (--remaining < 0) throw new IllegalArgumentException(field + " exceeds the maximum value count");
        }
    }
}
