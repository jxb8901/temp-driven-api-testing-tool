/* Author: Jeffrey + ChatGPT */
package att;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.core.ExecutionOptions;
import att.core.FrameworkEngine;
import att.core.RunSummary;
import att.report.PackageDocumentationGenerator;
import att.report.GeneratedOutputCleaner;
import att.report.RunArchiveBuilder;
import att.report.ReportRegenerator;
import att.validation.PackageValidator;
import att.validation.DiagnosticCodes;

import java.nio.file.Path;
import java.nio.file.Paths;

/** V2 command-line entry point. */
public final class FrameworkRunner {
    private FrameworkRunner() {}

    public static void main(String[] args) throws Exception {
        ExecutionOptions options = null;
        try {
            options = ExecutionOptions.parse(args);
            if ("help".equals(options.command())) { help(); return; }
            if ("version".equals(options.command())) { System.out.println(Version.DISPLAY); return; }
            Path root = Paths.get("").toAbsolutePath();
            FrameworkConfig config = new FrameworkConfigLoader().load(options.configPath());
            if ("docs".equals(options.command())) {
                System.out.println("Documentation: " + new PackageDocumentationGenerator().generate(root, config));
                return;
            }
            if ("clean".equals(options.command())) {
                new GeneratedOutputCleaner().clean(root, config);
                System.out.println("ATT generated output cleaned.");
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
            if ("run".equals(options.command())) new FrameworkEngine(root, config).assertRunIdAvailable(options);
            PackageValidator.ValidationSummary validation = new PackageValidator(root, config).validate(options);
            if ("validate".equals(options.command())) {
                if ("json".equals(options.format())) { String json = validation.toJson(); att.validation.JsonSchemaVerifier.verifyJson(root.resolve("schemas/att-validation-v2.1.schema.json"), json); System.out.println(json); }
                else { System.out.println("V2.1 validation " + (validation.valid() ? "PASS: " : "FAIL: ") + validation); printDiagnostics(validation, options); }
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
                    System.err.println("V2.1 validation FAIL: " + validation);
                    printDiagnostics(validation, options, System.err);
                }
                System.exit(2);
                return;
            }
            printDiagnostics(validation, options);
            if (!options.quiet() && "human".equals(options.format())) System.out.println("[1/4] V" + Version.PRODUCT + " validation PASS: " + validation);
            if (!options.quiet() && "human".equals(options.format())) {
                System.out.println("[2/4] Selected: " + validation.cases + " cases from " + validation.suites + " suites");
                System.out.println("[3/4] Executing cases" + (options.verbose() ? " (verbose)" : ""));
            }
            RunSummary summary = new FrameworkEngine(root, config).run(options, validation.diagnostics);
            if ("json".equals(options.format())) {
                java.util.Map<String,Object> output = new java.util.LinkedHashMap<String,Object>(); output.put("total", summary.total()); output.put("passed", summary.passed()); output.put("failed", summary.failed()); output.put("error", summary.error()); output.put("skipped", summary.skipped()); output.put("invalid", summary.invalid()); output.put("report", summary.reportPath().toString()); System.out.println(att.validation.JsonSupport.write(output));
            } else if (!options.quiet()) {
                System.out.printf("[4/4] Complete: total=%d, passed=%d, failed=%d, error=%d, skipped=%d, invalid=%d%n",
                        summary.total(), summary.passed(), summary.failed(), summary.error(), summary.skipped(), summary.invalid());
                System.out.println("Report: " + summary.reportPath());
            }
            if (summary.exitCode() != 0) System.exit(summary.exitCode());
        } catch (IllegalArgumentException e) {
            String code = code(e.getMessage());
            if (options != null && "json".equals(options.format()) && "validate".equals(options.command())) {
                att.validation.Diagnostic diagnostic = new att.validation.Diagnostic(DiagnosticCodes.CONFIG_INVALID, att.validation.Diagnostic.Severity.ERROR, e.getMessage(), null, null, null, null, null, null); java.util.List<att.validation.Diagnostic> diagnostics = java.util.Collections.singletonList(diagnostic); System.out.println(new PackageValidator.ValidationSummary(options.validationScope(), 0, 0, 0, 0, diagnostics).toJson());
            } else if (options != null && "json".equals(options.format())) {
                java.util.Map<String,Object> error = new java.util.LinkedHashMap<String,Object>(); error.put("valid", false); error.put("code", code); error.put("message", e.getMessage()); System.err.println(att.validation.JsonSupport.write(error));
            } else System.err.println(code + ": " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("ATT-RUN-001: " + e.getMessage());
            System.exit(3);
        }
    }

    private static void help() {
        System.out.println(Version.DISPLAY + "\nUsage: ./att.sh <command> [options]\n\nCommands:\n  run       Validate and execute cases\n  validate  Validate package or selected dependencies\n  docs      Generate one self-contained HTML reference\n  report    Regenerate a persisted report\n  build     Archive the latest completed run\n  clean     Delete generated ATT output\n  version   Print version\n  help      Show this help\n\nSelection:\n  --suite <xlsx> | --all | --case <workbook.sheet.caseId> | --tag <tag>\n  --exclude-tag <tag> --dry-run --fail-fast --run-id <id> --output-dir <dir>\n  --format human|json --ci-output junit,json [--queue|--parallel] --quiet --verbose");
    }

    private static String code(String message) {
        String value = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("run id") || value.contains("another att run") || value.contains("run directory")) return DiagnosticCodes.RUN_FAILED;
        if (value.contains("tool") || value.contains("argument") || value.contains("argv")) return "ATT-TOOL-001";
        if (value.contains("template") || value.contains("action") || value.contains("payload")) return "ATT-TPL-001";
        if (value.contains("sheet") || value.contains("column") || value.contains("case id") || value.contains("sidecar") || value.contains("stage")) return "ATT-TC-001";
        return "ATT-CFG-001";
    }
    private static void printDiagnostics(PackageValidator.ValidationSummary validation, ExecutionOptions options) {
        printDiagnostics(validation, options, System.out);
    }
    private static void printDiagnostics(PackageValidator.ValidationSummary validation, ExecutionOptions options, java.io.PrintStream output) {
        if (options.quiet() || !"human".equals(options.format())) return;
        for (att.validation.Diagnostic diagnostic : validation.diagnostics) {
            StringBuilder location = new StringBuilder();
            append(location, "file", diagnostic.file()); append(location, "field", diagnostic.field()); append(location, "sheet", diagnostic.sheet());
            append(location, "row", diagnostic.row()); append(location, "column", diagnostic.column()); append(location, "template", diagnostic.template()); append(location, "action", diagnostic.action());
            output.println("[" + diagnostic.severity() + "] " + diagnostic.code() + ": " + diagnostic.message() + (location.length() == 0 ? "" : " [" + location + "]"));
            if (diagnostic.suggestion() != null) output.println("  suggestion: " + diagnostic.suggestion());
        }
    }
    private static void append(StringBuilder out, String key, Object value) { if (value != null && !String.valueOf(value).isEmpty()) { if (out.length() > 0) out.append(", "); out.append(key).append('=').append(value); } }
}
