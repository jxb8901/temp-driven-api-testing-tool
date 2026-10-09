package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir Path temp;
    @Test void loadsCanonicalReadOnlyPackageMappingsAndCreatesPrivateDataDirectory() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages"));Path pkg=Files.createDirectory(allowed.resolve("payments"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("server-data");Path file=temp.resolve("server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+data+"\n  javaExecutable: "+java+"\nworkers:\n  maxConcurrent: 2\n  queuedLimit: 3\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    payments: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(file);
        assertEquals(pkg.toRealPath(),config.packages.get("payments"));assertTrue(Files.isDirectory(data.resolve("db")));assertTrue(Files.isDirectory(data.resolve("jobs")));
        assertThrows(UnsupportedOperationException.class,()->config.packages.put("other",pkg));
    }
    @Test void rejectsPackageRootsOutsideConfiguredAllowedRoots() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("allowed"));Path outside=Files.createDirectory(temp.resolve("outside"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve("server.yaml");Files.writeString(file,"server:\n  dataDir: "+temp.resolve("data")+"\n  javaExecutable: "+java+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    outside: "+outside+"\n");
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(file));assertTrue(error.getMessage().contains("outside"));
    }
}
