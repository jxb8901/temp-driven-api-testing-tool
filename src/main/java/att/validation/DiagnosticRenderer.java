package att.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared rendering for CLI, validation and runtime evidence. No source files are read here. */
public final class DiagnosticRenderer {
    private DiagnosticRenderer() { }

    public static Map<String, Object> location(Diagnostic diagnostic) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        put(map, "file", diagnostic.file()); put(map, "field", diagnostic.field());
        put(map, "sheet", diagnostic.sheet()); put(map, "row", diagnostic.row()); put(map, "column", diagnostic.column());
        put(map, "template", diagnostic.template()); put(map, "action", diagnostic.action());
        if (diagnostic.source() != null) {
            put(map, "sourceFile", diagnostic.source().file());
            put(map, "line", diagnostic.source().line()); put(map, "sourceColumn", diagnostic.source().column());
            put(map, "endLine", diagnostic.source().endLine()); put(map, "endColumn", diagnostic.source().endColumn());
        }
        return map;
    }

    public static String exception(Diagnostic diagnostic) {
        StringBuilder out = new StringBuilder(diagnostic.code()).append(": ").append(diagnostic.summary());
        String location = locationText(diagnostic);
        if (!location.isEmpty()) out.append("\n  location: ").append(location);
        if (diagnostic.detail() != null && !diagnostic.detail().equals(diagnostic.summary()))
            lines(out, "\n  detail: ", "\n    ", diagnostic.detail());
        extras(out, diagnostic, "  ");
        return out.toString();
    }

    public static String validation(Diagnostic diagnostic) {
        StringBuilder out = new StringBuilder();
        lines(out, "  [" + diagnostic.severity() + "] " + diagnostic.code() + ": ", "\n    ", diagnostic.message());
        String location = locationText(diagnostic);
        if (!location.isEmpty()) out.append("\n    location: ").append(location);
        extras(out, diagnostic, "    ");
        return out.toString();
    }

    public static String jsonError(Diagnostic diagnostic) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("valid", false); result.putAll(diagnostic.toMap());
        return JsonSupport.write(result);
    }

    private static void extras(StringBuilder out, Diagnostic diagnostic, String indent) {
        for (Map.Entry<String, Object> item : diagnostic.context().toMap().entrySet())
            out.append('\n').append(indent).append(item.getKey()).append(": ").append(item.getValue());
        SourceLocation source = diagnostic.source();
        if (source != null && source.excerpt() != null) {
            out.append('\n').append(indent).append(source.excerpt());
            out.append('\n').append(indent);
            // A bad/untrusted column must not allocate an arbitrarily large caret line.
            int spaces = Math.min(source.column() - 1, source.excerpt().length());
            for (int index = 0; index < spaces; index++) out.append(source.excerpt().charAt(index) == '\t' ? '\t' : ' ');
            out.append('^');
        }
        if (diagnostic.occurrences() > 1) {
            out.append('\n').append(indent).append("occurrences: ").append(diagnostic.occurrences());
            if (!diagnostic.affectedCases().isEmpty()) {
                out.append('\n').append(indent).append("affected cases:");
                for (Map<String, Object> affectedCase : diagnostic.affectedCases()) {
                    out.append('\n').append(indent).append("  - ");
                    boolean first = true;
                    for (Map.Entry<String, Object> item : affectedCase.entrySet()) {
                        if (!first) out.append(", ");
                        out.append(item.getKey()).append('=').append(item.getValue());
                        first = false;
                    }
                }
            }
        }
        if (!diagnostic.schemaViolations().isEmpty()) {
            out.append('\n').append(indent).append("schemaViolations:");
            for (Map<String, Object> violation : diagnostic.schemaViolations()) {
                out.append('\n').append(indent).append("  ");
                Object path = violation.get("path");
                Object keyword = violation.get("keyword");
                Object message = violation.get("message");
                if (path != null && !String.valueOf(path).isEmpty()) out.append(path).append(" ");
                if (keyword != null && !String.valueOf(keyword).isEmpty()) out.append('[').append(keyword).append("] ");
                out.append(message == null ? violation : message);
            }
        }
        if (diagnostic.suggestion() != null)
            lines(out, "\n" + indent + "suggestion: ", "\n" + indent + "  ", diagnostic.suggestion());
    }

    private static String locationText(Diagnostic diagnostic) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Object> item : location(diagnostic).entrySet()) {
            if (out.length() > 0) out.append(", ");
            out.append(item.getKey()).append('=').append(item.getValue());
        }
        return out.toString();
    }
    private static void lines(StringBuilder out, String first, String rest, String text) {
        String[] lines = String.valueOf(text).split("\\r?\\n", -1);
        out.append(first).append(lines[0]);
        for (int i = 1; i < lines.length; i++) out.append(rest).append(lines[i]);
    }
    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) map.put(key, value);
    }
}
