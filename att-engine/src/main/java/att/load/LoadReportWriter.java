package att.load;

import att.validation.JsonSupport;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.FilterOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Writes isolated machine-readable summaries and a self-contained Load performance report. */
public final class LoadReportWriter {
    private static final int MAX_REPORT_FILE_BYTES = 16 * 1024 * 1024;
    private static final long MAX_REPORT_TOTAL_BYTES = 120L * 1024 * 1024;
    static final String REPORT_MANIFEST = "load-report-manifest.json";
    static final String REPORT_MANIFEST_SCHEMA = "att-load-report-manifest/v1";

    public Path write(Path outputRoot, LoadRunResult result) throws IOException {
        Path runDirectory = outputRoot.resolve("load").resolve(result.runId()).toAbsolutePath().normalize();
        Path staging = null;
        List<Path> promoted = new ArrayList<Path>();
        boolean promotionStarted = false;
        try {
            Files.createDirectories(runDirectory);
            if (Files.isSymbolicLink(runDirectory) || !Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("LOAD report run directory must be a private regular directory.");
            runDirectory = runDirectory.toRealPath();
            final Path stageRoot = Files.createTempDirectory(runDirectory, ".load-report-stage-");
            staging = stageRoot;
            Map<String, Object> map = result.toMap();
            map.put("reportManifest", REPORT_MANIFEST);
            long detailBytes = LoadReportDetailWriter.write(stageRoot, map);
            // Stream each representation through the shared byte budget. Never hold three large
            // JSON/YAML/HTML byte arrays in the Worker heap before checking the archive limit.
            ReportByteBudget budget = new ReportByteBudget(detailBytes);
            Files.createDirectories(stageRoot.resolve("report"));
            try (OutputStream output = budget.open(stageRoot.resolve("load-summary.json"))) {
                JsonSupport.mapper().writerWithDefaultPrettyPrinter().writeValue(output, map);
            }
            try (OutputStream output = budget.open(stageRoot.resolve("load-summary.yaml"));
                 OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                new Yaml().dump(map, writer);
            }
            try (OutputStream output = budget.open(stageRoot.resolve("report/index.html"));
                 OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write(html(runDirectory, result, map));
            }
            Map<String, Object> manifest = createManifest(stageRoot, result);
            try (OutputStream output = budget.open(stageRoot.resolve(REPORT_MANIFEST))) {
                JsonSupport.mapper().writerWithDefaultPrettyPrinter().writeValue(output, manifest);
            }
            long reportBytes = treeBytes(stageRoot);
            if (reportBytes > MAX_REPORT_TOTAL_BYTES)
                throw new IOException("LOAD detailed report output exceeds its 120 MiB bounded report budget.");

            // The commit marker is absent during promotion and installed last. Readers can
            // distinguish a complete bundle from an interrupted multi-file replacement.
            Files.deleteIfExists(runDirectory.resolve(REPORT_MANIFEST));
            promotionStarted = true;
            List<Path> files;
            try (Stream<Path> paths = Files.walk(stageRoot)) {
                files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !path.equals(stageRoot.resolve(REPORT_MANIFEST)))
                        .sorted(Comparator.comparing(path -> stageRoot.relativize(path).toString()))
                        .collect(java.util.stream.Collectors.toList());
            }
            for (Path source : files) {
                Path relative = stageRoot.relativize(source);
                Path target = runDirectory.resolve(relative).normalize();
                if (!target.startsWith(runDirectory)) throw new IOException("LOAD report path escaped its run directory.");
                createPrivateParents(runDirectory, target.getParent());
                moveAtomically(source, target);
                promoted.add(target);
            }
            Path stagedManifest = stageRoot.resolve(REPORT_MANIFEST);
            Path publishedManifest = runDirectory.resolve(REPORT_MANIFEST);
            moveAtomically(stagedManifest, publishedManifest);
            return runDirectory.resolve("report/index.html");
        } catch (IOException | RuntimeException failure) {
            if (promotionStarted) {
                for (Path path : promoted) {
                    try { Files.deleteIfExists(path); }
                    catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                }
                try { Files.deleteIfExists(runDirectory.resolve(REPORT_MANIFEST)); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        } finally {
            if (staging != null) removeTree(staging);
        }
    }

    private static Map<String, Object> createManifest(Path staging, LoadRunResult result) throws IOException {
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        try (Stream<Path> paths = Files.walk(staging)) {
            for (Path path : (Iterable<Path>) paths.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS))
                    .filter(item -> !item.equals(staging.resolve(REPORT_MANIFEST)))
                    .sorted(Comparator.comparing(item -> staging.relativize(item).toString()))::iterator) {
                Path relative = staging.relativize(path);
                String name = relative.toString().replace('\\', '/');
                if (relative.isAbsolute() || name.startsWith("/") || name.contains("../") || name.equals("..") || Files.isSymbolicLink(path))
                    throw new IOException("Unsafe path in LOAD report bundle.");
                long size = Files.size(path);
                if (size > MAX_REPORT_FILE_BYTES) throw new IOException("LOAD report file exceeds the 16 MiB artifact entry limit: " + name);
                files.add(fields("path", name, "lengthBytes", size, "sha256", sha256(path)));
            }
        }
        if (files.size() > 9000) throw new IOException("LOAD report bundle exceeds the bounded file count.");
        Object checkpointMetadata = result.metrics().value("checkpoints");
        Object checkpointSequence = checkpointMetadata instanceof Map
                ? ((Map<?, ?>) checkpointMetadata).get("sequence") : null;
        Map<String, Object> checkpointSequences = new LinkedHashMap<String, Object>();
        if (checkpointSequence != null) checkpointSequences.put("aggregate", checkpointSequence);
        for (Map.Entry<String, LoadRunResult> workload : result.workloads().entrySet()) {
            Object workloadCheckpoint = workload.getValue().metrics().value("checkpoints");
            if (workloadCheckpoint instanceof Map && ((Map<?, ?>) workloadCheckpoint).get("sequence") != null)
                checkpointSequences.put(workload.getKey(), ((Map<?, ?>) workloadCheckpoint).get("sequence"));
        }
        return fields("schemaVersion", REPORT_MANIFEST_SCHEMA, "state", "COMPLETE", "complete", Boolean.TRUE,
                "runId", result.runId(), "executionStatus", result.executionStatus(),
                "performanceVerdict", result.performanceVerdict(), "accountingStatus", result.accountingStatus(),
                "startedAt", result.startedAt().toString(),
                "endedAt", result.endedAt().toString(), "durationMs",
                Math.max(0L, java.time.Duration.between(result.startedAt(), result.endedAt()).toMillis()),
                "checkpointSequence", checkpointSequence, "checkpointSequences", checkpointSequences, "files", files);
    }

    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long treeBytes(Path root) throws IOException {
        long total = 0L;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS))::iterator) {
                long size = Files.size(path);
                if (size > MAX_REPORT_TOTAL_BYTES - total) return MAX_REPORT_TOTAL_BYTES + 1L;
                total += size;
            }
        }
        return total;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void createPrivateParents(Path root, Path parent) throws IOException {
        if (!parent.startsWith(root)) throw new IOException("LOAD report parent escaped its run directory.");
        Path current = root;
        for (Path component : root.relativize(parent)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("LOAD report path contains a non-private directory component.");
            } else {
                Files.createDirectory(current);
            }
        }
        if (!current.toRealPath().startsWith(root)) throw new IOException("LOAD report parent escaped its run directory.");
    }

    private static void removeTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    /** Enforce per-file and cross-file UTF-8 byte caps while writing, not after allocation. */
    private static final class ReportByteBudget {
        private long total;
        ReportByteBudget(long detailBytes) throws IOException {
            if (detailBytes > MAX_REPORT_TOTAL_BYTES)
                throw new IOException("LOAD detailed report output exceeds its 120 MiB bounded report budget.");
            total = detailBytes;
        }
        OutputStream open(Path path) throws IOException {
            OutputStream target = Files.newOutputStream(path);
            return new FilterOutputStream(target) {
                private long fileBytes;
                private void check(int size) throws IOException {
                    if (size < 0 || fileBytes > MAX_REPORT_FILE_BYTES - size)
                        throw new IOException("LOAD report file exceeds the 16 MiB artifact entry limit: " + path.getFileName());
                    if (total > MAX_REPORT_TOTAL_BYTES - size)
                        throw new IOException("LOAD detailed report output exceeds its 120 MiB bounded report budget.");
                    fileBytes += size;
                    total += size;
                }
                @Override public void write(int value) throws IOException { check(1); out.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    check(length); out.write(bytes, offset, length);
                }
            };
        }
    }

    private String html(Path runDirectory, LoadRunResult result, Map<String, Object> summary) {
        @SuppressWarnings("unchecked") Map<String, Object> metrics = (Map<String, Object>) summary.get("metrics");
        StringBuilder html = new StringBuilder(64 * 1024);
        String status = result.status().name();
        String statusClass = result.passed() ? "pass" : "fail";
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<title>ATT Load ").append(escape(result.runId())).append("</title>");
        html.append("<style>");
        html.append("body{font:14px system-ui,-apple-system,sans-serif;line-height:1.4;margin:0;color:#172033;background:#f6f8fb}");
        html.append("main{max-width:1440px;margin:auto;padding:24px}h1{margin:.1em 0}.muted{color:#5d687b}.panel{background:#fff;border:1px solid #dce2eb;border-radius:8px;padding:16px;margin:16px 0;box-shadow:0 1px 2px #15233d0d}");
        html.append("table{width:100%;border-collapse:collapse;margin-top:8px}td,th{border-bottom:1px solid #e5e9f0;padding:7px;text-align:left;vertical-align:top}th{background:#f3f6fa;font-weight:650}tbody tr:hover{background:#fafcff}");
        html.append(".pass{color:#087443}.fail{color:#b42318}.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px}.kpi{border:1px solid #dce2eb;border-radius:8px;padding:12px;background:#fff}.kpi strong{display:block;font-size:1.45em;margin-top:3px}.kpi small{display:block;color:#5d687b;margin-top:4px}");
        html.append(".concepts{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px}.concept{border-radius:8px;padding:12px;background:#eef4ff;border-left:5px solid #356ae6}.concept.drop{background:#fff5e8;border-left-color:#d97706}.concept.error{background:#fff0f0;border-left-color:#c2413a}.concept strong{display:block;font-size:1.25em}.badge{display:inline-block;border-radius:999px;padding:2px 8px;font-weight:650}.badge.pass{background:#dcfce7}.badge.fail{background:#fee2e2}.badge.info{background:#e0e7ff;color:#243b82}.badge.drop{background:#ffedd5;color:#9a3412}");
        html.append(".chart-grid{display:grid;grid-template-columns:minmax(0,1fr);gap:12px}.chart-card{box-sizing:border-box;min-width:0;width:100%;border:1px solid #e5e9f0;border-radius:7px;padding:10px}.chart-card h3{margin:0 0 8px}.chart-card svg{display:block;width:100%;height:auto;background:#fff}.chart-legend{display:flex;flex-wrap:wrap;justify-content:flex-start;gap:8px 14px;margin:8px 0 0;padding:0;list-style:none}.chart-legend li{display:flex;align-items:center;gap:5px}.chart-swatch{display:inline-block;width:15px;height:3px;background:var(--c)}");
        html.append("pre{white-space:pre-wrap;word-break:break-word;background:#f7f9fc;padding:10px;border-radius:5px;max-height:420px;overflow:auto}.scroll{overflow:auto}a{color:#2459b8}.empty{color:#68758a;font-style:italic}.note{font-size:.92em;color:#5d687b}");
        html.append("</style></head><body><main>");
        html.append("<header class=\"panel\"><h1>ATT Load ").append(escape(result.runId())).append("</h1>");
        html.append("<p class=\"muted\">Model: <b>").append(escape(result.scenario().model().wireName())).append("</b> · ");
        html.append("Execution: <span class=\"badge ").append(statusClass).append("\">").append(escape(status)).append("</span> · Exit code: <b>").append(result.exitCode()).append("</b></p>");
        html.append("<p class=\"note\">Run ").append(escape(result.startedAt().toString())).append(" to ").append(escape(result.endedAt().toString()));
        html.append(" (duration ").append(display(summary.get("durationMs"))).append(" ms). Load output is isolated below this run directory.</p></header>");

        List<ThresholdResult> thresholdResults=result.thresholds().results();
        String thresholdSummary;
        if(thresholdResults.isEmpty())thresholdSummary="none configured";
        else {
            int passed=0, failed=0, notEvaluated=0;
            for(ThresholdResult item:thresholdResults) {
                if("NOT_EVALUATED".equals(item.status()))notEvaluated++;
                else if(item.passed())passed++;
                else failed++;
            }
            thresholdSummary=passed+" passed / "+failed+" failed / "+notEvaluated+" not evaluated";
        }
        html.append("<p>Thresholds: ").append(thresholdSummary)
                .append(" / Execution: ").append(escape(status)).append(" (runtime errors, all phases: ")
                .append(count(metrics.get("runtimeError"))).append(")</p>");
        appendKpis(html, result, metrics);
        appendFailureDiagnosis(html,result,metrics);
        appendModelSemantics(html, result, metrics);
        appendPhaseTable(html, result, summary);
        appendMetricsTable(html, metrics);
        appendWorkloadTable(html, result);
        appendThresholdTable(html, result.thresholds().results());
        appendResourceTable(html, result.resources());
        appendEvidenceTable(html, runDirectory, result.evidence());
        appendTimeSeries(html, result);

        html.append("<section class=\"panel\"><h2>Machine-readable summary</h2><p class=\"note\">Aggregate counters and thresholds are exact. Detailed per-workload series are stored in versioned, relative sidecars. Read ");
        html.append("<a href=\"../load-summary.json\">load-summary.json</a>, <a href=\"../load-summary.yaml\">load-summary.yaml</a>, <a href=\"../details/v1/manifest.json\">details/v1/manifest.json</a> and <a href=\"../load-report-manifest.json\">load-report-manifest.json</a>.</p></section>");
        String embedded = JsonSupport.write(summary).replace("<", "\\u003c");
        html.append("<script type=\"application/json\" id=\"att-load-summary-data\">").append(embedded).append("</script>");
        appendTrendScript(html);
        html.append("</main></body></html>");
        return html.toString();
    }

    private static long numeric(Map<String,Object> metrics,String key) {
        Object value=metrics.get(key);return value instanceof Number?((Number)value).longValue():0L;
    }
    private String errorCounts(Map<String,Object> metrics,String key) {
        long numerator=key==null?numeric(metrics,"measuredFailure")+numeric(metrics,"measuredRuntimeError"):numeric(metrics,key);
        return numerator+" / "+numeric(metrics,"measuredCompleted")+" measured completions";
    }
    private String errorRate(Map<String,Object> metrics,String key) {
        return numeric(metrics,"measuredCompleted")==0?"N/A":percent(metrics.get(key));
    }
    private void appendFailureDiagnosis(StringBuilder html,LoadRunResult result,Map<String,Object> metrics) {
        html.append("<section class=\"panel\"><h2>Runtime failures / Failure diagnosis</h2><p>Runtime errors (all phases): ")
                .append(count(metrics.get("runtimeError"))).append("; measured runtime errors: ")
                .append(count(metrics.get("measuredRuntimeError"))).append(". Warm-up errors affect execution outcome, but are excluded from measured rates.</p>");
        html.append("<p>Classifications: ").append(escape(display(metrics.get("errorClassifications")))).append("</p>");
        Object samples=metrics.get("failureDiagnostics");
        if(samples instanceof List && !((List<?>)samples).isEmpty()) {
            html.append("<p>At most 100 lightweight failure samples per collector; counts remain complete. Retained Case Log links appear in the Evidence section. Evidence availability is independent of iteration outcome.</p><pre>")
                    .append(escape(JsonSupport.write(samples))).append("</pre>");
        } else html.append("<p>No lightweight failure samples were recorded.</p>");
        for(Map.Entry<String,LoadRunResult> workload:result.workloads().entrySet()) {
            html.append("<h3>").append(escape(workload.getKey())).append("</h3>");
            Object workloadSamples=workload.getValue().metrics().values().get("failureDiagnostics");
            if(workloadSamples instanceof List && !((List<?>)workloadSamples).isEmpty())html.append("<pre>").append(escape(JsonSupport.write(workloadSamples))).append("</pre>");
            else html.append("<p>No lightweight failure samples were recorded.</p>");
        }
        html.append("</section>");
    }

    private void appendKpis(StringBuilder html, LoadRunResult result, Map<String, Object> metrics) {
        Object windowValue = metrics.get("windowMetrics");
        if (windowValue instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> windowMetrics = (Map<String, Object>) windowValue;
            @SuppressWarnings("unchecked") Map<String, Object> windows = (Map<String, Object>) windowMetrics.get("windows");
            if (windows != null) { appendWindowOverview(html, result, metrics, windows); return; }
        }
        html.append("<section class=\"panel\"><h2>Run overview</h2><div class=\"kpis\">");
        kpi(html, "Completed TPS", rate(metrics.get("completedThroughput")), "measured completed iterations per second");
        kpi(html, "p95 latency", metrics.get("p95Ms"), "milliseconds, bounded reservoir");
        kpi(html, "Successful TPS", rate(metrics.get("successfulThroughput")), "measured PASS iterations per second");
        kpi(html, "SUT Fail %", errorRate(metrics,"sutErrorRate"), errorCounts(metrics,"measuredFailure"));
        kpi(html, "Runtime Error %", errorRate(metrics,"runtimeErrorRate"), errorCounts(metrics,"measuredRuntimeError"));
        kpi(html, "Total Error %", errorRate(metrics,"totalErrorRate"), errorCounts(metrics,null));
        if (result.scenario().model() == LoadScenario.Model.ARRIVAL_RATE) {
            kpi(html, "Configured arrival", rate(metrics.get("configuredArrivalRatePerSecond")), "target generator rate /s");
            kpi(html, "Achieved scheduling", rate(metrics.get("measuredAchievedArrivalRate")), "started iterations /s, measured phases");
            kpi(html, "Generator drops", percent(metrics.get("droppedRate")), "capacity saturation, not SUT error");
        } else {
            kpi(html, "Configured users", metrics.get("configuredUsers"), "closed virtual users");
            kpi(html, "Max active VUs", metrics.get("maxActiveVus"), "observed concurrent virtual users");
        }
        html.append("</div></section>");
    }

    private void appendWindowOverview(StringBuilder html, LoadRunResult result, Map<String, Object> metrics,
                                      Map<String, Object> windows) {
        @SuppressWarnings("unchecked") Map<String, Object> steady = (Map<String, Object>) windows.get("steady");
        String execution = result.executionStatus();
        String verdict = result.performanceVerdict();
        html.append("<section class=\"panel\"><h2>Run overview</h2><p>Execution status: <span class=\"badge ")
                .append("ERROR".equals(execution) ? "fail" : "pass").append("\">").append(escape(execution))
                .append("</span> · Performance verdict: <span class=\"badge ")
                .append("MET".equals(verdict) ? "pass" : "NOT_MET".equals(verdict) ? "fail" : "info").append("\">").append(escape(verdict)).append("</span>");
        if (result.scenario().thresholds().isEmpty() && result.workloads().values().stream().allMatch(child -> child.scenario().thresholds().isEmpty()))
            html.append(" · No thresholds configured");
        html.append("</p><label for=\"load-window\">Metrics window </label><select id=\"load-window\">")
                .append("<option value=\"steady\" selected>Steady</option><option value=\"measured\">Measured</option><option value=\"all\">All execution</option></select>")
                .append("<p id=\"window-definition\" class=\"note\">");
        if (steady != null) html.append(escape(String.valueOf(steady.get("durationMs")))).append(" ms configured STEADY window; actual event-time rates divide in-window events by this fixed duration. Latency and outcomes follow scheduled cohorts, including drain.");
        html.append("</p><div id=\"window-kpis\" class=\"kpis\">");
        if (steady == null) { html.append("<p class=\"empty\">Steady window is unavailable.</p>"); }
        else appendWindowCards(html, result, metrics, steady, "steady");
        html.append("</div><noscript><p class=\"note\">The initial cards show STEADY. Window selection and charts need JavaScript; the full metrics remain available in JSON/YAML below.</p></noscript></section>");
    }

    @SuppressWarnings("unchecked")
    private void appendWindowCards(StringBuilder html, LoadRunResult result, Map<String, Object> metrics,
                                  Map<String, Object> window, String scope) {
        boolean arrival = result.scenario().model() == LoadScenario.Model.ARRIVAL_RATE;
        Object target = "steady".equals(scope) && arrival ? metrics.get("configuredArrivalRatePerSecond") : "steady".equals(scope) ? metrics.get("configuredUsers") : "Variable by phase";
        String targetNote = arrival ? ("steady".equals(scope) ? "configured steady target" : "arrival target follows the phase curve")
                : "closed virtual user model; no configured TPS target";
        Map<String, Object> cohort = (Map<String, Object>) window.get("cohort");
        Map<String, Object> latency = cohort == null ? Collections.<String, Object>emptyMap() : (Map<String, Object>) cohort.get("latency");
        html.append("<div class=\"kpi\"><span class=\"muted\">Configured target</span><strong>")
                .append(arrival && target instanceof Number ? rate(target) : escape(display(target))).append("</strong><small>").append(escape(targetNote)).append("</small></div>");
        kpi(html, "Delivered started TPS", rate(window.get("startedRate")), "event-time starts / configured " + display(window.get("durationMs")) + " ms");
        kpi(html, "Actual completed TPS", rate(window.get("completedRate")), "event-time completions / configured window");
        kpi(html, "Successful TPS", rate(window.get("successfulRate")), "event-time PASS completions / configured window");
        kpi(html, "Latency p50 / p95 / p99", display(latency.get("p50Ms")) + " / " + display(latency.get("p95Ms")) + " / " + display(latency.get("p99Ms")) + " ms",
                display(latency.get("sampleCount")) + " samples from " + display(latency.get("observationCount")) + " cohort completions; " + display(latency.get("confidence")));
        kpi(html, "Generator drops", display(window.get("dropped")) + " (" + percent(window.get("droppedRate")) + ")", "dropped / scheduled arrivals");
        kpi(html, "SUT FAIL", display(cohort.get("sutFailure")) + " (" + percent(cohort.get("sutErrorRate")) + ")", "scheduled cohort outcomes");
        kpi(html, "Runtime ERROR", display(cohort.get("runtimeError")) + " (" + percent(cohort.get("runtimeErrorRate")) + ")", "scheduled cohort outcomes");
        long totalErrors = numericCount(cohort.get("sutFailure")) + numericCount(cohort.get("runtimeError"));
        kpi(html, "Total ERROR", totalErrors + " (" + percent(cohort.get("errorRate")) + ")",
                "SUT FAIL plus runtime ERROR / scheduled cohort outcomes");
    }

    private void appendModelSemantics(StringBuilder html, LoadRunResult result, Map<String, Object> metrics) {
        html.append("<section class=\"panel\"><h2>Workload semantics</h2><div class=\"concepts\">");
        Map<?, ?> steady = steadyWindow(metrics);
        Object completedRate = steady.isEmpty() ? metrics.get("completedThroughput") : steady.get("completedRate");
        Object successfulRate = steady.isEmpty() ? metrics.get("successfulThroughput") : steady.get("successfulRate");
        String rateBasis = steady.isEmpty() ? "legacy measured throughput" : "event-time completions / configured steady duration";
        if (result.scenario().model() == LoadScenario.Model.ARRIVAL_RATE) {
            concept(html, "Configured arrival rate", rate(metrics.get("configuredArrivalRatePerSecond")), "scenario target; it is not completed TPS", "");
            concept(html, "Achieved scheduling rate", rate(metrics.get("measuredAchievedArrivalRate")), "started / planned arrivals in measured phases", "");
            concept(html, "Actual completed TPS", rate(completedRate), rateBasis, "");
            concept(html, "Actual successful TPS", rate(successfulRate), "event-time PASS completions / configured steady duration", "");
            concept(html, "Dropped arrivals", count(metrics.get("measuredDropped")), "generator saturation; excluded from SUT error", "drop");
            concept(html, "SUT failures", count(metrics.get("measuredFailure")), "completed iterations with FAIL status", "error");
        } else {
            concept(html, "Configured users", count(metrics.get("configuredUsers")), "stable closed-model virtual users", "");
            concept(html, "Active VUs", count(metrics.get("maxActiveVus")), "maximum concurrently active virtual users", "");
            concept(html, "Actual completed TPS", rate(completedRate), rateBasis, "");
            concept(html, "Actual successful TPS", rate(successfulRate), "event-time PASS completions / configured steady duration", "");
            concept(html, "SUT failures", count(metrics.get("measuredFailure")), "completed iterations with FAIL status", "error");
        }
        html.append("</div></section>");
    }

    private void appendPhaseTable(StringBuilder html, LoadRunResult result, Map<String, Object> summary) {
        html.append("<section class=\"panel\"><h2>Phase windows and event-time completion rates</h2>");
        html.append("<p class=\"note\">Counts and rates use event timestamps inside each phase window. Cohort outcomes and latency follow scheduled timestamps, including completions during later phases or drain. DRAIN has no configured duration; its rates use the observed post-generation interval.</p>");
        Map<?, ?> windows = Collections.emptyMap();
        Object windowMetricsValue = result.metrics().values().get("windowMetrics");
        if (windowMetricsValue instanceof Map) windows = nested((Map<?, ?>) windowMetricsValue, "windows");
        Object timingValue = summary.get("timing");
        if (!(timingValue instanceof Map)) { html.append("<p class=\"empty\">No phase timing available.</p></section>"); return; }
        Object phasesValue = ((Map<?, ?>) timingValue).get("phases");
        if (!(phasesValue instanceof List)) { html.append("<p class=\"empty\">No phase timing available.</p></section>"); return; }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Phase</th><th>Phase window</th><th>Scheduled</th><th>Started</th><th>Event-time completions</th><th>Event-time PASS</th><th>Event-time SUT FAIL</th><th>Event-time runtime ERROR</th><th>Drops</th><th>Completed/s</th><th>Successful/s</th><th>Scheduled cohort completed</th><th>Cohort SUT FAIL %</th><th>Cohort runtime ERROR %</th><th>Cohort p95</th></tr></thead><tbody>");
        for (Object item : (List<?>) phasesValue) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> configured = (Map<?, ?>) item;
            String phase = String.valueOf(configured.get("phase"));
            appendPhaseRow(html, phase, configured, nested(windows, phase));
        }
        Map<?, ?> drain = nested(windows, "DRAIN");
        if (!drain.isEmpty()) appendPhaseRow(html, "DRAIN", Collections.emptyMap(), drain);
        html.append("</tbody></table></div></section>");
    }

    private void appendPhaseRow(StringBuilder html, String phase, Map<?, ?> configured, Map<?, ?> eventWindow) {
        Map<?, ?> cohort = nested(eventWindow, "cohort");
        Map<?, ?> latency = nested(cohort, "latency");
        Object configuredStart = configured.get("startAt");
        Object configuredEnd = configured.get("endAt");
        Object start = configuredStart == null ? phaseBoundary(eventWindow.get("windowStartEpochMs")) : configuredStart;
        Object end = configuredEnd == null ? phaseBoundary(eventWindow.get("windowEndEpochMs")) : configuredEnd;
        Object duration = configured.containsKey("durationMs") ? configured.get("durationMs") : eventWindow.get("durationMs");
        String windowLabel = ("DRAIN".equals(phase) ? "Observed: " : "Configured: ")
                + display(start) + " → " + display(end) + " (" + display(duration) + " ms)";
        html.append("<tr><td><b>").append(escape(phase)).append("</b></td><td>")
                .append(escape(windowLabel)).append("</td><td>")
                .append(display(eventWindow.get("scheduled"))).append("</td><td>")
                .append(display(eventWindow.get("started"))).append("</td><td>")
                .append(display(eventWindow.get("completed"))).append("</td><td>")
                .append(display(eventWindow.get("successful"))).append("</td><td>")
                .append(display(eventWindow.get("sutFailure"))).append("</td><td>")
                .append(display(eventWindow.get("runtimeError"))).append("</td><td>")
                .append(display(eventWindow.get("dropped"))).append("</td><td>")
                .append(rate(eventWindow.get("completedRate"))).append("</td><td>")
                .append(rate(eventWindow.get("successfulRate"))).append("</td><td>")
                .append(display(cohort.get("completed"))).append("</td><td>")
                .append(percent(cohort.get("sutErrorRate"))).append("</td><td>")
                .append(percent(cohort.get("runtimeErrorRate"))).append("</td><td>")
                .append(display(latency.get("p95Ms"))).append(" ms</td></tr>");
    }

    private Object phaseBoundary(Object epochMillis) {
        if (!(epochMillis instanceof Number)) return epochMillis;
        return java.time.Instant.ofEpochMilli(((Number) epochMillis).longValue()).toString();
    }

    private void appendMetricsTable(StringBuilder html, Map<String, Object> metrics) {
        html.append("<section class=\"panel\"><h2>Aggregate metrics</h2><div class=\"scroll\"><table><thead><tr><th>Metric</th><th>Value</th></tr></thead><tbody>");
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            if ("buckets".equals(entry.getKey()) || "phases".equals(entry.getKey())) continue;
            html.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>").append(escape(display(entry.getValue()))).append("</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void appendWorkloadTable(StringBuilder html, LoadRunResult result) {
        if (result.workloads().isEmpty()) return;
        html.append("<section class=\"panel\"><h2>Workloads</h2>");
        html.append("<p class=\"note\">Each workload is independently paced. Steady rates use actual event timestamps and the configured steady duration; latency and error percentages follow scheduled cohorts. Aggregate latency is not an average of workload percentiles.</p>");
        html.append("<div class=\"scroll\"><table><thead><tr><th>Workload</th><th>Target</th><th>Model</th><th>Configured load</th><th>Steady started/s</th><th>Steady completed/s</th><th>Steady successful/s</th><th>Steady p95</th><th>SUT Fail %</th><th>Runtime Error %</th><th>Total Error %</th><th>Drop</th><th>Thresholds</th><th>Execution</th><th>Performance</th></tr></thead><tbody>");
        for (Map.Entry<String, LoadRunResult> entry : result.workloads().entrySet()) {
            LoadRunResult child = entry.getValue();
            Map<String, Object> metrics = child.metrics().values();
            Map<?, ?> steady = steadyWindow(metrics);
            Map<?, ?> cohort = nested(steady, "cohort");
            Map<?, ?> latency = nested(cohort, "latency");
            Object startedRate = steady == null ? metrics.get("measuredAchievedArrivalRate") : steady.get("startedRate");
            Object completedRate = steady == null ? metrics.get("completedThroughput") : steady.get("completedRate");
            Object successfulRate = steady == null ? metrics.get("successfulThroughput") : steady.get("successfulRate");
            Object p95 = latency.isEmpty() ? metrics.get("p95Ms") : latency.get("p95Ms");
            String configured = child.scenario().model() == LoadScenario.Model.CLOSED
                    ? child.scenario().users() + " users"
                    : rate(child.scenario().arrivalRatePerSecond());
            String target = child.scenario().workload().mixed()
                    ? child.scenario().workload().mix().size() + " mixed targets"
                    : child.scenario().targetType() + ":" + child.scenario().targetId();
            html.append("<tr><td><b>").append(escape(entry.getKey())).append("</b></td><td>").append(escape(target))
                    .append("</td><td>").append(escape(child.scenario().model().wireName())).append("</td><td>")
                    .append(escape(configured)).append("</td><td>").append(rate(startedRate))
                    .append("</td><td>").append(rate(completedRate)).append("</td><td>").append(rate(successfulRate))
                    .append("</td><td>").append(display(p95)).append(" ms</td><td>")
                    .append(percent(cohort.isEmpty() ? metrics.get("sutErrorRate") : cohort.get("sutErrorRate"))).append("</td><td>")
                    .append(percent(cohort.isEmpty() ? metrics.get("runtimeErrorRate") : cohort.get("runtimeErrorRate"))).append("</td><td>")
                    .append(percent(cohort.isEmpty() ? metrics.get("totalErrorRate") : cohort.get("errorRate"))).append("</td><td>")
                    .append(percent(steady == null ? metrics.get("droppedRate") : steady.get("droppedRate"))).append("</td><td>")
                    .append(escape(workloadThresholdSummary(child.thresholds().results())))
                    .append("</td><td class=\"").append("ERROR".equals(child.executionStatus()) ? "fail\">" : "pass\">")
                    .append(escape(child.executionStatus())).append("</td><td class=\"")
                    .append("MET".equals(child.performanceVerdict()) ? "pass" : "NOT_MET".equals(child.performanceVerdict()) ? "fail" : "info")
                    .append("\">").append(escape(child.performanceVerdict())).append("</td></tr>");
        }
        html.append("</tbody></table></div>");
        for (Map.Entry<String, LoadRunResult> entry : result.workloads().entrySet()) {
            LoadRunResult child = entry.getValue();
            if (!child.scenario().workload().mixed()) continue;
            Object raw = child.resources().get("mix");
            if (!(raw instanceof Map)) continue;
            html.append("<h3>").append(escape(entry.getKey())).append(" mix").append("</h3><div class=\"scroll\"><table><thead><tr><th>Mix entry</th><th>Target</th><th>Weight</th><th>Selected</th><th>Steady started/s</th><th>Steady completed/s</th><th>Steady successful/s</th><th>Steady p95</th><th>SUT Fail %</th><th>Runtime Error %</th><th>Drops</th></tr></thead><tbody>");
            @SuppressWarnings("unchecked") Map<String, Object> mixes = (Map<String, Object>) raw;
            for (Map.Entry<String, Object> mix : mixes.entrySet()) {
                if (!(mix.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) mix.getValue();
                @SuppressWarnings("unchecked") Map<String, Object> target = (Map<String, Object>) item.get("target");
                @SuppressWarnings("unchecked") Map<String, Object> mixMetrics = (Map<String, Object>) item.get("metrics");
                Map<?, ?> mixSteady = steadyWindow(mixMetrics), mixCohort = nested(mixSteady, "cohort");
                Map<?, ?> mixLatency = nested(mixCohort, "latency");
                html.append("<tr><td>").append(escape(mix.getKey())).append("</td><td>")
                        .append(escape(target.get("type") + ":" + target.get("id"))).append("</td><td>")
                        .append(escape(display(item.get("configuredWeight")))).append("</td><td>")
                        .append(escape(display(item.get("selectedCount")) + " (" + display(item.get("selectedPercent")) + "%)"))
                        .append("</td><td>").append(rate(mixSteady.isEmpty() ? mixMetrics.get("measuredAchievedArrivalRate") : mixSteady.get("startedRate")))
                        .append("</td><td>").append(rate(mixSteady.isEmpty() ? mixMetrics.get("completedThroughput") : mixSteady.get("completedRate")))
                        .append("</td><td>").append(rate(mixSteady.isEmpty() ? mixMetrics.get("successfulThroughput") : mixSteady.get("successfulRate")))
                        .append("</td><td>").append(display(mixLatency.isEmpty() ? mixMetrics.get("p95Ms") : mixLatency.get("p95Ms"))).append(" ms</td><td>")
                        .append(percent(mixCohort.isEmpty() ? mixMetrics.get("sutErrorRate") : mixCohort.get("sutErrorRate"))).append("</td><td>")
                        .append(percent(mixCohort.isEmpty() ? mixMetrics.get("runtimeErrorRate") : mixCohort.get("runtimeErrorRate"))).append("</td><td>")
                        .append(percent(mixSteady.isEmpty() ? mixMetrics.get("droppedRate") : mixSteady.get("droppedRate"))).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        html.append("</section>");
    }

    private String workloadThresholdSummary(List<ThresholdResult> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) return "none";
        StringBuilder result = new StringBuilder();
        for (ThresholdResult threshold : thresholds) {
            if (result.length() > 0) result.append("; ");
            result.append("errorRate".equals(threshold.name()) ? "errorRate (SUT FAIL-only)" : threshold.name()).append(' ').append(threshold.expression())
                    .append(" (actual ").append(threshold.actual()).append(") ")
                    .append(threshold.status());
            if (threshold.diagnostic() != null && !threshold.diagnostic().trim().isEmpty())
                result.append(" - ").append(threshold.diagnostic());
        }
        return result.toString();
    }

    private void appendThresholdTable(StringBuilder html, List<ThresholdResult> thresholds) {
        html.append("<section class=\"panel\"><h2>Thresholds</h2>");
        if (thresholds.isEmpty()) { html.append("<p class=\"empty\">No thresholds configured.</p></section>"); return; }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Name</th><th>Expected</th><th>Actual</th><th>Status</th><th>Diagnostic</th></tr></thead><tbody>");
        for (ThresholdResult threshold : thresholds) {
            String status = threshold.status();
            String style = "PASS".equals(status) ? "pass" : "FAIL".equals(status) ? "fail" : "info";
            html.append("<tr><td>").append(escape("errorRate".equals(threshold.name())?"errorRate (SUT FAIL-only)":threshold.name())).append("</td><td>").append(escape(threshold.expression())).append("</td><td>")
                    .append(escape(threshold.actual())).append("</td><td class=\"").append(style).append("\">").append(escape(status))
                    .append("</td><td>").append(escape(threshold.diagnostic())).append("</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void appendResourceTable(StringBuilder html, Map<String, Object> resources) {
        html.append("<section class=\"panel\"><h2>Resource and generator diagnostics</h2><p class=\"note\">Pool saturation and ATT generator pressure are reported separately from application/SUT failures. Credentials and live resource objects are never included.</p>");
        if (resources == null || resources.isEmpty()) { html.append("<p class=\"empty\">No resource diagnostics were collected for this run.</p></section>"); return; }
        Object preparationValue = resources.get("preparation");
        if (preparationValue instanceof Map) {
            Map<?, ?> preparation = (Map<?, ?>) preparationValue;
            String status = String.valueOf(preparation.get("status"));
            html.append("<h3>LOAD resource preparation</h3><p>Status: <b>").append(escape(status)).append("</b>; ")
                    .append("required ").append(display(preparation.get("required"))).append(", prepared ").append(display(preparation.get("prepared")))
                    .append(", failed ").append(display(preparation.get("failed"))).append(", dynamic skipped ").append(display(preparation.get("skippedDynamic")))
                    .append("; discovery ").append(display(preparation.get("discoveryMs"))).append(" ms; total ")
                    .append(display(preparation.get("preparationMs"))).append(" ms.</p>");
            Object rowsValue = preparation.get("helpers");
            if (rowsValue instanceof List && !((List<?>) rowsValue).isEmpty()) {
                html.append("<div class=\"scroll\"><table><thead><tr><th>Kind</th><th>Helper / instance</th><th>Status</th><th>Configured minIdle</th><th>Initial target</th><th>Verified idle</th><th>Max size</th><th>Setup ms</th><th>Details</th></tr></thead><tbody>");
                for (Object value : (List<?>) rowsValue) {
                    if (!(value instanceof Map)) continue;
                    Map<?, ?> row = (Map<?, ?>) value;
                    String details = row.containsKey("failureCategory") ? String.valueOf(row.get("failureCategory"))
                            : row.containsKey("fallback") ? String.valueOf(row.get("fallback"))
                            : row.containsKey("physicalConnections") ? String.valueOf(row.get("physicalConnections"))
                            : String.valueOf(row.get("sizingSource"));
                    String css = "READY".equals(row.get("status")) ? "pass" : "FAILED".equals(row.get("status")) ? "fail" : "info";
                    html.append("<tr><td>").append(escape(String.valueOf(row.get("kind")))).append("</td><td>")
                            .append(escape(String.valueOf(row.get("helper")))).append("</td><td class=\"").append(css).append("\">")
                            .append(escape(String.valueOf(row.get("status")))).append("</td><td>").append(display(row.get("configuredMinIdle")))
                            .append("</td><td>").append(display(row.get("initialReadyTarget"))).append("</td><td>").append(display(row.get("actualReady")))
                            .append("</td><td>").append(display(row.get("maxSize"))).append("</td><td>").append(display(row.get("preparationMs")))
                            .append("</td><td>").append(escape(details)).append("</td></tr>");
                }
                html.append("</tbody></table></div>");
            }
        }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Resource</th><th>Helper</th><th>Metric</th><th>Value</th></tr></thead><tbody>");
        for (Map.Entry<String, Object> resource : resources.entrySet()) {
            if (!(resource.getValue() instanceof Map)) continue;
            Map<?, ?> helpers = (Map<?, ?>) resource.getValue();
            if (helpers.isEmpty()) { html.append("<tr><td>").append(escape(resource.getKey())).append("</td><td colspan=\"3\">none opened</td></tr>"); continue; }
            for (Map.Entry<?, ?> helper : helpers.entrySet()) {
                if (!(helper.getValue() instanceof Map)) continue;
                for (Map.Entry<?, ?> metric : ((Map<?, ?>) helper.getValue()).entrySet()) {
                    html.append("<tr><td>").append(escape(resource.getKey())).append("</td><td>").append(escape(String.valueOf(helper.getKey())))
                            .append("</td><td>").append(escape(String.valueOf(metric.getKey()))).append("</td><td>")
                            .append(escape(display(metric.getValue()))).append("</td></tr>");
                }
            }
        }
        html.append("</tbody></table></div></section>");
    }

    @SuppressWarnings("unchecked")
    private void appendEvidenceTable(StringBuilder html, Path runDirectory, Map<String, Object> evidence) {
        html.append("<section class=\"panel\"><h2>Retained evidence</h2>");
        if(evidence!=null && evidence.get("policy") instanceof Map) {
            Map<?,?> policy=(Map<?,?>)evidence.get("policy");
            html.append("<p>Evidence quota mode: ").append(escape(String.valueOf(policy.get("quotaMode")))).append(". ");
            if("independent".equals(policy.get("quotaMode"))) {
                html.append("Success retained: ").append(escape(String.valueOf(evidence.get("retainedSuccessCount"))))
                        .append(" / ").append(escape(String.valueOf(policy.get("maxSuccessSamples"))))
                        .append("; failure retained: ").append(escape(String.valueOf(evidence.get("retainedFailureCount"))))
                        .append(" / ").append(escape(String.valueOf(policy.get("maxFailureSamples"))));
            } else if(LoadEvidencePolicy.QUOTA_SCOPE_PER_WORKLOAD.equals(String.valueOf(policy.get("quotaMode")))) {
                Object defaultsValue=policy.get("defaults");
                Map<?,?> defaults=defaultsValue instanceof Map?(Map<?,?>)defaultsValue:Collections.emptyMap();
                html.append("Each workload has independent hard caps. Defaults: success ")
                        .append(escape(String.valueOf(defaults.get("maxSuccessSamples"))))
                        .append(", failure ").append(escape(String.valueOf(defaults.get("maxFailureSamples"))))
                        .append(". Workload overrides and retained coverage are shown below.");
            } else html.append("Legacy combined cap: ").append(escape(String.valueOf(policy.get("maxSamples"))));
            html.append(".</p>");
            if(evidence.get("warning")!=null)html.append("<p class=\"warning\" role=\"alert\">").append(escape(String.valueOf(evidence.get("warning")))).append("</p>");
            Object workloadValue=evidence.get("workloads");
            if(workloadValue instanceof Map && !((Map<?,?>)workloadValue).isEmpty()) {
                boolean perWorkload=LoadEvidencePolicy.QUOTA_SCOPE_PER_WORKLOAD.equals(String.valueOf(policy.get("quotaMode")));
                html.append("<h3>Evidence coverage by workload</h3><div class=\"scroll\"><table><thead><tr><th>Workload</th>");
                html.append("<th>Target</th>");
                if(perWorkload)html.append("<th>Success cap</th><th>Failure cap</th>");
                html.append("<th>Eligible successes</th><th>Sampling ineligible</th><th>Success retained</th><th>Failure retained</th><th>Success quota skips</th><th>Failure quota skips</th><th>Missing evidence</th></tr></thead><tbody>");
                for(Map.Entry<?,?> entry:((Map<?,?>)workloadValue).entrySet()) {
                    if(!(entry.getValue() instanceof Map))continue;
                    Map<?,?> row=(Map<?,?>)entry.getValue();
                    html.append("<tr><td>").append(escape(String.valueOf(entry.getKey()))).append("</td>");
                    html.append("<td>").append(escape(display(row.get("targetId")))).append("</td>");
                    if(perWorkload)for(String key:new String[]{"maxSuccessSamples","maxFailureSamples"})
                        html.append("<td>").append(escape(display(row.get(key)))).append("</td>");
                    for(String key:new String[]{"eligibleSuccessCount","samplingIneligibleSuccessCount","retainedSuccessCount","retainedFailureCount","skippedSuccessQuotaCount","skippedFailureQuotaCount","skippedMissingEvidenceCount"})
                        html.append("<td>").append(escape(display(row.get(key)))).append("</td>");
                    html.append("</tr>");
                }
                html.append("</tbody></table></div>");
            }
        }
        Object itemsValue = evidence == null ? null : evidence.get("items");
        if (!(itemsValue instanceof List) || ((List<?>) itemsValue).isEmpty()) { html.append("<p class=\"empty\">No failure or sampled-success evidence was retained.</p></section>"); return; }
        html.append("<div class=\"scroll\"><table><thead><tr><th>Workload</th><th>EXEC.ID</th><th>Iteration</th><th>Kind</th><th>Case Log</th><th>Evidence</th></tr></thead><tbody>");
        for (Object value : (List<?>) itemsValue) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) value;
            String workload = item.get("workloadId") == null ? "" : String.valueOf(item.get("workloadId"));
            String path = item.get("path") == null ? "" : String.valueOf(item.get("path"));
            String executionId = item.get("execId") == null ? "" : String.valueOf(item.get("execId"));
            String caseLog = item.get("caseLog") == null ? "" : String.valueOf(item.get("caseLog"));
            String iteration = item.get("iterationId") == null ? "" : String.valueOf(item.get("iterationId"));
            String status = item.get("status") == null ? "" : String.valueOf(item.get("status"));
            html.append("<tr><td>").append(escape(workload)).append("</td><td>").append(escape(executionId)).append("</td><td>")
                    .append(escape(iteration)).append("</td><td>").append(escape(status)).append("</td><td>")
                    .append(artifactLink(runDirectory, caseLog, "Case Log")).append("</td><td>")
                    .append(artifactLink(runDirectory, path, "Evidence")).append("</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private String artifactLink(Path runDirectory, String reference, String label) {
        Path target = safeArtifact(runDirectory, reference);
        if (target == null) return "<span class=\"empty\">Not retained</span>";
        String relative = runDirectory.relativize(target).toString().replace('\\', '/');
        String href = "../" + encodePath(relative);
        return "<a href=\"" + escapeAttribute(href) + "\">" + escape(label) + "</a>";
    }

    private Path safeArtifact(Path runDirectory, String reference) {
        if (reference == null || reference.trim().isEmpty() || reference.indexOf('\\') >= 0) return null;
        try {
            Path base = runDirectory.toAbsolutePath().normalize();
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(base)) return null;
            Path raw = Paths.get(reference);
            Path target = (raw.isAbsolute() ? raw : base.resolve(raw)).normalize();
            if (!target.startsWith(base) || hasSymbolicLinkComponent(base, target)) return null;
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) return null;
            Path realBase = base.toRealPath();
            Path realTarget = target.toRealPath();
            return realTarget.startsWith(realBase) ? target : null;
        } catch (RuntimeException ignored) {
            return null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private boolean hasSymbolicLinkComponent(Path base, Path target) {
        Path current = base;
        for (Path component : base.relativize(target)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private String encodePath(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte valueByte : bytes) {
            int unsigned = valueByte & 0xff;
            if ((unsigned >= 'A' && unsigned <= 'Z') || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '_'
                    || unsigned == '.' || unsigned == '~' || unsigned == '/') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(hex[unsigned >>> 4]).append(hex[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private void appendTimeSeries(StringBuilder html, LoadRunResult result) {
        @SuppressWarnings("unchecked") Map<String, Object> contract = (Map<String, Object>) result.metrics().value("windowMetrics");
        @SuppressWarnings("unchecked") Map<String, Object> series = contract == null ? null : (Map<String, Object>) contract.get("timeSeries");
        @SuppressWarnings("unchecked") List<Map<String, Object>> points = series == null ? Collections.<Map<String, Object>>emptyList() : (List<Map<String, Object>>) series.get("points");
        html.append("<section class=\"panel\"><h2>Trends</h2><p class=\"note\">Offline charts use event timestamps and configured phase boundaries. Rates use full-bin duration; the window selector filters both the summary and charts. Hover or focus a legend entry for its definition. Latency follows scheduled cohorts, including completions in later phases or drain; bins disclose reservoir sample and observation counts.</p>");
        if (points.isEmpty()) { html.append("<p class=\"empty\">No time-series buckets were observed. No event-time series events were observed.</p></section>"); return; }
        html.append("<div class=\"chart-grid\"><section class=\"chart-card\"><h3>Rate (iterations/s)</h3><svg id=\"rate-chart\" viewBox=\"0 0 900 240\" role=\"img\" aria-label=\"Scheduled, started, completed, successful and target rates\"></svg><ul id=\"rate-legend\" class=\"chart-legend\"></ul></section>")
                .append("<section class=\"chart-card\"><h3>Latency (ms)</h3><svg id=\"latency-chart\" viewBox=\"0 0 900 240\" role=\"img\" aria-label=\"Iteration latency percentile trend\"></svg><ul id=\"latency-legend\" class=\"chart-legend\"></ul><p class=\"note\">Iteration latency includes framework and helper waiting; it is not SUT-only response time.</p></section>")
                .append("<section class=\"chart-card\"><h3>Concurrency and queue</h3><svg id=\"concurrency-chart\" viewBox=\"0 0 900 240\" role=\"img\" aria-label=\"Event-time in-flight, queue depth and drops\"></svg><ul id=\"concurrency-legend\" class=\"chart-legend\"></ul></section>")
                .append("<section class=\"chart-card\"><h3>Generator lag (ms)</h3><svg id=\"lag-chart\" viewBox=\"0 0 900 240\" role=\"img\" aria-label=\"Scheduler and worker submission lag\"></svg><ul id=\"lag-legend\" class=\"chart-legend\"></ul></section>")
                .append("<section class=\"chart-card\"><h3>Outcomes (%)</h3><svg id=\"failure-chart\" viewBox=\"0 0 900 240\" role=\"img\" aria-label=\"SUT failures, runtime errors and generator drops\"></svg><ul id=\"failure-legend\" class=\"chart-legend\"></ul><p class=\"note\">SUT FAIL and runtime ERROR use completed count; drops use scheduled arrivals.</p></section></div>")
                .append("<noscript><p class=\"note\">Charts need JavaScript. Event-time counts below use event timestamps; latency samples use scheduled time. The JSON/YAML summaries retain the same bounded data.</p></noscript>")
                .append("<h3>Event-time buckets</h3><div class=\"scroll\"><table><thead><tr><th>Window start</th><th>Bin duration</th><th>Phase coverage</th><th>Scheduled/s</th><th>Started/s</th><th>Actual completed/s</th><th>Successful/s</th><th>Scheduled-cohort p95 / sample / observation count</th><th>In-flight at bin end</th><th>Queue peak</th><th>Scheduler / submit lag</th><th>SUT fail</th><th>Runtime error</th><th>Drops</th></tr></thead><tbody>");
        long start = ((Number) result.metrics().value("startAtEpochMs")).longValue();
        for (Map<String, Object> point : points) {
            html.append("<tr><td>").append(escape(java.time.Instant.ofEpochMilli(((Number) point.get("windowStartEpochMs")).longValue()).toString()))
                    .append("</td><td>").append(display(point.get("durationMs"))).append(" ms</td><td>").append(escape(display(point.get("phaseCoverage")))).append("</td><td>").append(rate(point.get("scheduledRate")))
                    .append("</td><td>").append(rate(point.get("startedRate"))).append("</td><td>").append(rate(point.get("completedRate")))
                    .append("</td><td>").append(rate(point.get("successfulRate"))).append("</td><td>").append(display(point.get("latencyP95Ms")))
                    .append(" ms (sample n=").append(display(point.get("latencySampleCount"))).append(" / ")
                    .append(display(point.get("latencyObservationCount"))).append(" observations)</td><td>").append(display(point.get("inFlightAtBinEnd")))
                    .append("</td><td>").append(display(point.get("queueDepthPeak"))).append("</td><td>").append(display(point.get("schedulerLagMeanMs"))).append(" / ").append(display(point.get("submitLagMeanMs"))).append(" ms</td><td>").append(display(point.get("sutFailure"))).append("</td><td>").append(display(point.get("runtimeError")))
                    .append("</td><td>").append(display(point.get("dropped"))).append("</td></tr>");
        }
        html.append("</tbody></table></div></section>");
    }

    private void appendTrendScript(StringBuilder html) {
        html.append("<script>(function(){'use strict';")
                .append("var data=document.getElementById('att-load-summary-data'),s=data?JSON.parse(data.textContent):{},m=s.metrics||{},wm=m.windowMetrics||{},w=wm.windows||{},scenario=s.scenario||{};")
                .append("var select=document.getElementById('load-window'),box=document.getElementById('window-kpis'),definition=document.getElementById('window-definition');")
                .append("var timeSeries=wm.timeSeries||{},points=timeSeries.points||[],windowSeries=timeSeries.windowSeries||{},phaseBands=wm.phaseBoundaries||[],startMs=Date.parse(s.startedAt);function text(v){return v===null||v===undefined?'-':String(v)}function number(v){return typeof v==='number'&&isFinite(v)?Number(v.toFixed(3)).toString():text(v)}function rate(v){return typeof v==='number'?v.toFixed(3)+'/s':text(v)}function pct(v){return typeof v==='number'?(v*100).toFixed(2)+'%':text(v)}")
                .append("function card(label,value,note){var d=document.createElement('div'),a=document.createElement('span'),b=document.createElement('strong'),c=document.createElement('small');d.className='kpi';a.className='muted';a.textContent=label;b.textContent=value;c.textContent=note;d.appendChild(a);d.appendChild(b);d.appendChild(c);box.appendChild(d)}")
                .append("function show(k){var x=w[k];if(!x)return;while(box.firstChild)box.removeChild(box.firstChild);var c=x.cohort||{},l=c.latency||{},arrival=m.model==='arrivalRate',target=k==='steady'&&arrival?rate(m.configuredArrivalRatePerSecond):k==='steady'?text(m.configuredUsers):'Variable by phase';")
                .append("card('Configured target',target,arrival?(k==='steady'?'configured steady target':'phase-specific arrival schedule'):'closed virtual-user model; no configured TPS');card('Delivered started TPS',rate(x.startedRate),'event-time starts / configured '+text(x.durationMs)+' ms');card('Actual completed TPS',rate(x.completedRate),'event-time completions / configured window');card('Successful TPS',rate(x.successfulRate),'event-time PASS completions / configured window');")
                .append("card('Latency p50 / p95 / p99',number(l.p50Ms)+' / '+number(l.p95Ms)+' / '+number(l.p99Ms)+' ms',text(l.sampleCount)+' samples from '+text(l.observationCount)+' cohort completions; '+text(l.confidence));card('Generator drops',text(x.dropped)+' ('+pct(x.droppedRate)+')','dropped / scheduled arrivals');card('SUT FAIL',text(c.sutFailure)+' ('+pct(c.sutErrorRate)+')','scheduled cohort outcomes');card('Runtime ERROR',text(c.runtimeError)+' ('+pct(c.runtimeErrorRate)+')','scheduled cohort outcomes');var chartNote=k==='all'?'Trend charts show the full execution; rates use full bucket durations.':windowSeries[k]?'Trend charts use buckets aligned to this configured window; the final bucket is clipped at the boundary and rates use its clipped duration.':'Trend charts omit buckets that cross this window boundary to avoid mixing out-of-window events.';definition.textContent=text(x.durationMs)+' ms configured '+text(x.phase)+' window; rates use in-window event timestamps and this fixed duration. '+chartNote+' Cohort outcomes include drain.';renderCharts()}if(select)select.addEventListener('change',function(){show(select.value)});")
                .append("function duration(v){if(typeof v==='number')return v;var a=String(v||'0ms').match(/^([0-9.]+)(ms|s|m|h)$/);return a?Number(a[1])*({ms:1,s:1000,m:60000,h:3600000}[a[2]]):0}function configuredRate(load){var a=String(load.arrivalRate||'0/s').match(/^([0-9.]+)\\/(s|m)$/);return a?Number(a[1])*(a[2]==='m'?1/60:1):0}")
                .append("function targetFor(load,at){var r=configuredRate(load),t=at-startMs,wu=duration(load.warmup),up=duration(load.rampUp),steady=duration(load.duration),down=duration(load.rampDown);if(t<wu)return 0;if(t<wu+up)return up?r*(t-wu)/up:r;if(t<wu+up+steady)return r;if(t<wu+up+steady+down)return down?r*(1-(t-wu-up-steady)/down):0;return 0}function target(p){if(m.model!=='arrivalRate')return null;var at=p.windowStartEpochMs+p.durationMs/2,workloads=scenario.workloads||[];if(workloads.length){var sum=0;workloads.forEach(function(x){sum+=targetFor(x.load||scenario.load||{},at)});return sum}return targetFor(scenario.load||{},at)}")
                .append("var palette=['#64748b','#2563eb','#0f766e','#16a34a','#d97706','#7c3aed','#be123c'];function el(tag,attrs){var e=document.createElementNS('http://www.w3.org/2000/svg',tag);Object.keys(attrs||{}).forEach(function(k){e.setAttribute(k,attrs[k])});return e}function legend(id,lines){var ul=document.getElementById(id);if(!ul)return;ul.textContent='';lines.forEach(function(line){var li=document.createElement('li'),sw=document.createElement('span'),label=document.createElement('span');sw.className='chart-swatch';sw.style.setProperty('--c',line.color);sw.setAttribute('aria-hidden','true');label.textContent=line.name;li.title=line.description;li.appendChild(sw);li.appendChild(label);ul.appendChild(li)})}")
                .append("function inWindow(p,win){return p.windowStartEpochMs>=win.windowStartEpochMs&&p.windowStartEpochMs+p.durationMs<=win.windowEndEpochMs}function overlapsWindow(p,win){return p.windowStartEpochMs<win.windowEndEpochMs&&p.windowStartEpochMs+p.durationMs>win.windowStartEpochMs}function currentPoints(){var key=select?select.value:'steady',win=w[key],scoped=windowSeries[key];if(key==='all')return points;if(scoped&&scoped.points)return scoped.points;if(!win)return points;return points.filter(function(p){return inWindow(p,win)})}function hasPartialBuckets(){var key=select?select.value:'steady',win=w[key];if(!win||key==='all'||windowSeries[key])return false;return points.some(function(p){return overlapsWindow(p,win)&&!inWindow(p,win)})}function sloValue(){var thresholds=scenario.thresholds||{},scope=thresholds.steady||{},expr=scope.p95;if(typeof expr!=='string')return null;var match=expr.match(/[0-9.]+\\s*ms/i);return match?parseFloat(match[0]):null}")
                .append("function chart(id,legendId,series,unit,baseMax,opts){var svg=document.getElementById(id),pts=currentPoints();if(!svg)return;if(!svg.getAttribute('data-original-aria-label'))svg.setAttribute('data-original-aria-label',svg.getAttribute('aria-label')||'Load trend');svg.textContent='';var legendList=document.getElementById(legendId);if(legendList)legendList.textContent='';if(!pts.length){var message=hasPartialBuckets()?'No complete time-series buckets inside selected window; partial boundary buckets are omitted.':'No data for selected window';svg.setAttribute('aria-label',message);var empty=el('text',{x:450,y:130,'text-anchor':'middle',fill:'#64748b','font-size':'14'});empty.textContent=message;svg.appendChild(empty);if(legendList){var item=document.createElement('li');item.textContent=message;legendList.appendChild(item)}return}svg.setAttribute('aria-label',svg.getAttribute('data-original-aria-label'));var W=900,H=240,L=55,R=15,T=20,B=34,iw=W-L-R,ih=H-T-B,lo=pts[0].windowStartEpochMs,hi=pts[pts.length-1].windowStartEpochMs+pts[pts.length-1].durationMs,span=Math.max(1,hi-lo),win=w[select?select.value:'steady'];if(win){lo=Math.max(lo,win.windowStartEpochMs);hi=Math.min(hi,win.windowEndEpochMs);span=Math.max(1,hi-lo)}function x(t){return L+Math.max(0,Math.min(1,(t-lo)/span))*iw}function y(v,max){return T+ih-ih*v/max}function labelValue(v){return unit==='%'?(v*100).toFixed(0)+'%':unit==='s'?v.toFixed(0):v.toFixed(0)}")
                .append("phaseBands.forEach(function(b){var a=Math.max(lo,b.windowStartEpochMs),z=Math.min(hi,b.windowEndEpochMs);if(z<=a)return;var colors={WARMUP:'#f1f5f9',RAMP_UP:'#eff6ff',STEADY:'#ecfdf5',RAMP_DOWN:'#fff7ed',DRAIN:'#f8fafc'};svg.appendChild(el('rect',{x:x(a),y:T,width:Math.max(0,x(z)-x(a)),height:ih,fill:colors[b.phase]||'#f8fafc'}));if(x(z)-x(a)>52){var tag=el('text',{x:(x(a)+x(z))/2,y:T+12,'text-anchor':'middle',fill:'#64748b','font-size':'10'});tag.textContent=b.phase;svg.appendChild(tag)}});")
                .append("var vals=[];series.forEach(function(q){pts.forEach(function(p){var v=q.key==='target'?target(p):p[q.key];if(typeof v==='number'&&isFinite(v))vals.push(v)})});var max=Math.max(1,baseMax||0);vals.forEach(function(v){max=Math.max(max,v)});var slo=id==='latency-chart'&&select&&select.value==='steady'?sloValue():null;if(slo!==null)max=Math.max(max,slo);for(var tick=0;tick<=4;tick++){var v=max*tick/4,yy=y(v,max);svg.appendChild(el('path',{d:'M'+L+' '+yy+'H'+(L+iw),stroke:'#e5e9f0','stroke-width':'1'}));var t=el('text',{x:L-7,y:yy+4,'text-anchor':'end',fill:'#5d687b','font-size':'11'});t.textContent=labelValue(v);svg.appendChild(t)}svg.appendChild(el('path',{d:'M'+L+' '+T+'V'+(T+ih)+'H'+(L+iw),stroke:'#8491a4',fill:'none'}));var axis=el('text',{x:L,y:13,fill:'#5d687b','font-size':'11'});axis.textContent=unit;svg.appendChild(axis);if(slo!==null){var sy=y(slo,max),threshold=el('path',{d:'M'+L+' '+sy+'H'+(L+iw),stroke:'#be123c','stroke-dasharray':'6 4','stroke-width':'1.5'}),st=el('title');st.textContent='Configured steady p95 threshold '+number(slo)+' ms';threshold.appendChild(st);svg.appendChild(threshold)}")
                .append("series.forEach(function(q,index){var coords=[],color=palette[index%palette.length];pts.forEach(function(p){var v=q.key==='target'?target(p):p[q.key];if(typeof v==='number'&&isFinite(v))coords.push(x(p.windowStartEpochMs+p.durationMs/2)+','+y(v,max))});if(coords.length){var line=el('polyline',{points:coords.join(' '),fill:'none',stroke:color,'stroke-width':'2'}),title=el('title');title.textContent=q.description;line.appendChild(title);svg.appendChild(line)}});")
                .append("if(opts&&opts.maxConcurrent){var yy=y(opts.maxConcurrent,max),limit=el('path',{d:'M'+L+' '+yy+'H'+(L+iw),stroke:'#be123c','stroke-dasharray':'6 4','stroke-width':'1.5'}),title=el('title');title.textContent='Configured maxConcurrent '+opts.maxConcurrent;limit.appendChild(title);svg.appendChild(limit)}if(id==='rate-chart'){pts.forEach(function(p){if(p.dropped>0){var xx=x(p.windowStartEpochMs+p.durationMs/2),mark=el('path',{d:'M'+xx+' '+(T+3)+'l-5 9h10z',fill:'#be123c'}),title=el('title');title.textContent='Generator drops: '+p.dropped;mark.appendChild(title);svg.appendChild(mark)}})}")
                .append("var t0=el('text',{x:L,y:H-6,fill:'#5d687b','font-size':'11'}),t1=el('text',{x:L+iw,y:H-6,'text-anchor':'end',fill:'#5d687b','font-size':'11'});t0.textContent=((lo-startMs)/1000).toFixed(0)+' s';t1.textContent=((hi-startMs)/1000).toFixed(0)+' s';svg.appendChild(t0);svg.appendChild(t1);var entries=series.map(function(q,i){return {name:q.name,color:palette[i%palette.length],description:q.description}});if(slo!==null)entries.push({name:'p95 SLO',color:'#be123c',description:'Configured steady p95 threshold'});legend(legendId,entries)}")
                .append("function renderCharts(){var load=scenario.load||{},max=Number(load.maxConcurrent)||Number(load.users)||Number(m.configuredMaxConcurrent)||Number(m.configuredUsers)||0;chart('rate-chart','rate-legend',[{key:'target',name:'Target',description:'Configured target rate with ramp curve'},{key:'scheduledRate',name:'Scheduled',description:'Arrivals grouped by scheduled timestamp'},{key:'startedRate',name:'Started',description:'Actual starts grouped by start timestamp'},{key:'completedRate',name:'Completed',description:'Actual completions grouped by completion timestamp'},{key:'successfulRate',name:'Successful',description:'PASS completions grouped by completion timestamp'}],'iterations/s',0);chart('latency-chart','latency-legend',[{key:'latencyP50Ms',name:'p50',description:'Scheduled cohort iteration latency; includes ATT/helper time'},{key:'latencyP95Ms',name:'p95',description:'Scheduled cohort iteration latency; sample count is shown in table'},{key:'latencyP99Ms',name:'p99',description:'Bounded sample estimate; interpret sparse buckets cautiously'}],'ms',0);chart('concurrency-chart','concurrency-legend',[{key:'inFlightAtBinEnd',name:'In-flight at bin end',description:'Event-time start/completion delta at the end of each bin'},{key:'queueDepthPeak',name:'Worker queue peak',description:'Highest sampled worker queue depth in the bin'}],'iterations',max,{maxConcurrent:max});chart('lag-chart','lag-legend',[{key:'schedulerLagMeanMs',name:'Scheduler lag',description:'Mean observed scheduler wakeup lag in the bin'},{key:'submitLagMeanMs',name:'Submit lag',description:'Mean time from planned arrival to worker start in the bin'}],'ms',0);chart('failure-chart','failure-legend',[{key:'sutFailureRate',name:'SUT FAIL',description:'SUT failures divided by actual completions'},{key:'runtimeErrorRate',name:'Runtime ERROR',description:'ATT/runtime errors divided by actual completions'},{key:'droppedRate',name:'Generator DROPPED',description:'Dropped arrivals divided by scheduled arrivals'}],'%',1);}")
                .append("show(select?select.value:'steady');})();</script>");
    }

    private void kpi(StringBuilder html, String name, Object value, String note) {
        html.append("<div class=\"kpi\"><span class=\"muted\">").append(escape(name)).append("</span><strong>")
                .append(escape(display(value))).append("</strong><small>").append(escape(note)).append("</small></div>");
    }

    private void concept(StringBuilder html, String name, Object value, String note, String css) {
        html.append("<div class=\"concept ").append(css).append("\"><span class=\"muted\">").append(escape(name)).append("</span><strong>")
                .append(escape(display(value))).append("</strong><small>").append(escape(note)).append("</small></div>");
    }

    private Map<?, ?> steadyWindow(Map<?, ?> metrics) {
        Object contract = metrics == null ? null : metrics.get("windowMetrics");
        if (!(contract instanceof Map)) return Collections.emptyMap();
        Object windows = ((Map<?, ?>) contract).get("windows");
        if (!(windows instanceof Map)) return Collections.emptyMap();
        return nested((Map<?, ?>) windows, "steady");
    }

    private Map<?, ?> nested(Map<?, ?> source, String key) {
        if (source == null) return Collections.emptyMap();
        Object value = source.get(key);
        return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
    }

    private String count(Object value) { return display(value); }
    private long numericCount(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0L; }
    private String rate(Object value) { return value instanceof Number ? String.format(java.util.Locale.ROOT, "%.3f/s", ((Number) value).doubleValue()) : display(value); }
    private String percent(Object value) { return value instanceof Number ? String.format(java.util.Locale.ROOT, "%.2f%%", ((Number) value).doubleValue() * 100.0) : "N/A"; }
    private String display(Object value) {
        if (value == null) return "-";
        if (value instanceof Map || value instanceof List) return JsonSupport.write(value);
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) return "N/A";
            return java.math.BigDecimal.valueOf(number).setScale(3, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private String escapeAttribute(String value) { return escape(value).replace("'", "&#39;"); }
}
