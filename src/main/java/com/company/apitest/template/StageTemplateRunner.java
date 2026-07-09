/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import com.company.apitest.core.CaseExecutionLog;
import com.company.apitest.core.CaseRuntimeContext;
import com.company.apitest.core.ResultStatus;
import com.company.apitest.core.ValidationResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes V1.3 template actions and records action outcomes in the case context.
 */
public class StageTemplateRunner {
    private final UnifiedTemplateEngine templateEngine;
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    public StageTemplateRunner(UnifiedTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public List<ValidationResult> execute(String stageName, StageTemplate template, CaseRuntimeContext context, CaseExecutionLog log) {
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        context.put("STAGE.name", stageName);
        context.put("STAGE.template", template.name());
        for (TemplateAction action : template.actions()) {
            try {
                if ("render".equalsIgnoreCase(action.type())) {
                    String content = new String(Files.readAllBytes(template.directory().resolve(action.payload())), StandardCharsets.UTF_8);
                    String rendered = templateEngine.render(content, context, log);
                    Map<String, Object> invocation = renderRecord(action, rendered, context);
                    context.addAction(action.id(), invocation);
                    log.append("ACTION " + action.id(), invocation);
                    results.add(new ValidationResult(stageName, action.id(), ResultStatus.PASS, "", rendered, ""));
                } else if ("tool".equalsIgnoreCase(action.type())) {
                    Object output = templateEngine.executeCall(action.call(), context, log, action.id());
                    results.add(new ValidationResult(stageName, action.id(), ResultStatus.PASS, action.call(), String.valueOf(output), ""));
                } else if ("assert".equalsIgnoreCase(action.type())) {
                    String rendered = templateEngine.renderValues(action.expression(), context);
                    boolean passed = evaluator.evaluate(rendered);
                    Map<String, Object> invocation = record(action, rendered, rendered, passed ? "PASS" : "FAIL");
                    context.addAction(action.id(), invocation);
                    log.append("ACTION " + action.id(), invocation);
                    results.add(new ValidationResult(stageName, action.id(), passed ? ResultStatus.PASS : ResultStatus.FAIL, action.expression(), rendered, ""));
                    if (!passed && stopOnFailure(action, template)) {
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
                results.add(new ValidationResult(stageName, action.id(), ResultStatus.ERROR, action.type(), "", e.getMessage()));
                if (stopOnFailure(action, template)) {
                    break;
                }
            }
        }
        return results;
    }

    private Map<String, Object> renderRecord(TemplateAction action, String rendered, CaseRuntimeContext context) throws Exception {
        String saveAs = action.saveAs();
        Object mode = action.output().get("mode");
        if ((saveAs != null && !saveAs.trim().isEmpty()) || "file".equalsIgnoreCase(String.valueOf(mode))) {
            String fileName = saveAs == null || saveAs.trim().isEmpty() ? "output.txt" : saveAs;
            Path directory = context.actionOutputDir(action.id());
            Files.createDirectories(directory);
            Path outputFile = directory.resolve(fileName).normalize();
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
        invocation.put("actionId", action.id());
        invocation.put("type", action.type());
        invocation.put("output", output);
        invocation.put("rawOutput", rawOutput);
        invocation.put("status", status);
        invocation.put("durationMs", 0);
        return invocation;
    }

    private boolean stopOnFailure(TemplateAction action, StageTemplate template) {
        String onFailure = action.onFailure();
        if (onFailure == null || onFailure.trim().isEmpty()) {
            Object defaultValue = template.actionDefaults().get("onFailure");
            onFailure = defaultValue == null ? "stop" : String.valueOf(defaultValue);
        }
        return !"continue".equalsIgnoreCase(onFailure);
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
