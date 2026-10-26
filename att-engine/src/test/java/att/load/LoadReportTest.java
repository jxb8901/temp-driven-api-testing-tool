package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

    @Test void reportBudgetFailureRemovesPartialReportAndSidecarFiles() throws Exception {
        Instant started = Instant.parse("2026-10-26T00:00:00Z");
        long startMs = started.toEpochMilli();
        LoadScenario scenario = new LoadScenario(Paths.get("report-budget.yaml"), "template", "SIMPLE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, "closed",
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO,
                1, "drop", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, startMs, 1_000L);
        metrics.finish(startMs + 1_000L);
        LoadRunResult result = new LoadRunResult("report-budget", scenario, started, started.plusSeconds(1),
                metrics.snapshot(), LoadThresholdSummary.empty(), Collections.<String,Object>emptyMap(),
                Collections.<String,Object>emptyMap()).withResources(Collections.<String,Object>singletonMap(
                        "largeDiagnostic", repeat("x", 16 * 1024 * 1024)));

        IOException failure = assertThrows(IOException.class, () -> new LoadReportWriter().write(temp, result));
        assertTrue(failure.getMessage().contains("16 MiB"), failure.getMessage());
        Path runDirectory = temp.resolve("load/report-budget");
        assertFalse(Files.exists(runDirectory.resolve("load-summary.json")));
        assertFalse(Files.exists(runDirectory.resolve("load-summary.yaml")));
        assertFalse(Files.exists(runDirectory.resolve("report/index.html")));
        assertFalse(Files.exists(runDirectory.resolve("details/v1/manifest.json")));
        assertEquals("PASS", result.status().name(), "report failure does not mutate the executed LOAD outcome");
        assertEquals(0, result.exitCode());
    }

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
                Paths.get("schemas/att-load-summary-v1.3.schema.json"),
                new String(Files.readAllBytes(runDirectory.resolve("load-summary.json")), StandardCharsets.UTF_8)));
        Map<String, Object> legacySummary = new LinkedHashMap<String, Object>(json);
        Map<String, Object> legacyMetrics = new LinkedHashMap<String, Object>((Map<String, Object>) json.get("metrics"));
        for (String added : java.util.Arrays.asList("schedulerWakeups", "submitLagCount", "submitLagMeanMs",
                "submitLagMaxMs", "workerQueueDepth", "workerQueueDepthPeak", "generator"))
            legacyMetrics.remove(added);
        legacySummary.put("metrics", legacyMetrics);
        assertDoesNotThrow(() -> att.validation.JsonSchemaVerifier.verifyJson(
                Paths.get("schemas/att-load-summary-v1.3.schema.json"), att.validation.JsonSupport.write(legacySummary)),
                "summaries with optional telemetry fields remain valid when new telemetry fields are absent");
        assertEquals("att-load-summary/v1.3", json.get("schemaVersion"));
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
                Paths.get("schemas/att-load-summary-v1.3.schema.json"),
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
        LoadMetricsSnapshot snapshot = metrics.snapshot();
        @SuppressWarnings("unchecked") Map<String, Object> windowMetrics =
                (Map<String, Object>) snapshot.value("windowMetrics");
        @SuppressWarnings("unchecked") Map<String, Object> timeSeries =
                (Map<String, Object>) windowMetrics.get("timeSeries");
        @SuppressWarnings("unchecked") List<Map<String, Object>> trendPoints =
                (List<Map<String, Object>>) timeSeries.get("points");
        Map<String, Object> steadyTrend = trendPoints.stream()
                .filter(point -> ((Number) point.get("windowStartEpochMs")).longValue() == startMs + 1_000L)
                .findFirst().orElseThrow(() -> new AssertionError(trendPoints.toString()));
        Map<String, Object> drainTrend = trendPoints.stream()
                .filter(point -> ((Number) point.get("windowStartEpochMs")).longValue() == startMs + 2_000L)
                .findFirst().orElseThrow(() -> new AssertionError(trendPoints.toString()));
        assertTrue(((List<?>) steadyTrend.get("phaseCoverage")).contains("STEADY"));
        assertEquals(1, steadyTrend.get("latencySampleCount"));
        assertEquals(1L, steadyTrend.get("latencyObservationCount"));
        assertEquals(1_000L, steadyTrend.get("latencyP95Ms"));
        assertEquals(1L, steadyTrend.get("completed"));
        assertTrue(((List<?>) drainTrend.get("phaseCoverage")).contains("DRAIN"));
        assertEquals(1L, drainTrend.get("completed"));
        assertEquals(0, drainTrend.get("latencySampleCount"));
        @SuppressWarnings("unchecked") Map<String, Object> windows =
                (Map<String, Object>) windowMetrics.get("windows");
        @SuppressWarnings("unchecked") Map<String, Object> steadyWindow =
                (Map<String, Object>) windows.get("steady");
        @SuppressWarnings("unchecked") Map<String, Object> cohort =
                (Map<String, Object>) steadyWindow.get("cohort");
        @SuppressWarnings("unchecked") Map<String, Object> cohortLatency =
                (Map<String, Object>) cohort.get("latency");
        assertEquals(1_000L, cohortLatency.get("p95Ms"));
        LoadRunResult result = new LoadRunResult("phase-run", scenario, started, started.plusMillis(2_500L), snapshot);
        Path report = new LoadReportWriter().write(temp, result);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("Latency follows scheduled cohorts, including completions in later phases or drain"));
        assertTrue(html.contains("sample n=1 / 1 observations"));

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

    @Test void emptySelectedWindowClearsWarmupOnlyTrendCharts() throws Exception {
        Instant started = Instant.parse("2026-10-24T00:00:00Z");
        long startMs = started.toEpochMilli();
        Map<String, Object> steadyThreshold = Collections.<String, Object>singletonMap("p95", "<= 20ms");
        LoadScenario scenario = new LoadScenario(Paths.get("warmup-only.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 1.0, "1/s",
                Duration.ofMillis(250), Duration.ZERO, Duration.ofMillis(250), Duration.ZERO, Duration.ZERO,
                1, "drop", Collections.<String, Object>singletonMap("steady", steadyThreshold), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, startMs, 500L);
        metrics.onEvent(LoadEvent.completed("warmup-only", "arrivalRate", "WARMUP", "warmup", null, 1,
                startMs + 100L, startMs + 100L, startMs + 150L, ResultStatus.PASS));
        metrics.finish(startMs + 500L);

        @SuppressWarnings("unchecked") Map<String, Object> windowMetrics =
                (Map<String, Object>) metrics.snapshot().value("windowMetrics");
        @SuppressWarnings("unchecked") Map<String, Object> series =
                (Map<String, Object>) windowMetrics.get("timeSeries");
        @SuppressWarnings("unchecked") List<Map<String, Object>> points =
                (List<Map<String, Object>>) series.get("points");
        assertEquals(1, points.size(), "the fixture has one coarse bucket spanning warm-up and steady");
        assertEquals(startMs, ((Number) points.get(0).get("windowStartEpochMs")).longValue());
        assertEquals(1_000L, ((Number) points.get(0).get("durationMs")).longValue());
        assertTrue(((List<?>) points.get(0).get("phaseCoverage")).contains("WARMUP"));
        assertTrue(((List<?>) points.get(0).get("phaseCoverage")).contains("STEADY"));
        assertEquals(1L, points.get(0).get("completed"), "the only completion belongs to warm-up");
        @SuppressWarnings("unchecked") Map<String, Object> windows = (Map<String, Object>) windowMetrics.get("windows");
        @SuppressWarnings("unchecked") Map<String, Object> steadyWindow = (Map<String, Object>) windows.get("steady");
        @SuppressWarnings("unchecked") Map<String, Object> cohort = (Map<String, Object>) steadyWindow.get("cohort");
        @SuppressWarnings("unchecked") Map<String, Object> latency = (Map<String, Object>) cohort.get("latency");
        assertEquals(0L, latency.get("observationCount"));

        LoadRunResult result = new LoadRunResult("warmup-only", scenario, started, started.plusMillis(500), metrics.snapshot());
        Path report = new LoadReportWriter().write(temp, result);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        int chartStart = html.indexOf("function chart(id,legendId,series,unit,baseMax,opts)");
        int clear = html.indexOf("svg.textContent='';", chartStart);
        int emptyWindowCheck = html.indexOf("if(!pts.length)", chartStart);
        assertTrue(chartStart >= 0);
        assertTrue(clear > chartStart && clear < emptyWindowCheck,
                "each window selection must clear the previous SVG before the empty-series return");
        assertTrue(html.contains("legendList.textContent='';"), "the empty selection must also clear the previous legend");
        assertTrue(html.contains("No data for selected window"), "empty charts need a visible and accessible state");
        assertTrue(html.contains("No complete time-series buckets inside selected window; partial boundary buckets are omitted."),
                "a window containing only mixed-boundary buckets must explain why its trend is empty");
        assertTrue(html.contains("Trend charts omit buckets that cross this window boundary to avoid mixing out-of-window events"),
                "the selected window must disclose that partial buckets are filtered");
        assertTrue(html.contains("if(select)select.addEventListener('change',function(){show(select.value)});"),
                "changing the window selector must rerender the selected window");
        assertTrue(html.contains("function inWindow(p,win){return p.windowStartEpochMs>=win.windowStartEpochMs&&p.windowStartEpochMs+p.durationMs<=win.windowEndEpochMs}"),
                "a trend bucket must fit entirely inside the selected window");
        assertTrue(html.contains("function currentPoints(){var key=select?select.value:'steady',win=w[key],scoped=windowSeries[key];if(key==='all')return points;if(scoped&&scoped.points)return scoped.points;if(!win)return points;return points.filter(function(p){return inWindow(p,win)})}"),
                "trend series must use window-aligned points and retain a safe fallback for older summaries");
        assertTrue(html.contains("function hasPartialBuckets(){"),
                "empty windows with omitted partial buckets must be distinguishable from windows with no samples");
        int emptyReturn = html.indexOf("return}svg.setAttribute", emptyWindowCheck);
        int sloDrawing = html.indexOf("var slo=id==='latency-chart'", chartStart);
        assertTrue(emptyReturn > emptyWindowCheck && emptyReturn < sloDrawing,
                "an empty selected window must return before drawing latency data or its p95 SLO");
    }

    @Test void subSecondSteadyTrendShowsSteadyEventsWithoutWarmupContamination() throws Exception {
        Instant started = Instant.parse("2026-10-24T00:00:00Z");
        long startMs = started.toEpochMilli();
        LoadScenario scenario = new LoadScenario(Paths.get("subsecond-steady.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.ARRIVAL_RATE, 0, 1.0, "1/s",
                Duration.ofMillis(250), Duration.ZERO, Duration.ofMillis(250), Duration.ZERO, Duration.ZERO,
                1, "drop", Collections.emptyMap(), Collections.emptyMap());
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, startMs, 500L);
        metrics.onEvent(LoadEvent.completed("subsecond-steady", "arrivalRate", "WARMUP", "warmup", null, 1,
                startMs + 100L, startMs + 100L, startMs + 150L, ResultStatus.PASS));
        metrics.onEvent(LoadEvent.completed("subsecond-steady", "arrivalRate", "STEADY", "steady", null, 2,
                startMs + 350L, startMs + 350L, startMs + 375L, ResultStatus.PASS));
        metrics.finish(startMs + 500L);

        LoadRunResult result = new LoadRunResult("subsecond-steady", scenario, started, started.plusMillis(500), metrics.snapshot());
        Path report = new LoadReportWriter().write(temp, result);
        String html = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(html.contains("Trend charts use buckets aligned to this configured window"));
        assertTrue(html.contains("if(scoped&&scoped.points)return scoped.points"),
                "the selected window must use its dedicated trend series");

        String marker = "<script type=\"application/json\" id=\"att-load-summary-data\">";
        int dataStart = html.indexOf(marker);
        assertTrue(dataStart >= 0, "the report must embed the chart data");
        int dataEnd = html.indexOf("</script>", dataStart + marker.length());
        @SuppressWarnings("unchecked") Map<String, Object> embedded = att.validation.JsonSupport.mapper()
                .readValue(html.substring(dataStart + marker.length(), dataEnd), Map.class);
        @SuppressWarnings("unchecked") Map<String, Object> embeddedMetrics = (Map<String, Object>) embedded.get("metrics");
        @SuppressWarnings("unchecked") Map<String, Object> windowMetrics = (Map<String, Object>) embeddedMetrics.get("windowMetrics");
        @SuppressWarnings("unchecked") Map<String, Object> timeSeries = (Map<String, Object>) windowMetrics.get("timeSeries");
        @SuppressWarnings("unchecked") List<Map<String, Object>> allPoints = (List<Map<String, Object>>) timeSeries.get("points");
        assertEquals(2L, ((Number) allPoints.get(0).get("completed")).longValue(), "the full-execution bucket contains both phases");
        @SuppressWarnings("unchecked") Map<String, Object> windowSeries = (Map<String, Object>) timeSeries.get("windowSeries");
        @SuppressWarnings("unchecked") Map<String, Object> steadySeries = (Map<String, Object>) windowSeries.get("steady");
        @SuppressWarnings("unchecked") List<Map<String, Object>> steadyPoints = (List<Map<String, Object>>) steadySeries.get("points");
        assertEquals(1, steadyPoints.size(), "a valid sub-second steady window must keep its event");
        Map<String, Object> steadyPoint = steadyPoints.get(0);
        assertEquals(startMs + 250L, ((Number) steadyPoint.get("windowStartEpochMs")).longValue());
        assertEquals(250L, ((Number) steadyPoint.get("durationMs")).longValue());
        assertEquals(1L, ((Number) steadyPoint.get("scheduled")).longValue(), "warm-up arrivals must not enter the steady trend");
        assertEquals(1L, ((Number) steadyPoint.get("completed")).longValue());
        assertEquals(1L, ((Number) steadyPoint.get("latencyObservationCount")).longValue());
        assertEquals(25L, ((Number) steadyPoint.get("latencyP95Ms")).longValue());
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

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(Math.min(count, 1) * value.length());
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
