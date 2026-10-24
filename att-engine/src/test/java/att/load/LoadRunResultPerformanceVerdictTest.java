package att.load;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadRunResultPerformanceVerdictTest {
    @TempDir Path temp;

    @Test
    void rootPassCannotHideFailedOrUnevaluableWorkloadsAndAllPassingChildrenRemainMet() throws Exception {
        LoadScenario scenario = scenario();
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, 1_000L, 100L);
        metrics.finish(1_100L);
        LoadMetricsSnapshot snapshot = metrics.snapshot();
        ThresholdResult rootPass = new ThresholdResult("aggregate", ">= 0/s", "1.000/s", true, null);
        ThresholdResult childPass = new ThresholdResult("steady.completedRate", ">= 0/s", "1.000/s", true, null);
        ThresholdResult childFail = new ThresholdResult("steady.p95", "< 10ms", "20.000ms", false, "threshold missed");
        ThresholdResult childUnevaluated = new ThresholdResult("steady.completedRate", ">= 0/s", "N/A", false,
                "window evidence is incomplete", "NOT_EVALUATED");

        LoadRunResult failing = root(scenario, snapshot, rootPass, child("failed", scenario, snapshot, childFail),
                child("passing", scenario, snapshot, childPass));
        assertEquals("NOT_MET", failing.toMap().get("performanceVerdict"));
        assertEquals("FAIL", failing.toMap().get("status"));
        Map<?, ?> failingWorkloads = (Map<?, ?>) failing.toMap().get("workloads");
        assertEquals("NOT_MET", ((Map<?, ?>) failingWorkloads.get("failed")).get("performanceVerdict"));
        String failingHtml = writeHtml(failing);
        assertTrue(failingHtml.contains("Performance verdict: <span class=\"badge fail\">NOT_MET</span>"));
        assertTrue(workloadRow(failingHtml, "failed").contains("<td class=\"fail\">NOT_MET</td>"));

        LoadRunResult unevaluated = root(scenario, snapshot, rootPass, child("unknown", scenario, snapshot, childUnevaluated),
                child("passing", scenario, snapshot, childPass));
        assertEquals("NOT_EVALUATED", unevaluated.toMap().get("performanceVerdict"));
        assertEquals("PASS", unevaluated.toMap().get("status"));
        Map<?, ?> unevaluatedWorkloads = (Map<?, ?>) unevaluated.toMap().get("workloads");
        assertEquals("NOT_EVALUATED", ((Map<?, ?>) unevaluatedWorkloads.get("unknown")).get("performanceVerdict"));
        String unevaluatedHtml = writeHtml(unevaluated);
        assertTrue(unevaluatedHtml.contains("Performance verdict: <span class=\"badge info\">NOT_EVALUATED</span>"));
        assertTrue(workloadRow(unevaluatedHtml, "unknown").contains("<td class=\"info\">NOT_EVALUATED</td>"));

        LoadRunResult allPassing = root(scenario, snapshot, rootPass, child("one", scenario, snapshot, childPass),
                child("two", scenario, snapshot, childPass));
        assertEquals("MET", allPassing.toMap().get("performanceVerdict"));
        Map<?, ?> passingWorkloads = (Map<?, ?>) allPassing.toMap().get("workloads");
        assertEquals("MET", ((Map<?, ?>) passingWorkloads.get("one")).get("performanceVerdict"));
        assertEquals("MET", ((Map<?, ?>) passingWorkloads.get("two")).get("performanceVerdict"));
        String passingHtml = writeHtml(allPassing);
        assertTrue(passingHtml.contains("Performance verdict: <span class=\"badge pass\">MET</span>"));
        assertTrue(workloadRow(passingHtml, "one").contains("<td class=\"pass\">MET</td>"));
        assertTrue(workloadRow(passingHtml, "two").contains("<td class=\"pass\">MET</td>"));
    }

    private LoadRunResult root(LoadScenario scenario, LoadMetricsSnapshot metrics, ThresholdResult rootThreshold,
                               LoadRunResult... children) {
        Map<String, LoadRunResult> workloads = new LinkedHashMap<String, LoadRunResult>();
        for (LoadRunResult child : children) workloads.put(child.runId(), child);
        return new LoadRunResult("root-" + children[0].runId(), scenario, Instant.ofEpochMilli(1_000L),
                Instant.ofEpochMilli(1_100L), metrics, new LoadThresholdSummary(Collections.singletonList(rootThreshold)),
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(), workloads);
    }

    private LoadRunResult child(String id, LoadScenario scenario, LoadMetricsSnapshot metrics, ThresholdResult threshold) {
        return new LoadRunResult(id, scenario, Instant.ofEpochMilli(1_000L), Instant.ofEpochMilli(1_100L), metrics,
                new LoadThresholdSummary(Collections.singletonList(threshold)), Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap());
    }

    private String writeHtml(LoadRunResult result) throws Exception {
        Path report = new LoadReportWriter().write(temp, result);
        return new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
    }

    private String workloadRow(String html, String id) {
        int start = html.indexOf("<tr><td><b>" + id + "</b>");
        assertTrue(start >= 0, "missing workload row " + id);
        int end = html.indexOf("</tr>", start);
        assertTrue(end > start, "unterminated workload row " + id);
        return html.substring(start, end + 5);
    }

    private LoadScenario scenario() {
        return new LoadScenario(Paths.get("multi.yaml"), "template", "T", Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(), LoadScenario.Model.CLOSED, 1, 0.0, null, Duration.ZERO,
                Duration.ZERO, Duration.ofMillis(100), Duration.ZERO, Duration.ZERO, 0, "",
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());
    }
}
