package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SutMetricsGrafanaOfflineReportIntegrationTest {
    private static final String JOB_ID = "J0000000000000001";
    private static final String TOKEN = "test-only-grafana-token";

    @TempDir Path temp;

    @Test void grafanaUsesPinnedDatasourceBearerAndTenantAndNormalizesDataFrames() throws Exception {
        try (GrafanaFixture fixture = new GrafanaFixture("10.4.1");
             SutMetricsService service = new SutMetricsService(temp, Set.of(), reference -> {
                 assertEquals("GRAFANA_TOKEN", reference);
                 return TOKEN;
             })) {
            service.upsertSource(source(fixture.url()));
            service.upsertProfile(profile("sum(rate(http_requests_total[1m]))"));

            Map<String, Object> deniedBinding = binding("7");
            assertThrows(IllegalArgumentException.class, () -> service.resolveScenario(
                    scenario(List.of(deniedBinding)), "alice", false),
                    "A scenario cannot select an organization outside the registered allowlist.");
            assertEquals(0, fixture.queryRequests.get(), "Tenant authorization is checked before contacting Grafana.");

            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(binding(null))), "alice", false);
            service.writeSnapshot(JOB_ID, snapshot);
            Instant finishedAt = Instant.now().minusSeconds(180);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);

            Map<String, Object> collection = awaitCollection(service, JOB_ID);
            assertEquals("READY", collection.get("state"));
            assertEquals(1, fixture.healthRequests.get());
            assertEquals(1, fixture.queryRequests.get());
            assertEquals("/api/ds/query", fixture.requestPath.get());
            assertEquals("Bearer " + TOKEN, fixture.authorization.get());
            assertEquals("42", fixture.organization.get());

            JsonNode queryRequest = ServerRuntime.JSON.readTree(fixture.queryBody.get());
            JsonNode query = queryRequest.path("queries").get(0);
            assertEquals("prom-main", query.path("datasource").path("uid").asText());
            assertEquals("sum(rate(http_requests_total[1m]))", query.path("expr").asText());
            assertEquals("range", query.path("queryType").asText());

            Map<String, Object> page = service.readCollectionPage(JOB_ID, 0, 1, null, null, null);
            Map<String, Object> item = maps(page.get("items")).get(0);
            assertEquals("READY", maps(collection.get("files")).get(0).get("queryStatus"));
            List<Map<String, Object>> series = maps(item.get("series"));
            assertEquals(1, series.size());
            assertEquals("sut-1", object(series.get(0).get("labels")).get("instance"));
            Map<String, Object> sample = maps(series.get(0).get("samples")).get(0);
            assertEquals(42.5d, ((Number) sample.get("value")).doubleValue());
            assertNotNull(Instant.parse(String.valueOf(sample.get("timestamp"))));
            assertFalse(service.listSources("alice", false).toString().contains(TOKEN));
            assertFalse(collection.toString().contains(TOKEN));
            assertFalse(page.toString().contains(TOKEN));
        }
    }

    @Test void grafanaVersionMismatchIsRejectedBeforeQueryingTheDatasource() throws Exception {
        try (GrafanaFixture fixture = new GrafanaFixture("14.0.0");
             SutMetricsService service = new SutMetricsService(temp, Set.of(), reference -> TOKEN)) {
            service.upsertSource(source(fixture.url()));

            assertThrows(IllegalArgumentException.class, () -> service.testSource("grafana"));
            assertEquals(1, fixture.healthRequests.get());
            assertEquals(0, fixture.queryRequests.get());
        }
    }

    @Test void offlineReportAddsVerifiedPartialSutEvidenceWithoutChangingTheSealedArchive() throws Exception {
        try (GrafanaFixture fixture = new GrafanaFixture("11.2.0");
             SutMetricsService service = new SutMetricsService(temp, Set.of(), reference -> TOKEN)) {
            service.upsertSource(source(fixture.url()));
            service.upsertProfile(profile("up", "absent_metric"));
            Map<String, Object> snapshot = service.resolveScenario(scenario(List.of(binding(null))), "alice", false);
            service.writeSnapshot(JOB_ID, snapshot);

            Path nativeOutput = temp.resolve("native-output");
            Files.createDirectories(nativeOutput);
            Files.writeString(nativeOutput.resolve("sealed-native-evidence.txt"), "sealed native evidence", StandardCharsets.UTF_8);
            Path nativeArchive = temp.resolve("artifacts.zip");
            ArtifactArchive.build(nativeOutput, nativeArchive, (path, bytes) -> { });
            byte[] sealedArchiveBytes = Files.readAllBytes(nativeArchive);

            Instant finishedAt = Instant.now().minusSeconds(180);
            service.jobFinished(JOB_ID, finishedAt.minusSeconds(60), finishedAt);
            Map<String, Object> collection = awaitCollection(service, JOB_ID);
            assertEquals("PARTIAL", collection.get("state"));
            assertEquals(2, fixture.queryRequests.get());

            Path jobDirectory = temp.resolve("jobs").resolve(JOB_ID);
            Path verdict = jobDirectory.resolve("load-verdict/v1/manifest.json");
            Files.createDirectories(verdict.getParent());
            Files.writeString(verdict, ServerRuntime.JSON.writeValueAsString(map(
                    "schemaVersion", "att-load-verdict/v1", "jobId", JOB_ID,
                    "externalEvaluationStatus", "PENDING", "performanceVerdict", "PENDING")), StandardCharsets.UTF_8);

            Path publicationRoot = temp.resolve("report-v2");
            ServerRuntime.ArtifactPublisher publisher = (output, archive, validator) -> ArtifactArchive.build(output, archive, validator);
            ArtifactArchive.EntryValidator validator = (path, bytes) -> { };
            SutMetricsOfflineReportPublisher.Publication published = SutMetricsOfflineReportPublisher.publish(
                    JOB_ID, map("status", "SUCCEEDED"), jobDirectory, nativeArchive, publicationRoot,
                    service, publisher, validator);

            assertEquals("PARTIAL", published.collectionState());
            assertEquals("PENDING", published.externalEvaluationStatus());
            assertNull(published.performanceVerdict(), "A pending external evaluation must not be presented as a finalized verdict.");
            assertArrayEquals(sealedArchiveBytes, Files.readAllBytes(nativeArchive), "Publishing a report must preserve the sealed archive bytes.");

            Path reportArchive = publicationRoot.resolve("snapshots").resolve(published.publicationId() + ".zip");
            Map<String, Object> inventory = ArtifactArchive.verify(reportArchive, true);
            assertTrue(maps(inventory.get("entries")).stream().anyMatch(entry ->
                    "report/v2/index.html".equals(entry.get("path"))));
            assertThrows(java.io.FileNotFoundException.class,
                    () -> ArtifactArchive.read(reportArchive, "sealed-native-evidence.txt"),
                    "The supplemental report archive must not duplicate the sealed native archive.");

            JsonNode report = ServerRuntime.JSON.readTree(ArtifactArchive.read(reportArchive, "report/v2/manifest.json"));
            assertEquals("att-load-report-extension/v2", report.path("schemaVersion").asText());
            assertEquals(published.nativeArchiveSha256(), report.path("nativeArchive").path("sha256").asText());
            assertEquals("SUCCEEDED", report.path("executionStatus").asText());
            assertEquals("PARTIAL", report.path("collectionState").asText());
            assertEquals("PENDING", report.path("extensions").path("thresholdVerdict").path("externalEvaluationStatus").asText());
            assertEquals("NOT_INCLUDED", report.path("extensions").path("correlation").path("state").asText());
            assertEquals(2, report.path("observations").size());
            assertEquals("att-load-offline-observation/v1", report.path("observations").get(0).path("schemaVersion").asText());
            Path offlineSchema = Path.of("schemas/api/att-load-offline-observations-v1.schema.json");
            byte[] offlineManifest = ArtifactArchive.read(reportArchive, "report/v2/sut-metrics/manifest.json");
            att.validation.JsonSchemaVerifier.verifyJson(offlineSchema, new String(offlineManifest, StandardCharsets.UTF_8));
            com.fasterxml.jackson.databind.node.ObjectNode boundary =
                    (com.fasterxml.jackson.databind.node.ObjectNode) ServerRuntime.JSON.readTree(offlineManifest);
            if (boundary.path("observations").isArray() && !boundary.path("observations").isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode observation =
                        (com.fasterxml.jackson.databind.node.ObjectNode) boundary.path("observations").get(0);
                observation.put("lengthBytes", SutMetricsService.MAX_SERIES_FILE_BYTES);
                observation.put("seriesCount", 512);
                att.validation.JsonSchemaVerifier.verifyJson(offlineSchema,
                        ServerRuntime.JSON.writeValueAsString(boundary));
            }
            assertTrue(report.path("observations").toString().contains("NO_DATA"));
            String html = new String(ArtifactArchive.read(reportArchive, "report/v2/index.html"), StandardCharsets.UTF_8);
            assertTrue(html.contains("Content-Security-Policy"));
            assertTrue(html.contains("PARTIAL"));
            assertFalse(html.contains("Supplied combined performance verdict: <strong>PENDING</strong>"));

            SutMetricsOfflineReportPublisher.Publication recovered = SutMetricsOfflineReportPublisher.publish(
                    JOB_ID, map("status", "SUCCEEDED"), jobDirectory, nativeArchive, publicationRoot,
                    service, publisher, validator);
            assertEquals(published.publicationId(), recovered.publicationId());
            assertEquals(published.archiveSha256(), recovered.archiveSha256());

            Map<String, Object> sourceManifest = service.collectionStatus(JOB_ID);
            String sidecar = String.valueOf(maps(sourceManifest.get("files")).get(0).get("path"));
            Files.writeString(jobDirectory.resolve("sut-metrics").resolve(sidecar), "tampered sidecar", StandardCharsets.UTF_8);
            assertThrows(IllegalStateException.class, () -> SutMetricsOfflineReportPublisher.publish(
                    JOB_ID, map("status", "SUCCEEDED"), jobDirectory, nativeArchive, publicationRoot,
                    service, publisher, validator), "Report publication must reject changed SUT evidence.");
            assertArrayEquals(sealedArchiveBytes, Files.readAllBytes(nativeArchive));
        }
    }

    @Test void supplementalPublicationSucceedsBesideAMaximumSizeNativeArchiveWithoutCopyingIt() throws Exception {
        Path nativeOutput = temp.resolve("maximum-native-output");
        Files.createDirectories(nativeOutput);
        int entryBytes = Math.toIntExact(ArtifactArchive.MAX_ENTRY_BYTES);
        byte[] randomEntry = new byte[entryBytes];
        new java.util.Random(350L).nextBytes(randomEntry);
        for (int index = 0; index < randomEntry.length; index++) randomEntry[index] = (byte) ('a' + Byte.toUnsignedInt(randomEntry[index]) % 26);
        for (int index = 0; index < 8; index++)
            Files.write(nativeOutput.resolve(String.format("native-%02d.txt", index)), randomEntry);
        Path nativeArchive = temp.resolve("maximum-artifacts.zip");
        ArtifactArchive.build(nativeOutput, nativeArchive, (path, bytes) -> { });
        long nativeBytes = maps(ArtifactArchive.verify(nativeArchive, true).get("entries")).stream()
                .mapToLong(entry -> ((Number) entry.get("size")).longValue()).sum();
        assertEquals(ArtifactArchive.MAX_TOTAL_BYTES, nativeBytes);

        try (SutMetricsService service = new SutMetricsService(temp.resolve("metrics"), Set.of(), reference -> null)) {
            Path publicationRoot = temp.resolve("maximum-report-v2");
            ServerRuntime.ArtifactPublisher publisher = (output, archive, validator) -> ArtifactArchive.build(output, archive, validator);
            SutMetricsOfflineReportPublisher.Publication publication = SutMetricsOfflineReportPublisher.publish(
                    JOB_ID, map("status", "SUCCEEDED"), temp.resolve("jobs").resolve(JOB_ID), nativeArchive,
                    publicationRoot, service, publisher, (path, bytes) -> { });
            Path reportArchive = publicationRoot.resolve("snapshots").resolve(publication.publicationId() + ".zip");
            Map<String, Object> inventory = ArtifactArchive.verify(reportArchive, true);
            List<Map<String, Object>> entries = maps(inventory.get("entries"));
            assertTrue(entries.stream().allMatch(entry -> String.valueOf(entry.get("path")).startsWith("report/v2/")));
            assertTrue(Files.size(reportArchive) < 1024 * 1024);
            JsonNode manifest = ServerRuntime.JSON.readTree(ArtifactArchive.read(reportArchive, "report/v2/manifest.json"));
            assertEquals(publication.nativeArchiveSha256(), manifest.path("nativeArchive").path("sha256").asText());
        }
    }

    @Test void chartPreviewKeepsLabeledSeriesUsesUtcSpacingAndBreaksObservedGaps() throws Exception {
        long start = 1_700_000_000_000L;
        List<Map<String, Object>> source = List.of(
                map("labels", Map.of("instance", "sut-a"), "samples", List.of(
                        sample(start, 1), sample(start + 15_000, 2), sample(start + 45_000, 3))),
                map("labels", Map.of("instance", "sut-b"), "samples", List.of(
                        sample(start, 4), sample(start + 30_000, 5), sample(start + 45_000, 6))));
        var previewMethod = SutMetricsService.class.getDeclaredMethod("chartPreview", List.class, int.class, String.class);
        previewMethod.setAccessible(true);
        List<Map<String, Object>> preview = maps(previewMethod.invoke(null, source, 96, "15s"));
        assertEquals(2, preview.size());
        assertEquals("sut-a", object(preview.get(0).get("labels")).get("instance"));
        assertEquals("sut-b", object(preview.get(1).get("labels")).get("instance"));
        List<Map<String, Object>> firstSamples = maps(preview.get(0).get("samples"));
        List<Map<String, Object>> secondSamples = maps(preview.get(1).get("samples"));
        assertEquals(start + 15_000, ((Number) firstSamples.get(1).get("timestampEpochMs")).longValue());
        assertTrue(Boolean.TRUE.equals(firstSamples.get(2).get("gapBefore")));
        assertTrue(Boolean.TRUE.equals(secondSamples.get(1).get("gapBefore")));

        var renderMethod = SutMetricsOfflineReportPublisher.class.getDeclaredMethod(
                "renderChart", List.class, String.class, String.class, Map.class, int.class);
        renderMethod.setAccessible(true);
        String html = (String) renderMethod.invoke(null, preview, "count", "15s",
                map("startAt", Instant.ofEpochMilli(start).toString(),
                    "endAt", Instant.ofEpochMilli(start + 60_000).toString()), 0);
        assertTrue(html.contains("instance=sut-a"));
        assertTrue(html.contains("instance=sut-b"));
        assertTrue(html.contains("164.00,"), "x positions must use the UTC sample time rather than the array index.");
        assertTrue(html.contains("476.00,"), "irregular intervals must retain their different horizontal spacing.");
        assertTrue(html.split("<circle", -1).length - 1 >= 2, "Gaps must end one SVG segment and start another.");
        assertTrue(html.split("<polyline", -1).length - 1 >= 2, "Both labeled series must be represented.");
        assertTrue(preview.stream().mapToInt(item -> maps(item.get("samples")).size()).sum() <=
                SutMetricsService.MAX_REPORT_PREVIEW_POINTS);
    }

    private static Map<String, Object> sample(long timestamp, double value) {
        return map("timestamp", Instant.ofEpochMilli(timestamp).toString(), "timestampEpochMs", timestamp, "value", value);
    }

    private static Map<String, Object> source(String url) {
        return map("schemaVersion", "att-sut-metrics-source/v1", "id", "grafana", "name", "Grafana source",
                "provider", "GRAFANA", "grafanaDatasourceUid", "prom-main", "url", url,
                "apiPath", "/api/ds/query", "auth", map("mode", "BEARER", "secretRef", "GRAFANA_TOKEN"),
                "readers", List.of("alice"), "readRoles", List.of(), "allowedTenants", List.of("42"),
                "defaultTenant", "42");
    }

    private static Map<String, Object> profile(String... queries) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (int index = 0; index < queries.length; index++) metrics.add(map("id", "metric-" + (index + 1),
                "title", "Metric " + (index + 1), "unit", "count", "kind", "gauge", "query", queries[index]));
        return map("schemaVersion", "att-sut-metrics-profile/v1", "id", "base", "name", "Base profile",
                "kind", "base", "category", "app", "metrics", metrics);
    }

    private static Map<String, Object> binding(String tenant) {
        Map<String, Object> result = map("profile", "base", "source", "grafana",
                "targets", List.of(map("id", "sut-1", "type", "app")));
        if (tenant != null) result.put("tenant", tenant);
        return result;
    }

    private static Map<String, Object> scenario(List<Map<String, Object>> bindings) {
        return map("schemaVersion", "att-load-observability/v1", "collections", bindings,
                "collection", map("before", "1m", "after", "1m", "step", "15s"),
                "livePreview", Map.of("enabled", false));
    }

    private static Map<String, Object> awaitCollection(SutMetricsService service, String jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Map<String, Object> manifest = service.collectionStatus(jobId);
            if (Set.of("READY", "PARTIAL", "FAILED", "UNAVAILABLE", "DISABLED").contains(manifest.get("state"))) return manifest;
            Thread.sleep(25);
        }
        fail("SUT Metrics collection did not finish before the integration test deadline.");
        return Map.of();
    }

    private static List<Map<String, Object>> maps(Object value) {
        return ServerRuntime.JSON.convertValue(value, ServerRuntime.JSON.getTypeFactory()
                .constructCollectionType(List.class, Map.class));
    }

    private static Map<String, Object> object(Object value) {
        return ServerRuntime.JSON.convertValue(value, Map.class);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static final class GrafanaFixture implements AutoCloseable {
        final AtomicInteger healthRequests = new AtomicInteger();
        final AtomicInteger queryRequests = new AtomicInteger();
        final AtomicReference<String> authorization = new AtomicReference<>();
        final AtomicReference<String> organization = new AtomicReference<>();
        final AtomicReference<String> requestPath = new AtomicReference<>();
        final AtomicReference<byte[]> queryBody = new AtomicReference<>();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final String version;

        GrafanaFixture(String version) throws Exception {
            this.version = version;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/health", this::health);
            server.createContext("/api/ds/query", this::query);
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void health(HttpExchange exchange) throws IOException {
            healthRequests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, map("version", version, "database", "ok"));
        }

        private void query(HttpExchange exchange) throws IOException {
            queryRequests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            organization.set(exchange.getRequestHeaders().getFirst("X-Grafana-Org-Id"));
            requestPath.set(exchange.getRequestURI().getPath());
            byte[] request = exchange.getRequestBody().readAllBytes();
            queryBody.set(request);
            JsonNode parsed = ServerRuntime.JSON.readTree(request);
            String expression = parsed.path("queries").path(0).path("expr").asText();
            if ("absent_metric".equals(expression)) {
                respond(exchange, 200, map("results", map("A", map("frames", List.of()))));
                return;
            }
            long timestamp = Long.parseLong(parsed.path("to").asText());
            Map<String, Object> fields = map("schema", map("fields", List.of(
                            map("name", "Time", "type", "time"),
                            map("name", "Value", "type", "number", "labels", map("instance", "sut-1")))),
                    "data", map("values", List.of(List.of(timestamp), List.of(42.5d))));
            respond(exchange, 200, map("results", map("A", map("frames", List.of(fields)))));
        }

        private static void respond(HttpExchange exchange, int status, Map<String, Object> value) throws IOException {
            byte[] body = ServerRuntime.JSON.writeValueAsBytes(value);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
