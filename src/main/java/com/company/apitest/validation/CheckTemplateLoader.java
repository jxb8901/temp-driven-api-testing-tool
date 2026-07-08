/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.validation;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads templates/check/<name>/template.yaml into ordered check actions.
 */
public class CheckTemplateLoader {
    private final Path projectRoot;

    public CheckTemplateLoader(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public List<CheckAction> load(String name) throws Exception {
        if (name == null || name.trim().isEmpty()) {
            return new ArrayList<CheckAction>();
        }
        Path path = projectRoot.resolve("templates/check").resolve(name).resolve("template.yaml");
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(reader);
            List<CheckAction> actions = new ArrayList<CheckAction>();
            if (loaded instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        Map<?, ?> value = (Map<?, ?>) entry.getValue();
                        actions.add(new CheckAction(
                                String.valueOf(entry.getKey()),
                                text(value.get("description")),
                                text(value.get("call")),
                                text(value.get("expected"))
                        ));
                    }
                }
            }
            return actions;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
