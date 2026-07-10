/* Author: Jeffrey + ChatGPT */
package com.company.apitest.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Parses V2 comma-separated sheet and data-column specifications. */
public final class ColumnSpecParser {
    private ColumnSpecParser() {}

    public static List<SheetGroupConfig> sheets(String spec) {
        List<String> items = split(spec);
        if (items.isEmpty()) throw new IllegalArgumentException("excel.sheet is required");
        List<SheetGroupConfig> result = new ArrayList<SheetGroupConfig>();
        Set<String> ids = new LinkedHashSet<String>();
        for (String item : items) {
            int equals = unquotedIndex(item, '=');
            String id = equals < 0 ? "default" : unquote(item.substring(0, equals).trim());
            String sheet = unquote((equals < 0 ? item : item.substring(equals + 1)).trim());
            if (equals < 0 && items.size() != 1) throw new IllegalArgumentException("Group id may be omitted only for one sheet");
            if (id.isEmpty() || sheet.isEmpty()) throw new IllegalArgumentException("Sheet group id and sheet name must not be blank");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate sheet group id: " + id);
            result.add(new SheetGroupConfig(id, sheet));
        }
        return result;
    }

    public static List<DataColumnConfig> dataColumns(String spec) {
        List<DataColumnConfig> result = new ArrayList<DataColumnConfig>();
        Set<String> keys = new LinkedHashSet<String>();
        for (String raw : split(spec)) {
            String item = raw.trim();
            boolean yaml = hasYamlSuffix(item);
            if (yaml) item = item.substring(0, item.length() - 6).trim();
            int equals = unquotedIndex(item, '=');
            String key = equals < 0 ? unquote(item) : unquote(item.substring(0, equals).trim());
            String column = unquote(equals < 0 ? item : item.substring(equals + 1).trim());
            if (key.isEmpty() || column.isEmpty()) throw new IllegalArgumentException("Data column key and name must not be blank: " + raw);
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate data column key: " + key);
            result.add(new DataColumnConfig(key, column, yaml));
        }
        return result;
    }

    private static boolean hasYamlSuffix(String value) {
        if (!value.endsWith("(yaml)")) return false;
        int suffix = value.length() - 6;
        boolean quoted = false;
        for (int i = 0; i < suffix; i++) {
            if (value.charAt(i) == '"') {
                if (quoted && i + 1 < suffix && value.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            }
        }
        return !quoted;
    }

    private static List<String> split(String spec) {
        List<String> items = new ArrayList<String>();
        if (spec == null || spec.trim().isEmpty()) return items;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '"') {
                current.append(c);
                if (quoted && i + 1 < spec.length() && spec.charAt(i + 1) == '"') current.append(spec.charAt(++i));
                else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                if (!current.toString().trim().isEmpty()) items.add(current.toString().trim());
                current.setLength(0);
            } else current.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed double quote in configuration: " + spec);
        if (!current.toString().trim().isEmpty()) items.add(current.toString().trim());
        return items;
    }

    private static int unquotedIndex(String value, char wanted) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            } else if (c == wanted && !quoted) return i;
        }
        return -1;
    }

    private static String unquote(String value) {
        String text = value.trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text;
    }
}
