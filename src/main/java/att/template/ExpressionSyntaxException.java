package att.template;

/** A parse failure with an offset relative to the expression supplied by the caller. */
public final class ExpressionSyntaxException extends IllegalArgumentException {
    private final int offset;
    private final int endOffset;
    private final String expected;
    private final String actual;
    /*
     * Keep the complete caller expression so diagnostics can identify the
     * argument that contains the offending token.  expressionStart records
     * where that expression begins in the coordinate system of the current
     * caller; this matters when a nested expression is shifted by its parent.
     */
    private final String expression;
    private final int expressionStart;

    public ExpressionSyntaxException(int offset, int endOffset, String expected, String actual) {
        this(offset, endOffset, expected, actual, null, 0);
    }

    private ExpressionSyntaxException(int offset, int endOffset, String expected, String actual,
                                      String expression, int expressionStart) {
        super("Expected " + expected + " but found " + actual + " at expression offset " + offset);
        this.offset = Math.max(0, offset);
        this.endOffset = Math.max(this.offset, endOffset);
        this.expected = expected;
        this.actual = actual;
        this.expression = expression;
        this.expressionStart = Math.max(0, expressionStart);
    }

    public int offset() { return offset; }
    public int endOffset() { return endOffset; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    public String expression() { return expression; }

    public ExpressionSyntaxException shifted(int amount) {
        if (amount == 0) return this;
        return new ExpressionSyntaxException(offset + amount, endOffset + amount, expected, actual,
                expression, expressionStart + amount);
    }

    /** Binds the complete caller expression after parser offsets have been normalised. */
    public ExpressionSyntaxException withExpression(String value) {
        return new ExpressionSyntaxException(offset, endOffset, expected, actual, value, 0);
    }

    /**
     * Returns a bounded, non-secret diagnostic context for expression syntax
     * failures.  It deliberately reports only the argument name, token and a
     * small local excerpt rather than dumping the complete call (which may
     * contain credentials or other sensitive values).
     */
    public String diagnosticDetail() {
        if (expression == null || expression.isEmpty()) return null;
        int relativeOffset = offset - expressionStart;
        if (relativeOffset < 0 || relativeOffset > expression.length()) return null;

        String argument = new ToolCallParser().argumentAt(expression, relativeOffset);
        StringBuilder detail = new StringBuilder();
        if (argument != null) detail.append("argument=").append(argument);
        if (actual != null && !actual.trim().isEmpty()) {
            if (detail.length() > 0) detail.append('\n');
            detail.append("unexpected token: ").append(actual);
        }

        int relativeEnd = Math.max(relativeOffset + 1, endOffset - expressionStart);
        int from = Math.max(0, relativeOffset - 28);
        int to = Math.min(expression.length(), relativeEnd + 28);
        if (to > from) {
            String excerpt = expression.substring(from, to).replace('\r', ' ').replace('\n', ' ');
            if (!sensitiveExcerpt(excerpt, argument)) {
                if (detail.length() > 0) detail.append('\n');
                detail.append("near: ").append(excerpt);
                detail.append('\n').append("      ");
                for (int index = 0; index < relativeOffset - from; index++) detail.append(' ');
                detail.append('^');
            }
        }
        if (argument != null && looksLikePathArgument(argument) && actual != null && actual.indexOf("'/'") >= 0) {
            detail.append('\n').append("hint: quote path or text values in calls, for example outputPrefix='${CASE.outputDirectory}/CT001' or logFiles=['/fpp/log/...'].");
        }
        return detail.length() == 0 ? null : detail.toString();
    }

    private static boolean sensitiveExcerpt(String excerpt, String argument) {
        String value = ((argument == null ? "" : argument) + " " + excerpt).toLowerCase(java.util.Locale.ROOT);
        return value.contains("password") || value.contains("secret") || value.contains("token")
                || value.contains("credential") || value.contains("authorization") || value.contains("bearer")
                || value.contains("apikey") || value.contains("accesskey") || value.contains("privatekey");
    }

    private static boolean looksLikePathArgument(String argument) {
        if (argument == null) return false;
        String value = argument.toLowerCase(java.util.Locale.ROOT);
        return value.contains("file") || value.contains("path") || value.contains("dir") || value.contains("log");
    }

    public static ExpressionSyntaxException find(Throwable value) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = value; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof ExpressionSyntaxException) return (ExpressionSyntaxException) current;
        }
        return null;
    }
}
