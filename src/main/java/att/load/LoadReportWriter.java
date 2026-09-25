package att.load;

import att.validation.JsonSupport;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Writes isolated machine-readable summaries and a self-contained Load performance report. */
public final class LoadReportWriter {
    public Path write(Path outputRoot, LoadRunResult result) throws IOException {
        Path runDirectory = outputRoot.resolve("load").resolve(result.runId()).toAbsolutePath().normalize();
        Files.createDirectories(runDirectory.resolve("report"));
        Map<String, Object> map = result.toMap();
        Files.write(runDirectory.resolve("load-summary.json"), JsonSupport.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(map));
        Files.write(runDirectory.resolve("load-summary.yaml"), new Yaml().dump(map).getBytes(StandardCharsets.UTF_8));
        Files.write(runDirectory.resolve("report/index.html"), html(result, map).getBytes(StandardCharsets.UTF_8));
        return runDirectory.resolve("report/index.html");
    }

    private String html(LoadRunResult result, Map<String, Object> summary) {
        Map<String, Object> metrics = result.metrics().values();
        StringBuilder html = new StringBuilder(64 * 1024);
        String status = result.status().name();
        String statusClass = result.passed() ? "pass" : "fail";
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<title>ATT Load ").append(escape(result.runId())).append("</title>");
        html.append("<style>");
        html.append("body{font:14px system-ui,-apple-system,sans-serif;line-height:1.4;margin:0;color:#172033;background:#f6f8fb}");
        html.append("main{max-width:1440px;margin:auto;padding:24px}h1{margin:.1em 0}.muted{color:#5d687b}.panel{background:#fff;border:1px solid #dce2eb;border-radius:8px;padding:16px;margin:16px 0;box-shadow:0 1px 2px #15233d0d}");
        html.append("table{width:100%;border-collapse:collapse;margin-top:8px}td,th{border-bottom:1px solid #e5e9f0;padding:7px;text-align:left;vertical-align:top}th{background:#f3f6fa;font-weight:650}tbody tr:hover{background:#fafcff}");
        html.append(".pass{color:#087443}.fail{color:#b42318}.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px}.kpi{border:1px solid #dce2eb;border-radius:8px;padding:12px;background:#fff}.kpi strong{display:block;font-size:1.45em;margin-top:3px}.kpi small{display:block;color:#5d687b;margin-top:4px}");
        html.append(".concepts{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px}.concept{border-radius:8px;padding:12px;background:#eef4ff;border-left:5px solid #356ae6}.concept.drop{background:#fff5e8;border-left-color:#d97706}.concept.error{background:#fff0f0;border-left-color:#c2413a}.concept strong{display:block;font-size:1.25em}.badge{display:inline-block;border-radius:999px;padding:2px 8px;font-weight:650}.badge.pass{background:#dcfce7}.badge.fail{background:#fee2e2}.badge.info{background:#e0e7ff;color:#243b82}.badge.drop{background:#ffedd5;color:#9a3412}");
        html.append("pre{white-space:pre-wrap;word-break:break-word;background:#f7f9fc;padding:10px;border-radius:5px;max-height:420px;overflow:auto}.scroll{overflow:auto}a{color:#2459b8}.empty{color:#68758a;font-style:italic}.note{font-size:.92em;color:#5d687b}");
        html.append("</style></head><body><main>");
        html.append("<header class=\"panel\"><h1>ATT Load ").append(escape(result.runId())).append("</h1>");
        html.append("<p class=\"muted\">Model: <b>").append(escape(result.scenario().model().wireName())).append("</b> · ");
        html.append("Status: <span class=\"badge ").append(statusClass).append("\">").append(escape(status)).append("</span> · Exit code: <b>").append(result.exitCode()).append("</b></p>");
        html.append("<p class=\"note\">Run ").append(escape(result.startedAt().toString())).append(" to ").append(escape(result.endedAt().toString()));
        html.append(" (duration ").append(display(summary.get("durationMs"))).append(" ms). Load output is isolated below this run directory.</p></header>");

        appendKpis(html, result, metrics);
        appendModelSemantics(html, result, metrics);
        appendPhaseTable(html, result, summary);
        appendMetricsTable(html, metrics);
        appendWorkloadTable(html, result);
        appendThresholdTable(html, result.thresholds().results());
        appendResourceTable(html, result.resources());
        appendEvidenceTable(html, result.evidence());
        appendTimeSeries(html, result);

        html.append("<section class=\"panel\"><h2>Machine-readable summary</h2><p class=\"note\">The complete bounded summary is available beside this report as ");
        html.append("<a href=\"../load-summary.json\">load-summary.json</a> and <a href=\"../load-summary.yaml\">load-summary.yaml</a>.</p></section>");
        String embedded = JsonSupport.write(summary).replace("<", "\\u003c");
        html.append("<script>window.ATT_LOAD_SUMMARY=").append(embedded).append(";</script>");
        html.append("</main></body></html>");
        return html.toString();
    }

