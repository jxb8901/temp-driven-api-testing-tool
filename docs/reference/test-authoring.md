# Test authoring

## Author a Native Case

New Cases follow this path. For a Package authored with an earlier model, use the [Legacy documentation](../legacy/README.md).

~~~text
Flow + testcase.yaml + Data Source -> Resolution -> Resolved Case -> Suite selection -> Case Execution
~~~

1. Author or reuse a Flow (`att-flow/v3.6`).
2. Author a Test Case Definition (`att-testcase/v1`) that binds that Flow and a Data Source.
3. Resolve the Definition. Preview is read-only and does not create a Job.
4. Select Resolved Cases: one Case, explicit qualified IDs, tags, or an implicit Suite. There is no mandatory `suite.yaml`.
5. Validate the Package, then RUN. Each selected Resolved Case becomes one Case Execution with its own execution ID, status, and evidence.

`att-testcase/v1` is an exact 1:1 source-record contract, owned by this page and schema `att-testcase/v1`:

- A typed `inputs` map with no Data Source resolves to one Case whose ID is `default`.
- Each nonblank inline or Excel record resolves to exactly one Resolved Case. Resolution does not expand one record into many Cases.
- `caseIdExpression` is a single `${DATA.key}` lookup. It is not a generator.
- Excel `dataColumns` explicitly selects source columns and supports aliases, YAML parsing, and quoted delimiters.
- The Definition `id` is Package-scoped. A Resolved Case ID is one path-safe segment. The qualified ID is `definitionId.caseId`.
- Typed input projections, reports, and Package files stay isolated. Resolution and RUN do not modify the Definition or the source workbook.

n-to-m generators are outside `att-testcase/v1` and remain deferred. For data-source format details maintained for existing Packages, see the [Legacy documentation](../legacy/README.md).

## Distinguish Resolved Cases from Case executions

A **Resolved Case** is one selectable logical Case produced by Resolution. A **Case execution** is one runtime execution of that Resolved Case. The qualified Case ID is stable across RUNs; the execution ID, status, and evidence belong to one execution.

## Authoring contracts

| Need | Native contract |
|---|---|
| Reusable ordered Actions | Flow |
| One ordered operation | Action |
| External capability | Resource |
| Business input | Definition `inputs` or a `DATA` projection |
| Cross-Action mutable state | `EXEC.VARS` |
| Case selection | Resolved Case IDs, tags, or an implicit Suite |

## Native Test Case Definition

The active contract is `att-testcase/v1`. The current default branch includes Native Case CLI and validated Server draft-flow support for discovery, resolution, and RUN of singleton, inline, and Excel-backed Definitions. That code is integrated on this branch. Native RUN is implemented here. No 4.6.0 release or tag has been published. Implicit Suite acceptance, Native report acceptance, and remaining Default Package adoption stay open. Native LOAD Case targets, n-to-m generators, and Debug interoperability stay deferred. Existing direct LOAD and Debug paths remain supported.

The native Excel adapter reads a Package-contained `.xlsx` Data Source without modifying it and records workbook revision, sheet, row, and column provenance on each Resolved Case. It accepts at most 32 MiB of compressed workbook bytes; before POI loads the workbook, ZIP preflight caps it at 256 entries, 32 MiB per expanded entry, and 64 MiB total expanded data. The selected worksheet is capped at 100,000 physical rows, including blank rows. Header scanning is capped at 1,000,000 header cells, and Resolution accepts at most 10,000 nonblank records; preview output remains bounded. YAML cells use the shared parser limits of 3,000,000 code points, 50 collection aliases, and 50 nested levels; resulting value graphs are further capped at depth 64 and 100,000 values. Non-finite numbers and recursive aliases are rejected.

The v1 contract defines a singleton inline Case with a complete typed `inputs` map and the fixed Resolved Case ID `default`, or a multi-record inline/Excel Data Source with stable source IDs. Definition IDs are Package-scoped, case-preserving dotted IDs; Resolved Case IDs are one path-safe segment. Excel Case IDs must be literal text cells matching `[A-Za-z0-9_-]{1,128}`; formulas, numeric/date/boolean/error cells, blank markers, surrounding whitespace, reserved device names, and case-fold collisions are rejected. A `caseIdExpression` can override the resolved ID using only one `${DATA.key}` lookup. Empty, duplicate, malformed, missing, or colliding IDs and empty resolution results are errors; they never produce an all-PASS result.

For data-driven Cases, omitted `inputs` means same-name projection from `DATA`; a supplied projection replaces that identity mapping and uses `{from: DATA.key}` or `{value: <typed value>}`. Excel `dataColumns` explicitly selects columns and supports aliases, YAML parsing, and quoted delimiters. Omitted or blank `dataColumns` selects no business columns into `DATA` (Case ID and tags selectors are never auto-exposed); declare `dataColumns` explicitly when Excel inputs are required. Excel `path` values must be portable Package-relative `.xlsx` paths.

