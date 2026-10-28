# ATT Web UI inspection and inline job contracts

Status: Issue #176 contract and phased implementation. P1 implements authenticated package resource discovery and the Package Resource Explorer. P2 implements declared/effective configuration inspection. P3 adds target-scoped Debug forms, in-memory drafts, and typed inline Debug execution. P4 adds target-scoped Quick Load forms, model-specific policies, and one-workload inline Load execution. P5 adds the home-level Advanced Load builder, validated multi-workload drafts, and inline Load execution. P6 adds explicit opt-in and server-owned caps for browser-created Load drafts; the full security, browser, compatibility, packaging, hosted-CI, and rollout evidence remains a release gate.

## Purpose and scope

ATT Web UI adds package-resource inspection, read-only configuration views, resource-scoped Debug and Quick Load forms, and a multi-workload Load builder. The browser remains a client of authenticated public Server APIs. The Server remains the authority for resource resolution, visibility, validation, admission, and job creation.

This design preserves the existing package-relative job API and CLI behavior. It adds typed public contracts rather than exposing the internal Worker request or a general file browser.

## Decisions

1. **Keep the public API additive under `/api/v1`.** New inspection, draft, and inline-job endpoints use dedicated DTOs with strict unknown-field rejection. Existing path-based job requests retain their current behavior for compatibility.
2. **Address resources by logical identity.** A resource ID is package-scoped and identifies a Case, Template, Flow, or Tool. It never contains a host path. Case identity includes its logical suite, group, Case ID, and row provenance; other resource identities use their ATT IDs.
3. **Reuse Engine semantics.** Engine code owns Excel, Template, Flow, Tool, config, Debug sidecar, and Load parsing. Browser code renders typed projections; it does not parse ATT YAML or reproduce Load semantics.
4. **Return safe projections only.** Inspection APIs do not return unrestricted files or resolved secrets. Source and configuration output is allowlisted and redacted on the Server.
5. **Validate immutable drafts before submission.** The Server issues an opaque draft ID bound to the resolved request identity, package, environment, target closure, and normalized input. In anonymous mode all callers share that identity and its drafts. Submission references the draft instead of resending editable input.
6. **Keep new Debug and Load inputs in memory.** The Server sends typed values through the existing Worker JSON-lines request. It does not write generated inputs under the package root or accept client-supplied physical paths.
7. **Require explicit Server-side Load limits.** Inline Load is disabled until deployment configuration enables it and defines workload, rate, concurrency, and duration caps. Existing Server queue and concurrent-Load admission limits continue to apply.

## API boundary

The routes below define the public v1 contract. P1 implements resource discovery and P2 implements configuration inspection. P3 implements `debug-form`, Debug draft, and inline Debug routes with typed response DTOs in `att-server-api`. P4 implements Quick Load forms and drafts; P5 implements the model policy, full Load draft, and Advanced Load submission routes. The REST layer must not deserialize new UI requests directly into `att-worker.WorkerRequest`.

### Resource discovery

```http
GET /api/v1/packages/{packageId}/resources?type=flow&query=payment&limit=50&cursor=...
GET /api/v1/packages/{packageId}/resources/{kind}/{resourceId}
GET /api/v1/packages/{packageId}/resources/{kind}/{resourceId}/source
GET /api/v1/packages/{packageId}/resources/{kind}/{resourceId}/source-file?referenceId=<opaque>
GET /api/v1/packages/{packageId}/resources/{kind}/{resourceId}/debug-form?environment=SIT
GET /api/v1/packages/{packageId}/resources/{kind}/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT
GET /api/v1/packages/{packageId}/load-policy?model=virtualUsers&environment=SIT
```

List responses carry typed resource summaries, safe relationship references, and an optional encrypted continuation cursor. A cursor is bound to the resolved request identity, package, filters, offset, and private package revision digest. The digest is encrypted inside the cursor and never returned as a response field. The default page size is 50; the maximum is 100. If package content changes between pages, the Server rejects the stale cursor and the Explorer can refresh the list.

Details expose only fields supported by the resource type. Case details retain logical suite, group, Case ID, row or sheet provenance, tags, and mapped input/expected-value metadata. Template, Flow, and Tool details expose their parsed definitions and resolvable references. Unresolved references are diagnostics. Only Case details provide Run. Only Template, Flow, and Tool details provide Debug and Quick Load.

