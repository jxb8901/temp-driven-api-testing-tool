package att.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates normalized SUT threshold rules against bounded sampled Prometheus observations. */
final class SutThresholdEvaluator {
    private SutThresholdEvaluator() { }

    static Map<String, Object> evaluate(List<Map<String, Object>> rules,
                                        List<Map<String, Object>> queries,
                                        Map<String, Map<String, Object>> observations,
                                        List<Map<String, Object>> phaseWindows,
                                        long stepSeconds,
                                        long deadlineNanos) {
        Map<String, Map<String, Object>> queryById = new HashMap<>();
        for (Map<String, Object> query : queries) queryById.put(String.valueOf(query.get("id")), query);
        Map<String, Map<String, Object>> phases = new HashMap<>();
        for (Map<String, Object> phase : phaseWindows) phases.put(String.valueOf(phase.get("phase")), phase);
        List<Map<String, Object>> subjects = new ArrayList<>();
        List<Map<String, Object>> ruleResults = new ArrayList<>();
        boolean anyRequired = false, requiredNotMet = false, requiredNotEvaluated = false;
        for (Map<String, Object> rule : rules) {
            checkDeadline(deadlineNanos);
            List<Map<String, Object>> ruleSubjects = new ArrayList<>();
            boolean ruleNotMet = false, ruleNotEvaluated = false;
            for (Object rawQueryId : list(rule.get("queryIds"))) {
                checkDeadline(deadlineNanos);
                String queryId = String.valueOf(rawQueryId);
                Map<String, Object> query = queryById.get(queryId);
                if (query == null) {
                    ruleNotEvaluated = true;
                    Map<String, Object> missing = missingSubject(rule, queryId,
                            "The pinned metric target is unavailable.", phases.get("STEADY"), stepSeconds);
                    ruleSubjects.add(missing);
                    subjects.add(missing);
                    continue;
                }
                Map<String, Object> result = evaluateTarget(rule, query, observations.get(queryId), phases.get("STEADY"), stepSeconds, deadlineNanos);
                ruleSubjects.add(result);
                subjects.add(result);
                if ("NOT_MET".equals(result.get("status"))) ruleNotMet = true;
                else if (!"MET".equals(result.get("status"))) ruleNotEvaluated = true;
            }
            String status = ruleNotMet ? "NOT_MET" : ruleNotEvaluated ? "NOT_EVALUATED" : "MET";
            boolean required = Boolean.TRUE.equals(rule.get("required"));
            if (required) {
                anyRequired = true;
                if ("NOT_MET".equals(status)) requiredNotMet = true;
                else if (!"MET".equals(status)) requiredNotEvaluated = true;
            }
            Map<String, Object> summary = fields("id", rule.get("id"), "profile", rule.get("profile"),
                    "profilePath", rule.get("profilePath"), "metricId", rule.get("metricId"),
                    "metricName", rule.get("metricName"), "unit", rule.get("unit"),
                    "reduction", rule.get("reduction"), "operator", rule.get("operator"),
                    "value", rule.get("value"), "required", required, "status", status,
                    "targets", ruleSubjects);
            ruleResults.add(summary);
        }
        String requiredStatus = !anyRequired || (!requiredNotMet && !requiredNotEvaluated) ? "MET"
                : requiredNotMet ? "NOT_MET" : "NOT_EVALUATED";
        return fields("status", requiredStatus, "requiredThresholdCount", rules.stream()
                        .filter(rule -> Boolean.TRUE.equals(rule.get("required"))).count(),
                "items", ruleResults, "targets", subjects);
    }

