## 02 Test Authoring

### Authoring contracts

This chapter explains the normal day-to-day workflow in the same order that data moves through ATT.

### 2.1 Workbook

#### Workbook, sidecar, and snapshot relationship

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

After editing Excel, run `./att.sh snapshot --suite testcase/payment_regression.xlsx`. The generated `payment_regression.xml` uses schema `att-testcases/v2.4` and stores only normalized sidecar-mapped semantics. It preserves group, Case, tag, map/list, and stage order, uses explicit value types, and excludes styles and unrelated workbook content. String values containing LF or XML-special `&`, `<`, or `>` characters use CDATA; literal `]]>` content is split across adjacent CDATA sections and reconstructs exactly when parsed. Spaces/tabs immediately before LF use `&#32;`/`&#9;` between CDATA sections, preserving the value without Git trailing-whitespace warnings. Review and commit the XML with the xlsx; do not edit it manually.

Ordinary `run` and every `validate` mode remain read-only and fail before output creation if the XML is missing, invalid, non-canonical, or stale. `run --update-snapshot` explicitly permits ATT to refresh only changed snapshots for the selected complete workbooks before applying the same verification and validation gates. It never writes partial Case/tag snapshots, does not invoke tools during update, rejects snapshot symlinks, and also performs the authorized update when combined with `--dry-run`. Byte-identical snapshots are not rewritten.

#### Mapping data columns

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

#### Blank values

`N/A`, `NA`, `NULL`, `NONE`, empty cells, and whitespace-only values normalize to blank. An ordinary blank data value becomes the empty string. A blank `(yaml)` cell remains blank rather than being parsed.

A required stage selector rejects a blank value. An optional stage with a blank selector is skipped.

#### Formula, date, percentage, and scientific notation cells

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

#### Multi-row headers

`headerRows: 2` means rows 1–2 are headers and data begins at row 3. ATT scans each physical column top-to-bottom and uses its last non-empty trimmed header cell:

```text
Row 1: Basic data |           | Execution |
Row 2: Case ID    | Case name | Template  | Parameters
Effective: Case ID, Case name, Template, Parameters
```

ATT does not concatenate parent and child labels. Header matching removes spaces, tabs, line breaks, non-breaking spaces, and other Unicode whitespace from both the effective Excel header and configured sidecar/report label; matching otherwise remains case-sensitive. For example, `案例 編號`, `案例\n編號`, and `案例編號` identify the same column. Every effective header must exist exactly once after this normalization, so two physical headers that differ only by whitespace are a duplicate-header error. Testcase loading and result-workbook writing use this same projection; result columns that do not already exist are written to the final header row.

#### Stages and template selection

Each sidecar stage has a dot-free `key` and a `template` field naming the physical Excel selector column. The selector cell may contain a symbolic template name, full relative template path, or YAML map:

| Cell value | Meaning |
|---|---|
| `PAYMENT_INVOKE` | Symbolic-name shorthand |
| `payment/local/CT001` | Full-path shorthand relative to `templates.root` |
| `name: PAYMENT_INVOKE` | Explicit symbolic-name map |
| `name: PAYMENT_INVOKE` plus other keys | Template selection plus stage-private row data |

ATT first resolves `name` as a globally unique symbolic name. Only when no symbolic name matches does it try a complete relative template path. Absolute paths, partial paths, and paths escaping `templates.root` are invalid.

All selector-map keys, including `name`, are copied into the stage Context. `stages[].dataColumns` adds more stage-private values. A duplicate key between the selector map and stage data columns is an error.

#### Stage execution controls

| Setting | Values/default | Meaning |
|---|---|---|
| `required` | boolean/`false` | Whether a blank selector is an error |
| `runWhen` | `normal`/default, `onSuccess`, `onFailure`, `always` | When the stage is eligible to run |
| `onFailure` | `stop`/default, `continue` | Whether later eligible work may continue |

