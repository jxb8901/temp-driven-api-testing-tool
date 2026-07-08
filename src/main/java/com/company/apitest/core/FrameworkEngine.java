/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.config.StageConfig;
import com.company.apitest.excel.ExcelReportWriter;
import com.company.apitest.excel.ExcelTestSuiteLoader;
import com.company.apitest.exec.ToolInvoker;
import com.company.apitest.template.StageTemplate;
import com.company.apitest.template.StageTemplateLoader;
import com.company.apitest.template.StageTemplateRunner;
import com.company.apitest.template.UnifiedTemplateEngine;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Coordinates the V1.2 run, stage and action execution flow.
 */
public class FrameworkEngine {
    private final Path projectRoot;
    private final FrameworkConfig config;

    public FrameworkEngine(Path projectRoot, FrameworkConfig config) {
        this.projectRoot = projectRoot;
        this.config = config;
    }

    public RunSummary run(ExecutionOptions options) throws Exception {
        String runId = runId(options);
        Path outputRoot = options.outputDirectory() == null ? resolve(config.outputDirectory()) : resolve(options.outputDirectory());
        Path runDirectory = outputRoot.resolve(runId);
        if (Files.exists(runDirectory)) {
            throw new IllegalArgumentException("Run directory already exists: " + runDirectory);
        }
        Files.createDirectories(runDirectory);

        List<Path> suites = suites(options);
        Set<String> failedCaseIds = options.rerunFailed() ? latestFailedCaseIds(outputRoot) : Collections.<String>emptySet();
        List<TestResult> results = new ArrayList<>();
        ToolInvoker toolInvoker = new ToolInvoker(projectRoot, config);
        UnifiedTemplateEngine unifiedTemplateEngine = new UnifiedTemplateEngine(toolInvoker);
        StageTemplateLoader templateLoader = new StageTemplateLoader(projectRoot, config.templatesRoot());
        StageTemplateRunner templateRunner = new StageTemplateRunner(unifiedTemplateEngine);
        ExcelReportWriter reportWriter = new ExcelReportWriter(config);

        for (Path suite : suites) {
            List<TestCase> cases = new ExcelTestSuiteLoader(config).load(resolve(suite));
            List<TestResult> suiteResults = new ArrayList<TestResult>();
            for (TestCase testCase : cases) {
                TestResult result = runCase(testCase, options, failedCaseIds, runId, runDirectory, templateLoader, templateRunner);
                results.add(result);
                suiteResults.add(result);
                if (options.failFast() && (result.status() == ResultStatus.FAIL || result.status() == ResultStatus.ERROR)) {
                    break;
                }
            }
            reportWriter.write(resolve(suite), runDirectory, suiteResults);
        }
        writeHistory(outputRoot, runDirectory, runId, results);
        return new RunSummary(results, runDirectory);
    }

