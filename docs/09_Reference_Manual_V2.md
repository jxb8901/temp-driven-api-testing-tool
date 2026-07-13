# ATT V2.1 User Manual and Reference

Author: Jeffrey + ChatGPT
Version: 2.1
Status: Normative end-user documentation

This manual is designed to be read in two ways:

- If ATT is new to you, read Chapters 1–4 in order. They take you from the first test case to practical development patterns.
- If you already use ATT, Chapters 5–9 are the daily command, configuration, expression, report, and troubleshooting reference.
- Chapter 10 explains internal behavior for maintainers and users diagnosing lifecycle or integration problems.

Where an older V2.0 example conflicts with this manual, this manual wins.

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

ATT is an offline, template-driven API test runner. Test data lives in Excel, reusable execution logic lives in template directories, and external capabilities are registered as tools.

```text
Workbook row → Test case → Ordered stages
Stage → Template-selector column → Current row's selector cell → Template
Template → Ordered actions → Configured tool (for tool actions)
```

The four concepts you need first are:

| Concept | What it owns |
|---|---|
| Test case | One workbook row, case-level data, tags, and ordered stages |
| Stage | Template selection, stage-private data, execution condition, and failure handling |
| Template | A reusable ordered list of actions |
| Tool | A globally configured external executable with named inputs and one declared output format |

An action can render a payload, call a tool, assert an expression, or write a structured log. ATT validates the selected package before executing external tools and records the resulting evidence below one completed run directory.

### What V2.1 guarantees

- Configuration is strict. Unknown fields, wrong types, invalid enum values, duplicate YAML keys, and invalid action shapes are errors.
- Every workbook has a same-basename YAML sidecar.
- Every template is a directory containing `template.yaml`.
- `validate --package` checks the whole package; `validate --selected` checks only a selected dependency closure.
- Run ID and Case ID are validated and then used directly as output directory names.
- A final run directory is published only after the run is complete.
- FAIL, ERROR, INVALID, SKIPPED, and PASS have stable aggregation and exit-code meanings.
- JSON, XML, JUnit XML, JUnit HTML, and CI JSON outputs have versioned contracts.

### Package layout

```text
att-package/
├── att.sh
├── config/config.yaml
├── testcase/
│   ├── payment.xlsx
│   └── payment.yaml
├── templates/
│   └── payment/
│       ├── template.yaml
│       └── request.tmp.json
├── tools/
├── schemas/
└── output/
```

You normally edit `config/config.yaml`, workbook/sidecar pairs, templates, payloads, and tool scripts. ATT owns generated content below the configured output directory and its documented build locations.

The global `testcase.root` setting defaults to `testcase`. Discovery is recursive, and an adjacent same-basename YAML/XLSX pair defines one testcase set at any depth.

## 02 Quick Start

This example creates one payment test that renders JSON, invokes a tool, and verifies the returned status.

### Step 1: configure ATT

Create `config/config.yaml`:

```yaml
schemaVersion: att-config/v2.1
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
    command: "./tools/invoke_payment_api.sh '${requestFile}' '${environment}'"
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

`command` is tokenized into process arguments; it is not executed by a shell. Declared arguments are referenced directly, for example `${requestFile}`.

### Step 2: create the workbook and sidecar

Create `testcase/payment.xlsx` with one header row:

| Case ID | Tags | Amount | Expected Status | Invoke Template |
|---|---|---:|---|---|
| TC001 | smoke,payment | 100 | SUCCESS | PAYMENT_INVOKE |

Create the adjacent `testcase/payment.yaml`:

```yaml
schemaVersion: att-sidecar/v2.1
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
schemaVersion: att-template/v2.1
name: PAYMENT_INVOKE
description: Render, invoke, and verify a payment
actions:
  renderRequest:
    type: render
    payload: request.tmp.json
    saveAs: request.json
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"
  assertStatus:
    type: assert
    expression: "${ACTIONS.callApi.output.status} == ${CASE.expectedStatus}"
