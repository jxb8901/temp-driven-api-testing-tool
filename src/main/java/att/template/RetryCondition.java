package att.template;

import att.core.CaseRuntimeContext;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;

/** Side-effect-free boolean conditions for the completed-attempt retry lifecycle. */
public final class RetryCondition {
    private RetryCondition() { }
    public static void validate(Object configured) {
        String expression = expression(configured);
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        try {
            if (expression.contains("&{")) throw new IllegalArgumentException("File expressions are unavailable in retry.when");
            engine.validateValueSyntax(expression);
            for (ToolCallParser.ParsedCall call : engine.parseCalls(expression)) {
                if (!DefaultBuiltInProvider.isSafeForBootstrap(call.name()))
                    throw new IllegalArgumentException("retry.when permits only deterministic pure built-ins; call is forbidden: " + call.name());
                engine.validateBuiltInCall(call);
            }
            if (expression.startsWith("#{") && expression.endsWith("}")) engine.validateExpressionBlockSyntax(expression);
            else if (!(expression.startsWith("${") && engine.parseContextPaths(expression).size() == 1
                    && expression.endsWith("}") && expression.indexOf("}", 2) == expression.length() - 1))
                new ExpressionEvaluator().validateSyntax(expression);
            if (engine.parseContextPaths(expression).isEmpty()) evaluateValidated(expression, null);
        } catch (Exception error) { throw diagnostic(error); }
    }

    public static boolean evaluate(Object configured, CaseRuntimeContext context) {
        String expression = expression(configured);
        // Enforce policy for programmatically-created Actions that bypass package validation.
        validate(configured);
        try { return evaluateValidated(expression, context); }
        catch (Exception error) { throw diagnostic(error); }
    }

    private static boolean evaluateValidated(String expression, CaseRuntimeContext context) throws Exception {
        if ((expression.startsWith("#{") || expression.startsWith("${")) && expression.endsWith("}")) {
            Object value = new UnifiedTemplateEngine(null).evaluate(expression, context, null);
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("retry.when must evaluate to Boolean");
            return ((Boolean) value).booleanValue();
        }
        return context == null ? new ExpressionEvaluator().evaluate(expression)
                : new ExpressionEvaluator().evaluate(expression, context);
    }

    private static String expression(Object configured) {
        if (!(configured instanceof String) || ((String) configured).trim().isEmpty())
            throw diagnostic(new IllegalArgumentException("retry.when must be a non-empty boolean expression String"));
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
