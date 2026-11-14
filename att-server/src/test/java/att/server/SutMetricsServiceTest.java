package att.server;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test void thresholdRulesRequireAnExactAliasForRepeatedBaseProfileBindings() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom-a", fixture.url(), List.of("alice"), Map.of()));
            service.upsertSource(source("prom-b", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> configured = scenario(List.of(
                    map("id", "primary", "profile", "base", "source", "prom-a", "targets", List.of(map("id", "sut-a", "type", "app"))),
                    map("id", "replica", "profile", "base", "source", "prom-b", "targets", List.of(map("id", "sut-b", "type", "app")))),
                    "1m", "1m", "15s");
            configured.put("thresholds", List.of(map("id", "cpu-max", "profile", "base", "metric", "metric-1", "max", 90.0)));

            assertThrows(IllegalArgumentException.class, () -> service.resolveScenario(configured, "alice"),
                    "The base profile name must not silently select two source bindings.");
            configured.put("thresholds", List.of(map("id", "cpu-max", "profile", "replica", "metric", "metric-1", "max", 90.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            Map<String, Object> rule = maps(snapshot.get("thresholds")).get(0);
            assertEquals("replica", rule.get("bindingId"));
            assertEquals(1, ((List<?>) rule.get("queryIds")).size());
            String selectedQueryId = String.valueOf(((List<?>) rule.get("queryIds")).get(0));
            assertEquals("prom-b", maps(snapshot.get("queries")).stream()
                    .filter(query -> selectedQueryId.equals(query.get("id"))).findFirst().orElseThrow().get("sourceId"));
        }
    }

    @Test void oneLevelCompositeBindsBaseProfilesAndOnlyDeclaredScenarioParameterOverridesAreAccepted() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            Map<String, Object> base = map("schemaVersion", "att-sut-metrics-profile/v1", "id", "base", "name", "Base",
                    "kind", "base", "category", "app", "params", Map.of("service", Map.of("type", "promLabelValue", "default", "base")),
                    "parameters", Map.of("service", "base-default"),
                    "overridableParams", List.of("service"),
                    "metrics", List.of(map("id", "requests", "title", "Requests", "unit", "count", "kind", "gauge",
                            "query", "up{service=\"{{service}}\"}")));
            service.upsertProfile(base);
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "preset", "name", "Preset",
                    "kind", "composite", "includes", List.of(map("profile", "base", "source", "prom",
                            "targets", List.of(map("id", "sut-1", "type", "app")), "parameters", Map.of("service", "preset-value")))));

            Map<String, Object> defaulted = service.resolveScenario(scenario(List.of(map("profile", "preset")), "1m", "1m", "15s"), "alice");
            assertEquals("up{service=\"preset-value\"}", maps(defaulted.get("queries")).get(0).get("query"));
            assertThrows(IllegalArgumentException.class, () -> service.resolveScenario(scenario(List.of(
                    map("profile", "preset", "parameters", Map.of("service", "scenario-value"))), "1m", "1m", "15s"), "alice"));

            Map<String, Object> explicitlyOverridden = service.resolveScenario(scenario(List.of(
                    map("profile", "base", "source", "prom", "targets", List.of(map("id", "sut-1", "type", "app")),
                            "parameters", Map.of("service", "scenario-value"))), "1m", "1m", "15s"), "alice");
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

    @Test void derivedExtendsNestedCompositesAndAmbiguousAliasesAreRejected() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", "http://127.0.0.1:9090", List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            assertThrows(IllegalArgumentException.class, () -> service.upsertProfile(map(
                    "schemaVersion", "att-sut-metrics-profile/v1", "id", "bound-base", "kind", "base",
                    "source", "prom", "targets", List.of(map("id", "sut-1")),
                    "metrics", List.of(map("id", "m", "title", "Metric", "unit", "count", "query", "up")))));
            assertThrows(IllegalArgumentException.class, () -> service.upsertProfile(map(
                    "schemaVersion", "att-sut-metrics-profile/v1", "id", "derived", "kind", "derived", "extends", "base")));
            assertThrows(IllegalArgumentException.class, () -> service.upsertProfile(map(
                    "schemaVersion", "att-sut-metrics-profile/v1", "id", "extends", "kind", "base", "extends", "base",
                    "metrics", List.of(map("id", "m", "title", "Metric", "unit", "count", "query", "up")))));
            Map<String,Object> validPreset=map("schemaVersion","att-sut-metrics-profile/v1","id","preset","kind","composite",
                    "includes",List.of(map("profile","base","source","prom","targets",List.of(map("id","sut-1")))));
            service.upsertProfile(validPreset);
            assertThrows(IllegalArgumentException.class, () -> service.upsertProfile(map("schemaVersion","att-sut-metrics-profile/v1",
                    "id","unbound","kind","composite","includes",List.of(map("profile","base")))));
            assertThrows(IllegalArgumentException.class,()->service.upsertProfile(map("schemaVersion","att-sut-metrics-profile/v1",
                    "id","nested","kind","composite","includes",List.of(map("profile","preset")))));
            assertThrows(IllegalArgumentException.class,()->service.upsertProfile(map("schemaVersion","att-sut-metrics-profile/v1",
                    "id","ambiguous","kind","composite","includes",List.of(map("profile","base","source","prom",
                            "targets",List.of(map("id","sut-1"))),map("profile","base","source","prom",
                            "targets",List.of(map("id","sut-2")))))));
            service.upsertProfile(map("schemaVersion","att-sut-metrics-profile/v1","id","aliased","kind","composite",
                    "includes",List.of(map("profile","base","alias","first","source","prom","targets",List.of(map("id","sut-1"))),
                            map("profile","base","alias","second","source","prom","targets",List.of(map("id","sut-2"))))));
            Map<String,Object> resolved=service.resolveScenario(scenario(List.of(map("profile","aliased")),"1m","1m","15s"),"alice");
            assertEquals(List.of("aliased/first","aliased/second"),maps(resolved.get("queries")).stream().map(q->q.get("profilePath")).toList());
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
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::atWindowEndResponse); SutMetricsService service = new SutMetricsService(temp)) {
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
            Instant finishedAt = Instant.now().minusSeconds(7200);
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
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::atWindowEndResponse); SutMetricsService service = new SutMetricsService(data)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            snapshot = service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice");
            service.writeSnapshot(completedJob, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(120);
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
            Map<String, Object> inProgress = restarted.collectionStatus(interruptedJob);
            inProgress.put("state", "COLLECTING");
            inProgress.put("complete", Boolean.FALSE);
            restarted.writeManifest(interruptedDirectory, inProgress);
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

    @Test void postRunWaitsForAfterBufferAndIngestionStepBeforeQueryingPrometheus() throws Exception {
        Instant finishedAt = Instant.now();
        Instant visibleAt = finishedAt.plusSeconds(1);
        AtomicInteger requests = new AtomicInteger();
        try (PrometheusFixture fixture = new PrometheusFixture(raw -> {
                 requests.incrementAndGet();
                 if (Instant.now().isBefore(visibleAt)) return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";
                 return atWindowEndResponse(raw);
             }); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1s", "15s"), "alice");
            service.writeSnapshot(JOB_ID, snapshot);
            Instant startedAt = finishedAt.minusSeconds(30);

            service.jobFinished(JOB_ID, startedAt, finishedAt);
            assertEquals("PENDING", service.collectionStatus(JOB_ID).get("state"));
            assertEquals(0, requests.get(), "Prometheus must not be queried while the after-buffer is still open.");
            Thread.sleep(1200);
            assertEquals("PENDING", service.collectionStatus(JOB_ID).get("state"),
                    "Elapsed after-buffer data remains pending through one query-step ingestion grace.");
            assertEquals(0, requests.get(), "The collector must not query before the persisted eligibleAt boundary.");

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            Map<String, Object> coverage = object(maps(manifest.get("files")).get(0).get("coverage"));
            assertTrue(Instant.parse(String.valueOf(coverage.get("lastSampleAt"))).isAfter(finishedAt));
            assertTrue(requests.get() > 0);
        }
    }

    @Test void postRunObservationMatchesTheProviderNeutralGoldenContract() throws Exception {
        Instant finishedAt = Instant.now().minusSeconds(120), startedAt = finishedAt.minusSeconds(60);
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::atWindowEndResponse);
             SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice");
            service.writeSnapshot(JOB_ID, snapshot);
            List<Map<String, Object>> phases = List.of(
                    phase("RAMP_UP", startedAt, startedAt.plusSeconds(10), false),
                    phase("WARMUP", startedAt.plusSeconds(10), startedAt.plusSeconds(20), false),
                    phase("STEADY", startedAt.plusSeconds(20), startedAt.plusSeconds(50), true),
                    phase("RAMP_DOWN", startedAt.plusSeconds(50), finishedAt, true));

            service.jobFinished(JOB_ID, startedAt, finishedAt, phases);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            Map<String, Object> file = maps(manifest.get("files")).get(0);
            byte[] sidecar = Files.readAllBytes(temp.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics").resolve(String.valueOf(file.get("path"))));
            JsonNode golden;
            try (var input = getClass().getResourceAsStream("/att/server/sut-metrics-observation-v1.golden.json")) {
                assertNotNull(input, "The versioned observation golden fixture must be packaged with the service tests.");
                golden = ServerRuntime.JSON.readTree(input);
            }
            JsonNode actual = ServerRuntime.JSON.readTree(sidecar);
            assertGoldenShape(golden, actual, "$");
            JsonNode observation = actual.path("observation");
            assertEquals(SutMetricsService.OBSERVATION_SCHEMA, observation.path("schemaVersion").asText());
            assertEquals(4, observation.path("window").path("phaseWindows").size());
            assertEquals("FRESH", observation.path("availability").path("freshness").asText());
            assertFalse(new String(sidecar, StandardCharsets.UTF_8).contains(fixture.url()));
            assertFalse(actual.has("query"), "Resolved PromQL is omitted from published observation sidecars.");
            assertFalse(observation.has("query"));
        }
    }

    @Test void postRunThresholdEvaluationPersistsPerTargetEvidenceAndRecoversAfterRestart() throws Exception {
        Path data = temp.resolve("threshold-evaluation-recovery");
        String jobId = "J0000000000000091";
        Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(60);
        Map<String, Object> configured = scenario(List.of(map("id", "platform", "profile", "base", "source", "prom",
                "targets", List.of(map("id", "sut-1", "type", "app")))), "1m", "1m", "15s");
        configured.put("thresholds", List.of(map("id", "cpu-steady-90", "profile", "platform", "metric", "metric-1",
                "max", 90.0, "unit", "count", "minimumCoverage", 0.95)));
        try (PrometheusFixture fixture = new PrometheusFixture(raw -> thresholdResponse(raw, 90.0));
             SutMetricsService service = new SutMetricsService(data)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            assertEquals(1, maps(snapshot.get("thresholds")).size());
            assertEquals("platform", maps(snapshot.get("queries")).get(0).get("bindingId"));
            service.writeSnapshot(jobId, snapshot);
            List<Map<String, Object>> phases = List.of(phase("STEADY", startedAt.plusSeconds(10), finishedAt.minusSeconds(10), true));

            service.jobFinished(jobId, startedAt, finishedAt, phases);
            assertEquals("READY", awaitManifest(service, jobId).get("state"));
            Map<String, Object> evaluation = awaitThresholdEvaluation(service, jobId);
            assertEquals("MET", evaluation.get("state"));
            Map<String, Object> results = object(evaluation.get("sutThresholds"));
            Map<String, Object> threshold = maps(results.get("items")).get(0);
            Map<String, Object> target = maps(threshold.get("targets")).get(0);
            assertEquals("MET", target.get("status"));
            assertEquals(90.0, ((Number) target.get("actual")).doubleValue());
            assertEquals(90.0, ((Number) object(target.get("threshold")).get("value")).doubleValue());
            Path record = data.resolve("jobs").resolve(jobId).resolve("sut-metrics/threshold-evaluation.json");
            assertTrue(Files.isRegularFile(record));
            assertFalse(Files.readString(record).contains(fixture.url()));
        }
        try (SutMetricsService recovered = new SutMetricsService(data)) {
            assertEquals("MET", recovered.thresholdEvaluationStatus(jobId).get("state"));
        }
    }

    @Test void corruptUnrelatedTargetEvidenceDoesNotHideAProvenThresholdViolation() throws Exception {
        Path data = temp.resolve("threshold-corrupt-subject");
        String jobId = "J0000000000000092";
        AtomicBoolean corrupted = new AtomicBoolean();
        Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(60);
        Map<String, Object> configured = scenario(List.of(map("id", "platform", "profile", "base", "source", "prom",
                "targets", List.of(map("id", "sut-a", "type", "app"), map("id", "sut-b", "type", "app")))), "1m", "1m", "15s");
        configured.put("thresholds", List.of(map("id", "cpu-max-90", "profile", "platform", "metric", "metric-1",
                "max", 90.0, "unit", "count", "minimumCoverage", 0.95)));
        try (PrometheusFixture fixture = new PrometheusFixture(raw -> thresholdResponse(raw, 90.1));
             SutMetricsService service = new SutMetricsService(data, Set.of("127.0.0.1"), reference -> null, manifestPath -> {
                 try {
                     Map<String, Object> manifest = ServerRuntime.JSON.readValue(manifestPath.toFile(), Map.class);
                     if (!"READY".equals(manifest.get("state")) || !corrupted.compareAndSet(false, true)) return;
                     Map<String, Object> secondTarget = maps(manifest.get("files")).stream()
                             .filter(file -> "sut-b".equals(file.get("targetId"))).findFirst().orElseThrow();
                     Files.writeString(manifestPath.getParent().resolve(String.valueOf(secondTarget.get("path"))), "{}");
                 } catch (Exception failure) { throw new IllegalStateException(failure); }
             })) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            service.writeSnapshot(jobId, snapshot);
            service.jobFinished(jobId, startedAt, finishedAt,
                    List.of(phase("STEADY", startedAt.plusSeconds(10), finishedAt.minusSeconds(10), true)));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, jobId);
            assertTrue(corrupted.get(), "The second target sidecar should be corrupted before evaluation starts.");
            assertEquals("NOT_MET", evaluation.get("state"));
            List<Map<String, Object>> targets = maps(maps(object(evaluation.get("sutThresholds")).get("items")).get(0).get("targets"));
            assertTrue(targets.stream().anyMatch(target -> "sut-a".equals(object(target.get("target")).get("id"))
                    && "NOT_MET".equals(target.get("status"))), targets.toString());
            assertTrue(targets.stream().anyMatch(target -> "sut-b".equals(object(target.get("target")).get("id"))
                    && "NOT_EVALUATED".equals(target.get("status"))), targets.toString());
        }
    }

    @Test void timeoutOnSecondSubjectCannotEraseAnAlreadyProvenViolation() throws Exception {
        Path data = temp.resolve("threshold-timeout-after-violation");
        String jobId = "J0000000000000094";
        AtomicInteger evidenceReads = new AtomicInteger();
        AtomicBoolean secondSubjectTimedOut = new AtomicBoolean();
        Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(60);
        Map<String, Object> configured = scenario(List.of(map("id", "platform", "profile", "base", "source", "prom",
                "targets", List.of(map("id", "sut-a", "type", "app"), map("id", "sut-b", "type", "app")))), "1m", "1m", "15s");
        configured.put("thresholds", List.of(map("id", "cpu-max-90", "profile", "platform", "metric", "metric-1",
                "max", 90.0, "unit", "count", "minimumCoverage", 0.95)));
        try (PrometheusFixture fixture = new PrometheusFixture(raw -> thresholdResponse(raw, 90.1));
             SutMetricsService service = new SutMetricsService(data, Set.of("127.0.0.1"), reference -> null,
                     ignored -> { }, queryId -> {
                         if (evidenceReads.incrementAndGet() == 2) {
                             secondSubjectTimedOut.set(true);
                             throw new SutThresholdEvaluator.EvaluationTimeoutException();
                         }
                     })) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            service.writeSnapshot(jobId, snapshot);
            service.jobFinished(jobId, startedAt, finishedAt,
                    List.of(phase("STEADY", startedAt.plusSeconds(10), finishedAt.minusSeconds(10), true)));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, jobId);
            assertEquals(1, evidenceReads.get(), "The proven violation should finalize before the later subject is read.");
            assertFalse(secondSubjectTimedOut.get());
            assertEquals("NOT_MET", evaluation.get("state"));
            List<Map<String, Object>> targets = maps(maps(object(evaluation.get("sutThresholds")).get("items")).get(0).get("targets"));
            assertEquals(1, targets.stream().filter(target -> "NOT_MET".equals(target.get("status"))).count());
            assertEquals(1, targets.stream().filter(target -> "NOT_EVALUATED".equals(target.get("status"))).count());
        }
    }

    @Test void optionalViolationDoesNotShortCircuitLaterRequiredEvidence() throws Exception {
        Path data = temp.resolve("threshold-optional-violation-before-required");
        String jobId = "J0000000000000095";
        Instant finishedAt = Instant.now().minusSeconds(180), startedAt = finishedAt.minusSeconds(60);
        Map<String, Object> configured = scenario(List.of(binding("prom", null)), "1m", "1m", "15s");
        configured.put("thresholds", List.of(
                map("id", "optional-first", "profile", "base", "metric", "metric-1", "max", 90.0,
                        "unit", "count", "minimumCoverage", 0.95, "required", false),
                map("id", "required-second", "profile", "base", "metric", "metric-2", "max", 90.0,
                        "unit", "count", "minimumCoverage", 0.95, "required", true)));
        try (PrometheusFixture fixture = new PrometheusFixture(raw ->
                thresholdResponse(raw, "up".equals(queryParameter(raw, "query")) ? 90.1 : 90.0));
             SutMetricsService service = new SutMetricsService(data)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(profileWithQueries("up", "process_cpu_seconds_total"));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            assertEquals(List.of("prom/base/sut-1/metric-1", "prom/base/sut-1/metric-2"),
                    maps(snapshot.get("queries")).stream().map(query -> query.get("id")).toList());
            service.writeSnapshot(jobId, snapshot);
            service.jobFinished(jobId, startedAt, finishedAt,
                    List.of(phase("STEADY", startedAt.plusSeconds(10), finishedAt.minusSeconds(10), true)));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, jobId);
            assertEquals("MET", evaluation.get("state"));
            List<Map<String, Object>> items = maps(object(evaluation.get("sutThresholds")).get("items"));
            assertEquals("NOT_MET", items.get(0).get("status"), "Optional evidence should still be evaluated.");
            assertFalse((Boolean) items.get(0).get("required"));
            assertEquals("MET", items.get(1).get("status"), "The later required subject must be evaluated.");
            assertTrue((Boolean) items.get(1).get("required"));
        }
    }

    @Test void pendingAndErrorThresholdRecordsMatchThePublishedSchemaWithoutNullResults() throws Exception {
        Path data = temp.resolve("threshold-evaluation-schema");
        String jobId = "J0000000000000093";
        Path schema = Path.of("schemas/att-sut-threshold-evaluation-v1.schema.json");
        try (PrometheusFixture fixture = new PrometheusFixture(1); SutMetricsService service = new SutMetricsService(data)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> configured = scenario(List.of(map("id", "platform", "profile", "base", "source", "prom",
                    "targets", List.of(map("id", "sut-1", "type", "app")))), "1m", "1m", "15s");
            configured.put("thresholds", List.of(map("id", "cpu-max", "profile", "platform", "metric", "metric-1", "max", 90.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            service.writeSnapshot(jobId, snapshot);
            Path recordPath = data.resolve("jobs").resolve(jobId).resolve("sut-metrics/threshold-evaluation.json");
            String pending = Files.readString(recordPath);
            assertFalse(ServerRuntime.JSON.readTree(pending).has("sutThresholds"));
            att.validation.JsonSchemaVerifier.verifyJson(schema, pending);

            Map<String, Object> error = service.evaluationError(snapshot, "FAILED", "EVALUATION_FAILED", "bounded test error", 2);
            service.writeThresholdEvaluation(jobId, snapshot, error);
            String failed = Files.readString(recordPath);
            assertEquals("ERROR", ServerRuntime.JSON.readTree(failed).path("state").asText());
            assertFalse(ServerRuntime.JSON.readTree(failed).has("sutThresholds"));
            att.validation.JsonSchemaVerifier.verifyJson(schema, failed);
        }
    }

    @Test void collectionManifestRejectsIdentityAndStateOrFileListTampering() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::atWindowEndResponse);
             SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            service.writeSnapshot(JOB_ID, service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice"));
            Instant finishedAt = Instant.now().minusSeconds(120);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);
            Map<String, Object> completed = awaitManifest(service, JOB_ID);
            byte[] originalManifest = Files.readAllBytes(temp.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics/manifest.json"));
            Map<String, Object> file = maps(completed.get("files")).get(0);
            Path sidecar = temp.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics").resolve(String.valueOf(file.get("path")));
            byte[] originalSidecar = Files.readAllBytes(sidecar);
            Path manifestPath = temp.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics/manifest.json");

            Map<String, Object> wrongIdentity = object(ServerRuntime.JSON.readValue(originalManifest, Map.class));
            wrongIdentity.put("jobId", "J0000000000000099");
            Files.write(manifestPath, ServerRuntime.JSON.writeValueAsBytes(wrongIdentity));
            assertThrows(IllegalStateException.class, () -> service.collectionStatus(JOB_ID));

            Files.write(manifestPath, originalManifest);
            Map<String, Object> wrongSnapshot = object(ServerRuntime.JSON.readValue(originalManifest, Map.class));
            wrongSnapshot.put("snapshotId", "different-snapshot");
            Files.write(manifestPath, ServerRuntime.JSON.writeValueAsBytes(wrongSnapshot));
            assertThrows(IllegalStateException.class, () -> service.collectionStatus(JOB_ID));

            Files.write(manifestPath, originalManifest);
            Map<String, Object> alteredState = object(ServerRuntime.JSON.readValue(originalManifest, Map.class));
            alteredState.put("state", "PARTIAL");
            alteredState.put("complete", Boolean.FALSE);
            Files.write(manifestPath, ServerRuntime.JSON.writeValueAsBytes(alteredState));
            assertThrows(IllegalStateException.class, () -> service.collectionStatus(JOB_ID));

            Files.write(manifestPath, originalManifest);
            Map<String, Object> alteredPublication = object(ServerRuntime.JSON.readValue(originalManifest, Map.class));
            alteredPublication.put("files", List.of());
            Files.write(manifestPath, ServerRuntime.JSON.writeValueAsBytes(alteredPublication));
            assertThrows(IllegalStateException.class, () -> service.collectionStatus(JOB_ID));
            assertArrayEquals(originalSidecar, Files.readAllBytes(sidecar), "Manifest tampering leaves the published series file unchanged.");

            Files.write(manifestPath, originalManifest);
            assertEquals("READY", service.collectionStatus(JOB_ID).get("state"));
        }
    }

    @Test void manifestPublicationSurvivesRestartImmediatelyAfterItsAtomicRename() throws Exception {
        Path data = temp.resolve("manifest-atomic-recovery");
        AtomicBoolean interruptAfterNextPublication = new AtomicBoolean();
        try (PrometheusFixture fixture = new PrometheusFixture(1)) {
            try (SutMetricsService service = new SutMetricsService(data, Set.of(), ignored -> null, path -> {
                if ("manifest.json".equals(path.getFileName().toString())
                        && interruptAfterNextPublication.compareAndSet(true, false))
                    throw new IllegalStateException("simulated process stop after atomic manifest rename");
            })) {
                service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
                service.upsertProfile(baseProfile("base", 1, false));
                service.writeSnapshot(JOB_ID, service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice"));
                Map<String, Object> collecting = service.collectionStatus(JOB_ID);
                collecting.put("state", "COLLECTING");
                collecting.put("complete", Boolean.FALSE);
                interruptAfterNextPublication.set(true);
                Path directory = data.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics");
                assertThrows(IllegalStateException.class, () -> service.writeManifest(directory, collecting));
            }

            Path directory = data.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics");
            assertTrue(ServerRuntime.JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json"))).has("_integrity"));
            assertFalse(Files.exists(directory.resolve("manifest-integrity.json")),
                    "Manifest state and integrity history must publish in one atomic file.");
            try (SutMetricsService recovered = new SutMetricsService(data)) {
                Map<String, Object> status = recovered.collectionStatus(JOB_ID);
                assertEquals("FAILED", status.get("state"), "Restart must recover COLLECTING rather than report false READY evidence.");
                assertEquals("SERVER_RESTART_INTERRUPTED", maps(status.get("diagnostics")).get(0).get("code"));
            }
        }
    }

    @Test void manifestWritesForDistinctJobsDoNotBlockEachOther() throws Exception {
        String firstJob = JOB_ID, secondJob = "J0000000000000002";
        Path data = temp.resolve("independent-job-manifests");
        AtomicBoolean gatePublications = new AtomicBoolean();
        CountDownLatch bothPublished = new CountDownLatch(2), releasePublications = new CountDownLatch(1);
        try (PrometheusFixture fixture = new PrometheusFixture(1);
             SutMetricsService service = new SutMetricsService(data, Set.of(), ignored -> null, path -> {
                 if (!gatePublications.get() || !"manifest.json".equals(path.getFileName().toString())) return;
                 bothPublished.countDown();
                 try {
                     if (!releasePublications.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test publication gate timed out");
                 } catch (InterruptedException interrupted) {
                     Thread.currentThread().interrupt();
                     throw new IllegalStateException("test publication gate was interrupted", interrupted);
                 }
             })) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice");
            service.writeSnapshot(firstJob, snapshot);
            service.writeSnapshot(secondJob, snapshot);
            Map<String, Object> first = service.collectionStatus(firstJob), second = service.collectionStatus(secondJob);
            first.put("state", "COLLECTING"); first.put("complete", Boolean.FALSE);
            second.put("state", "COLLECTING"); second.put("complete", Boolean.FALSE);
            ExecutorService writes = Executors.newFixedThreadPool(2);
            gatePublications.set(true);
            try {
                Future<?> firstWrite = writes.submit(() -> {
                    service.writeManifest(data.resolve("jobs").resolve(firstJob).resolve("sut-metrics"), first);
                    return null;
                });
                Future<?> secondWrite = writes.submit(() -> {
                    service.writeManifest(data.resolve("jobs").resolve(secondJob).resolve("sut-metrics"), second);
                    return null;
                });
                assertTrue(bothPublished.await(5, TimeUnit.SECONDS), "Distinct Job manifests should publish without a service-wide lock.");
                releasePublications.countDown();
                firstWrite.get(5, TimeUnit.SECONDS);
                secondWrite.get(5, TimeUnit.SECONDS);
            } finally {
                releasePublications.countDown();
                gatePublications.set(false);
                writes.shutdownNow();
            }
            assertEquals("COLLECTING", service.collectionStatus(firstJob).get("state"));
            assertEquals("COLLECTING", service.collectionStatus(secondJob).get("state"));
        }
    }

    @Test void sampledSeriesWithoutAnAfterBufferSampleIsPartialNotReady() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(2); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            service.writeSnapshot(JOB_ID, service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice"));
            Instant finishedAt = Instant.now().minusSeconds(120);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("PARTIAL", manifest.get("state"));
            Map<String, Object> file = maps(manifest.get("files")).get(0);
            assertEquals("PARTIAL", file.get("queryStatus"));
            assertEquals("POST_BUFFER_NOT_OBSERVED", maps(manifest.get("diagnostics")).get(0).get("code"));
        }
    }

    @Test void earlyPostFinishSampleWithMissingAfterBufferTailIsPartial() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(raw -> sampleAtWindowEndOffsetResponse(raw, 300));
             SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            service.writeSnapshot(JOB_ID, service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1h", "15s"), "alice"));
            Instant finishedAt = Instant.now().minusSeconds(7200);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("PARTIAL", manifest.get("state"));
            Map<String, Object> file = maps(manifest.get("files")).get(0);
            assertEquals("PARTIAL", file.get("queryStatus"));
            assertTrue(Instant.parse(String.valueOf(object(file.get("coverage")).get("lastSampleAt"))).isAfter(finishedAt));
            Map<String, Object> sidecar = ServerRuntime.JSON.readValue(Files.readAllBytes(temp.resolve("jobs").resolve(JOB_ID)
                    .resolve("sut-metrics").resolve(String.valueOf(file.get("path")))), Map.class);
            assertEquals("STALE", object(object(sidecar.get("observation")).get("availability")).get("freshness"));
            assertEquals("POST_BUFFER_NOT_OBSERVED", maps(manifest.get("diagnostics")).get(0).get("code"));
        }
    }

    @Test void postRunStopsAfterThePerSourceSampleBudgetAndMarksUnissuedQueries() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(2); SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of("maxSamplesPerJob", 1)));
            service.upsertProfile(baseProfile("base", 2, false));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(binding("prom", null)), "1m", "1m", "15s"), "alice", false);
            service.writeSnapshot(JOB_ID, snapshot);

            Instant finishedAt = Instant.now().minusSeconds(120), startedAt = finishedAt.minusSeconds(30);
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

            Instant finished=Instant.now().minusSeconds(120);service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

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

            Instant finished=Instant.now().minusSeconds(120);service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

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
            Instant finished=Instant.now().minusSeconds(120);service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

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
            Instant finished=Instant.now().minusSeconds(120);service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

            Map<String,Object> manifest=awaitManifest(service,JOB_ID);
            assertEquals("FAILED",manifest.get("state"));
            assertEquals("PROMETHEUS_TIMEOUT",maps(manifest.get("diagnostics")).get(0).get("code"));
        }

        try(PrometheusFixture fixture=new PrometheusFixture(1,200,0,2048,Map.of());
            SutMetricsService service=new SutMetricsService(temp.resolve("response-limit"))) {
            service.upsertSource(source("prom",fixture.url(),List.of("alice"),Map.of("maxResponseBytes",1024)));
            service.upsertProfile(baseProfile("base",1,false));
            service.writeSnapshot(JOB_ID,service.resolveScenario(scenario(List.of(binding("prom",null)),"1m","1m","15s"),"alice"));
            Instant finished=Instant.now().minusSeconds(120);service.jobFinished(JOB_ID,finished.minusSeconds(30),finished);

            Map<String,Object> manifest=awaitManifest(service,JOB_ID);
            assertEquals("FAILED",manifest.get("state"));
            assertEquals("PROMETHEUS_RESPONSE_TOO_LARGE",maps(manifest.get("diagnostics")).get(0).get("code"));
            assertFalse(manifest.toString().contains("http://127.0.0.1"));
        }
    }

    @Test void businessCallValuePathsNormalizeTypedDatabaseAndToolResults() {
        assertEquals(12.0, SutMetricsService.numericMetricValue(12, null));
        assertEquals(7.0, SutMetricsService.numericMetricValue(map("rows", List.of(map("count", 7))), "rows[0].count"));
        assertEquals(8.0, SutMetricsService.numericMetricValue(List.of(map("count", 8)), "rows[0].count"));
        assertEquals(9.0, SutMetricsService.numericMetricValue(map("result", map("total", 9)), "result.total"));
        assertThrows(IllegalArgumentException.class, () -> SutMetricsService.numericMetricValue(map("count", 1), null));
        assertThrows(IllegalArgumentException.class, () -> SutMetricsService.numericMetricValue(null, null));
        assertThrows(IllegalArgumentException.class, () -> SutMetricsService.numericMetricValue("not-a-number", null));
        assertThrows(IllegalArgumentException.class, () -> SutMetricsService.numericMetricValue(Double.NaN, null));
        assertThrows(IllegalArgumentException.class, () -> SutMetricsService.numericMetricValue(Double.POSITIVE_INFINITY, null));
        assertThrows(IllegalArgumentException.class, () -> SutMetricsService.numericMetricValue(List.of(), "rows[0].count"));
    }

    @Test void businessCallExecutionPayloadIsPrivateAndCollectionTracksTypedCallsAndSteadyWindow() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp)) {
            List<Map<String, Object>> queries = List.of(
                    businessQuery("scalar", "#{db.orders.scalar(sql='select 1')}", "DBHELPER", null, "afterDrain", null),
                    businessQuery("db-query", "#{db.orders.query(sql='select count(*) from orders')}", "DBHELPER", "rows[0].count", "afterDrain", null),
                    businessQuery("tool-list", "#{reporter(summary='sensitive-tool-argument')}", "PACKAGE_TOOL", "rows[0].count", "afterDrain", null),
                    businessQuery("steady", "#{db.orders.query(sql='select 4')}", "DBHELPER", "total", "windowQuery", "load-a"));
            Map<String, Object> snapshot = businessSnapshot(queries);
            service.writeSnapshot(JOB_ID, snapshot);

            Path metrics = temp.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics");
            String publicSnapshot = Files.readString(metrics.resolve("snapshot.json"));
            assertFalse(publicSnapshot.contains("sensitive-tool-argument"));
            assertFalse(publicSnapshot.contains("select count(*)"));
            assertFalse(service.snapshotForJob(JOB_ID).toString().contains("sensitive-tool-argument"));
            assertTrue(Files.readString(metrics.resolve("business-calls.json")).contains("sensitive-tool-argument"));
            try {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(metrics.resolve("business-calls.json"))));
            } catch (UnsupportedOperationException ignored) { }

            AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
            AtomicReference<Instant> steadyStart = new AtomicReference<>(), steadyEnd = new AtomicReference<>();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> {
                assertEquals(JOB_ID, jobId);
                assertTrue(maps(executionSnapshot.get("queries")).stream().allMatch(query -> query.containsKey("call")));
                opened.incrementAndGet();
                return new SutMetricsService.BusinessCallSession() {
                    @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                    Instant executionFinishedAt, Instant actualSteadyStart,
                                                    Instant actualSteadyEnd, Instant observedAt) {
                        assertNotNull(query.get("call"));
                        if ("steady".equals(query.get("metricId"))) {
                            steadyStart.set(actualSteadyStart); steadyEnd.set(actualSteadyEnd);
                            assertEquals("load-a", query.get("workloadId"));
                        }
                        return switch (String.valueOf(query.get("metricId"))) {
                            case "scalar" -> 3;
                            case "db-query" -> map("rows", List.of(map("count", 7)));
                            case "tool-list" -> List.of(map("count", 8));
                            default -> map("total", 11);
                        };
                    }
                    @Override public void close() { closed.incrementAndGet(); }
                };
            });
            Instant startedAt = Instant.now().minusSeconds(180), finishedAt = Instant.now().minusSeconds(60);
            Instant expectedSteadyStart = startedAt.plusSeconds(20), expectedSteadyEnd = finishedAt.minusSeconds(20);
            service.jobFinished(JOB_ID, startedAt, finishedAt,
                    List.of(phase("STEADY", expectedSteadyStart, expectedSteadyEnd, true)));
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);

            assertEquals("READY", manifest.get("state"));
            Map<String, Object> coverage = object(manifest.get("coverage"));
            assertEquals(4, coverage.get("businessCallSubmitted"));
            assertEquals(4, coverage.get("businessCallStarted"));
            assertEquals(4, coverage.get("businessCallSucceeded"));
            assertEquals(0, coverage.get("businessCallFailed"));
            assertEquals(0, coverage.get("businessCallTimedOut"));
            assertEquals(0, coverage.get("businessCallSkipped"));
            assertTrue(((Number) coverage.get("businessCallDurationMs")).longValue() >= 0);
            assertEquals(4, opened.get()); assertEquals(4, closed.get());
            assertEquals(expectedSteadyStart, steadyStart.get()); assertEquals(expectedSteadyEnd, steadyEnd.get());
            Map<String, Object> page = service.readCollectionPage(JOB_ID, 0, 16, null, null, null);
            assertFalse(page.toString().contains("sensitive-tool-argument"));
            assertFalse(service.collectionStatus(JOB_ID).toString().contains("select count(*)"));
        }
    }

    @Test void timedOutBusinessCallIsCancelledAndRemainingCallsAreRecordedAsSkipped() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-timeout"))) {
            service.setBusinessCallLimitsForTesting(2, 2_000, 250);
            service.writeSnapshot(JOB_ID, businessSnapshot(List.of(
                    businessQuery("timeout", "#{db.orders.query(sql='select sleep')}", "DBHELPER", null, "afterDrain", null),
                    businessQuery("skipped", "#{db.orders.scalar(sql='select 2')}", "DBHELPER", null, "afterDrain", null))));
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger cancelled = new AtomicInteger(), closed = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, snapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) throws Exception {
                    entered.countDown();
                    new CountDownLatch(1).await();
                    return 1;
                }
                @Override public void cancel() { cancelled.incrementAndGet(); }
                @Override public void close() { closed.incrementAndGet(); }
            });
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "The business call should begin before its timeout expires.");
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);

            assertEquals("FAILED", manifest.get("state"));
            Map<String, Object> coverage = object(manifest.get("coverage"));
            assertEquals(1, coverage.get("businessCallSubmitted"));
            assertEquals(1, coverage.get("businessCallStarted"));
            assertEquals(0, coverage.get("businessCallSucceeded"));
            assertEquals(1, coverage.get("businessCallFailed"));
            assertEquals(1, coverage.get("businessCallTimedOut"));
            assertEquals(1, coverage.get("businessCallSkipped"));
            assertTrue(((Number) coverage.get("businessCallDurationMs")).longValue() >= 200);
            assertTrue(cancelled.get() >= 1);
            long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (closed.get() == 0 && System.nanoTime() < closeDeadline) Thread.sleep(10);
            assertEquals(1, closed.get());
            assertTrue(maps(manifest.get("diagnostics")).stream().anyMatch(d -> "BUSINESS_CALL_NOT_EXECUTED".equals(d.get("code"))));
        }
    }

    @Test void timedOutCallDoesNotExecuteWhenDelayedSessionOpenCompletes() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-open-timeout"))) {
            service.setBusinessCallLimitsForTesting(1, 2_000, 250);
            service.writeSnapshot(JOB_ID, businessSnapshot(List.of(
                    businessQuery("late-open", "#{db.orders.scalar(sql='select 1')}", "DBHELPER", null, "afterDrain", null))));
            CountDownLatch openEntered = new CountDownLatch(1), releaseOpen = new CountDownLatch(1);
            AtomicInteger executed = new AtomicInteger(), closed = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, snapshot) -> {
                openEntered.countDown();
                boolean interrupted = false;
                while (true) {
                    try { releaseOpen.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return new SutMetricsService.BusinessCallSession() {
                    @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                    Instant executionFinishedAt, Instant steadyStart,
                                                    Instant steadyEnd, Instant observedAt) {
                        executed.incrementAndGet(); return 1;
                    }
                    @Override public void close() { closed.incrementAndGet(); }
                };
            });
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            try {
                assertTrue(openEntered.await(5, TimeUnit.SECONDS), "Session setup should begin before the call timeout.");
                Map<String, Object> manifest = awaitManifest(service, JOB_ID);
                assertEquals("FAILED", manifest.get("state"));
                assertEquals(1, object(manifest.get("coverage")).get("businessCallTimedOut"));
                releaseOpen.countDown();
                long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (closed.get() == 0 && System.nanoTime() < closeDeadline) Thread.sleep(10);
                assertEquals(0, executed.get(), "A session opened after timeout must never execute the call.");
                assertEquals(1, closed.get());
            } finally {
                releaseOpen.countDown();
            }
        }
    }

    @Test void businessCallCountBudgetSkipsCallsAfterTheConfiguredPerJobLimit() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-count-budget"))) {
            service.setBusinessCallLimitsForTesting(1, 2_000, 1_000);
            service.writeSnapshot(JOB_ID, businessSnapshot(List.of(
                    businessQuery("first", "#{db.orders.scalar(sql='select 1')}", "DBHELPER", null, "afterDrain", null),
                    businessQuery("second", "#{db.orders.scalar(sql='select 2')}", "DBHELPER", null, "afterDrain", null))));
            AtomicInteger executed = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, snapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    executed.incrementAndGet(); return 1;
                }
                @Override public void close() { }
            });
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);

            assertEquals("PARTIAL", manifest.get("state"));
            Map<String, Object> coverage = object(manifest.get("coverage"));
            assertEquals(1, coverage.get("businessCallSubmitted"));
            assertEquals(1, coverage.get("businessCallStarted"));
            assertEquals(1, coverage.get("businessCallSucceeded"));
            assertEquals(1, coverage.get("businessCallSkipped"));
            assertEquals(1, executed.get());
            assertTrue(maps(manifest.get("diagnostics")).stream().anyMatch(d -> "BUSINESS_CALL_BUDGET_EXCEEDED".equals(d.get("code"))));
        }
    }

    @Test void businessCallProfilesAbovePerJobLimitAreRejectedAtAdmission() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-admission-budget"))) {
            List<Map<String, Object>> metrics = new ArrayList<>();
            for (int index = 1; index <= SutMetricsService.MAX_BUSINESS_CALLS_PER_JOB + 1; index++)
                metrics.add(map("id", "count-" + index, "title", "Count " + index, "unit", "count", "kind", "gauge",
                        "call", "#{db.orders.scalar(sql='select " + index + "')}"));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "over-budget",
                    "name", "Over budget", "kind", "base", "category", "db", "metrics", metrics));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> service.resolveScenario(scenario(List.of(map("profile", "over-budget")), "1m", "1m", "15s"), "alice"));
            assertTrue(failure.getMessage().contains("16 business-call per-Job limit"));
        }
    }

    @Test void businessMetricRejectsCommandToolErrorEvenWhenOutputIsUsable() {
        Map<String, Object> invocation = map("status", "ERROR", "exitCode", 1, "output", "42");
        att.exec.ToolInvocationResult result = new att.exec.ToolInvocationResult("reporter", "metric-1", "42",
                invocation, true);
        assertFalse(ServerRuntime.businessMetricInvocationSucceeded(result));
        assertTrue(ServerRuntime.businessMetricInvocationSucceeded(new att.exec.ToolInvocationResult(
                "reporter", "metric-2", "42", map("status", "PASS", "exitCode", 0), true)));
    }

    @Test void businessCallThresholdsPersistMetNotMetAndNotEvaluatedResults() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-thresholds"))) {
            service.upsertProfile(businessProfile("business", "#{db.orders.scalar(sql='select count(*) from orders')}"));
            Map<String, Object> configured = scenario(List.of(map("profile", "business")), "1m", "1m", "15s");
            configured.put("thresholds", List.of(map("id", "count-max", "profile", "business", "metric", "count", "max", 10.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            assertEquals("afterDrain", maps(snapshot.get("thresholds")).get(0).get("window"));

            List<Object> results = java.util.Arrays.asList(7, 12, null);
            List<String> expectedStates = List.of("MET", "NOT_MET", "NOT_EVALUATED");
            for (int index = 0; index < results.size(); index++) {
                String jobId = String.format(Locale.ROOT, "J%016X", 0x410L + index);
                Object result = results.get(index);
                service.setBusinessCallExecutor((ignoredJob, ignoredSnapshot) -> new SutMetricsService.BusinessCallSession() {
                    @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                    Instant executionFinishedAt, Instant steadyStart,
                                                    Instant steadyEnd, Instant observedAt) { return result; }
                    @Override public void close() { }
                });
                service.writeSnapshot(jobId, snapshot);
                Instant finishedAt = Instant.now().minusSeconds(30);
                service.jobFinished(jobId, finishedAt.minusSeconds(60), finishedAt);
                Map<String, Object> manifest = awaitManifest(service, jobId);
                assertTrue(Set.of("READY", "FAILED").contains(manifest.get("state")));
                Map<String, Object> evaluation = awaitThresholdEvaluation(service, jobId);
                assertEquals(expectedStates.get(index), evaluation.get("state"));
                Map<String, Object> rule = maps(object(evaluation.get("sutThresholds")).get("items")).get(0);
                assertEquals(expectedStates.get(index), rule.get("status"));
            }
        }
    }

    @Test void businessCallContextAdmissionOnlyLooksAtSelectedProfiles() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertProfile(baseProfile("prom", 1, false));
            service.upsertProfile(businessProfile("business", "#{db.orders.scalar(sql='select 1')}"));
            assertFalse(service.scenarioRequiresBusinessCallContext(scenario(List.of(binding("missing-source", null)), "1m", "1m", "15s")));
            assertTrue(service.scenarioRequiresBusinessCallContext(scenario(List.of(map("profile", "business")), "1m", "1m", "15s")));
        }
    }

    private static Map<String, Object> businessSnapshot(List<Map<String, Object>> queries) {
        return map("schemaVersion", SutMetricsService.SNAPSHOT_SCHEMA, "snapshotId", "business-snapshot",
                "createdAt", Instant.now().toString(), "collection", map("mode", "post-run", "before", "1s", "after", "1s", "step", "15s"),
                "livePreview", map("enabled", false), "provenance", List.of(), "queries", queries, "sources", Map.of(),
                "execution", map("packageId", "pkg", "environment", "test", "packageRevision", "a".repeat(64),
                        "runtimeConfigRevision", "b".repeat(64)), "thresholds", List.of());
    }

    private static Map<String, Object> businessQuery(String id, String call, String sourceType, String valuePath,
                                                     String sampling, String workloadId) {
        boolean workload = workloadId != null;
        return map("id", "att-call/profile/" + id, "profile", "business", "profilePath", "business",
                "sourceId", "DBHELPER".equals(sourceType) ? "dbhelper:orders" : "package-tool:reporter",
                "sourceName", "DBHELPER".equals(sourceType) ? "DBHelper orders" : "Package Tool reporter",
                "sourceType", sourceType, "targetId", workload ? workloadId : "job", "targetType", workload ? "workload" : "job",
                "metricId", id, "title", id, "category", "db", "unit", "count", "kind", "gauge",
                "observationType", "business-call", "observationKind", "windowQuery".equals(sampling) ? "window-aggregate" : "timed-snapshot",
                "sampling", sampling, "window", "windowQuery".equals(sampling) ? "steady" : null, "call", call,
                "callType", sourceType, "callName", "DBHELPER".equals(sourceType) ? "orders" : "reporter",
                "valuePath", valuePath, "scope", workload ? "workload" : "job", "workloadId", workloadId,
                "sourceRevision", "b".repeat(64), "profileRevision", "c".repeat(64), "targetRevision", "a".repeat(64));
    }

    private static Map<String, Object> businessProfile(String id, String call) {
        return map("schemaVersion", "att-sut-metrics-profile/v1", "id", id, "name", id, "kind", "base", "category", "db",
                "metrics", List.of(map("id", "count", "title", "Count", "unit", "count", "kind", "gauge", "call", call)));
    }

    private static Map<String,Object> profileWithQueries(String... queries) {
        List<Map<String,Object>> metrics=new ArrayList<>();
        for(int index=0;index<queries.length;index++)metrics.add(map("id","metric-"+(index+1),"title","Metric "+(index+1),
                "unit","count","kind","gauge","query",queries[index]));
        return map("schemaVersion","att-sut-metrics-profile/v1","id","base","name","Base profile","kind","base",
                "category","app","metrics",metrics);
    }

    private static Map<String, Object> source(String id, String url, List<String> readers, Map<String, Object> limits) {
        return map("schemaVersion", "att-sut-metrics-source/v1", "id", id, "name", "Prometheus " + id, "url", url,
                "readers", readers, "readRoles", List.of(), "limits", limits);
    }

    private static Map<String, Object> baseProfile(String id, int metricCount, boolean parameterized) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (int index = 1; index <= metricCount; index++) metrics.add(map("id", "metric-" + index, "title", "Metric " + index,
                "unit", "count", "kind", "gauge", "query", parameterized ? "up{service=\"{{service}}\"}" : "up"));
        Map<String, Object> profile = map("schemaVersion", "att-sut-metrics-profile/v1", "id", id, "name", "Base profile",
                "kind", "base", "category", "app", "metrics", metrics);
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Map<String, Object> manifest;
        do {
            manifest = service.collectionStatus(jobId);
            String state = String.valueOf(manifest.get("state"));
            if (Set.of("READY", "PARTIAL", "UNAVAILABLE", "FAILED", "DISABLED").contains(state)) return manifest;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("SUT Metrics collection did not finish before the test deadline.");
        return Map.of();
    }

    private static Map<String, Object> awaitThresholdEvaluation(SutMetricsService service, String jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Map<String, Object> evaluation;
        do {
            evaluation = service.thresholdEvaluationStatus(jobId);
            String state = String.valueOf(evaluation.get("state"));
            if (Set.of("MET", "NOT_MET", "NOT_EVALUATED", "ERROR").contains(state)) return evaluation;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("SUT threshold evaluation did not finish before the test deadline.");
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

    private static String atWindowEndResponse(String raw) {
        long timestamp = Instant.parse(queryParameter(raw, "end")).toEpochMilli();
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"instance\":\"sut-1\"},\"values\":[["
                + String.format(Locale.ROOT, "%.3f", timestamp / 1000.0) + ",\"1.0\"]]}]}}";
    }

    private static String thresholdResponse(String raw, double value) {
        long start = Instant.parse(queryParameter(raw, "start")).getEpochSecond();
        long end = Instant.parse(queryParameter(raw, "end")).getEpochSecond();
        long step = Long.parseLong(queryParameter(raw, "step").replace("s", ""));
        StringBuilder samples = new StringBuilder();
        for (long timestamp = start; timestamp <= end; timestamp += step) {
            if (samples.length() > 0) samples.append(',');
            samples.append('[').append(timestamp).append(",\"").append(value).append("\"]");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"instance\":\"sut-1\"},\"values\":[" + samples + "]}]}}";
    }

    private static String sampleAtWindowEndOffsetResponse(String raw, long secondsBeforeEnd) {
        long timestamp = Instant.parse(queryParameter(raw, "end")).minusSeconds(secondsBeforeEnd).toEpochMilli();
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"instance\":\"sut-1\"},\"values\":[["
                + String.format(Locale.ROOT, "%.3f", timestamp / 1000.0) + ",\"1.0\"]]}]}}";
    }

    private static Map<String, Object> phase(String name, Instant start, Instant end, boolean measured) {
        return map("phase", name, "startAt", start.toString(), "endAt", end.toString(), "measured", measured);
    }

    private static void assertGoldenShape(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertTrue(actual.isObject(), path + " must be an object");
            Set<String> expectedNames = new java.util.TreeSet<>(), actualNames = new java.util.TreeSet<>();
            expected.fieldNames().forEachRemaining(expectedNames::add);
            actual.fieldNames().forEachRemaining(actualNames::add);
            assertEquals(expectedNames, actualNames, path + " field names changed");
            expected.fields().forEachRemaining(entry -> assertGoldenShape(entry.getValue(), actual.get(entry.getKey()), path + "." + entry.getKey()));
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), path + " must be an array");
            assertEquals(expected.size(), actual.size(), path + " length changed");
            for (int index = 0; index < expected.size(); index++) assertGoldenShape(expected.get(index), actual.get(index), path + "[" + index + "]");
        } else if (expected.isTextual() && expected.asText().startsWith("$")) {
            String marker = expected.asText();
            if ("$TIME".equals(marker)) assertDoesNotThrow(() -> Instant.parse(actual.asText()), path + " must be a UTC timestamp");
            else if ("$REVISION".equals(marker) || "$SNAPSHOT".equals(marker)) assertTrue(actual.asText().matches("[a-f0-9]{64}"), path + " must be a SHA-256 revision");
            else if ("$JOB".equals(marker)) assertEquals(JOB_ID, actual.asText(), path + " must identify the authorized Job");
            else if ("$EPOCH".equals(marker)) assertTrue(actual.isIntegralNumber(), path + " must be epoch milliseconds");
            else fail("Unknown golden marker " + marker + " at " + path);
        } else assertEquals(expected, actual, path + " value changed");
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
