package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserPreferencesStoreTest {
    @TempDir Path temp;

    private ServerConfig config(Path data) throws Exception {
        Path packages=Files.createDirectories(temp.resolve("packages"));Path pkg=Files.createDirectories(packages.resolve("p"));
        Path file=Files.writeString(temp.resolve("server-"+data.getFileName()+".yaml"),"server:\n  dataDir: "+data+"\nworkers: {}\npackages:\n  allowedRoots: ["+packages+"]\n  entries:\n    p: "+pkg+"\n");
        return ServerConfig.load(file);
    }

    @Test void preferencesStayAccountScopedAcrossDisableAndLoginReuseDoesNotInheritThem() throws Exception {
        ServerConfig config=config(temp.resolve("preferences-data"));String subject;
        try(JobStore store=new JobStore(config)) {
            store.createAuthUser("jane","Jane","hash","USER","csrf");
            subject=store.localPreferenceSubject("jane");assertTrue(subject.startsWith("local:"));
            Map<String,Object> first=store.updateUserPreferences(subject,"zh-Hant",true,"dark",true,false);
            assertEquals("zh-Hant",first.get("language"));assertEquals("dark",first.get("theme"));assertEquals(1L,first.get("version"));
            store.setAuthActive("jane",false);assertNull(store.localPreferenceSubject("jane"),"Disabled accounts cannot resolve their preference identity");
            store.setAuthActive("jane",true);assertEquals(subject,store.localPreferenceSubject("jane"),"Re-enabling the same durable account retains its identity");
        }
        try(JobStore reopened=new JobStore(config)) {
            assertEquals(subject,reopened.localPreferenceSubject("jane"));
            assertEquals("zh-Hant",reopened.userPreferences(subject).get("language"),"Preferences survive reopening the Server database");
            assertEquals("dark",reopened.userPreferences(subject).get("theme"));
        }
        try(JobStore store=new JobStore(config)) {
            try(var connection=DriverManager.getConnection("jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE","sa","");var statement=connection.createStatement()) {
                statement.executeUpdate("DELETE FROM auth_users WHERE login_id='jane'");
            }
            store.createAuthUser("jane","A different Jane","hash","USER","csrf");
            String recreated=store.localPreferenceSubject("jane");assertNotEquals(subject,recreated,"Reusing a login label creates a different account identity");
            assertNull(store.userPreferences(recreated).get("language"));assertEquals("zh-Hant",store.userPreferences(subject).get("language"));
            assertThrows(IllegalArgumentException.class,()->store.updateUserPreferences(recreated,"fr",true,null,false,false));
        }
    }

    @Test void existingLocalAccountsReceiveOneStableOpaquePreferenceIdentityDuringMigration() throws Exception {
        ServerConfig config=config(temp.resolve("legacy-data"));String url="jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE";
        Files.createDirectories(config.dataDir.resolve("db"));
        java.sql.Driver driver=org.h2.Driver.load();boolean registered=false;
        var drivers=DriverManager.getDrivers();while(drivers.hasMoreElements())if(drivers.nextElement()==driver)registered=true;
        if(!registered)DriverManager.registerDriver(driver);
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE auth_users(login_id VARCHAR(120) PRIMARY KEY,display_name VARCHAR(120) NOT NULL,password_hash VARCHAR(512) NOT NULL,role VARCHAR(16) NOT NULL,active BOOLEAN NOT NULL,updated_at VARCHAR(40) NOT NULL)");
            statement.execute("INSERT INTO auth_users VALUES('legacy','Legacy User','hash','USER',TRUE,'2026-01-01T00:00:00Z')");
        }
        String before;
        try(JobStore store=new JobStore(config)){before=store.localPreferenceSubject("legacy");assertNotNull(before);assertTrue(before.matches("local:[0-9a-f-]{36}"));}
        try(JobStore reopened=new JobStore(config)){assertEquals(before,reopened.localPreferenceSubject("legacy"));assertEquals(0L,reopened.userPreferences(before).get("version"));}
    }
}
