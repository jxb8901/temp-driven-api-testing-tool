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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Durable ownership and exact reads for immutable Package and central Config source trees. */
final class JobSourceClosureStore implements ImmutableSourceClosureStore {
    static final String PROVENANCE_VERSION = "att-job-source-provenance/v1";
    static final String JOB_OWNER_PREFIX = "job:";
    static final String SCHEDULE_OWNER_PREFIX = "schedule:";
    private static final long MAX_READ_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_SNAPSHOT_BYTES = 512L * 1024L * 1024L;
    private static final String SNAPSHOT_VERSION = "att-worker-source-snapshot/v1";
    private static final String PACKAGE_DOMAIN = ChangeSetPublisher.PACKAGE_SOURCE;
    private static final String CONFIG_DOMAIN = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG;

    static final class SourceProvenance {
        final RevisionSet revisions;
        final String validationFingerprint;
        final List<Map<String,Object>> dependencies;
        final List<String> logicalPaths;
        final byte[] compiledFilePlans;

        SourceProvenance(RevisionSet revisions, String validationFingerprint,
                         List<Map<String,Object>> dependencies, List<String> logicalPaths, byte[] compiledFilePlans) {
            this.revisions = java.util.Objects.requireNonNull(revisions);
            if (validationFingerprint != null && !validationFingerprint.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Execution validation fingerprint is invalid");
            this.validationFingerprint = validationFingerprint;
            this.dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
            this.logicalPaths = List.copyOf(logicalPaths == null ? List.of() : logicalPaths);
            this.compiledFilePlans = compiledFilePlans == null ? null : compiledFilePlans.clone();
        }

        Map<String, Object> view() { return view(true); }

        Map<String, Object> view(boolean available) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", PROVENANCE_VERSION);
            result.put("revisions", ServerRuntime.JSON.convertValue(revisions, Map.class));
            if (validationFingerprint != null) result.put("validationFingerprint", validationFingerprint);
            result.put("dependencyClosure", dependencies);
            result.put("snapshotState", available ? "RETAINED" : "UNAVAILABLE");
            if (!available) result.put("requiredAction", "Review and replace this execution snapshot before running it again.");
            return result;
        }

        List<String> logicalPaths() { return logicalPaths; }
    }

    static final class SourceSnapshot {
        final String leaseId;
        final RevisionSet revisions;
        final Path packageRoot;
        final Path configRoot;
        final Path workerConfigPath;
        final Path archivePath;
        final String archiveSha256;
        final String configRelativePath;
        final List<Map<String,Object>> dependencies;
        final List<String> logicalPaths;
        final byte[] compiledFilePlans;

        SourceSnapshot(String leaseId, RevisionSet revisions, Path packageRoot, Path configRoot, Path workerConfigPath,
                       Path archivePath, String archiveSha256, String configRelativePath,
                       List<Map<String,Object>> dependencies, List<String> logicalPaths, byte[] compiledFilePlans) {
            this.leaseId=leaseId;this.revisions=revisions;this.packageRoot=packageRoot;
            this.configRoot=configRoot;this.workerConfigPath=workerConfigPath;
            this.archivePath=archivePath;this.archiveSha256=archiveSha256;this.configRelativePath=configRelativePath;
            this.dependencies=List.copyOf(dependencies==null?List.of():dependencies);
            this.logicalPaths=List.copyOf(logicalPaths==null?List.of():logicalPaths);
            this.compiledFilePlans=compiledFilePlans==null?null:compiledFilePlans.clone();
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
        public List<Map<String,Object>> dependencies;
        public String snapshotArchivePath;
        public String snapshotArchiveSha256;
        public String compiledFilePlansSha256;
        public String packageTreeDigest;
        public String configTreeDigest;
        public String packageExecutableDigest;
        public String configExecutableDigest;
        public Set<String> owners;
    }

    private final Path directory;
    private final Path leasesDirectory;
    private final Path snapshotsDirectory;
    private final Path materializedDirectory;
    private final Path retiredPath;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<String, LeaseRecord> leases = new ConcurrentHashMap<>();
    private final Set<String> retired = ConcurrentHashMap.newKeySet();

