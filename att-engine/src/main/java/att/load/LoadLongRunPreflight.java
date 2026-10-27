package att.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only risk summary for LOAD scenarios longer than the browser's inline limit. */
public final class LoadLongRunPreflight {
    private static final long LONG_RUN_MS = 24L * 60L * 60L * 1000L;
    private static final long LOW_SPACE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long ARCHIVE_LIMIT_BYTES = 128L * 1024L * 1024L;
    private static final long ESTIMATED_BYTES_PER_EVIDENCE_ENTRY = 4L * 1024L;
    private static final int LARGE_QUOTA_WARNING = 10_000;

    private LoadLongRunPreflight() { }

    public static Map<String, Object> inspect(LoadScenario scenario, Path outputRoot) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> warnings = new ArrayList<Map<String, Object>>();
        long longestDuration = 0L;
        for (LoadWorkload workload : scenario.workloads())
            longestDuration = Math.max(longestDuration, LoadPhase.totalMs(scenario.forWorkload(workload)));
        boolean applicable = longestDuration > LONG_RUN_MS;
        result.put("applicable", Boolean.valueOf(applicable));
        result.put("durationMs", Long.valueOf(longestDuration));
        if (!applicable) {
            result.put("state", "NOT_REQUIRED");
            result.put("warnings", warnings);
            return result;
        }

        long estimatedArrivals = estimateArrivals(scenario);
        Long estimatedEvidenceEntries = null;
        Long estimatedEvidenceBytes = null;
        if (estimatedArrivals >= 0L) {
            result.put("estimatedConfiguredArrivals", Long.valueOf(estimatedArrivals));
            try {
                estimatedEvidenceEntries = Long.valueOf(estimateEvidenceEntries(scenario, estimatedArrivals));
                estimatedEvidenceBytes = Long.valueOf(saturatedMultiply(estimatedEvidenceEntries.longValue(), ESTIMATED_BYTES_PER_EVIDENCE_ENTRY));
                result.put("estimatedEvidenceFiles", estimatedEvidenceEntries);
                result.put("estimatedEvidenceBytes", estimatedEvidenceBytes);
                result.put("estimatedEvidenceBytesPerFile", Long.valueOf(ESTIMATED_BYTES_PER_EVIDENCE_ENTRY));
                result.put("estimatedEvidenceBasis", "planning estimate only: at most one retained event file per configured arrival at 4 KiB each; Target Tool output is excluded");
                if (estimatedEvidenceEntries.longValue() > LARGE_QUOTA_WARNING)
                    add(warnings, "LOAD_LONG_RUN_EVIDENCE_FILE_BUDGET",
                            "The configured run could retain more than 10,000 iteration evidence files; review filesystem and archive capacity before starting.");
                if (estimatedEvidenceBytes.longValue() > ARCHIVE_LIMIT_BYTES)
                    add(warnings, "LOAD_LONG_RUN_ARCHIVE_BUDGET",
                            "The estimated retained evidence size exceeds the 128 MiB artifact archive limit; some evidence may be omitted from publication.");
            } catch (RuntimeException unavailable) {
                add(warnings, "LOAD_LONG_RUN_EVIDENCE_ESTIMATE_UNAVAILABLE",
                        "The evidence file budget could not be estimated from the configured policy; inspect the quota summary before starting.");
            }
        }
        Map<String, Object> evidence = scenario.evidence();
        String mode = text(evidence.get("mode"), "failures");
        boolean perWorkload = LoadEvidencePolicy.perWorkloadScope(evidence);
        boolean unboundedSuccess = !perWorkload
                && ("all".equalsIgnoreCase(mode) || "full".equalsIgnoreCase(text(evidence.get("success"), "")))
                && !evidence.containsKey("maxSamples") && !evidence.containsKey("maxSuccessSamples");
        boolean unboundedFailure = !perWorkload
                && "full".equalsIgnoreCase(text(evidence.get("failure"), ""))
                && !evidence.containsKey("maxSamples") && !evidence.containsKey("maxFailureSamples");
        if (unboundedSuccess || unboundedFailure) {
            add(warnings, "LOAD_LONG_RUN_UNBOUNDED_EVIDENCE",
                    "Long-duration LOAD is configured to retain full iteration evidence without a finite quota; workspace and archive growth can exceed report limits.");
        }
        if (largeQuota(evidence) || workloadHasLargeQuota(scenario)) {
            add(warnings, "LOAD_LONG_RUN_LARGE_EVIDENCE_QUOTA",
                    "A success or failure evidence quota exceeds 10,000 items; review the disk and archive budget for this long run.");
        }
        if (scenario.resourceOutputEnabled() && hasToolTarget(scenario)) {
            add(warnings, "LOAD_LONG_RUN_TOOL_OUTPUT",
                    "Tool target output retention is enabled for a long run; per-iteration files can grow with the configured arrival count.");
        }

