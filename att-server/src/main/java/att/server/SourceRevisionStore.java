package att.server;

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
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Stores complete immutable source trees and switches readers through one atomic pointer file. */
final class SourceRevisionStore {
    private static final String IDENTITY_VERSION = "att-source-revision-store-v1";
    private static final String POINTER_VERSION = "att-source-revision-pointer-v1";
    private static final long MAX_TREE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_TREE_FILES = 50000;
    private static final int MAX_TREE_ENTRIES = MAX_TREE_FILES * 4;
    private static final Map<Path, ReentrantLock> JVM_WRITER_LOCKS = new ConcurrentHashMap<>();

    static final class Revision {
        final String id;
        final String sourceRevision;
        final String transactionId;
        final Path root;
        Revision(String id, String sourceRevision, String transactionId, Path root) {
            this.id = id;
            this.sourceRevision = sourceRevision;
            this.transactionId = transactionId;
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
        final String domain, scopeId;
        final Path sourceRoot, store, revisions, active, lockFile;
        Registration(String domain, String scopeId, Path sourceRoot, Path store) {
            this.domain = domain; this.scopeId = scopeId; this.sourceRoot = sourceRoot; this.store = store;
            this.revisions = store.resolve("revisions"); this.active = store.resolve("active");
            this.lockFile = store.resolve("writer.lock");
        }
    }

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<String, Path> rootsByScope = new ConcurrentHashMap<>();

    synchronized void register(String domain, String scopeId, Path root) throws Exception {
        Path sourceRoot = canonicalRoot(root);
        String scopeKey = scopeKey(domain, scopeId);
        Path previousRoot = rootsByScope.get(scopeKey);
        if (previousRoot != null && previousRoot.equals(sourceRoot)) return;
        Registration registration = registration(domain, scopeId, sourceRoot);
        createStoreDirectories(registration);
        try (WriterLock ignored = acquire(registration)) {
            verifyIdentity(registration);
            if (!Files.exists(registration.active, LinkOption.NOFOLLOW_LINKS)) {
                String id = nextId();
                Path initial = registration.revisions.resolve(id);
                copyTree(sourceRoot, initial);
                String sourceDigest = treeDigest(sourceRoot);
                if (!sourceDigest.equals(treeDigest(initial))) {
                    deleteTree(initial);
                    throw new IOException("Source changed while its initial revision was being captured");
                }
                restoreTreePermissions(initial, sourceRoot);
                writeSharedAtomic(registration.active,pointer(id,null,null),registration.sourceRoot);
            }
        }
        registrations.put(scopeKey, registration);
        rootsByScope.put(scopeKey, sourceRoot);
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
        exportTree(active.root, registration.sourceRoot);
        if (!treeDigest(active.root).equals(treeDigest(registration.sourceRoot)))
            throw new IOException("Configured source export failed its integrity check");
    }

    Revision readActive(String domain, String scopeId, Path root) throws Exception {
        return readActive(lookup(domain, scopeId, root));
    }

    void switchTo(String domain, String scopeId, Path root, String id, String sourceRevision, String transactionId) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        Path target = checkedRevision(registration, id);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source revision is unavailable");
        writeSharedAtomic(registration.active,pointer(id,sourceRevision,transactionId),registration.sourceRoot);
    }