`continue` never changes FAIL or ERROR into PASS. It only permits later eligible work to run.

| Earlier outcome | Later `normal` | `onSuccess` | `onFailure` | `always` |
|---|---:|---:|---:|---:|
| PASS | Run | Run | Skip | Run |
| FAIL/ERROR with `stop` | Skip | Skip | Run | Run |
| FAIL/ERROR with `continue` | Run | Skip | Run | Run |

Use `onFailure` for rollback/diagnostics and `always` for cleanup or final evidence collection.

### 2.2 Template

A directory is a callable template only when it directly contains `template.yaml`. Category directories may contain other template directories but are not callable themselves.

```yaml
schemaVersion: att-template/v3.1
name: PAYMENT_INVOKE
description: Render and invoke a payment request
actions:
  buildReference:
    type: assign
    name: paymentReference
    expression: "PAY-#{sysdate('yyyyMMdd')}-#{sample.getSeq(10)}"
  renderRequest:
    type: render
    description: "Render request for ${EXEC.INPUT.caseId}; status=${output.status}"
    payload: requests/*.xml
    result: {format: text, path: rendered/{filename}}
    assert: "${output.targetFiles[0]} != null"
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]})}"
    result:
      path: "${EXEC.INPUT.caseId}-response.json"
      format: raw
      overwrite: false
    assert: "${output.result.status} == 'SUCCESS'"
  recordResult:
    type: log
    level: INFO
    message: "Payment ${EXEC.INPUT.caseId} completed"
    file: "${EXEC.ACTIONS.callApi.output.targetFiles[0]}"
```

`schemaVersion`, `description`, and a non-empty ordered `actions` map are required. `name` is optional when the template is always selected by full path; reusable templates should have a globally unique symbolic name.

#### Action types

| Type | Purpose | Required fields | Common result |
|---|---|---|---|
| `render` | Render one or more UTF-8 payloads | `type`, `payload`, `result.format`; optional `result.path` | nested `output.result` and `output.targetFiles` |
| `tool` | Invoke a configured external tool | `type`, `call` | nested typed result and process evidence |
| `db` | Query or update a configured database | `type`, `db`, exactly one `query`/`update` block | stable typed DB result and transaction evidence |
| `assert` | Evaluate a boolean expression | `type`, `assert` | PASS/FAIL or evaluation ERROR; optional Expected/Actual values |
| `log` | Write a rendered message and/or UTF-8 Case-output file | `type`, at least one of `message` or `file` | combined content, source path, and rendered fields |
| `assign` | Evaluate an expression and publish a Case-scoped typed value | `type`, `name`, `expression` | `${EXEC.VARS.<name>}`, `output.name`, and `output.result` |

Actions run in YAML order. Action IDs are unique within the template and cannot contain a dot. Every action may define `description` and `onFailure: stop|continue`.

Action validation is type-specific. Render requires a safe non-empty payload glob and `result.format: raw|text|json|yaml|xml`; an optional `result.path` persists its result. Tool, MQ receive/request, and DB use the same optional `result` object. Retry and Action-level timeout are valid only for tool actions. A DB action requires a configured `db` ID and exactly one `query` or `update` block; the selected block requires exactly one `sql` or `sqlFile` source. An assert action requires `assert` and may include `expected` and `actual`; `expression`, `acture`, and `actural` are invalid there. A log action requires `message`, `file`, or both and may use `level` and `fields`. An assign action requires `name` and `expression`. Unsupported fields are errors rather than ignored values.

`result.format` selects the in-memory `output.result` representation; `result.path` optionally persists that same selected, typed value without changing its representation. A pathless result creates no artifact; `path: console` writes the selected representation to the Case log only. Legacy `renderAs` and `saveAs` fields are rejected with migration suggestions by `att validate`.

For a configured process Tool with `result.format: raw`, `output.result` is the trimmed, bounded stdout preview (`rawOutput`), not the complete capture file. A real `result.path` persists exactly that same selected value as UTF-8, so whitespace trimming and any preview truncation are consistent between the Context value and the artifact. Full streamed stdout remains separate process evidence/log capture.

