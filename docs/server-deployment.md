# ATT Server deployment and API

ATT Server 4.1.0 provides a single-node control plane for submitting ATT Run, Debug, Load, and Validate jobs over a versioned REST API. Deploy its WAR to an external Tomcat 10.1+ instance running Java 17 or later. The CLI, Engine, and Worker remain Java 8-compatible.

## Prepare the deployment

1. Create a server-owned data directory, for example `/var/lib/att-server`, writable only by the Tomcat service account. Keep it separate from ATT installation files and package roots.
2. Create package directories under an allowed root and ensure the Tomcat service account can read them.
3. Copy `config/att-server.example.yaml` to a protected location, update its absolute paths, and keep credentials out of the file and package content.
4. Set `-Datt.server.config=/etc/att/server.yaml` in Tomcat's Java options. The `ATT_SERVER_CONFIG` environment variable is also accepted when the system property is absent.
5. Deploy `att-server-4.1.0.war` to Tomcat 10.1+. Keep Tomcat's `unpackWARs` enabled so the Server can launch the Worker from `WEB-INF/lib`.

Tomcat owns listeners, TLS, access logs, and authentication. The WAR uses the Servlet container's configured authentication mechanism on package and job APIs and metrics, and preserves the container's challenge or redirect when authentication fails. Any authenticated Servlet Principal has the same API permissions in v1; no `ATT_USER` role assignment or ATT-specific RBAC is required. Health and version remain public. Terminate TLS before exposing BASIC credentials. The Server fails startup when its configuration, Java baseline, or package mappings are invalid.

## Configuration

`server.dataDir` stores H2 control-plane metadata and job output. Worker concurrency, queue size, Load admission, graceful stop timeout, and optional per-Worker heap limits are bounded by `workers`. `workers.heapMaxMb` sets `-Xmx` for every Worker (64–65536 MiB); optional `heapInitialMb` sets `-Xms` (32–65536 MiB), requires `heapMaxMb`, and cannot exceed it. Plan the aggregate heap allowance against `maxConcurrent` plus the Server and container memory. Rejected submissions are discarded before they create durable job records. Terminal job metadata, journals, and artifacts are retained for `server.jobRetentionDays` (default 30, allowed range 1–3650); expired jobs are cleaned at startup and hourly, while active jobs are preserved. `workers.maxConcurrentLoad` bounds admitted Load jobs, including queued jobs, so waiting Loads do not occupy general Worker threads. Excess Load or overall-capacity submissions receive HTTP 429. `workers.libraryDirs` optionally lists absolute, existing, readable directories whose JARs are added to the Worker subprocess classpath for external JDBC, MQ, or other dependencies; configure only trusted server-owned directories. The `packages` registry is read-only and maps stable package IDs to canonical roots beneath `allowedRoots`. `server.inspection` separately bounds read-only resource discovery; it uses one-shot Workers and has its own concurrency, queue, timeout, heap, source, and response limits. Tool script text is unavailable unless its package-relative path is listed under `server.inspection.safeTextSources` for that package ID.

