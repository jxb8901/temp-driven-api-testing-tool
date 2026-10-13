package att.remote.config;

import att.remote.RemoteException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Loads the user-owned ~/.att/servers.yaml connection profile, never a package config. */
public final class ServerProfileLoader {
    private static final Pattern URI_SCHEME=Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*$",Pattern.DOTALL);
    private ServerProfileLoader() { }

    public static ServerProfile resolve(String selected) throws RemoteException {
        return resolve(selected,System.getenv("ATT_SERVER"),profilePath(),false);
    }
    public static ServerProfile resolve(String selected,String environmentSelection,Path file) throws RemoteException {
        return resolve(selected,environmentSelection,file,false);
    }
    @SuppressWarnings("unchecked")
    public static ServerProfile resolve(String selected,String environmentSelection,Path file,boolean noAuth) throws RemoteException {
        String choice=nonBlank(selected);
        if(choice==null)choice=nonBlank(environmentSelection);
        if(choice!=null&&isUrlLike(choice))return directUrl(choice,noAuth);

        Map<String,Object> root=readProfiles(file);
        String name=choice;
        if(name==null&&root!=null&&root.get("default") instanceof String)name=nonBlank((String)root.get("default"));
        if(name==null)throw new RemoteException("No ATT Server selected. Use --server <profile|url>, set ATT_SERVER, or configure a default profile");

        Object servers=root==null?null:root.get("servers");
        Object configured=servers instanceof Map?((Map<?,?>)servers).get(name):null;
        if(!(configured instanceof Map))throw new RemoteException("Unknown ATT Server profile: "+name);
        Map<String,Object> profile=(Map<String,Object>)configured;
        String url=string(profile.get("url"));
        if(url==null||url.trim().isEmpty())throw new RemoteException("ATT Server profile '"+name+"' has no URL");

        String username=null,passwordEnv=null,authType="auto";
        Object auth=profile.get("auth");
        if(auth!=null&&!(auth instanceof Map))throw new RemoteException("The selected Server profile auth must be a mapping");
        if(auth instanceof Map) {
            Map<?,?> settings=(Map<?,?>)auth;
            if(settings.containsKey("password")||settings.containsKey("passwordValue"))throw new RemoteException("Server profiles cannot store passwords; use passwordEnv or a secure prompt");
            Object type=settings.get("type");
            if(type!=null)authType=String.valueOf(type).toLowerCase(Locale.ROOT);
            if(!"auto".equals(authType)&&!"basic".equals(authType)&&!"none".equals(authType))throw new RemoteException("Unsupported ATT Remote auth.type '"+authType+"'; use auto, basic, or none");
            username=string(settings.get("username"));passwordEnv=string(settings.get("passwordEnv"));
            if(passwordEnv!=null&&!passwordEnv.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new RemoteException("passwordEnv must be an environment variable name");
        }
        if(noAuth)authType="none";
        String normalized=validateUrl(url);
        requireHttpsForBasic(normalized,authType);
        return new ServerProfile(name,normalized,username,passwordEnv,authType);
    }

    private static ServerProfile directUrl(String value,boolean noAuth)throws RemoteException {
        String normalized=validateUrl(value);
        return new ServerProfile(null,normalized,null,null,noAuth?"none":"auto");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> readProfiles(Path file)throws RemoteException {
        if(!Files.exists(file))return null;
        try(InputStream input=Files.newInputStream(file)) {
            Object loaded=new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if(loaded!=null&&!(loaded instanceof Map))throw new RemoteException("Server profile must be a YAML mapping");
            return (Map<String,Object>)loaded;
        } catch(RemoteException e) { throw e; }
        catch(Exception e) { throw new RemoteException("Unable to read ATT Server profiles at "+file+": "+safe(e)); }
    }
    private static boolean isUrlLike(String value) {
        return URI_SCHEME.matcher(value).matches();
    }
    private static String validateUrl(String value)throws RemoteException {
        try {
            URI uri=new URI(value.trim());
            String scheme=uri.getScheme();
            if(scheme==null||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
                throw invalidUrl();
            if(!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))
                throw new RemoteException("ATT Server URL scheme must be HTTP or HTTPS");
            String normalized=uri.toASCIIString().replaceAll("/+$","");
            if(normalized.equalsIgnoreCase(scheme+"://"+uri.getRawAuthority()))normalized=scheme.toLowerCase(Locale.ROOT)+"://"+uri.getRawAuthority();
            return normalized;
        } catch(RemoteException e) { throw e; }
        catch(Exception e) { throw invalidUrl(); }
    }
    private static void requireHttpsForBasic(String url,String authType)throws RemoteException {
        if("basic".equals(authType)&&!url.regionMatches(true,0,"https://",0,8))throw new RemoteException("Basic authentication requires HTTPS");
    }
    private static RemoteException invalidUrl() {
        return new RemoteException("ATT Server URL must be a valid HTTP(S) base URL without credentials, query, or fragment");
    }
    private static String nonBlank(String value) {
        if(value==null)return null;
        String trimmed=value.trim();return trimmed.isEmpty()?null:trimmed;
    }
    public static Path profilePath() { return profilePath(System.getenv()); }
    static Path profilePath(Map<String,String> environment) {
        String configured=environment.get("ATT_REMOTE_CONFIG");
        if(configured!=null&&!configured.trim().isEmpty())return Paths.get(configured).toAbsolutePath().normalize();
        return Paths.get(System.getProperty("user.home"),".att","servers.yaml");
    }
    private static String string(Object value) { return value==null?null:String.valueOf(value); }
    private static String safe(Exception e) {
        String message=e.getMessage();return message==null?e.getClass().getSimpleName():message.replaceAll("(?i)(password|authorization)\\s*[:=]\\s*[^ ,]+","$1=[REDACTED_SECRET]");
    }
}
