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
            List<ValidationResult> validations = new ArrayList<ValidationResult>();
            Object actionRows = row.get("actions");
            if (actionRows instanceof Iterable) for (Object actionValue : (Iterable<?>) actionRows) {
                if (!(actionValue instanceof Map)) continue;
                Map<String, Object> action = (Map<String, Object>) actionValue;
                validations.add(new ValidationResult(text(action.get("stage")), text(action.get("action")),
                        text(action.get("description")), ResultStatus.valueOf(text(action.get("status"))),
                        text(action.get("expected")), text(action.get("actual")), text(action.get("message")),
                        diagnostic(action.get("diagnostic"))));
            }
            results.add(new TestResult(caseId, caseName, ResultStatus.valueOf(String.valueOf(row.get("status"))),
                    Duration.ofMillis(longValue(row.get("durationMs"))), text(row.get("expected")), text(row.get("actual")), log,
                    validations, workbookId, groupId, strings(row.get("tags")), diagnostic(row.get("diagnostic"))));
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

    @SuppressWarnings("unchecked")
    private att.validation.Diagnostic diagnostic(Object value) {
        if (!(value instanceof Map)) return null;
        Map<String, Object> map = (Map<String, Object>) value;
        att.validation.SourceLocation source = null;
        Object sourceValue = map.get("source");
        if (sourceValue instanceof Map) {
            Map<String, Object> sourceMap = (Map<String, Object>) sourceValue;
            source = new att.validation.SourceLocation(text(sourceMap.get("file")), integer(sourceMap.get("line"), 1), integer(sourceMap.get("column"), 1),
                    integer(sourceMap.get("endLine"), integer(sourceMap.get("line"), 1)), integer(sourceMap.get("endColumn"), integer(sourceMap.get("column"), 1)),
                    sourceMap.get("excerpt") == null ? null : text(sourceMap.get("excerpt")));
        }
        att.validation.DiagnosticContext context = att.validation.DiagnosticContext.EMPTY;
        Object contextValue = map.get("context");
        if (contextValue instanceof Map) {
            Map<String, Object> contextMap = (Map<String, Object>) contextValue;
            context = new att.validation.DiagnosticContext(textOrNull(contextMap.get("caseFile")), textOrNull(contextMap.get("caseId")),
                    textOrNull(contextMap.get("stage")), textOrNull(contextMap.get("flowId")), strings(contextMap.get("callChain")));
        }
        att.validation.Diagnostic.Severity severity;
        try { severity = att.validation.Diagnostic.Severity.valueOf(text(map.get("severity"))); }
        catch (Exception ignored) { severity = att.validation.Diagnostic.Severity.ERROR; }
        java.util.List<java.util.Map<String, Object>> schemaViolations = new java.util.ArrayList<java.util.Map<String, Object>>();
        Object violationsValue = map.get("schemaViolations");
        if (violationsValue instanceof Iterable) for (Object item : (Iterable<?>) violationsValue) {
            if (item instanceof Map) schemaViolations.add(new java.util.LinkedHashMap<String, Object>((Map<String, Object>) item));
        }
        return new att.validation.Diagnostic(text(map.get("code")), severity, text(map.get("message")), textOrNull(map.get("file")),
                textOrNull(map.get("field")), textOrNull(map.get("sheet")), nullableInteger(map.get("row")), nullableInteger(map.get("column")),
                textOrNull(map.get("template")), textOrNull(map.get("action")), textOrNull(map.get("suggestion")),
                textOrNull(map.get("summary")), textOrNull(map.get("detail")), source, context, schemaViolations);
    }
    private String textOrNull(Object value) { return value == null ? null : text(value); }
    private Integer nullableInteger(Object value) { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
    private int integer(Object value, int fallback) { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
}
