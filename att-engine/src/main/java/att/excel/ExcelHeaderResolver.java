/* Author: Jeffrey + ChatGPT */
package att.excel;

import att.core.ValueNormalizer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared logical-header projection for testcase loading and result-workbook writing. */
final class ExcelHeaderResolver {
    private ExcelHeaderResolver() { }

    static Map<String, Integer> columns(Sheet sheet, int headerRows, DataFormatter formatter) {
        Map<String, Integer> result = new HeaderMap();
        int maxColumns = maxColumns(sheet, headerRows);
        for (int columnIndex = 0; columnIndex < maxColumns; columnIndex++) {
            String name = "";
            for (int rowIndex = 0; rowIndex < headerRows; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                Cell cell = row == null ? null : row.getCell(columnIndex);
                String candidate = ValueNormalizer.normalize(cell == null ? "" : formatter.formatCellValue(cell));
                if (!candidate.isEmpty()) name = candidate;
            }
            if (!name.isEmpty() && result.put(name, Integer.valueOf(columnIndex)) != null) {
                throw new IllegalArgumentException("Duplicate Excel header after ignoring whitespace: " + name);
            }
        }
        return result;
    }

    static int maxColumns(Sheet sheet, int headerRows) {
        int maxColumns = 0;
        for (int rowIndex = 0; rowIndex < headerRows; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) maxColumns = Math.max(maxColumns, row.getLastCellNum());
        }
        return Math.max(0, maxColumns);
    }

    /** Matching ignores whitespace inside headers while preserving case and the original header for diagnostics. */
    static String matchKey(String value) {
        String normalized = ValueNormalizer.normalize(value);
        StringBuilder key = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) key.append(character);
        }
        return key.toString();
    }

    private static final class HeaderMap extends LinkedHashMap<String, Integer> {
        private final Map<String, Integer> normalized = new LinkedHashMap<String, Integer>();

        @Override public Integer put(String key, Integer value) {
            String matchKey = ExcelHeaderResolver.matchKey(key);
            Integer previous = normalized.get(matchKey);
            if (previous != null) return previous;
            normalized.put(matchKey, value);
            super.put(key, value);
            return null;
        }

        @Override public Integer get(Object key) {
            return key instanceof String ? normalized.get(ExcelHeaderResolver.matchKey((String) key)) : null;
        }

        @Override public boolean containsKey(Object key) {
            return key instanceof String && normalized.containsKey(ExcelHeaderResolver.matchKey((String) key));
        }
    }
}
