/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.exec;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.config.ToolConfig;
import com.company.apitest.core.CaseExecutionLog;
import com.company.apitest.core.CaseRuntimeContext;
import com.company.apitest.template.TemplateRenderer;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes configured tools and records each invocation into the case log.
 */
public class ToolInvoker {
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
        Path inputFile = Files.createTempFile("att-" + id + "-", ".input.yaml");
        Path outputFile = Files.createTempFile("att-" + id + "-", "." + extension(tool.output()));

        // Call-site input is resolved first; config-level arguments can normalize or enrich that input.
        Map<String, Object> resolvedInput = resolveMap(input, context);
        resolvedInput.putAll(resolveToolArguments(tool.arguments(), resolvedInput, context));
        Files.write(inputFile, new Yaml().dump(resolvedInput).getBytes(StandardCharsets.UTF_8));

        // The command sees only file paths and resolved TOOL.input values, keeping scripts reusable.
        Map<String, Object> renderContext = new LinkedHashMap<String, Object>(context.values());
        flatten("TOOL.input", resolvedInput, renderContext);
        renderContext.put("TOOL.inputFile", inputFile.toString());
        renderContext.put("TOOL.outputFile", outputFile.toString());
        String command = TemplateRenderer.render(tool.command(), renderContext);
        CommandResult commandResult = commandRunner.run(command, Duration.ofSeconds(config.timeoutSeconds()));
        if (!Files.exists(outputFile)) {
            Files.write(outputFile, commandResult.stdout().getBytes(StandardCharsets.UTF_8));
        }

        String rawOutput = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8).trim();
        Object parsed = commandResult.exitCode() == 0 && !commandResult.timedOut() ? parseOutput(rawOutput, tool.output()) : rawOutput;

        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("type", "tool");
        invocation.put("tool", toolName);
        invocation.put("input", resolvedInput);
        invocation.put("output", parsed);
        invocation.put("rawOutput", rawOutput);
        invocation.put("stdout", commandResult.stdout());
        invocation.put("stderr", commandResult.stderr());
        invocation.put("command", command);
        invocation.put("status", commandResult.timedOut() ? "TIMEOUT" : (commandResult.exitCode() == 0 ? "PASS" : "ERROR"));
        invocation.put("durationMs", Duration.between(started, Instant.now()).toMillis());
        invocation.put("exitCode", commandResult.exitCode());
        context.addToolInvocation(id, invocation);
        context.put("TOOL.input", resolvedInput);
        context.put("TOOL.output", parsed);
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
            } else {
                resolved.put(entry.getKey(), TemplateRenderer.render(String.valueOf(entry.getValue()), renderContext));
            }
        }
        return resolved;
    }

    private Map<String, Object> resolveToolArguments(Map<String, Object> arguments, Map<String, Object> callInput, CaseRuntimeContext context) {
        Map<String, Object> renderContext = new LinkedHashMap<String, Object>(context.values());
        flatten("TOOL.input", callInput, renderContext);
        return resolveMap(arguments, renderContext);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveMap(Map<String, Object> input, Map<String, Object> renderContext) {
        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() instanceof Map) {
                resolved.put(entry.getKey(), resolveMap((Map<String, Object>) entry.getValue(), renderContext));
            } else {
                resolved.put(entry.getKey(), TemplateRenderer.render(String.valueOf(entry.getValue()), renderContext));
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

    public Object parseOutput(String text, String outputType) throws Exception {
        if ("yaml".equalsIgnoreCase(outputType)) {
            Object loaded = new Yaml().load(text);
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