```

Create `templates/payment/request.tmp.json`:

```json
{
  "caseId": "${CASE.caseId}",
  "amount": "${CASE.amount}"
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

#### Workbook and sidecar relationship

Every `.xlsx` workbook requires a YAML file with the same basename in the same directory:

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
```

The sidecar maps Excel structure into ATT concepts. It owns the sheet mapping, headers, case data, ordered stages, optional report-column labels, and an optional workbook timeout.

```yaml
schemaVersion: att-sidecar/v2.1
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

ATT reads the displayed text of ordinary Excel cells but does **not** calculate formulas. A formula cell is imported as its formula expression, such as `=A2+B2`; ATT does not ask Excel to recalculate or consume the cached result.

Do not use a formula cell when a calculated value must be consumed by a template, assertion, or tool. Recalculate in Excel and paste the result as a value, or calculate it in a dedicated ATT step.

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

ATT does not concatenate parent and child labels. Every configured effective header must exist exactly once. Duplicate or missing headers are validation errors. Result columns are written to the final header row.

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
schemaVersion: att-template/v2.1
name: PAYMENT_INVOKE
description: Render and invoke a payment request
actions:
  renderRequest:
    type: render
    payload: request.tmp.xml
    saveAs: "${CASE.caseId}-request.xml"
    assert: "${ACTIONS.renderRequest.output} != ''"
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"
    saveAs: "${CASE.caseId}-response.json"
    overwrite: false
    assert: "${ACTIONS.callApi.output.status} == 'SUCCESS'"
  recordResult:
    type: log
    level: INFO
    message: "Payment ${CASE.caseId} completed"
```

`schemaVersion`, `description`, and a non-empty ordered `actions` map are required. `name` is optional when the template is always selected by full path; reusable templates should have a globally unique symbolic name.

#### Action types

| Type | Purpose | Required fields | Common result |
|---|---|---|---|
| `render` | Render a UTF-8 payload | `type`, `payload`, `saveAs` | `output`, `outputFile` |
| `tool` | Invoke a configured external tool | `type`, `call` | parsed `output`, process evidence |
| `assert` | Evaluate a boolean expression | `type`, `expression` | PASS/FAIL or evaluation ERROR |
| `log` | Write a rendered structured message | `type`, `message` | message and rendered fields |

Actions run in YAML order. Action IDs are unique within the template and cannot contain a dot. Every action may define `description` and `onFailure: stop|continue`.

Action validation is type-specific. A render action cannot contain tool/assert/log fields; retry and timeout are valid only for tool actions; an assert action requires an expression; a log action may use `level` and `fields`. Unsupported fields are errors rather than ignored values.

Payload and output paths must remain below their intended template/case-log roots. Template names, paths, payloads, and descriptions support UTF-8 and are case-sensitive.

### 3.3 Tool

A tool is an external capability configured once under `config.yaml` and called by template actions using named arguments.

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered request
    command: "./tools/invoke_payment_api.sh '${requestFile}' '${environment}'"
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

The action call must use the configured key and named arguments:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"
```

Unknown, duplicate, or missing required arguments fail validation. Argument metadata documents and validates the contract; it does not inject values automatically.

#### Command processing

ATT first tokenizes the static command template and then resolves placeholders inside the resulting tokens. An ordinary declared argument always remains one atomic argv value, regardless of spaces, quotes, backslashes, leading dashes, or shell-like characters in its value. A final argument with `delimit` is the only form that may intentionally expand into zero or more argv values. Resolved values are never tokenized again. ATT does not invoke `/bin/sh`, `cmd.exe`, PowerShell, or another shell.

| Command text | Effect |
|---|---|
| whitespace outside quotes | separates argv elements |
| `'...'` or `"..."` | groups fixed text as one argv element; quotes are removed |
| backslash outside single quotes | escapes the next character for ATT tokenization |
| `|`, `>`, `<`, `;`, `&`, `$`, `(`, `)`, `*`, `?` | literal characters, not shell operators |
| unmatched quote or final escape | error |

This configuration:

```yaml
command: "./tools/send.sh '${requestFile}' --label 'Payment regression'"
```

produces these logical arguments:

```text
./tools/send.sh
<request-file-value>
--label
Payment regression
```

The text `status|PENDING` remains one literal argument and `>result` does not redirect output. Quotes in the static template group template text only; they are not an escaping requirement for resolved values.

Prefer the shortest declared-argument placeholder, such as `${keywords}`; use `${input.keywords}` when an explicit namespace improves clarity. Both forms are case-sensitive and must exactly match the argument key. `${TOOL.input.keywords}` remains supported but is not the preferred authoring style. Tools write their raw result to stdout and diagnostics to stderr; ATT records input/stdout/stderr in the case log.

Global tool commands may reference only their declared arguments. They cannot reference `${CASE...}`, `${ACTIONS...}`, or other runtime Context scopes. Pass runtime data explicitly in the action call, then reference that declared argument in the command. This keeps the global tool independent and its dependencies statically validateable.

#### Input, output, timeout, and status

Available command placeholders are:

| Placeholder | Meaning |
|---|---|
| `${argument}` | Preferred direct reference to a declared argument key |
| `${input.argument}` | Explicitly namespaced reference to the same declared argument |
| `${TOOL.input.argument}` | Supported explicit alias for the same declared argument |

ATT parses stdout as raw output. `output` may be `txt`, `yaml`, `json`, or `xml`; default is `txt`. A tool action may set `saveAs` to persist raw stdout in the case log directory. The relative filename may contain `${...}` Context expressions; template authors must keep it unique in that directory. `overwrite` defaults to `false`, so an existing target is an error unless overwrite is enabled. Every retry writes the same resolved target and replaces the earlier attempt's stdout, leaving the final attempt as the artifact. Without `saveAs`, ATT creates no dedicated tool-output file, while input, argv, stdout, stderr, status, parsed output, and retry evidence remain in the persisted case evidence.

Exit code 0 plus successful parsing is PASS. A non-zero exit code, timeout, process failure, or structured-output parse failure is ERROR. Command, inputs, stdout, stderr, raw output, duration, exit code, and parsed output are retained as evidence.

Timeout precedence is:

```text
tool action timeoutMs → workbook sidecar timeoutMs → global timeoutMs → 10000 ms
```

Every configured timeout is an integer from 1 to 3600000 milliseconds.

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

An empty selection is an error. `--rerun-failed` is itself a valid selection and reads FAIL/ERROR Case IDs from the latest completed persisted run. Additional `--case`, `--tag`, and `--exclude-tag` filters narrow that set. Missing history, no prior FAIL/ERROR cases, or no currently discoverable case matching the saved IDs is a command error. The current workbook, sidecar, template, and tool definitions are validated and executed; ATT does not replay the old input snapshot.

#### Execute

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --tag smoke
./att.sh run --all --case payment.payment.TC001 --run-id SIT-001
./att.sh run --rerun-failed
./att.sh run --rerun-failed --tag payment
./att.sh run --all --dry-run
```

`--dry-run` validates/plans and records selected cases as SKIPPED without invoking tools. `--fail-fast` stops scheduling further cases after the first FAIL or ERROR. `--output-dir` overrides the output root for one command.

`--quiet` suppresses normal progress/completion output. `--verbose` adds safe run/suite/case/stage/action lifecycle information but never prints payloads, commands, arguments, environment variables, stdout, or stderr. The two options are mutually exclusive.

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

Each expanded case shows its full Case ID, name, status, duration, expected and actual results, action-result rows, the persisted Stage/Template/Action/Tool Context tree, the detailed execution log, and links to persisted case artifacts. The tree and log contain resolved templates, executed or skipped work, tool inputs/argv/stdout/stderr/parsed output, retry attempts, and error evidence when those events occurred.

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
  expression: "${ACTIONS.callApi.output.status} == 'SUCCESS'"
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
  expression: "${ACTIONS.callApi.output.attributes.requestId} == 'R-100'"
assertStatusText:
  type: assert
  expression: "${ACTIONS.callApi.output.Status.text} == 'SUCCESS'"
assertStatusCode:
  type: assert
  expression: "${ACTIONS.callApi.output.Status.attributes.code} == '00'"
assertSecondMessage:
  type: assert
  expression: "${ACTIONS.callApi.output.Messages.Message[1].text} == 'review later'"
assertSecondSeverity:
  type: assert
  expression: "${ACTIONS.callApi.output.Messages.Message[1].attributes.severity} == 'WARN'"
```

With `xml.namespaceMode: ignore`, keys use local names. With `preserve`, namespace-aware names use Clark notation such as `{urn:payment}Status`. Keys containing braces, colons, dots, or spaces use quoted brackets, for example `${ACTIONS.callApi.output['{urn:payment}Status'].text}`. XML DTD, external entities, XInclude, and external resources are disabled.

A leaf without attributes becomes `ElementName: text`. Any element with attributes retains their names under `attributes`; therefore `<Item id="123"/>` becomes `Item: {attributes: {id: "123"}}`. Repeated same-name siblings become a list in source order; a singleton stays scalar/map. Empty elements become an empty string unless they have attributes. Text and CDATA are concatenated, trimmed at the boundary, and stored as `text` when child elements also exist. Comments and processing instructions are ignored. With namespace preservation, element and non-namespace attribute names use Clark notation, keeping equal local names from different namespaces distinct.

### Pass a list as separate process arguments

Only the final declared tool argument may use `delimit`:

```yaml
tools:
  grepFromAppLogs:
    name: Grep application logs
    description: Search one or more keywords
    command: "./tools/grep_from_app_logs.sh '${logFile}' ${keywords}"
    output: yaml
    arguments:
      logFile: {name: Log File, description: Source log, required: true}
      keywords: {name: Keywords, description: Comma-delimited values, required: true, delimit: ","}
```

```yaml
grepLogs:
  type: tool
  call: "#{grepFromAppLogs(logFile=${ACTIONS.getLogs.outputFile}, keywords='PAYMENT,POSTED')}"
```

The final value expands into two separate arguments, `PAYMENT` and `POSTED`. Surrounding whitespace is trimmed; empty middle elements are preserved; blank markers produce an empty array. A required blank value fails before expansion. Spaces, quotes, backslashes, leading dashes, and `|><` inside an item remain literal data. The delimited placeholder must occupy one complete static command token; quoting it in the template is allowed but unnecessary because static tokenization happens before value expansion.

### Retry selected exit codes

Retry belongs to a tool action, not to a workflow or arbitrary stage:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"
  timeoutMs: 30000
  retry:
    maxAttempts: 3
    retryOn: [EXIT_CODE]
    exitCodes: [1, 75]
```

`maxAttempts` includes the first attempt and defaults to 1. V2.1 supports only `EXIT_CODE`; if `exitCodes` is omitted, any non-zero exit code is eligible. Timeout, output parsing, I/O, configuration, assertion, render, and log failures are not retried. V2.1 has no delay/backoff fields, so eligible retries are immediate.

Each attempt is recorded directly in the case log/action record; no `attempt-001` directory is created. A later successful attempt makes the action PASS while retaining earlier evidence; exhausted attempts produce ERROR. Only retry operations that are safe to repeat.

## 05 CLI Reference

### Commands

| Command | Purpose | External tools? |
|---|---|---:|
| `help` | Show syntax and options; default with no command | No |
| `version` | Print ATT version | No |
| `validate` | Validate package or selected dependency closure | No |
| `run` | Validate and execute selected cases | Yes, except dry-run |
| `docs` | Generate searchable package documentation | No |
| `report` | Regenerate reports for a completed run | No |
| `build` | Archive the latest completed run | No |
| `clean` | Remove documented ATT-generated output | No |

### Command syntax

| Syntax | Notes |
|---|---|
| `./att.sh` or `./att.sh help` | Show help |
| `./att.sh version` | Print version |
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
| `./att.sh run <selection> --fail-fast` | Stop scheduling after first FAIL/ERROR |
| `./att.sh run <selection> --rerun-failed` | Select prior FAIL/ERROR cases |
| `./att.sh run <selection> --run-id <id>` | Set final run directory name |
| `./att.sh run <selection> --output-dir <dir>` | Override output root |
| `./att.sh run <selection> --ci-output junit,json` | Write CI XML/JSON plus JUnit HTML |
| `./att.sh run <selection> --format json` | Emit machine-readable summary |
| `./att.sh run <selection> --quiet` | Suppress normal progress |
| `./att.sh run <selection> --verbose` | Add safe lifecycle progress |
| `./att.sh report --run-id <id>` | Regenerate `report/index.html` and `report/junit.html` |
| `./att.sh docs` | Generate `build/docs/index.html` |
| `./att.sh build` | Archive latest completed run in `build/` |
| `./att.sh clean` | Remove documented generated outputs |

Options are command-specific. Unknown commands/options and missing option values are errors. `--package` and `--selected` are mutually exclusive. Selected validation and run require an explicit selection.

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
| Global | `config/config.yaml` | output/environment/runtime defaults, template root, reports, XML mode, tools |
| Workbook | `<workbook>.yaml` | Excel mapping, stages, workbook labels, workbook timeout |
| Template | `template.yaml` | template identity and ordered actions |
| CLI | command options | selection, Run ID, output override, presentation, CI formats |

Action timeout overrides sidecar timeout, which overrides global timeout. CLI `--output-dir` and `--run-id` override their applicable defaults for one command. A field valid in one layer is still rejected if placed in another layer.

### Schema catalog

| Artifact | Schema identifier | Formal definition |
|---|---|---|
| Global configuration | `att-config/v2.1` | [att-config-v2.1.schema.json](../schemas/att-config-v2.1.schema.json) |
| Workbook sidecar | `att-sidecar/v2.1` | [att-sidecar-v2.1.schema.json](../schemas/att-sidecar-v2.1.schema.json) |
| Template descriptor | `att-template/v2.1` | [att-template-v2.1.schema.json](../schemas/att-template-v2.1.schema.json) |
| Run manifest | `att-run/v2.1` | [att-run-v2.1.schema.json](../schemas/att-run-v2.1.schema.json) |
| Validation JSON | `att-validation/v2.1` | [att-validation-v2.1.schema.json](../schemas/att-validation-v2.1.schema.json) |
| CI summary | `att-ci-summary/v2.1` | [att-ci-summary-v2.1.schema.json](../schemas/att-ci-summary-v2.1.schema.json) |
| JUnit XML | XSD | [att-junit-v2.1.xsd](../schemas/att-junit-v2.1.xsd) |
| Diagnostic codes | `att-diagnostic-catalog/v2.1` | [diagnostic-codes.yaml](../schemas/diagnostic-codes.yaml) |

All JSON Schema files use Draft 2020-12. Schema-controlled objects reject unknown properties unless the schema explicitly permits `x-*`. Extensions are preserved metadata and have no execution meaning. Duplicate YAML keys, unsafe tags, wrong types, missing fields, invalid enums, and unsupported properties are errors.

### Global configuration

```yaml
schemaVersion: att-config/v2.1
outputDirectory: output
environment: SIT
timeoutMs: 10000
caseLog: {yamlAnchors: false}
testcase: {root: testcase}
templates: {root: templates}
run: {id: {default: timestamp, timestampFormat: yyyyMMdd-HHmmss}}
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns: {}
  junit: {caseLogEmbedThresholdBytes: 10240}
xml: {namespaceMode: ignore}
tools: {}
```

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | Exactly `att-config/v2.1` |
| `outputDirectory` | `output` | Non-empty package-relative output root |
| `environment` | `SIT` | Non-empty value exposed as `${CASE.environment}`; it does not choose endpoints by itself |
| `timeoutMs` | `10000` | Integer 1–3600000 milliseconds |
| `caseLog.yamlAnchors` | `false` | Boolean; false fully expands repeated YAML structures, true permits anchors/aliases |
| `templates.root` | `templates` | Non-empty package-relative template root |
| `testcase.root` | `testcase` | Non-empty package-relative recursive workbook/sidecar discovery root |
| `run.id.default` | `timestamp` | Only `timestamp` is supported |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Non-empty Java date/time format |
| `report.mode` | `append-to-copy` | Only `append-to-copy` is supported |
| `report.fileNamePattern` | `${suiteName}.result.xlsx` | Result workbook filename pattern |
| `report.columns` | `{}` | Arbitrary string keys and string label values |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | Integer 0–1048576 UTF-8 bytes; 0 always links |
| `xml.namespaceMode` | `ignore` | `ignore` or `preserve` |
| `tools` | `{}` | Map of reusable tool contracts |

Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `report`, `xml`, `tools`, `x-*` |
| `caseLog` | `yamlAnchors`, `x-*` |
| `templates` | `root`, `x-*` |
| `testcase` | `root`, `x-*` |
| `run` | `id`, `x-*` |
| `run.id` | `default`, `timestampFormat`, `x-*` |
| `report` | `mode`, `fileNamePattern`, `columns`, `junit`, `x-*` |
| `report.junit` | `caseLogEmbedThresholdBytes`, `x-*` |
| `xml` | `namespaceMode`, `x-*` |
| `tools.<key>` | `name`, `description`, `command`, `output`, `arguments`, `x-*` |
| `arguments.<key>` | `name`, `description`, `required`, `delimit`, `x-*` |

V2.0 fields such as `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` are not V2.1 fields.

Case log structured entries use YAML. `caseLog.yamlAnchors: false` is the default and prints repeated Map/List content in full at every location. With `true`, SnakeYAML may emit `&id001` and `*id001`; these are standard YAML anchor/alias markers and carry no ATT identifier semantics.

ATT prefixes every Case log block whose section or nested `status` is `ERROR`, `FAIL`, or `INVALID` with `【!!!!!】`. Search for that exact marker to locate abnormal blocks; PASS, SKIPPED, and informational blocks remain unmarked.

### Workbook sidecar

| Object | Allowed properties | Required/constraints |
|---|---|---|
| root | `schemaVersion`, `id`, `excel`, `stages`, `report`, `timeoutMs`, `x-*` | schemaVersion, package-unique id, excel, non-empty stages required |
| `excel` | `sheet`, `headerRows`, `caseId`, `tags`, `dataColumns` | sheet, caseId, tags required; headerRows ≥ 1 |
| `stages[]` | `key`, `template`, `dataColumns`, `required`, `runWhen`, `onFailure` | key/template required; key has no dot |
| `report` | `columns` | values are strings |

`timeoutMs` is 1–3600000. Only the sidecar root permits `x-*`; `excel`, stages, and sidecar `report` reject extensions and other unknown fields. The sidecar cannot override tools, template root, environment, or output root.

### Template and action

| Object/type | Allowed/required contract |
|---|---|
| template root | `schemaVersion`, `name`, `description`, `actions`, `x-*`; schemaVersion, description, non-empty actions required |
| action common | `type`, `description`, `onFailure`, plus only fields belonging to its selected type; action ID has no dot |
| render | requires `payload`, `saveAs`; optional `output`, `overwrite`, `assert`; no call/expression/message/level/fields/timeout/retry |
| tool | requires `call`; optional `saveAs`, `overwrite`, `assert`, `timeoutMs`, `retry`; no render/assert-action/log-only fields |
| assert | requires `expression`; no render/tool/log-only fields, timeout, or retry |
| log | requires `message`; optional `level`, `fields`; no render/tool/assert-only fields, timeout, or retry |
| retry | `maxAttempts`, `retryOn`, `exitCodes`; retryOn required and contains only `EXIT_CODE` |

`maxAttempts` is 1–10; `exitCodes` values are 1–255. Log level is `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR`. The template root, action, and action `output` permit `x-*`; `fields` is an unconstrained log-field map.

### Tool contract

Each tool requires `name`, `description`, and `command`. `output` defaults to `txt` and accepts `txt`, `yaml`, `json`, or `xml`. Each argument requires `name`, `description`, and a YAML boolean `required`. Only the final declared argument may define a non-empty `delimit`.

Tool/argument keys are case-sensitive and argument keys use identifier syntax. The argument descriptor `name` is display text and may contain spaces, Chinese, and punctuation. External tool calls use named arguments. Positional arguments are reserved for ATT built-ins.

### Identifier and path constraints

Run ID and full Case ID are used directly as directory names; ATT does not slugify or hash a valid identifier.

Run ID must be non-blank, at most 128 Unicode code points, not `.` or `..`, not have leading/trailing whitespace or trailing `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or control characters. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.

`workbookId`, `groupId`, and `rowCaseId` follow the same character rules. `workbookId` and `groupId` must not contain `.`, because dots separate the three components; `rowCaseId` may contain dots and is treated as the remaining suffix. Each component is at most 128 Unicode code points and the complete `workbookId.groupId.rowCaseId` is at most 255. The sidecar `id` supplies `workbookId`, the left side of `excel.sheet` supplies `groupId`, and the configured Case ID cell supplies `rowCaseId`. Template paths are relative to `templates.root`; render payloads remain below the template; resolved `saveAs` output stays below the case log directory. ATT normalizes and checks root containment before writes.

### Validation JSON contract

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "2.1.4",
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

Every diagnostic always contains `code`, `severity`, `message`, `file`, `field`, `sheet`, `row`, `column`, `template`, `action`, and `suggestion`. Inapplicable fields are `null`. Codes are stable; automation must not parse human messages.

### Generated-output schema summary

| Artifact | Required top-level contract |
|---|---|
| `run.yaml` | `schemaVersion`, `att`, `runtime`, `run`, `validation`, `inputs`, `cases`, `summary`, `outputs` |
| Validation JSON | `schemaVersion`, `attVersion`, `valid`, `mode`, `summary`, `diagnostics` |
| CI summary JSON | `schemaVersion`, `attVersion`, `runId`, `environment`, `startedAt`, `endedAt`, `status`, `summary`, `durationStatistics`, `cases`, `diagnosticCounts`, `report`, `inputManifestHash` |
| JUnit XML | one testsuite with test/failure/error/skipped counts and one testcase per ATT case |

Generated envelopes reject additional top-level fields according to their schemas. JUnit HTML is a human-readable output and not an XML/JSON schema artifact.

## 07 Expression Reference

### Runtime Context

The persisted runtime tree has one authoritative root:

```text
CASE
├── caseId, workbookId, groupId, rowCaseId, workbook, sheet, rowNumber, tags
├── environment, status, startedAt, durationMs, error
├── <case data aliases>
└── STAGES
    └── <stageKey>
        ├── name and stage-private data
        └── TEMPLATE
            └── ACTIONS
                └── <actionId>
                    ├── action metadata/output
                    └── TOOL
                        └── <toolName>
```

Keywords are uppercase: `CASE`, `STAGES`, `TEMPLATE`, `ACTIONS`, `TOOL`. Metadata remains camelCase. There are no `CASE.fields`, `CASE.data`, or `TOOLS` nodes.

Common properties include:

| Scope | Examples |
|---|---|
| CASE | `caseId`, `workbookId`, `groupId`, `rowCaseId`, `workbook`, `sheet`, `rowNumber`, `tags`, `environment`, case data aliases |
| STAGE | `key`, `name`, selector-map data, sidecar stage data, status, timing, error |
| TEMPLATE | `name`, `path`, `description`, status, timing, error |
| ACTION | `id`, `type`, status, timing, error, `output`, `outputFile`, assertion/log data |
| TOOL | configured name, `input`, `command`, `argv`, `stdout`, `stderr`, `rawOutput`, parsed `output`, status, exit code, duration, retry evidence, and optional `outputFile` when `saveAs` is used |

Use `${ACTIONS.<id>...}` as a current-template convenience view. Use the canonical `${CASE.STAGES.<stage>.TEMPLATE.ACTIONS...}` form for persisted cross-stage references. The root `${TOOL...}` scope is reserved for invocation internals and is not a case-wide “latest tool” API. Authors must use `${ACTIONS.<id>...}` for later actions and the canonical CASE path across stages. Tool input/argv/stdout/stderr evidence is persisted below the action and written to the case log.

Map properties use dot navigation and lists use zero-based brackets:

```text
${CASE.amount}
${CASE.STAGES.invoke.channel}
${ACTIONS.callApi.output.items[0].status}
```

Dot notation navigates simple map keys. Lists accept bracket or numeric-dot indexes, so `${CASE.items[0].status}` and `${CASE.items.0.status}` are equivalent. Indexes are zero-based. Map keys containing dots, spaces, braces, or colons use quoted brackets, for example `${CASE.response['{urn:payment}Status'].text}`.

An unresolved path renders as the empty string in ordinary template text. In an assertion, a missing value behaves as null, so `${CASE.missing} is null` is true. Invalid syntax produces an evaluation diagnostic. An action may read only data and earlier action outputs that exist at that execution point.

### Operators

Supported assertion operators are `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, `not`, `and`, and `or`. Use parentheses when mixing logical operators so intent is explicit.

`like` is case-insensitive as an operator keyword, but the canonical authored spelling is lowercase. It matches the complete value and uses two SQL-style wildcards:

- `%` matches zero or more characters;
- `_` matches exactly one character;
- matching is case-sensitive.

The current implementation translates `%` to Java regular-expression `.*` and `_` to `.`. Other regular-expression metacharacters such as `.`, `+`, `*`, `[`, `]`, `(`, `)`, `?`, `^`, `$`, `|`, and backslash retain their regular-expression meaning instead of being escaped automatically. Use `like` with ordinary literal text plus `%`/`_`; use `==` for exact text.

Comparison first recognizes boolean literals. If both operands are valid decimal numbers, ATT compares them as arbitrary-precision decimals, including ordinary Excel strings such as `"100"`; numeric equality also uses this coercion, so `1.0 == 1` is true. Otherwise ATT compares the rendered strings lexicographically and case-sensitively. Blank or missing values render as empty strings outside `is null`; use `is null`/`is not null` when absence matters. Use `#{number(...)}` when invalid numeric text should become an explicit evaluation error instead of a string comparison.

### Built-in functions

Built-ins are called with `#{...}`. Tool calls use the same outer syntax but are resolved against configured tools.

| Function | Purpose | Example |
|---|---|---|
| `upper` | Convert text to upper case | `#{upper(value=${CASE.currency})}` |
| `lower` | Convert text to lower case | `#{lower(value=${CASE.channel})}` |
| `trim` | Remove surrounding whitespace | `#{trim(value=${CASE.reference})}` |
| `string` | Convert a value to text | `#{string(value=${CASE.amount})}` |
| `number` | Parse and normalize a number | `#{number(value='12.50')}` |
| `boolean` | Convert true/false, yes/no, or 1/0 | `#{boolean(yes)}` |
| `length` | Return text length | `#{length(value=${CASE.reference})}` |
| `concat` | Concatenate arguments in call order | `#{concat(a='PAY-', b=${CASE.caseId})}` |
| `coalesce` | Return first non-blank value | `#{coalesce(${CASE.optional}, 'N/A')}` |

`upper`, `lower`, `trim`, `string`, `number`, `boolean`, and `length` accept `value=` or the first positional argument. Case conversion is locale-independent. `number` rejects non-numeric input and removes unnecessary trailing zeroes. `boolean` accepts true/false, yes/no, and 1/0. `concat` treats null as empty; `coalesce` skips null and whitespace-only values and returns empty when none qualifies.

Use built-ins for deterministic local transformations and tools for filesystem, network, database, system integration, or complex reusable logic. Built-ins create no TOOL process artifacts. Invalid arguments produce action ERROR.

Typical expressions:

```yaml
expression: "${CASE.amount} > 0"
expression: "${ACTIONS.callApi.output.status} == ${CASE.expectedStatus}"
expression: "${ACTIONS.callApi.output.message} like 'PAYMENT%SUCCESS'"
expression: "(${CASE.channel} == 'MOBILE') and (${CASE.amount} <= 1000)"
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

Run and Case IDs appear unchanged after validation. A final run directory represents a completed publication; interrupted work remains below `.in-progress`.

### Human HTML report

`report/index.html` is the primary end-user report. It can be opened without a web server. Groups are summarized by `workbookId.groupId`; the interface labels `groupId` as Sheet because it maps to one physical sheet. Cases supports Workbook/Sheet/Status dropdowns, case-insensitive search over workbook/group/full Case ID/tags, and ascending/descending sorting from every column heading. Duration sorting is numeric.

An expanded case contains the full Case ID and name, status and duration, expected and actual result, one row per recorded action result, the persisted Stage/Template/Action/Tool Context tree, detailed execution log, and artifact links. Depending on what ran, the tree/log show selected templates, executed or skipped stages/actions, assertion messages, tool arguments and argv, stdout/stderr, raw and parsed output, retry attempts, diagnostics, and saved payload/tool-output paths. Workbook ID, group ID, and tags are persisted per case in `run.yaml`, so `report --run-id` regenerates equivalent controls and grouping.

`report/junit.html` is a human-readable JUnit projection. It displays counts and one row per testcase with status, duration, and embedded case-log content or a relative artifact link.

### Result workbook

ATT copies the source workbook and appends configured result columns using `report.mode: append-to-copy`. Global `report.fileNamePattern` controls its filename. Sidecar `report.columns` changes workbook labels only.

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

`run.yaml` uses `schemaVersion: att-run/v2.1` and records ATT/build identity, Java/OS/locale/timezone, validation mode, environment, timestamps, status/summary, output paths, and SHA-256 inputs for effective configuration, workbook, sidecar, resolved templates/payloads, package-local tool files, and schema/catalog version.

### Documentation, archive, and clean

| Command | Output/behavior |
|---|---|
| `docs` | Generates searchable offline package documentation at `build/docs/index.html` |
| `report --run-id <id>` | Regenerates both HTML reports from completed evidence |
| `build` | Archives the latest completed run without executing tests |
| `clean` | Removes configured output directory, `build/docs`, and `build/att-*.tar.gz` |

The build archive contains reports, workbooks, case logs, referenced artifacts, redacted configuration/template snapshots, manifest, and hashes. Exact YAML keys named `password`, `token`, `secret`, or `authorization` are redacted case-insensitively; this is not a general log/result redactor.

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
| `ATT-TC` | Missing sidecar/sheet/header or duplicate Case ID | Check basenames, sheet mapping, effective headers, and full IDs |
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

#### Why is my optional Context value empty?

Optional missing/blank data resolves to the empty string. Check the physical header, alias, `(yaml)` marker, stage scope, and when the referenced action becomes available.

#### Why did a FAIL become ERROR?

A false assertion is FAIL. Invalid expression syntax/navigation, tool failure, timeout, parse failure, I/O failure, or runtime exception is ERROR. Inspect the action evidence rather than only the final aggregate status.

#### Why did a tool run more than once?

Its action used retry and received an eligible non-zero exit code. Inspect the attempt list and final action record in the case log.

#### Can I use a shell pipeline in `command`?

No. ATT passes `|`, `>`, and `<` literally. Put shell behavior inside a reviewed tool script.

#### Why does a required delimited argument reject `N/A`?

Required validation happens before array expansion. `N/A` normalizes to blank, so the required input is missing.

#### Should I use package or selected validation?

Use selected mode for fast local feedback. Use package mode before release, CI promotion, or sharing a package.

#### Can reports be opened without a server?

Yes. Keep the generated run directory together so relative artifact links continue to work.

#### Does build execute tests again?

No. It archives one completed persisted run.

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

The authoritative persisted runtime tree has one `CASE` root. Convenience scopes such as `ACTIONS` and `TOOL` do not create alternative persisted roots.

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

### Atomic run lifecycle

Before execution ATT creates:

```text
<outputDirectory>/.in-progress/<RunID>-<nonce>/
```

Evidence is written there. After all required outputs are finalized, ATT atomically publishes it as:

```text
<outputDirectory>/<RunID>/
```

An interrupted run stays in `.in-progress` and is not eligible for `report`, `build`, `rerun-failed`, or latest-run selection. Publication fails if the final Run ID already exists. `latest-run.yaml` is replaced atomically only after final publication.

### Process safety

ATT constructs argv directly and uses no implicit shell. Timeout termination must stop the managed process according to platform support and retain process evidence. Structured parsers reject malformed/ambiguous input and XML external-resource features.

Workbook import uses Apache POI `DataFormatter` for ordinary cells and deliberately does not create a `FormulaEvaluator`; formula expressions, not cached results, enter Context.

### CI and parallel execution

| Concurrent operation | Contract |
|---|---|
| Two runs use the same Run ID | Both may prepare separate `.in-progress` directories, but only one may publish the final run. The later publisher fails and does not overwrite it. |
| Multiple runs update `latest-run.yaml` | Each publishes its final directory first; the last completion wins the atomic pointer update. Completion order, not start order, determines latest. |
| `build` and `run` execute together | Build pins one completed latest-run/manifest pair and never archives `.in-progress` content. |
| `report` and `clean` execute together | This destructive race is unsupported. Report fails rather than producing a partial result; serialize report/archive/clean jobs sharing one output root. |

Use separate `--output-dir` values when parallel jobs need independent run history, cleanup, or latest-run behavior.

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
