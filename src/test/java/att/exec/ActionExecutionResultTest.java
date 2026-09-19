/* Author: Jeffrey + ChatGPT */
package att.exec;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionExecutionResultTest {
    @Test void operationEvidenceKeepsStableInvocationCardinality() {
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        ActionExecutionResult.mergeEvidence(target, ActionExecutionResult.evidence("db",
                map("helperId", "orders", "id", "orders_001")));
        ActionExecutionResult.mergeEvidence(target, ActionExecutionResult.evidence("db",
                map("helperId", "orders", "id", "orders_002")));
        ActionExecutionResult.mergeEvidence(target, ActionExecutionResult.evidence("db",
                map("helperId", "orders", "id", "orders_003")));

        Map<?, ?> db = (Map<?, ?>) target.get("db");
        assertTrue(db.get("invocations") instanceof java.util.List);
        assertEquals(3, ((java.util.List<?>) db.get("invocations")).size());
        assertFalse(String.valueOf(db).contains("invocations={"));
    }

    @Test void operationSnapshotDetachesFromMutableHelperEvidence() {
        Map<String, Object> raw = map("durationMs", 17L, "status", "PASS");
        ActionExecutionResult result = new ActionExecutionResult("ok",
                ActionExecutionResult.evidence("tool", raw), true);
        raw.put("evidenceError", "late log failure");

        assertEquals(17L, result.durationMs());
        assertNull(((Map<?, ?>) ((java.util.List<?>) ((Map<?, ?>) result.evidence().get("tool")).get("invocations")).get(0)).get("evidenceError"));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }
}
