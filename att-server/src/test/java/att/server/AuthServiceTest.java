package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    @TempDir Path temp;

    @Test void bootstrapCreatesOnlyHashedAdminAndLoginCreatesOpaqueRevocableSession() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages"));Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("data")+"\n  javaExecutable: "+javaExecutable+"\n  auth:\n    mode: local\nworkers: {}\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    default: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);JobStore store=new JobStore(config);AuthService auth=new AuthService(config,store);
        assertTrue(auth.bootstrapRequired());Path tokenFile=config.dataDir.resolve("bootstrap-admin.token");String token=Files.readString(tokenFile).strip();
        assertEquals("admin",auth.bootstrap(token,"Admin","ATT Administrator","correct horse battery staple").get("loginId"));assertFalse(Files.exists(tokenFile));assertFalse(auth.bootstrapRequired());
        Map<String,Object> stored=store.authUser("admin");assertNotEquals("correct horse battery staple",stored.get("passwordHash"));assertTrue(((String)stored.get("passwordHash")).startsWith("pbkdf2-sha256$"));
        Map<String,Object> login=auth.login("ADMIN","correct horse battery staple","127.0.0.1");String sessionToken=(String)login.get("token");assertNotNull(sessionToken);assertEquals(43,sessionToken.length());
        String tokenHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sessionToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertNotNull(store.authSession(tokenHash,System.currentTimeMillis(),AuthService.IDLE_MILLIS),"Session data must remain server-side and survive store reopen/restart");
        Map<String,Object> safe=(Map<String,Object>)login.get("user");assertFalse(safe.containsKey("passwordHash"));assertFalse(safe.containsKey("token"));
        assertThrows(AuthService.AuthFailure.class,()->auth.login("admin","wrong password","127.0.0.1"));
        AuthService.AuthFailure lastAdmin=assertThrows(AuthService.AuthFailure.class,()->auth.disable("admin"));assertEquals(409,lastAdmin.status);
        auth.createUser("second-admin","Second Admin","another secure password","ADMIN");auth.disable("admin");assertNull(store.authSession(tokenHash,System.currentTimeMillis(),AuthService.IDLE_MILLIS),"Disabling a user revokes all existing sessions");
        assertThrows(AuthService.AuthFailure.class,()->auth.login("admin","correct horse battery staple","127.0.0.1"));
        store.close();
    }

    @Test void bootstrapTokenIsOneTimeAndLoginFailureDoesNotRevealAccountExistence() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages2"));Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("server2.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("data2")+"\n  javaExecutable: "+javaExecutable+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    default: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);JobStore store=new JobStore(config);AuthService auth=new AuthService(config,store);
        assertThrows(AuthService.AuthFailure.class,()->auth.bootstrap("wrong","owner","Owner","long secure password"));
        AuthService.AuthFailure missing=assertThrows(AuthService.AuthFailure.class,()->auth.login("missing","wrong password","127.0.0.1"));
        AuthService.AuthFailure disabledOrWrong=assertThrows(AuthService.AuthFailure.class,()->auth.login("missing","also wrong","127.0.0.1"));
        assertEquals(missing.status,disabledOrWrong.status);assertEquals(missing.code,disabledOrWrong.code);store.close();
    }
}
