package att.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static att.load.LoadTemplateCatalog.obj;
import static org.junit.jupiter.api.Assertions.*;

class LoadTemplateCatalogTest {
    @TempDir Path root;

    @SuppressWarnings("unchecked")
    private Map<String,Object> bindings(Map<String,Object> definition) {
        Map<String,Object> bindings = new LinkedHashMap<>();
        for (Map<String,Object> workload : (List<Map<String,Object>>)definition.get("workloads")) {
            Map<String,Object> binding = new LinkedHashMap<>();
            if (workload.containsKey("mix")) {
                Map<String,Object> mix = new LinkedHashMap<>();
                for (Map<String,Object> slot : (List<Map<String,Object>>)workload.get("mix"))
                    mix.put((String)slot.get("id"), obj("type", "tool", "id", "shared"));
                binding.put("mix", mix);
            } else binding.put("target", obj("type", "tool", "id", "shared"));
            bindings.put((String)workload.get("id"), binding);
        }
        return obj("schemaVersion", LoadTemplateCatalog.BINDINGS_SCHEMA, "bindings", bindings);
    }

    @Test void allSixModesShareOneValidatedAuthoringAndCompositionContract() throws Exception {
        LoadTemplateCatalog catalog = new LoadTemplateCatalog(root);
        for (String mode : LoadTemplateCatalog.MODES) {
            Map<String,Object> definition = catalog.load("builtin:" + mode);
            LoadScenario scenario = catalog.compose(definition, bindings(definition), null);
            assertEquals("att-load/v1.8", scenario.schemaVersion());
            assertEquals(mode.startsWith("multi-") ? 2 : 1, scenario.workloads().size());
            for (LoadWorkload workload : scenario.workloads()) {
                assertEquals(mode.endsWith("mix"), workload.mixed());
                assertEquals(mode.endsWith("tps") ? LoadScenario.Model.ARRIVAL_RATE : LoadScenario.Model.CLOSED, workload.model());
            }
        }
        assertEquals(6, ((List<?>)catalog.list("", 0, 100).get("items")).size());
    }

