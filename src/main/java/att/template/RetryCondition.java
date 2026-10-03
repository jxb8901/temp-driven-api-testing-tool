package att.template;

import att.core.CaseRuntimeContext;
import att.core.TestCase;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Pure Boolean conditions over completed-attempt state, using ATT's normal typed parser. */
public final class RetryCondition {
    private RetryCondition() { }

    public static void validate(Object configured) {
        String expression = expression(configured);
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        try {
            if (expression.contains("&{")) throw new IllegalArgumentException("File expressions are unavailable in retry.when");
            engine.validateValueSyntax(expression);
            ExpressionBlockEvaluator.CompiledExpression compiled = new ExpressionBlockEvaluator().compile(expression);
            for (ToolCallParser.ParsedCall call : compiled.calls()) validateCall(engine, call);
            // Authored expression blocks inside interpolated string literals must follow the same policy.
            for (int start = expression.indexOf("#{"); start >= 0; start = expression.indexOf("#{", start + 2))
                for (ToolCallParser.ParsedCall call : engine.parseCalls(expression.substring(start))) validateCall(engine, call);
            if (engine.parseContextPaths(expression).isEmpty()) evaluateValidated(expression, constantContext());
        } catch (Exception error) { throw diagnostic(error); }
    }

    /** Parses and validates one retry predicate for reuse by a Load run plan. */
    public static CompiledCondition compile(Object configured) {
        String expression = expression(configured);
        validate(configured);
        return new CompiledCondition(expression, new ExpressionBlockEvaluator().compile(expression));
    }

    public static boolean evaluate(CompiledCondition compiled, CaseRuntimeContext context) {
        if (compiled == null) throw new IllegalArgumentException("Compiled retry.when is required");
        try { return evaluateValidated(compiled.expression, compiled.expressionPlan, context == null ? constantContext() : context); }
        catch (Exception error) { throw diagnostic(error); }
    }

    public static final class CompiledCondition {
        private final String expression;
        private final ExpressionBlockEvaluator.CompiledExpression expressionPlan;
        private CompiledCondition(String expression, ExpressionBlockEvaluator.CompiledExpression expressionPlan) {
            this.expression = expression; this.expressionPlan = expressionPlan;
        }
        public String expression() { return expression; }
    }

    public static boolean evaluate(Object configured, CaseRuntimeContext context) {
        String expression = expression(configured);
        validate(configured); // Programmatically-created Actions also obey the authoring policy.
        try { return evaluateValidated(expression, context == null ? constantContext() : context); }
        catch (Exception error) { throw diagnostic(error); }
    }

    private static void validateCall(UnifiedTemplateEngine engine, ToolCallParser.ParsedCall call) {
        requirePure(call.name());
        engine.validateBuiltInCall(call);
    }

    private static void requirePure(String name) {
        if (!DefaultBuiltInProvider.isSafeForBootstrap(name))
            throw new IllegalArgumentException("retry.when permits only deterministic pure built-ins; call is forbidden: " + name);
    }

    private static UnifiedTemplateEngine engine() {
        final DefaultBuiltInProvider delegate = new DefaultBuiltInProvider();
        BuiltInProvider pure = new BuiltInProvider() {
            @Override public Set<String> names() { return delegate.names(); }
            @Override public Object invoke(String name, Map<String,Object> input) {
                requirePure(name); // Defense in depth for interpolation inside authored literals.
                return delegate.invoke(name, input);
            }
        };
        return new UnifiedTemplateEngine(null, null, null, null, pure);
    }

    private static boolean evaluateValidated(String expression, final CaseRuntimeContext context) throws Exception {
        return evaluateValidated(expression, new ExpressionBlockEvaluator().compile(expression), context);
    }

    private static boolean evaluateValidated(String expression,
                                             ExpressionBlockEvaluator.CompiledExpression compiled,
                                             final CaseRuntimeContext context) throws Exception {
        final UnifiedTemplateEngine engine = engine();
        final DefaultBuiltInProvider builtins = new DefaultBuiltInProvider();
        Object value = compiled.evaluate(new ExpressionBlockEvaluator.Resolver() {
            @Override public Object context(String path) { return context.require(path); }
            @Override public Object contextOptional(String path) { return context.requireOptional(path); }
            @Override public boolean hasContext(String path) { return context.contains(path); }
            @Override public Object call(String name, Map<String,Object> arguments) {
                requirePure(name);
                return builtins.invoke(name, arguments);
            }
            @Override public String interpolate(String text) throws Exception { return engine.render(text, context, null); }
        });
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("retry.when must evaluate to Boolean");
        return ((Boolean) value).booleanValue();
    }

    private static CaseRuntimeContext constantContext() {
        // Pure constant evaluation needs only the engine's metadata scope; no files or resources are opened.
        Path root = Paths.get(".").toAbsolutePath().normalize();
        TestCase test = new TestCase(1, "validation", "retry", "constant", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        return new CaseRuntimeContext(test, root, "validation", root, root.resolve("case.log"));
    }

    private static String expression(Object configured) {
        if (!(configured instanceof String) || ((String) configured).trim().isEmpty())
            throw diagnostic(new IllegalArgumentException("retry.when must be a non-empty Boolean expression String"));
        return ((String) configured).trim();
    }

    private static DiagnosticException diagnostic(Exception error) {
        DiagnosticException typed = DiagnosticException.find(error);
        if (typed != null) return new DiagnosticException(typed.code(), typed.summary(), typed.detail(),
                null, "retry.when", null, null, null, null, null, typed.suggestion(), error);
        return new DiagnosticException(DiagnosticCodes.TEMPLATE_INVALID,
                "Invalid retry.when expression", error.getMessage(), null, "retry.when",
                null, null, null, null, null,
                "Use a Boolean expression over current attempt output and valid Context paths; only pure deterministic built-ins are allowed.", error);
    }
}
