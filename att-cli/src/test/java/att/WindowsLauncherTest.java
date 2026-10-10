/* Author: Jeffrey + ChatGPT */
package att;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
        assertTrue(text.contains("mvn -DskipTests -pl att-cli -am compile"));
        assertFalse(text.contains("where mvn"));
        assertFalse(text.contains("call mvn"));
        assertFalse(text.contains("javac"));
        assertFalse(text.contains("ATT_FORCE_JAVAC"));
        assertFalse(text.contains("test-classes"));
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

    @Test @EnabledOnOs(OS.WINDOWS)
    void sourceAndPackagedLaunchersNeverCompileWithMavenPresentOrAbsent() throws Exception {
        Path workspace = Paths.get("").toAbsolutePath();
        Path targetDirectory = workspace.resolve("target");
        Files.createDirectories(targetDirectory);
        Path temp = Files.createTempDirectory(targetDirectory, "windows-launcher-run-only-");
        Path buildToolLog = temp.resolve("build-tools.log");
        try {
            Path mavenPath = createWindowsPath(temp.resolve("path-with-maven"), true);
            Path noMavenPath = createWindowsPath(temp.resolve("path-without-maven"), false);
            Path packagedRoot = createPackagedLauncherRoot(temp.resolve("package"));
            Path sourceRoot = workspace;
            Path[] roots = {sourceRoot, packagedRoot};
            Path[] paths = {mavenPath, noMavenPath};
            String[][] commands = {{"version"}, {"help"}, {"debug"}, {"remote", "help"}};

            for (Path root : roots) {
                for (Path path : paths) {
                    for (String[] command : commands) {
                        ProcessResult result = invokeWindows(root, path, buildToolLog, command);
                        assertEquals(0, result.exitCode, result.output);
                    }
                }
            }

            Path missingRoot = temp.resolve("missing-source");
            Files.createDirectories(missingRoot);
            Files.copy(workspace.resolve("att.bat"), missingRoot.resolve("att.bat"));
            for (Path path : paths) {
                ProcessResult result = invokeWindows(missingRoot, path, buildToolLog, "version");
                assertEquals(2, result.exitCode, result.output);
                assertTrue(result.output.contains("missing required prebuilt classes"), result.output);
                assertTrue(result.output.contains("mvn -DskipTests -pl att-cli -am compile"), result.output);
            }
            assertFalse(Files.exists(buildToolLog), "The launcher attempted to invoke Maven or javac");
        } finally {
            try (Stream<Path> paths = Files.walk(temp)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static Path createWindowsPath(Path path, boolean mavenPresent) throws Exception {
        Files.createDirectories(path);
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java.exe");
        assertTrue(Files.isRegularFile(java), "Java executable not found: " + java);
        Files.write(path.resolve("java.cmd"), ("@echo off\r\n\"" + java + "\" %*\r\nexit /b %ERRORLEVEL%\r\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(path.resolve("javac.cmd"), buildToolShim("javac").getBytes(StandardCharsets.UTF_8));
        if (mavenPresent) Files.write(path.resolve("mvn.cmd"), buildToolShim("mvn").getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static String buildToolShim(String tool) {
        return "@echo off\r\necho " + tool + ">>\"%ATT_BUILD_TOOL_LOG%\"\r\nexit /b 97\r\n";
    }

    private static Path createPackagedLauncherRoot(Path root) throws Exception {
        Path lib = root.resolve("lib");
        Files.createDirectories(lib);
        Files.copy(Paths.get("att.bat"), root.resolve("att.bat"));
        String version = projectVersion();
        createApplicationJar(lib.resolve("att-" + version + ".jar"));
        for (String directory : new String[]{"config", "templates", "testcase", "load", "tools", "schemas"}) {
            Path source = Paths.get(directory);
            Path destination = root.resolve(directory);
            if (Files.isDirectory(source)) copyTree(source, destination);
            else Files.createDirectories(destination);
        }

        Path mavenRepository = Paths.get(System.getProperty("user.home"), ".m2", "repository");
        String[] runtimeJars = {
                "commons-io/commons-io/2.16.1/commons-io-2.16.1.jar",
                "org/apache/httpcomponents/httpclient/4.5.13/httpclient-4.5.13.jar",
                "org/apache/httpcomponents/httpcore/4.4.13/httpcore-4.4.13.jar",
                "commons-codec/commons-codec/1.11/commons-codec-1.11.jar",
                "commons-logging/commons-logging/1.2/commons-logging-1.2.jar",
                "com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar",
                "com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar",
                "com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar",
                "com/networknt/json-schema-validator/1.4.0/json-schema-validator-1.4.0.jar",
                "com/zaxxer/HikariCP/4.0.3/HikariCP-4.0.3.jar",
                "com/github/mwiede/jsch/2.28.2/jsch-2.28.2.jar",
                "com/ethlo/time/itu/1.8.0/itu-1.8.0.jar",
                "org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
                "org/slf4j/slf4j-nop/2.0.9/slf4j-nop-2.0.9.jar",
                "org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar",
                "org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar",
                "org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar",
                "org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
                "org/apache/xmlbeans/xmlbeans/5.2.0/xmlbeans-5.2.0.jar",
                "org/apache/poi/poi-ooxml/5.2.5/poi-ooxml-5.2.5.jar",
                "org/apache/poi/poi/5.2.5/poi-5.2.5.jar",
                "org/apache/poi/poi-ooxml-lite/5.2.5/poi-ooxml-lite-5.2.5.jar",
                "com/github/virtuald/curvesapi/1.08/curvesapi-1.08.jar",
                "org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar",
                "org/apache/logging/log4j/log4j-api/2.21.1/log4j-api-2.21.1.jar"
        };
        for (String runtimeJar : runtimeJars) {
            Path source = mavenRepository.resolve(runtimeJar);
            assertTrue(Files.isRegularFile(source), "Runtime dependency not found: " + source);
            Files.copy(source, lib.resolve(source.getFileName()));
        }
        return root;
    }

    private static String projectVersion() throws Exception {
        String pom = new String(Files.readAllBytes(Paths.get("pom.xml")), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("<artifactId>template-driven-api-testing-tool</artifactId>\\s*<version>([^<]+)")
                .matcher(pom);
        assertTrue(matcher.find(), "Parent project version was not found in pom.xml");
        return matcher.group(1);
    }

    private static void createApplicationJar(Path jarPath) throws Exception {
        Path[] classDirectories = {
                Paths.get("att-cli/target/classes"),
                Paths.get("att-engine/target/classes"),
                Paths.get("att-remote/target/classes"),
                Paths.get("att-server-api/target/classes")
        };
        Set<String> entries = new HashSet<String>();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Path classDirectory : classDirectories) {
                assertTrue(Files.isDirectory(classDirectory), "Compiled module classes not found: " + classDirectory);
                try (Stream<Path> files = Files.walk(classDirectory)) {
                    for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                        String entryName = classDirectory.relativize(file).toString().replace(File.separatorChar, '/');
                        if (!entries.add(entryName)) continue;
                        jar.putNextEntry(new JarEntry(entryName));
                        Files.copy(file, jar);
                        jar.closeEntry();
                    }
                }
            }
        }
    }

    private static void copyTree(Path source, Path destination) throws Exception {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static ProcessResult invokeWindows(Path root, Path path, Path buildToolLog, String... args) throws Exception {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) systemRoot = System.getenv("WINDIR");
        assertTrue(systemRoot != null, "SystemRoot is not defined");
        Path system32 = Paths.get(systemRoot, "System32");
        Files.createDirectories(root.resolve("target"));
        List<String> command = new ArrayList<String>();
        command.add(system32.resolve("cmd.exe").toString());
        command.add("/d");
        command.add("/c");
        command.add(root.resolve("att.bat").toString());
        command.addAll(java.util.Arrays.asList(args));
        Path stdout = Files.createTempFile(root.resolve("target"), "launcher-", ".out");
        Path stderr = Files.createTempFile(root.resolve("target"), "launcher-", ".err");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().put("PATH", path.toString() + ";" + system32);
        builder.environment().put("ATT_BUILD_TOOL_LOG", buildToolLog.toString());
        builder.environment().put("ORDERS_DB_USERNAME", "att-validation-placeholder");
        builder.environment().put("ORDERS_DB_PASSWORD", "att-validation-placeholder");
        builder.environment().put("PAYMENT_MQ_USERNAME", "att-validation-placeholder");
        builder.environment().put("PAYMENT_MQ_PASSWORD", "att-validation-placeholder");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "att.bat did not finish: " + command);
            String output = new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8)
                    + new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8);
            return new ProcessResult(process.exitValue(), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static String yamlQuote(Path path) { return "'" + path.toString().replace("'", "''") + "'"; }
}
