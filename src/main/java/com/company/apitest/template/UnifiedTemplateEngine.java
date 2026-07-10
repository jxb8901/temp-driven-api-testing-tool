/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import com.company.apitest.core.CaseRuntimeContext;
import com.company.apitest.core.CaseExecutionLog;
import com.company.apitest.exec.ToolInvoker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders ${context} references and executes #{tool(...)} calls.
 */
public class UnifiedTemplateEngine {
    private static final Pattern VALUE = Pattern.compile("\\$\\{([^}]+)}");
    private final ToolInvoker toolInvoker;
    private final ToolCallParser callParser = new ToolCallParser();

    public UnifiedTemplateEngine(ToolInvoker toolInvoker) {
        this.toolInvoker = toolInvoker;
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
        String body = call.trim();
        if (body.startsWith("#{") && body.endsWith("}")) {
            body = body.substring(2, body.length() - 1);
        }
        ToolCallParser.ParsedCall parsed = callParser.parse("#{" + body + "}");
        Map<String, Object> input = resolveArguments(parsed, context);
        Object builtIn = builtIn(parsed.name(), input);
        if (builtIn != null) return builtIn;
        if (log == null) {
            throw new IllegalStateException("Case execution log is required for tool invocation");
        }
        return toolInvoker.invoke(invocationId, parsed.name(), input, context, log).output();
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

    /** Built-ins keep ordinary transforms in templates without creating a shell tool. */
    private Object builtIn(String name, Map<String, Object> input) {
        String value = value(input, "value", "arg0");
        if ("upper".equalsIgnoreCase(name)) return value.toUpperCase(java.util.Locale.ROOT);
        if ("lower".equalsIgnoreCase(name)) return value.toLowerCase(java.util.Locale.ROOT);
        if ("trim".equalsIgnoreCase(name)) return value.trim();
        if ("string".equalsIgnoreCase(name)) return value;
        if ("number".equalsIgnoreCase(name)) {
            try { return new java.math.BigDecimal(value.trim()).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("number() requires a Number literal: " + value); }
        }
        if ("boolean".equalsIgnoreCase(name)) {
            if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) return "true";
            if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) return "false";
            throw new IllegalArgumentException("boolean() requires true/false, yes/no, or 1/0: " + value);
        }
        if ("length".equalsIgnoreCase(name)) return String.valueOf(value.length());
        if ("concat".equalsIgnoreCase(name)) {
            StringBuilder result = new StringBuilder();
            for (Object item : input.values()) result.append(item == null ? "" : item);
            return result.toString();
        }
        if ("coalesce".equalsIgnoreCase(name)) {
            for (Object item : input.values()) if (item != null && !String.valueOf(item).trim().isEmpty()) return item;
            return "";
        }
        return null;
    }

    private String value(Map<String, Object> input, String preferred, String fallback) {
        Object item = input.containsKey(preferred) ? input.get(preferred) : input.get(fallback);
        return item == null ? "" : String.valueOf(item);
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
