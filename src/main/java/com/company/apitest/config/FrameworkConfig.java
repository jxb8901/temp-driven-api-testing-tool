/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

import java.nio.file.Path;

/**
 * Immutable global runtime configuration loaded from config/config.yaml.
 */
public class FrameworkConfig {
    private final Path outputDirectory;
    private final Path reportDirectory;
    private final Path logDirectory;
    private final String environment;
    private final int timeoutSeconds;
    private final String executorCommand;

    public FrameworkConfig(Path outputDirectory, Path reportDirectory, Path logDirectory, String environment, int timeoutSeconds, String executorCommand) {
        this.outputDirectory = outputDirectory;
        this.reportDirectory = reportDirectory;
        this.logDirectory = logDirectory;
        this.environment = environment;
        this.timeoutSeconds = timeoutSeconds;
        this.executorCommand = executorCommand;
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

    public String executorCommand() {
        return executorCommand;
    }
}
