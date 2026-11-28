Warning: truncated output (original token count: 13174)
Total output lines: 396

# ATT 4.6.1 — patch release

## Validation and Server packaging

- Separate Studio/immutable source inspection from execution output readiness so package validation no longer reports false `ATT-PATH-001` against read-only Package revisions with a normal relative `outputDirectory` (#431).
- Bind File Expression descriptor source scope during runtime-value validation (including nested Flow bodies) so `&{./...}` resolves from the referring descriptor directory, matching static validation and execution (#432).
- Ship Apache POI (`poi` / `poi-ooxml` and required transitives) on the Server WAR classpath so Job Admission source-provenance/resource indexing no longer fails with `ClassNotFoundException: org.apache.poi.ss.usermodel.DataFormatter` (#433).
- Return a structured Server error for submission `LinkageError` failures instead of relying on an unhandled container response (#433).

# ATT 4.6.0 — formal release (2026-11-23)

## Native Test Authoring

- Activate the `att-testcase/v1` Definition and Flow binding model; resolve inline and Excel-backed Cases with stable IDs, typed inputs, bounded parsing, source provenance, preview and revision checks (#391–#394).
- Add validated Native Case RUN through local/Remote CLI and Server drafts, including immutable Package source closure, preserved run IDs, per-Case evidence, retry lineage and stale-source rejection (#393).
- Add the Studio Native Test Case editor and read-only preview, with revision-fenced Change Set publishing, secret redaction and workbook row diagnostics (#395).
- Add implicit cross-Definition Suites, qualified Case and tag selection, deterministic ordering, bounded selectors, fail-fast/skipped evidence and retry of the latest eligible failed Cases (#396).
- Add source-independent HTML, CI JSON, JUnit and XLSX reports with protected report regeneration and Run-bound Server publication. Excel-backed Definitions may also request a separate source-workbook-shaped XLSX; the accepted workbook is captured before RUN, rows are matched by pinned provenance, sensitive values are redacted across worksheets, and formula caches that could retain redacted values cause deterministic fallback to the standard report (#397).
- Resolve source-workbook OOXML relationships within the package root during Server archive validation, preserving safe relative relationships while rejecting true root traversal (#397).
- Keep optional LOAD report downloads from holding the native archive publication lock, and make artifact-read cleanup release its lock and capacity exactly once (#397).
- Add the S10 bilingual Template–Flow–Stage compatibility matrix and paired golden fixture. Keep the Stage runtime path supported; the fixture does not prove external compensation or database rollback (#398).
- Add Default Package Native Case examples, bilingual Quick Start and a resource migration matrix while retaining legacy Workbook/Sidecar/XML and direct LOAD examples without forced conversion (#413).
- Align Server version responses, UI fallback text and browser acceptance fixtures with the 4.6.0 release version.

## Package Source Management

- Add trusted Package-scoped editor grants for shared Packages, covering Online Edit, ZIP import, Change Set publication, source history and restore. Grants remain restricted to active USER identities and current Package authorization; they do not grant ADMIN or Central Runtime Config access (#387).

## Open follow-ups tracked outside the 4.6.0 release scope

- #387 retains deployed multi-role operator acceptance. #393 is closed: that closure accepts Native RUN for the issue and is not a published 4.6.0 GitHub Release. #394, #395, #396, and #397 remain open for their own acceptance even though the implementation is integrated on master. #413 retains any remaining Default Package migration acceptance.
- Concepts, Reference, Studio Explorer, and Quick Start now lead with Flow + `testcase.yaml` + Data Source → Resolution → Resolved Case → Suite selection → Case Execution. Workbook/Stage/Template stays a supported legacy path. `att-testcase/v1` remains an exact 1:1 record-to-case contract; n-to-m generators stay deferred under #419 (#424).
- S08 Native LOAD Case targets are tracked by #418; S09 ATT Testdata and bounded generated Case sources by #419; S11 Debug and Native Case interoperability by #420.
- Helper-specific external compensation and teardown validation from the S10 analysis is tracked by #421. Bilingual ADR-0001 review and approval for overall architecture closure is tracked by #422. #388 remains open for these follow-ups and overall acceptance.

The 4.4.1 and 4.5.0 development trains were not published as separate releases; their included changes are recorded above under 4.6.0.

# ATT 4.4.0 - release candidate (2026-11-18; acceptance pending)

- Add Package registration and immutable source revision contracts, source authoring and Change Set publication, plus Studio source editors for Package files, Testcases, and Testdata (#250–#255).
- Add source-pinned Job execution with an OS-enforced read-only boundary for accepted Package and Config trees; Linux requires `bubblewrap` and still needs hosted CI verification (#254).
- Add Config impact analysis and activation fencing. Central Config activation remains fail-closed where a production file-backed activation guard is unavailable (#335).
- Keep Epic #233 open. Source history/diff/restore and Package export (#256), integrated end-to-end acceptance and rollout documentation (#257), and Central Config SUT Metrics authoring (#382) remain outstanding.
- Release acceptance is pending. Do not tag or publish until Linux isolation and exact-head hosted CI pass, and the remaining #233 acceptance items are resolved.

# ATT 4.3.1 - release candidate (2026-11-16; not released)

- Add versioned Metric Source, Metric Group, flat Profile, normalized Observation v2, Job snapshot v2, and execution payload v2 contracts with bounded schema validation and v1 compatibility readers (#374).
- Reuse one admitted acquisition for multiple explicitly mapped outputs from SQL/Package Tool wide rows or Prometheus labelled series; preserve per-output quality, labels, provenance, and time coverage (#375).
- Resolve pinned logical Groups over observations without new source calls or implicit aggregation; enforce source, fetch, output, label, row, series, sample, and artifact budgets (#376).
- Add Settings authoring and static previews, group-aware Job Details and Correlation views, and a self-contained offline report Group index while preserving sealed native artifacts (#377).
- Keep historical v1 Job contracts readable and avoid rewriting old snapshots or reports. This release adds no Long Rows, dynamic metric-name pivot, or new threshold engine (#374–#377).
- Release acceptance is in progress under #378. Do not tag or publish until the exact release candidate passes hosted CI and the complete package gate.

# ATT 4.3.0 - release preparation (2026-11-16)

- Establish the 4.3.0 baseline from the Issue #348 SUT Observability implementation (`612e277a53b7a21a03261834e5ff56a58b0cb64b`, PR #357).
- Include the Prometheus/Grafana SUT metrics collection, immutable snapshots, correlation, threshold evidence, DBHelper/Package Tool metrics, multi-column/row mappings and versioned offline report from #348/#349/#350/#351/#358/#359.
- Release preparation only: full packaging, integration smoke, supported deployment security/egress and production-scale operational readiness still require release-gate verification before publishing or tagging.

# ATT 4.2.10 - 2026-11-11

- Introduce `att-tool/v1` as a versioned, JAR-embedded schema for Package-owned Tools, with deterministic `tools/*.yaml` discovery and Package-scoped Tool resolution (#352).
- Keep DB/MQ/HTTP/SSH Helper definitions centralized; reject unsupported legacy global Tools with explicit migration guidance instead of dispatch fallback.
- Add Package Tool source inspection to Explorer; remove Tool and Tool Group catalogs from Shared Configuration; include Package Tool descriptors in revision fingerprints.
- Convert bundled Package examples from global Tool declarations and document migration from older configurations.
- Add the Studio Read-only Validate workspace for shared/effective Config, registered Packages, Explorer resources, and executable LOAD Scenarios; advertise the `att-validation/v1` Server capability and report contract (#355).
- Add Server-managed Prometheus sources and reusable SUT Metrics profiles to LOAD Jobs, with immutable per-Job snapshots, bounded post-run sidecars, on-demand live preview, and a dedicated Job Details tab (#348).
- Add the Grafana SUT Metrics adapter and a verified, versioned offline LOAD report that references the native archive by digest and includes bounded observations, chart previews, diagnostics, and optional verdict state (#350).
- Keep LOAD report recovery active through retry backoff while releasing the publication fence, so persistent optional-report failures do not block age, manual, or storage-pressure retention (#350).
- Add pinned per-target SUT maximum thresholds, coverage-aware asynchronous evaluation and recovery, durable versioned records, authorized combined LOAD performance acceptance, and Studio rule/evidence views (#351).
- Short-circuit SUT evidence loading only after a proven required-rule violation; continue evaluating optional rules (#351).
- Keep native LOAD results available when optional SUT snapshots are corrupt, and retain SUT collection evidence when threshold records are corrupt without inferring a combined MET verdict (#351).
- Return the configured 413 size-limit response for oversized SUT Metrics requests and enforce Admin-only catalog writes.
- Preserve live SUT Metrics cache coalescing, chronological coverage, explicit empty-result status, and timestamp-scaled chart gaps (#348).
- Align #348 with the simplified two-form profile model: base profiles own only metric definitions and typed parameters; source/tenant/target bindings belong to direct Scenario bindings or one-level composite presets. Reject `extends`, derived profiles, nested composites, and incomplete preset bindings.
- Publish the #348 provider-neutral `att-sut-metrics-observation/v1` contract with pinned source/profile/target revisions, UTC execution/phase/query windows, coverage, gaps, availability, and safe provenance.
- Keep post-run metrics PENDING through the configured after-buffer and one query-step ingestion grace; mark missing or stale-tail coverage PARTIAL using a two-step/30-second tolerance, and atomically publish each snapshot-anchored manifest integrity transition with its manifest.
- Collect after-drain business metrics promptly while mixed Jobs remain PENDING for buffered Prometheus collection; fail closed with NOT_EVALUATED diagnostics when pinned Package or Runtime Config revisions change.
- Draft mapped business calls in a separate business-only Settings Profile, and label LOAD correlation means as unweighted arithmetic means of observed bins.
- Show verified mixed-Job Business Metrics as provisional read-only evidence during the Prometheus buffer, poll with deadline-aware backoff, and resume only the unfinished phase after restart; fail closed when a restart interrupts an uncertain business call. Keep the starter SQL's case-sensitive result mappings aligned with its lowercase columns.
- Serialize manifest writes per Job while keeping reads independent across Jobs.
- Add a read-only Correlation view to LOAD Job details for comparing selected native LOAD metrics with a post-run SUT query over UTC time, with workload, phase, time-range, and SUT-series filters (#349).
- Preserve native-bin and source-gap semantics in Correlation charts, including partial-range bins, extrema-aware downsampling, a 1,200-point cap, selected-versus-displayed counts, and no value extension across bins omitted by downsampling (#349).
- Add owner-scoped asynchronous validation records with bounded retention, status/list/cancel APIs, restart recovery, and contextual Studio validation that survives navigation and reload while preserving the synchronous validation contract (#364, #367).
- Preserve authorized opaque source references for Tool Group manifests and improve Explorer source presentation with compact related-manifest links (#365, #366, #367).
- Remove duplicate legacy global Tool Groups after auditing their Package-owned equivalents, verify the default Package schema inventory, and fix shared Flow identity across Run, Debug, Quick Load, and Advanced Load (#368, #367).

# Changelog

# ATT 4.3.0 - 2026-11-06

- Add hot-reloaded, deployment-controlled Package registration with atomic candidate validation, last-known-good retention, sanitized status, and content-addressable registry revisions (#250).
- Expose read-only registry and source-scope contracts with separate Package, Manifest, Central Runtime Config, effective Config, and Engine schema revision identities; bind Package drafts and approvals to the registry revision.
- Add shared Package and ADMIN Change Sets with bounded drafts, revision-fenced validation and review, File-resource-safe moves, journaled atomic publication and recovery, and fail-closed validator and Config pre-commit hooks (#252).

# ATT 4.2.9 - 2026-11-05

- Allow operational users to rerun shared Jobs while keeping replay drafts private and prese…7174 tokens truncated…eive/request queue defaults with explicit argument precedence.
- Advance current configuration, Tool Group, Template, and Flow schemas to `att-config/v2.9`, `att-tool-group/v2.8`, `att-template/v3.2`, and `att-flow/v3.2`, retaining every previous schema at its catalog-registered `schemas/history/` path.
- Expand English and Traditional Chinese HTTPHelper, MQHelper, Tool/result, validation, and migration guidance; update runnable SIT/UAT examples to the current `result.format` contract.

## 3.5.2 - 2026-09-25

- Implement issue #70: introduce `att-httphelper/v1.0` with environment-bound logical HTTP services, bounded pooled transport, secure auth/TLS defaults, typed Action results, HTTP metadata/evidence, and EN/ZH documentation.
- Implement issue #71: enrich older-schema validation errors with current-version migration guidance, preserve original violations and source locations, and relocate legacy schemas to `schemas/history/` without changing their contracts.

- Implement issue #59: extend MQHelper message defaults and IBM MQ metadata, preserve MsgId/CorrelId request/reply behavior, adopt common `saveAs` payload handling, publish MQ metadata directly under Action output, and document the complete English/Chinese contract.

- Implement issue #60: add `att-mqhelper/v1.1` logical groups with inherited defaults, immutable physical instances, random/round-robin selection, explicit instance overrides, isolated pools, secret-safe output/evidence, and EN/ZH documentation while preserving v1.0 descriptors.

- Implemented Issue #53 randomized think-time ranges for closed-VU load workloads while preserving the existing fixed `execution.thinkTime` syntax and keeping arrival-rate workloads unchanged.
- Added reusable `ThinkTimePolicy` normalization, deterministic run-level seeding with isolated per-VU random streams, inclusive millisecond range sampling, report-safe effective-seed/policy output, schema updates, an offline example, and EN/ZH Reference coverage.

- Restore typed TimePolicy` normalization, deterministic run-level seeding with isolated per-VU random streams, inclusive millisecond range sampling, report-safe effective-seed/policy output, schema updates, an offline example, and EN/ZH Reference coverage.
- Fixed production closed-VU think-time waiting so sampled delays longer than the scheduler's 50 ms cancellation quantum are fully consumed in bounded cancellable slices; added a real-system-timing regression test to prevent throughput inflation from truncated waits.
- Implemented Issues #50 and #51 with `att-load/v1.1` multi-workload scenarios while retaining `att-load/v1.0` single-target compatibility. A run may own independent fixed-arrival-rate workloads with per-target TPS/concurrency or independent closed-VU pools with fixed target/user count/think-time policy.
- Added a synchronized multi-workload coordinator with common T0/phase envelope, atomic pre-start target validation, shared run-scoped DB/MQ resources, isolated iteration Context/output, workload-qualified VU identity, per-workload evidence partitioning, and deterministic workload-aware random streams.
- Added aggregate plus per-workload metrics and thresholds; aggregate percentiles are calculated from combined raw latency observations rather than averaging workload percentiles. Mixed arrival/closed workloads and ambiguous unscoped CLI load overrides for multi-workload scenarios are rejected in v1.1.

## 3.5.1 - 2026-09-23

- Implemented Issue #36 environment profiles with one common `att-config/v2.6`, deterministic `--env` selection, shallow typed DB/MQ overlays, stable logical helper IDs, and backward-compatible complete `--config` workflows.
- Applied the same effective environment contract to `run`, `validate`, standalone `debug`, and `load`; unknown profiles, unsupported overlay fields, missing descriptors, and duplicate helper IDs fail before external execution.
- Added English/Chinese profile configuration, migration, CI, secret-handling, and scenario guidance plus regression coverage for precedence and compatibility.
- Completed Issues #40-#42 documentation architecture migration: the Reference Manual now follows product concepts rather than release chronology, with Run/Debug/Load as peer execution modes, Tool/DBHelper/MQHelper as peer resources, and centralized `EXEC`/`META` Context plus common Action result/evidence semantics.
- Split tutorial and maintainer material into canonical `docs/quick-start.md` and `docs/system-design/`, recorded an auditable legacy-to-current migration map, and retained generated standalone EN/ZH Reference Manuals for distribution.
- Added CI semantic coverage checks for the Reference information architecture in addition to deterministic EN/ZH generation/freshness checks; this documentation migration does not change ATT runtime behavior.
- Implemented Issue #43 as a documentation release gate covering ATT version consistency, EN/ZH module and numbered-heading parity, generated Reference freshness, local links/anchors, schema references, CLI command/option coverage, secret-safe examples, and canonical documentation ownership.
- Added `DocumentationExamplesTest` to regression-test the checked-in SIT/UAT profiles and DB/MQ/Tool resources through the real CLI, plus dry-run, standalone debug, closed-VU load, and arrival-rate load examples under Java 8 CI.
- Fixed documentation drift exposed by the new gate, including generated Reference schema/example links, a stale Quick Start anchor, incomplete CLI load/compatibility option coverage, and the current schema-catalog identity.
- Implemented Issue #39 direct DB Action execution controls: `query` and `update` accept Action-level `timeoutMs`; read-only `query` Actions may use bounded `ASSERTION`/`TIMEOUT` retry with per-attempt evidence and final/winning-result publication; mutating `update` Actions explicitly reject automatic retry because the mutation outcome may be uncertain after timeout or database/transport failure.
- Reused the existing Action attempt lifecycle and DBHelper timeout machinery, including the shorter effective Action/statement timeout, fresh timeout per attempt, retry interval outside the attempt timeout, terminal ordinary SQL errors, and Java 8 regression coverage for assertion retry, timeout retry, exhaustion, update timeout, and retry-safety validation.

## 3.5.0 - 2026-09-22

- Released `att-load/v1.0` scenario loading, CLI overrides, schema/semantic validation, Template/Flow/Tool target resolution, and compatibility-safe configuration guidance.
- Unified load iterations with the shared `EXEC`/`META` runtime contract and reusable `IterationExecutor`; load-only scheduler state is exposed as `EXEC.LOAD`, inputs use `EXEC.INPUT`, and concurrent iteration state/output remains isolated.
- Added a closed-VU scheduler with stable user identity and think-time semantics, plus a fixed arrival-rate scheduler with absolute planned due times, `maxConcurrent` capping, deterministic `drop` overload handling, and separate generator-drop accounting.
- Added HikariCP database pooling and reusable MQ connection pooling with exclusive leases, bounded diagnostics, timeout classification, cancellation cleanup, and credential-safe resource evidence.
- Added bounded-memory metrics and one-second time-series buckets, measured/warm-up phase separation, latency percentiles, throughput, concurrency, scheduler lag, and configured-versus-achieved arrival metrics.
- Added threshold evaluation with stable exit codes (`0` PASS, `1` threshold failure, `2` validation/configuration failure, `3` runtime/infrastructure error) and explicit SUT-error versus generator-drop evidence.
- Added `att-load-summary/v1.0` JSON/YAML summaries and self-contained offline HTML reports with secret-safe scenario projections, threshold diagnostics, resource diagnostics, and retained evidence links.
- Added end-to-end CLI/report coverage, copyable closed/arrival/tool/DB/MQ examples, short smoke scenarios, release-gate checks, and compatibility coverage for existing run, debug, validation, and reporting workflows.

## 3.4.2 - 2026-09-19

- Implemented Issue #32: Tool, DB, and MQ operations now converge on one Action result/evidence envelope. Current Action data remains available through local `output`, completed data through `EXEC.ACTIONS`, and helper-specific evidence is nested under `output.evidence`.
- Removed DB operation-result publication through `recordDbInvocation()` / `drainDbInvocations()`; DB transaction finalization remains an internal resource lifecycle result exposed through the existing compatibility `CASE.DB` view.
- Preserved typed call-backed Tool arguments, deterministic command-backed argv semantics, MQ 2033/no-reply behavior, retry/attempt evidence, legacy action evidence views, and redaction rules.
- Documented call-backed Tools as the preferred model for new framework-native/reusable capabilities and command-backed Tools as supported external-process extensions in English and Chinese maintained documentation.
- Stabilized `output.evidence.<kind>.invocations[]`, published only the final/winning primary operation at top level, and kept attempt-specific operation/collector evidence under `output.attempts[n].evidence`.
- Clarified that legacy `TOOL.*` / `DB.*` structures are internal or historical/result compatibility views only and do not weaken the #29 validation/migration contract.

- Unified normal TestCase and standalone debug expressions under canonical `EXEC` and curated immutable `META` roots. `EXEC.INPUT`, `EXEC.VARS`, and `EXEC.ACTIONS` are shared execution state; Action `output` remains local to the current Action.
- Mapped current Stage caller/input values into the active `EXEC.INPUT` with deterministic Stage-over-Case precedence, kept completed Actions in `EXEC.ACTIONS`, and deliberately left Stage status/timing/history in the execution/evidence model and legacy `CASE.STAGES` view; `EXEC.STAGES` is not a 3.4.2 node.
- Preserved `CASE.*`, `RUN.*`, and `ACTIONS.*` compatibility aliases, protected framework-owned canonical fields from input overwrite, and documented canonical/legacy resolution and optional references.

## 3.4.1 - 2026-09-19

- Added standalone `debug template|flow|tool <id>` execution with `att-debug/v1.0` sidecars, explicit `--input` overrides, synthetic protected Case/Context values, target-scoped validation, isolated debug artifacts, `case.log`, and machine-readable `result.yaml`.
- Added debug sidecar auto-discovery for Template directories, Flow directories, and grouped Tool configuration, while preserving ordinary run behavior.

## 3.4.0 - 2026-09-17

- Changed CLI defaults so human `run` output is verbose by default and `snapshot` without a selector generates all discovered workbook snapshots; `--verbose` and `--all` remain accepted for compatibility, while explicit selectors retain their existing narrowing behavior.
- Added optional Context references using `${path?}`. Missing map/list/root/intermediate segments now resolve to a real null through interpolation, typed expression blocks, built-ins, Tool arguments, assertions, and assignments; strict references, ambiguity, malformed syntax, and invalid traversal remain errors.
- Added Tool Action post-invocation evidence collectors. Collectors run after the primary result and before assertion, repeat for each primary retry attempt, preserve the primary result, and record timeout/failure policy and per-collector evidence.
- Added the `att-mqhelper/v1.0` IBM MQ helper with send, receive, and request/reply operations, exact file payload bytes, correlation matching, no-message success semantics, bounded reply artifacts, environment-backed credentials, and redacted evidence.
- Added the optional `ibm-mq` Maven profile and release-build jar injection path so default builds remain independent of the vendor client while MQ-enabled packages can include it.
- Added V3.4 schemas, configuration loading, validation, runtime coverage, and user documentation for both features.
