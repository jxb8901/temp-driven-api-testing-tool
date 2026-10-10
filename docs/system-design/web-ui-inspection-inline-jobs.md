# ATT Web UI inspection and inline job contracts

Status: Issue #176 P0 contract baseline. P1 implements authenticated package resource discovery, safe projections, encrypted pagination, and the Package Resource Explorer. Configuration views, drafts, resource-scoped Debug, Quick Load, and Advanced Load remain follow-up phases.

## Purpose and scope

ATT Web UI adds package-resource inspection, read-only configuration views, resource-scoped Debug and Quick Load forms, and a multi-workload Load builder. The browser remains a client of authenticated public Server APIs. The Server remains the authority for resource resolution, visibility, validation, admission, and job creation.

This design preserves the existing package-relative job API and CLI behavior. It adds typed public contracts rather than exposing the internal Worker request or a general file browser.

## Decisions

1. **Keep the public API additive under `/api/v1`.** New inspection, draft, and inline-job endpoints use dedicated DTOs with strict unknown-field rejection. Existing path-based job requests retain their current behavior for compatibility.
2. **Address resources by logical identity.** A resource ID is package-scoped and identifies a Case, Template, Flow, or Tool. It never contains a host path. Case identity includes its logical suite, group, Case ID, and row provenance; other resource identities use their ATT IDs.
3. **Reuse Engine semantics.** Engine code owns Excel, Template, Flow, Tool, config, Debug sidecar, and Load parsing. Browser code renders typed projections; it does not parse ATT YAML or reproduce Load semantics.
4. **Return safe projections only.** Inspection APIs do not return unrestricted files or resolved secrets. Source and configuration output is allowlisted and redacted on the Server.
5. **Validate immutable drafts before submission.** The Server issues an opaque draft ID bound to the authenticated principal, package, environment, target closure, and normalized input. Submission references that draft instead of resending editable input.
6. **Keep new Debug and Load inputs in memory.** The Server sends typed values through the existing Worker JSON-lines request. It does not write generated inputs under the package root or accept client-supplied physical paths.
7. **Require explicit Server-side Load limits.** Inline Load is disabled until deployment configuration enables it and defines workload, rate, concurrency, and duration caps. Existing Server queue and concurrent-Load admission limits continue to apply.

## API boundary

The routes below define the public v1 contract. P1 implements the first three resource discovery routes and typed response DTOs in `att-server-api`; the `debug-form` route and configuration, draft, and inline-job routes remain future work. The REST layer must not deserialize new UI requests directly into `att-worker.WorkerRequest`.

### Resource discovery

```http
GET /api/v1/packages/{packageId}/resources?type=flow&query=payment&limit=50&cursor=...
GET /api/v1/packages/{packageId}/resources/{kind}/{resourceId}
GET /api/v1/packages/{packageId}/resources/{kind}/{resourceId}/source
GET /api/v1/packages/{packageId}/resources/{kind}/{resourceId}/debug-form?environment=SIT
```

List responses carry typed resource summaries, safe relationship references, and an optional encrypted continuation cursor. A cursor is bound to the authenticated principal, package, filters, offset, and private package revision digest. The digest is encrypted inside the cursor and never returned as a response field. The default page size is 50; the maximum is 100. If package content changes between pages, the Server rejects the stale cursor and the Explorer can refresh the list.

Details expose only fields supported by the resource type. Case details retain logical suite, group, Case ID, row or sheet provenance, tags, and mapped input/expected-value metadata. Template, Flow, and Tool details expose their parsed definitions and resolvable references. Unresolved references are diagnostics. Only Case details provide Run. Only Template, Flow, and Tool details provide Debug and Quick Load.

Source responses are generated from a parsed, redacted projection. They are not arbitrary file reads. A Tool script is available only when its exact package-relative path appears in the server-side `server.inspection.safeTextSources` allowlist. Binary-only or unapproved source is reported as unavailable. A source response is limited to 64 KiB; a detail response is limited to 256 KiB; larger content is unavailable with a size-limit state. The private package-content digest is used to detect stale cursors and is never returned.

### Configuration inspection

```http
GET /api/v1/packages/{packageId}/configuration?view=declared
GET /api/v1/packages/{packageId}/configuration/effective?environment=SIT
GET /api/v1/packages/{packageId}/configuration/compare?left=SIT&right=UAT
```

The Engine's `FrameworkConfigLoader` selects and resolves the environment. Inspection makes no database, MQ, HTTP, or SSH connection. Declared and effective views are separate. The comparison contains only fields authorized for display. A hidden value is represented as hidden, never as equal, changed, a digest, or a value length.

### Draft, preview, validation, and submission

```http
POST /api/v1/drafts/debug
POST /api/v1/drafts/load
GET  /api/v1/drafts/{draftId}
POST /api/v1/jobs/debug
POST /api/v1/jobs/load
```

Draft creation validates the full typed request and its selected target closure. It returns a safe preview, validation diagnostics, the package revision, and an opaque draft ID. The draft store is in memory, bounded to 128 active drafts across the Server and 16 per authenticated principal, and expires drafts after 10 minutes. Server restart invalidates all drafts; clients must rebuild and revalidate them.

