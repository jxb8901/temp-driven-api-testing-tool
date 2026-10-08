/* Author: Jeffrey + ChatGPT */
package att;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsLauncherTest {
    @Test void windowsLauncherSupportsPackagedAndSourceTreeModes() throws Exception {
        Path launcher = Paths.get("att.bat");
        assertTrue(Files.isRegularFile(launcher));
        String text = new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8);
        assertTrue(text.contains("cd /d \"%ROOT_DIR%\""));
        assertTrue(text.contains("lib\\att-*.jar"));
        assertTrue(text.contains("lib\\*\" att.FrameworkRunner %*"));
        assertTrue(text.contains("target\\classes"));
        assertTrue(text.contains(";%M2_REPO%"));
    }

    @Test void releaseAssemblyPackagesWindowsLauncher() throws Exception {
        String binary = new String(Files.readAllBytes(Paths.get("att-dist/src/assembly/local.xml")), StandardCharsets.UTF_8);
        String source = new String(Files.readAllBytes(Paths.get("att-dist/src/assembly/source.xml")), StandardCharsets.UTF_8);
        String manifest = new String(Files.readAllBytes(Paths.get("att-dist/src/release/local/RELEASE_MANIFEST.txt")), StandardCharsets.UTF_8);
        assertTrue(binary.contains("<source>${project.basedir}/../att.bat</source>"));
        assertTrue(binary.contains("<destName>att.bat</destName>"));
        assertTrue(source.contains("<include>att.bat</include>"));
        assertTrue(manifest.contains("mainWindows: att.bat"));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void sourceTreeLauncherRunsCommandBackedToolWithoutNativeProcessDependency() throws Exception {
        Path packageRoot = Paths.get("").toAbsolutePath();
        Path targetDirectory = packageRoot.resolve("target");
        Files.createDirectories(targetDirectory);
        Path temp = Files.createTempDirectory(targetDirectory, "windows-launcher-smoke-" + UUID.randomUUID() + "-");
        Path templates = temp.resolve("templates");
        Path simple = templates.resolve("SIMPLE");
        Files.createDirectories(simple);
        Path templateRoot = packageRoot.relativize(templates);
        Path outputRoot = packageRoot.relativize(temp.resolve("output"));
        Path config = temp.resolve("config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.11\nenvironment: SIT\noutputDirectory: " + yamlQuote(outputRoot) + "\n"
                + "templates: {root: " + yamlQuote(templateRoot) + "}\ntestcase: {root: testcase}\ntools:\n"
                + "  smoke:\n    name: Launcher smoke\n    description: Verify source-tree Tool execution\n"
                + "    command: [cmd.exe, /c, echo, launcher-smoke]\n    stdoutFormat: text\n    arguments: {}\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(simple.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\nname: SIMPLE\n"
                + "description: Windows source launcher smoke\nactions:\n  invoke:\n    type: tool\n    call: \"#{smoke()}\"\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(simple.resolve("debug.yaml"), "schemaVersion: att-debug/v1.2\ninputs: {}\n".getBytes(StandardCharsets.UTF_8));

        Path output = temp.resolve("launcher.stdout"), error = temp.resolve("launcher.stderr");
        Path mavenRepository = Paths.get(System.getProperty("user.home"), ".m2", "repository");
        Path snakeYaml = mavenRepository.resolve("org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar");
        assertTrue(Files.isRegularFile(snakeYaml), "Maven did not populate the expected SnakeYAML artifact: " + snakeYaml);
        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", Paths.get("att.bat").toAbsolutePath().toString(),
                "debug", "template", "SIMPLE", "--config", config.toString(), "--format", "json", "--quiet")
                .directory(Paths.get("").toAbsolutePath().toFile())
                .redirectOutput(output.toFile()).redirectError(error.toFile());
        builder.environment().put("M2_REPO", mavenRepository.toString());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "att.bat did not finish the command-backed Tool smoke test");
            String stdout = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            String stderr = new String(Files.readAllBytes(error), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), stderr + "\n" + stdout);
            assertTrue(stdout.contains("PASS") && stdout.contains("launcher-smoke"), stdout + "\n" + stderr);
            assertFalse(stderr.contains("NoClassDefFoundError"), stderr);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }

    private static String yamlQuote(Path path) { return "'" + path.toString().replace("'", "''") + "'"; }
}
