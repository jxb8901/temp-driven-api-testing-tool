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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConfigActivationCoordinatorTest {
    private static final String SCOPE = ChangeSetPublisher.CENTRAL_SCOPE_ID;

    @Test void unavailableGuardKeepsLastKnownGoodAndReportsPublishedRevisionSeparately() {
        ConfigActivationCoordinator coordinator = ConfigActivationCoordinator.unavailable();
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        coordinator.seed(SCOPE, before);

        coordinator.committed(event(before, after));

        ActivationStatus status = coordinator.activationStatus(SCOPE);
        assertEquals(ActivationState.UNAVAILABLE, status.state);
        assertEquals(after.revision, status.publishedRevision.revision);
        assertEquals(before.revision, status.activeRevision.revision);
        assertEquals("ATT-CONFIG-RUNTIME-GUARD-UNAVAILABLE", status.failureCode);
    }

    @Test void failedActivationDoesNotReplaceTheLastKnownGoodRevision() {
        ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new Guard() {
            @Override public ActivationStatus activate(PublicationEvent event) throws Exception {
                throw new IllegalStateException("test failure");
            }
        });
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
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
        ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new Guard() {
            @Override public ActivationStatus activate(PublicationEvent event) {
                return new ActivationStatus(SCOPE, event.after.configSource, event.after.configSource,
                        ActivationState.ACTIVE, null);
            }
            @Override public void invalidate(InvalidationScope scope) { invalidations.incrementAndGet(); }
        });
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        coordinator.seed(SCOPE, before);

        coordinator.committed(event(before, after));
        coordinator.invalidate(new InvalidationScope("payments", "SIT", List.of("config", "helper")));

        ActivationStatus status = coordinator.activationStatus(SCOPE);
        assertEquals(ActivationState.ACTIVE, status.state);
        assertEquals(after.revision, status.publishedRevision.revision);
        assertEquals(after.revision, status.activeRevision.revision);
        assertEquals(1, invalidations.get());
    }

    @Test void nonActiveOrInconsistentGuardResponsesKeepTheLastKnownGoodRevision() {
        RevisionIdentity before = revision("a");
        RevisionIdentity after = revision("b");
        for (ActivationState state : List.of(ActivationState.PENDING, ActivationState.FAILED,
                ActivationState.UNAVAILABLE, ActivationState.ACTIVE)) {
            ConfigActivationCoordinator coordinator = new ConfigActivationCoordinator(new Guard() {
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

    private static PublicationEvent event(RevisionIdentity before, RevisionIdentity after) {
        RevisionSet beforeSet = new RevisionSet(null, null, null, null, before, null, null, null, null);
        RevisionSet afterSet = new RevisionSet(null, null, null, null, after, null, null, null, null);
        return new PublicationEvent(Domain.CENTRAL_RUNTIME_CONFIG, SCOPE, "request-1",
                beforeSet, afterSet, List.of("config/config.yaml"));
    }

    private static RevisionIdentity revision(String value) {
        return RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, SCOPE, value.repeat(64));
    }

    private abstract static class Guard implements ConfigActivationCoordinator.RuntimeGuard {
        @Override public boolean available() { return true; }
        @Override public ActivationStatus activate(PublicationEvent event) throws Exception {
            return new ActivationStatus(SCOPE, event.after.configSource, event.after.configSource,
                    ActivationState.ACTIVE, null);
        }
        @Override public void invalidate(InvalidationScope scope) { }
        @Override public ActivationStatus status(String scopeId) { return null; }
    }
}
