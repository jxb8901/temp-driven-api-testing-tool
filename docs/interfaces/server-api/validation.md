<!-- att-doc-status: current release=4.2.10 -->

# Server Validation API

`POST /api/v1/validation` runs the existing Server-side validator synchronously and returns an `att-validation/v1` report. Studio uses this bounded, read-only route to validate Server Runtime Config, a registered Package, or one selected resource without creating an execution draft or Job. The Server remains the only authority for validation, authorization, effective configuration, and resource revisions.

## Endpoint and capability

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/v1/validation` | Synchronous `att-validation/v1` report; no Job is created. |

`GET /api/v1/version` advertises `validationApiVersion: "att-validation/v1"` when the Server supports this route and report contract. Studio checks that marker before enabling **Read-only validation**. An absent or different marker means the Server build does not advertise the required capability.

The route delegates to the existing Server validator through a bounded inspection Worker. It does not execute business actions, submit RUN/LOAD work, create or persist a draft, create a Schedule, or save a snapshot. Canceling in Studio aborts the browser request and discards any late response; the Server still applies its configured validation time and resource bounds.

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
