/*
 * Author: Jeffrey + ChatGPT
 */
package att.template;

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
        if (open < 1 || !body.endsWith(")")) throw new ExpressionSyntaxException(Math.max(0, open), body.length(), "a complete tool/function call", "invalid call");
        String name = body.substring(0, open).trim();
        if (name.isEmpty()) throw new ExpressionSyntaxException(0, open, "a tool/function name", "blank name");
        String arguments = body.substring(open + 1, body.length() - 1).trim();
        List<Argument> parsed = new ArrayList<Argument>();
        int positional = 0;
        if (!arguments.isEmpty()) try { for (String item : splitArguments(arguments)) {
            int equals = topLevelEquals(item);
            String key = equals > 0 ? item.substring(0, equals).trim() : "arg" + positional++;
            String value = equals > 0 ? item.substring(equals + 1).trim() : item.trim();
            if (key.isEmpty() || value.isEmpty()) throw new IllegalArgumentException("Invalid tool/function argument: " + item);
            parsed.add(new Argument(key, value, equals <= 0));
        } } catch (ExpressionSyntaxException error) { throw error; }
        catch (IllegalArgumentException error) {
            throw new ExpressionSyntaxException(Math.max(0, open + 1), body.length() - 1,
                    "a valid argument list", error.getMessage());
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

    /** Splits an inline list body with the same quote/nesting rules used by call arguments. */
    public List<String> listItems(String expression) {
        String text = expression == null ? "" : expression.trim();
        if (!(text.startsWith("[") && text.endsWith("]"))) {
            throw new IllegalArgumentException("Expected an inline list: " + expression);
        }
        String body = text.substring(1, text.length() - 1).trim();
        if (body.isEmpty()) return Collections.emptyList();
        return splitArguments(body);
    }

    /**
     * Finds the named (or positional) argument containing a parser offset.
     * This is intentionally a non-validating scan: it must still work when
     * the expression is malformed and the normal call parser cannot build an
     * AST.  The innermost call containing the offset is preferred.
     */
    public String argumentAt(String expression, int offset) {
        if (expression == null || expression.isEmpty()) return null;
        String text = expression;
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        int end = text.length();
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (start >= end) return null;
        int bodyStart = start;
        int bodyEnd = end;
        if (text.charAt(start) == '#' && start + 1 < end && text.charAt(start + 1) == '{') {
            bodyStart = start + 2;
            if (bodyEnd > bodyStart && text.charAt(bodyEnd - 1) == '}') bodyEnd--;
        }
        String result = null;
        int bestOpen = -1;
        for (int index = bodyStart; index < bodyEnd; index++) {
            if (text.charAt(index) != '(' || !looksLikeCallOpen(text, index, bodyStart)) continue;
            String candidate = argumentAtCall(text, index, bodyEnd, offset);
            if (candidate != null && index >= bestOpen) {
                bestOpen = index;
                result = candidate;
            }
        }
        return result;
    }

    private String argumentAtCall(String text, int open, int limit, int offset) {
        int segmentStart = open + 1;
        int round = 0, curly = 0, square = 0;
        char quote = 0;
        boolean escaped = false;
        int positional = 0;
        for (int index = segmentStart; index < limit; index++) {
            char ch = text.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == quote) quote = 0;
                continue;
            }
            if (isOpeningQuote(ch)) { quote = closingQuote(ch); continue; }
            if (ch == '(') round++;
            else if (ch == ')') {
                if (round == 0 && curly == 0 && square == 0) {
                    return argumentForRange(text, segmentStart, index, offset, positional);
                }
                round--;
            } else if (ch == '{') curly++;
            else if (ch == '}') { if (curly > 0) curly--; }
            else if (ch == '[') square++;
            else if (ch == ']') { if (square > 0) square--; }
            else if (ch == ',' && round == 0 && curly == 0 && square == 0) {
                String candidate = argumentForRange(text, segmentStart, index, offset, positional);
                if (candidate != null) return candidate;
                segmentStart = index + 1;
                positional++;
            }
        }
        return argumentForRange(text, segmentStart, limit, offset, positional);
    }

    private String argumentForRange(String text, int start, int end, int offset, int positional) {
        if (offset < start || offset > end) return null;
        int left = start;
        while (left < end && Character.isWhitespace(text.charAt(left))) left++;
        int right = end;
        while (right > left && Character.isWhitespace(text.charAt(right - 1))) right--;
        if (left >= right) return null;
        int equals = topLevelEquals(text.substring(left, right));
        if (equals >= 0) {
            String key = text.substring(left, left + equals).trim();
            return key.isEmpty() ? "arg" + positional : key;
        }
        return "arg" + positional;
    }

    private boolean looksLikeCallOpen(String text, int open, int lowerBound) {
        int index = open - 1;
        while (index >= lowerBound && Character.isWhitespace(text.charAt(index))) index--;
        if (index < lowerBound) return false;
        char last = text.charAt(index);
        return Character.isLetterOrDigit(last) || last == '_' || last == '.' || last == '-';
    }

    private boolean isOpeningQuote(char value) {
        return value == '\'' || value == '"' || value == '\u2018' || value == '\u201c';
    }

    private char closingQuote(char value) {
        return value == '\u2018' ? '\u2019' : value == '\u201c' ? '\u201d' : value;
    }

    private List<String> splitArguments(String text) {
        List<String> result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        int round = 0, curly = 0, square = 0;
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
            else if (ch == '[') square++;
            else if (ch == ']') square--;
            if (round < 0 || curly < 0 || square < 0) throw new IllegalArgumentException("Unbalanced tool/function arguments: " + text);
            if (ch == ',' && round == 0 && curly == 0 && square == 0) { result.add(current.toString()); current.setLength(0); }
            else current.append(ch);
        }
        if (quote != 0 || round != 0 || curly != 0 || square != 0) throw new IllegalArgumentException("Unclosed tool/function argument: " + text);
        result.add(current.toString());
        return result;
    }

    private int topLevelEquals(String text) {
        char quote = 0; int round = 0, curly = 0, square = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) { if (ch == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0; continue; }
            if (ch == '\'' || ch == '\"') quote = ch;
            else if (ch == '(') round++;
            else if (ch == ')') round--;
            else if (ch == '{') curly++;
            else if (ch == '}') curly--;
            else if (ch == '[') square++;
            else if (ch == ']') square--;
            else if (ch == '=' && round == 0 && curly == 0 && square == 0) return i;
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
