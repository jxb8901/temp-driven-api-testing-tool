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

    public String renderValues(String text, CaseRuntimeContext context) {
        Matcher matcher = VALUE.matcher(text);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            Object value = context.resolve(matcher.group(1));
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(output);
        return output.toString();
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
        Map<String, Object> input = resolveArguments(parsed, context);
        if (builtIns.names().contains(parsed.name().toLowerCase(java.util.Locale.ROOT))) return builtIns.invoke(parsed.name(), input);
        if (log == null) {
            throw new IllegalStateException("Case execution log is required for tool invocation");
        }
        att.exec.ToolInvocationResult result = attempt ? toolInvoker.invokeAttempt(invocationId, parsed.name(), input, context, log, timeoutMs, saveAs, overwrite) : toolInvoker.invoke(invocationId, parsed.name(), input, context, log);
        return attempt ? result : result.output();
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

    private int findToolEnd(String text, int bodyStart) {
        // Tool calls may contain ${...} arguments, so a plain "next }" search would stop too early.
        int nestedValueDepth = 0;
        for (int i = bodyStart; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                nestedValueDepth++;
                i++;
                continue;
            }
            if (ch == '}' && nestedValueDepth > 0) {
                nestedValueDepth--;
                continue;
            }
            if (ch == '}' && nestedValueDepth == 0) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> resolveArguments(ToolCallParser.ParsedCall call, CaseRuntimeContext context) {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        for (ToolCallParser.Argument argument : call.arguments()) {
            String expression = argument.expression().trim();
            Matcher exact = VALUE.matcher(expression);
            Object value = exact.matches() ? context.resolve(exact.group(1)) : callParser.literal(renderValues(expression, context));
            putNested(input, argument.key(), value == null ? "" : value);
        }
        return input;
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
