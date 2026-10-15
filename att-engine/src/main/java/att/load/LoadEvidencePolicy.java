package att.load;

import java.util.Map;

/** Resolved success/failure evidence policy for a Load run. */
public final class LoadEvidencePolicy {
    public enum Success { NONE, SAMPLE, FULL }
    public enum Failure { NONE, FULL }
    private final Success success; private final Failure failure; private final double sampleRate; private final int maxSamples;
    private final boolean splitQuotas;
    private final int maxSuccessSamples, maxFailureSamples;
    public LoadEvidencePolicy(Success success, Failure failure, double sampleRate, int maxSamples) {
        this(success, failure, sampleRate, maxSamples, false, maxSamples, maxSamples);
    }
    private LoadEvidencePolicy(Success success, Failure failure, double sampleRate, int maxSamples,
                               boolean splitQuotas, int maxSuccessSamples, int maxFailureSamples) {
        this.success=success;this.failure=failure;this.sampleRate=sampleRate;this.maxSamples=maxSamples;
        this.splitQuotas=splitQuotas;this.maxSuccessSamples=maxSuccessSamples;this.maxFailureSamples=maxFailureSamples;
    }
    public static LoadEvidencePolicy from(LoadScenario scenario) {
        Map<String, Object> map = scenario.evidence();
        validateQuotas(map);
        String mode = String.valueOf(map.getOrDefault("mode", "failures"));
        Success success = modeSuccess(mode);
        if (map.containsKey("success")) success = explicitSuccess(map.get("success"));
        Failure failure = modeFailure(mode);
        if (map.containsKey("failure")) failure = explicitFailure(map.get("failure"));
        double sampleRate = success == Success.SAMPLE
                ? number(map.get("sampleRate"), 0.01) : 0.0;
        boolean explicitCap = map.containsKey("maxSamples");
        boolean unlimitedByDefault = "all".equalsIgnoreCase(mode) || success == Success.FULL;
        int maxSamples = explicitCap ? (int) number(map.get("maxSamples"), 1000.0)
                : unlimitedByDefault ? Integer.MAX_VALUE : 1000;
        if(map.containsKey("maxFailureSamples")) {
            int successCap=map.containsKey("maxSuccessSamples")?quota(map.get("maxSuccessSamples"),"maxSuccessSamples")
                    :map.containsKey("maxSamples")?quota(map.get("maxSamples"),"maxSamples"):1000;
            return new LoadEvidencePolicy(success,failure,Math.max(0.0,Math.min(1.0,sampleRate)),maxSamples,
                    true,successCap,quota(map.get("maxFailureSamples"),"maxFailureSamples"));
        }
        return new LoadEvidencePolicy(success, failure, Math.max(0.0, Math.min(1.0, sampleRate)), Math.max(0, maxSamples));
    }
    /** Semantic validation also covers programmatically constructed scenarios and inline policies. */
    public static void validateQuotas(Map<String,Object> map) {
        if(map.containsKey("maxSuccessSamples") && !map.containsKey("maxFailureSamples"))
            throw new IllegalArgumentException("evidence.maxSuccessSamples requires an explicit finite evidence.maxFailureSamples");
        if(!map.containsKey("maxFailureSamples"))return;
        quota(map.get("maxFailureSamples"),"maxFailureSamples");
        if(map.containsKey("maxSuccessSamples") && map.containsKey("maxSamples"))
            throw new IllegalArgumentException("Use evidence.maxSuccessSamples or evidence.maxSamples as the split success cap, not both");
        if(map.containsKey("maxSuccessSamples"))quota(map.get("maxSuccessSamples"),"maxSuccessSamples");
        if(map.containsKey("maxSamples"))quota(map.get("maxSamples"),"maxSamples");
    }
    private static int quota(Object value,String field) {
        if(!(value instanceof Number))throw new IllegalArgumentException("evidence."+field+" must be an integer from 0 to 1000000");
        double number=((Number)value).doubleValue();
        if(!Double.isFinite(number)||number<0||number>1000000||number!=Math.rint(number))
            throw new IllegalArgumentException("evidence."+field+" must be an integer from 0 to 1000000");
        return (int)number;
    }
    public boolean splitQuotas() { return splitQuotas; }
    public int maxSuccessSamples() { return maxSuccessSamples; }
    public int maxFailureSamples() { return maxFailureSamples; }
    public Map<String,Object> toMap() {
        Map<String,Object> result=new java.util.LinkedHashMap<String,Object>();
        result.put("success",success.name().toLowerCase(java.util.Locale.ROOT));
        result.put("failure",failure.name().toLowerCase(java.util.Locale.ROOT));result.put("sampleRate",sampleRate);
        result.put("quotaMode",splitQuotas?"independent":"legacyCombined");
        if(splitQuotas){result.put("maxSuccessSamples",maxSuccessSamples);result.put("maxFailureSamples",maxFailureSamples);}
        else result.put("maxSamples",maxSamples);
        return result;
    }
    private static Success modeSuccess(String mode) {
        if ("samples".equalsIgnoreCase(mode)) return Success.SAMPLE;
        if ("all".equalsIgnoreCase(mode)) return Success.FULL;
        return Success.NONE;
    }
    private static Failure modeFailure(String mode) {
        return "metrics".equalsIgnoreCase(mode) ? Failure.NONE : Failure.FULL;
    }
    private static Success explicitSuccess(Object value) {
        String name = String.valueOf(value);
        if ("sample".equalsIgnoreCase(name)) return Success.SAMPLE;
        if ("full".equalsIgnoreCase(name)) return Success.FULL;
        return Success.NONE;
    }
    private static Failure explicitFailure(Object value) {
        return "full".equalsIgnoreCase(String.valueOf(value)) ? Failure.FULL : Failure.NONE;
    }
    private static double number(Object value, double fallback) { return value instanceof Number ? ((Number) value).doubleValue() : fallback; }
    public Success success() { return success; } public Failure failure() { return failure; } public double sampleRate() { return sampleRate; } public int maxSamples() { return maxSamples; }
    public boolean retainSuccess(String iterationId) {
        if (iterationId == null) return false;
        return success == Success.FULL || sampleSuccess(iterationId);
    }
    public boolean retainFailure() { return failure == Failure.FULL; }
    public boolean sampleSuccess(String iterationId) {
        if (iterationId == null || success != Success.SAMPLE || sampleRate <= 0.0) return false;
        long hash = ((long) iterationId.hashCode()) & 0xffffffffL;
        return (hash % 1000000L) < Math.round(sampleRate * 1000000.0);
    }
    public boolean retain(LoadEvent event, int currentSamples) {
        if (event == null || currentSamples >= (splitQuotas ? (event.success()?maxSuccessSamples:maxFailureSamples) : maxSamples)) return false;
        if (event.dropped() || !event.completed()) return false;
        if (!event.success()) return failure == Failure.FULL;
        return retainSuccess(event.iterationId());
    }
}
