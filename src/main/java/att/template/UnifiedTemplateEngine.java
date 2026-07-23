/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import att.core.CaseRuntimeContext;
import att.core.CaseExecutionLog;
import att.exec.ToolInvoker;
import att.exec.DbHelperExecutor;
import att.exec.DbInvocationResult;
import att.config.ToolConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders ${context} references and executes #{tool(...)} calls.
 */
public class UnifiedTemplateEngine {
    private static final Pattern VALUE = Pattern.compile("\\$\\{((?:[^}'\"]|'(?:\\\\.|[^'])*'|\"(?:\\\\.|[^\"])*\")+)}");
    private final ToolInvoker toolInvoker;
    private final DbHelperExecutor dbHelperExecutor;
    private final ToolCallParser callParser = new ToolCallParser();
    private final BuiltInProvider builtIns;

    public UnifiedTemplateEngine(ToolInvoker toolInvoker) {
        this(toolInvoker, null, new DefaultBuiltInProvider());
    }

    UnifiedTemplateEngine(ToolInvoker toolInvoker, BuiltInProvider builtIns) {
        this(toolInvoker, null, builtIns);
    }

    public UnifiedTemplateEngine(ToolInvoker toolInvoker, DbHelperExecutor dbHelperExecutor) {
        this(toolInvoker, dbHelperExecutor, new DefaultBuiltInProvider());
    }

    UnifiedTemplateEngine(ToolInvoker toolInvoker, DbHelperExecutor dbHelperExecutor, BuiltInProvider builtIns) {
        this.toolInvoker = toolInvoker;
        this.dbHelperExecutor = dbHelperExecutor;
        this.builtIns = builtIns;
    }

    public String render(String text, CaseRuntimeContext context) throws Exception {
        return render(text, context, null);
    }

    public DbHelperExecutor dbHelperExecutor() { return dbHelperExecutor; }

    /** Returns builtin, db, or tool for the primary call without executing it. */
    public String callKind(String call) {
        ToolCallParser.ParsedCall parsed = callParser.parse(call);
        if (parsed.name().startsWith("db.")) return "db";
        if (builtIns.names().contains(parsed.name().toLowerCase(java.util.Locale.ROOT))) return "builtin";
        ToolConfig tool = toolInvoker == null ? null : toolInvoker.tool(parsed.name());
        return tool != null && tool.callBacked() ? "call-tool" : "tool";
    }

    public String render(String text, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        String afterTools = renderTools(text, context, log);
        return renderValues(afterTools, context);
    }

    /** Renders a non-Case expression scope (for example report filenames or Tool command arguments). */
    public String renderScoped(String text, Map<String, ?> values) throws Exception {
        return renderScoped(text, values, false);
    }

    /** Compatibility scope where unknown values render as empty strings. */
    public String renderScopedLenient(String text, Map<String, ?> values) throws Exception {
        return renderScoped(text, values, true);
    }

    private String renderScoped(String text, Map<String, ?> values, boolean missingAsEmpty) throws Exception {
        String afterCalls = renderScopedCalls(text, values, missingAsEmpty);
        return renderScopedValues(afterCalls, values, missingAsEmpty);
    }

    /** Executes inline calls but intentionally leaves ${...} references for typed assertion evaluation. */
    public String renderCalls(String text, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        return renderTools(text, context, log);
    }

    public String renderValues(String text, CaseRuntimeContext context) {
        return renderValues(text, context, false, false);
    }

    public String renderValuesPreserving(String text, CaseRuntimeContext context) {
        return renderValues(text, context, true, false);
    }

    public String renderValidationValues(String text, CaseRuntimeContext context) {
        return renderValues(text, context, true, true);
    }

