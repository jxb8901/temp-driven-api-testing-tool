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
    private static final String NATIVE_REPORT_PROTECTION_VERSION = "att-native-report/v1";
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
        Object cases = run.get("cases");
        boolean nativeRun = "nativeCase".equals(String.valueOf(runNode.get("targetType")))
                || "nativeSuite".equals(String.valueOf(runNode.get("targetType")));
        if (!nativeRun && cases instanceof Iterable) for (Object item : (Iterable<?>) cases)
            if (item instanceof Map && ((Map<?,?>) item).get("definitionId") != null) { nativeRun = true; break; }
        if (nativeRun && !NATIVE_REPORT_PROTECTION_VERSION.equals(String.valueOf(runNode.get("nativeReportProtection"))))
            throw new IllegalArgumentException("Native run manifest does not declare safe report projections; regenerate it with a version that protects execution secrets before report generation: " + runId);
        List<TestResult> results = new ArrayList<TestResult>();
        if (cases instanceof Iterable) for (Object item : (Iterable<?>) cases) {
            if (!(item instanceof Map)) continue;
            Object safeRow = protectManifestValue(item);
            if (!(safeRow instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) safeRow;
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
            boolean executionStarted = !row.containsKey("executed") || Boolean.parseBoolean(String.valueOf(row.get("executed")));
            String executionId = row.containsKey("execId")
                    ? (row.get("execId") == null ? null : String.valueOf(row.get("execId"))) : caseId;
            String definitionId = text(row.get("definitionId"));
            String flowId = text(row.get("flowId"));
            String flowRevision = text(row.get("flowRevision"));
            String sourceRevision = text(row.get("sourceRevision"));
            Map<String, Object> sourceProvenance = row.get("sourceProvenance") instanceof Map
                    ? new java.util.LinkedHashMap<String, Object>((Map<String, Object>) row.get("sourceProvenance"))
                    : Collections.<String, Object>emptyMap();
            results.add(new TestResult(caseId, caseName, ResultStatus.valueOf(String.valueOf(row.get("status"))),
                    Duration.ofMillis(longValue(row.get("durationMs"))), text(row.get("expected")), text(row.get("actual")), log,
                    validations, workbookId, groupId, strings(row.get("tags")), diagnostic(row.get("diagnostic")),
                    executionId, definitionId.isEmpty() ? null : definitionId,
                    text(row.get("qualifiedCaseId")).isEmpty() ? caseId : text(row.get("qualifiedCaseId")),
                    executionStarted, flowId.isEmpty() ? null : flowId,
                    flowRevision.isEmpty() ? null : flowRevision, sourceRevision.isEmpty() ? null : sourceRevision,
                    sourceProvenance));
        }
        Instant ended = runNode.get("endedAt") == null ? Files.getLastModifiedTime(manifest).toInstant() : Instant.parse(String.valueOf(runNode.get("endedAt")));
        Instant started = runNode.get("startedAt") == null ? ended : Instant.parse(String.valueOf(runNode.get("startedAt")));
        RunSummary summary = new RunSummary(results, runDir);
        Path report = new HtmlReportGenerator().generate(runDir, runId, summary, started, ended);
        new CiReportWriter().writeJunitHtml(runDir, runId, summary, 10240);
        if (results.stream().anyMatch(result -> result.definitionId() != null))
            new NativeCaseExcelReportWriter().write(runDir, runId, results);
        return report;
    }
    private long longValue(Object value) { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private java.util.List<String> strings(Object value) { java.util.List<String> result = new java.util.ArrayList<String>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(String.valueOf(item)); return result; }

    @SuppressWarnings("unchecked")
    private Object protectManifestValue(Object value) {
        if (value instanceof Map) {
            Map<String,Object> result = new java.util.LinkedHashMap<String,Object>();
            for (Map.Entry<?,?> entry : ((Map<?,?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                result.put(key, att.security.SecretProtection.sensitiveName(key)
                        ? att.security.SecretProtection.REDACTED : protectManifestValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(protectManifestValue(item));
            return result;
        }
        if (value instanceof String) return att.security.SecretProtection.text((String) value);
        return value;
    }

    private att.validation.Diagnostic diagnostic(Object value) {
        if (!(value instanceof Map)) return null;
        return att.validation.Diagnostic.fromMap((Map<String,?>) value);
    }
}
