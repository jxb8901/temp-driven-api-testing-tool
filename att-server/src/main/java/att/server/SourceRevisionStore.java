package att.server;

import att.config.YamlSupport;
import att.template.FileExpressionResolver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
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
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Stores complete immutable source trees and switches readers through one atomic pointer file. */
final class SourceRevisionStore {
    private static final String IDENTITY_VERSION = "att-source-revision-store-v1";
    private static final String POINTER_VERSION = "att-source-revision-pointer-v2";
    private static final String LEGACY_POINTER_VERSION = "att-source-revision-pointer-v1";
    private static final long MAX_TREE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_TREE_FILES = 50000;
    private static final int MAX_TREE_ENTRIES = MAX_TREE_FILES * 4;
    private static final int MAX_CENTRAL_REFERENCE_COUNT = 4096;
    private static final int MAX_CENTRAL_DESCRIPTOR_YAML_BYTES = 3_000_000;
    private static final Set<String> CENTRAL_HELPER_LIST_FIELDS = Set.of(
            "toolgroups", "dbhelpers", "mqhelpers", "httphelpers", "sshhelpers");
    private static final Map<Path, ReentrantLock> JVM_WRITER_LOCKS = new ConcurrentHashMap<>();

    static final class Revision {
        final String id;
        final String sourceRevision;
        final String transactionId;
        final String treeDigest;
        final boolean sealed;
        final Path root;
        Revision(String id, String sourceRevision, String transactionId, String treeDigest, boolean sealed, Path root) {
            this.id = id;
            this.sourceRevision = sourceRevision;
            this.transactionId = transactionId;
            this.treeDigest = treeDigest;
            this.sealed = sealed;
            this.root = root;
        }
    }

