package att.load;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * Resolved success/failure evidence policy for a Load run.
 *
 * <p>Two quota models exist and must not be mixed:</p>
 * <ul>
 *   <li>Legacy: one combined {@code maxSamples} cap, or independent
 *   {@code maxSuccessSamples}/{@code maxFailureSamples} caps shared across the whole run.</li>
 *   <li>{@code evidence.quotaScope: perWorkload}: every workload owns independent
 *   {@code maxSuccessSamples} and {@code maxFailureSamples} hard caps, resolved from
 *   {@code evidence.defaults} plus optional {@code workloads[].evidence} cap-only overrides.
 *   Unused capacity stays unused: there is no lending, borrowing, reclaim or revocation.</li>
 * </ul>
 */
public final class LoadEvidencePolicy {
    public enum Success { NONE, SAMPLE, FULL }
    public enum Failure { NONE, FULL }
    /** Default per-workload cap when {@code evidence.defaults} omits a value. */
    public static final int DEFAULT_PER_WORKLOAD_CAP = 1000;
    /** Preflight safety budget for the sum of effective per-workload caps, per outcome. */
    public static final int MAX_AGGREGATE_CAP = 1000000;
    public static final String QUOTA_SCOPE_PER_WORKLOAD = "perWorkload";
    /** Stable sampling contract: UTF-8 identity bytes, MurmurHash3 x86 32-bit, seed 0. */
    public static final String SUCCESS_HASH_VERSION = "murmur3-32-v1";
    /** Retention slots are allocated to equal run-time windows without replacing retained evidence. */
    public static final String RETENTION_STRATEGY = "timeWindowStratifiedV1";
    public static final int MAX_RETENTION_WINDOWS = 10;
    private final Success success; private final Failure failure; private final double sampleRate; private final int maxSamples;
    private final boolean splitQuotas;
    private final int maxSuccessSamples, maxFailureSamples;
    private final boolean perWorkload;
    private final int defaultSuccessCap, defaultFailureCap;
    private final Map<String,Integer> successCaps, failureCaps;
    public LoadEvidencePolicy(Success success, Failure failure, double sampleRate, int maxSamples) {
        this(success, failure, sampleRate, maxSamples, false, maxSamples, maxSamples);
    }
    private LoadEvidencePolicy(Success success, Failure failure, double sampleRate, int maxSamples,
                               boolean splitQuotas, int maxSuccessSamples, int maxFailureSamples) {
        this(success, failure, sampleRate, maxSamples, splitQuotas, maxSuccessSamples, maxFailureSamples,
                false, maxSuccessSamples, maxFailureSamples, Collections.<String,Integer>emptyMap(), Collections.<String,Integer>emptyMap());
    }
    private LoadEvidencePolicy(Success success, Failure failure, double sampleRate, int maxSamples,
                               boolean splitQuotas, int maxSuccessSamples, int maxFailureSamples,
                               boolean perWorkload, int defaultSuccessCap, int defaultFailureCap,
                               Map<String,Integer> successCaps, Map<String,Integer> failureCaps) {
        this.success=success;this.failure=failure;this.sampleRate=sampleRate;this.maxSamples=maxSamples;
        this.splitQuotas=splitQuotas;this.maxSuccessSamples=maxSuccessSamples;this.maxFailureSamples=maxFailureSamples;
        this.perWorkload=perWorkload;this.defaultSuccessCap=defaultSuccessCap;this.defaultFailureCap=defaultFailureCap;
        this.successCaps=Collections.unmodifiableMap(new LinkedHashMap<String,Integer>(successCaps));
        this.failureCaps=Collections.unmodifiableMap(new LinkedHashMap<String,Integer>(failureCaps));
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
        sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
        if (perWorkloadScope(map)) return perWorkload(scenario, map, success, failure, sampleRate);
        rejectWorkloadEvidenceInLegacyScope(scenario);
        boolean explicitCap = map.containsKey("maxSamples");
        boolean unlimitedByDefault = "all".equalsIgnoreCase(mode) || success == Success.FULL;
        int maxSamples = explicitCap ? (int) number(map.get("maxSamples"), 1000.0)
                : unlimitedByDefault ? Integer.MAX_VALUE : 1000;
        if(map.containsKey("maxFailureSamples")) {
            int successCap=map.containsKey("maxSuccessSamples")?quota(map.get("maxSuccessSamples"),"evidence.maxSuccessSamples")
                    :map.containsKey("maxSamples")?quota(map.get("maxSamples"),"evidence.maxSamples"):1000;
            return new LoadEvidencePolicy(success,failure,sampleRate,maxSamples,
                    true,successCap,quota(map.get("maxFailureSamples"),"evidence.maxFailureSamples"));
        }
        return new LoadEvidencePolicy(success, failure, sampleRate, Math.max(0, maxSamples));
    }

