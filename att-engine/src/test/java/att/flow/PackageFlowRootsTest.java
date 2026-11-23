package att.flow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                PackageFlowRoots.DEFAULT_FLOW_ROOT, java.nio.file.Paths.get("templates"));
        assertEquals(1, roots.existingRoots().size());
        assertTrue(roots.configuredFlowRoot().endsWith(java.nio.file.Paths.get("flows")));
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
                PackageFlowRoots.DEFAULT_FLOW_ROOT, java.nio.file.Paths.get("templates"));
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
                PackageFlowRoots.DEFAULT_FLOW_ROOT, java.nio.file.Paths.get("templates"));
        assertEquals(1, roots.existingRoots().size());
        FlowRegistry registry = new FlowRegistry(pkg, java.nio.file.Paths.get("templates"), true);
        assertEquals(java.util.Collections.singletonList("legacy.v1"), registry.ids());
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