    static final class WriterLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final ReentrantLock localLock;
        WriterLock(FileChannel channel, FileLock lock, ReentrantLock localLock) {
            this.channel = channel; this.lock = lock; this.localLock = localLock;
        }
        @Override public void close() throws IOException {
            try { lock.release(); } finally {
                try { channel.close(); } finally { localLock.unlock(); }
            }
        }
    }

    private static final class Registration {
        final String domain, scopeId, configuredConfigPath;
        final Path sourceRoot, store, revisions, executions, active, lockFile;
        Registration(String domain, String scopeId, Path sourceRoot, Path store, String configuredConfigPath) {
            this.domain = domain; this.scopeId = scopeId; this.sourceRoot = sourceRoot; this.store = store;
            this.configuredConfigPath = configuredConfigPath;
            this.revisions = store.resolve("revisions"); this.executions = store.resolve("executions");
            this.active = store.resolve("active");
            this.lockFile = store.resolve("writer.lock");
        }
    }

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<String, Path> rootsByScope = new ConcurrentHashMap<>();
    private final Map<String, String> configuredConfigPaths = new ConcurrentHashMap<>();

    synchronized void register(String domain, String scopeId, Path root) throws Exception {
        register(domain, scopeId, root, null);
    }

    synchronized void register(String domain, String scopeId, Path root, Path configuredConfigFile) throws Exception {
        Path sourceRoot = canonicalRoot(root);
        String scopeKey = scopeKey(domain, scopeId);
        String configPath = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain)
                ? configuredConfigPath(sourceRoot, configuredConfigFile, configuredConfigPaths.get(scopeKey)) : null;
        Path previousRoot = rootsByScope.get(scopeKey);
        if (previousRoot != null && previousRoot.equals(sourceRoot)) {
            Registration existing = registrations.get(scopeKey);
            if (configPath != null && existing != null && !configPath.equals(existing.configuredConfigPath))
                throw new IOException("Central Runtime Config path changed after source registration");
            return;
        }
        Registration registration = registration(domain, scopeId, sourceRoot, configPath);
        createStoreDirectories(registration);
        try (WriterLock ignored = acquire(registration)) {
            verifyIdentity(registration);
            if (!Files.exists(registration.active, LinkOption.NOFOLLOW_LINKS)) {
                String id = nextId();
                Path initial = registration.revisions.resolve(id);
                copyTree(domain, sourceRoot, initial, configPath, true);
                String sourceDigest = treeDigestConfigured(domain, sourceRoot, configPath);
                if (!sourceDigest.equals(treeDigest(domain, initial))) {
                    deleteTree(initial);
                    throw new IOException("Source changed while its initial revision was being captured");
                }
                restoreTreePermissions(initial, sourceRoot);
                writeSharedAtomic(registration.active,pointer(id,null,null,sourceDigest),registration.sourceRoot);
            }
            Revision active = readActive(registration);
            if (!active.sealed) writeSharedAtomic(registration.active,
                    pointer(active.id, active.sourceRevision, active.transactionId, active.treeDigest), registration.sourceRoot);
        }
        registrations.put(scopeKey, registration);
        rootsByScope.put(scopeKey, sourceRoot);
        if (configPath != null) configuredConfigPaths.put(scopeKey, configPath);
    }

    Revision pin(String domain, String scopeId, Path root) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        ReentrantLock local = localWriterLock(registration);
        if (local.isHeldByCurrentThread()) return readActive(registration);
        try (WriterLock ignored = acquire(registration)) {
            return refreshConfiguredSource(registration, false);
        }
    }

    Revision current(String domain, String scopeId, Path root) throws Exception {
        return pin(domain, scopeId, root);
    }

    /**
     * Returns a verified execution-only copy of the active revision. Publishers switch
     * revision pointers and never write this tree, so a delayed Worker cannot observe a
     * later publication or a same-owner edit to the retained publication tree.
     */
    Path executionPin(String domain, String scopeId, Path root) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        try (WriterLock ignored = acquire(registration)) {
            Revision active = refreshConfiguredSource(registration, false);
            Path target = registration.executions.resolve(active.id);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                        || !active.treeDigest.equals(treeDigest(domain, target)))
                    throw new IOException("Execution source snapshot failed its integrity check");
                return target;
            }
            Path temporary = registration.executions.resolve(".copy-" + UUID.randomUUID());
            try {
                copyTree(domain, active.root, temporary);
                if (!active.treeDigest.equals(treeDigest(domain, temporary)))
                    throw new IOException("Execution source snapshot failed its integrity check");
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                restoreTreePermissions(target, active.root);
                forceDirectory(registration.executions);
                return target;
            } catch (Exception failure) {
                try { deleteTree(temporary); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    try { deleteTree(target); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    boolean ownsExecutionRevision(String domain, String scopeId, Path configuredRoot, Path candidate) {
        try {
            Registration registration = lookup(domain, scopeId, configuredRoot);
            Path absolute = candidate.toAbsolutePath().normalize();
            return absolute.getParent() != null && absolute.getParent().equals(registration.executions)
                    && absolute.getFileName().toString().matches("R[A-F0-9]{32}")
                    && Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absolute);
        } catch (Exception ignored) { return false; }
    }

    WriterLock lock(String domain, String scopeId, Path root) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        return acquire(registration);
    }

    Revision refreshConfiguredSource(String domain, String scopeId, Path root, boolean verifyContent) throws Exception {
        return refreshConfiguredSource(lookup(domain, scopeId, root), verifyContent);
    }

    void exportActiveToConfiguredRoot(String domain, String scopeId, Path root, String id) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        Revision active = readActive(registration);
        if (!active.id.equals(id)) throw new IOException("Active source revision changed during configured-source export");
        if (ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain))
            exportCentralTree(active.root, registration.sourceRoot, registration.configuredConfigPath);
        else exportTree(active.root, registration.sourceRoot);
        if (!treeDigest(domain, active.root).equals(treeDigest(domain, registration.sourceRoot)))
            throw new IOException("Configured source export failed its integrity check");
    }

    Revision readActive(String domain, String scopeId, Path root) throws Exception {
        return readActive(lookup(domain, scopeId, root));
    }

    void switchTo(String domain, String scopeId, Path root, String id, String sourceRevision, String transactionId) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        Path target = checkedRevision(registration, id);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source revision is unavailable");
        writeSharedAtomic(registration.active,pointer(id,sourceRevision,transactionId,treeDigest(domain,target)),registration.sourceRoot);
    }

    void clearTransactionId(String domain, String scopeId, Path root, String id, String transactionId) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        Revision active = readActive(registration);
        if (!active.id.equals(id))
            throw new IOException("Active source transaction changed before its journal was cleared");
        if (active.transactionId == null) return;
        if (!active.transactionId.equals(transactionId))
            throw new IOException("Active source transaction changed before its journal was cleared");
        writeSharedAtomic(registration.active, pointer(active.id, active.sourceRevision, null, active.treeDigest), registration.sourceRoot);
    }

    Path createCandidate(String domain, String scopeId, Path root, Revision from, String id,
                         Map<String, SourceFileTransaction.Write> writes,
                         SourceFileTransaction.FaultInjector faultInjector) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        if (id == null || !id.matches("R[A-F0-9]{32}")) throw new IOException("Prepared source revision identity is invalid");
        Path candidate = registration.revisions.resolve(id);
        copyTree(domain, from.root, candidate);
        int applied = 0;
        for (SourceFileTransaction.Write write : writes.values()) {
            apply(candidate, write);
            applied++;
            if (faultInjector != null) faultInjector.afterApply(applied);
        }
        if (ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain)) pruneCentralSnapshot(candidate);
        return candidate;
    }

    void freezeCandidate(Path candidate, Path originalRoot) throws IOException { restoreTreePermissions(candidate, originalRoot); }

    String activeId(String domain, String scopeId, Path root) throws Exception {
        return readActive(domain, scopeId, root).id;
    }

    boolean revisionTreeMatches(String domain, String scopeId, Path root, String id, String expectedTreeDigest) {
        try { return expectedTreeDigest.equals(treeDigest(domain, checkedRevision(lookup(domain, scopeId, root), id))); }
        catch (Exception ignored) { return false; }
    }

    void deleteRevision(String domain, String scopeId, Path root, String id) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        if (id.equals(readActive(registration).id)) return;
        deleteTree(checkedRevision(registration, id));
        forceDirectory(registration.revisions);
    }

    Path revisionRoot(String domain,String scopeId,Path root,String id) throws Exception {
        Registration registration=lookup(domain,scopeId,root);
        Path revision=checkedRevision(registration,id);
        if(!Files.isDirectory(revision,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(revision))
            throw new IOException("Source revision is unavailable");
        return revision;
    }

    Path registeredRoot(String domain, String scopeId) { return rootsByScope.get(scopeKey(domain, scopeId)); }

    boolean ownsRevision(String domain, String scopeId, Path configuredRoot, Path candidate) {
        try {
            Registration registration = lookup(domain, scopeId, configuredRoot);
            Path absolute = candidate.toAbsolutePath().normalize();
            return absolute.getParent() != null && absolute.getParent().equals(registration.revisions)
                    && absolute.getFileName().toString().matches("R[A-F0-9]{32}")
                    && Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absolute);
        } catch (Exception ignored) { return false; }
    }

    Path storePath(String domain, String scopeId, Path root) throws Exception {
        return lookup(domain, scopeId, root).store;
    }

    private synchronized Registration lookup(String domain, String scopeId, Path root) throws Exception {
        String scopeKey = scopeKey(domain, scopeId);
        Path requestedRoot = canonicalRoot(root);
        Path sourceRoot = rootsByScope.get(scopeKey);
        if (sourceRoot == null || !sourceRoot.equals(requestedRoot)) {
            register(domain, scopeId, requestedRoot);
            return registrations.get(scopeKey);
        }
        Registration registration = registrations.get(scopeKey);
        if (registration == null) {
            String configPath = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain)
                    ? configuredConfigPaths.getOrDefault(scopeKey, "config/config.yaml") : null;
            registration = registration(domain, scopeId, sourceRoot, configPath);
            createStoreDirectories(registration);
            verifyIdentity(registration);
            registrations.put(scopeKey, registration);
        }
        return registration;
    }

    private Registration registration(String domain, String scopeId, Path sourceRoot,
                                      String configuredConfigPath) throws IOException {
        Path parent = sourceRoot.getParent();
        if (parent == null) throw new IOException("Source root has no parent directory");
        String capturePolicy = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain)
                ? "central-approved-config-v1" : "package-source-v1";
        String suffix = digest((domain + "\n" + scopeId + "\n" + sourceRoot + "\n" + capturePolicy + "\n"
                + String.valueOf(configuredConfigPath))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 20);
        String name = "." + sourceRoot.getFileName() + ".att-source-revisions-" + suffix;
        return new Registration(domain, scopeId, sourceRoot, parent.resolve(name), configuredConfigPath);
    }

    private static String configuredConfigPath(Path sourceRoot, Path configuredFile, String registeredPath) throws IOException {
        if (configuredFile == null) return registeredPath == null ? "config/config.yaml" : registeredPath;
        Path requested = configuredFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(requested) || !Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Configured central Runtime Config file is outside its source root");
        Path absolute = requested.toRealPath();
        if (!absolute.startsWith(sourceRoot))
            throw new IOException("Configured central Runtime Config file is outside its source root");
        String relative = relativePath(sourceRoot, absolute);
        return safeRelative(relative);
    }

    private void createStoreDirectories(Registration registration) throws Exception {
        Path parent = registration.store.getParent();
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Source revision storage parent is unsafe");
        if (Files.exists(registration.store, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(registration.store) || !Files.isDirectory(registration.store, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Source revision storage is unsafe");
        if (Files.isSymbolicLink(registration.revisions)) throw new IOException("Source revision storage is unsafe");
        Files.createDirectories(registration.revisions);
        if (Files.isSymbolicLink(registration.executions)) throw new IOException("Execution source storage is unsafe");
        Files.createDirectories(registration.executions);
        setStorePermissions(registration.store, registration.sourceRoot, true);
        setStorePermissions(registration.revisions, registration.sourceRoot, true);
        setStorePermissions(registration.executions, registration.sourceRoot, true);
        if (!Files.exists(registration.lockFile, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createFile(registration.lockFile); }
            catch (java.nio.file.FileAlreadyExistsException raced) { /* Another Server created the shared lock. */ }
        }
        setSharedFilePermissions(registration.lockFile, registration.sourceRoot, true);
        verifyNoLink(registration.store.resolve("identity"));
    }

    private void verifyIdentity(Registration registration) throws Exception {
        Path identity = registration.store.resolve("identity");
        String value = IDENTITY_VERSION + "\t" + enc(registration.domain) + "\t" + enc(registration.scopeId)
                + "\t" + enc(registration.sourceRoot.toString()) + "\t"
                + enc(String.valueOf(registration.configuredConfigPath)) + "\n";
        if (!Files.exists(identity, LinkOption.NOFOLLOW_LINKS)) writeAtomic(identity, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        else {
            verifyNoLink(identity);
            String existing = Files.readString(identity, java.nio.charset.StandardCharsets.UTF_8);
            if (!value.equals(existing)) throw new IOException("Source revision storage identity does not match its registration");
        }
        setSharedFilePermissions(identity, registration.sourceRoot, false);
    }

    private WriterLock acquire(Registration registration) throws IOException {
        ReentrantLock local=localWriterLock(registration);
        local.lock();
        FileChannel channel=null;
        try {
            channel=FileChannel.open(registration.lockFile, StandardOpenOption.WRITE);
            return new WriterLock(channel, channel.lock(), local);
        } catch (IOException | RuntimeException failure) {
            if(channel!=null)channel.close();local.unlock();throw failure;
        }
    }

    private static ReentrantLock localWriterLock(Registration registration) {
        Path key=registration.lockFile.toAbsolutePath().normalize();
        return JVM_WRITER_LOCKS.computeIfAbsent(key,ignored->new ReentrantLock(true));
    }

    private Revision readActive(Registration registration) throws Exception {
        verifyNoLink(registration.active);
        if (!Files.isRegularFile(registration.active, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Active source revision is unavailable");
        String value = Files.readString(registration.active, java.nio.charset.StandardCharsets.UTF_8);
        String[] fields = value.strip().split("\\t", -1);
        boolean sealed = fields.length == 5 && POINTER_VERSION.equals(fields[0]);
        boolean legacy = fields.length == 4 && LEGACY_POINTER_VERSION.equals(fields[0]);
        if ((!sealed && !legacy) || !fields[1].matches("R[A-F0-9]{32}"))
            throw new IOException("Active source revision pointer is invalid");
        String revision = "-".equals(fields[2]) ? null : fields[2];
        if (revision != null && !revision.matches("[a-f0-9]{64}")) throw new IOException("Active source revision pointer is invalid");
        String transactionId = "-".equals(fields[3]) ? null : fields[3];
        if (transactionId != null && !transactionId.matches("T[A-F0-9]{32}")) throw new IOException("Active source revision pointer is invalid");
        Path root = checkedRevision(registration, fields[1]);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("Active source revision tree is unavailable");
        String actualDigest = treeDigest(registration.domain, root);
        if (sealed && (!fields[4].matches("[a-f0-9]{64}") || !actualDigest.equals(fields[4])))
            throw new IOException("Active source revision failed its integrity check");
        return new Revision(fields[1], revision, transactionId, actualDigest, sealed, root);
    }

    /**
     * The configured source tree is the deployment import/export mirror. Server readers use
     * immutable revisions; a changed mirror is copied and verified before the active pointer moves.
     */
    private Revision refreshConfiguredSource(Registration registration, boolean verifyContent) throws Exception {
        Revision active = readActive(registration);
        if (active.transactionId != null) return active;
        String sourceDigest = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(registration.domain)
                ? treeDigestConfigured(registration.domain, registration.sourceRoot, registration.configuredConfigPath)
                : treeDigest(registration.domain, registration.sourceRoot);
        if (sourceDigest.equals(active.treeDigest)) return active;

        String id = nextId();
        Path imported = checkedRevision(registration, id);
        try {
            copyTree(registration.domain, registration.sourceRoot, imported, registration.configuredConfigPath, true);
            String copiedDigest = treeDigest(registration.domain, imported);
            String sourceDigestAfterCopy = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(registration.domain)
                    ? treeDigestConfigured(registration.domain, registration.sourceRoot, registration.configuredConfigPath)
                    : treeDigest(registration.domain, registration.sourceRoot);
            if (!sourceDigest.equals(copiedDigest) || !sourceDigest.equals(sourceDigestAfterCopy))
                throw new IOException("Configured source changed while its revision was being imported");
            restoreTreePermissions(imported, registration.sourceRoot);
            writeSharedAtomic(registration.active, pointer(id, null, null, copiedDigest), registration.sourceRoot);
            return new Revision(id, null, null, copiedDigest, true, imported);
        } catch (Exception failure) {
            try { deleteTree(imported); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static void exportTree(Path source, Path destination) throws Exception {
        List<Path> sourceEntries = treeEntries(source);
        Map<String, Path> desired = new LinkedHashMap<>();
        List<Path> desiredDirectories = new ArrayList<>();
        for (Path entry : sourceEntries) {
            String relative = relativePath(source, entry);
            desired.put(relative, entry);
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) desiredDirectories.add(entry);
        }

        Map<Path, Set<PosixFilePermission>> originalDirectoryPermissions = new LinkedHashMap<>();
        List<Path> destinationEntries = treeEntries(destination);
        destinationEntries.add(destination);
        try {
            for (Path directory : destinationEntries) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) continue;
                Set<PosixFilePermission> permissions = exactPermissions(directory);
                originalDirectoryPermissions.put(directory, permissions);
                if (permissions != null) {
                    EnumSet<PosixFilePermission> writable = permissions.isEmpty()
                            ? EnumSet.noneOf(PosixFilePermission.class) : EnumSet.copyOf(permissions);
                    writable.add(PosixFilePermission.OWNER_WRITE);
                    writable.add(PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(directory, writable);
                } else if (!directory.toFile().setWritable(true, false) || !directory.toFile().setExecutable(true, false)) {
                    throw new IOException("Configured source directory cannot be updated");
                }
            }

            desiredDirectories.sort(Comparator.comparingInt(Path::getNameCount));
            for (Path sourceDirectory : desiredDirectories) {
                Path target = destination.resolve(source.relativize(sourceDirectory));
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)))
                    deleteExisting(target);
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(target);
                    setPermissions(target, safeCreateDirectoryPermissions());
                }
            }

            for (Path sourceEntry : sourceEntries) {
                if (Files.isDirectory(sourceEntry, LinkOption.NOFOLLOW_LINKS)) continue;
                Path target = destination.resolve(source.relativize(sourceEntry));
                Path parent = target.getParent();
                if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent))
                    throw new IOException("Configured source export parent is unsafe");
                if (Files.isSymbolicLink(sourceEntry)) exportSymbolicLink(sourceEntry, target);
                else exportRegularFile(sourceEntry, target);
            }

            List<Path> existingEntries = treeEntries(destination);
            existingEntries.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path existing : existingEntries) {
                String relative = relativePath(destination, existing);
                if (!desired.containsKey(relative)) deleteExisting(existing);
            }
        } finally {
            List<Path> directories = new ArrayList<>(originalDirectoryPermissions.keySet());
            directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path directory : directories) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) continue;
                Set<PosixFilePermission> permissions = originalDirectoryPermissions.get(directory);
                if (permissions != null) Files.setPosixFilePermissions(directory, permissions);
            }
        }
    }

    /** Export only the approved central Config tree, preserving every unrelated ATT_HOME entry. */
    private static void exportCentralTree(Path source, Path destination, String configuredConfigPath) throws Exception {
        List<Path> sourceEntries = centralTreeEntries(source, "config/config.yaml", false);
        Map<String, Path> desiredFiles = new LinkedHashMap<>();
        List<Path> desiredDirectories = new ArrayList<>();
        for (Path entry : sourceEntries) {
            String relative = relativePath(source, entry);
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) desiredDirectories.add(entry);
            else desiredFiles.put("config/config.yaml".equals(relative) ? configuredConfigPath : relative, entry);
        }

        Map<Path, Set<PosixFilePermission>> originalDirectoryPermissions = new LinkedHashMap<>();
        List<Path> destinationEntries = centralTreeEntries(destination, configuredConfigPath, true);
        destinationEntries.add(destination);
        try {
            for (Path directory : destinationEntries) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) continue;
                Set<PosixFilePermission> permissions = exactPermissions(directory);
                originalDirectoryPermissions.put(directory, permissions);
                if (permissions != null) {
                    EnumSet<PosixFilePermission> writable = permissions.isEmpty()
                            ? EnumSet.noneOf(PosixFilePermission.class) : EnumSet.copyOf(permissions);
                    writable.add(PosixFilePermission.OWNER_WRITE);
                    writable.add(PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(directory, writable);
                } else if (!directory.toFile().setWritable(true, false) || !directory.toFile().setExecutable(true, false)) {
                    throw new IOException("Configured central Config directory cannot be updated");
                }
            }

            desiredDirectories.sort(Comparator.comparingInt(Path::getNameCount));
            for (Path sourceDirectory : desiredDirectories) {
                String relative = relativePath(source, sourceDirectory);
                Path target = destination.resolve(relative);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Configured central Config export directory is unsafe");
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(target);
                    setPermissions(target, safeCreateDirectoryPermissions());
                }
            }

            for (Map.Entry<String, Path> desired : desiredFiles.entrySet()) {
                Path target = destination.resolve(desired.getKey());
                Path sourceFile = desired.getValue();
                Path parent = target.getParent();
                if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent))
                    throw new IOException("Configured central Config export parent is unsafe");
                exportRegularFile(sourceFile, target);
            }

            for (Path existing : centralTreeEntries(destination, configuredConfigPath, true)) {
                if (Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!desiredFiles.containsKey(relativePath(destination, existing))) deleteExisting(existing);
            }
        } finally {
            List<Path> directories = new ArrayList<>(originalDirectoryPermissions.keySet());
            directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path directory : directories) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) continue;
                Set<PosixFilePermission> permissions = originalDirectoryPermissions.get(directory);
                if (permissions != null) Files.setPosixFilePermissions(directory, permissions);
            }
        }
    }

    private static List<Path> centralTreeEntries(Path root) throws IOException {
        return centralTreeEntries(root, "config/config.yaml", false);
    }

    private static List<Path> centralTreeEntries(Path root, String configuredConfigPath,
                                                 boolean configuredSource) throws IOException {
        List<Path> entries = new ArrayList<>();
        Set<String> centralFiles = centralSnapshotFiles(root, configuredConfigPath, configuredSource);
        Set<String> centralDirectories = centralSnapshotDirectories(centralFiles);
        final int[] fileCount = {0}, entryCount = {0};
        final long[] byteCount = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                String relative = relativePath(root, directory);
                if (!centralSnapshotDirectory(relative, centralDirectories)
                        && !(configuredSource && pathAncestor(relative, configuredConfigPath))) return FileVisitResult.SKIP_SUBTREE;
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory))
                    throw new IOException("Central Config source contains an unsafe directory link");
                if (++entryCount[0] > MAX_TREE_ENTRIES) throw new IOException("Central Config exceeds its revision entry limit");
                if (!directory.equals(root)) entries.add(directory);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = relativePath(root, file);
                if (!centralFiles.contains(relative)) return FileVisitResult.CONTINUE;
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || !attributes.isRegularFile())
                    throw new IOException("Central Config source contains an unsafe file");
                if (++entryCount[0] > MAX_TREE_ENTRIES || ++fileCount[0] > MAX_TREE_FILES
                        || (byteCount[0] += attributes.size()) > MAX_TREE_BYTES)
                    throw new IOException("Central Config exceeds its revision limit");
                entries.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { throw failure; }
        });
        return entries;
    }

    /**
     * Retains approved central Config descriptors and the bounded closure they name. The
     * Server and Worker resolve those paths from the same Config root, so every captured
     * reference must be immutable and addressable inside this revision.
     */
    private static Set<String> centralSnapshotFiles(Path root) throws IOException {
        return centralSnapshotFiles(root, "config/config.yaml", false);
    }

    private static Set<String> centralSnapshotFiles(Path root, String configuredConfigPath,
                                                     boolean configuredSource) throws IOException {
        Path canonicalRoot = root.toRealPath();
        TreeSet<String> included = new TreeSet<>();
        Deque<Path> yamlQueue = new ArrayDeque<>();
        String physicalConfigPath = configuredSource ? configuredConfigPath : "config/config.yaml";
        Path selectedConfig = canonicalRoot.resolve(physicalConfigPath).normalize();
        if (!selectedConfig.startsWith(canonicalRoot) || configuredSource
                && (!Files.isRegularFile(selectedConfig, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(selectedConfig)))
            throw new IOException("Configured central Runtime Config file is unavailable");
        Files.walkFileTree(canonicalRoot, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                String relative = relativePath(canonicalRoot, directory);
                if (!ChangeSetPublisher.centralSnapshotDirectory(relative)
                        && !(configuredSource && pathAncestor(relative, physicalConfigPath))) return FileVisitResult.SKIP_SUBTREE;
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory))
                    throw new IOException("Central Config source contains an unsafe directory link");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = relativePath(canonicalRoot, file);
                boolean selectedConfig = relative.equals(physicalConfigPath);
                if (!selectedConfig && (!ChangeSetPublisher.centralSnapshotFile(relative)
                        || configuredSource && !"config/config.yaml".equals(physicalConfigPath)
                                && "config/config.yaml".equals(relative))) return FileVisitResult.CONTINUE;
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || !attributes.isRegularFile())
                    throw new IOException("Central Config source contains an unsafe file");
                included.add(relative);
                if (isYamlPath(relative)) yamlQueue.addLast(file);
                if (included.size() > ChangeSetPublisher.MAX_REVISION_FILES)
                    throw new IOException("Central Config exceeds its revision file limit");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { throw failure; }
        });

        FileExpressionResolver fileExpressions;
        try { fileExpressions = new FileExpressionResolver(canonicalRoot); }
        catch (RuntimeException unavailable) { throw new IOException("Central Config reference root is unavailable", unavailable); }
        Set<Path> visitedYaml = new HashSet<>();
        int referenceCount = 0;
        while (!yamlQueue.isEmpty()) {
            Path descriptor = yamlQueue.removeFirst().toAbsolutePath().normalize();
            if (!visitedYaml.add(descriptor)) continue;
            if (visitedYaml.size() > MAX_CENTRAL_REFERENCE_COUNT)
                throw new IOException("Central Config exceeds its descriptor reference limit");
            if (Files.size(descriptor) > MAX_CENTRAL_DESCRIPTOR_YAML_BYTES) continue;
            Object parsed;
            try { parsed = YamlSupport.load(descriptor); }
            catch (Exception malformedDescriptor) { continue; }

            List<String> references = new ArrayList<>();
            collectCentralHelperPaths(parsed, references);
            collectCentralFileExpressions(parsed, references, fileExpressions);
            for (String authored : references) {
                if (++referenceCount > MAX_CENTRAL_REFERENCE_COUNT)
                    throw new IOException("Central Config exceeds its reference limit");
                Path lexical = centralReferencePath(canonicalRoot, descriptor, authored);
                if (lexical == null || hasSymbolicCentralAncestor(canonicalRoot, lexical)) continue;
                Path resolved;
                try { resolved = fileExpressions.resolve(authored, descriptor.getParent()); }
                catch (Exception invalidOrMissing) { continue; }
                if (!resolved.startsWith(canonicalRoot.resolve("config"))) continue;
                Path canonical;
                try { canonical = resolved.toRealPath(); }
                catch (IOException missing) { continue; }
                if (!canonical.startsWith(canonicalRoot.resolve("config"))
                        || !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = relativePath(canonicalRoot, canonical);
                if (!ChangeSetPublisher.centralSnapshotReferenceFile(relative)) continue;
                if (included.add(relative) && isYamlPath(relative)) yamlQueue.addLast(canonical);
                if (included.size() > ChangeSetPublisher.MAX_REVISION_FILES)
                    throw new IOException("Central Config exceeds its revision file limit");
            }
        }
        return included;
    }

    private static boolean pathAncestor(String directory, String file) {
        if (directory == null || directory.isEmpty()) return true;
        return file != null && (file.equals(directory) || file.startsWith(directory + "/"));
    }

    private static boolean centralSnapshotDirectory(String relative, Set<String> includedDirectories) {
        if (ChangeSetPublisher.centralSnapshotDirectory(relative)) return true;
        return relative != null && includedDirectories.contains(relative);
    }

    private static Set<String> centralSnapshotDirectories(Set<String> includedFiles) {
        Set<String> directories = new HashSet<>();
        for (String file : includedFiles) {
            int slash = file.lastIndexOf('/');
            while (slash > 0) {
                directories.add(file.substring(0, slash));
                slash = file.lastIndexOf('/', slash - 1);
            }
        }
        return directories;
    }

    private static boolean isYamlPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static void collectCentralHelperPaths(Object value, List<String> references) {
        if (!(value instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (CENTRAL_HELPER_LIST_FIELDS.contains(key.toLowerCase(java.util.Locale.ROOT))
                    && entry.getValue() instanceof Iterable<?> paths) {
                for (Object path : paths) if (path instanceof String text) references.add(text.trim());
            }
            collectCentralHelperPaths(entry.getValue(), references);
        }
    }

    private static void collectCentralFileExpressions(Object value, List<String> references,
                                                      FileExpressionResolver resolver) {
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) collectCentralFileExpressions(child, references, resolver);
        } else if (value instanceof Iterable<?> values) {
            for (Object child : values) collectCentralFileExpressions(child, references, resolver);
        } else if (value instanceof String text && text.contains("&{")) {
            try { references.addAll(resolver.extractReferences(text)); }
            catch (RuntimeException invalidExpression) { /* The Engine validator reports the malformed locator. */ }
        }
    }

    private static Path centralReferencePath(Path root, Path descriptor, String authored) {
        if (authored == null || authored.isBlank() || authored.length() > 1024
                || authored.codePoints().anyMatch(Character::isISOControl) || authored.indexOf('\\') >= 0) return null;
        try {
            Path value = Path.of(authored);
            if (value.isAbsolute()) return null;
            boolean relativeToDescriptor = authored.startsWith("./") || authored.startsWith("../");
            Path base = relativeToDescriptor ? descriptor.getParent() : root;
            Path lexical = base.resolve(value).normalize();
            if (!lexical.startsWith(root.resolve("config"))) return null;
            return lexical;
        } catch (RuntimeException invalid) { return null; }
    }

    private static boolean hasSymbolicCentralAncestor(Path root, Path path) {
        Path current = path;
        while (current != null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) return true;
            if (current.equals(root)) break;
            current = current.getParent();
        }
        return false;
    }

    private static void pruneCentralSnapshot(Path root) throws IOException {
        Set<String> included = centralSnapshotFiles(root);
        List<Path> remove = new ArrayList<>();
        Path config = root.resolve("config");
        if (!Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(config, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory))
                    throw new IOException("Central Config candidate contains an unsafe directory link");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = relativePath(root, file);
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file))
                    throw new IOException("Central Config candidate contains an unsafe file link");
                if (!included.contains(relative)) remove.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { throw failure; }
        });
        for (Path file : remove) Files.deleteIfExists(file);
    }

    private static void exportRegularFile(Path source, Path target) throws IOException {
        boolean existingRegular = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target);
        Set<PosixFilePermission> permissions = existingRegular ? exactPermissions(target) : safeCreatePermissions();
        if (existingRegular && Files.mismatch(source, target) == -1L) {
            Files.setLastModifiedTime(target, Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS));
            return;
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteExisting(target);
        Path temporary = Files.createTempFile(target.getParent(), ".att-source-export-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            if (permissions != null) Files.setPosixFilePermissions(temporary, permissions);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void exportSymbolicLink(Path source, Path target) throws IOException {
        Path wanted = Files.readSymbolicLink(source);
        if (Files.isSymbolicLink(target) && wanted.equals(Files.readSymbolicLink(target))) return;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteExisting(target);
        Path temporary = target.resolveSibling(".att-source-export-" + UUID.randomUUID());
        try {
            Files.createSymbolicLink(temporary, wanted);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void deleteExisting(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) deleteTree(path);
        else Files.deleteIfExists(path);
    }

    private static List<Path> treeEntries(Path root) throws IOException {
        List<Path> entries = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root)) entries.add(directory);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || attributes.isRegularFile()) {
                    entries.add(file);
                    return FileVisitResult.CONTINUE;
                }
                throw new IOException("Source tree contains an unsupported file");
            }
        });
        return entries;
    }

    private static String relativePath(Path root, Path entry) {
        return root.relativize(entry).toString().replace(java.io.File.separatorChar, '/');
    }

    private static byte[] pointer(String id, String sourceRevision, String transactionId, String treeDigest) {
        String value = POINTER_VERSION + "\t" + id + "\t" + (sourceRevision == null ? "-" : sourceRevision)
                + "\t" + (transactionId == null ? "-" : transactionId) + "\t" + treeDigest + "\n";
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Path checkedRevision(Registration registration, String id) throws IOException {
        if (id == null || !id.matches("R[A-F0-9]{32}")) throw new IOException("Source revision identity is invalid");
        Path path = registration.revisions.resolve(id).normalize();
        if (!path.startsWith(registration.revisions) || path.equals(registration.revisions) || Files.isSymbolicLink(path))
            throw new IOException("Source revision path is unsafe");
        return path;
    }

    private static Path canonicalRoot(Path root) throws IOException {
        if (root == null) throw new IOException("Source root is unavailable");
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Source root is unavailable");
        Path real = absolute.toRealPath();
        if (!absolute.equals(real)) throw new IOException("Source root is unsafe");
        return real;
    }

    private static void apply(Path root, SourceFileTransaction.Write write) throws Exception {
        String relative = safeRelative(write.logicalPath);
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Source path is unsafe");
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Source path contains an unsafe symlink");
        }
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (write.expectedHash == null) {
            if (exists) throw new SourceFileTransaction.RevisionConflict();
        } else if (!exists || Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !write.expectedHash.equals(fileDigest(target))) throw new SourceFileTransaction.RevisionConflict();
        if (write.bytes != null && write.bytes.length > SourceFileTransaction.MAX_FILE_BYTES)
            throw new IOException("Source file exceeds its size limit");
        Path parent = target.getParent();
        createDirectories(root, parent);
        if (write.bytes == null) {
            Files.deleteIfExists(target);
            forceDirectory(parent);
            return;
        }
        Set<PosixFilePermission> permissions = exists ? exactPermissions(target) : safeCreatePermissions();
        Path replacement = Files.createTempFile(parent, ".att-source-replacement-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(replacement, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(write.bytes);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            setPermissions(replacement, permissions);
            Files.move(replacement, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(parent);
        } finally { Files.deleteIfExists(replacement); }
    }

    private static void copyTree(String domain, Path source, Path destination) throws Exception {
        copyTree(domain, source, destination, "config/config.yaml", false);
    }

    private static void copyTree(String domain, Path source, Path destination,
                                 String configuredConfigPath, boolean configuredSource) throws Exception {
        Files.createDirectory(destination);
        final List<Path> directories = new ArrayList<>();
        final long[] byteCount = {0L};
        final int[] fileCount = {0},entryCount = {0};
        final boolean central = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain);
        final Set<String> centralFiles = central ? centralSnapshotFiles(source, configuredConfigPath, configuredSource) : Set.of();
        final Set<String> centralDirectories = central ? centralSnapshotDirectories(centralFiles) : Set.of();
        final String physicalConfigPath = configuredSource ? configuredConfigPath : "config/config.yaml";
        final long byteLimit = central ? ChangeSetPublisher.MAX_REVISION_BYTES : MAX_TREE_BYTES;
        final long fileByteLimit = central ? ChangeSetPublisher.MAX_REVISION_FILE_BYTES : MAX_TREE_BYTES;
        final int fileLimit = central ? ChangeSetPublisher.MAX_REVISION_FILES : MAX_TREE_FILES;
        final int entryLimit = central ? ChangeSetPublisher.MAX_REVISION_FILES * 3 : MAX_TREE_ENTRIES;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                String relativeName = relativePath(source, directory);
                if (central && !centralSnapshotDirectory(relativeName, centralDirectories)
                        && !(configuredSource && pathAncestor(relativeName, physicalConfigPath))) return FileVisitResult.SKIP_SUBTREE;
                if(++entryCount[0]>entryLimit)throw new IOException("Source tree exceeds its revision entry limit");
                if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink()) throw new IOException("Source tree contains an unsafe directory link");
                Path relative = source.relativize(directory);
                Path target = relative.getNameCount() == 0 ? destination : destination.resolve(relative);
                if (!target.equals(destination)) {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
                            throw new IOException("Source revision copy directory is unsafe");
                    } else Files.createDirectory(target);
                }
                directories.add(target);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relativeName = relativePath(source, file);
                if (central && !centralFiles.contains(relativeName)) return FileVisitResult.CONTINUE;
                if(++entryCount[0]>entryLimit)throw new IOException("Source tree exceeds its revision entry limit");
                Path relative = source.relativize(file);
                String targetRelative = central && configuredSource && relativeName.equals(physicalConfigPath)
                        ? "config/config.yaml" : relativeName;
                Path target = destination.resolve(targetRelative);
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    Path link = Files.readSymbolicLink(file);
                    if (link.isAbsolute() || !file.getParent().resolve(link).normalize().startsWith(source))
                        throw new IOException("Source tree contains an external symbolic link");
                createCopyParent(target, destination);
                Files.createSymbolicLink(target, link);
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile()) throw new IOException("Source tree contains an unsupported file");
                byteCount[0] += attributes.size();
                if (attributes.size() > fileByteLimit || ++fileCount[0] > fileLimit || byteCount[0] > byteLimit)
                    throw new IOException("Source tree exceeds its revision limit");
                createCopyParent(target, destination);
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                if(attributes.size()>0)forceFile(target);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException { throw failure; }
        });
        for (int index = directories.size() - 1; index >= 0; index--) {
            Path target = directories.get(index);
            Path original = target.equals(destination) ? source : source.resolve(destination.relativize(target));
            setPermissions(target, workingDirectoryPermissions(original));
        }
        forceDirectory(destination);
    }

    private static void createCopyParent(Path target, Path destination) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !parent.normalize().startsWith(destination.normalize()))
            throw new IOException("Source revision copy target escapes its destination");
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Source revision copy parent is unsafe");
    }

    private static void restoreTreePermissions(Path root, Path originalRoot) throws IOException {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                paths.add(directory); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                paths.add(file); return FileVisitResult.CONTINUE;
            }
        });
        for (Path path : paths) {
            Path original = originalRoot.resolve(root.relativize(path));
            if (Files.isSymbolicLink(path)) continue;
            boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> originalPermissions = Files.exists(original, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(original) ? exactPermissions(original) : null;
            setImmutablePermissions(path, immutablePermissions(originalPermissions, directory), directory);
        }
    }

    private static Set<PosixFilePermission> immutablePermissions(Set<PosixFilePermission> source, boolean directory) {
        EnumSet<PosixFilePermission> result = source == null || source.isEmpty()
                ? EnumSet.noneOf(PosixFilePermission.class) : EnumSet.copyOf(source);
        result.remove(PosixFilePermission.OWNER_WRITE);
        result.remove(PosixFilePermission.GROUP_WRITE);
        result.remove(PosixFilePermission.OTHERS_WRITE);
        result.add(PosixFilePermission.OWNER_READ);
        if (directory) result.add(PosixFilePermission.OWNER_EXECUTE);
        return result;
    }

    private static void setImmutablePermissions(Path path, Set<PosixFilePermission> permissions, boolean directory) throws IOException {
        if (permissions != null) {
            try { Files.setPosixFilePermissions(path, permissions); }
            catch (UnsupportedOperationException unsupported) {
                throw new IOException("Source revision storage cannot enforce read-only revision permissions", unsupported);
            }
            return;
        }
        if (!path.toFile().setWritable(false, false) || path.toFile().canWrite())
            throw new IOException("Source revision storage cannot enforce read-only revision permissions");
        if (directory && !path.toFile().setExecutable(true, false))
            throw new IOException("Source revision storage cannot preserve revision reader access");
    }

    static String treeDigest(Path root) throws Exception {
        return treeDigest(null, root);
    }

    static String treeDigest(String domain, Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files = new ArrayList<>();
        final boolean central = ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain);
        final Set<String> centralFiles = central ? centralSnapshotFiles(root) : Set.of();
        final Set<String> centralDirectories = central ? centralSnapshotDirectories(centralFiles) : Set.of();
        final int[] fileCount = {0},entryCount = {0};
        final long[] byteCount = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (central && !centralSnapshotDirectory(relativePath(root, directory), centralDirectories))
                    return FileVisitResult.SKIP_SUBTREE;
                if (central && ++entryCount[0] > ChangeSetPublisher.MAX_REVISION_FILES * 3)
                    throw new IOException("Central Config exceeds its revision entry limit");
                if (central && (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)))
                    throw new IOException("Central Config source contains an unsafe directory link");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relativeName = relativePath(root, file);
                if (central && !centralFiles.contains(relativeName)) return FileVisitResult.CONTINUE;
                if (central && ++entryCount[0] > ChangeSetPublisher.MAX_REVISION_FILES * 3)
                    throw new IOException("Central Config exceeds its revision entry limit");
                if (central && (attributes.isSymbolicLink() || Files.isSymbolicLink(file)))
                    throw new IOException("Central Config source contains an unsafe file link");
                if (central && (attributes.size() > ChangeSetPublisher.MAX_REVISION_FILE_BYTES
                        || ++fileCount[0] > ChangeSetPublisher.MAX_REVISION_FILES
                        || (byteCount[0] += attributes.size()) > ChangeSetPublisher.MAX_REVISION_BYTES))
                    throw new IOException("Central Config exceeds its revision limit");
                if (!attributes.isSymbolicLink() && !Files.isSymbolicLink(file) && !attributes.isRegularFile())
                    throw new IOException("Source tree contains an unsupported file");
                files.add(file); return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> root.relativize(path).toString().replace(java.io.File.separatorChar, '/')));
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            digest.update(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte)0);
            if (Files.isSymbolicLink(file)) {
                Path target = Files.readSymbolicLink(file);
                if (target.isAbsolute() || !file.getParent().resolve(target).normalize().startsWith(root))
                    throw new IOException("Source tree contains an external symbolic link");
                digest.update((byte)'L');
                digest.update(target.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                digest.update((byte)'F');
                digest.update(java.util.HexFormat.of().parseHex(fileDigest(file)));
            }
            digest.update((byte)0xff);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String treeDigestConfigured(String domain, Path root, String configuredConfigPath) throws Exception {
        if (!ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(domain)) return treeDigest(domain, root);
        Set<String> physicalFiles = centralSnapshotFiles(root, configuredConfigPath, true);
        TreeMap<String, Path> logicalFiles = new TreeMap<>();
        for (String physical : physicalFiles) {
            String logical = physical.equals(configuredConfigPath) ? "config/config.yaml" : physical;
            if (logicalFiles.put(logical, root.resolve(physical)) != null)
                throw new IOException("Central Config path mapping contains a collision");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, Path> entry : logicalFiles.entrySet()) {
            digest.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((byte) 'F');
            digest.update(java.util.HexFormat.of().parseHex(fileDigest(entry.getValue())));
            digest.update((byte) 0xff);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String fileDigest(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static Set<PosixFilePermission> exactPermissions(Path original) {
        try {
            return EnumSet.copyOf(Files.getPosixFilePermissions(original, LinkOption.NOFOLLOW_LINKS));
        } catch (Exception unsupported) { return null; }
    }

    private static Set<PosixFilePermission> workingDirectoryPermissions(Path original) {
        try {
            EnumSet<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(original, LinkOption.NOFOLLOW_LINKS));
            permissions.add(PosixFilePermission.OWNER_READ); permissions.add(PosixFilePermission.OWNER_WRITE);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            return permissions;
        } catch (Exception unsupported) { return null; }
    }

    private static Set<PosixFilePermission> safeCreatePermissions() {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private static Set<PosixFilePermission> safeCreateDirectoryPermissions() {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    }

    private static void createDirectories(Path root, Path parent) throws IOException {
        Path current = root;
        for (Path part : root.relativize(parent)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Source directory is unsafe");
            } else {
                Files.createDirectory(current);
                setPermissions(current, safeCreateDirectoryPermissions());
            }
        }
    }

    private static void setStorePermissions(Path path, Path sourceRoot, boolean directory) throws IOException {
        EnumSet<PosixFilePermission> result = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if (directory) result.add(PosixFilePermission.OWNER_EXECUTE);
        try { Files.setPosixFilePermissions(path, result); }
        catch (UnsupportedOperationException unsupported) {
            boolean safe = path.toFile().setReadable(false, false) && path.toFile().setWritable(false, false)
                    && (!directory || path.toFile().setExecutable(false, false))
                    && path.toFile().setReadable(true, true) && path.toFile().setWritable(true, true)
                    && (!directory || path.toFile().setExecutable(true, true));
            if (!safe) throw new IOException("Source revision storage cannot be protected from other principals", unsupported);
        }
    }

    private static void setSharedFilePermissions(Path path, Path sourceRoot, boolean writable) {
        try { Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (Exception ignored) { }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        if (permissions == null) return;
        try { Files.setPosixFilePermissions(path, permissions); } catch (Exception ignored) { }
    }

    private static String safeRelative(String value) throws IOException {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0
                || value.length() > 512 || value.startsWith("/") || value.matches("^[A-Za-z]:.*"))
            throw new IOException("Source path is unsafe");
        Path path = Path.of(value);
        if (path.isAbsolute() || !path.normalize().equals(path) || path.startsWith("..")) throw new IOException("Source path is unsafe");
        return value;
    }

    private static String scopeKey(String domain, String scopeId) { return domain + "\n" + scopeId; }
    private static String nextId() { return "R" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT); }
    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }
    private static String enc(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void verifyNoLink(Path path) throws IOException { if (Files.isSymbolicLink(path)) throw new IOException("Source revision storage contains an unsafe link"); }

    private static void writeAtomic(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".att-source-pointer-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void writeSharedAtomic(Path path,byte[] bytes,Path sourceRoot) throws IOException {
        Path temporary=Files.createTempFile(path.getParent(),".att-source-pointer-",".tmp");
        try {
            setSharedFilePermissions(temporary,sourceRoot,false);
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void forceFile(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); } catch (Exception ignored) { }
    }
    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); } catch (Exception ignored) { }
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root)) throw new IOException("Unsafe source revision entry");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                try { Files.setPosixFilePermissions(directory, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
                catch (UnsupportedOperationException ignored) { directory.toFile().setWritable(true, false); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if(!attributes.isSymbolicLink()&&!Files.isSymbolicLink(file)) {
                    try { Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ)); }
                    catch (UnsupportedOperationException ignored) { file.toFile().setWritable(true, false); }
                }
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
