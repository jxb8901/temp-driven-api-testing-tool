package att.server;

import att.server.api.source.SourceManagementContracts.ActivationState;
import att.server.api.source.SourceManagementContracts.ActivationStatus;
import att.server.api.source.SourceManagementContracts.Domain;
import att.server.api.source.SourceManagementContracts.InvalidationScope;
import att.server.api.source.SourceManagementContracts.PublicationEvent;
import att.server.api.source.SourceManagementContracts.PublicationLifecycleHook;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates central Config publication with the immutable runtime guard supplied by #254. */
final class ConfigActivationCoordinator implements PublicationLifecycleHook {
    static final String CENTRAL_SCOPE = ChangeSetPublisher.CENTRAL_SCOPE_ID;
    private static final ObjectMapper JSON = new ObjectMapper();

    interface RuntimeGuard {
        boolean available();
        ActivationStatus activate(PublicationEvent event) throws Exception;
        void invalidate(InvalidationScope scope) throws Exception;
        ActivationStatus status(String scopeId);
    }

    interface StatusStore {
        ActivationStatus load(String scopeId) throws Exception;
        void save(ActivationStatus status) throws Exception;
    }

    private final RuntimeGuard runtimeGuard;
    private final StatusStore statusStore;
    private final Map<String, ActivationStatus> statuses = new ConcurrentHashMap<>();

    ConfigActivationCoordinator(RuntimeGuard runtimeGuard) {
        this(runtimeGuard, new MemoryStatusStore());
    }

    ConfigActivationCoordinator(RuntimeGuard runtimeGuard, StatusStore statusStore) {
        this.runtimeGuard = runtimeGuard;
        this.statusStore = java.util.Objects.requireNonNull(statusStore);
    }

    static ConfigActivationCoordinator unavailable() {
        return new ConfigActivationCoordinator(null);
    }

    static ConfigActivationCoordinator fileBackedUnavailable(Path path) {
        return new ConfigActivationCoordinator(null, new FileStatusStore(path));
    }

    static ConfigActivationCoordinator fileBacked(RuntimeGuard runtimeGuard, Path path) {
        return new ConfigActivationCoordinator(runtimeGuard, new FileStatusStore(path));
    }

    boolean canActivateOnline() {
        return runtimeGuard != null && runtimeGuard.available();
    }

    /** Reconcile the recovered source pointer independently of audit replay. */
    void seed(String scopeId, RevisionIdentity publishedRevision) {
        if (scopeId == null || publishedRevision == null) return;
        ActivationStatus persisted = null;
        String loadFailure = null;
        try {
            persisted = statusStore.load(scopeId);
        } catch (Exception unavailable) {
            loadFailure = "ATT-CONFIG-ACTIVATION-STATE-UNAVAILABLE";
        }

        ActivationStatus observed = guardStatus(scopeId);
        if (verifiedActive(observed, scopeId, publishedRevision)) {
            store(observed);
            return;
        }

        RevisionIdentity lastKnownGood = persisted == null ? null : persisted.activeRevision;
        if (observed != null && observed.activeRevision != null) lastKnownGood = observed.activeRevision;
        ActivationState state = observed != null && runtimeGuard != null && runtimeGuard.available()
                ? ActivationState.FAILED : ActivationState.UNAVAILABLE;
        String failure = loadFailure != null ? loadFailure
                : state == ActivationState.FAILED ? "ATT-CONFIG-ACTIVATION-STATE-UNVERIFIED"
                : "ATT-CONFIG-ACTIVATION-STATUS-UNAVAILABLE";
        store(new ActivationStatus(scopeId, publishedRevision, lastKnownGood, state, failure));
    }

