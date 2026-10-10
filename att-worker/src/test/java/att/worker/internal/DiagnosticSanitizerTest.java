package att.worker.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosticSanitizerTest {
    @Test void fastPathPreservesPlainTextAndRedactsSensitiveAssignments() {
        assertEquals("ordinary event text", DiagnosticSanitizer.redactText("ordinary event text"));
        assertEquals("Authorization: [REDACTED_SECRET]",
                DiagnosticSanitizer.redactText("Authorization: Bearer example-token"));
        assertEquals("password=[REDACTED_SECRET]", DiagnosticSanitizer.redactText("password=example-secret"));
    }

    @Test void normalizesSensitiveFieldNamesWithoutChangingTheRedactionContract() {
        Map<String, Object> safe = DiagnosticSanitizer.sanitize(Map.of(
                "api-key", "example-key",
                "request-id", "request-123",
                "message", "token=example-token"));

        assertEquals("[REDACTED_SECRET]", safe.get("api-key"));
        assertEquals("request-123", safe.get("request-id"));
        assertEquals("token=[REDACTED_SECRET]", safe.get("message"));
    }
}
