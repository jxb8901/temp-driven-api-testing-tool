package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PackageToolRegistryTest {
    @TempDir Path temp;

    private Path tool(Path root, String filename, String yaml) throws Exception {
        Path directory = root.resolve("tools");
        Files.createDirectories(directory);
        Path path = directory.resolve(filename);
        Files.write(path, yaml.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private Path config() throws Exception {
        att.TestSchemas.install(temp);
        Path file = temp.resolve("config/config.yaml");
        Files.createDirectories(file.getParent());
        Files.write(file, "schemaVersion: att-config/v2.13\n".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test void duplicateIdsAcrossPackagesResolveIndependently() throws Exception {
        Path a = temp.resolve("packages/a"), b = temp.resolve("packages/b");
        tool(a, "first.yaml", "schemaVersion: att-tool/v1\nid: queryTransaction\nname: A\n"
                + "description: A only\ncall: \"#{upper('A')}\"\narguments: {}\n");
        tool(b, "second.yaml", "schemaVersion: att-tool/v1\nid: queryTransaction\nname: B\n"
                + "description: B only\ncall: \"#{upper('B')}\"\narguments: {}\n");
        Path central = config();
        FrameworkConfigLoader loader = new FrameworkConfigLoader();
        FrameworkConfig alpha = loader.load(central, temp, a, null);
        FrameworkConfig beta = loader.load(central, temp, b, null);
        assertEquals("A", alpha.tool("queryTransaction").name());
        assertEquals("B", beta.tool("queryTransaction").name());
        assertNotEquals(alpha.effectiveConfiguration().identity(), beta.effectiveConfiguration().identity());
        assertEquals(1, alpha.tools().size());
        assertEquals(1, beta.tools().size());
        assertNull(alpha.tool("missing"));
    }

    @Test void newDescriptorContentChangesEffectiveIdentity() throws Exception {
        Path pkg = temp.resolve("packages/revision");
        Path source = tool(pkg, "query.yaml", "schemaVersion: att-tool/v1\nid: query\nname: Query\n"
                + "description: before\ncall: \"#{upper('X')}\"\narguments: {}\n");
        Path central = config();
        String before = new FrameworkConfigLoader().load(central, temp, pkg, null).effectiveConfiguration().identity();
        Files.write(source, ("schemaVersion: att-tool/v1\nid: query\nname: Query\n"
                + "description: after\ncall: \"#{upper('X')}\"\narguments: {}\n").getBytes(StandardCharsets.UTF_8));
        String after = new FrameworkConfigLoader().load(central, temp, pkg, null).effectiveConfiguration().identity();
        assertNotEquals(before, after);
    }

    @Test void absentToolsDirectoryIsValidWithoutGlobalFallback() throws Exception {
        Path pkg = temp.resolve("packages/empty");
        Files.createDirectories(pkg);
        FrameworkConfig actual = new FrameworkConfigLoader().load(config(), temp, pkg, null);
        assertTrue(actual.tools().isEmpty());
    }

    @Test void duplicateAndInvalidToolDescriptorsAreRejected() throws Exception {
        Path pkg = temp.resolve("packages/duplicate");
        String source = "schemaVersion: att-tool/v1\nid: once\nname: Once\n"
                + "description: duplicate\ncall: \"#{upper('X')}\"\narguments: {}\n";
        tool(pkg, "a.yaml", source);
        tool(pkg, "b.yaml", source);
        assertThrows(Exception.class, () -> PackageToolRegistry.load(pkg));
        Files.delete(pkg.resolve("tools/b.yaml"));
        Files.write(pkg.resolve("tools/a.yaml"), ("schemaVersion: att-tool/v1\nid: once\nname: Once\n"
                + "description: invalid\ncall: \"#{upper('X')}\"\ncommand: [echo, hello]\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> PackageToolRegistry.load(pkg));
    }

    @Test void reservedGlobalHelperCallableNamesFail() throws Exception {
        Path pkg = temp.resolve("packages/helper");
        tool(pkg, "query.yaml", "schemaVersion: att-tool/v1\nid: db.orders.query\n"
                + "name: Bad\ncall: \"#{upper('X')}\"\n");
        assertThrows(Exception.class, () -> PackageToolRegistry.load(pkg));
    }

    @Test void legacyGlobalToolsRequireExplicitMigration() throws Exception {
        Path pkg = temp.resolve("packages/test");
        Files.createDirectories(pkg);
        Path central = config();
        Files.write(central, ("schemaVersion: att-config/v2.13\n"
                + "tools:\n  global:\n    name: Legacy\n    description: No fallback\n"
                + "    call: \"#{upper('X')}\"\n").getBytes(StandardCharsets.UTF_8));
        Exception failure = assertThrows(Exception.class,
                () -> new FrameworkConfigLoader().load(central, temp, pkg, null));
        assertTrue(String.valueOf(failure.getMessage()).contains("Legacy")
                || String.valueOf(failure.getMessage()).contains("Tool"));
    }
}
