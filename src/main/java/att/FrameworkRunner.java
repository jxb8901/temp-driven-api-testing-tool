/* Author: Jeffrey + ChatGPT */
package att;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.core.ExecutionOptions;
import att.core.FrameworkEngine;
import att.core.RunSummary;
import att.core.PerformanceProfile;
import att.report.PackageDocumentationGenerator;
import att.report.GeneratedOutputCleaner;
import att.report.RunArchiveBuilder;
import att.report.ReportRegenerator;
import att.validation.PackageValidator;
import att.validation.DiagnosticCodes;
import att.snapshot.SnapshotCommand;

import java.nio.file.Path;
import java.nio.file.Paths;

/** V2 command-line entry point. */
public final class FrameworkRunner {
    private FrameworkRunner() {}

    public static void main(String[] args) throws Exception {
        ExecutionOptions options = null;
        try {
            try { options = ExecutionOptions.parse(args); }
            catch (IllegalArgumentException e) {
                throw new att.validation.DiagnosticException(DiagnosticCodes.CLI_INVALID, "Invalid ATT command line",
                        e.getMessage(), null, "argv", null, null, null, null, null,
                        "Run './att.sh help' and correct the command, option, or missing option value.", e);
            }
            if ("help".equals(options.command())) { help(); return; }
            if ("version".equals(options.command())) { System.out.println(Version.DISPLAY); return; }
            Path root = Paths.get("").toAbsolutePath();
            PerformanceProfile profile = new PerformanceProfile(options.profile());
            long profilePhase = profile.begin();
            FrameworkConfig config;
            try { config = new FrameworkConfigLoader().load(options.configPath(), root); }
            catch (att.validation.DiagnosticException e) { throw e; }
            catch (Exception e) {
                throw att.validation.DiagnosticException.wrap(DiagnosticCodes.CONFIG_INVALID,
                        "Unable to load ATT global configuration", e, options.configPath().toString(), "config",
                        "Check that the config file exists, is readable YAML, and conforms to the configured schema version.");
            }
            profile.end("configLoadMs", profilePhase);
            if ("docs".equals(options.command())) {
                System.out.println("Documentation: " + new PackageDocumentationGenerator().generate(root, config));
                return;
            }
            if ("clean".equals(options.command())) {
                new GeneratedOutputCleaner().clean(root, config);
                System.out.println("ATT generated output cleaned.");
                return;
            }
            if ("snapshot".equals(options.command())) {
                for (Path snapshot : new SnapshotCommand().generate(root, config, options)) {
                    System.out.println("Snapshot: " + root.relativize(snapshot.toAbsolutePath().normalize()));
                }
                return;
            }
            if ("build".equals(options.command())) {
                Path output = options.outputDirectory() == null ? root.resolve(config.outputDirectory()) : root.resolve(options.outputDirectory());
                System.out.println("Archive: " + new RunArchiveBuilder().build(root, output.normalize()));
                return;
            }
            if ("report".equals(options.command())) {
                Path output = options.outputDirectory() == null ? root.resolve(config.outputDirectory()) : root.resolve(options.outputDirectory());
                System.out.println("Report: " + new ReportRegenerator().regenerate(output.normalize(), options.runId()));
                return;
            }
            if ("run".equals(options.command())) {
                new FrameworkEngine(root, config).assertRunIdAvailable(options);
                if (options.updateSnapshot()) {
                    java.util.List<Path> updated = new SnapshotCommand().updateForRun(root, config, options);
                    if (!options.quiet()) for (Path snapshot : updated) {
                        String notice = "Snapshot updated: " + root.relativize(snapshot.toAbsolutePath().normalize()).toString().replace('\\', '/');
                        if ("json".equals(options.format())) System.err.println(notice); else System.out.println(notice);
                    }
                }
            }
            profilePhase = profile.begin();
            PackageValidator.ValidationSummary validation = new PackageValidator(root, config).validate(options);
            profile.end("validationMs", profilePhase);
            if ("validate".equals(options.command())) {
                if ("json".equals(options.format())) { String json = validation.toJson(); att.validation.JsonSchemaVerifier.verifyJson(root.resolve("schemas/att-validation-v2.1.schema.json"), json); System.out.println(json); }
                else { System.out.println("Validation " + (validation.valid() ? "PASS: " : "FAIL: ") + validation); printDiagnostics(validation, options, System.out, true); }
                if (!validation.valid()) System.exit(2);
                return;
            }
            if (!validation.valid()) {
                if ("json".equals(options.format())) {
                    String json = validation.toJson();
                    att.validation.JsonSchemaVerifier.verifyJson(root.resolve("schemas/att-validation-v2.1.schema.json"), json);
                    System.out.println(json);
                }
                else {
                    System.err.println("Validation FAIL: " + validation);
                    printDiagnostics(validation, options, System.err, true);
                }
                System.exit(2);
                return;
            }
            if (options.verbose() && !options.quiet() && "human".equals(options.format())) System.out.println("[1/4] V" + Version.PRODUCT + " validation PASS: " + validation);
            printDiagnostics(validation, options, System.out, options.verbose());
            if (options.verbose() && !options.quiet() && "human".equals(options.format())) {
                System.out.println("[2/4] Selected: " + validation.cases + " cases from " + validation.suites + " suites");
                System.out.println("[3/4] Executing cases (verbose Case-log mirroring enabled)");
            }
            RunSummary summary = new FrameworkEngine(root, config).run(options, validation.diagnostics, profile);
            if ("json".equals(options.format())) {
                java.util.Map<String,Object> output = new java.util.LinkedHashMap<String,Object>(); output.put("total", summary.total()); output.put("passed", summary.passed()); output.put("failed", summary.failed()); output.put("error", summary.error()); output.put("skipped", summary.skipped()); output.put("invalid", summary.invalid()); output.put("report", summary.reportPath().toString()); System.out.println(att.validation.JsonSupport.write(output));
            } else if (!options.quiet()) {
                System.out.printf(options.verbose() ? "[4/4] Complete: total=%d, passed=%d, failed=%d, error=%d, skipped=%d, invalid=%d%n" : "Complete: total=%d, passed=%d, failed=%d, error=%d, skipped=%d, invalid=%d%n",
                        summary.total(), summary.passed(), summary.failed(), summary.error(), summary.skipped(), summary.invalid());
                System.out.println("Report: " + summary.reportPath());
            }
            if (summary.exitCode() != 0) System.exit(summary.exitCode());
        } catch (IllegalArgumentException e) {
            att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(e);
            if (typed == null) typed = att.validation.DiagnosticException.wrap(DiagnosticCodes.RUN_FAILED,
                    "ATT command failed", e, null, null, "Review the detailed cause and the referenced configuration or Case evidence.");
            if (options != null && "json".equals(options.format()) && "validate".equals(options.command())) {
                java.util.List<att.validation.Diagnostic> diagnostics = java.util.Collections.singletonList(typed.toDiagnostic()); System.out.println(new PackageValidator.ValidationSummary(options.validationScope(), 0, 0, 0, 0, diagnostics).toJson());
            } else if (options != null && "json".equals(options.format())) {
                java.util.Map<String,Object> error = new java.util.LinkedHashMap<String,Object>(); error.put("valid", false); error.put("code", typed.code()); error.put("message", typed.summary()); error.put("detail", typed.detail()); error.put("suggestion", typed.suggestion()); System.err.println(att.validation.JsonSupport.write(error));
            } else System.err.println(typed.format());
            System.exit(2);
        } catch (Exception e) {
            att.validation.DiagnosticException typed = att.validation.DiagnosticException.wrap(DiagnosticCodes.RUN_FAILED,
                    "Unexpected ATT runtime failure", e, null, null,
                    "Inspect the cause, Case log, and run evidence; rerun with --verbose when safe.");
            System.err.println(typed.format());
            System.exit(3);
        }
    }

