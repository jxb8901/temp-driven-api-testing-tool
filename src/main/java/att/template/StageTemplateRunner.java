/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.ValidationResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes V2 template actions and records outcomes in the CASE tree.
 */
public class StageTemplateRunner {
    private final UnifiedTemplateEngine templateEngine;
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    public StageTemplateRunner(UnifiedTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public List<ValidationResult> execute(String stageName, StageTemplate template, CaseRuntimeContext context, CaseExecutionLog log) {
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        for (TemplateAction action : template.actions()) {
            try {
                if ("render".equalsIgnoreCase(action.type())) {
                    Path payload = template.directory().resolve(att.core.IdentifierValidator.relativePath(action.payload(), "render payload")).normalize();
                    Path canonicalTemplate = template.directory().toRealPath();
                    Path canonicalPayload = payload.toRealPath();
                    if (!canonicalPayload.startsWith(canonicalTemplate) || Files.isSymbolicLink(payload) || !Files.isRegularFile(canonicalPayload)) {
                        throw new IllegalArgumentException("Render payload must stay inside template directory: " + action.payload());
                    }
                    String content = new String(Files.readAllBytes(canonicalPayload), StandardCharsets.UTF_8);
                    String rendered = templateEngine.render(content, context, log);
                    Map<String, Object> invocation = renderRecord(action, rendered, context);
                    context.addAction(action.id(), invocation);
                    log.append("ACTION " + action.id(), invocation);
                    results.add(new ValidationResult(stageName, action.id(), ResultStatus.PASS, "", rendered, ""));
                } else if ("tool".equalsIgnoreCase(action.type())) {
                    Object output = executeTool(action, context, log);
                    results.add(new ValidationResult(stageName, action.id(), ResultStatus.PASS, action.call(), String.valueOf(output), ""));
                } else if ("assert".equalsIgnoreCase(action.type())) {
                    String rendered = templateEngine.renderValues(action.expression(), context);
                    boolean passed = evaluator.evaluate(action.expression(), context);
                    Map<String, Object> invocation = record(action, rendered, rendered, passed ? "PASS" : "FAIL");
                    context.addAction(action.id(), invocation);
                    log.append("ACTION " + action.id(), invocation);
                    results.add(new ValidationResult(stageName, action.id(), passed ? ResultStatus.PASS : ResultStatus.FAIL, action.expression(), rendered, ""));
                    if (!passed && stopOnFailure(action)) {
                        break;
                    }
                } else if ("log".equalsIgnoreCase(action.type())) {
                    String message = templateEngine.renderValues(action.message(), context);
                    Map<String, Object> invocation = record(action, message, message, "PASS");
                    invocation.put("level", action.level());
                    invocation.put("message", message);
                    invocation.put("fields", renderFields(action.fields(), templateEngine, context));
                    context.addAction(action.id(), invocation);
                    log.append("ACTION " + action.id(), invocation);
                    results.add(new ValidationResult(stageName, action.id(), ResultStatus.PASS, "", message, ""));
                } else {
                    throw new IllegalArgumentException("Unsupported action type: " + action.type());
                }
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                results.add(new ValidationResult(stageName, action.id(), ResultStatus.ERROR, action.type(), "", message));
                Map<String,Object> error = new LinkedHashMap<String,Object>(); error.put("id", action.id()); error.put("type", action.type()); error.put("status", "ERROR"); error.put("message", message);
                try { log.append("ACTION " + action.id() + " ERROR", error); } catch (java.io.IOException ignored) { }
                if (stopOnFailure(action)) {
                    break;
                }
            }
        }
        return results;
    }

    private Object executeTool(TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        Map<String, Object> retry = action.retry();
        int maxAttempts = integer(retry.get("maxAttempts"), 1);
        java.util.Set<String> retryOn = strings(retry.get("retryOn"));
        java.util.Set<Integer> exitCodes = integers(retry.get("exitCodes"));
        List<Map<String, Object>> attempts = new ArrayList<Map<String, Object>>();
        att.exec.ToolExecutionException last = null;
        for (int number = 1; number <= maxAttempts; number++) {
            String attemptId = action.id() + "/attempt-" + String.format("%03d", number);
            try {
                att.exec.ToolInvocationResult result = templateEngine.executeToolAttempt(action.call(), context, log, attemptId, action.timeoutMs(), action.saveAs());
                attempts.add(new LinkedHashMap<String, Object>(result.invocation()));
                Map<String, Object> winning = new LinkedHashMap<String, Object>(result.invocation());
                winning.put("id", action.id()); winning.put("attempts", attempts); winning.put("winningAttempt", number);
                context.addAction(action.id(), winning);
                return result.output();
            } catch (att.exec.ToolExecutionException e) {
                last = e; Map<String, Object> evidence = new LinkedHashMap<String, Object>(e.evidence()); evidence.put("attempt", number); evidence.put("category", e.category()); attempts.add(evidence);
                boolean exitEligible = !"EXIT_CODE".equals(e.category()) || exitCodes.isEmpty() || (e.exitCode() != null && exitCodes.contains(e.exitCode()));
                if (number >= maxAttempts || !"EXIT_CODE".equals(e.category()) || !retryOn.contains("EXIT_CODE") || !exitEligible) break;
            }
        }
        Map<String, Object> failed = new LinkedHashMap<String, Object>(); failed.put("id", action.id()); failed.put("type", "tool"); failed.put("status", "ERROR"); failed.put("attempts", attempts); failed.put("winningAttempt", null); context.addAction(action.id(), failed);
        throw last;
    }

    private int integer(Object value, int fallback) { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
    private java.util.Set<String> strings(Object value) { java.util.Set<String> result = new java.util.LinkedHashSet<String>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(String.valueOf(item)); return result; }
    private java.util.Set<Integer> integers(Object value) { java.util.Set<Integer> result = new java.util.LinkedHashSet<Integer>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(Integer.valueOf(String.valueOf(item))); return result; }

    private Map<String, Object> renderRecord(TemplateAction action, String rendered, CaseRuntimeContext context) throws Exception {
        String saveAs = action.saveAs();
        Object mode = action.output().get("mode");
        if ((saveAs != null && !saveAs.trim().isEmpty()) || "file".equalsIgnoreCase(String.valueOf(mode))) {
            String fileName = saveAs == null || saveAs.trim().isEmpty() ? "output.txt" : saveAs;
            Path directory = context.actionOutputDir(action.id());
            Files.createDirectories(directory);
            Path outputFile = directory.resolve(att.core.IdentifierValidator.relativePath(fileName, "render saveAs")).normalize();
            if (!outputFile.startsWith(directory.normalize())) {
                throw new IllegalArgumentException("Action output file must stay under action directory: " + fileName);
            }
            Files.write(outputFile, rendered.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> invocation = record(action, null, null, "PASS");
            invocation.put("outputFile", outputFile.toString());
            invocation.put("outputBytes", rendered.getBytes(StandardCharsets.UTF_8).length);
            invocation.put("outputSha256", sha256(rendered));
            invocation.put("outputPreview", preview(rendered));
            return invocation;
        }
        return record(action, rendered, rendered, "PASS");
    }

    private Map<String, Object> record(TemplateAction action, Object output, String rawOutput, String status) {
        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("id", action.id());
        invocation.put("type", action.type());
        invocation.put("output", output);
        invocation.put("rawOutput", rawOutput);
        invocation.put("status", status);
        invocation.put("durationMs", 0);
        return invocation;
    }

    private boolean stopOnFailure(TemplateAction action) {
        return !"continue".equals(action.onFailure());
    }

    private Map<String, Object> renderFields(Map<String, Object> fields, UnifiedTemplateEngine engine, CaseRuntimeContext context) {
        Map<String, Object> rendered = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            rendered.put(entry.getKey(), engine.renderValues(String.valueOf(entry.getValue()), context));
        }
        return rendered;
    }

    private String preview(String text) {
        String normalized = text.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte value : bytes) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }
}
