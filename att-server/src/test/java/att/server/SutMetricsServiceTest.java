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

    private static final class PrometheusFixture implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        PrometheusFixture(int samplesPerResponse) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/v1/query_range", exchange -> respond(exchange, samplesPerResponse));
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void respond(HttpExchange exchange, int sampleCount) {
            requests.incrementAndGet();
            try {
                String raw = exchange.getRequestURI().getRawQuery();
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
                byte[] body = ("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{" +
                        "\"metric\":{\"instance\":\"sut-1\"},\"values\":[" + values + "]}]}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
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