Source responses are generated from sanitized, bounded projections. They are not arbitrary file reads. With the default `safe-package-files` policy, eligible non-sensitive package-local text and shared runtime configuration text are available without per-file allowlists, including Tool scripts, ordinary configuration and Testdata descriptor files, and eligible `&{...}` targets reached from an authorized source. Each navigable reference carries an opaque ID issued by the Server; navigation rechecks that ID, its package and origin, and the target with Engine-compatible canonicalization. The bounded shared `config/` file tree remains the only directory listing. In `allowlist-only` mode, Tool scripts and linked files require exact `server.inspection.safeTextSources` entries rooted in the Package or shared ATT_HOME configuration, such as `tools/helper.sh` or `config/tools/helper.sh`. External scripts are available only when the canonical file is listed under `sourceBrowsing.externalTextSources` for that package and is inside a configured canonical `externalSourceRoots` directory. External paths are never listed, wildcard entries are rejected, and responses use an opaque alias instead of the filesystem path. Credential-named files, denied paths, binary files, package-root escapes, and oversized files are unavailable; classified credentials and personal data are redacted in source text regardless of the display-masking setting. A source response is limited to 64 KiB and up to 100 referenced files are listed; a detail response is limited to 256 KiB. Larger content is unavailable with a size-limit state. The private package-content digest is used to detect stale cursors and is never returned.

The Debug form endpoint accepts only Template, Flow, or Tool resource IDs. It returns the target's typed `att-debug/v1.2` defaults from its optional sidecar; if the sidecar is absent, it returns valid empty defaults. Ordinary values are visible by default. Credential/authentication material and common personal-data fields remain protected even when masking is disabled or the caller is anonymous. When `server.inspection.redaction.enabled` is true, configured paths and field patterns are masked before serialization. The browser edits JSON objects for `inputs` on all targets, `vars` on Template/Flow targets, and `arguments` on Tool targets; it does not evaluate expressions. Tool `vars` and Template/Flow Tool arguments are rejected. The Load policy endpoint returns the safe model-specific policy used to initialize Advanced Load.

### Configuration inspection

```http
GET /api/v1/packages/{packageId}/configuration?view=declared
GET /api/v1/packages/{packageId}/configuration/effective?environment=SIT
GET /api/v1/packages/{packageId}/configuration/compare?left=SIT&right=UAT&section=mqhelpers&offset=0&limit=50
GET /api/v1/packages/{packageId}/configuration/testdata/{descriptorId}/records?environment=SIT&offset=0&limit=50
```

The Engine's `FrameworkConfigLoader` selects and resolves the environment. Inspection makes no database, MQ, HTTP, or SSH connection. Declared and effective views are separate. Compare requires a verified section ID, applies that filter on the Server before pagination, and scopes every page to that section. Testdata preview accepts only a descriptor configured for the selected environment and returns bounded pages. Ordinary values are visible by default; credentials, authentication material, and common personal-data fields remain masked for anonymous and authenticated callers. `redaction.enabled` adds configured sensitive paths and field patterns across source, configuration, Testdata, and Debug projections. A hidden comparison value is represented as hidden, never as equal, changed, a digest, or a value length.

### Draft, preview, validation, and submission

```http
POST /api/v1/drafts/debug
POST /api/v1/drafts/quick-load
POST /api/v1/drafts/load
GET  /api/v1/drafts/{draftId}
POST /api/v1/jobs/debug
POST /api/v1/jobs/load
```

Debug draft creation validates the typed request and selected target closure. It returns a safe YAML preview, validation diagnostics, and an opaque draft ID. Drafts are bound to the resolved request identity, package, environment, target, and private package revision. The in-memory store is bounded to 128 active drafts across the Server and 16 per identity; each expires after 10 minutes. In anonymous mode all callers share the `anonymous` identity and its draft quota. Server restart invalidates drafts, so clients must rebuild and revalidate them.

A draft captures normalized values and an internal content digest for the target, sidecars, config, referenced resource closure, and, for Quick Load, the selected model policy. The digest never leaves the Server. The Server checks the package revision when the draft is submitted and immediately before Worker start; the Worker verifies it again before Engine execution. A change detected during submission returns HTTP 409 and `ATT-SERVER-DRAFT-STALE`; a change detected after job acceptance marks the job `INVALID` with that diagnostic. The submitted Worker input is the same immutable typed value validated for preview.

