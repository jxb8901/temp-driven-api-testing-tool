package att.load;

import att.Version;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, semantically validated ATT load scenario. */
public final class LoadScenario {
    public enum Model {
        CLOSED("closed"), ARRIVAL_RATE("arrivalRate");
        private final String wireName;
        Model(String wireName) { this.wireName = wireName; }
        public String wireName() { return wireName; }
    }

    private final Path source;
    private final String schemaVersion;
    private final List<LoadWorkload> workloads;
    private final Map<String, Object> thresholds;
    private final Map<String, Object> evidence;
    private final Long seed;
    private final boolean workloadView;

    /** Compatibility constructor for the original fixed-duration think-time model. */
    LoadScenario(Path source, String targetType, String targetId, Map<String, Object> targetArguments,
                 Map<String, Object> inputs, Model model, int users, double arrivalRatePerSecond,
                 String arrivalRate, Duration warmup, Duration rampUp, Duration duration, Duration rampDown,
                 Duration thinkTime, int maxConcurrent, String overloadPolicy,
                 Map<String, Object> thresholds, Map<String, Object> evidence) {
        this(source, targetType, targetId, targetArguments, inputs, model, users, arrivalRatePerSecond,
                arrivalRate, warmup, rampUp, duration, rampDown, ThinkTimePolicy.fixed(thinkTime), null,
                maxConcurrent, overloadPolicy, thresholds, evidence);
    }

    /** Compatibility constructor used by att-load/v1.0. */
    LoadScenario(Path source, String targetType, String targetId, Map<String, Object> targetArguments,
                 Map<String, Object> inputs, Model model, int users, double arrivalRatePerSecond,
                 String arrivalRate, Duration warmup, Duration rampUp, Duration duration, Duration rampDown,
                 ThinkTimePolicy thinkTimePolicy, Long seed, int maxConcurrent, String overloadPolicy,
                 Map<String, Object> thresholds, Map<String, Object> evidence) {
        this(source, Version.LOAD_SCHEMA,
                Collections.singletonList(new LoadWorkload("default", targetType, targetId, targetArguments,
                        inputs, model, users, arrivalRatePerSecond, arrivalRate, warmup, rampUp, duration,
                        rampDown, thinkTimePolicy, maxConcurrent, overloadPolicy, thresholds)),
                seed, thresholds, evidence);
    }

    LoadScenario(Path source, String schemaVersion, List<LoadWorkload> workloads, Long seed,
                 Map<String, Object> thresholds, Map<String, Object> evidence) {
        this(source, schemaVersion, workloads, seed, thresholds, evidence, false);
    }

    private LoadScenario(Path source, String schemaVersion, List<LoadWorkload> workloads, Long seed,
                         Map<String, Object> thresholds, Map<String, Object> evidence, boolean workloadView) {
        if (workloads == null || workloads.isEmpty()) throw new IllegalArgumentException("Load scenario requires at least one workload");
        this.source = source;
        this.schemaVersion = schemaVersion == null ? Version.LOAD_SCHEMA : schemaVersion;
        this.workloads = Collections.unmodifiableList(new ArrayList<LoadWorkload>(workloads));
        this.seed = seed;
        this.thresholds = immutable(thresholds);
        this.evidence = immutable(evidence);
        this.workloadView = workloadView;
    }

    public Path source() { return source; }
    public String schemaVersion() { return schemaVersion; }
    public List<LoadWorkload> workloads() { return workloads; }
    public boolean multiWorkload() { return workloads.size() > 1; }
    public boolean legacyV1() { return Version.LOAD_SCHEMA.equals(schemaVersion); }
    boolean coordinatorRequired() { return !legacyV1() && !workloadView; }
    public LoadWorkload workload() { return workloads.get(0); }
    public LoadWorkload workload(String id) {
        for (LoadWorkload workload : workloads) if (workload.id().equals(id)) return workload;
        return null;
    }

