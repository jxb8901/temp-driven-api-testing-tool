# Compact LOAD terminal result

## Purpose

The Worker terminal result is control-plane data. It must stay small enough for
the Worker frame, Server journal, durable Job result, and initial Studio request.
The complete metrics, workload detail, thresholds, and evidence remain in the
LOAD report artifacts.

## Versioned contract

Server-mediated LOAD results use `att-load-terminal-result/v1` in the existing
`result.summary` field. The surrounding OperationResult fields (`executionId`,
`status`, `exitCode`, `durationMs`, and `diagnostics`) retain their names. The
summary carries these independently meaningful sections:

| Field | Contents |
| --- | --- |
| `execution` | Execution outcome, status, exit code, start/end timestamps, and duration. |
| `performance` | Threshold performance verdict, separate from execution outcome. |
| `metrics` | Bounded run counters, completed and successful throughput, and available latency percentiles. |
| `thresholds` | Overall verdict, total/pass/fail/not-evaluated counts, and a bounded preview. |
| `workloads` | A bounded set of workload outcome/count summaries, plus total and omitted counts. |
| `diagnostics` | Primary runtime/preparation cause, bounded actionable diagnostic, and bounded classification counts. |
| `metricCoverage` | Retained bucket count/capacity, bucket width, observed coverage, and window-series coverage/bin width. |
| `detailCompleteness` | Whether detailed report data was omitted from the inline result and bounded omission counts. |
| `archive` | Logical report paths and the initial archive availability hint. The archive API remains authoritative for its current state. |

The projection does not contain absolute host paths, secrets, request/response
payloads, per-iteration records, full time series, or unbounded evidence. Artifact
paths are relative to the Job archive, such as
`load/<runId>/load-summary.json` and `load/<runId>/report/index.html`.

## Size and precedence rules

The compact summary is capped at 32 KiB of UTF-8 JSON. The bounded result,
terminal Journal event and SSE envelope are measured end to end and remain below
48 KiB, leaving headroom under `JobEvents.MAX_EVENT_DATA_BYTES` (64 KiB). Tests
include Unicode and JSON escaping and measure the serialized event envelopes.
The Worker frame remains 1 MiB; neither transport limit changes.

The projection uses fixed limits for workload previews, diagnostics,
classifications, threshold previews, and text lengths. It reports total and
omitted counts when a preview is shortened. The root-cause diagnostic is
selected before secondary transport or archive diagnostics and is always
retained for an ERROR. Runtime classifications such as
`HTTP_RESPONSE_TOO_LARGE`, `MQ_RESPONSE_TOO_LARGE`, and `MQ_POOL_TIMEOUT`, and
preparation causes such as `LOAD_RESOURCE_PREPARATION_LIMIT` and
`LOAD_PREPARATION_TIMEOUT`, remain actionable. Worker or Journal truncation,
archive omission, and archive failure cannot replace that cause, change the
execution outcome, or change the exit code.

If a LOAD Worker exits before sending `RESULT`, the Server still publishes a
compact terminal result using its bounded Worker-exit or protocol diagnostic;
the performance verdict is `NOT_EVALUATED` and report availability is omitted
when no report artifact was produced.

Execution outcome, performance verdict, detail completeness, and archive
availability are separate fields. For example, an ERROR can have omitted
details and a READY archive; an execution can PASS while its archive is still
PENDING or has FAILED.

## Detailed artifacts and metrics coverage

`load-summary.json`, `load-summary.yaml`, and `report/index.html` remain the
canonical detailed report and retain the `att-load-summary/v1.2` schema. The
Server publishes them only through the verified #236 ZIP archive and its
existing authorized preview/download routes. Archive states are reported as
`PENDING`, `ARCHIVING`, `READY`, `FAILED`, or omitted by export policy. Missing
or omitted artifacts do not remove the inline outcome.

The inline result reports that `LoadMetrics` retains a rolling window of at
most 4,096 one-second buckets. `LoadWindowMetrics` reports its actual coverage,
bin width, and bounded series capacity. A long run must not be described as
having a full-duration one-second trend when earlier buckets have rolled out.

Studio loads the compact Job result with Job Details. It fetches detailed
summary data only when the operator opens Metrics, using the existing
authorized archive routes and showing archive, loading, omission, and failure
states. Artifacts are loaded when the operator opens the Artifacts tab.

## Compatibility

The Worker protocol version and its 1 MiB frame limit do not change. The legacy
`truncated` fallback remains for non-LOAD results and older Workers. Existing
direct CLI LOAD output continues to use the full `att-load-summary/v1.2`
summary. Server clients should check `result.summary.schemaVersion`: LOAD
terminal data now uses `att-load-terminal-result/v1`; detailed report consumers
should read the archive's `load-summary.json`. RUN, DEBUG, VALIDATE, and other
non-LOAD result shapes are unchanged.

## Verification benchmark

Run the deterministic compact-result benchmark/test with:

```sh
mvn -q -o -pl att-server -am -Dtest=ServerRuntimeLoadTerminalResultTest \
  -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test
```

It constructs short and 30–60-minute-equivalent multi-workload metric snapshots
without running a long LOAD, then compares Worker terminal bytes, stored result
bytes, terminal journal/SSE bytes, and initial Job Details transfer bytes.