Every action may use `assert` except that an assert action uses it as its required primary expression. Every action outcome is nested under `output`, including `status`, `success`, `durationMs`, `exception`, `targetFiles`, `result`, and optional assertion detail. Operational errors remain ERROR; otherwise an explicit assertion decides PASS/FAIL. A completed tool process with a non-zero exit code is not automatically ERROR: inspect `output.exitCode` in `assert` when the exit code matters.

Every action supports expression-bearing `description`. Validation checks `${...}` references and `#{...}` calls without invoking them, resolves available static Case values where needed, and preserves runtime-dependent references. After successful execution, ATT evaluates both forms against the current action-local `${output...}` scope before persisting the final description.

An assign action evaluates `expression` with the normal Context, built-in, configured-tool, and read-only DB-expression grammar. Its `name` must match `[A-Za-z_][A-Za-z0-9_]*`, is case-sensitive, and must not already exist below `EXEC.VARS` for the current Case. `EXEC.VARS` is created once per Test Case, survives stage/template changes, and keeps runtime assignments separate from Excel and framework-owned Case fields. A complete typed expression such as `#{db.orders.query(...)}` retains its Java object; it is not stringified. A successful assignment remains available to later actions and later stages as `${EXEC.VARS.<name>}`. The same value is retained in `${EXEC.ACTIONS.<assignActionId>.output.result}`. Assign supports optional `description`, `assert`, and `onFailure`, but not render, tool-action, log, report-only, retry, timeout, or `result` fields. Assertion FAIL/ERROR does not roll back a value whose expression already evaluated successfully; expression failure creates no variable.

Render payload paths must remain below the template root. Glob matches are regular non-symbolic-link files sorted by portable template-relative path. A Render `result.path` is evaluated as a normal ATT expression path first, then expands `{filename}`, `{name}`, `{ext}`, `{index}`, and `{relativePath}` tokens. Statically knowable path safety and collisions are checked during package validation; runtime-dependent path values are checked again before writing. Expanded targets must be unique; `overwrite: true` never permits intra-Action collisions. Render keeps one typed value for one source or an ordered source-keyed map for multiple sources, and `output.targetFiles` lists only files actually persisted.

A log action can emit a rendered `message`, the complete content of one `file`, or both:

```yaml
logResponse:
  type: log
  level: DEBUG
  message: "API response for ${EXEC.INPUT.caseId}:"
  file: "${EXEC.ACTIONS.callApi.output.targetFiles[0]}"
  fields:
    action: callApi
```

Both `message` and `file` support the unified `${...}` / `#{...}` expression engine and are evaluated before the log action publishes its own output. Their combined content is written to the Case log as raw text with CRLF/CR normalized to LF, so multiline content remains physical lines rather than YAML-escaped `\\n`. A relative `file` path resolves below `${EXEC.OUTPUT_DIR}`; an absolute path is accepted only when its resolved real path is still below that directory. The source must be an existing regular non-symlink UTF-8 file. Path/symlink escapes, malformed UTF-8, blank resolved paths, and attempts to read the current Case log are ERROR.

To print a typed DB result using the same SQL*Plus-style text as DB Action `result.format: text`, format it in the message with the pure `dbText(...)` built-in:

```yaml
printOrders:
  type: log
  message: "#{dbText(${EXEC.ACTIONS.queryOrders.output.result})}"
```

`dbText(...)` only formats its argument; it does not execute JDBC, change a transaction, or invalidate a cache. A nested read-only DB expression is also valid, but referencing a preceding DB Action avoids executing the query twice.

`output.sourceFile` records the canonical source path. `output.result` contains the file text once, with CRLF/CR normalized to LF; when both inputs are present, it contains the message, one LF, then the file content. This same result is emitted once in the human Case log rather than copying the file into multiple evidence fields.