P3 Debug and P4 Quick Load submission accept `packageId` and `draftId`; neither accepts an arbitrary file path or client-supplied digest. Advanced Load submits a complete `att-load/v1.9` object to `/drafts/load`, then uses the same draft-only `/jobs/load` contract. The draft ID prefix distinguishes Quick (`L`) from Advanced (`A`) Load internally, while both share capacity, principal binding, expiry, stale-revision checks, and the job pipeline. Existing path-based `debugInput` and `scenario` submissions remain available to compatible clients.

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

Example Quick Load draft request:

```json
{
  "packageId": "payments",
  "environment": "SIT",
  "target": { "type": "flow", "id": "PAYMENT.submit" },
  "model": "virtualUsers",
  "input": { "inputs": { "channel": "WEB" }, "vars": {} },
  "load": { "users": 10, "duration": "1m" },
  "execution": { "thinkTime": "100ms" },
  "testdata": ["testdata/load-accounts.yaml"]
}
```

Quick Load reads `load/load.visualuser.yaml` or `load/load.arrivalrate.yaml` as a policy-only descriptor, with a bundled one-user/one-arrival-per-second, ten-second fallback. The policy must match the selected model. The response includes a safe, normalized one-workload preview. If visibility policy masks any field, the response marks the preview as redacted; the UI disables preview copy and export. The Server still validates and submits its immutable private draft.

## Typed Debug and Load rules

Debug is available only from a selected Template, Flow, or Tool detail and contains one fixed target. All forms use typed `inputs`; Template and Flow also use `vars`, while Tool uses `arguments` and rejects `vars`. The form reads safe defaults from the package-authored `debug.yaml` when present. When absent, the Server creates valid `att-debug/v1.2` defaults in memory. Sidecars remain unchanged. Expression strings and native scalar, map, and list values are preserved; the browser does not evaluate them.

Quick Load uses one fixed selected target and composes one Workload. It reads `load/load.visualuser.yaml` or `load/load.arrivalrate.yaml` as a read-only package policy when present, otherwise it uses the bundled policy fallback. It does not require a stored Debug input or Load scenario. P4 submits the inline scenario through the existing Load validator, scheduler, event stream, and evidence pipeline. Debug-local Testdata is omitted with a visible form warning; users can supply additional package-relative Load-level descriptor paths through the separate `testdata` field, which is validated with the model policy's existing descriptors.

Advanced Load selects Virtual Users or Arrival Rate before adding Workloads and locks that model for the scenario. Virtual Users supports single-target and weighted-mix Workloads. Arrival Rate supports multiple single-target Workloads, but cannot mix targets within a workload. All Workloads share one timing envelope. Workload inputs and vars remain defaults; a mix entry replaces matching top-level keys instead of deep merging. Root aggregate thresholds stay distinct from workload thresholds. The UI can initialize target business values from safe `debug.yaml` projections or leave them empty. During draft validation, the Engine restores only `[REDACTED]` business values from that target's package-local sidecar. Debug `case`, `stage`, and local Testdata are excluded. The Server then stores the private normalized scenario and revision digest in its bounded draft store while returning only a safe preview. Submission passes that same immutable scenario through the existing Load validator, scheduler, event stream, and evidence pipeline without writing a scenario file.

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

The Server alone supplies the package root, output directory, Worker executable, and classpath. Public DTOs cannot set them. P3 adds an explicit typed inline Debug field; P4 and P5 pass typed Load scenarios through the Worker protocol. None accepts arbitrary external paths. Engine input objects are immutable copies. Debug defaults use the existing `DebugEngine` sidecar rules, with blank typed defaults when a sidecar is absent. Load scenarios use the current `att-load/v1.9` schema and `LoadScenarioLoader` semantic checks, composed through the shared `LoadScenarioBuilder`.

Job metadata continues to store the command and package ID summary, not the submitted input or draft contents. Drafts are not persisted. Temporary values exist only in Server and Worker memory and are cleared on expiry, cancellation, terminal completion, or Server shutdown.

## Visibility and redaction policy

