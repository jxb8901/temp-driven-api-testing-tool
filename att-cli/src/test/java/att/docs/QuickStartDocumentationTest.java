/* Author: Jeffrey + ChatGPT */
package att.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the beginner-first EN/ZH Quick Start introduced by issue #45. */
class QuickStartDocumentationTest {
    @TempDir Path temp;
    private int processCounter;

    @Test
    void quickStartAssetsAndLanguageEntryPointsStayAligned() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();

        for (String relative : Arrays.asList(
                "testcase/quick_start.xlsx",
                "testcase/quick_start.yaml",
                "testcase/quick_start.xml",
                "templates/QUICK_START/template.yaml",
                "docs/quick-start.md",
                "docs/quick-start.zh.md")) {
            assertTrue(Files.isRegularFile(root.resolve(relative)), "Missing Quick Start asset: " + relative);
        }

        String en = read(root.resolve("docs/quick-start.md"));
        String zh = read(root.resolve("docs/quick-start.zh.md"));
        String readme = read(root.resolve("README.md"));

        assertTrue(en.contains("(quick-start.zh.md)"), "English Quick Start must link to Chinese");
        assertTrue(zh.contains("(quick-start.md)"), "Chinese Quick Start must link to English");
        assertTrue(en.contains("testcase/quick_start.xlsx"));
        assertTrue(zh.contains("testcase/quick_start.xlsx"));
        assertTrue(en.contains("quickStart.default.QS001"));
        assertTrue(zh.contains("quickStart.default.QS001"));
        assertTrue(readme.contains("docs/quick-start.md"));
        assertTrue(readme.contains("docs/quick-start.zh.md"));

        assertLocalMarkdownLinksResolve(root.resolve("docs/quick-start.md"), root);
        assertLocalMarkdownLinksResolve(root.resolve("docs/quick-start.zh.md"), root);
    }

    @Test
    void quickStartCasesExecuteThroughTheRealCli() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        String config = root.resolve("config/config.yaml").toString();
        String suite = root.resolve("testcase/quick_start.xlsx").toString();

        CliResult basic = runCli(root, "run", "--config", config,
                "--suite", suite, "--case", "quickStart.default.QS001",
                "--output-dir", temp.resolve("basic-output").toString(),
                "--run-id", "quick-start-basic", "--format", "json", "--quiet");
        assertEquals(0, basic.exitCode, basic.stderr + "\n" + basic.stdout);

        CliResult tool = runCli(root, "run", "--config", config,
                "--suite", suite, "--case", "quickStart.default.QS002",
                "--output-dir", temp.resolve("tool-output").toString(),
                "--run-id", "quick-start-tool", "--format", "json", "--quiet");
        assertEquals(0, tool.exitCode, tool.stderr + "\n" + tool.stdout);
    }

    private void assertLocalMarkdownLinksResolve(Path markdown, Path root) throws Exception {
        Pattern pattern = Pattern.compile("(?<!!)\\[[^\\]]+\\]\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(read(markdown));
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (target.startsWith("http://") || target.startsWith("https://") ||
                    target.startsWith("mailto:") || target.startsWith("#")) {
                continue;
            }
            int hash = target.indexOf('#');
            if (hash >= 0) {
                target = target.substring(0, hash);
            }
            if (target.isEmpty()) {
                continue;
            }
            Path resolved = markdown.getParent().resolve(target).normalize();
            assertTrue(resolved.startsWith(root), "Quick Start link escapes repository: " + target);
            assertTrue(Files.exists(resolved), "Broken Quick Start link in " + markdown + ": " + target);
        }
    }

    private CliResult runCli(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "att.FrameworkRunner"));
        command.addAll(Arrays.asList(args));

        Path stdout = temp.resolve("quick-start-cli-" + (++processCounter) + ".stdout");
        Path stderr = temp.resolve("quick-start-cli-" + processCounter + ".stderr");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.environment().put("ORDERS_DB_USERNAME", "docs-example-user");
        builder.environment().put("ORDERS_DB_PASSWORD", "docs-example-password");
        builder.environment().put("PAYMENT_MQ_USERNAME", "docs-example-user");
        builder.environment().put("PAYMENT_MQ_PASSWORD", "docs-example-password");
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());

        Process process = builder.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "ATT CLI did not finish: " + Arrays.asList(args));
        return new CliResult(process.exitValue(), read(stdout), read(stderr));
    }

    private String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static final class CliResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CliResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
