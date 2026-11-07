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
    interface RecoveryAudit { void record(AuditRecord record) throws Exception; }
    interface CommitAudit { void record(AuditRecord record) throws Exception; }
    interface FaultInjector {
        void afterApply(int appliedCount) throws IOException;
        default void beforeJournal() throws IOException { }
        default void afterPointerSwitch() throws IOException { }
    }

    static final class AuditRecord {
        final String transactionId, actor, domain, scopeId, action, outcome;
        final String beforeRevision, afterRevision, requestId;
        final List<String> resources;
        AuditRecord(String transactionId, String actor, String domain, String scopeId, String action, String outcome,
                    String beforeRevision, String afterRevision, String requestId, List<String> resources) {
            this.transactionId = transactionId; this.actor = actor; this.domain = domain; this.scopeId = scopeId;
            this.action = action; this.outcome = outcome; this.beforeRevision = beforeRevision;
            this.afterRevision = afterRevision; this.requestId = requestId;
            this.resources = Collections.unmodifiableList(new ArrayList<String>(resources));
        }
    }

    static final class CommitResult {
        final String revision;
        final boolean auditConfirmed;
        CommitResult(String revision, boolean auditConfirmed) { this.revision = revision; this.auditConfirmed = auditConfirmed; }
    }

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
    private static final String REVISION_JOURNAL_VERSION = "att-source-revision-transaction-v2";
    static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    static final long MAX_BACKUP_BYTES = 64L * 1024L * 1024L;
    private final Path transactionRoot;
    private final SourcePublicationBarrier barrier;
    private final FaultInjector faultInjector;
    private final SourceRevisionStore revisions = new SourceRevisionStore();

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

    void registerRoot(String domain, String scopeId, Path root) throws Exception { revisions.register(domain, scopeId, root); }

    Path pin(String domain, String scopeId, Path root) throws Exception { return revisions.pin(domain, scopeId, root).root; }

    Path registeredRoot(String domain, String scopeId) { return revisions.registeredRoot(domain, scopeId); }

    boolean ownsRevision(String domain, String scopeId, Path configuredRoot, Path candidate) {
        return revisions.ownsRevision(domain, scopeId, configuredRoot, candidate);
    }

    String commit(String domain, String scopeId, Path root, String actor, String beforeRevision,
                  String afterRevision, Map<String, Write> writes, RevisionCheck revisionCheck) throws Exception {
        return commitWithAudit(domain, scopeId, root, actor, beforeRevision, afterRevision, writes,
                revisionCheck, null, Collections.emptyList(), auditAction(domain), null).revision;
    }

    CommitResult commitWithAudit(String domain, String scopeId, Path root, String actor, String beforeRevision,
                                 String afterRevision, Map<String, Write> writes, RevisionCheck revisionCheck,
                                 String requestId, List<String> resources, String action, CommitAudit audit) throws Exception {
        if (writes == null || writes.isEmpty()) throw new IllegalArgumentException("A non-empty source change set is required");
        if (revisions.registeredRoot(domain, scopeId) == null) registerRoot(domain, scopeId, root);
        Path sourceRoot = revisions.registeredRoot(domain, scopeId);
        if (sourceRoot == null) throw new IOException("Source revision root is unavailable");
        String transactionId = "T" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        Path transaction = transactionRoot.resolve(transactionId);
        Path candidate = null;
        boolean journalReady = false;
        boolean pointerSwitched = false;
        String beforeId = null;
        String afterId = null;
        try (SourceRevisionStore.WriterLock ignored = revisions.lock(domain, scopeId, sourceRoot)) {
            revisions.refreshConfiguredSource(domain, scopeId, sourceRoot, true);
            SourceRevisionStore.Revision active = revisions.readActive(domain, scopeId, sourceRoot);
            ensurePriorSwitchRecoverable(active);
            if (active.transactionId != null)
                revisions.exportActiveToConfiguredRoot(domain, scopeId, sourceRoot, active.id);
            beforeId = active.id;
            List<PreparedWrite> prepared = prepare(active.root, writes);
            privateDirectory(transaction);
            afterId = "R" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
            AuditRecord record = new AuditRecord(transactionId, actor, domain, scopeId,
                    action == null ? auditAction(domain) : action, "COMMITTED", beforeRevision, afterRevision, requestId,
                    resources == null ? Collections.emptyList() : resources);
            if (faultInjector != null) faultInjector.beforeJournal();
            writeRevisionJournal(transaction, sourceRoot, beforeId, afterId, null, record);
            journalReady = true;
            if (revisionCheck != null) revisionCheck.verify();
            candidate = revisions.createCandidate(domain, scopeId, sourceRoot, active, afterId, writes, faultInjector);
            String actualId = candidate.getFileName().toString();
            if (!afterId.equals(actualId)) throw new IOException("Prepared source revision identity changed");
            revisions.freezeCandidate(candidate, candidate);
            String afterTreeDigest = SourceRevisionStore.treeDigest(domain, candidate);
            writeRevisionJournal(transaction, sourceRoot, beforeId, afterId, afterTreeDigest, record);
            if (revisionCheck != null) revisionCheck.verify(); // Last source, authorization, and gate freshness fence before the pointer swap.
            revisions.switchTo(domain, scopeId, sourceRoot, afterId, afterRevision, transactionId);
            pointerSwitched = true;
            if (faultInjector != null) faultInjector.afterPointerSwitch();
            writeForcedAtomically(transaction.resolve("COMMITTED"), (transactionId + "\n" + afterRevision + "\n").getBytes(StandardCharsets.UTF_8));
            revisions.exportActiveToConfiguredRoot(domain, scopeId, sourceRoot, afterId);
            boolean auditConfirmed = true;
            if (audit != null) {
                try { audit.record(record); }
                catch (Exception auditFailure) { auditConfirmed = false; }
            }
            if (auditConfirmed) {
                writeForcedAtomically(transaction.resolve("AUDITED"), (transactionId + "\n").getBytes(StandardCharsets.UTF_8));
                revisions.clearTransactionId(domain, scopeId, sourceRoot, afterId, transactionId);
                cleanupRevisionTransaction(transaction);
            }
            return new CommitResult(afterRevision, auditConfirmed);
        } catch (SimulatedCrash interrupted) {
            throw interrupted;
        } catch (Exception failure) {
            if (pointerSwitched) {
                // The complete revision is already active. Keep the journal for startup audit replay.
                return new CommitResult(afterRevision, false);
            }
            if (transaction != null && journalReady) {
                try { if (afterId != null) revisions.deleteRevision(domain, scopeId, sourceRoot, afterId); }
                catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                try { deleteTree(transaction); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            } else if (transaction != null) {
                try { deleteTree(transaction); } catch (Exception ignored) { }
            }
            throw failure;
        }
    }

    /** Recover every prepared transaction before Server routes or Workers are made available. */
    int recover(Map<String, Path> trustedRoots, RecoveryAudit audit) throws Exception {
        Lock writeLock = barrier.writeLock();
        writeLock.lock();
        List<SourceRevisionStore.WriterLock> rootLocks = new ArrayList<>();
        try {
            acquireTrustedRootLocks(trustedRoots, rootLocks);
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
                String firstLine = Files.readAllLines(journal, StandardCharsets.UTF_8).stream().findFirst().orElse("");
                if (firstLine.startsWith(REVISION_JOURNAL_VERSION + "\t")) {
                    recoverRevisionTransaction(entry, trustedRoots, audit);
                } else {
                    recoverLegacyTransaction(entry, trustedRoots, audit);
                }
                recovered++;
            }
            for (Map.Entry<String, Path> trusted : trustedRoots.entrySet()) {
                int separator = trusted.getKey().indexOf(':');
                if (separator <= 0 || separator == trusted.getKey().length() - 1)
                    throw new IOException("Source recovery root registration is invalid");
                revisions.refreshConfiguredSource(trusted.getKey().substring(0, separator),
                        trusted.getKey().substring(separator + 1), trusted.getValue(), true);
            }
            return recovered;
        } finally {
            for (int index = rootLocks.size() - 1; index >= 0; index--) {
                try { rootLocks.get(index).close(); } catch (Exception ignored) { }
            }
            writeLock.unlock();
        }
    }

    private void acquireTrustedRootLocks(Map<String, Path> trustedRoots,
                                         List<SourceRevisionStore.WriterLock> held) throws Exception {
        if (trustedRoots == null) throw new IOException("Trusted source roots are unavailable for recovery");
        List<Map.Entry<String, Path>> ordered = new ArrayList<>(trustedRoots.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Path> root : ordered) {
            int separator = root.getKey().indexOf(':');
            if (separator <= 0 || separator == root.getKey().length() - 1)
                throw new IOException("Source recovery root registration is invalid");
            held.add(revisions.lock(root.getKey().substring(0, separator), root.getKey().substring(separator + 1), root.getValue()));
        }
    }

    private void recoverRevisionTransaction(Path transaction, Map<String, Path> trustedRoots, RecoveryAudit audit) throws Exception {
        RevisionJournal parsed = readRevisionJournal(transaction.resolve("journal"));
        Path trusted = trustedRoots.get(parsed.domain + ":" + parsed.scopeId);
        if (trusted == null || !canonicalRoot(trusted).equals(parsed.sourceRoot))
            throw new IOException("Source recovery root no longer matches trusted registration");
        registerRoot(parsed.domain, parsed.scopeId, parsed.sourceRoot);
        SourceRevisionStore.Revision active = revisions.readActive(parsed.domain, parsed.scopeId, parsed.sourceRoot);
        boolean markedCommitted = validRevisionCommitMarker(transaction.resolve("COMMITTED"), parsed.transactionId, parsed.afterRevision);
        boolean activeCommit = parsed.transactionId.equals(active.transactionId);
        boolean committed = markedCommitted || activeCommit;
        if (committed) {
            if (parsed.afterTreeDigest == null || !revisions.revisionTreeMatches(parsed.domain, parsed.scopeId,
                    parsed.sourceRoot, parsed.afterId, parsed.afterTreeDigest))
                throw new IOException("Committed source revision failed its integrity check");
            revisions.exportActiveToConfiguredRoot(parsed.domain, parsed.scopeId, parsed.sourceRoot, parsed.afterId);
            AuditRecord record = parsed.record("COMMITTED");
            if (audit != null) audit.record(record);
            writeForcedAtomically(transaction.resolve("AUDITED"), (parsed.transactionId + "\n").getBytes(StandardCharsets.UTF_8));
            revisions.clearTransactionId(parsed.domain, parsed.scopeId, parsed.sourceRoot, parsed.afterId, parsed.transactionId);
            cleanupRevisionTransaction(transaction);
            return;
        }
        if (!parsed.beforeId.equals(active.id))
            throw new IOException("Active source revision cannot be reconciled with its transaction journal");
        revisions.deleteRevision(parsed.domain, parsed.scopeId, parsed.sourceRoot, parsed.afterId);
        if (audit != null) audit.record(parsed.record("ROLLED_BACK"));
        cleanupRevisionTransaction(transaction);
    }

    private void recoverLegacyTransaction(Path transaction, Map<String, Path> trustedRoots, RecoveryAudit audit) throws Exception {
        Journal parsed = readJournal(transaction.resolve("journal"));
        Path trusted = trustedRoots.get(parsed.domain + ":" + parsed.scopeId);
        if (trusted == null || !canonicalRoot(trusted).equals(parsed.root))
            throw new IOException("Source recovery root no longer matches trusted registration");
        boolean isCommitted = validCommitMarker(transaction.resolve("COMMITTED"), parsed.afterRevision)
                && committedFilesMatch(parsed);
        if (!isCommitted) rollback(transaction); // Startup is blocked if every backup cannot be restored.
        if (audit != null) audit.record(new AuditRecord(transaction.getFileName().toString(), parsed.actor, parsed.domain,
                parsed.scopeId, auditAction(parsed.domain), isCommitted ? "COMMITTED" : "ROLLED_BACK",
                parsed.beforeRevision, parsed.afterRevision, null,
                parsed.files.stream().map(file -> file.path).toList()));
        if (isCommitted) cleanupCommitted(transaction); else cleanupRolledBack(transaction);
    }

    private void ensurePriorSwitchRecoverable(SourceRevisionStore.Revision active) throws IOException {
        if (active.transactionId == null) return;
        Path previous = transactionRoot.resolve(active.transactionId);
        if (Files.exists(previous.resolve("journal"), LinkOption.NOFOLLOW_LINKS)
                && !validRevisionCommitMarker(previous.resolve("COMMITTED"), active.transactionId, active.sourceRevision))
            throw new IOException("The active source revision has an unfinished publication journal");
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

    private static void writeRevisionJournal(Path transaction, Path sourceRoot, String beforeId, String afterId,
                                             String afterTreeDigest, AuditRecord record) throws Exception {
        StringBuilder value = new StringBuilder();
        value.append(REVISION_JOURNAL_VERSION).append('\t').append(encoded(record.transactionId))
                .append('\t').append(encoded(record.domain)).append('\t').append(encoded(record.scopeId))
                .append('\t').append(encoded(sourceRoot.toString())).append('\t').append(encoded(record.actor))
                .append('\t').append(encoded(record.action)).append('\t').append(encoded(record.beforeRevision))
                .append('\t').append(encoded(record.afterRevision)).append('\t').append(encoded(beforeId))
                .append('\t').append(encoded(afterId)).append('\t')
                .append(afterTreeDigest == null ? "-" : afterTreeDigest).append('\t')
                .append(record.requestId == null ? "-" : encoded(record.requestId)).append('\n');
        for (String resource : record.resources) value.append("R\t").append(encoded(resource)).append('\n');
        writeForcedAtomically(transaction.resolve("journal"), value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static RevisionJournal readRevisionJournal(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024)
            throw new IOException("Source transaction journal is unavailable");
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException("Source transaction journal is invalid");
        String[] fields = lines.get(0).split("\\t", -1);
        if (fields.length != 13 || !REVISION_JOURNAL_VERSION.equals(fields[0]))
            throw new IOException("Source transaction journal is invalid");
        String transactionId = decoded(fields[1]);
        String beforeId = decoded(fields[9]), afterId = decoded(fields[10]);
        String treeDigest = "-".equals(fields[11]) ? null : fields[11];
        String requestId = "-".equals(fields[12]) ? null : decoded(fields[12]);
        if (!transactionId.matches("T[A-F0-9]{32}") || !beforeId.matches("R[A-F0-9]{32}")
                || !afterId.matches("R[A-F0-9]{32}") || treeDigest != null && !isDigest(treeDigest))
            throw new IOException("Source transaction journal is invalid");
        String beforeRevision = decoded(fields[7]), afterRevision = decoded(fields[8]);
        if (!isDigest(beforeRevision) || !isDigest(afterRevision)) throw new IOException("Source transaction journal is invalid");
        List<String> resources = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String[] resource = lines.get(index).split("\\t", -1);
            if (resource.length != 2 || !"R".equals(resource[0]) || resources.size() >= 64)
                throw new IOException("Source transaction journal is invalid");
            resources.add(safeRelative(decoded(resource[1])));
        }
        return new RevisionJournal(transactionId, decoded(fields[2]), decoded(fields[3]),
                canonicalRoot(java.nio.file.Paths.get(decoded(fields[4]))), decoded(fields[5]), decoded(fields[6]),
                beforeRevision, afterRevision, beforeId, afterId, treeDigest, requestId, resources);
    }

    private static boolean validRevisionCommitMarker(Path marker, String transactionId, String revision) throws IOException {
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 512)
            return false;
        List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
        return lines.size() == 2 && transactionId.equals(lines.get(0)) && revision.equals(lines.get(1));
    }

    private static String auditAction(String domain) {
        return "SOURCE_CHANGE_SET_PUBLISH";
    }

    private void cleanupRevisionTransaction(Path transaction) throws IOException {
        // The durable DB audit is committed before AUDITED is written; journal removal is last.
        Files.deleteIfExists(transaction.resolve("journal"));
        forceDirectory(transaction);
        Files.deleteIfExists(transaction.resolve("COMMITTED"));
        Files.deleteIfExists(transaction.resolve("AUDITED"));
        forceDirectory(transaction);
        Files.deleteIfExists(transaction);
        forceDirectory(transactionRoot);
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

    private static final class RevisionJournal {
        final String transactionId, domain, scopeId, actor, action, beforeRevision, afterRevision;
        final String beforeId, afterId, afterTreeDigest, requestId;
        final Path sourceRoot;
        final List<String> resources;
        RevisionJournal(String transactionId, String domain, String scopeId, Path sourceRoot, String actor,
                        String action, String beforeRevision, String afterRevision, String beforeId, String afterId,
                        String afterTreeDigest, String requestId, List<String> resources) {
            this.transactionId = transactionId; this.domain = domain; this.scopeId = scopeId;
            this.sourceRoot = sourceRoot; this.actor = actor; this.action = action;
            this.beforeRevision = beforeRevision; this.afterRevision = afterRevision; this.beforeId = beforeId;
            this.afterId = afterId; this.afterTreeDigest = afterTreeDigest; this.requestId = requestId;
            this.resources = Collections.unmodifiableList(new ArrayList<>(resources));
        }
        AuditRecord record(String outcome) {
            return new AuditRecord(transactionId, actor, domain, scopeId, action, outcome,
                    beforeRevision, afterRevision, requestId, resources);
        }
    }
}