| Data | Default response | Additional rule |
| --- | --- | --- |
| Logical resource IDs, types, safe names, tags, and relationships | Visible to callers allowed by the configured identity mode | Package-scoped; no absolute paths |
| Case row and sheet provenance | Visible as logical identifiers | Never include the workbook's physical path |
| Template, Flow, and Tool definitions | Allowlisted structured projection | Sensitive fields are replaced before serialization |
| Safe Template and Flow YAML source | Available by default under `safe-package-files` | Parsed projection, package-root confinement, deny rules, and source-size cap |
| Package-local Tool script text | Available by default under `safe-package-files` | Package-root confinement, deny rules, text/size checks; `allowlist-only` requires exact package-relative `safeTextSources` |
| External Tool script text | Unavailable by default | Exact canonical file under a configured external root, allowlisted per package; opaque path alias and source-size cap |
| Declared or effective configuration | Allowlisted projection | No unrestricted YAML; use the real Engine resolution |
| Passwords, tokens, private keys, credentials, and auth material | Always hidden | No preview, diagnostic, event, cache, or export may contain bytes |
| Common personal-data fields | Always masked | Includes fields such as email, phone, address, and national identifiers |
| Testdata records | Paged preview for configured descriptors | Ordinary values visible by default; credential and personal-data fields stay masked |
| Hostnames, ports, JDBC URLs, endpoints, queues, network topology | Hidden by default | A Server-side visibility policy may allow selected fields for all authenticated callers; the browser cannot grant visibility |
| Cross-profile comparison of hidden values | Hidden state only | Do not reveal equality, hashes, lengths, or change counts |
| User-entered Debug/Load values | Safe typed preview | Apply the same sensitive-field rules; mark the preview redacted when any field is hidden |

Display masking defaults off for ordinary package values. When enabled, configured field paths and patterns are applied server-side before JSON or YAML serialization across all supported projections. The Server logs a startup warning when masking is disabled. Authentication/authorization, package containment, hard-deny classifications, and credential protections are independent of this display setting. Errors contain stable codes and safe summaries, not source contents, credentials, absolute paths, or parser snippets that include them. Browser rendering treats all returned strings as text.

## Resource isolation, bounds, and admission

Resource discovery runs in a bounded pool of long-lived inspector Worker processes, not on Tomcat request threads. Each Worker handles one request at a time and retains at most two package indexes. Initial index construction scans configured package resources; subsequent page, detail, and source requests reuse the snapshot. Package file events and watcher overflow invalidate cached indexes, and authoritative draft revision checks build a fresh snapshot before approval. The inspector is read-only and does not invoke Tools. Initial `server.inspection` defaults are `maxConcurrent: 2`, `queuedLimit: 16`, `timeoutMs: 30000`, `heapMaxMb: 512`, `maxResponseBytes: 262144`, and `maxSourceBytes: 65536`. These bounds do not inherit the general job queue limits. Timeout or output-limit violations terminate the inspector Worker; the next request starts a replacement. The inspector reuses `PackageResourceResolver` for package confinement and rejects traversal and external symlinks. Responses are paginated.

The opt-in `att.server.PackageResourceInspectionPerformanceBenchmark` exercises generated packages, each with five Excel workbooks (300 Cases each) and 200 Templates. It records cold startup, the second request for the same package, warm Case/Template first-page, page-N, detail and Tool-source latency, declared/effective SIT/UAT Configuration, Compare, package churn and concurrent clients. Fixed-cardinality Server/Worker telemetry records startup counts, index builds, instrumented reads, heap, GC and CPU. It excludes HTTP, Tomcat and browser rendering overhead. Run it from the repository root:

```sh
mvn -B -ntp -pl att-worker,att-server -am \
  -Dtest=PackageResourceInspectionPerformanceBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Datt.server.inspection.benchmark.warmups=2 \
  -Datt.server.inspection.benchmark.runs=15 \
  -Datt.server.inspection.benchmark.concurrentWarmups=10 \
  -Datt.server.inspection.benchmark.concurrentRuns=100 \
  -Datt.server.inspection.benchmark.workers=2 \
  -Datt.server.inspection.benchmark.packages=3 \
  -Datt.server.inspection.benchmark.pageSize=50 \
  -Datt.server.inspection.benchmark.revision="$(git rev-parse HEAD)" \
  -Datt.server.inspection.benchmark.output=target/issue-227-after-2-workers.json \
  test
```

The [4.1.0 capture](baselines/issue-176-resource-discovery-large-4.1.0.json) was collected on an 8-core Apple Silicon Mac, macOS 26.1, and OpenJDK 26.0.1. It recorded 1,142.5 ms for the cold index build. Warm-request p50 latencies were:

