package att.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Typed Run selection and execution intent. */
public final class RunRequest extends AttRequest {
    private final List<Path> suites; private final Path suiteDirectory;
    private final Set<String> caseIds, tags, excludeTags;
    private final boolean all, rerunFailed, dryRun, failFast;
    public RunRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                      List<Path> suites, Path suiteDirectory, Set<String> caseIds, Set<String> tags,
                      Set<String> excludeTags, boolean all, boolean rerunFailed, boolean dryRun, boolean failFast) {
        super(packageRoot, configPath, environment, outputDirectory, runId);
        this.suites = immutableList(suites); this.suiteDirectory = suiteDirectory;
        this.caseIds = immutableSet(caseIds); this.tags = immutableSet(tags); this.excludeTags = immutableSet(excludeTags);
        this.all = all; this.rerunFailed = rerunFailed; this.dryRun = dryRun; this.failFast = failFast;
    }
    public List<Path> suites() { return suites; } public Path suiteDirectory() { return suiteDirectory; }
    public Set<String> caseIds() { return caseIds; } public Set<String> tags() { return tags; } public Set<String> excludeTags() { return excludeTags; }
    public boolean all() { return all; } public boolean rerunFailed() { return rerunFailed; } public boolean dryRun() { return dryRun; } public boolean failFast() { return failFast; }
    static <T> List<T> immutableList(List<T> input) { return Collections.unmodifiableList(new ArrayList<T>(input == null ? Collections.<T>emptyList() : input)); }
    static <T> Set<T> immutableSet(Set<T> input) { return Collections.unmodifiableSet(new LinkedHashSet<T>(input == null ? Collections.<T>emptySet() : input)); }
}
