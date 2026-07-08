# ATT System Design V1.2

**Version:** V1.2
**Status:** Draft
**Source:** User review comments on V1.1 design
**Last Updated:** 2026-07-09

---

# 1. System Overview

## 1.1 Purpose

ATT, short for Automated Testing Tool, provides a lightweight automation layer for API SIT/UAT testing.

V1.2 extends V1.1 by generalizing the framework from a request/check-oriented execution model into a stage-based tool orchestration model.

The key design change is:

```text
Request Template + Check Template + API Invocation Template

become

Tool Invocation Template
```

In V1.2, ATT does not need to know whether a template is a request template, a check template, an API template, a DB template, or a log template.

ATT only needs to know:

- Which stages a test case should execute
- Which Tool Invocation Template each stage uses
- Which ordered Template Actions each template contains
- Which tools are called by the template
- How tool outputs affect case context, assertions, logs, and report result

Users may define their own template types and stage names according to project needs.

## 1.2 V1.2 Direction

V1.2 keeps the useful V1.1 concepts:

- Excel-driven test cases
- Configurable columns
- `${...}` for context references
- `#{...}` for tool invocations
- Case Runtime Context
- Tool-to-tool information transfer
- Tag-based case selection

V1.2 changes the following areas:

| Area | V1.1 | V1.2 |
|------|------|------|
| Template concepts | Request Template, PreCheck Template, PostCheck Template, API Invocation Template | Tool Invocation Template |
| Template execution | Template behavior implied by request/check type | Ordered Template Actions with explicit action `type` |
| XML template usage | Mainly tied to request generation | Payload template usable by any stage |
| Tool artifacts | Many per-tool files | One ordered case execution log |
| Output layout | Case-level output and separate report directory | Run-level output directory containing case logs, result workbook and run history |
| Report output | Separate report workbook with summary sheet | Copy source Excel and append configured result columns under the run directory |
| CLI entrypoint | Java runner arguments | User-facing `att.sh` |
| Rerun failed | Not defined | Latest failed cases from run history |

## 1.3 Non-Goals

V1.2 does not require ATT to understand business semantics such as payment, account, inward, outward, request, or check.

V1.2 does not replace external tools. ATT still invokes configured tools and evaluates their results.

V1.2 does not require the source Excel workbook to be overwritten during report generation.

---

# 2. V1.2 Design Goals

## 2.1 Unified Template Model

ATT should use one template concept for all execution stages.

A Tool Invocation Template may:

- Render XML, YAML, SQL, text, JSON, or any other payload
- Invoke one or more configured tools
- Evaluate assertions
- Inject values into the Case Runtime Context
- Produce actual results for reporting

Each Tool Invocation Template is made of ordered Template Actions.

Each Template Action has an explicit action `type`, such as `render`, `tool`, or `assert`, so ATT can execute the step without knowing any business-specific template category.

## 2.2 Stage-Based Execution

ATT should keep a multi-stage case flow because it is useful for test readability and migration from V1.1.

However, stage names should not hard-code framework behavior.

Example stages:

- `Pre`
- `Main`
- `Post`

Projects may define other stages:

- `Prepare`
- `Invoke`
- `Verify`
- `Cleanup`
- `Rollback`

## 2.3 Action-Based Template Execution

Template Actions are a new V1.2 design element.

They define the executable steps inside a Tool Invocation Template.

Example:

```yaml
actions:
  renderRequest:
    type: render
    payload: payload.xml

  invokeApi:
    type: tool
    call: "#{invokePaymentApi(requestXml=${TOOLS.renderRequest.output})}"

  checkStatus:
    type: assert
    expression: "${TOOLS.invokeApi.output.Response.Status} == '${expected.status}'"
```

This means:

```text
render payload
  -> invoke tool
  -> assert result
```

Template Actions make template behavior explicit.

ATT does not infer behavior from names such as Request, Check, API, PreCheck, or PostCheck.

## 2.4 Single Case Log

ATT should write one case execution log per case run.

Tool-related command, input, output, stdout, stderr, parsed result, duration, and status are stored in this one ordered log.

ATT should not create separate per-tool files such as:

- `input.yaml`
- `command.txt`
- `stdout.txt`
- `stderr.txt`
- `output.xml`
- `parsed-output.yaml`

## 2.5 Excel-Based Report

The report should be generated from the source Excel workbook.

ATT should:

1. Read the source workbook
2. Copy it to the current run directory
3. Append configured result columns after the test case columns
4. Write execution result back into the copied workbook

