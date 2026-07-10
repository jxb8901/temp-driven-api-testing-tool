/*
 * Author: Jeffrey + ChatGPT
 */
package com.company.apitest.template;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the shared V2 #{tool(...)} grammar for validation and execution. */
public final class ToolCallParser {
    public ParsedCall parse(String expression) {
        String body = expression == null ? "" : expression.trim();
        if (body.startsWith("#{") && body.endsWith("}")) body = body.substring(2, body.length() - 1).trim();
        int open = body.indexOf('(');
        if (open < 1 || !body.endsWith(")")) throw new IllegalArgumentException("Invalid V2 tool/function call: " + expression);
        String name = body.substring(0, open).trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Tool/function name is required: " + expression);
        String arguments = body.substring(open + 1, body.length() - 1).trim();
        List<Argument> parsed = new ArrayList<Argument>();
        int positional = 0;
        if (!arguments.isEmpty()) for (String item : splitArguments(arguments)) {
            int equals = topLevelEquals(item);
            String key = equals > 0 ? item.substring(0, equals).trim() : "arg" + positional++;
            String value = equals > 0 ? item.substring(equals + 1).trim() : item.trim();
            if (key.isEmpty() || value.isEmpty()) throw new IllegalArgumentException("Invalid tool/function argument: " + item);
            parsed.add(new Argument(key, value, equals <= 0));
        }
        return new ParsedCall(name, parsed);
    }

    /** Converts an already context-rendered token to its V2 scalar literal type. */
    public Object literal(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'")))) {
            return unescape(text.substring(1, text.length() - 1), text.charAt(0));
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) return Boolean.valueOf(text);
        try { return new BigDecimal(text); } catch (NumberFormatException ignored) { return value == null ? "" : value; }
    }

    private List<String> splitArguments(String text) {
        List<String> result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        int round = 0, curly = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) { current.append(ch); escaped = false; continue; }
            if (quote != 0) {
                current.append(ch);
                if (ch == '\\') escaped = true;
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '\"') { quote = ch; current.append(ch); continue; }
            if (ch == '(') round++;
            else if (ch == ')') round--;
            else if (ch == '{') curly++;
            else if (ch == '}') curly--;
            if (round < 0 || curly < 0) throw new IllegalArgumentException("Unbalanced tool/function arguments: " + text);
            if (ch == ',' && round == 0 && curly == 0) { result.add(current.toString()); current.setLength(0); }
            else current.append(ch);
        }
        if (quote != 0 || round != 0 || curly != 0) throw new IllegalArgumentException("Unclosed tool/function argument: " + text);
        result.add(current.toString());
        return result;
    }

    private int topLevelEquals(String text) {
        char quote = 0; int round = 0, curly = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) { if (ch == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0; continue; }
            if (ch == '\'' || ch == '\"') quote = ch;
            else if (ch == '(') round++;
            else if (ch == ')') round--;
            else if (ch == '{') curly++;
            else if (ch == '}') curly--;
            else if (ch == '=' && round == 0 && curly == 0) return i;
        }
        return -1;
    }

    private String unescape(String text, char quote) {
        return text.replace("\\" + quote, String.valueOf(quote)).replace("\\\\", "\\");
    }

    public static final class ParsedCall {
        private final String name;
        private final List<Argument> arguments;
        ParsedCall(String name, List<Argument> arguments) { this.name = name; this.arguments = new ArrayList<Argument>(arguments); }
        public String name() { return name; }
        public List<Argument> arguments() { return Collections.unmodifiableList(arguments); }
    }

    public static final class Argument {
        private final String key, expression;
        private final boolean positional;
        Argument(String key, String expression, boolean positional) { this.key = key; this.expression = expression; this.positional = positional; }
        public String key() { return key; }
        public String expression() { return expression; }
        public boolean positional() { return positional; }
    }
}
