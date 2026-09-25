# ATT V3.5.2 Reference Manual

Author: Jeffrey + ChatGPT
Version: 3.5.2
Status: Normative end-user documentation; generated from modular sources

<!-- GENERATED FILE. Edit docs/reference*/ modules, not this combined output. -->
## 01 Overview and Concepts

ATT separates test intent from integration mechanics. Test data is versioned in workbook/sidecar/snapshot form; Templates and Flows define reusable behavior; Resources connect that behavior to external systems.

### Product model

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- render / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

A **Testcase** is one normalized workbook row. A **Stage** selects a Template and contributes stage-private data. A **Template** is the executable scenario boundary. A **Flow** is reusable Template logic with an isolated Action scope. An **Action** is one ordered unit of work. A **Resource** is a configured Tool, DBHelper or MQHelper used by Actions or permitted expression calls.

### Execution modes are peers

Run, Debug and Load adapt different inputs into the same execution-neutral Context and reusable components:

| Mode | Primary input | Reuses |
|---|---|---|
| Run | workbook Testcases and Stage selectors | Templates, Flows, Tools, DB/MQ |
| Debug | `att-debug/v1.0` sidecar or `--input` | one Template, Flow or Tool target |
| Load | `att-load/v1.0` scenario | one Template, Flow or Tool target repeatedly |

Mode-specific identity is carried by `EXEC.MODE` and, for load only, `EXEC.LOAD`. Reusable Templates/Flows should normally depend on `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, `META`, and Action-local `output`, not on a second mode-specific runtime tree.

### Resources are peers

Tool, DBHelper and MQHelper are independent resource types. They differ in configuration and lifecycle but publish operation data into one common Action envelope. Public expressions should consume Action results/evidence rather than resource-internal connection/process state.

```text
Tool ----\
DBHelper --+--> operation result/evidence --> Action output
MQHelper -/
```

### Package boundaries

A normal package contains `config/`, `testcase/`, `templates/`, `tools/`, `schemas/` and generated `output/`. Paths and identifiers are validated before execution. Credentials belong in environment variables or external secret handling, not committed YAML.

For a guided package build, use `docs/quick-start.md`. The rest of this manual is normative lookup documentation.

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

For the common Tool/DB `saveAs` object, `path` is optional: a pathless object keeps the typed result in memory and creates no artifact. `saveAs` is still validated when present, so target-specific format defaults and restrictions apply even without a path; if `path` is present, its safety, collision, and overwrite rules apply as well.

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

## 03 Runtime and Context Model

ATT uses one public Context model for Run, Debug and each Load iteration.

### Canonical tree

```text
EXEC
├── ID
├── MODE
├── STARTED_AT
├── OUTPUT_DIR
├── INPUT
├── VARS
├── ACTIONS
└── LOAD          # load mode only

META
├── PROJECT
├── SOURCE
├── TARGET
├── TEMPLATE
├── FLOW
├── TOOL
├── DBHELPER
└── MQHELPER

output            # current Action only
```

`EXEC.INPUT` contains execution inputs adapted from the workbook/stage, debug sidecar, or load scenario. `EXEC.VARS` is the mutable publication area for `assign` and other explicit shared values. `EXEC.ACTIONS` contains completed Actions in the **current** Stage/Template/Flow scope. `META` is curated, immutable and secret-safe. `output` is the active Action's local result and is not a persistent root.

### Scope and lifetime

A normal Testcase owns its Case runtime. `EXEC.VARS` can carry explicitly published values across its Stages/Templates. Each Stage/Template starts a fresh `EXEC.ACTIONS` scope. Invoking a Flow temporarily installs a fresh Action scope for the Flow; nested Actions can read earlier Flow Actions, and the caller's Action scope is restored when the Flow returns. Values needed after a Flow returns must be published through `EXEC.VARS`.

There is no public `EXEC.STAGES`, `EXEC.OUTPUT`, `EXEC.CALL`, or invocation-worker tree. Stage/Flow history belongs to result/report evidence, not reusable expression state. Resource connection/pool/process lifecycle state is internal.

### Action-local output and publication

Executable Actions publish a stable envelope. Fields are present where meaningful:

```text
output
├── status
├── success
├── durationMs
├── result
├── diagnostic
├── evidence
└── attempts[]
```

While an Action is active, use `${output...}`. After it completes in the current scope, use `${EXEC.ACTIONS.<id>.output...}`. `result` is the final/winning primary operation result. Retry history and per-attempt collectors remain under `attempts[n]`; they do not replace the top-level final result.

### Load-only Context

`EXEC.LOAD` is conditional data added to the same Context model, not a second runtime. It may contain:

```text
EXEC.LOAD
├── RUN_ID
├── WORKLOAD_ID     # att-load/v1.1 multi-workload runs
├── MODEL
├── USER_ID         # closed-VU only
├── TARGET_TYPE     # v1.1 workload target identity
├── TARGET_ID       # v1.1 workload target identity
├── ITERATION_ID
├── ITERATION
├── PHASE
└── RUN_STARTED_AT
```

For `att-load/v1.1`, `WORKLOAD_ID` is the configured workload `id`. `TARGET_TYPE` and `TARGET_ID` identify the fixed target owned by that workload. Closed workloads provide a stable `USER_ID` for one virtual user; fixed-arrival-rate iterations have no persistent VU identity.

Different closed-VU workload pools may both contain a `VU-1`. The durable identity is therefore the pair `(EXEC.LOAD.WORKLOAD_ID, EXEC.LOAD.USER_ID)`. ATT does not introduce a separate `EXEC.USER` root or shared mutable VU Context; each iteration still gets isolated `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, transient Tool/DB state and Action-local `output`.

### Optional lookup

`${path}` is strict. `${path?}` returns null for a missing map/list path where optional lookup is defined, but it does not make malformed syntax, ambiguity, invalid traversal, or illegal scope access valid.

### Compatibility aliases

Deterministic legacy views such as `CASE`, `RUN`, and `ACTIONS` remain readable where they map one-to-one to canonical data and may produce migration warnings. New documentation and new authoring use `EXEC`/`META`. Semantically incompatible historical paths such as stage-history-as-runtime-state are errors rather than aliases.

## 04 Execution Modes

Run, Debug and Load are peer adapters over the same reusable Template/Flow/Tool/DB/MQ execution semantics.

| Mode | Context `EXEC.MODE` | Unit of execution | Primary result location |
|---|---|---|---|
| Run | `testcase` | selected Testcase | `output/<RunID>/` |
| Debug | `debug` | one target invocation | `output/debug/<debugId>/` |
| Load | `load` | repeated target iterations | `output/load/<runId>/` |

All three resolve the selected environment before execution, construct canonical Context, validate the target/dependency closure, and use the same component contracts. Mode-specific scheduling, selection and reporting do not create alternate Template or expression semantics.

### 4.1 Run Mode

Run is workbook-driven Testcase execution.

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT loads the effective configuration/environment, verifies canonical workbook snapshots, validates the selected dependency closure, reserves a unique Run ID, then executes selected Cases in Stage order. A Stage resolves its selector to a Template; Actions execute in YAML order subject to `runWhen` and `onFailure`.

