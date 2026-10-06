package att.api;

/** Command-neutral typed entry points for reusable ATT execution. */
public interface AttService {
    RunResult run(RunRequest request) throws Exception;
    DebugResult debug(DebugRequest request) throws Exception;
    LoadResult load(LoadRequest request) throws Exception;
    ValidateResult validate(ValidateRequest request) throws Exception;
    SnapshotResult snapshot(SnapshotRequest request) throws Exception;
}
