package att.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigurationValidatorTest {
    @TempDir Path temp;

    @Test void validatesActiveServerConfigWithoutCreatingDataDirectories() throws Exception {
        Path runtime=temp.resolve("config/config.yaml");Files.createDirectories(runtime.getParent());
        Files.write(runtime,"schemaVersion: att-config/v2.13\nenvironment: SIT\n".getBytes(StandardCharsets.UTF_8));
        Path packages=Files.createDirectory(temp.resolve("packages"));Path payment=Files.createDirectory(packages.resolve("payments"));
        Path data=temp.resolve("server-data");Path source=temp.resolve("att-server.yaml");
        Path java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        String sourceText="server:\n  dataDir: '"+data+"'\n  runtimeConfigPath: '"+runtime+"'\n  javaExecutable: '"+java+"'\nworkers: {}\npackages:\n  allowedRoots: ['"+packages+"']\n  entries:\n    payments: '"+payment+"'\n";
        Files.write(source,sourceText.getBytes(StandardCharsets.UTF_8));
        long modified=Files.getLastModifiedTime(source).toMillis();

        ServerConfigurationValidator.Result result=ServerConfigurationValidator.validate(source);

        assertEquals(payment.toRealPath(),result.packages().get("payments"));
        assertTrue(Files.isDirectory(result.runtimeConfig().getParent()));
        assertFalse(Files.exists(data));
        assertEquals(modified,Files.getLastModifiedTime(source).toMillis());
    }

    @Test void explicitlySelectedMissingServerConfigDoesNotFallBack() {
        String old=System.getProperty("att.server.config");Path missing=temp.resolve("missing/att-server.yaml");
        try {
            System.setProperty("att.server.config",missing.toString());
            assertEquals(missing.toAbsolutePath().normalize(),ServerConfigurationValidator.activeConfigPath());
            assertThrows(IllegalArgumentException.class,()->ServerConfigurationValidator.validate(ServerConfigurationValidator.activeConfigPath()));
        } finally {
            if(old==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",old);
        }
    }

    @Test void validatesTenDayInlineLoadLimitAndFieldSpecificOverflow() throws Exception {
        Path runtime=temp.resolve("duration-config/config.yaml");Files.createDirectories(runtime.getParent());
        Files.write(runtime,"schemaVersion: att-config/v2.13\nenvironment: SIT\n".getBytes(StandardCharsets.UTF_8));
        Path packages=Files.createDirectory(temp.resolve("duration-packages"));Path payment=Files.createDirectory(packages.resolve("payments"));
        Path java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path valid=temp.resolve("duration-valid.yaml");
        Files.write(valid,"server:\n  dataDir: '"+temp.resolve("duration-data")+"'\n  runtimeConfigPath: '"+runtime+"'\n  javaExecutable: '"+java+"'\n  inlineLoad:\n    maxDurationSeconds: 864000\nworkers: {}\npackages:\n  allowedRoots: ['"+packages+"']\n  entries:\n    payments: '"+payment+"'\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(payment.toRealPath(),ServerConfigurationValidator.validate(valid).packages().get("payments"));
        Path invalid=temp.resolve("duration-invalid.yaml");
        Files.write(invalid,"server:\n  dataDir: '"+temp.resolve("duration-data-invalid")+"'\n  runtimeConfigPath: '"+runtime+"'\n  javaExecutable: '"+java+"'\n  inlineLoad:\n    maxDurationSeconds: 864001\nworkers: {}\npackages:\n  allowedRoots: ['"+packages+"']\n  entries:\n    payments: '"+payment+"'\n".getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfigurationValidator.validate(invalid));
        assertTrue(error.getMessage().contains("server.inlineLoad.maxDurationSeconds"),error.getMessage());
    }
}
