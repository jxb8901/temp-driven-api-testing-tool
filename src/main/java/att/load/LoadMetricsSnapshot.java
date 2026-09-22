package att.load;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable bounded-memory metric view consumed by thresholds and reports. */
public final class LoadMetricsSnapshot {
    private final Map<String, Object> values;
    private final Map<String, Map<String, Object>> buckets;
    private final Map<String, Map<String, Object>> phases;

    LoadMetricsSnapshot(Map<String, Object> values, Map<String, Map<String, Object>> buckets) {
        this(values, buckets, Collections.<String, Map<String, Object>>emptyMap());
    }

    LoadMetricsSnapshot(Map<String, Object> values, Map<String, Map<String, Object>> buckets,
                        Map<String, Map<String, Object>> phases) {
        Map<String, Object> copiedValues = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : values.entrySet()) copiedValues.put(entry.getKey(), immutableCopy(entry.getValue()));
        this.values = Collections.unmodifiableMap(copiedValues);
        Map<String, Map<String, Object>> copiedBuckets = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : buckets.entrySet()) {
            copiedBuckets.put(entry.getKey(), immutableMap(entry.getValue()));
        }
        this.buckets = Collections.unmodifiableMap(copiedBuckets);
        Map<String, Map<String, Object>> copiedPhases = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : phases.entrySet()) {
            copiedPhases.put(entry.getKey(), immutableMap(entry.getValue()));
        }
        this.phases = Collections.unmodifiableMap(copiedPhases);
    }
    @SuppressWarnings("unchecked")
    private static Object immutableCopy(Object value) {
        if (value instanceof Map) {
            Map<Object, Object> copied = new LinkedHashMap<Object, Object>();
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) copied.put(entry.getKey(), immutableCopy(entry.getValue()));
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof java.util.List) return Collections.unmodifiableList(new java.util.ArrayList<Object>((java.util.List<Object>) value));
        return value;
    }
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        Map<String, Object> copied = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : value.entrySet()) copied.put(entry.getKey(), immutableCopy(entry.getValue()));
        return Collections.unmodifiableMap(copied);
    }
    public Map<String, Object> values() { return values; }
    public Map<String, Map<String, Object>> buckets() { return buckets; }
    public Map<String, Map<String, Object>> phases() { return phases; }
    public long longValue(String key) { return ((Number) values.get(key)).longValue(); }
    public double doubleValue(String key) { return ((Number) values.get(key)).doubleValue(); }
    public Object value(String key) { return values.get(key); }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>(values);
        result.put("buckets", buckets);
        result.put("phases", phases);
        return result;
    }
}
