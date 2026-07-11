/*
 * Author: Jeffrey + ChatGPT
 */
package att.report;

import att.Version;

import att.core.ResultStatus;
import att.core.RunSummary;
import att.core.TestResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generates one self-contained, offline-friendly V2 HTML run report. */
public final class HtmlReportGenerator {
    public Path generate(Path runDirectory, String runId, RunSummary summary, Instant started, Instant ended) throws Exception {
        Path report = runDirectory.resolve("report");
        Files.createDirectories(report);
        long elapsed = java.time.Duration.between(started, ended).toMillis();
        long aggregate = 0;
        long minimum = summary.results().isEmpty() ? 0 : Long.MAX_VALUE;
        long maximum = 0;
        Map<String, long[]> groups = new LinkedHashMap<String, long[]>();
        for (TestResult result : summary.results()) {
            long duration = result.duration().toMillis();
            aggregate += duration;
            minimum = Math.min(minimum, duration);
            maximum = Math.max(maximum, duration);
            String group = result.caseId().contains(".") ? result.caseId().substring(0, result.caseId().indexOf('.')) : "default";
            long[] counts = groups.computeIfAbsent(group, key -> new long[7]);
            counts[0]++; counts[6] += duration;
            if (result.status() == ResultStatus.PASS) counts[1]++;
            else if (result.status() == ResultStatus.FAIL) counts[2]++;
            else if (result.status() == ResultStatus.ERROR) counts[3]++;
            else if (result.status() == ResultStatus.SKIPPED) counts[4]++;
            else if (result.status() == ResultStatus.INVALID) counts[5]++;
        }
        long denominator = summary.passed() + summary.failed() + summary.error();
        double passRate = denominator == 0 ? 0 : summary.passed() * 100.0 / denominator;
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>ATT V").append(Version.PRODUCT).append(" Report</title><style>")
                .append("*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 8% 0,#172554 0,#080b16 42%,#06070c 100%);color:#e5e7eb;font:14px/1.5 Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;min-height:100vh}.wrap{max-width:1240px;margin:auto;padding:34px 20px}header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start;margin-bottom:24px}h1{margin:0;font-size:29px;letter-spacing:-.4px}h2{font-size:17px;margin:30px 0 10px}.muted{color:#94a3b8}.run{background:#172554;color:#93c5fd;border:1px solid #1d4ed8;padding:5px 10px;border-radius:999px;font-weight:700}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(135px,1fr));gap:12px}.card,table,details{background:#0f172ad9;border:1px solid #2b3850;border-radius:12px;box-shadow:0 12px 32px #0004}.card{padding:15px}.card strong{display:block;color:#94a3b8;font-size:11px;text-transform:uppercase;letter-spacing:.06em}.card span{font-size:22px;font-weight:750}table{width:100%;border-collapse:collapse;overflow:hidden}th,td{padding:11px 12px;text-align:left;border-bottom:1px solid #26334a}th{background:#111c31;color:#93c5fd;font-size:11px;text-transform:uppercase;letter-spacing:.04em}.badge{font-size:11px;font-weight:800;padding:4px 8px;border-radius:999px}.PASS{color:#6ee7b7;background:#064e3b}.FAIL,.ERROR,.INVALID{color:#fca5a5;background:#7f1d1d}.SKIPPED{color:#c4b5fd;background:#4c1d95}details{margin:10px 0;overflow:hidden}summary{cursor:pointer;padding:14px 16px;font-weight:650;list-style:none}.detail{padding:0 16px 16px;border-top:1px solid #26334a}dl{display:grid;grid-template-columns:130px 1fr;gap:6px 12px}dt{font-weight:650;color:#94a3b8}dd{margin:0;min-width:0}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#020617;color:#dbeafe;padding:12px;border-radius:8px;max-height:400px;overflow:auto}a{color:#60a5fa;text-decoration:none}a:hover{text-decoration:underline}input,select{background:#0f172a;color:#fff;border:1px solid #334155;border-radius:9px;padding:10px}@media(max-width:640px){.wrap{padding:20px 12px}header{display:block}.run{display:inline-block;margin-top:10px}table{font-size:12px}th,td{padding:9px 7px}dl{grid-template-columns:1fr}}</style></head><body><main class=\"wrap\">")
                .append("<header id=\"top\"><div><h1>ATT V").append(Version.PRODUCT).append(" Test Report</h1><div class=\"muted\">Started ").append(escape(started.toString())).append(" · Ended ").append(escape(ended.toString())).append(" · Wall time ").append(elapsed).append(" ms</div></div><div class=\"run\">Run ").append(escape(runId)).append("</div></header><nav aria-label=\"Report index\" style=\"position:sticky;top:0;z-index:5;display:flex;flex-wrap:wrap;gap:12px;background:#080b16ed;border:1px solid #2b3850;border-radius:12px;padding:12px;margin-bottom:18px\"><strong>Index</strong><a href=\"#overview\">Overview</a><a href=\"#groups\">Groups</a><a href=\"#cases\">Cases</a><a href=\"#case-details\">Case details</a><a href=\"../ci/junit.html\">JUnit HTML</a></nav><section id=\"overview\" class=\"grid\">")
                .append(card("Total", summary.total())).append(card("Passed", summary.passed())).append(card("Failed", summary.failed())).append(card("Errors", summary.error())).append(card("Skipped", summary.skipped())).append(card("Invalid", summary.invalid())).append(card("Pass rate", String.format(java.util.Locale.ROOT, "%.1f%%", passRate))).append(card("Wall time", elapsed + " ms")).append(card("Aggregate", aggregate + " ms")).append(card("Minimum", minimum + " ms")).append(card("Maximum", maximum + " ms")).append(card("Average", (summary.total() == 0 ? 0 : aggregate / summary.total()) + " ms"))
                .append("</section><h2 id=\"groups\">Groups</h2><table><tr><th>Group</th><th>Total</th><th>Pass</th><th>Fail</th><th>Error</th><th>Skipped</th><th>Invalid</th><th>Duration</th></tr>");
        for (Map.Entry<String, long[]> entry : groups.entrySet()) {
            long[] c = entry.getValue();
            html.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>").append(c[0]).append("</td><td>").append(c[1]).append("</td><td>").append(c[2]).append("</td><td>").append(c[3]).append("</td><td>").append(c[4]).append("</td><td>").append(c[5]).append("</td><td>").append(c[6]).append(" ms</td></tr>");
        }
        html.append("<span id=\"cases\"></span>");
        html.append("</table><h2>Cases</h2><p><input id=\"caseSearch\" placeholder=\"Search case / 中文\"> <select id=\"statusFilter\"><option value=\"\">All statuses</option><option>PASS</option><option>FAIL</option><option>ERROR</option><option>SKIPPED</option><option>INVALID</option></select></p><table id=\"caseTable\"><tr><th>Case ID</th><th>Name</th><th>Status</th><th>Duration</th></tr>");
        for (TestResult result : summary.results()) {
            html.append("<tr data-status=\"").append(result.status()).append("\"><td><a href=\"#").append(anchor(result.caseId())).append("\">").append(escape(result.caseId())).append("</a></td><td>").append(escape(result.caseName())).append("</td><td><span class=\"badge ").append(result.status()).append("\">").append(result.status()).append("</span></td><td>").append(result.duration().toMillis()).append(" ms</td></tr>");
        }
        html.append("</table><span id=\"case-details\"></span><h2>Case details</h2>");
        for (TestResult result : summary.results()) appendCase(html, runDirectory, result);
        html.append("<script>const q=document.querySelector('#caseSearch'),s=document.querySelector('#statusFilter');function filterCases(){document.querySelectorAll('#caseTable tr[data-status]').forEach(r=>r.hidden=!(r.innerText.toLowerCase().includes(q.value.toLowerCase())&&(!s.value||r.dataset.status===s.value)))}q.oninput=filterCases;s.onchange=filterCases;</script></main></body></html>");
        Path index = report.resolve("index.html");
        Files.write(index, html.toString().getBytes(StandardCharsets.UTF_8));
        return index;
    }

