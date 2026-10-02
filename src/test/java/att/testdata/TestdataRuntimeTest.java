package att.testdata;

import att.TestSchemas;
import att.load.LoadScenario;
import att.load.LoadScenarioLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestdataRuntimeTest {
    @TempDir Path root;

    @BeforeEach void installSchemas() throws Exception { TestSchemas.install(root); }

    @Test void exactAndPathReferencesPreserveNativeTypesAndOneChoicePerMapping() throws Exception {
        Path descriptor = write("data/people.yaml", "schemaVersion: att-testdata/v1.0\n"
                + "id: people\nrecords:\n  - name: Ada\n    active: true\n    tags: [blue, green]\n"
                + "    nullable: null\n  - name: Lin\n    active: false\n    tags: [red]\n"
                + "selection: {strategy: sequential, exhaustion: recycle}\n");
        TestdataInputResolver resolver = new TestdataInputResolver(new TestdataRegistry(root,
                Collections.<Path>emptyList(), Arrays.asList(descriptor)));
        Map<String, Object> mapping = new LinkedHashMap<String, Object>();
        mapping.put("record", "@{people}");
        mapping.put("name", "@{people.name}");
        mapping.put("active", "@{people.active}");
        mapping.put("tag", "@{people.tags[1]}");
        mapping.put("tags", "@{people.tags}");
        mapping.put("nullable", "@{people.nullable}");
        mapping.put("embedded", "account=@{people.name}");

        Map<String, Object> first = resolver.resolve(mapping, null, null, null);
        assertEquals("Ada", ((Map<?, ?>) first.get("record")).get("name"));
        assertEquals("Ada", first.get("name"));
        assertEquals(Boolean.TRUE, first.get("active"));
        assertEquals("green", first.get("tag"));
        assertEquals(Arrays.asList("blue", "green"), first.get("tags"));
        assertTrue(first.containsKey("nullable"));
        assertNull(first.get("nullable"));
        assertEquals("account=Ada", first.get("embedded"));
        assertEquals("load-local", ((Map<?, ?>) resolver.selectionEvidence().get("people")).get("layer"));
    }

    @Test void generatedRecordsAreIndexedAndRandomSelectionIsSeeded() throws Exception {
        Path descriptor = write("data/generated.yaml", "schemaVersion: att-testdata/v1.0\n"
                + "id: generated\nrecords:\n  generate:\n    seq: {from: 100, to: 1000000, format: '%04d'}\n"
                + "  record: {id: 'order-%{seq}', sequence: '%{seq}'}\n"
                + "selection: {strategy: random, seed: 42, exhaustion: recycle}\n");
        TestdataRegistry registry = new TestdataRegistry(root, Collections.<Path>emptyList(), Arrays.asList(descriptor));
        TestdataInputResolver first = new TestdataInputResolver(registry,
                Collections.<String, Object>emptyMap(), "work", "closed", Long.valueOf(9));
        TestdataInputResolver second = new TestdataInputResolver(registry,
                Collections.<String, Object>emptyMap(), "work", "closed", Long.valueOf(9));
        Map<String, Object> mapping = Collections.<String, Object>singletonMap("row", "@{generated}");
        Map<String, Object> one = first.resolve(mapping, null, "VU-1", "it-1");
        Map<String, Object> two = second.resolve(mapping, null, "VU-1", "it-1");
        assertEquals(one, two);
        assertTrue(((String) ((Map<?, ?>) one.get("row")).get("id")).startsWith("order-"));
        assertEquals(999901L, registry.resolve("generated").count());
        assertEquals(Long.valueOf(100L), registry.resolve("generated").sequence(0));
        java.util.List<Object> firstRun = new java.util.ArrayList<Object>();
        java.util.List<Object> secondRun = new java.util.ArrayList<Object>();
        for (int i = 1; i <= 12; i++) {
            String iteration = "it-" + i;
            firstRun.add(first.resolve(mapping, null, "VU-1", iteration).get("row"));
            secondRun.add(second.resolve(mapping, null, "VU-1", iteration).get("row"));
        }
        assertEquals(firstRun, secondRun);
    }

    @Test void loadScopesCachePerLifetimeAndStopSignalsSchedulerOnExhaustion() throws Exception {
        Path descriptor = write("data/scoped.yaml", "schemaVersion: att-testdata/v1.0\n"
                + "id: scoped\nrecords: [first, second]\n"
                + "selection: {strategy: sequential, exhaustion: stop}\n");
        TestdataRegistry registry = new TestdataRegistry(root, Collections.<Path>emptyList(), Arrays.asList(descriptor));
        Map<String, Object> userPolicy = Collections.<String, Object>singletonMap("scoped",
                Collections.<String, Object>singletonMap("scope", "user"));
        TestdataInputResolver users = new TestdataInputResolver(registry, userPolicy, "users", "closed", null);
        Map<String, Object> mapping = Collections.<String, Object>singletonMap("value", "@{scoped}");
        Object userA = users.resolve(mapping, null, "VU-A", "a1").get("value");
        assertEquals(userA, users.resolve(mapping, null, "VU-A", "a2").get("value"));
        assertNotEquals(userA, users.resolve(mapping, null, "VU-B", "b1").get("value"));

        TestdataInputResolver iterations = new TestdataInputResolver(registry,
                Collections.<String, Object>emptyMap(), "iterations", "closed", null);
        assertNotEquals(iterations.resolve(mapping, null, "VU-A", "i1").get("value"),
                iterations.resolve(mapping, null, "VU-A", "i2").get("value"));
        assertThrows(TestdataStopException.class, () -> iterations.resolve(mapping, null, "VU-A", "i3"));

        Map<String, Object> workloadPolicy = Collections.<String, Object>singletonMap("scoped",
                Collections.<String, Object>singletonMap("scope", "workload"));
        TestdataInputResolver workload = new TestdataInputResolver(registry, workloadPolicy, "one", "closed", null);
        Object workloadValue = workload.resolve(mapping, null, "VU-A", "w1").get("value");
        assertEquals(workloadValue, workload.resolve(mapping, null, "VU-B", "w2").get("value"));

        TestdataInputResolver arrivalRate = new TestdataInputResolver(registry, userPolicy, "rate", "arrivalRate", null);
        assertThrows(IllegalArgumentException.class, () -> arrivalRate.resolve(mapping, null, null, "a1"));
    }

    @Test void rejectsInputCyclesReservedMarkersMalformedReferencesAndCredentialFields() throws Exception {
        Path descriptor = write("data/plain.yaml", "schemaVersion: att-testdata/v1.0\nid: plain\nrecords: [ok]\n");
        TestdataInputResolver resolver = new TestdataInputResolver(new TestdataRegistry(root,
                Collections.<Path>emptyList(), Arrays.asList(descriptor)));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                Collections.<String, Object>singletonMap("x", "${EXEC.INPUT.prefix}-@{plain}"), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                Collections.<String, Object>singletonMap("x", "@{plain"), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                Collections.<String, Object>singletonMap("x", "%{seq}"), null, null, null));
        assertTrue(TestdataSyntax.references(Collections.<String, Object>singletonMap("literal", "#{not-a-mapping}" )).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TestdataSyntax.rejectDirectReferences(
                Collections.<String, Object>singletonMap("call", "#{f(@{plain})}"), "Template action"));

        Path credential = write("data/credential.yaml", "schemaVersion: att-testdata/v1.0\nid: bad\n"
                + "records: [{customer: 1, accessToken: never-store-this}]\n"
                + "selection: {strategy: sequential}\n");
        TestdataRegistry registry = new TestdataRegistry(root, Collections.<Path>emptyList(), Arrays.asList(credential));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("bad"));
    }

    @Test void packageValidationDetectsLayerDuplicatesAndBoundsGeneratedRanges() throws Exception {
        Path first = write("data/a.yaml", "schemaVersion: att-testdata/v1.0\nid: repeated\nrecords: [a]\n");
        Path second = write("data/b.yaml", "schemaVersion: att-testdata/v1.0\nid: repeated\nrecords: [b]\n");
        TestdataRegistry duplicates = new TestdataRegistry(root, Collections.<Path>emptyList(), Arrays.asList(first, second));
        assertThrows(IllegalArgumentException.class, duplicates::validateAll);
        assertThrows(IllegalArgumentException.class, () -> duplicates.resolve("repeated"));

        Path oversized = write("data/oversized.yaml", "schemaVersion: att-testdata/v1.0\nid: oversized\n"
                + "records: {generate: {seq: {from: 0, to: 1000000}}, record: '%{seq}'}\n");
        assertThrows(IllegalArgumentException.class, () -> new TestdataRegistry(root,
                Collections.<Path>emptyList(), Arrays.asList(oversized)).resolve("oversized"));
    }

    @Test void localDescriptorReplacesWholeEnvironmentDescriptorAndV14Migrates() throws Exception {
        Path env = write("data/env.yaml", "schemaVersion: att-testdata/v1.0\nid: shared\nrecords: [environment]\n");
        Path local = write("data/local.yaml", "schemaVersion: att-testdata/v1.0\nid: shared\nrecords: [local]\n");
        TestdataRegistry registry = new TestdataRegistry(root, Arrays.asList(env), Arrays.asList(local));
        assertEquals("local", registry.resolve("shared").record(0));
        assertEquals("load-local", registry.layer("shared"));

        Path scenario = write("load/prior.yaml", "schemaVersion: att-load/v1.4\nworkloads:\n"
                + "  - id: basic\n    target: {type: tool, id: echo}\n"
                + "    load: {users: 1, duration: 1s}\n");
        LoadScenario loaded = new LoadScenarioLoader(root).load(scenario);
        assertEquals(att.Version.LOAD_SCHEMA_CURRENT, loaded.schemaVersion());
        assertEquals(1, loaded.workloads().size());
    }

    @Test void checkedInGeneratedLoadExampleImportsAndValidatesItsVirtualRecords() throws Exception {
        Path packageRoot = Paths.get("").toAbsolutePath().normalize();
        LoadScenario scenario = new LoadScenarioLoader(packageRoot).load(
                packageRoot.resolve("examples/load/testdata-generated.yaml"));
        assertEquals(1, scenario.testdataDescriptors().size());
        TestdataRegistry registry = new TestdataRegistry(packageRoot,
                Collections.<Path>emptyList(), scenario.testdataDescriptors());
        TestdataDescriptor descriptor = registry.resolve("generatedAccounts");
        assertEquals(4L, descriptor.count());
        assertEquals("A-001000", ((Map<?, ?>) descriptor.record(0)).get("id"));
        TestdataMappingValidator.validate(scenario.inputs(), registry);
    }

    @Test void workloadSelectionOverridesReplaceTheWholePolicy() throws Exception {
        Path descriptor = write("data/override.yaml", "schemaVersion: att-testdata/v1.0\n"
                + "id: override\nrecords: [first, second]\n"
                + "selection: {strategy: sequential, exhaustion: stop}\n");
        Map<String, Object> policy = Collections.<String, Object>singletonMap("override",
                Collections.<String, Object>singletonMap("selection",
                        Collections.<String, Object>singletonMap("strategy", "roundRobin")));
        TestdataInputResolver resolver = new TestdataInputResolver(new TestdataRegistry(root,
                Collections.<Path>emptyList(), Arrays.asList(descriptor)), policy, "work", "closed", null);
        Map<String, Object> mapping = Collections.<String, Object>singletonMap("value", "@{override}");
        resolver.resolve(mapping, null, "VU-1", "i1");
        resolver.resolve(mapping, null, "VU-1", "i2");
        assertThrows(IllegalStateException.class, () -> resolver.resolve(mapping, null, "VU-1", "i3"));
    }

    private Path write(String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }
}
