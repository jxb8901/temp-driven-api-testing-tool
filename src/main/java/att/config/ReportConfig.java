/*
 * Author: Jeffrey + ChatGPT
 */

package att.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configures V1.2 result workbook generation from the source Excel workbook.
 */
public class ReportConfig {
    private final String mode;
    private final String fileNamePattern;
    private final Map<String, String> columns;
    private final int junitCaseLogEmbedThresholdBytes;
    private final int htmlCaseLogInlineLimitBytes;

    public ReportConfig(String mode, String fileNamePattern, Map<String, String> columns) {
        this(mode, fileNamePattern, columns, 10240);
    }

    public ReportConfig(String mode, String fileNamePattern, Map<String, String> columns, int junitCaseLogEmbedThresholdBytes) {
        this(mode, fileNamePattern, columns, junitCaseLogEmbedThresholdBytes, 32768);
    }

    public ReportConfig(String mode, String fileNamePattern, Map<String, String> columns, int junitCaseLogEmbedThresholdBytes, int htmlCaseLogInlineLimitBytes) {
        this.mode = mode == null || mode.trim().isEmpty() ? "append-to-copy" : mode;
        if (!("append-to-copy".equals(this.mode) || "none".equals(this.mode))) throw new IllegalArgumentException("report.mode must be append-to-copy or none");
        this.fileNamePattern = fileNamePattern == null || fileNamePattern.trim().isEmpty() ? "${suiteName}.result.xlsx" : fileNamePattern;
        this.columns = columns == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(columns);
        if (junitCaseLogEmbedThresholdBytes < 0 || junitCaseLogEmbedThresholdBytes > 1048576) throw new IllegalArgumentException("report.junit.caseLogEmbedThresholdBytes must be between 0 and 1048576");
        this.junitCaseLogEmbedThresholdBytes = junitCaseLogEmbedThresholdBytes;
        if (htmlCaseLogInlineLimitBytes < 0 || htmlCaseLogInlineLimitBytes > 1048576) throw new IllegalArgumentException("report.html.caseLogInlineLimitBytes must be between 0 and 1048576");
        this.htmlCaseLogInlineLimitBytes = htmlCaseLogInlineLimitBytes;
    }

    public String mode() { return mode; }
    public String fileNamePattern() { return fileNamePattern; }
    public Map<String, String> columns() { return columns; }
    public int junitCaseLogEmbedThresholdBytes() { return junitCaseLogEmbedThresholdBytes; }
    public int htmlCaseLogInlineLimitBytes() { return htmlCaseLogInlineLimitBytes; }
}
