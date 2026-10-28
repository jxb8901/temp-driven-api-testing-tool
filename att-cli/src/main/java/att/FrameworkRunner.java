/* Author: Jeffrey + ChatGPT */
package att;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.RuntimeConfigPath;
import att.report.PackageDocumentationGenerator;
import att.report.GeneratedOutputCleaner;
import att.report.RunArchiveBuilder;
import att.report.ReportRegenerator;
import att.validation.PackageValidator;
import att.validation.DiagnosticCodes;
import att.api.AttService;
import att.api.DefaultAttService;
import att.api.DebugRequest;
import att.api.DebugResult;
import att.api.SnapshotRequest;
import att.api.SnapshotResult;

import java.nio.file.Path;
import java.nio.file.Paths;

/** V2 command-line entry point. */
public final class FrameworkRunner {
    private FrameworkRunner() {}

    public static void main(String[] args) throws Exception {
        long javaMainEntryNanos = System.nanoTime();
        if (args.length > 0 && "remote".equals(args[0])) {
            int remoteExit = att.remote.RemoteCommand.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            if (remoteExit != 0) System.exit(remoteExit);
            return;
        }
        if (VNextOptions.isVNextRequest(args)) {
            int vNextExit = VNextCommand.run(args);
            if (vNextExit != 0) System.exit(vNextExit);
            return;
        }
        att.core.PathPresentation.configure(false, false);
        CliOptions options = null;
        long argumentParseNanos = 0L;
        Path root = Paths.get("").toAbsolutePath();
        try {
            long argumentParseStartedNanos = System.nanoTime();
            try {
                options = CliOptions.parse(args);
                att.core.PathPresentation.configure(options.substitutePackageRoot(), options.substituteExternalBasename());
                argumentParseNanos = System.nanoTime() - argumentParseStartedNanos;
            }
            catch (IllegalArgumentException e) {
                throw new att.validation.DiagnosticException(DiagnosticCodes.CLI_INVALID, "Invalid ATT command line",
                        e.getMessage(), null, "argv", null, null, null, null, null,
                        "Run './att.sh help' and correct the command, option, or missing option value.", e);
            }
            if ("help".equals(options.command())) { help(); return; }
            if ("version".equals(options.command())) { System.out.println(Version.DISPLAY); return; }
            FrameworkConfig config = null;
            if ("debug".equals(options.command())) {
                if (options.debugTargetType().isEmpty()) {
                    config = loadConfig(options, root);
                    CliDiscovery.printDebug(CliDiscovery.debug(root, config), options.format());
                    return;
                }
                AttService service = new DefaultAttService();
                att.api.DebugStartupMetrics startupMetrics = options.profile()
                        ? new att.api.DebugStartupMetrics(javaMainEntryNanos, argumentParseNanos) : null;
                DebugResult debug = service.debug(new DebugRequest(root, options.configPath(), options.environment(),
                        options.outputDirectory(), null, options.debugTargetType(), options.debugTargetId(),
                        options.debugInput(), options.unsafeFailureDetails(), cliObserver(options, root, startupMetrics),
                        options.debugId(), options.variableOverrides(), options.profile(), startupMetrics));
                if ("json".equals(options.format())) {
                    java.util.Map<String, Object> output = new java.util.LinkedHashMap<String, Object>();
                    output.put("executionId", debug.executionId()); output.put("status", debug.status()); output.put("exitCode", debug.exitCode());
                    output.put("failureDetailMode", options.unsafeFailureDetails() ? "local-unsafe" : "safe-default");
                    output.put("durationMs", debug.durationMs()); output.put("log", cliDisplayPath(debug.paths().get("log"), root));
                    output.put("result", cliDisplayPath(debug.paths().get("result"), root));
                    if (debug.paths().containsKey("performance"))
                        output.put("performance", cliDisplayPath(debug.paths().get("performance"), root));
                    output.put("diagnostics", presentedDiagnostics(debug.diagnostics(), root));
                    System.out.println(att.validation.JsonSupport.write(output));
                } else {
                    String consoleStatus = "INVALID".equals(debug.status()) ? "ERROR" : debug.status();
                    System.out.println(att.console.ExecutionConsoleFormatter.debugComplete(consoleStatus, options.debugTargetType(), options.debugTargetId(), debug.durationMs()));
                    if (!options.quiet()) {
                        System.out.println(att.console.ExecutionConsoleFormatter.labeledPath("Log", cliDisplayPath(debug.paths().get("log"), root)));
                        System.out.println(att.console.ExecutionConsoleFormatter.labeledPath("Result", cliDisplayPath(debug.paths().get("result"), root)));
                        if (debug.paths().containsKey("performance"))
                            System.out.println(att.console.ExecutionConsoleFormatter.labeledPath("Performance", cliDisplayPath(debug.paths().get("performance"), root)));
                    }
                    if (!debug.diagnostics().isEmpty()) System.err.println(att.core.PathPresentation.displayText(
                            att.validation.DiagnosticRenderer.exception(debug.diagnostics().get(0)), root));
                }
                if (debug.exitCode() != 0) System.exit(debug.exitCode());
                return;
            }
            if ("load".equals(options.command())) {
                if (options.listLoadTemplates()) {
                    java.util.Map<String,Object> catalog = LoadTemplateCommand.list(root);
                    if ("json".equals(options.format())) System.out.println(att.validation.JsonSupport.write(catalog));
                    else System.out.println(new org.yaml.snakeyaml.Yaml().dump(catalog));
                    return;
                }
                att.load.LoadScenario composedTemplate = null;
                if (options.loadTemplate() != null) {
                    config = loadConfig(options, root);
                    composedTemplate = LoadTemplateCommand.compose(root, config, options);
                    if (options.previewLoadTemplate()) {
                        java.util.Map<String,Object> projection = new att.debug.DebugEngine(root, options.configPath(), config, new att.exec.IbmMqClientFactory(), true, java.util.Collections.<String>emptyList(), java.util.Collections.<String>emptyList()).projectSafeValue(composedTemplate.toMap());
                        if ("json".equals(options.format())) System.out.println(att.validation.JsonSupport.write(projection));
                        else System.out.println(new org.yaml.snakeyaml.Yaml().dump(projection.get("value")));
                        return;
                    }
                }
                if (options.loadScenario() == null && !options.loadDebug()) {
                    config = loadConfig(options, root);
                    CliDiscovery.printLoad(CliDiscovery.load(root, config), options.format());
                    return;
                }
                String loadId = options.runId();
                final CliOptions loadOptions = options;
                att.load.LoadEventListener progress = new att.load.LoadEventListener() {
                    @Override public void onEvent(att.load.LoadEvent loadEvent) {
                        if (loadOptions.quiet() || !loadOptions.verbose()) return;
                        java.io.PrintStream output = "json".equals(loadOptions.format()) ? System.err : System.out;
                        synchronized (output) { output.println("[LOAD] " + att.validation.JsonSupport.write(loadEvent.toMap(root))); output.flush(); }
                    }
                    @Override public void onPreparationEvent(java.util.Map<String, Object> event) {
                        boolean json = "json".equals(loadOptions.format());
                        if (loadOptions.quiet() || (json && !loadOptions.verbose())) return;
                        java.io.PrintStream output = json ? System.err : System.out;
                        String rendered = loadOptions.verbose() || json
                                ? "[LOAD] " + att.validation.JsonSupport.write(event) : String.valueOf(event.get("console"));
                        synchronized (output) { output.println(rendered); output.flush(); }
                    }
                };
                att.api.LoadResult loadResult = null;
                try {
                    loadResult = new DefaultAttService().load(new att.api.LoadRequest(root, options.configPath(),
                            options.environment(), options.outputDirectory(), loadId, options.loadScenario(),
                            options.loadDebug() ? options.debugTargetType() : null,
                            options.loadDebug() ? options.debugTargetId() : null, options.loadUsers(),
                            options.loadArrivalRate(), options.loadWarmup(), options.loadRampUp(), options.loadDuration(),
                            options.loadRampDown(), options.loadThinkTime(), options.loadMaxConcurrent(),
                            options.loadOverloadPolicy(), options.variableOverrides(), progress, composedTemplate,
                            options.profile()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                if ("json".equals(options.format())) {
                    java.util.Map<String,Object> output = new java.util.LinkedHashMap<String,Object>(loadResult.summary());
                    output.put("executionId", loadResult.executionId());
                    output.put("report", cliDisplayPath(loadResult.paths().get("report"), root));
                    output.put("exitCode", loadResult.exitCode());
                    System.out.println(att.validation.JsonSupport.write(output));
                }
                else {
                    for (String diagnostic : loadDiagnosticLines(loadResult.summary().get("diagnostics")))
                        System.err.println(diagnostic);
                    System.out.println(att.console.ExecutionConsoleFormatter.reportLine(att.core.PathPresentation.displayPath(Paths.get(loadResult.paths().get("report")), root)));
                    if (!options.quiet()) System.out.println(att.console.ExecutionConsoleFormatter.metricsLine(loadResult.summary().get("metrics")));
                }
                if (loadResult.exitCode() != 0) System.exit(loadResult.exitCode());
                return;
            }
            if ("docs".equals(options.command())) {
                config = loadConfig(options, root);
                System.out.println("Documentation: " + new PackageDocumentationGenerator().generate(root, config));
                return;
            }
            if ("clean".equals(options.command())) {
                config = loadConfig(options, root);
                new GeneratedOutputCleaner().clean(root, config);
                System.out.println("ATT generated output cleaned.");
                return;
            }
            if ("snapshot".equals(options.command())) {
                SnapshotResult result = new DefaultAttService().snapshot(new SnapshotRequest(root, options.configPath(),
                        options.environment(), options.suitePaths(), options.suiteDirectory(), options.caseIds(), options.all()));
                @SuppressWarnings("unchecked") java.util.List<String> snapshots = (java.util.List<String>) result.summary().get("paths");
                for (String snapshot : snapshots) System.out.println("Snapshot: " + root.relativize(Paths.get(snapshot).toAbsolutePath().normalize()));
                return;
            }
            if ("build".equals(options.command())) {
                config = loadConfig(options, root);
                Path output = options.outputDirectory() == null ? root.resolve(config.outputDirectory()) : root.resolve(options.outputDirectory());
                System.out.println("Archive: " + new RunArchiveBuilder().build(root, output.normalize()));
                return;
            }
            if ("report".equals(options.command())) {
                config = loadConfig(options, root);
                Path output = options.outputDirectory() == null ? root.resolve(config.outputDirectory()) : root.resolve(options.outputDirectory());
                System.out.println("Report: " + new ReportRegenerator().regenerate(output.normalize(), options.runId()));
                return;
            }
            AttService service = new DefaultAttService();
            if ("validate".equals(options.command())) {
                att.api.ValidateResult result = service.validate(new att.api.ValidateRequest(root, options.configPath(),
                        options.environment(), options.suitePaths(), options.suiteDirectory(), options.caseIds(),
                        options.tags(), options.excludeTags(), options.all(), options.validationScope()));
                java.util.Map<String,Object> counts = result.summary();
                PackageValidator.ValidationSummary validation = new PackageValidator.ValidationSummary(options.validationScope(),
                        ((Number) counts.get("suites")).intValue(), ((Number) counts.get("cases")).intValue(),
                        ((Number) counts.get("templates")).intValue(), ((Number) counts.get("tools")).intValue(), result.diagnostics());
                if ("json".equals(options.format())) {
                    String json = validation.toJson();
                    att.validation.JsonSchemaVerifier.verifyJson(att.validation.SchemaFiles.resolve(root, "att-validation-v2.1.schema.json"), json);
                    System.out.println(json);
                }
                else {
                    System.out.println("Validation " + (validation.valid() ? "PASS: " : "FAIL: ") + validation);
                    printDiagnostics(validation, options, System.out, true);
                }
                if (result.exitCode() != 0) System.exit(result.exitCode());
                return;
            }
            att.api.RunResult run = service.run(new att.api.RunRequest(root, options.configPath(), options.environment(),
                    options.outputDirectory(), options.runId(), options.suitePaths(), options.suiteDirectory(),
                    options.caseIds(), options.tags(), options.excludeTags(), options.all(), options.rerunFailed(),
                    options.dryRun(), options.failFast(), options.ciOutputs(), options.concurrencyMode(), options.profile(),
                    cliObserver(options, root), options.updateSnapshot()));
            if ("json".equals(options.format())) {
                if ("INVALID".equals(run.status())) {
                    PackageValidator.ValidationSummary invalid = new PackageValidator.ValidationSummary(options.validationScope(),
                            number(run.summary(), "suites"), number(run.summary(), "cases"), number(run.summary(), "templates"),
                            number(run.summary(), "tools"), run.diagnostics());
                    System.out.println(invalid.toJson());
                } else {
                    System.out.println(runJson(run, root));
                }
            } else if (run.exitCode() == 2) {
                System.err.println("Validation FAIL");
                PackageValidator.ValidationSummary invalid = new PackageValidator.ValidationSummary(options.validationScope(),
                        number(run.summary(), "suites"), number(run.summary(), "cases"), number(run.summary(), "templates"),
                        number(run.summary(), "tools"), run.diagnostics());
                printDiagnostics(invalid, options, System.err, true);
                System.exit(2);
                return;
            } else if (!options.quiet()) {
                java.util.Map<String,Object> summary = run.summary();
                System.out.println(att.console.ExecutionConsoleFormatter.runComplete(options.verbose(),
                        summary.get("total"), summary.get("passed"), summary.get("failed"), summary.get("error"), summary.get("skipped"), summary.get("invalid")));
                if (run.paths().get("report") != null) System.out.println(att.console.ExecutionConsoleFormatter.reportLine(att.core.PathPresentation.displayPath(Paths.get(run.paths().get("report")), root)));
            } else {
                java.util.Map<String,Object> summary = run.summary();
                System.out.println(att.console.ExecutionConsoleFormatter.runComplete(false,
                        summary.get("total"), summary.get("passed"), summary.get("failed"), summary.get("error"), summary.get("skipped"), summary.get("invalid")));
                if (run.paths().get("report") != null) System.out.println(att.console.ExecutionConsoleFormatter.reportLine(att.core.PathPresentation.displayPath(Paths.get(run.paths().get("report")), root)));
                for (java.util.Map<String,Object> failure : run.caseFailures()) {
                    System.err.println(att.console.ExecutionConsoleFormatter.quietFailure(failure.get("status"), failure.get("caseId"),
                            cliDisplayPath((String) failure.get("caseLog"), root)));
                }
            }
            if (run.exitCode() != 0) System.exit(run.exitCode());
        } catch (IllegalArgumentException e) {
            att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(e);
            if (typed == null) typed = att.validation.DiagnosticException.wrap(DiagnosticCodes.RUN_FAILED,
                    "ATT command failed", e, null, null, "Review the detailed cause and the referenced configuration or Case evidence.");
            if (options != null && "json".equals(options.format()) && "validate".equals(options.command())) {
                java.util.List<att.validation.Diagnostic> diagnostics = java.util.Collections.singletonList(typed.toDiagnostic()); System.out.println(new PackageValidator.ValidationSummary(options.validationScope(), 0, 0, 0, 0, diagnostics).toJson());
            } else if (options != null && "json".equals(options.format())) {
                System.err.println(presentedDiagnosticJson(typed, root));
            } else System.err.println(att.core.PathPresentation.displayText(typed.format(), root));
            System.exit(2);
        } catch (Exception e) {
            att.validation.DiagnosticException typed = att.validation.DiagnosticException.wrap(DiagnosticCodes.RUN_FAILED,
                    "Unexpected ATT runtime failure", e, null, null,
                    "Inspect the cause, Case log, and run evidence; use --quiet to suppress live progress.");
            if (options != null && "json".equals(options.format()))
                System.err.println(presentedDiagnosticJson(typed, root));
            else System.err.println(att.core.PathPresentation.displayText(typed.format(), root));
            System.exit(3);
        }
    }

    @SuppressWarnings("unchecked")
    private static String presentedDiagnosticJson(att.validation.DiagnosticException diagnostic, Path root) {
        java.util.Map<String, Object> displayed = (java.util.Map<String, Object>) att.core.PathPresentation.displayStructure(
                diagnostic.toDiagnostic().toMap(), root);
        java.util.Map<String, Object> output = new java.util.LinkedHashMap<String, Object>();
        output.put("valid", false);
        output.putAll(displayed);
        return att.validation.JsonSupport.write(output);
    }

    private static String cliDisplayPath(String value, Path root) {
        return value == null ? null : att.core.PathPresentation.displayPath(Paths.get(value), root);
    }

    static java.util.List<String> loadDiagnosticLines(Object value) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        if (!(value instanceof java.util.Map)) return lines;
        java.util.Map<?,?> diagnostics = (java.util.Map<?,?>) value;
        Object primary = diagnostics.get("primaryError");
        if (primary instanceof java.util.Map) {
            String line = loadDiagnosticLine("LOAD ERROR", (java.util.Map<?,?>) primary);
            if (line != null) lines.add(line);
        }
        appendLoadDiagnosticLines(lines, "LOAD NOTICE", diagnostics.get("secondaryNotices"));
        appendLoadDiagnosticLines(lines, "LOAD TRANSPORT", diagnostics.get("transportWarnings"));
        return lines;
    }

    private static void appendLoadDiagnosticLines(java.util.List<String> lines, String label, Object value) {
        if (!(value instanceof Iterable)) return;
        for (Object item : (Iterable<?>) value) {
            if (lines.size() >= 5) return;
            if (item instanceof java.util.Map) {
                String line = loadDiagnosticLine(label, (java.util.Map<?,?>) item);
                if (line != null) lines.add(line);
            }
        }
    }

    private static String loadDiagnosticLine(String label, java.util.Map<?,?> diagnostic) {
        Object codeValue = diagnostic.get("code");
        if (!(codeValue instanceof String) || !((String) codeValue).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) return null;
        StringBuilder line = new StringBuilder(label).append(" [").append(codeValue).append(']');
        Object limitName = diagnostic.get("limitName");
        if (limitName instanceof String && ((String) limitName).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))
            line.append(" limit=").append(limitName);
        Object observed = diagnostic.containsKey("observed") ? diagnostic.get("observed") : diagnostic.get("actual");
        if (observed instanceof Number) line.append(" usage=").append(((Number) observed).longValue());
        Object configured = diagnostic.containsKey("configuredLimit") ? diagnostic.get("configuredLimit") : diagnostic.get("limit");
        if (configured instanceof Number) line.append(" configuredLimit=").append(((Number) configured).longValue());
        Object outcome = diagnostic.get("outcome");
        if (outcome instanceof String && ((String) outcome).matches("[A-Z_]{1,64}")) line.append(" outcome=").append(outcome);
        return line.toString();
    }

    private static int number(java.util.Map<String,Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    static String runJson(att.api.RunResult run, Path root) {
        java.util.Map<String,Object> output = new java.util.LinkedHashMap<String,Object>();
        java.util.Map<String,Object> summary = run.summary();
        for (String key : new String[]{"total", "passed", "failed", "error", "skipped", "invalid"})
            output.put(key, summary.get(key));
        output.put("report", cliDisplayPath(run.paths().get("report"), root));
        return att.validation.JsonSupport.write(output);
    }

    private static att.api.ExecutionEventListener cliObserver(final CliOptions options, final Path root) {
        return cliObserver(options, root, null);
    }

    private static att.api.ExecutionEventListener cliObserver(final CliOptions options, final Path root,
                                                               final att.api.DebugStartupMetrics startupMetrics) {
        return event -> {
            if ((options.quiet() && event.type() != att.api.ExecutionEvent.Type.WARNING)
                    || (!options.verbose() && event.type() != att.api.ExecutionEvent.Type.WARNING
                    && event.type() != att.api.ExecutionEvent.Type.STATUS)) return;
            String message = renderExecutionEvent(event, root);
            if (message == null) return;
            java.io.PrintStream output = "json".equals(options.format()) ? System.err : System.out;
            synchronized (output) {
                output.println(message);
                output.flush();
                if (startupMetrics != null) startupMetrics.markFirstConsoleEvent();
            }
        };
    }

    private static String renderExecutionEvent(att.api.ExecutionEvent event, Path root) {
        return att.console.ExecutionConsoleFormatter.format(event.type().name(), event.runId(), event.caseId(),
                event.stage(), event.action(), event.status(), event.durationMs(), event.message(), event.data(),
                att.console.ExecutionConsoleFormatter.Options.cli(value -> att.core.PathPresentation.displayStructure(value, root)));
    }

    private static FrameworkConfig loadConfig(CliOptions options, Path root) throws Exception {
        Path config = RuntimeConfigPath.resolve(options.configPath(), root);
        try { return new FrameworkConfigLoader().load(config, RuntimeConfigPath.root(config), root, options.environment()); }
        catch (att.validation.DiagnosticException e) { throw e; }
        catch (Exception e) {
            throw att.validation.DiagnosticException.wrap(DiagnosticCodes.CONFIG_INVALID,
                    "Unable to load ATT global configuration", e, options.configPath().toString(), "config",
                    "Check that the config file exists, is readable YAML, and conforms to the configured schema version.");
        }
    }

    private static java.util.List<java.util.Map<String, Object>> presentedDiagnostics(
            java.util.List<att.validation.Diagnostic> diagnostics, Path root) {
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (att.validation.Diagnostic diagnostic : diagnostics) {
            @SuppressWarnings("unchecked") java.util.Map<String, Object> displayed =
                    (java.util.Map<String, Object>) att.core.PathPresentation.displayStructure(diagnostic.toMap(), root);
            result.add(displayed);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> castMap(Object value) {
        return value instanceof java.util.Map ? (java.util.Map<String, Object>) value : java.util.Collections.<String, Object>emptyMap();
    }

    private static void help() {
        System.out.println("Debug: ./att.sh debug [template|flow|tool <id>] [--config <file>] [--env <name>] [--debug-id <id>] [--input <debug.yaml>] [--set <input|arg|vars>.<path>=<yaml-value>] [--unsafe-failure-details] [--output-dir <dir>] [--profile] [--format human|json] [--quiet|--verbose]");
        System.out.println("Debug discovery: ./att.sh debug [--format human|json] lists runnable targets and existing sidecars; discovery does not execute targets.");
        System.out.println("Load: ./att.sh load [<scenario.yaml> | --debug template|flow|tool <id>] [--input <debug.yaml>] [--set <input|arg|vars>.<path>=<yaml-value>] [--config <file>] [--env <name>] [--run-id <id>] [--users <n>|--arrival-rate <n/s>] [--duration <duration>] [--max-concurrent <n>] [--format human|json]");
        System.out.println("Load discovery: ./att.sh load [--format human|json] lists valid scenarios; load/load.yaml supplies the optional Quick Load policy.");
        System.out.println("--set namespaces: input.* -> EXEC.INPUT; arg.* -> Tool arguments; vars.* -> Template/Flow EXEC.VARS. Values use safe YAML types.");
        System.out.println("Path display: absolute paths are shown by default; --substitute-package-root and --substitute-external-basename independently enable $ATT_PACKAGE_ROOT and $EXTERNAL/<basename> aliases.");
        System.out.println("Load Templates (also att.bat): load --list-templates [--format json]; load --debug <type> <id> --load-template <builtin:single-vu|builtin:single-tps|path> [--preview]; load --debug --load-template <path|builtin:mode> --bindings <file> [--preview] [--format json]");
        System.out.println("Template preview/list starts no traffic. Multi-target input, set, users, arrival-rate and other unscoped pacing overrides are rejected; put scoped values in the template/bindings descriptor.");
        System.out.println("Load options: --ramp-up <duration> --warmup <duration> --ramp-down <duration> --think-time <duration> --overload-policy drop --output-dir <dir> --profile --quiet|--verbose");
        System.out.println("Load output: <output-dir>/load/<runId>/load-summary.json|yaml and report/index.html; exit codes PASS=0, threshold FAIL=1, validation=2, runtime=3");
        System.out.println("Environment profiles: use --env <name> with run, validate, debug, or load; --config selects the common config.");
        System.out.println("Profile output: run/load write runtime diagnostics; debug <type> <id> --profile records startup and Debug phases.");
        System.out.println(Version.DISPLAY + "\nUsage: ./att.sh <command> [options] (Windows: att.bat)\n\nCommands:\n  run       Validate and execute cases\n  validate  Validate package or selected dependencies\n  snapshot  Generate canonical testcase snapshots\n  debug     Execute one Template, Flow, or Tool\n  load      Execute a closed or fixed-arrival-rate load scenario\n  remote    Submit and manage jobs through ATT Server\n  docs      Generate one self-contained HTML reference\n  report    Regenerate a persisted report\n  build     Archive the latest completed run\n  clean     Delete generated ATT output\n  version   Print version\n  help      Show this help\n\nSelection:\n  --suite <xlsx> | --all | --case <workbookId.groupId.rowCaseId> | --tag <tag>\n  --exclude-tag <tag> --rerun-failed --dry-run --fail-fast --run-id <id> --output-dir <dir>\n  run, debug, and load stream bounded progress by default; --quiet keeps the final summary; --verbose remains accepted\n  run may use --update-snapshot to explicitly refresh changed selected snapshots before validation\n  snapshot defaults to --all when no selector is supplied; --all remains accepted\n  --format human|json --ci-output junit,json [--queue|--allow-parallel-runs] [--profile] --quiet --verbose\n  --parallel remains a deprecated alias for --allow-parallel-runs");
    }

    private static void printDiagnostics(PackageValidator.ValidationSummary validation, CliOptions options) {
        printDiagnostics(validation, options, System.out, false);
    }
    private static void printDiagnostics(PackageValidator.ValidationSummary validation, CliOptions options, java.io.PrintStream output) {
        printDiagnostics(validation, options, output, false);
    }
    private static void printDiagnostics(PackageValidator.ValidationSummary validation, CliOptions options, java.io.PrintStream output, boolean leadingBlank) {
        if (!"human".equals(options.format())) return;
        java.util.List<att.validation.Diagnostic> visible = new java.util.ArrayList<att.validation.Diagnostic>();
        for (att.validation.Diagnostic diagnostic : validation.diagnostics) {
            if (options.quiet() && diagnostic.severity() != att.validation.Diagnostic.Severity.ERROR) continue;
            if ("run".equals(options.command()) && !options.verbose()
                    && diagnostic.severity() == att.validation.Diagnostic.Severity.INFO) continue;
            visible.add(diagnostic);
        }
        if (visible.isEmpty()) return;
        if (leadingBlank) output.println();
        for (int index = 0; index < visible.size(); index++) {
            att.validation.Diagnostic diagnostic = visible.get(index);
            if (index > 0) output.println();
            output.println(att.validation.DiagnosticRenderer.validation(diagnostic));
        }
    }
    private static void append(StringBuilder out, String key, Object value) { if (value != null && !String.valueOf(value).isEmpty()) { if (out.length() > 0) out.append(", "); out.append(key).append('=').append(value); } }
}
