/* Author: Jeffrey + ChatGPT */
package att.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders the stable typed DB result as deterministic SQL*Plus-style text. */
final class DbTextResultFormatter {
    String format(Object value) {
        if (!(value instanceof Map)) throw invalid("result must be a map");
        Map<?, ?> result = (Map<?, ?>) value;
        String operation = String.valueOf(result.get("operation"));
        if ("query".equals(operation)) return query(result);
        if ("update".equals(operation)) return update(result);
        throw invalid("operation must be query or update");
    }

    private String query(Map<?, ?> result) {
        Object rowsValue = result.get("rows");
        if (!(rowsValue instanceof List)) throw invalid("query rows must be a list");
        List<?> rows = (List<?>) rowsValue;
        if (rows.isEmpty()) return "no rows selected.\n";

        List<String> columns = new ArrayList<String>();
        List<Map<String, Object>> normalizedRows = new ArrayList<Map<String, Object>>();
        for (Object rowValue : rows) {
            if (!(rowValue instanceof Map)) throw invalid("each query row must be a map");
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rowValue).entrySet()) {
                String column = String.valueOf(entry.getKey());
                if (!columns.contains(column)) columns.add(column);
                row.put(column, entry.getValue());
            }
            normalizedRows.add(row);
        }
        if (columns.isEmpty()) throw invalid("query rows must contain at least one column");

        int[] widths = new int[columns.size()];
        boolean[] numeric = new boolean[columns.size()];
        for (int index = 0; index < columns.size(); index++) {
            widths[index] = displayWidth(text(columns.get(index)));
            numeric[index] = false;
            boolean valueSeen = false;
            boolean allNumeric = true;
            for (Map<String, Object> row : normalizedRows) {
                Object cell = row.get(columns.get(index));
                if (cell != null) {
                    valueSeen = true;
                    allNumeric &= cell instanceof Number;
                }
                widths[index] = Math.max(widths[index], displayWidth(cell(cell)));
            }
            numeric[index] = valueSeen && allNumeric;
        }

        StringBuilder output = new StringBuilder();
        List<String> headers = new ArrayList<String>();
        for (String column : columns) headers.add(text(column));
        appendLine(output, headers, widths, numeric);
        List<String> separators = new ArrayList<String>();
        for (int width : widths) separators.add(repeat('-', width));
        appendLine(output, separators, widths, null);
        for (Map<String, Object> row : normalizedRows) {
            List<String> cells = new ArrayList<String>();
            for (String column : columns) cells.add(cell(row.get(column)));
            appendLine(output, cells, widths, numeric);
        }
        int count = rows.size();
        return output.append('\n').append(count).append(count == 1 ? " row selected.\n" : " rows selected.\n").toString();
    }

    private String update(Map<?, ?> result) {
        Object countValue = result.get("affectedRows");
        if (!(countValue instanceof Number)) throw invalid("update affectedRows must be a number");
        long count = ((Number) countValue).longValue();
        return count + (count == 1 ? " row updated.\n" : " rows updated.\n");
    }

    private void appendLine(StringBuilder output, List<String> values, int[] widths, boolean[] numeric) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) line.append("  ");
            boolean right = numeric != null && numeric[index];
            line.append(right ? leftPad(values.get(index), widths[index]) : rightPad(values.get(index), widths[index]));
        }
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') end--;
        output.append(line, 0, end).append('\n');
    }

    private String cell(Object value) { return value == null ? "NULL" : text(value); }

    private String text(Object value) {
        String input = String.valueOf(value);
        StringBuilder output = new StringBuilder(input.length());
        for (int index = 0; index < input.length();) {
            int codePoint = input.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\\') output.append("\\\\");
            else if (codePoint == '\r') output.append("\\r");
            else if (codePoint == '\n') output.append("\\n");
            else if (codePoint == '\t') output.append("\\t");
            else if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                output.append(codePoint <= 0xffff
                        ? String.format("\\u%04x", codePoint)
                        : String.format("\\U%08x", codePoint));
            } else output.appendCodePoint(codePoint);
        }
        return output.toString();
    }

    private int displayWidth(String value) {
        int width = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) continue;
            width += wide(codePoint) ? 2 : 1;
        }
        return width;
    }

    private boolean wide(int codePoint) {
        return codePoint >= 0x1100 && (codePoint <= 0x115f || codePoint == 0x2329 || codePoint == 0x232a
                || (codePoint >= 0x2e80 && codePoint <= 0xa4cf && codePoint != 0x303f)
                || (codePoint >= 0xac00 && codePoint <= 0xd7a3)
                || (codePoint >= 0xf900 && codePoint <= 0xfaff)
                || (codePoint >= 0xfe10 && codePoint <= 0xfe19)
                || (codePoint >= 0xfe30 && codePoint <= 0xfe6f)
                || (codePoint >= 0xff00 && codePoint <= 0xff60)
                || (codePoint >= 0xffe0 && codePoint <= 0xffe6)
                || (codePoint >= 0x1f300 && codePoint <= 0x1faff)
                || (codePoint >= 0x20000 && codePoint <= 0x3fffd));
    }

    private String leftPad(String value, int width) { return repeat(' ', width - displayWidth(value)) + value; }
    private String rightPad(String value, int width) { return value + repeat(' ', width - displayWidth(value)); }
    private String repeat(char character, int count) {
        StringBuilder value = new StringBuilder(Math.max(0, count));
        for (int index = 0; index < count; index++) value.append(character);
        return value.toString();
    }
    private IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("DB text output requires a stable DB query/update result: " + detail);
    }
}
