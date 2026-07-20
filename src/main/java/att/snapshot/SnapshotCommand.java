/* Author: Jeffrey + ChatGPT */
package att.snapshot;

import att.config.FrameworkConfig;
import att.config.SuiteConfigResolver;
import att.core.ExecutionOptions;
import att.core.IdentifierValidator;
import att.core.TestCase;
import att.excel.ExcelTestSuiteLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;

/** Generates canonical semantic snapshots for explicitly selected testcase workbooks. */
public final class SnapshotCommand {
    private static final Object JVM_UPDATE_LOCK = new Object();

    public List<Path> generate(Path projectRoot, FrameworkConfig global, ExecutionOptions options) throws Exception {
        return withUpdateLock(projectRoot, new LockedOperation<List<Path>>() {
            @Override public List<Path> run() throws Exception { return generateUnlocked(projectRoot, global, options); }
        });
    }

    /** Refreshes only changed snapshots for a run after every selected workbook has been prepared successfully. */
    public List<Path> updateForRun(Path projectRoot, FrameworkConfig global, ExecutionOptions options) throws Exception {
        return withUpdateLock(projectRoot, new LockedOperation<List<Path>>() {
            @Override public List<Path> run() throws Exception { return updateForRunUnlocked(projectRoot, global, options); }
        });
    }

    private List<Path> generateUnlocked(Path projectRoot, FrameworkConfig global, ExecutionOptions options) throws Exception {
        List<Prepared> prepared = prepare(projectRoot, global, options, false);
        TestcaseSnapshotService snapshots = new TestcaseSnapshotService();
        List<Path> written = new ArrayList<Path>();
        for (Prepared item : prepared) {
            try { written.add(snapshots.write(item.workbook, item.snapshot)); }
            catch (Exception e) { throw DiagnosticException.wrap(DiagnosticCodes.TESTCASE_INVALID, "Unable to write testcase snapshot", e,
                    item.workbook.toString(), "snapshot", "Check directory permissions and retry; an existing snapshot was preserved when replacement failed."); }
        }
        return written;
    }

    private List<Path> updateForRunUnlocked(Path projectRoot, FrameworkConfig global, ExecutionOptions options) throws Exception {
        List<Prepared> prepared = prepare(projectRoot, global, options, true);
        List<Prepared> changed = new ArrayList<Prepared>();
        for (Prepared item : prepared) {
            try {
                if (!Files.exists(item.target) || !java.util.Arrays.equals(Files.readAllBytes(item.target), item.bytes)) changed.add(item);
            } catch (Exception e) {
                throw DiagnosticException.wrap(DiagnosticCodes.TESTCASE_INVALID, "Unable to inspect testcase snapshot before update", e,
                        item.target.toString(), "snapshot", "Check that the snapshot is a readable regular file, then retry.");
            }
        }
        TestcaseSnapshotService snapshots = new TestcaseSnapshotService();
        List<Path> updated = new ArrayList<Path>();
        for (Prepared item : changed) {
            try { updated.add(snapshots.write(item.workbook, item.snapshot)); }
            catch (Exception e) {
                String completed = updated.isEmpty() ? "none" : join(updated);
                throw new DiagnosticException(DiagnosticCodes.TESTCASE_INVALID, "Unable to update testcase snapshot",
                        "Completed snapshot updates: " + completed + "; failed path: " + item.target + "; cause: " + message(e),
                        item.target.toString(), "snapshot", null, null, null, null, null,
                        "Correct the filesystem problem and rerun with --update-snapshot; completed atomic replacements remain valid.", e);
            }
        }
        return updated;
    }

    private List<Prepared> prepare(Path projectRoot, FrameworkConfig global, ExecutionOptions options, boolean runUpdate) throws Exception {
        List<Path> workbooks = suites(projectRoot, global, options);
        SuiteConfigResolver resolver = new SuiteConfigResolver(projectRoot, global);
        TestcaseSnapshotService snapshots = new TestcaseSnapshotService();
        List<Prepared> prepared = new ArrayList<Prepared>();
        for (Path workbook : workbooks) {
            try {
                Path canonical = IdentifierValidator.canonicalPath(workbook, "workbook");
                Path canonicalRoot = IdentifierValidator.canonicalPath(projectRoot, "package root");
                if (!canonical.startsWith(canonicalRoot) || Files.isSymbolicLink(workbook)) throw new IllegalArgumentException("Workbook escapes package root or is a symbolic link: " + workbook);
                FrameworkConfig config = resolver.resolve(canonical);
                List<TestCase> cases = new ExcelTestSuiteLoader(config).load(canonical);
                Map<String, Object> snapshot = snapshots.build(config, cases);
                byte[] bytes = snapshots.serialize(snapshot).getBytes(StandardCharsets.UTF_8); // Complete canonicalization before any selected file is written.
                Path target = snapshots.snapshotPath(canonical);
                if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("Snapshot is a symbolic link and cannot be replaced: " + target);
                if (Files.exists(target) && !Files.isRegularFile(target)) throw new IllegalArgumentException("Snapshot target is not a regular file: " + target);
                prepared.add(new Prepared(canonical, target, snapshot, bytes));
            } catch (Exception e) {
                throw DiagnosticException.wrap(DiagnosticCodes.TESTCASE_INVALID, "Unable to generate testcase snapshot", e,
                        workbook.toString(), "snapshot", runUpdate
                                ? "Correct the workbook/sidecar data, then rerun with --update-snapshot."
                                : "Correct the workbook/sidecar data, then rerun the snapshot command.");
            }
        }
        return prepared;
    }

    private List<Path> suites(Path projectRoot, FrameworkConfig global, ExecutionOptions options) throws Exception {
        List<Path> result = new ArrayList<Path>();
        if (!options.suitePaths().isEmpty()) {
            for (Path path : options.suitePaths()) result.add(path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize());
        } else {
            Path configured = options.suiteDirectory() == null ? global.testcasesRoot() : options.suiteDirectory();
            Path directory = configured.isAbsolute() ? configured : projectRoot.resolve(configured).normalize();
            try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx"))
                        .sorted().forEach(result::add);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No Excel suites selected for snapshot generation");
        Collections.sort(result);
        return result;
    }

    private static final class Prepared {
        private final Path workbook;
        private final Path target;
        private final Map<String, Object> snapshot;
        private final byte[] bytes;
        private Prepared(Path workbook, Path target, Map<String, Object> snapshot, byte[] bytes) {
            this.workbook = workbook; this.target = target; this.snapshot = snapshot; this.bytes = bytes;
        }
    }

    private <T> T withUpdateLock(Path projectRoot, LockedOperation<T> operation) throws Exception {
        synchronized (JVM_UPDATE_LOCK) {
            Path canonical = IdentifierValidator.canonicalPath(projectRoot, "package root");
            Path lockPath = Paths.get(System.getProperty("java.io.tmpdir")).resolve("att-snapshot-" + sha256(canonical.toString()) + ".lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.run();
            }
        }
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte item : digest) out.append(String.format("%02x", item & 0xff));
        return out.toString();
    }

    private String join(List<Path> paths) {
        List<String> values = new ArrayList<String>(); for (Path path : paths) values.add(path.toString());
        return String.join(", ", values);
    }

    private String message(Exception error) {
        String value = error.getMessage(); return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private interface LockedOperation<T> { T run() throws Exception; }
}
