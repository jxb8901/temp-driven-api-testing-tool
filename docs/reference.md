# ATT V3.6.1 Reference Manual

Author: Jeffrey + ChatGPT
Version: 3.6.1
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

A **Testcase** is one normalized workbook row. A **Stage** selects a Template and contributes stage-private data. A **Template** is the executable scenario boundary. A **Flow** is reusable Template logic with an isolated Action scope. An **Action** is one ordered unit of work. A **Resource** is a configured Tool, DBHelper, MQHelper, HTTPHelper or SSHHelper used by Actions or permitted expression calls.

### Execution modes are peers

Run, Debug and Load adapt different inputs into the same execution-neutral Context and reusable components:

| Mode | Primary input | Reuses |
|---|---|---|
| Run | workbook Testcases and Stage selectors | Templates, Flows, Tools, DB/MQ |
| Debug | `att-debug/v1.0` sidecar or `--input` | one Template, Flow or Tool target |
| Load | `att-load/v1.2` scenario | one Template, Flow or Tool target repeatedly |

Reusable Templates/Flows depend on `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, `META`, and Action-local `output`. Execution mode and scheduler identity are framework diagnostics in retained evidence, not expression data.

### Resources are peers

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are independent resource types. They differ in configuration and lifecycle, while Actions publish native typed results through `output.result` and keep optional presentation evidence separate. Public expressions should consume Action results/evidence rather than resource-internal connection/process state.

```text
Tool ------\
DBHelper ---+
MQHelper ---+--> typed operation result --> Action output
HTTPHelper -+
SSHHelper --/
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

A directory is a callable Template only when it directly contains template.yaml. ATT 3.6.0 uses att-template/v3.3. Each Template has a non-empty ordered actions map and a required description.

Each Action has a type-specific contract. Render returns DocumentValue without writing a file. Tool/DB/HTTP/MQ actions publish the native typed operation result. Log formats typed values for human observation. Assign publishes values to EXEC.VARS, and Flow runs in a nested Action scope.

See [Actions and Typed Values](reference/14_actions.md) for the complete field list, examples, typed result/evidence model, DocumentValue behavior, HTTP/MQ boundaries and migration guidance. [Expressions and Built-ins](reference/07_expressions.md) covers the shared expression engine and Load ID initialization scope.

## 03 Runtime and Context Model

Run, Debug and Load share one canonical EXEC/META expression model. EXEC changes through framework lifecycle and explicit input/variable/action publication. META is curated, immutable and secret-safe.

### Identity roots

| Path | Meaning and type | Availability |
|---|---|---|
| EXEC.ID | Current Case, Debug execution or Load iteration ID; String. | After execution initialization; generated before Load business actions. |
| EXEC.RUN_ID | Enclosing ATT Run ID; String. | All modes; shared by Cases/iterations in a run. |
| EXEC.STARTED_AT | Current execution start time; ISO-8601 String. | All modes. |
| EXEC.RUN_STARTED_AT | Enclosing run start time; ISO-8601 String. | All modes. |
| EXEC.OUTPUT_DIR | Absolute execution workspace path. | After initialization; in Load it may be planned and not yet exist. |
| EXEC.INPUT | Case/stage, Debug sidecar or Load workload input map. | All modes. Stage inputs temporarily overlay Case inputs. |
| EXEC.VARS | Typed values published by assign. | All modes; empty before assignment. |
| EXEC.ACTIONS | Completed Actions in the active Template/Flow scope. | After an Action is published; Flow has a nested scope. |
| EXEC.LOAD.MODEL | closed or arrivalRate. | Load only; available before ID generation. |
| EXEC.LOAD.WORKLOAD_ID | Configured workload ID. | Load only; available before ID generation. |
| EXEC.LOAD.USER_ID | Stable virtual-user ID. | Closed Load only; absent for arrival-rate. |
| EXEC.LOAD.ITERATION | Numeric iteration sequence. | Load only; available before ID generation. |
| EXEC.LOAD.PHASE | WARMUP, RAMP_UP, STEADY or RAMP_DOWN. | Load only; available before ID generation. |

EXEC.LOAD exposes stable identity. Scheduler counters, queue state and timing diagnostics stay in evidence-only DIAG.load, which is not an expression root.

### META field inventory and lifecycle

The public META root contains only `PROJECT`, `SOURCE`, `TARGET`, `TEMPLATE`, `FLOW`, `TOOL`, `DBHELPER`, `MQHELPER`, and `HTTPHELPER` as listed below. META contains descriptive fields only. A path may be absent when its component is not active.

| Public path | Meaning, type and example | Modes and availability | Scope and when absent |
|---|---|---|---|
| META.PROJECT.id | Project directory name; String, e.g. `payment-att`. | Run, Debug, Load; after project binding. | Execution-wide. |
| META.PROJECT.root | Normalized project-root path; String, e.g. `/srv/att/payment`. | Run, Debug, Load; after project binding. | Execution-wide. |
| META.SOURCE.type | Source kind; String: `testcase`, `debug` or `load`. | Run, Debug, Load. | Execution-wide. |
| META.SOURCE.path | Normalized absolute source path; String, e.g. `/srv/att/payment/testcase/payment.xlsx`, `/srv/att/payment/debug.yaml` or `/srv/att/payment/load/payment.yaml`. | Run, Debug, Load when a source file exists. | Execution-wide; absent for an in-memory source. |
| META.SOURCE.caseId | Canonical TestCase or synthetic Debug Case ID; String, e.g. `payment.default.P001`. | Run, Debug. | Execution-wide; absent in Load. |
| META.SOURCE.workbookId | Workbook identifier; String, e.g. `payment`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.groupId | Workbook group identifier; String, e.g. `default`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.rowCaseId | Case ID from the workbook row; String, e.g. `P001`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.sheet | Workbook sheet name; String, e.g. `Payment`. | Run when supplied by the workbook adapter. | Execution-wide; absent for Debug/Load or when unavailable. |
| META.SOURCE.row | One-based workbook row number; Number, e.g. `12`. | Run when supplied by the workbook adapter. | Execution-wide; absent for Debug/Load or when unavailable. |
| META.SOURCE.workbook | Optional workbook label; String when present, e.g. `payment_regression.xlsx`. | Run when supplied by the adapter. | Execution-wide; optional and otherwise absent. |
| META.SOURCE.scenario | Load scenario basename without its suffix; String, e.g. `payment-smoke`. | Load. | Execution-wide; absent outside Load. |
| META.TARGET.type | Resolved target kind; String: `testcase`, `template`, `flow` or `tool`. | Run, Debug, Load; after target selection. | Execution-wide. |
| META.TARGET.id | Resolved target identifier; String, e.g. `PAYMENT_INVOKE` or `payment.flow.v1`. | Run, Debug, Load; after target selection. | Execution-wide. |
| META.TEMPLATE.id | Active Template or resolved Load execution-wrapper ID; String, e.g. `PAYMENT_INVOKE`. | Run/Debug while a Stage runs; Load after target resolution and while its wrapper runs. | Component scope; absent before target/template resolution, restored or removed after the scope. In Load Flow/Tool targets this is the resolved synthetic wrapper. |
| META.TEMPLATE.path | Normalized Template or execution-wrapper directory; String, e.g. `/srv/att/templates/PAYMENT_INVOKE`. | Same availability as META.TEMPLATE.id. | Component scope; absent before resolution, restored or removed after the scope. |
| META.FLOW.id | Active Flow ID; String, e.g. `payment.request.v1`. | Run, Debug, Load while that Flow invocation runs. | Invocation scope; push on entry, restore on return, absent when inactive. |
| META.FLOW.invocationId | Caller Action ID; String, e.g. `sendRequest`. | Same availability as META.FLOW.id. | Invocation scope; absent when no Flow is active. |
| META.FLOW.depth | One-based nested Flow depth; Number, e.g. `1`. | Same availability as META.FLOW.id. | Invocation scope; absent when no Flow is active. |
| META.TOOL.id | Active configured Tool or built-in name; String, e.g. `payment.queryOrder` or `upper`. | Run, Debug, Load during the invocation. | Invocation scope; never retained as the “last Tool” after return. |
| META.TOOL.type | Invocation kind; String, e.g. `tool` or `builtin`. | Same availability as META.TOOL.id. | Invocation scope; absent after return unless an outer invocation remains. |
| META.DBHELPER.id | Logical DBHelper ID; String, e.g. `orders`. | Run, Debug, Load during a DBHelper operation/DB Action. | Invocation scope; absent after return unless an outer scope remains. |
| META.DBHELPER.type | Resource kind; String, `dbhelper`. | Same availability as META.DBHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |
| META.MQHELPER.id | Logical MQHelper ID; String, e.g. `payment`. | Run, Debug, Load during an MQHelper operation. | Invocation scope; push/restore; absent after return unless an outer scope remains. |
| META.MQHELPER.type | Resource kind; String, `mqhelper`. | Same availability as META.MQHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |
| META.HTTPHELPER.id | Logical HTTPHelper ID; String, e.g. `payment`. | Run, Debug, Load during an HTTPHelper operation. | Invocation scope; push/restore; absent after return unless an outer scope remains. |
| META.HTTPHELPER.type | Resource kind; String, `httphelper`. | Same availability as META.HTTPHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |

META.SSHHELPER is not public. SSH connection and credential settings stay private to Tool invocation. META.TOOL may identify the active Tool, but SSH endpoint, user, identity file and credentials are not META fields.

ATT recursively filters credential-bearing keys such as password, secret, token, authorization/cookie, API key and private key. Expressions and adapters can read META but cannot mutate it.

### Invocation and scope rules

Entering a Template, Flow, Tool or helper invocation publishes metadata for that active scope. Nested calls push a frame; exit restores previous metadata. When no invocation is active, its branch is absent. Consumers must not depend on “last invoked” state.

EXEC.INPUT is the canonical input map. A Stage temporarily overlays Case inputs and restores them after completion. EXEC.VARS is shared across later Stages in a Case. EXEC.ACTIONS is scoped to the active Template or Flow. An Action reads local output while running and publishes its envelope at EXEC.ACTIONS.<id>.output.

### Action output and evidence paths

