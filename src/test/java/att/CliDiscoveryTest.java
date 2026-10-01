package att;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliDiscoveryTest {
    @TempDir Path temp;

    @Test void debugDiscoveryListsOnlyRunnableTargetsAndOnlyExistingSidecars() throws Exception {
        Path root = fixture();
        FrameworkConfig config = config();
        Map<String, Object> result = CliDiscovery.debug(root, config);
        @SuppressWarnings("unchecked") List<Map<String, Object>> targets = (List<Map<String, Object>>) result.get("targets");
        assertEquals(1, targets.size());
        Map<String, Object> withSidecar = find(targets, "template", "TARGET");
        assertTrue(withSidecar.containsKey("sidecar"));
        assertTrue(String.valueOf(withSidecar.get("command")).contains("debug template"));
        assertFalse(targets.stream().anyMatch(target -> "NO_SIDECAR".equals(target.get("id"))));
        assertFalse(targets.stream().anyMatch(target -> "LOAD_ONLY".equals(target.get("id"))),
                "Debug discovery must not advertise a sidecar that depends on Load-only roots");
        assertTrue(targets.stream().allMatch(target -> Files.isRegularFile(Paths.get(String.valueOf(target.get("sidecar"))))));
        assertFalse(Files.exists(root.resolve("output")), "discovery must not create Debug output");
    }

    @Test void loadDiscoveryValidatesDeclaredScenariosIgnoresOtherYamlAndShowsQuickCommands() throws Exception {
        Path root = fixture();
        write(root, "load/scenarios/valid.yaml", "schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: quick\n    target: {type: template, id: TARGET}\n    load: {users: 1, duration: 1s}\n");
        write(root, "load/scenarios/invalid.yaml", "schemaVersion: att-load/v1.3\nworkloads: invalid\n");
        write(root, "load/scenarios/unrelated.yaml", "kind: unrelated\nvalue: not a load descriptor\n");
        write(root, "load/load.yaml", "schemaVersion: att-load-profile/v1.0\nload: {users: 1, duration: 1s}\n");

        Map<String, Object> result = CliDiscovery.load(root, config());
        @SuppressWarnings("unchecked") List<Map<String, Object>> scenarios = (List<Map<String, Object>>) result.get("scenarios");
        @SuppressWarnings("unchecked") List<Map<String, Object>> invalid = (List<Map<String, Object>>) result.get("invalid");
        @SuppressWarnings("unchecked") List<String> quick = (List<String>) result.get("quickLoadCommands");
        assertEquals(1, scenarios.size());
        assertEquals("load/scenarios/valid.yaml", scenarios.get(0).get("path"));
        assertEquals(1, invalid.size());
        assertEquals("load/scenarios/invalid.yaml", invalid.get(0).get("path"));
        assertTrue(String.valueOf(invalid.get(0).get("diagnostic")).contains("Invalid load scenario"));
        assertEquals(1, quick.size());
        assertTrue(quick.get(0).contains("load --debug template 'TARGET'"), quick.toString());
        assertFalse(Files.exists(root.resolve("output")), "discovery must not create load output or start a scheduler");
    }

    private Map<String, Object> find(List<Map<String, Object>> targets, String type, String id) {
        for (Map<String, Object> target : targets)
            if (type.equals(target.get("type")) && id.equals(target.get("id"))) return target;
        fail("Missing target " + type + " " + id);
        return Collections.emptyMap();
    }

    private FrameworkConfig config() {
        ToolConfig tool = new ToolConfig("echo", "echo", "Echo", "/bin/echo", "text", Collections.emptyMap());
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.singletonMap("echo", tool), null, null);
    }

    private Path fixture() throws Exception {
        Path root = temp.resolve("project");
        TestSchemas.install(root);
        write(root, "templates/TARGET/template.yaml", "schemaVersion: att-template/v3.3\nname: TARGET\n"
                + "description: discovery target\nactions:\n  show: {type: log, message: ready}\n");
        write(root, "templates/TARGET/debug.yaml", "schemaVersion: att-debug/v1.1\ninputs: {value: ready}\n");
        write(root, "templates/NO_SIDECAR/template.yaml", "schemaVersion: att-template/v3.3\nname: NO_SIDECAR\n"
                + "description: no sidecar\nactions:\n  show: {type: log, message: ready}\n");
        write(root, "templates/LOAD_ONLY/template.yaml", "schemaVersion: att-template/v3.3\nname: LOAD_ONLY\n"
                + "description: Load-only bootstrap root\nactions:\n  show: {type: log, message: ready}\n");
        write(root, "templates/LOAD_ONLY/debug.yaml", "schemaVersion: att-debug/v1.1\nvars:\n"
                + "  userId: '${EXEC.LOAD.USER_ID}'\n");
        write(root, "templates/flows/group/flow/flow.yaml", "schemaVersion: att-flow/v3.3\nid: group.flow.v1\n"
                + "name: Flow\ndescription: discovery flow\nactions:\n  show: {type: log, message: ready}\n");
        Files.createDirectories(root.resolve("output"));
        Files.delete(root.resolve("output"));
        return root;
    }

    private Path write(Path root, String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
