package att.server;

import att.server.api.source.SourceManagementContracts.ActivationState;
import att.server.api.source.SourceManagementContracts.ActivationStatus;
import att.server.api.source.SourceManagementContracts.InvalidationScope;
import att.server.api.source.SourceManagementContracts.PublicationEvent;
import att.server.api.source.SourceManagementContracts.PublicationLifecycleHook;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates central Config publication with the immutable runtime guard supplied by #254. */
final class ConfigActivationCoordinator implements PublicationLifecycleHook {
    static final String CENTRAL_SCOPE = ChangeSetPublisher.CENTRAL_SCOPE_ID;

    interface RuntimeGuard {
        boolean available();
        ActivationStatus activate(PublicationEvent event) throws Exception;
        void invalidate(InvalidationScope scope) throws Exception;
        ActivationStatus status(String scopeId);
    }

    private final RuntimeGuard runtimeGuard;
    private final Map<String, ActivationStatus> statuses = new ConcurrentHashMap<>();

    ConfigActivationCoordinator(RuntimeGuard runtimeGuard) {
        this.runtimeGuard = runtimeGuard;
    }

    static ConfigActivationCoordinator unavailable() {
        return new ConfigActivationCoordinator(null);
    }

    boolean canActivateOnline() {
        return runtimeGuard != null && runtimeGuard.available();
    }

    void seed(String scopeId, RevisionIdentity revision) {
        if (revision == null) return;
        statuses.put(scopeId, new ActivationStatus(scopeId, revision, revision, ActivationState.ACTIVE, null));
    }

    @Override public void committed(PublicationEvent event) {
        if (event == null || event.domain != att.server.api.source.SourceManagementContracts.Domain.CENTRAL_RUNTIME_CONFIG)
            return;
        String scopeId = event.scopeId;
        ActivationStatus previous = statuses.get(scopeId);
        RevisionIdentity lastKnownGood = previous == null ? null : previous.activeRevision;
        RevisionIdentity published = event.after.configSource;
        if (!canActivateOnline()) {
            statuses.put(scopeId, new ActivationStatus(scopeId, published, lastKnownGood,
                    ActivationState.UNAVAILABLE, "ATT-CONFIG-RUNTIME-GUARD-UNAVAILABLE"));
            return;
        }
        try {
            ActivationStatus status = runtimeGuard.activate(event);
            if (status == null || !scopeId.equals(status.scopeId)
                    || status.publishedRevision == null || published == null
                    || !same(status.publishedRevision, published)) {
                throw new IllegalStateException("Runtime guard returned an inconsistent activation status");
            }
            if (status.state == ActivationState.ACTIVE && same(status.activeRevision, published)
                    && status.failureCode == null) {
                statuses.put(scopeId, status);
            } else {
                ActivationState state = status.state == ActivationState.ACTIVE ? ActivationState.FAILED : status.state;
                String failureCode = status.failureCode == null
                        ? state == ActivationState.PENDING ? "ATT-CONFIG-ACTIVATION-PENDING"
                        : state == ActivationState.UNAVAILABLE ? "ATT-CONFIG-RUNTIME-GUARD-UNAVAILABLE"
                        : "ATT-CONFIG-ACTIVATION-FAILED"
                        : status.failureCode;
                statuses.put(scopeId, new ActivationStatus(scopeId, published, lastKnownGood, state, failureCode));
            }
        } catch (Exception failure) {
            statuses.put(scopeId, new ActivationStatus(scopeId, published, lastKnownGood,
                    ActivationState.FAILED, "ATT-CONFIG-ACTIVATION-FAILED"));
        }
    }

    @Override public void invalidate(InvalidationScope scope) {
        if (scope == null || runtimeGuard == null || !runtimeGuard.available()) return;
        try { runtimeGuard.invalidate(scope); }
        catch (Exception ignored) { /* Targeted cache invalidation is retried by the owning runtime guard. */ }
    }

    @Override public ActivationStatus activationStatus(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) return null;
        ActivationStatus local = statuses.get(scopeId);
        if (local != null) return local;
        if (runtimeGuard != null && runtimeGuard.available()) {
            try {
                ActivationStatus external = runtimeGuard.status(scopeId);
                if (external != null) return external;
            } catch (RuntimeException ignored) { }
        }
        return new ActivationStatus(scopeId, null, null, ActivationState.UNAVAILABLE,
                "ATT-CONFIG-ACTIVATION-STATUS-UNAVAILABLE");
    }

    private static boolean same(RevisionIdentity left, RevisionIdentity right) {
        return left.kind == right.kind && left.scopeId.equals(right.scopeId)
                && java.util.Objects.equals(left.qualifier, right.qualifier)
                && left.revision.equals(right.revision);
    }
}
