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
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        String id = invocationId == null || invocationId.trim().isEmpty() ? context.nextInvocationId(toolName) : invocationId;
        Instant started = Instant.now();
        Map<String, Object> resolvedInput = resolveMap(input, context);
        normalizeSinglePositionalArgument(tool, resolvedInput);
        validateArguments(tool, resolvedInput);
        expandDelimitedArgument(tool, resolvedInput);

        List<String> logicalArgv = expandCommand(tool, resolvedInput);
        List<String> argv = tool.ssh() == null ? resolveLocalExecutable(logicalArgv) : logicalArgv;
        String sshTransport = tool.ssh() == null ? "" : sshCommandRunner.transportName();
        CommandResult commandResult;
        long timeoutMs = actionTimeoutMs == null ? config.timeoutMs() : actionTimeoutMs.longValue();
        try {
            if (tool.ssh() == null) {
                Files.createDirectories(context.caseOutputDirectory());
                commandResult = commandRunner.run(argv, Duration.ofMillis(timeoutMs), context.caseOutputDirectory(),
                        localToolEnvironment(context));
            }
            else {
                SshCommandRunner.Execution execution = sshCommandRunner.run(tool.ssh(), logicalArgv, Duration.ofMillis(timeoutMs), projectRoot);
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
        if (!commandResult.timedOut()) try { parsed = parseOutput(rawOutput, tool.output()); } catch (Exception e) { parseFailure = e; }

        Map<String, Object> toolInvocation = new LinkedHashMap<String, Object>();
        toolInvocation.put("name", toolName);
        toolInvocation.put("input", resolvedInput);
        toolInvocation.put("output", parsed);
        toolInvocation.put("rawOutput", rawOutput);
        toolInvocation.put("stdout", commandResult.stdout());
        toolInvocation.put("stderr", commandResult.stderr());
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
            if (Files.exists(outputFile) && !overwrite) throw new IllegalArgumentException("saveAs file already exists and overwrite is false: " + saveAs);
            if (overwrite) Files.write(outputFile, commandResult.stdout().getBytes(StandardCharsets.UTF_8));
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
        if (recordAction) context.addAction(id, invocation);
        log.append("ACTION " + id, invocation);

        if (commandResult.timedOut()) {
            throw new ToolExecutionException("TIMEOUT", "Tool timed out: " + toolName, invocation, Integer.valueOf(commandResult.exitCode()), null);
        }
        if (parseFailure != null) throw new ToolExecutionException("OUTPUT_PARSE", "Unable to parse " + tool.output() + " output for tool " + toolName + ": " + parseFailure.getMessage(), invocation, Integer.valueOf(commandResult.exitCode()), parseFailure);
        return new ToolInvocationResult(toolName, id, parsed, invocation);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveMap(Map<String, Object> input, CaseRuntimeContext context) {
        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        Map<String, Object> renderContext = new LinkedHashMap<String, Object>(context.values());
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() instanceof Map) {
                resolved.put(entry.getKey(), resolveMap((Map<String, Object>) entry.getValue(), context));
            } else if (entry.getValue() instanceof String) {
                resolved.put(entry.getKey(), att.core.ValueNormalizer.normalize(renderValue((String) entry.getValue(), context)));
            } else {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return resolved;
    }

    private void validateArguments(ToolConfig tool, Map<String, Object> input) {
        for (String supplied : input.keySet()) {
            if (!tool.arguments().containsKey(supplied)) throw new IllegalArgumentException("Unknown argument '" + supplied + "' for tool " + tool.key());
        }
        for (ToolArgumentConfig argument : tool.arguments().values()) {
            if (argument.required() && (!input.containsKey(argument.key()) || blank(input.get(argument.key())))) {
                throw new IllegalArgumentException("Missing required argument '" + argument.key() + "' for tool " + tool.key());
            }
        }
    }

    private void normalizeSinglePositionalArgument(ToolConfig tool, Map<String, Object> input) {
        if (tool.arguments().size() != 1 || input.size() != 1 || !input.containsKey("arg0")) return;
        String declaredKey = tool.arguments().keySet().iterator().next();
        if ("arg0".equals(declaredKey)) return;
        Object value = input.remove("arg0");
        input.put(declaredKey, value);
    }

    private void expandDelimitedArgument(ToolConfig tool, Map<String, Object> input) {
        ToolArgumentConfig last = lastArgument(tool);
        if (last == null || !last.multiValue() || !input.containsKey(last.key())) return;
        String raw = input.get(last.key()) == null ? "" : String.valueOf(input.get(last.key()));
        List<String> values = new ArrayList<String>();
        boolean hasNonBlank = false;
        if (!raw.isEmpty()) {
            for (String item : raw.split(Pattern.quote(last.delimit()), -1)) {
                String normalized = att.core.ValueNormalizer.normalize(item);
                if (!normalized.isEmpty()) hasNonBlank = true;
                values.add(normalized);
            }
        }
        input.put(last.key(), hasNonBlank ? values : new ArrayList<String>());
    }

    private ToolArgumentConfig lastArgument(ToolConfig tool) {
        ToolArgumentConfig last = null;
        for (ToolArgumentConfig value : tool.arguments().values()) last = value;
        return last;
    }

    private boolean blank(Object value) { return value == null || att.core.ValueNormalizer.normalize(String.valueOf(value)).isEmpty(); }

    private List<String> expandCommand(ToolConfig tool, Map<String, Object> input) throws java.io.IOException {
        List<String> tokens = tool.commandArgv();
        List<String> argv = new ArrayList<String>();
        for (String token : tokens) {
            ToolArgumentConfig exact = exactArgumentPlaceholder(tool, token);
            if (exact != null) {
                Object value = input.get(exact.key());
                if (!provided(value)) {
                    if (exact.required()) throw new IllegalArgumentException("Missing required argument '" + exact.key() + "' for tool " + tool.key());
                    continue;
                }
                if (exact.namedArgv()) argv.add(exact.argName());
                if (value instanceof List) {
                    for (Object item : (List<?>) value) argv.add(item == null ? "" : String.valueOf(item));
                } else {
                    argv.add(String.valueOf(value));
                }
                continue;
            }
            Matcher matcher = VALUE.matcher(token);
            StringBuffer rendered = new StringBuffer();
            while (matcher.find()) {
                String key = argumentKey(matcher.group(1));
                if (!tool.arguments().containsKey(key)) throw new IllegalArgumentException("Tool command placeholder is not a declared argument: " + matcher.group(1));
                Object value = input.get(key);
                matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
            }
            matcher.appendTail(rendered);
            argv.add(rendered.toString());
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

    private String renderValue(String template, CaseRuntimeContext context) {
        Matcher matcher = VALUE.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            Object value = context.resolve(matcher.group(1));
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(output);
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

    private Object xmlToMap(String xml) throws Exception {
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
        Document document = builder.parse(new InputSource(new StringReader(xml)));
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
