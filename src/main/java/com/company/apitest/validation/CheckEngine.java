/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.validation;

import com.company.apitest.core.CaseRuntimeContext;
import com.company.apitest.core.ResultStatus;
import com.company.apitest.core.ValidationResult;
import com.company.apitest.exec.ToolInvocationResult;
import com.company.apitest.template.UnifiedTemplateEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes V1.1 check actions and evaluates their SQL-like expected expressions.
 */
public class CheckEngine {
    private final UnifiedTemplateEngine templateEngine;

    public CheckEngine(UnifiedTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public List<ValidationResult> execute(String source, List<CheckAction> actions, CaseRuntimeContext context) {
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        for (CheckAction action : actions) {
            try {
                Object output = templateEngine.executeCall(action.call(), context);
                context.put("TOOL.output", output);
                String rendered = templateEngine.renderValues(action.expected(), context);
                boolean passed = evaluate(rendered);
                results.add(new ValidationResult(source, action.name(), passed ? ResultStatus.PASS : ResultStatus.FAIL, action.expected(), rendered, action.description()));
            } catch (Exception e) {
                results.add(new ValidationResult(source, action.name(), ResultStatus.ERROR, action.expected(), "", e.getMessage()));
            }
        }
        return results;
    }

    private boolean evaluate(String expression) {
        return new ExpressionParser(expression).parse();
    }

    private static boolean compare(String left, String right, String op) {
        Double l = number(left);
        Double r = number(right);
        if (l != null && r != null) {
            int cmp = Double.compare(l, r);
            return applyCompare(cmp, op);
        }
        int cmp = left.compareTo(right);
        return applyCompare(cmp, op);
    }

    private static boolean applyCompare(int cmp, String op) {
        if (">=".equals(op)) return cmp >= 0;
        if ("<=".equals(op)) return cmp <= 0;
        if ("==".equals(op)) return cmp == 0;
        if ("!=".equals(op)) return cmp != 0;
        if (">".equals(op)) return cmp > 0;
        if ("<".equals(op)) return cmp < 0;
        return false;
    }

    private static Double number(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String strip(String value) {
        String text = value.trim();
        if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static class ExpressionParser {
        private final List<String> tokens;
        private int position;

        ExpressionParser(String expression) {
            this.tokens = tokenize(expression);
        }

        boolean parse() {
            boolean value = parseOr();
            if (position != tokens.size()) {
                throw new IllegalArgumentException("Unexpected token: " + tokens.get(position));
            }
            return value;
        }

        private boolean parseOr() {
            boolean value = parseAnd();
            while (matchIgnoreCase("or")) {
                value = value || parseAnd();
            }
            return value;
        }

        private boolean parseAnd() {
            boolean value = parseNot();
            while (matchIgnoreCase("and")) {
                value = value && parseNot();
            }
            return value;
        }

        private boolean parseNot() {
            if (matchIgnoreCase("not")) {
                return !parseNot();
            }
            return parsePrimary();
        }

        private boolean parsePrimary() {
            if (match("(")) {
                boolean value = parseOr();
                expect(")");
                return value;
            }
            String left = readOperand();
            if (matchIgnoreCase("is")) {
                boolean negate = matchIgnoreCase("not");
                expectIgnoreCase("null");
                boolean isNull = left == null || left.length() == 0 || "null".equalsIgnoreCase(left);
                return negate ? !isNull : isNull;
            }
            String op = next();
            if ("like".equalsIgnoreCase(op)) {
                String pattern = strip(readOperand());
                String regex = pattern.replace("%", ".*").replace("_", ".");
                return strip(left).matches(regex);
            }
            String right = readOperand();
            return compare(strip(left), strip(right), op);
        }

        private String readOperand() {
            StringBuilder operand = new StringBuilder();
            while (position < tokens.size() && !isBoundary(tokens.get(position))) {
                if (operand.length() > 0) {
                    operand.append(' ');
                }
                operand.append(tokens.get(position++));
            }
            if (operand.length() == 0) {
                throw new IllegalArgumentException("Expected operand");
            }
            return operand.toString();
        }

        private boolean isBoundary(String token) {
            return ")".equals(token)
                    || "and".equalsIgnoreCase(token)
                    || "or".equalsIgnoreCase(token)
                    || "is".equalsIgnoreCase(token)
                    || "like".equalsIgnoreCase(token)
                    || ">=".equals(token)
                    || "<=".equals(token)
                    || "==".equals(token)
                    || "!=".equals(token)
                    || ">".equals(token)
                    || "<".equals(token);
        }

        private String next() {
            if (position >= tokens.size()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }
            return tokens.get(position++);
        }

        private boolean match(String token) {
            if (position < tokens.size() && token.equals(tokens.get(position))) {
                position++;
                return true;
            }
            return false;
        }

        private boolean matchIgnoreCase(String token) {
            if (position < tokens.size() && token.equalsIgnoreCase(tokens.get(position))) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(String token) {
            if (!match(token)) {
                throw new IllegalArgumentException("Expected token: " + token);
            }
        }

        private void expectIgnoreCase(String token) {
            if (!matchIgnoreCase(token)) {
                throw new IllegalArgumentException("Expected token: " + token);
            }
        }

        private static List<String> tokenize(String expression) {
            List<String> tokens = new ArrayList<String>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            char quote = 0;
            for (int i = 0; i < expression.length(); i++) {
                char ch = expression.charAt(i);
                if (quoted) {
                    current.append(ch);
                    if (ch == quote) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        quoted = false;
                    }
                    continue;
                }
                if (ch == '\'' || ch == '"') {
                    flush(current, tokens);
                    quoted = true;
                    quote = ch;
                    current.append(ch);
                } else if (Character.isWhitespace(ch)) {
                    flush(current, tokens);
                } else if (ch == '(' || ch == ')') {
                    flush(current, tokens);
                    tokens.add(String.valueOf(ch));
                } else if ((ch == '>' || ch == '<' || ch == '=' || ch == '!') && i + 1 < expression.length() && expression.charAt(i + 1) == '=') {
                    flush(current, tokens);
                    tokens.add(expression.substring(i, i + 2));
                    i++;
                } else if (ch == '>' || ch == '<') {
                    flush(current, tokens);
                    tokens.add(String.valueOf(ch));
                } else {
                    current.append(ch);
                }
            }
            flush(current, tokens);
            return tokens;
        }

        private static void flush(StringBuilder current, List<String> tokens) {
            if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
    }
}