| Scenario | p50 (ms) | p95 (ms) |
| --- | ---: | ---: |
| Case first page | 14.9 | 17.3 |
| Case page N | 9.3 | 10.7 |
| Case detail | 0.9 | 1.1 |
| Template first page | 7.4 | 10.2 |
| Template page N | 8.6 | 10.8 |
| Template detail | 1.3 | 1.5 |
| Tool source | 2.0 | 2.0 |

The report preserves raw samples and package size. With five measured runs, p95 is the highest observed sample and is not a stable distribution estimate; timing results are descriptive, not portable pass/fail thresholds.

### Issue #227 cache implementation and measurement

Studio uses a shared bounded query cache for safe read projections and in-flight requests. Authentication context is verified before reuse; the opaque context covers identity, local session or existing container session, administrator capability, inspection policy and Server lifetime. Selection and session epochs reject responses from cancelled requests. Resource revisions are exposed only as HMAC tokens in `X-ATT-Inspection-Revision`; the private digest remains inside the Server. Stale responses trigger background revalidation and subscription updates. View metadata shares the session lifetime, and Explorer paints restored pages progressively.

Workers retain two Resource inspectors and four trusted Configuration snapshots. Configuration snapshot reconciliation hashes bounded dependencies on every reuse, including schema files and selected profile overrides. This costs reads even on a hit, but avoids relying on timestamp precision or watcher delivery for Configuration correctness. Fresh request-local inspectors apply permissions and redaction. Resource affinity uses the same package/config/environment/policy identity in the Server and Worker; the Server replaces its routing hints with keys actually retained by the daemon, including eviction and error responses. Busy affinity Workers never prevent selection of another idle Worker. Optional warmup uses a single scheduler, bounded key assignments and retries, and can be cancelled by interactive demand.

The baseline uses master `d291f8be` with the [instrumentation and benchmark patch](baselines/issue-227-baseline-instrumentation.patch.gz), without cache, affinity or warmup changes. To reproduce it, create a clean checkout of that commit and apply the decompressed patch with `gzip -dc issue-227-baseline-instrumentation.patch.gz | git apply`; use the same benchmark command, dataset, Worker count and run count for both revisions. The after capture identifies the implementation commit in `sourceRevision`. Run captures serially without a concurrent build. `warmups=2` excludes startup for steady-state scenarios; cold and second-request measurements are recorded separately. Concurrent samples complete `workers + 1` requests per batch, so batch latency is comparable only at the same Worker count. Heap peaks sum per-pool peaks rather than a simultaneous process maximum; instrumented read bytes and process CPU are cumulative for the captured workload.

Only duplicate Template YAML parsing within one build is removed in Phase 4. Per-file incremental indexing remains benchmark-gated: it requires a complete dependency graph for sidecars, aliases, relations and the private revision digest. The current implementation preserves atomic full rebuilds for edits, unknown changes and watcher overflow, and preserves fresh authoritative rebuilds before draft approval and execution. The routing measurements below assess avoidable duplicate builds before adding that graph.

The persistent inspector index also serves Debug form and draft validation requests. The execution Worker performs one fresh package revision check at the side-effect boundary. The opt-in `DebugDraftPipelinePerformanceBenchmark` measures form, draft creation, preview, submission, first-event, and completion latency against multiple Excel workbooks. Run it with `mvn -pl att-server -am -Dtest=DebugDraftPipelinePerformanceBenchmark -Datt.server.benchmark.debugDraft=true -Dsurefire.failIfNoSpecifiedTests=false test`; it writes JSON under `att-server/target/` by default. The recorded 4.1.0 baseline is [issue-176-debug-draft-pipeline-4.1.0.json](baselines/issue-176-debug-draft-pipeline-4.1.0.json). On its five-workbook, 1,500-Case fixture, three samples measured a p50 of 23.1 ms for draft creation, 0.31 ms for preview, 886 ms for submission, and 892 ms from draft creation through the first event (936 ms including form loading). The benchmark exercises ServerRuntime APIs without browser rendering; timings are descriptive for the recorded Mac OS X aarch64 / Java 26 host.

Inspection and draft endpoints use the same Server-resolved identity as job submission: the Servlet Principal when authentication is required, or the shared `anonymous` identity when explicit anonymous mode is enabled. Unknown packages and resources return the same not-found shape. New DTOs reject unknown fields and ambiguous combinations. Existing requests remain backward compatible.

