package att.server;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, end-to-end ServerRuntime benchmark for the inline Debug draft pipeline. */
class DebugDraftPipelinePerformanceBenchmark {
    @TempDir Path temp;

    @Test void writesMultiWorkbookDebugDraftPipelineBenchmarkJson() throws Exception {
        assumeTrue(Boolean.getBoolean("att.server.benchmark.debugDraft"), "opt-in local benchmark");
        int workbookCount = integerProperty("att.server.benchmark.debugDraft.workbooks", 5, 1);
        int casesPerWorkbook = integerProperty("att.server.benchmark.debugDraft.casesPerWorkbook", 300, 1);
        int runs = integerProperty("att.server.benchmark.debugDraft.runs", 3, 1);
        Path packageRoots = Files.createDirectories(temp.resolve("packages"));
        Path packageRoot = Files.createDirectories(packageRoots.resolve("benchmark"));
        installPackage(packageRoot, workbookCount, casesPerWorkbook);

        Path javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        Path serverYaml = temp.resolve("server.yaml");
        Path dataDir = temp.resolve("server-data");
        Files.writeString(serverYaml,
                "server:\n  dataDir: " + yaml(dataDir) + "\n  javaExecutable: " + yaml(javaBin)
                        + "\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 60000\n    heapMaxMb: 512\n"
                        + "workers:\n  maxConcurrent: 1\n  queuedLimit: 2\n  maxConcurrentLoad: 1\n"
                        + "packages:\n  allowedRoots:\n    - " + yaml(packageRoots) + "\n  entries:\n    benchmark: " + yaml(packageRoot) + "\n",
                StandardCharsets.UTF_8);
        Path webInfLib = Files.createDirectory(temp.resolve("WEB-INF-lib"));
        addModuleJar(webInfLib, "att-worker", Path.of("att-worker/target/classes"));
        addModuleJar(webInfLib, "att-engine", Path.of("att-engine/target/classes"));
        addModuleJar(webInfLib, "att-server-api", Path.of("att-server-api/target/classes"));
        addRuntimeJars(webInfLib);

        ServerRuntime runtime = new ServerRuntime(ServerConfig.load(serverYaml), webInfLib.toString());
        try {
            Map<String, Object> list = runtime.inspectResource("benchmark", "list", "template", null, null, 10, null, "benchmark-user");
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
            Map<String, Object> target = items.stream()
                    .filter(item -> "FORM".equals(item.get("logicalId")))
                    .findFirst().orElseThrow(AssertionError::new);
            String resourceId = String.valueOf(target.get("resourceId"));
            List<Map<String, Object>> samples = new ArrayList<>();
            for (int sampleIndex = 0; sampleIndex < runs; sampleIndex++) {
                samples.add(measurePipeline(runtime, resourceId, sampleIndex));
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", "att-server-debug-draft-pipeline-benchmark/v1");
            report.put("runtime", Map.of("attVersion", att.Version.PRODUCT,
                    "javaVersion", System.getProperty("java.version"),
                    "os", System.getProperty("os.name"),
                    "architecture", System.getProperty("os.arch"),
                    "availableProcessors", Runtime.getRuntime().availableProcessors()));
            report.put("fixture", Map.of("workbookCount", workbookCount,
                    "casesPerWorkbook", casesPerWorkbook,
                    "totalCases", workbookCount * casesPerWorkbook,
                    "target", "template:FORM"));
            report.put("settings", Map.of("runs", runs, "warmups", 0,
                    "inspectionWorkerConcurrency", 1, "measurement", "ServerRuntime API pipeline; no browser rendering"));
            report.put("samples", samples);

            Path output = Path.of(System.getProperty("att.server.benchmark.debugDraft.output",
                    "att-server/target/debug-draft-pipeline-benchmark.json")).toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            Files.writeString(output, ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
            System.out.println("Debug draft pipeline benchmark written to " + output);
        } finally {
            runtime.close();
        }
    }

    private Map<String, Object> measurePipeline(ServerRuntime runtime, String resourceId, int sampleIndex) throws Exception {
        long pipelineStarted = System.nanoTime();
        long started = System.nanoTime();
        Map<String, Object> form = runtime.inspectDebugForm("benchmark", "template", resourceId, null, "benchmark-user");
        double formMs = millisSince(started);

        com.fasterxml.jackson.databind.node.ObjectNode request = ServerRuntime.JSON.createObjectNode();
        request.put("packageId", "benchmark");
        request.set("target", ServerRuntime.JSON.valueToTree(Map.of("type", "template", "id", "FORM")));
        request.set("input", ServerRuntime.JSON.valueToTree(form.get("input")));
        started = System.nanoTime();
        Map<String, Object> draft = runtime.createDebugDraft(request, "benchmark-user");
        double createMs = millisSince(started);
        long draftCreatedAt = System.nanoTime();
        String draftId = String.valueOf(draft.get("draftId"));

        started = System.nanoTime();
        Map<String, Object> preview = runtime.getDebugDraft(draftId, "benchmark-user");
        double previewMs = millisSince(started);
        assertEquals(draftId, preview.get("draftId"));

        com.fasterxml.jackson.databind.node.ObjectNode submit = ServerRuntime.JSON.createObjectNode();
        submit.put("packageId", "benchmark");
        submit.put("draftId", draftId);
        started = System.nanoTime();
        long submitStartedAt = started;
        Map<String, Object> accepted = runtime.submitDebugDraft(submit, "benchmark-user");
        double submitMs = millisSince(started);
        String jobId = String.valueOf(accepted.get("jobId"));

        long firstEventDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<Map<String, Object>> events = runtime.events(jobId).after(0);
        while (events.isEmpty() && System.nanoTime() < firstEventDeadline) {
            Thread.sleep(2);
            events = runtime.events(jobId).after(0);
        }
        assertTrue(!events.isEmpty(), "Accepted Debug job should publish its first event");
        long firstEventAt = System.nanoTime();

        long jobDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Map<String, Object> record = runtime.jobRecord(jobId);
        while (!List.of("PASS", "FAIL", "ERROR", "INVALID", "CANCELLED").contains(record.get("status"))
                && System.nanoTime() < jobDeadline) {
            Thread.sleep(10);
            record = runtime.jobRecord(jobId);
        }
        assertTrue(List.of("PASS", "FAIL", "ERROR", "INVALID", "CANCELLED").contains(record.get("status")),
                "Benchmark Debug Worker should finish: " + record);
        assertEquals("PASS", record.get("status"), "Benchmark Debug target should pass");
        long completedAt = System.nanoTime();

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("sample", sampleIndex);
        sample.put("status", record.get("status"));
        sample.put("formMs", formMs);
        sample.put("draftCreateMs", createMs);
        sample.put("previewMs", previewMs);
        sample.put("submitMs", submitMs);
        sample.put("submitToFirstEventMs", millisBetween(submitStartedAt, firstEventAt));
        sample.put("draftCreateToFirstEventMs", millisBetween(draftCreatedAt, firstEventAt));
        sample.put("wholePipelineToFirstEventMs", millisBetween(pipelineStarted, firstEventAt));
        sample.put("firstEventToCompletionMs", millisBetween(firstEventAt, completedAt));
        sample.put("firstEvent", events.get(0).get("event"));
        return sample;
    }

    private void installPackage(Path root, int workbookCount, int casesPerWorkbook) throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("templates/FORM"));
        copySchemas(Path.of("schemas"), root.resolve("schemas"));
        Files.writeString(root.resolve("config/config.yaml"),
                "schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                        + "testcase: {root: testcase}\ntemplates: {root: templates}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("templates/FORM/template.yaml"),
                "schemaVersion: att-template/v3.6\nname: FORM\ndescription: Multi-workbook draft benchmark\nactions:\n"
                        + "  log:\n    type: log\n    message: \"${EXEC.INPUT.value}\"\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("templates/FORM/debug.yaml"),
                "schemaVersion: att-debug/v1.2\ninputs:\n  value: benchmark\n", StandardCharsets.UTF_8);
        for (int workbookIndex = 0; workbookIndex < workbookCount; workbookIndex++) {
            String suiteName = String.format(Locale.ROOT, "suite-%02d", workbookIndex + 1);
            Files.writeString(root.resolve("testcase/" + suiteName + ".yaml"),
                    "schemaVersion: att-sidecar/v2.2\nid: " + suiteName + "\nexcel:\n  sheet: Cases\n"
                            + "  caseId: Case ID\n  tags: Tags\nstages:\n  - key: invoke\n    template: Template\n",
                    StandardCharsets.UTF_8);
            Path workbookPath = root.resolve("testcase/" + suiteName + ".xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(workbookPath)) {
                Sheet sheet = workbook.createSheet("Cases");
                Row header = sheet.createRow(0);
                String[] columns = {"Case ID", "Tags", "Template"};
                for (int column = 0; column < columns.length; column++) header.createCell(column).setCellValue(columns[column]);
                for (int index = 0; index < casesPerWorkbook; index++) {
                    Row row = sheet.createRow(index + 1);
                    row.createCell(0).setCellValue(String.format(Locale.ROOT, "S%02dC%05d", workbookIndex + 1, index + 1));
                    row.createCell(1).setCellValue("benchmark");
                    row.createCell(2).setCellValue("FORM");
                }
                workbook.write(output);
            }
        }
    }

