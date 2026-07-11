package att.core;

/** Single status aggregation and CLI exit-code policy for every ATT consumer. */
public final class ResultAggregator {
    private ResultAggregator() {}

    public static ResultStatus aggregate(Iterable<ResultStatus> statuses) {
        boolean invalid = false, fail = false, pass = false, sawAny = false;
        for (ResultStatus status : statuses) {
            if (status == null) continue;
            sawAny = true;
            if (status == ResultStatus.ERROR) return ResultStatus.ERROR;
            if (status == ResultStatus.INVALID) invalid = true;
            else if (status == ResultStatus.FAIL) fail = true;
            else if (status == ResultStatus.PASS) pass = true;
        }
        if (invalid) return ResultStatus.INVALID;
        if (fail) return ResultStatus.FAIL;
        if (pass) return ResultStatus.PASS;
        return sawAny ? ResultStatus.SKIPPED : ResultStatus.SKIPPED;
    }

    public static int exitCode(ResultStatus status) {
        if (status == ResultStatus.ERROR) return 3;
        if (status == ResultStatus.INVALID) return 2;
        if (status == ResultStatus.FAIL) return 1;
        return 0;
    }
}
