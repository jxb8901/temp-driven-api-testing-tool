package att.load;

import java.util.Map;

/** Low-overhead success/failure evidence policy for Load V1. */
public final class LoadEvidencePolicy {
    public enum Success { NONE, SAMPLE }
    public enum Failure { NONE, FULL }
    private final Success success; private final Failure failure; private final double sampleRate; private final int maxSamples;
    public LoadEvidencePolicy(Success success, Failure failure, double sampleRate, int maxSamples) { this.success = success; this.failure = failure; this.sampleRate = sampleRate; this.maxSamples = maxSamples; }
    public static LoadEvidencePolicy from(LoadScenario scenario) {
        Map<String, Object> map = scenario.evidence(); String mode = String.valueOf(map.getOrDefault("mode", "failures"));
        Success success = "sample".equalsIgnoreCase(String.valueOf(map.getOrDefault("success", ""))) || "samples".equalsIgnoreCase(mode) || "all".equalsIgnoreCase(mode) ? Success.SAMPLE : Success.NONE;
        Failure failure = "none".equalsIgnoreCase(String.valueOf(map.getOrDefault("failure", ""))) || "metrics".equalsIgnoreCase(mode) ? Failure.NONE : Failure.FULL;
        double sampleRate = number(map.get("sampleRate"), success == Success.SAMPLE ? 0.01 : 0.0);
        int maxSamples = (int) number(map.get("maxSamples"), 1000.0);
        return new LoadEvidencePolicy(success, failure, Math.max(0.0, Math.min(1.0, sampleRate)), Math.max(0, maxSamples));
    }
    private static double number(Object value, double fallback) { return value instanceof Number ? ((Number) value).doubleValue() : fallback; }
    public Success success() { return success; } public Failure failure() { return failure; } public double sampleRate() { return sampleRate; } public int maxSamples() { return maxSamples; }
    public boolean retain(LoadEvent event, int currentSamples) {
        if (event == null || currentSamples >= maxSamples) return false;
        if (event.dropped()) return false;
        if (!event.success()) return failure == Failure.FULL;
        if (success == Success.NONE || sampleRate <= 0.0) return false;
        long hash = Math.abs((long) event.iterationId().hashCode()); return (hash % 1000000L) < Math.round(sampleRate * 1000000.0);
    }
}
