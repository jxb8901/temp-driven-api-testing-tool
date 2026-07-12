/* Author: Jeffrey + ChatGPT */
package att.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable global or suite-resolved V2 configuration. */
public final class FrameworkConfig {
    private final Path outputDirectory;
    private final Path reportDirectory;
    private final Path logDirectory;
    private final String environment;
    private final int timeoutMs;
    private final Path templatesRoot;
    private final Path testcasesRoot;
    private final Map<String, ToolConfig> tools;
    private final ReportConfig report;
    private final RunConfig run;
    private final List<SheetGroupConfig> sheetGroups;
    private final String caseIdColumn;
    private final String tagsColumn;
    private final List<DataColumnConfig> dataColumns;
    private final List<StageConfig> stages;
    private final int headerRows;
    private final String xmlNamespaceMode;
    private final String workbookId;

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment,
                           int timeoutMs, Path templatesRoot, Map<String, ToolConfig> tools,
                           ReportConfig report, RunConfig run) {
        this(outputDirectory, reportDirectory, logDirectory, environment, timeoutMs, templatesRoot, tools,
                report, run, null, "", "", null, null, 1, "ignore");
    }

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment,
                           int timeoutMs, Path templatesRoot, Map<String, ToolConfig> tools,
                           ReportConfig report, RunConfig run, List<SheetGroupConfig> sheetGroups,
                           String caseIdColumn, String tagsColumn, List<DataColumnConfig> dataColumns,
                           List<StageConfig> stages) {
        this(outputDirectory, reportDirectory, logDirectory, environment, timeoutMs, templatesRoot, tools,
                report, run, sheetGroups, caseIdColumn, tagsColumn, dataColumns, stages, 1, "ignore");
    }

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment,
                           int timeoutMs, Path templatesRoot, Map<String, ToolConfig> tools,
                           ReportConfig report, RunConfig run, List<SheetGroupConfig> sheetGroups,
                           String caseIdColumn, String tagsColumn, List<DataColumnConfig> dataColumns,
                           List<StageConfig> stages, int headerRows) {
        this(outputDirectory, reportDirectory, logDirectory, environment, timeoutMs, templatesRoot, tools, report, run,
                sheetGroups, caseIdColumn, tagsColumn, dataColumns, stages, headerRows, "ignore");
    }

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment,
                           int timeoutMs, Path templatesRoot, Map<String, ToolConfig> tools,
                           ReportConfig report, RunConfig run, List<SheetGroupConfig> sheetGroups,
                           String caseIdColumn, String tagsColumn, List<DataColumnConfig> dataColumns,
                           List<StageConfig> stages, int headerRows, String xmlNamespaceMode) {
        this(outputDirectory, reportDirectory, logDirectory, environment, timeoutMs, templatesRoot, Paths.get("testcase"), tools, report, run,
                sheetGroups, caseIdColumn, tagsColumn, dataColumns, stages, headerRows, xmlNamespaceMode, "");
    }

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment,
                           int timeoutMs, Path templatesRoot, Map<String, ToolConfig> tools,
                           ReportConfig report, RunConfig run, List<SheetGroupConfig> sheetGroups,
                           String caseIdColumn, String tagsColumn, List<DataColumnConfig> dataColumns,
                           List<StageConfig> stages, int headerRows, String xmlNamespaceMode, String workbookId) {
        this(outputDirectory, reportDirectory, logDirectory, environment, timeoutMs, templatesRoot, Paths.get("testcase"), tools,
                report, run, sheetGroups, caseIdColumn, tagsColumn, dataColumns, stages, headerRows, xmlNamespaceMode, workbookId);
    }

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment,
                           int timeoutMs, Path templatesRoot, Path testcasesRoot, Map<String, ToolConfig> tools,
                           ReportConfig report, RunConfig run, List<SheetGroupConfig> sheetGroups,
                           String caseIdColumn, String tagsColumn, List<DataColumnConfig> dataColumns,
                           List<StageConfig> stages, int headerRows, String xmlNamespaceMode, String workbookId) {
        this.outputDirectory = outputDirectory == null ? Paths.get("output") : outputDirectory;
        this.reportDirectory = reportDirectory == null ? Paths.get("report") : reportDirectory;
        this.logDirectory = logDirectory == null ? Paths.get("logs") : logDirectory;
        this.environment = environment == null ? "SIT" : environment;
        this.timeoutMs = timeoutMs;
        this.templatesRoot = templatesRoot == null ? Paths.get("templates") : templatesRoot;
        this.testcasesRoot = testcasesRoot == null ? Paths.get("testcase") : testcasesRoot;
        this.tools = tools == null ? Collections.<String, ToolConfig>emptyMap() : new LinkedHashMap<String, ToolConfig>(tools);
        this.report = report == null ? defaultReport() : report;
        this.run = run == null ? new RunConfig("timestamp", "yyyyMMdd-HHmmss") : run;
        this.sheetGroups = sheetGroups == null ? Collections.<SheetGroupConfig>emptyList() : new ArrayList<SheetGroupConfig>(sheetGroups);
        this.caseIdColumn = caseIdColumn == null ? "" : caseIdColumn;
        this.tagsColumn = tagsColumn == null ? "" : tagsColumn;
        this.dataColumns = dataColumns == null ? Collections.<DataColumnConfig>emptyList() : new ArrayList<DataColumnConfig>(dataColumns);
        this.stages = stages == null ? Collections.<StageConfig>emptyList() : new ArrayList<StageConfig>(stages);
        if (headerRows < 1) throw new IllegalArgumentException("excel.headerRows must be at least 1");
        this.headerRows = headerRows;
        this.xmlNamespaceMode = xmlNamespaceMode == null ? "ignore" : xmlNamespaceMode;
        this.workbookId = workbookId == null ? "" : workbookId;
        if (!("ignore".equals(this.xmlNamespaceMode) || "preserve".equals(this.xmlNamespaceMode))) throw new IllegalArgumentException("xml.namespaceMode must be ignore or preserve");
    }

    public Path outputDirectory() { return outputDirectory; }
    public Path reportDirectory() { return reportDirectory; }
    public Path logDirectory() { return logDirectory; }
    public String environment() { return environment; }
    public int timeoutMs() { return timeoutMs; }
    public Path templatesRoot() { return templatesRoot; }
    public Path testcasesRoot() { return testcasesRoot; }
    public Map<String, ToolConfig> tools() { return Collections.unmodifiableMap(tools); }
    public ToolConfig tool(String key) { return tools.get(key); }
    public ReportConfig report() { return report; }
    public RunConfig run() { return run; }
    public List<SheetGroupConfig> sheetGroups() { return Collections.unmodifiableList(sheetGroups); }
    public String caseIdColumn() { return caseIdColumn; }
    public String tagsColumn() { return tagsColumn; }
    public List<DataColumnConfig> dataColumns() { return Collections.unmodifiableList(dataColumns); }
    public List<StageConfig> stages() { return Collections.unmodifiableList(stages); }
    public int headerRows() { return headerRows; }
    public String xmlNamespaceMode() { return xmlNamespaceMode; }
    public String workbookId() { return workbookId; }
    public boolean suiteResolved() { return !sheetGroups.isEmpty(); }

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
