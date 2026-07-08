/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.config.FrameworkConfigLoader;
import com.company.apitest.core.ExecutionOptions;
import com.company.apitest.core.FrameworkEngine;
import com.company.apitest.core.RunSummary;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line entry point for running a configured Excel test suite.
 */
public final class FrameworkRunner {
    private FrameworkRunner() {
    }

    public static void main(String[] args) throws Exception {
        ExecutionOptions options = ExecutionOptions.parse(args);
        FrameworkConfig config = new FrameworkConfigLoader().load(options.configPath());
        RunSummary summary = new FrameworkEngine(Paths.get("").toAbsolutePath(), config).run(options);

        System.out.printf("Total: %d, Passed: %d, Failed: %d, Error: %d, Skipped: %d%n",
                summary.total(), summary.passed(), summary.failed(), summary.error(), summary.skipped());
        System.out.println("Run Directory: " + summary.reportPath());

        if (summary.failed() > 0 || summary.error() > 0) {
            System.exit(1);
        }
    }

    public static List<String> supportedArguments() {
        return Arrays.asList("--config", "--suite", "--suite-dir", "--case-id", "--tag", "--exclude-tag",
                "--rerun-failed", "--dry-run", "--fail-fast", "--run-id", "--output-dir");
    }
}
