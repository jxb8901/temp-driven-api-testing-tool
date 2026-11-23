/* Author: Jeffrey + ChatGPT */
package att.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline acceptance for the multi-Flow Native payments example (#425). */
class NativePaymentsExampleTest {
    private static final List<String> DEFINITIONS = Arrays.asList(
            "payments.offline.authorize", "payments.offline.settle");

    @TempDir Path temp;
    private int processCounter;

    @Test
    void guidesAndPackagedFilesNameTheResolvedCases() throws Exception {
        Path root = repoRoot();
        for (String relative : Arrays.asList(
                "packages/default/templates/flows/payments/offline-authorize/flow.yaml",
                "packages/default/templates/flows/payments/offline-settle/flow.yaml",
                "packages/default/testcase/payments/offline-authorize/testcase.yaml",
                "packages/default/testcase/payments/offline-settle/testcase.yaml",
                "packages/default/testdata/payments-offline-settle.xlsx",
                "docs/guides/native-payments.md",
                "docs/guides/native-payments.zh.md")) {
            assertTrue(Files.isRegularFile(root.resolve(relative)), "missing " + relative);
        }
        String en = read(root.resolve("docs/guides/native-payments.md"));
        String zh = read(root.resolve("docs/guides/native-payments.zh.md"));
        for (String id : Arrays.asList(
                "payments.offline.authorize.auth-accepted",
                "payments.offline.authorize.auth-rejected",
                "payments.offline.authorize.auth-mismatch",
                "payments.offline.settle.SETTLE-OK",
                "payments.offline.settle.SETTLE-REJECTED",
                "payments.offline.settle.SETTLE-MISMATCH",
                "payments.offline.authorize.v1",
                "payments.offline.settle.v1",
                "MULTIPLE_DEFINITIONS",
                "--rerun-failed")) {
            assertTrue(en.contains(id), "English walkthrough missing " + id);
            assertTrue(zh.contains(id), "Chinese walkthrough missing " + id);
        }
        assertTrue(en.contains("(native-payments.zh.md)"));
        assertTrue(zh.contains("(native-payments.md)"));
        assertTrue(en.contains("There is no `suite.yaml`"));
        assertFalse(read(root.resolve("packages/default/testcase/payments/offline-authorize/testcase.yaml")).contains("steps:"));
    }

    @Test
    void qualifiedIdsSpanBothFlowsAndPass() throws Exception {
        Path output = temp.resolve("ids");
        CliResult result = suite(output, "payments-ids", 0,
                "--qualified-case-id", "payments.offline.authorize.auth-accepted",
                "--qualified-case-id", "payments.offline.settle.SETTLE-OK");
        Map<String, String> status = caseStatus(result);
        assertEquals("PASS", status.get("payments.offline.authorize.auth-accepted"));
        assertEquals("PASS", status.get("payments.offline.settle.SETTLE-OK"));
        assertEquals(2, status.size());
        Map<?, ?> summary = summary(result);
        assertEquals(Integer.valueOf(2), toInt(summary.get("total")));
        assertEquals(Integer.valueOf(2), toInt(summary.get("passed")));
        assertEquals(Integer.valueOf(2), toInt(summary.get("executionCount")));
        assertEquals(Integer.valueOf(0), toInt(summary.get("skipped")));
        Map<String, String> flows = caseFlows(result);
        assertEquals("payments.offline.authorize.v1", flows.get("payments.offline.authorize.auth-accepted"));
        assertEquals("payments.offline.settle.v1", flows.get("payments.offline.settle.SETTLE-OK"));
        assertEvidence(output.resolve("payments-ids"), status);
    }

    @Test
    void tagSelectionExcludesBusinessRejection() throws Exception {
        CliResult result = suite(temp.resolve("tags"), "payments-tags", 0,
                "--tag", "smoke", "--exclude-tag", "business-rejection");
        Map<String, String> status = caseStatus(result);
        assertEquals(Arrays.asList(
                "payments.offline.authorize.auth-accepted",
                "payments.offline.settle.SETTLE-OK"), new ArrayList<String>(status.keySet()));
        assertEquals("PASS", status.get("payments.offline.authorize.auth-accepted"));
        assertEquals("PASS", status.get("payments.offline.settle.SETTLE-OK"));
    }

    @Test
    void allCasesRecordPassFailEvidenceAndSuiteFallback() throws Exception {
        Path workbook = repoRoot().resolve("packages/default/testdata/payments-offline-settle.xlsx");
        String before = sha256(workbook);
        Path runDir = temp.resolve("all").resolve("payments-all");
        CliResult result = suite(temp.resolve("all"), "payments-all", 1, "--all");
        Map<String, String> status = caseStatus(result);
        assertEquals("PASS", status.get("payments.offline.authorize.auth-accepted"));
        assertEquals("PASS", status.get("payments.offline.authorize.auth-rejected"));
        assertEquals("FAIL", status.get("payments.offline.authorize.auth-mismatch"));
        assertEquals("PASS", status.get("payments.offline.settle.SETTLE-OK"));
        assertEquals("PASS", status.get("payments.offline.settle.SETTLE-REJECTED"));
        assertEquals("FAIL", status.get("payments.offline.settle.SETTLE-MISMATCH"));
        Map<?, ?> summary = summary(result);
        assertEquals(Integer.valueOf(6), toInt(summary.get("total")));
        assertEquals(Integer.valueOf(4), toInt(summary.get("passed")));
        assertEquals(Integer.valueOf(2), toInt(summary.get("failed")));
        assertEquals(Integer.valueOf(6), toInt(summary.get("executionCount")));
        assertEquals(Integer.valueOf(0), toInt(summary.get("skipped")));
        assertEquals("FAIL", result.document.get("status"));
        assertEquals(before, sha256(workbook), "source workbook must be unchanged");
        assertTrue(Files.isRegularFile(runDir.resolve("workbooks/native-cases.result.xlsx")));
        assertFalse(Files.exists(runDir.resolve("workbooks/native-cases.source-workbook.xlsx")));
        Map<?, ?> run = runNode(runDir);
        Map<?, ?> sourceReport = (Map<?, ?>) run.get("sourceWorkbookReport");
        assertEquals("MULTIPLE_DEFINITIONS", sourceReport.get("diagnosticCode"));
        String html = read(runDir.resolve("report/index.html"));
        assertTrue(html.contains("payments.offline.authorize.auth-mismatch"));
        assertTrue(html.contains("case.log"));
        assertTrue(Files.isRegularFile(runDir.resolve("ci/junit.xml")));
        assertTrue(Files.isRegularFile(runDir.resolve("ci/summary.json")));
    }

    @Test
    void settlementRunWritesSourceWorkbookWithoutMutatingInput() throws Exception {
        Path workbook = repoRoot().resolve("packages/default/testdata/payments-offline-settle.xlsx");
        String before = sha256(workbook);
        Path runDir = temp.resolve("settle").resolve("payments-settle");
        CliResult result = runCli(repoRoot(), suiteArgs(temp.resolve("settle"), "payments-settle",
                "--definition", "payments.offline.settle", "--all"));
        assertEquals(1, result.exitCode, result.stderr + "\n" + result.stdout);
        Map<String, String> status = caseStatus(result);
        assertEquals("PASS", status.get("payments.offline.settle.SETTLE-OK"));
        assertEquals("PASS", status.get("payments.offline.settle.SETTLE-REJECTED"));
        assertEquals("FAIL", status.get("payments.offline.settle.SETTLE-MISMATCH"));
        assertEquals(before, sha256(workbook));
        assertTrue(Files.isRegularFile(runDir.resolve("workbooks/native-cases.result.xlsx")));
        assertTrue(Files.isRegularFile(runDir.resolve("workbooks/native-cases.source-workbook.xlsx")));
        Map<?, ?> sourceReport = (Map<?, ?>) runNode(runDir).get("sourceWorkbookReport");
        assertEquals("written", sourceReport.get("status"));
    }

    @Test
    void failFastSkipsCasesThatDidNotStart() throws Exception {
        CliResult result = suite(temp.resolve("failfast"), "payments-failfast", 1, "--all", "--fail-fast");
        Map<String, String> status = caseStatus(result);
        assertEquals("PASS", status.get("payments.offline.authorize.auth-accepted"));
        assertEquals("PASS", status.get("payments.offline.authorize.auth-rejected"));
        assertEquals("FAIL", status.get("payments.offline.authorize.auth-mismatch"));
        assertEquals("SKIPPED", status.get("payments.offline.settle.SETTLE-OK"));
        assertEquals("SKIPPED", status.get("payments.offline.settle.SETTLE-REJECTED"));
        assertEquals("SKIPPED", status.get("payments.offline.settle.SETTLE-MISMATCH"));
        Map<?, ?> summary = summary(result);
        assertEquals(Integer.valueOf(6), toInt(summary.get("total")));
        assertEquals(Integer.valueOf(2), toInt(summary.get("passed")));
        assertEquals(Integer.valueOf(1), toInt(summary.get("failed")));
        assertEquals(Integer.valueOf(3), toInt(summary.get("skipped")));
        assertEquals(Integer.valueOf(3), toInt(summary.get("executionCount")));
        for (Map<String, Object> row : cases(result)) {
            if ("SKIPPED".equals(row.get("status"))) {
                assertEquals(Boolean.FALSE, row.get("executed"));
            }
        }
    }

    @Test
    void rerunFailedRetriesOnlyExecutedFailures() throws Exception {
        Path output = temp.resolve("rerun");
        suite(output, "payments-all", 1, "--all");
        CliResult retry = suite(output, "payments-rerun", 1, "--rerun-failed");
        Map<String, String> status = caseStatus(retry);
        assertEquals(Arrays.asList(
                "payments.offline.authorize.auth-mismatch",
                "payments.offline.settle.SETTLE-MISMATCH"), new ArrayList<String>(status.keySet()));
        assertEquals("FAIL", status.get("payments.offline.authorize.auth-mismatch"));
        assertEquals("FAIL", status.get("payments.offline.settle.SETTLE-MISMATCH"));
        Map<?, ?> summary = summary(retry);
        assertEquals(Integer.valueOf(2), toInt(summary.get("total")));
        assertEquals(Integer.valueOf(2), toInt(summary.get("failed")));
        assertEquals(Integer.valueOf(2), toInt(summary.get("executionCount")));
        assertEquals(Integer.valueOf(0), toInt(summary.get("skipped")));
        Map<?, ?> lineage = (Map<?, ?>) runNode(output.resolve("payments-rerun")).get("retryLineage");
        assertEquals(Integer.valueOf(6), toInt(lineage.get("sourceSelectedCaseCount")));
        assertEquals(Integer.valueOf(1), toInt(lineage.get("retryAttemptNumber")));
        assertEquals("payments-all", lineage.get("sourceRunId"));
    }

    private CliResult suite(Path output, String runId, int expectedExit, String... selectors) throws Exception {
        List<String> args = new ArrayList<String>();
        args.add("--definition");
        args.add(DEFINITIONS.get(0));
        args.add("--definition");
        args.add(DEFINITIONS.get(1));
        args.addAll(Arrays.asList(selectors));
        CliResult result = runCli(repoRoot(), suiteArgs(output, runId, args.toArray(new String[0])));
        assertEquals(expectedExit, result.exitCode, result.stderr + "\n" + result.stdout);
        return result;
    }

    private List<String> suiteArgs(Path output, String runId, String... args) {
        List<String> command = new ArrayList<String>(Arrays.asList(
                "--local", "-p", "default", "--format", "json",
                "--output-dir", output.toString(), "--run-id", runId,
                "run", "native-suite"));
        command.addAll(Arrays.asList(args));
        return command;
    }

    private void assertEvidence(Path runDirectory, Map<String, String> status) throws Exception {
        assertTrue(Files.isRegularFile(runDirectory.resolve("report/index.html")));
        assertTrue(Files.isRegularFile(runDirectory.resolve("workbooks/native-cases.result.xlsx")));
        assertTrue(Files.isRegularFile(runDirectory.resolve("ci/summary.json")));
        String html = read(runDirectory.resolve("report/index.html"));
        for (String id : status.keySet()) {
            assertTrue(html.contains(id), html);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> runNode(Path runDirectory) throws Exception {
        Object loaded = new Yaml().load(read(runDirectory.resolve("run.yaml")));
        return (Map<?, ?>) ((Map<String, Object>) loaded).get("run");
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> summary(CliResult result) {
        return (Map<?, ?>) result.document.get("summary");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cases(CliResult result) {
        return (List<Map<String, Object>>) summary(result).get("cases");
    }

    private Map<String, String> caseStatus(CliResult result) {
        Map<String, String> status = new LinkedHashMap<String, String>();
        for (Map<String, Object> row : cases(result)) {
            status.put(String.valueOf(row.get("qualifiedCaseId")), String.valueOf(row.get("status")));
        }
        return status;
    }

    private Map<String, String> caseFlows(CliResult result) {
        Map<String, String> flows = new LinkedHashMap<String, String>();
        for (Map<String, Object> row : cases(result)) {
            flows.put(String.valueOf(row.get("qualifiedCaseId")), String.valueOf(row.get("flowId")));
        }
        return flows;
    }

    private CliResult runCli(Path root, List<String> args) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "-Datt.home=" + root,
                "att.FrameworkRunner"));
        command.addAll(args);
        Path stdout = temp.resolve("cli-" + (++processCounter) + ".stdout");
        Path stderr = temp.resolve("cli-" + processCounter + ".stderr");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.environment().put("ATT_HOME", root.toString());
        builder.environment().put("ORDERS_DB_USERNAME", "docs-example-user");
        builder.environment().put("ORDERS_DB_PASSWORD", "docs-example-password");
        builder.environment().put("PAYMENT_MQ_USERNAME", "docs-example-user");
        builder.environment().put("PAYMENT_MQ_PASSWORD", "docs-example-password");
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "ATT CLI did not finish: " + args);
        String out = read(stdout);
        CliResult result = new CliResult(process.exitValue(), out, read(stderr));
        if (out.trim().startsWith("{")) {
            result.document = att.validation.JsonSupport.mapper().readValue(out, Map.class);
        }
        return result;
    }

    private static Path repoRoot() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) hex.append(String.format("%02x", Integer.valueOf(value & 0xff)));
        return hex.toString();
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
        return Integer.valueOf(String.valueOf(value));
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static final class CliResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private Map<String, Object> document = new LinkedHashMap<String, Object>();

        private CliResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

}
