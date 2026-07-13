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
import att.exec.ToolInvoker;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.StageTemplateRunner;
import att.template.UnifiedTemplateEngine;
import att.report.HtmlReportGenerator;
import att.report.CiReportWriter;
import att.Version;
import att.validation.JsonSchemaVerifier;
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
        return run(options, Collections.<att.validation.Diagnostic>emptyList());
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

    public RunSummary run(ExecutionOptions options, List<att.validation.Diagnostic> validationDiagnostics) throws Exception {
        Instant runStarted = Instant.now();
        ExecutionPlan plan = buildPlan(options);
        String runId = plan.runId();
        Path outputRoot = plan.outputRoot();
        Path finalRunDirectory = plan.finalRunDirectory();
        RunConcurrencyGuard concurrencyGuard = RunConcurrencyGuard.acquire(outputRoot, options.concurrencyMode(), new Runnable() {
            @Override public void run() {
                if (!options.quiet() && "human".equals(options.format())) System.out.println("ATT run queued: waiting for the active run to complete");
            }
        });
        try {
        if (Files.exists(finalRunDirectory)) {
            throw new IllegalArgumentException("Run ID already exists: " + runId + " (" + finalRunDirectory + "). Choose a different --run-id.");
        }
        Files.createDirectories(outputRoot);
        Path progressRoot = outputRoot.resolve(".in-progress");
        Files.createDirectories(progressRoot);
        Path runDirectory = progressRoot.resolve(runId + "-" + java.util.UUID.randomUUID().toString());
        Files.createDirectories(runDirectory);

        List<TestResult> results = new ArrayList<>();
        Map<ExecutionPlan.Suite, List<TestResult>> suiteReportResults = new LinkedHashMap<ExecutionPlan.Suite, List<TestResult>>();
        boolean stopRun = false;
        verbose(options, "[RUN] id=" + runId + " suites=" + plan.suites().size() + " output=" + portable(outputRoot));

        for (ExecutionPlan.Suite suitePlan : plan.suites()) {
            Path suite = suitePlan.workbook();
            FrameworkConfig suiteConfig = suitePlan.config();
            ToolInvoker toolInvoker = new ToolInvoker(projectRoot, suiteConfig);
            UnifiedTemplateEngine unifiedTemplateEngine = new UnifiedTemplateEngine(toolInvoker);
            StageTemplateRunner templateRunner = new StageTemplateRunner(unifiedTemplateEngine);
            List<TestCase> cases = suitePlan.cases();
            verbose(options, "[SUITE] file=" + portable(resolve(suite)) + " cases=" + cases.size());
            List<TestResult> suiteResults = new ArrayList<TestResult>();
            for (TestCase testCase : cases) {
                verbose(options, "[CASE] id=" + testCase.caseId() + " status=START");
                TestResult result = runCase(testCase, suiteConfig, options, runId, runDirectory, suitePlan, templateRunner);
                verbose(options, "[CASE] id=" + testCase.caseId() + " status=" + result.status() + " durationMs=" + result.duration().toMillis());
                results.add(result);
                suiteResults.add(result);
                if (options.failFast() && (result.status() == ResultStatus.FAIL || result.status() == ResultStatus.ERROR)) {
                    stopRun = true;
                    break;
                }
            }
            suiteReportResults.put(suitePlan, new ArrayList<TestResult>(suiteResults));
            if (stopRun) break;
        }
        if (results.isEmpty()) throw new IllegalArgumentException("Case selection is empty after rerun-failed filtering");
        try (RunIdPublicationGuard publicationGuard = RunIdPublicationGuard.acquire(outputRoot, runId)) {
        String completedRunId = uniqueCompletionRunId(outputRoot, runId);
        if (!completedRunId.equals(runId)) {
            if (!options.quiet() && "human".equals(options.format())) System.out.println("Run ID collision detected at completion; publishing as " + completedRunId);
            finalRunDirectory = IdentifierValidator.strictChild(outputRoot, completedRunId, "Run directory");
            runId = completedRunId;
        }
        for (Map.Entry<ExecutionPlan.Suite, List<TestResult>> entry : suiteReportResults.entrySet()) {
            List<TestResult> publishedSuiteResults = new ArrayList<TestResult>();
            for (TestResult result : entry.getValue()) publishedSuiteResults.add(result.relocate(runDirectory, finalRunDirectory));
            new ExcelReportWriter(entry.getKey().config()).write(resolve(entry.getKey().workbook()), runDirectory, publishedSuiteResults);
        }
        for (TestResult result : results) appendEvent(runDirectory, runId, result);
        Instant runEnded = Instant.now();
        RunSummary summary = new RunSummary(results, runDirectory);
        Path html = new HtmlReportGenerator().generate(runDirectory, runId, summary, runStarted, runEnded);
        List<Map<String, Object>> inputs = inputHashes(options);
        String inputManifestHash = sha256Bytes(new Yaml().dump(inputs).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        new CiReportWriter().write(runDirectory, runId, config.environment(), summary, runStarted, runEnded, config.report().junitCaseLogEmbedThresholdBytes(), inputManifestHash, validationDiagnostics, options.ciOutputs());
        if (options.ciOutputs().contains("json")) JsonSchemaVerifier.verifyJson(projectRoot.resolve("schemas/att-ci-summary-v2.1.schema.json"), runDirectory.resolve("ci/summary.json"));
        if (options.ciOutputs().contains("junit")) {
            javax.xml.validation.SchemaFactory schemaFactory = javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, ""); schemaFactory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            javax.xml.validation.Validator validator = schemaFactory.newSchema(projectRoot.resolve("schemas/att-junit-v2.1.xsd").toFile()).newValidator();
            validator.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, ""); validator.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new javax.xml.transform.stream.StreamSource(runDirectory.resolve("ci/junit.xml").toFile()));
        }
        writeManifest(runDirectory, runId, results, runStarted, runEnded, html, options, inputs, validationDiagnostics);
        rewritePublishedPaths(runDirectory, finalRunDirectory);
        Files.move(runDirectory, finalRunDirectory, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        writeLatest(outputRoot, finalRunDirectory, runId, summary, runEnded);
        List<TestResult> relocated = new ArrayList<TestResult>();
        for (TestResult result : results) relocated.add(result.relocate(runDirectory, finalRunDirectory));
        return new RunSummary(relocated, finalRunDirectory.resolve("report/index.html"));
        }
        } finally {
            concurrencyGuard.close();
        }
    }

    private String uniqueCompletionRunId(Path outputRoot, String requested) {
        if (!Files.exists(IdentifierValidator.strictChild(outputRoot, requested, "Run directory"))) return requested;
        for (int sequence = 2; sequence < Integer.MAX_VALUE; sequence++) {
            String candidate = IdentifierValidator.runId(requested + "-" + sequence);
            if (!Files.exists(IdentifierValidator.strictChild(outputRoot, candidate, "Run directory"))) return candidate;
        }
        throw new IllegalArgumentException("Unable to allocate a unique Run ID for " + requested);
    }

    private TestResult runCase(TestCase testCase, FrameworkConfig suiteConfig, ExecutionOptions options, String runId, Path runDirectory,
                               ExecutionPlan.Suite suitePlan, StageTemplateRunner templateRunner) throws Exception {
        if (!testCase.valid()) {
            return invalid(testCase, testCase.invalidReason());
        }
        Instant started = Instant.now();
        String validatedCaseId = IdentifierValidator.caseId(testCase.workbookId(), testCase.groupId(), testCase.rowCaseId());
        Path caseOutputDir = IdentifierValidator.strictChild(runDirectory, validatedCaseId, "Case directory");
        Files.createDirectories(caseOutputDir);
        Path caseLogPath = caseOutputDir.resolve(testCase.caseId() + "." + runId.replace("-", ".") + ".001.log");
        CaseExecutionLog caseLog = new CaseExecutionLog(caseLogPath);
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, caseOutputDir, runId, runDirectory, caseLogPath);
        context.put("CASE.environment", suiteConfig.environment());
        caseLog.append("CASE", context.caseTree());
        List<ValidationResult> validations = new ArrayList<ValidationResult>();
        try {
            if (!options.dryRun()) {
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
                    verbose(options, "[STAGE] case=" + testCase.caseId() + " stage=" + stage.key() + " template=" + template.name() + " status=START");
                    context.beginStage(stageData, template.name(), template.directory());
                    caseLog.append("STAGE " + stage.key(), "template: " + stageData.templateName());
                    Instant stageStarted = Instant.now();
                    List<ValidationResult> stageResults = templateRunner.execute(stage.key(), template, context, caseLog);
                    ResultStatus stageStatus = aggregate(stageResults);
                    for (ValidationResult actionResult : stageResults) {
                        String detail = actionResult.message() == null || actionResult.message().isEmpty() ? "" : " message=" + actionResult.message();
                        verbose(options, "[ACTION] case=" + testCase.caseId() + " stage=" + stage.key() + " action=" + actionResult.name() + " status=" + actionResult.status() + detail);
                    }
                    context.finishStage(stageStatus.name(), Duration.between(stageStarted, Instant.now()).toMillis());
                    verbose(options, "[STAGE] case=" + testCase.caseId() + " stage=" + stage.key() + " template=" + template.name() + " status=" + stageStatus + " durationMs=" + Duration.between(stageStarted, Instant.now()).toMillis());
                    validations.addAll(stageResults);
                    if (stageStatus == ResultStatus.FAIL || stageStatus == ResultStatus.ERROR || stageStatus == ResultStatus.INVALID) {
                        hasPriorFailure = true;
                        if ("stop".equalsIgnoreCase(stage.onFailure())) stoppedByFailure = true;
                    }
                }
            }
            ResultStatus status = aggregate(validations);
            if (validations.isEmpty()) status = ResultStatus.PASS;
            ResultStatus finalStatus = options.dryRun() ? ResultStatus.SKIPPED : status;
            context.put("CASE.status", finalStatus.name());
            context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            writeCaseTree(caseOutputDir, context);
            return new TestResult(testCase.caseId(), testCase.caseName(), finalStatus,
                    Duration.between(started, Instant.now()), joinExpected(validations), joinActual(validations), caseLogPath, validations,
                    testCase.workbookId(), testCase.groupId(), testCase.tags());
        } catch (Exception e) {
            caseLog.append("ERROR", e.getMessage());
            context.put("CASE.status", ResultStatus.ERROR.name());
            context.put("CASE.error", e.getMessage());
            context.put("CASE.durationMs", Duration.between(started, Instant.now()).toMillis());
            writeCaseTree(caseOutputDir, context);
            return error(testCase, e.getMessage(), caseLogPath, Duration.between(started, Instant.now()));
        }
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

    private ExecutionPlan buildPlan(ExecutionOptions options) throws Exception {
        String runId = runId(options);
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
            List<TestCase> selectedCases = new ArrayList<TestCase>();
            Map<String, StageTemplate> templates = new LinkedHashMap<String, StageTemplate>();
            for (TestCase testCase : new ExcelTestSuiteLoader(suiteConfig).load(workbook)) {
                if (!options.matches(testCase) || (options.rerunFailed() && !failed.contains(testCase.caseId()))) continue;
                Path previousCase = fullCaseIds.put(testCase.caseId(), workbook);
                if (previousCase != null) throw new IllegalArgumentException("Duplicate full Case ID '" + testCase.caseId() + "' in " + previousCase + " and " + workbook);
                selectedCases.add(testCase);
                for (StageCaseData stage : testCase.stages().values()) if (!templates.containsKey(stage.templateName())) templates.put(stage.templateName(), loader.load(stage.templateName()));
            }
            if (!selectedCases.isEmpty()) suitePlans.add(new ExecutionPlan.Suite(workbook, suiteConfig, selectedCases, templates));
        }
        ExecutionPlan plan = new ExecutionPlan(runId, outputRoot, finalRunDirectory, suitePlans);
        if (plan.caseCount() == 0) throw new IllegalArgumentException("Case selection is empty after rerun-failed filtering");
        return plan;
    }

    private void verbose(ExecutionOptions options, String message) {
        if (options.verbose() && !options.quiet() && "human".equals(options.format())) System.out.println(message);
    }

    private String portable(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/') : absolute.getFileName().toString();
    }

    private String runId(ExecutionOptions options) {
        if (options.runId() != null && !options.runId().trim().isEmpty()) {
            return IdentifierValidator.runId(options.runId());
        }
        return IdentifierValidator.runId(DateTimeFormatter.ofPattern(config.run().timestampFormat()).format(LocalDateTime.now()));
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
        Map<String, Object> history = new LinkedHashMap<String, Object>();
        history.put("schemaVersion", Version.RUN_SCHEMA);
        Map<String, Object> att = new LinkedHashMap<String, Object>(); att.put("version", Version.PRODUCT); att.put("buildTime", Version.BUILD_TIME); att.put("gitCommit", Version.GIT_COMMIT); history.put("att", att);
        Map<String, Object> runtime = new LinkedHashMap<String, Object>(); runtime.put("javaVersion", System.getProperty("java.version")); runtime.put("javaVendor", System.getProperty("java.vendor")); runtime.put("osName", System.getProperty("os.name")); runtime.put("osVersion", System.getProperty("os.version")); runtime.put("osArchitecture", System.getProperty("os.arch")); runtime.put("locale", java.util.Locale.getDefault().toLanguageTag()); runtime.put("timezone", java.util.TimeZone.getDefault().getID()); history.put("runtime", runtime);
        Map<String, Object> run = new LinkedHashMap<String, Object>(); run.put("id", runId); run.put("state", "COMPLETE"); run.put("environment", config.environment()); run.put("startedAt", startedAt.toString()); run.put("endedAt", endedAt.toString()); history.put("run", run);
        List<Map<String, Object>> diagnosticMaps = new ArrayList<Map<String, Object>>(); for (att.validation.Diagnostic diagnostic : validationDiagnostics) diagnosticMaps.add(diagnostic.toMap());
        Map<String, Object> validation = new LinkedHashMap<String, Object>(); validation.put("mode", options.validationScope()); validation.put("diagnostics", diagnosticMaps); history.put("validation", validation);
        history.put("inputs", inputs);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        RunSummary runSummary = new RunSummary(results, runDirectory);
        summary.put("total", runSummary.total());
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
            item.put("caseName", result.caseName());
            item.put("workbookId", result.workbookId());
            item.put("groupId", result.groupId());
            item.put("tags", result.tags());
            item.put("status", result.status().name());
            item.put("durationMs", result.duration().toMillis());
            item.put("expected", result.expected());
            item.put("actual", result.actual());
            List<Map<String,Object>> actionResults = new ArrayList<Map<String,Object>>();
            for (ValidationResult action : result.validations()) { Map<String,Object> detail = new LinkedHashMap<String,Object>(); detail.put("stage", action.source()); detail.put("action", action.name()); detail.put("status", action.status().name()); detail.put("expected", action.expected()); detail.put("actual", action.actual()); detail.put("message", action.message()); actionResults.add(detail); }
            item.put("actions", actionResults);
            item.put("caseLog", result.caseLogPath() == null ? "" : runDirectory.relativize(result.caseLogPath()).toString().replace('\\', '/'));
            cases.add(item);
        }
        history.put("cases", cases);
        Map<String, Object> outputs = new LinkedHashMap<String, Object>(); outputs.put("html", "report/index.html"); if (options.ciOutputs().contains("json")) outputs.put("json", "ci/summary.json"); if (options.ciOutputs().contains("junit")) { outputs.put("junit", "ci/junit.xml"); outputs.put("junitHtml", "report/junit.html"); } history.put("outputs", outputs);
        JsonSchemaVerifier.verify(projectRoot.resolve("schemas/att-run-v2.1.schema.json"), history);
        byte[] bytes = new Yaml().dump(history).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(runDirectory.resolve("run.yaml"), bytes);
    }

    private void writeLatest(Path outputRoot, Path runDirectory, String runId, RunSummary summary, Instant endedAt) throws Exception {
        Map<String, Object> pointer = new LinkedHashMap<String, Object>(); pointer.put("schemaVersion", "att-latest-run/v2.1"); pointer.put("runId", runId); pointer.put("runDirectory", outputRoot.relativize(runDirectory).toString().replace('\\', '/')); pointer.put("completedAt", endedAt.toString()); pointer.put("status", summary.status().name()); pointer.put("manifestSha256", sha256(runDirectory.resolve("run.yaml")));
        byte[] bytes = new Yaml().dump(pointer).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path latest = outputRoot.resolve("latest-run.yaml");
        Path temporary = outputRoot.resolve("latest-run.yaml.tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, latest, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private List<Map<String, Object>> inputHashes(ExecutionOptions options) throws Exception {
        List<Map<String, Object>> inputs = new ArrayList<Map<String, Object>>();
        addInput(inputs, "global-config", resolve(options.configPath()));
        for (Path suite : suites(options)) { Path workbook = resolve(suite); addInput(inputs, "workbook", workbook); String name = workbook.getFileName().toString().replaceFirst("(?i)\\.xlsx$", ".yaml"); addInput(inputs, "sidecar", workbook.resolveSibling(name)); }
        Path templates = resolve(config.templatesRoot());
        if (Files.isDirectory(templates)) try (java.util.stream.Stream<Path> files = Files.walk(templates)) { java.util.Iterator<Path> iterator = files.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path)).filter(path -> !path.getFileName().toString().startsWith(".")).sorted().iterator(); while (iterator.hasNext()) addInput(inputs, "template-input", iterator.next()); }
        for (att.config.ToolConfig tool : config.tools().values()) {
            java.util.List<String> command = att.exec.CommandRunner.parseCommand(tool.command()); String first = command.isEmpty() ? "" : command.get(0);
            if (first.startsWith("./") || first.startsWith("../")) addInput(inputs, "tool-executable", projectRoot.resolve(first).normalize());
        }
        Path schemas = projectRoot.resolve("schemas");
        if (Files.isDirectory(schemas)) try (java.util.stream.Stream<Path> files = Files.walk(schemas)) { java.util.Iterator<Path> iterator = files.filter(Files::isRegularFile).sorted().iterator(); while (iterator.hasNext()) addInput(inputs, "schema", iterator.next()); }
        return inputs;
    }
    private void addInput(List<Map<String, Object>> inputs, String kind, Path file) throws Exception { if (!Files.isRegularFile(file)) return; Map<String, Object> item = new LinkedHashMap<String, Object>(); item.put("kind", kind); item.put("path", projectRoot.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')); item.put("sha256", sha256(file)); inputs.add(item); }
    private String sha256(Path file) throws Exception { java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256"); byte[] hash = digest.digest(Files.readAllBytes(file)); StringBuilder out = new StringBuilder(); for (byte value : hash) out.append(String.format("%02x", value & 255)); return out.toString(); }
    private String sha256Bytes(byte[] bytes) throws Exception { java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256"); byte[] hash = digest.digest(bytes); StringBuilder out = new StringBuilder(); for (byte value : hash) out.append(String.format("%02x", value & 255)); return out.toString(); }

    private void rewritePublishedPaths(Path workingDirectory, Path finalDirectory) throws Exception {
        String from = workingDirectory.toString(), to = finalDirectory.toString();
        try (java.util.stream.Stream<Path> files = Files.walk(workingDirectory)) {
            java.util.Iterator<Path> iterator = files.filter(Files::isRegularFile).filter(this::isTextEvidence).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                String content = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                if (content.contains(from)) Files.write(file, content.replace(from, to).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
    private boolean isTextEvidence(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".log") || name.endsWith(".txt");
    }

    private void writeCaseTree(Path caseDirectory, CaseRuntimeContext context) throws Exception {
        Files.write(caseDirectory.resolve("case.yaml"), new Yaml().dump(context.caseTree()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void appendEvent(Path runDirectory, String runId, TestResult result) throws Exception {
        Map<String,Object> event = new LinkedHashMap<String,Object>(); event.put("runId", runId); event.put("caseId", result.caseId()); event.put("status", result.status().name()); event.put("durationMs", result.duration().toMillis()); event.put("caseLog", result.caseLogPath() == null ? "" : result.caseLogPath().toString());
        String json = att.validation.JsonSupport.write(event) + "\n";
        Files.write(runDirectory.resolve("events.jsonl"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static TestResult skipped(TestCase testCase, String message) {
        return result(testCase, ResultStatus.SKIPPED, Duration.ZERO, message, "", null);
    }

    private static TestResult error(TestCase testCase, String message, Path outputXml, Duration duration) {
        return result(testCase, ResultStatus.ERROR, duration, "", message, outputXml);
    }
    private static TestResult invalid(TestCase testCase, String message) {
        return result(testCase, ResultStatus.INVALID, Duration.ZERO, "", message, null);
    }
    private static TestResult result(TestCase testCase, ResultStatus status, Duration duration, String expected, String actual, Path log) {
        return new TestResult(testCase.caseId(), testCase.caseName(), status, duration, expected, actual, log,
                Collections.<ValidationResult>emptyList(), testCase.workbookId(), testCase.groupId(), testCase.tags());
    }

    private static String joinExpected(List<ValidationResult> validations) {
        return validations.stream().map(v -> v.name() + "=" + v.expected()).reduce((a, b) -> a + "; " + b).orElse("");
    }

    private static String joinActual(List<ValidationResult> validations) {
        return validations.stream().map(v -> v.name() + "=" + v.actual()).reduce((a, b) -> a + "; " + b).orElse("");
    }
}
