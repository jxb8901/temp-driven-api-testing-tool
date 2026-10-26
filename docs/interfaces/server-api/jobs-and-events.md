<!-- att-doc-status: current release=4.2.4 -->

# Jobs, events and recovery APIs

## Server-sent events

Connect to `/api/v1/jobs/{jobId}/events` with `Accept: text/event-stream`. Events are retained in `dataDir/jobs/<jobId>/events.jsonl`, assigned increasing numeric IDs per job, and named `status`, `progress`, `log`, `diagnostic`, or `result`.

```text
id: 2
event: status
data: {"jobId":"J...","status":"RUNNING"}

```

Send `Last-Event-ID` after reconnecting to replay retained events with greater IDs. If older events have expired from the bounded journal, the stream resumes with events still retained. The stream sends `: keepalive` comments while idle and closes after it delivers the terminal result. A disconnected observer does not cancel the job. Up to 32 observers receive dedicated stream threads through direct handoff; excess observers are rejected with HTTP 503 before a stream starts. Each observer has its own one-entry notification queue. Client writes happen outside the Worker event reader.

## Job Results and compact LOAD terminal data

`GET /api/v1/jobs/{jobId}/result` returns the authorized Job result. For LOAD, `result.summary.schemaVersion` is `att-load-terminal-result/v1`; its output contract is [documented here](../../system-design/load-terminal-result.md) and in the [machine-readable DTO schema](../../../schemas/att-load-terminal-result-v1.schema.json). RUN, DEBUG, VALIDATE and other command result shapes remain unchanged.

The compact summary keeps execution outcome separate from performance verdict, with bounded aggregate metrics, threshold and workload previews, primary diagnostics, detail completeness, metric coverage, and logical archive references. Its UTF-8 JSON budget is 32 KiB. End-to-end tests measure the serialized terminal journal and SSE envelopes, including Unicode and JSON escaping, and keep each below 48 KiB against the unchanged 64 KiB Journal event-data limit. Worker frames retain the unchanged 1 MiB cap.

An ERROR keeps its runtime or preparation cause in `summary.diagnostics.primaryError`, including its bounded diagnostic and failed count. `HTTP_RESPONSE_TOO_LARGE`, `MQ_RESPONSE_TOO_LARGE`, `MQ_POOL_TIMEOUT`, `LOAD_RESOURCE_PREPARATION_LIMIT`, and `LOAD_PREPARATION_TIMEOUT` remain actionable causes. Worker-event truncation, Journal truncation and artifact publication state are secondary metadata; they do not replace the cause, terminal status or exit code. Older detailed LOAD results from Workers are projected to v1 before new persistence and terminal event publication; old stored results are projected on read.

`summary.archive.status` and `reportAvailability` are initial hints. Current archive state and approved entries come from the existing authorized artifact index route. Detailed `load-summary.json`, `load-summary.yaml`, and `report/index.html` retain `att-load-summary/v1.2` and are available only through the verified Job artifact routes. Metric coverage reports the 4,096-bucket, one-second rolling compatibility window and the independent `LoadWindowMetrics` bin width and coverage; it does not imply that a long run retains every second.

## Cancellation and recovery

`DELETE /jobs/{jobId}` requests graceful Worker termination, waits up to `gracefulStopMs`, and then force-terminates only that Worker if needed. The job record remains available. A Server restart does not automatically rerun jobs that were not terminal.

Public Artifact paths are logical paths inside the verified Job ZIP. Loose Worker output is private and has no direct download route. Absolute paths, traversal, and symlink escapes are rejected. The Server does not expose general filesystem browsing or package mutation APIs.
