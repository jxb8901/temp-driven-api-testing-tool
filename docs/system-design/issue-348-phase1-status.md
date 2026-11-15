# PR #357 cross-issue implementation checkpoint

This checkpoint summarizes the SUT Metrics work present in PR #357 across issues #348, #349, #350, #351, #358, and #359. The PR is based on `master` at `ccecc3d23d3d79a2c572f08464ae3d613c1283ba`. Issue status and acceptance remain tracked on their respective issues; PR coverage and passing tests do not close those issues or establish production-scale behavior.

## Implementation and evidence matrix

| Issue | Work represented in PR #357 | Verification evidence | Remaining boundary |
| --- | --- | --- | --- |
| #348 — LOAD SUT Metrics foundation | Admin-managed Prometheus sources and versioned Profiles; immutable Job snapshots; live preview; buffered post-run collection; atomic manifest integrity; normalized observations; Job Details view. | `ApiServletIntegratedLimitsTest`, `SutMetricsServiceTest`, `ServerRuntimeJobReplayTest`, `LoadScenarioTest#observabilityBindingSurvivesScenarioNormalizationAndComposition`, and `JobsPage.test.tsx`. | The Server allowlist validates host names, not resolved destination IPs; deployment egress policy is still required. The 25-hour fixture does not prove production-scale latency, throughput, or resource use. |
| #349 — LOAD/SUT correlation | UTC time selection, phase and range filters, gap-aware plots, partial-bin disclosure, and LOAD/SUT summaries. Percentile bins are not averaged; LOAD means are unweighted arithmetic means of observed bins. | `CorrelationPanel.test.ts` covers phase/range boundaries, unequal bin durations, gaps, UTC selection, and p95 behavior. | The view is a comparison aid; it does not resample, prorate clipped bins, estimate missing values, or infer interval throughput from the mean. |
| #350 — Grafana and offline report | Fixed Grafana datasource adapter; bounded offline report with digest-linked native archive, observations, chart previews, diagnostics, and optional verdict state; retention recovery for report retries. | `SutMetricsGrafanaOfflineReportIntegrationTest` and the report/recovery tests in the server suite. | No live customer Grafana deployment or production archive scale was exercised. |
| #351 — thresholds and verdicts | Pinned per-target thresholds; asynchronous, durable evaluation; coverage-aware NOT_EVALUATED outcomes; combined LOAD/SUT acceptance and Studio evidence. Evaluation waits for terminal mixed collection. | `SutThresholdEvaluatorTest`, threshold tests in `SutMetricsServiceTest`, and report integration tests. | Delayed external-verdict and offline-report paths require exact-head CI and deployment-level follow-up; no production verdict service or policy has been certified here. |
| #358 — business-call metrics | Server-owned DBHelper or Package Tool call groups with after-drain or steady-window sampling, explicit scalar/mapping semantics, bounded execution, and safe diagnostics. | Business-call tests in `SutMetricsServiceTest` and revision-change coverage in `ServerRuntimeJobReplayTest`. | Calls fail closed when the admitted Package or Runtime Config changes. The original executable revision is not retained for later replay, so the affected metric is NOT_EVALUATED with a diagnostic. |
| #359 — multi-output mapping | One DBHelper or Package Tool call can produce multiple numeric outputs, mapped by explicit result columns to immutable LOAD `workloadId`/`mixId`; duplicates and ambiguous identities remain diagnostic. The Studio mapping editor saves these calls in a separate business-only Profile. | Mapping, identity, and call-count cases in `SutMetricsServiceTest`; `SettingsPage.test.tsx` covers default draft, add, save, and validation of a business-only binding. | The call is shared once per Job/call group; each output keeps its own mapping and quality result. Unknown identities are not inferred or joined heuristically. |

## Collection and revision behavior

For mixed Prometheus and business-call Jobs, after-drain calls run promptly after LOAD finishes. Their sidecars are published while the combined manifest remains `PENDING`; Prometheus collection starts after the configured after-buffer and one-step ingestion grace. Threshold evaluation starts only when the combined collection reaches a terminal state. Per-Job calls remain bounded, and the manifest records phase progress so restart recovery does not repeat a completed business call.

Business-call execution uses the Package and Runtime Config revisions pinned at LOAD admission. If either revision changes before collection, the call does not run against current files. The sidecar records `NOT_EVALUATED` and a specific safe diagnostic. Executable source snapshots are not retained to run the admitted revision later.

## Verification record

Each local result and hosted check must identify the exact source revision. A local test result is not hosted CI evidence, and neither substitutes for production deployment or scale validation.

- Local verification for the review-fix changes: the affected Server reactor (`SutMetricsServiceTest` and `ServerRuntimeJobReplayTest`) passed 88 tests, and the Settings/Correlation frontend tests passed 13 tests. Maven also type-checked and built the frontend and passed the initial bundle budget.
- Earlier hosted CI run `43669628531` passed 8/8 checks on PR head `b40b770dbeabc65109322d69d08c7a074e1c7795`; it predates these review fixes and does not validate them.
- Production deployment, live Grafana, destination-IP egress enforcement, external verdict integration, and production-scale 25-hour collection remain outside the evidence above.