A draft captures normalized values and an internal content digest for the target, sidecars, config, and referenced resource closure. The digest never leaves the Server. The Server checks the closure again when the draft is submitted and immediately before Worker start. A changed closure rejects the draft with HTTP 409 and `ATT-SERVER-DRAFT-STALE`. The submitted Worker input is the same immutable typed value validated for preview.

Debug and Load job submission accepts `packageId` and `draftId`; it does not accept an arbitrary file path. Load submission also requires `confirmed: true` as an explicit UI acknowledgement. This acknowledgement is not an authorization boundary and does not replace server-side limits. Existing path-based `debugInput` and `scenario` submissions remain available to compatible clients.

Example Debug draft request:

```json
{
  "packageId": "payments",
  "environment": "SIT",
  "target": { "type": "flow", "id": "PAYMENT.submit" },
  "input": {
    "inputs": { "channel": "WEB" },
    "vars": { "reference": "REF001" }
  }
}
```

Example Load draft request:

```json
{
  "packageId": "payments",
  "environment": "SIT",
  "scenario": {
    "schemaVersion": "att-load/v1.6",
    "load": { "warmup": "5s", "rampUp": "10s", "duration": "1m", "rampDown": "5s" },
    "workloads": [
      {
        "id": "payment",
        "load": { "users": 10 },
        "target": { "type": "flow", "id": "PAYMENT.submit" },
        "inputs": { "channel": "WEB" }
      }
    ]
  }
}
```

The Load draft response includes the complete normalized scenario when every field is visible. If visibility policy masks any field, the response marks the preview as redacted and non-runnable; the UI must not present that redacted text as an executable export. The Server still validates and executes its immutable private draft.

## Typed Debug and Load rules

Debug is available only from a selected Template, Flow, or Tool detail and contains one fixed target. Template and Flow forms use typed `inputs` and `vars`; Tool forms use typed `arguments` and reject `vars`. The form reads safe defaults from the package-authored `debug.yaml` when present. When absent, the Server creates valid `att-debug/v1.2` defaults in memory. Sidecars remain unchanged. Expression strings and native scalar, map, and list values are preserved; the browser does not evaluate them.

Quick Load uses one fixed selected target and composes one Workload. It reads `load.visualuser.yaml` or `load.arrivalrate.yaml` as a read-only package policy when present, otherwise it uses the bundled policy fallback. It does not require a stored Debug input or Load scenario. Advanced Load selects VU or Arrival Rate before adding Workloads. VU supports single-target or weighted-mix Workloads. Arrival Rate supports multiple single-target Workloads and rejects mixes. Every Workload uses the same model and timing envelope. Debug-only fields and Debug-local Testdata are not copied into Load; users configure supported Load-level fields explicitly.

## Worker and Engine data flow

```text
authenticated browser
  -> strict public DTO
  -> Server resolves package ID and logical resource IDs
  -> Engine parses, projects, and validates
  -> bounded in-memory draft bound to principal and package revision
  -> Server validates draft and admission limits again
  -> typed Worker JSON-lines request
  -> Engine executes through the existing service and scheduler
```

The Server alone supplies the package root, output directory, Worker executable, and classpath. Public DTOs cannot set them. The Worker protocol gains explicit typed inline Debug and Load fields; it does not accept arbitrary external paths for those fields. Engine input objects are immutable copies. Debug defaults use the existing `DebugEngine` sidecar rules, with blank typed defaults when a sidecar is absent. Load scenarios use the current `att-load/v1.6` schema and `LoadScenarioLoader` semantic checks.

Job metadata continues to store the command and package ID summary, not the submitted input or draft contents. Drafts are not persisted. Temporary values exist only in Server and Worker memory and are cleared on expiry, cancellation, terminal completion, or Server shutdown.

## Visibility and redaction policy

| Data | Default response | Additional rule |
| --- | --- | --- |
| Logical resource IDs, types, safe names, tags, and relationships | Visible to an authenticated principal | Package-scoped; no absolute paths |
| Case row and sheet provenance | Visible as logical identifiers | Never include the workbook's physical path |
| Template, Flow, and Tool definitions | Allowlisted structured projection | Sensitive fields are replaced before serialization |
| Tool script text | Unavailable | Available only under explicit server-side package/resource allowlisting and source-size cap |
| Declared or effective configuration | Allowlisted projection | No unrestricted YAML; use the real Engine resolution |
| Passwords, tokens, private keys, credentials, resolved environment values, auth headers | Always hidden | No preview, diagnostic, event, cache, or export may contain bytes |
| Testdata records and resolved values | Never returned | Descriptor IDs, schema, and permitted selection metadata may be shown |
| Hostnames, ports, JDBC URLs, endpoints, queues, network topology | Hidden by default | A Server-side visibility policy may allow selected fields for all authenticated principals; the browser cannot grant visibility |
| Cross-profile comparison of hidden values | Hidden state only | Do not reveal equality, hashes, lengths, or change counts |
| User-entered Debug/Load values | Safe typed preview | Apply the same sensitive-field rules; mark the preview redacted when any field is hidden |