Browser-created Quick and Advanced Load drafts have a separate opt-in: `server.inlineLoad.enabled` defaults to `false`. When enabled, the Server enforces the configured workload, target, total Virtual User, aggregate Arrival Rate, per-workload and aggregate concurrency, and total timing-envelope caps after Engine validation and again before queue admission. The [Server configuration example](#configuration) sets conservative defaults. The duration cap is the sum of warmup, ramp-up, measured duration, and ramp-down; target count includes each fixed target and each VU mix entry. A disabled feature returns `403 ATT-SERVER-INLINE-LOAD-DISABLED`. These limits apply to browser-created drafts; existing path-based Load submissions retain their compatibility behavior and continue to use `workers.maxConcurrentLoad` admission.

```yaml
server:
  dataDir: /var/lib/att-server
  authenticationRequired: true
  jobRetentionDays: 30
  inspection:
    enabled: true
    maxConcurrent: 2
    queuedLimit: 16
    timeoutMs: 30000
    heapMaxMb: 512
    maxResponseBytes: 262144
    maxSourceBytes: 65536
    # Optional Tool script source allowlist. Paths are package-relative.
    # safeTextSources:
    #   payments:
    #     - tools/payment-check.sh
  inlineLoad:
    enabled: false
    maxWorkloads: 10
    maxTargets: 20
    maxTotalUsers: 100
    maxAggregateArrivalRatePerSecond: 100
    maxConcurrentPerWorkload: 100
    maxDurationSeconds: 3600
workers:
  maxConcurrent: 8
  queuedLimit: 100
  # Maximum admitted Load jobs, queued or executing.
  maxConcurrentLoad: 2
  gracefulStopMs: 10000
  # Optional bounds applied separately to each Worker subprocess.
  # heapInitialMb: 256
  # heapMaxMb: 1024
  # Optional external Worker dependency JAR directories.
  libraryDirs:
    - /opt/att/worker-libs
packages:
  allowedRoots:
    - /srv/att/packages
  entries:
    payments: /srv/att/packages/payments
```

Inline Load settings use these defaults and hard ranges:

| Setting | Default | Allowed range | Enforcement |
| --- | ---: | ---: | --- |
| `maxWorkloads` | 10 | 1–128 | Workloads per draft |
| `maxTargets` | 20 | 1–256 | Fixed targets plus VU mix entries |
| `maxTotalUsers` | 100 | 1–100,000 | Sum of Virtual Users across workloads |
| `maxAggregateArrivalRatePerSecond` | 100 | greater than 0 to 1,000,000 | Sum of arrival rates normalized to requests per second |
| `maxConcurrentPerWorkload` | 100 | 1–1,000,000 | Each Arrival Rate workload |
| `maxTotalConcurrent` | 1,000 | 1–1,000,000 | Sum of `maxConcurrent` across Arrival Rate workloads |
| `maxDurationSeconds` | 3,600 | 1–86,400 | Sum of warmup, ramp-up, duration, and ramp-down |

`dataDir` must be absolute. Package roots, allowed roots, and configured Worker library directories must exist at startup. Package paths are canonicalized; symlinks that resolve outside an allowed root are rejected. Clients submit `packageId`; they cannot select a package path, output path, Worker executable, or classpath. Package registration and mutation endpoints are not available in v1.

Server state is stored in `dataDir/db/`. Each job uses `dataDir/jobs/<jobId>/` for its bounded event journal and execution output. A restarted Server marks old queued or active jobs `ERROR` with `ATT-SERVER-INTERRUPTED`; it does not rerun them.

## Authentication and identity

Tomcat authenticates requests. ATT Server reads `HttpServletRequest.getUserPrincipal()` and requires a Principal on package, resource-inspection, Debug/Quick Load draft, job, result, event, and artifact endpoints. The new resource-inspection and Debug/Quick Load draft endpoints always require a real Servlet Principal, even when anonymous access is enabled for the legacy API. Health and version may be anonymous. The principal name is stored with job and audit metadata and is not sent to the Worker or exposed in ATT expression Context.

State-changing requests require `application/json`; requests carrying an `Origin` must match the request origin. Behind a TLS-terminating proxy, configure Tomcat's `RemoteIpValve` to derive the Servlet scheme, host, and port from the proxy's forwarded headers. Set `internalProxies` to only the actual proxy addresses, and ensure the proxy removes client-supplied `Forwarded`/`X-Forwarded-*` headers before adding its own. For example, adapt these header names and trusted addresses to the proxy:

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="10\\.20\\.0\\.12"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       hostHeader="X-Forwarded-Host"
       portHeader="X-Forwarded-Port" />
```

All authenticated Servlet Principals have the same permissions in v1; ATT does not require a particular container role or provide ATT-specific RBAC. For an intentionally isolated network, set `server.authenticationRequired: false` to accept anonymous API requests; this bypasses authentication for every API operation and should not be used on an untrusted network. ATT Server does not implement passwords, JWT/OIDC validation, LDAP authentication, or login flows. The Servlet container owns authentication challenges and redirects. Do not put credentials in API payloads or package files.

The bundled browser console is served at `<context path>/ui/` and uses the same container-managed authentication policy as the API. See [ATT Server Web UI](att-server-web-ui.md).

## REST API

All endpoints use `/api/v1`. Requests and responses use JSON unless the endpoint is an SSE stream or artifact download.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health`, `/version` | Health, build, and browser Load capability information |
| `GET` | `/metrics` | Bounded job and Worker counts |
| `GET` | `/packages`, `/packages/{packageId}` | Read the configured registry |
| `GET` | `/packages/{packageId}/resources?type=case&query=...&limit=50&cursor=...` | List safe Case, Template, Flow, and Tool projections |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}` | Read one safe resource definition and references |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/source` | Read a redacted YAML projection or allowlisted Tool script |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/debug-form?environment=SIT` | Read safe target-scoped Debug defaults |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT` | Read safe defaults and one-workload Quick Load preview |
| `GET` | `/packages/{packageId}/load-policy?model=virtualUsers&environment=SIT` | Read safe model-specific Load policy defaults for Advanced Load |
| `POST` | `/drafts/debug` | Validate a fixed-target Debug input and create an in-memory draft |
| `POST` | `/drafts/quick-load` | Validate a fixed-target Quick Load and create an in-memory draft |
| `POST` | `/drafts/load` | Validate an `att-load/v1.6` scenario and create an in-memory draft |
| `GET` | `/drafts/{draftId}` | Read the safe preview for an owned Debug, Quick Load, or Advanced Load draft |
| `POST` | `/jobs/debug`, `/jobs/load` | Submit an owned Debug, Quick Load, or Advanced Load draft |
| `POST` | `/jobs/run`, `/jobs/load`, `/jobs/validate` | Submit one path-based job |
| `GET` | `/jobs`, `/jobs/{jobId}` | List recent jobs or read job status |
| `GET` | `/jobs/{jobId}/result` | Read the canonical result and diagnostic |
| `GET` | `/jobs/{jobId}/events` | Observe retained and live job events |
| `GET` | `/jobs/{jobId}/artifacts` | List output files |
| `GET` | `/jobs/{jobId}/artifacts/{path}` | Download one output file |
| `DELETE` | `/jobs/{jobId}` | Cancel an active job; retain its history |

