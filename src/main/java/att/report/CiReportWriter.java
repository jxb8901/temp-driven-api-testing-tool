package att.report;

import att.Version;
import att.core.ResultStatus;
import att.core.RunSummary;
import att.core.TestResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Writes deterministic CI-native JSON and JUnit artifacts for a completed run candidate. */
public final class CiReportWriter {
    public void write(Path runDirectory, String runId, String environment, RunSummary summary, Instant started, Instant ended, int caseLogThresholdBytes, String inputManifestHash, java.util.List<att.validation.Diagnostic> diagnostics, java.util.Set<String> formats) throws Exception {
        Path ci = runDirectory.resolve("ci");
        Files.createDirectories(ci);
        if (formats.contains("json")) Files.write(ci.resolve("summary.json"), json(runDirectory, runId, environment, summary, started, ended, inputManifestHash, diagnostics).getBytes(StandardCharsets.UTF_8));
        if (formats.contains("junit")) {
            Files.write(ci.resolve("junit.xml"), junit(runDirectory, runId, summary, caseLogThresholdBytes).getBytes(StandardCharsets.UTF_8));
            writeJunitHtml(runDirectory, runId, summary, caseLogThresholdBytes);
        }
    }

    public Path writeJunitHtml(Path runDirectory, String runId, RunSummary summary, int caseLogThresholdBytes) throws Exception {
        Path report = runDirectory.resolve("report"); Files.createDirectories(report); Path target = report.resolve("junit.html");
        Files.write(target, junitHtml(runDirectory, runId, summary, caseLogThresholdBytes).getBytes(StandardCharsets.UTF_8)); return target;
    }

    private String json(Path runDirectory, String runId, String environment, RunSummary summary, Instant started, Instant ended, String inputManifestHash, java.util.List<att.validation.Diagnostic> diagnostics) {
        long totalDuration = 0, minimum = summary.results().isEmpty() ? 0 : Long.MAX_VALUE, maximum = 0;
        for (TestResult result : summary.results()) { long duration = result.duration().toMillis(); totalDuration += duration; minimum = Math.min(minimum, duration); maximum = Math.max(maximum, duration); }
        java.util.Map<String,Object> root = new java.util.LinkedHashMap<String,Object>(); root.put("schemaVersion", Version.CI_SUMMARY_SCHEMA); root.put("attVersion", Version.PRODUCT); root.put("runId", runId); root.put("environment", environment); root.put("startedAt", started.toString()); root.put("endedAt", ended.toString()); root.put("status", summary.status().name());
        java.util.Map<String,Object> counts = new java.util.LinkedHashMap<String,Object>(); counts.put("total", summary.total()); counts.put("passed", summary.passed()); counts.put("failed", summary.failed()); counts.put("error", summary.error()); counts.put("skipped", summary.skipped()); counts.put("invalid", summary.invalid()); root.put("summary", counts);
        java.util.Map<String,Object> durations = new java.util.LinkedHashMap<String,Object>(); durations.put("totalMs", totalDuration); durations.put("minimumMs", minimum); durations.put("maximumMs", maximum); durations.put("averageMs", summary.total() == 0 ? 0 : totalDuration / summary.total()); root.put("durationStatistics", durations);
        java.util.List<java.util.Map<String,Object>> cases = new java.util.ArrayList<java.util.Map<String,Object>>();
        for (TestResult result : summary.results()) {
            String caseLog = result.caseLogPath() == null ? null : runDirectory.relativize(result.caseLogPath()).toString().replace('\\', '/');
            java.util.Map<String,Object> item = new java.util.LinkedHashMap<String,Object>(); item.put("caseId", result.caseId()); item.put("name", result.caseName()); item.put("status", result.status().name()); item.put("durationMs", result.duration().toMillis()); item.put("caseLog", caseLog); cases.add(item);
        }
        long errors = 0, warnings = 0, info = 0; for (att.validation.Diagnostic diagnostic : diagnostics) { if (diagnostic.severity() == att.validation.Diagnostic.Severity.ERROR) errors++; else if (diagnostic.severity() == att.validation.Diagnostic.Severity.WARNING) warnings++; else info++; }
        root.put("cases", cases); java.util.Map<String,Object> diagnosticCounts = new java.util.LinkedHashMap<String,Object>(); diagnosticCounts.put("error", errors); diagnosticCounts.put("warning", warnings); diagnosticCounts.put("info", info); root.put("diagnosticCounts", diagnosticCounts); root.put("report", "report/index.html"); root.put("inputManifestHash", inputManifestHash);
        return att.validation.JsonSupport.write(root);
    }

