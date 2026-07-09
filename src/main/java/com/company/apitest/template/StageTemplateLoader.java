/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import com.company.apitest.config.StageConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads V1.3 stage template packages.
 */
public class StageTemplateLoader {
    private final Path projectRoot;
    private final Path templatesRoot;

    public StageTemplateLoader(Path projectRoot, Path templatesRoot) {
        this.projectRoot = projectRoot;
        this.templatesRoot = templatesRoot;
    }

    public StageTemplate load(String templateName) throws Exception {
        Path root = templatesRoot.isAbsolute() ? templatesRoot : projectRoot.resolve(templatesRoot).normalize();
        Path directory = root.resolve(templateName);
        if (!Files.exists(directory.resolve("template.yaml"))) {
            Path legacy = root.resolve("stage").resolve(templateName);
            if (Files.exists(legacy.resolve("template.yaml"))) {
                directory = legacy;
            }
        }
        return loadDirectory(templateName, directory);
    }

    public StageTemplate load(StageConfig stage) throws Exception {
        if (stage.templatePath() != null && !stage.templatePath().trim().isEmpty()) {
            Path directory = Paths.get(stage.templatePath());
            if (!directory.isAbsolute()) {
                directory = projectRoot.resolve(directory).normalize();
            }
            return loadDirectory(stage.key(), directory);
        }
        return load(stage.template());
    }

    private StageTemplate loadDirectory(String templateName, Path directory) throws Exception {
        Path file = directory.resolve("template.yaml");
        try (Reader reader = Files.newBufferedReader(file)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Template must be a YAML map: " + file);
            }
            Map<?, ?> map = (Map<?, ?>) loaded;
            List<TemplateAction> actions = new ArrayList<TemplateAction>();
            Object configured = map.get("actions");
            if (configured instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        actions.add(new TemplateAction(String.valueOf(entry.getKey()), objectMap((Map<?, ?>) entry.getValue())));
                    }
                }
            }
            return new StageTemplate(text(map.get("name"), templateName), directory, actions, objectMapValue(map.get("actionDefaults")));
        }
    }

    private Map<String, Object> objectMapValue(Object value) {
        if (!(value instanceof Map)) {
            return new java.util.LinkedHashMap<String, Object>();
        }
        return objectMap((Map<?, ?>) value);
    }

    private Map<String, Object> objectMap(Map<?, ?> input) {
        java.util.Map<String, Object> output = new java.util.LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            output.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return output;
    }

    private String text(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }
}
