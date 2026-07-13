/* Author: Jeffrey + ChatGPT */
package att.config;

/** Immutable SSH execution target for one global tool namespace or tool group. */
public final class SshConfig {
    private final String host;
    private final String user;
    private final int port;
    private final String identityFile;

    public SshConfig(String host, String user, int port, String identityFile) {
        this.host = host;
        this.user = user;
        this.port = port;
        this.identityFile = identityFile == null ? "" : identityFile;
    }

    public String host() { return host; }
    public String user() { return user; }
    public int port() { return port; }
    public String identityFile() { return identityFile; }
    public String destination() { return user + "@" + host; }
}