Submit a job with a stable package ID and command-specific ATT Worker fields:

```http
POST /api/v1/jobs/run
Content-Type: application/json

{"packageId":"payments","all":true}
```

The API returns `202 Accepted` and a job ID. Jobs move through `QUEUED`, `PREPARING`, and `RUNNING`, then finish as `PASS`, `FAIL`, `ERROR`, `INVALID`, or `CANCELLED`. Capacity overflow returns `429` with an `ATT-SERVER-CAPACITY-EXCEEDED` error. Errors use an `error` object with `code`, `summary`, `detail`, and `requestId`; server stack traces are not returned.

Browser Quick and Advanced Load endpoints return `403 ATT-SERVER-INLINE-LOAD-DISABLED` until `server.inlineLoad.enabled` is enabled. A scenario that exceeds an enabled cap returns `400 ATT-SERVER-INVALID-REQUEST` and is not queued.

Job records include an additive `performance` object when measurements are available. `performance.timings` uses monotonic elapsed time for admission, queue wait, Worker preparation/spawn, Worker-ready (first `STATUS`), execution-ready (first `PROGRESS` or `LOG`), Worker lifetime, and result-to-termination. `performance.worker` records heap, live-thread, GC, process CPU, and sampled peak RSS metrics from the isolated Worker. Sampling is event-triggered and limited to one sample per 100 ms; brief peaks can be missed. RSS is available on Linux `/proc` systems only. Worker resource metrics are included in the canonical job record and survive Server restart.

`GET /jobs` returns the latest 100 jobs. Submission payloads are not persisted; metadata stores only the command and package ID summary. Engine results and diagnostics are persisted as control-plane metadata. Larger reports, logs, and other evidence remain under the job output directory.

Resource discovery accepts `case`, `template`, `flow`, or `tool` as the optional `type`. List pages default to 50 items and allow up to 100; continuation cursors are encrypted, bound to the authenticated principal and query, and rejected with `409` if package content changes. The explorer returns logical resource IDs, safe projections, provenance, relationships, and stable diagnostics. YAML source responses are parsed and redacted projections, not original file bytes. Tool script source is returned only when the exact package-relative script path is explicitly allowlisted. Discovery never executes Tools and does not expose a filesystem browser. Inspection requires an authenticated Servlet Principal even when `server.authenticationRequired: false`; the inspection queue, Worker heap, timeout, and response bounds are independent of normal job execution.

### Package resource inspection