    private void appendCase(StringBuilder html, Path runDirectory, TestResult result) throws Exception {
        String log = result.caseLogPath() == null ? "" : result.caseLogPath().toString();
        String logContent = result.caseLogPath() != null && Files.exists(result.caseLogPath()) ? new String(Files.readAllBytes(result.caseLogPath()), StandardCharsets.UTF_8) : "";
        html.append("<details id=\"").append(anchor(result.caseId())).append("\"><summary><span class=\"badge ").append(result.status()).append("\">").append(result.status()).append("</span> ").append(escape(result.caseId())).append(" — ").append(escape(result.caseName())).append(" <span class=\"muted\">(").append(result.duration().toMillis()).append(" ms)</span></summary><div class=\"detail\"><dl><dt>Expected</dt><dd><pre>").append(escape(result.expected())).append("</pre></dd><dt>Actual</dt><dd><pre>").append(escape(result.actual())).append("</pre></dd><dt>Case log</dt><dd>").append(escape(log)).append("</dd>");
        if (result.caseLogPath() != null && Files.exists(result.caseLogPath())) {
            String relative = runDirectory.relativize(result.caseLogPath()).toString().replace('\\', '/');
            html.append("<dt>Artifacts</dt><dd><a href=\"../").append(escape(relative)).append("\">Open case folder artifact</a></dd>");
        }
        Path tree = result.caseLogPath() == null ? null : result.caseLogPath().getParent().resolve("case.yaml");
        String treeContent = tree != null && Files.exists(tree) ? new String(Files.readAllBytes(tree), StandardCharsets.UTF_8) : "";
        html.append("</dl><h3>Stage / Template / Action / Tool tree</h3><pre>").append(escape(treeContent)).append("</pre><h3>Detailed execution log</h3><pre>").append(escape(logContent)).append("</pre></div></details>");
    }

    private String card(String name, Object value) { return "<div class=\"card\"><strong>" + escape(name) + "</strong><span>" + escape(String.valueOf(value)) + "</span></div>"; }
    private String anchor(String value) { return "case-" + HtmlSupport.id(value); }
    private String escape(String value) { return HtmlSupport.escape(value); }
}
