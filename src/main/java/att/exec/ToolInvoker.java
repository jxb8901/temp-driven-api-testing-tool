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
import att.template.TemplateRenderer;
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

    public ToolInvoker(Path projectRoot, FrameworkConfig config) {
        this(projectRoot, config, new CommandRunner());
    }

    public ToolInvoker(Path projectRoot, FrameworkConfig config, CommandRunner commandRunner) {
        this.projectRoot = projectRoot;
        this.config = config;
        this.commandRunner = commandRunner;
    }

    public ToolInvocationResult invoke(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        return invoke(invocationId, toolName, input, context, log, true);
    }

    public ToolInvocationResult invokeAttempt(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log, Long timeoutMs) throws Exception {
        return invoke(invocationId, toolName, input, context, log, false, timeoutMs);
    }

    private ToolInvocationResult invoke(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log, boolean recordAction) throws Exception {
        return invoke(invocationId, toolName, input, context, log, recordAction, null);
    }

    private ToolInvocationResult invoke(String invocationId, String toolName, Map<String, Object> input, CaseRuntimeContext context, CaseExecutionLog log, boolean recordAction, Long actionTimeoutMs) throws Exception {
        ToolConfig tool = config.tool(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        String id = invocationId == null || invocationId.trim().isEmpty() ? context.nextInvocationId(toolName) : invocationId;
        Instant started = Instant.now();
        Path actionDir = context.actionOutputDir(id);
        Files.createDirectories(actionDir);
        Path inputFile = actionDir.resolve("input.yaml");
        Path outputFile = actionDir.resolve("output." + extension(tool.output()));

        Map<String, Object> resolvedInput = resolveMap(input, context);
        validateArguments(tool, resolvedInput);
        expandDelimitedArgument(tool, resolvedInput);
        Files.write(inputFile, new Yaml().dump(resolvedInput).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> renderContext = new LinkedHashMap<String, Object>(context.values());
        flatten("TOOL.input", resolvedInput, renderContext);
        renderContext.put("TOOL.inputFile", inputFile.toString());
        renderContext.put("TOOL.outputFile", outputFile.toString());
        ToolArgumentConfig last = lastArgument(tool);
        if (last != null && last.multiValue()) {
            Object value = resolvedInput.get(last.key());
            renderContext.put("TOOL.input." + last.key(), value instanceof List ? shellArgs(stringList((List<?>) value)) : "");
        }
        String command = TemplateRenderer.render(tool.command(), renderContext);
        CommandResult commandResult;
        long timeoutMs = actionTimeoutMs == null ? config.timeoutMs() : actionTimeoutMs.longValue();
        try { commandResult = commandRunner.run(command, Duration.ofMillis(timeoutMs), projectRoot); }
        catch (java.io.IOException e) { Map<String, Object> evidence = new LinkedHashMap<String, Object>(); evidence.put("id", id); evidence.put("type", "tool"); evidence.put("status", "ERROR"); evidence.put("category", "IO_ERROR"); evidence.put("message", e.getMessage()); throw new ToolExecutionException("IO_ERROR", "Tool I/O failed: " + toolName + ": " + e.getMessage(), evidence, null, e); }
        if (!Files.exists(outputFile)) {
            Files.write(outputFile, commandResult.stdout().getBytes(StandardCharsets.UTF_8));
        }

        String rawOutput = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8).trim();
        Object parsed = rawOutput;
        Exception parseFailure = null;
        if (commandResult.exitCode() == 0 && !commandResult.timedOut()) try { parsed = parseOutput(rawOutput, tool.output()); } catch (Exception e) { parseFailure = e; }

        Map<String, Object> toolInvocation = new LinkedHashMap<String, Object>();
        toolInvocation.put("name", toolName);
        toolInvocation.put("input", resolvedInput);
        toolInvocation.put("inputFile", inputFile.toString());
        toolInvocation.put("output", parsed);
        toolInvocation.put("outputFile", outputFile.toString());
        toolInvocation.put("rawOutput", rawOutput);
        toolInvocation.put("stdout", commandResult.stdout());
        toolInvocation.put("stderr", commandResult.stderr());
        toolInvocation.put("command", command);
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
        invocation.put("inputFile", inputFile.toString());
        invocation.put("outputFile", outputFile.toString());
        invocation.put("rawOutput", rawOutput);
        // Keep the action node focused on action metadata while exposing the
        // invoked tool through the V2 uppercase TOOL child node.
        Map<String, Object> toolNode = new LinkedHashMap<String, Object>();
        toolNode.put(toolName, toolInvocation);
        invocation.put("TOOL", toolNode);
        if (recordAction) context.addAction(id, invocation);
        context.put("TOOL.input", resolvedInput);
        context.put("TOOL.output", parsed);
        context.put("TOOL.inputFile", inputFile.toString());
        context.put("TOOL.outputFile", outputFile.toString());
        log.append("ACTION " + id, invocation);

        if (commandResult.timedOut()) {
            throw new ToolExecutionException("TIMEOUT", "Tool timed out: " + toolName, invocation, Integer.valueOf(commandResult.exitCode()), null);
        }
        if (commandResult.exitCode() != 0) {
            throw new ToolExecutionException("EXIT_CODE", "Tool failed: " + toolName + ", exitCode=" + commandResult.exitCode(), invocation, Integer.valueOf(commandResult.exitCode()), null);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveMap(Map<String, Object> input, Map<String, Object> renderContext) {
        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() instanceof Map) {
                resolved.put(entry.getKey(), resolveMap((Map<String, Object>) entry.getValue(), renderContext));
            } else if (entry.getValue() instanceof String) {
                resolved.put(entry.getKey(), att.core.ValueNormalizer.normalize(renderValue((String) entry.getValue(), renderContext)));
            } else {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Object value, Map<String, Object> output) {
        output.put(prefix, value);
        if (value instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                flatten(prefix + "." + entry.getKey(), entry.getValue(), output);
            }
        }
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

    private List<String> stringList(List<?> values) {
        List<String> result = new ArrayList<String>();
        for (Object value : values) result.add(value == null ? "" : String.valueOf(value));
        return result;
    }

    private String shellArgs(List<String> values) {
        StringBuilder output = new StringBuilder();
        for (String item : values) {
            if (output.length() > 0) {
                output.append(' ');
            }
            output.append(shellQuote(item));
        }
        return output.toString();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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

    private String renderValue(String template, Map<String, Object> renderContext) {
        Matcher matcher = VALUE.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            Object value = renderContext.get(matcher.group(1));
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
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            mapper.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
            return mapper.readValue(text, Object.class);
        }
        return text;
    }

    private Map<String, Object> xmlToMap(String xml) throws Exception {
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
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", xmlName(document.getDocumentElement()));
        result.putAll(elementToMap(document.getDocumentElement()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> elementToMap(Node node) {
        NodeList children = node.getChildNodes();
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        NamedNodeMap nodeAttributes = node.getAttributes();
        if (nodeAttributes != null) for (int i = 0; i < nodeAttributes.getLength(); i++) {
            Node attribute = nodeAttributes.item(i);
            if (attribute.getNodeName().startsWith("xmlns")) continue;
            attributes.put(xmlName(attribute), attribute.getNodeValue());
        }
        map.put("attributes", attributes);
        StringBuilder text = new StringBuilder();
        Map<String, Object> grouped = new LinkedHashMap<String, Object>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String name = xmlName(child);
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("name", name);
                item.putAll(elementToMap(child));
                Object existing = grouped.get(name);
                if (existing == null) grouped.put(name, item);
                else if (existing instanceof List) ((List<Object>) existing).add(item);
                else { List<Object> repeated = new ArrayList<Object>(); repeated.add(existing); repeated.add(item); grouped.put(name, repeated); }
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        map.put("text", text.toString().trim());
        map.put("children", grouped);
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