Use the public read-only endpoints with an authenticated Servlet Principal. `kind` is `case`, `template`, `flow`, or `tool`; `resourceId` is the opaque package-scoped ID returned by the list endpoint. The list `query` matches logical IDs, names, descriptions, and tags. Omit `type`, `query`, or `limit` to use the defaults. A continuation cursor is valid only for the same package, principal, type, and query.

```http
GET /api/v1/packages/payments/resources?type=case&query=refund&limit=50
```

The response includes safe summaries, a total count, and an opaque `nextCursor` when another page exists:

```json
{
  "items": [{
    "resourceId": "case.<opaque>",
    "type": "case",
    "logicalId": "PAYMENT.REFUND01",
    "name": "Refund request",
    "state": "ready",
    "sourceAvailable": false,
    "provenance": {"suite": "testcase/payments.xlsx", "groupId": "PAYMENT", "sheet": "Cases", "rowNumber": 12},
    "references": [{"type": "template", "logicalId": "PAYMENT.refund", "resourceId": "template.<opaque>", "resolution": "resolved"}],
    "referencedBy": [],
    "diagnostics": []
  }],
  "total": 1,
  "nextCursor": null,
  "diagnostics": [],
  "requestId": "..."
}
```

Read one resource's parsed definition and relationships, then request its safe source projection separately:

```http
GET /api/v1/packages/payments/resources/template/{resourceId}
GET /api/v1/packages/payments/resources/template/{resourceId}/source
```

The detail response contains `resource`, `definition`, `diagnostics`, and `requestId`. The source response contains `resource`, `available`, `format`, `text`, `redacted`, and `requestId`. For unavailable source, `available` is `false`, `reason` is `source-unavailable` or `size-limit`, and no partial text is returned. References with no matching resource have `resolution: "unresolved"` and a stable resource diagnostic.

Invalid list parameters return `400`; unknown packages/resources return the same `404` shape. Stale cursors return `409`, oversized responses `413`, a full inspection queue `503`, and an inspector timeout `504`. Error responses use the existing `error.code`, `error.summary`, and `requestId` envelope; they do not include physical paths or parser exception messages.

### Target-scoped Debug and drafts

Debug form, Quick Load form, Load policy, and draft endpoints require a real authenticated Servlet Principal, including when anonymous access is enabled for legacy jobs. Use a resource ID from the Package Resource Explorer; Cases cannot be Debug or Quick Load targets.

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

The response includes an opaque `draftId`, safe YAML preview, validation diagnostics, and expiry. `GET /api/v1/drafts/{draftId}` returns the same safe preview only to its owning Principal. Drafts live in memory for up to 10 minutes, with a limit of 128 active drafts per Server and 16 per Principal. Restart invalidates them. The Server never returns the internal package revision digest.

Submit only the draft identity:

```http
POST /api/v1/jobs/debug
Content-Type: application/json

{"packageId":"payments","draftId":"D0123456789ABCDEF0123456789ABCDEF"}
```

The Server rechecks the package revision at submission and before Worker start. A change detected during submission returns `409 ATT-SERVER-DRAFT-STALE`; a change detected after job acceptance marks the job `INVALID` with that diagnostic. Validation failures return a top-level `diagnostics` array with safe `code`, `summary`, `field`, and logical `resourceId` values; source paths and parser snippets are omitted. Worker execution receives the same immutable typed values validated for the preview; inline values remain in memory and do not create files under the package root. Existing path-based Debug requests remain supported for compatible clients.

### Target-scoped Quick Load

Quick Load is available from a selected Template, Flow, or Tool when `server.inlineLoad.enabled` is true. It uses one fixed target and creates one Workload. The model is `virtualUsers` or `arrivalRate`; the Server composes the matching current `att-load/v1.6` policy and validates the target through the existing Load pipeline. When disabled, both Quick Load form/policy reads and draft creation return `403 ATT-SERVER-INLINE-LOAD-DISABLED`.

```http
GET /api/v1/packages/payments/resources/flow/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT
```

The form response includes safe `inputs` and `vars` defaults for Template/Flow targets, or `inputs` and `arguments` for a Tool, plus a redacted one-workload preview. It reads only the business fields from the optional target `debug.yaml`; Debug-only `case`, `stage`, and local `testdata` are not copied. When a sidecar has Debug-local Testdata, the form reports that those imports were omitted. Supply any needed package-relative descriptors in the separate Load-level `testdata` array; these paths are validated as part of the effective Load scenario.

