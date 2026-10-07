package att.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Typed package or selected-dependency validation intent. */
public final class ValidateRequest extends AttRequest {
    private final List<Path> suites; private final Path suiteDirectory; private final Set<String> caseIds, tags, excludeTags;
    private final boolean all; private final String scope;
    public ValidateRequest(Path packageRoot, Path configPath, String environment, List<Path> suites, Path suiteDirectory,
                           Set<String> caseIds, Set<String> tags, Set<String> excludeTags, boolean all, String scope) {
        super(packageRoot, configPath, environment, null, null);
        this.suites=RunRequest.immutableList(suites); this.suiteDirectory=suiteDirectory; this.caseIds=RunRequest.immutableSet(caseIds);
        this.tags=RunRequest.immutableSet(tags); this.excludeTags=RunRequest.immutableSet(excludeTags); this.all=all;
        this.scope=scope == null ? "selected" : scope;
    }
    public List<Path> suites() { return suites; } public Path suiteDirectory() { return suiteDirectory; }
    public Set<String> caseIds() { return caseIds; } public Set<String> tags() { return tags; } public Set<String> excludeTags() { return excludeTags; }
    public boolean all() { return all; } public String scope() { return scope; }
}