Run evidence is written directly below `output/<RunID>/`. The completed run publishes `run.yaml`, Case directories/logs, result workbooks, HTML/CI outputs as configured, and only after completion updates `latest-run.yaml`. A pre-existing Run ID is rejected rather than overwritten. `run --update-snapshot` is the explicit opt-in snapshot refresh path before validation/execution.

Status aggregation preserves severity: ERROR > INVALID > FAIL > PASS > SKIPPED. Process exit code is `0` when the run completes without failing status, `1` for test/assertion failure, `2` for invalid command/configuration/validation, and `3` for runtime/infrastructure error.

Use Chapter 10 for exact selectors/options, Chapter 8 for execution control, and Chapter 11 for artifact contracts.

### 4.2 Standalone Debug

Debug executes one Template, Flow or Tool without requiring a workbook Testcase.

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

Debug input uses `schemaVersion: att-debug/v1.0`. Supported top-level data is `case`, optional `stage`, `inputs`, `arguments`, and grouped `tools.<localKey>.arguments`. `inputs` is adapted into canonical `EXEC.INPUT`; framework-owned identity, output, Actions, resource metadata and compatibility views cannot be overwritten by user input.

Without `--input`, ATT looks for `debug.yaml` beside a selected Template or Flow and for `config/tools/<group>.debug.yaml` for a grouped Tool. When no default sidecar exists, supply `--input`. `--env` uses the same environment resolver as Run/Validate/Load before target validation.

Debug performs target-scoped validation: it validates the selected Template/Flow dependency closure or Tool contract, rather than requiring unrelated workbooks. Template and Flow debug use the same Action/Flow scope rules as Run. Tool debug constructs the same configured Tool invocation contract.

Each invocation is isolated under:

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug does not create or update normal `latest-run.yaml`. Exit codes are `0` PASS, `1` FAIL, `2` invalid CLI/config/input/validation, and `3` runtime error. It is execution-equivalent at the selected reusable-component boundary, but it is **not** a workbook Case: there is no workbook selection, Stage history or result-workbook lifecycle unless explicitly represented by debug inputs/artifacts.

### 4.3 Load Mode

ATT Load executes Template, Flow or Tool targets through a bounded load-run lifecycle. `att-load/v1.0` remains the single-target compatibility contract; `att-load/v1.1` adds multiple independently paced workloads in one run.

#### Single-target compatibility (`att-load/v1.0`)

```yaml
schemaVersion: att-load/v1.0
target: {type: template, id: V3_FLOW_EXAMPLE}
inputs: {region: HK}
load:
  users: 20
  duration: 5m
execution:
  thinkTime: 500ms
thresholds:
  errorRate: "< 1%"
  p95: "< 800ms"
```

`target.type` is `template`, `flow`, or `tool`. Only Tool targets accept `target.arguments`. Scenario `inputs` become each iteration's `EXEC.INPUT`. Existing v1.0 scenarios continue through the original single-workload path.

#### Multi-workload contract (`att-load/v1.1`)

A v1.1 scenario owns one or more named `workloads`. Each workload has a stable `id`, one fixed target, optional inputs/Tool arguments, its own load settings, and optional workload thresholds.

Independent arrival-rate example:

```yaml
schemaVersion: att-load/v1.1
workloads:
  - id: payment
    target: {type: template, id: PAYMENT}
    load:
      arrivalRate: 80/s
      duration: 10m
      maxConcurrent: 200
      overloadPolicy: drop
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"

  - id: balance
    target: {type: template, id: BALANCE_INQUIRY}
    load:
      arrivalRate: 20/s
      duration: 10m
      maxConcurrent: 100
      overloadPolicy: drop

  - id: customer
    target: {type: flow, id: CUSTOMER_LOOKUP}
    load:
      arrivalRate: 5/s
      duration: 10m
      maxConcurrent: 30
      overloadPolicy: drop

thresholds:
  errorRate: "< 0.5%"
  minThroughput: ">= 100/s"
```

These are three independent arrival generators, not one 105/s generator randomly choosing targets. PAYMENT remains paced at 80/s, BALANCE at 20/s and CUSTOMER at 5/s; each workload owns its own `maxConcurrent` and drop behavior.

Closed-VU pool example:

```yaml
schemaVersion: att-load/v1.1
seed: 12345
workloads:
  - id: payment
    target: {type: template, id: PAYMENT}
    load:
      users: 60
      duration: 10m
    execution:
      thinkTime:
        min: 300ms
        max: 1s

  - id: balance
    target: {type: template, id: BALANCE_INQUIRY}
    load:
      users: 30
      duration: 10m
    execution:
      thinkTime: 500ms

  - id: enquiry
    target: {type: flow, id: CUSTOMER_ENQUIRY}
    load:
      users: 10
      duration: 10m
```

Here 60 VUs always execute PAYMENT, 30 always execute BALANCE and 10 always execute ENQUIRY. This is **not** transaction mix: one VU does not switch targets during the run. Transaction-mix selection is a separate feature.

#### v1.1 lifecycle and validation

All workloads in one v1.1 run must use the same scheduler model: either all `arrivalRate` or all `users`. Mixed open/closed workload models are rejected. All workloads must also use the same `warmup`, `rampUp`, `duration` and `rampDown` envelope. This gives all child schedulers one common monotonic T0 and aligned phase windows.

Before scheduling starts, ATT resolves and validates **every** workload target and dependency. If any workload is invalid, no workload begins execution. Workloads share the same run-scoped DB/MQ resource layer so they contend realistically for configured pools, while mutable iteration Context and output remain isolated.

For v1.1, the existing `EXEC.LOAD` node additionally exposes `WORKLOAD_ID`, `TARGET_TYPE`, and `TARGET_ID`. Closed pools retain stable `USER_ID`. Because separate pools may each contain `VU-1`, the durable virtual-user identity is `(WORKLOAD_ID, USER_ID)`.

#### Workload models

**Closed VU** uses positive `load.users`. One virtual user repeatedly runs its workload's fixed target and waits for completion before its next iteration. `execution.thinkTime` applies between completed iterations.

**Fixed arrival rate** uses `load.arrivalRate` plus positive `maxConcurrent` and `overloadPolicy: drop`. It has no persistent VU identity. Arrivals that cannot start because the workload's concurrency limit is full are recorded as `dropped`; they are not queued and are not counted as SUT errors. Arrival-rate workloads reject `execution.thinkTime`.

`duration` is required. Optional `warmup`, `rampUp`, and `rampDown` define phases; `EXEC.LOAD.PHASE` identifies `WARMUP`, `RAMP_UP`, `STEADY`, or `RAMP_DOWN`. Warm-up traffic executes but is excluded from measured threshold aggregates.

#### Closed-VU think time and deterministic randomization

The fixed-duration form remains valid:

```yaml
execution:
  thinkTime: 500ms
```

Closed VUs may alternatively use a uniform range:

```yaml
execution:
  thinkTime:
    min: 500ms
    max: 2s
```

Both endpoints use ATT integer duration syntax (`ms`, `s`, `m`, `h`), are inclusive after millisecond normalization, and `max >= min` is required. A new delay is sampled after each completed iteration. Sleep is clipped to the remaining load envelope and excluded from response latency.