The Server reads `load/load.visualuser.yaml` for `virtualUsers` and `load/load.arrivalrate.yaml` for `arrivalRate`. These are policy-only `att-load/v1.6` descriptors; the selected policy must match the chosen model. If a model-specific file is absent, a bundled low-intensity fallback is used: one Virtual User for 10 seconds, or 1 arrival per second for 10 seconds with `maxConcurrent: 1` and `overloadPolicy: drop`. Existing CLI `load/load.yaml` behavior is unchanged.

Validate business values and pacing overrides to create an owned draft:

```http
POST /api/v1/drafts/quick-load
Content-Type: application/json

{"packageId":"payments","environment":"SIT","target":{"type":"flow","id":"PAYMENT.submit"},"model":"virtualUsers","input":{"inputs":{"channel":"WEB"},"vars":{"reference":"REF001"}},"load":{"users":4,"duration":"30s"},"execution":{"thinkTime":"100ms"},"testdata":["testdata/load-accounts.yaml"]}
```

`load` accepts only pacing fields supported by the selected model. `execution` accepts the optional closed-workload `thinkTime`. `testdata` is an optional array of additional package-relative Load descriptor paths; it is kept separate from Debug-local imports and the model policy's existing descriptors. The response returns a principal-bound opaque `draftId` beginning with `L`, the safe effective YAML preview, redaction state, and expiry. Debug and Quick Load drafts share the 128-active-per-Server, 16-per-Principal, 10-minute in-memory limits.

After reviewing the preview, submit only the draft identity:

```http
POST /api/v1/jobs/load
Content-Type: application/json

{"packageId":"payments","draftId":"L0123456789ABCDEF0123456789ABCDEF"}
```

The Server checks the package revision at draft submission, before Worker start, and again in the Worker. A stale draft returns `409 ATT-SERVER-DRAFT-STALE`, or marks an already accepted job `INVALID` with that diagnostic. The Worker receives the exact immutable scenario validated for preview; it stays in memory and is not written into the package. Existing path-based Load requests remain supported.

### Advanced Load builder

Advanced Load creates a full `att-load/v1.6` scenario with one or more workloads when `server.inlineLoad.enabled` is true. The selected `model` must be `virtualUsers` or `arrivalRate` for every workload. Virtual Users workloads can use one fixed target or a weighted `mix`; Arrival Rate workloads use one fixed target each. The Engine validates the shared timing envelope, workload intensity, thresholds, Testdata policies, and every target before the Server creates a draft. The Server then enforces its configured inline Load caps before returning the preview. Exceeding a cap returns `400 ATT-SERVER-INVALID-REQUEST` with a safe summary; the Server does not schedule the scenario.

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
    "schemaVersion": "att-load/v1.6",
    "load": {"users": 1, "duration": "30s"},
    "workloads": [
      {"id": "browse", "target": {"type": "flow", "id": "PAYMENT.browse"}, "load": {"users": 4}},
      {"id": "submit", "target": {"type": "template", "id": "PAYMENT.submit"}, "load": {"users": 2}}
    ]
  }
}
```

The Server validates the full scenario and resolves every target without scheduling traffic. It restores redacted values only from each selected target's package-local `debug.yaml` business fields; Debug `case`, `stage`, and local Testdata are excluded. The response contains an opaque draft ID beginning with `A`, a safe effective YAML preview, the selected model, a redaction flag, and expiry. Advanced Load shares the 128-active-draft Server limit, 16-draft per-Principal limit, and 10-minute expiry with Debug and Quick Load.

Submit only the package and draft ID to `POST /api/v1/jobs/load`. The Server checks the package revision at submission and before Worker start, then passes the same immutable normalized scenario validated for preview through the existing Worker, Engine, scheduler, event, and evidence paths. A stale draft returns `409 ATT-SERVER-DRAFT-STALE`; a change detected after job acceptance marks the job `INVALID`. The browser disables YAML copy and export when any preview field is redacted. Existing path-based Load requests remain supported.

### Package configuration inspection

Configuration inspection uses the same authenticated, bounded inspection Worker as package resources. These read-only endpoints require an authenticated Servlet Principal, including when `server.authenticationRequired: false`:

```http
GET /api/v1/packages/payments/configuration?view=declared
GET /api/v1/packages/payments/configuration/effective?environment=SIT
GET /api/v1/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&offset=0&limit=50
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&offset=0&limit=50
```

The declared view returns the schema version, safe global fields, configured profiles, and for each configuration section its declared or absent state, entry count, and profile inheritance or replacement state. Effective views use `FrameworkConfigLoader` to select the environment and return safe helper, Tool, and Testdata descriptor metadata. Omit `environment` to inspect a root-only package or use the configured default. The first effective response returns section counts; request a section by its ID (`dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `tools`, or `testdata`) to read its entries. Effective section and comparison field responses accept `offset` and `limit` (1–100; default 50) and return `total` and `nextOffset` for paging. Inspection never connects to DB, MQ, HTTP, or SSH services. It does not return unrestricted configuration YAML or Testdata records.