| Path | Meaning and availability |
|---|---|
| `output.result` | Primary typed Action result while the Action is active, including its assertion. |
| `output.evidence.collectors.<id>.result` | Typed result of an active Tool evidence collector. |
| `output.evidence.collectors.<id>.status` | Collector `PASS`/`ERROR` status while the Action is active. |
| `output.evidence.collectors.<id>.error` | Bounded failure summary with a non-blank `message` when the collector fails. |
| `output.evidence.collectors.<id>.evidence` | Preserved bounded/redacted underlying Tool/resource evidence, including resource identity and native failure fields when supplied. |
| `EXEC.ACTIONS.<actionId>.output.result` | Published primary typed result after the Action completes. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result` | Published final/winning collector result. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.status` | Published final/winning collector status. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.error/evidence` | Published collector failure summary and preserved operation evidence. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.result/status` | Collector result/status for a specific retry attempt; earlier attempts remain after a later success. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.error/evidence` | Failure summary and underlying evidence for that specific collector attempt. |

Strings, numbers, booleans, null, maps, lists and DocumentValue remain typed across Action/Template/Flow boundaries.

### Load execution ID initialization

Load uses schema att-load/v1.2. If execution.execIdFormat is present, ATT evaluates it once per started iteration with the normal ${...} / #{...} engine during initialization; otherwise the default run-scoped ID remains in effect.

Available values include EXEC.RUN_ID, timestamps, EXEC.INPUT, EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE, closed-only EXEC.LOAD.USER_ID and the already curated META.PROJECT/SOURCE/TARGET/TEMPLATE. EXEC.ID and EXEC.OUTPUT_DIR are unavailable because the generated ID determines the workspace. No Action has run, so EXEC.ACTIONS and invocation-scoped Flow/Tool/helper META are absent.

Only deterministic, side-effect-free built-ins are allowed. External Tool/DB/MQ/HTTP/SSH calls and stateful, random, clock or filesystem functions are rejected. seq.next() is neither allowed nor required. Use stable identity components:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate has no USER_ID:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

IDs must be non-empty, path-safe single segments and unique within the Load run. Duplicate or unsafe values fail before the target starts; ATT does not append a hidden suffix.

### Run, execution and evidence navigation

| Identity | Meaning | Scope | Artifact role |
|---|---|---|---|
| EXEC.RUN_ID | Enclosing ATT run. | Run. | Run root, summary and report. |
| EXEC.ID | Current Case/Debug/Load execution. | Execution. | Key for logs/evidence when a workspace exists. |
| EXEC.OUTPUT_DIR | Workspace path associated with EXEC.ID. | Execution. | Physical Run/Debug workspace or planned lazy Load workspace. |

Normal Run stores functional Cases under output/<RUN_ID>/executions/<EXEC.ID>/. In Load, EXEC.OUTPUT_DIR and CASE.outputDirectory remain at output/load/<RUN_ID>/executions/<EXEC.ID>/ throughout the iteration. When retained, a copy of its artifacts is also stored under samples/<EXEC.ID>/ or failures/<EXEC.ID>/. Metrics-only iterations have EXEC.ID but no per-iteration directory after the scheduler releases their temporary workspace. Retained Load rows show EXEC.ID and link to case.log when present. Debug uses its debug ID as both EXEC.RUN_ID and EXEC.ID.

DIAG is evidence-only. Do not reference DIAG, EXEC.MODE or arbitrary scheduler counters in expressions; pass business variation through EXEC.INPUT.

### Optional lookup and compatibility

${path} is strict. ${path?} returns null for an allowed missing map/list path; it does not make malformed syntax or illegal scope access valid. Legacy CASE, RUN and ACTIONS aliases remain only where they map one-to-one to canonical data. New Templates should use EXEC and META.

## 04 Execution Modes

Run, Debug and Load are peer adapters over the same reusable Template/Flow/Tool/DB/MQ execution semantics.

| Mode | `EXEC.ID` | Unit of execution | Primary result location |
|---|---|---|---|
| Run | canonical Case ID | selected Testcase | `output/<RunID>/` |
| Debug | debug ID | one target invocation | `output/debug/<debugId>/` |
| Load | unique iteration execution ID | repeated target iterations | `output/load/<runId>/` |

`EXEC.RUN_ID` identifies the enclosing ATT run. Mode and scheduler-specific information are retained under evidence-only `DIAG`; neither `EXEC.MODE`, `EXEC.LOAD`, nor `DIAG` is available to expressions.

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

### Debug troubleshooting and MQ payload paths

When a debug target cannot be resolved, check the target kind and identifier first, then use `--input <path>` to remove sidecar discovery from the diagnosis. Template and Flow debug discover `<target directory>/debug.yaml`; grouped Tool debug discovers `config/tools/<group>.debug.yaml`. The selected target's dependency closure is validated, so an unrelated workbook or Case file is not a prerequisite.

MQ `file` arguments follow the same safe path rules in Debug, Run and Load:

- An absolute path must resolve to a regular file inside the ATT package root. It is validated against the package root even when Load has not materialized a lazy iteration workspace yet.
- A relative path is resolved under the active Case output directory. `..` traversal, symlink payloads, symlink escapes, directories and non-regular files are rejected before MQ connect/open/put/get.
- A missing or unsafe payload reports the payload path directly. No MQ connection is attempted, so a path error should be fixed before investigating broker credentials or queue state.

Use the output directory to separate diagnosis stages:

| Symptom | Check |
|---|---|
| `Debug input file does not exist` | Add the sidecar beside the selected target or pass `--input` explicitly. |
| `target` or dependency validation fails | Confirm the target type/id and inspect the reported dependency field; unrelated workbook files are not required. |
| MQ reports a missing/unsafe payload | Verify the absolute package path or the relative Case-output path; remove traversal and symlinks. |
| The action runs but output is unexpected | Read `case.log`, `result.yaml` and the action artifacts under `output/debug/<debugId>/`; compare rendered inputs with the selected environment. |

Load-specific evidence retention (`metrics`, `failures`, `samples`, `all`) does not apply to a standalone Debug invocation. Debug always keeps its invocation result and artifacts under its own debug directory; see the Load evidence retention section in Chapter 4 when the same target is exercised by a load run.

### 4.3 Load Mode

ATT 3.6.0 accepts att-load/v1.2 scenarios. A scenario has one or more workloads; each workload owns a fixed Template, Flow or Tool target and its pacing policy. ATT validates the scenario and all targets before a scheduler starts.

#### Scenario shape

~~~yaml
schemaVersion: att-load/v1.2
workloads:
  - id: payment
    target: {type: template, id: PAYMENT_INVOKE}
    inputs: {region: HK}
    load:
      users: 20
      warmup: 10s
      rampUp: 5s
      duration: 1m
      rampDown: 5s
    execution:
      thinkTime: 500ms
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"
thresholds:
  minThroughput: ">= 10/s"
evidence:
  mode: failures
  resources:
    output: none
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

A target accepts template, flow or tool; Tool targets may provide named arguments. Workload inputs become EXEC.INPUT for each iteration. Workloads must share one model (closed users or arrivalRate) and one warmup/rampUp/duration/rampDown envelope. They are independently paced fixed targets, not a transaction mix.

#### Workload models

Closed workloads use positive load.users. Each stable virtual user repeatedly executes its target and observes execution.thinkTime before starting the next iteration. thinkTime may be a duration or a {min, max} range.

Arrival-rate workloads use load.arrivalRate, positive load.maxConcurrent and overloadPolicy: drop. They schedule against absolute due times. Arrivals beyond maxConcurrent are recorded as generator drops; they are not queued or counted as SUT errors. Arrival-rate workloads have no persistent USER_ID and cannot configure thinkTime.

duration is required. warmup, rampUp and rampDown default to zero. Warm-up sends real traffic but is excluded from measured threshold aggregates. Optional seed makes closed-VU think-time randomization deterministic.

#### Load identity and output layout

Each started iteration has a unique EXEC.ID across the Load run and shares EXEC.RUN_ID. If execution.execIdFormat is omitted, ATT uses its default run-scoped ID. Otherwise, ATT evaluates it once during initialization with the ordinary ${...} / #{...} engine. Closed workloads can use EXEC.LOAD.USER_ID; arrival-rate cannot. See [Runtime and Context Model](reference/03_runtime_context.md) for field availability and function restrictions.

Generated IDs must be non-empty, path-safe segments. Duplicate IDs fail before the target starts; ATT does not silently append a suffix.

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── executions/<EXEC.ID>/
│   ├── case.log
│   └── action outputs written under EXEC.OUTPUT_DIR
├── failures/<EXEC.ID>/case.log
├── failures/<EXEC.ID>/case.yaml
├── samples/<EXEC.ID>/case.log
└── samples/<EXEC.ID>/case.yaml
~~~

A metrics-only iteration still has EXEC.ID but does not create a per-iteration execution directory unless an operation writes an artifact or a retention decision materializes evidence. EXEC.OUTPUT_DIR remains the logical planned path at executions/<EXEC.ID> while the iteration runs. Retained failures and sampled successes receive an evidence copy under failures/<EXEC.ID>/ or samples/<EXEC.ID>/. The report and evidence summary show EXEC.ID and link to case.log when it exists. Helper resource-output formatting is deferred until retention; explicit Tool evidence collectors still execute because they are author-requested diagnostic operations.

#### Evidence and resource output

evidence.mode accepts metrics, failures, samples or all; the default is failures. sampleRate and maxSamples bound retained evidence. Dropped arrivals do not create iteration evidence.

evidence.resources.output accepts inherit (default) or none. none disables optional human-readable resource-output formatting and materialization while preserving typed results, stdoutFormat/responseFormat parsing, Render DocumentValue and requestFormat behavior. In Load, resource output is deferred until the iteration is retained. Metrics-only iterations do no business-output formatting or evidence file I/O.

#### Reports, metrics and thresholds

ATT writes bounded load-summary.json/yaml and a self-contained report/index.html below the run root. The report shows EXEC.ID for retained executions, workload/target identity, status, timing and case.log links when available. Aggregate latency percentiles use the aggregate latency collector; ATT does not average workload percentiles.

Top-level thresholds apply to the aggregate run; workload thresholds apply to one workload. Threshold failure returns FAIL/exit 1. Invalid config/target returns exit 2; runtime/infrastructure errors return ERROR/exit 3. Generator drops are not SUT errors.

#### CLI and examples

For one workload, options such as --users, --arrival-rate, --warmup, --ramp-up, --duration, --ramp-down, --think-time and --max-concurrent can override matching YAML values. Unscoped load-model overrides fail for multi-workload scenarios.

~~~sh
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh load examples/load/multi-closed.yaml
./att.sh load examples/load/multi-arrival.yaml
~~~

Copyable examples and field descriptions are maintained in [examples/load/README.md](../examples/load/README.md). Historical v1.0/v1.1 schemas are archived and are not accepted as active versions. Migrate to v1.2 workloads syntax; see [Migrations](reference/appendices/migrations.md).

## 05 Resources and Integrations

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are peer integration/resource types. SSHHelper routes command-backed Tools. They converge on the common operation-result/evidence contract.

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
HTTPHelper -> HTTP operation ---------/
SSHHelper -> Tool SSH routing --------/
```

Resource IDs are logical contracts referenced by Templates/expressions or Tool groups. Environment profiles may bind the same DB/MQ/HTTP/SSH logical ID to different descriptors without changing Action YAML.