Optional top-level `seed` supplies the run seed; otherwise ATT derives one from `runId`. Each v1.1 closed VU uses a deterministic stream derived from run seed + workload identity + `USER_ID`, preventing cross-workload/shared-RNG coupling. The report-safe summary records the effective seed for randomized think-time runs; individual sampled delays are not durably retained.

Checked-in offline examples are `examples/load/multi-arrival.yaml` and `examples/load/multi-closed.yaml`.

#### CLI overrides

Explicit CLI values continue to replace corresponding v1.0 scenario values:

`--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, `--overload-policy`.

For a v1.1 scenario containing **more than one workload**, these unscoped load-model overrides are ambiguous and are rejected before execution; YAML is authoritative for each workload. A single-workload v1.1 scenario may still use them. `--think-time <duration>` remains fixed-only; range overrides are not encoded into an ad-hoc CLI string.

#### Metrics, thresholds and evidence

A v1.1 report contains both aggregate and per-workload metrics. Aggregate counters and rates combine raw workload events. Aggregate latency percentiles are calculated from the aggregate latency collector; ATT never computes overall P95/P99 by averaging workload percentiles.

Workload thresholds live inside each workload; top-level v1.1 thresholds apply to the aggregate run. Any workload-threshold or global-threshold failure makes the run `FAIL`/exit `1`. A runtime/infrastructure error in any workload makes the run `ERROR`/exit `3`.

Supported thresholds include `errorRate`, `p95`, `p99`, `minThroughput`, `droppedRate`, and `achievedArrivalRate`, subject to workload-model compatibility.

Evidence policy remains run-scoped. For v1.1, retained artifacts are partitioned by workload where applicable. Each retained sample/failure record and its summary link identify the workload, target type and ID, iteration ID, and (for closed users) user ID:

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/<workloadId>/...
├── failures/<workloadId>/...
└── performance.json   # with --profile
```

DB/MQ resource diagnostics remain aggregate/run-scoped. Successful iteration workspaces are not retained by default.

#### Outputs and exit codes

`--profile` measures ATT generator/runtime overhead; it is not a target-host CPU/memory benchmark. Load exit codes are `0` PASS, `1` threshold failure, `2` invalid scenario/configuration/target, and `3` runtime/infrastructure error.

`EXEC.LOAD` is defined centrally in Chapter 3; artifact schemas and report details are in Chapter 11.

## 05 Resources and Integrations

Tool, DBHelper and MQHelper are peer integration/resource types. They have different descriptors and lifecycle rules but converge on the common operation-result/evidence contract in 5.4.

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
```

Resource IDs are logical contracts referenced by Templates/expressions. Environment profiles may bind the same DB/MQ logical ID to different descriptors without changing Action YAML.

### 5.1 Tool

A Tool is a named external or framework-native capability. A Tool declares exactly one backend: **command-backed** or **call-backed**.

#### Command-backed Tool

Command-backed Tools execute a configured argv contract locally or through configured SSH transport. Argv-list definitions preserve item boundaries; scalar command definitions are tokenized into the same internal argv model. ATT does not implicitly invoke a shell or expand wildcards for ordinary process-backed Tools. Stdout/stderr, exit code, timeout and process diagnostics are evidence; a non-zero process exit does not by itself define assertion PASS/FAIL unless the Action contract says so.

Use command-backed Tools for scripts, CLIs, SSH and third-party executables.

#### Call-backed Tool

Call-backed Tools execute typed framework-native calls, such as supported DB read/update façades or pure built-ins, without converting typed values into process strings. Use them when the capability is naturally represented by a typed ATT call contract rather than an external process.

Both backends publish the same public Action envelope. The primary value is `${output.result}` while active and `${EXEC.ACTIONS.<id>.output.result}` after publication. Final operation evidence is under `output.evidence`; retries preserve per-attempt evidence under `output.attempts[n].evidence`.

A Tool Action may use object `saveAs` and post-operation evidence collectors where permitted. Collectors execute after the primary operation and before that attempt's assertion; collector failure policy does not replace the primary `result`.

### 5.2 DBHelper

DBHelper is a first-class JDBC resource, configured independently from Tools. Each descriptor uses `schemaVersion: att-dbhelper/v2.5` and a stable logical `id`; global `dbhelpers` references descriptor files.

```yaml
schemaVersion: att-dbhelper/v2.5
id: orders
driverClass: oracle.jdbc.OracleDriver
url: ${ENV:ORDERS_DB_URL}
username: ${ENV:ORDERS_DB_USERNAME}
password: ${ENV:ORDERS_DB_PASSWORD}
```

Credentials may be resolved from environment variables and must not be published into `META`, reports or diagnostics. JDBC driver jars are supplied in `lib/`; ATT does not bundle a database driver.

A `type: db` Action selects one helper ID and exactly one `query` or `update` block. Read operations are also available through supported `#{db.<id>.query(...)}` / `scalar(...)` expression calls. Positional JDBC `?` bindings and direct-Action named `:name` parameters are supported by the documented contracts.

Queries return typed rows/scalars; updates return the documented update result. Operation and SQL/parameter evidence enters the common Action envelope. Secret credentials are never evidence. Parameter evidence follows descriptor/Action masking/type policy.

Direct DB Actions may declare `timeoutMs` from 1 to 3,600,000 ms. The executor applies the shorter effective limit between the Action timeout and the DBHelper `statement.timeoutSeconds`; each retry attempt gets a fresh Action timeout and the retry interval is outside that timeout.

A direct `query` Action may also use the standard retry block with `maxAttempts` 2–10, `intervalMs` 0–3,600,000, and a non-empty unique `retryOn` list containing `ASSERTION` and/or `TIMEOUT`. `ASSERTION` requires an Action `assert`. Ordinary SQL errors are terminal. Retry-enabled query attempts are retained in `output.attempts[n]`; the top-level `output.result` / `output.evidence` represent the final or winning attempt, with `winningAttempt` or `finalAttempt` recording the terminal attempt number.

