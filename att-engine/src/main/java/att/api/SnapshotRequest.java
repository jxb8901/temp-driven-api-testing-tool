package att.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Typed testcase snapshot selection intent. */
public final class SnapshotRequest extends AttRequest {
    private final List<Path> suites; private final Path suiteDirectory; private final Set<String> caseIds;
    private final boolean all;
    public SnapshotRequest(Path packageRoot, Path configPath, String environment, List<Path> suites, Path suiteDirectory, Set<String> caseIds, boolean all) {
        super(packageRoot, configPath, environment, null, null);
        this.suites=RunRequest.immutableList(suites); this.suiteDirectory=suiteDirectory; this.caseIds=RunRequest.immutableSet(caseIds); this.all=all;
    }
    public List<Path> suites() { return suites; } public Path suiteDirectory() { return suiteDirectory; } public Set<String> caseIds() { return caseIds; } public boolean all() { return all; }
}