    private void appendKpis(StringBuilder html, LoadRunResult result, Map<String, Object> metrics) {
        html.append("<section class=\"panel\"><h2>Run overview</h2><div class=\"kpis\">");
        kpi(html, "Completed TPS", rate(metrics.get("completedThroughput")), "measured completed iterations per second");
        kpi(html, "p95 latency", metrics.get("p95Ms"), "milliseconds, bounded reservoir");
        kpi(html, "SUT error rate", percent(metrics.get("sutErrorRate")), "application failures; runtime errors separate");
        if (result.scenario().model() == LoadScenario.Model.ARRIVAL_RATE) {
            kpi(html, "Configured arrival", rate(metrics.get("configuredArrivalRatePerSecond")), "target generator rate /s");
            kpi(html, "Achieved scheduling", rate(metrics.get("measuredAchievedArrivalRate")), "started iterations /s, measured phases");
            kpi(html, "Generator drops", percent(metrics.get("droppedRate")), "capacity saturation, not SUT error");
        } else {
            kpi(html, "Configured users", metrics.get("configuredUsers"), "closed virtual users");
            kpi(html, "Max active VUs", metrics.get("maxActiveVus"), "observed concurrent virtual users");
        }
        html.append("</div></section>");
    }

    private void appendModelSemantics(StringBuilder html, LoadRunResult result, Map<String, Object> metrics) {
        html.append("<section class=\"panel\"><h2>Workload semantics</h2><div class=\"concepts\">");
        if (result.scenario().model() == LoadScenario.Model.ARRIVAL_RATE) {
            concept(html, "Configured arrival rate", rate(metrics.get("configuredArrivalRatePerSecond")), "scenario target; it is not completed TPS", "");
            concept(html, "Achieved scheduling rate", rate(metrics.get("measuredAchievedArrivalRate")), "started / planned arrivals in measured phases", "");
            concept(html, "Completed TPS", rate(metrics.get("completedThroughput")), "iterations completed by the SUT per second", "");
            concept(html, "Dropped arrivals", count(metrics.get("measuredDropped")), "generator saturation; excluded from SUT error", "drop");
            concept(html, "SUT failures", count(metrics.get("measuredFailure")), "completed iterations with FAIL status", "error");
        } else {
            concept(html, "Configured users", count(metrics.get("configuredUsers")), "stable closed-model virtual users", "");
            concept(html, "Active VUs", count(metrics.get("maxActiveVus")), "maximum concurrently active virtual users", "");
            concept(html, "Completed TPS", rate(metrics.get("completedThroughput")), "measured completed iterations per second", "");
            concept(html, "SUT failures", count(metrics.get("measuredFailure")), "completed iterations with FAIL status", "error");
        }
        html.append("</div></section>");
    }

