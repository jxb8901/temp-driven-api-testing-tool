package att.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded conservation checks shared by checkpoints and final LOAD reports. */
final class LoadMetricsAccounting {
    private static final int MAX_VIOLATIONS = 32;
    private static final String[] COUNTERS = {
            "scheduled", "started", "completed", "success", "failure", "runtimeError", "otherOutcome", "dropped", "currentInFlight"
    };

    private LoadMetricsAccounting() { }

    static Map<String, Object> evaluate(Map<String, Object> values,
                                        Map<String, Map<String, Object>> phases,
                                        boolean finalized) {
        List<Map<String, Object>> violations = new ArrayList<Map<String, Object>>();
        boolean partial = false;
        String state = checkScope("run", values, finalized, violations);
        partial |= "PARTIAL".equals(state);
        for (Map.Entry<String, Map<String, Object>> phase : phases.entrySet()) {
            String phaseState = checkScope("phase:" + phase.getKey(), phase.getValue(), finalized, violations);
            partial |= "PARTIAL".equals(phaseState);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String finalState = !violations.isEmpty() ? "FAILED" : partial ? "PARTIAL" : finalized ? "VERIFIED" : "IN_PROGRESS";
        result.put("state", finalState);
        result.put("finalized", Boolean.valueOf(finalized));
        result.put("scopesChecked", Integer.valueOf(1 + phases.size()));
        result.put("violations", violations);
        return result;
    }

    static Map<String, Object> compareWorkloads(Map<String, Object> aggregate,
                                                 Map<String, Map<String, Object>> workloads) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> violations = new ArrayList<Map<String, Object>>();
        Map<String, Object> sums = new LinkedHashMap<String, Object>();
        for (String key : COUNTERS) {
            long total = 0L;
            boolean overflow = false;
            for (Map<String, Object> workload : workloads.values()) {
                Object raw = workload.get(key);
                long value = raw instanceof Number ? ((Number) raw).longValue() : 0L;
                try { total = Math.addExact(total, value); }
                catch (ArithmeticException failure) { overflow = true; break; }
            }
            sums.put(key, Long.valueOf(total));
            if (overflow || number(aggregate.get(key)) != total) {
                addViolation(violations, "WORKLOAD_AGGREGATE_MISMATCH", "workloads." + key,
                        number(aggregate.get(key)), overflow ? "overflow" : total);
            }
        }
        result.put("state", violations.isEmpty() ? "VERIFIED" : "FAILED");
        result.put("workloadCount", Integer.valueOf(workloads.size()));
        result.put("sums", sums);
        result.put("violations", violations);
        return result;
    }

    private static String checkScope(String scope, Map<String, Object> counters, boolean finalized,
                                     List<Map<String, Object>> violations) {
        long scheduled = number(counters.get("scheduled"));
        long started = number(counters.get("started"));
        long completed = number(counters.get("completed"));
        long dropped = number(counters.get("dropped"));
        long inFlight = number(counters.get("currentInFlight"));
        long outcomes = add(add(add(number(counters.get("success")), number(counters.get("failure")), scope + ".outcomes", violations),
                number(counters.get("runtimeError")), scope + ".outcomes", violations),
                number(counters.get("otherOutcome")), scope + ".outcomes", violations);
        long admittedOrDropped = add(started, dropped, scope + ".scheduled", violations);
        long drainedOrInFlight = add(completed, inFlight, scope + ".started", violations);
        for (String key : COUNTERS) if (number(counters.get(key)) < 0L)
            addViolation(violations, "NEGATIVE_COUNTER", scope + "." + key, number(counters.get(key)), "non-negative");
        if (scheduled != admittedOrDropped)
            addViolation(violations, "ARRIVAL_CONSERVATION_FAILED", scope + ".scheduled", scheduled, admittedOrDropped);
        if (started != drainedOrInFlight)
            addViolation(violations, "START_CONSERVATION_FAILED", scope + ".started", started, drainedOrInFlight);
        if (completed != outcomes)
            addViolation(violations, "COMPLETION_CONSERVATION_FAILED", scope + ".completed", completed, outcomes);
        return finalized && violations.isEmpty() && inFlight > 0L ? "PARTIAL"
                : finalized ? "VERIFIED" : "IN_PROGRESS";
    }

    private static void addViolation(List<Map<String, Object>> violations, String code,
                                     String field, Object actual, Object expected) {
        if (violations.size() >= MAX_VIOLATIONS) return;
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("code", code); item.put("field", field); item.put("actual", actual); item.put("expected", expected);
        violations.add(item);
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static long add(long left, long right, String field, List<Map<String, Object>> violations) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException overflow) {
            addViolation(violations, "COUNTER_OVERFLOW", field, "overflow", "representable long sum");
            return Long.MIN_VALUE;
        }
    }
}
