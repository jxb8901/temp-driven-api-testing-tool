package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadReportTest {
    @TempDir Path temp;

    @Test void writesBoundedArrivalSummaryHtmlAndEvidenceLinks() throws Exception {
        Instant started = Instant.parse("2026-09-22T00:00:00Z");
        long startMs = started.toEpochMilli();
        LoadScenario scenario = new LoadScenario(Paths.get("arrival.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 20.0, "20/s",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ZERO,
                2, "drop", Collections.singletonMap("p95", "<= 100ms"), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, startMs, 5_000L);
        metrics.onEvent(LoadEvent.completed("run-24", "arrivalRate", "WARMUP", "warmup-1", null, 1,
                startMs, startMs, startMs + 20L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("run-24", "arrivalRate", "STEADY", "steady-1", null, 2,
                startMs + 2_000L, startMs + 2_000L, startMs + 2_050L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.dropped("run-24", "arrivalRate", "STEADY", "drop-1", 3, startMs + 2_000L, startMs + 2_100L));
        metrics.finish(startMs + 5_000L);
        LoadMetricsSnapshot snapshot = metrics.snapshot();
        LoadThresholdSummary thresholds = new LoadThresholdSummary(Collections.singletonList(
                new ThresholdResult("p95", "<= 100ms", "50.000ms", true, null)));
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("count", 1);
        evidence.put("items", Collections.<Map<String, Object>>singletonList(new LinkedHashMap<String, Object>() {{
            put("iterationId", "steady-1"); put("status", "SAMPLE"); put("path", "samples/00001-deadbeef.json");
        }}));
        Map<String, Object> resources = new LinkedHashMap<String, Object>();
        Map<String, Object> dbHelper = new LinkedHashMap<String, Object>();
        dbHelper.put("timeoutCount", 2L); dbHelper.put("waiting", 1);
        resources.put("db", Collections.<String, Object>singletonMap("orders", dbHelper));
        LoadRunResult result = new LoadRunResult("run-24", scenario, started, started.plusSeconds(5), snapshot,
                thresholds, evidence, resources);

        Path report = new LoadReportWriter().write(temp, result);
        Path runDirectory = temp.resolve("load/run-24");
        assertEquals(runDirectory.resolve("report/index.html").toAbsolutePath().normalize(), report.toAbsolutePath().normalize());
        assertTrue(Files.isRegularFile(runDirectory.resolve("load-summary.json")));
        assertTrue(Files.isRegularFile(runDirectory.resolve("load-summary.yaml")));
        assertTrue(Files.isRegularFile(report));

        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper()
                .readValue(runDirectory.resolve("load-summary.json").toFile(), Map.class);
        assertDoesNotThrow(() -> att.validation.JsonSchemaVerifier.verifyJson(
                Paths.get("schemas/att-load-summary-v1.0.schema.json"),
                new String(Files.readAllBytes(runDirectory.resolve("load-summary.json")), StandardCharsets.UTF_8)));
        assertEquals("att-load-summary/v1.0", json.get("schemaVersion"));
        assertEquals("PASS", json.get("status"));
        assertTrue(json.containsKey("timing"));
        assertTrue(json.containsKey("resources"));
        @SuppressWarnings("unchecked") Map<String, Object> jsonMetrics = (Map<String, Object>) json.get("metrics");
        assertTrue(jsonMetrics.containsKey("phases"));
        assertTrue(jsonMetrics.containsKey("buckets"));

        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("Configured arrival rate"));
        assertTrue(html.contains("Achieved scheduling rate"));
        assertTrue(html.contains("Completed TPS"));
        assertTrue(html.contains("Dropped arrivals"));
        assertTrue(html.contains("SUT failures"));
        assertTrue(html.contains("Phase timing and warm-up separation"));
        assertTrue(html.contains("Resource diagnostics"));
        assertTrue(html.contains("../samples/00001-deadbeef.json"));
        assertTrue(html.contains("window.ATT_LOAD_SUMMARY"));
    }

    @Test void reportHandlesEmptyMetricsAndRuntimeErrorStatus() throws Exception {
        Instant started = Instant.parse("2026-09-22T01:00:00Z");
        LoadScenario scenario = new LoadScenario(Paths.get("closed.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.CLOSED, 2, 0.0, null,
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                0, "", Collections.emptyMap(), Collections.emptyMap());
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("runtimeError", 1L); values.put("model", "closed");
        LoadRunResult result = new LoadRunResult("empty-error", scenario, started, started.plusSeconds(1),
                new LoadMetricsSnapshot(values, Collections.emptyMap()));
        Path report = new LoadReportWriter().write(temp, result);
        assertEquals(ResultStatus.ERROR, result.status());
        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper()
                .readValue(temp.resolve("load/empty-error/load-summary.json").toFile(), Map.class);
        assertEquals("ERROR", json.get("status"));
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("No time-series buckets were observed."));
        assertTrue(html.contains("Configured users"));
    }
}
