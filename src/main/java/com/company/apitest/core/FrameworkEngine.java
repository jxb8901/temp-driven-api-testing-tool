/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.excel.ExcelReportWriter;
import com.company.apitest.excel.ExcelTestSuiteLoader;
import com.company.apitest.exec.ToolInvoker;
import com.company.apitest.template.RequestTemplateEngine;
import com.company.apitest.template.UnifiedTemplateEngine;
import com.company.apitest.validation.CheckAction;
import com.company.apitest.validation.CheckEngine;
import com.company.apitest.validation.CheckTemplateLoader;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.io.Reader;

/**
 * Coordinates the V1.1 sequential execution flow.
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

        List<TestCase> cases = new ExcelTestSuiteLoader(config).load(resolve(options.suitePath()));
        List<TestResult> results = new ArrayList<>();
        ToolInvoker toolInvoker = new ToolInvoker(projectRoot, config);
        UnifiedTemplateEngine unifiedTemplateEngine = new UnifiedTemplateEngine(toolInvoker);
        RequestTemplateEngine requestTemplateEngine = new RequestTemplateEngine(projectRoot, unifiedTemplateEngine);
        CheckTemplateLoader checkTemplateLoader = new CheckTemplateLoader(projectRoot);
        CheckEngine checkEngine = new CheckEngine(unifiedTemplateEngine);

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
            Path caseOutputDir = caseOutputDir(testCase.caseId());
            try {
                CaseRuntimeContext context = new CaseRuntimeContext(testCase, caseOutputDir);
                context.put("environment", config.environment());

                List<ValidationResult> precheck = checkEngine.execute("PreCheck", checkTemplateLoader.load(testCase.precheckTemplate()), context);
                if (hasFailure(precheck)) {
                    results.add(new TestResult(testCase.caseId(), testCase.caseName(), ResultStatus.PRECHECK_FAILED,
                            Duration.between(started, Instant.now()), joinExpected(precheck), joinActual(precheck), caseOutputDir, precheck));
                    continue;
                }

                requestTemplateEngine.renderArtifact(testCase.requestTemplate(), context);
                executeApiInvocation(testCase, unifiedTemplateEngine, context);

                List<ValidationResult> validations = checkEngine.execute("PostCheck", checkTemplateLoader.load(testCase.postcheckTemplate()), context);
                ResultStatus status = hasFailure(validations) ? ResultStatus.POSTCHECK_FAILED : ResultStatus.PASS;
                results.add(new TestResult(
                        testCase.caseId(),
                        testCase.caseName(),
                        status,
                        Duration.between(started, Instant.now()),
                        joinExpected(validations),
                        joinActual(validations),
                        caseOutputDir,
                        validations
                ));
            } catch (Exception e) {
                results.add(error(testCase, e.getMessage(), caseOutputDir, Duration.between(started, Instant.now())));
            }
        }

        Path report = resolve(config.reportDirectory()).resolve("report-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".xlsx");
        new ExcelReportWriter().write(report, results);
        return new RunSummary(results, report);
    }

    private void executeApiInvocation(TestCase testCase, UnifiedTemplateEngine templateEngine, CaseRuntimeContext context) throws Exception {
        Path path = projectRoot.resolve("templates/request").resolve(testCase.requestTemplate()).resolve("api.invocation.yaml");
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(reader);
            if (loaded instanceof Map) {
                for (Object value : ((Map<?, ?>) loaded).values()) {
                    if (value instanceof Map) {
                        Object call = ((Map<?, ?>) value).get("call");
                        if (call != null) {
                            templateEngine.executeCall(String.valueOf(call), context);
                        }
                    }
                }
            }
        }
    }

    private boolean hasFailure(List<ValidationResult> results) {
        for (ValidationResult result : results) {
            if (result.status() != ResultStatus.PASS) {
                return true;
            }
        }
        return false;
    }

    private Path caseOutputDir(String caseId) throws Exception {
        Path dir = resolve(config.outputDirectory()).resolve(caseId);
        Files.createDirectories(dir);
        return dir;
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
