package att.worker.internal;

/** Compatibility facade; sanitization is shared through att-server-api. */
public final class DiagnosticSanitizer {
    private DiagnosticSanitizer() { }
    public static java.util.Map<String,Object> sanitize(java.util.Map<?,?> source) { return att.server.api.internal.DiagnosticSanitizer.sanitize(source); }
    public static String redactText(String value) { return att.server.api.internal.DiagnosticSanitizer.redactText(value); }
    public static Object sanitizeValue(Object value) { return att.server.api.internal.DiagnosticSanitizer.sanitizeValue(value); }
}
