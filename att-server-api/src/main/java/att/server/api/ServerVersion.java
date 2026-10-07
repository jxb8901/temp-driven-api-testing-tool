package att.server.api;

/** Server version response. Build metadata is informational; apiVersion controls compatibility. */
public final class ServerVersion {
    public String apiVersion;
    public String version;
    public String buildTime;
    public String gitCommit;
    public Integer javaMinimum;
    public ServerVersion() { }
}
