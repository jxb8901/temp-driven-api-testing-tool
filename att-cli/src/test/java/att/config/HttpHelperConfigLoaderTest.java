package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpHelperConfigLoaderTest {
    @TempDir Path root;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(root); }

    @Test void ignoresExtensionFieldsWithoutStrippingHttpHeaderNames() throws Exception {
        Path helper = root.resolve("config/http.yaml");
        Files.createDirectories(helper.getParent());
        Files.write(helper, ("schemaVersion: att-httphelper/v1.1\nid: paymentApi\nbaseUrl: https://api.example.test\n"
                + "defaults:\n  x-disabled: [not, defaults]\n  headers: {x-correlation-id: preserved}\n  maxResponseBytes: 2048\n"
                + "pool: {x-disabled: invalid}\nauth: {type: none, x-disabled: invalid}\n"
                + "tls: {verifyHostname: true, x-disabled: invalid}\n"
                + "evidence: {x-disabled: invalid, output: {format: json, x-disabled: invalid}}\n")
                .getBytes(StandardCharsets.UTF_8));

        Map<String, HttpHelperConfig> loaded = new HttpHelperConfigLoader().load(
                Collections.singletonList("config/http.yaml"), root);
        HttpHelperConfig config = loaded.get("paymentApi");
        assertNotNull(config);
        assertEquals("preserved", config.headers().get("x-correlation-id"));
        assertEquals(2048, config.maxResponseBytes());
        assertNotNull(config.evidenceOutput());
    }
}
