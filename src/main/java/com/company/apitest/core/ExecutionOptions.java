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
    private final Set<String> caseIds;
    private final Set<String> tags;

    public ExecutionOptions(Path configPath, Path suitePath, Set<String> caseIds, Set<String> tags) {
        this.configPath = configPath;
        this.suitePath = suitePath;
        this.caseIds = caseIds;
        this.tags = tags;
    }

    public static ExecutionOptions parse(String[] args) {
        Path config = Paths.get("config/config.yaml");
        Path suite = Paths.get("testcase/payment_regression.xlsx");
        Set<String> caseIds = new HashSet<>();
        Set<String> tags = new HashSet<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg)) {
                config = Paths.get(requiredValue(args, ++i, "--config"));
            } else if ("--suite".equals(arg)) {
                suite = Paths.get(requiredValue(args, ++i, "--suite"));
            } else if ("--case-id".equals(arg)) {
                caseIds.add(requiredValue(args, ++i, "--case-id"));
            } else if ("--tag".equals(arg)) {
                tags.add(requiredValue(args, ++i, "--tag"));
            } else {
                throw new IllegalArgumentException("Unsupported argument: " + args[i]);
            }
        }
        return new ExecutionOptions(config, suite, caseIds, tags);
    }

    public Path configPath() {
        return configPath;
    }

    public Path suitePath() {
        return suitePath;
    }

    public Set<String> caseIds() {
        return caseIds;
    }

    public Set<String> tags() {
        return tags;
    }

    public boolean matches(TestCase testCase) {
        boolean caseMatches = caseIds.isEmpty() || caseIds.contains(testCase.caseId());
        boolean tagMatches = tags.isEmpty() || testCase.tags().stream().anyMatch(tags::contains);
        return caseMatches && tagMatches;
    }

    private static String requiredValue(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + name);
        }
        return args[index];
    }
}
