package att.remote.config;

import att.remote.RemoteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ServerProfileLoaderTest {
    @TempDir Path temp;
    @Test void explicitSelectionOverridesEnvironmentAndEnvironmentOverridesDefault() throws Exception {
        Path file=temp.resolve("servers.yaml");Files.write(file,(
                "default: sit\nservers:\n  sit:\n    url: https://sit.example.com\n    auth:\n      type: basic\n      username: ci-user\n      passwordEnv: CI_PASSWORD\n  uat:\n    url: https://uat.example.com\n").getBytes("UTF-8"));
        ServerProfile environment=ServerProfileLoader.resolve(null,"uat",file);
        assertEquals("uat",environment.name);assertEquals("https://uat.example.com",environment.url);assertEquals("auto",environment.authType);
        ServerProfile explicit=ServerProfileLoader.resolve("uat","https://override.example.com",file);
        assertEquals("https://uat.example.com",explicit.url);assertEquals("uat",explicit.name);
        ServerProfile defaultProfile=ServerProfileLoader.resolve(null,null,file);
        assertEquals("sit",defaultProfile.name);assertEquals("basic",defaultProfile.authType);
    }
    @Test void explicitAndEnvironmentUrlsDoNotInheritUnrelatedProfileCredentials() throws Exception {
        Path file=temp.resolve("servers.yaml");Files.write(file,("default: sit\nservers:\n  sit:\n    url: https://sit.example.com/att\n    auth:\n      type: basic\n      username: sit-user\n      passwordEnv: SIT_SECRET\n").getBytes("UTF-8"));
        ServerProfile environment=ServerProfileLoader.resolve(null,"https://other.example.com:8443/att/",file);
        assertNull(environment.name);assertEquals("https://other.example.com:8443/att",environment.url);assertEquals("auto",environment.authType);assertNull(environment.username);assertNull(environment.passwordEnv);
        ServerProfile explicit=ServerProfileLoader.resolve("http://localhost:8080/att","sit",file);
        assertNull(explicit.name);assertEquals("http://localhost:8080/att",explicit.url);assertNull(explicit.username);assertEquals("auto",explicit.authType);
    }
    @Test void namedEnvironmentSelectionUsesItsOwnProfileAuthentication() throws Exception {
        Path file=temp.resolve("servers.yaml");Files.write(file,("default: sit\nservers:\n  sit:\n    url: https://sit.example.com\n  uat:\n    url: https://uat.example.com/att\n    auth:\n      type: auto\n      username: uat-user\n      passwordEnv: UAT_SECRET\n").getBytes("UTF-8"));
        ServerProfile environment=ServerProfileLoader.resolve(null,"uat",file);
        assertEquals("uat",environment.name);assertEquals("https://uat.example.com/att",environment.url);assertTrue(environment.autoAuth);assertEquals("uat-user",environment.username);assertEquals("UAT_SECRET",environment.passwordEnv);
    }
    @Test void urlSelectionRejectsInvalidSchemesCredentialsQueriesAndFragments() throws Exception {
        Path file=temp.resolve("absent.yaml");
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve("ftp://files.example.com/att",null,file));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve("https://user:secret@example.com/att",null,file));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve("https://example.com/att?x=1",null,file));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve("https://example.com/att#top",null,file));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve(null,null,file));
    }
    @Test void defaultsToAutoAndValidatesAuthTypesAndBasicHttps() throws Exception {
        Path auto=temp.resolve("auto.yaml");Files.write(auto,("default: local\nservers:\n  local:\n    url: http://localhost:8080/att\n").getBytes("UTF-8"));
        assertEquals("auto",ServerProfileLoader.resolve(null,null,auto).authType);
        Path basic=temp.resolve("basic-http.yaml");Files.write(basic,("default: local\nservers:\n  local:\n    url: http://localhost:8080/att\n    auth:\n      type: basic\n").getBytes("UTF-8"));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve(null,null,basic));
        assertEquals("none",ServerProfileLoader.resolve(null,null,basic,true).authType);
        Path unsupported=temp.resolve("unsupported.yaml");Files.write(unsupported,("default: local\nservers:\n  local:\n    url: https://localhost:8443/att\n    auth:\n      type: oidc\n").getBytes("UTF-8"));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve(null,null,unsupported));
    }
    @Test void noAuthAllowsHttpProfileWithoutReadingConfiguredBasicCredentials() throws Exception {
        Path file=temp.resolve("anonymous.yaml");Files.write(file,("default: local\nservers:\n  local:\n    url: http://localhost:8080\n    auth:\n      type: basic\n      username: configured-user\n      passwordEnv: LOCAL_SECRET\n").getBytes("UTF-8"));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve(null,null,file));
        ServerProfile anonymous=ServerProfileLoader.resolve(null,null,file,true);assertEquals("http://localhost:8080",anonymous.url);assertEquals("configured-user",anonymous.username);
    }
    @Test void rejectsStoredPasswordsAndBasicOverHttp() throws Exception {
        Path password=temp.resolve("password.yaml");Files.write(password,("default: ci\nservers:\n  ci:\n    url: https://ci.example.com\n    auth:\n      type: basic\n      username: ci\n      password: unsafe\n").getBytes("UTF-8"));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve(null,null,password));
        Path http=temp.resolve("http.yaml");Files.write(http,("default: ci\nservers:\n  ci:\n    url: http://ci.example.com\n    auth:\n      type: basic\n      username: ci\n").getBytes("UTF-8"));
        assertThrows(RemoteException.class,()->ServerProfileLoader.resolve(null,null,http));
    }
    @Test void remoteProfilePathUsesDedicatedEnvironmentVariable() {
        Path serverConfig=temp.resolve("att-server.yaml"),remoteConfig=temp.resolve("remote-servers.yaml");
        java.util.Map<String,String> environment=new java.util.HashMap<>();environment.put("ATT_SERVER_CONFIG",serverConfig.toString());environment.put("ATT_REMOTE_CONFIG",remoteConfig.toString());
        assertEquals(remoteConfig.toAbsolutePath().normalize(),ServerProfileLoader.profilePath(environment));
    }
}