Inline Load is disabled by default. Enabling `server.inlineLoad.enabled` applies server-owned maxima for `maxWorkloads` (default 10), `maxTargets` (20), `maxTotalUsers` (100), `maxAggregateArrivalRatePerSecond` (100), `maxConcurrentPerWorkload` (100), `maxTotalConcurrent` (1,000), and `maxDurationSeconds` (default 3,600, maximum 864,000). Deployments can lower or raise these bounded values in Server configuration. The Server enforces them after Engine validation, before storing a draft, and again before queue admission. Total targets count fixed targets and VU mix entries; the duration limit applies to the effective per-workload envelope after root settings are merged with workload overrides, summing warmup, ramp-up, measured duration, and ramp-down. The same envelope check applies to scheduled LOAD and package-backed replay. A disabled feature returns `403 ATT-SERVER-INLINE-LOAD-DISABLED`; exceeding a configured cap returns a safe `400 ATT-SERVER-INVALID-REQUEST`. VU and Arrival Rate cannot mix; Arrival Rate cannot contain a weighted mix; divergent per-workload timing remains unsupported for a shared replay edit; Engine validation remains authoritative. The existing `maxConcurrentLoad` and general Worker queue limits still apply. Direct path-based Load requests retain their existing contract and are not subject to the browser-draft caps.

## Error behavior

New endpoints use the existing `error` envelope and stable codes:

| HTTP status | Code | Meaning |
| --- | --- | --- |
| 400 | `ATT-SERVER-INVALID-REQUEST` | Invalid JSON, unknown field, invalid logical ID, schema error, or semantic error |
| 401 | `ATT-SERVER-AUTHENTICATION-REQUIRED` | No request identity is available because authentication is required |
| 404 | `ATT-SERVER-NOT-FOUND` | Package, resource, or draft is unavailable |
| 409 | `ATT-RESOURCE-CURSOR-STALE` | Package content changed while listing resources |
| 409 | `ATT-SERVER-DRAFT-STALE` | Package content changed after Debug or Load preview |
| 413 | `ATT-SERVER-REQUEST-TOO-LARGE` | Request body exceeds its configured size limit |
| 413 | `ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE` | Projected inspection response exceeds its configured size limit |
| 429 | `ATT-SERVER-CAPACITY-EXCEEDED` | Normal job admission limit reached |
| 429 | `ATT-SERVER-DRAFT-CAPACITY` | Shared in-memory Debug and Load draft limit reached |
| 503 | `ATT-SERVER-INSPECTION-CAPACITY` | Bounded inspection queue is full |
| 504 | `ATT-SERVER-INSPECTION-TIMEOUT` | Inspector Worker exceeded its time limit |

Debug validation failures include a top-level `diagnostics` array with safe `code`, `summary`, `field`, and logical `resourceId` values. They never include source lines, parser excerpts, or physical paths. An oversized projected source is returned as unavailable with a size-limit state, not as a partial file. New request bodies also use the existing `maxRequestBytes` cap.

## Implementation and verification contract

| Phase | Delivery boundary | Required evidence |
| --- | --- | --- |
| P0 | This public contract, visibility matrix, data flow, and security design | API examples and test matrix reviewed; no unresolved path, secret, or draft-consistency decision |
| P1 | Bounded Package Resource Resolver adapter, public discovery DTOs/routes, Package Explorer | All resource types, references, provenance, pagination, stale refresh, auth, size, traversal, symlink, and redaction coverage |
| P2 | Declared/effective configuration and profile comparison | Environment selection matches `FrameworkConfigLoader`; hidden fields do not leak through values or comparisons |
| P3 | Typed Debug form, bounded in-memory drafts, and inline Debug execution | Sidecar and no-sidecar paths for Template, Flow, and Tool; Worker isolation; package unchanged; draft principal binding and stale-revision rejection |
| P4 | Quick Load and shared typed scenario composer | VU and Arrival Rate templates/fallbacks; one-workload execution without stored Debug or Load files |
| P5 | Advanced multi-workload builder and inline Load execution | VU mix and single-target scenarios, Arrival Rate multi-single-target scenarios, invalid model combinations, preview-to-run identity, limits, SSE, and cancellation |
| P6 | Security, compatibility, docs, packaging, and rollout gate | Full acceptance matrix, EN/ZH user docs, Java 8/17 compatibility, packaged WAR, and hosted CI |

