package att.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChangeSetPublisherTest {
    private static final String VALIDATION_REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SCHEMA_REVISION = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir Path temp;
    private Path packageRoot;
    private Path centralRoot;
    private Path dataRoot;
    private SourceFileTransaction transaction;
    private AtomicReference<String> registryRevision;
    private final List<String> audit = new ArrayList<String>();
    private AtomicInteger validatorCalls;
    private AtomicInteger gateCalls;

    @BeforeEach void setUp() throws Exception {
        packageRoot = Files.createDirectories(temp.resolve("packages/owner")).toRealPath();
        centralRoot = Files.createDirectories(temp.resolve("runtime")).toRealPath();
        dataRoot = Files.createDirectories(temp.resolve("data"));
        Files.createDirectories(centralRoot.resolve("config"));
        Files.write(centralRoot.resolve("config/config.yaml"), "schemaVersion: att-config/v2.13\n".getBytes(StandardCharsets.UTF_8));
        registryRevision = new AtomicReference<String>(digest("registry-1"));
        validatorCalls = new AtomicInteger();
        gateCalls = new AtomicInteger();
        audit.clear();
    }

    @Test void publishesMultiFileCandidatesAllOrNoneAndDraftValidationNeverTouchesLiveFiles() throws Exception {
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            List<ChangeSetPublisher.OperationInput> operations = Arrays.asList(
                    ChangeSetPublisher.OperationInput.create("templates/payment.yaml", bytes("schemaVersion: att-template/v3.6\n")),
                    ChangeSetPublisher.OperationInput.create("templates/payment/request.xml", bytes("<request/>\n")),
                    ChangeSetPublisher.OperationInput.create("flows/payment.yaml", bytes("schemaVersion: att-flow/v1\n")));
            Map<String, Object> draft = stagePackage(publisher, operations);
            assertFalse(Files.exists(activePackageRoot().resolve("templates/payment.yaml")));
            assertFalse(Files.exists(activePackageRoot().resolve("templates/payment/request.xml")));
            String id = String.valueOf(draft.get("changeSetId"));
            publisher.validate(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            assertFalse(Files.exists(activePackageRoot().resolve("templates/payment.yaml")));
            assertFalse(Files.exists(activePackageRoot().resolve("flows/payment.yaml")));
            publisher.review(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            Map<String, Object> result = publisher.publish(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            assertEquals(Boolean.TRUE, result.get("published"));
            assertEquals("schemaVersion: att-template/v3.6\n", read("templates/payment.yaml"));
            assertEquals("<request/>\n", read("templates/payment/request.xml"));
            assertEquals("schemaVersion: att-flow/v1\n", read("flows/payment.yaml"));
            Map<String, Object> next = stagePackage(publisher, Arrays.asList(
                    ChangeSetPublisher.OperationInput.create("templates/next.yaml", bytes("id: next\n"))));
            assertEquals(result.get("revision"), next.get("baseSourceRevision"));
        } finally { publisher.close(); }
    }

    @Test void concurrentEditorsFromOneBaseRevisionHaveOneSuccessAndOneTypedConflict() throws Exception {
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Map<String, Object> first = stagePackage(publisher, Arrays.asList(
                    ChangeSetPublisher.OperationInput.create("templates/first.yaml", bytes("id: first\n"))));
            Map<String, Object> second = stagePackage(publisher, Arrays.asList(
                    ChangeSetPublisher.OperationInput.create("templates/second.yaml", bytes("id: second\n"))));
            String firstId = String.valueOf(first.get("changeSetId"));
            String secondId = String.valueOf(second.get("changeSetId"));
            assertEquals(first.get("baseSourceRevision"), second.get("baseSourceRevision"));
            validateReview(publisher, firstId);
            validateReview(publisher, secondId);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> one = executor.submit(() -> publishAfter(start, publisher, firstId));
            Future<Boolean> two = executor.submit(() -> publishAfter(start, publisher, secondId));
            start.countDown();
            int successes = (one.get(15, TimeUnit.SECONDS) ? 1 : 0) + (two.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            Path active=activePackageRoot();
            assertTrue(Files.exists(active.resolve("templates/first.yaml"))
                    ^ Files.exists(active.resolve("templates/second.yaml")));
        } finally {
            executor.shutdownNow();
            publisher.close();
        }
    }

    @Test void requiresObservedSourceAndRegistryRevisionsAndRejectsStaleEditorAtDraftCreation() throws Exception {
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            Map<String,String> observed=publisher.currentRevisions(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false);
            assertThrows(IllegalArgumentException.class,()->publisher.stage(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false,
                    null,observed.get("registryRevision"),List.of(ChangeSetPublisher.OperationInput.create("templates/omitted-base.yaml",bytes("id: omitted\n")))));
            assertThrows(IllegalArgumentException.class,()->publisher.stage(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false,
                    observed.get("baseSourceRevision"),null,List.of(ChangeSetPublisher.OperationInput.create("templates/omitted-registry.yaml",bytes("id: omitted\n")))));

            Map<String,Object> first=publisher.stage(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false,
                    observed.get("baseSourceRevision"),observed.get("registryRevision"),
                    List.of(ChangeSetPublisher.OperationInput.create("templates/first-editor.yaml",bytes("id: first\n"))));
            String firstId=String.valueOf(first.get("changeSetId"));
            validateReview(publisher,firstId);
            publisher.publish(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false,firstId);

            assertThrows(ChangeSetPublisher.Conflict.class,()->publisher.stage(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false,
                    observed.get("baseSourceRevision"),observed.get("registryRevision"),
                    List.of(ChangeSetPublisher.OperationInput.create("templates/stale-editor.yaml",bytes("id: stale\n")))));
            assertFalse(Files.exists(activePackageRoot().resolve("templates/stale-editor.yaml")));
        } finally { publisher.close(); }
    }

    @Test void changeSetRevisionSetKeepsPackageSourceAndCentralConfigSeparateFromAggregateFence() throws Exception {
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            Map<String,Object> packageDraft = stagePackage(publisher, List.of(
                    ChangeSetPublisher.OperationInput.create("files/source.txt", bytes("package source\n"))));
            @SuppressWarnings("unchecked") Map<String,Object> packageRevisions = (Map<String,Object>)packageDraft.get("revisions");
            @SuppressWarnings("unchecked") Map<String,Object> packageSource = (Map<String,Object>)packageRevisions.get("packageSourceRevision");
            @SuppressWarnings("unchecked") Map<String,Object> legacyBusiness = (Map<String,Object>)packageRevisions.get("businessSource");
            assertEquals("PACKAGE_SOURCE", packageSource.get("kind"));
            assertEquals(packageSource.get("revision"), legacyBusiness.get("revision"));
            assertNotEquals(packageDraft.get("baseSourceRevision"), packageSource.get("revision"),
                    "The aggregate source CAS fence is not the Package-owned source identity");
            assertNull(packageRevisions.get("executionRevisionDigest"),
                    "A source draft must not invent an execution-bound revision");

            Map<String,Object> configDraft = stageCentral(publisher, List.of(
                    ChangeSetPublisher.OperationInput.replace("config/config.yaml", bytes("schemaVersion: att-config/v2.13\ntimeoutMs: 2500\n"))));
            @SuppressWarnings("unchecked") Map<String,Object> configRevisions = (Map<String,Object>)configDraft.get("revisions");
            @SuppressWarnings("unchecked") Map<String,Object> configSource = (Map<String,Object>)configRevisions.get("configSource");
            assertNull(configRevisions.get("packageSourceRevision"));
            assertNull(configRevisions.get("businessSource"));
            assertEquals("CONFIG_SOURCE", configSource.get("kind"));
            assertNotEquals(configDraft.get("baseSourceRevision"), configSource.get("revision"));
        } finally { publisher.close(); }
    }

    @Test void invalidatesApprovalWhenPackageOrRegistryRevisionChanges() throws Exception {
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            Map<String, Object> draft = stagePackage(publisher, Arrays.asList(
                    ChangeSetPublisher.OperationInput.create("templates/candidate.yaml", bytes("id: candidate\n"))));
            String id = String.valueOf(draft.get("changeSetId"));
            validateReview(publisher, id);
            externalPackageWrite("templates/external.yaml",bytes("id: outside-change\n"));
            assertThrows(ChangeSetPublisher.Conflict.class,
                    () -> publisher.publish(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id));
            assertFalse(Files.exists(activePackageRoot().resolve("templates/candidate.yaml")));

            Map<String, Object> next = stagePackage(publisher, Arrays.asList(
                    ChangeSetPublisher.OperationInput.create("templates/next.yaml", bytes("id: next\n"))));
            String nextId = String.valueOf(next.get("changeSetId"));
            validateReview(publisher, nextId);
            registryRevision.set(digest("registry-2"));
            assertThrows(ChangeSetPublisher.Conflict.class,
                    () -> publisher.publish(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, nextId));
        } finally { publisher.close(); }
    }

    @Test void separatesPackageOwnerAndCentralAdminAuthorities() throws Exception {
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            assertThrows(ChangeSetPublisher.Denied.class, () -> publisher.stage(
                    ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                    "owner", false, null, null, Arrays.asList(ChangeSetPublisher.OperationInput.replace(
                            "config/config.yaml", bytes("schemaVersion: att-config/v2.13\n")))));
            assertThrows(ChangeSetPublisher.Denied.class, () -> publisher.stage(
                    ChangeSetPublisher.PACKAGE_SOURCE, "owner", "admin", true, null, null,
                    Arrays.asList(ChangeSetPublisher.OperationInput.create("templates/admin.yaml", bytes("id: admin\n")))));

            Map<String, Object> central = stageCentral(publisher, Arrays.asList(ChangeSetPublisher.OperationInput.replace(
                    "config/config.yaml", bytes("schemaVersion: att-config/v2.13\nname: revised\n"))));
            assertEquals(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, central.get("domain"));
        } finally { publisher.close(); }
    }

    @Test void rejectsUnsafePaths() throws Exception {
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            List<String> rejected = Arrays.asList("../outside.yaml", "/etc/passwd.yaml", "C:/temp/out.yaml",
                    "config/config.yaml", "schemas/att.yaml", "att-server.yaml",
                    temp.resolve("packages/other/templates/a.yaml").toString());
            for (String path : rejected) assertThrows(IllegalArgumentException.class, () -> stagePackage(publisher,
                    Arrays.asList(ChangeSetPublisher.OperationInput.create(path, bytes("id: rejected\n")))), path);

        } finally { publisher.close(); }
    }

    @Test void rejectsCaseCollisionsInPublishedTree() throws Exception {
        Files.createDirectories(packageRoot.resolve("Templates"));
        Files.write(packageRoot.resolve("Templates/Existing.yaml"), bytes("id: existing\n"));
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            assertThrows(Exception.class, () -> stagePackage(publisher,
                    Arrays.asList(ChangeSetPublisher.OperationInput.create("templates/new.yaml", bytes("id: new\n")))));
        } finally { publisher.close(); }
    }

    @Test void rejectsManagedSymlinkSources() throws Exception {
        Files.createDirectories(packageRoot.resolve("templates"));
        Path target = packageRoot.resolve("templates/real.yaml");
        Files.write(target, bytes("id: real\n"));
        Path link = packageRoot.resolve("templates/linked.yaml");
        Files.createSymbolicLink(link, target.getFileName());
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            assertThrows(Exception.class, () -> stagePackage(publisher,
                    Arrays.asList(ChangeSetPublisher.OperationInput.replace("templates/linked.yaml", bytes("id: changed\n")))));
        } finally { publisher.close(); }
    }

    @Test void testcaseSnapshotRequiresSeparateOwnerActionAndIsBoundToWorkbookAndSidecarHashes() throws Exception {
        Path testcase = Files.createDirectories(packageRoot.resolve("testcase"));
        Path workbook = testcase.resolve("orders.xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook xlsx = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            xlsx.createSheet("Orders");
            try (java.io.OutputStream output = Files.newOutputStream(workbook)) { xlsx.write(output); }
        }
        byte[] sidecar = bytes("schemaVersion: att-sidecar/v2.2\nid: orders\n");
        Files.write(testcase.resolve("orders.yaml"), sidecar);
        Files.write(testcase.resolve("orders.xml"), bytes("<old-snapshot/>\n"));
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate(),
                (candidate, workbookPath) -> bytes("<testcases schemaVersion=\"att-testcase-snapshot/v2.4\"/>\n"));
        try {
            assertThrows(IllegalArgumentException.class, () -> stagePackage(publisher, Arrays.asList(
                    ChangeSetPublisher.OperationInput.replace("testcase/orders.xml", bytes("<hand-edited/>\n")))));
            Map<String, Object> draft = stagePackage(publisher, Arrays.asList(
                    ChangeSetPublisher.OperationInput.create("templates/unrelated.yaml", bytes("id: unrelated\n"))));
            String id = String.valueOf(draft.get("changeSetId"));
            assertThrows(ChangeSetPublisher.Conflict.class, () -> publisher.generateSnapshot(ChangeSetPublisher.PACKAGE_SOURCE,
                    "owner", "owner", false, id, String.valueOf(draft.get("baseSourceRevision")),
                    "testcase/orders.xlsx", digest(Files.readAllBytes(workbook)), digest("stale-sidecar"), "snapshot-stale"));
            Map<String, Object> generated = publisher.generateSnapshot(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false,
                    id, String.valueOf(draft.get("baseSourceRevision")), "testcase/orders.xlsx",
                    digest(Files.readAllBytes(workbook)), digest(sidecar), "snapshot-test");
            assertEquals(Boolean.TRUE, generated.get("snapshotGenerated"));
            assertEquals("testcase/orders.xml", generated.get("snapshotPath"));
            assertFalse(Files.readString(testcase.resolve("orders.xml")).contains("testcases schemaVersion"),
                    "generation stages the snapshot and leaves the published Package untouched");
            Map<String, Object> validated = publisher.validate(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            assertEquals("validated", validated.get("state"));
        } finally { publisher.close(); }
    }

    @Test void changeSetValidationUsesInstalledEngineSchemasForTheWholeCandidate() throws Exception {
        ChangeSetPublisher publisher = publisher(new ChangeSetCandidateValidator(), allowingGate());
        try {
            Map<String, Object> draft = stagePackage(publisher, Arrays.asList(ChangeSetPublisher.OperationInput.create(
                    "templates/invalid.yaml", bytes("schemaVersion: att-template/v999\nname: invalid\ndescription: invalid\nactions: {}\n"))));
            ChangeSetPublisher.ValidationRejected rejected = assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    () -> publisher.validate(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false,
                            String.valueOf(draft.get("changeSetId"))));
            assertTrue(rejected.code.startsWith("ATT-"));
            assertFalse(Files.exists(activePackageRoot().resolve("templates/invalid.yaml")),
                    "static validation must not mutate the published Package");
        } finally { publisher.close(); }
    }

    @Test void centralPublisherComparesDeploymentOutputDirectoryToItsPinnedBase() throws Exception {
        String base = "schemaVersion: att-config/v2.13\nenvironment: SIT\noutputDirectory: output\ntimeoutMs: 10000\n";
        Files.writeString(centralRoot.resolve("config/config.yaml"), base, StandardCharsets.UTF_8);
        ChangeSetPublisher publisher = publisher(new ChangeSetCandidateValidator(), allowingGate());
        try {
            Map<String,Object> unchanged = stageCentral(publisher, List.of(ChangeSetPublisher.OperationInput.replace(
                    "config/config.yaml", bytes("schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                            + "outputDirectory: output\ntimeoutMs: 12000\n"))));
            Map<String,Object> accepted = publisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, String.valueOf(unchanged.get("changeSetId")));
            assertEquals("validated", accepted.get("state"));

            Map<String,Object> changed = stageCentral(publisher, List.of(ChangeSetPublisher.OperationInput.replace(
                    "config/config.yaml", bytes("schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                            + "outputDirectory: reports\ntimeoutMs: 12000\n"))));
            ChangeSetPublisher.ValidationRejected rejected = assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    () -> publisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                            ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true,
                            String.valueOf(changed.get("changeSetId"))));
            assertEquals("ATT-CONFIG-ONLINE-UNSUPPORTED", rejected.code);
        } finally { publisher.close(); }
    }

    @Test void publisherRejectsTruncatedLoadInventoryAsNonExecutable() throws Exception {
        Path scenarios=Files.createDirectories(packageRoot.resolve("load/scenarios"));
        for(int index=0;index<=att.validation.SourceCandidateValidator.MAX_LOAD_DESCRIPTORS;index++)
            Files.writeString(scenarios.resolve(String.format("scenario-%04d.yaml",index)),"schemaVersion: att-load/v1\n");
        ChangeSetPublisher publisher=publisher(new ChangeSetCandidateValidator(),allowingGate());
        try {
            Map<String,Object> draft=stagePackage(publisher,List.of(ChangeSetPublisher.OperationInput.create(
                    "files/staged.txt",bytes("candidate must be rejected when LOAD coverage is truncated\n"))));
            ChangeSetPublisher.ValidationRejected rejected=assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    ()->publisher.validate(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false,
                            String.valueOf(draft.get("changeSetId"))));
            assertTrue(rejected.code.contains("LOAD")||rejected.code.contains("CANDIDATE"),rejected.code);
            assertFalse(Files.exists(activePackageRoot().resolve("files/staged.txt")),
                    "An incomplete whole-candidate LOAD inventory cannot be published");
        } finally { publisher.close(); }
    }

    @Test void publisherEndpointResponsesConformToTheVersionedJsonSchema() throws Exception {
        com.fasterxml.jackson.databind.JsonNode schemaNode;
        try (java.io.InputStream input = getClass().getResourceAsStream("/schemas/att-source-management-v1.schema.json")) {
            assertNotNull(input, "the API schema is packaged with the Server");
            schemaNode = ServerRuntime.JSON.readTree(input);
        }
        com.networknt.schema.JsonSchema schema = com.networknt.schema.JsonSchemaFactory
                .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            Map<String, Object> draft = stagePackage(publisher, List.of(ChangeSetPublisher.OperationInput.create(
                    "templates/wire.yaml", bytes("schemaVersion: att-template/v3.6\nname: Wire\ndescription: Wire\nactions: {}\n"))));
            assertWireResponse(schema, draft);
            String id = String.valueOf(draft.get("changeSetId"));
            Map<String, Object> validated = publisher.validate(ChangeSetPublisher.PACKAGE_SOURCE,
                    "owner", "owner", false, id, "api-request");
            assertWireResponse(schema, validated);
            Map<String, Object> reviewed = publisher.review(ChangeSetPublisher.PACKAGE_SOURCE,
                    "owner", "owner", false, id, "api-request");
            assertWireResponse(schema, reviewed);
            Map<String, Object> published = publisher.publish(ChangeSetPublisher.PACKAGE_SOURCE,
                    "owner", "owner", false, id, "api-request");
            assertWireResponse(schema, published);

            Map<String, Object> discardDraft = stagePackage(publisher, List.of(ChangeSetPublisher.OperationInput.create(
                    "templates/discard.yaml", bytes("schemaVersion: att-template/v3.6\nname: Discard\ndescription: Discard\nactions: {}\n"))));
            Map<String, Object> discarded = publisher.discard(ChangeSetPublisher.PACKAGE_SOURCE,
                    "owner", "owner", false, String.valueOf(discardDraft.get("changeSetId")), "api-request");
            assertWireResponse(schema, discarded);
        } finally { publisher.close(); }
    }

    @Test void movingReferencedFileRewritesEveryStaticLocatorAndDeletingReferencedFileFailsValidation() throws Exception {
        Files.createDirectories(packageRoot.resolve("templates/shared"));
        Files.createDirectories(packageRoot.resolve("flows"));
        Files.write(packageRoot.resolve("templates/shared/payload.txt"), bytes("request-body\n"));
        Files.write(packageRoot.resolve("templates/one.yaml"), bytes("actions:\n  a: {type: assign, expression: '&{./shared/payload.txt}'}\n"));
        Files.write(packageRoot.resolve("flows/two.yaml"), bytes("actions:\n  b: {type: assign, expression: '&{templates/shared/payload.txt}'}\n"));
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            Map<String, Object> moved = stagePackage(publisher, Arrays.asList(ChangeSetPublisher.OperationInput.move(
                    "templates/shared/payload.txt", "templates/shared/moved.txt")));
            String id = String.valueOf(moved.get("changeSetId"));
            List<?> changed = (List<?>) moved.get("changedFiles");
            assertEquals(3, changed.size());
            Map<?,?> moveRow=(Map<?,?>)changed.stream().map(Map.class::cast)
                    .filter(row->"templates/shared/payload.txt".equals(row.get("path"))).findFirst().orElseThrow();
            List<?> inbound=(List<?>)moveRow.get("inboundReferences");
            assertEquals(2,inbound.size());
            assertTrue(inbound.toString().contains("templates/one.yaml"));
            assertTrue(inbound.toString().contains("flows/two.yaml"));
            Map<?,?> rewrittenRow=(Map<?,?>)changed.stream().map(Map.class::cast)
                    .filter(row->"templates/one.yaml".equals(row.get("path"))).findFirst().orElseThrow();
            String rewritten="actions:\n  a: {type: assign, expression: '&{templates/shared/moved.txt}'}\n";
            assertEquals(digest(rewritten),rewrittenRow.get("newHash"));
            assertEquals((long)rewritten.getBytes(StandardCharsets.UTF_8).length,rewrittenRow.get("newBytes"));
            assertTrue(rewrittenRow.get("contentDiff").toString().contains("&{./shared/payload.txt}"));
            assertTrue(rewrittenRow.get("contentDiff").toString().contains("&{templates/shared/moved.txt}"));
            validateReview(publisher, id);
            publisher.publish(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            assertTrue(read("templates/one.yaml").contains("&{templates/shared/moved.txt}"));
            assertTrue(read("flows/two.yaml").contains("&{templates/shared/moved.txt}"));
            assertFalse(Files.exists(activePackageRoot().resolve("templates/shared/payload.txt")));
            assertTrue(Files.exists(activePackageRoot().resolve("templates/shared/moved.txt")));

            Map<String, Object> deleting = stagePackage(publisher, Arrays.asList(ChangeSetPublisher.OperationInput.delete(
                    "templates/shared/moved.txt")));
            assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    () -> publisher.validate(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false,
                            String.valueOf(deleting.get("changeSetId"))));
            assertTrue(Files.exists(activePackageRoot().resolve("templates/shared/moved.txt")));
        } finally { publisher.close(); }
    }

    @Test void reviewShowsAutomaticReferenceRewriteInsideAMovedDescriptor() throws Exception {
        Files.createDirectories(packageRoot.resolve("templates/shared"));
        Files.write(packageRoot.resolve("templates/shared/payload.txt"), bytes("request-body\n"));
        Files.write(packageRoot.resolve("templates/old.yaml"), bytes("request: '&{./shared/payload.txt}'\n"));
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            Map<String, Object> draft = stagePackage(publisher, Arrays.asList(
                    ChangeSetPublisher.OperationInput.move("templates/old.yaml", "templates/new.yaml"),
                    ChangeSetPublisher.OperationInput.move("templates/shared/payload.txt", "templates/shared/moved.txt")));
            Map<?, ?> movedDescriptor = ((List<?>) draft.get("changedFiles")).stream().map(Map.class::cast)
                    .filter(row -> "templates/old.yaml".equals(row.get("path"))).findFirst().orElseThrow();
            assertEquals(1, movedDescriptor.get("updatedFileReferences"));
            assertNotEquals("Moved without changing file content", movedDescriptor.get("contentSummary"));
            assertTrue(movedDescriptor.get("contentDiff").toString().contains("&{./shared/payload.txt}"));
            assertTrue(movedDescriptor.get("contentDiff").toString().contains("&{templates/shared/moved.txt}"));
        } finally { publisher.close(); }
    }

    @Test void reviewAuditAndErrorsNeverReturnAuthoredSecretValues() throws Exception {
        Files.createDirectories(packageRoot.resolve("templates"));
        Files.write(packageRoot.resolve("templates/secret.yaml"), bytes("id: secret\n"));
        String secret = "review-secret-6ca7";
        ChangeSetPublisher publisher = publisher(acceptingValidator(), allowingGate());
        try {
            Map<String, Object> draft = stagePackage(publisher, Arrays.asList(ChangeSetPublisher.OperationInput.replace(
                    "templates/secret.yaml", bytes("password: " + secret + "\n"))));
            String id = String.valueOf(draft.get("changeSetId"));
            assertTrue(draft.toString().contains("Protected or unparseable content changed; replace-only summary"));
            Map<String, Object> validated = publisher.validate(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            Map<String, Object> reviewed = publisher.review(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            String safe = draft.toString() + validated.toString() + reviewed.toString() + audit.toString();
            assertFalse(safe.contains(secret), safe);
            Map<String, Object> result = publisher.publish(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            assertFalse(result.toString().contains(secret));
            assertEquals("password: " + secret + "\n", read("templates/secret.yaml"));
        } finally { publisher.close(); }

        ChangeSetPublisher rejecting = publisher(candidate -> { throw new IllegalStateException("validator input=" + secret); }, allowingGate());
        try {
            Map<String, Object> rejectedDraft = stagePackage(rejecting, Arrays.asList(ChangeSetPublisher.OperationInput.replace(
                    "templates/secret.yaml", bytes("password: " + secret + "\n"))));
            ChangeSetPublisher.ValidationRejected rejected = assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    () -> rejecting.validate(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false,
                            String.valueOf(rejectedDraft.get("changeSetId"))));
            assertFalse(rejected.getMessage().contains(secret));
            assertFalse(audit.toString().contains(secret));
        } finally { rejecting.close(); }
    }

    @Test void configPublishCallsPluggableGateAndAStaleOrUnsafeApprovalCanBlock() throws Exception {
        Files.createDirectories(centralRoot.resolve("config/tools"));
        Files.createDirectories(centralRoot.resolve("config/dbhelpers"));
        Files.write(centralRoot.resolve("config/tools/orders.yaml"), bytes("schemaVersion: att-tool/v1\n"));
        Files.write(centralRoot.resolve("config/dbhelpers/orders.yaml"), bytes("schemaVersion: att-dbhelper/v2.6\n"));
        ChangeSetPublisher blocked = publisher(acceptingValidator(), (candidate, review) -> {
            gateCalls.incrementAndGet(); return ChangeSetPublisher.GateDecision.block("ATT-CONFIG-IMPACT-STALE");
        });
        try {
            List<ChangeSetPublisher.OperationInput> operations = Arrays.asList(
                    ChangeSetPublisher.OperationInput.replace("config/config.yaml", bytes("schemaVersion: att-config/v2.13\nname: revised\n")),
                    ChangeSetPublisher.OperationInput.replace("config/tools/orders.yaml", bytes("schemaVersion: att-tool/v1\nname: revised\n")),
                    ChangeSetPublisher.OperationInput.replace("config/dbhelpers/orders.yaml", bytes("schemaVersion: att-dbhelper/v2.6\nid: orders\n")));
            Map<String, Object> draft = stageCentral(blocked, operations);
            String id = String.valueOf(draft.get("changeSetId"));
            validateReview(blocked, id, ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true);
            ChangeSetPublisher.ValidationRejected rejected = assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    () -> blocked.publish(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, id));
            assertEquals("ATT-CONFIG-IMPACT-STALE", rejected.code);
            assertEquals(1, gateCalls.get());
            assertEquals("schemaVersion: att-config/v2.13\n", new String(Files.readAllBytes(activeCentralRoot().resolve("config/config.yaml")), StandardCharsets.UTF_8));
            assertEquals("schemaVersion: att-tool/v1\n", new String(Files.readAllBytes(activeCentralRoot().resolve("config/tools/orders.yaml")), StandardCharsets.UTF_8));
        } finally { blocked.close(); }

        ChangeSetPublisher allowed = publisher(acceptingValidator(), (candidate, review) -> {
            gateCalls.incrementAndGet(); return ChangeSetPublisher.GateDecision.allow(VALIDATION_REVISION);
        });
        try {
            Map<String, Object> draft = stageCentral(allowed, Arrays.asList(
                    ChangeSetPublisher.OperationInput.replace("config/config.yaml", bytes("schemaVersion: att-config/v2.13\nname: final\n")),
                    ChangeSetPublisher.OperationInput.replace("config/tools/orders.yaml", bytes("schemaVersion: att-tool/v1\nname: final\n")),
                    ChangeSetPublisher.OperationInput.replace("config/dbhelpers/orders.yaml", bytes("schemaVersion: att-dbhelper/v2.6\nid: orders\n"))));
            validateReview(allowed, String.valueOf(draft.get("changeSetId")), ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true);
            Map<String, Object> result = allowed.publish(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, String.valueOf(draft.get("changeSetId")));
            assertEquals(Boolean.TRUE, result.get("published"));
            assertEquals("schemaVersion: att-config/v2.13\nname: final\n",
                    new String(Files.readAllBytes(activeCentralRoot().resolve("config/config.yaml")), StandardCharsets.UTF_8));
            assertTrue(gateCalls.get() >= 2);
        } finally { allowed.close(); }
    }

    @Test void configApprovalFreshnessIsRecheckedAtTheCommitFence() throws Exception {
        Files.createDirectories(centralRoot.resolve("config/tools"));
        AtomicInteger approvals=new AtomicInteger();
        ChangeSetPublisher publisher=publisher(acceptingValidator(),(candidate,review)->
                ChangeSetPublisher.GateDecision.allow(approvals.incrementAndGet()==1?VALIDATION_REVISION:digest("revoked-approval")));
        try {
            Map<String,Object> draft=stageCentral(publisher,List.of(ChangeSetPublisher.OperationInput.replace(
                    "config/config.yaml",bytes("schemaVersion: att-config/v2.13\nname: revised\n"))));
            String id=String.valueOf(draft.get("changeSetId"));
            validateReview(publisher,id,ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"admin",true);
            ChangeSetPublisher.Conflict stale=assertThrows(ChangeSetPublisher.Conflict.class,
                    ()->publisher.publish(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,"admin",true,id));
            assertEquals("ATT-CONFIG-IMPACT-STALE",stale.code);
            assertTrue(approvals.get()>=2);
            assertEquals("schemaVersion: att-config/v2.13\n",Files.readString(activeCentralRoot().resolve("config/config.yaml")));
        } finally { publisher.close(); }
    }

    @Test void safeDefaultValidatorAndConfigGateFailClosedUntilTheirOwningIssuesSupplyHooks() throws Exception {
        ChangeSetPublisher validatorMissing = publisher(ChangeSetPublisher.unavailableValidator(), allowingGate());
        try {
            Map<String, Object> draft = stagePackage(validatorMissing, Arrays.asList(ChangeSetPublisher.OperationInput.create(
                    "templates/new.yaml", bytes("id: new\n"))));
            ChangeSetPublisher.ValidationRejected rejected = assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    () -> validatorMissing.validate(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false,
                            String.valueOf(draft.get("changeSetId"))));
            assertEquals("ATT-CHANGESET-VALIDATOR-UNAVAILABLE", rejected.code);
            assertFalse(Files.exists(activePackageRoot().resolve("templates/new.yaml")));
        } finally { validatorMissing.close(); }

        ChangeSetPublisher gateMissing = publisher(acceptingValidator(), ChangeSetPublisher.unavailableConfigGate());
        try {
            Map<String, Object> draft = stageCentral(gateMissing, Arrays.asList(ChangeSetPublisher.OperationInput.replace(
                    "config/config.yaml", bytes("schemaVersion: att-config/v2.13\n"))));
            validateReview(gateMissing, String.valueOf(draft.get("changeSetId")), ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true);
            ChangeSetPublisher.ValidationRejected rejected = assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    () -> gateMissing.publish(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                            ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, String.valueOf(draft.get("changeSetId"))));
            assertEquals("ATT-CONFIG-PRECOMMIT-UNAVAILABLE", rejected.code);
            assertEquals("schemaVersion: att-config/v2.13\n", new String(Files.readAllBytes(activeCentralRoot().resolve("config/config.yaml")), StandardCharsets.UTF_8));
        } finally { gateMissing.close(); }
    }

    private boolean publishAfter(CountDownLatch start, ChangeSetPublisher publisher, String id) throws Exception {
        start.await();
        try {
            publisher.publish(ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false, id);
            return true;
        } catch (ChangeSetPublisher.Conflict conflict) { return false; }
    }

    private Map<String, Object> stagePackage(ChangeSetPublisher publisher, List<ChangeSetPublisher.OperationInput> operations) throws Exception {
        Map<String,String> revisions=publisher.currentRevisions(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false);
        return publisher.stage(ChangeSetPublisher.PACKAGE_SOURCE,"owner","owner",false,
                revisions.get("baseSourceRevision"),revisions.get("registryRevision"),operations);
    }

    private Map<String, Object> stageCentral(ChangeSetPublisher publisher, List<ChangeSetPublisher.OperationInput> operations) throws Exception {
        Map<String,String> revisions=publisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                ChangeSetPublisher.CENTRAL_SCOPE_ID,"admin",true);
        return publisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                "admin", true, revisions.get("baseSourceRevision"), null, operations);
    }

    private void validateReview(ChangeSetPublisher publisher, String id) throws Exception {
        validateReview(publisher, id, ChangeSetPublisher.PACKAGE_SOURCE, "owner", "owner", false);
    }

    private void validateReview(ChangeSetPublisher publisher, String id, String domain, String scope, String actor,
                                boolean systemSettingsWrite) throws Exception {
        publisher.validate(domain, scope, actor, systemSettingsWrite, id);
        publisher.review(domain, scope, actor, systemSettingsWrite, id);
    }

    private ChangeSetPublisher.CandidateValidator acceptingValidator() {
        return candidate -> { validatorCalls.incrementAndGet(); return ChangeSetPublisher.ValidationResult.accepted(VALIDATION_REVISION); };
    }

    private ChangeSetPublisher.ConfigPublishGate allowingGate() {
        return (candidate, review) -> { gateCalls.incrementAndGet(); return ChangeSetPublisher.GateDecision.allow(VALIDATION_REVISION); };
    }

    private ChangeSetPublisher publisher(ChangeSetPublisher.CandidateValidator validator,
                                         ChangeSetPublisher.ConfigPublishGate gate) throws Exception {
        return publisher(validator, gate, null);
    }

    private ChangeSetPublisher publisher(ChangeSetPublisher.CandidateValidator validator,
                                         ChangeSetPublisher.ConfigPublishGate gate,
                                         ChangeSetPublisher.SnapshotGenerator snapshotGenerator) throws Exception {
        transaction = new SourceFileTransaction(dataRoot, new SourcePublicationBarrier());
        return new ChangeSetPublisher(Clock.systemUTC(), 128, 8, 900,
                (domain, scope, actor, systemSettingsWrite) -> {
                    if (ChangeSetPublisher.PACKAGE_SOURCE.equals(domain)) {
                        if (!"owner".equals(scope) || !"owner".equals(actor)) throw new ChangeSetPublisher.Denied();
                        return new ChangeSetPublisher.Scope(domain, scope,
                                transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,scope,packageRoot),
                                transaction.pin(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,ChangeSetPublisher.CENTRAL_SCOPE_ID,centralRoot),
                                registryRevision.get(), SCHEMA_REVISION);
                    }
                    if (!ChangeSetPublisher.CENTRAL_SCOPE_ID.equals(scope) || !"admin".equals(actor) || !systemSettingsWrite)
                        throw new ChangeSetPublisher.Denied();
                    Path active=transaction.pin(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,scope,centralRoot);
                    return new ChangeSetPublisher.Scope(domain, scope, active, active, null, SCHEMA_REVISION);
                }, validator, gate,
                (actor, action, domain, scope, detail) -> audit.add(actor + " " + action + " " + domain + " " + scope + " " + detail),
                transaction, snapshotGenerator);
    }

    private String read(String path) throws Exception {
        return new String(Files.readAllBytes(activePackageRoot().resolve(path)), StandardCharsets.UTF_8);
    }

    private Path activePackageRoot() throws Exception { return transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,"owner",packageRoot); }
    private Path activeCentralRoot() throws Exception { return transaction.pin(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
            ChangeSetPublisher.CENTRAL_SCOPE_ID,centralRoot); }
    private void externalPackageWrite(String path,byte[] bytes) throws Exception {
        Path active=activePackageRoot();Path target=active.resolve(path);
        String old=Files.exists(target)?digest(Files.readAllBytes(target)):null;
        Map<String,SourceFileTransaction.Write> writes=new LinkedHashMap<>();
        writes.put(path,new SourceFileTransaction.Write(path,bytes,old));
        transaction.commit(ChangeSetPublisher.PACKAGE_SOURCE,"owner",active,"external",digest("test-before"),
                digest("test-after"),writes,()->{});
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void assertWireResponse(com.networknt.schema.JsonSchema schema, Map<String, Object> source) {
        Map<String, Object> endpoint = new LinkedHashMap<>(source);
        endpoint.put("requestId", "api-request");
        java.util.Set<com.networknt.schema.ValidationMessage> errors = schema.validate(ServerRuntime.JSON.valueToTree(endpoint));
        assertTrue(errors.isEmpty(), errors.toString());
    }
    private static String digest(String value) throws Exception { return digest(bytes(value)); }
    private static String digest(byte[] value) throws Exception {
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : hashed) result.append(String.format("%02x", item & 255));
        return result.toString();
    }
}
