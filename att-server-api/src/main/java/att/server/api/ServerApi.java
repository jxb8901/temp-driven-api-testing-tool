package att.server.api;

/** Stable constants for the public ATT Server REST and SSE protocol. */
public final class ServerApi {
    public static final String VERSION = "1";
    public static final String PREFIX = "/api/v1";
    public static final String HEALTH = PREFIX + "/health";
    public static final String SERVER_VERSION = PREFIX + "/version";
    public static final String PACKAGES = PREFIX + "/packages";
    public static final String PACKAGE_RESOURCES = PACKAGES + "/{packageId}/resources";
    public static final String PACKAGE_RESOURCE = PACKAGE_RESOURCES + "/{kind}/{resourceId}";
    public static final String PACKAGE_RESOURCE_SOURCE = PACKAGE_RESOURCE + "/source";
    public static final String PACKAGE_RESOURCE_DEBUG_FORM = PACKAGE_RESOURCE + "/debug-form";
    public static final String PACKAGE_RESOURCE_QUICK_LOAD_FORM = PACKAGE_RESOURCE + "/quick-load-form";
    public static final String PACKAGE_CONFIGURATION = PACKAGES + "/{packageId}/configuration";
    public static final String PACKAGE_CONFIGURATION_EFFECTIVE = PACKAGE_CONFIGURATION + "/effective";
    public static final String PACKAGE_CONFIGURATION_COMPARE = PACKAGE_CONFIGURATION + "/compare";
    public static final String JOBS = PREFIX + "/jobs";
    public static final String DEBUG_DRAFTS = PREFIX + "/drafts/debug";
    public static final String QUICK_LOAD_DRAFTS = PREFIX + "/drafts/quick-load";
    public static final String EVENTS_MEDIA_TYPE = "text/event-stream";
    private ServerApi() { }
}
