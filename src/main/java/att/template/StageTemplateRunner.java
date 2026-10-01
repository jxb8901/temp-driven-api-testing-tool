/* Author: Jeffrey + ChatGPT */
package att.template;

import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.InternalExceptionLogger;
import att.core.ResultStatus;
import att.core.ValidationResult;
import att.exec.ActionExecutionResult;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes V2.3 actions and persists one canonical nested outcome per action. */
public class StageTemplateRunner {
    private final UnifiedTemplateEngine templateEngine;
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();
    private final RenderPayloadResolver payloadResolver = new RenderPayloadResolver();
    private final FlowRegistry flows;

    public StageTemplateRunner(UnifiedTemplateEngine templateEngine) { this(templateEngine, null); }
    public StageTemplateRunner(UnifiedTemplateEngine templateEngine, FlowRegistry flows) { this.templateEngine = templateEngine; this.flows = flows; }

    public List<ValidationResult> execute(String stageName, StageTemplate template, CaseRuntimeContext context, CaseExecutionLog log) {
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        for (TemplateAction action : template.actions()) {
            Instant started = Instant.now();
            List<String> targets = new ArrayList<String>();
            Map<String, Object> output = outcome(targets);
            if ("flow".equalsIgnoreCase(action.type())) {
                output.remove("result");
            }
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("id", action.id());
            node.put("type", action.type());
            String description = action.description();
            node.put("description", description);
            node.put("output", output);
            boolean recorded = false;
            boolean invocationSucceeded = true;
            ResultStatus toolStatus = null;
            att.validation.Diagnostic actionDiagnostic = null;
            String expected = "", actual = "";
            String executionField = "runWhen";
            try {
                context.beginAction(output);
                String type = action.type().toLowerCase(java.util.Locale.ROOT);
                if (!action.runWhen().trim().isEmpty() && !evaluateCondition(action.runWhen(), context, log)) {
                    output.put("status", "SKIPPED"); output.put("success", true);
                    output.put("durationMs", Duration.between(started, Instant.now()).toMillis());
                    context.addAction(action.id(), node); recorded = true;
                    context.setActionOutput(output);
                    executionField = "description";
                    node.put("description", normalizeLines(templateEngine.render(description, context, log)));
                    context.updateAction(action.id(), node); appendActionLog(log, "ACTION " + action.id() + " SKIPPED", node);
                    results.add(new ValidationResult(stageName, action.id(), String.valueOf(node.get("description")), ResultStatus.SKIPPED, "", "", ""));
                    continue;
                }
                executionField = "render".equals(type) ? "payload" : "tool".equals(type) ? "call" : "db".equals(type) ? (action.query().isEmpty() ? "update" : "query")
                        : "assert".equals(type) ? "expected" : "log".equals(type) ? "message" : "assign".equals(type) ? "expression" : "use";
                Map<String, Object> actionStart = new LinkedHashMap<String, Object>();
                actionStart.put("stage", stageName);
                actionStart.put("action", action.id());
                actionStart.put("type", type);
                actionStart.put("status", "START");
                appendProgress(log, "ACTION", actionStart);
                if ("render".equals(type)) executeRender(action, template, context, log, output, targets);
                else if ("tool".equals(type)) toolStatus = executeTool(stageName, template, action, context, log, output, targets, node);
                else if ("db".equals(type)) toolStatus = executeDb(stageName, action, context, log, output, targets);
                else if ("assert".equals(type)) expected = templateEngine.render(action.expected(), context, log);
                else if ("log".equals(type)) executeLog(action, context, log, output);
                else if ("assign".equals(type)) executeAssign(action, context, log, output);
                else if ("flow".equals(type)) {
                    FlowExecutionResult flowResult = executeFlow(stageName, action, context, log, output, node);
                    toolStatus = flowResult.status;
                    actionDiagnostic = flowResult.diagnostic;
                }
                else throw new IllegalArgumentException("Unsupported action type: " + action.type());

                context.addAction(action.id(), node);
                recorded = true;
                context.setActionOutput(output);
                executionField = "assert";
                ResultStatus status = toolStatus == null ? applyAssertion(action, output, context, log, invocationSucceeded) : toolStatus;
                if ("assert".equals(type)) {
                    output.put("result", Boolean.valueOf(status == ResultStatus.PASS));
                    executionField = "actual";
                    actual = normalizeLines(templateEngine.render(action.actual(), context, log));
                    expected = normalizeLines(expected);
                    output.put("expected", expected);
                    output.put("actual", actual);
                }
                boolean assertionReport = "assert".equals(type)
                        || (("tool".equals(type) || "db".equals(type)) && !action.assertion().trim().isEmpty());
                if ("tool".equals(type) && assertionReport) {
                    executionField = "expected";
                    expected = normalizeLines(templateEngine.render(action.expected(), context, log));
                    executionField = "actual";
                    actual = normalizeLines(templateEngine.render(action.actual(), context, log));
                    output.put("expected", expected);
                    output.put("actual", actual);
                }
                output.put("durationMs", Duration.between(started, Instant.now()).toMillis());
                executionField = "description";
                node.put("description", normalizeLines(templateEngine.render(description, context, log)));
                publishLegacyActionViews(node, output);
                context.updateAction(action.id(), node);
                appendActionLog(log, "ACTION " + action.id(), node);
                String reportExpected = assertionReport ? joinLines(String.valueOf(node.get("description")), expected) : "";
                results.add(new ValidationResult(stageName, action.id(), String.valueOf(node.get("description")), status,
                        reportExpected, assertionReport ? actual : "", assertionMessage(output), actionDiagnostic));
                if (status != ResultStatus.PASS && stopOnFailure(action)) break;
            } catch (Exception e) {
                String internalPhase = "tool".equalsIgnoreCase(action.type()) ? "tool.call"
                        : "db".equalsIgnoreCase(action.type())
                        ? (action.query().isEmpty() ? "db.update" : "db.query")
                        : action.type().toLowerCase(java.util.Locale.ROOT) + "." + executionField;
                InternalExceptionLogger.logIfInternal(log, internalPhase, e, java.util.Collections.<String>emptyList());
                att.validation.DiagnosticException typed = detailed(e, template, action, executionField).withContext(context.diagnosticContext());
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
                node.put("diagnostic", typed.toDiagnostic().toMap());
                output.put("diagnostic", typed.toDiagnostic().toMap());
                exception.put("context", typed.context().toMap());
                output.put("exception", exception);
                context.setActionOutput(output);
                node.put("description", normalizeLines(templateEngine.renderValuesPreserving(description, context)));
                try {
                    publishLegacyActionViews(node, output);
                    if (recorded) context.updateAction(action.id(), node); else context.addAction(action.id(), node);
                    appendActionLog(log, "ACTION " + action.id() + " ERROR", node);
                } catch (Exception evidenceError) { recordEvidenceError(node, evidenceError); }
                String reportExpected = "assert".equalsIgnoreCase(action.type()) ? joinLines(String.valueOf(node.get("description")), expected) : "";
                results.add(new ValidationResult(stageName, action.id(), String.valueOf(node.get("description")),
                        ResultStatus.ERROR, reportExpected, actual, message, typed.toDiagnostic()));
                if (stopOnFailure(action)) break;
            } finally {
                context.endAction();
                context.clearActionOutput();
            }
        }
        return results;
    }

