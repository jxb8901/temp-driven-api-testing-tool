package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactArrivalWorkloadFixtureTest {
    @TempDir Path temporary;

    @Test void generatesDeterministicHundredFiveHundredAndThousandPerSecondPlans() throws Exception {
        List<Map<String, Object>> scenarios = ArtifactArrivalWorkloadFixtures.generate(temporary, 1);
        assertEquals(List.of(100, 500, 1000), scenarios.stream()
                .map(row -> ((Number) row.get("targetArrivalsPerSecond")).intValue()).toList());
        for (Map<String, Object> scenario : scenarios) {
            int rate = ((Number) scenario.get("targetArrivalsPerSecond")).intValue();
            int count = rate;
            assertEquals(count, scenario.get("scheduledArrivalEnvelopes"));
            assertEquals(1, scenario.get("durationSeconds"));
            assertEquals(0L, scenario.get("firstScheduledOffsetNanos"));
            assertEquals(1_000_000_000L / rate, scenario.get("interArrivalIntervalNanos"));
            assertEquals((long) (count - 1) * 1_000_000_000L / rate,
                    scenario.get("lastScheduledOffsetNanos"));
            assertTrue(String.valueOf(scenario.get("scope")).contains("no archive/API completion rate"));

            Path directory = temporary.resolve(String.valueOf(scenario.get("fixtureDirectory")));
            try (var files = Files.list(directory)) { assertEquals(count, files.count()); }
            var first = ServerRuntime.JSON.readTree(Files.readString(directory.resolve("A000000.json")));
            var last = ServerRuntime.JSON.readTree(Files.readString(directory.resolve(String.format("A%06d.json", count - 1))));
            assertEquals(rate, first.path("targetArrivalsPerSecond").asInt());
            assertEquals(0L, first.path("scheduledOffsetNanos").asLong());
            assertEquals((long) (count - 1) * 1_000_000_000L / rate,
                    last.path("scheduledOffsetNanos").asLong());
            assertEquals(true, first.path("fixtureOnly").asBoolean());
        }
    }

    @Test void boundsArrivalFixtureDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactArrivalWorkloadFixtures.generate(temporary.resolve("zero"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactArrivalWorkloadFixtures.generate(temporary.resolve("long"), 11));
    }
}
