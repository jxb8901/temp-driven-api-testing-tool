package att.api;

import att.testcase.TestCaseSuiteResolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Typed RUN request for a derived implicit Suite across one or more native Definitions. */
public final class NativeCaseSuiteRunRequest extends AttRequest {
    private final String packageId;
    private final List<String> definitionIds;
    private final List<String> qualifiedCaseIds;
    private final List<String> tags;
    private final List<String> excludeTags;
    private final boolean all;
    private final boolean dryRun;
    private final boolean failFast;
    private final ExecutionEventListener observer;

    public NativeCaseSuiteRunRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory,
            String runId, String packageId, List<String> definitionIds, List<String> qualifiedCaseIds,
            List<String> tags, List<String> excludeTags, boolean all, boolean dryRun, boolean failFast,
            ExecutionEventListener observer) {
        super(packageRoot, configPath, environment, outputDirectory, runId);
        if (packageId == null || packageId.trim().isEmpty()) throw new IllegalArgumentException("packageId is required");
        this.packageId = packageId;
        this.definitionIds = copy(definitionIds, "definitionIds", TestCaseSuiteResolver.MAX_DEFINITIONS);
        this.qualifiedCaseIds = copy(qualifiedCaseIds, "qualifiedCaseIds", TestCaseSuiteResolver.MAX_RESOLVED_CASES);
        this.tags = copy(tags, "tags", 100, TestCaseSuiteResolver.MAX_TAG_LENGTH);
        this.excludeTags = copy(excludeTags, "excludeTags", 100, TestCaseSuiteResolver.MAX_TAG_LENGTH);
        if (this.definitionIds.isEmpty()) throw new IllegalArgumentException("At least one Definition ID is required");
        if (!all && this.qualifiedCaseIds.isEmpty() && this.tags.isEmpty())
            throw new IllegalArgumentException("Select all cases, at least one Qualified Case ID, or at least one tag");
        if (all && !this.qualifiedCaseIds.isEmpty()) throw new IllegalArgumentException("all and qualifiedCaseIds cannot be combined");
        this.all = all; this.dryRun = dryRun; this.failFast = failFast; this.observer = observer;
    }

    private static List<String> copy(List<String> values, String name, int maximum) {
        return copy(values, name, maximum, 512);
    }

    private static List<String> copy(List<String> values, String name, int maximum, int maxValueLength) {
        if (values == null) return Collections.emptyList();
        if (values.size() > maximum) throw new IllegalArgumentException(name + " must not exceed " + maximum + " entries");
        List<String> copy = new ArrayList<String>(values.size());
        Set<String> seen = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty() || value.length() > maxValueLength
                    || value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException(name + " contains an invalid value");
            if (!seen.add(value)) throw new IllegalArgumentException(name + " contains duplicate values: " + value);
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    public String packageId() { return packageId; }
    public List<String> definitionIds() { return definitionIds; }
    public List<String> qualifiedCaseIds() { return qualifiedCaseIds; }
    public List<String> tags() { return tags; }
    public List<String> excludeTags() { return excludeTags; }
    public boolean all() { return all; }
    public boolean dryRun() { return dryRun; }
    public boolean failFast() { return failFast; }
    public ExecutionEventListener observer() { return observer; }
}
