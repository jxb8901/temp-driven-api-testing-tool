package att.template;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TypedValueFormatterTest {
    @Test void formatsTypedObjectsWithoutMutationAndRejectsRaw() throws Exception {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", 7); value.put("active", true); value.put("items", Arrays.asList("A", "B"));
        Map<String, Object> before = new LinkedHashMap<String, Object>(value);
        DefaultBuiltInProvider provider = new DefaultBuiltInProvider();
        for (String format : Arrays.asList("json", "yaml", "xml", "text")) {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("format", format); args.put("obj", value);
            Object rendered = provider.invoke("format", args);
            assertTrue(rendered instanceof String);
            assertEquals(rendered, provider.invoke("format", args));
            assertEquals(before, value);
        }
        assertThrows(IllegalArgumentException.class, () -> new TypedValueFormatter().format(value, "raw"));
        assertEquals("7", new TypedValueFormatter().format(7, "text"));
        assertEquals("null\n", new TypedValueFormatter().format(null, "json"));
        Map<String, Object> scope = Collections.<String, Object>singletonMap("VALUE", value);
        String json = new UnifiedTemplateEngine(null).renderScoped("#{format(format='json', obj=${VALUE})}", scope);
        assertTrue(json.contains("\"id\""));
        assertEquals(before, value);
    }
    @Test void sqlplusMatchesExistingDbFormatter() {
        Map<String, Object> db = new LinkedHashMap<String, Object>();
        db.put("operation", "query"); db.put("rows", Collections.singletonList(Collections.singletonMap("ID", 42)));
        assertEquals(new DbTextResultFormatter().format(db), new TypedValueFormatter().format(db, "sqlplus"));
    }
}
