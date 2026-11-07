package att.server;

import att.server.api.source.SourceManagementContracts.ApprovalDecision;
import att.server.api.source.SourceManagementContracts.ApprovalPhase;
import att.server.api.source.SourceManagementContracts.ApprovalRequest;
import att.server.api.source.SourceManagementContracts.CandidateValidation;
import att.server.api.source.SourceManagementContracts.Domain;
import att.server.api.source.SourceManagementContracts.ImpactFreshnessToken;
import att.server.api.source.SourceManagementContracts.ImpactReport;
import att.server.api.source.SourceManagementContracts.ProviderState;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import att.server.api.source.SourceManagementContracts.ValidationState;
import att.validation.SourceCandidateValidator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigImpactAnalyzerTest {
    @Test void freshnessFingerprintChangesWhenAnyPackageOrScheduleRevisionChanges() {
        ImpactFreshnessToken original = freshness("a", "a");
        ImpactFreshnessToken changedPackage = freshness("b", "a");
        ImpactFreshnessToken changedSchedule = freshness("a", "b");

        String fingerprint = ConfigImpactAnalyzer.freshnessFingerprint(original);
        assertNotEquals(fingerprint, ConfigImpactAnalyzer.freshnessFingerprint(changedPackage));
        assertNotEquals(fingerprint, ConfigImpactAnalyzer.freshnessFingerprint(changedSchedule));
    }

    @Test void acknowledgementDigestBindsBothTheReportAndTheAdministrator() {
        String fingerprint = "a".repeat(64);
        String administratorDigest = ConfigImpactAnalyzer.acknowledgementDigest(fingerprint, "admin");

        assertNotEquals(administratorDigest, ConfigImpactAnalyzer.acknowledgementDigest(fingerprint, "other-admin"));
        assertNotEquals(administratorDigest, ConfigImpactAnalyzer.acknowledgementDigest("b".repeat(64), "admin"));
    }

    @Test void missingCandidateReportRemainsUnavailableAndCannotApprove() {
        ConfigImpactAnalyzer analyzer = new ConfigImpactAnalyzer(new ConfigImpactAnalyzer.Inventory() {
            @Override public PackageRegistry.Snapshot registry() { return null; }
            @Override public List<LoadSchedule> schedules() { return Collections.emptyList(); }
        }, PackageConfigurationPolicy.load(null), new SourceCandidateValidator(),
                ConfigActivationCoordinator.unavailable());
        ImpactReport report = analyzer.report("lost-draft", null);
        CandidateValidation candidate = new CandidateValidation(Domain.CENTRAL_RUNTIME_CONFIG,
                ChangeSetPublisher.CENTRAL_SCOPE_ID, ValidationState.VALID, true, "a".repeat(64),
                RevisionSet.empty(), Collections.emptyList(), Collections.emptyList());
        ApprovalRequest request = new ApprovalRequest(candidate, "admin", true,
                ApprovalPhase.COMMIT_FENCE, null);

        ApprovalDecision decision = analyzer.authorize(request, null);

        assertEquals(ProviderState.UNAVAILABLE, report.providerState);
        assertEquals(att.server.api.source.SourceManagementContracts.ApprovalState.UNAVAILABLE, decision.state);
        assertEquals("ATT-CONFIG-IMPACT-UNAVAILABLE", decision.code);
    }

    private static ImpactFreshnessToken freshness(String packageRevision, String scheduleRevision) {
        RevisionIdentity base = RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, "central-runtime-config", "base");
        RevisionIdentity candidate = RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, "central-runtime-config", "candidate");
        RevisionIdentity registry = RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION, "package-registry", "registry");
        RevisionIdentity pkg = RevisionIdentity.of(RevisionKind.BUSINESS_SOURCE, "payments", packageRevision);
        RevisionIdentity manifest = RevisionIdentity.of(RevisionKind.PACKAGE_MANIFEST, "payments", "manifest");
        RevisionIdentity schema = RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA, "installed-engine", "schema");
        RevisionIdentity schedule = RevisionIdentity.of(RevisionKind.SCHEDULE_SNAPSHOT, "schedule-1", scheduleRevision);
        return new ImpactFreshnessToken("draft-1", "f".repeat(64), base, candidate, registry,
                List.of(pkg), List.of(manifest), schema, List.of(schedule));
    }
}