```yaml
actions:
  waitForOrder:
    type: db
    db: orders
    timeoutMs: 1500
    query:
      sql: select status from orders where id = :id
      parameters:
        id: "${EXEC.INPUT.orderId}"
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

Direct `update` Actions support `timeoutMs` but deliberately reject `retry`. A timeout or database/transport failure cannot generally prove whether a mutation reached or committed at the server, so generic automatic replay could duplicate business state. Application-specific idempotent retry must be modeled explicitly instead.

DBHelper owns connection/statement limits, query timeout and transaction behavior defined by its descriptor. Transaction finalization is tied to the Case/iteration lifecycle; commit/rollback/reconnect are resource operations, not public Context roots. Action-level timeout/retry extends the shared Action lifecycle without changing the DBHelper identity or Context model.

### 5.3 MQHelper

MQHelper is a first-class IBM MQ resource. Each descriptor uses `schemaVersion: att-mqhelper/v1.0` or `att-mqhelper/v1.1`, with a stable logical `id`, connection topology and optional credentials. Global `mqhelpers` references descriptor files; environment profiles may select a different descriptor for the same logical ID. v1.0 remains the compatible single-instance form; v1.1 adds logical groups of physical instances.

Primary calls are:

```text
#{mq.<id>.send(...)}
#{mq.<id>.receive(...)}
#{mq.<id>.request(...)}
```

Payloads are file-based so request bytes do not have to be duplicated into Context/evidence. `request` combines send and correlated receive behavior. Correlation identifiers, queue/operation metadata, timing and diagnostic information are evidence; credentials and payload bytes are not copied into evidence.

Timeout behavior is operation-specific and remains distinct from assertion failure. MQ connection/pool lifecycle is framework-owned resource state, especially in Load mode; it is not exposed as a public `EXEC.MQ` tree.

ATT's default build does not require IBM MQ client classes. Runtime MQ use requires the IBM MQ client jar/profile documented by the package/release instructions. MQ operations feed the same Action result/evidence envelope as Tool and DB operations.


#### Issue #59 configuration and public contract

A complete descriptor can contain connection, message, requestReply, evidence, and pool fields:

~~~yaml
schemaVersion: att-mqhelper/v1.0
id: ordersMq
name: Orders MQ
description: IBM MQ connection used by SIT/UAT order tests
connection:
  queueManager: QM1
  host: 10.12.13.14
  port: 1414
  channel: CHANNEL
  username: ${ENV:MQ_USERNAME}
  password: ${ENV:MQ_PASSWORD}
message:
  charset: 1208
  encoding: 273
  format: ""
  persistence: asQueue
  expiry: -1
  requestQueue: requestQ
  replyQueue: replyQ
requestReply: {waitMs: 40000}
evidence: {payload: metadata}
pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
~~~

username and password map to MQConstants.USER_ID_PROPERTY and PASSWORD_PROPERTY before constructing MQQueueManager. `evidence.payload` accepts `metadata` or `none`; `metadata` keeps only the policy marker in evidence, while `none` omits it. Environment credentials are secret and never enter evidence, logs, reports, or generated docs.

message.charset is an integer IBM MQ CCSID for MQMessage.characterSet, not a Java charset name. ccsid remains a compatibility alias and must equal charset when both are present. encoding maps to MQMessage.encoding. Empty message.format is valid and remains empty; named values MQSTR, MQFMT_STRING, MQHRF2, MQFMT_NONE, and NONE remain supported. persistence accepts asQueue/0, persistent/1, and notPersistent/nonPersistent/2. expiry -1 means MQEI_UNLIMITED; positive values use IBM MQ tenths-of-a-second units, not milliseconds.

requestQueue and replyQueue are optional request defaults. Queue precedence is call argument > message default > validation error. send(queue=...) and receive(queue=...) do not use these defaults. Request payload files stay byte-preserving through MQMessage.write(byte[]). Only the request output queue uses `MQOO_BIND_NOT_FIXED`; send output uses ordinary `MQOO_OUTPUT`, and reply input uses shared input. ATT sets MQPMO_NEW_MSG_ID and correlates reply correlationId to the generated request MsgId with MQGMO_WAIT, MQMO_MATCH_CORREL_ID, and waitInterval from waitMs. ATT uses NO_SYNCPOINT for put/get and does not call legacy commit(). `encoding` is validated as a legal IBM MQ integer/decimal/float encoding combination before the descriptor is accepted.

#### Common saveAs

MQ receive/request use the common Action saveAs object; no MQ-specific resultType/replyType exists.

~~~yaml
saveAs:
  format: raw
  path: response.bin
  overwrite: false
~~~

format defaults to raw and path is optional. raw gives the original byte[], text gives String, and json/yaml/xml give existing ATT typed values. No saveAs or saveAs: {} keeps the result in memory and creates no file. `path: console` writes the selected representation to the Case log and creates no `output.targetFiles` entry or file. No .reply.bin is created unless a real path is explicit. raw plus a real path writes exact bytes; overwrite/path safety follow the common Action rules.

~~~yaml
- id: requestXml
  type: tool
  call: "#{mq.ordersMq.request(file='request.xml')}"
  saveAs: {format: xml}
  assert: "${output.result.Response.Status} == 'SUCCESS'"

- id: requestXmlSaved
  type: tool
  call: "#{mq.ordersMq.request(file='request.xml')}"
  saveAs: {format: xml, path: responses/payment.xml, overwrite: false}

- id: receiveReply
  type: tool
  call: "#{mq.ordersMq.receive(queue='replyQ', correlationId=${EXEC.ACTIONS.sendRequest.output.messageId}, waitMs=40000)}"
  saveAs: {format: json}
~~~

#### Output and validation

output.result is the business payload; MQ metadata is directly under output. Every operation publishes `mqHelper`, selected physical `instance`, `queueManager`, and `selectionStrategy`; v1.0 and single-instance helpers report `selectionStrategy: single`. send publishes sent, queue, bytes, messageId, correlationId, and leaves result null/absent. `send` does not produce a business payload and rejects `saveAs`; `receive` and `request` support it. receive/request publish received or replyReceived, queue names, effective waitMs, messageId, replyMessageId, replyCorrelationId, byte counts, reply CCSID/encoding/format when supplied by MQ, completion/reason fields, and put the parsed payload only in result. A normal request satisfies output.messageId == output.replyCorrelationId. MQRC 2033 leaves result null and publishes received/replyReceived false plus reasonCode 2033, MQRC_NO_MSG_AVAILABLE, and the effective waitMs.

Public MsgId/CorrelId values are lowercase hex, two characters per byte, no separators, with leading zeroes; a 24-byte ID is 48 characters. Raw runtime values remain byte[]. Typed reply decoding uses the received MQMessage.characterSet/CCSID when available, with an explicit IBM MQ CCSID-to-Java charset resolver and the configured charset as fallback; unsupported CCSIDs fail clearly. Logs/reports display raw bytes using new String(rawBytes, Charset.defaultCharset()) semantics, not hex and not an implicit file. Validation rejects unknown fields, conflicting charset/ccsid, invalid encoding/expiry/queues, missing effective request/reply queues, unsupported saveAs formats, and unsafe paths.

#### Issue #60 v1.1 logical groups and physical instances

`att-mqhelper/v1.1` keeps one public logical helper id while declaring one or more physical connection instances. A v1.0 descriptor remains valid without changes. The v1.1 descriptor has group defaults and per-instance overrides for `connection`, `message`, `requestReply`, and `pool`:

~~~yaml
schemaVersion: att-mqhelper/v1.1
id: payment
name: Payment MQ
description: Payment MQ endpoints
defaults:
  connection:
    queueManager: QM1
    host: mq.default.example
    port: 1414
    channel: APP.SVRCONN
    username: ${ENV:MQ_USERNAME}
    password: ${ENV:MQ_PASSWORD}
  message: {charset: 1208, requestQueue: PAYMENT.REQUEST, replyQueue: PAYMENT.REPLY}
  requestReply: {waitMs: 40000}
  pool: {maxSize: 20, minIdle: 2, borrowTimeout: 2s}
instances:
  - id: payment-a
    connection: {host: mq-a.example}
  - id: payment-b
    connection: {host: mq-b.example}
    message: {replyQueue: PAYMENT.REPLY.B}
selection: {strategy: roundRobin}
evidence: {payload: none}
~~~

Each physical instance is materialized into an immutable effective configuration before an invocation. For every section the precedence is invocation override, instance override, group default, runtime default, then validation error. Effective `queueManager`, `host`, `port`, and `channel` are required; username/password are optional and are never emitted as evidence.

The public call remains logical:

~~~text
#{mq.payment.send(queue='PAYMENT.REQUEST', file='request.bin')}
#{mq.payment.request(file='request.bin', instance='payment-b')}
#{mq.payment.receive(queue='PAYMENT.REPLY', instance='payment-a')}
~~~

A single-instance v1.1 group uses that instance directly. A group with multiple instances must declare `selection.strategy: random` or `roundRobin`; selection occurs once per MQ invocation, before connecting, so a `request` PUT and correlated GET always use the same physical instance. An explicit `instance` call argument selects that physical id and is rejected when it is unknown. Each physical instance has an isolated pool; pool identity is logical id plus physical id.

Output and evidence retain the logical helper id and expose the selected physical instance. Evidence also records the applicable strategy, queue manager, operation, queue names, MsgId/CorrelId, and safe connection metadata. With `evidence.payload: none`, the payload policy marker is omitted; credentials and payload bytes are never included. Validation rejects duplicate physical ids, unknown inherited fields, missing effective connection fields, invalid strategies or overrides, and invalid effective message/requestReply/pool values.

`output.selectionStrategy` identifies the configured group policy (`single`, `random`, or `roundRobin`), not the selection source for an individual invocation. When a call explicitly supplies `instance`, that policy value remains unchanged and `output.instance` identifies the physical instance actually selected.

### 5.4 Common Operation Result and Evidence

Tool, DB and MQ executors converge at one operation boundary before the Template runner applies Action lifecycle, assertions and retry policy.

```text
operation
├── result
├── evidence
├── executionSuccess
├── diagnostic
└── timing
       |
       v
