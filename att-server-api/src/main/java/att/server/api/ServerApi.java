package att.server.api;

/** Stable constants for the public ATT Server REST and SSE protocol. */
public final class ServerApi {
    public static final String VERSION = "1";
    public static final String PREFIX = "/api/v1";
    public static final String HEALTH = PREFIX + "/health";
    public static final String SERVER_VERSION = PREFIX + "/version";
    public static final String PACKAGES = PREFIX + "/packages";
    public static final String JOBS = PREFIX + "/jobs";
    public static final String EVENTS_MEDIA_TYPE = "text/event-stream";
    private ServerApi() { }
}
