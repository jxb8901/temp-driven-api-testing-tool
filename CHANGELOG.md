# Changelog

## 3.5.2 - 2026-09-25

- Implement issue #59: extend MQHelper message defaults and IBM MQ metadata, preserve MsgId/CorrelId request/reply behavior, adopt common `saveAs` payload handling, publish MQ metadata directly under Action output, and document the complete English/Chinese contract.

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