### 5.1 Tool

A Tool is a named external or framework-native capability. A descriptor selects exactly one backend: command or call.

#### Command-backed Tool

A command Tool declares stdoutFormat to parse external stdout into the typed result:

~~~yaml
tools:
  queryOrder:
    command: [./tools/query-order.sh]
    stdoutFormat: json
    arguments: {}
~~~

stdoutFormat accepts text, json, yaml or xml. It is ingress parsing: ATT parses stdout once and publishes the typed value at output.result. It does not control human-readable logging or file output. Exit code, bounded stdout/stderr preview and streamed process artifacts remain evidence.

#### Call-backed Tool

A call-backed Tool invokes a built-in or supported native DB/MQ/HTTP operation. Its native typed return value is output.result. Call-backed descriptors do not declare stdoutFormat.

~~~yaml
tools:
  queryOrder:
    call: "#{db.orders.query(sql='select id from orders where id=:id', parameters={id: ${input.orderId}})}"
    arguments:
      orderId:
        name: Order ID
        description: Order key
        required: true
~~~

Tool invocation has no result.format/path/overwrite contract. File persistence is explicit to an API that defines it; human-readable presentation belongs to Log or configured evidence output. HTTP/MQ parsing is owned by those transport boundaries.

See [Actions and Typed Values](reference/14_actions.md) for Action result handling and [Operation Result and Evidence](reference/05_resources/operation_result.md) for typed results versus evidence.

### 5.2 DBHelper

DBHelper is a first-class JDBC resource, configured independently from Tools. Each descriptor uses `schemaVersion: att-dbhelper/v2.6` and a stable logical `id`; global `dbhelpers` references descriptor files.

```yaml
schemaVersion: att-dbhelper/v2.6
id: orders
driverClass: oracle.jdbc.OracleDriver
url: ${ENV:ORDERS_DB_URL}
username: ${ENV:ORDERS_DB_USERNAME}
password: ${ENV:ORDERS_DB_PASSWORD}
```

Credentials may be resolved from environment variables and must not be published into `META`, reports or diagnostics. JDBC driver jars are supplied in `lib/`; ATT does not bundle a database driver.

A `type: db` Action selects one helper ID and exactly one `query` or `update` block. Read operations are also available through supported `#{db.<id>.query(...)}` / `scalar(...)` expression calls. Positional JDBC `?` bindings and direct-Action named `:name` parameters are supported by the documented contracts.

Queries return typed rows/scalars; updates return the documented update result. Operation and SQL/parameter evidence enters the common Action envelope. Secret credentials are never evidence. Parameter evidence follows descriptor/Action masking/type policy.

Direct DB Actions may declare `timeoutMs` from 1 to 3,600,000 ms. When present, `Action.timeoutMs` overrides `DBHelper.statement.timeoutSeconds`; otherwise the helper timeout is used. Each retry attempt gets a fresh Action timeout, and the retry interval is outside that timeout.

A direct `query` Action may also use the standard retry block with `maxAttempts` 2–10, `intervalMs` 0–3,600,000, and a non-empty unique `retryOn` list containing `ASSERTION` and/or `TIMEOUT`. An explicit Action `timeoutMs` overrides the helper's statement-timeout default; without it, the helper default applies. JDBC query timeout is rounded up to whole seconds while ATT retains millisecond deadline cancellation. `ASSERTION` requires an Action `assert`. Ordinary SQL errors are terminal. Retry-enabled query attempts are retained in `output.attempts[n]`; the top-level `output.result` / `output.evidence` represent the final or winning attempt, with `winningAttempt` or `finalAttempt` recording the terminal attempt number.

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

MQHelper is a logical IBM MQ resource with one or more physical instances. Current descriptors use att-mqhelper/v1.2. Connection settings, credentials, queue defaults and pool limits belong to the resource and are not exposed through META.

~~~yaml
schemaVersion: att-mqhelper/v1.2
id: payment
name: Payment MQ
description: Payment request/reply queues
defaults:
  connection:
    queueManager: QM1
    host: mq.example.internal
    port: 1414
    channel: APP.SVRCONN
  message:
    requestQueue: PAYMENT.REQUEST
    replyQueue: PAYMENT.REPLY
  requestReply:
    waitMs: 5000
    responseFormat: xml
  pool:
    maxSize: 20
instances:
  - id: primary
evidence:
  payload: none
  output:
    format: text
    maxChars: 10000
~~~

A Tool Action calls mq.<id>.send, mq.<id>.receive or mq.<id>.request as its primary operation. MQ reply bytes are decoded using received CCSID metadata when available, then parsed by responseFormat (text/json/yaml/xml). The parsed typed value is output.result. responseFormat owns ingress parsing; Log.format and evidence.output.format only control presentation.

#### Sending represented and abstract values

Render output is a DocumentValue and can be passed directly as payload:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

ATT encodes the exact rendered text using the configured MQ charset/CCSID. It does not parse and reserialize the document. Do not supply requestFormat for DocumentValue. The document format does not set MQMD.Format; MQ transport metadata remains resource-owned.

A Map/List is an abstract structured value and requires requestFormat (text/json/yaml/xml), for example payload=${EXEC.INPUT.request}, requestFormat=json. DocumentValue + requestFormat and String + requestFormat are rejected. payload and file are mutually exclusive. file remains available for explicit raw file input; Render does not create a file or targetFiles.

#### Evidence, response parsing and Load

MQ evidence may contain bounded transport metadata such as helper/instance identity, operation, safe queue names, message IDs, CCSID, byte counts, response format, duration and failure classification. Payload capture is controlled by evidence.payload; human-readable result snapshots are separately controlled by evidence.output. Load can disable resource snapshots with evidence.resources.output: none; otherwise formatting is deferred until the iteration is retained. Typed result and response parsing do not change.

Call-level responseFormat may override requestReply.responseFormat for receive/request; send does not parse a reply. Instance selection and pool limits belong to the descriptor. Historical v1.0/v1.1 schemas are archived; migrate descriptors to v1.2 before validation.

See [Actions and Typed Values](reference/14_actions.md) for the shared DocumentValue and typed-result contract.

### 5.5 HTTPHelper

HTTPHelper is an environment-bound HTTP resource. The selected config profile binds a stable logical helper ID to its base URL. Descriptors use att-httphelper/v1.1.

~~~yaml
schemaVersion: att-httphelper/v1.1
id: payment
name: Payment API
description: Payment service
baseUrl: https://payments.example.internal
defaults:
  responseFormat: auto
  connectTimeoutMs: 5000
  readTimeoutMs: 30000
  followRedirects: false
evidence:
  output:
    format: json
    maxChars: 10000
~~~

Call http.<id>.get/post/request as the primary call of a type: tool Action. Response bytes are parsed at this boundary using call responseFormat, the helper default, or Content-Type when auto is selected. Supported response formats are auto, text, json, yaml and xml. The parsed native value is output.result. Optional evidence.output is a bounded human-readable snapshot and never changes that value.

#### Request bodies and DocumentValue

A Render Action returns a DocumentValue containing format and authoritative rendered text. Pass it directly as body:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
sendRequest:
  type: tool
  call: "#{http.payment.post(path='/v1/payments', body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

HTTP sends the exact DocumentValue text to its charset-encoding boundary. ATT does not parse and reserialize it. Do not combine a DocumentValue with requestFormat.

A Map/List is an abstract structured value and requires explicit requestFormat, such as body=${EXEC.INPUT.request}, requestFormat=json. requestFormat accepts text, json, yaml or xml and applies only to Map/List. DocumentValue + requestFormat and String + requestFormat are rejected. body and file are mutually exclusive; file is explicit raw file input supported by the HTTP call. Render creates no result file and has no targetFiles.

DocumentValue.format does not override resource-owned HTTP Content-Type. Configure contentType/header when a specific media type is required. Request charset/headers and response parsing remain HTTPHelper concerns, separate from Action result or Log formatting.

#### Failure and evidence

Transport/protocol and response-parse failures are operational errors. A received 4xx/5xx is a completed response and can be asserted through statusCode. HTTP evidence may include helper ID, method, safe URL, response status, content type, byte counts, response format and duration. Credentials and payloads are not implicitly stored. Load can set evidence.resources.output: none to skip optional resource-output formatting, or defer it until the iteration evidence is retained.

See [Actions and Typed Values](reference/14_actions.md) for the shared DocumentValue and typed-result contract.

### 5.4 SSHHelper: logical SSH targets

SSHHelper routes a command-backed Tool to a stable logical application-server ID instead of embedding a physical host in the Tool group. The `att-sshhelper/v1.0` YAML descriptor contains `id`, optional `name`/`description`, optional `defaults` (`user`, `port`, `identityFile`), a non-empty ordered `instances` list, optional `selection.strategy`, and optional `fanout.maxConcurrency` (default 4, range 1–256). Each instance needs `id` and `host`; `user` must come from the instance or defaults. Instance fields override defaults; port defaults to 22 and must be 1–65535. Helper and instance IDs match `[A-Za-z_][A-Za-z0-9_-]*` and are unique ignoring case. Invalid hosts/users, unknown properties, duplicates, missing users, unsafe paths, and unsupported strategies fail before SSH execution.

```yaml
# config/sshhelpers/sit/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
name: Application servers
description: SIT application tier
defaults: {user: deploy, port: 22, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: roundRobin}
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

Bind descriptor paths globally or in `environments.<NAME>.sshhelpers` of `att-config/v2.10`. Current packages use config v2.10 and Tool Group v2.9. The selected environment's list replaces the global list; omission inherits it. A group binding must resolve to the same logical ID in each selected profile. SIT can bind one host and UAT two without changing the Tool or Action:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.10
environment: SIT
toolGroups: [config/tools/application.yaml]
environments:
  SIT:
    sshhelpers: [config/sshhelpers/sit/application.yaml]
  UAT:
    sshhelpers: [config/sshhelpers/uat/application.yaml]
```

```yaml
# config/sshhelpers/uat/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
defaults: {user: deploy, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: all}
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# config/tools/application.yaml
schemaVersion: att-tool-group/v2.9
id: app
name: Application tools
description: Remote application inspection
ssh:
  helper: application
  selection: {strategy: all} # optional group override
tools:
  status:
    name: Status
    description: Print service status
    command: [systemctl, is-active, example.service]
    result: {format: text}
```