Every Native RUN writes the source-independent result workbook at `workbooks/native-cases.result.xlsx`; it contains the selected Case results and does not depend on the source workbook. An Excel-backed Definition may also set `report: {format: xlsx, template: sourceWorkbook}` to request the optional source-workbook-shaped artifact at `workbooks/native-cases.source-workbook.xlsx`. Before RUN starts, Engine pins and verifies the workbook bytes, formula inventory, and selected physical row identities. It writes results only to those selected rows in a separate file, preserves source formulas, and leaves the Package workbook unchanged.

The optional artifact is limited to one Excel Definition and worksheet, a nonempty selection of at most 1,000 Cases, and an output size of at most 16 MiB. Before publication, ATT redacts sensitive source columns on every worksheet, including columns whose headers or mapped aliases are classified as sensitive, and masks registered secret values elsewhere. If redaction could leave a sensitive cached formula result or safely verifying/publishing the source-shaped file fails, ATT omits that optional artifact and records a fallback diagnostic; the standard result workbook and Case results remain available. A workbook that changed after resolution or no longer matches the pinned rows is rejected before execution rather than silently falling back to stale source data.

The source-shaped artifact contains the workbook's other rows and sheets, with sensitive values redacted. The Server publishes and serves it only when the Job's Package source permission is current; it rechecks Package editor permission for the artifact listing, each entry read, and the archive download. Users without that permission can still access the source-independent standard result workbook and other Job artifacts under the normal artifact policy.

See the [active schema](../../schemas/att-testcase-v1.schema.json), [single inline example](../adr/examples/att-testcase-v1/inline-single.yaml), [multi-record inline example](../adr/examples/att-testcase-v1/inline-multi.yaml), [Excel example](../adr/examples/att-testcase-v1/excel.yaml), [Excel omitted-`dataColumns` example](../adr/examples/att-testcase-v1/excel-omitted-data-columns.yaml), and [ADR-0001](../adr/0001-flow-centric-testcase-resolution.md) for the complete contract and validation boundary.

The Default Package ships offline Native Quick Start Definitions under `packages/default/testcase/quick-start/**/testcase.yaml` bound to Flow `quick.start.v1`. Use the [Default Package Native Quick Start guide](../quick-start.md) and the [S12 migration matrix](../maintenance/native-case-default-package-migration-s12.md). S07 adds source-workbook-shaped reports for Excel-backed Definitions.

### Author in Studio

In Explorer, choose **New native Test Case** or open an indexed `testcase.yaml`. The guided editor sets the Package-scoped Definition ID, name, tags, canonical Flow, typed inputs, and either one Case or inline records. Advanced YAML remains available for fields outside the guided form. New files must be placed under the Package's configured `resources.testcaseRoot` (default `testcase/`).

**Preview / Resolve** sends the current editor candidate and Package-relative `testcase.yaml` path to the authenticated Server. The Server checks the exact USER/editor authority and Package source revisions, then resolves the candidate through the bounded Test Case resolver and S04 Excel adapter. Preview output is capped at 1,000 Cases and returns the Package source revision, Definition revision, and workbook revision when applicable. Excel row errors include sheet, row, and column locations. Typed inputs use the Server's configured redaction policy. Preview does not create a Job or RunDraft, write Package source, or modify a workbook; RUN validation resolves and freezes the selected Case IDs again.

Use **Add to Change Set** to stage a create or replacement through the existing Package source workflow: Draft → Validate → Review → Publish. The current Package source stays active until Publish. After publication, preview the latest source and select the Case IDs to validate and submit a RUN. Native Case RUN is available only when the Server advertises `att-execution/v2`; the Server validates and freezes the source and selected IDs for the Job.

Excel-backed definitions use the same read-only S04 adapter as native resolution, with row and column diagnostics shown in Studio. For a Package authored with an earlier model, use the [Legacy documentation](../legacy/README.md).

### Run an implicit Native Case Suite

The 4.6.0 release target adds Suite selection over resolved Cases without an authored `suite.yaml`. Declare one or more Definition IDs, then select all Cases, explicit qualified Case IDs, or Cases matching one or more tags. Excluded tags take precedence over included tags.

This local command selects every `smoke` Case from two Definitions except Cases tagged `destructive`:

```sh
att --local --package payments run native-suite --definition fpp.transfer --definition fpp.refund --tag smoke --exclude-tag destructive --fail-fast
```

To select named Cases, use `--qualified-case-id` for each ID, such as `fpp.transfer.customerA`. Each ID must belong to one of the declared Definitions. The Server backend uses the same selectors through the validated `att-execution/v2` RUN draft flow.

ATT orders Definitions by ID and keeps each Definition's source record order. Duplicate Definition IDs, duplicate qualified Case IDs, unknown IDs, and empty selections fail validation. The accepted Server draft freezes its selected IDs and Definition source revisions before the Worker runs. Per-Case results use the existing RUN evidence and report pipeline.

This implicit Suite is a runtime selection, not a reusable authored resource. It does not add LOAD targets or advanced Testdata/generator bindings; Debug interoperability remains deferred.

