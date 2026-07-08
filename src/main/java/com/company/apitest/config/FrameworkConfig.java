/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    private final Map<String, ToolConfig> tools;

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment, int timeoutSeconds,
                           String testcaseSheet, Map<String, String> testcaseColumns, Map<String, ToolConfig> tools) {
        this.outputDirectory = outputDirectory;
        this.reportDirectory = reportDirectory;
        this.logDirectory = logDirectory;
        this.environment = environment;
        this.timeoutSeconds = timeoutSeconds;
        this.testcaseSheet = testcaseSheet;
        this.testcaseColumns = testcaseColumns == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(testcaseColumns);
        this.tools = tools == null ? Collections.<String, ToolConfig>emptyMap() : new LinkedHashMap<String, ToolConfig>(tools);
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
    public Map<String, ToolConfig> tools() { return tools; }
    public ToolConfig tool(String key) { return tools.get(key); }
}
