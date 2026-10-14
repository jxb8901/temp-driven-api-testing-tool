package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    @TempDir Path temp;

    @Test void bootstrapCreatesOnlyHashedAdminAndLoginCreatesOpaqueRevocableSession() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages"));Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("data")+"\n  javaExecutable: "+javaExecutable+"\n  auth:\n    mode: local\nworkers: {}\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    default: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);JobStore store=new JobStore(config);AuthService auth=new AuthService(config,store);
        assertTrue(auth.bootstrapRequired());Path tokenFile=config.dataDir.resolve("bootstrap-admin.token");String token=Files.readString(tokenFile).strip();
        assertEquals("admin",auth.bootstrap(token,"Admin","ATT Administrator","correct horse battery staple","127.0.0.1").get("loginId"));assertFalse(Files.exists(tokenFile));assertFalse(auth.bootstrapRequired());
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
        for(int i=0;i<8;i++)assertThrows(AuthService.AuthFailure.class,()->auth.bootstrap("wrong","owner","Owner","long secure password","192.0.2.4"));
        AuthService.AuthFailure throttled=assertThrows(AuthService.AuthFailure.class,()->auth.bootstrap("wrong","owner","Owner","long secure password","192.0.2.4"));assertEquals(429,throttled.status);
        AuthService.AuthFailure oversized=assertThrows(AuthService.AuthFailure.class,()->auth.login("missing","x".repeat(100_000),"192.0.2.5"));assertEquals(401,oversized.status);
        for(int i=0;i<32;i++){final int user=i;AuthService.AuthFailure spray=assertThrows(AuthService.AuthFailure.class,()->auth.login("spray-user-"+user,"wrong password value","192.0.2.6"));assertEquals(401,spray.status);}
        AuthService.AuthFailure sprayed=assertThrows(AuthService.AuthFailure.class,()->auth.login("another-user","wrong password value","192.0.2.6"));assertEquals(429,sprayed.status);
        AuthService.AuthFailure missing=assertThrows(AuthService.AuthFailure.class,()->auth.login("missing","wrong password","127.0.0.1"));
        AuthService.AuthFailure disabledOrWrong=assertThrows(AuthService.AuthFailure.class,()->auth.login("missing","also wrong","127.0.0.1"));
        assertEquals(missing.status,disabledOrWrong.status);assertEquals(missing.code,disabledOrWrong.code);store.close();
    }

    @Test void concurrentBootstrapRequestsCanCreateOnlyOneAdministrator() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("parallel-packages"));Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name"," ").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=Files.writeString(temp.resolve("parallel-server.yaml"),"server:\n  dataDir: "+temp.resolve("parallel-data")+"\n  javaExecutable: "+javaExecutable+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    default: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);JobStore store=new JobStore(config);AuthService auth=new AuthService(config,store);String token=Files.readString(config.dataDir.resolve("bootstrap-admin.token")).strip();
        var pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> first=pool.submit(()->bootstrapAfter(start,auth,token,"admin-one"));
            Future<Boolean> second=pool.submit(()->bootstrapAfter(start,auth,token,"admin-two"));start.countDown();
            assertNotEquals(first.get(),second.get(),"Exactly one concurrent request may claim the bootstrap token");
            assertEquals(1,store.authUserCount());assertEquals(1,store.activeAdminCount());assertTrue(store.bootstrapClaimed());
        } finally {pool.shutdownNow();store.close();}
    }

    private static boolean bootstrapAfter(CountDownLatch start,AuthService auth,String token,String login)throws Exception {
        start.await();try{auth.bootstrap(token,login,login,"a secure bootstrap password","198.51.100.7");return true;}
        catch(AuthService.AuthFailure rejected){assertEquals(409,rejected.status);return false;}
    }
}
