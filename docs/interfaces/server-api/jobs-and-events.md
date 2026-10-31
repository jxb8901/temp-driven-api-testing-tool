<!-- att-doc-status: current release=4.2.8 -->

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

ERROR and INVALID results keep their bounded runtime, preparation, or validation cause in `summary.diagnostics.primaryError`. A failed count appears only when the source reports a real count. `HTTP_RESPONSE_TOO_LARGE`, `MQ_RESPONSE_TOO_LARGE`, `MQ_POOL_TIMEOUT`, `LOAD_RESOURCE_PREPARATION_LIMIT`, and `LOAD_PREPARATION_TIMEOUT` remain actionable causes. Worker-event truncation, Journal truncation and artifact publication state are secondary metadata; they do not replace the cause, terminal status or exit code. Oversized legacy LOAD results are projected before Worker emission and reprojected before Server persistence/publication; older stored results are projected on read.

`summary.archive.status` and `reportAvailability` are initial hints. Current archive state and approved entries come from the existing authorized artifact index route. `LoadReportWriter` emits archived `load-summary.json` and YAML with `att-load-summary/v1.3`; long metric collections use the relative, versioned `details/v1/manifest.json` sidecars. These files are available only through the verified Job artifact routes. Direct Engine/CLI `OperationResult` output remains legacy summary contract (schema under `schemas/history/`). Metric coverage reports the 4,096-bucket, one-second rolling compatibility window and the independent `LoadWindowMetrics` bin width and coverage; it does not imply that a long run retains every second.

## Cancellation and recovery

`DELETE /jobs/{jobId}` requests graceful Worker termination, waits up to `gracefulStopMs`, and then force-terminates only that Worker if needed. The job record remains available. A Server restart does not automatically rerun jobs that were not terminal.

Public Artifact paths are logical paths inside the verified Job ZIP. Loose Worker output is private and has no direct download route. Absolute paths, traversal, and symlink escapes are rejected. The Server does not expose general filesystem browsing or package mutation APIs.

## Job replay and editable drafts

Replay is available only when the caller is authorized for the source Job and it has a safely retained `att-job-execution/v1` or `/v2` definition. Replay never reconstructs secrets, Worker transport data, host paths or unsafe source paths from logs/artifacts.

The v2 retained-spec contract is [att-job-execution/v2](../../../schemas/api/att-job-execution-v2.schema.json). The response envelope for the short-lived editor draft is [att-job-replay-draft/v1](../../../schemas/api/att-job-replay-draft-v1.schema.json).

| Method and path | Contract |
| --- | --- |
| `GET /jobs/{jobId}/replay` | Safe availability metadata and definition for an authorized caller. |
| `POST /jobs/{jobId}/replay-draft` | Accepts `{"sourcePolicy":"current"|"strict","overrides":{...}}`; returns `att-job-replay-draft/v1`, `draftRef`, `intentId`, original/current revisions and validated preview. Omitting `sourcePolicy` keeps legacy strict behavior. |
| `GET /jobs/{jobId}/replay-draft/{draftRef}` | Returns the sanitized prefill definition and freshly resolved configuration for the principal-bound editor draft. Response is `private, no-store`. |
| `POST /jobs/{jobId}/rerun` | Accepts `{"intentId":"...","confirm":true,"sourcePolicy":"current"|"strict"}` and returns the independent Job with `rerunOfJobId`. Same-intent retries return the accepted Job. |

Overrides are bounded to 32 KiB and typed allowlists. LOAD workload edits bind to retained workload IDs and preserve the original `virtualUsers` or `arrivalRate` model; the Server rejects implicit model conversion. RUN edits can select the retained target or exact Case selection and change supported options/inputs. The ordinary Engine validators remain authoritative. A package-file-backed LOAD replay uses its validated safe package-relative source path and is not subject to `server.inlineLoad` inline-only ceilings.

`ATT-SERVER-REPLAY-REVISION-CONFLICT` means strict mode cannot use the recorded original revision. It does not restore missing historical source files. `ATT-SERVER-REPLAY-DRAFT-STALE` means the Package changed after draft validation. `ATT-SERVER-REPLAY-DRAFT-EXPIRED` means the short-lived draft or intent expired or was lost on Server restart. Missing safe inputs return `ATT-SERVER-REPLAY-INPUT-MISSING`; removed/unresolved resources return `ATT-SERVER-REPLAY-RESOURCE-MISSING`; schema or field errors return `ATT-SERVER-REPLAY-VALIDATION-FAILED` with safe diagnostics; capacity returns HTTP 429. Draft references and confirmation intents are principal-bound and held in Server memory for ten minutes. Reopen the source Job to create a new draft after expiration/restart.
