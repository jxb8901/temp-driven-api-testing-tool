package att.load;

/** Safe terminal exception for a mandatory pre-scheduler LOAD readiness failure. */
public final class LoadResourcePreparationException extends Exception {
    public LoadResourcePreparationException(String message, Throwable cause) { super(message, cause); }
}
