# ATT V3.4.2 User Manual and Reference

Author: Jeffrey + ChatGPT
Version: 3.4.2
Status: Normative end-user documentation

This manual is designed to be read in two ways:

- If ATT is new to you, read Chapters 1–4 in order. They take you from the first test case to practical development patterns.
- If you already use ATT, Chapters 5–9 are the daily command, configuration, expression, report, and troubleshooting reference.
- Chapter 10 explains internal behavior for maintainers and users diagnosing lifecycle or integration problems.

Where an older V2 example conflicts with this manual, this manual wins. V2 schema compatibility is retained unless a section explicitly requires a V3 schema.

## Contents

01. [Introduction](#01-introduction)
02. [Quick Start](#02-quick-start)
03. [User Guide](#03-user-guide)
   - [3.1 Workbook](#31-workbook)
   - [3.2 Template](#32-template)
   - [3.3 Tool](#33-tool)
   - [3.4 Running Tests](#34-running-tests)
   - [3.5 Reports](#35-reports)
04. [Cookbook](#04-cookbook)
05. [CLI Reference](#05-cli-reference)
06. [Configuration Reference](#06-configuration-reference)
07. [Expression Reference](#07-expression-reference)
08. [Report Reference](#08-report-reference)
09. [Troubleshooting](#09-troubleshooting)
10. [Architecture for Maintainers](#10-architecture-for-maintainers)

## 01 Introduction

ATT is an offline, template-driven API test runner. Test data lives in Excel, complete scenarios live in Template directories, reusable implementation sequences live in Flows, and external capabilities are registered as Tools.

```text
Workbook row → Test case → Ordered stages
Stage → Template-selector column → Current row's selector cell → Template
Template → Ordered actions → Flow or configured tool
Flow → Reusable ordered actions in the calling Template Context → Tool / DB / built-in / nested Flow
```

The four concepts you need first are:

| Concept | What it owns |
|---|---|
| Test case | One workbook row, case-level data, tags, and ordered stages |
| Stage | Template selection, stage-private data, execution condition, and failure handling |
| Template | A complete scenario expressed as an ordered list of actions |
| Flow | A reusable ordered group of Template Actions with a fresh Action scope and explicit `EXEC.VARS` publication |
| Tool | A globally configured external executable with named inputs and one declared output format |
| Dbhelper | One independently configured database connection, timeout, transaction, limit, and evidence policy |

An action can render a payload, call a tool, query/update a database, assert an expression, write a structured log, assign a scoped runtime value, or invoke a Flow. Read-only DB queries are also available in expressions. ATT validates the selected package before executing external tools or JDBC operations and records the resulting evidence below one completed run directory.

### What V3.4 guarantees

- Configuration is strict. Unknown fields, wrong types, invalid enum values, duplicate YAML keys, and invalid action shapes are errors.
- Every workbook has a same-basename YAML sidecar and generated semantic XML snapshot.
- Every template is a directory containing `template.yaml`.
- Every Flow is below `templates/flows/**/flow.yaml`, has a static `.vN` ID, uses the canonical caller input/metadata roots with a fresh Action scope, and has a maximum nesting depth of 3.
- `validate --package` checks the whole package; `validate --selected` checks only a selected dependency closure.
- Run ID and Case ID are validated and then used directly as output directory names.
- The final run directory is reserved before execution so live evidence is directly inspectable; only a completed manifest is published as latest.
- FAIL, ERROR, INVALID, SKIPPED, and PASS have stable aggregation and exit-code meanings.
- JSON, XML, JUnit XML, JUnit HTML, and CI JSON outputs have versioned contracts.
- V2.6 Templates remain readable, but only `att-template/v3.0` may use Flow Actions or Action `runWhen`.
- Multiline Log Action text and process output remain physical Case-log lines; ordinary runs create no persistent `process-output` artifact.
- `#{...}` supports typed calls, arithmetic, comparisons, boolean logic, lists, and `in`, while `${...}` remains the Context-reference syntax.
- Tool Actions may run post-invocation evidence collectors before assertion, with per-attempt results, independent timeout, and `continue|stop` failure policy.
- IBM MQ helpers provide primary Tool Action `send`, `receive`, and `request` calls with exact file payloads and correlation-aware request/reply evidence.

### Package layout

```text
att-package/
├── att.sh
├── att.bat
├── config/
│   ├── config.yaml
│   ├── dbhelpers/
│   │   └── orders.yaml
│   ├── mqhelpers/
│   │   └── orders.yaml
│   └── tools/
│       └── orders-db.yaml
├── testcase/
│   ├── payment.xlsx
│   ├── payment.yaml
│   └── payment.xml
├── templates/
│   ├── payment/
│   │   ├── template.yaml
│   │   └── request.tmp.json
│   └── flows/common/prepare/
│       └── flow.yaml
├── tools/
├── schemas/
└── output/
```

You normally edit `config/config.yaml`, referenced dbhelper files, workbooks, sidecars, templates, payloads, and tool scripts. The same-basename testcase XML is normally generated by `snapshot` and reviewed as source-control evidence; run-only `--update-snapshot` is an explicit opt-in refresh workflow. ATT owns generated content below the configured output directory and its documented build locations.

The global `testcase.root` setting defaults to `testcase`. Discovery is recursive, and an adjacent same-basename XLSX/YAML/XML triple defines one testcase set at any depth.

### V3 Flow authoring contract

A Flow descriptor has exactly the top-level fields `schemaVersion`, `id`, `name`, `description`, and `actions`:

```yaml
schemaVersion: att-flow/v3.0
id: common.decorate.v1
name: Decorate
description: Append a stable suffix.
actions:
  decorate:
    type: assign
    name: decoratedResult
    expression: "${EXEC.INPUT.caseId}-done"
  audit:
    type: log
    message: "Decorated ${EXEC.VARS.decoratedResult}"
    runWhen: "${EXEC.INPUT.auditEnabled} == true"
```

A V3 Template invokes the Flow using a literal canonical ID:

```yaml
schemaVersion: att-template/v3.0
name: PAYMENT_FLOW
description: Complete scenario using one reusable Flow.
actions:
  prepare:
    type: flow
    use: common.decorate.v1
  verify:
    type: assert
    assert: "${EXEC.VARS.decoratedResult} == '${META.SOURCE.caseId}-done'"
```

Flow and inline Template Actions use the same expression engine and canonical `EXEC`/`META` roots, but each Stage/Template and Flow invocation has an explicit Action scope. `output` is Action-local. The `CASE`, `RUN`, and `ACTIONS` roots remain compatibility aliases where they map deterministically. `${...}` reads Context and `#{...}` invokes a Tool, DB facade, or built-in where the Action permits it. An Action may read only earlier completed Actions in its current scope.

Flow `inputs`, `outputs`, invocation `with`, and the dedicated `input`, lowercase `actions`, `runtime`, and `flow` roots are invalid. A Flow `assign` writes `EXEC.VARS` exactly like an inline assign. A Flow's internal Actions are directly readable through `${EXEC.ACTIONS.<internalActionId>.output...}` only while that Flow scope is active; after return the parent scope is restored. Publish any value needed by the caller through `${EXEC.VARS.<name>}`. The Flow invocation Action exposes its standard outcome and never creates `output.outputs`.

Action IDs must be unique within one Stage/Template/Flow scope. Separate or repeated Flow invocations may reuse internal IDs because each invocation receives a fresh scope; duplicate IDs in one scope still fail validation. An invoked Flow with all internal Actions skipped is PASS; a Flow Action whose own `runWhen` is false is SKIPPED.

Flow `use` is never dynamic. `runAlways`, warning impact, Flow timeout/retry, loops, dynamic dispatch, and parallel branches are not V3.4.0 features. Aggregate priority remains `ERROR > INVALID > FAIL > PASS > SKIPPED`.

## 02 Quick Start

This example creates one payment test that renders JSON, invokes a tool, and verifies the returned status.

### Step 1: configure ATT

Create `config/config.yaml`:

```yaml
schemaVersion: att-config/v2.6
outputDirectory: output
environment: SIT
timeoutMs: 10000
templates:
  root: templates
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
xml:
  namespaceMode: ignore
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Send a rendered payment request
    command: ["./tools/invoke_payment_api.sh", "${input.requestFile}", "${input.environment}"]
    output: json
    arguments:
      requestFile:
        name: Request File
        description: Rendered request filename
        required: true
      environment:
        name: Environment
        description: Target environment name
        required: true
```

An argv-list `command` preserves every list item as one process argument. A legacy scalar command is tokenized once into the same internal list. New definitions use the canonical `${input.requestFile}` form; `${requestFile}` remains a declared-argument shorthand only with a validation migration warning.

### Step 2: create the workbook and sidecar

Create `testcase/payment.xlsx` with one header row:

| Case ID | Tags | Amount | Expected Status | Invoke Template |
|---|---|---:|---|---|
| TC001 | smoke,payment | 100 | SUCCESS | PAYMENT_INVOKE |

Create the adjacent `testcase/payment.yaml`:

```yaml
schemaVersion: att-sidecar/v2.2
id: payment
excel:
  sheet: payment=Payment Cases
  headerRows: 1
  caseId: Case ID
  tags: Tags
  dataColumns: amount=Amount, expectedStatus=Expected Status
stages:
  - key: invoke
    template: Invoke Template
    required: true
```

The full Case ID is `payment.payment.TC001`: the first `payment` is the mandatory package-unique workbook `id`, the second is the logical `groupId` on the left of the sheet mapping, and `TC001` comes from the row.

### Step 3: create the template

Create `templates/payment/template.yaml`:

```yaml
schemaVersion: att-template/v2.6
name: PAYMENT_INVOKE
description: Render, invoke, and verify a payment
actions:
  renderRequest:
    type: render
    payload: request.tmp.json
    renderAs: file
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}, environment=${EXEC.INPUT.environment})}"
  assertStatus:
    type: assert
    description: Payment API status matches the expected status
    assert: "${EXEC.ACTIONS.callApi.output.result.status} == ${EXEC.INPUT.expectedStatus}"
    expected: "${EXEC.INPUT.expectedStatus}"
    actual: "${EXEC.ACTIONS.callApi.output.result.status}"
```

Create `templates/payment/request.tmp.json`:

```json
{
  "caseId": "${EXEC.INPUT.caseId}",
  "amount": "${EXEC.INPUT.amount}"
}
```

### Step 4: create the mock tool

Create `tools/invoke_payment_api.sh`:

```sh
#!/usr/bin/env sh
set -eu

request_file="${1:?missing request file}"
environment="${2:?missing environment}"

[ -f "$request_file" ] || {
  echo "request file not found: $request_file" >&2
  exit 2
}

printf '{"status":"SUCCESS","environment":"%s"}\n' "$environment"
```

Make it executable:

```sh
chmod +x tools/invoke_payment_api.sh
```

### Step 5: validate and run

```sh
./att.sh validate --package
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001 --run-id SIT-001 --ci-output junit,json
```

On Windows, replace `./att.sh` with `att.bat`; command names, options, output, and exit codes are identical. This Quick Start tool is a POSIX shell example, so a Windows package must configure an equivalent `.bat`, `.cmd`, PowerShell, or native executable before running that tool.

Use package validation as the release gate. During development, a faster selected check is available:

```sh
./att.sh validate --selected --suite testcase/payment.xlsx --case payment.payment.TC001
```

### Step 6: inspect the result

After successful completion, open:

```text
output/SIT-001/report/index.html
```

Other useful files are:

- `output/SIT-001/run.yaml` — versioned run manifest and input hashes;
- `output/SIT-001/payment.payment.TC001/case.yaml` — persisted runtime Context;
- `output/SIT-001/ci/summary.json` — CI JSON summary;
- `output/SIT-001/ci/junit.xml` — JUnit XML;
- `output/SIT-001/report/junit.html` — human-readable JUnit view.

You now have the complete ATT development loop: author, validate, run, inspect, and refine.

## 03 User Guide

This chapter explains the normal day-to-day workflow in the same order that data moves through ATT.

### 3.1 Workbook

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

### 3.2 Template

A directory is a callable template only when it directly contains `template.yaml`. Category directories may contain other template directories but are not callable themselves.

```yaml
schemaVersion: att-template/v2.6
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
    renderAs: file
    assert: "${output.targetFiles[0]} != null"
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]})}"
    saveAs:
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
| `render` | Render one or more UTF-8 payloads | `type`, `payload`, `renderAs` | nested `output.result` and `output.targetFiles` |
| `tool` | Invoke a configured external tool | `type`, `call` | nested typed result and process evidence |
| `db` | Query or update a configured database | `type`, `db`, exactly one `query`/`update` block | stable typed DB result and transaction evidence |
| `assert` | Evaluate a boolean expression | `type`, `assert` | PASS/FAIL or evaluation ERROR; optional Expected/Actual values |
| `log` | Write a rendered message and/or UTF-8 Case-output file | `type`, at least one of `message` or `file` | combined content, source path, and rendered fields |
| `assign` | Evaluate an expression and publish a Case-scoped typed value | `type`, `name`, `expression` | `${EXEC.VARS.<name>}`, `output.name`, and `output.result` |

Actions run in YAML order. Action IDs are unique within the template and cannot contain a dot. Every action may define `description` and `onFailure: stop|continue`.

Action validation is type-specific. A render action requires a safe non-empty payload glob and `renderAs: file|text|json|yaml|xml`; it cannot contain tool/assert-action/log/DB fields. Retry and Action-level timeout are valid only for tool actions. Tool and DB actions may use the common object-shaped `saveAs`; no other action type may use it. A DB action requires a configured `db` ID and exactly one `query` or `update` block; the selected block requires exactly one `sql` or `sqlFile` source. An assert action requires `assert` and may include `expected` and `actual`; `expression`, `acture`, and `actural` are invalid there. A log action requires `message`, `file`, or both and may use `level` and `fields`. An assign action requires `name` and `expression`. Unsupported fields are errors rather than ignored values.

Every action may use `assert` except that an assert action uses it as its required primary expression. Every action outcome is nested under `output`, including `status`, `success`, `durationMs`, `exception`, `targetFiles`, `result`, and optional assertion detail. Operational errors remain ERROR; otherwise an explicit assertion decides PASS/FAIL. A completed tool process with a non-zero exit code is not automatically ERROR: inspect `output.exitCode` in `assert` when the exit code matters.

Every action supports expression-bearing `description`. Validation checks `${...}` references and `#{...}` calls without invoking them, resolves available static Case values where needed, and preserves runtime-dependent references. After successful execution, ATT evaluates both forms against the current action-local `${output...}` scope before persisting the final description.

An assign action evaluates `expression` with the normal Context, built-in, configured-tool, and read-only DB-expression grammar. Its `name` must match `[A-Za-z_][A-Za-z0-9_]*`, is case-sensitive, and must not already exist below `EXEC.VARS` for the current Case. `EXEC.VARS` is created once per Test Case, survives stage/template changes, and keeps runtime assignments separate from Excel and framework-owned Case fields. A complete typed expression such as `#{db.orders.query(...)}` retains its Java object; it is not stringified. A successful assignment remains available to later actions and later stages as `${EXEC.VARS.<name>}`. The same value is retained in `${EXEC.ACTIONS.<assignActionId>.output.result}`. Assign supports optional `description`, `assert`, and `onFailure`, but not render, tool-action, log, report-only, retry, timeout, or `saveAs` fields. Assertion FAIL/ERROR does not roll back a value whose expression already evaluated successfully; expression failure creates no variable.

Render payload paths must remain below the template root. Glob matches are regular non-symbolic-link files sorted by portable template-relative path. `renderAs: file` writes the rendered result under the Case output directory using that same relative path; collisions are ERROR. Other render modes write no file and store one typed value, or an ordered relative-path-to-value map for multiple matches, in `output.result`.

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

To print a typed DB result using the same SQL*Plus-style text as DB Action `saveAs.format: text`, format it in the message with the pure `dbText(...)` built-in:

```yaml
printOrders:
  type: log
  message: "#{dbText(${EXEC.ACTIONS.queryOrders.output.result})}"
```

`dbText(...)` only formats its argument; it does not execute JDBC, change a transaction, or invalidate a cache. A nested read-only DB expression is also valid, but referencing a preceding DB Action avoids executing the query twice.

`output.sourceFile` records the canonical source path. `output.result` contains the file text once, with CRLF/CR normalized to LF; when both inputs are present, it contains the message, one LF, then the file content. This same result is emitted once in the human Case log rather than copying the file into multiple evidence fields.

### 3.3 Tool

A Tool is a named capability configured either globally under `config.yaml` or inside an independent tool-group file. A command-backed Tool launches an external process; a V2.6 call-backed Tool invokes one typed DB operation or pure built-in. Both use the same outer call syntax and named-argument contract.

#### Tool backend selection and the common Action envelope

Call-backed Tools are the preferred/default extension model for new
framework-native and reusable capabilities. They execute through ATT's typed
runtime and preserve String, Number, Boolean, null, List, Context-derived
values, and nested built-in/helper calls. Command-backed Tools remain fully
supported, but are the special-case external-process extension mechanism for
scripts, third-party CLIs, SSH, operating-system utilities, and other process
boundaries. They are not deprecated.

The backend-specific invocation contracts intentionally remain distinct while
their observable outcome converges at the Action boundary:

| | call-backed | command-backed |
|---|---|---|
| Invocation | typed native/helper call | OS process, script, CLI, or SSH |
| Preferred role | normal Tool model | supported external-process escape hatch |
| Arguments | typed values and nested calls | deterministic argv; scalar is one item and a flat List may expand |
| `argName` / `argNameMode` | invalid/not applicable | supported process-only shaping |
| stdout/stderr and exit code | no process contract | captured process evidence |
| cache | supported where valid | no process cache |
| publication | operation result consumed by the Action runner | operation result consumed by the Action runner |

Every primary Tool, direct DB operation, and MQ helper operation produces one
operation result consumed exactly once by the Action runner. While the Action is active, use
`${output.result}` and `${output.evidence}`. After publication, use
`${EXEC.ACTIONS.<actionId>.output.result}` and
`${EXEC.ACTIONS.<actionId>.output.evidence}`. The envelope may contain
`status`/`success`, `durationMs`, `result`, typed `diagnostic`, helper-native
`evidence`, and retry/collector `attempts` as applicable:

```yaml
# call-backed: typed result, preferred for a reusable native capability
lookupOrder:
  type: tool
  call: "#{orders.find(customerId=${EXEC.INPUT.customerId})}"

# command-backed: legitimate external process use case
invokeApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.INPUT.requestFile})}"
```

The standard evidence keys are `tool`, `db`, and `mq`. Each operation key has
the stable shape `<kind>.invocations[]`, including when only one invocation
exists. For example, use `${output.evidence.db.invocations[0].helperId}` and
`${EXEC.ACTIONS.<actionId>.output.evidence.tool.invocations[0].name}`. The
final/winning primary operation is published at top level; prior and
attempt-specific evidence remains below `${output.attempts[n].evidence}`.
Post-invoke collectors remain under the final Action evidence and the
corresponding attempt evidence. DB connection/transaction state,
MQ connection/queue handles, process handles, and cache leases are resource
lifecycle state and never become `EXEC.DB`, `EXEC.MQ`, `EXEC.TOOL`, or another
helper-specific canonical Context root. Existing root `TOOL.*`/`DB.*` and
Action-level uppercase helper nodes may remain only in internal or persisted
historical/result compatibility views; they are not supported general
expression APIs and do not weaken #29 validation/migration rules. The
completed Case `${CASE.DB.<instance>}` view contains transaction finalization
state, not the DB operation result/evidence.

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered request
    command: ["./tools/invoke_payment_api.sh", "${input.requestFile}", "${input.environment}"]
    output: json
    arguments:
      requestFile:
        name: Request File
        description: Rendered request path
        required: true
      environment:
        name: Environment
        description: Target environment
        required: true
```

The action call must use the configured key. Tools with multiple declared arguments use named arguments:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}, environment=${EXEC.INPUT.environment})}"
```

Unknown, duplicate, or missing required arguments fail validation. Argument metadata documents and validates the contract; it does not inject values automatically. If—and only if—the tool declares exactly one argument, the call may omit its name: `#{getAppLogs(${EXEC.INPUT.caseId})}` is equivalent to `#{getAppLogs(caseId=${EXEC.INPUT.caseId})}`. A zero-argument tool still uses `#{tool()}`; a multi-argument tool rejects positional arguments.

Global tools retain unqualified names. V2.2 groups are listed by `toolGroups` in the global config:

```yaml
toolGroups:
  - config/tools/database.yaml
```

The referenced file declares a package-unique ID and its own tools:

```yaml
schemaVersion: att-tool-group/v2.2
id: database
name: Database tools
description: Read-only queries
script: ["/opt/att/database-tools"]
tools:
  selectPayment:
    name: Select payment
    description: Query one payment
    command: ["select-payment", "--case", "${caseId}"]
    output: json
    arguments:
      caseId: {name: Case ID, description: Full Case ID, required: true}
```

Call it as `#{database.selectPayment(caseId=${EXEC.INPUT.caseId})}`. With `script`, logical argv is `/opt/att/database-tools selectPayment select-payment --case <caseId>`: script argv, unqualified tool key, then tool command argv. Without `script`, the tool command starts with the executable. Persisted grouped evidence is navigable below `TOOL.database.selectPayment`.

```text
#{fpp.invokeApi(requestId=${EXEC.INPUT.requestId}, requestType=${EXEC.INPUT.requestType}, requestFile=${EXEC.INPUT.requestFile}, apiLogPath=${EXEC.INPUT.apiLogPath})}
#{fpp.sqlplusToXml(inputFile=${EXEC.INPUT.sqlplusOutput})}
#{fpp.exehelper(command=${EXEC.INPUT.command}, stdoutPath=${EXEC.INPUT.stdoutPath}, stderrPath=${EXEC.INPUT.stderrPath})}
```

The two reference helpers below deliberately add pathname expansion without changing the normal process-backed Tool contract:

```yaml
runChecks:
  type: tool
  call: >-
    #{fpp.exehelper(
        command='wc',
        arguments=['-l', '${EXEC.OUTPUT_DIR}/requests/*.xml'],
        stdoutPath='${EXEC.OUTPUT_DIR}/request-counts.txt'
    )}

findTransactions:
  type: tool
  call: >-
    #{fpp.loghelper(
        maxTidFiles=10,
        minTidFiles=2,
        outputPrefix='${EXEC.OUTPUT_DIR}/transaction',
        logFiles=['/var/log/payment/app*.log', '/archive/payment/app-2026-07-2?.log'],
        keywords=[${EXEC.INPUT.caseId}, 'SUCCESS'],
        recentLogCount=0,
        sshOption='--ssh'
    )}
```

`fpp.exehelper.arguments` is an ordered typed array. `exehelper.sh` expands each argument containing `*`, `?`, or `[` against its filesystem working directory. Matches use C-locale pathname order and remain separate atomic argv values, including paths containing spaces. An unmatched pattern remains one literal argument. The executable name and stdout/stderr paths are not expanded, and no shell syntax, command substitution, variable expansion, pipe, or redirection is evaluated.

`fpp.loghelper.logFiles` and `keywords` are ordered typed arrays; each path or pathname pattern is expanded independently on the host being searched. Only regular files are accepted, duplicate canonical paths are searched once, and unmatched patterns are skipped with a diagnostic. The call fails when no local pattern resolves to a regular file. Remote hosts receive the original patterns so that expansion uses the remote filesystem.

Running `tools/loghelper.sh` without arguments displays its usage and exits successfully. `--help` and `--usage` provide the same behavior. Help is written to stderr so stdout remains reserved for the ATT YAML result contract.

When `--ssh` is enabled, configure `tools/loghelper.sh` with `SSH_SERVERS`, or provide `LOGHELPER_SSH_SERVERS` as newline-separated records:

```text
localhost||||
server1.example.com|appuser|22||/opt/att/tools/loghelper.sh
server2.example.com|appuser|2222|/secure/att_ed25519|/opt/att/tools/loghelper.sh
```

Each record is `host|user|port|identity-file|remote-loghelper-path`. A shared list may include `localhost`, a loopback address, or the current hostname; loghelper recognizes that entry as the already-searched local host and does not SSH to itself. Non-local entries still require a user, positive port, remote helper path, and any configured identity file.

#### Database helpers

V2.5 treats dbhelper as a first-class database service rather than a Tool implementation. Tool argv, SSH, exit-code, retry, and stdout-parser settings do not apply. Database instances use independent configuration files; templates use `type: db` Actions or the reserved read-only `db.<instance>.*` expression namespace.

> Implementation status: this section describes the V2.5.0 runtime, schema, validation, and packaging contract.

The main configuration contains file references only:

```yaml
dbhelpers:
  - config/dbhelpers/orders.yaml
  - config/dbhelpers/audit.yaml
```

Each package-contained YAML file defines exactly one instance:

```yaml
# config/dbhelpers/orders.yaml
schemaVersion: att-dbhelper/v2.5
id: orders
name: Orders database
description: Query and update orders

connection:
  url: jdbc:postgresql://db.example/orders
  username: att_user
  password: "${ENV:ORDERS_DB_PASSWORD}"
  driverClass: org.postgresql.Driver
  properties:
    connectTimeout: "10"
  readOnly: false
  isolation: readCommitted

statement:
  timeoutSeconds: 30

transaction:
  scope: case
  onEnd: rollback

result:
  maxRows: 1000
  maxCellBytes: 1048576
  maxBytes: 10485760

evidence:
  sql: full
  parameters: values

pool:
  maxSize: 20
  minIdle: 2
  connectionTimeout: 2s
```

`id` is package-global, case-insensitive for uniqueness, and matches `^[A-Za-z_][A-Za-z0-9_-]*$`. The file requires `schemaVersion`, `id`, `name`, `description`, and `connection.url`. `statement`, `transaction`, `result`, and `evidence` are optional. Unknown fields are rejected except `x-*`. Referenced paths must remain inside the package, exist, and be unique after normalization.

`connection.username`, `password`, and property values may be literal strings or complete `${ENV:NAME}` references; a missing variable blocks execution during configuration loading. JDBC URLs, passwords, and password/secret/token property values are removed from evidence and redacted from driver messages. `connection.readOnly: true` rejects DB update Actions and also calls `Connection.setReadOnly`; database account permissions remain the real security boundary. Isolation accepts `driverDefault`, `readUncommitted`, `readCommitted`, `repeatableRead`, or `serializable`.

`evidence.parameters` defaults to `values`, so ordinary resolved SQL bindings are visible in DB and Case logs for testcase diagnosis. `types` records Java type names and `masked` records asterisks instead. Connection credentials are never SQL-parameter evidence. If business SQL parameters themselves contain secrets, select `masked` or `types` for that dbhelper; ATT cannot infer business sensitivity from an arbitrary positional value.

`statement.timeoutSeconds` is configured per instance, defaults to 30, accepts 1–3600, and is passed to every `PreparedStatement.setQueryTimeout`. A DB Action cannot override it. Portable connection timeout is not exposed because JDBC provides no safe per-Connection setter; use driver-specific `connection.properties`.

Transaction settings combine a scope and its completion action:

| `scope` | `onEnd` | Behaviour |
|---|---|---|
| `statement` | `commit` | JDBC auto-commit |
| `statement` | `rollback` | rollback after every DB operation |
| `case` | `commit` | commit each used instance at Case end |
| `case` | `rollback` | rollback each used instance at Case end |

The defaults are `scope: case` and `onEnd: rollback`.

##### DB Actions

A query Action uses a `query` block; there is no separate `operation` field:

```yaml
queryOrder:
  type: db
  db: orders
  query:
    sql: >-
      select id, status
      from orders
      where customer_id = ?
        and status = ?
    params:
      - "${EXEC.INPUT.customerId}"
      - OPEN
  assert: "${output.result.rowCount} > 0"
  saveAs:
    path: order-result.json
    format: json
    overwrite: false
```

An update uses an `update` block:

```yaml
closeOrder:
  type: db
  db: orders
  update:
    sql: "update orders set status = ? where id = ?"
    params: [CLOSED, "${EXEC.INPUT.orderId}"]
  assert: "${output.result.affectedRows} == 1"
```

Exactly one of `query` or `update` is required. Its block requires exactly one of `sql` or `sqlFile`. Use either positional `params` with JDBC `?` placeholders, or named `parameters` with `:name` placeholders; the two forms cannot coexist. `params` may be an inline YAML list or one exact Context reference whose typed value is a Java `List`. `parameters` is a string-keyed map whose values may be typed literals or expressions. ATT compiles each named placeholder to `?`, binds repeated names in occurrence order with `PreparedStatement.setObject`, and never interpolates a value into SQL. Quoted text, line/block comments, and PostgreSQL `::` casts are not placeholders. Missing and unused names fail validation. Batches, generated keys, callable statements, and multiple JDBC results remain unsupported.

`retry` and Action-level `timeoutMs` are invalid for `type: db`. Automatic SQL retry is unsafe for updates. A DB Action otherwise uses the normal `description`, `assert`, and `onFailure` contract. Assertions run only after successful DB execution. DB errors remain `ERROR` and cannot be downgraded by an assertion.

DB `saveAs` uses the common Tool/DB object shape but serializes the typed result rather than stdout. It requires `path` and `format: text|json|yaml|xml`; `overwrite` defaults to false and `raw` is invalid. `path: console` writes the selected representation to the Case log and creates no target file. Other paths must stay under the Case artifact directory. Formatting, serialization, or writing failure is `ERROR`.

For a query, text output uses JDBC column-label order, expands each column to its widest terminal display width (including wide Unicode characters), right-aligns columns whose non-null values are all numeric, prints `NULL` explicitly, and ends with `1 row selected.` or `<n> rows selected.`. Backslashes, control characters, and non-printing format characters inside a header/cell are escaped so one DB row remains one physical line. An empty query is `no rows selected.`. An update is `1 row updated.` or `<n> rows updated.`. Output is UTF-8 with LF line endings. This representation contains table/update content rather than transaction metadata; use JSON, YAML, or XML when the complete stable result object is required.

```text
ID    STATUS  AMOUNT
----  ------  ------
A100  READY    12.50
A101  DONE         3

2 rows selected.
```

##### Rendered SQL and SQL files

Inline SQL is rendered against the current Context before JDBC prepares it. A preceding render Action may supply the complete SQL:

```yaml
query:
  sql: "${EXEC.ACTIONS.renderSql.output.result}"
  params: "${EXEC.INPUT.queryParams}"
```

File-backed SQL is also supported:

```yaml
query:
  sqlFile: sql/orders/find-by-customer.sql
  params:
    - "${EXEC.INPUT.customerId}"
    - OPEN
```

`sqlFile` is a static package-relative UTF-8 path. Validation checks that it is a regular package-contained file. ATT renders its contents using the same rules as inline SQL and records its source path and hash; the path itself is not rendered. SQL-source rendering accepts Context references and pure built-ins, but it does not execute configured Tools or nested DB calls. Perform external preparation in an earlier Action and reference its completed result.

Rendering is for SQL structure. Test data should remain in `params` and use JDBC `?` placeholders. ATT does not parse, classify, rewrite, or interpolate parameter values into vendor SQL.

##### DB queries in expressions

Read-only queries remain available wherever Case-runtime expressions are supported:

```yaml
assert: >-
  #{db.orders.scalar(
      sql='select count(*) from orders where customer_id = ?',
      params=${EXEC.INPUT.customerParams}
  )} == 1
```

Multiple parameters may also be written inline in JDBC placeholder order:

```yaml
#{db.orders.query(
    sql='select id from orders where customer_id = ? and status = ? and amount >= ?',
    params=[${EXEC.INPUT.customerId}, 'OPEN', ${EXEC.INPUT.minimumAmount}]
)}
```

- `#{db.<instance>.query(sql|sqlFile, params?)}` returns the stable query result object.
- `#{db.<instance>.scalar(sql|sqlFile, params?)}` requires exactly one row and one column and returns that typed cell; other cardinalities are `ERROR`.

An exact query call preserves its Java object in an assign Action:

```yaml
loadOrders:
  type: assign
  name: customerOrders
  expression: >-
    #{db.orders.query(
        sql='select id, status from orders where customer_id = ?',
        params=${EXEC.INPUT.customerParams}
    )}
```

Later Actions read `${EXEC.VARS.customerOrders.rows[0].STATUS}`. A non-scalar query object cannot be interpolated into surrounding text; assign it or use a DB Action. Expression updates, DDL, callable statements, and generic execute operations are rejected. Query failures make the containing Action and Case `ERROR`. Inline call evidence is retained below `EXEC.ACTIONS.<action>.DB.<instance>.<callId>`.

##### Result and lifecycle

Query `rows` is always a List, including zero or one row:

```yaml
success: true
operation: query
rowCount: 1
rows:
  - ID: A100
    STATUS: READY
affectedRows: null
transaction: {scope: case, onEnd: rollback, state: PENDING_ROLLBACK}
error: null
```

Updates use `rows: []`, `rowCount: 0`, and an integer `affectedRows`. Duplicate column labels are errors; add SQL aliases. Binary values use Base64, temporal values use portable strings, and LOB/cell/row/result limits fail instead of truncating.

Failures keep the same object shape with `success: false`, empty rows, zero row count, null affected rows, and a sanitized `error` containing `type`, `message`, `sqlState`, and `vendorCode`. Types are `CONNECTION_ERROR`, `DB_POOL_TIMEOUT`, `BIND_ERROR`, `SQL_ERROR`, `TIMEOUT`, `LIMIT_EXCEEDED`, `ROLLBACK_ONLY`, and `FINALIZE_ERROR`.

Connections belong to a dbhelper instance and execution thread, not a Case. ATT reuses one Connection per instance/thread. Case completion applies the configured transaction action to instances used by that Case but does not close them. Before the next Case, every open non-auto-commit Connection on that thread is rolled back for isolation. A rollback exception discards the old Connection and triggers reconnect without changing the new Case status; failure of the first subsequent DB operation becomes `ERROR`. In load mode, one HikariCP pool is created per dbhelper within the load-run owner; a Connection is borrowed lazily, exclusively held by one iteration thread, and returned after Case finalization or abort. `DB_POOL_TIMEOUT` is reported separately from SQL and SUT errors. Pool metrics expose active, idle, total, waiting, borrow wait duration, borrow timeouts, and borrow failures without exposing credentials.

For Case-scope transactions, any SQL/JDBC error marks the instance rollback-only. Later calls in that Case return `ERROR` without executing, and finalization rolls back. Final outcomes appear at the fixed `${CASE.DB.<instance>}` path. Connections close when their worker thread shuts down or the run ends. Vendor DDL may commit implicitly despite ATT transaction settings.

`CASE.DB` is framework-owned and cannot be renamed or supplied by Case data. It contains transaction finalization, not individual SQL results:

```yaml
CASE:
  DB:
    orders:
      scope: case
      onEnd: rollback
      state: ROLLED_BACK
      success: true
      error: null
```

The similar DB spellings serve different purposes:

| Form | Meaning |
|---|---|
| `#{db.orders.query(...)}` | lowercase callable namespace that executes a read query at the expression's evaluation point |
| `${CASE.DB.orders.state}` | fixed Case Context path containing the final transaction outcome after Case completion |
| `${DB...}` | invocation-local internal evidence scope; not a Case-wide “latest DB call” API |

ATT creates `CASE.DB` as an empty map when the Case starts, then adds one entry for each used dbhelper only after all Case Actions have finished and transaction finalization has run. Consequently, Actions in that Case cannot use `${CASE.DB.orders.state}` to make execution decisions. Read an operation's result from `${EXEC.ACTIONS.<actionId>.output.result}`; use `CASE.DB` in persisted Context, Case logs, reports, or other post-Case processing.

ATT bundles no JDBC driver. Put the driver and all dependencies in package-root `lib/` before starting ATT. JDBC service discovery is automatic; `connection.driverClass` supports legacy drivers. Jars load at JVM startup on a flat shared classpath.

#### IBM MQ helpers

MQ helpers are independent configuration files listed by the global `mqhelpers` array. The V1 schema requires a queue manager, host, port, and channel and supports optional environment-backed username/password values. The runtime adapter uses IBM MQ Classes for Java in TCP client mode. The default ATT build does not load the vendor client; add `com.ibm.mq:com.ibm.mq.allclient` through the Maven `ibm-mq` profile or place the resolved jar in the package `lib/` directory.

```yaml
# config/config.yaml
schemaVersion: att-config/v2.6
mqhelpers:
  - config/mqhelpers/orders.yaml
```

```yaml
# config/mqhelpers/orders.yaml
schemaVersion: att-mqhelper/v1.0
id: orders
name: Orders MQ
description: Order request and reply queues
connection:
  queueManager: QM1
  host: mq.example.internal
  port: 1414
  channel: APP.SVRCONN
  username: att
  password: "${ENV:MQ_PASSWORD}"
message: {ccsid: 1208, format: MQSTR, persistence: asQueue}
requestReply: {waitMs: 10000}
pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
evidence: {payload: metadata}
```

Only a primary `type: tool` call may invoke MQ:

```yaml
requestOrder:
  type: tool
  call: >-
    #{mq.orders.request(
      requestQueue='ORDER.REQUEST',
      replyQueue='ORDER.REPLY',
      file=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]},
      waitMs=5000
    )}
```

`send` accepts `queue` and `file`; `receive` accepts `queue`, optional `waitMs`, and optional `correlationId`; `request` accepts `requestQueue`, `replyQueue`, `file`, and optional `waitMs`. Payloads are read as exact bytes. Request PUT captures MsgId and GET matches CorrelId. Reason 2033 is a successful no-message result (`received: false` or `replyReceived: false`), so a required reply must be asserted explicitly. MQ queue handles remain invocation-scoped and use no syncpoint; in load mode the configured pool reuses bounded physical connections with exclusive leases, invalidating only failed connections and returning healthy no-message connections. `MQ_POOL_TIMEOUT` is distinct from MQ operation errors. Pool metrics expose active, idle, total, waiting, wait duration, creation/failure, replacement, and timeout counts without credentials. Reply bytes are written once below the Case output directory; structured evidence contains paths, lengths, IDs, status, duration, and safe reason metadata, never full payloads or credentials.

#### Call-backed Tools (V2.6)

Call-backed Tools give repeated DB operations a short business name while preserving first-class dbhelper behavior. They are façades, not a replacement for `type: db` or `#{db.<instance>.*}`.

Use `att-config/v2.6` and either define the Tool under global `tools`, or reference an `att-tool-group/v2.6` file:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.6
toolGroups:
  - config/tools/orders-db.yaml
dbhelpers:
  - config/dbhelpers/orders.yaml
```

A V2.6 descriptor has exactly one of `command` or `call`. The following group shows query, scalar, SQL-file, update, and cache variants:

```yaml
# config/tools/orders-db.yaml
schemaVersion: att-tool-group/v2.6
id: orders
name: Order database tools
description: Reusable typed order operations

tools:
  find:
    name: Find orders
    description: Find orders by customer and status
    call: "#{db.orders.query(sql='select order_id, status from orders where customer_id = ? and status = ?', params=[${input.customerId}, ${input.status}])}"
    cache:
      scope: case
    arguments:
      customerId: {name: Customer ID, description: Customer to query, required: true}
      status: {name: Status, description: Exact status, required: true}

  count:
    name: Count orders
    description: Return one typed count
    call: "#{db.orders.scalar(sql='select count(*) from orders where customer_id = ?', params=[${input.customerId}])}"
    cache:
      scope: db
    arguments:
      customerId: {name: Customer ID, description: Customer to count, required: true}

  findByDate:
    name: Find orders by date
    description: Use a package-contained rendered SQL file
    call: "#{db.orders.query(sqlFile='sql/orders-by-date.sql', params=[${input.customerId}, ${input.fromDate}])}"
    arguments:
      customerId: {name: Customer ID, description: Customer to query, required: true}
      fromDate: {name: From date, description: Inclusive lower bound, required: true}

  updateStatus:
    name: Update order status
    description: Update one order
    call: "#{db.orders.update(sql='update orders set status = ? where order_id = ?', params=[${input.status}, ${input.orderId}])}"
    arguments:
      orderId: {name: Order ID, description: Order to update, required: true}
      status: {name: Status, description: New status, required: true}
```

The definition reads typed values through canonical `${input.<argument>}`. `${TOOL.input.<argument>}` remains a legacy compatible alias and emits `CONTEXT_TOOL_INPUT_SHORTHAND`; bare input paths are rejected. `CASE`, `RUN`, `ACTIONS`, `DB`, and configured Tool chaining are not available. Case data must enter through the outer Tool call. Pure built-ins are allowed inside the definition, for example `params=[${input.customerId}, #{upper(${input.status})}]`.

The `call` value must be one exact `#{...}` expression targeting `db.<instance>.query`, `scalar`, `update`, or one pure built-in. A call-backed Tool cannot configure `output`, SSH, group `script`, or argument `argName|argNameMode`; those fields describe process execution and are rejected rather than ignored.

For a DB façade, `sqlFile` must be a static package-relative path, for example `sqlFile='sql/orders-by-date.sql'`; an input- or expression-derived path is invalid. ATT validates the file, includes its SHA-256 in `run.yaml` as a `tool-sql` input, and renders the file contents at invocation time. This keeps package provenance complete while still allowing declared Tool inputs and pure built-ins inside the SQL text.

##### Scenario: typed query in an expression

READ façades work in `assign`, `assert`, payload rendering, log fields, descriptions, and ordinary call arguments:

```yaml
loadOrders:
  type: assign
  name: customerOrders
  expression: >-
    #{orders.find(
        customerId=${EXEC.INPUT.customerId},
        status='OPEN'
    )}

checkFirstOrder:
  type: assert
  assert: "${EXEC.VARS.customerOrders.rowCount} > 0"
  expected: At least one OPEN order
  actual: "${EXEC.VARS.customerOrders.rowCount}"
```

The exact call returns the same Java result object as `db.orders.query`; it is not converted through text. Row fields remain accessible as `${EXEC.VARS.customerOrders.rows[0].STATUS}`.

##### Scenario: scalar assertion

```yaml
checkOrderCount:
  type: assert
  assert: "#{orders.count(customerId=${EXEC.INPUT.customerId})} >= 1"
  expected: Customer has an order
  actual: Count returned by orders.count
```

`scalar` still requires exactly one row and one column. Zero, multiple, or multi-column results are `ERROR`.

##### Scenario: primary Tool Action and saveAs

```yaml
queryOrders:
  type: tool
  call: "#{orders.find(customerId=${EXEC.INPUT.customerId}, status='OPEN')}"
  saveAs:
    path: db/open-orders.json
    format: json
    overwrite: false
  assert: "${output.result.rowCount} > 0"
```

Call-backed Tool `saveAs` uses the normal `path`, `format`, and `overwrite` fields. `format` is required and may be `text|json|yaml|xml`. There is no process stdout, so `raw` is invalid. Prefer JSON/YAML/XML for query or update objects; `text` is most useful for a scalar or intentionally textual built-in result.

##### Scenario: update façade

WRITE façades are permitted only as the primary call of `type: tool`:

```yaml
closeOrder:
  type: tool
  call: "#{orders.updateStatus(orderId=${EXEC.INPUT.orderId}, status='CLOSED')}"
  assert: "${output.result.affectedRows} == 1"
```

`#{orders.updateStatus(...)}` inside `assign`, `assert`, payload text, another Tool argument, or SQL is rejected during validation and again at runtime. DB errors make the Action and Case `ERROR`, not `FAIL`.

##### Scenario: wrap a pure built-in

Small domain-normalization helpers need no Java code:

```yaml
tools:
  normalizeStatus:
    name: Normalize status
    description: Trim and uppercase a status
    call: "#{upper(#{trim(${input.value})})}"
    cache:
      scope: case
    arguments:
      value: {name: Value, description: Status text, required: true}
```

Call it as `#{normalizeStatus(value=${EXEC.INPUT.status})}`. A pure-built-in façade may use Case cache but cannot use DB cache.

##### Cache scopes and stale-read contract

```yaml
cache:
  scope: case   # or db
```

Only successful query, scalar, or pure-built-in results are stored. Updates cannot declare cache. The key is a SHA-256 digest of the qualified Tool name plus a deterministic, type-sensitive representation of resolved input; reordering named arguments does not change it.

| Scope | Ownership and lifetime | Typical use |
|---|---|---|
| `case` | current `CaseRuntimeContext`; disappears with the Case | repeated lookup within one Case |
| `db` | dbhelper instance + executor thread; normally worker/suite lifetime | stable or reference data reused across Cases |

Cache is intentionally independent of JDBC lifecycle. DB update, commit, rollback, Case finalization, connection close, and automatic reconnect do **not** clear either cache. A DB-scope hit does not open or check the Connection. Consequently `db` scope can return stale data and should be enabled only when the package owner accepts that behavior. V2.6 has no TTL, size limit, persistence, or automatic coherence.

Evidence records `cache.scope`, the SHA-256 `cache.key`, and `cache.hit`. A miss has nested DB evidence; a hit does not create a DB invocation because JDBC did not run.

##### Timeout, retry, lifecycle, and evidence

Call-backed DB Tools retain the target dbhelper's read-only, transaction, result-limit, connection, and Case lifecycle rules. They now use the same Tool Action `timeoutMs` and ASSERTION/TIMEOUT retry contract as command-backed Tools. The effective JDBC query timeout is the shorter of the Tool attempt timeout and the dbhelper `statement.timeoutSeconds`; a retry-enabled Action bypasses call-backed cache so polling cannot reuse a stale value.

The Action keeps a normal `TOOL` wrapper with `implementation: call`, input, typed output, status, duration, and cache details. A DB cache miss also produces the ordinary Action `DB` evidence. Fields that only exist for a process—`command`, `logicalArgv`, `argv`, `stdout`, `stderr`, `rawOutput`, and `exitCode`—are absent.

Choose the lightest authoring form:

| Need | Recommended form |
|---|---|
| one-off query/update with visible SQL | `type: db` |
| one-off read inside an expression | `#{db.orders.query|scalar(...)}` |
| repeated operation with a business name and stable arguments | call-backed Tool |
| intentional in-memory reuse | call-backed Tool with explicit cache |
| external executable, SSH, argv, or stdout parser | command-backed Tool |

The complete normative contract is [V2.6 Call-backed Tool System Design](02_System_Design_V2.6.md).

#### Command processing

ATT normalizes every command to an argv template list. A scalar command is tokenized once with the legacy tokenizer. A YAML list is already normalized: each item is exactly one argv value and is never tokenized. An ordinary declared scalar argument therefore remains atomic regardless of spaces, quotes, backslashes, leading dashes, or shell-like characters in its value. A typed List supplied by a Tool call expands into zero or more argv values only at an exact complete-token placeholder. Resolved values are never tokenized again. ATT does not invoke a local shell.

V2.3.2 starts every local tool process with `${EXEC.OUTPUT_DIR}` as its current working directory. A configured executable beginning with `./` or `../` remains package-relative: ATT resolves that first argv value against the package root before launch. A bare executable name still uses `PATH`. All other relative argv paths are intentionally interpreted by the tool from the Case output directory. This makes relative tool artifacts part of the Case output without requiring every action to build an absolute path.

ATT supplies and owns these local-process environment variables:

| Variable | Contract |
|---|---|
| `ATT_ROOT_DIR` | normalized absolute ATT package root |
| `ATT_CASE_OUTPUT_DIR` | normalized absolute current Case output directory; identical to `${EXEC.OUTPUT_DIR}` at process launch |

Inherited values with these names are replaced. POSIX scripts use forms such as `$ATT_CASE_OUTPUT_DIR`; Windows batch scripts use `%ATT_CASE_OUTPUT_DIR%`.

| Command text | Effect |
|---|---|
| whitespace outside quotes | separates argv elements |
| `'...'` or `"..."` | groups fixed text as one argv element; quotes are removed |
| backslash outside single quotes | escapes the next character for ATT tokenization |
| `|`, `>`, `<`, `;`, `&`, `$`, `(`, `)`, `*`, `?` | literal characters, not shell operators |
| unmatched quote or final escape | error |

This configuration:

```yaml
command: "./tools/send.sh '${input.requestFile}' --label 'Payment regression'"
```

produces these logical arguments:

```text
./tools/send.sh
<request-file-value>
--label
Payment regression
```

The text `status|PENDING` remains one literal argument and `>result` does not redirect output. Quotes in the static template group template text only; they are not an escaping requirement for resolved values.

The recommended equivalent is clearer:

```yaml
command:
  - ./tools/send.sh
  - "${input.requestFile}"
  - --label
  - Payment regression
```

An argument may define an optional atomic `argName` token. Its placeholder determines the insertion position and must occupy exactly one complete command token when `argName` is non-empty:

```yaml
command:
  - ./tools/send.sh
  - "${input.requestFile}"
  - "${input.reference}"
arguments:
  requestFile: {name: Request File, description: Input file, required: true}
  reference: {name: Reference, description: Optional reference, required: false, argName: --reference}
```

With `reference='REF 123'`, the final portion of logical argv is `--reference`, `REF 123`; the value remains one atomic argument. If the optional value is missing or normalizes to blank, neither token is emitted. Omitting `argName` or setting `argName: ''` makes the argument positional: an exact-token placeholder emits only its value, or emits no argv when the optional value is blank. An embedded placeholder such as `--reference=${reference}` remains one ordinary rendered token and cannot receive a List. For typed List values, `argNameMode` controls whether the name is emitted `once` (the default) before the complete list or `repeat` before every value; it has no output effect for positional arguments.

Prefer the canonical declared-argument placeholder `${input.keywords}`. `${TOOL.input.keywords}` remains a legacy compatible alias and `${keywords}` remains deprecated shorthand; both must exactly match one declared argument and produce `CONTEXT_TOOL_INPUT_SHORTHAND`. Tools write their raw result to stdout and diagnostics to stderr; ATT records input/stdout/stderr in the case log.

Global tool commands may reference only their declared arguments. They cannot reference `${EXEC.INPUT...}`, `${EXEC.ACTIONS...}`, or other runtime Context scopes. Pass runtime data explicitly in the action call, then reference that declared argument in the command. This keeps the global tool independent and its dependencies statically validateable.

#### SSH execution

Global tools and each tool group may define one execution target at their own root:

```yaml
ssh:
  host: tools.example.internal
  user: att
  port: 22
  identityFile: /secure/keys/att_ed25519
```

Root `ssh` applies only to inline global tools; a group uses only its own `ssh` and does not inherit the root value. Host/user are required, port defaults to 22, and the key is optional. Password fields are unsupported. ATT prefers local OpenSSH with `BatchMode=yes` and `StrictHostKeyChecking=yes`. If `PATH` has no executable `ssh`, ATT warns that it will use the bundled mwiede/jsch Java library. The fallback uses a strict `~/.ssh/known_hosts`, does not inherit the OpenSSH agent or `~/.ssh/config`, and normally requires `identityFile`. Both transports safely single-quote the logical argv into one POSIX remote command string. Remote connectivity and executable presence cannot be proven by package validation. SSH stdout/stderr/status/timeout/retry/assert/saveAs behavior matches local tools, and evidence records `transport: openssh|mwiede/jsch`.

The Case output working-directory and two environment-variable rules apply to local tool processes only. ATT does not prepend `cd` or inject local filesystem paths into an SSH remote command because `${EXEC.OUTPUT_DIR}` and the package root have no defined remote mappings. The remote process uses the SSH account's default directory. Pass a shared-filesystem or remote directory as an explicitly declared tool argument when required.

#### Input, output, timeout, and status

Available command placeholders are:

| Placeholder | Meaning |
|---|---|
| `${input.argument}` | Canonical Tool-local reference to a declared argument key |
| `${TOOL.input.argument}` | Legacy compatible alias; `att validate` emits `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${argument}` | Deprecated compatibility shorthand; warning when it uniquely matches a declared argument |

The shorthand is intentionally narrow: `${argument}` is accepted only when the
name is exactly one declared argument of this Tool. `att validate` emits
`CONTEXT_TOOL_INPUT_SHORTHAND` with the exact `${input.argument}` replacement.
An undeclared or ambiguous shorthand is an error. This rule is identical for
command-backed and call-backed Tools. Canonical documentation and new
definitions should use `${input.argument}`.

ATT parses stdout according to the Tool configuration `output`, which may be `txt`, `yaml`, `json`, or `xml` and defaults to `txt`. A Tool action may use the common object-shaped `saveAs` to persist exact stdout bytes (`format: raw`) or its parsed typed result (`text|json|yaml|xml`) in the Case artifact directory. A primary built-in has no stdout, so it supports only `text|json|yaml|xml`. See [Action `saveAs`](#action-saveas) for formats, rendering, containment, overwrite, and retry behaviour. Without `saveAs`, ATT creates no dedicated Tool-output file, while input, argv, stdout, stderr, parsed result, exit code, and retry evidence remain in the persisted Case evidence.

Timeout, launch/process I/O failure, or structured-output parse failure is ERROR and cannot be overridden by an assertion. Otherwise a tool action's `assert` decides PASS/FAIL. If no assertion is configured, operational completion is PASS even when the process exit code is non-zero. The exit code remains in `action.output.exitCode`, so templates that require zero must say so explicitly. Command, inputs, stdout, stderr, raw output, duration, exit code, parsed `output.result`, and assertion details are retained as evidence.

Timeout precedence is:

```text
tool action timeoutMs → Tool descriptor timeoutMs → global timeoutMs → 10000 ms
```

Every configured timeout is an integer from 1 to 3600000 milliseconds.

Retry is valid only on `type: tool` Actions and has no global, Tool, Template, stage, or sidecar default:

```yaml
retry:
  maxAttempts: 3
  intervalMs: 1000
  retryOn: [ASSERTION, TIMEOUT]
```

`maxAttempts` includes the first attempt and is 2–10; `intervalMs` is 0–3600000. `ASSERTION` requires an Action `assert`: ATT evaluates it after every normal result and retries only when it is false. `TIMEOUT` retries only an attempt timeout. Exit code has no retry category and remains available at `${output.exitCode}` for the assertion. Configuration, argument, I/O, parse, non-timeout DB, and assertion-evaluation errors are not retried.

#### Post-invocation evidence collectors

A Tool Action may declare an `evidence` map whose keys are collector IDs. A collector requires a `call` and may set an independent `timeoutMs` and `onFailure: continue|stop`:

```yaml
invokeApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.INPUT.requestFile})}"
  evidence:
    queueState:
      call: "#{readQueueState(queue=${EXEC.INPUT.queue})}"
      timeoutMs: 3000
      onFailure: continue
  assert: "${output.result.status} == 'SUCCESS'"
```

ATT runs the primary attempt first, then collectors in declaration order, then the primary assertion. During collection `${output.result}` is the primary result; collector output never replaces it. The complete record is stored at `output.attempts[n].evidence.<collectorId>` and includes collector ID, attempt, invocation ID, status, success, result, duration, and error details where applicable. A primary assertion retry reruns the primary and every collector. `continue` records a collector failure while preserving the primary assertion result; `stop` makes the Action `ERROR` and skips assertion evaluation. Collector calls may target permitted built-ins, configured Tools, or read-only call-backed Tool façades. Direct DB and MQ helper calls remain restricted to their primary Action scopes.

### 3.4 Running Tests

#### Validate first

```sh
./att.sh validate --package
```

Package mode is the default and checks global configuration, every discovered workbook/sidecar, configured sheets and rows, all templates including unreferenced ones, expressions, tools, paths, and package integrity. It never invokes external tools and is the required release gate.

```sh
./att.sh validate --selected --case payment.payment.TC001
```

Selected mode checks only explicitly selected cases and their dependency closure. It is useful for fast authoring feedback and reports that unselected content was not checked. `run` always performs selected-scope validation for its immutable execution plan.

#### Select cases

| Option | Meaning |
|---|---|
| `--all` | Discover all workbook/sidecar pairs recursively below `testcase.root` |
| `--suite <xlsx>` | Select one workbook; repeatable |
| `--suite-dir <dir>` | Discover workbooks below another directory |
| `--case <workbookId.groupId.rowCaseId>` | Select one complete Case ID |
| `--tag <tag>` | Include cases with any requested tag |
| `--exclude-tag <tag>` | Exclude matching cases after inclusion filters |

An empty selection is an error. `--rerun-failed` is itself a valid selection and reads FAIL/ERROR Case IDs from the latest completed persisted run. Additional `--case`, `--tag`, and `--exclude-tag` filters narrow that set. Missing history, no prior FAIL/ERROR cases, or no currently discoverable case matching the saved IDs is a command error. The current workbook, sidecar, testcase XML snapshot, template, and tool definitions are validated and executed; ATT does not replay old run inputs.

#### Execute

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --tag smoke
./att.sh run --all --case payment.payment.TC001 --run-id SIT-001
./att.sh run --rerun-failed
./att.sh run --rerun-failed --tag payment
./att.sh run --all --dry-run
./att.sh run --suite testcase/payment.xlsx --update-snapshot
```

`--dry-run` validates/plans and records selected cases as SKIPPED without invoking tools. `--fail-fast` stops scheduling further cases after the first FAIL or ERROR. `--output-dir` overrides the output root for one command.

`--update-snapshot` is valid only for `run`. It explicitly creates or atomically replaces changed canonical XML snapshots for the complete workbooks selected by that run before validation and output-directory creation; byte-identical files retain their bytes and modification time. Workbook preparation completes before any selected XML is replaced. A snapshot symlink is rejected. A later per-file I/O failure reports earlier completed updates, and concurrent snapshot generation/update phases for the same package are serialized. With `--format json`, successful update notices go to stderr so stdout remains one JSON document; `--quiet` suppresses them. Combining it with `--dry-run` still authorizes the XML update while testcase tools remain disabled.

Human `run` enables run/suite/Case/stage/action lifecycle progress and mirrors every complete Case-log block to the console by default, including template/tool input, logical and executed argv, stdout, stderr, payload, and action evidence. `--verbose` remains accepted for compatibility; `--quiet` suppresses this default output, while explicitly combining `--verbose --quiet` remains invalid. Non-quiet output can contain secrets or personal data and must be used only in an appropriately protected terminal.

#### Result and exit code

| Highest result present | Run status | Exit code |
|---|---|---:|
| ERROR | ERROR | 3 |
| INVALID without ERROR | INVALID | 2 |
| FAIL without ERROR/INVALID | FAIL | 1 |
| At least one PASS and only PASS/SKIPPED | PASS | 0 |
| All selected cases SKIPPED | SKIPPED | 0 |

Assertion false is FAIL. Expression evaluation, process, timeout, parsing, I/O, configuration, and validation failures are ERROR or INVALID according to their phase. A run containing both FAIL and ERROR exits 3.

### 3.5 Reports

A completed run is published at `<outputDirectory>/<RunID>/`:

```text
output/<RunID>/
├── run.yaml
├── events.jsonl
├── workbooks/
├── ci/
│   ├── summary.json
│   └── junit.xml
├── report/
│   ├── index.html
│   └── junit.html
└── <workbookId>.<groupId>.<rowCaseId>/
    ├── case.yaml
    ├── <case>.log
    └── <stage>/<action>/
```

Open `report/index.html` directly from disk. Groups are aggregated by `workbookId.groupId`; the UI labels the logical group as Sheet because it maps to a physical workbook sheet. In Cases, combine Workbook, Sheet, and Status selectors with text search over workbook ID, group ID, full Case ID, and tags. Click any Cases heading to toggle ascending/descending sorting; Duration sorts numerically.

Each expanded case shows its full Case ID, name, status, duration, Expected and Actual results, action-result rows, the detailed execution log, and explicit links to the `.log` and structured `case.yaml` artifacts. The HTML does not duplicate the complete persisted Stage/Template/Action/Tool/DB tree inline; open `case.yaml` when the structured final runtime state is required. Action rows expose final Description independently. Only `type: assert` actions contribute to case Expected/Actual: Expected appends each non-blank final description and validation-time `expected`, while Actual appends each non-blank runtime `actual`. Values follow action order and use exactly one LF between non-blank entries. HTML escapes and pre-wraps the text, Excel preserves LF with wrapping, JSON uses escaped `\n`, and JUnit retains the same line boundaries.

`--ci-output junit,json` requests JUnit XML, JUnit HTML, and JSON summary:

- `<run>/ci/junit.xml`
- `<run>/report/junit.html`
- `<run>/ci/summary.json`

JUnit HTML is a human-readable projection of the same completed summary as JUnit XML. It is not a second aggregation.

Regenerate reports from persisted run evidence with:

```sh
./att.sh report --run-id SIT-001
```

## 04 Cookbook

This chapter starts from a task you want to perform and shows the corresponding ATT pattern.

### Run rollback after failure and cleanup every time

```yaml
stages:
  - {key: invoke, template: 執行模板, required: true}
  - {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
  - {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

Rollback runs only after an earlier failure. Cleanup runs regardless of the earlier result. A rollback or cleanup failure is still retained in final aggregation.

### Override result-workbook column labels

```yaml
report:
  columns:
    result: 測試結果
    durationMs: 耗時毫秒
    expectedResult: 預期結果
    actualResult: 實際結果
    caseLog: 案例日誌
    reportLink: 詳細報告
    runTime: 執行時間
```

This changes copied-workbook labels only. It does not rename Context keys, HTML fields, or CI schema properties.

### Parse JSON tool output

Configure `output: json`, then use normal map/list navigation:

```yaml
assertStatus:
  type: assert
  description: API status is successful
  assert: "${EXEC.ACTIONS.callApi.output.result.status} == 'SUCCESS'"
  expected: SUCCESS
  actual: "${EXEC.ACTIONS.callApi.output.result.status}"
```

JSON duplicate object keys, malformed JSON, or a declared JSON output that cannot be parsed are ERROR even when the process exits 0.

### Access XML attributes, text, and repeated elements

ATT converts XML into a stable map/list tree:

```xml
<Response requestId="R-100">
  <Status code="00">SUCCESS</Status>
  <Messages>
    <Message severity="INFO">accepted</Message>
    <Message severity="WARN">review later</Message>
  </Messages>
</Response>
```

```yaml
name: Response
attributes:
  requestId: R-100
Status:
  attributes: {code: "00"}
  text: SUCCESS
Messages:
  Message:
    - attributes: {severity: INFO}
      text: accepted
    - attributes: {severity: WARN}
      text: review later
```

Only repeated siblings are arrays, so indexes are used only for repeated nodes:

```yaml
assertRootAttribute:
  type: assert
  assert: "${EXEC.ACTIONS.callApi.output.result.attributes.requestId} == 'R-100'"
assertStatusText:
  type: assert
  assert: "${EXEC.ACTIONS.callApi.output.result.Status.text} == 'SUCCESS'"
assertStatusCode:
  type: assert
  assert: "${EXEC.ACTIONS.callApi.output.result.Status.attributes.code} == '00'"
assertSecondMessage:
  type: assert
  assert: "${EXEC.ACTIONS.callApi.output.result.Messages.Message[1].text} == 'review later'"
assertSecondSeverity:
  type: assert
  assert: "${EXEC.ACTIONS.callApi.output.result.Messages.Message[1].attributes.severity} == 'WARN'"
```

With `xml.namespaceMode: ignore`, keys use local names. With `preserve`, namespace-aware names use Clark notation such as `{urn:payment}Status`. Keys containing braces, colons, dots, or spaces use quoted brackets, for example `${EXEC.ACTIONS.callApi.output.result['{urn:payment}Status'].text}`. XML DTD, external entities, XInclude, and external resources are disabled.

A leaf without attributes becomes `ElementName: text`. Any element with attributes retains their names under `attributes`; therefore `<Item id="123"/>` becomes `Item: {attributes: {id: "123"}}`. Repeated same-name siblings become a list in source order; a singleton stays scalar/map. Empty elements become an empty string unless they have attributes. Text and CDATA are concatenated, trimmed at the boundary, and stored as `text` when child elements also exist. Comments and processing instructions are ignored. With namespace preservation, element and non-namespace attribute names use Clark notation, keeping equal local names from different namespaces distinct.

### Pass a list as separate process arguments

V2.6 call sites pass typed YAML arrays directly. The following example shows both `argNameMode` values without separator configuration:

```yaml
tools:
  grepFromAppLogs:
    name: Grep application logs
    description: Search one or more keywords and log levels
    command:
      - ./tools/grep_from_app_logs.sh
      - "${input.logFile}"
      - "${input.keywords}"
      - "${input.levels}"
    output: yaml
    arguments:
      logFile: {name: Log File, description: Source log, required: true}
      keywords: {name: Keywords, description: Ordered keyword array, required: true, argName: --keyword, argNameMode: repeat}
      levels: {name: Levels, description: Ordered level array, required: false, argName: --levels, argNameMode: once}
```

```yaml
grepLogs:
  type: tool
  call: "#{grepFromAppLogs(logFile=${EXEC.ACTIONS.getLogs.output.targetFiles[0]}, keywords=['PAYMENT', 'POSTED'], levels=['ERROR', 'WARN'])}"
```

The resulting tail of logical argv is `--keyword`, `PAYMENT`, `--keyword`, `POSTED`, `--levels`, `ERROR`, `WARN`. Explicit `repeat` repeats `--keyword` for every keyword. `once` is the default and may be omitted; it emits `--levels` only once before the complete levels list. Each typed List expands independently at its own command-placeholder position.

#### Linux Bash parsing examples

The following focused snippets assume any earlier positional arguments have already been consumed. For `argNameMode: repeat`, each option occurrence owns exactly one following value. A Bash script can append every occurrence to an array, including values that begin with `-` or `--`:

```bash
#!/usr/bin/env bash
set -euo pipefail

keywords=()
while (($#)); do
  case "$1" in
    --keyword)
      [[ $# -ge 2 ]] || { echo "--keyword requires a value" >&2; exit 2; }
      keywords+=("$2")
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

for keyword in "${keywords[@]}"; do
  printf 'keyword=%s\n' "$keyword"
done
```

Given `--keyword PAYMENT --keyword POSTED`, the array contains `PAYMENT` and `POSTED` in order.

For `argNameMode: once`, one option introduces all following values. The clearest contract is to place that multi-value argument last in the tool command, as `levels` is in the preceding configuration, and consume the remaining argv:

```bash
#!/usr/bin/env bash
set -euo pipefail

levels=()
while (($#)); do
  case "$1" in
    --levels)
      shift
      levels=("$@")
      break
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

for level in "${levels[@]}"; do
  printf 'level=%s\n' "$level"
done
```

Given `--levels ERROR WARN`, the array contains `ERROR` and `WARN`. If a `once` list is not last, the script needs an unambiguous boundary defined by its own protocol, such as a fixed item count or an explicit terminator argument. Do not infer the boundary merely from the next value beginning with `--`: ATT preserves leading dashes as data, so a legitimate list value may also begin with `--`. Use `repeat` when each option-value pair must be independently parseable.

String items are normalized individually; blank markers become empty strings, while an explicitly empty array remains empty. A required empty List fails before expansion. Spaces, quotes, backslashes, leading dashes, and `|><` inside an item remain literal data. Every List placeholder must occupy one complete static command token. Nested Lists and maps are rejected as argv items. An empty optional List emits neither its `argName` nor values. Current `att-config/v2.6` and `att-tool-group/v2.6` schemas reject `delimit`; legacy configuration schemas retain their historical split behavior only for read compatibility.

### Retry assertion polling or timeout

Retry belongs to a tool action, not to a workflow or arbitrary stage:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]})}"
  timeoutMs: 30000
  assert: "${output.result.status} == 'COMPLETED'"
  retry:
    maxAttempts: 3
    intervalMs: 1000
    retryOn: [ASSERTION, TIMEOUT]
```

`maxAttempts` includes the first attempt. ATT evaluates the Tool Action assertion after every normal result; `ASSERTION` retries a false result, while `TIMEOUT` independently permits retry after the attempt timeout. Exit codes are ordinary evidence and can be included in the same assertion. The retry block has no defaults outside the Action.

Each attempt is recorded directly in the case log/action record; no `attempt-001` directory is created. A later successful attempt makes the action PASS while retaining earlier evidence; exhausted attempts produce ERROR. Only retry operations that are safe to repeat.

## 05 CLI Reference

### Commands

| Command | Purpose | External tools? |
|---|---|---:|
| `help` | Show syntax and options; default with no command | No |
| `version` | Print ATT version | No |
| `validate` | Validate package or selected dependency closure | No |
| `snapshot` | Generate same-basename canonical testcase XML | No |
| `run` | Validate and execute selected cases | Yes, except dry-run |
| `debug` | Execute one Template, Flow, or Tool with a debug sidecar | Yes |
| `docs` | Generate searchable package documentation | No |
| `report` | Regenerate reports for a completed run | No |
| `build` | Archive the latest completed run | No |
| `clean` | Remove documented ATT-generated output | No |

### Command syntax

The tables use the Linux/macOS launcher `./att.sh`. On Windows, use `att.bat` with the same command and options. `att.bat snapshot`, `att.bat validate`, and `att.bat docs` do not invoke configured testcase tools. Windows validation checks `.sh` file existence and path safety, skips POSIX launch/executable compatibility, and emits one warning listing affected tools; a validation PASS does not prove those scripts can run on Windows. Provide and test Windows-native equivalents before `run`. Binary releases require Java 8+. Source-tree `att.bat` compiles with Maven when available and otherwise requires existing `target\classes`.

| Syntax | Notes |
|---|---|
| `./att.sh` or `./att.sh help` | Show help |
| `./att.sh version` | Print version |
| `./att.sh snapshot` | Generate snapshots recursively below `testcase.root`; equivalent to `--all` when no selector is supplied |
| `./att.sh snapshot --suite <xlsx>` | Generate one same-basename XML snapshot |
| `./att.sh snapshot --all` | Generate snapshots recursively below `testcase.root` |
| `./att.sh snapshot --suite-dir <dir>` | Generate snapshots recursively below a directory |
| `./att.sh validate --package` | Validate complete package; default scope |
| `./att.sh validate --selected <selection>` | Validate selected dependency closure |
| `./att.sh validate --package --format json` | Emit one validation JSON document to stdout |
| `./att.sh run --all` | Run all discovered cases |
| `./att.sh run --suite <xlsx>` | Run one workbook; repeatable |
| `./att.sh run --suite-dir <dir>` | Discover workbooks below a directory |
| `./att.sh run <selection> --case <workbookId.groupId.rowCaseId>` | Include one full Case ID |
| `./att.sh run <selection> --tag <tag>` | Include a tag |
| `./att.sh run <selection> --exclude-tag <tag>` | Exclude a tag |
| `./att.sh run <selection> --dry-run` | Validate/plan without tool execution |
| `./att.sh run <selection> --update-snapshot` | Explicitly refresh changed complete-workbook snapshots before validation |
| `./att.sh run <selection> --fail-fast` | Stop scheduling after first FAIL/ERROR |
| `./att.sh run <selection> --rerun-failed` | Select prior FAIL/ERROR cases |
| `./att.sh run <selection> --run-id <id>` | Set final run directory name |
| `./att.sh run <selection> --output-dir <dir>` | Override output root |
| `./att.sh run <selection> --ci-output junit,json` | Write CI XML/JSON plus JUnit HTML |
| `./att.sh run <selection> --profile` | Write per-run `performance.json` timings and counters |
| `./att.sh run <selection> --queue` | Wait for another ATT process using the same output root |
| `./att.sh run <selection> --allow-parallel-runs` | Allow concurrent ATT processes; does not parallelize Cases in this run |
| `./att.sh run <selection> --format json` | Emit machine-readable summary |
| `./att.sh run <selection> --quiet` | Suppress the default lifecycle and Case-log output |
| `./att.sh run <selection> --verbose` | Explicitly retain the default lifecycle progress and complete Case-log mirroring; accepted for compatibility |
| `./att.sh debug template <id>` | Execute one Template; auto-discover `<template-dir>/debug.yaml` |
| `./att.sh debug flow <id>` | Execute one canonical Flow; auto-discover `<flow-dir>/debug.yaml` |
| `./att.sh debug tool <id>` | Execute one Tool; auto-discover `config/tools/<group>.debug.yaml` |
| `./att.sh debug <type> <id> --input <file>` | Override the target's auto-discovered debug input |
| `./att.sh debug <type> <id> --output-dir <dir>` | Isolate debug output below `<dir>/debug/<debugId>/` |
| `./att.sh debug <type> <id> --format json` | Emit a compact machine-readable console summary; full evidence remains in `result.yaml` |
| `./att.sh report --run-id <id>` | Regenerate `report/index.html` and `report/junit.html` |
| `./att.sh docs` | Generate `build/docs/index.html` |
| `./att.sh build` | Archive latest completed run in `build/` |
| `./att.sh clean` | Remove documented generated outputs |

Options are command-specific. Unknown commands/options and missing option values are errors. `--package` and `--selected` are mutually exclusive. Selected validation and run require an explicit selection.

### Standalone debug inputs and outputs

Debug input files use `att-debug/v1.0`. `case` values become synthetic `CASE` data, `stage.key` and `stage.values` declare the one debug stage, and `inputs` is adapted directly into canonical `EXEC.INPUT.*`. For compatibility, `${CASE.inputs.<field>}` remains a read-only view when no business field is literally named `inputs`; it is not duplicated below `EXEC.INPUT`. Tool arguments come from the root `arguments` map or `tools.<localKey>.arguments`. An explicit `--input` always wins over auto-discovery.

Before execution ATT validates only the selected Template or Flow dependency closure, or the selected Tool definition. It does not require unrelated workbook snapshots or unrelated malformed Template descriptors to pass. The selected target still uses the normal Template/Flow/Tool runner, including Context resolution, Flow nesting, Tool retry/timeout, evidence, saveAs, DB finalization, and Case-log behavior.

#### Configuration examples

The following examples show the supported placement of debug values. Every file is a complete `att-debug/v1.0` document.

Template sidecar (`templates/PAYMENT_INVOKE/debug.yaml`):

```yaml
schemaVersion: att-debug/v1.0
case:
  caseName: PAYMENT debug
  amount: 100
  environment: SIT
stage:
  key: invoke
  values:
    channel: WEB
    sourceRef: SRC-001
```

Run it with `./att.sh debug template PAYMENT_INVOKE`. Template expressions should prefer `${EXEC.INPUT.amount}`, `${EXEC.INPUT.environment}`, and the current Stage value `${EXEC.INPUT.channel}`; the current Stage's `values` overlay Case-level input for that Stage, with the Stage value winning on collisions. The corresponding `CASE.*` paths remain compatibility aliases, while `CASE.STAGES.*` is retained only as the legacy execution/evidence view.

Flow sidecar (`templates/flows/common/compose/debug.yaml`):

```yaml
schemaVersion: att-debug/v1.0
case:
  caseName: Compose debug
  traceId: TRACE-001
stage:
  key: DEBUG
  values:
    mode: SIT
inputs:
  source: payment
  suffix: -debug
```

Run it with `./att.sh debug flow common.compose.v1`. Flow inputs are available as `${EXEC.INPUT.source}` and, when there is no same-named Case value, as the compatibility alias `${EXEC.INPUT.source}`.

Grouped Tool sidecar (`config/tools/fpp.debug.yaml` for `fpp.invokeApi`):

```yaml
schemaVersion: att-debug/v1.0
case:
  RefNo: REF001
tools:
  invokeApi:
    arguments:
      requestId: REF001
      requestType: PAYMENT
      requestFile: /tmp/payment-request.xml
      apiLogPath: /tmp/payment-api.log
```

Run it with `./att.sh debug tool fpp.invokeApi`. The `invokeApi` key is the group-local Tool key. Values must be scalar or list values accepted by the Tool descriptor; map literals are not supported by the standalone Tool adapter.

Ungrouped Tool sidecar (`config/tools/invokePaymentApi.debug.yaml`):

```yaml
schemaVersion: att-debug/v1.0
arguments:
  requestFile: /tmp/payment-request.xml
  environment: SIT
```

Run it with `./att.sh debug tool invokePaymentApi`. For an ungrouped Tool, root `arguments` is passed directly; it is not wrapped under `tools`.

An explicit file overrides sidecar discovery, which is useful for temporary values in CI or local diagnosis:

```sh
./att.sh debug template PAYMENT_INVOKE --input /tmp/payment-debug.yaml \
  --output-dir /tmp/att-debug --format json
```

The selected input is validated before execution. Missing files, invalid schema, unknown or missing Tool arguments, and other input/configuration errors return exit code `2`. Framework-owned values such as `EXEC.ID`, `EXEC.MODE`, `EXEC.OUTPUT_DIR`, `EXEC.VARS`, and `EXEC.ACTIONS`, together with the corresponding `CASE.*`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, and `DB.*` aliases, remain authoritative even if they appear in the input `case` map. `EXEC.STAGES` is not a 3.4.2 Context node; Stage history remains in the legacy `CASE.STAGES` evidence view.

Each invocation writes:

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
    └── case.yaml
```

`result.yaml` contains the target, status, exit code, duration, input path, Case ID, action results, diagnostic (when present), and evidence locations. Synthetic framework-owned fields always win over same-named values in `case`; debug inputs cannot replace `CASE.caseId`, `CASE.workbookId`, `CASE.groupId`, `CASE.rowCaseId`, `CASE.outputDirectory`, the legacy `CASE.STAGES` evidence view, `CASE.DB`, `CASE.VARS`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, or `DB.*`. Debug output is independent of ordinary `output/latest-run.yaml` and report lifecycle.

For `validate --format json`, stdout contains exactly one JSON document; progress and human diagnostics go to stderr.

### Exit codes

| Code | Meaning |
|---:|---|
| 0 | Command/run succeeded without FAIL, ERROR, or INVALID |
| 1 | One or more FAIL results and no ERROR/INVALID |
| 2 | CLI/configuration/validation/INVALID failure |
| 3 | One or more ERROR results or unrecoverable runtime failure |

## 06 Configuration Reference

This chapter is the authoritative reading reference for author-authored configuration. The files below [`schemas/`](../schemas/) remain the machine-readable contract. Schema validation runs before cross-field and filesystem validation.

### Configuration layers and precedence

| Layer | Source | Owns |
|---|---|---|
| Global | `config/config.yaml` | output/environment/runtime defaults, template root, reports, XML mode, global tools, group paths, MQ helper paths, optional global SSH |
| Tool group | configured YAML path | group identity, optional script/SSH, grouped tools |
| Dbhelper | configured `dbhelpers` YAML path | one database identity, connection, statement timeout, transaction, limits, and evidence policy |
| Workbook | `<workbook>.yaml` | Excel mapping, stages, workbook labels |
| Template | `template.yaml` | template identity and ordered actions |
| CLI | command options | selection, Run ID, output override, presentation, CI formats |

Tool Action timeout overrides Tool descriptor timeout, which overrides global timeout. Sidecars, stages, and Templates do not own timeout/retry defaults. For call-backed DB Tools the dbhelper statement timeout remains a backend ceiling. CLI `--output-dir` and `--run-id` override their applicable defaults for one command. A field valid in one layer is still rejected if placed in another layer.

### Schema catalog

V3.4 adds post-invocation Tool evidence and the independent MQ helper schema. V2.6.2 adds `att-template/v2.6` and `att-sidecar/v2.2` for the unified Tool Action policy. The dbhelper schema remains V2.5.

| Artifact | Schema identifier | Formal definition |
|---|---|---|
| Debug input | `att-debug/v1.0` | [att-debug-v1.0.schema.json](../schemas/att-debug-v1.0.schema.json) |
| Global configuration | `att-config/v2.6` | [att-config-v2.6.schema.json](../schemas/att-config-v2.6.schema.json) |
| Legacy global configuration (read compatibility) | `att-config/v2.1`, `att-config/v2.2`, `att-config/v2.5` | [att-config-v2.5.schema.json](../schemas/att-config-v2.5.schema.json) |
| Dbhelper instance | `att-dbhelper/v2.5` | [att-dbhelper-v2.5.schema.json](../schemas/att-dbhelper-v2.5.schema.json) |
| MQ helper instance | `att-mqhelper/v1.0` | [att-mqhelper-v1.0.schema.json](../schemas/att-mqhelper-v1.0.schema.json) |
| Tool group | `att-tool-group/v2.6` | [att-tool-group-v2.6.schema.json](../schemas/att-tool-group-v2.6.schema.json) |
| Legacy Tool group (read compatibility) | `att-tool-group/v2.2` | [att-tool-group-v2.2.schema.json](../schemas/att-tool-group-v2.2.schema.json) |
| Workbook sidecar | `att-sidecar/v2.2` | [att-sidecar-v2.2.schema.json](../schemas/att-sidecar-v2.2.schema.json) |
| Legacy workbook sidecar (without timeout) | `att-sidecar/v2.1` | [att-sidecar-v2.1.schema.json](../schemas/att-sidecar-v2.1.schema.json) |
| Template descriptor | `att-template/v2.6` | [att-template-v2.6.schema.json](../schemas/att-template-v2.6.schema.json) |
| Legacy template descriptor (read compatibility) | `att-template/v2.5`, `att-template/v2.3` | [att-template-v2.5.schema.json](../schemas/att-template-v2.5.schema.json) |
| Run manifest | `att-run/v2.1` | [att-run-v2.1.schema.json](../schemas/att-run-v2.1.schema.json) |
| Validation JSON | `att-validation/v2.1` | [att-validation-v2.1.schema.json](../schemas/att-validation-v2.1.schema.json) |
| CI summary | `att-ci-summary/v2.1` | [att-ci-summary-v2.1.schema.json](../schemas/att-ci-summary-v2.1.schema.json) |
| JUnit XML | XSD | [att-junit-v2.1.xsd](../schemas/att-junit-v2.1.xsd) |
| Diagnostic codes | `att-diagnostic-catalog/v2.1` | [diagnostic-codes.yaml](../schemas/diagnostic-codes.yaml) |

All JSON Schema files use Draft 2020-12. Schema-controlled objects reject unknown properties unless the schema explicitly permits `x-*`. Extensions are preserved metadata and have no execution meaning. Duplicate YAML keys, unsafe tags, wrong types, missing fields, invalid enums, and unsupported properties are errors.

### Global configuration

```yaml
schemaVersion: att-config/v2.6
outputDirectory: output
environment: SIT
timeoutMs: 10000
caseLog: {yamlAnchors: false}
testcase: {root: testcase}
templates: {root: templates}
run: {id: {default: timestamp, timestampFormat: yyyyMMdd-HHmmss}}
execution:
  processOutput: {memoryLimitBytes: 65536, artifactLimitBytes: 104857600}
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns: {}
  html: {caseLogInlineLimitBytes: 32768}
  junit: {caseLogEmbedThresholdBytes: 10240}
xml: {namespaceMode: ignore}
toolGroups: [config/tools/database.yaml]
dbhelpers: [config/dbhelpers/orders.yaml]
mqhelpers: [config/mqhelpers/orders.yaml]
tools: {}
```

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | `att-config/v2.6`; V2.1/V2.2/V2.5 remain readable, but only V2.6 Tool descriptors accept `call`/`cache` |
| `outputDirectory` | `output` | Non-empty package-relative output root |
| `environment` | `SIT` | Non-empty value exposed as `${EXEC.INPUT.environment}`; it does not choose endpoints by itself |
| `timeoutMs` | `10000` | Integer 1–3600000 milliseconds |
| `caseLog.yamlAnchors` | `false` | Boolean; false fully expands repeated YAML structures, true permits anchors/aliases |
| `templates.root` | `templates` | Non-empty package-relative template root |
| `testcase.root` | `testcase` | Non-empty package-relative recursive workbook/sidecar discovery root |
| `run.id.default` | `timestamp` | Only `timestamp` is supported |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Non-empty Java date/time format |
| `execution.processOutput.memoryLimitBytes` | `65536` | Integer 1024–1048576; in-memory head/tail preview per stdout/stderr stream |
| `execution.processOutput.artifactLimitBytes` | `104857600` | Integer from `memoryLimitBytes` through 1073741824; maximum bytes streamed to each process artifact |
| `report.mode` | `append-to-copy` | `append-to-copy` or `none`; `none` skips result-workbook creation |
| `report.fileNamePattern` | `${suiteName}.result.xlsx` | Result workbook filename pattern |
| `report.columns` | `{}` | Arbitrary string keys and string label values |
| `report.html.caseLogInlineLimitBytes` | `32768` | Integer 0–1048576 UTF-8 bytes; larger logs use a bounded head/tail preview plus artifact link |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | Integer 0–1048576 UTF-8 bytes; 0 always links |
| `xml.namespaceMode` | `ignore` | `ignore` or `preserve` |
| `toolGroups` | `[]` | Unique safe package-relative tool-group YAML paths |
| `dbhelpers` | `[]` | Unique package-contained `att-dbhelper/v2.5` YAML paths; normalized duplicates are rejected |
| `mqhelpers` | `[]` | Unique package-contained `att-mqhelper/v1.0` YAML paths; normalized duplicates are rejected |
| `ssh` | absent | Optional SSH target for inline global tools |
| `tools` | `{}` | Map of reusable tool contracts |

Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `execution`, `report`, `xml`, `toolGroups`, `dbhelpers`, `mqhelpers`, `ssh`, `tools`, `x-*` |
| `caseLog` | `yamlAnchors`, `x-*` |
| `templates` | `root`, `x-*` |
| `testcase` | `root`, `x-*` |
| `run` | `id`, `x-*` |
| `run.id` | `default`, `timestampFormat`, `x-*` |
| `execution` | `processOutput`, `x-*` |
| `execution.processOutput` | `memoryLimitBytes`, `artifactLimitBytes`, `x-*` |
| `report` | `mode`, `fileNamePattern`, `columns`, `html`, `junit`, `x-*` |
| `report.html` | `caseLogInlineLimitBytes`, `x-*` |
| `report.junit` | `caseLogEmbedThresholdBytes`, `x-*` |
| `xml` | `namespaceMode`, `x-*` |
| `ssh` | `host`, `user`, `port`, `identityFile` |
| `tools.<key>` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; process Tools may use `output`, call-backed Tools may use `cache`; `x-*` |
| call-backed `tools.<key>.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, `argName`, `argNameMode`, `x-*` |

V2.0 fields such as `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` are not V2.2 fields.

### Dbhelper configuration

Each path in global `dbhelpers` resolves from the package root and contains one `att-dbhelper/v2.5` object:

| Object | Required/default | Allowed properties and constraints |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, `connection`; optional `statement`, `transaction`, `result`, `evidence`, `pool`, `x-*` |
| `connection` | required | required `url`; optional `username`, `password`, `driverClass`, `properties`, `readOnly`, `isolation`, `x-*` |
| `statement` | defaults | `timeoutSeconds` defaults to 30, integer 1–3600 |
| `transaction` | defaults | `scope: case|statement`, `onEnd: commit|rollback`; defaults `case`/`rollback` |
| `result` | defaults | `maxRows` 1000, `maxCellBytes` 1048576, `maxBytes` 10485760; positive bounded integers |
| `evidence` | defaults | `sql: full|hash` defaults full; `parameters: values|types|masked` defaults values |
| `pool` | defaults | `maxSize` defaults 20, `minIdle` defaults 0, `connectionTimeout` defaults 2s; `maxSize` 1–10000, `minIdle` cannot exceed `maxSize`, timeout is at least 250ms |

The root `id` must match `^[A-Za-z_][A-Za-z0-9_-]*$` and be package-unique ignoring case. `connection.isolation` is `driverDefault`, `readUncommitted`, `readCommitted`, `repeatableRead`, or `serializable`. Driver `properties` is a string-to-string map. Complete `${ENV:NAME}` values resolve while loading configuration; missing variables are errors. See [Database helpers](#database-helpers) for Action, expression, result, security, and lifecycle behaviour.

### MQ helper configuration

Each path in global `mqhelpers` resolves from the package root and contains one `att-mqhelper/v1.0` object:

| Object | Required/default | Allowed properties and constraints |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, `connection`; optional `message`, `requestReply`, `evidence`, `pool`, `x-*` |
| `connection` | required | `queueManager`, `host`, `port`, and `channel` required; optional `username`, `password`; port 1–65535 |
| `message` | defaults | `ccsid` defaults to 1208; `format` is `MQSTR`, `MQHRF2`, `MQFMT_STRING`, `MQFMT_NONE`, or `NONE`; `persistence` is `asQueue`, `persistent`, `notPersistent`, or `nonPersistent` |
| `requestReply` | defaults | `waitMs` defaults to 10000 and is 0–3600000 milliseconds |
| `evidence` | defaults | `payload: metadata` is the only V1 mode; full payload bytes are never placed in structured evidence |
| `pool` | defaults | `maxSize` defaults 20, `minIdle` defaults 0, `borrowTimeout` defaults 2s; `maxSize` 1–10000, `minIdle` cannot exceed `maxSize` |

Connection credentials may be complete `${ENV:NAME}` references. The loader resolves them without putting the secret or the environment variable value in diagnostics, metadata, or Case evidence. Queue names supplied in calls are non-blank, at most 48 characters, and restricted to IBM MQ queue-name characters. A helper instance is selected case-insensitively by its `id`; configured paths and IDs must be unique.

Case log structured entries use YAML. The human log records each normal action and each Tool/DB invocation once; duplicated attempt fields and persisted `TOOL`/`DB` subtrees are omitted from this projection. The complete final Stage/Template/Action/Tool/DB state remains in `case.yaml`. `caseLog.yamlAnchors: false` is the default for remaining shared Map/List objects; `true` permits SnakeYAML `&id001` / `*id001` anchor markers, which carry no ATT identifier semantics.

ATT prefixes every Case log block whose section or nested `status` is `ERROR`, `FAIL`, or `INVALID` with `【!!!!!】`. Search for that exact marker to locate abnormal blocks; PASS, SKIPPED, and informational blocks remain unmarked.

### Workbook sidecar

| Object | Allowed properties | Required/constraints |
|---|---|---|
| root | `schemaVersion`, `id`, `excel`, `stages`, `report`, `x-*` | schemaVersion, package-unique id, excel, non-empty stages required |
| `excel` | `sheet`, `headerRows`, `caseId`, `tags`, `dataColumns` | sheet, caseId, tags required; headerRows ≥ 1 |
| `stages[]` | `key`, `template`, `dataColumns`, `required`, `runWhen`, `onFailure` | key/template required; key has no dot |
| `report` | `columns` | values are strings |

Only the sidecar root permits `x-*`; `excel`, stages, and sidecar `report` reject extensions and other unknown fields. The sidecar cannot override timeout, retry, tools, dbhelpers, template root, environment, or output root.

### Template and action

| Object/type | Allowed/required contract |
|---|---|
| template root | `schemaVersion`, `name`, `description`, `actions`, `x-*`; schemaVersion, description, non-empty actions required |
| action common | `type`, `description`, `onFailure`, plus only fields belonging to its selected type; action ID has no dot |
| render | requires `payload`, `renderAs`; optional `assert`; no saveAs/output/call/expression/message/file/level/fields/timeout/retry/DB fields |
| tool | requires `call`; optional object-shaped `saveAs`, `assert`, `expected`, `actual`, `timeoutMs`, Action-only `retry`, and `evidence`; command/call-backed Tools share this contract |
| db | requires `db` and exactly one `query`/`update`; selected block requires exactly one `sql`/`sqlFile` and either typed-list `params` or named `parameters`; optional `assert` and object-shaped `saveAs`; no action-level `overwrite`, `call`, retry, or Action timeout |
| assert | requires `assert`; optional `expected`, `actual`; no expression/render/tool/log-only fields, timeout, or retry |
| log | requires at least one of `message` or `file`; optional `level`, `fields`, `assert`; no render/tool/assert-action-only fields, timeout, or retry |
| assign | requires `name`, `expression`; optional `assert`; exact typed calls retain their Java value; name is unique below `EXEC.VARS` for the entire Case; no render/tool/DB/assert-action/log-only fields, timeout, retry, or saveAs |
| `saveAs` | requires safe relative `path`; optional `format` and `overwrite`; target-specific format/default rules below; `overwrite` defaults false |
| retry | required `maxAttempts`, `intervalMs`, `retryOn`; categories are `ASSERTION`, `TIMEOUT` |

`renderAs` is `file`, `text`, `json`, `yaml`, or `xml`. Retry `maxAttempts` is 2–10 and `intervalMs` is 0–3600000. `ASSERTION` requires a non-empty Tool Action `assert`. Log level is `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR`. The template root and action permit `x-*`; `fields` is an unconstrained log-field map. `output` is runtime evidence and is never an action configuration field.

#### Assign variable uniqueness and lifetime

An `assign` action creates one immutable, Case-scoped entry below `EXEC.VARS`; it is not a mutable-variable update operation. Every `name` must be unique for the entire Test Case, including across stages and templates:

```yaml
firstAssign:
  type: assign
  name: txnSeq
  expression: "FIRST"

secondAssign:
  type: assign
  name: txnSeq
  expression: "SECOND"  # invalid: txnSeq was already declared for this Case
```

Validation reports `ATT-CTX-001` for the duplicate `${EXEC.VARS.txnSeq}` assignment and blocks execution. The second action never replaces the first value. `onFailure: continue` changes only subsequent action scheduling; it does not authorize an overwrite.

Use a new name for every transformation step:

```yaml
captureInitialAmount:
  type: assign
  name: initialAmount
  expression: "${EXEC.INPUT.amount}"

normalizeAmount:
  type: assign
  name: normalizedAmount
  expression: "#{number(${EXEC.VARS.initialAmount})}"
```

Later actions and stages read the transformed value as `${EXEC.VARS.normalizedAmount}`. The same assign name may be used by different Test Cases because each Case owns an isolated `EXEC.VARS`; uniqueness applies within one Case execution, not across the package globally.

If an assign expression fails, ATT does not create its variable. This does not relax the authoring rule: a later assign in the same Case plan still cannot reuse that declared name. If the expression succeeds and the assign action's optional assertion subsequently returns FAIL or ERROR, the variable remains available; assertions do not roll back successful assignment. `EXEC.VARS` survives stage/template transitions and is discarded only when that Test Case ends.

#### Action `saveAs`

`saveAs` is an optional property of `type: tool` and `type: db` actions. V2.6 uses one object shape:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]})}"
  saveAs:
    path: "responses/${EXEC.INPUT.rowCaseId}-response.json"
    format: raw
    overwrite: false
  assert: "${output.result.status} == 'SUCCESS'"
```

`path` is required and `overwrite` defaults to `false`. `format` has target-specific rules:

| Action target | Allowed `format` | Default | Content |
|---|---|---|---|
| configured process Tool | `raw`, `text`, `json`, `yaml`, `xml` | `raw` | exact stdout bytes for `raw`; parsed typed `output.result` otherwise |
| primary built-in called by `type: tool` | `text`, `json`, `yaml`, `xml` | `text` | typed `output.result` |
| configured call-backed Tool | `text`, `json`, `yaml`, `xml` | none; required | typed `output.result`; no stdout exists |
| `type: db` | `text`, `json`, `yaml`, `xml` | none; required | SQL*Plus-style rows/update count for `text`; stable typed DB result otherwise |

For a configured Tool, `raw` writes stdout exactly as produced by the process, including any final line ending; it is not the trimmed `rawOutput` string or parsed `output.result`. A built-in and DB action have no process stdout, so `raw` is invalid. For Tool/built-in targets, `text` writes `String.valueOf(output.result)` as UTF-8; for a direct DB Action it uses the SQL*Plus-style formatter above. `json`, `yaml`, and `xml` serialize the typed result using the selected codec and never serialize raw process stdout. Serialization does not replace the typed Context value.

ATT resolves the path below the current Case artifact directory, normally alongside `case.log` under `output/<RunID>/<CaseID>/`. Parent directories such as `responses/` are created. The saved path appears in the Action's `output.targetFiles`; configured Tool attempt evidence also records it as `outputFile`. Without `saveAs`, ATT creates no separate output file.

`saveAs.path` may be the reserved case-insensitive value `console`, which writes the selected representation to the Case log, adds no `output.targetFiles` entry, and creates no file. Otherwise it may contain `${...}` references plus `#{...}` expressions evaluated before the primary Action starts. Canonical Context paths are preferred:

```yaml
saveAs: {path: "${EXEC.INPUT.caseId}-response.json", format: raw}
saveAs: {path: "responses/${EXEC.VARS.txnSeq}.json", format: json}
saveAs: {path: "responses/${EXEC.ACTIONS.prepare.output.result}.txt", format: text}
saveAs: {path: "responses/#{lower(${EXEC.INPUT.rowCaseId})}.json", format: raw}
saveAs: {path: console, format: text}
```

The current Action has not produced an outcome when `saveAs.path` is evaluated, so `${output...}`, the current Action through `${EXEC.ACTIONS...}`, and future Action outputs are invalid. A configured Tool or DB call in the path is a real preceding invocation with normal Case-log evidence; built-ins are preferable for filename formatting.

Except for the reserved `console` value, the rendered value must be a non-blank safe relative path using `/` separators. Absolute paths, backslashes, empty path segments, `.` segments, `..` segments, and any path that escapes the Case artifact directory are rejected. Examples:

```yaml
saveAs: {path: "responses/TC001.json", format: raw}    # valid Tool artifact
saveAs: {path: "/tmp/response.json", format: raw}      # invalid: absolute
saveAs: {path: "../response.json", format: raw}        # invalid: parent traversal
saveAs: {path: "responses\\TC001.json", format: raw}   # invalid: backslash separator
saveAs: {path: "${output.result}.json", format: raw}   # invalid: current output unavailable
```

If the resolved file already exists within the same Case, the Action is ERROR unless `saveAs.overwrite: true` is configured. When Tool retry is enabled, every attempt uses the same resolved path; a later attempt may replace only the artifact written by an earlier attempt of that same Action so the dedicated file contains the final attempt. Separate Cases have separate artifact directories and therefore do not collide merely because they use the same relative path.

Write timing follows the selected content source and precedes the optional assertion. A process Tool with `raw` saves captured stdout even when output parsing, exit-code retry, or the final Action outcome is unsuccessful. A non-`raw` Tool artifact requires a successfully parsed typed result; a built-in artifact requires a successful built-in call; a DB artifact requires successful JDBC execution. Codec, path-resolution, collision, and file-write failures are Action `ERROR`. `render`, `assert`, `log`, and `assign` actions reject `saveAs`. A render action writes files through `renderAs: file` and exposes them through its own `output.targetFiles` contract.

DB therefore uses the same shape without pretending it has process output:

```yaml
saveAs:
  path: "db/${EXEC.INPUT.rowCaseId}-orders.txt"
  format: text
  overwrite: false
```

`path` and `format` are required for DB; format is `text`, `json`, `yaml`, or `xml`. The written representation never replaces `${output.result}`'s typed Java object.

`att-template/v2.3` remains read-compatible: its legacy Tool form `saveAs: response.json` plus sibling `overwrite: false` keeps its original raw-stdout meaning and is normalized internally to `{path: response.json, format: raw, overwrite: false}`. Newly authored `att-template/v2.6` files must use the object form; scalar `saveAs` and Action-level sibling `overwrite` are invalid.

### Tool contract

Each Tool requires `name`, `description`, and exactly one of `command` or `call`. Optional descriptor `timeoutMs` supplies the Tool-level default. A command is a non-blank scalar or non-empty string list; its `output` defaults to `txt` and accepts `txt|yaml|json|xml`. A call is one exact expression targeting DB query/scalar/update or a pure built-in; it forbids process-only `output`, SSH/script, and argument argv fields. Optional call-backed `cache` contains exactly `scope: case|db`; updates cannot be cached and `db` scope requires a DB query/scalar target.

Every argument requires `name`, `description`, and a YAML boolean `required`. For command-backed Tools, `argName` is optional and must be empty or one whitespace-free argv token. A non-empty `argName` requires exactly one complete-token placeholder. `argNameMode` accepts `once|repeat` and defaults to `once`; it controls a typed List supplied at the call site. These two argv properties are invalid for call-backed arguments. V2.6 does not define `delimit`.

Tool/argument keys are case-sensitive and argument keys use identifier syntax. The argument descriptor `name` is display text and may contain spaces, Chinese, and punctuation. External tool calls use named arguments. Positional arguments are reserved for ATT built-ins.

A tool-group root requires `schemaVersion`, package-unique `id`, `name`, `description`, and non-empty `tools`. It optionally accepts `script` in scalar/list command form and `ssh`. The group ID is the Tool package, so group calls use `group.tool`; inline global calls remain unqualified. Group/tool IDs match `[A-Za-z_][A-Za-z0-9_-]*` and contain no dot. Neither global nor qualified Tools may collide case-insensitively with canonical or legacy built-in names.

### Identifier and path constraints

Run ID and full Case ID are used directly as directory names; ATT does not slugify or hash a valid identifier.

Run ID must be non-blank, at most 128 Unicode code points, not `.` or `..`, not have leading/trailing whitespace or trailing `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or control characters. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.

`workbookId`, `groupId`, and `rowCaseId` follow the same character rules. `workbookId` and `groupId` must not contain `.`, because dots separate the three components; `rowCaseId` may contain dots and is treated as the remaining suffix. Each component is at most 128 Unicode code points and the complete `workbookId.groupId.rowCaseId` is at most 255. The sidecar `id` supplies `workbookId`, the left side of `excel.sheet` supplies `groupId`, and the configured Case ID cell supplies `rowCaseId`. Template paths are relative to `templates.root`; render glob matches remain below the template and `renderAs: file` targets remain below the Case output directory. Tool/DB Action `saveAs.path` stays below the Case artifact directory. ATT normalizes and checks root containment before reads and writes.

### Validation JSON contract

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.4.2",
  "valid": false,
  "mode": "package",
  "summary": {"errors": 1, "warnings": 0, "suites": 1, "cases": 22, "templates": 7, "tools": 7},
  "diagnostics": [{
    "code": "ATT-TPL-104",
    "severity": "ERROR",
    "message": "assert action requires a non-blank expression",
    "file": "templates/PAYMENT_VERIFY/template.yaml",
    "field": "actions.assertStatus.expression",
    "sheet": null,
    "row": null,
    "column": null,
    "template": "PAYMENT_VERIFY",
    "action": "assertStatus",
    "suggestion": "Add expression to the assert action"
  }]
}
```

Every diagnostic always contains `code`, `severity`, `message`, `file`, `field`, `sheet`, `row`, `column`, `template`, `action`, and `suggestion`. Inapplicable fields are `null`. When package and case validation discover the same root failure, ATT emits one diagnostic with `occurrences` and, when applicable, an `affectedCases` list; `summary.errors` counts unique diagnostics while `summary.errorOccurrences` preserves the raw occurrence count. Codes are stable; automation must not parse human messages.

ATT 3.3.0 may also include `summary`, `detail`, `source`, `context`, and `schemaViolations`. `source` holds physical YAML or payload `line`, `column`, `endLine`, and `endColumn`; the top-level `row` and `column` continue to identify an Excel cell. For single-line plain or directly quoted YAML scalars, an expression syntax error points to its character. Folded, multiline, or escaped scalars use the YAML scalar range when an exact mapping is unavailable. Every schema violation retains its own path, keyword, message, and physical source. `context` may contain the Case, Stage, Flow ID, and nested call chain. Expression syntax details identify the containing tool-call argument (for example, `logFiles`), the unexpected token, and a bounded caret excerpt when it is safe to show; source excerpts are omitted when the field or line may contain credentials or secrets.

Runtime Action failures preserve the same structure in Case YAML, `run.yaml`, regenerated reports, CI JSON, and JUnit failure detail. A nested Flow failure identifies the inner `flow.yaml` and Action while the call chain identifies how the Template reached it. Tool and DB evidence adds attempts, timeout, parse/capture, parameter binding, and cancellation details where available. File save failures include the configured path and allowed artifact root.

### Generated-output schema summary

| Artifact | Required top-level contract |
|---|---|
| `run.yaml` | `schemaVersion`, `att`, `runtime`, `run`, `validation`, `inputs`, `cases`, `summary`, `outputs` |
| Validation JSON | `schemaVersion`, `attVersion`, `valid`, `mode`, `summary`, `diagnostics` |
| CI summary JSON | `schemaVersion`, `attVersion`, `runId`, `environment`, `startedAt`, `endedAt`, `status`, `summary`, `durationStatistics`, `cases`, `diagnosticCounts`, `report`, `inputManifestHash` |
| JUnit XML | one testsuite with test/failure/error/skipped counts and one testcase per ATT case |

Generated envelopes reject additional top-level fields according to their schemas. JUnit HTML is a human-readable output and not an XML/JSON schema artifact.

## 07 Expression Reference

### Unified expression engine

V3.4 uses one engine with two deliberately separate roles:

- `${path}` reads one Context value and interpolates it into surrounding text, for example `Reference=${EXEC.VARS.SrcRefNo}`.
- `#{expression}` evaluates one typed expression block. The block may contain Context operands, calls, list literals, parentheses, unary operators, arithmetic, comparisons, `like`, `in`, null tests, and boolean logic.

Context references remain explicit inside a block; write `${EXEC.INPUT.amount}`, never bare `CASE.amount`. Append `?` to make the entire reference optional, for example `${EXEC.INPUT.response.body.missing?}`. If any map, list, root-owned Context value, or intermediate segment is missing, the result is the real `null`; an existing final `null` also remains `null`. `${path}` remains strict. Optional lookup does not suppress ambiguity, malformed syntax, or invalid traversal such as indexing a scalar, so those authoring errors still fail. Exact blocks preserve their Java result type, while a block embedded in surrounding text is converted to text.

```yaml
assert: "#{${EXEC.INPUT.response.body.missing?} is null}"
actual: "#{nvl(${EXEC.INPUT.response.body.missing?}, 'not supplied')}"
description: "status=${EXEC.INPUT.response.body.status?}; fallback=#{coalesce(${EXEC.INPUT.response.body.missing?}, 'N/A')}"
```

```yaml
assert: >-
  #{(${EXEC.INPUT.amount} * ${EXEC.INPUT.rate}) >= 100
    and ${EXEC.INPUT.status} in ['PENDING', 'POSTED']}
description: "Reference length: #{length(${EXEC.VARS.SrcRefNo})}"
expression: "#{${EXEC.ACTIONS.query.output.result.rowCount} + 1}"
```

Operator precedence from highest to lowest is:

1. parentheses, literals, `${...}`, lists, and calls;
2. unary `+`, unary `-`, and `not`;
3. `*` and `/`;
4. `+` and `-`;
5. `== != > >= < <=`, `like`, `in`, and `is [not] null`;
6. `and`;
7. `or`.

Arithmetic operands must be numeric and division by zero is an error. `in` requires a List, array, or Iterable right operand; a literal list such as `['A', 'B']` and a typed Context list such as `${EXEC.INPUT.allowedStatuses}` are valid. The legacy non-block assertion grammar also accepts literal-list `in`, but arithmetic and typed list membership should use `#{...}`.

Call arguments may themselves be any expression. Calls can be nested directly, for example `#{upper(trim(${EXEC.INPUT.name}))}`; the older nested-block spelling `#{upper(#{trim(${EXEC.INPUT.name})})}` remains accepted. Single/double ASCII quotes and paired typographic quotes delimit strings. Numeric, boolean, and null literals retain their types. Other unquoted tokens are literal strings unless they look like reserved Context paths or a visible scoped variable, in which case ATT requires `${...}`.

Context interpolation within surrounding text still uses `${...}`: write `prefix-${EXEC.INPUT.caseId}` or `#{concat('prefix-', ${EXEC.INPUT.caseId})}`. Unique-suffix lookup remains available only inside `${...}`, although canonical paths such as `${EXEC.VARS.SrcRefNo}` are preferred.

For backward compatibility, an unquoted Tool-call argument shaped like `${directory}/file.name` remains text interpolation rather than numeric division. New numeric division such as `${EXEC.INPUT.amount}/2` remains arithmetic; quote path-like values in new configuration when practical.

The available values and callable capabilities still depend on the location's scope:

| Expression-bearing location | `${...}` scope | Built-in `#{...}` | Configured Tool `#{...}` | DB `db.*` query | Evaluation point |
|---|---|---:|---:|---:|---|
| render payload content | Runtime Context | Yes | Yes | Yes | Before payload parsing/writing |
| action `description` | Runtime Context including current `output` | Yes | Yes | Yes | After normal action completion |
| action `assert` | Runtime Context including current `output` | Yes | Yes | Yes | After the action result is published locally |
| assert-action `expected` | Runtime Context before current output | Yes | Yes | Yes | Before the assert action |
| assert-action `actual` | Runtime Context including current `output` | Yes | Yes | Yes | After assertion evaluation |
| log-action `message`, `file`, and `fields` values | Runtime Context before current output | Yes | Yes | Yes | Before reading/emitting the optional file |
| assign-action `expression` | Runtime Context before current output | Yes | Yes | Yes, typed for an exact call | Before publishing `EXEC.VARS.<name>` |
| Tool-action `call` | Runtime Context before current output | Yes, including as the primary call | Yes | Yes inside arguments | As the action's primary invocation |
| Tool/DB-action `saveAs.path` | Runtime Context before current output | Yes | Yes | Yes | Before the primary Tool/JDBC invocation |
| DB-action `query/update.params` | Runtime Context before current output | Yes | Yes | Yes | Before primary JDBC binding |
| DB-action `query/update.sql` or `sqlFile` content | Runtime Context before current output | Pure built-ins only | No | No | Before JDBC prepare |
| `config.report.fileNamePattern` | `${suiteName}` | Yes | No | No | When writing the result workbook |
| Tool-definition `command` tokens | declared Tool-input `${...}` aliases | Yes | No | No | When constructing logical argv |
| Tool-definition `call` | declared typed `${input.*}` only | Pure built-ins | No configured Tool chaining | One primary DB query/scalar/update | When invoking the façade |

For a `type: tool` action, the outer `call` may name either a configured Tool or an ATT built-in. A primary built-in runs in a bounded daemon executor and publishes its value at `${output.result}`; it has `exitCode: 0`, supports timeout, Action assertion/retry, and optional `saveAs`, and records `type: builtin` attempt evidence without a `TOOL` process node, argv, stdout, or stderr. Built-ins, command-backed Tools, call-backed READ Tools, and direct read-only DB queries may be used inside ordinary Case-runtime expressions. A call-backed DB update is restricted to the primary call of a Tool Action. Configured Tool and DB calls remain unavailable in `fileNamePattern`, Tool `command`, and DB SQL-source rendering because those dedicated scopes cannot safely contain hidden or recursive external execution.

```yaml
normalizeReference:
  type: tool
  call: "#{upper(${EXEC.INPUT.reference})}"
  saveAs:
    path: "normalized-reference.txt"
    format: text
  assert: "${output.result} == 'PAY-001'"
```

`#{...}` is not restricted to text replacement. An exact block retains its typed result and is evaluated before the Action consumes it. Therefore both of these are valid:

```yaml
assert: "#{length(value=${EXEC.VARS.SrcRefNo})} <= 35"
assert: "#{${EXEC.INPUT.status} in ${EXEC.INPUT.allowedStatuses}}"
```

The first block returns a Boolean directly; ATT does not stringify and reparse it. A configured Tool or DB query called from a Case-runtime field is a real external invocation and produces evidence; do not use either merely for formatting when a built-in or existing Context value is sufficient.

### Runtime Context

The execution-neutral Context has two canonical roots and one Action-local binding:

```text
EXEC
├── ID, MODE, STARTED_AT, OUTPUT_DIR
├── INPUT (TestCase data or debug sidecar input)
├── VARS (typed variables shared by later stages/templates)
└── ACTIONS (completed/published Action results)
META
├── PROJECT, SOURCE, TARGET
├── TEMPLATE, FLOW
└── TOOL, DBHELPER, MQHELPER (curated invocation metadata)
output
└── current Action/attempt-local result; unavailable outside that Action scope
```

`EXEC.MODE` is `testcase` for a normal run and `debug` for standalone debug. `EXEC.INPUT`, `EXEC.VARS`, and `EXEC.ACTIONS` are the same mutable runtime state used by both modes, not parallel copies. The TestCase adapter overlays current Stage caller/input values onto `EXEC.INPUT` for the active Stage; Stage values win over Case-level values on collision and the Case-level values are restored after the Stage. Framework-owned fields such as `EXEC.ID`, `EXEC.MODE`, `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.VARS`, and `EXEC.ACTIONS` cannot be overwritten by Case or sidecar input. There is intentionally no `EXEC.TOOL`, `EXEC.DB`, `EXEC.MQ`, `EXEC.OUTPUT`, `EXEC.CALL`, `EXEC.INVOCATION`, `EXEC.STAGE`, or `EXEC.STAGES`: helper/resource state remains internal, root-level `TOOL.*` / `DB.*` remain compatibility or transient views, and Action result/evidence is consumed through local `output` while active and `EXEC.ACTIONS` after publication. Stage/Template status, timing, and history remain in the execution result/evidence model and legacy `CASE.STAGES`. Ordinary 3.4.2 TestCase and debug execution has no `EXEC.LOAD`; the 3.5.0 `att-load/v1.0` adapter adds the load-only `EXEC.LOAD` namespace described below.

### Load V1 Context (3.5.0)

Each load iteration uses the same `EXEC`/`META` tree and action-local `output` as normal execution. `EXEC.MODE` is `load`; `EXEC.ID` and `EXEC.LOAD.ITERATION_ID` are the same iteration identity; `EXEC.STARTED_AT` is the iteration start; and `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, and local `output` are isolated per iteration. The scheduler-owned fields are:

| Path | Meaning |
|---|---|
| `EXEC.LOAD.RUN_ID` | Enclosing load run identity shared by its iterations. |
| `EXEC.LOAD.MODEL` | `closed` or `arrivalRate`. |
| `EXEC.LOAD.USER_ID` | Stable closed-model Virtual User identity; `null` or absent for arrival-rate. |
| `EXEC.LOAD.ITERATION_ID` | Globally unique iteration identity within the load run. |
| `EXEC.LOAD.ITERATION` | Scheduler sequence number. |
| `EXEC.LOAD.PHASE` | `WARMUP`, `RAMP_UP`, `STEADY`, or `RAMP_DOWN`. |
| `EXEC.LOAD.RUN_STARTED_AT` | Optional enclosing load-run start timestamp. |

Scenario `inputs` are copied only into `EXEC.INPUT.*`; reusable Templates, Flows, and Tools must use that canonical input tree, `EXEC.VARS.*`, `EXEC.ACTIONS.*`, and current `output.*`. `META.SOURCE` identifies the load scenario by type, scenario name, and path; iteration identity remains under `EXEC.ID` and `EXEC.LOAD.*`, and secrets are excluded. Root-level `LOAD.*`, `EXEC.OUTPUT`, `EXEC.CALL`, and `EXEC.INVOCATION` are not public load APIs. See [`examples/load/README.md`](../examples/load/README.md) for complete closed/arrival-rate configurations, CLI overrides, target forms, thresholds, evidence, and validation examples.

`att load` validates the scenario and target before starting one of two schedulers. Closed mode keeps a stable Virtual User identity and waits for target completion before think time and the next iteration. Arrival-rate mode uses absolute planned due times; when `maxConcurrent` is full, the arrival is recorded as generator `dropped` work rather than queued or counted as a SUT failure. Both schedulers publish compact events to bounded-memory metrics, and both write isolated `output/load/<runId>/load-summary.json`, `load-summary.yaml`, and `report/index.html`. Warm-up is real traffic but is excluded from measured threshold aggregates by default. Successful iterations retain metrics only unless evidence sampling is configured; a bounded sampled success gets a physical iteration workspace with `case.log` and `case.yaml`, while a failure creates that workspace lazily when its diagnostic is retained. Evidence links are written below the load run's `samples/` or `failures/` directories and never enter ordinary functional-run artifacts.

The shortest end-to-end smoke commands are:

```sh
./att.sh load examples/load/closed.yaml
./att.sh load examples/load/arrival-rate.yaml --format json
./att.sh load examples/load/tool.yaml --duration 100ms --run-id load-tool-example
```

`examples/load/README.md` is the maintained copyable reference for Template, Flow, Tool, DB/MQ pool sizing, thresholds, evidence, CLI overrides, and invalid configurations. All four examples are schema- and dependency-validated by `LoadAcceptanceTest`; that test also launches the real `att.FrameworkRunner load` CLI for short closed and arrival-rate scenarios and checks the persisted JSON, YAML, and offline HTML report.

### Load summary and HTML report contract

`load-summary.json` and `load-summary.yaml` share the stable `att-load-summary/v1.0` contract. Root fields are `schemaVersion`, `status` (`PASS`, `FAIL`, or `ERROR`), `exitCode`, `runId`, `startedAt`, `endedAt`, `durationMs`, `scenario`, `timing`, `metrics`, `thresholds`, `resources`, optional `evidence`, and `report: report/index.html` relative to the run directory. The JSON schema is `schemas/att-load-summary-v1.0.schema.json`, registered in the schema catalog as `att-load-summary/v1.0`.

The persisted `scenario` is a dedicated report-safe projection. It retains target type/id, workload and execution timing, threshold configuration, and evidence policy, but omits arbitrary business `inputs` and Tool `target.arguments` from JSON, YAML, and the HTML `window.ATT_LOAD_SUMMARY`. CI and offline tooling can therefore consume the summary without durable password, token, request-body, or other oversized payload values.

`timing.phases` lists configured `WARMUP`, `RAMP_UP`, `STEADY`, and `RAMP_DOWN` start/end/duration windows. `metrics.phases` contains observed scheduled/started/completed/failure/drop counts, throughput, latency, scheduler lag, and concurrency aggregates per phase. Warm-up has `measured: false`: its traffic remains visible in the run history, but measured SLA aggregates exclude it. Other phases remain measured. A phase with no events still appears in `timing.phases`, so empty and edge runs have a stable machine-readable shape.

`resources.db` and `resources.mq` contain only bounded pool diagnostics such as pool size, active/idle, waiting, and timeout/acquisition counts; they never contain connections, queue handles, credentials, or other live objects. Pool saturation and acquisition timeouts are separate from SUT failures. `evidence.items[].path` points to retained evidence below `<runId>/samples/` or `<runId>/failures/`; the HTML report renders each path as a relative link.

`report/index.html` is self-contained and can be opened offline. It shows run identity/status, closed or arrival-rate semantics, phase and warm-up separation, aggregate metrics, threshold diagnostics, resource diagnostics, retained evidence links, and bounded one-second time-series buckets. Arrival-rate reports explicitly distinguish configured arrival rate, achieved scheduling rate, completed TPS, and generator drops; drops are not SUT errors. The report links to the adjacent JSON/YAML summaries but does not embed raw per-iteration samples or secrets; `window.ATT_LOAD_SUMMARY` exposes the same bounded summary for offline tooling.

The machine-readable `metrics` object reports configured load (`configuredUsers`, `configuredArrivalRatePerSecond`, `configuredMaxConcurrent`), iteration/scheduling counts (`iterations`, `scheduled`, `measuredScheduled`, `started`, `measuredStarted`, `completed`, `success`, `failure`, `runtimeError`, `dropped`, `measuredDropped`), concurrency (`activeVus`, `maxActiveVus`, `currentInFlight`, `maxInFlight`), measured-phase results (`warmupCompleted`, `measuredCompleted`, `sutErrorRate`, `runtimeErrorRate`, `droppedRate`, `completedThroughput`), latency percentiles (`p50Ms`, `p95Ms`, `p99Ms`), scheduler lag, and grouped `errorClassifications`. Percentiles use a bounded reservoir; `latencyMinMs`, `latencyMeanMs`, `latencyMaxMs`, and `latencyObservationCount` remain exact across all measured observations. Runtime errors are separate from SUT failures, and generator drops never increase `sutErrorRate`. The `buckets` map is sorted by one-second epoch-millisecond key; each bucket includes `model`, `phase`, configured rate/concurrency, completed TPS, p95/p99, SUT/drop rates, active/in-flight counts, scheduler lag, and error classifications. Latency storage is capped at 4096 global samples and 256 samples per bucket; time-series storage is capped at 4096 buckets and evicts the oldest bucket, so memory does not grow linearly with run duration or raw latency values.

Load thresholds use the common `errorRate` (`%`), `p95`/`p99` (`ms`), and `minThroughput` (`/s` or `/m`) fields for both workload models. Arrival-rate scenarios additionally support `droppedRate` (`%`) and `achievedArrivalRate` (`%`, `/s`, or `/m`). For the percentage form, achieved arrival rate is measured `measuredStarted / measuredScheduled`; warm-up is excluded, while ramp-up, steady, and ramp-down remain part of the integrated measured schedule. The rate forms compare the actual average started rate over the full phase window; `/m` thresholds are normalized to per-second before comparison. Each threshold is reported independently with expected expression, formatted actual value, PASS/FAIL status, and failure diagnostic. The load result then uses exit code `0` for PASS, `1` for a completed run with failed SLA thresholds, `2` for validation/configuration failure, and `3` for load runtime/infrastructure error. `target.arguments` is valid only for Tool targets; Template and Flow targets reject it with a field-specific diagnostic.

The release gate is deliberately reproducible rather than a SUT microbenchmark:

```sh
mvn -q -Dtest=LoadAcceptanceTest,LoadRuntimeTest,LoadScenarioTest,LoadReportTest test
```

It checks the CLI-to-report path for both schedulers, Context deep-copy and iteration isolation, lazy success/failure workspaces, bounded evidence and metric reservoirs, scheduler lag accounting, process/file artifact behavior, pool cleanup, threshold PASS/FAIL, summary schema, report rendering, and compatibility of the existing run/debug/validation test suite. Load V1 does not claim distributed execution, Poisson/random pacing, weighted multi-scenario, rendezvous, adaptive pools, MQ handle pooling, XA/affinity, or target CPU/memory benchmarking.

Common properties include:

| Scope | Examples |
|---|---|
| EXEC.INPUT | TestCase columns, debug `case`/`inputs`, and stage input aliases |
| EXEC.VARS | `assign` values; `CASE.VARS` remains a compatibility alias |
| EXEC.ACTIONS | current Stage's completed/published Action results; cleared when the next Stage starts |
| CASE.STAGES | persisted Stage/Template status, timing, and nested Action evidence; not a supported expression namespace |
| META | safe project/source/target/component identity; never a config dump or credential store |
| output | current Action result, assertion actual value, and final description inputs |
| CASE / RUN / ACTIONS | generated legacy views of the canonical state; `ACTIONS` is current-scope only |
| CASE.DB / TOOL / DB | existing finalization or transient framework scopes, kept separate from `EXEC` |

Prefer canonical paths such as `${EXEC.INPUT.amount}`, `${EXEC.INPUT.channel}`, `${EXEC.VARS.txnSeq}`, `${EXEC.ACTIONS.callApi.output.result}`, and `${META.TARGET.id}`. Use `${output...}` only for the current Action and `${EXEC.ACTIONS.<id>...}` only for a completed Action in the current scope. Stage/Template/Flow history, including `${CASE.STAGES...}`, is persisted result/evidence data and is not a supported reusable expression path; direct reads produce `CONTEXT_CROSS_SCOPE`. Root `${TOOL...}` and `${DB...}` may remain only as internal or persisted historical/result compatibility views, not case-wide “latest invocation” APIs; general expressions using them produce `CONTEXT_LEGACY_PATH`. Tool and inline DB evidence is persisted below the containing Action with stable `<kind>.invocations[]` cardinality; Case-level DB finalization remains available through `${CASE.DB.<instance>}` after Case completion.

The following aliases are required for existing packages. New authoring should use the right-hand canonical/local path; the left-hand forms belong in migration or compatibility material only:

| Legacy path | Canonical/local path |
|---|---|
| `${CASE.<businessField>}` | `${EXEC.INPUT.<businessField>}` |
| `${CASE.caseId}` / `${CASE.workbookId}` / `${CASE.groupId}` / `${CASE.rowCaseId}` | `${META.SOURCE.caseId}` / `${META.SOURCE.workbookId}` / `${META.SOURCE.groupId}` / `${META.SOURCE.rowCaseId}` |
| `${CASE.VARS}` | `${EXEC.VARS}` |
| `${ACTIONS}` | `${EXEC.ACTIONS}` |
| `${RUN.id}` / `${RUN.runId}` | `${EXEC.ID}` |
| `${CASE.outputDirectory}` | `${EXEC.OUTPUT_DIR}` |
| `${CASE.status}` / `${CASE.durationMs}` / `${CASE.environment}` | Legacy lifecycle/result aliases; there is no corresponding canonical `EXEC` field |
| `${CASE.STAGES.<stage>...}` | Legacy execution/evidence data only; direct expression use is rejected with `CONTEXT_CROSS_SCOPE` |
| `${output.*}` | current Action-local `output.*` |

For compatibility, a framework adapter may still write `${CASE.<businessField>}`; that write is applied to the same `EXEC.INPUT` map and does not create a second input store. New expressions should read the canonical path; only compatibility adapters should use the legacy write spelling. Framework-owned identity, lifecycle, `VARS`, `DB`, and Stage evidence fields remain protected.

`META` is read-only to expressions and contains only curated safe metadata. Optional references such as `${EXEC.INPUT.maybeMissing?}` and `${output.response?}` use the same canonical/local resolver and return null only for missing values; malformed, ambiguous, or invalid traversal remains an error.

V2.4.1 also accepts a case-sensitive path-segment suffix when it identifies exactly one currently readable logical Context path. For example, if `EXEC.INPUT.payment.response.resultCode` is the only readable path ending with those segments, `${payment.response.resultCode}`, `${response.resultCode}`, and `${resultCode}` resolve to the same value. Matching uses parsed map keys/list indexes, not a raw character suffix. Canonical and convenience aliases of the same logical node count once. If multiple logical paths match, ATT raises `ATT-CTX-002`, lists every canonical candidate in deterministic order, and requires a longer suffix or full path. Adding a conflicting node therefore makes an existing shorthand invalid rather than silently changing its target. Documentation continues to prefer canonical paths.

`${EXEC.OUTPUT_DIR}` is a reserved, normalized absolute path and Case data cannot override it. `EXEC.VARS` and `CASE.DB` are likewise fixed framework-owned maps, so a sidecar `excel.dataColumns` alias or any other Case-root alias cannot be named `VARS` or `DB`. All three nodes exist before the first stage; `CASE.DB` remains empty until Case transaction finalization publishes used-instance outcomes. During a Stage, its caller/input values are adapted into `EXEC.INPUT` and do not create an `EXEC.TOOL`, `EXEC.DB`, `EXEC.MQ`, `EXEC.STAGE`, or `EXEC.STAGES` node. Stage status, timing, and historical selector values remain below legacy `CASE.STAGES.<stage>` evidence. During execution `outputDirectory` is already the final `<outputDirectory>/<RunID>/<CaseID>` directory, so live evidence and persisted paths are identical. Validation preserves the output-directory placeholder because no runtime Run directory exists yet.

Map properties use dot navigation and lists use zero-based brackets:

```text
${EXEC.INPUT.amount}
${EXEC.INPUT.channel}
${EXEC.ACTIONS.callApi.output.result.items[0].status}
```

Dot notation navigates simple map keys. Lists accept bracket or numeric-dot indexes, so `${EXEC.INPUT.items[0].status}` and `${EXEC.INPUT.items.0.status}` are equivalent. Indexes are zero-based. Map keys containing dots, spaces, braces, or colons use quoted brackets, for example `${EXEC.INPUT.response['{urn:payment}Status'].text}`.

#### Example: reference stage-selector data from an XML payload

Suppose the Excel selector cell for stage `invoke` contains this YAML flow map:

```yaml
{name: templateName, debitAccount: "012123456", InstrAmt: "100.00"}
```

Flow-map entries use commas, not semicolons. Every selector-map key is adapted into the current Stage's `EXEC.INPUT`, so the canonical XML payload reference is:

```xml
<InstrAmt>${EXEC.INPUT.InstrAmt}</InstrAmt>
```

The old `CASE.STAGES` path remains available in persisted execution evidence and migration material only; it is not readable from a Template/Flow expression. When no other currently readable logical path creates a suffix conflict, the following forms resolve to the same current Stage input value:

| Expression | Meaning | Stability |
|---|---|---|
| `${EXEC.INPUT.InstrAmt}` | Canonical current-Stage input | Preferred; explicit and stable |
| `${EXEC.INPUT.InstrAmt}` | Canonical current-Stage input | Preferred; explicit and stable |
| `${invoke.InstrAmt}` | Unique current-input suffix | Valid only while unique; migrate to canonical form |
| `${InstrAmt}` | One-segment suffix | May be ambiguous because the value is also in `EXEC.INPUT` |

If another readable path also ends in `InstrAmt`, the shortest form raises `ATT-CTX-002` instead of choosing one silently. Lengthen the suffix or use the canonical path. Bracket notation for `CASE.STAGES` remains a report/evidence selector spelling only and is rejected when used as a runtime Context expression.

`InstrAmt` is current Stage input at `${EXEC.INPUT.InstrAmt}`; it is not a direct child of `TEMPLATE`. `${TEMPLATE.InstrAmt}` is invalid unless an independently mapped value actually exists at the requested path. Quote XML lexical values such as account numbers and fixed-scale amounts in the selector YAML. This preserves the leading zero in `"012123456"` and the authored decimal representation `"100.00"`; unquoted YAML numeric values are typed numbers and do not promise to retain their original text formatting.

Validation resolves available static values and preserves only values that are legitimately runtime-dependent. Runtime resolves every remaining reference at its defined execution point. Canonical `EXEC`/`META` roots and supported legacy aliases are traversed strictly; `CASE.STAGES` and cross-scope Action reads are rejected as incompatible scope references. References without an explicit root use the unique-suffix rule above; when validation can identify the canonical current-scope replacement, it emits `CONTEXT_LEGACY_PATH` and should be migrated. Tool definitions have a separate rule described below. An unknown Context path is never converted silently to empty text: `ATT-CTX-001` reports the exact `requestedPath`, deepest successfully reached `currentNode`, first `missingSegment`, and source location. `ATT-CTX-002` reports the requested shorthand and all candidate paths. Neither diagnostic dumps the complete Context tree, preventing large failed Action/Tool/DB structures from being copied repeatedly into logs and reports. A declared optional Case field whose actual value is blank remains a valid empty string. An Action may read only Case data, its local `output` where supported, and Action outputs that exist in its current scope; validation rejects current/future or cross-scope Action references.

### `config.report.fileNamePattern`

#### Context and legal forms

`report.fileNamePattern` uses the unified expression engine with a dedicated non-Case scope. It has one case-sensitive value reference:

| Placeholder | Value |
|---|---|
| `${suiteName}` | Source workbook basename with its final lowercase `.xlsx` suffix removed; for example, `testcase/payment_regression.xlsx` becomes `payment_regression` |

The configured string must reference `${suiteName}` explicitly, whether used as text interpolation or as a built-in argument. Bare `suiteName` inside a call is rejected. Legal examples include:

```yaml
report:
  fileNamePattern: "${suiteName}.result.xlsx"
```

```yaml
fileNamePattern: "result-${suiteName}.xlsx"
fileNamePattern: "ATT-${suiteName}-report.xlsx"
fileNamePattern: "${suiteName}-${suiteName}.xlsx"
fileNamePattern: "#{upper(${suiteName})}.result.xlsx"
fileNamePattern: "#{concat('ATT-', #{lower(${suiteName})})}.xlsx"
```

For `testcase/payment.xlsx`, the first example writes `output/<RunID>/workbooks/payment.result.xlsx`. `${suiteName}` is the physical workbook basename, not the sidecar `id`, Sheet/group ID, Case ID, or Run ID. Authors should keep the value a safe filename ending in `.xlsx`; avoid `/`, `\`, absolute paths, `..`, and platform-reserved names. Workbooks in different recursive directories that share the same basename resolve to the same default result filename, so package authors must avoid that collision.

#### Illegal or unsupported forms

These values fail configuration loading because they do not reference `suiteName`:

```yaml
fileNamePattern: "result.xlsx"
fileNamePattern: "${runId}.result.xlsx"
fileNamePattern: "${workbookId}.result.xlsx"
```

No other value reference or Runtime Context path is supported. Configured Tool calls are also unavailable in this scope. These forms are invalid:

```text
${runId}
${workbookId}
${environment}
${EXEC.INPUT.caseId}
${EXEC.ID}
#{configuredTool()}
#{upper(${runId})}
```

A pattern such as `${suiteName}-${runId}.xlsx` is rejected; unknown references are never retained as literal output text. All documented built-ins are parsed by the same engine, including nested calls. Because the resulting text becomes a filename, prefer deterministic string transformations and avoid side-effecting filesystem built-ins, random values, path separators, absolute paths, `..`, and platform-reserved names.

### Tool-definition `command` expressions

#### Context and legal forms

A configured Tool `command` also has its own restricted Context. It may reference only keys declared by that Tool's `arguments` map. The canonical placeholder is `${input.argument}`. `${TOOL.input.argument}` and the exact `${argument}` spelling remain compatible legacy forms and both produce `CONTEXT_TOOL_INPUT_SHORTHAND` when they uniquely match a declared key:

| Form | Meaning |
|---|---|
| `${requestFile}` | Legacy shorthand; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${input.requestFile}` | Explicit Tool-input namespace |
| `${TOOL.input.requestFile}` | Legacy full alias; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |

For example:

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestFile}"
      - "${input.environment}"
    output: json
    arguments:
      requestFile:
        name: Request File
        description: Rendered XML request path
        required: true
      environment:
        name: Environment
        description: Target environment
        required: true
```

The action call is the boundary between the general Runtime Context and this restricted Tool-input Context:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}, environment=${EXEC.INPUT.environment})}"
```

The call resolves the explicit `${EXEC.ACTIONS...}` and `${EXEC.INPUT...}` references first and creates Tool inputs named `requestFile` and `environment`. The command then substitutes `${input.requestFile}` and `${input.environment}` from those inputs; `${input.environment}` does not read global configuration directly. The legacy `${requestFile}` / `${environment}` spelling and `${TOOL.input.*}` remain compatible only when each name is declared and emit `CONTEXT_TOOL_INPUT_SHORTHAND`.

Each command token also accepts built-in calls through the same expression engine. Built-ins see only the declared Tool-input aliases shown above, and calls may be nested:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--environment=#{upper(${input.environment})}"
  - "--label=#{concat('ATT-', #{lower(${input.requestFile})})}"
```

Inside a command-side built-in call, declared inputs must also use placeholders: `${input.requestFile}` is canonical; `${TOOL.input.requestFile}` and `${requestFile}` are deprecated compatible forms and produce `CONTEXT_TOOL_INPUT_SHORTHAND`. Bare `requestFile` or `input.requestFile` is not inferred. Outside `#{...}`, command text continues to use the same Tool-local rule.

A normal argument placeholder may occupy a complete argv token, which is preferred, or be embedded in fixed text:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--request=${input.requestFile}"
  - "--environment=${input.environment}"
```

Because this is a YAML argv list, each list item remains one atomic process argument even when its resolved value contains spaces or shell-like characters. ATT does not invoke a local shell.

#### Quotes, Context values, and atomic argv

Quotes inside a Tool call belong to the ATT expression grammar; they are not shell quotes. The outer `'...'` or `"..."` delimiters are removed before invocation, the opposite quote is literal, and a matching quote can be escaped with a backslash. A `${...}` reference embedded in a quoted value is interpolated, while an unquoted canonical Context path passes its typed value directly.

The following configured Tool keeps each declared input as one argv value:

```yaml
tools:
  writeAudit:
    name: Write audit
    description: Write one audit message for one source file
    command: [./tools/write_audit.sh, "${message}", "${sourceFile}"]
    output: yaml
    arguments:
      message:
        name: Message
        description: Exact audit message
        required: true
      sourceFile:
        name: Source file
        description: File associated with the message
        required: true
```

Use a YAML block scalar when a call contains several quote layers:

```yaml
singleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message="Customer O'Reilly",
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
    )}
```

The child process receives the three messages exactly as `Customer O'Reilly`, `status="READY"`, and, for example, `O'Reilly said "READY" for payment.payment.TC001`. A Context value that itself contains either quote needs no caller-side shell escaping and still occupies one argv item.

If a call is kept on one YAML line, YAML escaping is an additional and separate layer:

```yaml
call: "#{writeAudit(message='status=\"READY\"', sourceFile=${EXEC.INPUT.sourceFile})}"
call: '#{writeAudit(message="O''Reilly", sourceFile=${EXEC.INPUT.sourceFile})}'
```

The first line escapes double quotes for the YAML double-quoted scalar. The second doubles the apostrophe for the YAML single-quoted scalar. The expression engine then evaluates the resulting `#{...}` text.

Ordinary process-backed Tools never ask a shell to reinterpret resolved inputs. Text such as `$HOME`, `$(date)`, `a*.xml`, `|`, `>`, and quotes carried by a Context value is passed literally. Use an explicitly reviewed wrapper when shell-like behavior is required; the shipped `fpp.exehelper` and `fpp.loghelper` provide only the narrowly documented pathname expansion above.

#### Illegal forms and token restrictions

Tool commands cannot directly read the general Runtime Context, use unique-suffix navigation, or navigate argument fields with bracket syntax. These `${...}` forms are rejected during configuration or package validation:

```text
${EXEC.INPUT.environment}
${EXEC.ID}
${EXEC.ACTIONS.renderRequest.output.targetFiles[0]}
${STAGES.invoke.InstrAmt}
${input['requestFile']}
${TOOL.input['requestFile']}
${requestFile.path}
```

Configured Tool calls are not available inside `command`:

```text
#{anotherConfiguredTool(value=${requestFile})}
```

This is rejected during configuration loading. Expanding one Tool's command cannot invoke another Tool or recursively invoke itself. An unknown, misspelled, differently cased, or undeclared `${...}` argument reference is also a validation error. For a standalone global Tool, the executable token is static and cannot itself contain `${...}` or `#{...}`.

If an argument declares a non-empty `argName`, its placeholder must appear exactly once and occupy one complete command token:

```yaml
command: [./tools/invoke_payment_api.sh, "${input.requestFile}"]
arguments:
  requestFile:
    name: Request File
    description: Rendered XML request path
    required: true
    argName: --request
```

ATT expands that token to two argv values: `--request`, then the resolved path. An embedded form such as `--request=${input.requestFile}` or a transformed form such as `#{str.upper(${input.requestFile})}` is invalid when `argName` is non-empty. Likewise, every typed List must use a complete-token placeholder so ATT can safely expand it to zero or more argv values. For an optional argument, a blank complete-token placeholder emits neither its `argName` nor a value; an embedded scalar placeholder instead leaves its surrounding fixed token in argv.

### Operators

Supported assertion operators are `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, `not`, `and`, and `or`. Use parentheses when mixing logical operators so intent is explicit.

`like` is case-insensitive as an operator keyword, but the canonical authored spelling is lowercase. It matches the complete value and uses two SQL-style wildcards:

- `%` matches zero or more characters;
- `_` matches exactly one character;
- matching is case-sensitive.

The current implementation translates `%` to Java regular-expression `.*` and `_` to `.`. Other regular-expression metacharacters such as `.`, `+`, `*`, `[`, `]`, `(`, `)`, `?`, `^`, `$`, `|`, and backslash retain their regular-expression meaning instead of being escaped automatically. Use `like` with ordinary literal text plus `%`/`_`; use `==` for exact text.

Comparison first recognizes boolean literals. If both operands are valid decimal numbers, ATT compares them as arbitrary-precision decimals, including ordinary Excel strings such as `"100"`; numeric equality also uses this coercion, so `1.0 == 1` is true. Otherwise ATT compares the rendered strings lexicographically and case-sensitively. A present blank value compares as an empty string; a missing Context path is `ATT-CTX-001`, not an implicit null/empty value. Use `is null` only for a path that exists with a null value, and use `#{number(...)}` when invalid numeric text should become an explicit evaluation error instead of a string comparison.

### Built-in functions

Built-ins are called with `#{...}`. Canonical names use framework-owned `str.*`, `date.*`, `file.*`, and `misc.*` packages. Legacy flat names remain aliases for compatibility. Tool groups use the same package-like `group.tool` shape; configured Tools cannot claim a built-in package root or any canonical/legacy built-in name.

| Function | Purpose | Example |
|---|---|---|
| `str.upper` | Convert text to upper case | `#{str.upper(value=${EXEC.INPUT.currency})}` |
| `str.lower` | Convert text to lower case | `#{str.lower(value=${EXEC.INPUT.channel})}` |
| `str.trim` | Remove surrounding whitespace | `#{str.trim(value=${EXEC.INPUT.reference})}` |
| `str.ltrim` / `str.rtrim` | Remove leading/trailing whitespace | `#{str.ltrim(${EXEC.INPUT.reference})}` |
| `str.length` | Return text length | `#{str.length(value=${EXEC.INPUT.reference})}` |
| `str.concat` | Concatenate arguments in call order | `#{str.concat(a='PAY-', b=${EXEC.INPUT.caseId})}` |
| `str.substr` | Extract text from a zero-based start | `#{str.substr(${EXEC.INPUT.reference}, 0, 8)}` |
| `str.indexOf` | Return zero-based position or `-1` | `#{str.indexOf(${EXEC.INPUT.reference}, '-')}` |
| `str.contains` | Test literal substring membership | `#{str.contains(${EXEC.INPUT.message}, 'SUCCESS')}` |
| `str.startsWith` / `str.endsWith` | Test a literal prefix/suffix | `#{str.startsWith(${EXEC.INPUT.reference}, 'PAY')}` |
| `str.replace` | Replace every literal target | `#{str.replace(${EXEC.INPUT.reference}, '-', '')}` |
| `str.lpad` / `str.rpad` | Pad to a minimum length | `#{str.lpad(${EXEC.INPUT.sequence}, 8, '0')}` |
| `str.repeat` | Repeat a value 0–10000 times | `#{str.repeat(3, '9')}` |
| `date.sysdate` | Return system-zone date, optionally formatted | `#{date.sysdate('yyyyMMdd')}` |
| `date.systimestamp` | Return system-zone timestamp, optionally formatted | `#{date.systimestamp(format='yyyyMMdd-HHmmssXXX')}` |
| `date.format` | Format an ISO-8601 value | `#{date.format(${EXEC.INPUT.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `date.add` | Add a calendar/time amount | `#{date.add(${EXEC.INPUT.businessDate}, 1, 'day')}` |
| `file.exists` | Test whether a regular file exists | `#{file.exists(${EXEC.INPUT.requestFile})}` |
| `file.directoryExists` | Test whether a directory exists | `#{file.directoryExists(${EXEC.OUTPUT_DIR})}` |
| `file.size` | Return regular-file size in bytes | `#{file.size(${EXEC.INPUT.requestFile})}` |
| `file.mkdirs` | Create a directory tree and return its absolute path | `#{file.mkdirs(${EXEC.INPUT.archiveDirectory})}` |
| `file.copy` | Copy a regular file and return the target path | `#{file.copy(${EXEC.INPUT.requestFile}, ${EXEC.INPUT.backupFile}, true)}` |
| `file.move` | Move a regular file and return the target path | `#{file.move(${EXEC.INPUT.sourceFile}, ${EXEC.INPUT.targetFile})}` |
| `file.delete` | Delete a non-directory file | `#{file.delete(${EXEC.INPUT.temporaryFile}, true)}` |
| `misc.string` | Convert a value to text | `#{misc.string(value=${EXEC.INPUT.amount})}` |
| `misc.number` | Parse and normalize a number | `#{misc.number(value='12.50')}` |
| `misc.boolean` | Convert true/false, yes/no, or 1/0 | `#{misc.boolean(yes)}` |
| `misc.coalesce` | Return first non-blank value | `#{misc.coalesce(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.nvl` | Return a default for null/empty text | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | Select one of two values from a boolean | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | Return one of 1–1000 input values | `#{misc.randomChoice('A', 'B', 'C')}` |
| `misc.dbText` | Format one stable typed DB result as SQL*Plus-style text | `#{misc.dbText(${EXEC.ACTIONS.queryOrders.output.result})}` |
| `prettyPrint` / `format.pretty` | Deterministically format a Map/List/array tree | `#{prettyPrint(${EXEC.ACTIONS.queryOrders.output.result})}` |

The single-value `str.upper/lower/trim/ltrim/rtrim/length` and `misc.string/number/boolean` functions accept either `value=...` or one unnamed value. Other built-ins accept either their documented names or a complete positional list; do not mix named and positional arguments in one call. Case conversion is locale-independent. `misc.number` rejects non-numeric input and removes unnecessary trailing zeroes. `misc.boolean` accepts true/false, yes/no, and 1/0. `str.concat` treats null as empty; `misc.coalesce` skips null and whitespace-only values and returns empty when none qualifies. `misc.nvl` tests null/empty without trimming. `misc.iif` accepts the same boolean text forms and resolves all three arguments eagerly. `str.repeat` requires an integer count from 0 through 10000 and repeats the complete value.

`substr(value, start[, length])` uses zero-based UTF-16 indexes. A negative start counts from the end; an out-of-range start or negative length is an error, while an overlong length stops at the end. `indexOf` is case-sensitive, accepts an optional zero-based `fromIndex`, and returns `-1` when absent. Match and replacement functions are case-sensitive and literal, not regular expressions. Padding defaults to one space, never truncates an already long value, rejects an empty pad, and limits target length to 10000.

`sysdate()` returns `yyyy-MM-dd`. `systimestamp()` returns `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`; both use the JVM system zone at invocation time. Each accepts zero arguments or one positional/named `format` argument using a locale-independent Java `DateTimeFormatter` pattern. Blank, invalid, or incompatible patterns are `ATT-BUILTIN-001` errors that identify the function, argument, supplied value, and formatter cause. `formatDate` accepts ISO local dates, local date-times, offset/zoned timestamps, and UTC instants, then applies the same pattern rules. `zoneId` accepts an IANA name such as `Asia/Hong_Kong` or an offset such as `+08:00`; it converts instant/offset/zoned values and attaches a zone to a local date-time. `dateAdd` preserves the input ISO shape and accepts singular/plural `year`, `month`, `week`, `day`, `hour`, `minute`, `second`, or `millisecond`; incompatible combinations such as hours plus a date-only value are errors.

Filesystem built-ins resolve relative paths against the ATT JVM working directory and return normalized absolute paths from create/copy/move operations. Existence and size functions accept only their documented regular-file or directory type and do not follow the final symbolic link. Copy and move reject symbolic-link sources/targets, create missing target parents, and default `overwrite` to `false`; an existing target is an error unless `overwrite=true`. `deleteFile` rejects directories, may delete a file or symbolic link itself, and defaults `missingOk` to `false`. Filesystem errors produce action ERROR and these in-process operations create no TOOL process artifacts.

`randomChoice` accepts either a complete positional list or consistently named values, preserves the selected value's type, and rejects zero, more than 1000, or mixed-style inputs. Selection is deliberately non-deterministic and is intended for test-data variation, not cryptography or reproducible sampling.

`dbText` accepts exactly one positional argument or named `value`. The value must be a stable query/update result returned by a direct DB Action, DB expression, or DB-backed Tool. It uses exactly the same deterministic formatter as direct DB Action `saveAs.format: text` and has no JDBC, transaction, connection, or cache side effects.

`prettyPrint` accepts exactly one positional argument or named `value`. It formats Maps, Lists, Iterables, arrays, scalars, and null with two-space indentation. Linked and sorted Maps retain their iteration order; other Map keys are sorted by text. Strings are quoted and escaped, cycles and excessive depth are marked, output is bounded, and the source object is not modified.

Use built-ins for in-process transformations, time values, DB-result formatting, and simple local file operations; use tools when filesystem work needs process evidence or for network, database, system integration, or complex reusable logic. Built-ins occupy reserved framework packages. V2.6 retains an internal provider boundary for a future release, but configuration cannot load custom Java classes. Invalid arguments produce action ERROR.

Typical expressions:

```yaml
assert: "${EXEC.INPUT.amount} > 0"
assert: "${EXEC.ACTIONS.callApi.output.result.status} == ${EXEC.INPUT.expectedStatus}"
assert: "${EXEC.ACTIONS.callApi.output.result.message} like 'PAYMENT%SUCCESS'"
assert: "(${EXEC.INPUT.channel} == 'MOBILE') and (${EXEC.INPUT.amount} <= 1000)"
```

## 08 Report Reference

### Run directory

```text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── workbooks/
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
└── <CaseID>/...
```

Run and Case IDs appear unchanged after validation. A run is completed only when its `run.yaml` state is `COMPLETE`; interrupted work remains directly below its reserved Run ID for debugging.

### Human HTML report

`report/index.html` is the primary end-user report. It can be opened without a web server. Groups are summarized by `workbookId.groupId`; the interface labels `groupId` as Sheet because it maps to one physical sheet. Cases supports Workbook/Sheet/Status dropdowns, case-insensitive search over workbook/group/full Case ID/tags, and ascending/descending sorting from every column heading. Duration sorting is numeric.

An expanded case contains the full Case ID and name, status and duration, Expected and Actual results, one row per recorded action result, a bounded detailed execution-log preview, and explicit `.log`/`case.yaml` artifact links. Each Action Results row has independent Stage, Action, Description, Status, and Message columns; Description is the final rendered action description and is also persisted in `run.yaml` and CI JSON. `report.html.caseLogInlineLimitBytes` controls the head/tail preview; `0` keeps only the artifact link. For compatibility, Expected remains the ordered LF-joined non-blank assert descriptions and `expected` values; Actual is the ordered LF-joined non-blank runtime `actual` values. `case.yaml` holds the complete structured final Stage/Template/Action/Tool/DB state. Depending on what ran, these artifacts include selected templates, executed or skipped stages/actions, assertion messages, Tool argv/stdout/stderr/retry evidence, DB source/parameter/result/finalization evidence, diagnostics, and saved payload/Tool/DB-output paths. Workbook ID, group ID, and tags are persisted per case in `run.yaml`, so `report --run-id` regenerates equivalent controls and grouping.

`report/junit.html` is a human-readable JUnit projection. It displays counts and one row per testcase with status, duration, and embedded case-log content or a relative artifact link.

### Result workbook

ATT copies the source workbook and appends configured result columns using `report.mode: append-to-copy`. Set `report.mode: none` for CI or large runs that do not need a copied workbook. Global `report.fileNamePattern` controls the copy filename. Sidecar `report.columns` changes workbook labels only. Supported mappings include `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, and `runTime`; Expected/Actual cells retain LF characters and use wrapped text. Row matching reads the Case ID with the same Excel `DataFormatter` and whitespace normalization as testcase loading, so displayed formats such as numeric leading zeroes identify the same Case during execution and report writing.

### JUnit XML

Each ATT case maps to one `<testcase>`:

| ATT status | JUnit representation |
|---|---|
| PASS | no failure child |
| FAIL | `<failure>` |
| ERROR | `<error>` |
| SKIPPED | `<skipped>` |
| INVALID | `<error type="ATTValidationError">` |

Text is XML-escaped. JUnit XML and HTML use `report.junit.caseLogEmbedThresholdBytes`. Logs at or below the threshold are embedded; larger logs use a relative link. `0` always links.

### CI JSON summary

`ci/summary.json` uses `schemaVersion: att-ci-summary/v2.1` and contains ATT/run IDs, environment, timing, aggregate status/counts, duration statistics, per-case records, diagnostic counts, report/artifact paths, and the input-manifest hash.

### Run manifest and reproducibility

`run.yaml` uses `schemaVersion: att-run/v2.1` and records ATT/build identity, Java/OS/locale/timezone, validation mode, environment, timestamps, status/summary, output paths, and SHA-256 inputs for effective configuration, tool-group files, call-backed Tool SQL files (`tool-sql`), workbook, sidecar, resolved templates/payloads, package-local tool files, and schema/catalog version.

### Documentation, archive, and clean

| Command | Output/behavior |
|---|---|
| `docs` | Generates searchable offline package documentation at `build/docs/index.html`; Testcases are grouped by workbook and Sheet |
| `report --run-id <id>` | Regenerates both HTML reports from completed evidence |
| `build` | Archives the latest completed run without executing tests |
| `clean` | Removes configured output directory, `build/docs`, and `build/att-*.tar.gz` |

The build archive contains reports, workbooks, case logs, referenced artifacts, redacted configuration/template snapshots, manifest, and hashes. Exact YAML keys named `password`, `token`, `secret`, or `authorization` are redacted case-insensitively; this is not a general log/result redactor.

The Testcases section renders one table per Sheet below each workbook heading. The Sheet column is omitted because the group heading supplies that context. Each table includes Expected Result, formed in stage/action order from every assert action's validation-time `description` and `expected`; unresolved runtime placeholders remain visible and line endings are normalized to LF.

Clean never removes testcase, template, tool, configuration, documentation, schema, or other source files. It canonicalizes paths, rejects source/package roots and external symlink targets, is idempotent, and reports what it removed.

## 09 Troubleshooting

### Start with validation

Run this after every workbook, sidecar, template, or tool change:

```sh
./att.sh validate --package
```

Then use the diagnostic code and structured location. Do not automate against message text.

| Category | Typical cause | Corrective action |
|---|---|---|
| `ATT-TC` | Missing/stale snapshot, sidecar/sheet/header error, or duplicate Case ID | Check snapshot/basenames, sheet mapping, effective headers, and full IDs |
| `ATT-CTX` | Unknown or ambiguous Context path | Inspect requested/current/missing fields, nearest suggestion, or canonical candidates |
| `ATT-STG` | Blank required selector, invalid selector YAML, duplicate stage key | Check selector form, `name`, aliases, and required flag |
| `ATT-TPL` | Unknown/duplicate template, invalid action or payload | Check symbolic name/full path, descriptor, action type, and local files |
| `ATT-CFG` | Unknown field, duplicate key, wrong schema/type/enum | Compare with Chapter 6 and remove unsupported fields |
| `ATT-TOOL` | Unknown/missing argument, process or parse failure | Compare call contract, inspect exit code/stdout/stderr/raw output |
| `ATT-PATH` | Illegal ID or escaping path | Remove illegal characters and keep content below configured roots |
| `ATT-RUN` | Timeout, non-zero exit, render/runtime failure | Inspect case log and action/tool evidence |

### Common questions

#### Why is the Case ID rejected although Excel displays it correctly?

ATT imports displayed cell text, then applies strict ID safety checks. Check hidden leading/trailing whitespace, trailing `.`, path characters, controls, and Windows device names. Store identifiers as text to preserve leading zeroes.

#### Can two sheets both contain `TC001`?

Yes. Give sheets different group IDs, producing IDs such as `payment.payment.TC001` and `payment.batch.TC001`.

#### Why did `N/A` become empty?

ATT normalizes `N/A`, `NA`, `NULL`, `NONE`, empty, and whitespace-only values to blank before data mapping and stage selection.

#### Why does a Context variable fail?

ATT treats an absent path as an authoring/runtime error instead of silently rendering it as empty. Follow `ATT-CTX-001` and its `requestedPath`, `currentNode`, `missingSegment`, and nearest suggestion to check the case-sensitive scope, physical header/alias, stage key, action ID, and availability point. A suffix shorthand must identify exactly one readable logical path; `ATT-CTX-002` lists every conflicting candidate so you can lengthen it. The diagnostic intentionally omits the full Context tree. A declared optional field with a blank value is still valid and renders as the empty string.

#### Why did a FAIL become ERROR?

A false assertion is FAIL. Invalid expression syntax/navigation, tool failure, timeout, parse failure, I/O failure, or runtime exception is ERROR. Inspect the action evidence rather than only the final aggregate status.

#### Why did a tool run more than once?

Its action used retry and received an eligible non-zero exit code. Inspect the attempt list and final action record in the case log.

#### Can I use a shell pipeline in `command`?

No. ATT passes `|`, `>`, and `<` literally. Put shell behavior inside a reviewed tool script.

#### Why does a required array argument reject `[]`?

Required validation happens before argv expansion. An empty typed List is missing input; pass at least one scalar item or make the argument optional.

#### Should I use package or selected validation?

Use selected mode for fast local feedback. Use package mode before release, CI promotion, or sharing a package.

#### Can reports be opened without a server?

Yes. Keep the generated run directory together so relative artifact links continue to work.

#### Does build execute tests again?

No. It archives one completed persisted run.

#### Why does `att.bat` ask for Maven, or why does a `.sh` tool fail on Windows?

In a binary release, `att.bat` finds `lib\att-*.jar` and only requires Java 8+. In a source tree it compiles with Maven when Maven is on `PATH`; without Maven, previously compiled `target\classes` must exist. Use `att.bat version` to confirm the launcher before validating the package.

The launcher makes ATT itself cross-platform; it cannot translate external tool executables. Configure a Windows-compatible `.bat`, `.cmd`, PowerShell script (with an explicit `powershell`/`pwsh` argv), or native executable instead of a POSIX-only `.sh` command. PATH validation follows Windows `PATHEXT`, so names such as `pwsh` can resolve `pwsh.exe`. Keep argument contracts and stdout output formats identical when maintaining platform variants.

#### Why did ATT say it will use mwiede/jsch, or why did Java SSH algorithm negotiation fail?

ATT uses the local `ssh` command when it is executable on `PATH`. If it is absent, ATT prints `local ssh command not found; ATT will use Java SSH library mwiede/jsch` and opens a Java exec channel instead. This is an automatic fallback, not a remote connectivity test.

The fallback is deliberately minimal: ATT includes `com.github.mwiede:jsch:2.28.2` but does not bundle Bouncy Castle. It requires a readable non-symbolic-link `~/.ssh/known_hosts` for strict host verification. It does not read `~/.ssh/config` or automatically use the OpenSSH agent; configure an unencrypted or otherwise non-interactively readable `identityFile`. Password and interactive passphrase prompts remain unsupported.

Algorithm availability depends on the Java runtime:

| Algorithm | Java fallback limitation | Preferred solution |
|---|---|---|
| `ssh-ed25519`, `ssh-ed448` | Require Java 15+, or a Bouncy Castle provider | Prefer local OpenSSH or Java 15+; otherwise have an administrator add approved `bcprov-jdk18on` to the runtime classpath |
| `curve25519-sha256`, `curve448-sha512` | Require Java 11+, or Bouncy Castle | Prefer local OpenSSH or Java 11+; otherwise use an approved Bouncy Castle provider |
| `chacha20-poly1305@openssh.com` | Requires Bouncy Castle on every Java version | Prefer local OpenSSH, enable an AES-GCM/CTR cipher on the server, or add an approved Bouncy Castle provider |
| RSA/SHA-1 `ssh-rsa` signatures | Disabled by default by mwiede/jsch | Update the server to RSA/SHA-2 (`rsa-sha2-256`/`rsa-sha2-512`) or another modern host/user-key algorithm; do not re-enable SHA-1 except as a reviewed temporary legacy measure |

When negotiation fails, first run the same connection with local `ssh -v` to identify the host-key, key-exchange, cipher, or user-key mismatch. Prefer upgrading Java or the server's algorithm set over weakening JSch defaults. The authoritative compatibility notes and configurable `jsch.kex`, `jsch.server_host_key`, `jsch.cipher`, and `jsch.mac` system properties are documented in the [mwiede/jsch README](https://github.com/mwiede/jsch). ATT does not change those secure defaults.

### Security reminders

Do not place passwords, tokens, private keys, or sensitive customer data in workbook cells, template descriptors, command strings, stdout, or stderr. Prefer approved secret injection inside tool scripts. Review reports and archives before sharing.

## 10 Architecture for Maintainers

This chapter explains the behavior that users normally do not need while authoring cases but maintainers need when modifying validation, execution, persistence, or reports.

### Ownership model

```text
case owns ordered stages
stage defines a template-selector column and owns stage-private data
the current row's selector cell names the template to resolve
template owns ordered actions
tool action invokes one independent global tool contract through declared arguments
```

The authoritative persisted runtime tree has one `CASE` root. Convenience scopes such as `ACTIONS`, `TOOL`, and `DB` do not create alternative persisted roots.

### Validation pipeline

ATT uses Draft 2020-12 schemas before semantic checks. Validation then resolves workbook mappings, selectors, templates, payloads, expressions, tools, argument contracts, identifiers, paths, and package integrity.

Package mode discovers everything below the configured roots. Selected mode validates only the immutable dependency closure selected for execution. Validation completes before external tools or final run publication.

### Execution and aggregation

The runner plans selected cases and executes stage/template/action order deterministically. `onFailure` controls continuation but does not suppress result severity. Aggregation is exact and shared by all consumers:

```text
if any ERROR exists: ERROR
else if any INVALID exists: INVALID
else if any FAIL exists: FAIL
else if any PASS exists: PASS
else: SKIPPED
```

Therefore PASS + SKIPPED is PASS, all SKIPPED is SKIPPED, and a selection that resolves to no cases is a command error rather than a SKIPPED run.

Report, manifest, CLI summary, CI JSON, JUnit XML, JUnit HTML, and process exit code must derive from the same aggregate model.

### Run lifecycle

After validation and planning, ATT atomically reserves:

```text
<outputDirectory>/<RunID>/
```

Evidence is written there and can be inspected while Actions execute. After all required outputs are finalized, ATT writes a `COMPLETE` manifest and atomically replaces `latest-run.yaml`. An interrupted run stays at the reserved path without a completed manifest and is not eligible for `report`, `build`, `rerun-failed`, or latest-run selection. A pre-existing Run ID is rejected before execution; move or clean an incomplete directory before retrying that ID.

### Process safety

ATT constructs argv directly and uses no implicit shell. Local stdout and stderr are drained concurrently, retained in memory only as bounded head/tail previews, and streamed through bounded temporary spools into the Case log. The spools are removed after logging or explicit `saveAs`; ordinary runs create no `process-output` file or directory. Evidence records original byte counts and truncation flags. Timeout termination stops the managed process according to platform support and retains the same bounded evidence. Structured parsers reject malformed/ambiguous input and XML external-resource features.

`run --profile` writes `performance.json` beside `run.yaml`. It records configuration load, validation, plan, Case execution, result-workbook, HTML/CI report, and input-hash timings; selected/completed Case counts; schema/Template/payload cache loads and hits; process-output bytes/truncations; and a completion-time heap snapshot. It is diagnostic evidence, not a stable CI schema contract.

Workbook import uses Apache POI `DataFormatter` for ordinary cells and deliberately does not create a `FormulaEvaluator`; formula expressions, not cached results, enter Context.

### CI and parallel execution

| Concurrent operation | Contract |
|---|---|
| Two runs use the same Run ID | Atomic directory reservation allows only one to start; the other fails without overwriting evidence. |
| Multiple runs update `latest-run.yaml` | Each writes its completed manifest first; the last completion wins the atomic pointer update. Completion order, not start order, determines latest. |
| `build` and `run` execute together | Build pins one completed latest-run/manifest pair and ignores any run without a `COMPLETE` manifest. |
| `report` and `clean` execute together | This destructive race is unsupported. Report fails rather than producing a partial result; serialize report/archive/clean jobs sharing one output root. |

Use `--allow-parallel-runs` only to permit multiple ATT processes to share one output root; it does not add Case workers inside one run. `--parallel` remains a deprecated compatibility alias. Use separate `--output-dir` values when parallel jobs need independent run history, cleanup, or latest-run behavior.

### Path and identifier safety

Validated Run ID and Case ID map directly to directory names. Every write resolves against an intended root, normalizes the path, resolves relevant existing symlinks, and verifies strict containment. Logical CLI identifiers are never accepted as arbitrary filesystem paths.

### Reproducibility and versioned outputs

The completed manifest captures runtime identity, effective inputs, hashes, selected cases, summary, and output paths. Validation JSON, run manifest, and CI summary have explicit `schemaVersion` values. JUnit XML is constrained by XSD. Consumers should validate the declared version rather than infer structure.

### Maintainer release checklist

- Run the full automated test suite and require all tests to pass.
- Run `validate --package` against representative packages.
- Verify FAIL/ERROR/INVALID aggregation and exit codes across CLI and all reports.
- Verify JSON/XML parsing, repeated XML children, attributes, and namespaces.
- Verify timeout and retry evidence, including exhausted and later-success cases.
- Verify Run ID collision, atomic completion, latest-run update, and interrupted runs.
- Verify report/build/clean boundaries and concurrent-command behavior.
- Verify schemas, examples, generated documentation, and this manual remain aligned.
