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
                "schemaVersion: att-load/v1.1\n"
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
        assertEquals(Version.LOAD_SCHEMA_V1_1, scenario.schemaVersion());
        assertTrue(scenario.multiWorkload());
        assertEquals(3, scenario.workloads().size());
        assertEquals(105.0, scenario.configuredArrivalRatePerSecond(), 0.00001);
        assertEquals(17, scenario.configuredMaxConcurrent());
        assertEquals(LoadScenario.Model.ARRIVAL_RATE, scenario.model());
    }

    @Test void parsesIndependentClosedVuPoolsAndSumsUsers() throws Exception {
        LoadScenario scenario = load("closed.yaml",
                "schemaVersion: att-load/v1.1\n"
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
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- {id: same, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n"
                + "- {id: same, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n",
                "duplicate workload id");
        assertInvalid("mixed.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- {id: closed, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n"
                + "- {id: open, target: {type: tool, id: sample.getAcDate}, load: {arrivalRate: 1/s, duration: 1s, maxConcurrent: 1, overloadPolicy: drop}}\n",
                "mixed closed and arrivalRate");
        assertInvalid("timing.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- {id: a, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n"
                + "- {id: b, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 2s}}\n",
                "same warmup/rampUp/duration/rampDown");
    }

    @Test void rejectsAmbiguousCliOverridesForMultipleWorkloads() throws Exception {
        Path file = write("override.yaml",
                "schemaVersion: att-load/v1.1\nworkloads:\n"
                + "- {id: a, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n"
                + "- {id: b, target: {type: tool, id: sample.getAcDate}, load: {users: 1, duration: 1s}}\n");
        Exception error = assertThrows(Exception.class, () -> new LoadScenarioLoader(projectRoot()).load(file,
                new LoadOverrides("2", null, null, null, null, null, null, null, null)));
        assertTrue(message(error).contains("ambiguous for multi-workload"));
    }

    @Test void legacyV10RemainsSingleWorkloadCompatible() throws Exception {
        LoadScenario scenario = load("legacy.yaml",
                "schemaVersion: att-load/v1.0\n"
                + "target: {type: tool, id: sample.getAcDate}\n"
                + "load: {users: 2, duration: 1s}\n");
        assertTrue(scenario.legacyV1());
        assertFalse(scenario.multiWorkload());
        assertEquals(1, scenario.workloads().size());
        assertEquals("sample.getAcDate", scenario.targetId());
        assertFalse(scenario.toSummaryMap().containsKey("workloads"));
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
