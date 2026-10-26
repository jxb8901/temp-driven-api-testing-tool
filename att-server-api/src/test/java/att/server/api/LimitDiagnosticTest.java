package att.server.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitDiagnosticTest {
    @Test
    void taxonomyAndSafeStructuredFieldsAreStable() {
        assertEquals(Arrays.asList(LimitOutcome.EXECUTION_ERROR, LimitOutcome.SUBMISSION_REJECTED,
                        LimitOutcome.ARCHIVE_FAILED, LimitOutcome.ARTIFACT_OMITTED,
                        LimitOutcome.DATA_TRUNCATED, LimitOutcome.HISTORY_EVICTED,
                        LimitOutcome.METRICS_SAMPLED), Arrays.asList(LimitOutcome.values()));

        Map<String, Object> diagnostic = LimitDiagnostic.create("ATT-TEST-LIMIT", "A bounded limit was reached",
                "worker.frameBytes", 1024, 1200, "worker.protocol", LimitOutcome.DATA_TRUNCATED,
                "transport", "Inspect the retained report artifact", "load/run-1/report.json");
        assertEquals("DATA_TRUNCATED", diagnostic.get("outcome"));
        assertEquals(1024, diagnostic.get("configuredLimit"));
        assertEquals(1200, diagnostic.get("observed"));
        assertEquals(1200, diagnostic.get("usage"));
        assertEquals("load/run-1/report.json", diagnostic.get("artifactRef"));

        Map<String, Object> unsafe = LimitDiagnostic.create("ATT-TEST-LIMIT", "A limit was reached",
                "limit", 1, 2, "/private/host", LimitOutcome.DATA_TRUNCATED,
                "transport", "/Users/private/secret", "/private/host/file.json");
        assertFalse(unsafe.containsKey("scope"));
        assertFalse(unsafe.containsKey("remediationHint") && String.valueOf(unsafe.get("remediationHint")).contains("/Users"));
        assertFalse(unsafe.containsKey("artifactRef"));
        assertTrue(unsafe.containsKey("outcome"));
    }
}