The source Excel workbook remains unchanged by default.

## 2.6 User-Friendly CLI

`att.sh` should be the normal user-facing entrypoint.

It should support:

- Running one Excel workbook
- Running every workbook in a directory
- Including cases by tag
- Excluding cases by tag
- Running selected case IDs
- Rerunning latest failed cases
- Dry-run selection preview

---

# 3. Terminology

| Term | Meaning |
|------|---------|
| Test Suite | One Excel workbook or a collection of Excel workbooks |
| Test Case | One executable row in a configured worksheet |
| Stage | A named execution step in a case flow |
| Tool Invocation Template | A template package that defines payload rendering, tool calls, assertions, and context updates |
| Payload Template | Optional file rendered by a Tool Invocation Template, such as XML, SQL, YAML, JSON, or text |
| Tool | External command configured in `config.yaml` |
| Case Runtime Context | Per-case runtime data used by templates and tools |
| Case Execution Log | One ordered log file containing all stage and tool execution details for a case run |
| Result Workbook | Copied Excel workbook with appended execution result columns |
| Run History | Machine-readable record of run-level and case-level outcomes |

## 3.1 Terminology Changes From V1.1

| V1.1 Term | V1.2 Term | Notes |
|-----------|-----------|-------|
| Request Template | Tool Invocation Template | Request generation is only one possible template use |
| Check Template | Tool Invocation Template | Checking is only one possible template use |
| API Invocation Template | Tool Invocation Template | API invocation can be a normal stage action |
| Request XML Template | Payload Template | XML is one payload type, not a framework-level concept |
| Tool Artifact Files | Case Execution Log | Tool details are logged in one case log |
| Report Workbook | Result Workbook | Result workbook is based on the original case workbook |

---

# 4. Overall Architecture

```text
                         att.sh
                           │
                           ▼
                   Parse Run Options
                           │
                           ▼
          ┌────────────────┴────────────────┐
          │                                 │
          ▼                                 ▼
  Single Excel Workbook             Excel Directory
          │                                 │
          └────────────────┬────────────────┘
                           ▼
                    Load Test Cases
                           │
                           ▼
                  Apply Case Selection
        (case-id, tag, exclude-tag, rerun-failed)
                           │
                           ▼
                Build Case Runtime Context
                           │
                           ▼
                  Execute Configured Stages
          (Tool Invocation Templates + Tools)
                           │
                           ▼
                 Write Case Execution Log
                           │
                           ▼
                  Update Run History
                           │
                           ▼
      Copy Source Excel And Append Result Columns
                           │
                           ▼
                    Result Workbook
```

## 4.1 Execution Principle

ATT executes stages sequentially for each selected case.

Within one stage, actions are executed in the order defined by the Tool Invocation Template.

If a stage fails, the template or configuration decides whether the case should:

- Stop immediately
- Continue to the next stage
- Mark the case as failed but still run cleanup stages

Default behavior:

```text
stopOnFailure: true
```

---

# 5. Excel Test Suite Model

## 5.1 Workbook

A Test Suite may be:

- One Excel workbook
- All Excel workbooks under a directory

ATT reads configured worksheets and configured columns only.

Workbook formatting such as fonts, colors, borders, filters, merged cells and helper columns must not affect execution.

## 5.2 Input Columns

V1.2 continues to use configured Excel columns.

Example:

```yaml
testcase:
  sheet: TestCases
  columns:
    caseId: Case ID
    caseName: Case Name
    tags: Tags
    stagePre: Pre Stage Template
    stageMain: Main Stage Template
    stagePost: Post Stage Template
    debitAccount: Debit Account
    creditAccount: Credit Account
    amount: Amount
    currency: Currency
    data: Case Data
    remarks: Remarks
```

Recommended logical fields:

| Field | Example Column | Purpose |
|-------|----------------|---------|
| `caseId` | Case ID | Unique case identifier |
| `caseName` | Case Name | Human-readable case name |
| `tags` | Tags | Comma-separated selection tags |
| `stagePre` | Pre Stage Template | Template executed before main stage |
| `stageMain` | Main Stage Template | Main business execution template |
| `stagePost` | Post Stage Template | Verification template after main stage |
| `data` | Case Data | YAML data used by any stage |
| `remarks` | Remarks | Human-facing notes |

The stage fields are examples. Projects may configure different stage names.

## 5.3 Stage Columns

V1.2 uses multi-stage columns by default.

Example:

