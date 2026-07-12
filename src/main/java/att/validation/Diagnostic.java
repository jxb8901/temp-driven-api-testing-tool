package att.validation;

/** Stable machine-readable V2.1 validation diagnostic. */
public final class Diagnostic implements Comparable<Diagnostic> {
    public enum Severity { ERROR, WARNING, INFO }
    private final String code, message, file, field, sheet, template, action, suggestion;
    private final Severity severity;
    private final Integer row, column;

    public Diagnostic(String code, Severity severity, String message, String file, String sheet,
                      Integer row, Integer column, String template, String action) {
        this(code, severity, message, file, null, sheet, row, column, template, action, null);
    }
    public Diagnostic(String code, Severity severity, String message, String file, String field, String sheet,
                      Integer row, Integer column, String template, String action, String suggestion) {
        this.code = code; this.severity = severity; this.message = message; this.file = file;
        this.field = field; this.sheet = sheet; this.row = row; this.column = column; this.template = template; this.action = action; this.suggestion = suggestion;
    }
    public String code() { return code; }
    public Severity severity() { return severity; }
    public String message() { return message; }
    public String file() { return file; }
    public String field() { return field; }
    public String sheet() { return sheet; }
    public Integer row() { return row; }
    public Integer column() { return column; }
    public String template() { return template; }
    public String action() { return action; }
    public String suggestion() { return suggestion; }
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        map.put("code", code); map.put("severity", severity.name()); map.put("message", message); map.put("file", file); map.put("field", field); map.put("sheet", sheet); map.put("row", row); map.put("column", column); map.put("template", template); map.put("action", action); map.put("suggestion", suggestion); return map;
    }
    public String toJson() {
        return JsonSupport.write(toMap());
    }
    @Override public int compareTo(Diagnostic other) {
        int value = safe(file).compareTo(safe(other.file));
        if (value == 0) value = integer(row).compareTo(integer(other.row));
        if (value == 0) value = integer(column).compareTo(integer(other.column));
        if (value == 0) value = code.compareTo(other.code);
        return value == 0 ? message.compareTo(other.message) : value;
    }
    private static Integer integer(Integer value) { return value == null ? Integer.MAX_VALUE : value; }
    private static String safe(String value) { return value == null ? "" : value; }
}
