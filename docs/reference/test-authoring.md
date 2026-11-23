# Test authoring

## Author a Native Case

New Cases follow this path. Workbook, Stage, and Template remain supported under [Workbook](#workbook). They are not the only Case authoring model.

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
- Excel `dataColumns` keeps the `att-sidecar/v2.2` comma-separated syntax, including aliases, `(yaml)` parsing, and quoted delimiters.
- The Definition `id` is Package-scoped. A Resolved Case ID is one path-safe segment. The qualified ID is `definitionId.caseId`.
- Typed input projections, reports, and Package files stay isolated. Resolution and RUN do not modify the Definition or the source workbook.

n-to-m generators are outside `att-testcase/v1` and remain deferred. Formula cells, multi-row headers, and XML serialization for the legacy workbook path remain in [Advanced workbook and Snapshot details](#advanced-workbook-and-snapshot-details).

## Distinguish Resolved Cases from Case executions

A **Resolved Case** is one selectable logical Case produced by Resolution. A **Case execution** is one runtime execution of that Resolved Case. The qualified Case ID is stable across RUNs; the execution ID, status, and evidence belong to one execution. On the supported legacy workbook path, the authored Testcase is still one normalized workbook row whose full Case ID is `workbookId.groupId.rowCaseId`. This page uses *Resolved Case* for Native content, *Testcase* for legacy workbook content, and *Case execution* for runtime work.

## Authoring contracts

| Need | Current Native path | Supported legacy path |
|---|---|---|
| Reusable ordered Actions | Flow | Template, which may call a Flow |
| One ordered operation | Action | Action |
| External capability | Resource | Resource |
| Business input | Definition `inputs` or a `DATA` projection | `EXEC.INPUT` from the Stage and workbook row |
| Cross-Action mutable state | `EXEC.VARS` | `EXEC.VARS` |
| Case selection | Resolved Case IDs, tags, or an implicit Suite | Workbook row, Stage, tag, or legacy Suite selectors |

## Native Test Case Definition

The active contract is `att-testcase/v1`. The current default branch includes Native Case CLI and validated Server draft-flow support for discovery, resolution, and RUN of singleton, inline, and Excel-backed Definitions. That code is integrated on this branch. Native RUN is implemented here. No 4.6.0 release or tag has been published. Implicit Suite acceptance, Native report acceptance, and remaining Default Package adoption stay open. Native LOAD Case targets, n-to-m generators, and Debug interoperability stay deferred. Existing direct LOAD and Debug paths remain supported.

The native Excel adapter reads a package-contained `.xlsx` workbook without modifying it, reuses the legacy Sidecar header and `dataColumns` mapping rules, and records workbook revision, sheet, row, and column provenance on each resolved Case. It accepts at most 32 MiB of compressed workbook bytes; before POI loads the workbook, ZIP preflight caps it at 256 entries, 32 MiB per expanded entry, and 64 MiB total expanded data. The selected worksheet is capped at 100,000 physical rows, including blank rows. Header scanning is capped at 1,000,000 header cells, and Resolution accepts at most 10,000 nonblank records; preview output remains bounded. YAML cells use the shared parser limits of 3,000,000 code points, 50 collection aliases, and 50 nested levels; resulting value graphs are further capped at depth 64 and 100,000 values. Non-finite numbers and recursive aliases are rejected. It does not create or update the legacy XML Snapshot. Existing workbook/Sidecar/Snapshot RUN remains supported.

The v1 contract defines a singleton inline Case with a complete typed `inputs` map and the fixed Resolved Case ID `default`, or a multi-record inline/Excel Data Source with stable source IDs. Definition IDs are Package-scoped, case-preserving dotted IDs; Resolved Case IDs are one path-safe segment. Excel Case IDs must be literal text cells matching `[A-Za-z0-9_-]{1,128}`; formulas, numeric/date/boolean/error cells, blank markers, surrounding whitespace, reserved device names, and case-fold collisions are rejected. A `caseIdExpression` can override the resolved ID using only one `${DATA.key}` lookup. Empty, duplicate, malformed, missing, or colliding IDs and empty resolution results are errors; they never produce an all-PASS result.

For data-driven Cases, omitted `inputs` means same-name projection from `DATA`; a supplied projection replaces that identity mapping and uses `{from: DATA.key}` or `{value: <typed value>}`. Excel `dataColumns` remains the `att-sidecar/v2.2` comma-separated string syntax, including aliases, `(yaml)` parsing, and quoted delimiters. Omitted or blank `dataColumns` selects no business columns into `DATA` (Case ID and tags selectors are never auto-exposed); declare `dataColumns` explicitly when Excel inputs are required. Excel `path` values must be portable Package-relative `.xlsx` paths.

Every Native RUN writes the source-independent result workbook at `workbooks/native-cases.result.xlsx`; it contains the selected Case results and does not depend on the source workbook. An Excel-backed Definition may also set `report: {format: xlsx, template: sourceWorkbook}` to request the optional source-workbook-shaped artifact at `workbooks/native-cases.source-workbook.xlsx`. Before RUN starts, Engine pins and verifies the workbook bytes, formula inventory, and selected physical row identities. It writes results only to those selected rows in a separate file, preserves source formulas, and leaves the Package workbook unchanged.

The optional artifact is limited to one Excel Definition and worksheet, a nonempty selection of at most 1,000 Cases, and an output size of at most 16 MiB. Before publication, ATT redacts sensitive source columns on every worksheet, including columns whose headers or mapped aliases are classified as sensitive, and masks registered secret values elsewhere. If redaction could leave a sensitive cached formula result or safely verifying/publishing the source-shaped file fails, ATT omits that optional artifact and records a fallback diagnostic; the standard result workbook and Case results remain available. A workbook that changed after resolution or no longer matches the pinned rows is rejected before execution rather than silently falling back to stale source data.

The source-shaped artifact contains the workbook's other rows and sheets, with sensitive values redacted. The Server publishes and serves it only when the Job's Package source permission is current; it rechecks Package editor permission for the artifact listing, each entry read, and the archive download. Users without that permission can still access the source-independent standard result workbook and other Job artifacts under the normal artifact policy.

See the [active schema](../../schemas/att-testcase-v1.schema.json), [single inline example](../adr/examples/att-testcase-v1/inline-single.yaml), [multi-record inline example](../adr/examples/att-testcase-v1/inline-multi.yaml), [Excel example](../adr/examples/att-testcase-v1/excel.yaml), [Excel omitted-`dataColumns` example](../adr/examples/att-testcase-v1/excel-omitted-data-columns.yaml), and [ADR-0001](../adr/0001-flow-centric-testcase-resolution.md) for the complete contract and validation boundary.

The Default Package ships offline Native Quick Start Definitions under `packages/default/testcase/quick-start/**/testcase.yaml` bound to Flow `quick.start.v1`. Use the [Default Package Native Quick Start guide](../quick-start.md) and the [S12 migration matrix](../maintenance/native-case-default-package-migration-s12.md). S07 adds source-workbook-shaped reports for Excel-backed Definitions.

### Author in Studio

In Explorer, choose **New native Test Case** or open an indexed `testcase.yaml`. The guided editor sets the Package-scoped Definition ID, name, tags, canonical Flow, typed inputs, and either one Case or inline records. Advanced YAML remains available for fields outside the guided form. New files must be placed under the Package's configured `resources.testcaseRoot` (default `testcase/`).

**Preview / Resolve** sends the current editor candidate and Package-relative `testcase.yaml` path to the authenticated Server. The Server checks the exact USER/editor authority and Package source revisions, then resolves the candidate through the bounded Test Case resolver and S04 Excel adapter. Preview output is capped at 1,000 Cases and returns the Package source revision, Definition revision, and workbook revision when applicable. Excel row errors include sheet, row, and column locations. Typed inputs use the Server's configured redaction policy. Preview does not create a Job or RunDraft, write Package source, or modify a workbook; RUN validation resolves and freezes the selected Case IDs again.

Use **Add to Change Set** to stage a create or replacement through the existing Package source workflow: Draft → Validate → Review → Publish. The current Package source stays active until Publish. After publication, preview the latest source and select the Case IDs to validate and submit a RUN. Native Case RUN is available only when the Server advertises `att-execution/v2`; the Server validates and freezes the source and selected IDs for the Job.

Excel-backed definitions use the same read-only S04 adapter as native resolution, with row and column diagnostics shown in Studio. The existing Workbook/Sidecar editor remains available for legacy suites.

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

## Workbook

Workbook, Stage, and Template remain a supported runtime. Use this path when Stage selection, `runWhen` / `onFailure`, or an existing Snapshot identity is required. It is not the only Case authoring model.

### Workbook, Sidecar, and Snapshot relationship

In the legacy Workbook/Sidecar/Snapshot workflow, every `.xlsx` workbook requires a YAML sidecar and generated XML snapshot with the same basename in the same directory:

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
testcase/payment_regression.xml
```

The sidecar maps Excel structure into ATT concepts. It owns the sheet mapping, headers, case data, ordered stages, and optional report-column labels. Timeout and retry policy do not belong to the workbook.

```yaml
schemaVersion: att-sidecar/v2.2
id: paymentRegression
excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
  headerRows: 2
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, amount=金額, expected=預期結果(yaml)
stages:
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道, options=執行參數(yaml)
    required: true
    runWhen: normal
    onFailure: stop
```

The root `id` is mandatory and must be unique across the package. `excel.sheet` accepts one sheet name or comma-separated `groupId=sheetName` entries. If one sheet is given without a group ID, ATT uses `default`. Full Case IDs always have the form `workbookId.groupId.rowCaseId` and must be unique across the package.

After editing Excel, run `./att.sh snapshot --suite testcase/payment_regression.xlsx`. The generated `payment_regression.xml` uses schema `att-testcases/v2.4` and stores only normalized sidecar-mapped semantics. It preserves group, Case, tag, map/list, and stage order, uses explicit value types, and excludes styles and unrelated workbook content. Review and commit the XML with the xlsx; do not edit it manually.

Ordinary `run` and every `validate` mode remain read-only and fail before output creation if the XML is missing, invalid, non-canonical, or stale. `run --update-snapshot` explicitly permits ATT to refresh only changed snapshots for the selected complete workbooks before applying the same verification and validation gates. It never writes partial Case/tag snapshots, does not invoke tools during update, rejects snapshot symlinks, and also performs the authorized update when combined with `--dry-run`. Byte-identical snapshots are not rewritten.

### Mapping data columns

`dataColumns` accepts:

```text
ColumnName
alias=ColumnName
ColumnName(yaml)
alias=ColumnName(yaml)
```

Ordinary columns enter Context as strings. A `(yaml)` column parses the displayed cell value into a YAML scalar, list, or map.

Double quotes protect commas, equals signs, and parentheses:

```yaml
dataColumns: amount=金額, note="備註,補充", formula="規則=值", payload="請求(yaml)"(yaml)
```

The final `(yaml)` is the ATT parsing marker. In the last example, the physical Excel header is `請求(yaml)`.

### Blank values

`N/A`, `NA`, `NULL`, `NONE`, empty cells, and whitespace-only values normalize to blank. An ordinary blank data value becomes the empty string. A blank `(yaml)` cell remains blank rather than being parsed.

A required stage selector rejects a blank value. An optional stage with a blank selector is skipped.

### Workbook Sidecar

| Object | Allowed properties | Required/constraints |
|---|---|---|
| root | `schemaVersion`, `id`, `excel`, `stages`, `report`, `x-*` | schemaVersion, package-unique id, excel, non-empty stages required |
| `excel` | `sheet`, `headerRows`, `caseId`, `tags`, `dataColumns` | sheet, caseId, tags required; headerRows ≥ 1 |
| `stages[]` | `key`, `template`, `dataColumns`, `required`, `runWhen`, `onFailure` | key/template required; key has no dot |
| `report` | `columns` | values are strings |

Only the sidecar root permits `x-*`; `excel`, stages, and sidecar `report` reject extensions and other unknown fields. The sidecar cannot override timeout, retry, tools, dbhelpers, template root, environment, or output root.

## Stage

Each sidecar stage has a dot-free `key` and a `template` field naming the physical Excel selector column. The selector cell may contain a symbolic template name, full relative template path, or YAML map:

| Cell value | Meaning |
|---|---|
| `PAYMENT_INVOKE` | Symbolic-name shorthand |
| `payment/local/CT001` | Full-path shorthand relative to `templates.root` |
| `name: PAYMENT_INVOKE` | Explicit symbolic-name map |
| `name: PAYMENT_INVOKE` plus other keys | Template selection plus stage-private row data |

ATT first resolves `name` as a globally unique symbolic name. Only when no symbolic name matches does it try a complete relative template path. Absolute paths, partial paths, and paths escaping `templates.root` are invalid.

All selector-map keys, including `name`, are copied into the stage Context. `stages[].dataColumns` adds more stage-private values. A duplicate key between the selector map and stage data columns is an error.


Stage `required`, `runWhen` and `onFailure` behavior is defined in [Reliability](reliability-execution-control.md).

## Template

A directory is a callable Template only when it directly contains template.yaml. ATT uses att-template/v3.6. Each Template has a non-empty ordered actions map and a required description.

Each Action has a type-specific contract. A file-content expression such as `&{templates/payment/request.xml}` returns the exact UTF-8 file content as a String without creating a file. Tool/DB/HTTP/MQ/SSH actions publish the native typed operation result. Log formats typed values for human observation. Assign publishes values to EXEC.VARS, and Flow runs in a nested Action scope.

See [Actions and Typed Values](actions.md) for the complete field list, examples, typed result/evidence model, HTTP/MQ/SSH boundaries and migration guidance. [Expressions and Built-ins](expressions.md) covers the shared expression language; [Load](execution-modes/load.md) owns ID initialization.

## Flow

A Flow is reusable Template logic, declared in `flow.yaml` using `att-flow/v3.6`. Required fields are `schemaVersion`, a versioned canonical `id` such as `common.payment.v1`, `name`, `description`, and a non-empty ordered `actions` map. A Template invokes it through a Flow Action with `use: common.payment.v1`. Each invocation creates a fresh `EXEC.ACTIONS` scope and restores the caller's scope on return. `META.FLOW` exists during the invocation only. [Actions](actions.md) owns Flow results and Assign behavior; [Context](runtime-context.md) owns scope lifetime.

## Authoring lifecycle

After changing a Workbook, generate its Snapshot, review and commit the diff. After changing a Sidecar, Template, Flow or Resource, run `./att.sh validate --package`. Use [Debug](execution-modes/debug.md) to isolate an authoring check and [Run](execution-modes/run.md) to create Case executions from Testcases. Follow [Quick Start](../quick-start.md) to build the first package.

## Test data ownership

Workbook/Sidecar/Snapshot defines Testcase data. Testcase and Stage business inputs enter `EXEC.INPUT`; [Context](runtime-context.md) defines their scope and lifetime. Run turns each selected Testcase into a Case execution. Environment selection belongs to [Configuration](configuration.md).

## Testdata registry and input mapping

Use `att-testdata/v1.0` descriptors for reusable records, then reference them only from a Case, Stage, Debug `inputs`, or Load workload `inputs` mapping. An exact `@{id}` reference keeps the record's native map/list/scalar type; `@{id.path}` selects a nested value, including a numeric list index. Interpolated references such as `"ORD-@{accounts.id}"` produce text and therefore require a scalar value from the selected record. `${...}` in a mapping reads Context roots initialized before that mapping is resolved. The allowed roots depend on the mapping phase, and validation checks them before execution starts (before the scheduler starts for Load):

- Run Case/Stage mappings may read `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, `EXEC.OUTPUT_DIR`, and `META.SOURCE` or `META.TARGET`.
- Debug `inputs` may read the same execution roots and metadata, plus `META.TEMPLATE`.
- Load workload `inputs` may read `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, initialized `EXEC.LOAD` identity fields, and `META.SOURCE`, `META.TARGET`, or `META.TEMPLATE`. `EXEC.ID` and `EXEC.OUTPUT_DIR` are initialized only after input resolution. `EXEC.LOAD.USER_ID` is absent for arrival-rate workloads; use the optional path form `${EXEC.LOAD.USER_ID?}` when one mapping must support both models.

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

The [Package Manifest](package-manifest.md) declares the Package Testdata registry and optional Environment-specific replacement lists. A Load scenario may declare its own top-level `testdata` imports; matching IDs replace the whole environment descriptor for that Load only. Duplicate IDs within one layer fail. Ordinary Run/Debug activate referenced IDs lazily, while `validate --package` checks every declared Manifest descriptor layer. Templates, Flows, and Tool definitions receive resolved values through `EXEC.INPUT`; they cannot contain direct `@{...}` or `%{...}` references. See [Environment and Test Data](configuration.md), [Load](execution-modes/load.md), and the maintainer [testdata design](../system-design/testdata.md).

## Advanced workbook and Snapshot details

### Normalize XML text safely

String values containing LF or XML-special `&`, `<`, or `>` characters use CDATA; literal `]]>` content is split across adjacent CDATA sections and reconstructs exactly when parsed. Spaces/tabs immediately before LF use `&#32;`/`&#9;` between CDATA sections, preserving the value without Git trailing-whitespace warnings. Review and commit the XML with the xlsx; do not edit it manually.

