# ADR-0001: Flow-centric Test Case definitions and resolution

- **Status:** Proposed
- **Date:** 2026-11-20
- **Scope:** Next-generation ATT authoring (future; not shipped in 4.4.0)
- **Related:** [Issue #388](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/388), [#233](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/233), [PR #386](https://github.com/jxb8901/temp-driven-api-testing-tool/pull/386)
- **Translation:** [繁體中文版](0001-flow-centric-testcase-resolution.zh.md)

> **Decision:** A Test Case Definition binds reusable Flow logic to a Data Source and resolution rules. Resolution generates **0..m Resolved Test Cases**; RUN/LOAD executes selected cases. Case generation and execution are different lifecycles.

## Context

ATT's established authoring path is **XLSX + att-sidecar/v2.2 + generated XML snapshot → Testcase → Stage → Template → Action**, with optional nested Flow. The model works, but user-facing concepts overlap and the workbook is entangled with source authoring and reporting.

Existing Flow definitions already contain ordered Actions and nested Flow calls; Debug can execute a Flow with temporary input, and LOAD already repeats Flow/Template/Tool targets. Introducing an independent Steps model would duplicate the same execution language.

This ADR sets **future architecture direction**, not a claim that native testcase YAML, Case Resolution, generators, or LOAD Resolved Case targets already exist.

## Decision

### Minimal domain concepts

| Concept | Responsibility | Persistent author-owned resource? |
| --- | --- | --- |
| **Action** | One operation or nested Flow invocation | Part of Flow |
| **Flow** | Ordered reusable executable Actions, assertions, error/cleanup handling | Yes: `flow.yaml` |
| **Test Case Definition** | Flow reference, metadata, Data Source, mapping/generation/filtering and Case ID rule | Yes: `testcase.yaml` + inline/referenced data |
| **Data Source** | Inline YAML, XLSX rows, existing ATT Testdata or future generated/transformed records | Part of Definition; not automatically a new resource category |
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
  + Data Source (inline | Excel | ATT Testdata | future generator)
  + Resolution Rules (mapping, expansion, filtering, Case ID)
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

A Definition with `n` source records/rules may produce `m` cases, **not necessarily n**. For example:

| Data Source | n | Resolution | m |
| --- | ---: | --- | ---: |
| One inline input map | 1 | No expansion | 1 |
| Excel static rows | 100 | No expansion | 100 |
| Excel static rows | 100 | Filter to smoke | 20 |
| Boundary-value rule | 1 | Minimum/maximum with adjacent values | 6 |
| ATT Testdata records | 3 | Three variants per record | 9 |
| Any source | n | Everything filtered | 0 |

An empty selection must have an explicit non-PASS status/policy; **zero executions are not an all-PASS result**. Resolution is bounded and may not call business Tools.

### Identity and reproducibility

Distinct identities:
- **Definition ID:** `fpp.transfer.boundary`.
- **Resolved Case ID:** `min_minus_1`, unique within a Definition.
- **Qualified Case ID:** e.g. `fpp.transfer.boundary.min_minus_1`, unique under defined Package/Definition namespace rules.
- **Execution/Iteration ID:** unique to each attempt; one Case ID may have arbitrarily many executions.

A Case ID expression, or a stable source record key, is evaluated **during Resolution** using a safe deterministic record context. Do not default to physical Excel row numbers as durable identity. Validate blanks, duplicates, conflicts and unsafe IDs.

The same Case ID does **not** imply the same input values in every execution: runtime Testdata selection can change by execution while case identity stays constant. Historical/exact replay needs pinned source/Flow/data revisions, generator version/parameters/seed, the resolved binding and permitted per-execution Testdata evidence. Distinguish exact-source replay from rerunning against latest data.

### Native and data-driven authoring

#### Proposed `att-testcase/v1` schema direction

The following is a **proposed minimal schema** for review (field names and exact grammar remain open until a follow-up schema issue freezes them). Excel Data Source deliberately reuses the familiar `att-sidecar/v2.2` column-mapping vocabulary to reduce migration cost.

**Inline single-case (simplest):**

```yaml
schemaVersion: att-testcase/v1
id: fpp.transfer.success
name: FPP Transfer Success
flow: payment.transfer.v1
tags: [fpp, smoke]
inputs:
  debitAccount: "A001"
  amount: 100
  expectedStatus: SUCCESS
```

**Inline multi-case:**

```yaml
schemaVersion: att-testcase/v1
id: fpp.transfer.boundary.inline
flow: payment.transfer.v1
dataSource:
  type: inline
  records:
    - caseId: min
      amount: 1
      expectedStatus: SUCCESS
    - caseId: zero
      amount: 0
      expectedStatus: REJECTED
caseId: "${DATA.caseId}"
inputs:
  amount: "${DATA.amount}"
  expectedStatus: "${DATA.expectedStatus}"
```

**Excel Data Source (reuses sidecar-style mapping):**

```yaml
schemaVersion: att-testcase/v1
id: fpp.transfer.regression
name: FPP Transfer Regression
flow: payment.transfer.v1
tags: [fpp, regression]
dataSource:
  type: excel
  path: testdata/transfer-cases.xlsx
  sheet: Cases
  headerRows: 1
  caseId: "Case ID"                 # reuses sidecar excel.caseId concept
  tags: "Tags"                      # optional, reuses sidecar
  dataColumns:                      # reuses sidecar dataColumns syntax exactly
    debitAccount: Debit Account
    amount: Amount
    expectedStatus: Expected Status(yaml)
    # also supports: note: "備註,補充"
    #                payload: "請求(yaml)"(yaml)
# inputs optional: if omitted, dataColumns aliases become Flow inputs directly
inputs:
  debitAccount: "${DATA.debitAccount}"
  amount: "${DATA.amount}"
  expectedStatus: "${DATA.expectedStatus}"
report:
  format: xlsx
  template: sourceWorkbook          # optional; output is always a new workbook
```

Key reuse points from `att-sidecar/v2.2`:
- `headerRows`, `caseId`, `tags`, and especially `dataColumns` (including `alias=ColumnName`, `ColumnName(yaml)`, quoting rules, and blank/N/A normalization) are intentionally preserved.
- Stage selector columns and `stages[].dataColumns` do **not** apply; the Definition binds a single entry Flow.
- `(yaml)` type parsing behavior should match existing sidecar rules.

One Definition plus three Excel rows usually resolves into **three Cases**, all calling the same Flow. One boundary-value rule may resolve into six Cases using a documented generated key/expected-value policy. Static Excel rows, inline input, ATT Testdata, and future generated/filtered/transformed data share **one conceptual Resolution contract**; advanced generators and all source types need not ship in V1.

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
- **Resolve/Prepare:** apply mapping, filtering/expansion and Case ID rules; validate uniqueness; pin selected Case targets and source/Flow/data/dependency closure plus seed where applicable.
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

Legacy path **must remain operational**: XLSX + `att-sidecar/v2.2` + canonical XML snapshot, multi-Stage Case execution, Template entry points, existing CLI/Job/Schedule/report IDs. Stage `required`/`runWhen`/`onFailure`, scope/overlays, rollback/cleanup, nested Flow state, retries, logs and events are **not automatically equivalent** to a plain nested Flow Action. Keep legacy execution via adapter until semantic equivalence is proven.

Reuse 4.4.0 Package Source / Change Sets / Validate / Review / Publish / Revision / History / Restore and immutable Job source closure. Do not create a second authoring/publishing subsystem.

**ATT 4.4.0 GA and PR #386 must not be blocked** by delivering native Test Cases, dynamic generators, Flow/Template consolidation or new LOAD targets. This ADR guides post-4.4 implementation and pre-release compatibility review only.

## Open questions before acceptance

1. Exact `att-testcase/v1` schema and input/default precedence, especially bootstrap `EXEC.VARS`. (Schema direction above is a proposal for review.)
2. Case ID expression grammar, namespace, stable identity under edits/moves, migration of legacy `workbookId.groupId.rowCaseId`.
3. Zero-case handling, invalid-record policy, generator seed/limits, external/non-deterministic sources and precise replay guarantees.
4. Eager vs deterministic bounded/lazy Resolution; when/where selected target sets are pinned.
5. Persistent Suite selection representation if needed across Definitions.
6. Mapping selected Case targets into existing LOAD workload/mix without changing direct Flow/Template/Tool behavior.
7. Exact compatibility of Stage scope/failure/cleanup vs Flow Action semantics, and Template-to-Flow transition.
8. Debug-to-Case conversion details, confidential input evidence and optional Excel report template behavior.

These are delegated to focused follow-up design/issues. Status remains **Proposed** until review and first-implementation contracts are agreed.

## Required acceptance examples

- Inline Definition → one Resolved Case → one RUN execution.
- Excel three rows → three uniquely selectable Cases, independent results/replay and optional new output workbook.
- One boundary rule → six stable Cases; filtering/expansion demonstrates n→m and m=0 policy.
- LOAD one selected Case × 100 iterations → **one Case ID, 100 execution IDs**, even when per-iteration Testdata changes.
- LOAD three selected Cases × 100 iterations each → **3 Cases, 300 executions**; weighted mix reports actual unequal counts.
- Legacy five-Stage workbook, rollback/cleanup, source snapshots, Job/Schedule/Debug/LOAD compatibility and evidence remain unchanged.
