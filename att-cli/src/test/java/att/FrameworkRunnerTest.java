/* Author: Jeffrey + ChatGPT */
package att;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkRunnerTest {
    @TempDir Path temp;

    @Test void helpDocumentsCleanAndAllSelection() throws Exception {
        java.lang.reflect.Method help=FrameworkRunner.class.getDeclaredMethod("help"); help.setAccessible(true);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); PrintStream previous=System.out;
        try { System.setOut(new PrintStream(bytes)); help.invoke(null); } finally { System.setOut(previous); }
        String text=bytes.toString("UTF-8"); assertTrue(text.contains("clean")); assertTrue(text.contains("--all")); assertTrue(text.contains("--update-snapshot")); assertTrue(text.contains("att.bat")); assertTrue(text.contains("defaults to --all")); assertTrue(text.contains("stream bounded progress by default")); assertTrue(text.contains("debug template|flow|tool")); assertTrue(text.contains("--overload-policy")); assertTrue(text.contains("load-summary.json|yaml")); assertFalse(text.contains("--single-page"));
    }

    @Test void reportRunIdLoadsConfigWhenOutputDirectoryIsOmitted() throws Exception {
        Files.createDirectories(temp.resolve("templates"));
        Files.createDirectories(temp.resolve("testcase"));
        try (java.util.stream.Stream<Path> paths = Files.walk(Paths.get("schemas"))) {
            for (Path source : (Iterable<Path>) paths::iterator) {
                Path destination = temp.resolve(source);
                if (Files.isDirectory(source)) Files.createDirectories(destination);
                else Files.copy(source, destination);
            }
        }
        Path config = temp.resolve("config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.11\nenvironment: SIT\noutputDirectory: reports\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\ntools: {}\n")
                .getBytes("UTF-8"));
        Process process = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FrameworkRunner.class.getName(), "report",
                "--run-id", "MISSING-RUN", "--config", config.toString())
                .directory(temp.toFile()).start();
        String stdout = read(process.getInputStream());
        String stderr = read(process.getErrorStream());
        assertEquals(2, process.waitFor(), stdout + "\n" + stderr);
        assertFalse(stderr.contains("NullPointerException"), stderr);
        assertTrue(stderr.contains("MISSING-RUN") || stderr.contains("run"), stderr);
    }

    private static String read(InputStream stream) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        return new String(bytes.toByteArray(), "UTF-8");
    }

    @Test void verboseIsAnExplicitOutputModeAndConflictsWithQuiet() {
        CliOptions defaults = CliOptions.parse(new String[]{"run", "--all"});
        assertTrue(defaults.verbose());
        assertFalse(defaults.quiet());
        CliOptions verbose = CliOptions.parse(new String[]{"run", "--all", "--verbose"});
        assertTrue(verbose.verbose());
        assertFalse(verbose.quiet());
        CliOptions quiet = CliOptions.parse(new String[]{"run", "--all", "--quiet"});
        assertTrue(quiet.quiet());
        assertFalse(quiet.verbose());
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[]{"run", "--all", "--verbose", "--quiet"}));
        assertTrue(CliOptions.parse(new String[]{"debug", "template", "X"}).verbose());
        assertTrue(CliOptions.parse(new String[]{"load", "scenario.yaml"}).verbose());
    }

    @Test void snapshotDefaultsToAllWorkbooksAndRejectsRunOnlyOptions() {
        CliOptions implicitAll = CliOptions.parse(new String[]{"snapshot"});
        assertEquals("snapshot", implicitAll.command());
        assertTrue(implicitAll.all());
        assertTrue(CliOptions.parse(new String[]{"snapshot", "--all"}).all());
        assertEquals(1, CliOptions.parse(new String[]{"snapshot", "--suite", "testcase/payment.xlsx"}).suitePaths().size());
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[]{"snapshot", "--all", "--tag", "smoke"}));
        CliOptions update = CliOptions.parse(new String[]{"run", "--all", "--update-snapshot"});
        assertTrue(update.updateSnapshot());
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[]{"validate", "--package", "--update-snapshot"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[]{"snapshot", "--all", "--update-snapshot"}));
    }

    @Test void typedDiagnosticKeepsRunCodeIndependentFromMessageText() {
        att.validation.DiagnosticException error = new att.validation.DiagnosticException(
                att.validation.DiagnosticCodes.RUN_FAILED, "Run ID already exists",
                "path=/tmp/api-testing-tool/output/X", null, "runId", null, null, null, null, null,
                "Choose another --run-id.", null);
        assertEquals("ATT-RUN-001", error.code());
        assertTrue(error.format().contains("suggestion: Choose another --run-id."));
    }

    @Test void runJsonKeepsTheLegacyFlatCliFieldLayout() throws Exception {
        java.util.Map<String,Object> summary = new java.util.LinkedHashMap<String,Object>();
        summary.put("total", 1); summary.put("passed", 1); summary.put("failed", 0);
        summary.put("error", 0); summary.put("skipped", 0); summary.put("invalid", 0);
        att.api.RunResult result = new att.api.RunResult("run-1", "PASS", 0, 10,
                java.util.Collections.<att.validation.Diagnostic>emptyList(),
                java.util.Collections.singletonMap("report", "/tmp/att/output/run-1/report/index.html"), summary);
        java.lang.reflect.Method method = FrameworkRunner.class.getDeclaredMethod("runJson", att.api.RunResult.class, java.nio.file.Path.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.Map<String,Object> json = att.validation.JsonSupport.mapper()
                .readValue((String) method.invoke(null, result, java.nio.file.Paths.get("/tmp/att")), java.util.Map.class);
        assertEquals(java.util.Arrays.asList("total", "passed", "failed", "error", "skipped", "invalid", "report"),
                new java.util.ArrayList<String>(json.keySet()));
        assertEquals(1L, ((Number) json.get("total")).longValue());
        assertEquals("$ATT_HOME/output/run-1/report/index.html", json.get("report"));
    }

    @Test void humanValidationDiagnosticsAreIndentedAndSeparated() throws Exception {
        java.util.List<att.validation.Diagnostic> diagnostics = java.util.Arrays.asList(
                new att.validation.Diagnostic("ATT-CTX-001", att.validation.Diagnostic.Severity.ERROR,
                        "Unknown Context\nrequestedPath: missing", "template.yaml", "actions.verify.actual", null, null, null, "VERIFY", "verify", "Use the Context tree."),
                new att.validation.Diagnostic("ATT-TPL-001", att.validation.Diagnostic.Severity.ERROR,
                        "Missing assertion", "template.yaml", "actions.verify.assert", null, null, null, "VERIFY", "verify", "Add an assertion."));
        att.validation.PackageValidator.ValidationSummary summary = new att.validation.PackageValidator.ValidationSummary("package", 1, 1, 1, 0, diagnostics);
        CliOptions options = CliOptions.parse(new String[]{"validate", "--package"});
        java.lang.reflect.Method method = FrameworkRunner.class.getDeclaredMethod("printDiagnostics",
                att.validation.PackageValidator.ValidationSummary.class, CliOptions.class, PrintStream.class, boolean.class);
        method.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        method.invoke(null, summary, options, new PrintStream(bytes), true);
        assertEquals("\n  [ERROR] ATT-CTX-001: Unknown Context\n"
                        + "    requestedPath: missing\n"
                        + "    location: file=template.yaml, field=actions.verify.actual, template=VERIFY, action=verify\n"
                        + "    suggestion: Use the Context tree.\n\n"
                        + "  [ERROR] ATT-TPL-001: Missing assertion\n"
                        + "    location: file=template.yaml, field=actions.verify.assert, template=VERIFY, action=verify\n"
                        + "    suggestion: Add an assertion.\n",
                bytes.toString("UTF-8"));
    }

    @Test void quietValidationKeepsErrorsButSuppressesInformationalDiagnostics() throws Exception {
        java.util.List<att.validation.Diagnostic> diagnostics = java.util.Arrays.asList(
                new att.validation.Diagnostic("ATT-TPL-001", att.validation.Diagnostic.Severity.ERROR,
                        "Action failed", "template.yaml", "actions.call", null, null, null, "PAYMENT", "call", "Inspect case.log."),
                new att.validation.Diagnostic("ATT-INFO-001", att.validation.Diagnostic.Severity.INFO,
                        "Optional dependency", "template.yaml", "actions.call", null, null, null, "PAYMENT", "call", "No action required."));
        att.validation.PackageValidator.ValidationSummary summary = new att.validation.PackageValidator.ValidationSummary("package", 1, 1, 1, 0, diagnostics);
        CliOptions options = CliOptions.parse(new String[]{"validate", "--package", "--quiet"});
        java.lang.reflect.Method method = FrameworkRunner.class.getDeclaredMethod("printDiagnostics",
                att.validation.PackageValidator.ValidationSummary.class, CliOptions.class, PrintStream.class, boolean.class);
        method.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        method.invoke(null, summary, options, new PrintStream(bytes), false);
        String output = bytes.toString("UTF-8");
        assertTrue(output.contains("ATT-TPL-001"));
        assertFalse(output.contains("ATT-INFO-001"));
    }
}
