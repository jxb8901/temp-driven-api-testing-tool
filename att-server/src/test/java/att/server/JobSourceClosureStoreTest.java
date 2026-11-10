package att.server;

import att.server.api.source.SourceManagementContracts;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JobSourceClosureStoreTest {
    private static final String PACKAGE_ID="p";
    private static final String CONFIG_SCOPE="central-runtime-config";
    @TempDir Path temp;

    @Test void retainedPackageAndConfigTreesFenceDispatchAndSurviveStoreRestart() throws Exception {
        Path data=Files.createDirectories(temp.resolve("data"));
        Path packageA=sourceTree("R00000000000000000000000000000001","Package A",true);
        Path configA=sourceTree("R00000000000000000000000000000002","Config A",false);
        Path packageB=sourceTree("R00000000000000000000000000000003","Package B",true);
        Path configB=sourceTree("R00000000000000000000000000000004","Config B",false);
        RevisionSet revisions=revisions(packageA,configA);
        JobSourceClosureStore first=new JobSourceClosureStore(data);
        first.registerSnapshot(revisions.businessSource,ChangeSetPublisher.PACKAGE_SOURCE,packageA);
        first.registerSnapshot(revisions.configSource,ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,configA);
        SourceManagementContracts.SourceLease lease=first.acquire(new SourceManagementContracts.SourceLeaseRequest(
                PACKAGE_ID,"SIT",revisions,List.of("templates/use.yaml","testdata/cases.yaml","files/query.sql","config.yaml"),"job:J0000000000000001"));
        Path approvedConfig=configA.resolve("config.yaml");
        first.bindWorkerConfiguration(lease.leaseId(),approvedConfig);
        JobSourceClosureStore.SourceSnapshot accepted=first.snapshotForOwner("job:J0000000000000001");
        // Executable-mode metadata is now mandatory: never reuse the legacy v1
        // identifier for archives that older Workers cannot safely interpret.
        try(java.util.zip.ZipFile archive=new java.util.zip.ZipFile(accepted.archivePath.toFile())) {
            java.util.zip.ZipEntry manifestEntry=archive.getEntry("manifest.json");
            assertNotNull(manifestEntry);
            try(var content=archive.getInputStream(manifestEntry)) {
                assertEquals("att-worker-source-snapshot/v2",
                        ServerRuntime.JSON.readTree(content).path("schemaVersion").asText());
            }
        }


        assertEquals("Package A",new String(lease.read("templates/use.yaml").bytes(),StandardCharsets.UTF_8));
        assertEquals("Package A",new String(lease.read("testdata/cases.yaml").bytes(),StandardCharsets.UTF_8));
        assertEquals("Package A",new String(lease.read("files/query.sql").bytes(),StandardCharsets.UTF_8));
        assertTrue(first.verifyDispatch(lease.leaseId(),"job:J0000000000000001",accepted.archivePath.toString(),accepted.archiveSha256,accepted.configRelativePath));
        assertFalse(first.verifyDispatch(lease.leaseId(),"job:J0000000000000001",accepted.archivePath.toString(),"0".repeat(64),accepted.configRelativePath));

        JobSourceClosureStore restarted=new JobSourceClosureStore(data);
        JobSourceClosureStore.SourceSnapshot recovered=restarted.snapshotForOwner("job:J0000000000000001");
        assertEquals(lease.leaseId(),recovered.leaseId);
        assertEquals(revisions.packageSourceRevision.revision,recovered.revisions.packageSourceRevision.revision);
        assertEquals(revisions.businessSource.revision,recovered.revisions.businessSource.revision,
                "The legacy businessSource alias survives persisted closure restoration");
        assertEquals(revisions.executionRevisionDigest.revision,recovered.revisions.executionRevisionDigest.revision,
                "The execution-bound revision survives persisted closure restoration");
        assertTrue(restarted.verifyDispatch(recovered.leaseId,"job:J0000000000000001",recovered.archivePath.toString(),recovered.archiveSha256,recovered.configRelativePath));
        assertEquals("Config A",new String(Files.readAllBytes(recovered.workerConfigPath),StandardCharsets.UTF_8));
        assertEquals("Package B",Files.readString(packageB.resolve("templates/use.yaml")));
        assertEquals("Config B",Files.readString(configB.resolve("config.yaml")));
    }

    @Test void retirementRejectsNewLeasesButKeepsExistingOwnerReadableUntilRelease() throws Exception {
        Path data=Files.createDirectories(temp.resolve("data"));
        Path packageRoot=sourceTree("R10000000000000000000000000000001","Pinned Package",true);
        Path configRoot=sourceTree("R10000000000000000000000000000002","Pinned Config",false);
        RevisionSet revisions=revisions(packageRoot,configRoot);
        JobSourceClosureStore store=new JobSourceClosureStore(data);
        store.registerSnapshot(revisions.businessSource,ChangeSetPublisher.PACKAGE_SOURCE,packageRoot);
        store.registerSnapshot(revisions.configSource,ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,configRoot);
        var lease=store.acquire(new SourceManagementContracts.SourceLeaseRequest(PACKAGE_ID,"SIT",revisions,List.of("templates/use.yaml","config.yaml"),"schedule:S00000000000000000000000000000001:1"));
        store.bindWorkerConfiguration(lease.leaseId(),configRoot.resolve("config.yaml"));

        store.retire(revisions.businessSource);
        assertThrows(SourceManagementContracts.StaleSourceException.class,()->store.acquire(
                new SourceManagementContracts.SourceLeaseRequest(PACKAGE_ID,"SIT",revisions,List.of(),"job:J0000000000000002")));
        assertEquals("Pinned Package",new String(lease.read("templates/use.yaml").bytes(),StandardCharsets.UTF_8));
        assertTrue(store.hasOwner("schedule:S00000000000000000000000000000001:1"));
        store.releaseOwner("schedule:S00000000000000000000000000000001:1");
        assertFalse(store.hasOwner("schedule:S00000000000000000000000000000001:1"));
        assertTrue(Files.exists(packageRoot.resolve("templates/use.yaml")),"Retirement does not delete bytes owned by immutable revisions");
    }

    @Test void leaseReadsStayInsideTheApprovedLogicalPathClosure() throws Exception {
        Path data=Files.createDirectories(temp.resolve("data"));
        Path packageRoot=sourceTree("R20000000000000000000000000000001","Pinned Package",true);
        Path configRoot=sourceTree("R20000000000000000000000000000002","Pinned Config",false);
        RevisionSet revisions=revisions(packageRoot,configRoot);
        JobSourceClosureStore store=new JobSourceClosureStore(data);
        store.registerSnapshot(revisions.businessSource,ChangeSetPublisher.PACKAGE_SOURCE,packageRoot);
        store.registerSnapshot(revisions.configSource,ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,configRoot);
        var lease=store.acquire(new SourceManagementContracts.SourceLeaseRequest(PACKAGE_ID,"SIT",revisions,
                List.of("templates/use.yaml"),"job:J0000000000000003"));

        assertEquals("Pinned Package",new String(lease.read("templates/use.yaml").bytes(),StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class,()->lease.read("files/query.sql"));
    }

    @Test void retainedArchiveKeepsAcceptedBytesAfterOwnerMutatesOrRemovesSourceTrees() throws Exception {
        Path data=Files.createDirectories(temp.resolve("data"));
        Path packageRoot=sourceTree("R30000000000000000000000000000001","Accepted Package",true);
        Path configRoot=sourceTree("R30000000000000000000000000000002","Accepted Config",false);
        RevisionSet revisions=revisions(packageRoot,configRoot);String owner="job:J0000000000000004";
        JobSourceClosureStore store=new JobSourceClosureStore(data);
        store.registerSnapshot(revisions.businessSource,ChangeSetPublisher.PACKAGE_SOURCE,packageRoot);
        store.registerSnapshot(revisions.configSource,ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,configRoot);
        var lease=store.acquire(new SourceManagementContracts.SourceLeaseRequest(PACKAGE_ID,"SIT",revisions,
                List.of("templates/use.yaml","testdata/cases.yaml","files/query.sql","config.yaml"),owner));
        store.bindWorkerConfiguration(lease.leaseId(),configRoot.resolve("config.yaml"));

        Files.writeString(packageRoot.resolve("templates/use.yaml"),"Owner mutation B");
        Files.writeString(configRoot.resolve("config.yaml"),"Owner mutation B");
        Files.delete(packageRoot.resolve("testdata/cases.yaml"));
        assertEquals("Accepted Package",new String(lease.read("templates/use.yaml").bytes(),StandardCharsets.UTF_8));
        assertEquals("Accepted Config",new String(lease.read("config.yaml").bytes(),StandardCharsets.UTF_8));

        JobSourceClosureStore.SourceSnapshot accepted=store.snapshotForOwner(owner);
        assertEquals("Accepted Package",Files.readString(accepted.packageRoot.resolve("templates/use.yaml")));
        assertEquals("Accepted Config",Files.readString(accepted.workerConfigPath));
        Files.delete(packageRoot.resolve("files/query.sql"));
        JobSourceClosureStore restarted=new JobSourceClosureStore(data);
        assertTrue(restarted.hasOwner(owner));
        JobSourceClosureStore.SourceSnapshot restored=restarted.snapshotForOwner(owner);
        assertEquals("Accepted Package",Files.readString(restored.packageRoot.resolve("templates/use.yaml")));
        assertEquals("Accepted Config",Files.readString(restored.workerConfigPath));
    }

    @Test void retainedSnapshotRestoresExecutablePackageFilesBeforeAndAfterRestart() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path data=Files.createDirectories(temp.resolve("executable-data"));
        Path packageRoot=sourceTree("R35000000000000000000000000000001","Accepted Package",true);
        Path configRoot=sourceTree("R35000000000000000000000000000002","Accepted Config",false);
        Path executable=packageRoot.resolve("tools/local-tool.sh");Files.createDirectories(executable.getParent());
        Files.write(executable,"#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(executable,java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        RevisionSet revisions=revisions(packageRoot,configRoot);String owner="job:J0000000000000005";
        JobSourceClosureStore store=new JobSourceClosureStore(data);
        store.registerSnapshot(revisions.businessSource,ChangeSetPublisher.PACKAGE_SOURCE,packageRoot);
        store.registerSnapshot(revisions.configSource,ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,configRoot);
        var lease=store.acquire(new SourceManagementContracts.SourceLeaseRequest(PACKAGE_ID,"SIT",revisions,
                List.of("templates/use.yaml","tools/local-tool.sh","config.yaml"),owner));
        store.bindWorkerConfiguration(lease.leaseId(),configRoot.resolve("config.yaml"));

        JobSourceClosureStore.SourceSnapshot captured=store.snapshotForOwner(owner);
        Path restoredTool=captured.packageRoot.resolve("tools/local-tool.sh");
        assertTrue(Files.isExecutable(restoredTool),"archive materialization retains the approved executable bit");
        assertEquals("#!/bin/sh\nexit 0\n",Files.readString(restoredTool));

        JobSourceClosureStore restarted=new JobSourceClosureStore(data);
        JobSourceClosureStore.SourceSnapshot recovered=restarted.snapshotForOwner(owner);
        Path recoveredTool=recovered.packageRoot.resolve("tools/local-tool.sh");
        assertTrue(Files.isExecutable(recoveredTool),"restart materialization retains the approved executable bit");
        assertEquals("#!/bin/sh\nexit 0\n",Files.readString(recoveredTool));
    }

    @Test void provenanceRestorationReadsCanonicalRevisionsAndLegacyAliases() {
        RevisionSet revisions=new RevisionSet(
                RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION,PACKAGE_ID,"registry-1"),
                RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE,PACKAGE_ID,"R50000000000000000000000000000001"),
                null,null,null,null,null,null,null,
                RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE,PACKAGE_ID,"R50000000000000000000000000000001"),
                RevisionIdentity.of(RevisionKind.EXECUTION_REVISION,"p@SIT","a".repeat(64)));
        Map<String,Object> raw=ServerRuntime.JSON.convertValue(revisions,Map.class);
        RevisionSet restored=JobSourceClosureStore.revisionsFromProvenance(Map.of(
                "schemaVersion",JobSourceClosureStore.PROVENANCE_VERSION,"revisions",raw));
        assertEquals(RevisionKind.PACKAGE_SOURCE,restored.packageSourceRevision.kind);
        assertEquals(RevisionKind.BUSINESS_SOURCE,restored.businessSource.kind);
        assertEquals(revisions.packageSourceRevision.revision,restored.businessSource.revision);
        assertEquals(RevisionKind.EXECUTION_REVISION,restored.executionRevisionDigest.kind);
        assertEquals(revisions.executionRevisionDigest.revision,restored.executionRevisionDigest.revision);

        Map<String,Object> legacy=new java.util.LinkedHashMap<>(raw);
        legacy.remove("packageSourceRevision");legacy.remove("executionRevisionDigest");
        RevisionSet restoredLegacy=JobSourceClosureStore.revisionsFromProvenance(Map.of(
                "schemaVersion",JobSourceClosureStore.PROVENANCE_VERSION,"revisions",legacy));
        assertEquals(revisions.businessSource.revision,restoredLegacy.packageSourceRevision.revision,
                "An old businessSource-only record is upgraded to the canonical Package source identity");
        assertNull(restoredLegacy.executionRevisionDigest,"Old provenance remains readable without an execution identity");
    }

    @Test void missingOrCorruptSnapshotIsUnavailableAfterRestartAndCanBeRestored() throws Exception {
        Path data=Files.createDirectories(temp.resolve("data"));
        Path packageRoot=sourceTree("R40000000000000000000000000000001","Retained Package",true);
        Path configRoot=sourceTree("R40000000000000000000000000000002","Retained Config",false);
        RevisionSet revisions=revisions(packageRoot,configRoot);String owner="schedule:S00000000000000000000000000000004:1";
        JobSourceClosureStore store=new JobSourceClosureStore(data);
        store.registerSnapshot(revisions.businessSource,ChangeSetPublisher.PACKAGE_SOURCE,packageRoot);
        store.registerSnapshot(revisions.configSource,ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,configRoot);
        var lease=store.acquire(new SourceManagementContracts.SourceLeaseRequest(PACKAGE_ID,"SIT",revisions,
                List.of("templates/use.yaml","config.yaml"),owner));
        store.bindWorkerConfiguration(lease.leaseId(),configRoot.resolve("config.yaml"));
        JobSourceClosureStore.SourceSnapshot snapshot=store.snapshotForOwner(owner);
        Path backup=temp.resolve("snapshot.zip.bak");Files.copy(snapshot.archivePath,backup);

        Files.delete(snapshot.archivePath);
        JobSourceClosureStore missing=new JobSourceClosureStore(data);
        assertFalse(missing.hasOwner(owner));
        assertThrows(SourceManagementContracts.StaleSourceException.class,()->missing.snapshotForOwner(owner));
        assertFalse(missing.verifyDispatch(lease.leaseId(),owner,snapshot.archivePath.toString(),snapshot.archiveSha256,snapshot.configRelativePath));

        Files.copy(backup,snapshot.archivePath,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JobSourceClosureStore restoredMissing=new JobSourceClosureStore(data);
        assertTrue(restoredMissing.hasOwner(owner));
        assertEquals("Retained Package",Files.readString(restoredMissing.snapshotForOwner(owner).packageRoot.resolve("templates/use.yaml")));

        Files.write(snapshot.archivePath,new byte[]{1,2,3});
        JobSourceClosureStore corrupted=new JobSourceClosureStore(data);
        assertFalse(corrupted.hasOwner(owner));
        assertThrows(SourceManagementContracts.StaleSourceException.class,()->corrupted.snapshotForOwner(owner));
        assertFalse(corrupted.verifyDispatch(lease.leaseId(),owner,snapshot.archivePath.toString(),snapshot.archiveSha256,snapshot.configRelativePath));

        Files.copy(backup,snapshot.archivePath,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JobSourceClosureStore restored=new JobSourceClosureStore(data);
        assertTrue(restored.hasOwner(owner));
        assertEquals("Retained Package",Files.readString(restored.snapshotForOwner(owner).packageRoot.resolve("templates/use.yaml")));
    }

    @Test void publicationRefreshesOnlyItsPackageAndConfigActivationWaitsForTheCoordinator() {
        java.util.List<SourceManagementContracts.InvalidationScope> invalidated=new java.util.ArrayList<>();
        SourcePublicationLifecycle lifecycle=new SourcePublicationLifecycle(invalidated::add);
        RevisionIdentity packageRevision=RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE,"p","R00000000000000000000000000000001");
        RevisionSet before=new RevisionSet(null,packageRevision,null,null,null,null,null,null,null);
        RevisionSet after=new RevisionSet(null,RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE,"p","R00000000000000000000000000000002"),null,null,null,null,null,null,null);
        lifecycle.committed(new SourceManagementContracts.PublicationEvent(SourceManagementContracts.Domain.PACKAGE_SOURCE,"p","request-1",before,after,List.of("files/query.sql")));
        assertEquals(1,lifecycle.generation("p",null));
        assertEquals(0,lifecycle.generation("q",null));
        assertEquals("p",invalidated.get(0).packageId);
        assertNull(invalidated.get(0).environment);

        lifecycle.invalidate(new SourceManagementContracts.InvalidationScope("p","SIT",List.of("EFFECTIVE_CONFIGURATION")));
        assertEquals(2,lifecycle.generation("p","SIT"));
        assertEquals(1,lifecycle.generation("p","UAT"));

        RevisionIdentity configRevision=RevisionIdentity.of(RevisionKind.CONFIG_SOURCE,CONFIG_SCOPE,"R00000000000000000000000000000003");
        RevisionSet configAfter=new RevisionSet(null,null,null,null,configRevision,null,null,null,null);
        int scopesBefore=lifecycleGenerationCount(invalidated);
        lifecycle.committed(new SourceManagementContracts.PublicationEvent(SourceManagementContracts.Domain.CENTRAL_RUNTIME_CONFIG,
                CONFIG_SCOPE,"request-2",RevisionSet.empty(),configAfter,List.of("config.yaml")));
        assertEquals(scopesBefore,lifecycleGenerationCount(invalidated),"Central Config publication leaves impact analysis to the activation coordinator");
        assertEquals(SourceManagementContracts.ActivationState.UNAVAILABLE,lifecycle.activationStatus(CONFIG_SCOPE).state);
    }

    private static int lifecycleGenerationCount(List<?> values) { return values.size(); }

    private Path sourceTree(String revision,String value,boolean packageTree) throws Exception {
        Path root=Files.createDirectories(temp.resolve(revision));
        if(packageTree) {
            write(root,"templates/use.yaml",value);
            write(root,"testdata/cases.yaml",value);
            write(root,"files/query.sql",value);
        } else write(root,"config.yaml",value);
        return root;
    }

    private static void write(Path root,String relative,String value) throws Exception {
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,value,StandardCharsets.UTF_8);
    }

    private static RevisionSet revisions(Path packageRoot,Path configRoot) {
        return new RevisionSet(
                RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION,PACKAGE_ID,"registry-1"),
                RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE,PACKAGE_ID,packageRoot.getFileName().toString()),
                null,
                RevisionIdentity.of(RevisionKind.PACKAGE_MANIFEST,PACKAGE_ID,"a".repeat(64)),
                RevisionIdentity.of(RevisionKind.CONFIG_SOURCE,CONFIG_SCOPE,configRoot.getFileName().toString()),
                RevisionIdentity.of(RevisionKind.EFFECTIVE_CONFIG,"p@SIT","b".repeat(64)),
                new RevisionIdentity(RevisionKind.ENVIRONMENT,"SIT",PACKAGE_ID,"b".repeat(64)),
                RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA,"embedded-engine-schema","c".repeat(64)),
                null,
                RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE,PACKAGE_ID,packageRoot.getFileName().toString()),
                RevisionIdentity.of(RevisionKind.EXECUTION_REVISION,"p@SIT","d".repeat(64)));
    }
}
