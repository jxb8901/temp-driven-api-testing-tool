/*
 * Author: Jeffrey + ChatGPT
 */

package att.core;

import att.config.FrameworkConfig;
import att.config.StageConfig;
import att.config.SuiteConfigResolver;
import att.config.YamlSupport;
import att.excel.ExcelReportWriter;
import att.excel.ExcelTestSuiteLoader;
import att.snapshot.TestcaseSnapshotService;
import att.exec.ToolInvoker;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.StageTemplateRunner;
import att.template.UnifiedTemplateEngine;
import att.template.TemplateAction;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.report.HtmlReportGenerator;
import att.report.CiReportWriter;
import att.report.NativeCaseExcelReportWriter;
import att.Version;
import att.validation.JsonSchemaVerifier;
import att.validation.SchemaFiles;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import att.testcase.CaseSourceProvenance;
import att.testcase.ResolvedTestCase;
import att.testcase.TestCaseDefinitionResolver;
import att.testcase.TestCaseResolutionResult;
import att.testcase.TestCaseSuiteResolutionResult;
import att.testcase.TestCaseSuiteResolver;
import att.excel.ExcelDataSourceAdapter;
import att.resource.PackageResourceResolver;

/**
 * Coordinates the V2 run, stage and action execution flow.
 */
public class FrameworkEngine {
    private final Path projectRoot;
    private final FrameworkConfig config;

    public FrameworkEngine(Path projectRoot, FrameworkConfig config) {
        this.projectRoot = projectRoot;
        this.config = config;
    }

    public RunSummary run(ExecutionOptions options) throws Exception {
        config.requireExecutionConfig();
        return run(options, Collections.<att.validation.Diagnostic>emptyList());
    }

    /** Executes one complete bounded Native Case resolution using the normal RUN Flow runner. */
    public RunSummary runNativeCases(TestCaseResolutionResult resolution, String requestedRunId,
            Path requestedOutputRoot, boolean dryRun, boolean failFast,
            att.api.ExecutionEventListener observer) throws Exception {
        return runNativeCaseSuite(TestCaseSuiteResolutionResult.fromSingle(resolution), requestedRunId,
                requestedOutputRoot, dryRun, failFast, observer);
    }

    /** Executes one bounded implicit Suite across one or more resolved native Definitions. */
    public RunSummary runNativeCaseSuite(TestCaseSuiteResolutionResult resolution, String requestedRunId,
            Path requestedOutputRoot, boolean dryRun, boolean failFast,
            att.api.ExecutionEventListener observer) throws Exception {
        return runNativeCaseSuite(resolution, requestedRunId, requestedOutputRoot, null,
                dryRun, failFast, observer, null);
    }

    /** Executes a frozen retry selection while keeping its counts separate from this RUN's results. */
    public RunSummary runNativeCaseSuite(TestCaseSuiteResolutionResult resolution, String requestedRunId,
            Path requestedOutputRoot, boolean dryRun, boolean failFast,
            att.api.ExecutionEventListener observer, att.api.NativeSuiteRetryLineage retryLineage) throws Exception {
        return runNativeCaseSuite(resolution, requestedRunId, requestedOutputRoot, null,
                dryRun, failFast, observer, retryLineage);
    }

    /** Executes a Suite with its Package identity for scoped local retry history. */
    public RunSummary runNativeCaseSuite(TestCaseSuiteResolutionResult resolution, String requestedRunId,
            Path requestedOutputRoot, String packageId, boolean dryRun, boolean failFast,
            att.api.ExecutionEventListener observer, att.api.NativeSuiteRetryLineage retryLineage) throws Exception {
        config.requireExecutionConfig();
        List<ResolvedTestCase> selected = validateNativeSuiteResolution(resolution);
        String runId = IdentifierValidator.runId(requestedRunId);
        Path outputRoot = requestedOutputRoot == null ? resolve(config.outputDirectory()) : resolve(requestedOutputRoot);
        Path runDirectory = IdentifierValidator.strictChild(outputRoot, runId, "Run directory");
        if (Files.exists(runDirectory))
            throw new IllegalArgumentException("Run ID already exists: " + runId + " (" + runDirectory + "). Choose a different --run-id.");

        // Capture every source identity before creating output. A changed Definition or Flow
        // invalidates the resolution instead of executing inputs from one revision against another.
        FlowRegistry flows = new FlowRegistry(projectRoot, config.templatesRoot(), false);
        List<Map<String,Object>> inputs = verifyNativeSources(resolution, selected, flows);

        RunConcurrencyGuard concurrencyGuard = RunConcurrencyGuard.acquire(outputRoot, "reject", new Runnable() { @Override public void run() { } });
        try {
            Files.createDirectories(outputRoot);
            try { Files.createDirectory(runDirectory); }
            catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalArgumentException("Run ID already exists: " + runId + " (" + runDirectory + "). Choose a different --run-id.", e);
            }

            Instant runStarted = Instant.now();
            emitNativeEvent(observer, "RUN_STARTED", runId, null, "RUNNING", null,
                    nativeSuiteIdentityData(resolution, null));
            att.template.DefaultBuiltInProvider runBuiltIns = new att.template.DefaultBuiltInProvider(new att.template.SequenceService());
            ToolInvoker toolInvoker = new ToolInvoker(projectRoot, config);
            att.exec.DbHelperExecutor dbHelperExecutor = new att.exec.DbHelperExecutor(projectRoot, config);
            att.exec.MqHelperExecutor mqHelperExecutor = new att.exec.MqHelperExecutor(projectRoot, config);
            att.exec.HttpHelperExecutor httpHelperExecutor = new att.exec.HttpHelperExecutor(projectRoot, config);
            UnifiedTemplateEngine templateEngine = new UnifiedTemplateEngine(toolInvoker, dbHelperExecutor,
                    mqHelperExecutor, httpHelperExecutor, runBuiltIns);
            List<TestResult> results = new ArrayList<TestResult>();
            boolean stopRun = false;
            try {
                for (ResolvedTestCase testCase : selected) {
                    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Native RUN was cancelled");
                    if (stopRun) {
                        String reason = "Not started because failFast stopped the Suite after an earlier Case failure";
                        TestResult skipped = TestResult.notExecuted(testCase.qualifiedCaseId(), nativeCaseName(testCase),
                                testCase.tags(), testCase.definitionId(), testCase.qualifiedCaseId(), reason,
                                testCase.flowId(), testCase.flowRevision(), testCase.sourceRevision(),
                                testCase.sourceProvenance().toMap());
                        results.add(skipped);
                        appendEvent(runDirectory, runId, skipped);
                        Map<String,Object> identity = new LinkedHashMap<String,Object>();
                        identity.put("definitionId", testCase.definitionId());
                        identity.put("qualifiedCaseId", testCase.qualifiedCaseId());
                        identity.put("notStartedReason", "failFast");
                        identity.put("executionStarted", Boolean.FALSE);
                        emitNativeEvent(observer, "CASE_FINISHED", runId, testCase.qualifiedCaseId(),
                                ResultStatus.SKIPPED.name(), 0L, identity);
                        continue;
                    }
                    TestResult result = runNativeCase(testCase, runId, runStarted, runDirectory, templateEngine,
                            flows, dbHelperExecutor, dryRun, observer);
                    results.add(result);
                    appendEvent(runDirectory, runId, result);
                    if (failFast && (result.status() == ResultStatus.FAIL || result.status() == ResultStatus.ERROR
                            || result.status() == ResultStatus.INVALID)) {
                        stopRun = true;
                    }
                }
            } finally {
                try { dbHelperExecutor.close(); }
                finally { httpHelperExecutor.close(); }
            }
            if (results.isEmpty()) throw new IllegalArgumentException("Native Case selection is empty after execution");

            Instant runEnded = Instant.now();
            RunSummary summary = new RunSummary(results, runDirectory, runId);
            Path html = new HtmlReportGenerator().generate(runDirectory, runId, summary, runStarted, runEnded,
                    config.report().htmlCaseLogInlineLimitBytes());
            new NativeCaseExcelReportWriter().write(runDirectory, runId, results);
            String inputManifestHash = sha256Bytes(new Yaml().dump(inputs).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Set<String> ciOutputs = new LinkedHashSet<String>(java.util.Arrays.asList("junit", "json"));
            new CiReportWriter().write(runDirectory, runId, config.environment(), summary, runStarted, runEnded,
                    config.report().junitCaseLogEmbedThresholdBytes(), inputManifestHash,
                    Collections.<att.validation.Diagnostic>emptyList(), ciOutputs);
            JsonSchemaVerifier.verifyJson(SchemaFiles.resolve(projectRoot, "att-ci-summary-v2.1.schema.json"),
                    runDirectory.resolve("ci/summary.json"));
            verifyJunit(runDirectory.resolve("ci/junit.xml"));
            att.config.ConfigurationEvidence.write(runDirectory, config);
            ExecutionOptions manifestOptions = nativeManifestOptions(runId, requestedOutputRoot, dryRun, failFast, ciOutputs);
            writeManifest(runDirectory, runId, results, runStarted, runEnded, html, manifestOptions, inputs,
                    Collections.<att.validation.Diagnostic>emptyList(), nativeRunMetadata(resolution, summary, retryLineage));
            writeLatest(outputRoot, runDirectory, runId, summary, runEnded);
            if(!dryRun&&packageId!=null)writeLatest(outputRoot,runDirectory,runId,summary,runEnded,
                    att.testcase.NativeSuiteRetryResolver.latestPointerFileName(packageId,
                            resolution.definitions().stream().map(item->item.definition().id())
                                    .collect(java.util.stream.Collectors.toList())));
            emitNativeEvent(observer, "RUN_FINISHED", runId, null, summary.status().name(),
                    Duration.between(runStarted, runEnded).toMillis(), nativeSuiteIdentityData(resolution, null));
            return new RunSummary(results, html, runId);
        } finally {
            concurrencyGuard.close();
        }
    }

