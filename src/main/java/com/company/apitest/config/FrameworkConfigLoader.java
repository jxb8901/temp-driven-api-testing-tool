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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
                    Paths.get(value(map, "outputDirectory", "output")),
                    Paths.get(value(map, "reportDirectory", "report")),
                    Paths.get(value(map, "logDirectory", "logs")),
                    value(map, "environment", "SIT"),
                    Integer.parseInt(value(map, "timeoutSeconds", "120")),
                    testcaseSheet(map),
                    testcaseColumns(map),
                    stages(map),
                    templatesRoot(map),
                    defaultTestCaseTemplate(map),
                    tools(map),
                    report(map),
                    run(map)
            );
        }
    }

    private static String testcaseSheet(Map<?, ?> map) {
        Object testcase = map.get("testcase");
        if (testcase instanceof Map) {
            Object sheet = ((Map<?, ?>) testcase).get("sheet");
            if (sheet != null) {
                return String.valueOf(sheet);
            }
        }
        return "TestCases";
    }

    private static List<StageConfig> stages(Map<?, ?> map) {
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
        if (stages.isEmpty()) {
            stages.add(new StageConfig("stagePre", "Pre", false, "stop", "normal"));
            stages.add(new StageConfig("stageMain", "Main", true, "stop", "normal"));
            stages.add(new StageConfig("stagePost", "Post", false, "stop", "normal"));
        }
        return stages;
    }

    private static Path templatesRoot(Map<?, ?> map) {
        Object configured = map.get("templates");
        if (configured instanceof Map) {
            Object root = ((Map<?, ?>) configured).get("root");
            if (root != null) {
                return Paths.get(String.valueOf(root));
            }
        }
        return Paths.get("templates");
    }

    private static String defaultTestCaseTemplate(Map<?, ?> map) {
        Object configured = map.get("templates");
        if (configured instanceof Map) {
            Object value = ((Map<?, ?>) configured).get("defaultTestCaseTemplate");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static Map<String, String> testcaseColumns(Map<?, ?> map) {
        Map<String, String> columns = new LinkedHashMap<String, String>();
        Object testcase = map.get("testcase");
        if (testcase instanceof Map) {
            Object configured = ((Map<?, ?>) testcase).get("columns");
            if (configured instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
                    columns.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ToolConfig> tools(Map<?, ?> map) {
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        Object configured = map.get("tools");
        if (!(configured instanceof Map)) {
            return tools;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<?, ?> value = (Map<?, ?>) entry.getValue();
            tools.put(key, new ToolConfig(
                    key,
                    text(value.get("name"), key),
                    text(value.get("command"), ""),
                    text(value.get("output"), "txt"),
                    objectMap(value.get("arguments")),
                    objectList(value.get("argv")),
                    new LinkedHashMap<String, String>()
            ));
        }
        return tools;
    }

    private static List<Object> objectList(Object value) {
        List<Object> result = new ArrayList<Object>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                result.add(item);
            }
        }
        return result;
    }

    private static ReportConfig report(Map<?, ?> map) {
        Object configured = map.get("report");
        if (configured instanceof Map) {
            Map<?, ?> value = (Map<?, ?>) configured;
            return new ReportConfig(text(value.get("mode"), "append-to-copy"),
                    text(value.get("fileNamePattern"), "${suiteName}.result.xlsx"),
                    stringMap(value.get("columns")));
        }
        return new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", null);
    }

    private static RunConfig run(Map<?, ?> map) {
        Object configured = map.get("run");
        if (configured instanceof Map) {
            Object id = ((Map<?, ?>) configured).get("id");
            if (id instanceof Map) {
                Map<?, ?> idMap = (Map<?, ?>) id;
                return new RunConfig(text(idMap.get("default"), "timestamp"), text(idMap.get("timestampFormat"), "yyyyMMdd-HHmmss"));
            }
        }
        return new RunConfig("timestamp", "yyyyMMdd-HHmmss");
    }

    private static Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private static String text(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String value(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