Action output
├── status / success
├── durationMs
├── result          # final/winning primary operation
├── diagnostic
├── evidence        # final operation evidence
└── attempts[]      # retry history + per-attempt evidence
```

`result` is business/operation data. `evidence` is supporting execution data. `diagnostic` explains an operational failure. `status` is the Action-level classification after operation outcome and assertion handling. These are intentionally different concepts.

Retries never publish multiple competing top-level results: only the final/winning primary operation is top-level. Each attempt retains its own evidence and collector results in `attempts[n]`. Connection pools, JDBC transaction objects, MQ sessions and process handles are internal lifecycle state and must not be treated as Context.

## 06 Environment and Test Data

Environment selection changes resource binding, not Action logic.

### Environment profiles

`att-config/v2.6` may declare an `environment` default and an `environments` map. `--config` selects the base configuration file; `--env` selects one named binding inside that configuration. Explicit `--env` wins over the configured default. Unknown environments fail before external execution.

Profiles are typed shallow bindings, not generic recursive YAML inheritance. Current profile-owned lists are `dbhelpers` and `mqhelpers`: when a profile supplies one of those lists it replaces that resource list; an omitted list inherits the common root list.

```yaml
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

The descriptor in every environment should expose the same stable logical IDs (`orders`, `payment`, etc.). Template/Flow/Action references therefore remain unchanged across SIT/UAT/PREPROD.

### Topology and secrets

Topology may vary by descriptor/environment. Secrets should be injected through `${ENV:NAME}` where the descriptor supports it and must not be committed or surfaced in effective metadata/diagnostics. Missing required environment variables are validation/configuration errors that identify the field/variable name without printing a resolved secret.

### Cross-mode consistency

Run, Validate, Debug and Load resolve the environment through the same effective-config step before their mode-specific work. `--env` therefore cannot be used as Action branching and does not create mode-specific helper IDs.

### Migration from separate configs

Existing separate `--config config/environments/sit.yaml` / `uat.yaml` workflows remain useful when whole configurations genuinely differ. Profiles are preferable when the package contract is common and only typed DB/MQ bindings vary. Separate configs remain preferable for materially different package policy, roots, Tool topology or configuration ownership.

### Test data extension point

