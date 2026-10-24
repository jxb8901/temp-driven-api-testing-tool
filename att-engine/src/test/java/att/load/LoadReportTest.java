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
import java.util.ArrayList;
import java.util.List;
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
        evidence.put("policy", Collections.<String,Object>singletonMap("quotaMode", "independent"));
        evidence.put("workloads", Collections.<String,Object>singletonMap("payments", new LinkedHashMap<String,Object>() {{
            put("targetId", "PAYMENTS_TEMPLATE");
            put("eligibleSuccessCount", 7); put("samplingIneligibleSuccessCount", 1);
            put("retainedSuccessCount", 2); put("retainedFailureCount", 1);
            put("skippedSuccessQuotaCount", 3); put("skippedFailureQuotaCount", 0); put("skippedMissingEvidenceCount", 1);
        }}));
        evidence.put("items", Collections.<Map<String, Object>>singletonList(new LinkedHashMap<String, Object>() {{
            put("workloadId", "payments<&"); put("execId", "exec-1"); put("iterationId", "steady-1");
            put("status", "SAMPLE"); put("path", "samples/00001-deadbeef.json"); put("caseLog", "executions/exec-1/case log<&.log");
        }}));
        Map<String, Object> resources = new LinkedHashMap<String, Object>();
        Map<String, Object> dbHelper = new LinkedHashMap<String, Object>();
        dbHelper.put("timeoutCount", 2L); dbHelper.put("waiting", 1);
        resources.put("db", Collections.<String, Object>singletonMap("orders", dbHelper));
        Map<String, Object> mqHelper = new LinkedHashMap<String, Object>();
        mqHelper.put("timeoutCount", 3L); mqHelper.put("active", 0); mqHelper.put("idle", 1);
        resources.put("mq", Collections.<String, Object>singletonMap("broker", mqHelper));
        Map<String, Object> httpHelper = new LinkedHashMap<String, Object>();
        httpHelper.put("active", 1); httpHelper.put("idle", 2); httpHelper.put("waiting", 0); httpHelper.put("peakActive", 2);
        resources.put("http", Collections.<String, Object>singletonMap("gateway", httpHelper));
        LoadRunResult result = new LoadRunResult("run-24", scenario, started, started.plusSeconds(5), snapshot,
                thresholds, evidence, resources);

        Path runDirectory = temp.resolve("load/run-24");
        Files.createDirectories(runDirectory.resolve("samples"));
        Files.write(runDirectory.resolve("samples/00001-deadbeef.json"), Collections.singletonList("evidence"), StandardCharsets.UTF_8);
        Files.createDirectories(runDirectory.resolve("executions/exec-1"));
        Files.write(runDirectory.resolve("executions/exec-1/case log<&.log"), Collections.singletonList("case log"), StandardCharsets.UTF_8);
        Path report = new LoadReportWriter().write(temp, result);
        assertEquals(runDirectory.resolve("report/index.html").toAbsolutePath().normalize(), report.toAbsolutePath().normalize());
        assertTrue(Files.isRegularFile(runDirectory.resolve("load-summary.json")));
        assertTrue(Files.isRegularFile(runDirectory.resolve("load-summary.yaml")));
        assertTrue(Files.isRegularFile(report));
        String htmlReport = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(htmlReport.contains("Evidence coverage by workload"));
        assertTrue(htmlReport.contains("payments"));
        assertTrue(htmlReport.contains("PAYMENTS_TEMPLATE"));
        assertTrue(htmlReport.contains("Eligible successes"));
        assertTrue(htmlReport.contains("Sampling ineligible"));

        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper()
                .readValue(runDirectory.resolve("load-summary.json").toFile(), Map.class);
        assertDoesNotThrow(() -> att.validation.JsonSchemaVerifier.verifyJson(
                Paths.get("schemas/att-load-summary-v1.2.schema.json"),
                new String(Files.readAllBytes(runDirectory.resolve("load-summary.json")), StandardCharsets.UTF_8)));
        Map<String, Object> legacySummary = new LinkedHashMap<String, Object>(json);
        Map<String, Object> legacyMetrics = new LinkedHashMap<String, Object>((Map<String, Object>) json.get("metrics"));
        for (String added : java.util.Arrays.asList("schedulerWakeups", "submitLagCount", "submitLagMeanMs",
                "submitLagMaxMs", "workerQueueDepth", "workerQueueDepthPeak", "generator"))
            legacyMetrics.remove(added);
        legacySummary.put("metrics", legacyMetrics);
        assertDoesNotThrow(() -> att.validation.JsonSchemaVerifier.verifyJson(
                Paths.get("schemas/att-load-summary-v1.2.schema.json"), att.validation.JsonSupport.write(legacySummary)),
                "summaries with optional telemetry fields remain valid when new telemetry fields are absent");
        assertEquals("att-load-summary/v1.2", json.get("schemaVersion"));
        assertEquals("PASS", json.get("status"));
        assertTrue(json.containsKey("timing"));
        assertTrue(json.containsKey("resources"));
        assertEquals("report/index.html", json.get("report"));
        @SuppressWarnings("unchecked") Map<String, Object> jsonMetrics = (Map<String, Object>) json.get("metrics");
        assertTrue(jsonMetrics.containsKey("phases"));
        assertTrue(jsonMetrics.containsKey("buckets"));
        assertTrue(((Map<?, ?>) jsonMetrics.get("generator")).containsKey("heapPeakUsedBytes"));
        @SuppressWarnings("unchecked") Map<String, Object> jsonResources = (Map<String, Object>) json.get("resources");
        assertEquals(2L, ((Number) ((Map<?, ?>) ((Map<?, ?>) jsonResources.get("db")).get("orders")).get("timeoutCount")).longValue());
        assertEquals(3L, ((Number) ((Map<?, ?>) ((Map<?, ?>) jsonResources.get("mq")).get("broker")).get("timeoutCount")).longValue());
        assertEquals(2, ((Number) ((Map<?, ?>) ((Map<?, ?>) jsonResources.get("http")).get("gateway")).get("peakActive")).intValue());
        Map<String, Object> missingReport = new LinkedHashMap<String, Object>(json);
        missingReport.remove("report");
        assertThrows(IllegalArgumentException.class, () -> att.validation.JsonSchemaVerifier.verifyJson(
                Paths.get("schemas/att-load-summary-v1.2.schema.json"),
                att.validation.JsonSupport.write(missingReport)));

        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("Configured arrival rate"));
        assertTrue(html.contains("Achieved scheduling rate"));
        assertTrue(html.contains("Actual completed TPS"));
        assertTrue(html.contains("Dropped arrivals"));
        assertTrue(html.contains("SUT failures"));
        assertTrue(html.contains("Phase windows and event-time completion rates"));
        assertTrue(html.contains("Resource and generator diagnostics"));
        assertTrue(html.contains("orders"));
        assertTrue(html.contains("broker"));
        assertTrue(html.contains("timeoutCount"));
        assertTrue(html.contains("peakActive"));
        assertTrue(html.contains("../samples/00001-deadbeef.json"));
        assertTrue(html.contains("../executions/exec-1/case%20log%3C%26.log"));
        assertTrue(html.contains("payments&lt;&amp;"));
        assertTrue(html.contains("<script type=\"application/json\" id=\"att-load-summary-data\">"));
        assertFalse(html.contains("window.ATT_LOAD_SUMMARY="));
    }

    @Test void phaseTableSeparatesCrossPhaseCompletionsFromScheduledCohortsAndLateDrain() throws Exception {
        Instant started = Instant.parse("2026-10-24T00:00:00Z");
        long startMs = started.toEpochMilli();
        LoadScenario scenario = new LoadScenario(Paths.get("phase-times.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 10.0, "10/s",
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                2, "drop", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, startMs, 2_500L);
        metrics.onEvent(LoadEvent.completed("phase-run", "arrivalRate", "RAMP_UP", "ramp-cohort", null, 1,
                startMs + 500L, startMs + 500L, startMs + 1_500L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("phase-run", "arrivalRate", "STEADY", "steady-cohort", null, 2,
                startMs + 1_500L, startMs + 1_500L, startMs + 2_500L, ResultStatus.PASS));
        metrics.finish(startMs + 2_500L);
        LoadRunResult result = new LoadRunResult("phase-run", scenario, started, started.plusMillis(2_500L), metrics.snapshot());
        Path report = new LoadReportWriter().write(temp, result);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);

        String rampUp = phaseRow(html, "RAMP_UP");
        assertTrue(rampUp.contains("</td><td>1</td><td>1</td><td>0</td><td>0</td>"), rampUp);
        assertTrue(rampUp.contains("</td><td>1</td><td>0.00%"), "the scheduled ramp-up cohort must retain its eventual outcome: " + rampUp);
        String steady = phaseRow(html, "STEADY");
        assertTrue(steady.contains("</td><td>1</td><td>1</td><td>1</td><td>1</td>"), steady);
        assertTrue(steady.contains("<td>1.000/s</td><td>1.000/s</td>"), steady);
        String drain = phaseRow(html, "DRAIN");
        assertTrue(drain.contains("</td><td>0</td><td>0</td><td>1</td><td>1</td>"), drain);
        assertTrue(drain.contains("<td>2.000/s</td><td>2.000/s</td>"), drain);
        assertTrue(html.contains("Scheduled cohort completed"));
        assertTrue(html.contains("Event-time completions"));
        assertFalse(html.contains("<th>Completed TPS</th>"));
        assertFalse(html.contains("<th>Successful TPS</th>"));
    }

    private static String phaseRow(String html, String phase) {
        String start = "<tr><td><b>" + phase + "</b>";
        int from = html.indexOf(start);
        assertTrue(from >= 0, "missing phase row " + phase);
        int end = html.indexOf("</tr>", from);
        assertTrue(end > from, "unterminated phase row " + phase);
        return html.substring(from, end + 5);
    }

    @Test void reportDoesNotCreateCaseLogLinksForMissingOrEscapingArtifacts() throws Exception {
        Instant started = Instant.parse("2026-09-22T04:00:00Z");
        LoadScenario scenario = new LoadScenario(Paths.get("evidence.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null,
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                0, "", Collections.emptyMap(), Collections.emptyMap());
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(new LinkedHashMap<String, Object>() {{
            put("workloadId", "safe"); put("iterationId", "iteration-1"); put("status", "FAILURE");
            put("caseLog", "../outside/case.log"); put("path", "samples/retained.json");
        }});
        Path runDirectory = temp.resolve("load/evidence-links");
        Files.createDirectories(runDirectory.resolve("samples"));
        Files.write(runDirectory.resolve("samples/retained.json"), Collections.singletonList("evidence"), StandardCharsets.UTF_8);
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.write(outside.resolve("secret.json"), Collections.singletonList("secret"), StandardCharsets.UTF_8);
        Files.createSymbolicLink(runDirectory.resolve("samples/escaped.json"), outside.resolve("secret.json"));
        items.add(new LinkedHashMap<String, Object>() {{
            put("workloadId", "symlink"); put("iterationId", "iteration-2"); put("status", "FAILURE");
            put("path", "samples/escaped.json");
        }});
        evidence.put("items", items);
        LoadRunResult result = new LoadRunResult("evidence-links", scenario, started, started.plusSeconds(1),
                new LoadMetricsSnapshot(Collections.<String, Object>emptyMap(), Collections.<String, Map<String, Object>>emptyMap()),
                LoadThresholdSummary.empty(), evidence, Collections.<String, Object>emptyMap());
        Path report = new LoadReportWriter().write(temp, result);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("Case Log"));
        assertTrue(html.contains("Not retained"));
        assertFalse(html.contains("href=\"../../outside/case.log\""));
        assertTrue(html.contains("href=\"../samples/retained.json\""));
        assertFalse(html.contains("href=\"../samples/escaped.json\""));
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

    @Test void writesThresholdFailureToAllReportFormats() throws Exception {
        Instant started = Instant.parse("2026-09-22T02:00:00Z");
        long startMs = started.toEpochMilli();
        LoadScenario scenario = new LoadScenario(Paths.get("closed-fail.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null,
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                0, "", Collections.singletonMap("p95", "<= 100ms"), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, startMs, 1_000L);
        metrics.onEvent(LoadEvent.completed("run-24-fail", "closed", "STEADY", "iteration-1", "user-1", 1,
                startMs, startMs, startMs + 250L, ResultStatus.PASS));
        metrics.finish(startMs + 1_000L);
        LoadThresholdSummary thresholds = new LoadThresholdSummary(Collections.singletonList(
                new ThresholdResult("p95", "<= 100ms", "250.000ms", false, "p95 250.000ms exceeds <= 100ms")));
        LoadRunResult result = new LoadRunResult("run-24-fail", scenario, started, started.plusSeconds(1), metrics.snapshot(), thresholds,
                Collections.emptyMap(), Collections.emptyMap());

        Path report = new LoadReportWriter().write(temp, result);
        Path runDirectory = temp.resolve("load/run-24-fail");
        String jsonText = new String(Files.readAllBytes(runDirectory.resolve("load-summary.json")), StandardCharsets.UTF_8);
        String yamlText = new String(Files.readAllBytes(runDirectory.resolve("load-summary.yaml")), StandardCharsets.UTF_8);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper().readValue(jsonText, Map.class);
        assertEquals("FAIL", json.get("status"));
        assertEquals(1, ((Number) json.get("exitCode")).intValue());
        @SuppressWarnings("unchecked") Map<String, Object> jsonThresholds = (Map<String, Object>) json.get("thresholds");
        assertEquals("NOT_MET", jsonThresholds.get("status"));
        assertEquals(Boolean.FALSE, jsonThresholds.get("legacyPassed"));
        assertEquals("NOT_MET", jsonThresholds.get("performanceVerdict"));
        assertTrue(jsonText.contains("250.000ms exceeds <= 100ms"));
        assertTrue(yamlText.contains("250.000ms exceeds <= 100ms"));
        assertTrue(html.contains("FAIL"));
        assertTrue(html.contains("250.000ms exceeds &lt;= 100ms"));
    }

    @Test void reportScenarioOmitsInputsAndToolArguments() throws Exception {
        Instant started = Instant.parse("2026-09-22T03:00:00Z");
        Map<String, Object> nestedInputs = new LinkedHashMap<String, Object>();
        nestedInputs.put("password", "secret-password-value");
        nestedInputs.put("payload", Collections.<String, Object>singletonMap("authorization", "secret-payload-value"));
        Map<String, Object> nestedArguments = new LinkedHashMap<String, Object>();
        nestedArguments.put("token", "secret-token-value");
        nestedArguments.put("request", Collections.<String, Object>singletonMap("body", "secret-body-value"));
        LoadScenario scenario = new LoadScenario(Paths.get("safe-summary.yaml"), "tool", "safe-tool",
                nestedArguments, nestedInputs, LoadScenario.Model.CLOSED, 2, 0.0, null,
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                0, "", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, started.toEpochMilli(), 1_000L);
        metrics.finish(started.plusSeconds(1).toEpochMilli());
        LoadRunResult result = new LoadRunResult("safe-summary", scenario, started, started.plusSeconds(1), metrics.snapshot());

        Path report = new LoadReportWriter().write(temp, result);
        Path runDirectory = temp.resolve("load/safe-summary");
        String jsonText = new String(Files.readAllBytes(runDirectory.resolve("load-summary.json")), StandardCharsets.UTF_8);
        String yamlText = new String(Files.readAllBytes(runDirectory.resolve("load-summary.yaml")), StandardCharsets.UTF_8);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked") Map<String, Object> json = att.validation.JsonSupport.mapper().readValue(jsonText, Map.class);
        @SuppressWarnings("unchecked") Map<String, Object> summaryScenario = (Map<String, Object>) json.get("scenario");
        @SuppressWarnings("unchecked") Map<String, Object> summaryTarget = (Map<String, Object>) summaryScenario.get("target");
        assertEquals("safe-tool", summaryTarget.get("id"));
        assertFalse(summaryScenario.containsKey("inputs"));
        assertFalse(summaryTarget.containsKey("arguments"));
        assertTrue(jsonText.contains("safe-tool"));
        assertFalse(jsonText.contains("secret-password-value"));
        assertFalse(jsonText.contains("secret-payload-value"));
        assertFalse(jsonText.contains("secret-token-value"));
        assertFalse(jsonText.contains("secret-body-value"));
        assertFalse(yamlText.contains("secret-password-value"));
        assertFalse(yamlText.contains("secret-payload-value"));
        assertFalse(yamlText.contains("secret-token-value"));
        assertFalse(yamlText.contains("secret-body-value"));
        assertFalse(html.contains("secret-password-value"));
        assertFalse(html.contains("secret-payload-value"));
        assertFalse(html.contains("secret-token-value"));
        assertFalse(html.contains("secret-body-value"));
    }
}
