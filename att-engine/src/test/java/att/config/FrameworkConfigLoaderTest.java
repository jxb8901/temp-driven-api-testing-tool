/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkConfigLoaderTest {
    @TempDir Path tempDir;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(tempDir); }

    @Test void loadsRunAndDebugIdentityFormatsFromCurrentConfigSchema() throws Exception {
        Path config = write("identity-formats.yaml", "schemaVersion: att-config/v2.12\n"
                + "execution: {runIdFormat: 'run-${META.SOURCE.type}', debugIdFormat: 'debug-${META.TARGET.id}'}\n");
        FrameworkConfig loaded = new FrameworkConfigLoader().load(config, tempDir);
        assertEquals("run-${META.SOURCE.type}", loaded.run().runIdFormat());
        assertEquals("debug-${META.TARGET.id}", loaded.run().debugIdFormat());
        Path whitespace = write("blank-identity-format.yaml", "schemaVersion: att-config/v2.12\nexecution: {runIdFormat: '   '}\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(whitespace, tempDir));
    }

    @Test void ignoresLowercaseXProfilesAndToolsButKeepsTheirCanonicalRequirements() throws Exception {
        Path config = tempDir.resolve("x-config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.11\nenvironment: SIT\n"
                + "x-note: [ignored, without, interpretation]\n"
                + "report: {columns: {x-disabled-column: [not, a, label], result: Status}}\n"
                + "environments:\n  x-disabled-profile: not-a-profile\n  SIT: {x-disabled-resource-list: [not, paths]}\n"
                + "tools:\n  x-disabled-tool: [not, a, tool]\n  active:\n"
                + "    name: Active\n    description: Active Tool\n    call: \"#{upper('ok')}\"\n"
                + "    x-invalid-field: {anything: goes}\n").getBytes("UTF-8"));
        FrameworkConfig loaded = new FrameworkConfigLoader().load(config, tempDir);
        assertNotNull(loaded.tool("active"));
        assertNull(loaded.tool("x-disabled-tool"));
        assertEquals("Status", loaded.report().columns().get("result"));
        assertFalse(loaded.report().columns().containsKey("x-disabled-column"));

        Path group = tempDir.resolve("config/x-group.yaml");
        Files.createDirectories(group.getParent());
        Files.write(group, ("schemaVersion: att-tool-group/v2.9\nid: sample\nname: Sample\ndescription: Sample tools\n"
                + "tools:\n  x-disabled-tool: not-a-tool\n  active:\n"
                + "    name: Group Tool\n    description: Active Group Tool\n    call: \"#{upper('ok')}\"\n").getBytes("UTF-8"));
        Path groupConfig = tempDir.resolve("group-config.yaml");
        Files.write(groupConfig, "schemaVersion: att-config/v2.11\ntoolGroups: [config/x-group.yaml]\n".getBytes("UTF-8"));
        assertNotNull(new FrameworkConfigLoader().load(groupConfig, tempDir).tool("sample.active"));

        Path missingVersion = write("x-schema-version.yaml", "x-schemaVersion: att-config/v2.11\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(missingVersion, tempDir));
        Path uppercase = write("uppercase-prefix.yaml", "schemaVersion: att-config/v2.11\ntools: {X-disabled-tool: not-a-tool}\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(uppercase, tempDir));
    }

    @Test void treatsAnEnvironmentMapWithOnlyDisabledProfilesAsAbsent() throws Exception {
        Path config = write("disabled-profiles-only.yaml", "schemaVersion: att-config/v2.11\n"
                + "environments: {x-disabled-profile: not-a-profile}\n");

        FrameworkConfig loaded = new FrameworkConfigLoader().load(config, tempDir);

        assertEquals("SIT", loaded.environment());
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config, tempDir, "UAT"));
    }

    @Test void loadsDeclaredArgumentTypesAndEnumValuesFromCurrentSchemas() throws Exception {
        Path config = write("typed-tool.yaml", "schemaVersion: att-config/v2.11\n"
                + "tools:\n  typed:\n    name: Typed\n    description: Typed inputs\n"
                + "    command: [echo, '${input.mode}']\n    stdoutFormat: text\n"
                + "    arguments:\n      mode: {name: Mode, description: Mode, required: true, type: enum, enumValues: [SAFE, FAST]}\n");

        ToolArgumentConfig mode = new FrameworkConfigLoader().load(config, tempDir).tool("typed").arguments().get("mode");

        assertEquals("enum", mode.type());
        assertEquals(java.util.Arrays.asList("SAFE", "FAST"), mode.enumValues());
    }

    @Test void ignoresDisabledToolArgumentDeclarationsInGlobalAndGroupTools() throws Exception {
        String activeArgument = "    arguments:\n"
                + "      x-oldCustomerId: not-an-argument-descriptor\n"
                + "      customerId: {name: Customer ID, description: Active input, required: true}\n";
        Path globalConfig = write("root-tool-arguments.yaml", "schemaVersion: att-config/v2.11\n"
                + "tools:\n  rootEcho:\n"
                + "    name: Root echo\n    description: Root tool with one active argument\n"
                + "    command: [echo, '${input.customerId}']\n"
                + "    stdoutFormat: text\n"
                + activeArgument);
        FrameworkConfig global = new FrameworkConfigLoader().load(globalConfig, tempDir);
        assertEquals(java.util.Collections.singleton("customerId"), global.tool("rootEcho").arguments().keySet());

        Path group = tempDir.resolve("config/tools/argument-group.yaml");
        Files.createDirectories(group.getParent());
        Files.write(group, ("schemaVersion: att-tool-group/v2.9\nid: argumentGroup\n"
                + "name: Argument group\ndescription: Group with disabled argument declaration\n"
                + "tools:\n  groupedCall:\n"
                + "    name: Grouped call\n    description: Group tool with one active argument\n"
                + "    call: \"#{upper(${customerId})}\"\n"
                + activeArgument)
                .getBytes("UTF-8"));
        Path groupConfig = write("group-tool-arguments.yaml",
                "schemaVersion: att-config/v2.11\ntoolGroups: [config/tools/argument-group.yaml]\n");
        FrameworkConfig grouped = new FrameworkConfigLoader().load(groupConfig, tempDir);
        assertEquals(java.util.Collections.singleton("customerId"), grouped.tool("argumentGroup.groupedCall").arguments().keySet());

        Path disabledCommandReference = write("disabled-command-argument-reference.yaml", "schemaVersion: att-config/v2.11\n"
                + "tools:\n  invalid:\n"
                + "    name: Invalid command\n    description: References a disabled argument\n"
                + "    command: [echo, '${input.oldCustomerId}']\n"
                + "    stdoutFormat: text\n"
                + activeArgument);
        IllegalArgumentException commandFailure = assertThrows(IllegalArgumentException.class,
                () -> new FrameworkConfigLoader().load(disabledCommandReference, tempDir));
        assertTrue(commandFailure.getMessage().contains("declared argument"), commandFailure.getMessage());

        Path disabledCallReference = tempDir.resolve("config/tools/disabled-call-reference.yaml");
        Files.write(disabledCallReference, ("schemaVersion: att-tool-group/v2.9\nid: invalidCallGroup\n"
                + "name: Invalid call group\ndescription: Call references a disabled argument\n"
                + "tools:\n  invalid:\n"
                + "    name: Invalid call\n    description: References a disabled argument\n"
                + "    call: \"#{upper(${oldCustomerId})}\"\n"
                + activeArgument)
                .getBytes("UTF-8"));
        Path disabledCallConfig = write("disabled-call-argument-reference-config.yaml",
                "schemaVersion: att-config/v2.11\ntoolGroups: [config/tools/disabled-call-reference.yaml]\n");
        IllegalArgumentException callFailure = assertThrows(IllegalArgumentException.class,
                () -> new FrameworkConfigLoader().load(disabledCallConfig, tempDir));
        assertTrue(callFailure.getMessage().contains("declared argument"), callFailure.getMessage());
    }

    @Test void rejectsCaseInsensitiveDbHelperIdsAndInvalidTimeouts() throws Exception {
        Path helpers = tempDir.resolve("config/dbhelpers");
        Files.createDirectories(helpers);
        String base = "name: Orders\ndescription: Orders DB\nconnection: {url: 'jdbc:test'}\n";
        Files.write(helpers.resolve("one.yaml"), ("schemaVersion: att-dbhelper/v2.6\nid: orders\n" + base).getBytes("UTF-8"));
        Files.write(helpers.resolve("two.yaml"), ("schemaVersion: att-dbhelper/v2.6\nid: Orders\n" + base).getBytes("UTF-8"));
        Path duplicate = tempDir.resolve("config/duplicate.yaml");
        Files.write(duplicate, ("schemaVersion: att-config/v2.11\ndbhelpers: [config/dbhelpers/one.yaml, config/dbhelpers/two.yaml]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(duplicate, tempDir));

        Files.write(helpers.resolve("two.yaml"), ("schemaVersion: att-dbhelper/v2.6\nid: audit\n" + base
                + "statement: {timeoutSeconds: 0}\n").getBytes("UTF-8"));
        Path invalidTimeout = tempDir.resolve("config/invalid-timeout.yaml");
        Files.write(invalidTimeout, ("schemaVersion: att-config/v2.11\ndbhelpers: [config/dbhelpers/two.yaml]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidTimeout, tempDir));
    }

    @Test void helperExtensionsAreIgnoredButDatabasePropertiesRemainData() throws Exception {
        Path helper = tempDir.resolve("config/dbhelpers/extensions.yaml");
        Files.createDirectories(helper.getParent());
        Files.write(helper, ("schemaVersion: att-dbhelper/v2.6\nid: extensions\nname: Extensions\ndescription: Extensions\n"
                + "connection:\n  url: jdbc:test\n  x-disabled: [not, a, connection field]\n  properties: {x-correlation-id: retained}\n"
                + "statement: {timeoutSeconds: 10, x-disabled: invalid}\n"
                + "transaction: {scope: case, x-disabled: invalid}\n"
                + "result: {maxRows: 20, x-disabled: invalid}\n"
                + "evidence: {sql: hash, x-disabled: invalid, output: {format: json, x-disabled: invalid}}\n"
                + "pool: {maxSize: 2, x-disabled: invalid}\n").getBytes("UTF-8"));
        Path config = tempDir.resolve("db-extension-config.yaml");
        Files.write(config, "schemaVersion: att-config/v2.11\ndbhelpers: [config/dbhelpers/extensions.yaml]\n".getBytes("UTF-8"));

        DbHelperConfig loaded = new FrameworkConfigLoader().load(config, tempDir).dbHelper("extensions");
        assertEquals("retained", loaded.properties().get("x-correlation-id"));
        assertEquals("hash", loaded.evidenceSql());
        assertNotNull(loaded.evidenceOutput());
    }

    @Test void loadsV26CallBackedToolsAndKeepsV25CommandOnly() throws Exception {
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory.resolve("dbhelpers"));
        Files.createDirectories(configDirectory.resolve("tools"));
        Files.write(configDirectory.resolve("dbhelpers/orders.yaml"), ("schemaVersion: att-dbhelper/v2.6\n" +
                "id: orders\nname: Orders\ndescription: Orders DB\nconnection: {url: 'jdbc:test'}\n").getBytes("UTF-8"));
        Files.write(configDirectory.resolve("tools/orders.yaml"), ("schemaVersion: att-tool-group/v2.9\n" +
                "id: orderTools\nname: Order tools\ndescription: Typed order queries\n" +
                "tools:\n  find:\n    name: Find order\n    description: Find by two parameters\n" +
                "    timeoutMs: 4321\n" +
                "    cache: {scope: case}\n" +
                "    call: \"#{db.orders.query(sql='select * from orders where id = ? and status = ?', params=[${input.id}, #{upper(${input.status})}])}\"\n" +
                "    arguments:\n      id: {name: ID, description: Order ID, required: true}\n" +
                "      status: {name: Status, description: Order status, required: true}\n").getBytes("UTF-8"));
        Path config = configDirectory.resolve("config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.11\n" +
                "dbhelpers: [config/dbhelpers/orders.yaml]\n" +
                "toolGroups: [config/tools/orders.yaml]\n" +
                "tools:\n  today:\n    name: Today\n    description: Normalized date\n" +
                "    timeoutMs: 3210\n" +
                "    call: \"#{upper(${input.value})}\"\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));

        FrameworkConfig loaded = new FrameworkConfigLoader().load(config);
        assertTrue(loaded.tool("orderTools.find").callBacked());
        assertTrue(loaded.tool("orderTools.find").caseCached());
        assertEquals(Long.valueOf(4321), loaded.tool("orderTools.find").timeoutMs());
        assertEquals("db.orders.query", new att.template.ToolCallParser().parse(loaded.tool("orderTools.find").call()).name());
        assertTrue(loaded.tool("today").callBacked());
        assertEquals(Long.valueOf(3210), loaded.tool("today").timeoutMs());
        assertTrue(loaded.tool("today").commandArgv().isEmpty());
        assertEquals("values", loaded.dbHelpers().get("orders").evidenceParameters());

        Path legacy = tempDir.resolve("legacy-v25-call.yaml");
        Files.write(legacy, ("schemaVersion: att-config/v2.11\ntools:\n  bad:\n" +
                "    name: Bad\n    description: Bad\n    call: '#{upper(input.value)}'\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(legacy));
    }

    @Test void rejectsAmbiguousAndProcessOnlyCallBackedToolFields() throws Exception {
        String prefix = "schemaVersion: att-config/v2.11\ntools:\n  bad:\n    name: Bad\n    description: Bad\n";
        FrameworkConfig shorthand = new FrameworkConfigLoader().load(write("call-shorthand.yaml",
                prefix + "    call: '#{upper(${value})}'\n" +
                        "    arguments:\n      value: {name: Value, description: Value, required: true}\n"));
        assertTrue(shorthand.tool("bad").callBacked());
        IllegalArgumentException bareInput = assertThrows(IllegalArgumentException.class,
                () -> new FrameworkConfigLoader().load(write("bare-input.yaml",
                        prefix + "    call: '#{upper(input.value)}'\n" +
                                "    arguments:\n      value: {name: Value, description: Value, required: true}\n")));
        assertTrue(bareInput.getMessage().contains("${input.value}"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("both.yaml",
                prefix + "    command: [echo]\n    call: '#{upper(${input.value})}'\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("output.yaml",
                prefix + "    call: '#{upper(${input.value})}'\n    output: json\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("context.yaml",
                prefix + "    call: '#{upper(${CASE.value})}'\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("nested-context.yaml",
                prefix + "    call: \"#{db.orders.query(sql='select ?', params=[#{upper(${CASE.value})}])}\"\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("dynamic-sql-file.yaml",
                prefix + "    call: '#{db.orders.query(sqlFile=${input.file}, params=[])}'\n" +
                        "    arguments:\n      file: {name: File, description: SQL file, required: true}\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("argv.yaml",
                prefix + "    call: '#{upper(${input.value})}'\n    arguments:\n" +
                        "      value: {name: Value, description: Value, required: true, argName: --value}\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("cached-update.yaml",
                prefix + "    call: \"#{db.orders.update(sql='update t set v=1')}\"\n    cache: {scope: db}\n")));
    }

    @Test void loadsV25DbHelperInstancesFromDedicatedFiles() throws Exception {
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory.resolve("dbhelpers"));
        Files.write(configDirectory.resolve("dbhelpers/orders.yaml"), ("schemaVersion: att-dbhelper/v2.6\n" +
                "id: orders\nname: Orders DB\ndescription: Order queries\n" +
                "connection:\n  url: jdbc:test:orders\n  username: att\n  password: local\n" +
                "statement: {timeoutSeconds: 7}\n" +
                "transaction: {scope: case, onEnd: commit}\n" +
                "result: {maxRows: 25, maxCellBytes: 1024, maxBytes: 4096}\n").getBytes("UTF-8"));
        Files.write(configDirectory.resolve("dbhelpers/audit.yaml"), ("schemaVersion: att-dbhelper/v2.6\n" +
                "id: audit\nname: Audit DB\ndescription: Audit queries\n" +
                "connection: {url: 'jdbc:test:audit', readOnly: true}\n" +
                "transaction: {scope: statement, onEnd: rollback}\n").getBytes("UTF-8"));
        Path config = configDirectory.resolve("config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.11\n" +
                "dbhelpers: [config/dbhelpers/orders.yaml, config/dbhelpers/audit.yaml]\n").getBytes("UTF-8"));

        FrameworkConfig loaded = new FrameworkConfigLoader().load(config);
        assertEquals(7, loaded.dbHelper("orders").timeoutSeconds());
        assertEquals(25, loaded.dbHelper("orders").maxRows());
        assertEquals("case", loaded.dbHelper("orders").transactionScope());
        assertEquals("commit", loaded.dbHelper("orders").transactionOnEnd());
        assertTrue(loaded.dbHelper("AUDIT").readOnly());
        assertEquals("statement", loaded.dbHelper("audit").transactionScope());
        assertEquals("rollback", loaded.dbHelper("audit").transactionOnEnd());

        Path invalid = configDirectory.resolve("invalid.yaml");
        Files.write(invalid, ("schemaVersion: att-config/v2.11\n" +
                "dbhelpers: [config/dbhelpers/orders.yaml, config/dbhelpers/orders.yaml]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalid));
    }
    @Test void validatesBuiltInsInReportAndToolCommandScopes() throws Exception {
        Path valid = tempDir.resolve("expressions.yaml");
        Files.write(valid, ("schemaVersion: att-config/v2.11\n" +
                "report: {fileNamePattern: \"#{upper(${suiteName})}.result.xlsx\"}\n" +
                "tools:\n  echo:\n    name: Echo\n    description: Echo\n" +
                "    command: [echo, \"#{trim(${input.value})}\"]\n" +
                "    stdoutFormat: text\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        FrameworkConfig config = new FrameworkConfigLoader().load(valid);
        assertEquals("#{upper(${suiteName})}.result.xlsx", config.report().fileNamePattern());

        Path invalidReport = tempDir.resolve("invalid-report-expression.yaml");
        Files.write(invalidReport, "schemaVersion: att-config/v2.11\nreport: {fileNamePattern: \"${suiteName}-#{external()}.xlsx\"}\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidReport));

        Path bareReport = tempDir.resolve("bare-report-expression.yaml");
        Files.write(bareReport, "schemaVersion: att-config/v2.11\nreport: {fileNamePattern: \"#{upper(suiteName)}-${suiteName}.xlsx\"}\n".getBytes("UTF-8"));
        IllegalArgumentException bareReportError = assertThrows(IllegalArgumentException.class,
                () -> new FrameworkConfigLoader().load(bareReport));
        assertTrue(bareReportError.getMessage().contains("${suiteName}"));

        Path incompatibleReport = tempDir.resolve("uppercase-report-expression.yaml");
        Files.write(incompatibleReport, "schemaVersion: att-config/v2.11\nreport: {fileNamePattern: \"${SUITE_NAME}.result.xlsx\"}\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(incompatibleReport));

        Path invalidCommand = tempDir.resolve("invalid-command-expression.yaml");
        Files.write(invalidCommand, ("schemaVersion: att-config/v2.11\ntools:\n  echo:\n    name: Echo\n    description: Echo\n" +
                "    command: [echo, \"#{external(${value})}\"]\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidCommand));

        Path invalidScopedPath = tempDir.resolve("invalid-command-scope.yaml");
        Files.write(invalidScopedPath, ("schemaVersion: att-config/v2.11\ntools:\n  echo:\n    name: Echo\n    description: Echo\n" +
                "    command: [echo, \"#{upper(${input.missing})}\"]\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidScopedPath));

        Path bareArray = tempDir.resolve("bare-command-array.yaml");
        Files.write(bareArray, ("schemaVersion: att-config/v2.11\ntools:\n  echo:\n    name: Echo\n    description: Echo\n" +
                "    command: [echo, \"#{concat(value=[input.value])}\"]\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        IllegalArgumentException bareArrayError = assertThrows(IllegalArgumentException.class,
                () -> new FrameworkConfigLoader().load(bareArray));
        assertNotNull(bareArrayError.getMessage());
    }

    @Test void loadsV2AndRejectsGlobalStages() throws Exception {
        Path ok=tempDir.resolve("ok.yaml"); Files.write(ok,"schemaVersion: att-config/v2.11\noutputDirectory: out\ntools: {}\n".getBytes("UTF-8"));
        assertEquals(Paths.get("out"),new FrameworkConfigLoader().load(ok).outputDirectory());
        assertEquals(10000, new FrameworkConfigLoader().load(ok).timeoutMs());
        Path rooted=tempDir.resolve("rooted.yaml"); Files.write(rooted,"schemaVersion: att-config/v2.11\ntestcase: {root: cases/nested}\ncaseLog: {yamlAnchors: true}\ntimeoutMs: 3600000\n".getBytes("UTF-8"));
        FrameworkConfig rootedConfig = new FrameworkConfigLoader().load(rooted);
        assertEquals(Paths.get("cases/nested"), rootedConfig.testcasesRoot());
        assertEquals(3600000, rootedConfig.timeoutMs());
        assertTrue(rootedConfig.caseLogYamlAnchors());
        assertFalse(new FrameworkConfigLoader().load(ok).caseLogYamlAnchors());
        Path excessive=tempDir.resolve("excessive.yaml"); Files.write(excessive,"schemaVersion: att-config/v2.11\ntimeoutMs: 3600001\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(excessive));
        Path mismatch=tempDir.resolve("mismatch.yaml"); Files.write(mismatch,("schemaVersion: att-config/v2.11\ntools:\n  find:\n    name: 顯示 名稱 !\n    description: test\n    command: 'echo ${KeyWords}'\n    stdoutFormat: text\n    arguments:\n      keywords: {name: 關鍵 字詞 !, description: test, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(mismatch));
        Path direct=tempDir.resolve("direct.yaml"); Files.write(direct,("schemaVersion: att-config/v2.11\ntools:\n  find:\n    name: 顯示 名稱 !\n    description: test\n    command: 'echo ${keywords} ${input.keywords}'\n    stdoutFormat: text\n    arguments:\n      keywords: {name: 關鍵 字詞 !, description: test, required: true}\n").getBytes("UTF-8"));
        ToolConfig legacyScalar = new FrameworkConfigLoader().load(direct).tool("find");
        assertEquals("顯示 名稱 !", legacyScalar.name());
        assertEquals(java.util.Arrays.asList("echo", "${keywords}", "${input.keywords}"), legacyScalar.commandArgv());
        Path hiddenContext=tempDir.resolve("hidden-context.yaml"); Files.write(hiddenContext,("schemaVersion: att-config/v2.11\ntools:\n  find:\n    name: Find\n    description: test\n    command: 'echo ${CASE.caseId}'\n    arguments: {}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(hiddenContext));
        Path bad=tempDir.resolve("bad.yaml"); Files.write(bad,"stages: []\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(bad));
    }


    @Test void loadsV22ArgvGroupsAndSshTargets() throws Exception {
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory.resolve("tools"));
        Files.write(configDirectory.resolve("tools/database.yaml"), ("schemaVersion: att-tool-group/v2.9\n" +
                "id: database\nname: Database\ndescription: Database tools\n" +
                "script: [./tools/dispatch.sh, --read-only]\n" +
                "ssh: {host: db.example, user: att, port: 2222, identityFile: keys/id_ed25519}\n" +
                "tools:\n  select:\n    name: Select\n    description: Query row\n" +
                "    command: [query, '${id}']\n    stdoutFormat: json\n" +
                "    arguments:\n      id: {name: ID, description: Row ID, required: true, argName: --id}\n").getBytes("UTF-8"));
        Files.write(configDirectory.resolve("tools/logs.yaml"), ("schemaVersion: att-tool-group/v2.9\n" +
                "id: logs\nname: Logs\ndescription: Log tools\n" +
                "tools:\n  tail:\n    name: Tail\n    description: Tail logs\n    command: [./tools/tail.sh]\n    stdoutFormat: text\n").getBytes("UTF-8"));
        Files.write(configDirectory.resolve("config.yaml"), ("schemaVersion: att-config/v2.11\n" +
                "toolGroups: [config/tools/database.yaml, config/tools/logs.yaml]\n" +
                "ssh: {host: global.example, user: runner}\n" +
                "tools:\n  echo:\n    name: Echo\n    description: Echo value\n" +
                "    command:\n      - /usr/bin/printf\n      - '%s\\n'\n      - '${value}'\n    stdoutFormat: text\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true, argName: ''}\n").getBytes("UTF-8"));
        FrameworkConfig config = new FrameworkConfigLoader().load(configDirectory.resolve("config.yaml"));
        assertEquals(java.util.Arrays.asList("/usr/bin/printf", "%s\\n", "${value}"), config.tool("echo").commandArgv());
        assertEquals("", config.tool("echo").arguments().get("value").argName());
        assertEquals("runner@global.example", config.tool("echo").ssh().destination());
        ToolConfig grouped = config.tool("database.select");
        assertEquals("database", grouped.groupId());
        assertEquals("select", grouped.localKey());
        assertEquals(java.util.Arrays.asList("./tools/dispatch.sh", "--read-only"), grouped.groupScriptArgv());
        assertEquals(java.util.Arrays.asList("query", "${id}"), grouped.commandArgv());
        assertEquals("--id", grouped.arguments().get("id").argName());
        assertEquals(2222, grouped.ssh().port());
        assertEquals(configDirectory.resolve("tools/database.yaml").toRealPath(), grouped.sourceFile());
        assertNotNull(config.tool("logs.tail"));
        assertNull(config.tool("logs.tail").ssh());
    }

    @Test void rejectsInvalidArgNameDefinitions() throws Exception {
        String prefix = "schemaVersion: att-config/v2.11\ntools:\n  sample:\n    name: Sample\n    description: Sample\n";
        Path embedded = tempDir.resolve("embedded.yaml");
        Files.write(embedded, (prefix + "    command: [echo, 'value=${value}']\n    arguments:\n      value: {name: Value, description: Value, required: false, argName: --value}\n").getBytes("UTF-8"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(embedded)).getMessage() != null);

        Path duplicate = tempDir.resolve("duplicate-arg-name.yaml");
        Files.write(duplicate, (prefix + "    command: [echo, '${value}', '${input.value}']\n    arguments:\n      value: {name: Value, description: Value, required: false, argName: --value}\n").getBytes("UTF-8"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(duplicate)).getMessage() != null);

        Path unused = tempDir.resolve("unused-arg-name.yaml");
        Files.write(unused, (prefix + "    command: [echo]\n    arguments:\n      value: {name: Value, description: Value, required: false, argName: --value}\n").getBytes("UTF-8"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(unused)).getMessage() != null);

        Path invalidMode = tempDir.resolve("invalid-arg-name-mode.yaml");
        Files.write(invalidMode, (prefix + "    command: [echo, '${value}']\n    arguments:\n      value: {name: Value, description: Value, required: false, argName: --value, argNameMode: sometimes}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidMode));
    }

    @Test void reportsToolConfigurationCodeFileFieldAndRepairHint() throws Exception {
        Path config = tempDir.resolve("bad-tool.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.11\n" +
                "tools:\n  sample:\n    name: Sample\n    description: Sample\n" +
                "    command: [echo, '${missing}']\n" +
                "    stdoutFormat: text\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));

        att.validation.DiagnosticException error = assertThrows(att.validation.DiagnosticException.class,
                () -> new FrameworkConfigLoader().load(config));

        assertEquals(att.validation.DiagnosticCodes.TOOL_INVALID, error.code());
        assertEquals("$ATT_HOME/bad-tool.yaml", error.file());
        assertTrue(error.field().contains("tools"));
        assertTrue(error.detail().contains("missing"));
        assertNotNull(error.suggestion());
    }

    @Test void loadsMultipleDelimitedArgumentsAndArgNameModes() throws Exception {
        Path config = tempDir.resolve("multiple-delimited.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.11\n" +
                "tools:\n  capture:\n    name: Capture\n    description: Capture lists\n" +
                "    command: [capture, '${keywords}', '${types}']\n" +
                "    stdoutFormat: text\n" +
                "    arguments:\n" +
                "      keywords: {name: Keywords, description: Search words, required: true, delimit: ',', argName: --keyword, argNameMode: repeat}\n" +
                "      types: {name: Types, description: Transaction types, required: true, delimit: '|', argName: --types}\n").getBytes("UTF-8"));

        ToolConfig tool = new FrameworkConfigLoader().load(config).tool("capture");

        assertEquals(",", tool.arguments().get("keywords").delimit());
        assertEquals("repeat", tool.arguments().get("keywords").argNameMode());
        assertEquals("|", tool.arguments().get("types").delimit());
        assertEquals("once", tool.arguments().get("types").argNameMode());
    }

    @Test void v26RejectsLegacyDelimitAndReservedQualifiedBuiltInNames() throws Exception {
        Path delimiter = write("v26-delimit.yaml", "schemaVersion: att-config/v2.11\ntools:\n  capture:\n    name: Capture\n    description: Capture\n    command: [capture, '${values}']\n    arguments:\n      values: {name: Values, description: Values, required: true, delimit: ','}\n");
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(delimiter));

        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory.resolve("tools"));
        Files.write(configDirectory.resolve("tools/str.yaml"), ("schemaVersion: att-tool-group/v2.9\nid: str\nname: Strings\ndescription: Strings\ntools:\n  custom:\n    name: Custom\n    description: Reserved package\n    command: [echo]\n").getBytes("UTF-8"));
        Path reserved = configDirectory.resolve("reserved-qualified.yaml");
        Files.write(reserved, "schemaVersion: att-config/v2.11\ntoolGroups: [config/tools/str.yaml]\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(reserved));
    }

    @Test void rejectsDuplicateGroupIdsUnsafePathsAndReservedGlobalNames() throws Exception {
        Path configDirectory = tempDir.resolve("config"); Files.createDirectories(configDirectory.resolve("tools"));
        String group = "schemaVersion: att-tool-group/v2.9\nid: duplicate\nname: Group\ndescription: Group\ntools:\n  echo:\n    name: Echo\n    description: Echo\n    command: [echo]\n";
        Files.write(configDirectory.resolve("tools/one.yaml"), group.getBytes("UTF-8"));
        Files.write(configDirectory.resolve("tools/two.yaml"), group.getBytes("UTF-8"));
        Path duplicate = configDirectory.resolve("duplicate.yaml");
        Files.write(duplicate, "schemaVersion: att-config/v2.11\ntoolGroups: [config/tools/one.yaml, config/tools/two.yaml]\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(duplicate));
        Path unsafe = configDirectory.resolve("unsafe.yaml");
        Files.write(unsafe, "schemaVersion: att-config/v2.11\ntoolGroups: [../outside.yaml]\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(unsafe));
        Path reserved = configDirectory.resolve("reserved.yaml");
        Files.write(reserved, ("schemaVersion: att-config/v2.11\ntools:\n  nvl:\n    name: NVL\n    description: reserved\n    command: [echo]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(reserved));
    }

    @Test void loadsPerformanceHardeningLimitsAndWorkbookDisableMode() throws Exception {
        Path config = tempDir.resolve("performance.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.11\n" +
                "execution:\n  processOutput: {memoryLimitBytes: 4096, artifactLimitBytes: 8192}\n" +
                "report:\n  mode: none\n  html: {caseLogInlineLimitBytes: 2048}\n  junit: {caseLogEmbedThresholdBytes: 1024}\n").getBytes("UTF-8"));
        FrameworkConfig loaded = new FrameworkConfigLoader().load(config);
        assertEquals(4096, loaded.processOutput().memoryLimitBytes());
        assertEquals(8192, loaded.processOutput().artifactLimitBytes());
        assertEquals("none", loaded.report().mode());
        assertEquals(2048, loaded.report().htmlCaseLogInlineLimitBytes());
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("bad-performance.yaml", "schemaVersion: att-config/v2.11\nexecution: {processOutput: {memoryLimitBytes: 4096, artifactLimitBytes: 2048}}\n")));
    }

    private Path write(String name, String content) throws Exception { Path file = tempDir.resolve(name); Files.write(file, content.getBytes("UTF-8")); return file; }
}