Workbook/sidecar/snapshot remains the current Testcase data contract. Future logical environment-bound fixtures (#38) belong in this chapter and should follow the same stable logical-name principle rather than introducing environment branches into Actions.

## 07 Expressions and Built-ins

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

`EXEC.MODE` is `testcase`, `debug`, or `load`. `EXEC.LOAD` exists only when `EXEC.MODE=load`; ordinary TestCase and debug execution do not materialize it. `EXEC.INPUT`, `EXEC.VARS`, and `EXEC.ACTIONS` are the same mutable runtime state used by all modes, not parallel copies. The TestCase adapter overlays current Stage caller/input values onto `EXEC.INPUT` for the active Stage; Stage values win over Case-level values on collision and the Case-level values are restored after the Stage. Framework-owned fields such as `EXEC.ID`, `EXEC.MODE`, `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.VARS`, and `EXEC.ACTIONS` cannot be overwritten by Case or sidecar input. There is intentionally no `EXEC.TOOL`, `EXEC.DB`, `EXEC.MQ`, `EXEC.OUTPUT`, `EXEC.CALL`, `EXEC.INVOCATION`, `EXEC.STAGE`, or `EXEC.STAGES`: helper/resource state remains internal, root-level `TOOL.*` / `DB.*` remain compatibility or transient views, and Action result/evidence is consumed through local `output` while active and `EXEC.ACTIONS` after publication. Stage/Template status, timing, and history remain in the execution result/evidence model and legacy `CASE.STAGES`. The `att-load/v1.0` adapter adds the load-only `EXEC.LOAD` namespace described below.

### Load V1 Context (3.5.2)

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
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/tool.yaml --duration 100ms --run-id load-tool-example
```

`examples/load/README.md` is the maintained copyable reference for Template, Flow, Tool, DB/MQ pool sizing, thresholds, evidence, CLI overrides, and invalid configurations. All six examples are schema- and dependency-validated by `LoadAcceptanceTest`; that test also launches the real `att.FrameworkRunner load` CLI for short closed and arrival-rate scenarios and checks the persisted JSON, YAML, and offline HTML report.

### Load summary and HTML report contract

`load-summary.json` and `load-summary.yaml` share the stable `att-load-summary/v1.0` contract. Root fields are `schemaVersion`, `status` (`PASS`, `FAIL`, or `ERROR`), `exitCode`, `runId`, `startedAt`, `endedAt`, `durationMs`, `scenario`, `timing`, `metrics`, `thresholds`, `resources`, optional `evidence`, and `report: report/index.html` relative to the run directory. The JSON schema is `schemas/att-load-summary-v1.0.schema.json`, registered in the schema catalog as `att-load-summary/v1.0`.

The persisted `scenario` is a dedicated report-safe projection. It retains target type/id, workload and execution timing, threshold configuration, and evidence policy, but omits arbitrary business `inputs` and Tool `target.arguments` from JSON, YAML, and the HTML `window.ATT_LOAD_SUMMARY`. CI and offline tooling can therefore consume the summary without durable password, token, request-body, or other oversized payload values.

`timing.phases` lists configured `WARMUP`, `RAMP_UP`, `STEADY`, and `RAMP_DOWN` start/end/duration windows. `metrics.phases` contains observed scheduled/started/completed/failure/drop counts, throughput, latency, scheduler lag, and concurrency aggregates per phase. Warm-up has `measured: false`: its traffic remains visible in the run history, but measured SLA aggregates exclude it. Other phases remain measured. A phase with no events still appears in `timing.phases`, so empty and edge runs have a stable machine-readable shape.

`resources.db` and `resources.mq` contain only bounded pool diagnostics such as pool size, active/idle, waiting, and timeout/acquisition counts; they never contain connections, queue handles, credentials, or other live objects. Pool saturation and acquisition timeouts are separate from SUT failures. `evidence.items[].path` points to retained evidence below `<runId>/samples/` or `<runId>/failures/`; the HTML report renders each path as a relative link.

`report/index.html` is self-contained and can be opened offline. It shows run identity/status, closed or arrival-rate semantics, phase and warm-up separation, aggregate metrics, threshold diagnostics, resource diagnostics, retained evidence links, and bounded one-second time-series buckets. Arrival-rate reports explicitly distinguish configured arrival rate, achieved scheduling rate, completed TPS, and generator drops; drops are not SUT errors. The report links to the adjacent JSON/YAML summaries but does not embed raw per-iteration samples or secrets; `window.ATT_LOAD_SUMMARY` exposes the same bounded summary for offline tooling.

`att load --profile` keeps the existing profiling contract and writes `performance.json` beside the load summary. It records load execution/report phases, bounded load counters, and the shared schema/Template/payload/process counters, so the documented self-overhead gate is reproducible without turning ATT into a target CPU or memory benchmark.

The machine-readable `metrics` object reports configured load (`configuredUsers`, `configuredArrivalRatePerSecond`, `configuredMaxConcurrent`), iteration/scheduling counts (`iterations`, `scheduled`, `measuredScheduled`, `started`, `measuredStarted`, `completed`, `success`, `failure`, `runtimeError`, `dropped`, `measuredDropped`), concurrency (`activeVus`, `maxActiveVus`, `currentInFlight`, `maxInFlight`), measured-phase results (`warmupCompleted`, `measuredCompleted`, `sutErrorRate`, `runtimeErrorRate`, `droppedRate`, `completedThroughput`), latency percentiles (`p50Ms`, `p95Ms`, `p99Ms`), scheduler lag, and grouped `errorClassifications`. Percentiles use a bounded reservoir; `latencyMinMs`, `latencyMeanMs`, `latencyMaxMs`, and `latencyObservationCount` remain exact across all measured observations. Runtime errors are separate from SUT failures, and generator drops never increase `sutErrorRate`. The `buckets` map is sorted by one-second epoch-millisecond key; each bucket includes `model`, `phase`, configured rate/concurrency, completed TPS, p95/p99, SUT/drop rates, active/in-flight counts, scheduler lag, and error classifications. Latency storage is capped at 4096 global samples and 256 samples per bucket; time-series storage is capped at 4096 buckets and evicts the oldest bucket, so memory does not grow linearly with run duration or raw latency values.

Load thresholds use the common `errorRate` (`%`), `p95`/`p99` (`ms`), and `minThroughput` (`/s` or `/m`) fields for both workload models. Arrival-rate scenarios additionally support `droppedRate` (`%`) and `achievedArrivalRate` (`%`, `/s`, or `/m`). For the percentage form, achieved arrival rate is measured `measuredStarted / measuredScheduled`; warm-up is excluded, while ramp-up, steady, and ramp-down remain part of the integrated measured schedule. The rate forms compare the actual average started rate over the full phase window; `/m` thresholds are normalized to per-second before comparison. Each threshold is reported independently with expected expression, formatted actual value, PASS/FAIL status, and failure diagnostic. The load result then uses exit code `0` for PASS, `1` for a completed run with failed SLA thresholds, `2` for validation/configuration failure, and `3` for load runtime/infrastructure error. `target.arguments` is valid only for Tool targets; Template and Flow targets reject it with a field-specific diagnostic.

The release gate is deliberately reproducible rather than a SUT microbenchmark:

```sh
mvn -q -Dtest=LoadAcceptanceTest,LoadCrossModeTest,ClosedVuSchedulerTest,FixedArrivalRateSchedulerTest,LoadRuntimeTest,LoadScenarioTest,LoadReportTest,LoadDbPoolingTest,LoadMqPoolingTest,PooledMqHelperExecutorTest,PooledMqTransportFactoryTest test
```

It checks the CLI-to-report path for both schedulers, including deterministic arrival-rate cap/drop and configured-versus-achieved-versus-completed metrics; Context deep-copy and iteration isolation; lazy success/failure workspaces; bounded evidence and metric reservoirs; scheduler lag accounting; process/file artifact behavior; DB/MQ reuse, timeout, pool diagnostics, and cleanup; threshold PASS/FAIL; summary schema; report rendering; and compatibility of the existing run/debug/validation test suite. Load V1 does not claim distributed execution, Poisson/random pacing, weighted multi-scenario, rendezvous, adaptive pools, MQ handle pooling, XA/affinity, or target CPU/memory benchmarking.

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

## 08 Reliability and Execution Control

This chapter owns cross-cutting public execution behavior.

### Assertion and status

An assertion evaluates a boolean condition after the Action's primary work at the documented assertion point. A false assertion is `FAIL`; an exception/infrastructure problem is `ERROR`; invalid authoring/configuration is `INVALID`; a non-selected condition is `SKIPPED`; successful work is `PASS`. Operation failure and assertion failure are therefore distinct.

### `runWhen` and `onFailure`

`runWhen` controls whether a statically known Action/Stage is eligible to execute. `onFailure: stop|continue` controls continuation after failure; `continue` never changes the failed status into PASS. Cleanup/diagnostic work should use the documented conditional execution semantics rather than hiding failures.

### Timeout

Timeout terminates or abandons the operation according to the supported backend and records diagnostic/evidence. Timeout is an operational failure; it is not an assertion false result. Tool timeout behavior and resource-specific DB/MQ limits are documented in their resource contracts.

### Retry and attempts

Where retry is supported, one logical Action owns multiple attempts. Retry policy determines which operation failures are retryable. The final/winning operation becomes top-level `output.result` / `output.evidence`; every attempt remains available under `output.attempts[n]`. A later success does not erase earlier attempt evidence.

### Evidence collectors

Tool evidence collectors run after the primary operation and before that attempt's assertion. Collectors have independent timeout and `onFailure: continue|stop`. Collector output belongs to the attempt's evidence and never replaces the primary operation result.

### Transaction/resource lifecycle

DB transaction finalization and DB/MQ resource cleanup occur at the appropriate execution lifecycle boundary. These mechanisms can affect operation success/diagnostics but are internal resource state, not public Context namespaces.

### Aggregation

When multiple child outcomes contribute to a parent, severity is preserved:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

Future fixture behavior (#38) and DB Action-level timeout/retry (#39) extend this chapter's existing concepts rather than creating a new reliability model.

## 09 Configuration Reference

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

### Multi-environment profiles in V3.5.2

ATT V3.5.2 selects an environment through one common `att-config/v2.6` file. It does not select an environment by changing an Action or by adding an environment-specific Tool ID. Actions keep stable logical IDs across SIT, UAT, PREPROD, and production-like environments:

```text
Actions -> logical helper ID -> selected config -> physical descriptor -> endpoint
```

The supported package layout is:

```text
config/
├── config.yaml
├── dbhelpers/{sit,uat}/orders.yaml
└── mqhelpers/{sit,uat}/payment.yaml
```

The common config keeps the existing templates, testcase roots, run/execution/report settings, `toolGroups`, and global `tools` registry. The profile layer is deliberately limited to typed DB/MQ descriptor lists:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.6
environment: SIT                 # default profile; --env overrides it
templates: {root: templates}
testcase: {root: testcase}
toolGroups:
  - config/tools/sample.yaml
  - config/tools/fpp.yaml
  - config/tools/orders-db.yaml
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

Use the executable complete configs in `config/environments/sit.yaml` and `config/environments/uat.yaml` as the migration source for the common registry, including `invokePaymentApi` and the `sample.getAcDate` tool used by `examples/load/closed-smoke.yaml`. Do not replace that shared registry with `tools: {}` or `toolGroups: []` in a real package.

The SIT and UAT DBHelper descriptors both use `id: orders`, while their JDBC URL and other physical connection details differ. The MQHelper descriptors both use `id: payment`, while host, queue manager, port, and channel differ. A complete descriptor pair, including pool settings and safe evidence policy, is in [`examples/environments/README.md`](../examples/environments/README.md).

The Action definitions remain identical:

```yaml
actions:
  renderRequest:
    type: render
    payload: payment/request.json
    renderAs: file

  queryOrder:
    type: db
    db: orders
    query:
      sql: "select * from orders where order_id = ?"
      params:
        - "${EXEC.INPUT.orderId}"

  paymentRequest:
    type: tool
    call: >-
      #{mq.payment.request(
        requestQueue='PAYMENT.REQUEST',
        replyQueue='PAYMENT.REPLY',
        file=${EXEC.ACTIONS.renderRequest.output.targetFiles[0]},
        waitMs=5000
      )}
