package att.load;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, semantically validated att-load/v1.0 scenario. */
public final class LoadScenario {
    public enum Model {
        CLOSED("closed"), ARRIVAL_RATE("arrivalRate");
        private final String wireName;
        Model(String wireName) { this.wireName = wireName; }
        public String wireName() { return wireName; }
    }

    private final Path source;
    private final String targetType, targetId;
    private final Map<String, Object> targetArguments, inputs, thresholds, evidence;
    private final Model model;
    private final int users, maxConcurrent;
    private final double arrivalRatePerSecond;
    private final String arrivalRate;
    private final Duration warmup, rampUp, duration, rampDown, thinkTime;
    private final String overloadPolicy;

    LoadScenario(Path source, String targetType, String targetId, Map<String, Object> targetArguments,
                 Map<String, Object> inputs, Model model, int users, double arrivalRatePerSecond,
                 String arrivalRate, Duration warmup, Duration rampUp, Duration duration, Duration rampDown,
                 Duration thinkTime, int maxConcurrent, String overloadPolicy,
                 Map<String, Object> thresholds, Map<String, Object> evidence) {
        this.source = source; this.targetType = targetType; this.targetId = targetId;
        this.targetArguments = immutable(targetArguments); this.inputs = immutable(inputs);
        this.model = model; this.users = users; this.arrivalRatePerSecond = arrivalRatePerSecond;
        this.arrivalRate = arrivalRate; this.warmup = warmup; this.rampUp = rampUp; this.duration = duration;
        this.rampDown = rampDown; this.thinkTime = thinkTime; this.maxConcurrent = maxConcurrent;
        this.overloadPolicy = overloadPolicy; this.thresholds = immutable(thresholds); this.evidence = immutable(evidence);
    }

    public Path source() { return source; }
    public String targetType() { return targetType; }
    public String targetId() { return targetId; }
    public Map<String, Object> targetArguments() { return targetArguments; }
    public Map<String, Object> inputs() { return inputs; }
    public Model model() { return model; }
    public int users() { return users; }
    public double arrivalRatePerSecond() { return arrivalRatePerSecond; }
    public String arrivalRate() { return arrivalRate; }
    public Duration warmup() { return warmup; }
    public Duration rampUp() { return rampUp; }
    public Duration duration() { return duration; }
    public Duration rampDown() { return rampDown; }
    public Duration thinkTime() { return thinkTime; }
    public int maxConcurrent() { return maxConcurrent; }
    public String overloadPolicy() { return overloadPolicy; }
    public Map<String, Object> thresholds() { return thresholds; }
    public Map<String, Object> evidence() { return evidence; }

    public Map<String, Object> toMap() {
        return toMap(true);
    }

    /** Returns the report-safe scenario projection; business inputs and tool arguments are never persisted. */
    public Map<String, Object> toSummaryMap() {
        Map<String, Object> result = toMap(false);
        @SuppressWarnings("unchecked") Map<String, Object> load = (Map<String, Object>) result.get("load");
        load.put("model", model.wireName());
        return result;
    }

    private Map<String, Object> toMap(boolean includeExecutionData) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", att.Version.LOAD_SCHEMA);
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("type", targetType); target.put("id", targetId);
        if (includeExecutionData && !targetArguments.isEmpty()) target.put("arguments", targetArguments);
        result.put("target", target);
        if (includeExecutionData && !inputs.isEmpty()) result.put("inputs", inputs);
        Map<String, Object> load = new LinkedHashMap<String, Object>();
        if (model == Model.CLOSED) load.put("users", users); else load.put("arrivalRate", arrivalRate);
        load.put("warmup", format(warmup)); load.put("rampUp", format(rampUp));
        load.put("duration", format(duration)); load.put("rampDown", format(rampDown));
        if (model == Model.ARRIVAL_RATE) { load.put("maxConcurrent", maxConcurrent); load.put("overloadPolicy", overloadPolicy); }
        result.put("load", load);
        if (model == Model.CLOSED) {
            Map<String, Object> execution = new LinkedHashMap<String, Object>();
            execution.put("thinkTime", format(thinkTime));
            result.put("execution", execution);
        }
        if (!thresholds.isEmpty()) result.put("thresholds", thresholds);
        if (!evidence.isEmpty()) result.put("evidence", evidence);
        return result;
    }

    private static String format(Duration value) {
        long millis = value.toMillis();
        if (millis == 0L) return "0ms";
        if (millis % 3600000L == 0) return (millis / 3600000L) + "h";
        if (millis % 60000L == 0) return (millis / 60000L) + "m";
        if (millis % 1000L == 0) return (millis / 1000L) + "s";
        return millis + "ms";
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(deepMap(LoadIsolation.deepCopyMap(source)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) result.put(entry.getKey(), deepValue(entry.getValue()));
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Object deepValue(Object value) {
        if (value instanceof Map) return Collections.unmodifiableMap(deepMap((Map<String, Object>) value));
        if (value instanceof java.util.List) { java.util.List<Object> list = new java.util.ArrayList<Object>(); for (Object item : (java.util.List<?>) value) list.add(deepValue(item)); return Collections.unmodifiableList(list); }
        return value;
    }
}
