# Changelog

## 3.7.3 - 2026-10-04

- Adopt the Google Developer Documentation Style Guide as the editorial baseline for current ATT documentation, with ATT-specific terminology and identifier exceptions (#144).
- Add deterministic editorial checks for standalone-page H1 counts, numbered headings, ambiguous abbreviations, and selected positional references; exclude generated Reference outputs from editorial lint while preserving freshness and EN/ZH structure checks.
- Remove sequence numbers from Quick Start headings and clarify current documentation wording covered by the new checks.
- Upgrade ATT product version to 3.7.3.

## 3.7.2 - 2026-10-04

- Implement issue #52: add deterministic weighted closed-VU mixes across pre-resolved Template, Flow and Tool targets, with shared run resources, VU-scoped testdata state, mix identity, bounded per-entry metrics, and evidence/report attribution.
- Advance Load scenarios to `att-load/v1.6` and Load summaries to `att-load-summary/v1.1`; preserve v1.5 and v1.0 schemas as historical contracts.
- Remove direct local file-path arguments from HTTPHelper and MQHelper; use `&{...}` Strings as HTTP bodies and MQ payloads (#133).
- Make SSHHelper upload content-based and remove native SSH download (#133).
- Normalize ATT-owned local Paths in Case logs to `$ATT_HOME` presentation and bound external Path values (#134).
- Upgrade ATT product version to 3.7.2.

## 3.7.1 - 2026-10-02

- Parse structured HTTP and MQ responses directly from readers, avoiding an intermediate full-body String for bounded response payloads (#121).
- Make Load failure-log capture stop when `maxSamples` is zero or retained/reserved evidence has exhausted capacity; cover zero and full capacity.
- Preserve a full deferred log when a success-reserved sampled iteration fails and uses its reserved slot for failure evidence.
- Describe Load case-log behavior using effective success/failure policies, including independent overrides.
- Upgrade ATT product version to 3.7.1.

## 3.7.0 - 2026-10-02

- Implement issue #63: add layered environment and Load-local testdata registries, literal and virtual sequence records, typed input mapping, deterministic per-scope selection, Load scope/exhaustion policies, bounded selection evidence, and package validation.
- Add the `att-testdata/v1.0` descriptor contract, advance global configuration to `att-config/v2.11` and Load scenarios to `att-load/v1.5`, and preserve the previous config/Load schemas under `schemas/history/`.
- Document testdata authoring, mapping, Load policies, and migration in English and Traditional Chinese.

## 3.6.2 - 2026-10-01

- Remove presentation-only dbText/prettyPrint aliases and user-authored Log.level; migrate to typed Log value + format and plain Case-log entries (#109).
- Remove local file built-ins/aliases and add typed SSHHelper SFTP stat, mkdirs, move and non-recursive delete using the existing selection, concurrency, timeout and identity handling (#110).
- Document shared DB/MQ/HTTP evidence.output presentation, retain automatic Case-log visibility and deferred Load materialization, and redact credentials before formatting/truncation (#111).

- Classify MQ request PUT followed by correlated reply MQRC 2033 as canonical TIMEOUT, preserve native completion/reason/wait metadata, and retain standalone receive polling semantics (issue #92).
- Add common optional Boolean `retry.when` for Tool Actions, including DB queries, evaluated against current-attempt output only after retryOn matches; validate pure expressions, preserve suppressed TIMEOUT/FAIL outcomes, and record retry decisions. Document the MQ 2033 replay-suppression example and the duplicate-request risk of unconditional TIMEOUT retry (issue #92).
- Correct typed expression source spans when retry conditions repeat literal values, preserving current-attempt DB timeout checks.

- Add native SSH Resource Helper `execute`, `upload`, and `download` operations with typed stdout, bounded concurrency, one Action deadline, stable error categories, redacted diagnostics, controlled transfer paths, and timeout retry for `execute` only (issue #89).
- Unify current Load descriptors on `att-load/v1.4`, preserve historical v1.3 threshold semantics, and keep policy-only Quick Load descriptors non-executable (issue #102).
- Link retained Load evidence and Case Logs safely in portable reports, including canonical path containment and symlink-escape checks (issue #100).
- Replace `DocumentValue` and `templateFormat` handling with String values at the Render boundary (issue #101), then retire Render Actions from current `att-template/v3.5` and `att-flow/v3.5`; retain v3.4 as a historical contract and migrate shipped templates, flows, examples, and EN/ZH manuals to project-file expressions (issue #107).
- Add typed `&{project-file}` expressions that return exactly one UTF-8 String, enforce canonical project-root containment and static locators, and reject absolute paths, globs, unsafe targets, and nested file expressions inside project-file content in v1 (issue #107).
- Cache immutable compiled file plans in Run/Debug, re-evaluate their Context/call nodes for each use, and freeze validated file identities, content, and plans for Load. Evaluate authored nodes once without rescanning runtime Context values, Tool results, or file output as expression syntax (issue #107).
- Validate Context scope and Action ordering plus Tool/Helper call dependencies inside referenced files using the normal authoring contracts; add regression coverage for literal runtime locators, invalid dependencies, and consistent nested-file rejection across Run/Debug/Load (issue #107).

## 3.6.1 - 2026-09-30

- Preserve evidence collector root causes, structured resource evidence, diagnostics, and retry history for continue/stop handling (issue #94).
- Attribute nested Tool, assertion, and evidence collector failures to their source fields, retaining Flow source locations (issue #95).

## 3.6.0 - 2026-09-30

- Implement Issues #87 and #88: replace common result representation settings with typed `DocumentValue` results, preserve rendered documents through HTTP/MQ requests, add explicit log/resource evidence formatting, and configure Load `EXEC.ID` with runtime expressions.
- Document curated `META` fields and invocation lifecycles, and expose `EXEC.RUN_ID` / `EXEC.ID` navigation for reports and retained evidence.
- Advance active schemas and migrate shipped configuration/examples to the 3.6.0 contracts.

## 3.5.3 - 2026-09-29

- Complete Issues #70–#74: harden HTTPHelper result/header contracts and schema-catalog enforcement; unify typed Action and Tool results; add bounded, secret-redacted internal exception stacks to Case logs; and allow MQ send/receive/request queue defaults with explicit argument precedence.
- Advance current configuration, Tool Group, Template, and Flow schemas to `att-config/v2.9`, `att-tool-group/v2.8`, `att-template/v3.2`, and `att-flow/v3.2`, retaining every previous schema at its catalog-registered `schemas/history/` path.
- Expand English and Traditional Chinese HTTPHelper, MQHelper, Tool/result, validation, and migration guidance; update runnable SIT/UAT examples to the current `result.format` contract.

## 3.5.2 - 2026-09-25

- Implement issue #70: introduce `att-httphelper/v1.0` with environment-bound logical HTTP services, bounded pooled transport, secure auth/TLS defaults, typed Action results, HTTP metadata/evidence, and EN/ZH documentation.
- Implement issue #71: enrich older-schema validation errors with current-version migration guidance, preserve original violations and source locations, and relocate legacy schemas to `schemas/history/` without changing their contracts.

- Implement issue #59: extend MQHelper message defaults and IBM MQ metadata, preserve MsgId/CorrelId request/reply behavior, adopt common `saveAs` payload handling, publish MQ metadata directly under Action output, and document the complete English/Chinese contract.

- Implement issue #60: add `att-mqhelper/v1.1` logical groups with inherited defaults, immutable physical instances, random/round-robin selection, explicit instance overrides, isolated pools, secret-safe output/evidence, and EN/ZH documentation while preserving v1.0 descriptors.

- Implemented Issue #53 randomized think-time ranges for closed-VU load workloads while preserving the existing fixed `execution.thinkTime` syntax and keeping arrival-rate workloads unchanged.
- Added reusable `ThinkTimePolicy` normalization, deterministic run-level seeding with isolated per-VU random streams, inclusive millisecond range sampling, report-safe effective-seed/policy output, schema updates, an offline example, and EN/ZH Reference coverage.
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