    JobSourceClosureStore(Path dataDirectory) throws IOException {
        directory = dataDirectory.resolve("job-source-closures").toAbsolutePath().normalize();
        leasesDirectory = directory.resolve("leases");
        snapshotsDirectory = directory.resolve("snapshots");
        materializedDirectory = directory.resolve("materialized");
        retiredPath = directory.resolve("retired.json");
        createPrivateDirectory(directory);
        createPrivateDirectory(leasesDirectory);
        createPrivateDirectory(snapshotsDirectory);
        createPrivateDirectory(materializedDirectory);
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

    SourceProvenance provenance(RevisionSet revisions, String validationFingerprint,
                                List<Map<String,Object>> dependencies, List<String> logicalPaths) {
        return provenance(revisions,validationFingerprint,dependencies,logicalPaths,null);
    }
    SourceProvenance provenance(RevisionSet revisions, String validationFingerprint,
                                List<Map<String,Object>> dependencies, List<String> logicalPaths,
                                byte[] compiledFilePlans) {
        return new SourceProvenance(revisions, validationFingerprint, dependencies, logicalPaths, compiledFilePlans);
    }

    @Override public synchronized SourceLease acquire(SourceLeaseRequest request) throws IOException, StaleSourceException {
        if (request == null || request.packageId == null || request.packageId.isBlank())
            throw new IOException("A Package source scope is required");
        RevisionIdentity business = request.expectedRevisions.businessSource;
        RevisionIdentity config = request.expectedRevisions.configSource;
        if (business == null || config == null || business.kind != RevisionKind.BUSINESS_SOURCE
                || config.kind != RevisionKind.CONFIG_SOURCE || !request.packageId.equals(business.scopeId)
                || !"central-runtime-config".equals(config.scopeId) || request.logicalPaths.isEmpty())
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
        record.dependencies = List.of();
        record.owners = new LinkedHashSet<>(Set.of(request.ownerReference));
        createSnapshotArchive(record,null);
        try { persist(record); }
        catch(IOException failure) { deleteSnapshotFiles(record); throw failure; }
        leases.put(record.id, record);
        return new LeaseHandle(record.id, request.ownerReference);
    }

    @Override public synchronized void release(String leaseId, String ownerReference) throws IOException {
        LeaseRecord record = leases.get(leaseId);
        if (record == null || record.owners == null || !record.owners.remove(ownerReference)) return;
        if (record.owners.isEmpty()) {
            leases.remove(leaseId);
            Files.deleteIfExists(leasePath(leaseId));
            deleteSnapshotFiles(record);
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
            if (record.owners != null && record.owners.contains(ownerReference)) return snapshotAvailable(record);
        return false;
    }

    synchronized void bindWorkerConfiguration(String leaseId,Path workerConfigPath) throws IOException {
        bindWorkerConfiguration(leaseId,workerConfigPath,List.of());
    }

    synchronized void bindWorkerConfiguration(String leaseId,Path workerConfigPath,
                                               List<Map<String,Object>> dependencies) throws IOException {
        LeaseRecord record=leases.get(leaseId);
        if(record==null)throw new IOException("The immutable source lease is unavailable");
        try {
            byte[] emptyPlans=att.template.FileExpressionResolver.FileExpressionSnapshot.empty(Path.of(record.packageRoot)).serialize();
            bindWorkerConfiguration(leaseId,workerConfigPath,dependencies,emptyPlans);
        } catch(IOException failure) { throw failure; }
        catch(Exception failure) { throw new IOException("Unable to encode the empty File-expression plan snapshot",failure); }
    }

    synchronized void bindWorkerConfiguration(String leaseId,Path workerConfigPath,
                                               List<Map<String,Object>> dependencies,byte[] compiledFilePlans) throws IOException {
        LeaseRecord record=leases.get(leaseId);
        if(record==null)throw new IOException("The immutable source lease is unavailable");
        if(compiledFilePlans==null||compiledFilePlans.length==0||compiledFilePlans.length>128L*1024L*1024L)
            throw new IOException("The approved File-expression plans are unavailable or oversized");
        Path root=Path.of(record.configRoot).toRealPath(),file=workerConfigPath.toRealPath();
        if(!file.startsWith(root)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(workerConfigPath))
            throw new StaleSourceException();
        record.configPath=root.relativize(file).toString().replace(java.io.File.separatorChar,'/');
        if(!record.logicalPaths.contains(record.configPath)) {
            List<String> paths=new ArrayList<>(record.logicalPaths);paths.add(record.configPath);record.logicalPaths=List.copyOf(paths);
        }
        record.dependencies=dependencies==null?List.of():List.copyOf(dependencies);
        record.compiledFilePlansSha256=digest(compiledFilePlans);
        createSnapshotArchive(record,compiledFilePlans);
        persist(record);
    }

    synchronized SourceLease retainOwner(String ownerReference,String newOwner) throws IOException {
        for(LeaseRecord record:leases.values())if(record.owners!=null&&record.owners.contains(ownerReference))
            return new LeaseHandle(record.id,ownerReference).retain(newOwner);
        throw new StaleSourceException();
    }

    synchronized SourceSnapshot snapshotForOwner(String ownerReference) throws IOException {
        for(LeaseRecord record:leases.values())if(record.owners!=null&&record.owners.contains(ownerReference)) {
            if(!snapshotAvailable(record))throw new StaleSourceException();
            Path root=materializeSnapshot(record);
            Path packageRoot=root.resolve("package");
            Path configRoot=root.resolve("config");
            RevisionSet revisions=revisionSet(record.revisions);
            if(revisions.businessSource==null||revisions.configSource==null)throw new StaleSourceException();
            Path workerConfig=record.configPath==null?null:configRoot.resolve(record.configPath).normalize();
            if(workerConfig==null||!workerConfig.startsWith(configRoot)||!Files.isRegularFile(workerConfig,LinkOption.NOFOLLOW_LINKS)
                    ||Files.isSymbolicLink(workerConfig))throw new StaleSourceException();
            return new SourceSnapshot(record.id,revisions,packageRoot,configRoot,workerConfig,
                    Path.of(record.snapshotArchivePath),record.snapshotArchiveSha256,record.configPath,
                    record.dependencies,record.logicalPaths,readCompiledFilePlans(record));
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

    synchronized boolean verifyDispatch(String leaseId, String ownerReference, String archivePath,
                                        String archiveSha256,String configRelativePath) {
        LeaseRecord record = leases.get(leaseId);
        return record != null && record.owners != null && record.owners.contains(ownerReference)
                && record.configPath != null && java.util.Objects.equals(record.snapshotArchivePath,archivePath)
                && java.util.Objects.equals(record.snapshotArchiveSha256,archiveSha256)
                && java.util.Objects.equals(record.configPath,configRelativePath)
                && snapshotAvailable(record);
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
                if (record.logicalPaths == null || !record.logicalPaths.contains(normalized))
                    throw new IOException("The source path is outside the approved closure");
                RevisionSet revisions = revisionSet(record.revisions);
                Path verified = verifiedArchiveCopy(record);
                try (ZipFile archive = new ZipFile(verified.toFile())) {
                    ZipEntry entry = archive.getEntry("package/" + normalized);
                    RevisionIdentity identity = revisions.businessSource;
                    if (entry == null) { entry = archive.getEntry("config/" + normalized); identity = revisions.configSource; }
                    if (entry == null || entry.isDirectory()) throw new IOException("The source path is unavailable in the retained revision");
                    if (entry.getSize() > MAX_READ_BYTES) throw new IOException("The retained source file exceeds the read limit");
                    byte[] bytes;
                    try (InputStream input = archive.getInputStream(entry); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192]; int count; long total = 0;
                        while ((count = input.read(buffer)) >= 0) {
                            total += count;
                            if (total > MAX_READ_BYTES) throw new IOException("The retained source file exceeds the read limit");
                            output.write(buffer, 0, count);
                        }
                        bytes = output.toByteArray();
                    }
                    return new VersionedSourceBytes(identity, normalized, digest(bytes), bytes);
                } finally { Files.deleteIfExists(verified); }
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

    private boolean snapshotAvailable(LeaseRecord record) {
        if (record.configPath == null || record.logicalPaths == null || record.logicalPaths.isEmpty()
                || record.snapshotArchiveSha256 == null || record.packageTreeDigest == null || record.configTreeDigest == null
                || record.packageExecutableDigest == null || record.configExecutableDigest == null
                || record.compiledFilePlansSha256 == null || !record.compiledFilePlansSha256.matches("[a-f0-9]{64}"))
            return false;
        Path verified = null;
        try {
            verified = verifiedArchiveCopy(record);
            try (ZipFile archive = new ZipFile(verified.toFile())) {
                JsonNode manifest = readSnapshotManifest(archive);
                if (!snapshotManifestMatches(record, manifest)) return false;
                String configEntry = "config/" + record.configPath;
                if (archive.getEntry(configEntry) == null) return false;
                ZipEntry plans=archive.getEntry("file-plans.bin");
                if(plans==null||plans.isDirectory()||plans.getSize()<=0||plans.getSize()>128L*1024L*1024L
                        ||!record.compiledFilePlansSha256.equals(digestEntry(archive,plans)))return false;
                return true;
            }
        } catch (Exception unavailable) { return false; }
        finally { if (verified != null) try { Files.deleteIfExists(verified); } catch (IOException ignored) { } }
    }

    private Path verifiedArchiveCopy(LeaseRecord record) throws IOException {
        Path expected = snapshotsDirectory.resolve(record.id + ".zip").toAbsolutePath().normalize();
        if (record.snapshotArchivePath == null || !expected.toString().equals(record.snapshotArchivePath))
            throw new StaleSourceException();
        Path source = expected;
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.size(source) > MAX_SNAPSHOT_BYTES) throw new StaleSourceException();
        Path copy = snapshotsDirectory.resolve(".verify-" + UUID.randomUUID().toString().replace("-", "") + ".zip");
        try {
            Files.copy(source, copy, StandardCopyOption.COPY_ATTRIBUTES);
            try { Files.setPosixFilePermissions(copy, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) { }
            if (!record.snapshotArchiveSha256.equals(digestFile(copy))) throw new StaleSourceException();
            return copy;
        } catch (Exception failure) {
            Files.deleteIfExists(copy);
            if (failure instanceof IOException io) throw io;
            throw new IOException("The immutable source snapshot is unavailable", failure);
        }
    }

    private Path materializeSnapshot(LeaseRecord record) throws IOException {
        Path destination = materializedDirectory.resolve(record.id);
        if (materializedMatches(record, destination)) return destination;
        deleteTree(destination);
        Path temporary = materializedDirectory.resolve("." + record.id + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
        createPrivateDirectory(temporary);
        Path verified = null;
        try {
            verified = verifiedArchiveCopy(record);
            try (ZipFile archive = new ZipFile(verified.toFile())) {
                JsonNode manifest = readSnapshotManifest(archive);
                if (!snapshotManifestMatches(record, manifest)) throw new StaleSourceException();
                extractSnapshotTree(archive, temporary, "package", manifest.path("packageSymlinks"),manifest.path("packageExecutableFiles"));
                extractSnapshotTree(archive, temporary, "config", manifest.path("configSymlinks"),manifest.path("configExecutableFiles"));
                Path packageRoot = temporary.resolve("package"), configRoot = temporary.resolve("config");
                if (!record.packageTreeDigest.equals(SourceRevisionStore.treeDigest(packageRoot))
                        || !record.configTreeDigest.equals(SourceRevisionStore.treeDigest(configRoot))
                        || !record.packageExecutableDigest.equals(executableDigestTree(packageRoot))
                        || !record.configExecutableDigest.equals(executableDigestTree(configRoot))) throw new StaleSourceException();
                Path config = configRoot.resolve(record.configPath).normalize();
                if (!config.startsWith(configRoot) || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(config)) throw new StaleSourceException();
            }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination); }
            forceDirectory(materializedDirectory);
            return destination;
        } catch (IOException failure) {
            deleteTree(temporary);
            throw failure;
        } catch (Exception failure) {
            deleteTree(temporary);
            throw new IOException("The immutable source snapshot could not be restored", failure);
        } finally { if (verified != null) Files.deleteIfExists(verified); }
    }

    private boolean materializedMatches(LeaseRecord record, Path destination) {
        try {
            Path packageRoot = destination.resolve("package"), configRoot = destination.resolve("config");
            Path config = configRoot.resolve(record.configPath).normalize();
            return !Files.isSymbolicLink(destination) && Files.isDirectory(packageRoot, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(configRoot, LinkOption.NOFOLLOW_LINKS)
                    && record.packageTreeDigest.equals(SourceRevisionStore.treeDigest(packageRoot))
                    && record.configTreeDigest.equals(SourceRevisionStore.treeDigest(configRoot))
                    && record.packageExecutableDigest.equals(executableDigestTree(packageRoot))
                    && record.configExecutableDigest.equals(executableDigestTree(configRoot))
                    && config.startsWith(configRoot) && Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(config);
        } catch (Exception unavailable) { return false; }
    }

    private JsonNode readSnapshotManifest(ZipFile archive) throws IOException {
        ZipEntry entry = archive.getEntry("manifest.json");
        if (entry == null || entry.isDirectory() || entry.getSize() > 1024 * 1024) throw new StaleSourceException();
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new StaleSourceException();
            JsonNode manifest = ServerRuntime.JSON.readTree(bytes);
            if (manifest == null || !SNAPSHOT_VERSION.equals(manifest.path("schemaVersion").asText())) throw new StaleSourceException();
            return manifest;
        }
    }

    private boolean snapshotManifestMatches(LeaseRecord record, JsonNode manifest) {
        RevisionSet revisions;
        try { revisions = revisionSet(record.revisions); } catch (RuntimeException invalid) { return false; }
        return record.packageId.equals(manifest.path("packageId").asText())
                && record.configPath.equals(manifest.path("configurationPath").asText())
                && java.util.Objects.equals(record.compiledFilePlansSha256,manifest.path("compiledFilePlansSha256").asText(null))
                && record.packageTreeDigest.equals(manifest.path("packageTreeDigest").asText())
                && record.configTreeDigest.equals(manifest.path("configTreeDigest").asText())
                && java.util.Objects.equals(record.packageExecutableDigest,manifest.path("packageExecutableDigest").asText(null))
                && java.util.Objects.equals(record.configExecutableDigest,manifest.path("configExecutableDigest").asText(null))
                && java.util.Objects.equals(record.packageExecutableDigest,executableDigestManifest(manifest.path("packageExecutableFiles")))
                && java.util.Objects.equals(record.configExecutableDigest,executableDigestManifest(manifest.path("configExecutableFiles")))
                && revisions.businessSource.revision.equals(manifest.path("packageRevision").asText())
                && revisions.configSource.revision.equals(manifest.path("configRevision").asText());
    }

    private void extractSnapshotTree(ZipFile archive, Path destination, String tree, JsonNode symlinks,
                                    JsonNode executableFiles) throws IOException {
        Path root = destination.resolve(tree);
        createPrivateDirectory(root);
        java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
        long total = 0; Set<String> seen = new LinkedHashSet<>();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(tree + "/")) continue;
            String relative = name.substring(tree.length() + 1);
            if (relative.isEmpty()) continue;
            String safe = safeLogicalPath(relative);
            if (!seen.add(safe)) throw new StaleSourceException();
            Path target = root.resolve(safe).normalize();
            if (!target.startsWith(root)) throw new StaleSourceException();
            if (entry.isDirectory()) { createPrivateDirectory(target); continue; }
            if (entry.getSize() < 0 || entry.getSize() > MAX_SNAPSHOT_BYTES) throw new StaleSourceException();
            total += entry.getSize();
            if (total > MAX_SNAPSHOT_BYTES) throw new StaleSourceException();
            createPrivateDirectory(target.getParent());
            try (InputStream input = archive.getInputStream(entry); OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192]; int count; long copied = 0;
                while ((count = input.read(buffer)) >= 0) {
                    copied += count;
                    if (copied > MAX_SNAPSHOT_BYTES) throw new StaleSourceException();
                    output.write(buffer, 0, count);
                }
                if (entry.getSize() != copied) throw new StaleSourceException();
            }
        }
        restoreExecutableFiles(root, executableFiles);
        if (symlinks != null && symlinks.isObject()) {
            var fields = symlinks.fields();
            while (fields.hasNext()) {
                var link = fields.next();
                String relative = safeLogicalPath(link.getKey());
                String targetText = link.getValue().asText();
                Path target = Path.of(targetText);
                if (target.isAbsolute() || target.toString().isBlank()) throw new StaleSourceException();
                Path linkPath = root.resolve(relative).normalize(), resolved = linkPath.getParent().resolve(target).normalize();
                if (!linkPath.startsWith(root) || !resolved.startsWith(root) || Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) throw new StaleSourceException();
                createPrivateDirectory(linkPath.getParent());
                Files.createSymbolicLink(linkPath, target);
            }
        }
    }

