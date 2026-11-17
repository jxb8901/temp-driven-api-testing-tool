package att.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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

/**
 * Pure, deterministic adapters between the 4.3.0 SUT Metrics snapshots and
 * the source-neutral 4.3.1 contracts. Historical objects are never rewritten.
 */
final class SutMetricsContractMigration {
    static final String PROFILE_V1 = "att-sut-metrics-profile/v1";
    static final String PROFILE_V2 = "att-sut-metrics-profile/v2";
    static final String SNAPSHOT_V1 = "att-sut-metrics-snapshot/v1";
    static final String SNAPSHOT_V2 = "att-sut-metrics-snapshot/v2";
    static final String OBSERVATION_V1 = "att-sut-metrics-observation/v1";
    static final String OBSERVATION_V2 = "att-sut-metrics-observation/v2";

    private static final ObjectMapper JSON = ServerRuntime.JSON;
    private static final int MAX_QUERIES = 256;
    private static final int MAX_SOURCES = 64;
    private static final int MAX_OUTPUTS_PER_FETCH = 64;
    private static final int MAX_BINDINGS = 256;
    private static final int MAX_OBSERVATIONS = 4096;
    private static final int MAX_LABELS = 40;
    private static final Set<String> SENSITIVE_LABEL = Set.of("token", "secret", "password", "credential", "authorization", "api_key");

    private SutMetricsContractMigration() { }

