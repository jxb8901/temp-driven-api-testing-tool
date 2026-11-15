package att.server;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SutThresholdEvaluatorTest {
    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");
    private static final long STEP_SECONDS = 15;

    @Test void exactBoundaryIsMetAndAnyValidExceedanceWinsOverIncompleteCoverage() {
        Map<String, Object> phase = phase(START, START.plusSeconds(60));
        Map<String, Object> exact = evaluate(List.of(rule("cpu", 90.0, List.of("q1"))),
                List.of(query("q1", "sut-1")), Map.of("q1", observation(START, regularSamples(START, 60, 90.0))), phase);
        assertEquals("MET", exact.get("status"));
        assertEquals("MET", targets(exact).get(0).get("status"));

        List<Map<String, Object>> sparseViolation = List.of(
                sample(START, 90.0), sample(START.plusSeconds(15), 90.0),
                sample(START.plusSeconds(30), 90.1), sample(START.plusSeconds(60), 90.0));
        Map<String, Object> violation = evaluate(List.of(rule("cpu", 90.0, List.of("q1"))),
                List.of(query("q1", "sut-1")), Map.of("q1", observation(START, sparseViolation)), phase);
        assertEquals("NOT_MET", violation.get("status"));
        assertEquals("NOT_MET", targets(violation).get(0).get("status"));
        assertTrue(((Number) map(targets(violation).get(0).get("coverage")).get("ratio")).doubleValue() < 0.95);
    }

    @Test void targetsAreEvaluatedIndependentlyAndNeverAveraged() {
        Map<String, Object> phase = phase(START, START.plusSeconds(60));
        Map<String, Object> result = evaluate(List.of(rule("cpu", 90.0, List.of("q1", "q2"))),
                List.of(query("q1", "sut-1"), query("q2", "sut-2")),
                Map.of("q1", observation(START, regularSamples(START, 60, 90.0)),
                        "q2", observation(START, regularSamples(START, 60, 90.1))), phase);
        assertEquals("NOT_MET", result.get("status"));
        assertEquals(2, targets(result).size());
        assertEquals("MET", targets(result).get(0).get("status"));
        assertEquals("NOT_MET", targets(result).get(1).get("status"));
        assertEquals("sut-1", map(targets(result).get(0).get("target")).get("id"));
        assertEquals("sut-2", map(targets(result).get(1).get("target")).get("id"));
    }

    @Test void alternatingLabelSeriesGapsCannotCombineIntoCompleteCoverage() {
        Map<String, Object> phase = phase(START, START.plusSeconds(60));
        Map<String, Object> split = observation(START, List.of());
        split.put("series", List.of(
                fields("labels", Map.of("instance", "sut-1", "pod", "a"), "samples", List.of(
                        sample(START, 90.0), sample(START.plusSeconds(30), 90.0), sample(START.plusSeconds(60), 90.0))),
                fields("labels", Map.of("instance", "sut-1", "pod", "b"), "samples", List.of(
                        sample(START.plusSeconds(15), 90.0), sample(START.plusSeconds(45), 90.0)))));
        reportSeriesCount(split, 2);

        Map<String, Object> result = evaluate(List.of(rule("cpu", 90.0, List.of("q1"))),
                List.of(query("q1", "sut-1")), Map.of("q1", split), phase);

        assertEquals("NOT_EVALUATED", result.get("status"));
        assertEquals("NOT_EVALUATED", targets(result).get(0).get("status"));
        assertEquals(0.4, ((Number) map(targets(result).get(0).get("coverage")).get("ratio")).doubleValue());

        Map<String, Object> violating = observation(START, List.of());
        violating.put("series", List.of(
                fields("labels", Map.of("pod", "a"), "samples", List.of(sample(START.plusSeconds(30), 90.1))),
                fields("labels", Map.of("pod", "b"), "samples", List.of(sample(START.plusSeconds(15), 90.0)))));
        Map<String, Object> violation = evaluate(List.of(rule("cpu", 90.0, List.of("q1"))),
                List.of(query("q1", "sut-1")), Map.of("q1", violating), phase);
        assertEquals("NOT_MET", violation.get("status"));
        assertEquals("NOT_MET", targets(violation).get(0).get("status"));

        Map<String, Object> missingMembership = observation(START, regularSamples(START, 60, 90.0));
        reportSeriesCount(missingMembership, 2);
        Map<String, Object> incomplete = evaluate(List.of(rule("cpu", 90.0, List.of("q1"))),
                List.of(query("q1", "sut-1")), Map.of("q1", missingMembership), phase);
        assertEquals("NOT_EVALUATED", incomplete.get("status"));
        assertEquals("NOT_EVALUATED", targets(incomplete).get(0).get("status"));
    }

    @Test void stalePartialAndUnknownFreshnessCannotProduceMet() {
        Map<String, Object> phase = phase(START, START.plusSeconds(60));
        List<Map<String, Object>> points = regularSamples(START, 60, 90.0);
        for (String[] quality : List.of(new String[]{"AVAILABLE", "STALE"},
                new String[]{"PARTIAL", "FRESH"}, new String[]{"AVAILABLE", "UNKNOWN"})) {
            Map<String, Object> result = evaluate(List.of(rule("cpu", 90.0, List.of("q1"))),
                    List.of(query("q1", "sut-1")),
                    Map.of("q1", observation(START, points, quality[0], quality[1])), phase);
            assertEquals("NOT_EVALUATED", result.get("status"));
            assertEquals("NOT_EVALUATED", targets(result).get(0).get("status"));
        }
    }

    @Test void staleOrMismatchedBusinessSnapshotsCannotProveAThresholdViolation() {
        Map<String, Object> phase = phase(START, START.plusSeconds(60));
        Map<String, Object> rule = businessRule("business-q", "afterDrain", 10.0);
        List<Map<String, Object>> rules = List.of(rule);
        List<Map<String, Object>> queries = List.of(businessQuery("business-q", "afterDrain"));

        Map<String, Object> stale = businessObservation(12.0, "STALE", "after-drain");
        Map<String, Object> staleResult = evaluate(rules, queries, Map.of("business-q", stale), phase);
        assertEquals("NOT_EVALUATED", staleResult.get("status"));
        assertEquals("NOT_EVALUATED", maps(staleResult.get("targets")).get(0).get("status"));
        assertEquals("NOT_EVALUATED", maps(staleResult.get("items")).get(0).get("status"));
        assertFalse(SutThresholdEvaluator.hasProvenViolation(rules, "business-q", stale, phase,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10)));

        Map<String, Object> mismatched = businessObservation(12.0, "FRESH", "steady-window-query");
        Map<String, Object> mismatchedResult = evaluate(rules, queries, Map.of("business-q", mismatched), phase);
        assertEquals("NOT_EVALUATED", mismatchedResult.get("status"));
        assertEquals("NOT_EVALUATED", maps(mismatchedResult.get("targets")).get(0).get("status"));
        assertFalse(SutThresholdEvaluator.hasProvenViolation(rules, "business-q", mismatched, phase,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10)));

        Map<String, Object> fresh = businessObservation(12.0, "FRESH", "after-drain");
        Map<String, Object> freshResult = evaluate(rules, queries, Map.of("business-q", fresh), phase);
        assertEquals("NOT_MET", freshResult.get("status"));
        assertEquals("NOT_MET", maps(freshResult.get("targets")).get(0).get("status"));
        assertTrue(SutThresholdEvaluator.hasProvenViolation(rules, "business-q", fresh, phase,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10)));
    }

    @Test void missingSamplesStayNotEvaluatedWithoutZeroFill() {
        Map<String, Object> result = evaluate(List.of(rule("cpu", 90.0, List.of("q1"))),
                List.of(query("q1", "sut-1")), Map.of(), phase(START, START.plusSeconds(60)));
        assertEquals("NOT_EVALUATED", result.get("status"));
        assertEquals("NOT_EVALUATED", targets(result).get(0).get("status"));
        assertEquals(0, targets(result).get(0).get("actual") == null ? 0 : 1);
    }

    @Test void oneFourAndTwentyFiveHourWindowsUseActualPrometheusGridAndStayBounded() {
        for (long durationSeconds : List.of(3_600L, 14_400L, 90_000L)) {
            Instant end = START.plusSeconds(durationSeconds);
            List<Map<String, Object>> points = regularSamples(START, durationSeconds, 90.0);
            Map<String, Object> result = evaluate(List.of(rule("cpu", 90.0, List.of("q1"))),
                    List.of(query("q1", "sut-1")), Map.of("q1", observation(START, points)), phase(START, end));
            assertEquals("MET", result.get("status"), durationSeconds + " second interval");
            Map<String, Object> coverage = map(targets(result).get(0).get("coverage"));
            assertEquals((int) (durationSeconds / STEP_SECONDS + 1), ((Number) coverage.get("expectedSampleCount")).intValue());
            assertEquals(1.0, ((Number) coverage.get("ratio")).doubleValue());
        }
    }

    private static Map<String, Object> evaluate(List<Map<String, Object>> rules, List<Map<String, Object>> queries,
                                                Map<String, Map<String, Object>> observations, Map<String, Object> phase) {
        return SutThresholdEvaluator.evaluate(rules, queries, observations, List.of(phase), STEP_SECONDS,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }

    private static List<Map<String, Object>> targets(Map<String, Object> result) {
        return maps(result.get("targets"));
    }

    private static Map<String, Object> rule(String id, double max, List<String> queryIds) {
        return fields("id", id, "profile", "base", "profilePath", "base", "metricId", "cpu", "metricName", "CPU",
                "unit", "percent", "reduction", "max", "operator", "<=", "value", max,
                "minimumCoverage", 0.95, "required", true, "queryIds", queryIds);
    }

    private static Map<String, Object> businessRule(String queryId, String window, double max) {
        return fields("id", "business-limit", "profile", "business", "profilePath", "business",
                "metricId", "count", "metricName", "Count", "unit", "count", "reduction", "max",
                "operator", "<=", "value", max, "required", true, "window", window,
                "queryIds", List.of(queryId));
    }

    private static Map<String, Object> businessQuery(String id, String sampling) {
        return fields("id", id, "profile", "business", "profilePath", "business", "profileRevision", "profile-rev",
                "sourceId", "dbhelper:orders", "sourceName", "DBHelper orders", "sourceRevision", "source-rev",
                "targetId", "job", "targetType", "job", "targetRevision", "target-rev", "metricId", "count",
                "title", "Count", "unit", "count", "kind", "gauge", "observationType", "business-call",
                "sampling", sampling);
    }

    private static Map<String, Object> businessObservation(double value, String freshness, String windowKind) {
        return fields("value", value, "observation", fields("kind", "timed-snapshot",
                "window", fields("kind", windowKind, "executionFinishedAt", START.toString(),
                        "observedAt", START.plusSeconds(30).toString()),
                "availability", fields("state", "AVAILABLE", "freshness", freshness, "quality", "SINGLE_VALUE")));
    }

    private static Map<String, Object> query(String id, String targetId) {
        return fields("id", id, "profile", "base", "profilePath", "base", "profileRevision", "profile-rev",
                "sourceId", "prom", "sourceName", "Prometheus", "sourceRevision", "source-rev",
                "targetId", targetId, "targetType", "app", "targetRevision", "target-rev", "metricId", "cpu", "title", "CPU");
    }

    private static Map<String, Object> observation(Instant queryStart, List<Map<String, Object>> samples) {
        return observation(queryStart, samples, "AVAILABLE", "FRESH");
    }

    private static Map<String, Object> observation(Instant queryStart, List<Map<String, Object>> samples,
                                                  String state, String freshness) {
        return fields("observation", fields("window", fields("queryStart", queryStart.toString()),
                        "availability", fields("state", state, "freshness", freshness,
                                "coverage", fields("seriesCount", 1))),
                "series", List.of(fields("samples", samples)));
    }

    private static void reportSeriesCount(Map<String, Object> observation, int count) {
        Map<String, Object> observationBody = map(observation.get("observation"));
        Map<String, Object> availability = map(observationBody.get("availability"));
        Map<String, Object> coverage = map(availability.get("coverage"));
        coverage.put("seriesCount", count);
        availability.put("coverage", coverage);
        observationBody.put("availability", availability);
        observation.put("observation", observationBody);
    }

    private static List<Map<String, Object>> regularSamples(Instant start, long duration, double value) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (long offset = 0; offset <= duration; offset += STEP_SECONDS) samples.add(sample(start.plusSeconds(offset), value));
        return samples;
    }

    private static Map<String, Object> sample(Instant timestamp, double value) {
        return fields("timestamp", timestamp.toString(), "value", value);
    }

    private static Map<String, Object> phase(Instant start, Instant end) {
        return fields("phase", "STEADY", "startAt", start.toString(), "endAt", end.toString());
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) if (row instanceof Map<?, ?> item) result.add(map(item));
        return result;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key instanceof String name) result.put(name, item); });
        return result;
    }

    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
}
