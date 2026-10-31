package att.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoadScheduleProvenanceTest {
    @Test void provenanceSurvivesSerializationAndCopyWithoutSharingSnapshot() throws Exception {
        LoadSchedule original = new LoadSchedule();
        original.savedScenarioId = "LS001";
        original.savedScenarioRevision = 3L;
        original.expectedRevisionDigest = "package-revision";
        original.inlineScenario = new LinkedHashMap<>(Map.of("workloads", List.of(Map.of("arrivalRate", "100/s"))));
        LoadSchedule restored = ServerRuntime.JSON.readValue(ServerRuntime.JSON.writeValueAsString(original), LoadSchedule.class);
        LoadSchedule copy = restored.copy();
        assertEquals("LS001", copy.savedScenarioId);
        assertEquals(3L, copy.savedScenarioRevision);
        assertEquals("package-revision", copy.expectedRevisionDigest);
        assertEquals(restored.inlineScenario, copy.inlineScenario);
        assertNotSame(restored.inlineScenario.get("workloads"), copy.inlineScenario.get("workloads"));
        LoadSchedule legacy = ServerRuntime.JSON.readValue("{}", LoadSchedule.class);
        assertNull(legacy.savedScenarioId);
        assertNull(legacy.savedScenarioRevision);
        assertEquals("load", legacy.effectiveJobType());
    }
}