    /**
     * Produces a new v2 snapshot model while leaving the v1-derived query
     * payload in memory for the writer to move into its private payload file.
     */
    static Map<String, Object> upgradeSnapshot(Map<String, Object> legacy) {
        if (legacy == null || !SNAPSHOT_V1.equals(legacy.get("schemaVersion")))
            throw new IllegalArgumentException("Only an att-sut-metrics-snapshot/v1 snapshot can be migrated.");
        List<Map<String, Object>> queries = list(legacy.get("queries"));
        if (queries.size() > MAX_QUERIES) throw new IllegalArgumentException("The legacy SUT Metrics snapshot exceeds its query limit.");
        Map<String, String> defaultMetricSourceByConnection = new LinkedHashMap<>();
        Map<String, String> connectionByMetricSource = new LinkedHashMap<>();
        Map<String, Set<String>> fetchesByMetricSource = new LinkedHashMap<>();
        Map<String, Set<String>> metricRevisionsByMetricSource = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> queriesByFetch = new LinkedHashMap<>();
        Map<String, Map<String, Object>> fetchPlan = new LinkedHashMap<>();
        Map<String, Map<String, Object>> nativeMetricSources = new LinkedHashMap<>();
        for (Map<String, Object> source : list(legacy.get("nativeMetricSources")))
            nativeMetricSources.put(text(source.get("id"), "metric source id"), source);
        List<Map<String, Object>> bindings = new ArrayList<>();
        List<Map<String, Object>> expectedSubjects = new ArrayList<>();
        Set<String> seenBindings = new HashSet<>(), seenSubjects = new HashSet<>();

        for (Map<String, Object> query : queries) {
            String sourceId = text(query.get("sourceId"), "sourceId");
            boolean business = "business-call".equals(query.get("observationType"));
            String oldFetchId = effectiveFetchBindingId(query, legacy.get("collection"));
            String fetchKey = sourceId + "\u0000" + oldFetchId;
            String fetchId = canonicalFetchId(fetchKey);
            String metricSourceId = nullableText(query.get("metricSourceId"));
            if (metricSourceId == null) metricSourceId = defaultMetricSourceByConnection.computeIfAbsent(sourceId,
                    ignored -> "metric-source-" + digest(sourceId).substring(0, 32));
            Map<String, Object> nativeMetricSource = nativeMetricSources.get(metricSourceId);
            if (!nativeMetricSources.isEmpty() && nativeMetricSource == null)
                throw new IllegalArgumentException("A resolved query references an undeclared Metric Source.");
            String priorConnection = connectionByMetricSource.putIfAbsent(metricSourceId, sourceId);
            if (priorConnection != null && !priorConnection.equals(sourceId))
                throw new IllegalArgumentException("A Metric Source cannot resolve to multiple connection identities.");
            query.put("fetchId", fetchId);
            query.put("metricSourceId", metricSourceId);
            String fingerprint = fetchFingerprint(query, sourceId, oldFetchId);
            query.put("fetchFingerprint", fingerprint);
            queriesByFetch.computeIfAbsent(fetchId, ignored -> new ArrayList<>()).add(query);
            fetchesByMetricSource.computeIfAbsent(metricSourceId, ignored -> new LinkedHashSet<>()).add(fetchId);
            metricRevisionsByMetricSource.computeIfAbsent(metricSourceId, ignored -> new LinkedHashSet<>())
                    .add(fingerprint);

            String targetId = text(query.getOrDefault("targetId", business ? query.getOrDefault("workloadId", "job") : "target"), "targetId");
            String targetType = text(query.getOrDefault("targetType", business ? query.getOrDefault("scope", "job") : "target"), "targetType");
            String tenant = nullableText(query.get("tenant"));
            String bindingKey = metricSourceId + "\u0000" + targetId + "\u0000" + targetType + "\u0000" + String.valueOf(tenant);
            if (seenBindings.add(bindingKey)) {
                Map<String, Object> binding = fields("sourceId", metricSourceId, "targetId", targetId,
                        "targetType", targetType);
                if (tenant != null) binding.put("tenant", tenant);
                bindings.add(binding);
            }
            Map<String, Object> subject = legacySubject(query);
            if (subject != null) {
                String key = digest(subject);
                if (seenSubjects.add(key)) expectedSubjects.add(subject);
            } else {
                Map<String, Object> targetSubject = fields("scope", "target", "targetId", targetId, "targetType", targetType);
                String key = digest(targetSubject);
                if (seenSubjects.add(key)) expectedSubjects.add(targetSubject);
            }
        }
        if (expectedSubjects.size() > MAX_OBSERVATIONS)
            throw new IllegalArgumentException("The legacy SUT Metrics snapshot exceeds its expected-subject limit.");
        if (connectionByMetricSource.size() > MAX_SOURCES || bindings.size() > MAX_BINDINGS)
            throw new IllegalArgumentException("The legacy SUT Metrics snapshot exceeds its source or binding limit.");

        for (Map.Entry<String, List<Map<String, Object>>> entry : queriesByFetch.entrySet()) {
            List<Map<String, Object>> members = entry.getValue();
            Map<String, Object> first = members.get(0);
            List<Map<String, Object>> outputs = new ArrayList<>();
            List<Map<String, Object>> subjects = new ArrayList<>();
            Set<String> uniqueOutputs = new LinkedHashSet<>(), uniqueSubjects = new LinkedHashSet<>();
            for (Map<String, Object> query : members) {
                String outputKey = String.valueOf(query.get("metricId")) + "\u0000" + String.valueOf(query.get("unit"))
                        + "\u0000" + String.valueOf(query.get("kind"));
                if (uniqueOutputs.add(outputKey)) {
                    Map<String, Object> output = fields("metricId", query.get("metricId"), "unit", query.get("unit"),
                            "kind", query.get("kind"), "mappingRevision", mappingRevision(query));
                    if (query.get("column") != null) output.put("column", query.get("column"));
                    if (query.get("metricName") != null) output.put("metricName", query.get("metricName"));
                    if (query.get("discriminator") != null) output.put("discriminator", query.get("discriminator"));
                    outputs.add(output);
                }
                Map<String, Object> subject = legacySubject(query);
                if (subject == null) subject = fields("scope", "target", "targetId", query.get("targetId"),
                        "targetType", query.get("targetType"));
                String subjectKey = digest(subject);
                if (uniqueSubjects.add(subjectKey)) subjects.add(subject);
            }
            if (outputs.size() > MAX_OUTPUTS_PER_FETCH)
                throw new IllegalArgumentException("A legacy SUT Metrics fetch exceeds its declared output limit.");
            Map<String, Object> item = fields("id", entry.getKey(),
                    "sourceId", first.get("metricSourceId"),
                    "revision", digest(fields("fingerprint", first.get("fetchFingerprint"), "outputs", outputs)),
                    "fingerprint", first.get("fetchFingerprint"),
                    "sampling", first.getOrDefault("sampling", "range"),
                    "window", first.get("window"),
                    "outputs", outputs,
                    "expectedSubjects", subjects);
            fetchPlan.put(entry.getKey(), item);
        }

        List<Map<String, Object>> metricSources = new ArrayList<>();
        for (Map.Entry<String, String> entry : connectionByMetricSource.entrySet()) {
            String metricSourceId = entry.getKey(), sourceId = entry.getValue();
            Map<String, Object> nativeSource = nativeMetricSources.get(metricSourceId);
            if (nativeSource != null) {
                Map<String, Object> connection = asMap(nativeSource.get("connection"));
                Map<String, Object> metricSourceSummary = fields("id", metricSourceId, "revision", nativeSource.get("revision"),
                        "connectionKind", connection.get("kind"), "connectionId", connection.get("id"),
                        "fetchIds", sorted(fetchesByMetricSource.getOrDefault(metricSourceId, Set.of())));
                metricSourceSummary.put("mapping", fields("allowedLabels",
                        asMap(nativeSource.get("mapping")).getOrDefault("allowedLabels", List.of())));
                if (nativeSource.get("limits") instanceof Map<?, ?> limits)
                    metricSourceSummary.put("limits", new LinkedHashMap<>(asMap(limits)));
                metricSources.add(metricSourceSummary);
            } else {
                Map<String, Object> members = firstForSource(queries, sourceId);
                String sourceType = String.valueOf(members.getOrDefault("sourceType", "DATA_SOURCE"));
                String connectionKind = "business-call".equals(members.get("observationType")) ? sourceType : "DATA_SOURCE";
                String sourceRevision = nullableText(members.get("sourceRevision"));
                metricSources.add(fields("id", metricSourceId,
                        "revision", digest(fields("connectionId", sourceId, "connectionRevision", sourceRevision,
                                "fetches", sorted(fetchesByMetricSource.getOrDefault(metricSourceId, Set.of())),
                                "mappings", sorted(metricRevisionsByMetricSource.getOrDefault(metricSourceId, Set.of())))),
                        "connectionKind", normalizedConnectionKind(connectionKind),
                        "connectionId", sourceId,
                        "fetchIds", sorted(fetchesByMetricSource.getOrDefault(metricSourceId, Set.of()))));
            }
        }
        metricSources.sort(Comparator.comparing(row -> String.valueOf(row.get("id"))));

        Map<String, Object> firstQuery = queries.isEmpty() ? Map.of() : queries.get(0);
        Set<String> profileRevisions = new LinkedHashSet<>();
        Set<String> profileIds = new LinkedHashSet<>();
        for (Map<String, Object> query : queries) {
            if (query.get("profileRevision") instanceof String revision) profileRevisions.add(revision);
            if (query.get("profile") instanceof String id) profileIds.add(id);
        }
        List<String> profileRevisionValues = sorted(profileRevisions);
        String profileRevision = digest(fields("profileIds", sorted(profileIds), "revisions", profileRevisionValues));
        String profileId = profileIds.size() == 1 ? profileIds.iterator().next() : nullableText(firstQuery.get("bindingId"));
        Map<String, Object> nativeProfile = asMap(legacy.get("nativeProfile"));
        Map<String, Object> profile = nativeProfile.isEmpty()
                ? fields("schemaVersion", PROFILE_V1, "id", profileId, "revision", profileRevision, "migration", "legacy-v1-adapter")
                : fields("schemaVersion", PROFILE_V2, "id", nativeProfile.get("id"),
                        "revision", nativeProfile.get("revision"), "migration", "native-v2");

        Map<String, Object> sources = asMap(legacy.get("sources"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SNAPSHOT_V2);
        List<Map<String, Object>> groups = list(legacy.get("groups"));
        result.put("snapshotId", digest(fields("legacySnapshotId", legacy.get("snapshotId"),
                "profile", profile, "metricSources", metricSources, "groups", groups,
                "fetchPlan", new ArrayList<>(fetchPlan.values()))));
        result.put("createdAt", legacy.getOrDefault("createdAt", Instant.EPOCH.toString()));
        result.put("profile", profile);
        result.put("metricSources", metricSources);
        result.put("groups", groups);
        result.put("bindings", bindings);
        result.put("fetchPlan", new ArrayList<>(fetchPlan.values()));
        result.put("expectedSubjects", expectedSubjects);
        result.put("collection", legacy.getOrDefault("collection", Map.of()));
        result.put("livePreview", legacy.getOrDefault("livePreview", Map.of()));
        result.put("provenance", legacy.getOrDefault("provenance", List.of()));
        result.put("queries", queries);
        result.put("sources", sources);
        if (legacy.get("execution") instanceof Map<?, ?> execution)
            result.put("execution", new LinkedHashMap<>(asMap(execution)));
        if (legacy.get("thresholds") instanceof List<?> thresholds)
            result.put("thresholds", thresholds);
        result.put("budgets", legacy.getOrDefault("budgets", Map.of()));
        result.put("executionPayloadSha256", digest(List.of()));
        if (nativeProfile.isEmpty()) result.put("compatibility", fields("sourceSchema", PROFILE_V1,
                "adapterVersion", "v1-to-v2", "unsupportedFeatures", List.of()));
        return result;
    }

    /** Returns the same immutable fetch id used by {@link #upgradeSnapshot(Map)} before the
     * v2 migration runs, so budget preflight and the persisted plan count identical work. */
    static String effectiveFetchId(Map<String, Object> query, Map<String, Object> collection) {
        String sourceId = text(query.get("sourceId"), "sourceId");
        return canonicalFetchId(sourceId + "\u0000" + effectiveFetchBindingId(query, collection));
    }

    private static String effectiveFetchBindingId(Map<String, Object> query, Object collection) {
        if ("business-call".equals(query.get("observationType")))
            return text(query.getOrDefault("callGroupId", query.get("id")), "callGroupId");
        return "prometheus:" + digest(fields("sourceId", query.get("sourceId"),
                "sourceRevision", query.get("sourceRevision"),
                "targetRevision", query.get("targetRevision"),
                "targetId", query.get("targetId"), "targetType", query.get("targetType"),
                "tenant", query.get("tenant"), "query", query.get("query"),
                "parameters", query.get("parameters"), "sampling", query.getOrDefault("sampling", "range"),
                "window", query.get("window"), "collection", collection))
                + (hasPrometheusOutputSelector(query) ? "" : "\u0000" + query.get("id"));
    }

    private static String canonicalFetchId(String fetchKey) {
        return "fetch-" + digest(fetchKey).substring(0, 40);
    }

    static Map<String, Object> privateExecutionPayload(Map<String, Object> snapshot, String jobId) {
        Map<String, Map<String, Object>> fetches = new LinkedHashMap<>();
        for (Map<String, Object> query : list(snapshot.get("queries"))) {
            String fetchId = nullableText(query.get("fetchId"));
            if (fetchId == null) {
                String sourceId = text(query.get("sourceId"), "sourceId");
                String bindingId = "business-call".equals(query.get("observationType"))
                        ? String.valueOf(query.getOrDefault("callGroupId", query.get("id")))
                        : String.valueOf(query.get("id"));
                fetchId = "fetch-" + digest(sourceId + "\u0000" + bindingId).substring(0, 40);
            }
            String executableQuery = nullableText(query.get("query"));
            String executableCall = nullableText(query.get("call"));
            if (executableQuery == null && executableCall == null) continue;
            Map<String, Object> payload = fields("id", fetchId);
            if (executableQuery != null) payload.put("query", executableQuery);
            if (executableCall != null) payload.put("call", executableCall);
            Map<String, Object> parameters = asMap(query.get("parameters"));
            if (!parameters.isEmpty()) {
                if (parameters.size() > 32 || parameters.values().stream().anyMatch(value -> !(value instanceof String
                        || value instanceof Number || value instanceof Boolean)))
                    throw new IllegalArgumentException("The private SUT Metrics execution plan contains invalid parameters.");
                payload.put("parameters", parameters);
            }
            Map<String, Object> prior = fetches.putIfAbsent(fetchId, payload);
            if (prior != null && !prior.equals(payload))
                throw new IllegalStateException("A shared SUT Metrics fetch binding has conflicting executable payloads.");
        }
        List<Map<String, Object>> rows = new ArrayList<>(fetches.values());
        if (rows.size() > MAX_QUERIES) throw new IllegalArgumentException("The private SUT Metrics execution plan exceeds its fetch limit.");
        if (asMap(snapshot.get("sources")).size() > MAX_SOURCES)
            throw new IllegalArgumentException("The private SUT Metrics execution plan exceeds its source limit.");
        return fields("schemaVersion", "att-sut-metrics-execution-payload/v2", "jobId", jobId,
                "fetches", rows, "sources", asMap(snapshot.get("sources")));
    }

    static Map<String, Object> redactSnapshotForStorage(Map<String, Object> snapshot) {
        Map<String, Object> safe = new LinkedHashMap<>(asMap(snapshot));
        safe.remove("__attPrivateExecutionPayload");
        safe.remove("__attPrivateSources");
        List<Map<String, Object>> queries = new ArrayList<>();
        for (Map<String, Object> query : list(snapshot.get("queries"))) {
            Map<String, Object> row = safeQuery(query);
            if (nullableText(row.get("fetchId")) == null) {
                String sourceId = text(row.get("sourceId"), "sourceId");
                String bindingId = "business-call".equals(row.get("observationType"))
                        ? String.valueOf(row.getOrDefault("callGroupId", row.get("id")))
                        : String.valueOf(row.get("id"));
                row.put("fetchId", "fetch-" + digest(sourceId + "\u0000" + bindingId).substring(0, 40));
            }
            queries.add(row);
        }
        safe.put("queries", queries);
        safe.put("sources", safeSources(snapshot.get("sources")));
        safe.put("provenance", safeProvenance(snapshot.get("provenance")));
        return safe;
    }

    /**
     * Expands a v1 sidecar into one canonical v2 observation per label set.
     * Duplicate normalized identities fail closed rather than merging values.
     */
    static List<Map<String, Object>> normalizeObservationV1(Map<String, Object> sidecar,
                                                             Map<String, Object> snapshot,
                                                             String jobId) {
        return normalizeObservationV1(sidecar, snapshot, jobId, null);
    }

    static List<Map<String, Object>> normalizeObservationV1(Map<String, Object> sidecar,
                                                             Map<String, Object> snapshot,
                                                             String jobId,
                                                             Long requestCountOverride) {
        if (!OBSERVATION_V1.equals(sidecar.get("schemaVersion")))
            throw new IllegalArgumentException("Only att-sut-metrics-observation/v1 sidecars can be migrated.");
        if (sidecar.get("labeledValues") instanceof List<?> labeledValues) {
            if (labeledValues.isEmpty() || labeledValues.size() > SutMetricsService.MAX_LABELLED_VALUES)
                throw new IllegalArgumentException("The wide-row observation exceeds the normalized observation limit.");
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Object rawValue : labeledValues) {
                Map<String, Object> labeled = asMap(rawValue);
                Map<String, Object> oneValue = new LinkedHashMap<>(sidecar);
                oneValue.remove("labeledValues");
                oneValue.remove("series");
                oneValue.put("value", labeled.get("value"));
                Map<String, Object> observation = normalizeObservationV1(oneValue, snapshot, jobId, requestCountOverride).get(0);
                Map<String, Object> identity = new LinkedHashMap<>(asMap(observation.get("identity")));
                identity.put("labels", safeLabels(labeled.get("labels")));
                observation.put("identity", identity);
                observation.put("observationId", digest(identity));
                normalized.add(observation);
            }
            return List.copyOf(normalized);
        }
        List<Map<String, Object>> snapshotQueries = list(snapshot == null ? null : snapshot.get("queries"));
        String queryId = text(sidecar.get("queryId"), "queryId");
        Map<String, Object> query = snapshotQueries.stream()
                .filter(candidate -> queryId.equals(candidate.get("id"))).findFirst().orElse(Map.of());
        Map<String, Object> nested = asMap(sidecar.get("observation"));
        Map<String, Object> source = asMap(nested.get("source"));
        Map<String, Object> profile = asMap(nested.get("profile"));
        Map<String, Object> provenance = asMap(nested.get("provenance"));
        Map<String, Object> window = asMap(nested.get("window"));
        Map<String, Object> availability = asMap(nested.get("availability"));
        String sourceId = text(sidecar.getOrDefault("sourceId", source.get("id")), "sourceId");
        String metricId = text(sidecar.get("metricId"), "metricId");
        String targetId = text(sidecar.getOrDefault("targetId", asMap(nested.get("target")).get("id")), "targetId");
        String targetType = text(sidecar.getOrDefault("targetType", asMap(nested.get("target")).get("type")), "targetType");
        String kind = text(sidecar.getOrDefault("kind", asMap(nested.get("metric")).get("kind")), "metric kind");
        String unit = text(sidecar.get("unit"), "unit");
        String title = text(sidecar.getOrDefault("title", sidecar.get("metricId")), "metric title");
        String observationKind = text(nested.get("kind"), "observation kind");
        String startAt = firstText(window.get("queryStart"), window.get("executionFinishedAt"), window.get("observedAt"));
        String endAt = firstText(window.get("queryEnd"), window.get("observedAt"), window.get("completedAt"), startAt);
        if (startAt == null || endAt == null || !isInstant(startAt) || !isInstant(endAt))
            throw new IllegalArgumentException("The v1 SUT Metrics observation has no valid temporal window.");
        String step = nullableText(window.get("step"));
        String semantics = firstText(window.get("semantics"), window.get("kind"), observationKind);
        String effectiveBindingId = nullableText(nested.get("effectiveBindingId"));
        String legacyFetchId = nullableText(query.get("fetchId"));
        if (legacyFetchId == null) {
            String stableLegacyKey = "business-call".equals(query.get("observationType"))
                    ? String.valueOf(query.getOrDefault("callGroupId", queryId)) : queryId;
            legacyFetchId = "fetch-" + digest(sourceId + "\u0000" + stableLegacyKey).substring(0, 40);
        }
        String bindingSourceId = nullableText(query.get("metricSourceId"));
        if (bindingSourceId == null) bindingSourceId = "metric-source-" + digest(sourceId).substring(0, 32);
        String fetchRevision = nullableText(query.get("fetchFingerprint"));
        if (fetchRevision == null) fetchRevision = digest(fields("source", sourceId, "query", queryId,
                "effectiveBinding", effectiveBindingId, "profile", profile.get("revision")));
        String sourceRevision = firstText(source.get("revision"), query.get("sourceRevision"));
        if (sourceRevision == null || !sourceRevision.matches("[a-f0-9]{64}")) sourceRevision = digest(fields("source", sourceId));
        String snapshotId = nullableText(asMap(snapshot).get("snapshotId"));
        if (snapshotId == null || !snapshotId.matches("[a-f0-9]{64}")) snapshotId = digest(fields("jobId", jobId, "source", sourceId));
        String invocationId = digest(fields("jobId", jobId, "snapshotId", snapshotId, "fetchId", legacyFetchId));
        String connectionKind = normalizedConnectionKind(String.valueOf(provenance.getOrDefault("sourceType",
                "prometheus".equals(provenance.get("provider")) ? "DATA_SOURCE" : "LEGACY")));
        Map<String, Object> acquisition = fields("invocationId", invocationId,
                "source", fields("id", bindingSourceId, "revision", sourceRevision, "connectionKind", connectionKind),
                "fetch", fields("id", legacyFetchId, "revision", fetchRevision),
                "execution", fields("state", acquisitionState(availability, sidecar),
                        "startedAt", firstText(window.get("queryStart"), window.get("executionFinishedAt")),
                        "completedAt", firstText(window.get("completedAt"), window.get("observedAt"), window.get("queryEnd")),
                        "durationMs", numberOr(availability.get("collectionDurationMs"), 0L),
                        "requestCount", numberOr(requestCountOverride, numberOr(sidecar.get("requestCount"), numberOr(availability.get("callCount"), 0L))),
                        "diagnostics", diagnosticCodes(availability.get("diagnostics"))),
                "job", fields("jobId", jobId, "snapshotId", snapshotId));

        Map<String, Object> subject = subject(sidecar, query);
        Object rawSeries = sidecar.get("series");
        List<Map<String, Object>> seriesRows = list(rawSeries);
        if (seriesRows.isEmpty()) seriesRows = List.of(Map.of("labels", Map.of(), "samples", List.of()));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (Map<String, Object> series : seriesRows) {
            Map<String, String> labels = safeLabels(series.get("labels"));
            List<Map<String, Object>> samples = samples(series.get("samples"));
            Map<String, Object> seriesCoverage = asMap(series.get("coverage"));
            Map<String, Object> coverage = seriesCoverage.isEmpty()
                    ? asMap(availability.get("coverage")) : seriesCoverage;
            Map<String, Object> identity = fields("metricId", metricId,
                    "binding", fields("sourceId", bindingSourceId, "fetchId", legacyFetchId),
                    "target", fields("id", targetId, "type", targetType),
                    "labels", labels, "subject", subject,
                    "window", fields("kind", observationKind, "startAt", startAt, "endAt", endAt,
                            "step", step, "semantics", semantics));
            String observationId = digest(identity);
            if (!identities.add(observationId))
                throw new IllegalArgumentException("Duplicate SUT Metrics observation identity in v1 sidecar; refusing to merge or overwrite it.");
            Map<String, Object> data = fields("state", observationState(availability, sidecar),
                    "freshness", freshness(availability.get("freshness")),
                    "diagnostics", diagnostics(availability.get("diagnostics")));
            if (!coverage.isEmpty()) data.put("coverage", normalizeCoverage(coverage, startAt, endAt));
            if (sidecar.containsKey("value")) {
                Object value = sidecar.get("value");
                data.put("value", value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : null);
            } else {
                data.put("series", samples);
            }
            Map<String, Object> normalized = fields("schemaVersion", OBSERVATION_V2, "observationId", observationId,
                    "identity", identity, "metric", fields("id", metricId, "name", title, "unit", unit, "kind", kind),
                    "data", data, "acquisition", acquisition);
            result.add(normalized);
            if (result.size() > MAX_OBSERVATIONS)
                throw new IllegalArgumentException("The v1 sidecar exceeds the normalized observation limit.");
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> firstForSource(List<Map<String, Object>> queries, String sourceId) {
        return queries.stream().filter(query -> sourceId.equals(query.get("sourceId"))).findFirst().orElse(Map.of());
    }

    private static Map<String, Object> safeQuery(Map<String, Object> query) {
        Set<String> allowed = Set.of("id", "fetchId", "metricSourceId", "fetchFingerprint", "bindingId",
                "profile", "profilePath", "profileRevision", "sourceId", "sourceName", "sourceType",
                "sourceRevision", "targetId", "targetType", "targetRevision", "metricId", "title",
                "category", "unit", "kind", "observationType", "observationKind", "sampling", "window",
                "step", "scope", "workloadId", "mixId", "tenant", "metricName", "discriminator", "subjectKey", "callGroupId", "callType", "callName",
                "callWorkloadId", "valuePath", "column", "multiOutput", "sideEffectsAcknowledged", "mapping");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowed) if (query.containsKey(key)) result.put(key, query.get(key));
        return result;
    }

    private static List<Map<String, Object>> safeProvenance(Object raw) {
        Set<String> allowed = Set.of("profile", "profilePath", "profileRevision", "sourceId", "sourceName",
                "sourceRevision", "targetId", "targetType", "targetRevision", "metricId", "scope",
                "workloadId", "mixId");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : list(raw)) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (String key : allowed) if (row.containsKey(key)) safe.put(key, row.get(key));
            result.add(safe);
            if (result.size() == 4096) break;
        }
        return result;
    }

    private static Map<String, Object> safeSources(Object raw) {
        Map<String, Object> all = asMap(raw);
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : all.entrySet()) {
            Map<String, Object> source = asMap(entry.getValue());
            String revision = nullableText(source.get("revision"));
            if (revision == null || !revision.matches("[a-f0-9]{64}")) revision = digest(source);
            Map<String, Object> summary = fields("id", entry.getKey(),
                    "name", source.getOrDefault("name", entry.getKey()),
                    "provider", source.getOrDefault("provider", "PROMETHEUS"),
                    "revision", revision);
            if (source.get("limits") instanceof Map<?, ?> limits) summary.put("limits", limits);
            if (source.get("allowedTenants") instanceof List<?> tenants) summary.put("allowedTenants", tenants);
            if (source.get("defaultTenant") instanceof String tenant) summary.put("defaultTenant", tenant);
            safe.put(entry.getKey(), summary);
        }
        return safe;
    }

    private static boolean hasPrometheusOutputSelector(Map<String, Object> query) {
        return nullableText(query.get("metricName")) != null || !asMap(query.get("discriminator")).isEmpty();
    }

    private static String fetchFingerprint(Map<String, Object> query, String sourceId, String oldFetchId) {
        Map<String, Object> key = fields("sourceId", sourceId, "binding", oldFetchId,
                "sourceRevision", query.get("sourceRevision"), "targetRevision", query.get("targetRevision"),
                "targetId", "business-call".equals(query.get("observationType")) ? null : query.get("targetId"),
                "targetType", "business-call".equals(query.get("observationType")) ? null : query.get("targetType"),
                "tenant", query.get("tenant"), "sampling", query.get("sampling"), "window", query.get("window"),
                "querySha256", nullableText(query.get("query")) == null ? null : digest(query.get("query")),
                "callSha256", nullableText(query.get("call")) == null ? null : digest(query.get("call")),
                "parametersSha256", query.get("parameters") == null ? null : digest(query.get("parameters")));
        return digest(key);
    }

    private static String mappingRevision(Map<String, Object> query) {
        return digest(fields("metricId", query.get("metricId"), "unit", query.get("unit"), "kind", query.get("kind"),
                "column", query.get("column"), "valuePath", query.get("valuePath"), "mapping", query.get("mapping"),
                "profileRevision", query.get("profileRevision"), "metricName", query.get("metricName"),
                "discriminator", query.get("discriminator")));
    }

    private static Map<String, Object> legacySubject(Map<String, Object> query) {
        String scope = nullableText(query.get("scope"));
        if (scope == null) return null;
        if ("job".equals(scope)) return fields("scope", "job");
        if ("workload".equals(scope)) return fields("scope", "workload", "workloadId", query.get("workloadId"));
        if ("mix".equals(scope)) return fields("scope", "mix", "workloadId", query.get("workloadId"), "mixId", query.get("mixId"));
        return null;
    }

    private static Map<String, Object> subject(Map<String, Object> sidecar, Map<String, Object> query) {
        String scope = firstText(sidecar.get("scope"), query.get("scope"), asMap(asMap(sidecar.get("observation")).get("provenance")).get("scope"));
        if (scope == null || "null".equals(scope)) return null;
        String workloadId = firstText(sidecar.get("workloadId"), query.get("workloadId"));
        String mixId = firstText(sidecar.get("mixId"), query.get("mixId"));
        if ("job".equals(scope)) return fields("scope", "job");
        if ("workload".equals(scope) && workloadId != null) return fields("scope", "workload", "workloadId", workloadId);
        if ("mix".equals(scope) && workloadId != null && mixId != null)
            return fields("scope", "mix", "workloadId", workloadId, "mixId", mixId);
        return null;
    }

    private static String observationState(Map<String, Object> availability, Map<String, Object> sidecar) {
        String value = nullableText(availability.get("state"));
        if (value == null) value = nullableText(sidecar.get("queryStatus"));
        return switch (value == null ? "" : value.toUpperCase(Locale.ROOT)) {
            case "AVAILABLE", "READY" -> "AVAILABLE";
            case "PARTIAL", "STALE" -> "PARTIAL";
            case "NO_DATA" -> "NO_DATA";
            case "NOT_EVALUATED" -> "NOT_EVALUATED";
            default -> "UNAVAILABLE";
        };
    }

    private static String acquisitionState(Map<String, Object> availability, Map<String, Object> sidecar) {
        String state = observationState(availability, sidecar);
        if (Set.of("AVAILABLE", "PARTIAL", "NO_DATA").contains(state)) return "SUCCEEDED";
        List<Map<String, Object>> diagnostics = list(availability.get("diagnostics"));
        for (Map<String, Object> diagnostic : diagnostics) {
            String code = String.valueOf(diagnostic.get("code"));
            if (code.startsWith("BUSINESS_MAPPING_") || "BUSINESS_METRIC_VALUE_INVALID".equals(code))
                return "SUCCEEDED";
        }
        return "FAILED";
    }

    private static String freshness(Object value) {
        return Set.of("FRESH", "STALE").contains(String.valueOf(value)) ? String.valueOf(value) : "UNKNOWN";
    }

    private static List<Map<String, Object>> diagnostics(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : list(raw)) {
            String code = nullableText(item.get("code"));
            String message = nullableText(item.get("message"));
            if (code == null) continue;
            if (code.length() > 80 || !code.matches("[A-Z][A-Z0-9_]{0,79}")) code = "LEGACY_DIAGNOSTIC";
            if (message == null || message.length() > 240 || hasControl(message))
                message = "The legacy observation reported incomplete metric evidence.";
            result.add(fields("code", code, "message", message));
            if (result.size() == 64) break;
        }
        return result;
    }

    private static List<String> diagnosticCodes(Object raw) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> item : diagnostics(raw)) {
            result.add(String.valueOf(item.get("code")));
            if (result.size() == 32) break;
        }
        return result;
    }

    private static Map<String, Object> normalizeCoverage(Map<String, Object> raw, String startAt, String endAt) {
        long sampleCount = numberOr(raw.get("sampleCount"), 0L);
        long seriesCount = numberOr(raw.get("seriesCount"), 0L);
        long gapCount = numberOr(raw.get("gapCount"), 0L);
        return fields("sampleCount", sampleCount, "seriesCount", seriesCount, "gapCount", gapCount,
                "firstSampleAt", nullableText(raw.get("firstSampleAt")),
                "lastSampleAt", nullableText(raw.get("lastSampleAt")),
                "windowStart", firstText(raw.get("windowStart"), startAt),
                "windowEnd", firstText(raw.get("windowEnd"), endAt));
    }

    private static List<Map<String, Object>> samples(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) return result;
        for (Object value : rows) {
            Map<String, Object> point = asMap(value);
            String timestamp = nullableText(point.get("timestamp"));
            Object rawValue = point.get("value");
            if (timestamp == null || !isInstant(timestamp) || !(rawValue instanceof Number number)
                    || !Double.isFinite(number.doubleValue())) continue;
            result.add(fields("timestamp", timestamp, "value", number.doubleValue()));
            if (result.size() == 250000) break;
        }
        return result;
    }

    private static Map<String, String> safeLabels(Object raw) {
        Map<String, Object> labels = asMap(raw);
        if (labels.size() > MAX_LABELS) throw new IllegalArgumentException("The v1 observation has too many labels.");
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, Object> entry : labels.entrySet()) {
            String key = entry.getKey();
            if (key.length() > 64 || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || hasControl(key))
                throw new IllegalArgumentException("The v1 observation contains an invalid label name.");
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (value.length() > 512 || hasControl(value))
                throw new IllegalArgumentException("The v1 observation contains an invalid label value.");
            String normalizedKey = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            boolean sensitive = SENSITIVE_LABEL.stream().map(token -> token.replace("_", ""))
                    .anyMatch(normalizedKey::contains);
            result.put(key, sensitive ? "[REDACTED]" : value);
        }
        return result;
    }

    private static String normalizedConnectionKind(String raw) {
        return switch (raw == null ? "" : raw.toUpperCase(Locale.ROOT)) {
            case "DATA_SOURCE", "PROMETHEUS", "GRAFANA" -> "DATA_SOURCE";
            case "DBHELPER" -> "DBHELPER";
            case "PACKAGE_TOOL" -> "PACKAGE_TOOL";
            default -> "LEGACY";
        };
    }

    private static List<Map<String, Object>> list(Object raw) {
        if (!(raw instanceof List<?> rows)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) if (row instanceof Map<?, ?>) result.add(asMap(row));
        return result;
    }

    private static Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet())
            if (entry.getKey() instanceof String key) result.put(key, entry.getValue());
        return result;
    }

    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<>(values);
        Collections.sort(result);
        return result;
    }

    private static String text(Object value, String field) {
        String result = nullableText(value);
        if (result == null || result.length() > 512 || hasControl(result))
            throw new IllegalArgumentException("The v1 SUT Metrics " + field + " is missing or invalid.");
        return result;
    }

    private static String nullableText(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String text = nullableText(value);
            if (text != null) return text;
        }
        return null;
    }

    private static long numberOr(Object value, long fallback) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) return fallback;
        long normalized = number.longValue();
        return normalized < 0 ? fallback : normalized;
    }

    private static boolean isInstant(String value) {
        try { Instant.parse(value); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2)
            result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static String digest(Object value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch (Exception failure) { throw new IllegalStateException("Could not create a stable SUT Metrics contract digest.", failure); }
    }

    static String digestValue(Object value) { return digest(value); }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item));
        return value.toString();
    }
}
