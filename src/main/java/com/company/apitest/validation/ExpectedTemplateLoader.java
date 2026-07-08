/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.validation;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads templates/expected/<name>/template.yaml into strongly typed validation rules.
 */
public class ExpectedTemplateLoader {
    private final Path projectRoot;

    public ExpectedTemplateLoader(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public ExpectedTemplate load(String name) throws Exception {
        Path path = projectRoot.resolve("templates/expected").resolve(name).resolve("template.yaml");
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Expected template must be a YAML map: " + name);
            }
            Map<?, ?> map = (Map<?, ?>) loaded;
            ExpectedTemplate template = new ExpectedTemplate();
            // Missing sections are allowed so a template can validate only XML, only DB/log, or any combination.
            loadXmlRules(template, map.get("xml"));
            loadCommandRules(template.databaseRules(), map.get("database"));
            loadCommandRules(template.logRules(), map.get("log"));
            return template;
        }
    }

    private void loadXmlRules(ExpectedTemplate template, Object value) {
        if (!(value instanceof List)) {
            return;
        }
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                Map<?, ?> rule = (Map<?, ?>) item;
                template.xmlRules().add(new ExpectedTemplate.XmlRule(
                        text(rule.get("name")),
                        text(rule.get("xpath")),
                        text(rule.get("equals"))
                ));
            }
        }
    }

    private void loadCommandRules(List<ExpectedTemplate.CommandRule> target, Object value) {
        if (!(value instanceof List)) {
            return;
        }
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                Map<?, ?> rule = (Map<?, ?>) item;
                target.add(new ExpectedTemplate.CommandRule(
                        text(rule.get("name")),
                        text(rule.get("command")),
                        text(rule.get("expected"))
                ));
            }
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
