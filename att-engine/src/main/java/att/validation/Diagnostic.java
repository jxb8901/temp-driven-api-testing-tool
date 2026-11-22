package att.validation;

/** Stable machine-readable V2.1 validation diagnostic. */
public final class Diagnostic implements Comparable<Diagnostic> {
    public enum Severity { ERROR, WARNING, INFO }
    private final String code, message, file, field, sheet, template, action, suggestion;
    private final Severity severity;
    private final Integer row, column;
    private final String summary, detail;
    private final SourceLocation source;
    private final DiagnosticContext context;
    private final java.util.List<java.util.Map<String, Object>> schemaViolations;
    private final int occurrences;
    private final java.util.List<java.util.Map<String, Object>> affectedCases;
    private final java.util.Map<String, Object> metadata;

    public Diagnostic(String code, Severity severity, String message, String file, String sheet,
                      Integer row, Integer column, String template, String action) {
        this(code, severity, message, file, null, sheet, row, column, template, action, null);
    }
    public Diagnostic(String code, Severity severity, String message, String file, String field, String sheet,
                      Integer row, Integer column, String template, String action, String suggestion) {
        this(code, severity, message, file, field, sheet, row, column, template, action, suggestion,
                null, null, null, DiagnosticContext.EMPTY, java.util.Collections.<java.util.Map<String, Object>>emptyList());
    }
    public Diagnostic(String code, Severity severity, String message, String file, String field, String sheet,
                      Integer row, Integer column, String template, String action, String suggestion,
                      String summary, String detail, SourceLocation source, DiagnosticContext context) {
        this(code, severity, message, file, field, sheet, row, column, template, action, suggestion,
                summary, detail, source, context, java.util.Collections.<java.util.Map<String, Object>>emptyList(),
                1, java.util.Collections.<java.util.Map<String, Object>>emptyList());
    }
    public Diagnostic(String code, Severity severity, String message, String file, String field, String sheet,
                      Integer row, Integer column, String template, String action, String suggestion,
                      String summary, String detail, SourceLocation source, DiagnosticContext context,
                      java.util.List<java.util.Map<String, Object>> schemaViolations) {
        this(code, severity, message, file, field, sheet, row, column, template, action, suggestion,
                summary, detail, source, context, schemaViolations, 1,
                java.util.Collections.<java.util.Map<String, Object>>emptyList());
    }
    private Diagnostic(String code, Severity severity, String message, String file, String field, String sheet,
                       Integer row, Integer column, String template, String action, String suggestion,
                       String summary, String detail, SourceLocation source, DiagnosticContext context,
                       java.util.List<java.util.Map<String, Object>> schemaViolations,
                       int occurrences, java.util.List<java.util.Map<String, Object>> affectedCases) {
        this(code, severity, message, file, field, sheet, row, column, template, action, suggestion,
                summary, detail, source, context, schemaViolations, occurrences, affectedCases,
                java.util.Collections.<String, Object>emptyMap());
    }
    private Diagnostic(String code, Severity severity, String message, String file, String field, String sheet,
                       Integer row, Integer column, String template, String action, String suggestion,
                       String summary, String detail, SourceLocation source, DiagnosticContext context,
                       java.util.List<java.util.Map<String, Object>> schemaViolations,
                       int occurrences, java.util.List<java.util.Map<String, Object>> affectedCases,
                       java.util.Map<String, Object> metadata) {
        this.code = code; this.severity = severity; this.message = message == null ? null : att.security.SecretProtection.text(message); this.file = file;
        this.field = field; this.sheet = sheet; this.row = row; this.column = column; this.template = template; this.action = action; this.suggestion = suggestion;
        this.summary = summary == null ? null : att.security.SecretProtection.text(summary); this.detail = detail == null ? null : att.security.SecretProtection.text(detail); this.source = source;
        this.context = context == null ? DiagnosticContext.EMPTY : context;
        java.util.List<java.util.Map<String, Object>> copied = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (schemaViolations != null) for (java.util.Map<String, Object> violation : schemaViolations)
            copied.add(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>(violation)));
        this.schemaViolations = java.util.Collections.unmodifiableList(copied);
        this.occurrences = Math.max(1, occurrences);
        java.util.List<java.util.Map<String, Object>> cases = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (affectedCases != null) for (java.util.Map<String, Object> affectedCase : affectedCases)
            cases.add(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>(affectedCase)));
        this.affectedCases = java.util.Collections.unmodifiableList(cases);
        this.metadata = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>(metadata));
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
    public String summary() { return summary == null ? message : summary; }
    public String detail() { return detail; }
    public SourceLocation source() { return source; }
    public DiagnosticContext context() { return context; }
    public java.util.List<java.util.Map<String, Object>> schemaViolations() { return schemaViolations; }
    public int occurrences() { return occurrences; }
    public java.util.List<java.util.Map<String, Object>> affectedCases() { return affectedCases; }

    /**
     * Returns this diagnostic with aggregation metadata. The original location,
     * message, and first occurrence context remain the primary diagnostic.
     */
    public Diagnostic withOccurrences(int count, java.util.List<java.util.Map<String, Object>> cases) {
        if (count <= 1 && (cases == null || cases.isEmpty())) return this;
        return new Diagnostic(code, severity, message, file, field, sheet, row, column, template, action, suggestion,
                summary, detail, source, context, schemaViolations, count, cases);
    }

    /** Adds stable machine fields without changing the primary human-readable cause. */
    public Diagnostic withMetadata(java.util.Map<String, Object> values) {
        if (values == null || values.isEmpty()) return this;
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<String, Object>(metadata);
        java.util.Set<String> allowed = new java.util.HashSet<String>(java.util.Arrays.asList(
                "limitName", "configuredLimit", "actual", "observed", "usage", "limit", "threshold",
                "scope", "outcome", "stage", "remediationHint", "artifactRef"));
        for (java.util.Map.Entry<String, Object> entry : values.entrySet())
            if (allowed.contains(entry.getKey())) merged.put(entry.getKey(), entry.getValue());
        return new Diagnostic(code, severity, message, file, field, sheet, row, column, template, action, suggestion,
                summary, detail, source, context, schemaViolations, occurrences, affectedCases, merged);
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        map.put("code", code); map.put("severity", severity.name()); map.put("message", message); map.put("file", file); map.put("field", field); map.put("sheet", sheet); map.put("row", row); map.put("column", column); map.put("template", template); map.put("action", action); map.put("suggestion", suggestion);
        if (summary != null) map.put("summary", summary);
        if (detail != null) map.put("detail", detail);
        if (source != null) map.put("source", source.toMap());
        if (!context.toMap().isEmpty()) map.put("context", context.toMap());
        if (!schemaViolations.isEmpty()) map.put("schemaViolations", schemaViolations);
        if (occurrences > 1) {
            map.put("occurrences", occurrences);
            if (!affectedCases.isEmpty()) map.put("affectedCases", affectedCases);
        }
        map.putAll(metadata);
        return map;
    }

    /** Reconstructs the complete diagnostic contract from its persisted map. */
    @SuppressWarnings("unchecked")
    public static Diagnostic fromMap(java.util.Map<String, ?> map) {
        if (map == null) return null;
        String code = text(map.get("code"));
        Severity severity;
        try { severity = Severity.valueOf(text(map.get("severity"))); }
        catch (RuntimeException invalid) { severity = Severity.ERROR; }
        SourceLocation source = null;
        Object sourceValue = map.get("source");
        if (sourceValue instanceof java.util.Map) {
            java.util.Map<String, ?> value = (java.util.Map<String, ?>) sourceValue;
            int line = integer(value.get("line"), 1), column = integer(value.get("column"), 1);
            source = new SourceLocation(text(value.get("file")), line, column,
                    integer(value.get("endLine"), line), integer(value.get("endColumn"), column),
                    nullableText(value.get("excerpt")), integer(value.get("excerptStartLine"), line));
        }
        DiagnosticContext context = DiagnosticContext.EMPTY;
        Object contextValue = map.get("context");
        if (contextValue instanceof java.util.Map) {
            java.util.Map<String, ?> value = (java.util.Map<String, ?>) contextValue;
            context = new DiagnosticContext(nullableText(value.get("caseFile")), nullableText(value.get("caseId")),
                    nullableText(value.get("stage")), nullableText(value.get("flowId")), strings(value.get("callChain")));
        }
        java.util.List<java.util.Map<String, Object>> violations = objectMaps(map.get("schemaViolations"));
        java.util.List<java.util.Map<String, Object>> cases = objectMaps(map.get("affectedCases"));
        int occurrences = integer(map.get("occurrences"), 1);
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
        java.util.Set<String> known = new java.util.HashSet<String>(java.util.Arrays.asList(
                "code", "severity", "message", "file", "field", "sheet", "row", "column", "template", "action",
                "suggestion", "summary", "detail", "source", "context", "schemaViolations", "occurrences", "affectedCases"));
        for (java.util.Map.Entry<String, ?> entry : map.entrySet())
            if (!known.contains(entry.getKey())) metadata.put(entry.getKey(), entry.getValue());
        return new Diagnostic(code, severity, nullableText(map.get("message")), nullableText(map.get("file")),
                nullableText(map.get("field")), nullableText(map.get("sheet")), nullableInteger(map.get("row")),
                nullableInteger(map.get("column")), nullableText(map.get("template")), nullableText(map.get("action")),
                nullableText(map.get("suggestion")), nullableText(map.get("summary")), nullableText(map.get("detail")),
                source, context, violations, occurrences, cases, metadata);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String nullableText(Object value) { return value == null ? null : String.valueOf(value); }
    private static Integer nullableInteger(Object value) {
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static int integer(Object value, int fallback) {
        Integer parsed = nullableInteger(value);
        return parsed == null ? fallback : parsed.intValue();
    }
    private static java.util.List<String> strings(Object value) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(String.valueOf(item));
        return result;
    }
    @SuppressWarnings("unchecked")
    private static java.util.List<java.util.Map<String, Object>> objectMaps(Object value) {
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (value instanceof Iterable) for (Object item : (Iterable<?>) value)
            if (item instanceof java.util.Map) result.add(new java.util.LinkedHashMap<String, Object>((java.util.Map<String, Object>) item));
        return result;
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
