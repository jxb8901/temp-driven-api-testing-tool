package att.load;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One pre-resolved target choice in a closed workload's weighted transaction mix. */
public final class LoadMixEntry {
    private final String id, targetType, targetId;
    private final int weight;
    private final Map<String, Object> targetArguments, inputs, vars;

    public LoadMixEntry(String id, String targetType, String targetId, int weight,
                        Map<String, Object> targetArguments, Map<String, Object> inputs, Map<String, Object> vars) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Load mix entry id is required");
        if (weight < 1) throw new IllegalArgumentException("Load mix weight must be a positive integer");
        this.id = id; this.targetType = targetType; this.targetId = targetId; this.weight = weight;
        this.targetArguments = immutable(targetArguments); this.inputs = immutable(inputs); this.vars = immutable(vars);
    }
    public String id() { return id; }
    public String targetType() { return targetType; }
    public String targetId() { return targetId; }
    public int weight() { return weight; }
    public Map<String, Object> targetArguments() { return targetArguments; }
    public Map<String, Object> inputs() { return inputs; }
    public Map<String, Object> vars() { return vars; }
    Map<String, Object> toMap(boolean includeExecutionData) {
        Map<String, Object> item = new LinkedHashMap<String, Object>(); item.put("id", id); item.put("weight", weight);
        Map<String, Object> target = new LinkedHashMap<String, Object>(); target.put("type", targetType); target.put("id", targetId);
        if (includeExecutionData && !targetArguments.isEmpty()) target.put("arguments", targetArguments);
        item.put("target", target);
        if (includeExecutionData && !inputs.isEmpty()) item.put("inputs", inputs);
        if (includeExecutionData && !vars.isEmpty()) item.put("vars", vars);
        return item;
    }
    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null || source.isEmpty() ? Collections.<String, Object>emptyMap() : LoadIsolation.deepImmutableMap(source);
    }
}