    @Override public void committed(PublicationEvent event) {
        if (event == null || event.domain != Domain.CENTRAL_RUNTIME_CONFIG) return;
        String scopeId = event.scopeId;
        ActivationStatus previous = activationStatus(scopeId);
        RevisionIdentity lastKnownGood = previous == null ? null : previous.activeRevision;
        RevisionIdentity published = event.after.configSource;
        if (!canActivateOnline()) {
            store(new ActivationStatus(scopeId, published, lastKnownGood,
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
                // The activation result is an acknowledgement, not proof that the live
                // runtime guard switched its dispatch state. Verify its read-back.
                ActivationStatus observed = guardStatus(scopeId);
                if (verifiedActive(observed, scopeId, published)) {
                    store(observed);
                } else {
                    store(new ActivationStatus(scopeId, published, lastKnownGood,
                            ActivationState.FAILED, "ATT-CONFIG-ACTIVATION-STATE-UNVERIFIED"));
                }
            } else {
                ActivationState state = status.state == ActivationState.ACTIVE ? ActivationState.FAILED : status.state;
                String failureCode = status.failureCode == null
                        ? state == ActivationState.PENDING ? "ATT-CONFIG-ACTIVATION-PENDING"
                        : state == ActivationState.UNAVAILABLE ? "ATT-CONFIG-RUNTIME-GUARD-UNAVAILABLE"
                        : "ATT-CONFIG-ACTIVATION-FAILED"
                        : status.failureCode;
                store(new ActivationStatus(scopeId, published, lastKnownGood, state, failureCode));
            }
        } catch (Exception failure) {
            store(new ActivationStatus(scopeId, published, lastKnownGood,
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
        ActivationStatus external = guardStatus(scopeId);
        RevisionIdentity expectedPublished = local == null ? null : local.publishedRevision;
        if (verifiedActive(external, scopeId, expectedPublished)) {
            store(external);
            return external;
        }
        if (external != null) {
            if (local != null && local.state != ActivationState.ACTIVE
                    && expectedPublished != null && external.publishedRevision != null
                    && !same(expectedPublished, external.publishedRevision)) return local;
            RevisionIdentity published = expectedPublished == null ? external.publishedRevision : expectedPublished;
            RevisionIdentity lastKnownGood = local == null ? external.activeRevision : local.activeRevision;
            ActivationState state = external.state == ActivationState.ACTIVE
                    ? ActivationState.FAILED : external.state;
            String failure = external.failureCode == null
                    ? "ATT-CONFIG-ACTIVATION-STATE-UNVERIFIED" : external.failureCode;
            ActivationStatus reconciled = new ActivationStatus(scopeId, published, lastKnownGood, state, failure);
            store(reconciled);
            return reconciled;
        }
        if (local != null) {
            if (local.state == ActivationState.ACTIVE) {
                ActivationStatus unverified = new ActivationStatus(scopeId, local.publishedRevision,
                        local.activeRevision, ActivationState.UNAVAILABLE,
                        "ATT-CONFIG-ACTIVATION-STATUS-UNAVAILABLE");
                store(unverified);
                return unverified;
            }
            return local;
        }
        return new ActivationStatus(scopeId, null, null, ActivationState.UNAVAILABLE,
                "ATT-CONFIG-ACTIVATION-STATUS-UNAVAILABLE");
    }

    private ActivationStatus guardStatus(String scopeId) {
        if (!canActivateOnline()) return null;
        try {
            ActivationStatus status = runtimeGuard.status(scopeId);
            return status != null && scopeId.equals(status.scopeId) ? status : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean verifiedActive(ActivationStatus status, String scopeId,
                                          RevisionIdentity expectedPublished) {
        return status != null && scopeId.equals(status.scopeId)
                && status.state == ActivationState.ACTIVE
                && status.failureCode == null
                && status.publishedRevision != null
                && status.activeRevision != null
                && same(status.publishedRevision, status.activeRevision)
                && (expectedPublished == null || same(status.publishedRevision, expectedPublished));
    }

    private void store(ActivationStatus status) {
        if (status == null) return;
        statuses.put(status.scopeId, status);
        try {
            statusStore.save(status);
        } catch (Exception failure) {
            statuses.put(status.scopeId, new ActivationStatus(status.scopeId, status.publishedRevision,
                    status.activeRevision, ActivationState.UNAVAILABLE,
                    "ATT-CONFIG-ACTIVATION-STATE-PERSIST-FAILED"));
        }
    }

    private static boolean same(RevisionIdentity left, RevisionIdentity right) {
        return left != null && right != null && left.kind == right.kind && left.scopeId.equals(right.scopeId)
                && java.util.Objects.equals(left.qualifier, right.qualifier)
                && left.revision.equals(right.revision);
    }

    private static final class MemoryStatusStore implements StatusStore {
        private final Map<String, ActivationStatus> values = new ConcurrentHashMap<>();
        @Override public ActivationStatus load(String scopeId) { return values.get(scopeId); }
        @Override public void save(ActivationStatus status) { values.put(status.scopeId, status); }
    }

    private static final class FileStatusStore implements StatusStore {
        private final Path file;
        FileStatusStore(Path file) { this.file = file.toAbsolutePath().normalize(); }

        @Override public synchronized ActivationStatus load(String scopeId) throws Exception {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > 16 * 1024) throw new IOException("Activation status is unavailable");
            Map<?, ?> values = JSON.readValue(Files.readAllBytes(file), Map.class);
            if (!"att-config-activation-journal/v1".equals(values.get("schemaVersion")))
                throw new IOException("Activation status version is unsupported");
            if (!scopeId.equals(values.get("scopeId"))) return null;
            String published = string(values.get("publishedRevision"));
            String active = string(values.get("activeRevision"));
            String rawState = string(values.get("state"));
            ActivationState state = ActivationState.valueOf(rawState);
            return new ActivationStatus(scopeId, revision(scopeId, published), revision(scopeId, active), state,
                    string(values.get("failureCode")));
        }

        @Override public synchronized void save(ActivationStatus status) throws Exception {
            Path parent = file.getParent();
            if (parent == null) throw new IOException("Activation status directory is unavailable");
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(file))
                throw new IOException("Activation status path is unsafe");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("schemaVersion", "att-config-activation-journal/v1");
            values.put("scopeId", status.scopeId);
            values.put("publishedRevision", revisionText(status.publishedRevision));
            values.put("activeRevision", revisionText(status.activeRevision));
            values.put("state", status.state.name());
            values.put("failureCode", status.failureCode);
            Path temporary = parent.resolve(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            byte[] bytes = JSON.writeValueAsBytes(values);
            try {
                Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                try { Files.setPosixFilePermissions(temporary, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE)); } catch (UnsupportedOperationException ignored) { }
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private static String string(Object value) {
            if (value == null) return null;
            if (!(value instanceof String text)) throw new IllegalArgumentException("Activation status is malformed");
            return text;
        }
        private static String revisionText(RevisionIdentity value) { return value == null ? null : value.revision; }
        private static RevisionIdentity revision(String scopeId, String value) {
            if (value == null) return null;
            return RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, scopeId, value);
        }
    }
}
