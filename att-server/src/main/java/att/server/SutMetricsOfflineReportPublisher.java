package att.server;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds an immutable, versioned offline report alongside the sealed native LOAD archive. */
final class SutMetricsOfflineReportPublisher {
    static final String SCHEMA = "att-load-report-extension/v2";
    private static final long MAX_VERDICT_RECORD_BYTES = 1024L * 1024L;
    private static final long MAX_VERDICT_EVIDENCE_BYTES = 8L * 1024L * 1024L;

    private SutMetricsOfflineReportPublisher() { }

    static Publication publish(String jobId, Map<String, Object> job, Path jobDirectory,
                               Path nativeArchive, Path publicationRoot,
                               SutMetricsService metrics, ServerRuntime.ArtifactPublisher publisher,
                               ArtifactArchive.EntryValidator validator) throws Exception {
        Path normalizedRoot = publicationRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot.resolve("snapshots"));
        cleanupStaleStaging(normalizedRoot);
        Path staging = Files.createTempDirectory(normalizedRoot, ".report-v2-stage-");
        try {
            if (Files.isSymbolicLink(nativeArchive)
                    || !Files.isRegularFile(nativeArchive, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The sealed native archive is unavailable.");
            ArtifactArchive.verify(nativeArchive, true);
            String nativeSha256 = sha256(nativeArchive);

            Map<String, Object> sut = metrics.writeOfflineReportEvidence(jobId, staging);
            Verdict verdict = readVerdict(jobId, jobDirectory);
            Object rawCollectionDigest = sut.get("sourceManifestSha256");
            String collectionDigest = rawCollectionDigest instanceof String digest ? digest : "";
            String publicationId = sha256((SCHEMA + "\u0000" + jobId + "\u0000" + nativeSha256 + "\u0000"
                    + collectionDigest + "\u0000" + verdict.recordSha256()).getBytes(StandardCharsets.UTF_8));
            Path normalizedDestination = normalizedRoot.resolve("snapshots").resolve(publicationId + ".zip").normalize();
            if (!normalizedDestination.startsWith(normalizedRoot.resolve("snapshots")))
                throw new IOException("The report snapshot path is invalid.");
            if (Files.exists(normalizedDestination, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(normalizedDestination) || !Files.isRegularFile(normalizedDestination, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("The existing report snapshot is not a regular file.");
                verifyReportArchive(normalizedDestination, publicationId, jobId, nativeSha256, collectionDigest, sut);
                byte[] existingManifest = ArtifactArchive.read(normalizedDestination, "report/v2/manifest.json");
                return new Publication(publicationId, sha256(normalizedDestination), Files.size(normalizedDestination),
                        sha256(existingManifest), String.valueOf(sut.get("state")), verdict.externalStatus(),
                        verdict.performanceVerdict(), nativeSha256, verdict.recordSha256());
            }
            Map<String, Object> extension = fields("schemaVersion", SCHEMA, "publicationId", publicationId,
                    "jobId", jobId, "createdAt", Instant.now().toString(),
                    "executionStatus", String.valueOf(job.getOrDefault("status", "UNKNOWN")),
                    "nativeArchive", fields("path", "artifacts.zip", "sha256", nativeSha256),
                    "observationsSchema", "att-load-offline-observations/v1",
                    "collectionState", sut.get("state"), "collectionManifestSha256", collectionDigest,
                    "observations", sut.get("observations"), "window", sut.get("window"),
                    "sampleResolution", sut.get("sampleResolution"), "coverage", sut.get("coverage"),
                    "diagnostics", sut.get("diagnostics"),
                    "extensions", fields("thresholdVerdict", verdict.summary(),
                            "correlation", fields("schemaVersion", "att-load-correlation/v1", "state", "NOT_INCLUDED"),
                            "futureObservationTypes", List.of("windowAggregate", "dimensionSeries")));
            byte[] extensionBytes = ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(extension);
            if (extensionBytes.length > SutMetricsService.MAX_MANIFEST_BYTES)
                throw new IOException("The offline report manifest exceeds its publication limit.");
            writeContained(staging, "report/v2/manifest.json", extensionBytes);
            if (verdict.manifestBytes() != null)
                writeContained(staging, "report/v2/extensions/load-verdict/v1/manifest.json", verdict.manifestBytes());
            if (verdict.evidenceBytes() != null)
                writeContained(staging, "report/v2/extensions/load-verdict/v1/evidence.json", verdict.evidenceBytes());
            writeContained(staging, "report/v2/index.html", renderHtml(jobId, job, sut, verdict));

            publisher.publish(staging, normalizedDestination, validator);
            Map<String, Object> verified = verifyReportArchive(normalizedDestination, publicationId, jobId,
                    nativeSha256, collectionDigest, sut);
            String archiveSha256 = sha256(normalizedDestination);
            return new Publication(publicationId, archiveSha256, Files.size(normalizedDestination),
                    sha256(extensionBytes), String.valueOf(sut.get("state")), verdict.externalStatus(),
                    verdict.performanceVerdict(), nativeSha256, verdict.recordSha256());
        } finally {
            deleteTree(staging);
        }
    }

    private static Map<String, Object> verifyReportArchive(Path archive, String publicationId, String jobId,
                                                            String nativeSha256, String collectionDigest,
                                                            Map<String, Object> sut) throws Exception {
        Map<String, Object> inventory = ArtifactArchive.verify(archive, true);
        List<?> entries = (List<?>) inventory.get("entries");
        if (entries == null || !containsEntry(entries, "report/v2/index.html"))
            throw new IOException("The offline report HTML is missing from the verified archive.");
        byte[] extensionBytes = ArtifactArchive.read(archive, "report/v2/manifest.json");
        JsonNode extension = ServerRuntime.JSON.readTree(extensionBytes);
        if (!SCHEMA.equals(extension.path("schemaVersion").asText())
                || !publicationId.equals(extension.path("publicationId").asText())
                || !jobId.equals(extension.path("jobId").asText())
                || !nativeSha256.equals(extension.path("nativeArchive").path("sha256").asText())
                || !collectionDigest.equals(extension.path("collectionManifestSha256").asText()))
            throw new IOException("The offline report snapshot identity does not match its publication request.");
        JsonNode observations = extension.path("observations");
        List<?> expected = (List<?>) sut.get("observations");
        if (!observations.isArray() || observations.size() != expected.size())
            throw new IOException("The offline report observation inventory is incomplete.");
        for (int index = 0; index < expected.size(); index++) {
            if (!(expected.get(index) instanceof Map<?, ?> observation))
                throw new IOException("The offline report observation metadata is invalid.");
            String path = String.valueOf(observation.get("path")), digest = String.valueOf(observation.get("sha256"));
            JsonNode archived = observations.get(index);
            if (!path.equals(archived.path("path").asText()) || !digest.equals(archived.path("sha256").asText())
                    || !path.startsWith("report/v2/sut-metrics/observations/") || !containsEntry(entries, path))
                throw new IOException("The offline report is missing a verified observation sidecar.");
            if (!digest.equals(sha256(ArtifactArchive.read(archive, path))))
                throw new IOException("An offline report observation changed during publication.");
        }
        byte[] sutManifestBytes = ArtifactArchive.read(archive, "report/v2/sut-metrics/manifest.json");
        JsonNode sutManifest = ServerRuntime.JSON.readTree(sutManifestBytes);
        String sourceManifestSha = sutManifest.path("sourceManifestSha256").isTextual()
                ? sutManifest.path("sourceManifestSha256").asText() : "";
        if (!"att-load-offline-observations/v1".equals(sutManifest.path("schemaVersion").asText())
                || !jobId.equals(sutManifest.path("jobId").asText())
                || !collectionDigest.equals(sourceManifestSha))
            throw new IOException("The offline SUT metrics manifest failed publication verification.");
        return inventory;
    }

    private static boolean containsEntry(List<?> entries, String path) {
        return entries.stream().anyMatch(value -> value instanceof Map<?, ?> entry && path.equals(entry.get("path")));
    }

    private static Verdict readVerdict(String jobId, Path jobDirectory) throws Exception {
        Path expectedRoot = jobDirectory.toAbsolutePath().normalize();
        if (!jobId.equals(expectedRoot.getFileName().toString())) throw new IOException("The verdict path escaped the Job data directory.");
        Path manifest = jobDirectory.resolve("load-verdict/v1/manifest.json").normalize();
        if (!manifest.startsWith(jobDirectory)) throw new IOException("The verdict manifest path is invalid.");
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return Verdict.notProvided();
        if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifest) > MAX_VERDICT_RECORD_BYTES) return Verdict.invalid();
        if (!manifest.toRealPath().startsWith(expectedRoot.toRealPath())) return Verdict.invalid();
        byte[] bytes = Files.readAllBytes(manifest);
        if (bytes.length > MAX_VERDICT_RECORD_BYTES) return Verdict.invalid();
        JsonNode value;
        try { value = ServerRuntime.JSON.readTree(bytes); }
        catch (Exception malformed) { return Verdict.invalid(); }
        if (!"att-load-verdict/v1".equals(value.path("schemaVersion").asText())
                || !jobId.equals(value.path("jobId").asText())) return Verdict.invalid();
        String external = value.path("externalEvaluationStatus").asText("UNKNOWN");
        if (!Set.of("PENDING", "EVALUATING", "MET", "NOT_MET", "NOT_EVALUATED", "ERROR").contains(external))
            return Verdict.invalid();
        String performance = value.path("performanceVerdict").asText("");
        if (!performance.isBlank() && !Set.of("PENDING", "MET", "NOT_MET", "NOT_EVALUATED").contains(performance))
            return Verdict.invalid();
        byte[] evidence = null;
        String evidencePath = value.path("evidencePath").asText("");
        String evidenceSha = value.path("evidenceSha256").asText("");
        if (!evidencePath.isBlank()) {
            if (!"load-verdict/v1/evidence.json".equals(evidencePath) || !evidenceSha.matches("[0-9a-f]{64}"))
                return Verdict.invalid();
            Path evidenceFile = jobDirectory.resolve(evidencePath).normalize();
            if (!evidenceFile.startsWith(jobDirectory) || Files.isSymbolicLink(evidenceFile)
                    || !Files.isRegularFile(evidenceFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(evidenceFile) > MAX_VERDICT_EVIDENCE_BYTES) return Verdict.invalid();
            if (!evidenceFile.toRealPath().startsWith(expectedRoot.toRealPath())) return Verdict.invalid();
            evidence = Files.readAllBytes(evidenceFile);
            if (!evidenceSha.equals(sha256(evidence))) return Verdict.invalid();
        }
        String status = Set.of("MET", "NOT_MET", "NOT_EVALUATED", "ERROR").contains(external) ? "FINAL" : "PENDING";
        String cleanPerformance = "FINAL".equals(status) && !performance.isBlank() ? performance : null;
        return new Verdict(status, external, cleanPerformance, bytes, evidence, sha256(bytes), evidenceSha);
    }

    private static byte[] renderHtml(String jobId, Map<String, Object> job, Map<String, Object> sut, Verdict verdict) {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'\">")
                .append("<title>LOAD offline SUT report · ").append(escape(jobId)).append("</title><style>")
                .append("body{font:16px/1.5 system-ui,sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#172033;background:#f7f8fa}h1,h2{line-height:1.2}main,section,article{background:#fff;border:1px solid #d9dee8;border-radius:8px;padding:1rem;margin:1rem 0}dl{display:grid;grid-template-columns:12rem 1fr;gap:.4rem}dt{font-weight:650}dd{margin:0;overflow-wrap:anywhere}.state{font-weight:700}.chart{width:100%;height:150px}.chart polyline{fill:none;stroke-width:2}.chart line{stroke:#ccd3de;stroke-width:1}.legend{display:flex;flex-wrap:wrap;gap:.6rem}.legend span{font-size:.85rem}.muted{color:#536078}table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #d9dee8;text-align:left;padding:.4rem;vertical-align:top}code{overflow-wrap:anywhere}</style></head><body><main>")
                .append("<h1>LOAD offline SUT metrics report</h1><p>Job <code>").append(escape(jobId))
                .append("</code> · native execution status <strong>").append(escape(String.valueOf(job.getOrDefault("status", "UNKNOWN"))))
                .append("</strong></p><p class=\"muted\">This versioned supplement preserves the sealed LOAD report and renders verified, server-collected SUT observations. It evaluates no thresholds and does not change the native execution result.</p>")
                .append("<p class=\"muted\">The native sealed archive is referenced by SHA-256 in the versioned report manifest and remains a separate download.</p><section><h2>Collection</h2><dl>")
                .append(row("State", sut.get("state"))).append(row("Window", windowText(sut.get("window"))))
                .append(row("Sample resolution", sut.get("sampleResolution"))).append(row("Coverage", jsonText(sut.get("coverage"))))
                .append(row("Observation count", list(sut.get("observations")).size()))
                .append("</dl>");
        List<Map<String, Object>> diagnostics = maps(sut.get("diagnostics"));
        for (Map<String, Object> diagnostic : diagnostics)
            html.append("<p role=\"status\"><strong>").append(escape(String.valueOf(diagnostic.get("code"))))
                    .append("</strong>: ").append(escape(String.valueOf(diagnostic.get("message")))).append("</p>");
        html.append("</section><section><h2>Threshold verdict extension</h2><p>Record state: <strong>")
                .append(escape(verdict.status())).append("</strong>; evaluation state: <strong>")
                .append(escape(verdict.externalStatus())).append("</strong>. ");
        if (verdict.performanceVerdict() != null)
            html.append("Supplied combined performance verdict: <strong>").append(escape(verdict.performanceVerdict())).append("</strong>. ");
        html.append("This report displays a separately versioned record when present; it does not calculate a verdict.</p>")
                .append("</section><section><h2>Observed SUT metrics</h2>");
        List<Map<String, Object>> observations = maps(sut.get("observations"));
        if (observations.isEmpty()) {
            html.append("<p role=\"status\">No SUT observations were available for this report. Collection state: <strong>")
                    .append(escape(String.valueOf(sut.get("state")))).append("</strong>.</p>");
        } else {
            for (Map<String, Object> observation : observations) {
                Map<String, Object> metric = map(observation.get("metric")), source = map(observation.get("source"));
                Map<String, Object> profile = map(observation.get("profile")), target = map(observation.get("target"));
                html.append("<article><h3>").append(escape(String.valueOf(metric.getOrDefault("name", metric.get("id"))))).append("</h3><dl>")
                        .append(row("Source", source.getOrDefault("name", source.get("id"))))
                        .append(row("Metric / unit", String.valueOf(metric.getOrDefault("id", "")) + " · " + String.valueOf(metric.getOrDefault("unit", "unspecified"))))
                        .append(row("Profile", profile.getOrDefault("path", profile.get("id"))))
                        .append(row("Target", String.valueOf(target.getOrDefault("type", "target")) + " · " + String.valueOf(target.getOrDefault("id", "unknown"))))
                        .append(row("UTC window", windowText(observation.get("window"))))
                        .append(row("Resolution / coverage", String.valueOf(observation.get("sampleResolution")) + " · " + jsonText(observation.get("coverage"))))
                        .append(row("Status / gaps", String.valueOf(map(observation.get("quality")).get("status")) + " · " + String.valueOf(map(observation.get("quality")).getOrDefault("gapCount", "unknown"))))
                        .append("</dl>").append(renderChart(maps(observation.get("preview")), String.valueOf(metric.getOrDefault("unit", "")),
                                String.valueOf(observation.get("sampleResolution")), map(observation.get("window")),
                                ((Number) observation.getOrDefault("previewOmittedSeriesCount", 0)).intValue()))
                        .append("<p><a href=\"").append(escape(String.valueOf(observation.get("path")).substring("report/v2/".length())))
                        .append("\">Open the verified observation JSON</a></p></article>");
            }
        }
        html.append("</section><section><h2>Phase windows</h2>");
        List<Map<String, Object>> phases = maps(map(sut.get("window")).get("phaseWindows"));
        if (phases.isEmpty()) html.append("<p>Phase windows were unavailable.</p>");
        else {
            html.append("<table><thead><tr><th>Phase</th><th>Start (UTC)</th><th>End (UTC)</th></tr></thead><tbody>");
            for (Map<String, Object> phase : phases) html.append("<tr><td>").append(escape(String.valueOf(phase.getOrDefault("phase", "unknown"))))
                    .append("</td><td>").append(escape(String.valueOf(phase.getOrDefault("startAt", "unknown"))))
                    .append("</td><td>").append(escape(String.valueOf(phase.getOrDefault("endAt", "unknown")))).append("</td></tr>");
            html.append("</tbody></table>");
        }
        html.append("</section></main></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String renderChart(List<Map<String, Object>> series, String unit, String sampleResolution,
                                      Map<String, Object> window, int omittedSeriesCount) {
        if (series.isEmpty()) return "<p class=\"muted\">No samples are available for a chart.</p>";
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        long firstSample = Long.MAX_VALUE, lastSample = Long.MIN_VALUE;
        for (Map<String, Object> item : series) for (Map<String, Object> sample : maps(item.get("samples"))) {
            if (sample.get("value") instanceof Number value && Double.isFinite(value.doubleValue())) {
                min = Math.min(min, value.doubleValue()); max = Math.max(max, value.doubleValue());
            }
            long time = sampleTime(sample);
            if (time != Long.MIN_VALUE) { firstSample = Math.min(firstSample, time); lastSample = Math.max(lastSample, time); }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || firstSample == Long.MAX_VALUE)
            return "<p class=\"muted\">No finite samples are available for a chart.</p>";
        if (max == min) { max = min + 1; min -= 1; }
        long windowStart = parseTime(window.get("startAt"), firstSample), windowEnd = parseTime(window.get("endAt"), lastSample);
        if (windowEnd <= windowStart) { windowStart = firstSample; windowEnd = lastSample; }
        if (windowEnd <= windowStart) windowEnd = windowStart + 1;
        List<String> colors = List.of("#1453a6", "#b83280", "#087f5b", "#c2410c", "#6d28d9", "#a16207",
                "#0369a1", "#be123c", "#4d7c0f", "#7c3aed", "#0f766e", "#b45309", "#1d4ed8", "#9d174d",
                "#15803d", "#7e22ce");
        StringBuilder svg = new StringBuilder("<svg class=\"chart\" viewBox=\"0 0 640 150\" role=\"img\" aria-label=\"Sampled metric values in ")
                .append(escape(unit)).append("\"><line x1=\"8\" y1=\"14\" x2=\"632\" y2=\"14\"/><line x1=\"8\" y1=\"126\" x2=\"632\" y2=\"126\"/>");
        StringBuilder legend = new StringBuilder("<p class=\"legend\">");
        for (int seriesIndex = 0; seriesIndex < series.size(); seriesIndex++) {
            String color = colors.get(seriesIndex % colors.size());
            Map<String, Object> item = series.get(seriesIndex);
            String label = labelText(map(item.get("labels")));
            legend.append("<span style=\"color:").append(color).append("\">● ").append(escape(label)).append("</span> ");
            List<Map<String, Object>> samples = maps(item.get("samples"));
            StringBuilder points = new StringBuilder();
            for (Map<String, Object> sample : samples) {
                if (Boolean.TRUE.equals(sample.get("gapBefore"))) {
                    appendChartSegment(svg, points, color);
                    points.setLength(0);
                }
                if (!(sample.get("value") instanceof Number number) || !Double.isFinite(number.doubleValue())) continue;
                long timestamp = sampleTime(sample);
                if (timestamp == Long.MIN_VALUE) continue;
                double x = 8 + 624.0 * (timestamp - windowStart) / (windowEnd - windowStart);
                double y = 126 - 112.0 * (number.doubleValue() - min) / (max - min);
                if (!Double.isFinite(x) || !Double.isFinite(y)) continue;
                if (!points.isEmpty()) points.append(' ');
                points.append(String.format(java.util.Locale.ROOT, "%.2f,%.2f", x, y));
            }
            appendChartSegment(svg, points, color);
        }
        svg.append("</svg>").append(legend).append("</p><p class=\"muted\">Displayed values use UTC timestamps and break at observed gaps. ");
        if (omittedSeriesCount > 0) svg.append(omittedSeriesCount).append(" additional series are available in the verified observation JSON. ");
        return svg.append("The preview is bounded; the observation JSON contains the complete verified samples.</p>").toString();
    }

    private static void appendChartSegment(StringBuilder svg, StringBuilder points, String color) {
        if (points.isEmpty()) return;
        if (points.indexOf(" ") < 0) {
            String[] coordinates = points.toString().split(",", 2);
            svg.append("<circle cx=\"").append(coordinates[0]).append("\" cy=\"").append(coordinates[1])
                    .append("\" r=\"2.5\" style=\"fill:").append(color).append("\"/>");
        } else svg.append("<polyline style=\"stroke:").append(color).append("\" points=\"").append(points).append("\"/>");
    }

    private static long sampleTime(Map<String, Object> sample) {
        if (sample.get("timestampEpochMs") instanceof Number epoch) return epoch.longValue();
        return parseTime(sample.get("timestamp"), Long.MIN_VALUE);
    }

    private static long parseTime(Object value, long fallback) {
        if (!(value instanceof String text)) return fallback;
        try { return Instant.parse(text).toEpochMilli(); } catch (RuntimeException invalid) { return fallback; }
    }

    private static String labelText(Map<String, Object> labels) {
        if (labels.isEmpty()) return "(unlabeled series)";
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> label : labels.entrySet()) {
            if (count++ == 6) { result.append(", …"); break; }
            if (!result.isEmpty()) result.append(", ");
            String value = String.valueOf(label.getValue());
            if (value.length() > 80) value = value.substring(0, 80);
            result.append(label.getKey()).append('=').append(value);
        }
        return result.toString();
    }

    private static String row(String title, Object value) {
        return "<dt>" + escape(title) + "</dt><dd>" + escape(String.valueOf(value == null ? "unavailable" : value)) + "</dd>";
    }
    private static String windowText(Object value) {
        Map<String, Object> window = map(value);
        return String.valueOf(window.getOrDefault("startAt", "unknown")) + " to " + String.valueOf(window.getOrDefault("endAt", "unknown"));
    }
    private static String jsonText(Object value) {
        try { return ServerRuntime.JSON.writeValueAsString(value == null ? "unavailable" : value); }
        catch (Exception impossible) { return "unavailable"; }
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key instanceof String text) result.put(text, item); });
        return result;
    }
    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> row) result.add(map(row));
        return result;
    }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
    private static void writeContained(Path root, String relative, byte[] bytes) throws IOException {
        if (!ArtifactArchive.safePath(relative)) throw new IOException("The report contains an unsafe path.");
        Path output = root.resolve(relative).normalize();
        if (!output.startsWith(root.normalize())) throw new IOException("The report path escaped its staging directory.");
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }
    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
    private static void cleanupStaleStaging(Path root) throws IOException {
        try (var entries = Files.newDirectoryStream(root, ".report-v2-stage-*")) {
            for (Path entry : entries) if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry))
                deleteTree(entry);
        }
    }

    record Publication(String publicationId, String archiveSha256, long archiveBytes, String manifestSha256,
                       String collectionState, String externalEvaluationStatus, String performanceVerdict,
                       String nativeArchiveSha256, String verdictRecordSha256) { }

    static String verdictRecordSha256(String jobId, Path jobDirectory) throws Exception {
        return readVerdict(jobId, jobDirectory).recordSha256();
    }

    private record Verdict(String status, String externalStatus, String performanceVerdict, byte[] manifestBytes,
                           byte[] evidenceBytes, String recordSha256, String evidenceSha256) {
        static Verdict notProvided() { return new Verdict("NOT_PROVIDED", "NOT_PROVIDED", null, null, null, "", ""); }
        static Verdict invalid() { return new Verdict("INVALID", "UNKNOWN", null, null, null, "", ""); }
        Map<String, Object> summary() {
            return fields("schemaVersion", "att-load-verdict/v1", "state", status,
                    "externalEvaluationStatus", externalStatus, "performanceVerdict", performanceVerdict,
                    "reference", "load-verdict/v1/manifest.json", "recordSha256", recordSha256,
                    "evidencePath", evidenceBytes == null ? null : "report/v2/extensions/load-verdict/v1/evidence.json",
                    "evidenceSha256", evidenceBytes == null ? null : evidenceSha256);
        }
    }
}
