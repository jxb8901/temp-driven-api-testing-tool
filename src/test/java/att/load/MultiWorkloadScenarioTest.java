package att.load;

import att.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class MultiWorkloadScenarioTest {
    @TempDir Path temp;

    @Test void parsesIndependentArrivalWorkloadsAndAggregatesConfiguredRate() throws Exception {
        LoadScenario scenario = load("arrival.yaml",
                "schemaVersion: att-load/v1.3\n"
                + "workloads:\n"
                + "  - id: payment\n"
                + "    target: {type: tool, id: sample.getAcDate}\n"
                + "    load: {arrivalRate: 80/s, duration: 1s, maxConcurrent: 10, overloadPolicy: drop}\n"
                + "  - id: balance\n"
                + "    target: {type: tool, id: sample.getAcDate}\n"
                + "    load: {arrivalRate: 20/s, duration: 1s, maxConcurrent: 5, overloadPolicy: drop}\n"
                + "  - id: customer\n"
                + "    target: {type: tool, id: sample.getAcDate}\n"
                + "    load: {arrivalRate: 5/s, duration: 1s, maxConcurrent: 2, overloadPolicy: drop}\n");
        assertEquals(Version.LOAD_SCHEMA_CURRENT, scenario.schemaVersion());
        assertTrue(scenario.multiWorkload());
        assertEquals(3, scenario.workloads().size());
        assertEquals(105.0, scenario.configuredArrivalRatePerSecond(), 0.00001);
        assertEquals(17, scenario.configuredMaxConcurrent());
        assertEquals(LoadScenario.Model.ARRIVAL_RATE, scenario.model());
    }

    @Test void parsesAndDeterministicallySelectsWeightedClosedTargetMix() throws Exception {
        LoadScenario scenario = load("mix.yaml", "schemaVersion: att-load/v1.6\nseed: 91\nworkloads:\n"
                + "  - id: checkout\n    inputs: {region: HK, operation: common}\n"
                + "    mix:\n"
                + "      - id: browse\n        weight: 60\n        target: {type: template, id: BROWSE}\n        inputs: {operation: browse}\n"
                + "      - id: purchase\n        weight: 30\n        target: {type: flow, id: PURCHASE}\n"
                + "      - id: report\n        weight: 10\n        target: {type: tool, id: sample.getSeq, arguments: {seqLen: 8}}\n"
                + "    load: {users: 4, duration: 1s}\n");
        LoadWorkload workload = scenario.workload();
        assertTrue(workload.mixed());
        assertNull(workload.targetType());
        assertEquals(3, workload.mix().size());
        assertNotNull(LoadMixSelector.select(workload, 91L, 1L, "VU-1"));
        int[] counts = new int[3];
        for (int i = 1; i <= 10000; i++) {
            String selected = LoadMixSelector.select(workload, 91L, i, "VU-1").id();
            if ("browse".equals(selected)) counts[0]++;
            else if ("purchase".equals(selected)) counts[1]++;
            else if ("report".equals(selected)) counts[2]++;
            assertEquals(selected, LoadMixSelector.select(workload, 91L, i, "VU-1").id());
        }
        assertEquals(60.0, counts[0] / 100.0, 2.0);
        assertEquals(30.0, counts[1] / 100.0, 2.0);
        assertEquals(10.0, counts[2] / 100.0, 2.0);
        assertEquals("browse", workload.forMixEntry(workload.mix().get(0)).inputs().get("operation"));
        assertEquals("HK", workload.forMixEntry(workload.mix().get(0)).inputs().get("region"));
    }

    @Test void rejectsInvalidMixConfigurations() throws Exception {
        assertInvalid("duplicate-mix.yaml", "schemaVersion: att-load/v1.6\nworkloads:\n"
                + "- id: m\n  mix:\n  - {id: same, weight: 1, target: {type: template, id: A}}\n"
                + "  - {id: same, weight: 2, target: {type: template, id: B}}\n  load: {users: 1, duration: 1s}\n",
                "mix entry id must be unique");
        assertInvalid("tool-vars-mix.yaml", "schemaVersion: att-load/v1.6\nworkloads:\n"
                + "- id: m\n  vars: {x: 1}\n  mix:\n"
                + "  - {id: tool, weight: 1, target: {type: tool, id: sample.getAcDate}}\n  load: {users: 1, duration: 1s}\n",
                "Tool mix entries do not support");
        assertInvalid("arrival-mix.yaml", "schemaVersion: att-load/v1.6\nworkloads:\n"
                + "- id: m\n  mix:\n  - {id: a, weight: 1, target: {type: template, id: A}}\n"
                + "  load: {arrivalRate: 1/s, duration: 1s, maxConcurrent: 1, overloadPolicy: drop}\n",
                "target mixes are supported only for closed workloads");
    }

    @Test void parsesIndependentClosedVuPoolsAndSumsUsers() throws Exception {
        LoadScenario scenario = load("closed.yaml",
                "schemaVersion: att-load/v1.3\n"
                + "workloads:\n"
                + "  - id: payment\n"
                + "    target: {type: tool, id: sample.getAcDate}\n"
                + "    load: {users: 60, duration: 1s}\n"
                + "    execution: {thinkTime: 100ms}\n"
                + "  - id: balance\n"
                + "    target: {type: tool, id: sample.getAcDate}\n"
                + "    load: {users: 30, duration: 1s}\n"
                + "    execution: {thinkTime: {min: 50ms, max: 150ms}}\n"
                + "  - id: enquiry\n"
                + "    target: {type: tool, id: sample.getAcDate}\n"
                + "    load: {users: 10, duration: 1s}\n");
        assertEquals(100, scenario.configuredUsers());
        assertEquals(LoadScenario.Model.CLOSED, scenario.model());
        assertEquals("payment", scenario.workloads().get(0).id());
        assertTrue(scenario.workloads().get(1).thinkTimePolicy().randomized());
    }

    @Test void rejectsDuplicateIdsMixedModelsAndDifferentTimingEnvelopes() throws Exception {
        assertInvalid("duplicate.yaml",
                "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "- {id: same, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n"
                + "- {id: same, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n",
                "duplicate workload id");
        assertInvalid("mixed.yaml",
                "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "- {id: closed, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n"
                + "- {id: open, target: {type: tool, id: sample.getAcDate}, load: {arrivalRate: 1/s, duration: 1s, maxConcurrent: 1, overloadPolicy: drop}}\n",
                "mixed closed and arrivalRate");
        assertInvalid("timing.yaml",
                "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "- {id: a, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n"
                + "- {id: b, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 2s}}\n",
                "same warmup/rampUp/duration/rampDown");
    }

    @Test void rejectsAmbiguousCliOverridesForMultipleWorkloads() throws Exception {
        Path file = write("override.yaml",
                "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "- {id: a, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n"
                + "- {id: b, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n");
        Exception error = assertThrows(Exception.class, () -> new LoadScenarioLoader(projectRoot()).load(file,
                new LoadOverrides("2", null, null, null, null, null, null, null, null)));
        assertTrue(message(error).contains("ambiguous for multi-workload"));
    }

    @Test void rejectsHistoricalV10Schema() throws Exception {
        Path file = temp.resolve("legacy.yaml");
        Files.write(file, ("schemaVersion: att-load/v1.0\n"
                + "target: {type: tool, id: sample.getAcDate}\n"
                + "load: {users: 2, duration: 1s}\n").getBytes(StandardCharsets.UTF_8));
        Exception error = assertThrows(Exception.class, () -> new LoadScenarioLoader(projectRoot()).load(file));
        assertTrue(message(error).contains("att-load/v1.3"), message(error));
    }

    private LoadScenario load(String name, String content) throws Exception {
        return new LoadScenarioLoader(projectRoot()).load(write(name, content));
    }
    private void assertInvalid(String name, String content, String expected) throws Exception {
        Exception error = assertThrows(Exception.class, () -> load(name, content));
        assertTrue(message(error).contains(expected), message(error));
    }
    private Path write(String name, String content) throws Exception {
        Path file = temp.resolve(name); Files.write(file, content.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private static Path projectRoot() { return Paths.get("").toAbsolutePath().normalize(); }
    private static String message(Throwable value) {
        StringBuilder result = new StringBuilder();
        while (value != null) { if (value.getMessage() != null) result.append(value.getMessage()).append('\n'); value = value.getCause(); }
        return result.toString();
    }
}
