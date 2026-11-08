package att.server.api.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static att.server.api.source.SourceManagementContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceManagementContractsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REV_1 = repeat('a');
    private static final String REV_2 = repeat('b');
    private static final String DIGEST_1 = repeat('c');
    private static final String DIGEST_2 = repeat('d');

    @Test void independentRuntimeLeaseReadsExactBytesAcrossPublicationAndRejectsStaleAcquisition() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.publish("pay", REV_1, "testdata/customers.yaml", "records: [old]\n");
        runtime.publish("other", REV_1, "testdata/customers.yaml", "records: [other-package]\n");

        SourceLease lease = runtime.acquire(request("pay", REV_1));
        SourceLease retained = lease.retain("queued-dispatch-17");
        VersionedSourceBytes pinned = lease.read("testdata/customers.yaml");
        assertEquals(REV_1, pinned.sourceRevision.revision);
        assertEquals("records: [old]\n", new String(pinned.bytes(), StandardCharsets.UTF_8));

        runtime.publish("pay", REV_2, "testdata/customers.yaml", "records: [new]\n");
        assertEquals("records: [old]\n", new String(lease.read("testdata/customers.yaml").bytes(), StandardCharsets.UTF_8));
        runtime.retire(pinned.sourceRevision);
        assertEquals("records: [old]\n", new String(retained.read("testdata/customers.yaml").bytes(), StandardCharsets.UTF_8));
        assertThrows(StaleSourceException.class, () -> runtime.acquire(request("pay", REV_1)));
        assertEquals("records: [new]\n", new String(runtime.acquire(request("pay", REV_2))
                .read("testdata/customers.yaml").bytes(), StandardCharsets.UTF_8));
        assertEquals("records: [other-package]\n", new String(runtime.acquire(request("other", REV_1))
                .read("testdata/customers.yaml").bytes(), StandardCharsets.UTF_8));
        lease.close();
        retained.close();
        runtime.retire(pinned.sourceRevision);
    }

    @Test void ownerAndCentralClientContractsSeparateCapabilitiesAndSafeSourceViews() throws Exception {
        AuthorityContext owner = new AuthorityContext(Domain.PACKAGE_SOURCE, "pay",
                EnumSet.of(Capability.SAFE_INSPECTION, Capability.READ_AUTHORIZED_SOURCE, Capability.EDIT_PACKAGE_SOURCE),
                SourceCachePolicy.SAFE_INSPECTION_CACHE, false);
        AuthorityContext admin = new AuthorityContext(Domain.CENTRAL_RUNTIME_CONFIG, null,
                EnumSet.of(Capability.EDIT_CENTRAL_CONFIG), SourceCachePolicy.SAFE_INSPECTION_CACHE, false);
        assertEquals("pay", owner.packageId);
        assertNull(admin.packageId);
        assertThrows(IllegalArgumentException.class, () -> new AuthorityContext(Domain.CENTRAL_RUNTIME_CONFIG,
                "pay", Collections.<Capability>emptySet(), SourceCachePolicy.SAFE_INSPECTION_CACHE, false));
        assertThrows(IllegalArgumentException.class, () -> new AuthorityContext(Domain.PACKAGE_SOURCE, "pay",
                EnumSet.of(Capability.READ_AUTHORIZED_SOURCE), SourceCachePolicy.SAFE_INSPECTION_CACHE, true));

        PrivilegedSourceView source = new PrivilegedSourceView("config/secret.yaml", "token: top-secret",
                DIGEST_1);
        assertTrue(source.noStore);
        assertFalse(source.etag.contains("top-secret"));
        SafeSourceView safe = new SafeSourceView("config/secret.yaml", "token: [REDACTED]", true, false);
        JsonNode safeJson = JSON.valueToTree(safe);
        assertFalse(safeJson.has("source"));
        assertFalse(safeJson.toString().contains("top-secret"));
        assertFalse(safe.noStore);

        ApiFailure forbidden = new ApiFailure(ApiFailureStatus.FORBIDDEN_403, "ATT-SOURCE-FORBIDDEN",
                "request-1", "Source read is not authorized", RevisionSet.empty());
        assertEquals(ApiFailureStatus.FORBIDDEN_403, forbidden.status);
    }

    @Test void revisionSerializationKeepsIndependentIdentitiesAndNeverContainsSourceValues() throws Exception {
        RevisionIdentity registration = RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION, "registry", DIGEST_1);
        RevisionIdentity source = RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, "pay", DIGEST_2);
        RevisionIdentity manifest = RevisionIdentity.of(RevisionKind.PACKAGE_MANIFEST, "pay", REV_1);
        RevisionIdentity config = RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, "central-runtime-config", REV_2);
        RevisionIdentity schedule = RevisionIdentity.of(RevisionKind.SCHEDULE_SNAPSHOT, "schedule-4", DIGEST_1);
        RevisionSet set = new RevisionSet(registration, source, null, manifest, config, null, null,
                RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA, "installed-engine", DIGEST_2), schedule);
        JsonNode json = JSON.valueToTree(set);
        assertEquals(RevisionKind.BUSINESS_SOURCE.name(), json.path("businessSource").path("kind").asText());
        assertEquals(RevisionKind.CONFIG_SOURCE.name(), json.path("configSource").path("kind").asText());
        assertEquals(RevisionKind.PACKAGE_MANIFEST.name(), json.path("manifest").path("kind").asText());
        assertFalse(json.toString().contains("password"));
        assertFalse(json.toString().contains("top-secret"));
        assertThrows(IllegalArgumentException.class, () -> new RevisionSet(registration, source, null,
                config, manifest, null, null, null, null));
    }

    @Test void legacyBusinessSourceAndCanonicalPackageAndExecutionRevisionsRemainDistinct() {
        RevisionIdentity legacy = RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, "pay", DIGEST_1);
        RevisionSet oldClient = new RevisionSet(null, legacy, null, null, null, null, null, null, null);
        assertEquals(DIGEST_1, oldClient.packageSourceRevision.revision);
        assertEquals(RevisionKind.PACKAGE_SOURCE, oldClient.packageSourceRevision.kind);
        assertEquals(RevisionKind.BUSINESS_SOURCE, oldClient.businessSource.kind);

        RevisionIdentity packageSource = RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE, "pay", DIGEST_1);
        RevisionIdentity execution = RevisionIdentity.of(RevisionKind.EXECUTION_REVISION, "pay@SIT", DIGEST_2);
        RevisionSet current = new RevisionSet(null, null, null, null, null, null, null, null, null,
                packageSource, execution);
        assertEquals(DIGEST_1, current.businessSource.revision);
        assertNotEquals(current.packageSourceRevision.revision, current.executionRevisionDigest.revision);
        assertEquals(1L, current.present().stream().filter(value -> value.kind == RevisionKind.PACKAGE_SOURCE).count());
        assertEquals(1L, current.present().stream().filter(value -> value.kind == RevisionKind.BUSINESS_SOURCE).count());
        assertThrows(IllegalArgumentException.class, () -> new RevisionSet(null, legacy, null, null, null,
                null, null, null, null,
                RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE, "pay", DIGEST_2), execution));
    }

    @Test void impactFreshnessAcceptsCanonicalPackageSourceAndLegacyBusinessSourceOnly() {
        RevisionIdentity base = RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, "central-runtime-config", DIGEST_1);
        RevisionIdentity candidate = RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, "central-runtime-config", DIGEST_2);
        RevisionIdentity registry = RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION, "package-registry", DIGEST_1);
        RevisionIdentity packageSource = RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE, "pay", DIGEST_1);
        RevisionIdentity legacyBusiness = RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, "legacy-pay", DIGEST_2);
        RevisionIdentity manifest = RevisionIdentity.of(RevisionKind.PACKAGE_MANIFEST, "pay", DIGEST_2);
        RevisionIdentity schema = RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA, "installed-engine", DIGEST_1);

        ImpactFreshnessToken canonical = new ImpactFreshnessToken("draft", DIGEST_1, base, candidate,
                registry, Arrays.asList(packageSource), Arrays.asList(manifest), schema,
                Collections.<RevisionIdentity>emptyList());
        ImpactFreshnessToken legacy = new ImpactFreshnessToken("draft", DIGEST_1, base, candidate,
                registry, Arrays.asList(legacyBusiness), Arrays.asList(manifest), schema,
                Collections.<RevisionIdentity>emptyList());
        assertEquals(RevisionKind.PACKAGE_SOURCE, canonical.packageSources.get(0).kind);
        assertEquals(RevisionKind.BUSINESS_SOURCE, legacy.packageSources.get(0).kind);
        assertThrows(IllegalArgumentException.class, () -> new ImpactFreshnessToken("draft", DIGEST_1,
                base, candidate, registry,
                Arrays.asList(RevisionIdentity.of(RevisionKind.EXECUTION_REVISION, "pay@SIT", DIGEST_1)),
                Arrays.asList(manifest), schema, Collections.<RevisionIdentity>emptyList()));
    }

    @Test void workerExecutionRevisionFieldAcceptsLegacyAliasAndRejectsAmbiguousRequests() throws Exception {
        WorkerRequest legacy = JSON.readValue("{\"expectedRevisionDigest\":\"" + DIGEST_1 + "\"}", WorkerRequest.class);
        legacy.normalizeExpectedExecutionRevisionDigest();
        assertEquals(DIGEST_1, legacy.expectedExecutionRevisionDigest);
        assertEquals(DIGEST_1, legacy.expectedRevisionDigest);

        WorkerRequest current = new WorkerRequest();
        current.expectedExecutionRevisionDigest = DIGEST_2;
        current.normalizeExpectedExecutionRevisionDigest();
        assertEquals(DIGEST_2, current.expectedRevisionDigest);
        assertTrue(JSON.writeValueAsString(current).contains("expectedExecutionRevisionDigest"));

        WorkerRequest ambiguous = new WorkerRequest();
        ambiguous.expectedExecutionRevisionDigest = DIGEST_1;
        ambiguous.expectedRevisionDigest = DIGEST_2;
        assertThrows(IllegalArgumentException.class, ambiguous::normalizeExpectedExecutionRevisionDigest);
    }

    @Test void publicationHooksAreScopedAndImpactProviderUnavailableNeverApproves() throws Exception {
        AtomicInteger commitCount = new AtomicInteger();
        Map<String, InvalidationScope> invalidations = new LinkedHashMap<String, InvalidationScope>();
        PublicationLifecycleHook hook = new PublicationLifecycleHook() {
            @Override public void committed(PublicationEvent event) { commitCount.incrementAndGet(); }
            @Override public void invalidate(InvalidationScope scope) { invalidations.put(scope.packageId, scope); }
            @Override public ActivationStatus activationStatus(String scopeId) {
                return new ActivationStatus(scopeId,
                        RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, scopeId, DIGEST_2),
                        RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, scopeId, DIGEST_1),
                        ActivationState.FAILED, "ATT-ACTIVATION-UNAVAILABLE");
            }
        };
        PublicationEvent event = new PublicationEvent(Domain.PACKAGE_SOURCE, "pay", "req-1",
                RevisionSet.empty(), new RevisionSet(null,
                RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, "pay", DIGEST_2), null, null, null,
                null, null, null, null), Arrays.asList("templates/lookup.yaml"));
        hook.committed(event);
        hook.invalidate(new InvalidationScope("pay", "SIT", Arrays.asList("TEMPLATE", "FILE")));
        assertEquals(1, commitCount.get());
        assertTrue(invalidations.containsKey("pay"));
        assertFalse(invalidations.containsKey("other"));
        ActivationStatus status = hook.activationStatus("pay");
        assertEquals(ActivationState.FAILED, status.state);
        assertNotEquals(status.publishedRevision.revision, status.activeRevision.revision);

        ImpactReport unavailable = ImpactReport.unavailable("ATT-IMPACT-PROVIDER-UNAVAILABLE");
        assertEquals(ProviderState.UNAVAILABLE, unavailable.providerState);
        assertNull(unavailable.decision);
        assertThrows(IllegalArgumentException.class, () -> new ImpactReport(ProviderState.UNAVAILABLE,
                null, null, ImpactDecision.COMPATIBLE, Collections.<AffectedPackage>emptyList(), null, null, false));
        ImpactReportProvider fake335 = new ImpactReportProvider() {
            @Override public ImpactReport report(String draftId, String pageCursor) { return unavailable; }
            @Override public ApprovalDecision authorize(ApprovalRequest request, ImpactAcknowledgement acknowledgement) {
                return new ApprovalDecision(ApprovalState.UNAVAILABLE, null, "ATT-IMPACT-PROVIDER-UNAVAILABLE");
            }
        };
        CandidateValidation candidate = validConfigCandidate();
        ApprovalDecision decision = fake335.authorize(new ApprovalRequest(candidate, "admin", true,
                ApprovalPhase.COMMIT_FENCE, null), null);
        assertEquals(ApprovalState.UNAVAILABLE, decision.state);
    }

    @Test void precommitApprovalMustRemainFreshAtTheCommitFence() throws Exception {
        CandidateValidation candidate = validConfigCandidate();
        AtomicReference<String> currentDependencyFingerprint = new AtomicReference<String>(DIGEST_1);
        PreCommitApprovalHook hook = request -> {
            if (request.phase == ApprovalPhase.COMMIT_FENCE
                    && !candidate.validationFingerprint.equals(currentDependencyFingerprint.get()))
                return new ApprovalDecision(ApprovalState.STALE, null, "ATT-CONFIG-IMPACT-STALE");
            return new ApprovalDecision(ApprovalState.APPROVED, candidate.validationFingerprint, null);
        };
        ApprovalRequest review = new ApprovalRequest(candidate, "admin", true, ApprovalPhase.PRECOMMIT, null);
        assertEquals(ApprovalState.APPROVED, hook.evaluate(review).state);
        currentDependencyFingerprint.set(DIGEST_2);
        ApprovalDecision fence = hook.evaluate(new ApprovalRequest(candidate, "admin", true,
                ApprovalPhase.COMMIT_FENCE, null));
        assertEquals(ApprovalState.STALE, fence.state);
        assertEquals("ATT-CONFIG-IMPACT-STALE", fence.code);
    }

    @Test void typedApiSchemasCoverSixCategoriesSeparateManifestAndConflictStatuses() throws Exception {
        try (java.io.InputStream stream = getClass().getResourceAsStream("/schemas/att-source-management-v1.schema.json")) {
            assertNotNull(stream, "versioned #255 API JSON Schema is packaged");
            JsonNode schema = JSON.readTree(stream);
            assertEquals("https://att.example/schemas/att-source-management/v1", schema.path("$id").asText());
            JsonNode categories = schema.path("$defs").path("PackageCategory").path("enum");
            assertEquals(Arrays.asList("TESTCASE", "TEMPLATE", "FLOW", "LOAD", "TESTDATA", "FILE"),
                    JSON.convertValue(categories, JSON.getTypeFactory().constructCollectionType(java.util.List.class, String.class)));
            assertTrue(schema.path("$defs").path("ChangeSetView").path("properties").has("manifestMetadataChanged"));
            assertTrue(schema.path("$defs").path("ApiFailureStatus").path("enum").toString().contains("CONFLICT_409"));
        }
    }

    @Test void serializedChangeSetDtoAndNullRevisionSetValidateAgainstThePublishedWireSchema() throws Exception {
        JsonNode schema;
        try (java.io.InputStream stream = getClass().getResourceAsStream("/schemas/att-source-management-v1.schema.json")) {
            assertNotNull(stream);
            schema = JSON.readTree(stream);
        }
        com.networknt.schema.JsonSchema validator = com.networknt.schema.JsonSchemaFactory
                .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schema);
        RevisionSet revisions = new RevisionSet(null, null, null, null, null, null, null, null, null,
                RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE, "pay", DIGEST_1),
                RevisionIdentity.of(RevisionKind.EXECUTION_REVISION, "pay@SIT", DIGEST_2));
        ChangeSetView dto = new ChangeSetView("C123", Domain.PACKAGE_SOURCE, "pay", DraftState.DRAFT,
                revisions, null, true, Collections.<PackageCategory>emptyList(), false,
                Collections.<DependencyReference>emptyList(), Collections.<ValidationDiagnostic>emptyList());
        JsonNode serialized = JSON.valueToTree(dto);
        java.util.Set<com.networknt.schema.ValidationMessage> errors = validator.validate(serialized);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals("draft", serialized.path("state").asText());
        assertEquals("PACKAGE_SOURCE", serialized.path("revisions").path("packageSourceRevision").path("kind").asText());
        assertEquals("EXECUTION_REVISION", serialized.path("revisions").path("executionRevisionDigest").path("kind").asText());
        assertEquals(DIGEST_1, serialized.path("revisions").path("businessSource").path("revision").asText());
        assertTrue(serialized.path("revisions").path("packageRegistration").isNull(),
                "absent revision identities serialize as JSON null and remain valid");
        assertTrue(serialized.path("noStore").asBoolean());
    }

    @Test void candidateValidationAndEffectiveResolutionAreTopLevelVersionedSchemaContracts() throws Exception {
        JsonNode schemaNode;
        try(java.io.InputStream stream=getClass().getResourceAsStream("/schemas/att-source-management-v1.schema.json")) {
            assertNotNull(stream);
            schemaNode=JSON.readTree(stream);
        }
        com.networknt.schema.JsonSchema validator=com.networknt.schema.JsonSchemaFactory
                .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        CandidateValidation candidate=validConfigCandidate();
        assertTrue(validator.validate(JSON.valueToTree(candidate)).isEmpty());

        EffectiveResolution resolution=new EffectiveResolution("pay","SIT",DIGEST_1,true,RevisionSet.empty(),
                Collections.singletonMap("timeoutMs",ResolutionOrigin.GLOBAL_CONFIG),
                Collections.singletonMap("timeoutMs",DIGEST_2),Collections.<DependencyReference>emptyList(),
                Collections.<DependencyReference>emptyList(),Collections.<ValidationDiagnostic>emptyList());
        JsonNode serialized=JSON.valueToTree(resolution);
        assertTrue(validator.validate(serialized).isEmpty());
        assertEquals(DIGEST_2,serialized.path("semanticFingerprints").path("timeoutMs").asText());
        assertTrue(serialized.path("revisions").path("packageRegistration").isNull());
    }

    private static CandidateValidation validConfigCandidate() {
        return new CandidateValidation(Domain.CENTRAL_RUNTIME_CONFIG, "central-runtime-config", ValidationState.VALID,
                true, DIGEST_1, RevisionSet.empty(), Collections.<DependencyReference>emptyList(),
                Collections.<ValidationDiagnostic>emptyList());
    }

    private static SourceLeaseRequest request(String packageId, String revision) {
        RevisionSet expected = new RevisionSet(null,
                RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, packageId, revision), null, null, null,
                null, null, null, null);
        return new SourceLeaseRequest(packageId, "SIT", expected,
                Collections.singletonList("testdata/customers.yaml"), "job-42");
    }

    private static final class FakeRuntime implements ImmutableSourceClosureStore {
        private final Map<String, Snapshot> current = new HashMap<String, Snapshot>();
        private final Map<String, Integer> leases = new HashMap<String, Integer>();
        private final Map<String, Snapshot> revisions = new HashMap<String, Snapshot>();
        private final Map<String, Snapshot> leaseSnapshots = new HashMap<String, Snapshot>();
        private int nextLease;

        synchronized void publish(String packageId, String revision, String logicalPath, String value) throws Exception {
            RevisionIdentity identity = RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, packageId, revision);
            Snapshot snapshot = new Snapshot(identity, Collections.singletonMap(logicalPath,
                    value.getBytes(StandardCharsets.UTF_8)));
            current.put(packageId, snapshot);
            revisions.put(packageId + "\n" + revision, snapshot);
        }

        @Override public synchronized SourceLease acquire(SourceLeaseRequest request) throws IOException, StaleSourceException {
            Snapshot latest = current.get(request.packageId);
            RevisionIdentity expected = request.expectedRevisions.businessSource;
            if (latest == null || expected == null || !latest.identity.revision.equals(expected.revision))
                throw new StaleSourceException();
            Snapshot pinned = revisions.get(request.packageId + "\n" + expected.revision);
            if (pinned == null) throw new StaleSourceException();
            Map<String, byte[]> selected = new HashMap<String, byte[]>();
            for (String path : request.logicalPaths) {
                byte[] bytes = pinned.files.get(path);
                if (bytes == null) throw new IOException("Requested source path is unavailable");
                selected.put(path, bytes.clone());
            }
            String key = request.packageId + "\n" + pinned.identity.revision;
            return newLease(request.ownerReference, pinned, selected);
        }

        @Override public synchronized void release(String leaseId, String ownerReference) {
            Snapshot snapshot = leaseSnapshots.remove(leaseId);
            if (snapshot != null) release(snapshot);
        }

        synchronized SourceLease retain(String ownerReference, Snapshot snapshot, Map<String, byte[]> files) {
            return newLease(ownerReference, snapshot, files);
        }

        private synchronized SourceLease newLease(String ownerReference, Snapshot snapshot, Map<String, byte[]> files) {
            String key = snapshot.identity.scopeId + "\n" + snapshot.identity.revision;
            leases.put(key, leases.getOrDefault(key, 0) + 1);
            String id = "fake-lease-" + (++nextLease);
            leaseSnapshots.put(id, snapshot);
            return new FakeLease(this, ownerReference, snapshot, files, id);
        }

        synchronized void release(Snapshot snapshot) {
            String key = snapshot.identity.scopeId + "\n" + snapshot.identity.revision;
            Integer count = leases.get(key);
            if (count == null || count <= 1) leases.remove(key);
            else leases.put(key, count - 1);
        }

        @Override public synchronized void retire(RevisionIdentity revision) {
            if (revision == null) return;
            String key = revision.scopeId + "\n" + revision.revision;
            Snapshot snapshot = revisions.get(key);
            if (snapshot != null && !leases.containsKey(key)) revisions.remove(key);
        }
    }

    private static final class Snapshot {
        final RevisionIdentity identity;
        final Map<String, byte[]> files;
        Snapshot(RevisionIdentity identity, Map<String, byte[]> files) {
            this.identity = identity;
            Map<String, byte[]> copy = new HashMap<String, byte[]>();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) copy.put(entry.getKey(), entry.getValue().clone());
            this.files = Collections.unmodifiableMap(copy);
        }
    }

    private static final class FakeLease implements SourceLease {
        private final FakeRuntime store;
        private final String owner;
        private final Snapshot snapshot;
        private final Map<String, byte[]> files;
        private final String id;
        private boolean closed;
        FakeLease(FakeRuntime store, String owner, Snapshot snapshot, Map<String, byte[]> files, String id) {
            this.store = store; this.owner = owner; this.snapshot = snapshot; this.files = files; this.id = id;
        }
        @Override public String leaseId() { return id; }
        @Override public RevisionSet revisions() {
            return new RevisionSet(null, snapshot.identity, null, null, null, null, null, null, null);
        }
        @Override public VersionedSourceBytes read(String logicalPath) throws IOException {
            if (closed) throw new IOException("Lease is closed");
            byte[] bytes = files.get(logicalPath);
            if (bytes == null) throw new IOException("Source path is outside the lease closure");
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder digest = new StringBuilder();
                for (byte value : hash) digest.append(String.format("%02x", value & 255));
                return new VersionedSourceBytes(snapshot.identity, logicalPath, digest.toString(), bytes);
            } catch (Exception failure) { throw new IOException("Source digest is unavailable"); }
        }
        @Override public SourceLease retain(String ownerReference) throws IOException {
            if (closed) throw new IOException("Lease is closed");
            return store.retain(ownerReference, snapshot, files);
        }
        @Override public void close() {
            if (!closed) { closed = true; store.release(id, owner); }
        }
    }

    private static String repeat(char value) {
        char[] result = new char[64]; Arrays.fill(result, value); return new String(result);
    }
}
