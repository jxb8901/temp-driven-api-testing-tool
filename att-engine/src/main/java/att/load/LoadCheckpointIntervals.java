package att.load;

import att.core.IdentifierValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact, integrity-verified projection of the Worker's retained aggregate checkpoints. */
public final class LoadCheckpointIntervals {
    private static final String[] COUNTERS = {"scheduled", "started", "completed", "success", "failure",
            "runtimeError", "otherOutcome", "dropped", "measuredScheduled", "measuredStarted",
            "measuredCompleted", "measuredSuccess", "measuredFailure", "measuredRuntimeError",
            "measuredOtherOutcome", "measuredDropped"};

    private LoadCheckpointIntervals() { }

    /** Never exposes a filesystem path or the full checkpoint payload. */
    public static Map<String, Object> read(Path outputRoot, String runId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "att-load-checkpoint-interval/v1");
        result.put("state", "WAITING");
        if (outputRoot == null || runId == null) return result;
        try {
            String id = IdentifierValidator.runId(runId);
            Path root = outputRoot.toRealPath();
            Path directory = root;
            for (String component : new String[] {"load", id, "checkpoints", "aggregate"}) {
                directory = directory.resolve(component);
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return result;
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    result.put("state", "DEGRADED"); return result;
                }
            }
            List<Map<String, Object>> checkpoints = LoadMetricsCheckpointStore.readVerifiedGenerations(directory, id, "aggregate");
            if (checkpoints.size() < 2) {
                long candidates;
                try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                    candidates = files.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{20}\\.json"))
                            .limit(2L).count();
                }
                if (candidates > checkpoints.size() || (checkpoints.size() == 1 &&
                        ((Number) checkpoints.get(0).get("sequence")).longValue() > 1L))
                    result.put("state", "DEGRADED");
                return result;
            }
            Map<String, Object> end = checkpoints.get(0), start = checkpoints.get(1);
            long endSequence = ((Number) end.get("sequence")).longValue();
            long startSequence = ((Number) start.get("sequence")).longValue();
            Instant startTime = Instant.parse((String) start.get("capturedAt"));
            Instant endTime = Instant.parse((String) end.get("capturedAt"));
            long durationMs = java.time.Duration.between(startTime, endTime).toMillis();
            if (endSequence != startSequence + 1 || durationMs <= 0L) { result.put("state", "DEGRADED"); return result; }
            Map<?, ?> before = metrics(start), after = metrics(end);
            Map<String, Object> counts = new LinkedHashMap<String, Object>();
            for (String key : COUNTERS) {
                Object left = before.get(key), right = after.get(key);
                if (!(left instanceof Number) || !(right instanceof Number)) { result.put("state", "DEGRADED"); return result; }
                long previous = ((Number) left).longValue(), current = ((Number) right).longValue();
                if (previous < 0L || current < previous) { result.put("state", "DEGRADED"); return result; }
                counts.put(key, current - previous);
            }
            result.put("state", "VERIFIED");
            result.put("startSequence", startSequence); result.put("endSequence", endSequence);
            result.put("startAt", startTime.toString()); result.put("endAt", endTime.toString());
            result.put("durationMs", durationMs); result.put("counts", counts);
            Object beforeCount=before.get("latencyObservationCount"), afterCount=after.get("latencyObservationCount");
            Object beforeSum=before.get("latencySumMs"), afterSum=after.get("latencySumMs");
            if(beforeCount instanceof Number && afterCount instanceof Number && beforeSum instanceof Number && afterSum instanceof Number) {
                long count0=((Number)beforeCount).longValue(), count1=((Number)afterCount).longValue();
                long sum0=((Number)beforeSum).longValue(), sum1=((Number)afterSum).longValue();
                if(count0<0L || count1<count0 || sum0<0L || sum1<sum0) { result.put("state", "DEGRADED"); return result; }
                long samples=count1-count0;result.put("latencySampleCount",samples);
                if(samples>0L)result.put("latencyMeanMs",(double)(sum1-sum0)/samples);
            }
        } catch (IOException | RuntimeException invalid) {
            result.put("state", "DEGRADED");
        }
        return result;
    }

    private static Map<?, ?> metrics(Map<String, Object> checkpoint) {
        return (Map<?, ?>) ((Map<?, ?>) checkpoint.get("payload")).get("metrics");
    }
}
