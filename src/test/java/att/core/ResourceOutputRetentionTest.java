package att.core;

import att.config.ResourceOutputConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceOutputRetentionTest {
    @TempDir Path root;
    static class CountingMap extends LinkedHashMap<String, Object> {
        int visits;
        @Override public Set<Map.Entry<String, Object>> entrySet() { visits++; return super.entrySet(); }
    }
    private CaseRuntimeContext context() {
        TestCase test = new TestCase(1, "g", "s", "C", Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), null);
        return new CaseRuntimeContext(test, root, "E", "R", root, root.resolve("case.log"), "load", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
    }
    private ResourceOutputConfig policy() {
        Map<String, Object> output = new LinkedHashMap<String, Object>(); output.put("format", "json"); output.put("maxChars", 12);
        return ResourceOutputConfig.from(Collections.singletonMap("output", output));
    }
    @Test void discardedIterationsDoNotSerializeAndRetainedOnesMaterializeOnce() throws Exception {
        CountingMap value = new CountingMap(); value.put("paymentId", "1234567890");
        CaseRuntimeContext context = context(); Map<String, Object> metadata = new LinkedHashMap<String, Object>(); metadata.put("resource", "http");
        context.recordResourceOutput(policy(), value, metadata);
        assertEquals(0, value.visits); assertFalse(Files.exists(root.resolve("resource-output.yaml")));
        assertFalse(metadata.containsKey("output"));
        context.materializeResourceOutputs(root);
        assertTrue(value.visits > 0); int visits = value.visits;
        context.materializeResourceOutputs(root); assertEquals(visits, value.visits);
        String evidence = new String(Files.readAllBytes(root.resolve("resource-output.yaml")), "UTF-8");
        assertTrue(evidence.contains("metadata:")); assertTrue(evidence.contains("truncated: true"));
        assertEquals("1234567890", value.get("paymentId"));
    }
    @Test void credentialsAreRedactedBeforeTruncationInRetainedLoadEvidence() throws Exception {
        CaseRuntimeContext context = context();
        Map<String, Object> value = new LinkedHashMap<String, Object>(); value.put("token", "secret-value");
        context.recordResourceOutput(policy(), value, new LinkedHashMap<String, Object>(), Collections.singletonList("secret-value"));
        assertEquals("secret-value", value.get("token"));
        context.materializeResourceOutputs(root);
        String output = new String(Files.readAllBytes(root.resolve("resource-output.yaml")), "UTF-8");
        assertFalse(output.contains("secret-value")); assertFalse(output.contains("secret-"));
    }

    @Test void presentationFailureDoesNotAlterTypedResultOrFailRetention() throws Exception {
        CaseRuntimeContext context = context();
        Object value = new Object() { @Override public String toString() { throw new IllegalStateException("private failure"); } };
        ResourceOutputConfig invalid = ResourceOutputConfig.from(Collections.singletonMap("output",
                Collections.singletonMap("format", "sqlplus")));
        context.recordResourceOutput(invalid, value, new LinkedHashMap<String, Object>());
        context.materializeResourceOutputs(root);
        String output = new String(Files.readAllBytes(root.resolve("resource-output.yaml")), "UTF-8");
        assertTrue(output.contains("outputError: Resource output formatting failed")); assertFalse(output.contains("private failure"));
    }

    @Test void explicitNoneSuppressesFormattingEvenWhenEvidenceIsRetained() throws Exception {
        CountingMap value = new CountingMap(); value.put("payment", 1);
        CaseRuntimeContext context = context(); context.setResourceOutputEnabled(false);
        context.recordResourceOutput(policy(), value, new LinkedHashMap<String, Object>());
        context.materializeResourceOutputs(root);
        assertEquals(0, value.visits); assertFalse(Files.exists(root.resolve("resource-output.yaml")));
    }

    @Test void retainedMetadataRedactsNestedDbResultsWithoutTouchingCanonicalValues() throws Exception {
        CaseRuntimeContext context = context();
        CountingMap value = new CountingMap();
        value.put("rows", Collections.singletonList(Collections.singletonMap("PASSWORD", "secret-value")));
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("result", value);
        metadata.put("secret-value", Collections.singletonMap("diagnostic", "prefix secret-value suffix"));
        metadata.put("numericCredential", 12345);
        context.recordResourceOutput(policy(), value, metadata, Arrays.asList("secret-value", "12345"));
        assertEquals(0, value.visits, "Metadata redaction must also wait for retention");
        context.materializeResourceOutputs(root);
        String output = new String(Files.readAllBytes(root.resolve("resource-output.yaml")), "UTF-8");
        assertFalse(output.contains("secret-value")); assertFalse(output.contains("12345"));
        assertTrue(output.contains("[REDACTED_SECRET]"));
        assertSame(value, metadata.get("result"));
        assertEquals("secret-value", ((Map<?, ?>) ((List<?>) value.get("rows")).get(0)).get("PASSWORD"));
        assertEquals("prefix secret-value suffix", ((Map<?, ?>) metadata.get("secret-value")).get("diagnostic"));
    }
}
