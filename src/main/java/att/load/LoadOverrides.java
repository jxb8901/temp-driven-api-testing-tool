package att.load;

import att.core.ExecutionOptions;
import att.core.BootstrapVariableOverrides;

import java.util.Collections;
import java.util.List;

/** Explicit command-line values that replace scenario values when supplied. */
public final class LoadOverrides {
    private final String users, arrivalRate, warmup, rampUp, duration, rampDown, thinkTime, maxConcurrent, overloadPolicy;
    private final List<String> variableOverrides;

    public LoadOverrides(String users, String arrivalRate, String warmup, String rampUp, String duration,
                         String rampDown, String thinkTime, String maxConcurrent, String overloadPolicy) {
        this(users, arrivalRate, warmup, rampUp, duration, rampDown, thinkTime, maxConcurrent, overloadPolicy,
                Collections.<String>emptyList());
    }

    public LoadOverrides(String users, String arrivalRate, String warmup, String rampUp, String duration,
                         String rampDown, String thinkTime, String maxConcurrent, String overloadPolicy,
                         List<String> variableOverrides) {
        this.users = users; this.arrivalRate = arrivalRate; this.warmup = warmup; this.rampUp = rampUp;
        this.duration = duration; this.rampDown = rampDown; this.thinkTime = thinkTime;
        this.maxConcurrent = maxConcurrent; this.overloadPolicy = overloadPolicy;
        this.variableOverrides = BootstrapVariableOverrides.copyAssignments(variableOverrides);
    }

    public static LoadOverrides none() { return new LoadOverrides(null, null, null, null, null, null, null, null, null); }
    public static LoadOverrides from(ExecutionOptions options) {
        return new LoadOverrides(options.loadUsers(), options.loadArrivalRate(), options.loadWarmup(),
                options.loadRampUp(), options.loadDuration(), options.loadRampDown(), options.loadThinkTime(),
                options.loadMaxConcurrent(), options.loadOverloadPolicy(), options.variableOverrides());
    }

    public boolean any() {
        return users != null || arrivalRate != null || warmup != null || rampUp != null || duration != null
                || rampDown != null || thinkTime != null || maxConcurrent != null || overloadPolicy != null
                || !variableOverrides.isEmpty();
    }
    public String users() { return users; }
    public String arrivalRate() { return arrivalRate; }
    public String warmup() { return warmup; }
    public String rampUp() { return rampUp; }
    public String duration() { return duration; }
    public String rampDown() { return rampDown; }
    public String thinkTime() { return thinkTime; }
    public String maxConcurrent() { return maxConcurrent; }
    public String overloadPolicy() { return overloadPolicy; }
    public List<String> variableOverrides() { return Collections.unmodifiableList(variableOverrides); }
    public boolean hasVariableOverrides() { return !variableOverrides.isEmpty(); }
}