```

`environment` is the default profile name. A case-insensitive `--env` selector overrides it. Each profile may replace `dbhelpers` and/or `mqhelpers` as a whole list; omitted lists inherit the common list. No generic recursive YAML merge is performed, and profile fields other than `dbhelpers` and `mqhelpers` are rejected. Unknown profile names fail before validation or external execution. Use the same package with every supported execution mode:

```sh
# SIT
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env SIT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT

# UAT
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env UAT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env UAT
```

For CI, run the same validation and execution stages once per selected environment:

```sh
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env SIT --all
./att.sh validate --config config/config.yaml --env UAT --package
./att.sh run --config config/config.yaml --env UAT --all
```

This design keeps Testcases, Templates, Flows, and Actions reusable and makes validation deterministic because the selected config defines the complete resource registry before execution. Logical IDs such as `orders` and `payment` represent capabilities, not physical endpoints; infrastructure topology belongs in configuration. Do not introduce `orders_sit`, `orders_uat`, or environment conditionals solely to choose endpoints. Separate top-level configs are appropriate when testcase/template roots, report policy, or package structure intentionally differ.

Keep non-secret topology in YAML: JDBC URL, MQ host/port, queue manager, channel, pool sizes, and timeouts. Keep DB/MQ usernames and passwords in `${ENV:NAME}` references backed by the local environment or CI secret store. DBHelper resolves complete `${ENV:NAME}` values for the URL, username, password, and string-valued connection properties. MQHelper resolves `${ENV:NAME}` only for username/password; host, queue manager, channel, and numeric port are normally literal values in the selected descriptor. Resolved secrets remain absent from profile metadata, diagnostics, reports, and generated documentation.

Use profiles when the same test package is promoted across environments and only infrastructure bindings change. Use separate top-level configs when testcase/template roots, report policy, or package structure intentionally differ. Migration from the 3.5.0 complete-config pattern keeps every descriptor and Action unchanged: move the common settings into `config/config.yaml`, place each descriptor list under `environments.<NAME>`, and replace `--config config/environments/<env>.yaml` with `--config config/config.yaml --env <NAME>`.

### Schema catalog

V3.4 adds post-invocation Tool evidence and the independent MQ helper schema. V2.6.2 adds `att-template/v2.6` and `att-sidecar/v2.2` for the unified Tool Action policy. The dbhelper schema remains V2.5.

| Artifact | Schema identifier | Formal definition |
|---|---|---|
| Debug input | `att-debug/v1.0` | [att-debug-v1.0.schema.json](../schemas/att-debug-v1.0.schema.json) |
| Global configuration | `att-config/v2.6` | [att-config-v2.6.schema.json](../schemas/att-config-v2.6.schema.json) |
| Legacy global configuration (read compatibility) | `att-config/v2.1`, `att-config/v2.2`, `att-config/v2.5` | [att-config-v2.5.schema.json](../schemas/att-config-v2.5.schema.json) |
| Dbhelper instance | `att-dbhelper/v2.5` | [att-dbhelper-v2.5.schema.json](../schemas/att-dbhelper-v2.5.schema.json) |
| MQ helper descriptor | `att-mqhelper/v1.0`, `att-mqhelper/v1.1` | [att-mqhelper-v1.0.schema.json](../schemas/att-mqhelper-v1.0.schema.json), [att-mqhelper-v1.1.schema.json](../schemas/att-mqhelper-v1.1.schema.json) |
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
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

| Path | Required/default | Constraints |
|---|---|---|
| `schemaVersion` | required | `att-config/v2.6`; V2.1/V2.2/V2.5 remain readable, but only V2.6 Tool descriptors accept `call`/`cache` |
| `outputDirectory` | `output` | Non-empty package-relative output root |
| `environment` | `SIT` | Non-empty default profile name when `environments` is present; otherwise exposed metadata only |
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
| `mqhelpers` | `[]` | Unique package-contained `att-mqhelper/v1.0` or `att-mqhelper/v1.1` YAML paths; normalized duplicates are rejected |
| `environments` | absent | Non-empty map of profile names; each profile may contain only `dbhelpers` and/or `mqhelpers` typed lists |
| `ssh` | absent | Optional SSH target for inline global tools |
| `tools` | `{}` | Map of reusable tool contracts |

Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `execution`, `report`, `xml`, `toolGroups`, `dbhelpers`, `mqhelpers`, `ssh`, `tools`, `environments`, `x-*` |
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

The root `id` must match `^[A-Za-z_][A-Za-z0-9_-]*$` and be package-unique ignoring case. `connection.isolation` is `driverDefault`, `readUncommitted`, `readCommitted`, `repeatableRead`, or `serializable`. Driver `properties` is a string-to-string map. Complete `${ENV:NAME}` values resolve while loading configuration; missing variables are errors. See [Database helpers](#52-dbhelper) for Action, expression, result, security, and lifecycle behaviour.

### MQ helper configuration

Each path in global `mqhelpers` resolves from the package root and contains one `att-mqhelper/v1.0` or `att-mqhelper/v1.1` object. v1.0 is a flat single-instance descriptor. v1.1 has `defaults`, a non-empty `instances[]` list, optional `selection.strategy` (`random` or `roundRobin` for multiple instances), and group-level `evidence`; each physical instance receives effective `connection`, `message`, `requestReply`, and `pool` values before execution. The detailed v1.1 model and invocation examples are maintained in the MQHelper resource module.

| Object | Required/default | Allowed properties and constraints |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, `connection`; optional `message`, `requestReply`, `evidence`, `pool`, `x-*` |
| `connection` | required | `queueManager`, `host`, `port`, and `channel` required; optional `username`, `password`; port 1–65535 |
| `message` | defaults | `ccsid` defaults to 1208; `format` is `MQSTR`, `MQHRF2`, `MQFMT_STRING`, `MQFMT_NONE`, or `NONE`; `persistence` is `asQueue`, `persistent`, `notPersistent`, or `nonPersistent` |
| `requestReply` | defaults | `waitMs` defaults to 10000 and is 0–3600000 milliseconds |
| `evidence` | defaults | `payload: none|metadata`; `none` omits payload evidence and `metadata` records only the policy marker; full payload bytes are never placed in structured evidence |
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
| `saveAs` | optional `path`; optional `format` and `overwrite`; target-specific format/default rules are validated whenever `saveAs` is supplied; `overwrite` defaults false |
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

`path` is optional and `overwrite` defaults to `false`. Omitting `path`, including in `saveAs: {format: ...}`, keeps the typed result in memory and creates no artifact. A supplied `saveAs` is still validated against the target-specific format defaults and restrictions even when `path` is absent. When `path` is present, it is written using those target-specific rules:

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

`path` is optional for DB as well, but DB still requires `format: text|json|yaml|xml` whenever `saveAs` is supplied, even when no path is present. A valid pathless DB `saveAs` creates no artifact; when a DB artifact path is present, the same format and path rules apply. The written representation never replaces `${output.result}`'s typed Java object.

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
  "attVersion": "3.5.2",
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

## 10 CLI Reference

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

The selected input is validated before execution. Missing files, invalid schema, unknown or missing Tool arguments, and other input/configuration errors return exit code `2`. Framework-owned values such as `EXEC.ID`, `EXEC.MODE`, `EXEC.OUTPUT_DIR`, `EXEC.VARS`, and `EXEC.ACTIONS`, together with the corresponding `CASE.*`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, and `DB.*` aliases, remain authoritative even if they appear in the input `case` map. `EXEC.STAGES` is not a canonical Context node; Stage history remains in the legacy `CASE.STAGES` evidence view.

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

### Complete option matrix (3.5.2)

`--config <file>` selects the base configuration. `--env <name>` selects one environment profile from an `att-config/v2.6` configuration and is valid for `run`, `validate`, `debug`, and `load`. `--help` prints help. `--case-id` is a compatibility synonym for `--case`. `--parallel` is the deprecated compatibility spelling for `--allow-parallel-runs`; prefer the latter. `--queue` and `--allow-parallel-runs` control process-level output-root concurrency, not Case workers. `--profile` writes performance diagnostics for `run` or `load`.

Load uses the scenario as the base and explicit workload options override the corresponding fields before the effective scenario is validated again:

```sh
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --warmup 1s --ramp-up 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

