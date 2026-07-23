/* Author: Jeffrey + ChatGPT */
package att.template;

import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.ValidationResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes V2.3 actions and persists one canonical nested outcome per action. */
public class StageTemplateRunner {
    private final UnifiedTemplateEngine templateEngine;
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();
    private final RenderPayloadResolver payloadResolver = new RenderPayloadResolver();
    private final ActionResultArtifactWriter artifactWriter = new ActionResultArtifactWriter();

    public StageTemplateRunner(UnifiedTemplateEngine templateEngine) { this.templateEngine = templateEngine; }

    public List<ValidationResult> execute(String stageName, StageTemplate template, CaseRuntimeContext context, CaseExecutionLog log) {
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        for (TemplateAction action : template.actions()) {
            Instant started = Instant.now();
            List<String> targets = new ArrayList<String>();
            Map<String, Object> output = outcome(targets);
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("id", action.id());
            node.put("type", action.type());
            String description = action.description();
            node.put("description", description);
            node.put("output", output);
            boolean recorded = false;
            boolean invocationSucceeded = true;
            String expected = "", actual = "";
            try {
                String type = action.type().toLowerCase(java.util.Locale.ROOT);
                if ("render".equals(type)) executeRender(action, template, context, log, output, targets);
                else if ("tool".equals(type)) invocationSucceeded = executeTool(action, context, log, output, targets, node);
                else if ("db".equals(type)) invocationSucceeded = executeDb(action, context, log, output, targets);
                else if ("assert".equals(type)) expected = templateEngine.render(action.expected(), context, log);
                else if ("log".equals(type)) executeLog(action, context, log, output);
                else if ("assign".equals(type)) executeAssign(action, context, log, output);
                else throw new IllegalArgumentException("Unsupported action type: " + action.type());

                context.addAction(action.id(), node);
                recorded = true;
                context.setActionOutput(output);
                ResultStatus status = applyAssertion(action, output, context, log, invocationSucceeded);
                if ("assert".equals(type)) {
                    output.put("result", Boolean.valueOf(status == ResultStatus.PASS));
                    actual = normalizeLines(templateEngine.render(action.actual(), context, log));
                    expected = normalizeLines(expected);
                    output.put("expected", expected);
                    output.put("actual", actual);
                }
                output.put("durationMs", Duration.between(started, Instant.now()).toMillis());
                node.put("description", normalizeLines(templateEngine.render(description, context, log)));
                mergeDbEvidence(node, context.drainDbInvocations());
                context.updateAction(action.id(), node);
                log.appendAction("ACTION " + action.id(), node);
                String reportExpected = "assert".equals(type) ? joinLines(String.valueOf(node.get("description")), expected) : "";
                results.add(new ValidationResult(stageName, action.id(), status, reportExpected, "assert".equals(type) ? actual : "", assertionMessage(output)));
                if (status != ResultStatus.PASS && stopOnFailure(action)) break;
            } catch (Exception e) {
                att.validation.DiagnosticException typed = detailed(e, template, action);
                String message = typed.format();
                output.put("status", "ERROR");
                output.put("success", false);
                output.put("durationMs", Duration.between(started, Instant.now()).toMillis());
                Map<String, Object> exception = new LinkedHashMap<String, Object>();
                exception.put("type", e.getClass().getName());
                exception.put("code", typed.code());
                exception.put("summary", typed.summary());
                exception.put("detail", typed.detail());
                exception.put("location", diagnosticLocation(typed));
                exception.put("suggestion", typed.suggestion());
                exception.put("message", message);
                output.put("exception", exception);
                context.setActionOutput(output);
                node.put("description", normalizeLines(templateEngine.renderValuesPreserving(description, context)));
                try {
                    mergeDbEvidence(node, context.drainDbInvocations());
                    if (recorded) context.updateAction(action.id(), node); else context.addAction(action.id(), node);
                    log.appendAction("ACTION " + action.id() + " ERROR", node);
                } catch (Exception ignored) { }
                String reportExpected = "assert".equalsIgnoreCase(action.type()) ? joinLines(String.valueOf(node.get("description")), expected) : "";
                results.add(new ValidationResult(stageName, action.id(), ResultStatus.ERROR, reportExpected, actual, message));
                if (stopOnFailure(action)) break;
            } finally {
                context.clearActionOutput();
            }
        }
        return results;
    }

