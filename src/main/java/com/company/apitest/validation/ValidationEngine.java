/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.validation;

import com.company.apitest.core.ResultStatus;
import com.company.apitest.core.ValidationResult;
import com.company.apitest.exec.CommandResult;
import com.company.apitest.exec.CommandRunner;
import com.company.apitest.template.TemplateRenderer;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies expected-template rules against response XML and shell-script validation outputs.
 */
public class ValidationEngine {
    private final Path projectRoot;
    private final CommandRunner commandRunner;

    public ValidationEngine(Path projectRoot) {
        this(projectRoot, new CommandRunner());
    }

    public ValidationEngine(Path projectRoot, CommandRunner commandRunner) {
        this.projectRoot = projectRoot;
        this.commandRunner = commandRunner;
    }

    public List<ValidationResult> validate(ExpectedTemplate template, Map<String, Object> expectedContext, Path responseXml) throws Exception {
        List<ValidationResult> results = new ArrayList<ValidationResult>();
        Document document = parse(responseXml);

        for (ExpectedTemplate.XmlRule rule : template.xmlRules()) {
            String expected = TemplateRenderer.render(rule.equals(), expectedContext);
            String actual = xpath(document, rule.xpath());
            results.add(compare("XML", rule.name(), expected, actual, ""));
        }
        for (ExpectedTemplate.CommandRule rule : template.databaseRules()) {
            results.add(runCommandRule("Database", rule, expectedContext));
        }
        for (ExpectedTemplate.CommandRule rule : template.logRules()) {
            results.add(runCommandRule("Log", rule, expectedContext));
        }
        return results;
    }

    private Document parse(Path path) throws Exception {
        String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        // Avoid loading external document definitions while parsing API responses.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private String xpath(Document document, String expression) throws Exception {
        Object value = XPathFactory.newInstance().newXPath().evaluate(expression, document, XPathConstants.STRING);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private ValidationResult runCommandRule(String source, ExpectedTemplate.CommandRule rule, Map<String, Object> expectedContext) throws Exception {
        String expected = TemplateRenderer.render(rule.expected(), expectedContext);
        String command = TemplateRenderer.render(rule.command(), expectedContext);
        // Script validators follow the documented contract: exit code plus first non-empty stdout line.
        CommandResult result = commandRunner.run(command, Duration.ofSeconds(120));
        if (result.timedOut()) {
            return new ValidationResult(source, rule.name(), ResultStatus.ERROR, expected, "", "Validation script timed out");
        }
        if (result.exitCode() != 0) {
            return new ValidationResult(source, rule.name(), ResultStatus.ERROR, expected, firstNonEmptyLine(result.stdout()), result.stderr());
        }
        return compare(source, rule.name(), expected, firstNonEmptyLine(result.stdout()), "");
    }

    private ValidationResult compare(String source, String name, String expected, String actual, String message) {
        ResultStatus status = expected.equals(actual) ? ResultStatus.PASS : ResultStatus.FAIL;
        return new ValidationResult(source, name, status, expected, actual, message);
    }

    private String firstNonEmptyLine(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                return line.trim();
            }
        }
        return "";
    }
}