### Formula, date, percentage, and scientific notation cells

V2.4 rejects formula cells in configured Case ID, tags, case-data, stage-selector, and stage-data columns. Formula definitions and cached/displayed results can diverge and therefore cannot produce a trustworthy semantic snapshot. Recalculate in Excel and paste the result as a literal value, or calculate it in a dedicated ATT step.

Merged regions intersecting configured testcase columns below `excel.headerRows` are likewise rejected. Merged presentation cells wholly inside the configured header area remain allowed.

For non-formula cells, ATT imports the displayed text. The exact representation follows the workbook's cell format and the runtime locale:

| Excel value and format | Context value |
|---|---|
| `45292` formatted `yyyy-mm-dd` | `2024-01-01` |
| `0.125` formatted `0.0%` | `12.5%` |
| `123000` formatted `0.00E+00` | `1.23E+05` |
| `000123` stored/formatted as text | `000123` |

An ordinary column remains a string. A `(yaml)` column may convert displayed text into another YAML type. Quote a YAML scalar when text such as a date, percentage, scientific number, account number, or code must stay a string.

### Multi-row headers

`headerRows: 2` means rows 1–2 are headers and data begins at row 3. ATT scans each physical column top-to-bottom and uses its last non-empty trimmed header cell:

```text
Row 1: Basic data |           | Execution |
Row 2: Case ID    | Case name | Template  | Parameters
Effective: Case ID, Case name, Template, Parameters
```

ATT does not concatenate parent and child labels. Header matching removes spaces, tabs, line breaks, non-breaking spaces, and other Unicode whitespace from both the effective Excel header and configured sidecar/report label; matching otherwise remains case-sensitive. For example, `案例 編號`, `案例\n編號`, and `案例編號` identify the same column. Every effective header must exist exactly once after this normalization, so two physical headers that differ only by whitespace are a duplicate-header error. Testcase loading and result-workbook writing use this same projection; result columns that do not already exist are written to the final header row.
