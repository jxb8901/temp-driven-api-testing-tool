package att.load;

/** Shared preparation limits used by LOAD Workers and the Server admission cap. */
public final class LoadPreparationBudget {
    public static final int PER_RUN_CONNECTIONS = 64;
    public static final int WORKER_JVM_CONNECTIONS = 256;
    public static final int MAX_SERVER_LOAD_WORKERS = WORKER_JVM_CONNECTIONS / PER_RUN_CONNECTIONS;

    private LoadPreparationBudget() { }
}
