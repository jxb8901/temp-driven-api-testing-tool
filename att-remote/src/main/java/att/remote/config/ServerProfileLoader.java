package att.remote.config;

import att.remote.RemoteException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** Loads the user-owned ~/.att/servers.yaml connection profile, never a package config. */
public final class ServerProfileLoader {
    private ServerProfileLoader() { }
    @SuppressWarnings("unchecked")
    public static ServerProfile resolve(String selected) throws RemoteException {
        return resolve(selected,System.getenv("ATT_SERVER"),profilePath());
    }
    @SuppressWarnings("unchecked")
    public static ServerProfile resolve(String selected,String envUrl,Path file) throws RemoteException {
        Map<String,Object> root=null;
        if(Files.exists(file)) {
            try(InputStream input=Files.newInputStream(file)) {
                Object loaded=new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
                if(loaded!=null && !(loaded instanceof Map)) throw new RemoteException("Server profile must be a YAML mapping");
                root=(Map<String,Object>)loaded;
            } catch(RemoteException e) { throw e; }
            catch(Exception e) { throw new RemoteException("Unable to read ATT Server profiles at " + file + ": " + safe(e)); }
        }
        String name=selected;
        if(name==null && root!=null && root.get("default") instanceof String) name=(String)root.get("default");
        Map<String,Object> profile=null;
        if(name!=null) {
            Object servers=root==null?null:root.get("servers");
            if(!(servers instanceof Map) || !(((Map<?,?>)servers).get(name) instanceof Map)) throw new RemoteException("Unknown ATT Server profile: " + name);
            profile=(Map<String,Object>)((Map<?,?>)servers).get(name);
        }
        String url=selected!=null && profile!=null ? string(profile.get("url")) : envUrl;
        if(url==null && profile!=null && profile.get("url") instanceof String) url=(String)profile.get("url");
        if(url==null) throw new RemoteException("No ATT Server selected. Set ATT_SERVER or configure ~/.att/servers.yaml");
        String username=null,passwordEnv=null;boolean basic=false;
        if(profile!=null) {
            Object auth=profile.get("auth");
            if(auth!=null && !(auth instanceof Map)) throw new RemoteException("The selected Server profile auth must be a mapping");
            if(auth instanceof Map) {
                Map<?,?> a=(Map<?,?>)auth;
                if(a.containsKey("password")||a.containsKey("passwordValue")) throw new RemoteException("Server profiles cannot store passwords; use passwordEnv or a secure prompt");
                Object type=a.get("type");
                if(type!=null && !"basic".equalsIgnoreCase(String.valueOf(type))) throw new RemoteException("Only Basic authentication is supported by ATT Remote v1");
                basic=type==null || "basic".equalsIgnoreCase(String.valueOf(type));
                username=string(a.get("username")); passwordEnv=string(a.get("passwordEnv"));
                if(passwordEnv!=null && !passwordEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new RemoteException("passwordEnv must be an environment variable name");
            }
        }
        try {
            java.net.URI uri=new java.net.URI(url);
            if(uri.getHost()==null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new RemoteException("ATT_SERVER must be an HTTP(S) base URL without credentials, query, or fragment");
            if((basic || username!=null || passwordEnv!=null) && !"https".equalsIgnoreCase(uri.getScheme())) throw new RemoteException("Basic authentication requires HTTPS");
        } catch(java.net.URISyntaxException e) { throw new RemoteException("Invalid ATT Server URL"); }
        return new ServerProfile(name,url.replaceAll("/+$",""),username,passwordEnv,basic);
    }
    private static Path profilePath() {
        String configured=System.getenv("ATT_SERVER_CONFIG");
        if(configured!=null && !configured.trim().isEmpty()) return Paths.get(configured).toAbsolutePath().normalize();
        return Paths.get(System.getProperty("user.home"),".att","servers.yaml");
    }
    private static String string(Object v) { return v==null?null:String.valueOf(v); }
    private static String safe(Exception e) { String m=e.getMessage(); return m==null?e.getClass().getSimpleName():m.replaceAll("(?i)(password|authorization)\\s*[:=]\\s*[^ ,]+","$1=[REDACTED_SECRET]"); }
}
