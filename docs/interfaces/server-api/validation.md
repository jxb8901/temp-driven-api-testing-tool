<!-- att-doc-status: planned release=unreleased issue=287 -->

# Proposed Server Validation API

> **Planned, not released.** This proposed route is not part of ATT 4.2.6. Use the current [REST API v1](v1.md) route `POST /api/v1/jobs/validate` for path-based validation; it submits an asynchronous Job.

## Endpoint

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/v1/validation` | Synchronous, read-only `att-validation/v1` report; does not create a Job. |

The endpoint runs Server-side validation through the bounded inspection Worker. It does not submit a RUN/LOAD Job or perform business I/O.

## Request

Send `Content-Type: application/json`.

Allowed fields: `command`, `targetKind`, `target`, `packageId`, `environment`, `all`.

| Field | Contract |
|---|---|
| `command` | Required and must be `validate`. |
| `targetKind` | Optional; defaults to `config`. Supported scopes are `config`, `package`, `case`, `suite`, `template`, `flow`, `tool`, and `load`. |
| `target` | Required for `case`, `suite`, `template`, `flow`, `tool`, and `load`. Use a logical Case/Resource ID or a Package-relative Suite/Load path. It is not accepted for `config` or `package`. |
| `packageId` | Required for `package` and selected-target scopes. Optional for `config`; when present, it selects a registered Package. |
| `environment` | Optional Environment Profile for Package-scoped validation. The Server applies its configured allow policy. |
| `all` | Optional boolean. When `true`, validate the installation-wide scope. Do not combine it with `targetKind`, `target`, `packageId`, or `environment`. |

The Server owns Runtime Config. Clients cannot send a Config file path or arbitrary request fields. A Config check without `packageId` inspects Server Runtime Config; with a registered `packageId`, it checks the selected Package's effective Config. Selected-target and Package checks require a registered Package ID.

Example: validate the effective Config for the `payments` Package in `SIT`:

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

A completed validation returns HTTP `200`, including when the validation report records failures. The JSON report follows the [att-validation/v1 schema](../../../schemas/att-validation-v1.schema.json) and includes:

| Field | Meaning |
|---|---|
| `schemaVersion` | Constant `att-validation/v1`. |
| `scope`, `backend` | Requested scope and `SERVER`. |
| `sections`, `diagnostics` | Hierarchical status sections and flattened diagnostics. |
| `counts` | `pass`, `fail`, `warn`, `skip`, `errors`, and `warnings` totals. |
| `overallStatus`, `complete`, `exitCode` | Overall `PASS`, `INVALID`, or `PARTIAL`; completeness; CLI-compatible result code. |
| `context` | Optional validation context. |
| `requestId` | Server request identifier for tracing. |

Validation findings are in the report body; an HTTP `200` does not mean that every section passed. No Job ID is returned.

## Authorization and errors

Every request requires an authenticated Server principal. Scoped checks require operational access (the configured Server operator role or an active local USER account); `all: true` requires system-administrator capability. The endpoint also applies the Server's Origin/CSRF policy and configured request/inspection response limits.

| HTTP status | Meaning |
|---|---|
| `400` | Malformed JSON, unsupported fields, or an invalid validation plan. |
| `401` | Authentication is required. |
| `403` | Operator/system-administrator access or Environment authorization is denied. |
| `404` | Registered Package or selected validation target is not found. |
| `413` | Request or report exceeds its configured size limit. |
| `415` | Request is not JSON. |
| `500` | Sanitized validation failure; server stack traces are not returned. |
| `503` | Inspection Worker capacity is full. |
| `504` | Read-only validation exceeds its time limit. |

These routes and behaviors remain Planned until #287 is released and the published API contract is verified.
