/* Author: Jeffrey + ChatGPT */
package att.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportLifecycleTest {
    @TempDir Path tempDir;
    @Test void regeneratesCompletedRunAndBuildsArchive() throws Exception {
        Path output=tempDir.resolve("output"), run=output.resolve("R1"); Files.createDirectories(run);
        Files.createDirectories(tempDir.resolve("config")); Files.write(tempDir.resolve("config/config.yaml"),"token: secret\n".getBytes("UTF-8"));
        String manifest="schemaVersion: att-run/v2.1\nrun:\n  id: R1\n  state: COMPLETE\n  startedAt: '2026-01-01T00:00:00Z'\n  endedAt: '2026-01-01T00:00:01Z'\ninputs:\n  - kind: global-config\n    path: config/config.yaml\n    sha256: "+sha256(tempDir.resolve("config/config.yaml"))+"\ncases:\n  - caseId: payments.local.TC001\n    caseName: Local payment\n    workbookId: payments\n    sheetId: local\n    tags: [smoke, payment]\n    status: PASS\n    durationMs: 12\n    expected: SUCCESS\n    actual: SUCCESS\n    caseLog: ''\n";
        Files.write(run.resolve("run.yaml"),manifest.getBytes("UTF-8"));
        String latest="schemaVersion: att-latest-run/v2.1\nrunId: R1\nrunDirectory: R1\nstatus: PASS\nmanifestSha256: "+sha256(run.resolve("run.yaml"))+"\n";
        Files.write(output.resolve("latest-run.yaml"),latest.getBytes("UTF-8"));
        Path regenerated = new ReportRegenerator().regenerate(output,"R1");
        assertTrue(Files.exists(regenerated));
        String html = new String(Files.readAllBytes(regenerated), "UTF-8");
        assertTrue(html.contains("payments.local"));
        assertTrue(html.contains("data-tags=\"smoke, payment\""));
        assertTrue(html.contains("id=\"workbookFilter\""));
        assertTrue(Files.exists(run.resolve("report/junit.html")));
        assertTrue(Files.exists(new RunArchiveBuilder().build(tempDir,output)));
        assertFalse(Files.exists(run.resolve("package-config")));
    }
    @Test void reportAndBuildRejectSymlinkRunDirectory() throws Exception {
        Path output=tempDir.resolve("out"), outside=tempDir.resolve("outside"); Files.createDirectories(output); Files.createDirectories(outside);
        try { Files.createSymbolicLink(output.resolve("R1"), outside); } catch (UnsupportedOperationException | java.io.IOException e) { return; }
        assertThrows(IllegalArgumentException.class, () -> new ReportRegenerator().regenerate(output,"R1"));
        Files.write(output.resolve("latest-run.yaml"), "schemaVersion: att-latest-run/v2.1\nrunId: R1\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new RunArchiveBuilder().build(tempDir,output));
    }
    private String sha256(Path file) throws Exception { byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)); StringBuilder out=new StringBuilder(); for(byte value:hash) out.append(String.format("%02x",value&255)); return out.toString(); }
}
