/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable global runtime configuration loaded from config/config.yaml.
 */
public class FrameworkConfig {
    private final Path outputDirectory;
    private final Path reportDirectory;
    private final Path logDirectory;
    private final String environment;
    private final int timeoutSeconds;
    private final String testcaseSheet;
    private final Map<String, String> testcaseColumns;
    private final List<StageConfig> stages;
    private final Path templatesRoot;
    private final String defaultTestCaseTemplate;
    private final Map<String, ToolConfig> tools;
    private final ReportConfig report;
    private final RunConfig run;

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment, int timeoutSeconds,
                           String testcaseSheet, Map<String, String> testcaseColumns, Map<String, ToolConfig> tools) {
        this(outputDirectory, reportDirectory, logDirectory, environment, timeoutSeconds, testcaseSheet, testcaseColumns,
                defaultStages(), Paths.get("templates"), "", tools, defaultReport(), new RunConfig("timestamp", "yyyyMMdd-HHmmss"));
    }

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment, int timeoutSeconds,
                           String testcaseSheet, Map<String, String> testcaseColumns, List<StageConfig> stages,
                           Path templatesRoot, Map<String, ToolConfig> tools, ReportConfig report, RunConfig run) {
        this(outputDirectory, reportDirectory, logDirectory, environment, timeoutSeconds, testcaseSheet, testcaseColumns,
                stages, templatesRoot, "", tools, report, run);
    }

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment, int timeoutSeconds,
                           String testcaseSheet, Map<String, String> testcaseColumns, List<StageConfig> stages,
                           Path templatesRoot, String defaultTestCaseTemplate, Map<String, ToolConfig> tools, ReportConfig report, RunConfig run) {
        this.outputDirectory = outputDirectory;
        this.reportDirectory = reportDirectory;
        this.logDirectory = logDirectory;
        this.environment = environment;
        this.timeoutSeconds = timeoutSeconds;
        this.testcaseSheet = testcaseSheet;
        this.testcaseColumns = testcaseColumns == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(testcaseColumns);
        this.stages = stages == null ? defaultStages() : new ArrayList<StageConfig>(stages);
        this.templatesRoot = templatesRoot == null ? Paths.get("templates") : templatesRoot;
        this.defaultTestCaseTemplate = defaultTestCaseTemplate == null ? "" : defaultTestCaseTemplate;
        this.tools = tools == null ? Collections.<String, ToolConfig>emptyMap() : new LinkedHashMap<String, ToolConfig>(tools);
        this.report = report == null ? defaultReport() : report;
        this.run = run == null ? new RunConfig("timestamp", "yyyyMMdd-HHmmss") : run;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public Path reportDirectory() {
        return reportDirectory;
    }

    public Path logDirectory() {
        return logDirectory;
    }

    public String environment() {
        return environment;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public String testcaseSheet() { return testcaseSheet; }
    public Map<String, String> testcaseColumns() { return testcaseColumns; }
    public List<StageConfig> stages() { return stages; }
    public Path templatesRoot() { return templatesRoot; }
    public String defaultTestCaseTemplate() { return defaultTestCaseTemplate; }
    public Map<String, ToolConfig> tools() { return tools; }
    public ToolConfig tool(String key) { return tools.get(key); }
    public ReportConfig report() { return report; }
    public RunConfig run() { return run; }

    private static List<StageConfig> defaultStages() {
        List<StageConfig> stages = new ArrayList<StageConfig>();
        stages.add(new StageConfig("stagePre", "Pre", false, "stop", "normal"));
        stages.add(new StageConfig("stageMain", "Main", true, "stop", "normal"));
        stages.add(new StageConfig("stagePost", "Post", false, "stop", "normal"));
        return stages;
    }

    private static ReportConfig defaultReport() {
        Map<String, String> columns = new LinkedHashMap<String, String>();
        columns.put("result", "Test Result");
        columns.put("durationMs", "Duration(ms)");
        columns.put("actualResult", "Actual Result");
        columns.put("caseLog", "Case Log");
        columns.put("runTime", "Run Time");
        return new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", columns);
    }
}