    private String junit(Path runDirectory, String runId, RunSummary summary, int caseLogThresholdBytes) throws Exception {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"ATT ").append(xml(runId)).append("\" tests=\"").append(summary.total()).append("\" failures=\"").append(summary.failed()).append("\" errors=\"").append(summary.error() + summary.invalid()).append("\" skipped=\"").append(summary.skipped()).append("\">");
        for (TestResult result : summary.results()) {
            out.append("<testcase classname=\"ATT\" name=\"").append(xml(result.caseId())).append("\" time=\"").append(String.format(java.util.Locale.ROOT, "%.3f", result.duration().toMillis() / 1000.0)).append("\">");
            if (result.status() == ResultStatus.FAIL) out.append("<failure message=\"ATT assertion failure\">").append(xml(att.validation.DiagnosticCodes.ASSERTION_FAILED + "\n" + detail(result))).append("</failure>");
            else if (result.status() == ResultStatus.ERROR || result.status() == ResultStatus.INVALID) out.append("<error type=\"").append(result.status() == ResultStatus.INVALID ? "ATTValidationError" : "ATTExecutionError").append("\">").append(xml((result.status() == ResultStatus.INVALID ? att.validation.DiagnosticCodes.TESTCASE_INVALID : att.validation.DiagnosticCodes.RUN_FAILED) + "\n" + detail(result))).append("</error>");
            else if (result.status() == ResultStatus.SKIPPED) out.append("<skipped/>");
            if (result.caseLogPath() != null && Files.exists(result.caseLogPath())) {
                byte[] bytes = Files.readAllBytes(result.caseLogPath());
                String log = caseLogThresholdBytes > 0 && bytes.length <= caseLogThresholdBytes ? new String(bytes, StandardCharsets.UTF_8) : "Case log: " + runDirectory.relativize(result.caseLogPath()).toString().replace('\\', '/');
                out.append("<system-out>").append(xml(log)).append("</system-out>");
            }
            out.append("</testcase>");
        }
        return out.append("</testsuite>").toString();
    }
    private String junitHtml(Path runDirectory, String runId, RunSummary summary, int threshold) throws Exception {
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>JUnit ").append(HtmlSupport.escape(runId)).append("</title><style>body{font:14px/1.5 system-ui;margin:32px;color:#172033}table{width:100%;border-collapse:collapse}th,td{padding:9px;border-bottom:1px solid #d8dee9;text-align:left}th{background:#eef2f7}.PASS{color:#08783e}.FAIL,.ERROR,.INVALID{color:#b42318}.SKIPPED{color:#6941c6}pre{white-space:pre-wrap;max-height:320px;overflow:auto;background:#f6f8fa;padding:10px}a{color:#175cd3}</style></head><body><h1>JUnit Report</h1><p>Run <strong>").append(HtmlSupport.escape(runId)).append("</strong> · Tests ").append(summary.total()).append(" · Failures ").append(summary.failed()).append(" · Errors ").append(summary.error() + summary.invalid()).append(" · Skipped ").append(summary.skipped()).append("</p><p><a href=\"index.html\">Open ATT report</a></p><table><tr><th>Case</th><th>Name</th><th>Status</th><th>Time</th><th>Case log</th></tr>");
        for (TestResult result : summary.results()) {
            out.append("<tr><td>").append(HtmlSupport.escape(result.caseId())).append("</td><td>").append(HtmlSupport.escape(result.caseName())).append("</td><td class=\"").append(result.status()).append("\">").append(result.status()).append("</td><td>").append(result.duration().toMillis()).append(" ms</td><td>");
            if (result.caseLogPath() != null && Files.exists(result.caseLogPath())) {
                byte[] bytes = Files.readAllBytes(result.caseLogPath());
                if (threshold > 0 && bytes.length <= threshold) out.append("<pre>").append(HtmlSupport.escape(new String(bytes, StandardCharsets.UTF_8))).append("</pre>");
                else out.append("<a href=\"../").append(HtmlSupport.escape(runDirectory.relativize(result.caseLogPath()).toString().replace('\\', '/'))).append("\">Open log</a>");
            }
            out.append("</td></tr>");
        }
        return out.append("</table></body></html>").toString();
    }
    private String xml(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private String detail(TestResult result) { StringBuilder out = new StringBuilder(); for (att.core.ValidationResult validation : result.validations()) out.append(validation.source()).append('/').append(validation.name()).append(" status=").append(validation.status()).append(" expected=").append(validation.expected()).append(" actual=").append(validation.actual()).append(" message=").append(validation.message()).append('\n'); if (out.length() == 0) out.append(result.actual()); return out.toString(); }
}
