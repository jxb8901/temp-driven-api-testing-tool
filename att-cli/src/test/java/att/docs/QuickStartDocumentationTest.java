/* Author: Jeffrey + ChatGPT */
package att.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the beginner-first EN/ZH Quick Start introduced by issue #45. */
class QuickStartDocumentationTest {
    @TempDir Path temp;
    private int processCounter;

    @Test
    void quickStartAssetsAndLanguageEntryPointsStayAligned() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path packageRoot = root.resolve("packages/default");

        for (String relative : Arrays.asList(
                "templates/flows/quick-start/flow.yaml",
                "testcase/quick-start/single/testcase.yaml",
                "testcase/quick-start/inline-multi/testcase.yaml",
                "testcase/quick-start/excel/testcase.yaml",
                "testdata/quick-start-native.xlsx",
                "testcase/quick_start.xlsx",
                "testcase/quick_start.yaml",
                "testcase/quick_start.xml",
                "templates/QUICK_START/template.yaml",
                "docs/quick-start.md",
                "docs/quick-start.zh.md",
                "docs/maintenance/native-case-default-package-migration-s12.md")) {
            Path assetRoot = relative.startsWith("docs/") ? root : packageRoot;
            assertTrue(Files.isRegularFile(assetRoot.resolve(relative)), "Missing Quick Start asset: " + relative);
        }

        String en = read(root.resolve("docs/quick-start.md"));
        String zh = read(root.resolve("docs/quick-start.zh.md"));
        String readme = read(root.resolve("README.md"));

        assertTrue(en.contains("(quick-start.zh.md)"), "English Quick Start must link to Chinese");
        assertTrue(zh.contains("(quick-start.md)"), "Chinese Quick Start must link to English");
        assertTrue(en.contains("quick.start.single"));
        assertTrue(zh.contains("quick.start.single"));
        assertTrue(en.contains("run native-case"));
        assertTrue(zh.contains("run native-case"));
        assertTrue(en.contains("report/index.html"));
        assertTrue(zh.contains("report/index.html"));
        assertTrue(en.contains("ci/summary.json"));
        assertTrue(zh.contains("ci/summary.json"));
        assertTrue(en.contains("testcase/quick_start.xlsx"));
        assertTrue(zh.contains("testcase/quick_start.xlsx"));
        assertTrue(en.contains("quickStart.default.QS001"));
        assertTrue(zh.contains("quickStart.default.QS001"));
        assertTrue(readme.contains("docs/quick-start.md"));
        assertTrue(readme.contains("docs/quick-start.zh.md"));
        assertTrue(readme.contains("run native-case quick.start.single"));