    private static LoadEvidencePolicy perWorkload(LoadScenario scenario, Map<String,Object> map,
                                                  Success success, Failure failure, double sampleRate) {
        Map<String,Object> defaults = objectMap(map.get("defaults"));
        int defaultSuccess = defaults.containsKey("maxSuccessSamples")
                ? quota(defaults.get("maxSuccessSamples"), "evidence.defaults.maxSuccessSamples")
                : DEFAULT_PER_WORKLOAD_CAP;
        int defaultFailure = defaults.containsKey("maxFailureSamples")
                ? quota(defaults.get("maxFailureSamples"), "evidence.defaults.maxFailureSamples")
                : DEFAULT_PER_WORKLOAD_CAP;
        Map<String,Integer> successCaps = new LinkedHashMap<String,Integer>();
        Map<String,Integer> failureCaps = new LinkedHashMap<String,Integer>();
        long totalSuccess = 0L, totalFailure = 0L;
        for (LoadWorkload workload : scenario.workloads()) {
            Map<String,Object> overrides = workload.evidence();
            validateWorkloadEvidence(overrides, "workloads[" + workload.id() + "].evidence");
            int successCap = overrides.containsKey("maxSuccessSamples")
                    ? quota(overrides.get("maxSuccessSamples"), "workloads[" + workload.id() + "].evidence.maxSuccessSamples")
                    : defaultSuccess;
            int failureCap = overrides.containsKey("maxFailureSamples")
                    ? quota(overrides.get("maxFailureSamples"), "workloads[" + workload.id() + "].evidence.maxFailureSamples")
                    : defaultFailure;
            successCaps.put(workload.id(), successCap);
            failureCaps.put(workload.id(), failureCap);
            totalSuccess += successCap;
            totalFailure += failureCap;
        }
        if (scenario.workloads().isEmpty()) {
            // Policy-only descriptor: the defaults are the effective caps of its single run.
            totalSuccess = defaultSuccess;
            totalFailure = defaultFailure;
        }
        if (totalSuccess > MAX_AGGREGATE_CAP || totalFailure > MAX_AGGREGATE_CAP)
            throw new IllegalArgumentException("evidence: the sum of effective per-workload caps (success=" + totalSuccess
                    + ", failure=" + totalFailure + ") exceeds the documented aggregate safety budget of "
                    + MAX_AGGREGATE_CAP + " per outcome; lower evidence.defaults or a workload override");
        return new LoadEvidencePolicy(success, failure, sampleRate, Integer.MAX_VALUE,
                false, defaultSuccess, defaultFailure, true, defaultSuccess, defaultFailure, successCaps, failureCaps);
    }

    private static void rejectWorkloadEvidenceInLegacyScope(LoadScenario scenario) {
        for (LoadWorkload workload : scenario.workloads()) {
            if (!workload.evidence().isEmpty())
                throw new IllegalArgumentException("workloads[" + workload.id()
                        + "].evidence requires evidence.quotaScope: perWorkload");
        }
    }

    /** True when the new independent per-workload quota model is configured. */
    public static boolean perWorkloadScope(Map<String,Object> map) {
        return map != null && map.containsKey("quotaScope");
    }

