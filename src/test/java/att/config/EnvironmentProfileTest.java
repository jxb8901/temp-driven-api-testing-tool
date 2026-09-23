/* Author: Jeffrey + ChatGPT */
package att.config;

import att.core.ExecutionOptions;
import att.validation.JsonSchemaVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentProfileTest {
    @TempDir Path temp;

    @Test
    void cliEnvironmentSelectorIsSharedByTheFourProfileAwareModes() {
        assertEquals("UAT", ExecutionOptions.parse(new String[]{"run", "--all", "--env", "UAT"}).environment());
        assertEquals("UAT", ExecutionOptions.parse(new String[]{"validate", "--package", "--env", "UAT"}).environment());
        assertEquals("UAT", ExecutionOptions.parse(new String[]{"debug", "template", "PAYMENT", "--env", "UAT"}).environment());
        assertEquals("UAT", ExecutionOptions.parse(new String[]{"load", "scenario.yaml", "--env", "UAT"}).environment());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"docs", "--env", "UAT"}));
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
}
