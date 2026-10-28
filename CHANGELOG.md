Warning: truncated output (original token count: 8950)
Total output lines: 281

# Changelog

# Unreleased

- Treat missing optional Package resource roots as empty inventories, including fail-closed handling for inaccessible roots, and return sanitized typed diagnostics for invalid roots (#308).
- Persist immutable Job provenance with separate source, execution identity and Schedule Owner filters, using the Schedules filter layout (#309).
- Store language and theme preferences per authenticated account in Server H2, report save state for every edited field, and retain failed edits for retry (#310).
- Enforce an exclusive LOAD arrival-generation deadline at admission, reject overdue arrivals, and drain all admitted work even when a queued task starts after generation ends (#312).
- Use versioned MurmurHash3 sampling, append-only time-window retention, and accurate LOAD evidence quota/disposition coverage across JSON, HTML, and Studio reports (#313).
- Add bounded per-entry STORED/DEFLATE/AUTO artifact compression with a safe 200:1 fallback and publication metrics (#314).
- Exclude routine checkpoints only for a verified complete report with `executionStatus: PASS`, retain recovery evidence for cancelled/error or unavailable-report runs, share exactly equal single-workload detail payloads with measured byte savings, and categorize artifact browsing (#315).
- Bound Job event journal reload, line size, retained memory, cache size, and on-disk growth; make compaction verifiable and convergent at the byte cap, and isolate storage failures with per-Job diagnostics and rate-limited retries (#316).

# ATT 4.2.6 - 2026-10-27

- Raise the configured LOAD envelope limit to 10 days and apply root plus workload timing consistently across drafts, schedules, and replay validation (#306).
- Add bounded live LOAD progress to Job Results and Metrics, with persisted history, throughput trends, phase counts, and latency estimates (#306).
- Improve workload summaries, console retention, structured event previews, and replay timing review in Studio (#306).

# ATT 4.2.5 - 2026-10-27

- Publish compact, versioned LOAD terminal results with artifact-backed details, durable failure causes, and Studio evidence access (#297).
- Inventory internal capacity limits, classify limit outcomes, and expose structured capacity diagnostics across execution, artifacts, and API responses (#298).
- Add lossless LOAD outcome accounting, bounded five-minute metrics checkpoints, checksummed staged report publication, and truthful partial evidence on interruption (#299).
- Add current-Package and strict Job rerun choices plus a principal-bound editable RUN/LOAD draft flow with safe prefill, typed overrides, stale-revision checks and `rerunOfJobId` provenance (#300).

# ATT 4.2.4 - 2026-10-24

- Prepare referenced DB/MQ/HTTP resources before LOAD scheduler start, with bounded readiness, live diagnostics, and no business requests during preflight (#285).
- Add event-time all/measured/steady Load metrics and window-scoped thresholds, render offline charts in archived and Studio ZIP previews, and preserve report units and counts during path redaction (#290).
- Present target-first Jobs with complete Server-side filtering, grouping, pagination, compact row actions and page-scoped pin/deletion menus (#286).
- Add reversible, audited pending deletion with background cleanup; expose LOAD schedule targets and planned rates, and full standalone Job Details.
- Move complete historical Target indexing off startup, isolate malformed legacy metadata with transactional diagnostics, and keep explicit exact Job-ID lookup available during migration.
- Use accessible Rerun/Schedule review modals; enforce grouped A–Z ordering and document measured indexing/search costs.

# ATT 4.2.3 - 2026-10-22

- Add a unified six-mode Load Template catalog, modal editors and shared CLI bindings; consolidate LOAD history under Jobs and results (#276).
- Move LOAD to `att-load/v1.8` with run-owned sequential execution IDs, durable namespace claims and actionable rejection of custom Load ID formats (#277).

- Resolve Schedule History/List/Edit routes by view and schedule ID, preserving dirty forms during refresh (#271).
- Add strict, auto-pause, and revalidate-latest schedule package-change policies; preserve stale schedule metadata editing and immutable execution sources (#272).
- Drain runtime-owned event compactions before closing H2 and unregister only ATT-owned JDBC drivers on WAR undeploy (#273).
- Render standalone Job consoles in a dedicated viewport-filling shell without Studio navigation or window controls (#275).

- Retain bounded, versioned safe RUN/LOAD execution definitions for Job rerun and schedule creation, with current-source validation and explicit historical/unsafe-input fallback (#237).
- Add confirmation reviews in Jobs, Job Details and LOAD history; submit independent Jobs with durable replay intents and `rerunOfJobId` lineage.
- Open the unified New Schedule form from a revalidated Job snapshot, retain source-job provenance, and distinguish originating schedule edits from new schedule creation.

# ATT 4.2.2 - 2026-10-21

- Distinguish measured SUT failures, runtime/total errors, successful throughput and execution outcome in Load summaries/reports; add optional runtime/total thresholds and bounded failure diagnostics independent of full evidence (#263).
- Persist shared Job pins, protect pinned evidence from existing retention, and support confirmed single/bulk whole-Job deletion with lifecycle fencing and audit records (#264).
- Configure published Live Output entry limits through trusted per-Job Worker policy, with bounded UTF-8 journal retention and explicit truncation indicators (#265).
- Round-1 review fixes: lazily render and cache Events payloads, add accessible deletion confirmation with readable outcomes and 202 recovery status, surface pin/delete conflict messages, and support pin reasons plus bulk pin actions.
- Correct whole-Job deletion to use confirmed `POST /api/v1/jobs/{jobId}/delete`, preserve `DELETE /api/v1/jobs/{jobId}` cancellation, and surface 409 deletion outcome maps in Studio.
- Document the structured Live Output journal allow-list and `droppedFields`, complete pin/deletion status-code and reconciliation contracts, and describe current Load report threshold and failure-sample wording.
- Add a persistent detachable Job Console for RUN, DEBUG, and LOAD monitoring: floating, minimize/restore, drag/resize, Live Output and Events views, and encoded same-origin standalone console tabs with shared bounded per-tab SSE streams (#267).
- Improve Studio performance for #266: downsample only large chart displays while preserving exact metric data, isolate hidden-aware schedule/catalog countdowns, fetch catalog packages with bounded concurrency, and lazy-load Explorer with reviewed route chunk reporting.

# ATT 4.2.1 - 2026-10-16

- Share one plain-text console formatter across CLI, Worker, and Server so Studio Live Output shows RUN, DEBUG, and bounded LOAD lines from the Job event journal (#258).
- Add an optional size quota for `dataDir/jobs` that evicts the oldest complete Jobs, hides a Job while it is deleting, and reports the sweep without counting or compacting H2 (#259).
- Keep LOAD Live Output progress periodic while a run is active, including when no iteration events arrive, and report a size sweep that hits its candidate budget as `partial` instead of `quota_unmet`.
- Resume a partial size sweep after the last candidate it examined, and keep quarantined or artifact-recovery Jobs out of that candidate budget so a protected prefix cannot block eviction. A resumed sweep that finishes a pass already started at the oldest candidate reports `quota_unmet` when only protected Jobs remain.
- Publish bounded, sanitized RUN/LOAD Job artifacts as atomic ZIP archives with a versioned integrity manifest, shared USER authorization, ZIP-backed previews, retryable failures, and removal of loose output after verification (#236, #243).
- Unify functional execution as RUN with optional Debug diagnostics, validated drafts, frozen Case selections, shared RUN/LOAD schedules and one-time triggers; preserve absolute shared-config paths in immutable RUN schedule snapshots (#220).
- Preserve legacy CLI, Debug, Case Run and Load schedule interfaces; add Studio Run and bilingual migration contracts (#220).
- Fix Studio startup and scheduled-job ownership: lazy-load Load, keep unsaved Debug drafts across cache invalidation, list only active environments with retry, refresh occurrence status and history, and validate independent Load evidence caps (#231).
- Open Schedules on a unified RUN/LOAD list with explicit New/Edit views, linked occurrence history, shared USER access, guarded drafts, and confirmed actions; normalize blank shared-config fields on schedule updates while preserving validated execution snapshots and legacy schedule links (#235, #246).
- Redesign Explorer and Run around a full-width resource catalog with separate resource, RUN, and Quick LOAD pages; remove duplicate navigation while preserving batch execution and deep links (#245).
- Use one ordinary Package model with explicit Server registration, package IDs independent of login IDs, strict package-local business-resource resolution, and shared runtime configuration and trusted schemas outside package roots; allow safe-source inspection of shared config files (#232).
- Separate ADMIN system and account management from USER operations; active USERs share eligible packages, runs, jobs, results, artifacts, and schedules across accounts, while active execution identities govern schedule availability (#246).
- Separate manual schedule execution from recurring execution identity; preserve the original creator, migrate an active legacy ADMIN-owned schedule to the active USER who first runs it, and fail closed for disabled or missing owners (#246).
- Add the LOAD Catalog for executable scenarios and Quick LOAD templates, with safe previews, validation, scheduling, and shared job history (#242 / #234).
- Resolve Quick LOAD target identity independently from Load scenario validation, carry opaque resource and logical IDs through Explorer and target pickers, and surface sanitized discovery diagnostics with retry.
- Complete the #245 Schedule and LOAD Catalog follow-ups with short-lived identity/environment/revision-bound Catalog drafts, expired-draft recovery, structured resource detail pages with optional YAML, profile-aware action reasons, and shared responsive filters, tables, and page gutters.
- Align Schedule and Execution History controls with compact shared tokens, preserve copyable full Occurrence IDs, and cover sidebar, mobile touch targets, action states, and detail navigation in UI acceptance checks (#245).
- Make the matching Engine JAR's classpath-embedded Runtime Schema Registry the sole runtime authority; package only Catalog-registered schemas, preserve catalogued historical versions, ignore external and package-local schema folders at runtime, bind Engine, registry, shared config, Package and Environment revisions, verify Worker registry identity, and safely prune unused old cache versions (#232).
- Parse the embedded schema catalog and versions once per JVM, validate JSON Schema bytes directly from the Classpath with a bounded registry-digest-keyed validator cache, reject non-local `$ref` targets, and validate JUnit XML from an embedded schema stream. Keep the verified content-addressed disk cache only as a transitional Path compatibility layer (#232).
- Restore typed R…3950 tokens truncated…kTimePolicy` normalization, deterministic run-level seeding with isolated per-VU random streams, inclusive millisecond range sampling, report-safe effective-seed/policy output, schema updates, an offline example, and EN/ZH Reference coverage.
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
