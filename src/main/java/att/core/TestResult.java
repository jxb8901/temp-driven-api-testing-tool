/*
 * Author: Jeffrey + ChatGPT
 */

package att.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Stores the final execution outcome and report fields for one test case.
 */
public class TestResult {
    private final String caseId;
    private final String caseName;
    private final ResultStatus status;
    private final Duration duration;
    private final String expected;
    private final String actual;
    private final Path caseLogPath;
    private final List<ValidationResult> validations;
    private final String workbookId;
    private final String groupId;
    private final List<String> tags;

    public TestResult(String caseId, String caseName, ResultStatus status, Duration duration, String expected, String actual, Path caseLogPath, List<ValidationResult> validations) {
        this(caseId, caseName, status, duration, expected, actual, caseLogPath, validations, inferred(caseId, 0), inferred(caseId, 1), Collections.<String>emptyList());
    }

    public TestResult(String caseId, String caseName, ResultStatus status, Duration duration, String expected, String actual, Path caseLogPath,
                      List<ValidationResult> validations, String workbookId, String groupId, List<String> tags) {
        this.caseId = caseId;
        this.caseName = caseName;
        this.status = status;
        this.duration = duration;
        this.expected = expected;
        this.actual = actual;
        this.caseLogPath = caseLogPath;
        this.validations = validations;
        this.workbookId = workbookId == null ? "" : workbookId;
        this.groupId = groupId == null ? "" : groupId;
        this.tags = tags == null ? Collections.<String>emptyList() : new ArrayList<String>(tags);
    }

    public String caseId() { return caseId; }
    public String caseName() { return caseName; }
    public ResultStatus status() { return status; }
    public Duration duration() { return duration; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    public Path caseLogPath() { return caseLogPath; }
    public Path outputXml() { return caseLogPath; }
    public List<ValidationResult> validations() { return validations; }
    public String workbookId() { return workbookId; }
    public String groupId() { return groupId; }
    public List<String> tags() { return Collections.unmodifiableList(tags); }
    public TestResult relocate(Path from, Path to) {
        Path relocated = caseLogPath != null && caseLogPath.startsWith(from) ? to.resolve(from.relativize(caseLogPath)) : caseLogPath;
        return new TestResult(caseId, caseName, status, duration, expected, actual, relocated, validations, workbookId, groupId, tags);
    }
    private static String inferred(String caseId, int index) {
        if (caseId == null) return "";
        String[] parts = caseId.split("\\.", 3);
        return parts.length == 3 ? parts[index] : (index == 1 && parts.length > 1 ? parts[0] : "");
    }
}
