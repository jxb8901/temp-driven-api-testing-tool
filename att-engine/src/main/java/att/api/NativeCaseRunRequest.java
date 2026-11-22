package att.api;

import att.testcase.TestCaseDefinitionResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Typed RUN request for selected cases from one native Test Case Definition. */
public final class NativeCaseRunRequest extends AttRequest {
    private final String packageId;
    private final String definitionId;
    private final List<String> resolvedCaseIds;
    private final boolean dryRun;
    private final boolean failFast;
    private final ExecutionEventListener observer;

    public NativeCaseRunRequest(Path packageRoot, Path configPath, String environment, Path outputDirectory,
            String runId, String packageId, String definitionId, List<String> resolvedCaseIds,
            boolean dryRun, boolean failFast, ExecutionEventListener observer) {
        super(packageRoot, configPath, environment, outputDirectory, runId);
        if (packageId == null || packageId.trim().isEmpty()) throw new IllegalArgumentException("packageId is required");
        if (definitionId == null || definitionId.trim().isEmpty() || definitionId.length() > 126)
            throw new IllegalArgumentException("definitionId must be a non-empty native Definition ID");
        if (resolvedCaseIds == null || resolvedCaseIds.isEmpty())
            throw new IllegalArgumentException("At least one resolved native Case ID is required");
        if (resolvedCaseIds.size() > TestCaseDefinitionResolver.MAX_PREVIEW_CASES)
            throw new IllegalArgumentException("Native RUN supports at most " + TestCaseDefinitionResolver.MAX_PREVIEW_CASES + " selected Cases");
        Set<String> seen = new LinkedHashSet<String>();
        List<String> copy = new ArrayList<String>(resolvedCaseIds.size());
        for (String id : resolvedCaseIds) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
                throw new IllegalArgumentException("resolvedCaseIds entries must be path-safe local Case IDs");
            if (!seen.add(id)) throw new IllegalArgumentException("resolvedCaseIds must not contain duplicates: " + id);
            copy.add(id);
        }
        this.packageId = packageId;
        this.definitionId = definitionId;
        this.resolvedCaseIds = Collections.unmodifiableList(copy);
        this.dryRun = dryRun;
        this.failFast = failFast;
        this.observer = observer;
    }

    public String packageId() { return packageId; }
    public String definitionId() { return definitionId; }
    public List<String> resolvedCaseIds() { return resolvedCaseIds; }
    public boolean dryRun() { return dryRun; }
    public boolean failFast() { return failFast; }
    public ExecutionEventListener observer() { return observer; }
}
