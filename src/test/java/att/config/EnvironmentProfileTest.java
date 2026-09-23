/* Author: Jeffrey + ChatGPT */
package att.config;

import att.core.ExecutionOptions;
import att.validation.JsonSchemaVerifier;
import att.validation.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentProfileTest {
    @TempDir Path temp;
    private int processCounter;

    @Test
    void cliEnvironmentSelectorIsSharedByTheFourProfileAwareModes() {
        assertEquals("UAT", ExecutionOptions.parse(new String[]{"run", "--all", "--env", "UAT"}).environment());
        assertEquals("UAT", ExecutionOptions.parse(new String[]{"validate", "--package", "--env", "UAT"}).environment());
        assertEquals("UAT", ExecutionOptions.parse(new String[]{"debug", "template", "PAYMENT", "--env", "UAT"}).environment());
        assertEquals("UAT", ExecutionOptions.parse(new String[]{"load", "scenario.yaml", "--env", "UAT"}).environment());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"docs", "--env", "UAT"}));
    }

    @Test
    void checkedInProfilesBindStableLogicalDbAndMqIdsToDifferentPhysicalDescriptors() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        Map<?, ?> rootConfig = (Map<?, ?>) YamlSupport.load(root.resolve("config/config.yaml"));
        Map<?, ?> profiles = (Map<?, ?>) rootConfig.get("environments");
        Map<?, ?> sit = (Map<?, ?>) profiles.get("SIT");
        Map<?, ?> uat = (Map<?, ?>) profiles.get("UAT");

        assertEquals(Arrays.asList("config/dbhelpers/sit/orders.yaml"), sit.get("dbhelpers"));
        assertEquals(Arrays.asList("config/mqhelpers/sit/payment.yaml"), sit.get("mqhelpers"));
        assertEquals(Arrays.asList("config/dbhelpers/uat/orders.yaml"), uat.get("dbhelpers"));
        assertEquals(Arrays.asList("config/mqhelpers/uat/payment.yaml"), uat.get("mqhelpers"));

        Map<?, ?> sitDb = (Map<?, ?>) YamlSupport.load(root.resolve("config/dbhelpers/sit/orders.yaml"));
        Map<?, ?> uatDb = (Map<?, ?>) YamlSupport.load(root.resolve("config/dbhelpers/uat/orders.yaml"));
        Map<?, ?> sitMq = (Map<?, ?>) YamlSupport.load(root.resolve("config/mqhelpers/sit/payment.yaml"));
        Map<?, ?> uatMq = (Map<?, ?>) YamlSupport.load(root.resolve("config/mqhelpers/uat/payment.yaml"));
        assertEquals("orders", sitDb.get("id"));
        assertEquals("orders", uatDb.get("id"));
        assertEquals("payment", sitMq.get("id"));
        assertEquals("payment", uatMq.get("id"));
        assertNotEquals(connectionValue(sitDb, "url"), connectionValue(uatDb, "url"));
        assertNotEquals(connectionValue(sitMq, "host"), connectionValue(uatMq, "host"));
    }

    @Test
    void checkedInProfilesUseTheSameEffectiveContractInValidateDebugAndLoad() throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        String config = root.resolve("config/config.yaml").toString();
        for (String environment : Arrays.asList("SIT", "UAT")) {
            CliResult validation = runCli(root, "validate", "--config", config, "--env", environment,
                    "--package", "--format", "json", "--quiet");
            assertEquals(0, validation.exitCode, validation.stderr);
            @SuppressWarnings("unchecked") Map<String, Object> json = JsonSupport.mapper().readValue(validation.stdout, Map.class);
            assertEquals(Boolean.TRUE, json.get("valid"));
        }

        CliResult debug = runCli(root, "debug", "template", "PAYMENT_INVOKE", "--config", config,
                "--env", "SIT", "--output-dir", temp.resolve("debug-output").toString(), "--format", "json", "--quiet");
        assertEquals(0, debug.exitCode, debug.stderr);

        Path loadOutput = temp.resolve("load-output");
        CliResult load = runCli(root, "load", root.resolve("examples/load/closed-smoke.yaml").toString(),
                "--config", config, "--env", "UAT", "--output-dir", loadOutput.toString(),
                "--run-id", "profile-load", "--duration", "25ms", "--format", "json", "--quiet");
        assertEquals(0, load.exitCode, load.stderr);
        assertTrue(Files.isRegularFile(loadOutput.resolve("load/profile-load/load-summary.json")));
    }

    @Test
    void explicitSelectorOverridesDefaultAndReplacesOnlyTypedResourceLists() throws Exception {
        Path config = write("config.yaml", "schemaVersion: att-config/v2.6\n"
                + "environment: SIT\n"
                + "templates: {root: templates}\n"
                + "dbhelpers: [db/common.yaml]\n"
                + "mqhelpers: [mq/common.yaml]\n"
                + "environments:\n"
                + "  SIT:\n"
                + "    dbhelpers: [db/sit.yaml]\n"
                + "  UAT:\n"
                + "    dbhelpers: [db/uat.yaml]\n"
                + "    mqhelpers: [mq/uat.yaml]\n");
        write("db/sit.yaml", db("orders", "jdbc:sit"));
        write("db/uat.yaml", db("orders", "jdbc:uat"));
        write("db/common.yaml", db("audit", "jdbc:common"));
        write("mq/common.yaml", mq("payment", "sit-mq"));
        write("mq/uat.yaml", mq("payment", "uat-mq"));

        FrameworkConfig defaultConfig = new FrameworkConfigLoader().load(config, temp);
        FrameworkConfig uat = new FrameworkConfigLoader().load(config, temp, "uat");
        JsonSchemaVerifier.verify(Paths.get("schemas/att-config-v2.6.schema.json"), YamlSupport.load(config));

        assertEquals("SIT", defaultConfig.environment());
        assertEquals("jdbc:sit", defaultConfig.dbHelper("orders").url());
        assertEquals("sit-mq", defaultConfig.mqHelper("payment").host());
        assertEquals("UAT", uat.environment());
        assertEquals("jdbc:uat", uat.dbHelper("orders").url());
        assertEquals("uat-mq", uat.mqHelper("payment").host());
        assertTrue(uat.dbHelper("audit") == null, "a profile list replaces, rather than recursively merges, dbhelpers");
    }

    @Test
    void rejectsUnknownProfilesMissingDefaultAndUnsupportedOverlayFields() throws Exception {
        Path unknown = write("unknown.yaml", "schemaVersion: att-config/v2.6\n"
                + "environment: SIT\n"
                + "environments: {SIT: {}}\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(unknown, temp, "UAT"));

        Path missingDefault = write("missing-default.yaml", "schemaVersion: att-config/v2.6\n"
                + "environments: {SIT: {}}\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(missingDefault, temp));

        Path unsupported = write("unsupported.yaml", "schemaVersion: att-config/v2.6\n"
                + "environment: SIT\n"
                + "environments:\n"
                + "  SIT:\n"
                + "    report: {mode: none}\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(unsupported, temp));
    }

    @Test
    void profileDescriptorFailuresRemainPreExecutionConfigurationErrors() throws Exception {
        Path missing = write("missing-descriptor.yaml", "schemaVersion: att-config/v2.6\n"
                + "environment: SIT\n"
                + "environments: {SIT: {dbhelpers: [db/not-found.yaml]}}\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(missing, temp));

        write("db/one.yaml", db("orders", "jdbc:one"));
        write("db/two.yaml", db("orders", "jdbc:two"));
        Path duplicate = write("duplicate-descriptor.yaml", "schemaVersion: att-config/v2.6\n"
                + "environment: SIT\n"
                + "environments:\n"
                + "  SIT:\n"
                + "    dbhelpers: [db/one.yaml, db/two.yaml]\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(duplicate, temp));
    }

    @Test
    void legacyCompleteConfigRemainsCompatibleAndDoesNotAcceptEnvSelector() throws Exception {
        Path config = write("legacy.yaml", "schemaVersion: att-config/v2.5\nenvironment: SIT\n");
        assertEquals("SIT", new FrameworkConfigLoader().load(config, temp).environment());
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config, temp, "UAT"));
    }

    private Path write(String relative, String content) throws Exception {
        Path file = temp.resolve(relative);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String db(String id, String url) {
        return "schemaVersion: att-dbhelper/v2.5\n"
                + "id: " + id + "\nname: " + id + " DB\ndescription: " + id + " database\n"
                + "connection: {url: '" + url + "'}\n";
    }

    private String mq(String id, String host) {
        return "schemaVersion: att-mqhelper/v1.0\n"
                + "id: " + id + "\nname: " + id + " MQ\ndescription: " + id + " queue\n"
                + "connection: {queueManager: QM1, host: " + host + ", port: 1414, channel: APP.SVRCONN}\n";
    }

    private Object connectionValue(Map<?, ?> helper, String field) {
        return ((Map<?, ?>) helper.get("connection")).get(field);
    }

    private CliResult runCli(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "att.FrameworkRunner"));
        command.addAll(Arrays.asList(args));
        Path stdout = temp.resolve("cli-" + (++processCounter) + ".stdout");
        Path stderr = temp.resolve("cli-" + processCounter + ".stderr");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.environment().put("ORDERS_DB_USERNAME", "profile-test");
        builder.environment().put("ORDERS_DB_PASSWORD", "profile-test");
        builder.environment().put("PAYMENT_MQ_USERNAME", "profile-test");
        builder.environment().put("PAYMENT_MQ_PASSWORD", "profile-test");
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "ATT CLI did not finish: " + Arrays.asList(args));
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
