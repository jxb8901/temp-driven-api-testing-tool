/* Author: Jeffrey + ChatGPT */
package att.config;

import att.Version;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads V2 global configuration. Excel and stages are intentionally rejected here. */
public final class FrameworkConfigLoader {
    public FrameworkConfig load(Path path) throws IOException {
        return load(path, projectRoot(path));
    }

    public FrameworkConfig load(Path path, Path projectRoot) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = YamlSupport.parser().load(reader);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Config must be a YAML map: " + path);
            Map<?, ?> map = (Map<?, ?>) loaded;
            String schemaVersion = String.valueOf(map.get("schemaVersion"));
            boolean v22 = Version.CONFIG_SCHEMA.equals(schemaVersion);
            if (!(v22 || "att-config/v2.1".equals(schemaVersion))) throw new IllegalArgumentException("Unsupported config schemaVersion: " + schemaVersion);
            projectRoot = projectRoot.toAbsolutePath().normalize();
            String schemaName = v22 ? "att-config-v2.2.schema.json" : "att-config-v2.1.schema.json";
            Path schema = schema(projectRoot, schemaName);
            if (Files.isRegularFile(schema)) try { att.validation.JsonSchemaVerifier.verify(schema, map); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
            SchemaSupport.rejectUnknown(map, "config", v22
                    ? new String[]{"schemaVersion", "outputDirectory", "environment", "timeoutMs", "templates", "testcase", "run", "report", "caseLog", "xml", "toolGroups", "ssh", "tools"}
                    : new String[]{"schemaVersion", "outputDirectory", "environment", "timeoutMs", "templates", "testcase", "run", "report", "caseLog", "xml", "tools"});
            validateGlobalMappings(map);
            Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
            SshConfig globalSsh = ssh(map.get("ssh"), "config.ssh");
            addTools(map.get("tools"), tools, "", Collections.<String>emptyList(), globalSsh, "tools", null);
            if (v22) addToolGroups(map.get("toolGroups"), projectRoot, tools);
            return new FrameworkConfig(relativePath(map.get("outputDirectory"), "output", "outputDirectory"),
                    Paths.get("report"), Paths.get("logs"),
                    map.get("environment") == null ? "SIT" : SchemaSupport.string(map.get("environment"), "environment", true), positiveInteger(map.get("timeoutMs"), 10000, "timeoutMs"),
                    templatesRoot(map), testcasesRoot(map), tools, report(map), run(map), null, "", "", null, null, 1, xmlNamespaceMode(map), "", caseLogYamlAnchors(map));
        }
    }

    private static Path projectRoot(Path config) {
        Path absolute = config.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null && parent.getFileName() != null && "config".equals(parent.getFileName().toString()) && parent.getParent() != null) return parent.getParent();
        return parent == null ? Paths.get("").toAbsolutePath().normalize() : parent;
    }

    private static Path schema(Path projectRoot, String name) {
        Path cwd = Paths.get("").toAbsolutePath().resolve("schemas").resolve(name);
        return Files.isRegularFile(cwd) ? cwd : projectRoot.resolve("schemas").resolve(name);
    }

    private static String xmlNamespaceMode(Map<?, ?> map) {
        Object xml = map.get("xml");
        return xml instanceof Map ? text(((Map<?, ?>) xml).get("namespaceMode"), "ignore") : "ignore";
    }

    private static boolean caseLogYamlAnchors(Map<?, ?> map) {
        Object caseLog = map.get("caseLog");
        return caseLog instanceof Map && booleanValue(((Map<?, ?>) caseLog).get("yamlAnchors"), false, "caseLog.yamlAnchors");
    }

    private static void validateGlobalMappings(Map<?, ?> map) {
        if (map.get("templates") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(map.get("templates"), "config.templates"), "config.templates", "root");
        if (map.get("testcase") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(map.get("testcase"), "config.testcase"), "config.testcase", "root");
        if (map.get("caseLog") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(map.get("caseLog"), "config.caseLog"), "config.caseLog", "yamlAnchors");
        if (map.get("ssh") != null) validateSshMap(SchemaSupport.map(map.get("ssh"), "config.ssh"), "config.ssh");
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

    private static void addTools(Object configured, Map<String, ToolConfig> result, String groupId,
                                 List<String> script, SshConfig ssh, String owner, Path sourceFile) {
        if (!(configured instanceof Map)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Tool keys must be strings");
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Tool must be a map: " + entry.getKey());
            String localKey = String.valueOf(entry.getKey());
            if (!localKey.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Tool key must match [A-Za-z_][A-Za-z0-9_-]*: " + localKey);
            if (groupId.isEmpty() && new att.template.DefaultBuiltInProvider().names().contains(localKey.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Global tool key is reserved for built-in function: " + localKey);
            }
            String key = groupId.isEmpty() ? localKey : groupId + "." + localKey;
            Map<?, ?> tool = (Map<?, ?>) entry.getValue();
            SchemaSupport.rejectUnknown(tool, owner + "." + localKey, "name", "description", "command", "output", "arguments");
            String output = tool.get("output") == null ? "txt" : SchemaSupport.string(tool.get("output"), owner + "." + localKey + ".output", true);
            if (!("txt".equals(output) || "yaml".equals(output) || "json".equals(output) || "xml".equals(output))) {
                throw new IllegalArgumentException("Tool output must be txt, yaml, json, or xml: " + key);
            }
            Map<String, ToolArgumentConfig> arguments = arguments(key, tool.get("arguments"));
            List<String> command = command(tool.get("command"), "tool " + key + ".command");
            validateCommandArguments(key, command, arguments, script.isEmpty());
            ToolConfig previous = result.put(key, new ToolConfig(key, localKey, groupId, required(tool, "name", "tool " + key), required(tool, "description", "tool " + key), command, script, output, arguments, ssh, sourceFile));
            if (previous != null) throw new IllegalArgumentException("Duplicate qualified tool name: " + key);
        }
    }

    private static void validateCommandArguments(String tool, List<String> tokens, Map<String, ToolArgumentConfig> arguments, boolean commandOwnsExecutable) {
            if (tokens.isEmpty()) throw new IllegalArgumentException("Tool command is blank: " + tool);
            if (tokens.get(0).trim().isEmpty()) throw new IllegalArgumentException("Tool command first argv item must be non-blank: " + tool);
            if (commandOwnsExecutable && tokens.get(0).contains("${")) throw new IllegalArgumentException("Tool executable must be static and non-blank: " + tool);
            for (String token : tokens) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(token);
                while (matcher.find()) {
                    String expression = matcher.group(1);
                    String argument = expression.startsWith("TOOL.input.") ? expression.substring(11) : expression.startsWith("input.") ? expression.substring(6) : expression;
                    boolean argumentForm = expression.startsWith("TOOL.input.") || expression.startsWith("input.") || expression.matches("[A-Za-z_][A-Za-z0-9_]*");
                    if (!argumentForm || !arguments.containsKey(argument)) throw new IllegalArgumentException("Tool command placeholders must reference a declared argument: " + tool + "." + expression);
                }
            }
            for (ToolArgumentConfig argument : arguments.values()) {
                if (!argument.multiValue()) continue;
                String direct = "${" + argument.key() + "}";
                String input = "${input." + argument.key() + "}";
                String legacy = "${TOOL.input." + argument.key() + "}";
                for (String token : tokens) {
                    if ((token.contains(direct) || token.contains(input) || token.contains(legacy))
                            && !(token.equals(direct) || token.equals(input) || token.equals(legacy))) {
                        throw new IllegalArgumentException("Delimited argument placeholder must occupy one complete argv token: " + tool + "." + argument.key());
                    }
                }
            }
    }

    private static void addToolGroups(Object configured, Path projectRoot, Map<String, ToolConfig> tools) throws IOException {
        if (configured == null) return;
        if (!(configured instanceof Iterable)) throw new IllegalArgumentException("toolGroups must be a list");
        Set<Path> paths = new LinkedHashSet<Path>();
        Set<String> ids = new LinkedHashSet<String>();
        Path canonicalRoot = projectRoot.toRealPath();
        for (Object value : (Iterable<?>) configured) {
            if (value == null) throw new IllegalArgumentException("toolGroups paths must be non-blank strings");
            Path relative = relativePath(value, null, "toolGroups path");
            Path logical = projectRoot.resolve(relative).normalize();
            if (!logical.startsWith(projectRoot.normalize())) throw new IllegalArgumentException("Tool group escapes package root: " + value);
            Path file = logical.toRealPath();
            if (!file.startsWith(canonicalRoot) || Files.isSymbolicLink(logical) || !Files.isRegularFile(file)) throw new IllegalArgumentException("Missing/unsafe tool group file: " + value);
            if (!paths.add(file)) throw new IllegalArgumentException("Duplicate tool group path: " + value);
            Map<?, ?> group = yaml(file, "Tool group");
            Path schema = schema(projectRoot, "att-tool-group-v2.2.schema.json");
            if (Files.isRegularFile(schema)) try { att.validation.JsonSchemaVerifier.verify(schema, group); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
            SchemaSupport.requireVersion(group, Version.TOOL_GROUP_SCHEMA, "tool group");
            SchemaSupport.rejectUnknown(group, "tool group", "schemaVersion", "id", "name", "description", "script", "ssh", "tools");
            String id = required(group, "id", "tool group");
            if (!id.matches("[A-Za-z_][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Tool group id must match [A-Za-z_][A-Za-z0-9_-]*: " + id);
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate tool group id: " + id);
            required(group, "name", "tool group " + id);
            required(group, "description", "tool group " + id);
            List<String> script = group.get("script") == null ? Collections.<String>emptyList() : command(group.get("script"), "tool group " + id + ".script");
            if (!script.isEmpty()) {
                if (script.get(0).trim().isEmpty() || script.get(0).contains("${")) throw new IllegalArgumentException("Tool group script executable must be static and non-blank: " + id);
                for (String token : script) if (token.contains("${")) throw new IllegalArgumentException("Tool group script cannot reference tool arguments: " + id);
            }
            SshConfig ssh = ssh(group.get("ssh"), "tool group " + id + ".ssh");
            if (!(group.get("tools") instanceof Map) || ((Map<?, ?>) group.get("tools")).isEmpty()) throw new IllegalArgumentException("Tool group tools must be a non-empty map: " + id);
            addTools(group.get("tools"), tools, id, script, ssh, "tool group " + id + ".tools", file);
        }
    }

    private static Map<?, ?> yaml(Path file, String owner) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object loaded = YamlSupport.parser().load(reader);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException(owner + " must be a YAML map: " + file);
            return (Map<?, ?>) loaded;
        }
    }

    private static List<String> command(Object value, String owner) {
        if (value instanceof String) {
            try {
                List<String> parsed = att.exec.CommandRunner.parseCommand((String) value);
                if (parsed.isEmpty()) throw new IllegalArgumentException(owner + " must not be blank");
                return parsed;
            } catch (IOException e) { throw new IllegalArgumentException("Invalid " + owner + ": " + e.getMessage(), e); }
        }
        if (value instanceof Iterable) {
            List<String> result = new ArrayList<String>();
            for (Object item : (Iterable<?>) value) {
                if (!(item instanceof String)) throw new IllegalArgumentException(owner + " argv items must be strings");
                result.add((String) item);
            }
            if (result.isEmpty()) throw new IllegalArgumentException(owner + " must contain at least one argv item");
            return result;
        }
        throw new IllegalArgumentException(owner + " must be a string or argv list");
    }

    private static SshConfig ssh(Object value, String owner) {
        if (value == null) return null;
        Map<?, ?> map = SchemaSupport.map(value, owner);
        validateSshMap(map, owner);
        String host = required(map, "host", owner);
        String user = required(map, "user", owner);
        if (invalidSshText(host) || invalidSshText(user)) throw new IllegalArgumentException(owner + " host/user must not contain whitespace or control characters");
        int port = boundedInteger(map.get("port"), 22, 1, 65535, owner + ".port");
        String identity = map.get("identityFile") == null ? "" : SchemaSupport.string(map.get("identityFile"), owner + ".identityFile", true);
        return new SshConfig(host, user, port, identity);
    }

    private static void validateSshMap(Map<?, ?> map, String owner) {
        SchemaSupport.rejectUnknown(map, owner, "host", "user", "port", "identityFile");
    }

    private static boolean invalidSshText(String value) { return value.matches(".*[\\s\\p{Cntrl}].*"); }

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
        java.util.Set<String> allowed = new java.util.LinkedHashSet<String>(java.util.Arrays.asList("result", "durationMs", "expectedResult", "actualResult", "caseLog", "reportLink", "runTime"));
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
