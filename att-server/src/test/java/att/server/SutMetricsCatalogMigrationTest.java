package att.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SutMetricsCatalogMigrationTest {
    @TempDir Path temp;

    @BeforeEach void installSchemas() throws Exception { TestSchemaInstaller.install(temp); }

    @Test void dryRunPreservesInputsAndWritesValidatedCandidateForAllDefinitionKinds() throws Exception {
        Path config = writeConfig("schemaVersion: att-config/v2.13\nenvironment: prod\n");
        Path catalog = writeCatalog(legacyCatalog());
        Path candidate = temp.resolve("config/config.candidate.yaml");
        byte[] originalConfig = Files.readAllBytes(config);
        byte[] originalCatalog = Files.readAllBytes(catalog);

        SutMetricsCatalogMigration.Result result = SutMetricsCatalogMigration.migrate(config, catalog, candidate, false);

        assertEquals("candidate-written", result.state());
        String text = Files.readString(candidate);
        assertTrue(text.contains("schemaVersion: att-config/v2.14"));
        assertTrue(text.contains("dataSources:"));
        assertTrue(text.contains("metricSources:"));
        assertTrue(text.contains("groups:"));
        assertTrue(text.contains("profiles:"));
        assertFalse(text.contains("createdAt:"));
        assertFalse(text.contains("default: null"), "An absent legacy parameter default must be omitted in the strict v2.14 config.");
        assertTrue(text.contains("window: null"), "The strict v2.14 schema requires null windows on normalized afterDrain multi-output metrics.");
        assertFalse(text.contains("revision: bbbbbbbbb"));
        assertFalse(text.contains("password:"));
        assertArrayEquals(originalConfig, Files.readAllBytes(config));
        assertArrayEquals(originalCatalog, Files.readAllBytes(catalog));
        assertFalse(Files.exists(config.resolveSibling(config.getFileName() + ".pre-sut-metrics-" + result.configRevision() + ".yaml")));

        byte[] firstCandidate = Files.readAllBytes(candidate);
        SutMetricsCatalogMigration.Result repeated = SutMetricsCatalogMigration.migrate(config, catalog, candidate, false);
        assertEquals("candidate-written", repeated.state());
        assertArrayEquals(firstCandidate, Files.readAllBytes(candidate));
    }

    @Test void applyMakesVerifiedBackupsAndAnIdenticalSecondRunIsANoOp() throws Exception {
        Path config = writeConfig("schemaVersion: att-config/v2.13\nenvironment: prod\n");
        Path catalog = writeCatalog(legacyCatalog());
        byte[] originalConfig = Files.readAllBytes(config);
        byte[] originalCatalog = Files.readAllBytes(catalog);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> originalPermissions = null;
        try { originalPermissions = Files.getPosixFilePermissions(config); }
        catch (UnsupportedOperationException ignored) { }

        SutMetricsCatalogMigration.Result applied = SutMetricsCatalogMigration.migrate(config, catalog, null, true);

        assertEquals("applied", applied.state());
        assertArrayEquals(originalConfig, Files.readAllBytes(applied.configBackup()));
        assertArrayEquals(originalCatalog, Files.readAllBytes(applied.catalogBackup()));
        assertArrayEquals(originalCatalog, Files.readAllBytes(catalog), "The legacy catalog remains intact for explicit rollback.");
        if (originalPermissions != null)
            assertEquals(originalPermissions, Files.getPosixFilePermissions(config), "Applying migration preserves Runtime Config read permissions.");
        assertTrue(Files.readString(config).contains("schemaVersion: att-config/v2.14"));

        byte[] migratedConfig = Files.readAllBytes(config);
        SutMetricsCatalogMigration.Result repeated = SutMetricsCatalogMigration.migrate(config, catalog, null, true);
        assertEquals("already-migrated", repeated.state());
        assertArrayEquals(migratedConfig, Files.readAllBytes(config));
    }

    @Test void duplicateIdsAndConflictingRuntimeDefinitionsFailWithoutWriting() throws Exception {
        Path config = writeConfig("schemaVersion: att-config/v2.13\nenvironment: SIT\n");
        Path duplicate = writeCatalog(legacyCatalog().replace("\"sources\":[", "\"sources\":[" + sourceJson() + ","));
        Path candidate = temp.resolve("config/duplicate-candidate.yaml");
        assertThrows(IllegalArgumentException.class, () -> SutMetricsCatalogMigration.migrate(config, duplicate, candidate, false));
        assertFalse(Files.exists(candidate));

        Files.writeString(config, "schemaVersion: att-config/v2.14\nsutMetrics: {dataSources: [], metricSources: [], groups: [], profiles: []}\n");
        Path validCatalog = writeCatalog(legacyCatalog());
        assertThrows(IllegalStateException.class, () -> SutMetricsCatalogMigration.migrate(config, validCatalog, candidate, false));
        assertFalse(Files.exists(candidate));
    }

    private Path writeConfig(String value) throws Exception {
        Path directory = temp.resolve("config");
        Files.createDirectories(directory);
        Path path = directory.resolve("config.yaml");
        Files.writeString(path, value);
        return path;
    }

    private Path writeCatalog(String value) throws Exception {
        Path directory = temp.resolve("data");
        Files.createDirectories(directory);
        Path path = directory.resolve("sut-metrics-catalog.json");
        Files.writeString(path, value);
        return path;
    }

    private static String sourceJson() {
        return "{\"schemaVersion\":\"att-sut-metrics-source/v1\",\"id\":\"prom\",\"name\":\"Prometheus\","
                + "\"url\":\"http://localhost:9090\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"grafanaDatasourceUid\":null,\"defaultTenant\":null,"
                + "\"tls\":{\"verify\":true,\"caSecretRef\":null},\"auth\":{\"mode\":\"NONE\",\"secretRef\":null}}";
    }

    private static String legacyCatalog() {
        return "{\"schemaVersion\":\"att-sut-metrics-catalog/v1\",\"updatedAt\":\"2026-01-01T00:00:00Z\","
                + "\"sources\":[" + sourceJson() + "],"
                + "\"metricSources\":[{\"schemaVersion\":\"att-sut-metrics-metric-source/v1\",\"id\":\"host-metrics\",\"name\":\"Host metrics\","
                + "\"revision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"connection\":{\"kind\":\"DATA_SOURCE\",\"id\":\"prom\",\"revision\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"},"
                + "\"fetch\":{\"id\":\"host\",\"mode\":\"prometheus-range\",\"query\":\"up\",\"parameters\":{},\"sampling\":\"range\",\"step\":\"15s\"},"
                + "\"mapping\":{\"labels\":{\"retain\":[\"instance\"]},\"outputs\":[{\"id\":\"service-up\",\"title\":\"Service up\",\"unit\":\"count\",\"kind\":\"gauge\",\"metricName\":\"up\"}]},"
                + "\"limits\":{\"maxRows\":128,\"maxSeries\":32,\"maxSamples\":1000,\"maxArtifactBytes\":1048576}}],"
                + "\"groups\":[{\"schemaVersion\":\"att-sut-metrics-group/v1\",\"id\":\"runtime\",\"title\":\"Runtime\",\"order\":1,"
                + "\"match\":{\"metricIds\":[\"service-up\"]},\"groupBy\":[\"instance\"],\"display\":\"chart\"}],"
                + "\"profiles\":["
                + "{\"schemaVersion\":\"att-sut-metrics-profile/v1\",\"id\":\"legacy-base\",\"name\":\"Legacy base\",\"kind\":\"base\",\"category\":\"app\","
                + "\"params\":{\"instance\":{\"type\":\"text\",\"required\":false,\"default\":null}},"
                + "\"metrics\":[{\"id\":\"legacy-cpu\",\"title\":\"CPU\",\"unit\":\"percent\",\"kind\":\"gauge\",\"query\":\"up\"},"
                + "{\"id\":\"legacy-sessions\",\"title\":\"Sessions\",\"unit\":\"count\",\"kind\":\"gauge\",\"category\":\"app\","
                + "\"call\":\"#{db.orders.query(sql='select instance, sessions from metric_summary')}\",\"sampling\":\"afterDrain\",\"window\":null,"
                + "\"sideEffectsAcknowledged\":false,\"mapping\":{\"rows\":\"rows\",\"labels\":{\"instance\":\"INSTANCE\"}},"
                + "\"callGroupId\":\"legacy-sessions-call\",\"column\":\"SESSIONS\",\"multiOutput\":true}]},"
                + "{\"schemaVersion\":\"att-sut-metrics-profile/v1\",\"id\":\"legacy-composite\",\"name\":\"Legacy composite\",\"kind\":\"composite\",\"category\":\"app\","
                + "\"includes\":[{\"profile\":\"legacy-base\",\"alias\":\"legacy\",\"source\":\"prom\",\"targets\":[{\"id\":\"sut-1\",\"type\":\"application\"}]}]},"
                + "{\"schemaVersion\":\"att-sut-metrics-profile/v2\",\"id\":\"flat\",\"name\":\"Flat profile\",\"revision\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\","
                + "\"metricSourceIds\":[\"host-metrics\"],\"groupIds\":[\"runtime\"],\"allowedBindings\":{\"environments\":[\"prod\"],\"targets\":[],\"tenants\":[],\"parameters\":{}},"
                + "\"budgets\":{\"maxFetches\":1,\"maxObservations\":128,\"maxArtifactBytes\":1048576}}]}";
    }
}
