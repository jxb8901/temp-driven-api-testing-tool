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

    @Test void allProfileValidationReportsPartialWhenTheEnvironmentBoundIsReached() throws Exception {
        att.TestSchemas.install(temp);
        Path config=temp.resolve("config/config.yaml");Files.createDirectories(config.getParent());
        StringBuilder yaml=new StringBuilder("schemaVersion: att-config/v2.13\nenvironments:\n");
        for(int i=0;i<=RuntimeConfigurationValidator.MAX_ENVIRONMENT_PROFILES;i++)
            yaml.append("  Env").append(String.format("%03d",i)).append(": {}\n");
        Files.write(config,yaml.toString().getBytes(StandardCharsets.UTF_8));

        AttValidationReport report=RuntimeConfigurationValidator.validate(config,temp,null,null,"LOCAL",true);

        assertEquals(3,report.exitCode());
        assertEquals("PARTIAL",report.overallStatus());
        assertTrue(((List<?>)((Map<?,?>)report.toMap().get("context")).get("environments")).size()
                <=RuntimeConfigurationValidator.MAX_ENVIRONMENT_PROFILES);
        assertTrue(((List<?>)report.toMap().get("diagnostics")).stream().anyMatch(value->
                "ATT-VALIDATION-LIMIT".equals(((Map<?,?>)value).get("code"))));
    }

    @Test void selectedEnvironmentConfigValidationIgnoresOtherPackageTestdataLayers() throws Exception {
        att.TestSchemas.install(temp);
        Path config=temp.resolve("config/config.yaml");Files.createDirectories(config.getParent());
        Files.write(config,("schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments:\n"
                +"  SIT: {}\n  UAT: {}\n").getBytes(StandardCharsets.UTF_8));
        Path packageRoot=temp.resolve("package");Files.createDirectories(packageRoot.resolve("testdata"));
        Files.write(packageRoot.resolve("package.yaml"),("schemaVersion: att-package/v1\ntestdata:\n"
                +"  descriptors: []\n  environments:\n    UAT: [testdata/uat.yaml]\n").getBytes(StandardCharsets.UTF_8));
        Files.write(packageRoot.resolve("testdata/uat.yaml"),"records: [unterminated\n".getBytes(StandardCharsets.UTF_8));

        AttValidationReport selected=RuntimeConfigurationValidator.validate(config,temp,packageRoot,"SIT","LOCAL",false);
        assertEquals(0,selected.exitCode());

        AttValidationReport fullPackage=RuntimeConfigurationValidator.validate(config,temp,packageRoot,"SIT","LOCAL",false,true,null);
        assertEquals(2,fullPackage.exitCode());
        assertTrue(att.validation.JsonSupport.write(fullPackage.toMap()).contains("ATT-PACKAGE-INVALID"));
    }
}
