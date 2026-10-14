package att.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PackageConfigurationFileInspectorTest {
    @TempDir Path temp;

    @Test void listsAndSearchesOnlyPackageConfigurationWithBoundedPagination() throws Exception {
        Path root = packageRoot();
        write(root.resolve("config/alpha.yaml"), "name: alpha\n");
        write(root.resolve("config/nested/needle.json"), "{\"value\":1}\n");
        write(root.resolve("config/nested/other.sql"), "select 1;\n");
        write(root.resolve("config/private.bin"), "opaque-binary-placeholder");
        PackageConfigurationFileInspector inspector = inspector(root, Collections.singleton("config/alpha.yaml"));

        Map<String, Object> first = inspector.list("", null, 0, 3);
        assertEquals("ready", first.get("state"));
        assertEquals(4, first.get("total"));
        assertEquals(3, first.get("nextOffset"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> firstItems = (List<Map<String, Object>>) first.get("items");
        assertEquals(3, firstItems.size());
        assertTrue(firstItems.stream().anyMatch(item -> "directory".equals(item.get("kind"))));
        assertFalse(first.toString().contains(root.toString()));

        Map<String, Object> second = inspector.list("config/nested", null, 0, 100);
        @SuppressWarnings("unchecked") List<Map<String, Object>> nested = (List<Map<String, Object>>) second.get("items");
        assertEquals(2, nested.size());
        assertEquals("needle.json", nested.get(0).get("name"));

        Map<String, Object> search = inspector.list(null, "needle", 0, 1);
        assertEquals(1, search.get("total"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> matches = (List<Map<String, Object>>) search.get("items");
        assertEquals("config/nested/needle.json", matches.get(0).get("path"));
        assertEquals("ready", search.get("state"));
    }

    @Test void marksOnlyAllowlistedSupportedSmallTextAsSourceAvailable() throws Exception {
        Path root = packageRoot();
        write(root.resolve("config/visible.yaml"), "name: visible\n");
        write(root.resolve("config/large.yaml"), "0123456789abcdef-more-than-limit\n");
        write(root.resolve("config/unsupported.bin"), "ordinary text\n");
        write(root.resolve("config/private.bin"), "opaque");
        PackageConfigurationFileInspector inspector = inspector(root,
                new java.util.HashSet<>(java.util.Arrays.asList("config/visible.yaml", "config/large.yaml", "config/unsupported.bin")), 16);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) inspector.list("", null, 0, 100).get("items");
        Map<String, Map<String, Object>> byPath = items.stream().collect(Collectors.toMap(
                item -> String.valueOf(item.get("path")), item -> item));

        assertEquals(Boolean.TRUE, byPath.get("config/visible.yaml").get("sourceAvailable"));
        assertEquals("yaml", byPath.get("config/visible.yaml").get("format"));
        assertEquals(Boolean.FALSE, byPath.get("config/large.yaml").get("sourceAvailable"));
        assertEquals("size-limit", byPath.get("config/large.yaml").get("reason"));
        assertEquals(Boolean.FALSE, byPath.get("config/unsupported.bin").get("sourceAvailable"));
        assertEquals("unsupported-file-type", byPath.get("config/unsupported.bin").get("reason"));
        assertFalse(byPath.get("config/private.bin").containsKey("text"));
    }

    @Test void rejectsTraversalAndAmbiguousDirectorySegments() throws Exception {
        PackageConfigurationFileInspector inspector = inspector(packageRoot(), Collections.emptySet());
        for (String path : java.util.Arrays.asList("../outside", "config/../outside", "config/./nested",
                "config//nested", "/config/nested", "config/nested/")) {
            assertThrows(IllegalArgumentException.class, () -> inspector.list(path, null, 0, 20), path);
        }
        assertThrows(IllegalArgumentException.class, () -> inspector.list("", null, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> inspector.list("", null, -1, 20));
    }

    @Test void omitsSymlinksAndRejectsExplicitExternalSymlinkTraversal() throws Exception {
        Path root = packageRoot();
        Path external = Files.createDirectories(temp.resolve("outside"));
        write(external.resolve("secret.yaml"), "password: outside\n");
        Path link = root.resolve("config/external");
        try {
            Files.createSymbolicLink(link, external);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable in this test environment");
        }
        PackageConfigurationFileInspector inspector = inspector(root, Collections.emptySet());
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) inspector.list("", null, 0, 100).get("items");
        assertFalse(items.stream().anyMatch(item -> "config/external".equals(item.get("path"))));
        assertThrows(IllegalArgumentException.class, () -> inspector.list("config/external", null, 0, 20));
    }

    @Test void returnsUnavailableWhenThePackageHasNoConfigDirectory() throws Exception {
        Path root = Files.createDirectories(temp.resolve("without-config"));
        Map<String, Object> result = inspector(root, Collections.emptySet()).list("", null, 0, 20);
        assertEquals("unavailable", result.get("state"));
        assertEquals(Collections.emptyList(), result.get("items"));
        assertFalse(result.toString().contains(root.toString()));
    }

    private PackageConfigurationFileInspector inspector(Path root, java.util.Set<String> allowlist) {
        return inspector(root, allowlist, 65536);
    }

    private PackageConfigurationFileInspector inspector(Path root, java.util.Set<String> allowlist, int maxSourceBytes) {
        return new PackageConfigurationFileInspector(root, allowlist, maxSourceBytes, 262144);
    }

    private Path packageRoot() throws Exception {
        Path root = Files.createDirectories(temp.resolve("package-" + System.nanoTime()));
        Files.createDirectories(root.resolve("config/nested"));
        write(root.resolve("config/config.yaml"), "schemaVersion: att-config/v2.12\n");
        return root;
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }
}