    /** Semantic validation also covers programmatically constructed scenarios and inline policies. */
    public static void validateQuotas(Map<String,Object> map) {
        if (map == null) return;
        if (perWorkloadScope(map)) {
            Object scope = map.get("quotaScope");
            if (!QUOTA_SCOPE_PER_WORKLOAD.equalsIgnoreCase(String.valueOf(scope)))
                throw new IllegalArgumentException("evidence.quotaScope supports only " + QUOTA_SCOPE_PER_WORKLOAD);
            if (map.containsKey("maxSamples") || map.containsKey("maxSuccessSamples") || map.containsKey("maxFailureSamples"))
                throw new IllegalArgumentException("evidence.quotaScope: perWorkload replaces the root evidence.maxSamples/maxSuccessSamples/maxFailureSamples cap; configure evidence.defaults and workload-level evidence overrides instead");
            Object defaults = map.get("defaults");
            if (defaults != null) {
                if (!(defaults instanceof Map))
                    throw new IllegalArgumentException("evidence.defaults must be a map with maxSuccessSamples/maxFailureSamples");
                for (Object key : ((Map<?,?>) defaults).keySet()) {
                    String name = String.valueOf(key);
                    if (!"maxSuccessSamples".equals(name) && !"maxFailureSamples".equals(name))
                        throw new IllegalArgumentException("evidence.defaults supports only maxSuccessSamples and maxFailureSamples");
                }
                @SuppressWarnings("unchecked") Map<String,Object> values = (Map<String,Object>) defaults;
                if (values.containsKey("maxSuccessSamples")) quota(values.get("maxSuccessSamples"), "evidence.defaults.maxSuccessSamples");
                if (values.containsKey("maxFailureSamples")) quota(values.get("maxFailureSamples"), "evidence.defaults.maxFailureSamples");
            }
            return;
        }
        if (map.containsKey("defaults"))
            throw new IllegalArgumentException("evidence.defaults requires evidence.quotaScope: perWorkload");
        if(map.containsKey("maxSuccessSamples") && !map.containsKey("maxFailureSamples"))
            throw new IllegalArgumentException("evidence.maxSuccessSamples requires an explicit finite evidence.maxFailureSamples");
        if(!map.containsKey("maxFailureSamples"))return;
        quota(map.get("maxFailureSamples"),"evidence.maxFailureSamples");
        if(map.containsKey("maxSuccessSamples") && map.containsKey("maxSamples"))
            throw new IllegalArgumentException("Use evidence.maxSuccessSamples or evidence.maxSamples as the split success cap, not both");
        if(map.containsKey("maxSuccessSamples"))quota(map.get("maxSuccessSamples"),"evidence.maxSuccessSamples");
        if(map.containsKey("maxSamples"))quota(map.get("maxSamples"),"evidence.maxSamples");
    }

    /** Validates a {@code workloads[].evidence} override object: caps only, no shared policy. */
    public static void validateWorkloadEvidence(Map<String,Object> map, String field) {
        if (map == null || map.isEmpty()) return;
        for (String key : map.keySet()) {
            if (!"maxSuccessSamples".equals(key) && !"maxFailureSamples".equals(key))
                throw new IllegalArgumentException(field + " supports only maxSuccessSamples and maxFailureSamples; sampling policy and defaults stay at root evidence");
        }
        if (map.containsKey("maxSuccessSamples")) quota(map.get("maxSuccessSamples"), field + ".maxSuccessSamples");
        if (map.containsKey("maxFailureSamples")) quota(map.get("maxFailureSamples"), field + ".maxFailureSamples");
    }

