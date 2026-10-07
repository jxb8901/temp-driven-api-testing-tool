/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NamedSqlParametersTest {
    @Test void bindsRepeatedNamesInJdbcOrderAndIgnoresQuotesCommentsAndCasts() {
        Map<String,Object> values = new LinkedHashMap<String,Object>();
        values.put("id", "A100"); values.put("status", "POSTED");
        NamedSqlParameters.Binding binding = NamedSqlParameters.bind(
                "select ':ignored', col::text from t -- :comment\nwhere id=:id or parent_id=:id and status=:status /* :alsoIgnored */", values);
        assertEquals("select ':ignored', col::text from t -- :comment\nwhere id=? or parent_id=? and status=? /* :alsoIgnored */", binding.sql());
        assertEquals(java.util.Arrays.asList("id", "id", "status"), binding.names());
        assertEquals(java.util.Arrays.<Object>asList("A100", "A100", "POSTED"), binding.values());
    }

    @Test void rejectsMissingUnusedAndUnclosedSql() {
        assertThrows(IllegalArgumentException.class,
                () -> NamedSqlParameters.bind("select * from t where id=:id", java.util.Collections.<String,Object>emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> NamedSqlParameters.bind("select 1", java.util.Collections.<String,Object>singletonMap("id", "A")));
        assertThrows(IllegalArgumentException.class,
                () -> NamedSqlParameters.bind("select 'unfinished", java.util.Collections.<String,Object>emptyMap()));
    }
}
