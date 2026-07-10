/* Author: Jeffrey + ChatGPT */
package com.company.apitest.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads V2 global configuration. Excel and stages are intentionally rejected here. */
public final class FrameworkConfigLoader {
    public FrameworkConfig load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = YamlSupport.parser().load(reader);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Config must be a YAML map: " + path);
            Map<?, ?> map = (Map<?, ?>) loaded;
            if (map.containsKey("testcase") || map.containsKey("stages")) {
                throw new IllegalArgumentException("V2 global config must not define testcase or stages; use the workbook sidecar");
            }
            return new FrameworkConfig(Paths.get(text(map.get("outputDirectory"), "output")),
                    Paths.get(text(map.get("reportDirectory"), "report")),
                    Paths.get(text(map.get("logDirectory"), "logs")),
                    text(map.get("environment"), "SIT"), integer(map.get("timeoutSeconds"), 120),
                    templatesRoot(map), tools(map), report(map), run(map));
        }
    }

    private static Path templatesRoot(Map<?, ?> map) {
        Object value = map.get("templates");
        return value instanceof Map ? Paths.get(text(((Map<?, ?>) value).get("root"), "templates")) : Paths.get("templates");
    }

    private static Map<String, ToolConfig> tools(Map<?, ?> map) {
        Map<String, ToolConfig> result = new LinkedHashMap<String, ToolConfig>();
        Object configured = map.get("tools");
        if (!(configured instanceof Map)) return result;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Tool must be a map: " + entry.getKey());
            String key = String.valueOf(entry.getKey());
            Map<?, ?> tool = (Map<?, ?>) entry.getValue();
            if (tool.containsKey("argv")) throw new IllegalArgumentException("V2 tool does not support argv: " + key);
            result.put(key, new ToolConfig(key, text(tool.get("name"), key), text(tool.get("description"), ""),
                    required(tool, "command", "tool " + key), text(tool.get("output"), "txt"), arguments(key, tool.get("arguments"))));
        }
        return result;
    }

    private static Map<String, ToolArgumentConfig> arguments(String toolKey, Object value) {
        Map<String, ToolArgumentConfig> result = new LinkedHashMap<String, ToolArgumentConfig>();
        if (value == null) return result;
        if (!(value instanceof Map)) throw new IllegalArgumentException("arguments must be a map for tool: " + toolKey);
        int index = 0;
        int size = ((Map<?, ?>) value).size();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            index++;
            String key = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Argument descriptor must be a map: " + toolKey + "." + key);
            Map<?, ?> descriptor = (Map<?, ?>) entry.getValue();
            if (!descriptor.containsKey("required")) throw new IllegalArgumentException("required is mandatory for argument: " + toolKey + "." + key);
            String delimit = text(descriptor.get("delimit"), "");
            if (!delimit.isEmpty() && index != size) throw new IllegalArgumentException("delimit is allowed only on the final argument: " + toolKey + "." + key);
            result.put(key, new ToolArgumentConfig(key, required(descriptor, "name", "argument " + key),
                    required(descriptor, "description", "argument " + key), bool(descriptor.get("required"), false), delimit));
        }
        return result;
    }

    private static ReportConfig report(Map<?, ?> map) {
        Object value = map.get("report");
        if (!(value instanceof Map)) return null;
        Map<?, ?> report = (Map<?, ?>) value;
        return new ReportConfig(text(report.get("mode"), "append-to-copy"), text(report.get("fileNamePattern"), "${suiteName}.result.xlsx"), stringMap(report.get("columns")));
    }

    private static RunConfig run(Map<?, ?> map) {
        Object value = map.get("run");
        if (value instanceof Map && ((Map<?, ?>) value).get("id") instanceof Map) {
            Map<?, ?> id = (Map<?, ?>) ((Map<?, ?>) value).get("id");
            return new RunConfig(text(id.get("default"), "timestamp"), text(id.get("timestampFormat"), "yyyyMMdd-HHmmss"));
        }
        return new RunConfig("timestamp", "yyyyMMdd-HHmmss");
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return result;
    }
    private static String required(Map<?, ?> map, String key, String owner) {
        String value = text(map.get(key), "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required for " + owner);
        return value;
    }
    private static String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private static int integer(Object value, int fallback) { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
    private static boolean bool(Object value, boolean fallback) { return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value)); }
}
