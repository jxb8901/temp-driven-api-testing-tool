package att.load;

import java.util.Map;

/** Resolved success/failure evidence policy for a Load run. */
public final class LoadEvidencePolicy {
    public enum Success { NONE, SAMPLE, FULL }
    public enum Failure { NONE, FULL }
    private final Success success; private final Failure failure; private final double sampleRate; private final int maxSamples;
    public LoadEvidencePolicy(Success success, Failure failure, double sampleRate, int maxSamples) { this.success = success; this.failure = failure; this.sampleRate = sampleRate; this.maxSamples = maxSamples; }
    public static LoadEvidencePolicy from(LoadScenario scenario) {
        Map<String, Object> map = scenario.evidence();
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
        return new LoadEvidencePolicy(success, failure, Math.max(0.0, Math.min(1.0, sampleRate)), Math.max(0, maxSamples));
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
        if (event == null || currentSamples >= maxSamples) return false;
        if (event.dropped() || !event.completed()) return false;
        if (!event.success()) return failure == Failure.FULL;
        return retainSuccess(event.iterationId());
    }
}
