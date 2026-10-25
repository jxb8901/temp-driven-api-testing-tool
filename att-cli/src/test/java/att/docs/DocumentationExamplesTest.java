/* Author: Jeffrey + ChatGPT */
package att.docs;

import att.config.YamlSupport;
import att.validation.JsonSchemaVerifier;
import att.validation.SchemaFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Release-gate coverage for the real assets cited by the end-user documentation. */
class DocumentationExamplesTest {
    @TempDir Path temp;
    private int processCounter;

    @Test
    void taggedCurrentDocumentationExamplesMatchManifestAndEmbeddedSchemas() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Path manifestPath = root.resolve("docs/example-contracts.json");
        JsonNode manifest = new ObjectMapper().readTree(manifestPath.toFile());
        assertEquals("att-doc-examples/v1", manifest.path("schemaVersion").asText());
        assertTrue(manifest.path("examples").isArray(), "example contract manifest must contain an examples array");

        Set<String> ids = new HashSet<String>();
        for (JsonNode entry : manifest.path("examples")) {
            String id = entry.path("id").asText();
            String sourceName = entry.path("source").asText();
            String schemaVersion = entry.path("schema").asText();
            String format = entry.path("format").asText();
            assertTrue(ids.add(id), "duplicate documentation example id: " + id);
            assertEquals("current", entry.path("status").asText(), "only Current examples enter this test");

            Path source = root.resolve(sourceName).normalize();
            assertTrue(source.startsWith(root), "documentation example source escapes the checkout: " + sourceName);
            TaggedExample example = taggedExample(source, id);
            assertEquals(schemaVersion, example.schema, "schema marker drift for " + id);
            assertEquals(format, example.format, "format marker drift for " + id);

            Object value = "json".equals(format)
                    ? new ObjectMapper().readTree(example.content)
                    : YamlSupport.parser().load(example.content);
            Path schema = SchemaFiles.resolveVersion(root, schemaVersion);
            JsonSchemaVerifier.verify(schema, value);
        }
        assertEquals(4, ids.size(), "update the manifest and schema test when Current tagged examples change");
    }

    @Test
    void checkedInEnvironmentAndResourceExamplesValidateThroughTheRealCli() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        String common = root.resolve("config/config.yaml").toString();

        assertAsset(root, "config/environments/sit.yaml");
        assertAsset(root, "config/environments/uat.yaml");
        assertAsset(root, "config/dbhelpers/sit/orders.yaml");
        assertAsset(root, "config/dbhelpers/uat/orders.yaml");
        assertAsset(root, "config/mqhelpers/sit/payment.yaml");
        assertAsset(root, "config/mqhelpers/uat/payment.yaml");
        assertAsset(root, "config/tools/sample.yaml");
        assertAsset(root, "config/tools/orders-db.yaml");
        assertAsset(root, "packages/default/testdata/accounts.yaml");

        for (String environment : Arrays.asList("SIT", "UAT")) {
            CliResult result = runCli(root, "validate", "--config", common, "--env", environment,
                    "--package", "--format", "json", "--quiet");
            assertEquals(0, result.exitCode, result.stderr);
            assertTrue(result.stdout.contains("\"valid\":true") || result.stdout.contains("\"valid\" : true"), result.stdout);
        }

        for (String config : Arrays.asList("config/environments/sit.yaml", "config/environments/uat.yaml")) {
            CliResult result = runCli(root, "validate", "--config", root.resolve(config).toString(),
                    "--package", "--format", "json", "--quiet");
            assertEquals(0, result.exitCode, result.stderr);
        }

        String commandBacked = read(root.resolve("config/tools/sample.yaml"));
        String callBacked = read(root.resolve("config/tools/orders-db.yaml"));
        assertTrue(commandBacked.contains("command:"), "sample.yaml must remain the documented command-backed Tool example");
        assertTrue(callBacked.contains("call:"), "orders-db.yaml must remain the documented call-backed Tool example");
    }

    @Test
    void documentedRunDebugAndLoadEntryPointsRemainExecutable() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        String config = root.resolve("config/config.yaml").toString();

        assertAsset(root, "packages/default/testcase/payment2.xlsx");
        assertAsset(root, "packages/default/templates/PAYMENT_INVOKE/debug.yaml");
        assertAsset(root, "examples/load/closed-smoke.yaml");
        assertAsset(root, "examples/load/arrival-smoke.yaml");

        CliResult run = runCli(root, "run", "--config", config, "--env", "SIT",
                "--suite", root.resolve("packages/default/testcase/payment2.xlsx").toString(), "--dry-run",
                "--output-dir", temp.resolve("run-output").toString(), "--run-id", "docs-run",
                "--format", "json", "--quiet");
        assertEquals(0, run.exitCode, run.stderr);

        CliResult debug = runCli(root, "debug", "template", "PAYMENT_INVOKE", "--config", config,
                "--env", "SIT", "--output-dir", temp.resolve("debug-output").toString(),
                "--format", "json", "--quiet");
        assertEquals(0, debug.exitCode, debug.stderr);

        CliResult closed = runCli(root, "load", root.resolve("examples/load/closed-smoke.yaml").toString(),
                "--config", config, "--env", "SIT", "--output-dir", temp.resolve("closed-output").toString(),
                "--run-id", "docs-closed", "--format", "json", "--quiet");
        assertEquals(0, closed.exitCode, closed.stderr);

        CliResult arrival = runCli(root, "load", root.resolve("examples/load/arrival-smoke.yaml").toString(),
                "--config", config, "--env", "UAT", "--output-dir", temp.resolve("arrival-output").toString(),
                "--run-id", "docs-arrival", "--format", "json", "--quiet");
        assertEquals(0, arrival.exitCode, arrival.stderr);
    }

    private void assertAsset(Path root, String relative) {
        assertTrue(Files.isRegularFile(root.resolve(relative)), "Missing documented asset: " + relative);
    }

    private TaggedExample taggedExample(Path source, String id) throws Exception {
        String[] lines = read(source).split("\\r?\\n");
        Pattern marker = Pattern.compile("^<!-- att-doc-example: current id=([a-z0-9-]+) schema=([^ ]+) format=(json|yaml) -->$");
        int found = 0;
        TaggedExample result = null;
        for (int index = 0; index < lines.length; index++) {
            Matcher tagged = marker.matcher(lines[index].trim());
            if (!tagged.matches() || !id.equals(tagged.group(1))) continue;
            found++;
            int fenceIndex = index + 1;
            while (fenceIndex < lines.length && lines[fenceIndex].trim().isEmpty()) fenceIndex++;
            assertTrue(fenceIndex < lines.length, "missing code block after documentation example tag " + id);
            Matcher fence = Pattern.compile("^```(json|yaml|yml)\\s*$").matcher(lines[fenceIndex].trim());
            assertTrue(fence.matches(), "expected a JSON/YAML code block after documentation example tag " + id);
            String blockFormat = "yml".equals(fence.group(1)) ? "yaml" : fence.group(1);
            StringBuilder content = new StringBuilder();
            int end = fenceIndex + 1;
            while (end < lines.length && !"```".equals(lines[end].trim())) {
                if (content.length() > 0) content.append('\n');
                content.append(lines[end]);
                end++;
            }
            assertTrue(end < lines.length, "unterminated documentation example code block " + id);
            result = new TaggedExample(tagged.group(2), blockFormat, content.toString());
        }
        assertEquals(1, found, "expected exactly one Current example tag for " + id + " in " + source);
        return result;
    }

    private CliResult runCli(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "att.FrameworkRunner"));
        command.addAll(Arrays.asList(args));

        Path stdout = temp.resolve("docs-cli-" + (++processCounter) + ".stdout");
        Path stderr = temp.resolve("docs-cli-" + processCounter + ".stderr");
        ProcessBuilder builder = new ProcessBuilder(command);
        Path packageRoot = root.resolve("packages/default");
        builder.directory(Files.isDirectory(packageRoot) ? packageRoot.toFile() : root.toFile());
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

    private static final class TaggedExample {
        private final String schema;
        private final String format;
        private final String content;

        private TaggedExample(String schema, String format, String content) {
            this.schema = schema;
            this.format = format;
            this.content = content;
        }
    }
}
