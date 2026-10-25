package att.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Safe configuration identity retained with operation results and managed artifacts. */
public final class ConfigurationEvidence {
    private ConfigurationEvidence() { }
    public static Map<String,Object> metadata(FrameworkConfig config) {
        return config.effectiveConfiguration() == null ? Collections.<String,Object>emptyMap()
                : config.effectiveConfiguration().metadata(config.environment());
    }
    public static Path write(Path directory, FrameworkConfig config) throws java.io.IOException {
        Path file = directory.resolve("configuration.json");
        Files.write(file, att.validation.JsonSupport.write(metadata(config)).getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