    private static void help() {
        System.out.println(Version.DISPLAY + "\nUsage: ./att.sh <command> [options] (Windows: att.bat)\n\nCommands:\n  run       Validate and execute cases\n  validate  Validate package or selected dependencies\n  snapshot  Generate canonical testcase snapshots\n  docs      Generate one self-contained HTML reference\n  report    Regenerate a persisted report\n  build     Archive the latest completed run\n  clean     Delete generated ATT output\n  version   Print version\n  help      Show this help\n\nSelection:\n  --suite <xlsx> | --all | --case <workbookId.groupId.rowCaseId> | --tag <tag>\n  --exclude-tag <tag> --rerun-failed --dry-run --fail-fast --run-id <id> --output-dir <dir>\n  run may use --update-snapshot to explicitly refresh changed selected snapshots before validation\n  snapshot uses --suite <xlsx>, --suite-dir <dir>, or --all\n  --format human|json --ci-output junit,json [--queue|--allow-parallel-runs] [--profile] --quiet --verbose\n  --parallel remains a deprecated alias for --allow-parallel-runs");
    }

    private static void printDiagnostics(PackageValidator.ValidationSummary validation, ExecutionOptions options) {
        printDiagnostics(validation, options, System.out, false);
    }
    private static void printDiagnostics(PackageValidator.ValidationSummary validation, ExecutionOptions options, java.io.PrintStream output) {
        printDiagnostics(validation, options, output, false);
    }
    private static void printDiagnostics(PackageValidator.ValidationSummary validation, ExecutionOptions options, java.io.PrintStream output, boolean leadingBlank) {
        if (options.quiet() || !"human".equals(options.format())) return;
        java.util.List<att.validation.Diagnostic> visible = new java.util.ArrayList<att.validation.Diagnostic>();
        for (att.validation.Diagnostic diagnostic : validation.diagnostics) {
            if ("run".equals(options.command()) && !options.verbose()
                    && diagnostic.severity() == att.validation.Diagnostic.Severity.INFO) continue;
            visible.add(diagnostic);
        }
        if (visible.isEmpty()) return;
        if (leadingBlank) output.println();
        for (int index = 0; index < visible.size(); index++) {
            att.validation.Diagnostic diagnostic = visible.get(index);
            if (index > 0) output.println();
            StringBuilder location = new StringBuilder();
            append(location, "file", diagnostic.file()); append(location, "field", diagnostic.field()); append(location, "sheet", diagnostic.sheet());
            append(location, "row", diagnostic.row()); append(location, "column", diagnostic.column()); append(location, "template", diagnostic.template()); append(location, "action", diagnostic.action());
            String[] message = String.valueOf(diagnostic.message()).split("\\r?\\n", -1);
            output.println("  [" + diagnostic.severity() + "] " + diagnostic.code() + ": " + message[0]);
            for (int line = 1; line < message.length; line++) output.println("    " + message[line]);
            if (location.length() > 0) output.println("    location: " + location);
            if (diagnostic.suggestion() != null) output.println("    suggestion: " + diagnostic.suggestion());
        }
    }
    private static void append(StringBuilder out, String key, Object value) { if (value != null && !String.valueOf(value).isEmpty()) { if (out.length() > 0) out.append(", "); out.append(key).append('=').append(value); } }
}
