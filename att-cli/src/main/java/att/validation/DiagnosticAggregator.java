package att.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collapses repeated reports of one semantic validation failure while keeping
 * the number and case provenance of every occurrence.
 *
 * <p>Validation deliberately runs both package-level and case-level checks.
 * Those checks can discover the same malformed expression more than once. A
 * diagnostic is therefore grouped by its stable source/error identity, not by
 * its rendered message or Excel row. Case row and case id are occurrence
 * metadata and are never part of the grouping key.</p>
 */
public final class DiagnosticAggregator {
    private static final Pattern SYNTAX_FAILURE = Pattern.compile(
            "Expected\\s+(.+?)\\s+at\\s+expression\\s+offset\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private DiagnosticAggregator() { }

    public static List<Diagnostic> aggregate(List<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return Collections.emptyList();
        Map<DiagnosticKey, Group> groups = new LinkedHashMap<DiagnosticKey, Group>();
        int ordinal = 0;
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            DiagnosticKey key = DiagnosticKey.of(diagnostic, ordinal++);
            Group group = groups.get(key);
            if (group == null) {
                group = new Group(diagnostic);
                groups.put(key, group);
            } else {
                group.add(diagnostic);
            }
        }
        List<Diagnostic> result = new ArrayList<Diagnostic>();
        for (Group group : groups.values()) result.add(group.result());
        Collections.sort(result);
        return result;
    }

    private static final class Group {
        private Diagnostic primary;
        private int occurrences;
        private final List<Map<String, Object>> affectedCases = new ArrayList<Map<String, Object>>();
        private final java.util.Set<String> affectedCaseKeys = new java.util.LinkedHashSet<String>();

        private Group(Diagnostic first) { add(first); }

        private void add(Diagnostic diagnostic) {
            occurrences += Math.max(1, diagnostic.occurrences());
            if (primary == null || primaryScore(diagnostic) > primaryScore(primary)) primary = diagnostic;
            if (diagnostic.affectedCases().isEmpty()) {
                addOccurrence(occurrence(diagnostic));
            } else {
                for (Map<String, Object> affectedCase : diagnostic.affectedCases()) addOccurrence(affectedCase);
            }
        }

        private void addOccurrence(Map<String, Object> occurrence) {
            if (occurrence == null || occurrence.isEmpty()) return;
            String key = occurrence.toString();
            if (affectedCaseKeys.add(key)) affectedCases.add(occurrence);
        }

        private Diagnostic result() {
            return occurrences > 1 ? primary.withOccurrences(occurrences, affectedCases) : primary;
        }

        /** Prefer a package/static location over a case copy, then richer source details. */
        private static int primaryScore(Diagnostic diagnostic) {
            int score = 0;
            if (diagnostic.context().toMap().isEmpty()) score += 4;
            if (diagnostic.row() == null && diagnostic.sheet() == null) score += 2;
            if (diagnostic.source() != null) score += 2;
            if (diagnostic.field() != null) score++;
            return score;
        }

        private static Map<String, Object> occurrence(Diagnostic diagnostic) {
            Map<String, Object> context = diagnostic.context().toMap();
            boolean caseOccurrence = context.containsKey("caseFile") || context.containsKey("caseId")
                    || diagnostic.sheet() != null || diagnostic.row() != null;
            if (!caseOccurrence) return Collections.emptyMap();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> item : context.entrySet()) result.put(item.getKey(), item.getValue());
            put(result, "sheet", diagnostic.sheet());
            put(result, "row", diagnostic.row());
            put(result, "column", diagnostic.column());
            put(result, "template", diagnostic.template());
            put(result, "action", diagnostic.action());
            return result;
        }
    }

    private static final class DiagnosticKey {
        private final String severity, code, file, identity;

        private DiagnosticKey(String severity, String code, String file, String identity) {
            this.severity = severity; this.code = code; this.file = file; this.identity = identity;
        }

        private static DiagnosticKey of(Diagnostic diagnostic, int ordinal) {
            String file = normalizePath(diagnostic.file());
            if (file.isEmpty() && diagnostic.source() != null) file = normalizePath(diagnostic.source().file());
            String text = join(diagnostic.summary(), diagnostic.message(), diagnostic.detail());
            Matcher syntax = SYNTAX_FAILURE.matcher(text);
            String identity;
            if (syntax.find()) {
                // Summary wrappers and per-case suffixes differ between
                // validation phases; the source field, physical range, and
                // parser's expected token/offset are the stable identity.
                String source = diagnostic.source() == null ? "" : diagnostic.source().line() + ":"
                        + diagnostic.source().column() + ":" + diagnostic.source().endLine() + ":"
                        + diagnostic.source().endColumn();
                identity = "syntax|" + normalize(diagnostic.field()) + "|" + source + "|"
                        + normalize(syntax.group(1)) + "|" + syntax.group(2);
            } else {
                String source = diagnostic.source() == null ? "" : diagnostic.source().line() + ":"
                        + diagnostic.source().column() + ":" + diagnostic.source().endLine() + ":"
                        + diagnostic.source().endColumn();
                String field = normalize(diagnostic.field());
                // Without a file, field, or physical source range there is no
                // stable identity with which to prove that two generic messages
                // describe the same problem. Keep those diagnostics separate.
                if (file.isEmpty() && field.isEmpty() && source.isEmpty()) {
                    identity = "unlocated|" + ordinal;
                } else {
                    identity = "diagnostic|" + field + "|" + source + "|"
                            + normalize(stripCaseSource(diagnostic.summary())) + "|"
                            + normalize(stripCaseSource(diagnostic.detail()));
                }
            }
            return new DiagnosticKey(String.valueOf(diagnostic.severity()), diagnostic.code(), file, identity);
        }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof DiagnosticKey)) return false;
            DiagnosticKey other = (DiagnosticKey) value;
            return Objects.equals(severity, other.severity) && Objects.equals(code, other.code)
                    && Objects.equals(file, other.file) && Objects.equals(identity, other.identity);
        }

        @Override public int hashCode() { return Objects.hash(severity, code, file, identity); }
    }

    private static String join(String summary, String message, String detail) {
        return String.valueOf(summary) + "\n" + String.valueOf(message) + "\n" + String.valueOf(detail);
    }

    private static String stripCaseSource(String value) {
        if (value == null) return "";
        int marker = value.indexOf("; Case source:");
        if (marker < 0) marker = value.indexOf("Case source:");
        return marker < 0 ? value : value.substring(0, marker);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalizePath(String value) {
        if (value == null) return "";
        String result = value.replace('\\', '/');
        while (result.startsWith("./")) result = result.substring(2);
        return result;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) map.put(key, value);
    }
}
