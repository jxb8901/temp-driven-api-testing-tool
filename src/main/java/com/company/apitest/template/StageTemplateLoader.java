/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads V1.2 Tool Invocation Template packages from templates/stage.
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
            return new StageTemplate(text(map.get("name"), templateName), directory, actions);
        }
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
