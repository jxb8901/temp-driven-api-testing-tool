/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.company.apitest.core.CaseRuntimeContext;

/**
 * Evaluates the small SQL-like boolean expression subset used by template assertions.
 */
public class ExpressionEvaluator {
    private static final Pattern CONTEXT = Pattern.compile("\\$\\{([^}]+)}");
    public boolean evaluate(String expression) {
        return new Parser(expression).parse();
    }

    /** Resolves Context values as typed expression literals before parsing. */
    public boolean evaluate(String expression, CaseRuntimeContext context) {
        Matcher matcher = CONTEXT.matcher(expression);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            Object value = context.resolve(matcher.group(1));
            char activeQuote = activeQuote(expression, matcher.start());
            String replacement = activeQuote != 0 ? escapeInside(value, activeQuote) : literal(value);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return evaluate(rendered.toString());
    }

    private static String literal(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return "'" + escapeInside(value, '\'') + "'";
    }

    private static String escapeInside(Object value, char quote) {
        String text = value == null ? "" : String.valueOf(value);
        return text.replace("\\", "\\\\").replace(String.valueOf(quote), "\\" + quote);
    }

    private static char activeQuote(String expression, int end) {
        char quote = 0;
        for (int i = 0; i < end; i++) {
            char ch = expression.charAt(i);
            if ((ch == '\'' || ch == '"') && (i == 0 || expression.charAt(i - 1) != '\\')) {
                if (quote == 0) quote = ch; else if (quote == ch) quote = 0;
            }
        }
        return quote;
    }

    private static class Parser {
        private final List<String> tokens;
        private int position;

        Parser(String expression) {
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
                boolean right = parseAnd(); // Always parse RHS even when Java could short-circuit.
                value = value || right;
            }
            return value;
        }

        private boolean parseAnd() {
            boolean value = parseNot();
            while (matchIgnoreCase("and")) {
                boolean right = parseNot();
                value = value && right;
            }
            return value;
        }

        private boolean parseNot() {
            return matchIgnoreCase("not") ? !parseNot() : parsePrimary();
        }

        private boolean parsePrimary() {
            if (match("(")) {
                boolean value = parseOr();
                expect(")");
                return value;
            }
            String left = readOperand();
            // A literal is a complete expression too: true, false, 'text' is not.
            if (position >= tokens.size() || ")".equals(tokens.get(position)) || "and".equalsIgnoreCase(tokens.get(position)) || "or".equalsIgnoreCase(tokens.get(position))) {
                Boolean literal = bool(strip(left));
                if (literal == null) throw new IllegalArgumentException("Expected comparison after operand: " + left);
                return literal.booleanValue();
            }
            if (matchIgnoreCase("is")) {
                boolean negate = matchIgnoreCase("not");
                expectIgnoreCase("null");
                boolean isNull = left.length() == 0 || "null".equalsIgnoreCase(left);
                return negate ? !isNull : isNull;
            }
            String op = next();
            if ("like".equalsIgnoreCase(op)) {
                return strip(left).matches(strip(readOperand()).replace("%", ".*").replace("_", "."));
            }
            return compare(strip(left), strip(readOperand()), op);
        }

        private String readOperand() {
            StringBuilder operand = new StringBuilder();
            while (position < tokens.size() && !isBoundary(tokens.get(position))) {
                if (operand.length() > 0) operand.append(' ');
                operand.append(tokens.get(position++));
            }
            if (operand.length() == 0) throw new IllegalArgumentException("Expected operand");
            return operand.toString();
        }

        private boolean isBoundary(String token) {
            return ")".equals(token) || "and".equalsIgnoreCase(token) || "or".equalsIgnoreCase(token)
                    || "is".equalsIgnoreCase(token) || "like".equalsIgnoreCase(token)
                    || ">=".equals(token) || "<=".equals(token) || "==".equals(token) || "!=".equals(token)
                    || ">".equals(token) || "<".equals(token);
        }

        private String next() {
            if (position >= tokens.size()) throw new IllegalArgumentException("Unexpected end of expression");
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
            if (!match(token)) throw new IllegalArgumentException("Expected token: " + token);
        }

        private void expectIgnoreCase(String token) {
            if (!matchIgnoreCase(token)) throw new IllegalArgumentException("Expected token: " + token);
        }

        private static boolean compare(String left, String right, String op) {
            Boolean lbool = bool(left);
            Boolean rbool = bool(right);
            if (lbool != null && rbool != null) {
                if ("==".equals(op)) return lbool.equals(rbool);
                if ("!=".equals(op)) return !lbool.equals(rbool);
                throw new IllegalArgumentException("Boolean literals only support == and !=");
            }
            Double l = number(left);
            Double r = number(right);
            int cmp = l != null && r != null ? Double.compare(l, r) : left.compareTo(right);
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

        private static Boolean bool(String value) {
            if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
            return null;
        }

        private static String strip(String value) {
            String text = value.trim();
            if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
                char quote = text.charAt(0);
                return text.substring(1, text.length() - 1).replace("\\" + quote, String.valueOf(quote)).replace("\\\\", "\\");
            }
            return text;
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
                    if (ch == quote && (i == 0 || expression.charAt(i - 1) != '\\')) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        quoted = false;
                    }
                } else if (ch == '\'' || ch == '"') {
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
            if (quoted) throw new IllegalArgumentException("Unclosed quoted literal in expression");
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