The comparison reports visible effective fields and their origins. A sensitive field is returned as `state: "hidden"` with `change: "hidden"`; the response does not reveal whether its value is equal or different. Absolute paths, credentials, connection endpoints, network topology, and unrestricted descriptor content are not returned.

Configuration responses include `view`, `state`, `schemaVersion` when available, and `diagnostics`. Paged responses also include `section`, `offset`, `limit`, `total`, and `nextOffset`. An invalid configuration returns HTTP 200 with `state: "invalid"` and a stable diagnostic; malformed profile parameters return `400`. Unknown packages return `404`, oversized responses return `413`, a full inspection queue returns `503`, and an inspector timeout returns `504`. The same response-size, queue, and timeout limits used by resource inspection apply.

## Server-sent events

Connect to `/api/v1/jobs/{jobId}/events` with `Accept: text/event-stream`. Events are retained in `dataDir/jobs/<jobId>/events.jsonl`, assigned increasing numeric IDs per job, and named `status`, `progress`, `log`, `diagnostic`, or `result`.

```text
id: 2
event: status
data: {"jobId":"J...","status":"RUNNING"}

```

Send `Last-Event-ID` after reconnecting to replay retained events with greater IDs. If older events have expired from the bounded journal, the stream resumes with events still retained. The stream sends `: keepalive` comments while idle and closes after it delivers the terminal result. A disconnected observer does not cancel the job. Up to 32 observers receive dedicated stream threads through direct handoff; excess observers are rejected with HTTP 503 before a stream starts. Each observer has its own one-entry notification queue. Client writes happen outside the Worker event reader.

## Cancellation and recovery

`DELETE /jobs/{jobId}` requests graceful Worker termination, waits up to `gracefulStopMs`, and then force-terminates only that Worker if needed. The job record remains available. A Server restart does not automatically rerun jobs that were not terminal.

Artifact paths are relative to the job output directory. Absolute paths, traversal, and symlink escapes are rejected. The Server does not expose general filesystem browsing or package mutation APIs.

## Distribution and compatibility

The Server binary is distributed separately as `att-4.1.0-server.tar.gz`, containing `server/att-server-4.1.0.war` and the deployment guides. The local CLI is in `att-4.1.0-local.tar.gz` with its runtime libraries. Keeping these archives separate avoids duplicating the WAR's bundled libraries in the local package. Tomcat provides the HTTP listener and Servlet API. The Server requires Java 17; the CLI, Engine, and Worker continue to target Java 8.

### Issue #176 rollout checklist

1. Back up the Server `dataDir` and retain the current WAR and deployment configuration for rollback.
2. Keep Tomcat authentication enabled and terminate public traffic with HTTPS. Confirm the package roots and Server data directory are separate and writable only by the service account.
3. Deploy the Server WAR as an exploded web application so its `WEB-INF/lib` directory is available to Worker processes. Confirm `/api/v1/health` and `/api/v1/version` through the deployed Tomcat context path.
4. Begin with `server.inlineLoad.enabled: false`. Verify the Package and Configuration Explorers against a non-production package. Enable browser Load only in an approved environment and set caps for expected workload count, targets, users or rate, concurrency, and total envelope duration.
5. Monitor Worker and Load queue capacity, job outcomes, cancellation, and retained artifacts. The package remains read-only; rollback consists of disabling inline Load and restoring the prior WAR/config if needed.

This checklist describes deployment steps; it does not substitute for the P6 security, browser, compatibility, packaged-WAR, and hosted-CI acceptance evidence.