| Case ID | Tags | Pre Stage Template | Main Stage Template | Post Stage Template |
|---------|------|--------------------|---------------------|---------------------|
| TC001 | smoke,payment | PAYMENT_PREPARE | PAYMENT_INVOKE | PAYMENT_VERIFY |

ATT does not treat `Pre`, `Main`, or `Post` as special template types.

They are only ordered stage slots configured for this project.

## 5.4 Alternative Stage Configuration Example

Projects may define more operational stage names when the test flow needs setup, verification and recovery behavior.

Example configuration:

```yaml
testcase:
  sheet: TestCases
  columns:
    caseId: Case ID
    caseName: Case Name
    tags: Tags
    stagePrepare: Prepare Template
    stageInvoke: Invoke Template
    stageVerify: Verify Template
    stageCleanup: Cleanup Template
    stageRollback: Rollback Template
    data: Case Data
    remarks: Remarks

stages:
  - key: stagePrepare
    name: Prepare
    required: false
    onFailure: stop
  - key: stageInvoke
    name: Invoke
    required: true
    onFailure: rollback
  - key: stageVerify
    name: Verify
    required: true
    onFailure: rollback
  - key: stageCleanup
    name: Cleanup
    required: false
    runWhen: always
  - key: stageRollback
    name: Rollback
    required: false
    runWhen: onFailure
```

Example Excel row:

| Case ID | Tags | Prepare Template | Invoke Template | Verify Template | Cleanup Template | Rollback Template |
|---------|------|------------------|-----------------|-----------------|------------------|-------------------|
| TC101 | regression,payment | PAYMENT_PREPARE_DATA | PAYMENT_INVOKE_API | PAYMENT_VERIFY_ALL | PAYMENT_CLEAN_TEMP | PAYMENT_ROLLBACK_TXN |

Stage usage:

| Stage | Typical Usage |
|-------|---------------|
| Prepare | Prepare account state, create test data, obtain sequence numbers or render setup payloads |
| Invoke | Execute the main business action, such as API invocation or message submission |
| Verify | Check API response, database records, downstream logs, files or messages |
| Cleanup | Remove temporary data or release resources after success or failure |
| Rollback | Compensate failed execution, reverse generated data, or restore environment state |

`Cleanup` and `Rollback` are still Tool Invocation Templates. ATT does not need special cleanup or rollback engines.

The only special behavior is controlled by stage configuration such as `runWhen` and `onFailure`.

## 5.5 Case Data

Case Data is a YAML object available to all stages.

Example:

```yaml
channel: MOBILE
limitScenario: DAILY_MAX
expected:
  status: SUCCESS
  rejectCode: "0000"
  txnStatus: POSTED
```

Templates may reference these values:

```text
${channel}
${expected.status}
${expected.txnStatus}
```

---

# 6. Tool Invocation Template Model

## 6.1 Template Package

A Tool Invocation Template is a package under `templates/`.

Recommended structure:

```text
templates/
  stage/
    PAYMENT_PREPARE/
      template.yaml
      README.md

    PAYMENT_INVOKE/
      template.yaml
      payload.xml
      README.md

    PAYMENT_VERIFY/
      template.yaml
      verify.sql
      README.md
```

The directory names are project conventions. ATT only needs the configured template package path.

## 6.2 Template YAML

Example:

```yaml
name: PAYMENT_INVOKE
description: Render payment XML and invoke payment API

actions:
  renderPaymentXml:
    type: render
    payload: payload.xml

  invokePaymentApi:
    type: tool
    call: "#{invokePaymentApi(requestXml=${TOOLS.renderPaymentXml.output})}"

  assertApiStatus:
    type: assert
    expression: "${TOOLS.invokePaymentApi.output.Response.Status} == '${expected.status}'"
```

## 6.3 Template Actions

A Tool Invocation Template contains ordered actions.

Each action is one executable step inside the template.

The action `type` tells ATT how to execute that step.

The action `type` is not a business type, not a stage type, and not a template type. It is only an execution instruction for ATT.

Example:

```yaml
actions:
  buildRequestXml:
    type: render
    payload: payment_request.xml

  sendPayment:
    type: tool
    call: "#{invokePaymentApi(requestXml=${TOOLS.buildRequestXml.output})}"

  verifyApiSuccess:
    type: assert
    expression: "${TOOLS.sendPayment.output.Response.Status} == '${expected.status}'"
```

The execution sequence is:

```text
1. render payment_request.xml and store the result under `TOOLS.buildRequestXml.output`
2. call invokePaymentApi using `TOOLS.buildRequestXml.output` and store the parsed result under `TOOLS.sendPayment.output`
3. assert `TOOLS.sendPayment.output.Response.Status` against `expected.status`
```

Recommended action types:

| Type | Meaning | Typical Use |
|------|---------|-------------|
| `render` | Render a payload template and store the rendered content or file path in context | Build XML, SQL, JSON, YAML or text payload |
| `tool` | Execute a configured external tool through `#{...}` | Invoke API, query DB, read logs, grep files |
| `assert` | Evaluate an expression against context | Check API status, DB row count, log keyword |
| `log` | Write a human-readable message to the case execution log | Record stage progress or debug context |

Common action fields:

| Field | Applies To | Meaning |
|-------|------------|---------|
| `type` | all actions | Execution type used by ATT |
| `payload` | `render` | Payload template file to render |
| `call` | `tool` | Tool call expression using `#{...}` |
| `expression` | `assert` | Boolean expression evaluated after context resolution |
| `id` | all actions | Optional explicit invocation ID; defaults to the YAML action key |
| `message` | `log` | Message written to the case execution log |

The first implementation does not need to support every action type.

Minimum useful implementation:

- `render`
- `tool`
- `assert`

`log` can be added later if the project does not need it immediately.

## 6.4 Payload Templates

A payload template may be XML, SQL, YAML, JSON, text, or any project-specific format.

Examples:

```text
payload.xml
verify.sql
request.json
message.txt
```

XML is not special in V1.2.

Any stage may render XML if it needs XML.

Any stage may render SQL if it needs SQL.

Any stage may render text if it needs text.

## 6.5 Template Syntax

V1.2 keeps V1.1 syntax:

| Syntax | Meaning |
|--------|---------|
| `${path.to.value}` | Resolve value from Case Runtime Context |
| `#{toolName(...)}` | Execute configured tool |

`${}` never triggers tool execution.

`#{}` is the only syntax that triggers tool execution.

---

# 7. Case Runtime Context

## 7.1 Context Structure

```text
Case Runtime Context

├── CASE
│   ├── configured Excel columns
│   └── Case Data
│
├── RUN
│   ├── runId
│   ├── startedAt
│   ├── suitePath
│   └── reportPath
│
├── STAGE
│   ├── name
│   ├── template
│   ├── status
│   └── durationMs
│
├── TOOL
│   ├── name
│   ├── input
│   ├── output
│   ├── stdout
│   ├── stderr
│   ├── command
│   └── durationMs
│
└── TOOLS
    └── action invocation records keyed by invocation ID
```

## 7.2 Context Resolution

Resolution order:

1. Current `TOOL.input`
2. Current `TOOL.output`
3. Current `STAGE`
4. Case values and Case Data
5. Run metadata
6. Previous tool outputs in `TOOLS`

Recommended explicit forms:

| Form | Meaning |
|------|---------|
| `${CaseID}` | Short form for case ID |
| `${CASE.CaseID}` | Explicit case value |
| `${RUN.runId}` | Current run identifier |
| `${STAGE.name}` | Current stage name |
| `${TOOL.output.a.b.c}` | Current parsed tool output |
| `${TOOLS.invokeApi.output.Response.Status}` | Parsed output from action invocation `invokeApi` |
| `${TOOLS.renderRequest.output}` | Rendered payload output from action invocation `renderRequest` |
| `${TOOLS.queryTxn.rawOutput}` | Raw output from action invocation `queryTxn` |
| `${TOOLS.queryTxn.input}` | Resolved input passed to action invocation `queryTxn` |
| `${TOOLS.queryTxn.stdout}` | Standard output captured from action invocation `queryTxn` |
| `${TOOLS.queryTxn.stderr}` | Standard error captured from action invocation `queryTxn` |

## 7.3 Action Invocation References

V1.2 does not use a separate injection mechanism as a core feature.

Each action invocation is stored in `TOOLS` by invocation ID.

The invocation ID is:

1. The explicit `id` field if provided
2. Otherwise the YAML action key

The invocation ID must be unique within one case execution.

If two stages use the same action key, the template should provide an explicit `id`.

Recommended explicit ID format:

```text
<stageName>_<actionName>
```

Example:

```yaml
actions:
  createEndToEndId:
    id: prepare_createEndToEndId
    type: tool
    call: "#{genEndToEndId(caseId=${CaseID})}"
```

The explicit ID `prepare_createEndToEndId` becomes the invocation ID.

