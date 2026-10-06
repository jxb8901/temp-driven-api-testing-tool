/*
 * Author: Jeffrey + ChatGPT
 */

package att.core;

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
        return count(ResultStatus.FAIL);
    }

    public long error() {
        return count(ResultStatus.ERROR);
    }

    public long skipped() {
        return count(ResultStatus.SKIPPED);
    }

    public long invalid() { return count(ResultStatus.INVALID); }

    public ResultStatus status() {
        java.util.List<ResultStatus> statuses = new java.util.ArrayList<ResultStatus>();
        for (TestResult result : results) statuses.add(result.status());
        return ResultAggregator.aggregate(statuses);
    }

    public int exitCode() { return ResultAggregator.exitCode(status()); }

    private long count(ResultStatus status) {
        return results.stream().filter(result -> result.status() == status).count();
    }
}
