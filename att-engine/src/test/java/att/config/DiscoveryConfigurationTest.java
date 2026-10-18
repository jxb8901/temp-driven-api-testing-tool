package att.config;

import att.resource.PackageResourceInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryConfigurationTest {
    private static final String MISSING = "ATT_244_TEST_ABSENT_71F29BE6";
    @TempDir Path root;

    @BeforeEach void setup() throws Exception {
        assertNull(System.getenv(MISSING));
        att.TestSchemas.install(root);
        Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase"));
    }

    @Test void defersDbMqSshAndHttpSecretsWithoutWeakeningStrictLoads() throws Exception {
        write("config/db.yaml", "schemaVersion: att-dbhelper/v2.6\nid: orders\nname: Orders\ndescription: Orders DB\n"
                + "connection: {url: 'jdbc:unused', password: '${ENV:" + MISSING + "}'}\n");
        write("config/mq.yaml", "schemaVersion: att-mqhelper/v1.3\nid: payments\nname: Payments\ndescription: Payments MQ\n"
                + "defaults: {connection: {queueManager: QM1, host: localhost, port: 1414, channel: CH, password: '${ENV:" + MISSING + "}'}}\n"
                + "instances: [{id: primary}]\n");
        write("config/ssh.yaml", "schemaVersion: att-sshhelper/v1.0\nid: app\ndefaults: {user: deploy, identityFile: '${ENV:" + MISSING + "}'}\n"
                + "instances: [{id: primary, host: localhost}]\n");
        write("config/http.yaml", "schemaVersion: att-httphelper/v1.1\nid: api\nbaseUrl: https://example.invalid\n"
                + "auth: {type: bearer, token: '${ENV:" + MISSING + "}'}\n");
        for (String type : new String[] { "db", "mq", "ssh", "http" }) {
            Path config = config(type + "helpers: [config/" + type + ".yaml]\n");
            FrameworkConfig metadata = discovery(config, null);
            assertTrue(metadata.discoveryOnly());
            assertEquals(1, metadata.environmentRequirements().size(), type);
            EnvironmentRequirement requirement = metadata.environmentRequirements().get(0);
            assertEquals(type, requirement.helperType());
            assertEquals(MISSING, requirement.variable());
            assertEquals("config/" + type + ".yaml", requirement.descriptor());
            assertEquals("ATT-RESOURCE-ENV-REQUIRED", requirement.diagnostic("SIT").get("code"));
            assertFalse(requirement.diagnostic("SIT").toString().contains(root.toString()));
            assertThrows(UnsupportedOperationException.class, () -> metadata.environmentRequirements().clear());
            assertThrows(IllegalStateException.class, metadata::requireExecutionConfig);
            assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config, root));
        }
    }

    @Test void discoveryNeverResolvesEvenPresentEnvironmentVariables() throws Exception {
        assertNotNull(System.getenv("PATH"));
        write("config/db.yaml", "schemaVersion: att-dbhelper/v2.6\nid: orders\nname: Orders\ndescription: Orders DB\n"
                + "connection: {url: 'jdbc:unused', password: '${ENV:PATH}'}\n");
        Path config = config("dbhelpers: [config/db.yaml]\n");
        assertEquals("${ENV:PATH}", discovery(config, null).dbHelper("orders").password());
        assertEquals(System.getenv("PATH"), new FrameworkConfigLoader().load(config, root).dbHelper("orders").password());
    }

    @Test void discoveryPreservesStructuralProfileAndPathSecurityChecks() throws Exception {
        Path config = config("dbhelpers: [config/db.yaml]\n");
        write("config/db.yaml", "schemaVersion: att-dbhelper/v2.6\nid: orders\nname: Orders\ndescription: Invalid\nconnection: {url: 42}\n");
        assertEquals("invalid", discovery(config, null).helperDiscoveryFailures().get(0).diagnostic("SIT").get("state"));
        write("config/db.yaml", "[malformed\n");
        assertEquals("invalid", discovery(config, null).helperDiscoveryFailures().get(0).diagnostic("SIT").get("state"));
        config("dbhelpers: [../outside.yaml]\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
        Path outside = write("outside.yaml", "schemaVersion: att-dbhelper/v2.6\n");
        Files.delete(root.resolve("config/db.yaml"));
        Files.createSymbolicLink(root.resolve("config/db.yaml"), outside);
        config("dbhelpers: [config/db.yaml]\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
        config("environments: {SIT: {}, UAT: {}}\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, "PROD"));
        write("config/config.yaml", "schemaVersion: [broken\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
    }

    @Test void indexesDegradedMetadataWithTransitiveReadinessAndEnvironmentAffinity() throws Exception {
        write("config/db.yaml", "schemaVersion: att-dbhelper/v2.6\nid: orders\nname: Orders\ndescription: Orders DB\n"
                + "connection: {url: 'jdbc:unused', password: '${ENV:" + MISSING + "}'}\n");
        Path config = config("environments:\n  SIT: {dbhelpers: [config/db.yaml]}\n  UAT: {}\n"
                + "tools:\n  query:\n    name: Query\n    description: Read orders\n    call: \"#{db.orders.query(sql='select 1')}\"\n");
        write("templates/DEPENDENT/template.yaml", "schemaVersion: att-template/v3.6\nname: DEPENDENT\ndescription: Dependent target\nactions:\n"
                + "  query:\n    type: tool\n    call: \"#{query()}\"\n");
        write("templates/INDEPENDENT/template.yaml", "schemaVersion: att-template/v3.6\nname: INDEPENDENT\ndescription: Independent target\nactions:\n"
                + "  note:\n    type: log\n    message: safe\n");
        write("load/policy.yaml", "schemaVersion: att-load/v1.6\nload: {users: 1, duration: 1s}\n");
        try (PackageResourceInspector sit = inspector(config, "SIT"); PackageResourceInspector uat = inspector(config, "UAT")) {
            Map<String, Object> page = sit.inspect("list", "template", null, null, 0, 50);
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            Map<String, Map<String, Object>> byName = items.stream().collect(Collectors.toMap(item -> String.valueOf(item.get("name")), item -> item));
            Map<String, Object> dependent = byName.get("DEPENDENT"), independent = byName.get("INDEPENDENT");
            assertEquals("available", dependent.get("metadataState"));
            assertEquals("dependency-unresolved", ((Map<?, ?>) dependent.get("executionReadiness")).get("state"));
            assertTrue(dependent.get("executionReadiness").toString().contains(MISSING));
            assertEquals("not-validated", ((Map<?, ?>) independent.get("executionReadiness")).get("state"));
            assertFalse(independent.get("executionReadiness").toString().contains(MISSING));
            assertNotEquals(page.get("revisionDigest"), uat.inspect("list", "template", null, null, 0, 50).get("revisionDigest"));
            assertNotNull(sit.inspectDebugForm("template", String.valueOf(independent.get("resourceId")), null).get("input"));
            assertNotNull(sit.inspectQuickLoadForm("template", String.valueOf(independent.get("resourceId")), "virtualUsers", null).get("input"));
            assertNotNull(sit.inspectDebugForm("template", String.valueOf(dependent.get("resourceId")), null).get("input"));
            assertEquals("template", ((List<?>) sit.inspectLoadCatalog("", 0, 50, null).get("items")).stream()
                    .map(item -> ((Map<?, ?>) item).get("kind")).findFirst().orElse(null));
            assertFalse(page.toString().contains(root.toString()));
            assertThrows(IllegalArgumentException.class, () -> sit.validateDebugInput("template", "DEPENDENT", Collections.emptyMap()));
        }
    }

    @Test void metadataConfigurationsCannotCrossTheExecutionBoundary() throws Exception {
        FrameworkConfig metadata = discovery(config(""), null);
        Path workbook = root.resolve("testcase/cases.xlsx");
        write("testcase/cases.yaml", "schemaVersion: att-sidecar/v2.2\nid: cases\nexcel: {sheet: Cases, caseId: Case ID, tags: Tags}\nstages:\n  - key: invoke\n    template: Template\n");
        FrameworkConfig suite = new SuiteConfigResolver(root, metadata).resolve(workbook);
        assertTrue(suite.discoveryOnly());
        assertThrows(IllegalStateException.class, suite::requireExecutionConfig);
        assertThrows(IllegalStateException.class, () -> new att.exec.DbHelperExecutor(root, metadata));
        assertThrows(IllegalStateException.class, () -> new att.exec.MqHelperExecutor(root, metadata));
        assertThrows(IllegalStateException.class, () -> new att.exec.HttpHelperExecutor(root, metadata));
        assertThrows(IllegalStateException.class, () -> new att.exec.SshResourceExecutor(root, metadata));
        assertThrows(IllegalStateException.class, () -> new att.debug.DebugEngine(root, metadata).run(null));
        assertThrows(IllegalStateException.class, () -> new att.core.FrameworkEngine(root, metadata).run(null));
        assertThrows(IllegalStateException.class, () -> new att.exec.ToolInvoker(root, metadata).invoke(null, "missing", null, null, null));
    }

    @Test void isolatesMissingAndMalformedDescriptorsForAllHelperKindsWithoutLeakingSources() throws Exception {
        for (String type : new String[] { "db", "mq", "ssh", "http" }) {
            write("config/invalid-" + type + ".yaml", "[invalid: PRIVATE_DISCOVERY_CANARY\n");
            Path config = config(type + "helpers: [config/missing.yaml, config/invalid-" + type + ".yaml]\n");
            FrameworkConfig metadata = discovery(config, null);
            assertEquals(2, metadata.helperDiscoveryFailures().size(), type);
            assertTrue(metadata.environmentRequirements().isEmpty());
            assertEquals("unavailable", metadata.helperDiscoveryFailures().get(0).diagnostic("SIT").get("state"));
            assertEquals("invalid", metadata.helperDiscoveryFailures().get(1).diagnostic("SIT").get("state"));
            assertThrows(UnsupportedOperationException.class, () -> metadata.helperDiscoveryFailures().clear());
            assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(config, root));
            try (PackageResourceInspector inspector = inspector(config, null)) {
                Map<String, Object> page = inspector.inspect("list", "template", null, null, 0, 50);
                assertTrue(page.get("diagnostics").toString().contains("ATT-RESOURCE-HELPER-INVALID"), type);
                assertFalse(page.toString().contains("PRIVATE_DISCOVERY_CANARY"));
                assertFalse(page.toString().contains(root.toString()));
                assertNotNull(inspector.inspectLoadCatalog("", 0, 50, null));
            }
        }
    }

    @Test void retainsHealthyHelpersAndUnrelatedResourcesWhileFailedHelpersRemainUnresolved() throws Exception {
        write("config/good.yaml", "schemaVersion: att-dbhelper/v2.6\nid: healthy\nname: Healthy\ndescription: Healthy DB\n"
                + "connection: {url: 'jdbc:unused', password: '${ENV:" + MISSING + "}'}\n");
        write("config/bad.yaml", "schemaVersion: att-dbhelper/v2.6\nid: broken\nname: Broken\ndescription: Broken DB\n"
                + "connection: {url: 'jdbc:unused', password: '${ENV:ROLL_BACK_THIS_REQUIREMENT}'}\nresult: {maxCellBytes: 1024, maxBytes: 1}\n");
        Path config = config("dbhelpers: [config/bad.yaml, config/good.yaml]\n");
        FrameworkConfig metadata = discovery(config, null);
        assertNotNull(metadata.dbHelper("healthy"));
        assertNull(metadata.dbHelper("broken"));
        assertEquals(1, metadata.environmentRequirements().size());
        assertEquals(MISSING, metadata.environmentRequirements().get(0).variable());
        write("testcase/cases.yaml", "schemaVersion: att-sidecar/v2.2\nid: cases\nexcel: {sheet: Cases, caseId: Case ID, tags: Tags}\nstages:\n"
                + "  - key: invoke\n    template: INDEPENDENT\n");
        FrameworkConfig suite = new SuiteConfigResolver(root, metadata).resolve(root.resolve("testcase/cases.xlsx"));
        assertEquals(1, suite.helperDiscoveryFailures().size());
        assertThrows(IllegalStateException.class, suite::requireExecutionConfig);
        write("templates/DEPENDENT/template.yaml", "schemaVersion: att-template/v3.6\nname: DEPENDENT\ndescription: Dependent\nactions:\n"
                + "  query: {type: tool, call: \"#{db.broken.query(sql='select 1')}\"}\n");
        write("templates/INDEPENDENT/template.yaml", "schemaVersion: att-template/v3.6\nname: INDEPENDENT\ndescription: Independent\nactions:\n"
                + "  note: {type: log, message: safe}\n");
        try (PackageResourceInspector inspector = inspector(config, null)) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>)
                    inspector.inspect("list", "template", null, null, 0, 50).get("items");
            assertEquals(2, items.size());
            for (Map<String, Object> item : items)
                assertEquals("DEPENDENT".equals(item.get("name")) ? "dependency-unresolved" : "not-validated",
                        ((Map<?, ?>) item.get("executionReadiness")).get("state"));
        }
    }

    @Test void unsafeMissingPathsDuplicateIdsAndExcessiveDiscoveryRemainFatal() throws Exception {
        Path config = config("dbhelpers: [config/../outside.yaml]\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
        config("dbhelpers: [config/missing.yaml, config/missing.yaml]\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
        String helper = "schemaVersion: att-dbhelper/v2.6\nid: orders\nname: Orders\ndescription: DB\nconnection: {url: 'jdbc:unused'}\n";
        write("config/one.yaml", helper);
        write("config/two.yaml", helper.replace("id: orders", "id: ORDERS"));
        config("dbhelpers: [config/one.yaml, config/two.yaml]\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
        config("dbhelpers: [" + java.util.stream.IntStream.range(0, 257).mapToObj(i -> "config/missing" + i + ".yaml")
                .collect(Collectors.joining(",")) + "]\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
        Files.createSymbolicLink(root.resolve("config/link"), root.resolve("templates"));
        config("dbhelpers: [config/link/absent.yaml]\n");
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
    }

    @Test void oversizedDescriptorsRemainFatalAndPackageSchemasCannotOverrideTrustedRegistry() throws Exception {
        Path config = config("dbhelpers: [config/oversized.yaml]\n");
        Files.write(root.resolve("config/oversized.yaml"), new byte[3_000_001]);
        assertThrows(IllegalArgumentException.class, () -> discovery(config, null));
        config("dbhelpers: [config/missing.yaml]\n");
        Files.write(root.resolve("schemas/att-dbhelper-v2.6.schema.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        assertEquals("unavailable", discovery(config, null).helperDiscoveryFailures().get(0).diagnostic("SIT").get("state"));
    }

    @Test void manyMissingHelpersDoNotOverflowAnOtherwiseUsableMetadataPage() throws Exception {
        Path config = config("dbhelpers: [" + java.util.stream.IntStream.range(0, 100).mapToObj(i -> "config/missing" + i + ".yaml")
                .collect(Collectors.joining(",")) + "]\n");
        try (PackageResourceInspector inspector = inspector(config, null)) {
            Map<String, Object> page = inspector.inspect("list", "template", null, null, 0, 50);
            List<?> diagnostics = (List<?>) page.get("diagnostics");
            assertEquals(5, diagnostics.size());
            assertTrue(diagnostics.get(4).toString().contains("DIAGNOSTICS-TRUNCATED"));
            assertTrue(diagnostics.get(4).toString().contains("96"));
        }
    }

    private FrameworkConfig discovery(Path config, String environment) throws Exception {
        return new FrameworkConfigLoader().loadDiscovery(config, root, root, environment);
    }
    private PackageResourceInspector inspector(Path config, String environment) {
        return new PackageResourceInspector(root, config, environment, Collections.emptyList(), 65536, 262144, true);
    }
    private Path config(String fields) throws Exception {
        return write("config/config.yaml", "schemaVersion: att-config/v2.12\nenvironment: SIT\n" + fields);
    }
    private Path write(String path, String content) throws Exception {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        return Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