    /** Read-only early check used before validation/progress output; buildPlan rechecks to close the race window. */
    public void assertRunIdAvailable(ExecutionOptions options) {
        String runId = runId(options);
        Path outputRoot = options.outputDirectory() == null ? resolve(config.outputDirectory()) : resolve(options.outputDirectory());
        Path finalRunDirectory = IdentifierValidator.strictChild(outputRoot, runId, "Run directory");
        if (Files.exists(finalRunDirectory)) {
            throw new IllegalArgumentException("Run ID already exists: " + runId + " (" + finalRunDirectory + "). Choose a different --run-id.");
        }
    }

    /** Resolves one Run identity for callers that must validate and execute under the same ID. */
    public String effectiveRunId(ExecutionOptions options) {
        return runId(options, options.identitySeed() == null ? Instant.now() : options.identitySeed().startedAt());
    }

    public RunSummary run(ExecutionOptions options, List<att.validation.Diagnostic> validationDiagnostics) throws Exception {
        return run(options, validationDiagnostics, new PerformanceProfile(options.profile()));
    }

    public RunSummary run(ExecutionOptions options, List<att.validation.Diagnostic> validationDiagnostics, PerformanceProfile profile) throws Exception {
        config.requireExecutionConfig();
        Instant runStarted = options.identitySeed() == null ? Instant.now() : options.identitySeed().startedAt();
        long phaseStarted = profile.begin();
        ExecutionPlan plan = buildPlan(options, runStarted);
        profile.end("planCompileMs", phaseStarted);
        profile.counter("suites", plan.suites().size());
        long selectedCases = 0; for (ExecutionPlan.Suite suite : plan.suites()) selectedCases += suite.cases().size();
        profile.counter("casesSelected", selectedCases);
        String runId = plan.runId();
        Path outputRoot = plan.outputRoot();
        Path finalRunDirectory = plan.finalRunDirectory();
        RunConcurrencyGuard concurrencyGuard = RunConcurrencyGuard.acquire(outputRoot, options.concurrencyMode(), new Runnable() {
            @Override public void run() {
                progress(options, "RUN_QUEUED", runId, null, null, null, null, null,
                        "waiting for the active run to complete", Collections.<String,Object>emptyMap());
            }
        });
        ConsoleCancellationHook cancellation = new ConsoleCancellationHook(new Runnable() {
            @Override public void run() { progress(options, "RUN_CANCELLED", runId, null, null, null, "CANCELLED", null, null, Collections.<String,Object>emptyMap()); }
        });
        try {
        Files.createDirectories(outputRoot);
        Path runDirectory = finalRunDirectory;
        try {
            // Reserve the final Run ID atomically. Evidence is intentionally
            // visible at its permanent path while the Case is executing.
            Files.createDirectory(runDirectory);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new IllegalArgumentException("Run ID already exists: " + runId + " (" + finalRunDirectory + "). Choose a different --run-id.", e);
        }

        List<TestResult> results = new ArrayList<>();
        Map<ExecutionPlan.Suite, List<TestResult>> suiteReportResults = new LinkedHashMap<ExecutionPlan.Suite, List<TestResult>>();
        boolean stopRun = false;
        att.template.DefaultBuiltInProvider runBuiltIns = new att.template.DefaultBuiltInProvider(new att.template.SequenceService());
        Map<String,Object> runData = new LinkedHashMap<String,Object>(); runData.put("suites", plan.suites().size()); runData.put("output", portable(outputRoot));
        progress(options, "RUN_STARTED", runId, null, null, null, "RUNNING", null, null, runData);

        phaseStarted = profile.begin();
        for (ExecutionPlan.Suite suitePlan : plan.suites()) {
            Path suite = suitePlan.workbook();
            FrameworkConfig suiteConfig = suitePlan.config();
            att.testdata.TestdataInputResolver testdataAllocator = options.dryRun() ? null
                    : new att.testdata.TestdataInputResolver(new att.testdata.TestdataRegistry(
                            projectRoot, suiteConfig.testdataDescriptors(), Collections.<Path>emptyList()));
            ToolInvoker toolInvoker = new ToolInvoker(projectRoot, suiteConfig);
            att.exec.DbHelperExecutor dbHelperExecutor = new att.exec.DbHelperExecutor(projectRoot, suiteConfig);
            att.exec.MqHelperExecutor mqHelperExecutor = new att.exec.MqHelperExecutor(projectRoot, suiteConfig);
            att.exec.HttpHelperExecutor httpHelperExecutor = new att.exec.HttpHelperExecutor(projectRoot, suiteConfig);
            UnifiedTemplateEngine unifiedTemplateEngine = new UnifiedTemplateEngine(toolInvoker, dbHelperExecutor, mqHelperExecutor, httpHelperExecutor, runBuiltIns);
            List<TestCase> cases = suitePlan.cases();
            Map<String,Object> suiteData = new LinkedHashMap<String,Object>(); suiteData.put("file", portable(resolve(suite))); suiteData.put("cases", cases.size());
            progress(options, "SUITE_STARTED", runId, null, null, null, "RUNNING", null, null, suiteData);
            List<TestResult> suiteResults = new ArrayList<TestResult>();
            try {
                for (TestCase testCase : cases) {
                    progress(options, "CASE_STARTED", runId, testCase.caseId(), null, null, "START", null, null, Collections.<String,Object>emptyMap());
                    // A Run Render plan follows one Testcase execution. Nested
                    // stages and Flows share this runner, while the next
                    // Testcase observes edits made after the current one.
                    StageTemplateRunner templateRunner = new StageTemplateRunner(unifiedTemplateEngine, suitePlan.flows());
                    TestResult result = runCase(testCase, suiteConfig, options, runId, runStarted, runDirectory,
                            suitePlan, templateRunner, dbHelperExecutor, testdataAllocator);
                    progress(options, "CASE_FINISHED", runId, testCase.caseId(), null, null, result.status().name(), result.duration().toMillis(), null, Collections.<String,Object>emptyMap());
                    results.add(result);
                    suiteResults.add(result);
                    if (options.failFast() && (result.status() == ResultStatus.FAIL || result.status() == ResultStatus.ERROR)) {
                        stopRun = true;
                        break;
                    }
                }
            } finally { dbHelperExecutor.close(); httpHelperExecutor.close(); }
            suiteReportResults.put(suitePlan, new ArrayList<TestResult>(suiteResults));
            if (stopRun) break;
        }
        profile.end("caseExecutionMs", phaseStarted);
        if (results.isEmpty()) throw new IllegalArgumentException("Case selection is empty after rerun-failed filtering");
        phaseStarted = profile.begin();
        for (Map.Entry<ExecutionPlan.Suite, List<TestResult>> entry : suiteReportResults.entrySet()) {
            if ("none".equals(entry.getKey().config().report().mode())) continue;
            new ExcelReportWriter(entry.getKey().config()).write(resolve(entry.getKey().workbook()), runDirectory, entry.getValue());
        }
        profile.end("resultWorkbookMs", phaseStarted);
        for (TestResult result : results) appendEvent(runDirectory, runId, result);
        Instant runEnded = Instant.now();
        RunSummary summary = new RunSummary(results, runDirectory);
        phaseStarted = profile.begin();
        Path html = new HtmlReportGenerator().generate(runDirectory, runId, summary, runStarted, runEnded, config.report().htmlCaseLogInlineLimitBytes());
        profile.end("htmlReportMs", phaseStarted);
        phaseStarted = profile.begin();
        List<Map<String, Object>> inputs = inputHashes(options);
        String inputManifestHash = sha256Bytes(new Yaml().dump(inputs).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        profile.end("inputHashMs", phaseStarted);
        phaseStarted = profile.begin();
        new CiReportWriter().write(runDirectory, runId, config.environment(), summary, runStarted, runEnded, config.report().junitCaseLogEmbedThresholdBytes(), inputManifestHash, validationDiagnostics, options.ciOutputs());
        if (options.ciOutputs().contains("json")) JsonSchemaVerifier.verifyJson(att.validation.SchemaFiles.resolve(projectRoot, "att-ci-summary-v2.1.schema.json"), runDirectory.resolve("ci/summary.json"));
        if (options.ciOutputs().contains("junit")) {
            javax.xml.validation.SchemaFactory schemaFactory = javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, ""); schemaFactory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            att.validation.SchemaFiles.SchemaResource junitSchema = att.validation.SchemaFiles.readSchema(
                    att.validation.SchemaFiles.resolve(projectRoot, "att-junit-v2.1.xsd"));
            javax.xml.validation.Schema compiledSchema;
            try (java.io.InputStream schemaBytes = new java.io.ByteArrayInputStream(junitSchema.bytes())) {
                javax.xml.transform.stream.StreamSource schemaSource = new javax.xml.transform.stream.StreamSource(schemaBytes);
                schemaSource.setSystemId(junitSchema.identity());
                compiledSchema = schemaFactory.newSchema(schemaSource);
            }
            javax.xml.validation.Validator validator = compiledSchema.newValidator();
            validator.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, ""); validator.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new javax.xml.transform.stream.StreamSource(runDirectory.resolve("ci/junit.xml").toFile()));
        }
        profile.end("ciReportMs", phaseStarted);
        profile.counter("casesCompleted", results.size());
        profile.write(runDirectory);
        // COMPLETE is the final run artifact. If any earlier evidence write
        // fails, the directly visible directory remains intentionally
        // incomplete and cannot be consumed as a finished run.
        att.config.ConfigurationEvidence.write(runDirectory, config);
        writeManifest(runDirectory, runId, results, runStarted, runEnded, html, options, inputs, validationDiagnostics);
        writeLatest(outputRoot, runDirectory, runId, summary, runEnded);
        return new RunSummary(results, runDirectory.resolve("report/index.html"), runId);
        } finally {
            try { concurrencyGuard.close(); }
            finally { cancellation.close(); }
        }
    }

    private TestResult runCase(TestCase testCase, FrameworkConfig suiteConfig, ExecutionOptions options, String runId, Instant runStartedAt, Path runDirectory,
                               ExecutionPlan.Suite suitePlan, StageTemplateRunner templateRunner,
                               att.exec.DbHelperExecutor dbHelperExecutor,
                               att.testdata.TestdataInputResolver testdataAllocator) throws Exception {
        if (!testCase.valid()) {
            return invalid(testCase, testCase.invalidReason());
        }
        Instant started = Instant.now();
        String validatedCaseId = IdentifierValidator.caseId(testCase.workbookId(), testCase.groupId(), testCase.rowCaseId());
        Path executionsDirectory = runDirectory.resolve("executions");
        Files.createDirectories(executionsDirectory);
        Path caseOutputDir = IdentifierValidator.strictChild(executionsDirectory, validatedCaseId, "Execution directory");
        Files.createDirectories(caseOutputDir);
        Path caseLogPath = caseOutputDir.resolve("case.log");
        if (options.hasObserver()) {
            Map<String,Object> logData = new LinkedHashMap<String,Object>(); logData.put("file", portable(caseLogPath));
            progress(options, "CASE_LOG_PATH", runId, testCase.caseId(), null, null, null, null, null, logData);
        }
        CaseExecutionLog caseLog = options.hasObserver()
                ? new CaseExecutionLog(caseLogPath, suiteConfig.caseLogYamlAnchors(),
                        new CaseLogConsoleMirror(testCase.caseId(), new java.util.function.Consumer<String>() {
                            @Override public void accept(String text) { options.emitCaseLog(runId, testCase.caseId(), text); }
                        }))
                : CaseExecutionLog.buffered(caseLogPath, suiteConfig.caseLogYamlAnchors());
        caseLog.setProjectRoot(projectRoot);
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, caseOutputDir, validatedCaseId, runId,
                runDirectory, caseLogPath, "testcase", started.toString(), runStartedAt.toString());
        context.setProject(projectRoot);
        context.put("CASE.environment", suiteConfig.environment());
        caseLog.append("CASE", context.caseTree());
        List<ValidationResult> validations = new ArrayList<ValidationResult>();
        boolean dbFinalized = false;
        dbHelperExecutor.beginCase();
        try {
            if (!options.dryRun()) {
                att.testdata.TestdataInputResolver testdata = testdataAllocator.forExecutionScope(validatedCaseId);
                Map<String, Object> resolvedCaseInput = testdata.resolve(testCase.caseData(), context, null, null);
                context.replaceInputValues(resolvedCaseInput);
                if (!testdata.selectionEvidence().isEmpty())
                    context.put("CASE.testdataSelections", testdata.selectionEvidence());
                boolean hasPriorFailure = false;
                boolean stoppedByFailure = false;
                for (StageConfig stage : suiteConfig.stages()) {
                    if (!shouldRunStage(stage, hasPriorFailure, stoppedByFailure)) {
                        continue;
                    }
                    StageCaseData stageData = testCase.stage(stage.key());
                    if (stageData == null) {
                        if (stage.required()) throw new IllegalArgumentException("Missing required stage data: " + stage.key());
                        continue;
                    }
                    StageTemplate template = suitePlan.template(stageData.templateName());
                    if (template == null) throw new IllegalArgumentException("Template was not resolved in execution plan: " + stageData.templateName());
                    Map<String,Object> stageEventData = new LinkedHashMap<String,Object>(); stageEventData.put("template", template.name());
                    progress(options, "STAGE_STARTED", runId, testCase.caseId(), stage.key(), null, "START", null, null, stageEventData);
                    Map<String, Object> resolvedStageInput = testdata.resolve(stageData.values(), context, null, null);
                    if (!testdata.selectionEvidence().isEmpty())
                        context.put("CASE.testdataSelections", testdata.selectionEvidence());
                    StageCaseData executionStage = new StageCaseData(stageData.key(), stageData.templateName(), resolvedStageInput);
                    context.beginStage(executionStage, template.name(), template.directory());
                    caseLog.append("STAGE " + stage.key(), "template: " + stageData.templateName());
                    Instant stageStarted = Instant.now();
                    List<ValidationResult> stageResults = templateRunner.execute(stage.key(), template, context, caseLog);
                    ResultStatus stageStatus = aggregate(stageResults);
                    for (ValidationResult actionResult : stageResults) {
                        Map<String,Object> actionData = new LinkedHashMap<String,Object>();
                        if(actionResult.message()!=null&&!actionResult.message().isEmpty()) actionData.put("detail", actionResult.message());
                        progress(options, "ACTION_FINISHED", runId, testCase.caseId(), stage.key(), actionResult.name(), actionResult.status().name(), null, null, actionData);
                    }
                    context.finishStage(stageStatus.name(), Duration.between(stageStarted, Instant.now()).toMillis());
                    progress(options, "STAGE_FINISHED", runId, testCase.caseId(), stage.key(), null, stageStatus.name(), Duration.between(stageStarted, Instant.now()).toMillis(), null, stageEventData);
                    validations.addAll(stageResults);
                    if (stageStatus == ResultStatus.FAIL || stageStatus == ResultStatus.ERROR || stageStatus == ResultStatus.INVALID) {
                        hasPriorFailure = true;
                        if ("stop".equalsIgnoreCase(stage.onFailure())) stoppedByFailure = true;
                    }
                }
            }
            validations.addAll(dbHelperExecutor.finishCase(context, caseLog));
            dbFinalized = true;
            ResultStatus status = aggregate(validations);
            if (validations.isEmpty()) status = ResultStatus.PASS;
            ResultStatus finalStatus = options.dryRun() ? ResultStatus.SKIPPED : status;
            context.put("CASE.status", finalStatus.name());
            context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            writeCaseTree(caseOutputDir, context, caseLog);
            return new TestResult(testCase.caseId(), testCase.caseName(), finalStatus,
                    Duration.between(started, Instant.now()), joinExpected(validations), joinActual(validations), caseLogPath, validations,
                    testCase.workbookId(), testCase.groupId(), testCase.tags(), null, validatedCaseId,
                    null, null, !options.dryRun());
        } catch (Exception e) {
            att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(e);
            String errorMessage = typed == null ? message(e) : typed.format();
            try { caseLog.append("ERROR", errorMessage); }
            catch (Exception logError) { errorMessage += "; Case-log write failed: " + message(logError); }
            if (context.hasActiveStage()) {
                try { context.finishStage(ResultStatus.ERROR.name(), Duration.between(started, Instant.now()).toMillis()); }
                catch (Exception stageError) { errorMessage += "; Stage finalization failed: " + message(stageError); }
            }
            context.put("CASE.status", ResultStatus.ERROR.name());
            context.put("CASE.error", errorMessage);
            if (typed != null) context.put("CASE.errorDiagnostic", typed.toDiagnostic().toMap());
            context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            try { writeCaseTree(caseOutputDir, context, caseLog); }
            catch (Exception treeError) {
                errorMessage += "; Case evidence write failed: " + message(treeError);
                // Keep the persisted CASE.error in sync with the final error text
                // even when the first evidence write itself failed.
                context.put("CASE.error", errorMessage);
            }
            return error(testCase, errorMessage, caseLogPath, Duration.between(started, Instant.now()),
                    typed == null ? null : typed.toDiagnostic(), validatedCaseId, !options.dryRun());
        } finally {
            if (!dbFinalized) dbHelperExecutor.abortCase();
            caseLog.close();
        }
    }

    private TestResult runNativeCase(ResolvedTestCase testCase, String runId, Instant runStartedAt,
            Path runDirectory, UnifiedTemplateEngine templateEngine, FlowRegistry flows,
            att.exec.DbHelperExecutor dbHelperExecutor, boolean dryRun,
            att.api.ExecutionEventListener observer) throws Exception {
        Instant started = Instant.now();
        String executionId = nativeExecutionId(runId, testCase.qualifiedCaseId());
        Path executionsDirectory = runDirectory.resolve("executions");
        Files.createDirectories(executionsDirectory);
        Path caseOutputDir = IdentifierValidator.strictChild(executionsDirectory, executionId, "Native Case execution directory");
        Files.createDirectory(caseOutputDir);
        Path caseLogPath = caseOutputDir.resolve("case.log");
        Map<String,Object> identity = nativeIdentityData(testCase.definitionId(), testCase.sourceReference(),
                testCase.sourceRevision(), testCase);
        identity.put("executionId", executionId);
        emitNativeEvent(observer, "CASE_STARTED", runId, testCase.qualifiedCaseId(), "RUNNING", null, identity);

        java.util.function.Consumer<String> mirror = observer == null ? null : new java.util.function.Consumer<String>() {
            @Override public void accept(String text) {
                observer.onEvent(new att.api.ExecutionEvent(att.api.ExecutionEvent.Type.CASE_LOG, runId,
                        testCase.qualifiedCaseId(), null, null, null, null, text, identity));
            }
        };
        try (CaseExecutionLog caseLog = new CaseExecutionLog(caseLogPath, config.caseLogYamlAnchors(), mirror)) {
            caseLog.setProjectRoot(projectRoot);
            CaseRuntimeContext context = new CaseRuntimeContext(testCase, caseOutputDir, executionId,
                    runId, runDirectory, caseLogPath, "testcase", started.toString(), runStartedAt.toString());
            context.setProject(projectRoot);
            context.put("CASE.environment", config.environment());
            context.replaceInputValues(testCase.inputs());
            caseLog.append("CASE", context.caseTree());

            List<ValidationResult> validations = new ArrayList<ValidationResult>();
            boolean dbFinalized = false;
            dbHelperExecutor.beginCase();
            try {
                if (dryRun) {
                    caseLog.append("STAGE RUN", mapOfNative("status", "SKIPPED", "flowId", testCase.flowId()));
                } else {
                    FlowDefinition flow = flows.get(testCase.flowId());
                    if (flow == null) throw new IllegalArgumentException("Resolved Flow is unavailable: " + testCase.flowId());
                    Map<String,Object> actionData = new LinkedHashMap<String,Object>();
                    actionData.put("id", "run"); actionData.put("type", "flow"); actionData.put("use", testCase.flowId());
                    actionData.put("description", testCase.name() == null ? "Native Case Flow" : testCase.name());
                    TemplateAction flowAction = new TemplateAction("run", actionData);
                    Path definitionDirectory = projectRoot.resolve(testCase.sourceReference()).normalize().getParent();
                    StageTemplate nativeWrapper = new StageTemplate("Native Case", definitionDirectory,
                            Collections.singletonList(flowAction));
                    StageTemplateRunner runner = new StageTemplateRunner(templateEngine, flows);
                    StageCaseData stage = new StageCaseData("run", nativeWrapper.name(), Collections.<String,Object>emptyMap());
                    context.beginStage(stage, nativeWrapper.name(), nativeWrapper.directory());
                    caseLog.append("STAGE RUN", mapOfNative("flowId", testCase.flowId(), "status", "RUNNING"));
                    List<ValidationResult> flowResults = runner.execute("run", nativeWrapper, context, caseLog);
                    validations.addAll(flowResults);
                    ResultStatus flowStatus = aggregate(flowResults);
                    context.finishStage(flowStatus.name(), Duration.between(started, Instant.now()).toMillis());
                    for (ValidationResult actionResult : flowResults) {
                        Map<String,Object> actionIdentity = new LinkedHashMap<String,Object>(identity);
                        actionIdentity.put("flowId", testCase.flowId());
                        actionIdentity.put("detail", actionResult.message());
                        emitNativeEvent(observer, "ACTION_FINISHED", runId, testCase.qualifiedCaseId(),
                                actionResult.status().name(), null, actionIdentity);
                    }
                }
                validations.addAll(dbHelperExecutor.finishCase(context, caseLog));
                dbFinalized = true;
                ResultStatus status = dryRun ? ResultStatus.SKIPPED
                        : validations.isEmpty() ? ResultStatus.PASS : aggregate(validations);
                context.put("CASE.status", status.name());
                context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
                writeCaseTree(caseOutputDir, context, caseLog);
                TestResult result = new TestResult(testCase.qualifiedCaseId(), nativeCaseName(testCase), status,
                        Duration.between(started, Instant.now()), joinExpected(validations), joinActual(validations),
                        caseLogPath, validations, "", "", testCase.tags(), null, executionId,
                        testCase.definitionId(), testCase.qualifiedCaseId(), !dryRun, testCase.flowId(),
                        testCase.flowRevision(), testCase.sourceRevision(), testCase.sourceProvenance().toMap());
                identity.put("executionId", executionId);
                emitNativeEvent(observer, "CASE_FINISHED", runId, testCase.qualifiedCaseId(), status.name(),
                        result.duration().toMillis(), identity);
                return result;
            } catch (Exception e) {
                att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(e);
                String errorMessage = typed == null ? message(e) : typed.format();
                try { caseLog.append("ERROR", errorMessage); }
                catch (Exception logError) { errorMessage += "; Case-log write failed: " + message(logError); }
                if (context.hasActiveStage()) {
                    try { context.finishStage(ResultStatus.ERROR.name(), Duration.between(started, Instant.now()).toMillis()); }
                    catch (Exception stageError) { errorMessage += "; Stage finalization failed: " + message(stageError); }
                }
                context.put("CASE.status", ResultStatus.ERROR.name());
                context.put("CASE.error", errorMessage);
                if (typed != null) context.put("CASE.errorDiagnostic", typed.toDiagnostic().toMap());
                context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
                try { writeCaseTree(caseOutputDir, context, caseLog); }
                catch (Exception treeError) { errorMessage += "; Case evidence write failed: " + message(treeError); }
                Duration duration = Duration.between(started, Instant.now());
                TestResult result = new TestResult(testCase.qualifiedCaseId(), nativeCaseName(testCase), ResultStatus.ERROR,
                        duration, "", errorMessage, caseLogPath, validations, "", "", testCase.tags(),
                        typed == null ? null : typed.toDiagnostic(), executionId, testCase.definitionId(),
                        testCase.qualifiedCaseId(), !dryRun, testCase.flowId(), testCase.flowRevision(),
                        testCase.sourceRevision(), testCase.sourceProvenance().toMap());
                emitNativeEvent(observer, "CASE_FINISHED", runId, testCase.qualifiedCaseId(), ResultStatus.ERROR.name(),
                        duration.toMillis(), identity);
                return result;
            } finally {
                if (!dbFinalized && !dryRun) dbHelperExecutor.abortCase();
            }
        }
    }

    private List<ResolvedTestCase> validateNativeSuiteResolution(TestCaseSuiteResolutionResult resolution) {
        if (resolution == null || resolution.definitions().isEmpty())
            throw new IllegalArgumentException("A resolved native Test Case Definition is required");
        if (resolution.truncated())
            throw new IllegalArgumentException("A truncated Native Case Suite cannot be executed; resolve the complete selection first");
        if (resolution.emptySelection() || resolution.selectedCount() == 0)
            throw new IllegalArgumentException("Native Case Suite selection is empty");
        if (resolution.selectedCount() > TestCaseSuiteResolver.MAX_SELECTED_CASES)
            throw new IllegalArgumentException("Native RUN supports at most " + TestCaseSuiteResolver.MAX_SELECTED_CASES + " selected Cases");
        Map<String,TestCaseResolutionResult> definitions = new LinkedHashMap<String,TestCaseResolutionResult>();
        for (TestCaseResolutionResult definition : resolution.definitions()) {
            if (definition == null || definition.definition() == null)
                throw new IllegalArgumentException("Native Case Suite contains an incomplete Definition resolution");
            if (definitions.put(definition.definition().id(), definition) != null)
                throw new IllegalArgumentException("Native Case Suite contains a duplicate Definition ID");
        }
        List<ResolvedTestCase> cases = new ArrayList<ResolvedTestCase>(resolution.cases());
        Set<String> seen = new LinkedHashSet<String>();
        for (ResolvedTestCase testCase : cases) {
            TestCaseResolutionResult definition = testCase == null ? null : definitions.get(testCase.definitionId());
            if (definition == null || !testCase.qualifiedCaseId().equals(testCase.definitionId() + "." + testCase.caseId())
                    || !definition.sourceReference().equals(testCase.sourceReference())
                    || !definition.sourceRevision().equals(testCase.sourceRevision()))
                throw new IllegalArgumentException("Native Case resolution identity is inconsistent");
            if (!seen.add(testCase.qualifiedCaseId()))
                throw new IllegalArgumentException("Native Case selection contains a duplicate Qualified Case ID: " + testCase.qualifiedCaseId());
        }
        if (cases.size() != resolution.selectedCount())
            throw new IllegalArgumentException("Native Case Suite does not contain its complete selected Case set");
        return cases;
    }

    private List<Map<String,Object>> verifyNativeSources(TestCaseSuiteResolutionResult resolution,
            List<ResolvedTestCase> cases, FlowRegistry flows) throws Exception {
        PackageResourceResolver resources = new PackageResourceResolver(projectRoot);
        Map<String,Map<String,Object>> captured = new LinkedHashMap<String,Map<String,Object>>();
        for (TestCaseResolutionResult definitionResolution : resolution.definitions()) {
            Path definition = resources.resolvePackageRelative(definitionResolution.sourceReference(), PackageResourceResolver.Kind.FILE).canonicalPath();
            byte[] definitionBytes = readNativeSource(definition, TestCaseDefinitionResolver.MAX_SOURCE_BYTES, "Native Test Case Definition");
            String actualDefinitionRevision = sha256Bytes(definitionBytes);
            if (!actualDefinitionRevision.equals(definitionResolution.sourceRevision()))
                throw new IllegalArgumentException("Native Test Case Definition changed after resolution; resolve again before RUN: "
                        + definitionResolution.definition().id());
            addNativeInput(captured, "test-case-definition", definitionResolution.sourceReference(), definitionResolution.sourceRevision());
        }

        Map<String,String> dependencyRevisions = new LinkedHashMap<String,String>();
        for (ResolvedTestCase testCase : cases) {
            String priorRootRevision = dependencyRevisions.putIfAbsent(testCase.flowId(), testCase.flowRevision());
            if (priorRootRevision != null && !priorRootRevision.equals(testCase.flowRevision()))
                throw new IllegalArgumentException("Native Case Flow revision changed between selected Cases");
            for (ResolvedTestCase.FlowDependency dependency : testCase.dependencies()) {
                String previous = dependencyRevisions.putIfAbsent(dependency.id(), dependency.revision());
                if (previous != null && !previous.equals(dependency.revision()))
                    throw new IllegalArgumentException("Native Case Flow dependency has conflicting revisions: " + dependency.id());
            }
        }
        for (Map.Entry<String,String> entry : dependencyRevisions.entrySet()) {
            FlowDefinition flow = flows.get(entry.getKey());
            if (flow == null) throw new IllegalArgumentException("Resolved Flow is unavailable: " + entry.getKey());
            byte[] sourceBytes = flows.descriptorBytes(entry.getKey());
            if (sourceBytes == null || !entry.getValue().equals(sha256Bytes(sourceBytes)))
                throw new IllegalArgumentException("Native Case Flow changed after resolution; resolve again before RUN: " + entry.getKey());
            Path sourcePath = flows.descriptorPath(entry.getKey());
            String logicalPath = resources.fromInternalPath(sourcePath, PackageResourceResolver.Kind.FILE).logicalName();
            boolean referenced = false;
            for (ResolvedTestCase testCase : cases) for (ResolvedTestCase.FlowDependency dependency : testCase.dependencies())
                if (dependency.id().equals(entry.getKey()) && dependency.sourceReference().equals(logicalPath)) referenced = true;
            if (!referenced) throw new IllegalArgumentException("Native Case Flow source identity changed after resolution: " + entry.getKey());
            for (TemplateAction action : flow.actions()) if ("flow".equalsIgnoreCase(action.type())) flows.validateInvocation(action);
            addNativeInput(captured, "flow", logicalPath, entry.getValue());
        }

        // Excel DATA rows are part of the frozen RUN source identity even though Definition YAML
        // hashes exclude the workbook. Re-check every distinct workbook against the SHA captured
        // during resolution so a workbook-only edit cannot execute against a stale resolution.
        Map<String,String> workbookRevisions = new LinkedHashMap<String,String>();
        for (ResolvedTestCase testCase : cases) {
            CaseSourceProvenance provenance = testCase.sourceProvenance();
            if (!"excel".equals(provenance.sourceType())) continue;
            String logicalPath = provenance.sourceReference();
            Object sha = provenance.metadata().get("workbookSha256");
            if (!(sha instanceof String) || !((String) sha).matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Native Case Excel Data Source is missing a workbook SHA-256: " + logicalPath);
            String previous = workbookRevisions.putIfAbsent(logicalPath, (String) sha);
            if (previous != null && !previous.equals(sha))
                throw new IllegalArgumentException("Native Case Excel Data Source has conflicting workbook revisions: " + logicalPath);
        }
        int workbookLimit = ExcelDataSourceAdapter.MAX_WORKBOOK_BYTES > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) ExcelDataSourceAdapter.MAX_WORKBOOK_BYTES;
        for (Map.Entry<String,String> entry : workbookRevisions.entrySet()) {
            Path workbook = resources.resolvePackageRelative(entry.getKey(), PackageResourceResolver.Kind.FILE).canonicalPath();
            byte[] workbookBytes = readNativeSource(workbook, workbookLimit, "Native Excel Data Source");
            if (!entry.getValue().equals(sha256Bytes(workbookBytes)))
                throw new IllegalArgumentException("Native Excel Data Source changed after resolution; resolve again before RUN: " + entry.getKey());
            String logicalPath = resources.fromInternalPath(workbook, PackageResourceResolver.Kind.FILE).logicalName();
            if (!entry.getKey().equals(logicalPath))
                throw new IllegalArgumentException("Native Excel Data Source path identity changed after resolution: " + entry.getKey());
            addNativeInput(captured, "excel-data-source", logicalPath, entry.getValue());
        }
        return new ArrayList<Map<String,Object>>(captured.values());
    }

    private byte[] readNativeSource(Path source, int maximumBytes, String label) throws Exception {
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException(label + " source is unavailable");
        long size = Files.size(source);
        if (size > maximumBytes) throw new IllegalArgumentException(label + " source exceeds its size limit");
        try (java.io.InputStream input = Files.newInputStream(source, java.nio.file.LinkOption.NOFOLLOW_LINKS);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream((int) size)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > maximumBytes) throw new IllegalArgumentException(label + " source exceeds its size limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void addNativeInput(Map<String,Map<String,Object>> inputs, String kind, String path, String digest) {
        String key = kind + "\n" + path;
        if (inputs.containsKey(key)) return;
        Map<String,Object> item = new LinkedHashMap<String,Object>();
        item.put("kind", kind); item.put("path", path); item.put("sha256", digest);
        inputs.put(key, item);
    }

    private String nativeExecutionId(String runId, String qualifiedCaseId) throws Exception {
        return "native-" + sha256Bytes((runId + "\n" + qualifiedCaseId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 24);
    }

    private String nativeCaseName(ResolvedTestCase testCase) {
        return testCase.name() == null || testCase.name().trim().isEmpty()
                ? testCase.qualifiedCaseId() : testCase.name();
    }

    private Map<String,Object> nativeIdentityData(String definitionId, String sourceReference,
            String sourceRevision, ResolvedTestCase testCase) {
        Map<String,Object> data = new LinkedHashMap<String,Object>();
        data.put("targetType", "nativeCase"); data.put("definitionId", definitionId);
        data.put("sourceReference", sourceReference); data.put("sourceRevision", sourceRevision);
        if (testCase != null) {
            data.put("localCaseId", testCase.caseId());
            data.put("qualifiedCaseId", testCase.qualifiedCaseId());
            data.put("flowId", testCase.flowId()); data.put("flowRevision", testCase.flowRevision());
            data.put("sourceRecordId", testCase.sourceRecordId()); data.put("recordIndex", testCase.recordIndex());
        }
        return data;
    }

    private Map<String,Object> nativeSuiteIdentityData(TestCaseSuiteResolutionResult resolution,
            ResolvedTestCase testCase) {
        if (testCase != null) return nativeIdentityData(testCase.definitionId(), testCase.sourceReference(),
                testCase.sourceRevision(), testCase);
        if (!resolution.implicitSuite() && resolution.definitions().size() == 1) {
            TestCaseResolutionResult definition = resolution.definitions().get(0);
            return nativeIdentityData(definition.definition().id(), definition.sourceReference(), definition.sourceRevision(), null);
        }
        Map<String,Object> data = new LinkedHashMap<String,Object>();
        data.put("targetType", "nativeSuite");
        List<Map<String,Object>> definitions = new ArrayList<Map<String,Object>>();
        List<String> definitionIds = new ArrayList<String>();
        for (TestCaseResolutionResult definition : resolution.definitions()) {
            definitionIds.add(definition.definition().id());
            Map<String,Object> item = new LinkedHashMap<String,Object>();
            item.put("definitionId", definition.definition().id());
            item.put("sourceReference", definition.sourceReference());
            item.put("sourceRevision", definition.sourceRevision());
            definitions.add(item);
        }
        data.put("definitionIds", definitionIds);
        data.put("definitions", definitions);
        return data;
    }

    private void emitNativeEvent(att.api.ExecutionEventListener observer, String event, String runId,
            String caseId, String status, Long durationMs, Map<String,Object> data) {
        if (observer == null) return;
        Map<String,Object> fields = new LinkedHashMap<String,Object>(data == null
                ? Collections.<String,Object>emptyMap() : data);
        fields.put("event", event);
        observer.onEvent(new att.api.ExecutionEvent(att.api.ExecutionEvent.Type.PROGRESS, runId, caseId,
                "run".equals(event) ? "run" : null, null, status, durationMs, null, fields));
    }

    private Map<String,Object> mapOfNative(Object... values) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (int index = 0; index + 1 < values.length; index += 2)
            result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private ExecutionOptions nativeManifestOptions(String runId, Path outputRoot, boolean dryRun,
            boolean failFast, Set<String> ciOutputs) {
        return ExecutionOptions.forApi("run", projectRoot.resolve("config/config.yaml"), config.environment(),
                Collections.<Path>emptyList(), null, Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), runId, false, false, dryRun, failFast, outputRoot,
                "selected", null, null, null, false, null, null, null, null, null, null, null, null, null, null,
                Collections.<String>emptyList()).withRunPolicies(ciOutputs, "reject", false);
    }

    private Map<String,Object> nativeRunMetadata(TestCaseSuiteResolutionResult resolution, RunSummary summary,
            att.api.NativeSuiteRetryLineage retryLineage) throws Exception {
        Map<String,Object> metadata = new LinkedHashMap<String,Object>();
        metadata.put("executionType", "run");
        metadata.put("targetType", resolution.implicitSuite() ? "nativeSuite" : "nativeCase");
        metadata.put("packageId", resolution.cases().get(0).packageId());
        List<Map<String,Object>> definitionValues = new ArrayList<Map<String,Object>>();
        List<String> definitionIds = new ArrayList<String>();
        for (TestCaseResolutionResult definition : resolution.definitions()) {
            definitionIds.add(definition.definition().id());
            Map<String,Object> value = new LinkedHashMap<String,Object>();
            value.put("definitionId", definition.definition().id());
            value.put("sourceReference", definition.sourceReference());
            value.put("sourceRevision", definition.sourceRevision());
            definitionValues.add(value);
        }
        metadata.put("definitionIds", definitionIds);
        metadata.put("definitions", definitionValues);
        if (resolution.definitions().size() == 1) {
            TestCaseResolutionResult definition = resolution.definitions().get(0);
            metadata.put("definitionId", definition.definition().id());
            metadata.put("sourceReference", definition.sourceReference());
            metadata.put("sourceRevision", definition.sourceRevision());
        }
        metadata.put("resolvedCaseCount", resolution.resolvedCount());
        metadata.put("selectedCaseCount", resolution.selectedCount());
        metadata.put("executionCount", summary.executionCount());
        metadata.put("skippedCaseCount", summary.skipped());
        List<Map<String,Object>> caseSources = new ArrayList<Map<String,Object>>();
        for (ResolvedTestCase testCase : resolution.cases())
            caseSources.add(att.testcase.NativeSuiteRetryResolver.caseSourceIdentity(testCase));
        metadata.put("caseSources", caseSources);
        metadata.put("sourceIdentityDigest", nativeSourceIdentityDigest(definitionValues, caseSources));
        if (retryLineage != null) metadata.put("retryLineage", retryLineage.toManifestMap(
                summary.executionCount(), summary.skipped()));
        return metadata;
    }

    private String nativeSourceIdentityDigest(List<Map<String,Object>> definitions,
            List<Map<String,Object>> caseSources) throws Exception {
        List<Object> identity=new ArrayList<Object>();identity.add(definitions);identity.add(caseSources);
        return sha256Bytes(new Yaml().dump(identity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void verifyJunit(Path junitFile) throws Exception {
        javax.xml.validation.SchemaFactory schemaFactory = javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemaFactory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        schemaFactory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        att.validation.SchemaFiles.SchemaResource junitSchema = att.validation.SchemaFiles.readSchema(
                att.validation.SchemaFiles.resolve(projectRoot, "att-junit-v2.1.xsd"));
        javax.xml.validation.Schema compiledSchema;
        try (java.io.InputStream schemaBytes = new java.io.ByteArrayInputStream(junitSchema.bytes())) {
            javax.xml.transform.stream.StreamSource schemaSource = new javax.xml.transform.stream.StreamSource(schemaBytes);
            schemaSource.setSystemId(junitSchema.identity());
            compiledSchema = schemaFactory.newSchema(schemaSource);
        }
        javax.xml.validation.Validator validator = compiledSchema.newValidator();
        validator.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        validator.validate(new javax.xml.transform.stream.StreamSource(junitFile.toFile()));
    }

    private boolean shouldRunStage(StageConfig stage, boolean hasPriorFailure, boolean stoppedByFailure) {
        String runWhen = stage.runWhen();
        if ("always".equalsIgnoreCase(runWhen)) {
            return true;
        }
        if ("onFailure".equalsIgnoreCase(runWhen) || "failure".equalsIgnoreCase(runWhen) || "failed".equalsIgnoreCase(runWhen)) {
            return hasPriorFailure;
        }
        if ("onSuccess".equalsIgnoreCase(runWhen)) return !hasPriorFailure;
        return !stoppedByFailure;
    }

    private ResultStatus aggregate(List<ValidationResult> results) {
        List<ResultStatus> statuses = new ArrayList<ResultStatus>();
        for (ValidationResult result : results) statuses.add(result.status());
        return ResultAggregator.aggregate(statuses);
    }

    private Path resolve(Path path) {
        return path.isAbsolute() ? path : projectRoot.resolve(path).normalize();
    }

    private ExecutionPlan buildPlan(ExecutionOptions options, Instant identityStartedAt) throws Exception {
        String runId = runId(options, identityStartedAt);
        Path outputRoot = options.outputDirectory() == null ? resolve(config.outputDirectory()) : resolve(options.outputDirectory());
        Path finalRunDirectory = IdentifierValidator.strictChild(outputRoot, runId, "Run directory");
        if (Files.exists(finalRunDirectory)) throw new IllegalArgumentException("Run ID already exists: " + runId + " (" + finalRunDirectory + "). Choose a different --run-id.");
        Set<String> failed = options.rerunFailed() ? latestFailedCaseIds(outputRoot) : Collections.<String>emptySet();
        SuiteConfigResolver resolver = new SuiteConfigResolver(projectRoot, config);
        List<ExecutionPlan.Suite> suitePlans = new ArrayList<ExecutionPlan.Suite>();
        Map<String, Path> workbookIds = new LinkedHashMap<String, Path>();
        Map<String, Path> fullCaseIds = new LinkedHashMap<String, Path>();
        for (Path selected : suites(options)) {
            Path workbook = resolve(selected);
            FrameworkConfig suiteConfig = resolver.resolve(workbook);
            Path previousWorkbook = workbookIds.put(suiteConfig.workbookId(), workbook);
            if (previousWorkbook != null) throw new IllegalArgumentException("Duplicate workbook id '" + suiteConfig.workbookId() + "' in " + previousWorkbook + " and " + workbook);
            StageTemplateLoader loader = new StageTemplateLoader(projectRoot, suiteConfig.templatesRoot());
            att.flow.FlowRegistry flows = new att.flow.FlowRegistry(projectRoot, suiteConfig.templatesRoot(), false);
            List<TestCase> selectedCases = new ArrayList<TestCase>();
            Map<String, StageTemplate> templates = new LinkedHashMap<String, StageTemplate>();
            List<TestCase> loadedCases = new ExcelTestSuiteLoader(suiteConfig).load(workbook);
            new TestcaseSnapshotService().verify(workbook, suiteConfig, loadedCases);
            for (TestCase testCase : loadedCases) {
                if (!options.matches(testCase) || (options.rerunFailed() && !failed.contains(testCase.caseId()))) continue;
                Path previousCase = fullCaseIds.put(testCase.caseId(), workbook);
                if (previousCase != null) throw new IllegalArgumentException("Duplicate full Case ID '" + testCase.caseId() + "' in " + previousCase + " and " + workbook);
                selectedCases.add(testCase);
                for (StageCaseData stage : testCase.stages().values()) if (!templates.containsKey(stage.templateName())) {
                    StageTemplate template = loader.load(stage.templateName());
                    for (att.template.TemplateAction action : template.actions()) if ("flow".equalsIgnoreCase(action.type())) flows.validateInvocation(action);
                    templates.put(stage.templateName(), template);
                }
            }
            if (!selectedCases.isEmpty()) suitePlans.add(new ExecutionPlan.Suite(workbook, suiteConfig, selectedCases, templates, flows));
        }
        ExecutionPlan plan = new ExecutionPlan(runId, outputRoot, finalRunDirectory, suitePlans);
        if (plan.caseCount() == 0) throw new IllegalArgumentException("Case selection is empty after rerun-failed filtering");
        return plan;
    }

    private void progress(ExecutionOptions options, String event, String runId, String caseId, String stage,
                         String action, String status, Long durationMs, String message, Map<String,Object> data) {
        if (!options.hasObserver()) return;
        Map<String,Object> fields = new LinkedHashMap<String,Object>(data); fields.put("event", event);
        options.emitEvent(new att.api.ExecutionEvent(att.api.ExecutionEvent.Type.PROGRESS, runId, caseId,
                stage, action, status, durationMs, message, fields));
    }

    private String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private String portable(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/') : absolute.getFileName().toString();
    }

    private String runId(ExecutionOptions options) {
        return runId(options, options.identitySeed() == null ? Instant.now() : options.identitySeed().startedAt());
    }

    private String runId(ExecutionOptions options, Instant identityStartedAt) {
        if (options.runId() != null && !options.runId().trim().isEmpty()) {
            return IdentifierValidator.runId(options.runId());
        }
        String fallback = DateTimeFormatter.ofPattern(config.run().timestampFormat()).format(LocalDateTime.now());
        Path source = !options.suitePaths().isEmpty() ? options.suitePaths().get(0)
                : options.suiteDirectory() == null ? config.testcasesRoot() : options.suiteDirectory();
        return ExecutionIdentityFormat.runId(config.run().runIdFormat(), fallback, projectRoot,
                "testcase", source.isAbsolute() ? source : projectRoot.resolve(source), identityStartedAt);
    }

    private List<Path> suites(ExecutionOptions options) throws Exception {
        List<Path> suites = new ArrayList<Path>();
        if (options.suiteDirectory() != null || options.suitePaths().isEmpty()) {
            Path configured = options.suiteDirectory() == null ? config.testcasesRoot() : options.suiteDirectory();
            try (java.util.stream.Stream<Path> paths = Files.walk(resolve(configured))) {
                paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx"))
                        .filter(path -> Files.isRegularFile(path.resolveSibling(path.getFileName().toString().replaceFirst("(?i)\\.xlsx$", ".yaml"))))
                        .sorted().forEach(suites::add);
            }
        } else {
            suites.addAll(options.suitePaths());
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
        Map<String, Object> latestMap = (Map<String, Object>) loaded;
        if (latestMap.get("runDirectory") != null && !latestMap.containsKey("cases")) {
            String logicalRunId = String.valueOf(latestMap.get("runId"));
            IdentifierValidator.runId(logicalRunId);
            Path manifest = IdentifierValidator.strictChild(outputRoot, logicalRunId, "Latest run directory").resolve("run.yaml");
            String expectedHash = String.valueOf(latestMap.get("manifestSha256"));
            if (expectedHash.length() != 64 || !expectedHash.equals(sha256(manifest))) throw new IllegalArgumentException("Latest run manifest hash does not match: " + logicalRunId);
            loaded = YamlSupport.parser().load(new String(Files.readAllBytes(manifest), java.nio.charset.StandardCharsets.UTF_8));
            if (!(loaded instanceof Map)) return failed;
        }
        Object cases = ((Map<String, Object>) loaded).get("cases");
        if (!(cases instanceof Iterable)) {
            return failed;
        }
        for (Object item : (Iterable<Object>) cases) {
            if (item instanceof Map) {
                Map<String, Object> row = (Map<String, Object>) item;
                String status = String.valueOf(row.get("status"));
                if ("FAIL".equals(status) || "ERROR".equals(status) || "INVALID".equals(status)) {
                    failed.add(String.valueOf(row.get("caseId")));
                }
            }
        }
        return failed;
    }

    private void writeManifest(Path runDirectory, String runId, List<TestResult> results, Instant startedAt, Instant endedAt, Path reportPath, ExecutionOptions options, List<Map<String, Object>> inputs, List<att.validation.Diagnostic> validationDiagnostics) throws Exception {
        writeManifest(runDirectory, runId, results, startedAt, endedAt, reportPath, options, inputs,
                validationDiagnostics, null);
    }

    private void writeManifest(Path runDirectory, String runId, List<TestResult> results, Instant startedAt,
            Instant endedAt, Path reportPath, ExecutionOptions options, List<Map<String, Object>> inputs,
            List<att.validation.Diagnostic> validationDiagnostics, Map<String,Object> nativeRunMetadata) throws Exception {
        Map<String, Object> history = new LinkedHashMap<String, Object>();
        history.put("schemaVersion", Version.RUN_SCHEMA);
        Map<String, Object> att = new LinkedHashMap<String, Object>(); att.put("version", Version.PRODUCT); att.put("buildTime", Version.BUILD_TIME); att.put("gitCommit", Version.GIT_COMMIT); history.put("att", att);
        Map<String, Object> runtime = new LinkedHashMap<String, Object>(); runtime.put("javaVersion", System.getProperty("java.version")); runtime.put("javaVendor", System.getProperty("java.vendor")); runtime.put("osName", System.getProperty("os.name")); runtime.put("osVersion", System.getProperty("os.version")); runtime.put("osArchitecture", System.getProperty("os.arch")); runtime.put("locale", java.util.Locale.getDefault().toLanguageTag()); runtime.put("timezone", java.util.TimeZone.getDefault().getID()); history.put("runtime", runtime);
        Map<String, Object> run = new LinkedHashMap<String, Object>(); run.put("id", runId); run.put("state", "COMPLETE"); run.put("environment", config.environment()); run.put("startedAt", startedAt.toString()); run.put("endedAt", endedAt.toString());
        if (nativeRunMetadata != null) run.putAll(nativeRunMetadata);
        history.put("run", run);
        List<Map<String, Object>> diagnosticMaps = new ArrayList<Map<String, Object>>(); for (att.validation.Diagnostic diagnostic : validationDiagnostics) diagnosticMaps.add(diagnostic.toMap());
        Map<String, Object> validation = new LinkedHashMap<String, Object>(); validation.put("mode", options.validationScope()); validation.put("diagnostics", diagnosticMaps); history.put("validation", validation);
        history.put("inputs", inputs);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        RunSummary runSummary = new RunSummary(results, runDirectory);
        summary.put("total", runSummary.total());
        summary.put("executionCount", runSummary.executionCount());
        summary.put("passed", runSummary.passed());
        summary.put("failed", runSummary.failed());
        summary.put("error", runSummary.error());
        summary.put("skipped", runSummary.skipped());
        summary.put("invalid", runSummary.invalid());
        history.put("summary", summary);
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        for (TestResult result : results) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("caseId", result.caseId());
            item.put("execId", result.executionId());
            if (result.definitionId() != null) {
                item.put("definitionId", result.definitionId());
                item.put("localCaseId", result.qualifiedCaseId().substring(result.definitionId().length() + 1));
                item.put("qualifiedCaseId", result.qualifiedCaseId());
                item.put("flowId", result.flowId());
                item.put("flowRevision", result.flowRevision());
                item.put("sourceRevision", result.sourceRevision());
                item.put("sourceProvenance", result.sourceProvenance());
            }
            item.put("caseName", result.caseName());
            item.put("executed", result.executionStarted());
            item.put("workbookId", result.workbookId());
            item.put("groupId", result.groupId());
            item.put("tags", result.tags());
            item.put("status", result.status().name());
            item.put("durationMs", result.duration().toMillis());
            item.put("expected", result.expected());
            item.put("actual", result.actual());
            List<Map<String,Object>> actionResults = new ArrayList<Map<String,Object>>();
            for (ValidationResult action : result.validations()) {
                Map<String,Object> detail = new LinkedHashMap<String,Object>(); detail.put("stage", action.source()); detail.put("action", action.name()); detail.put("description", action.description()); detail.put("status", action.status().name()); detail.put("expected", action.expected()); detail.put("actual", action.actual()); detail.put("message", action.message());
                if (action.diagnostic() != null) detail.put("diagnostic", action.diagnostic().toMap());
                actionResults.add(detail);
            }
            item.put("actions", actionResults);
            if (result.diagnostic() != null) item.put("diagnostic", result.diagnostic().toMap());
            item.put("caseLog", result.caseLogPath() == null ? "" : runDirectory.relativize(result.caseLogPath()).toString().replace('\\', '/'));
            cases.add(item);
        }
        history.put("cases", cases);
        Map<String, Object> outputs = new LinkedHashMap<String, Object>(); outputs.put("html", "report/index.html"); if (options.ciOutputs().contains("json")) outputs.put("json", "ci/summary.json"); if (options.ciOutputs().contains("junit")) { outputs.put("junit", "ci/junit.xml"); outputs.put("junitHtml", "report/junit.html"); }
        if (results.stream().anyMatch(result -> result.definitionId() != null)) outputs.put("xlsx", NativeCaseExcelReportWriter.OUTPUT_PATH);
        history.put("outputs", outputs);
        JsonSchemaVerifier.verify(SchemaFiles.resolve(projectRoot, "att-run-v2.1.schema.json"), history);
        byte[] bytes = new Yaml().dump(history).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(runDirectory.resolve("run.yaml"), bytes);
    }

    private void writeLatest(Path outputRoot, Path runDirectory, String runId, RunSummary summary, Instant endedAt) throws Exception {
        writeLatest(outputRoot,runDirectory,runId,summary,endedAt,"latest-run.yaml");
    }

    private void writeLatest(Path outputRoot,Path runDirectory,String runId,RunSummary summary,Instant endedAt,
            String pointerFileName) throws Exception {
        Path absoluteOutputRoot = IdentifierValidator.canonicalPath(outputRoot, "Output root");
        Path absoluteRunDirectory = IdentifierValidator.canonicalPath(runDirectory, "Run directory");
        if (!absoluteOutputRoot.equals(absoluteRunDirectory.getParent()))
            throw new IllegalArgumentException("Run directory must be a direct child of the output directory: "
                    + absoluteRunDirectory + " under " + absoluteOutputRoot);
        Map<String, Object> pointer = new LinkedHashMap<String, Object>(); pointer.put("schemaVersion", "att-latest-run/v2.1"); pointer.put("runId", runId); pointer.put("runDirectory", absoluteOutputRoot.relativize(absoluteRunDirectory).toString().replace('\\', '/')); pointer.put("completedAt", endedAt.toString()); pointer.put("status", summary.status().name()); pointer.put("manifestSha256", sha256(absoluteRunDirectory.resolve("run.yaml")));
        byte[] bytes = new Yaml().dump(pointer).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(pointerFileName==null||!pointerFileName.matches("latest-(?:run|native-suite-[a-f0-9]{64})\\.yaml"))
            throw new IllegalArgumentException("Invalid latest RUN pointer filename");
        Path latest = absoluteOutputRoot.resolve(pointerFileName);
        Path temporary = absoluteOutputRoot.resolve(pointerFileName+".tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, latest, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private List<Map<String, Object>> inputHashes(ExecutionOptions options) throws Exception {
        List<Map<String, Object>> inputs = new ArrayList<Map<String, Object>>();
        Path runtimeConfig = att.config.RuntimeConfigPath.resolve(options.configPath(), projectRoot);
        Path runtimeConfigRoot = att.config.RuntimeConfigPath.root(runtimeConfig);
        addInput(inputs, "global-config", runtimeConfig, runtimeConfigRoot);
        for (Path suite : suites(options)) {
            Path workbook = resolve(suite);
            addInput(inputs, "workbook", workbook);
            String sidecar = workbook.getFileName().toString().replaceFirst("(?i)\\.xlsx$", ".yaml");
            addInput(inputs, "sidecar", workbook.resolveSibling(sidecar));
            addInput(inputs, "testcase-snapshot", new TestcaseSnapshotService().snapshotPath(workbook));
        }
        Path templates = resolve(config.templatesRoot());
        if (Files.isDirectory(templates)) try (java.util.stream.Stream<Path> files = Files.walk(templates)) { java.util.Iterator<Path> iterator = files.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path)).filter(path -> !path.getFileName().toString().startsWith(".")).sorted().iterator(); while (iterator.hasNext()) addInput(inputs, "template-input", iterator.next()); }
        java.util.Set<Path> toolGroupFiles = new java.util.LinkedHashSet<Path>();
        java.util.Set<Path> toolSqlFiles = new java.util.LinkedHashSet<Path>();
        att.template.FileExpressionResolver toolFileExpressions = new att.template.FileExpressionResolver(projectRoot);
        for (att.config.ToolConfig tool : config.tools().values()) {
            if (tool.sourceFile() != null && toolGroupFiles.add(tool.sourceFile())) addInput(inputs, "tool-group", tool.sourceFile(), runtimeConfigRoot);
            if (tool.callBacked()) {
                att.template.ToolCallParser.ParsedCall call = new att.template.ToolCallParser().parse(tool.call());
                for (att.template.ToolCallParser.Argument argument : call.arguments()) {
                    addToolFileExpressionInputs(inputs, toolFileExpressions, argument.expression(), projectRoot, toolSqlFiles, runtimeConfigRoot);
                }
            }
            java.util.List<String> command = tool.groupScriptArgv().isEmpty() ? tool.commandArgv() : tool.groupScriptArgv();
            String first = command.isEmpty() ? "" : command.get(0);
            if (tool.ssh() == null && tool.sshHelper().isEmpty() && (first.startsWith("./") || first.startsWith("../"))) addInput(inputs, "tool-executable", projectRoot.resolve(first).normalize());
        }
        java.util.Set<Path> dbHelperFiles = new java.util.LinkedHashSet<Path>();
        for (att.config.DbHelperConfig helper : config.dbHelpers().values()) {
            if (helper.sourceFile() != null && dbHelperFiles.add(helper.sourceFile())) {
                addInput(inputs, "dbhelper", helper.sourceFile(), runtimeConfigRoot);
            }
        }
        java.util.Set<Path> mqHelperFiles = new java.util.LinkedHashSet<Path>();
        for (att.config.MqHelperConfig helper : config.mqHelpers().values()) {
            if (helper.sourceFile() != null && mqHelperFiles.add(helper.sourceFile())) {
                addInput(inputs, "mqhelper", helper.sourceFile(), runtimeConfigRoot);
            }
        }
        return inputs;
    }
    private void addToolFileExpressionInputs(List<Map<String, Object>> inputs,
                                              att.template.FileExpressionResolver resolver,
                                              String expression, Path sourceDirectory,
                                              Set<Path> captured, Path runtimeConfigRoot) throws Exception {
        java.util.ArrayDeque<java.util.Map.Entry<String, Path>> pending = new java.util.ArrayDeque<java.util.Map.Entry<String, Path>>();
        for (String path : resolver.extractReferences(expression)) {
            pending.addLast(new java.util.AbstractMap.SimpleImmutableEntry<String, Path>(path, sourceDirectory));
        }
        while (!pending.isEmpty()) {
            java.util.Map.Entry<String, Path> reference = pending.removeFirst();
            Path file = resolver.resolve(reference.getKey(), reference.getValue());
            if (!captured.add(file)) continue;
            addInput(inputs, "tool-sql", file, runtimeConfigRoot);
            att.template.FileExpressionResolver.CompiledFilePlan plan = resolver.compile(reference.getKey(), reference.getValue());
            for (String nested : plan.filePaths()) {
                pending.addLast(new java.util.AbstractMap.SimpleImmutableEntry<String, Path>(nested, plan.file().getParent()));
            }
        }
    }
    private void addInput(List<Map<String, Object>> inputs, String kind, Path file) throws Exception {
        addInput(inputs, kind, file, null);
    }

    private void addInput(List<Map<String, Object>> inputs, String kind, Path file, Path runtimeConfigRoot) throws Exception {
        if (!Files.isRegularFile(file)) return;
        Path canonicalPackage = projectRoot.toRealPath();
        Path canonicalFile = file.toRealPath();
        String presentedPath;
        if (canonicalFile.startsWith(canonicalPackage)) {
            presentedPath = canonicalPackage.relativize(canonicalFile).toString().replace('\\', '/');
        } else if (runtimeConfigRoot != null && canonicalFile.startsWith(runtimeConfigRoot.toRealPath())) {
            presentedPath = "$ATT_HOME/" + runtimeConfigRoot.toRealPath().relativize(canonicalFile).toString().replace('\\', '/');
        } else {
            throw new IllegalArgumentException("Run input escapes the Package and shared ATT_HOME roots: " + file);
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("kind", kind); item.put("path", presentedPath); item.put("sha256", sha256(canonicalFile)); inputs.add(item);
    }
    private String sha256(Path file) throws Exception { java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256"); try (java.io.InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count); } byte[] hash = digest.digest(); StringBuilder out = new StringBuilder(); for (byte value : hash) out.append(String.format("%02x", value & 255)); return out.toString(); }
    private String sha256Bytes(byte[] bytes) throws Exception { java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256"); byte[] hash = digest.digest(bytes); StringBuilder out = new StringBuilder(); for (byte value : hash) out.append(String.format("%02x", value & 255)); return out.toString(); }

    private void writeCaseTree(Path caseDirectory, CaseRuntimeContext context, CaseExecutionLog caseLog) throws Exception {
        Files.write(caseDirectory.resolve("case.yaml"), caseLog.protectSnapshotText(new Yaml().dump(att.security.SecretProtection.value(PathPresentation.displayStructure(context.caseTree(), projectRoot)))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void appendEvent(Path runDirectory, String runId, TestResult result) throws Exception {
        Map<String,Object> event = new LinkedHashMap<String,Object>(); event.put("runId", runId); event.put("caseId", result.caseId()); event.put("status", result.status().name()); event.put("durationMs", result.duration().toMillis()); event.put("executionStarted", Boolean.valueOf(result.executionStarted())); event.put("caseLog", result.caseLogPath() == null ? "" : PathPresentation.displayPath(result.caseLogPath(), projectRoot));
        if (result.executionId() != null) event.put("execId", result.executionId());
        if (!result.executionStarted() && result.actual() != null && !result.actual().isEmpty())
            event.put("notStartedReason", result.actual());
        String json = att.validation.JsonSupport.write(event) + "\n";
        Files.write(runDirectory.resolve("events.jsonl"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static TestResult skipped(TestCase testCase, String message) {
        return new TestResult(testCase.caseId(), testCase.caseName(), ResultStatus.SKIPPED, Duration.ZERO,
                message, "", null, Collections.<ValidationResult>emptyList(), testCase.workbookId(),
                testCase.groupId(), testCase.tags(), null, null, null, null, false);
    }

    private static TestResult error(TestCase testCase, String message, Path outputXml, Duration duration) {
        return error(testCase, message, outputXml, duration, null);
    }
    private static TestResult error(TestCase testCase, String message, Path outputXml, Duration duration,
                                    att.validation.Diagnostic diagnostic) {
        return error(testCase, message, outputXml, duration, diagnostic, testCase.caseId());
    }
    private static TestResult error(TestCase testCase, String message, Path outputXml, Duration duration,
                                   att.validation.Diagnostic diagnostic, String executionId) {
        return error(testCase,message,outputXml,duration,diagnostic,executionId,true);
    }
    static TestResult error(TestCase testCase, String message, Path outputXml, Duration duration,
                            att.validation.Diagnostic diagnostic, String executionId,boolean executionStarted) {
        return new TestResult(testCase.caseId(), testCase.caseName(), ResultStatus.ERROR, duration, "", message, outputXml,
                Collections.<ValidationResult>emptyList(), testCase.workbookId(), testCase.groupId(), testCase.tags(),
                diagnostic, executionId, null, testCase.caseId(), executionStarted);
    }
    private static TestResult invalid(TestCase testCase, String message) {
        return new TestResult(testCase.caseId(), testCase.caseName(), ResultStatus.INVALID, Duration.ZERO,
                "", message, null, Collections.<ValidationResult>emptyList(), testCase.workbookId(),
                testCase.groupId(), testCase.tags(), null, null, null, null, false);
    }
    private static TestResult result(TestCase testCase, ResultStatus status, Duration duration, String expected, String actual, Path log) {
        return result(testCase, status, duration, expected, actual, log, null);
    }
    private static TestResult result(TestCase testCase, ResultStatus status, Duration duration, String expected, String actual, Path log,
                                     att.validation.Diagnostic diagnostic) {
        return new TestResult(testCase.caseId(), testCase.caseName(), status, duration, expected, actual, log,
                Collections.<ValidationResult>emptyList(), testCase.workbookId(), testCase.groupId(), testCase.tags(), diagnostic);
    }

    private static String joinExpected(List<ValidationResult> validations) {
        return joinReportValues(validations, true);
    }

    private static String joinActual(List<ValidationResult> validations) {
        return joinReportValues(validations, false);
    }

    private static String joinReportValues(List<ValidationResult> validations, boolean expected) {
        StringBuilder result = new StringBuilder();
        for (ValidationResult validation : validations) {
            String value = expected ? validation.expected() : validation.actual();
            if (value == null) continue;
            value = value.replace("\r\n", "\n").replace('\r', '\n').trim();
            if (value.isEmpty()) continue;
            if (result.length() > 0) result.append('\n');
            result.append(value);
        }
        return result.toString();
    }
}