Redaction runs before JSON or YAML serialization. Errors contain stable codes and safe summaries, not source contents, credentials, absolute paths, or parser snippets that include them. Browser rendering treats all returned strings as text.

## Resource isolation, bounds, and admission

Resource discovery is delegated to one-shot inspector Worker processes through a bounded dispatcher, not performed on Tomcat request threads. The inspector is read-only and does not invoke Tools. Initial `server.inspection` defaults are `maxConcurrent: 2`, `queuedLimit: 16`, `timeoutMs: 30000`, `heapMaxMb: 512`, `maxResponseBytes: 262144`, and `maxSourceBytes: 65536`. These bounds do not inherit the general job queue limits. Timeout or output-limit violations terminate the inspector Worker. The inspector reuses `PackageResourceResolver` for package confinement and rejects traversal and external symlinks. Results are lazy and paginated.

New inspection and draft endpoints always require a Servlet Principal, even when a deployment enables anonymous access for the legacy API. Unknown packages and resources return the same not-found shape. New DTOs reject unknown fields and ambiguous combinations. Existing requests remain backward compatible.

Inline Load is disabled by default. Enabling `server.inlineLoad.enabled` requires positive server-owned maxima for `maxWorkloads`, `maxTargets`, `maxTotalUsers`, `maxAggregateArrivalRatePerSecond`, `maxConcurrentPerWorkload`, and `maxDurationSeconds`. The Server enforces these caps after Engine validation and before queue admission. VU and Arrival Rate cannot mix; Arrival Rate cannot contain a weighted mix; workload timing envelopes must match; Engine validation remains authoritative. The existing `maxConcurrentLoad` and general Worker queue limits still apply.

## Error behavior

New endpoints use the existing `error` envelope and stable codes:

| HTTP status | Code | Meaning |
| --- | --- | --- |
| 400 | `ATT-SERVER-INVALID-REQUEST` | Invalid JSON, unknown field, invalid logical ID, schema error, or semantic error |
| 401 | `ATT-SERVER-AUTHENTICATION-REQUIRED` | No authenticated Servlet Principal |
| 404 | `ATT-SERVER-NOT-FOUND` | Package, resource, or draft is unavailable |
| 409 | `ATT-RESOURCE-CURSOR-STALE` | Package content changed while listing resources |
| 413 | `ATT-SERVER-REQUEST-TOO-LARGE` | Request body exceeds its configured size limit |
| 413 | `ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE` | Projected inspection response exceeds its configured size limit |
| 429 | `ATT-SERVER-CAPACITY-EXCEEDED` | Normal job admission limit reached |
| 503 | `ATT-SERVER-INSPECTION-CAPACITY` | Bounded inspection queue is full |
| 504 | `ATT-SERVER-INSPECTION-TIMEOUT` | Inspector Worker exceeded its time limit |

Validation failures include structured field diagnostics with logical resource IDs. They never include source lines or physical paths. An oversized projected source is returned as unavailable with a size-limit state, not as a partial file. New request bodies also use the existing `maxRequestBytes` cap.

## Implementation and verification contract

| Phase | Delivery boundary | Required evidence |
| --- | --- | --- |
| P0 | This public contract, visibility matrix, data flow, and security design | API examples and test matrix reviewed; no unresolved path, secret, or draft-consistency decision |
| P1 | Bounded Package Resource Resolver adapter, public discovery DTOs/routes, Package Explorer | All resource types, references, provenance, pagination, stale refresh, auth, size, traversal, symlink, and redaction coverage |
| P2 | Declared/effective configuration and profile comparison | Environment selection matches `FrameworkConfigLoader`; hidden fields do not leak through values or comparisons |
| P3 | Typed Debug form and inline Debug execution | Sidecar and no-sidecar paths for Template, Flow, and Tool; Worker isolation; package unchanged |
| P4 | Quick Load and shared typed scenario composer | VU and Arrival Rate templates/fallbacks; one-workload execution without stored Debug or Load files |
| P5 | Advanced multi-workload builder and inline Load execution | VU mix and single-target scenarios, Arrival Rate multi-single-target scenarios, invalid model combinations, preview-to-run identity, limits, SSE, and cancellation |
| P6 | Security, compatibility, docs, packaging, and rollout gate | Full acceptance matrix, EN/ZH user docs, Java 8/17 compatibility, packaged WAR, and hosted CI |

Each phase is a separate review PR. P1 and P3 form the critical discovery/Debug path; P2 may proceed after the shared inspection projection exists. P4 reuses P3 input projection. P5 reuses P4 composition and the inline Load contract. P6 closes the full #176 acceptance criteria.

The test plan must cover contract serialization and strict field rejection; package containment, symlink, encoded traversal, and source-size limits; authorization; malformed and stale resources; secrets in source, errors, previews, logs, and diffs; concurrent principals and drafts; server restart expiry; package mutation between preview and submit; Worker input isolation; both Load models and all Engine semantic restrictions; queue admission, cancellation, and package immutability. These are implementation gates, not claims that tests already exist.
