package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-owned Prometheus catalog, immutable LOAD snapshots and companion evidence. */
final class SutMetricsService implements AutoCloseable {
    static final String OBSERVABILITY_SCHEMA = "att-load-observability/v1";
    static final String SNAPSHOT_SCHEMA = "att-sut-metrics-snapshot/v1";
    static final String COLLECTION_SCHEMA = "att-sut-metrics-manifest/v1";
    static final String SERIES_SCHEMA = "att-sut-metrics-series/v1";
    static final String OBSERVATION_SCHEMA = "att-sut-metrics-observation/v1";
    static final String MANIFEST_INTEGRITY_SCHEMA = "att-sut-metrics-manifest-integrity/v1";
    static final String THRESHOLD_EVALUATION_SCHEMA = "att-sut-threshold-evaluation/v1";
    private static final String MANIFEST_INTEGRITY_FIELD = "_integrity";
    static final int MAX_SOURCES = 64, MAX_PROFILES = 256;
    static final int MAX_SCHEDULED_COLLECTIONS = 128, MAX_MANIFEST_TRANSITIONS = 16;
    static final long MAX_MANIFEST_BYTES = 8L * 1024 * 1024;
    static final int MAX_EXPANDED_METRICS = 64, MAX_EXPANDED_TARGETS = 64, MAX_EXPANDED_QUERIES = 256;
    static final int MAX_THRESHOLD_RULES = 64, MAX_THRESHOLD_SUBJECTS = 256;
    static final long MAX_COLLECTION_WINDOW_SECONDS = 26L * 60 * 60;
    static final long MAX_THRESHOLD_EVALUATION_MILLIS = 30_000L;
    static final long MAX_SEGMENT_SECONDS = 6L * 60 * 60;
    static final long MAX_JOB_COLLECTION_BYTES = 64L * 1024 * 1024;
    static final long MAX_JOB_SAMPLES = 1_500_000L;
    static final long MAX_LIVE_PREVIEW_SAMPLES = 100_000L;
    static final int MAX_SERIES_FILE_BYTES = 2 * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Pattern JOB_ID = Pattern.compile("J[0-9A-F]{16}");
    private static final Pattern PARAM = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern SECRET_REF = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)(ms|s|m|h|d|w|y)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}" );
    private static final Pattern SAFE_NUMBER = Pattern.compile("-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");
    private final ObjectMapper json = ServerRuntime.JSON;
    private final Path dataDir, jobsRoot, catalogPath;
    private final Set<String> allowedPrometheusHosts;
    private final Function<String, String> secretResolver;
    private final Consumer<Path> afterManifestPublication;
    private final Consumer<String> beforeThresholdEvidenceRead;
    private final Object catalogLock = new Object();
    private final ConcurrentHashMap<String, ManifestWriteLock> manifestWriteLocks = new ConcurrentHashMap<>();
    private Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> profiles = new LinkedHashMap<>();
    private final ExecutorService collectors = boundedExecutor(2, 64, "att-sut-metrics-collector");
    private final ExecutorService evaluators = boundedExecutor(2, 32, "att-sut-threshold-evaluator");
    private final ScheduledThreadPoolExecutor collectionScheduler = scheduledExecutor("att-sut-metrics-scheduler");
    private final Set<String> scheduledCollections = ConcurrentHashMap.newKeySet();
    private final Set<String> evaluatingJobs = ConcurrentHashMap.newKeySet();
    private final ExecutorService previews = boundedExecutor(4, 16, "att-sut-metrics-preview");
    private final ExecutorService bodyReaders = boundedExecutor(4, 16, "att-sut-metrics-response");
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> previewFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> previewCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HttpClient> clients = new ConcurrentHashMap<>();

    SutMetricsService(Path dataDir) throws Exception {
        this(dataDir, configuredAllowedPrometheusHosts());
    }

    SutMetricsService(Path dataDir, Function<String, String> secretResolver) throws Exception {
        this(dataDir, configuredAllowedPrometheusHosts(), secretResolver);
    }

    SutMetricsService(Path dataDir, Set<String> allowedPrometheusHosts) throws Exception {
        this(dataDir, allowedPrometheusHosts, ref -> System.getenv("ATT_SECRET_" + ref));
    }

    SutMetricsService(Path dataDir, Set<String> allowedPrometheusHosts, Function<String, String> secretResolver) throws Exception {
        this(dataDir, allowedPrometheusHosts, secretResolver, ignored -> { });
    }

    SutMetricsService(Path dataDir, Set<String> allowedPrometheusHosts, Function<String, String> secretResolver,
                      Consumer<Path> afterManifestPublication) throws Exception {
        this(dataDir, allowedPrometheusHosts, secretResolver, afterManifestPublication, ignored -> { });
    }

    SutMetricsService(Path dataDir, Set<String> allowedPrometheusHosts, Function<String, String> secretResolver,
                      Consumer<Path> afterManifestPublication, Consumer<String> beforeThresholdEvidenceRead) throws Exception {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.jobsRoot = this.dataDir.resolve("jobs");
        this.catalogPath = this.dataDir.resolve("sut-metrics-catalog.json");
        this.allowedPrometheusHosts = normalizeAllowedHosts(allowedPrometheusHosts);
        this.secretResolver = java.util.Objects.requireNonNull(secretResolver, "secretResolver");
        this.afterManifestPublication = java.util.Objects.requireNonNull(afterManifestPublication, "afterManifestPublication");
        this.beforeThresholdEvidenceRead = java.util.Objects.requireNonNull(beforeThresholdEvidenceRead, "beforeThresholdEvidenceRead");
        loadCatalog();
        recoverInterruptedCollections();
        recoverThresholdEvaluations();
    }

    Map<String, Object> listSources(String principal, boolean admin) {
        synchronized (catalogLock) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> source : sources.values()) if (admin || sourceAllowed(source, principal)) result.add(sourceView(source, admin));
            result.sort(Comparator.comparing(value -> String.valueOf(value.get("id"))));
            return fields("schemaVersion", "att-sut-metrics-sources/v1", "items", result);
        }
    }

    Map<String, Object> listProfiles(String principal, boolean admin) {
        synchronized (catalogLock) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> profile : profiles.values()) {
                if (admin || profileReadable(profile, principal)) result.add(profileView(profile, admin));
            }
            result.sort(Comparator.comparing(value -> String.valueOf(value.get("id"))));
            return fields("schemaVersion", "att-sut-metrics-profiles/v1", "items", result);
        }
    }

    Map<String, Object> upsertSource(Map<String, Object> submitted) throws Exception {
        Map<String, Object> source = validateSource(submitted, true);
        synchronized (catalogLock) {
            Map<String, Map<String, Object>> prior = sources;
            Map<String, Map<String, Object>> updated = deepMap(prior);
            if (!updated.containsKey(String.valueOf(source.get("id"))) && updated.size() >= MAX_SOURCES)
                throw new IllegalArgumentException("Data Source catalog exceeds 64 sources.");
            updated.put(String.valueOf(source.get("id")), source);
            sources = updated;
            try { saveCatalog(); clients.keySet().removeIf(key -> key.startsWith(source.get("id") + ":")); }
            catch (Exception failure) { sources = prior; throw failure; }
        }
        return sourceView(source, true);
    }

    Map<String, Object> upsertProfile(Map<String, Object> submitted) throws Exception {
        synchronized (catalogLock) {
            Map<String, Object> profile = validateProfile(submitted);
            Map<String, Map<String, Object>> prior = profiles;
            Map<String, Map<String, Object>> updated = deepMap(prior);
            updated.put(String.valueOf(profile.get("id")), profile);
            profiles = updated;
            try { validateProfileGraph(); saveCatalog(); }
            catch (Exception failure) { profiles = prior; throw failure; }
            return profileView(profile, true);
        }
    }

    Map<String, Object> deleteSource(String id) throws Exception {
        synchronized (catalogLock) {
            if (!sources.containsKey(id)) throw new IllegalArgumentException("Data Source was not found.");
            for (Map<String, Object> profile : profiles.values()) if (profileReferencesSource(profile, id))
                throw new IllegalArgumentException("A Metrics Profile still references this Data Source.");
            Map<String, Map<String, Object>> prior = sources;
            Map<String, Map<String, Object>> updated = deepMap(prior); updated.remove(id); sources = updated;
            try { saveCatalog(); } catch (Exception failure) { sources = prior; throw failure; }
            clients.keySet().removeIf(key -> key.startsWith(id + ":"));
            return fields("deleted", Boolean.TRUE, "id", id);
        }
    }

    Map<String, Object> deleteProfile(String id) throws Exception {
        synchronized (catalogLock) {
            if (!profiles.containsKey(id)) throw new IllegalArgumentException("Metrics Profile was not found.");
            for (Map<String, Object> profile : profiles.values()) if (!id.equals(profile.get("id")) && profileReferencesProfile(profile, id))
                throw new IllegalArgumentException("Another Metrics Profile still references this profile.");
            Map<String, Map<String, Object>> prior = profiles;
            Map<String, Map<String, Object>> updated = deepMap(prior); updated.remove(id); profiles = updated;
            try { saveCatalog(); } catch (Exception failure) { profiles = prior; throw failure; }
            return fields("deleted", Boolean.TRUE, "id", id);
        }
    }

    Map<String, Object> testSource(String id) throws Exception {
        Map<String, Object> source = source(id);
        Instant end = Instant.now(), start = end.minusSeconds(60);
        long began = System.nanoTime();
        queryRange(source, "up", start, end, Duration.ofSeconds(30), null);
        return fields("ok", Boolean.TRUE, "sourceId", id, "testedAt", end.toString(),
                "latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began),
                "message", "Prometheus query_range responded successfully.");
    }

    Map<String, Object> resolveScenario(Map<String, Object> observability, String principal) {
        return resolveScenario(observability, principal, false);
    }

    Map<String, Object> resolveScenario(Map<String, Object> observability, String principal, boolean admin) {
        if (observability == null) return null;
        if (!OBSERVABILITY_SCHEMA.equals(observability.get("schemaVersion")))
            throw new IllegalArgumentException("observability.schemaVersion must be " + OBSERVABILITY_SCHEMA + ".");
        rejectUnknown(observability, Set.of("schemaVersion", "collections", "collection", "livePreview", "thresholds"), "observability");
        Object rawCollections = observability.get("collections");
        if (!(rawCollections instanceof List<?> collectionList) || collectionList.isEmpty() || collectionList.size() > 16)
            throw new IllegalArgumentException("observability.collections must contain 1 to 16 profile bindings.");
        Map<String, Object> collection = object(observability.get("collection"), "observability.collection");
        Map<String, Object> live = objectOrEmpty(observability.get("livePreview"));
        validateCollectionOptions(collection);
        validateLiveOptions(live);
        List<Map<String, Object>> expanded = new ArrayList<>();
        List<Map<String, Object>> provenance = new ArrayList<>();
        Map<String, Map<String, Object>> pinnedSources = new LinkedHashMap<>();
        Set<String> duplicateTargetMetric = new HashSet<>();
        Set<String> targets = new LinkedHashSet<>();
        Set<String> metricIds = new LinkedHashSet<>();
        Map<String, Set<String>> sourceTargets = new LinkedHashMap<>();
        Map<String, Set<String>> sourceMetrics = new LinkedHashMap<>();
        int bindingIndex = 0;
        Set<String> bindingIds = new HashSet<>(), explicitBindingIds = new HashSet<>();
        synchronized (catalogLock) {
            for (Object rawBinding : collectionList) {
                Map<String, Object> binding = object(rawBinding, "observability.collections[" + bindingIndex + "]");
                rejectUnknown(binding, Set.of("id", "profile", "source", "tenant", "targets", "parameters", "targetIds"), "profile binding");
                String profileId = requiredText(binding, "profile");
                String bindingId = binding.containsKey("id") ? requiredText(binding, "id") : profileId;
                boolean explicitBindingId = binding.containsKey("id");
                if (!ID.matcher(bindingId).matches()) throw new IllegalArgumentException("Profile binding id is invalid.");
                if (!bindingIds.add(bindingId) && (explicitBindingId || explicitBindingIds.contains(bindingId)))
                    throw new IllegalArgumentException("Profile binding ids must be unique when explicitly supplied.");
                if (explicitBindingId) explicitBindingIds.add(bindingId);
                Set<String> selected = stringSet(binding.get("targetIds"), "targetIds");
                int before = expanded.size();
                expand(profileId, binding, bindingId, profileId, expanded, provenance, principal, admin, selected);
                if (expanded.size() == before) throw new IllegalArgumentException("Profile binding " + profileId + " resolved to no metrics or targets.");
                bindingIndex++;
            }
            for (Map<String, Object> query : expanded) {
                String sourceId = String.valueOf(query.get("sourceId"));
                Map<String, Object> source = sources.get(sourceId);
                if (source == null) throw new IllegalArgumentException("Data Source not found: " + sourceId + ".");
                pinnedSources.putIfAbsent(sourceId, ServerRuntime.JSON.convertValue(source, Map.class));
            }
        }
        for (Map<String, Object> query : expanded) {
            String sourceId = String.valueOf(query.get("sourceId"));
            String profilePath = String.valueOf(query.get("profilePath"));
            String targetId = String.valueOf(query.get("targetId"));
            String targetKey = sourceId + "\u0000" + targetId;
            String metricId = String.valueOf(query.get("metricId"));
            if (!duplicateTargetMetric.add(targetKey + "\u0000" + metricId))
                throw new IllegalArgumentException("Duplicate metric id " + metricId + " resolves within target " + targetId + ".");
            targets.add(sourceId + "\u0000" + targetId);
            metricIds.add(sourceId + "\u0000" + profilePath + "\u0000" + metricId);
            sourceTargets.computeIfAbsent(sourceId, ignored -> new LinkedHashSet<>()).add(targetId);
            sourceMetrics.computeIfAbsent(sourceId, ignored -> new LinkedHashSet<>()).add(profilePath + "\u0000" + metricId);
        }
        if (expanded.size() > MAX_EXPANDED_QUERIES || targets.size() > MAX_EXPANDED_TARGETS || metricIds.size() > MAX_EXPANDED_METRICS)
            throw new IllegalArgumentException("Resolved Metrics Profile exceeds the 64 metrics, 64 targets, or 256 query budget.");
        for (String sourceId : pinnedSources.keySet()) {
            Map<String, Object> source = pinnedSources.get(sourceId);
            if (sourceTargets.getOrDefault(sourceId, Set.of()).size() > sourceInt(source, "maxTargets"))
                throw new IllegalArgumentException("Resolved profile exceeds the Data Source target limit.");
            if (sourceMetrics.getOrDefault(sourceId, Set.of()).size() > sourceInt(source, "maxMetrics"))
                throw new IllegalArgumentException("Resolved profile exceeds the Data Source metric limit.");
        }
        long before = durationSeconds(String.valueOf(collection.containsKey("before") ? collection.get("before") : "5m"), "observability.collection.before");
        long after = durationSeconds(String.valueOf(collection.containsKey("after") ? collection.get("after") : "2m"), "observability.collection.after");
        String step = String.valueOf(collection.getOrDefault("step", "30s"));
        long stepSeconds = durationSeconds(step, "observability.collection.step");
        if (stepSeconds < 15 || stepSeconds > 300) throw new IllegalArgumentException("observability.collection.step must be between 15s and 5m.");
        Map<String, Object> safeCollection = fields("mode", "post-run", "step", step, "before", before + "s", "after", after + "s");
        Map<String, Object> safeLive = fields("enabled", live.getOrDefault("enabled", Boolean.TRUE),
                "refreshInterval", String.valueOf(live.getOrDefault("refreshInterval", "30s")),
                "defaultRange", String.valueOf(live.getOrDefault("defaultRange", "15m")));
        List<Map<String, Object>> thresholdRules = normalizeThresholdRules(observability.get("thresholds"), expanded);
        String snapshotDigest;
        try { snapshotDigest = sha256(json.writeValueAsBytes(fields("queries", expanded, "sources", pinnedSources,
                "collection", safeCollection, "livePreview", safeLive, "thresholds", thresholdRules))); }
        catch (Exception impossible) { throw new IllegalStateException("Could not canonicalize SUT Metrics snapshot.", impossible); }
        return fields("schemaVersion", SNAPSHOT_SCHEMA, "snapshotId", snapshotDigest,
                "createdAt", Instant.now().toString(), "collection", safeCollection, "livePreview", safeLive,
                "provenance", provenance, "queries", expanded, "sources", pinnedSources, "thresholds", thresholdRules,
                "budgets", fields("metrics", expanded.size(),
                        "targets", targets.size(), "maxWindowSeconds", MAX_COLLECTION_WINDOW_SECONDS));
    }

    private List<Map<String, Object>> normalizeThresholdRules(Object raw, List<Map<String, Object>> queries) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> rules) || rules.isEmpty() || rules.size() > MAX_THRESHOLD_RULES)
            throw new IllegalArgumentException("observability.thresholds must contain 1 to " + MAX_THRESHOLD_RULES + " rules.");
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int subjects = 0;
        for (int index = 0; index < rules.size(); index++) {
            Map<String, Object> rule = object(rules.get(index), "observability.thresholds[" + index + "]");
            rejectUnknown(rule, Set.of("id", "profile", "metric", "max", "reduction", "operator", "value", "unit",
                    "window", "targets", "minimumCoverage", "onInsufficientData", "required"), "SUT threshold rule");
            String id = requiredText(rule, "id");
            if (!ID.matcher(id).matches() || !ids.add(id)) throw new IllegalArgumentException("SUT threshold ids must be valid and unique.");
            String metricId = requiredText(rule, "metric");
            String selectedProfile = nullableText(rule.get("profile"));
            boolean selectedBindingAlias = selectedProfile != null && queries.stream()
                    .anyMatch(query -> selectedProfile.equals(query.get("bindingId")));
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Map<String, Object> query : queries) {
                if (!metricId.equals(query.get("metricId"))) continue;
                if (selectedProfile != null && (selectedBindingAlias
                        ? !selectedProfile.equals(query.get("bindingId"))
                        : !selectedProfile.equals(query.get("profilePath"))
                        && !selectedProfile.equals(query.get("profile")))) continue;
                matches.add(query);
            }
            if (matches.isEmpty()) throw new IllegalArgumentException("SUT threshold " + id + " refers to an unknown profile or metric.");
            Set<String> profilePaths = new LinkedHashSet<>(), kinds = new LinkedHashSet<>(), units = new LinkedHashSet<>();
            Set<String> bindingAliases = new LinkedHashSet<>();
            List<String> queryIds = new ArrayList<>();
            for (Map<String, Object> query : matches) {
                profilePaths.add(String.valueOf(query.get("profilePath")));
                kinds.add(String.valueOf(query.get("kind")));
                units.add(String.valueOf(query.get("unit")));
                bindingAliases.add(String.valueOf(query.get("bindingId")));
                queryIds.add(String.valueOf(query.get("id")));
            }
            if (profilePaths.size() > 1 || bindingAliases.size() > 1 && !selectedBindingAlias)
                throw new IllegalArgumentException("SUT threshold " + id + " is ambiguous; select an effective profile binding or profile path.");
            if (kinds.size() != 1 || !Set.of("gauge", "rate").contains(kinds.iterator().next()))
                throw new IllegalArgumentException("SUT threshold " + id + " supports sampled gauge and rate metrics only.");
            if (units.size() != 1) throw new IllegalArgumentException("SUT threshold " + id + " resolves to metrics with different units.");
            String metricUnit = units.iterator().next();
            if (rule.containsKey("unit") && !metricUnit.equals(requiredText(rule, "unit")))
                throw new IllegalArgumentException("SUT threshold " + id + " unit must match the selected metric unit.");
            boolean shorthand = rule.containsKey("max");
            boolean explicit = rule.containsKey("reduction") || rule.containsKey("operator") || rule.containsKey("value");
            if (shorthand == explicit) throw new IllegalArgumentException("SUT threshold " + id + " must use max shorthand or one canonical reduction/operator/value rule.");
            if (shorthand && rule.keySet().stream().anyMatch(key -> Set.of("reduction", "operator", "value").contains(key)))
                throw new IllegalArgumentException("SUT threshold " + id + " cannot mix max shorthand with canonical rule fields.");
            String reduction = shorthand ? "max" : String.valueOf(rule.get("reduction"));
            String operator = shorthand ? "<=" : String.valueOf(rule.get("operator"));
            Object rawValue = shorthand ? rule.get("max") : rule.get("value");
            if (!"max".equals(reduction) || !"<=".equals(operator))
                throw new IllegalArgumentException("SUT threshold " + id + " supports only reduction max with operator <=.");
            if (!(rawValue instanceof Number number) || !Double.isFinite(number.doubleValue()))
                throw new IllegalArgumentException("SUT threshold " + id + " value must be a finite number.");
            Object rawWindow = rule.getOrDefault("window", "steady");
            if (!(rawWindow instanceof String window) || !"steady".equals(window))
                throw new IllegalArgumentException("SUT threshold " + id + " supports only the steady window.");
            Object rawTargets = rule.getOrDefault("targets", "all");
            if (!(rawTargets instanceof String targets) || !"all".equals(targets))
                throw new IllegalArgumentException("SUT threshold " + id + " supports targets: all only.");
            Object rawCoverage = rule.getOrDefault("minimumCoverage", 0.95d);
            if (!(rawCoverage instanceof Number coverage) || !Double.isFinite(coverage.doubleValue())
                    || coverage.doubleValue() <= 0.0d || coverage.doubleValue() > 1.0d)
                throw new IllegalArgumentException("SUT threshold " + id + " minimumCoverage must be greater than 0 and at most 1.");
            Object rawInsufficient = rule.getOrDefault("onInsufficientData", "NOT_EVALUATED");
            if (!(rawInsufficient instanceof String insufficient) || !"NOT_EVALUATED".equals(insufficient))
                throw new IllegalArgumentException("SUT threshold " + id + " onInsufficientData must be NOT_EVALUATED.");
            Object rawRequired = rule.getOrDefault("required", Boolean.TRUE);
            if (!(rawRequired instanceof Boolean required))
                throw new IllegalArgumentException("SUT threshold " + id + " required must be a boolean.");
            subjects += queryIds.size();
            if (subjects > MAX_THRESHOLD_SUBJECTS)
                throw new IllegalArgumentException("SUT threshold target expansion exceeds the " + MAX_THRESHOLD_SUBJECTS + " subject budget.");
            String profilePath = profilePaths.iterator().next();
            normalized.add(fields("id", id, "profile", selectedProfile == null ? profilePath : selectedProfile,
                    "bindingId", bindingAliases.size() == 1 ? bindingAliases.iterator().next() : null,
                    "profilePath", profilePath, "metricId", metricId, "metricName", matches.get(0).get("title"),
                    "unit", metricUnit, "kind", kinds.iterator().next(), "reduction", reduction,
                    "operator", operator, "value", number.doubleValue(), "window", window, "targets", targets,
                    "minimumCoverage", coverage.doubleValue(), "onInsufficientData", insufficient,
                    "required", required, "queryIds", queryIds));
        }
        return normalized;
    }

    Map<String, Object> previewResolution(Map<String, Object> observability, String principal, boolean admin) {
        Map<String, Object> snapshot = resolveScenario(observability, principal, admin);
        if (snapshot == null) return fields("configured", Boolean.FALSE, "items", List.of());
        return previewSnapshot(snapshot);
    }

    Map<String, Object> previewSnapshot(Map<String, Object> snapshot) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> query : listOfMaps(snapshot.get("queries"))) {
            Map<String, Object> row = fields("id", query.get("id"), "profile", query.get("profile"), "profilePath", query.get("profilePath"),
                    "sourceId", query.get("sourceId"), "sourceName", query.get("sourceName"), "targetId", query.get("targetId"),
                    "targetType", query.get("targetType"), "metricId", query.get("metricId"), "title", query.get("title"),
                    "category", query.get("category"), "unit", query.get("unit"), "parameters", query.get("parameters"),
                    "queryPreview", query.get("query"));
            if (query.get("bindingId") != null) row.put("bindingId", query.get("bindingId"));
            rows.add(row);
        }
        return fields("configured", Boolean.TRUE, "schemaVersion", SNAPSHOT_SCHEMA,
                "snapshotId", snapshot.get("snapshotId"), "collection", snapshot.get("collection"),
                "thresholds", snapshot.get("thresholds"), "items", rows);
    }

    void writeSnapshot(String jobId, Map<String, Object> snapshot) throws Exception {
        Path directory = jobMetricsDirectory(jobId);
        Files.createDirectories(directory);
        Map<String, Object> initialManifest = fields("schemaVersion", COLLECTION_SCHEMA, "state", "PENDING", "complete", Boolean.FALSE,
                "jobId", jobId, "snapshotId", snapshot.get("snapshotId"), "createdAt", Instant.now().toString(), "updatedAt", Instant.now().toString(),
                "queries", queryMetadata(snapshot), "files", List.of(), "diagnostics", List.of());
        byte[] initialManifestBytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(initialManifest);
        Map<String, Object> content = fields("schemaVersion", SNAPSHOT_SCHEMA, "snapshot", snapshot,
                "snapshotSha256", sha256(json.writeValueAsBytes(snapshot)), "jobId", jobId,
                "initialManifestSha256", sha256(initialManifestBytes));
        atomicJson(directory.resolve("snapshot.json"), content);
        writeManifest(directory, initialManifest);
        if (!listOfMaps(snapshot.get("thresholds")).isEmpty()) {
            Instant now = Instant.now();
            writeThresholdEvaluation(jobId, snapshot, fields("state", "PENDING", "complete", Boolean.FALSE,
                    "attempt", 0, "createdAt", now.toString(), "updatedAt", now.toString(),
                    "collectionState", "PENDING", "effectiveRules", snapshot.get("thresholds"),
                    "diagnostics", List.of()));
        }
    }

    Map<String, Object> snapshotForJob(String jobId) throws Exception {
        SnapshotEnvelope envelope = snapshotEnvelope(jobId);
        return envelope == null ? null : envelope.snapshot();
    }

    private SnapshotEnvelope snapshotEnvelope(String jobId) throws Exception {
        Path file = jobMetricsDirectory(jobId).resolve("snapshot.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return null;
        if (Files.size(file) > MAX_MANIFEST_BYTES) throw new IllegalStateException("The immutable SUT Metrics Job snapshot exceeds its read limit.");
        JsonNode wrapper = json.readTree(file.toFile());
        JsonNode snapshot = wrapper.path("snapshot");
        String digest = wrapper.path("snapshotSha256").asText();
        if (!SNAPSHOT_SCHEMA.equals(snapshot.path("schemaVersion").asText())
                || !digest.equals(sha256(json.writeValueAsBytes(snapshot)))
                || wrapper.hasNonNull("jobId") && !jobId.equals(wrapper.path("jobId").asText()))
            throw new IllegalStateException("The immutable SUT Metrics Job snapshot failed integrity verification.");
        return new SnapshotEnvelope(json.convertValue(snapshot, Map.class), digest, wrapper.path("initialManifestSha256").asText(null));
    }

    Map<String, Object> collectionStatus(String jobId) throws Exception {
        SnapshotEnvelope envelope = snapshotEnvelope(jobId);
        if (envelope == null) return fields("schemaVersion", COLLECTION_SCHEMA, "state", "DISABLED", "complete", Boolean.TRUE,
                "jobId", jobId, "queries", List.of(), "files", List.of());
        Path directory = jobMetricsDirectory(jobId);
        Path manifest = directory.resolve("manifest.json");
        Path history = directory.resolve("manifest-integrity.json");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest)) {
            if (Files.exists(history, LinkOption.NOFOLLOW_LINKS) || envelope.initialManifestSha256() != null)
                throw new IllegalStateException("The SUT Metrics companion manifest is missing from its integrity record.");
            return fields("schemaVersion", COLLECTION_SCHEMA, "state", "PENDING", "complete", Boolean.FALSE,
                    "jobId", jobId, "snapshotId", envelope.snapshot().get("snapshotId"), "queries", queryMetadata(envelope.snapshot()), "files", List.of());
        }
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) > MAX_MANIFEST_BYTES)
            throw new IllegalStateException("The SUT Metrics companion manifest exceeds its read limit.");
        byte[] bytes = Files.readAllBytes(manifest);
        Map<String, Object> value = json.readValue(bytes, Map.class);
        verifyManifestIntegrity(directory, bytes, value, envelope, true);
        validateManifest(value, jobId, envelope.snapshot());
        return value;
    }

    Map<String, Object> thresholdEvaluationStatus(String jobId) throws Exception {
        SnapshotEnvelope envelope = snapshotEnvelope(jobId);
        if (envelope == null || listOfMaps(envelope.snapshot().get("thresholds")).isEmpty())
            return fields("schemaVersion", THRESHOLD_EVALUATION_SCHEMA, "state", "DISABLED", "complete", Boolean.TRUE,
                    "jobId", jobId, "items", List.of(), "targets", List.of());
        Map<String, Object> record = readThresholdEvaluation(jobId, envelope);
        if (record != null) return record;
        return fields("schemaVersion", THRESHOLD_EVALUATION_SCHEMA, "state", "PENDING", "complete", Boolean.FALSE,
                "jobId", jobId, "snapshotId", envelope.snapshot().get("snapshotId"), "attempt", 0,
                "effectiveRules", envelope.snapshot().get("thresholds"), "collectionState", collectionStatus(jobId).get("state"),
                "items", List.of(), "targets", List.of());
    }

    private Map<String, Object> readThresholdEvaluation(String jobId, SnapshotEnvelope envelope) throws Exception {
        Path file = jobMetricsDirectory(jobId).resolve("threshold-evaluation.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return null;
        if (Files.size(file) > 1024L * 1024L) throw new IllegalStateException("The durable SUT threshold evaluation exceeds its read limit.");
        Map<String, Object> record = json.readValue(file.toFile(), Map.class);
        validateThresholdEvaluation(record, jobId, envelope);
        return record;
    }

    void writeThresholdEvaluation(String jobId, Map<String, Object> snapshot, Map<String, Object> update) throws Exception {
        SnapshotEnvelope envelope = snapshotEnvelope(jobId);
        if (envelope == null || !String.valueOf(snapshot.get("snapshotId")).equals(envelope.snapshot().get("snapshotId")))
            throw new IllegalStateException("The SUT threshold evaluation does not match its immutable Job snapshot.");
        Map<String, Object> record = new LinkedHashMap<>(update);
        record.put("schemaVersion", THRESHOLD_EVALUATION_SCHEMA);
        record.put("jobId", jobId);
        record.put("snapshotId", envelope.snapshot().get("snapshotId"));
        record.put("snapshotSha256", envelope.snapshotSha256());
        record.put("effectiveRules", snapshot.get("thresholds"));
        record.putIfAbsent("createdAt", Instant.now().toString());
        record.put("updatedAt", Instant.now().toString());
        if (record.get("sutThresholds") == null) record.remove("sutThresholds");
        validateThresholdEvaluation(record, jobId, envelope);
        Path directory = jobMetricsDirectory(jobId);
        Files.createDirectories(directory);
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
        if (bytes.length > 1024L * 1024L) throw new IllegalStateException("The durable SUT threshold evaluation exceeds its write limit.");
        atomicBytes(directory.resolve("threshold-evaluation.json"), bytes);
    }

    private void validateThresholdEvaluation(Map<String, Object> record, String jobId, SnapshotEnvelope envelope) {
        if (!THRESHOLD_EVALUATION_SCHEMA.equals(record.get("schemaVersion")) || !jobId.equals(record.get("jobId"))
                || !String.valueOf(envelope.snapshot().get("snapshotId")).equals(record.get("snapshotId"))
                || !envelope.snapshotSha256().equals(record.get("snapshotSha256")))
            throw new IllegalStateException("The SUT threshold evaluation identity does not match its immutable Job snapshot.");
        Object stateValue = record.get("state"), completeValue = record.get("complete"), attemptValue = record.get("attempt");
        if (!(stateValue instanceof String state) || !Set.of("PENDING", "EVALUATING", "MET", "NOT_MET", "NOT_EVALUATED", "ERROR").contains(state)
                || !(completeValue instanceof Boolean complete) || complete != Set.of("MET", "NOT_MET", "NOT_EVALUATED", "ERROR").contains(state)
                || !(attemptValue instanceof Number attempt) || attempt.intValue() < 0 || attempt.intValue() > 2
                || !(record.get("effectiveRules") instanceof List<?> rules) || rules.size() > MAX_THRESHOLD_RULES
                || !(record.get("diagnostics") instanceof List<?> diagnostics) || diagnostics.size() > 4)
            throw new IllegalStateException("The durable SUT threshold evaluation structure is invalid.");
        try {
            byte[] expected = json.writeValueAsBytes(envelope.snapshot().get("thresholds"));
            byte[] actual = json.writeValueAsBytes(record.get("effectiveRules"));
            if (!java.util.Arrays.equals(expected, actual)) throw new IllegalStateException("The SUT threshold evaluation rules do not match the immutable Job snapshot.");
            Object results = record.get("sutThresholds");
            if (results instanceof Map<?, ?> values) {
                if (listOfMaps(values.get("targets")).size() > MAX_THRESHOLD_SUBJECTS
                        || listOfMaps(values.get("items")).size() > MAX_THRESHOLD_RULES)
                    throw new IllegalStateException("The durable SUT threshold evaluation exceeds its result limit.");
            } else if (Set.of("MET", "NOT_MET", "NOT_EVALUATED").contains(state)) {
                throw new IllegalStateException("The final SUT threshold evaluation is missing its result.");
            }
        } catch (Exception invalid) {
            if (invalid instanceof IllegalStateException invalidState) throw invalidState;
            throw new IllegalStateException("The durable SUT threshold evaluation could not be verified.", invalid);
        }
    }

    private void recoverThresholdEvaluations() throws Exception {
        if (!Files.isDirectory(jobsRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jobsRoot)) return;
        try (var jobDirectories = Files.newDirectoryStream(jobsRoot)) {
            for (Path jobDirectory : jobDirectories) {
                String jobId = jobDirectory.getFileName().toString();
                if (!JOB_ID.matcher(jobId).matches() || !Files.isDirectory(jobDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(jobDirectory)) continue;
                try {
                    SnapshotEnvelope envelope = snapshotEnvelope(jobId);
                    if (envelope == null || listOfMaps(envelope.snapshot().get("thresholds")).isEmpty()) continue;
                    String collectionState = String.valueOf(collectionStatus(jobId).get("state"));
                    if (!Set.of("READY", "PARTIAL", "UNAVAILABLE", "FAILED").contains(collectionState)) continue;
                    Map<String, Object> record = readThresholdEvaluation(jobId, envelope);
                    String state = record == null ? "PENDING" : String.valueOf(record.get("state"));
                    int attempts = record != null && record.get("attempt") instanceof Number number ? number.intValue() : 0;
                    if (Set.of("PENDING", "EVALUATING").contains(state) || "ERROR".equals(state) && attempts < 2)
                        scheduleThresholdEvaluation(jobId);
                } catch (Exception invalid) { /* Retain corrupt data for the authorized integrity-error path. */ }
            }
        }
    }

    private void scheduleThresholdEvaluation(String jobId) {
        if (!evaluatingJobs.add(jobId)) return;
        Map<String, Object> snapshot = null;
        Map<String, Object> record;
        int attempt = 0;
        String collectionState = "UNKNOWN";
        boolean submitted = false;
        boolean recordReadCompleted = false;
        try {
            snapshot = snapshotForJob(jobId);
            if (snapshot == null || listOfMaps(snapshot.get("thresholds")).isEmpty()) return;
            Map<String, Object> collection = collectionStatus(jobId);
            collectionState = String.valueOf(collection.get("state"));
            if (!Set.of("READY", "PARTIAL", "UNAVAILABLE", "FAILED").contains(collectionState)) return;
            SnapshotEnvelope envelope = snapshotEnvelope(jobId);
            record = readThresholdEvaluation(jobId, envelope);
            recordReadCompleted = true;
            String priorState = record == null ? "PENDING" : String.valueOf(record.get("state"));
            int priorAttempt = record != null && record.get("attempt") instanceof Number number ? number.intValue() : 0;
            if (Set.of("MET", "NOT_MET", "NOT_EVALUATED").contains(priorState) || "ERROR".equals(priorState) && priorAttempt >= 2) return;
            attempt = priorAttempt + 1;
            Map<String, Object> evaluating = record == null ? new LinkedHashMap<>() : new LinkedHashMap<>(record);
            evaluating.putAll(fields("state", "EVALUATING", "complete", Boolean.FALSE, "attempt", attempt,
                    "collectionState", collectionState, "diagnostics", List.of()));
            writeThresholdEvaluation(jobId, snapshot, evaluating);
            Map<String, Object> evaluationSnapshot = snapshot;
            Map<String, Object> collectionSnapshot = collection;
            int evaluationAttempt = attempt;
            evaluators.execute(() -> runThresholdEvaluation(jobId, evaluationSnapshot, collectionSnapshot, evaluationAttempt));
            submitted = true;
        } catch (java.util.concurrent.RejectedExecutionException full) {
            if (snapshot != null) try {
                int recordedAttempt = attempt >= 2 ? 2 : Math.max(0, attempt - 1);
                writeThresholdEvaluation(jobId, snapshot, evaluationError(snapshot, collectionState,
                        "EVALUATION_CAPACITY", "SUT threshold evaluation capacity is full.", recordedAttempt));
                if (attempt < 2) collectionScheduler.schedule(() -> scheduleThresholdEvaluation(jobId), 1, TimeUnit.SECONDS);
            } catch (Exception ignored) { }
        } catch (Exception failure) {
            if (snapshot != null && recordReadCompleted) {
                try { writeThresholdEvaluation(jobId, snapshot, evaluationError(snapshot, collectionState,
                        "EVALUATION_FAILED", "The durable SUT threshold evaluation could not start.", Math.min(2, attempt))); }
                catch (Exception ignored) { }
            }
        } finally {
            if (!submitted) evaluatingJobs.remove(jobId);
        }
    }

    private void runThresholdEvaluation(String jobId, Map<String, Object> snapshot,
                                        Map<String, Object> collection, int attempt) {
        boolean retry = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_THRESHOLD_EVALUATION_MILLIS);
            List<Map<String, Object>> queries = listOfMaps(snapshot.get("queries"));
            Set<String> thresholdQueryIds = new LinkedHashSet<>();
            for (Map<String, Object> rule : listOfMaps(snapshot.get("thresholds")))
                thresholdQueryIds.addAll(listOfStrings(rule.get("queryIds")));
            Map<String, Map<String, Object>> observations = new LinkedHashMap<>();
            List<Map<String, Object>> phaseWindows = listOfMaps(collection.get("phaseWindows"));
            Map<String, Object> steadyPhase = phaseWindows.stream()
                    .filter(phase -> "STEADY".equals(phase.get("phase"))).findFirst().orElse(null);
            long step = durationSeconds(String.valueOf(collection.get("step")), "step");
            long selectedBytes = 0L;
            for (Map<String, Object> entry : listOfMaps(collection.get("files"))) {
                String queryId = String.valueOf(entry.get("queryId"));
                if (!thresholdQueryIds.contains(queryId)) continue;
                SutThresholdEvaluator.checkDeadline(deadline);
                long fileBytes = entry.get("lengthBytes") instanceof Number length ? length.longValue() : -1L;
                if (fileBytes < 0 || fileBytes > MAX_JOB_COLLECTION_BYTES - selectedBytes) continue;
                selectedBytes += fileBytes;
                try {
                    beforeThresholdEvidenceRead.accept(queryId);
                    Map<String, Object> observation = readSeriesFile(jobId, entry, deadline);
                    observations.put(queryId, observation);
                    if (SutThresholdEvaluator.hasProvenViolation(listOfMaps(snapshot.get("thresholds")), queryId,
                            observation, steadyPhase, deadline)) {
                        persistThresholdEvaluationResult(jobId, snapshot, collection, attempt, queries,
                                observations, phaseWindows, step, deadline);
                        return;
                    }
                } catch (SutThresholdEvaluator.EvaluationTimeoutException timeout) {
                    throw timeout;
                } catch (Exception unavailable) {
                    // Missing or corrupt evidence makes this subject unevaluable; other valid subjects still count.
                }
            }
            persistThresholdEvaluationResult(jobId, snapshot, collection, attempt, queries,
                    observations, phaseWindows, step, deadline);
        } catch (SutThresholdEvaluator.EvaluationTimeoutException timeout) {
            retry = attempt < 2;
            writeEvaluationFailure(jobId, snapshot, collection, attempt, "EVALUATION_TIMEOUT", timeout.getMessage());
        } catch (Exception failure) {
            retry = attempt < 2;
            writeEvaluationFailure(jobId, snapshot, collection, attempt, "EVALUATION_FAILED", safeFailureMessage(failure));
        } finally {
            evaluatingJobs.remove(jobId);
        }
        if (retry) {
            try { collectionScheduler.schedule(() -> scheduleThresholdEvaluation(jobId), 1, TimeUnit.SECONDS); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    }

    private void persistThresholdEvaluationResult(String jobId, Map<String, Object> snapshot,
                                                 Map<String, Object> collection, int attempt,
                                                 List<Map<String, Object>> queries,
                                                 Map<String, Map<String, Object>> observations,
                                                 List<Map<String, Object>> phaseWindows,
                                                 long step, long deadline) throws Exception {
        Map<String, Object> result = SutThresholdEvaluator.evaluate(listOfMaps(snapshot.get("thresholds")), queries,
                observations, phaseWindows, step, deadline);
        String state = String.valueOf(result.get("status"));
        Map<String, Object> completeness = fields("complete", Boolean.TRUE,
                "ruleCount", listOfMaps(snapshot.get("thresholds")).size(),
                "requiredRuleCount", result.get("requiredThresholdCount"),
                "targetCount", listOfMaps(result.get("targets")).size(),
                "notEvaluatedTargetCount", listOfMaps(result.get("targets")).stream()
                        .filter(target -> "NOT_EVALUATED".equals(target.get("status"))).count());
        Map<String, Object> finalRecord = fields("state", state, "complete", Boolean.TRUE,
                "attempt", attempt, "collectionState", collection.get("state"), "collectionUpdatedAt", collection.get("updatedAt"),
                "evaluatedAt", Instant.now().toString(), "window", fields("phase", "steady", "phaseWindows", collection.get("phaseWindows"), "step", collection.get("step")),
                "completeness", completeness, "sutThresholds", result, "diagnostics", List.of());
        writeThresholdEvaluation(jobId, snapshot, finalRecord);
    }

    private void writeEvaluationFailure(String jobId, Map<String, Object> snapshot, Map<String, Object> collection,
                                        int attempt, String code, String message) {
        try {
            writeThresholdEvaluation(jobId, snapshot, evaluationError(snapshot, String.valueOf(collection.get("state")), code, message, attempt));
        } catch (Exception ignored) { }
    }

    Map<String, Object> evaluationError(Map<String, Object> snapshot, String collectionState,
                                        String code, String message, int attempt) {
        boolean finalAttempt = attempt >= 2;
        return fields("state", finalAttempt ? "ERROR" : "PENDING", "complete", finalAttempt, "attempt", Math.min(2, attempt),
                "collectionState", collectionState, "evaluatedAt", Instant.now().toString(),
                "diagnostics", List.of(fields("code", code, "message", message == null ? "Evaluation failed." : message.substring(0, Math.min(240, message.length())))));
    }

    List<String> pendingCollectionJobIds() throws Exception {
        if (!Files.isDirectory(jobsRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jobsRoot)) return List.of();
        List<String> pending = new ArrayList<>();
        try (var jobDirectories = Files.newDirectoryStream(jobsRoot)) {
            for (Path jobDirectory : jobDirectories) {
                String jobId = jobDirectory.getFileName().toString();
                if (!JOB_ID.matcher(jobId).matches() || !Files.isDirectory(jobDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(jobDirectory)) continue;
                Path metricsDirectory = jobDirectory.resolve("sut-metrics");
                if (!Files.isDirectory(metricsDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(metricsDirectory)) continue;
                Path snapshot = metricsDirectory.resolve("snapshot.json");
                if (!Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(snapshot)) continue;
                try {
                    if ("PENDING".equals(collectionStatus(jobId).get("state"))) pending.add(jobId);
                } catch (Exception malformed) { /* Preserve the corrupt manifest for the normal integrity error path. */ }
            }
        }
        pending.sort(String::compareTo);
        return pending;
    }

    private void recoverInterruptedCollections() throws Exception {
        if (!Files.isDirectory(jobsRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jobsRoot)) return;
        try (var jobDirectories = Files.newDirectoryStream(jobsRoot)) {
            for (Path jobDirectory : jobDirectories) {
                if (!JOB_ID.matcher(jobDirectory.getFileName().toString()).matches()
                        || !Files.isDirectory(jobDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jobDirectory)) continue;
                Path directory = jobDirectory.resolve("sut-metrics");
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) continue;
                Map<String, Object> manifest;
                try { manifest = collectionStatus(jobDirectory.getFileName().toString()); }
                catch (Exception malformedOrTampered) { continue; }
                if (!"COLLECTING".equals(manifest.get("state"))) continue;
                List<Map<String, Object>> diagnostics = new ArrayList<>(listOfMaps(manifest.get("diagnostics")));
                diagnostics.add(fields("code", "SERVER_RESTART_INTERRUPTED",
                        "message", "The Server restarted before SUT Metrics collection completed."));
                List<Map<String, Object>> published = listOfMaps(manifest.get("files"));
                Set<String> publishedPaths = new HashSet<>();
                for (Map<String, Object> item : published) if (item.get("path") instanceof String path) publishedPaths.add(path);
                manifest.put("state", published.isEmpty() ? "FAILED" : "PARTIAL");
                manifest.put("complete", Boolean.FALSE);
                manifest.put("diagnostics", diagnostics);
                writeManifest(directory, manifest);

                try (var seriesFiles = Files.newDirectoryStream(directory, "series-*.json")) {
                    for (Path seriesFile : seriesFiles) {
                        if (!publishedPaths.contains(seriesFile.getFileName().toString())
                                && seriesFile.getFileName().toString().matches("series-[0-9]{4}\\.json")
                                && Files.isRegularFile(seriesFile, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(seriesFile))
                            Files.deleteIfExists(seriesFile);
                    }
                }
            }
        }
    }

    Map<String, Object> readCollectionPage(String jobId, int offset, int limit, String queryId,
                                          String targetId, String profilePath) throws Exception {
        Map<String, Object> manifest = collectionStatus(jobId);
        String state = String.valueOf(manifest.get("state"));
        if (!Set.of("READY", "PARTIAL").contains(state)) return fields("manifest", manifest, "items", List.of(), "nextOffset", null);
        List<Map<String, Object>> all = listOfMaps(manifest.get("files"));
        if (queryId != null && !queryId.isBlank()) all.removeIf(item -> !queryId.equals(item.get("queryId")));
        if (targetId != null && !targetId.isBlank()) all.removeIf(item -> !targetId.equals(item.get("targetId")));
        if (profilePath != null && !profilePath.isBlank()) all.removeIf(item -> !profilePath.equals(item.get("profilePath")));
        int from = Math.max(0, Math.min(offset, all.size())), to = Math.min(all.size(), from + Math.max(1, Math.min(limit, 16)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> fileEntry : all.subList(from, to)) rows.add(readSeriesFile(jobId, fileEntry));
        return fields("manifest", manifest, "items", rows, "nextOffset", to < all.size() ? to : null);
    }

    Map<String, Object> livePreview(String jobId, String principal, boolean admin, Instant startedAt,
                                    String range, String step, String targetId, String profilePath) throws Exception {
        Map<String, Object> snapshot = snapshotForJob(jobId);
        if (snapshot == null) return fields("schemaVersion", "att-sut-metrics-live/v1", "state", "DISABLED", "items", List.of());
        authorizeSnapshot(snapshot, principal, admin);
        List<Map<String, Object>> all = listOfMaps(snapshot.get("queries"));
        Instant now = Instant.now();
        if (startedAt == null) return fields("schemaVersion", "att-sut-metrics-live/v1", "state", "PENDING",
                "snapshotId", snapshot.get("snapshotId"), "queries", queryMetadata(snapshot), "items", List.of(), "message", "Live metrics begin when LOAD execution starts.");
        if (!Boolean.TRUE.equals(objectOrEmpty(snapshot.get("livePreview")).get("enabled")))
            return fields("schemaVersion", "att-sut-metrics-live/v1", "state", "DISABLED", "snapshotId", snapshot.get("snapshotId"), "items", List.of());
        long rangeSeconds = durationSeconds(range == null ? "15m" : range, "range");
        long stepSeconds = durationSeconds(step == null ? "30s" : step, "step");
        if (rangeSeconds < 60 || rangeSeconds > 4L * 60 * 60 || stepSeconds < 15 || stepSeconds > 300)
            throw new IllegalArgumentException("Live range must be 1m to 4h and step must be 15s to 5m.");
        if (targetId != null && !targetId.isBlank() && all.stream().noneMatch(query -> targetId.equals(query.get("targetId"))))
            throw new IllegalArgumentException("The selected SUT target is not part of this Job snapshot.");
        if (profilePath != null && !profilePath.isBlank() && all.stream().noneMatch(query -> profilePath.equals(query.get("profilePath"))))
            throw new IllegalArgumentException("The selected Metrics Profile is not part of this Job snapshot.");
        if (targetId != null && !targetId.isBlank()) all.removeIf(query -> !targetId.equals(query.get("targetId")));
        if (profilePath != null && !profilePath.isBlank()) all.removeIf(query -> !profilePath.equals(query.get("profilePath")));
        long roundedEnd = (now.getEpochSecond() / stepSeconds) * stepSeconds;
        Instant end = Instant.ofEpochSecond(roundedEnd);
        if (!startedAt.isBefore(end)) return fields("schemaVersion", "att-sut-metrics-live/v1", "state", "NO_DATA",
                "snapshotId", snapshot.get("snapshotId"), "queries", queryMetadata(snapshot), "items", List.of(),
                "windowStart", startedAt.toString(), "windowEnd", now.toString(), "stepSeconds", stepSeconds);
        Instant bucketStart = end.minusSeconds(rangeSeconds);
        Instant requestedStart = startedAt.isAfter(bucketStart) ? startedAt : bucketStart;
        long queryStartEpochSecond = ceilEpochSecond(requestedStart);
        if (!requestedStart.isBefore(end) || queryStartEpochSecond >= end.getEpochSecond()) return fields("schemaVersion", "att-sut-metrics-live/v1", "state", "NO_DATA",
                "snapshotId", snapshot.get("snapshotId"), "queries", queryMetadata(snapshot), "items", List.of(),
                "windowStart", requestedStart.toString(), "windowEnd", end.toString(), "stepSeconds", stepSeconds);
        // Use the exact second sent to query_range as the window/cache boundary so sub-second
        // progress timestamp jitter cannot split otherwise identical viewer requests.
        Instant start = Instant.ofEpochSecond(queryStartEpochSecond);
        if (all.isEmpty()) return fields("schemaVersion", "att-sut-metrics-live/v1", "state", "NO_DATA",
                "snapshotId", snapshot.get("snapshotId"), "queries", queryMetadata(snapshot), "items", List.of(),
                "windowStart", start.toString(), "windowEnd", end.toString(), "stepSeconds", stepSeconds);
        if (all.size() > 64) throw new IllegalArgumentException("Live preview exceeds the 64-query request budget.");
        queryRequestBudgets(snapshot, all, Duration.between(start, end).getSeconds());
        String key = String.valueOf(snapshot.get("snapshotId")) + ":" + start + ":" + end + ":" + stepSeconds + ":"
                + (targetId == null ? "*" : targetId) + ":" + (profilePath == null ? "*" : profilePath);
        Map<String, Object> cached = cachedPreview(key);
        if (cached != null) return cached;
        CompletableFuture<Map<String, Object>> flight;
        try {
            flight = previewFlights.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> {
                try { return collectPreview(snapshot, all, start, end, stepSeconds); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            }, previews).thenApply(result -> {
                previewCache.put(key, new CacheEntry(System.nanoTime() + TimeUnit.SECONDS.toNanos(5), result));
                trimPreviewCache();
                return result;
            }));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            throw new CapacityException("Live SUT Metrics capacity is full; retry shortly.");
        }
        flight.whenComplete((value, failure) -> previewFlights.remove(key, flight));
        try {
            return flight.get(35, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("Live SUT Metrics query failed.", cause);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new SutMetricsTimeoutException("Live SUT Metrics query exceeded its time limit.");
        } finally { if (flight.isDone()) previewFlights.remove(key, flight); }
    }

    void jobFinished(String jobId, Instant startedAt, Instant finishedAt) {
        jobFinished(jobId, startedAt, finishedAt, List.of());
    }

    void jobFinished(String jobId, Instant startedAt, Instant finishedAt, List<Map<String, Object>> phaseWindows) {
        try {
            Map<String, Object> snapshot = snapshotForJob(jobId);
            if (snapshot == null) return;
            if (startedAt == null || finishedAt == null || !finishedAt.isAfter(startedAt)) {
                updateState(jobId, "UNAVAILABLE", "A trustworthy LOAD execution window was not available.", List.of());
                return;
            }
            Instant start = startedAt.minusSeconds(durationSeconds(String.valueOf(objectOrEmpty(snapshot.get("collection")).get("before")), "before"));
            Instant end = finishedAt.plusSeconds(durationSeconds(String.valueOf(objectOrEmpty(snapshot.get("collection")).get("after")), "after"));
            if (Duration.between(start, end).getSeconds() > MAX_COLLECTION_WINDOW_SECONDS) {
                updateState(jobId, "FAILED", "The requested collection window exceeds the 26-hour Job budget.", List.of()); return;
            }
            Map<String, Object> prior = collectionStatus(jobId);
            if (!"PENDING".equals(prior.get("state"))) return;
            if (!scheduledCollections.add(jobId)) return;
            if (scheduledCollections.size() > MAX_SCHEDULED_COLLECTIONS) {
                scheduledCollections.remove(jobId);
                updateState(jobId, "FAILED", "Post-run collection scheduling capacity is full.", List.of());
                return;
            }
            long stepSeconds = durationSeconds(String.valueOf(objectOrEmpty(snapshot.get("collection")).get("step")), "step");
            Instant eligibleAt = end.plusSeconds(stepSeconds);
            List<Map<String, Object>> safePhases = normalizePhaseWindows(phaseWindows, startedAt, finishedAt);
            boolean alreadyScheduled = startedAt.toString().equals(prior.get("startedAt"))
                    && finishedAt.toString().equals(prior.get("finishedAt"))
                    && end.toString().equals(prior.get("windowEnd"))
                    && eligibleAt.toString().equals(prior.get("eligibleAt"));
            if (!alreadyScheduled) {
                Map<String, Object> pending = new LinkedHashMap<>(prior);
                pending.putAll(fields("state", "PENDING", "complete", Boolean.FALSE, "startedAt", startedAt.toString(),
                        "finishedAt", finishedAt.toString(), "windowStart", start.toString(), "windowEnd", end.toString(),
                        "eligibleAt", eligibleAt.toString(), "step", objectOrEmpty(snapshot.get("collection")).get("step"),
                        "phaseWindows", safePhases, "updatedAt", Instant.now().toString()));
                writeManifest(jobMetricsDirectory(jobId), pending);
            }
            long delayMillis = Math.max(0L, Duration.between(Instant.now(), eligibleAt).toMillis());
            collectionScheduler.schedule(() -> {
                try {
                    Map<String, Object> current = collectionStatus(jobId);
                    if (!"PENDING".equals(current.get("state"))) return;
                    current.put("state", "COLLECTING");
                    current.put("complete", Boolean.FALSE);
                    current.put("updatedAt", Instant.now().toString());
                    writeManifest(jobMetricsDirectory(jobId), current);
                    collectors.execute(() -> {
                        try { collectPostRun(jobId, snapshot, startedAt, finishedAt, start, end, eligibleAt, safePhases); }
                        finally { scheduledCollections.remove(jobId); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    try { updateState(jobId, "FAILED", "Post-run collection capacity is full; no collection worker was available.", List.of()); }
                    catch (Exception ignored) { }
                    scheduledCollections.remove(jobId);
                } catch (Exception failure) {
                    try { updateState(jobId, "FAILED", "Post-run SUT Metrics could not be scheduled.", List.of()); }
                    catch (Exception ignored) { }
                    scheduledCollections.remove(jobId);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException full) {
            scheduledCollections.remove(jobId);
            try { updateState(jobId, "FAILED", "Post-run collection scheduling capacity is full.", List.of()); }
            catch (Exception ignored) { }
        } catch (Exception failure) {
            scheduledCollections.remove(jobId);
            try { updateState(jobId, "FAILED", "Post-run SUT Metrics could not be scheduled.", List.of()); }
            catch (Exception ignored) { }
        }
    }

    void authorizeSnapshot(Map<String, Object> snapshot, String principal, boolean admin) {
        if (admin) return;
        for (Map<String, Object> query : listOfMaps(snapshot.get("queries"))) {
            Map<String, Object> source = source(String.valueOf(query.get("sourceId")));
            if (!sourceAllowed(source, principal)) throw new ServerRuntime.NotFoundException();
        }
    }

    private static List<Map<String, Object>> normalizePhaseWindows(List<Map<String, Object>> phases,
                                                                   Instant executionStart, Instant executionEnd) {
        if (phases == null || phases.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> names = Set.of("RAMP_UP", "WARMUP", "STEADY", "RAMP_DOWN", "DRAIN");
        for (Map<String, Object> raw : phases) {
            if (result.size() >= 8) break;
            String name = nullableText(raw.get("phase"));
            Instant start = parseInstant(nullableText(raw.get("startAt")));
            Instant end = parseInstant(nullableText(raw.get("endAt")));
            if (!names.contains(name) || start == null || end == null || !end.isAfter(start)) continue;
            if (executionStart != null && start.isBefore(executionStart)) start = executionStart;
            if (executionEnd != null && end.isAfter(executionEnd)) end = executionEnd;
            if (!end.isAfter(start)) continue;
            boolean measured = raw.get("measured") instanceof Boolean value ? value
                    : Set.of("STEADY", "RAMP_DOWN").contains(name);
            result.add(fields("phase", name, "startAt", start.toString(), "endAt", end.toString(),
                    "durationMs", Duration.between(start, end).toMillis(), "measured", measured));
        }
        result.sort(Comparator.comparing(value -> String.valueOf(value.get("startAt"))));
        return result;
    }

    private static String observationFreshness(String lastSampleAt, Instant queryEnd, long stepSeconds) {
        if (lastSampleAt == null) return "UNKNOWN";
        try {
            return Instant.parse(lastSampleAt).isBefore(queryEnd.minusSeconds(Math.max(stepSeconds * 2, 30))) ? "STALE" : "FRESH";
        } catch (RuntimeException invalid) { return "UNKNOWN"; }
    }

    private static boolean coversPostRunBuffer(String lastSampleAt, Instant executionFinishedAt,
                                               Instant queryEnd, long stepSeconds) {
        if (lastSampleAt == null) return false;
        try {
            Instant latest = Instant.parse(lastSampleAt);
            return latest.isAfter(executionFinishedAt)
                    && !latest.isBefore(queryEnd.minusSeconds(Math.max(stepSeconds * 2, 30)));
        } catch (RuntimeException invalid) { return false; }
    }

    private void collectPostRun(String jobId, Map<String, Object> snapshot, Instant executionStartedAt,
                                Instant executionFinishedAt, Instant start, Instant end, Instant eligibleAt,
                                List<Map<String, Object>> phaseWindows) {
        List<Map<String, Object>> fileEntries = new ArrayList<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        long totalBytes = 0, totalSamples = 0, queriesSucceeded = 0, queriesFailed = 0, queriesEmpty = 0, queriesPartial = 0;
        long stepSeconds = durationSeconds(String.valueOf(objectOrEmpty(snapshot.get("collection")).get("step")), "step");
        long afterSeconds = durationSeconds(String.valueOf(objectOrEmpty(snapshot.get("collection")).get("after")), "after");
        Map<String, Long> sourceSampleCounts = new HashMap<>();
        Path directory;
        try {
            directory = jobMetricsDirectory(jobId); Files.createDirectories(directory);
            List<Map<String, Object>> queries = listOfMaps(snapshot.get("queries"));
            queryRequestBudgets(snapshot, queries, Duration.between(start, end).getSeconds());
            for (int index = 0; index < queries.size(); index++) {
                Map<String, Object> query = queries.get(index);
                try {
                    Map<String, Object> source = snapshotSource(snapshot, String.valueOf(query.get("sourceId")));
                    Map<String, Object> value = collectQuery(source, query, start, end,
                            stepSeconds);
                    List<Map<String, Object>> series = listOfMaps(value.get("series"));
                    int sampleCount = ((Number) value.get("sampleCount")).intValue();
                    Map<String, Object> coverage = object(value.get("coverage"), "coverage");
                    String lastSampleAt = nullableText(coverage.get("lastSampleAt"));
                    boolean afterObserved = afterSeconds == 0
                            || coversPostRunBuffer(lastSampleAt, executionFinishedAt, end, stepSeconds);
                    boolean queryPartial = sampleCount > 0 && !afterObserved;
                    String sourceId = String.valueOf(query.get("sourceId"));
                    long sourceSamples = sourceSampleCounts.getOrDefault(sourceId, 0L) + sampleCount;
                    long jobSamples = totalSamples + sampleCount;
                    if (sourceSamples > sourceInt(source, "maxSamplesPerJob")) throw new CollectionBudgetExceededException("The Data Source per-Job sample budget was exceeded.");
                    if (jobSamples > MAX_JOB_SAMPLES) throw new CollectionBudgetExceededException("The per-Job sample budget was exceeded.");
                    String queryStatus = series.isEmpty() ? "NO_DATA" : queryPartial ? "PARTIAL" : "READY";
                    List<Map<String, Object>> queryDiagnostics = queryPartial
                            ? List.of(fields("code", "POST_BUFFER_NOT_OBSERVED",
                                    "message", "The latest usable sample does not cover the configured post-run buffer within a two-step or 30-second freshness tolerance."))
                            : List.of();
                    Map<String, Object> observation = fields("schemaVersion", OBSERVATION_SCHEMA,
                            "kind", "sampled-time-series", "effectiveBindingId", query.get("id"),
                            "source", fields("id", query.get("sourceId"), "name", query.get("sourceName"), "revision", query.get("sourceRevision")),
                            "profile", fields("id", query.get("profile"), "path", query.get("profilePath"), "revision", query.get("profileRevision")),
                            "target", fields("id", query.get("targetId"), "type", query.get("targetType"), "revision", query.get("targetRevision")),
                            "metric", fields("id", query.get("metricId"), "name", query.get("title"), "category", query.get("category"),
                                    "unit", query.get("unit"), "kind", query.get("kind")),
                            "window", fields("executionStartedAt", executionStartedAt.toString(), "executionFinishedAt", executionFinishedAt.toString(),
                                    "queryStart", start.toString(), "queryEnd", end.toString(), "step", stepSeconds + "s", "phaseWindows", phaseWindows),
                            "availability", fields("state", sampleCount == 0 ? "NO_DATA" : queryPartial ? "PARTIAL" : "AVAILABLE",
                                    "freshness", observationFreshness(lastSampleAt, end, stepSeconds), "coverage", coverage,
                                    "diagnostics", queryDiagnostics),
                            "provenance", fields("provider", "prometheus", "jobId", jobId,
                                    "snapshotId", snapshot.get("snapshotId"), "queryId", query.get("id")));
                    byte[] bytes = json.writeValueAsBytes(fields("schemaVersion", OBSERVATION_SCHEMA, "queryId", query.get("id"),
                            "profile", query.get("profile"), "profilePath", query.get("profilePath"),
                            "sourceId", query.get("sourceId"), "sourceName", query.get("sourceName"),
                            "metricId", query.get("metricId"), "title", query.get("title"),
                            "category", query.get("category"), "kind", query.get("kind"), "targetId", query.get("targetId"),
                            "targetType", query.get("targetType"), "unit", query.get("unit"), "observation", observation, "series", series));
                    if (bytes.length > MAX_SERIES_FILE_BYTES || totalBytes > MAX_JOB_COLLECTION_BYTES - bytes.length)
                        throw new CollectionBudgetExceededException("The per-Job SUT Metrics artifact budget was exceeded.");
                    String name = String.format(Locale.ROOT, "series-%04d.json", index);
                    Path path = directory.resolve(name); atomicBytes(path, bytes); totalBytes += bytes.length;
                    totalSamples = jobSamples; sourceSampleCounts.put(sourceId, sourceSamples);
                    if (series.isEmpty()) queriesEmpty++;
                    else queriesSucceeded++;
                    if (queryPartial) queriesPartial++;
                    fileEntries.add(fields("queryId", query.get("id"), "metricId", query.get("metricId"), "title", query.get("title"),
                            "profile", query.get("profile"), "profilePath", query.get("profilePath"), "category", query.get("category"),
                            "targetId", query.get("targetId"), "targetType", query.get("targetType"),
                            "sourceId", query.get("sourceId"), "unit", query.get("unit"), "path", name,
                            "lengthBytes", bytes.length, "sha256", sha256(bytes), "sampleCount", sampleCount,
                            "seriesCount", series.size(), "coverage", coverage, "queryStatus", queryStatus));
                    diagnostics.addAll(queryDiagnostics.stream().map(diagnostic -> fields("queryId", query.get("id"),
                            "code", diagnostic.get("code"), "message", diagnostic.get("message"))).toList());
                } catch (CollectionBudgetExceededException budget) {
                    queriesFailed++;
                    diagnostics.add(fields("queryId", query.get("id"), "code", "QUERY_BUDGET_EXCEEDED", "message", safeFailureMessage(budget)));
                    for (int skipped = index + 1; skipped < queries.size(); skipped++) {
                        queriesFailed++;
                        diagnostics.add(fields("queryId", queries.get(skipped).get("id"), "code", "QUERY_NOT_COLLECTED",
                                "message", "Collection stopped after a configured Job budget was reached."));
                    }
                    break;
                } catch (Exception failure) {
                    queriesFailed++;
                    diagnostics.add(fields("queryId", query.get("id"), "code", safeFailureCode(failure),
                            "message", safeFailureMessage(failure)));
                }
            }
            String state = queriesFailed > 0 ? (queriesSucceeded == 0 ? "FAILED" : "PARTIAL")
                    : queriesSucceeded == 0 ? "UNAVAILABLE" : queriesEmpty > 0 || queriesPartial > 0 ? "PARTIAL" : "READY";
            Map<String, Object> manifest = fields("schemaVersion", COLLECTION_SCHEMA, "state", state,
                    "complete", "READY".equals(state), "jobId", jobId, "snapshotId", snapshot.get("snapshotId"),
                    "startedAt", executionStartedAt.toString(), "finishedAt", executionFinishedAt.toString(), "windowStart", start.toString(),
                    "windowEnd", end.toString(), "eligibleAt", eligibleAt.toString(), "step", objectOrEmpty(snapshot.get("collection")).get("step"),
                    "phaseWindows", phaseWindows,
                    "coverage", fields("queryCount", queries.size(), "successfulQueries", queriesSucceeded,
                            "emptyQueries", queriesEmpty, "partialQueries", queriesPartial, "failedQueries", queriesFailed, "sampleCount", totalSamples,
                            "artifactBytes", totalBytes), "queries", queryMetadata(snapshot), "files", fileEntries,
                    "diagnostics", diagnostics);
            writeManifest(directory, manifest);
        } catch (Exception failure) {
            try { updateState(jobId, "FAILED", safeFailureMessage(failure), diagnostics); }
            catch (Exception ignored) { }
        }
    }

    private Map<String, Object> collectPreview(Map<String, Object> snapshot, List<Map<String, Object>> queries,
                                               Instant start, Instant end, long stepSeconds) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>(); int failures = 0, noData = 0; long samples = 0;
        for (int index = 0; index < queries.size(); index++) {
            Map<String, Object> query = queries.get(index);
            try {
                Map<String, Object> source = snapshotSource(snapshot, String.valueOf(query.get("sourceId")));
                Map<String, Object> collected = collectQuery(source, query, start, end, stepSeconds);
                List<Map<String, Object>> series = listOfMaps(collected.get("series"));
                long querySamples = ((Number) collected.get("sampleCount")).longValue();
                if (samples + querySamples > MAX_LIVE_PREVIEW_SAMPLES) throw new CollectionBudgetExceededException("The live preview sample budget was exceeded.");
                samples += querySamples;
                if (querySamples == 0) noData++;
                String queryStatus = querySamples == 0 ? "NO_DATA" : isStale(collected, Instant.now(), stepSeconds) ? "STALE" : "READY";
                items.add(fields("id", query.get("id"), "metricId", query.get("metricId"), "title", query.get("title"),
                        "profile", query.get("profile"), "profilePath", query.get("profilePath"),
                        "category", query.get("category"), "targetId", query.get("targetId"), "targetType", query.get("targetType"),
                        "sourceId", query.get("sourceId"), "sourceName", query.get("sourceName"), "unit", query.get("unit"),
                        "series", series, "coverage", collected.get("coverage"), "queryStatus", queryStatus));
            } catch (CollectionBudgetExceededException budget) {
                failures++;
                items.add(fields("id", query.get("id"), "metricId", query.get("metricId"), "title", query.get("title"),
                        "profile", query.get("profile"), "profilePath", query.get("profilePath"), "targetId", query.get("targetId"),
                        "unit", query.get("unit"), "series", List.of(), "queryStatus", "UNAVAILABLE", "error", safeFailureMessage(budget)));
                for (int skipped = index + 1; skipped < queries.size(); skipped++) {
                    Map<String, Object> remaining = queries.get(skipped); failures++;
                    items.add(fields("id", remaining.get("id"), "metricId", remaining.get("metricId"), "title", remaining.get("title"),
                            "profile", remaining.get("profile"), "profilePath", remaining.get("profilePath"), "targetId", remaining.get("targetId"),
                            "unit", remaining.get("unit"), "series", List.of(), "queryStatus", "UNAVAILABLE",
                            "error", "Live preview stopped after its sample budget was reached."));
                }
                break;
            } catch (CapacityException full) {
                throw full;
            } catch (Exception failure) {
                failures++;
                items.add(fields("id", query.get("id"), "metricId", query.get("metricId"), "title", query.get("title"),
                        "profile", query.get("profile"), "profilePath", query.get("profilePath"), "targetId", query.get("targetId"),
                        "unit", query.get("unit"), "series", List.of(),
                        "queryStatus", "UNAVAILABLE", "error", safeFailureMessage(failure)));
            }
        }
        String state = failures == 0 && samples == 0 ? "NO_DATA"
                : failures == queries.size() ? "UNAVAILABLE"
                : failures > 0 || noData > 0 ? "PARTIAL" : "READY";
        return fields("schemaVersion", "att-sut-metrics-live/v1", "state", state,
                "snapshotId", snapshot.get("snapshotId"), "windowStart", start.toString(), "windowEnd", end.toString(),
                "stepSeconds", stepSeconds, "sampleCount", samples, "sourceQueryTime", Instant.now().toString(),
                "queries", queryMetadata(snapshot), "items", items);
    }

    private static boolean isStale(Map<String, Object> collected, Instant now, long stepSeconds) {
        String latest = nullableText(objectOrEmpty(collected.get("coverage")).get("lastSampleAt"));
        if (latest == null) return false;
        try { return Instant.parse(latest).isBefore(now.minusSeconds(Math.max(90, stepSeconds * 2))); }
        catch (RuntimeException invalid) { return false; }
    }

    private Map<String, Object> collectQuery(Map<String, Object> source, Map<String, Object> query, Instant start, Instant end, long stepSeconds) throws Exception {
        long window = Duration.between(start, end).getSeconds();
        enforceSourceQueryBudget(source, 1, window);
        TreeMap<String, TreeMap<Long, Double>> byLabels = new TreeMap<>();
        long segmentStart = ceilEpochSecond(start);
        int segmentCount = 0;
        while (segmentStart < end.getEpochSecond()) {
            long segmentEnd = Math.min(end.getEpochSecond(), segmentStart + MAX_SEGMENT_SECONDS);
            List<Map<String, Object>> rows = queryRange(source, String.valueOf(query.get("query")), Instant.ofEpochSecond(segmentStart),
                    Instant.ofEpochSecond(segmentEnd), Duration.ofSeconds(stepSeconds), nullableText(query.get("tenant")));
            for (Map<String, Object> row : rows) {
                String key = json.writeValueAsString(row.get("labels"));
                TreeMap<Long, Double> values = byLabels.computeIfAbsent(key, ignored -> new TreeMap<>());
                for (Map<String, Object> point : listOfMaps(row.get("samples"))) values.put(((Number) point.get("timestampEpochMs")).longValue(), ((Number) point.get("value")).doubleValue());
                if (byLabels.size() > sourceInt(source, "maxSeriesPerJob")) throw new IllegalArgumentException("Prometheus returned too many series.");
            }
            segmentStart = segmentEnd; segmentCount++;
            if (segmentCount > 32) throw new IllegalArgumentException("The segmented query budget was exceeded.");
        }
        List<Map<String, Object>> series = new ArrayList<>(); long samples = 0;
        Long firstSampleEpochMs = null, lastSampleEpochMs = null;
        long totalGapCount = 0;
        for (Map.Entry<String, TreeMap<Long, Double>> row : byLabels.entrySet()) {
            List<Map<String, Object>> points = new ArrayList<>();
            long seriesGapCount = 0;
            Long previousEpochMs = null;
            for (Map.Entry<Long, Double> point : row.getValue().entrySet()) {
                if (previousEpochMs != null && point.getKey() - previousEpochMs > stepSeconds * 1500L) seriesGapCount++;
                previousEpochMs = point.getKey();
                points.add(fields("timestamp", Instant.ofEpochMilli(point.getKey()).toString(), "timestampEpochMs", point.getKey(), "value", point.getValue()));
                firstSampleEpochMs = firstSampleEpochMs == null ? point.getKey() : Math.min(firstSampleEpochMs, point.getKey());
                lastSampleEpochMs = lastSampleEpochMs == null ? point.getKey() : Math.max(lastSampleEpochMs, point.getKey());
                samples++;
            }
            Instant first = points.isEmpty() ? null : Instant.parse(String.valueOf(points.get(0).get("timestamp")));
            Instant last = points.isEmpty() ? null : Instant.parse(String.valueOf(points.get(points.size() - 1).get("timestamp")));
            series.add(fields("labels", json.readValue(row.getKey(), Map.class), "samples", points,
                    "coverage", fields("sampleCount", points.size(), "seriesCount", 1, "gapCount", seriesGapCount,
                            "firstSampleAt", first == null ? null : first.toString(),
                            "lastSampleAt", last == null ? null : last.toString(), "windowStart", start.toString(), "windowEnd", end.toString())));
            totalGapCount += seriesGapCount;
        }
        if (samples > sourceInt(source, "maxSamplesPerQuery")) throw new IllegalArgumentException("The Data Source per-query sample budget was exceeded.");
        return fields("series", series, "sampleCount", samples,
                "coverage", fields("sampleCount", samples, "seriesCount", series.size(), "gapCount", totalGapCount,
                        "firstSampleAt", firstSampleEpochMs == null ? null : Instant.ofEpochMilli(firstSampleEpochMs).toString(),
                        "lastSampleAt", lastSampleEpochMs == null ? null : Instant.ofEpochMilli(lastSampleEpochMs).toString(),
                        "windowStart", start.toString(), "windowEnd", end.toString()));
    }

    private Map<String, Integer> queryRequestBudgets(Map<String, Object> snapshot, List<Map<String, Object>> queries, long windowSeconds) {
        Map<String, Integer> requestsBySource = new LinkedHashMap<>();
        int segments = segmentCount(windowSeconds);
        for (Map<String, Object> query : queries) {
            String sourceId = String.valueOf(query.get("sourceId"));
            Map<String, Object> source = snapshotSource(snapshot, sourceId);
            enforceSourceQueryBudget(source, 1, windowSeconds);
            int total = requestsBySource.merge(sourceId, segments, Integer::sum);
            if (total > sourceInt(source, "maxQueriesPerJob"))
                throw new IllegalArgumentException("The per-Job Prometheus query budget was exceeded for Data Source " + sourceId + ".");
        }
        return requestsBySource;
    }

    private static Map<String, Object> snapshotSource(Map<String, Object> snapshot, String sourceId) {
        Object value = objectOrEmpty(snapshot.get("sources")).get(sourceId);
        if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("The immutable SUT Metrics snapshot is missing Data Source " + sourceId + ".");
        return object(value, "snapshot data source");
    }

    private static int segmentCount(long windowSeconds) {
        if (windowSeconds <= 0) throw new IllegalArgumentException("The collection range must be positive.");
        return Math.toIntExact((windowSeconds + MAX_SEGMENT_SECONDS - 1) / MAX_SEGMENT_SECONDS);
    }

    private static long ceilEpochSecond(Instant instant) {
        return instant.getEpochSecond() + (instant.getNano() == 0 ? 0 : 1);
    }

    private static Instant parseInstant(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (RuntimeException invalid) { return null; }
    }

    private List<Map<String, Object>> queryRange(Map<String, Object> source, String query, Instant start, Instant end, Duration step, String tenant) throws Exception {
        if (query == null || query.isBlank() || query.length() > 4096 || query.contains(";")) throw new IllegalArgumentException("PromQL expression is invalid or exceeds its limit.");
        URI endpoint = endpoint(source, query, start, end, step);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(sourceInt(source, "requestTimeoutMs")))
                .header("Accept", "application/json").GET();
        addAuthentication(builder, source);
        if (tenant != null && !tenant.isBlank()) {
            if (!listOfStrings(source.get("allowedTenants")).contains(tenant)) throw new IllegalArgumentException("The requested Prometheus tenant is not allowed.");
            builder.header("X-Scope-OrgID", tenant);
        }
        HttpResponse<InputStream> response = client(source).send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        InputStream responseBody = response.body();
        Future<byte[]> bodyRead;
        try { bodyRead = bodyReaders.submit(() -> readBounded(responseBody, sourceInt(source, "maxResponseBytes"))); }
        catch (java.util.concurrent.RejectedExecutionException full) {
            try { responseBody.close(); } catch (Exception ignored) { }
            throw new CapacityException("Prometheus response-read capacity is full; retry shortly.");
        }
        try { body = bodyRead.get(sourceInt(source, "requestTimeoutMs"), TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) {
            bodyRead.cancel(true);
            try { responseBody.close(); } catch (Exception ignored) { }
            throw new java.net.http.HttpTimeoutException("Prometheus response body timed out.");
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("Prometheus response could not be read.", cause);
        } finally { try { responseBody.close(); } catch (Exception ignored) { } }
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalArgumentException("Prometheus returned HTTP " + response.statusCode() + ".");
        JsonNode root = json.readTree(body);
        if (!"success".equals(root.path("status").asText())) throw new IllegalArgumentException("Prometheus query was not successful.");
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) throw new IllegalArgumentException("Prometheus returned an unsupported query_range result.");
        int maxSeries = sourceInt(source, "maxSeriesPerJob");
        if (result.size() > maxSeries) throw new IllegalArgumentException("Prometheus returned too many series.");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode series : result) {
            JsonNode metric = series.path("metric"), values = series.path("values");
            if (!metric.isObject() || !values.isArray()) continue;
            Map<String, Object> labels = json.convertValue(metric, Map.class);
            List<Map<String, Object>> points = new ArrayList<>();
            for (JsonNode point : values) {
                if (!point.isArray() || point.size() < 2 || !point.get(0).isNumber()) continue;
                double timestamp = point.get(0).asDouble();
                double value;
                try { value = Double.parseDouble(point.get(1).asText()); } catch (NumberFormatException invalid) { continue; }
                if (!Double.isFinite(timestamp) || !Double.isFinite(value)) continue;
                long epochMs = Math.round(timestamp * 1000.0);
                points.add(fields("timestamp", Instant.ofEpochMilli(epochMs).toString(), "timestampEpochMs", epochMs, "value", value));
                if (points.size() > sourceInt(source, "maxSamplesPerQuery")) throw new IllegalArgumentException("Prometheus returned too many samples.");
            }
            rows.add(fields("labels", labels, "samples", points));
        }
        return rows;
    }

    private URI endpoint(Map<String, Object> source, String query, Instant start, Instant end, Duration step) {
        String base = String.valueOf(source.get("url"));
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String path = String.valueOf(source.get("apiPath"));
        String queryString = "query=" + enc(query) + "&start=" + enc(start.toString()) + "&end=" + enc(end.toString()) + "&step=" + enc(step.toSeconds() + "s");
        return URI.create(base + path + "?" + queryString);
    }

    private HttpClient client(Map<String, Object> source) throws Exception {
        enforceAllowedPrometheusUri(URI.create(String.valueOf(source.get("url"))));
        String key = String.valueOf(source.get("id")) + ":" + String.valueOf(source.get("url")) + ":" + String.valueOf(objectOrEmpty(source.get("tls")).get("caSecretRef"));
        HttpClient cached = clients.get(key); if (cached != null) return cached;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(sourceInt(source, "connectTimeoutMs")))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { }
                });
        Map<String, Object> tls = objectOrEmpty(source.get("tls"));
        String caRef = nullableText(tls.get("caSecretRef"));
        if (caRef != null) builder.sslContext(trustContext(caRef));
        HttpClient built = builder.build(); HttpClient prior = clients.putIfAbsent(key, built); return prior == null ? built : prior;
    }

    private SSLContext trustContext(String secretRef) throws Exception {
        String pem = secret(secretRef);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        java.util.Collection<? extends Certificate> additions = factory.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        if (additions.isEmpty()) throw new IllegalArgumentException("TLS CA secret reference did not contain an X.509 certificate.");
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); defaults.init((KeyStore) null);
        int index = 0;
        for (var manager : defaults.getTrustManagers()) if (manager instanceof javax.net.ssl.X509TrustManager x509)
            for (Certificate certificate : x509.getAcceptedIssuers()) trust.setCertificateEntry("system-" + index++, certificate);
        for (Certificate certificate : additions) trust.setCertificateEntry("custom-" + index++, certificate);
        TrustManagerFactory factoryTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factoryTrust.init(trust);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, factoryTrust.getTrustManagers(), new SecureRandom()); return context;
    }

    private void addAuthentication(HttpRequest.Builder builder, Map<String, Object> source) {
        Map<String, Object> auth = objectOrEmpty(source.get("auth")); String mode = String.valueOf(auth.getOrDefault("mode", "NONE"));
        if ("BEARER".equals(mode)) builder.header("Authorization", "Bearer " + secret(String.valueOf(auth.get("secretRef"))));
        else if ("BASIC".equals(mode)) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(secret(String.valueOf(auth.get("secretRef"))).getBytes(StandardCharsets.UTF_8)));
    }

    private void enforceSourceQueryBudget(Map<String, Object> source, int queryCount, long windowSeconds) {
        if (windowSeconds <= 0 || windowSeconds > sourceInt(source, "maxRangeSeconds")) throw new IllegalArgumentException("The collection range exceeds the Data Source limit.");
        if (queryCount > sourceInt(source, "maxQueriesPerJob")) throw new IllegalArgumentException("The per-Job Prometheus query budget was exceeded.");
    }

    private void expand(String profileId, Map<String, Object> binding, String bindingId, String profilePath,
                        List<Map<String, Object>> queries, List<Map<String, Object>> provenance,
                        String principal, boolean admin, Set<String> selectedTargets) {
        Map<String, Object> profile = profiles.get(profileId);
        if (profile == null) throw new IllegalArgumentException("Metrics Profile reference not found: " + profileId + ".");
        Set<String> bindingTargets = stringSet(binding.get("targetIds"), "targetIds");
        Set<String> effectiveTargets = selectedTargets;
        if (!bindingTargets.isEmpty()) {
            if (selectedTargets.isEmpty()) effectiveTargets = bindingTargets;
            else {
                effectiveTargets = new LinkedHashSet<>(selectedTargets);
                effectiveTargets.retainAll(bindingTargets);
            }
        }
        String kind = String.valueOf(profile.get("kind"));
        if ("composite".equals(kind)) {
            if (binding.keySet().stream().anyMatch(key -> !Set.of("id", "profile", "targetIds").contains(key)))
                throw new IllegalArgumentException("Composite profile bindings may select targetIds only; source and targets come from includes.");
            for (Map<String, Object> include : listOfMaps(profile.get("includes"))) {
                String child = String.valueOf(include.get("profile"));
                String alias = String.valueOf(include.getOrDefault("alias", child));
                String childPath = profilePath + "/" + alias;
                Map<String, Object> childBinding = new LinkedHashMap<>(include);
                childBinding.remove("profile"); childBinding.remove("alias");
                Map<String, Object> childProfile = profiles.get(child);
                if (childProfile == null || !"base".equals(childProfile.get("kind")))
                    throw new IllegalArgumentException("Composite profiles may include base profiles only.");
                bindBase(childProfile, childBinding, bindingId, childPath, queries, provenance, principal, admin, effectiveTargets);
            }
        } else {
            bindBase(profile, binding, bindingId, profilePath, queries, provenance, principal, admin, effectiveTargets);
        }
        if (queries.size() > MAX_EXPANDED_QUERIES) throw new IllegalArgumentException("Resolved Metrics Profile exceeds the 256 query budget.");
    }

    private void bindBase(Map<String, Object> profile, Map<String, Object> binding, String bindingId, String profilePath,
                          List<Map<String, Object>> queries, List<Map<String, Object>> provenance,
                          String principal, boolean admin, Set<String> selectedTargets) {
        String profileId = String.valueOf(profile.get("id"));
        String sourceId = nullableText(binding.get("source"));
        if (sourceId == null) throw new IllegalArgumentException("A registered Data Source is required for profile " + profileId + ".");
        Map<String, Object> source = sources.get(sourceId);
        if (source == null) throw new IllegalArgumentException("Registered Data Source not found: " + sourceId + ".");
        if (!sourceAllowed(source, principal, admin)) throw new ServerRuntime.NotFoundException();
        String tenant = nullableText(binding.get("tenant"));
        if (tenant == null) tenant = nullableText(source.get("defaultTenant"));
        if (tenant != null && !listOfStrings(source.get("allowedTenants")).contains(tenant)) throw new IllegalArgumentException("The selected Prometheus tenant is not allowed by the Data Source.");
        List<Map<String, Object>> targetBindings = listOfMaps(binding.get("targets"));
        if (targetBindings.isEmpty()) throw new IllegalArgumentException("Profile " + profileId + " has no target bindings.");
        if (targetBindings.size() > sourceInt(source, "maxTargets")) throw new IllegalArgumentException("Profile exceeds the Data Source target limit.");
        Map<String, Object> definitions = objectOrEmpty(profile.get("params"));
        Map<String, Object> defaults = objectOrEmpty(profile.get("parameters"));
        Map<String, Object> overrides = objectOrEmpty(binding.get("parameters"));
        Set<String> overridable = stringSet(profile.get("overridableParams"), "overridableParams");
        for (String name : overrides.keySet()) if (!overridable.contains(name))
            throw new IllegalArgumentException("Scenario override is not allowed for parameter " + name + " on " + profileId + ".");
        for (Map<String, Object> target : targetBindings) {
            String targetId = targetId(target);
            if (!selectedTargets.isEmpty() && !selectedTargets.contains(targetId) && !selectedTargets.contains(profilePath + "/" + targetId)) continue;
            Map<String, Object> targetParams = targetParameters(target);
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : definitions.entrySet()) {
                Map<String, Object> definition = object(entry.getValue(), "parameter " + entry.getKey());
                Object value = definition.get("default");
                if (defaults.containsKey(entry.getKey())) value = defaults.get(entry.getKey());
                if (targetParams.containsKey(entry.getKey())) value = targetParams.get(entry.getKey());
                if (overrides.containsKey(entry.getKey())) value = overrides.get(entry.getKey());
                if (value == null && bool(definition.get("required"))) throw new IllegalArgumentException("Required profile parameter " + entry.getKey() + " is missing for target " + targetId + ".");
                if (value != null) parameters.put(entry.getKey(), validateParameter(entry.getKey(), definition, value));
            }
            for (String name : targetParams.keySet()) if (!definitions.containsKey(name)) throw new IllegalArgumentException("Unknown profile parameter " + name + " on target " + targetId + ".");
            for (String name : defaults.keySet()) if (!definitions.containsKey(name)) throw new IllegalArgumentException("Unknown default profile parameter " + name + ".");
            for (String name : overrides.keySet()) if (!definitions.containsKey(name)) throw new IllegalArgumentException("Unknown scenario profile parameter " + name + ".");
            if (parameters.size() != definitions.size() && definitions.values().stream().anyMatch(value -> bool(objectOrEmpty(value).get("required")) && !parameters.containsKey(definitionName(definitions, value))))
                throw new IllegalArgumentException("One or more required profile parameters are missing.");
            for (Map<String, Object> metric : listOfMaps(profile.get("metrics"))) {
                String query = interpolate(String.valueOf(metric.get("query")), definitions, parameters);
                String stableId = sourceId + "/" + profilePath + "/" + targetId + "/" + metric.get("id");
                Map<String, Object> row = fields("id", stableId, "bindingId", bindingId, "profile", profileId, "profilePath", profilePath,
                        "sourceId", sourceId, "sourceName", source.get("name"), "tenant", tenant,
                        "targetId", targetId, "targetType", target.getOrDefault("type", profile.getOrDefault("category", "")),
                        "metricId", metric.get("id"), "title", metric.get("title"), "category", metric.getOrDefault("category", profile.getOrDefault("category", "app")),
                        "unit", metric.get("unit"), "kind", metric.get("kind"), "parameters", parameters,
                        "sourceRevision", sha256Json(source), "profileRevision", sha256Json(profile),
                        "targetRevision", sha256Json(target),
                        "query", query);
                queries.add(row);
            }
            provenance.add(fields("profile", profileId, "profilePath", profilePath, "sourceId", sourceId,
                    "sourceName", source.get("name"), "tenant", tenant, "targetId", targetId,
                    "targetType", target.getOrDefault("type", ""), "sourceRevision", sha256Json(source),
                    "profileRevision", sha256Json(profile), "targetRevision", sha256Json(target), "parameters", parameters));
        }
    }

    private Map<String, Object> validateSource(Map<String, Object> input, boolean enforceEgressAllowlist) {
        rejectUnknown(input, Set.of("schemaVersion", "id", "name", "url", "apiPath", "tls", "auth", "readers", "readRoles", "allowedTenants", "defaultTenant", "limits"), "Data Source");
        if (input.containsKey("schemaVersion") && !"att-sut-metrics-source/v1".equals(input.get("schemaVersion")))
            throw new IllegalArgumentException("Data Source schemaVersion must be att-sut-metrics-source/v1.");
        String id = requiredText(input, "id"), name = requiredText(input, "name"), url = requiredText(input, "url");
        if (!ID.matcher(id).matches() || name.length() > 120 || hasControl(name)) throw new IllegalArgumentException("Data Source id or name is invalid.");
        URI uri;
        try { uri = URI.create(url); } catch (RuntimeException malformed) { throw new IllegalArgumentException("Data Source URL is invalid."); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || ("http".equals(scheme) && isLoopbackHost(uri.getHost()))) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() > 65535
                || uri.getPath() != null && List.of(uri.getPath().split("/")).contains(".."))
            throw new IllegalArgumentException("Data Source URL must be HTTPS (or loopback HTTP for local Prometheus) without user info, query, fragment, or parent path.");
        if (enforceEgressAllowlist) enforceAllowedPrometheusUri(uri);
        String apiPath = String.valueOf(input.getOrDefault("apiPath", "/api/v1/query_range"));
        if (!apiPath.startsWith("/") || apiPath.contains("..") || apiPath.contains("?") || apiPath.contains("#") || apiPath.length() > 256)
            throw new IllegalArgumentException("Data Source apiPath must be a fixed safe absolute path.");
        Map<String, Object> tls = objectOrEmpty(input.get("tls")); rejectUnknown(tls, Set.of("verify", "caSecretRef"), "tls");
        boolean verifyTls = !tls.containsKey("verify") || bool(tls.get("verify"));
        String caRef = nullableText(tls.get("caSecretRef")); if (caRef != null) validateSecretRef(caRef);
        if ("https".equals(scheme) && !verifyTls) throw new IllegalArgumentException("Prometheus TLS certificate verification must remain enabled.");
        Map<String, Object> auth = objectOrEmpty(input.get("auth")); rejectUnknown(auth, Set.of("mode", "secretRef"), "auth");
        String authMode = String.valueOf(auth.getOrDefault("mode", "NONE")).toUpperCase(Locale.ROOT);
        if (!Set.of("NONE", "BEARER", "BASIC").contains(authMode)) throw new IllegalArgumentException("auth.mode must be NONE, BEARER, or BASIC.");
        String secretRef = nullableText(auth.get("secretRef"));
        if (!"NONE".equals(authMode)) { if (secretRef == null) throw new IllegalArgumentException("A server secret reference is required for authenticated sources."); validateSecretRef(secretRef); }
        else if (secretRef != null) throw new IllegalArgumentException("secretRef is not accepted when auth.mode is NONE.");
        List<String> allowedTenants = checkedStrings(input.get("allowedTenants"), 32, 128, "allowedTenants");
        String defaultTenant = nullableText(input.get("defaultTenant"));
        if (defaultTenant != null && !allowedTenants.contains(defaultTenant)) throw new IllegalArgumentException("defaultTenant must be listed in allowedTenants.");
        List<String> readers = checkedStrings(input.get("readers"), 256, 120, "readers");
        List<String> readRoles = checkedRoles(input.get("readRoles"));
        Map<String, Object> limits = normalizeLimits(objectOrEmpty(input.get("limits")));
        return fields("schemaVersion", "att-sut-metrics-source/v1", "id", id, "name", name, "url", uri.toString(),
                "apiPath", apiPath, "tls", fields("verify", verifyTls, "caSecretRef", caRef),
                "auth", fields("mode", authMode, "secretRef", secretRef), "readers", readers, "readRoles", readRoles,
                "allowedTenants", allowedTenants, "defaultTenant", defaultTenant, "limits", limits,
                "createdAt", Instant.now().toString());
    }

    private Map<String, Object> validateProfile(Map<String, Object> input) {
        rejectUnknown(input, Set.of("schemaVersion", "id", "name", "description", "kind", "category", "params", "parameters",
                "overridableParams", "metrics", "includes"), "Metrics Profile");
        if (input.containsKey("schemaVersion") && !"att-sut-metrics-profile/v1".equals(input.get("schemaVersion")))
            throw new IllegalArgumentException("Metrics Profile schemaVersion must be att-sut-metrics-profile/v1.");
        String id = requiredText(input, "id"), name = String.valueOf(input.getOrDefault("name", id));
        if (!ID.matcher(id).matches() || name.length() > 120 || hasControl(name)) throw new IllegalArgumentException("Metrics Profile id or name is invalid.");
        String kind = String.valueOf(input.getOrDefault("kind", "base")).toLowerCase(Locale.ROOT);
        if (!Set.of("base", "composite").contains(kind)) throw new IllegalArgumentException("Metrics Profile kind must be base or composite.");
        String category = String.valueOf(input.getOrDefault("category", "app")).toLowerCase(Locale.ROOT);
        if (!Set.of("os", "jvm", "app", "db", "mq", "custom").contains(category)) throw new IllegalArgumentException("Metrics Profile category is invalid.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "att-sut-metrics-profile/v1"); result.put("id", id); result.put("name", name);
        result.put("description", boundedText(input.get("description"), 512)); result.put("kind", kind); result.put("category", category);
        List<String> overridable = checkedParamNames(input.get("overridableParams"), "overridableParams"); result.put("overridableParams", overridable);
        if ("base".equals(kind)) {
            if (input.containsKey("includes")) throw new IllegalArgumentException("Base profiles cannot include other profiles.");
            Map<String, Object> definitions = validateParamDefinitions(objectOrEmpty(input.get("params")));
            Map<String, Object> parameterDefaults = validateParameterDefaults(objectOrEmpty(input.get("parameters")), definitions);
            List<Map<String, Object>> metrics = validateMetrics(input.get("metrics"), category);
            if (metrics.isEmpty()) throw new IllegalArgumentException("Base profile requires at least one metric definition.");
            for (String param : overridable) if (!definitions.containsKey(param)) throw new IllegalArgumentException("overridableParams contains an undefined parameter: " + param + ".");
            result.put("params", definitions); result.put("parameters", parameterDefaults); result.put("metrics", metrics);
        } else {
            if (input.containsKey("metrics") || input.containsKey("params") || input.containsKey("parameters"))
                throw new IllegalArgumentException("Composite profiles contain base-profile bindings only; source and targets belong on each include.");
            List<Map<String, Object>> includes = validateIncludes(input.get("includes"));
            if (includes.isEmpty()) throw new IllegalArgumentException("Composite profiles require at least one include binding.");
            result.put("includes", includes);
        }
        return result;
    }

    private Map<String, Object> validateParamDefinitions(Map<String, Object> raw) {
        if (raw.size() > 32) throw new IllegalArgumentException("A base profile may define at most 32 parameters.");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!PARAM.matcher(entry.getKey()).matches()) throw new IllegalArgumentException("Profile parameter name is invalid: " + entry.getKey() + ".");
            Map<String, Object> definition = object(entry.getValue(), "parameter " + entry.getKey());
            rejectUnknown(definition, Set.of("type", "required", "default", "allowedValues"), "parameter definition");
            String type = String.valueOf(definition.getOrDefault("type", "promLabelValue"));
            if (!Set.of("promLabelValue", "promLabelRegex", "text", "enum", "number", "duration").contains(type)) throw new IllegalArgumentException("Unsupported profile parameter type " + type + ".");
            List<Object> allowed = new ArrayList<>(); Object rawAllowed = definition.get("allowedValues");
            if (rawAllowed != null) {
                if (!(rawAllowed instanceof List<?> values) || values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("allowedValues must contain 1 to 64 values.");
                for (Object value : values) allowed.add(validateTyped(type, value));
            }
            if ("enum".equals(type) && allowed.isEmpty()) throw new IllegalArgumentException("enum profile parameters require allowedValues.");
            Object defaultValue = definition.get("default"); if (defaultValue != null) defaultValue = validateTyped(type, defaultValue);
            if (defaultValue != null && !allowed.isEmpty() && !allowed.contains(defaultValue)) throw new IllegalArgumentException("Parameter default is outside allowedValues.");
            Map<String, Object> normalized = fields("type", type, "required", bool(definition.get("required")), "default", defaultValue);
            if (!allowed.isEmpty()) normalized.put("allowedValues", allowed);
            out.put(entry.getKey(), normalized);
        }
        return out;
    }

    private Map<String, Object> validateParameterDefaults(Map<String, Object> submitted, Map<String, Object> definitions) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : submitted.entrySet()) {
            Map<String, Object> definition = objectOrEmpty(definitions.get(entry.getKey()));
            if (definition.isEmpty()) throw new IllegalArgumentException("Profile default references an undefined parameter: " + entry.getKey() + ".");
            out.put(entry.getKey(), validateParameter(entry.getKey(), definition, entry.getValue()));
        }
        return out;
    }

    private List<Map<String, Object>> validateMetrics(Object raw, String category) {
        if (!(raw instanceof List<?> list) || list.size() > 64) throw new IllegalArgumentException("Base profile metrics must be an array of at most 64 definitions.");
        Set<String> ids = new HashSet<>(); List<Map<String, Object>> out = new ArrayList<>();
        for (Object value : list) {
            Map<String, Object> metric = object(value, "metric definition");
            rejectUnknown(metric, Set.of("id", "title", "unit", "kind", "category", "query"), "metric definition");
            String id = requiredText(metric, "id"), title = requiredText(metric, "title"), unit = requiredText(metric, "unit"), kind = String.valueOf(metric.getOrDefault("kind", "gauge"));
            String query = requiredText(metric, "query").trim();
            if (!ID.matcher(id).matches() || !ids.add(id) || title.length() > 120 || query.length() > 4096 || query.contains(";")
                    || query.contains("http://") || query.contains("https://") || query.isBlank()) throw new IllegalArgumentException("Metric id or PromQL expression is invalid.");
            if (!Set.of("percent", "bytes", "bytes_per_second", "seconds", "milliseconds", "count", "ratio", "cores", "custom").contains(unit)) throw new IllegalArgumentException("Unsupported metric unit " + unit + ".");
            if (!Set.of("gauge", "rate", "counter", "histogram").contains(kind)) throw new IllegalArgumentException("Unsupported metric kind " + kind + ".");
            String metricCategory = String.valueOf(metric.getOrDefault("category", category)).toLowerCase(Locale.ROOT);
            if (!Set.of("os", "jvm", "app", "db", "mq", "custom").contains(metricCategory)) throw new IllegalArgumentException("Metric category is invalid.");
            out.add(fields("id", id, "title", title, "unit", unit, "kind", kind, "category", metricCategory, "query", query));
        }
        return out;
    }

    private List<Map<String, Object>> validateTargets(Object raw, String field) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_EXPANDED_TARGETS) throw new IllegalArgumentException(field + " must contain 1 to 64 targets.");
        List<Map<String, Object>> targets = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (Object value : list) {
            Map<String, Object> target = object(value, field + " target");
            if (target.size() > 40) throw new IllegalArgumentException("Target has too many parameters.");
            String id = nullableText(target.get("id")); if (id == null) id = nullableText(target.get("alias"));
            if (id != null && (!ID.matcher(id).matches() || !ids.add(id))) throw new IllegalArgumentException("Target id or alias is invalid or duplicated.");
            String type = nullableText(target.get("type")); if (type != null && (type.length() > 64 || hasControl(type))) throw new IllegalArgumentException("Target type is invalid.");
            Object params = target.get("parameters"); if (params != null) object(params, "target parameters");
            for (String key : target.keySet()) if (!Set.of("id", "alias", "type", "tenant", "parameters").contains(key) && !PARAM.matcher(key).matches())
                throw new IllegalArgumentException("Target parameter name is invalid: " + key + ".");
            Map<String, Object> copied = new LinkedHashMap<>(target);
            targets.add(copied);
        }
        return targets;
    }

    private List<Map<String, Object>> validateIncludes(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 16) throw new IllegalArgumentException("Composite includes must contain at most 16 bindings.");
        List<Map<String, Object>> out = new ArrayList<>(); Set<String> aliases = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> include = object(list.get(i), "includes[" + i + "]");
            rejectUnknown(include, Set.of("profile", "alias", "source", "tenant", "targets", "parameters"), "include binding");
            String child = requiredText(include, "profile");
            String alias = nullableText(include.get("alias"));
            if (alias == null) alias = child;
            Map<String, Object> childProfile = profiles.get(child);
            if (childProfile == null || !"base".equals(childProfile.get("kind")))
                throw new IllegalArgumentException("Composite profiles may include existing base profiles only.");
            if (!ID.matcher(alias).matches() || !aliases.add(alias)) throw new IllegalArgumentException("Composite profile include has a duplicate or invalid alias; repeated base profiles require distinct explicit aliases.");
            String source = requiredText(include, "source"); if (!sources.containsKey(source)) throw new IllegalArgumentException("Composite profile source does not exist: " + source + ".");
            Map<String, Object> binding = new LinkedHashMap<>(include); binding.put("profile", child); binding.put("alias", alias);
            binding.put("targets", validateTargets(include.get("targets"), "includes.targets"));
            if (include.containsKey("parameters")) object(include.get("parameters"), "includes.parameters");
            out.add(binding);
        }
        return out;
    }

    private Map<String, Object> normalizeLimits(Map<String, Object> input) {
        rejectUnknown(input, Set.of("connectTimeoutMs", "requestTimeoutMs", "maxRangeSeconds", "maxResponseBytes", "maxQueriesPerJob", "maxSamplesPerQuery", "maxSamplesPerJob", "maxSeriesPerJob", "maxTargets", "maxMetrics"), "Data Source limits");
        return fields("connectTimeoutMs", boundedInt(input.get("connectTimeoutMs"), 5000, 250, 30000),
                "requestTimeoutMs", boundedInt(input.get("requestTimeoutMs"), 10000, 250, 60000),
                "maxRangeSeconds", boundedInt(input.get("maxRangeSeconds"), (int) MAX_COLLECTION_WINDOW_SECONDS, 60, (int) MAX_COLLECTION_WINDOW_SECONDS),
                "maxResponseBytes", boundedInt(input.get("maxResponseBytes"), 4 * 1024 * 1024, 1024, 8 * 1024 * 1024),
                "maxQueriesPerJob", boundedInt(input.get("maxQueriesPerJob"), 256, 1, 2048),
                "maxSamplesPerQuery", boundedInt(input.get("maxSamplesPerQuery"), 100000, 1, 250000),
                "maxSamplesPerJob", boundedInt(input.get("maxSamplesPerJob"), 1500000, 1, 1500000),
                "maxSeriesPerJob", boundedInt(input.get("maxSeriesPerJob"), 64, 1, 512),
                "maxTargets", boundedInt(input.get("maxTargets"), 64, 1, 64),
                "maxMetrics", boundedInt(input.get("maxMetrics"), 64, 1, 64));
    }

    private void validateProfileGraph() {
        if (profiles.size() > MAX_PROFILES) throw new IllegalArgumentException("Metrics Profile catalog exceeds 256 profiles.");
        for (Map<String, Object> profile : profiles.values()) {
            if (!Set.of("base", "composite").contains(profile.get("kind")))
                throw new IllegalArgumentException("Metrics Profile kind must be base or composite.");
            if ("composite".equals(profile.get("kind"))) for (Map<String, Object> include : listOfMaps(profile.get("includes"))) {
                Map<String, Object> child = profiles.get(String.valueOf(include.get("profile")));
                if (child == null || !"base".equals(child.get("kind")))
                    throw new IllegalArgumentException("Composite profiles may include existing base profiles only.");
            }
        }
    }

    private boolean profileReadable(Map<String, Object> profile, String principal) {
        for (Map<String, Object> include : listOfMaps(profile.get("includes"))) {
            String source = nullableText(include.get("source")); if (source != null && !sourceAllowed(sources.get(source), principal)) return false;
        }
        return true;
    }

    private Map<String, Object> source(String id) {
        synchronized (catalogLock) { Map<String, Object> value = sources.get(id); if (value == null) throw new IllegalArgumentException("Data Source not found: " + id + "."); return value; }
    }

    private boolean sourceAllowed(Map<String, Object> source, String principal) { return sourceAllowed(source, principal, false); }

    private boolean sourceAllowed(Map<String, Object> source, String principal, boolean admin) {
        if (admin) return source != null;
        if (source == null || principal == null || principal.isBlank()) return false;
        List<String> readers = listOfStrings(source.get("readers"));
        List<String> roles = listOfStrings(source.get("readRoles"));
        return readers.contains("*") || readers.contains(principal) || roles.contains("USER");
    }

    private Map<String, Object> sourceView(Map<String, Object> source, boolean admin) {
        Map<String, Object> result = fields("id", source.get("id"), "name", source.get("name"), "authConfigured",
                !"NONE".equals(objectOrEmpty(source.get("auth")).get("mode")), "tlsVerify", objectOrEmpty(source.get("tls")).get("verify"),
                "allowedTenants", source.get("allowedTenants"), "defaultTenant", source.get("defaultTenant"), "limits", source.get("limits"));
        if (admin) { result.put("url", source.get("url")); result.put("apiPath", source.get("apiPath")); result.put("readers", source.get("readers"));
            result.put("readRoles", source.get("readRoles")); result.put("tls", fields("verify", objectOrEmpty(source.get("tls")).get("verify"),
                    "caSecretConfigured", objectOrEmpty(source.get("tls")).get("caSecretRef") != null,
                    "caSecretRef", objectOrEmpty(source.get("tls")).get("caSecretRef")));
            result.put("auth", fields("mode", objectOrEmpty(source.get("auth")).get("mode"),
                    "secretConfigured", objectOrEmpty(source.get("auth")).get("secretRef") != null,
                    "secretRef", objectOrEmpty(source.get("auth")).get("secretRef"))); }
        return result;
    }

    private Map<String, Object> profileView(Map<String, Object> profile, boolean admin) {
        Map<String, Object> result = new LinkedHashMap<>(profile);
        if (!admin) {
            List<Map<String, Object>> metricSummary = new ArrayList<>();
            for (Map<String, Object> metric : listOfMaps(profile.get("metrics")))
                metricSummary.add(fields("id", metric.get("id"), "title", metric.get("title"), "unit", metric.get("unit"),
                        "kind", metric.get("kind"), "category", metric.get("category")));
            result.remove("metrics"); result.put("metricCount", metricSummary.size()); result.put("metricCatalog", metricSummary);
            Map<String, Object> parameterSchema = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : objectOrEmpty(profile.get("params")).entrySet()) {
                Map<String, Object> definition = objectOrEmpty(entry.getValue());
                parameterSchema.put(entry.getKey(), fields("type", definition.get("type"), "required", definition.get("required"),
                        "allowedValues", definition.get("allowedValues")));
            }
            result.remove("params"); result.put("parameterSchema", parameterSchema);
            result.remove("parameters"); result.remove("targets"); result.put("overridableParams", profile.get("overridableParams"));
            if (profile.get("includes") instanceof List<?>) {
                List<Map<String, Object>> includes = new ArrayList<>();
                for (Map<String, Object> include : listOfMaps(profile.get("includes")))
                    includes.add(fields("profile", include.get("profile"), "alias", include.get("alias")));
                result.put("includes", includes);
            }
        }
        return result;
    }

    private boolean profileReferencesSource(Map<String, Object> profile, String id) {
        for (Map<String, Object> include : listOfMaps(profile.get("includes"))) if (id.equals(include.get("source"))) return true;
        return false;
    }

    private boolean profileReferencesProfile(Map<String, Object> profile, String id) {
        for (Map<String, Object> include : listOfMaps(profile.get("includes"))) if (id.equals(include.get("profile"))) return true;
        return false;
    }

    private static void normalizeStoredEmptyAllowedValues(Map<String, Object> profile) {
        Map<String, Object> definitions = objectOrEmpty(profile.get("params"));
        for (Map.Entry<String, Object> entry : definitions.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawDefinition)) continue;
            Map<String, Object> definition = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : rawDefinition.entrySet())
                if (field.getKey() instanceof String name) definition.put(name, field.getValue());
            if (definition.get("allowedValues") instanceof List<?> values && values.isEmpty())
                definition.remove("allowedValues");
            definitions.put(entry.getKey(), definition);
        }
        profile.put("params", definitions);
    }

    private void loadCatalog() throws Exception {
        synchronized (catalogLock) {
            if (!Files.exists(catalogPath, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(catalogPath) || !Files.isRegularFile(catalogPath, LinkOption.NOFOLLOW_LINKS) || Files.size(catalogPath) > 8 * 1024 * 1024)
                throw new IllegalArgumentException("SUT Metrics catalog is not a bounded regular file.");
            JsonNode root = json.readTree(catalogPath.toFile());
            if (!"att-sut-metrics-catalog/v1".equals(root.path("schemaVersion").asText())) throw new IllegalArgumentException("Unsupported SUT Metrics catalog schema.");
            Map<String, Map<String, Object>> loadedSources = new LinkedHashMap<>(), loadedProfiles = new LinkedHashMap<>();
            if (!root.path("sources").isArray() || root.path("sources").size() > MAX_SOURCES
                    || !root.path("profiles").isArray() || root.path("profiles").size() > MAX_PROFILES)
                throw new IllegalArgumentException("SUT Metrics catalog exceeds its source or profile limits.");
            for (JsonNode item : root.path("sources")) {
                Map<String, Object> raw = json.convertValue(item, Map.class);
                Object createdAt = raw.remove("createdAt");
                Map<String, Object> value = validateSource(raw, false);
                if (loadedSources.putIfAbsent(String.valueOf(value.get("id")), value) != null)
                    throw new IllegalArgumentException("SUT Metrics catalog contains a duplicate Data Source id.");
                if (createdAt instanceof String) value.put("createdAt", createdAt);
            }
            sources = loadedSources;
            for (JsonNode item : root.path("profiles")) {
                Map<String, Object> value = json.convertValue(item, Map.class);
                normalizeStoredEmptyAllowedValues(value);
                if (loadedProfiles.putIfAbsent(String.valueOf(value.get("id")), value) != null)
                    throw new IllegalArgumentException("SUT Metrics catalog contains a duplicate Metrics Profile id.");
            }
            profiles = loadedProfiles;
            Map<String, Map<String, Object>> validatedProfiles = new LinkedHashMap<>();
            for (Map<String, Object> value : profiles.values()) {
                Map<String, Object> validated = validateProfile(value);
                validatedProfiles.put(String.valueOf(validated.get("id")), validated);
            }
            profiles = validatedProfiles; validateProfileGraph();
        }
    }

    private void saveCatalog() throws Exception {
        List<Map<String, Object>> sourceRows = new ArrayList<>(sources.values()), profileRows = new ArrayList<>(profiles.values());
        Map<String, Object> catalog = fields("schemaVersion", "att-sut-metrics-catalog/v1", "updatedAt", Instant.now().toString(),
                "sources", sourceRows, "profiles", profileRows);
        atomicJson(catalogPath, catalog);
    }

    void writeManifest(Path directory, Map<String, Object> manifest) throws Exception {
        String jobId = directory.getParent().getFileName().toString();
        ManifestWriteLock lock = manifestWriteLocks.compute(jobId, (key, existing) -> {
            ManifestWriteLock selected = existing == null ? new ManifestWriteLock() : existing;
            selected.users++;
            return selected;
        });
        lock.lock.lock();
        boolean published = false;
        try {
            writeManifestLocked(directory, manifest);
            published = true;
        } finally {
            lock.lock.unlock();
            manifestWriteLocks.compute(jobId, (key, current) -> {
                if (current != lock) return current;
                return --lock.users == 0 ? null : lock;
            });
        }
        if (published && Set.of("READY", "PARTIAL", "UNAVAILABLE", "FAILED").contains(manifest.get("state")))
            scheduleThresholdEvaluation(jobId);
    }

    private void writeManifestLocked(Path directory, Map<String, Object> manifest) throws Exception {
        String jobId = directory.getParent().getFileName().toString();
        SnapshotEnvelope envelope = snapshotEnvelope(jobId);
        if (envelope == null) throw new IllegalStateException("The SUT Metrics Job snapshot is missing for manifest publication.");
        Map<String, Object> next = new LinkedHashMap<>(manifest);
        next.put("schemaVersion", COLLECTION_SCHEMA);
        next.put("jobId", jobId);
        next.put("snapshotId", envelope.snapshot().get("snapshotId"));
        next.put("updatedAt", next.getOrDefault("updatedAt", Instant.now().toString()));
        validateManifest(next, jobId, envelope.snapshot());
        next.remove(MANIFEST_INTEGRITY_FIELD);
        byte[] nextBytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(next);
        Path manifestPath = directory.resolve("manifest.json"), integrityPath = directory.resolve("manifest-integrity.json");
        List<Map<String, Object>> entries = new ArrayList<>();
        if (Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(manifestPath) || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(manifestPath) > MAX_MANIFEST_BYTES)
                throw new IllegalStateException("The current SUT Metrics manifest is not a bounded regular file.");
            byte[] currentBytes = Files.readAllBytes(manifestPath);
            Map<String, Object> current = json.readValue(currentBytes, Map.class);
            validateManifest(current, jobId, envelope.snapshot());
            entries = verifyManifestIntegrity(directory, currentBytes, current, envelope, true);
        } else if (Files.exists(integrityPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The SUT Metrics manifest is missing from its integrity record.");
        }
        if (entries.isEmpty()) {
            String initialDigest = envelope.initialManifestSha256();
            if (initialDigest != null && !initialDigest.equals(sha256(nextBytes)))
                throw new IllegalStateException("The initial SUT Metrics manifest does not match its immutable snapshot anchor.");
        }
        if (entries.size() >= MAX_MANIFEST_TRANSITIONS)
            throw new IllegalStateException("The SUT Metrics manifest transition limit was exceeded.");
        Map<String, Object> entry = manifestIntegrityEntry(entries.size() + 1, entries.isEmpty()
                ? manifestIntegrityRoot(jobId, envelope.snapshotSha256())
                : String.valueOf(entries.get(entries.size() - 1).get("entrySha256")),
                sha256(nextBytes), next, jobId, String.valueOf(envelope.snapshot().get("snapshotId")));
        entries.add(entry);
        Map<String, Object> integrity = fields("schemaVersion", MANIFEST_INTEGRITY_SCHEMA, "jobId", jobId,
                "snapshotId", envelope.snapshot().get("snapshotId"), "snapshotSha256", envelope.snapshotSha256(), "entries", entries);
        Map<String, Object> publication = new LinkedHashMap<>(next);
        publication.put(MANIFEST_INTEGRITY_FIELD, integrity);
        byte[] publicationBytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(publication);
        if (publicationBytes.length > MAX_MANIFEST_BYTES)
            throw new IllegalStateException("The SUT Metrics companion manifest exceeds its publication limit.");
        atomicBytes(manifestPath, publicationBytes);
        afterManifestPublication.accept(manifestPath);
        Files.deleteIfExists(integrityPath);
    }

    private void validateManifest(Map<String, Object> manifest, String jobId, Map<String, Object> snapshot) {
        if (!COLLECTION_SCHEMA.equals(manifest.get("schemaVersion")) || !jobId.equals(manifest.get("jobId"))
                || !String.valueOf(snapshot.get("snapshotId")).equals(manifest.get("snapshotId")))
            throw new IllegalStateException("The SUT Metrics manifest identity does not match its Job snapshot.");
        Set<String> states = Set.of("PENDING", "COLLECTING", "READY", "PARTIAL", "UNAVAILABLE", "FAILED");
        String state = String.valueOf(manifest.get("state"));
        if (!states.contains(state) || !(manifest.get("complete") instanceof Boolean complete)
                || complete != "READY".equals(state))
            throw new IllegalStateException("The SUT Metrics manifest state is invalid.");
        Object queryValue = manifest.get("queries"), fileValue = manifest.get("files"), diagnosticValue = manifest.get("diagnostics");
        if (!(queryValue instanceof List<?> queries) || queries.size() > MAX_EXPANDED_QUERIES
                || !(fileValue instanceof List<?> files) || files.size() > MAX_EXPANDED_QUERIES
                || !(diagnosticValue instanceof List<?> diagnostics) || diagnostics.size() > MAX_EXPANDED_QUERIES * 2)
            throw new IllegalStateException("The SUT Metrics manifest exceeds its structure limits.");
        List<Map<String, Object>> expectedQueries = queryMetadata(snapshot);
        List<Map<String, Object>> actualQueries = new ArrayList<>();
        Set<String> queryIds = new HashSet<>();
        for (Object value : queries) {
            Map<String, Object> query = object(value, "manifest query");
            String id = nullableText(query.get("id"));
            if (id == null || !queryIds.add(id)) throw new IllegalStateException("The SUT Metrics manifest has duplicate query identities.");
            actualQueries.add(query);
        }
        if (!expectedQueries.equals(actualQueries)) throw new IllegalStateException("The SUT Metrics manifest query list does not match its immutable snapshot.");
        Set<String> filePaths = new HashSet<>(), fileQueryIds = new HashSet<>();
        Set<String> expectedQueryIds = new HashSet<>();
        for (Map<String, Object> query : expectedQueries) expectedQueryIds.add(String.valueOf(query.get("id")));
        for (Object value : files) {
            Map<String, Object> file = object(value, "manifest file");
            String path = nullableText(file.get("path")), queryId = nullableText(file.get("queryId"));
            Object length = file.get("lengthBytes");
            if (path == null || !path.matches("series-[0-9]{4}\\.json") || !filePaths.add(path)
                    || queryId == null || !expectedQueryIds.contains(queryId) || !fileQueryIds.add(queryId)
                    || !(length instanceof Number number) || number.longValue() < 1 || number.longValue() > MAX_SERIES_FILE_BYTES
                    || !(file.get("sha256") instanceof String digest) || !digest.matches("[a-f0-9]{64}")
                    || !(file.get("sampleCount") instanceof Number sampleCount) || sampleCount.longValue() < 0
                    || !(file.get("seriesCount") instanceof Number seriesCount) || seriesCount.longValue() < 0
                    || !(file.get("queryStatus") instanceof String queryStatus)
                    || !Set.of("READY", "PARTIAL", "NO_DATA").contains(queryStatus))
                throw new IllegalStateException("The SUT Metrics manifest contains an invalid sidecar entry.");
        }
        Object phases = manifest.get("phaseWindows");
        if (phases != null && (!(phases instanceof List<?> phaseList) || phaseList.size() > 8))
            throw new IllegalStateException("The SUT Metrics manifest phase windows exceed their limit.");
    }

    private List<Map<String, Object>> verifyManifestIntegrity(Path directory, byte[] manifestBytes,
                                                               Map<String, Object> manifest, SnapshotEnvelope envelope,
                                                               boolean allowBootstrap) throws Exception {
        String jobId = String.valueOf(manifest.get("jobId"));
        String snapshotId = String.valueOf(envelope.snapshot().get("snapshotId"));
        if (manifest.containsKey(MANIFEST_INTEGRITY_FIELD)) {
            Object rawIntegrity = manifest.remove(MANIFEST_INTEGRITY_FIELD);
            Map<String, Object> integrity = object(rawIntegrity, "manifest integrity record");
            if (!MANIFEST_INTEGRITY_SCHEMA.equals(integrity.get("schemaVersion"))
                    || !jobId.equals(integrity.get("jobId")) || !snapshotId.equals(integrity.get("snapshotId"))
                    || !envelope.snapshotSha256().equals(integrity.get("snapshotSha256")))
                throw new IllegalStateException("The SUT Metrics manifest integrity record identity is invalid.");
            Object rawEntries = integrity.get("entries");
            if (!(rawEntries instanceof List<?> list))
                throw new IllegalStateException("The SUT Metrics manifest integrity history is missing.");
            byte[] manifestPayload = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
            return verifyManifestIntegrityEntries(list, sha256(manifestPayload), manifest, jobId, snapshotId, envelope.snapshotSha256());
        }

        Path historyPath = directory.resolve("manifest-integrity.json");
        if (Files.exists(historyPath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(historyPath) || !Files.isRegularFile(historyPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(historyPath) > 128 * 1024)
                throw new IllegalStateException("The legacy SUT Metrics manifest integrity record is not a bounded regular file.");
            Map<String, Object> history = json.readValue(historyPath.toFile(), Map.class);
            if (!MANIFEST_INTEGRITY_SCHEMA.equals(history.get("schemaVersion"))
                    || !jobId.equals(history.get("jobId")) || !snapshotId.equals(history.get("snapshotId"))
                    || !envelope.snapshotSha256().equals(history.get("snapshotSha256"))
                    || !(history.get("entries") instanceof List<?> entries))
                throw new IllegalStateException("The legacy SUT Metrics manifest integrity record identity or size is invalid.");
            return verifyManifestIntegrityEntries(entries, sha256(manifestBytes), manifest, jobId, snapshotId, envelope.snapshotSha256());
        }

        String manifestDigest = sha256(manifestBytes);
        if (envelope.initialManifestSha256() != null && !envelope.initialManifestSha256().equals(manifestDigest))
            throw new IllegalStateException("The SUT Metrics manifest does not match its immutable integrity anchor.");
        if (!allowBootstrap) throw new IllegalStateException("The SUT Metrics manifest integrity record is missing.");
        return List.of(manifestIntegrityEntry(1, manifestIntegrityRoot(jobId, envelope.snapshotSha256()),
                manifestDigest, manifest, jobId, snapshotId));
    }

    private List<Map<String, Object>> verifyManifestIntegrityEntries(List<?> rawEntries, String manifestDigest,
                                                                      Map<String, Object> manifest, String jobId,
                                                                      String snapshotId, String snapshotSha256) throws Exception {
        if (rawEntries.isEmpty() || rawEntries.size() > MAX_MANIFEST_TRANSITIONS)
            throw new IllegalStateException("The SUT Metrics manifest integrity history exceeds its size limit.");
        List<Map<String, Object>> entries = new ArrayList<>();
        String previous = manifestIntegrityRoot(jobId, snapshotSha256);
        for (int index = 0; index < rawEntries.size(); index++) {
            Map<String, Object> entry = object(rawEntries.get(index), "manifest integrity entry");
            Object sequence = entry.get("sequence");
            if (!(sequence instanceof Number number) || number.intValue() != index + 1
                    || !previous.equals(entry.get("previousSha256")) || !jobId.equals(entry.get("jobId"))
                    || !snapshotId.equals(entry.get("snapshotId")) || !Set.of("PENDING", "COLLECTING", "READY", "PARTIAL", "UNAVAILABLE", "FAILED").contains(entry.get("state"))
                    || !(entry.get("manifestSha256") instanceof String digest) || !digest.matches("[a-f0-9]{64}"))
                throw new IllegalStateException("The SUT Metrics manifest integrity chain is invalid.");
            Map<String, Object> core = fields("sequence", index + 1, "previousSha256", previous,
                    "manifestSha256", digest, "state", entry.get("state"), "jobId", jobId, "snapshotId", snapshotId);
            String entrySha = sha256(json.writeValueAsBytes(core));
            if (!entrySha.equals(entry.get("entrySha256"))) throw new IllegalStateException("The SUT Metrics manifest integrity chain failed verification.");
            previous = entrySha;
            entries.add(entry);
        }
        Map<String, Object> last = entries.get(entries.size() - 1);
        if (!manifestDigest.equals(last.get("manifestSha256")) || !manifest.get("state").equals(last.get("state")))
            throw new IllegalStateException("The SUT Metrics manifest does not match its durable integrity record.");
        return entries;
    }

    private Map<String, Object> manifestIntegrityEntry(int sequence, String previous, String manifestDigest,
                                                        Map<String, Object> manifest, String jobId, String snapshotId) throws Exception {
        Map<String, Object> core = fields("sequence", sequence, "previousSha256", previous, "manifestSha256", manifestDigest,
                "state", manifest.get("state"), "jobId", jobId, "snapshotId", snapshotId);
        Map<String, Object> entry = new LinkedHashMap<>(core);
        entry.put("entrySha256", sha256(json.writeValueAsBytes(core)));
        return entry;
    }

    private static String manifestIntegrityRoot(String jobId, String snapshotSha256) {
        return sha256((MANIFEST_INTEGRITY_SCHEMA + "\u0000" + jobId + "\u0000" + snapshotSha256).getBytes(StandardCharsets.UTF_8));
    }

    private void updateState(String jobId, String state, String message, List<Map<String, Object>> diagnostics) throws Exception {
        Path directory = jobMetricsDirectory(jobId); Files.createDirectories(directory);
        Map<String, Object> current;
        Path manifest = directory.resolve("manifest.json");
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) current = json.convertValue(json.readTree(manifest.toFile()), Map.class);
        else current = fields("schemaVersion", COLLECTION_SCHEMA, "jobId", jobId, "files", List.of(), "queries", List.of());
        current.put("state", state); current.put("complete", "READY".equals(state)); current.put("updatedAt", Instant.now().toString());
        if (message != null) current.put("diagnostics", diagnostics.isEmpty() ? List.of(fields("code", state, "message", message)) : diagnostics);
        writeManifest(directory, current);
    }

    private Map<String, Object> readSeriesFile(String jobId, Map<String, Object> entry) throws Exception {
        return readSeriesFile(jobId, entry, Long.MAX_VALUE);
    }

    private Map<String, Object> readSeriesFile(String jobId, Map<String, Object> entry, long deadline) throws Exception {
        SutThresholdEvaluator.checkDeadline(deadline);
        String relative = String.valueOf(entry.get("path"));
        if (!relative.matches("series-[0-9]{4}\\.json")) throw new IllegalArgumentException("Unsafe SUT Metrics sidecar path.");
        Path root = jobMetricsDirectory(jobId).toRealPath(), file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) != ((Number) entry.get("lengthBytes")).longValue())
            throw new IllegalStateException("SUT Metrics companion sidecar failed integrity verification.");
        if (Files.size(file) > MAX_SERIES_FILE_BYTES) throw new IllegalStateException("SUT Metrics companion sidecar exceeds its read limit.");
        if (!sha256(file, deadline).equals(entry.get("sha256")))
            throw new IllegalStateException("SUT Metrics companion sidecar failed integrity verification.");
        try (InputStream input = new DeadlineInputStream(Files.newInputStream(file), deadline)) {
            return json.readValue(input, Map.class);
        }
    }

    private Path jobMetricsDirectory(String jobId) {
        if (jobId == null || !jobId.matches("J[A-F0-9]{16}")) throw new IllegalArgumentException("Invalid Job id.");
        Path job = jobsRoot.resolve(jobId).normalize(); if (!job.startsWith(jobsRoot)) throw new IllegalArgumentException("Invalid Job path.");
        return job.resolve("sut-metrics");
    }

    private static List<Map<String, Object>> queryMetadata(Map<String, Object> snapshot) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> query : listOfMaps(snapshot.get("queries"))) {
            Map<String, Object> row = fields("id", query.get("id"), "profile", query.get("profile"),
                    "profilePath", query.get("profilePath"), "sourceId", query.get("sourceId"), "sourceName", query.get("sourceName"),
                    "targetId", query.get("targetId"), "targetType", query.get("targetType"), "metricId", query.get("metricId"),
                    "title", query.get("title"), "category", query.get("category"), "unit", query.get("unit"), "kind", query.get("kind"),
                    "sourceRevision", query.get("sourceRevision"), "profileRevision", query.get("profileRevision"),
                    "targetRevision", query.get("targetRevision"));
            if (query.get("bindingId") != null) row.put("bindingId", query.get("bindingId"));
            rows.add(row);
        }
        return rows;
    }

    private static String interpolate(String raw, Map<String, Object> definitions, Map<String, Object> parameters) {
        Matcher matcher = PLACEHOLDER.matcher(raw); StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1); Map<String, Object> definition = objectOrEmpty(definitions.get(name)); Object value = parameters.get(name);
            if (value == null) throw new IllegalArgumentException("Unresolved profile query placeholder " + name + ".");
            String replacement = String.valueOf(value); String type = String.valueOf(definition.get("type"));
            int start = matcher.start(), end = matcher.end();
            int beforeQuote = raw.lastIndexOf('"', start), afterQuote = raw.indexOf('"', end);
            if (beforeQuote >= 0 && afterQuote >= 0) {
                String prefix = raw.substring(0, beforeQuote);
                Matcher selector = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*\\s*(?:=|=~)\\s*)$").matcher(prefix);
                if (!selector.find() || raw.substring(beforeQuote + 1, start).contains("{{") || raw.substring(end, afterQuote).contains("{{"))
                    throw new IllegalArgumentException("Profile parameters may only be substituted as complete Prometheus label-selector values.");
                String operator = selector.group(1).contains("=~") ? "=~" : "=";
                replacement = escapePromLabel(replacement, "=~".equals(operator) || "promLabelRegex".equals(type));
            } else if ("duration".equals(type)) {
                String prefix = raw.substring(0, start), suffix = raw.substring(end);
                if (!prefix.matches("(?s).*\\[\\s*") || !suffix.matches("(?s)\\s*].*")) throw new IllegalArgumentException("Duration parameters may only be used as a complete Prometheus range selector value.");
                replacement = String.valueOf(durationSeconds(replacement, name)) + "s";
            } else if ("number".equals(type) && SAFE_NUMBER.matcher(replacement).matches()) {
                // Numeric parameters are validated as finite scalar literals before interpolation.
            } else throw new IllegalArgumentException("Profile parameters may not inject PromQL fragments.");
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        if (out.indexOf("{{") >= 0 || out.indexOf("}}") >= 0) throw new IllegalArgumentException("PromQL contains an unresolved profile placeholder.");
        return out.toString();
    }

    private static String escapePromLabel(String value, boolean regex) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"' || (regex && ".^$|?*+()[]{}".indexOf(c) >= 0)) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static Object validateParameter(String name, Map<String, Object> definition, Object value) {
        String type = String.valueOf(definition.get("type")); Object checked = validateTyped(type, value);
        List<?> allowed = definition.get("allowedValues") instanceof List<?> list ? list : List.of();
        if (!allowed.isEmpty() && !allowed.contains(checked)) throw new IllegalArgumentException("Parameter " + name + " is outside its allowedValues.");
        return checked;
    }

    private static Object validateTyped(String type, Object value) {
        if ("number".equals(type)) {
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("number parameters require a finite JSON number.");
            return number;
        }
        if (!(value instanceof String text) || text.length() > 255 || hasControl(text)) throw new IllegalArgumentException(type + " parameters require a bounded string.");
        if ("duration".equals(type)) { durationSeconds(text, "duration parameter"); return text; }
        if (text.isBlank()) throw new IllegalArgumentException(type + " parameters cannot be blank.");
        return text;
    }

    private static Map<String, Object> targetParameters(Map<String, Object> target) {
        Map<String, Object> result = new LinkedHashMap<>(objectOrEmpty(target.get("parameters")));
        for (Map.Entry<String, Object> entry : target.entrySet()) if (!Set.of("id", "alias", "type", "tenant", "parameters").contains(entry.getKey())) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static String targetId(Map<String, Object> target) {
        String id = nullableText(target.get("id")); if (id == null) id = nullableText(target.get("alias"));
        if (id == null) id = nullableText(targetParameters(target).get("instance"));
        if (id == null) {
            try { id = "target-" + sha256(ServerRuntime.JSON.writeValueAsBytes(target)).substring(0, 12); }
            catch (Exception impossible) { throw new IllegalStateException("Could not identify target binding.", impossible); }
        }
        return id.length() > 120 ? id.substring(0, 120) : id;
    }

    private static String definitionName(Map<String, Object> definitions, Object value) {
        for (Map.Entry<String, Object> entry : definitions.entrySet()) if (entry.getValue() == value) return entry.getKey();
        return "";
    }

    private static long durationSeconds(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException(field + " must be a duration such as 30s or 5m.");
        long number; try { number = Long.parseLong(matcher.group(1)); } catch (NumberFormatException invalid) { throw new IllegalArgumentException(field + " is outside its supported range."); }
        long multiplier = switch (matcher.group(2)) { case "ms" -> 0; case "s" -> 1; case "m" -> 60; case "h" -> 3600; case "d" -> 86400; case "w" -> 604800; case "y" -> 31536000; default -> 0; };
        if (multiplier == 0) { if ("ms".equals(matcher.group(2)) && number >= 1000) return number / 1000; throw new IllegalArgumentException(field + " must be at least one second."); }
        if (number > Long.MAX_VALUE / multiplier) throw new IllegalArgumentException(field + " is outside its supported range.");
        return number * multiplier;
    }

    private static void validateCollectionOptions(Map<String, Object> options) {
        rejectUnknown(options, Set.of("mode", "step", "before", "after"), "observability.collection");
        if (options.containsKey("mode") && !"post-run".equals(options.get("mode"))) throw new IllegalArgumentException("Only post-run collection is supported in Phase 1.");
        long before = durationSeconds(String.valueOf(options.getOrDefault("before", "5m")), "before");
        long after = durationSeconds(String.valueOf(options.getOrDefault("after", "2m")), "after");
        if (before > 3600 || after > 3600) throw new IllegalArgumentException("Pre/post collection buffers are limited to one hour each.");
        String step = String.valueOf(options.getOrDefault("step", "30s")); long seconds = durationSeconds(step, "step");
        if (seconds < 15 || seconds > 300) throw new IllegalArgumentException("Collection step must be between 15s and 5m.");
    }

    private static void validateLiveOptions(Map<String, Object> options) {
        rejectUnknown(options, Set.of("enabled", "refreshInterval", "defaultRange"), "observability.livePreview");
        String refresh = String.valueOf(options.getOrDefault("refreshInterval", "30s"));
        if (durationSeconds(refresh, "livePreview.refreshInterval") != 30) throw new IllegalArgumentException("Live preview refreshInterval is fixed at 30s in Phase 1.");
        String range = String.valueOf(options.getOrDefault("defaultRange", "15m"));
        if (durationSeconds(range, "livePreview.defaultRange") != 15 * 60) throw new IllegalArgumentException("Live preview defaultRange is fixed at 15m in Phase 1.");
        if (options.containsKey("enabled") && !(options.get("enabled") instanceof Boolean)) throw new IllegalArgumentException("livePreview.enabled must be a boolean.");
    }

    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " must be an object.");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) { if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException(name + " has an invalid key."); result.put((String) entry.getKey(), entry.getValue()); }
        return result;
    }

    private static Map<String, Object> objectOrEmpty(Object value) { return value instanceof Map<?, ?> ? object(value, "object") : new LinkedHashMap<>(); }
    private static List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>(); if (!(value instanceof List<?> list)) return result;
        for (Object item : list) if (item instanceof Map<?, ?>) result.add(object(item, "object item")); return result;
    }
    private static List<String> listOfStrings(Object value) { return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of(); }
    private static Set<String> stringSet(Object value, String field) {
        if (value == null) return Set.of(); if (!(value instanceof List<?> list)) throw new IllegalArgumentException(field + " must be an array of strings.");
        Set<String> result = new LinkedHashSet<>(); for (Object item : list) { if (!(item instanceof String text) || text.isBlank() || text.length() > 120 || !result.add(text)) throw new IllegalArgumentException(field + " contains an invalid or duplicate value."); }
        return result;
    }
    private static List<String> checkedStrings(Object value, int maxCount, int maxLength, String field) {
        if (value == null) return List.of(); if (!(value instanceof List<?> list) || list.size() > maxCount) throw new IllegalArgumentException(field + " exceeds its entry limit.");
        List<String> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Object item : list) { if (!(item instanceof String text) || text.isBlank() || text.length() > maxLength || hasControl(text) || !seen.add(text)) throw new IllegalArgumentException(field + " contains an invalid or duplicate value."); out.add(text); }
        return out;
    }
    private static List<String> checkedRoles(Object value) {
        List<String> roles = checkedStrings(value, 8, 32, "readRoles");
        for (String role : roles) if (!Set.of("USER", "ADMIN").contains(role)) throw new IllegalArgumentException("readRoles may contain USER and ADMIN only.");
        return roles;
    }
    private static List<String> checkedParamNames(Object value, String field) {
        if (value == null) return List.of(); if (!(value instanceof List<?> list) || list.size() > 32) throw new IllegalArgumentException(field + " must be a bounded array.");
        List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Object item : list) { if (!(item instanceof String text) || !PARAM.matcher(text).matches() || !seen.add(text)) throw new IllegalArgumentException(field + " contains an invalid or duplicate parameter name."); result.add(text); }
        return result;
    }
    private static void rejectUnknown(Map<String, ?> value, Set<String> allowed, String name) { for (String key : value.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown " + name + " field: " + key + "."); }
    private static String requiredText(Map<String, ?> value, String name) { Object item = value.get(name); if (!(item instanceof String text) || text.isBlank() || text.length() > 4096 || hasControl(text)) throw new IllegalArgumentException(name + " must be a bounded non-empty string."); return text; }
    private static String nullableText(Object value) { return value instanceof String text && !text.isBlank() ? text : null; }
    private static String boundedText(Object value, int maximum) { if (value == null) return ""; if (!(value instanceof String text) || text.length() > maximum || hasControl(text)) throw new IllegalArgumentException("Text field exceeds its limit."); return text; }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static boolean hasControl(String value) { return value != null && value.codePoints().anyMatch(Character::isISOControl); }
    private static int boundedInt(Object raw, int defaultValue, int minimum, int maximum) { if (raw == null) return defaultValue; if (!(raw instanceof Number number)) throw new IllegalArgumentException("Limit values must be integers."); int value = number.intValue(); if (number.doubleValue() != value || value < minimum || value > maximum) throw new IllegalArgumentException("Limit value is outside its supported range."); return value; }
    private static int sourceInt(Map<String, Object> source, String name) { return ((Number) objectOrEmpty(source.get("limits")).get(name)).intValue(); }
    private static boolean isLoopbackHost(String host) { return host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(Locale.ROOT)); }
    private void enforceAllowedPrometheusUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) && isLoopbackHost(uri.getHost())) return;
        if ("https".equals(scheme) && allowedPrometheusHosts.contains(normalizeHost(uri.getHost()))) return;
        throw new IllegalArgumentException("Prometheus host is not in the Server ATT_SUT_METRICS_ALLOWED_HOSTS exact-host allowlist.");
    }
    private static Set<String> configuredAllowedPrometheusHosts() {
        String raw = System.getenv("ATT_SUT_METRICS_ALLOWED_HOSTS");
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> hosts = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String host = part.trim();
            if (host.isEmpty() || host.contains("*") || host.contains("/") || host.contains("@") || host.contains("?"))
                throw new IllegalArgumentException("ATT_SUT_METRICS_ALLOWED_HOSTS must contain exact host names or IP addresses separated by commas.");
            hosts.add(normalizeHost(host));
        }
        return Set.copyOf(hosts);
    }
    private static Set<String> normalizeAllowedHosts(Set<String> hosts) {
        if (hosts == null || hosts.isEmpty()) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String host : hosts) {
            if (host == null || host.isBlank() || host.contains("*") || host.contains("/") || host.contains("@") || host.contains("?"))
                throw new IllegalArgumentException("Prometheus host allowlist entries must be exact host names or IP addresses.");
            normalized.add(normalizeHost(host));
        }
        return Set.copyOf(normalized);
    }
    private static String normalizeHost(String host) {
        if (host == null) return "";
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }
    private static void validateSecretRef(String ref) { if (!SECRET_REF.matcher(ref).matches()) throw new IllegalArgumentException("Secret references must be uppercase environment variable names."); }
    private String secret(String ref) { validateSecretRef(ref); String value = secretResolver.apply(ref); if (value == null || value.isBlank()) throw new IllegalArgumentException("A configured Server secret reference is unavailable."); return value; }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static byte[] readBounded(InputStream input, int maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192)); byte[] buffer = new byte[8192]; int count, total = 0;
        while ((count = input.read(buffer)) >= 0) { if (count == 0) continue; if (total > maximum - count) throw new IllegalArgumentException("Prometheus response exceeds its configured byte limit."); output.write(buffer, 0, count); total += count; }
        return output.toByteArray();
    }
    private static String safeFailureCode(Exception failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || failure instanceof java.net.http.HttpTimeoutException) return "PROMETHEUS_TIMEOUT";
        if (message.contains("response exceeds its configured byte limit")) return "PROMETHEUS_RESPONSE_TOO_LARGE";
        if (message.contains("too many") || message.contains("budget") || message.contains("limit")) return "QUERY_BUDGET_EXCEEDED";
        if (message.contains("http ")) return "PROMETHEUS_HTTP_ERROR";
        return "PROMETHEUS_UNAVAILABLE";
    }
    private static String safeFailureMessage(Exception failure) {
        String message = failure.getMessage(); if (message == null || message.isBlank()) return "Prometheus query failed.";
        // Never reflect an endpoint or authentication header into public diagnostics.
        if (message.contains("http://") || message.contains("https://") || message.toLowerCase(Locale.ROOT).contains("authorization")) return "Prometheus query failed; endpoint details are withheld.";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
    private static Map<String, Map<String, Object>> deepMap(Map<String, Map<String, Object>> input) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : input.entrySet()) copy.put(entry.getKey(), ServerRuntime.JSON.convertValue(entry.getValue(), Map.class));
        return copy;
    }
    private static Map<String, Object> fields(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    private static ExecutorService boundedExecutor(int threads, int queued, String name) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queued), task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    private static ScheduledThreadPoolExecutor scheduledExecutor(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
    private void atomicJson(Path path, Object value) throws Exception { atomicBytes(path, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    private void atomicBytes(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.getParent()); Path temporary = Files.createTempFile(path.getParent(), ".sut-metrics-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
            Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String sha256(Path path) throws Exception { try (InputStream input = Files.newInputStream(path)) { MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read); return hex(digest.digest()); } }
    private static String sha256(Path path, long deadline) throws Exception { try (InputStream input = new DeadlineInputStream(Files.newInputStream(path), deadline)) { MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read); return hex(digest.digest()); } }
    private static String sha256(byte[] bytes) { try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    private String sha256Json(Object value) {
        try { return sha256(json.writeValueAsBytes(value)); }
        catch (Exception impossible) { throw new IllegalStateException("Could not calculate a safe SUT Metrics revision.", impossible); }
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff)); return out.toString(); }
    private Map<String, Object> cachedPreview(String key) { CacheEntry entry = previewCache.get(key); if (entry == null) return null; if (System.nanoTime() >= entry.expiresAtNanos) { previewCache.remove(key, entry); return null; } return entry.value; }
    private void trimPreviewCache() { if (previewCache.size() > 128) previewCache.entrySet().removeIf(entry -> System.nanoTime() >= entry.getValue().expiresAtNanos); if (previewCache.size() > 128) previewCache.clear(); }

    @Override public void close() { collectionScheduler.shutdownNow(); collectors.shutdownNow(); evaluators.shutdownNow(); previews.shutdownNow(); bodyReaders.shutdownNow(); scheduledCollections.clear(); evaluatingJobs.clear(); previewFlights.clear(); previewCache.clear(); clients.clear(); }
    static final class CapacityException extends RuntimeException { CapacityException(String message) { super(message); } }
    static final class SutMetricsTimeoutException extends RuntimeException { SutMetricsTimeoutException(String message) { super(message); } }
    private static final class DeadlineInputStream extends FilterInputStream {
        private final long deadlineNanos;
        DeadlineInputStream(InputStream input, long deadlineNanos) { super(input); this.deadlineNanos = deadlineNanos; }
        @Override public int read() throws java.io.IOException {
            SutThresholdEvaluator.checkDeadline(deadlineNanos);
            return super.read();
        }
        @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            SutThresholdEvaluator.checkDeadline(deadlineNanos);
            return super.read(buffer, offset, length);
        }
    }
    private static final class ManifestWriteLock {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }
    private static final class CollectionBudgetExceededException extends IllegalArgumentException {
        CollectionBudgetExceededException(String message) { super(message); }
    }
    private record CacheEntry(long expiresAtNanos, Map<String, Object> value) { }
    private record SnapshotEnvelope(Map<String, Object> snapshot, String snapshotSha256, String initialManifestSha256) { }
}
