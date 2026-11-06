package att.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.TimeUnit;

/** Durable, recoverable multi-file replacement used by Manifest and Change Set publication. */
final class SourceFileTransaction {
    interface RevisionCheck { void verify() throws Exception; }
    interface RecoveryAudit {
        void record(String actor, String domain, String scopeId, String outcome, String revision) throws Exception;
    }
    interface FaultInjector { void afterApply(int appliedCount) throws IOException; }

    static final class Write {
        final String logicalPath;
        final byte[] bytes;
        final String expectedHash;

        Write(String logicalPath, byte[] bytes, String expectedHash) {
            this.logicalPath = logicalPath;
            this.bytes = bytes == null ? null : bytes.clone();
            this.expectedHash = expectedHash;
        }
    }

    static final class RevisionConflict extends RuntimeException {
        RevisionConflict() { super("Source revision changed"); }
    }
    static final class Busy extends RuntimeException {
        Busy() { super("Source readers did not release the publication barrier before the deadline"); }
    }

    static final class SimulatedCrash extends IOException {
        SimulatedCrash() { super("Simulated interrupted source transaction"); }
    }

    private static final String JOURNAL_VERSION = "att-source-transaction-v1";
    static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    static final long MAX_BACKUP_BYTES = 64L * 1024L * 1024L;
    private final Path transactionRoot;
    private final SourcePublicationBarrier barrier;
    private final FaultInjector faultInjector;

    SourceFileTransaction(Path dataDirectory, SourcePublicationBarrier barrier) throws IOException {
        this(dataDirectory, barrier, null);
    }

    SourceFileTransaction(Path dataDirectory, SourcePublicationBarrier barrier, FaultInjector faultInjector) throws IOException {
        this.barrier = barrier;
        this.faultInjector = faultInjector;
        this.transactionRoot = dataDirectory.resolve("source-transactions").toAbsolutePath().normalize();
        privateDirectory(transactionRoot);
    }

    Lock readLock() { return barrier.readLock(); }

    String commit(String domain, String scopeId, Path root, String actor, String beforeRevision,
                  String afterRevision, Map<String, Write> writes, RevisionCheck revisionCheck) throws Exception {
        if (writes == null || writes.isEmpty()) throw new IllegalArgumentException("A non-empty source change set is required");
        Lock writeLock = barrier.writeLock();
        boolean acquired;
        try { acquired = writeLock.tryLock(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
        if (!acquired) throw new Busy();
        Path transaction = null;
        boolean journalReady = false;
        boolean committed = false;
        try {
            revisionCheck.verify();
            Path canonicalRoot = canonicalRoot(root);
            List<PreparedWrite> prepared = prepare(canonicalRoot, writes);
            transaction = transactionRoot.resolve("T" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT));
            privateDirectory(transaction);
            prepareFiles(transaction, canonicalRoot, prepared);
            revisionCheck.verify();
            writeJournal(transaction, domain, scopeId, canonicalRoot, actor, beforeRevision, afterRevision, prepared);
            journalReady = true;
            int applied = 0;
            for (int index = 0; index < prepared.size(); index++) {
                apply(transaction, canonicalRoot, prepared.get(index), index);
                applied++;
                if (faultInjector != null) faultInjector.afterApply(applied);
            }
            writeForcedAtomically(transaction.resolve("COMMITTED"), (afterRevision + "\n").getBytes(StandardCharsets.UTF_8));
            committed = true;
            return afterRevision;
        } catch (SimulatedCrash interrupted) {
            throw interrupted;
        } catch (Exception failure) {
            if (journalReady && transaction != null && !committed) {
                try {
                    rollback(transaction);
                    cleanupRolledBack(transaction);
                } catch (Exception recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
            } else if (transaction != null && !journalReady) {
                try { deleteTree(transaction); } catch (Exception ignored) { }
            }
            throw failure;
        } finally {
            writeLock.unlock();
            if (committed && transaction != null) {
                try { cleanupCommitted(transaction); } catch (Exception ignored) { }
            }
        }
    }

    /** Recover every prepared transaction before Server routes or Workers are made available. */
    int recover(Map<String, Path> trustedRoots, RecoveryAudit audit) throws Exception {
        Lock writeLock = barrier.writeLock();
        writeLock.lock();
        try {
            if (!Files.isDirectory(transactionRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(transactionRoot))
                throw new IOException("Source recovery storage is unavailable");
            List<Path> entries = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.list(transactionRoot)) {
                stream.forEach(entries::add);
            }
            Collections.sort(entries, Comparator.comparing(path -> path.getFileName().toString()));
            int recovered = 0;
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Source recovery storage contains an unsafe entry");
                if (!entry.getFileName().toString().startsWith("T")) continue;
                Path journal = entry.resolve("journal");
                if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(entry); // A crash before the durable journal never changed published files.
                    continue;
                }
                Journal parsed = readJournal(journal);
                Path trusted = trustedRoots.get(parsed.domain + ":" + parsed.scopeId);
                if (trusted == null || !canonicalRoot(trusted).equals(parsed.root))
                    throw new IOException("Source recovery root no longer matches trusted registration");
                boolean isCommitted = validCommitMarker(entry.resolve("COMMITTED"), parsed.afterRevision)
                        && committedFilesMatch(parsed);
                if (!isCommitted) rollback(entry);
                if (audit != null) audit.record(parsed.actor, parsed.domain, parsed.scopeId,
                        isCommitted ? "COMMITTED" : "ROLLED_BACK", parsed.afterRevision);
                if (isCommitted) cleanupCommitted(entry); else cleanupRolledBack(entry);
                recovered++;
            }
            return recovered;
        } finally {
            writeLock.unlock();
        }
    }

