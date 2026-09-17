/* Author: Jeffrey + ChatGPT */
package att.template;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed parser/evaluator for V3.2 #{...} expression blocks. */
public final class ExpressionBlockEvaluator {
    public interface Resolver {
        Object context(String path) throws Exception;
        Object call(String name, Map<String, Object> arguments) throws Exception;
        String interpolate(String value) throws Exception;
        default boolean hasContext(String path) { return false; }
        default Object contextOptional(String path) throws Exception { return context(path); }
        default Object context(String path, boolean optional) throws Exception {
            return optional ? contextOptional(path) : context(path);
        }
    }

    public Object evaluate(String expression, Resolver resolver) throws Exception {
        return parse(expression).root.evaluate(resolver);
    }

    public void validateSyntax(String expression) { parse(expression); }

    public List<ToolCallParser.ParsedCall> calls(String expression) {
        Parsed parsed = parse(expression);
        List<ToolCallParser.ParsedCall> calls = new ArrayList<ToolCallParser.ParsedCall>();
        parsed.root.collectCalls(calls);
        return calls;
    }

    public List<String> contextPaths(String expression) {
        Parsed parsed = parse(expression);
        List<String> paths = new ArrayList<String>();
        parsed.root.collectContextPaths(paths);
        return paths;
    }

    private Parsed parse(String expression) {
        String source = expression == null ? "" : expression.trim();
        int baseOffset = expression == null ? 0 : expression.indexOf(source);
        if (source.startsWith("#{") && matchingBlockEnd(source, 2) == source.length() - 1) {
            source = source.substring(2, source.length() - 1);
            baseOffset += 2;
        }
        try {
            Parser parser = new Parser(source);
            Node root = parser.parseExpression();
            parser.expect(TokenType.END);
            return new Parsed(root);
        } catch (ExpressionSyntaxException error) {
            throw error.shifted(baseOffset).withExpression(expression);
        } catch (IllegalArgumentException error) {
            // Keep parser failures structurally locatable. Runtime evaluation errors are
            // raised after parsing and therefore are not wrapped here.
            throw new ExpressionSyntaxException(baseOffset, baseOffset + source.length(),
                    "a valid expression", error.getMessage() == null ? "invalid expression" : error.getMessage())
                    .withExpression(expression);
        }
    }