    private void copySchemas(Path source, Path destination) throws Exception {
        source = source.toRealPath();
        try (var paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private void addRuntimeJars(Path lib) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String element : classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate = Path.of(element);
            if (!Files.isRegularFile(candidate) || !candidate.toString().endsWith(".jar")) continue;
            String name = candidate.getFileName().toString();
            if (name.startsWith("att-worker-") || name.startsWith("att-engine-") || name.startsWith("att-server-api-")) continue;
            Path target = lib.resolve(name);
            if (!Files.exists(target)) {
                try { Files.createSymbolicLink(target, candidate); }
                catch (Exception unsupported) { Files.copy(candidate, target); }
            }
        }
    }

    private void addModuleJar(Path lib, String name, Path classes) throws Exception {
        Path target = lib.resolve(name + ".jar");
        try (OutputStream file = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(file);
             var paths = Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, jar);
                    jar.closeEntry();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
        }
    }

    private int integerProperty(String name, int fallback, int minimum) {
        int value = Integer.getInteger(name, fallback);
        if (value < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum);
        return value;
    }

    private double millisSince(long startedNanos) { return millisBetween(startedNanos, System.nanoTime()); }
    private double millisBetween(long startedNanos, long finishedNanos) { return (finishedNanos - startedNanos) / 1_000_000.0; }
    private String yaml(Path path) { return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'"; }
}
