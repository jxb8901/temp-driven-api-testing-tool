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
        String manifest="schemaVersion: att-run/v2.1\nrun:\n  id: R1\n  state: COMPLETE\n  startedAt: '2026-01-01T00:00:00Z'\n  endedAt: '2026-01-01T00:00:01Z'\ninputs:\n  - kind: global-config\n    path: config/config.yaml\n    sha256: "+sha256(tempDir.resolve("config/config.yaml"))+"\ncases: []\n";
        Files.write(run.resolve("run.yaml"),manifest.getBytes("UTF-8"));
        String latest="schemaVersion: att-latest-run/v2.1\nrunId: R1\nrunDirectory: R1\nstatus: PASS\nmanifestSha256: "+sha256(run.resolve("run.yaml"))+"\n";
        Files.write(output.resolve("latest-run.yaml"),latest.getBytes("UTF-8"));
        assertTrue(Files.exists(new ReportRegenerator().regenerate(output,"R1")));
        assertTrue(Files.exists(new RunArchiveBuilder().build(tempDir,output)));
        assertFalse(Files.exists(run.resolve("package-config")));
    }
    private String sha256(Path file) throws Exception { byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)); StringBuilder out=new StringBuilder(); for(byte value:hash) out.append(String.format("%02x",value&255)); return out.toString(); }
}
