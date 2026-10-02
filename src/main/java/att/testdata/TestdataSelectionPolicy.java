package att.testdata;

import java.util.Map;

/** Immutable record selection policy shared by literal and generated descriptors. */
public final class TestdataSelectionPolicy {
    private final String strategy;
    private final String exhaustion;
    private final Long seed;

    public TestdataSelectionPolicy(String strategy, String exhaustion, Long seed) {
        this.strategy = strategy;
        this.exhaustion = exhaustion;
        this.seed = seed;
    }

    public String strategy() { return strategy; }
    public String exhaustion() { return exhaustion; }
    public Long seed() { return seed; }

    public static TestdataSelectionPolicy parse(Map<?, ?> map, String owner) {
        if (map == null) return null;
        Object strategy = map.get("strategy");
        if (!(strategy instanceof String) || !("sequential".equals(strategy)
                || "roundRobin".equals(strategy) || "random".equals(strategy)))
            throw new IllegalArgumentException(owner + ".strategy must be sequential, roundRobin, or random");
        Object exhaustion = map.get("exhaustion");
        String exhausted = exhaustion == null ? "error" : String.valueOf(exhaustion);
        if (!("error".equals(exhausted) || "recycle".equals(exhausted) || "stop".equals(exhausted)))
            throw new IllegalArgumentException(owner + ".exhaustion must be error, recycle, or stop");
        Long seed = null;
        Object rawSeed = map.get("seed");
        if (rawSeed != null) {
            if (!(rawSeed instanceof Number) || rawSeed instanceof Float || rawSeed instanceof Double)
                throw new IllegalArgumentException(owner + ".seed must be an integer");
            try { seed = Long.valueOf(new java.math.BigDecimal(String.valueOf(rawSeed)).longValueExact()); }
            catch (ArithmeticException | NumberFormatException error) {
                throw new IllegalArgumentException(owner + ".seed must be a 64-bit integer");
            }
        }
        return new TestdataSelectionPolicy(String.valueOf(strategy), exhausted, seed);
    }
}
