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

    @Test void repositoryKeepsEngineCliWorkerServerAndDistributionBoundaries() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        for (String module : Arrays.asList("att-engine", "att-cli", "att-worker", "att-server", "att-dist"))
            assertTrue(Files.isRegularFile(root.resolve(module + "/pom.xml")), module);
        assertTrue(Files.isRegularFile(root.resolve("att-cli/src/main/java/att/FrameworkRunner.java")));
        assertTrue(Files.isRegularFile(root.resolve("att-engine/src/main/java/att/api/AttService.java")));
        assertTrue(Files.isRegularFile(root.resolve("att-worker/src/main/java/att/worker/WorkerMain.java")));
        assertFalse(Files.exists(root.resolve("src/main/java/att/FrameworkRunner.java")));

        String parent = read(root.resolve("pom.xml"));
        for (String module : Arrays.asList("att-engine", "att-cli", "att-worker", "att-server", "att-dist"))
            assertTrue(parent.contains("<module>" + module + "</module>"), parent);
        assertTrue(parent.contains("<att.cli.java>8</att.cli.java>"), parent);
        assertFalse(parent.contains("<maven.compiler.source>"), parent);
        assertFalse(parent.contains("<maven.compiler.target>"), parent);

        String engine = read(root.resolve("att-engine/pom.xml"));
        assertTrue(engine.contains("<source>${att.cli.java}</source>"), engine);
        assertTrue(engine.contains("<target>${att.cli.java}</target>"), engine);
        for (String forbidden : Arrays.asList("<artifactId>att-cli</artifactId>", "<artifactId>att-worker</artifactId>", "<artifactId>att-server</artifactId>"))
            assertFalse(engine.contains(forbidden), engine);
        String cli = read(root.resolve("att-cli/pom.xml"));
        assertTrue(cli.contains("<artifactId>att-engine</artifactId>"), cli);
        assertTrue(cli.contains("<mainClass>att.FrameworkRunner</mainClass>"), cli);
        String worker = read(root.resolve("att-worker/pom.xml"));
        assertTrue(worker.contains("<artifactId>att-engine</artifactId>"), worker);
        assertTrue(worker.contains("<artifactId>att-cli</artifactId><version>${project.version}</version><scope>test</scope>"), worker);

        String server = read(root.resolve("att-server/pom.xml"));
        assertTrue(server.contains("<packaging>war</packaging>"), server);
        assertTrue(server.contains("<release>17</release>"), server);
        assertTrue(server.contains("jakarta.servlet-api"), server);
        String lower = server.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : Arrays.asList("spring", "jetty", "netty", "servermain", "websocket"))
            assertFalse(lower.contains(forbidden), server);
    }

    @Test void releasePackagingContainsCliEngineAndWorkerDependencies() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        String binary = read(root.resolve("att-dist/src/assembly/binary.xml"));
        assertTrue(binary.contains("<include>att:att-cli</include>"), binary);
        assertTrue(binary.contains("att-${project.version}.jar"), binary);
        assertTrue(binary.contains("att-server-${project.version}.war"), binary);
        String source = read(root.resolve("att-dist/src/assembly/source.xml"));
        assertTrue(source.contains("<include>att-engine/**</include>"), source);
        assertTrue(source.contains("<include>att-worker/**</include>"), source);
        assertTrue(source.contains("<include>att-server/**</include>"), source);

        String build = read(root.resolve("build.sh"));
        assertFalse(build.contains("javac "), build);
        assertFalse(build.contains("jar cf "), build);
        assertTrue(build.contains("att-dist/target/$PACKAGE_NAME.tar.gz"), build);
        int outputDir = build.indexOf("mkdir -p \"$PACKAGE_DIR/output\"");
        int finalRepack = build.indexOf("tar --no-xattrs -czf \"$BINARY_ARCHIVE\" \"$PACKAGE_NAME\"");
        int finalExtract = build.indexOf("tar -xzf \"$BINARY_ARCHIVE\" -C \"$RELEASE_WORK\"", finalRepack);
        int smoke = build.indexOf("./att.sh version | grep -Fx");
        assertTrue(outputDir >= 0 && finalRepack > outputDir && finalExtract > finalRepack && smoke > finalExtract, build);
        assertTrue(read(root.resolve("att.sh")).contains("$ENGINE_DIR/target/classes"));
    }

    @Test void cleanCannotDeleteAnyModuleSourceTree() throws Exception {
        for (String module : Arrays.asList("att-engine", "att-cli", "att-worker", "att-server", "att-dist")) {
            Path root = temp.resolve(module + "-project");
            Files.createDirectories(root.resolve(module));
            FrameworkConfig config = new FrameworkConfig(Paths.get(module), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                    Paths.get("templates"), Collections.emptyMap(), null, new RunConfig("timestamp", "yyyyMMdd"));
            assertThrows(IllegalArgumentException.class, () -> new GeneratedOutputCleaner().clean(root, config));
            assertTrue(Files.isDirectory(root.resolve(module)));
        }
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
