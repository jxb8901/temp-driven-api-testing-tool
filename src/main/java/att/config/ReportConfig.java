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

    public ReportConfig(String mode, String fileNamePattern, Map<String, String> columns) {
        this(mode, fileNamePattern, columns, 10240);
    }

    public ReportConfig(String mode, String fileNamePattern, Map<String, String> columns, int junitCaseLogEmbedThresholdBytes) {
        this.mode = mode == null || mode.trim().isEmpty() ? "append-to-copy" : mode;
        this.fileNamePattern = fileNamePattern == null || fileNamePattern.trim().isEmpty() ? "${suiteName}.result.xlsx" : fileNamePattern;
        this.columns = columns == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(columns);
        if (junitCaseLogEmbedThresholdBytes < 0 || junitCaseLogEmbedThresholdBytes > 1048576) throw new IllegalArgumentException("report.junit.caseLogEmbedThresholdBytes must be between 0 and 1048576");
        this.junitCaseLogEmbedThresholdBytes = junitCaseLogEmbedThresholdBytes;
    }

    public String mode() { return mode; }
    public String fileNamePattern() { return fileNamePattern; }
    public Map<String, String> columns() { return columns; }
    public int junitCaseLogEmbedThresholdBytes() { return junitCaseLogEmbedThresholdBytes; }
}
