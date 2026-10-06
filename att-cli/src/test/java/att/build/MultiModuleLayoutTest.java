/* Author: Jeffrey + ChatGPT */
package att.build;

import att.config.FrameworkConfig;
import att.config.RunConfig;
import att.report.GeneratedOutputCleaner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiModuleLayoutTest {
    @TempDir Path temp;

    @Test void repositoryKeepsCliServerAndDistributionBoundariesExplicit() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("att-cli/pom.xml")));
        assertTrue(Files.isRegularFile(root.resolve("att-cli/src/main/java/att/FrameworkRunner.java")));
        assertFalse(Files.exists(root.resolve("src/main/java/att/FrameworkRunner.java")));
        assertTrue(Files.isRegularFile(root.resolve("att-server/pom.xml")));
        assertTrue(Files.isRegularFile(root.resolve("att-dist/pom.xml")));
        assertTrue(Files.isRegularFile(root.resolve("att-dist/src/assembly/binary.xml")));
        assertTrue(Files.isRegularFile(root.resolve("att-dist/src/assembly/source.xml")));
        String parent = read(root.resolve("pom.xml"));
        for (String module : Arrays.asList("att-cli", "att-server", "att-dist"))
            assertTrue(parent.contains("<module>" + module + "</module>"), parent);
        String server = read(root.resolve("att-server/pom.xml")).toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : Arrays.asList("spring", "jetty", "netty", "servermain", "worker", "websocket"))
            assertFalse(server.contains(forbidden), server);
        String build = read(root.resolve("build.sh"));
        assertFalse(build.contains("javac "), build);
        assertTrue(build.contains("att-dist/target/$PACKAGE_NAME.tar.gz"), build);
        assertTrue(read(root.resolve("att.sh")).contains("att-cli/target/classes"));
    }

    @Test void cleanCannotDeleteAnyModuleSourceTree() throws Exception {
        for (String module : Arrays.asList("att-cli", "att-server", "att-dist")) {
            Path root = temp.resolve(module + "-project");
            Files.createDirectories(root.resolve(module));
            FrameworkConfig config = new FrameworkConfig(Paths.get(module), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                    Paths.get("templates"), Collections.emptyMap(), null, new RunConfig("timestamp", "yyyyMMdd"));
            assertThrows(IllegalArgumentException.class, () -> new GeneratedOutputCleaner().clean(root, config));
            assertTrue(Files.isDirectory(root.resolve(module)));
        }
    }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
}
