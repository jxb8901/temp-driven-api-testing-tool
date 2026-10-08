package att.remote.config;

import att.remote.RemoteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ServerProfileLoaderTest {
    @TempDir Path temp;
    @Test void explicitProfileOverridesServerUrlButEnvironmentOverridesDefaultUrl() throws Exception {
        Path file=temp.resolve("servers.yaml");Files.write(file,(
                "default: sit\nservers:\n  sit:\n    url: https://sit.example.com\n    auth:\n      type: basic\n      username: ci-user\n      passwordEnv: CI_PASSWORD\n  uat:\n    url: https://uat.example.com\n").getBytes("UTF-8"));
        ServerProfile environment=ServerProfileLoader.resolve(null,"https://override.example.com",file);
        assertEquals("https://override.example.com",environment.url);assertEquals("ci-user",environment.username);assertEquals("CI_PASSWORD",environment.passwordEnv);
        ServerProfile explicit=ServerProfileLoader.resolve("uat","https://override.example.com",file);
        assertEquals("https://uat.example.com",explicit.url);
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
}
