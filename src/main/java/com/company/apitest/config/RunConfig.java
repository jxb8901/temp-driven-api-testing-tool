/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

/**
 * Configures V1.2 run identity generation.
 */
public class RunConfig {
    private final String defaultMode;
    private final String timestampFormat;

    public RunConfig(String defaultMode, String timestampFormat) {
        this.defaultMode = defaultMode == null || defaultMode.trim().isEmpty() ? "timestamp" : defaultMode;
        this.timestampFormat = timestampFormat == null || timestampFormat.trim().isEmpty() ? "yyyyMMdd-HHmmss" : timestampFormat;
    }

    public String defaultMode() { return defaultMode; }
    public String timestampFormat() { return timestampFormat; }
}