    private void executeRender(TemplateAction action, StageTemplate template, CaseRuntimeContext context, CaseExecutionLog log,
                               Map<String, Object> output, List<String> targets) throws Exception {
        Path templateRoot = template.directory().toRealPath();
        List<Path> matches = payloadResolver.resolve(template.directory(), action.payload());
        Map<String, Object> multiple = new LinkedHashMap<String, Object>();
        Object single = null;
        for (Path source : matches) {
            String relative = RenderPayloadResolver.portable(templateRoot.relativize(source));
            String content = PayloadCache.readUtf8(source);
            String rendered = templateEngine.render(content, context, log);
            Object value;
            if ("file".equalsIgnoreCase(action.renderAs())) {
                Path target = context.caseOutputDirectory().resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
                if (!target.startsWith(context.caseOutputDirectory())) throw new IllegalArgumentException("Render target escapes Case output directory: " + relative);
                Files.createDirectories(target.getParent());
                Files.write(target, rendered.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
                value = target.toString();
                targets.add(target.toString());
            } else value = templateEngine.parseRendered(rendered, action.renderAs());
            if (matches.size() == 1) single = value; else multiple.put(relative, value);
        }
        output.put("result", "file".equalsIgnoreCase(action.renderAs()) ? new ArrayList<String>(targets) : (matches.size() == 1 ? single : multiple));
        output.put("renderAs", action.renderAs().toLowerCase(java.util.Locale.ROOT));
        output.put("sources", sourceNames(templateRoot, matches));
    }

    private void executeLog(TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log, Map<String, Object> output) throws Exception {
        String message = normalizeLines(templateEngine.render(action.message(), context, log));
        String content = "";
        if (!action.file().trim().isEmpty()) {
            String renderedPath = templateEngine.render(action.file(), context, log);
            Path source = logSource(renderedPath, context, log);
            byte[] bytes = Files.readAllBytes(source);
            content = normalizeLines(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            output.put("sourceFile", source.toString());
        }
        output.put("result", joinLogContent(message, content));
        output.put("level", action.level());
        output.put("fields", renderFields(action.fields(), context, log));
    }

    private Path logSource(String value, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Log file expression resolved to a blank path");
        Path configured = java.nio.file.Paths.get(value);
        Path source = configured.isAbsolute() ? configured.normalize() : context.caseOutputDirectory().resolve(configured).normalize();
        Path root = context.caseOutputDirectory().toRealPath();
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Log file must be an existing regular non-symlink file: " + value);
        }
        Path real = source.toRealPath();
        if (!real.startsWith(root)) throw new IllegalArgumentException("Log file must stay under the current Case output directory: " + value);
        if (real.equals(log.path().toRealPath())) throw new IllegalArgumentException("A log action cannot read the current Case log file");
        return real;
    }

    private String joinLogContent(String message, String content) {
        if (message == null || message.isEmpty()) return content == null ? "" : content;
        if (content == null || content.isEmpty()) return message;
        return message + "\n" + content;
    }

    private void executeAssign(TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log,
                               Map<String, Object> output) throws Exception {
        context.requireCaseVariableAvailable(action.name());
        Object value = templateEngine.evaluate(action.expression(), context, log);
        output.put("result", value);
        output.put("name", action.name());
        context.assignCaseVariable(action.name(), value);
    }

    private boolean executeDb(TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log,
                              Map<String, Object> output, List<String> targets) throws Exception {
        att.exec.DbHelperExecutor executor = templateEngine.dbHelperExecutor();
        if (executor == null) throw new IllegalStateException("DB action execution is unavailable");
        boolean query = !action.query().isEmpty();
        Map<String, Object> operation = query ? action.query() : action.update();
        String source = "inline";
        String sql;
        if (operation.containsKey("sqlFile")) {
            String configured = String.valueOf(operation.get("sqlFile"));
            Path file = executor.resolveSqlFile(configured);
            sql = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            source = configured;
        } else sql = String.valueOf(operation.get("sql"));
        sql = templateEngine.renderDbSql(sql, context);
        Object configuredParams = operation.get("params");
        List<Object> params = new ArrayList<Object>();
        if (configuredParams instanceof List) {
            for (Object value : (List<?>) configuredParams) {
                params.add(value instanceof String ? templateEngine.evaluate((String) value, context, log) : value);
            }
        } else if (configuredParams != null) {
            Object value = templateEngine.evaluate(String.valueOf(configuredParams), context, log);
            if (!(value instanceof List)) throw new IllegalArgumentException("DB action params must resolve to a List");
            params.addAll((List<?>) value);
        }
        String invocationId = context.nextDbInvocationId(action.db());
        att.exec.DbInvocationResult result = executor.execute(action.db(), query ? "query" : "update",
                sql, source, params, invocationId);
        context.recordDbInvocation(action.db(), invocationId, result.evidence());
        try { log.append("DB " + action.db() + " " + invocationId, result.evidence()); } catch (Exception ignored) { }
        output.put("result", result.result());
        if (result.success() && action.saveConfig().configured()) {
            String path = templateEngine.render(action.saveConfig().path(), context, log);
            String format = requiredFormat(action.saveConfig(), "DB", "");
            Path saved = artifactWriter.write(context, path, format, result.result(), action.saveConfig().overwrite());
            targets.add(saved.toString());
        }
        return result.success();
    }

    private boolean executeTool(TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log, Map<String, Object> output,
                             List<String> targets, Map<String, Object> node) throws Exception {
        Map<String, Object> retry = action.retry();
        int maxAttempts = integer(retry.get("maxAttempts"), 1);
        java.util.Set<String> retryOn = strings(retry.get("retryOn"));
        java.util.Set<Integer> exitCodes = integers(retry.get("exitCodes"));
        List<Map<String, Object>> attempts = new ArrayList<Map<String, Object>>();
        output.put("attempts", attempts);
        ActionSaveConfig save = action.saveConfig();
        String saveAs = save.configured() ? templateEngine.render(save.path(), context, log) : "";
        String kind = templateEngine.callKind(action.call());
        if ("call-tool".equals(kind) && (action.timeoutMs() != null || !retry.isEmpty())) {
            throw new IllegalArgumentException("call-backed Tool uses dbhelper/built-in limits and does not support process timeoutMs or retry: " + action.id());
        }
        String format = save.configured() ? toolFormat(save, kind) : "";
        boolean invokerWritesRaw = save.configured() && "tool".equals(kind) && "raw".equals(format);
        boolean actionOwnedArtifact = false;
        for (int number = 1; number <= maxAttempts; number++) {
            try {
                att.exec.ToolInvocationResult result = templateEngine.executeToolAttempt(action.call(), context, log,
                        action.id(), action.timeoutMs(), invokerWritesRaw ? saveAs : "",
                        save.overwrite() || actionOwnedArtifact);
                Map<String, Object> invocation = new LinkedHashMap<String, Object>(result.invocation());
                invocation.put("attempt", number);
                if (save.configured() && !invokerWritesRaw) {
                    Path saved = artifactWriter.write(context, saveAs, format, result.output(),
                            save.overwrite() || actionOwnedArtifact);
                    actionOwnedArtifact = true;
                    invocation.put("outputFile", saved.toString());
                } else if (invocation.get("outputFile") != null) {
                    actionOwnedArtifact = true;
                }
                attempts.add(invocation);
                int exitCode = integer(invocation.get("exitCode"), 0);
                boolean retryable = exitCode != 0 && retryOn.contains("EXIT_CODE") && (exitCodes.isEmpty() || exitCodes.contains(Integer.valueOf(exitCode)));
                if (retryable && number < maxAttempts) continue;
                output.put("result", result.output());
                copy(invocation, output, "exitCode", "stdout", "stderr", "rawOutput", "command", "logicalArgv", "argv", "timeoutMs");
                output.put("winningAttempt", number);
                Object saved = invocation.get("outputFile");
                if (saved != null) targets.add(String.valueOf(saved));
                if (invocation.get("TOOL") != null) node.put("TOOL", invocation.get("TOOL"));
                if (invocation.get("DB") != null) node.put("DB", invocation.get("DB"));
                return result.executionSuccess();
            } catch (att.exec.ToolExecutionException e) {
                Map<String, Object> evidence = new LinkedHashMap<String, Object>(e.evidence());
                evidence.put("attempt", number);
                evidence.put("category", e.category());
                attempts.add(evidence);
                copy(evidence, output, "exitCode", "stdout", "stderr", "rawOutput", "command", "logicalArgv", "argv", "timeoutMs");
                if (evidence.containsKey("output")) output.put("result", evidence.get("output"));
                if (evidence.get("outputFile") != null) targets.add(String.valueOf(evidence.get("outputFile")));
                if (evidence.get("TOOL") != null) node.put("TOOL", evidence.get("TOOL"));
                if (evidence.get("DB") != null) node.put("DB", evidence.get("DB"));
                throw e;
            }
        }
        throw new IllegalStateException("Tool action completed without a final attempt: " + action.id());
    }

    private String toolFormat(ActionSaveConfig save, String kind) {
        if (save.legacy()) {
            if ("call-tool".equals(kind)) throw new IllegalArgumentException("call-backed Tool saveAs must use {path, format, overwrite}");
            return "builtin".equals(kind) ? "text" : "raw";
        }
        String fallback = "builtin".equals(kind) ? "text" : ("call-tool".equals(kind) ? "" : "raw");
        String format = requiredFormat(save, "Tool", fallback);
        if (("builtin".equals(kind) || "call-tool".equals(kind)) && "raw".equals(format)) {
            throw new IllegalArgumentException("Built-in and call-backed Tool saveAs.format do not support raw; use text, json, yaml, or xml");
        }
        if (!("raw".equals(format) || "text".equals(format) || "json".equals(format)
                || "yaml".equals(format) || "xml".equals(format))) {
            throw new IllegalArgumentException("Tool saveAs.format must be raw, text, json, yaml, or xml: " + format);
        }
        return format;
    }

    private String requiredFormat(ActionSaveConfig save, String owner, String fallback) {
        String format = save.format() == null ? "" : save.format().trim().toLowerCase(java.util.Locale.ROOT);
        if (format.isEmpty()) format = fallback;
        if (format.isEmpty()) throw new IllegalArgumentException(owner + " saveAs.format is required");
        if ("DB".equals(owner) && !("json".equals(format) || "yaml".equals(format) || "xml".equals(format))) {
            throw new IllegalArgumentException("DB saveAs.format must be json, yaml, or xml: " + format);
        }
        return format;
    }

    private ResultStatus applyAssertion(TemplateAction action, Map<String, Object> output, CaseRuntimeContext context, CaseExecutionLog log,
                                        boolean invocationSucceeded) throws Exception {
        if (!invocationSucceeded) {
            output.put("status", "ERROR"); output.put("success", false); return ResultStatus.ERROR;
        }
        if (action.assertion() == null || action.assertion().trim().isEmpty()) {
            output.put("status", "PASS"); output.put("success", true); return ResultStatus.PASS;
        }
        String afterCalls = templateEngine.renderCalls(action.assertion(), context, log);
        String rendered = templateEngine.renderValues(afterCalls, context);
        boolean passed = evaluator.evaluate(afterCalls, context);
        Map<String, Object> assertion = new LinkedHashMap<String, Object>();
        assertion.put("expression", action.assertion());
        assertion.put("rendered", rendered);
        assertion.put("passed", passed);
        output.put("assertion", assertion);
        output.put("status", passed ? "PASS" : "FAIL");
        output.put("success", passed);
        return passed ? ResultStatus.PASS : ResultStatus.FAIL;
    }

    private Map<String, Object> outcome(List<String> targets) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("status", "PASS");
        output.put("success", true);
        output.put("exception", null);
        output.put("durationMs", 0L);
        output.put("targetFiles", targets);
        output.put("result", null);
        return output;
    }

