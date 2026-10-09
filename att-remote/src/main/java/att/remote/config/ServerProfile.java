package att.remote.config;

public final class ServerProfile {
    public final String name, url, username, passwordEnv;
    public final boolean basicAuth;
    public ServerProfile(String name, String url, String username, String passwordEnv, boolean basicAuth) {
        this.name=name; this.url=url; this.username=username; this.passwordEnv=passwordEnv; this.basicAuth=basicAuth;
    }
}
