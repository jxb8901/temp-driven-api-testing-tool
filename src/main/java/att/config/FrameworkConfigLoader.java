/* Author: Jeffrey + ChatGPT */
package att.config;

import att.Version;

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
            Path schema = Paths.get("").toAbsolutePath().resolve("schemas/att-config-v2.1.schema.json");
            if (!Files.isRegularFile(schema) && path.toAbsolutePath().normalize().getParent() != null && path.toAbsolutePath().normalize().getParent().getParent() != null) schema = path.toAbsolutePath().normalize().getParent().getParent().resolve("schemas/att-config-v2.1.schema.json");
            if (Files.isRegularFile(schema)) try { att.validation.JsonSchemaVerifier.verify(schema, map); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
            SchemaSupport.requireVersion(map, Version.CONFIG_SCHEMA, "config");
            SchemaSupport.rejectUnknown(map, "config", "schemaVersion", "outputDirectory", "environment", "timeoutMs", "templates", "testcase", "run", "report", "xml", "tools");
            validateGlobalMappings(map);
            return new FrameworkConfig(relativePath(map.get("outputDirectory"), "output", "outputDirectory"),
                    Paths.get("report"), Paths.get("logs"),
                    map.get("environment") == null ? "SIT" : SchemaSupport.string(map.get("environment"), "environment", true), positiveInteger(map.get("timeoutMs"), 10000, "timeoutMs"),
                    templatesRoot(map), testcasesRoot(map), tools(map), report(map), run(map), null, "", "", null, null, 1, xmlNamespaceMode(map), "");
        }
    }

    private static String xmlNamespaceMode(Map<?, ?> map) {
        Object xml = map.get("xml");
        return xml instanceof Map ? text(((Map<?, ?>) xml).get("namespaceMode"), "ignore") : "ignore";
    }

    private static void validateGlobalMappings(Map<?, ?> map) {
        if (map.get("templates") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(map.get("templates"), "config.templates"), "config.templates", "root");
        if (map.get("testcase") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(map.get("testcase"), "config.testcase"), "config.testcase", "root");
        if (map.get("run") != null) {
            Map<?, ?> run = SchemaSupport.map(map.get("run"), "config.run");
            SchemaSupport.rejectUnknown(run, "config.run", "id");
            if (run.get("id") != null) {
                Map<?, ?> id = SchemaSupport.map(run.get("id"), "config.run.id"); SchemaSupport.rejectUnknown(id, "config.run.id", "default", "timestampFormat");
                if (id.get("default") != null && !"timestamp".equals(SchemaSupport.string(id.get("default"), "run.id.default", true))) throw new IllegalArgumentException("run.id.default must be timestamp");
                if (id.get("timestampFormat") != null) try { java.time.format.DateTimeFormatter.ofPattern(SchemaSupport.string(id.get("timestampFormat"), "run.id.timestampFormat", true)); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid run.id.timestampFormat: " + e.getMessage()); }
            }
        }
        if (map.get("report") != null) {
            Map<?, ?> report = SchemaSupport.map(map.get("report"), "config.report");
            SchemaSupport.rejectUnknown(report, "config.report", "mode", "fileNamePattern", "columns", "junit");
            if (report.get("junit") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(report.get("junit"), "config.report.junit"), "config.report.junit", "caseLogEmbedThresholdBytes");
        }
        if (map.get("xml") != null) { Map<?, ?> xml = SchemaSupport.map(map.get("xml"), "config.xml"); SchemaSupport.rejectUnknown(xml, "config.xml", "namespaceMode"); if (xml.get("namespaceMode") != null) { String mode = SchemaSupport.string(xml.get("namespaceMode"), "xml.namespaceMode", true); if (!("ignore".equals(mode) || "preserve".equals(mode))) throw new IllegalArgumentException("xml.namespaceMode must be ignore or preserve"); } }
    }

    private static Path templatesRoot(Map<?, ?> map) {
        Object value = map.get("templates");
        return value instanceof Map ? relativePath(((Map<?, ?>) value).get("root"), "templates", "templates.root") : Paths.get("templates");
    }
    private static Path testcasesRoot(Map<?, ?> map) {
        Object value = map.get("testcase");
        return value instanceof Map ? relativePath(((Map<?, ?>) value).get("root"), "testcase", "testcase.root") : Paths.get("testcase");
    }

    private static Map<String, ToolConfig> tools(Map<?, ?> map) {
        Map<String, ToolConfig> result = new LinkedHashMap<String, ToolConfig>();
        Object configured = map.get("tools");
        if (!(configured instanceof Map)) return result;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Tool keys must be strings");
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Tool must be a map: " + entry.getKey());
            String key = String.valueOf(entry.getKey());
            Map<?, ?> tool = (Map<?, ?>) entry.getValue();
            SchemaSupport.rejectUnknown(tool, "tools." + key, "name", "description", "command", "output", "arguments");
            String output = tool.get("output") == null ? "txt" : SchemaSupport.string(tool.get("output"), "tools." + key + ".output", true);
            if (!("txt".equals(output) || "yaml".equals(output) || "json".equals(output) || "xml".equals(output))) {
                throw new IllegalArgumentException("Tool output must be txt, yaml, json, or xml: " + key);
            }
            Map<String, ToolArgumentConfig> arguments = arguments(key, tool.get("arguments"));
            String command = required(tool, "command", "tool " + key);
            validateCommandArguments(key, command, arguments);
            result.put(key, new ToolConfig(key, required(tool, "name", "tool " + key), required(tool, "description", "tool " + key), command, output, arguments));
        }
        return result;
    }

    private static void validateCommandArguments(String tool, String command, Map<String, ToolArgumentConfig> arguments) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(command);
        while (matcher.find()) {
            String expression = matcher.group(1);
            String argument = expression.startsWith("TOOL.input.") ? expression.substring(11) : expression.startsWith("input.") ? expression.substring(6) : expression;
            boolean argumentForm = expression.startsWith("TOOL.input.") || expression.startsWith("input.") || expression.matches("[A-Za-z_][A-Za-z0-9_]*");
            if (argumentForm && !arguments.containsKey(argument)) throw new IllegalArgumentException("Tool command argument reference is case-sensitive and must match a declared argument: " + tool + "." + expression);
        }
    }

    private static Map<String, ToolArgumentConfig> arguments(String toolKey, Object value) {
        Map<String, ToolArgumentConfig> result = new LinkedHashMap<String, ToolArgumentConfig>();
        if (value == null) return result;
        if (!(value instanceof Map)) throw new IllegalArgumentException("arguments must be a map for tool: " + toolKey);
        int index = 0;
        int size = ((Map<?, ?>) value).size();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Tool argument keys must be strings: " + toolKey);
            index++;
            String key = String.valueOf(entry.getKey());
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Tool argument name must match [A-Za-z_][A-Za-z0-9_]*: " + toolKey + "." + key);
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Argument descriptor must be a map: " + toolKey + "." + key);
            Map<?, ?> descriptor = (Map<?, ?>) entry.getValue();
            SchemaSupport.rejectUnknown(descriptor, "tools." + toolKey + ".arguments." + key, "name", "description", "required", "delimit");
            if (!descriptor.containsKey("required")) throw new IllegalArgumentException("required is mandatory for argument: " + toolKey + "." + key);
            String delimit = descriptor.get("delimit") == null ? "" : SchemaSupport.string(descriptor.get("delimit"), "tools." + toolKey + ".arguments." + key + ".delimit", true);
            if (!delimit.isEmpty() && index != size) throw new IllegalArgumentException("delimit is allowed only on the final argument: " + toolKey + "." + key);
            result.put(key, new ToolArgumentConfig(key, required(descriptor, "name", "argument " + key),
                    required(descriptor, "description", "argument " + key), booleanValue(descriptor.get("required"), false,
                    "required for argument " + toolKey + "." + key), delimit));
        }
        return result;
    }

    private static ReportConfig report(Map<?, ?> map) {
        Object value = map.get("report");
        if (!(value instanceof Map)) return null;
        Map<?, ?> report = (Map<?, ?>) value;
        String mode = report.get("mode") == null ? "append-to-copy" : SchemaSupport.string(report.get("mode"), "report.mode", true); if (!"append-to-copy".equals(mode)) throw new IllegalArgumentException("report.mode must be append-to-copy");
        String pattern = report.get("fileNamePattern") == null ? "${suiteName}.result.xlsx" : SchemaSupport.string(report.get("fileNamePattern"), "report.fileNamePattern", true); if (!pattern.contains("${suiteName}")) throw new IllegalArgumentException("report.fileNamePattern must contain ${suiteName}");
        Object junitValue = report.get("junit");
        Map<?, ?> junit = junitValue instanceof Map ? (Map<?, ?>) junitValue : java.util.Collections.emptyMap();
        return new ReportConfig(mode, pattern, reportColumns(report.get("columns")), boundedInteger(junit.get("caseLogEmbedThresholdBytes"), 10240, 0, 1048576, "report.junit.caseLogEmbedThresholdBytes"));
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
        if (value == null) return result;
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a string mapping");
        for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            if (!(e.getKey() instanceof String)) throw new IllegalArgumentException("Mapping key must be a string: " + e.getKey());
            if (!(e.getValue() instanceof String)) throw new IllegalArgumentException("Mapping value must be a string: " + e.getKey());
            result.put(String.valueOf(e.getKey()), (String) e.getValue());
        }
        return result;
    }
    private static Map<String, String> reportColumns(Object value) {
        Map<String, String> result = stringMap(value);
        java.util.Set<String> allowed = new java.util.LinkedHashSet<String>(java.util.Arrays.asList("result", "durationMs", "actualResult", "caseLog", "reportLink", "runTime"));
        for (Map.Entry<String, String> entry : result.entrySet()) {
            if (!allowed.contains(entry.getKey())) throw new IllegalArgumentException("Unknown report column key: " + entry.getKey());
            if (entry.getValue().trim().isEmpty()) throw new IllegalArgumentException("Report column header must not be blank: " + entry.getKey());
        }
        return result;
    }
    private static String required(Map<?, ?> map, String key, String owner) {
        return SchemaSupport.string(map.get(key), owner + "." + key, true);
    }
    private static String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private static int positiveInteger(Object value, int fallback, String owner) {
        if (value != null && !(value instanceof Number)) throw new IllegalArgumentException(owner + " must be an integer");
        int result = value == null ? fallback : ((Number) value).intValue();
        if (result < 1 || result > 3600000) throw new IllegalArgumentException(owner + " must be between 1 and 3600000");
        return result;
    }
    private static Path relativePath(Object value, String fallback, String owner) {
        String text = value == null ? fallback : SchemaSupport.string(value, owner, true);
        Path path = Paths.get(text);
        if (path.isAbsolute() || path.normalize().startsWith("..") || ".".equals(path.normalize().toString())) throw new IllegalArgumentException(owner + " must be a safe relative package path");
        return path.normalize();
    }
    private static boolean booleanValue(Object value, boolean fallback, String owner) {
        if (value == null) return fallback;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        throw new IllegalArgumentException(owner + " must be true or false");
    }
    private static int boundedInteger(Object value, int fallback, int minimum, int maximum, String owner) {
        if (value == null) return fallback;
        if (!(value instanceof Number)) throw new IllegalArgumentException(owner + " must be an integer");
        int result = ((Number) value).intValue();
        if (result < minimum || result > maximum) throw new IllegalArgumentException(owner + " must be between " + minimum + " and " + maximum);
        return result;
    }
}