Later actions can reference the output directly:

```yaml
actions:
  invokeApi:
    type: tool
    call: "#{invokePaymentApi(e2eId=${TOOLS.prepare_createEndToEndId.output})}"
```

Or inside a payload template:

```xml
<EndToEndId>${TOOLS.prepare_createEndToEndId.output}</EndToEndId>
```

## 7.3.1 Invocation Record Fields

Each `TOOLS.<InvocationId>` record may contain:

| Field | Meaning |
|-------|---------|
| `type` | Action type, such as `render`, `tool`, or `assert` |
| `tool` | Tool name for `tool` actions |
| `input` | Resolved input object |
| `output` | Parsed output or rendered payload result |
| `rawOutput` | Raw text output before parsing |
| `stdout` | Captured standard output |
| `stderr` | Captured standard error |
| `command` | Resolved command for tool actions |
| `status` | Action status |
| `durationMs` | Action duration |

## 7.3.2 Example: Extract TxnRef From API Response

An API response may be large. Later actions may only need the transaction reference.

```yaml
actions:
  invokeApi:
    type: tool
    call: "#{invokePaymentApi(requestXml=${TOOLS.renderRequest.output})}"

  selectTxn:
    type: tool
    call: "#{selectCtxn(txn.ref=${TOOLS.invokeApi.output.Response.TxnRef})}"
```

No duplicate context key is needed.

The source of the value remains clear:

```text
TOOLS.invokeApi.output.Response.TxnRef
```

## 7.3.3 Example: Cleanup Uses Previous Action Output

Verify stage may discover a temporary transaction ID that Cleanup or Rollback needs.

```yaml
actions:
  queryTxn:
    type: tool
    call: "#{selectCtxn(caseId=${CaseID})}"
```

Cleanup stage:

```yaml
actions:
  deleteTempTxn:
    type: tool
    call: "#{deleteTxn(txnId=${TOOLS.queryTxn.output.rows[0].txnId})}"
```

---

# 8. Stage Execution Flow

## 8.1 Case-Level Flow

```text
Start Case
  │
  ▼
Create Case Runtime Context
  │
  ▼
Open Case Execution Log
  │
  ▼
Execute Stage 1
  │
  ▼
Execute Stage 2
  │
  ▼
Execute Stage N
  │
  ▼
Calculate Case Result
  │
  ▼
Write Run History
  │
  ▼
Write Result Workbook Row
```

## 8.2 Stage-Level Flow

```text
Start Stage
  │
  ▼
Load Tool Invocation Template
  │
  ▼
For Each Action
  │
  ├── Render payload if needed
  ├── Execute tool if needed
  ├── Evaluate assertion if needed
  ├── Inject context if needed
  └── Append details to case log
  │
  ▼
Mark Stage Result
```

## 8.3 Failure Handling

Each stage may define:

```yaml
onFailure: stop
```

Supported values:

| Value | Meaning |
|-------|---------|
| `stop` | Stop the case and mark failed |
| `continue` | Continue next action or stage, but mark current action failed |
| `cleanup` | Continue only stages marked as cleanup |

Default:

```text
stop
```

---

# 9. Case Execution Log

## 9.1 Log File Naming

Each ATT execution creates one run directory:

```text
output/<RunID>/
```

`RunID` identifies one ATT execution.

Default `RunID` is generated from the run start timestamp:

```text
yyyyMMdd-HHmmss
```

Users may override it from CLI:

```sh
./att.sh --run-id SIT-20260709-01
```

Example:

```text
output/20260709-192355/
```

If a specified or generated `RunID` already exists, ATT should fail fast unless overwrite behavior is explicitly added in a later version.

Each case execution writes one log file under the run directory:

```text
output/<RunID>/<CaseID>/<CaseID>.<yyyyMMdd>.<HHmmss>.<seq>.log
```

Example:

```text
output/20260709-192355/TC001/TC001.20260709.192355.001.log
```

`seq` is used when the same case is executed more than once in the same run.

## 9.2 Log Content

The log contains ordered sections.

Example:

```text
[CASE]
caseId: TC001
caseName: ATM HKD transfer success
startedAt: 2026-07-09T19:23:55+08:00

[STAGE 001]
name: Pre
template: PAYMENT_PREPARE
startedAt: 2026-07-09T19:23:55+08:00

[ACTION 001.001]
name: selectAccount
type: tool
tool: selectCtxn
command: ./tools/select_ct_txn.sh --input ...
input:
  txn:
    ref: "111111"
stdout: |
  ...
stderr: |
  ...
rawOutput: |
  effectRows: 1
parsedOutput:
  effectRows: 1
durationMs: 28
status: PASS
```

