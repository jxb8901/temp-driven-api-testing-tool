package att.validation;

import att.server.api.AttValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationValidatorTest {
    @TempDir Path temp;

    @Test void allProfileValidationChecksEnabledProfilesAndIgnoresDisabledExtensions() throws Exception {
        att.TestSchemas.install(temp);
        Path config=temp.resolve("config/config.yaml");Files.createDirectories(config.getParent());
        Files.write(config,"schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n  SIT: {}\n  UAT: {}\n  x-disabled-profile: not-a-profile\n".getBytes(StandardCharsets.UTF_8));

        AttValidationReport report=RuntimeConfigurationValidator.validate(config,temp,null,null,"LOCAL",true);

        assertEquals(0,report.exitCode());
        Object profiles=((Map<?,?>)report.toMap().get("context")).get("environments");
        assertEquals(Arrays.asList("SIT","UAT"),profiles);
        JsonSchemaVerifier.verify(SchemaFiles.resolveVersion(temp,"att-validation/v1"),report.toMap());
        assertDoesNotThrow(()->SchemaFiles.historicalSchemaVersions(temp));
    }
}
