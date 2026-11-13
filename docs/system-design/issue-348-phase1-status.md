# Issue #348 Phase 1 implementation status

This document tracks implementation evidence for draft PR #357 against `master` at `ccecc3d23d3d79a2c572f08464ae3d613c1283ba`. The feature branch is based on that commit. Test and CI results apply to the source revision identified by each result. This remains an implementation checkpoint, not a complete production acceptance sign-off.

## Acceptance coverage

| #348 area | Status | Current evidence and remaining boundary |
| --- | --- | --- |
| Admin-managed Prometheus Data Sources | Implemented locally | The servlet tests cover Admin-only writes, request-size rejection, reader/role visibility, Job ownership, and source authorization. They also verify the pinned tenant, bearer-header forwarding, no-store responses, timeout and response-size errors, concurrent viewers, and queue-capacity 503. Source and profile views do not expose secret values. External destination IP policy remains deployment-owned. |
| Base and composite profiles | Implemented locally | Base profiles own metric definitions and typed parameters; source, tenant, and target bindings belong to composite includes or explicit Scenario bindings. Only one-level composite presets are accepted. Includes must reference existing base profiles; `extends`, derived kinds, nested composites, missing bindings, and duplicate implicit aliases are rejected. Explicit aliases distinguish repeated base includes. Service tests cover direct/base bindings, typed parameter overrides, one-level presets, and rejected profile structures. |
| Scenario binding and Job snapshots | Implemented locally | The Engine regression verifies the optional `att-load-observability/v1` binding survives composition and normalization while staying out of report summaries. Runtime tests verify that later profile edits affect a new Schedule occurrence, while replaying a dispatched Job retains its pinned snapshot. Jobs without the binding remain compatible. |
| Running Live Preview | Implemented locally | The API path uses the immutable Job snapshot and enforces Job/source visibility. Tests cover same-window cache/coalescing for staggered viewers of an older Job, successful empty and mixed Prometheus results, chronological coverage across label-sorted series, bearer secret forwarding without response leakage, tenant pinning, timeout, oversized responses, saturation, and private no-store headers. The UI tests cover visible-tab polling, hidden-document pauses, request cancellation, the live-to-post-run transition, shared timestamp scaling, and visible gaps. |
| Post-run collection and companion sidecars | Implemented locally | The durable manifest stays `PENDING` until the after-buffer and one query-step ingestion grace have elapsed; the bounded scheduler does not hold collector workers. A query is not `READY` unless it contains a sample after LOAD completion when an after-buffer is configured. Service tests cover delayed samples, 25-hour segmented collection, actual LOAD/phase windows, bounded query/sample/artifact budgets, explicit no-data/partial results, failures, and tamper detection. A separate bounded transition-integrity history binds manifest bytes, Job identity, and snapshot identity; reads validate manifest shape and sidecar metadata. Restart tests verify published sidecars remain readable and interrupted collections recover explicitly. |
| Minimum normalized observation contract | Implemented locally | `att-sut-metrics-observation/v1` is the #348 provider-neutral sampled-series envelope. It carries effective binding identity, source/profile/target revision hashes, metric unit/kind, UTC execution/query windows and step, phase boundaries, availability/freshness, coverage/gaps, and safe provenance. The JSON Schema and golden contract fixture are available for #349/#351. |
| Job Details SUT Metrics tab | Implemented locally | The dedicated LOAD-only tab shows coverage, freshness, status, and unit-aware summaries. The chart uses one timestamp scale for each selected window, sorts samples chronologically, and starts a new path across gaps wider than the expected sampling interval. The Jobs page tests cover all collection states, cancellation on tab changes, hidden-document pause/resume, filtered paging, and Job changes during live preview. No ATT TPS/latency overlay is included. |
| Security, docs, and examples | Partial | Scenarios cannot submit endpoints, credentials, or arbitrary PromQL. HTTPS hosts must exactly match `ATT_SUT_METRICS_ALLOWED_HOSTS`; HTTP is loopback-only, redirects and proxies are disabled, and English/Traditional Chinese docs explain the required deployment egress control. The application does not pin DNS-resolved IPs, so the network policy must restrict the allowed destinations. Secret values remain server-managed and are not stored in snapshots. |

## Remaining acceptance boundary

- Deployments must enforce network egress restrictions for the resolved Prometheus destination IPs. The application allowlist checks the configured host name but does not pin or revalidate the connected IP.
- The 25-hour case is a bounded local fixture test. It does not establish production-scale Prometheus latency, throughput, or resource usage.
- Hosted CI must pass on the commit containing these local changes before the PR review threads can be resolved or the implementation called fully accepted.

## Provider boundary

Phase 1 remains Prometheus-first. Issue #348 owns the minimum provider-neutral `att-sut-metrics-observation/v1` contract and its golden fixture. Issue #349 elaborates cross-clock/phase alignment and correlation semantics. Future DBHelper and Package Tool callable metrics belong to #358 and may extend this contract with snapshot/window aggregates. This PR does not add DB/Tool collectors, another JDBC Data Source registry, or SQL safety certification.

Secret references, not secret values, are stored in source definitions and Job snapshots. Values come from the Server's `ATT_SECRET_<NAME>` environment and are resolved when a query runs. Rotating that environment requires a Server restart; if a collection is interrupted during restart, its state becomes `FAILED` with a diagnostic rather than remaining `COLLECTING`.

## Local verification on this workspace

- `mvn -pl att-server -am '-Dtest=ApiServletIntegratedLimitsTest,SutMetricsServiceTest,ServerRuntimeJobReplayTest' -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test` — passed, 65 tests (15 API, 20 SUT Metrics service, 30 replay/runtime); Maven also built and type-checked the frontend and passed the bundle budget. Loopback and Worker-process access were enabled for this run.
- `mvn -pl att-engine -am '-Dtest=LoadScenarioTest#observabilityBindingSurvivesScenarioNormalizationAndComposition' -Dsurefire.failIfNoSpecifiedTests=false test` — passed, 1 test.
- `npm test -- --run src/features/load/JobsPage.test.tsx` — passed, 69 tests.
- `git diff --check` and changed-file conflict-marker scan — passed.

The full Java integration suite and full frontend test suite are outside the local evidence recorded here. Hosted CI for the current PR head is tracked in the [SUT Metrics phase-one pull request](https://github.com/jxb8901/temp-driven-api-testing-tool/pull/357); neither local nor hosted tests establish production-scale Prometheus performance or deployment egress policy.

## Explicitly deferred

Do not implement #349 TPS/latency overlays or correlation, #350 background collection/Grafana/offline report, or #351 SUT threshold verdicts in this phase.
