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

    @Test void repositoryKeepsModuleBoundariesAndIndependentJavaBaselines() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("att-cli/pom.xml")));
        assertTrue(Files.isRegularFile(root.resolve("att-cli/src/main/java/att/FrameworkRunner.java")));
        assertFalse(Files.exists(root.resolve("src/main/java/att/FrameworkRunner.java")));
        assertTrue(Files.isRegularFile(root.resolve("att-server/pom.xml")));
        assertTrue(Files.isRegularFile(root.resolve("att-dist/pom.xml")));

        String parent = read(root.resolve("pom.xml"));
        for (String module : Arrays.asList("att-cli", "att-server", "att-dist"))
            assertTrue(parent.contains("<module>" + module + "</module>"), parent);
        assertTrue(parent.contains("<att.cli.java>8</att.cli.java>"), parent);
        assertFalse(parent.contains("<maven.compiler.source>"), parent);
        assertFalse(parent.contains("<maven.compiler.target>"), parent);

        String cli = read(root.resolve("att-cli/pom.xml"));
        assertTrue(cli.contains("<source>${att.cli.java}</source>"), cli);
        assertTrue(cli.contains("<target>${att.cli.java}</target>"), cli);
        assertTrue(cli.contains("<mainClass>att.FrameworkRunner</mainClass>"), cli);

        String server = read(root.resolve("att-server/pom.xml"));
        assertFalse(server.contains("<source>"), server);
        assertFalse(server.contains("<target>"), server);
        String lower = server.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : Arrays.asList("spring", "jetty", "netty", "servermain", "worker", "websocket"))
            assertFalse(lower.contains(forbidden), server);
    }

    @Test void releasePackagingConsumesMavenBuiltCliJar() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        String binary = read(root.resolve("att-dist/src/assembly/binary.xml"));
        assertTrue(binary.contains("<include>att:att-cli</include>"), binary);
        assertTrue(binary.contains("<outputFileNameMapping>att-${project.version}.jar</outputFileNameMapping>"), binary);

        String distPom = read(root.resolve("att-dist/pom.xml"));
        assertTrue(count(distPom, "<attach>false</attach>") == 2, distPom);

        String build = read(root.resolve("build.sh"));
        assertFalse(build.contains("javac "), build);
        assertFalse(build.contains("jar cf "), build);
        assertTrue(build.contains("att-dist/target/$PACKAGE_NAME.tar.gz"), build);
        int outputDir = build.indexOf("mkdir -p \"$PACKAGE_DIR/output\"");
        int finalRepack = build.indexOf("tar --no-xattrs -czf \"$BINARY_ARCHIVE\" \"$PACKAGE_NAME\"");
        int finalExtract = build.indexOf("tar -xzf \"$BINARY_ARCHIVE\" -C \"$RELEASE_WORK\"", finalRepack);
        int smoke = build.indexOf("./att.sh version | grep -Fx");
        assertTrue(outputDir >= 0 && finalRepack > outputDir && finalExtract > finalRepack && smoke > finalExtract, build);
        assertTrue(read(root.resolve("att.sh")).contains("CLI_DIR=\"$ROOT_DIR/att-cli\""));
        assertTrue(read(root.resolve("att.sh")).contains("$CLI_DIR/target/classes"));
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

    private static int count(String value, String token) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