Studio's Run editor also exposes this selection: enter one or more Definition IDs, then choose all Cases, explicit Qualified Case IDs, or Cases matching tags. Optional included and excluded tags further filter the selection, and replay preserves tag selectors returned in the saved definition. Native Suite `--rerun-failed` retries only failed, executed Cases from the latest eligible completed RUN for the same Package and Definitions. Local runs verify the latest-run pointer and manifest hash; Server drafts freeze the source Job/run, manifest digest, Package revision, and exact Qualified Case IDs. A stale draft or changed source is rejected, skipped Cases are not retried, and per-attempt execution/skipped counts remain separate from source and retry counts. Schedules retain the validated retry source. The same selector is available in Studio's Run editor and on the CLI. It is implemented and integrated on the current default branch. Implicit Suite acceptance remains open, and no 4.6.0 release tag has been published.

## Flow

A Flow is a first-class reusable sequence of ordered Actions, declared in `flow.yaml` using `att-flow/v3.6`. A Native Test Case Definition binds that Flow directly. Required fields are `schemaVersion`, a versioned canonical `id` such as `common.payment.v1`, `name`, `description`, and a non-empty ordered `actions` map. Each invocation creates a fresh `EXEC.ACTIONS` scope and restores the caller's scope on return. `META.FLOW` exists during the invocation only. [Actions](actions.md) owns Flow results and Assign behavior; [Context](runtime-context.md) owns scope lifetime.

## Authoring lifecycle

After changing a Flow or a Test Case Definition, run `./att.sh validate --package`, then RUN the selected Resolved Cases. Use [Debug](execution-modes/debug.md) to isolate one Flow or Tool, and [Run](execution-modes/run.md) to create Case executions. Follow [Quick Start](../quick-start.md) to build the first package.

## Test data ownership

A Native Test Case Definition owns its Data Source and input projections. Resolution reads that source and does not modify it. [Context](runtime-context.md) defines their scope and lifetime. RUN turns each selected Resolved Case into a Case execution. Environment selection belongs to [Configuration](configuration.md).

## Testdata registry and input mapping

Use `att-testdata/v1.0` descriptors for reusable records, then reference them from a Case, Debug `inputs`, or LOAD workload `inputs` mapping. An exact `@{id}` reference keeps the record's native map/list/scalar type; `@{id.path}` selects a nested value, including a numeric list index. Interpolated references such as `"ORD-@{accounts.id}"` produce text and therefore require a scalar value from the selected record. `${...}` in a mapping reads Context roots initialized before that mapping is resolved. The allowed roots depend on the mapping phase, and validation checks them before execution starts (before the scheduler starts for Load):

- RUN mappings may read `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, `EXEC.OUTPUT_DIR`, and `META.SOURCE` or `META.TARGET`.
- Debug `inputs` may read the same execution roots and metadata, plus target metadata.
- Load workload `inputs` may read `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, initialized `EXEC.LOAD` identity fields, and `META.SOURCE`, `META.TARGET`, or target metadata. `EXEC.ID` and `EXEC.OUTPUT_DIR` are initialized only after input resolution. `EXEC.LOAD.USER_ID` is absent for arrival-rate workloads; use the optional path form `${EXEC.LOAD.USER_ID?}` when one mapping must support both models.

Every mode rejects references to `EXEC.INPUT` (the value being built), `EXEC.VARS`, `EXEC.ACTIONS`, Action `output`, and invocation-scoped helper metadata. The V1 mapping grammar evaluates literals, selected-record `@{...}` references, and `${...}` Context references; built-in calls are not evaluated. `#{...}`, `&{...}`, and `%{...}` are not input-mapping expressions.

~~~yaml
schemaVersion: att-testdata/v1.0
id: accounts
records:
  - {id: "A-100", tier: gold}
  - {id: "A-200", tier: silver}
selection: {strategy: sequential, exhaustion: recycle}
~~~

Generated records are virtual and indexed; ATT materializes only the selected record. The inclusive integer range is capped at 1,000,000 records, and `%{seq}` is the only supported generated-record substitution:

~~~yaml
schemaVersion: att-testdata/v1.0
id: generatedAccounts
records:
  generate:
    seq: {from: 100, to: 999999, format: "%06d"}
  record: {id: "A-%{seq}", amount: 42}
selection: {strategy: roundRobin, exhaustion: stop}
~~~

The [Package Manifest](package-manifest.md) declares the Package Testdata registry and optional Environment-specific replacement lists. A Load scenario may declare its own top-level `testdata` imports; matching IDs replace the whole environment descriptor for that Load only. Duplicate IDs within one layer fail. Ordinary Run/Debug activate referenced IDs lazily, while `validate --package` checks every declared Manifest descriptor layer. Flows and Tool definitions receive resolved values through `EXEC.INPUT`; they cannot contain direct `@{...}` or `%{...}` references. See [Environment and Test Data](configuration.md), [Load](execution-modes/load.md), and the maintainer [testdata design](../system-design/testdata.md).
