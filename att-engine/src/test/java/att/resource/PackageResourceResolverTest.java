package att.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackageResourceResolverTest {
    @TempDir Path root;

    @Test void resolvesBareNamesFromPackageRootAndExplicitRelativeNamesFromDescriptorOrigin() throws Exception {
        Files.createDirectories(root.resolve("templates/payment/fragments"));
        Files.write(root.resolve("shared.txt"), "root".getBytes("UTF-8"));
        Files.write(root.resolve("templates/payment/body.txt"), "local".getBytes("UTF-8"));
        PackageResourceResolver resolver = new PackageResourceResolver(root);
        PackageResourceResolver.ResourceOrigin origin = resolver.origin(root.resolve("templates/payment"));

        assertEquals("shared.txt", resolver.resolve(new PackageResourceResolver.ResourceLocator("shared.txt"), origin,
                PackageResourceResolver.Kind.FILE).logicalName());
        assertEquals("templates/payment/body.txt", resolver.resolve(new PackageResourceResolver.ResourceLocator("./body.txt"), origin,
                PackageResourceResolver.Kind.FILE).logicalName());
        assertEquals("templates/payment/fragments", resolver.resolve(new PackageResourceResolver.ResourceLocator("./fragments"), origin,
                PackageResourceResolver.Kind.DIRECTORY).logicalName());
    }

    @Test void rejectsAbsoluteAndEscapingNamesWithLogicalDiagnostics() throws Exception {
        Files.createDirectories(root.resolve("templates/payment"));
        PackageResourceResolver resolver = new PackageResourceResolver(root);
        PackageResourceResolver.ResourceOrigin origin = resolver.origin(root.resolve("templates/payment"));

        IllegalArgumentException traversal = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                new PackageResourceResolver.ResourceLocator("../../../outside.txt"), origin, PackageResourceResolver.Kind.FILE));
        assertEquals("Package resource escapes the package root: ../../../outside.txt", traversal.getMessage());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                new PackageResourceResolver.ResourceLocator(root.resolve("shared.txt").toString()), origin,
                PackageResourceResolver.Kind.FILE));
    }

    @Test void rejectsSymlinkEscape() throws Exception {
        Path outside = Files.createTempFile("att-resource-outside", ".txt");
        Path link = root.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            return;
        }
        PackageResourceResolver resolver = new PackageResourceResolver(root);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                new PackageResourceResolver.ResourceLocator("linked.txt"), resolver.origin(root), PackageResourceResolver.Kind.FILE));
        assertEquals("Package resource escapes the package root: linked.txt", error.getMessage());
    }
}
