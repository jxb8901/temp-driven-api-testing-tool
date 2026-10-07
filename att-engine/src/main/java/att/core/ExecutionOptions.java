/* Author: Jeffrey + ChatGPT */
package att.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import att.api.ExecutionEvent;
import att.api.ExecutionEventListener;

/** Typed engine execution intent, independent of command-line presentation settings. */
public final class ExecutionOptions {
    private final String command;
    private final Path configPath;
    private final String environment;
    private final List<Path> suitePaths;
    private final Path suiteDirectory;
    private final Set<String> caseIds;
    private final Set<String> tags;
    private final Set<String> excludeTags;
    private final String runId;
    private final boolean all;
    private final boolean rerunFailed;
    private final boolean dryRun;
    private final boolean failFast;
    private final Path outputDirectory;
    private final String validationScope;
    private final Set<String> ciOutputs;
    private final String concurrencyMode;
    private final boolean updateSnapshot;
    private final boolean profile;
    private final String debugTargetType;
    private final String debugTargetId;
    private final Path debugInput;
    private final boolean unsafeFailureDetails;
    private final Path loadScenario;
    private final String loadUsers;
    private final String loadArrivalRate;
    private final String loadWarmup;
    private final String loadRampUp;
    private final String loadDuration;
    private final String loadRampDown;
    private final String loadThinkTime;
    private final String loadMaxConcurrent;
    private final String loadOverloadPolicy;
    private final List<String> variableOverrides;
    private final ExecutionEventListener observer;
    private final ExecutionIdentitySeed identitySeed;

    public ExecutionOptions(Path configPath, Path suitePath, Path suiteDirectory, Set<String> caseIds, Set<String> tags,
                            Set<String> excludeTags, String runId, boolean rerunFailed, boolean dryRun,
                            boolean failFast, Path outputDirectory) {
        this("run", configPath, suitePath == null ? Collections.<Path>emptyList() : Collections.singletonList(suitePath), suiteDirectory, caseIds, tags, excludeTags, runId, false,
                rerunFailed, dryRun, failFast, outputDirectory, "selected", defaultCiOutputs(), "reject", false, false);
    }

    ExecutionOptions(String command, Path configPath, List<Path> suitePaths, Path suiteDirectory,
                             Set<String> caseIds, Set<String> tags, Set<String> excludeTags, String runId,
                             boolean all, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                             String validationScope, Set<String> ciOutputs, String concurrencyMode,
                             boolean updateSnapshot) {
        this(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags, runId, all, rerunFailed, dryRun, failFast,
                outputDirectory, validationScope, ciOutputs, concurrencyMode, updateSnapshot, false);
    }

    ExecutionOptions(String command, Path configPath, List<Path> suitePaths, Path suiteDirectory,
                             Set<String> caseIds, Set<String> tags, Set<String> excludeTags, String runId,
                             boolean all, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                             String validationScope, Set<String> ciOutputs, String concurrencyMode,
                             boolean updateSnapshot, boolean profile) {
        this(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags, runId, all, rerunFailed, dryRun,
                failFast, outputDirectory, validationScope, ciOutputs, concurrencyMode,
                updateSnapshot, profile, "", "", null);
    }

    ExecutionOptions(String command, Path configPath, List<Path> suitePaths, Path suiteDirectory,
                             Set<String> caseIds, Set<String> tags, Set<String> excludeTags, String runId,
                             boolean all, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                             String validationScope, Set<String> ciOutputs, String concurrencyMode,
                             boolean updateSnapshot, boolean profile, String debugTargetType, String debugTargetId, Path debugInput) {
        this(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags, runId, all, rerunFailed,
                dryRun, failFast, outputDirectory, validationScope, ciOutputs, concurrencyMode,
                updateSnapshot, profile, debugTargetType, debugTargetId, debugInput, null, null, null, null, null,
                null, null, null, null, null);
    }

