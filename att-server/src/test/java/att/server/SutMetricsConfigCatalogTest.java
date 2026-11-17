package att.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SutMetricsConfigCatalogTest {
    @TempDir Path temp;

    @BeforeEach void installSchemas() throws Exception { TestSchemaInstaller.install(temp); }

    @Test void runtimeConfigIsAuthoritativeReloadableAndKeepsLastKnownGood() throws Exception {
        Path config = configPath();
        Path data = temp.resolve("data");
        Files.createDirectories(data);
        Path legacyCatalog = data.resolve("sut-metrics-catalog.json");
        byte[] legacySentinel = "legacy-catalog-must-not-be-read-or-written".getBytes(StandardCharsets.UTF_8);
        Files.write(legacyCatalog, legacySentinel);
        writeConfig(config, "Prometheus");

        String firstSourceRevision;
        String firstConnectionRevision;
        try (SutMetricsService service = service(data, config)) {
            Map<String, Object> first = source(service);
            firstSourceRevision = String.valueOf(first.get("revision"));
            assertTrue(firstSourceRevision.matches("[a-f0-9]{64}"));
            Map<String, Object> metricSource = metricSource(service);
            Map<?, ?> connection = (Map<?, ?>) metricSource.get("connection");
            firstConnectionRevision = String.valueOf(connection.get("revision"));
            assertEquals(firstSourceRevision, firstConnectionRevision);
            assertEquals("ACTIVE", configuration(service).get("state"));
            assertThrows(SutMetricsService.ReadOnlyCatalogException.class,
                    () -> service.upsertSource(Map.of("id", "other", "name", "Other", "url", "http://localhost:9091")));

            writeConfig(config, "Prometheus Renamed");
            assertEquals("Prometheus Renamed", source(service).get("name"), "A valid Runtime Config edit must hot reload without restart.");
            String updatedRevision = String.valueOf(source(service).get("revision"));
            assertNotEquals(firstSourceRevision, updatedRevision);
            assertNotEquals(firstConnectionRevision, ((Map<?, ?>) metricSource(service).get("connection")).get("revision"));

            Files.writeString(config, configText("Prometheus Broken").replace("name: Prometheus Broken", "name: Prometheus Broken\n      password: secret"));
            assertEquals("Prometheus Renamed", source(service).get("name"), "An invalid candidate must not replace last-known-good definitions.");
            Map<String, Object> rejected = configuration(service);
            assertEquals("LAST_KNOWN_GOOD", rejected.get("state"));
            assertEquals("SUT_METRICS_CONFIG_REJECTED", rejected.get("diagnosticCode"));
            assertTrue(String.valueOf(rejected.get("field")).contains("sutMetrics.dataSources[0]"), rejected.toString());
            assertFalse(rejected.toString().contains("secret"));
            assertFalse(rejected.toString().contains(config.toString()));

            writeConfig(config, "Prometheus Recovered");
            assertEquals("Prometheus Recovered", source(service).get("name"));
            assertEquals("ACTIVE", configuration(service).get("state"));
        }

        try (SutMetricsService restarted = service(data, config)) {
            assertEquals(String.valueOf(source(restarted).get("revision")),
                    String.valueOf(((Map<?, ?>) metricSource(restarted).get("connection")).get("revision")));
            assertNotEquals(firstSourceRevision, source(restarted).get("revision"));
        }
        assertArrayEquals(legacySentinel, Files.readAllBytes(legacyCatalog),
                "Production config-backed mode must neither consult nor overwrite the former JSON catalog.");
    }

    @Test void documentedV214RuntimeConfigExampleLoadsAsACompleteCatalog() throws Exception {
        Path config = configPath();
        Files.copy(Paths.get("examples/config/sut-metrics-v2.14.yaml"), config,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (SutMetricsService service = service(temp.resolve("example-data"), config)) {
            assertEquals("prometheus-prod", source(service).get("id"));
            assertEquals("host-cpu", metricSource(service).get("id"));
            assertEquals("ACTIVE", configuration(service).get("state"));
            assertEquals("host-observability", ((Map<?, ?>) ((List<?>) service.listProfiles("admin", true).get("items")).get(0)).get("id"));
            assertEquals("host-runtime", ((Map<?, ?>) ((List<?>) service.listGroups("admin", true).get("items")).get(0)).get("id"));
        }
    }

    @Test void dbHelperMetricSourcesAreCheckedAgainstEveryAllowedEnvironment() throws Exception {
        Path config = configPath();
        Path helper = temp.resolve("config/dbhelpers/orders.yaml");
        Files.createDirectories(helper.getParent());
        Files.writeString(helper, "schemaVersion: att-dbhelper/v2.6\n"
                + "id: orders\nname: Orders\ndescription: Orders database\n"
                + "connection: {url: jdbc:test}\n");
        Files.writeString(config, dbHelperConfig("SIT"));

        try (SutMetricsService service = service(temp.resolve("dbhelper-data"), config)) {
            assertEquals("ACTIVE", configuration(service).get("state"));

            Files.writeString(config, dbHelperConfig("UAT"));
            Map<String, Object> rejected = configuration(service);
            assertEquals("LAST_KNOWN_GOOD", rejected.get("state"),
                    "A DBHelper absent from the Profile's allowed Environment must reject the candidate without losing the active catalog.");
            assertEquals("config.sutMetrics.metricSources[].connection.id", rejected.get("field"));
        }
    }

    @Test void forbiddenHostsAndUnavailableSecretsRejectCandidatesWithoutReplacingLastKnownGood() throws Exception {
        Path config = configPath();
        Path data = temp.resolve("validation-data");
        writeConfig(config, "Prometheus");

        try (SutMetricsService service = service(data, config)) {
            String forbiddenHost = configText("Prometheus")
                    .replace("url: http://localhost:9090", "url: https://forbidden.example.invalid");
            Files.writeString(config, forbiddenHost);
            assertEquals("Prometheus", source(service).get("name"));
            Map<String, Object> rejectedHost = configuration(service);
            assertEquals("LAST_KNOWN_GOOD", rejectedHost.get("state"));
            assertEquals("config.sutMetrics.dataSources[].url", rejectedHost.get("field"));

            String missingSecret = configText("Prometheus")
                    .replace("url: http://localhost:9090\n", "url: http://localhost:9090\n      auth: {mode: BEARER, secretRef: MISSING}\n");
            Files.writeString(config, missingSecret);
            assertEquals("Prometheus", source(service).get("name"));
            Map<String, Object> rejectedSecret = configuration(service);
            assertEquals("LAST_KNOWN_GOOD", rejectedSecret.get("state"));
            assertEquals("config.sutMetrics.dataSources[].auth.secretRef", rejectedSecret.get("field"));
            assertFalse(rejectedSecret.toString().contains("MISSING"));
        }
    }

    @Test void rejectedCandidateDoesNotMaskAValidRevisionPublishedDuringValidation() throws Exception {
        Path config = configPath();
        Path data = temp.resolve("concurrent-validation-data");
        writeConfig(config, "Prometheus");
        AtomicBoolean publishValidRevisionDuringSecretResolution = new AtomicBoolean();
        java.util.function.Function<String, String> resolver = reference -> {
            if ("MISSING".equals(reference) && publishValidRevisionDuringSecretResolution.compareAndSet(true, false)) {
                try { Files.writeString(config, configText("Prometheus Newer")); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                return null;
            }
            return "test-secret";
        };

        try (SutMetricsService service = new SutMetricsService(data,
                Set.of("prometheus.example.invalid"), resolver, ignored -> { }, ignored -> { }, config, temp)) {
            String invalidCandidate = configText("Prometheus Invalid")
                    .replace("readers: ['*']", "readers: ['*']\n      auth: {mode: BEARER, secretRef: MISSING}");
            Files.writeString(config, invalidCandidate);
            publishValidRevisionDuringSecretResolution.set(true);

            assertEquals("Prometheus Newer", source(service).get("name"),
                    "A valid revision published while the invalid candidate is being validated must be loaded immediately.");
            assertEquals("Prometheus Newer", source(service).get("name"),
                    "A newer valid revision written during validation must be reconsidered immediately.");
            assertEquals("ACTIVE", configuration(service).get("state"));
        }
    }

    @Test void slowerValidRevisionCannotReplaceNewerRevisionOrRestoreRevokedReader() throws Exception {
        Path config = configPath();
        writeConfig(config, "Prometheus Base");
        AtomicBoolean pauseNextActivation = new AtomicBoolean();
        AtomicReference<String> pausedRevision = new AtomicReference<>();
        java.util.concurrent.CountDownLatch candidateReady = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseCandidate = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Consumer<String> activationBarrier = revision -> {
            if (!pauseNextActivation.compareAndSet(true, false)) return;
            pausedRevision.set(revision);
            candidateReady.countDown();
            try {
                if (!releaseCandidate.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Timed out waiting to release the stale Runtime Config candidate.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pausing the Runtime Config candidate.", interrupted);
            }
        };

        try (SutMetricsService service = new SutMetricsService(temp.resolve("revision-fence-data"),
                Set.of("prometheus.example.invalid"), ref -> "test-secret", ignored -> { }, ignored -> { },
                config, temp, activationBarrier)) {
            String candidateA = configText("Prometheus Candidate A")
                    .replace("readers: ['*']", "readers: ['alice', 'bob']");
            String candidateB = configText("Prometheus Candidate B")
                    .replace("readers: ['*']", "readers: ['alice']");
            Files.writeString(config, candidateA);
            pauseNextActivation.set(true);

            java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Future<Map<String, Object>> slowerRead = callers.submit(() -> service.listSources("admin", true));
                assertTrue(candidateReady.await(5, java.util.concurrent.TimeUnit.SECONDS),
                        "The first valid revision must pause after validation and before activation.");
                assertEquals(sha256(candidateA.getBytes(StandardCharsets.UTF_8)), pausedRevision.get());

                Files.writeString(config, candidateB);
                java.util.concurrent.Future<Map<String, Object>> newerRead = callers.submit(() -> service.listSources("alice", false));
                Map<String, Object> newerResponse = newerRead.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("Prometheus Candidate B", firstSourceName(newerResponse));

                releaseCandidate.countDown();
                Map<String, Object> slowerResponse = slowerRead.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("Prometheus Candidate B", firstSourceName(slowerResponse),
                        "The stale successful candidate must be discarded after a newer revision activates.");
                assertEquals("Prometheus Candidate B", source(service).get("name"));
                assertEquals(sha256(candidateB.getBytes(StandardCharsets.UTF_8)), configuration(service).get("revision"));
                assertEquals(1, ((List<?>) service.listSources("alice", false).get("items")).size());
                assertTrue(((List<?>) service.listSources("bob", false).get("items")).isEmpty(),
                        "A slower earlier revision must not restore a reader revoked by the newer Runtime Config.");
            } finally {
                releaseCandidate.countDown();
                callers.shutdownNow();
            }
        }
    }

    private SutMetricsService service(Path data, Path config) throws Exception {
        return new SutMetricsService(data, Set.of("prometheus.example.invalid"),
                ref -> "MISSING".equals(ref) ? null : "test-secret", ignored -> { }, ignored -> { }, config, temp);
    }

    private Path configPath() throws Exception {
        Path directory = temp.resolve("config");
        Files.createDirectories(directory);
        return directory.resolve("config.yaml");
    }

    private void writeConfig(Path path, String sourceName) throws Exception {
        Files.writeString(path, configText(sourceName));
    }

    private String configText(String sourceName) {
        return "schemaVersion: att-config/v2.14\n"
                + "sutMetrics:\n"
                + "  dataSources:\n"
                + "    - schemaVersion: att-sut-metrics-source/v1\n"
                + "      id: prom\n"
                + "      name: " + sourceName + "\n"
                + "      url: http://localhost:9090\n"
                + "      readers: ['*']\n"
                + "  metricSources:\n"
                + "    - schemaVersion: att-sut-metrics-metric-source/v1\n"
                + "      id: host-metrics\n"
                + "      name: Host metrics\n"
                + "      connection: {kind: DATA_SOURCE, id: prom}\n"
                + "      fetch: {id: host, mode: prometheus-range, query: up, parameters: {}, sampling: range, step: 15s}\n"
                + "      mapping:\n"
                + "        labels: {retain: [instance]}\n"
                + "        outputs: [{id: service-up, title: Service up, unit: count, kind: gauge, metricName: up}]\n"
                + "      limits: {maxRows: 128, maxSeries: 32, maxSamples: 1000, maxArtifactBytes: 1048576}\n"
                + "  groups:\n"
                + "    - {schemaVersion: att-sut-metrics-group/v1, id: runtime, title: Runtime, order: 1, match: {metricIds: [service-up]}, groupBy: [instance], display: chart}\n"
                + "  profiles: []\n";
    }

    private static String firstSourceName(Map<String, Object> response) {
        Map<?, ?> first = (Map<?, ?>) ((List<?>) response.get("items")).get(0);
        return String.valueOf(first.get("name"));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String dbHelperConfig(String allowedEnvironment) {
        return "schemaVersion: att-config/v2.14\n"
                + "environment: SIT\n"
                + "environments:\n"
                + "  SIT: {dbhelpers: [config/dbhelpers/orders.yaml]}\n"
                + "  UAT: {dbhelpers: []}\n"
                + "sutMetrics:\n"
                + "  dataSources: []\n"
                + "  metricSources:\n"
                + "    - schemaVersion: att-sut-metrics-metric-source/v1\n"
                + "      id: orders-metrics\n"
                + "      name: Orders metrics\n"
                + "      connection: {kind: DBHELPER, id: orders}\n"
                + "      fetch: {id: orders-query, mode: dbhelper-query, call: \"#{db.orders.query(sql='select instance, sessions from metric_summary')}\", parameters: {}, sampling: afterDrain}\n"
                + "      mapping:\n"
                + "        labels: {instance: INSTANCE}\n"
                + "        outputs: [{id: sessions, title: Sessions, unit: count, kind: gauge, column: SESSIONS}]\n"
                + "      limits: {maxRows: 64, maxSeries: 16, maxSamples: 1, maxArtifactBytes: 1048576}\n"
                + "  groups: []\n"
                + "  profiles:\n"
                + "    - schemaVersion: att-sut-metrics-profile/v2\n"
                + "      id: orders-profile\n"
                + "      name: Orders profile\n"
                + "      metricSourceIds: [orders-metrics]\n"
                + "      groupIds: []\n"
                + "      allowedBindings: {environments: [" + allowedEnvironment + "], targets: [], tenants: [], parameters: {}}\n";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> source(SutMetricsService service) {
        return (Map<String, Object>) ((List<?>) service.listSources("admin", true).get("items")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metricSource(SutMetricsService service) {
        return (Map<String, Object>) ((List<?>) service.listMetricSources("admin", true).get("items")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configuration(SutMetricsService service) {
        return (Map<String, Object>) service.listSources("admin", true).get("configuration");
    }
}
