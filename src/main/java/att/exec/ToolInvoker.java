/*
 * Author: Jeffrey + ChatGPT
 */

package att.exec;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.config.ToolArgumentConfig;
import att.config.YamlSupport;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.NamedNodeMap;
import org.xml.sax.InputSource;
import org.yaml.snakeyaml.Yaml;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes configured tools and records each invocation into the case log.
 */
public class ToolInvoker {
    private static final Pattern VALUE = Pattern.compile("\\$\\{([^}]+)}");
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final CommandRunner commandRunner;
    private final SshCommandRunner sshCommandRunner;

    public ToolInvoker(Path projectRoot, FrameworkConfig config) {
        this(projectRoot, config, new CommandRunner());
    }

    public ToolInvoker(Path projectRoot, FrameworkConfig config, CommandRunner commandRunner) {
        this(projectRoot, config, commandRunner, new SshCommandRunner(commandRunner));
    }

    ToolInvoker(Path projectRoot, FrameworkConfig config, CommandRunner commandRunner, SshCommandRunner sshCommandRunner) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
        this.commandRunner = commandRunner;
        this.sshCommandRunner = sshCommandRunner;
    }

    public ToolInvocationResult invoke(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        return invoke(invocationId, toolName, input, context, log, true);
    }

    public ToolInvocationResult invokeAttempt(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log, Long timeoutMs, String saveAs) throws Exception {
        return invokeAttempt(invocationId, toolName, input, context, log, timeoutMs, saveAs, false);
    }

    public ToolInvocationResult invokeAttempt(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log, Long timeoutMs, String saveAs, boolean overwrite) throws Exception {
        return invoke(invocationId, toolName, input, context, log, false, timeoutMs, saveAs, overwrite);
    }

    public ToolInvocationResult invokeAttempt(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log, Long timeoutMs) throws Exception {
        return invokeAttempt(invocationId, toolName, input, context, log, timeoutMs, "");
    }

    private ToolInvocationResult invoke(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log, boolean recordAction) throws Exception {
        return invoke(invocationId, toolName, input, context, log, recordAction, null, "", false);
    }

    private ToolInvocationResult invoke(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log, boolean recordAction, Long actionTimeoutMs, String saveAs, boolean overwrite) throws Exception {
        ToolConfig tool = config.tool(toolName);
        if (tool == null) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TOOL_INVALID,
                    "Unknown configured tool '" + toolName + "'", "Available tools: " + String.join(", ", config.tools().keySet()),
                    null, "call", null, null, null, null, null,
                    "Correct the case-sensitive qualified group.tool name or define it in the loaded configuration.", null);
        }
        String id = invocationId == null || invocationId.trim().isEmpty() ? context.nextInvocationId(toolName) : invocationId;
        Instant started = Instant.now();
        Map<String, Object> resolvedInput = resolveMap(input);
        normalizeSinglePositionalArgument(tool, resolvedInput);
        validateArguments(tool, resolvedInput);
        expandDelimitedArguments(tool, resolvedInput);

        List<String> logicalArgv = expandCommand(tool, resolvedInput);
        List<String> argv = tool.ssh() == null ? resolveLocalExecutable(logicalArgv) : logicalArgv;
        String sshTransport = tool.ssh() == null ? "" : sshCommandRunner.transportName();
        CommandResult commandResult;
        long timeoutMs = actionTimeoutMs == null ? config.timeoutMs() : actionTimeoutMs.longValue();
        CommandRunner.CapturePolicy capture = capturePolicy(context, id);
        try {
            if (tool.ssh() == null) {
                Files.createDirectories(context.caseOutputDirectory());
                commandResult = commandRunner.runWithCapture(argv, Duration.ofMillis(timeoutMs), context.caseOutputDirectory(),
                        localToolEnvironment(context), capture);
            }
            else {
                SshCommandRunner.Execution execution = sshCommandRunner.run(tool.ssh(), logicalArgv, Duration.ofMillis(timeoutMs), projectRoot, capture);
                commandResult = execution.result(); argv = execution.argv(); sshTransport = execution.transport();
            }
        }
        catch (java.io.IOException e) {
            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            evidence.put("id", id); evidence.put("type", "tool"); evidence.put("name", toolName);
            evidence.put("status", "ERROR"); evidence.put("category", "IO_ERROR"); evidence.put("message", e.getMessage());
            evidence.put("logicalArgv", logicalArgv); evidence.put("argv", argv);
            if (tool.grouped()) { evidence.put("groupId", tool.groupId()); evidence.put("toolKey", tool.localKey()); }
            if (tool.ssh() != null) { evidence.put("sshDestination", tool.ssh().destination()); evidence.put("sshPort", tool.ssh().port()); evidence.put("sshTransport", sshTransport); }
            throw new ToolExecutionException("IO_ERROR", "Tool I/O failed: " + toolName + ": " + e.getMessage(), evidence, null, e);
        }
        String command = printableCommand(argv);
        String rawOutput = commandResult.stdout().trim();
        Object parsed = rawOutput;
        Exception parseFailure = null;
        if (!commandResult.timedOut()) try {
            parsed = structured(tool.output()) && commandResult.stdoutArtifact() != null && !commandResult.stdoutArtifactTruncated()
                    ? parseOutput(commandResult.stdoutArtifact(), tool.output()) : parseOutput(rawOutput, tool.output());
        } catch (Exception e) { parseFailure = e; }

        Map<String, Object> toolInvocation = new LinkedHashMap<String, Object>();
        toolInvocation.put("name", toolName);
        toolInvocation.put("input", resolvedInput);
        toolInvocation.put("output", parsed);
        toolInvocation.put("rawOutput", rawOutput);
        toolInvocation.put("stdout", commandResult.stdout());
        toolInvocation.put("stderr", commandResult.stderr());
        addCaptureEvidence(toolInvocation, commandResult);
        toolInvocation.put("command", command);
        toolInvocation.put("logicalArgv", logicalArgv);
        toolInvocation.put("argv", argv);
        if (tool.grouped()) {
            toolInvocation.put("groupId", tool.groupId());
            toolInvocation.put("toolKey", tool.localKey());
        }
        if (tool.ssh() != null) {
            Map<String, Object> ssh = new LinkedHashMap<String, Object>();
            ssh.put("destination", tool.ssh().destination());
            ssh.put("port", tool.ssh().port());
            ssh.put("identityFileConfigured", !tool.ssh().identityFile().isEmpty());
            ssh.put("transport", sshTransport);
            toolInvocation.put("ssh", ssh);
        }
        toolInvocation.put("timeoutMs", timeoutMs);
        toolInvocation.put("status", commandResult.timedOut() ? "TIMEOUT" : (commandResult.exitCode() == 0 && parseFailure == null ? "PASS" : "ERROR"));
        if (parseFailure != null) toolInvocation.put("parserDiagnostic", parseFailure.getMessage());
        toolInvocation.put("durationMs", Duration.between(started, Instant.now()).toMillis());
        toolInvocation.put("exitCode", commandResult.exitCode());
        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("id", id);
        invocation.put("type", "tool");
        invocation.put("status", toolInvocation.get("status"));
        invocation.put("durationMs", toolInvocation.get("durationMs"));
        invocation.put("input", resolvedInput);
        invocation.put("output", parsed);
        invocation.put("rawOutput", rawOutput);
        invocation.put("stdout", commandResult.stdout());
        invocation.put("stderr", commandResult.stderr());
        addCaptureEvidence(invocation, commandResult);
        invocation.put("exitCode", commandResult.exitCode());
        invocation.put("timeoutMs", timeoutMs);
        invocation.put("command", command);
        invocation.put("logicalArgv", logicalArgv);
        invocation.put("argv", argv);
        if (saveAs != null && !saveAs.trim().isEmpty()) {
            Path directory = context.caseLogDirectory();
            Files.createDirectories(directory);
            Path outputFile = directory.resolve(att.core.IdentifierValidator.relativePath(saveAs, "tool saveAs")).normalize();
            if (!outputFile.startsWith(directory.normalize())) throw new IllegalArgumentException("Tool saveAs must stay under case log directory: " + saveAs);
            Files.createDirectories(outputFile.getParent());
            boolean captureIsTarget = commandResult.stdoutArtifact() != null && commandResult.stdoutArtifact().normalize().equals(outputFile);
            if (Files.exists(outputFile) && !overwrite && !captureIsTarget) throw new IllegalArgumentException("saveAs file already exists and overwrite is false: " + saveAs);
            if (captureIsTarget) {
                // The streamed artifact already is the requested output.
            } else if (commandResult.stdoutArtifact() != null && Files.isRegularFile(commandResult.stdoutArtifact())) {
                if (overwrite) Files.copy(commandResult.stdoutArtifact(), outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                else Files.copy(commandResult.stdoutArtifact(), outputFile);
            } else if (overwrite) Files.write(outputFile, commandResult.stdout().getBytes(StandardCharsets.UTF_8));
            else Files.write(outputFile, commandResult.stdout().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
            invocation.put("outputFile", outputFile.toString());
            toolInvocation.put("outputFile", outputFile.toString());
        }
        // Keep the action node focused on action metadata while exposing the
        // invoked tool through the V2 uppercase TOOL child node.
        Map<String, Object> toolNode = new LinkedHashMap<String, Object>();
        if (tool.grouped()) {
            Map<String, Object> groupNode = new LinkedHashMap<String, Object>();
            groupNode.put(tool.localKey(), toolInvocation);
            toolNode.put(tool.groupId(), groupNode);
        } else toolNode.put(toolName, toolInvocation);
        invocation.put("TOOL", toolNode);
        if (recordAction) {
            context.addAction(id, invocation);
            log.appendToolInvocation("ACTION " + id, invocation);
        }

        if (commandResult.timedOut()) {
            throw new ToolExecutionException("TIMEOUT", "Tool timed out: " + toolName, invocation, Integer.valueOf(commandResult.exitCode()), null);
        }
        if (parseFailure != null) throw new ToolExecutionException("OUTPUT_PARSE", "Unable to parse " + tool.output() + " output for tool " + toolName + ": " + parseFailure.getMessage(), invocation, Integer.valueOf(commandResult.exitCode()), parseFailure);
        return new ToolInvocationResult(toolName, id, parsed, invocation);
    }

    private CommandRunner.CapturePolicy capturePolicy(CaseRuntimeContext context, String invocationId) throws Exception {
        Path directory = context.caseOutputDirectory().resolve("process-output");
        Files.createDirectories(directory);
        String base = invocationId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path stdout = available(directory, base, ".stdout");
        Path stderr = stdout.resolveSibling(stdout.getFileName().toString().replaceFirst("\\.stdout$", ".stderr"));
        return new CommandRunner.CapturePolicy(config.processOutput().memoryLimitBytes(), config.processOutput().artifactLimitBytes(), stdout, stderr);
    }

    private Path available(Path directory, String base, String suffix) {
        Path candidate = directory.resolve(base + suffix);
        for (int sequence = 2; Files.exists(candidate) || Files.exists(candidate.resolveSibling(candidate.getFileName().toString().replaceFirst("\\.stdout$", ".stderr"))); sequence++) candidate = directory.resolve(base + "-" + sequence + suffix);
        return candidate;
    }

    private void addCaptureEvidence(Map<String, Object> evidence, CommandResult result) {
        evidence.put("stdoutBytes", result.stdoutBytes()); evidence.put("stderrBytes", result.stderrBytes());
        evidence.put("stdoutTruncated", result.stdoutTruncated()); evidence.put("stderrTruncated", result.stderrTruncated());
        evidence.put("stdoutArtifactTruncated", result.stdoutArtifactTruncated()); evidence.put("stderrArtifactTruncated", result.stderrArtifactTruncated());
        if (result.stdoutArtifact() != null) evidence.put("stdoutArtifact", result.stdoutArtifact().toString());
        if (result.stderrArtifact() != null) evidence.put("stderrArtifact", result.stderrArtifact().toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveMap(Map<String, Object> input) {
        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() instanceof Map) {
                resolved.put(entry.getKey(), resolveMap((Map<String, Object>) entry.getValue()));
            } else if (entry.getValue() instanceof String) {
                resolved.put(entry.getKey(), att.core.ValueNormalizer.normalize((String) entry.getValue()));
            } else {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return resolved;
    }

    private void validateArguments(ToolConfig tool, Map<String, Object> input) {
        for (String supplied : input.keySet()) {
            if (!tool.arguments().containsKey(supplied)) throw toolArgumentError(tool, supplied,
                    "Unknown argument '" + supplied + "'", "Declared arguments: " + tool.arguments().keySet(),
                    "Use an exact case-sensitive declared argument name.");
        }
        for (ToolArgumentConfig argument : tool.arguments().values()) {
            if (argument.required() && (!input.containsKey(argument.key()) || blank(input.get(argument.key())))) {
                throw toolArgumentError(tool, argument.key(), "Missing required argument '" + argument.key() + "'",
                        "required=true, providedValue=" + input.get(argument.key()),
                        "Provide a non-blank value for this argument in the tool call.");
            }
        }
    }

    private att.validation.DiagnosticException toolArgumentError(ToolConfig tool, String argument, String summary,
                                                                  String detail, String suggestion) {
        return new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TOOL_INVALID,
                summary + " for tool '" + tool.key() + "'", detail,
                tool.sourceFile() == null ? null : tool.sourceFile().toString(),
                "tools." + tool.key() + ".arguments." + argument, null, null, null, null, null, suggestion, null);
    }

    private void normalizeSinglePositionalArgument(ToolConfig tool, Map<String, Object> input) {
        if (tool.arguments().size() != 1 || input.size() != 1 || !input.containsKey("arg0")) return;
        String declaredKey = tool.arguments().keySet().iterator().next();
        if ("arg0".equals(declaredKey)) return;
        Object value = input.remove("arg0");
        input.put(declaredKey, value);
    }

    private void expandDelimitedArguments(ToolConfig tool, Map<String, Object> input) {
        for (ToolArgumentConfig argument : tool.arguments().values()) {
            if (!argument.multiValue() || !input.containsKey(argument.key())) continue;
            String raw = input.get(argument.key()) == null ? "" : String.valueOf(input.get(argument.key()));
            List<String> values = new ArrayList<String>();
            boolean hasNonBlank = false;
            if (!raw.isEmpty()) {
                for (String item : raw.split(Pattern.quote(argument.delimit()), -1)) {
                    String normalized = att.core.ValueNormalizer.normalize(item);
                    if (!normalized.isEmpty()) hasNonBlank = true;
                    values.add(normalized);
                }
            }
            input.put(argument.key(), hasNonBlank ? values : new ArrayList<String>());
        }
    }

    private boolean blank(Object value) { return value == null || att.core.ValueNormalizer.normalize(String.valueOf(value)).isEmpty(); }

    private List<String> expandCommand(ToolConfig tool, Map<String, Object> input) throws Exception {
        List<String> tokens = tool.commandArgv();
        List<String> argv = new ArrayList<String>();
        Map<String, Object> declaredValues = new LinkedHashMap<String, Object>();
        for (String key : tool.arguments().keySet()) declaredValues.put(key, input.get(key));
        Map<String, Object> scopedValues = new LinkedHashMap<String, Object>(declaredValues);
        scopedValues.put("input", new LinkedHashMap<String, Object>(declaredValues));
        Map<String, Object> toolScope = new LinkedHashMap<String, Object>();
        toolScope.put("input", new LinkedHashMap<String, Object>(declaredValues));
        scopedValues.put("TOOL", toolScope);
        att.template.UnifiedTemplateEngine expressionEngine = new att.template.UnifiedTemplateEngine(null);
        for (String token : tokens) {
            ToolArgumentConfig exact = exactArgumentPlaceholder(tool, token);
            if (exact != null) {
                Object value = input.get(exact.key());
                if (!provided(value)) {
                    if (exact.required()) throw new IllegalArgumentException("Missing required argument '" + exact.key() + "' for tool " + tool.key());
                    continue;
                }
                if (value instanceof List) {
                    if (exact.namedArgv() && !exact.repeatArgName()) argv.add(exact.argName());
                    for (Object item : (List<?>) value) {
                        if (exact.namedArgv() && exact.repeatArgName()) argv.add(exact.argName());
                        argv.add(item == null ? "" : String.valueOf(item));
                    }
                } else {
                    if (exact.namedArgv()) argv.add(exact.argName());
                    argv.add(String.valueOf(value));
                }
                continue;
            }
            argv.add(expressionEngine.renderScoped(token, scopedValues));
        }
        if (!tool.groupScriptArgv().isEmpty()) {
            List<String> dispatched = new ArrayList<String>(tool.groupScriptArgv());
            dispatched.add(tool.localKey());
            dispatched.addAll(argv);
            return dispatched;
        }
        return argv;
    }

    private List<String> resolveLocalExecutable(List<String> logicalArgv) {
        List<String> executed = new ArrayList<String>(logicalArgv);
        if (executed.isEmpty()) return executed;
        String executable = executed.get(0);
        if (executable.startsWith("./") || executable.startsWith("../")) {
            executed.set(0, projectRoot.resolve(executable).normalize().toString());
        }
        return executed;
    }

    private Map<String, String> localToolEnvironment(CaseRuntimeContext context) {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("ATT_ROOT_DIR", projectRoot.toString());
        environment.put("ATT_CASE_OUTPUT_DIR", context.caseOutputDirectory().toString());
        return environment;
    }

    private ToolArgumentConfig exactArgumentPlaceholder(ToolConfig tool, String token) {
        Matcher matcher = VALUE.matcher(token);
        if (!matcher.matches()) return null;
        return tool.arguments().get(argumentKey(matcher.group(1)));
    }

    private boolean provided(Object value) {
        if (value instanceof List) return !((List<?>) value).isEmpty();
        return !blank(value);
    }

    private String argumentKey(String expression) {
        if (expression.startsWith("TOOL.input.")) return expression.substring(11);
        if (expression.startsWith("input.")) return expression.substring(6);
        return expression;
    }

    private String printableCommand(List<String> argv) {
        StringBuilder output = new StringBuilder();
        for (String item : argv) {
            if (output.length() > 0) output.append(' ');
            output.append('\'').append(item.replace("'", "'\\''")).append('\'');
        }
        return output.toString();
    }

    public Object parseOutput(String text, String outputType) throws Exception {
        if ("yaml".equalsIgnoreCase(outputType)) {
            Object loaded = YamlSupport.parser().load(text);
            return loaded == null ? new LinkedHashMap<String, Object>() : loaded;
        }
        if ("xml".equalsIgnoreCase(outputType)) {
            return xmlToMap(text);
        }
        if ("json".equalsIgnoreCase(outputType)) {
            return att.validation.JsonSupport.mapper().readValue(text, Object.class);
        }
        return text;
    }

    private Object parseOutput(Path file, String outputType) throws Exception {
        if ("json".equalsIgnoreCase(outputType)) try (java.io.InputStream input = Files.newInputStream(file)) { return att.validation.JsonSupport.mapper().readValue(input, Object.class); }
        if ("yaml".equalsIgnoreCase(outputType)) try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { synchronized (YamlSupport.parser()) { Object loaded = YamlSupport.parser().load(reader); return loaded == null ? new LinkedHashMap<String, Object>() : loaded; } }
        if ("xml".equalsIgnoreCase(outputType)) return xmlToMap(new InputSource(file.toUri().toString()));
        return parseOutput(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), outputType);
    }

    private boolean structured(String outputType) { return "json".equalsIgnoreCase(outputType) || "yaml".equalsIgnoreCase(outputType) || "xml".equalsIgnoreCase(outputType); }

    private Object xmlToMap(String xml) throws Exception {
        return xmlToMap(new InputSource(new StringReader(xml)));
    }

    private Object xmlToMap(InputSource source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            public void warning(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        Document document = builder.parse(source);
        Object value = elementValue(document.getDocumentElement());
        if (!(value instanceof Map)) return value;
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("name", xmlName(document.getDocumentElement())); result.putAll((Map<String,Object>) value); return result;
    }

    @SuppressWarnings("unchecked")
    private Object elementValue(Node node) {
        NodeList children = node.getChildNodes();
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        NamedNodeMap nodeAttributes = node.getAttributes();
        if (nodeAttributes != null) for (int i = 0; i < nodeAttributes.getLength(); i++) {
            Node attribute = nodeAttributes.item(i);
            if (attribute.getNodeName().startsWith("xmlns")) continue;
            attributes.put(xmlName(attribute), attribute.getNodeValue());
        }
        StringBuilder text = new StringBuilder();
        Map<String, Object> grouped = new LinkedHashMap<String, Object>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String name = xmlName(child);
                Object item = elementValue(child);
                Object existing = grouped.get(name);
                if (existing == null) grouped.put(name, item);
                else if (existing instanceof List) ((List<Object>) existing).add(item);
                else { List<Object> repeated = new ArrayList<Object>(); repeated.add(existing); repeated.add(item); grouped.put(name, repeated); }
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        String normalizedText = text.toString().trim();
        if (grouped.isEmpty()) {
            if (attributes.isEmpty()) return normalizedText;
        }
        if (!attributes.isEmpty()) map.put("attributes", attributes);
        if (!normalizedText.isEmpty()) map.put("text", normalizedText);
        map.putAll(grouped);
        return map;
    }

    private String xmlName(Node node) {
        String local = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
        if ("ignore".equals(config.xmlNamespaceMode())) return local;
        String uri = node.getNamespaceURI();
        return "{" + (uri == null ? "" : uri) + "}" + local;
    }

    private String extension(String outputType) {
        return "xml".equalsIgnoreCase(outputType) ? "xml" : ("yaml".equalsIgnoreCase(outputType) ? "yaml" : ("json".equalsIgnoreCase(outputType) ? "json" : "txt"));
    }
}
