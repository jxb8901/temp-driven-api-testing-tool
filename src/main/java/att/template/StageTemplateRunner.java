/* Author: Jeffrey + ChatGPT */
package att.template;

import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes V2.3 actions and persists one canonical nested outcome per action. */
public class StageTemplateRunner {
    private final UnifiedTemplateEngine templateEngine;
    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();
    private final RenderPayloadResolver payloadResolver = new RenderPayloadResolver();
    private final ActionResultArtifactWriter artifactWriter = new ActionResultArtifactWriter();
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
                output.remove("targetFiles");
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
                if ("render".equals(type)) executeRender(action, template, context, log, output, targets);
                else if ("tool".equals(type)) toolStatus = executeTool(action, context, log, output, targets, node);
                else if ("db".equals(type)) invocationSucceeded = executeDb(action, context, log, output, targets);
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
                boolean assertionReport = "assert".equals(type) || ("tool".equals(type) && !action.assertion().trim().isEmpty());
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
        try {
            StageTemplate body = new StageTemplate(flow.name(), flow.directory(), flow.actions(), att.Version.TEMPLATE_SCHEMA, flow.directory().resolve("flow.yaml"));
            internal.addAll(execute(stageName + "." + action.id(), body, context, log));
            ResultStatus status = aggregateFlow(internal);
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
        Object single = null;
        for (Path source : matches) {
            String relative = RenderPayloadResolver.portable(templateRoot.relativize(source));
            String content = PayloadCache.readUtf8(source);
            String rendered;
            try { rendered = templateEngine.render(content, context, log); }
            catch (Exception error) {
                throw att.config.YamlSupport.locateText(att.validation.DiagnosticException.wrap(
                        att.validation.DiagnosticCodes.TEMPLATE_INVALID, "Unable to render payload", error, null, null,
                        "Check the payload expression and available Context values."), source, "actions." + action.id() + ".payload");
            }
            Object value;
            if ("file".equalsIgnoreCase(action.renderAs())) {
                Path renderRoot = context.inFlow() ? context.actionOutputDir(action.id()) : context.caseOutputDirectory();
                Path target = renderRoot.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
                if (!target.startsWith(renderRoot)) throw new IllegalArgumentException("Render target escapes Action output directory: " + relative);
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

    private boolean executeDb(TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log,
                              Map<String, Object> output, List<String> targets) throws Exception {
        att.exec.DbHelperExecutor executor = templateEngine.dbHelperExecutor();
        if (executor == null) throw new IllegalStateException("DB action execution is unavailable");
        context.setDbHelperMetadata(action.db());
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
        String invocationId = context.nextDbInvocationId(action.db());
        att.exec.DbInvocationResult result = parameterNames.isEmpty()
                ? executor.execute(action.db(), query ? "query" : "update", sql, source, params, invocationId)
                : executor.execute(action.db(), query ? "query" : "update", sql, source, params, parameterNames, invocationId);
        context.recordActionEvidence(result.actionResult().evidence());
        try { log.append("DB " + action.db() + " " + invocationId, result.evidence()); }
        catch (Exception error) { recordEvidenceError(output, error); result.evidence().put("evidenceError", safeMessage(error)); }
        output.put("result", result.result());
        if (result.success() && action.saveConfig().configured()) {
            String path = templateEngine.render(action.saveConfig().path(), context, log);
            String format = requiredFormat(action.saveConfig(), "DB", "");
            if (console(path)) try { log.appendRaw("ACTION " + action.id() + " SAVE", artifactWriter.renderDb(format, result.result())); }
            catch (Exception error) { recordEvidenceError(output, error); }
            else {
                Path saved = artifactWriter.writeDb(context, action.id(), path, format, result.result(), action.saveConfig().overwrite());
                targets.add(saved.toString());
            }
        }
        return result.success();
    }

    private ResultStatus executeTool(TemplateAction action, CaseRuntimeContext context, CaseExecutionLog log, Map<String, Object> output,
                             List<String> targets, Map<String, Object> node) throws Exception {
        Map<String, Object> retry = action.retry();
        int maxAttempts = integer(retry.get("maxAttempts"), 1);
        java.util.Set<String> retryOn = strings(retry.get("retryOn"));
        int intervalMs = integer(retry.get("intervalMs"), 0);
        List<Map<String, Object>> attempts = new ArrayList<Map<String, Object>>();
        output.put("attempts", attempts);
        ActionSaveConfig save = action.saveConfig();
        String saveAs = save.configured() ? templateEngine.render(save.path(), context, log) : "";
        boolean console = console(saveAs);
        String kind = templateEngine.callKind(action.call());
        String format = save.configured() ? toolFormat(save, kind) : "";
        boolean invokerWritesRaw = save.configured() && !console && "tool".equals(kind) && "raw".equals(format);
        boolean actionOwnedArtifact = false;
        for (int number = 1; number <= maxAttempts; number++) {
            try {
                String invokerSaveAs = invokerWritesRaw ? context.scopedArtifactPath(action.id(), saveAs) : "";
                att.exec.ToolInvocationResult result = templateEngine.executeToolAttempt(action.call(), context, log,
                        context.qualifiedActionId(action.id()), action.timeoutMs(), invokerSaveAs,
                        save.overwrite() || actionOwnedArtifact, !retry.isEmpty());
                Map<String, Object> invocation = new LinkedHashMap<String, Object>(result.invocation());
                invocation.put("attempt", number);
                if (save.configured() && !invokerWritesRaw) {
                    if (console) {
                        if (!("tool".equals(kind) && "raw".equals(format))) {
                            try { log.appendRaw("ACTION " + action.id() + " SAVE", artifactWriter.render(format, result.output())); }
                            catch (Exception error) { recordEvidenceError(output, error); }
                        }
                    } else {
                        Path saved = artifactWriter.write(context, action.id(), saveAs, format, result.output(),
                                save.overwrite() || actionOwnedArtifact);
                        actionOwnedArtifact = true;
                        invocation.put("outputFile", saved.toString());
                    }
                } else if (invocation.get("outputFile") != null) {
                    actionOwnedArtifact = true;
                }
                attempts.add(invocation);
                output.put("result", result.output());
                mergeActionResult(output, result.actionResult());
                invocation.put("actionResult", result.actionResult().evidence());
                copy(invocation, output, "exitCode", "stdout", "stderr", "rawOutput", "command", "logicalArgv", "argv", "timeoutMs");
                Object saved = invocation.get("outputFile");
                if (saved != null && !targets.contains(String.valueOf(saved))) targets.add(String.valueOf(saved));
                if (invocation.get("TOOL") != null) node.put("TOOL", invocation.get("TOOL"));
                if (invocation.get("DB") != null) node.put("DB", invocation.get("DB"));
                if (!result.executionSuccess()) {
                    output.put("finalAttempt", number);
                    output.put("status", "ERROR"); output.put("success", false);
                    return ResultStatus.ERROR;
                }
                context.setActionOutput(output);
                runEvidenceCollectors(action, number, context, log, output, invocation);
                boolean passed = evaluateAssertion(action, output, context, log);
                if (output.get("assertion") != null) invocation.put("assertion", new LinkedHashMap<String, Object>((Map<String, Object>) output.get("assertion")));
                if (passed) {
                    output.put("winningAttempt", number);
                    output.put("status", "PASS"); output.put("success", true);
                    return ResultStatus.PASS;
                }
                if (!retryOn.contains("ASSERTION") || number >= maxAttempts) {
                    output.put("finalAttempt", number);
                    output.put("status", "FAIL"); output.put("success", false);
                    return ResultStatus.FAIL;
                }
                invocation.put("retryReason", "ASSERTION");
                waitBeforeRetry(intervalMs);
            } catch (att.exec.ToolExecutionException e) {
                Map<String, Object> evidence = new LinkedHashMap<String, Object>(e.evidence());
                evidence.put("attempt", number);
                evidence.put("category", e.category());
                attempts.add(evidence);
                copy(evidence, output, "exitCode", "stdout", "stderr", "rawOutput", "command", "logicalArgv", "argv", "timeoutMs");
                if (evidence.containsKey("output")) output.put("result", evidence.get("output"));
                Map<String, Object> failedEvidence = ActionExecutionResult.evidence("tool", evidence);
                context.recordActionEvidence(failedEvidence);
                mergeActionEvidence(output, failedEvidence);
                if (evidence.get("outputFile") != null && !targets.contains(String.valueOf(evidence.get("outputFile")))) targets.add(String.valueOf(evidence.get("outputFile")));
                if (evidence.get("TOOL") != null) node.put("TOOL", evidence.get("TOOL"));
                if (evidence.get("DB") != null) node.put("DB", evidence.get("DB"));
                if ("TIMEOUT".equals(e.category()) && retryOn.contains("TIMEOUT") && number < maxAttempts) {
                    evidence.put("retryReason", "TIMEOUT");
                    waitBeforeRetry(intervalMs);
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("Tool action completed without a final attempt: " + action.id());
    }

    private void runEvidenceCollectors(TemplateAction action, int attempt, CaseRuntimeContext context,
                                       CaseExecutionLog log, Map<String, Object> output,
                                       Map<String, Object> invocation) throws Exception {
        if (action.evidence().isEmpty()) return;
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        invocation.put("evidence", evidence);
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
                att.exec.ToolInvocationResult result = templateEngine.executeToolAttempt(collector.call(), context, log,
                        invocationId, collector.timeoutMs(), "", false, true);
                Object status = result.invocation().get("status");
                boolean passed = result.executionSuccess() && "PASS".equalsIgnoreCase(String.valueOf(status));
                record.put("status", passed ? "PASS" : (status == null ? "ERROR" : String.valueOf(status)));
                record.put("success", Boolean.valueOf(passed));
                record.put("invocationId", result.invocationId());
                record.put("result", result.output());
                record.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                evidence.put(collector.id(), record);
                mergeActionEvidence(output, ActionExecutionResult.evidence("collectors", evidence));
                if (!passed) {
                    record.put("error", collectorError(result.invocation(), "Evidence collector did not complete successfully"));
                    throw new EvidenceCollectorFailure(collector, record);
                }
                appendEvidenceLog(log, action, attempt, collector, record);
            } catch (EvidenceCollectorFailure failure) {
                record.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                evidence.put(collector.id(), record);
                appendEvidenceLog(log, action, attempt, collector, record);
                if ("stop".equals(collector.onFailure())) throw failure;
            } catch (Exception error) {
                record.put("status", error instanceof att.exec.ToolExecutionException
                        ? ((att.exec.ToolExecutionException) error).category() : "ERROR");
                record.put("success", false);
                record.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
                record.put("error", collectorError(error));
                evidence.put(collector.id(), record);
                mergeActionEvidence(output, ActionExecutionResult.evidence("collectors", evidence));
                appendEvidenceLog(log, action, attempt, collector, record);
                if ("stop".equals(collector.onFailure())) throw new EvidenceCollectorFailure(collector, record, error);
            }
        }
    }

    private Map<String, Object> collectorError(Object source, String fallback) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        if (source instanceof Map) {
            Map<?, ?> invocation = (Map<?, ?>) source;
            Object category = invocation.get("category");
            Object message = invocation.get("error");
            if (category != null) error.put("category", category);
            if (message != null) error.put("message", message);
            Object exitCode = invocation.get("exitCode");
            if (exitCode != null) error.put("exitCode", exitCode);
        }
        if (error.isEmpty()) error.put("message", fallback);
        return error;
    }

    private Map<String, Object> collectorError(Exception error) {
        if (error instanceof att.exec.ToolExecutionException) {
            att.exec.ToolExecutionException tool = (att.exec.ToolExecutionException) error;
            return collectorError(tool.evidence(), tool.getMessage());
        }
        return collectorError(null, safeMessage(error));
    }

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
            super("Evidence collector '" + collector.id() + "' failed: " + String.valueOf(record.get("status")));
        }
        private EvidenceCollectorFailure(EvidenceCollector collector, Map<String, Object> record, Throwable cause) {
            super("Evidence collector '" + collector.id() + "' failed: " + String.valueOf(record.get("status")), cause);
        }
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
        if ("DB".equals(owner) && !("text".equals(format) || "json".equals(format) || "yaml".equals(format) || "xml".equals(format))) {
            throw new IllegalArgumentException("DB saveAs.format must be text, json, yaml, or xml: " + format);
        }
        return format;
    }

    private boolean console(String path) { return "console".equalsIgnoreCase(path == null ? "" : path.trim()); }

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
    private void mergeActionResult(Map<String, Object> output, ActionExecutionResult result) {
        if (result == null) return;
        mergeActionEvidence(output, result.evidence());
    }

    @SuppressWarnings("unchecked")
    private void mergeActionEvidence(Map<String, Object> output, Map<String, Object> additions) {
        if (additions == null || additions.isEmpty()) return;
        Map<String, Object> evidence = output.get("evidence") instanceof Map
                ? (Map<String, Object>) output.get("evidence") : new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : additions.entrySet()) {
            Object existing = evidence.get(entry.getKey());
            if (existing == null || existing == entry.getValue() || (existing != null && existing.equals(entry.getValue()))) {
                evidence.put(entry.getKey(), entry.getValue());
            } else if (existing instanceof Map && entry.getValue() instanceof Map) {
                List<Object> values = new ArrayList<Object>();
                values.add(existing); values.add(entry.getValue());
                Map<String, Object> grouped = new LinkedHashMap<String, Object>();
                grouped.put("invocations", values);
                evidence.put(entry.getKey(), grouped);
            } else {
                List<Object> values = new ArrayList<Object>();
                values.add(existing); values.add(entry.getValue());
                evidence.put(entry.getKey(), values);
            }
        }
        output.put("evidence", evidence);
    }

    @SuppressWarnings("unchecked")
    private void publishLegacyActionViews(Map<String, Object> node, Map<String, Object> output) {
        Object evidence = output.get("evidence");
        if (!(evidence instanceof Map)) return;
        Object db = ((Map<?, ?>) evidence).get("db");
        if (!(db instanceof Map)) return;
        Map<?, ?> dbEvidence = (Map<?, ?>) db;
        Object helper = dbEvidence.get("db");
        Object invocation = dbEvidence.get("id");
        if (helper == null || invocation == null) return;
        Map<String, Object> calls = new LinkedHashMap<String, Object>();
        calls.put(String.valueOf(invocation), db);
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        legacy.put(String.valueOf(helper), calls);
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
        if (typed.file() == null && typed.field() != null && (typed.field().startsWith("saveAs") || typed.field().equals("sqlFile")))
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
