/* Author: Jeffrey + ChatGPT */
package att.config;

/** Immutable SSH execution target for one global tool namespace or tool group. */
public final class SshConfig {
    private final String host;
    private final String user;
    private final int port;
    private final String identityFile;
    private final boolean identityFileFromEnvironment;

    public SshConfig(String host, String user, int port, String identityFile) {
        this(host, user, port, identityFile, false);
    }

    public SshConfig(String host, String user, int port, String identityFile, boolean identityFileFromEnvironment) {
        this.host = host;
        this.user = user;
        this.port = port;
        this.identityFile = identityFile == null ? "" : identityFile;
        this.identityFileFromEnvironment = identityFileFromEnvironment;
    }

    public String host() { return host; }
    public String user() { return user; }
    public int port() { return port; }
    public String identityFile() { return identityFile; }
    public boolean identityFileFromEnvironment() { return identityFileFromEnvironment; }
    public String destination() { return user + "@" + host; }
}
