package att.load;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable bounded-memory metric view consumed by thresholds and reports. */
public final class LoadMetricsSnapshot {
    private final Map<String, Object> values;
    private final Map<String, Map<String, Object>> buckets;

    LoadMetricsSnapshot(Map<String, Object> values, Map<String, Map<String, Object>> buckets) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
        this.buckets = Collections.unmodifiableMap(new LinkedHashMap<String, Map<String, Object>>(buckets));
    }
    public Map<String, Object> values() { return values; }
    public Map<String, Map<String, Object>> buckets() { return buckets; }
    public long longValue(String key) { return ((Number) values.get(key)).longValue(); }
    public double doubleValue(String key) { return ((Number) values.get(key)).doubleValue(); }
    public Object value(String key) { return values.get(key); }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>(values);
        result.put("buckets", buckets);
        return result;
    }
}