    private static int quota(Object value,String field) {
        if(!(value instanceof Number))throw new IllegalArgumentException(field+" must be an integer from 0 to 1000000");
        double number=((Number)value).doubleValue();
        if(!Double.isFinite(number)||number<0||number>1000000||number!=Math.rint(number))
            throw new IllegalArgumentException(field+" must be an integer from 0 to 1000000");
        return (int)number;
    }
    public boolean splitQuotas() { return splitQuotas; }
    /** True when each workload owns independent success/failure hard caps. */
    public boolean perWorkloadQuotas() { return perWorkload; }
    public int maxSuccessSamples() { return maxSuccessSamples; }
    public int maxFailureSamples() { return maxFailureSamples; }
    public int defaultSuccessCap() { return defaultSuccessCap; }
    public int defaultFailureCap() { return defaultFailureCap; }
    /** Effective success cap of one workload bucket; legacy scopes share the configured cap. */
    public int successCapFor(String workloadId) {
        if (!perWorkload) return maxSuccessSamples;
        Integer cap = workloadId == null ? null : successCaps.get(workloadId);
        return cap == null ? defaultSuccessCap : cap;
    }
    /** Effective failure cap of one workload bucket; legacy scopes share the configured cap. */
    public int failureCapFor(String workloadId) {
        if (!perWorkload) return maxFailureSamples;
        Integer cap = workloadId == null ? null : failureCaps.get(workloadId);
        return cap == null ? defaultFailureCap : cap;
    }
    public Map<String,Integer> effectiveSuccessCaps() { return successCaps; }
    public Map<String,Integer> effectiveFailureCaps() { return failureCaps; }
    public Map<String,Object> toMap() {
        Map<String,Object> result=new java.util.LinkedHashMap<String,Object>();
        result.put("success",success.name().toLowerCase(java.util.Locale.ROOT));
        result.put("failure",failure.name().toLowerCase(java.util.Locale.ROOT));result.put("sampleRate",sampleRate);
        result.put("successSamplingHash",SUCCESS_HASH_VERSION);
        result.put("successSamplingEncoding","UTF-8");
        result.put("retentionStrategy",RETENTION_STRATEGY);
        result.put("retentionMaxWindows",MAX_RETENTION_WINDOWS);
        if(perWorkload) {
            result.put("quotaScope",QUOTA_SCOPE_PER_WORKLOAD);
            result.put("quotaMode",QUOTA_SCOPE_PER_WORKLOAD);
            Map<String,Object> defaults=new java.util.LinkedHashMap<String,Object>();
            defaults.put("maxSuccessSamples",defaultSuccessCap);
            defaults.put("maxFailureSamples",defaultFailureCap);
            result.put("defaults",defaults);
            return result;
        }
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
        long unsignedHash = Integer.toUnsignedLong(murmur3_32(iterationId.getBytes(StandardCharsets.UTF_8), 0));
        long threshold = (long) Math.floor(sampleRate * 4294967296.0d);
        return unsignedHash < threshold;
    }

    /** MurmurHash3 x86 32-bit, seed 0; all arithmetic intentionally wraps modulo 2^32. */
    static int murmur3_32(byte[] data, int seed) {
        int hash = seed;
        int roundedEnd = data.length & 0xfffffffc;
        for (int offset = 0; offset < roundedEnd; offset += 4) {
            int key = (data[offset] & 0xff)
                    | ((data[offset + 1] & 0xff) << 8)
                    | ((data[offset + 2] & 0xff) << 16)
                    | ((data[offset + 3] & 0xff) << 24);
            key *= 0xcc9e2d51;
            key = Integer.rotateLeft(key, 15);
            key *= 0x1b873593;
            hash ^= key;
            hash = Integer.rotateLeft(hash, 13);
            hash = hash * 5 + 0xe6546b64;
        }

        int tail = 0;
        switch (data.length & 3) {
            case 3: tail ^= (data[roundedEnd + 2] & 0xff) << 16;
            case 2: tail ^= (data[roundedEnd + 1] & 0xff) << 8;
            case 1:
                tail ^= data[roundedEnd] & 0xff;
                tail *= 0xcc9e2d51;
                tail = Integer.rotateLeft(tail, 15);
                tail *= 0x1b873593;
                hash ^= tail;
                break;
            default:
                break;
        }

        hash ^= data.length;
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        hash *= 0xc2b2ae35;
        hash ^= hash >>> 16;
        return hash;
    }
    public boolean retain(LoadEvent event, int currentSamples) {
        if (event == null || currentSamples >= (splitQuotas ? (event.success()?maxSuccessSamples:maxFailureSamples) : maxSamples)) return false;
        if (event.dropped() || !event.completed()) return false;
        if (!event.success()) return failure == Failure.FULL;
        return retainSuccess(event.iterationId());
    }
    private static Map<String,Object> objectMap(Object value) {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        if (value instanceof Map) for (Map.Entry<?,?> entry : ((Map<?,?>) value).entrySet())
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
}
