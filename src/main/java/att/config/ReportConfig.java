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

    public ReportConfig(String mode, String fileNamePattern, Map<String, String> columns) {
        this.mode = mode == null || mode.trim().isEmpty() ? "append-to-copy" : mode;
        this.fileNamePattern = fileNamePattern == null || fileNamePattern.trim().isEmpty() ? "${suiteName}.result.xlsx" : fileNamePattern;
        this.columns = columns == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(columns);
    }

    public String mode() { return mode; }
    public String fileNamePattern() { return fileNamePattern; }
    public Map<String, String> columns() { return columns; }
}
