package att.server;

import att.server.api.source.SourceManagementContracts.ActivationState;
import att.server.api.source.SourceManagementContracts.ActivationStatus;
import att.server.api.source.SourceManagementContracts.Domain;
import att.server.api.source.SourceManagementContracts.InvalidationScope;
import att.server.api.source.SourceManagementContracts.PublicationEvent;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigActivationCoordinatorTest {
    private static final String SCOPE = ChangeSetPublisher.CENTRAL_SCOPE_ID;
    @TempDir Path temp;

    @Test void unavailableGuardKeepsLastKnownGoodAndReportsPublishedRevisionSeparately() {
        ConfigActivationCoordinator coordinator = ConfigActivationCoordinator.unavailable();
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        coordinator.seed(SCOPE, before);

        coordinator.committed(event(before, after));

        ActivationStatus status = coordinator.activationStatus(SCOPE);
        assertEquals(ActivationState.UNAVAILABLE, status.state);
        assertEquals(after.revision, status.publishedRevision.revision);
        assertNull(status.activeRevision, "an unverified startup source must not be reported as active");
        assertEquals("ATT-CONFIG-RUNTIME-GUARD-UNAVAILABLE", status.failureCode);
    }

    @Test void failedActivationDoesNotReplaceTheLastKnownGoodRevision() {
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new Guard(before) {
            @Override public ActivationStatus activate(PublicationEvent event) throws Exception {
                throw new IllegalStateException("test failure");
            }
        });
        coordinator.seed(SCOPE, before);

        coordinator.committed(event(before, after));

        ActivationStatus status = coordinator.activationStatus(SCOPE);
        assertEquals(ActivationState.FAILED, status.state);
        assertEquals(after.revision, status.publishedRevision.revision);
        assertEquals(before.revision, status.activeRevision.revision);
        assertEquals("ATT-CONFIG-ACTIVATION-FAILED", status.failureCode);
    }

    @Test void successfulActivationPublishesAndActivatesTheSameRevisionAndInvalidatesTargetedScopes() {
        AtomicInteger invalidations = new AtomicInteger();
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new Guard(before) {
            @Override public ActivationStatus activate(PublicationEvent event) throws Exception {
                return super.activate(event);
            }
            @Override public void invalidate(InvalidationScope scope) { invalidations.incrementAndGet(); }
        });
        coordinator.seed(SCOPE, before);

        coordinator.committed(event(before, after));
        coordinator.invalidate(new InvalidationScope("payments", "SIT", List.of("config", "helper")));

        ActivationStatus status = coordinator.activationStatus(SCOPE);
        assertEquals(ActivationState.ACTIVE, status.state);
        assertEquals(after.revision, status.publishedRevision.revision);
        assertEquals(after.revision, status.activeRevision.revision);
        assertEquals(1, invalidations.get());
    }

    @Test void postPublicationInvalidationFailureIsReportedSoThePublisherCanRollBack() {
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new Guard(before) {
            @Override public void afterPublication(String revision) throws Exception {
                throw new IllegalStateException("cache invalidation failed");
            }
        });
        coordinator.seed(SCOPE, before);
        coordinator.committed(event(before, after));

        assertFalse(coordinator.afterPublication(after.revision));

        ActivationStatus status = coordinator.recordedStatus(SCOPE);
        assertEquals(ActivationState.FAILED, status.state);
        assertEquals(after.revision, status.publishedRevision.revision);
        assertEquals(after.revision, status.activeRevision.revision);
        assertEquals("ATT-CONFIG-ACTIVATION-INVALIDATION-FAILED", status.failureCode);
    }

    @Test void claimedActivationWithoutLiveGuardReadbackMustFailClosed() {
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new Guard(before) {
            @Override public ActivationStatus activate(PublicationEvent event) {
                // Misbehaving guard returns success without changing actual live dispatch.
                return new ActivationStatus(SCOPE, event.after.configSource,
                        event.after.configSource, ActivationState.ACTIVE, null);
            }
        });
        coordinator.seed(SCOPE, before);
        coordinator.committed(event(before, after));

        ActivationStatus status = coordinator.activationStatus(SCOPE);
        assertEquals(ActivationState.FAILED, status.state);
        assertEquals(after.revision, status.publishedRevision.revision);
        assertEquals(before.revision, status.activeRevision.revision);
        assertEquals("ATT-CONFIG-ACTIVATION-STATE-UNVERIFIED", status.failureCode);
    }

    @Test void nonActiveOrInconsistentGuardResponsesKeepTheLastKnownGoodRevision() {
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        for (ActivationState state : List.of(ActivationState.PENDING, ActivationState.FAILED,
                ActivationState.UNAVAILABLE, ActivationState.ACTIVE)) {
            ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new Guard(before) {
                @Override public ActivationStatus activate(PublicationEvent event) {
                    RevisionIdentity unconfirmed = state == ActivationState.ACTIVE ? before : null;
                    return new ActivationStatus(SCOPE, event.after.configSource, unconfirmed, state, null);
                }
            });
            coordinator.seed(SCOPE, before);

            coordinator.committed(event(before, after));

            ActivationStatus status = coordinator.activationStatus(SCOPE);
            assertEquals(after.revision, status.publishedRevision.revision);
            assertEquals(before.revision, status.activeRevision.revision);
            assertNotEquals(ActivationState.ACTIVE, status.state);
            assertNotNull(status.failureCode);
        }
    }

    @Test void restartReconcilesPublishedPointerAndRecoversLastKnownGoodWithoutClaimingActive() throws Exception {
        Path journal = temp.resolve("config-activation-status.json");
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        ConfigActivationCoordinator active = ConfigActivationCoordinator.fileBacked(new Guard(before), journal);
        active.seed(SCOPE, before);
        active.committed(event(before, after));

        ConfigActivationCoordinator restarted = ConfigActivationCoordinator.fileBackedUnavailable(journal);
        restarted.seed(SCOPE, revision("c"));

        ActivationStatus status = restarted.activationStatus(SCOPE);
        assertEquals(ActivationState.FAILED, status.state);
        assertEquals(after.revision, status.publishedRevision.revision);
        assertEquals(revision("c").revision, status.activeRevision.revision,
                "an unexpected active pointer is reported accurately but remains unverified");
        assertEquals("ATT-CONFIG-ACTIVATION-STATE-UNVERIFIED", status.failureCode);
    }

    @Test void restartPreservesFailedActivationWhenEitherLastKnownGoodOrFailedCandidateIsActive() throws Exception {
        Path journal = temp.resolve("failed-config-activation-status.json");
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        Guard firstGuard = new Guard(before) {
            @Override public ActivationStatus activate(PublicationEvent event) {
                observed = new ActivationStatus(SCOPE, event.after.configSource, event.before.configSource,
                        ActivationState.FAILED, "ATT-CONFIG-ACTIVATION-FAILED");
                return observed;
            }
        };
        ConfigActivationCoordinator publishing = ConfigActivationCoordinator.fileBacked(firstGuard, journal);
        publishing.seed(SCOPE, before);
        publishing.committed(event(before, after));

        Guard rolledBackGuard = new Guard(before);
        ConfigActivationCoordinator rolledBack = ConfigActivationCoordinator.fileBacked(rolledBackGuard, journal);
        rolledBack.seed(SCOPE, before);
        ActivationStatus rolledBackStatus = rolledBack.activationStatus(SCOPE);
        assertEquals(ActivationState.FAILED, rolledBackStatus.state);
        assertEquals(after.revision, rolledBackStatus.publishedRevision.revision);
        assertEquals(before.revision, rolledBackStatus.activeRevision.revision);

        Guard rollbackFailedGuard = new Guard(after);
        ConfigActivationCoordinator rollbackFailed = ConfigActivationCoordinator.fileBacked(rollbackFailedGuard, journal);
        rollbackFailed.seed(SCOPE, after);
        ActivationStatus rollbackFailedStatus = rollbackFailed.activationStatus(SCOPE);
        assertEquals(ActivationState.FAILED, rollbackFailedStatus.state,
                "A candidate left active after a failed rollback must not be promoted to ACTIVE on restart.");
        assertEquals(after.revision, rollbackFailedStatus.publishedRevision.revision);
        assertEquals(after.revision, rollbackFailedStatus.activeRevision.revision);
    }

    @Test void restartDoesNotPromoteAnUnexpectedActivePointerAfterAPreviouslyVerifiedRevision() throws Exception {
        Path journal = temp.resolve("unexpected-config-pointer-status.json");
        RevisionIdentity verified = revision("a");
        RevisionIdentity unexpected = revision("c");
        ConfigActivationCoordinator first = ConfigActivationCoordinator.fileBacked(new Guard(verified), journal);
        first.seed(SCOPE, verified);

        ConfigActivationCoordinator restarted = ConfigActivationCoordinator.fileBacked(new Guard(unexpected), journal);
        restarted.seed(SCOPE, unexpected);
        ActivationStatus status = restarted.activationStatus(SCOPE);

        assertEquals(ActivationState.FAILED, status.state);
        assertEquals("ATT-CONFIG-ACTIVATION-STATE-UNVERIFIED", status.failureCode);
        assertEquals(verified.revision, status.publishedRevision.revision);
        assertEquals(unexpected.revision, status.activeRevision.revision,
                "The reported pointer must show the unexpected active revision while keeping it unapproved.");
    }

    private static PublicationEvent event(RevisionIdentity before, RevisionIdentity after) {
        RevisionSet beforeSet = new RevisionSet(null, null, null, null, before, null, null, null, null);
        RevisionSet afterSet = new RevisionSet(null, null, null, null, after, null, null, null, null);
        return new PublicationEvent(Domain.CENTRAL_RUNTIME_CONFIG, SCOPE, "request-1",
                beforeSet, afterSet, List.of("config/config.yaml"));
    }

    private static RevisionIdentity revision(String value) {
        return RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, SCOPE, value.repeat(64));
    }

    private static class Guard implements ConfigActivationCoordinator.RuntimeGuard {
        volatile ActivationStatus observed;
        Guard() { }
        Guard(RevisionIdentity initialActive) {
            observed = new ActivationStatus(SCOPE, initialActive, initialActive, ActivationState.ACTIVE, null);
        }
        @Override public boolean available() { return true; }
        @Override public ActivationStatus activate(PublicationEvent event) throws Exception {
            observed = new ActivationStatus(SCOPE, event.after.configSource, event.after.configSource,
                    ActivationState.ACTIVE, null);
            return observed;
        }
        @Override public void invalidate(InvalidationScope scope) { }
        @Override public ActivationStatus status(String scopeId) { return observed; }
    }

}