    ExecutionOptions(String command, Path configPath, List<Path> suitePaths, Path suiteDirectory,
                             Set<String> caseIds, Set<String> tags, Set<String> excludeTags, String runId,
                             boolean all, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                             String validationScope, Set<String> ciOutputs, String concurrencyMode,
                             boolean updateSnapshot, boolean profile, String debugTargetType, String debugTargetId, Path debugInput,
                             Path loadScenario, String loadUsers, String loadArrivalRate, String loadWarmup, String loadRampUp,
                             String loadDuration, String loadRampDown, String loadThinkTime, String loadMaxConcurrent,
                             String loadOverloadPolicy) {
        this(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags, runId, all, rerunFailed,
                dryRun, failFast, outputDirectory, validationScope, ciOutputs, concurrencyMode,
                updateSnapshot, profile, debugTargetType, debugTargetId, debugInput, loadScenario, loadUsers,
                loadArrivalRate, loadWarmup, loadRampUp, loadDuration, loadRampDown, loadThinkTime, loadMaxConcurrent,
                loadOverloadPolicy, null);
    }

    ExecutionOptions(String command, Path configPath, List<Path> suitePaths, Path suiteDirectory,
                             Set<String> caseIds, Set<String> tags, Set<String> excludeTags, String runId,
                             boolean all, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                             String validationScope, Set<String> ciOutputs, String concurrencyMode,
                             boolean updateSnapshot, boolean profile, String debugTargetType, String debugTargetId, Path debugInput,
                             Path loadScenario, String loadUsers, String loadArrivalRate, String loadWarmup, String loadRampUp,
                             String loadDuration, String loadRampDown, String loadThinkTime, String loadMaxConcurrent,
                             String loadOverloadPolicy, String environment) {
        this(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags, runId, all, rerunFailed,
                dryRun, failFast, outputDirectory, validationScope, ciOutputs, concurrencyMode,
                updateSnapshot, profile, debugTargetType, debugTargetId, debugInput, loadScenario, loadUsers,
                loadArrivalRate, loadWarmup, loadRampUp, loadDuration, loadRampDown, loadThinkTime, loadMaxConcurrent,
                loadOverloadPolicy, environment, Collections.<String>emptyList(), false);
    }

    ExecutionOptions(String command, Path configPath, List<Path> suitePaths, Path suiteDirectory,
                             Set<String> caseIds, Set<String> tags, Set<String> excludeTags, String runId,
                             boolean all, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                             String validationScope, Set<String> ciOutputs, String concurrencyMode,
                             boolean updateSnapshot, boolean profile, String debugTargetType, String debugTargetId, Path debugInput,
                             Path loadScenario, String loadUsers, String loadArrivalRate, String loadWarmup, String loadRampUp,
                             String loadDuration, String loadRampDown, String loadThinkTime, String loadMaxConcurrent,
                             String loadOverloadPolicy, String environment, List<String> variableOverrides,
                             boolean unsafeFailureDetails) {
        this(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags, runId, all, rerunFailed,
                dryRun, failFast, outputDirectory, validationScope, ciOutputs, concurrencyMode,
                updateSnapshot, profile, debugTargetType, debugTargetId, debugInput, loadScenario, loadUsers,
                loadArrivalRate, loadWarmup, loadRampUp, loadDuration, loadRampDown, loadThinkTime, loadMaxConcurrent,
                loadOverloadPolicy, environment, variableOverrides, unsafeFailureDetails, null);
    }