## 9.3 Log Rules

ATT should:

- Append all stage and tool details in execution order
- Keep enough detail for troubleshooting without creating per-tool artifact files
- Record command, input, stdout, stderr, raw output, parsed output, duration, and status
- Record assertion expressions and rendered actual values
- Record final case result

ATT should not create separate per-tool logs.

The case log is the only required detailed execution artifact for one case.

---

# 10. Report Workbook Generation

## 10.1 Report Strategy

V1.2 generates report output by copying the source Excel workbook.

Default behavior:

```text
Source workbook: testcase/payment_regression.xlsx
Run directory: output/20260709-192355/
Result workbook: output/20260709-192355/payment_regression.result.xlsx
```

The source workbook is not overwritten.

V1.2 does not require a separate `report/` directory.

The result workbook, run history and case logs are stored together under `output/<RunID>/`.

## 10.2 Result Columns

Result columns are configured in `config.yaml`.

Example:

```yaml
report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns:
    result: Test Result
    durationMs: Duration(ms)
    actualResult: Actual Result
    caseLog: Case Log
    runTime: Run Time
```

ATT appends these columns after the existing configured or used columns.

Recommended result fields:

| Field | Purpose |
|-------|---------|
| `result` | Final case result: PASS, FAIL, ERROR, SKIPPED |
| `durationMs` | Case execution duration |
| `actualResult` | Summary of stage/action results |
| `caseLog` | Path to case execution log |
| `runTime` | Execution timestamp |
| `runId` | Optional run identifier |
| `failedStage` | Optional failed stage name |
| `failedAction` | Optional failed action name |

## 10.3 Row Mapping

ATT maps results back to workbook rows by `Case ID`.

If duplicate Case IDs exist in one workbook, ATT should mark those rows as invalid and not execute them by default.

## 10.4 Formatting

ATT should preserve source workbook formatting where practical.

For appended columns, ATT may apply simple formatting:

- Header style copied from nearby header cells
- Result value color coding
- Wrapped text for actual result
- Hyperlink or plain path for case log

---

# 11. ATT Command Line Tool

## 11.1 Entrypoint

V1.2 adds:

```text
att.sh
```

`att.sh` is the recommended command line interface.

It wraps Java invocation and hides classpath or Maven details from users.

## 11.2 Supported Options

| Option | Meaning |
|--------|---------|
| `--config <file>` | Use a specific config file |
| `--suite <xlsx>` | Run one Excel workbook |
| `--suite-dir <dir>` | Run all Excel workbooks in a directory |
| `--case-id <id>` | Include a specific case ID |
| `--tag <tag>` | Include cases with tag |
| `--exclude-tag <tag>` | Exclude cases with tag |
| `--rerun-failed` | Run failed cases from latest run history |
| `--dry-run` | Print selected cases without executing them |
| `--fail-fast` | Stop the run after the first failed or error case |
| `--run-id <id>` | Override generated run ID; default is timestamp `yyyyMMdd-HHmmss` |
| `--output-dir <dir>` | Override output directory |

## 11.3 Selection Rules

Case selection uses all configured filters.

Default selection:

```text
all enabled cases in selected workbook(s)
```

If `--tag` is supplied, a case must contain at least one included tag.

If `--exclude-tag` is supplied, a case must not contain any excluded tag.

If `--case-id` is supplied, a case must match one of the specified case IDs.

If `--rerun-failed` is supplied, ATT starts from failed cases in latest run history, then applies additional filters.

## 11.4 Examples

Run all cases in one workbook:

```sh
./att.sh --suite testcase/payment_regression.xlsx
```

Run all Excel workbooks in a directory:

```sh
./att.sh --suite-dir testcase
```

Run smoke cases except slow cases:

```sh
./att.sh --tag smoke --exclude-tag slow
```

Rerun latest failed cases:

```sh
./att.sh --rerun-failed
```

Preview selected cases:

```sh
./att.sh --dry-run
```

Run a specific case:

```sh
./att.sh --suite testcase/payment_regression.xlsx --case-id TC001
```

Run with an explicit run ID:

```sh
./att.sh --suite testcase/payment_regression.xlsx --run-id SIT-20260709-01
```

---

# 12. Run History And Rerun Failed

## 12.1 Run History

