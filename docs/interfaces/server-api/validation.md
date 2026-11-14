<!-- att-doc-status: current release=4.2.10 -->

# Server Validation API

`POST /api/v1/validation` runs the existing Server-side validator synchronously and returns an `att-validation/v1` report. Studio uses this bounded, read-only route to validate Server Runtime Config, a registered Package, or one selected resource without creating an execution draft or Job. The Server remains the only authority for validation, authorization, effective configuration, and resource revisions.

## Endpoint and capability

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/v1/validation` | Synchronous `att-validation/v1` report; no Job is created. |
| `POST` | `/api/v1/validations` | Admit a durable asynchronous validation record; returns `202`. |
| `GET` | `/api/v1/validations/{validationId}` | Read one owner-scoped record and its report. |
| `GET` | `/api/v1/validations` | List bounded recent records for the authenticated owner. |
| `POST` | `/api/v1/validations/{validationId}/cancel` | Cancel the owner's queued or running record. |

`GET /api/v1/version` advertises `validationApiVersion: "att-validation/v1"` when the Server supports this route and report contract. Studio checks that marker before enabling **Read-only validation**. An absent or different marker means the Server build does not advertise the required capability.

Both submit routes delegate to the existing Server validator through the bounded inspection Worker and use the same typed request contract. They do not execute business actions, submit RUN/LOAD work, create an execution draft, create a Schedule, or save a snapshot. The synchronous route remains available to compatible clients. Use the asynchronous routes when the caller needs durable progress and result recovery.

## Request

Send `Content-Type: application/json`.

Allowed fields: `command`, `targetKind`, `target`, `packageId`, `environment`, `all`.

| Field | Contract |
|---|---|
| `command` | Required and must be `validate`. |
| `targetKind` | Optional; defaults to `config`. Supported scopes: `config`, `package`, `case`, `suite`, `template`, `flow`, `tool`, and `load`. |
| `target` | Required for selected-resource scopes. A Case uses its logical Case ID; Suite and LOAD use a package-relative path; Template, Flow, and Tool use the Server's canonical package-local resource ID. It is not accepted for `config` or `package`. |
| `packageId` | Required for `package` and selected-resource scopes. Optional for `config`; when supplied, it selects that registered Package's effective Config. Without it, Config scope validates Server Runtime Config. |
| `environment` | Optional active Environment Profile for Package-scoped validation. When omitted, the Server resolves the Package's effective/default Environment and includes it in report context. The Server applies its configured authorization policy. |
| `all` | Optional boolean. When `true`, validate the installation-wide scope. Do not combine it with `targetKind`, `target`, `packageId`, or `environment`; this scope requires system-administrator access and is not exposed by Studio. |

Example: validate the effective Config for `payments` in `SIT`:

```json
{
  "command": "validate",
  "targetKind": "config",
  "packageId": "payments",
  "environment": "SIT"
}
```

An installation-wide request is `{"command":"validate","all":true}`. It cannot name a Package, target, or Environment.

## Response

A completed validation returns HTTP `200`, including when the report records failures. The JSON response follows the [att-validation/v1 schema](../../../schemas/att-validation-v1.schema.json) and contains:

| Field | Meaning |
|---|---|
| `schemaVersion` | Constant `att-validation/v1`. |
| `scope`, `backend` | Requested scope and `SERVER`. |
| `sections`, `diagnostics` | Hierarchical checks and flattened findings. |
| `counts` | `pass`, `fail`, `warn`, `skip`, `errors`, and `warnings` totals. |
| `overallStatus`, `complete`, `exitCode` | `PASS`, `INVALID`, or `PARTIAL`; completeness; CLI-compatible result code. Incomplete reports are not a pass. |
| `context` | Optional safe validation context, including resolved Package and Environment identity where applicable. |
| `requestId` | Server request identifier for tracing. |

Validation findings are in the report body; HTTP `200` does not mean every check passed. No Job ID or draft ID is returned.

## Authorization and errors

Every request requires an authenticated Server principal. Scoped checks require operational access (the configured Server operator role or an active local USER account); `all: true` requires system-administrator capability. The endpoint applies the Server's Origin/CSRF policy, request-size limit, inspection Worker capacity, and response-size limit. Package and Environment access are checked by the Server for each request.

| HTTP status | Meaning |
|---|---|
| `400` | Malformed JSON, unsupported fields, or an invalid validation plan. |
| `401` | Authentication is required or the session expired. |
| `403` | Operator/system-administrator access or Environment authorization is denied. |
| `404` | Registered Package or selected validation target is not found. |
| `409` | Source revision changed or the request was rejected as stale. |
| `413` | Request or report exceeds its configured size limit. |
| `415` | Request is not JSON. |
| `500` | Sanitized validation failure; server stack traces are not returned. |
| `503` | Inspection Worker capacity is full. |
| `504` | Read-only validation exceeds its time limit. |

See [Studio Read-only validation](../studio/validation.md) for scope selection, contextual entry points, report review, and cancellation behavior.

## Persistent asynchronous records

`POST /api/v1/validations` accepts the same JSON fields as the synchronous route. It returns HTTP `202` only after the normalized plan and initial `QUEUED` record have been committed to the Server database. It includes `validationId` and a `Location` header. Admission reserves space in the existing bounded inspection capacity; a full queue returns `503`.

The record contains the typed plan identity, lifecycle state, timing values, optional safe source revision, safe error information, and (when complete) the `att-validation/v1` report. It does not store submitted source text, arbitrary filesystem paths, or client-supplied configuration. The Server rechecks registered Package and Environment access when a record is read or listed. Records are visible only to their owner. Installation-wide records also require system-administrator access. The list endpoint does not enumerate another owner's records.

`GET /api/v1/validations` accepts optional `packageId`, `scope`, `limit`, and opaque `cursor` query parameters. `limit` defaults to `20` and must be from `1` through `100`. The response contains `items` and `nextCursor`; list items omit the report body. Results use a bounded, newest-first page. A revoked or unavailable Package or Environment is omitted.

`GET /api/v1/validations/{validationId}` returns the record and report when available. `POST /api/v1/validations/{validationId}/cancel` performs a Server-side cancellation for a queued or running record. It is idempotent for terminal records and returns their current state. Closing a browser view, changing routes, or stopping polling does not cancel a record. Only this explicit endpoint requests cancellation.

Transport state is separate from report outcome:

| Record state | Meaning |
|---|---|
| `QUEUED` | Durable admission succeeded; the record waits for an inspection slot. |
| `RUNNING` | The existing validator is executing. A phase may be unavailable. |
| `COMPLETED` | A report was produced. Read `overallStatus`, `complete`, and `exitCode` for the validation result. |
| `FAILED` | The Server could not produce a report. |
| `TIMED_OUT` | The configured inspection time limit elapsed. |
| `CANCELLED` | The owner requested Server-side cancellation. |
| `INTERRUPTED` | A Server shutdown interrupted the validation. |

The report result remains `PASS`, `INVALID`, or `PARTIAL`. Only `overallStatus: PASS`, `complete: true`, and `exitCode: 0` represent a pass. The API sets `applicability` to `UNVERIFIED`; a historical pass is not evidence that current source still matches it. `sourceRevision` is included only when the validator supplies a safe revision digest.

By default, records are retained for seven days. Configure `server.validationRecords.enabled`, `retentionDays`, `maxRecords`, `maxTotalSizeMb`, and `maxRecordBytes` in `att-server.yaml` to set retention and storage caps. The defaults are enabled, `7`, `5000`, `64` MiB, and `2097152` bytes per record. If `maxTotalSizeMb` is lower than the default per-record limit, the per-record default is reduced to fit the total cap. Expired or oldest terminal records are removed to stay within configured caps; active records are not evicted. If the record store has no room, admission returns `503`. When the feature and inspection Worker are enabled, `/api/v1/version` advertises `validationRecordsApiVersion: "att-validation-records/v1"`.

On Server startup, records left in `QUEUED` or `RUNNING` become `INTERRUPTED`. Completed records remain available to the same authenticated owner until retention or storage cleanup removes them. Keep the record ID in a route or bookmark to reopen its report after navigation or browser reload.
