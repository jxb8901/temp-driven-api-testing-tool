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

    @Test void indexesStaticContractsAndRejectsForbiddenCaseScope() throws Exception {
        flow("one", valid("common.one.v1", "${input.value}"));
        FlowRegistry registry = new FlowRegistry(root, root.resolve("templates"));
        assertEquals(1, registry.size());
        assertEquals("string", registry.get("common.one.v1").outputs().get("value").type());

        flow("bad", valid("common.bad.v1", "${CASE.value}"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(error.getMessage().contains("may not access"));
    }

    @Test void rejectsUnknownOrNotYetAvailableLocalContext() throws Exception {
        flow("unknown-input", valid("common.unknown-input.v1", "${input.missing}"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("Unknown Flow input"));

        deleteFlows();
        flow("unknown-runtime", valid("common.unknown-runtime.v1", "${runtime.missing}"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("not assigned"));
    }

    @Test void rejectsMissingBindingsCyclesAndDepthFour() throws Exception {
        flow("a", caller("common.a.v1", "common.b.v1", true));
        flow("b", caller("common.b.v1", "common.a.v1", true));
        IllegalArgumentException cycle = assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(cycle.getMessage().contains("cycle"));

        deleteFlows();
        flow("a", caller("common.a.v1", "common.b.v1", true));
        flow("b", caller("common.b.v1", "common.c.v1", true));
        flow("c", caller("common.c.v1", "common.d.v1", true));
        flow("d", valid("common.d.v1", "${input.value}"));
        IllegalArgumentException depth = assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(depth.getMessage().contains("depth"));
    }

    @Test void rejectsDuplicateIdsAndStaticTypeMismatch() throws Exception {
        flow("one", valid("common.same.v1", "${input.value}"));
        flow("two", valid("common.same.v1", "${input.value}"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("Duplicate"));

        deleteFlows();
        flow("source", "schemaVersion: att-flow/v3.0\nid: common.source.v1\nname: Source\ndescription: Source\ninputs: {}\nactions:\n  make: {type: assign, name: value, expression: '1'}\noutputs:\n  value: {type: integer, from: '${runtime.value}'}\n");
        flow("target", "schemaVersion: att-flow/v3.0\nid: common.target.v1\nname: Target\ndescription: Target\ninputs:\n  value: {type: string, required: true}\nactions:\n  copy: {type: assign, name: value, expression: '${input.value}'}\noutputs:\n  value: {type: string, from: '${runtime.value}'}\n");
        flow("caller", "schemaVersion: att-flow/v3.0\nid: common.caller.v1\nname: Caller\ndescription: Caller\ninputs: {}\nactions:\n  source: {type: flow, use: common.source.v1}\n  target:\n    type: flow\n    use: common.target.v1\n    with: {value: '${actions.source.output.outputs.value}'}\noutputs: {}\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates"))).getMessage().contains("type mismatch"));
    }

    @Test void compilesTwoHundredFlowsAndTwoThousandReferencesOnceWithinTarget() throws Exception {
        for (int index = 0; index < 180; index++) {
            flow("leaf-" + index, "schemaVersion: att-flow/v3.0\nid: perf.leaf" + index + ".v1\nname: Leaf\ndescription: Leaf\ninputs: {}\nactions:\n  note: {type: log, message: ok}\noutputs: {}\n");
        }
        for (int caller = 0; caller < 20; caller++) {
            StringBuilder yaml = new StringBuilder("schemaVersion: att-flow/v3.0\nid: perf.caller" + caller + ".v1\nname: Caller\ndescription: Caller\ninputs: {}\nactions:\n");
            for (int offset = 0; offset < 100; offset++) {
                int leaf = (caller * 100 + offset) % 180;
                yaml.append("  call").append(offset).append(": {type: flow, use: perf.leaf").append(leaf).append(".v1}\n");
            }
            yaml.append("outputs: {}\n"); flow("caller-" + caller, yaml.toString());
        }
        FlowRegistry registry = assertTimeout(Duration.ofSeconds(10), () -> new FlowRegistry(root, root.resolve("templates")));
        assertEquals(200, registry.size()); assertEquals(200, registry.parsedCount());
    }

    @Test void selectedRegistryLoadsOnlyReferencedDependencyClosure() throws Exception {
        flow("selected", valid("common.selected.v1", "${input.value}"));
        flow("unselected-invalid", "schemaVersion: att-flow/v3.0\nid: common.invalid.v1\nthis: is not a valid Flow\n");
        FlowRegistry registry = new FlowRegistry(root, root.resolve("templates"), false);
        java.util.Map<String,Object> raw = new java.util.LinkedHashMap<String,Object>(); raw.put("type", "flow"); raw.put("use", "common.selected.v1"); raw.put("with", java.util.Collections.singletonMap("value", "ok"));
        assertDoesNotThrow(() -> registry.validateInvocation(new att.template.TemplateAction("selected", raw, "att-template/v3.0")));
        assertEquals(1, registry.parsedCount());
        assertThrows(IllegalArgumentException.class, () -> registry.get("common.invalid.v1"));
    }

    @Test void rejectsMalformedNestedFlowUseAndWithBeforeRuntime() throws Exception {
        flow("bad-with", "schemaVersion: att-flow/v3.0\nid: common.bad-with.v1\nname: Bad\ndescription: Bad\ninputs: {}\nactions:\n  call: {type: flow, use: common.target.v1, with: [one]}\noutputs: {}\n");
        IllegalArgumentException invalidWith = assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(invalidWith.getMessage().contains("with"), invalidWith.getMessage());

        deleteFlows();
        flow("bad-use", "schemaVersion: att-flow/v3.0\nid: common.bad-use.v1\nname: Bad\ndescription: Bad\ninputs: {}\nactions:\n  call: {type: flow, use: bad}\noutputs: {}\n");
        IllegalArgumentException invalidUse = assertThrows(IllegalArgumentException.class,
                () -> new FlowRegistry(root, root.resolve("templates")));
        assertTrue(invalidUse.getMessage().contains("Flow use") || invalidUse.getMessage().contains("pattern"), invalidUse.getMessage());
    }

    private String valid(String id, String output) {
        return "schemaVersion: att-flow/v3.0\nid: " + id + "\nname: Test\ndescription: Test Flow\ninputs:\n  value: {type: string, required: true}\nactions:\n  copy: {type: assign, name: value, expression: '${input.value}'}\noutputs:\n  value: {type: string, from: '" + output + "'}\n";
    }

    private String caller(String id, String target, boolean bind) {
        return "schemaVersion: att-flow/v3.0\nid: " + id + "\nname: Caller\ndescription: Caller\ninputs:\n  value: {type: string, required: true}\nactions:\n  call:\n    type: flow\n    use: " + target + (bind ? "\n    with: {value: '${input.value}'}" : "") + "\noutputs: {}\n";
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
