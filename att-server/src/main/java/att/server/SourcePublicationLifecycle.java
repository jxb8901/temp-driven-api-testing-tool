package att.server;

import att.server.api.source.SourceManagementContracts.ActivationState;
import att.server.api.source.SourceManagementContracts.ActivationStatus;
import att.server.api.source.SourceManagementContracts.Domain;
import att.server.api.source.SourceManagementContracts.InvalidationScope;
import att.server.api.source.SourceManagementContracts.PublicationEvent;
import att.server.api.source.SourceManagementContracts.PublicationLifecycleHook;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Server adapter for post-commit scoped invalidation and #335 activation status. */
final class SourcePublicationLifecycle implements PublicationLifecycleHook {
    interface Invalidator { void invalidate(InvalidationScope scope); }

    private final Invalidator invalidator;
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final Map<String, ActivationStatus> activation = new ConcurrentHashMap<>();

    SourcePublicationLifecycle(Invalidator invalidator) { this.invalidator = java.util.Objects.requireNonNull(invalidator); }

    @Override public void committed(PublicationEvent event) {
        if (event.domain == Domain.PACKAGE_SOURCE) {
            invalidate(new InvalidationScope(event.scopeId, null,
                    java.util.List.of("PACKAGE_RESOURCE", "RESOURCE_INDEX", "LOAD_CATALOG", "VALIDATION", "SCHEDULE_REVIEW")));
        } else {
            RevisionIdentity published = event.after.configSource;
            activation.put(event.scopeId, new ActivationStatus(event.scopeId, published, null,
                    ActivationState.UNAVAILABLE, "ATT-CONFIG-ACTIVATION-UNAVAILABLE"));
        }
    }

    @Override public void invalidate(InvalidationScope scope) {
        if (scope == null || scope.packageId == null) return;
        generations.computeIfAbsent(key(scope.packageId, scope.environment), ignored -> new AtomicLong()).incrementAndGet();
        invalidator.invalidate(scope);
    }

    @Override public ActivationStatus activationStatus(String scopeId) {
        return activation.getOrDefault(scopeId,
                new ActivationStatus(scopeId, null, null, ActivationState.UNAVAILABLE, "ATT-CONFIG-ACTIVATION-UNAVAILABLE"));
    }

    void recordActivationStatus(ActivationStatus status) {
        if (status != null) activation.put(status.scopeId, status);
    }

    long generation(String packageId, String environment) {
        long packageGeneration = currentGeneration(packageId, null);
        if (environment == null) return packageGeneration;
        return packageGeneration + currentGeneration(packageId, environment);
    }

    private long currentGeneration(String packageId, String environment) {
        AtomicLong value = generations.get(key(packageId, environment));
        return value == null ? 0 : value.get();
    }

    private static String key(String packageId, String environment) {
        return packageId + "\n" + (environment == null ? "*" : environment);
    }
}
