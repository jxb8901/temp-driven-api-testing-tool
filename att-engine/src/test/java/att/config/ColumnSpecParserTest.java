/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ColumnSpecParserTest {
    @Test void parsesQuotedChineseMappingsAndYamlMarker() {
        List<DataColumnConfig> columns = ColumnSpecParser.dataColumns("amount=金額,payload=\"請求,資料\"(yaml),中文欄位");
        assertEquals(3, columns.size());
        assertEquals("payload", columns.get(1).key());
        assertTrue(columns.get(1).yaml());
        assertEquals("中文欄位", columns.get(2).key());
    }
}