    ExecutionOptions(String command, Path configPath, List<Path> suitePaths, Path suiteDirectory,
                             Set<String> caseIds, Set<String> tags, Set<String> excludeTags, String runId,
                             boolean all, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                             String validationScope, Set<String> ciOutputs, String concurrencyMode,
                             boolean updateSnapshot, boolean profile, String debugTargetType, String debugTargetId, Path debugInput,
                             Path loadScenario, String loadUsers, String loadArrivalRate, String loadWarmup, String loadRampUp,
                             String loadDuration, String loadRampDown, String loadThinkTime, String loadMaxConcurrent,
                             String loadOverloadPolicy, String environment, List<String> variableOverrides,
                             boolean unsafeFailureDetails, ExecutionEventListener observer) {
        this.command = command;
        this.configPath = configPath;
        this.environment = environment;
        this.suitePaths = new ArrayList<Path>(suitePaths);
        this.suiteDirectory = suiteDirectory;
        this.caseIds = caseIds;
        this.tags = tags;
        this.excludeTags = excludeTags;
        this.runId = runId;
        this.all = all;
        this.rerunFailed = rerunFailed;
        this.dryRun = dryRun;
        this.failFast = failFast;
        this.outputDirectory = outputDirectory;
        this.validationScope = validationScope;
        this.ciOutputs = new LinkedHashSet<String>(ciOutputs);
        this.concurrencyMode = concurrencyMode;
        this.updateSnapshot = updateSnapshot;
        this.profile = profile;
        this.debugTargetType = debugTargetType == null ? "" : debugTargetType;
        this.debugTargetId = debugTargetId == null ? "" : debugTargetId;
        this.debugInput = debugInput;
        this.unsafeFailureDetails = unsafeFailureDetails;
        this.loadScenario = loadScenario;
        this.loadUsers = loadUsers;
        this.loadArrivalRate = loadArrivalRate;
        this.loadWarmup = loadWarmup;
        this.loadRampUp = loadRampUp;
        this.loadDuration = loadDuration;
        this.loadRampDown = loadRampDown;
        this.loadThinkTime = loadThinkTime;
        this.loadMaxConcurrent = loadMaxConcurrent;
        this.loadOverloadPolicy = loadOverloadPolicy;
        this.variableOverrides = Collections.unmodifiableList(new ArrayList<String>(variableOverrides));
        this.observer = observer;
        this.identitySeed = null;
    }

    private ExecutionOptions(ExecutionOptions source, ExecutionIdentitySeed identitySeed) {
        this.command = source.command;
        this.configPath = source.configPath;
        this.environment = source.environment;
        this.suitePaths = new ArrayList<Path>(source.suitePaths);
        this.suiteDirectory = source.suiteDirectory;
        this.caseIds = source.caseIds;
        this.tags = source.tags;
        this.excludeTags = source.excludeTags;
        this.runId = source.runId;
        this.all = source.all;
        this.rerunFailed = source.rerunFailed;
        this.dryRun = source.dryRun;
        this.failFast = source.failFast;
        this.outputDirectory = source.outputDirectory;
        this.validationScope = source.validationScope;
        this.ciOutputs = new LinkedHashSet<String>(source.ciOutputs);
        this.concurrencyMode = source.concurrencyMode;
        this.updateSnapshot = source.updateSnapshot;
        this.profile = source.profile;
        this.debugTargetType = source.debugTargetType;
        this.debugTargetId = source.debugTargetId;
        this.debugInput = source.debugInput;
        this.unsafeFailureDetails = source.unsafeFailureDetails;
        this.loadScenario = source.loadScenario;
        this.loadUsers = source.loadUsers;
        this.loadArrivalRate = source.loadArrivalRate;
        this.loadWarmup = source.loadWarmup;
        this.loadRampUp = source.loadRampUp;
        this.loadDuration = source.loadDuration;
        this.loadRampDown = source.loadRampDown;
        this.loadThinkTime = source.loadThinkTime;
        this.loadMaxConcurrent = source.loadMaxConcurrent;
        this.loadOverloadPolicy = source.loadOverloadPolicy;
        this.variableOverrides = source.variableOverrides;
        this.observer = source.observer;
        this.identitySeed = identitySeed;
    }

