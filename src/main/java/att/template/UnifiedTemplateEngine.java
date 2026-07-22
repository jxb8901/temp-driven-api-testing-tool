/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import att.core.CaseRuntimeContext;
import att.core.CaseExecutionLog;
import att.exec.ToolInvoker;

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
    private final ToolCallParser callParser = new ToolCallParser();
    private final BuiltInProvider builtIns;

    public UnifiedTemplateEngine(ToolInvoker toolInvoker) {
        this(toolInvoker, new DefaultBuiltInProvider());
    }

    UnifiedTemplateEngine(ToolInvoker toolInvoker, BuiltInProvider builtIns) {
        this.toolInvoker = toolInvoker;
        this.builtIns = builtIns;
    }

    public String render(String text, CaseRuntimeContext context) throws Exception {
        return render(text, context, null);
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
                        || expression.startsWith("TOOL.") || expression.equals("output") || expression.startsWith("output.");
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
                || expression.equals("output") || expression.startsWith("output.") || expression.startsWith("output[");
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
        Map<String, Object> input = resolveArguments(parsed, context, log);
        if (builtIns.names().contains(parsed.name().toLowerCase(java.util.Locale.ROOT))) {
            long started = System.nanoTime();
            Object output = builtIns.invoke(parsed.name(), input);
            return attempt ? builtInAttempt(parsed.name(), invocationId, input, output, context, saveAs, overwrite, started) : output;
        }
        if (toolInvoker == null) throw new IllegalStateException("Configured Tool invocation is unavailable: " + parsed.name());
        if (log == null) {
            throw new IllegalStateException("Case execution log is required for tool invocation");
        }
        att.exec.ToolInvocationResult result = attempt
                ? toolInvoker.invokeAttempt(invocationId, parsed.name(), input, context, log, timeoutMs, saveAs, overwrite)
                : toolInvoker.invokeAttempt(invocationId, parsed.name(), input, context, log, null, "", false);
        return attempt ? result : result.output();
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
            Object value = executeCall(text.substring(start, end + 1), context, log, null);
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
