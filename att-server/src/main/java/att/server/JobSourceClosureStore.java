package att.server;

import att.server.api.source.SourceManagementContracts;
import att.server.api.source.SourceManagementContracts.ImmutableSourceClosureStore;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;
import att.server.api.source.SourceManagementContracts.SourceLease;
import att.server.api.source.SourceManagementContracts.SourceLeaseRequest;
import att.server.api.source.SourceManagementContracts.StaleSourceException;
import att.server.api.source.SourceManagementContracts.VersionedSourceBytes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable ownership and exact reads for immutable Package and central Config source trees. */
final class JobSourceClosureStore implements ImmutableSourceClosureStore {
    static final String PROVENANCE_VERSION = "att-job-source-provenance/v1";
    static final String JOB_OWNER_PREFIX = "job:";
    static final String SCHEDULE_OWNER_PREFIX = "schedule:";
    private static final long MAX_READ_BYTES = 64L * 1024L * 1024L;
    private static final String PACKAGE_DOMAIN = ChangeSetPublisher.PACKAGE_SOURCE;
    private static final String CONFIG_DOMAIN = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG;

    static final class SourceProvenance {
        final RevisionSet revisions;
        final String validationFingerprint;

        SourceProvenance(RevisionSet revisions, String validationFingerprint) {
            this.revisions = java.util.Objects.requireNonNull(revisions);
            if (validationFingerprint != null && !validationFingerprint.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Execution validation fingerprint is invalid");
            this.validationFingerprint = validationFingerprint;
        }

        Map<String, Object> view() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", PROVENANCE_VERSION);
            result.put("revisions", ServerRuntime.JSON.convertValue(revisions, Map.class));
            if (validationFingerprint != null) result.put("validationFingerprint", validationFingerprint);
            result.put("snapshotState", "RETAINED");
            return result;
        }
    }

    static final class SourceSnapshot {
        final String leaseId;
        final RevisionSet revisions;
        final Path packageRoot;
        final Path configRoot;
        final Path workerConfigPath;

        SourceSnapshot(String leaseId, RevisionSet revisions, Path packageRoot, Path configRoot, Path workerConfigPath) {
            this.leaseId=leaseId;this.revisions=revisions;this.packageRoot=packageRoot;
            this.configRoot=configRoot;this.workerConfigPath=workerConfigPath;
        }
    }

    private static final class Registration {
        final RevisionIdentity identity;
        final String domain;
        final Path root;
        Registration(RevisionIdentity identity, String domain, Path root) {
            this.identity = identity; this.domain = domain; this.root = root;
        }
    }

    private static final class LeaseRecord {
        public String id;
        public String packageId;
        public String environment;
        public Map<String, Object> revisions;
        public String packageRevisionKey;
        public String configRevisionKey;
        public String packageRoot;
        public String configRoot;
        public String configPath;
        public List<String> logicalPaths;
        public Set<String> owners;
    }

    private final Path directory;
    private final Path leasesDirectory;
    private final Path retiredPath;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<String, LeaseRecord> leases = new ConcurrentHashMap<>();
    private final Set<String> retired = ConcurrentHashMap.newKeySet();

    JobSourceClosureStore(Path dataDirectory) throws IOException {
        directory = dataDirectory.resolve("job-source-closures").toAbsolutePath().normalize();
        leasesDirectory = directory.resolve("leases");
        retiredPath = directory.resolve("retired.json");
        createPrivateDirectory(directory);
        createPrivateDirectory(leasesDirectory);
        loadRetired();
        loadLeases();
    }

