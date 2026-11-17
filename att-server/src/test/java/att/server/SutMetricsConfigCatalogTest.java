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
            assertEquals("host-runtime", ((Map<?, ?>) ((List<?>) service.listGroups().get("items")).get(0)).get("id"));
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
