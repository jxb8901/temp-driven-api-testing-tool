# ATT V2.3.4 User Manual and Reference

Author: Jeffrey + ChatGPT
Version: 2.3.4
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

### What V2.3 guarantees

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
├── att.bat
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
schemaVersion: att-config/v2.2
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
    command: ["./tools/invoke_payment_api.sh", "${requestFile}", "${environment}"]
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

An argv-list `command` preserves every list item as one process argument. A legacy scalar command is tokenized once into the same internal list. Declared arguments are referenced directly, for example `${requestFile}`.

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
schemaVersion: att-template/v2.3
name: PAYMENT_INVOKE
description: Render, invoke, and verify a payment
actions:
  renderRequest:
    type: render
    payload: request.tmp.json
    renderAs: file
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]}, environment=${CASE.environment})}"
  assertStatus:
    type: assert
    description: Payment API status matches the expected status
    assert: "${ACTIONS.callApi.output.result.status} == ${CASE.expectedStatus}"
    expected: "${CASE.expectedStatus}"
    actual: "${ACTIONS.callApi.output.result.status}"
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
schemaVersion: att-template/v2.3
name: PAYMENT_INVOKE
description: Render and invoke a payment request
actions:
  renderRequest:
    type: render
    description: "Render request for ${CASE.caseId}; status=${output.status}"
    payload: requests/*.xml
    renderAs: file
    assert: "${output.targetFiles[0]} != null"
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]})}"
    saveAs: "${CASE.caseId}-response.json"
    overwrite: false
    assert: "${output.result.status} == 'SUCCESS'"
  recordResult:
    type: log
    level: INFO
    message: "Payment ${CASE.caseId} completed"
```

`schemaVersion`, `description`, and a non-empty ordered `actions` map are required. `name` is optional when the template is always selected by full path; reusable templates should have a globally unique symbolic name.

#### Action types

| Type | Purpose | Required fields | Common result |
|---|---|---|---|
| `render` | Render one or more UTF-8 payloads | `type`, `payload`, `renderAs` | nested `output.result` and `output.targetFiles` |
| `tool` | Invoke a configured external tool | `type`, `call` | nested typed result and process evidence |
| `assert` | Evaluate a boolean expression | `type`, `assert` | PASS/FAIL or evaluation ERROR; optional Expected/Actual values |
| `log` | Write a rendered structured message | `type`, `message` | message and rendered fields |

Actions run in YAML order. Action IDs are unique within the template and cannot contain a dot. Every action may define `description` and `onFailure: stop|continue`.

Action validation is type-specific. A render action requires a safe non-empty payload glob and `renderAs: file|text|json|yaml|xml`; it cannot contain tool/assert-action/log fields. Retry and timeout are valid only for tool actions. An assert action requires `assert` and may include `expected` and `actual`; `expression`, `acture`, and `actural` are invalid. A log action may use `level` and `fields`. Unsupported fields are errors rather than ignored values.

Every action may use `assert` except that an assert action uses it as its required primary expression. Every action outcome is nested under `output`, including `status`, `success`, `durationMs`, `exception`, `targetFiles`, `result`, and optional assertion detail. Operational errors remain ERROR; otherwise an explicit assertion decides PASS/FAIL. A completed tool process with a non-zero exit code is not automatically ERROR: inspect `output.exitCode` in `assert` when the exit code matters.

Every action supports expression-bearing `description`. Validation partially resolves static Case values and preserves unavailable runtime placeholders exactly. After execution, ATT resolves the remaining placeholders against the current action-local `${output...}` scope before persisting the final description.

Render payload paths must remain below the template root. Glob matches are regular non-symbolic-link files sorted by portable template-relative path. `renderAs: file` writes the rendered result under the Case output directory using that same relative path; collisions are ERROR. Other render modes write no file and store one typed value, or an ordered relative-path-to-value map for multiple matches, in `output.result`.

### 3.3 Tool

A tool is an external capability configured either globally under `config.yaml` or inside an independent tool-group file and called by template actions using named arguments.

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered request
    command: ["./tools/invoke_payment_api.sh", "${requestFile}", "${environment}"]
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
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]}, environment=${CASE.environment})}"
```

Unknown, duplicate, or missing required arguments fail validation. Argument metadata documents and validates the contract; it does not inject values automatically. If—and only if—the tool declares exactly one argument, the call may omit its name: `#{getAppLogs(${CASE.caseId})}` is equivalent to `#{getAppLogs(caseId=${CASE.caseId})}`. A zero-argument tool still uses `#{tool()}`; a multi-argument tool rejects positional arguments.

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

Call it as `#{database.selectPayment(caseId=${CASE.caseId})}`. With `script`, logical argv is `/opt/att/database-tools selectPayment select-payment --case <caseId>`: script argv, unqualified tool key, then tool command argv. Without `script`, the tool command starts with the executable. Persisted grouped evidence is navigable below `TOOL.database.selectPayment`.

The shipped `fpp` group is a reference implementation rather than an FPP product adapter:

| Call | Input | Parsed result |
|---|---|---|
| `fpp.invokeApi` | `requestId`, `requestType`, absolute request-file path, API-log path | XML with request metadata, `ResultCode`, and `ResultMessage` |
| `fpp.sqlplusToXml` | SQLPlus output-file path | XML rows and columns plus `RowCount` |
| `fpp.runScript` | child script, stdout path, stderr path | YAML with child `exitCode`, `success`, `errorMessage`, and output paths |

```text
#{fpp.invokeApi(requestId=${CASE.requestId}, requestType=${CASE.requestType}, requestFile=${CASE.requestFile}, apiLogPath=${CASE.apiLogPath})}
#{fpp.sqlplusToXml(inputFile=${CASE.sqlplusOutput})}
#{fpp.runScript(script=${CASE.script}, stdoutPath=${CASE.stdoutPath}, stderrPath=${CASE.stderrPath})}
```

`tools/fpp_invoke_api.sh` validates and XML-escapes its inputs, records a correlation line in the requested log, and deliberately returns `ResultCode=NOT_IMPLEMENTED` until its marked integration block is replaced with an approved API client. A missing request file returns `INPUT_FILE_NOT_FOUND`. `tools/fpp_sqlplus_to_xml.sh` expects the first non-blank line to contain pipe-separated column names and converts one or more subsequent records; separator and SQLPlus footer lines are ignored. A safe ASCII XML name such as `name` is emitted directly as `<name>value</name>`; names with spaces, an invalid first character, or the reserved case-insensitive `xml` prefix use the valid fallback `<Column name="original">value</Column>`. `tools/fpp_run_script.sh` captures the complete child stdout/stderr, reports missing/non-executable scripts as child exit codes 127/126, and exits successfully after producing valid YAML, so assertions should inspect `${output.result.exitCode}` rather than the tool process exit code. These scripts require a POSIX shell; Windows packages may point an equivalent tool group at `.bat`, PowerShell, or native commands.

#### Command processing

V2.2 normalizes every command to an argv template list. A scalar command is tokenized once with the legacy tokenizer. A YAML list is already normalized: each item is exactly one argv value and is never tokenized. An ordinary declared argument therefore remains atomic regardless of spaces, quotes, backslashes, leading dashes, or shell-like characters in its value. Any declared argument with `delimit` may intentionally expand into zero or more argv values, and multiple arguments in one tool may do so independently. Resolved values are never tokenized again. ATT does not invoke a local shell.

V2.3.2 starts every local tool process with `${CASE.outputDirectory}` as its current working directory. A configured executable beginning with `./` or `../` remains package-relative: ATT resolves that first argv value against the package root before launch. A bare executable name still uses `PATH`. All other relative argv paths are intentionally interpreted by the tool from the Case output directory. This makes relative tool artifacts part of the Case output without requiring every action to build an absolute path.

ATT supplies and owns these local-process environment variables:

| Variable | Contract |
|---|---|
| `ATT_ROOT_DIR` | normalized absolute ATT package root |
| `ATT_CASE_OUTPUT_DIR` | normalized absolute current Case output directory; identical to `${CASE.outputDirectory}` at process launch |

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

The recommended equivalent is clearer:

```yaml
command:
  - ./tools/send.sh
  - "${requestFile}"
  - --label
  - Payment regression
```

An argument may define an optional atomic `argName` token. Its placeholder determines the insertion position and must occupy exactly one complete command token when `argName` is non-empty:

```yaml
command:
  - ./tools/send.sh
  - "${requestFile}"
  - "${reference}"
arguments:
  requestFile: {name: Request File, description: Input file, required: true}
  reference: {name: Reference, description: Optional reference, required: false, argName: --reference}
```

With `reference='REF 123'`, the final portion of logical argv is `--reference`, `REF 123`; the value remains one atomic argument. If the optional value is missing or normalizes to blank, neither token is emitted. Omitting `argName` or setting `argName: ''` makes the argument positional: an exact-token placeholder emits only its value, or emits no argv when the optional value is blank. An embedded placeholder such as `--reference=${reference}` remains one ordinary rendered token and cannot use a non-empty `argName`. For delimited values, `argNameMode` controls whether the name is emitted `once` (the backward-compatible default) before the complete list or `repeat` before every value; it has no output effect for positional arguments.

Prefer the shortest declared-argument placeholder, such as `${keywords}`; use `${input.keywords}` when an explicit namespace improves clarity. Both forms are case-sensitive and must exactly match the argument key. `${TOOL.input.keywords}` remains supported but is not the preferred authoring style. Tools write their raw result to stdout and diagnostics to stderr; ATT records input/stdout/stderr in the case log.

Global tool commands may reference only their declared arguments. They cannot reference `${CASE...}`, `${ACTIONS...}`, or other runtime Context scopes. Pass runtime data explicitly in the action call, then reference that declared argument in the command. This keeps the global tool independent and its dependencies statically validateable.

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

The Case output working-directory and two environment-variable rules apply to local tool processes only. ATT does not prepend `cd` or inject local filesystem paths into an SSH remote command because `${CASE.outputDirectory}` and the package root have no defined remote mappings. The remote process uses the SSH account's default directory. Pass a shared-filesystem or remote directory as an explicitly declared tool argument when required.

#### Input, output, timeout, and status

Available command placeholders are:

| Placeholder | Meaning |
|---|---|
| `${argument}` | Preferred direct reference to a declared argument key |
| `${input.argument}` | Explicitly namespaced reference to the same declared argument |
| `${TOOL.input.argument}` | Supported explicit alias for the same declared argument |

ATT parses stdout as raw output. The tool configuration `output` may be `txt`, `yaml`, `json`, or `xml`; default is `txt`. A tool action may set `saveAs` to persist raw stdout in the case log directory. The relative filename may contain `${...}` Context expressions; template authors must keep it unique in that directory. `overwrite` defaults to `false`, so an existing target is an error unless overwrite is enabled. Every retry writes the same resolved target and replaces the earlier attempt's stdout, leaving the final attempt as the artifact. Without `saveAs`, ATT creates no dedicated tool-output file, while input, argv, stdout, stderr, parsed result, exit code, and retry evidence remain in the persisted case evidence.

Timeout, launch/process I/O failure, or structured-output parse failure is ERROR and cannot be overridden by an assertion. Otherwise a tool action's `assert` decides PASS/FAIL. If no assertion is configured, operational completion is PASS even when the process exit code is non-zero. The exit code remains in `action.output.exitCode`, so templates that require zero must say so explicitly. Command, inputs, stdout, stderr, raw output, duration, exit code, parsed `output.result`, and assertion details are retained as evidence.

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

Each expanded case shows its full Case ID, name, status, duration, Expected and Actual results, action-result rows, the persisted Stage/Template/Action/Tool Context tree, the detailed execution log, and links to persisted case artifacts. Only `type: assert` actions contribute: Expected appends each non-blank final description and validation-time `expected`; Actual appends each non-blank runtime `actual`. Values follow action order and use exactly one LF between non-blank entries. HTML escapes and pre-wraps the text, Excel preserves LF with wrapping, JSON uses escaped `\n`, and JUnit retains the same line boundaries.

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
  assert: "${ACTIONS.callApi.output.result.status} == 'SUCCESS'"
  expected: SUCCESS
  actual: "${ACTIONS.callApi.output.result.status}"
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
  assert: "${ACTIONS.callApi.output.result.attributes.requestId} == 'R-100'"
assertStatusText:
  type: assert
  assert: "${ACTIONS.callApi.output.result.Status.text} == 'SUCCESS'"
assertStatusCode:
  type: assert
  assert: "${ACTIONS.callApi.output.result.Status.attributes.code} == '00'"
assertSecondMessage:
  type: assert
  assert: "${ACTIONS.callApi.output.result.Messages.Message[1].text} == 'review later'"
assertSecondSeverity:
  type: assert
  assert: "${ACTIONS.callApi.output.result.Messages.Message[1].attributes.severity} == 'WARN'"
```

With `xml.namespaceMode: ignore`, keys use local names. With `preserve`, namespace-aware names use Clark notation such as `{urn:payment}Status`. Keys containing braces, colons, dots, or spaces use quoted brackets, for example `${ACTIONS.callApi.output.result['{urn:payment}Status'].text}`. XML DTD, external entities, XInclude, and external resources are disabled.

A leaf without attributes becomes `ElementName: text`. Any element with attributes retains their names under `attributes`; therefore `<Item id="123"/>` becomes `Item: {attributes: {id: "123"}}`. Repeated same-name siblings become a list in source order; a singleton stays scalar/map. Empty elements become an empty string unless they have attributes. Text and CDATA are concatenated, trimmed at the boundary, and stored as `text` when child elements also exist. Comments and processing instructions are ignored. With namespace preservation, element and non-namespace attribute names use Clark notation, keeping equal local names from different namespaces distinct.

### Pass a list as separate process arguments

Multiple declared tool arguments may use `delimit`. The following example also shows both `argNameMode` values:

```yaml
tools:
  grepFromAppLogs:
    name: Grep application logs
    description: Search one or more keywords and log levels
    command:
      - ./tools/grep_from_app_logs.sh
      - "${logFile}"
      - "${keywords}"
      - "${levels}"
    output: yaml
    arguments:
      logFile: {name: Log File, description: Source log, required: true}
      keywords: {name: Keywords, description: Comma-delimited values, required: true, delimit: ",", argName: --keyword, argNameMode: repeat}
      levels: {name: Levels, description: Pipe-delimited levels, required: false, delimit: "|", argName: --levels, argNameMode: once}
```

```yaml
grepLogs:
  type: tool
  call: "#{grepFromAppLogs(logFile=${ACTIONS.getLogs.output.targetFiles[0]}, keywords='PAYMENT,POSTED', levels='ERROR|WARN')}"
```

The resulting tail of logical argv is `--keyword`, `PAYMENT`, `--keyword`, `POSTED`, `--levels`, `ERROR`, `WARN`. Explicit `repeat` repeats `--keyword` for every keyword. `once` is the default and may be omitted; it emits `--levels` only once before the complete levels list. Each delimited argument expands independently at its own command-placeholder position.

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

Surrounding whitespace is trimmed; empty middle elements are preserved; blank markers produce an empty array. A required blank value fails before expansion. Spaces, quotes, backslashes, leading dashes, and `|><` inside an item remain literal data. Every delimited placeholder must occupy one complete static command token; quoting it in the template is allowed but unnecessary because static tokenization happens before value expansion. An empty optional list emits neither its `argName` nor values.

### Retry selected exit codes

Retry belongs to a tool action, not to a workflow or arbitrary stage:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.output.targetFiles[0]})}"
  timeoutMs: 30000
  retry:
    maxAttempts: 3
    retryOn: [EXIT_CODE]
    exitCodes: [1, 75]
```

`maxAttempts` includes the first attempt and defaults to 1. V2.3 supports only `EXIT_CODE`; if `exitCodes` is omitted, any non-zero exit code is eligible. Timeout, output parsing, I/O, configuration, assertion, render, and log failures are not retried. V2.3 has no delay/backoff fields, so eligible retries are immediate.

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

The tables use the Linux/macOS launcher `./att.sh`. On Windows, use `att.bat` with the same command and options, for example `att.bat validate --package`. Binary releases require Java 8+. Source-tree `att.bat` compiles with Maven when available and otherwise requires existing `target\classes`.

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
| Global | `config/config.yaml` | output/environment/runtime defaults, template root, reports, XML mode, global tools, group paths, optional global SSH |
| Tool group | configured YAML path | group identity, optional script/SSH, grouped tools |
| Workbook | `<workbook>.yaml` | Excel mapping, stages, workbook labels, workbook timeout |
| Template | `template.yaml` | template identity and ordered actions |
| CLI | command options | selection, Run ID, output override, presentation, CI formats |

Action timeout overrides sidecar timeout, which overrides global timeout. CLI `--output-dir` and `--run-id` override their applicable defaults for one command. A field valid in one layer is still rejected if placed in another layer.

### Schema catalog

| Artifact | Schema identifier | Formal definition |
|---|---|---|
| Global configuration | `att-config/v2.2` | [att-config-v2.2.schema.json](../schemas/att-config-v2.2.schema.json) |
| Legacy global configuration (read compatibility) | `att-config/v2.1` | [att-config-v2.1.schema.json](../schemas/att-config-v2.1.schema.json) |
| Tool group | `att-tool-group/v2.2` | [att-tool-group-v2.2.schema.json](../schemas/att-tool-group-v2.2.schema.json) |
| Workbook sidecar | `att-sidecar/v2.1` | [att-sidecar-v2.1.schema.json](../schemas/att-sidecar-v2.1.schema.json) |
| Template descriptor | `att-template/v2.3` | [att-template-v2.3.schema.json](../schemas/att-template-v2.3.schema.json) |
| Run manifest | `att-run/v2.1` | [att-run-v2.1.schema.json](../schemas/att-run-v2.1.schema.json) |
| Validation JSON | `att-validation/v2.1` | [att-validation-v2.1.schema.json](../schemas/att-validation-v2.1.schema.json) |
| CI summary | `att-ci-summary/v2.1` | [att-ci-summary-v2.1.schema.json](../schemas/att-ci-summary-v2.1.schema.json) |
| JUnit XML | XSD | [att-junit-v2.1.xsd](../schemas/att-junit-v2.1.xsd) |
| Diagnostic codes | `att-diagnostic-catalog/v2.1` | [diagnostic-codes.yaml](../schemas/diagnostic-codes.yaml) |

All JSON Schema files use Draft 2020-12. Schema-controlled objects reject unknown properties unless the schema explicitly permits `x-*`. Extensions are preserved metadata and have no execution meaning. Duplicate YAML keys, unsafe tags, wrong types, missing fields, invalid enums, and unsupported properties are errors.

### Global configuration

```yaml
schemaVersion: att-config/v2.2
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
toolGroups: [config/tools/database.yaml]
tools: {}
```

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | `att-config/v2.2`; unchanged V2.1 files remain readable but cannot use V2.2-only fields |
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
| `toolGroups` | `[]` | Unique safe package-relative tool-group YAML paths |
| `ssh` | absent | Optional SSH target for inline global tools |
| `tools` | `{}` | Map of reusable tool contracts |

Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `report`, `xml`, `toolGroups`, `ssh`, `tools`, `x-*` |
| `caseLog` | `yamlAnchors`, `x-*` |
| `templates` | `root`, `x-*` |
| `testcase` | `root`, `x-*` |
| `run` | `id`, `x-*` |
| `run.id` | `default`, `timestampFormat`, `x-*` |
| `report` | `mode`, `fileNamePattern`, `columns`, `junit`, `x-*` |
| `report.junit` | `caseLogEmbedThresholdBytes`, `x-*` |
| `xml` | `namespaceMode`, `x-*` |
| `ssh` | `host`, `user`, `port`, `identityFile` |
| `tools.<key>` | `name`, `description`, `command`, `output`, `arguments`, `x-*` |
| `arguments.<key>` | `name`, `description`, `required`, `argName`, `argNameMode`, `delimit`, `x-*` |

V2.0 fields such as `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` are not V2.2 fields.

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
| render | requires `payload`, `renderAs`; optional `assert`; no saveAs/overwrite/output/call/expression/message/level/fields/timeout/retry |
| tool | requires `call`; optional `saveAs`, `overwrite`, `assert`, `timeoutMs`, `retry`; no render/assert-action/log-only fields |
| assert | requires `assert`; optional `expected`, `actual`; no expression/render/tool/log-only fields, timeout, or retry |
| log | requires `message`; optional `level`, `fields`, `assert`; no render/tool/assert-action-only fields, timeout, or retry |
| retry | `maxAttempts`, `retryOn`, `exitCodes`; retryOn required and contains only `EXIT_CODE` |

`renderAs` is `file`, `text`, `json`, `yaml`, or `xml`. `maxAttempts` is 1–10; `exitCodes` values are 1–255. Log level is `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR`. The template root and action permit `x-*`; `fields` is an unconstrained log-field map. `output` is runtime evidence in V2.3 and is never an action configuration field.

### Tool contract

Each tool requires `name`, `description`, and `command`. `command` is either a non-blank scalar or a non-empty string list. `output` defaults to `txt` and accepts `txt`, `yaml`, `json`, or `xml`. Each argument requires `name`, `description`, and a YAML boolean `required`. `argName` is optional and must be empty or one whitespace-free argv token. A non-empty `argName` requires exactly one complete-token placeholder for that argument. `argNameMode` accepts `once` or `repeat` and defaults to `once` for V2.3.3 compatibility. Any number of declared arguments may define a non-empty `delimit`, and every delimited placeholder must occupy one complete command token.

Tool/argument keys are case-sensitive and argument keys use identifier syntax. The argument descriptor `name` is display text and may contain spaces, Chinese, and punctuation. External tool calls use named arguments. Positional arguments are reserved for ATT built-ins.

A tool-group root requires `schemaVersion`, package-unique `id`, `name`, `description`, and non-empty `tools`. It optionally accepts `script` in scalar/list command form and `ssh`. Group/tool IDs match `[A-Za-z_][A-Za-z0-9_-]*` and contain no dot. Group calls use `group.tool`; inline global calls remain unqualified. Built-in names are reserved only in the global namespace.

### Identifier and path constraints

Run ID and full Case ID are used directly as directory names; ATT does not slugify or hash a valid identifier.

Run ID must be non-blank, at most 128 Unicode code points, not `.` or `..`, not have leading/trailing whitespace or trailing `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or control characters. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.

`workbookId`, `groupId`, and `rowCaseId` follow the same character rules. `workbookId` and `groupId` must not contain `.`, because dots separate the three components; `rowCaseId` may contain dots and is treated as the remaining suffix. Each component is at most 128 Unicode code points and the complete `workbookId.groupId.rowCaseId` is at most 255. The sidecar `id` supplies `workbookId`, the left side of `excel.sheet` supplies `groupId`, and the configured Case ID cell supplies `rowCaseId`. Template paths are relative to `templates.root`; render glob matches remain below the template and `renderAs: file` targets remain below the Case output directory. Tool-action `saveAs` stays below the case log directory. ATT normalizes and checks root containment before reads and writes.

### Validation JSON contract

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "2.3.4",
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
├── outputDirectory
├── environment, status, startedAt, durationMs, error
├── <case data aliases>
└── STAGES
    └── <stageKey>
        ├── name and stage-private data
        └── TEMPLATE
            └── ACTIONS
                └── <actionId>
                    ├── id, type, description
                    ├── output
                    └── TOOL
                        └── <toolName>
```

Keywords are uppercase: `CASE`, `STAGES`, `TEMPLATE`, `ACTIONS`, `TOOL`. Metadata remains camelCase. There are no `CASE.fields`, `CASE.data`, or `TOOLS` nodes.

Common properties include:

| Scope | Examples |
|---|---|
| CASE | `caseId`, `workbookId`, `groupId`, `rowCaseId`, `workbook`, `sheet`, `rowNumber`, `tags`, `environment`, reserved `outputDirectory`, case data aliases |
| STAGE | `key`, `name`, selector-map data, sidecar stage data, status, timing, error |
| TEMPLATE | `name`, `path`, `description`, status, timing, error |
| ACTION | `id`, `type`, final `description`; nested `output.status`, `success`, `durationMs`, `exception`, `targetFiles`, `result`, and assertion/log data |
| TOOL | qualified configured name, optional group ID/tool key, `input`, logical/executed `argv`, optional SSH destination metadata, `stdout`, `stderr`, `rawOutput`, parsed `output`, status, exit code, duration, retry evidence, and optional `outputFile` when `saveAs` is used |

Use `${output...}` for the current action while its runtime assertion, actual value, and final description are evaluated. Use `${ACTIONS.<id>...}` as a completed current-template convenience view. Use the canonical `${CASE.STAGES.<stage>.TEMPLATE.ACTIONS...}` form for persisted cross-stage references. The root `${TOOL...}` scope is reserved for invocation internals and is not a case-wide “latest tool” API. Tool input/argv/stdout/stderr evidence is persisted below the action and written to the case log.

`${CASE.outputDirectory}` is a reserved, normalized absolute path and Case data cannot override it. It exists before the first stage. During execution it is the physical `.in-progress/<RunID>-<nonce>/<CaseID>` directory; after successful publication ATT rewrites persisted text evidence to `<outputDirectory>/<RunID>/<CaseID>`. Validation preserves the placeholder because no runtime Run directory exists yet.

Map properties use dot navigation and lists use zero-based brackets:

```text
${CASE.amount}
${CASE.STAGES.invoke.channel}
${ACTIONS.callApi.output.result.items[0].status}
```

Dot notation navigates simple map keys. Lists accept bracket or numeric-dot indexes, so `${CASE.items[0].status}` and `${CASE.items.0.status}` are equivalent. Indexes are zero-based. Map keys containing dots, spaces, braces, or colons use quoted brackets, for example `${CASE.response['{urn:payment}Status'].text}`.

For `description` and assert-action `expected`, validation resolves available static values and preserves unresolved placeholders verbatim. Runtime resolves the remainder after the current action outcome exists; unresolved descriptive/actual placeholders remain visible, while unresolved or invalid assertion values produce an evaluation ERROR. An action may read only case data, its local `output`, and earlier action outputs that exist at that execution point.

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
| `ltrim` | Remove leading whitespace | `#{ltrim(${CASE.reference})}` |
| `rtrim` | Remove trailing whitespace | `#{rtrim(${CASE.reference})}` |
| `string` | Convert a value to text | `#{string(value=${CASE.amount})}` |
| `number` | Parse and normalize a number | `#{number(value='12.50')}` |
| `boolean` | Convert true/false, yes/no, or 1/0 | `#{boolean(yes)}` |
| `length` | Return text length | `#{length(value=${CASE.reference})}` |
| `concat` | Concatenate arguments in call order | `#{concat(a='PAY-', b=${CASE.caseId})}` |
| `coalesce` | Return first non-blank value | `#{coalesce(${CASE.optional}, 'N/A')}` |
| `nvl` | Return a default for null/empty text | `#{nvl(${CASE.optional}, 'N/A')}` |
| `iif` | Select one of two values from a boolean | `#{iif(${CASE.enabled}, 'Y', 'N')}` |
| `nchar` | Repeat a value 0–10000 times | `#{nchar(3, '9')}` |
| `substr` | Extract text from a zero-based start | `#{substr(${CASE.reference}, 0, 8)}` |
| `indexOf` | Return zero-based position or `-1` | `#{indexOf(${CASE.reference}, '-')}` |
| `contains` | Test literal substring membership | `#{contains(${CASE.message}, 'SUCCESS')}` |
| `startsWith` | Test a literal prefix | `#{startsWith(${CASE.reference}, 'PAY')}` |
| `endsWith` | Test a literal suffix | `#{endsWith(${CASE.fileName}, '.xml')}` |
| `replace` | Replace every literal target | `#{replace(${CASE.reference}, '-', '')}` |
| `padLeft` | Pad to a minimum length | `#{padLeft(${CASE.sequence}, 8, '0')}` |
| `padRight` | Pad to a minimum length | `#{padRight(${CASE.code}, 5, '_')}` |
| `sysdate` | Return system-zone ISO date | `#{sysdate()}` |
| `systimestamp` | Return system-zone offset timestamp | `#{systimestamp()}` |
| `formatDate` | Format an ISO-8601 value | `#{formatDate(${CASE.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `dateAdd` | Add a calendar/time amount | `#{dateAdd(${CASE.businessDate}, 1, 'day')}` |
| `fileExists` | Test whether a regular file exists | `#{fileExists(${CASE.requestFile})}` |
| `directoryExists` | Test whether a directory exists | `#{directoryExists(${CASE.outputDirectory})}` |
| `fileSize` | Return regular-file size in bytes | `#{fileSize(${CASE.requestFile})}` |
| `makeDirectories` | Create a directory tree and return its absolute path | `#{makeDirectories(${CASE.archiveDirectory})}` |
| `copyFile` | Copy a regular file and return the target path | `#{copyFile(${CASE.requestFile}, ${CASE.backupFile}, true)}` |
| `moveFile` | Move a regular file and return the target path | `#{moveFile(${CASE.sourceFile}, ${CASE.targetFile})}` |
| `deleteFile` | Delete a non-directory file | `#{deleteFile(${CASE.temporaryFile}, true)}` |
| `randomChoice` | Return one of 1–1000 input values | `#{randomChoice('A', 'B', 'C')}` |

`upper`, `lower`, `trim`, `ltrim`, `rtrim`, `string`, `number`, `boolean`, and `length` require exactly one argument and accept either `value=...` or one unnamed value. Other built-ins accept either their documented names or a complete positional list; do not mix named and positional arguments in one call. Case conversion is locale-independent. `number` rejects non-numeric input and removes unnecessary trailing zeroes. `boolean` accepts true/false, yes/no, and 1/0. `concat` treats null as empty; `coalesce` skips null and whitespace-only values and returns empty when none qualifies. `nvl` tests null/empty without trimming. `iif` accepts the same boolean text forms and resolves all three arguments eagerly. `nchar` requires an integer count from 0 through 10000 and repeats the complete value.

`substr(value, start[, length])` uses zero-based UTF-16 indexes. A negative start counts from the end; an out-of-range start or negative length is an error, while an overlong length stops at the end. `indexOf` is case-sensitive, accepts an optional zero-based `fromIndex`, and returns `-1` when absent. Match and replacement functions are case-sensitive and literal, not regular expressions. Padding defaults to one space, never truncates an already long value, rejects an empty pad, and limits target length to 10000.

`sysdate()` returns `yyyy-MM-dd`. `systimestamp()` returns `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`; both use the JVM system zone at invocation time. `formatDate` accepts ISO local dates, local date-times, offset/zoned timestamps, and UTC instants, then applies a locale-independent Java `DateTimeFormatter` pattern. `zoneId` accepts an IANA name such as `Asia/Hong_Kong` or an offset such as `+08:00`; it converts instant/offset/zoned values and attaches a zone to a local date-time. `dateAdd` preserves the input ISO shape and accepts singular/plural `year`, `month`, `week`, `day`, `hour`, `minute`, `second`, or `millisecond`; incompatible combinations such as hours plus a date-only value are errors.

Filesystem built-ins resolve relative paths against the ATT JVM working directory and return normalized absolute paths from create/copy/move operations. Existence and size functions accept only their documented regular-file or directory type and do not follow the final symbolic link. Copy and move reject symbolic-link sources/targets, create missing target parents, and default `overwrite` to `false`; an existing target is an error unless `overwrite=true`. `deleteFile` rejects directories, may delete a file or symbolic link itself, and defaults `missingOk` to `false`. Filesystem errors produce action ERROR and these in-process operations create no TOOL process artifacts.

`randomChoice` accepts either a complete positional list or consistently named values, preserves the selected value's type, and rejects zero, more than 1000, or mixed-style inputs. Selection is deliberately non-deterministic and is intended for test-data variation, not cryptography or reproducible sampling.

Use built-ins for in-process transformations, time values, and simple local file operations; use tools when filesystem work needs process evidence or for network, database, system integration, or complex reusable logic. Built-ins remain global. V2.3.4 retains an internal provider boundary for a future release, but configuration cannot load custom Java classes. Invalid arguments produce action ERROR.

Typical expressions:

```yaml
assert: "${CASE.amount} > 0"
assert: "${ACTIONS.callApi.output.result.status} == ${CASE.expectedStatus}"
assert: "${ACTIONS.callApi.output.result.message} like 'PAYMENT%SUCCESS'"
assert: "(${CASE.channel} == 'MOBILE') and (${CASE.amount} <= 1000)"
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

An expanded case contains the full Case ID and name, status and duration, Expected and Actual results, one row per recorded action result, the persisted Stage/Template/Action/Tool Context tree, detailed execution log, and artifact links. Expected is the ordered LF-joined non-blank descriptions and `expected` values from assert actions; Actual is the ordered LF-joined non-blank runtime `actual` values. Depending on what ran, the tree/log show selected templates, executed or skipped stages/actions, assertion messages, tool arguments and argv, stdout/stderr, raw and parsed output, retry attempts, diagnostics, and saved payload/tool-output paths. Workbook ID, group ID, and tags are persisted per case in `run.yaml`, so `report --run-id` regenerates equivalent controls and grouping.

`report/junit.html` is a human-readable JUnit projection. It displays counts and one row per testcase with status, duration, and embedded case-log content or a relative artifact link.

### Result workbook

ATT copies the source workbook and appends configured result columns using `report.mode: append-to-copy`. Global `report.fileNamePattern` controls its filename. Sidecar `report.columns` changes workbook labels only. Supported mappings include `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, and `runTime`; Expected/Actual cells retain LF characters and use wrapped text.

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

`run.yaml` uses `schemaVersion: att-run/v2.1` and records ATT/build identity, Java/OS/locale/timezone, validation mode, environment, timestamps, status/summary, output paths, and SHA-256 inputs for effective configuration, tool-group files, workbook, sidecar, resolved templates/payloads, package-local tool files, and schema/catalog version.

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
