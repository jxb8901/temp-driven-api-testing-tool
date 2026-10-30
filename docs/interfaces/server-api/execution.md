<!-- att-doc-status: current release=4.2.7 -->

# RUN and LOAD APIs

## Target-scoped Debug and drafts

Debug form, Quick Load form, Load policy, and draft endpoints use the same resolved identity as the rest of the API. In anonymous mode, drafts created by one caller are available to every other anonymous caller. Use a resource ID from the Package Resource Explorer; Cases cannot be Debug or Quick Load targets.

```http
GET /api/v1/packages/payments/resources/flow/{resourceId}/debug-form?environment=SIT
```

The response contains the selected resource, fixed logical target, safe `att-debug/v1.2` input defaults, a `redacted` flag, and `requestId`. A missing sidecar produces valid empty defaults. Sensitive fields are redacted before the response.

Validate the typed input to create a server-issued draft:

```http
POST /api/v1/drafts/debug
Content-Type: application/json

{"packageId":"payments","environment":"SIT","target":{"type":"flow","id":"PAYMENT.submit"},"input":{"inputs":{"channel":"WEB"},"vars":{"reference":"REF001"}}}
```

The response includes an opaque `draftId`, safe YAML preview, validation diagnostics, and expiry. `GET /api/v1/drafts/{draftId}` returns the same safe preview only to its owning resolved identity. Drafts live in memory for up to 10 minutes, with a limit of 128 active drafts per Server and 16 per identity. In anonymous mode every caller shares that identity and draft limit. Restart invalidates drafts. The Server never returns the internal package revision digest.

Submit only the draft identity:

```http
POST /api/v1/jobs/debug
Content-Type: application/json

{"packageId":"payments","draftId":"D0123456789ABCDEF0123456789ABCDEF"}
```

The Server rechecks the package revision at submission and before Worker start. A change detected during submission returns `409 ATT-SERVER-DRAFT-STALE`; a change detected after job acceptance marks the job `INVALID` with that diagnostic. Validation failures return a top-level `diagnostics` array with safe `code`, `summary`, `field`, and logical `resourceId` values; source paths and parser snippets are omitted. Worker execution receives the same immutable typed values validated for the preview; inline values remain in memory and do not create files under the package root. Existing path-based Debug requests remain supported for compatible clients.

## Target-scoped Quick Load

Quick Load is available from a selected Template, Flow, or Tool when `server.inlineLoad.enabled` is true. It uses one fixed target and creates one Workload. The model is `virtualUsers` or `arrivalRate`; the Server composes the matching current `att-load/v1.9` policy and validates the target through the existing Load pipeline. When disabled, both Quick Load form/policy reads and draft creation return `403 ATT-SERVER-INLINE-LOAD-DISABLED`.

```http
GET /api/v1/packages/payments/resources/flow/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT
```

The form response includes safe `inputs` and `vars` defaults for Template/Flow targets, or `inputs` and `arguments` for a Tool, plus a redacted one-workload preview. It reads only the business fields from the optional target `debug.yaml`; Debug-only `case`, `stage`, and local `testdata` are not copied. When a sidecar has Debug-local Testdata, the form reports that those imports were omitted. Supply any needed package-relative descriptors in the separate Load-level `testdata` array; these paths are validated as part of the effective Load scenario.

The Server reads `load/load.visualuser.yaml` for `virtualUsers` and `load/load.arrivalrate.yaml` for `arrivalRate`. These are policy-only `att-load/v1.9` descriptors; the selected policy must match the chosen model. If a model-specific file is absent, a bundled low-intensity fallback is used: one Virtual User for 10 seconds, or 1 arrival per second for 10 seconds with `maxConcurrent: 1` and `overloadPolicy: drop`. Existing CLI `load/load.yaml` behavior is unchanged.

Validate business values and pacing overrides to create an owned draft:

```http
POST /api/v1/drafts/quick-load
Content-Type: application/json

{"packageId":"payments","environment":"SIT","target":{"type":"flow","id":"PAYMENT.submit"},"model":"virtualUsers","input":{"inputs":{"channel":"WEB"},"vars":{"reference":"REF001"}},"load":{"users":4,"duration":"30s"},"execution":{"thinkTime":"100ms"},"testdata":["testdata/load-accounts.yaml"]}
```

`load` accepts only pacing fields supported by the selected model. `execution` accepts the optional closed-workload `thinkTime`. `testdata` is an optional array of additional package-relative Load descriptor paths; it is kept separate from Debug-local imports and the model policy's existing descriptors. The response returns an identity-bound opaque `draftId` beginning with `L`, the safe effective YAML preview, redaction state, and expiry. Debug and Quick Load drafts share the 128-active-per-Server, 16-per-identity, 10-minute in-memory limits.

After reviewing the preview, submit only the draft identity:

```http
POST /api/v1/jobs/load
Content-Type: application/json

{"packageId":"payments","draftId":"L0123456789ABCDEF0123456789ABCDEF"}
```

The Server checks the package revision at draft submission, before Worker start, and again in the Worker. A stale draft returns `409 ATT-SERVER-DRAFT-STALE`, or marks an already accepted job `INVALID` with that diagnostic. The Worker receives the exact immutable scenario validated for preview; it stays in memory and is not written into the package. Existing path-based Load requests remain supported.

## Advanced Load builder

Advanced Load creates a full `att-load/v1.9` scenario with one or more workloads when `server.inlineLoad.enabled` is true. The selected `model` must be `virtualUsers` or `arrivalRate` for every workload. Virtual Users workloads can use one fixed target or a weighted `mix`; Arrival Rate workloads use one fixed target each. The Engine validates the shared timing envelope, workload intensity, thresholds, Testdata policies, and every target before the Server creates a draft. The Server then enforces its configured inline Load caps before returning the preview. Exceeding a cap returns `400 ATT-SERVER-INLINE-LOAD-LIMIT` with a safe summary; the Server does not schedule the scenario.

Read safe model policy defaults with:

```http
GET /api/v1/packages/payments/load-policy?model=virtualUsers&environment=SIT
```

The response contains `model`, a redacted `policy`, its safe `previewYaml`, a `redacted` flag, and `requestId`. This endpoint is read-only and does not create a draft.

Validate a complete scenario to create a server-issued draft:

```http
POST /api/v1/drafts/load
Content-Type: application/json

{
  "packageId": "payments",
  "environment": "SIT",
  "scenario": {
    "schemaVersion": "att-load/v1.9",
    "load": {"users": 1, "duration": "30s"},
    "workloads": [
      {"id": "browse", "target": {"type": "flow", "id": "PAYMENT.browse"}, "load": {"users": 4}},
      {"id": "submit", "target": {"type": "template", "id": "PAYMENT.submit"}, "load": {"users": 2}}
    ]
  }
}
```

The Server validates the full scenario and resolves every target without scheduling traffic. It restores redacted values only from each selected target's package-local `debug.yaml` business fields; Debug `case`, `stage`, and local Testdata are excluded. The response contains an opaque draft ID beginning with `A`, a safe effective YAML preview, the selected model, a redaction flag, and expiry. Advanced Load shares the 128-active-draft Server limit, 16-draft per-identity limit, and 10-minute expiry with Debug and Quick Load.

Submit only the package and draft ID to `POST /api/v1/jobs/load`. The Server checks the package revision at submission and before Worker start, then passes the same immutable normalized scenario validated for preview through the existing Worker, Engine, scheduler, event, and evidence paths. A stale draft returns `409 ATT-SERVER-DRAFT-STALE`; a change detected after job acceptance marks the job `INVALID`. The browser disables YAML copy and export when any preview field is redacted. Existing path-based Load requests remain supported.
