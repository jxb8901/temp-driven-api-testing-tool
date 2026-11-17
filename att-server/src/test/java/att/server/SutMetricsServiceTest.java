package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            Instant finishedAt = Instant.ofEpochSecond(Instant.now().getEpochSecond() - 7200L);
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

    @Test void postRunCollectionCapturesFourHourPrometheusWindowInOneBoundedRequest() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::atWindowEndResponse);
             SutMetricsService service = new SutMetricsService(temp)) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            Map<String, Object> snapshot = service.resolveScenario(
                    scenario(List.of(binding("prom", null)), "1h", "1h", "300s"), "alice", false);
            service.writeSnapshot(JOB_ID, snapshot);

            Instant finishedAt = Instant.ofEpochSecond(Instant.now().getEpochSecond() - 7200L);
            Instant startedAt = finishedAt.minusSeconds(2 * 3600L);
            service.jobFinished(JOB_ID, startedAt, finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);

            assertEquals("READY", manifest.get("state"));
            assertEquals(startedAt.minusSeconds(3600).toString(), manifest.get("windowStart"));
            assertEquals(finishedAt.plusSeconds(3600).toString(), manifest.get("windowEnd"));
            assertEquals(1, fixture.requests.get(), "A 4-hour range stays within one bounded Prometheus request.");
            assertEquals(manifest.get("windowStart"), fixture.queryStart.get());
            assertEquals(manifest.get("windowEnd"), fixture.queryEnd.get());
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
            // The configured 1m after-buffer plus one 15s step must already be eligible
            // before this test's 30s manifest wait begins.
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
            JsonNode v1Envelope = actual.deepCopy();
            ((ObjectNode) v1Envelope).remove("normalizedObservationsV2");
            assertGoldenShape(golden, v1Envelope, "$");
            assertTrue(actual.path("normalizedObservationsV2").isArray());
            assertEquals(SutMetricsContractMigration.OBSERVATION_V2,
                    actual.path("normalizedObservationsV2").path(0).path("schemaVersion").asText());
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
            Map<String, Object> pinnedSnapshot = service.snapshotForJob(jobId);
            Path recordPath = data.resolve("jobs").resolve(jobId).resolve("sut-metrics/threshold-evaluation.json");
            String pending = Files.readString(recordPath);
            assertFalse(ServerRuntime.JSON.readTree(pending).has("sutThresholds"));
            att.validation.JsonSchemaVerifier.verifyJson(schema, pending);

            Map<String, Object> error = service.evaluationError(pinnedSnapshot, "FAILED", "EVALUATION_FAILED", "bounded test error", 2);
            service.writeThresholdEvaluation(jobId, pinnedSnapshot, error);
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
            assertEquals(1, maps(manifest.get("files")).size(),
                    "The failed request keeps an explicit UNAVAILABLE observation sidecar.");
            assertEquals("UNAVAILABLE", maps(manifest.get("files")).get(0).get("queryStatus"));
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
            assertTrue(Files.readString(metrics.resolve("execution-payload.json")).contains("sensitive-tool-argument"));
            try {
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(metrics.resolve("execution-payload.json"))));
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
            assertEquals(0, coverage.get("businessCallNativeTimedOut"));
            assertEquals(1, coverage.get("businessCallBudgetTimedOut"));
            assertEquals(1, coverage.get("businessCallSkipped"));
            assertTrue(((Number) coverage.get("businessCallDurationMs")).longValue() >= 200);
            assertTrue(cancelled.get() >= 1);
            long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (closed.get() == 0 && System.nanoTime() < closeDeadline) Thread.sleep(10);
            assertEquals(1, closed.get());
            assertTrue(maps(manifest.get("diagnostics")).stream().anyMatch(d -> "BUSINESS_CALL_BUDGET_TIMEOUT".equals(d.get("code"))));
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
                assertEquals(1, object(manifest.get("coverage")).get("businessCallBudgetTimedOut"));
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

    @Test void businessCallStartedAtIsCapturedAfterSessionSetup() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-call-start-time"))) {
            service.setBusinessCallLimitsForTesting(1, 3_000, 2_000);
            service.writeSnapshot(JOB_ID, businessSnapshot(List.of(
                    businessQuery("slow-open", "#{db.orders.scalar(sql='select 1')}", "DBHELPER", null, "afterDrain", null))));
            CountDownLatch openEntered = new CountDownLatch(1), releaseOpen = new CountDownLatch(1);
            AtomicReference<Instant> openCompletedAt = new AtomicReference<>();
            AtomicReference<Instant> callStartedAt = new AtomicReference<>();
            AtomicReference<Instant> executeEnteredAt = new AtomicReference<>();
            service.setBusinessCallExecutor((jobId, snapshot) -> {
                openEntered.countDown();
                if (!releaseOpen.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("The test did not release the delayed business-call session.");
                openCompletedAt.set(Instant.now());
                return new SutMetricsService.BusinessCallSession() {
                    @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                    Instant executionFinishedAt, Instant steadyStart,
                                                    Instant steadyEnd, Instant observedAt) {
                        callStartedAt.set(observedAt);
                        executeEnteredAt.set(Instant.now());
                        return 1;
                    }
                    @Override public void close() { }
                };
            });

            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            try {
                assertTrue(openEntered.await(5, TimeUnit.SECONDS), "Session setup should begin before execution.");
                Thread.sleep(75);
            } finally {
                releaseOpen.countDown();
            }
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);

            assertEquals("READY", manifest.get("state"));
            assertNotNull(openCompletedAt.get());
            assertNotNull(callStartedAt.get());
            assertFalse(callStartedAt.get().isBefore(openCompletedAt.get()),
                    "CALL_STARTED_AT must be captured after the delayed session setup finishes.");
            assertFalse(callStartedAt.get().isAfter(executeEnteredAt.get()),
                    "CALL_STARTED_AT must be captured immediately before invoking the business call.");
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

    @Test void nativeBusinessCallTimeoutHasItsOwnDiagnosticAndCounter() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-native-timeout"))) {
            service.setBusinessCallLimitsForTesting(1, 2_000, 1_000);
            service.writeSnapshot(JOB_ID, businessSnapshot(List.of(
                    businessQuery("native-timeout", "#{db.orders.scalar(sql='select timeout')}", "DBHELPER", null, "afterDrain", null))));
            service.setBusinessCallExecutor((jobId, snapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) throws Exception {
                    throw new SutMetricsService.BusinessCallNativeTimeoutException();
                }
                @Override public void close() { }
            });
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);

            assertEquals("FAILED", manifest.get("state"));
            Map<String, Object> coverage = object(manifest.get("coverage"));
            assertEquals(1, coverage.get("businessCallTimedOut"));
            assertEquals(1, coverage.get("businessCallNativeTimedOut"));
            assertEquals(0, coverage.get("businessCallBudgetTimedOut"));
            assertTrue(maps(manifest.get("diagnostics")).stream().anyMatch(d -> "BUSINESS_CALL_TIMEOUT".equals(d.get("code"))));
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

    @Test void businessMetricNativeToolAndDbHelperTimeoutsUseStructuredSignals() {
        att.exec.ToolExecutionException toolTimeout = new att.exec.ToolExecutionException(
                "TIMEOUT", "secret tool arguments are withheld", Map.of("message", "secret"), null, null);
        assertTrue(ServerRuntime.businessMetricNativeTimeout(toolTimeout));
        assertFalse(ServerRuntime.businessMetricNativeTimeout(new IllegalStateException("timed out")),
                "Timeout words in free-form messages must not be used for classification.");

        Map<String, Object> invocation = map("status", "ERROR", "DB", map("orders", map("metric-1", map(
                "result", map("error", map("type", "TIMEOUT", "message", "secret SQL details"))))));
        att.exec.ToolInvocationResult statementTimeout = new att.exec.ToolInvocationResult(
                "db.orders", "metric-1", map("error", map("type", "TIMEOUT", "message", "secret")), invocation, false);
        assertTrue(ServerRuntime.businessMetricInvocationTimedOut(statementTimeout));

        att.exec.ToolInvocationResult poolTimeout = new att.exec.ToolInvocationResult("db.orders", "metric-2",
                map("error", map("type", "DB_POOL_TIMEOUT", "message", "secret")), map("status", "ERROR"), false);
        assertTrue(ServerRuntime.businessMetricInvocationTimedOut(poolTimeout));
        att.exec.ToolInvocationResult sqlError = new att.exec.ToolInvocationResult("db.orders", "metric-3",
                map("error", map("type", "SQL_ERROR", "message", "timed out")), map("status", "ERROR"), false);
        assertFalse(ServerRuntime.businessMetricInvocationTimedOut(sqlError));
    }

    @Test void mixedPrometheusAndBusinessCallsMarkLateAfterDrainSnapshotsStale() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(1);
             SutMetricsService service = new SutMetricsService(temp.resolve("mixed-late-business"))) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            service.upsertProfile(businessProfile("business", "#{db.orders.scalar(sql='select count(*)')}"));
            Map<String, Object> configured = scenario(List.of(binding("prom", null), map("profile", "business")),
                    "1m", "1h", "5m");
            configured.put("thresholds", List.of(map("id", "count-max", "profile", "business", "metric", "count", "max", 10.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            service.writeSnapshot(JOB_ID, snapshot);
            AtomicInteger calls = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    calls.incrementAndGet(); return 12;
                }
                @Override public void close() { }
            });

            long expectedPrometheusDelayMillis = (60L * 60L + 5L * 60L) * 1000L;
            Instant finishedAt = Instant.now().minusMillis(SutMetricsService.MAX_BUSINESS_AFTER_DRAIN_FRESHNESS_DELAY_MILLIS
                    + expectedPrometheusDelayMillis + 30_000L);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals(1, calls.get());
            Map<String, Object> businessFile = maps(manifest.get("files")).stream()
                    .filter(file -> "count".equals(file.get("metricId"))).findFirst().orElseThrow();
            Map<String, Object> sidecar = ServerRuntime.JSON.readValue(Files.readAllBytes(temp.resolve("mixed-late-business").resolve("jobs").resolve(JOB_ID)
                    .resolve("sut-metrics").resolve(String.valueOf(businessFile.get("path")))), Map.class);
            Map<String, Object> observation = object(sidecar.get("observation"));
            Map<String, Object> window = object(observation.get("window"));
            Map<String, Object> availability = object(observation.get("availability"));
            long actualDelay = ((Number) window.get("delayFromExecutionEndMs")).longValue();
            assertTrue(actualDelay > expectedPrometheusDelayMillis);
            assertEquals("STALE", availability.get("freshness"));
            assertEquals("BUSINESS_CALL_STALE_AFTER_DRAIN", maps(availability.get("diagnostics")).get(0).get("code"));
            Map<String, Object> evaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("NOT_EVALUATED", evaluation.get("state"));
            assertEquals("NOT_EVALUATED", object(evaluation.get("sutThresholds")).get("status"));
            Map<String, Object> rule = maps(object(evaluation.get("sutThresholds")).get("items")).get(0);
            assertEquals("NOT_EVALUATED", rule.get("status"));
        }
    }

    @Test void defaultMixedPrometheusBufferKeepsAfterDrainBusinessSnapshotFreshAndEvaluable() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::atWindowEndResponse);
             SutMetricsService service = new SutMetricsService(temp.resolve("mixed-default-business-freshness"))) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            service.upsertProfile(businessProfile("business", "#{db.orders.scalar(sql='select count(*)')}"));
            Map<String, Object> configured = scenario(List.of(binding("prom", null), map("profile", "business")),
                    "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "count-max", "profile", "business", "metric", "count", "max", 10.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            assertEquals("1s", object(snapshot.get("collection")).get("after"));
            assertEquals("15s", object(snapshot.get("collection")).get("step"));
            service.writeSnapshot(JOB_ID, snapshot);
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) { return 7; }
                @Override public void close() { }
            });

            Instant finishedAt = Instant.now().minusSeconds(2);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            Map<String, Object> businessFile = maps(manifest.get("files")).stream()
                    .filter(file -> "count".equals(file.get("metricId"))).findFirst().orElseThrow();
            Map<String, Object> sidecar = ServerRuntime.JSON.readValue(Files.readAllBytes(temp.resolve("mixed-default-business-freshness")
                    .resolve("jobs").resolve(JOB_ID).resolve("sut-metrics").resolve(String.valueOf(businessFile.get("path")))), Map.class);
            Map<String, Object> availability = object(object(sidecar.get("observation")).get("availability"));
            assertEquals("FRESH", availability.get("freshness"));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("MET", evaluation.get("state"));
            assertEquals("MET", object(evaluation.get("sutThresholds")).get("status"));
            assertEquals("MET", maps(object(evaluation.get("sutThresholds")).get("items")).get(0).get("status"));
        }
    }

    @Test void hourLongPrometheusBufferDoesNotDelayAfterDrainBusinessSnapshot() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::atWindowEndResponse);
             SutMetricsService service = new SutMetricsService(temp.resolve("mixed-hour-business-buffer"))) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(baseProfile("base", 1, false));
            service.upsertProfile(businessProfile("business", "#{db.orders.scalar(sql='select count(*)')}"));
            Map<String, Object> configured = scenario(List.of(binding("prom", null), map("profile", "business")),
                    "1m", "1h", "5m");
            configured.put("thresholds", List.of(map("id", "count-max", "profile", "business", "metric", "count", "max", 10.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice");
            service.writeSnapshot(JOB_ID, snapshot);
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) { return 12; }
                @Override public void close() { }
            });

            Instant finishedAt = Instant.now().minusSeconds(2);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Map<String, Object> manifest;
            do {
                manifest = service.collectionStatus(JOB_ID);
                if ("COMPLETE".equals(object(manifest.get("collectionProgress")).get("business"))) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertEquals("PENDING", manifest.get("state"), "The combined manifest must stay pending for the Prometheus buffer.");
            assertEquals("COMPLETE", object(manifest.get("collectionProgress")).get("business"));
            assertEquals("PENDING", object(manifest.get("collectionProgress")).get("prometheus"));
            assertEquals(0, fixture.requests.get(), "Prometheus collection must wait for after=1h plus one step.");
            Map<String, Object> provisionalPage = service.readCollectionPage(JOB_ID, 0, 16, null, null, null);
            assertEquals(Boolean.TRUE, provisionalPage.get("provisional"));
            assertEquals("COMPLETE", object(object(provisionalPage.get("manifest")).get("collectionProgress")).get("business"));
            assertEquals("PENDING", object(object(provisionalPage.get("manifest")).get("collectionProgress")).get("prometheus"));
            assertEquals(1, maps(provisionalPage.get("items")).size(), "Only completed Business evidence is published provisionally.");
            assertEquals("count", maps(provisionalPage.get("items")).get(0).get("metricId"));
            Map<String, Object> businessFile = maps(manifest.get("files")).stream()
                    .filter(file -> "count".equals(file.get("metricId"))).findFirst().orElseThrow();
            Map<String, Object> sidecar = ServerRuntime.JSON.readValue(Files.readAllBytes(temp.resolve("mixed-hour-business-buffer")
                    .resolve("jobs").resolve(JOB_ID).resolve("sut-metrics").resolve(String.valueOf(businessFile.get("path")))), Map.class);
            Map<String, Object> observation = object(sidecar.get("observation"));
            Map<String, Object> window = object(observation.get("window"));
            Map<String, Object> availability = object(observation.get("availability"));
            assertTrue(((Number) window.get("delayFromExecutionEndMs")).longValue()
                    <= SutMetricsService.MAX_BUSINESS_AFTER_DRAIN_FRESHNESS_DELAY_MILLIS);
            assertEquals("FRESH", availability.get("freshness"));
            assertEquals("PENDING", service.thresholdEvaluationStatus(JOB_ID).get("state"),
                    "Threshold evaluation must wait until the combined collection reaches a terminal state.");
        }
    }

    @Test void restartAfterCompletedBusinessPhaseResumesPrometheusWithoutRepeatingCalls() throws Exception {
        Path data = temp.resolve("mixed-restart-after-business");
        AtomicInteger businessInvocations = new AtomicInteger();
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::atWindowEndResponse)) {
            Instant finishedAt = Instant.now().minusSeconds(5), startedAt = finishedAt.minusSeconds(30);
            Map<String, Object> businessFileBeforeRestart;
            SutMetricsService first = new SutMetricsService(data);
            first.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            first.upsertProfile(baseProfile("base", 1, false));
            first.upsertProfile(businessProfile("business", "#{db.orders.scalar(sql='select count(*)')}"));
            Map<String, Object> configured = scenario(List.of(binding("prom", null), map("profile", "business")),
                    "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "count-max", "profile", "business", "metric", "count", "max", 10.0)));
            first.writeSnapshot(JOB_ID, first.resolveScenario(configured, "alice"));
            first.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    businessInvocations.incrementAndGet();
                    return 12;
                }
                @Override public void close() { }
            });
            first.jobFinished(JOB_ID, startedAt, finishedAt);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Map<String, Object> manifest;
            do {
                manifest = first.collectionStatus(JOB_ID);
                if ("COMPLETE".equals(object(manifest.get("collectionProgress")).get("business"))) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertEquals("PENDING", manifest.get("state"));
            assertEquals("PENDING", object(manifest.get("collectionProgress")).get("prometheus"));
            assertEquals(1, businessInvocations.get());
            assertEquals(0, fixture.requests.get());
            businessFileBeforeRestart = maps(manifest.get("files")).stream()
                    .filter(file -> "count".equals(file.get("metricId"))).findFirst().orElseThrow();
            String businessDigest = String.valueOf(businessFileBeforeRestart.get("sha256"));
            assertEquals(1, maps(first.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items")).size());
            first.close();

            try (SutMetricsService restarted = new SutMetricsService(data)) {
                restarted.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                    @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                    Instant executionFinishedAt, Instant steadyStart,
                                                    Instant steadyEnd, Instant observedAt) {
                        businessInvocations.incrementAndGet();
                        return 99;
                    }
                    @Override public void close() { }
                });
                assertEquals(List.of(JOB_ID), restarted.pendingCollectionJobIds());
                restarted.jobFinished(JOB_ID, startedAt, finishedAt);
                Map<String, Object> completed = awaitManifest(restarted, JOB_ID);
                assertEquals("READY", completed.get("state"));
                assertEquals("COMPLETE", object(completed.get("collectionProgress")).get("business"));
                assertEquals("COMPLETE", object(completed.get("collectionProgress")).get("prometheus"));
                assertEquals(1, businessInvocations.get(), "Recovery must not repeat a completed side-effecting call.");
                assertTrue(fixture.requests.get() > 0, "Recovery must resume the unfinished Prometheus phase.");
                assertEquals(2, maps(completed.get("files")).size());
                Map<String, Object> businessFileAfterRestart = maps(completed.get("files")).stream()
                        .filter(file -> "count".equals(file.get("metricId"))).findFirst().orElseThrow();
                assertEquals(businessDigest, businessFileAfterRestart.get("sha256"), "The completed business sidecar must remain unchanged.");
                assertEquals(2, ((Number) object(completed.get("coverage")).get("successfulQueries")).intValue());

                Map<String, Object> evaluation = awaitThresholdEvaluation(restarted, JOB_ID);
                assertEquals("NOT_MET", evaluation.get("state"));
                Map<String, Object> offline = restarted.writeOfflineReportEvidence(JOB_ID, temp.resolve("mixed-restart-report"));
                assertEquals("READY", offline.get("state"));
                assertEquals(2, maps(offline.get("observations")).size(), "The terminal combined report must include both phases.");
            }
        }
    }

    @Test void restartDuringBusinessCallFailsClosedWithoutRetryingUncertainSideEffects() throws Exception {
        Path data = temp.resolve("mixed-restart-during-business");
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch callExited = new CountDownLatch(1);
        AtomicInteger businessInvocations = new AtomicInteger();
        SutMetricsService original = new SutMetricsService(data);
        try {
            original.upsertProfile(businessProfile("business", "#{db.orders.scalar(sql='update account set seen = 1')}"));
            original.writeSnapshot(JOB_ID, original.resolveScenario(scenario(List.of(map("profile", "business")),
                    "1m", "1m", "15s"), "alice"));
            original.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) throws Exception {
                    businessInvocations.incrementAndGet();
                    callStarted.countDown();
                    try { new CountDownLatch(1).await(); return 1; }
                    finally { callExited.countDown(); }
                }
                @Override public void close() { }
            });
            Instant finishedAt = Instant.now().minusSeconds(2), startedAt = finishedAt.minusSeconds(30);
            original.jobFinished(JOB_ID, startedAt, finishedAt);
            assertTrue(callStarted.await(10, TimeUnit.SECONDS), "The test side-effecting call should enter before restart.");
            assertEquals("COLLECTING", original.collectionStatus(JOB_ID).get("state"));

            try (SutMetricsService restarted = new SutMetricsService(data)) {
                Map<String, Object> failed = restarted.collectionStatus(JOB_ID);
                assertEquals("FAILED", failed.get("state"));
                assertEquals("SERVER_RESTART_INTERRUPTED", maps(failed.get("diagnostics")).get(0).get("code"));
                AtomicInteger retryInvocations = new AtomicInteger();
                restarted.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                    @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                    Instant executionFinishedAt, Instant steadyStart,
                                                    Instant steadyEnd, Instant observedAt) {
                        retryInvocations.incrementAndGet();
                        return 2;
                    }
                    @Override public void close() { }
                });
                restarted.jobFinished(JOB_ID, startedAt, finishedAt);
                assertEquals(0, retryInvocations.get(), "Recovery must not retry a call whose side effects are uncertain.");
                assertTrue(restarted.pendingCollectionJobIds().isEmpty());
                assertEquals(1, businessInvocations.get());
            }
        } finally {
            original.close();
            assertTrue(callExited.await(5, TimeUnit.SECONDS), "The simulated in-flight call should stop when the original service closes.");
        }
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

    @Test void multiOutputMappingReadsRealLowercaseDatabaseColumnLabelsExactly() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-lowercase-columns"))) {
            Map<String, Object> profile = map("schemaVersion", "att-sut-metrics-profile/v1", "id", "lowercase-columns",
                    "name", "Lowercase columns", "kind", "base", "category", "db", "metrics", List.of(map(
                            "id", "transaction-query",
                            "call", "#{db.orders.query(sql='select workload_id, mix_id, success, failure from transaction_summary')}",
                            "sampling", "afterDrain",
                            "mapping", map("rows", "rows", "workloadColumn", "workload_id", "mixColumn", "mix_id"),
                            "outputs", List.of(
                                    map("id", "success", "title", "Success", "column", "success", "unit", "transactions", "kind", "counter"),
                                    map("id", "failure", "title", "Failure", "column", "failure", "unit", "transactions", "kind", "counter")))));
            service.upsertProfile(profile);
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(map("profile", "lowercase-columns")),
                    "1m", "1s", "15s"), "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 2, "rows", List.of(
                            map("workload_id", "checkout", "mix_id", "web", "success", 20, "failure", 2),
                            map("workload_id", "checkout", "mix_id", "mobile", "success", 8, "failure", 0)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"), manifest.toString());
            assertEquals(1, invocations.get());
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(4, items.size());
            Map<String, Object> web = items.stream().filter(item -> "success".equals(item.get("metricId"))
                    && "web".equals(item.get("mixId"))).findFirst().orElseThrow();
            assertEquals(20.0, web.get("value"));
            assertEquals("MAPPED", web.get("mappingStatus"));
            Map<String, Object> mobileFailure = items.stream().filter(item -> "failure".equals(item.get("metricId"))
                    && "mobile".equals(item.get("mixId"))).findFirst().orElseThrow();
            assertEquals(0.0, mobileFailure.get("value"));
        }
    }

    @Test void legacyBusinessProfileLabelColumnsStillRequireAnExplicitAllowlist() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("legacy-label-allowlist"))) {
            for (Map<String, Object> mapping : List.of(
                    map("rows", "rows", "labelColumns", map("instance", "INSTANCE")),
                    map("rows", "rows", "allowedLabels", List.of(), "labelColumns", map("instance", "INSTANCE")))) {
                Map<String, Object> profile = map("schemaVersion", "att-sut-metrics-profile/v1", "id", "legacy-labels-" + mapping.size(),
                        "name", "Legacy label mapping", "kind", "base", "category", "app", "metrics", List.of(map(
                                "id", "instance-summary",
                                "call", "#{db.orders.query(sql='select instance, cpu_pct from metric_summary')}",
                                "sampling", "afterDrain", "mapping", mapping,
                                "outputs", List.of(map("id", "cpu", "title", "CPU", "column", "CPU_PCT", "unit", "percent")))));
                assertThrows(IllegalArgumentException.class, () -> service.upsertProfile(profile));
            }
        }
    }

    @Test void oneWideRowCallProducesThreeMetricsForFourDeclaredLabelSets() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp)) {
            Map<String, Object> profile = map("schemaVersion", "att-sut-metrics-profile/v1", "id", "wide-row",
                    "name", "Wide row", "kind", "base", "category", "app", "metrics", List.of(map(
                            "id", "instance-summary",
                            "call", "#{db.orders.query(sql='select instance, role, cpu_pct, memory_mb, sessions from metric_summary')}",
                            "sampling", "afterDrain",
                            "mapping", map("rows", "rows", "allowedLabels", List.of("instance", "role"),
                                    "labelColumns", map("instance", "INSTANCE", "role", "ROLE")),
                            "outputs", List.of(
                                    map("id", "cpu", "title", "CPU percent", "column", "CPU_PCT", "unit", "percent", "kind", "gauge"),
                                    map("id", "memory", "title", "Memory MB", "column", "MEMORY_MB", "unit", "custom", "kind", "gauge"),
                                    map("id", "sessions", "title", "Sessions", "column", "SESSIONS", "unit", "count", "kind", "gauge")))));
            service.upsertProfile(profile);
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 4, "rows", List.of(
                            map("INSTANCE", "app-1", "ROLE", "app", "CPU_PCT", 11, "MEMORY_MB", 128, "SESSIONS", 3),
                            map("INSTANCE", "app-2", "ROLE", "app", "CPU_PCT", 12, "MEMORY_MB", 256, "SESSIONS", 4),
                            map("INSTANCE", "db-1", "ROLE", "db", "CPU_PCT", 21, "MEMORY_MB", 512, "SESSIONS", 5),
                            map("INSTANCE", "db-2", "ROLE", "db", "CPU_PCT", 22, "MEMORY_MB", 1024, "SESSIONS", 6)));
                }
                @Override public void close() { }
            });
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(map("profile", "wide-row")),
                    "1m", "1s", "15s"), "alice");
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, invocations.get(), "One shared wide-row call must serve all three output metrics.");
            assertEquals(3, maps(manifest.get("files")).size());
            Set<String> metricIds = new java.util.TreeSet<>();
            Set<String> observationIds = new java.util.HashSet<>();
            for (Map<String, Object> file : maps(manifest.get("files"))) {
                metricIds.add(String.valueOf(file.get("metricId")));
                Map<String, Object> sidecar = ServerRuntime.JSON.readValue(Files.readString(
                        temp.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics").resolve(String.valueOf(file.get("path")))), Map.class);
                assertEquals(4, maps(sidecar.get("labeledValues")).size());
                List<Map<String, Object>> observations = maps(sidecar.get("normalizedObservationsV2"));
                assertEquals(4, observations.size());
                for (Map<String, Object> observation : observations) {
                    Map<String, Object> identity = object(observation.get("identity"));
                    Map<String, Object> labels = object(identity.get("labels"));
                    assertEquals(Set.of("instance", "role"), labels.keySet());
                    assertTrue(observationIds.add(String.valueOf(observation.get("observationId"))),
                            "Each labelled metric instance must have its own observation identity.");
                }
            }
            assertEquals(Set.of("cpu", "memory", "sessions"), metricIds);
            assertEquals(12, observationIds.size());
        }
    }

    @Test void onePrometheusRangeFetchRoutesMultipleLabelledOutputsWithoutRelyingOnName() throws Exception {
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::multiMetricResponse);
             SutMetricsService service = new SutMetricsService(temp.resolve("prometheus-multi-output"))) {
            service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "multi-prom",
                    "name", "Multi-series Prometheus", "kind", "base", "category", "app", "metrics", List.of(
                            map("id", "cpu", "title", "CPU", "unit", "percent", "query", "sum by(instance) (up or process_cpu_seconds_total)",
                                    "metricName", "host_cpu_seconds_total"),
                            map("id", "memory", "title", "Memory", "unit", "bytes", "query", "sum by(instance) (up or process_cpu_seconds_total)",
                                    "metricName", "host_memory_bytes"),
                            map("id", "sessions", "title", "Sessions", "unit", "count", "query", "sum by(instance) (up or process_cpu_seconds_total)",
                                    "discriminator", Map.of("family", "sessions")))));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(map("profile", "multi-prom",
                    "source", "prom", "targets", List.of(map("id", "sut-1", "type", "app")))),
                    "1m", "1m", "15s"), "alice");
            List<Map<String, Object>> queries = maps(snapshot.get("queries"));
            assertEquals(3, queries.size());
            assertEquals(1, queries.stream().map(query -> query.get("fetchId")).distinct().count(),
                    "Outputs with the same source, target, tenant, PromQL and collection window share one immutable fetch.");
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(120);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, fixture.requests.get(), "A single query_range response must serve the three metric outputs.");
            assertEquals(3, maps(manifest.get("files")).size());
            Map<String, String> expectedLabels = Map.of("cpu", "host_cpu_seconds_total",
                    "memory", "host_memory_bytes", "sessions", "sessions");
            for (Map<String, Object> file : maps(manifest.get("files"))) {
                String metricId = String.valueOf(file.get("metricId"));
                Map<String, Object> sidecar = ServerRuntime.JSON.readValue(Files.readString(
                        temp.resolve("prometheus-multi-output/jobs").resolve(JOB_ID).resolve("sut-metrics")
                                .resolve(String.valueOf(file.get("path")))), Map.class);
                List<Map<String, Object>> series = maps(sidecar.get("series"));
                assertEquals(1, series.size(), "Each output receives only its matching provider series.");
                Map<String, Object> labels = object(series.get(0).get("labels"));
                if ("sessions".equals(metricId)) assertEquals("sessions", labels.get("family"));
                else assertEquals(expectedLabels.get(metricId), labels.get("__name__"));
                List<Map<String, Object>> normalized = maps(sidecar.get("normalizedObservationsV2"));
                assertEquals(1, normalized.size());
                assertEquals(metricId, object(normalized.get(0).get("identity")).get("metricId"));
            }
        }
    }

    @Test void prometheusMetricSourceRoutesByMetricNameBeforeRetainingConfiguredLabels() throws Exception {
        Path data = temp.resolve("prometheus-label-allowlist");
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::unapprovedMetricLabelResponse);
             SutMetricsService service = new SutMetricsService(data)) {
            Map<String, Object> sourceView = service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "host-metrics",
                    "name", "Host metrics", "connection", map("kind", "DATA_SOURCE", "id", "prom",
                            "revision", sourceView.get("revision")),
                    "fetch", map("id", "host-series", "mode", "prometheus-range", "query", "allowed_metric",
                            "parameters", Map.of(), "sampling", "range", "step", "15s"),
                    "mapping", map("labels", map("retain", List.of("instance")), "outputs", List.of(
                            map("id", "cpu", "title", "CPU", "unit", "percent", "kind", "gauge",
                                    "metricName", "allowed_metric"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 1000, "maxArtifactBytes", 1048576)));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v2", "id", "flat-host",
                    "name", "Flat host profile", "metricSourceIds", List.of("host-metrics"), "groupIds", List.of(),
                    "allowedBindings", map("environments", List.of("TEST"),
                            "targets", List.of(map("type", "target", "id", "sut-1")),
                            "tenants", List.of(), "parameters", Map.of()),
                    "budgets", map("maxFetches", 1, "maxObservations", 64, "maxArtifactBytes", 1048576)));
            Map<String, Object> configured = scenario(List.of(map("profile", "flat-host")), "1m", "1m", "15s");
            configured.put("livePreview", Map.of("enabled", true));
            Map<String, Object> snapshot = service.resolveScenario(
                    configured, "alice", false, multiOutputBusinessContext(List.of()));
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(120);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            Map<String, Object> file = maps(manifest.get("files")).get(0);
            String sidecarJson = Files.readString(data.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics")
                    .resolve(String.valueOf(file.get("path"))));
            Map<String, Object> sidecar = ServerRuntime.JSON.readValue(sidecarJson, Map.class);
            assertEquals(Map.of("instance", "sut-1"), object(maps(sidecar.get("series")).get(0).get("labels")));
            assertFalse(sidecarJson.contains("private-pod-7"));

            String apiPage = ServerRuntime.JSON.writeValueAsString(service.readCollectionPage(JOB_ID, 0, 16, null, null, null));
            assertFalse(apiPage.contains("private-pod-7"));
            Map<String, Object> offline = service.writeOfflineReportEvidence(JOB_ID, temp.resolve("allowlist-offline-report"));
            String offlineJson = ServerRuntime.JSON.writeValueAsString(offline);
            assertFalse(offlineJson.contains("private-pod-7"));

            while (Instant.now().getEpochSecond() % 30 < 8 || Instant.now().getEpochSecond() % 30 > 21) Thread.sleep(20);
            Instant previewStartedAt = Instant.now().minusSeconds(3600);
            int requestsBeforePreview = fixture.requests.get();
            Map<String, Object> preview = service.livePreview(JOB_ID, "alice", false, previewStartedAt,
                    "15m", "30s", null, null);
            Map<String, Object> previewItem = maps(preview.get("items")).get(0);
            String previewJson = ServerRuntime.JSON.writeValueAsString(preview);
            assertEquals("READY", preview.get("state"));
            assertEquals(Map.of("instance", "sut-1"), object(maps(previewItem.get("series")).get(0).get("labels")));
            assertFalse(previewJson.contains("private-pod-7"));
            assertEquals(requestsBeforePreview + 1, fixture.requests.get());

            Map<String, Object> cachedPreview = service.livePreview(JOB_ID, "alice", false, previewStartedAt,
                    "15m", "30s", null, null);
            assertEquals(requestsBeforePreview + 1, fixture.requests.get(), "A cache hit must retain the filtered live preview.");
            assertFalse(ServerRuntime.JSON.writeValueAsString(cachedPreview).contains("private-pod-7"));
        }
    }

    @Test void prometheusMetricSourceReportsDistinctSeriesThatCollapseAfterLabelRetention() throws Exception {
        Path data = temp.resolve("prometheus-label-retention-collision");
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::prometheusLabelCollisionResponse);
             SutMetricsService service = new SutMetricsService(data)) {
            Map<String, Object> sourceView = service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "host-metrics",
                    "name", "Host metrics", "connection", map("kind", "DATA_SOURCE", "id", "prom",
                            "revision", sourceView.get("revision")),
                    "fetch", map("id", "host-series", "mode", "prometheus-range", "query", "allowed_metric",
                            "parameters", Map.of(), "sampling", "range", "step", "15s"),
                    "mapping", map("labels", map("retain", List.of("instance")), "outputs", List.of(
                            map("id", "cpu", "title", "CPU", "unit", "percent", "kind", "gauge",
                                    "metricName", "allowed_metric"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 1000, "maxArtifactBytes", 1048576)));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v2", "id", "flat-host",
                    "name", "Flat host profile", "metricSourceIds", List.of("host-metrics"), "groupIds", List.of(),
                    "allowedBindings", map("environments", List.of("TEST"),
                            "targets", List.of(map("type", "target", "id", "sut-1")),
                            "tenants", List.of(), "parameters", Map.of()),
                    "budgets", map("maxFetches", 1, "maxObservations", 64, "maxArtifactBytes", 1048576)));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(map("profile", "flat-host")),
                    "1m", "1m", "15s"), "alice", false, multiOutputBusinessContext(List.of()));
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(120);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("FAILED", manifest.get("state"));
            assertTrue(maps(manifest.get("diagnostics")).stream()
                    .anyMatch(diagnostic -> "METRIC_LABEL_RETENTION_COLLISION".equals(diagnostic.get("code"))));
        }
    }

    @Test void flatProfileResolvesMetricSourcesAndPinsExactGroupViews() throws Exception {
        Path data = temp.resolve("flat-profile-v2");
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::multiMetricResponse);
             SutMetricsService service = new SutMetricsService(data)) {
            Map<String, Object> sourceView = service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "host-metrics",
                    "name", "Host metrics", "connection", map("kind", "DATA_SOURCE", "id", "prom",
                            "revision", sourceView.get("revision")),
                    "fetch", map("id", "host-series", "mode", "prometheus-range",
                            "query", "host_cpu_seconds_total or host_memory_bytes", "parameters", Map.of(),
                            "sampling", "range", "step", "15s"),
                    "mapping", map("labels", map("retain", List.of("instance")), "outputs", List.of(
                            map("id", "cpu", "title", "CPU", "unit", "percent", "kind", "gauge",
                                    "metricName", "host_cpu_seconds_total"),
                            map("id", "memory", "title", "Memory", "unit", "bytes", "kind", "gauge",
                                    "metricName", "host_memory_bytes"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 1000, "maxArtifactBytes", 1048576)));
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "db-metrics",
                    "name", "Database metrics", "connection", map("kind", "DBHELPER", "id", "orders",
                            "revision", "a".repeat(64)),
                    "fetch", map("id", "db-summary", "mode", "dbhelper-query",
                            "call", "#{db.orders.query(sql='select instance, sessions from metric_summary')}",
                            "parameters", Map.of(), "sampling", "afterDrain"),
                    "mapping", map("labels", map("instance", "INSTANCE"),
                            "outputs", List.of(map("id", "sessions", "title", "Sessions", "unit", "count",
                                    "kind", "gauge", "column", "SESSIONS"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 1, "maxArtifactBytes", 1048576)));
            service.upsertGroup(map("schemaVersion", "att-sut-metrics-group/v1", "id", "runtime",
                    "title", "Runtime", "order", 10,
                    "match", map("metricIds", List.of("cpu", "memory")),
                    "groupBy", List.of("instance"), "display", "table"));
            assertThrows(IllegalArgumentException.class, () -> service.upsertGroup(map(
                    "schemaVersion", "att-sut-metrics-group/v1", "id", "credential-label",
                    "title", "Credential label", "order", 15,
                    "match", map("labels", map("api_key", "private")), "groupBy", List.of(), "display", "summary")));
            service.upsertGroup(map("schemaVersion", "att-sut-metrics-group/v1", "id", "host-source",
                    "title", "Host source", "order", 20,
                    "match", map("sourceIds", List.of("host-metrics")),
                    "groupBy", List.of(), "display", "summary"));
            Map<String, Object> profile = map("schemaVersion", "att-sut-metrics-profile/v2", "id", "flat-host",
                    "name", "Flat host profile", "metricSourceIds", List.of("host-metrics", "db-metrics"),
                    "groupIds", List.of("runtime"), "allowedBindings", map("environments", List.of("TEST"),
                            "targets", List.of(map("type", "target", "id", "sut-1")),
                            "tenants", List.of(), "parameters", Map.of()),
                    "budgets", map("maxFetches", 4, "maxObservations", 64, "maxArtifactBytes", 1048576));
            service.upsertProfile(profile);
            Map<String, Object> oneFetchMultiOutputProfile = new LinkedHashMap<>(profile);
            oneFetchMultiOutputProfile.put("id", "flat-host-one-fetch");
            oneFetchMultiOutputProfile.put("metricSourceIds", List.of("host-metrics"));
            oneFetchMultiOutputProfile.put("budgets", map("maxFetches", 1, "maxObservations", 64, "maxArtifactBytes", 1048576));
            service.upsertProfile(oneFetchMultiOutputProfile);
            Map<String, Object> oneFetchSnapshot = service.resolveScenario(
                    scenario(List.of(map("profile", "flat-host-one-fetch")), "1m", "1s", "15s"),
                    "alice", false, multiOutputBusinessContext(List.of()));
            assertEquals(2, maps(oneFetchSnapshot.get("queries")).size());
            assertEquals(1, maps(oneFetchSnapshot.get("fetchPlan")).size(),
                    "A two-output Prometheus Metric Source consumes one logical fetch budget.");
            Map<String, Object> lowFetchProfile = new LinkedHashMap<>(profile);
            lowFetchProfile.put("id", "flat-host-low-fetch");
            lowFetchProfile.put("budgets", map("maxFetches", 1, "maxObservations", 64, "maxArtifactBytes", 1048576));
            service.upsertProfile(lowFetchProfile);
            assertThrows(IllegalArgumentException.class, () -> service.resolveScenario(
                    scenario(List.of(map("profile", "flat-host-low-fetch")), "1m", "1s", "15s"),
                    "alice", false, multiOutputBusinessContext(List.of())));
            Map<String, Object> overlappingProfile = new LinkedHashMap<>(profile);
            overlappingProfile.put("id", "flat-host-overlap");
            overlappingProfile.put("groupIds", List.of("runtime", "host-source"));
            assertThrows(IllegalArgumentException.class, () -> service.upsertProfile(overlappingProfile),
                    "Flat Profile validation rejects statically overlapping group match rules.");
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 1, "rows", List.of(map("INSTANCE", "db-1", "SESSIONS", 5)));
                }
                @Override public void close() { }
            });
            Map<String, Object> observability = scenario(List.of(map("profile", "flat-host")), "1m", "1s", "15s");
            SutMetricsService.BusinessCallContext callContext = multiOutputBusinessContext(List.of());
            Map<String, Object> snapshot = service.resolveScenario(observability, "alice", false, callContext);
            assertEquals("att-sut-metrics-profile/v2", object(snapshot.get("profile")).get("schemaVersion"));
            assertEquals("native-v2", object(snapshot.get("profile")).get("migration"));
            assertEquals(4, ((Number) object(snapshot.get("budgets")).get("maxFetches")).intValue());
            assertEquals(64, ((Number) object(snapshot.get("budgets")).get("maxObservations")).intValue());
            assertEquals(1048576, ((Number) object(snapshot.get("budgets")).get("maxArtifactBytes")).intValue());
            assertEquals(2, maps(snapshot.get("metricSources")).size());
            assertEquals(1, maps(snapshot.get("groups")).size());
            assertEquals(2, maps(snapshot.get("fetchPlan")).size());
            assertEquals(1, maps(snapshot.get("fetchPlan")).stream()
                    .filter(fetch -> "host-metrics".equals(fetch.get("sourceId"))).count());

            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, fixture.requests.get(), "The two labelled Prometheus outputs share one query_range fetch.");
            assertEquals(1, invocations.get(), "The DBHelper wide-row outputs share one call.");
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            Map<String, Object> cpu = items.stream().filter(item -> "cpu".equals(item.get("metricId"))).findFirst().orElseThrow();
            List<Map<String, Object>> assignments = maps(object(cpu.get("groupView")).get("assignments"));
            assertEquals(1, assignments.size());
            assertEquals("runtime", assignments.get(0).get("groupId"));
            assertEquals(Map.of("instance", "sut-1"), object(assignments.get(0).get("groupBy")));
            Map<String, Object> cpuObservation = maps(cpu.get("normalizedObservationsV2")).get(0);
            String observationId = String.valueOf(cpuObservation.get("observationId"));
            assertEquals(observationId, assignments.get(0).get("observationId"));
            Map<String, Object> sessions = items.stream().filter(item -> "sessions".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertTrue(maps(object(sessions.get("groupView")).get("assignments")).isEmpty());
            assertEquals("METRIC_GROUP_UNASSIGNED", maps(object(sessions.get("groupView")).get("diagnostics")).get(0).get("code"));
            Map<String, Object> offline = service.writeOfflineReportEvidence(JOB_ID, temp.resolve("flat-profile-offline-report"));
            assertEquals(1, maps(offline.get("groups")).size());
            Map<String, Object> offlineCpu = maps(offline.get("observations")).stream()
                    .filter(item -> "cpu".equals(object(item.get("metric")).get("id"))).findFirst().orElseThrow();
            assertEquals("runtime", maps(object(offlineCpu.get("groupView")).get("assignments")).get(0).get("groupId"));
            Map<String, Object> offlineSessions = maps(offline.get("observations")).stream()
                    .filter(item -> "sessions".equals(object(item.get("metric")).get("id"))).findFirst().orElseThrow();
            assertEquals(5.0, maps(offlineSessions.get("labeledValues")).get(0).get("value"));
            assertEquals(1, maps(object(offlineSessions.get("groupView")).get("unassigned")).size());
            att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/api/att-load-offline-observations-v1.schema.json"),
                    ServerRuntime.JSON.writeValueAsString(offline));
            String html = renderOfflineHtml(JOB_ID, offline);
            assertTrue(html.contains("instance=db-1"));
            assertTrue(html.contains("5.0 count"));
            assertTrue(html.contains("AVAILABLE"));
            assertTrue(html.contains("Observed at (UTC)"));
            assertTrue(html.contains(String.valueOf(object(offlineSessions.get("window")).get("observedAt"))));
        }
        try (SutMetricsService restarted = new SutMetricsService(data)) {
            assertEquals(2, maps(restarted.listGroups("admin", true).get("items")).size());
            List<Map<String, Object>> replayedItems = maps(restarted.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            Map<String, Object> replayedCpu = replayedItems.stream()
                    .filter(item -> "cpu".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals("runtime", maps(object(replayedCpu.get("groupView")).get("assignments")).get(0).get("groupId"));
        }
    }

    @Test void flatProfileWideRowsRouteIntoApplicationAndDatabaseGroupsWithoutAnotherFetch() throws Exception {
        String jobId = "J0000000000000410";
        try (SutMetricsService service = new SutMetricsService(temp.resolve("flat-role-groups"))) {
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "role-wide-row",
                    "name", "Role wide row", "connection", map("kind", "DBHELPER", "id", "orders", "revision", "a".repeat(64)),
                    "fetch", map("id", "role-summary", "mode", "dbhelper-query",
                            "call", "#{db.orders.query(sql='select instance, role, cpu_pct, memory_mb, sessions, private_token from metric_summary')}",
                            "parameters", Map.of(), "sampling", "afterDrain"),
                    "mapping", map("labels", map("instance", "INSTANCE", "role", "ROLE"),
                            "outputs", List.of(
                                    map("id", "cpu", "title", "CPU", "unit", "percent", "kind", "gauge", "column", "CPU_PCT"),
                                    map("id", "memory", "title", "Memory", "unit", "bytes", "kind", "gauge", "column", "MEMORY_MB"),
                                    map("id", "sessions", "title", "Sessions", "unit", "count", "kind", "gauge", "column", "SESSIONS"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 64, "maxArtifactBytes", 1048576)));
            service.upsertGroup(map("schemaVersion", "att-sut-metrics-group/v1", "id", "app-servers",
                    "title", "App Servers", "order", 10,
                    "match", map("metricIds", List.of("cpu", "memory", "sessions"), "labels", map("role", "app")),
                    "groupBy", List.of("instance"), "display", "table"));
            service.upsertGroup(map("schemaVersion", "att-sut-metrics-group/v1", "id", "db-servers",
                    "title", "DB Servers", "order", 20,
                    "match", map("metricIds", List.of("cpu", "memory", "sessions"), "labels", map("role", "db")),
                    "groupBy", List.of("instance"), "display", "table"));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v2", "id", "role-profile",
                    "name", "Role groups", "metricSourceIds", List.of("role-wide-row"),
                    "groupIds", List.of("app-servers", "db-servers"),
                    "allowedBindings", map("environments", List.of("TEST"), "targets", List.of(),
                            "tenants", List.of(), "parameters", Map.of()),
                    "budgets", map("maxFetches", 1, "maxObservations", 64, "maxArtifactBytes", 1048576)));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((ignoredJob, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 4, "rows", List.of(
                            map("INSTANCE", "app-1", "ROLE", "app", "CPU_PCT", 11, "MEMORY_MB", 128, "SESSIONS", 3, "PRIVATE_TOKEN", "never-publish-this-token"),
                            map("INSTANCE", "app-2", "ROLE", "app", "CPU_PCT", 12, "MEMORY_MB", 256, "SESSIONS", 4, "PRIVATE_TOKEN", "never-publish-this-token"),
                            map("INSTANCE", "db-1", "ROLE", "db", "CPU_PCT", 21, "MEMORY_MB", 512, "SESSIONS", 5, "PRIVATE_TOKEN", "never-publish-this-token"),
                            map("INSTANCE", "db-2", "ROLE", "db", "CPU_PCT", 22, "MEMORY_MB", 1024, "SESSIONS", 6, "PRIVATE_TOKEN", "never-publish-this-token")));
                }
                @Override public void close() { }
            });
            Map<String, Object> snapshot = service.resolveScenario(
                    scenario(List.of(map("profile", "role-profile")), "1m", "1s", "15s"), "alice", false,
                    multiOutputBusinessContext(List.of()));
            assertEquals(1, maps(snapshot.get("fetchPlan")).size(), "The three outputs share one immutable fetch.");
            assertEquals(64, object(maps(snapshot.get("metricSources")).get(0).get("limits")).get("maxSamples"));
            service.writeSnapshot(jobId, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(jobId, finishedAt.minusSeconds(30), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, jobId);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, invocations.get(), "Adding two presentation Groups must not execute another call.");
            List<Map<String, Object>> items = maps(service.readCollectionPage(jobId, 0, 16, null, null, null).get("items"));
            assertEquals(3, items.size());
            for (Map<String, Object> file : maps(manifest.get("files"))) {
                String sidecarJson = Files.readString(temp.resolve("flat-role-groups/jobs").resolve(jobId).resolve("sut-metrics")
                        .resolve(String.valueOf(file.get("path"))));
                assertFalse(sidecarJson.contains("never-publish-this-token"), "Unmapped SQL result columns must not escape in sidecars.");
            }
            assertFalse(ServerRuntime.JSON.writeValueAsString(service.readCollectionPage(jobId, 0, 16, null, null, null))
                    .contains("never-publish-this-token"), "Unmapped SQL result columns must not escape in the collection API.");
            Set<String> observationIds = new java.util.HashSet<>();
            for (Map<String, Object> item : items) {
                List<Map<String, Object>> observations = maps(item.get("normalizedObservationsV2"));
                assertEquals(4, observations.size());
                for (Map<String, Object> observation : observations)
                    assertTrue(observationIds.add(String.valueOf(observation.get("observationId"))));
                Map<String, Object> groupView = object(item.get("groupView"));
                List<Map<String, Object>> assignments = maps(groupView.get("assignments"));
                assertEquals(4, assignments.size(), "Each of the four labelled observations receives one exact Group assignment.");
                assertEquals(Set.of("app-servers", "db-servers"), assignments.stream()
                        .map(assignment -> String.valueOf(assignment.get("groupId"))).collect(java.util.stream.Collectors.toSet()));
                for (Map<String, Object> assignment : assignments) {
                    Map<String, Object> labels = object(assignment.get("labels"));
                    assertEquals("instance", String.join(",", object(assignment.get("groupBy")).keySet()));
                    assertEquals(assignment.get("groupId").equals("app-servers") ? "app" : "db", labels.get("role"));
                }
            }
            assertEquals(12, observationIds.size(), "Three metric IDs across four label sets retain distinct identities.");
            Map<String, Object> offline = service.writeOfflineReportEvidence(jobId, temp.resolve("role-groups-offline"));
            assertEquals(2, maps(offline.get("groups")).size());
            List<Map<String, Object>> offlineRows = maps(offline.get("observations"));
            assertEquals(3, offlineRows.size(), "The offline manifest retains one integrity-checked sidecar per declared output.");
            assertEquals(12, offlineRows.stream().mapToLong(row ->
                    maps(object(row.get("groupView")).get("assignments")).size()).sum(),
                    "All twelve labelled normalized observations remain reachable through offline Group assignments.");
            assertTrue(offlineRows.stream().allMatch(row ->
                    !maps(object(row.get("groupView")).get("assignments")).isEmpty()));
            Set<Double> groupedValues = offlineRows.stream()
                    .flatMap(row -> maps(object(row.get("groupView")).get("assignments")).stream())
                    .map(assignment -> assignment.get("value"))
                    .filter(Number.class::isInstance).map(Number.class::cast).map(Number::doubleValue)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of(11d, 12d, 21d, 22d, 128d, 256d, 512d, 1024d, 3d, 4d, 5d, 6d), groupedValues);
            String offlineJson = ServerRuntime.JSON.writeValueAsString(offline);
            assertFalse(offlineJson.contains("never-publish-this-token"), "Unmapped SQL result columns must not escape in offline JSON.");

            var renderHtml = SutMetricsOfflineReportPublisher.class.getDeclaredMethod("renderHtml", String.class,
                    Map.class, Map.class, Class.forName("att.server.SutMetricsOfflineReportPublisher$Verdict"));
            renderHtml.setAccessible(true);
            Class<?> verdictType = Class.forName("att.server.SutMetricsOfflineReportPublisher$Verdict");
            var verdictConstructor = verdictType.getDeclaredConstructors()[0];
            verdictConstructor.setAccessible(true);
            Object verdict = verdictConstructor.newInstance("NOT_PROVIDED", "NOT_PROVIDED", null, null, null, "", "");
            String html = new String((byte[]) renderHtml.invoke(null, jobId, Map.of("status", "PASS"), offline, verdict), StandardCharsets.UTF_8);
            for (double expected : groupedValues) assertTrue(html.contains(String.valueOf((long) expected)),
                    "Offline group rows should show value " + expected + ".");
            assertFalse(html.contains("never-publish-this-token"));
        }
    }

    @Test void invalidWideRowValueOnlyMarksItsOwnLabelledOutputRowPartial() throws Exception {
        String jobId = "J0000000000000416";
        try (SutMetricsService service = new SutMetricsService(temp.resolve("wide-row-invalid-value"))) {
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "wide-row",
                    "name", "Wide row", "connection", map("kind", "DBHELPER", "id", "orders", "revision", "a".repeat(64)),
                    "fetch", map("id", "summary", "mode", "dbhelper-query",
                            "call", "#{db.orders.query(sql='select instance, cpu, sessions from summary')}",
                            "parameters", Map.of(), "sampling", "afterDrain"),
                    "mapping", map("labels", map("instance", "INSTANCE"), "outputs", List.of(
                            map("id", "cpu", "title", "CPU", "unit", "percent", "kind", "gauge", "column", "CPU"),
                            map("id", "sessions", "title", "Sessions", "unit", "count", "kind", "gauge", "column", "SESSIONS"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 64, "maxArtifactBytes", 1048576)));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v2", "id", "wide-row-profile",
                    "name", "Wide row", "metricSourceIds", List.of("wide-row"), "groupIds", List.of(),
                    "allowedBindings", map("environments", List.of("TEST"), "targets", List.of(),
                            "tenants", List.of(), "parameters", Map.of()),
                    "budgets", map("maxFetches", 1, "maxObservations", 64, "maxArtifactBytes", 1048576)));
            service.setBusinessCallExecutor((ignoredJob, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    return map("rowCount", 3, "rows", List.of(
                            map("INSTANCE", "app-1", "CPU", 11, "SESSIONS", 3),
                            map("INSTANCE", "app-2", "CPU", "bad-number", "SESSIONS", 4),
                            map("INSTANCE", "db-1", "CPU", 21, "SESSIONS", 5)));
                }
                @Override public void close() { }
            });
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(map("profile", "wide-row-profile")),
                    "1m", "1s", "15s"), "alice", false, multiOutputBusinessContext(List.of()));
            service.writeSnapshot(jobId, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(jobId, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, jobId);
            assertEquals("PARTIAL", manifest.get("state"));
            List<Map<String, Object>> items = maps(service.readCollectionPage(jobId, 0, 16, null, null, null).get("items"));
            Map<String, Object> cpu = items.stream().filter(item -> "cpu".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals("PARTIAL", cpu.get("queryStatus"));
            List<Map<String, Object>> cpuValues = maps(cpu.get("labeledValues"));
            assertEquals(3, cpuValues.size(), "Valid sibling rows stay present when another row has an invalid numeric value.");
            Map<String, Object> invalid = cpuValues.stream().filter(value -> "app-2".equals(object(value.get("labels")).get("instance"))).findFirst().orElseThrow();
            assertNull(invalid.get("value"));
            assertEquals("PARTIAL", invalid.get("state"));
            assertEquals("BUSINESS_METRIC_VALUE_INVALID", maps(invalid.get("diagnostics")).get(0).get("code"));
            assertEquals(Set.of(11d, 21d), cpuValues.stream().filter(value -> value.get("value") instanceof Number)
                    .map(value -> ((Number) value.get("value")).doubleValue()).collect(java.util.stream.Collectors.toSet()));
            Map<String, Object> normalizedInvalid = maps(cpu.get("normalizedObservationsV2")).stream()
                    .filter(observation -> "app-2".equals(object(object(observation.get("identity")).get("labels")).get("instance")))
                    .findFirst().orElseThrow();
            assertEquals("PARTIAL", object(normalizedInvalid.get("data")).get("state"));
            assertEquals("BUSINESS_METRIC_VALUE_INVALID", maps(object(normalizedInvalid.get("data")).get("diagnostics")).get(0).get("code"));
            Map<String, Object> sessions = items.stream().filter(item -> "sessions".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals("READY", sessions.get("queryStatus"), "Another declared output remains usable across all three rows.");
            assertEquals(Set.of(3d, 4d, 5d), maps(sessions.get("labeledValues")).stream()
                    .map(value -> ((Number) value.get("value")).doubleValue()).collect(java.util.stream.Collectors.toSet()));
            Map<String, Object> offline = service.writeOfflineReportEvidence(jobId, temp.resolve("wide-row-no-groups-offline"));
            assertTrue(maps(offline.get("groups")).isEmpty());
            Map<String, Object> offlineCpu = maps(offline.get("observations")).stream()
                    .filter(value -> "cpu".equals(object(value.get("metric")).get("id"))).findFirst().orElseThrow();
            assertEquals(3, maps(offlineCpu.get("labeledValues")).size());
            att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/api/att-load-offline-observations-v1.schema.json"),
                    ServerRuntime.JSON.writeValueAsString(offline));
            String html = renderOfflineHtml(jobId, offline);
            assertTrue(html.contains("instance=app-1"));
            assertTrue(html.contains("11.0 percent"));
            assertTrue(html.contains("instance=app-2"));
            assertTrue(html.contains("No numeric value"));
            assertTrue(html.contains("PARTIAL"));
            assertTrue(html.contains("instance=db-1"));
            assertTrue(html.contains("21.0 percent"));
            assertTrue(html.contains("4.0 count"));
            assertTrue(html.contains("Observed at (UTC)"));
            assertTrue(html.contains(String.valueOf(object(offlineCpu.get("window")).get("observedAt"))));
        }
    }

    @Test void flatProfileWorkloadAllowlistRejectsUnlistedWorkloads() throws Exception {
        String jobId = "J0000000000000415";
        try (SutMetricsService service = new SutMetricsService(temp.resolve("flat-workload-allowlist"))) {
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "workload-summary",
                    "name", "Workload summary", "connection", map("kind", "DBHELPER", "id", "orders", "revision", "a".repeat(64)),
                    "fetch", map("id", "workload-cpu", "mode", "dbhelper-query",
                            "call", "#{db.orders.query(sql='select cpu_pct from metric_summary')}",
                            "parameters", Map.of(), "sampling", "afterDrain"),
                    "mapping", map("allowedLabels", List.of(), "outputs", List.of(
                            map("id", "cpu", "title", "CPU", "unit", "percent", "kind", "gauge", "column", "CPU_PCT"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 64, "maxArtifactBytes", 1048576)));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v2", "id", "workload-limited",
                    "name", "One workload only", "metricSourceIds", List.of("workload-summary"), "groupIds", List.of(),
                    "allowedBindings", map("environments", List.of("TEST"),
                            "targets", List.of(map("type", "workload", "id", "app-1")),
                            "tenants", List.of(), "parameters", Map.of()),
                    "budgets", map("maxFetches", 1, "maxObservations", 16, "maxArtifactBytes", 1048576)));
            List<SutMetricsService.WorkloadSubject> subjects = List.of(
                    new SutMetricsService.WorkloadSubject("app-1", null, "target", "app-host", false),
                    new SutMetricsService.WorkloadSubject("db-1", null, "target", "db-host", false));
            SutMetricsService.BusinessCallContext context = multiOutputBusinessContext(subjects);
            Map<String, Object> allowedScenario = scenario(List.of(map("profile", "workload-limited",
                    "scope", "workload", "workloadId", "app-1")), "1m", "1s", "15s");
            Map<String, Object> snapshot = service.resolveScenario(allowedScenario, "alice", false, context);
            assertEquals(Set.of("app-1"), maps(snapshot.get("queries")).stream()
                    .map(query -> String.valueOf(query.get("workloadId"))).collect(java.util.stream.Collectors.toSet()));
            assertThrows(IllegalArgumentException.class, () -> service.resolveScenario(
                    scenario(List.of(map("profile", "workload-limited", "scope", "workload", "workloadId", "db-1")),
                            "1m", "1s", "15s"), "alice", false, context));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((ignoredJob, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 1, "rows", List.of(map("CPU_PCT", 74)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(jobId, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(jobId, finishedAt.minusSeconds(30), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, jobId);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, invocations.get());
            Map<String, Object> item = maps(service.readCollectionPage(jobId, 0, 16, null, null, null).get("items")).get(0);
            Map<String, Object> observation = maps(item.get("normalizedObservationsV2")).get(0);
            assertEquals("app-1", object(object(observation.get("identity")).get("subject")).get("workloadId"));
        }
    }

    @Test void flatProfileEnforcesPinnedMetricSourceRowSeriesSampleAndArtifactLimits() throws Exception {
        Path data = temp.resolve("flat-metric-source-budgets");
        List<Map<String, Object>> cases = List.of(
                map("jobId", "J0000000000000411", "limit", "maxRows", "value", 3, "diagnostic", "BUSINESS_MAPPING_ROW_LIMIT_EXCEEDED"),
                map("jobId", "J0000000000000412", "limit", "maxSeries", "value", 8, "diagnostic", "BUSINESS_MAPPING_SERIES_LIMIT_EXCEEDED"),
                map("jobId", "J0000000000000413", "limit", "maxSamples", "value", 11, "diagnostic", "QUERY_BUDGET_EXCEEDED"),
                map("jobId", "J0000000000000414", "limit", "maxArtifactBytes", "value", 1024, "diagnostic", "QUERY_BUDGET_EXCEEDED"));
        try (SutMetricsService service = new SutMetricsService(data)) {
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "budget-wide-row",
                    "name", "Budget wide row", "connection", map("kind", "DBHELPER", "id", "orders", "revision", "a".repeat(64)),
                    "fetch", map("id", "budget-summary", "mode", "dbhelper-query",
                            "call", "#{db.orders.query(sql='select instance, role, cpu_pct, memory_mb, sessions from metric_summary')}",
                            "parameters", Map.of(), "sampling", "afterDrain"),
                    "mapping", map("allowedLabels", List.of("instance", "role"),
                            "labelColumns", map("instance", "INSTANCE", "role", "ROLE"),
                            "outputs", List.of(
                                    map("id", "cpu", "title", "CPU", "unit", "percent", "kind", "gauge", "column", "CPU_PCT"),
                                    map("id", "memory", "title", "Memory", "unit", "bytes", "kind", "gauge", "column", "MEMORY_MB"),
                                    map("id", "sessions", "title", "Sessions", "unit", "count", "kind", "gauge", "column", "SESSIONS"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 64, "maxArtifactBytes", 1048576)));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v2", "id", "source-budget-profile",
                    "name", "Metric Source budgets", "metricSourceIds", List.of("budget-wide-row"), "groupIds", List.of(),
                    "allowedBindings", map("environments", List.of("TEST"), "targets", List.of(),
                            "tenants", List.of(), "parameters", Map.of()),
                    "budgets", map("maxFetches", 1, "maxObservations", 64, "maxArtifactBytes", 1048576)));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((ignoredJob, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 4, "rows", List.of(
                            map("INSTANCE", "app-1", "ROLE", "app", "CPU_PCT", 11, "MEMORY_MB", 128, "SESSIONS", 3),
                            map("INSTANCE", "app-2", "ROLE", "app", "CPU_PCT", 12, "MEMORY_MB", 256, "SESSIONS", 4),
                            map("INSTANCE", "db-1", "ROLE", "db", "CPU_PCT", 21, "MEMORY_MB", 512, "SESSIONS", 5),
                            map("INSTANCE", "db-2", "ROLE", "db", "CPU_PCT", 22, "MEMORY_MB", 1024, "SESSIONS", 6)));
                }
                @Override public void close() { }
            });
            for (Map<String, Object> budgetCase : cases) {
                Map<String, Object> limits = map("maxRows", 64, "maxSeries", 16, "maxSamples", 64, "maxArtifactBytes", 1048576);
                limits.put(String.valueOf(budgetCase.get("limit")), budgetCase.get("value"));
                service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "budget-wide-row",
                        "name", "Budget wide row", "connection", map("kind", "DBHELPER", "id", "orders", "revision", "a".repeat(64)),
                        "fetch", map("id", "budget-summary", "mode", "dbhelper-query",
                                "call", "#{db.orders.query(sql='select instance, role, cpu_pct, memory_mb, sessions from metric_summary')}",
                                "parameters", Map.of(), "sampling", "afterDrain"),
                        "mapping", map("allowedLabels", List.of("instance", "role"),
                                "labelColumns", map("instance", "INSTANCE", "role", "ROLE"),
                                "outputs", List.of(
                                        map("id", "cpu", "title", "CPU", "unit", "percent", "kind", "gauge", "column", "CPU_PCT"),
                                        map("id", "memory", "title", "Memory", "unit", "bytes", "kind", "gauge", "column", "MEMORY_MB"),
                                        map("id", "sessions", "title", "Sessions", "unit", "count", "kind", "gauge", "column", "SESSIONS"))),
                        "limits", limits));
                Map<String, Object> snapshot = service.resolveScenario(
                        scenario(List.of(map("profile", "source-budget-profile")), "1m", "1s", "15s"),
                        "alice", false, multiOutputBusinessContext(List.of()));
                Map<String, Object> pinned = maps(snapshot.get("metricSources")).get(0);
                assertEquals(limits, object(pinned.get("limits")), "Metric Source limits must survive in the immutable v2 snapshot.");
                assertEquals(1, maps(snapshot.get("fetchPlan")).size());
                String jobId = String.valueOf(budgetCase.get("jobId"));
                service.writeSnapshot(jobId, snapshot);
                Instant finishedAt = Instant.now().minusSeconds(30);
                service.jobFinished(jobId, finishedAt.minusSeconds(30), finishedAt);
                Map<String, Object> manifest = awaitManifest(service, jobId);
                assertTrue(Set.of("FAILED", "PARTIAL").contains(manifest.get("state")));
                assertTrue(maps(manifest.get("diagnostics")).stream().anyMatch(diagnostic ->
                        budgetCase.get("diagnostic").equals(diagnostic.get("code"))));
                long writtenBytes = maps(manifest.get("files")).stream()
                        .mapToLong(file -> ((Number) file.get("lengthBytes")).longValue()).sum();
                if ("maxArtifactBytes".equals(budgetCase.get("limit")))
                    assertTrue(writtenBytes <= 1024, "Metric Source artifact bytes must stop at the pinned source cap.");
            }
            assertEquals(4, invocations.get(), "Each Job performs one shared source invocation, including over-budget mappings.");
        }
    }

    @Test void flatProfileObservationAndArtifactBudgetsStopCollectionBeforeWritingOverLimit() throws Exception {
        Path data = temp.resolve("flat-profile-budgets");
        String observationBudgetJob = "J0000000000000401";
        String artifactBudgetJob = "J0000000000000402";
        try (PrometheusFixture fixture = new PrometheusFixture(SutMetricsServiceTest::twoInstanceResponse);
             SutMetricsService service = new SutMetricsService(data)) {
            Map<String, Object> sourceView = service.upsertSource(source("prom", fixture.url(), List.of("alice"), Map.of()));
            service.upsertMetricSource(map("schemaVersion", "att-sut-metrics-metric-source/v1", "id", "host-metrics",
                    "name", "Host metrics", "connection", map("kind", "DATA_SOURCE", "id", "prom",
                            "revision", sourceView.get("revision")),
                    "fetch", map("id", "host-up", "mode", "prometheus-range", "query", "up",
                            "parameters", Map.of(), "sampling", "range", "step", "15s"),
                    "mapping", map("allowedLabels", List.of("instance"), "outputs", List.of(
                            map("id", "up", "title", "Up", "unit", "count", "kind", "gauge", "metricName", "up"))),
                    "limits", map("maxRows", 64, "maxSeries", 16, "maxSamples", 1000, "maxArtifactBytes", 1048576)));
            Map<String, Object> allowedBindings = map("environments", List.of(),
                    "targets", List.of(map("type", "target", "id", "sut-1")),
                    "tenants", List.of(), "parameters", Map.of());
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v2", "id", "observation-cap",
                    "name", "Observation cap", "metricSourceIds", List.of("host-metrics"), "groupIds", List.of(),
                    "allowedBindings", allowedBindings,
                    "budgets", map("maxFetches", 1, "maxObservations", 1, "maxArtifactBytes", 1048576)));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v2", "id", "artifact-cap",
                    "name", "Artifact cap", "metricSourceIds", List.of("host-metrics"), "groupIds", List.of(),
                    "allowedBindings", allowedBindings,
                    "budgets", map("maxFetches", 1, "maxObservations", 64, "maxArtifactBytes", 1024)));

            for (String[] run : List.of(new String[]{"observation-cap", observationBudgetJob},
                    new String[]{"artifact-cap", artifactBudgetJob})) {
                Map<String, Object> snapshot = service.resolveScenario(
                        scenario(List.of(map("profile", run[0])), "1m", "1s", "15s"), "alice");
                service.writeSnapshot(run[1], snapshot);
                Instant finishedAt = Instant.now().minusSeconds(30);
                service.jobFinished(run[1], finishedAt.minusSeconds(60), finishedAt);
                Map<String, Object> manifest = awaitManifest(service, run[1]);
                assertEquals("FAILED", manifest.get("state"));
                assertTrue(maps(manifest.get("diagnostics")).stream()
                        .anyMatch(item -> "QUERY_BUDGET_EXCEEDED".equals(item.get("code"))), manifest.toString());
                assertTrue(maps(manifest.get("files")).isEmpty(), "Over-budget sidecars must not be written.");
                assertEquals(0, ((Number) object(manifest.get("coverage")).get("artifactBytes")).intValue());
            }
        }
    }

    @Test void failedWideRowCallIsSharedAndLeavesEveryOutputUnavailable() throws Exception {
        Path data = temp.resolve("failed-wide-row");
        try (SutMetricsService service = new SutMetricsService(data)) {
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "failed-wide",
                    "name", "Failed wide row", "kind", "base", "category", "app", "metrics", List.of(map(
                            "id", "instance-summary",
                            "call", "#{db.orders.query(sql='select instance, role, cpu_pct from metric_summary')}",
                            "sampling", "afterDrain",
                            "mapping", map("rows", "rows", "allowedLabels", List.of("instance", "role"),
                                    "labelColumns", map("instance", "INSTANCE", "role", "ROLE")),
                            "outputs", List.of(
                                    map("id", "cpu", "title", "CPU percent", "column", "CPU_PCT", "unit", "percent"),
                                    map("id", "sessions", "title", "Sessions", "column", "SESSIONS", "unit", "count"))))));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    throw new IllegalStateException("database unavailable");
                }
                @Override public void close() { }
            });
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(map("profile", "failed-wide")),
                    "1m", "1s", "15s"), "alice");
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(30), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("FAILED", manifest.get("state"));
            assertEquals(1, invocations.get(), "A failed fetch is shared across every output and is never repeated.");
            List<Map<String, Object>> files = maps(manifest.get("files"));
            assertEquals(2, files.size());
            assertTrue(files.stream().allMatch(file -> "UNAVAILABLE".equals(file.get("queryStatus"))));
            for (Map<String, Object> file : files) {
                Map<String, Object> sidecar = ServerRuntime.JSON.readValue(Files.readString(
                        data.resolve("jobs").resolve(JOB_ID).resolve("sut-metrics").resolve(String.valueOf(file.get("path")))), Map.class);
                Map<String, Object> normalized = maps(sidecar.get("normalizedObservationsV2")).get(0);
                assertEquals("UNAVAILABLE", object(normalized.get("data")).get("state"));
            }
        }
    }

    @Test void oneMultiOutputCallMapsEachColumnToItsExactMixAndThresholdSelection() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output"))) {
            service.upsertProfile(multiOutputBusinessProfile("business"));
            List<SutMetricsService.WorkloadSubject> subjects = multiMixSubjects("checkout");
            Map<String, Object> configured = scenario(List.of(map("profile", "business")), "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "mobile-success-max", "profile", "business",
                    "metric", "success", "max", 10.0, "subjects", List.of(map("scope", "mix",
                            "workloadId", "checkout", "mixId", "mobile")))));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false,
                    multiOutputBusinessContext(subjects));
            List<Map<String, Object>> queries = maps(snapshot.get("queries"));
            assertEquals(4, queries.size());
            assertEquals(1, queries.stream().map(query -> query.get("callGroupId")).distinct().count());
            assertEquals(Set.of("web", "mobile"), queries.stream().map(query -> query.get("mixId")).collect(java.util.stream.Collectors.toSet()));
            Map<String, Object> rule = maps(snapshot.get("thresholds")).get(0);
            assertEquals("selected", rule.get("targets"));
            assertEquals(1, ((List<?>) rule.get("queryIds")).size());
            Map<String, Object> selectedQuery = queries.stream().filter(query -> query.get("id").equals(((List<?>) rule.get("queryIds")).get(0))).findFirst().orElseThrow();
            assertEquals("checkout", selectedQuery.get("workloadId"));
            assertEquals("mobile", selectedQuery.get("mixId"));
            assertEquals("same-target", selectedQuery.get("targetId"));

            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 2, "rows", List.of(
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", 20, "FAILURE", 2),
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "mobile", "SUCCESS", 8, "FAILURE", 0)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, invocations.get());
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(4, items.size());
            assertEquals(1, items.stream().map(item -> item.get("callGroupId")).distinct().count());
            assertEquals(1, items.stream().map(item -> item.get("callCount")).distinct().count());
            assertEquals(1, items.stream().map(item -> item.get("mappingStatus")).distinct().count());
            Map<String, Object> webSuccess = items.stream().filter(item -> "web".equals(item.get("mixId")) && "success".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals(20.0, webSuccess.get("value"));
            att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/att-sut-metrics-observation-v1.schema.json"),
                    ServerRuntime.JSON.writeValueAsString(webSuccess));
            Map<String, Object> mobileFailure = items.stream().filter(item -> "mobile".equals(item.get("mixId")) && "failure".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals(0.0, mobileFailure.get("value"));

            Map<String, Object> offline = service.writeOfflineReportEvidence(JOB_ID, temp.resolve("offline-report"));
            List<Map<String, Object>> offlineItems = maps(offline.get("observations"));
            Map<String, Object> offlineMobile = offlineItems.stream().filter(item -> "mobile".equals(item.get("mixId"))
                    && "success".equals(object(item.get("metric")).get("id"))).findFirst().orElseThrow();
            assertEquals("checkout", offlineMobile.get("workloadId"));
            assertEquals(1, offlineMobile.get("callCount"));
            assertEquals("MAPPED", offlineMobile.get("mappingStatus"));
            att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/api/att-load-offline-observations-v1.schema.json"),
                    ServerRuntime.JSON.writeValueAsString(offline));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("MET", evaluation.get("state"));
            Map<String, Object> evaluatedRule = maps(object(evaluation.get("sutThresholds")).get("items")).get(0);
            assertEquals("MET", evaluatedRule.get("status"));
            List<Map<String, Object>> evaluatedTargets = maps(evaluatedRule.get("targets"));
            assertEquals(1, evaluatedTargets.size());
            assertEquals("mobile", evaluatedTargets.get(0).get("mixId"));
            assertEquals(8.0, evaluatedTargets.get(0).get("actual"));
        }
    }

    @Test void normalizedMultiOutputProfilesSurviveReloadEditAndSave() throws Exception {
        Path catalogRoot = temp.resolve("business-multi-output-catalog-roundtrip");
        Map<String, Object> submitted = multiOutputBusinessProfile("catalog-roundtrip");
        Map<String, Object> saved;
        try (SutMetricsService service = new SutMetricsService(catalogRoot)) {
            saved = service.upsertProfile(submitted);
            att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/att-sut-metrics-profile-v1.schema.json"),
                    ServerRuntime.JSON.writeValueAsString(saved));
            Map<String, Object> firstMetric = maps(saved.get("metrics")).get(0);
            assertEquals("transaction-query", firstMetric.get("callGroupId"));
            assertEquals("SUCCESS", firstMetric.get("column"));
            assertEquals(Boolean.TRUE, firstMetric.get("multiOutput"));
        }

        try (SutMetricsService service = new SutMetricsService(catalogRoot)) {
            Map<String, Object> loaded = maps(service.listProfiles("alice", true).get("items")).get(0);
            assertEquals(saved.get("metrics"), loaded.get("metrics"));
            loaded.put("name", "Edited after restart");
            saved = service.upsertProfile(loaded);
            assertEquals("Edited after restart", saved.get("name"));
        }

        try (SutMetricsService service = new SutMetricsService(catalogRoot)) {
            Map<String, Object> loadedAgain = maps(service.listProfiles("alice", true).get("items")).get(0);
            assertEquals("Edited after restart", loadedAgain.get("name"));
            assertEquals(saved.get("metrics"), loadedAgain.get("metrics"));
        }
    }

    @Test void multiOutputSteadyWindowRetainsWindowQualityForThresholdEvaluation() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-window-quality"))) {
            Map<String, Object> profile = multiOutputBusinessProfile("window-quality");
            Map<String, Object> group = new LinkedHashMap<>(maps(profile.get("metrics")).get(0));
            group.put("sampling", "windowQuery");
            group.put("window", "steady");
            profile.put("metrics", List.of(group));
            service.upsertProfile(profile);
            Map<String, Object> configured = scenario(List.of(map("profile", "window-quality")), "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "success-max", "profile", "window-quality",
                    "metric", "success", "max", 10.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 2, "rows", List.of(
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", 20, "FAILURE", 2),
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "mobile", "SUCCESS", 8, "FAILURE", 0)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant startedAt = Instant.now().minusSeconds(180), finishedAt = Instant.now().minusSeconds(60);
            Instant steadyStart = startedAt.plusSeconds(20), steadyEnd = finishedAt.minusSeconds(20);
            service.jobFinished(JOB_ID, startedAt, finishedAt, List.of(phase("STEADY", steadyStart, steadyEnd, true)));

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, invocations.get());
            Map<String, Object> successFile = maps(manifest.get("files")).stream()
                    .filter(file -> "success".equals(file.get("metricId")) && "web".equals(file.get("mixId")))
                    .findFirst().orElseThrow();
            Path sidecarPath = temp.resolve("business-multi-output-window-quality").resolve("jobs").resolve(JOB_ID)
                    .resolve("sut-metrics").resolve(String.valueOf(successFile.get("path")));
            Map<String, Object> sidecar = ServerRuntime.JSON.readValue(Files.readAllBytes(sidecarPath), Map.class);
            Map<String, Object> observation = object(sidecar.get("observation"));
            assertEquals("steady-window-query", object(observation.get("window")).get("kind"));
            assertEquals("AUTHOR_DEFINED_WINDOW_QUERY", object(observation.get("availability")).get("quality"));
            assertEquals("MAPPED", object(observation.get("availability")).get("mappingStatus"));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("NOT_MET", evaluation.get("state"));
            Map<String, Object> rule = maps(object(evaluation.get("sutThresholds")).get("items")).get(0);
            assertEquals("NOT_MET", rule.get("status"));
        }
    }

    @Test void unmatchedRowsDoNotHideAProvenMultiOutputThresholdViolation() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-partial-violation"))) {
            service.upsertProfile(multiOutputBusinessProfile("partial-violation"));
            Map<String, Object> configured = scenario(List.of(map("profile", "partial-violation")), "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "success-max", "profile", "partial-violation",
                    "metric", "success", "max", 10.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    return map("rowCount", 3, "rows", List.of(
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", 20, "FAILURE", 2),
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "mobile", "SUCCESS", 8, "FAILURE", 0),
                            map("WORKLOAD_ID", "unknown", "MIX_ID", "other", "SUCCESS", 99, "FAILURE", 1)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("PARTIAL", manifest.get("state"));
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            Map<String, Object> web = items.stream().filter(item -> "success".equals(item.get("metricId"))
                    && "web".equals(item.get("mixId"))).findFirst().orElseThrow();
            assertEquals(20.0, web.get("value"));
            assertEquals("MAPPED", web.get("mappingStatus"));
            assertEquals(1, web.get("unmatchedRowCount"));
            assertEquals("PARTIAL", web.get("queryStatus"));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("NOT_MET", evaluation.get("state"));
            Map<String, Object> rule = maps(object(evaluation.get("sutThresholds")).get("items")).get(0);
            assertEquals("NOT_MET", rule.get("status"));
            Map<String, Object> violatingTarget = maps(rule.get("targets")).stream()
                    .filter(target -> "web".equals(target.get("mixId"))).findFirst().orElseThrow();
            assertEquals("NOT_MET", violatingTarget.get("status"));
        }
    }

    @Test void missingMixIdentityDoesNotInvalidateIndependentlyMappedSlots() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-missing-mix-identity"))) {
            service.upsertProfile(multiOutputBusinessProfile("missing-mix-identity"));
            Map<String, Object> configured = scenario(List.of(map("profile", "missing-mix-identity")), "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "web-success-max", "profile", "missing-mix-identity",
                    "metric", "success", "max", 10.0, "subjects", List.of(map("scope", "mix",
                            "workloadId", "checkout", "mixId", "web")))));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    return map("rowCount", 3, "rows", List.of(
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", 20, "FAILURE", 2),
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "mobile", "SUCCESS", 8, "FAILURE", 0),
                            map("WORKLOAD_ID", "checkout", "MIX_ID", null, "SUCCESS", 99, "FAILURE", 1)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("PARTIAL", manifest.get("state"));
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            Map<String, Object> web = items.stream().filter(item -> "success".equals(item.get("metricId"))
                    && "web".equals(item.get("mixId"))).findFirst().orElseThrow();
            assertEquals(20.0, web.get("value"));
            assertEquals("MAPPED", web.get("mappingStatus"));
            assertEquals(1, web.get("unmatchedRowCount"));
            Map<String, Object> mobile = items.stream().filter(item -> "success".equals(item.get("metricId"))
                    && "mobile".equals(item.get("mixId"))).findFirst().orElseThrow();
            assertEquals(8.0, mobile.get("value"));
            assertEquals("MAPPED", mobile.get("mappingStatus"));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("NOT_MET", evaluation.get("state"));
            Map<String, Object> rule = maps(object(evaluation.get("sutThresholds")).get("items")).get(0);
            assertEquals("NOT_MET", rule.get("status"));
        }
    }

    @Test void mixedWorkloadAggregateMapsAtWorkloadScopeWithoutMixAttribution() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-workload-aggregate"))) {
            service.upsertProfile(multiOutputBusinessProfile("workload-aggregate", false));
            Map<String, Object> configured = scenario(List.of(map("profile", "workload-aggregate")), "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "workload-success-max", "profile", "workload-aggregate",
                    "metric", "success", "max", 10.0, "subjects", List.of(map("scope", "workload",
                            "workloadId", "checkout")))));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            List<Map<String, Object>> queries = maps(snapshot.get("queries"));
            assertEquals(2, queries.size(), "Workload aggregation produces one subject per declared output.");
            assertTrue(queries.stream().allMatch(query -> "workload".equals(query.get("scope"))
                    && "checkout".equals(query.get("workloadId")) && query.get("mixId") == null));

            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 1, "rows", List.of(
                            map("WORKLOAD_ID", "checkout", "SUCCESS", 20, "FAILURE", 3)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(2, items.size());
            Map<String, Object> success = items.stream()
                    .filter(item -> "success".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals(20.0, success.get("value"));
            assertEquals("workload", success.get("scope"));
            assertEquals("checkout", success.get("workloadId"));
            assertNull(success.get("mixId"), "An aggregate is not attributed to any mix slot.");
            assertEquals("MAPPED", success.get("mappingStatus"));
            assertEquals(1, invocations.get(), "The outputs share one query invocation.");

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("NOT_MET", evaluation.get("state"));
            Map<String, Object> rule = maps(object(evaluation.get("sutThresholds")).get("items")).get(0);
            assertEquals("NOT_MET", rule.get("status"));
        }
    }

    @Test void packageToolTypedMapCanSupplySeveralUnpartitionedMetrics() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-tool-typed-map"))) {
            Map<String, Object> group = map("id", "report-query", "call", "#{reporter(summary='private')}",
                    "sampling", "afterDrain", "sideEffectsAcknowledged", true,
                    "outputs", List.of(
                            map("id", "success", "title", "Successful transactions", "column", "SUCCESS", "unit", "transactions", "kind", "counter"),
                            map("id", "failure", "title", "Failed transactions", "column", "FAILED", "unit", "transactions", "kind", "counter")));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "tool-map",
                    "name", "Tool Map", "kind", "base", "category", "app", "metrics", List.of(group)));
            Map<String, Object> snapshot = service.resolveScenario(
                    scenario(List.of(map("profile", "tool-map")), "1m", "1s", "15s"), "alice");
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("SUCCESS", 12, "FAILED", 3);
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);

            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, invocations.get());
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(2, items.size());
            assertEquals(12.0, items.stream().filter(item -> "success".equals(item.get("metricId"))).findFirst().orElseThrow().get("value"));
            assertEquals(3.0, items.stream().filter(item -> "failure".equals(item.get("metricId"))).findFirst().orElseThrow().get("value"));
            assertTrue(items.stream().allMatch(item -> "JOB_SHARED".equals(item.get("mappingStatus"))));
        }
    }

    @Test void oneJobWideResultRowProducesSeveralIndependentMetrics() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-one-row"))) {
            Map<String, Object> group = map("id", "transaction-query",
                    "call", "#{db.orders.query(sql='select total_txns, success_txns, failed_txns, pending_txns from transaction_summary')}",
                    "sampling", "afterDrain", "mapping", map("rows", "rows"),
                    "outputs", List.of(
                            map("id", "total", "title", "Total transactions", "column", "TOTAL_TXNS", "unit", "transactions", "kind", "counter"),
                            map("id", "success", "title", "Successful transactions", "column", "SUCCESS_TXNS", "unit", "transactions", "kind", "counter"),
                            map("id", "failure", "title", "Failed transactions", "column", "FAILED_TXNS", "unit", "transactions", "kind", "counter"),
                            map("id", "pending", "title", "Pending transactions", "column", "PENDING_TXNS", "unit", "transactions", "kind", "gauge")));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "business-row",
                    "name", "Business row", "kind", "base", "category", "db", "metrics", List.of(group)));
            Map<String, Object> snapshot = service.resolveScenario(
                    scenario(List.of(map("profile", "business-row")), "1m", "1s", "15s"), "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 1, "rows", List.of(map("TOTAL_TXNS", 10_000, "SUCCESS_TXNS", 9_900,
                            "FAILED_TXNS", 60, "PENDING_TXNS", 40)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(30);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, invocations.get());
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(4, items.size());
            assertEquals(Set.of("total", "success", "failure", "pending"),
                    items.stream().map(item -> String.valueOf(item.get("metricId"))).collect(java.util.stream.Collectors.toSet()));
            assertEquals(10_000.0, items.stream().filter(item -> "total".equals(item.get("metricId"))).findFirst().orElseThrow().get("value"));
            assertEquals(9_900.0, items.stream().filter(item -> "success".equals(item.get("metricId"))).findFirst().orElseThrow().get("value"));
            assertEquals(60.0, items.stream().filter(item -> "failure".equals(item.get("metricId"))).findFirst().orElseThrow().get("value"));
            assertEquals(40.0, items.stream().filter(item -> "pending".equals(item.get("metricId"))).findFirst().orElseThrow().get("value"));
            assertTrue(items.stream().allMatch(item -> "JOB_SHARED".equals(item.get("mappingStatus"))));
            assertTrue(items.stream().allMatch(item -> item.get("workloadId") == null && item.get("mixId") == null),
                    "The unpartitioned Job result stays shared despite configured Workload/mix subjects.");
            Map<String, Object> total = items.stream().filter(item -> "total".equals(item.get("metricId"))).findFirst().orElseThrow();
            Map<String, Object> pending = items.stream().filter(item -> "pending".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals("transactions", total.get("unit"));
            assertEquals("counter", total.get("kind"));
            assertEquals("transactions", pending.get("unit"));
            assertEquals("gauge", pending.get("kind"));
        }
    }

    @Test void fixedWorkloadRowsUseExactWorkloadIdsEvenWhenTargetsAreShared() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-fixed-workloads"))) {
            Map<String, Object> group = map("id", "workload-query",
                    "call", "#{db.orders.query(sql='select workload_id, success from transaction_summary')}",
                    "sampling", "afterDrain",
                    "mapping", map("rows", "rows", "workloadColumn", "WORKLOAD_ID"),
                    "outputs", List.of(map("id", "success", "title", "Successful transactions",
                            "column", "SUCCESS", "unit", "transactions", "kind", "counter")));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "business-fixed-workloads",
                    "name", "Business fixed Workloads", "kind", "base", "category", "db", "metrics", List.of(group)));
            List<SutMetricsService.WorkloadSubject> subjects = List.of(
                    new SutMetricsService.WorkloadSubject("outbound", null, "template", "shared-target", false),
                    new SutMetricsService.WorkloadSubject("inbound", null, "template", "shared-target", false));
            Map<String, Object> configured = scenario(List.of(map("profile", "business-fixed-workloads")), "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "success-max", "profile", "business-fixed-workloads",
                    "metric", "success", "max", 5_500.0)));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false,
                    multiOutputBusinessContext(subjects));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 2, "rows", List.of(map("WORKLOAD_ID", "outbound", "SUCCESS", 5_000),
                            map("WORKLOAD_ID", "inbound", "SUCCESS", 6_000)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("READY", manifest.get("state"));
            assertEquals(1, invocations.get());
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(2, items.size());
            Map<String, Object> outbound = items.stream().filter(item -> "outbound".equals(item.get("workloadId"))).findFirst().orElseThrow();
            Map<String, Object> inbound = items.stream().filter(item -> "inbound".equals(item.get("workloadId"))).findFirst().orElseThrow();
            assertEquals(5_000.0, outbound.get("value"));
            assertEquals(6_000.0, inbound.get("value"));
            assertEquals("MAPPED", outbound.get("mappingStatus"));
            assertEquals("MAPPED", inbound.get("mappingStatus"));
            assertEquals("shared-target", outbound.get("targetId"));
            assertEquals("shared-target", inbound.get("targetId"));

            Map<String, Object> evaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("NOT_MET", evaluation.get("state"), "The passing Workload must not hide a violation from the other Workload.");
            Map<String, Object> rule = maps(object(evaluation.get("sutThresholds")).get("items")).get(0);
            assertEquals("NOT_MET", rule.get("status"));
            List<Map<String, Object>> targets = maps(rule.get("targets"));
            assertEquals(2, targets.size());
            assertEquals(Set.of("MET", "NOT_MET"), targets.stream().map(target -> String.valueOf(target.get("status")))
                    .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void missingExpectedMixRemainsUnevaluatedUnlessAnotherSelectedMixProvesViolation() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-missing-mix"))) {
            service.upsertProfile(multiOutputBusinessProfile("business"));
            Map<String, Object> configured = scenario(List.of(map("profile", "business")), "1m", "1s", "15s");
            configured.put("thresholds", List.of(map("id", "success-max", "profile", "business",
                    "metric", "success", "max", 30.0)));
            SutMetricsService.BusinessCallContext callContext = multiOutputBusinessContext(multiMixSubjects("checkout"));
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false, callContext);
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 1, "rows", List.of(
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", 20, "FAILURE", 2)));
                }
                @Override public void close() { }
            });
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.writeSnapshot(JOB_ID, snapshot);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("PARTIAL", manifest.get("state"));
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(4, items.size());
            Map<String, Object> mobileSuccess = items.stream().filter(item -> "mobile".equals(item.get("mixId"))
                    && "success".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertNull(mobileSuccess.get("value"));
            assertEquals("NOT_EVALUATED", mobileSuccess.get("queryStatus"));
            assertEquals("BUSINESS_MAPPING_SUBJECT_MISSING",
                    maps(object(object(mobileSuccess.get("observation")).get("availability")).get("diagnostics")).get(0).get("code"));
            Map<String, Object> firstEvaluation = awaitThresholdEvaluation(service, JOB_ID);
            assertEquals("NOT_EVALUATED", firstEvaluation.get("state"));
            assertEquals("NOT_EVALUATED", maps(object(firstEvaluation.get("sutThresholds")).get("items")).get(0).get("status"));

            String violatingJobId = "J0000000000000002";
            configured.put("thresholds", List.of(map("id", "success-max", "profile", "business",
                    "metric", "success", "max", 10.0)));
            Map<String, Object> violatingSnapshot = service.resolveScenario(configured, "alice", false, callContext);
            service.writeSnapshot(violatingJobId, violatingSnapshot);
            service.jobFinished(violatingJobId, finishedAt.minusSeconds(60), finishedAt);
            awaitManifest(service, violatingJobId);
            Map<String, Object> violatingEvaluation = awaitThresholdEvaluation(service, violatingJobId);
            assertEquals("NOT_MET", violatingEvaluation.get("state"),
                    "A valid violation from one selected mix must take precedence over another mix's missing value.");
            assertEquals("NOT_MET", maps(object(violatingEvaluation.get("sutThresholds")).get("items")).get(0).get("status"));
            assertEquals(2, invocations.get(), "One shared call runs for each Job, regardless of output/subject count.");
        }
    }

    @Test void multiOutputRowsAboveTheBoundAreRejectedWithoutPersistingRawRows() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-row-bound"))) {
            Map<String, Object> group = map("id", "bounded-query",
                    "call", "#{db.orders.query(sql='select workload_id, mix_id, success from transaction_summary')}",
                    "sampling", "afterDrain",
                    "mapping", map("rows", "rows", "workloadColumn", "WORKLOAD_ID", "mixColumn", "MIX_ID"),
                    "outputs", List.of(map("id", "success", "title", "Successful transactions",
                            "column", "SUCCESS", "unit", "transactions", "kind", "counter")));
            service.upsertProfile(map("schemaVersion", "att-sut-metrics-profile/v1", "id", "business-row-bound",
                    "name", "Business row bound", "kind", "base", "category", "db", "metrics", List.of(group)));
            Map<String, Object> snapshot = service.resolveScenario(
                    scenario(List.of(map("profile", "business-row-bound")), "1m", "1s", "15s"), "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            List<Map<String, Object>> oversizedRows = new ArrayList<>(SutMetricsService.MAX_BUSINESS_RESULT_ROWS + 1);
            for (int index = 0; index <= SutMetricsService.MAX_BUSINESS_RESULT_ROWS; index++)
                oversizedRows.add(map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", "RAW_ROW_SENTINEL_" + index));
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    return map("rowCount", oversizedRows.size(), "rows", oversizedRows);
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("FAILED", manifest.get("state"));
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(2, items.size(), "The expected Workload/mix subjects remain visible when the row bound is exceeded.");
            assertTrue(items.stream().allMatch(item -> item.get("value") == null
                    && "NOT_EVALUATED".equals(item.get("queryStatus"))
                    && "INVALID".equals(item.get("mappingStatus"))
                    && Integer.valueOf(SutMetricsService.MAX_BUSINESS_RESULT_ROWS).equals(item.get("unmatchedRowCount"))));
            for (Map<String, Object> item : maps(manifest.get("files"))) {
                Path sidecar = temp.resolve("business-multi-output-row-bound").resolve("jobs").resolve(JOB_ID)
                        .resolve("sut-metrics").resolve(String.valueOf(item.get("path")));
                String serialized = new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8);
                assertFalse(serialized.contains("RAW_ROW_SENTINEL"), "Raw DB rows must not be persisted in the sidecar.");
                Map<String, Object> sidecarJson = ServerRuntime.JSON.readValue(serialized, Map.class);
                att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/att-sut-metrics-observation-v1.schema.json"), serialized);
                Map<String, Object> availability = object(object(sidecarJson.get("observation")).get("availability"));
                assertEquals("BUSINESS_MAPPING_ROW_LIMIT_EXCEEDED", maps(availability.get("diagnostics")).get(0).get("code"));
            }
            Map<String, Object> offline = service.writeOfflineReportEvidence(JOB_ID, temp.resolve("offline-row-bound-report"));
            att.validation.JsonSchemaVerifier.verifyJson(Path.of("schemas/api/att-load-offline-observations-v1.schema.json"),
                    ServerRuntime.JSON.writeValueAsString(offline));
        }
    }

    @Test void invalidOutputColumnDoesNotInvalidateSiblingsAndFailedCallInvalidatesEveryOutput() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-failures"))) {
            service.upsertProfile(multiOutputBusinessProfile("business"));
            Map<String, Object> configured = scenario(List.of(map("profile", "business")), "1m", "1s", "15s");
            Map<String, Object> snapshot = service.resolveScenario(configured, "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            AtomicInteger invocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    invocations.incrementAndGet();
                    return map("rowCount", 2, "rows", List.of(
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", 20, "FAILURE", "invalid"),
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "mobile", "SUCCESS", 8, "FAILURE", 0)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("PARTIAL", manifest.get("state"));
            assertEquals(1, invocations.get());
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            Map<String, Object> webSuccess = items.stream().filter(item -> "web".equals(item.get("mixId")) && "success".equals(item.get("metricId"))).findFirst().orElseThrow();
            Map<String, Object> webFailure = items.stream().filter(item -> "web".equals(item.get("mixId")) && "failure".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals(20.0, webSuccess.get("value"));
            assertNull(webFailure.get("value"));
            assertEquals("NOT_EVALUATED", webFailure.get("queryStatus"));
            assertEquals("BUSINESS_METRIC_VALUE_INVALID", maps(object(object(webFailure.get("observation")).get("availability")).get("diagnostics")).get(0).get("code"));
            assertEquals(8.0, items.stream().filter(item -> "mobile".equals(item.get("mixId")) && "success".equals(item.get("metricId"))).findFirst().orElseThrow().get("value"));

            String failedJob = "J0000000000000002";
            AtomicInteger failedInvocations = new AtomicInteger();
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    failedInvocations.incrementAndGet();
                    throw new IllegalStateException("database unavailable");
                }
                @Override public void close() { }
            });
            service.writeSnapshot(failedJob, snapshot);
            service.jobFinished(failedJob, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> failedManifest = awaitManifest(service, failedJob);
            assertEquals("FAILED", failedManifest.get("state"));
            assertEquals(1, failedInvocations.get());
            List<Map<String, Object>> failedItems = maps(service.readCollectionPage(failedJob, 0, 16, null, null, null).get("items"));
            assertEquals(4, failedItems.size());
            assertTrue(failedItems.stream().allMatch(item -> item.get("value") == null && "UNAVAILABLE".equals(item.get("queryStatus"))));
        }
    }

    @Test void duplicateAndUnknownBusinessRowsKeepExpectedSubjectsAndDiagnostics() throws Exception {
        try (SutMetricsService service = new SutMetricsService(temp.resolve("business-multi-output-row-errors"))) {
            service.upsertProfile(multiOutputBusinessProfile("business"));
            Map<String, Object> snapshot = service.resolveScenario(
                    scenario(List.of(map("profile", "business")), "1m", "1s", "15s"), "alice", false,
                    multiOutputBusinessContext(multiMixSubjects("checkout")));
            service.setBusinessCallExecutor((jobId, executionSnapshot) -> new SutMetricsService.BusinessCallSession() {
                @Override public Object execute(Map<String, Object> query, Instant executionStartedAt,
                                                Instant executionFinishedAt, Instant steadyStart,
                                                Instant steadyEnd, Instant observedAt) {
                    return map("rowCount", 4, "rows", List.of(
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", 20, "FAILURE", 2),
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "web", "SUCCESS", 21, "FAILURE", 3),
                            map("WORKLOAD_ID", "checkout", "MIX_ID", "mobile", "SUCCESS", 8, "FAILURE", 0),
                            map("WORKLOAD_ID", "unknown", "MIX_ID", "outside", "SUCCESS", 1, "FAILURE", 0)));
                }
                @Override public void close() { }
            });
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(60);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> manifest = awaitManifest(service, JOB_ID);
            assertEquals("PARTIAL", manifest.get("state"));
            List<Map<String, Object>> items = maps(service.readCollectionPage(JOB_ID, 0, 16, null, null, null).get("items"));
            assertEquals(4, items.size(), "Every pinned Workload/mix and output remains represented.");
            Map<String, Object> web = items.stream().filter(item -> "web".equals(item.get("mixId")) && "success".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertNull(web.get("value"));
            assertEquals("BUSINESS_MAPPING_DUPLICATE", maps(object(object(web.get("observation")).get("availability")).get("diagnostics")).get(0).get("code"));
            Map<String, Object> mobile = items.stream().filter(item -> "mobile".equals(item.get("mixId")) && "success".equals(item.get("metricId"))).findFirst().orElseThrow();
            assertEquals(8.0, mobile.get("value"));
            assertEquals("MAPPED", mobile.get("mappingStatus"));
            assertEquals(2, mobile.get("unmatchedRowCount"));
            assertEquals("BUSINESS_MAPPING_UNMATCHED_ROWS", maps(object(object(mobile.get("observation")).get("availability")).get("diagnostics")).get(0).get("code"));
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
        Map<String, Object> legacy = map("schemaVersion", "att-sut-metrics-snapshot/v1", "snapshotId", "business-snapshot",
                "createdAt", Instant.now().toString(), "collection", map("mode", "post-run", "before", "1s", "after", "1s", "step", "15s"),
                "livePreview", map("enabled", false), "provenance", List.of(), "queries", queries, "sources", Map.of(),
                "execution", map("packageId", "pkg", "environment", "test", "packageRevision", "a".repeat(64),
                        "runtimeConfigRevision", "b".repeat(64)), "thresholds", List.of());
        return SutMetricsContractMigration.upgradeSnapshot(legacy);
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

    private static Map<String, Object> multiOutputBusinessProfile(String id) {
        return multiOutputBusinessProfile(id, true);
    }

    private static Map<String, Object> multiOutputBusinessProfile(String id, boolean mapMixSlots) {
        Map<String, Object> mapping = map("rows", "rows", "workloadColumn", "WORKLOAD_ID");
        if (mapMixSlots) mapping.put("mixColumn", "MIX_ID");
        String call = mapMixSlots
                ? "#{db.orders.query(sql='select workload_id, mix_id, success, failure from transaction_summary')}"
                : "#{db.orders.query(sql='select workload_id, sum(success) as success, sum(failure) as failure from transaction_summary group by workload_id')}";
        return map("schemaVersion", "att-sut-metrics-profile/v1", "id", id, "name", id, "kind", "base", "category", "db",
                "metrics", List.of(map("id", "transaction-query",
                        "call", call,
                        "sampling", "afterDrain",
                        "mapping", mapping,
                        "outputs", List.of(
                                map("id", "success", "title", "Successful transactions", "column", "SUCCESS", "unit", "transactions", "kind", "counter"),
                                map("id", "failure", "title", "Failed transactions", "column", "FAILURE", "unit", "transactions", "kind", "counter")))));
    }

    private static List<SutMetricsService.WorkloadSubject> multiMixSubjects(String workloadId) {
        return List.of(new SutMetricsService.WorkloadSubject(workloadId, "web", "tool", "same-target", true),
                new SutMetricsService.WorkloadSubject(workloadId, "mobile", "tool", "same-target", true));
    }

    private static SutMetricsService.BusinessCallContext multiOutputBusinessContext(
            List<SutMetricsService.WorkloadSubject> workloadSubjects) {
        att.config.DbHelperConfig db = new att.config.DbHelperConfig("orders", "Orders", null,
                "jdbc:h2:mem:sut_multi_output", "sa", "", "org.h2.Driver", Map.of(), true,
                "driverDefault", 30, "iteration", "rollback", 100, 1024, 8192,
                null, null, 20, 0, 2000L, null);
        att.config.FrameworkConfig frameworkConfig = new att.config.FrameworkConfig(
                Path.of("output"), Path.of("report"), Path.of("logs"), "TEST", 30_000,
                Path.of("templates"), Path.of("testcases"), Map.of(), Map.of("orders", db), Map.of(), Map.of(), Map.of(),
                null, null, List.of(), "", "", List.of(), List.of(), 1, "ignore", "", false,
                att.config.ProcessOutputConfig.defaults(), List.of());
        Set<String> workloadIds = workloadSubjects.stream().map(SutMetricsService.WorkloadSubject::workloadId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new SutMetricsService.BusinessCallContext("pkg", Path.of("package"), Path.of("runtime-config.yaml"),
                Path.of("."), "TEST", "a".repeat(64), "b".repeat(64), workloadIds,
                workloadSubjects, frameworkConfig);
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

    private static String multiMetricResponse(String raw) {
        long timestamp = Instant.parse(queryParameter(raw, "end")).toEpochMilli();
        String atEnd = String.format(Locale.ROOT, "%.3f", timestamp / 1000.0);
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"__name__\":\"host_cpu_seconds_total\",\"instance\":\"sut-1\"},\"values\":[[" + atEnd + ",\"11.0\"]]},"
                + "{\"metric\":{\"__name__\":\"host_memory_bytes\",\"instance\":\"sut-1\"},\"values\":[[" + atEnd + ",\"256.0\"]]},"
                + "{\"metric\":{\"family\":\"sessions\",\"instance\":\"sut-1\"},\"values\":[[" + atEnd + ",\"3.0\"]]}]}}";
    }

    private static String unapprovedMetricLabelResponse(String raw) {
        long timestamp = Instant.parse(queryParameter(raw, "end")).toEpochMilli();
        String atEnd = String.format(Locale.ROOT, "%.3f", timestamp / 1000.0);
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"__name__\":\"allowed_metric\",\"instance\":\"sut-1\",\"pod\":\"private-pod-7\"},\"values\":[["
                + atEnd + ",\"1.0\"]]}]}}";
    }

    private static String prometheusLabelCollisionResponse(String raw) {
        long timestamp = Instant.parse(queryParameter(raw, "end")).toEpochMilli();
        String atEnd = String.format(Locale.ROOT, "%.3f", timestamp / 1000.0);
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"__name__\":\"allowed_metric\",\"instance\":\"sut-1\",\"pod\":\"pod-a\"},\"values\":[["
                + atEnd + ",\"1.0\"]]},"
                + "{\"metric\":{\"__name__\":\"allowed_metric\",\"instance\":\"sut-1\",\"pod\":\"pod-b\"},\"values\":[["
                + atEnd + ",\"2.0\"]]}]}}";
    }

    private static String twoInstanceResponse(String raw) {
        long timestamp = Instant.parse(queryParameter(raw, "end")).toEpochMilli();
        String atEnd = String.format(Locale.ROOT, "%.3f", timestamp / 1000.0);
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"__name__\":\"up\",\"instance\":\"sut-a\"},\"values\":[[" + atEnd + ",\"1.0\"]]},"
                + "{\"metric\":{\"__name__\":\"up\",\"instance\":\"sut-b\"},\"values\":[[" + atEnd + ",\"1.0\"]]}]}}";
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

    private static String renderOfflineHtml(String jobId, Map<String, Object> sut) throws Exception {
        var renderHtml = SutMetricsOfflineReportPublisher.class.getDeclaredMethod("renderHtml", String.class,
                Map.class, Map.class, Class.forName("att.server.SutMetricsOfflineReportPublisher$Verdict"));
        renderHtml.setAccessible(true);
        Class<?> verdictType = Class.forName("att.server.SutMetricsOfflineReportPublisher$Verdict");
        var verdictConstructor = verdictType.getDeclaredConstructors()[0];
        verdictConstructor.setAccessible(true);
        Object verdict = verdictConstructor.newInstance("NOT_PROVIDED", "NOT_PROVIDED", null, null, null, "", "");
        return new String((byte[]) renderHtml.invoke(null, jobId, Map.of("status", "PASS"), sut, verdict), StandardCharsets.UTF_8);
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
        final AtomicReference<String> queryEnd = new AtomicReference<>();
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
                queryEnd.set(queryParameter(raw, "end"));
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
