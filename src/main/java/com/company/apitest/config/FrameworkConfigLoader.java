/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Loads framework configuration from YAML and applies V1 default values.
 */
public class FrameworkConfigLoader {
    public FrameworkConfig load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Config must be a YAML map: " + path);
            }
            Map<?, ?> map = (Map<?, ?>) loaded;
            return new FrameworkConfig(
                    Paths.get(value(map, "outputDirectory", "output/xml")),
                    Paths.get(value(map, "reportDirectory", "report")),
                    Paths.get(value(map, "logDirectory", "logs")),
                    value(map, "environment", "SIT"),
                    Integer.parseInt(value(map, "timeoutSeconds", "120")),
                    value(map, "executorCommand", "")
            );
        }
    }

    private static String value(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
