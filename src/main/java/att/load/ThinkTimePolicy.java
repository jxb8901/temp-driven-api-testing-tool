package att.load;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Normalized closed-VU think-time policy shared by scenario parsing and schedulers. */
public final class ThinkTimePolicy {
    public enum Type { FIXED, UNIFORM_RANGE }

    private final Type type;
    private final long minMillis;
    private final long maxMillis;

    private ThinkTimePolicy(Type type, long minMillis, long maxMillis) {
        if (minMillis < 0L || maxMillis < 0L) throw new IllegalArgumentException("think time must be non-negative");
        if (maxMillis < minMillis) throw new IllegalArgumentException("think time max must be greater than or equal to min");
        this.type = type;
        this.minMillis = minMillis;
        this.maxMillis = maxMillis;
    }

    public static ThinkTimePolicy fixed(Duration value) {
        if (value == null) return new ThinkTimePolicy(Type.FIXED, 0L, 0L);
        long millis;
        try { millis = value.toMillis(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("think time is too large", e); }
        return new ThinkTimePolicy(Type.FIXED, millis, millis);
    }

    public static ThinkTimePolicy uniform(Duration min, Duration max) {
        if (min == null || max == null) throw new IllegalArgumentException("uniform think time requires min and max");
        long minMillis;
        long maxMillis;
        try { minMillis = min.toMillis(); maxMillis = max.toMillis(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("think time is too large", e); }
        if (minMillis == maxMillis) return new ThinkTimePolicy(Type.FIXED, minMillis, maxMillis);
        return new ThinkTimePolicy(Type.UNIFORM_RANGE, minMillis, maxMillis);
    }

    public Type type() { return type; }
    public boolean randomized() { return type == Type.UNIFORM_RANGE; }
    public long minMillis() { return minMillis; }
    public long maxMillis() { return maxMillis; }
    public Duration min() { return Duration.ofMillis(minMillis); }
    public Duration max() { return Duration.ofMillis(maxMillis); }

    /** Samples one integer-millisecond delay. Range endpoints are inclusive. */
    public long sampleMillis(Random random) {
        if (!randomized()) return minMillis;
        if (random == null) throw new IllegalArgumentException("uniform think time requires a random stream");
        if (minMillis == 0L && maxMillis == Long.MAX_VALUE) return random.nextLong() & Long.MAX_VALUE;
        long bound = maxMillis - minMillis + 1L;
        return minMillis + nextLong(random, bound);
    }

    public Object toConfigValue() {
        if (!randomized()) return format(minMillis);
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("min", format(minMillis));
        value.put("max", format(maxMillis));
        return value;
    }

    public String summary() {
        return randomized() ? "uniform: " + format(minMillis) + ".." + format(maxMillis)
                : "fixed: " + format(minMillis);
    }

    private static long nextLong(Random random, long bound) {
        if (bound <= 0L) throw new IllegalArgumentException("random bound must be positive");
        long r = random.nextLong();
        long m = bound - 1L;
        if ((bound & m) == 0L) return r & m;
        long u = r >>> 1;
        long candidate = u % bound;
        while (u + m - candidate < 0L) {
            u = random.nextLong() >>> 1;
            candidate = u % bound;
        }
        return candidate;
    }

    static String format(long millis) {
        if (millis == 0L) return "0ms";
        if (millis % 3600000L == 0L) return (millis / 3600000L) + "h";
        if (millis % 60000L == 0L) return (millis / 60000L) + "m";
        if (millis % 1000L == 0L) return (millis / 1000L) + "s";
        return millis + "ms";
    }
}
