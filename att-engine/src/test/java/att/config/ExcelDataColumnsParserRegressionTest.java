/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelDataColumnsParserRegressionTest {
    @Test
    void parsesDataColumnsFromTheExcelTestcaseExample() throws Exception {
        Path fixture = Paths.get("").toAbsolutePath().normalize()
                .resolve("docs/adr/examples/att-testcase-v1/excel.yaml");
        Map<?, ?> definition = (Map<?, ?>) YamlSupport.load(fixture);
        Map<?, ?> dataSource = (Map<?, ?>) definition.get("dataSource");
        String spec = (String) dataSource.get("dataColumns");

        List<DataColumnConfig> columns = ColumnSpecParser.dataColumns(spec);

        assertEquals(Arrays.asList("stableKey", "debitAccount", "amount", "expectedStatus", "note", "payload"),
                keys(columns));
        assertEquals(Arrays.asList("Stable Key", "Debit Account", "Amount", "Expected Status", "備註,補充", "請求(yaml)"),
                columnNames(columns));
        assertEquals(Arrays.asList(false, false, false, true, false, true), yamlFlags(columns));

        String withBlankTokens = ", , " + spec.replace(", debitAccount=", ", , debitAccount=") + ", , ";
        List<DataColumnConfig> normalized = ColumnSpecParser.dataColumns(withBlankTokens);
        assertEquals(keys(columns), keys(normalized));
        assertEquals(columnNames(columns), columnNames(normalized));
        assertEquals(yamlFlags(columns), yamlFlags(normalized));
    }

    @Test
    void omittedOrBlankDataColumnsSelectNoBusinessColumns() throws Exception {
        Path fixture = Paths.get("").toAbsolutePath().normalize()
                .resolve("docs/adr/examples/att-testcase-v1/excel-omitted-data-columns.yaml");
        Map<?, ?> definition = (Map<?, ?>) YamlSupport.load(fixture);
        Map<?, ?> dataSource = (Map<?, ?>) definition.get("dataSource");

        assertTrue(!dataSource.containsKey("dataColumns"));
        assertTrue(!definition.containsKey("inputs"));
        assertEquals(Arrays.asList(), keys(ColumnSpecParser.dataColumns(null)));
        assertEquals(Arrays.asList(), keys(ColumnSpecParser.dataColumns("")));
        assertEquals(Arrays.asList(), keys(ColumnSpecParser.dataColumns("   ")));
    }

    @Test
    void rejectsDuplicateAliasesAndMalformedQuotes() {
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> ColumnSpecParser.dataColumns("alias=First, alias=Second"));
        assertEquals("Duplicate data column key: alias", duplicate.getMessage());

        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> ColumnSpecParser.dataColumns("alias=\"unterminated"));
        assertTrue(malformed.getMessage().contains("Unclosed double quote"));
    }

    private static List<String> keys(List<DataColumnConfig> columns) {
        List<String> values = new ArrayList<String>();
        for (DataColumnConfig column : columns) values.add(column.key());
        return values;
    }

    private static List<String> columnNames(List<DataColumnConfig> columns) {
        List<String> values = new ArrayList<String>();
        for (DataColumnConfig column : columns) values.add(column.columnName());
        return values;
    }

    private static List<Boolean> yamlFlags(List<DataColumnConfig> columns) {
        List<Boolean> values = new ArrayList<Boolean>();
        for (DataColumnConfig column : columns) values.add(column.yaml());
        return values;
    }
}
