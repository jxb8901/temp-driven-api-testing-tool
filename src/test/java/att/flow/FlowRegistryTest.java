package att.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class FlowRegistryTest {
    @TempDir Path root;

    @Test void indexesReusableActionGroupsUsingOrdinaryContext() throws Exception {
        flow("one", valid("common.one.v1", "copy", "${CASE.value}"));
        FlowRegistry registry = new FlowRegistry(root, root.resolve("templates"));
        assertEquals(1, registry.size());
        assertEquals("copy", registry.get("common.one.v1").actions().get(0).id());
    }

    @Test void rejectsRemovedInputOutputAndWithContracts() throws Exception {
        flow("inputs", valid("common.inputs.v1", "copy", "${CASE.value}")
                .replace("actions:\n", "inputs: {}\nactions:\n"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("inputs"));

        deleteFlows();
        flow("outputs", valid("common.outputs.v1", "copy", "${CASE.value}") + "outputs: {}\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("outputs"));

        deleteFlows();
        flow("leaf", valid("common.leaf.v1", "leafAction", "${CASE.value}"));
        flow("caller", "schemaVersion: att-flow/v3.0\nid: common.caller.v1\nname: Caller\ndescription: Caller\nactions:\n  call: {type: flow, use: common.leaf.v1, with: {value: x}}\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("with"));
    }

    @Test void rejectsCyclesAndDepthFour() throws Exception {
        flow("a", caller("common.a.v1", "callB", "common.b.v1"));
        flow("b", caller("common.b.v1", "callA", "common.a.v1"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("cycle"));

        deleteFlows();
        flow("a", caller("common.a.v1", "callB", "common.b.v1"));
        flow("b", caller("common.b.v1", "callC", "common.c.v1"));
        flow("c", caller("common.c.v1", "callD", "common.d.v1"));
        flow("d", valid("common.d.v1", "leaf", "${CASE.value}"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("depth"));
    }

    @Test void rejectsDuplicateIds() throws Exception {
        flow("one", valid("common.same.v1", "one", "${CASE.value}"));
        flow("two", valid("common.same.v1", "two", "${CASE.value}"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("Duplicate"));
    }

    @Test void compilesTwoHundredFlowsAndTwoThousandReferencesOnceWithinTarget() throws Exception {
        for (int index = 0; index < 180; index++) {
            flow("leaf-" + index, valid("perf.leaf" + index + ".v1", "note" + index, "${CASE.caseId}"));
        }
        for (int caller = 0; caller < 20; caller++) {
            StringBuilder yaml = new StringBuilder("schemaVersion: att-flow/v3.0\nid: perf.caller" + caller + ".v1\nname: Caller\ndescription: Caller\nactions:\n");
            for (int offset = 0; offset < 100; offset++) {
                int leaf = (caller * 100 + offset) % 180;
                yaml.append("  call").append(caller).append('_').append(offset)
                        .append(": {type: flow, use: perf.leaf").append(leaf).append(".v1}\n");
            }
            flow("caller-" + caller, yaml.toString());
        }
        FlowRegistry registry = assertTimeout(Duration.ofSeconds(10), () -> new FlowRegistry(root, root.resolve("templates")));
        assertEquals(200, registry.size()); assertEquals(200, registry.parsedCount());
    }

    @Test void selectedRegistryLoadsOnlyReferencedDependencyClosure() throws Exception {
        flow("selected", valid("common.selected.v1", "selectedAction", "${CASE.value}"));
        flow("unselected-invalid", "schemaVersion: att-flow/v3.0\nid: common.invalid.v1\nthis: is not a valid Flow\n");
        FlowRegistry registry = new FlowRegistry(root, root.resolve("templates"), false);
        java.util.Map<String,Object> raw = new java.util.LinkedHashMap<String,Object>();
        raw.put("type", "flow"); raw.put("use", "common.selected.v1");
        assertDoesNotThrow(() -> registry.validateInvocation(new att.template.TemplateAction("selected", raw, "att-template/v3.0")));
        assertEquals(1, registry.parsedCount());
        assertThrows(IllegalArgumentException.class, () -> registry.get("common.invalid.v1"));
    }

    @Test void rejectsMalformedNestedFlowUseBeforeRuntime() throws Exception {
        flow("bad-use", "schemaVersion: att-flow/v3.0\nid: common.bad-use.v1\nname: Bad\ndescription: Bad\nactions:\n  call: {type: flow, use: bad}\n");
        IllegalArgumentException invalidUse = assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(invalidUse.getMessage().contains("Flow use") || invalidUse.getMessage().contains("pattern"), invalidUse.getMessage());
    }

    private String valid(String id, String actionId, String expression) {
        return "schemaVersion: att-flow/v3.0\nid: " + id + "\nname: Test\ndescription: Test Flow\nactions:\n  "
                + actionId + ": {type: assign, name: " + actionId + "Value, expression: '" + expression + "'}\n";
    }

    private String caller(String id, String actionId, String target) {
        return "schemaVersion: att-flow/v3.0\nid: " + id + "\nname: Caller\ndescription: Caller\nactions:\n  "
                + actionId + ": {type: flow, use: " + target + "}\n";
    }

    private void flow(String directory, String yaml) throws Exception {
        Path path = root.resolve("templates/flows").resolve(directory);
        Files.createDirectories(path);
        Files.write(path.resolve("flow.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private void deleteFlows() throws Exception {
        Path flows = root.resolve("templates/flows");
        if (!Files.exists(flows)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(flows)) {
            java.util.List<Path> ordered = new java.util.ArrayList<Path>(); paths.forEach(ordered::add);
            java.util.Collections.sort(ordered, java.util.Collections.reverseOrder());
            for (Path path : ordered) Files.delete(path);
        }
    }
}
