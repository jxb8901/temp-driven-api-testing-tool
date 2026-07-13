/* Author: Jeffrey + ChatGPT */
package att.report;

import att.core.ResultStatus;
import att.core.RunSummary;
import att.core.TestResult;
import att.core.ValidationResult;
import att.core.IdentifierValidator;
import att.config.YamlSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Rebuilds report HTML from the persisted completed run manifest. */
public final class ReportRegenerator {
    @SuppressWarnings("unchecked")
    public Path regenerate(Path outputRoot, String runId) throws Exception {
        runId = IdentifierValidator.runId(runId);
        Path runDir = IdentifierValidator.strictExistingChild(outputRoot, runId, "Report run directory");
        Path manifest = runDir.resolve("run.yaml");
        if (!Files.exists(manifest)) throw new IllegalArgumentException("Run manifest does not exist: " + manifest);
        if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest)) throw new IllegalArgumentException("Unsafe run manifest: " + manifest);
        Object loaded = YamlSupport.parser().load(new String(Files.readAllBytes(manifest), "UTF-8"));
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("Invalid run manifest: " + manifest);
        Map<String, Object> run = (Map<String, Object>) loaded;
        if (!"att-run/v2.1".equals(String.valueOf(run.get("schemaVersion")))) throw new IllegalArgumentException("Run manifest is not V2.1: " + manifest);
        Map<String, Object> runNode = run.get("run") instanceof Map ? (Map<String, Object>) run.get("run") : run;
        if (!"COMPLETE".equals(String.valueOf(runNode.get(run.containsKey("run") ? "state" : "status")))) throw new IllegalArgumentException("Run is not COMPLETE: " + runId);
        List<TestResult> results = new ArrayList<TestResult>();
        Object cases = run.get("cases");
        if (cases instanceof Iterable) for (Object item : (Iterable<?>) cases) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) item;
            String caseId = String.valueOf(row.get("caseId"));
            Path log = String.valueOf(row.get("caseLog")).isEmpty() ? null : runDir.resolve(String.valueOf(row.get("caseLog"))).normalize();
            if (log != null && !log.startsWith(runDir)) throw new IllegalArgumentException("Unsafe case log path in manifest: " + row.get("caseLog"));
            String caseName = row.get("caseName") == null ? caseId : String.valueOf(row.get("caseName"));
            String workbookId = text(row.get("workbookId")), groupId = text(row.get("groupId"));
            if (groupId.isEmpty()) groupId = text(row.get("sheetId")); // read pre-2.1.2 manifests
            String[] idParts = caseId.split("\\.", 3);
            if (workbookId.isEmpty() && idParts.length == 3) workbookId = idParts[0];
            if (groupId.isEmpty() && idParts.length == 3) groupId = idParts[1];
            results.add(new TestResult(caseId, caseName, ResultStatus.valueOf(String.valueOf(row.get("status"))),
                    Duration.ofMillis(longValue(row.get("durationMs"))), text(row.get("expected")), text(row.get("actual")), log,
                    Collections.<ValidationResult>emptyList(), workbookId, groupId, strings(row.get("tags"))));
        }
        Instant ended = runNode.get("endedAt") == null ? Files.getLastModifiedTime(manifest).toInstant() : Instant.parse(String.valueOf(runNode.get("endedAt")));
        Instant started = runNode.get("startedAt") == null ? ended : Instant.parse(String.valueOf(runNode.get("startedAt")));
        RunSummary summary = new RunSummary(results, runDir);
        Path report = new HtmlReportGenerator().generate(runDir, runId, summary, started, ended);
        new CiReportWriter().writeJunitHtml(runDir, runId, summary, 10240);
        return report;
    }
    private long longValue(Object value) { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private java.util.List<String> strings(Object value) { java.util.List<String> result = new java.util.ArrayList<String>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(String.valueOf(item)); return result; }
}
