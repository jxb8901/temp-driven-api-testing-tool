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
                PACKAGE_ID,"SIT",revisions,List.of(),"job:J0000000000000001"));
        Path approvedConfig=configA.resolve("config.yaml");
        first.bindWorkerConfiguration(lease.leaseId(),approvedConfig);

        assertEquals("Package A",new String(lease.read("templates/use.yaml").bytes(),StandardCharsets.UTF_8));
        assertEquals("Package A",new String(lease.read("testdata/cases.yaml").bytes(),StandardCharsets.UTF_8));
        assertEquals("Package A",new String(lease.read("files/query.sql").bytes(),StandardCharsets.UTF_8));
        assertTrue(first.verifyDispatch(lease.leaseId(),"job:J0000000000000001",packageA,approvedConfig));
        assertFalse(first.verifyDispatch(lease.leaseId(),"job:J0000000000000001",packageB,approvedConfig));
        assertFalse(first.verifyDispatch(lease.leaseId(),"job:J0000000000000001",packageA,configB.resolve("config.yaml")));

        JobSourceClosureStore restarted=new JobSourceClosureStore(data);
        JobSourceClosureStore.SourceSnapshot recovered=restarted.snapshotForOwner("job:J0000000000000001");
        assertEquals(lease.leaseId(),recovered.leaseId);
        assertTrue(restarted.verifyDispatch(recovered.leaseId,"job:J0000000000000001",packageA,approvedConfig));
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
        var lease=store.acquire(new SourceManagementContracts.SourceLeaseRequest(PACKAGE_ID,"SIT",revisions,List.of(),"schedule:S00000000000000000000000000000001:1"));
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
                null);
    }
}
