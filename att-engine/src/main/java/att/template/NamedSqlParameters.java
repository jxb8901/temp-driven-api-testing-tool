/* Author: Jeffrey + ChatGPT */
package att.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts safe :name placeholders to JDBC positional bindings without interpolating values. */
public final class NamedSqlParameters {
    private NamedSqlParameters() { }

    public static Binding bind(String sql, Map<String, ?> parameters) {
        if (sql == null) throw new IllegalArgumentException("SQL must not be null");
        Map<String, ?> supplied = parameters == null ? Collections.<String, Object>emptyMap() : parameters;
        StringBuilder jdbc = new StringBuilder(sql.length());
        List<Object> values = new ArrayList<Object>();
        List<String> names = new ArrayList<String>();
        Set<String> used = new LinkedHashSet<String>();
        boolean single = false, dual = false, lineComment = false, blockComment = false;
        for (int index = 0; index < sql.length();) {
            char ch = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (lineComment) {
                jdbc.append(ch); index++;
                if (ch == '\n' || ch == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                jdbc.append(ch); index++;
                if (ch == '*' && next == '/') { jdbc.append(next); index++; blockComment = false; }
                continue;
            }
            if (!single && !dual && ch == '-' && next == '-') { jdbc.append(ch).append(next); index += 2; lineComment = true; continue; }
            if (!single && !dual && ch == '/' && next == '*') { jdbc.append(ch).append(next); index += 2; blockComment = true; continue; }
            if (ch == '\'' && !dual) {
                jdbc.append(ch); index++;
                if (single && next == '\'') { jdbc.append(next); index++; }
                else single = !single;
                continue;
            }
            if (ch == '"' && !single) {
                jdbc.append(ch); index++;
                if (dual && next == '"') { jdbc.append(next); index++; }
                else dual = !dual;
                continue;
            }
            if (!single && !dual && ch == ':' && next == ':') { jdbc.append("::"); index += 2; continue; }
            if (!single && !dual && ch == ':' && identifierStart(next)) {
                int end = index + 2;
                while (end < sql.length() && identifierPart(sql.charAt(end))) end++;
                String name = sql.substring(index + 1, end);
                if (!supplied.containsKey(name)) throw new IllegalArgumentException("Missing named SQL parameter ':" + name + "'");
                jdbc.append('?');
                names.add(name); values.add(supplied.get(name)); used.add(name);
                index = end;
                continue;
            }
            jdbc.append(ch); index++;
        }
        if (single || dual || blockComment) throw new IllegalArgumentException("Unclosed SQL quote or block comment");
        Set<String> unused = new LinkedHashSet<String>(supplied.keySet());
        unused.removeAll(used);
        if (!unused.isEmpty()) throw new IllegalArgumentException("Unused named SQL parameters: " + unused);
        return new Binding(jdbc.toString(), names, values);
    }

    private static boolean identifierStart(char value) { return value == '_' || Character.isLetter(value); }
    private static boolean identifierPart(char value) { return value == '_' || Character.isLetterOrDigit(value); }

    public static final class Binding {
        private final String sql;
        private final List<String> names;
        private final List<Object> values;
        private Binding(String sql, List<String> names, List<Object> values) {
            this.sql = sql;
            this.names = Collections.unmodifiableList(new ArrayList<String>(names));
            this.values = Collections.unmodifiableList(new ArrayList<Object>(values));
        }
        public String sql() { return sql; }
        public List<String> names() { return names; }
        public List<Object> values() { return values; }
    }
}
