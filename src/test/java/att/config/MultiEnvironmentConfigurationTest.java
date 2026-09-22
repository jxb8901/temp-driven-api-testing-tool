/* Author: Jeffrey + ChatGPT */
package att.config;

import att.validation.JsonSchemaVerifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiEnvironmentConfigurationTest {
    private final Path root = Paths.get("").toAbsolutePath().normalize();

    @Test
    void checkedInSitAndUatConfigsKeepSharedRegistryAndValidateDescriptors() throws Exception {
        Map<?, ?> sit = yaml(root.resolve("config/environments/sit.yaml"));
        Map<?, ?> uat = yaml(root.resolve("config/environments/uat.yaml"));

        JsonSchemaVerifier.verify(root.resolve("schemas/att-config-v2.6.schema.json"), sit);
        JsonSchemaVerifier.verify(root.resolve("schemas/att-config-v2.6.schema.json"), uat);

        assertEquals("SIT", sit.get("environment"));
        assertEquals("UAT", uat.get("environment"));
        assertEquals(sit.get("toolGroups"), uat.get("toolGroups"));
        assertEquals(sit.get("tools"), uat.get("tools"));
        assertEquals(Arrays.asList("config/tools/sample.yaml", "config/tools/fpp.yaml", "config/tools/orders-db.yaml"), sit.get("toolGroups"));

        Map<?, ?> sitTools = map(sit.get("tools"));
        assertNotNull(sitTools.get("invokePaymentApi"));
        assertNotNull(sitTools.get("getAcDate"));

        assertEquals(Arrays.asList("config/dbhelpers/sit/orders.yaml"), sit.get("dbhelpers"));
        assertEquals(Arrays.asList("config/dbhelpers/uat/orders.yaml"), uat.get("dbhelpers"));
        assertEquals(Arrays.asList("config/mqhelpers/sit/payment.yaml"), sit.get("mqhelpers"));
        assertEquals(Arrays.asList("config/mqhelpers/uat/payment.yaml"), uat.get("mqhelpers"));

        validateDescriptor(sit, "dbhelpers", "schemas/att-dbhelper-v2.5.schema.json");
        validateDescriptor(uat, "dbhelpers", "schemas/att-dbhelper-v2.5.schema.json");
        validateDescriptor(sit, "mqhelpers", "schemas/att-mqhelper-v1.0.schema.json");
        validateDescriptor(uat, "mqhelpers", "schemas/att-mqhelper-v1.0.schema.json");

        Map<?, ?> sitDb = yaml(root.resolve("config/dbhelpers/sit/orders.yaml"));
        Map<?, ?> uatDb = yaml(root.resolve("config/dbhelpers/uat/orders.yaml"));
        Map<?, ?> sitMq = yaml(root.resolve("config/mqhelpers/sit/payment.yaml"));
        Map<?, ?> uatMq = yaml(root.resolve("config/mqhelpers/uat/payment.yaml"));
        assertEquals("orders", sitDb.get("id"));
        assertEquals("orders", uatDb.get("id"));
        assertEquals("payment", sitMq.get("id"));
        assertEquals("payment", uatMq.get("id"));
        assertFalse(sitDb.equals(uatDb));
        assertFalse(sitMq.equals(uatMq));
    }

    private void validateDescriptor(Map<?, ?> config, String field, String schema) throws Exception {
        List<?> paths = list(config.get(field));
        assertEquals(1, paths.size());
        Path descriptor = root.resolve(String.valueOf(paths.get(0)));
        assertTrue(Files.isRegularFile(descriptor), descriptor.toString());
        JsonSchemaVerifier.verify(root.resolve(schema), yaml(descriptor));
    }

    private Map<?, ?> yaml(Path file) throws Exception {
        Object loaded = YamlSupport.load(file);
        return map(loaded);
    }

    private Map<?, ?> map(Object value) {
        assertTrue(value instanceof Map, String.valueOf(value));
        return (Map<?, ?>) value;
    }

    private List<?> list(Object value) {
        assertTrue(value instanceof List, String.valueOf(value));
        return (List<?>) value;
    }
}
