package att.load;

/** Safe terminal exception for a mandatory pre-scheduler LOAD readiness failure. */
public final class LoadResourcePreparationException extends Exception {
    public static final String RESOURCE_LIMIT = "LOAD_RESOURCE_PREPARATION_LIMIT";
    public static final String DEADLINE = "LOAD_PREPARATION_TIMEOUT";
    public static final String FAILED = "LOAD_RESOURCE_PREPARATION_FAILED";

    private final String diagnosticCode;

    public LoadResourcePreparationException(String message, Throwable cause) {
        this(message, FAILED, cause);
    }

    public LoadResourcePreparationException(String message, String diagnosticCode, Throwable cause) {
        super(message, cause);
        this.diagnosticCode = diagnosticCode == null || diagnosticCode.trim().isEmpty() ? FAILED : diagnosticCode;
    }

    public String diagnosticCode() { return diagnosticCode; }
}