The unchanged Action calls `app.status`. Set `APP_SSH_KEY` to a readable private-key **path** in the local/CI secret environment, then validate both profiles: `./att.sh validate --config config/config.yaml --env SIT --package` and the equivalent UAT command. An exact `${ENV:NAME}` identity-file reference is resolved at load time; a missing/empty variable is rejected without revealing its value. A Tool group uses either direct SSH (`host`, `user`, optional `port`/`identityFile`) or logical SSH (`helper`, optional `selection`), never both. Call-backed Tools cannot use SSH. The active Tool Group schema is v2.9. Command-backed Tools declare stdout parsing with `stdoutFormat` (`text|json|yaml|xml`); call-backed Tools preserve their native result type. Superseded config and group schemas are migration references only. SSH routing details are not published as `META.SSHHELPER`; see [Runtime Context](reference/03_runtime_context.md) for the public META inventory and the reason. There is no Action- or per-call strategy override.

Strategy precedence is group override then helper default. One instance works without a strategy (`single`); multiple instances require one. `random` selects one uniformly, `roundRobin` selects one via a thread-safe cyclic counter, and explicit `all` executes every listed instance once with bounded parallelism. **`all` has side effects on every host**: use only commands safe across the entire group. There is no implicit fan-out, cross-host retry, or failover. If an author configures an Action timeout retry, the whole `all` invocation is repeated, not just one host. Each host gets the Action/Tool/global timeout; interruption cancels active OpenSSH processes or Java SSH sessions. Both transports receive the same normalized host/user/port/key. OpenSSH is preferred; mwiede/jsch fallback retains strict host-key verification and the limitations in the SSH diagnostics chapter.

For a single selected host, parsed `output.result` remains the legacy scalar/object value. Evidence adds `sshHelper`, `instance`, `host`, `selectionStrategy`, `selectionSource` (`helper` or `toolGroup`), transport, start/end/duration, exit code, output and errors. For `all`, `output.result` contains `sshHelper`, effective `selectionStrategy`, `selectionSource`, and `instances` keyed in descriptor order. Every entry has `instance`, `host`, `port`, `transport`, `startedAt`, `endedAt`, `durationMs`, `status`, and when available `exitCode`, `stdout`, `stderr`, `rawOutput`, parsed `output`, or `error`. A completed command has `status: PASS` even with a non-zero `exitCode`; that code is evidence for the Action assertion, not an operational failure. The operation fails only on an execution, output-parse, cancellation, or timeout error; other hosts' evidence is retained. Assertions may inspect `${output.result.instances.app1.exitCode}`, `${output.result.instances.app1.status}`, or `${output.result.instances.app1.output}`. Credential contents and environment-supplied key paths are not recorded; keep private keys outside the package and do not put secrets in commands.

Environment-supplied identity paths are redacted from argv, transport stderr (including streamed Case-log diagnostics), and exception evidence for both single-host and `all` execution. This no-recording guarantee applies to ATT metadata and transport diagnostics; parsed business stdout remains unchanged, so commands must not print secret paths.

Migration: leave direct SSH unchanged if one physical target suffices. To migrate, move its host/user/port/key into a helper descriptor, bind that descriptor per environment, upgrade the group to v2.7, replace physical `ssh` with `ssh: {helper: application}`, and validate each environment. Actions stay unchanged. Inventory discovery, per-Action host override, distributed transactions, cross-host failover and orchestration are out of scope.

### 5.5 Operation Result and Evidence

ATT keeps an operation's logical result separate from execution evidence:

~~~text
Operation
├── result       # native typed value
└── evidence     # bounded execution/transport metadata
~~~

The Action publishes the final operation value at output.result. Action status, assertion detail, diagnostic and attempts describe execution; they do not replace the business result. Command stdout is parsed through stdoutFormat. HTTP/MQ responses use responseFormat. DB operations return native typed values. Render returns DocumentValue as described in [Actions and Typed Values](reference/14_actions.md).

Resource evidence can include low-cost metadata. A helper may also configure an optional human-readable snapshot:

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

Evidence output supports json, yaml, xml, text and sqlplus. It is presentation only; it does not mutate or replace output.result. Secret-bearing values are filtered or omitted.

Load scenarios may set evidence.resources.output to inherit (default) or none. none skips optional resource-output formatting/materialization. inherit defers formatting until a success sample or failure receives a retention slot. Metrics-only iterations do not serialize resource output or create an evidence workspace. Transport parsing and Render representation are unchanged.

## 06 Environment and Test Data

Environment selection changes resource bindings, not Action logic.

### Environment profiles

`att-config/v2.10` defines the current `environment` default and `environments` map. `--config` selects the shared configuration file; `--env` selects one profile and overrides the configured default. Matching is case-insensitive. Unknown profiles fail before external execution.

Profiles are typed shallow bindings, not generic recursive YAML inheritance. The profile may replace each configured `dbhelpers`, `mqhelpers`, `sshhelpers`, or `httphelpers` list as a whole; an omitted list inherits the common root list.

```yaml
schemaVersion: att-config/v2.10
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
    sshhelpers: [config/sshhelpers/sit/application.yaml]
    httphelpers: [config/httphelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
    sshhelpers: [config/sshhelpers/uat/application.yaml]
    httphelpers: [config/httphelpers/uat/payment.yaml]
```

Each environment should expose the same stable logical IDs (`orders`, `payment`, `application`, etc.). Templates, Flows, Actions, and Tool-group bindings then remain unchanged across SIT/UAT/PREPROD. SSH endpoint details are intentionally not published as `META.SSHHELPER`; see the [SSHHelper chapter](reference/05_resources/sshhelper.md) and [Runtime Context inventory](reference/03_runtime_context.md).

### Topology and secrets

Topology may vary by descriptor and environment. Inject secrets through `${ENV:NAME}` where supported; never commit them or expose resolved values in META, reports or diagnostics. Missing required variables identify the field/name without printing the secret.

### Cross-mode consistency

Run, Validate, Debug, and Load resolve the selected environment through the same effective configuration. `--env` is not Action branching and does not create mode-specific helper IDs.

### Separate configuration files

Separate `--config config/environments/sit.yaml` and `uat.yaml` files remain useful when package roots, report policy, Tool topology, or other configuration intentionally differ. Use profiles when the package contract is shared and only resource bindings change.

### Test data extension point

Workbook/sidecar/snapshot remains the Testcase data contract. Environment-bound business inputs belong in `EXEC.INPUT`; environment selection belongs to configuration, not Action expressions.

## 07 Expressions and Built-ins

### Unified expression engine

ATT uses one expression engine for runtime Templates, Flows, Actions and Tool calls:

- ${path} reads a Context value and interpolates it into surrounding text.
- #{expression} evaluates a typed expression. It supports Context operands, built-in calls, list literals, parentheses, unary operators, arithmetic, comparisons, like, in, null checks and boolean logic.

A complete expression preserves its value type. For example, an exact #{...} may return a number, boolean, map, list or DocumentValue. Embedding an expression in surrounding text produces a String. Use canonical EXEC and META paths; optional lookup uses a trailing question mark.

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

Use the expression form supported by each field. Render content, Action descriptions/assertions, Log message/value, assign expressions and Tool calls use the ordinary runtime model. A Log value can recursively contain typed expressions; see [Actions and Typed Values](reference/14_actions.md).

### Load execution ID initialization

ATT does not define a separate non-runtime/configuration expression language. Load execution.execIdFormat uses the same ${...} / #{...} engine, evaluated once during iteration initialization. Its accessible values are limited by lifecycle: EXEC.RUN_ID, timestamps, EXEC.INPUT, stable EXEC.LOAD identity, and META branches already initialized.

EXEC.ID/EXEC.OUTPUT_DIR are not yet available because the ID determines the workspace. EXEC.ACTIONS and invocation-scoped Flow/Tool/DB/MQ/HTTP metadata are absent. Arrival-rate has no EXEC.LOAD.USER_ID. Only deterministic side-effect-free built-ins are allowed; external calls, seq.next(), random/clock/filesystem functions are rejected.

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

For arrival-rate, omit USER_ID:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

See [Runtime and Context Model](reference/03_runtime_context.md) for the full META inventory, lifecycle table and artifact-navigation layout. There is no general configuration-expression model in 3.6.0.

### `config.report.fileNamePattern`

#### Context and legal forms

`report.fileNamePattern` uses the unified expression engine with a dedicated non-Case scope. It has a dedicated configuration-local root, separate from EXEC:

| Placeholder | Value |
|---|---|
| `${suiteName}` | Source workbook basename with its final lowercase `.xlsx` suffix removed; for example, `testcase/payment_regression.xlsx` becomes `payment_regression` |

The configured string must reference `${suiteName}` explicitly, whether used as text interpolation or as a built-in argument. No other general non-runtime/configuration expression roots are defined. Bare `suiteName` inside a call is rejected. Legal examples include:

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

These values fail configuration loading because they do not reference `${suiteName}`:

```yaml
fileNamePattern: "result.xlsx"
fileNamePattern: "${RUN_ID}.result.xlsx"
fileNamePattern: "${WORKBOOK_ID}.result.xlsx"
```

No other configuration root or Runtime Context path is supported. Configured Tool calls are also unavailable in this scope. These forms are invalid:

```text
${RUN_ID}
${WORKBOOK_ID}
${ENVIRONMENT}
${EXEC.INPUT.caseId}
${EXEC.ID}
#{configuredTool()}
#{upper(${RUN_ID})}
```

A pattern such as `${suiteName}-${RUN_ID}.xlsx` is rejected; unknown references are never retained as literal output text. All documented built-ins are parsed by the same engine, including nested calls. Because the resulting text becomes a filename, prefer deterministic string transformations and avoid side-effecting filesystem built-ins, random values, path separators, absolute paths, `..`, and platform-reserved names.

### Tool-definition `command` expressions

#### Context and legal forms

A configured Tool `command` also has its own restricted Context. It may reference only keys declared by that Tool's `arguments` map. The canonical placeholder is `${input.argument}`. `${TOOL.input.argument}` and the exact `${argument}` spelling remain compatible legacy forms and both produce `CONTEXT_TOOL_INPUT_SHORTHAND` when they uniquely match a declared key:

| Form | Meaning |
|---|---|
| `${requestText}` | Legacy shorthand; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${input.requestText}` | Explicit Tool-input namespace |
| `${TOOL.input.requestText}` | Legacy full alias; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |

For example:

```yaml
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: Invoke a rendered payment request
    command:
      - ./tools/invoke_payment_api.sh
      - "${input.requestText}"
      - "${input.environment}"
    stdoutFormat: json
    arguments:
      requestText:
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
  call: "#{invokePaymentApi(requestText=${EXEC.ACTIONS.renderRequest.output.result}, environment=${EXEC.INPUT.environment})}"