    private FlowExecutionResult executeFlow(String stageName, TemplateAction action, CaseRuntimeContext context,
                                             CaseExecutionLog log, Map<String, Object> output, Map<String, Object> node) throws Exception {
        if (flows == null) throw new IllegalStateException("Flow execution is unavailable");
        FlowDefinition flow = flows.get(action.use());
        if (flow == null) throw new IllegalArgumentException("Unresolved Flow reference '" + action.use() + "'");
        List<ValidationResult> internal = new ArrayList<ValidationResult>();
        CaseRuntimeContext.FlowEvidence evidence = null;
        context.beginFlow(flow.id(), action.id());
        Map<String, Object> flowEvent = new LinkedHashMap<String, Object>();
        flowEvent.put("stage", stageName);
        flowEvent.put("action", action.id());
        flowEvent.put("flowId", flow.id());
        flowEvent.put("template", flow.name());
        flowEvent.put("status", "START");
        appendProgress(log, "FLOW", flowEvent);
        String flowStatus = "ERROR";
        try {
            StageTemplate body = new StageTemplate(flow.name(), flow.directory(), flow.actions(), att.Version.TEMPLATE_SCHEMA, flow.directory().resolve("flow.yaml"));
            internal.addAll(execute(stageName + "." + action.id(), body, context, log));
            ResultStatus status = aggregateFlow(internal);
            flowStatus = status.name();
            output.put("status", status.name()); output.put("success", status == ResultStatus.PASS);
            att.validation.Diagnostic diagnostic = null;
            for (ValidationResult result : internal) {
                if ((result.status() == ResultStatus.ERROR || result.status() == ResultStatus.INVALID)
                        && result.diagnostic() != null) {
                    diagnostic = result.diagnostic();
                    node.put("diagnostic", diagnostic.toMap());
                    output.put("exception", diagnostic.toMap());
                    break;
                }
            }
            return new FlowExecutionResult(status, diagnostic);
        } finally {
            evidence = context.finishFlow();
            Map<String, Object> flowNode = new LinkedHashMap<String, Object>();
            flowNode.putAll(evidence.flow()); flowNode.put("name", flow.name()); flowNode.put("actions", evidence.actions());
            node.put("flow", flowNode);
            flowEvent.put("status", flowStatus);
            appendProgress(log, "FLOW", flowEvent);
        }
    }

    private static final class FlowExecutionResult {
        private final ResultStatus status;
        private final att.validation.Diagnostic diagnostic;
        private FlowExecutionResult(ResultStatus status, att.validation.Diagnostic diagnostic) {
            this.status = status;
            this.diagnostic = diagnostic;
        }
    }

    private ResultStatus aggregateFlow(List<ValidationResult> results) {
        boolean invalid = false, fail = false;
        for (ValidationResult result : results) {
            if (result.status() == ResultStatus.ERROR) return ResultStatus.ERROR;
            if (result.status() == ResultStatus.INVALID) invalid = true;
            else if (result.status() == ResultStatus.FAIL) fail = true;
        }
        if (invalid) return ResultStatus.INVALID;
        if (fail) return ResultStatus.FAIL;
        return ResultStatus.PASS;
    }

    private void executeRender(TemplateAction action, StageTemplate template, CaseRuntimeContext context, CaseExecutionLog log,
                               Map<String, Object> output, List<String> targets) throws Exception {
        Path templateRoot = template.directory().toRealPath();
        List<Path> matches = payloadResolver.resolve(template.directory(), action.payload());
        Map<String, Object> multiple = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> sourceEvidence = new ArrayList<Map<String, Object>>();
        Object single = null;
        long started = System.nanoTime();
        for (int index = 0; index < matches.size(); index++) {
            Path source = matches.get(index);
            String relative = RenderPayloadResolver.portable(templateRoot.relativize(source));
            String content = PayloadCache.readUtf8(source);
            String rendered;
            try { rendered = templateEngine.render(content, context, log); }
            catch (Exception error) {
                throw att.config.YamlSupport.locateText(att.validation.DiagnosticException.wrap(
                        att.validation.DiagnosticCodes.TEMPLATE_INVALID, "Unable to render payload", error, null, null,
                        "Check the payload expression and available Context values."), source, "actions." + action.id() + ".payload");
            }
            Object value = rendered;
            if (matches.size() == 1) single = value; else multiple.put(relative, value);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("source", relative);
            item.put("sourceBytes", Long.valueOf(Files.size(source))); item.put("renderedChars", Integer.valueOf(rendered.length()));
            sourceEvidence.add(item);
        }
        output.put("result", matches.size() == 1 ? single : multiple);
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("resource", "render");
        evidence.put("sources", sourceEvidence); evidence.put("durationMs", Long.valueOf((System.nanoTime() - started) / 1000000L));
        output.put("evidence", evidence);
    }

    private Path renderPayloadRoot(Path templateDirectory, String payload) throws Exception {
        int wildcard = firstGlobCharacter(payload);
        String prefix = wildcard < 0 ? payload : payload.substring(0, wildcard);
        int slash = prefix.lastIndexOf('/');
        String directory = slash < 0 ? "" : prefix.substring(0, slash);
        return templateDirectory.toRealPath().resolve(directory.replace('/', java.io.File.separatorChar)).normalize().toRealPath();
    }

    private int firstGlobCharacter(String value) {
        int result = -1;
        for (char token : new char[]{'*', '?', '{', '['}) {
            int found = value.indexOf(token);
            if (found >= 0 && (result < 0 || found < result)) result = found;
        }
        return result;
    }

