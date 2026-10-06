package att.load;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stateless deterministic selector independent of each VU's think-time RNG. */
public final class LoadMixSelector {
    private LoadMixSelector() { }

    public static LoadMixEntry select(LoadWorkload workload, long runSeed, long iteration, String userId) {
        if (workload == null || !workload.mixed()) return null;
        long total = 0L;
        for (LoadMixEntry entry : workload.mix()) total = Math.addExact(total, entry.weight());
        long draw = Long.remainderUnsigned(hash(runSeed, workload.id(), userId, iteration), total);
        for (LoadMixEntry entry : workload.mix()) {
            if (draw < entry.weight()) return entry;
            draw -= entry.weight();
        }
        throw new IllegalStateException("Unable to select Load mix entry");
    }

    public static Map<String, Object> mergeInputs(Map<String, Object> defaults, LoadMixEntry entry) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (defaults != null) result.putAll(defaults);
        if (entry != null) result.putAll(entry.inputs());
        return result;
    }

    private static long hash(long seed, String workload, String user, long iteration) {
        long value = seed ^ 0xcbf29ce484222325L;
        value = feed(value, workload);
        value = feed(value, user);
        value ^= iteration;
        value *= 0x100000001b3L;
        value ^= value >>> 30; value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27; value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    private static long feed(long value, String text) {
        String source = text == null ? "" : text;
        for (int i = 0; i < source.length(); i++) { value ^= source.charAt(i); value *= 0x100000001b3L; }
        return value;
    }
}
