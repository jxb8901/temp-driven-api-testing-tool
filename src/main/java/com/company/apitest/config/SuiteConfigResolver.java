/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the effective V1.3 configuration for one Excel suite.
 */
public class SuiteConfigResolver {
    private final Path projectRoot;
    private final FrameworkConfig global;

    public SuiteConfigResolver(Path projectRoot, FrameworkConfig global) {
        this.projectRoot = projectRoot;
        this.global = global;
    }

    public FrameworkConfig resolve(Path suitePath) throws Exception {
        Map<String, Object> sidecar = loadIfExists(sidecarPath(suitePath));
        String templateId = testCaseTemplateId(sidecar, global.defaultTestCaseTemplate());
        Map<String, Object> template = templateId.isEmpty() ? new LinkedHashMap<String, Object>() : loadIfExists(testCaseTemplatePath(templateId));

        String sheet = firstText(nested(sidecar, "testcase", "sheet"), nested(template, "testcase", "sheet"), global.testcaseSheet());
        Map<String, String> columns = new LinkedHashMap<String, String>(global.testcaseColumns());
        columns.putAll(stringMap(nested(template, "testcase", "columns")));
        columns.putAll(stringMap(nested(sidecar, "testcase", "columns")));

        List<StageConfig> stages = stages(sidecar);
        if (stages.isEmpty()) {
            stages = stages(template);
        }
        if (stages.isEmpty()) {
            stages = global.stages();
        }

        ReportConfig report = report(template, sidecar);
        int timeout = intValue(sidecar.get("timeoutSeconds"), global.timeoutSeconds());

        return new FrameworkConfig(global.outputDirectory(), global.reportDirectory(), global.logDirectory(),
                global.environment(), timeout, sheet, columns, stages, global.templatesRoot(), templateId,
                global.tools(), report, global.run());
    }

    private Path sidecarPath(Path suitePath) {
        String name = suitePath.getFileName().toString().replaceFirst("\\.xlsx$", ".yaml");
        Path parent = suitePath.getParent();
        return parent == null ? Paths.get(name) : parent.resolve(name);
    }

    private Path testCaseTemplatePath(String templateId) {
        Path root = global.templatesRoot().isAbsolute() ? global.templatesRoot() : projectRoot.resolve(global.templatesRoot()).normalize();
        return root.resolve("testcase").resolve(templateId).resolve("template.yaml");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadIfExists(Path path) throws Exception {
        Path resolved = path.isAbsolute() ? path : projectRoot.resolve(path).normalize();
        if (!Files.exists(resolved)) {
            return new LinkedHashMap<String, Object>();
        }
        try (Reader reader = Files.newBufferedReader(resolved)) {
            Object loaded = new Yaml().load(reader);
            if (loaded == null) {
                return new LinkedHashMap<String, Object>();
            }
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("YAML config must be a map: " + resolved);
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
    }

    private String testCaseTemplateId(Map<String, Object> sidecar, String defaultValue) {
        Object configured = sidecar.get("testCaseTemplate");
        if (configured instanceof Map) {
            Object id = ((Map<?, ?>) configured).get("id");
            return id == null ? defaultValue : String.valueOf(id);
        }
        return configured == null ? defaultValue : String.valueOf(configured);
    }

    private List<StageConfig> stages(Map<String, Object> map) {
        List<StageConfig> stages = new ArrayList<StageConfig>();
        Object configured = map.get("stages");
        if (configured instanceof Iterable) {
            for (Object item : (Iterable<?>) configured) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> value = (Map<?, ?>) item;
                String key = text(value.get("key"), "");
                if (!key.isEmpty()) {
                    stages.add(new StageConfig(key, text(value.get("name"), key), text(value.get("template"), ""),
                            text(value.get("templatePath"), ""), bool(value.get("required"), false),
                            text(value.get("onFailure"), "stop"), text(value.get("runWhen"), "normal")));
                }
            }
        }
        return stages;
    }

    private ReportConfig report(Map<String, Object> template, Map<String, Object> sidecar) {
        Map<String, String> columns = new LinkedHashMap<String, String>(global.report().columns());
        columns.putAll(stringMap(nested(template, "report", "columns")));
        columns.putAll(stringMap(nested(sidecar, "report", "columns")));
        String mode = firstText(nested(sidecar, "report", "mode"), nested(template, "report", "mode"), global.report().mode());
        String fileName = firstText(nested(sidecar, "report", "fileNamePattern"), nested(template, "report", "fileNamePattern"), global.report().fileNamePattern());
        return new ReportConfig(mode, fileName, columns);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> map, String first, String second) {
        Object value = map.get(first);
        if (value instanceof Map) {
            return ((Map<String, Object>) value).get(second);
        }
        return null;
    }

    private String firstText(Object first, Object second, String fallback) {
        if (first != null && !String.valueOf(first).trim().isEmpty()) {
            return String.valueOf(first);
        }
        if (second != null && !String.valueOf(second).trim().isEmpty()) {
            return String.valueOf(second);
        }
        return fallback;
    }

    private String text(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private boolean bool(Object value, boolean defaultValue) {
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
