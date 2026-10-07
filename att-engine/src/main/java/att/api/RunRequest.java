package att.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import att.core.PerformanceProfile;

/** Typed Run selection and execution intent. */
public final class RunRequest extends AttRequest {
    private final List<Path> suites; private final Path suiteDirectory;
    private final Set<String> caseIds, tags, excludeTags;
    private final boolean all, rerunFailed, dryRun, failFast;
    private final Set<String> ciOutputs; private final String concurrencyMode; private final boolean profile;
    private final String outputFormat; private final boolean quiet, verbose;
    private final Consumer<String> outputListener; private final PerformanceProfile performanceProfile;
    public RunRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                      List<Path> suites, Path suiteDirectory, Set<String> caseIds, Set<String> tags,
                      Set<String> excludeTags, boolean all, boolean rerunFailed, boolean dryRun, boolean failFast) {
        this(packageRoot, configPath, environment, outputDirectory, runId, suites, suiteDirectory, caseIds, tags,
                excludeTags, all, rerunFailed, dryRun, failFast, null, "reject", false);
    }
    public RunRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                      List<Path> suites, Path suiteDirectory, Set<String> caseIds, Set<String> tags,
                      Set<String> excludeTags, boolean all, boolean rerunFailed, boolean dryRun, boolean failFast,
                      Set<String> ciOutputs, String concurrencyMode, boolean profile) {
        this(packageRoot, configPath, environment, outputDirectory, runId, suites, suiteDirectory, caseIds, tags,
                excludeTags, all, rerunFailed, dryRun, failFast, ciOutputs, concurrencyMode, profile,
                "machine", true, false, null, null);
    }
    public RunRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory, String runId,
                      List<Path> suites, Path suiteDirectory, Set<String> caseIds, Set<String> tags,
                      Set<String> excludeTags, boolean all, boolean rerunFailed, boolean dryRun, boolean failFast,
                      Set<String> ciOutputs, String concurrencyMode, boolean profile, String outputFormat,
                      boolean quiet, boolean verbose, Consumer<String> outputListener,
                      PerformanceProfile performanceProfile) {
        super(packageRoot, configPath, environment, outputDirectory, runId);
        this.suites = immutableList(suites); this.suiteDirectory = suiteDirectory;
        this.caseIds = immutableSet(caseIds); this.tags = immutableSet(tags); this.excludeTags = immutableSet(excludeTags);
        this.all = all; this.rerunFailed = rerunFailed; this.dryRun = dryRun; this.failFast = failFast;
        this.ciOutputs = immutableSet(ciOutputs == null
                ? new LinkedHashSet<String>(java.util.Arrays.asList("junit", "json")) : ciOutputs);
        this.concurrencyMode = concurrencyMode == null ? "reject" : concurrencyMode; this.profile = profile;
        this.outputFormat = outputFormat == null ? "machine" : outputFormat;
        this.quiet = quiet; this.verbose = verbose; this.outputListener = outputListener;
        this.performanceProfile = performanceProfile;
    }
    public List<Path> suites() { return suites; } public Path suiteDirectory() { return suiteDirectory; }
    public Set<String> caseIds() { return caseIds; } public Set<String> tags() { return tags; } public Set<String> excludeTags() { return excludeTags; }
    public boolean all() { return all; } public boolean rerunFailed() { return rerunFailed; } public boolean dryRun() { return dryRun; } public boolean failFast() { return failFast; }
    public Set<String> ciOutputs() { return ciOutputs; } public String concurrencyMode() { return concurrencyMode; } public boolean profile() { return profile; }
    public String outputFormat() { return outputFormat; } public boolean quiet() { return quiet; } public boolean verbose() { return verbose; }
    public Consumer<String> outputListener() { return outputListener; } public PerformanceProfile performanceProfile() { return performanceProfile; }
    static <T> List<T> immutableList(List<T> input) { return Collections.unmodifiableList(new ArrayList<T>(input == null ? Collections.<T>emptyList() : input)); }
    static <T> Set<T> immutableSet(Set<T> input) { return Collections.unmodifiableSet(new LinkedHashSet<T>(input == null ? Collections.<T>emptySet() : input)); }
}