    private List<String> sourceNames(Path root, List<Path> matches) {
        List<String> result = new ArrayList<String>();
        for (Path path : matches) result.add(RenderPayloadResolver.portable(root.relativize(path)));
        return result;
    }

    private Map<String, Object> renderFields(Map<String, Object> fields, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        Map<String, Object> rendered = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) rendered.put(entry.getKey(), templateEngine.render(String.valueOf(entry.getValue()), context, log));
        return rendered;
    }

    private void copy(Map<String, Object> from, Map<String, Object> to, String... keys) { for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key)); }
    @SuppressWarnings("unchecked")
    private void mergeDbEvidence(Map<String, Object> node, Map<String, Object> additions) {
        if (additions == null || additions.isEmpty()) return;
        Map<String, Object> existing = node.get("DB") instanceof Map
                ? (Map<String, Object>) node.get("DB") : new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : additions.entrySet()) {
            if (!(entry.getValue() instanceof Map) || !(existing.get(entry.getKey()) instanceof Map)) {
                existing.put(entry.getKey(), entry.getValue());
            } else {
                ((Map<String, Object>) existing.get(entry.getKey())).putAll((Map<String, Object>) entry.getValue());
            }
        }
        node.put("DB", existing);
    }
    private int integer(Object value, int fallback) { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
    private java.util.Set<String> strings(Object value) { java.util.Set<String> result = new java.util.LinkedHashSet<String>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(String.valueOf(item)); return result; }
    private java.util.Set<Integer> integers(Object value) { java.util.Set<Integer> result = new java.util.LinkedHashSet<Integer>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(Integer.valueOf(String.valueOf(item))); return result; }
    private boolean stopOnFailure(TemplateAction action) { return !"continue".equals(action.onFailure()); }
    private String assertionMessage(Map<String, Object> output) { Object value = output.get("assertion"); return value == null ? "" : String.valueOf(value); }
    private Map<String, Object> diagnosticLocation(att.validation.DiagnosticException diagnostic) {
        Map<String, Object> location = new LinkedHashMap<String, Object>();
        if (diagnostic.file() != null) location.put("file", diagnostic.file());
        if (diagnostic.field() != null) location.put("field", diagnostic.field());
        if (diagnostic.sheet() != null) location.put("sheet", diagnostic.sheet());
        if (diagnostic.row() != null) location.put("row", diagnostic.row());
        if (diagnostic.column() != null) location.put("column", diagnostic.column());
        if (diagnostic.template() != null) location.put("template", diagnostic.template());
        if (diagnostic.action() != null) location.put("action", diagnostic.action());
        return location;
    }
    private att.validation.DiagnosticException detailed(Exception error, StageTemplate template, TemplateAction action) {
        att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(error);
        if (typed == null && error instanceof att.exec.ToolExecutionException) {
            att.exec.ToolExecutionException tool = (att.exec.ToolExecutionException) error;
            typed = new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TOOL_EXECUTION,
                    "Tool action '" + action.id() + "' failed", "category=" + tool.category() + ", cause=" + safeMessage(error),
                    null, "actions." + action.id() + ".call", null, null, null, template.name(), action.id(),
                    "Inspect logical argv, executed argv, exitCode, stdout, stderr, rawOutput, and parser diagnostics in this action's evidence.", error);
        }
        if (typed == null) {
            String code = "tool".equalsIgnoreCase(action.type()) ? att.validation.DiagnosticCodes.TOOL_EXECUTION : att.validation.DiagnosticCodes.TEMPLATE_INVALID;
            typed = new att.validation.DiagnosticException(code, "Action '" + action.id() + "' failed",
                    safeMessage(error), null, "actions." + action.id(), null, null, null, template.name(), action.id(),
                    "Check the action fields, Context references, input files, call arguments, and detailed Case-log evidence.", error);
        }
        return typed.withLocation(template.directory().resolve("template.yaml").toString(),
                "actions." + action.id(), null, null, null, template.name(), action.id());
    }
    private String safeMessage(Exception e) { return e.getMessage() == null || e.getMessage().trim().isEmpty() ? e.getClass().getSimpleName() : e.getMessage(); }
    private String normalizeLines(String value) { return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n'); }
    private String joinLines(String first, String second) { String a = normalizeLines(first).trim(), b = normalizeLines(second).trim(); return a.isEmpty() ? b : (b.isEmpty() ? a : a + "\n" + b); }
}
