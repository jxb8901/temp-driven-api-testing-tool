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
                    ? new String[]{"schemaVersion", "outputDirectory", "environment", "timeoutMs", "templates", "testcase", "run", "execution", "report", "caseLog", "xml", "toolGroups", "ssh", "tools"}
                    : new String[]{"schemaVersion", "outputDirectory", "environment", "timeoutMs", "templates", "testcase", "run", "report", "caseLog", "xml", "tools"});
            validateGlobalMappings(map);
            Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
            SshConfig globalSsh = ssh(map.get("ssh"), "config.ssh");
            try {
                addTools(map.get("tools"), tools, "", Collections.<String>emptyList(), globalSsh, "tools", path);
                if (v22) addToolGroups(map.get("toolGroups"), projectRoot, tools);
            } catch (Exception e) {
                throw att.validation.DiagnosticException.wrap(att.validation.DiagnosticCodes.TOOL_INVALID,
                        "Invalid tool configuration", e, path.toString(), "tools/toolGroups",
                        "Check the qualified tool/group name, required metadata, declared arguments, command placeholders, and executable path.");
            }
            return new FrameworkConfig(relativePath(map.get("outputDirectory"), "output", "outputDirectory"),
                    Paths.get("report"), Paths.get("logs"),
                    map.get("environment") == null ? "SIT" : SchemaSupport.string(map.get("environment"), "environment", true), positiveInteger(map.get("timeoutMs"), 10000, "timeoutMs"),
                    templatesRoot(map), testcasesRoot(map), tools, report(map), run(map), null, "", "", null, null, 1, xmlNamespaceMode(map), "", caseLogYamlAnchors(map), processOutput(map));
        } catch (att.validation.DiagnosticException e) {
            throw e;
        } catch (Exception e) {
            att.validation.JsonSchemaVerifier.SchemaValidationException schema = att.validation.JsonSchemaVerifier.SchemaValidationException.find(e);
            String field = schema == null ? "config" : schema.field();
            boolean toolField = field != null && (field.contains("tools") || field.contains("toolGroups") || field.contains("ssh"));
            throw new att.validation.DiagnosticException(toolField ? att.validation.DiagnosticCodes.TOOL_INVALID : att.validation.DiagnosticCodes.CONFIG_INVALID,
                    toolField ? "Invalid tool configuration" : "Invalid global configuration",
                    e.getMessage(), path.toString(), field, null, null, null, null, null,
                    toolField
                            ? "Check the qualified tool/group name, descriptor fields, argument declarations, and command argv contract."
                            : "Compare the reported field with the strict config schema and correct its name, type, or value.", e);
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
        if (map.get("execution") != null) {
            Map<?, ?> execution = SchemaSupport.map(map.get("execution"), "config.execution");
            SchemaSupport.rejectUnknown(execution, "config.execution", "processOutput");
            if (execution.get("processOutput") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(execution.get("processOutput"), "config.execution.processOutput"), "config.execution.processOutput", "memoryLimitBytes", "artifactLimitBytes");
        }
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
            SchemaSupport.rejectUnknown(report, "config.report", "mode", "fileNamePattern", "columns", "junit", "html");
            if (report.get("junit") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(report.get("junit"), "config.report.junit"), "config.report.junit", "caseLogEmbedThresholdBytes");
            if (report.get("html") != null) SchemaSupport.rejectUnknown(SchemaSupport.map(report.get("html"), "config.report.html"), "config.report.html", "caseLogInlineLimitBytes");
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
            if (commandOwnsExecutable && (tokens.get(0).contains("${") || tokens.get(0).contains("#{"))) throw new IllegalArgumentException("Tool executable must be static and non-blank: " + tool);
            att.template.UnifiedTemplateEngine expressionEngine = new att.template.UnifiedTemplateEngine(null);
            for (String token : tokens) {
                expressionEngine.validateValueSyntax(token);
                for (att.template.ToolCallParser.ParsedCall call : expressionEngine.parseCalls(token)) expressionEngine.validateBuiltInCall(call);
                for (att.template.ToolCallParser.ParsedCall call : expressionEngine.parseCalls(token)) {
                    for (att.template.ToolCallParser.Argument callArgument : call.arguments()) {
                        String expression = callArgument.expression().trim();
                        if (expression.startsWith("input.") || expression.startsWith("TOOL.input.")) {
                            String argument = expression.startsWith("TOOL.input.") ? expression.substring(11) : expression.substring(6);
                            if (!arguments.containsKey(argument)) throw new IllegalArgumentException("Tool command expression must reference a declared argument: " + tool + "." + expression);
                        }
                    }
                }
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(token);
                while (matcher.find()) {
                    String expression = matcher.group(1);
                    String argument = expression.startsWith("TOOL.input.") ? expression.substring(11) : expression.startsWith("input.") ? expression.substring(6) : expression;
                    boolean argumentForm = expression.startsWith("TOOL.input.") || expression.startsWith("input.") || expression.matches("[A-Za-z_][A-Za-z0-9_]*");
                    if (!argumentForm || !arguments.containsKey(argument)) throw new IllegalArgumentException("Tool command placeholders must reference a declared argument: " + tool + "." + expression);
                }
            }
            for (ToolArgumentConfig argument : arguments.values()) {
                String direct = "${" + argument.key() + "}";
                String input = "${input." + argument.key() + "}";
                String legacy = "${TOOL.input." + argument.key() + "}";
                int references = 0;
                int exactReferences = 0;
                for (String token : tokens) {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(token);
                    while (matcher.find()) {
                        String expression = matcher.group(1);
                        String key = expression.startsWith("TOOL.input.") ? expression.substring(11) : expression.startsWith("input.") ? expression.substring(6) : expression;
                        if (argument.key().equals(key)) references++;
                    }
                    boolean exact = token.equals(direct) || token.equals(input) || token.equals(legacy);
                    for (att.template.ToolCallParser.ParsedCall call : expressionEngine.parseCalls(token)) {
                        for (att.template.ToolCallParser.Argument callArgument : call.arguments()) {
                            String expression = callArgument.expression().trim();
                            if (argument.key().equals(expression) || ("input." + argument.key()).equals(expression)
                                    || ("TOOL.input." + argument.key()).equals(expression)) references++;
                        }
                    }
                    if (exact) exactReferences++;
                    boolean transformedReference = expressionEngine.referencesBareArgument(token, argument.key())
                            || expressionEngine.referencesBareArgument(token, "input." + argument.key())
                            || expressionEngine.referencesBareArgument(token, "TOOL.input." + argument.key());
                    if (argument.multiValue() && (token.contains(direct) || token.contains(input) || token.contains(legacy) || transformedReference) && !exact) {
                        throw new IllegalArgumentException("Delimited argument placeholder must occupy one complete argv token: " + tool + "." + argument.key());
                    }
                }
                if (argument.namedArgv() && (references != 1 || exactReferences != 1)) {
                    throw new IllegalArgumentException("argName argument placeholder must occupy exactly one complete argv token: " + tool + "." + argument.key());
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
            loadToolGroup(file, projectRoot, tools, ids);
        }
    }

    private static void loadToolGroup(Path file, Path projectRoot, Map<String, ToolConfig> tools, Set<String> ids) {
        try {
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
                if (script.get(0).trim().isEmpty() || script.get(0).contains("${") || script.get(0).contains("#{")) throw new IllegalArgumentException("Tool group script executable must be static and non-blank: " + id);
                for (String token : script) if (token.contains("${") || token.contains("#{")) throw new IllegalArgumentException("Tool group script cannot contain expressions: " + id);
            }
            SshConfig ssh = ssh(group.get("ssh"), "tool group " + id + ".ssh");
            if (!(group.get("tools") instanceof Map) || ((Map<?, ?>) group.get("tools")).isEmpty()) throw new IllegalArgumentException("Tool group tools must be a non-empty map: " + id);
            addTools(group.get("tools"), tools, id, script, ssh, "tool group " + id + ".tools", file);
        } catch (att.validation.DiagnosticException e) {
            throw e;
        } catch (Exception e) {
            att.validation.JsonSchemaVerifier.SchemaValidationException schema = att.validation.JsonSchemaVerifier.SchemaValidationException.find(e);
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TOOL_INVALID,
                    "Invalid tool group configuration", e.getMessage(), file.toString(),
                    schema == null ? "toolGroup" : schema.field(), null, null, null, null, null,
                    "Correct the reported group metadata, script/SSH settings, tool descriptor, arguments, or command argv.", e);
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
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Tool argument keys must be strings: " + toolKey);
            String key = String.valueOf(entry.getKey());
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Tool argument name must match [A-Za-z_][A-Za-z0-9_]*: " + toolKey + "." + key);
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Argument descriptor must be a map: " + toolKey + "." + key);
            Map<?, ?> descriptor = (Map<?, ?>) entry.getValue();
            SchemaSupport.rejectUnknown(descriptor, "tools." + toolKey + ".arguments." + key, "name", "description", "required", "delimit", "argName", "argNameMode");
            if (!descriptor.containsKey("required")) throw new IllegalArgumentException("required is mandatory for argument: " + toolKey + "." + key);
            String delimit = descriptor.get("delimit") == null ? "" : SchemaSupport.string(descriptor.get("delimit"), "tools." + toolKey + ".arguments." + key + ".delimit", true);
            Object argNameValue = descriptor.get("argName");
            if (argNameValue != null && !(argNameValue instanceof String)) throw new IllegalArgumentException("argName must be a string: " + toolKey + "." + key);
            String argName = argNameValue == null ? "" : (String) argNameValue;
            if (!argName.isEmpty() && argName.matches(".*[\\s\\p{Cntrl}].*")) throw new IllegalArgumentException("argName must be one non-blank argv token: " + toolKey + "." + key);
            String argNameMode = descriptor.get("argNameMode") == null ? "once" : SchemaSupport.string(descriptor.get("argNameMode"), "tools." + toolKey + ".arguments." + key + ".argNameMode", true);
            if (!("once".equals(argNameMode) || "repeat".equals(argNameMode))) throw new IllegalArgumentException("argNameMode must be once or repeat: " + toolKey + "." + key);
            result.put(key, new ToolArgumentConfig(key, required(descriptor, "name", "argument " + key),
                    required(descriptor, "description", "argument " + key), booleanValue(descriptor.get("required"), false,
                    "required for argument " + toolKey + "." + key), delimit, argName, argNameMode));
        }
        return result;
    }

    private static ReportConfig report(Map<?, ?> map) {
        Object value = map.get("report");
        if (!(value instanceof Map)) return null;
        Map<?, ?> report = (Map<?, ?>) value;
        String mode = report.get("mode") == null ? "append-to-copy" : SchemaSupport.string(report.get("mode"), "report.mode", true); if (!("append-to-copy".equals(mode) || "none".equals(mode))) throw new IllegalArgumentException("report.mode must be append-to-copy or none");
        String pattern = report.get("fileNamePattern") == null ? "${suiteName}.result.xlsx" : SchemaSupport.string(report.get("fileNamePattern"), "report.fileNamePattern", true);
        att.template.UnifiedTemplateEngine reportExpressions = new att.template.UnifiedTemplateEngine(null);
        if (!pattern.contains("${suiteName}") && !reportExpressions.referencesBareArgument(pattern, "suiteName")) throw new IllegalArgumentException("report.fileNamePattern must reference suiteName");
        reportExpressions.validateValueSyntax(pattern);
        for (att.template.ToolCallParser.ParsedCall call : reportExpressions.parseCalls(pattern)) reportExpressions.validateBuiltInCall(call);
        for (String path : reportExpressions.parseValuePaths(pattern)) if (!"suiteName".equals(path)) throw new IllegalArgumentException("report.fileNamePattern only supports ${suiteName}: ${" + path + "}");
        Object junitValue = report.get("junit");
        Map<?, ?> junit = junitValue instanceof Map ? (Map<?, ?>) junitValue : java.util.Collections.emptyMap();
        Object htmlValue = report.get("html");
        Map<?, ?> html = htmlValue instanceof Map ? (Map<?, ?>) htmlValue : java.util.Collections.emptyMap();
        return new ReportConfig(mode, pattern, reportColumns(report.get("columns")), boundedInteger(junit.get("caseLogEmbedThresholdBytes"), 10240, 0, 1048576, "report.junit.caseLogEmbedThresholdBytes"), boundedInteger(html.get("caseLogInlineLimitBytes"), 32768, 0, 1048576, "report.html.caseLogInlineLimitBytes"));
    }

    private static ProcessOutputConfig processOutput(Map<?, ?> map) {
        Object executionValue = map.get("execution");
        Map<?, ?> execution = executionValue instanceof Map ? (Map<?, ?>) executionValue : java.util.Collections.emptyMap();
        Object outputValue = execution.get("processOutput");
        Map<?, ?> output = outputValue instanceof Map ? (Map<?, ?>) outputValue : java.util.Collections.emptyMap();
        int memory = boundedInteger(output.get("memoryLimitBytes"), 65536, 1024, 1048576, "execution.processOutput.memoryLimitBytes");
        int artifact = boundedInteger(output.get("artifactLimitBytes"), 104857600, memory, 1073741824, "execution.processOutput.artifactLimitBytes");
        return new ProcessOutputConfig(memory, artifact);
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
