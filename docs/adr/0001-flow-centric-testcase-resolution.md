# ADR-0001: Flow-centric Test Case definitions and resolution

- **Status:** Proposed
- **Date:** 2026-11-20
- **Scope:** Proposed next-generation ATT authoring, after the existing Source Management workflow
- **Native Case status:** The 4.6.0 release target contains the S03 inline resolution and RUN implementation; the release and tag have not been published, and deployed acceptance remains open under [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393).
- **Related:** [Issue #388](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/388), [#233](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/233), [PR #386](https://github.com/jxb8901/temp-driven-api-testing-tool/pull/386)
- **Translation:** [繁體中文版](0001-flow-centric-testcase-resolution.zh.md)
- **S10 compatibility evidence:** [Template–Flow–Stage matrix and golden fixture](../system-design/template-flow-stage-compatibility.md)

> **Decision:** A Test Case Definition binds reusable Flow logic to a Data Source and resolution rules. The long-term model may resolve **0..m Resolved Test Cases**; a zero-case result is a validation error before RUN. RUN/LOAD executes selected cases. Case generation and execution are different lifecycles.

## Context

ATT's established authoring path is **XLSX + att-sidecar/v2.2 + generated XML snapshot → Testcase → Stage → Template → Action**, with optional nested Flow. The model works, but user-facing concepts overlap and the workbook is entangled with source authoring and reporting.

Existing Flow definitions already contain ordered Actions and nested Flow calls; Debug can execute a Flow with temporary input, and LOAD already repeats Flow/Template/Tool targets. Introducing an independent Steps model would duplicate the same execution language.

This ADR remains **Proposed** and records the architecture direction and staged implementation boundary. The unpublished 4.6.0 release-target stack contains implementation work for S03–S07 and S12, including Native Case RUN, Excel sources, Studio editing, implicit Suites, reports, and Default Package examples. The code is in open stacked PRs; deployed acceptance remains open for Native RUN ([#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393)), report integration ([#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397)), and S12 adoption ([#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413)). Shared-editor deployment evidence is tracked separately in [#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387). S10/#398 is limited to compatibility analysis and a golden fixture; it makes no FrameworkEngine runtime-semantic changes. Native LOAD targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), advanced Testdata and generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)), and Helper rollback/teardown proof ([#421](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/421)) are follow-ups outside the target. ADR approval and architecture closure are tracked after the 4.6.0 target in [#422](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/422) and are not a release blocker.

## Decision

### Minimal domain concepts

| Concept | Responsibility | Persistent author-owned resource? |
| --- | --- | --- |
| **Action** | One operation or nested Flow invocation | Part of Flow |
| **Flow** | Ordered reusable executable Actions, assertions, error/cleanup handling | Yes: `flow.yaml` |
| **Test Case Definition** | Flow reference, metadata, Data Source, mapping and Case ID rule; later versions may add generation/filtering | Yes: `testcase.yaml` + inline/referenced data |
| **Data Source** | V1: inline YAML or XLSX rows; future versioned extensions may add ATT Testdata or generated/transformed records | Part of Definition; not automatically a new resource category |
| **Resolved Test Case** (Case Instance) | A concrete, identifiable and selectable logical case with bound Flow and inputs | **No**: derived normalized model, not `instance.yaml` |
| **Test Suite** | Implicit or explicit selection/grouping of Resolved Cases, possibly from multiple Definitions | Optional persistent descriptor only if necessary |
| **Case Execution** | One actual attempt of a Resolved Case with its own ID, status, timing and evidence | Runtime record/artifacts |
| **LOAD Run / Iteration** | Outer scheduler job / one execution of a selected target | Existing workload/iteration model |
| **Debug input** | Temporary input/vars for a selected target | Existing `debug.yaml`; not automatically a Test Case |

There is **no separate Steps/Step Group source type or DSL**. A Flow contains Actions and can invoke other Flows. A new `testcase.yaml` references one entry Flow and provides data bindings, not another executable Template/Stage/Actions tree. Expected values are inputs evaluated by assertions in the Flow.

### Resolution is independent of execution

```text
Flow
  + Test Case Definition (testcase.yaml)
  + Data Source (V1: inline | Excel; future versioned extensions: other sources)
  + Resolution Rules (V1: mapping and Case ID; future versions may add expansion/filtering)
       |
       v
    Case Resolution  ---> 0..m Resolved Test Cases
                                 |
                     Suite/target selection
                                 |
                RUN executions / LOAD iterations
```

Formally:

`Resolve(Definition, ResolutionContext) → {ResolvedCase_1, ..., ResolvedCase_m}`

A later schema version or separately approved versioned extension may produce `m` cases from `n` source records/rules. V1 does not filter, expand, or reorder records: it produces exactly one Resolved Case per source record, in source order. A single inline Definition without a Data Source produces one `default` Case. For example:

| Data Source | n | Resolution | m |
| --- | ---: | --- | ---: |
| One inline Case without a Data Source (V1) | 1 | Use `default` ID | 1 |
| Inline records (V1) | 3 | Preserve source order; no filtering or expansion | 3 |
| Excel static rows (V1) | 100 | Preserve source order; no filtering or expansion | 100 |
| Future versioned source/rule | 1 | Boundary-value expansion | 6 |
| Future versioned source/rule | 100 | Filter to smoke | 20 |

An empty source, or any future rule that would resolve to zero cases, is a validation error before RUN; it is never a successful empty selection or an all-PASS result. Resolution is bounded and may not call business Tools.

### Identity and reproducibility

The contract separates these identities:

- **Definition ID:** a case-preserving, dotted identifier such as `fpp.transfer.boundary`, unique within a Package. It is the identity; the descriptor's filename and directory are not.
- **Source record ID:** `recordId` for an inline record or the Excel column selected by `dataSource.caseId`. It is required for multi-record sources and is not a row number.
- **Resolved Case ID:** one path-safe segment, such as `min_minus_1`, unique within its Definition.
- **Qualified Case ID:** `<definition-id>.<resolved-case-id>`, such as `fpp.transfer.boundary.min_minus_1`. It is a Package-scoped logical identity, not a filesystem path, and is at most 255 ASCII characters.
- **Execution/Iteration ID:** unique to each attempt; one Case ID may have many executions.

Definition IDs use dotted `[A-Za-z0-9_-]+` segments and are at most 126 ASCII characters. Resolved Case IDs use `[A-Za-z0-9_-]{1,128}` and contain no path separator. IDs preserve their authored case; the resolver rejects ASCII case-folded Definition ID collisions within a Package and Resolved Case ID collisions within a Definition. The resulting Qualified Case IDs must also be unique under ASCII case folding within the Package. It never silently lowercases or trims an ID. Moving or renaming the descriptor path does not change its Definition ID; changing the `id` creates a new logical Definition and does not migrate prior results. Qualified IDs are namespace composition, not storage paths; any output-path mapping must be separately defined and preserve containment boundaries.

For a single inline Definition with no Data Source, the Resolved Case ID is `default`. For inline records, use `recordId`; for Excel, use the selected `dataSource.caseId` column. `caseIdExpression`, when present, overrides that source ID. Its V1 grammar is exactly one `${DATA.key}` lookup. It is evaluated during Resolution against one direct key in the source record; functions, concatenation, nested lookup, `EXEC.*`, `META.*`, environment state and other runtime-only values are invalid. The result must be a nonblank string matching the Resolved Case ID grammar. Missing, null, non-string, invalid or duplicate results are validation errors. Do not default to physical Excel row numbers.

The active schema contract enforces ID syntax and requires at least one inline record. The S03 Resolver rejects ASCII case-folded ID collisions and duplicate ASCII case-folded `recordId` values within inline Definitions, even when `caseIdExpression` overrides the resolved IDs. The resolution-invalid fixture demonstrates case-variant collisions for both source and resolved IDs. Cross-Definition package uniqueness is also checked during package resolution. The S04 Excel adapter applies the same row-uniqueness rule to workbook IDs. V1 preserves the declared inline record order and Excel data-row order, with exactly one Resolved Case per record. V1 has no filter or expansion rule. Empty sources and any resolution that would produce zero cases are validation errors before RUN, never all-PASS results.

The same Case ID does **not** imply the same input values in every execution: runtime Testdata selection can change by execution while case identity stays constant. Historical/exact replay needs pinned source/Flow/data revisions, generator version/parameters/seed, the resolved binding and permitted per-execution Testdata evidence. Distinguish exact-source replay from rerunning against latest data.

### Native and data-driven authoring

#### Frozen `att-testcase/v1` authoring contract

S01 froze the field and type contract; S03 promotes it into the Engine's active schema registry and supports inline Definition discovery, resolution, and execution. S04 adds package-contained Excel source resolution while preserving the existing Sidecar mapping semantics. The schema and examples live under `schemas/` and `docs/adr/examples/att-testcase-v1/`.

The top-level fields are `schemaVersion`, Package-scoped `id`, optional `name`, `description` and `tags`, a static versioned `flow`, and exactly one of:

- A single inline Case: `inputs` is required, `dataSource` and `caseIdExpression` are absent, and the Resolved Case ID is `default`.
- A multi-record Data Source: `dataSource` is `inline` or `excel`. `recordId` or the Excel `caseId` column supplies a stable source ID; optional `caseIdExpression` derives the Resolved Case ID from the record's `DATA` map.

The V1 schema accepts only the `inline` and `excel` Data Source types. A validator must reject an unknown or unrecognized `dataSource.type` as unsupported; an `x-` extension property does not add a Data Source type. ATT Testdata as a case-generation source, generated records, and transformed records require a future schema version or a separately approved, versioned extension. Each such source must first normalize to ordered records with a stable record ID, typed values, and source revision/provenance before V1-style binding and Case ID rules can apply.

The examples are [inline single](examples/att-testcase-v1/inline-single.yaml), [inline multi](examples/att-testcase-v1/inline-multi.yaml), [Excel](examples/att-testcase-v1/excel.yaml), and [Excel with omitted `dataColumns`](examples/att-testcase-v1/excel-omitted-data-columns.yaml). Proposed-schema-invalid fixtures cover empty records, ambiguous `caseId`, runtime-only references, inline Actions/Steps, dynamic Flow IDs, map-shaped Excel `dataColumns`, unsafe Excel paths, and incompatible report templates. The [resolution-invalid duplicate-ID fixture](examples/att-testcase-v1/resolution-invalid-duplicate-ids.yaml) documents S03 inline collision behavior; the S04 adapter also rejects Excel source and resolved ID collisions.

`flow` must be a static canonical Flow ID ending in `.vN`, such as `payment.transfer.v1`; the Flow registry must resolve it. The schema rejects expression-based Flow IDs and unknown top-level fields, including `actions` and `steps`. Flow existence and dependency checks remain resolver responsibilities.

Definition, Data Source, record and report objects accept extension properties prefixed with `x-`; all other unknown properties are rejected. The projection and binding shapes remain strict.

#### Input binding and precedence

For a single inline Case, `inputs` is the complete `EXEC.INPUT` map and accepts typed YAML values, including `null`, booleans, numbers, strings, arrays and objects. It is required but may be empty. A missing key is absent; an explicit `null` remains a typed null.

For a Data Source, `DATA` is the normalized record before Flow binding. If `inputs` is omitted, the resolver copies each `DATA` key to the same Flow input name. If `inputs` is present, it is the complete projection and replaces identity projection; it is not merged with it. Each projected value is either `{from: DATA.key}` or `{value: <typed value>}`. A missing source key is an error; a present `null` remains null. V1 defines no implicit defaults, environment overlays or override precedence. Flow inputs populate `EXEC.INPUT`; they do not initialize `EXEC.VARS` or Debug bootstrap variables.

#### Excel mapping and type compatibility

`dataSource.caseId` and `dataSource.tags` select physical Excel headers. `caseIdExpression` is a separate resolved-ID rule. `dataColumns` is the existing `att-sidecar/v2.2` comma-separated **string**, not a YAML map. Reuse `ColumnSpecParser.dataColumns` semantics: `alias=ColumnName`, `ColumnName(yaml)`, case-sensitive header names, double-quoted commas or equals, and doubled quotes inside a quoted name. The terminal `(yaml)` suffix requests safe YAML scalar/list/map parsing. If no alias is given, the trimmed column selector is also the `DATA` key; use an alias when the header is not a valid V1 data key.

**Omitted or blank `dataColumns` is explicit-empty, not implicit-all-columns.** It reuses `ColumnSpecParser.dataColumns(null / "")` and therefore selects no business columns into `DATA`. The Case ID selector column and optional tags selector column are metadata selectors only; V1 never auto-exposes them (or any other unmapped worksheet header) as `DATA` keys. Authors who need Excel business inputs must declare `dataColumns` explicitly. When `inputs` is also omitted, identity projection of that empty `DATA` map yields an empty `EXEC.INPUT` map, which is valid. Present `inputs` projections that reference missing `DATA` keys remain resolve-time errors. See [excel-omitted-data-columns.yaml](examples/att-testcase-v1/excel-omitted-data-columns.yaml).

`dataSource.path` is a Package-relative portable path: nonempty ASCII `[A-Za-z0-9_-]` components separated by `/`, ending in `.xlsx` / `.XLSX`. The active schema rejects empty segments, `.` / `..`, backslashes, drive/colon forms, spaces, and control characters at the syntax layer. Schema rejection is necessary but not sufficient: the S04 Excel adapter must still resolve the path under the Package root and reject escapes or unsafe filesystem forms.

The S04 Excel adapter preserves the existing displayed-cell and blank-marker contract: empty/whitespace-only cells and `N/A`, `NA`, `NULL`, or `NONE` normalize to an empty string; blank `(yaml)` cells stay empty and are not parsed. Inline YAML `null` remains a typed null. Excel `tags` values use comma-separated tags; Definition and record tags combine in source order with duplicates removed. Missing mapped columns, duplicate aliases, malformed quoting, unsafe paths and unsupported workbook structures are validation errors. The adapter reads one bounded byte snapshot (32 MiB compressed maximum), fingerprints the exact consumed bytes, and never modifies the workbook or creates the legacy XML Snapshot. ZIP preflight allows at most 256 entries, 32 MiB per expanded entry, and 64 MiB total expanded data. A selected worksheet is capped at 100,000 physical rows; resolution accepts at most 10,000 nonblank records. Header configuration allows up to 86,400 rows, while the combined header-row/column scan is limited to 1,000,000 cells. YAML cells use shared `YamlSupport` limits (3,000,000 code points, 50 collection aliases, and 50 nested levels); parsed values are further limited to depth 64 and 100,000 values. Non-finite numbers and recursive aliases are rejected.

Excel Case IDs have stricter identity rules than ordinary data columns. Read `dataSource.caseId` from a literal text cell using the existing `DataFormatter(Locale.ROOT)` extraction; formula, numeric, date, boolean and error cells are invalid. A blank cell or a value recognized as blank by the existing `ValueNormalizer` (`N/A`, `NA`, `NULL`, or `NONE`, ignoring surrounding whitespace and ASCII case) is a missing ID and is an error when the row contains other mapped data. For every other value, preserve the exact text and case: do not trim or case-normalize it. Reject leading/trailing whitespace, controls, values outside `[A-Za-z0-9_-]{1,128}`, and Windows reserved device names (case-insensitive). Check duplicate source IDs using ASCII case folding. `caseIdExpression` may derive a different Resolved Case ID, but it does not remove the required source ID. Physical row numbers are diagnostic provenance only, never an ID.

The optional `report` object supports `format: xlsx`; `template: sourceWorkbook` requests a copy of the Excel Data Source as a new output workbook and is invalid for inline data. S07 writes that optional workbook report after checking the accepted workbook revision and selected physical rows before RUN. It preserves source formulas, redacts sensitive source values, and never writes back to the Package workbook. The standard Native report remains the complete fallback when the optional source-shaped artifact cannot be produced within its supported scope or limits. Server archive access to the source-shaped workbook requires current Package editor permission.

Key reuse points from `att-sidecar/v2.2`:
- `headerRows`, physical `caseId` and `tags` selectors, and especially scalar-string `dataColumns` (including `alias=ColumnName`, `ColumnName(yaml)`, quoting rules, and blank/N/A normalization) are intentionally preserved.
- Stage selector columns and `stages[].dataColumns` do **not** apply; the Definition binds a single entry Flow.
- `(yaml)` type parsing behavior should match existing sidecar rules.

One Definition plus three Excel rows resolves into **three Cases**, all calling the same Flow, in worksheet order. V1 inline records follow their declared order and resolve one-to-one; V1 has no filtering or expansion. The architecture may add ATT Testdata, generated, transformed, filtered, or expanded sources only through a future schema version or separately approved versioned extension. Such sources must normalize to ordered records with stable IDs, typed values, and source revision/provenance before case binding.

**Distinguish data used for case generation from runtime Testdata binding.** Data selected per LOAD iteration (workload/user/iteration scope) does **not** generate new Resolved Cases.

### Suite is selection, not another execution language

A Definition resolving into multiple Cases naturally forms an **implicit Suite**; no mandatory `suite.yaml`. Explicit Suites may select Cases from multiple Definitions/Flows. A Resolved Case can belong to multiple Suites without copying its source.

### RUN and LOAD cardinality

**RUN:** Select `m` Resolved Cases, normally execute each once.

**LOAD:** Select `k` Resolved Case targets, repeat each according to the scheduler; a repeated iteration is **not** another Resolved Case.

| Operation | Resolved Case count | Executions/iterations |
| --- | ---: | ---: |
| RUN selects m cases once | m | m |
| LOAD selects one case n times | **1** | **n** |
| LOAD selects k cases each n times | **k** | **n × k** |
| LOAD selects k cases with different counts | **k** | **sum(n_i)** |

For weighted mixes, arrival-rate load, cancellation and drops, actual per-target execution counts are scheduler-dependent; do not promise `n × k` unless each target actually ran n times. Report **selected targets, started/completed iterations, outcome counts and retained evidence samples** separately. Per-iteration input/Testdata variation preserves the selected Case identity.

A Definition resolving to many Cases does **not** automatically select all of them for LOAD. Keep the current `att-load/v1.9` direct **Template/Flow/Tool** targets and workload mix/pacing fully compatible; Resolved Case targets are an incremental future addition.

### Debug remains different from a published Test Case

`Flow + debug.yaml` may execute one scenario, but `att-debug/v1.2` has temporary invocation inputs and lacks durable Case ID, Suite membership, normal RUN reporting and revisioned Definition selection. Share compatible input/bootstrap machinery. A future **Save as Test Case** or **Debug this Case** action must be explicit and validate debug-only fields; never silently convert `debug.yaml`.

### Excel has separate data and report roles

Excel may provide input data **and optionally** a workbook-shaped report layout. Report generation copies the source layout into a **new output workbook** and inserts results; never modifies author-owned input. Native inline/generated/Testdata Cases must produce HTML/CI/JUnit and standard XLSX without an Excel input. Results should be projected from normalized execution results, not inferred from workbook authoring.

## Lifecycle and invariants

- **Discovery/Preview:** inspect definitions and source metadata; show selected/estimated case IDs with bounded previews; no business I/O.
- **Resolve/Prepare:** in V1, preserve source order, apply mapping and Case ID rules, and reject empty results before RUN; validate uniqueness and pin selected Case targets and source/Flow/data/dependency closure. A future versioned extension may add filtering/expansion and its associated provenance and seed rules.
- **Execute:** invoke bound Flow, resolve any intentionally deferred runtime Testdata, produce distinct execution IDs/results/evidence.

Preserve source ownership, authorization, confidentiality, revision consistency, bounded generation, and safe output. Source changes after Job acceptance must not change queued/running work. View/Validate/Preview cannot mutate source, silently regenerate XML, or invoke business Tools. Large/lazy resolution may be introduced only with determinism and snapshot guarantees; **lazy enumeration does not mean regenerating Case IDs on every LOAD iteration**.

## Alternatives considered

| Option | Decision and reason |
| --- | --- |
| Retain Stage/Template/Excel as the primary future model | Reject: overlapping author concepts and workbook coupling |
| Add Steps or Step Groups | Reject: duplicate Flow execution semantics |
| Use `debug.yaml` as the persistent Test Case | Reject: temporary invocation lacks durable identities/reporting |
| Create one YAML Definition for each Excel row | Reject by default: unnecessary duplication |
| Author executable logic inside Excel cells | Reject: second opaque executable language |
| Create one Resolved Case for each LOAD iteration | Reject: conflates case identity with execution |
| Flow + Definition + Data Source → Resolved Cases | **Selected direction** |

## Consequences, compatibility and rollout

**Benefits:** smaller public model, shared Flow semantics, data-driven authoring, future boundary/combination generators, independently selectable/replayable case identities, consistent reporting.

**Costs:** new Definition schema/Resolver, identity rules, potentially costly source expansion, validation and provenance, compatible reuse of execution core.

Legacy path **must remain operational**: XLSX + att-sidecar/v2.2 + canonical XML snapshot, multi-Stage Case execution, Template entry points, existing CLI/Job/Schedule/report IDs. Stage required/runWhen/onFailure, scope/overlays, rollback/cleanup, nested Flow state, retries, logs and events are **not automatically equivalent** to a plain nested Flow Action. The S10 fixture proves only one explicit failure/rollback/cleanup mapping and exposes the Stage-only input overlay and output-shape differences. Keep legacy execution via adapter until each compatibility boundary is proven.

Reuse the existing Package Source / Change Sets / Validate / Review / Publish / Revision / History / Restore workflow and immutable Job source closure. Do not create a second authoring/publishing subsystem.

The original rollout plan kept PR #386 independent from Native Test Cases, dynamic generators, Flow/Template consolidation and new LOAD targets. The current 4.6.0 target includes the scoped S03–S07 and S12 implementation; S08/S09/S11 and Helper rollback verification remain follow-ups. This ADR still requires architecture review and approval under #422 after the target work; that closure does not block the release target.

## Open architecture questions after S01

1. Migration of legacy `workbookId.groupId.rowCaseId` into native Definition/Case identities and historical replay behavior.
2. Generator seed/limits, external or nondeterministic sources, and precise replay guarantees.
3. Eager versus deterministic bounded/lazy Resolution; when and where selected target sets are pinned.
4. Persistent Suite selection representation if needed across Definitions.
5. Mapping selected Case targets into existing LOAD workload/mix without changing direct Flow/Template/Tool behavior.
6. Exact compatibility of Stage scope/failure/cleanup versus Flow Action semantics, and Template-to-Flow transition ([S10 matrix](../system-design/template-flow-stage-compatibility.md)).
7. Debug-to-Case conversion details, confidential input evidence and optional Excel report template behavior.

These are delegated to focused follow-up design/issues. Status remains **Proposed** until review and first-implementation contracts are agreed.

## Required acceptance examples

- Inline Definition → one Resolved Case → one RUN execution.
- Excel three rows → three uniquely selectable Cases, independent results/replay and optional new output workbook.
- Future versioned extension: one boundary rule → six stable Cases; filtering/expansion demonstrates n→m. A result of zero cases is a validation error before RUN.
- LOAD one selected Case × 100 iterations → **one Case ID, 100 execution IDs**, even when per-iteration Testdata changes.
- LOAD three selected Cases × 100 iterations each → **3 Cases, 300 executions**; weighted mix reports actual unequal counts.
- Legacy five-Stage workbook, rollback/cleanup, source snapshots, Job/Schedule/Debug/LOAD compatibility and evidence remain unchanged.