    private static Map<String, Object> evaluateTarget(Map<String, Object> rule, Map<String, Object> query,
                                                       Map<String, Object> observation, Map<String, Object> phase,
                                                       long stepSeconds, long deadlineNanos) {
        Map<String, Object> source = fields("id", query.get("sourceId"), "name", query.get("sourceName"),
                "revision", query.get("sourceRevision"));
        Map<String, Object> profile = fields("id", query.get("profile"), "path", query.get("profilePath"),
                "revision", query.get("profileRevision"));
        Map<String, Object> target = fields("id", query.get("targetId"), "type", query.get("targetType"),
                "revision", query.get("targetRevision"));
        Map<String, Object> window = window(phase, stepSeconds);
        if (phase == null) return subject(rule, query, source, profile, target, window, "NOT_EVALUATED",
                null, null, 0, 0, 0.0, null, null, "The actual STEADY phase interval is unavailable.");
        if (observation == null) return subject(rule, query, source, profile, target, window, "NOT_EVALUATED",
                null, null, 0, expectedSamples(phase, stepSeconds, null), 0.0, null, null,
                "No usable observations were published for this target.");

        Instant start = instant(phase.get("startAt")), end = instant(phase.get("endAt"));
        if (start == null || end == null || !end.isAfter(start))
            return subject(rule, query, source, profile, target, window, "NOT_EVALUATED", null, null,
                    0, 0, 0.0, null, null, "The actual STEADY phase interval is invalid.");
        Set<Long> timestamps = new HashSet<>();
        Double peak = null;
        Instant peakAt = null, firstAt = null, lastAt = null;
        long examined = 0;
        for (Map<String, Object> series : maps(observation.get("series"))) {
            for (Map<String, Object> sample : maps(series.get("samples"))) {
                if ((examined++ & 1023L) == 0L) checkDeadline(deadlineNanos);
                Instant at = instant(sample.get("timestamp"));
                Object value = sample.get("value");
                if (at == null || !(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || at.isBefore(start) || at.isAfter(end)) continue;
                timestamps.add(at.toEpochMilli());
                if (firstAt == null || at.isBefore(firstAt)) firstAt = at;
                if (lastAt == null || at.isAfter(lastAt)) lastAt = at;
                if (peak == null || number.doubleValue() > peak) { peak = number.doubleValue(); peakAt = at; }
            }
        }
        long expected = expectedSamples(phase, stepSeconds, observation);
        long observed = timestamps.size();
        double coverage = expected == 0 ? 0.0 : Math.min(1.0, (double) observed / (double) expected);
        long tolerance = Math.max(stepSeconds * 2L, 30L);
        boolean coversStart = firstAt != null && !firstAt.isAfter(start.plusSeconds(tolerance));
        boolean coversEnd = lastAt != null && !lastAt.isBefore(end.minusSeconds(tolerance));
        Map<String, Object> observationBody = map(observation.get("observation"));
        Map<String, Object> availability = map(observationBody.get("availability"));
        String availabilityState = String.valueOf(availability.get("state"));
        String freshness = String.valueOf(availability.get("freshness"));
        boolean partial = "PARTIAL".equals(availabilityState);
        boolean stale = "STALE".equals(freshness);
        boolean availabilityUnknown = !"AVAILABLE".equals(availabilityState) || !"FRESH".equals(freshness);
        double threshold = ((Number) rule.get("value")).doubleValue();
        String status;
        String reason;
        if (peak != null && peak > threshold) {
            status = "NOT_MET";
            reason = "A valid sampled maximum exceeded the configured limit.";
        } else if (peak == null) {
            status = "NOT_EVALUATED";
            reason = "No valid samples fall inside the requested STEADY window.";
        } else if (availabilityUnknown) {
            status = "NOT_EVALUATED";
            reason = partial ? "The pinned observation is partial."
                    : stale ? "The pinned observation is stale."
                    : "The pinned observation does not prove availability and freshness.";
        } else if (coverage < ((Number) rule.get("minimumCoverage")).doubleValue()) {
            status = "NOT_EVALUATED";
            reason = "Sample coverage is below the configured minimum.";
        } else if (!coversStart || !coversEnd) {
            status = "NOT_EVALUATED";
            reason = "Samples do not cover both ends of the requested STEADY window within the freshness tolerance.";
        } else {
            status = "MET";
            reason = "The sampled maximum is within the configured limit and coverage is sufficient.";
        }
        return subject(rule, query, source, profile, target, window, status, peak, peakAt, observed,
                expected, coverage, firstAt, lastAt, reason);
    }

    private static Map<String, Object> missingSubject(Map<String, Object> rule, String queryId, String reason,
                                                      Map<String, Object> phase, long stepSeconds) {
        Map<String, Object> query = fields("id", queryId, "profile", rule.get("profile"),
                "profilePath", rule.get("profilePath"), "profileRevision", null,
                "sourceId", null, "sourceName", null, "sourceRevision", null,
                "targetId", null, "targetType", null, "targetRevision", null,
                "title", rule.get("metricName"));
        Map<String, Object> source = fields("id", null, "name", null, "revision", null);
        Map<String, Object> profile = fields("id", rule.get("profile"),
                "path", rule.get("profilePath"), "revision", null);
        Map<String, Object> target = fields("id", null, "type", null, "revision", null);
        return subject(rule, query, source, profile, target, window(phase, stepSeconds),
                "NOT_EVALUATED", null, null, 0, 0, 0.0, null, null, reason);
    }

    private static Map<String, Object> subject(Map<String, Object> rule, Map<String, Object> query,
                                               Map<String, Object> source, Map<String, Object> profile,
                                               Map<String, Object> target, Map<String, Object> window,
                                               String status, Double peak, Instant peakAt, long observed,
                                               long expected, double coverage, Instant firstAt, Instant lastAt,
                                               String reason) {
        return fields("ruleId", rule.get("id"), "queryId", query.get("id"),
                "profile", rule.get("profile"), "profilePath", query.get("profilePath"), "metricId", rule.get("metricId"),
                "metricName", query.get("title"), "unit", rule.get("unit"), "required", rule.get("required"),
                "source", source, "profileRevision", profile.get("revision"), "target", target,
                "window", window, "sampleResolution", String.valueOf(stepSeconds(window)) + "s",
                "threshold", fields("reduction", rule.get("reduction"), "operator", rule.get("operator"),
                        "value", rule.get("value"), "unit", rule.get("unit")),
                "actual", peak, "peakObservedAt", peakAt == null ? null : peakAt.toString(),
                "coverage", fields("sampleCount", observed, "expectedSampleCount", expected,
                        "ratio", coverage, "firstSampleAt", firstAt == null ? null : firstAt.toString(),
                        "lastSampleAt", lastAt == null ? null : lastAt.toString()),
                "status", status, "reason", reason);
    }

    private static Map<String, Object> window(Map<String, Object> phase, long stepSeconds) {
        return fields("name", "steady", "startAt", phase == null ? null : phase.get("startAt"),
                "endAt", phase == null ? null : phase.get("endAt"), "step", stepSeconds + "s");
    }

    private static long expectedSamples(Map<String, Object> phase, long stepSeconds, Map<String, Object> observation) {
        Instant start = instant(phase.get("startAt")), end = instant(phase.get("endAt"));
        if (start == null || end == null || !end.isAfter(start) || stepSeconds < 1) return 0;
        Map<String, Object> observationBody = map(observation == null ? null : observation.get("observation"));
        Map<String, Object> queryWindow = map(observationBody.get("window"));
        Instant queryStart = instant(queryWindow.get("queryStart"));
        if (queryStart == null) queryStart = start;
        long stepMillis = Math.multiplyExact(stepSeconds, 1000L);
        long originMillis = ceilEpochSecond(queryStart) * 1000L;
        long startMillis = ceilEpochMillis(start);
        long endMillis = end.toEpochMilli();
        // Prometheus query_range places samples on a step-aligned grid from queryStart.
        long firstIndex = -Math.floorDiv(-(startMillis - originMillis), stepMillis);
        long firstSample = originMillis + firstIndex * stepMillis;
        return firstSample > endMillis ? 0L : Math.floorDiv(endMillis - firstSample, stepMillis) + 1L;
    }

    private static long ceilEpochSecond(Instant value) {
        return value.getEpochSecond() + (value.getNano() == 0 ? 0L : 1L);
    }

    private static long ceilEpochMillis(Instant value) {
        return value.toEpochMilli() + (value.getNano() % 1_000_000 == 0 ? 0L : 1L);
    }

    private static long stepSeconds(Map<String, Object> window) {
        Object raw = window.get("step");
        if (!(raw instanceof String value) || !value.endsWith("s")) return 0L;
        try { return Long.parseLong(value.substring(0, value.length() - 1)); }
        catch (NumberFormatException invalid) { return 0L; }
    }

    static void checkDeadline(long deadlineNanos) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadlineNanos) throw new EvaluationTimeoutException();
    }

    private static Instant instant(Object value) {
        try { return value instanceof String text ? Instant.parse(text) : null; }
        catch (RuntimeException invalid) { return null; }
    }

    private static List<?> list(Object value) { return value instanceof List<?> rows ? rows : List.of(); }
    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list(value)) if (item instanceof Map<?, ?> map) rows.add(cast(map));
        return rows;
    }
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> values ? cast(values) : Map.of();
    }
    private static Map<String, Object> cast(Map<?, ?> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> { if (key instanceof String text) copy.put(text, item); });
        return copy;
    }
    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    static final class EvaluationTimeoutException extends RuntimeException {
        EvaluationTimeoutException() { super("SUT threshold evaluation exceeded its bounded deadline."); }
    }
}