    private TestResult runCase(TestCase testCase, ExecutionOptions options, Set<String> failedCaseIds, String runId, Path runDirectory,
                               StageTemplateLoader templateLoader, StageTemplateRunner templateRunner) throws Exception {
        if (!testCase.enabled() || !options.matches(testCase)) {
            return skipped(testCase, "Case disabled or not selected");
        }
        if (options.rerunFailed() && !failedCaseIds.contains(testCase.caseId())) {
            return skipped(testCase, "Case was not failed in latest run");
        }
        if (!testCase.valid()) {
            return error(testCase, testCase.invalidReason(), null, Duration.ZERO);
        }
        Instant started = Instant.now();
        Path caseOutputDir = runDirectory.resolve(testCase.caseId());
        Files.createDirectories(caseOutputDir);
        Path caseLogPath = caseOutputDir.resolve(testCase.caseId() + "." + runId.replace("-", ".") + ".001.log");
        CaseExecutionLog caseLog = new CaseExecutionLog(caseLogPath);
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, caseOutputDir, runId, runDirectory, caseLogPath);
        context.put("environment", config.environment());
        caseLog.append("CASE", testCase.fixedValues());
        List<ValidationResult> validations = new ArrayList<ValidationResult>();
        try {
            if (!options.dryRun()) {
                for (StageConfig stage : config.stages()) {
                    String templateName = testCase.stageTemplates().get(stage.key());
                    if ((templateName == null || templateName.trim().isEmpty()) && stage.required()) {
                        throw new IllegalArgumentException("Missing required stage template: " + stage.key());
                    }
                    if (templateName == null || templateName.trim().isEmpty()) {
                        continue;
                    }
                    StageTemplate template = templateLoader.load(templateName);
                    caseLog.append("STAGE " + stage.name(), "template: " + templateName);
                    List<ValidationResult> stageResults = templateRunner.execute(stage.name(), template, context, caseLog);
                    validations.addAll(stageResults);
                    if (hasFailure(stageResults) && "stop".equalsIgnoreCase(stage.onFailure())) {
                        break;
                    }
                }
            }
            ResultStatus status = hasFailure(validations) ? ResultStatus.FAIL : ResultStatus.PASS;
            return new TestResult(testCase.caseId(), testCase.caseName(), options.dryRun() ? ResultStatus.SKIPPED : status,
                    Duration.between(started, Instant.now()), joinExpected(validations), joinActual(validations), caseLogPath, validations);
        } catch (Exception e) {
            caseLog.append("ERROR", e.getMessage());
            return error(testCase, e.getMessage(), caseLogPath, Duration.between(started, Instant.now()));
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

    private Path resolve(Path path) {
        return path.isAbsolute() ? path : projectRoot.resolve(path).normalize();
    }

    private String runId(ExecutionOptions options) {
        if (options.runId() != null && !options.runId().trim().isEmpty()) {
            return options.runId().trim();
        }
        return DateTimeFormatter.ofPattern(config.run().timestampFormat()).format(LocalDateTime.now());
    }

    private List<Path> suites(ExecutionOptions options) throws Exception {
        List<Path> suites = new ArrayList<Path>();
        if (options.suiteDirectory() != null) {
            try (java.util.stream.Stream<Path> paths = Files.list(resolve(options.suiteDirectory()))) {
                paths.filter(path -> path.getFileName().toString().endsWith(".xlsx")).sorted().forEach(suites::add);
            }
        } else {
            suites.add(options.suitePath());
        }
        return suites;
    }

    @SuppressWarnings("unchecked")
    private Set<String> latestFailedCaseIds(Path outputRoot) throws Exception {
        Path latest = outputRoot.resolve("latest-run.yaml");
        if (!Files.exists(latest)) {
            throw new IllegalArgumentException("--rerun-failed requires existing run history: " + latest);
        }
        Object loaded = new Yaml().load(new String(Files.readAllBytes(latest), java.nio.charset.StandardCharsets.UTF_8));
        Set<String> failed = new LinkedHashSet<String>();
        if (!(loaded instanceof Map)) {
            return failed;
        }
        Object cases = ((Map<String, Object>) loaded).get("cases");
        if (!(cases instanceof Iterable)) {
            return failed;
        }
        for (Object item : (Iterable<Object>) cases) {
            if (item instanceof Map) {
                Map<String, Object> row = (Map<String, Object>) item;
                String status = String.valueOf(row.get("status"));
                if ("FAIL".equals(status) || "ERROR".equals(status) || "PRECHECK_FAILED".equals(status) || "POSTCHECK_FAILED".equals(status)) {
                    failed.add(String.valueOf(row.get("caseId")));
                }
            }
        }
        return failed;
    }

    private void writeHistory(Path outputRoot, Path runDirectory, String runId, List<TestResult> results) throws Exception {
        Map<String, Object> history = new LinkedHashMap<String, Object>();
        history.put("runId", runId);
        history.put("runDirectory", runDirectory.toString());
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        RunSummary runSummary = new RunSummary(results, runDirectory);
        summary.put("total", runSummary.total());
        summary.put("passed", runSummary.passed());
        summary.put("failed", runSummary.failed());
        summary.put("error", runSummary.error());
        summary.put("skipped", runSummary.skipped());
        history.put("summary", summary);
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        for (TestResult result : results) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("caseId", result.caseId());
            item.put("status", result.status().name());
            item.put("caseLog", result.caseLogPath() == null ? "" : result.caseLogPath().toString());
            cases.add(item);
        }
        history.put("cases", cases);
        byte[] bytes = new Yaml().dump(history).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(runDirectory.resolve("run.yaml"), bytes);
        Files.write(outputRoot.resolve("latest-run.yaml"), bytes);
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