    /** Enables CLI presentation for a typed request while leaving the API default silent. */
    public ExecutionOptions withObserver(ExecutionEventListener listener) {
        return new ExecutionOptions(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags, runId,
                all, rerunFailed, dryRun, failFast, outputDirectory,
                validationScope, ciOutputs, concurrencyMode, updateSnapshot, profile, debugTargetType, debugTargetId,
                debugInput, loadScenario, loadUsers, loadArrivalRate, loadWarmup, loadRampUp, loadDuration,
                loadRampDown, loadThinkTime, loadMaxConcurrent, loadOverloadPolicy, environment, variableOverrides,
                unsafeFailureDetails, listener).withIdentitySeed(identitySeed);
    }

    /** Freezes an exact, already validated execution identity for subsequent lifecycle phases. */
    public ExecutionOptions withRunId(String value) {
        return new ExecutionOptions(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags,
                value, all, rerunFailed, dryRun, failFast, outputDirectory, validationScope, ciOutputs,
                concurrencyMode, updateSnapshot, profile, debugTargetType, debugTargetId, debugInput,
                loadScenario, loadUsers, loadArrivalRate, loadWarmup, loadRampUp, loadDuration, loadRampDown,
                loadThinkTime, loadMaxConcurrent, loadOverloadPolicy, environment, variableOverrides,
                unsafeFailureDetails, observer).withIdentitySeed(identitySeed);
    }

    public ExecutionOptions withIdentitySeed(ExecutionIdentitySeed value) {
        return new ExecutionOptions(this, value);
    }

    public void emitOutput(String message) {
        if (observer != null) observer.onEvent(ExecutionEvent.log(message));
    }
    public void emitEvent(ExecutionEvent event) { if (observer != null && event != null) observer.onEvent(event); }
    public boolean hasObserver() { return observer != null; }

    public void emitCaseLog(String runId, String caseId, String text) {
        if (observer != null && text != null && !text.isEmpty()) observer.onEvent(new ExecutionEvent(
                ExecutionEvent.Type.CASE_LOG, runId, caseId, null, null, null, null, text, null));
    }

    public ExecutionEventListener observer() { return observer; }

    /** Creates execution intent from typed callers. This factory accepts no argv or presentation settings. */
    public static ExecutionOptions forApi(String command, Path configPath, String environment,
                                          List<Path> suites, Path suiteDirectory, Set<String> caseIds,
                                          Set<String> tags, Set<String> excludeTags, String runId, boolean all,
                                          boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                                          String validationScope, String debugTargetType, String debugTargetId,
                                          Path debugInput, boolean unsafeFailureDetails, Path loadScenario,
                                          String loadUsers, String loadArrivalRate, String loadWarmup,
                                          String loadRampUp, String loadDuration, String loadRampDown,
                                          String loadThinkTime, String loadMaxConcurrent, String loadOverloadPolicy,
                                          List<String> variableOverrides) {
        if (!("run".equals(command) || "validate".equals(command) || "snapshot".equals(command)
                || "debug".equals(command) || "load".equals(command)))
            throw new IllegalArgumentException("Unsupported engine operation: " + command);
        return new ExecutionOptions(command, configPath == null ? Paths.get("config/config.yaml") : configPath,
                suites == null ? Collections.<Path>emptyList() : suites, suiteDirectory,
                caseIds == null ? Collections.<String>emptySet() : caseIds,
                tags == null ? Collections.<String>emptySet() : tags,
                excludeTags == null ? Collections.<String>emptySet() : excludeTags,
                runId == null ? "" : runId, all, rerunFailed, dryRun, failFast, outputDirectory,
                validationScope == null ? "selected" : validationScope,
                defaultCiOutputs(), "reject", false, false, debugTargetType, debugTargetId, debugInput,
                loadScenario, loadUsers, loadArrivalRate, loadWarmup, loadRampUp, loadDuration,
                loadRampDown, loadThinkTime, loadMaxConcurrent, loadOverloadPolicy, environment,
                variableOverrides == null ? Collections.<String>emptyList() : variableOverrides,
                unsafeFailureDetails);
    }