    private void executeLog(TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log, Map<String, Object> output) throws Exception {
        String message = normalizeLines(templateEngine.render(action.message(), context, log));
        Object value = action.valuePresent() ? templateEngine.evaluateTypedTree(action.value(), context, log) : null;
        String formatted = action.valuePresent() ? new TypedValueFormatter().format(value, action.format()) : "";
        output.put("result", message.isEmpty() ? formatted : formatted.isEmpty() ? message : message + "\n" + formatted);
        output.put("level", action.level());
        try { log.appendRaw("LOG " + action.id() + " " + action.level(), String.valueOf(output.get("result"))); }
        catch (Exception error) { recordEvidenceError(output, error); }
    }

    private Path logSource(String value, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        if (value == null || value.trim().isEmpty()) throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.PATH_INVALID,
                "Log action file path is blank", "configuredPath=" + value,
                null, "file", null, null, null, null, null,
                "Resolve file to an existing Case-contained log file.", null);
        Path configured = java.nio.file.Paths.get(value);
        Path source = configured.isAbsolute() ? configured.normalize() : context.caseOutputDirectory().resolve(configured).normalize();
        Path root = context.caseOutputDirectory().toRealPath();
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.PATH_INVALID,
                    "Log action file does not exist or is unsafe",
                    "configuredPath=" + value + ", resolvedPath=" + source + ", allowedRoot=" + context.caseOutputDirectory(),
                    null, "file", null, null, null, null, null,
                    "Use an existing regular non-symlink file under the current Case output directory.", null);
        }
        Path real = source.toRealPath();
        if (!real.startsWith(root)) throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.PATH_INVALID,
                "Log action file escapes the Case output directory",
                "configuredPath=" + value + ", resolvedPath=" + real + ", allowedRoot=" + root,
                null, "file", null, null, null, null, null,
                "Use a Case-contained relative path.", null);
        if (real.equals(log.path().toRealPath())) throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.PATH_INVALID,
                "A log action cannot read the current Case log file",
                "configuredPath=" + value + ", resolvedPath=" + real,
                null, "file", null, null, null, null, null,
                "Select a different input log file.", null);
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

    private ResultStatus executeDb(String stageName, TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log,
                                   Map<String, Object> output, List<String> targets) throws Exception {
        CaseRuntimeContext.MetadataScope scope = context.pushComponentMetadata("DBHELPER",
                metadata(action.db(), "dbhelper"));
        try { return executeDbScoped(stageName, action, context, log, output, targets); }
        finally { scope.close(); }
    }

    private ResultStatus executeDbScoped(String stageName, TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log,
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
        Object configuredNamed = operation.get("parameters");
        if (configuredParams != null && configuredNamed != null) throw new IllegalArgumentException("DB action cannot use both params and parameters");
        List<Object> params = new ArrayList<Object>();
        List<String> parameterNames = new ArrayList<String>();
        if (configuredParams instanceof List) {
            for (Object value : (List<?>) configuredParams) {
                params.add(value instanceof String ? templateEngine.evaluate((String) value, context, log) : value);
            }
        } else if (configuredParams != null) {
            Object value = templateEngine.evaluate(String.valueOf(configuredParams), context, log);
            if (!(value instanceof List)) throw new IllegalArgumentException("DB action params must resolve to a List");
            params.addAll((List<?>) value);
        }
        if (configuredNamed != null) {
            if (!(configuredNamed instanceof Map)) throw new IllegalArgumentException("DB action parameters must be a map");
            Map<String, Object> resolved = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) configuredNamed).entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                resolved.put(name, value instanceof String ? templateEngine.evaluate((String) value, context, log) : value);
            }
            NamedSqlParameters.Binding binding = NamedSqlParameters.bind(sql, resolved);
            sql = binding.sql(); params.addAll(binding.values()); parameterNames.addAll(binding.names());
        }

        Map<String, Object> retry = action.retry();
        int maxAttempts = integer(retry.get("maxAttempts"), 1);
        java.util.Set<String> retryOn = strings(retry.get("retryOn"));
        int intervalMs = integer(retry.get("intervalMs"), 0);
        List<Map<String, Object>> attempts = new ArrayList<Map<String, Object>>();
        if (!retry.isEmpty()) output.put("attempts", attempts);

        for (int number = 1; number <= maxAttempts; number++) {
            String invocationId = context.nextDbInvocationId(action.db());
            att.exec.DbInvocationResult result;
            appendResourceEvent(log, stageName, action.id(), "db", number, "START", null, null);
            long operationStarted = System.nanoTime();
            try {
                if (action.timeoutMs() != null) {
                    // Explicit Action timeout overrides the helper's statement timeout.
                    // Positional execution is also valid for SQL normalized from named parameters.
                    result = executor.execute(action.db(), query ? "query" : "update", sql, source,
                            params, invocationId, action.timeoutMs());
                } else {
                    result = parameterNames.isEmpty()
                            ? executor.execute(action.db(), query ? "query" : "update", sql, source, params, invocationId)
                            : executor.execute(action.db(), query ? "query" : "update", sql, source, params, parameterNames, invocationId);
                }
            } catch (Exception error) {
                appendResourceEvent(log, stageName, action.id(), "db", number, "ERROR",
                        elapsedMillis(operationStarted), error.getClass().getSimpleName());
                throw error;
            }
            executor.recordResourceOutput(action.db(), result, context);
        try { log.append("DB " + action.db() + " " + invocationId, result.evidence()); }
            catch (Exception error) { recordEvidenceError(output, error); result.evidence().put("evidenceError", safeMessage(error)); }

            ActionExecutionResult operationResult = result.operationResult();
            appendResourceEvent(log, stageName, action.id(), "db", number,
                    operationResult.executionSuccess() ? "PASS" : "ERROR", operationResult.durationMs(),
                    operationResult.executionSuccess() ? null : dbFailureType(operationResult.result()));
            publishOperationResult(output, operationResult);
            Map<String, Object> attempt = new LinkedHashMap<String, Object>();
            attempt.put("attempt", number);
            attempt.put("invocationId", invocationId);
            attempt.put("result", operationResult.result());
            attempt.put("evidence", operationResult.evidence());
            attempt.put("status", operationResult.executionSuccess() ? "PASS" : "ERROR");
            attempt.put("success", Boolean.valueOf(operationResult.executionSuccess()));
            if (!retry.isEmpty()) attempts.add(attempt);

            if (!operationResult.executionSuccess()) {
                String category = dbFailureType(operationResult.result());
                if (category != null) attempt.put("category", category);
                if (query && "TIMEOUT".equals(category)
                        && shouldRetry(retryOn, "TIMEOUT", number, maxAttempts)) {
                    attempt.put("retryReason", "TIMEOUT");
                    appendResourceEvent(log, stageName, action.id(), "db", number, "RETRY", null, "TIMEOUT");
                    waitBeforeRetry(intervalMs);
                    continue;
                }
                if (!retry.isEmpty()) output.put("finalAttempt", number);
                output.put("status", "ERROR"); output.put("success", false);
                return ResultStatus.ERROR;
            }

            context.setActionOutput(output);
            boolean passed = evaluateAssertion(action, output, context, log);
            if (output.get("assertion") != null) {
                attempt.put("assertion", new LinkedHashMap<String, Object>((Map<String, Object>) output.get("assertion")));
            }
            if (passed) {
                if (!retry.isEmpty()) output.put("winningAttempt", number);
                output.put("status", "PASS"); output.put("success", true);
                return ResultStatus.PASS;
            }
            if (!shouldRetry(retryOn, "ASSERTION", number, maxAttempts)) {
                if (!retry.isEmpty()) output.put("finalAttempt", number);
                output.put("status", "FAIL"); output.put("success", false);
                return ResultStatus.FAIL;
            }
            attempt.put("status", "FAIL"); attempt.put("success", false);
            attempt.put("retryReason", "ASSERTION");
            waitBeforeRetry(intervalMs);
        }
        throw new IllegalStateException("DB action completed without a final attempt: " + action.id());
    }

    private Map<String, Object> metadata(String id, String type) {
        Map<String, Object> values = new LinkedHashMap<String, Object>(); values.put("id", id); values.put("type", type); return values;
    }

    @SuppressWarnings("unchecked")
    private String dbFailureType(Object result) {
        if (!(result instanceof Map)) return null;
        Object error = ((Map<String, Object>) result).get("error");
        if (!(error instanceof Map)) return null;
        Object type = ((Map<String, Object>) error).get("type");
        return type == null ? null : String.valueOf(type);
    }

    private ResultStatus executeTool(String stageName, StageTemplate template, TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log, Map<String, Object> output,
                             List<String> targets, Map<String, Object> node) throws Exception {
        Map<String, Object> retry = action.retry();
        int maxAttempts = integer(retry.get("maxAttempts"), 1);
        java.util.Set<String> retryOn = strings(retry.get("retryOn"));
        int intervalMs = integer(retry.get("intervalMs"), 0);
        List<Map<String, Object>> attempts = new ArrayList<Map<String, Object>>();
        output.put("attempts", attempts);
        String kind = templateEngine.callKind(action.call());
        for (int number = 1; number <= maxAttempts; number++) {
            appendResourceEvent(log, stageName, action.id(), kind, number, "START", null, null);
            long operationStarted = System.nanoTime();
            try {
                att.exec.ToolInvocationResult result;
                try {
                    result = templateEngine.executeToolAttempt(action.call(), context, log,
                            context.qualifiedActionId(action.id()), action.id(), action.timeoutMs(), "", "",
                            false, !retry.isEmpty());
                } catch (att.exec.ToolExecutionException error) {
                    throw error;
                } catch (Exception error) {
                    appendResourceEvent(log, stageName, action.id(), kind, number, "ERROR",
                            elapsedMillis(operationStarted), error.getClass().getSimpleName());
                    throw error;
                }
                Map<String, Object> invocation = new LinkedHashMap<String, Object>(result.invocation());
                invocation.put("attempt", number);
                ActionExecutionResult operation = result.operationResult();
                appendResourceEvent(log, stageName, action.id(), kind, number,
                        operation.executionSuccess() ? "PASS" : "ERROR",
                        operation.durationMs() >= 0L ? operation.durationMs() : elapsedMillis(operationStarted),
                        operation.executionSuccess() ? null : "OPERATION_FAILED");
                Object selectedResult = resultValue(operation.result());
                attempts.add(invocation);
                publishOperationResult(output, operation);
                output.put("result", selectedResult);
                invocation.put("evidence", operation.evidence());
                copy(invocation, output, "exitCode", "stdout", "stderr", "rawOutput", "command", "logicalArgv", "argv", "timeoutMs");
                if (invocation.get("TOOL") != null) node.put("TOOL", invocation.get("TOOL"));
                if (invocation.get("DB") != null) node.put("DB", invocation.get("DB"));
                if (invocation.get("HTTP") != null) node.put("HTTP", invocation.get("HTTP"));
                if (invocation.get("SSH") != null) node.put("SSH", invocation.get("SSH"));
                if (!operation.executionSuccess()) {
                    if ((("mq".equals(kind) && "MQ_TIMEOUT".equals(mqErrorType(operation.outputMetadata())))
                            || ("http".equals(kind) && httpTimeout(operation.outputMetadata()))
                            || ("ssh".equals(kind) && sshTimeout(result.invocation())))
                            && shouldRetry(retryOn, "TIMEOUT", number, maxAttempts)) {
                        invocation.put("retryReason", "TIMEOUT");
                        appendResourceEvent(log, stageName, action.id(), kind, number, "RETRY", null, "TIMEOUT");
                        waitBeforeRetry(intervalMs);
                        continue;
                    }
                    output.put("finalAttempt", number);
                    output.put("status", "ERROR"); output.put("success", false);
                    return ResultStatus.ERROR;
                }
                context.setActionOutput(output);
                runEvidenceCollectors(template, action, number, context, log, output, invocation);
                boolean passed;
                try {
                    passed = evaluateAssertion(action, output, context, log);
                } catch (Exception error) {
                    throw phaseDiagnostic(error, template, action, "actions." + action.id() + ".assert");
                }
                if (output.get("assertion") != null) invocation.put("assertion", new LinkedHashMap<String, Object>((Map<String, Object>) output.get("assertion")));
                if (passed) {
                    output.put("winningAttempt", number);
                    output.put("status", "PASS"); output.put("success", true);
                    return ResultStatus.PASS;
                }
                if (!shouldRetry(retryOn, "ASSERTION", number, maxAttempts)) {
                    output.put("finalAttempt", number);
                    output.put("status", "FAIL"); output.put("success", false);
                    return ResultStatus.FAIL;
                }
                invocation.put("retryReason", "ASSERTION");
                appendResourceEvent(log, stageName, action.id(), kind, number, "RETRY", null, "ASSERTION");
                waitBeforeRetry(intervalMs);
            } catch (att.exec.ToolExecutionException e) {
                appendResourceEvent(log, stageName, action.id(), kind, number, "ERROR",
                        elapsedMillis(operationStarted), e.category());
                Map<String, Object> evidence = new LinkedHashMap<String, Object>(e.evidence());
                evidence.put("attempt", number);
                evidence.put("category", e.category());
                Map<String, Object> operationEvidence = new LinkedHashMap<String, Object>(evidence);
                operationEvidence.remove("TOOL");
                operationEvidence.remove("DB");
                operationEvidence.remove("MQ");
                Map<String, Object> failedEvidence = ActionExecutionResult.evidence("tool", operationEvidence);
                evidence.put("evidence", failedEvidence);
                attempts.add(evidence);
                copy(evidence, output, "exitCode", "stdout", "stderr", "rawOutput", "command", "logicalArgv", "argv", "timeoutMs");
                if (evidence.containsKey("output")) output.put("result", evidence.get("output"));
                replaceActionEvidence(output, failedEvidence);
                if (evidence.get("TOOL") != null) node.put("TOOL", evidence.get("TOOL"));
                if (evidence.get("DB") != null) node.put("DB", evidence.get("DB"));
                if (evidence.get("SSH") != null) node.put("SSH", evidence.get("SSH"));
                if ("TIMEOUT".equals(e.category()) && shouldRetry(retryOn, "TIMEOUT", number, maxAttempts)) {
                    evidence.put("retryReason", "TIMEOUT");
                    appendResourceEvent(log, stageName, action.id(), kind, number, "RETRY", null, "TIMEOUT");
                    waitBeforeRetry(intervalMs);
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("Tool action completed without a final attempt: " + action.id());
    }

    private void appendResourceEvent(CaseExecutionLog log, String stage, String action, String kind,
                                     int attempt, String status, Long durationMs, String errorType) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("stage", stage);
        event.put("action", action);
        event.put("resource", kind == null ? "tool" : kind.toUpperCase(java.util.Locale.ROOT));
        event.put("attempt", Integer.valueOf(attempt));
        event.put("status", status);
        if (durationMs != null && durationMs.longValue() >= 0L) event.put("durationMs", durationMs);
        if (errorType != null && !errorType.isEmpty()) event.put("errorType", errorType);
        appendProgress(log, "RESOURCE", event);
    }

    private void appendProgress(CaseExecutionLog log, String section, Map<String, Object> event) {
        try { log.append(section, event); } catch (Exception ignored) { /* progress must not change execution semantics */ }
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    @SuppressWarnings("unchecked")
    private String mqErrorType(Map<String, Object> outputMetadata) {
        Object error = outputMetadata == null ? null : outputMetadata.get("error");
        if (!(error instanceof Map)) return null;
        Object type = ((Map<String, Object>) error).get("type");
        return type == null ? null : String.valueOf(type);
    }

    @SuppressWarnings("unchecked")
    private boolean httpTimeout(Map<String, Object> outputMetadata) {
        String type = mqErrorType(outputMetadata);
        return "HTTP_TIMEOUT".equals(type) || "HTTP_POOL_TIMEOUT".equals(type);
    }

    @SuppressWarnings("unchecked")
    private boolean sshTimeout(Map<String, Object> invocation) {
        Object ssh = invocation == null ? null : invocation.get("SSH");
        if (!(ssh instanceof Map)) return false;
        Object error = ((Map<String, Object>) ssh).get("error");
        if (!(error instanceof Map)) return false;
        Object category = ((Map<String, Object>) error).get("category");
        return "SSH_TIMEOUT".equals(category) || "SSH_POOL_TIMEOUT".equals(category);
    }

    private void runEvidenceCollectors(StageTemplate template, TemplateAction action, int attempt, CaseRuntimeContext context,
                                       CaseExecutionLog log, Map<String, Object> output,
                                       Map<String, Object> invocation) throws Exception {
        if (action.evidence().isEmpty()) return;
        Map<String, Object> attemptEvidence = invocationEvidence(invocation);
        Map<String, Object> evidence = attemptEvidence.get("collectors") instanceof Map
                ? (Map<String, Object>) attemptEvidence.get("collectors") : new LinkedHashMap<String, Object>();
        attemptEvidence.put("collectors", evidence);
        for (EvidenceCollector collector : action.evidence().values()) {
            long started = System.nanoTime();
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            record.put("collectorId", collector.id());
            record.put("attempt", attempt);
            record.put("status", "ERROR");
            try {
                if ("mq".equals(templateEngine.callKind(collector.call()))) {
                    throw new IllegalArgumentException("MQ operations may only be the primary call of a type: tool Action");
                }
                String invocationId = context.qualifiedActionId(action.id()) + ".evidence." + collector.id() + "." + attempt;
                // Satisfy the executor log contract without publishing unprojected process/resource output.
                att.exec.ToolInvocationResult result;
                try (CaseExecutionLog executorLog = CaseExecutionLog.discarding(log == null
                        ? context.caseOutputDirectory().resolve("case.log") : log.path())) {
                    result = templateEngine.executeToolAttempt(collector.call(), context, executorLog,
                            invocationId, collector.timeoutMs(), "", false, true);
                }
                Object status = result.invocation().get("status");
                boolean passed = result.executionSuccess() && "PASS".equalsIgnoreCase(String.valueOf(status));
                if (!passed) result = CollectorExceptionEvidence.project(result);
                record.put("status", passed ? "PASS" : (status == null ? "ERROR" : String.valueOf(status)));
                record.put("success", Boolean.valueOf(passed));
                record.put("invocationId", result.invocationId());
                record.put("result", result.output());
                if (!result.operationResult().evidence().isEmpty()) {
                    record.put("evidence", result.operationResult().evidence());
                }
                if (result.operationResult().diagnostic() != null && !result.operationResult().diagnostic().isEmpty()) {
                    record.put("diagnostic", result.operationResult().diagnostic());
                }
                record.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                evidence.put(collector.id(), record);
                Map<String, Object> collectorEvidence = new LinkedHashMap<String, Object>();
                collectorEvidence.put("collectors", evidence);
                ActionExecutionResult.mergeEvidence(attemptEvidence, collectorEvidence);
                ActionExecutionResult.mergeEvidence(outputEvidence(output), collectorEvidence);
                if (!passed) {
                    record.put("error", collectorResultError(result, "Evidence collector did not complete successfully"));
                    if (record.get("diagnostic") != null) record.put("operationDiagnostic", record.get("diagnostic"));
                    att.validation.DiagnosticException diagnostic = collectorDiagnostic(template, action, collector, record, null);
                    record.put("diagnostic", diagnostic.toDiagnostic().toMap());
                    throw new EvidenceCollectorFailure(collector, record, diagnostic);
                }
                appendEvidenceLog(log, action, attempt, collector, record);
            } catch (EvidenceCollectorFailure failure) {
                record.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                evidence.put(collector.id(), record);
                appendEvidenceLog(log, action, attempt, collector, record);
                if ("stop".equals(collector.onFailure())) throw failure;
            } catch (Exception error) {
                if (error instanceof att.exec.ToolExecutionException) {
                    error = CollectorExceptionEvidence.project((att.exec.ToolExecutionException) error);
                }
                record.put("status", error instanceof att.exec.ToolExecutionException
                        ? ((att.exec.ToolExecutionException) error).category() : "ERROR");
                record.put("success", false);
                record.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                record.put("error", collectorError(error));
                Map<String, Object> failureEvidence = collectorFailureEvidence(error);
                if (!failureEvidence.isEmpty()) record.put("evidence", failureEvidence);
                att.validation.DiagnosticException diagnostic = collectorDiagnostic(template, action, collector, record, error);
                record.put("diagnostic", diagnostic.toDiagnostic().toMap());
                evidence.put(collector.id(), record);
                Map<String, Object> collectorEvidence = new LinkedHashMap<String, Object>();
                collectorEvidence.put("collectors", evidence);
                ActionExecutionResult.mergeEvidence(attemptEvidence, collectorEvidence);
                ActionExecutionResult.mergeEvidence(outputEvidence(output), collectorEvidence);
                appendEvidenceLog(log, action, attempt, collector, record);
                if ("stop".equals(collector.onFailure())) throw new EvidenceCollectorFailure(collector, record, diagnostic);
            }
        }
    }

    private att.validation.DiagnosticException collectorDiagnostic(StageTemplate template, TemplateAction action,
                                                                    EvidenceCollector collector, Map<String, Object> record,
                                                                    Throwable failure) {
        String field = "actions." + action.id() + ".evidence." + collector.id() + ".call";
        att.validation.DiagnosticException existing = failure == null ? null : att.validation.DiagnosticException.find(failure);
        if (existing != null) return phaseDiagnostic(existing, template, action, field);
        Object error = record.get("error");
        String message = error instanceof Map && ((Map<?, ?>) error).get("message") != null
                ? String.valueOf(((Map<?, ?>) error).get("message"))
                : failure instanceof Exception ? safeMessage((Exception) failure) : "Evidence collector failed";
        if (message == null || message.trim().isEmpty()) message = "Evidence collector failed";
        att.validation.DiagnosticException generated = new att.validation.DiagnosticException(
                att.validation.DiagnosticCodes.TOOL_EXECUTION,
                "Evidence collector '" + collector.id() + "' failed",
                message,
                null, field, null, null, null, template.name(), action.id(),
                "Inspect the collector call and its execution evidence for the underlying resource failure.", failure);
        return phaseDiagnostic(generated, template, action, field);
    }

    private Map<String, Object> collectorResultError(att.exec.ToolInvocationResult result, String fallback) {
        if (result == null) return collectorError((Object) null, fallback);
        // Defer the fallback until the native operation diagnostic/result has been consulted.
        Map<String, Object> error = collectorError(result.invocation(), "");
        Map<String, Object> diagnostic = result.operationResult().diagnostic();
        String message = firstMessage(diagnostic);
        if (isBlank(String.valueOf(error.get("message"))) && !isBlank(message)) error.put("message", message);
        if (error.get("category") == null && !result.executionSuccess()) error.put("category", "OPERATION_FAILED");
        if (error.get("category") == null && "ERROR".equalsIgnoreCase(String.valueOf(result.invocation().get("status")))) {
            error.put("category", "OPERATION_FAILED");
        }
        if (isBlank(String.valueOf(error.get("message"))) || fallback.equals(String.valueOf(error.get("message")))) {
            error.put("message", operationFailureMessage(result, fallback));
        }
        return error;
    }

    private Map<String, Object> collectorError(Object source, String fallback) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        if (source instanceof Map) {
            Map<?, ?> invocation = (Map<?, ?>) source;
            Object category = invocation.get("category");
            if (category == null) category = nestedValue(invocation.get("error"), "type", "category");
            Object message = firstMessage(invocation.get("message"), invocation.get("error"), invocation.get("diagnostic"));
            if (category != null) error.put("category", category);
            if (!isBlank(String.valueOf(message))) error.put("message", message);
            Object exitCode = invocation.get("exitCode");
            if (exitCode == null) exitCode = nestedValue(invocation.get("error"), "exitCode", "reasonCode");
            if (exitCode != null) error.put("exitCode", exitCode);
            if (invocation.get("error") instanceof Map) {
                Map<?, ?> nativeError = (Map<?, ?>) invocation.get("error");
                for (String field : new String[] {"type", "sqlState", "vendorCode", "cancellation", "completionCode", "reasonCode", "reason"}) {
                    if (nativeError.containsKey(field)) error.put(field, nativeError.get(field));
                }
            }
            if (error.get("category") == null && "ERROR".equalsIgnoreCase(String.valueOf(invocation.get("status")))) {
                error.put("category", "OPERATION_FAILED");
            }
        }
        if (isBlank(String.valueOf(error.get("message")))) error.put("message", fallback);
        return error;
    }

    private Map<String, Object> collectorError(Exception error) {
        if (error instanceof att.exec.ToolExecutionException) {
            att.exec.ToolExecutionException tool = (att.exec.ToolExecutionException) error;
            Map<String, Object> result = collectorError(tool.evidence(), tool.getMessage());
            if (result.get("category") == null) result.put("category", tool.category());
            if (result.get("exitCode") == null && tool.exitCode() != null) result.put("exitCode", tool.exitCode());
            if (isBlank(String.valueOf(result.get("message")))) result.put("message", tool.getMessage());
            return result;
        }
        return collectorError((Object) null, safeMessage(error));
    }

    private Map<String, Object> collectorFailureEvidence(Exception error) {
        if (error instanceof att.exec.ToolExecutionException) {
            att.exec.ToolExecutionException tool = (att.exec.ToolExecutionException) error;
            return ActionExecutionResult.evidence("tool", CollectorExceptionEvidence.project(tool).evidence());
        }
        att.validation.DiagnosticException diagnostic = att.validation.DiagnosticException.find(error);
        if (diagnostic == null) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("diagnostic", diagnostic.toDiagnostic().toMap());
        return result;
    }

    private String operationFailureMessage(att.exec.ToolInvocationResult result, String fallback) {
        String status = String.valueOf(result.invocation().get("status"));
        Object exitCode = result.invocation().get("exitCode");
        if (!isBlank(status) && !"null".equalsIgnoreCase(status)) {
            return exitCode == null
                    ? "Evidence collector operation failed with status " + status
                    : "Evidence collector operation failed with status " + status + " (exitCode=" + exitCode + ")";
        }
        return fallback;
    }

    private Object nestedValue(Object value, String... keys) {
        if (!(value instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) value;
        for (String key : keys) if (map.get(key) != null) return map.get(key);
        return null;
    }

    private String firstMessage(Object... values) {
        for (Object value : values) {
            String message = messageValue(value);
            if (!isBlank(message)) return message;
        }
        return "";
    }

    private String messageValue(Object value) {
        if (value == null) return "";
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (String key : new String[]{"message", "error", "detail", "reason", "description", "summary"}) {
                String nested = messageValue(map.get(key));
                if (!isBlank(nested)) return nested;
            }
            return "";
        }
        String message = String.valueOf(value).trim();
        return message.isEmpty() ? "" : message;
    }

    private boolean isBlank(String value) { return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim()); }

    private void appendEvidenceLog(CaseExecutionLog log, TemplateAction action, int attempt,
                                   EvidenceCollector collector, Map<String, Object> record) {
        try {
            log.append("EVIDENCE " + action.id() + " attempt=" + attempt + " collector=" + collector.id(), record);
        } catch (Exception error) {
            record.put("logError", safeMessage(error));
        }
    }

    private static final class EvidenceCollectorFailure extends Exception {
        private EvidenceCollectorFailure(EvidenceCollector collector, Map<String, Object> record) {
            super("Evidence collector '" + collector.id() + "' failed: " + rootCause(record));
        }
        private EvidenceCollectorFailure(EvidenceCollector collector, Map<String, Object> record, Throwable cause) {
            super("Evidence collector '" + collector.id() + "' failed: " + rootCause(record), cause);
        }
        private static String rootCause(Map<String, Object> record) {
            Object error = record.get("error");
            if (error instanceof Map) {
                Object message = ((Map<?, ?>) error).get("message");
                if (message != null && !String.valueOf(message).trim().isEmpty()) return String.valueOf(message);
            }
            return String.valueOf(record.get("status"));
        }
    }

    private String toolFormat(ActionResultConfig save, String kind, String call) {
        String fallback = templateEngine.configuredToolResultFormat(call);
        if (fallback == null || fallback.isEmpty()) fallback = "text";
        String format = requiredFormat(save, "Tool", fallback);
        if (!("text".equals(format) || "json".equals(format) || "yaml".equals(format) || "xml".equals(format))) {
            throw new IllegalArgumentException("Tool result.format must be text, json, yaml, or xml: " + format);
        }
        return format;
    }

    private String requiredFormat(ActionResultConfig save, String owner, String fallback) {
        String format = save.format() == null ? "" : save.format().trim().toLowerCase(java.util.Locale.ROOT);
        if (format.isEmpty()) format = fallback;
        if (format.isEmpty()) throw new IllegalArgumentException(owner + " result.format is required");
        if ("DB".equals(owner) && !("text".equals(format) || "json".equals(format) || "yaml".equals(format) || "xml".equals(format))) {
            throw new IllegalArgumentException("DB result.format must be text, json, yaml, or xml: " + format);
        }
        return format;
    }

    private boolean console(String path) { return "console".equalsIgnoreCase(path == null ? "" : path.trim()); }

    private Object resultValue(Object nativeResult) {
        return nativeResult;
    }

    private ResultStatus applyAssertion(TemplateAction action, Map<String, Object> output, CaseRuntimeContext context, CaseExecutionLog log,
                                        boolean invocationSucceeded) throws Exception {
        if (!invocationSucceeded) {
            output.put("status", "ERROR"); output.put("success", false); return ResultStatus.ERROR;
        }
        if (action.assertion() == null || action.assertion().trim().isEmpty()) {
            output.put("status", "PASS"); output.put("success", true); return ResultStatus.PASS;
        }
        boolean passed = evaluateAssertion(action, output, context, log);
        output.put("status", passed ? "PASS" : "FAIL");
        output.put("success", passed);
        return passed ? ResultStatus.PASS : ResultStatus.FAIL;
    }

    private boolean evaluateAssertion(TemplateAction action, Map<String, Object> output, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        if (action.assertion() == null || action.assertion().trim().isEmpty()) return true;
        Object evaluated = templateEngine.evaluate(action.assertion(), context, log);
        String rendered = String.valueOf(evaluated);
        boolean passed = evaluated instanceof Boolean ? ((Boolean) evaluated).booleanValue()
                : evaluator.evaluate(String.valueOf(evaluated));
        Map<String, Object> assertion = new LinkedHashMap<String, Object>();
        assertion.put("expression", action.assertion());
        assertion.put("rendered", rendered);
        assertion.put("passed", passed);
        output.put("assertion", assertion);
        return passed;
    }

    private boolean evaluateCondition(String expression, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        Object evaluated = templateEngine.evaluate(expression, context, log);
        return evaluated instanceof Boolean ? ((Boolean) evaluated).booleanValue()
                : evaluator.evaluate(String.valueOf(evaluated));
    }

    private boolean shouldRetry(java.util.Set<String> retryOn, String reason, int attempt, int maxAttempts) {
        return retryOn.contains(reason) && attempt < maxAttempts;
    }

    private void waitBeforeRetry(int intervalMs) throws InterruptedException {
        if (intervalMs <= 0) return;
        try { Thread.sleep(intervalMs); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
    }

    private Map<String, Object> outcome(List<String> targets) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("status", "PASS");
        output.put("success", true);
        output.put("exception", null);
        output.put("durationMs", 0L);
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
    private void publishOperationResult(Map<String, Object> output, ActionExecutionResult result) {
        if (result == null) return;
        if (result.outputMetadata() != null) output.putAll(result.outputMetadata());
        output.put("result", result.result());
        replaceActionEvidence(output, result.evidence());
        if (result.diagnostic() != null) output.put("diagnostic", result.diagnostic());
    }

    @SuppressWarnings("unchecked")
    private void replaceActionEvidence(Map<String, Object> output, Map<String, Object> additions) {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        ActionExecutionResult.mergeEvidence(evidence, additions);
        if (evidence.isEmpty()) output.remove("evidence"); else output.put("evidence", evidence);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputEvidence(Map<String, Object> output) {
        Map<String, Object> evidence = output.get("evidence") instanceof Map
                ? (Map<String, Object>) output.get("evidence") : new LinkedHashMap<String, Object>();
        output.put("evidence", evidence);
        return evidence;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invocationEvidence(Map<String, Object> invocation) {
        Map<String, Object> evidence = invocation.get("evidence") instanceof Map
                ? (Map<String, Object>) invocation.get("evidence") : new LinkedHashMap<String, Object>();
        invocation.put("evidence", evidence);
        return evidence;
    }

    @SuppressWarnings("unchecked")
    private void publishLegacyActionViews(Map<String, Object> node, Map<String, Object> output) {
        Object evidence = output.get("evidence");
        if (!(evidence instanceof Map)) return;
        Object db = ((Map<?, ?>) evidence).get("db");
        if (!(db instanceof Map)) return;
        Object invocations = ((Map<?, ?>) db).get("invocations");
        if (!(invocations instanceof List)) return;
        Map<String, Object> calls = new LinkedHashMap<String, Object>();
        for (Object item : (List<?>) invocations) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> dbEvidence = (Map<?, ?>) item;
            Object helper = dbEvidence.get("db");
            Object invocation = dbEvidence.get("id");
            if (helper == null || invocation == null) continue;
            Object helperCalls = calls.get(String.valueOf(helper));
            Map<String, Object> helperNode = helperCalls instanceof Map
                    ? (Map<String, Object>) helperCalls : new LinkedHashMap<String, Object>();
            helperNode.put(String.valueOf(invocation), dbEvidence);
            calls.put(String.valueOf(helper), helperNode);
        }
        if (calls.isEmpty()) return;
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        legacy.putAll(calls);
        node.put("DB", legacy);
    }
    private int integer(Object value, int fallback) { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
    private java.util.Set<String> strings(Object value) { java.util.Set<String> result = new java.util.LinkedHashSet<String>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(String.valueOf(item)); return result; }
    private java.util.Set<Integer> integers(Object value) { java.util.Set<Integer> result = new java.util.LinkedHashSet<Integer>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(Integer.valueOf(String.valueOf(item))); return result; }
    private boolean stopOnFailure(TemplateAction action) { return !"continue".equals(action.onFailure()); }
    private String assertionMessage(Map<String, Object> output) { Object value = output.get("assertion"); return value == null ? "" : String.valueOf(value); }
    private Map<String, Object> diagnosticLocation(att.validation.DiagnosticException diagnostic) {
        return att.validation.DiagnosticRenderer.location(diagnostic.toDiagnostic());
    }
    private void appendActionLog(CaseExecutionLog log, String section, Map<String, Object> node) {
        try { log.appendAction(section, node); }
        catch (Exception error) { recordEvidenceError(node, error); }
    }
    private void recordEvidenceError(Map<String, Object> node, Exception error) {
        Object existing = node.get("evidenceError");
        String message = safeMessage(error);
        node.put("evidenceError", existing == null ? message : String.valueOf(existing) + "; " + message);
    }
    private att.validation.DiagnosticException phaseDiagnostic(Exception error, StageTemplate template,
                                                               TemplateAction action, String field) {
        att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(error);
        if (typed == null) {
            typed = new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TOOL_EXECUTION,
                    "Action '" + action.id() + "' failed", safeMessage(error), null, field,
                    null, null, null, template.name(), action.id(),
                    "Inspect the action field and its execution evidence.", error);
        }
        return phaseDiagnostic(typed, template, action, field);
    }
    private att.validation.DiagnosticException phaseDiagnostic(att.validation.DiagnosticException typed,
                                                               StageTemplate template, TemplateAction action,
                                                               String field) {
        if (typed.file() != null || template.sourceFile() == null) return typed;
        return att.config.YamlSupport.locate(typed, template.sourceFile(), field)
                .withLocation(null, null, null, null, null, template.name(), action.id());
    }
    private att.validation.DiagnosticException detailed(Exception error, StageTemplate template, TemplateAction action, String executionField) {
        att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(error);
        if (typed == null && error instanceof att.exec.ToolExecutionException) {
            att.exec.ToolExecutionException tool = (att.exec.ToolExecutionException) error;
            typed = new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TOOL_EXECUTION,
                    "Tool action '" + action.id() + "' failed", toolDetail(tool, error),
                    null, "actions." + action.id() + ".call", null, null, null, template.name(), action.id(),
                    "Inspect logical argv, executed argv, exitCode, stdout, stderr, rawOutput, and parser diagnostics in this action's evidence.", error);
        }
        if (typed == null) {
            String code = "tool".equalsIgnoreCase(action.type()) ? att.validation.DiagnosticCodes.TOOL_EXECUTION : att.validation.DiagnosticCodes.TEMPLATE_INVALID;
            typed = new att.validation.DiagnosticException(code, "Action '" + action.id() + "' failed",
                    safeMessage(error), null, "actions." + action.id(), null, null, null, template.name(), action.id(),
                    "Check the action fields, Context references, input files, call arguments, and detailed Case-log evidence.", error);
        }
        String sourceField = "actions." + action.id() + "." + executionField;
        if (typed.file() == null && typed.field() != null && (typed.field().startsWith("result") || typed.field().equals("sqlFile")))
            sourceField = "actions." + action.id() + "." + typed.field();
        return att.config.YamlSupport.locate(typed, template.sourceFile(), sourceField)
                .withLocation(null, null, null, null, null, template.name(), action.id());
    }
    private String toolDetail(att.exec.ToolExecutionException tool, Exception error) {
        StringBuilder detail = new StringBuilder("category=").append(tool.category()).append(", cause=").append(safeMessage(error));
        Map<String, Object> evidence = tool.evidence();
        appendEvidence(detail, evidence, "attempt", "durationMs", "timeoutMs", "exitCode", "outputFile", "parserDiagnostic");
        return detail.toString();
    }
    private void appendEvidence(StringBuilder detail, Map<String, Object> evidence, String... keys) {
        for (String key : keys) if (evidence.get(key) != null) detail.append(", ").append(key).append('=').append(evidence.get(key));
    }
    private String safeMessage(Exception e) { return e.getMessage() == null || e.getMessage().trim().isEmpty() ? e.getClass().getSimpleName() : e.getMessage(); }
    private String normalizeLines(String value) { return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n'); }
    private String joinLines(String first, String second) { String a = normalizeLines(first).trim(), b = normalizeLines(second).trim(); return a.isEmpty() ? b : (b.isEmpty() ? a : a + "\n" + b); }
}
