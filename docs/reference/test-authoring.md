# Test authoring

## Author and run a Testcase

A Testcase is one normalized workbook row; Run creates one Case execution from each selected row. Use this workflow to author, validate, and execute a change.

1. Edit the workbook and map its columns in the Sidecar.
2. Generate the Snapshot and review its diff.
3. Validate the package with ./att.sh validate --package.
4. Use [Debug](execution-modes/debug.md) to isolate a Template, Flow, or Tool, then use [Run](execution-modes/run.md) to create Case executions.

The following sections define each authoring contract. See [Advanced workbook and Snapshot details](#advanced-workbook-and-snapshot-details) for formula cells, multi-row headers, and XML serialization.

## Distinguish Testcases from Case executions

A **Testcase** is the authored workbook row after sidecar mapping and snapshot normalization. Its full Case ID is `workbookId.groupId.rowCaseId`. A **Case execution** is one runtime execution of that Testcase by Run, with its own status and evidence. The identifier is shared; the concepts are not synonyms. This page uses *Testcase* for workbook content and *Case execution* for runtime work.

## Authoring contracts

This page explains the normal day-to-day workflow in the same order that data moves through ATT.

| Need | Use |
|---|---|
| Stage execution entry point | Template |
| Reusable Template logic | Flow |
| One ordered operation | Action |
| External capability | Resource |
| Testcase/Stage business input | EXEC.INPUT |
| Cross-Action mutable state | EXEC.VARS |

## Workbook

### Workbook, Sidecar, and Snapshot relationship

Every `.xlsx` workbook requires a YAML sidecar and generated XML snapshot with the same basename in the same directory:

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

Each Action has a type-specific contract. A project-file expression such as `&{templates/payment/request.xml}` returns the exact UTF-8 file content as a String without creating a file. Tool/DB/HTTP/MQ/SSH actions publish the native typed operation result. Log formats typed values for human observation. Assign publishes values to EXEC.VARS, and Flow runs in a nested Action scope.

See [Actions and Typed Values](actions.md) for the complete field list, examples, typed result/evidence model, HTTP/MQ/SSH boundaries and migration guidance. [Expressions and Built-ins](expressions.md) covers the shared expression language; [Load](execution-modes/load.md) owns ID initialization.

## Flow

A Flow is reusable Template logic, declared in `flow.yaml` using `att-flow/v3.6`. Required fields are `schemaVersion`, a versioned canonical `id` such as `common.payment.v1`, `name`, `description`, and a non-empty ordered `actions` map. A Template invokes it through a Flow Action with `use: common.payment.v1`. Each invocation creates a fresh `EXEC.ACTIONS` scope and restores the caller's scope on return. `META.FLOW` exists during the invocation only. [Actions](actions.md) owns Flow results and Assign behavior; [Context](runtime-context.md) owns scope lifetime.

## Authoring lifecycle

After changing a Workbook, generate its Snapshot, review and commit the diff. After changing a Sidecar, Template, Flow or Resource, run `./att.sh validate --package`. Use [Debug](execution-modes/debug.md) to isolate an authoring check and [Run](execution-modes/run.md) to create Case executions from Testcases. Follow [Quick Start](../quick-start.md) to build the first package.

## Test data ownership

Workbook/Sidecar/Snapshot defines Testcase data. Testcase and Stage business inputs enter `EXEC.INPUT`; [Context](runtime-context.md) defines their scope and lifetime. Run turns each selected Testcase into a Case execution. Environment selection belongs to [Configuration](configuration.md).

## Testdata registry and input mapping

Use `att-testdata/v1.0` descriptors for reusable records, then reference them only from a Case, Stage, Debug `inputs`, or Load workload `inputs` mapping. An exact `@{id}` reference keeps the record's native map/list/scalar type; `@{id.path}` selects a nested value, including a numeric list index. Interpolated references such as `"ORD-@{accounts.id}"` produce text and therefore require a scalar value from the selected record. `${...}` in a mapping reads Context roots initialized before that mapping is resolved. The allowed roots depend on the mapping phase, and validation checks them before execution starts (before the scheduler starts for Load):

- Run Case/Stage mappings may read `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, `EXEC.OUTPUT_DIR`, and `META.PROJECT`, `META.SOURCE`, or `META.TARGET`.
- Debug `inputs` may read the same execution roots and metadata, plus `META.TEMPLATE`.
- Load workload `inputs` may read `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, initialized `EXEC.LOAD` identity fields, and `META.PROJECT`, `META.SOURCE`, `META.TARGET`, or `META.TEMPLATE`. `EXEC.ID` and `EXEC.OUTPUT_DIR` are initialized only after input resolution. `EXEC.LOAD.USER_ID` is absent for arrival-rate workloads; use the optional path form `${EXEC.LOAD.USER_ID?}` when one mapping must support both models.

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

An environment profile's `testdata` list declares the shared registry. A Load scenario may declare its own top-level `testdata` imports; matching IDs replace the whole environment descriptor for that Load only. Duplicate IDs within one layer fail. Ordinary Run/Debug activate referenced IDs lazily, while `validate --package` checks every configured descriptor. Templates, Flows, and Tool definitions receive resolved values through `EXEC.INPUT`; they cannot contain direct `@{...}` or `%{...}` references. See [Environment and Test Data](configuration.md), [Load](execution-modes/load.md), and the maintainer [testdata design](../system-design/testdata.md).

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