        Long usable = usableSpace(outputRoot);
        result.put("outputUsableSpaceBytes", usable);
        if (usable == null) {
            add(warnings, "LOAD_LONG_RUN_DISK_SPACE_UNKNOWN",
                    "Available output disk space could not be read during preflight; monitor the run workspace while LOAD is active.");
        } else if (usable.longValue() < LOW_SPACE_BYTES) {
            add(warnings, "LOAD_LONG_RUN_LOW_DISK_SPACE",
                    "Available output disk space is below 2 GiB; review retained outputs before starting this long run.");
        }
        if (usable != null && estimatedEvidenceBytes != null && estimatedEvidenceBytes.longValue() > usable.longValue())
            add(warnings, "LOAD_LONG_RUN_ESTIMATED_DISK_BUDGET",
                    "The planning estimate for retained evidence exceeds currently available output disk space; actual output size varies by iteration and target.");
        result.put("artifactArchiveLimitBytes", Long.valueOf(ARCHIVE_LIMIT_BYTES));
        result.put("state", warnings.isEmpty() ? "CLEAR" : "WARNINGS");
        result.put("warnings", warnings);
        return result;
    }

    private static long estimateArrivals(LoadScenario scenario) {
        long total = 0L;
        boolean found = false;
        for (LoadWorkload workload : scenario.workloads()) {
            if (workload.model() != LoadScenario.Model.ARRIVAL_RATE) return -1L;
            found = true;
            double estimate = workload.arrivalRatePerSecond() * (LoadPhase.totalMs(scenario.forWorkload(workload)) / 1000.0);
            if (!Double.isFinite(estimate) || estimate >= Long.MAX_VALUE - total) return Long.MAX_VALUE;
            total += (long) Math.ceil(Math.max(0.0, estimate));
        }
        return found ? total : -1L;
    }

    private static long estimateEvidenceEntries(LoadScenario scenario, long arrivals) {
        LoadEvidencePolicy policy = LoadEvidencePolicy.from(scenario);
        long limit = 0L;
        if (policy.perWorkloadQuotas()) {
            if (scenario.workloads().isEmpty()) {
                if (policy.success() != LoadEvidencePolicy.Success.NONE) limit += policy.defaultSuccessCap();
                if (policy.failure() != LoadEvidencePolicy.Failure.NONE) limit += policy.defaultFailureCap();
            } else for (LoadWorkload workload : scenario.workloads()) {
                if (policy.success() != LoadEvidencePolicy.Success.NONE) limit += policy.successCapFor(workload.id());
                if (policy.failure() != LoadEvidencePolicy.Failure.NONE) limit += policy.failureCapFor(workload.id());
            }
        } else if (policy.splitQuotas()) {
            if (policy.success() != LoadEvidencePolicy.Success.NONE) limit += policy.maxSuccessSamples();
            if (policy.failure() != LoadEvidencePolicy.Failure.NONE) limit += policy.maxFailureSamples();
        } else if (policy.success() != LoadEvidencePolicy.Success.NONE || policy.failure() != LoadEvidencePolicy.Failure.NONE) {
            limit = policy.maxSamples();
        }
        if (policy.success() == LoadEvidencePolicy.Success.SAMPLE && policy.failure() == LoadEvidencePolicy.Failure.NONE) {
            long sampleEstimate = (long) Math.ceil(arrivals * policy.sampleRate());
            limit = Math.min(limit, sampleEstimate);
        }
        return Math.min(arrivals, Math.max(0L, limit));
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value <= 0L || multiplier <= 0L) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static boolean hasToolTarget(LoadScenario scenario) {
        for (LoadWorkload workload : scenario.workloads()) {
            if ("tool".equalsIgnoreCase(workload.targetType())) return true;
            for (LoadMixEntry entry : workload.mix()) if ("tool".equalsIgnoreCase(entry.targetType())) return true;
        }
        return false;
    }

    private static boolean largeQuota(Map<String, Object> evidence) {
        for (String key : new String[] {"maxSamples", "maxSuccessSamples", "maxFailureSamples"})
            if (number(evidence.get(key)) > LARGE_QUOTA_WARNING) return true;
        Object defaults = evidence.get("defaults");
        if (defaults instanceof Map) for (String key : new String[] {"maxSuccessSamples", "maxFailureSamples"})
            if (number(((Map<?, ?>) defaults).get(key)) > LARGE_QUOTA_WARNING) return true;
        return false;
    }

    private static boolean workloadHasLargeQuota(LoadScenario scenario) {
        for (LoadWorkload workload : scenario.workloads())
            if (largeQuota(workload.evidence())) return true;
        return false;
    }

    private static Long usableSpace(Path path) {
        Path current = path == null ? Paths.get(".").toAbsolutePath() : path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current)) current = current.getParent();
        if (current == null) return null;
        try { return Long.valueOf(Files.getFileStore(current).getUsableSpace()); }
        catch (IOException | SecurityException unavailable) { return null; }
    }

    private static void add(List<Map<String, Object>> warnings, String code, String message) {
        if (warnings.size() >= 16) return;
        Map<String, Object> warning = new LinkedHashMap<String, Object>();
        warning.put("code", code); warning.put("message", message);
        warnings.add(warning);
    }

    private static int number(Object value) {
        return value instanceof Number ? Math.max(0, ((Number) value).intValue()) : 0;
    }

    private static String text(Object value, String fallback) {
        return value instanceof String ? ((String) value).trim() : fallback;
    }
}