    synchronized void registerSnapshot(RevisionIdentity identity, String domain, Path root) throws IOException {
        if (identity == null || root == null || !(PACKAGE_DOMAIN.equals(domain) || CONFIG_DOMAIN.equals(domain)))
            throw new IllegalArgumentException("An immutable source revision and scope are required");
        Path canonical = root.toRealPath();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
                || !canonical.getFileName().toString().equals(identity.revision))
            throw new IOException("Immutable source revision is unavailable");
        String key = revisionKey(identity);
        if (retired.contains(key)) throw new StaleSourceException();
        Registration current = registrations.get(key);
        if (current != null && (!current.root.equals(canonical) || !current.domain.equals(domain)))
            throw new IOException("Immutable source revision identity is ambiguous");
        registrations.put(key, new Registration(identity, domain, canonical));
    }

    SourceProvenance provenance(RevisionSet revisions, String validationFingerprint) {
        return new SourceProvenance(revisions, validationFingerprint);
    }

    @Override public synchronized SourceLease acquire(SourceLeaseRequest request) throws IOException, StaleSourceException {
        if (request == null || request.packageId == null || request.packageId.isBlank())
            throw new IOException("A Package source scope is required");
        RevisionIdentity business = request.expectedRevisions.businessSource;
        RevisionIdentity config = request.expectedRevisions.configSource;
        if (business == null || config == null || business.kind != RevisionKind.BUSINESS_SOURCE
                || config.kind != RevisionKind.CONFIG_SOURCE || !request.packageId.equals(business.scopeId)
                || !"central-runtime-config".equals(config.scopeId))
            throw new StaleSourceException();
        String packageKey = revisionKey(business), configKey = revisionKey(config);
        if (retired.contains(packageKey) || retired.contains(configKey)) throw new StaleSourceException();
        Registration packageSource = registrations.get(packageKey), configSource = registrations.get(configKey);
        if (!available(packageSource, PACKAGE_DOMAIN, business) || !available(configSource, CONFIG_DOMAIN, config))
            throw new StaleSourceException();

        LeaseRecord record = new LeaseRecord();
        record.id = "L" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        record.packageId = request.packageId;
        record.environment = request.environment;
        record.revisions = ServerRuntime.JSON.convertValue(request.expectedRevisions, Map.class);
        record.packageRevisionKey = packageKey;
        record.configRevisionKey = configKey;
        record.packageRoot = packageSource.root.toString();
        record.configRoot = configSource.root.toString();
        record.logicalPaths = List.copyOf(request.logicalPaths);
        record.owners = new LinkedHashSet<>(Set.of(request.ownerReference));
        persist(record);
        leases.put(record.id, record);
        return new LeaseHandle(record.id, request.ownerReference);
    }

    @Override public synchronized void release(String leaseId, String ownerReference) throws IOException {
        LeaseRecord record = leases.get(leaseId);
        if (record == null || record.owners == null || !record.owners.remove(ownerReference)) return;
        if (record.owners.isEmpty()) {
            leases.remove(leaseId);
            Files.deleteIfExists(leasePath(leaseId));
            forceDirectory(leasesDirectory);
        } else persist(record);
    }

    /** Releases a durable Job or Schedule owner without requiring the caller to retain a handle. */
    synchronized void releaseOwner(String ownerReference) throws IOException {
        if (ownerReference == null || ownerReference.isBlank()) return;
        for (LeaseRecord record : new ArrayList<>(leases.values()))
            if (record.owners != null && record.owners.contains(ownerReference)) release(record.id, ownerReference);
    }

    @Override public synchronized void retire(RevisionIdentity revision) throws IOException {
        if (revision == null) throw new IllegalArgumentException("A source revision is required");
        retired.add(revisionKey(revision));
        List<String> values = new ArrayList<>(retired); Collections.sort(values);
        writeAtomic(retiredPath, ServerRuntime.JSON.writeValueAsBytes(values));
    }

    synchronized boolean hasOwner(String ownerReference) {
        if (ownerReference == null) return false;
        for (LeaseRecord record : leases.values())
            if (record.owners != null && record.owners.contains(ownerReference)) return true;
        return false;
    }

    synchronized void bindWorkerConfiguration(String leaseId,Path workerConfigPath) throws IOException {
        LeaseRecord record=leases.get(leaseId);
        if(record==null)throw new IOException("The immutable source lease is unavailable");
        Path root=Path.of(record.configRoot).toRealPath(),file=workerConfigPath.toRealPath();
        if(!file.startsWith(root)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(workerConfigPath))
            throw new StaleSourceException();
        record.configPath=root.relativize(file).toString().replace(java.io.File.separatorChar,'/');
        persist(record);
    }

    synchronized SourceLease retainOwner(String ownerReference,String newOwner) throws IOException {
        for(LeaseRecord record:leases.values())if(record.owners!=null&&record.owners.contains(ownerReference))
            return new LeaseHandle(record.id,ownerReference).retain(newOwner);
        throw new StaleSourceException();
    }

    synchronized SourceSnapshot snapshotForOwner(String ownerReference) throws IOException {
        for(LeaseRecord record:leases.values())if(record.owners!=null&&record.owners.contains(ownerReference)) {
            Path packageRoot=Path.of(record.packageRoot).toRealPath();
            Path configRoot=Path.of(record.configRoot).toRealPath();
            RevisionSet revisions=revisionSet(record.revisions);
            if(revisions.businessSource==null||revisions.configSource==null
                    ||!packageRoot.getFileName().toString().equals(revisions.businessSource.revision)
                    ||!configRoot.getFileName().toString().equals(revisions.configSource.revision))throw new StaleSourceException();
            Path workerConfig=record.configPath==null?null:configRoot.resolve(record.configPath).normalize();
            if(workerConfig==null||!workerConfig.startsWith(configRoot)||!Files.isRegularFile(workerConfig,LinkOption.NOFOLLOW_LINKS)
                    ||Files.isSymbolicLink(workerConfig))throw new StaleSourceException();
            return new SourceSnapshot(record.id,revisions,packageRoot,configRoot,workerConfig);
        }
        throw new StaleSourceException();
    }

    static RevisionSet revisionsFromProvenance(Map<String,Object> provenance) {
        if(provenance==null||!PROVENANCE_VERSION.equals(provenance.get("schemaVersion"))
                ||!(provenance.get("revisions") instanceof Map<?,?> revisions))
            throw new IllegalArgumentException("Source revision provenance is invalid");
        Map<String,Object> values=new LinkedHashMap<>();
        for(Map.Entry<?,?> entry:revisions.entrySet())if(entry.getKey() instanceof String key)values.put(key,entry.getValue());
        return revisionSet(values);
    }

    static String validationFingerprint(Map<String,Object> provenance) {
        Object value=provenance==null?null:provenance.get("validationFingerprint");
        return value instanceof String?(String)value:null;
    }

    synchronized boolean verifyDispatch(String leaseId, String ownerReference, Path packageRoot, Path configFile) {
        LeaseRecord record = leases.get(leaseId);
        if (record == null || record.owners == null || !record.owners.contains(ownerReference)) return false;
        try {
            Path expectedPackageRoot = Path.of(record.packageRoot).toRealPath();
            Path expectedConfigRoot = Path.of(record.configRoot).toRealPath();
            Path acceptedPackageRoot = packageRoot.toRealPath();
            Path acceptedConfigFile = configFile.toRealPath();
            RevisionSet revisions = revisionSet(record.revisions);
            String configRelative=expectedConfigRoot.relativize(acceptedConfigFile).toString().replace(java.io.File.separatorChar,'/');
            return !Files.isSymbolicLink(expectedPackageRoot) && !Files.isSymbolicLink(expectedConfigRoot)
                    && expectedPackageRoot.equals(acceptedPackageRoot)
                    && acceptedPackageRoot.getFileName().toString().equals(revisions.businessSource.revision)
                    && record.configPath!=null && record.configPath.equals(configRelative)
                    && acceptedConfigFile.startsWith(expectedConfigRoot)
                    && Files.isRegularFile(acceptedConfigFile, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(configFile);
        } catch (Exception unavailable) { return false; }
    }

    synchronized void close() { }

    private boolean available(Registration registration, String domain, RevisionIdentity identity) {
        if (registration == null || !registration.domain.equals(domain) || !registration.identity.revision.equals(identity.revision)) return false;
        try {
            Path root = registration.root.toRealPath();
            return root.equals(registration.root) && !Files.isSymbolicLink(root)
                    && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    && root.getFileName().toString().equals(identity.revision);
        } catch (IOException unavailable) { return false; }
    }

    private final class LeaseHandle implements SourceLease {
        private final String id;
        private final String owner;
        private volatile boolean closed;
        LeaseHandle(String id, String owner) { this.id = id; this.owner = owner; }
        @Override public String leaseId() { return id; }
        @Override public RevisionSet revisions() {
            synchronized (JobSourceClosureStore.this) {
                LeaseRecord record = leases.get(id);
                if (record == null) throw new IllegalStateException("Source lease is unavailable");
                return revisionSet(record.revisions);
            }
        }
        @Override public VersionedSourceBytes read(String logicalPath) throws IOException {
            synchronized (JobSourceClosureStore.this) {
                LeaseRecord record = requireLease(id, owner);
                String normalized = safeLogicalPath(logicalPath);
                if (record.logicalPaths != null && !record.logicalPaths.isEmpty() && !record.logicalPaths.contains(normalized))
                    throw new IOException("The source path is outside the approved closure");
                RevisionSet revisions = revisionSet(record.revisions);
                Path packageRoot = Path.of(record.packageRoot), configRoot = Path.of(record.configRoot);
                Path source = readableFile(packageRoot, normalized);
                RevisionIdentity identity = revisions.businessSource;
                if (source == null) {
                    source = readableFile(configRoot, normalized);
                    identity = revisions.configSource;
                }
                if (source == null) throw new IOException("The source path is unavailable in the retained revision");
                long length = Files.size(source);
                if (length > MAX_READ_BYTES) throw new IOException("The retained source file exceeds the read limit");
                byte[] bytes = Files.readAllBytes(source);
                return new VersionedSourceBytes(identity, normalized, digest(bytes), bytes);
            }
        }
        @Override public SourceLease retain(String ownerReference) throws IOException {
            if (ownerReference == null || ownerReference.isBlank()) throw new IllegalArgumentException("A source lease owner is required");
            synchronized (JobSourceClosureStore.this) {
                LeaseRecord record = requireLease(id, owner);
                if (record.owners.add(ownerReference)) persist(record);
                return new LeaseHandle(id, ownerReference);
            }
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { release(id, owner); } catch (IOException ignored) { }
        }
    }

    private LeaseRecord requireLease(String id, String owner) throws IOException {
        LeaseRecord record = leases.get(id);
        if (record == null || record.owners == null || !record.owners.contains(owner))
            throw new IOException("The immutable source lease is unavailable");
        return record;
    }

    private static Path readableFile(Path root, String logicalPath) throws IOException {
        Path canonicalRoot = root.toRealPath();
        Path candidate = canonicalRoot.resolve(logicalPath).normalize();
        if (!candidate.startsWith(canonicalRoot)) return null;
        Path current = canonicalRoot;
        for (Path segment : canonicalRoot.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("The retained source path is unavailable");
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return null;
        Path real = candidate.toRealPath();
        return real.startsWith(canonicalRoot) ? real : null;
    }

    private static String safeLogicalPath(String path) throws IOException {
        if (path == null || path.isBlank() || path.length() > 1024 || path.startsWith("/")
                || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0
                || path.chars().anyMatch(Character::isISOControl)) throw new IOException("The logical source path is invalid");
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().isBlank()
                || !normalized.toString().replace('\\', '/').equals(path))
            throw new IOException("The logical source path is invalid");
        return path;
    }

    private void loadLeases() throws IOException {
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(leasesDirectory, "L*.json")) {
            for (Path path : paths) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024)
                    continue;
                try {
                    LeaseRecord record = ServerRuntime.JSON.readValue(path.toFile(), LeaseRecord.class);
                    if (record.id != null && record.id.matches("L[A-F0-9]{32}") && record.owners != null && !record.owners.isEmpty()
                            && record.packageRoot != null && record.configRoot != null && record.revisions != null
                            && path.getFileName().toString().equals(record.id + ".json")) leases.put(record.id, record);
                } catch (Exception invalid) { /* Corrupt leases remain unavailable; never resolve them by a mutable source path. */ }
            }
        }
    }

    private void loadRetired() throws IOException {
        if (!Files.exists(retiredPath, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(retiredPath) || !Files.isRegularFile(retiredPath, LinkOption.NOFOLLOW_LINKS) || Files.size(retiredPath) > 4 * 1024 * 1024)
            throw new IOException("The immutable source retirement index is unsafe");
        JsonNode values = ServerRuntime.JSON.readTree(retiredPath.toFile());
        if (values != null && values.isArray()) for (JsonNode value : values)
            if (value.isTextual() && value.asText().matches("[a-f0-9]{64}")) retired.add(value.asText());
    }

    private void persist(LeaseRecord record) throws IOException {
        writeAtomic(leasePath(record.id), ServerRuntime.JSON.writeValueAsBytes(record));
    }

    private Path leasePath(String id) throws IOException {
        if (id == null || !id.matches("L[A-F0-9]{32}")) throw new IOException("Source lease identity is invalid");
        return leasesDirectory.resolve(id + ".json");
    }

    private static RevisionSet revisionSet(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("Source revision set is unavailable");
        return new RevisionSet(identity(raw.get("packageRegistration"), RevisionKind.PACKAGE_REGISTRATION),
                identity(raw.get("businessSource"), RevisionKind.BUSINESS_SOURCE),
                identity(raw.get("changeSet"), RevisionKind.CHANGE_SET),
                identity(raw.get("manifest"), RevisionKind.PACKAGE_MANIFEST),
                identity(raw.get("configSource"), RevisionKind.CONFIG_SOURCE),
                identity(raw.get("effectiveConfig"), RevisionKind.EFFECTIVE_CONFIG),
                identity(raw.get("environment"), RevisionKind.ENVIRONMENT),
                identity(raw.get("engineSchema"), RevisionKind.ENGINE_SCHEMA),
                identity(raw.get("scheduleSnapshot"), RevisionKind.SCHEDULE_SNAPSHOT));
    }

    private static RevisionIdentity identity(Object raw, RevisionKind expected) {
        if (!(raw instanceof Map<?, ?> value)) return null;
        if (!expected.name().equals(String.valueOf(value.get("kind")))) throw new IllegalArgumentException("Source revision kind is invalid");
        return new RevisionIdentity(expected, text(value.get("scopeId")), text(value.get("qualifier")), text(value.get("revision")));
    }

    private static String text(Object value) { return value instanceof String string ? string : null; }

    private static String revisionKey(RevisionIdentity identity) {
        String value = identity.kind.name() + "\n" + identity.scopeId + "\n"
                + (identity.qualifier == null ? "" : identity.qualifier) + "\n" + identity.revision;
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The source closure store is unsafe");
        } else Files.createDirectories(path);
        try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
        catch (UnsupportedOperationException ignored) { }
    }

    private static void writeAtomic(Path path, byte[] bytes) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes)); channel.force(true);
        }
        try { Files.setPosixFilePermissions(temporary, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignored) { }
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        forceDirectory(path.getParent());
    }

    private static void forceDirectory(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        catch (Exception ignored) { }
    }
}
