/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract-level regression coverage for issue #39 direct DB execution controls. */
class DirectDbActionContractTest {

    @Test
    void queryAcceptsTimeoutAndAssertionTimeoutRetry() {
        TemplateAction action = new TemplateAction("poll", map(
                "type", "db",
                "db", "orders",
                "query", map("sql", "select status from orders"),
                "timeoutMs", 250,
                "assert", "#{${output.result.rowCount} > 0}",
                "retry", map("maxAttempts", 3, "intervalMs", 25,
                        "retryOn", Arrays.asList("ASSERTION", "TIMEOUT"))),
                "att-template/v3.0");

        assertEquals(Long.valueOf(250L), action.timeoutMs());
        assertEquals(3, action.retry().get("maxAttempts"));
        assertEquals(Arrays.asList("ASSERTION", "TIMEOUT"), action.retry().get("retryOn"));
        assertFalse(action.raw().containsKey("timeoutMs"));
        assertFalse(action.raw().containsKey("retry"));
    }

    @Test
    void queryRetryRequiresAssertionWhenAssertionCategoryIsConfigured() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new TemplateAction("poll", map(
                        "type", "db",
                        "db", "orders",
                        "query", map("sql", "select status from orders"),
                        "retry", map("maxAttempts", 2, "intervalMs", 0,
                                "retryOn", Collections.singletonList("ASSERTION"))),
                        "att-template/v3.0"));
        assertTrue(error.getMessage().contains("requires action assert"));
    }

    @Test
    void updateAllowsTimeoutButRejectsAutomaticRetryWithSafetyMessage() {
        TemplateAction timeoutOnly = new TemplateAction("write", map(
                "type", "db", "db", "orders",
                "update", map("sql", "update orders set status='DONE'"),
                "timeoutMs", 500), "att-template/v3.0");
        assertEquals(Long.valueOf(500L), timeoutOnly.timeoutMs());
        assertFalse(timeoutOnly.raw().containsKey("timeoutMs"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new TemplateAction("write", map(
                        "type", "db", "db", "orders",
                        "update", map("sql", "update orders set status='DONE'"),
                        "retry", map("maxAttempts", 2, "intervalMs", 0,
                                "retryOn", Collections.singletonList("TIMEOUT"))),
                        "att-template/v3.0"));
        assertTrue(error.getMessage().contains("mutating DB Actions"));
        assertTrue(error.getMessage().contains("outcome may be uncertain"));
    }

    @Test
    void retryBoundsAndCategoriesStayAlignedWithToolContract() {
        assertThrows(IllegalArgumentException.class, () -> queryWithRetry(1, 0, "TIMEOUT"));
        assertThrows(IllegalArgumentException.class, () -> queryWithRetry(11, 0, "TIMEOUT"));
        assertThrows(IllegalArgumentException.class, () -> queryWithRetry(2, -1, "TIMEOUT"));
        assertThrows(IllegalArgumentException.class, () -> queryWithRetry(2, 3600001, "TIMEOUT"));
        assertThrows(IllegalArgumentException.class, () -> queryWithRetry(2, 0, "SQL_ERROR"));
        assertThrows(IllegalArgumentException.class, () -> queryWithRetry(2, 0, "TIMEOUT", "TIMEOUT"));
    }

    @Test
    void timeoutBoundsAreStrict() {
        assertThrows(IllegalArgumentException.class, () -> new TemplateAction("low", map(
                "type", "db", "db", "orders", "query", map("sql", "select 1"), "timeoutMs", 0), "att-template/v3.0"));
        assertThrows(IllegalArgumentException.class, () -> new TemplateAction("high", map(
                "type", "db", "db", "orders", "query", map("sql", "select 1"), "timeoutMs", 3600001), "att-template/v3.0"));
    }

    private TemplateAction queryWithRetry(int maxAttempts, int intervalMs, String... categories) {
        return new TemplateAction("poll", map(
                "type", "db", "db", "orders",
                "query", map("sql", "select 1"),
                "retry", map("maxAttempts", maxAttempts, "intervalMs", intervalMs,
                        "retryOn", Arrays.asList(categories))), "att-template/v3.0");
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }
}
