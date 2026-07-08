/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses and stores command-line selection options for a framework run.
 */
public class ExecutionOptions {
    private final Path configPath;
    private final Path suitePath;
    private final Path suiteDirectory;
    private final Set<String> caseIds;
    private final Set<String> tags;
    private final Set<String> excludeTags;
    private final String runId;
    private final boolean rerunFailed;
    private final boolean dryRun;
    private final boolean failFast;
    private final Path outputDirectory;

    public ExecutionOptions(Path configPath, Path suitePath, Set<String> caseIds, Set<String> tags) {
        this(configPath, suitePath, null, caseIds, tags, new HashSet<String>(), "", false, false, false, null);
    }

    public ExecutionOptions(Path configPath, Path suitePath, Path suiteDirectory, Set<String> caseIds, Set<String> tags,
                            Set<String> excludeTags, String runId, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory) {
        this.configPath = configPath;
        this.suitePath = suitePath;
        this.suiteDirectory = suiteDirectory;
        this.caseIds = caseIds;
        this.tags = tags;
        this.excludeTags = excludeTags;
        this.runId = runId;
        this.rerunFailed = rerunFailed;
        this.dryRun = dryRun;
        this.failFast = failFast;
        this.outputDirectory = outputDirectory;
    }

    public static ExecutionOptions parse(String[] args) {
        Path config = Paths.get("config/config.yaml");
        Path suite = Paths.get("testcase/payment_regression.xlsx");
        Path suiteDir = null;
        Set<String> caseIds = new HashSet<>();
        Set<String> tags = new HashSet<>();
        Set<String> excludeTags = new HashSet<String>();
        String runId = "";
        boolean rerunFailed = false;
        boolean dryRun = false;
        boolean failFast = false;
        Path outputDir = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg)) {
                config = Paths.get(requiredValue(args, ++i, "--config"));
            } else if ("--suite".equals(arg)) {
                suite = Paths.get(requiredValue(args, ++i, "--suite"));
            } else if ("--suite-dir".equals(arg)) {
                suiteDir = Paths.get(requiredValue(args, ++i, "--suite-dir"));
            } else if ("--case-id".equals(arg)) {
                caseIds.add(requiredValue(args, ++i, "--case-id"));
            } else if ("--tag".equals(arg)) {
                tags.add(requiredValue(args, ++i, "--tag"));
            } else if ("--exclude-tag".equals(arg)) {
                excludeTags.add(requiredValue(args, ++i, "--exclude-tag"));
            } else if ("--run-id".equals(arg)) {
                runId = requiredValue(args, ++i, "--run-id");
            } else if ("--rerun-failed".equals(arg)) {
                rerunFailed = true;
            } else if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if ("--fail-fast".equals(arg)) {
                failFast = true;
            } else if ("--output-dir".equals(arg)) {
                outputDir = Paths.get(requiredValue(args, ++i, "--output-dir"));
            } else {
                throw new IllegalArgumentException("Unsupported argument: " + args[i]);
            }
        }
        return new ExecutionOptions(config, suite, suiteDir, caseIds, tags, excludeTags, runId, rerunFailed, dryRun, failFast, outputDir);
    }

    public Path configPath() {
        return configPath;
    }

    public Path suitePath() {
        return suitePath;
    }

    public Path suiteDirectory() { return suiteDirectory; }

    public Set<String> caseIds() {
        return caseIds;
    }

    public Set<String> tags() {
        return tags;
    }
    public Set<String> excludeTags() { return excludeTags; }
    public String runId() { return runId; }
    public boolean rerunFailed() { return rerunFailed; }
    public boolean dryRun() { return dryRun; }
    public boolean failFast() { return failFast; }
    public Path outputDirectory() { return outputDirectory; }

    public boolean matches(TestCase testCase) {
        boolean caseMatches = caseIds.isEmpty() || caseIds.contains(testCase.caseId());
        boolean tagMatches = tags.isEmpty() || testCase.tags().stream().anyMatch(tags::contains);
        boolean excludeMatches = !testCase.tags().stream().anyMatch(excludeTags::contains);
        return caseMatches && tagMatches && excludeMatches;
    }

    private static String requiredValue(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + name);
        }
        return args[index];
    }
}
