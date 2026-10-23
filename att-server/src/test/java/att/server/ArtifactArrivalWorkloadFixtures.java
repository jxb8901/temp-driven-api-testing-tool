package att.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic planned arrival envelopes for artifact workload generators. */
final class ArtifactArrivalWorkloadFixtures {
    private static final int[] TARGET_RATES = {100, 500, 1000};
    private static final int[] INVENTORY_SIZES = {25, 101, 501, 1001, 2049};

    private ArtifactArrivalWorkloadFixtures() { }

    static List<Map<String, Object>> generate(Path root, int durationSeconds) throws Exception {
        if (durationSeconds < 1 || durationSeconds > 10)
            throw new IllegalArgumentException("Arrival fixture duration must be between one and ten seconds");
        Files.createDirectories(root);
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int rate : TARGET_RATES) {
            int count = Math.multiplyExact(rate, durationSeconds);
            Path scenario = Files.createDirectories(root.resolve("target-" + rate + "-per-second"));
            long lastOffset = 0L;
            for (int ordinal = 0; ordinal < count; ordinal++) {
                long offset = Math.multiplyExact((long) ordinal, 1_000_000_000L) / rate;
                lastOffset = offset;
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("schemaVersion", "att-artifact-arrival-fixture/v1");
                envelope.put("arrivalId", String.format(Locale.ROOT, "A%06d", ordinal));
                envelope.put("targetArrivalsPerSecond", rate);
                envelope.put("scheduledOffsetNanos", offset);
                envelope.put("inventoryFixtureEntries", INVENTORY_SIZES[ordinal % INVENTORY_SIZES.length]);
                envelope.put("fixtureOnly", true);
                Files.writeString(scenario.resolve(String.format(Locale.ROOT, "A%06d.json", ordinal)),
                        ServerRuntime.JSON.writeValueAsString(envelope), StandardCharsets.UTF_8);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("targetArrivalsPerSecond", rate);
            summary.put("durationSeconds", durationSeconds);
            summary.put("scheduledArrivalEnvelopes", count);
            summary.put("firstScheduledOffsetNanos", 0L);
            summary.put("lastScheduledOffsetNanos", lastOffset);
            summary.put("interArrivalIntervalNanos", 1_000_000_000L / rate);
            summary.put("inventoryFixtureEntries", java.util.Arrays.stream(INVENTORY_SIZES).boxed().toList());
            summary.put("fixtureDirectory", root.relativize(scenario).toString());
            summary.put("scope", "planned workload generation only; no archive/API completion rate measured");
            scenarios.add(summary);
        }
        return List.copyOf(scenarios);
    }
}