    private static List<PreparedWrite> prepare(Path root, Map<String, Write> writes) throws Exception {
        List<PreparedWrite> result = new ArrayList<PreparedWrite>();
        Map<String, Boolean> unique = new LinkedHashMap<String, Boolean>();
        long backupBytes = 0L;
        for (Write write : writes.values()) {
            String relative = safeRelative(write.logicalPath);
            if (unique.put(relative, Boolean.TRUE) != null) throw new IllegalArgumentException("Duplicate source path");
            Path target = resolveWithoutLinks(root, relative);
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (write.expectedHash == null) {
                if (exists) throw new RevisionConflict();
            } else {
                long size = exists ? Files.size(target) : 0L;
                if (size > MAX_FILE_BYTES || backupBytes + size > MAX_BACKUP_BYTES)
                    throw new IllegalArgumentException("Source transaction backup exceeds its size limit");
                backupBytes += size;
                if (!exists || Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || !write.expectedHash.equals(hashFile(target))) throw new RevisionConflict();
            }
            if (write.bytes != null && write.bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Source file exceeds its size limit");
            result.add(new PreparedWrite(relative, write.bytes, write.expectedHash));
        }
        return result;
    }

    private static void prepareFiles(Path transaction, Path root, List<PreparedWrite> writes) throws Exception {
        Path backup = Files.createDirectory(transaction.resolve("before"));
        Path staged = Files.createDirectory(transaction.resolve("after"));
        setPrivate(backup); setPrivate(staged);
        for (int index = 0; index < writes.size(); index++) {
            PreparedWrite write = writes.get(index);
            Path target = resolveWithoutLinks(root, write.path);
            if (write.expectedHash != null) {
                Path saved = backup.resolve(Integer.toString(index));
                copyForced(target, saved);
                if (!write.expectedHash.equals(hashFile(saved))) throw new RevisionConflict();
            }
            if (write.bytes != null) writeForced(staged.resolve(Integer.toString(index)), write.bytes);
        }
        forceDirectory(backup);
        forceDirectory(staged);
        forceDirectory(transaction);
    }

    private static void writeJournal(Path transaction, String domain, String scopeId, Path root, String actor,
                                     String beforeRevision, String afterRevision, List<PreparedWrite> writes) throws Exception {
        StringBuilder value = new StringBuilder();
        value.append(JOURNAL_VERSION).append('\t').append(encoded(domain)).append('\t').append(encoded(scopeId))
                .append('\t').append(encoded(root.toString())).append('\t').append(encoded(actor))
                .append('\t').append(encoded(beforeRevision)).append('\t').append(encoded(afterRevision)).append('\n');
        for (int index = 0; index < writes.size(); index++) {
            PreparedWrite write = writes.get(index);
            value.append("F\t").append(index).append('\t').append(encoded(write.path)).append('\t')
                    .append(write.expectedHash == null ? "-" : write.expectedHash).append('\t')
                    .append(write.bytes == null ? "-" : hash(write.bytes)).append('\n');
        }
        writeForcedAtomically(transaction.resolve("journal"), value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void apply(Path transaction, Path root, PreparedWrite write, int index) throws Exception {
        Path target = resolveWithoutLinks(root, write.path);
        Path parent = target.getParent();
        createDirectoriesWithoutLinks(root, parent);
        if (write.bytes == null) {
            Files.delete(target);
            forceDirectory(parent);
        } else {
            Path source = transaction.resolve("after").resolve(Integer.toString(index));
            Path replacement = Files.createTempFile(parent, ".att-source-replacement-", ".tmp");
            try {
                Files.delete(replacement);
                copyForced(source, replacement);
                Files.move(replacement, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                forceDirectory(parent);
            } finally {
                Files.deleteIfExists(replacement);
            }
        }
    }

    private static void rollback(Path transaction) throws Exception {
        Journal journal = readJournal(transaction.resolve("journal"));
        Path root = canonicalRoot(journal.root);
        for (int index = journal.files.size() - 1; index >= 0; index--) {
            JournalFile file = journal.files.get(index);
            Path target = resolveWithoutLinks(root, file.path);
            if (file.beforeHash == null) {
                Files.deleteIfExists(target);
                removeEmptyParents(root, target.getParent());
            } else {
                Path backup = transaction.resolve("before").resolve(Integer.toString(index));
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backup)
                        || !file.beforeHash.equals(hashFile(backup)))
                    throw new IOException("Source transaction backup failed its integrity check");
                createDirectoriesWithoutLinks(root, target.getParent());
                Path restore = Files.createTempFile(target.getParent(), ".att-source-restore-", ".tmp");
                try {
                    Files.delete(restore);
                    copyForced(backup, restore);
                    Files.move(restore, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    forceDirectory(target.getParent());
                } finally {
                    Files.deleteIfExists(restore);
                }
            }
        }
    }

    private static Journal readJournal(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024)
            throw new IOException("Source transaction journal is unavailable");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException("Source transaction journal is invalid");
        String[] header = lines.get(0).split("\\t", -1);
        if (header.length != 7 || !JOURNAL_VERSION.equals(header[0])) throw new IOException("Source transaction journal is invalid");
        Journal result = new Journal(decoded(header[1]), decoded(header[2]), canonicalRoot(java.nio.file.Paths.get(decoded(header[3]))),
                decoded(header[4]), decoded(header[5]), decoded(header[6]));
        for (int line = 1; line < lines.size(); line++) {
            String[] fields = lines.get(line).split("\\t", -1);
            if (fields.length != 5 || !"F".equals(fields[0]) || Integer.parseInt(fields[1]) != result.files.size())
                throw new IOException("Source transaction journal is invalid");
            String before = "-".equals(fields[3]) ? null : fields[3];
            String after = "-".equals(fields[4]) ? null : fields[4];
            if (before != null && !isDigest(before) || after != null && !isDigest(after))
                throw new IOException("Source transaction journal is invalid");
            result.files.add(new JournalFile(safeRelative(decoded(fields[2])), before, after));
        }
        if (result.files.isEmpty()) throw new IOException("Source transaction journal is invalid");
        return result;
    }

    private static Path canonicalRoot(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Source root is unavailable");
        Path real = absolute.toRealPath();
        if (!absolute.equals(real)) throw new IOException("Source root is unsafe");
        return real;
    }

    private static Path resolveWithoutLinks(Path root, String relative) throws IOException {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Source path is unsafe");
        Path current = root;
        Path rel = root.relativize(target);
        for (Path segment : rel) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("Source path contains an unsafe symlink");
        }
        return target;
    }

    private static void createDirectoriesWithoutLinks(Path root, Path parent) throws IOException {
        Path relative = root.relativize(parent);
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Source directory is unsafe");
            } else {
                Files.createDirectory(current);
                forceDirectory(current.getParent());
            }
        }
    }

    private static void removeEmptyParents(Path root, Path parent) throws IOException {
        Path current = parent;
        while (current != null && !current.equals(root) && current.startsWith(root)) {
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) { current = current.getParent(); continue; }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return;
            try (java.util.stream.Stream<Path> contents = Files.list(current)) {
                if (contents.findAny().isPresent()) return;
            }
            Files.deleteIfExists(current);
            forceDirectory(current.getParent());
            current = current.getParent();
        }
    }

    private static boolean validCommitMarker(Path marker, String expectedRevision) throws IOException {
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 256)
            return false;
        String value = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
        return expectedRevision.equals(value);
    }

    private static boolean committedFilesMatch(Journal journal) {
        try {
            for (JournalFile file : journal.files) {
                Path target = resolveWithoutLinks(journal.root, file.path);
                boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (file.afterHash == null) {
                    if (exists) return false;
                } else if (!exists || Files.isSymbolicLink(target)
                        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || !file.afterHash.equals(hashFile(target))) return false;
            }
            return true;
        } catch (Exception unsafeOrUnreadable) {
            return false;
        }
    }

    private static String safeRelative(String value) throws IOException {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0
                || value.length() > 512 || value.startsWith("/") || value.matches("^[A-Za-z]:.*"))
            throw new IOException("Source path is unsafe");
        String[] parts = value.split("/", -1);
        for (String part : parts) if (part.isEmpty() || part.length() > 128 || ".".equals(part) || "..".equals(part)
                || part.indexOf(':') >= 0 || hasControl(part)) throw new IOException("Source path is unsafe");
        return String.join("/", parts);
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }

    private static void copyForced(Path source, Path destination) throws IOException {
        long size = Files.size(source);
        if (size > MAX_FILE_BYTES) throw new IOException("Source file exceeds its transaction limit");
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long copied = 0L;
            int count;
            while ((count = in.read(buffer)) >= 0) {
                copied += count;
                if (copied > MAX_FILE_BYTES) throw new IOException("Source file exceeds its transaction limit");
                buffer.flip();
                while (buffer.hasRemaining()) out.write(buffer);
                buffer.clear();
            }
            out.force(true);
        }
        setPrivate(destination);
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        setPrivate(path);
    }

    private static void writeForcedAtomically(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".att-source-marker-", ".tmp");
        try {
            writeReplace(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(path.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeReplace(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        setPrivate(path);
    }

    private static void forceDirectory(Path directory) {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        catch (Exception ignored) { /* Directory fsync is not supported by every provider. */ }
    }

    private static String hashFile(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item & 255));
        return value.toString();
    }
    private static boolean isDigest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static String encoded(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decoded(String value) throws IOException {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new IOException("Source transaction journal is invalid"); }
    }

    private static void privateDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(directory))
            throw new IOException("Private source transaction storage is unsafe");
        Files.createDirectories(directory);
        setPrivate(directory);
    }

    private static void setPrivate(Path path) {
        try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "rwx------" : "rw-------")); }
        catch (Exception ignored) { }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root)) throw new IOException("Unsafe source transaction entry");
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }

    private void cleanupCommitted(Path transaction) throws IOException {
        // A durable commit marker remains until after the journal is gone and backups are no longer needed.
        Files.deleteIfExists(transaction.resolve("journal"));
        forceDirectory(transaction);
        deleteTree(transaction.resolve("before"));
        deleteTree(transaction.resolve("after"));
        Files.deleteIfExists(transaction.resolve("COMMITTED"));
        forceDirectory(transaction);
        Files.deleteIfExists(transaction);
        forceDirectory(transactionRoot);
    }

    private void cleanupRolledBack(Path transaction) throws IOException {
        // Published files are restored before removing the journal, making later orphan cleanup harmless.
        Files.deleteIfExists(transaction.resolve("journal"));
        forceDirectory(transaction);
        deleteTree(transaction.resolve("before"));
        deleteTree(transaction.resolve("after"));
        Files.deleteIfExists(transaction.resolve("COMMITTED"));
        forceDirectory(transaction);
        Files.deleteIfExists(transaction);
        forceDirectory(transactionRoot);
    }

    private static final class PreparedWrite {
        final String path;
        final byte[] bytes;
        final String expectedHash;
        PreparedWrite(String path, byte[] bytes, String expectedHash) {
            this.path = path; this.bytes = bytes == null ? null : bytes.clone(); this.expectedHash = expectedHash;
        }
    }

    private static final class Journal {
        final String domain, scopeId, actor, beforeRevision, afterRevision;
        final Path root;
        final List<JournalFile> files = new ArrayList<JournalFile>();
        Journal(String domain, String scopeId, Path root, String actor, String beforeRevision, String afterRevision) {
            this.domain = domain; this.scopeId = scopeId; this.root = root; this.actor = actor;
            this.beforeRevision = beforeRevision; this.afterRevision = afterRevision;
        }
    }

    private static final class JournalFile {
        final String path, beforeHash, afterHash;
        JournalFile(String path, String beforeHash, String afterHash) {
            this.path = path; this.beforeHash = beforeHash; this.afterHash = afterHash;
        }
    }
}