    @Test void catalogRevisionTracksSourceContentWithoutEnvironmentAndReservedFoldersStaySeparate() throws Exception {
        Path directory=Files.createDirectories(root.resolve("load/templates"));
        LoadTemplateCatalog catalog=new LoadTemplateCatalog(root);
        String before=catalog.sourceRevision();
        Files.write(directory.resolve("mixed.yaml"), new org.yaml.snakeyaml.Yaml().dump(LoadTemplateCatalog.builtin("multi-workload-mix")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String added=catalog.sourceRevision(); assertNotEquals(before,added);
        assertEquals(added,new LoadTemplateCatalog(root).sourceRevision());
        assertEquals(7,((List<?>)catalog.list("mixed",0,100).get("items")).size());
        Files.write(directory.resolve("mixed.yaml"), "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNotEquals(added,catalog.sourceRevision());
    }

    @Test void independentlyMixedWorkloadsPreserveSharedTargetsAndBusinessIsolation() throws Exception {
        Map<String,Object> definition = LoadTemplateCatalog.builtin("multi-workload-mix");
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>)definition.get("workloads");
        rows.get(0).put("load", obj("users", 10)); rows.get(1).put("load", obj("users", 20));
        rows.get(1).put("mix", Arrays.asList(obj("id", "slot-1", "weight", 40), obj("id", "slot-2", "weight", 60)));
        Map<String,Object> bindings = obj("schemaVersion", LoadTemplateCatalog.BINDINGS_SCHEMA, "bindings", obj(
                "workload-1", obj("mix", obj("slot-1", obj("type", "template", "id", "A"), "slot-2", obj("type", "flow", "id", "B"))),
                "workload-2", obj("mix", obj("slot-1", obj("type", "flow", "id", "B", "inputs", obj("amount", 7)), "slot-2", obj("type", "tool", "id", "C")))));
        LoadScenario scenario = new LoadTemplateCatalog(root).compose(definition, bindings, (type,id) -> obj("inputs", obj("source", id)));
        assertEquals(30, scenario.workloads().stream().mapToInt(LoadWorkload::users).sum());
        assertEquals(70, scenario.workloads().get(0).mix().get(0).weight());
        assertEquals(40, scenario.workloads().get(1).mix().get(0).weight());
        assertFalse(scenario.workloads().get(0).mix().get(1).inputs().containsKey("amount"));
        assertEquals(7, scenario.workloads().get(1).mix().get(0).inputs().get("amount"));
        assertEquals("B", scenario.workloads().get(1).mix().get(0).inputs().get("source"));
    }

    @Test void workloadBindingsMergeAfterDefaultsAndTargetBindingsWithoutLosingDistinctKeys() throws Exception {
        Map<String,Object> definition = LoadTemplateCatalog.builtin("single-vu");
        Map<String,Object> descriptor = obj("schemaVersion", LoadTemplateCatalog.BINDINGS_SCHEMA, "bindings", obj(
                "workload-1", obj("target", obj("type", "template", "id", "A",
                        "inputs", obj("region", "HK", "targetWins", "target", "workloadWins", "target"),
                        "vars", obj("region", "HK", "targetWins", "target", "workloadWins", "target")),
                        "inputs", obj("customerId", 123, "workloadWins", "workload"),
                        "vars", obj("customerId", 123, "workloadWins", "workload"))));
        LoadWorkload workload = new LoadTemplateCatalog(root).compose(definition, descriptor, (type,id) -> obj(
                "inputs", obj("defaultOnly", true, "targetWins", "default", "workloadWins", "default"),
                "vars", obj("defaultOnly", true, "targetWins", "default", "workloadWins", "default"))).workloads().get(0);
        Map<String,Object> expected = obj("defaultOnly", true, "region", "HK", "customerId", 123,
                "targetWins", "target", "workloadWins", "workload");
        assertEquals(expected, workload.inputs());
        assertEquals(expected, workload.vars());
    }

    @Test void rejectsIncompleteExtraUnsafeAndWrongModelInputsBeforeExecution() throws Exception {
        LoadTemplateCatalog catalog = new LoadTemplateCatalog(root);
        Map<String,Object> definition = LoadTemplateCatalog.builtin("multi-vu");
        assertThrows(IllegalArgumentException.class, () -> catalog.compose(definition, obj("schemaVersion", LoadTemplateCatalog.BINDINGS_SCHEMA, "bindings", obj("workload-1", obj("target", obj("type", "tool", "id", "A")))), null));
        assertThrows(IllegalArgumentException.class, () -> catalog.load("../outside.yaml"));
        definition.put("policy", obj("load", obj("arrivalRate", "1/s", "duration", "1s", "maxConcurrent", 1, "overloadPolicy", "drop")));
        assertThrows(IllegalArgumentException.class, () -> catalog.validate(definition));
    }

    @Test void extraSlotsRequireExplicitPrototypeAndClonePolicyRatherThanTargetIdentity() throws Exception {
        Map<String,Object> definition = LoadTemplateCatalog.builtin("multi-vu");
        Map<String,Object> bound = obj("schemaVersion", LoadTemplateCatalog.BINDINGS_SCHEMA, "bindings", obj(
                "workload-1", obj("target", obj("type", "tool", "id", "A")),
                "workload-2", obj("target", obj("type", "tool", "id", "B")),
                "extra", obj("prototype", "workload-1", "target", obj("type", "tool", "id", "C"))));
        LoadScenario scenario = new LoadTemplateCatalog(root).compose(definition, bound, null);
        assertEquals(3, scenario.workloads().size());
        assertEquals("extra", scenario.workloads().get(2).id());
        assertEquals("C", scenario.workloads().get(2).targetId());
    }
}
