/* Author: Jeffrey + ChatGPT */
package att.docs;

import att.TestSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inventory, packaging inputs, and selection boundaries for #427. */
class ExampleInventoryContractTest {
    @TempDir Path temp;

    @Test
    void inventoryClassifiesCurrentPlannedLegacyAndBlockedFiles() throws Exception {
        Path root = repoRoot();
        Map<String, Object> inventory = att.validation.JsonSupport.mapper().readValue(
                read(root.resolve("docs/example-inventory.json")), Map.class);
        assertEquals("att-example-inventory/v1", inventory.get("schemaVersion"));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) inventory.get("entries");
        Set<String> covered = new LinkedHashSet<String>();
        Set<String> ids = new LinkedHashSet<String>();
        boolean executable = false;
        boolean planned = false;
        for (Map<String, Object> entry : entries) {
            String id = String.valueOf(entry.get("id"));
            String path = String.valueOf(entry.get("path"));
            String status = String.valueOf(entry.get("status"));
            assertTrue(ids.add(id), "duplicate id " + id);
            assertTrue(Files.isRegularFile(root.resolve(path)), path);
            assertTrue(Arrays.asList("current", "planned", "legacy", "blocked").contains(status), status);
            covered.add(path);
            if ("legacy".equals(status)) {
                assertTrue(path.startsWith("examples/legacy/") || path.startsWith("examples/history/"), path);
            }
            if ("blocked".equals(status)) {
                assertTrue(path.startsWith("packages/default/"), path);
                assertFalse(Boolean.TRUE.equals(entry.get("executable")));
            }
            if ("current".equals(status)) {
                assertFalse(path.startsWith("examples/legacy/"), path);
            }
            if (Boolean.TRUE.equals(entry.get("executable"))) {
                assertEquals("current", status);
                assertEquals("offline", entry.get("environment"));
                assertEquals("native-suite", entry.get("kind"));
                executable = true;
            }
            if ("planned".equals(status)) {
                planned = true;
                assertFalse(Boolean.TRUE.equals(entry.get("executable")));
            }
        }
        assertTrue(executable, "current offline Native Suite must be executable");
        assertTrue(planned, "planned work must stay in the inventory and out of the executable set");
        List<String> missing = new ArrayList<String>();
        for (String base : Arrays.asList("packages/default/templates", "packages/default/testcase",
                "packages/default/testdata", "packages/default/tools", "examples")) {
            for (Path file : (Iterable<Path>) Files.walk(root.resolve(base))::iterator) {
                if (Files.isRegularFile(file)) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (!covered.contains(relative)) missing.add(relative);
                }
            }
        }
        assertTrue(missing.isEmpty(), "unclassified files: " + missing);
        String loadReadme = read(root.resolve("examples/load/README.md"));
        assertFalse(loadReadme.contains("Register `source.json`"));
        assertTrue(loadReadme.contains("examples/package-local-sut-metrics"));
        assertTrue(loadReadme.contains("examples/legacy/sut-metrics"));
        String matrix = read(root.resolve("docs/maintenance/example-migration-matrix.md"));
        assertTrue(matrix.contains("packages/default/testcase/payment_regression.xlsx"));
        assertTrue(matrix.contains("examples/legacy/sut-metrics/source.json"));
        assertTrue(matrix.contains("packages/legacy"));
    }

    @Test
    void currentSuiteExecutionWritesTheStandardWorkbookWithoutMutatingInput() throws Exception {
        Path workbook = repoRoot().resolve("packages/default/testdata/payments-offline-settle.xlsx");
        String before = sha256(workbook);
        Path output = temp.resolve("inventory-run");
        CliResult result = cli(repoRoot(), Arrays.asList(
                "--local", "-p", "default", "--format", "json",
                "--output-dir", output.toString(), "--run-id", "inventory-suite",
                "run", "native-suite",
                "--definition", "payments.offline.authorize",
                "--definition", "payments.offline.settle",
                "--qualified-case-id", "payments.offline.authorize.auth-accepted",
                "--qualified-case-id", "payments.offline.settle.SETTLE-OK"));
        assertEquals(0, result.exitCode, result.stderr + "\n" + result.stdout);
        assertTrue(result.stdout.contains("payments.offline.authorize.auth-accepted"));
        assertTrue(result.stdout.contains("payments.offline.settle.SETTLE-OK"));
        assertTrue(Files.isRegularFile(output.resolve("inventory-suite/workbooks/native-cases.result.xlsx")));
        assertTrue(Files.isRegularFile(output.resolve("inventory-suite/report/index.html")));
        assertTrue(Files.isRegularFile(output.resolve("inventory-suite/ci/junit.xml")));
        assertEquals(before, sha256(workbook));
    }

    @Test
    void missingDuplicateEmptyAndUnsafeSelectionsFailClosed() throws Exception {
        CliResult missing = cli(repoRoot(), Arrays.asList(
                "--local", "-p", "default", "--format", "json",
                "--output-dir", temp.resolve("missing").toString(), "--run-id", "missing-id",
                "run", "native-case", "payments.offline.authorize",
                "--resolved-case-id", "missing-case"));
        assertNotEquals(0, missing.exitCode);
        assertTrue((missing.stderr + missing.stdout).contains("unknown Qualified Case ID"), missing.stderr);

        CliResult duplicateFlag = cli(repoRoot(), Arrays.asList(
                "--local", "-p", "default",
                "run", "native-case", "payments.offline.authorize",
                "--resolved-case-id", "auth-accepted",
                "--resolved-case-id", "auth-accepted"));
        assertEquals(2, duplicateFlag.exitCode);
        assertTrue(duplicateFlag.stderr.contains("cannot contain duplicates"), duplicateFlag.stderr);

        CliResult empty = cli(repoRoot(), Arrays.asList(
                "--local", "-p", "default", "--format", "json",
                "--output-dir", temp.resolve("empty").toString(), "--run-id", "empty-selection",
                "run", "native-suite",
                "--definition", "payments.offline.authorize",
                "--definition", "payments.offline.settle",
                "--tag", "not-a-real-tag"));
        assertNotEquals(0, empty.exitCode);
        assertTrue((empty.stderr + empty.stdout).contains("Case selection is empty"), empty.stderr + empty.stdout);

        Path home = temp.resolve("boundary-home");
        Path pkg = home.resolve("packages/boundary");
        Files.createDirectories(pkg.resolve("testcase"));
        TestSchemas.install(home);
        Files.createDirectories(home.resolve("config"));
        Files.write(home.resolve("config/config.yaml"),
                ("schemaVersion: att-config/v2.13\noutputDirectory: output\nenvironment: SIT\n"
                        + "templates: {root: templates}\ntestcase: {root: testcase}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(pkg.resolve("package.yaml"), "schemaVersion: att-package/v1\n".getBytes(StandardCharsets.UTF_8));
        Path flow = pkg.resolve("templates/flows/boundary/flow.yaml");
        Files.createDirectories(flow.getParent());
        Files.write(flow, ("schemaVersion: att-flow/v3.6\nid: boundary.flow.v1\nname: Boundary\n"
                + "description: Offline boundary fixture\nactions:\n  show:\n    type: log\n    message: boundary\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(pkg.resolve("testcase/duplicate"));
        Files.createDirectories(pkg.resolve("testcase/unsafe"));
        Files.write(pkg.resolve("testcase/duplicate/testcase.yaml"),
                ("schemaVersion: att-testcase/v1\nid: boundary.duplicate\nflow: boundary.flow.v1\n"
                        + "caseIdExpression: \"${DATA.caseKey}\"\ndataSource:\n  type: inline\n  records:\n"
                        + "    - recordId: one\n      data: {caseKey: same}\n"
                        + "    - recordId: two\n      data: {caseKey: same}\n").getBytes(StandardCharsets.UTF_8));
        CliResult duplicateIds = cli(home, Arrays.asList(
                "--local", "-p", "boundary", "--format", "json",
                "--config", home.resolve("config/config.yaml").toString(),
                "--output-dir", temp.resolve("duplicate-ids").toString(), "--run-id", "duplicate-ids",
                "run", "native-case", "boundary.duplicate", "--resolved-case-id", "same"));
        assertNotEquals(0, duplicateIds.exitCode);
        assertTrue((duplicateIds.stderr + duplicateIds.stdout).contains("Resolved Case IDs collide"),
                duplicateIds.stderr + duplicateIds.stdout);

        Path outside = home.resolve("outside.xlsx");
        Files.write(outside, new byte[] {1, 2, 3, 4});
        String outsideHash = sha256(outside);
        Files.write(pkg.resolve("testcase/unsafe/testcase.yaml"),
                ("schemaVersion: att-testcase/v1\nid: boundary.unsafe\nflow: boundary.flow.v1\n"
                        + "dataSource:\n  type: excel\n  path: ../outside.xlsx\n  sheet: Cases\n  headerRows: 1\n"
                        + "  caseId: CaseID\n").getBytes(StandardCharsets.UTF_8));
        CliResult unsafe = cli(home, Arrays.asList(
                "--local", "-p", "boundary", "--format", "json",
                "--config", home.resolve("config/config.yaml").toString(),
                "--output-dir", temp.resolve("unsafe").toString(), "--run-id", "unsafe-source",
                "run", "native-case", "boundary.unsafe", "--resolved-case-id", "ROW"));
        assertNotEquals(0, unsafe.exitCode);
        String unsafeOutput = unsafe.stderr + unsafe.stdout;
        assertTrue(unsafeOutput.contains("unavailable or unsafe") || unsafeOutput.contains("does not match the regex pattern"),
                unsafeOutput);
        assertEquals(outsideHash, sha256(outside));
    }

    private CliResult cli(Path home, List<String> args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-Datt.home=" + home);
        command.add("att.FrameworkRunner");
        command.addAll(args);
        Path stdout = temp.resolve("stdout-" + System.nanoTime() + ".log");
        Path stderr = temp.resolve("stderr-" + System.nanoTime() + ".log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        builder.environment().put("ATT_HOME", home.toString());
        builder.environment().put("ORDERS_DB_USERNAME", "docs-example-user");
        builder.environment().put("ORDERS_DB_PASSWORD", "docs-example-password");
        builder.environment().put("PAYMENT_MQ_USERNAME", "docs-example-user");
        builder.environment().put("PAYMENT_MQ_PASSWORD", "docs-example-password");
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "ATT CLI did not finish: " + args);
        return new CliResult(process.exitValue(), read(stdout), read(stderr));
    }

    private static Path repoRoot() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) hex.append(String.format("%02x", Integer.valueOf(value & 0xff)));
        return hex.toString();
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
