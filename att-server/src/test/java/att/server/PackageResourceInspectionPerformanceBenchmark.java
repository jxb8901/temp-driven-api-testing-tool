package att.server;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Opt-in latency benchmark for warm and cold resource discovery on a generated multi-workbook package. */
class PackageResourceInspectionPerformanceBenchmark {
    @TempDir Path temp;

    @Test void writesLargePackageResourceDiscoveryLatencyReport() throws Exception {
        int warmups = integerProperty("att.server.inspection.benchmark.warmups", 1, 0);
        int runs = integerProperty("att.server.inspection.benchmark.runs", 5, 1);
        int pageSize = integerProperty("att.server.inspection.benchmark.pageSize", 50, 1);
        int workbookCount = integerProperty("att.server.inspection.benchmark.workbooks", 5, 1);
        int casesPerWorkbook = integerProperty("att.server.inspection.benchmark.casesPerWorkbook", 300, 1);
        int templateCount = integerProperty("att.server.inspection.benchmark.templates", 200, 2);
        if (pageSize > 100) throw new IllegalArgumentException("pageSize must not exceed 100");

        Path packageRoot = createRepresentativePackage(temp.resolve("large-package"),
                workbookCount, casesPerWorkbook, templateCount);
        Path javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        Path serverConfig = temp.resolve("inspection-benchmark-server.yaml");
        Path dataDir = temp.resolve("server-data");
        Files.writeString(serverConfig, "server:\n  dataDir: " + yaml(dataDir)
                + "\n  javaExecutable: " + yaml(javaBin)
                + "\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 120000\n    heapMaxMb: 512"
                + "\n    safeTextSources:\n      benchmark:\n        - tools/benchmark.sh"
                + "\nworkers: {}\npackages:\n  allowedRoots:\n    - " + yaml(packageRoot)
                + "\n  entries:\n    benchmark: " + yaml(packageRoot) + "\n", StandardCharsets.UTF_8);

        Path webInfLib = Files.createDirectory(temp.resolve("WEB-INF-lib"));
        addModuleJar(webInfLib, "att-worker", Path.of("att-worker/target/classes"));
        addModuleJar(webInfLib, "att-engine", Path.of("att-engine/target/classes"));
        copyWorkerDependencies(webInfLib);

        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, Object> coldIndex = new LinkedHashMap<>();
        try (ServerRuntime runtime = new ServerRuntime(ServerConfig.load(serverConfig), webInfLib.toString(), builder -> {
            builder.environment().put("ORDERS_DB_USERNAME", "inspection-benchmark");
            builder.environment().put("ORDERS_DB_PASSWORD", "inspection-benchmark");
            builder.environment().put("PAYMENT_MQ_USERNAME", "inspection-benchmark");
            builder.environment().put("PAYMENT_MQ_PASSWORD", "inspection-benchmark");
            return builder.start();
        })) {
            long coldStart = System.nanoTime();
            Map<String, Object> caseFirstPage = list(runtime, "case", pageSize, null);
            coldIndex.put("latencyMs", elapsedMs(coldStart));
            coldIndex.put("totalResources", caseFirstPage.get("total"));
            coldIndex.put("returnedResources", itemCount(caseFirstPage));

            String caseCursor = cursor(caseFirstPage);
            String templateCursor = cursor(list(runtime, "template", pageSize, null));
            String caseId = firstResourceId(caseFirstPage);
            Map<String, Object> firstTemplates = list(runtime, "template", pageSize, null);
            String templateId = firstResourceId(firstTemplates);
            String toolId = firstResourceId(list(runtime, "tool", pageSize, null));

            measure(samples, "case-first-page", warmups, runs, () -> list(runtime, "case", pageSize, null));
            measure(samples, "case-page-n", warmups, runs, () -> list(runtime, "case", pageSize, caseCursor));
            measure(samples, "case-detail", warmups, runs, () ->
                    runtime.inspectResource("benchmark", "detail", "case", caseId, null, pageSize, null, "benchmark"));
            measure(samples, "template-first-page", warmups, runs, () -> list(runtime, "template", pageSize, null));
            measure(samples, "template-page-n", warmups, runs, () -> list(runtime, "template", pageSize, templateCursor));
            measure(samples, "template-detail", warmups, runs, () ->
                    runtime.inspectResource("benchmark", "detail", "template", templateId, null, pageSize, null, "benchmark"));
            measure(samples, "tool-source", warmups, runs, () ->
                    runtime.inspectResource("benchmark", "source", "tool", toolId, null, pageSize, null, "benchmark"));
        }

        List<Map<String, Object>> scenarios = new ArrayList<>();
        List<String> names = List.of("case-first-page", "case-page-n", "case-detail", "template-first-page",
                "template-page-n", "template-detail", "tool-source");
        for (String name : names) {
            List<Double> latencies = samples.stream()
                    .filter(sample -> name.equals(sample.get("scenario")))
                    .map(sample -> ((Number) sample.get("latencyMs")).doubleValue())
                    .sorted(Comparator.naturalOrder()).toList();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("scenario", name);
            summary.put("measuredRuns", latencies.size());
            summary.put("p50LatencyMs", percentile(latencies, 0.50));
            summary.put("p95LatencyMs", percentile(latencies, 0.95));
            scenarios.add(summary);
        }

        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("javaVm", System.getProperty("java.vm.name"));
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        environment.put("liveThreads", ManagementFactory.getThreadMXBean().getThreadCount());
        environment.put("capturedAt", Instant.now().toString());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "att-package-resource-discovery-benchmark/v2");
        report.put("version", "4.1.0");
        report.put("sourceRevision", System.getProperty("att.server.inspection.benchmark.revision", "unknown"));
        report.put("dataset", "synthetic multi-workbook package");
        report.put("measurementScope", "ServerRuntime.inspectResource through a persistent bounded inspection Worker; excludes HTTP and Tomcat overhead");
        report.put("environment", environment);
        report.put("settings", Map.of("warmups", warmups, "runs", runs, "pageSize", pageSize,
                "workbooks", workbookCount, "casesPerWorkbook", casesPerWorkbook,
                "totalCases", workbookCount * casesPerWorkbook, "templates", templateCount,
                "workerHeapMaxMb", 512, "workerTimeoutMs", 120000));
        report.put("coldIndexBuild", coldIndex);
        report.put("scenarios", scenarios);
        report.put("samples", samples);

