package att.load;

import att.validation.JsonSupport;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Writes isolated JSON/YAML summaries and a compact HTML performance report. */
public final class LoadReportWriter {
    public Path write(Path outputRoot, LoadRunResult result) throws IOException {
        Path runDirectory = outputRoot.resolve("load").resolve(result.runId()).toAbsolutePath().normalize();
        Files.createDirectories(runDirectory.resolve("report"));
        Map<String, Object> map = result.toMap();
        Files.write(runDirectory.resolve("load-summary.json"), JsonSupport.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(map));
        Files.write(runDirectory.resolve("load-summary.yaml"), new Yaml().dump(map).getBytes(StandardCharsets.UTF_8));
        Files.write(runDirectory.resolve("report/index.html"), html(result).getBytes(StandardCharsets.UTF_8));
        return runDirectory.resolve("report/index.html");
    }

    private String html(LoadRunResult result) {
        Map<String, Object> metrics = result.metrics().values(); StringBuilder html = new StringBuilder();
        html.append("<!doctype html><meta charset=\"utf-8\"><title>ATT Load ").append(escape(result.runId())).append("</title>");
        html.append("<style>body{font:14px sans-serif;margin:2em}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:.35em}.pass{color:green}.fail{color:#b00}</style>");
        html.append("<h1>ATT Load ").append(escape(result.runId())).append("</h1><p>Model: <b>").append(escape(result.scenario().model().wireName())).append("</b> | Status: <b class=\"").append(result.passed() ? "pass\">PASS" : "fail\">FAIL").append("</b></p>");
        html.append("<h2>Metrics</h2><table><tr><th>Metric</th><th>Value</th></tr>");
        for (Map.Entry<String, Object> entry : metrics.entrySet()) html.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>").append(escape(String.valueOf(entry.getValue()))).append("</td></tr>");
        html.append("</table><h2>Thresholds</h2><table><tr><th>Name</th><th>Expected</th><th>Actual</th><th>Status</th></tr>");
        for (ThresholdResult threshold : result.thresholds().results()) html.append("<tr><td>").append(escape(threshold.name())).append("</td><td>").append(escape(threshold.expression())).append("</td><td>").append(escape(threshold.actual())).append("</td><td class=\"").append(threshold.passed() ? "pass\">PASS" : "fail\">FAIL").append("</td></tr>");
        html.append("</table><h2>Time series buckets</h2><pre>").append(escape(JsonSupport.write(result.metrics().buckets()))).append("</pre>");
        return html.toString();
    }
    private String escape(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
