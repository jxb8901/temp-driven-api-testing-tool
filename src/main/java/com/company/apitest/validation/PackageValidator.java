/* Author: Jeffrey + ChatGPT */
package com.company.apitest.validation;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.config.SuiteConfigResolver;
import com.company.apitest.config.ToolArgumentConfig;
import com.company.apitest.config.ToolConfig;
import com.company.apitest.core.ExecutionOptions;
import com.company.apitest.core.StageCaseData;
import com.company.apitest.core.TestCase;
import com.company.apitest.excel.ExcelTestSuiteLoader;
import com.company.apitest.template.StageTemplate;
import com.company.apitest.template.StageTemplateLoader;
import com.company.apitest.template.TemplateAction;
import com.company.apitest.template.ToolCallParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Performs V2 package/suite/template/tool validation without running tools. */
public final class PackageValidator {
    private final ToolCallParser callParser = new ToolCallParser();
    private static final Set<String> BUILT_INS = new LinkedHashSet<String>(java.util.Arrays.asList("upper", "lower", "trim", "string", "number", "boolean", "length", "concat", "coalesce"));
    private final Path projectRoot;
    private final FrameworkConfig global;

    public PackageValidator(Path projectRoot, FrameworkConfig global) { this.projectRoot = projectRoot; this.global = global; }

    public ValidationSummary validate(ExecutionOptions options) throws Exception {
        List<Path> suites = suites(options);
        int cases = 0;
        Set<String> templates = new LinkedHashSet<String>();
        SuiteConfigResolver resolver = new SuiteConfigResolver(projectRoot, global);
        StageTemplateLoader loader = new StageTemplateLoader(projectRoot, global.templatesRoot());
        for (Path suite : suites) {
            Path resolved = suite.isAbsolute() ? suite : projectRoot.resolve(suite).normalize();
            FrameworkConfig config = resolver.resolve(resolved);
            List<TestCase> loaded = new ExcelTestSuiteLoader(config).load(resolved);
            for (TestCase testCase : loaded) {
                if (!options.matches(testCase)) continue;
                cases++;
                for (StageCaseData stage : testCase.stages().values()) {
                if (!templates.add(stage.templateName())) continue;
                validateTemplate(loader.load(stage.templateName()), config);
                }
            }
        }
        if (cases == 0) throw new IllegalArgumentException("Case selection is empty");
        return new ValidationSummary(suites.size(), cases, templates.size(), global.tools().size());
    }

    private void validateTemplate(StageTemplate template, FrameworkConfig config) {
        Set<String> actionIds = new LinkedHashSet<String>();
        for (TemplateAction action : template.actions()) {
            if (action.id().contains(".")) throw new IllegalArgumentException("Action ID must not contain '.': " + action.id());
            if (!actionIds.add(action.id())) throw new IllegalArgumentException("Duplicate Action ID: " + action.id());
            String type = action.type().toLowerCase(java.util.Locale.ROOT);
            if (!("render".equals(type) || "tool".equals(type) || "assert".equals(type) || "log".equals(type))) throw new IllegalArgumentException("Unsupported action type: " + action.type());
            if ("render".equals(type)) {
                Path payload = template.directory().resolve(action.payload()).normalize();
                if (!payload.startsWith(template.directory().normalize()) || !Files.isRegularFile(payload)) throw new IllegalArgumentException("Missing/unsafe render payload: " + action.payload());
            }
            if ("tool".equals(type)) validateToolCall(action.call(), config);
        }
    }

    private void validateToolCall(String call, FrameworkConfig config) {
        ToolCallParser.ParsedCall parsed = callParser.parse(call);
        String toolName = parsed.name();
        if (BUILT_INS.contains(toolName.toLowerCase(java.util.Locale.ROOT))) return;
        ToolConfig tool = config.tool(toolName);
        if (tool == null) throw new IllegalArgumentException("Unknown tool in template: " + toolName);
        Set<String> supplied = new LinkedHashSet<String>();
        for (ToolCallParser.Argument argument : parsed.arguments()) {
            if (argument.positional()) throw new IllegalArgumentException("Configured tools require named arguments: " + argument.expression());
            String key = argument.key();
            if (!supplied.add(key)) throw new IllegalArgumentException("Duplicate tool argument: " + key);
            if (!tool.arguments().containsKey(key)) throw new IllegalArgumentException("Unknown argument '" + key + "' for tool " + toolName);
        }
        for (ToolArgumentConfig argument : tool.arguments().values()) if (argument.required() && !supplied.contains(argument.key())) throw new IllegalArgumentException("Missing required argument '" + argument.key() + "' for tool " + toolName);
    }

    private List<Path> suites(ExecutionOptions options) throws Exception {
        List<Path> result = new ArrayList<Path>();
        if (options.suiteDirectory() != null) {
            Path directory = options.suiteDirectory().isAbsolute() ? options.suiteDirectory() : projectRoot.resolve(options.suiteDirectory());
            try (Stream<Path> stream = Files.list(directory)) { stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx")).sorted().forEach(result::add); }
        } else result.addAll(options.suitePaths());
        if (result.isEmpty()) throw new IllegalArgumentException("No Excel suites selected");
        return result;
    }

    public static final class ValidationSummary {
        public final int suites, cases, templates, tools;
        ValidationSummary(int suites, int cases, int templates, int tools) { this.suites = suites; this.cases = cases; this.templates = templates; this.tools = tools; }
        @Override public String toString() { return suites + " suites, " + cases + " cases, " + templates + " templates, " + tools + " tools"; }
    }
}
