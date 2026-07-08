/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads request XML templates and renders them with the request context.
 */
public class RequestTemplateEngine {
    private final Path projectRoot;

    public RequestTemplateEngine(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public String render(String templateName, Map<String, Object> requestContext) throws IOException {
        Path template = projectRoot.resolve("templates/request").resolve(templateName).resolve("template.xml");
        String content = new String(Files.readAllBytes(template), StandardCharsets.UTF_8);
        return TemplateRenderer.render(content, requestContext);
    }
}
