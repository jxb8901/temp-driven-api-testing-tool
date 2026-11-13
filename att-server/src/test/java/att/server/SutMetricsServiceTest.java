package att.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SutMetricsServiceTest {
    private static final String JOB_ID = "J0000000000000001";
    @TempDir Path temp;

    @Test void directBaseBindingUsesScenarioOverridesAndSourceScopedStableIds() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom-a", fixture.url(), List.of("alice"), Map.of()));
            service.upsertSource(source("prom-b", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, true));

            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(
                    binding("prom-a", "scenario-override"), binding("prom-b", "scenario-override")), "1m", "1m", "15s"), "alice", false);
            List<Map<String, Object>> queries = maps(snapshot.get("queries"));

            assertEquals(List.of("prom-a/base/sut-1/metric-1", "prom-b/base/sut-1/metric-1"),
                    queries.stream().map(query -> query.get("id")).toList());
            assertEquals("up{service=\"scenario-override\"}", queries.get(0).get("query"));
            assertEquals("prom-a", queries.get(0).get("sourceId"));
            assertEquals("sut-1", queries.get(0).get("targetId"));
            assertThrows(ServerRuntime.NotFoundException.class,
                    () -> service.resolveScenario(scenario(List.of(binding("prom-a", "scenario-override")), "1m", "1m", "15s"), "mallory", false));
        }
    }

    @Test void derivedProfileDefaultsReachBaseAndOnlyDeclaredScenarioOverridesAreAccepted() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            Map<String, Object> base = map("schemaVersion", "att-sut-metrics-profile/v1", "id", "base", "name", "Base",
                    "kind", "base", "category", "app", "params", Map.of("service", Map.of("type", "promLabelValue", "default", "base")),
                    "parameters", Map.of("service", "base-default"), "targets", List.of(map("id", "base-target", "type", "app")),
                    "metrics", List.of(map("id", "requests", "title", "Requests", "unit", "count", "kind", "gauge",
                            "query", "up{service=\"{{service}}\"}")));
            service.upsertProfile(base);
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "derived", "name", "Derived",
                    "kind", "derived", "extends", "base", "source", "prom", "targets", List.of(map("id", "sut-1", "type", "app")),
                    "parameters", Map.of("service", "derived-default")));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "derived-open", "name", "Derived open",
                    "kind", "derived", "extends", "base", "source", "prom", "targets", List.of(map("id", "sut-1", "type", "app")),
                    "parameters", Map.of("service", "derived-default"), "overridableParams", List.of("service")));

            Map<String, Object> defaulted = service.resolveScenario(scenario(List.of(map("profile", "derived")), "1m", "1m", "15s"), "alice");
            assertEquals("up{service=\"derived-default\"}", maps(defaulted.get("queries")).get(0).get("query"));
            assertThrows(IllegalArgumentException.class, () -> service.resolveScenario(scenario(List.of(
                    map("profile", "derived", "parameters", Map.of("service", "scenario-value"))), "1m", "1m", "15s"), "alice"));

            Map<String, Object> explicitlyOverridden = service.resolveScenario(scenario(List.of(
                    map("profile", "derived-open", "parameters", Map.of("service", "scenario-value"))), "1m", "1m", "15s"), "alice");
            assertEquals("up{service=\"scenario-value\"}", maps(explicitlyOverridden.get("queries")).get(0).get("query"));
        }
    }

    @Test void duplicateMetricIdsAreRejectedAcrossProfilesForTheSameResolvedTarget() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base-a", 1, false));
            service.upsertProfile(baseProfile("base-b", 1, false));
            List<Map<String, Object>> bindings = List.of(
                    map("profile", "base-a", "source", "prom", "targets", List.of(map("id", "sut-1", "type", "app"))),
                    map("profile", "base-b", "source", "prom", "targets", List.of(map("id", "sut-1", "type", "app"))));

            assertThrows(IllegalArgumentException.class,
                    () -> service.resolveScenario(scenario(bindings, "1m", "1m", "15s"), "alice"));
        }
    }

    @Test void missingProfileReferencesAndDependencyCyclesAreRejectedAtomically() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", "http://127.0.0.1:9090", List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> first = map("schemaVersion", "att-sut-metrics-profile/v1", "id", "derived-one", "name", "Derived one",
                    "kind", "derived", "extends", "base", "source", "prom", "targets", List.of(map("id", "sut-1", "type", "app")));
            service.upsertProfile(first);
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "derived-two", "name", "Derived two",
                    "kind", "derived", "extends", "derived-one"));

            Map<String, Object> cycle = new LinkedHashMap<>(first);
            cycle.put("extends", "derived-two");
            assertThrows(IllegalArgumentException.class, () -> service.upsertProfile(cycle));
            assertThrows(IllegalArgumentException.class, () -> service.upsertProfile(map(
                    "schemaVersion", "att-sut-metrics-profile/v1", "id", "missing-parent", "name", "Missing parent",
                    "kind", "derived", "extends", "not-installed")));

            Map<String, Object> resolved = service.resolveScenario(scenario(List.of(map("profile", "derived-one")), "1m", "1m", "15s"), "alice");
            assertEquals("up", maps(resolved.get("queries")).get(0).get("query"),
                    "A rejected catalog edit must leave the previously valid dependency graph intact.");
        }
    }

    @Test void exactHttpsEgressAllowlistIsRequiredForRegisteredExternalHosts() throws Exception {
        Map<String, Object> external = source("prom", "https://metrics.example.test", List.of("*"), Map.of());
        try (SutMetricsService blocked = new SutMetricsService(temp.resolve("blocked"), Set.of())) {
            assertThrows(IllegalArgumentException.class, () -> blocked.upsertSource(external));
        }
        try (SutMetricsService allowed = new SutMetricsService(temp.resolve("allowed"), Set.of("metrics.example.test"))) {
            assertEquals("prom", allowed.upsertSource(external).get("id"));
        }
    }

    @Test void livePreviewReturnsNoDataForValidFiltersWithNoMatchingCombination() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            service.upsertProfile(baseProfile("base-b", 1, false));
            Map<String, Object> configured = scenario(List.of(
                    map("profile", "base", "source", "prom", "targets", List.of(map("id", "sut-1", "type", "app"))),
                    map("profile", "base-b", "source", "prom", "targets", List.of(map("id", "sut-2", "type", "app")))), "1m", "1m", "15s");
            configured.put("livePreview", Map.of("enabled", true));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false);
            service.writeSnapshot(JOB_ID, snapshot);

            Map<String, Object> preview = service.livePreview(JOB_ID, "alice", false, Instant.now().minusSeconds(60),
                    "15m", "30s", "sut-1", "base-b");

            assertEquals("NO_DATA", preview.get("state"));
            assertTrue(maps(preview.get("items")).isEmpty());
            assertEquals(0, fixture.requests.get(), "A valid empty filter combination must not issue a source request.");
        }
    }

    @Test void livePreviewReturnsNoDataWhenPrometheusSuccessfullyReturnsEmptyResults() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(0); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(profileWithQueries("up"));
            Map<String, Object> configured = scenario(List.of(binding("prom", null)), "1m", "1m", "15s");
            configured.put("livePreview", Map.of("enabled", true));
            service.writeSnapshot(JOB_ID, service.resolveScenario(configured, "alice", false));

            Map<String, Object> preview = service.livePreview(JOB_ID, "alice", false, Instant.now().minusSeconds(300),
                    "15m", "30s", null, null);

            assertEquals(1, fixture.requests.get(), "The Prometheus endpoint must return a successful empty result.");
            assertEquals("NO_DATA", preview.get("state"));
            assertEquals("NO_DATA", maps(preview.get("items")).get(0).get("queryStatus"));
        }
    }

    @Test void livePreviewMarksMixedPopulatedAndEmptyPrometheusQueriesPartial() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1, Map.of("up", 1, "down", 0));
             SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(profileWithQueries("up", "down"));
            Map<String, Object> configured = scenario(List.of(binding("prom", null)), "1m", "1m", "15s");
            configured.put("livePreview", Map.of("enabled", true));
            service.writeSnapshot(JOB_ID, service.resolveScenario(configured, "alice", false));

            Map<String, Object> preview = service.livePreview(JOB_ID, "alice", false, Instant.now().minusSeconds(300),
                    "15m", "30s", null, null);

            assertEquals("PARTIAL", preview.get("state"));
            assertEquals(List.of("STALE", "NO_DATA"), maps(preview.get("items")).stream()
                    .map(item -> item.get("queryStatus")).toList());
        }
    }

    @Test void authenticatedSourceResolvesBearerSecretWithoutExposingItInViewsOrPreview() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1);
             SutMetricsService service = new SutMetricsService(temp, Set.of(), ref -> {
                 assertEquals("PROM_TOKEN", ref);
                 return "test-only-token-value";
             })) {
            Map<String, Object> configuredSource = source("prom", fixture.url(), List.of("alice"), Map.of());
            configuredSource.put("auth", map("mode", "BEARER", "secretRef", "PROM_TOKEN"));
            service.upsertSource(configuredSource);
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> configured = scenario(List.of(binding("prom", null)), "1m", "1m", "15s");
            configured.put("livePreview", Map.of("enabled", true));
            service.writeSnapshot(JOB_ID, service.resolveScenario(configured, "alice", false));

            Map<String, Object> preview = service.livePreview(JOB_ID, "alice", false, Instant.now().minusSeconds(60),
                    "15m", "30s", null, null);

            assertEquals("READY", preview.get("state"));
            assertEquals("Bearer test-only-token-value", fixture.authorization.get());
            assertFalse(service.listSources("alice", false).toString().contains("test-only-token-value"));
            assertFalse(service.listSources("admin", true).toString().contains("test-only-token-value"));
            assertFalse(preview.toString().contains("test-only-token-value"));
        }
    }

    @Test void staggeredLiveViewersShareOnePrometheusRequestForTheSameWindowBucket() throws Exception {
        try(PrometheusFixture fixture=new PrometheusFixture(1,200,600,0,Map.of());
            SutMetricsService service=new SutMetricsService(temp)) {
            service.upsertSource(source("prom",fixture.url(),List.of("alice"),Map.of()));
            service.upsertProfile(baseProfile("base",1,false));
            Map<String,Object> configured=scenario(List.of(binding("prom",null)),"1m","1m","15s");
            configured.put("livePreview",Map.of("enabled",true));
            service.writeSnapshot(JOB_ID,service.resolveScenario(configured,"alice",false));

            while(Instant.now().getEpochSecond()%30<8||Instant.now().getEpochSecond()%30>21)Thread.sleep(20);
            Instant startedAt=Instant.now().minusSeconds(3600);
            Map<String,Object> first=service.livePreview(JOB_ID,"alice",false,startedAt,"15m","30s",null,null);
            Thread.sleep(1000);
            Map<String,Object> second=service.livePreview(JOB_ID,"alice",false,startedAt,"15m","30s",null,null);
            assertEquals("READY",first.get("state"));
            assertEquals("READY",second.get("state"));
            assertEquals(first.get("windowStart"),second.get("windowStart"));
            assertEquals(first.get("windowEnd"),second.get("windowEnd"));
            assertEquals(1,fixture.requests.get(),"Viewers polling the same old-Job window bucket share the cached result.");
        }
    }

    @Test void livePreviewRoundsFractionalStartUpWithoutQueryingBeforeLoadExecution() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(profileWithQueries("up"));
            Map<String, Object> configured = scenario(List.of(binding("prom", null)), "1m", "1m", "15s");
            configured.put("livePreview", Map.of("enabled", true));
            service.writeSnapshot(JOB_ID, service.resolveScenario(configured, "alice", false));
            Instant startedAt = Instant.now().minusSeconds(600);

            service.livePreview(JOB_ID, "alice", false, startedAt, "15m", "30s", null, null);

            long expectedQueryStart = startedAt.getEpochSecond() + (startedAt.getNano() == 0 ? 0 : 1);
            assertEquals(Instant.ofEpochSecond(expectedQueryStart).toString(), fixture.queryStart.get());
            assertFalse(Instant.parse(fixture.queryStart.get()).isBefore(startedAt));
        }
    }

    @Test void livePreviewCoverageUsesChronologicalExtremaAcrossLabelSortedSeries() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(raw -> {
                 long end = Instant.parse(queryParameter(raw, "end")).getEpochSecond();
                 String newer = String.format(Locale.ROOT, "%.3f", (double) end - 10);
                 String older = String.format(Locale.ROOT, "%.3f", (double) end - 100);
                 return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                         + "{\"metric\":{\"instance\":\"a\"},\"values\":[[" + newer + ",\"3.0\"]]},"
                         + "{\"metric\":{\"instance\":\"z\"},\"values\":[[" + older + ",\"1.0\"]]}]}}";
             }); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(profileWithQueries("up"));
            Map<String, Object> configured = scenario(List.of(binding("prom", null)), "1m", "1m", "15s");
            configured.put("livePreview", Map.of("enabled", true));
            service.writeSnapshot(JOB_ID, service.resolveScenario(configured, "alice", false));

            Map<String, Object> preview = service.livePreview(JOB_ID, "alice", false, Instant.now().minusSeconds(600),
                    "15m", "30s", null, null);
            Map<String, Object> item = maps(preview.get("items")).get(0);
            Map<String, Object> coverage = object(item.get("coverage"));
            Instant windowEnd = Instant.parse(String.valueOf(preview.get("windowEnd")));

            assertEquals(windowEnd.minusSeconds(100).toString(), coverage.get("firstSampleAt"));
            assertEquals(windowEnd.minusSeconds(10).toString(), coverage.get("lastSampleAt"));
            assertEquals("READY", item.get("queryStatus"), "The latest timestamp is fresh even though label z sorts after label a.");
        }
    }

    @Test void postRunCollectionSegmentsTwentyFiveHoursAndPreservesLoadWindowAndSealedArtifacts() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(binding("prom", null)), "1h", "1h", "300s"), "alice", false);
            service.writeSnapshot(JOB_ID, snapshot);

            Path job = temp.resolve("jobs").resolve(JOB_ID);
            Files.createDirectories(job.resolve("output"));
            byte[] report = "sealed load report".getBytes(StandardCharsets.UTF_8);
            byte[] archive = "sealed artifact archive".getBytes(StandardCharsets.UTF_8);
            Files.write(job.resolve("output/report.json"), report);
            Files.write(job.resolve("artifacts.zip"), archive);
            Instant finishedAt = Instant.now();
            Instant startedAt = finishedAt.minusSeconds(23 * 3600L);

            service.jobFinished(JOB_ID, startedAt, finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);

            assertEquals("READY", manifest.get("state"));
            assertEquals(startedAt.toString(), manifest.get("startedAt"));
            assertEquals(finishedAt.toString(), manifest.get("finishedAt"));
            assertEquals(startedAt.minusSeconds(3600).toString(), manifest.get("windowStart"));
            assertEquals(finishedAt.plusSeconds(3600).toString(), manifest.get("windowEnd"));
            assertEquals(5, fixture.requests.get(), "A 25-hour range is split into five bounded Prometheus requests.");
            assertArrayEquals(report, Files.readAllBytes(job.resolve("output/report.json")));
            assertArrayEquals(archive, Files.readAllBytes(job.resolve("artifacts.zip")));

            Map<String, Object> file = maps(manifest.get("files")).get(0);
            Path sidecar = job.resolve("sut-metrics").resolve(String.valueOf(file.get("path")));
            byte[] sidecarBytes = Files.readAllBytes(sidecar);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sidecarBytes)), file.get("sha256"));
            Map<?, ?> evidence = ServerRuntime.JSON.readValue(sidecarBytes, Map.class);
            assertEquals("base", evidence.get("profile"));
            assertEquals("base", evidence.get("profilePath"));
            assertEquals("prom", evidence.get("sourceId"));
            assertEquals("sut-1", evidence.get("targetId"));
            assertEquals(1, maps(service.readCollectionPage(JOB_ID,0,1,null,null,null).get("items")).size());
            Files.writeString(sidecar,"tampered sidecar");
            assertThrows(IllegalStateException.class,()->service.readCollectionPage(JOB_ID,0,1,null,null,null),
                    "A changed sidecar must fail the stored checksum before data is returned.");
        }
    }

    @Test void readyCollectionAndInterruptedCollectionRecoverySurviveServiceRestart() throws Exception {
        Path data = temp.resolve("restart-recovery");
        Map<String, Object> snapshot;
        String completedJob = "J0000000000000081";
        String interruptedJob = "J0000000000000082";
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(data)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            snapshot = service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice");
            service.writeSnapshot(completedJob, snapshot);
            Instant finishedAt = Instant.now();
            service.jobFinished(completedJob, finishedAt.minusSeconds(30), finishedAt);
            assertEquals("READY", awaitManifest(service, completedJob).get("state"));
        }

        Path interruptedDirectory = data.resolve("jobs").resolve(interruptedJob).resolve("sut-metrics");
        try (SutMetricsService restarted = new SutMetricsService(data)) {
            Map<String, Object> restored = restarted.collectionStatus(completedJob);
            assertEquals("READY", restored.get("state"));
            Map<String, Object> page = restarted.readCollectionPage(completedJob, 0, 1, null, null, null);
            assertEquals(1, maps(page.get("items")).size(), "Published sidecars remain readable after a Server service restart.");

            restarted.writeSnapshot(interruptedJob, snapshot);
            Path manifest = interruptedDirectory.resolve("manifest.json");
            Map<String, Object> inProgress = ServerRuntime.JSON.convertValue(ServerRuntime.JSON.readTree(manifest.toFile()), Map.class);
            inProgress.put("state", "COLLECTING");
            inProgress.put("complete", Boolean.FALSE);
            Files.write(manifest, ServerRuntime.JSON.writeValueAsBytes(inProgress));
            Files.writeString(interruptedDirectory.resolve("series-0000.json"), "unpublished partial sidecar");
        }

        try (SutMetricsService recovered = new SutMetricsService(data)) {
            Map<String, Object> failed = recovered.collectionStatus(interruptedJob);
            assertEquals("FAILED", failed.get("state"));
            assertEquals(Boolean.FALSE, failed.get("complete"));
            assertEquals("SERVER_RESTART_INTERRUPTED", maps(failed.get("diagnostics")).get(0).get("code"));
            assertFalse(Files.exists(interruptedDirectory.resolve("series-0000.json")),
                    "A sidecar that was never published by the manifest must be discarded during recovery.");
        }
    }

    @Test void postRunStopsAfterThePerSourceSampleBudgetAndMarksUnissuedQueries() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(2); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of("maxSamplesPerJob", 1)));
            service.upsertProfile(baseProfile("base", 2, false));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice", false);
            service.writeSnapshot(JOB_ID, snapshot);

            Instant startedAt = Instant.now().minusSeconds(30), finishedAt = Instant.now();
            service.jobFinished(JOB_ID, startedAt, finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);

            assertEquals("FAILED", manifest.get("state"));
            assertEquals(1, fixture.requests.get(), "No Prometheus requests should be made after the Job sample budget is exceeded.");
            assertTrue(maps(manifest.get("files")).isEmpty());
            assertEquals(2, ((Number) object(manifest.get("coverage")).getOrDefault("failedQueries", -1)).intValue());
            assertEquals(2, maps(manifest.get("diagnostics")).size());
        }
    }

    @Test void postRunCollectionRecordsUnavailablePartialSourceFailureTimeoutAndResponseLimitStates() throws Exception {
        try(SutMetricsService service=new SutMetricsService(temp.resolve("unavailable"))) {
            service.upsertSource(source("prom","http://127.0.0.1:9090",List.of("alice"),Map.of()));
            service.upsertProfile(baseProfile("base",1,false));
            service.writeSnapshot(JOB_ID,service.resolveScenario(scenario(List.of(binding("prom",null)),"1m","1m","15s"),"alice"));

            service.jobFinished(JOB_ID,null,null);

            Map<String,Object> manifest=awaitManifest(service,JOB_ID);
            assertEquals("UNAVAILABLE",manifest.get("state"));
            assertEquals("A trustworthy LOAD execution window was not available.",maps(manifest.get("diagnostics")).get(0).get("message"));
        }

        try(PrometheusFixture fixture=new PrometheusFixture(1,Map.of("up",1,"down",0));
            SutMetricsService service=new SutMetricsService(temp.resolve("partial"))) {
            service.upsertSource(source("prom",fixture.url(),List.of("alice"),Map.of()));
            service.upsertProfile(profileWithQueries("up","down"));
            service.writeSnapshot(JOB_ID,service.resolveScenario(scenario(List.of(binding("prom",null)),"1m","1m","15s"),"alice"));

            Instant finished=Instant.now();service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

            Map<String,Object> manifest=awaitManifest(service,JOB_ID);
            assertEquals("PARTIAL",manifest.get("state"));
            assertEquals(1,((Number)object(manifest.get("coverage")).get("successfulQueries")).intValue());
            assertEquals(1,((Number)object(manifest.get("coverage")).get("emptyQueries")).intValue());
            List<Map<String,Object>> files=maps(manifest.get("files"));
            assertEquals(2,files.size(),"The no-data metric still receives an explicit checksummed sidecar.");
            assertTrue(files.stream().anyMatch(file->"NO_DATA".equals(file.get("queryStatus"))));
        }

        try(PrometheusFixture fixture=new PrometheusFixture(1,Map.of("up",2,"down",2));
            SutMetricsService service=new SutMetricsService(temp.resolve("budget"))) {
            service.upsertSource(source("prom",fixture.url(),List.of("alice"),Map.of("maxSamplesPerJob",3)));
            service.upsertProfile(profileWithQueries("up","down"));
            service.writeSnapshot(JOB_ID,service.resolveScenario(scenario(List.of(binding("prom",null)),"1m","1m","15s"),"alice"));

            Instant finished=Instant.now();service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

            Map<String,Object> manifest=awaitManifest(service,JOB_ID);
            assertEquals("PARTIAL",manifest.get("state"));
            assertEquals(2,fixture.requests.get(),"A total-budget overrun is detected after the second successful Prometheus response.");
            assertEquals(1,maps(manifest.get("files")).size());
            assertEquals(1,((Number)object(manifest.get("coverage")).get("failedQueries")).intValue());
        }

        try(PrometheusFixture fixture=new PrometheusFixture(1,500,0,0,Map.of());
            SutMetricsService service=new SutMetricsService(temp.resolve("http-failure"))) {
            service.upsertSource(source("prom",fixture.url(),List.of("alice"),Map.of()));
            service.upsertProfile(baseProfile("base",1,false));
            service.writeSnapshot(JOB_ID,service.resolveScenario(scenario(List.of(binding("prom",null)),"1m","1m","15s"),"alice"));
            Instant finished=Instant.now();service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

            Map<String,Object> manifest=awaitManifest(service,JOB_ID);
            assertEquals("FAILED",manifest.get("state"));
            assertEquals("PROMETHEUS_HTTP_ERROR",maps(manifest.get("diagnostics")).get(0).get("code"));
            assertTrue(maps(manifest.get("files")).isEmpty());
        }

        try(PrometheusFixture fixture=new PrometheusFixture(1,200,700,0,Map.of());
            SutMetricsService service=new SutMetricsService(temp.resolve("timeout"))) {
            service.upsertSource(source("prom",fixture.url(),List.of("alice"),Map.of("requestTimeoutMs",250)));
            service.upsertProfile(baseProfile("base",1,false));
            service.writeSnapshot(JOB_ID,service.resolveScenario(scenario(List.of(binding("prom",null)),"1m","1m","15s"),"alice"));
            Instant finished=Instant.now();service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

            Map<String,Object> manifest=awaitManifest(service,JOB_ID);
            assertEquals("FAILED",manifest.get("state"));
            assertEquals("PROMETHEUS_TIMEOUT",maps(manifest.get("diagnostics")).get(0).get("code"));
        }

        try(PrometheusFixture fixture=new PrometheusFixture(1,200,0,2048,Map.of());
            SutMetricsService service=new SutMetricsService(temp.resolve("response-limit"))) {
            service.upsertSource(source("prom",fixture.url(),List.of("alice"),Map.of("maxResponseBytes",1024)));
            service.upsertProfile(baseProfile("base",1,false));
            service.writeSnapshot(JOB_ID,service.resolveScenario(scenario(List.of(binding("prom",null)),"1m","1m","15s"),"alice"));
            Instant finished=Instant.now();service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

            Map<String,Object> manifest=awaitManifest(service,JOB_ID);
            assertEquals("FAILED",manifest.get("state"));
            assertEquals("PROMETHEUS_RESPONSE_TOO_LARGE",maps(manifest.get("diagnostics")).get(0).get("code"));
            assertFalse(manifest.toString().contains("http://127.0.0.1"));
        }
    }

    private static Map<String,Object> profileWithQueries(String... queries) {
        List<Map<String,Object>> metrics=new ArrayList<>();
        for(int index=0;index<queries.length;index++)metrics.add(map("id","metric-"+(index+1),"title","Metric "+(index+1),
                "unit","count","kind","gauge","query",queries[index]));
        return map("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile","kind","base",
                "category","app","metrics",metrics,"targets",List.of(map("id","default-target","type","app")));
    }

    private static Map<String, Object> source(String id, String url, List<String> readers, Map<String, Object> limits) {
        return map("schemaVersion", "att-sut-metrics-source/v1", "id", id, "name", "Prometheus " + id, "url", url,
                "readers", readers, "readRoles", List.of(), "limits", limits);
    }

    private static Map<String, Object> baseProfile(String id, int metricCount, boolean parameterized) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (int index = 1; index <= metricCount; index++) metrics.add(map("id", "metric-" + index, "title", "Metric " + index,
                "unit", "count", "kind", "gauge", "query", parameterized ? "up{service=\"{{service}}\"}" : "up"));
        Map<String, Object> target = parameterized
                ? map("id", "default-target", "type", "app", "parameters", Map.of("service", "profile-target"))
                : map("id", "default-target", "type", "app");
        Map<String, Object> profile = map("schemaVersion", "att-sut-metrics-profile/v1", "id", id, "name", "Base profile",
                "kind", "base", "category", "app", "metrics", metrics, "targets", List.of(target));
        if (parameterized) {
            profile.put("params", Map.of("service", Map.of("type", "promLabelValue", "default", "parameter-default")));
            profile.put("parameters", Map.of("service", "profile-default"));
            profile.put("overridableParams", List.of("service"));
        }
        return profile;
    }

    private static Map<String, Object> binding(String source, String scenarioParameter) {
        Map<String, Object> target = scenarioParameter == null
                ? map("id", "sut-1", "type", "app")
                : map("id", "sut-1", "type", "app", "parameters", Map.of("service", "target-default"));
        Map<String, Object> binding = map("profile", "base", "source", source, "targets", List.of(target));
        if (scenarioParameter != null) binding.put("parameters", Map.of("service", scenarioParameter));
        return binding;
    }

    private static Map<String, Object> scenario(List<Map<String, Object>> bindings, String before, String after, String step) {
        return map("schemaVersion", "att-load-observability/v1", "collections", bindings,
                "collection", map("before", before, "after", after, "step", step),
                "livePreview", Map.of("enabled", false));
    }

    private static Map<String, Object> awaitManifest(SutMetricsService service, String jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Map<String, Object> manifest;
        do {
            manifest = service.collectionStatus(jobId);
            String state = String.valueOf(manifest.get("state"));
            if (!"PENDING".equals(state) && !"COLLECTING".equals(state)) return manifest;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("SUT Metrics collection did not finish before the test deadline.");
        return Map.of();
    }

    private static List<Map<String, Object>> maps(Object value) {
        return ServerRuntime.JSON.convertValue(value, ServerRuntime.JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    private static Map<String, Object> object(Object value) { return ServerRuntime.JSON.convertValue(value, Map.class); }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static String queryParameter(String raw, String name) {
        return java.util.Arrays.stream(raw.split("&"))
                .filter(part -> part.startsWith(name + "="))
                .map(part -> URLDecoder.decode(part.substring((name + "=").length()), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }

    private static final class PrometheusFixture implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> authorization = new AtomicReference<>();
        final AtomicReference<String> queryStart = new AtomicReference<>();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final int defaultSamples,responseStatus,responseBytes;
        private final long responseDelayMs;
        private final Map<String,Integer> responseSamplesByQuery;
        private final Function<String, String> responseBodyFactory;

        PrometheusFixture(int samplesPerResponse) throws Exception {
            this(samplesPerResponse,200,0,0,Map.of(),null);
        }

        PrometheusFixture(int samplesPerResponse,Map<String,Integer> responseSamplesByQuery) throws Exception {
            this(samplesPerResponse,200,0,0,responseSamplesByQuery,null);
        }

        PrometheusFixture(int samplesPerResponse,int responseStatus,long responseDelayMs,int responseBytes,
                          Map<String,Integer> responseSamplesByQuery) throws Exception {
            this(samplesPerResponse,responseStatus,responseDelayMs,responseBytes,responseSamplesByQuery,null);
        }

        PrometheusFixture(Function<String, String> responseBodyFactory) throws Exception {
            this(0,200,0,0,Map.of(),responseBodyFactory);
        }

        private PrometheusFixture(int samplesPerResponse,int responseStatus,long responseDelayMs,int responseBytes,
                                  Map<String,Integer> responseSamplesByQuery, Function<String, String> responseBodyFactory) throws Exception {
            this.defaultSamples=samplesPerResponse;this.responseStatus=responseStatus;this.responseDelayMs=responseDelayMs;
            this.responseBytes=responseBytes;this.responseSamplesByQuery=Map.copyOf(responseSamplesByQuery);
            this.responseBodyFactory=responseBodyFactory;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/v1/query_range", exchange -> respond(exchange, samplesPerResponse));
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void respond(HttpExchange exchange, int sampleCount) {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if(responseDelayMs>0)Thread.sleep(responseDelayMs);
                String raw = exchange.getRequestURI().getRawQuery();
                queryStart.set(queryParameter(raw, "start"));
                String query=java.util.Arrays.stream(raw.split("&"))
                        .filter(part->part.startsWith("query="))
                        .map(part->URLDecoder.decode(part.substring("query=".length()),StandardCharsets.UTF_8))
                        .findFirst().orElse("");
                sampleCount=responseSamplesByQuery.getOrDefault(query,defaultSamples);
                String startText = java.util.Arrays.stream(raw.split("&"))
                        .filter(part -> part.startsWith("start="))
                        .map(part -> URLDecoder.decode(part.substring("start=".length()), StandardCharsets.UTF_8))
                        .findFirst().orElse(Instant.now().toString());
                long start = Instant.parse(startText).getEpochSecond();
                StringBuilder values = new StringBuilder();
                for (int index = 0; index < sampleCount; index++) {
                    if (index > 0) values.append(',');
                    values.append('[').append(String.format(Locale.ROOT, "%.3f", (double) (start + index + 1)))
                            .append(",\"").append(index + 1).append(".0\"]");
                }
                String response=responseBodyFactory==null
                        ? "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":"+
                                (sampleCount==0?"[]":"[{\"metric\":{\"instance\":\"sut-1\"},\"values\":["+values+"]}]")+"}}"
                        : responseBodyFactory.apply(raw);
                byte[] body=responseBytes>0?new byte[responseBytes]:response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) { }
            finally { exchange.close(); }
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