Each phase is a separate review PR. P1 and P3 form the critical discovery/Debug path; P2 may proceed after the shared inspection projection exists. P4 reuses P3 input projection. P5 reuses P4 composition and the inline Load contract. P6 closes the full #176 acceptance criteria.

The test plan must cover contract serialization and strict field rejection; package containment, symlink, encoded traversal, and source-size limits; authorization; malformed and stale resources; secrets in source, errors, previews, logs, and diffs; concurrent principals and drafts; server restart expiry; package mutation between preview and submit; Worker input isolation; both Load models and all Engine semantic restrictions; queue admission, cancellation, and package immutability. These are implementation gates, not claims that tests already exist.

### Issue #227 recorded comparison

These captures use three registered packages and one or two Workers. Each package contains 1,500 Cases and 200 Templates. Normal scenarios use two warmups and 15 measured runs; concurrent clients use ten warmups and 100 measured batches. Cold means a new Worker/index, not an emptied operating-system file cache. Samples are descriptive; the 15-run p95 is the highest observation and does not establish a stable latency distribution.

- 1 Worker(s): [before](baselines/issue-227-before-1-worker.json), [after](baselines/issue-227-after-1-worker.json).
- 2 Worker(s): [before](baselines/issue-227-before-2-workers.json), [after](baselines/issue-227-after-2-workers.json).

| Workers | Scenario | Before p50 / p95 (ms) | After p50 / p95 (ms) |
| ---: | --- | ---: | ---: |
| 1 | case-first-page | 26.10 / 35.63 | 25.28 / 29.07 |
| 1 | case-page-n | 19.10 / 20.43 | 19.17 / 24.63 |
| 1 | case-detail | 1.09 / 2.76 | 1.22 / 1.45 |
| 1 | template-first-page | 22.64 / 44.53 | 23.01 / 38.66 |
| 1 | template-page-n | 14.82 / 15.70 | 14.52 / 16.00 |
| 1 | template-detail | 8.88 / 9.30 | 8.83 / 9.20 |
| 1 | tool-source | 1.10 / 1.55 | 1.06 / 1.73 |
| 1 | config-declared | 6.67 / 9.32 | 7.24 / 10.81 |
| 1 | config-effective-SIT | 6.20 / 16.59 | 8.42 / 14.88 |
| 1 | config-effective-UAT | 4.15 / 7.00 | 5.60 / 7.39 |
| 1 | config-compare | 3.94 / 4.58 | 8.51 / 12.90 |
| 1 | multi-package-churn | 483.83 / 546.83 | 459.54 / 486.77 |
| 1 | concurrent-clients | 25.00 / 26.84 | 24.59 / 26.62 |
| 2 | case-first-page | 27.16 / 35.90 | 19.84 / 23.97 |
| 2 | case-page-n | 23.68 / 28.68 | 19.20 / 20.80 |
| 2 | case-detail | 1.62 / 2.81 | 1.24 / 2.85 |
| 2 | template-first-page | 26.03 / 33.70 | 23.57 / 29.59 |
| 2 | template-page-n | 15.84 / 18.20 | 14.58 / 16.25 |
| 2 | template-detail | 9.34 / 9.53 | 8.97 / 14.54 |
| 2 | tool-source | 1.33 / 2.68 | 1.13 / 1.76 |
| 2 | config-declared | 6.69 / 13.23 | 5.18 / 8.04 |
| 2 | config-effective-SIT | 10.71 / 31.58 | 8.96 / 15.52 |
| 2 | config-effective-UAT | 8.31 / 12.06 | 7.31 / 9.61 |
| 2 | config-compare | 6.06 / 9.41 | 8.07 / 8.68 |
| 2 | multi-package-churn | 498.27 / 577.82 | 59.71 / 160.36 |
| 2 | concurrent-clients | 26.56 / 28.58 | 26.29 / 32.38 |

| Metric | 1 Worker: before → after | 2 Workers: before → after |
| --- | ---: | ---: |
| Worker startups | 1 → 1 | 2 → 2 |
| Index builds | 52 → 52 | 52 → 4 |
| Configuration loads | 172 → 106 | 172 → 11 |
| Configuration snapshot hits | 0 → 82 | 0 → 81 |
| Instrumented reads (MiB) | 15.89 → 46.22 | 18.00 → 37.55 |
| Worker CPU (seconds) | 24.60 → 24.14 | 36.10 → 20.99 |
| Worker heap pool peaks, summed (MiB) | 204.72 → 203.03 | 369.08 → 262.68 |
| Worker GC (ms) | 206 → 192 | 276 → 190 |
| Cold Worker/index request (ms) | 1215.66 → 1194.64 | 1252.50 → 1217.33 |
| Second same-package request (ms) | 46.15 → 42.18 | 1157.80 → 42.89 |
| Concurrent completed requests / measured batch-second | 78.93 → 80.28 | 111.99 → 109.63 |