    /** Compatibility single-workload accessors. Multi-workload orchestration uses workloads(). */
    public String workloadId() { return workload().id(); }
    public String targetType() { return workload().targetType(); }
    public String targetId() { return workload().targetId(); }
    public Map<String, Object> targetArguments() { return workload().targetArguments(); }
    public Map<String, Object> inputs() { return workload().inputs(); }
    public Model model() { return workload().model(); }
    public int users() { return workload().users(); }
    public double arrivalRatePerSecond() { return workload().arrivalRatePerSecond(); }
    public String arrivalRate() { return workload().arrivalRate(); }
    public Duration warmup() { return workload().warmup(); }
    public Duration rampUp() { return workload().rampUp(); }
    public Duration duration() { return workload().duration(); }
    public Duration rampDown() { return workload().rampDown(); }
    public Duration thinkTime() { return workload().thinkTimePolicy().min(); }
    public ThinkTimePolicy thinkTimePolicy() { return workload().thinkTimePolicy(); }
    public Long seed() { return seed; }
    public int maxConcurrent() { return workload().maxConcurrent(); }
    public String overloadPolicy() { return workload().overloadPolicy(); }
    /** Run-level thresholds for v1.1; identical to workload thresholds for legacy v1.0. */
    public Map<String, Object> thresholds() { return thresholds; }
    public Map<String, Object> evidence() { return evidence; }

    public int configuredUsers() {
        int result = 0;
        for (LoadWorkload workload : workloads) result += workload.users();
        return result;
    }
    public double configuredArrivalRatePerSecond() {
        double result = 0.0;
        for (LoadWorkload workload : workloads) result += workload.arrivalRatePerSecond();
        return result;
    }
    public int configuredMaxConcurrent() {
        int result = 0;
        for (LoadWorkload workload : workloads) result += workload.maxConcurrent();
        return result;
    }
    public boolean randomizedThinkTime() {
        for (LoadWorkload workload : workloads) if (workload.thinkTimePolicy().randomized()) return true;
        return false;
    }

    /** Creates the single-workload view consumed by the existing schedulers and validators. */
    public LoadScenario forWorkload(LoadWorkload workload) {
        if (workload == null) throw new IllegalArgumentException("Load workload is required");
        return new LoadScenario(source, schemaVersion, Collections.singletonList(workload), seed,
                workload.thresholds(), evidence, true);
    }

    public Map<String, Object> toMap() { return toMap(true, false); }

    /** Returns a report-safe scenario projection; business inputs and Tool arguments are never persisted. */
    public Map<String, Object> toSummaryMap() { return toMap(false, true); }

    private Map<String, Object> toMap(boolean includeExecutionData, boolean summary) {
        if (legacyV1()) return legacyMap(includeExecutionData, summary);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", schemaVersion);
        if (seed != null) result.put("seed", seed);
        List<Map<String, Object>> workloadMaps = new ArrayList<Map<String, Object>>();
        for (LoadWorkload workload : workloads) workloadMaps.add(workload.toMap(includeExecutionData, summary));
        result.put("workloads", workloadMaps);
        if (!thresholds.isEmpty()) result.put("thresholds", thresholds);
        if (!evidence.isEmpty()) result.put("evidence", evidence);
        return result;
    }

    private Map<String, Object> legacyMap(boolean includeExecutionData, boolean summary) {
        LoadWorkload workload = workload();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", Version.LOAD_SCHEMA);
        if (seed != null) result.put("seed", seed);
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("type", workload.targetType());
        target.put("id", workload.targetId());
        if (includeExecutionData && !workload.targetArguments().isEmpty()) target.put("arguments", workload.targetArguments());
        result.put("target", target);
        if (includeExecutionData && !workload.inputs().isEmpty()) result.put("inputs", workload.inputs());
        Map<String, Object> load = new LinkedHashMap<String, Object>();
        if (workload.model() == Model.CLOSED) load.put("users", workload.users());
        else load.put("arrivalRate", workload.arrivalRate());
        load.put("warmup", ThinkTimePolicy.format(workload.warmup().toMillis()));
        load.put("rampUp", ThinkTimePolicy.format(workload.rampUp().toMillis()));
        load.put("duration", ThinkTimePolicy.format(workload.duration().toMillis()));
        load.put("rampDown", ThinkTimePolicy.format(workload.rampDown().toMillis()));
        if (workload.model() == Model.ARRIVAL_RATE) {
            load.put("maxConcurrent", workload.maxConcurrent());
            load.put("overloadPolicy", workload.overloadPolicy());
        }
        if (summary) load.put("model", workload.model().wireName());
        result.put("load", load);
        if (workload.model() == Model.CLOSED) {
            Map<String, Object> execution = new LinkedHashMap<String, Object>();
            execution.put("thinkTime", workload.thinkTimePolicy().toConfigValue());
            if (summary) execution.put("thinkTimePolicy", workload.thinkTimePolicy().summary());
            result.put("execution", execution);
        }
        if (!thresholds.isEmpty()) result.put("thresholds", thresholds);
        if (!evidence.isEmpty()) result.put("evidence", evidence);
        return result;
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        return LoadIsolation.deepImmutableMap(source);
    }
}
