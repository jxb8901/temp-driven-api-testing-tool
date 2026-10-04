package att.load;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/** Immutable normalized workload used by active workloads-based load scenarios. */
public final class LoadWorkload {
    private final String id;
    private final String targetType;
    private final String targetId;
    private final Map<String, Object> targetArguments;
    private final Map<String, Object> inputs;
    private final Map<String, Object> vars;
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
    private final Map<String, Object> testdata;
    private final int sourceIndex;
    private final List<LoadMixEntry> mix;

    public LoadWorkload(String id, String targetType, String targetId, Map<String, Object> targetArguments,
                        Map<String, Object> inputs, LoadScenario.Model model, int users,
                        double arrivalRatePerSecond, String arrivalRate, Duration warmup, Duration rampUp,
                        Duration duration, Duration rampDown, ThinkTimePolicy thinkTimePolicy,
                        int maxConcurrent, String overloadPolicy, Map<String, Object> thresholds) {
        this(id, targetType, targetId, targetArguments, inputs, Collections.<String, Object>emptyMap(), model,
                users, arrivalRatePerSecond, arrivalRate, warmup, rampUp, duration, rampDown, thinkTimePolicy,
                maxConcurrent, overloadPolicy, thresholds, -1);
    }

    public LoadWorkload(String id, String targetType, String targetId, Map<String, Object> targetArguments,
                        Map<String, Object> inputs, Map<String, Object> vars, LoadScenario.Model model, int users,
                        double arrivalRatePerSecond, String arrivalRate, Duration warmup, Duration rampUp,
                        Duration duration, Duration rampDown, ThinkTimePolicy thinkTimePolicy,
                        int maxConcurrent, String overloadPolicy, Map<String, Object> thresholds) {
        this(id, targetType, targetId, targetArguments, inputs, vars, model, users, arrivalRatePerSecond,
                arrivalRate, warmup, rampUp, duration, rampDown, thinkTimePolicy, maxConcurrent, overloadPolicy,
                thresholds, -1);
    }

    public LoadWorkload(String id, String targetType, String targetId, Map<String, Object> targetArguments,
                        Map<String, Object> inputs, Map<String, Object> vars, LoadScenario.Model model, int users,
                        double arrivalRatePerSecond, String arrivalRate, Duration warmup, Duration rampUp,
                        Duration duration, Duration rampDown, ThinkTimePolicy thinkTimePolicy,
                        int maxConcurrent, String overloadPolicy, Map<String, Object> thresholds, int sourceIndex) {
        this(id, targetType, targetId, targetArguments, inputs, vars, model, users, arrivalRatePerSecond,
                arrivalRate, warmup, rampUp, duration, rampDown, thinkTimePolicy, maxConcurrent, overloadPolicy,
                thresholds, Collections.<String, Object>emptyMap(), sourceIndex);
    }

    public LoadWorkload(String id, String targetType, String targetId, Map<String, Object> targetArguments,
                        Map<String, Object> inputs, Map<String, Object> vars, LoadScenario.Model model, int users,
                        double arrivalRatePerSecond, String arrivalRate, Duration warmup, Duration rampUp,
                        Duration duration, Duration rampDown, ThinkTimePolicy thinkTimePolicy,
                        int maxConcurrent, String overloadPolicy, Map<String, Object> thresholds,
                        Map<String, Object> testdata, int sourceIndex) {
        this(id, targetType, targetId, targetArguments, inputs, vars, model, users, arrivalRatePerSecond,
                arrivalRate, warmup, rampUp, duration, rampDown, thinkTimePolicy, maxConcurrent, overloadPolicy,
                thresholds, testdata, sourceIndex, Collections.<LoadMixEntry>emptyList());
    }

    public LoadWorkload(String id, String targetType, String targetId, Map<String, Object> targetArguments,
                        Map<String, Object> inputs, Map<String, Object> vars, LoadScenario.Model model, int users,
                        double arrivalRatePerSecond, String arrivalRate, Duration warmup, Duration rampUp,
                        Duration duration, Duration rampDown, ThinkTimePolicy thinkTimePolicy,
                        int maxConcurrent, String overloadPolicy, Map<String, Object> thresholds,
                        Map<String, Object> testdata, int sourceIndex, List<LoadMixEntry> mix) {
        this.id = id;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetArguments = immutable(targetArguments);
        this.inputs = immutable(inputs);
        this.vars = immutable(vars);
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
        this.testdata = immutable(testdata);
        this.sourceIndex = sourceIndex;
        this.mix = mix == null ? Collections.<LoadMixEntry>emptyList()
                : Collections.unmodifiableList(new ArrayList<LoadMixEntry>(mix));
    }

    public String id() { return id; }
    public String targetType() { return targetType; }
    public String targetId() { return targetId; }
    public Map<String, Object> targetArguments() { return targetArguments; }
    public Map<String, Object> inputs() { return inputs; }
    public Map<String, Object> vars() { return vars; }
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
    public Map<String, Object> testdata() { return testdata; }
    /** Zero-based index in the source scenario, or -1 for in-memory promoted workloads. */
    public int sourceIndex() { return sourceIndex; }
    public List<LoadMixEntry> mix() { return mix; }
    public boolean mixed() { return !mix.isEmpty(); }
    LoadWorkload forMixEntry(LoadMixEntry entry) {
        Map<String, Object> mergedInputs = new LinkedHashMap<String, Object>(inputs); mergedInputs.putAll(entry.inputs());
        Map<String, Object> mergedVars = new LinkedHashMap<String, Object>(vars); mergedVars.putAll(entry.vars());
        return new LoadWorkload(id, entry.targetType(), entry.targetId(), entry.targetArguments(), mergedInputs,
                mergedVars, model, users, arrivalRatePerSecond, arrivalRate, warmup, rampUp, duration, rampDown,
                thinkTimePolicy, maxConcurrent, overloadPolicy, thresholds, testdata, sourceIndex);
    }

    Map<String, Object> toMap(boolean includeExecutionData, boolean summary) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        if (mixed()) {
            List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
            for (LoadMixEntry entry : mix) entries.add(entry.toMap(includeExecutionData));
            result.put("mix", entries);
        } else {
            Map<String, Object> target = new LinkedHashMap<String, Object>();
            target.put("type", targetType);
            target.put("id", targetId);
            if (includeExecutionData && !targetArguments.isEmpty()) target.put("arguments", targetArguments);
            result.put("target", target);
        }
        if (includeExecutionData && !inputs.isEmpty()) result.put("inputs", inputs);
        if (includeExecutionData && !vars.isEmpty()) result.put("vars", vars);
        if (!testdata.isEmpty()) result.put("testdata", testdata);
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
