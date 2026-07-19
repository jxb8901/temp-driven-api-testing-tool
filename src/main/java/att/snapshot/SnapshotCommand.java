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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;

/** Generates canonical semantic snapshots for explicitly selected testcase workbooks. */
public final class SnapshotCommand {
    public List<Path> generate(Path projectRoot, FrameworkConfig global, ExecutionOptions options) throws Exception {
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
                snapshots.serialize(snapshot); // Complete canonicalization before any selected file is written.
                prepared.add(new Prepared(canonical, snapshot));
            } catch (Exception e) {
                throw DiagnosticException.wrap(DiagnosticCodes.TESTCASE_INVALID, "Unable to generate testcase snapshot", e,
                        workbook.toString(), "snapshot", "Correct the workbook/sidecar data, then rerun the snapshot command.");
            }
        }
        List<Path> written = new ArrayList<Path>();
        for (Prepared item : prepared) {
            try { written.add(snapshots.write(item.workbook, item.snapshot)); }
            catch (Exception e) { throw DiagnosticException.wrap(DiagnosticCodes.TESTCASE_INVALID, "Unable to write testcase snapshot", e,
                    item.workbook.toString(), "snapshot", "Check directory permissions and retry; an existing snapshot was preserved when replacement failed."); }
        }
        return written;
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
        private final Map<String, Object> snapshot;
        private Prepared(Path workbook, Map<String, Object> snapshot) { this.workbook = workbook; this.snapshot = snapshot; }
    }
}
