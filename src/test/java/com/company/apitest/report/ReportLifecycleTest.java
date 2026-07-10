/* Author: Jeffrey + ChatGPT */
package com.company.apitest.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportLifecycleTest {
    @TempDir Path tempDir;
    @Test void regeneratesCompletedRunAndBuildsArchive() throws Exception {
        Path output=tempDir.resolve("output"), run=output.resolve("R1"); Files.createDirectories(run);
        String manifest="runId: R1\nrunDirectory: '"+run.toString().replace("'","''")+"'\nstatus: COMPLETE\nstartedAt: '2026-01-01T00:00:00Z'\nendedAt: '2026-01-01T00:00:01Z'\ncases: []\n";
        Files.write(run.resolve("run.yaml"),manifest.getBytes("UTF-8")); Files.write(output.resolve("latest-run.yaml"),manifest.getBytes("UTF-8"));
        Files.createDirectories(tempDir.resolve("config")); Files.write(tempDir.resolve("config/config.yaml"),"token: secret\n".getBytes("UTF-8"));
        assertTrue(Files.exists(new ReportRegenerator().regenerate(output,"R1")));
        assertTrue(Files.exists(new RunArchiveBuilder().build(tempDir,output)));
        assertTrue(new String(Files.readAllBytes(run.resolve("package-config/config/config.yaml")),"UTF-8").contains("***REDACTED***"));
    }
}
