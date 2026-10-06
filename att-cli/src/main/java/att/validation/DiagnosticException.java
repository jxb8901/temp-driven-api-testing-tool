/* Author: Jeffrey + ChatGPT */
package att.validation;

/**
 * Typed user-facing failure carrying the same location fields as validation diagnostics.
 * It prevents CLI/runtime code from guessing diagnostic categories from message text.
 */
public class DiagnosticException extends IllegalArgumentException {
    private final String code;
    private final String summary;
    private final String detail;
    private final String file;
    private final String field;
    private final String sheet;
    private final Integer row;
    private final Integer column;
    private final String template;
    private final String action;
    private final String suggestion;
    private final SourceLocation source;
    private final DiagnosticContext context;
    private final java.util.List<java.util.Map<String, Object>> schemaViolations;

    public DiagnosticException(String code, String summary, String detail, String file, String field,
                               String sheet, Integer row, Integer column, String template, String action,
                               String suggestion, Throwable cause) {
        this(code, summary, detail, file, field, sheet, row, column, template, action, suggestion, cause, null, DiagnosticContext.EMPTY,
                java.util.Collections.<java.util.Map<String, Object>>emptyList());
    }

    private DiagnosticException(String code, String summary, String detail, String file, String field,
                                String sheet, Integer row, Integer column, String template, String action,
                                String suggestion, Throwable cause, SourceLocation source, DiagnosticContext context,
                                java.util.List<java.util.Map<String, Object>> schemaViolations) {
        super(summary, cause);
        this.code = required(code, "diagnostic code");
        this.summary = required(summary, "diagnostic summary");
        this.detail = blankToNull(detail);
        this.file = blankToNull(file);
        this.field = blankToNull(field);
        this.sheet = blankToNull(sheet);
        this.row = row;
        this.column = column;
        this.template = blankToNull(template);
        this.action = blankToNull(action);
        this.suggestion = blankToNull(suggestion);
        this.source = source;
        this.context = context;
        java.util.List<java.util.Map<String, Object>> copied = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (schemaViolations != null) for (java.util.Map<String, Object> violation : schemaViolations)
            copied.add(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>(violation)));
        this.schemaViolations = java.util.Collections.unmodifiableList(copied);
    }

    public static DiagnosticException of(String code, String summary, String detail, String suggestion) {
        return new DiagnosticException(code, summary, detail, null, null, null, null, null, null, null, suggestion, null);
    }

    public static DiagnosticException wrap(String code, String summary, Throwable cause, String file,
                                           String field, String suggestion) {
        DiagnosticException typed = find(cause);
        if (typed != null) return typed.withLocation(file, field, null, null, null, null, null);
        return new DiagnosticException(code, summary, causeMessage(cause), file, field, null, null, null,
                null, null, suggestion, cause);
    }

    public DiagnosticException withLocation(String file, String field, String sheet, Integer row, Integer column,
                                            String template, String action) {
        return new DiagnosticException(code, summary, detail,
                first(this.file, file), first(this.field, field), first(this.sheet, sheet),
                this.row == null ? row : this.row, this.column == null ? column : this.column,
                first(this.template, template), first(this.action, action), suggestion, this, source, context, schemaViolations);
    }

    /** Attach fallback provenance without overwriting the innermost error location. */
    public DiagnosticException withSource(SourceLocation fallback) {
        return new DiagnosticException(code, summary, detail, first(file, fallback == null ? null : fallback.file()),
                field, sheet, row, column, template, action, suggestion, this, source == null ? fallback : source, context, schemaViolations);
    }

    public DiagnosticException withContext(DiagnosticContext fallback) {
        return new DiagnosticException(code, summary, detail, file, field, sheet, row, column, template, action,
                suggestion, this, source, context.withFallback(fallback), schemaViolations);
    }

    /** Add a stable detail line without changing the error's identity or source. */
    public DiagnosticException withDetail(String extra) {
        if (extra == null || extra.trim().isEmpty()) return this;
        String combined = detail == null || detail.trim().isEmpty() ? extra : detail + "\n" + extra;
        return new DiagnosticException(code, summary, combined, file, field, sheet, row, column, template, action,
                suggestion, this, source, context, schemaViolations);
    }

    /** Bind a semantic error to its source field once; outer callers must not relocate it. */
    public DiagnosticException atSource(String file, String field) {
        if (this.file != null) return this;
        return new DiagnosticException(code, summary, detail, file, field, sheet, row, column, template, action,
                suggestion, this, source, context, schemaViolations);
    }

    /** Preserve validator-native paths for JSON and CI consumers instead of flattening them into text. */
    public DiagnosticException withSchemaViolations(java.util.List<JsonSchemaVerifier.SchemaViolation> violations) {
        if (violations == null || violations.isEmpty()) return this;
        java.util.List<java.util.Map<String, Object>> maps = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (JsonSchemaVerifier.SchemaViolation violation : violations) maps.add(violation.toMap());
        return new DiagnosticException(code, summary, detail, file, field, sheet, row, column, template, action,
                suggestion, this, source, context, maps);
    }

    public static DiagnosticException find(Throwable value) {
        Throwable current = value;
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && visited.add(current)) {
            if (current instanceof DiagnosticException) return (DiagnosticException) current;
            current = current.getCause();
        }
        return null;
    }

    public String code() { return code; }
    public String summary() { return summary; }
    public String detail() { return detail; }
    public String file() { return file; }
    public String field() { return field; }
    public String sheet() { return sheet; }
    public Integer row() { return row; }
    public Integer column() { return column; }
    public String template() { return template; }
    public String action() { return action; }
    public String suggestion() { return suggestion; }
    public SourceLocation source() { return source; }
    public DiagnosticContext context() { return context; }
    public java.util.List<java.util.Map<String, Object>> schemaViolations() { return schemaViolations; }

    public Diagnostic toDiagnostic() {
        String message = detail == null ? summary : summary + ": " + detail;
        return new Diagnostic(code, Diagnostic.Severity.ERROR, message, file, field, sheet, row, column,
                template, action, suggestion, summary, detail, source, context, schemaViolations);
    }

    public String format() {
        return DiagnosticRenderer.exception(toDiagnostic());
    }

    @Override public String getMessage() {
        StringBuilder output = new StringBuilder(summary);
        if (detail != null && !detail.equals(summary)) output.append("; ").append(detail);
        if (suggestion != null) output.append("; suggestion: ").append(suggestion);
        return output.toString();
    }

    private static String causeMessage(Throwable cause) {
        if (cause == null) return null;
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) message = cause.getClass().getSimpleName();
        att.template.ExpressionSyntaxException syntax = att.template.ExpressionSyntaxException.find(cause);
        if (syntax != null) {
            String detail = syntax.diagnosticDetail();
            if (detail != null && !detail.trim().isEmpty() && message.indexOf(detail) < 0)
                message = message + "\n" + detail;
        }
        return message;
    }

    private static String first(String current, String fallback) { return current == null ? blankToNull(fallback) : current; }
    private static String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value; }
    private static String required(String value, String owner) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(owner + " is required");
        return value;
    }
}
