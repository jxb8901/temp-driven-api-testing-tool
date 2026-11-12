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
        Files.createDirectories(path.getParent());
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
    @Test void groupedToolKeepsQualifiedIdentityAndTracksManifestRevision() throws Exception {
        Path pkg = temp.resolve("packages/fpp");
        tool(pkg, "fpp/tool-group.yaml", "schemaVersion: att-tool-group/v3.0\nid: fpp\n"
                + "name: FPP tools\ndescription: FPP group\ntools: [invokeApi.yaml]\n");
        tool(pkg, "fpp/invokeApi.yaml", "schemaVersion: att-tool/v1\nid: invokeApi\n"
                + "name: Invoke API\ndescription: FPP integration\ncall: \"#{upper('ok')}\"\narguments: {}\n");
        Path central = config();
        FrameworkConfig loaded = new FrameworkConfigLoader().load(central, temp, pkg, null);
        ToolConfig call = loaded.tool("fpp.invokeApi");
        assertNotNull(call);
        assertEquals("fpp", call.groupId());
        assertEquals("invokeApi", call.localKey());
        assertEquals("invokeApi.yaml", call.sourceFile().getFileName().toString());
        assertNull(loaded.tool("invokeApi"));
        String before = loaded.effectiveConfiguration().identity();
        tool(pkg, "fpp/tool-group.yaml", "schemaVersion: att-tool-group/v3.0\nid: fpp\n"
                + "name: FPP tools\ndescription: Updated group\ntools: [invokeApi.yaml]\n");
        String after = new FrameworkConfigLoader().load(central, temp, pkg, null)
                .effectiveConfiguration().identity();
        assertNotEquals(before, after, "Group source changes must invalidate an accepted Tool revision");
    }

    @Test void groupedDispatcherRetainsCommandAndGlobalSshBinding() throws Exception {
        Path pkg = temp.resolve("packages/dispatch");
        tool(pkg, "sample/tool-group.yaml", "schemaVersion: att-tool-group/v3.0\nid: sample\n"
                + "name: Sample\ndescription: Dispatcher\n"
                + "script: [./tools/dispatch.sh, --read-only]\n"
                + "ssh: {helper: appserver, selection: {strategy: roundRobin}}\n"
                + "tools: [getAcDate.yaml]\n");
        tool(pkg, "sample/getAcDate.yaml", "schemaVersion: att-tool/v1\nid: getAcDate\n"
                + "name: Account date\ndescription: Get date\ncommand: [echo, ok]\n"
                + "stdoutFormat: text\n");
        att.TestSchemas.install(temp);
        ToolConfig value = PackageToolRegistry.load(pkg).get("sample.getAcDate");
        assertNotNull(value);
        assertEquals(java.util.Arrays.asList("./tools/dispatch.sh", "--read-only"), value.groupScriptArgv());
        assertEquals(java.util.Arrays.asList("echo", "ok"), value.commandArgv());
        assertEquals("appserver", value.sshHelper());
        assertEquals("roundRobin", value.sshSelectionStrategy());
    }

    @Test void changingDispatcherOrMemberScriptInvalidatesAcceptedEffectiveRevision() throws Exception {
        Path pkg = temp.resolve("packages/script-closure");
        tool(pkg, "sample/tool-group.yaml",
                "schemaVersion: att-tool-group/v3.0\nid: sample\nname: Sample\n"
                + "description: Tool Group\nscript: [./tools/dispatch.sh]\n"
                + "tools: [getAcDate.yaml]\n");
        tool(pkg, "sample/getAcDate.yaml",
                "schemaVersion: att-tool/v1\nid: getAcDate\nname: Account date\n"
                + "description: Read account date\ncommand: [./tools/date.sh]\nstdoutFormat: text\n");
        Path dispatcher = tool(pkg, "dispatch.sh", "#!/bin/sh\necho dispatch\n");
        Path member = tool(pkg, "date.sh", "#!/bin/sh\necho first\n");
        Path central = config();
        FrameworkConfigLoader loader = new FrameworkConfigLoader();
        String accepted = loader.load(central, temp, pkg, null).effectiveConfiguration().identity();
        Files.write(member, "#!/bin/sh\necho second\n".getBytes(StandardCharsets.UTF_8));
        String afterMember = loader.load(central, temp, pkg, null).effectiveConfiguration().identity();
        assertNotEquals(accepted, afterMember, "Editing only a referenced member script must make an accepted Job stale");
        Files.write(dispatcher, "#!/bin/sh\necho revised\n".getBytes(StandardCharsets.UTF_8));
        String afterDispatcher = loader.load(central, temp, pkg, null).effectiveConfiguration().identity();
        assertNotEquals(afterMember, afterDispatcher, "Editing only a dispatcher script must make an accepted Job stale");
    }

    @Test void invalidGroupMembershipAndCollisionsFailClosed() throws Exception {
        Path pkg = temp.resolve("packages/invalid-group");
        tool(pkg, "first/tool-group.yaml", "schemaVersion: att-tool-group/v3.0\nid: payments\n"
                + "name: Payments\ndescription: Group\ntools: [missing.yaml]\n");
        att.TestSchemas.install(temp);
        assertThrows(Exception.class, () -> PackageToolRegistry.load(pkg));
        tool(pkg, "first/missing.yaml", "schemaVersion: att-tool/v1\nid: query\n"
                + "name: Query\ndescription: Query\ncall: \"#{upper('X')}\"\n");
        assertNotNull(PackageToolRegistry.load(pkg).get("payments.query"));
        tool(pkg, "second/tool-group.yaml", "schemaVersion: att-tool-group/v3.0\nid: payments\n"
                + "name: Duplicate\ndescription: Duplicate ID\ntools: [query.yaml]\n");
        tool(pkg, "second/query.yaml", "schemaVersion: att-tool/v1\nid: query\n"
                + "name: Query\ndescription: Query\ncall: \"#{upper('Y')}\"\n");
        assertThrows(Exception.class, () -> PackageToolRegistry.load(pkg));
        Files.delete(pkg.resolve("tools/second/tool-group.yaml"));
        tool(pkg, "first/tool-group.yaml", "schemaVersion: att-tool-group/v3.0\nid: payments\n"
                + "name: Payments\ndescription: Group\ntools: [missing.yaml, missing.yaml]\n");
        assertThrows(Exception.class, () -> PackageToolRegistry.load(pkg));
    }

    @Test void unlistedGroupFilesAreNotDiscoveredAndRootFilesStayStandalone() throws Exception {
        Path pkg = temp.resolve("packages/mixed");
        tool(pkg, "fpp/tool-group.yaml", "schemaVersion: att-tool-group/v3.0\nid: fpp\n"
                + "name: FPP\ndescription: Group\ntools: [invokeApi.yaml]\n");
        tool(pkg, "fpp/invokeApi.yaml", "schemaVersion: att-tool/v1\nid: invokeApi\n"
                + "name: Invoke\ndescription: Invoke\ncall: \"#{upper('A')}\"\n");
        tool(pkg, "fpp/unlisted.yaml", "schemaVersion: att-tool/v1\nid: hidden\n"
                + "name: Hidden\ndescription: Not listed\ncall: \"#{upper('B')}\"\n");
        tool(pkg, "standalone.yaml", "schemaVersion: att-tool/v1\nid: standalone\n"
                + "name: Standalone\ndescription: Standalone\ncall: \"#{upper('C')}\"\n");
        att.TestSchemas.install(temp);
        java.util.Map<String, ToolConfig> result = PackageToolRegistry.load(pkg);
        assertEquals(2, result.size());
        assertTrue(result.containsKey("fpp.invokeApi"));
        assertTrue(result.containsKey("standalone"));
        assertFalse(result.containsKey("fpp.hidden"));
    }

}
