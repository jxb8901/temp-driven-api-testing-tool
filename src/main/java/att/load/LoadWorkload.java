package att.load;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable normalized workload used by both att-load/v1.0 and v1.1 scenarios. */
public final class LoadWorkload {
    private final String id;
    private final String targetType;
    private final String targetId;
    private final Map<String, Object> targetArguments;
    private final Map<String, Object> inputs;
    private final LoadScenario.Model model;
    private final int users;
    private final double arrivalRatePerSecond;
    private final String arrivalRate;
    private final Duration warmup;
    private final Duration rampUp;
    private final Duration duration;
    private final Duration rampDown;
    private final ThinkTimePolicy thinkTimePolicy;
    private final int maxConcurrent;
    private final String overloadPolicy;
    private final Map<String, Object> thresholds;

    public LoadWorkload(String id, String targetType, String targetId, Map<String, Object> targetArguments,
                        Map<String, Object> inputs, LoadScenario.Model model, int users,
                        double arrivalRatePerSecond, String arrivalRate, Duration warmup, Duration rampUp,
                        Duration duration, Duration rampDown, ThinkTimePolicy thinkTimePolicy,
                        int maxConcurrent, String overloadPolicy, Map<String, Object> thresholds) {
        this.id = id;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetArguments = immutable(targetArguments);
        this.inputs = immutable(inputs);
        this.model = model;
        this.users = users;
        this.arrivalRatePerSecond = arrivalRatePerSecond;
        this.arrivalRate = arrivalRate;
        this.warmup = warmup == null ? Duration.ZERO : warmup;
        this.rampUp = rampUp == null ? Duration.ZERO : rampUp;
        this.duration = duration == null ? Duration.ZERO : duration;
        this.rampDown = rampDown == null ? Duration.ZERO : rampDown;
        this.thinkTimePolicy = thinkTimePolicy == null ? ThinkTimePolicy.fixed(Duration.ZERO) : thinkTimePolicy;
        this.maxConcurrent = maxConcurrent;
        this.overloadPolicy = overloadPolicy;
        this.thresholds = immutable(thresholds);
    }

    public String id() { return id; }
    public String targetType() { return targetType; }
    public String targetId() { return targetId; }
    public Map<String, Object> targetArguments() { return targetArguments; }
    public Map<String, Object> inputs() { return inputs; }
    public LoadScenario.Model model() { return model; }
    public int users() { return users; }
    public double arrivalRatePerSecond() { return arrivalRatePerSecond; }
    public String arrivalRate() { return arrivalRate; }
    public Duration warmup() { return warmup; }
    public Duration rampUp() { return rampUp; }
    public Duration duration() { return duration; }
    public Duration rampDown() { return rampDown; }
    public ThinkTimePolicy thinkTimePolicy() { return thinkTimePolicy; }
    public int maxConcurrent() { return maxConcurrent; }
    public String overloadPolicy() { return overloadPolicy; }
    public Map<String, Object> thresholds() { return thresholds; }

    Map<String, Object> toMap(boolean includeExecutionData, boolean summary) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("type", targetType);
        target.put("id", targetId);
        if (includeExecutionData && !targetArguments.isEmpty()) target.put("arguments", targetArguments);
        result.put("target", target);
        if (includeExecutionData && !inputs.isEmpty()) result.put("inputs", inputs);
        Map<String, Object> load = new LinkedHashMap<String, Object>();
        if (model == LoadScenario.Model.CLOSED) load.put("users", users);
        else load.put("arrivalRate", arrivalRate);
        load.put("warmup", ThinkTimePolicy.format(warmup.toMillis()));
        load.put("rampUp", ThinkTimePolicy.format(rampUp.toMillis()));
        load.put("duration", ThinkTimePolicy.format(duration.toMillis()));
        load.put("rampDown", ThinkTimePolicy.format(rampDown.toMillis()));
        if (model == LoadScenario.Model.ARRIVAL_RATE) {
            load.put("maxConcurrent", maxConcurrent);
            load.put("overloadPolicy", overloadPolicy);
        }
        if (summary) load.put("model", model.wireName());
        result.put("load", load);
        if (model == LoadScenario.Model.CLOSED) {
            Map<String, Object> execution = new LinkedHashMap<String, Object>();
            execution.put("thinkTime", thinkTimePolicy.toConfigValue());
            if (summary) execution.put("thinkTimePolicy", thinkTimePolicy.summary());
            result.put("execution", execution);
        }
        if (!thresholds.isEmpty()) result.put("thresholds", thresholds);
        return result;
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        return LoadIsolation.deepImmutableMap(source);
    }
}