        assertLocalMarkdownLinksResolve(root.resolve("docs/quick-start.md"), root);
        assertLocalMarkdownLinksResolve(root.resolve("docs/quick-start.zh.md"), root);
    }

    @Test
    void nativeQuickStartCasesExecuteThroughTheRealCli() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path outputRoot = temp.resolve("native-output");
        Files.createDirectories(outputRoot);

        CliResult dryRun = runCli(root, "--local", "-p", "default", "--format", "json",
                "--output-dir", outputRoot.toString(),
                "--run-id", "native-quick-start-dry",
                "run", "native-case", "quick.start.single",
                "--resolved-case-id", "default", "--dry-run");
        assertEquals(0, dryRun.exitCode, dryRun.stderr + "\n" + dryRun.stdout);
        Map<?, ?> dryResult = parseJson(dryRun.stdout);
        assertEquals("SKIPPED", dryResult.get("status"), dryRun.stdout);
        assertNativeRunArtifacts(outputRoot.resolve("native-quick-start-dry"), false);

        CliResult single = runCli(root, "--local", "-p", "default", "--format", "json",
                "--output-dir", outputRoot.toString(),
                "--run-id", "native-quick-start-single",
                "run", "native-case", "quick.start.single",
                "--resolved-case-id", "default");
        assertEquals(0, single.exitCode, single.stderr + "\n" + single.stdout);
        assertNativeSelection(single.stdout, "PASS", 1, 1,
                Arrays.asList("quick.start.single.default"));
        assertNativeRunArtifacts(outputRoot.resolve("native-quick-start-single"), true);

        CliResult inline = runCli(root, "--local", "-p", "default", "--format", "json",
                "--output-dir", outputRoot.toString(),
                "--run-id", "native-quick-start-inline",
                "run", "native-case", "quick.start.inline",
                "--resolved-case-id", "basic", "--resolved-case-id", "with-tool");
        assertEquals(0, inline.exitCode, inline.stderr + "\n" + inline.stdout);
        assertNativeSelection(inline.stdout, "PASS", 2, 2,
                Arrays.asList("quick.start.inline.basic", "quick.start.inline.with-tool"));
        assertNativeRunArtifacts(outputRoot.resolve("native-quick-start-inline"), true);

        CliResult excel = runCli(root, "--local", "-p", "default", "--format", "json",
                "--output-dir", outputRoot.toString(),
                "--run-id", "native-quick-start-excel",
                "run", "native-case", "quick.start.excel",
                "--resolved-case-id", "QS001", "--resolved-case-id", "QS002");
        assertEquals(0, excel.exitCode, excel.stderr + "\n" + excel.stdout);
        assertNativeSelection(excel.stdout, "PASS", 2, 2,
                Arrays.asList("quick.start.excel.QS001", "quick.start.excel.QS002"));
        assertNativeRunArtifacts(outputRoot.resolve("native-quick-start-excel"), true);
    }

    @Test
    void legacyQuickStartAndPaymentRegressionRemainRunnable() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path packageRoot = root.resolve("packages/default");
        String config = root.resolve("config/config.yaml").toString();
        String suite = packageRoot.resolve("testcase/quick_start.xlsx").toString();

        CliResult basic = runCli(packageRoot, "run", "--config", config,
                "--suite", suite, "--case", "quickStart.default.QS001",
                "--output-dir", temp.resolve("basic-output").toString(),
                "--run-id", "quick-start-basic", "--format", "human", "--profile");
        assertEquals(0, basic.exitCode, basic.stderr + "\n" + basic.stdout);
        assertTrue(basic.stdout.contains("[RUN] id=quick-start-basic"), basic.stdout);
        assertTrue(basic.stdout.contains("[CASE] id=quickStart.default.QS001"), basic.stdout);
        @SuppressWarnings("unchecked") java.util.Map<String,Object> performance = att.validation.JsonSupport.mapper()
                .readValue(temp.resolve("basic-output/quick-start-basic/performance.json").toFile(), java.util.Map.class);
        @SuppressWarnings("unchecked") java.util.Map<String,Object> phases = (java.util.Map<String,Object>) performance.get("phases");
        assertTrue(phases.containsKey("configLoadMs"));
        assertTrue(phases.containsKey("validationMs"));

        CliResult tool = runCli(packageRoot, "run", "--config", config,
                "--suite", suite, "--case", "quickStart.default.QS002",
                "--output-dir", temp.resolve("tool-output").toString(),
                "--run-id", "quick-start-tool", "--format", "json", "--quiet");
        assertEquals(0, tool.exitCode, tool.stderr + "\n" + tool.stdout);

        // Safe offline evidence for the retained multi-Stage fixture: dry-run skips all rows
        // without calling FPP/MQ/DB Tools. Full business execution remains #398 / integration-only.
        CliResult paymentRegression = runCli(packageRoot, "run", "--config", config,
                "--suite", packageRoot.resolve("testcase/payment_regression.xlsx").toString(),
                "--dry-run", "--format", "json", "--quiet",
                "--output-dir", temp.resolve("payment-regression-output").toString(),
                "--run-id", "payment-regression-dry");
        assertEquals(0, paymentRegression.exitCode, paymentRegression.stderr + "\n" + paymentRegression.stdout);
        Map<?, ?> drySummary = parseJson(paymentRegression.stdout);
        assertEquals(Integer.valueOf(22), toInt(drySummary.get("total")), paymentRegression.stdout);
        assertEquals(Integer.valueOf(22), toInt(drySummary.get("skipped")), paymentRegression.stdout);
        assertEquals(Integer.valueOf(0), toInt(drySummary.get("failed")), paymentRegression.stdout);
        assertEquals(Integer.valueOf(0), toInt(drySummary.get("error")), paymentRegression.stdout);
        assertTrue(Files.isRegularFile(temp.resolve("payment-regression-output/payment-regression-dry/report/index.html")),
                "payment_regression dry-run must write HTML report");
    }

    private void assertNativeSelection(String stdout, String status, int total, int passed,
            List<String> qualifiedCaseIds) throws Exception {
        Map<?, ?> result = parseJson(stdout);
        assertEquals(status, result.get("status"), stdout);
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        assertEquals(Integer.valueOf(total), toInt(summary.get("total")), stdout);
        assertEquals(Integer.valueOf(passed), toInt(summary.get("passed")), stdout);
        @SuppressWarnings("unchecked") List<Map<String, Object>> cases = (List<Map<String, Object>>) summary.get("cases");
        assertEquals(qualifiedCaseIds.size(), cases.size(), stdout);
        Map<String, String> byQualified = new LinkedHashMap<String, String>();
        for (Map<String, Object> executed : cases) {
            byQualified.put(String.valueOf(executed.get("qualifiedCaseId")), String.valueOf(executed.get("status")));
            assertTrue(executed.get("execId") != null && !String.valueOf(executed.get("execId")).trim().isEmpty(),
                    "missing execId for " + executed.get("qualifiedCaseId"));
        }
        for (String qualified : qualifiedCaseIds) {
            assertEquals("PASS", byQualified.get(qualified),
                    "missing or non-PASS Case " + qualified + " in " + byQualified);
        }

        Map<?, ?> ciDocument = att.validation.JsonSupport.mapper().readValue(
                Files.newInputStream(temp.resolve("native-output")
                        .resolve(String.valueOf(result.get("executionId")))
                        .resolve("ci/summary.json")),
                Map.class);
        Map<?, ?> ciSummary = (Map<?, ?>) ciDocument.get("summary");
        assertEquals(Integer.valueOf(total), toInt(ciSummary.get("total")), "ci/summary.json total");
        assertEquals(Integer.valueOf(passed), toInt(ciSummary.get("passed")), "ci/summary.json passed");
        @SuppressWarnings("unchecked") List<Map<String, Object>> ciCases =
                (List<Map<String, Object>>) ciDocument.get("cases");
        assertEquals(qualifiedCaseIds.size(), ciCases.size(), "ci/summary.json cases");
        Map<String, String> ciById = new LinkedHashMap<String, String>();
        for (Map<String, Object> executed : ciCases) {
            ciById.put(String.valueOf(executed.get("caseId")), String.valueOf(executed.get("status")));
        }
        for (String qualified : qualifiedCaseIds) {
            assertEquals("PASS", ciById.get(qualified),
                    "missing or non-PASS CI Case " + qualified + " in " + ciById);
        }
    }

    private void assertNativeRunArtifacts(Path runDirectory, boolean requireCaseLogs) throws Exception {
        assertTrue(Files.isDirectory(runDirectory), "missing run directory: " + runDirectory);
        assertTrue(Files.isRegularFile(runDirectory.resolve("run.yaml")), "missing run.yaml");
        assertTrue(Files.isRegularFile(runDirectory.resolve("report/index.html")), "missing report/index.html");
        assertTrue(Files.isRegularFile(runDirectory.resolve("ci/summary.json")), "missing ci/summary.json");
        assertTrue(Files.isRegularFile(runDirectory.resolve("ci/junit.xml")), "missing ci/junit.xml");
        if (requireCaseLogs) {
            Path executions = runDirectory.resolve("executions");
            assertTrue(Files.isDirectory(executions), "missing executions/");
            try (java.util.stream.Stream<Path> children = Files.list(executions)) {
                List<Path> caseLogs = new ArrayList<Path>();
                for (Path child : (Iterable<Path>) children::iterator) {
                    Path log = child.resolve("case.log");
                    if (Files.isRegularFile(log)) {
                        caseLogs.add(log);
                    }
                }
                assertTrue(!caseLogs.isEmpty(), "missing case.log under executions/");
            }
        }
    }

    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private Map<?, ?> parseJson(String text) throws Exception {
        return att.validation.JsonSupport.mapper().readValue(text, Map.class);
    }

    private void assertLocalMarkdownLinksResolve(Path markdown, Path root) throws Exception {
        Pattern pattern = Pattern.compile("(?<!!)\\[[^\\]]+\\]\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(read(markdown));
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (target.startsWith("http://") || target.startsWith("https://") ||
                    target.startsWith("mailto:") || target.startsWith("#")) {
                continue;
            }
            int hash = target.indexOf('#');
            if (hash >= 0) {
                target = target.substring(0, hash);
            }
            if (target.isEmpty()) {
                continue;
            }
            Path resolved = markdown.getParent().resolve(target).normalize();
            assertTrue(resolved.startsWith(root), "Quick Start link escapes repository: " + target);
            assertTrue(Files.exists(resolved), "Broken Quick Start link in " + markdown + ": " + target);
        }
    }

    private CliResult runCli(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "-Datt.home=" + Paths.get("").toAbsolutePath().normalize(),
                "att.FrameworkRunner"));
        command.addAll(Arrays.asList(args));

        Path stdout = temp.resolve("quick-start-cli-" + (++processCounter) + ".stdout");
        Path stderr = temp.resolve("quick-start-cli-" + processCounter + ".stderr");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.environment().put("ATT_HOME", Paths.get("").toAbsolutePath().normalize().toString());
        builder.environment().put("ORDERS_DB_USERNAME", "docs-example-user");
        builder.environment().put("ORDERS_DB_PASSWORD", "docs-example-password");
        builder.environment().put("PAYMENT_MQ_USERNAME", "docs-example-user");
        builder.environment().put("PAYMENT_MQ_PASSWORD", "docs-example-password");
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());

        Process process = builder.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "ATT CLI did not finish: " + Arrays.asList(args));
        return new CliResult(process.exitValue(), read(stdout), read(stderr));
    }

    private String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static final class CliResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CliResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