    private void appendPhaseTable(StringBuilder html, LoadRunResult result, Map<String, Object> summary) {
        html.append("<section class=\"panel\"><h2>Phase timing and warm-up separation</h2>");
        html.append("<p class=\"note\">Warm-up traffic is visible but excluded from measured SLA aggregates. Ramp-up, steady, and ramp-down remain measured.</p>");
        Map<String, Map<String, Object>> observed = result.metrics().phases();
        Object timingValue = summary.get("timing");
        if (!(timingValue instanceof Map)) { html.append("<p class=\"empty\">No phase timing available.</p></section>"); return; }
        Object phasesValue = ((Map<?, ?>) timingValue).get("phases");
        if (!(phasesValue instanceof List)) { html.append("<p class=\"empty\">No phase timing available.</p></section>"); return; }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Phase</th><th>Configured window</th><th>Measured</th><th>Scheduled</th><th>Started</th><th>Completed</th><th>Failures</th><th>Drops</th><th>TPS</th><th>p95</th></tr></thead><tbody>");
        for (Object item : (List<?>) phasesValue) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> configured = (Map<?, ?>) item; String phase = String.valueOf(configured.get("phase"));
            Map<String, Object> actual = observed.get(phase);
            html.append("<tr><td><b>").append(escape(phase)).append("</b></td><td>")
                    .append(escape(String.valueOf(configured.get("startAt")))).append(" → ")
                    .append(escape(String.valueOf(configured.get("endAt")))).append("<br>")
                    .append(display(configured.get("durationMs"))).append(" ms</td><td>")
                    .append(Boolean.TRUE.equals(configured.get("measured")) ? "yes" : "warm-up").append("</td>");
            html.append("<td>").append(display(actualValue(actual, "scheduled", 0L))).append("</td><td>")
                    .append(display(actualValue(actual, "started", 0L))).append("</td><td>")
                    .append(display(actualValue(actual, "completed", 0L))).append("</td><td>")
                    .append(display(actualValue(actual, "failure", 0L))).append("</td><td>")
                    .append(display(actualValue(actual, "dropped", 0L))).append("</td><td>")
                    .append(rate(actualValue(actual, "completedThroughput", 0.0))).append("</td><td>")
                    .append(display(actualValue(actual, "p95Ms", 0L))).append(" ms</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void appendMetricsTable(StringBuilder html, Map<String, Object> metrics) {
        html.append("<section class=\"panel\"><h2>Aggregate metrics</h2><div class=\"scroll\"><table><thead><tr><th>Metric</th><th>Value</th></tr></thead><tbody>");
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            if ("buckets".equals(entry.getKey()) || "phases".equals(entry.getKey())) continue;
            html.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>").append(escape(display(entry.getValue()))).append("</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void appendWorkloadTable(StringBuilder html, LoadRunResult result) {
        if (result.workloads().isEmpty()) return;
        html.append("<section class=\"panel\"><h2>Workloads</h2>");
        html.append("<p class=\"note\">Each workload is independently paced. Overall latency percentiles above come from the aggregate raw-latency collector, not an average of workload percentiles.</p>");
        html.append("<div class=\"scroll\"><table><thead><tr><th>Workload</th><th>Target</th><th>Model</th><th>Configured load</th><th>Completed TPS</th><th>P95</th><th>Error</th><th>Drop</th><th>Thresholds</th><th>Status</th></tr></thead><tbody>");
        for (Map.Entry<String, LoadRunResult> entry : result.workloads().entrySet()) {
            LoadRunResult child = entry.getValue();
            Map<String, Object> metrics = child.metrics().values();
            String configured = child.scenario().model() == LoadScenario.Model.CLOSED
                    ? child.scenario().users() + " users"
                    : rate(child.scenario().arrivalRatePerSecond());
            String target = child.scenario().targetType() + ":" + child.scenario().targetId();
            String childStatus = child.status().name();
            html.append("<tr><td><b>").append(escape(entry.getKey())).append("</b></td><td>").append(escape(target))
                    .append("</td><td>").append(escape(child.scenario().model().wireName())).append("</td><td>")
                    .append(escape(configured)).append("</td><td>").append(rate(metrics.get("completedThroughput")))
                    .append("</td><td>").append(display(metrics.get("p95Ms"))).append(" ms</td><td>")
                    .append(percent(metrics.get("sutErrorRate"))).append("</td><td>").append(percent(metrics.get("droppedRate")))
                    .append("</td><td>").append(escape(workloadThresholdSummary(child.thresholds().results())))
                    .append("</td><td class=\"").append("PASS".equals(childStatus) ? "pass\">" : "fail\">")
                    .append(escape(childStatus)).append("</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private String workloadThresholdSummary(List<ThresholdResult> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) return "none";
        StringBuilder result = new StringBuilder();
        for (ThresholdResult threshold : thresholds) {
            if (result.length() > 0) result.append("; ");
            result.append(threshold.name()).append(' ').append(threshold.expression())
                    .append(" (actual ").append(threshold.actual()).append(") ")
                    .append(threshold.passed() ? "PASS" : "FAIL");
            if (threshold.diagnostic() != null && !threshold.diagnostic().trim().isEmpty())
                result.append(" - ").append(threshold.diagnostic());
        }
        return result.toString();
    }

    private void appendThresholdTable(StringBuilder html, List<ThresholdResult> thresholds) {
        html.append("<section class=\"panel\"><h2>Thresholds</h2>");
        if (thresholds.isEmpty()) { html.append("<p class=\"empty\">No thresholds configured.</p></section>"); return; }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Name</th><th>Expected</th><th>Actual</th><th>Status</th><th>Diagnostic</th></tr></thead><tbody>");
        for (ThresholdResult threshold : thresholds) {
            html.append("<tr><td>").append(escape(threshold.name())).append("</td><td>").append(escape(threshold.expression())).append("</td><td>")
                    .append(escape(threshold.actual())).append("</td><td class=\"").append(threshold.passed() ? "pass\">PASS" : "fail\">FAIL")
                    .append("</td><td>").append(escape(threshold.diagnostic())).append("</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void appendResourceTable(StringBuilder html, Map<String, Object> resources) {
        html.append("<section class=\"panel\"><h2>Resource diagnostics</h2><p class=\"note\">Pool saturation and acquisition problems are reported separately from application/SUT failures. Credentials and live resource objects are never included.</p>");
        if (resources == null || resources.isEmpty()) { html.append("<p class=\"empty\">No DB or MQ pool was opened during this run.</p></section>"); return; }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Resource</th><th>Helper</th><th>Metric</th><th>Value</th></tr></thead><tbody>");
        for (Map.Entry<String, Object> resource : resources.entrySet()) {
            if (!(resource.getValue() instanceof Map)) continue;
            Map<?, ?> helpers = (Map<?, ?>) resource.getValue();
            if (helpers.isEmpty()) { html.append("<tr><td>").append(escape(resource.getKey())).append("</td><td colspan=\"3\">none opened</td></tr>"); continue; }
            for (Map.Entry<?, ?> helper : helpers.entrySet()) {
                if (!(helper.getValue() instanceof Map)) continue;
                for (Map.Entry<?, ?> metric : ((Map<?, ?>) helper.getValue()).entrySet()) {
                    html.append("<tr><td>").append(escape(resource.getKey())).append("</td><td>").append(escape(String.valueOf(helper.getKey())))
                            .append("</td><td>").append(escape(String.valueOf(metric.getKey()))).append("</td><td>")
                            .append(escape(display(metric.getValue()))).append("</td></tr>");
                }
            }
        }
        html.append("</tbody></table></div></section>");
    }

    @SuppressWarnings("unchecked")
    private void appendEvidenceTable(StringBuilder html, Map<String, Object> evidence) {
        html.append("<section class=\"panel\"><h2>Retained evidence</h2>");
        Object itemsValue = evidence == null ? null : evidence.get("items");
        if (!(itemsValue instanceof List) || ((List<?>) itemsValue).isEmpty()) { html.append("<p class=\"empty\">No failure or sampled-success evidence was retained.</p></section>"); return; }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Iteration</th><th>Kind</th><th>Link</th></tr></thead><tbody>");
        for (Object value : (List<?>) itemsValue) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) value;
            String path = String.valueOf(item.get("path"));
            html.append("<tr><td>").append(escape(String.valueOf(item.get("iterationId")))).append("</td><td>")
                    .append(escape(String.valueOf(item.get("status")))).append("</td><td><a href=\"../")
                    .append(escapeAttribute(path)).append("\">").append(escape(path)).append("</a></td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void appendTimeSeries(StringBuilder html, LoadRunResult result) {
        html.append("<section class=\"panel\"><h2>Bounded time series</h2><p class=\"note\">Rendered from bounded one-second buckets; no raw per-iteration sample is retained in this report.</p>");
        if (result.metrics().buckets().isEmpty()) { html.append("<p class=\"empty\">No time-series buckets were observed.</p></section>"); return; }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Bucket</th><th>Phase</th><th>Scheduled</th><th>Started</th><th>Completed TPS</th><th>p95</th><th>p99</th><th>SUT error</th><th>Dropped</th><th>Active VUs</th><th>In-flight</th><th>Scheduler lag</th></tr></thead><tbody>");
        for (Map.Entry<String, Map<String, Object>> bucket : result.metrics().buckets().entrySet()) {
            Map<String, Object> values = bucket.getValue();
            html.append("<tr><td>").append(escape(String.valueOf(values.get("bucketStart")))).append("</td><td>")
                    .append(escape(String.valueOf(values.get("phase")))).append("</td><td>").append(display(values.get("scheduled")))
                    .append("</td><td>").append(display(values.get("started"))).append("</td><td>").append(display(values.get("completedTps")))
                    .append("</td><td>").append(display(values.get("p95Ms"))).append(" ms</td><td>").append(display(values.get("p99Ms"))).append(" ms</td><td>")
                    .append(percent(values.get("sutErrorRate"))).append("</td><td class=\"drop\">").append(display(values.get("dropped")))
                    .append(" (").append(percent(values.get("droppedRate"))).append(")</td><td>").append(display(values.get("activeVus")))
                    .append("</td><td>").append(display(values.get("currentInFlight"))).append("</td><td>").append(display(values.get("schedulerLagMeanMs"))).append(" ms</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void kpi(StringBuilder html, String name, Object value, String note) {
        html.append("<div class=\"kpi\"><span class=\"muted\">").append(escape(name)).append("</span><strong>")
                .append(escape(display(value))).append("</strong><small>").append(escape(note)).append("</small></div>");
    }

    private void concept(StringBuilder html, String name, Object value, String note, String css) {
        html.append("<div class=\"concept ").append(css).append("\"><span class=\"muted\">").append(escape(name)).append("</span><strong>")
                .append(escape(display(value))).append("</strong><small>").append(escape(note)).append("</small></div>");
    }

    private Object actualValue(Map<String, Object> values, String key, Object fallback) {
        return values == null || !values.containsKey(key) ? fallback : values.get(key);
    }

    private String count(Object value) { return display(value); }
    private String rate(Object value) { return value instanceof Number ? String.format(java.util.Locale.ROOT, "%.3f/s", ((Number) value).doubleValue()) : display(value); }
    private String percent(Object value) { return value instanceof Number ? String.format(java.util.Locale.ROOT, "%.2f%%", ((Number) value).doubleValue() * 100.0) : display(value); }
    private String display(Object value) {
        if (value == null) return "-";
        if (value instanceof Map || value instanceof List) return JsonSupport.write(value);
        return String.valueOf(value);
    }
    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private String escapeAttribute(String value) { return escape(value).replace("'", "&#39;"); }
}
