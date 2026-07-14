/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.FrameworkConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FppReferenceToolsTest {
    @TempDir Path tempDir;
    private final Path projectRoot = Paths.get("").toAbsolutePath().normalize();

    @Test void apiSkeletonReturnsXmlAndWritesCorrelationLog() throws Exception {
        requirePosix();
        Path request = tempDir.resolve("request.xml");
        Path log = tempDir.resolve("logs/api.log");
        Files.write(request, "<Request/>".getBytes(StandardCharsets.UTF_8));

        CommandResult result = run("./tools/fpp_invoke_api.sh", "REQ<&1", "PAYMENT", request.toString(), log.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("<RequestId>REQ&lt;&amp;1</RequestId>"));
        assertTrue(result.stdout().contains("<ResultCode>NOT_IMPLEMENTED</ResultCode>"));
        assertTrue(result.stdout().contains("<ResultMessage>Reference skeleton:"));
        assertTrue(new String(Files.readAllBytes(log), StandardCharsets.UTF_8).contains("requestId=REQ<&1"));
    }

    @Test void sqlplusConverterSupportsMultipleRowsAndXmlEscaping() throws Exception {
        requirePosix();
        Path input = tempDir.resolve("sqlplus.out");
        Files.write(input, ("ID | NAME | DISPLAY NAME | 2FA | xmlData\n" +
                "---+----------------+----------------+-----+--------\n" +
                "1 | Alice & Bob | Alice | Y | safe\n" +
                "2 | <Admin> | Root | N | reserved\n" +
                "2 rows selected.\n").getBytes(StandardCharsets.UTF_8));

        CommandResult result = run("./tools/fpp_sqlplus_to_xml.sh", input.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("<Row index=\"1\">"));
        assertTrue(result.stdout().contains("<ID>1</ID>"));
        assertTrue(result.stdout().contains("<NAME>Alice &amp; Bob</NAME>"));
        assertTrue(result.stdout().contains("<NAME>&lt;Admin&gt;</NAME>"));
        assertTrue(result.stdout().contains("<Column name=\"DISPLAY NAME\">Alice</Column>"));
        assertTrue(result.stdout().contains("<Column name=\"2FA\">Y</Column>"));
        assertTrue(result.stdout().contains("<Column name=\"xmlData\">safe</Column>"));
        assertTrue(result.stdout().contains("<RowCount>2</RowCount>"));
        assertTrue(new ToolInvoker(tempDir, emptyConfig()).parseOutput(result.stdout(), "xml") instanceof java.util.Map);
    }

    @Test void scriptRunnerCapturesStreamsAndReturnsChildExitCodeAsYaml() throws Exception {
        requirePosix();
        Path child = tempDir.resolve("child.sh");
        Files.write(child, ("#!/usr/bin/env sh\n" +
                "printf 'standard output %s %s\\n' \"$1\" \"$2\"\n" +
                "printf \"can't continue\\n\" >&2\n" +
                "exit 7\n").getBytes(StandardCharsets.UTF_8));
        child.toFile().setExecutable(true);
        Path stdout = tempDir.resolve("capture/stdout.log");
        Path stderr = tempDir.resolve("capture/stderr.log");

        CommandResult result = run("./tools/fpp_run_script.sh", child.toString(), stdout.toString(), stderr.toString(), "D3", "-104");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("exitCode: 7"));
        assertTrue(result.stdout().contains("success: false"));
        assertTrue(result.stdout().contains("errorMessage: 'can''t continue'"));
        assertEquals(7, ((Number) ((java.util.Map<?, ?>) new ToolInvoker(tempDir, emptyConfig())
                .parseOutput(result.stdout(), "yaml")).get("exitCode")).intValue());
        assertEquals("standard output D3 -104\n", new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8));
        assertEquals("can't continue\n", new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8));

        CommandResult missing = run("./tools/fpp_run_script.sh", tempDir.resolve("missing.sh").toString(), stdout.toString(), stderr.toString());
        assertEquals(0, missing.exitCode());
        assertTrue(missing.stdout().contains("exitCode: 127"));
    }

    @Test void fileHelpersMoveInspectAndSearchLiteralText() throws Exception {
        requirePosix();
        Path source = tempDir.resolve("input");
        Path target = tempDir.resolve("worker");
        Files.createDirectories(source);
        String tenLines = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";
        Files.write(source.resolve("FPSPDRR001.xml"), tenLines.getBytes(StandardCharsets.UTF_8));
        Files.write(source.resolve("FPSPDRR002.xml"), tenLines.getBytes(StandardCharsets.UTF_8));

        CommandResult inspected = run("./tools/fpp_inspect_files.sh", source.toString(), "FPSPDRR*.xml", "10");
        Map<?, ?> inspection = yaml(inspected.stdout());
        assertEquals(0, inspected.exitCode());
        assertEquals(2, ((Number) inspection.get("count")).intValue());
        assertEquals(Boolean.TRUE, inspection.get("lineCountMatches"));

        CommandResult moved = run("./tools/fpp_move_files.sh", source.toString(), "FPSPDRR*.xml", target.toString(), "false");
        assertEquals(0, moved.exitCode());
        assertEquals(2, ((Number) yaml(moved.stdout()).get("count")).intValue());
        assertTrue(Files.isRegularFile(target.resolve("FPSPDRR001.xml")));
        assertTrue(Files.isRegularFile(target.resolve("FPSPDRR002.xml")));

        Files.write(source.resolve("FPSPDRR001.xml"), "replacement\n".getBytes(StandardCharsets.UTF_8));
        CommandResult collision = run("./tools/fpp_move_files.sh", source.toString(), "FPSPDRR*.xml", target.toString(), "false");
        assertEquals(5, collision.exitCode());
        assertTrue(String.valueOf(yaml(collision.stdout()).get("errorMessage")).contains("already exists"));

        Path log = tempDir.resolve("transaction.log");
        Files.write(log, "CT001 SUCCESS[1]\nCT001 OTHER\n".getBytes(StandardCharsets.UTF_8));
        CommandResult searched = run("./tools/fpp_search_text.sh", log.toString(), "SUCCESS[1]");
        Map<?, ?> search = yaml(searched.stdout());
        assertEquals(0, searched.exitCode());
        assertEquals(Boolean.TRUE, search.get("found"));
        assertEquals(1, ((Number) search.get("count")).intValue());
    }

    @Test void sqlplusQueryConvertsSummaryColumnsAndPropagatesExitCode() throws Exception {
        requirePosix();
        Path fakeSqlplus = tempDir.resolve("fake-sqlplus.sh");
        Files.write(fakeSqlplus, ("#!/usr/bin/env sh\n" +
                "printf 'RECORD_COUNT | MATCH_COUNT | RESULT_SUMMARY\\n'\n" +
                "printf '2 | 2 | SR_STUS=PCFM\\n'\n" +
                "exit 7\n").getBytes(StandardCharsets.UTF_8));
        fakeSqlplus.toFile().setExecutable(true);
        Path sql = tempDir.resolve("query.sql");
        Files.write(sql, "select 1 from dual;\n".getBytes(StandardCharsets.UTF_8));
        Path output = tempDir.resolve("sql/query.out");
        Path stderr = tempDir.resolve("sql/query.err");

        CommandResult result = run("/usr/bin/env", "FPP_SQLPLUS_BIN=" + fakeSqlplus,
                "FPP_SQLPLUS_CONNECT=/@ATT_TEST", "./tools/fpp_query_sqlplus.sh",
                sql.toString(), output.toString(), stderr.toString());

        assertEquals(7, result.exitCode());
        Map<?, ?> xml = (Map<?, ?>) new ToolInvoker(tempDir, emptyConfig()).parseOutput(result.stdout(), "xml");
        Map<?, ?> row = (Map<?, ?>) ((Map<?, ?>) xml.get("Rows")).get("Row");
        assertEquals("2", row.get("RECORD_COUNT"));
        assertEquals("2", row.get("MATCH_COUNT"));
        assertEquals("SR_STUS=PCFM", row.get("RESULT_SUMMARY"));
        assertTrue(Files.isRegularFile(output));
    }

    private CommandResult run(String... argv) throws Exception {
        return new CommandRunner().run(Arrays.asList(argv), Duration.ofSeconds(5), projectRoot);
    }

    private FrameworkConfig emptyConfig() {
        return new FrameworkConfig(null, null, null, "SIT", 10000, null, null, null, null);
    }

    private Map<?, ?> yaml(String text) throws Exception {
        return (Map<?, ?>) new ToolInvoker(tempDir, emptyConfig()).parseOutput(text, "yaml");
    }

    private void requirePosix() {
        Assumptions.assumeFalse(File.separatorChar == '\\', "POSIX reference scripts are packaged as examples on Windows");
    }
}