    /** Applies Run-only execution policies to a typed API request. */
    public ExecutionOptions withRunPolicies(Set<String> outputs, String concurrency, boolean profileEnabled) {
        if (!"run".equals(command)) throw new IllegalStateException("Run policies apply only to Run requests");
        return new ExecutionOptions(command, configPath, suitePaths, suiteDirectory, caseIds, tags, excludeTags, runId,
                all, rerunFailed, dryRun, failFast, outputDirectory, validationScope,
                outputs == null ? defaultCiOutputs() : outputs, concurrency == null ? "reject" : concurrency,
                updateSnapshot, profileEnabled, debugTargetType, debugTargetId, debugInput, loadScenario, loadUsers,
                loadArrivalRate, loadWarmup, loadRampUp, loadDuration, loadRampDown, loadThinkTime, loadMaxConcurrent,
                loadOverloadPolicy, environment, variableOverrides, unsafeFailureDetails).withIdentitySeed(identitySeed);
    }

    public String command() { return command; }
    public Path configPath() { return configPath; }
    /** Explicit --env selector; null means use the profile config's declared default. */
    public String environment() { return environment; }
    public String environmentSelector() { return environment; }
    public Path suitePath() { return suitePaths.isEmpty() ? null : suitePaths.get(0); }
    public List<Path> suitePaths() { return Collections.unmodifiableList(suitePaths); }
    public Path suiteDirectory() { return suiteDirectory; }
    public Set<String> caseIds() { return caseIds; }
    public Set<String> tags() { return tags; }
    public Set<String> excludeTags() { return excludeTags; }
    public String runId() { return runId; }
    public ExecutionIdentitySeed identitySeed() { return identitySeed; }
    public boolean all() { return all; }
    public boolean rerunFailed() { return rerunFailed; }
    public boolean dryRun() { return dryRun; }
    public boolean failFast() { return failFast; }
    public Path outputDirectory() { return outputDirectory; }
    public String validationScope() { return validationScope; }
    public Set<String> ciOutputs() { return Collections.unmodifiableSet(ciOutputs); }
    public String concurrencyMode() { return concurrencyMode; }
    public boolean updateSnapshot() { return updateSnapshot; }
    public boolean profile() { return profile; }
    public String debugTargetType() { return debugTargetType; }
    public String debugTargetId() { return debugTargetId; }
    public Path debugInput() { return debugInput; }
    public boolean unsafeFailureDetails() { return unsafeFailureDetails; }
    public List<String> variableOverrides() { return variableOverrides; }
    public List<String> setOverrides() { return variableOverrides; }
    public boolean loadDebug() { return "load".equals(command) && !debugTargetType.isEmpty(); }
    public Path loadScenario() { return loadScenario; }
    public String loadUsers() { return loadUsers; }
    public String loadArrivalRate() { return loadArrivalRate; }
    public String loadWarmup() { return loadWarmup; }
    public String loadRampUp() { return loadRampUp; }
    public String loadDuration() { return loadDuration; }
    public String loadRampDown() { return loadRampDown; }
    public String loadThinkTime() { return loadThinkTime; }
    public String loadMaxConcurrent() { return loadMaxConcurrent; }
    public String loadOverloadPolicy() { return loadOverloadPolicy; }

    private static Set<String> defaultCiOutputs() {
        return new LinkedHashSet<String>(java.util.Arrays.asList("junit", "json"));
    }

    public boolean matches(TestCase testCase) {
        boolean caseMatches = caseIds.isEmpty() || caseIds.contains(testCase.caseId());
        boolean tagMatches = tags.isEmpty() || testCase.tags().stream().anyMatch(tags::contains);
        boolean excluded = testCase.tags().stream().anyMatch(excludeTags::contains);
        return caseMatches && tagMatches && !excluded;
    }

}
