/* Author: Jeffrey + ChatGPT */
package att.report;

import att.config.FrameworkConfig;
import att.config.RunConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedOutputCleanerTest {
    @TempDir Path tempDir;

    @Test
    void removesOnlyKnownGeneratedDirectories() throws Exception {
        for (String directory : new String[]{"output", "report", "logs", "build/docs", "dist", "target"}) {
            Path file = tempDir.resolve(directory).resolve("generated.txt");
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[]{1});
        }
        Files.write(tempDir.resolve("build/att-run-R1.tar.gz"), new byte[]{1});
        Files.write(tempDir.resolve("build/keep.txt"), new byte[]{1});
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), Collections.emptyMap(), null, new RunConfig("timestamp", "yyyyMMdd"));
        new GeneratedOutputCleaner().clean(tempDir, config);
        for (String directory : new String[]{"output", "build/docs"}) assertFalse(Files.exists(tempDir.resolve(directory)));
        assertTrue(Files.exists(tempDir.resolve("report")));
        assertTrue(Files.exists(tempDir.resolve("logs")));
        assertTrue(Files.exists(tempDir.resolve("dist")));
        assertTrue(Files.exists(tempDir.resolve("target")));
        assertFalse(Files.exists(tempDir.resolve("build/att-run-R1.tar.gz")));
        assertTrue(Files.exists(tempDir.resolve("build/keep.txt")));
    }

    @Test
    void refusesToCleanThePackageRoot() {
        FrameworkConfig config = new FrameworkConfig(Paths.get("."), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), Collections.emptyMap(), null, new RunConfig("timestamp", "yyyyMMdd"));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedOutputCleaner().clean(tempDir, config));
    }

    @Test
    void refusesToCleanSourceDirectories() {
        FrameworkConfig config = new FrameworkConfig(Paths.get("templates"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), Collections.emptyMap(), null, new RunConfig("timestamp", "yyyyMMdd"));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedOutputCleaner().clean(tempDir, config));
    }
}
