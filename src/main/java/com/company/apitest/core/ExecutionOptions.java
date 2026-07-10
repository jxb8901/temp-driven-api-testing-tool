/* Author: Jeffrey + ChatGPT */
package com.company.apitest.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** V2 command and case-selection options. */
public final class ExecutionOptions {
    private final String command;
    private final Path configPath;
    private final List<Path> suitePaths;
    private final Path suiteDirectory;
    private final Set<String> caseIds;
    private final Set<String> tags;
    private final Set<String> excludeTags;
    private final String runId;
    private final boolean all;
    private final boolean rerunFailed;
    private final boolean dryRun;
    private final boolean failFast;
    private final Path outputDirectory;
    private final String format;
    private final boolean quiet;
    private final boolean verbose;
    private final boolean singlePage;

    public ExecutionOptions(Path configPath, Path suitePath, Path suiteDirectory, Set<String> caseIds, Set<String> tags,
                            Set<String> excludeTags, String runId, boolean rerunFailed, boolean dryRun,
                            boolean failFast, Path outputDirectory) {
        this("run", configPath, suitePath == null ? Collections.<Path>emptyList() : Collections.singletonList(suitePath), suiteDirectory, caseIds, tags, excludeTags, runId, false,
                rerunFailed, dryRun, failFast, outputDirectory, "human", false, false, false);
    }

    private ExecutionOptions(String command, Path configPath, List<Path> suitePaths, Path suiteDirectory,
                             Set<String> caseIds, Set<String> tags, Set<String> excludeTags, String runId,
                             boolean all, boolean rerunFailed, boolean dryRun, boolean failFast, Path outputDirectory,
                             String format, boolean quiet, boolean verbose, boolean singlePage) {
        this.command = command;
        this.configPath = configPath;
        this.suitePaths = new ArrayList<Path>(suitePaths);
        this.suiteDirectory = suiteDirectory;
        this.caseIds = caseIds;
        this.tags = tags;
        this.excludeTags = excludeTags;
        this.runId = runId;
        this.all = all;
        this.rerunFailed = rerunFailed;
        this.dryRun = dryRun;
        this.failFast = failFast;
        this.outputDirectory = outputDirectory;
        this.format = format;
        this.quiet = quiet;
        this.verbose = verbose;
        this.singlePage = singlePage;
    }

    public static ExecutionOptions parse(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) return empty("help");
        String command = args[0].startsWith("--") ? "run" : args[0];
        int start = args[0].startsWith("--") ? 0 : 1;
        if (!("run".equals(command) || "validate".equals(command) || "docs".equals(command) || "report".equals(command) || "build".equals(command) || "version".equals(command))) {
            throw new IllegalArgumentException("Unknown command: " + command);
        }
        Path config = Paths.get("config/config.yaml");
        List<Path> suites = new ArrayList<Path>();
        Path suiteDir = null;
        Set<String> caseIds = new LinkedHashSet<String>();
        Set<String> tags = new LinkedHashSet<String>();
        Set<String> excludeTags = new LinkedHashSet<String>();
        String runId = "";
        boolean all = false, rerun = false, dry = false, failFast = false;
        boolean quiet = false, verbose = false, singlePage = false;
        String format = "human";
        Path output = null;
        for (int i = start; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg)) config = Paths.get(value(args, ++i, arg));
            else if ("--suite".equals(arg)) suites.add(Paths.get(value(args, ++i, arg)));
            else if ("--suite-dir".equals(arg)) suiteDir = Paths.get(value(args, ++i, arg));
            else if ("--case".equals(arg) || "--case-id".equals(arg)) caseIds.add(value(args, ++i, arg));
            else if ("--tag".equals(arg)) tags.add(value(args, ++i, arg));
            else if ("--exclude-tag".equals(arg)) excludeTags.add(value(args, ++i, arg));
            else if ("--run-id".equals(arg)) runId = value(args, ++i, arg);
            else if ("--output-dir".equals(arg)) output = Paths.get(value(args, ++i, arg));
            else if ("--format".equals(arg)) format = value(args, ++i, arg);
            else if ("--all".equals(arg)) all = true;
            else if ("--rerun-failed".equals(arg)) rerun = true;
            else if ("--dry-run".equals(arg)) dry = true;
            else if ("--fail-fast".equals(arg)) failFast = true;
            else if ("--quiet".equals(arg)) quiet = true;
            else if ("--verbose".equals(arg)) verbose = true;
            else if ("--single-page".equals(arg)) singlePage = true;
            else if ("--help".equals(arg)) return empty("help");
            else throw new IllegalArgumentException("Unsupported option: " + arg);
        }
        if ("validate".equals(command)) dry = true;
        if (("run".equals(command) || "validate".equals(command)) && !all && suites.isEmpty() && suiteDir == null && caseIds.isEmpty() && tags.isEmpty()) {
            throw new IllegalArgumentException(command + " requires --all, --suite, --case, or --tag");
        }
        if (all && suites.isEmpty() && suiteDir == null) suiteDir = Paths.get("testcase");
        if (suites.isEmpty() && suiteDir == null && (!caseIds.isEmpty() || !tags.isEmpty())) suiteDir = Paths.get("testcase");
        if (!("human".equals(format) || "json".equals(format))) throw new IllegalArgumentException("--format must be human or json");
        if (quiet && verbose) throw new IllegalArgumentException("--quiet and --verbose cannot be used together");
        return new ExecutionOptions(command, config, suites, suiteDir, caseIds, tags, excludeTags, runId, all, rerun, dry, failFast, output, format, quiet, verbose, singlePage);
    }

    private static ExecutionOptions empty(String command) {
        return new ExecutionOptions(command, Paths.get("config/config.yaml"), Collections.<Path>emptyList(), null, new LinkedHashSet<String>(),
                new LinkedHashSet<String>(), new LinkedHashSet<String>(), "", false, false, false, false, null, "human", false, false, false);
    }

    public String command() { return command; }
    public Path configPath() { return configPath; }
    public Path suitePath() { return suitePaths.isEmpty() ? null : suitePaths.get(0); }
    public List<Path> suitePaths() { return Collections.unmodifiableList(suitePaths); }
    public Path suiteDirectory() { return suiteDirectory; }
    public Set<String> caseIds() { return caseIds; }
    public Set<String> tags() { return tags; }
    public Set<String> excludeTags() { return excludeTags; }
    public String runId() { return runId; }
    public boolean all() { return all; }
    public boolean rerunFailed() { return rerunFailed; }
    public boolean dryRun() { return dryRun; }
    public boolean failFast() { return failFast; }
    public Path outputDirectory() { return outputDirectory; }
    public String format() { return format; }
    public boolean quiet() { return quiet; }
    public boolean verbose() { return verbose; }
    public boolean singlePage() { return singlePage; }

    public boolean matches(TestCase testCase) {
        boolean caseMatches = caseIds.isEmpty() || caseIds.contains(testCase.caseId());
        boolean tagMatches = tags.isEmpty() || testCase.tags().stream().anyMatch(tags::contains);
        boolean excluded = testCase.tags().stream().anyMatch(excludeTags::contains);
        return caseMatches && tagMatches && !excluded;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
        return args[index];
    }
}
