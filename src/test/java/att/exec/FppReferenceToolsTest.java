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
                "printf 'standard output\\n'\n" +
                "printf \"can't continue\\n\" >&2\n" +
                "exit 7\n").getBytes(StandardCharsets.UTF_8));
        child.toFile().setExecutable(true);
        Path stdout = tempDir.resolve("capture/stdout.log");
        Path stderr = tempDir.resolve("capture/stderr.log");

        CommandResult result = run("./tools/fpp_run_script.sh", child.toString(), stdout.toString(), stderr.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("exitCode: 7"));
        assertTrue(result.stdout().contains("success: false"));
        assertTrue(result.stdout().contains("errorMessage: 'can''t continue'"));
        assertEquals(7, ((Number) ((java.util.Map<?, ?>) new ToolInvoker(tempDir, emptyConfig())
                .parseOutput(result.stdout(), "yaml")).get("exitCode")).intValue());
        assertEquals("standard output\n", new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8));
        assertEquals("can't continue\n", new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8));

        CommandResult missing = run("./tools/fpp_run_script.sh", tempDir.resolve("missing.sh").toString(), stdout.toString(), stderr.toString());
        assertEquals(0, missing.exitCode());
        assertTrue(missing.stdout().contains("exitCode: 127"));
    }

    private CommandResult run(String... argv) throws Exception {
        return new CommandRunner().run(Arrays.asList(argv), Duration.ofSeconds(5), projectRoot);
    }

    private FrameworkConfig emptyConfig() {
        return new FrameworkConfig(null, null, null, "SIT", 10000, null, null, null, null);
    }

    private void requirePosix() {
        Assumptions.assumeFalse(File.separatorChar == '\\', "POSIX reference scripts are packaged as examples on Windows");
    }
}
