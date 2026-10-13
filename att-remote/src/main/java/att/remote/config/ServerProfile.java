package att.remote.config;

public final class ServerProfile {
    public final String name, url, username, passwordEnv, authType;
    public final boolean basicAuth, autoAuth;
    public ServerProfile(String name, String url, String username, String passwordEnv, boolean basicAuth) {
        this(name,url,username,passwordEnv,basicAuth?"basic":"none");
    }
    public ServerProfile(String name,String url,String username,String passwordEnv,String authType) {
        this.name=name;this.url=url;this.username=username;this.passwordEnv=passwordEnv;this.authType=authType;
        this.basicAuth="basic".equals(authType);this.autoAuth="auto".equals(authType);
    }
}