```

The call resolves the explicit `${EXEC.ACTIONS...}` and `${EXEC.INPUT...}` references first and creates Tool inputs named `requestText` and `environment`. The command then substitutes `${input.requestText}` and `${input.environment}` from those inputs; `${input.environment}` does not read global configuration directly. The legacy `${requestText}` / `${ENVIRONMENT}` spelling and `${TOOL.input.*}` remain compatible only when each name is declared and emit `CONTEXT_TOOL_INPUT_SHORTHAND`.

Each command token also accepts built-in calls through the same expression engine. Built-ins see only the declared Tool-input aliases shown above, and calls may be nested:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--environment=#{upper(${input.environment})}"
  - "--label=#{concat('ATT-', #{lower(${input.requestText})})}"
```

Inside a command-side built-in call, declared inputs must also use placeholders: `${input.requestText}` is canonical; `${TOOL.input.requestText}` and `${requestText}` are deprecated compatible forms and produce `CONTEXT_TOOL_INPUT_SHORTHAND`. Bare `requestText` or `input.requestText` is not inferred. Outside `#{...}`, command text continues to use the same Tool-local rule.

A normal argument placeholder may occupy a complete argv token, which is preferred, or be embedded in fixed text:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--request=${input.requestText}"
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
    stdoutFormat: yaml
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
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.INPUT.sourceFile}
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
${EXEC.ID}
${STAGES.invoke.InstrAmt}
${input['requestText']}
${TOOL.input['requestText']}
${requestText.path}
```

Configured Tool calls are not available inside `command`:

```text
#{anotherConfiguredTool(value=${requestText})}
```

This is rejected during configuration loading. Expanding one Tool's command cannot invoke another Tool or recursively invoke itself. An unknown, misspelled, differently cased, or undeclared `${...}` argument reference is also a validation error. For a standalone global Tool, the executable token is static and cannot itself contain `${...}` or `#{...}`.

If an argument declares a non-empty `argName`, its placeholder must appear exactly once and occupy one complete command token:

```yaml
command: [./tools/invoke_payment_api.sh, "${input.requestText}"]
arguments:
  requestText:
    name: Request File
    description: Rendered XML request path
    required: true
    argName: --request
```

ATT expands that token to two argv values: `--request`, then the resolved path. An embedded form such as `--request=${input.requestText}` or a transformed form such as `#{str.upper(${input.requestText})}` is invalid when `argName` is non-empty. Likewise, every typed List must use a complete-token placeholder so ATT can safely expand it to zero or more argv values. For an optional argument, a blank complete-token placeholder emits neither its `argName` nor a value; an embedded scalar placeholder instead leaves its surrounding fixed token in argv.

### Operators

Supported assertion operators are `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, `not`, `and`, and `or`. Use parentheses when mixing logical operators so intent is explicit.

`like` is case-insensitive as an operator keyword, but the canonical authored spelling is lowercase. It matches the complete value and uses two SQL-style wildcards:

- `%` matches zero or more characters;
- `_` matches exactly one character;
- matching is case-sensitive.

The current implementation translates `%` to Java regular-expression `.*` and `_` to `.`. Other regular-expression metacharacters such as `.`, `+`, `*`, `[`, `]`, `(`, `)`, `?`, `^`, `$`, `|`, and backslash retain their regular-expression meaning instead of being escaped automatically. Use `like` with ordinary literal text plus `%`/`_`; use `==` for exact text.

Comparison first recognizes boolean literals. If both operands are valid decimal numbers, ATT compares them as arbitrary-precision decimals, including ordinary Excel strings such as `"100"`; numeric equality also uses this coercion, so `1.0 == 1` is true. Otherwise ATT compares the rendered strings lexicographically and case-sensitively. A present blank value compares as an empty string; a missing Context path is `ATT-CTX-001`, not an implicit null/empty value. Use `is null` only for a path that exists with a null value, and use `#{number(...)}` when invalid numeric text should become an explicit evaluation error instead of a string comparison.

### Built-in functions

Built-ins are called with `#{...}`. Canonical names use framework-owned `str.*`, `date.*`, `file.*`, `misc.*`, and `seq.*` packages. Legacy flat names remain aliases for compatibility. Tool groups use the same package-like `group.tool` shape; configured Tools cannot claim a built-in package root or any canonical/legacy built-in name.

| Function | Purpose | Example |
|---|---|---|
| `seq.next` | Return a run-scoped `Long`; optional sequence name and width produce independent named counters or exact-width zero-padded text | `#{seq.next('payment', 10)}` |
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
| `file.exists` | Test whether a regular file exists | `#{file.exists(${EXEC.INPUT.requestText})}` |
| `file.directoryExists` | Test whether a directory exists | `#{file.directoryExists(${EXEC.OUTPUT_DIR})}` |
| `file.size` | Return regular-file size in bytes | `#{file.size(${EXEC.INPUT.requestText})}` |
| `file.mkdirs` | Create a directory tree and return its absolute path | `#{file.mkdirs(${EXEC.INPUT.archiveDirectory})}` |
| `file.copy` | Copy a regular file and return the target path | `#{file.copy(${EXEC.INPUT.requestText}, ${EXEC.INPUT.backupFile}, true)}` |
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

#### `seq.next` run-scoped sequences

`seq.next` supports these four positional overloads (the same arguments may be supplied by the names `name` and `width`; do not mix named and positional styles):

| Call | Counter | Return value |
|---|---|---|
| `#{seq.next()}` | Default sequence | Incrementing Java `Long` |
| `#{seq.next('payment')}` | Independent sequence named `payment` | Incrementing Java `Long` |
| `#{seq.next(10)}` | Default sequence | Java `String`, decimal value left-padded with zeroes to exactly 10 characters |
| `#{seq.next('payment', 10)}` | Sequence named `payment` | Java `String`, decimal value left-padded with zeroes to exactly 10 characters |

The default and each named sequence have independent counters, each starting at 1. State is owned by one ATT Run: Run shares counters across its Testcases and suites; Debug has a fresh service for its one-shot execution; Load shares counters across its workloads and concurrent iterations. Each per-name counter is thread-safe and issues unique, monotonically increasing values within that Run; concurrent scheduling does not guarantee which VU receives which value. A new Run, Debug execution, or Load run starts the counters again at 1. No `EXEC.SEQUENCES` Context node or reset/current API is exposed.

| Mode | Example use | Scope note |
|---|---|---|
| Testcase | In an `assign` Action: `expression: "#{seq.next('payment', 10)}"` | Consecutive Cases in one Run share the `payment` counter. |
| Debug | In an `assign` Action: `expression: "#{seq.next()}"` | A fresh one-shot Debug execution starts at 1. |
| Load | In an `assign` Action in the target Template/Flow: `expression: "#{seq.next('load-order', 10)}"` | Iterations share the counter; concurrent calls are unique, but no stable VU allocation order is promised. |

`width` must be an integer from 1 through 1000. The name must be non-blank text; with one positional argument, a number means `width` and a string means sequence name. More than two arguments, mixed named/positional argument styles, invalid argument types, blank names, fractional/zero/negative/out-of-range widths are errors. Diagnostics identify `seq.next` and the invalid arity, argument, or range. If a padded value needs more digits than `width`, or the underlying `Long` counter overflows, evaluation fails explicitly; ATT never truncates a sequence or silently exceeds the requested width.

The single-value `str.upper/lower/trim/ltrim/rtrim/length` and `misc.string/number/boolean` functions accept either `value=...` or one unnamed value. Other built-ins accept either their documented names or a complete positional list; do not mix named and positional arguments in one call. Case conversion is locale-independent. `misc.number` rejects non-numeric input and removes unnecessary trailing zeroes. `misc.boolean` accepts true/false, yes/no, and 1/0. `str.concat` treats null as empty; `misc.coalesce` skips null and whitespace-only values and returns empty when none qualifies. `misc.nvl` tests null/empty without trimming. `misc.iif` accepts the same boolean text forms and resolves all three arguments eagerly. `str.repeat` requires an integer count from 0 through 10000 and repeats the complete value.

`substr(value, start[, length])` uses zero-based UTF-16 indexes. A negative start counts from the end; an out-of-range start or negative length is an error, while an overlong length stops at the end. `indexOf` is case-sensitive, accepts an optional zero-based `fromIndex`, and returns `-1` when absent. Match and replacement functions are case-sensitive and literal, not regular expressions. Padding defaults to one space, never truncates an already long value, rejects an empty pad, and limits target length to 10000.

`sysdate()` returns `yyyy-MM-dd`. `systimestamp()` returns `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`; both use the JVM system zone at invocation time. Each accepts zero arguments or one positional/named `format` argument using a locale-independent Java `DateTimeFormatter` pattern. Blank, invalid, or incompatible patterns are `ATT-BUILTIN-001` errors that identify the function, argument, supplied value, and formatter cause. `formatDate` accepts ISO local dates, local date-times, offset/zoned timestamps, and UTC instants, then applies the same pattern rules. `zoneId` accepts an IANA name such as `Asia/Hong_Kong` or an offset such as `+08:00`; it converts instant/offset/zoned values and attaches a zone to a local date-time. `dateAdd` preserves the input ISO shape and accepts singular/plural `year`, `month`, `week`, `day`, `hour`, `minute`, `second`, or `millisecond`; incompatible combinations such as hours plus a date-only value are errors.

Filesystem built-ins resolve relative paths against the ATT JVM working directory and return normalized absolute paths from create/copy/move operations. Existence and size functions accept only their documented regular-file or directory type and do not follow the final symbolic link. Copy and move reject symbolic-link sources/targets, create missing target parents, and default `overwrite` to `false`; an existing target is an error unless `overwrite=true`. `deleteFile` rejects directories, may delete a file or symbolic link itself, and defaults `missingOk` to `false`. Filesystem errors produce action ERROR and these in-process operations create no TOOL process artifacts.

`randomChoice` accepts either a complete positional list or consistently named values, preserves the selected value's type, and rejects zero, more than 1000, or mixed-style inputs. Selection is deliberately non-deterministic and is intended for test-data variation, not cryptography or reproducible sampling.

`dbText` accepts exactly one positional argument or named `value`. The value must be a stable query/update result returned by a direct DB Action, DB expression, or DB-backed Tool. It uses exactly the same deterministic formatter as DB `output.result` text presentation and has no JDBC, transaction, connection, or cache side effects.

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

Tool evidence collectors run after the primary operation has published its typed `output.result` and before that attempt's assertion. While the Action is active, `${output.evidence.collectors.<id>.result}` and `${output.evidence.collectors.<id>.status}` are available; after publication the canonical paths are `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` and `.status`. Collectors have independent `timeoutMs` and `onFailure: continue|stop`. Collector output belongs to the attempt's evidence and never replaces or mutates the primary operation result.

Collectors run once per primary attempt. The top-level collector node represents the final/winning attempt, while `output.attempts[n].evidence.collectors.<id>` retains each attempt. `continue` keeps the primary/assertion outcome visible when diagnostic collection fails; `stop` makes the collector failure an Action error. Use an ordinary Tool/Log/Assign Action when the collected value is business/test data rather than pre-assertion diagnostics.

