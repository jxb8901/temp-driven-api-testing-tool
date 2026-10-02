package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SshHelperConfigLoaderTest {
    @TempDir Path root;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(root); }

    @Test void ignoresExtensionFieldsAcrossSshDescriptorObjects() throws Exception {
        Path helper = root.resolve("ssh.yaml");
        Files.write(helper, ("schemaVersion: att-sshhelper/v1.0\nid: app\n"
                + "defaults: {user: deploy, x-disabled: [not, a, default]}\n"
                + "selection: {strategy: random, x-disabled: invalid}\n"
                + "fanout: {maxConcurrency: 2, x-disabled: invalid}\n"
                + "timeouts: {connectTimeoutMs: 1000, x-disabled: invalid}\n"
                + "instances: [{id: primary, host: localhost, x-disabled: invalid}]\n")
                .getBytes(StandardCharsets.UTF_8));

        SshHelperConfig config = new SshHelperConfigLoader().load(Collections.singletonList("ssh.yaml"), root).get("app");
        assertEquals("random", config.strategy());
        assertEquals(2, config.maxConcurrency());
        assertEquals("deploy", config.instances().get("primary").user());
    }
}
