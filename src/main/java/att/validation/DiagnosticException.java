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

    public DiagnosticException(String code, String summary, String detail, String file, String field,
                               String sheet, Integer row, Integer column, String template, String action,
                               String suggestion, Throwable cause) {
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
                first(this.template, template), first(this.action, action), suggestion, this);
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

    public Diagnostic toDiagnostic() {
        String message = detail == null ? summary : summary + ": " + detail;
        return new Diagnostic(code, Diagnostic.Severity.ERROR, message, file, field, sheet, row, column,
                template, action, suggestion);
    }

    public String format() {
        StringBuilder output = new StringBuilder(code).append(": ").append(summary);
        String location = location();
        if (!location.isEmpty()) output.append("\n  location: ").append(location);
        if (detail != null && !detail.equals(summary)) appendMultiline(output, "detail", detail);
        if (suggestion != null) output.append("\n  suggestion: ").append(suggestion);
        return output.toString();
    }

    private static void appendMultiline(StringBuilder output, String label, String value) {
        String[] lines = value.split("\\r?\\n", -1);
        output.append("\n  ").append(label).append(": ").append(lines[0]);
        for (int index = 1; index < lines.length; index++) output.append("\n    ").append(lines[index]);
    }

    @Override public String getMessage() {
        StringBuilder output = new StringBuilder(summary);
        if (detail != null && !detail.equals(summary)) output.append("; ").append(detail);
        if (suggestion != null) output.append("; suggestion: ").append(suggestion);
        return output.toString();
    }

    private String location() {
        StringBuilder output = new StringBuilder();
        append(output, "file", file); append(output, "field", field); append(output, "sheet", sheet);
        append(output, "row", row); append(output, "column", column); append(output, "template", template);
        append(output, "action", action);
        return output.toString();
    }

    private static void append(StringBuilder output, String name, Object value) {
        if (value == null || String.valueOf(value).isEmpty()) return;
        if (output.length() > 0) output.append(", ");
        output.append(name).append('=').append(value);
    }

    private static String causeMessage(Throwable cause) {
        if (cause == null) return null;
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
    }

    private static String first(String current, String fallback) { return current == null ? blankToNull(fallback) : current; }
    private static String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value; }
    private static String required(String value, String owner) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(owner + " is required");
        return value;
    }
}
