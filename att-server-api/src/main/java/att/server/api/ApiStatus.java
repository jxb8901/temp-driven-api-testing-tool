package att.server.api;

/** Terminal and active states returned by the v1 job API. */
public enum ApiStatus {
    QUEUED, PREPARING, RUNNING, CANCEL_REQUESTED, PASS, FAIL, ERROR, INVALID, CANCELLED;
    public boolean isTerminal() { return this == PASS || this == FAIL || this == ERROR || this == INVALID || this == CANCELLED; }
    public static ApiStatus parse(String value) {
        if (value == null) throw new IllegalArgumentException("Job status is missing");
        try { return valueOf(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unsupported ATT Server job status: " + value); }
    }
}
