/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbTextResultFormatterTest {
    private final DbTextResultFormatter formatter = new DbTextResultFormatter();

    @Test void rendersQueryAsAlignedSqlPlusStyleTable() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("ID", "A100", "AMOUNT", new BigDecimal("12.50"), "NOTE", "OPEN\nNOW"));
        rows.add(row("ID", "B2", "AMOUNT", Integer.valueOf(3), "NOTE", null));
        Map<String, Object> result = result("query");
        result.put("rows", rows);

        assertEquals(
                "ID    AMOUNT  NOTE\n" +
                "----  ------  ---------\n" +
                "A100   12.50  OPEN\\nNOW\n" +
                "B2         3  NULL\n" +
                "\n2 rows selected.\n",
                formatter.format(result));
    }

    @Test void rendersEmptyQueriesAndUpdateCounts() {
        Map<String, Object> empty = result("query");
        empty.put("rows", new ArrayList<Object>());
        assertEquals("no rows selected.\n", formatter.format(empty));

        Map<String, Object> one = result("update"); one.put("affectedRows", Integer.valueOf(1));
        Map<String, Object> many = result("update"); many.put("affectedRows", Integer.valueOf(4));
        assertEquals("1 row updated.\n", formatter.format(one));
        assertEquals("4 rows updated.\n", formatter.format(many));
    }

    @Test void alignsWideUnicodeColumnsByTerminalDisplayWidth() {
        Map<String, Object> result = result("query");
        result.put("rows", java.util.Collections.singletonList(row("名稱", "訂單", "COUNT", Integer.valueOf(7))));

        assertEquals("名稱  COUNT\n----  -----\n訂單      7\n\n1 row selected.\n", formatter.format(result));
    }

    @Test void rejectsValuesOutsideTheStableDbResultShape() {
        assertThrows(IllegalArgumentException.class, () -> formatter.format("query"));
        assertThrows(IllegalArgumentException.class, () -> formatter.format(result("query")));
        assertThrows(IllegalArgumentException.class, () -> formatter.format(result("other")));
    }

    private Map<String, Object> result(String operation) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", Boolean.TRUE);
        result.put("operation", operation);
        return result;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }
}
