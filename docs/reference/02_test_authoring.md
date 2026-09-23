## 02 Test Authoring

<!-- Transitional placement produced by issue #41. #42 owns semantic reorganization. -->

### 02 Quick Start

This example creates one payment test that renders JSON, invokes a tool, and verifies the returned status.

#### Step 1: configure ATT

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

#### Step 2: create the workbook and sidecar

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

#### Step 3: create the template

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

#### Step 4: create the mock tool

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

#### Step 5: validate and run

```sh
./att.sh validate --package
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001 --run-id SIT-001 --ci-output junit,json
```

On Windows, replace `./att.sh` with `att.bat`; command names, options, output, and exit codes are identical. This Quick Start tool is a POSIX shell example, so a Windows package must configure an equivalent `.bat`, `.cmd`, PowerShell, or native executable before running that tool.

Use package validation as the release gate. During development, a faster selected check is available:

```sh
./att.sh validate --selected --suite testcase/payment.xlsx --case payment.payment.TC001
```

#### Step 6: inspect the result

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

### 03 User Guide

This chapter explains the normal day-to-day workflow in the same order that data moves through ATT.

#### 3.1 Workbook

##### Workbook, sidecar, and snapshot relationship

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

##### Mapping data columns

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

##### Blank values

`N/A`, `NA`, `NULL`, `NONE`, empty cells, and whitespace-only values normalize to blank. An ordinary blank data value becomes the empty string. A blank `(yaml)` cell remains blank rather than being parsed.

A required stage selector rejects a blank value. An optional stage with a blank selector is skipped.

##### Formula, date, percentage, and scientific notation cells

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

##### Multi-row headers

`headerRows: 2` means rows 1–2 are headers and data begins at row 3. ATT scans each physical column top-to-bottom and uses its last non-empty trimmed header cell:

```text
Row 1: Basic data |           | Execution |
Row 2: Case ID    | Case name | Template  | Parameters
Effective: Case ID, Case name, Template, Parameters
```

ATT does not concatenate parent and child labels. Header matching removes spaces, tabs, line breaks, non-breaking spaces, and other Unicode whitespace from both the effective Excel header and configured sidecar/report label; matching otherwise remains case-sensitive. For example, `案例 編號`, `案例\n編號`, and `案例編號` identify the same column. Every effective header must exist exactly once after this normalization, so two physical headers that differ only by whitespace are a duplicate-header error. Testcase loading and result-workbook writing use this same projection; result columns that do not already exist are written to the final header row.

##### Stages and template selection

Each sidecar stage has a dot-free `key` and a `template` field naming the physical Excel selector column. The selector cell may contain a symbolic template name, full relative template path, or YAML map:

| Cell value | Meaning |
|---|---|
| `PAYMENT_INVOKE` | Symbolic-name shorthand |
| `payment/local/CT001` | Full-path shorthand relative to `templates.root` |
| `name: PAYMENT_INVOKE` | Explicit symbolic-name map |
| `name: PAYMENT_INVOKE` plus other keys | Template selection plus stage-private row data |

ATT first resolves `name` as a globally unique symbolic name. Only when no symbolic name matches does it try a complete relative template path. Absolute paths, partial paths, and paths escaping `templates.root` are invalid.

All selector-map keys, including `name`, are copied into the stage Context. `stages[].dataColumns` adds more stage-private values. A duplicate key between the selector map and stage data columns is an error.

##### Stage execution controls

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

#### 3.2 Template

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

##### Action types

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

#### 3.3 Tool

A Tool is a named capability configured either globally under `config.yaml` or inside an independent tool-group file. A command-backed Tool launches an external process; a V2.6 call-backed Tool invokes one typed DB operation or pure built-in. Both use the same outer call syntax and named-argument contract.

##### Tool backend selection and the common Action envelope

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

##### Database helpers

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

###### DB Actions

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

###### Rendered SQL and SQL files

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

###### DB queries in expressions

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

###### Result and lifecycle

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

##### IBM MQ helpers

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

##### Call-backed Tools (V2.6)

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

