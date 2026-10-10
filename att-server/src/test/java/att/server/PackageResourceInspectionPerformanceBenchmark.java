package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.management.ManagementFactory;
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

/** Opt-in end-to-end benchmark for the bounded Server-to-Worker resource listing path. */
class PackageResourceInspectionPerformanceBenchmark {
    private static final String[] TYPES = {null, "case", "template", "flow", "tool"};

    @TempDir Path temp;

    @Test void writesPackageResourceDiscoveryLatencyReport() throws Exception {
        Path packageRoot = Path.of(System.getProperty("att.server.inspection.benchmark.package", "."))
                .toRealPath();
        int warmups = integerProperty("att.server.inspection.benchmark.warmups", 1, 0);
        int runs = integerProperty("att.server.inspection.benchmark.runs", 3, 1);
        int pageSize = integerProperty("att.server.inspection.benchmark.pageSize", 100, 1);
        if (pageSize > 100) throw new IllegalArgumentException("pageSize must not exceed 100");

        Path javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        Path serverConfig = temp.resolve("inspection-benchmark-server.yaml");
        Path dataDir = temp.resolve("server-data");
        Files.writeString(serverConfig, "server:\n  dataDir: " + yaml(dataDir)
                + "\n  javaExecutable: " + yaml(javaBin)
                + "\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 120000\n    heapMaxMb: 512"
                + "\nworkers: {}\npackages:\n  allowedRoots:\n    - " + yaml(packageRoot)
                + "\n  entries:\n    benchmark: " + yaml(packageRoot) + "\n");

        Path webInfLib = Files.createDirectory(temp.resolve("WEB-INF-lib"));
        addModuleJar(webInfLib, "att-worker", Path.of("att-worker/target/classes"));
        addModuleJar(webInfLib, "att-engine", Path.of("att-engine/target/classes"));
        copyWorkerDependencies(webInfLib);

        List<Map<String, Object>> samples = new ArrayList<>();
        try (ServerRuntime runtime = new ServerRuntime(ServerConfig.load(serverConfig), webInfLib.toString(), builder -> {
            builder.environment().put("ORDERS_DB_USERNAME", "inspection-benchmark");
            builder.environment().put("ORDERS_DB_PASSWORD", "inspection-benchmark");
            builder.environment().put("PAYMENT_MQ_USERNAME", "inspection-benchmark");
            builder.environment().put("PAYMENT_MQ_PASSWORD", "inspection-benchmark");
            return builder.start();
        })) {
            for (String type : TYPES) {
                for (int sample = 0; sample < warmups + runs; sample++) {
                    long start = System.nanoTime();
                    Map<String, Object> page = runtime.inspectResource("benchmark", "list", type,
                            null, null, pageSize, null, "benchmark");
                    double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
                    if (sample >= warmups) {
                        @SuppressWarnings("unchecked") List<Map<String, Object>> items =
                                (List<Map<String, Object>>) page.get("items");
                        Map<String, Object> measured = new LinkedHashMap<>();
                        measured.put("type", type == null ? "all" : type);
                        measured.put("sample", sample - warmups + 1);
                        measured.put("latencyMs", latencyMs);
                        measured.put("totalResources", page.get("total"));
                        measured.put("returnedResources", items == null ? 0 : items.size());
                        measured.put("hasNextPage", page.get("nextCursor") != null);
                        samples.add(measured);
                    }
                }
            }
        }

        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (String type : TYPES) {
            String name = type == null ? "all" : type;
            List<Double> latencies = samples.stream()
                    .filter(sample -> name.equals(sample.get("type")))
                    .map(sample -> ((Number) sample.get("latencyMs")).doubleValue())
                    .sorted(Comparator.naturalOrder()).toList();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("type", name);
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
        report.put("schemaVersion", "att-package-resource-discovery-benchmark/v1");
        report.put("version", "4.1.0");
        report.put("sourceRevision", System.getProperty("att.server.inspection.benchmark.revision", "unknown"));
        report.put("dataset", "att-repository-test-package");
        report.put("measurementScope", "ServerRuntime.inspectResource through a one-shot Worker; excludes HTTP and Tomcat overhead");
        report.put("environment", environment);
        report.put("settings", Map.of("warmups", warmups, "runs", runs, "pageSize", pageSize,
                "workerHeapMaxMb", 512, "workerTimeoutMs", 120000));
        report.put("scenarios", scenarios);
        report.put("samples", samples);

        Path output = Path.of(System.getProperty("att.server.inspection.benchmark.output",
                "target/issue-176-resource-discovery.json")).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Package resource discovery benchmark written to " + output);
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
            try {
                Files.createSymbolicLink(target, candidate.toAbsolutePath());
            } catch (Exception unsupported) {
                Files.copy(candidate, target);
            }
        }
    }

    private static void addModuleJar(Path lib, String name, Path classes) throws Exception {
        Path target = lib.resolve(name + ".jar");
        try (OutputStream file = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(file);
             var paths = Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, jar);
                    jar.closeEntry();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
        }
    }

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
}
