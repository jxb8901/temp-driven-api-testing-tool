package att.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceOutputConfigTest {
    @Test void sharedFormatsKeepValuesTypedAndRedactBeforeEncodingAndTruncation() {
        String credential = "private\"token\nline";
        Map<String, Object> row = new LinkedHashMap<String, Object>(); row.put("ID", credential); row.put("AMOUNT", 7);
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("operation", "query"); value.put("rows", Collections.singletonList(row));
        for (String format : Arrays.asList("text", "json", "yaml", "sqlplus")) {
            ResourceOutputConfig config = ResourceOutputConfig.from(Collections.singletonMap("output",
                    new LinkedHashMap<String, Object>() {{ put("format", format); put("maxChars", 10000); }}));
            assertEquals(10000, config.maxChars());
            Map<String, Object> output = config.render(value, Collections.singletonList(credential));
            assertEquals(format, output.get("format")); assertEquals(false, output.get("truncated"));
            assertTrue(String.valueOf(output.get("text")).contains("[REDACTED_SECRET]"));
            assertFalse(String.valueOf(output.get("text")).contains("private")); assertFalse(String.valueOf(output.get("text")).contains("token"));
            assertEquals(credential, row.get("ID")); assertEquals(7, row.get("AMOUNT"));
            ResourceOutputConfig bounded = ResourceOutputConfig.from(Collections.singletonMap("output",
                    new LinkedHashMap<String, Object>() {{ put("format", format); put("maxChars", 8); }}));
            Map<String, Object> first = bounded.render(value, Collections.singletonList(credential));
            assertEquals(first, bounded.render(value, Collections.singletonList(credential)));
            assertEquals(true, first.get("truncated")); assertEquals(8, String.valueOf(first.get("text")).length());
        }
        assertNull(ResourceOutputConfig.from(Collections.emptyMap()));
        for (Object limit : Arrays.asList(0, 1000001, 1.5))
            assertThrows(IllegalArgumentException.class, () -> ResourceOutputConfig.from(Collections.singletonMap("output",
                    new LinkedHashMap<String, Object>() {{ put("format", "json"); put("maxChars", limit); }})));
    }
}