### Transaction/resource lifecycle

DB transaction finalization and DB/MQ resource cleanup occur at the appropriate execution lifecycle boundary. These mechanisms can affect operation success/diagnostics but are internal resource state, not public Context namespaces.

### Aggregation

When multiple child outcomes contribute to a parent, severity is preserved:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

Future fixture behavior (#38) and DB Action-level timeout/retry (#39) extend this chapter's existing concepts rather than creating a new reliability model.

## 09 Configuration Reference

This chapter is the authoritative reading reference for author-authored configuration. The files below [`schemas/`](../schemas) remain the machine-readable contract. Schema validation runs before cross-field and filesystem validation.

### Configuration layers and precedence

| Layer | Source | Owns |
|---|---|---|
| Global | `config/config.yaml` | output/environment/runtime defaults, template root, reports, XML mode, global tools, group paths, DB/MQ/HTTP/SSHHelper paths, optional legacy inline SSH |
| Tool group | configured YAML path | group identity, optional script/SSH, grouped tools |
| Dbhelper | configured `dbhelpers` YAML path | one database identity, connection, statement timeout, transaction, limits, and evidence policy |
| SSHHelper | configured `sshhelpers` YAML path | logical SSH ID, physical instances, defaults, selection and fan-out cap |
| HTTPHelper | configured `httphelpers` YAML path | logical HTTP ID, base URL, defaults, pool, auth and TLS |
| Workbook | `<workbook>.yaml` | Excel mapping, stages, workbook labels |
| Template | `template.yaml` | template identity and ordered actions |
| CLI | command options | selection, Run ID, output override, presentation, CI formats |

Tool Action timeout overrides Tool descriptor timeout, which overrides global timeout. Sidecars, stages, and Templates do not own timeout/retry defaults. For call-backed DB Tools the dbhelper statement timeout remains a backend ceiling. CLI `--output-dir` and `--run-id` override their applicable defaults for one command. A field valid in one layer is still rejected if placed in another layer.

### Multi-environment profiles in V3.6.0

`att-config/v2.10` is the active profile contract. Profiles can replace configured DBHelper, MQHelper, SSHHelper and HTTPHelper descriptor lists as a whole. See the resource chapters for each binding.

ATT 3.6.0 selects an environment through one common `att-config/v2.10` file. It does not select an environment by changing an Action or by adding an environment-specific Tool ID. Actions keep stable logical IDs across SIT, UAT, PREPROD, and production-like environments:

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

The common config keeps the existing templates, testcase roots, run/execution/report settings, `toolGroups`, and global `tools` registry. The profile layer contains typed DB/MQ/SSH/HTTP descriptor lists; the example below shows DB/MQ bindings:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.10
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
    templateFormat: json

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
        payload=${EXEC.ACTIONS.renderRequest.output.result},
        responseFormat='xml',
        waitMs=5000
      )}
```

`environment` is the default profile name. A case-insensitive `--env` selector overrides it. Each profile may replace configured resource descriptor lists as a whole; omitted lists inherit the common list. No generic recursive YAML merge is performed. Unknown profile names fail before validation or external execution. Use the same package with every supported execution mode:

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

ATT 3.6.0 uses the active resource/configuration schemas below. The current JSON Schema definitions live in schemas/. Historical definitions live under schemas/history and do not enable active runtime compatibility.

| Artifact | Active schema |
|---|---|
| Global configuration | att-config/v2.10 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.2 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Template | att-template/v3.3 |
| Flow | att-flow/v3.3 |
| Load scenario | att-load/v1.2 |

The schema catalog at schemas/catalog.yaml is authoritative. Package validation verifies catalog registrations; it does not make archived schema versions executable. Unsupported active schema versions fail with migration guidance.

### Global configuration

```yaml
schemaVersion: att-config/v2.10
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
| `schemaVersion` | required | Current: `att-config/v2.10`; older configuration versions are not active contracts. The example uses the active schema. |
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
| `report.columns` | `{}` | Supported keys: `result`, `durationMs`, `expectedResult`, `actualResult`, `caseLog`, `reportLink`, `runTime`, `execId`; each value is a string column label |
| `report.html.caseLogInlineLimitBytes` | `32768` | Integer 0–1048576 UTF-8 bytes; larger logs use a bounded head/tail preview plus artifact link |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | Integer 0–1048576 UTF-8 bytes; 0 always links |
| `xml.namespaceMode` | `ignore` | `ignore` or `preserve` |
| `toolGroups` | `[]` | Unique safe package-relative tool-group YAML paths |
| `dbhelpers` | `[]` | Unique package-contained `att-dbhelper/v2.6` YAML paths; normalized duplicates are rejected |
| `mqhelpers` | `[]` | Unique package-contained `att-mqhelper/v1.2` YAML paths; normalized duplicates are rejected |
| `sshhelpers` | `[]` | Unique package-contained `att-sshhelper/v1.0` YAML paths |
| `httphelpers` | `[]` | Unique package-contained `att-httphelper/v1.1` YAML paths |
| `environments` | absent | Non-empty map of profile names; profiles may contain configured resource descriptor lists |
| `ssh` | absent | Optional SSH target for inline global tools |
| `tools` | `{}` | Map of reusable tool contracts |

Allowed global object properties are:

| Object | Allowed properties |
|---|---|
| root | `schemaVersion`, `outputDirectory`, `environment`, `timeoutMs`, `caseLog`, `templates`, `testcase`, `run`, `execution`, `report`, `xml`, `toolGroups`, `dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `ssh`, `tools`, `environments`, `x-*` |
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
| `tools.<key>` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; command Tools require `stdoutFormat`, call-backed Tools may use `cache`; `x-*` |
| call-backed `tools.<key>.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, optional `argName`, `argNameMode`, `delimit`, `x-*` |

V2.0 fields such as `timeoutSeconds`, `reportDirectory`, `logDirectory`, `validation`, and `environmentPolicy` are not V2.2 fields.

### Dbhelper configuration

Each path in global `dbhelpers` resolves from the package root and contains one `att-dbhelper/v2.6` object:

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

Each path in global `mqhelpers` resolves from the package root and contains one active `att-mqhelper/v1.2` object. It defines the logical group, defaults, physical `instances[]`, selection, and evidence policy; each physical instance receives effective `connection`, `message`, `requestReply`, and `pool` values before execution. Optional `evidence.output` controls human-readable snapshots without changing typed `output.result`. See the [MQHelper resource module](reference/05_resources/mqhelper.md).

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

A callable Template directly contains template.yaml and uses att-template/v3.3. Its description and non-empty ordered actions map are required. ATT validates actions against the type-specific active contract.

| Action | Required fields | Typed-result contract |
|---|---|---|
| render | payload | Returns DocumentValue; no result file or targetFiles. |
| tool | call | Publishes the native Tool/helper result. Command stdout parsing is configured by stdoutFormat. |
| db | db and one query/update block | Publishes the native typed DB result. |
| assert | assert | Records PASS/FAIL for the evaluated condition. |
| log | message or value | Accepts level/message/value/format; no file or fields. |
| assign | name/expression | Publishes a typed value below EXEC.VARS. |
| flow | use | Runs a Flow in a nested Action scope. |

Common Action result.format/path/overwrite is removed. Render uses templateFormat to label DocumentValue. HTTP/MQ responseFormat handles ingress parsing; requestFormat is only for abstract Map/List payloads. See [Actions and Typed Values](reference/14_actions.md) for field details, examples, evidence behavior and migration notes.

### Tool contract

A Tool descriptor defines exactly one of command or call. Command-backed Tools require stdoutFormat: text|json|yaml|xml to parse stdout into output.result. Call-backed Tools preserve their native return type and do not define stdoutFormat. Tool descriptors and actions have no common result representation/persistence field. Process output is operational evidence; human-readable formatting belongs to Log or optional resource evidence output.

Tool group resources use att-tool-group/v2.9. See [Tool](reference/05_resources/tools.md) for command, call, argument and evidence examples.

### Identifier and path constraints

Run ID and full Case ID are used directly as directory names; ATT does not slugify or hash a valid identifier.