The complete workload override set is `--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, and `--overload-policy`. `--think-time` is closed-VU only. Common selection/output options remain command-specific: `--suite`, `--suite-dir`, `--case`/`--case-id`, `--tag`, `--exclude-tag`, `--all`, `--run-id`, `--output-dir`, `--format`, `--quiet`, `--verbose`, `--ci-output`, `--dry-run`, `--fail-fast`, `--rerun-failed`, `--update-snapshot`, `--package`, `--selected`, `--input`, `--queue`, `--parallel`, `--allow-parallel-runs`, `--profile`, `--config`, `--env`, and `--help` are accepted only where the command contract permits them.

## 11 Results, Reports, and Evidence

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

## 12 Validation and Diagnostics

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

## 13 CI, Packaging, and Operations

ATT supports source-tree development and offline release packages.

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` runs the release gate, regenerates the modular Reference Manual, builds the application jar and release/source archives, and verifies the packaged launcher. It requires Java/Maven plus Python 3 and Pandoc for Reference generation.

### Runtime dependencies

Java 8+ is the runtime baseline. ATT does not bundle JDBC drivers; place required driver/dependency jars in `lib/`. IBM MQ is optional: the default build remains usable without MQ client classes, while MQ deployments package the supported IBM client jar/profile.

### Documentation operations

`./att.sh docs` generates package documentation at `build/docs/index.html` from the validated ATT package model. The normative product Reference is independently generated from `docs/reference*` by `tools/build_reference_manual.py`. `./att.sh clean` removes documented generated runtime/build outputs but preserves source inputs.

### CI and environment promotion

CI should validate the package before executing external integration tests, keep Run/Debug/Load evidence as job artifacts as appropriate, and select environments through explicit `--config`/`--env` policy. Stable logical DB/MQ IDs let the same Templates move across SIT/UAT/PREPROD without Action edits.

Parallel jobs should use unique Run IDs and, when independent retention/latest-run state is required, separate output roots. Destructive operations such as clean/report/archive over one shared output root must be serialized.

Maintainer implementation sequencing, scheduler internals and resource-owner details live in `docs/system-design/`, not in this end-user Reference.

## 14 Appendices

The appendices collect stable lookup material that should not drive the main product narrative: schema/version matrix, compatibility/deprecations, migration notes, and limits/defaults.

### 14.1 Schema and Version Matrix

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.6` |
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.0`, `att-mqhelper/v1.1` |
| Tool group | `att-tool-group/v2.6` |
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.0` (older supported forms remain readable where compatible) |
| Flow | `att-flow/v3.0` |
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.0` |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` is the authoritative repository catalog. Compatibility is a reader contract; new authoring should use the current schema for the feature being authored.

### 14.2 Compatibility and Deprecated Aliases

Compatibility exists to read established packages without creating a second current model. New authoring uses canonical `EXEC`, `META`, Action-local `output`, current schema versions, `--env`, and current Tool/DB/MQ contracts.

Deterministic legacy aliases may remain readable with migration warnings. Aliases are not created where old semantics conflict with scope isolation or the common result/evidence contract. Deprecated CLI/authoring forms remain documented in their owning chapter or CHANGELOG only when users still need a migration path.

### 14.3 Migration Notes

The current Reference describes ATT by product concept rather than release chronology. Release-by-release changes remain in `CHANGELOG.md` and `docs/history/`.

Key current migrations are:

- prefer `EXEC` / `META` over legacy Context aliases;
- use `output.result` / `EXEC.ACTIONS.<id>.output.result` and the common evidence/attempt contract;
- treat Tool, DBHelper and MQHelper as peer resources;
- use environment profiles when only typed DB/MQ bindings vary;
- treat Run, Debug and Load as peer execution modes.

The auditable disposition of the pre-#42 monolithic manual is recorded in `docs/reference-migration-map.md`.

### 14.4 Limits and Defaults

Use the owning schema/configuration chapter for normative field defaults. Important architectural limits include:

- load V1 chooses exactly one workload model;
- arrival-rate overload policy is `drop`;
- Flow invocation receives a fresh Action scope and caller scope is restored on return;
- `EXEC.LOAD` exists only for Load iterations;
- resource lifecycle state is not a public Context tree;
- unknown schema fields are rejected except documented extension locations such as root `x-*` where supported.

Operational numeric limits such as timeout ranges, evidence sample bounds, result limits and pool sizes remain defined by their schemas/descriptors so this appendix does not become a second source of truth.
