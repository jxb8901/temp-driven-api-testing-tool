/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkConfigLoaderTest {
    @TempDir Path tempDir;
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
}
