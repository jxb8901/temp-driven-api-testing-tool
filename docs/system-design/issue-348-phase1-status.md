# Issue #348 Phase 1 status

This branch contains the Server-owned SUT Metrics path, optional `att-load-observability/v1` binding, Settings catalog screens, LOAD Job Details tab, bilingual user documentation, and examples. This is an implementation checkpoint, not a complete acceptance sign-off: API/integration coverage and several UI lifecycle cases remain open.

## Completed in this checkpoint

- Rebased PR #357 onto current `master` and updated the PR base to `master`.
- Added exact HTTPS host matching through the Server `ATT_SUT_METRICS_ALLOWED_HOSTS` environment variable. HTTP remains loopback-only; redirects and proxies are disabled.
- Fixed direct base-profile target binding and scenario parameter precedence. Stable query IDs now include source ID, profile path, target ID, and metric ID. Resolved profile/source target and metric limits are enforced.
- Preserved actual LOAD execution timestamps separately from the expanded Prometheus collection window.
- Stopped post-run requests once per-source or per-Job sample/artifact budgets are reached, and mark unissued queries in collection diagnostics.
- Bounded collector, preview, and response-body executor queues; mapped capacity rejection to HTTP 503 and live-request deadline expiry to HTTP 504.
- Added target/profile/range selection, stale live-sample status, min/average/max summaries, and query metadata for the SUT Metrics tab.
- Added checksummed sidecar provenance fields and kept SUT evidence in the sibling `sut-metrics/` directory.
- Added five Java service tests and three SUT Metrics UI regression tests. Added English and Traditional Chinese documentation plus source, base/derived/composite profile, and scenario examples.

## Acceptance still open

- API integration tests for system-admin-only catalog writes, reader authorization, role/tenant restrictions, secret headers, concurrent viewers sharing a live-preview flight, and HTTP failure/timeout/response-size handling.
- Collection tests for checksum tampering, unavailable/partial transitions, failed sidecars, timeouts, and a total-budget exhaustion across multiple successful metric queries.
- UI tests for live-to-post-run transition, all server collection states, hidden Job tab cancellation, post-run filter paging, and API filtering across pages.
- The hostname allowlist does not pin DNS resolution to the checked IP. Deployments still need network egress rules that restrict resolved destination IPs. Do not treat a hostname allowlist alone as a complete DNS-rebinding defense.
- Snapshot secret references are pinned by name, while secret values are resolved from `ATT_SECRET_*` for each request. Rotating a secret during a long collection may change the credential material mid-Job.

## Acceptance coverage

| #348 area | Status | Evidence and remaining work |
| --- | --- | --- |
| Admin-managed Prometheus Data Sources | Partial | Admin CRUD/test routes, TLS, auth secret references, reader/role visibility, tenants, bounded limits, loopback HTTP, and exact HTTPS host allowlist are implemented. Add API authorization/header/timeout tests and deployment IP-level egress policy. |
| Base, derived, and composite Metrics Profiles | Partial | Typed parameters, safe interpolation, DAG validation, override rules, expansion budgets, and immutable profile snapshots are implemented. Service tests cover direct base binding, scenario-over-target parameter precedence, source-scoped IDs, and allowlist validation. Add DAG-cycle, duplicate resolution, and broader permissions tests. |
| LOAD scenario binding and schema | Partial | Optional root-level `observability` remains compatible with scenarios that omit it. Both schema catalogs contain the binding. Add explicit legacy/current schema round-trip and schedule snapshot tests. |
| Post-run `query_range` collection | Partial | Uses actual Worker execution times, segments at six hours, writes checksummed provenance-rich sidecars separately from reports/archive, and stops at sample budgets. Tests cover a 25-hour window, five segments, timestamp preservation, report/archive bytes, checksum, and early budget stop. Add injected source failures, checksum tampering, and state-transition tests. |
| Running Live Preview | Partial | Snapshot-only, bounded range/step, request coalescing/cache, visibility-aware polling, and target/profile/range selection are implemented. UI tests cover stale summary rendering and hidden-document abort. Add concurrent-viewer and live-to-post-run API tests, plus explicit timeout/capacity integration tests. |
| Job Details SUT Metrics tab | Partial | Dedicated LOAD-only tab shows query status, coverage, freshness and min/average/max. UI tests cover filters, stale status, statistics, and document-hidden cancellation. Add disabled/partial/failed transitions, hidden-tab cleanup, pagination, and post-run filtering tests. |
| Security, docs, and examples | Partial | Scenario input cannot supply endpoint URLs or credentials; responses are private/no-store; secret values are not serialized; HTTPS hosts must exactly match the Server allowlist. Bilingual docs and example files are present. IP-level DNS/egress enforcement remains deployment-owned. |

## Explicitly deferred

Do not implement #349 TPS/latency overlays or correlation, #350 background collection/Grafana/offline report, or #351 SUT threshold verdicts in this phase.

## Local verification

- `mvn -pl att-server -am -DskipTests compile` — passed; this includes Java compilation, frontend TypeScript build, static assets, and bundle budget.
- `mvn -pl att-server -am -Dtest=SutMetricsServiceTest -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test` — passed, 5 tests. The fixture uses loopback HTTP and requires socket access. The reactor also built the frontend, type-checked it, and passed the bundle budget.
- `npm test -- src/features/load/JobsPage.test.tsx` — passed, 63 tests including the three new SUT Metrics tests.
- `python3 tools/verify_documentation.py` — passed after regenerating the bilingual Reference schema matrices and documentation inventory.
- Full Java integration suites and the complete frontend suite have not been rerun on this implementation head.