###### Scenario: typed query in an expression

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

###### Scenario: scalar assertion

```yaml
checkOrderCount:
  type: assert
  assert: "#{orders.count(customerId=${EXEC.INPUT.customerId})} >= 1"
  expected: Customer has an order
  actual: Count returned by orders.count
```

`scalar` still requires exactly one row and one column. Zero, multiple, or multi-column results are `ERROR`.

###### Scenario: primary Tool Action and saveAs

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

###### Scenario: update façade

WRITE façades are permitted only as the primary call of `type: tool`:

```yaml
closeOrder:
  type: tool
  call: "#{orders.updateStatus(orderId=${EXEC.INPUT.orderId}, status='CLOSED')}"
  assert: "${output.result.affectedRows} == 1"
```

`#{orders.updateStatus(...)}` inside `assign`, `assert`, payload text, another Tool argument, or SQL is rejected during validation and again at runtime. DB errors make the Action and Case `ERROR`, not `FAIL`.

###### Scenario: wrap a pure built-in

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

###### Cache scopes and stale-read contract

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

###### Timeout, retry, lifecycle, and evidence

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

##### Command processing

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

##### SSH execution

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

##### Input, output, timeout, and status

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

##### Post-invocation evidence collectors

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

#### 3.4 Running Tests

##### Validate first

```sh
./att.sh validate --package
```

Package mode is the default and checks global configuration, every discovered workbook/sidecar, configured sheets and rows, all templates including unreferenced ones, expressions, tools, paths, and package integrity. It never invokes external tools and is the required release gate.

```sh
./att.sh validate --selected --case payment.payment.TC001
```

Selected mode checks only explicitly selected cases and their dependency closure. It is useful for fast authoring feedback and reports that unselected content was not checked. `run` always performs selected-scope validation for its immutable execution plan.

##### Select cases

| Option | Meaning |
|---|---|
| `--all` | Discover all workbook/sidecar pairs recursively below `testcase.root` |
| `--suite <xlsx>` | Select one workbook; repeatable |
| `--suite-dir <dir>` | Discover workbooks below another directory |
| `--case <workbookId.groupId.rowCaseId>` | Select one complete Case ID |
| `--tag <tag>` | Include cases with any requested tag |
| `--exclude-tag <tag>` | Exclude matching cases after inclusion filters |

An empty selection is an error. `--rerun-failed` is itself a valid selection and reads FAIL/ERROR Case IDs from the latest completed persisted run. Additional `--case`, `--tag`, and `--exclude-tag` filters narrow that set. Missing history, no prior FAIL/ERROR cases, or no currently discoverable case matching the saved IDs is a command error. The current workbook, sidecar, testcase XML snapshot, template, and tool definitions are validated and executed; ATT does not replay old run inputs.

##### Execute

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

##### Result and exit code

| Highest result present | Run status | Exit code |
|---|---|---:|
| ERROR | ERROR | 3 |
| INVALID without ERROR | INVALID | 2 |
| FAIL without ERROR/INVALID | FAIL | 1 |
| At least one PASS and only PASS/SKIPPED | PASS | 0 |
| All selected cases SKIPPED | SKIPPED | 0 |

Assertion false is FAIL. Expression evaluation, process, timeout, parsing, I/O, configuration, and validation failures are ERROR or INVALID according to their phase. A run containing both FAIL and ERROR exits 3.

#### 3.5 Reports

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

### 04 Cookbook

This chapter starts from a task you want to perform and shows the corresponding ATT pattern.

#### Run rollback after failure and cleanup every time

```yaml
stages:
  - {key: invoke, template: 執行模板, required: true}
  - {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
  - {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

Rollback runs only after an earlier failure. Cleanup runs regardless of the earlier result. A rollback or cleanup failure is still retained in final aggregation.

#### Override result-workbook column labels

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

#### Parse JSON tool output

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

#### Access XML attributes, text, and repeated elements

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

#### Pass a list as separate process arguments

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

##### Linux Bash parsing examples

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

#### Retry assertion polling or timeout

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
