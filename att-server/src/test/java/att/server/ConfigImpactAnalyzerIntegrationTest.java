package att.server;

import att.server.api.source.SourceManagementContracts.ActivationState;
import att.server.api.source.SourceManagementContracts.ActivationStatus;
import att.server.api.source.SourceManagementContracts.ImpactAcknowledgement;
import att.server.api.source.SourceManagementContracts.ImpactDecision;
import att.server.api.source.SourceManagementContracts.PublicationEvent;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import att.server.api.source.SourceManagementContracts.ScheduleAction;
import att.validation.SchemaFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConfigImpactAnalyzerIntegrationTest {
    @TempDir Path temp;

    @Test void ignoresUnreferencedToolAndCommentChangesButRequiresFreshAcknowledgementForEffectiveChanges() throws Exception {
        Path runtime = Files.createDirectories(temp.resolve("runtime"));
        Path packageRoot = Files.createDirectories(temp.resolve("packages/default"));
        Path globalDefaultPackageRoot = Files.createDirectories(temp.resolve("packages/global-default"));
        Path data = Files.createDirectories(temp.resolve("data"));
        Path configDirectory = Files.createDirectories(runtime.resolve("config"));
        createPackage(packageRoot, "SIT-default Package", "SIT");
        createPackage(globalDefaultPackageRoot, "Global-default Package", null);
        Path configFile = configDirectory.resolve("config.yaml");
        String original = """
                schemaVersion: att-config/v2.13
                environment: SIT
                timeoutMs: 10000
                testcase:
                  root: testcase
                templates:
                  root: templates
                tools:
                  impactUnusedProbe:
                    name: Impact unused probe
                    description: Baseline description for an unreferenced tool
                    command: [./tools/probe.sh]
                    stdoutFormat: text
                    arguments: {}
                environments:
                  SIT: {}
                  UAT: {}
                """;
        Files.writeString(configFile, original);

        PackageRegistry.Snapshot registry = registrySnapshot(Map.of("default", packageRoot,
                "global-default", globalDefaultPackageRoot));
        LoadSchedule defaultSchedule = schedule("schedule-default", "default", null, true, "pause");
        LoadSchedule uatPausedSchedule = schedule("schedule-uat-paused", "default", "UAT", false, "strict");
        LoadSchedule uatIgnoreSchedule = schedule("schedule-uat-ignore", "default", "UAT", true, "ignore");
        LoadSchedule globalDefaultSchedule = schedule("schedule-global-default", "global-default", null, true, "strict");
        List<LoadSchedule> initialSchedules = List.of(defaultSchedule, uatPausedSchedule,
                uatIgnoreSchedule, globalDefaultSchedule);
        AtomicReference<List<LoadSchedule>> schedules = new AtomicReference<>(initialSchedules);
        Map<String, ActivationStatus> runtimeStatuses = new ConcurrentHashMap<>();
        ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new ConfigActivationCoordinator.RuntimeGuard() {
            @Override public boolean available() { return true; }
            @Override public ActivationStatus activate(PublicationEvent event) {
                ActivationStatus status = new ActivationStatus(event.scopeId, event.after.configSource,
                        event.after.configSource, ActivationState.ACTIVE, null);
                runtimeStatuses.put(event.scopeId, status);
                return status;
            }
            @Override public void invalidate(att.server.api.source.SourceManagementContracts.InvalidationScope scope) { }
            @Override public ActivationStatus status(String scopeId) { return runtimeStatuses.get(scopeId); }
        });
        SourceFileTransaction transaction = new SourceFileTransaction(data, new SourcePublicationBarrier());
        String schemaRevision = SchemaFiles.registryDigest();
        ConfigImpactAnalyzer analyzer = new ConfigImpactAnalyzer(new ConfigImpactAnalyzer.Inventory() {
            @Override public PackageRegistry.Snapshot registry() { return registry; }
            @Override public List<LoadSchedule> schedules() { return schedules.get(); }
        }, PackageConfigurationPolicy.load(null), "strict", new att.validation.SourceCandidateValidator(), coordinator);
        ChangeSetPublisher publisher = new ChangeSetPublisher(Clock.systemUTC(), 16, 4, 900,
                (domain, scopeId, actor, systemSettingsWrite) -> {
                    if (!ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain)
                            || !ChangeSetPublisher.CENTRAL_SCOPE_ID.equals(scopeId)
                            || !"admin".equals(actor) || !systemSettingsWrite) throw new ChangeSetPublisher.Denied();
                    Path pinned = transaction.pin(domain, scopeId, runtime);
                    return new ChangeSetPublisher.Scope(domain, scopeId, pinned, pinned, null, schemaRevision);
                }, new ChangeSetCandidateValidator(), (candidate, review) -> {
                    var approval = analyzer.authorizeCandidate(candidate, review);
                    if (approval.state == att.server.api.source.SourceManagementContracts.ApprovalState.APPROVED)
                        return ChangeSetPublisher.GateDecision.allow(approval.approvalRevision);
                    return ChangeSetPublisher.GateDecision.block(approval.code == null
                            ? "ATT-CONFIG-IMPACT-BLOCKED" : approval.code);
                }, new ChangeSetPublisher.Audit() {
                    @Override public void record(String actor, String action, String domain, String scopeId, String detail) { }
                    @Override public void recordCommitted(SourceFileTransaction.AuditRecord record) {
                        RevisionIdentity before = RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, record.scopeId,
                                record.beforeRevision);
                        RevisionIdentity after = RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, record.scopeId,
                                record.afterRevision);
                        coordinator.committed(new PublicationEvent(
                                att.server.api.source.SourceManagementContracts.Domain.CENTRAL_RUNTIME_CONFIG,
                                record.scopeId, record.requestId,
                                new RevisionSet(null, null, null, null, before, null, null, null, null),
                                new RevisionSet(null, null, null, null, after, null, null, null, null), record.resources));
                    }
                }, transaction, new ChangeSetCandidateValidator(), analyzer);
        try (publisher) {
            Map<String, String> current = publisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true);
            coordinator.seed(ChangeSetPublisher.CENTRAL_SCOPE_ID, RevisionIdentity.of(RevisionKind.CONFIG_SOURCE,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, current.get("baseSourceRevision")));

            String commentOnly = original + "\n# A comment does not change effective Config semantics.\n";
            Map<String, Object> commentDraft = publisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, current.get("baseSourceRevision"), null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml", bytes(commentOnly))));
            String commentId = String.valueOf(commentDraft.get("changeSetId"));
            try {
                publisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                        "admin", true, commentId);
            } catch (ChangeSetPublisher.ValidationRejected invalid) {
                throw new AssertionError("candidate validation failed with " + invalid.code, invalid);
            }
            var commentImpact = publisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, commentId, null);
            assertEquals(ImpactDecision.COMPATIBLE, commentImpact.decision);
            assertTrue(commentImpact.affectedPackages.isEmpty());
            assertFalse(commentImpact.acknowledgementRequired);

            String unusedTool = original.replace("Baseline description for an unreferenced tool",
                    "Changed description for an unreferenced tool").replace(
                    "                    arguments: {}\n                environments:",
                    "                    arguments: {}\n  impactUnusedAddition:\n"
                            + "    name: Impact unused addition\n"
                            + "    description: An unreferenced added Tool\n"
                            + "    command: [./tools/probe.sh]\n"
                            + "    stdoutFormat: text\n"
                            + "    arguments: {}\n                environments:");
            Map<String, Object> unusedDraft = publisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, current.get("baseSourceRevision"), null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml", bytes(unusedTool))));
            String unusedId = String.valueOf(unusedDraft.get("changeSetId"));
            publisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                    "admin", true, unusedId);
            var unusedImpact = publisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, unusedId, null);
            assertEquals(ImpactDecision.COMPATIBLE, unusedImpact.decision, "provider=" + unusedImpact.providerState
                    + " coverage=" + unusedImpact.coverage + " code=" + unusedImpact.unavailableCode);
            assertTrue(unusedImpact.affectedPackages.isEmpty());
            assertFalse(unusedImpact.acknowledgementRequired);

            String withComment = unusedTool + "\n# A comment does not change effective Config semantics.\n";
            String changedTimeout = withComment.replace("timeoutMs: 10000", "timeoutMs: 12000");
            Map<String, Object> impactDraft = publisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, current.get("baseSourceRevision"), null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml", bytes(changedTimeout))));
            String impactId = String.valueOf(impactDraft.get("changeSetId"));
            try {
                publisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                        "admin", true, impactId);
            } catch (ChangeSetPublisher.ValidationRejected invalid) {
                throw new AssertionError("impact candidate validation failed with " + invalid.code, invalid);
            }
            publisher.review(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                    "admin", true, impactId);
            var impact = publisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, impactId, null);
            assertEquals(ImpactDecision.REQUIRES_REVIEW, impact.decision);
            assertTrue(impact.acknowledgementRequired);
            assertTrue(impact.affectedPackages.stream().anyMatch(item -> "default".equals(item.packageId)
                    && item.certainty == att.server.api.source.SourceManagementContracts.Certainty.CONFIRMED_AFFECTED));
            assertTrue(impact.affectedPackages.stream().anyMatch(item -> "default".equals(item.packageId)
                    && "UAT".equalsIgnoreCase(item.environment)));
            assertTrue(impact.affectedPackages.stream().anyMatch(item -> "global-default".equals(item.packageId)
                    && "UAT".equalsIgnoreCase(item.environment)));
            assertTrue(impact.schedules.items.stream().anyMatch(item -> "schedule-default".equals(item.scheduleId)
                    && item.action == ScheduleAction.PAUSE_AND_REAPPROVE));
            assertTrue(impact.schedules.items.stream().anyMatch(item -> "schedule-uat-paused".equals(item.scheduleId)
                    && item.action == ScheduleAction.PAUSE_AND_REAPPROVE));
            assertTrue(impact.schedules.items.stream().anyMatch(item -> "schedule-uat-ignore".equals(item.scheduleId)
                    && item.action == ScheduleAction.UNCHANGED));

            String freshness = ConfigImpactAnalyzer.freshnessFingerprint(impact.freshness);
            ImpactAcknowledgement acknowledgement = new ImpactAcknowledgement(freshness, "admin",
                    ConfigImpactAnalyzer.acknowledgementDigest(freshness, "admin"));
            LoadSchedule changedSchedule = schedule("schedule-default", "default", null, false, "pause");
            schedules.set(List.of(changedSchedule, uatPausedSchedule, uatIgnoreSchedule, globalDefaultSchedule));
            ChangeSetPublisher.ValidationRejected stale = assertThrows(ChangeSetPublisher.ValidationRejected.class,
                    () -> publisher.publish(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                            ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, impactId,
                            "stale-impact-integration-test", acknowledgement));
            assertEquals("ATT-CONFIG-IMPACT-STALE", stale.code);
            schedules.set(initialSchedules);
            Map<String, Object> published;
            try {
                published = publisher.publish(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                        ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, impactId, "impact-integration-test",
                        acknowledgement);
            } catch (ChangeSetPublisher.ValidationRejected rejected) {
                throw new AssertionError("publish gate rejected with " + rejected.code, rejected);
            }
            assertEquals(Boolean.TRUE, published.get("published"));
            ActivationStatus active = coordinator.activationStatus(ChangeSetPublisher.CENTRAL_SCOPE_ID);
            assertEquals(ActivationState.ACTIVE, active.state);
            assertEquals(published.get("revision"), active.activeRevision.revision);
            assertEquals("timeoutMs: 12000", Files.readString(runtime.resolve("config/config.yaml"))
                    .lines().filter(line -> line.startsWith("timeoutMs:")).findFirst().orElseThrow());

            Map<String, String> afterPublish = publisher.currentRevisions(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true);
            String migratedDefault = Files.readString(configFile).replace("environment: SIT", "environment: UAT");
            Map<String, Object> defaultDraft = publisher.stage(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, afterPublish.get("baseSourceRevision"), null,
                    List.of(ChangeSetPublisher.OperationInput.replace("config/config.yaml", bytes(migratedDefault))));
            String defaultDraftId = String.valueOf(defaultDraft.get("changeSetId"));
            publisher.validate(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG, ChangeSetPublisher.CENTRAL_SCOPE_ID,
                    "admin", true, defaultDraftId);
            var defaultImpact = publisher.impactReport(ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG,
                    ChangeSetPublisher.CENTRAL_SCOPE_ID, "admin", true, defaultDraftId, null);
            assertEquals(ImpactDecision.REQUIRES_REVIEW, defaultImpact.decision);
            assertTrue(defaultImpact.affectedPackages.stream().anyMatch(item -> "global-default".equals(item.packageId)
                    && "UAT".equalsIgnoreCase(item.environment)
                    && item.certainty == att.server.api.source.SourceManagementContracts.Certainty.CONFIRMED_AFFECTED));
            assertFalse(defaultImpact.affectedPackages.stream().anyMatch(item -> "default".equals(item.packageId)
                    && "UAT".equalsIgnoreCase(item.environment)),
                    "the Package Manifest's SIT default shields it from the global default migration");
            assertTrue(defaultImpact.schedules.items.stream().anyMatch(item -> "schedule-global-default".equals(item.scheduleId)
                    && item.action == ScheduleAction.REVALIDATE));
        }
    }

    private static PackageRegistry.Snapshot registrySnapshot(Map<String, Path> packages) throws Exception {
        Constructor<?> constructor = PackageRegistry.Snapshot.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (PackageRegistry.Snapshot) constructor.newInstance(1L, "c".repeat(64), "READY",
                Instant.now(), null, null, null, packages, List.copyOf(packages.values()),
                packages.keySet().stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> "READY")));
    }

    private static void createPackage(Path packageRoot, String name, String defaultEnvironment) throws Exception {
        Files.createDirectories(packageRoot.resolve("templates"));
        Files.createDirectories(packageRoot.resolve("testcase"));
        Path probe = Files.createDirectories(packageRoot.resolve("tools")).resolve("probe.sh");
        Files.writeString(probe, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(probe, java.util.EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        String defaultValue = defaultEnvironment == null ? "" : "defaultEnvironment: " + defaultEnvironment + "\n";
        Files.writeString(packageRoot.resolve("package.yaml"), "schemaVersion: att-package/v1\nmetadata:\n  name: "
                + name + "\n" + defaultValue);
    }

    private static LoadSchedule schedule(String id, String packageId, String environment,
                                         boolean enabled, String policy) {
        LoadSchedule schedule = new LoadSchedule();
        schedule.id = id;
        schedule.name = id;
        schedule.packageId = packageId;
        schedule.environment = environment;
        schedule.enabled = enabled;
        schedule.packageChangePolicy = policy;
        return schedule;
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
