package att.flow;

import att.config.PackageConfig;
import att.testcase.TestCaseDefinitionResolver;
import att.testcase.TestCaseResolutionResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Canonical Flow-root resolution and dual-root duplicate rejection. */
class PackageFlowRootsTest {
    @TempDir Path temp;

    @Test void discoversFlowsWithoutATemplatesDirectory() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("native-only"));
        writeFlow(pkg.resolve("flows/demo"), "demo.v1", "Native only");
        Files.write(pkg.resolve("package.yaml"),
                ("schemaVersion: att-package/v1\nmetadata:\n  name: Native\n").getBytes(StandardCharsets.UTF_8));
        PackageFlowRoots.Resolution roots = PackageFlowRoots.resolve(pkg,
                PackageFlowRoots.DEFAULT_FLOW_ROOT, Paths.get("templates"));
        assertEquals(1, roots.existingRoots().size());
        assertTrue(roots.configuredFlowRoot().endsWith(Paths.get("flows")));
        FlowRegistry registry = new FlowRegistry(pkg, roots, true, null, null);
        assertEquals(1, registry.ids().size());
        assertEquals("demo.v1", registry.ids().get(0));
        assertNotNull(registry.get("demo.v1"));
    }

    @Test void rejectsDuplicateIdsAcrossCanonicalAndLegacyCompanionRoots() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("dual"));
        writeFlow(pkg.resolve("flows/shared"), "shared.v1", "Canonical");
        writeFlow(pkg.resolve("templates/flows/shared"), "shared.v1", "Legacy companion");
        PackageFlowRoots.Resolution roots = PackageFlowRoots.resolve(pkg,
                PackageFlowRoots.DEFAULT_FLOW_ROOT, Paths.get("templates"));
        assertEquals(2, roots.existingRoots().size());
        Exception failure = assertThrows(Exception.class,
                () -> new FlowRegistry(pkg, roots, true, null, null));
        assertTrue(failure.getMessage().contains("Duplicate Flow ID")
                || (failure.getCause() != null && failure.getCause().getMessage() != null
                && failure.getCause().getMessage().contains("Duplicate Flow ID")),
                failure.getMessage());
    }

    @Test void keepsLegacyCompanionWhenCanonicalRootIsAbsent() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("legacy-only"));
        writeFlow(pkg.resolve("templates/flows/legacy"), "legacy.v1", "Companion");
        PackageFlowRoots.Resolution roots = PackageFlowRoots.resolve(pkg,
                PackageFlowRoots.DEFAULT_FLOW_ROOT, Paths.get("templates"));
        assertEquals(1, roots.existingRoots().size());
        FlowRegistry registry = new FlowRegistry(pkg, Paths.get("templates"), true);
        assertEquals(java.util.Collections.singletonList("legacy.v1"), registry.ids());
    }

    @Test void honorsConfiguredCustomFlowRootFromPackageManifest() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("custom-root"));
        writeFlow(pkg.resolve("custom-flows/payments"), "payments.offline.v1", "Custom root");
        Files.write(pkg.resolve("package.yaml"),
                ("schemaVersion: att-package/v2\nresources:\n  flowRoot: custom-flows\n  testcaseRoot: testcase\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(pkg.resolve("testcase/payments"));
        Files.write(pkg.resolve("testcase/payments/testcase.yaml"),
                ("schemaVersion: att-testcase/v1\nid: payments.offline\nflow: payments.offline.v1\n"
                        + "name: Offline\ndescription: Custom flowRoot fixture.\ninputs: {}\n")
                        .getBytes(StandardCharsets.UTF_8));

        PackageConfig manifest = PackageConfig.load(pkg);
        assertEquals(Paths.get("custom-flows"), manifest.flowRoot());
        PackageFlowRoots.Resolution roots = PackageFlowRoots.resolve(pkg, manifest);
        assertEquals(1, roots.existingRoots().size());
        assertTrue(roots.configuredFlowRoot().endsWith(Paths.get("custom-flows")));
        assertTrue(new FlowRegistry(pkg, roots, true, null, null).ids().contains("payments.offline.v1"));

        Path schema = repositoryRoot().resolve("schemas/att-testcase-v1.schema.json");
        TestCaseDefinitionResolver resolver = new TestCaseDefinitionResolver(schema);
        TestCaseResolutionResult resolution = resolver.resolveByDefinitionId(pkg, "custom",
                manifest.flowRoot(), manifest.templatesRoot(), manifest.testcaseRoot(),
                "payments.offline", TestCaseDefinitionResolver.Options.defaults());
        assertEquals(1, resolution.selectedCount());
        assertEquals("payments.offline.v1", resolution.selectedCases().get(0).flowId());
        assertFalse(Files.exists(pkg.resolve("flows")));
        assertFalse(Files.exists(pkg.resolve("templates")));
    }

    @Test void rejectsPathTraversalInConfiguredFlowRoot() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("escape"));
        Files.write(pkg.resolve("package.yaml"),
                ("schemaVersion: att-package/v2\nresources:\n  flowRoot: ../outside\n")
                        .getBytes(StandardCharsets.UTF_8));
        Exception failure = assertThrows(Exception.class, () -> PackageConfig.load(pkg));
        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("path")
                || failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("outside")
                || failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("noncanonical"),
                failure.getMessage());
    }

    @Test void rejectsSymlinkEscapeOnConfiguredFlowRoot() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside-flows"));
        writeFlow(outside.resolve("escape"), "escape.v1", "Outside");
        Path pkg = Files.createDirectories(temp.resolve("symlink-pkg"));
        Path link = pkg.resolve("linked-flows");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return;
        }
        Files.write(pkg.resolve("package.yaml"),
                ("schemaVersion: att-package/v2\nresources:\n  flowRoot: linked-flows\n")
                        .getBytes(StandardCharsets.UTF_8));
        Exception failure = assertThrows(Exception.class,
                () -> PackageFlowRoots.resolve(pkg, Paths.get("linked-flows"), Paths.get("templates")));
        assertNotNull(failure.getMessage());
    }

    @Test void rejectsOverlappingConfiguredFlowRootAndLegacyCompanion() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("overlap"));
        writeFlow(pkg.resolve("templates/flows/example"), "example.v1", "Nested");
        Files.write(pkg.resolve("package.yaml"),
                ("schemaVersion: att-package/v2\nresources:\n  flowRoot: templates\n  templatesRoot: templates\n")
                        .getBytes(StandardCharsets.UTF_8));
        PackageConfig manifest = PackageConfig.load(pkg);
        Exception failure = assertThrows(Exception.class, () -> PackageFlowRoots.resolve(pkg, manifest));
        assertTrue(failure.getMessage().contains("must not overlap"), failure.getMessage());
        assertFalse(failure.getMessage().contains("Duplicate Flow ID"), failure.getMessage());
    }

    @Test void rejectsDuplicateIdsAcrossCustomFlowRootAndLegacyCompanion() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("custom-dual"));
        writeFlow(pkg.resolve("custom-flows/shared"), "shared.v1", "Custom canonical");
        writeFlow(pkg.resolve("templates/flows/shared"), "shared.v1", "Legacy companion");
        Files.write(pkg.resolve("package.yaml"),
                ("schemaVersion: att-package/v2\nresources:\n  flowRoot: custom-flows\n")
                        .getBytes(StandardCharsets.UTF_8));
        PackageConfig manifest = PackageConfig.load(pkg);
        PackageFlowRoots.Resolution roots = PackageFlowRoots.resolve(pkg, manifest);
        assertEquals(2, roots.existingRoots().size());
        Exception failure = assertThrows(Exception.class,
                () -> new FlowRegistry(pkg, roots, true, null, null));
        assertTrue(failure.getMessage().contains("Duplicate Flow ID")
                || (failure.getCause() != null && failure.getCause().getMessage() != null
                && failure.getCause().getMessage().contains("Duplicate Flow ID")),
                failure.getMessage());
    }

    @Test void treatsMissingOptionalFlowRootAsEmptyWhilePresentInvalidRootFails() throws Exception {
        Path pkg = Files.createDirectories(temp.resolve("missing-vs-invalid"));
        PackageFlowRoots.Resolution missing = PackageFlowRoots.resolve(pkg,
                PackageFlowRoots.DEFAULT_FLOW_ROOT, Paths.get("templates"));
        assertTrue(missing.isEmpty());

        Path invalid = pkg.resolve("flows");
        Files.write(invalid, "not-a-directory".getBytes(StandardCharsets.UTF_8));
        Exception failure = assertThrows(Exception.class,
                () -> PackageFlowRoots.resolve(pkg, PackageFlowRoots.DEFAULT_FLOW_ROOT, Paths.get("templates")));
        assertTrue(failure.getMessage().contains("Flow root must be a non-symlink directory"), failure.getMessage());
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        Path candidate = current.resolve("schemas/att-testcase-v1.schema.json");
        if (Files.isRegularFile(candidate)) return current;
        return current.getParent();
    }

    private static void writeFlow(Path directory, String id, String name) throws Exception {
        Files.createDirectories(directory);
        Files.write(directory.resolve("flow.yaml"),
                ("schemaVersion: att-flow/v3.6\nid: " + id + "\nname: " + name + "\n"
                        + "description: " + name + " fixture.\n"
                        + "actions:\n  note:\n    type: log\n    message: " + name + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }
}