    private static final class CapturedEntry {
        final String path;
        final String type;
        final String fingerprint;
        final boolean executable;
        CapturedEntry(String path, String type, String fingerprint,boolean executable) {
            this.path=path;this.type=type;this.fingerprint=fingerprint;this.executable=executable;
        }
    }

    private byte[] readCompiledFilePlans(LeaseRecord record) throws IOException {
        Path verified=verifiedArchiveCopy(record);
        try(ZipFile archive=new ZipFile(verified.toFile())) {
            ZipEntry entry=archive.getEntry("file-plans.bin");
            if(entry==null||entry.isDirectory()||entry.getSize()<=0||entry.getSize()>128L*1024L*1024L)
                throw new StaleSourceException();
            try(InputStream input=archive.getInputStream(entry);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int read;long total=0;
                while((read=input.read(buffer))>=0) {
                    total+=read;if(total>128L*1024L*1024L)throw new StaleSourceException();
                    output.write(buffer,0,read);
                }
                byte[] bytes=output.toByteArray();
                if(!record.compiledFilePlansSha256.equals(digest(bytes)))throw new StaleSourceException();
                return bytes;
            }
        } finally { Files.deleteIfExists(verified); }
    }

    private static String digestEntry(ZipFile archive,ZipEntry entry) throws IOException {
        MessageDigest digest=sha256();
        try(InputStream input=archive.getInputStream(entry)) {
            byte[] buffer=new byte[8192];int read;long total=0;
            while((read=input.read(buffer))>=0) {
                total+=read;if(total>128L*1024L*1024L)throw new StaleSourceException();
                digest.update(buffer,0,read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private void createSnapshotArchive(LeaseRecord record,byte[] compiledFilePlans) throws IOException {
        Path packageRoot=Path.of(record.packageRoot),configRoot=Path.of(record.configRoot);
        try {
            String packageBefore=SourceRevisionStore.treeDigest(packageRoot),configBefore=SourceRevisionStore.treeDigest(configRoot);
            String packageModesBefore=executableDigestTree(packageRoot),configModesBefore=executableDigestTree(configRoot);
            List<CapturedEntry> packageEntries=new ArrayList<>(),configEntries=new ArrayList<>();
            Map<String,String> packageLinks=new LinkedHashMap<>(),configLinks=new LinkedHashMap<>();
            long[] total={0};
            Path archive=snapshotsDirectory.resolve(record.id+".zip");
            if(Files.isSymbolicLink(archive))throw new IOException("The source snapshot archive path is unsafe");
            Path temporary=snapshotsDirectory.resolve("."+record.id+".tmp-"+UUID.randomUUID().toString().replace("-",""));
            try {
                try(OutputStream file=Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                    ZipOutputStream zip=new ZipOutputStream(file,StandardCharsets.UTF_8)) {
                    captureTree(zip,packageRoot,"package",packageEntries,packageLinks,total);
                    captureTree(zip,configRoot,"config",configEntries,configLinks,total);
                    String packageDigest=treeDigest(packageEntries),configDigest=treeDigest(configEntries);
                    String packageModes=executableDigestEntries(packageEntries),configModes=executableDigestEntries(configEntries);
                    if(!packageBefore.equals(packageDigest)||!configBefore.equals(configDigest)
                            ||!packageBefore.equals(SourceRevisionStore.treeDigest(packageRoot))
                            ||!configBefore.equals(SourceRevisionStore.treeDigest(configRoot))
                            ||!packageModesBefore.equals(packageModes)||!configModesBefore.equals(configModes)
                            ||!packageModes.equals(executableDigestTree(packageRoot))
                            ||!configModes.equals(executableDigestTree(configRoot)))
                        throw new StaleSourceException();
                    if(compiledFilePlans!=null)try {
                        att.template.FileExpressionResolver.FileExpressionSnapshot.restore(packageRoot,compiledFilePlans);
                    } catch(Exception stalePlans) { throw new StaleSourceException(); }
                    if(compiledFilePlans!=null) {
                        if(compiledFilePlans.length==0||compiledFilePlans.length>128L*1024L*1024L)
                            throw new IOException("The compiled File-expression snapshot is oversized");
                        ZipEntry plans=new ZipEntry("file-plans.bin");plans.setTime(0L);zip.putNextEntry(plans);
                        zip.write(compiledFilePlans);zip.closeEntry();
                    }
                    Map<String,Object> manifest=new LinkedHashMap<>();
                    manifest.put("schemaVersion",SNAPSHOT_VERSION);manifest.put("packageId",record.packageId);
                    RevisionSet revisions=revisionSet(record.revisions);
                    manifest.put("packageRevision",revisions.businessSource.revision);manifest.put("configRevision",revisions.configSource.revision);
                    manifest.put("configurationPath",record.configPath);manifest.put("packageTreeDigest",packageDigest);
                    manifest.put("configTreeDigest",configDigest);manifest.put("logicalPaths",record.logicalPaths);
                    manifest.put("packageExecutableFiles",executablePaths(packageEntries));
                    manifest.put("configExecutableFiles",executablePaths(configEntries));
                    manifest.put("packageExecutableDigest",packageModes);manifest.put("configExecutableDigest",configModes);
                    manifest.put("dependencies",record.dependencies==null?List.of():record.dependencies);
                    manifest.put("compiledFilePlansSha256",compiledFilePlans==null?null:digest(compiledFilePlans));
                    manifest.put("packageSymlinks",packageLinks);manifest.put("configSymlinks",configLinks);
                    ZipEntry manifestEntry=new ZipEntry("manifest.json");manifestEntry.setTime(0L);zip.putNextEntry(manifestEntry);
                    zip.write(ServerRuntime.JSON.writeValueAsBytes(manifest));zip.closeEntry();
                    zip.finish();
                    record.packageTreeDigest=packageDigest;record.configTreeDigest=configDigest;
                    record.packageExecutableDigest=packageModes;record.configExecutableDigest=configModes;
                }
                if(Files.size(temporary)>MAX_SNAPSHOT_BYTES)throw new IOException("The immutable source snapshot exceeds the size limit");
                try { Files.setPosixFilePermissions(temporary,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)); }
                catch(UnsupportedOperationException ignored) { }
                try { Files.move(temporary,archive,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary,archive,StandardCopyOption.REPLACE_EXISTING); }
                record.snapshotArchivePath=archive.toString();record.snapshotArchiveSha256=digestFile(archive);
                forceDirectory(snapshotsDirectory);
            } catch(Exception failure) {
                Files.deleteIfExists(temporary);
                if(failure instanceof IOException io)throw io;
                throw new IOException("Unable to capture immutable source bytes",failure);
            }
        } catch(Exception failure) {
            if(failure instanceof IOException io)throw io;
            throw new IOException("Unable to capture immutable source bytes",failure);
        }
    }

    private static void captureTree(ZipOutputStream zip,Path root,String prefix,List<CapturedEntry> captured,
                                    Map<String,String> symlinks,long[] total) throws IOException {
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new StaleSourceException();
        List<Path> entries=new ArrayList<>();
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                if(attrs.isSymbolicLink()||Files.isSymbolicLink(file)||attrs.isRegularFile()){entries.add(file);return FileVisitResult.CONTINUE;}
                throw new IOException("Source snapshot contains an unsupported file");
            }
        });
        entries.sort(Comparator.comparing(path->root.relativize(path).toString().replace(java.io.File.separatorChar,'/')));
        for(Path file:entries) {
            String relative=safeLogicalPath(root.relativize(file).toString().replace(java.io.File.separatorChar,'/'));
            if(Files.isSymbolicLink(file)) {
                Path target=Files.readSymbolicLink(file);
                if(target.isAbsolute()||!file.getParent().resolve(target).normalize().startsWith(root))throw new StaleSourceException();
                String targetText=target.toString().replace(java.io.File.separatorChar,'/');
                symlinks.put(relative,targetText);captured.add(new CapturedEntry(relative,"L",targetText,false));
                continue;
            }
            boolean executable=Files.isExecutable(file);
            long declared=Files.size(file);total[0]+=declared;
            if(declared>MAX_SNAPSHOT_BYTES||total[0]>MAX_SNAPSHOT_BYTES)throw new IOException("The immutable source snapshot exceeds the size limit");
            MessageDigest content=sha256();long copied=0;
            ZipEntry entry=new ZipEntry(prefix+"/"+relative);entry.setTime(0L);zip.putNextEntry(entry);
            try(InputStream input=Files.newInputStream(file)) {
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))>=0) {
                    copied+=count;if(copied>MAX_SNAPSHOT_BYTES||total[0]-declared+copied>MAX_SNAPSHOT_BYTES)throw new IOException("The immutable source snapshot exceeds the size limit");
                    content.update(buffer,0,count);zip.write(buffer,0,count);
                }
            }
            zip.closeEntry();
            if(copied!=declared||executable!=Files.isExecutable(file))throw new StaleSourceException();
            total[0]=total[0]-declared+copied;
            captured.add(new CapturedEntry(relative,"F",java.util.HexFormat.of().formatHex(content.digest()),executable));
        }
    }

    private static String treeDigest(List<CapturedEntry> entries) {
        MessageDigest digest=sha256();
        for(CapturedEntry entry:entries) {
            digest.update(entry.path.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            digest.update(entry.type.getBytes(StandardCharsets.UTF_8));
            digest.update("F".equals(entry.type)?java.util.HexFormat.of().parseHex(entry.fingerprint):entry.fingerprint.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0xff);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> executablePaths(List<CapturedEntry> entries) {
        List<String> paths=new ArrayList<>();
        for(CapturedEntry entry:entries)if("F".equals(entry.type)&&entry.executable)paths.add(entry.path);
        Collections.sort(paths);return List.copyOf(paths);
    }

    private static String executableDigestEntries(List<CapturedEntry> entries) {
        return executableDigestPaths(executablePaths(entries));
    }

    private static String executableDigestTree(Path root) throws IOException {
        List<String> paths=new ArrayList<>();
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {
                if(attributes.isRegularFile()&&!attributes.isSymbolicLink()&&!Files.isSymbolicLink(file)&&Files.isExecutable(file))
                    paths.add(root.relativize(file).toString().replace(java.io.File.separatorChar,'/'));
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(paths);return executableDigestPaths(paths);
    }

    private static String executableDigestManifest(JsonNode files) {
        if(files==null||!files.isArray())return null;
        List<String> paths=new ArrayList<>();Set<String> seen=new LinkedHashSet<>();
        for(JsonNode value:files) {
            String path=value.isTextual()?value.asText():null;
            if(path==null)return null;
            try { path=safeLogicalPath(path); } catch(IOException invalid) { return null; }
            if(!seen.add(path))return null;paths.add(path);
        }
        List<String> sorted=new ArrayList<>(paths);Collections.sort(sorted);
        if(!sorted.equals(paths))return null;
        return executableDigestPaths(paths);
    }

    private static String executableDigestPaths(List<String> paths) {
        MessageDigest digest=sha256();
        for(String path:paths){digest.update(path.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void restoreExecutableFiles(Path root,JsonNode files) throws IOException {
        if(files==null||!files.isArray())throw new StaleSourceException();
        Set<String> seen=new LinkedHashSet<>();
        for(JsonNode value:files) {
            String relative=value.isTextual()?safeLogicalPath(value.asText()):null;
            if(relative==null||!seen.add(relative))throw new StaleSourceException();
            Path target=root.resolve(relative).normalize();
            if(!target.startsWith(root)||Files.isSymbolicLink(target)||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
                throw new StaleSourceException();
            try {
                Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(target,LinkOption.NOFOLLOW_LINKS);
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(target,permissions);
            } catch(UnsupportedOperationException unsupported) {
                if(!target.toFile().setExecutable(true,false))throw new StaleSourceException();
            }
            if(!Files.isExecutable(target))throw new StaleSourceException();
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(Exception unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static String digestFile(Path path) throws IOException {
        MessageDigest digest=sha256();
        try(InputStream input=Files.newInputStream(path)) {
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))>=0)digest.update(buffer,0,count);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private void deleteSnapshotFiles(LeaseRecord record) {
        if(record==null)return;
        try { Files.deleteIfExists(snapshotsDirectory.resolve(record.id+".zip")); } catch(IOException ignored) { }
        deleteTree(materializedDirectory.resolve(record.id));
    }

    private static void deleteTree(Path root) {
        if(root==null||!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        try { Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException { Files.deleteIfExists(file);return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException { if(error!=null)throw error;Files.deleteIfExists(dir);return FileVisitResult.CONTINUE; }
        }); } catch(IOException ignored) { }
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
