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
selected before secondary transport or archive diagnostics and is retained for
ERROR and INVALID outcomes. It carries the bounded sanitized diagnostic; real
failed counts are included only when they were measured. Runtime classifications such as
`HTTP_RESPONSE_TOO_LARGE`, `MQ_RESPONSE_TOO_LARGE`, and `MQ_POOL_TIMEOUT`, and
preparation causes such as `LOAD_RESOURCE_PREPARATION_LIMIT` and
`LOAD_PREPARATION_TIMEOUT`, remain actionable. Worker or Journal truncation,
archive omission, and archive failure cannot replace that cause, change the
execution outcome, or change the exit code.

When an oversized legacy LOAD `RESULT` cannot fit a Worker frame, WorkerMain
projects the allowlisted cause and bounded diagnostic from the legacy metrics,
failure diagnostics, preparation summary, or outer diagnostics before dropping
detail. `ATT-WORKER-EVENT-TRUNCATED` is appended as a secondary warning. The
Server reprojects the same result before storing `resultJson` and publishing the
terminal Journal/SSE event, preserving status and exit code. Preparation-limit
and deadline classification use typed exceptions; no failed count is invented
when no helper failure was observed.

If a LOAD Worker exits before sending `RESULT`, the Server still publishes a
compact terminal result using its bounded Worker-exit or protocol diagnostic;
the performance verdict is `NOT_EVALUATED` and report availability is omitted
when no report artifact was produced.

Execution outcome, performance verdict, detail completeness, and archive
availability are separate fields. For example, an ERROR can have omitted
details and a READY archive; an execution can PASS while its archive is still
PENDING or has FAILED.

## Detailed artifacts and metrics coverage

`LoadReportWriter` stages `load-summary.json`, YAML, standalone HTML, and
versioned details before promoting them into the run directory. It writes
`load-report-manifest.json` last with bundle state, run identity, measured
duration, checkpoint sequence references, and each report path's byte length
and SHA-256. The manifest is the commit marker: a failure during rendering or
promotion leaves it absent. The Server refuses to publish a canonical LOAD
summary unless the marker, all four essential report files, all listed hashes,
and the versioned detail manifest verify.

The report uses the additive
`att-load-summary/v1.3` schema. It preserves exact aggregate counters and
thresholds while moving long aggregate and per-workload metric collections into
`details/v1/` files described by `details/v1/manifest.json` using
`att-load-detail-manifest/v1`, `att-load-detail-reference/v1`, and
`att-load-detail-series/v1`, and `att-load-workload-detail/v1`. Inline previews contain at most 32 items per
collection; sidecar chunks are at most 4 MiB, the sidecar set is capped at 100
MiB/9,000 files, and complete report output is capped at 120 MiB. The three
main report files each remain at or below 16 MiB. The standalone HTML remains
useful offline with exact report tables and an inline chart preview, and links
to the sidecar manifest for full data. If a bound is exceeded, report creation
fails explicitly and removes partial report files; the compact archive state is
`FAILED`, while execution status and exit code remain unchanged. It does not silently omit series. The Server publishes all
files only through the verified #236 ZIP archive and existing authorized
preview/download routes. Archive states are reported as
`PENDING`, `ARCHIVING`, `READY`, `FAILED`, or omitted by export policy. Missing
or omitted artifacts do not remove the inline outcome.

The inline result reports that `LoadMetrics` retains a rolling window of at
most 4,096 one-second buckets. `LoadWindowMetrics` reports its actual coverage,
bin width, and bounded series capacity. A long run must not be described as
having a full-duration one-second trend when earlier buckets have rolled out.

Studio loads the compact Job result with Job Details and makes no artifact
request during the initial Details load. Opening Results fetches the authorized
summary preview on demand and shows bounded retained-evidence records, workload
counts, and links to visible workload detail sidecars. Opening Metrics loads
the authorized summary and its referenced metric-series sidecars on demand. The
page shows archive, loading, omission, and failure states. The full archive
listing is fetched only when the operator opens the Artifacts tab.

## Compatibility

The Worker protocol version and its 1 MiB frame limit do not change. The legacy
`truncated` fallback remains for non-LOAD results and older Workers. Existing
direct Engine/CLI `OperationResult` output continues to use its legacy
full-summary contract; its schema definition remains under `schemas/history/`.
The archived report emitted by `LoadReportWriter` is
versioned separately as `att-load-summary/v1.3`. Server clients should check
`result.summary.schemaVersion`: LOAD terminal data uses
`att-load-terminal-result/v1`; detailed report consumers should follow the
archive's `detailManifestPath` and relative series references. RUN, DEBUG,
VALIDATE, and other non-LOAD result shapes are unchanged.

## Verification benchmark

Run the deterministic compact-result benchmark/test with:

```sh
mvn -q -o -pl att-server -am -Dtest=ServerRuntimeLoadTerminalResultTest \
  -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test
```

It constructs short and 30–60-minute-equivalent multi-workload metric snapshots
without running a long LOAD, then compares Worker terminal bytes, stored result
bytes, terminal journal/SSE bytes, and initial Job Details transfer bytes. It
also reports a best-effort JVM heap-used snapshot delta around projection; this
is neither a forced-GC allocation measurement nor a Worker peak-heap
measurement. The frontend test bounds initial render structure by DOM node
count and verifies that no archive request occurs before Results or Metrics is
opened.