    private static int matchingBlockEnd(String text, int bodyStart) {
        int depth = 1; char quote = 0;
        for (int index = bodyStart; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quote != 0) {
                if (value == quote && text.charAt(index - 1) != '\\') quote = 0;
                continue;
            }
            if (value == '\'' || value == '"') { quote = value; continue; }
            if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private static final class Parsed { private final Node root; private Parsed(Node root) { this.root = root; } }

    private interface Node {
        Object evaluate(Resolver resolver) throws Exception;
        void collectCalls(List<ToolCallParser.ParsedCall> calls);
        void collectContextPaths(List<String> paths);
        String source();
    }

    private abstract static class BaseNode implements Node {
        private final String source;
        BaseNode(String source) { this.source = source; }
        @Override public String source() { return source; }
        @Override public void collectCalls(List<ToolCallParser.ParsedCall> calls) { }
        @Override public void collectContextPaths(List<String> paths) { }
    }

    private static final class LiteralNode extends BaseNode {
        private final Object value; private final boolean interpolate;
        LiteralNode(String source, Object value, boolean interpolate) { super(source); this.value = value; this.interpolate = interpolate; }
        @Override public Object evaluate(Resolver resolver) throws Exception {
            return interpolate && value instanceof String ? resolver.interpolate((String) value) : value;
        }
    }

    private static final class ContextNode extends BaseNode {
        private final String path; private final boolean optional;
        ContextNode(String source, String path, boolean optional) { super(source); this.path = path; this.optional = optional; }
        @Override public Object evaluate(Resolver resolver) throws Exception { return resolver.context(path, optional); }
        @Override public void collectContextPaths(List<String> paths) { paths.add(optional ? path + "?" : path); }
    }

    private static final class IdentifierNode extends BaseNode {
        private final String value;
        IdentifierNode(String source, String value) { super(source); this.value = value; }
        @Override public Object evaluate(Resolver resolver) {
            if (explicitContextRoot(value) || resolver.hasContext(value)) {
                throw new IllegalArgumentException("Context values must use ${...}: ${" + value + "}");
            }
            return value;
        }
    }

    private static final class ListNode extends BaseNode {
        private final List<Node> values;
        ListNode(String source, List<Node> values) { super(source); this.values = values; }
        @Override public Object evaluate(Resolver resolver) throws Exception {
            List<Object> result = new ArrayList<Object>();
            for (Node value : values) result.add(value.evaluate(resolver));
            return result;
        }
        @Override public void collectCalls(List<ToolCallParser.ParsedCall> calls) { for (Node value : values) value.collectCalls(calls); }
        @Override public void collectContextPaths(List<String> paths) { for (Node value : values) value.collectContextPaths(paths); }
    }

    private static final class UnaryNode extends BaseNode {
        private final String operator; private final Node value;
        UnaryNode(String source, String operator, Node value) { super(source); this.operator = operator; this.value = value; }
        @Override public Object evaluate(Resolver resolver) throws Exception {
            Object resolved = value.evaluate(resolver);
            if ("not".equals(operator)) return Boolean.valueOf(!bool(resolved));
            BigDecimal number = number(resolved, "Unary " + operator);
            return "+".equals(operator) ? number : number.negate();
        }
        @Override public void collectCalls(List<ToolCallParser.ParsedCall> calls) { value.collectCalls(calls); }
        @Override public void collectContextPaths(List<String> paths) { value.collectContextPaths(paths); }
    }

    private static final class BinaryNode extends BaseNode {
        private final String operator; private final Node left, right;
        BinaryNode(String source, String operator, Node left, Node right) { super(source); this.operator = operator; this.left = left; this.right = right; }
        @Override public Object evaluate(Resolver resolver) throws Exception {
            Object l = left.evaluate(resolver);
            if ("and".equals(operator) && !bool(l)) return Boolean.FALSE;
            if ("or".equals(operator) && bool(l)) return Boolean.TRUE;
            Object r = right.evaluate(resolver);
            if ("and".equals(operator)) return Boolean.valueOf(bool(r));
            if ("or".equals(operator)) return Boolean.valueOf(bool(r));
            if ("+".equals(operator) || "-".equals(operator) || "*".equals(operator) || "/".equals(operator)) {
                BigDecimal ln = number(l, "Left operand"), rn = number(r, "Right operand");
                if ("+".equals(operator)) return ln.add(rn);
                if ("-".equals(operator)) return ln.subtract(rn);
                if ("*".equals(operator)) return ln.multiply(rn);
                if (rn.compareTo(BigDecimal.ZERO) == 0) throw new IllegalArgumentException("Division by zero");
                return ln.divide(rn, MathContext.DECIMAL128).stripTrailingZeros();
            }
            if ("in".equals(operator)) return Boolean.valueOf(in(l, r));
            if ("like".equals(operator)) return Boolean.valueOf(text(l).matches(likePattern(text(r))));
            int comparison = compare(l, r);
            if ("==".equals(operator)) return Boolean.valueOf(comparison == 0);
            if ("!=".equals(operator)) return Boolean.valueOf(comparison != 0);
            if (">".equals(operator)) return Boolean.valueOf(comparison > 0);
            if (">=".equals(operator)) return Boolean.valueOf(comparison >= 0);
            if ("<".equals(operator)) return Boolean.valueOf(comparison < 0);
            if ("<=".equals(operator)) return Boolean.valueOf(comparison <= 0);
            throw new IllegalArgumentException("Unsupported expression operator: " + operator);
        }
        @Override public void collectCalls(List<ToolCallParser.ParsedCall> calls) { left.collectCalls(calls); right.collectCalls(calls); }
        @Override public void collectContextPaths(List<String> paths) { left.collectContextPaths(paths); right.collectContextPaths(paths); }
    }

    private static final class IsNullNode extends BaseNode {
        private final Node value; private final boolean negate;
        IsNullNode(String source, Node value, boolean negate) { super(source); this.value = value; this.negate = negate; }
        @Override public Object evaluate(Resolver resolver) throws Exception { return Boolean.valueOf(negate ? value.evaluate(resolver) != null : value.evaluate(resolver) == null); }
        @Override public void collectCalls(List<ToolCallParser.ParsedCall> calls) { value.collectCalls(calls); }
        @Override public void collectContextPaths(List<String> paths) { value.collectContextPaths(paths); }
    }

    private static final class CallNode extends BaseNode {
        private final String name; private final List<CallArgument> arguments;
        CallNode(String source, String name, List<CallArgument> arguments) { super(source); this.name = name; this.arguments = arguments; }
        @Override public Object evaluate(Resolver resolver) throws Exception {
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            int positional = 0;
            for (CallArgument argument : arguments) {
                String key = argument.name == null ? "arg" + positional++ : argument.name;
                if (values.containsKey(key)) throw new IllegalArgumentException("Duplicate function argument: " + key);
                values.put(key, argument.value.evaluate(resolver));
            }
            return resolver.call(name, values);
        }
        @Override public void collectCalls(List<ToolCallParser.ParsedCall> calls) {
            List<ToolCallParser.Argument> parsed = new ArrayList<ToolCallParser.Argument>();
            int positional = 0;
            for (CallArgument argument : arguments) {
                String key = argument.name == null ? "arg" + positional++ : argument.name;
                parsed.add(new ToolCallParser.Argument(key, argument.value.source(), argument.name == null));
            }
            calls.add(new ToolCallParser.ParsedCall(name, parsed));
            for (CallArgument argument : arguments) argument.value.collectCalls(calls);
        }
        @Override public void collectContextPaths(List<String> paths) { for (CallArgument argument : arguments) argument.value.collectContextPaths(paths); }
    }

    private static final class CallArgument { private final String name; private final Node value; private CallArgument(String name, Node value) { this.name = name; this.value = value; } }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof String && ("true".equalsIgnoreCase((String) value) || "false".equalsIgnoreCase((String) value))) return Boolean.parseBoolean((String) value);
        throw new IllegalArgumentException("Expression requires a boolean but was " + type(value));
    }

    private static BigDecimal number(Object value, String owner) {
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return new BigDecimal(String.valueOf(value));
        try { return new BigDecimal(text(value)); }
        catch (RuntimeException error) { throw new IllegalArgumentException(owner + " must be numeric but was " + type(value)); }
    }

    private static int compare(Object left, Object right) {
        if (left == null || right == null) return left == right ? 0 : (left == null ? -1 : 1);
        try { return number(left, "left").compareTo(number(right, "right")); }
        catch (IllegalArgumentException ignored) { return text(left).compareTo(text(right)); }
    }

    private static boolean in(Object needle, Object values) {
        if (values == null) throw new IllegalArgumentException("Right operand of in must be a list or array, not null");
        if (values instanceof Iterable) {
            for (Object value : (Iterable<?>) values) if (compare(needle, value) == 0) return true;
            return false;
        }
        if (values.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(values); index++) if (compare(needle, Array.get(values, index)) == 0) return true;
            return false;
        }
        throw new IllegalArgumentException("Right operand of in must be a list or array but was " + type(values));
    }

    private static String likePattern(String pattern) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int index = 0; index < pattern.length(); index++) {
            char value = pattern.charAt(index);
            if (value == '%' || value == '_') {
                if (literal.length() > 0) {
                    regex.append(java.util.regex.Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(value == '%' ? ".*" : ".");
            } else literal.append(value);
        }
        if (literal.length() > 0) regex.append(java.util.regex.Pattern.quote(literal.toString()));
        return regex.toString();
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String type(Object value) { return value == null ? "null" : value.getClass().getSimpleName(); }

    private enum TokenType { NUMBER, STRING, CONTEXT, IDENTIFIER, EMBEDDED, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, EQUALS, OPERATOR, END }
    private static final class Token {
        private final TokenType type; private final String text; private final int start, end;
        private Token(TokenType type, String text, int start, int end) { this.type = type; this.text = text; this.start = start; this.end = end; }
    }

    private static final class Lexer {
        private final String source; private int index;
        Lexer(String source) { this.source = source; }
        List<Token> tokens() {
            List<Token> values = new ArrayList<Token>();
            while (true) { Token token = next(); values.add(token); if (token.type == TokenType.END) return values; }
        }
        private Token next() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
            int start = index;
            if (index >= source.length()) return new Token(TokenType.END, "", index, index);
            char value = source.charAt(index);
            if (value == '$' && index + 1 < source.length() && source.charAt(index + 1) == '{') return context(start);
            if (value == '#' && index + 1 < source.length() && source.charAt(index + 1) == '{') return embedded(start);
            if (value == '\'' || value == '"' || value == '\u2018' || value == '\u2019'
                    || value == '\u201c' || value == '\u201d') return string(start, value);
            if (Character.isDigit(value) || (value == '.' && index + 1 < source.length() && Character.isDigit(source.charAt(index + 1)))) return number(start);
            if (Character.isLetter(value) || value == '_') return identifier(start);
            index++;
            if (value == '(') return new Token(TokenType.LPAREN, "(", start, index);
            if (value == ')') return new Token(TokenType.RPAREN, ")", start, index);
            if (value == '[') return new Token(TokenType.LBRACKET, "[", start, index);
            if (value == ']') return new Token(TokenType.RBRACKET, "]", start, index);
            if (value == ',') return new Token(TokenType.COMMA, ",", start, index);
            if (value == '=' && (index >= source.length() || source.charAt(index) != '=')) return new Token(TokenType.EQUALS, "=", start, index);
            if ((value == '=' || value == '!' || value == '>' || value == '<') && index < source.length() && source.charAt(index) == '=') {
                index++; return new Token(TokenType.OPERATOR, source.substring(start, index), start, index);
            }
            if ("+-*/><".indexOf(value) >= 0) return new Token(TokenType.OPERATOR, String.valueOf(value), start, index);
            throw new ExpressionSyntaxException(start, index, "an expression token", "'" + value + "'");
        }
        private Token context(int start) {
            index += 2; int body = index; char quote = 0;
            while (index < source.length()) {
                char value = source.charAt(index);
                if (quote != 0) { if (value == quote && source.charAt(index - 1) != '\\') quote = 0; index++; continue; }
                if (value == '\'' || value == '"') { quote = value; index++; continue; }
                if (value == '}') { String path = source.substring(body, index); index++; if (path.trim().isEmpty() || !path.equals(path.trim())) throw new ExpressionSyntaxException(start, index, "a non-blank Context path", "invalid Context path"); return new Token(TokenType.CONTEXT, path, start, index); }
                index++;
            }
            throw new ExpressionSyntaxException(start, index, "'}' to close Context expression", "end of expression");
        }
        private Token embedded(int start) {
            int end = matchingBlockEnd(source, index + 2);
            if (end < 0) throw new ExpressionSyntaxException(start, source.length(), "'}' to close nested expression", "end of expression");
            String body = source.substring(index + 2, end); index = end + 1;
            return new Token(TokenType.EMBEDDED, body, start, index);
        }
        private Token string(int start, char quote) {
            char close = quote == '\u2018' ? '\u2019' : (quote == '\u201c' ? '\u201d' : quote);
            index++; StringBuilder value = new StringBuilder(); boolean escaped = false;
            while (index < source.length()) {
                char ch = source.charAt(index++);
                if (escaped) { value.append(ch == 'n' ? '\n' : (ch == 'r' ? '\r' : (ch == 't' ? '\t' : ch))); escaped = false; }
                else if (ch == '\\') escaped = true;
                else if (ch == close) return new Token(TokenType.STRING, value.toString(), start, index);
                else value.append(ch);
            }
            throw new ExpressionSyntaxException(start, index, "matching closing quote", "end of expression");
        }
        private Token number(int start) {
            while (index < source.length() && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) index++;
            return new Token(TokenType.NUMBER, source.substring(start, index), start, index);
        }
        private Token identifier(int start) {
            while (index < source.length()) {
                char value = source.charAt(index);
                if (!(Character.isLetterOrDigit(value) || value == '_' || value == '.' || value == '-')) break;
                index++;
            }
            return new Token(TokenType.IDENTIFIER, source.substring(start, index), start, index);
        }
    }

    private static final class Parser {
        private final String source; private final List<Token> tokens; private int position;
        Parser(String source) { this.source = source; this.tokens = new Lexer(source).tokens(); }
        Node parseExpression() { if (peek().type == TokenType.END) throw new IllegalArgumentException("Expression block must not be blank"); return parseOr(); }
        private Node parseOr() { Node value = parseAnd(); while (keyword("or")) value = binary("or", value, parseAnd()); return value; }
        private Node parseAnd() { Node value = parseComparison(); while (keyword("and")) value = binary("and", value, parseComparison()); return value; }
        private Node parseComparison() {
            Node value = parseAdditive();
            if (keyword("is")) { boolean negate = keyword("not"); requireKeyword("null"); return new IsNullNode(slice(value, previous()), value, negate); }
            if (keyword("in")) return binary("in", value, parseAdditive());
            if (keyword("like")) return binary("like", value, parseAdditive());
            if (peek().type == TokenType.OPERATOR && ("==".equals(peek().text) || "!=".equals(peek().text) || ">".equals(peek().text) || ">=".equals(peek().text) || "<".equals(peek().text) || "<=".equals(peek().text))) {
                String operator = consume().text; return binary(operator, value, parseAdditive());
            }
            return value;
        }
        private Node parseAdditive() { Node value = parseMultiplicative(); while (operator("+") || operator("-")) { String op = previous().text; value = binary(op, value, parseMultiplicative()); } return value; }
        private Node parseMultiplicative() { Node value = parseUnary(); while (operator("*") || operator("/")) { String op = previous().text; value = binary(op, value, parseUnary()); } return value; }
        private Node parseUnary() {
            if (keyword("not")) { Token start = previous(); Node value = parseUnary(); return new UnaryNode(source.substring(start.start, end(value)), "not", value); }
            if (operator("+") || operator("-")) { Token start = previous(); Node value = parseUnary(); return new UnaryNode(source.substring(start.start, end(value)), start.text, value); }
            return parsePrimary();
        }
        private Node parsePrimary() {
            Token token = consume();
            if (token.type == TokenType.NUMBER) {
                try { return new LiteralNode(raw(token), new BigDecimal(token.text), false); }
                catch (NumberFormatException error) { throw new ExpressionSyntaxException(token.start, token.end, "a valid number", "invalid number"); }
            }
            if (token.type == TokenType.STRING) return new LiteralNode(raw(token), token.text, true);
            if (token.type == TokenType.CONTEXT) {
                boolean optional = token.text.endsWith("?");
                String path = optional ? token.text.substring(0, token.text.length() - 1) : token.text;
                try { att.core.CaseRuntimeContext.validateReferencePath(path); }
                catch (IllegalArgumentException error) {
                    throw new ExpressionSyntaxException(token.start, token.end, "a valid Context path", error.getMessage());
                }
                return new ContextNode(raw(token), path, optional);
            }
            if (token.type == TokenType.EMBEDDED) {
                try {
                    Parser nested = new Parser(token.text);
                    Node value = nested.parseExpression();
                    nested.expect(TokenType.END);
                    return new WrappedNode(raw(token), value);
                } catch (ExpressionSyntaxException error) {
                    throw error.shifted(token.start + 2);
                }
            }
            if (token.type == TokenType.LPAREN) { Node value = parseOr(); Token close = expect(TokenType.RPAREN); return new WrappedNode(source.substring(token.start, close.end), value); }
            if (token.type == TokenType.LBRACKET) {
                List<Node> items = new ArrayList<Node>();
                if (!check(TokenType.RBRACKET)) { do { items.add(parseOr()); } while (match(TokenType.COMMA)); }
                Token close = expect(TokenType.RBRACKET); return new ListNode(source.substring(token.start, close.end), items);
            }
            if (token.type == TokenType.IDENTIFIER) {
                if ("true".equalsIgnoreCase(token.text)) return new LiteralNode(raw(token), Boolean.TRUE, false);
                if ("false".equalsIgnoreCase(token.text)) return new LiteralNode(raw(token), Boolean.FALSE, false);
                if ("null".equalsIgnoreCase(token.text)) return new LiteralNode(raw(token), null, false);
                if (match(TokenType.LPAREN)) return call(token);
                return new IdentifierNode(raw(token), token.text);
            }
            String actual = token.type == TokenType.OPERATOR
                    ? "operator '" + token.text + "'"
                    : token.type.name().toLowerCase(java.util.Locale.ROOT);
            throw new ExpressionSyntaxException(token.start, token.end, "an expression operand", actual);
        }
        private Node call(Token name) {
            List<CallArgument> arguments = new ArrayList<CallArgument>();
            if (!check(TokenType.RPAREN)) {
                do {
                    String argumentName = null;
                    if (check(TokenType.IDENTIFIER) && lookahead(1).type == TokenType.EQUALS) { argumentName = consume().text; consume(); }
                    arguments.add(new CallArgument(argumentName, parseOr()));
                } while (match(TokenType.COMMA));
            }
            Token close = expect(TokenType.RPAREN);
            return new CallNode(source.substring(name.start, close.end), name.text, arguments);
        }
        private Node binary(String operator, Node left, Node right) { return new BinaryNode(source.substring(start(left), end(right)), operator, left, right); }
        private boolean keyword(String value) { if (peek().type == TokenType.IDENTIFIER && value.equalsIgnoreCase(peek().text)) { position++; return true; } return false; }
        private void requireKeyword(String value) { if (!keyword(value)) throw new ExpressionSyntaxException(peek().start, peek().end, "'" + value + "'", peek().type.name().toLowerCase(java.util.Locale.ROOT)); }
        private boolean operator(String value) { if (peek().type == TokenType.OPERATOR && value.equals(peek().text)) { position++; return true; } return false; }
        private boolean match(TokenType type) { if (check(type)) { position++; return true; } return false; }
        private boolean check(TokenType type) { return peek().type == type; }
        private Token consume() { return tokens.get(position++); }
        private Token peek() { return tokens.get(position); }
        private Token lookahead(int offset) { return tokens.get(Math.min(tokens.size() - 1, position + offset)); }
        private Token previous() { return tokens.get(position - 1); }
        private Token expect(TokenType type) { if (!check(type)) throw new ExpressionSyntaxException(peek().start, peek().end, type.name().toLowerCase(java.util.Locale.ROOT), peek().type.name().toLowerCase(java.util.Locale.ROOT)); return consume(); }
        private String raw(Token token) { return source.substring(token.start, token.end); }
        private int start(Node node) { return source.indexOf(node.source()); }
        private int end(Node node) { int start = source.indexOf(node.source()); return start < 0 ? source.length() : start + node.source().length(); }
        private String slice(Node left, Token end) { int start = source.indexOf(left.source()); return source.substring(Math.max(0, start), end.end); }
    }

    private static boolean explicitContextRoot(String value) {
        return value.equals("CASE") || value.startsWith("CASE.")
                || value.equals("RUN") || value.startsWith("RUN.")
                || value.equals("ACTIONS") || value.startsWith("ACTIONS.")
                || value.equals("TOOL") || value.startsWith("TOOL.")
                || value.equals("DB") || value.startsWith("DB.")
                || value.equals("output") || value.startsWith("output.")
                || value.equals("input") || value.startsWith("input.");
    }

    private static final class WrappedNode extends BaseNode {
        private final Node value; WrappedNode(String source, Node value) { super(source); this.value = value; }
        @Override public Object evaluate(Resolver resolver) throws Exception { return value.evaluate(resolver); }
        @Override public void collectCalls(List<ToolCallParser.ParsedCall> calls) { value.collectCalls(calls); }
        @Override public void collectContextPaths(List<String> paths) { value.collectContextPaths(paths); }
    }
}
