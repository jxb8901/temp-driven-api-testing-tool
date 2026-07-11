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
import org.xml.sax.InputSource;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilderFactory;
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
        CommandResult commandResult = commandRunner.run(command, Duration.ofSeconds(config.timeoutSeconds()), projectRoot);
        if (!Files.exists(outputFile)) {
            Files.write(outputFile, commandResult.stdout().getBytes(StandardCharsets.UTF_8));
        }

        String rawOutput = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8).trim();
        Object parsed = commandResult.exitCode() == 0 && !commandResult.timedOut() ? parseOutput(rawOutput, tool.output()) : rawOutput;

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
        toolInvocation.put("status", commandResult.timedOut() ? "TIMEOUT" : (commandResult.exitCode() == 0 ? "PASS" : "ERROR"));
        toolInvocation.put("durationMs", Duration.between(started, Instant.now()).toMillis());
        toolInvocation.put("exitCode", commandResult.exitCode());
        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("id", id);
        invocation.put("type", "tool");
        invocation.put("status", toolInvocation.get("status"));
        invocation.put("durationMs", toolInvocation.get("durationMs"));
        // Keep the action node focused on action metadata while exposing the
        // invoked tool through the V2 uppercase TOOL child node.
        Map<String, Object> toolNode = new LinkedHashMap<String, Object>();
        toolNode.put(toolName, toolInvocation);
        invocation.put("TOOL", toolNode);
        context.addAction(id, invocation);
        context.put("TOOL.input", resolvedInput);
        context.put("TOOL.output", parsed);
        context.put("TOOL.inputFile", inputFile.toString());
        context.put("TOOL.outputFile", outputFile.toString());
        log.append("ACTION " + id, invocation);

        if (commandResult.timedOut()) {
            throw new IllegalStateException("Tool timed out: " + toolName);
        }
        if (commandResult.exitCode() != 0) {
            throw new IllegalStateException("Tool failed: " + toolName + ", exitCode=" + commandResult.exitCode());
        }
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
        return text;
    }

    private Map<String, Object> xmlToMap(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(document.getDocumentElement().getNodeName(), elementToMap(document.getDocumentElement()));
        return result;
    }

    private Object elementToMap(Node node) {
        NodeList children = node.getChildNodes();
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        boolean hasElement = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                hasElement = true;
                map.put(child.getNodeName(), elementToMap(child));
            }
        }
        return hasElement ? map : node.getTextContent().trim();
    }

    private String extension(String outputType) {
        return "xml".equalsIgnoreCase(outputType) ? "xml" : ("yaml".equalsIgnoreCase(outputType) ? "yaml" : "txt");
    }
}
