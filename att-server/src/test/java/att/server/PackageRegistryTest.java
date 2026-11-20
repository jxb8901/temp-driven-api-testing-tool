package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PackageRegistryTest {
    @TempDir Path temp;

    @Test void equivalentConfigReformatKeepsTheRegistryRevisionAndGeneration() throws Exception {
        Fixture fixture=fixture();
        try(PackageRegistry registry=new PackageRegistry(fixture.config,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(300),
                Runnable::run,(before,after)->fail("A semantic no-op must not revoke registry-bound work"))) {
            PackageRegistry.Snapshot initial=registry.snapshot();
            writeYaml(fixture,Files.readString(fixture.configFile)+"\n# formatting-only update\n");
            settle(registry);
            PackageRegistry.Snapshot reformatted=registry.snapshot();
            assertEquals(initial.revision,reformatted.revision);
            assertEquals(initial.generation,reformatted.generation);
            assertEquals(Set.of("alpha"),reformatted.packages.keySet());
        }
    }

    @Test void atomicallyPublishesWholeRegistryAndTracksContentRevision() throws Exception {
        Fixture fixture=fixture();
        PackageRegistry[] holder=new PackageRegistry[1];
        AtomicBoolean coordinatorSawOldSnapshot=new AtomicBoolean();
        PackageRegistry registry=new PackageRegistry(fixture.config,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(300),
                atomicInstall->{
                    PackageRegistry.Snapshot before=holder[0].snapshot();
                    boolean first=!coordinatorSawOldSnapshot.getAndSet(true);
                    if(first)assertEquals(Set.of("alpha"),before.packages.keySet());
                    atomicInstall.run();
                    PackageRegistry.Snapshot after=holder[0].snapshot();
                    if(first)assertEquals(Set.of("alpha","beta"),after.packages.keySet());
                    else assertEquals(Set.of("alpha"),after.packages.keySet());
                    assertNotEquals(before.revision,after.revision);
                },(before,after)->{});
        holder[0]=registry;
        try {
            PackageRegistry.Snapshot initial=registry.snapshot();
            assertEquals(1,initial.generation);
            assertEquals(fixture.alpha.toRealPath(),initial.packages.get("alpha"));

            writeYaml(fixture,registryYaml(fixture,Map.of("alpha",fixture.beta,"beta",fixture.alpha)));
            replaceConfigAtomically(fixture);
            settle(registry);

            PackageRegistry.Snapshot repointed=registry.snapshot();
            assertEquals(2,repointed.generation);
            assertEquals(fixture.beta.toRealPath(),repointed.packages.get("alpha"));
            assertEquals(fixture.alpha.toRealPath(),repointed.packages.get("beta"));
            assertTrue(coordinatorSawOldSnapshot.get());

            writeYaml(fixture,registryYaml(fixture,Map.of("alpha",fixture.beta)));
            settle(registry);
            PackageRegistry.Snapshot removed=registry.snapshot();
            assertEquals(3,removed.generation);
            assertEquals(Set.of("alpha"),removed.packages.keySet());
            assertNotEquals(repointed.revision,removed.revision);
            assertTrue(removed.view().toString().contains("package:alpha"));
            assertFalse(removed.view().toString().contains(temp.toString()),"Registry contracts must not expose physical paths");
        } finally { registry.close(); }
    }

    @Test void aReloadedEmptyRegistryCanBeLoadedAgainAtRestart() throws Exception {
        Fixture fixture=fixture();
        try(PackageRegistry registry=new PackageRegistry(fixture.config,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(300),
                Runnable::run,(before,after)->{})) {
            writeYaml(fixture,registryYaml(fixture,Map.of()));
            settle(registry);
            assertTrue(registry.snapshot().packages.isEmpty());
            assertEquals("Ready",registry.snapshot().status);
        }

        ServerConfig restarted=ServerConfig.load(fixture.configFile);
        assertTrue(restarted.packages.isEmpty());
        try(PackageRegistry registry=new PackageRegistry(restarted,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(300),
                Runnable::run,(before,after)->{})) {
            assertTrue(registry.snapshot().packages.isEmpty());
            assertEquals("Ready",registry.snapshot().status);
        }
    }

    @Test void observesAtomicReplacementBetweenConfigLoadAndRegistryConstruction() throws Exception {
        Fixture fixture=fixture();
        String replacement=registryYaml(fixture,Map.of("alpha",fixture.beta));
        replaceConfigAtomically(fixture,replacement);

        try(PackageRegistry registry=new PackageRegistry(fixture.config,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(300),
                Runnable::run,(before,after)->{})) {
            assertEquals(fixture.alpha.toRealPath(),registry.snapshot().packages.get("alpha"),
                    "The running startup config remains the one ServerConfig validated");
            settle(registry);
            PackageRegistry.Snapshot updated=registry.snapshot();
            assertEquals("Ready",updated.status);
            assertEquals(2,updated.generation);
            assertEquals(fixture.beta.toRealPath(),updated.packages.get("alpha"),
                    "The replacement must be evaluated instead of being pinned as already processed");
        }
    }

    @Test void startupReplacementCannotChangeTheBootstrapDeploymentPolicy() throws Exception {
        Fixture fixture=fixture();
        String replacement=registryYaml(fixture,Map.of("alpha",fixture.beta))
                .replace("server:\n", "server:\n  authenticationRequired: false\n");
        replaceConfigAtomically(fixture,replacement);

        try(PackageRegistry registry=new PackageRegistry(fixture.config,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(300),
                Runnable::run,(before,after)->{})) {
            settle(registry);
            PackageRegistry.Snapshot snapshot=registry.snapshot();
            assertEquals("Invalid",snapshot.status);
            assertEquals("ATT-DEPLOYMENT-POLICY-RESTART-REQUIRED",snapshot.diagnostic.code());
            assertEquals(fixture.alpha.toRealPath(),snapshot.packages.get("alpha"));
            assertEquals(1,snapshot.generation);
        }
    }

    @Test void retriesInvalidCandidateWhenMissingPackageDirectoryAppears() throws Exception {
        Fixture fixture=fixture();
        Path recovering=fixture.packageRoot.resolve("recovering");
        String candidate=registryYaml(fixture,Map.of("alpha",recovering));
        try(PackageRegistry registry=new PackageRegistry(fixture.config,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(300),
                Runnable::run,(before,after)->{})) {
            String initialRevision=registry.snapshot().revision;
            writeYaml(fixture,candidate);
            settle(registry);
            assertInvalidWithLastKnownGood(registry,initialRevision,fixture.alpha.toRealPath(),"ATT-SOURCE-REGISTRY-INVALID");

            Files.createDirectory(recovering);
            Thread.sleep(25);
            registry.pollNow();

            PackageRegistry.Snapshot recovered=registry.snapshot();
            assertEquals("Ready",recovered.status);
            assertEquals(2,recovered.generation);
            assertEquals(recovering.toRealPath(),recovered.packages.get("alpha"));
            assertEquals(candidate,Files.readString(fixture.configFile),"Filesystem recovery must not require a YAML rewrite");
        }
    }

    @Test void malformedUnauthorizedSymlinkPermissionAndPartialWritesRetainLastKnownGood() throws Exception {
        Fixture fixture=fixture();
        try(PackageRegistry registry=new PackageRegistry(fixture.config,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(120),
                Runnable::run,(before,after)->{})) {
            String revision=registry.snapshot().revision;
            Path registered=fixture.alpha.toRealPath();

            writeYaml(fixture,"server: [partial");
            settle(registry);
            assertInvalidWithLastKnownGood(registry,revision,registered,"ATT-SOURCE-REGISTRY-INVALID");

            Path outside=Files.createDirectories(temp.resolve("unapproved"));
            writeYaml(fixture,registryYaml(fixture,Map.of("alpha",outside)));
            settle(registry);
            assertInvalidWithLastKnownGood(registry,revision,registered,"ATT-SOURCE-REGISTRY-INVALID");

            writeYaml(fixture,registryYaml(fixture,Map.of("alpha",fixture.beta)).replace("packages:\n", "packages:\n  fallback: {}\n"));
            settle(registry);
            assertInvalidWithLastKnownGood(registry,revision,registered,"ATT-SOURCE-REGISTRY-INVALID");

            Path link=fixture.packageRoot.resolve("unsafe-link");
            Files.createSymbolicLink(link,fixture.beta);
            writeYaml(fixture,registryYaml(fixture,Map.of("alpha",link)));
            settle(registry);
            assertInvalidWithLastKnownGood(registry,revision,registered,"ATT-SOURCE-REGISTRY-INVALID");

            try {
                Set<PosixFilePermission> original=Files.getPosixFilePermissions(fixture.beta);
                try {
                    Files.setPosixFilePermissions(fixture.beta,Set.of());
                    writeYaml(fixture,registryYaml(fixture,Map.of("alpha",fixture.beta)));
                    settle(registry);
                    assertInvalidWithLastKnownGood(registry,revision,registered,"ATT-SOURCE-REGISTRY-INVALID");
                } finally { Files.setPosixFilePermissions(fixture.beta,original); }
            } catch(UnsupportedOperationException ignored) {
                // Permission-bit validation is covered where POSIX attributes are available.
            }
        }
    }

    @Test void unrelatedDeploymentPolicyChangesDoNotPartiallyApplyPackageEntries() throws Exception {
        Fixture fixture=fixture();
        try(PackageRegistry registry=new PackageRegistry(fixture.config,Clock.systemUTC(),Duration.ofMillis(15),Duration.ofMillis(300),
                Runnable::run,(before,after)->{})) {
            String revision=registry.snapshot().revision;
            String yaml=registryYaml(fixture,Map.of("alpha",fixture.beta)).replace("server:\n", "server:\n  authenticationRequired: false\n");
            writeYaml(fixture,yaml);
            settle(registry);
            PackageRegistry.Snapshot snapshot=registry.snapshot();
            assertEquals(revision,snapshot.revision);
            assertEquals(fixture.alpha.toRealPath(),snapshot.packages.get("alpha"));
            assertEquals("Invalid",snapshot.status);
            assertEquals("ATT-DEPLOYMENT-POLICY-RESTART-REQUIRED",snapshot.diagnostic.code());
            assertFalse(snapshot.diagnostic.summary().contains(temp.toString()));
        }
    }

    private void assertInvalidWithLastKnownGood(PackageRegistry registry,String revision,Path registered,String code) {
        PackageRegistry.Snapshot snapshot=registry.snapshot();
        assertEquals("Invalid",snapshot.status);
        assertEquals(code,snapshot.diagnostic.code());
        assertEquals(revision,snapshot.revision);
        assertEquals(registered,snapshot.packages.get("alpha"));
        assertFalse(snapshot.diagnostic.summary().contains(temp.toString()));
    }

    private Fixture fixture() throws Exception {
        Path packageRoot=Files.createDirectories(temp.resolve("packages"));
        Path alpha=Files.createDirectory(packageRoot.resolve("alpha"));
        Path beta=Files.createDirectory(packageRoot.resolve("beta"));
        Path runtimeDirectory=Files.createDirectories(temp.resolve("runtime"));
        Path runtimeConfig=Files.writeString(runtimeDirectory.resolve("config.yaml"),"engine: {}\n");
        Path config=temp.resolve("att-server.yaml");
        Fixture fixture=new Fixture(packageRoot,alpha,beta,runtimeConfig,config,null);
        writeYaml(fixture,registryYaml(fixture,Map.of("alpha",alpha)));
        return new Fixture(packageRoot,alpha,beta,runtimeConfig,config,ServerConfig.load(config));
    }

    private String registryYaml(Fixture fixture,Map<String,Path> entries) {
        StringBuilder yaml=new StringBuilder("server:\n  dataDir: '").append(temp.resolve("data")).append("'\n  runtimeConfigPath: '")
                .append(fixture.runtimeConfig).append("'\nworkers: {}\npackages:\n  allowedRoots: ['").append(fixture.packageRoot).append("']\n");
        if(entries.isEmpty())yaml.append("  entries: {}\n");
        else {
            yaml.append("  entries:\n");
            entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->yaml.append("    ").append(entry.getKey()).append(": '").append(entry.getValue()).append("'\n"));
        }
        return yaml.toString();
    }

    private void writeYaml(Fixture fixture,String yaml) throws Exception { Files.writeString(fixture.configFile,yaml); }

    private void replaceConfigAtomically(Fixture fixture) throws Exception {
        replaceConfigAtomically(fixture,Files.readString(fixture.configFile));
    }

    private void replaceConfigAtomically(Fixture fixture,String yaml) throws Exception {
        Path replacement=temp.resolve("att-server.yaml.new");
        Files.writeString(replacement,yaml);
        Files.move(replacement,fixture.configFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    private void settle(PackageRegistry registry) throws Exception {
        registry.pollNow();Thread.sleep(25);registry.pollNow();
    }

    private record Fixture(Path packageRoot,Path alpha,Path beta,Path runtimeConfig,Path configFile,ServerConfig config) {}
}
