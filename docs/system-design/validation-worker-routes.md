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
| Internal RUN/DEBUG prewarm admission | `prewarm-eligibility` | Existing Inspector freshly reads effective helper configuration and execution revision; no resource connection or Tool invocation. Unsupported native environment consumers fall back to cold dispatch. |
| CLI `validate` | CLI `AttService.validate` | Runs inside the CLI invocation, not through the Server Inspector pool. |
| Completed-Job artifact preview | ZIP-backed artifact reader | No validation Worker; authorized, verified published ZIP only. |

Implementation anchors: `att-web/frontend/src/api.ts`, `features/explorer/ExplorerPage.tsx`, `features/run/RunPage.tsx`, `features/load/QuickLoadPage.tsx`, `features/load/LoadPage.tsx`, `ApiServlet`, `ServerRuntime`, and `WorkerMain`. `TypedSidecarForm` edits values; its enclosing page owns draft validation.

## Server-side route regressions

The route inventory is backed by ServerRuntime boundary tests rather than a second dispatcher implementation:

- Explorer resource list, detail, source and pagination reuse: `ServerRuntimeInspectionTest.listsPackageResourcesThroughTheBoundedInspectorWorkerAndSignsPaginationCursors`.
- Declared/effective/compare configuration and testdata-record pagination: `ServerRuntimeInspectionTest.returnsDeclaredEffectiveAndComparisonConfigurationThroughTheBoundedInspectorWorker` and `ServerRuntimeInspectionTest.pagesTestdataRecordsThroughTheBoundedInspectorWorker`. The regression verifies bounded pagination and that successful pages reuse one Inspector process.
- Debug form, normalized draft ownership, classified-default redaction and stale-source rejection: `ServerRuntimeInspectionTest.validatesDebugDraftsWithoutDisclosingDefaultsAndRejectsStalePrincipalBoundSubmissions`.
- RUN selection and stale package revision: `ServerRuntimeUnifiedRunTest`.
- Quick Load draft validation and stale revision: `ServerRuntimeQuickLoadTest.quickLoadDraftOmitsDebugImportsAndRunsOnlyExplicitLoadTestdata`. A broken nested Flow produces validation diagnostics; Quick and Advanced Load draft validation reuse the Inspector and leave the package Tool invocation marker absent.
- Advanced Load/catalog and schedule validation: `ServerRuntimeScheduleTest`.
- Replay draft revalidation and durable one-shot Validate Job behavior: `ServerRuntimeJobReplayTest` and `ServerRuntimeTest`.
- Public authenticated `POST /jobs/validate` integration: `AuthenticationIntegrationTest`.

Engine-level testdata preview coverage additionally exercises classified-field redaction, pagination bounds, generated-value caps and missing descriptors in `PackageConfigurationInspectorTest.pagesTestdataPreviewAndKeepsClassifiedFieldsProtectedWhenMaskingIsOff` and adjacent testdata-preview cases.

## Cache and admission boundaries

The Inspector affinity key includes package, runtime/configuration and environment. Existing resource/configuration inspectors watch package sources and discard their bounded snapshots on change or watcher uncertainty. Sensitive field and source-browsing policy are part of the Inspector context; browser caching is additionally scoped by the server-issued authorization/masking context. Refresh and changed source must not revive another context's cached projection.

Execution is authorized independently of a displayed preview. Debug draft creation checks fresh source revisions before and after input validation, admission independently checks the revision, and Worker start verifies `expectedRevisionDigest` again before business execution. RUN selection validation also verifies the digest after reading the selection. Warm dispatch additionally checks effective configuration eligibility against fresh sources and the assigned Worker rechecks it. A cached preview never removes these checks. Environment switches use a different Inspector context; draft principal/package checks remain on Server.

The existing inspection queue, response/source byte caps, watchdog kill, fair affinity selection, timeout diagnostics and audit apply to validation as well as Explorer requests. No new queue or duplicate parser is introduced by the prewarm feature. A separate validation queue needs measured Explorer starvation evidence first. Cold/warm and mixed Explorer/validation measurements remain a delivery gate for #278, not a speedup implied by this inventory.

The separate execution prewarm boundary is covered by `ServerWarUiIT.packagedWarRunAndDebugKeepColdEquivalentResultsWhenLeasingOneShotWorkers`, which compares packaged-WAR RUN/DEBUG PASS, FAIL, cancellation and Tool-timeout outcomes in cold and warm modes. `ExecutionWorkerSemanticParityTest.warmAndColdRunAndDebugPassAndFailHaveEquivalentStateEventsAndReports` also submits a successful LOAD with the pool enabled and asserts `workerDispatch=cold`, preserving the existing isolated one-shot LOAD path. These regressions close those specific route cases; the wider #278 route/deployment, RSS and Validate/Preview acceptance matrix remains open.
