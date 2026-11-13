# Issue #348 handoff: remaining work

This branch is an implementation checkpoint for Phase 1. It contains the Server-owned SUT Metrics path, the optional `att-load-observability/v1` LOAD binding, a Job Details SUT Metrics tab, and admin catalog screens. It is **not a complete acceptance sign-off**: the SUT Metrics-specific test matrix and bilingual user/design documentation are still outstanding.

## Checklist by completion status

### Completed at implementation level

- Added versioned schemas/catalog entries for the LOAD observability binding, sources, profiles, immutable snapshots, collection manifests, and series artifacts.
- Added the optional binding to `att-load/v1.9`; scenarios without the binding remain compatible.
- Added Server-owned source/profile catalog APIs and UI, job snapshot resolution, on-demand preview, and post-run collection on a separate artifact path.
- Added a separate LOAD Job Details SUT Metrics tab. The existing Metrics tab remains unchanged, and no TPS/latency overlay is included.
- Kept SUT collection status separate from the LOAD execution verdict and report manifest/archive.

### Partially completed acceptance areas

- Data Source security/configuration, profile composition/override behavior, scenario compatibility, Prometheus collection, Live Preview, and the Job Details tab have implementation coverage described below, but do not yet have the dedicated SUT Metrics acceptance tests needed to close those criteria.
- Secret references are snapshot-bound by name while secret values are resolved at request time; endpoint DNS/IP and egress destinations are not constrained by an application allowlist.

### Not completed

- SUT Metrics unit and integration tests, including two viewers, unprivileged/hidden-tab behavior, HTTP failure and timeout handling, sidecar checksums/status transitions, and a 25-hour-equivalent budgeted run.
- Frontend tests dedicated to SUT Metrics states, visibility cancellation, live-to-post-run transition, and pagination. The existing Job Details tab test was updated for the added tab.
- English and Traditional Chinese observability documentation and example source/profile/scenario files.
- Configurable DNS/IP or egress allowlisting for administrator-configured Prometheus endpoints.

## Acceptance coverage

| #348 area | Status | Current implementation and remaining work |
| --- | --- | --- |
| Admin-managed Prometheus Data Sources | Partial | Admin-only CRUD and test routes, Settings UI, TLS verification/custom CA reference, bearer/basic secret references, role/reader visibility, tenants, timeouts, and query/sample/range limits are implemented. Secret values are read from `ATT_SECRET_*` environment variables and are not returned. Add an explicit deployment egress/DNS policy for administrator-configured endpoints and authorization tests. |
| Base, derived, and composite Metrics Profiles | Partial | Versioned profile records, typed parameters, label-safe PromQL substitution, single-parent `extends`, multi-profile `includes`, DAG/cycle and expansion-budget checks, target/source/parameter overrides, catalog persistence, and preview resolution are implemented. Add unit tests for graph errors, duplicate resolution, parameter escaping, permissions, and override rules. |
| LOAD scenario binding and schema | Partial | `att-load-observability/v1` is optional under `att-load/v1.9`; the Advanced LOAD editor preserves it and legacy scenarios without it remain valid. Schema definitions are registered in both schema catalogs. Verify legacy migration and current-schema round trips with explicit tests. |
| Post-run `query_range` collection | Partial | Collection uses the Worker execution window when available, segments long windows, writes outside `output/` and the sealed ZIP, uses separate PENDING/COLLECTING/READY/PARTIAL/UNAVAILABLE/FAILED state, and checksums series sidecars. The code has no dedicated test for a 25-hour-equivalent run, HTTP failures, timeouts, sample/query budgets, or the sealed-report non-mutation guarantee. |
| Running Live Preview | Partial | The on-demand API is snapshot-bound, defaults to 15 minutes and 30-second steps, coalesces identical requests, caches briefly without persistence, and is polled only while the tab is active and the document is visible. Add tests for two viewers, hidden-tab cancellation, authorization/visibility, and transition to post-run data. |
| Job Details SUT Metrics tab | Partial | A separate LOAD-only tab and series view are implemented. The existing Metrics panel is unchanged and no TPS/latency overlay is added. Add frontend tests for enabled/disabled/partial/failed states, pagination, hidden tab behavior, and status transition. |
| Security, tests, docs, and examples | Partial | API routes require an authenticated authorized reader, catalog writes/testing require system administration, end-user scenarios cannot submit source URLs or credentials, HTTP is restricted to loopback, redirects/proxies are disabled, and secret values are not serialized. No SUT Metrics-specific Java or frontend tests, bilingual user docs, or example source/profile/scenario files have been added yet. |

## Known issues and test gaps

- Add service unit tests using a loopback Prometheus fixture for Data Source validation, profile DAG resolution/cycles, injection-safe parameters, auth/tenant headers, timeout/response bounds, snapshot immutability, sidecar checksums, and status transitions.
- Add API integration tests for admin-only catalog mutation, unprivileged users, source/profile visibility, and two authorized viewers sharing a live-preview flight.
- Add a 25-hour-equivalent post-run test that verifies segmentation, query/sample budgets, actual LOAD timestamps, and collection status independence from the execution verdict.
- Add frontend tests for the new SUT Metrics tab, hidden-document/tab cancellation, no requests while hidden, live-to-post-run transition, and load-more paging.
- The Data Source endpoint is admin-managed and cannot be supplied by an end user, but there is no configurable egress allowlist or DNS/IP resolution policy. Deployments should constrain Server egress to approved Prometheus services; consider a configurable allowlist for a follow-up.
- Job snapshots pin endpoint, TLS/auth references, tenant, and limits. Secret values themselves are intentionally not persisted; the referenced environment variable is resolved when each request runs, so rotating a secret during a long collection can change the credential material mid-job.
- Add English and Traditional Chinese user documentation and sample Prometheus Data Source, base/derived/composite profiles, and LOAD scenario bindings.

## Explicitly deferred

Do not implement #349 TPS/latency overlays or correlation, #350 background collection/Grafana/offline report, or #351 SUT threshold verdicts in this phase.

## Verification run for this checkpoint

Verified with `JAVA_HOME=/home/box/opt/jdks/java17`:

- `mvn -pl att-server -am -DskipTests compile` — passed; server compiled with Java 17 and Java 8 modules retained their Java 8 target. The Maven frontend build, TypeScript check, and bundle budget check also passed.
- `mvn -pl att-worker,att-engine,att-server -am -Dtest=LoadScenarioTest,ApiServletIntegratedLimitsTest,ServerRuntimeLoadTerminalResultTest -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test` — passed. 55 tests passed: 33 LOAD scenario tests, 10 API integrated limits tests, and 12 LOAD terminal-result tests. Worker Java 8 sources and tests compiled in the reactor.
- `npm test` — passed: 47 files and 447 tests. The existing Job Details tab expectation was updated to include the new SUT Metrics tab. A prior run during the Maven `npm ci` step saw incomplete dependencies, and the first stable run exposed one pre-existing Quick Load timing failure; the final full rerun passed.
- `git diff --check` — passed before final commit.

No new SUT Metrics-specific test classes were added in this checkpoint; see the test gaps above. The integration and service coverage listed there remains required before claiming full #348 acceptance.
