/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.company.apitest.core.CaseRuntimeContext;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads request XML templates and renders them with the request context.
 */
public class RequestTemplateEngine {
    private final Path projectRoot;
    private final UnifiedTemplateEngine templateEngine;

    public RequestTemplateEngine(Path projectRoot, UnifiedTemplateEngine templateEngine) {
        this.projectRoot = projectRoot;
        this.templateEngine = templateEngine;
    }

    public String render(String templateName, CaseRuntimeContext context) throws Exception {
        Path template = projectRoot.resolve("templates/request").resolve(templateName).resolve("template.xml");
        String content = new String(Files.readAllBytes(template), StandardCharsets.UTF_8);
        return templateEngine.render(content, context);
    }

    public Path renderArtifact(String templateName, CaseRuntimeContext context) throws Exception {
        // Rendering is internal, but it is recorded like a tool invocation so request XML follows V1.1 artifact rules.
        int sequence = context.nextToolSequence("renderRequestTemplate");
        Path caseDir = java.nio.file.Paths.get(String.valueOf(context.resolve("PATH.caseOutputDir")));
        Path invocationDir = caseDir.resolve("tools").resolve(String.format("%03d_%s", sequence, "renderRequestTemplate"));
        Files.createDirectories(invocationDir);

        Path inputFile = invocationDir.resolve("input.yaml");
        Path outputFile = invocationDir.resolve("output.xml");
        Path commandFile = invocationDir.resolve("command.txt");
        Path stdoutFile = invocationDir.resolve("stdout.txt");
        Path stderrFile = invocationDir.resolve("stderr.txt");
        Path parsedFile = invocationDir.resolve("parsed-output.yaml");
        Path injectionFile = invocationDir.resolve("context-injection.yaml");

        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("template", templateName);
        input.put("caseId", context.resolve("CaseID"));
        Files.write(inputFile, new Yaml().dump(input).getBytes(StandardCharsets.UTF_8));
        Files.write(commandFile, "internal: render request template".getBytes(StandardCharsets.UTF_8));
        Files.write(stdoutFile, new byte[0]);
        Files.write(stderrFile, new byte[0]);
        Files.write(outputFile, render(templateName, context).getBytes(StandardCharsets.UTF_8));

        // Keep the parsed output lightweight; XML content remains available through outputFile.
        Map<String, Object> parsed = new LinkedHashMap<String, Object>();
        parsed.put("xmlFile", outputFile.toString());
        Files.write(parsedFile, new Yaml().dump(parsed).getBytes(StandardCharsets.UTF_8));
        Files.write(injectionFile, new Yaml().dump(new LinkedHashMap<String, Object>()).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("input", input);
        invocation.put("output", parsed);
        invocation.put("inputFile", inputFile.toString());
        invocation.put("outputFile", outputFile.toString());
        context.addToolInvocation("renderRequestTemplate", invocation);
        return outputFile;
    }
}
