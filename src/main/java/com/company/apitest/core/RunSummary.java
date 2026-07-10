/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Aggregates all case results and exposes summary counters for CLI output.
 */
public class RunSummary {
    private final List<TestResult> results;
    private final Path reportPath;

    public RunSummary(List<TestResult> results, Path reportPath) {
        this.results = results;
        this.reportPath = reportPath;
    }

    public List<TestResult> results() {
        return results;
    }

    public Path reportPath() {
        return reportPath;
    }

    public long total() {
        return results.size();
    }

    public long passed() {
        return count(ResultStatus.PASS);
    }

    public long failed() {
        return count(ResultStatus.FAIL) + count(ResultStatus.PRECHECK_FAILED) + count(ResultStatus.POSTCHECK_FAILED);
    }

    public long error() {
        return count(ResultStatus.ERROR);
    }

    public long skipped() {
        return count(ResultStatus.SKIPPED);
    }

    public long invalid() { return count(ResultStatus.INVALID); }

    private long count(ResultStatus status) {
        return results.stream().filter(result -> result.status() == status).count();
    }
}
