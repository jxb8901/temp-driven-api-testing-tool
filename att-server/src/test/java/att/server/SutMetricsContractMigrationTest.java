package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SutMetricsContractMigrationTest {
    private static final String JOB = "J0000000000000001";
    private static final String REVISION_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String REVISION_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    @TempDir Path temp;

    @Test void publishedContractExamplesValidateAndRejectUnsafeShapes() throws Exception {
        Map<String, String> examples = Map.of(
                "metric-source-db-wide-row.json", "att-sut-metrics-metric-source-v1.schema.json",
                "metric-source-prometheus-multi-series.json", "att-sut-metrics-metric-source-v1.schema.json",
                "group-runtime.json", "att-sut-metrics-group-v1.schema.json",
                "profile-flat.json", "att-sut-metrics-profile-v2.schema.json",
                "observation-sampled-series.json", "att-sut-metrics-observation-v2.schema.json");
        for (Map.Entry<String, String> entry : examples.entrySet()) {
            String source = Files.readString(Path.of("examples/load/sut-metrics/v2", entry.getKey()));
            att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas", entry.getValue()), source);
        }

        Map<String, Object> metricSource = readMap(Path.of("examples/load/sut-metrics/v2/metric-source-db-wide-row.json"));
        Map<String, Object> mapping = map(metricSource.get("mapping"));
        assertTrue(mapping.get("labels") instanceof Map<?, ?>,
                "SQL labels are projected from explicit columns without a separate allowedLabels list.");
        assertFalse(mapping.containsKey("allowedLabels"));
        mapping.put("longRows", true);
        assertInvalid("att-sut-metrics-metric-source-v1.schema.json", metricSource);
        mapping.remove("longRows");
        mapping.put("metricIdLabel", "metric_name");
        assertInvalid("att-sut-metrics-metric-source-v1.schema.json", metricSource);
        mapping.remove("metricIdLabel");
        mapping.put("transform", "value * 100");
        assertInvalid("att-sut-metrics-metric-source-v1.schema.json", metricSource);

        Map<String, Object> prometheusSource = readMap(Path.of("examples/load/sut-metrics/v2/metric-source-prometheus-multi-series.json"));
        Map<String, Object> prometheusMapping = map(prometheusSource.get("mapping"));
        @SuppressWarnings("unchecked") List<String> retained = (List<String>) map(prometheusMapping.get("labels")).get("retain");
        retained.add("__name__");
        assertInvalid("att-sut-metrics-metric-source-v1.schema.json", prometheusSource);

        Map<String, Object> observation = readMap(Path.of("examples/load/sut-metrics/v2/observation-sampled-series.json"));
        map(observation.get("identity")).put("groupId", "runtime");
        assertInvalid("att-sut-metrics-observation-v2.schema.json", observation);

        for (String version : List.of("att-sut-metrics-metric-source/v1", "att-sut-metrics-group/v1",
                "att-sut-metrics-profile/v2", "att-sut-metrics-snapshot/v2",
                "att-sut-metrics-execution-payload/v2", "att-sut-metrics-observation/v2"))
            assertTrue(Files.isRegularFile(att.validation.SchemaFiles.resolveVersion(Path.of("."), version)), version);
        att.validation.SchemaFiles.validateCatalog(Path.of("."));
    }

    @Test void legacyMixedSnapshotMigratesDeterministicallyAndStoresExecutablePlanPrivately() throws Exception {
        Map<String, Object> legacy = legacyMixedSnapshot();
        Map<String, Object> upgraded = SutMetricsContractMigration.upgradeSnapshot(legacy);
        Map<String, Object> repeated = SutMetricsContractMigration.upgradeSnapshot(legacyMixedSnapshot());
        assertEquals(ServerRuntime.JSON.writeValueAsString(upgraded), ServerRuntime.JSON.writeValueAsString(repeated));
        assertEquals(SutMetricsContractMigration.SNAPSHOT_V2, upgraded.get("schemaVersion"));
        assertEquals(2, list(upgraded.get("metricSources")).size());
        List<Map<String, Object>> fetchPlan = list(upgraded.get("fetchPlan"));
        assertEquals(3, fetchPlan.size(), "The shared #359 result query remains one fetch across its two outputs.");
        Map<String, Object> shared = fetchPlan.stream().filter(row -> list(row.get("outputs")).size() == 2).findFirst().orElseThrow();
        assertEquals(2, list(shared.get("outputs")).size());
        assertEquals(Set.of("success", "failure"), Set.of(
                map(list(shared.get("outputs")).get(0)).get("metricId"),
                map(list(shared.get("outputs")).get(1)).get("metricId")));

        Map<String, Object> payload = SutMetricsContractMigration.privateExecutionPayload(upgraded, JOB);
        String payloadJson = ServerRuntime.JSON.writeValueAsString(payload);
        att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/att-sut-metrics-execution-payload-v2.schema.json"), payloadJson);
        assertTrue(payloadJson.contains("node_cpu_seconds_total"));
        assertTrue(payloadJson.contains("select workload_id, mix_id, success, failure from transaction_summary"));
        assertTrue(payloadJson.contains("PRIVATE_SENTINEL"));

        Map<String, Object> stored = SutMetricsContractMigration.redactSnapshotForStorage(upgraded);
        String payloadDigest = sha256(ServerRuntime.JSON.writeValueAsBytes(payload));
        stored.put("executionPayloadSha256", payloadDigest);
        stored.remove("snapshotId");
        stored.put("snapshotId", SutMetricsContractMigration.digestValue(stored));
        String storedJson = ServerRuntime.JSON.writeValueAsString(stored);
        assertFalse(storedJson.contains("node_cpu_seconds_total"));
        assertFalse(storedJson.contains("select workload_id"));
        assertFalse(storedJson.contains("PRIVATE_SENTINEL"));
        att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/att-sut-metrics-snapshot-v2.schema.json"), storedJson);

        Path serviceRoot = temp.resolve("snapshot-write");
        try (SutMetricsService service = new SutMetricsService(serviceRoot)) {
            service.writeSnapshot(JOB, upgraded);
            Map<String, Object> readable = service.snapshotForJob(JOB);
            assertFalse(readable.toString().contains("node_cpu_seconds_total"));
            assertFalse(readable.toString().contains("PRIVATE_SENTINEL"));
            Map<String, Object> replay = service.snapshotForExecution(JOB);
            assertTrue(replay.toString().contains("node_cpu_seconds_total"));
            assertTrue(replay.toString().contains("PRIVATE_SENTINEL"));
            Path directory = serviceRoot.resolve("jobs").resolve(JOB).resolve("sut-metrics");
            assertTrue(Files.isRegularFile(directory.resolve("execution-payload.json")));
            assertFalse(Files.exists(directory.resolve("business-calls.json")));
            String publicFile = Files.readString(directory.resolve("snapshot.json"));
            assertFalse(publicFile.contains("node_cpu_seconds_total"));
            assertFalse(publicFile.contains("PRIVATE_SENTINEL"));
            try {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(directory.resolve("execution-payload.json"))));
            } catch (UnsupportedOperationException ignored) { }
        }
        try (SutMetricsService restarted = new SutMetricsService(serviceRoot)) {
            assertTrue(restarted.snapshotForExecution(JOB).toString().contains("PRIVATE_SENTINEL"));
        }
    }

    @Test void v1ObservationNormalizesToStableGoldenAndRetainsDistinctTargets() throws Exception {
        Map<String, Object> migratedSnapshot = SutMetricsContractMigration.upgradeSnapshot(legacyMixedSnapshot());
        Map<String, Object> firstSidecar = legacyObservation("node-1", "prom/base/node-1/cpu");
        List<Map<String, Object>> first = SutMetricsContractMigration.normalizeObservationV1(firstSidecar, migratedSnapshot, JOB);
        assertEquals(1, first.size());
        Map<String, Object> normalized = first.get(0);
        att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/att-sut-metrics-observation-v2.schema.json"),
                ServerRuntime.JSON.writeValueAsString(normalized));

        Map<String, Object> identity = map(normalized.get("identity"));
        assertEquals(SutMetricsContractMigration.digestValue(identity), normalized.get("observationId"));
        assertFalse(identity.containsKey("groupId"));
        Map<String, String> placeholders = Map.of(
                "$OBSERVATION_ID", String.valueOf(normalized.get("observationId")),
                "$METRIC_SOURCE_ID", String.valueOf(map(identity.get("binding")).get("sourceId")),
                "$FETCH_ID", String.valueOf(map(identity.get("binding")).get("fetchId")),
                "$FETCH_REVISION", String.valueOf(map(map(normalized.get("acquisition")).get("fetch")).get("revision")),
                "$INVOCATION_ID", String.valueOf(map(normalized.get("acquisition")).get("invocationId")),
                "$SNAPSHOT_ID", String.valueOf(map(map(normalized.get("acquisition")).get("job")).get("snapshotId")));
        String expected = Files.readString(Path.of("att-server/src/test/resources/att/server/sut-metrics-v2/normalized-observation.golden.json"));
        for (Map.Entry<String, String> placeholder : placeholders.entrySet())
            expected = expected.replace(placeholder.getKey(), placeholder.getValue());
        JsonNode golden = ServerRuntime.JSON.readTree(expected);
        assertEquals(golden, ServerRuntime.JSON.readTree(ServerRuntime.JSON.writeValueAsString(normalized)));

        Map<String, Object> otherTarget = SutMetricsContractMigration.normalizeObservationV1(
                legacyObservation("node-2", "prom/base/node-2/cpu"), migratedSnapshot, JOB).get(0);
        assertNotEquals(normalized.get("observationId"), otherTarget.get("observationId"));
        assertNotEquals(map(identity.get("target")).get("id"), map(map(otherTarget.get("identity")).get("target")).get("id"));

        List<Map<String, Object>> scalar = SutMetricsContractMigration.normalizeObservationV1(
                legacyBusinessObservation(), migratedSnapshot, JOB);
        assertEquals(1, scalar.size(), "A single-value business result becomes one labelled observation with an empty label set.");
        assertEquals(15.0, map(scalar.get(0).get("data")).get("value"));
        assertTrue(map(scalar.get(0).get("identity")).get("labels") instanceof Map<?, ?> labels && labels.isEmpty());
        att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/att-sut-metrics-observation-v2.schema.json"),
                ServerRuntime.JSON.writeValueAsString(scalar.get(0)));

        Map<String, Object> duplicates = legacyObservation("node-1", "prom/base/node-1/cpu");
        duplicates.put("series", new ArrayList<>(list(duplicates.get("series"))));
        List<Map<String, Object>> series = list(duplicates.get("series"));
        series.add(new LinkedHashMap<>(series.get(0)));
        assertThrows(IllegalArgumentException.class,
                () -> SutMetricsContractMigration.normalizeObservationV1(duplicates, migratedSnapshot, JOB));
    }

    private static void assertInvalid(String schema, Object value) throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas", schema), ServerRuntime.JSON.writeValueAsString(value)));
    }

    private static Map<String, Object> legacyMixedSnapshot() {
        List<Map<String, Object>> queries = new ArrayList<>();
        queries.add(prometheusQuery("node-1"));
        queries.add(prometheusQuery("node-2"));
        queries.add(businessQuery("success", "SUCCESS", "count"));
        queries.add(businessQuery("failure", "FAILURE", "count"));
        return fields("schemaVersion", "att-sut-metrics-snapshot/v1", "snapshotId", REVISION_B,
                "createdAt", "2026-11-16T12:00:00Z", "queries", queries,
                "sources", fields("prom", fields("id", "prom", "name", "Prometheus", "provider", "PROMETHEUS",
                                "url", "https://metrics.example.test", "auth", fields("secretRef", "PRIVATE_SENTINEL")),
                        "dbhelper:orders", fields("id", "orders", "name", "Orders DBHelper", "provider", "DBHELPER",
                                "connectionPassword", "PRIVATE_SENTINEL")),
                "provenance", List.of(fields("profile", "base", "parameters", fields("credential", "PRIVATE_SENTINEL"))),
                "collection", Map.of(), "livePreview", Map.of(), "thresholds", List.of());
    }

    private static Map<String, Object> prometheusQuery(String target) {
        return fields("id", "prom/base/" + target + "/cpu", "bindingId", "primary", "profile", "base",
                "profilePath", "base", "profileRevision", REVISION_B, "sourceId", "prom", "sourceName", "Prometheus",
                "sourceRevision", REVISION_A, "targetId", target, "targetType", "instance", "targetRevision", REVISION_B,
                "metricId", "cpu", "title", "CPU", "category", "app", "unit", "percent", "kind", "gauge",
                "query", "node_cpu_seconds_total{job=\"payments\"}", "sampling", "range",
                "parameters", fields("service", "payments"), "scope", "job");
    }

    private static Map<String, Object> businessQuery(String metric, String column, String unit) {
        return fields("id", "business/base/job/" + metric, "bindingId", "base", "profile", "base", "profilePath", "base",
                "profileRevision", REVISION_B, "sourceId", "dbhelper:orders", "sourceName", "Orders DBHelper",
                "sourceType", "DBHELPER", "sourceRevision", REVISION_A, "targetId", "job", "targetType", "job",
                "targetRevision", REVISION_B, "metricId", metric, "title", metric, "category", "db", "unit", unit,
                "kind", "counter", "observationType", "business-call", "observationKind", "timed-snapshot",
                "sampling", "afterDrain", "call", "#{db.orders.query(sql='select workload_id, mix_id, success, failure from transaction_summary')}",
                "callGroupId", "shared-query", "callType", "DBHELPER", "callName", "orders", "multiOutput", true,
                "column", column, "mapping", fields("rows", "rows", "workloadColumn", "WORKLOAD_ID", "mixColumn", "MIX_ID"),
                "scope", "job");
    }

    private static Map<String, Object> legacyObservation(String target, String queryId) {
        Map<String, Object> coverage = fields("sampleCount", 2, "seriesCount", 1, "gapCount", 0,
                "firstSampleAt", "2026-11-16T12:00:00Z", "lastSampleAt", "2026-11-16T12:01:00Z",
                "windowStart", "2026-11-16T12:00:00Z", "windowEnd", "2026-11-16T12:01:00Z");
        return fields("schemaVersion", "att-sut-metrics-observation/v1", "queryId", queryId,
                "profile", "base", "profilePath", "base", "sourceId", "prom", "sourceName", "Prometheus",
                "metricId", "cpu", "title", "CPU", "category", "app", "kind", "gauge",
                "targetId", target, "targetType", "instance", "unit", "percent", "requestCount", 1,
                "series", List.of(fields("labels", fields("instance", target, "role", "blue"),
                        "samples", List.of(fields("timestamp", "2026-11-16T12:00:00Z", "timestampEpochMs", 1794830400000L, "value", 42.0),
                                fields("timestamp", "2026-11-16T12:01:00Z", "timestampEpochMs", 1794830460000L, "value", 43.0)),
                        "coverage", coverage)),
                "observation", fields("schemaVersion", "att-sut-metrics-observation/v1", "kind", "sampled-time-series",
                        "effectiveBindingId", queryId,
                        "source", fields("id", "prom", "name", "Prometheus", "revision", REVISION_A),
                        "profile", fields("id", "base", "path", "base", "revision", REVISION_B),
                        "target", fields("id", target, "type", "instance", "revision", REVISION_B),
                        "metric", fields("id", "cpu", "name", "CPU", "category", "app", "unit", "percent", "kind", "gauge"),
                        "window", fields("queryStart", "2026-11-16T12:00:00Z", "queryEnd", "2026-11-16T12:01:00Z",
                                "step", "15s", "executionFinishedAt", "2026-11-16T12:01:00Z"),
                        "availability", fields("state", "AVAILABLE", "freshness", "FRESH", "coverage", coverage,
                                "collectionDurationMs", 10, "diagnostics", List.of()),
                        "provenance", fields("provider", "prometheus")));
    }

    private static Map<String, Object> legacyBusinessObservation() {
        String queryId = "business/base/job/success";
        return fields("schemaVersion", "att-sut-metrics-observation/v1", "queryId", queryId,
                "profile", "base", "profilePath", "base", "sourceId", "dbhelper:orders", "sourceName", "Orders DBHelper",
                "metricId", "success", "title", "success", "category", "db", "kind", "counter",
                "targetId", "job", "targetType", "job", "unit", "count", "queryStatus", "READY", "value", 15.0,
                "scope", "job", "callCount", 1, "mappingStatus", "MAPPED", "unmatchedRowCount", 0,
                "observation", fields("schemaVersion", "att-sut-metrics-observation/v1", "kind", "timed-snapshot",
                        "effectiveBindingId", queryId,
                        "source", fields("id", "dbhelper:orders", "name", "Orders DBHelper", "revision", REVISION_A),
                        "profile", fields("id", "base", "path", "base", "revision", REVISION_B),
                        "target", fields("id", "job", "type", "job", "revision", REVISION_B),
                        "metric", fields("id", "success", "name", "success", "category", "db", "unit", "count", "kind", "counter"),
                        "window", fields("observedAt", "2026-11-16T12:01:00Z", "completedAt", "2026-11-16T12:01:00Z"),
                        "availability", fields("state", "AVAILABLE", "freshness", "FRESH", "callCount", 1,
                                "collectionDurationMs", 12, "coverage", fields("sampleCount", 1, "seriesCount", 1, "gapCount", 0,
                                        "firstSampleAt", "2026-11-16T12:01:00Z", "lastSampleAt", "2026-11-16T12:01:00Z",
                                        "windowStart", "2026-11-16T12:01:00Z", "windowEnd", "2026-11-16T12:01:00Z"), "diagnostics", List.of()),
                        "provenance", fields("sourceType", "DBHELPER", "provider", "att-call", "scope", "job")));
    }

    private static Map<String, Object> readMap(Path path) throws Exception {
        return ServerRuntime.JSON.readValue(Files.readString(path), Map.class);
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }
    private static String sha256(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }
}
