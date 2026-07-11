/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkConfigLoaderTest {
    @TempDir Path tempDir;
    @Test void loadsV2AndRejectsGlobalStages() throws Exception {
        Path ok=tempDir.resolve("ok.yaml"); Files.write(ok,"schemaVersion: att-config/v2.1\noutputDirectory: out\ntools: {}\n".getBytes("UTF-8"));
        assertEquals(Paths.get("out"),new FrameworkConfigLoader().load(ok).outputDirectory());
        Path bad=tempDir.resolve("bad.yaml"); Files.write(bad,"stages: []\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(bad));
    }
}
