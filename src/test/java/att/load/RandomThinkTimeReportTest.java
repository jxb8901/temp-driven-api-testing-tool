package att.load;

import att.validation.JsonSchemaVerifier;
import att.validation.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomThinkTimeReportTest {
    @Test
    void summaryRecordsBoundedPolicyAndEffectiveSeedWithoutPerIterationSamples() throws Exception {
        Instant started = Instant.parse("2026-09-24T12:00:00Z");
        LoadScenario scenario = scenario(null);
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, started.toEpochMilli(), 1000L);
        metrics.finish(started.plusSeconds(1).toEpochMilli());
        LoadRunResult result = new LoadRunResult("derived-seed-run", scenario, started, started.plusSeconds(1), metrics.snapshot());

        Map<String, Object> summary = result.toMap();
        @SuppressWarnings("unchecked") Map<String, Object> scenarioSummary = (Map<String, Object>) summary.get("scenario");
        assertEquals(Long.valueOf(LoadRandomization.effectiveSeed(scenario, "derived-seed-run")), scenarioSummary.get("effectiveSeed"));
        @SuppressWarnings("unchecked") Map<String, Object> execution = (Map<String, Object>) scenarioSummary.get("execution");
        assertEquals("uniform: 500ms..2s", execution.get("thinkTimePolicy"));
        assertTrue(execution.get("thinkTime") instanceof Map);
        assertFalse(JsonSupport.write(summary).contains("sampledThinkTime"));
        JsonSchemaVerifier.verifyJson(Paths.get("schemas/att-load-summary-v1.0.schema.json"), JsonSupport.write(summary));

        LoadRunResult replay = new LoadRunResult("derived-seed-run", scenario, started, started.plusSeconds(1), metrics.snapshot());
        LoadRunResult different = new LoadRunResult("different-run", scenario, started, started.plusSeconds(1), metrics.snapshot());
        assertEquals(((Map<?, ?>) replay.toMap().get("scenario")).get("effectiveSeed"), scenarioSummary.get("effectiveSeed"));
        assertNotEquals(((Map<?, ?>) different.toMap().get("scenario")).get("effectiveSeed"), scenarioSummary.get("effectiveSeed"));
    }

    @Test
    void explicitRunSeedIsTheEffectiveSeed() {
        Instant started = Instant.parse("2026-09-24T12:00:00Z");
        LoadScenario scenario = scenario(Long.valueOf(12345L));
        LoadMetrics metrics = LoadMetrics.forScenario(scenario, started.toEpochMilli(), 1000L);
        metrics.finish(started.plusSeconds(1).toEpochMilli());
        Map<String, Object> summary = new LoadRunResult("any-run-id", scenario, started, started.plusSeconds(1), metrics.snapshot()).toMap();
        @SuppressWarnings("unchecked") Map<String, Object> scenarioSummary = (Map<String, Object>) summary.get("scenario");
        assertEquals(Long.valueOf(12345L), scenarioSummary.get("seed"));
        assertEquals(Long.valueOf(12345L), scenarioSummary.get("effectiveSeed"));
    }

    private static LoadScenario scenario(Long seed) {
        return new LoadScenario(Paths.get("random-think.yaml"), "template", "LOAD_TEMPLATE",
                Collections.emptyMap(), Collections.emptyMap(), LoadScenario.Model.CLOSED, 2, 0.0, null,
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO,
                ThinkTimePolicy.uniform(Duration.ofMillis(500L), Duration.ofSeconds(2L)), seed,
                0, "", Collections.emptyMap(), Collections.emptyMap());
    }
}
