/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import com.company.apitest.core.CaseRuntimeContext;
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

    public UnifiedTemplateEngine(ToolInvoker toolInvoker) {
        this.toolInvoker = toolInvoker;
    }

    public String render(String text, CaseRuntimeContext context) throws Exception {
        String afterTools = renderTools(text, context);
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
        String body = call.trim();
        if (body.startsWith("#{") && body.endsWith("}")) {
            body = body.substring(2, body.length() - 1);
        }
        ToolCall parsed = parseCall(body, context);
        return toolInvoker.invoke(parsed.name, parsed.input, context).output();
    }

    private String renderTools(String text, CaseRuntimeContext context) throws Exception {
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
            Object value = executeCall(text.substring(start, end + 1), context);
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

    private ToolCall parseCall(String body, CaseRuntimeContext context) {
        int open = body.indexOf('(');
        if (open < 0) {
            return new ToolCall(body.trim(), new LinkedHashMap<String, Object>());
        }
        String name = body.substring(0, open).trim();
        String args = body.substring(open + 1, body.lastIndexOf(')')).trim();
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        if (!args.isEmpty()) {
            // Dotted argument names become nested YAML input, for example txn.ref -> {txn: {ref: ...}}.
            for (String pair : args.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    putNested(input, pair.substring(0, eq).trim(), renderValues(pair.substring(eq + 1).trim(), context));
                }
            }
        }
        return new ToolCall(name, input);
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

    private static class ToolCall {
        private final String name;
        private final Map<String, Object> input;

        private ToolCall(String name, Map<String, Object> input) {
            this.name = name;
            this.input = input;
        }
    }
}
