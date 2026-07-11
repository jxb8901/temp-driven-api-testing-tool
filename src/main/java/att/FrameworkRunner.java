/* Author: Jeffrey + ChatGPT */
package att;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.core.ExecutionOptions;
import att.core.FrameworkEngine;
import att.core.RunSummary;
import att.report.PackageDocumentationGenerator;
import att.report.RunArchiveBuilder;
import att.report.ReportRegenerator;
import att.validation.PackageValidator;

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
            if ("version".equals(options.command())) { System.out.println("ATT V2.0"); return; }
            Path root = Paths.get("").toAbsolutePath();
            FrameworkConfig config = new FrameworkConfigLoader().load(options.configPath());
            if ("docs".equals(options.command())) {
                System.out.println("Documentation: " + new PackageDocumentationGenerator().generate(root, config, options.singlePage()));
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
            PackageValidator.ValidationSummary validation = new PackageValidator(root, config).validate(options);
            if ("validate".equals(options.command())) {
                if ("json".equals(options.format())) System.out.printf("{\"valid\":true,\"suites\":%d,\"cases\":%d,\"templates\":%d,\"tools\":%d}%n", validation.suites, validation.cases, validation.templates, validation.tools);
                else System.out.println("V2 validation PASS: " + validation);
                return;
            }
            if (!options.quiet() && "human".equals(options.format())) System.out.println("[1/4] V2 validation PASS: " + validation);
            RunSummary summary = new FrameworkEngine(root, config).run(options);
            if ("json".equals(options.format())) {
                System.out.printf("{\"total\":%d,\"passed\":%d,\"failed\":%d,\"error\":%d,\"skipped\":%d,\"invalid\":%d,\"report\":\"%s\"}%n",
                        summary.total(), summary.passed(), summary.failed(), summary.error(), summary.skipped(), summary.invalid(), summary.reportPath().toString().replace("\\", "\\\\").replace("\"", "\\\""));
            } else {
                System.out.printf("[4/4] Complete: total=%d, passed=%d, failed=%d, error=%d, skipped=%d, invalid=%d%n",
                        summary.total(), summary.passed(), summary.failed(), summary.error(), summary.skipped(), summary.invalid());
                System.out.println("Report: " + summary.reportPath());
            }
            if (summary.failed() > 0) System.exit(1);
            if (summary.error() > 0) System.exit(3);
        } catch (IllegalArgumentException e) {
            String code = code(e.getMessage());
            if (options != null && "json".equals(options.format())) {
                System.err.println("{\"valid\":false,\"code\":\"" + code + "\",\"message\":\"" + json(e.getMessage()) + "\"}");
            } else System.err.println(code + ": " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("ATT-RUN-001: " + e.getMessage());
            System.exit(3);
        }
    }

    private static void help() {
        System.out.println("ATT V2.0\nUsage: ./att.sh <command> [options]\n\nCommands:\n  run       Validate and execute cases\n  validate  Validate selected suites without executing tools\n  docs      Generate HTML testcase/template/tool reference\n  report    Regenerate a persisted report\n  build     Archive the latest completed run\n  version   Print version\n  help      Show this help\n\nSelection:\n  --suite <xlsx> | --all | --case <group.caseId> | --tag <tag>\n  --exclude-tag <tag> --dry-run --fail-fast --run-id <id> --output-dir <dir>\n  --format human|json --quiet --verbose --single-page");
    }

    private static String code(String message) {
        String value = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("tool") || value.contains("argument") || value.contains("argv")) return "ATT-TOOL-001";
        if (value.contains("template") || value.contains("action") || value.contains("payload")) return "ATT-TPL-001";
        if (value.contains("sheet") || value.contains("column") || value.contains("case id") || value.contains("sidecar") || value.contains("stage")) return "ATT-TC-001";
        return "ATT-CFG-001";
    }
    private static String json(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
}
