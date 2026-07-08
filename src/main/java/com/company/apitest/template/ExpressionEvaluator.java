/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the small SQL-like boolean expression subset used by template assertions.
 */
public class ExpressionEvaluator {
    public boolean evaluate(String expression) {
        return new Parser(expression).parse();
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
            while (matchIgnoreCase("or")) value = value || parseAnd();
            return value;
        }

        private boolean parseAnd() {
            boolean value = parseNot();
            while (matchIgnoreCase("and")) value = value && parseNot();
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

        private static String strip(String value) {
            String text = value.trim();
            if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
                return text.substring(1, text.length() - 1);
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
                    if (ch == quote) {
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