ATT should write a machine-readable run history file after each run.

Recommended path:

```text
output/latest-run.yaml
output/<RunID>/run.yaml
```

`output/latest-run.yaml` is a small pointer or copy of the latest run history.

`output/<RunID>/run.yaml` is the full run history for that execution.

Example:

```yaml
runId: "20260709-192355"
runDirectory: "output/20260709-192355"
startedAt: "2026-07-09T19:23:55+08:00"
endedAt: "2026-07-09T19:25:10+08:00"
config: "config/config.yaml"
resultWorkbook: "output/20260709-192355/payment_regression.result.xlsx"
selection:
  runIdSource: "timestamp"
  suite:
    - "testcase/payment_regression.xlsx"
  includeTags:
    - smoke
  excludeTags:
    - slow
summary:
  total: 20
  passed: 18
  failed: 2
  error: 0
  skipped: 0
cases:
  - suite: "testcase/payment_regression.xlsx"
    sheet: "TestCases"
    row: 2
    caseId: "TC001"
    status: "PASS"
    caseLog: "output/20260709-192355/TC001/TC001.20260709.192355.001.log"
  - suite: "testcase/payment_regression.xlsx"
    sheet: "TestCases"
    row: 7
    caseId: "TC006"
    status: "FAIL"
    caseLog: "output/20260709-192355/TC006/TC006.20260709.192355.001.log"
```

## 12.2 Rerun Failed

`--rerun-failed` uses the latest run history by default.

Failed statuses:

- `FAIL`
- `ERROR`
- Stage-specific failure statuses if implementation keeps them

Skipped cases are not rerun by default.

If there is no latest run history, ATT should return a clear error:

```text
No run history found. Cannot use --rerun-failed.
```

---

# 13. Configuration Model

## 13.1 Example

```yaml
outputDirectory: output
environment: SIT
timeoutSeconds: 120

run:
  id:
    default: timestamp
    timestampFormat: yyyyMMdd-HHmmss

testcase:
  sheet: TestCases
  columns:
    caseId: Case ID
    caseName: Case Name
    tags: Tags
    stagePre: Pre Stage Template
    stageMain: Main Stage Template
    stagePost: Post Stage Template
    data: Case Data
    remarks: Remarks

stages:
  - key: stagePre
    name: Pre
    required: false
    onFailure: stop
  - key: stageMain
    name: Main
    required: true
    onFailure: stop
  - key: stagePost
    name: Post
    required: false
    onFailure: stop

templates:
  root: templates/stage

tools:
  invokePaymentApi:
    name: Invoke Payment API
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile}"
    output: xml
  selectCtxn:
    name: Select CT records
    command: "./tools/select_ct_txn.sh --input ${TOOL.inputFile}"
    output: yaml

report:
  mode: append-to-copy
  fileNamePattern: "${suiteName}.result.xlsx"
  columns:
    result: Test Result
    durationMs: Duration(ms)
    actualResult: Actual Result
    caseLog: Case Log
    runTime: Run Time
```

## 13.2 Configuration Principles

- Excel column names are project-configurable.
- Stage names and stage count are project-configurable.
- Template directory layout is project-configurable.
- Result report columns are project-configurable.
- Each run stores case logs, run history and result workbooks under `output/<RunID>/`.
- Run ID defaults to timestamp and may be overridden by `att.sh --run-id`.
- Tools remain external commands.
- ATT should not hard-code request/check terminology in V1.2 runtime behavior.

---

# 14. Migration From V1.1

## 14.1 Concept Mapping

| V1.1 | V1.2 |
|------|------|
| `PreCheck Template` | `Pre Stage Template` |
| `Request Template` | `Main Stage Template` or any configured stage |
| `api.invocation.yaml` | Action inside a Tool Invocation Template |
| `PostCheck Template` | `Post Stage Template` |
| Request XML template | Payload template |
| `output/<case>/tools/<seq>_<tool>/...` | `output/<case>/<case>.<date>.<time>.<seq>.log` |
| Separate report workbook | Copied source workbook with appended result columns |

## 14.2 Migration Example

V1.1 columns:

```text
PreCheck Template
Request Template
PostCheck Template
```

V1.2 columns:

```text
Pre Stage Template
Main Stage Template
Post Stage Template
```

V1.1 request package:

```text
templates/request/PAYMENT_TRANSFER/template.xml
templates/request/PAYMENT_TRANSFER/api.invocation.yaml
```

V1.2 stage package:

```text
templates/stage/PAYMENT_INVOKE/template.yaml
templates/stage/PAYMENT_INVOKE/payload.xml
```

V1.1 post-check package:

```text
templates/check/PAYMENT_POSTCHECK/template.yaml
```

V1.2 stage package:

```text
templates/stage/PAYMENT_VERIFY/template.yaml
```

---

# 15. Examples

## 15.1 Three-Stage Case

Excel row:

| Case ID | Tags | Pre Stage Template | Main Stage Template | Post Stage Template | Case Data |
|---------|------|--------------------|---------------------|---------------------|-----------|
| TC001 | smoke,payment | PAYMENT_PREPARE | PAYMENT_INVOKE | PAYMENT_VERIFY | `channel: ATM` |

## 15.2 Main Stage Rendering XML And Calling API

`templates/stage/PAYMENT_INVOKE/template.yaml`:

```yaml
name: PAYMENT_INVOKE

actions:
  renderRequest:
    type: render
    payload: payload.xml

  invokeApi:
    type: tool
    call: "#{invokePaymentApi(requestXml=${TOOLS.renderRequest.output})}"
```

`templates/stage/PAYMENT_INVOKE/payload.xml`:

```xml
<PaymentTransferRequest>
  <CaseId>${CaseID}</CaseId>
  <DebitAccount>${DebitAccount}</DebitAccount>
  <CreditAccount>${CreditAccount}</CreditAccount>
  <Amount>${Amount}</Amount>
  <Currency>${Currency}</Currency>
  <Channel>${channel}</Channel>
</PaymentTransferRequest>
```

## 15.3 Post Stage Checking DB, Logs And API Response

```yaml
name: PAYMENT_VERIFY

actions:
  checkApiStatus:
    type: assert
    expression: "${TOOLS.invokeApi.output.Response.Status} == '${expected.status}'"

  selectTxn:
    type: tool
    call: "#{selectCtxn(txn.ref=${CaseID})}"

  checkTxnStatus:
    type: assert
    expression: "${TOOLS.selectTxn.output.record.status} like '%${expected.txnStatus}%'"

  getLogs:
    type: tool
    call: "#{getAppLogs(caseId=${CaseID})}"

  checkLogs:
    type: assert
    expression: "${TOOLS.getLogs.output} like '%PAYMENT%POSTED%'"
```

## 15.4 Payload Template In A Check Stage

A check stage may render SQL before invoking a DB tool.

`templates/stage/PAYMENT_VERIFY/select_txn.sql`:

```sql
select *
from CTXN
where CASE_ID = '${CaseID}'
  and TXN_STATUS = '${expected.txnStatus}'
```

`template.yaml`:

```yaml
actions:
  renderSql:
    type: render
    payload: select_txn.sql

  queryTxn:
    type: tool
    call: "#{queryDb(sql=${TOOLS.renderSql.output})}"
```

This shows that payload templates are not request-only.

## 15.5 CLI Examples

```sh
./att.sh --suite testcase/payment_regression.xlsx
```

```sh
./att.sh --suite-dir testcase
```

```sh
./att.sh --tag smoke --exclude-tag slow
```

```sh
./att.sh --rerun-failed
```

```sh
./att.sh --dry-run
```

---

# 16. Acceptance Criteria

V1.2 design is considered complete when the following statements are true:

- Request Template, Check Template, and API Invocation Template are replaced by the generic Tool Invocation Template concept.
- Multi-stage execution is documented without hard-coding request/check semantics.
- XML template rendering is documented as one kind of payload rendering usable by any stage.
- A single case execution log replaces per-tool artifact files.
- The run output directory is defined as `output/<RunID>/`.
- The case log naming format is defined as `output/<RunID>/<CaseID>/<CaseID>.<yyyyMMdd>.<HHmmss>.<seq>.log`.
- The case log content includes command, input, stdout, stderr, output, parsed output, duration, and status.
- Report generation copies the source Excel workbook into `output/<RunID>/` and appends configured result columns.
- Source Excel is not overwritten by default.
- Report columns are configured in `config.yaml`.
- No separate `report/` directory is required.
- `att.sh` is documented as the user-facing CLI.
- CLI examples cover suite file, suite directory, include tag, exclude tag, rerun failed, run ID override, and dry-run.
- Run ID defaults to timestamp `yyyyMMdd-HHmmss` and can be specified by `att.sh --run-id`.
- Rerun failed is based on machine-readable run history.
- Migration from V1.1 concepts to V1.2 concepts is documented.