    private String renderValues(String text, CaseRuntimeContext context, boolean preserveMissing, boolean validationOnly) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        Matcher matcher = VALUE.matcher(text);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1);
            boolean validationValueAvailable = expression.startsWith("CASE.")
                    && !expression.startsWith("CASE.STAGES.")
                    && !"CASE.outputDirectory".equals(expression);
            Object value;
            if (validationOnly) {
                boolean runtimeDependent = "CASE.outputDirectory".equals(expression) || expression.startsWith("CASE.STAGES.") || expression.startsWith("ACTIONS.")
                        || expression.startsWith("TOOL.") || expression.startsWith("DB.")
                        || expression.equals("output") || expression.startsWith("output.");
                if (validationValueAvailable) value = context.require(expression);
                else if (runtimeDependent) value = null;
                else if (context.contains(expression)) value = context.require(expression);
                else if (!explicitContextRoot(expression)) value = null; // A dynamic unique suffix is checked structurally and completed at runtime.
                else value = context.require(expression);
            }
            else if (preserveMissing) value = context.resolve(expression);
            else value = context.require(expression);
            String replacement = value == null && preserveMissing ? matcher.group(0) : (value == null ? "" : String.valueOf(value));
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private boolean explicitContextRoot(String expression) {
        return expression.equals("CASE") || expression.startsWith("CASE.") || expression.startsWith("CASE[")
                || expression.equals("RUN") || expression.startsWith("RUN.") || expression.startsWith("RUN[")
                || expression.equals("ACTIONS") || expression.startsWith("ACTIONS.") || expression.startsWith("ACTIONS[")
                || expression.equals("TOOL") || expression.startsWith("TOOL.") || expression.startsWith("TOOL[")
                || expression.equals("DB") || expression.startsWith("DB.") || expression.startsWith("DB[")
                || expression.equals("output") || expression.startsWith("output.") || expression.startsWith("output[");
    }

    /** Preserves a complete typed Context/call expression; otherwise returns rendered text. */
    public Object evaluate(String expression, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        String value = expression == null ? "" : expression.trim();
        Matcher exact = VALUE.matcher(value);
        if (exact.matches()) return context.require(exact.group(1));
        if (value.startsWith("#{") && findToolEnd(value, 2) == value.length() - 1) {
            return executeCall(value, context, log, null);
        }
        return render(expression, context, log);
    }

    /** SQL-source scope: Context values and pure built-ins only; no Tool or nested DB execution. */
    public String renderDbSql(String sql, CaseRuntimeContext context) throws Exception {
        return renderScoped(sql, context.values());
    }

    public Object parseRendered(String text, String renderAs) throws Exception {
        return "text".equalsIgnoreCase(renderAs) ? text : toolInvoker.parseOutput(text, renderAs);
    }

    public void validateValueSyntax(String text) {
        if (text == null || text.isEmpty()) return;
        int position = 0;
        while (true) {
            int start = text.indexOf("${", position);
            if (start < 0) return;
            Matcher matcher = VALUE.matcher(text);
            matcher.region(start, text.length());
            if (!matcher.lookingAt()) throw new IllegalArgumentException("Unclosed or invalid context reference in template value");
            String path = matcher.group(1);
            if (path.trim().isEmpty() || !path.equals(path.trim())) throw new IllegalArgumentException("Invalid context reference in template value: ${" + path + "}");
            position = matcher.end();
        }
    }

    /** Returns Context paths using the same value-reference grammar as rendering. */
    public java.util.List<String> parseValuePaths(String text) {
        java.util.List<String> paths = new java.util.ArrayList<String>();
        if (text == null || text.isEmpty()) return paths;
        validateValueSyntax(text);
        Matcher matcher = VALUE.matcher(text);
        while (matcher.find()) paths.add(matcher.group(1));
        return paths;
    }

    /** Returns interpolation paths plus unquoted canonical Runtime Context paths used as complete call arguments. */
    public java.util.List<String> parseContextPaths(String text) {
        java.util.List<String> paths = new java.util.ArrayList<String>(parseValuePaths(text));
        for (ToolCallParser.ParsedCall call : parseCalls(text)) {
            for (ToolCallParser.Argument argument : call.arguments()) {
                String expression = argument.expression().trim();
                if (isExplicitContextPath(expression)) paths.add(expression);
            }
        }
        return paths;
    }

    /** True only for complete, unquoted canonical Case-runtime roots; suffix shorthand still requires ${...}. */
    public boolean isExplicitContextPath(String expression) {
        if (expression == null) return false;
        String value = expression.trim();
        return value.equals(expression) && explicitContextRoot(value);
    }

    /** Finds complete unquoted argument tokens equal to a dedicated-scope path. */
    public boolean referencesBareArgument(String text, String path) {
        if (path == null || path.isEmpty()) return false;
        for (ToolCallParser.ParsedCall call : parseCalls(text)) {
            for (ToolCallParser.Argument argument : call.arguments()) {
                if (path.equals(argument.expression().trim())) return true;
            }
        }
        return false;
    }

    public Object executeCall(String call, CaseRuntimeContext context) throws Exception {
        return executeCall(call, context, null, null);
    }

    public Object executeCall(String call, CaseRuntimeContext context, CaseExecutionLog log, String invocationId) throws Exception {
        return executeCall(call, context, log, invocationId, false);
    }

    public att.exec.ToolInvocationResult executeToolAttempt(String call, CaseRuntimeContext context, CaseExecutionLog log, String invocationId, Long timeoutMs, String saveAs) throws Exception {
        Object result = executeCall(call, context, log, invocationId, true, timeoutMs, saveAs, false);
        return (att.exec.ToolInvocationResult) result;
    }

    public att.exec.ToolInvocationResult executeToolAttempt(String call, CaseRuntimeContext context, CaseExecutionLog log, String invocationId, Long timeoutMs, String saveAs, boolean overwrite) throws Exception {
        Object result = executeCall(call, context, log, invocationId, true, timeoutMs, saveAs, overwrite);
        return (att.exec.ToolInvocationResult) result;
    }

    private Object executeCall(String call, CaseRuntimeContext context, CaseExecutionLog log, String invocationId, boolean attempt) throws Exception {
        return executeCall(call, context, log, invocationId, attempt, null, "", false);
    }

    private Object executeCall(String call, CaseRuntimeContext context, CaseExecutionLog log, String invocationId, boolean attempt, Long timeoutMs, String saveAs, boolean overwrite) throws Exception {
        String body = call.trim();
        if (body.startsWith("#{") && body.endsWith("}")) {
            body = body.substring(2, body.length() - 1);
        }
        ToolCallParser.ParsedCall parsed = callParser.parse("#{" + body + "}");
        if (parsed.name().startsWith("db.")) {
            if (attempt) throw new IllegalArgumentException("A DB query cannot be the primary call of type: tool; use type: db or an ordinary expression");
            return executeDbCall(parsed, context, log, invocationId);
        }
        Map<String, Object> input = resolveArguments(parsed, context, log);
        if (builtIns.names().contains(parsed.name().toLowerCase(java.util.Locale.ROOT))) {
            long started = System.nanoTime();
            Object output = builtIns.invoke(parsed.name(), input);
            return attempt ? builtInAttempt(parsed.name(), invocationId, input, output, context, saveAs, overwrite, started) : output;
        }
        if (toolInvoker == null) throw new IllegalStateException("Configured Tool invocation is unavailable: " + parsed.name());
        ToolConfig configured = toolInvoker.tool(parsed.name());
        if (configured != null && configured.callBacked()) {
            if (timeoutMs != null) throw new IllegalArgumentException("timeoutMs is process-only and cannot be set on call-backed Tool " + configured.key());
            return executeCallBackedTool(configured, input, context, log, invocationId, attempt);
        }
        if (log == null) {
            throw new IllegalStateException("Case execution log is required for process Tool invocation");
        }
        att.exec.ToolInvocationResult result = attempt
                ? toolInvoker.invokeAttempt(invocationId, parsed.name(), input, context, log, timeoutMs, saveAs, overwrite)
                : toolInvoker.invokeAttempt(invocationId, parsed.name(), input, context, log, null, "", false);
        return attempt ? result : result.output();
    }

    private Object executeCallBackedTool(ToolConfig tool, Map<String, Object> supplied,
                                         CaseRuntimeContext context, CaseExecutionLog log,
                                         String requestedId, boolean attempt) throws Exception {
        Map<String, Object> input = toolInvoker.prepareInput(tool.key(), supplied);
        ToolCallParser.ParsedCall target = callParser.parse(tool.call());
        boolean write = target.name().startsWith("db.") && target.name().endsWith(".update");
        if (write && !attempt) {
            throw new IllegalArgumentException("A call-backed DB update Tool may only be the primary call of a type: tool Action: " + tool.key());
        }
        String id = requestedId == null || requestedId.trim().isEmpty()
                ? context.nextInvocationId(tool.key()) : requestedId;
        long started = System.nanoTime();
        String dbInstance = target.name().startsWith("db.") ? target.name().split("\\.", -1)[1] : "";
        boolean cached = tool.caseCached() || tool.dbCached();
        String cacheKey = cached ? callToolCacheKey(tool.key(), input) : "";
        boolean cacheHit = tool.caseCached() ? context.hasCallToolCache(cacheKey)
                : tool.dbCached() && dbHelperExecutor.hasCached(dbInstance, cacheKey);
        Object output;
        boolean success = true;
        Map<String, Object> dbEvidence = new LinkedHashMap<String, Object>();
        if (cacheHit) {
            output = tool.caseCached() ? context.callToolCache(cacheKey)
                    : dbHelperExecutor.cached(dbInstance, cacheKey);
        } else if (target.name().startsWith("db.")) {
            CallBackedDbResult result = executeCallBackedDb(target, input, context, log);
            output = result.output;
            success = result.success;
            Map<String, Object> calls = new LinkedHashMap<String, Object>();
            calls.put(result.invocationId, result.evidence);
            dbEvidence.put(result.instance, calls);
        } else {
            output = builtIns.invoke(target.name(), resolveDefinitionArguments(target, input));
        }
        if (success && !cacheHit) {
            if (tool.caseCached()) context.cacheCallTool(cacheKey, output);
            else if (tool.dbCached()) dbHelperExecutor.cache(dbInstance, cacheKey, output);
        }

        Map<String, Object> toolEvidence = new LinkedHashMap<String, Object>();
        toolEvidence.put("name", tool.key());
        toolEvidence.put("implementation", "call");
        toolEvidence.put("input", input);
        toolEvidence.put("output", output);
        toolEvidence.put("status", success ? "PASS" : "ERROR");
        toolEvidence.put("durationMs", java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
        if (tool.grouped()) {
            toolEvidence.put("groupId", tool.groupId());
            toolEvidence.put("toolKey", tool.localKey());
        }
        if (!dbEvidence.isEmpty()) toolEvidence.put("DB", dbEvidence);
        if (cached) {
            Map<String, Object> cache = new LinkedHashMap<String, Object>();
            cache.put("scope", tool.cache());
            cache.put("key", cacheKey);
            cache.put("hit", Boolean.valueOf(cacheHit));
            toolEvidence.put("cache", cache);
        }

        Map<String, Object> toolNode = new LinkedHashMap<String, Object>();
        if (tool.grouped()) {
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put(tool.localKey(), toolEvidence);
            toolNode.put(tool.groupId(), group);
        } else toolNode.put(tool.key(), toolEvidence);

        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("id", id);
        invocation.put("type", "tool");
        invocation.put("name", tool.key());
        invocation.put("implementation", "call");
        invocation.put("status", success ? "PASS" : "ERROR");
        invocation.put("durationMs", toolEvidence.get("durationMs"));
        invocation.put("input", input);
        invocation.put("output", output);
        invocation.put("TOOL", toolNode);
        if (!dbEvidence.isEmpty()) invocation.put("DB", dbEvidence);
        att.exec.ToolInvocationResult result = new att.exec.ToolInvocationResult(tool.key(), id, output, invocation, success);
        if (!attempt && !success) throw new IllegalStateException("DB call failed through Tool " + tool.key());
        return attempt ? result : output;
    }

    private String callToolCacheKey(String tool, Map<String, Object> input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((tool + "\n" + canonicalCacheValue(input))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String canonicalCacheValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) {
            java.util.List<Map.Entry<?, ?>> entries = new java.util.ArrayList<Map.Entry<?, ?>>(((Map<?, ?>) value).entrySet());
            java.util.Collections.sort(entries, new java.util.Comparator<Map.Entry<?, ?>>() {
                @Override public int compare(Map.Entry<?, ?> left, Map.Entry<?, ?> right) {
                    return canonicalCacheValue(left.getKey()).compareTo(canonicalCacheValue(right.getKey()));
                }
            });
            StringBuilder result = new StringBuilder("map{");
            for (Map.Entry<?, ?> entry : entries) result.append(canonicalCacheValue(entry.getKey())).append(':')
                    .append(canonicalCacheValue(entry.getValue())).append(';');
            return result.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder result = new StringBuilder("list[");
            for (Object item : (Iterable<?>) value) result.append(canonicalCacheValue(item)).append(';');
            return result.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder result = new StringBuilder("array[");
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) result.append(canonicalCacheValue(java.lang.reflect.Array.get(value, index))).append(';');
            return result.append(']').toString();
        }
        return value.getClass().getName() + ':' + String.valueOf(value).length() + ':' + String.valueOf(value);
    }

    private CallBackedDbResult executeCallBackedDb(ToolCallParser.ParsedCall call, Map<String, Object> input,
                                                   CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        if (dbHelperExecutor == null) throw new IllegalStateException("DB invocation is unavailable: " + call.name());
        String[] parts = call.name().split("\\.", -1);
        if (parts.length != 3 || !"db".equals(parts[0]) || parts[1].isEmpty()
                || !("query".equals(parts[2]) || "scalar".equals(parts[2]) || "update".equals(parts[2]))) {
            throw new IllegalArgumentException("call-backed Tool DB target must be db.<instance>.query|scalar|update: " + call.name());
        }
        Map<String, Object> arguments = resolveDefinitionDbArguments(call, input);
        boolean hasSql = arguments.containsKey("sql");
        boolean hasFile = arguments.containsKey("sqlFile");
        if (hasSql == hasFile) throw new IllegalArgumentException(call.name() + " requires exactly one of sql or sqlFile");
        String source = "inline";
        String sql;
        if (hasFile) {
            String configured = String.valueOf(arguments.get("sqlFile"));
            java.nio.file.Path file = dbHelperExecutor.resolveSqlFile(configured);
            sql = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            source = configured;
            sql = renderScoped(sql, definitionScope(input));
        } else sql = String.valueOf(arguments.get("sql"));
        Object paramsValue = arguments.get("params");
        if (paramsValue != null && !(paramsValue instanceof java.util.List)) {
            throw new IllegalArgumentException(call.name() + ".params must resolve to a List");
        }
        java.util.List<?> params = paramsValue == null ? java.util.Collections.emptyList() : (java.util.List<?>) paramsValue;
        String invocationId = context.nextDbInvocationId(parts[1]);
        String operation = "update".equals(parts[2]) ? "update" : "query";
        DbInvocationResult result = dbHelperExecutor.execute(parts[1], operation, sql, source, params, invocationId);
        context.recordDbInvocation(parts[1], invocationId, result.evidence());
        if (log != null) try { log.append("DB " + parts[1] + " " + invocationId, result.evidence()); } catch (Exception ignored) { }
        Object output = result.result();
        if (result.success() && "scalar".equals(parts[2])) output = scalar(parts[1], result.result());
        return new CallBackedDbResult(parts[1], invocationId, output, result.evidence(), result.success());
    }

    private Object scalar(String instance, Object result) {
        Map<?, ?> query = (Map<?, ?>) result;
        Object rowsValue = query.get("rows");
        if (!(rowsValue instanceof java.util.List) || ((java.util.List<?>) rowsValue).size() != 1) {
            throw new IllegalStateException("db." + instance + ".scalar requires exactly one row");
        }
        Object rowValue = ((java.util.List<?>) rowsValue).get(0);
        if (!(rowValue instanceof Map) || ((Map<?, ?>) rowValue).size() != 1) {
            throw new IllegalStateException("db." + instance + ".scalar requires exactly one column");
        }
        return ((Map<?, ?>) rowValue).values().iterator().next();
    }

    private Map<String, Object> resolveDefinitionDbArguments(ToolCallParser.ParsedCall call,
                                                             Map<String, Object> input) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (ToolCallParser.Argument argument : call.arguments()) {
            if (argument.positional()) throw new IllegalArgumentException(call.name() + " requires named arguments");
            if (!("sql".equals(argument.key()) || "sqlFile".equals(argument.key()) || "params".equals(argument.key()))) {
                throw new IllegalArgumentException("Unknown DB call argument: " + argument.key());
            }
            if (result.containsKey(argument.key())) throw new IllegalArgumentException("Duplicate DB call argument: " + argument.key());
            String expression = argument.expression().trim();
            Object value;
            if ("params".equals(argument.key()) && expression.startsWith("[") && expression.endsWith("]")) {
                java.util.List<Object> values = new java.util.ArrayList<Object>();
                for (String item : callParser.listItems(expression)) values.add(resolveDefinitionValue(item, input));
                value = values;
            } else value = resolveDefinitionValue(expression, input);
            result.put(argument.key(), value);
        }
        return result;
    }

    private Map<String, Object> resolveDefinitionArguments(ToolCallParser.ParsedCall call,
                                                           Map<String, Object> input) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (ToolCallParser.Argument argument : call.arguments()) {
            Object value = resolveDefinitionValue(argument.expression().trim(), input);
            putNested(result, argument.key(), value == null ? "" : value);
        }
        return result;
    }

    private Object resolveDefinitionValue(String expression, Map<String, Object> input) throws Exception {
        expression = expression == null ? "" : expression.trim();
        Map<String, Object> scope = definitionScope(input);
        Matcher exact = VALUE.matcher(expression);
        if (exact.matches()) return requireScoped(scope, exact.group(1), false);
        if (hasScopedPath(scope, expression) || explicitScopedRoot(expression)) {
            return requireScoped(scope, expression, false);
        }
        if (expression.startsWith("#{") && findToolEnd(expression, 2) == expression.length() - 1) {
            return executeScopedCall(expression, scope, false);
        }
        return callParser.literal(renderScoped(expression, scope, false));
    }

    private Map<String, Object> definitionScope(Map<String, Object> input) {
        Map<String, Object> scope = new LinkedHashMap<String, Object>(input);
        scope.put("input", new LinkedHashMap<String, Object>(input));
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("input", new LinkedHashMap<String, Object>(input));
        scope.put("TOOL", tool);
        return scope;
    }

    private static final class CallBackedDbResult {
        private final String instance, invocationId;
        private final Object output;
        private final Map<String, Object> evidence;
        private final boolean success;
        private CallBackedDbResult(String instance, String invocationId, Object output,
                                   Map<String, Object> evidence, boolean success) {
            this.instance = instance; this.invocationId = invocationId; this.output = output;
            this.evidence = evidence; this.success = success;
        }
    }

    private Object executeDbCall(ToolCallParser.ParsedCall call, CaseRuntimeContext context,
                                 CaseExecutionLog log, String requestedId) throws Exception {
        if (dbHelperExecutor == null) throw new IllegalStateException("DB expression invocation is unavailable: " + call.name());
        String[] parts = call.name().split("\\.", -1);
        if (parts.length != 3 || !"db".equals(parts[0]) || parts[1].isEmpty()
                || !("query".equals(parts[2]) || "scalar".equals(parts[2]))) {
            throw new IllegalArgumentException("DB expression must be db.<instance>.query(...) or db.<instance>.scalar(...): " + call.name());
        }
        Map<String, Object> input = resolveDbArguments(call, context, log);
        boolean hasSql = input.containsKey("sql");
        boolean hasFile = input.containsKey("sqlFile");
        if (hasSql == hasFile) throw new IllegalArgumentException(call.name() + " requires exactly one of sql or sqlFile");
        String source = "inline";
        String sql;
        if (hasFile) {
            String configured = String.valueOf(input.get("sqlFile"));
            java.nio.file.Path file = dbHelperExecutor.resolveSqlFile(configured);
            sql = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            source = configured;
        } else sql = String.valueOf(input.get("sql"));
        sql = renderDbSql(sql, context);
        Object paramsValue = input.get("params");
        if (paramsValue != null && !(paramsValue instanceof java.util.List)) {
            throw new IllegalArgumentException(call.name() + ".params must resolve to a List");
        }
        java.util.List<?> params = paramsValue == null ? java.util.Collections.emptyList() : (java.util.List<?>) paramsValue;
        String id = requestedId == null || requestedId.trim().isEmpty()
                ? context.nextDbInvocationId(parts[1]) : requestedId;
        DbInvocationResult result = dbHelperExecutor.execute(parts[1], "query", sql, source, params, id);
        context.recordDbInvocation(parts[1], id, result.evidence());
        if (log != null) try { log.append("DB " + parts[1] + " " + id, result.evidence()); } catch (Exception ignored) { }
        if (!result.success()) {
            Object error = result.result() instanceof Map ? ((Map<?, ?>) result.result()).get("error") : null;
            throw new IllegalStateException("DB query failed for " + parts[1] + ": " + String.valueOf(error));
        }
        if ("query".equals(parts[2])) return result.result();
        return scalar(parts[1], result.result());
    }

    private Map<String, Object> resolveDbArguments(ToolCallParser.ParsedCall call, CaseRuntimeContext context,
                                                   CaseExecutionLog log) throws Exception {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        for (ToolCallParser.Argument argument : call.arguments()) {
            if (argument.positional()) throw new IllegalArgumentException(call.name() + " requires named arguments");
            String key = argument.key();
            if (!("sql".equals(key) || "sqlFile".equals(key) || "params".equals(key))) {
                throw new IllegalArgumentException("Unknown DB expression argument: " + key);
            }
            if (input.containsKey(key)) throw new IllegalArgumentException("Duplicate DB expression argument: " + key);
            String expression = argument.expression().trim();
            Object value;
            if ("params".equals(key) && expression.startsWith("[") && expression.endsWith("]")) {
                java.util.List<Object> items = new java.util.ArrayList<Object>();
                for (String item : callParser.listItems(expression)) items.add(resolveDbValue(item, context, log));
                value = items;
            } else value = resolveDbValue(expression, context, log);
            input.put(key, value);
        }
        return input;
    }

    private Object resolveDbValue(String expression, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        Matcher exact = VALUE.matcher(expression);
        if (exact.matches()) return context.require(exact.group(1));
        if (isExplicitContextPath(expression)) return context.require(expression);
        if (expression.startsWith("#{") && findToolEnd(expression, 2) == expression.length() - 1) {
            return executeCall(expression, context, log, null);
        }
        return callParser.literal(expression);
    }

    private att.exec.ToolInvocationResult builtInAttempt(String name, String invocationId, Map<String, Object> input,
                                                         Object output, CaseRuntimeContext context, String saveAs,
                                                         boolean overwrite, long started) throws Exception {
        String id = invocationId == null || invocationId.trim().isEmpty() ? context.nextInvocationId(name) : invocationId;
        String rawOutput = output == null ? "" : String.valueOf(output);
        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("id", id);
        invocation.put("type", "builtin");
        invocation.put("name", name);
        invocation.put("status", "PASS");
        invocation.put("durationMs", java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
        invocation.put("input", input);
        invocation.put("output", output);
        invocation.put("rawOutput", rawOutput);
        invocation.put("exitCode", 0);
        if (saveAs != null && !saveAs.trim().isEmpty()) {
            java.nio.file.Path directory = context.caseLogDirectory();
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Path outputFile = directory.resolve(att.core.IdentifierValidator.relativePath(saveAs, "tool saveAs")).normalize();
            if (!outputFile.startsWith(directory.normalize())) throw new IllegalArgumentException("Tool saveAs must stay under case log directory: " + saveAs);
            java.nio.file.Files.createDirectories(outputFile.getParent());
            byte[] bytes = rawOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (overwrite) java.nio.file.Files.write(outputFile, bytes);
            else java.nio.file.Files.write(outputFile, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            invocation.put("outputFile", outputFile.toString());
        }
        return new att.exec.ToolInvocationResult(name, id, output, invocation);
    }

    private String renderTools(String text, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        StringBuilder output = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf("#{", index);
            if (start < 0) {
                output.append(text.substring(index));
                break;
            }
            output.append(text.substring(index, start));
            int end = findToolEnd(text, start + 2);
            if (end < 0) {
                throw new IllegalArgumentException("Unclosed tool call: " + text.substring(start));
            }
            String expression = text.substring(start, end + 1);
            Object value = executeCall(expression, context, log, null);
            ToolCallParser.ParsedCall parsed = callParser.parse(expression);
            if (parsed.name().startsWith("db.") && parsed.name().endsWith(".query") && value instanceof Map) {
                throw new IllegalArgumentException("A typed DB query result cannot be interpolated into text; use an exact assign expression or type: db Action");
            }
            output.append(value == null ? "" : String.valueOf(value));
            index = end + 1;
        }
        return output.toString();
    }

    /** Parses every inline call exactly as runtime rendering would, without invoking it. */
    public java.util.List<ToolCallParser.ParsedCall> parseCalls(String text) {
        java.util.List<ToolCallParser.ParsedCall> calls = new java.util.ArrayList<ToolCallParser.ParsedCall>();
        if (text == null || text.isEmpty()) return calls;
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf("#{", index);
            if (start < 0) break;
            int end = findToolEnd(text, start + 2);
            if (end < 0) throw new IllegalArgumentException("Unclosed tool/function call: " + text.substring(start));
            ToolCallParser.ParsedCall call = callParser.parse(text.substring(start, end + 1));
            calls.add(call);
            for (ToolCallParser.Argument argument : call.arguments()) calls.addAll(parseCalls(argument.expression()));
            index = end + 1;
        }
        return calls;
    }

    public boolean isBuiltIn(String name) {
        return name != null && builtIns.names().contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Validates a built-in call's name and argument shape without evaluating its values. */
    public void validateBuiltInCall(ToolCallParser.ParsedCall call) {
        if (!isBuiltIn(call.name())) throw new IllegalArgumentException("Configured Tool call is not available in this expression scope: " + call.name());
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (ToolCallParser.Argument argument : call.arguments()) putNested(arguments, argument.key(), "<expression>");
        if (builtIns instanceof DefaultBuiltInProvider) ((DefaultBuiltInProvider) builtIns).validateInvocation(call.name(), arguments);
    }

    /** Replaces each complete inline call with a neutral operand for assertion grammar validation. */
    public String maskCalls(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder output = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf("#{", index);
            if (start < 0) { output.append(text.substring(index)); break; }
            output.append(text.substring(index, start));
            int end = findToolEnd(text, start + 2);
            if (end < 0) throw new IllegalArgumentException("Unclosed tool/function call: " + text.substring(start));
            callParser.parse(text.substring(start, end + 1));
            output.append('0');
            index = end + 1;
        }
        return output.toString();
    }

    private int findToolEnd(String text, int bodyStart) {
        int depth = 1;
        char quote = 0;
        for (int i = bodyStart; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0;
                continue;
            }
            if ((ch == '\'' || ch == '"') && (i == 0 || text.charAt(i - 1) != '\\')) {
                quote = ch;
                continue;
            }
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private Map<String, Object> resolveArguments(ToolCallParser.ParsedCall call, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        for (ToolCallParser.Argument argument : call.arguments()) {
            String expression = argument.expression().trim();
            Matcher exact = VALUE.matcher(expression);
            Object value;
            if (exact.matches()) value = context.require(exact.group(1));
            else if (isExplicitContextPath(expression)) value = context.require(expression);
            else if (expression.startsWith("#{") && findToolEnd(expression, 2) == expression.length() - 1) value = executeCall(expression, context, log, null);
            else value = callParser.literal(render(expression, context, log));
            putNested(input, argument.key(), value == null ? "" : value);
        }
        return input;
    }

    private String renderScopedCalls(String text, Map<String, ?> values, boolean missingAsEmpty) throws Exception {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder output = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf("#{", index);
            if (start < 0) { output.append(text.substring(index)); break; }
            output.append(text.substring(index, start));
            int end = findToolEnd(text, start + 2);
            if (end < 0) throw new IllegalArgumentException("Unclosed function call: " + text.substring(start));
            Object value = executeScopedCall(text.substring(start, end + 1), values, missingAsEmpty);
            output.append(value == null ? "" : String.valueOf(value));
            index = end + 1;
        }
        return output.toString();
    }

    private Map<String, Object> resolveScopedArguments(ToolCallParser.ParsedCall call, Map<String, ?> values, boolean missingAsEmpty) throws Exception {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        for (ToolCallParser.Argument argument : call.arguments()) {
            String expression = argument.expression().trim();
            Matcher exact = VALUE.matcher(expression);
            Object value;
            if (exact.matches()) value = requireScoped(values, exact.group(1), missingAsEmpty);
            else if (hasScopedPath(values, expression)) value = requireScoped(values, expression, missingAsEmpty);
            else if (explicitScopedRoot(expression)) value = requireScoped(values, expression, missingAsEmpty);
            else if (expression.startsWith("#{") && findToolEnd(expression, 2) == expression.length() - 1) {
                value = executeScopedCall(expression, values, missingAsEmpty);
            } else value = callParser.literal(renderScoped(expression, values, missingAsEmpty));
            putNested(input, argument.key(), value == null ? "" : value);
        }
        return input;
    }

    private Object executeScopedCall(String expression, Map<String, ?> values, boolean missingAsEmpty) throws Exception {
        ToolCallParser.ParsedCall call = callParser.parse(expression);
        String normalized = call.name().toLowerCase(java.util.Locale.ROOT);
        if (!builtIns.names().contains(normalized)) {
            throw new IllegalArgumentException("Configured Tool call is not available in this expression scope: " + call.name());
        }
        return builtIns.invoke(call.name(), resolveScopedArguments(call, values, missingAsEmpty));
    }

    private String renderScopedValues(String text, Map<String, ?> values, boolean missingAsEmpty) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        Matcher matcher = VALUE.matcher(text);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            Object value = requireScoped(values, matcher.group(1), missingAsEmpty);
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    @SuppressWarnings("unchecked")
    private Object requireScoped(Map<String, ?> values, String path, boolean missingAsEmpty) {
        if (values.containsKey(path)) return values.get(path);
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(part)) {
                if (missingAsEmpty) return null;
                throw new IllegalArgumentException("Unknown expression value in this scope: ${" + path + "}");
            }
            current = ((Map<String, ?>) current).get(part);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private boolean hasScopedPath(Map<String, ?> values, String path) {
        if (path == null || path.isEmpty() || quoted(path)) return false;
        if (values.containsKey(path)) return true;
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(part)) return false;
            current = ((Map<String, ?>) current).get(part);
        }
        return true;
    }

    private boolean explicitScopedRoot(String expression) {
        return expression != null && (expression.startsWith("input.") || expression.startsWith("TOOL.input."));
    }

    private boolean quoted(String expression) {
        return expression.length() >= 2 && ((expression.startsWith("'") && expression.endsWith("'"))
                || (expression.startsWith("\"") && expression.endsWith("\"")));
    }

    @SuppressWarnings("unchecked")
    private void putNested(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }

}