Run ID must be non-blank, at most 128 Unicode code points, not `.` or `..`, not have leading/trailing whitespace or trailing `.`, and not contain `/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, or control characters. Windows device names such as `CON`, `NUL`, `COM1`, and `LPT1` are rejected case-insensitively.

`workbookId`, `groupId`, and `rowCaseId` follow the same character rules. `workbookId` and `groupId` must not contain `.`, because dots separate the three components; `rowCaseId` may contain dots and is treated as the remaining suffix. Each component is at most 128 Unicode code points and the complete `workbookId.groupId.rowCaseId` is at most 255. The sidecar `id` supplies `workbookId`, the left side of `excel.sheet` supplies `groupId`, and the configured Case ID cell supplies `rowCaseId`. Template paths are relative to `templates.root`; render glob matches remain below the template and resource file inputs and outputs must remain below their documented safe roots. ATT normalizes and checks root containment before reads and writes.

### Validation JSON contract

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.6.1",
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
| `./att.sh run <selection> --quiet` | Suppress detailed live progress; keep the final summary and errors |
| `./att.sh run <selection> --verbose` | Accepted for compatibility; detailed live progress is already the default |
| `./att.sh debug template <id>` | Execute one Template; auto-discover `<template-dir>/debug.yaml` |
| `./att.sh debug flow <id>` | Execute one canonical Flow; auto-discover `<flow-dir>/debug.yaml` |
| `./att.sh debug tool <id>` | Execute one Tool; auto-discover `config/tools/<group>.debug.yaml` |
| `./att.sh debug <type> <id> --input <file>` | Override the target's auto-discovered debug input |
| `./att.sh debug <type> <id> --output-dir <dir>` | Isolate debug output below `<dir>/debug/<debugId>/` |
| `./att.sh debug <type> <id> --format json` | Emit a compact machine-readable console summary; full evidence remains in `result.yaml` |
| `./att.sh debug <type> <id> --quiet` | Suppress detailed live progress; keep the final summary and errors |
| `./att.sh load <scenario.yaml> --quiet` | Suppress periodic live progress; keep the final summary and errors |
| `./att.sh load <scenario.yaml> --verbose` | Accepted for compatibility; bounded live progress is already the default |
| `./att.sh report --run-id <id>` | Regenerate `report/index.html` and `report/junit.html` |
| `./att.sh docs` | Generate `build/docs/index.html` |
| `./att.sh build` | Archive latest completed run in `build/` |
| `./att.sh clean` | Remove documented generated outputs |

Options are command-specific. Unknown commands/options and missing option values are errors. `--package` and `--selected` are mutually exclusive. Selected validation and run require an explicit selection.

`run`, `debug`, and `load` default to interactive verbose behavior. Lifecycle, Case, Stage, Action, resource-attempt, retry, assertion, and error records are written as they occur and flushed promptly. The live Case-log mirror uses the same redacted append path as `case.log`; `case.log`, `case.yaml`/`result.yaml`, reports, and evidence remain the persistent source of truth. Concurrent Case-log chunks carry a Case ID prefix. `--quiet` suppresses detailed live progress but retains a final summary and errors. With `--format json`, machine-readable output remains on stdout and live progress is sent to stderr. Load progress prints bounded periodic counters/rates and throttled errors, never one console block per successful iteration.

### Standalone debug inputs and outputs

Debug input files use `att-debug/v1.0`. `case` values become synthetic `CASE` data, `stage.key` and `stage.values` declare the one debug stage, and `inputs` is adapted directly into canonical `EXEC.INPUT.*`. For compatibility, `${CASE.inputs.<field>}` remains a read-only view when no business field is literally named `inputs`; it is not duplicated below `EXEC.INPUT`. Tool arguments come from the root `arguments` map or `tools.<localKey>.arguments`. An explicit `--input` always wins over auto-discovery.

Before execution ATT validates only the selected Template or Flow dependency closure, or the selected Tool definition. It does not require unrelated workbook snapshots or unrelated malformed Template descriptors to pass. The selected target still uses the normal Template/Flow/Tool runner, including Context resolution, Flow nesting, Tool retry/timeout, evidence, Action result persistence, DB finalization, and Case-log behavior.

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

The selected input is validated before execution. Missing files, invalid schema, unknown or missing Tool arguments, and other input/configuration errors return exit code `2`. Framework-owned values such as `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.OUTPUT_DIR`, `EXEC.VARS`, and `EXEC.ACTIONS`, together with the corresponding `CASE.*`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, and `DB.*` aliases, remain authoritative even if they appear in the input `case` map. Mode/scheduler diagnostics are not expression-visible. `EXEC.STAGES` is not a canonical Context node; Stage history remains in the legacy `CASE.STAGES` evidence view.

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

### Complete option matrix (3.6.1)

`--config <file>` selects the base configuration. `--env <name>` selects one environment profile from an `att-config/v2.10` configuration and is valid for `run`, `validate`, `debug`, and `load`. `--help` prints help. `--case-id` is a compatibility synonym for `--case`. `--parallel` is the deprecated compatibility spelling for `--allow-parallel-runs`; prefer the latter. `--queue` and `--allow-parallel-runs` control process-level output-root concurrency, not Case workers. `--profile` writes performance diagnostics for `run` or `load`.

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

### Tool evidence collector failures

An evidence collector is post-operation observability, not the primary Tool result. With `onFailure: continue`, the primary Action may remain `PASS` while the collector is independently recorded as `ERROR`:

```yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
```

Inspect `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>` (or the equivalent `ACTIONS` compatibility view). The record contains `status`, `success`, `invocationId`, `result`, `error`, and the bounded/redacted underlying operation `evidence`; when an operation supplies structured diagnostics, `diagnostic` is retained as well. `error.message` is populated from the underlying exception, operation status/exit code, or a safe fallback. Resource identity and fields such as SSH helper/instance, command, exit code, bounded stderr, MQ reason codes, HTTP status, parser diagnostics, and timeout details remain under `evidence` when provided by the executor.

For retries, inspect `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`. Earlier failed collector records remain available after a later successful attempt; the top-level collector record follows the final/winning attempt. With `onFailure: stop`, the Action can fail, but its diagnostic still includes the collector's root-cause message and preserved evidence. The same structured record is written to the `EVIDENCE <action> attempt=<n> collector=<id>` block in `case.log`, so the basic resource, category, message, exit code, and bounded stderr can be diagnosed without opening internal exception traces. Existing capture limits and secret redaction continue to apply; collector wrapping does not enable unbounded raw output.

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

Run this after every workbook, sidecar, template, helper, or tool change:

```sh
./att.sh validate --package
```

For one environment, use `./att.sh validate --config config/config.yaml --env SIT --package`. ATT 3.6.0 validates changed descriptor families against their active schemas only: config v2.10, DBHelper v2.6, MQHelper v1.2, HTTPHelper v1.1, Tool Group v2.9, Template/Flow v3.3 and Load v1.2. Superseded schema files under `schemas/history/` are historical references, not runtime compatibility contracts. Update the declared `schemaVersion` and migrate fields to the active contract before validation. Diagnostics retain the original violation, file and YAML field location and provide migration guidance; they never rewrite descriptors. For example, remove an old Render `result.path` and pass the typed `output.result` value as described in [Actions and Typed Values](reference/14_actions.md). Unsupported versions fail before execution.

Current schemas are in [`schemas/`](../schemas); older definitions are under [`schemas/history/`](../schemas/history). `validate --package` checks every catalog-registered schema resource, even when the package does not use it. A missing, unreadable, unsafe, or duplicate registered schema is a hard `PACKAGE_INVALID` error. Validation never rewrites YAML. Review the migration guidance, update the file, then rerun package validation for each selected `--env`.

Then use the diagnostic code and structured location. Do not automate against message text.

| Category | Typical cause | Corrective action |
|---|---|---|
| `ATT-TC` | Missing/stale snapshot, sidecar/sheet/header error, or duplicate Case ID | Check snapshot/basenames, sheet mapping, effective headers, and full IDs |
| `ATT-CTX` | Unknown or ambiguous Context path | Inspect requested/current/missing fields, nearest suggestion, or canonical candidates |
| `ATT-STG` | Blank required selector, invalid selector YAML, duplicate stage key | Check selector form, `name`, aliases, and required flag |
| `ATT-TPL` | Unknown/duplicate template, invalid action or payload | Check symbolic name/full path, descriptor, action type, and local files |
| `ATT-CFG` | Unknown field, duplicate key, wrong schema/type/enum | Compare with Chapter 6 and remove unsupported fields |
| `ATT-TOOL` | Unknown/missing argument, process or parse failure | Compare call contract; inspect exit code and bounded stdout/stderr capture evidence |
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

#### When does an unexpected exception get a stack trace?

Unexpected internal failures such as `NullPointerException`, `ClassCastException`, reflection lookup/access failures, other unexpected runtime exceptions, and non-domain `IllegalStateException` (including when nested in a wrapper cause) add a bounded `[ATT INTERNAL ERROR]` block to `case.log` with the execution phase and original cause chain. Validation `IllegalArgumentException`, recognized domain/transport failures, timeout/cancellation, assertion failures, and ordinary MQ no-message outcomes remain concise. A Throwable is written only once per Case log even when both a resource executor and its Action boundary see it. Resource-specific redactions (including environment-supplied SSH identity-file paths) are registered with that Case log and applied to subsequent log writes, so the outer Action diagnostic cannot expose text omitted from the sanitized stack. Stack output is capped at 180 lines/16 KB; configured secrets and sensitive key/value assignments are also redacted. Public Action evidence contains only the compact error type/phase, not the stack. The shared logging path covers Run, Debug, and reusable Tool/HTTP/MQ/DB execution.

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

## 14 Actions and Typed Values

This chapter defines the active ATT 3.6.0 action contract. Templates use att-template/v3.3. Each completed action publishes its logical typed value at output.result. Actions do not use a shared result.format/path/overwrite object. See the Tool, DBHelper, MQHelper and HTTPHelper chapters for resource configuration.

### Action types

| Type | Required fields | Result and behavior |
|---|---|---|
| render | payload | Renders template files into a DocumentValue, or a relative-path keyed map of DocumentValues for multiple sources. It does not parse the document or write a result file. |
| tool | call | Invokes a configured Tool, built-in or helper call and preserves the native typed result. |
| db | db and exactly one query/update block | Returns the DB operation's typed value and evidence. |
| assert | assert | Evaluates a boolean condition and records PASS or FAIL. expected and actual are optional diagnostic values. |
| log | message or value | Formats a typed value for the Case log. Its fields are level, message, value and format. |
| assign | name and expression | Publishes the expression's typed result below EXEC.VARS. |
| flow | use | Runs a registered Flow in a nested Action scope and restores the caller's scope on return. |

Actions run in YAML order. Where supported, an action may also define id, description, onFailure and runWhen. Action IDs are unique within their scope. Type-specific invalid fields fail validation. Action result, Log file and Log fields are not part of the current action contract.

### Separate logical values from representations

ATT keeps the logical operation result separate from human or wire representations:

| Boundary | Field/value | Purpose |
|---|---|---|
| Command Tool stdout | stdoutFormat | Parses external stdout into a typed result. |
| HTTP/MQ response | responseFormat | Parses external response bytes into a typed result. |
| Render output | templateFormat | Labels the representation produced by the template. |
| Abstract Map/List sent over HTTP/MQ | requestFormat | Serializes the value at the outbound boundary. |
| Log or resource evidence | format / evidence.output.format | Produces a human-readable representation. |

DB results are already typed values. Tool, Action, Template, Flow and expression results remain typed while they move through ATT.

### Render and DocumentValue

Render returns a represented document. DocumentValue carries a format and the exact rendered text:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
~~~

One source produces output.result as a DocumentValue. Multiple sources produce an ordered map keyed by template-root-relative source paths. templateFormat accepts auto, text, json, yaml or xml. auto selects json for .json, yaml for .yaml/.yml, xml for .xml, and text otherwise.

DocumentValue.text is authoritative. ATT does not parse it into a navigable map/tree, pretty-print, normalize or rewrite it before transport. Render creates no file and exposes no output.targetFiles. Use the original typed Context value for structured access, such as EXEC.INPUT.amount or a prior action's output.result.amount.

Pass Render output directly to HTTP or MQ:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml

sendRequest:
  type: tool
  call: "#{http.payment.post(body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

For MQ, pass the DocumentValue as payload. Do not add requestFormat to a DocumentValue. A resource encodes its exact text with its configured charset/CCSID. DocumentValue.format does not set MQMD.Format or override resource-owned HTTP Content-Type.

requestFormat is for abstract structured values such as Map or List. Such a body requires an explicit format, for example requestFormat=json. Combining requestFormat with DocumentValue fails, so an already represented document is never silently parsed and serialized. A raw file input remains available only for resource calls that explicitly define a file argument; Render does not create a handoff file.

### Tool, DB and Flow results

A command-backed Tool declares stdoutFormat in its Tool descriptor:

~~~yaml
tools:
  getOrder:
    command: [./get-order.sh]
    stdoutFormat: json
~~~

stdoutFormat parses external stdout once into output.result; it is not output serialization. Call-backed Tools and DB/HTTP/MQ operations keep their native return types.

A DB action uses db and exactly one query or update block. SQL, bind parameters, transaction controls and DB evidence follow the DB action and DBHelper contracts.

A Flow action uses use with a canonical Flow ID. It runs in a fresh EXEC.ACTIONS scope and publishes its result/evidence to the caller when it returns. META.FLOW exists only while that invocation is active.

### Tool evidence collectors

A Tool Action may define first-class `evidence` collectors for diagnostics that must be gathered before the Action assertion. The lifecycle is:

```text
primary Tool call
    -> typed primary output.result
    -> evidence collector call(s)
    -> Action assertion
    -> PASS / FAIL / ERROR
