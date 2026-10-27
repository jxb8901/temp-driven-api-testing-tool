package att.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadLongRunPreflightTest {
    @TempDir Path temp;

    @Test
    void shortRunsSkipTheLongRunPreflight() {
        Map<String, Object> result = LoadLongRunPreflight.inspect(scenario(Duration.ofHours(2), "template",
                Collections.<String, Object>emptyMap()), temp.resolve("output"));
        assertEquals("NOT_REQUIRED", result.get("state"));
        assertTrue(((List<?>) result.get("warnings")).isEmpty());
    }

    @Test
    void longRunsWarnAboutUnboundedEvidenceAndToolOutputWithoutRejectingDuration() {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("mode", "all");
        evidence.put("resources", Collections.singletonMap("output", "all"));
        Map<String, Object> result = LoadLongRunPreflight.inspect(
                scenario(Duration.ofHours(25), "tool", evidence), temp.resolve("output"));
        assertEquals("WARNINGS", result.get("state"));
        assertEquals(9_000_000L, result.get("estimatedConfiguredArrivals"));
        assertEquals(9_000_000L, result.get("estimatedEvidenceFiles"));
        assertTrue(((Number) result.get("estimatedEvidenceBytes")).longValue() > 128L * 1024L * 1024L);
        String warnings = String.valueOf(result.get("warnings"));
        assertTrue(warnings.contains("LOAD_LONG_RUN_UNBOUNDED_EVIDENCE"));
        assertTrue(warnings.contains("LOAD_LONG_RUN_TOOL_OUTPUT"));
        assertTrue(warnings.contains("LOAD_LONG_RUN_ARCHIVE_BUDGET"));
    }

    @Test
    void boundedMetricsEvidenceProducesNoQuotaWarning() {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("mode", "metrics");
        evidence.put("maxFailureSamples", 1000);
        evidence.put("resources", Collections.singletonMap("output", "none"));
        Map<String, Object> result = LoadLongRunPreflight.inspect(
                scenario(Duration.ofHours(25), "template", evidence), temp.resolve("output"));
        String warnings = String.valueOf(result.get("warnings"));
        assertFalse(warnings.contains("UNBOUNDED_EVIDENCE"));
        assertFalse(warnings.contains("TOOL_OUTPUT"));
        assertEquals(0L, result.get("estimatedEvidenceFiles"));
    }

    private static LoadScenario scenario(Duration duration, String targetType, Map<String, Object> evidence) {
        return new LoadScenario(Paths.get("long-run.yaml"), targetType, "NOOP",
                Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap(),
                LoadScenario.Model.ARRIVAL_RATE, 0, 100.0, "100/s", Duration.ZERO, Duration.ZERO,
                duration, Duration.ZERO, Duration.ZERO, 512, "drop",
                Collections.<String, Object>emptyMap(), evidence);
    }
}
