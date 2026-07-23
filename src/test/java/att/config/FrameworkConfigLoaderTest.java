/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkConfigLoaderTest {
    @TempDir Path tempDir;

    @Test void rejectsCaseInsensitiveDbHelperIdsAndInvalidTimeouts() throws Exception {
        Path helpers = tempDir.resolve("config/dbhelpers");
        Files.createDirectories(helpers);
        String base = "name: Orders\ndescription: Orders DB\nconnection: {url: 'jdbc:test'}\n";
        Files.write(helpers.resolve("one.yaml"), ("schemaVersion: att-dbhelper/v2.5\nid: orders\n" + base).getBytes("UTF-8"));
        Files.write(helpers.resolve("two.yaml"), ("schemaVersion: att-dbhelper/v2.5\nid: Orders\n" + base).getBytes("UTF-8"));
        Path duplicate = tempDir.resolve("config/duplicate.yaml");
        Files.write(duplicate, ("schemaVersion: att-config/v2.5\ndbhelpers: [config/dbhelpers/one.yaml, config/dbhelpers/two.yaml]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(duplicate, tempDir));

        Files.write(helpers.resolve("two.yaml"), ("schemaVersion: att-dbhelper/v2.5\nid: audit\n" + base
                + "statement: {timeoutSeconds: 0}\n").getBytes("UTF-8"));
        Path invalidTimeout = tempDir.resolve("config/invalid-timeout.yaml");
        Files.write(invalidTimeout, ("schemaVersion: att-config/v2.5\ndbhelpers: [config/dbhelpers/two.yaml]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidTimeout, tempDir));
    }

    @Test void loadsV26CallBackedToolsAndKeepsV25CommandOnly() throws Exception {
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory.resolve("dbhelpers"));
        Files.createDirectories(configDirectory.resolve("tools"));
        Files.write(configDirectory.resolve("dbhelpers/orders.yaml"), ("schemaVersion: att-dbhelper/v2.5\n" +
                "id: orders\nname: Orders\ndescription: Orders DB\nconnection: {url: 'jdbc:test'}\n").getBytes("UTF-8"));
        Files.write(configDirectory.resolve("tools/orders.yaml"), ("schemaVersion: att-tool-group/v2.6\n" +
                "id: orderTools\nname: Order tools\ndescription: Typed order queries\n" +
                "tools:\n  find:\n    name: Find order\n    description: Find by two parameters\n" +
                "    cache: {scope: case}\n" +
                "    call: \"#{db.orders.query(sql='select * from orders where id = ? and status = ?', params=[input.id, #{upper(input.status)}])}\"\n" +
                "    arguments:\n      id: {name: ID, description: Order ID, required: true}\n" +
                "      status: {name: Status, description: Order status, required: true}\n").getBytes("UTF-8"));
        Path config = configDirectory.resolve("config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.6\n" +
                "dbhelpers: [config/dbhelpers/orders.yaml]\n" +
                "toolGroups: [config/tools/orders.yaml]\n" +
                "tools:\n  today:\n    name: Today\n    description: Normalized date\n" +
                "    call: \"#{upper(input.value)}\"\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));

        FrameworkConfig loaded = new FrameworkConfigLoader().load(config);
        assertTrue(loaded.tool("orderTools.find").callBacked());
        assertTrue(loaded.tool("orderTools.find").caseCached());
        assertEquals("db.orders.query", new att.template.ToolCallParser().parse(loaded.tool("orderTools.find").call()).name());
        assertTrue(loaded.tool("today").callBacked());
        assertTrue(loaded.tool("today").commandArgv().isEmpty());

        Path legacy = tempDir.resolve("legacy-v25-call.yaml");
        Files.write(legacy, ("schemaVersion: att-config/v2.5\ntools:\n  bad:\n" +
                "    name: Bad\n    description: Bad\n    call: '#{upper(input.value)}'\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(legacy));
    }

    @Test void rejectsAmbiguousAndProcessOnlyCallBackedToolFields() throws Exception {
        String prefix = "schemaVersion: att-config/v2.6\ntools:\n  bad:\n    name: Bad\n    description: Bad\n";
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("both.yaml",
                prefix + "    command: [echo]\n    call: '#{upper(input.value)}'\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("output.yaml",
                prefix + "    call: '#{upper(input.value)}'\n    output: json\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("context.yaml",
                prefix + "    call: '#{upper(CASE.value)}'\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("nested-context.yaml",
                prefix + "    call: \"#{db.orders.query(sql='select ?', params=[#{upper(CASE.value)}])}\"\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("dynamic-sql-file.yaml",
                prefix + "    call: '#{db.orders.query(sqlFile=input.file, params=[])}'\n" +
                        "    arguments:\n      file: {name: File, description: SQL file, required: true}\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("argv.yaml",
                prefix + "    call: '#{upper(input.value)}'\n    arguments:\n" +
                        "      value: {name: Value, description: Value, required: true, argName: --value}\n")));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("cached-update.yaml",
                prefix + "    call: \"#{db.orders.update(sql='update t set v=1')}\"\n    cache: {scope: db}\n")));
    }

    @Test void loadsV25DbHelperInstancesFromDedicatedFiles() throws Exception {
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory.resolve("dbhelpers"));
        Files.write(configDirectory.resolve("dbhelpers/orders.yaml"), ("schemaVersion: att-dbhelper/v2.5\n" +
                "id: orders\nname: Orders DB\ndescription: Order queries\n" +
                "connection:\n  url: jdbc:test:orders\n  username: att\n  password: local\n" +
                "statement: {timeoutSeconds: 7}\n" +
                "transaction: {scope: case, onEnd: commit}\n" +
                "result: {maxRows: 25, maxCellBytes: 1024, maxBytes: 4096}\n").getBytes("UTF-8"));
        Files.write(configDirectory.resolve("dbhelpers/audit.yaml"), ("schemaVersion: att-dbhelper/v2.5\n" +
                "id: audit\nname: Audit DB\ndescription: Audit queries\n" +
                "connection: {url: 'jdbc:test:audit', readOnly: true}\n" +
                "transaction: {scope: statement, onEnd: rollback}\n").getBytes("UTF-8"));
        Path config = configDirectory.resolve("config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.5\n" +
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
        Files.write(invalid, ("schemaVersion: att-config/v2.5\n" +
                "dbhelpers: [config/dbhelpers/orders.yaml, config/dbhelpers/orders.yaml]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalid));
    }
    @Test void validatesBuiltInsInReportAndToolCommandScopes() throws Exception {
        Path valid = tempDir.resolve("expressions.yaml");
        Files.write(valid, ("schemaVersion: att-config/v2.2\n" +
                "report: {fileNamePattern: \"#{upper(suiteName)}.result.xlsx\"}\n" +
                "tools:\n  echo:\n    name: Echo\n    description: Echo\n" +
                "    command: [echo, \"#{trim(input.value)}\"]\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        FrameworkConfig config = new FrameworkConfigLoader().load(valid);
        assertEquals("#{upper(suiteName)}.result.xlsx", config.report().fileNamePattern());

        Path invalidReport = tempDir.resolve("invalid-report-expression.yaml");
        Files.write(invalidReport, "schemaVersion: att-config/v2.2\nreport: {fileNamePattern: \"${suiteName}-#{external()}.xlsx\"}\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidReport));

        Path invalidCommand = tempDir.resolve("invalid-command-expression.yaml");
        Files.write(invalidCommand, ("schemaVersion: att-config/v2.2\ntools:\n  echo:\n    name: Echo\n    description: Echo\n" +
                "    command: [echo, \"#{external(${value})}\"]\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidCommand));

        Path invalidScopedPath = tempDir.resolve("invalid-command-scope.yaml");
        Files.write(invalidScopedPath, ("schemaVersion: att-config/v2.2\ntools:\n  echo:\n    name: Echo\n    description: Echo\n" +
                "    command: [echo, \"#{upper(input.missing)}\"]\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidScopedPath));
    }

    @Test void loadsV2AndRejectsGlobalStages() throws Exception {
        Path ok=tempDir.resolve("ok.yaml"); Files.write(ok,"schemaVersion: att-config/v2.1\noutputDirectory: out\ntools: {}\n".getBytes("UTF-8"));
        assertEquals(Paths.get("out"),new FrameworkConfigLoader().load(ok).outputDirectory());
        assertEquals(10000, new FrameworkConfigLoader().load(ok).timeoutMs());
        Path rooted=tempDir.resolve("rooted.yaml"); Files.write(rooted,"schemaVersion: att-config/v2.1\ntestcase: {root: cases/nested}\ncaseLog: {yamlAnchors: true}\ntimeoutMs: 3600000\n".getBytes("UTF-8"));
        FrameworkConfig rootedConfig = new FrameworkConfigLoader().load(rooted);
        assertEquals(Paths.get("cases/nested"), rootedConfig.testcasesRoot());
        assertEquals(3600000, rootedConfig.timeoutMs());
        assertTrue(rootedConfig.caseLogYamlAnchors());
        assertFalse(new FrameworkConfigLoader().load(ok).caseLogYamlAnchors());
        Path excessive=tempDir.resolve("excessive.yaml"); Files.write(excessive,"schemaVersion: att-config/v2.1\ntimeoutMs: 3600001\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(excessive));
        Path mismatch=tempDir.resolve("mismatch.yaml"); Files.write(mismatch,("schemaVersion: att-config/v2.1\ntools:\n  find:\n    name: 顯示 名稱 !\n    description: test\n    command: 'echo ${KeyWords}'\n    arguments:\n      keywords: {name: 關鍵 字詞 !, description: test, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(mismatch));
        Path direct=tempDir.resolve("direct.yaml"); Files.write(direct,("schemaVersion: att-config/v2.1\ntools:\n  find:\n    name: 顯示 名稱 !\n    description: test\n    command: 'echo ${keywords} ${input.keywords}'\n    arguments:\n      keywords: {name: 關鍵 字詞 !, description: test, required: true}\n").getBytes("UTF-8"));
        ToolConfig legacyScalar = new FrameworkConfigLoader().load(direct).tool("find");
        assertEquals("顯示 名稱 !", legacyScalar.name());
        assertEquals(java.util.Arrays.asList("echo", "${keywords}", "${input.keywords}"), legacyScalar.commandArgv());
        Path hiddenContext=tempDir.resolve("hidden-context.yaml"); Files.write(hiddenContext,("schemaVersion: att-config/v2.1\ntools:\n  find:\n    name: Find\n    description: test\n    command: 'echo ${CASE.caseId}'\n    arguments: {}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(hiddenContext));
        Path bad=tempDir.resolve("bad.yaml"); Files.write(bad,"stages: []\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(bad));
    }


    @Test void loadsV22ArgvGroupsAndSshTargets() throws Exception {
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory.resolve("tools"));
        Files.write(configDirectory.resolve("tools/database.yaml"), ("schemaVersion: att-tool-group/v2.2\n" +
                "id: database\nname: Database\ndescription: Database tools\n" +
                "script: [./tools/dispatch.sh, --read-only]\n" +
                "ssh: {host: db.example, user: att, port: 2222, identityFile: keys/id_ed25519}\n" +
                "tools:\n  select:\n    name: Select\n    description: Query row\n" +
                "    command: [query, '${id}']\n    output: json\n" +
                "    arguments:\n      id: {name: ID, description: Row ID, required: true, argName: --id}\n").getBytes("UTF-8"));
        Files.write(configDirectory.resolve("tools/logs.yaml"), ("schemaVersion: att-tool-group/v2.2\n" +
                "id: logs\nname: Logs\ndescription: Log tools\n" +
                "tools:\n  tail:\n    name: Tail\n    description: Tail logs\n    command: [./tools/tail.sh]\n").getBytes("UTF-8"));
        Files.write(configDirectory.resolve("config.yaml"), ("schemaVersion: att-config/v2.2\n" +
                "toolGroups: [config/tools/database.yaml, config/tools/logs.yaml]\n" +
                "ssh: {host: global.example, user: runner}\n" +
                "tools:\n  echo:\n    name: Echo\n    description: Echo value\n" +
                "    command:\n      - /usr/bin/printf\n      - '%s\\n'\n      - '${value}'\n" +
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
        String prefix = "schemaVersion: att-config/v2.2\ntools:\n  sample:\n    name: Sample\n    description: Sample\n";
        Path embedded = tempDir.resolve("embedded.yaml");
        Files.write(embedded, (prefix + "    command: [echo, 'value=${value}']\n    arguments:\n      value: {name: Value, description: Value, required: false, argName: --value}\n").getBytes("UTF-8"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(embedded)).getMessage().contains("exactly one complete argv token"));

        Path duplicate = tempDir.resolve("duplicate-arg-name.yaml");
        Files.write(duplicate, (prefix + "    command: [echo, '${value}', '${input.value}']\n    arguments:\n      value: {name: Value, description: Value, required: false, argName: --value}\n").getBytes("UTF-8"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(duplicate)).getMessage().contains("exactly one complete argv token"));

        Path unused = tempDir.resolve("unused-arg-name.yaml");
        Files.write(unused, (prefix + "    command: [echo]\n    arguments:\n      value: {name: Value, description: Value, required: false, argName: --value}\n").getBytes("UTF-8"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(unused)).getMessage().contains("exactly one complete argv token"));

        Path invalidMode = tempDir.resolve("invalid-arg-name-mode.yaml");
        Files.write(invalidMode, (prefix + "    command: [echo, '${value}']\n    arguments:\n      value: {name: Value, description: Value, required: false, argName: --value, argNameMode: sometimes}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidMode));
    }

    @Test void reportsToolConfigurationCodeFileFieldAndRepairHint() throws Exception {
        Path config = tempDir.resolve("bad-tool.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.2\n" +
                "tools:\n  sample:\n    name: Sample\n    description: Sample\n" +
                "    command: [echo, '${missing}']\n" +
                "    arguments:\n      value: {name: Value, description: Value, required: true}\n").getBytes("UTF-8"));

        att.validation.DiagnosticException error = assertThrows(att.validation.DiagnosticException.class,
                () -> new FrameworkConfigLoader().load(config));

        assertEquals(att.validation.DiagnosticCodes.TOOL_INVALID, error.code());
        assertEquals(config.toString(), error.file());
        assertTrue(error.field().contains("tools"));
        assertTrue(error.detail().contains("missing"));
        assertNotNull(error.suggestion());
    }

    @Test void loadsMultipleDelimitedArgumentsAndArgNameModes() throws Exception {
        Path config = tempDir.resolve("multiple-delimited.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.2\n" +
                "tools:\n  capture:\n    name: Capture\n    description: Capture lists\n" +
                "    command: [capture, '${keywords}', '${types}']\n" +
                "    arguments:\n" +
                "      keywords: {name: Keywords, description: Search words, required: true, delimit: ',', argName: --keyword, argNameMode: repeat}\n" +
                "      types: {name: Types, description: Transaction types, required: true, delimit: '|', argName: --types}\n").getBytes("UTF-8"));

        ToolConfig tool = new FrameworkConfigLoader().load(config).tool("capture");

        assertEquals(",", tool.arguments().get("keywords").delimit());
        assertEquals("repeat", tool.arguments().get("keywords").argNameMode());
        assertEquals("|", tool.arguments().get("types").delimit());
        assertEquals("once", tool.arguments().get("types").argNameMode());
    }

    @Test void rejectsDuplicateGroupIdsUnsafePathsAndReservedGlobalNames() throws Exception {
        Path configDirectory = tempDir.resolve("config"); Files.createDirectories(configDirectory.resolve("tools"));
        String group = "schemaVersion: att-tool-group/v2.2\nid: duplicate\nname: Group\ndescription: Group\ntools:\n  echo:\n    name: Echo\n    description: Echo\n    command: [echo]\n";
        Files.write(configDirectory.resolve("tools/one.yaml"), group.getBytes("UTF-8"));
        Files.write(configDirectory.resolve("tools/two.yaml"), group.getBytes("UTF-8"));
        Path duplicate = configDirectory.resolve("duplicate.yaml");
        Files.write(duplicate, "schemaVersion: att-config/v2.2\ntoolGroups: [config/tools/one.yaml, config/tools/two.yaml]\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(duplicate));
        Path unsafe = configDirectory.resolve("unsafe.yaml");
        Files.write(unsafe, "schemaVersion: att-config/v2.2\ntoolGroups: [../outside.yaml]\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(unsafe));
        Path reserved = configDirectory.resolve("reserved.yaml");
        Files.write(reserved, ("schemaVersion: att-config/v2.2\ntools:\n  nvl:\n    name: NVL\n    description: reserved\n    command: [echo]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(reserved));
    }

    @Test void loadsPerformanceHardeningLimitsAndWorkbookDisableMode() throws Exception {
        Path config = tempDir.resolve("performance.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.2\n" +
                "execution:\n  processOutput: {memoryLimitBytes: 4096, artifactLimitBytes: 8192}\n" +
                "report:\n  mode: none\n  html: {caseLogInlineLimitBytes: 2048}\n  junit: {caseLogEmbedThresholdBytes: 1024}\n").getBytes("UTF-8"));
        FrameworkConfig loaded = new FrameworkConfigLoader().load(config);
        assertEquals(4096, loaded.processOutput().memoryLimitBytes());
        assertEquals(8192, loaded.processOutput().artifactLimitBytes());
        assertEquals("none", loaded.report().mode());
        assertEquals(2048, loaded.report().htmlCaseLogInlineLimitBytes());
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(write("bad-performance.yaml", "schemaVersion: att-config/v2.2\nexecution: {processOutput: {memoryLimitBytes: 4096, artifactLimitBytes: 2048}}\n")));
    }

    private Path write(String name, String content) throws Exception { Path file = tempDir.resolve(name); Files.write(file, content.getBytes("UTF-8")); return file; }
}
