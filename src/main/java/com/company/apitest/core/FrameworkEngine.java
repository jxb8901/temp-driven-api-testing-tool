/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.excel.ExcelReportWriter;
import com.company.apitest.excel.ExcelTestSuiteLoader;
import com.company.apitest.exec.ApiExecutor;
import com.company.apitest.template.RequestTemplateEngine;
import com.company.apitest.validation.ExpectedTemplateLoader;
import com.company.apitest.validation.ValidationEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Map;

/**
 * Coordinates the V1 sequential execution flow: load Excel cases, render XML,
 * delegate API execution, validate outputs and write the Excel report.
 */
public class FrameworkEngine {
    private final Path projectRoot;
    private final FrameworkConfig config;

    public FrameworkEngine(Path projectRoot, FrameworkConfig config) {
        this.projectRoot = projectRoot;
        this.config = config;
    }

    public RunSummary run(ExecutionOptions options) throws Exception {
        Files.createDirectories(resolve(config.outputDirectory()));
        Files.createDirectories(resolve(config.reportDirectory()));
        Files.createDirectories(resolve(config.logDirectory()));

        List<TestCase> cases = new ExcelTestSuiteLoader().load(resolve(options.suitePath()));
        List<TestResult> results = new ArrayList<>();
        RequestTemplateEngine requestTemplateEngine = new RequestTemplateEngine(projectRoot);
        ApiExecutor apiExecutor = new ApiExecutor(projectRoot, config);
        ExpectedTemplateLoader expectedTemplateLoader = new ExpectedTemplateLoader(projectRoot);
        ValidationEngine validationEngine = new ValidationEngine(projectRoot);

        for (TestCase testCase : cases) {
            if (!testCase.enabled() || !options.matches(testCase)) {
                results.add(skipped(testCase, "Case disabled or not selected"));
                continue;
            }
            if (!testCase.valid()) {
                results.add(error(testCase, testCase.invalidReason(), null, Duration.ZERO));
                continue;
            }

            Instant started = Instant.now();
            Path requestXml = caseArtifact(testCase.caseId(), "request.xml");
            Path responseXml = caseArtifact(testCase.caseId(), "response.xml");
            try {
                // Keep request and expected contexts separate so validation-only data cannot alter the API request.
                Map<String, Object> requestContext = Contexts.requestContext(testCase);
                Map<String, Object> expectedContext = Contexts.expectedContext(testCase);
                Files.write(requestXml, requestTemplateEngine.render(testCase.requestTemplate(), requestContext).getBytes(StandardCharsets.UTF_8));

                ApiExecutor.ExecutorResult executorResult = apiExecutor.execute(testCase, requestXml, responseXml);
                if (!executorResult.success()) {
                    results.add(error(testCase, executorResult.message(), responseXml, Duration.between(started, Instant.now())));
                    continue;
                }

                List<ValidationResult> validations = validationEngine.validate(
                        expectedTemplateLoader.load(testCase.expectedTemplate()),
                        expectedContext,
                        responseXml
                );
                ResultStatus status = validations.stream().allMatch(v -> v.status() == ResultStatus.PASS)
                        ? ResultStatus.PASS
                        : ResultStatus.FAIL;
                results.add(new TestResult(
                        testCase.caseId(),
                        testCase.caseName(),
                        status,
                        Duration.between(started, Instant.now()),
                        joinExpected(validations),
                        joinActual(validations),
                        responseXml,
                        validations
                ));
            } catch (Exception e) {
                results.add(error(testCase, e.getMessage(), responseXml, Duration.between(started, Instant.now())));
            }
        }

        Path report = resolve(config.reportDirectory()).resolve("report-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".xlsx");
        new ExcelReportWriter().write(report, results);
        return new RunSummary(results, report);
    }

    private Path caseArtifact(String caseId, String name) throws Exception {
        Path dir = resolve(config.outputDirectory()).resolve(caseId);
        Files.createDirectories(dir);
        return dir.resolve(name);
    }

    private Path resolve(Path path) {
        return path.isAbsolute() ? path : projectRoot.resolve(path).normalize();
    }

    private static TestResult skipped(TestCase testCase, String message) {
        return new TestResult(testCase.caseId(), testCase.caseName(), ResultStatus.SKIPPED, Duration.ZERO, message, "", null, Collections.<ValidationResult>emptyList());
    }

    private static TestResult error(TestCase testCase, String message, Path outputXml, Duration duration) {
        return new TestResult(testCase.caseId(), testCase.caseName(), ResultStatus.ERROR, duration, "", message, outputXml, Collections.<ValidationResult>emptyList());
    }

    private static String joinExpected(List<ValidationResult> validations) {
        return validations.stream().map(v -> v.name() + "=" + v.expected()).reduce((a, b) -> a + "; " + b).orElse("");
    }

    private static String joinActual(List<ValidationResult> validations) {
        return validations.stream().map(v -> v.name() + "=" + v.actual()).reduce((a, b) -> a + "; " + b).orElse("");
    }
}
