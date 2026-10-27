package att.load;

/** Shared preparation limits used by LOAD Workers and the Server admission cap. */
public final class LoadPreparationBudget {
    public static final int PER_RUN_CONNECTIONS = 64;
    public static final int WORKER_JVM_CONNECTIONS = 256;
    public static final int MAX_SERVER_LOAD_WORKERS = WORKER_JVM_CONNECTIONS / PER_RUN_CONNECTIONS;

    private LoadPreparationBudget() { }

    /** Typed per-run admission failure; callers should preserve this code through Worker diagnostics. */
    public static void requireWithinPerRunLimit(int required) throws LoadResourcePreparationException {
        if (required > PER_RUN_CONNECTIONS)
            throw new LoadResourcePreparationException(
                    "LOAD resource preparation exceeded its bounded per-run connection budget",
                    LoadResourcePreparationException.RESOURCE_LIMIT, null);
    }
}