The two-Worker routing benefit is concentrated in package churn and avoids duplicate builds. One Worker still thrashes when cycling three packages through a two-entry LRU; an incremental parser inside an evicted inspector would not retain that state. A separate cross-package parser cache would need a new bounded memory design before it could address this case. Configuration load counts fall, while some Configuration and tiny Source requests become slower: content reconciliation deliberately reads and hashes schema dependencies on each reuse. Those additional reads are included above. The browser cache avoids repeating successful inspection transports during its fresh window, as covered by the transport and view-restore regressions; these ServerRuntime timings do not measure that browser benefit.

Concurrent throughput above is completed requests divided by the sum of measured batch durations, excluding warmups and inter-batch overhead. This synthetic sample is not a sustained-load capacity claim. Both Workers remain available to concurrent callers, as independently exercised by real-process routing tests.

The earlier short concurrent capture is also retained: [one Worker before](baselines/issue-227-short-run/issue-227-before-1-worker.json.gz), [one Worker after](baselines/issue-227-short-run/issue-227-after-1-worker.json.gz), [two Workers before](baselines/issue-227-short-run/issue-227-before-2-workers.json.gz), and [two Workers after](baselines/issue-227-short-run/issue-227-after-2-workers.json.gz). It used two concurrent warmups and 15 measured batches. That small sample measured lower concurrent throughput after the change, which prompted extending both revisions to the identical ten-warmup/100-batch scenario above. Reproduce the short scenario with `concurrentWarmups=2` and `concurrentRuns=15`; its after capture identifies the earlier implementation revision before the longer concurrent scenario and metric-serialization optimization.


The first 100-batch implementation captures, before removing redundant secret classification of fixed metrics, are retained for comparison: [one Worker](baselines/issue-227-pre-metric-optimization/issue-227-after-1-worker.json.gz) and [two Workers](baselines/issue-227-pre-metric-optimization/issue-227-after-2-workers.json.gz). They measured 78.03 and 107.93 requests per measured batch-second. Only the locally constructed numeric counters, validated labels and opaque hashes avoid repeated secret classification; authored results, diagnostics and Worker telemetry continue through the sanitizer.


The subsequent full-metric-wire captures are retained too: [one Worker](baselines/issue-227-pre-metric-delta/issue-227-after-1-worker.json.gz) and [two Workers](baselines/issue-227-pre-metric-delta/issue-227-after-2-workers.json.gz). They measured 77.25 and 107.17 requests per measured batch-second. The final implementation sends changed metric fields over the private protocol and merges them atomically into the complete Server snapshot. Cache keys are updated on eviction, process replacement starts with a full snapshot, and omitting telemetry under a small response cap forces the next full snapshot. Counters remain current; this is transport compaction rather than sampling.


The metric-delta-only captures are also retained: [one Worker](baselines/issue-227-pre-chunked-reader/issue-227-after-1-worker.json.gz) and [two Workers](baselines/issue-227-pre-chunked-reader/issue-227-after-2-workers.json.gz). They measured 78.31 and 108.08 requests per measured batch-second. The final inspection transport replaces synchronized per-character reads with an 8,192-character framing buffer. It retains the configured frame limit, total UTF-8 byte limit, CRLF behavior, oversized-frame draining and process watchdog. Job-log framing is unchanged.

The final concurrent rates are 78.93 → 80.28 requests/second with one Worker and 111.99 → 109.63 with two. An exploratory block bootstrap of the 100 measured batches (consecutive blocks of ten, 10,000 independent before/after resamples, random seed 227, processing one Worker before two) gives throughput-change intervals of approximately +0.77% to +2.69% and -6.49% to +1.82%, respectively. The two-Worker sample does not establish a throughput improvement or strict equivalence; retain that limitation when assessing the gain in churn. Real-process tests independently verify that affinity does not serialize concurrent demand or starve the other Worker. Counter consistency checks also confirm that Server queue counts, merged Worker processing counts and package/environment request aggregates agree (463 requests for one Worker and 573 for two).