        Path output = Path.of(System.getProperty("att.server.inspection.benchmark.output",
                "target/issue-176-resource-discovery-large.json")).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Large package resource discovery benchmark written to " + output);
    }

    private static Map<String, Object> list(ServerRuntime runtime, String type, int pageSize, String cursor) throws Exception {
        return runtime.inspectResource("benchmark", "list", type, null, null, pageSize, cursor, "benchmark");
    }

    private static void measure(List<Map<String, Object>> samples, String scenario, int warmups, int runs,
                                InspectionCall call) throws Exception {
        for (int sample = 0; sample < warmups + runs; sample++) {
            long start = System.nanoTime();
            Map<String, Object> response = call.run();
            double latency = elapsedMs(start);
            if (sample < warmups) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scenario", scenario);
            result.put("sample", sample - warmups + 1);
            result.put("latencyMs", latency);
            result.put("totalResources", response.get("total"));
            result.put("returnedResources", itemCount(response));
            result.put("hasNextPage", response.get("nextCursor") != null);
            samples.add(result);
        }
    }

    @SuppressWarnings("unchecked")
    private static String cursor(Map<String, Object> page) {
        Object value = page.get("nextCursor");
        if (!(value instanceof String)) throw new IllegalStateException("Synthetic package must have multiple pages: total="
                + page.get("total") + ", items=" + page.get("items") + ", diagnostics=" + page.get("diagnostics"));
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static String firstResourceId(Map<String, Object> page) {
        Object items = page.get("items");
        if (!(items instanceof List) || ((List<?>) items).isEmpty()) throw new IllegalStateException("Synthetic package has no resources");
        Object value = ((Map<String, Object>) ((List<?>) items).get(0)).get("resourceId");
        if (!(value instanceof String)) throw new IllegalStateException("Synthetic resource has no ID");
        return (String) value;
    }

    private static int itemCount(Map<String, Object> response) {
        Object items = response.get("items");
        return items instanceof List ? ((List<?>) items).size() : 0;
    }

    private Path createRepresentativePackage(Path root, int workbookCount, int casesPerWorkbook, int templateCount) throws Exception {
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates"));
        copySchemas(root);
        writeUtf8(root.resolve("config/config.yaml"), "schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                + "testcase:\n  root: testcase\ntemplates:\n  root: templates\ntools:\n  benchmarkEcho:\n"
                + "    name: Benchmark Echo\n    description: Resource source benchmark\n    command: [./tools/benchmark.sh]\n"
                + "    stdoutFormat: text\n    arguments: {}\n");
        writeUtf8(root.resolve("tools/benchmark.sh"), "#!/bin/sh\nprintf '%s\\n' benchmark\n");
        for (int i = 0; i < templateCount; i++) {
            String name = String.format(java.util.Locale.ROOT, "TARGET_%03d", i);
            Path descriptor = Files.createDirectories(root.resolve("templates").resolve(name)).resolve("template.yaml");
            writeUtf8(descriptor, "schemaVersion: att-template/v3.6\nname: " + name
                    + "\ndescription: Representative discovery benchmark resource\nactions:\n  note:\n    type: log\n    message: safe\n");
        }
        for (int workbook = 0; workbook < workbookCount; workbook++) {
            String suite = String.format(java.util.Locale.ROOT, "cases-%02d", workbook);
            Path suiteFile = root.resolve("testcase").resolve(suite + ".xlsx");
            writeUtf8(root.resolve("testcase").resolve(suite + ".yaml"), "schemaVersion: att-sidecar/v2.2\nid: suite_" + workbook
                    + "\nexcel:\n  sheet: Cases\n  caseId: Case ID\n  tags: Tags\n  dataColumns: payload=Payload\nstages:\n"
                    + "  - key: invoke\n    template: Template\n    dataColumns: request=Request\n");
            try (Workbook outputWorkbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(suiteFile)) {
                Sheet sheet = outputWorkbook.createSheet("Cases");
                Row header = sheet.createRow(0);
                String[] columns = {"Case ID", "Tags", "Payload", "Template", "Request"};
                for (int column = 0; column < columns.length; column++) header.createCell(column).setCellValue(columns[column]);
                for (int rowIndex = 0; rowIndex < casesPerWorkbook; rowIndex++) {
                    Row row = sheet.createRow(rowIndex + 1);
                    row.createCell(0).setCellValue(String.format(java.util.Locale.ROOT, "CASE_%02d_%04d", workbook, rowIndex));
                    row.createCell(1).setCellValue("benchmark");
                    row.createCell(2).setCellValue("payload-" + rowIndex);
                    row.createCell(3).setCellValue("TARGET_000");
                    row.createCell(4).setCellValue("request-" + rowIndex);
                }
                outputWorkbook.write(output);
            }
        }
        return root;
    }

    private void copySchemas(Path root) throws Exception {
        Path source = Path.of("schemas").toAbsolutePath().normalize();
        try (var paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = root.resolve("schemas").resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private void copyWorkerDependencies(Path webInfLib) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String element : classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate = Path.of(element);
            if (!Files.isRegularFile(candidate) || !candidate.toString().endsWith(".jar")
                    || candidate.getFileName().toString().startsWith("att-worker-")
                    || candidate.getFileName().toString().startsWith("att-engine-")) continue;
            Path target = webInfLib.resolve(candidate.getFileName());
            if (Files.exists(target)) continue;
            try { Files.createSymbolicLink(target, candidate.toAbsolutePath()); }
            catch (Exception unsupported) { Files.copy(candidate, target); }
        }
    }

    private static void addModuleJar(Path lib, String name, Path classes) throws Exception {
        Path target = lib.resolve(name + ".jar");
        try (OutputStream file = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(file);
             var paths = Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, jar);
                    jar.closeEntry();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
        }
    }

    private static Path writeUtf8(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static double elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1));
    }

    private static int integerProperty(String name, int fallback, int minimum) {
        int value = Integer.getInteger(name, fallback);
        if (value < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum);
        return value;
    }

    private static String yaml(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    @FunctionalInterface private interface InspectionCall { Map<String, Object> run() throws Exception; }
}
