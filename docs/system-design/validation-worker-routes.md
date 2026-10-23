# Validate and Preview ownership

This inventory covers the Studio and Server routes at the merged PR #282 baseline. `ServerRuntime.executeInspection()` queues typed read-only work through the existing bounded Inspector executor; `runInspectionWorker()` leases an affinity-matched `InspectionWorker`, which sends requests to `WorkerMain --inspection-daemon`. A daemon rejects execution commands. These routes already reuse JVMs; renaming the public Validate Job would not save an additional process on these paths.

| Entry point | Server / Worker operation | Process and side effects |
| --- | --- | --- |
| Explorer resource list, detail, YAML/text source | `inspectResource`; `list`, `detail`, `source`, `source-file` | Pooled Inspector. Reads bounded projections, no business action. |
| Explorer typed Debug form | `inspectDebugFormInternal`; `debug-form` | Pooled Inspector. Resolves schema/defaults, no Tool call. |
| Explorer Validate and preview / RUN page Debug target | `createDebugDraft`; `debug-input` | Pooled Inspector validates normalized input; Server stores a bounded owner-bound draft. No execution. |
| RUN page workbook/Case selection | `createRunDraft`; `run-input` | Pooled Inspector invokes selected `AttService.validate`, including selection identity checks, then verifies revision. No execution. |
| Quick Load form and defaults | `inspectQuickLoadForm`, `inspectQuickLoadPolicy`; `quick-load-form`, `quick-load-policy` | Pooled Inspector. Server-owned workload bounds still apply. |
| Quick Load Validate | `createQuickLoadDraft`; `quick-load-input` | Pooled Inspector checks input and target, then Server stores immutable normalized draft. No DB/MQ/HTTP/SSH action. |
| Advanced Load Validate, including YAML authoring | `createLoadDraft`; `load-scenario` | Pooled Inspector checks normalized scenario/targets. YAML syntax editor checks are local hints, not execution authorization. |
| Six-mode template selection / composition | `inspectLoadTemplates`, `createLoadDraft`; `load-templates`, `load-template-compose` | Pooled Inspector. Composition binds targets and produces a validated Load draft; listing does not contain executable bound definitions. |
| Authored Load catalog Validate | `validateLoadCatalogScenario`; `load-scenario` with `inspectionPath` | Pooled Inspector validates current file and environment. Server can store an owner-bound scheduling draft. |
| Resource/configuration YAML preview | `inspectResource`, `inspectConfiguration`; resource `source*`, configuration `declared/effective/compare/files/source` | Pooled Inspector with path/role/redaction projection. Editor-local syntax/schema feedback does not submit a Job. |
| Testdata preview | `inspectTestdataRecords`; `testdata-records` | Pooled Inspector with record/output caps. Optional generated-value preview is bounded; it does not invoke SUT traffic. |
| Replay draft / Schedule validation | replay/draft builders and `revision`, `run-input`, `debug-input`, `load-scenario` | Existing Inspector; owner, revision and scheduling policy rechecked at admission. |
| Schedule timing preview | Schedule `/preview` | Server timing/policy calculation, no Worker or SUT action. |
| Public `POST /jobs/validate` | `ServerRuntime.submit` → one-shot `WorkerMain` → `AttService.validate` | Retains a durable independent Validate Job and its full CLI-compatible semantics. It is not redefined as a restricted Inspector action. |
| CLI `validate` | CLI `AttService.validate` | Runs inside the CLI invocation, not through the Server Inspector pool. |
| Completed-Job artifact preview | ZIP-backed artifact reader | No validation Worker; authorized, verified published ZIP only. |

Implementation anchors: `att-web/frontend/src/api.ts`, `features/explorer/ExplorerPage.tsx`, `features/run/RunPage.tsx`, `features/load/QuickLoadPage.tsx`, `features/load/LoadPage.tsx`, `ApiServlet`, `ServerRuntime`, and `WorkerMain`. `TypedSidecarForm` edits values; its enclosing page owns draft validation.

## Cache and admission boundaries

The Inspector affinity key includes package, runtime/configuration and environment. Existing resource/configuration inspectors watch package sources and discard their bounded snapshots on change or watcher uncertainty. Sensitive field and source-browsing policy are part of the Inspector context; browser caching is additionally scoped by the server-issued authorization/masking context. Refresh and changed source must not revive another context's cached projection.

Execution is authorized independently of a displayed preview. Draft creation records the execution revision, admission calls the existing revision check, and Worker start verifies `expectedRevisionDigest` again before business execution. RUN selection validation also verifies the digest after reading the selection. A cached preview never removes these checks. Environment switches use a different Inspector context; draft principal/package checks remain on Server.

The existing inspection queue, response/source byte caps, watchdog kill, fair affinity selection, timeout diagnostics and audit apply to validation as well as Explorer requests. No new queue or duplicate parser is introduced by the prewarm feature. A separate validation queue needs measured Explorer starvation evidence first. Cold/warm and mixed Explorer/validation measurements remain a delivery gate for #278, not a speedup implied by this inventory.
