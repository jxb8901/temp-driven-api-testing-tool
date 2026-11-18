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
        Files.copy(Paths.get("examples/history/config/sut-metrics-v2.14.yaml"), config,
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

    @Test void everyEnvironmentDataSourceIsValidatedWhileCompatibilityViewUsesTheFirstEnvironment() throws Exception {
        Path config = configPath();
        Path descriptors = Files.createDirectories(temp.resolve("config/sut-metrics"));
        Path sit = descriptors.resolve("prometheus-sit.yaml");
        Path uat = descriptors.resolve("prometheus-uat.yaml");
        Files.writeString(sit, dataSource("SIT Prometheus", "sit"));
        Files.writeString(uat, dataSource("UAT Prometheus", "uat"));
        String source = "schemaVersion: att-config/v2.15\n"
                + "environment: SIT\n"
                + "sutMetrics: {dataSources: [config/sut-metrics/prometheus-sit.yaml]}\n"
                + "environments:\n"
                + "  UAT: {sutMetrics: {dataSources: [config/sut-metrics/prometheus-uat.yaml]}}\n";
        Files.writeString(config, source);

        try (SutMetricsService service = new SutMetricsService(temp.resolve("all-environment-data"),
                Set.of("prometheus-sit.example.invalid", "prometheus-uat.example.invalid"),
                reference -> "MISSING".equals(reference) ? null : "test-secret",
                ignored -> { }, ignored -> { }, config, temp)) {
            assertEquals("prom-main", source(service).get("id"));
            assertEquals("SIT Prometheus", source(service).get("name"));

            Files.writeString(uat, dataSource("UAT Prometheus", "uat")
                    .replace("auth: {mode: NONE}", "auth: {mode: BEARER, secretRef: MISSING}"));
            Map<String, Object> missingSecret = configuration(service);
            assertEquals("LAST_KNOWN_GOOD", missingSecret.get("state"));
            assertEquals("config.sutMetrics.dataSources[].auth.secretRef", missingSecret.get("field"));
            assertEquals("SIT Prometheus", source(service).get("name"));

            Files.writeString(uat, dataSource("UAT Prometheus", "uat")
                    .replace("url: https://prometheus-uat.example.invalid", "url: https://forbidden.example.invalid"));
            Map<String, Object> forbiddenHost = configuration(service);
            assertEquals("LAST_KNOWN_GOOD", forbiddenHost.get("state"));
            assertEquals("config.sutMetrics.dataSources[].url", forbiddenHost.get("field"));
            assertEquals("SIT Prometheus", source(service).get("name"));
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
                assertEquals(runtimeRevision(config, candidateA), pausedRevision.get());

                Files.writeString(config, candidateB);
                java.util.concurrent.Future<Map<String, Object>> newerRead = callers.submit(() -> service.listSources("alice", false));
                Map<String, Object> newerResponse = newerRead.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("Prometheus Candidate B", firstSourceName(newerResponse));

                releaseCandidate.countDown();
                Map<String, Object> slowerResponse = slowerRead.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("Prometheus Candidate B", firstSourceName(slowerResponse),
                        "The stale successful candidate must be discarded after a newer revision activates.");
                assertEquals("Prometheus Candidate B", source(service).get("name"));
                assertEquals(runtimeRevision(config, candidateB), configuration(service).get("revision"));
                assertEquals(1, ((List<?>) service.listSources("alice", false).get("items")).size());
                assertTrue(((List<?>) service.listSources("bob", false).get("items")).isEmpty(),
                        "A slower earlier revision must not restore a reader revoked by the newer Runtime Config.");
            } finally {
                releaseCandidate.countDown();
                callers.shutdownNow();
            }
        }
    }

    @Test void successfulSecretResolutionIsFencedBeforePublishingOrReturningTheCandidate() throws Exception {
        Path config = configPath();
        writeConfig(config, "Prometheus Base");
        java.util.concurrent.CountDownLatch secretAResolutionEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishSecretAResolution = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean firstSecretAResolution = new AtomicBoolean(true);
        AtomicBoolean secretAResolvedSuccessfully = new AtomicBoolean();
        java.util.function.Function<String, String> resolver = reference -> {
            if ("SECRET_A".equals(reference) && firstSecretAResolution.compareAndSet(true, false)) {
                secretAResolutionEntered.countDown();
                try {
                    if (!finishSecretAResolution.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out while resolving the successful Runtime Config A secret.");
                    secretAResolvedSuccessfully.set(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while resolving the Runtime Config A secret.", interrupted);
                }
            }
            return "test-secret";
        };

        try (SutMetricsService service = new SutMetricsService(temp.resolve("secret-resolution-fence-data"),
                Set.of("prometheus.example.invalid"), resolver, ignored -> { }, ignored -> { }, config, temp)) {
            String candidateA = configText("Prometheus Candidate A")
                    .replace("      readers: ['*']\n", "      readers: ['alice', 'bob']\n"
                            + "      auth: {mode: BEARER, secretRef: SECRET_A}\n");
            String candidateB = configText("Prometheus Candidate B")
                    .replace("      readers: ['*']\n", "      readers: ['alice']\n"
                            + "      auth: {mode: BEARER, secretRef: SECRET_B}\n");
            Files.writeString(config, candidateA);

            java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Future<Map<String, Object>> slowerRead =
                        callers.submit(() -> service.listSources("admin", true));
                assertTrue(secretAResolutionEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                        "The valid A candidate must pause inside successful secret resolution.");

                Files.writeString(config, candidateB);
                java.util.concurrent.Future<Map<String, Object>> newerRead =
                        callers.submit(() -> service.listSources("alice", false));
                Map<String, Object> newerResponse = newerRead.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("Prometheus Candidate B", firstSourceName(newerResponse),
                        "B must be publishable while A's external secret resolver is still in flight.");
                assertTrue(((List<?>) service.listSources("bob", false).get("items")).isEmpty(),
                        "B must revoke Bob before A resumes.");

                finishSecretAResolution.countDown();
                Map<String, Object> slowerResponse = slowerRead.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(secretAResolvedSuccessfully.get(), "A's secret lookup must complete successfully before its stale result is fenced.");
                assertEquals("Prometheus Candidate B", firstSourceName(slowerResponse),
                        "The in-flight request must not return the stale A view after B replaced it.");
                assertEquals("Prometheus Candidate B", source(service).get("name"));
                assertEquals(runtimeRevision(config, candidateB), configuration(service).get("revision"));
                assertTrue(((List<?>) service.listSources("bob", false).get("items")).isEmpty(),
                        "A must not restore a reader that B revoked.");
            } finally {
                finishSecretAResolution.countDown();
                callers.shutdownNow();
            }
        }
    }

    @Test void descriptorOnlyRevisionIsReloadedAndFencesAValidatedStaleCandidate() throws Exception {
        Path config = configPath();
        Path descriptor = temp.resolve("config/sut-metrics/prometheus.yaml");
        Files.createDirectories(descriptor.getParent());
        String configSource = "schemaVersion: att-config/v2.15\nenvironment: SIT\n"
                + "sutMetrics: {dataSources: [config/sut-metrics/prometheus.yaml]}\n";
        Files.writeString(config, configSource);
        Files.writeString(descriptor, descriptorSource("Prometheus Base", "alice", "BASE_SECRET"));
        java.util.concurrent.CountDownLatch secretAEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseSecretA = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean pauseA = new AtomicBoolean(true);
        java.util.function.Function<String, String> resolver = reference -> {
            if ("SECRET_A".equals(reference) && pauseA.compareAndSet(true, false)) {
                secretAEntered.countDown();
                try {
                    if (!releaseSecretA.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out while resolving the stale descriptor candidate.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while resolving the stale descriptor candidate.", interrupted);
                }
            }
            return "resolved-token";
        };
        try (SutMetricsService service = new SutMetricsService(temp.resolve("descriptor-fence-data"),
                Set.of("prometheus.example.invalid"), resolver, ignored -> { }, ignored -> { }, config, temp)) {
            Files.writeString(descriptor, descriptorSource("Descriptor Candidate A", "alice,bob", "SECRET_A"));
            java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Future<Map<String, Object>> slower = callers.submit(() -> service.listSources("admin", true));
                assertTrue(secretAEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                        "Descriptor A must pause after successful catalog validation begins.");
                Files.writeString(descriptor, descriptorSource("Descriptor Candidate B", "alice", "SECRET_B"));
                Map<String, Object> newer = callers.submit(() -> service.listSources("alice", false))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("Descriptor Candidate B", firstSourceName(newer));
                assertTrue(((List<?>) service.listSources("bob", false).get("items")).isEmpty(),
                        "The descriptor-only B revision must revoke Bob.");
                releaseSecretA.countDown();
                assertEquals("Descriptor Candidate B", firstSourceName(slower.get(5, java.util.concurrent.TimeUnit.SECONDS)));
                assertEquals("Descriptor Candidate B", source(service).get("name"));
                assertEquals(runtimeRevision(config, configSource), configuration(service).get("revision"));
            } finally {
                releaseSecretA.countDown();
                callers.shutdownNow();
            }
        }
    }

    @Test void packageCatalogResolvesExplicitInventoryPerEnvironmentWithoutExposingCredentialsOrQueryingSources() throws Exception {
        Path config = configPath();
        Path packageRoot = temp.resolve("packages/payments");
        Files.createDirectories(packageRoot);
        Files.writeString(config, "schemaVersion: att-config/v2.15\n"
                + "environment: SIT\n"
                + "sutMetrics: {dataSources: [config/sut-metrics/prometheus-sit.yaml]}\n"
                + "environments:\n"
                + "  UAT: {sutMetrics: {dataSources: [config/sut-metrics/prometheus-uat.yaml]}}\n");
        String dataSource = "schemaVersion: att-sut-metrics-source/v1\n"
                + "id: prom-main\nname: %s Prometheus\nurl: https://prometheus.example.invalid\n"
                + "auth: {mode: BEARER, secretRef: NEVER_EXPOSE_THIS_SECRET_REF}\n"
                + "readers: [alice]\nreadRoles: []\n"
                + "limits: {connectTimeoutMs: 5000, requestTimeoutMs: 15000, maxRangeSeconds: 86400, maxResponseBytes: 2097152, maxSamplesPerJob: 250000, maxSeriesPerJob: 128}\n";
        Files.createDirectories(temp.resolve("config/sut-metrics"));
        Files.writeString(temp.resolve("config/sut-metrics/prometheus-sit.yaml"), dataSource.formatted("SIT"));
        Files.writeString(temp.resolve("config/sut-metrics/prometheus-uat.yaml"), dataSource.formatted("UAT"));
        Files.writeString(packageRoot.resolve("package.yaml"), "schemaVersion: att-package/v2\n"
                + "metadata: {name: Payments}\n"
                + "observability:\n"
                + "  metricSources: [load/sut-metrics/sources/app-runtime.yaml]\n"
                + "  groups: [load/sut-metrics/groups/app-servers.yaml]\n"
                + "  profiles: [load/sut-metrics/profiles/basic.yaml]\n");
        Files.createDirectories(packageRoot.resolve("load/sut-metrics/sources"));
        Files.createDirectories(packageRoot.resolve("load/sut-metrics/groups"));
        Files.createDirectories(packageRoot.resolve("load/sut-metrics/profiles"));
        Files.writeString(packageRoot.resolve("load/sut-metrics/sources/app-runtime.yaml"),
                "schemaVersion: att-sut-metrics-metric-source/v1\n"
                        + "id: app-runtime\nname: Application CPU\nconnection: {kind: DATA_SOURCE, id: prom-main}\n"
                        + "fetch: {id: cpu-range, mode: prometheus-range, query: app_cpu, parameters: {}, sampling: range, step: 30s}\n"
                        + "mapping: {labels: {retain: [instance]}, outputs: [{id: cpu, title: CPU, unit: percent, kind: gauge, metricName: app_cpu}]}\n"
                        + "limits: {maxRows: 100, maxSeries: 16, maxSamples: 1000, maxArtifactBytes: 8192}\n");
        Files.writeString(packageRoot.resolve("load/sut-metrics/groups/app-servers.yaml"),
                "schemaVersion: att-sut-metrics-group/v1\nid: app-servers\ntitle: Application servers\n"
                        + "order: 10\nmatch: {metricIds: [cpu]}\ngroupBy: [instance]\ndisplay: chart\n");
        Files.writeString(packageRoot.resolve("load/sut-metrics/profiles/basic.yaml"),
                "schemaVersion: att-sut-metrics-profile/v2\nid: basic\nname: Basic metrics\n"
                        + "metricSourceIds: [app-runtime]\ngroupIds: [app-servers]\n"
                        + "allowedBindings: {environments: [SIT, UAT], targets: [{type: template, id: PAYMENTS_HEALTH}], tenants: [], parameters: {}}\n"
                        + "budgets: {maxFetches: 1, maxObservations: 16, maxArtifactBytes: 8192}\n");

        Path data = temp.resolve("package-catalog-data");
        try (SutMetricsService service = new SutMetricsService(data, Set.of("prometheus.example.invalid"),
                ignored -> "resolved-secret-value", ignored -> { }, ignored -> { }, config, temp)) {
            Map<String, Object> sitCatalog = service.packageCatalog(packageRoot, "payments", "SIT", "alice", false);
            assertEquals("att-package-sut-metrics-catalog/v1", sitCatalog.get("schemaVersion"));
            assertEquals("SIT Prometheus", object(((List<?>) sitCatalog.get("dataSources")).get(0)).get("name"));
            assertEquals(1, ((List<?>) sitCatalog.get("metricSources")).size());
            assertEquals("att-sut-metrics-profile/v2",
                    object(((List<?>) sitCatalog.get("profiles")).get(0)).get("schemaVersion"));
            String safeCatalog = ServerRuntime.JSON.writeValueAsString(sitCatalog);
            assertFalse(safeCatalog.contains("NEVER_EXPOSE_THIS_SECRET_REF"));
            assertFalse(safeCatalog.contains("resolved-secret-value"));
            assertFalse(safeCatalog.contains("https://prometheus.example.invalid"));
            assertTrue(safeCatalog.contains("load/sut-metrics/sources/app-runtime.yaml"));
            assertEquals("UAT Prometheus", object(((List<?>) service.packageCatalog(packageRoot, "payments", "UAT",
                    "alice", false).get("dataSources")).get(0)).get("name"));

            att.config.FrameworkConfigLoader loader = new att.config.FrameworkConfigLoader();
            var loadedRuntime = loader.loadSutMetricsRuntimeConfiguration(config, temp, Files.readString(config));
            var framework = loader.loadDiscovery(config, temp, packageRoot, "SIT");
            SutMetricsService.BusinessCallContext context = new SutMetricsService.BusinessCallContext(
                    "payments", packageRoot, config, temp, "SIT", "c".repeat(64),
                    loadedRuntime.effectiveRevision("SIT"), Set.of(), List.of(), framework);
            Map<String, Object> scenario = Map.of("schemaVersion", "att-load-observability/v1",
                    "collections", List.of(Map.of("profile", "basic")),
                    "collection", Map.of("before", "1m", "after", "1m", "step", "30s"),
                    "livePreview", Map.of("enabled", false));
            assertFalse(service.scenarioRequiresBusinessCallContext(scenario, packageRoot));
            Map<String, Object> snapshot = service.resolveScenario(scenario, "alice", false, context);
            assertEquals("c".repeat(64), object(snapshot.get("execution")).get("packageRevision"));
            assertEquals(att.config.PackageConfig.load(packageRoot).digest(),
                    object(snapshot.get("execution")).get("manifestDigest"));
            assertEquals("prom-main", maps(snapshot.get("queries")).get(0).get("sourceId"));
            assertTrue(maps(snapshot.get("provenance")).stream().anyMatch(item ->
                    "load/sut-metrics/profiles/basic.yaml".equals(item.get("path"))));

            Path metricSourcePath = packageRoot.resolve("load/sut-metrics/sources/app-runtime.yaml");
            byte[] liveMetricSource = Files.readAllBytes(metricSourcePath);
            String candidateSource = Files.readString(metricSourcePath).replace("Application CPU", "Candidate CPU");
            Map<String, Object> validated = service.validatePackageMetricResource(packageRoot, "payments", "alice",
                    "load/sut-metrics/sources/app-runtime.yaml", "SIT", candidateSource);
            assertEquals(true, validated.get("valid"));
            assertEquals(sha256(candidateSource.getBytes(java.nio.charset.StandardCharsets.UTF_8)), validated.get("resourceDigest"));
            String initialValidationRevision = (String) validated.get("validationRevision");
            assertTrue(initialValidationRevision.matches("[a-f0-9]{64}"));
            assertArrayEquals(liveMetricSource, Files.readAllBytes(metricSourcePath),
                    "Candidate Package resources must be resolved as an overlay without writes.");

            Files.writeString(temp.resolve("config/sut-metrics/prometheus-sit.yaml"), dataSource.formatted("SIT changed"));
            Map<String, Object> afterGlobalDescriptorChange = service.validatePackageMetricResource(packageRoot, "payments", "alice",
                    "load/sut-metrics/sources/app-runtime.yaml", "SIT", candidateSource);
            assertNotEquals(initialValidationRevision, afterGlobalDescriptorChange.get("validationRevision"),
                    "Validation revision must include referenced Runtime Config Data Source descriptor digests.");
            assertArrayEquals(liveMetricSource, Files.readAllBytes(metricSourcePath));

            String packageManifest = Files.readString(packageRoot.resolve("package.yaml"));
            String newResourcePath = "load/sut-metrics/sources/app-memory.yaml";
            String candidateManifest = packageManifest.replace(
                    "load/sut-metrics/sources/app-runtime.yaml]",
                    "load/sut-metrics/sources/app-runtime.yaml, " + newResourcePath + "]");
            String newResourceSource = Files.readString(metricSourcePath)
                    .replace("app-runtime", "app-memory").replace("Application CPU", "Application Memory")
                    .replace("cpu-range", "memory-range").replace("app_cpu", "app_memory")
                    .replace("id: cpu, title: CPU", "id: memory, title: Memory");
            Map<String, Object> createValidation = service.validatePackageMetricResource(packageRoot, "payments", "alice",
                    newResourcePath, "SIT", newResourceSource, candidateManifest,
                    att.config.PackageConfig.load(packageRoot).digest());
            assertEquals(sha256(newResourceSource.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    createValidation.get("resourceDigest"));
            assertEquals(sha256(candidateManifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    createValidation.get("candidateManifestDigest"));
            assertFalse(Files.exists(packageRoot.resolve(newResourcePath)));
            assertEquals(packageManifest, Files.readString(packageRoot.resolve("package.yaml")),
                    "Detached manifest/resource validation must not activate or write either candidate.");
            assertThrows(IllegalArgumentException.class, () -> service.validatePackageMetricResource(packageRoot, "payments", "alice",
                    newResourcePath, "SIT", null, candidateManifest,
                    att.config.PackageConfig.load(packageRoot).digest()),
                    "A create candidate without resource bytes must fail closed.");
            assertThrows(IllegalArgumentException.class, () -> service.validatePackageMetricResource(packageRoot, "payments", "alice",
                    newResourcePath, "SIT", "schemaVersion: invalid-resource/v1\nid: app-memory\n", candidateManifest,
                    att.config.PackageConfig.load(packageRoot).digest()),
                    "A non-matching Metric Source schema must not be activated.");
            assertFalse(Files.exists(packageRoot.resolve(newResourcePath)));
            assertEquals(packageManifest, Files.readString(packageRoot.resolve("package.yaml")),
                    "Rejected source candidates must leave the Package inventory unchanged.");
            assertThrows(IllegalArgumentException.class, () -> service.validatePackageMetricResource(packageRoot, "payments", "alice",
                    newResourcePath, "SIT", newResourceSource,
                    candidateManifest.replace("metadata: {name: Payments}", "metadata: {name: Changed}"),
                    att.config.PackageConfig.load(packageRoot).digest()));
            assertEquals(0, ((List<?>) service.listMetricSources("alice", false).get("items")).size(),
                    "Package definitions must remain request-scoped and must not mutate the global catalog.");
        }
    }

    @Test void explicitEmptyPackageInventoryDoesNotFallBackToGlobalMetricProfiles() throws Exception {
        Path config = configPath();
        String global = configText("Global Prometheus").replace("profiles: []", "profiles:\n"
                + "    - schemaVersion: att-sut-metrics-profile/v1\n"
                + "      id: global-profile\n      name: Global profile\n      kind: base\n      category: app\n"
                + "      metrics: [{id: up, title: Up, unit: count, kind: gauge, query: up}]\n");
        Files.writeString(config, global);
        Path packageRoot = temp.resolve("packages/empty");
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("package.yaml"), "schemaVersion: att-package/v2\n"
                + "observability: {metricSources: [], groups: [], profiles: []}\n");
        try (SutMetricsService service = service(temp.resolve("empty-package-data"), config)) {
            Map<String, Object> catalog = service.packageCatalog(packageRoot, "empty", "SIT", "alice", false);
            assertTrue(((List<?>) catalog.get("metricSources")).isEmpty());
            assertTrue(((List<?>) catalog.get("groups")).isEmpty());
            assertTrue(((List<?>) catalog.get("profiles")).isEmpty());
            att.config.FrameworkConfigLoader loader = new att.config.FrameworkConfigLoader();
            var loadedRuntime = loader.loadSutMetricsRuntimeConfiguration(config, temp, Files.readString(config));
            var framework = loader.loadDiscovery(config, temp, packageRoot, "SIT");
            SutMetricsService.BusinessCallContext context = new SutMetricsService.BusinessCallContext(
                    "empty", packageRoot, config, temp, "SIT", "d".repeat(64),
                    loadedRuntime.effectiveRevision("SIT"), Set.of(), List.of(), framework);
            Map<String, Object> scenario = Map.of("schemaVersion", "att-load-observability/v1",
                    "collections", List.of(Map.of("profile", "global-profile")),
                    "collection", Map.of(), "livePreview", Map.of("enabled", false));
            assertThrows(IllegalArgumentException.class,
                    () -> service.resolveScenario(scenario, "alice", false, context));
        }
    }

    @Test void preV2PackageCatalogRetainsHistoricalGlobalMetricsFallback() throws Exception {
        Path config = configPath();
        String global = configText("Legacy Prometheus").replace("readers: ['*']", "readers: [alice]")
                .replace("  profiles: []\n", "  profiles:\n"
                + "    - schemaVersion: att-sut-metrics-profile/v2\n"
                + "      id: global-profile\n      name: Global profile\n"
                + "      metricSourceIds: [host-metrics]\n      groupIds: [runtime]\n"
                + "      allowedBindings: {environments: [], targets: [], tenants: [], parameters: {}}\n");
        Files.writeString(config, global);
        Path packageRoot = temp.resolve("packages/legacy");
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("package.yaml"), "schemaVersion: att-package/v1\n");

        try (SutMetricsService service = service(temp.resolve("legacy-package-data"), config)) {
            Map<String, Object> catalog = service.packageCatalog(packageRoot, "legacy", "SIT", "alice", false);
            assertEquals(1, ((List<?>) catalog.get("metricSources")).size());
            assertEquals(1, ((List<?>) catalog.get("groups")).size());
            assertEquals("global-profile", object(((List<?>) catalog.get("profiles")).get(0)).get("id"));
            Map<String, Object> restricted = service.packageCatalog(packageRoot, "legacy", "SIT", "bob", false);
            assertTrue(((List<?>) restricted.get("metricSources")).isEmpty());
            assertTrue(((List<?>) restricted.get("profiles")).isEmpty(),
                    "Legacy fallback must not expose Profiles that reference an unreadable Data Source.");
        }
    }

    @Test void legacyProfileProjectionKeepsThresholdMetadataWithoutQueryText() throws Exception {
        Path config = configPath();
        String global = configText("Legacy Prometheus").replace("  profiles: []\n", "  profiles:\n"
                + "    - schemaVersion: att-sut-metrics-profile/v1\n"
                + "      id: legacy-base\n      name: Legacy base\n      kind: base\n      category: app\n"
                + "      metrics: [{id: cpu, title: CPU usage, unit: percent, kind: gauge, query: 'up{job=\"api\"}'}]\n"
                + "    - schemaVersion: att-sut-metrics-profile/v1\n"
                + "      id: legacy-composite\n      name: Legacy composite\n      kind: composite\n      category: app\n"
                + "      includes: [{profile: legacy-base, alias: host, source: prom, targets: [{type: template, id: Payments.create}]}]\n");
        Files.writeString(config, global);
        Path packageRoot = temp.resolve("packages/legacy-profiles");
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("package.yaml"), "schemaVersion: att-package/v1\n");

        try (SutMetricsService service = service(temp.resolve("legacy-profile-data"), config)) {
            Map<String, Object> catalog = service.packageCatalog(packageRoot, "legacy-profiles", "SIT", "alice", false);
            List<?> profiles = (List<?>) catalog.get("profiles");
            Map<String, Object> base = profiles.stream().map(SutMetricsConfigCatalogTest::object)
                    .filter(profile -> "legacy-base".equals(profile.get("id"))).findFirst().orElseThrow();
            assertEquals(false, base.get("packageOwned"));
            assertEquals("base", base.get("kind"));
            assertEquals("cpu", object(((List<?>) base.get("metricCatalog")).get(0)).get("id"));
            assertFalse(base.containsKey("metrics"));
            assertFalse(base.toString().contains("up{job="), "Legacy query text must stay private.");
            Map<String, Object> composite = profiles.stream().map(SutMetricsConfigCatalogTest::object)
                    .filter(profile -> "legacy-composite".equals(profile.get("id"))).findFirst().orElseThrow();
            assertEquals("composite", composite.get("kind"));
            assertEquals("legacy-base", object(((List<?>) composite.get("includes")).get(0)).get("profile"));
            assertFalse(composite.toString().contains("source"), "Connection bindings are unnecessary for manual threshold editing.");
        }
    }

    @Test void packageBusinessCallProfilesRequestExecutionContextOnlyWhenSelected() throws Exception {
        Path packageRoot = temp.resolve("packages/business-metrics");
        Files.createDirectories(packageRoot.resolve("load/sut-metrics/sources"));
        Files.createDirectories(packageRoot.resolve("load/sut-metrics/profiles"));
        Files.writeString(packageRoot.resolve("package.yaml"), "schemaVersion: att-package/v2\n"
                + "observability:\n"
                + "  metricSources: [load/sut-metrics/sources/db.yaml]\n"
                + "  profiles: [load/sut-metrics/profiles/db.yaml]\n");
        Files.writeString(packageRoot.resolve("load/sut-metrics/sources/db.yaml"),
                "schemaVersion: att-sut-metrics-metric-source/v1\nid: db-summary\nname: DB summary\n"
                        + "connection: {kind: DBHELPER, id: orders}\n"
                        + "fetch: {id: db-query, mode: dbhelper-query, call: \"#{db.orders.query(sql='select 1')}\", parameters: {}, sampling: afterDrain}\n"
                        + "mapping: {labels: {}, outputs: [{id: count, title: Count, column: COUNT, unit: count, kind: gauge}]}\n"
                        + "limits: {maxRows: 10, maxSeries: 1, maxSamples: 10, maxArtifactBytes: 8192}\n");
        Files.writeString(packageRoot.resolve("load/sut-metrics/profiles/db.yaml"),
                "schemaVersion: att-sut-metrics-profile/v2\nid: database\nname: Database\n"
                        + "metricSourceIds: [db-summary]\ngroupIds: []\n"
                        + "allowedBindings: {environments: [SIT], targets: [], tenants: [], parameters: {}}\n");
        Map<String, Object> selected = Map.of("collections", List.of(Map.of("profile", "database")));
        Map<String, Object> unrelated = Map.of("collections", List.of(Map.of("profile", "missing")));
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-context-data"))) {
            assertTrue(service.scenarioRequiresBusinessCallContext(selected, packageRoot));
            assertFalse(service.scenarioRequiresBusinessCallContext(unrelated, packageRoot));
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

    private String descriptorSource(String name, String readers, String secretRef) {
        return "schemaVersion: att-sut-metrics-source/v1\n"
                + "id: prometheus\nname: " + name + "\nurl: https://prometheus.example.invalid\n"
                + "auth: {mode: BEARER, secretRef: " + secretRef + "}\n"
                + "readers: [" + String.join(", ", readers.split(",")) + "]\nreadRoles: []\n";
    }

    private String dataSource(String name, String suffix) {
        return "schemaVersion: att-sut-metrics-source/v1\n"
                + "id: prom-main\n"
                + "name: " + name + "\n"
                + "url: https://prometheus-" + suffix + ".example.invalid\n"
                + "apiPath: /api/v1/query_range\n"
                + "tls: {verify: true}\n"
                + "auth: {mode: NONE}\n"
                + "readers: [load-observer]\n"
                + "readRoles: [USER]\n"
                + "limits: {requestTimeoutMs: 15000, maxRangeSeconds: 86400, maxResponseBytes: 2097152, maxSamplesPerJob: 250000, maxSeriesPerJob: 128}\n";
    }

    private static String firstSourceName(Map<String, Object> response) {
        Map<?, ?> first = (Map<?, ?>) ((List<?>) response.get("items")).get(0);
        return String.valueOf(first.get("name"));
    }

    private static Map<String, Object> object(Object value) {
        return ServerRuntime.JSON.convertValue(value, Map.class);
    }

    private static List<Map<String, Object>> maps(Object value) {
        return ServerRuntime.JSON.convertValue(value,
                ServerRuntime.JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String runtimeRevision(Path path, String source) throws Exception {
        return new att.config.FrameworkConfigLoader().loadSutMetricsRuntimeConfiguration(path, temp, source)
                .overallRevision();
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