    void clearTransactionId(String domain, String scopeId, Path root, String id, String transactionId) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        Revision active = readActive(registration);
        if (!active.id.equals(id))
            throw new IOException("Active source transaction changed before its journal was cleared");
        if (active.transactionId == null) return;
        if (!active.transactionId.equals(transactionId))
            throw new IOException("Active source transaction changed before its journal was cleared");
        writeSharedAtomic(registration.active, pointer(active.id, active.sourceRevision, null), registration.sourceRoot);
    }

    Path createCandidate(String domain, String scopeId, Path root, Revision from, String id,
                         Map<String, SourceFileTransaction.Write> writes,
                         SourceFileTransaction.FaultInjector faultInjector) throws Exception {
        Registration registration = lookup(domain, scopeId, root);
        if (id == null || !id.matches("R[A-F0-9]{32}")) throw new IOException("Prepared source revision identity is invalid");
        Path candidate = registration.revisions.resolve(id);
        copyTree(from.root, candidate);
        int applied = 0;
        for (SourceFileTransaction.Write write : writes.values()) {
            apply(candidate, write);
            applied++;
            if (faultInjector != null) faultInjector.afterApply(applied);
        }
        return candidate;
    }

    void freezeCandidate(Path candidate, Path originalRoot) throws IOException { restoreTreePermissions(candidate, originalRoot); }

    String activeId(String domain, String scopeId, Path root) throws Exception {
        return readActive(domain, scopeId, root).id;
    }

    boolean revisionTreeMatches(String domain, String scopeId, Path root, String id, String expectedTreeDigest) {
        try { return expectedTreeDigest.equals(treeDigest(checkedRevision(lookup(domain, scopeId, root), id))); }
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
            registration = registration(domain, scopeId, sourceRoot);
            createStoreDirectories(registration);
            verifyIdentity(registration);
            registrations.put(scopeKey, registration);
        }
        return registration;
    }

    private Registration registration(String domain, String scopeId, Path sourceRoot) throws IOException {
        Path parent = sourceRoot.getParent();
        if (parent == null) throw new IOException("Source root has no parent directory");
        String suffix = digest((domain + "\n" + scopeId + "\n" + sourceRoot).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 20);
        String name = "." + sourceRoot.getFileName() + ".att-source-revisions-" + suffix;
        return new Registration(domain, scopeId, sourceRoot, parent.resolve(name));
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
        setStorePermissions(registration.store, registration.sourceRoot, true);
        setStorePermissions(registration.revisions, registration.sourceRoot, true);
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
                + "\t" + enc(registration.sourceRoot.toString()) + "\n";
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
        if (fields.length != 4 || !POINTER_VERSION.equals(fields[0]) || !fields[1].matches("R[A-F0-9]{32}"))
            throw new IOException("Active source revision pointer is invalid");
        String revision = "-".equals(fields[2]) ? null : fields[2];
        if (revision != null && !revision.matches("[a-f0-9]{64}")) throw new IOException("Active source revision pointer is invalid");
        String transactionId = "-".equals(fields[3]) ? null : fields[3];
        if (transactionId != null && !transactionId.matches("T[A-F0-9]{32}")) throw new IOException("Active source revision pointer is invalid");
        Path root = checkedRevision(registration, fields[1]);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("Active source revision tree is unavailable");
        return new Revision(fields[1], revision, transactionId, root);
    }

    /**
     * The configured source tree is the deployment import/export mirror. Server readers use
     * immutable revisions; a changed mirror is copied and verified before the active pointer moves.
     */
    private Revision refreshConfiguredSource(Registration registration, boolean verifyContent) throws Exception {
        Revision active = readActive(registration);
        if (active.transactionId != null) return active;
        if (!verifyContent && metadataFingerprint(registration.sourceRoot).equals(metadataFingerprint(active.root)))
            return active;

        String sourceDigest = treeDigest(registration.sourceRoot);
        if (sourceDigest.equals(treeDigest(active.root))) return active;

        String id = nextId();
        Path imported = checkedRevision(registration, id);
        try {
            copyTree(registration.sourceRoot, imported);
            String copiedDigest = treeDigest(imported);
            String sourceDigestAfterCopy = treeDigest(registration.sourceRoot);
            if (!sourceDigest.equals(copiedDigest) || !sourceDigest.equals(sourceDigestAfterCopy))
                throw new IOException("Configured source changed while its revision was being imported");
            restoreTreePermissions(imported, registration.sourceRoot);
            writeSharedAtomic(registration.active, pointer(id, null, null), registration.sourceRoot);
            return new Revision(id, null, null, imported);
        } catch (Exception failure) {
            try { deleteTree(imported); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static String metadataFingerprint(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> entries = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || attributes.isRegularFile()) {
                    entries.add(file);
                    return FileVisitResult.CONTINUE;
                }
                throw new IOException("Source tree contains an unsupported file");
            }
        });
        entries.sort(Comparator.comparing(path -> root.relativize(path).toString().replace(java.io.File.separatorChar, '/')));
        for (Path entry : entries) {
            String relative = root.relativize(entry).toString().replace(java.io.File.separatorChar, '/');
            digest.update(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (Files.isSymbolicLink(entry)) {
                Path target = Files.readSymbolicLink(entry);
                if (target.isAbsolute() || !entry.getParent().resolve(target).normalize().startsWith(root))
                    throw new IOException("Source tree contains an external symbolic link");
                digest.update((byte) 'L');
                digest.update(target.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                digest.update((byte) 'F');
                digest.update(Long.toString(attributes.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(attributes.lastModifiedTime().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            digest.update((byte) 0xff);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
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

    private static byte[] pointer(String id, String sourceRevision, String transactionId) {
        String value = POINTER_VERSION + "\t" + id + "\t" + (sourceRevision == null ? "-" : sourceRevision)
                + "\t" + (transactionId == null ? "-" : transactionId) + "\n";
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

    private static void copyTree(Path source, Path destination) throws Exception {
        Files.createDirectory(destination);
        final List<Path> directories = new ArrayList<>();
        final long[] byteCount = {0L};
        final int[] fileCount = {0},entryCount = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if(++entryCount[0]>MAX_TREE_ENTRIES)throw new IOException("Source tree exceeds its revision entry limit");
                if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink()) throw new IOException("Source tree contains an unsafe directory link");
                Path relative = source.relativize(directory);
                Path target = relative.getNameCount() == 0 ? destination : destination.resolve(relative);
                if (!target.equals(destination)) Files.createDirectory(target);
                directories.add(target);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if(++entryCount[0]>MAX_TREE_ENTRIES)throw new IOException("Source tree exceeds its revision entry limit");
                Path relative = source.relativize(file);
                Path target = destination.resolve(relative);
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    Path link = Files.readSymbolicLink(file);
                    if (link.isAbsolute() || !file.getParent().resolve(link).normalize().startsWith(source))
                        throw new IOException("Source tree contains an external symbolic link");
                    Files.createSymbolicLink(target, link);
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile()) throw new IOException("Source tree contains an unsupported file");
                byteCount[0] += attributes.size();
                if (++fileCount[0] > MAX_TREE_FILES || byteCount[0] > MAX_TREE_BYTES)
                    throw new IOException("Source tree exceeds its revision limit");
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                forceFile(target);
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
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
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