```

Collectors are diagnostic operations, not replacement Actions. Each collector has its own typed result and does not replace or mutate the primary `output.result`:

```yaml
callPayment:
  type: tool
  call: >-
    #{mq.payment.request(
      payload=${EXEC.ACTIONS.renderRequest.output.result},
      responseFormat='xml'
    )}
  evidence:
    appLog:
      call: >-
        #{ssh.app.execute(
          command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100'
        )}
      timeoutMs: 10000
      onFailure: continue
  assert: >-
    ${output.replyReceived} == true
```

While the containing Action, including its assertion, is active, use these paths:

```text
${output.result}
${output.evidence.collectors.<collectorId>.result}
${output.evidence.collectors.<collectorId>.status}
```

After publication, the same values are available below `EXEC.ACTIONS`:

```text
${EXEC.ACTIONS.callPayment.output.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.status}
```

The public shape keeps primary resource evidence and collector evidence separate:

```text
output
├── result                         # primary Tool logical result
├── evidence
│   ├── <resource-kind>            # primary operation evidence
│   └── collectors
│       └── <collectorId>
│           ├── result             # typed collector result
│           ├── status
│           ├── invocationId
│           └── durationMs
└── attempts
    └── [n]
        └── evidence.collectors.<collectorId>.result/status
```

When a Tool retries, collectors run for every primary attempt before that attempt's assertion. The top-level `output.evidence.collectors.<id>` is the final/winning attempt; `output.attempts[n].evidence.collectors.<id>` retains each attempt, including earlier failures. After publication, the corresponding history path is `${EXEC.ACTIONS.<actionId>.output.attempts[0].evidence.collectors.<id>.result}`.

`call` is required. `timeoutMs` is independent of the primary Tool timeout. `onFailure: continue` is the normal application-log pattern so a diagnostic collection failure does not hide the original business or assertion failure; `stop` makes the collector failure an Action error. Collector status and diagnostic remain observable, and collector failure never changes the primary logical result. Distinguish an evidence collector from an ordinary Tool/Log/Assign Action: use a collector for diagnostic data needed before the containing Tool assertion, and an ordinary Action when the collected value is normal business/test data for later assertions.

Collector results follow the normal typed-result rules. Evidence placement does not stringify a map, list or `DocumentValue`; matching-format presentation preserves a `DocumentValue`'s authoritative text. In Load, explicit collector execution is separate from helper `evidence.output` serialization. Resource-output formatting remains controlled by the Load evidence policy and is not silently substituted for or dropped in place of an author-requested collector.

### Log: typed value to Case log

Log is a presentation action and therefore has its own format field:

~~~yaml
logOrder:
  type: log
  level: INFO
  message: "Order response"
  value: "${EXEC.ACTIONS.callOrder.output.result}"
  format: yaml
~~~

level defaults to INFO and accepts TRACE, DEBUG, INFO, WARN or ERROR. At least one of message or value is required. message is rendered as text. value accepts any typed value, including nested maps/lists. Exact ${...} and #{...} expressions preserve their native types; map/list children are evaluated recursively without converting numbers, booleans, nulls or nested values to strings. format accepts text, json, yaml, xml or sqlplus and controls only the emitted Case-log string. When format is present, value is required.

When both message and value are supplied, Log emits the message, a newline, then the formatted value. output.result is that emitted string. A DocumentValue is emitted as its authoritative text when format is omitted or matches its own format; a conflicting format fails instead of converting it. Log does not read a file and has no fields map. Put a typed map/list in value for structured log content.

### Expressions and variable scope

Action expressions use the regular ATT expression engine. A complete ${...} or #{...} expression keeps its result type; embedding an expression in surrounding text produces a String. See [Expressions and Built-ins](reference/07_expressions.md).

assign publishes its typed value once below EXEC.VARS.<name>. The name must match [A-Za-z_][A-Za-z0-9_]* and be unique within the Case. Values assigned in one Stage are available to later Stages. Action-local output is available at output.* while an Action runs and at EXEC.ACTIONS.<id>.output.* after publication. Flow invocation creates a temporary Action namespace; publish values to EXEC.VARS when the caller needs them after the Flow returns.

### Resource output evidence

Resource evidence is separate from the logical result. A helper may configure an optional evidence.output presentation policy:

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

This adds a bounded human-readable snapshot beside operation metadata; it does not change output.result or response parsing. In Load, evidence.resources.output accepts inherit (default) or none. none skips resource-output formatting and file materialization. Metrics-only iterations create no execution directory. When iteration evidence is retained, eligible resource output is formatted lazily into that workspace.

### Removed fields and migration

ATT 3.6.0 accepts only the current schema for each resource. Historical versions are archived under schemas/history and are not active contracts.

| Old configuration | 3.6.0 form |
|---|---|
| Command Tool result.format | Tool descriptor stdoutFormat |
| Render result.format/path/overwrite or renderAs/saveAs | templateFormat; consume output.result as DocumentValue; no implicit file replacement |
| Log file | Pass a typed value to Log.value |
| Log fields | Put a typed map/list in Log.value and select Log.format |
| Render targetFiles handoff to HTTP/MQ | Pass DocumentValue directly as HTTP body or MQ payload |
| requestFormat on rendered output | Remove it; reserve requestFormat for abstract Map/List values |

Unsupported schema versions fail validation before execution with migration guidance. ATT does not silently convert old fields or run Tools/resources while producing that guidance.

See [Runtime and Context Model](reference/03_runtime_context.md) for META lifecycle and [Load Mode](reference/04_execution_modes/load.md) for execution identity and retained evidence paths.

## 14 Appendices

The appendices collect stable lookup material that should not drive the main product narrative: schema/version matrix, compatibility/deprecations, migration notes, and limits/defaults.

### 14.1 Schema and Version Matrix

ATT 3.6.0 active schemas:

| Artifact | Active schema |
|---|---|
| Global configuration | att-config/v2.10 |
| DBHelper | att-dbhelper/v2.6 |
| MQHelper | att-mqhelper/v1.2 |
| HTTPHelper | att-httphelper/v1.1 |
| SSHHelper | att-sshhelper/v1.0 |
| Tool group | att-tool-group/v2.9 |
| Workbook sidecar | att-sidecar/v2.2 |
| Testcase snapshot | att-testcases/v2.4 |
| Template | att-template/v3.3 |
| Flow | att-flow/v3.3 |
| Debug input | att-debug/v1.0 |
| Load scenario | att-load/v1.2 |
| Load summary | att-load-summary/v1.0 |
| Run manifest | att-run/v2.1 |
| Validation JSON | att-validation/v2.1 |
| CI summary | att-ci-summary/v2.1 |

For the changed resource/configuration schemas, older versions are historical definitions under schemas/history; they are not active execution contracts. Unsupported versions fail validation with migration guidance. The repository catalog at schemas/catalog.yaml is authoritative. Package validation checks the registered schema resources themselves; it does not enable runtime compatibility for archived versions.

### 14.2 Compatibility and Deprecated Aliases

Compatibility exists to read established packages without creating a second current model. New authoring uses canonical `EXEC`, `META`, Action-local `output`, current schema versions, `--env`, and current Tool/DB/MQ contracts.

Deterministic legacy aliases may remain readable with migration warnings. Aliases are not created where old semantics conflict with scope isolation or the common result/evidence contract. Deprecated CLI/authoring forms remain documented in their owning chapter or CHANGELOG only when users still need a migration path.

### 14.3 Migration Notes

ATT 3.6.0 separates typed operation results, external parsing, rendered documents, outbound transport and human-readable evidence.

| Previous field/model | 3.6.0 migration |
|---|---|
| Command Tool result.format | Move the parsing choice to the Tool descriptor's stdoutFormat. |
| Common Action result.format/path/overwrite | Remove it. output.result is the native logical typed value; no implicit file replacement exists. |
| Render result.format/path or renderAs/saveAs | Use templateFormat. Render returns DocumentValue with exact text and creates no result file or targetFiles. |
| Render file handoff through targetFiles | Pass the DocumentValue directly as HTTP body or MQ payload. |
| requestFormat on rendered output | Remove it. requestFormat is only for abstract Map/List values; DocumentValue + requestFormat fails. |
| Log file | Pass the value directly to Log.value. |
| Log fields | Put the typed map/list in Log.value and select Log.format. |
| HTTP/MQ common result formatting | Use responseFormat for ingress parsing; optional evidence.output.format is human presentation only. |
| Older active resource/config schema versions | Update schemaVersion to the ATT 3.6.0 active schema and migrate the fields listed above. Archived schemas under schemas/history are not active runtime contracts. |

A Render-to-HTTP example:

~~~yaml
renderRequest:
  type: render
  payload: payload/request.xml
  templateFormat: xml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.ACTIONS.renderRequest.output.result})}"
~~~

For an abstract value, use requestFormat explicitly:

~~~yaml
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.INPUT.request}, requestFormat='json')}"
~~~

For Load, migrate old single-target or v1.1 scenarios to att-load/v1.2 workloads form. Put pacing under each workload and set the optional top-level execution.execIdFormat when a custom EXEC.ID is required. That field uses the ordinary expression engine once during initialization; closed workloads may use EXEC.LOAD.USER_ID, while arrival-rate workloads do not have it. Do not use seq.next() or external/stateful functions in the format.

Unsupported schema versions fail before execution and include migration guidance. ATT does not auto-upgrade package files or invoke external resources to build the diagnostic. See [Actions and Typed Values](reference/14_actions.md), [Runtime and Context Model](reference/03_runtime_context.md), [Load Mode](reference/04_execution_modes/load.md) and [Schema Matrix](reference/appendices/schema_matrix.md).

### 14.4 Limits and Defaults

Use the owning schema/configuration chapter for normative field defaults. Important architectural limits include:

- load V1 chooses exactly one workload model;
- arrival-rate overload policy is `drop`;
- Flow invocation receives a fresh Action scope and caller scope is restored on return;
- `DIAG` is framework-owned evidence and is not part of the expression tree;
- resource lifecycle state is not a public Context tree;
- unknown schema fields are rejected except documented extension locations such as root `x-*` where supported.

Operational numeric limits such as timeout ranges, evidence sample bounds, result limits and pool sizes remain defined by their schemas/descriptors so this appendix does not become a second source of truth.
