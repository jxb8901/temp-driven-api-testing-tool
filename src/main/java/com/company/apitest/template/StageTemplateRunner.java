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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes V1.2 template actions and records action outcomes in the case context.
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
                    Map<String, Object> invocation = record(action, rendered, rendered, "PASS");
                    context.addToolInvocation(action.id(), invocation);
                    log.append("ACTION " + action.id(), invocation);
                    results.add(new ValidationResult(stageName, action.id(), ResultStatus.PASS, "", rendered, ""));
                } else if ("tool".equalsIgnoreCase(action.type())) {
                    Object output = templateEngine.executeCall(action.call(), context, log, action.id());
                    results.add(new ValidationResult(stageName, action.id(), ResultStatus.PASS, action.call(), String.valueOf(output), ""));
                } else if ("assert".equalsIgnoreCase(action.type())) {
                    String rendered = templateEngine.renderValues(action.expression(), context);
                    boolean passed = evaluator.evaluate(rendered);
                    Map<String, Object> invocation = record(action, rendered, rendered, passed ? "PASS" : "FAIL");
                    context.addToolInvocation(action.id(), invocation);
                    log.append("ACTION " + action.id(), invocation);
                    results.add(new ValidationResult(stageName, action.id(), passed ? ResultStatus.PASS : ResultStatus.FAIL, action.expression(), rendered, ""));
                } else if ("log".equalsIgnoreCase(action.type())) {
                    String message = templateEngine.renderValues(action.message(), context);
                    Map<String, Object> invocation = record(action, message, message, "PASS");
                    context.addToolInvocation(action.id(), invocation);
                    log.append("ACTION " + action.id(), invocation);
                    results.add(new ValidationResult(stageName, action.id(), ResultStatus.PASS, "", message, ""));
                } else {
                    throw new IllegalArgumentException("Unsupported action type: " + action.type());
                }
            } catch (Exception e) {
                results.add(new ValidationResult(stageName, action.id(), ResultStatus.ERROR, action.type(), "", e.getMessage()));
                break;
            }
        }
        return results;
    }

    private Map<String, Object> record(TemplateAction action, Object output, String rawOutput, String status) {
        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("type", action.type());
        invocation.put("output", output);
        invocation.put("rawOutput", rawOutput);
        invocation.put("status", status);
        invocation.put("durationMs", 0);
        return invocation;
    }
}
